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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.function.Function as JavaFunction

/**
 * 慢病档案（ChronicDiseaseService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 活动入住可登记、一人多种病种；同病种「管理中」档案防重复建档
 *   - 登记成功即自动生成首轮「慢病随访」计划（planned_date=登记日+频率，不早于入住开始日）
 *   - 病种默认频率常量表 + metadata 覆盖；频率/控制状态/档案状态中文枚举白名单
 *   - 伪造归属、DECEASED、非活动入住一律拒绝且无副作用；审计字段白名单
 *   - 列表 {records, meta} 聚合（下次随访日/最近随访结果/是否逾期）；空列表 total=0
 *   - 随访完成联动：next_followup_date 优先、否则按频率滚动生成；幂等不重复；
 *     停管/已缓解后停止生成；非慢病随访、无档案关联的手动计划不生成
 *   - 状态机：管理中→已缓解/已停管；恢复管理中不与既有档案冲突
 *   - 写路由无认证 401；读取接口不产生计划
 */
@ExtendWith(VertxExtension::class)
class ChronicDiseaseServiceTest {

    private class DatabaseStub(
        var patients: RowSet<Row> = rowSet(),
        var encounters: RowSet<Row> = rowSet(),
        var registrations: RowSet<Row> = rowSet(),
        var registrationCounts: RowSet<Row> = rowSet(),
        var planCounts: RowSet<Row> = rowSet(),
        var recordCounts: RowSet<Row> = rowSet(),
        var selectOneRegistrations: RowSet<Row> = rowSet(),
        var selectOnePlans: RowSet<Row> = rowSet(),
        var lockRegistrations: RowSet<Row> = rowSet(),
        var plans: RowSet<Row> = rowSet(),
        var records: RowSet<Row> = rowSet(),
        var notes: RowSet<Row> = rowSet(),
        var registrationUpdateAffected: Int = 1,
        var planUpdateAffected: Int = 1,
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
                    sql.contains("insert into healthcare.followup_plans") -> rowSet()
                    sql.contains("insert into healthcare.followup_records") -> rowSet()
                    sql.contains("insert into healthcare.chronic_disease_registrations") -> rowSet()
                    sql.contains("update healthcare.chronic_disease_registrations") -> updated(registrationUpdateAffected)
                    sql.contains("update healthcare.followup_plans") && sql.contains("cancel_reason") -> updated(planUpdateAffected)
                    sql.contains("update healthcare.followup_plans") -> updated(planUpdateAffected)
                    sql.contains("update healthcare.followup_records") -> updated(1)
                    sql.contains("count(*)") && sql.contains("from healthcare.chronic_disease_registrations") -> registrationCounts
                    sql.contains("count(*)") && sql.contains("from healthcare.followup_plans") -> planCounts
                    sql.contains("count(*)") && sql.contains("from healthcare.followup_records") -> recordCounts
                    // jOOQ 渲染 select 1 as "one"，存在性/幂等查询必须按列名+表名双条件匹配
                    sql.contains("select 1") && sql.contains("from healthcare.chronic_disease_registrations") -> selectOneRegistrations
                    sql.contains("select 1") && sql.contains("from healthcare.followup_plans") -> selectOnePlans
                    sql.contains("for update") && sql.contains("from healthcare.chronic_disease_registrations") -> lockRegistrations
                    // 档案主查询（含关联子查询）必须先于 followup_plans/records 独立查询
                    sql.contains("from healthcare.chronic_disease_registrations") -> registrations
                    sql.contains("from healthcare.progress_notes") -> notes
                    sql.contains("from healthcare.followup_records") -> records
                    sql.contains("from healthcare.followup_plans") -> plans
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

    private fun registrationRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "cd-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "disease_name" to "高血压",
            "icd_code" to "I10",
            "confirmed_date" to LocalDate.parse("2026-08-01"),
            "control_status" to "良好",
            "followup_frequency" to "每月",
            "physician" to "李医生",
            "remark" to null,
            "status" to "管理中",
            "metadata" to null,
            "next_followup_date" to LocalDate.parse("2026-09-01"),
            "recent_followup_date" to null,
            "recent_followup_result" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun planRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "plan-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "followup_type" to "慢病随访",
            "planned_date" to LocalDate.parse("2026-08-10"),
            "planned_way" to "电话",
            "assignee" to "user-1",
            "status" to "待随访",
            "completed_at" to null,
            "cancel_reason" to null,
            "remark" to null,
            "metadata" to JsonObject().put("chronic_disease_id", "cd-1"),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun recordRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "rec-1",
            "plan_id" to "plan-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "followup_type" to "慢病随访",
            "followup_way" to "电话",
            "followup_date" to OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
            "contact_object" to "张奶奶",
            "condition_summary" to "血压平稳",
            "vitals" to JsonObject().put("systolic", 130),
            "guidance" to "继续服药",
            "result" to "正常",
            "next_followup_date" to LocalDate.parse("2026-09-10"),
            "operator" to "user-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun validBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("patient_id", "pat-1")
            .put("encounter_id", "enc-1")
            .put("disease_name", "高血压")
            .put("icd_code", "I10")
            .put("confirmed_date", "2026-08-01")
            .put("control_status", "良好")
            .put("followup_frequency", "每月")
            .put("physician", "李医生")
            .put("remark", "长期服药")
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
    private val businessZone = ZoneId.of("Asia/Shanghai")

    private fun today(): LocalDate = LocalDate.now(businessZone)

    private fun planInserts(stub: DatabaseStub): List<List<Any?>> =
        stub.tuples.filter { it.first.contains("insert into healthcare.followup_plans") }.map { it.second }

    // ========================================================================
    //  1. 慢病登记 + 自动生成首轮计划
    // ========================================================================

    @Test
    fun `登记成功返回ULID且同一事务自动生成首轮慢病随访计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
        )
        val service = ChronicDiseaseService(stub.pool)

        val result = successOf(service.createRegistration(validBody(), "user-1")) as JsonObject
        assertTrue(ulidPattern.matches(result.getString("id")), "id 必须为 26 位 ULID")
        assertEquals("张奶奶", result.getString("patient_name"))
        assertEquals("高血压", result.getString("disease_name"))
        assertEquals("良好", result.getString("control_status"))
        assertEquals("每月", result.getString("followup_frequency"))
        assertEquals("管理中", result.getString("status"))
        assertNotNull(result.getString("created_at"))
        assertTrue(stub.queries.any { it.contains("insert into healthcare.chronic_disease_registrations") })
        assertTrue(
            stub.queries.any { it.contains("select 1") && it.contains("from healthcare.chronic_disease_registrations") },
            "必须做同病种查重",
        )
        val planInserts = planInserts(stub)
        assertEquals(1, planInserts.size, "登记成功必须同事务生成一条随访计划")
        val insert = planInserts[0]
        assertTrue(insert.contains("pat-1"), "计划必须携带 patient_id")
        assertTrue(insert.contains("enc-1"), "计划必须携带 encounter_id")
        assertTrue(insert.contains("慢病随访"), "计划类型必须为慢病随访")
        assertTrue(insert.contains("电话"), "默认方式为电话")
        assertTrue(insert.contains("user-1"), "assignee 必须取自认证主体")
        assertTrue(insert.contains("待随访"), "计划初始状态为待随访")
        assertTrue(insert.any { it == today().plusMonths(1) }, "planned_date = 登记日 + 每月，实际: $insert")
    }

    @Test
    fun `登记planned_date不早于入住开始日`() {
        val admit = today().plusMonths(3)
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("admit_date" to admit.atStartOfDay(businessZone).toOffsetDateTime()))),
        )
        successOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        val insert = planInserts(stub).single()
        assertTrue(insert.any { it == admit }, "planned_date 不得早于入住开始日，实际: $insert")
    }

    @Test
    fun `登记频率缺省时按病种默认频率表`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        successOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(mapOf("followup_frequency" to null)), "user-1"))
        val registrationInsert = stub.tuples.first { it.first.contains("insert into healthcare.chronic_disease_registrations") }.second
        assertTrue(registrationInsert.any { it == "每月" }, "高血压默认频率应为每月，实际: $registrationInsert")
        val insert = planInserts(stub).single()
        assertTrue(insert.any { it == today().plusMonths(1) }, "首轮计划按默认频率滚动")
    }

    @Test
    fun `登记频率缺省且病种无默认频率时报错且无SQL`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val cause = causeOf(
            ChronicDiseaseService(stub.pool).createRegistration(
                validBody(mapOf("disease_name" to "阿尔茨海默病", "followup_frequency" to null)),
                "user-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("followup_frequency is required") == true, "got: ${cause.message}")
        assertTrue(stub.queries.isEmpty(), "输入校验失败不得触发任何 SQL")
    }

    @Test
    fun `登记频率可用metadata覆盖默认频率`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        successOf(
            ChronicDiseaseService(stub.pool).createRegistration(
                validBody(
                    mapOf(
                        "followup_frequency" to null,
                        "metadata" to JsonObject().put("followup_frequency", "每季度"),
                    ),
                ),
                "user-1",
            ),
        )
        val registrationInsert = stub.tuples.first { it.first.contains("insert into healthcare.chronic_disease_registrations") }.second
        assertTrue(registrationInsert.any { it == "每季度" }, "metadata 覆盖默认频率，实际: $registrationInsert")
        val insert = planInserts(stub).single()
        assertTrue(insert.any { it == today().plusMonths(3) })
    }

    @Test
    fun `登记拒绝审计与状态字段`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = ChronicDiseaseService(stub.pool)

        listOf("id", "status", "assignee", "created_at", "updated_at", "operator")
            .forEach { key ->
                val cause = causeOf(service.createRegistration(validBody(mapOf(key to "hacked")), "user-1"))
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                assertTrue(cause.message?.contains("unsupported chronic disease registration keys") == true, "got: ${cause.message}")
            }
        assertTrue(stub.queries.isEmpty(), "白名单校验失败不得触发任何 SQL")
    }

    @Test
    fun `登记校验必填与枚举`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = ChronicDiseaseService(stub.pool)

        fun expectInvalid(body: JsonObject, fragment: String) {
            val cause = causeOf(service.createRegistration(body, "user-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid(validBody(mapOf("patient_id" to null)), "patient_id is required")
        expectInvalid(validBody(mapOf("encounter_id" to null)), "encounter_id is required")
        expectInvalid(validBody(mapOf("disease_name" to null)), "disease_name is required")
        expectInvalid(validBody(mapOf("disease_name" to "a".repeat(101))), "100")
        expectInvalid(validBody(mapOf("confirmed_date" to null)), "confirmed_date is required")
        expectInvalid(validBody(mapOf("confirmed_date" to "2026/08/01")), "ISO-8601")
        expectInvalid(validBody(mapOf("control_status" to "失控")), "invalid control_status")
        expectInvalid(validBody(mapOf("followup_frequency" to "每周")), "invalid followup_frequency")
        expectInvalid(validBody(mapOf("remark" to "a".repeat(501))), "500")
        assertTrue(stub.queries.isEmpty(), "输入校验失败不得触发任何 SQL")
    }

    @Test
    fun `登记患者不存在返回404`() {
        val stub = DatabaseStub(patients = rowSet(), encounters = rows(encounterRow()))
        val cause = causeOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("patient not found") == true)
    }

    @Test
    fun `登记DECEASED患者拒绝且无副作用`() {
        val stub = DatabaseStub(
            patients = rows(patientRow(mapOf("status" to "DECEASED"))),
            encounters = rows(encounterRow()),
        )
        val cause = causeOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("deceased") == true)
        assertTrue(stub.queries.none { it.contains("insert") }, "DECEASED 登记不得产生任何写入")
    }

    @Test
    fun `登记伪造encounter归属拒绝`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("patient_id" to "pat-2"))),
        )
        val cause = causeOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("encounter does not belong") == true)
        assertTrue(stub.queries.none { it.contains("insert") })
    }

    @Test
    fun `登记非养老入住拒绝`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("encounter_type" to "INPATIENT"))),
        )
        val cause = causeOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("ELDERLY_CARE") == true)
        assertTrue(stub.queries.none { it.contains("insert") })
    }

    @Test
    fun `登记非活动入住拒绝`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("status" to "DISCHARGED"))),
        )
        val cause = causeOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("active admission") == true)
        assertTrue(stub.queries.none { it.contains("insert") })
    }

    @Test
    fun `同一患者同一病种重复登记返回409`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            selectOneRegistrations = rows(mapOf("id" to "cd-1")),
        )
        val cause = causeOf(ChronicDiseaseService(stub.pool).createRegistration(validBody(), "user-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already exists") == true)
        assertTrue(stub.queries.none { it.contains("insert") }, "重复建档不得写入")
    }

    @Test
    fun `同一患者可登记多种病种`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = ChronicDiseaseService(stub.pool)
        successOf(service.createRegistration(validBody(), "user-1"))
        successOf(service.createRegistration(validBody(mapOf("disease_name" to "糖尿病")), "user-1"))
        assertEquals(2, planInserts(stub).size, "两种病种各生成一条计划")
    }

    // ========================================================================
    //  2. 列表与详情
    // ========================================================================

    @Test
    fun `列表返回records与meta且带聚合字段`() {
        val stub = DatabaseStub(
            registrationCounts = rows(mapOf("total" to 1L)),
            registrations = rows(
                registrationRow(
                    mapOf(
                        "next_followup_date" to LocalDate.parse("2026-08-05"),
                        "recent_followup_date" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
                        "recent_followup_result" to "正常",
                    ),
                ),
            ),
        )
        val result = successOf(
            ChronicDiseaseService(stub.pool).listRegistrations(null, null, null, null, 50, 0),
        ) as JsonObject
        assertEquals(1L, result.getJsonObject("meta").getLong("total"))
        val record = result.getJsonArray("records").getJsonObject(0)
        assertEquals("张奶奶", record.getString("patient_name"))
        assertEquals("A20260801001", record.getString("encounter_no"))
        assertEquals("2026-08-05", record.getString("next_followup_date"))
        assertTrue(record.getBoolean("is_overdue"), "待随访计划日早于今天应标记逾期")
        assertEquals("正常", record.getString("recent_followup_result"))
        assertNotNull(record.getString("recent_followup_date"))
    }

    @Test
    fun `列表未逾期时is_overdue为false`() {
        val stub = DatabaseStub(
            registrationCounts = rows(mapOf("total" to 1L)),
            registrations = rows(registrationRow(mapOf("next_followup_date" to LocalDate.parse("2099-01-01")))),
        )
        val result = successOf(ChronicDiseaseService(stub.pool).listRegistrations(null, null, null, null, 50, 0)) as JsonObject
        assertFalse(result.getJsonArray("records").getJsonObject(0).getBoolean("is_overdue"))
    }

    @Test
    fun `列表筛选非法控制状态拒绝`() {
        val stub = DatabaseStub()
        val cause = causeOf(ChronicDiseaseService(stub.pool).listRegistrations(null, null, "失控", null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("invalid control_status") == true)
        assertTrue(stub.queries.isEmpty())
    }

    @Test
    fun `空列表返回records空数组与total0`() {
        val stub = DatabaseStub(registrationCounts = rows(mapOf("total" to 0L)), registrations = rowSet())
        val result = successOf(ChronicDiseaseService(stub.pool).listRegistrations(null, null, null, null, 50, 0)) as JsonObject
        assertEquals(0, result.getJsonArray("records").size())
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))
    }

    @Test
    fun `档案详情不存在返回404`() {
        val stub = DatabaseStub(registrations = rowSet())
        val cause = causeOf(ChronicDiseaseService(stub.pool).getRegistration("cd-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("chronic disease registration not found") == true)
    }

    @Test
    fun `档案时间线返回病程随访计划与记录`() {
        val stub = DatabaseStub(
            selectOneRegistrations = rows(mapOf("id" to "cd-1")),
            notes = rows(
                mapOf(
                    "id" to "note-1", "encounter_id" to "enc-1", "note_type" to "CHRONIC",
                    "content" to "血压控制平稳", "physician" to "李医生",
                    "record_time" to OffsetDateTime.parse("2026-08-05T10:00:00+08:00"),
                    "metadata" to JsonObject().put("chronic_disease_id", "cd-1"),
                    "created_at" to OffsetDateTime.parse("2026-08-05T10:00:00+08:00"),
                ),
            ),
            plans = rows(planRow(mapOf("planned_date" to LocalDate.parse("2026-08-01"), "status" to "待随访"))),
            records = rows(recordRow()),
        )
        val result = successOf(ChronicDiseaseService(stub.pool).getRegistrationTimeline("cd-1")) as JsonObject
        assertEquals("cd-1", result.getString("chronic_disease_id"))
        val noteList = result.getJsonArray("progress_notes")
        assertEquals(1, noteList.size())
        assertEquals("CHRONIC", noteList.getJsonObject(0).getString("note_type"))
        val planList = result.getJsonArray("followup_plans")
        assertEquals(1, planList.size())
        assertEquals("已逾期", planList.getJsonObject(0).getString("status"), "待随访且已过计划日按逾期展示")
        val recordList = result.getJsonArray("followup_records")
        assertEquals(1, recordList.size())
        assertEquals("正常", recordList.getJsonObject(0).getString("result"))
    }

    @Test
    fun `档案时间线档案不存在返回404`() {
        val stub = DatabaseStub(selectOneRegistrations = rowSet())
        val cause = causeOf(ChronicDiseaseService(stub.pool).getRegistrationTimeline("cd-x"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("chronic disease registration not found") == true)
    }

    // ========================================================================
    //  3. 状态 PATCH
    // ========================================================================

    @Test
    fun `档案状态可停管与恢复`() {
        val stub = DatabaseStub(
            lockRegistrations = rows(registrationRow(mapOf("status" to "管理中"))),
            registrations = rows(registrationRow(mapOf("status" to "已停管"))),
        )
        val service = ChronicDiseaseService(stub.pool)
        val stopped = successOf(service.updateRegistrationStatus("cd-1", JsonObject().put("status", "已停管"), "user-1")) as JsonObject
        assertEquals("已停管", stopped.getString("status"))
        assertTrue(stub.queries.any { it.contains("update healthcare.chronic_disease_registrations") })
        assertTrue(stub.queries.any { it.contains("for update") }, "状态变更必须锁定档案行")
    }

    @Test
    fun `状态PATCH拒绝白名单外字段`() {
        val stub = DatabaseStub(registrations = rows(registrationRow()))
        val cause = causeOf(
            ChronicDiseaseService(stub.pool).updateRegistrationStatus(
                "cd-1",
                JsonObject().put("status", "已停管").put("remark", "x"),
                "user-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("unsupported status update keys") == true)
        assertTrue(stub.queries.isEmpty())
    }

    @Test
    fun `状态PATCH拒绝非法状态`() {
        val stub = DatabaseStub(registrations = rows(registrationRow()))
        val cause = causeOf(
            ChronicDiseaseService(stub.pool).updateRegistrationStatus("cd-1", JsonObject().put("status", "已治愈"), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("invalid status") == true)
    }

    @Test
    fun `状态PATCH相同状态拒绝`() {
        val stub = DatabaseStub(
            lockRegistrations = rows(registrationRow(mapOf("status" to "管理中"))),
        )
        val cause = causeOf(
            ChronicDiseaseService(stub.pool).updateRegistrationStatus("cd-1", JsonObject().put("status", "管理中"), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("already 管理中") == true)
    }

    @Test
    fun `恢复管理中与既有档案冲突返回409`() {
        val stub = DatabaseStub(
            lockRegistrations = rows(registrationRow(mapOf("status" to "已停管"))),
            selectOneRegistrations = rows(mapOf("id" to "cd-other")),
        )
        val cause = causeOf(
            ChronicDiseaseService(stub.pool).updateRegistrationStatus("cd-1", JsonObject().put("status", "管理中"), "user-1"),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already exists") == true)
        assertTrue(stub.queries.none { it.contains("update healthcare.chronic_disease_registrations") })
    }

    @Test
    fun `档案不存在状态PATCH返回404`() {
        val stub = DatabaseStub(registrations = rowSet())
        val cause = causeOf(
            ChronicDiseaseService(stub.pool).updateRegistrationStatus("cd-x", JsonObject().put("status", "已停管"), "user-1"),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
    }

    // ========================================================================
    //  4. 随访完成联动（经 FollowupService 完成路径）
    // ========================================================================

    private fun linkedFollowupService(stub: DatabaseStub): FollowupService =
        FollowupService(stub.pool, chronicDiseaseService = ChronicDiseaseService(stub.pool))

    private fun completePlan(stub: DatabaseStub, body: JsonObject): JsonObject =
        successOf(linkedFollowupService(stub).updatePlanStatus("plan-1", body, "user-1")) as JsonObject

    @Test
    fun `随访完成按next_followup_date生成下一轮`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成"))),
            records = rows(recordRow()),
            registrations = rows(registrationRow()),
        )
        val plan = completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        assertEquals("已完成", plan.getString("status"))
        val inserts = planInserts(stub)
        assertEquals(1, inserts.size, "完成必须滚动生成下一轮计划")
        val insert = inserts[0]
        assertTrue(insert.any { it == LocalDate.parse("2026-09-10") }, "next_followup_date 优先，实际: $insert")
        assertTrue(insert.any { it == "user-1" }, "assignee 取自认证主体")
        assertTrue(
            stub.queries.any { it.contains("select 1") && it.contains("from healthcare.followup_plans") },
            "必须做幂等检查",
        )
    }

    @Test
    fun `随访完成无next_followup_date时按档案频率滚动`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成"))),
            records = rows(recordRow(mapOf("next_followup_date" to null))),
            registrations = rows(registrationRow(mapOf("followup_frequency" to "每月"))),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        val insert = planInserts(stub).single()
        assertTrue(insert.any { it == LocalDate.parse("2026-09-10") }, "计划日 2026-08-10 + 每月，实际: $insert")
    }

    @Test
    fun `重复完成同一计划不产生重复计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成"))),
            records = rows(recordRow()),
            registrations = rows(registrationRow()),
            // 幂等检查命中：同档案同 planned_date 已存在未取消计划
            selectOnePlans = rows(mapOf("one" to 1)),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        assertEquals(0, planInserts(stub).size, "已有活跃计划时不得重复生成")
    }

