package com.ovaphlow.crate.healthcare

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
 * 生命体征（VitalSignService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 批量创建：ULID、默认单位、recorded_by 取自认证主体、血压双记录一次提交
 *   - 异常判定：内置参考范围边界值、metadata.thresholds 覆盖、WEIGHT 不判异常
 *   - 校验：非法数值/类型、患者不存在、encounter 归属不匹配、未来测量时间、SPO2 越界
 *   - 列表分页与过滤、空列表 records:[] total:0；快照每类型一条；趋势时间升序
 *   - 修正重算 abnormal、白名单字段拒绝；软删除后默认查询排除
 *   - 写路由无认证上下文返回 401；全链路嵌入式 HTTP 验证
 */
@ExtendWith(VertxExtension::class)
class VitalSignServiceTest {

    private class DatabaseStub(
        var patients: RowSet<Row> = rowSet(),
        var encounters: RowSet<Row> = rowSet(),
        var records: RowSet<Row> = rowSet(),
        var recordCounts: RowSet<Row> = rowSet(),
        var updateAffected: Int = 1,
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
                    sql.contains("insert into healthcare.vital_sign_records") -> rowSet()
                    sql.contains("update healthcare.vital_sign_records") -> updated(updateAffected)
                    sql.contains("count(*)") && sql.contains("from healthcare.vital_sign_records") -> recordCounts
                    sql.contains("from healthcare.vital_sign_records") -> records
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
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260801001",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun recordRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "vs-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "type" to "TEMPERATURE",
            "value" to BigDecimal("36.5"),
            "unit" to "℃",
            "measured_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
            "recorded_by" to "user-1",
            "abnormal" to false,
            "note" to null,
            "metadata" to null,
            "deleted_at" to null,
            "created_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun validRecord(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("patient_id", "pat-1")
            .put("encounter_id", "enc-1")
            .put("type", "TEMPERATURE")
            .put("value", 36.5)
            .put("measured_at", "2026-08-10T08:00:00+08:00")
            .put("note", "晨间测量")
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
    //  1. 创建
    // ========================================================================

    @Test
    fun `批量创建返回ULID默认单位与认证记录人且血压双记录一次提交`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
        )
        val service = VitalSignService(stub.pool)

