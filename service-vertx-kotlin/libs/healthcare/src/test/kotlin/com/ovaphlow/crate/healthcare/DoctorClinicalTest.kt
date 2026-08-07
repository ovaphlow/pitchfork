package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 010 医生诊疗工作台非数据库测试：
 * 病程记录、诊断、医嘱 order_class 的输入校验、活动入住隔离和路由行为。
 */
class DoctorClinicalTest {

    // ——— 数据库桩：记录 SQL 并按语句类型返回配置好的行集 ———

    private inner class DatabaseStub(
        val encounters: RowSet<Row> = dcRows(encounterRow()),
        val periods: RowSet<Row> = dcRows(periodRow()),
        val notes: RowSet<Row> = dcRowSet(),
        val diagnoses: RowSet<Row> = dcRowSet(),
        val orders: RowSet<Row> = dcRowSet(),
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
                tuples.add(sql to dcTupleValues(firstArg()))
                val result = when {
                    sql.contains("insert into healthcare.progress_notes") -> dcRowSet()
                    sql.contains("insert into healthcare.diagnoses") -> dcRowSet()
                    sql.contains("insert into healthcare.medical_orders") -> dcRowSet()
                    sql.contains("insert into nursing.nursing_tasks") -> dcRowSet()
                    sql.contains("update healthcare.medical_orders") -> dcRowSet()
                    sql.contains("update nursing.nursing_tasks") -> dcRowSet()
                    sql.contains("count(*)") && sql.contains("from healthcare.progress_notes") -> dcRows(dcMockRow(mapOf("total" to 2L)))
                    sql.contains("count(*)") && sql.contains("from healthcare.diagnoses") -> dcRows(dcMockRow(mapOf("total" to 1L)))
                    sql.contains("count(*)") && sql.contains("from healthcare.medical_orders") -> dcRows(dcMockRow(mapOf("total" to 1L)))
                    sql.contains("from healthcare.patients") -> dcRowSet()
                    sql.contains("from healthcare.encounters") -> encounters
                    sql.contains("from healthcare.progress_notes") -> notes
                    sql.contains("from healthcare.diagnoses") -> diagnoses
                    sql.contains("from healthcare.medical_orders") -> orders
                    sql.contains("nursing_service_periods") -> periods
                    sql.contains("nursing_tasks") -> dcRowSet()
                    else -> dcRowSet()
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
            val normalizedSql = dcNormalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }
    }

