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
import java.time.ZoneId
import java.util.function.Function as JavaFunction

/**
 * 体检管理（CheckupService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 批次创建：ULID、默认草稿、operator 取认证主体、同年度唯一 409、快照在册人员
 *   - 状态机：草稿 → 进行中 → 已完成单向流转，非法跳转 400
 *   - 名单：仅接受 ACTIVE 患者、锚点解析、幂等补录、已完成批次拒绝
 *   - 结果：数值项自动判异常（含边界与 metadata.thresholds 覆盖）、WEIGHT 不判异常、
 *     文本项人工标记、名单外患者拒绝、批量录入标记已检
 *   - 修正：数值项重算 abnormal、文本项人工修改、字段白名单
 *   - 转体征：仅 abnormal 数值映射项；来源引用/阈值/记录人正确；重复 409
 *   - 转随访：锚定活动 encounter、默认计划日=体检日+7、assignee=认证主体；无锚点 409
 *   - 统计口径、列表分页与空列表 records:[] total:0；写路由无认证 401
 */
@ExtendWith(VertxExtension::class)
class CheckupServiceTest {

    private class DatabaseStub(
        var checkups: RowSet<Row> = rowSet(),
        var checkupListRows: RowSet<Row> = rowSet(),
        var checkupCounts: RowSet<Row> = rowSet(),
        var memberRows: RowSet<Row> = rowSet(),
        var memberDetailRows: RowSet<Row> = rowSet(),
        var memberCounts: RowSet<Row> = rowSet(),
        var statsMemberCounts: RowSet<Row> = rowSet(),
        var statsResultCounts: RowSet<Row> = rowSet(),
        var resultRows: RowSet<Row> = rowSet(),
        var resultDetailRows: RowSet<Row> = rowSet(),
        var resultCounts: RowSet<Row> = rowSet(),
        var patients: RowSet<Row> = rowSet(),
        var encounters: RowSet<Row> = rowSet(),
        var updateAffected: Int = 1,
        var memberInsertAffected: Int = 1,
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                val result = when {
                    sql.contains("insert into healthcare.health_checkups") -> rowSet()
                    sql.contains("insert into healthcare.health_checkup_members") -> updated(memberInsertAffected)
                    sql.contains("insert into healthcare.health_checkup_results") -> rowSet()
                    sql.contains("insert into healthcare.vital_sign_records") -> rowSet()
                    sql.contains("insert into healthcare.followup_plans") -> rowSet()
                    sql.contains("update healthcare.health_checkups") -> updated(updateAffected)
                    sql.contains("update healthcare.health_checkup_members") -> updated(updateAffected)
                    sql.contains("update healthcare.health_checkup_results") -> updated(updateAffected)
                    // 批次列表/详情带聚合（join），须先于 count 判定
                    sql.contains("from healthcare.health_checkups") && sql.contains(" join ") -> checkupListRows
                    sql.contains("count(*)") && sql.contains("from healthcare.health_checkups") -> checkupCounts
                    sql.contains("from healthcare.health_checkups") -> checkups
                    // 名单统计（count(*) filter）先于普通 count 判定
                    sql.contains("count(*)") && sql.contains("from healthcare.health_checkup_members") && sql.contains("filter (where") -> statsMemberCounts
                    sql.contains("count(*)") && sql.contains("from healthcare.health_checkup_members") -> memberCounts
                    sql.contains("from healthcare.health_checkup_members") && sql.contains(" join ") -> memberDetailRows
                    sql.contains("from healthcare.health_checkup_members") -> memberRows
                    sql.contains("count(*)") && sql.contains("from healthcare.health_checkup_results") && sql.contains("filter (where") -> statsResultCounts
                    sql.contains("count(*)") && sql.contains("from healthcare.health_checkup_results") -> resultCounts
                    sql.contains("from healthcare.health_checkup_results") && sql.contains(" join ") -> resultDetailRows
                    sql.contains("from healthcare.health_checkup_results") -> resultRows
                    sql.contains("from healthcare.encounters") -> encounters
                    sql.contains("from healthcare.patients") -> patients
                    else -> rowSet()
                }
                Future.succeededFuture(result)
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            queries.add(lastSql)
        }
    }

    // ——— fixture 行 ———

    private fun checkupRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "ck-1",
            "checkup_year" to 2026,
            "name" to "2026年度体检",
            "status" to "草稿",
            "start_date" to LocalDate.parse("2026-08-01"),
            "end_date" to LocalDate.parse("2026-08-31"),
            "operator" to "user-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun memberRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "mem-1",
            "checkup_id" to "ck-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "checked" to false,
            "checked_at" to null,
            "operator" to "user-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun anchorRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "pat-1",
            "name" to "张奶奶",
            "status" to "ACTIVE",
            "encounter_id" to "enc-1",
        )
        base.putAll(overrides)
        return base
    }

    private fun patientRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "pat-1",
            "name" to "张奶奶",
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun resultRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "res-1",
            "checkup_id" to "ck-1",
            "member_id" to "mem-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "item_name" to "体温",
            "item_category" to "数值",
            "value" to BigDecimal("38.5"),
            "unit" to "℃",
            "text_value" to null,
            "ref_min" to null,
            "ref_max" to null,
            "abnormal" to true,
            "exam_date" to LocalDate.now().minusDays(1),
            "operator" to "user-1",
            "vital_sign_id" to null,
            "followup_plan_id" to null,
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun validCheckup(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("checkup_year", 2026)
            .put("name", "2026年度体检")
            .put("start_date", "2026-08-01")
            .put("end_date", "2026-08-31")
            .put("snapshot", false)
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun numericResult(
        itemName: String = "体温",
        value: Number = 38.5,
        overrides: Map<String, Any?> = emptyMap(),
    ): JsonObject {
        val body = JsonObject()
            .put("patient_id", "pat-1")
            .put("item_name", itemName)
            .put("item_category", "数值")
            .put("value", value)
            .put("exam_date", LocalDate.now().minusDays(1).toString())
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun textResult(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("patient_id", "pat-1")
            .put("item_name", "心电图")
            .put("item_category", "文本")
            .put("text_value", "窦性心律不齐，建议复查")
            .put("abnormal", true)
            .put("exam_date", LocalDate.now().minusDays(1).toString())
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
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

    private fun successOf(future: Future<*>): Any? =
        try {
            future.toCompletionStage().toCompletableFuture().get()
        } catch (error: Throwable) {
            throw AssertionError("expected future to succeed, got: $error")
        }

    private val ulidPattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    // ========================================================================
    //  1. 体检批次
    // ========================================================================

    @Test
    fun `创建批次默认草稿且操作人取认证主体`() {
        val stub = DatabaseStub(checkupCounts = rows(mapOf("total" to 0L)))
        val service = CheckupService(stub.pool)

        val result = successOf(service.createCheckup(validCheckup(), "user-1")) as JsonObject

        assertEquals("草稿", result.getString("status"))
        assertEquals(2026, result.getInteger("checkup_year"))
        assertEquals("2026年度体检", result.getString("name"))
        assertEquals("user-1", result.getString("operator"), "operator 必须取自认证主体")
        assertTrue(ulidPattern.matches(result.getString("id")), "id 必须为 26 位 ULID")
        assertEquals(1, stub.transactionCalls, "创建必须单事务")
        assertTrue(stub.queries.any { it.contains("insert into healthcare.health_checkups") })
        assertTrue(stub.tuples.any { it.second.contains(2026) && it.second.contains("2026年度体检") })
        assertFalse(
            stub.queries.any { it.contains("insert into healthcare.health_checkup_members") },
            "snapshot=false 不得快照名单",
        )
    }

    @Test
    fun `创建批次默认快照本机构在册人员`() {
        val stub = DatabaseStub(
            checkupCounts = rows(mapOf("total" to 0L)),
            patients = rows(
                mapOf("id" to "pat-1", "encounter_id" to "enc-1"),
                mapOf("id" to "pat-2", "encounter_id" to null),
            ),
        )
        val service = CheckupService(stub.pool)

        val result = successOf(service.createCheckup(validCheckup(mapOf("snapshot" to null)), "user-1")) as JsonObject

        assertEquals(2, result.getInteger("member_total"), "默认快照在册人员")
        assertEquals(2, stub.queries.count { it.contains("insert into healthcare.health_checkup_members") })
        assertTrue(stub.tuples.any { it.second.contains("enc-1") }, "活动锚点写入名单")
    }

    @Test
    fun `创建同一年度第二批次返回409`() {
        val stub = DatabaseStub(checkupCounts = rows(mapOf("total" to 1L)))
        val service = CheckupService(stub.pool)

        val cause = causeOf(service.createCheckup(validCheckup(), "user-1"))

        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already exists") == true, "got: ${cause.message}")
        assertFalse(stub.queries.any { it.contains("insert into healthcare.health_checkups") }, "重复创建不得落库")
    }

    @Test
    fun `创建批次校验年号必填字段与白名单`() {
        val stub = DatabaseStub(checkupCounts = rows(mapOf("total" to 0L)))
        val service = CheckupService(stub.pool)

        fun expectInvalid(body: JsonObject, fragment: String) {
            val cause = causeOf(service.createCheckup(body, "user-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid(validCheckup(mapOf("checkup_year" to null)), "checkup_year is required")
        expectInvalid(validCheckup(mapOf("checkup_year" to 1999)), "between 2000 and 2100")
        expectInvalid(validCheckup(mapOf("checkup_year" to 2101)), "between 2000 and 2100")
        expectInvalid(validCheckup(mapOf("name" to null)), "name is required")
        expectInvalid(validCheckup(mapOf("start_date" to "not-a-date")), "ISO-8601")
        expectInvalid(validCheckup(mapOf("end_date" to "2026-07-01")), "end_date must not be earlier")
        expectInvalid(validCheckup(mapOf("operator" to "hacked")), "unsupported health checkup keys")
        expectInvalid(validCheckup(mapOf("status" to "已完成")), "unsupported health checkup keys")
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    @Test
    fun `状态机按草稿进行中已完成单向流转`() {
        // getCheckupRow 读 checkups（流转前状态）；流转后 getCheckupVia 读
        // checkupListRows（带 join 的详情查询），须各自反映当前/目标状态
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            checkupListRows = rows(checkupRow(mapOf("status" to "进行中"))),
        )
        val service = CheckupService(stub.pool)

        val advanced = successOf(
            service.updateCheckupStatus("ck-1", JsonObject().put("status", "进行中")),
        ) as JsonObject
        assertEquals("进行中", advanced.getString("status"))
        assertTrue(stub.queries.any { it.contains("update healthcare.health_checkups") })

        stub.checkups = rows(checkupRow(mapOf("status" to "进行中")))
        stub.checkupListRows = rows(checkupRow(mapOf("status" to "已完成")))
        val completed = successOf(
            service.updateCheckupStatus("ck-1", JsonObject().put("status", "已完成")),
        ) as JsonObject
        assertEquals("已完成", completed.getString("status"))
    }

    @Test
    fun `状态机拒绝跳变与回退与非法值`() {
        val stub = DatabaseStub(checkups = rows(checkupRow()))
        val service = CheckupService(stub.pool)

        fun expectInvalid(status: String, fragment: String) {
            val cause = causeOf(service.updateCheckupStatus("ck-1", JsonObject().put("status", status)))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid("已完成", "invalid status transition from 草稿 to 已完成")
        expectInvalid("草稿", "invalid status transition from 草稿 to 草稿")
        expectInvalid("已取消", "invalid status")

        stub.checkups = rows(checkupRow(mapOf("status" to "进行中")))
        expectInvalid("草稿", "invalid status transition from 进行中 to 草稿")

        stub.checkups = rows(checkupRow(mapOf("status" to "已完成")))
        expectInvalid("进行中", "invalid status transition from 已完成 to 进行中")

        val forbidden = causeOf(service.updateCheckupStatus("ck-1", JsonObject().put("status", "进行中").put("operator", "x")))
        assertInstanceOf(IllegalArgumentException::class.java, forbidden)
        assertTrue(forbidden.message?.contains("unsupported checkup status keys") == true)
    }

    @Test
    fun `状态流转批次不存在返回404`() {
        val stub = DatabaseStub(checkups = rowSet())
        val service = CheckupService(stub.pool)

        val cause = causeOf(service.updateCheckupStatus("missing", JsonObject().put("status", "进行中")))

        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("health checkup not found") == true)
    }

    // ========================================================================
    //  2. 参检名单
    // ========================================================================

    @Test
    fun `补录名单接受在册患者并解析活动锚点`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            patients = rows(anchorRow()),
        )
        val service = CheckupService(stub.pool)

        val result = successOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray().add("pat-1")), "user-1"),
        ) as JsonObject

        val record = result.getJsonArray("records").getJsonObject(0)
        assertEquals("pat-1", record.getString("patient_id"))
        assertEquals("张奶奶", record.getString("patient_name"))
        assertEquals("enc-1", record.getString("encounter_id"), "活动锚点必须解析")
        assertEquals(false, record.getBoolean("skipped"), "新成员不得标记跳过")
        assertTrue(stub.queries.any { it.contains("insert into healthcare.health_checkup_members") })
        assertTrue(stub.tuples.any { it.second.contains("enc-1") }, "锚点必须写入名单")
        assertEquals(1, stub.transactionCalls, "补录必须单事务")
    }

    @Test
    fun `补录名单拒绝非在册患者与不存在患者`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            patients = rows(anchorRow(mapOf("status" to "DISCHARGED"))),
        )
        val service = CheckupService(stub.pool)

        val inactive = causeOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray().add("pat-1")), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, inactive)
        assertTrue(inactive.message?.contains("not in the active registry") == true, "got: ${inactive.message}")

        stub.patients = rowSet()
        val missing = causeOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray().add("pat-9")), "user-1"),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, missing)
    }

    @Test
    fun `补录名单拒绝重复患者与空列表与已完成批次`() {
        val stub = DatabaseStub(checkups = rows(checkupRow()))
        val service = CheckupService(stub.pool)

        val dup = causeOf(
            service.addMembers(
                "ck-1",
                JsonObject().put("patient_ids", JsonArray().add("pat-1").add("pat-1")),
                "user-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, dup)
        assertTrue(dup.message?.contains("duplicates") == true)

        val empty = causeOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray()), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, empty)

        stub.checkups = rows(checkupRow(mapOf("status" to "已完成")))
        val completed = causeOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray().add("pat-1")), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, completed)
        assertTrue(completed.message?.contains("completed checkup") == true)

        val forbidden = causeOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray().add("pat-1")).put("operator", "x"), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, forbidden)
        assertTrue(forbidden.message?.contains("unsupported checkup members keys") == true)
    }

    @Test
    fun `补录名单幂等跳过已存在成员`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            patients = rows(anchorRow()),
            memberInsertAffected = 0,
        )
        val service = CheckupService(stub.pool)

        val result = successOf(
            service.addMembers("ck-1", JsonObject().put("patient_ids", JsonArray().add("pat-1")), "user-1"),
        ) as JsonObject

        assertEquals(true, result.getJsonArray("records").getJsonObject(0).getBoolean("skipped"), "已存在成员幂等跳过")
    }

    @Test
    fun `名单列表分页已检过滤与空列表`() {
        val stub = DatabaseStub(
            memberCounts = rows(mapOf("total" to 1L)),
            memberDetailRows = rows(memberRow()),
        )
        val service = CheckupService(stub.pool)

        val result = successOf(service.listMembers("ck-1", "false", 10, 0)) as JsonObject
        assertEquals(1, result.getJsonArray("records").size())
        assertEquals(1L, result.getJsonObject("meta").getLong("total"))
        assertEquals("张奶奶", result.getJsonArray("records").getJsonObject(0).getString("patient_name"))
        assertTrue(
            stub.tuples.any { it.first.contains("from healthcare.health_checkup_members") && it.second.contains(false) },
            "checked=false 过滤必须落 SQL/绑定参数",
        )

        stub.memberDetailRows = rowSet()
        stub.memberCounts = rows(mapOf("total" to 0L))
        val empty = successOf(service.listMembers("ck-1", null, 10, 0)) as JsonObject
        assertEquals(0, empty.getJsonArray("records").size())
        assertEquals(0L, empty.getJsonObject("meta").getLong("total"))

        val badFilter = causeOf(service.listMembers("ck-1", "maybe", 10, 0))
        assertInstanceOf(IllegalArgumentException::class.java, badFilter)
    }

    // ========================================================================
    //  3. 体检结果
    // ========================================================================

    @Test
    fun `结果录入数值项自动判异常含边界值`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            memberRows = rows(mapOf("id" to "mem-1", "patient_id" to "pat-1")),
        )
        val service = CheckupService(stub.pool)

        fun abnormalOf(value: Number): Boolean {
            val result = successOf(
                service.createResults("ck-1", numericResult(value = value), "user-1"),
            ) as JsonObject
            return result.getJsonArray("records").getJsonObject(0).getBoolean("abnormal")
        }

        assertFalse(abnormalOf(36.5), "36.5 在 36.0–37.3 内不判异常")
        assertTrue(abnormalOf(38.5), "38.5 超上限判异常")
        assertTrue(abnormalOf(35.9), "35.9 低于下限判异常")
        assertFalse(abnormalOf(37.3), "上限边界值不判异常")
        assertFalse(abnormalOf(36.0), "下限边界值不判异常")
        assertTrue(stub.tuples.any { it.second.contains(BigDecimal("38.5")) }, "数值正确落库")
    }

    @Test
    fun `结果录入阈值覆盖与体重及映射外项目不判异常`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            memberRows = rows(mapOf("id" to "mem-1", "patient_id" to "pat-1")),
        )
        val service = CheckupService(stub.pool)

        // metadata.thresholds 覆盖内置范围：体温 37.5 在自定义 37–38 内不判异常
        val thresholds = JsonObject().put(
            "thresholds",
            JsonObject().put("TEMPERATURE", JsonObject().put("min", 37).put("max", 38)),
        )
        val covered = successOf(
            service.createResults("ck-1", numericResult(value = 37.5, overrides = mapOf("metadata" to thresholds)), "user-1"),
        ) as JsonObject
        assertFalse(covered.getJsonArray("records").getJsonObject(0).getBoolean("abnormal"), "阈值覆盖后 37.5 不判异常")

        // WEIGHT 不判异常
        val weight = successOf(
            service.createResults("ck-1", numericResult(itemName = "体重", value = 88.5), "user-1"),
        ) as JsonObject
        assertFalse(weight.getJsonArray("records").getJsonObject(0).getBoolean("abnormal"), "WEIGHT 不判异常")

        // 映射外数值项：可录入（需显式 unit），不判异常
        val cholesterol = successOf(
            service.createResults(
                "ck-1",
                numericResult(itemName = "总胆固醇", value = 7.2, overrides = mapOf("unit" to "mmol/L")),
                "user-1",
            ),
        ) as JsonObject
        assertFalse(cholesterol.getJsonArray("records").getJsonObject(0).getBoolean("abnormal"), "映射外数值项不判异常")

        // 映射内省略 unit 时按类型默认
        val defaulted = successOf(
            service.createResults("ck-1", numericResult(itemName = "空腹血糖", value = 5.0), "user-1"),
        ) as JsonObject
        assertEquals("mmol/L", defaulted.getJsonArray("records").getJsonObject(0).getString("unit"))

        // 映射外数值项省略 unit 被拒
        val noUnit = causeOf(service.createResults("ck-1", numericResult(itemName = "总胆固醇", value = 7.2), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, noUnit)
        assertTrue(noUnit.message?.contains("unit is required") == true)

        // 阈值 min>max 被拒
        val badThresholds = JsonObject().put(
            "thresholds",
            JsonObject().put("TEMPERATURE", JsonObject().put("min", 38).put("max", 37)),
        )
        val badRange = causeOf(
            service.createResults("ck-1", numericResult(value = 37.5, overrides = mapOf("metadata" to badThresholds)), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, badRange)
        assertTrue(badRange.message?.contains("thresholds min must not be greater") == true)
    }

    @Test
    fun `结果录入文本项人工标记且数值文本字段互斥`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            memberRows = rows(mapOf("id" to "mem-1", "patient_id" to "pat-1")),
        )
        val service = CheckupService(stub.pool)

        val text = successOf(service.createResults("ck-1", textResult(), "user-1")) as JsonObject
        val record = text.getJsonArray("records").getJsonObject(0)
        assertEquals("文本", record.getString("item_category"))
        assertEquals(true, record.getBoolean("abnormal"), "文本项异常由录入人显式标记")
        assertEquals("窦性心律不齐，建议复查", record.getString("text_value"))

        fun expectInvalid(body: JsonObject, fragment: String) {
            val cause = causeOf(service.createResults("ck-1", body, "user-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid(textResult(mapOf("abnormal" to null)), "abnormal is required for text items")
        expectInvalid(numericResult(overrides = mapOf("abnormal" to true)), "abnormal is computed by the server")
        expectInvalid(numericResult(overrides = mapOf("text_value" to "x")), "text_value is only allowed for text items")
        expectInvalid(textResult(mapOf("value" to 1)), "value/unit/ref_min/ref_max are only allowed for numeric items")
        expectInvalid(numericResult(value = -1), "must be a positive number")
        expectInvalid(numericResult(value = 36.555), "at most 2 decimal places")
        expectInvalid(numericResult(overrides = mapOf("item_category" to "图片")), "invalid item_category")
        expectInvalid(numericResult(overrides = mapOf("item_name" to "血氧饱和度", "value" to 120)), "SPO2 must be between 0 and 100")
        expectInvalid(
            numericResult(overrides = mapOf("exam_date" to LocalDate.now().plusDays(1).toString())),
            "exam_date must not be in the future",
        )
        expectInvalid(
            numericResult(overrides = mapOf("patient_id" to null)),
            "patient_id is required",
        )
        expectInvalid(
            numericResult(overrides = mapOf("operator" to "hacked")),
            "unsupported checkup result keys",
        )
    }

    @Test
    fun `结果录入拒绝名单外患者与已完成批次并标记已检`() {
        val stub = DatabaseStub(checkups = rows(checkupRow()))
        val service = CheckupService(stub.pool)

        val outside = causeOf(service.createResults("ck-1", numericResult(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, outside)
        assertTrue(outside.message?.contains("not in this checkup roster") == true, "got: ${outside.message}")

        stub.checkups = rows(checkupRow(mapOf("status" to "已完成")))
        val completed = causeOf(service.createResults("ck-1", numericResult(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, completed)
        assertTrue(completed.message?.contains("completed checkup") == true)
    }

    @Test
    fun `结果批量录入单事务并标记成员已检`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            memberRows = rows(
                mapOf("id" to "mem-1", "patient_id" to "pat-1"),
                mapOf("id" to "mem-2", "patient_id" to "pat-2"),
            ),
        )
        val service = CheckupService(stub.pool)

        val body = JsonArray()
            .add(numericResult(value = 36.5))
            .add(numericResult(itemName = "心率", value = 72))
        val result = successOf(service.createResults("ck-1", body, "user-1")) as JsonObject

        assertEquals(2, result.getJsonArray("records").size())
        assertTrue(result.getJsonArray("records").getJsonObject(0).getString("id").let(ulidPattern::matches))
        assertEquals(1, stub.transactionCalls, "批量录入必须单事务")
        assertEquals(2, stub.queries.count { it.contains("insert into healthcare.health_checkup_results") })
        assertTrue(
            stub.queries.any { it.contains("update healthcare.health_checkup_members") && it.contains("checked") },
            "录入成功必须标记成员已检",
        )
    }

    // ========================================================================
    //  4. 结果修正
    // ========================================================================

    @Test
    fun `修正数值项重算异常标记`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow(mapOf("value" to BigDecimal("36.5"), "abnormal" to false))),
            resultDetailRows = rows(resultRow(mapOf("value" to BigDecimal("36.5"), "abnormal" to false))),
        )
        val service = CheckupService(stub.pool)

        // 修正后重读结果需反映新状态（修正响应来自详情查询）
        stub.resultDetailRows = rows(resultRow(mapOf("value" to BigDecimal("38.5"), "abnormal" to true)))
        val result = successOf(
            service.updateResult("res-1", JsonObject().put("value", 38.5)),
        ) as JsonObject

        assertEquals(true, result.getBoolean("abnormal"), "修正后必须按新值重算 abnormal")
        assertTrue(stub.tuples.any { it.second.contains(BigDecimal("38.5")) }, "新值必须落库")

        // 数值项不得提交 abnormal（服务端重算）
        val cause = causeOf(service.updateResult("res-1", JsonObject().put("abnormal", true)))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("text_value/abnormal are only allowed for text items") == true)
    }

    @Test
    fun `修正文本项人工异常与已完成批次拒绝`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow(mapOf("item_category" to "文本", "text_value" to "正常", "abnormal" to false))),
            resultDetailRows = rows(resultRow(mapOf("item_category" to "文本", "text_value" to "正常", "abnormal" to false))),
        )
        val service = CheckupService(stub.pool)

        // 修正后重读结果需反映新状态（修正响应来自详情查询）
        stub.resultDetailRows = rows(
            resultRow(mapOf("item_category" to "文本", "text_value" to "T 波倒置", "abnormal" to true)),
        )
        val result = successOf(
            service.updateResult("res-1", JsonObject().put("abnormal", true).put("text_value", "T 波倒置")),
        ) as JsonObject
        assertEquals(true, result.getBoolean("abnormal"))
        assertTrue(stub.tuples.any { it.second.contains("T 波倒置") })

        val cause = causeOf(service.updateResult("res-1", JsonObject().put("value", 1)))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("value/unit/ref_min/ref_max are only allowed for numeric items") == true)

        stub.checkups = rows(checkupRow(mapOf("status" to "已完成")))
        val completed = causeOf(service.updateResult("res-1", JsonObject().put("abnormal", false)))
        assertInstanceOf(IllegalArgumentException::class.java, completed)
        assertTrue(completed.message?.contains("completed checkup") == true)
    }

    // ========================================================================
    //  5. 异常转体征
    // ========================================================================

    @Test
    fun `转体征生成记录带来源引用与阈值且记录人取认证主体`() {
        val examDate = LocalDate.now().minusDays(1)
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow()),
            memberRows = rows(memberRow()),
            memberDetailRows = rows(memberRow()),
            resultDetailRows = rows(resultRow()),
        )
        val service = CheckupService(stub.pool)

        val result = successOf(service.toVitalSign("res-1", "user-1")) as JsonObject
        val vitalSign = result.getJsonObject("vital_sign")

        assertTrue(ulidPattern.matches(vitalSign.getString("id")))
        assertEquals("TEMPERATURE", vitalSign.getString("type"), "按常量映射表映射类型")
        assertEquals("pat-1", vitalSign.getString("patient_id"))
        assertEquals("enc-1", vitalSign.getString("encounter_id"), "体征记录携带活动锚点")
        assertEquals("user-1", vitalSign.getString("recorded_by"), "recorded_by 必须取认证主体")
        assertEquals(true, vitalSign.getBoolean("abnormal"), "abnormal 按原参考范围判定")
        assertEquals("待复核", vitalSign.getString("review_status"), "进入既有体征复核闭环")
        assertTrue(vitalSign.getString("measured_at")?.startsWith(examDate.toString()) == true, "measured_at 取体检日期")

        val metadata = vitalSign.getJsonObject("metadata")
        assertEquals("体检异常转体征", metadata.getString("source"))
        assertEquals("res-1", metadata.getString("exam_result_id"), "来源引用必须写入")
        assertEquals("ck-1", metadata.getString("checkup_id"))
        assertEquals(36.0, metadata.getJsonObject("thresholds").getJsonObject("TEMPERATURE").getDouble("min"))
        assertEquals(37.3, metadata.getJsonObject("thresholds").getJsonObject("TEMPERATURE").getDouble("max"))

        assertTrue(stub.queries.any { it.contains("insert into healthcare.vital_sign_records") })
        assertTrue(stub.tuples.any { it.second.contains("user-1") })
        // 条件更新兜底：仅当 vital_sign_id 为空时标记
        assertTrue(
            stub.queries.any { it.contains("update healthcare.health_checkup_results") && it.contains("vital_sign_id is null") },
        )
        assertEquals("res-1", result.getJsonObject("result").getString("id"))
    }

    @Test
    fun `转体征拒绝非异常文本项与映射外项目`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow(mapOf("abnormal" to false))),
            memberRows = rows(memberRow()),
            memberDetailRows = rows(memberRow()),
            resultDetailRows = rows(resultRow()),
        )
        val service = CheckupService(stub.pool)

        val normal = causeOf(service.toVitalSign("res-1", "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, normal)
        assertTrue(normal.message?.contains("only abnormal results") == true, "got: ${normal.message}")

        stub.resultRows = rows(resultRow(mapOf("item_category" to "文本", "item_name" to "心电图", "abnormal" to true)))
        val text = causeOf(service.toVitalSign("res-1", "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, text)
        assertTrue(text.message?.contains("only numeric items") == true)

        stub.resultRows = rows(resultRow(mapOf("item_name" to "总胆固醇", "abnormal" to true)))
        val unmapped = causeOf(service.toVitalSign("res-1", "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, unmapped)
        assertTrue(unmapped.message?.contains("not mappable to a vital sign type") == true)
    }

    @Test
    fun `转体征重复返回409且已完成批次拒绝`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow(mapOf("vital_sign_id" to "vs-1"))),
            memberRows = rows(memberRow()),
        )
        val service = CheckupService(stub.pool)

        val duplicate = causeOf(service.toVitalSign("res-1", "user-1"))
        assertInstanceOf(ConflictException::class.java, duplicate)
        assertTrue(duplicate.message?.contains("already been converted") == true)

        stub.resultRows = rows(resultRow())
        stub.checkups = rows(checkupRow(mapOf("status" to "已完成")))
        val completed = causeOf(service.toVitalSign("res-1", "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, completed)
        assertTrue(completed.message?.contains("completed checkup") == true)
    }

    // ========================================================================
    //  6. 异常转随访
    // ========================================================================

    @Test
    fun `转随访生成计划锚定活动周期且默认计划日为体检日加7天`() {
        val examDate = LocalDate.now().minusDays(1)
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow()),
            memberRows = rows(memberRow()),
            memberDetailRows = rows(memberRow()),
            resultDetailRows = rows(resultRow()),
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
        )
        val service = CheckupService(stub.pool)

        val result = successOf(
            service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"),
        ) as JsonObject
        val plan = result.getJsonObject("followup_plan")

        assertTrue(ulidPattern.matches(plan.getString("id")))
        assertEquals("pat-1", plan.getString("patient_id"))
        assertEquals("enc-1", plan.getString("encounter_id"), "必须锚定成员活动 encounter")
        assertEquals("慢病随访", plan.getString("followup_type"))
        assertEquals(examDate.plusDays(7).toString(), plan.getString("planned_date"), "默认计划日=体检日+7")
        assertEquals("电话", plan.getString("planned_way"), "默认方式电话")
        assertEquals("user-1", plan.getString("assignee"), "assignee 必须取认证主体")
        assertEquals("待随访", plan.getString("status"))
        assertEquals("体检异常转随访", plan.getJsonObject("metadata").getString("source"))
        assertEquals("res-1", plan.getJsonObject("metadata").getString("exam_result_id"))
        assertEquals("ck-1", plan.getJsonObject("metadata").getString("checkup_id"))

        assertTrue(stub.queries.any { it.contains("insert into healthcare.followup_plans") })
        assertTrue(stub.tuples.any { it.second.contains("enc-1") && it.second.contains("慢病随访") })
        assertTrue(
            stub.queries.any { it.contains("update healthcare.health_checkup_results") && it.contains("followup_plan_id is null") },
        )
    }

    @Test
    fun `转随访支持显式计划日期与方式及备注`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow()),
            memberRows = rows(memberRow()),
            memberDetailRows = rows(memberRow()),
            resultDetailRows = rows(resultRow()),
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
        )
        val service = CheckupService(stub.pool)

        val planned = LocalDate.now().plusDays(15)
        val result = successOf(
            service.toFollowup(
                "res-1",
                JsonObject()
                    .put("followup_type", "常规电话随访")
                    .put("planned_date", planned.toString())
                    .put("planned_way", "上门")
                    .put("remark", "家属要求上门"),
                "user-1",
            ),
        ) as JsonObject

        val plan = result.getJsonObject("followup_plan")
        assertEquals(planned.toString(), plan.getString("planned_date"))
        assertEquals("上门", plan.getString("planned_way"))
        assertEquals("家属要求上门", plan.getString("remark"))
    }

    @Test
    fun `转随访拒绝无锚点重复与类型白名单死者及归属不匹配`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            resultRows = rows(resultRow()),
            memberRows = rows(memberRow(mapOf("encounter_id" to null))),
        )
        val service = CheckupService(stub.pool)

        val noAnchor = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"))
        assertInstanceOf(ConflictException::class.java, noAnchor)
        assertTrue(noAnchor.message?.contains("no active encounter") == true, "got: ${noAnchor.message}")

        // 重复转出
        stub.memberRows = rows(memberRow())
        stub.resultRows = rows(resultRow(mapOf("followup_plan_id" to "fp-1")))
        val duplicate = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"))
        assertInstanceOf(ConflictException::class.java, duplicate)
        assertTrue(duplicate.message?.contains("already been converted") == true)

        // 类型白名单
        stub.resultRows = rows(resultRow())
        val badType = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "上门回访"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, badType)
        assertTrue(badType.message?.contains("invalid followup_type") == true)

        // 非异常项
        stub.resultRows = rows(resultRow(mapOf("abnormal" to false)))
        val normal = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, normal)
        assertTrue(normal.message?.contains("only abnormal results") == true)

        // 死者拒绝
        stub.resultRows = rows(resultRow())
        stub.patients = rows(patientRow(mapOf("status" to "DECEASED")))
        val deceased = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, deceased)
        assertTrue(deceased.message?.contains("deceased") == true)

        // encounter 归属不匹配
        stub.patients = rows(patientRow())
        stub.encounters = rows(encounterRow(mapOf("patient_id" to "pat-9")))
        val mismatch = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, mismatch)
        assertTrue(mismatch.message?.contains("does not belong") == true)

        // 计划日早于入院日
        stub.encounters = rows(encounterRow())
        val early = causeOf(
            service.toFollowup(
                "res-1",
                JsonObject().put("followup_type", "慢病随访").put("planned_date", "2026-07-01"),
                "user-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, early)
        assertTrue(early.message?.contains("planned_date must not be earlier") == true)

        // 已完成批次拒绝
        stub.checkups = rows(checkupRow(mapOf("status" to "已完成")))
        val completed = causeOf(service.toFollowup("res-1", JsonObject().put("followup_type", "慢病随访"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, completed)
        assertTrue(completed.message?.contains("completed checkup") == true)
    }

    // ========================================================================
    //  7. 统计与列表
    // ========================================================================

    @Test
    fun `批次统计应检已检完成率与异常转出汇总`() {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            statsMemberCounts = rows(mapOf("member_total" to 10L, "checked_total" to 4L)),
            statsResultCounts = rows(
                mapOf("abnormal_total" to 3L, "vital_sign_total" to 2L, "followup_total" to 1L),
            ),
        )
        val service = CheckupService(stub.pool)

        val stats = successOf(service.getCheckupStats("ck-1")) as JsonObject

        assertEquals(10L, stats.getLong("member_total"))
        assertEquals(4L, stats.getLong("checked_total"))
        assertEquals(40L, stats.getLong("completion_rate"), "完成率=已检/应检*100")
        assertEquals(3L, stats.getLong("abnormal_total"))
        assertEquals(2L, stats.getLong("vital_sign_total"))
        assertEquals(1L, stats.getLong("followup_total"))
    }

    @Test
    fun `批次列表空列表返回records空数组与total零`() {
        val stub = DatabaseStub(
            checkupCounts = rows(mapOf("total" to 0L)),
            checkupListRows = rowSet(),
        )
        val service = CheckupService(stub.pool)

        val result = successOf(service.listCheckups(null, 50, 0)) as JsonObject

        assertEquals(0, result.getJsonArray("records").size())
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))

        val badStatus = causeOf(service.listCheckups("已取消", 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, badStatus)
    }

    // ========================================================================
    //  8. 路由
    // ========================================================================

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body)
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

    private fun <T> withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        userId: String? = null,
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose {
                server.close().map { Unit }
            }
        }
    }

    @Test
    fun `体检写路由无认证上下文返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(checkupCounts = rows(mapOf("total" to 0L)))
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/health-checkups", validCheckup().encode())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status)
                        assertEquals("authentication required", body.getString("error"))
                        ctx.completeNow()
                    }
                }
        }
    }

    @Test
    fun `体检路由全链路批次创建列表与统计`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            checkups = rows(checkupRow()),
            checkupCounts = rows(mapOf("total" to 0L)),
            checkupListRows = rows(
                checkupRow() + mapOf("member_total" to 2L, "checked_total" to 1L),
            ),
            statsMemberCounts = rows(mapOf("member_total" to 2L, "checked_total" to 1L)),
            statsResultCounts = rows(mapOf("abnormal_total" to 1L, "vital_sign_total" to 0L, "followup_total" to 0L)),
        )
        withServer(vertx, stub, userId = "user-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/health-checkups", validCheckup().encode())
                .compose { (createStatus, created) ->
                    ctx.verify {
                        assertEquals(201, createStatus, "创建必须 201")
                        assertEquals("草稿", created.getString("status"))
                        assertTrue(ulidPattern.matches(created.getString("id")))
                    }
                    // 创建已落库：计数查询从 0 变为 1（stub 静态，需显式推进状态）
                    stub.checkupCounts = rows(mapOf("total" to 1L))
                    val encodedStatus = java.net.URLEncoder.encode("草稿", "UTF-8")
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/health-checkups?status=$encodedStatus&limit=10&offset=0")
                        .compose { (listStatus, list) ->
                            ctx.verify {
                                assertEquals(200, listStatus)
                                assertEquals(1, list.getJsonArray("records").size())
                                assertEquals(1L, list.getJsonObject("meta").getLong("total"))
                                assertEquals(2L, list.getJsonArray("records").getJsonObject(0).getLong("member_total"))
                            }
                            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/health-checkups/ck-1/stats")
                                .map { (statsStatus, stats) ->
                                    ctx.verify {
                                        assertEquals(200, statsStatus)
                                        assertEquals(50L, stats.getLong("completion_rate"))
                                        assertEquals(1L, stats.getLong("abnormal_total"))
                                        ctx.completeNow()
                                    }
                                }
                        }
                }
        }
    }
}

// ——— mock 基础设施（顶层函数：供测试方法与嵌套 DatabaseStub 共用） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getBoolean(any<String>()) } answers { values[firstArg<String>()] as? Boolean }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    every { rs.rowCount() } returns rows.size
    return rs
}

private fun updated(affected: Int): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = emptyList<Row>().iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns 0
    every { rs.rowCount() } returns affected
    return rs
}

private fun rows(vararg values: Map<String, Any?>): RowSet<Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}
