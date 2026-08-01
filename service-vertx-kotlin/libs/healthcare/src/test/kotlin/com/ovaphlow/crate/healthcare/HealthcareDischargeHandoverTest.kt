package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * HealthcareService 养老离院交接摘要（DISCHARGE_SUMMARY）的非数据库测试。
 *
 * 使用 mockk 模拟 Pool.withTransaction 与 SqlConnection/RowSet，不访问数据库：
 *   - 输入校验：author 必填/去空白/长度，handover_note 可选/类型/长度，客户端注入字段被忽略
 *   - 资格错误映射：不存在 404、非养老 400、未离院/缺周期/周期非 COMPLETED/日期不一致/患者不一致 409
 *   - 幂等：首次 201、相同输入重试 200 同一 ID、不同输入 409
 *   - 快照内容：服务端构建的 content_blocks 为空数组/零计数/最小患者字段，不含敏感身份数据
 *   - 护理记录快照按业务记录时间（metadata.record_time）稳定排序
 *   - 嵌入式 HTTP 路由：静态路径不被泛型 encounter 路由吞掉，201/200/409/400/404 状态码正确
 */
@ExtendWith(VertxExtension::class)
class HealthcareDischargeHandoverTest {

    // ——— mock 基础设施 ———

    private fun mockRow(values: Map<String, Any?>): Row {
        val row = mockk<Row>()
        every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
        every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
        every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
        every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
        every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
        return row
    }

    private fun rowSet(vararg rows: Row): RowSet<Row> {
        val rs = mockk<RowSet<Row>>()
        // 每次 iterator() 返回新的迭代器，同一 RowSet 可被多次请求复用
        every { rs.iterator() } answers {
            val delegate = rows.iterator()
            val rowIterator = mockk<RowIterator<Row>>()
            every { rowIterator.hasNext() } answers { delegate.hasNext() }
            every { rowIterator.next() } answers { delegate.next() }
            rowIterator
        }
        every { rs.size() } returns rows.size
        return rs
    }

    private fun rows(vararg values: Map<String, Any?>): RowSet<Row> = rowSet(*values.map { mockRow(it) }.toTypedArray())

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    /** 每个 preparedQuery 按 SQL 结构特征分发；返回 (conn, 捕获的 insert 参数) */
    private fun stubConnection(
        encounters: RowSet<Row>,
        periods: RowSet<Row>,
        existingHandover: RowSet<Row>,
        patients: RowSet<Row>,
        nursingRecords: RowSet<Row>,
        readBack: RowSet<Row>,
    ): Triple<SqlConnection, MutableList<String>, Pool> {
        val conn = mockk<SqlConnection>()
        val insertPayloads = mutableListOf<String>()
        var lastSql = ""
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { conn.preparedQuery(any<String>()) } answers {
            lastSql = normalized(firstArg<String>())
            pq
        }
        every { conn.preparedQuery(any<String>(), any()) } answers {
            lastSql = normalized(firstArg<String>())
            pq
        }
        every { pq.execute(any<Tuple>()) } answers {
            val sql = lastSql
            val tuple = firstArg<Tuple>()
            // 捕获插入参数（JSONB 在 tuple 中为 JsonArray/JsonObject）
            for (i in 0 until tuple.size()) {
                val v = tuple.getValue(i)
                val text = when (v) {
                    is JsonArray -> v.encode()
                    is JsonObject -> v.encode()
                    else -> v?.toString() ?: ""
                }
                if (text.contains("snapshot_version")) insertPayloads.add(text)
            }
            val rs = when {
                sql.contains("nursing_assessments") || sql.contains("nursing_plans") ||
                    sql.contains("nursing_plan_items") || sql.contains("nursing_tasks") ||
                    sql.contains("nursing_task_executions") -> rowSet()
                sql.contains("nursing_service_periods") -> periods
                sql.contains("from healthcare.patients") -> patients
                sql.contains("from healthcare.encounters") -> encounters
                sql.contains("insert into healthcare.medical_records") -> rowSet()
                sql.contains("from healthcare.medical_records") && sql.contains("for update") -> existingHandover
                // selectFrom 读回：WHERE 按 id 定位（列清单含 record_type）
                sql.contains("from healthcare.medical_records") && sql.contains("id = $1") -> readBack
                sql.contains("from healthcare.medical_records") -> nursingRecords
                else -> rowSet()
            }
            Future.succeededFuture(rs)
        }
        val pool = mockk<Pool>()
        every { pool.withTransaction<Any>(any()) } answers {
            val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
            handler.apply(conn)
        }
        return Triple(conn, insertPayloads, pool)
    }