    @Test
    fun `档案停管后完成随访不生成新计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成"))),
            records = rows(recordRow()),
            registrations = rows(registrationRow(mapOf("status" to "已停管"))),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        assertEquals(0, planInserts(stub).size, "停管后停止自动生成")
    }

    @Test
    fun `档案已缓解后完成随访不生成新计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成"))),
            records = rows(recordRow()),
            registrations = rows(registrationRow(mapOf("status" to "已缓解"))),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        assertEquals(0, planInserts(stub).size)
    }

    @Test
    fun `非慢病随访计划完成不生成新计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成", "followup_type" to "出院后随访"))),
            records = rows(recordRow()),
            registrations = rows(registrationRow()),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        assertEquals(0, planInserts(stub).size, "非慢病随访计划不触发自动生成")
    }

    @Test
    fun `无档案关联的手动计划完成不生成新计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成", "metadata" to JsonObject().put("source", "手动创建")))),
            records = rows(recordRow()),
            registrations = rows(registrationRow()),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        assertEquals(0, planInserts(stub).size, "手动创建的慢病随访计划不自动滚动")
    }

    @Test
    fun `next_followup_date早于入住开始日时提升到入住日`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow(mapOf("status" to "已完成"))),
            records = rows(recordRow(mapOf("next_followup_date" to LocalDate.parse("2026-07-01")))),
            registrations = rows(registrationRow()),
        )
        completePlan(stub, JsonObject().put("status", "已完成").put("record_id", "rec-1"))
        val insert = planInserts(stub).single()
        assertTrue(insert.any { it == LocalDate.parse("2026-08-01") }, "不得早于入住开始日 2026-08-01，实际: $insert")
    }

    @Test
    fun `带plan的随访记录创建同样触发滚动生成`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            plans = rows(planRow()),
            records = rows(recordRow()),
            registrations = rows(registrationRow()),
        )
        val body = JsonObject()
            .put("plan_id", "plan-1")
            .put("patient_id", "pat-1")
            .put("encounter_id", "enc-1")
            .put("followup_type", "慢病随访")
            .put("result", "正常")
            .put("followup_date", "2026-08-10T10:00:00+08:00")
            .put("next_followup_date", "2026-09-10")
        successOf(linkedFollowupService(stub).createRecord(body, "user-1"))
        val insert = planInserts(stub).single()
        assertTrue(insert.any { it == LocalDate.parse("2026-09-10") }, "记录创建完成路径同样滚动生成，实际: $insert")
    }

    // ========================================================================
    //  5. 路由
    // ========================================================================

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

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "127.0.0.1", path)
            .compose { req ->
                req.putHeader("Content-Type", "application/json")
                if (body != null) req.end(body.encode()) else req.end()
                req.response().compose { resp ->
                    resp.body().map { buffer ->
                        Pair(resp.statusCode(), buffer.toJsonObject())
                    }
                }
            }
            .onComplete { client.close() }
    }

    @Test
    fun `慢病写路由无认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/chronic-diseases", validBody())
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
    fun `慢病路由全链路可用且读取不产生计划`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            registrationCounts = rows(mapOf("total" to 0L)),
            registrations = rowSet(),
        )
        withServer(vertx, stub, userId = "user-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/chronic-diseases", validBody())
                .compose { (createStatus, created) ->
                    ctx.verify {
                        assertEquals(201, createStatus, "创建档案必须 201")
                        assertTrue(ulidPattern.matches(created.getString("id")))
                    }
                    // 登记阶段允许的写入（档案 insert + 首轮计划 insert）至此结束
                    val createQueryCount = stub.queries.size
                    httpRequest(
                        vertx, port, HttpMethod.GET,
                        "/healthcare/v1/chronic-diseases?limit=10&offset=0",
                    ).compose { (listStatus, list) ->
                        ctx.verify {
                            assertEquals(200, listStatus)
                            assertEquals(0, list.getJsonArray("records").size())
                            assertEquals(0L, list.getJsonObject("meta").getLong("total"))
                        }
                        // 档案时间线：先存在性检查（selectOne），再查病程/计划/记录
                        stub.selectOneRegistrations = rows(mapOf("id" to "cd-1"))
                        httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/chronic-diseases/cd-1/timeline")
                            .compose { (timelineStatus, timeline) ->
                                ctx.verify {
                                    assertEquals(200, timelineStatus)
                                    assertNotNull(timeline.getJsonArray("progress_notes"))
                                    assertNotNull(timeline.getJsonArray("followup_plans"))
                                    assertNotNull(timeline.getJsonArray("followup_records"))
                                }
                                // 状态 PATCH：锁定行 + 更新后详情（已停管）
                                stub.lockRegistrations = rows(registrationRow())
                                stub.registrations = rows(registrationRow(mapOf("status" to "已停管")))
                                httpRequest(vertx, port, HttpMethod.PATCH, "/healthcare/v1/chronic-diseases/cd-1/status", JsonObject().put("status", "已停管"))
                                    .map { (patchStatus, patched) ->
                                        ctx.verify {
                                            assertEquals(200, patchStatus)
                                            assertEquals("已停管", patched.getString("status"))
                                            // 读取接口（列表/时间线/详情）与状态 PATCH 不得产生任何新计划/写入
                                            assertTrue(
                                                stub.queries.drop(createQueryCount).none { it.contains("insert") },
                                                "登记后读取/状态变更不得产生计划或写入",
                                            )
                                            ctx.completeNow()
                                        }
                                    }
                            }
                    }
                }
        }
    }
}

// ——— mock 基础设施 ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
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
