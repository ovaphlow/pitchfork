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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 随访管理（FollowupService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 创建计划归属/类型/日期合法性校验、DECEASED 患者拒绝、审计字段白名单
 *   - 列表分页与 overdue 计算（已逾期不落库）、空列表 records:[] total:0
 *   - 带 plan_id 的随访记录单事务完成计划；并发重复完成只有一次成功
 *   - 取消必须带原因；已完成/已取消不可再次流转
 *   - 记录创建后无编辑/删除路由；写接口拒绝 assignee/operator/created_at
 *   - 记录时间不得晚于当前时间；老人随访历史时间线
 */
@ExtendWith(VertxExtension::class)
class FollowupServiceTest {

    private class DatabaseStub(
        var patients: RowSet<Row> = rowSet(),
        var encounters: RowSet<Row> = rowSet(),
        var plans: RowSet<Row> = rowSet(),
        var planCounts: RowSet<Row> = rowSet(),
        var records: RowSet<Row> = rowSet(),
        var recordCounts: RowSet<Row> = rowSet(),
        var selectOnePatients: RowSet<Row> = rowSet(),
        var planCompleteAffected: Int = 1,
        var planCancelAffected: Int = 1,
        var linkRecordAffected: Int = 1,
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
                    // 取消更新会 SET cancel_reason；完成更新不带该列，据此区分两条更新路径
                    sql.contains("update healthcare.followup_plans") && sql.contains("cancel_reason") -> updated(planCancelAffected)
                    sql.contains("update healthcare.followup_plans") -> updated(planCompleteAffected)
                    sql.contains("update healthcare.followup_records") -> updated(linkRecordAffected)
                    // jOOQ 渲染 count(*) as "total"，必须按 count(*) 前缀路由计数查询
                    sql.contains("count(*)") && sql.contains("from healthcare.followup_plans") -> planCounts
                    sql.contains("count(*)") && sql.contains("from healthcare.followup_records") -> recordCounts
                    sql.contains("from healthcare.followup_plans") -> plans
                    sql.contains("from healthcare.followup_records") -> records
                    // jOOQ 渲染 select 1 as "one"，必须按列名+表名匹配存在性查询
                    sql.contains("select 1") && sql.contains("from healthcare.patients") -> selectOnePatients
                    sql.contains("from healthcare.patients") -> patients
                    sql.contains("from healthcare.encounters") -> encounters
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
            "metadata" to null,
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

    private fun validPlanBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("patient_id", "pat-1")
            .put("encounter_id", "enc-1")
            .put("followup_type", "慢病随访")
            .put("planned_date", "2026-08-10")
            .put("planned_way", "电话")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun validRecordBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("plan_id", "plan-1")
            .put("patient_id", "pat-1")
            .put("encounter_id", "enc-1")
            .put("followup_type", "慢病随访")
            .put("followup_way", "电话")
            .put("followup_date", "2026-08-10T10:00:00+08:00")
            .put("contact_object", "张奶奶")
            .put("condition_summary", "血压平稳")
            .put("result", "正常")
            .put("next_followup_date", "2026-09-10")
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
    //  1. 创建计划
    // ========================================================================

    @Test
    fun `创建计划成功返回ULID与审计字段且默认方式为电话`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
        )
        val service = FollowupService(stub.pool)