    // ——— fixture 行 ———

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260801001",
            "department" to "三楼",
            "ward" to "301-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "death_date" to null,
            "death_cause" to null,
            "admitting_diagnosis" to "高血压",
            "discharge_diagnosis" to null,
            "attending_physician" to "赵医生",
            "status" to "ACTIVE",
            "metadata" to JsonObject(),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun periodRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "per-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to null,
            "coordinator" to "钱协调",
            "encounter_id" to "enc-1",
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun progressNoteRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "note-1",
            "encounter_id" to "enc-1",
            "note_type" to "DAILY",
            "content" to "今日精神状态稳定，食欲一般，继续观察。",
            "physician" to "张医生",
            "record_time" to OffsetDateTime.parse("2026-08-05T09:30:00+08:00"),
            "created_at" to OffsetDateTime.parse("2026-08-05T09:30:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun diagnosisRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "diag-1",
            "encounter_id" to "enc-1",
            "diagnosis_type" to "PRIMARY",
            "icd_code" to "I10",
            "diagnosis_text" to "高血压",
            "diagnosis_date" to LocalDate.of(2026, 8, 5),
            "physician" to "张医生",
            "is_major" to true,
            "metadata" to JsonObject().put("remark", "继续观察血压变化"),
            "created_at" to OffsetDateTime.parse("2026-08-05T09:30:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun orderRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "ord-1",
            "encounter_id" to "enc-1",
            "order_type" to "MEDICATION",
            "order_class" to "LONG_TERM",
            "order_content" to "降压药每日一次",
            "order_details" to JsonObject()
                .put("drug_name", "降压药")
                .put("frequency_code", "QD")
                .put("frequency_name", "每日一次"),
            "start_time" to OffsetDateTime.parse("2026-08-05T08:00:00+08:00"),
            "end_time" to null,
            "doctor" to "张医生",
            "status" to "ACTIVE",
            "task_id" to "tsk-1",
            "created_at" to OffsetDateTime.parse("2026-08-05T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-05T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun validNoteBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("note_type", "DAILY")
            .put("content", "今日精神状态稳定，食欲一般，继续观察。")
            .put("physician", "张医生")
            .put("record_time", "2026-08-05T09:30:00+08:00")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun validDiagnosisBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("diagnosis_type", "PRIMARY")
            .put("diagnosis_text", "高血压")
            .put("icd_code", "I10")
            .put("diagnosis_date", "2026-08-05")
            .put("physician", "张医生")
            .put("is_major", true)
            .put("remark", "继续观察血压变化")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun validOrderBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("order_type", "MEDICATION")
            .put("order_class", "LONG_TERM")
            .put("order_content", "降压药每日一次")
            .put("doctor", "张医生")
            .put("start_time", "2026-08-05T08:00:00+08:00")
            .put(
                "order_details",
                JsonObject()
                    .put("drug_name", "降压药")
                    .put("frequency_code", "QD")
                    .put("frequency_name", "每日一次"),
            )
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

    // ——— 1. 病程记录 ———

    @Test
    fun `创建病程记录写入精确encounter并返回完整字段`() {
        val stub = DatabaseStub()
        val future = HealthcareService(stub.pool).createProgressNote("enc-1", validNoteBody())
        val result = future.toCompletionStage().toCompletableFuture().get()

        assertEquals("enc-1", result.getString("encounter_id"))
        assertEquals("DAILY", result.getString("note_type"))
        assertEquals("今日精神状态稳定，食欲一般，继续观察。", result.getString("content"))
        assertEquals("张医生", result.getString("physician"))
        assertNotNull(result.getString("record_time"))
        assertNotNull(result.getString("id"))

        val insertSql = stub.queries.first { it.contains("insert into healthcare.progress_notes") }
        assertTrue(insertSql.contains("record_time"), "insert 必须写入 record_time: $insertSql")
        assertFalse(insertSql.contains("encounter_id = 'enc-2'"), "encounter 由路径决定")
    }

    @Test
    fun `创建病程记录输入校验全部返回400且不触发SQL`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.createProgressNote("enc-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(validNoteBody(mapOf("note_type" to null)), "note_type is required")
        expectInvalid(validNoteBody(mapOf("note_type" to "PROGRESS")), "invalid note_type")
        expectInvalid(validNoteBody(mapOf("note_type" to "NURSING")), "invalid note_type")

        expectInvalid(validNoteBody(mapOf("content" to null)), "content is required")
        expectInvalid(validNoteBody(mapOf("content" to "   ")), "content is required")
        expectInvalid(validNoteBody(mapOf("content" to "a".repeat(2001))), "2000")

        expectInvalid(validNoteBody(mapOf("physician" to null)), "physician is required")
        expectInvalid(validNoteBody(mapOf("physician" to "a".repeat(101))), "100")

        expectInvalid(validNoteBody(mapOf("record_time" to "2026-08-05 09:30")), "ISO-8601")

        // encounter_id 不能被请求体覆盖：未知键一律拒绝
        expectInvalid(validNoteBody(mapOf("encounter_id" to "enc-2")), "unsupported keys")
        expectInvalid(validNoteBody(mapOf("metadata" to JsonObject())), "unsupported keys")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
        assertEquals(0, stub.transactionCalls)
    }

    @Test
    fun `病程记录创建资格映射404与409`() {
        val notFound = DatabaseStub(encounters = dcRowSet())
        val cause1 = causeOf(HealthcareService(notFound.pool).createProgressNote("enc-1", validNoteBody()))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause1)
        assertTrue(cause1.message?.contains("encounter not found") == true, "got: ${cause1.message}")

        val notElderly = DatabaseStub(encounters = dcRows(encounterRow(mapOf("encounter_type" to "INPATIENT"))))
        val cause2 = causeOf(HealthcareService(notElderly.pool).createProgressNote("enc-1", validNoteBody()))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("not an elderly admission") == true, "got: ${cause2.message}")

        val discharged = DatabaseStub(encounters = dcRows(encounterRow(mapOf("status" to "DISCHARGED"))))
        val cause3 = causeOf(HealthcareService(discharged.pool).createProgressNote("enc-1", validNoteBody()))
        assertInstanceOf(ConflictException::class.java, cause3)
        assertTrue(cause3.message?.contains("encounter is not active") == true, "got: ${cause3.message}")

        val deceased = DatabaseStub(encounters = dcRows(encounterRow(mapOf("status" to "DECEASED"))))
        val cause4 = causeOf(HealthcareService(deceased.pool).createProgressNote("enc-1", validNoteBody()))
        assertInstanceOf(ConflictException::class.java, cause4)
        assertTrue(cause4.message?.contains("encounter is not active") == true, "got: ${cause4.message}")
    }

    @Test
    fun `病程记录列表按精确encounter过滤并支持类型与日期筛选`() {
        val stub = DatabaseStub(notes = dcRows(progressNoteRow()))
        val future = HealthcareService(stub.pool).listProgressNotes("enc-1", noteType = "DAILY", dateFrom = "2026-08-01", dateTo = "2026-08-31", limit = 20, offset = 5)
        val result = future.toCompletionStage().toCompletableFuture().get()

        assertEquals(2L, result.getJsonObject("meta").getLong("total"))
        assertEquals(1, result.getJsonArray("records").size())
        assertEquals("note-1", result.getJsonArray("records").getJsonObject(0).getString("id"))

        val listSql = stub.queries.first { it.contains("from healthcare.progress_notes") && !it.contains("count(*)") }
        assertTrue(listSql.contains("encounter_id = $1"), "列表必须按精确 encounter 隔离: $listSql")
        assertTrue(listSql.contains("note_type = $2"), "支持 note_type 过滤: $listSql")
        assertTrue(listSql.contains("record_time >= cast($3"), "支持 date_from: $listSql")
        assertTrue(listSql.contains("record_time < cast($4"), "date_to 取次日零点: $listSql")
        assertTrue(listSql.contains("record_time desc"), "record_time DESC: $listSql")
        assertTrue(listSql.contains("offset $5 rows fetch next $6 rows only"), "分页: $listSql")

        val listTuple = stub.tuples.firstOrNull { it.first.contains("from healthcare.progress_notes") && !it.first.contains("count(*)") }?.second
            ?: throw AssertionError("no data query tuple; queries=${stub.queries}; tuples=${stub.tuples.map { it.first }}")
        assertTrue(listTuple.contains("enc-1"), "列表查询必须绑定精确 encounter_id")
        assertTrue(listTuple.contains("DAILY"), "列表查询必须绑定 note_type")
        assertTrue(
            listTuple.any { it is OffsetDateTime && it.toString().startsWith("2026-08-01T00:00") },
            "date_from 绑定为当日零点: $listTuple",
        )
        assertTrue(
            listTuple.any { it is OffsetDateTime && it.toString().startsWith("2026-09-01T00:00") },
            "date_to 绑定为次日零点: $listTuple",
        )
    }

    @Test
    fun `病程记录列表非法日期返回400`() {
        val stub = DatabaseStub()
        val cause = causeOf(HealthcareService(stub.pool).listProgressNotes("enc-1", dateFrom = "not-a-date"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("invalid date_from") == true, "got: ${cause.message}")
    }

    @Test
    fun `病程记录详情未找到返回404`() {
        val stub = DatabaseStub()
        val cause = causeOf(HealthcareService(stub.pool).getProgressNote("missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("progress note not found") == true, "got: ${cause.message}")
    }

    // ——— 2. 诊断 ———

    @Test
    fun `创建诊断写入精确encounter并保存icd和remark`() {
        val stub = DatabaseStub()
        val future = HealthcareService(stub.pool).createDiagnosis("enc-1", validDiagnosisBody())
        val result = future.toCompletionStage().toCompletableFuture().get()

        assertEquals("enc-1", result.getString("encounter_id"))
        assertEquals("PRIMARY", result.getString("diagnosis_type"))
        assertEquals("I10", result.getString("icd_code"))
        assertEquals("高血压", result.getString("diagnosis_text"))
        assertEquals("2026-08-05", result.getString("diagnosis_date"))
        assertEquals("张医生", result.getString("physician"))
        assertEquals(true, result.getBoolean("is_major"))
        assertEquals("继续观察血压变化", result.getJsonObject("metadata").getString("remark"))

        val insertSql = stub.queries.first { it.contains("insert into healthcare.diagnoses") }
        assertTrue(insertSql.contains("icd_code"), "有编码时保存编码: $insertSql")
        assertTrue(insertSql.contains("metadata"), "remark 写入受控 metadata: $insertSql")
    }

    @Test
    fun `创建诊断输入校验全部返回400且不触发SQL`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.createDiagnosis("enc-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(validDiagnosisBody(mapOf("diagnosis_type" to null)), "diagnosis_type is required")
        expectInvalid(validDiagnosisBody(mapOf("diagnosis_type" to "主要诊断")), "invalid diagnosis_type")
        expectInvalid(validDiagnosisBody(mapOf("diagnosis_type" to "ASSESSMENT")), "invalid diagnosis_type")

        expectInvalid(validDiagnosisBody(mapOf("diagnosis_text" to null)), "diagnosis_text is required")
        expectInvalid(validDiagnosisBody(mapOf("diagnosis_text" to "  ")), "diagnosis_text is required")
        expectInvalid(validDiagnosisBody(mapOf("diagnosis_text" to "a".repeat(2001))), "2000")

        expectInvalid(validDiagnosisBody(mapOf("diagnosis_date" to null)), "diagnosis_date is required")
        expectInvalid(validDiagnosisBody(mapOf("diagnosis_date" to "2026/08/05")), "ISO-8601")

        expectInvalid(validDiagnosisBody(mapOf("physician" to null)), "physician is required")

        expectInvalid(validDiagnosisBody(mapOf("icd_code" to "a".repeat(33))), "32")
        expectInvalid(validDiagnosisBody(mapOf("is_major" to "yes")), "is_major must be a boolean")
        expectInvalid(validDiagnosisBody(mapOf("remark" to "a".repeat(501))), "500")

        // 患者/入住归属不能由请求体伪造
        expectInvalid(validDiagnosisBody(mapOf("encounter_id" to "enc-2")), "unsupported keys")
        expectInvalid(validDiagnosisBody(mapOf("patient_id" to "pat-2")), "unsupported keys")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
        assertEquals(0, stub.transactionCalls)
    }

    @Test
    fun `诊断创建资格映射404与409`() {
        val notElderly = DatabaseStub(encounters = dcRows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))))
        val cause1 = causeOf(HealthcareService(notElderly.pool).createDiagnosis("enc-1", validDiagnosisBody()))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("not an elderly admission") == true, "got: ${cause1.message}")

        val discharged = DatabaseStub(encounters = dcRows(encounterRow(mapOf("status" to "DISCHARGED"))))
        val cause2 = causeOf(HealthcareService(discharged.pool).createDiagnosis("enc-1", validDiagnosisBody()))
        assertInstanceOf(ConflictException::class.java, cause2)
        assertTrue(cause2.message?.contains("encounter is not active") == true, "got: ${cause2.message}")
    }