        val body = JsonArray()
            .add(validRecord())
            .add(validRecord(mapOf("type" to "SYSTOLIC_BP", "value" to 135)))
            .add(validRecord(mapOf("type" to "DIASTOLIC_BP", "value" to 85)))
        val result = successOf(service.createVitalSigns(body, "user-1")) as JsonObject
        val records = result.getJsonArray("records")
        assertEquals(3, records.size())
        records.forEach { record ->
            val item = record as JsonObject
            assertTrue(ulidPattern.matches(item.getString("id")), "id 必须为 26 位 ULID")
            assertEquals("user-1", item.getString("recorded_by"), "recorded_by 必须取自认证上下文")
        }
        assertEquals("℃", records.getJsonObject(0).getString("unit"), "体温默认单位 ℃")
        assertEquals("mmHg", records.getJsonObject(1).getString("unit"), "收缩压默认单位 mmHg")
        assertEquals(1, stub.transactionCalls, "批量创建必须单事务")
        assertEquals(3, stub.queries.count { it.contains("insert into healthcare.vital_sign_records") })
        assertTrue(stub.tuples.any { it.second.contains("user-1") }, "记录人来自认证主体")
        assertTrue(stub.tuples.any { it.second.contains(BigDecimal("135.0")) }, "血压数值正确落库")
    }

    @Test
    fun `创建省略encounter与时间时允许并默认当前时间`() {
        val stub = DatabaseStub(patients = rows(patientRow()))
        val service = VitalSignService(stub.pool)

        val result = successOf(
            service.createVitalSigns(
                JsonArray().add(validRecord(mapOf("encounter_id" to null, "measured_at" to null))),
                "user-1",
            ),
        ) as JsonObject
        val record = result.getJsonArray("records").getJsonObject(0)
        assertNull(record.getString("encounter_id"), "encounter_id 可空（居家/社区场景）")
        assertNotNull(record.getString("measured_at"), "未提供测量时间默认当前时间")
        assertFalse(stub.queries.any { it.contains("from healthcare.encounters") }, "无 encounter_id 不校验归属")
    }

    @Test
    fun `创建拒绝审计字段与白名单外字段`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = VitalSignService(stub.pool)

        listOf("recorded_by", "abnormal", "created_at", "updated_at", "id", "deleted_at")
            .forEach { key ->
                val cause = causeOf(service.createVitalSigns(JsonArray().add(validRecord(mapOf(key to "hacked"))), "user-1"))
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                assertTrue(cause.message?.contains("unsupported vital sign record keys") == true, "got: ${cause.message}")
            }
        assertTrue(stub.queries.isEmpty(), "白名单校验失败不得触发任何 SQL")
    }

    @Test
    fun `创建校验必填类型数值与枚举`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = VitalSignService(stub.pool)

        fun expectInvalid(body: JsonObject, fragment: String) {
            val cause = causeOf(service.createVitalSigns(JsonArray().add(body), "user-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid(validRecord(mapOf("patient_id" to null)), "patient_id is required")
        expectInvalid(validRecord(mapOf("type" to null)), "type is required")
        expectInvalid(validRecord(mapOf("type" to "HEART_RATE")), "invalid type")
        expectInvalid(validRecord(mapOf("value" to null)), "value is required")
        expectInvalid(validRecord(mapOf("value" to "37")), "value must be a number")
        expectInvalid(validRecord(mapOf("value" to -1)), "value must be a positive number")
        expectInvalid(validRecord(mapOf("value" to 0)), "value must be a positive number")
        expectInvalid(validRecord(mapOf("value" to 36.555)), "at most 2 decimal places")
        expectInvalid(validRecord(mapOf("measured_at" to "2026-08-10")), "ISO-8601 offset date-time")
        expectInvalid(validRecord(mapOf("measured_at" to "2099-01-01T08:00:00+08:00")), "must not be in the future")
        expectInvalid(validRecord(mapOf("note" to "a".repeat(1001))), "1000 characters")
        expectInvalid(validRecord(mapOf("metadata" to "not-object")), "must be a JSON object")
        assertTrue(stub.queries.isEmpty(), "输入校验失败不得触发任何 SQL")
    }

    @Test
    fun `创建拒绝空数组与非对象元素`() {
        val stub = DatabaseStub(patients = rows(patientRow()))
        val service = VitalSignService(stub.pool)

        val empty = causeOf(service.createVitalSigns(JsonArray(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, empty)
        assertTrue(empty.message?.contains("must not be empty") == true)

        val notObject = causeOf(service.createVitalSigns(JsonArray().add("37"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, notObject)
        assertTrue(notObject.message?.contains("must be a JSON object") == true)
        assertTrue(stub.queries.isEmpty())
    }

    @Test
    fun `创建患者不存在返回404`() {
        val stub = DatabaseStub(patients = rowSet(), encounters = rows(encounterRow()))
        val cause = causeOf(VitalSignService(stub.pool).createVitalSigns(JsonArray().add(validRecord()), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("patient not found") == true)
    }

    @Test
    fun `创建encounter不存在或归属不匹配返回错误`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("patient_id" to "pat-other"))),
        )
        val service = VitalSignService(stub.pool)

        val mismatch = causeOf(service.createVitalSigns(JsonArray().add(validRecord()), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, mismatch)
        assertTrue(mismatch.message?.contains("does not belong to the specified patient") == true)

        stub.encounters = rowSet()
        val missing = causeOf(service.createVitalSigns(JsonArray().add(validRecord()), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, missing)
        assertTrue(missing.message?.contains("encounter not found") == true)
    }

    // ========================================================================
    //  2. 异常判定
    // ========================================================================

    @Test
    fun `异常判定按内置参考范围边界计算`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = VitalSignService(stub.pool)

        fun abnormalOf(type: String, value: Double): Boolean {
            stub.records = rowSet()
            stub.recordCounts = rows(mapOf("total" to 0L))
            val result = successOf(
                service.createVitalSigns(JsonArray().add(validRecord(mapOf("type" to type, "value" to value))), "user-1"),
            ) as JsonObject
            return result.getJsonArray("records").getJsonObject(0).getBoolean("abnormal")
        }

        // 体温 36.0–37.3（含边界）
        assertFalse(abnormalOf("TEMPERATURE", 36.0), "下边界不判异常")
        assertFalse(abnormalOf("TEMPERATURE", 37.3), "上边界不判异常")
        assertTrue(abnormalOf("TEMPERATURE", 35.9), "低于下限判异常")
        assertTrue(abnormalOf("TEMPERATURE", 37.4), "高于上限判异常")
        // 脉搏 60–100
        assertFalse(abnormalOf("PULSE", 60.0))
        assertTrue(abnormalOf("PULSE", 101.0))
        // 呼吸 12–20
        assertFalse(abnormalOf("RESPIRATION", 20.0))
        assertTrue(abnormalOf("RESPIRATION", 11.0))
        // 收缩压 90–140
        assertFalse(abnormalOf("SYSTOLIC_BP", 140.0))
        assertTrue(abnormalOf("SYSTOLIC_BP", 141.0))
        // 舒张压 60–90
        assertFalse(abnormalOf("DIASTOLIC_BP", 60.0))
        assertTrue(abnormalOf("DIASTOLIC_BP", 59.0))
        // 血氧 ≥95
        assertFalse(abnormalOf("SPO2", 95.0))
        assertTrue(abnormalOf("SPO2", 94.0))
        // 空腹血糖 3.9–6.1
        assertFalse(abnormalOf("BLOOD_GLUCOSE", 6.1))
        assertTrue(abnormalOf("BLOOD_GLUCOSE", 6.2))
        // 体重不判异常
        assertFalse(abnormalOf("WEIGHT", 300.0))
    }

    @Test
    fun `SPO2物理合理性限制0到100`() {
        val stub = DatabaseStub(patients = rows(patientRow()))
        val cause = causeOf(
            VitalSignService(stub.pool).createVitalSigns(
                JsonArray().add(validRecord(mapOf("type" to "SPO2", "value" to 101))),
                "user-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("SPO2 must be between 0 and 100") == true)
    }

    @Test
    fun `metadata阈值可覆盖参考范围`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = VitalSignService(stub.pool)

        // 老人基础体温偏低，37.2 属于其正常范围
        val body = validRecord(
            mapOf(
                "value" to 37.2,
                "metadata" to JsonObject().put(
                    "thresholds",
                    JsonObject().put(
                        "TEMPERATURE",
                        JsonObject().put("min", 35.5).put("max", 37.5),
                    ),
                ),
            ),
        )
        val result = successOf(service.createVitalSigns(JsonArray().add(body), "user-1")) as JsonObject
        val record = result.getJsonArray("records").getJsonObject(0)
        assertFalse(record.getBoolean("abnormal"), "覆盖阈值后 37.2 不判异常")
    }

    // ========================================================================
    //  3. 列表 / 详情 / 快照 / 趋势
    // ========================================================================

    @Test
    fun `列表空返回records空数组与total0且软删除默认排除`() {
        val stub = DatabaseStub(recordCounts = rows(mapOf("total" to 0L)), records = rowSet())
        val result = successOf(VitalSignService(stub.pool).listVitalSigns("pat-1", null, null, null, 50, 0)) as JsonObject
        assertEquals(0, result.getJsonArray("records").size())
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))
        val listSql = stub.queries.first { it.contains("from healthcare.vital_sign_records") && it.contains("count(*)") }
        assertTrue(listSql.contains("deleted_at is null"), "默认查询必须排除已作废记录: $listSql")
    }

    @Test
    fun `列表要求patient_id且支持类型与时间过滤`() {
        val stub = DatabaseStub(recordCounts = rows(mapOf("total" to 1L)), records = rows(recordRow()))
        val service = VitalSignService(stub.pool)

        val missing = causeOf(service.listVitalSigns(null, null, null, null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, missing)
        assertTrue(missing.message?.contains("patient_id is required") == true)

        val result = successOf(
            service.listVitalSigns("pat-1", "TEMPERATURE", "2026-08-01T00:00:00+08:00", "2026-08-31T23:59:59+08:00", 20, 0),
        ) as JsonObject
        assertEquals(1, result.getJsonArray("records").size())
        val sql = stub.queries.first { it.contains("from healthcare.vital_sign_records") && it.contains("count(*)") }
        assertTrue(sql.contains("patient_id = $"), "老人过滤生效: $sql")
        assertTrue(sql.contains("type = $"), "类型过滤生效: $sql")
        assertTrue(sql.contains("measured_at >= cast($") && sql.contains("measured_at <= cast($"), "时间范围过滤生效: $sql")

        val invalid = causeOf(service.listVitalSigns("pat-1", "HEART_RATE", null, null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, invalid)
    }

    @Test
    fun `详情不存在返回404`() {
        val stub = DatabaseStub(records = rowSet())
        val cause = causeOf(VitalSignService(stub.pool).getVitalSign("vs-missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
    }

    @Test
    fun `快照按类型取最近一条且患者不存在返回404`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            records = rows(
                recordRow(mapOf("type" to "TEMPERATURE", "value" to BigDecimal("36.5"))),
                recordRow(mapOf("id" to "vs-2", "type" to "PULSE", "value" to BigDecimal("78"))),
            ),
        )
        val result = successOf(VitalSignService(stub.pool).getSnapshot("pat-1")) as JsonObject
        assertEquals(2, result.getJsonArray("records").size())
        val snapshotSql = stub.queries.first { it.contains("distinct on") }
        assertTrue(snapshotSql.contains("from healthcare.vital_sign_records"), "快照基于体征表: $snapshotSql")
        assertTrue(snapshotSql.contains("as latest"), "快照使用派生表: $snapshotSql")
        assertTrue(snapshotSql.contains("measured_at") && snapshotSql.contains("desc"), "每类型取最近一条: $snapshotSql")

        stub.patients = rowSet()
        val cause = causeOf(VitalSignService(stub.pool).getSnapshot("pat-missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
    }

    @Test
    fun `趋势按类型时间升序且要求类型参数`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            records = rows(recordRow()),
        )
        val service = VitalSignService(stub.pool)

        val missing = causeOf(service.getTrend("pat-1", null, null, null))
        assertInstanceOf(IllegalArgumentException::class.java, missing)
        assertTrue(missing.message?.contains("type is required") == true)

        val result = successOf(service.getTrend("pat-1", "TEMPERATURE", null, null)) as JsonObject
        assertEquals(1, result.getJsonArray("records").size())
        val trendSql = stub.queries.first { it.contains("from healthcare.vital_sign_records") && it.contains("measured_at") && it.contains("asc") }
        assertTrue(trendSql.contains("type = $"), "趋势必须按类型过滤: $trendSql")
        assertTrue(trendSql.contains("deleted_at is null"), "趋势必须排除已作废记录: $trendSql")
    }

    // ========================================================================
    //  4. 修正与删除
    // ========================================================================

    @Test
    fun `修正重算异常标记并保留省略字段`() {
        val stub = DatabaseStub(
            records = rows(recordRow(mapOf("value" to BigDecimal("38.9"), "abnormal" to true))),
        )
        val service = VitalSignService(stub.pool)

        val result = successOf(
            service.updateVitalSign("vs-1", JsonObject().put("value", 38.9).put("note", "复测确认发热"), "user-1"),
        ) as JsonObject
        assertEquals("vs-1", result.getString("id"))
        assertTrue(result.getBoolean("abnormal"), "38.9℃ 超上限必须重算为异常")
        val updateSql = stub.queries.first { it.contains("update healthcare.vital_sign_records") }
        assertTrue(updateSql.contains("abnormal = $"), "修正必须重算 abnormal: $updateSql")
        assertTrue(updateSql.contains("deleted_at is null"), "已作废记录不可修正: $updateSql")
        val updateTuple = stub.tuples.first { it.first.contains("update healthcare.vital_sign_records") }.second
        assertTrue(updateTuple.contains(BigDecimal("38.9")), "修正值正确落库")
        assertTrue(updateTuple.contains(true), "abnormal=true 正确落库")
    }

    @Test
    fun `修正拒绝审计字段与非法数值且不触发SQL`() {
        val stub = DatabaseStub(records = rows(recordRow()))
        val service = VitalSignService(stub.pool)

        listOf("recorded_by", "abnormal", "created_at", "id", "patient_id", "type")
            .forEach { key ->
                val cause = causeOf(
                    service.updateVitalSign("vs-1", JsonObject().put("value", 37.0).put(key, "hacked"), "user-1"),
                )
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                assertTrue(cause.message?.contains("unsupported vital sign update keys") == true, "got: ${cause.message}")
            }
        val invalid = causeOf(service.updateVitalSign("vs-1", JsonObject().put("value", "36"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, invalid)
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    @Test
    fun `修正不存在或已作废记录返回404`() {
        val stub = DatabaseStub(records = rowSet())
        val cause = causeOf(VitalSignService(stub.pool).updateVitalSign("vs-missing", JsonObject().put("value", 37.0), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)

        stub.records = rows(recordRow(mapOf("deleted_at" to OffsetDateTime.now())))
        val deleted = causeOf(VitalSignService(stub.pool).updateVitalSign("vs-1", JsonObject().put("value", 37.0), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, deleted)
    }

    @Test
    fun `删除为软删除且重复删除返回404`() {
        val stub = DatabaseStub(records = rows(recordRow()))
        val service = VitalSignService(stub.pool)

        val result = successOf(service.deleteVitalSign("vs-1", "user-1")) as JsonObject
        assertEquals("vs-1", result.getString("id"))
        assertNotNull(result.getString("deleted_at"))
        val deleteSql = stub.queries.first { it.contains("update healthcare.vital_sign_records") }
        assertTrue(deleteSql.contains("deleted_at"), "删除必须置 deleted_at（软删除）: $deleteSql")

        stub.updateAffected = 0
        val again = causeOf(service.deleteVitalSign("vs-1", "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, again)
        assertTrue(again.message?.contains("not found") == true)
    }

    // ========================================================================
    //  5. 路由
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
    fun `写路由无认证上下文返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs", JsonArray().add(validRecord()).encode())
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
    fun `生命体征路由全链路可用`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            recordCounts = rows(mapOf("total" to 0L)),
            records = rowSet(),
        )
        withServer(vertx, stub, userId = "user-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs", JsonArray().add(validRecord()).encode())
                .compose { (createStatus, created) ->
                    ctx.verify {
                        assertEquals(201, createStatus, "创建必须 201")
                        val records = created.getJsonArray("records")
                        assertEquals(1, records.size())
                        assertTrue(ulidPattern.matches(records.getJsonObject(0).getString("id")))
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/vital-signs?patient_id=pat-1&limit=10&offset=0")
                        .compose { (listStatus, list) ->
                            ctx.verify {
                                assertEquals(200, listStatus)
                                assertEquals(0, list.getJsonArray("records").size())
                                assertEquals(0L, list.getJsonObject("meta").getLong("total"))
                            }
                            // 快照与趋势：命中 fixture 行
                            stub.records = rows(recordRow())
                            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/patients/pat-1/vital-signs/snapshot")
                                .compose { (snapshotStatus, snapshot) ->
                                    ctx.verify {
                                        assertEquals(200, snapshotStatus)
                                        assertEquals(1, snapshot.getJsonArray("records").size())
                                        assertEquals("TEMPERATURE", snapshot.getJsonArray("records").getJsonObject(0).getString("type"))
                                    }
                                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/patients/pat-1/vital-signs/trend?type=TEMPERATURE")
                                        .compose { (trendStatus, trend) ->
                                            ctx.verify {
                                                assertEquals(200, trendStatus)
                                                assertEquals(1, trend.getJsonArray("records").size())
                                            }
                                            // 修正：命中更新后的行
                                            stub.records = rows(recordRow(mapOf("value" to BigDecimal("38.9"), "abnormal" to true)))
                                            httpRequest(vertx, port, HttpMethod.PATCH, "/healthcare/v1/vital-signs/vs-1", JsonObject().put("value", 38.9).encode())
                                                .compose { (patchStatus, patched) ->
                                                    ctx.verify {
                                                        assertEquals(200, patchStatus)
                                                        assertTrue(patched.getBoolean("abnormal"))
                                                    }
                                                    httpRequest(vertx, port, HttpMethod.DELETE, "/healthcare/v1/vital-signs/vs-1")
                                                        .map { (deleteStatus, deleted) ->
                                                            ctx.verify {
                                                                assertEquals(200, deleteStatus)
                                                                assertEquals("vs-1", deleted.getString("id"))
                                                                assertNotNull(deleted.getString("deleted_at"))
                                                                ctx.completeNow()
                                                            }
                                                        }
                                                }
                                        }
                                }
                        }
                }
        }
    }

    @Test
    fun `非数组请求体返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub()
        withServer(vertx, stub, userId = "user-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs", validRecord().encode())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status)
                        assertTrue(body.getString("error")?.contains("JSON array") == true)
                        ctx.completeNow()
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