        val result = successOf(service.createPlan(validPlanBody(), "user-1")) as JsonObject
        assertTrue(ulidPattern.matches(result.getString("id")), "id 必须为 26 位 ULID")
        assertEquals("张奶奶", result.getString("patient_name"))
        assertEquals("慢病随访", result.getString("followup_type"))
        assertEquals("电话", result.getString("planned_way"))
        assertEquals("user-1", result.getString("assignee"), "assignee 必须取自认证上下文")
        assertEquals("待随访", result.getString("status"))
        assertNotNull(result.getString("created_at"))
        assertNotNull(result.getString("updated_at"))
        assertTrue(stub.queries.any { it.contains("insert into healthcare.followup_plans") })
        val insertTuple = stub.tuples.first { it.first.contains("insert into healthcare.followup_plans") }.second
        assertTrue(insertTuple.contains("user-1"), "责任人来自认证主体")
    }

    @Test
    fun `创建计划方式省略时默认电话`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
        )
        val result = successOf(FollowupService(stub.pool).createPlan(validPlanBody(mapOf("planned_way" to null)), "user-1")) as JsonObject
        assertEquals("电话", result.getString("planned_way"))
    }

    @Test
    fun `创建计划拒绝审计与状态字段`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = FollowupService(stub.pool)

        listOf("assignee", "operator", "created_at", "updated_at", "status", "completed_at", "id")
            .forEach { key ->
                val cause = causeOf(service.createPlan(validPlanBody(mapOf(key to "hacked")), "user-1"))
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                assertTrue(cause.message?.contains("unsupported followup plan keys") == true, "got: ${cause.message}")
            }
        assertTrue(stub.queries.isEmpty(), "白名单校验失败不得触发任何 SQL")
    }

    @Test
    fun `创建计划校验必填与枚举`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = FollowupService(stub.pool)

        fun expectInvalid(body: JsonObject, fragment: String) {
            val cause = causeOf(service.createPlan(body, "user-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid(validPlanBody(mapOf("patient_id" to null)), "patient_id is required")
        expectInvalid(validPlanBody(mapOf("encounter_id" to null)), "encounter_id is required")
        expectInvalid(validPlanBody(mapOf("followup_type" to "康复随访")), "invalid followup_type")
        expectInvalid(validPlanBody(mapOf("followup_type" to null)), "followup_type is required")
        expectInvalid(validPlanBody(mapOf("planned_date" to null)), "planned_date is required")
        expectInvalid(validPlanBody(mapOf("planned_date" to "2026-08-10 10:00")), "ISO-8601")
        expectInvalid(validPlanBody(mapOf("planned_way" to "微信")), "invalid planned_way")
        expectInvalid(validPlanBody(mapOf("remark" to "a".repeat(1001))), "1000")
        assertTrue(stub.queries.isEmpty(), "输入校验失败不得触发任何 SQL")
    }

    @Test
    fun `创建计划患者不存在返回404`() {
        val stub = DatabaseStub(patients = rowSet(), encounters = rows(encounterRow()))
        val cause = causeOf(FollowupService(stub.pool).createPlan(validPlanBody(), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("patient not found") == true)
    }

    @Test
    fun `创建计划患者已去世返回400`() {
        val stub = DatabaseStub(
            patients = rows(patientRow(mapOf("status" to "DECEASED"))),
            encounters = rows(encounterRow()),
        )
        val cause = causeOf(FollowupService(stub.pool).createPlan(validPlanBody(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("deceased") == true)
    }

    @Test
    fun `创建计划入住不存在返回404`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rowSet())
        val cause = causeOf(FollowupService(stub.pool).createPlan(validPlanBody(), "user-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("encounter not found") == true)
    }

    @Test
    fun `创建计划入住归属不匹配返回400`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("patient_id" to "pat-other"))),
        )
        val cause = causeOf(FollowupService(stub.pool).createPlan(validPlanBody(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("does not belong to the specified patient") == true)
    }

    @Test
    fun `创建计划非养老入住返回400`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))),
        )
        val cause = causeOf(FollowupService(stub.pool).createPlan(validPlanBody(), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("ELDERLY_CARE") == true)
    }

    @Test
    fun `创建计划早于入住日开始日期返回400且等于入住日成功`() {
        val stub = DatabaseStub(patients = rows(patientRow()), encounters = rows(encounterRow()))
        val service = FollowupService(stub.pool)

        val cause = causeOf(service.createPlan(validPlanBody(mapOf("planned_date" to "2026-07-31")), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("planned_date must not be earlier than the admission start date") == true)

        val ok = successOf(service.createPlan(validPlanBody(mapOf("planned_date" to "2026-08-01")), "user-1")) as JsonObject
        assertEquals("2026-08-01", ok.getString("planned_date"))
    }

    @Test
    fun `已离院入住允许创建离院随访计划`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow(mapOf("status" to "DISCHARGED", "discharge_date" to OffsetDateTime.parse("2026-08-05T10:00:00+08:00")))),
        )
        val result = successOf(FollowupService(stub.pool).createPlan(validPlanBody(mapOf("planned_date" to "2026-08-07")), "user-1")) as JsonObject
        assertEquals("待随访", result.getString("status"))
    }

    // ========================================================================
    //  2. 列表与统计
    // ========================================================================

    @Test
    fun `计划列表空返回records空数组与total0`() {
        val stub = DatabaseStub(planCounts = rows(mapOf("total" to 0L)), plans = rowSet())
        val result = successOf(FollowupService(stub.pool).listPlans(null, null, null, null, null, null, 50, 0)) as JsonObject
        assertEquals(0, result.getJsonArray("records").size())
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))
    }

    @Test
    fun `计划列表支持筛选且逾期由查询计算`() {
        val yesterday = LocalDate.now().minusDays(1)
        val stub = DatabaseStub(
            planCounts = rows(mapOf("total" to 1L)),
            plans = rows(planRow(mapOf("planned_date" to yesterday))),
        )
        val service = FollowupService(stub.pool)

        val result = successOf(service.listPlans("待随访", "慢病随访", "pat-1", null, null, null, 50, 0)) as JsonObject
        val record = result.getJsonArray("records").getJsonObject(0)
        assertEquals("已逾期", record.getString("status"), "待随访且计划日早于今天 → 已逾期（计算得出，不落库）")

        val overdueSql = stub.queries.first { it.contains("from healthcare.followup_plans") && it.contains("count(*)") }
        // jOOQ NAMED 参数渲染为 $1/$2...，PostgreSQL 占位符为 $N
        assertTrue(overdueSql.contains("status = $"), "status 筛选生效: $overdueSql")
        assertTrue(overdueSql.contains("followup_type = $"), "followup_type 筛选生效: $overdueSql")
        assertTrue(overdueSql.contains("patient_id = $"), "patient_id 筛选生效: $overdueSql")

        successOf(service.listPlans(null, null, null, null, null, "true", 50, 0))
        // jOOQ 对 LocalDate 参数渲染 cast($N as date)
        assertTrue(
            stub.queries.any { it.contains("from healthcare.followup_plans") && it.contains("planned_date < cast($") },
            "overdue=true 必须带计划日早于今天的条件",
        )
    }

    @Test
    fun `计划列表拒绝已逾期状态参数`() {
        val stub = DatabaseStub(planCounts = rows(mapOf("total" to 0L)))
        val cause = causeOf(FollowupService(stub.pool).listPlans("已逾期", null, null, null, null, null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("overdue=true") == true)
    }

    @Test
    fun `统计返回今日待随访已逾期与本月已完成`() {
        val stub = DatabaseStub(
            planCounts = rows(mapOf("total" to 3L)),
            recordCounts = rows(mapOf("total" to 0L)),
        )
        val result = successOf(FollowupService(stub.pool).getPlanStats()) as JsonObject
        assertEquals(3L, result.getLong("today_pending"))
        assertEquals(3L, result.getLong("overdue"))
        assertEquals(3L, result.getLong("month_completed"))
        assertEquals(3, stub.queries.count { it.contains("count(*)") && it.contains("from healthcare.followup_plans") })
    }

    // ========================================================================
    //  3. 随访记录
    // ========================================================================

    @Test
    fun `带计划新增记录单事务内落库并完成计划`() {
        val stub = DatabaseStub(
            plans = rows(planRow()),
            records = rows(recordRow()),
        )
        val service = FollowupService(stub.pool)

        val result = successOf(service.createRecord(validRecordBody(), "user-1")) as JsonObject
        assertEquals("rec-1", result.getString("id"))
        assertEquals("plan-1", result.getString("plan_id"))
        assertEquals(1, stub.transactionCalls, "记录+完成计划必须单事务")
        val sqls = stub.queries
        assertTrue(sqls.any { it.contains("insert into healthcare.followup_records") }, "记录必须落库")
        val completeSql = sqls.first { it.contains("update healthcare.followup_plans") }
        // SET status = $N（置已完成）与 WHERE status = $N（待随访守卫）各出现一次
        val statusBindCount = Regex("status = \\$").findAll(completeSql).count()
        assertTrue(statusBindCount >= 2, "SET 与 WHERE 条件都必须带 status 绑定: $completeSql")
    }

    @Test
    fun `重复提交同一计划并发只有一次成功`() {
        val stub = DatabaseStub(
            plans = rows(planRow()),
            records = rows(recordRow()),
            planCompleteAffected = 1,
        )
        val service = FollowupService(stub.pool)

        val first = successOf(service.createRecord(validRecordBody(), "user-1")) as JsonObject
        assertEquals("rec-1", first.getString("id"))

        stub.planCompleteAffected = 0 // 第二次竞争失败：条件更新影响 0 行
        val cause = causeOf(service.createRecord(validRecordBody(), "user-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("not 待随访") == true)
    }

    @Test
    fun `记录与计划归属不一致返回400`() {
        val stub = DatabaseStub(plans = rows(planRow()), records = rows(recordRow()))
        val service = FollowupService(stub.pool)

        val cause = causeOf(service.createRecord(validRecordBody(mapOf("patient_id" to "pat-other")), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("must match the followup plan") == true)
    }

    @Test
    fun `随访记录时间不得晚于当前时间`() {
        val stub = DatabaseStub(plans = rows(planRow()), records = rows(recordRow()))
        val future = OffsetDateTime.now().plusDays(1).toString()
        val cause = causeOf(FollowupService(stub.pool).createRecord(validRecordBody(mapOf("followup_date" to future)), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("future") == true)
    }

    @Test
    fun `随访记录拒绝审计字段与非法枚举`() {
        val stub = DatabaseStub(plans = rows(planRow()), records = rows(recordRow()))
        val service = FollowupService(stub.pool)

        listOf("operator", "created_at", "updated_at", "id")
            .forEach { key ->
                val cause = causeOf(service.createRecord(validRecordBody(mapOf(key to "hacked")), "user-1"))
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                assertTrue(cause.message?.contains("unsupported followup record keys") == true, "got: ${cause.message}")
            }
        val cause = causeOf(service.createRecord(validRecordBody(mapOf("result" to "待定")), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("invalid result") == true)
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    @Test
    fun `无计划临时随访直接新增记录`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            records = rows(recordRow(mapOf("plan_id" to null))),
        )
        val service = FollowupService(stub.pool)

        val body = validRecordBody(mapOf("plan_id" to null))
        val result = successOf(service.createRecord(body, "user-1")) as JsonObject
        assertNull(result.getString("plan_id"))
        assertTrue(stub.queries.any { it.contains("insert into healthcare.followup_records") })
        assertTrue(stub.queries.none { it.contains("update healthcare.followup_plans") }, "临时随访不得触碰计划")
    }

    @Test
    fun `记录列表空返回records空数组与total0`() {
        val stub = DatabaseStub(recordCounts = rows(mapOf("total" to 0L)), records = rowSet())
        val result = successOf(FollowupService(stub.pool).listRecords(null, null, null, null, null, null, 50, 0)) as JsonObject
        assertEquals(0, result.getJsonArray("records").size())
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))
    }

    @Test
    fun `记录详情不存在返回404`() {
        val stub = DatabaseStub(records = rowSet())
        val cause = causeOf(FollowupService(stub.pool).getRecord("rec-missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
    }

    // ========================================================================
    //  4. 状态流转
    // ========================================================================

    @Test
    fun `取消计划必须带原因且成功置为已取消`() {
        val stub = DatabaseStub(plans = rows(planRow()))
        val service = FollowupService(stub.pool)

        val missing = causeOf(service.updatePlanStatus("plan-1", JsonObject().put("status", "已取消"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, missing)
        assertTrue(missing.message?.contains("cancel_reason is required") == true)

        stub.plans = rows(planRow(mapOf("status" to "已取消", "cancel_reason" to "老人已回访无需再访")))
        val ok = successOf(
            service.updatePlanStatus(
                "plan-1",
                JsonObject().put("status", "已取消").put("cancel_reason", "老人已回访无需再访"),
                "user-1",
            ),
        ) as JsonObject
        assertEquals("已取消", ok.getString("status"))
        assertEquals("老人已回访无需再访", ok.getString("cancel_reason"))
        assertTrue(stub.queries.any { it.contains("update healthcare.followup_plans") })
    }

    @Test
    fun `已完成或已取消计划不可再次流转`() {
        val stub = DatabaseStub(plans = rows(planRow(mapOf("status" to "已完成"))), planCancelAffected = 0)
        val cause = causeOf(FollowupService(stub.pool).updatePlanStatus("plan-1", JsonObject().put("status", "已取消").put("cancel_reason", "不需要"), "user-1"))
        assertInstanceOf(ConflictException::class.java, cause)
    }

    @Test
    fun `完成计划必须提交记录或记录id`() {
        val stub = DatabaseStub(plans = rows(planRow()))
        val service = FollowupService(stub.pool)

        val cause = causeOf(service.updatePlanStatus("plan-1", JsonObject().put("status", "已完成"), "user-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("requires a record or record_id") == true)

        val both = causeOf(
            service.updatePlanStatus(
                "plan-1",
                JsonObject().put("status", "已完成").put("record_id", "rec-1").put("record", JsonObject().put("result", "正常")),
                "user-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, both)
        assertTrue(both.message?.contains("mutually exclusive") == true)
    }

    @Test
    fun `引用记录完成计划时链接并回填完成时间`() {
        val stub = DatabaseStub(
            plans = rows(planRow(mapOf("status" to "已完成", "completed_at" to OffsetDateTime.parse("2026-08-10T10:00:00+08:00")))),
            records = rows(recordRow(mapOf("plan_id" to null))),
        )
        val service = FollowupService(stub.pool)

        val ok = successOf(
            service.updatePlanStatus("plan-1", JsonObject().put("status", "已完成").put("record_id", "rec-1"), "user-1"),
        ) as JsonObject
        assertEquals("已完成", ok.getString("status"))
        assertEquals("2026-08-10T10:00+08:00", ok.getString("completed_at"), "回填实际随访时间")
        val linkSql = stub.queries.first { it.contains("update healthcare.followup_records") }
        assertTrue(linkSql.contains("plan_id"), "记录必须链接到计划: $linkSql")
    }

    @Test
    fun `引用其他计划的记录完成计划返回409`() {
        val stub = DatabaseStub(
            plans = rows(planRow()),
            records = rows(recordRow(mapOf("plan_id" to "plan-other"))),
        )
        val cause = causeOf(
            FollowupService(stub.pool).updatePlanStatus("plan-1", JsonObject().put("status", "已完成").put("record_id", "rec-1"), "user-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("another followup plan") == true)
    }

    @Test
    fun `内联记录完成计划同事务创建记录并完成`() {
        val stub = DatabaseStub(
            plans = rows(planRow(mapOf("status" to "已完成"))),
        )
        val service = FollowupService(stub.pool)

        val ok = successOf(
            service.updatePlanStatus(
                "plan-1",
                JsonObject()
                    .put("status", "已完成")
                    .put(
                        "record",
                        JsonObject()
                            .put("followup_way", "上门")
                            .put("condition_summary", "状态良好")
                            .put("result", "正常"),
                    ),
                "user-1",
            ),
        ) as JsonObject
        assertEquals("已完成", ok.getString("status"))
        assertEquals(1, stub.transactionCalls, "内联记录+完成计划必须单事务")
        assertTrue(stub.queries.any { it.contains("insert into healthcare.followup_records") })
        assertTrue(stub.queries.any { it.contains("update healthcare.followup_plans") })
    }

    // ========================================================================
    //  5. 老人随访历史与路由
    // ========================================================================

    @Test
    fun `老人随访历史返回计划与记录时间线`() {
        val stub = DatabaseStub(
            selectOnePatients = rows(mapOf("id" to "pat-1")),
            plans = rows(planRow()),
            records = rows(recordRow()),
        )
        val result = successOf(FollowupService(stub.pool).listPatientFollowups("pat-1")) as JsonObject
        assertEquals(1, result.getJsonArray("plans").size())
        assertEquals(1, result.getJsonArray("records").size())
        assertEquals("张奶奶", result.getJsonArray("plans").getJsonObject(0).getString("patient_name"))
    }

    @Test
    fun `老人随访历史患者不存在返回404`() {
        val stub = DatabaseStub(selectOnePatients = rowSet())
        val cause = causeOf(FollowupService(stub.pool).listPatientFollowups("pat-missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
    }

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
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/followup-plans", validPlanBody())
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
    fun `随访路由全链路可用`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            planCounts = rows(mapOf("total" to 0L)),
            plans = rowSet(),
            recordCounts = rows(mapOf("total" to 0L)),
            records = rowSet(),
            selectOnePatients = rows(mapOf("id" to "pat-1")),
        )
        withServer(vertx, stub, userId = "user-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/followup-plans", validPlanBody())
                .compose { (createStatus, created) ->
                    ctx.verify {
                        assertEquals(201, createStatus, "创建计划必须 201")
                        assertTrue(ulidPattern.matches(created.getString("id")))
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/followup-plans?status=" + java.net.URLEncoder.encode("待随访", java.nio.charset.StandardCharsets.UTF_8) + "&limit=10&offset=0")
                        .compose { (listStatus, list) ->
                            ctx.verify {
                                assertEquals(200, listStatus)
                                assertEquals(0, list.getJsonArray("records").size())
                                assertEquals(0L, list.getJsonObject("meta").getLong("total"))
                            }
                            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/followup-plans/stats")
                                .compose { (statsStatus, stats) ->
                                    ctx.verify {
                                        assertEquals(200, statsStatus)
                                        assertNotNull(stats.getLong("today_pending"))
                                    }
                                    // 记录随访：先让计划/记录查询命中 fixture 行
                                    stub.plans = rows(planRow())
                                    stub.records = rows(recordRow())
                                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/followup-records", validRecordBody())
                                        .compose { (recordStatus, record) ->
                                            ctx.verify {
                                                assertEquals(201, recordStatus)
                                                assertEquals("plan-1", record.getString("plan_id"))
                                            }
                                            // 取消计划：计划详情返回已取消状态
                                            stub.plans = rows(planRow(mapOf("status" to "已取消", "cancel_reason" to "老人已回访")))
                                            httpRequest(vertx, port, HttpMethod.PATCH, "/healthcare/v1/followup-plans/plan-1/status", JsonObject().put("status", "已取消").put("cancel_reason", "老人已回访"))
                                                .compose { (patchStatus, patched) ->
                                                    ctx.verify {
                                                        assertEquals(200, patchStatus)
                                                        assertEquals("已取消", patched.getString("status"))
                                                    }
                                                    // 老人随访历史：当前无任何计划/记录
                                                    stub.plans = rowSet()
                                                    stub.records = rowSet()
                                                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/patients/pat-1/followups")
                                                        .map { (historyStatus, history) ->
                                                            ctx.verify {
                                                                assertEquals(200, historyStatus)
                                                                assertEquals(0, history.getJsonArray("plans").size())
                                                                assertEquals(0, history.getJsonArray("records").size())
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

    // ========================================================================
    //  6. 无编辑删除路由
    // ========================================================================

    @Test
    fun `随访记录无编辑与删除路由`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(records = rows(recordRow()))
        withServer(vertx, stub, userId = "user-1") { port ->
            httpRequest(vertx, port, HttpMethod.PUT, "/healthcare/v1/followup-records/rec-1", JsonObject().put("result", "异常"))
                .compose { (putStatus, _) ->
                    httpRequest(vertx, port, HttpMethod.DELETE, "/healthcare/v1/followup-records/rec-1")
                        .map { (deleteStatus, _) ->
                            ctx.verify {
                                // Vert.x 对已存在路径的未注册方法返回 405（无子路径则 404），两者都证明无编辑/删除路由
                                assertTrue(putStatus == 404 || putStatus == 405, "记录不可编辑：实际 $putStatus")
                                assertTrue(deleteStatus == 404 || deleteStatus == 405, "记录不可删除：实际 $deleteStatus")
                                ctx.completeNow()
                            }
                        }
                }
        }
    }
}

// ——— mock 基础设施（顶层函数：供测试方法与嵌套 DatabaseStub 共用） ———

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