    @Test
    fun `诊断列表按精确encounter过滤并支持类型筛选`() {
        val stub = DatabaseStub(diagnoses = dcRows(diagnosisRow()))
        val future = HealthcareService(stub.pool).listDiagnoses("enc-1", diagnosisType = "PRIMARY")
        val result = future.toCompletionStage().toCompletableFuture().get()

        assertEquals(1L, result.getJsonObject("meta").getLong("total"))
        assertEquals("diag-1", result.getJsonArray("records").getJsonObject(0).getString("id"))
        assertEquals(true, result.getJsonArray("records").getJsonObject(0).getBoolean("is_major"))

        val listSql = stub.queries.first { it.contains("from healthcare.diagnoses") && !it.contains("count(*)") }
        assertTrue(listSql.contains("encounter_id = $1"), "诊断必须按精确 encounter 隔离: $listSql")
        assertTrue(listSql.contains("diagnosis_type = $2"), "支持诊断类型过滤: $listSql")
        assertTrue(listSql.contains("diagnosis_date desc"), "诊断日期倒序: $listSql")

        val listTuple = stub.tuples.firstOrNull { it.first.contains("from healthcare.diagnoses") && !it.first.contains("count(*)") }?.second
            ?: throw AssertionError("no data query tuple; queries=${stub.queries}; tuples=${stub.tuples.map { it.first }}")
        assertTrue(listTuple.contains("enc-1"), "诊断列表查询必须绑定精确 encounter_id")
        assertTrue(listTuple.contains("PRIMARY"), "诊断列表查询必须绑定诊断类型")
    }