    // ——— fixture 行 ———

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260731001",
            "department" to "三楼",
            "ward" to "301-1",
            "admit_date" to OffsetDateTime.parse("2026-07-01T00:00:00+08:00"),
            "discharge_date" to OffsetDateTime.parse("2026-07-31T00:00:00+08:00"),
            "admitting_diagnosis" to "高血压",
            "discharge_diagnosis" to "病情稳定",
            "attending_physician" to "赵医生",
            "status" to "DISCHARGED",
            "metadata" to JsonObject(),
            "created_at" to OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-07-31T10:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun periodRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "per-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.of(2026, 7, 1),
            "end_date" to LocalDate.of(2026, 7, 31),
            "coordinator" to "钱协调",
            "encounter_id" to "enc-1",
            "status" to "COMPLETED",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-07-31T10:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun patientRow(): Map<String, Any?> = mapOf(
        "id" to "pat-1",
        "name" to "张三",
        "gender" to "男",
        "birth_date" to LocalDate.of(1940, 1, 1),
        "emergency_contact" to JsonObject().put("name", "张四").put("phone", "13800000000"),
        "allergies" to JsonArray().add(JsonObject().put("allergen", "青霉素")),
        "past_history" to "高血压病史",
    )

    private fun handoverRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "rec-1",
            "record_type" to "DISCHARGE_SUMMARY",
            "title" to "养老照护离院交接摘要",
            "encounter_id" to "enc-1",
            "physician" to "王护理师",
            "content" to null,
            "content_blocks" to JsonArray().add(
                JsonObject().put("snapshot_version", 1).put("snapshot", JsonObject()),
            ),
            "metadata" to JsonObject()
                .put("is_elderly_discharge_handover", true)
                .put("period_id", "per-1")
                .put("snapshot_version", 1)
                .put("generated_at", "2026-07-31T10:15:00+08:00"),
            "record_date" to LocalDate.of(2026, 7, 31),
            "created_at" to OffsetDateTime.parse("2026-07-31T10:15:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-07-31T10:15:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun causeOf(future: Future<*>): Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get()
            throw AssertionError("expected future to fail")
        } catch (error: Throwable) {
            var cause = error
            while (cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException) {
                cause = cause.cause ?: break
            }
            return cause
        }
    }

    private fun happyStub(): Pair<Pool, MutableList<String>> {
        val (_, payloads, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rowSet(),
            patients = rows(patientRow()),
            nursingRecords = rowSet(),
            readBack = rows(handoverRow(mapOf("content" to "已交接"))),
        )
        return Pair(pool, payloads)
    }

    // ——— 创建与幂等 ———

    @Test
    fun `首次创建返回201且快照由服务端构建`() {
        val (pool, payloads) = happyStub()
        val service = HealthcareService(pool)

        val (created, handover) = service.createElderlyDischargeHandover(
            "enc-1",
            JsonObject().put("author", "  王护理师  ").put("handover_note", "  已交接  "),
        ).toCompletionStage().toCompletableFuture().get()

        assertTrue(created, "首次创建必须返回 created=true")
        assertEquals("DISCHARGE_SUMMARY", handover.getString("record_type"))
        assertEquals("养老照护离院交接摘要", handover.getString("title"))
        assertEquals("enc-1", handover.getString("encounter_id"))
        assertEquals("per-1", handover.getString("period_id"))
        assertEquals("2026-07-31", handover.getString("record_date"), "record_date 必须等于离院业务日期")
        assertEquals("王护理师", handover.getString("author"), "author 必须去空白")
        assertEquals("已交接", handover.getString("handover_note"), "备注必须去首尾空白")
        assertEquals(1, handover.getInteger("snapshot_version"))

        // 服务端写入的 content_blocks：空数组、零计数、最小患者字段、不含敏感数据
        val metaPayload = payloads.first { it.contains("is_elderly_discharge_handover") }
        val meta = JsonObject(metaPayload)
        assertTrue(meta.getBoolean("is_elderly_discharge_handover", false), "必须带内部归档标记")
        assertEquals("per-1", meta.getString("period_id"))
        val blocksPayload = payloads.first { it.contains("\"snapshot\"") && !it.contains("is_elderly_discharge_handover") }
        val blocks = JsonArray(blocksPayload)
        val snapshot = blocks.getJsonObject(0).getJsonObject("snapshot")
        assertEquals(0, snapshot.getJsonArray("assessments").size())
        assertEquals(0, snapshot.getJsonArray("plans").size())
        assertEquals(0, snapshot.getJsonArray("tasks").size())
        assertEquals(0, snapshot.getJsonArray("nursing_records").size())
        val summary = snapshot.getJsonObject("execution_summary")
        assertEquals(0, summary.getInteger("COMPLETED"))
        assertEquals("张三", snapshot.getJsonObject("patient").getString("name"))
        assertEquals("男", snapshot.getJsonObject("patient").getString("gender"))
        // 敏感字段不得进入快照
        val patientJson = snapshot.getJsonObject("patient").encode()
        assertFalse(patientJson.contains("id_card_no"), "快照不得包含身份证号")
        assertFalse(patientJson.contains("address"), "快照不得包含地址")
        assertFalse(patientJson.contains("medical_insurance"), "快照不得包含医保号")
    }

    @Test
    fun `UTC读取的离院时间仍按上海业务日期归档`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow(mapOf("discharge_date" to OffsetDateTime.parse("2026-07-30T16:00:00Z")))),
            periods = rows(periodRow()),
            existingHandover = rowSet(),
            patients = rows(patientRow()),
            nursingRecords = rowSet(),
            readBack = rows(handoverRow()),
        )
        val service = HealthcareService(pool)

        val (created, handover) = service.createElderlyDischargeHandover(
            "enc-1",
            JsonObject().put("author", "王护理师"),
        ).toCompletionStage().toCompletableFuture().get()

        assertTrue(created)
        assertEquals("2026-07-31", handover.getString("record_date"))
    }

    @Test
    fun `相同输入重试返回200同一ID`() {
        val (_, payloads, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rows(handoverRow()),
            patients = rows(patientRow()),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)

        val (created, handover) = service.createElderlyDischargeHandover(
            "enc-1",
            JsonObject().put("author", "王护理师"),
        ).toCompletionStage().toCompletableFuture().get()

        assertFalse(created, "相同输入重试必须返回 created=false")
        assertEquals("rec-1", handover.getString("id"), "必须返回既有同一 ID")
    }

    @Test
    fun `不同输入重试返回409且不覆盖`() {
        val (_, payloads, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rows(handoverRow()),
            patients = rows(patientRow()),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)

        val cause = causeOf(
            service.createElderlyDischargeHandover(
                "enc-1",
                JsonObject().put("author", "李护理师").put("handover_note", "不同备注"),
            ),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("different") == true, "got: ${cause.message}")
    }

    // ——— 输入校验 ———

    @Test
    fun `author缺失或空白返回400`() {
        val (pool, _) = happyStub()
        val service = HealthcareService(pool)

        val cause1 = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject()))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertEquals("author is required", cause1.message)

        val cause2 = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "   ")))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertEquals("author is required", cause2.message)
    }

    @Test
    fun `author或备注超长返回400`() {
        val (pool, _) = happyStub()
        val service = HealthcareService(pool)

        val cause1 = causeOf(
            service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "a".repeat(101))),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("100") == true, "got: ${cause1.message}")

        val cause2 = causeOf(
            service.createElderlyDischargeHandover(
                "enc-1",
                JsonObject().put("author", "王护理师").put("handover_note", "b".repeat(2001)),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("2000") == true, "got: ${cause2.message}")
    }

    @Test
    fun `handover_note类型错误返回400`() {
        val (pool, _) = happyStub()
        val service = HealthcareService(pool)

        val cause = causeOf(
            service.createElderlyDischargeHandover(
                "enc-1",
                JsonObject().put("author", "王护理师").put("handover_note", 123),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("handover_note must be a string") == true, "got: ${cause.message}")
    }

    @Test
    fun `客户端注入snapshot或metadata字段被忽略`() {
        val (pool, payloads) = happyStub()
        val service = HealthcareService(pool)

        service.createElderlyDischargeHandover(
            "enc-1",
            JsonObject()
                .put("author", "王护理师")
                .put("snapshot", JsonObject().put("patient", JsonObject().put("name", "注入者")))
                .put("period_id", "evil-period")
                .put("record_type", "OTHER")
                .put("record_date", "2020-01-01")
                .put("metadata", JsonObject().put("hacked", true)),
        ).toCompletionStage().toCompletableFuture().get()

        // 服务端 metadata 只含受控字段，period_id 为锁定的精确周期
        val metaPayload = payloads.first { it.contains("is_elderly_discharge_handover") }
        val parsed = JsonObject(metaPayload)
        assertEquals("per-1", parsed.getString("period_id"), "客户端提交的 period_id 必须被忽略")
        assertNull(parsed.getValue("hacked"), "客户端 metadata 不得进入归档 metadata")
        val blocksPayload = payloads.first { it.contains("\"snapshot\"") && !it.contains("is_elderly_discharge_handover") }
        val blocks = JsonArray(blocksPayload)
        val snapshot = blocks.getJsonObject(0).getJsonObject("snapshot")
        assertEquals("张三", snapshot.getJsonObject("patient").getString("name"), "快照必须由服务端构建")
    }

    @Test
    fun `护理记录快照按业务记录时间优先稳定排序`() {
        val conn = mockk<SqlConnection>()
        val queries = mutableListOf<String>()
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { conn.preparedQuery(any<String>()) } answers {
            val sql = normalized(firstArg<String>())
            queries.add(sql)
            pq
        }
        every { conn.preparedQuery(any<String>(), any()) } answers {
            val sql = normalized(firstArg<String>())
            queries.add(sql)
            pq
        }
        every { pq.execute(any<Tuple>()) } answers {
            val sql = queries.last()
            val rs = when {
                sql.contains("nursing_assessments") || sql.contains("nursing_plans") ||
                    sql.contains("nursing_plan_items") || sql.contains("nursing_tasks") ||
                    sql.contains("nursing_task_executions") -> rowSet()
                sql.contains("nursing_service_periods") -> rows(periodRow())
                sql.contains("from healthcare.patients") -> rows(patientRow())
                sql.contains("from healthcare.encounters") -> rows(encounterRow())
                sql.contains("insert into healthcare.medical_records") -> rowSet()
                sql.contains("from healthcare.medical_records") && sql.contains("for update") -> rowSet()
                sql.contains("from healthcare.medical_records") && sql.contains("id = $1") -> rows(handoverRow())
                else -> rowSet()
            }
            Future.succeededFuture(rs)
        }
        val pool = mockk<Pool>()
        every { pool.withTransaction<Any>(any()) } answers {
            val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
            handler.apply(conn)
        }
        val service = HealthcareService(pool)

        service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师"))
            .toCompletionStage().toCompletableFuture().get()

        val recordsSql = queries.last { it.contains("from healthcare.medical_records") && !it.contains("for update") && !it.contains("id = $1") }
        val orderStart = recordsSql.indexOf("order by")
        assertTrue(orderStart >= 0, "护理记录查询必须带稳定排序: $recordsSql")
        val orderClause = recordsSql.substring(orderStart)
        assertTrue(
            orderClause.indexOf("record_time") < orderClause.indexOf("created_at"),
            "护理记录必须按业务记录时间 record_time 优先于 created_at 排序: $recordsSql",
        )
    }

    // ——— 资格错误映射 ———

    @Test
    fun `不存在encounter返回404`() {
        val (_, _, pool) = stubConnection(
            encounters = rowSet(),
            periods = rowSet(),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("missing", JsonObject().put("author", "王护理师")))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("encounter not found") == true, "got: ${cause.message}")
    }

    @Test
    fun `非养老encounter返回400`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))),
            periods = rowSet(),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师")))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("not an elderly admission") == true, "got: ${cause.message}")
    }

    @Test
    fun `未离院encounter返回409`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow(mapOf("status" to "ACTIVE", "discharge_date" to null))),
            periods = rowSet(),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师")))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("not discharged") == true, "got: ${cause.message}")
    }

    @Test
    fun `缺少精确关联周期返回409`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rowSet(),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师")))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("no bound nursing care period") == true, "got: ${cause.message}")
    }

    @Test
    fun `周期非COMPLETED返回409`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("status" to "ACTIVE"))),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师")))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("not completed") == true, "got: ${cause.message}")
    }

    @Test
    fun `周期结束日期与离院日期不一致返回409`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("end_date" to LocalDate.of(2026, 8, 1)))),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师")))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("end date does not match") == true, "got: ${cause.message}")
    }

    @Test
    fun `周期与encounter患者不一致返回409`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("patient_id" to "pat-other"))),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.createElderlyDischargeHandover("enc-1", JsonObject().put("author", "王护理师")))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("patient_id mismatch") == true, "got: ${cause.message}")
    }

    @Test
    fun `获取无既有摘要返回404`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val cause = causeOf(service.getElderlyDischargeHandover("enc-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("not found") == true, "got: ${cause.message}")
    }

    @Test
    fun `获取既有摘要返回200形态对象`() {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rows(handoverRow()),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val service = HealthcareService(pool)
        val handover = service.getElderlyDischargeHandover("enc-1").toCompletionStage().toCompletableFuture().get()
        assertEquals("rec-1", handover.getString("id"))
        assertEquals("per-1", handover.getString("period_id"))
        assertNotNull(handover.getJsonObject("snapshot"))
    }

    @Test
    fun `护理记录更正入口拒绝DISCHARGE_SUMMARY归档文书`() {
        // 更正入口查询按 record_type='NURSING_RECORD' 过滤，归档文书查不到 → 404
        val pool = mockk<Pool>()
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } returns pq
        every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(rowSet())
        val service = HealthcareService(pool)

        val cause = causeOf(
            service.createNursingRecordCorrection("rec-1", JsonObject().put("content", "更正")),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("nursing record not found") == true, "got: ${cause.message}")
    }

    // ——— 嵌入式 HTTP 路由 ———

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body.encode())
                else req.send()
            }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    @Test
    fun `POST路由首次201重试200不同输入409`(vertx: Vertx, ctx: VertxTestContext) {
        // 首次：无既有文书
        val (_, _, firstPool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rowSet(),
            patients = rows(patientRow()),
            nursingRecords = rowSet(),
            readBack = rows(handoverRow()),
        )
        val router = Router.router(vertx)
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, firstPool))
        vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            val port = server.actualPort()
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/elderly-admissions/enc-1/discharge-handover",
                JsonObject().put("author", "王护理师"),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "首次创建必须 201")
                    assertEquals("rec-1", body.getString("id"))
                }
                // 第二次：既有文书相同输入 → 200
                val (_, _, secondPool) = stubConnection(
                    encounters = rows(encounterRow()),
                    periods = rows(periodRow()),
                    existingHandover = rows(handoverRow()),
                    patients = rowSet(),
                    nursingRecords = rowSet(),
                    readBack = rowSet(),
                )
                val router2 = Router.router(vertx)
                router2.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, secondPool))
                vertx.createHttpServer().requestHandler(router2).listen(0).compose { server2 ->
                    val port2 = server2.actualPort()
                    httpRequest(
                        vertx, port2, HttpMethod.POST,
                        "/healthcare/v1/elderly-admissions/enc-1/discharge-handover",
                        JsonObject().put("author", "王护理师"),
                    ).compose { (status2, body2) ->
                        ctx.verify {
                            assertEquals(200, status2, "相同输入重试必须 200")
                            assertEquals("rec-1", body2.getString("id"))
                        }
                        // 第三次：不同输入 → 409
                        httpRequest(
                            vertx, port2, HttpMethod.POST,
                            "/healthcare/v1/elderly-admissions/enc-1/discharge-handover",
                            JsonObject().put("author", "李护理师"),
                        ).map { (status3, body3) ->
                            ctx.verify {
                                assertEquals(409, status3, "不同输入必须 409")
                                assertTrue(body3.getString("error").contains("different"), "got: ${body3.encode()}")
                            }
                            server2.close()
                            Unit
                        }
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `GET与POST静态路径不被泛型encounter路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            existingHandover = rows(handoverRow()),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val router = Router.router(vertx)
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))
        vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            val port = server.actualPort()
            httpRequest(
                vertx, port, HttpMethod.GET,
                "/healthcare/v1/elderly-admissions/enc-1/discharge-handover",
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "GET 必须命中静态路由而非 404/405")
                    assertEquals("rec-1", body.getString("id"))
                }
                server.close()
                Unit
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `HTTP错误形状为error对象`(vertx: Vertx, ctx: VertxTestContext) {
        val (_, _, pool) = stubConnection(
            encounters = rows(encounterRow(mapOf("status" to "ACTIVE", "discharge_date" to null))),
            periods = rowSet(),
            existingHandover = rowSet(),
            patients = rowSet(),
            nursingRecords = rowSet(),
            readBack = rowSet(),
        )
        val router = Router.router(vertx)
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))
        vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            val port = server.actualPort()
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/elderly-admissions/enc-1/discharge-handover",
                JsonObject().put("author", "王护理师"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "未离院必须 409 而非 404")
                    assertNotNull(body.getString("error"), "错误响应必须为 { error: ... }")
                }
                server.close()
                Unit
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }
}