    @Test
    fun `诊断详情未找到返回404`() {
        val stub = DatabaseStub()
        val cause = causeOf(HealthcareService(stub.pool).getDiagnosis("missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("diagnosis not found") == true, "got: ${cause.message}")
    }

    // ——— 3. 医嘱 order_class ———

    @Test
    fun `新建医嘱order_class必填且非法值返回400`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        val cause1 = causeOf(service.createOrder("enc-1", validOrderBody(mapOf("order_class" to null))))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("order_class is required") == true, "got: ${cause1.message}")

        val cause2 = causeOf(service.createOrder("enc-1", validOrderBody(mapOf("order_class" to "LONG"))))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("invalid order_class") == true, "got: ${cause2.message}")

        val cause3 = causeOf(service.createOrder("enc-1", validOrderBody(mapOf("order_class" to "长期"))))
        assertInstanceOf(IllegalArgumentException::class.java, cause3)
        assertTrue(cause3.message?.contains("invalid order_class") == true, "got: ${cause3.message}")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
        assertEquals(0, stub.transactionCalls)
    }

    @Test
    fun `新建医嘱写入order_class且响应带只读展示标签`() {
        val stub = DatabaseStub(orders = dcRows(orderRow(mapOf("order_class" to "TEMPORARY"))))
        val future = HealthcareService(stub.pool).createOrder(
            "enc-1",
            validOrderBody(
                mapOf(
                    "order_class" to "TEMPORARY",
                    "end_time" to "2026-08-05T09:00:00+08:00",
                ),
            ),
        )
        val result = future.toCompletionStage().toCompletableFuture().get()

        assertEquals("TEMPORARY", result.getString("order_class"))
        assertEquals("临时医嘱", result.getString("order_class_label"))
        assertEquals("MEDICATION", result.getString("order_type"))
        assertEquals("用药医嘱", result.getString("order_type_label"))

        val insertSql = stub.queries.first { it.contains("insert into healthcare.medical_orders") }
        assertTrue(insertSql.contains("order_class"), "新建医嘱必须写入 order_class: $insertSql")
        val insertTuple = stub.tuples.first { it.first.contains("insert into healthcare.medical_orders") }.second
        assertTrue(insertTuple.contains("TEMPORARY"), "insert 绑定值必须包含 order_class: $insertTuple")
    }

    @Test
    fun `临时医嘱没有结束条件返回400且不触发SQL`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)
        val cause = causeOf(
            service.createOrder(
                "enc-1",
                validOrderBody(
                    mapOf(
                        "order_class" to "TEMPORARY",
                        "order_details" to JsonObject().put("drug_name", "降压药"),
                    ),
                ),
            ),
        )

        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("requires end_time") == true, "got: ${cause.message}")
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
        assertEquals(0, stub.transactionCalls)
    }

    @Test
    fun `响应中历史空order_class返回null且标签不猜测`() {
        val stub = DatabaseStub(orders = dcRows(orderRow(mapOf("order_class" to null))))
        val result = HealthcareService(stub.pool).getOrder("ord-1").toCompletionStage().toCompletableFuture().get()

        assertNull(result.getString("order_class"))
        assertNull(result.getString("order_class_label"))
        assertEquals("用药医嘱", result.getString("order_type_label"))
    }

    @Test
    fun `检查检验医嘱接受计划内可选字段`() {
        val stub = DatabaseStub(orders = dcRows(orderRow()))
        val service = HealthcareService(stub.pool)

        val examBody = validOrderBody(
            mapOf(
                "order_type" to "EXAMINATION",
                "order_class" to "TEMPORARY",
                "end_time" to "2026-08-05T09:00:00+08:00",
                "order_details" to JsonObject()
                    .put("item_name", "胸部X光")
                    .put("body_part", "胸部")
                    .put("priority", "普通")
                    .put("clinical_note", "咳嗽三天")
                    .put("remark", "备注"),
            ),
        )
        service.createOrder("enc-1", examBody).toCompletionStage().toCompletableFuture().get()

        val labBody = validOrderBody(
            mapOf(
                "order_type" to "LAB_TEST",
                "order_class" to "LONG_TERM",
                "order_details" to JsonObject()
                    .put("item_name", "血常规")
                    .put("specimen_type", "静脉血")
                    .put("priority", "加急")
                    .put("fasting", true)
                    .put("clinical_note", "空腹采血"),
            ),
        )
        service.createOrder("enc-1", labBody).toCompletionStage().toCompletableFuture().get()
    }

    @Test
    fun `跨类型字段与未知字段仍返回400`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        // EXAMINATION 不能带用药字段
        val crossField = validOrderBody(
            mapOf(
                "order_type" to "EXAMINATION",
                "order_details" to JsonObject()
                    .put("item_name", "胸部X光")
                    .put("drug_name", "阿司匹林"),
            ),
        )
        val cause1 = causeOf(service.createOrder("enc-1", crossField))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("unsupported keys") == true, "got: ${cause1.message}")

        // 未知字段
        val unknown = validOrderBody(
            mapOf(
                "order_details" to JsonObject()
                    .put("drug_name", "降压药")
                    .put("hacked", true),
            ),
        )
        val cause2 = causeOf(service.createOrder("enc-1", unknown))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("unsupported keys") == true, "got: ${cause2.message}")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    @Test
    fun `PRN和STAT仍是频次不替代order_class`() {
        val stub = DatabaseStub(orders = dcRows(orderRow()))
        val service = HealthcareService(stub.pool)

        // 长期备用医嘱：LONG_TERM + PRN
        val longTermPrn = validOrderBody(
            mapOf(
                "order_details" to JsonObject()
                    .put("drug_name", "硝酸甘油")
                    .put("frequency_code", "PRN")
                    .put("frequency_name", "按需"),
            ),
        )
        val result1 = service.createOrder("enc-1", longTermPrn).toCompletionStage().toCompletableFuture().get()
        assertEquals("LONG_TERM", result1.getString("order_class"))

        // 临时立即执行：TEMPORARY + STAT
        val temporaryStub = DatabaseStub(orders = dcRows(orderRow(mapOf("order_class" to "TEMPORARY"))))
        val temporaryStat = validOrderBody(
            mapOf(
                "order_class" to "TEMPORARY",
                "order_details" to JsonObject()
                    .put("drug_name", "布洛芬")
                    .put("frequency_code", "STAT")
                    .put("frequency_name", "立即"),
            ),
        )
        val result2 = HealthcareService(temporaryStub.pool)
            .createOrder("enc-1", temporaryStat).toCompletionStage().toCompletableFuture().get()
        assertEquals("TEMPORARY", result2.getString("order_class"))
    }

    // ——— 公共辅助 ———

    private fun dcMockRow(values: Map<String, Any?>): Row {
        val row = mockk<Row>()
        every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
        every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
        every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
        every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
        every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
        every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
        every { row.getBoolean(any<String>()) } answers { values[firstArg<String>()] as? Boolean }
        return row
    }

    private fun dcRowSet(vararg rows: Row): RowSet<Row> {
        val rs = mockk<RowSet<Row>>()
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

    private fun dcRows(vararg rows: Row): RowSet<Row> = dcRowSet(*rows)

    private fun dcRows(values: List<Map<String, Any?>>): RowSet<Row> = dcRowSet(*values.map(::dcMockRow).toTypedArray())

    private fun dcRows(vararg values: Map<String, Any?>): RowSet<Row> = dcRows(values.toList())

    private fun dcNormalized(sql: String): String = sql.lowercase().replace("\"", "")

    private fun dcTupleValues(tuple: Tuple): List<Any?> {
        val values = mutableListOf<Any?>()
        for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
        return values
    }
}
