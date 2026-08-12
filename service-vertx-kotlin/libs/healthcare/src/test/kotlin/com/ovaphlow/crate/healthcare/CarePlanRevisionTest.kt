package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.NotFoundException
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 复评与照护计划修订的非数据库测试（mockk + 嵌入式 HTTP）：
 *   - 创建请求白名单与输入校验（400，不触发 SQL）
 *   - encounter/period/活动计划/IN_PROGRESS 执行的资格映射（404/400/409）
 *   - 成功路径 201：响应结构、事务内写顺序、任务绑定参数
 *   - 写入失败时整笔事务失败（同一连接内写，无孤儿 SQL）
 *   - 具体修订路由不被泛型 encounter 路由吞掉
 */
@ExtendWith(VertxExtension::class)
class CarePlanRevisionTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发，
     * 捕获全部 SQL 与 tuple 以便断言顺序、查询边界与参数。
     */
    private class DatabaseStub(
        var encounters: RowSet<Row> = rowSet(),
        var periods: RowSet<Row> = rowSet(),
        var assessments: RowSet<Row> = rowSet(),
        var plans: RowSet<Row> = rowSet(),
        /** plan_id = pln-2 时返回的新计划行（用于详情组装区分新旧计划） */
        var newPlanRows: RowSet<Row> = rowSet(),
        var planItems: RowSet<Row> = rowSet(),
        var tasks: RowSet<Row> = rowSet(),
        var inProgressChecks: RowSet<Row> = rowSet(),
        var maxNoRows: RowSet<Row> = rowSet(),
        var countRows: RowSet<Row> = rowSet(),
        var revisionRows: RowSet<Row> = rowSet(),
        /** normalized SQL 片段：命中的 insert 将失败（模拟事务中途写入失败） */
        var failInsertFragment: String? = null,
    ) {
        val connQueries = mutableListOf<String>()
        val poolQueries = mutableListOf<String>()
        val connTuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        val conn = mockk<SqlConnection>()
        val pool = mockk<Pool>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>(), connQueries); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>(), connQueries); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>(), poolQueries); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>(), poolQueries); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                connTuples.add(sql to tupleValues(firstArg()))
                val branch = when {
                    sql.startsWith("insert into nursing.nursing_assessments") -> "insert_assessments"
                    sql.startsWith("insert into nursing.nursing_plans") -> "insert_plans"
                    sql.startsWith("insert into nursing.nursing_plan_items") -> "insert_plan_items"
                    sql.startsWith("insert into nursing.nursing_tasks") -> "insert_tasks"
                    sql.startsWith("insert into nursing.nursing_care_plan_revisions") -> "insert_revisions"
                    sql.startsWith("update") -> "updates"
                    sql.contains("from nursing.nursing_task_executions") -> "in_progress_checks"
                    sql.contains("coalesce(max(") -> "max_no"
                    sql.contains("count(*)") && sql.contains("nursing_care_plan_revisions") -> "count_revisions"
                    sql.contains("from nursing.nursing_care_plan_revisions") -> "revisions"
                    sql.contains("from healthcare.encounters") -> "encounters"
                    sql.contains("from nursing.nursing_service_periods") -> "periods"
                    sql.contains("from nursing.nursing_assessments") -> "assessments"
                    sql.contains("from nursing.nursing_plan_items") -> "plan_items"
                    sql.contains("from nursing.nursing_tasks") -> "tasks"
                    sql.contains("from nursing.nursing_plans") -> "plans"
                    else -> "else"
                }
                if (failInsertFragment != null && sql.startsWith("insert into") && sql.contains(failInsertFragment!!)) {
                    return@answers Future.failedFuture(IllegalStateException("simulated insert failure: $branch"))
                }
                val result = when (branch) {
                    "insert_assessments", "insert_plans", "insert_plan_items", "insert_tasks",
                    "insert_revisions", "updates", "else" -> rowSet()
                    "in_progress_checks" -> inProgressChecks
                    "max_no" -> maxNoRows
                    "count_revisions" -> countRows
                    "revisions" -> revisionRows
                    "encounters" -> encounters
                    "periods" -> periods
                    "assessments" -> assessments
                    "plan_items" -> planItems
                    "tasks" -> tasks
                    "plans" -> {
                        val tuple = tupleValues(firstArg())
                        if (newPlanRows != null && tuple.isNotEmpty() && tuple[0] == "pln-2") newPlanRows else plans
                    }
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

        private fun record(sql: String, sink: MutableList<String>) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            sink.add(normalizedSql)
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

    private fun planRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "pln-1",
            "period_id" to "per-1",
            "encounter_id" to "enc-1",
            "plan_name" to "第一阶段照护计划",
            "goals" to "维持日常生活能力",
            "status" to "ACTIVE",
            "created_by" to "sub-1",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to LocalDate.of(2026, 8, 14),
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun planItemRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "pmi-1",
            "plan_id" to "pln-1",
            "action" to "每日协助晨间洗漱",
            "frequency_code" to "QD",
            "frequency_name" to "每日一次",
            "duration_days" to 14,
            "remark" to null,
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun taskRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "tsk-1",
            "period_id" to "per-1",
            "encounter_id" to "enc-1",
            "plan_item_id" to "pmi-1",
            "order_item_id" to null,
            "task_type" to "NURSING",
            "description" to "每日协助晨间洗漱",
            "frequency_code" to "QD",
            "frequency_name" to "每日一次",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to LocalDate.of(2026, 8, 14),
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun maxNoRow(value: Int): MutableMap<String, Any?> =
        mutableMapOf("max_no" to value)

    private fun countRow(total: Long): MutableMap<String, Any?> =
        mutableMapOf("total" to total)

    private fun assessmentRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "asm-1",
            "encounter_id" to "enc-1",
            "period_id" to "per-1",
            "assess_type" to "BARTHEL",
            "assess_date" to LocalDate.of(2026, 8, 5),
            "assessor" to "护理员",
            "total_score" to BigDecimal.valueOf(65.0),
            "result_level" to "中度依赖",
            "detail" to JsonObject().put("note", "近期步行能力下降"),
            "remark" to "复评说明",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-05T10:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    /** 列表 join 后的行：修订字段 + 评估摘要 + 新旧计划摘要（prev 为左连别名） */
    private fun revisionRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "rev-1",
            "period_id" to "per-1",
            "encounter_id" to "enc-1",
            "revision_no" to 1,
            "assessment_id" to "asm-1",
            "assess_type" to "BARTHEL",
            "assess_date" to LocalDate.of(2026, 8, 5),
            "assessor" to "护理员",
            "result_level" to "中度依赖",
            "previous_plan_id" to "pln-1",
            "prev_plan_name" to "第一阶段照护计划",
            "prev_plan_status" to "DISCONTINUED",
            "new_plan_id" to "pln-2",
            "new_plan_name" to "第二阶段照护计划",
            "new_plan_status" to "ACTIVE",
            "created_at" to OffsetDateTime.parse("2026-08-05T10:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun validRevisionBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("assessment", JsonObject()
                .put("assess_type", "BARTHEL")
                .put("assess_date", "2026-08-05")
                .put("assessor", "护理员")
                .put("total_score", 65)
                .put("result_level", "中度依赖")
                .put("detail", JsonObject().put("note", "近期步行能力下降"))
                .put("remark", "复评说明"))
            .put("plan", JsonObject()
                .put("plan_name", "第二阶段照护计划")
                .put("goals", "提高日常活动能力")
                .put("created_by", "护理员")
                .put("start_date", "2026-08-05")
                .put("end_date", "2026-08-19")
                .put("items", JsonArray().add(JsonObject()
                    .put("action", "每日协助步行训练")
                    .put("frequency_code", "QD")
                    .put("frequency_name", "每日一次")
                    .put("duration_days", 14)
                    .put("remark", "根据耐受程度调整"))))
        overrides.forEach { (key, value) ->
            if (value == null) body.remove(key) else body.put(key, value)
        }
        return body
    }

    /** 成功路径默认桩：encounter/period/唯一活动计划/活动措施/活动任务/无执行中/首版修订 */
    private fun happyStub(): DatabaseStub {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            plans = rows(planRow()),
            planItems = rows(planItemRow()),
            tasks = rows(taskRow()),
            inProgressChecks = rowSet(),
            maxNoRows = rows(maxNoRow(0)),
        )
        return stub
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

    // ——— 1. 输入校验（400，不触发 SQL） ———

    @Test
    fun `复评请求白名单与输入校验全部返回400且不触发SQL`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.createCarePlanRevision("enc-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        // 顶层未知键
        expectInvalid(
            validRevisionBody().put("id", "injected"),
            "unsupported keys in request",
        )
        expectInvalid(
            validRevisionBody().put("metadata", JsonObject()),
            "unsupported keys in request",
        )
        // assessment 未知键
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "assessment" to validRevisionBody().getJsonObject("assessment").copy()
                        .put("period_id", "injected"),
                ),
            ),
            "unsupported keys in assessment",
        )
        // plan 未知键（服务端推导的 ID 不能被覆盖）
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "plan" to validRevisionBody().getJsonObject("plan").copy()
                        .put("id", "injected"),
                ),
            ),
            "unsupported keys in plan",
        )
        // 措施未知键
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "plan" to validRevisionBody().getJsonObject("plan").copy()
                        .put("items", JsonArray().add(JsonObject()
                            .put("action", "每日协助步行训练")
                            .put("task_id", "injected"))),
                ),
            ),
            "unsupported keys in plan items",
        )
        // 服务端推导键全部拒绝：status/encounter_id/plan_id/revision_no 落在 plan 层白名单外
        for (injectedKey in listOf("status", "encounter_id", "plan_id", "revision_no")) {
            expectInvalid(
                validRevisionBody(
                    mapOf(
                        "plan" to validRevisionBody().getJsonObject("plan").copy()
                            .put(injectedKey, "injected"),
                    ),
                ),
                "unsupported keys in plan",
            )
        }
        // 顶层任意未知键同样拒绝
        expectInvalid(
            validRevisionBody().put("random_unknown", 1),
            "unsupported keys in request",
        )
        // 缺 assessment / plan
        expectInvalid(JsonObject().put("plan", JsonObject()), "assessment is required")
        // assessment 存在但为空：先校验评估字段
        expectInvalid(JsonObject().put("assessment", JsonObject()), "assess_type is required")
        // 评估类型白名单：ADMISSION 不是复评类型
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "assessment" to validRevisionBody().getJsonObject("assessment").copy()
                        .put("assess_type", "ADMISSION"),
                ),
            ),
            "invalid assess_type",
        )
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "assessment" to validRevisionBody().getJsonObject("assessment").copy()
                        .put("assess_type", "RANDOM"),
                ),
            ),
            "invalid assess_type",
        )
        // 必填字段（注意：remove 返回被删值，需先 copy 再单独 remove）
        val assessmentNoDate = validRevisionBody().getJsonObject("assessment").copy()
        assessmentNoDate.remove("assess_date")
        expectInvalid(
            validRevisionBody(mapOf("assessment" to assessmentNoDate)),
            "assess_date is required",
        )
        val planNoName = validRevisionBody().getJsonObject("plan").copy()
        planNoName.remove("plan_name")
        expectInvalid(
            validRevisionBody(mapOf("plan" to planNoName)),
            "plan_name is required",
        )
        val planNoStart = validRevisionBody().getJsonObject("plan").copy()
        planNoStart.remove("start_date")
        expectInvalid(
            validRevisionBody(mapOf("plan" to planNoStart)),
            "start_date is required",
        )
        // 类型错误
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "assessment" to validRevisionBody().getJsonObject("assessment").copy()
                        .put("total_score", "high"),
                ),
            ),
            "total_score must be a number",
        )
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "plan" to validRevisionBody().getJsonObject("plan").copy()
                        .put("items", JsonArray().add(JsonObject()
                            .put("action", "每日协助步行训练")
                            .put("duration_days", -1))),
                ),
            ),
            "duration_days must be a non-negative integer",
        )
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "plan" to validRevisionBody().getJsonObject("plan").copy()
                        .put("items", JsonArray().add(JsonObject()
                            .put("action", "每日协助步行训练")
                            .put("duration_days", 2.5))),
                ),
            ),
            "duration_days must be a non-negative integer",
        )
        expectInvalid(
            validRevisionBody(
                mapOf(
                    "plan" to validRevisionBody().getJsonObject("plan").copy()
                        .put("items", JsonArray().add(JsonObject()
                            .put("action", "每日协助步行训练")
                            .put("frequency_code", "QD")
                            .put("metadata", JsonObject()))),
                ),
            ),
            "unsupported keys in plan items",
        )

        assertTrue(stub.poolQueries.isEmpty(), "校验失败不得触发任何 SQL")
        assertTrue(stub.connQueries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    // ——— 2. 资格校验（404/400/409） ———

    @Test
    fun `encounter 不存在返回404`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)
        val cause = causeOf(service.createCarePlanRevision("missing", validRevisionBody()))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertEquals("encounter not found: missing", cause.message)
    }

    @Test
    fun `非养老或非活动 encounter 返回400和409`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))),
        )
        val service = HealthcareService(stub.pool)
        val cause400 = causeOf(service.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(IllegalArgumentException::class.java, cause400)
        assertTrue(cause400.message?.contains("not an elderly admission") == true, "got: ${cause400.message}")

        val stub2 = DatabaseStub(
            encounters = rows(encounterRow(mapOf("status" to "DISCHARGED"))),
        )
        val service2 = HealthcareService(stub2.pool)
        val cause409 = causeOf(service2.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, cause409)
        assertEquals("encounter is not active", cause409.message)
    }

    @Test
    fun `缺精确 period、非活动 period 或患者不匹配返回409`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val service = HealthcareService(stub.pool)
        val causeNoPeriod = causeOf(service.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, causeNoPeriod)
        assertTrue(causeNoPeriod.message?.contains("no bound nursing care period") == true, "got: ${causeNoPeriod.message}")

        val stub2 = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("status" to "COMPLETED"))),
        )
        val service2 = HealthcareService(stub2.pool)
        val causeClosed = causeOf(service2.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, causeClosed)
        assertTrue(causeClosed.message?.contains("period is not active") == true, "got: ${causeClosed.message}")

        val stub3 = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("patient_id" to "pat-9"))),
        )
        val service3 = HealthcareService(stub3.pool)
        val causeMismatch = causeOf(service3.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, causeMismatch)
        assertTrue(causeMismatch.message?.contains("patient_id mismatch") == true, "got: ${causeMismatch.message}")
    }

    @Test
    fun `没有基线计划或多个活动计划返回409`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
        )
        val service = HealthcareService(stub.pool)
        val causeNoPlan = causeOf(service.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, causeNoPlan)
        assertTrue(causeNoPlan.message?.contains("no active plan") == true, "got: ${causeNoPlan.message}")

        val stub2 = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            plans = rows(planRow(), planRow(mapOf("id" to "pln-2"))),
        )
        val service2 = HealthcareService(stub2.pool)
        val causeMultiple = causeOf(service2.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, causeMultiple)
        assertTrue(causeMultiple.message?.contains("multiple active plans") == true, "got: ${causeMultiple.message}")
    }

    @Test
    fun `当前计划存在 IN_PROGRESS 执行时拒绝且不发出任何写SQL`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            plans = rows(planRow()),
            planItems = rows(planItemRow()),
            tasks = rows(taskRow()),
            inProgressChecks = rows(mutableMapOf("one" to 1)),
            maxNoRows = rows(maxNoRow(0)),
        )
        val service = HealthcareService(stub.pool)
        val cause = causeOf(service.createCarePlanRevision("enc-1", validRevisionBody()))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("execution is in progress") == true, "got: ${cause.message}")

        assertTrue(stub.connQueries.none { it.startsWith("insert") }, "拒绝时不得发出任何 insert")
        assertTrue(stub.connQueries.none { it.startsWith("update") }, "拒绝时不得发出任何 update")
    }

    @Test
    fun `assess_date 早于周期开始或晚于当前业务日期返回400`() {
        // 周期开始 2026-08-01，assess_date 早于它
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            plans = rows(planRow()),
            planItems = rows(planItemRow()),
            tasks = rows(taskRow()),
            maxNoRows = rows(maxNoRow(0)),
        )
        val service = HealthcareService(stub.pool)
        val bodyEarly = validRevisionBody(
            mapOf(
                "assessment" to validRevisionBody().getJsonObject("assessment").copy()
                    .put("assess_date", "2026-07-31"),
            ),
        )
        val causeEarly = causeOf(service.createCarePlanRevision("enc-1", bodyEarly))
        assertInstanceOf(IllegalArgumentException::class.java, causeEarly)
        assertTrue(causeEarly.message?.contains("assess_date cannot be earlier") == true, "got: ${causeEarly.message}")

        val bodyFuture = validRevisionBody(
            mapOf(
                "assessment" to validRevisionBody().getJsonObject("assessment").copy()
                    .put("assess_date", "2099-01-01"),
            ),
        )
        val causeFuture = causeOf(service.createCarePlanRevision("enc-1", bodyFuture))
        assertInstanceOf(IllegalArgumentException::class.java, causeFuture)
        assertTrue(causeFuture.message?.contains("assess_date cannot be in the future") == true, "got: ${causeFuture.message}")

        val bodyOrder = validRevisionBody(
            mapOf(
                "plan" to validRevisionBody().getJsonObject("plan").copy()
                    .put("start_date", "2026-08-04"),
            ),
        )
        val causeOrder = causeOf(service.createCarePlanRevision("enc-1", bodyOrder))
        assertInstanceOf(IllegalArgumentException::class.java, causeOrder)
        assertTrue(causeOrder.message?.contains("start_date cannot be earlier than assess_date") == true, "got: ${causeOrder.message}")

        val bodyEnd = validRevisionBody(
            mapOf(
                "plan" to validRevisionBody().getJsonObject("plan").copy()
                    .put("end_date", "2026-08-03"),
            ),
        )
        val causeEnd = causeOf(service.createCarePlanRevision("enc-1", bodyEnd))
        assertInstanceOf(IllegalArgumentException::class.java, causeEnd)
        assertTrue(causeEnd.message?.contains("end_date cannot be earlier than start_date") == true, "got: ${causeEnd.message}")
    }

    // ——— 3. 成功路径 ———

    @Test
    fun `成功创建复评与计划修订返回201且事务内写顺序正确`() {
        val stub = happyStub()
        val service = HealthcareService(stub.pool)
        val result = service.createCarePlanRevision("enc-1", validRevisionBody())
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(1, stub.transactionCalls)
        // 响应结构
        assertNotNull(result.getString("revision_id"))
        assertEquals(1, result.getInteger("revision_no"))
        assertEquals("BARTHEL", result.getJsonObject("assessment").getString("assess_type"))
        assertEquals("2026-08-05", result.getJsonObject("assessment").getString("assess_date"))
        assertEquals("中度依赖", result.getJsonObject("assessment").getString("result_level"))
        assertEquals("pln-1", result.getJsonObject("previous_plan").getString("id"))
        assertEquals("DISCONTINUED", result.getJsonObject("previous_plan").getString("status"))
        assertEquals("ACTIVE", result.getJsonObject("plan").getString("status"))
        assertEquals("第二阶段照护计划", result.getJsonObject("plan").getString("plan_name"))
        assertEquals(1, result.getJsonObject("plan").getJsonArray("items").size())
        assertEquals(1, result.getJsonArray("items").size())
        assertEquals(1, result.getJsonArray("tasks").size())

        val task = result.getJsonArray("tasks").getJsonObject(0)
        assertNotNull(task.getString("id"))
        assertEquals("ACTIVE", task.getString("status"))
        // 新任务精确绑定新措施：task.plan_item_id == 新计划措施 id
        val newItemId = result.getJsonObject("plan").getJsonArray("items").getJsonObject(0).getString("id")
        assertEquals(newItemId, task.getString("plan_item_id"))
        // 任务结束日期 = start_date + duration_days
        assertEquals("2026-08-19", task.getString("end_date"))
        assertEquals("2026-08-05", task.getString("start_date"))

        // 事务内写顺序：复评 → 收束 → 新计划 → 新任务 → 修订关系
        val writes = stub.connQueries.filter { it.startsWith("insert into") || it.startsWith("update") }
        val expectedOrder = listOf(
            "insert into nursing.nursing_assessments",
            "update nursing.nursing_plans",
            "update nursing.nursing_plan_items",
            "update nursing.nursing_tasks",
            "insert into nursing.nursing_plans",
            "insert into nursing.nursing_plan_items",
            "insert into nursing.nursing_tasks",
            "insert into nursing.nursing_care_plan_revisions",
        )
        assertEquals(expectedOrder.size, writes.size, "写操作数量不符: $writes")
        for (i in expectedOrder.indices) {
            assertTrue(writes[i].startsWith(expectedOrder[i]), "第 $i 个写操作应为 ${expectedOrder[i]}, got: ${writes[i]}")
        }

        // 旧计划/措施/任务收束（参数化 SQL：值在 tuple 中）
        val planUpdateTuple = stub.connTuples.first { it.first.startsWith("update nursing.nursing_plans") }.second
        assertEquals("DISCONTINUED", planUpdateTuple[0])
        assertEquals("pln-1", planUpdateTuple[2])
        val itemUpdateTuple = stub.connTuples.first { it.first.startsWith("update nursing.nursing_plan_items") }.second
        assertEquals("DISCONTINUED", itemUpdateTuple[0])
        assertEquals("pln-1", itemUpdateTuple[1])
        assertEquals("ACTIVE", itemUpdateTuple[2])
        val taskUpdateTuple = stub.connTuples.first { it.first.startsWith("update nursing.nursing_tasks") }.second
        assertEquals("CANCELLED", taskUpdateTuple[0])
        assertTrue(taskUpdateTuple.contains("tsk-1"), "旧活动任务必须被取消: $taskUpdateTuple")

        // 新任务绑定参数：period/encounter/NURSING/新措施
        val taskInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_tasks") }
        val taskTuple = taskInsert.second
        assertEquals("per-1", taskTuple[1])
        assertEquals("enc-1", taskTuple[2])
        assertEquals(newItemId, taskTuple[3])
        assertEquals("NURSING", taskTuple[4])
        assertEquals("每日协助步行训练", taskTuple[5])
        assertEquals("ACTIVE", taskTuple[6])

        // 修订关系绑定：period/encounter/assessment/旧计划/新计划/revision_no
        val revisionInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_care_plan_revisions") }
        val revisionTuple = revisionInsert.second
        assertEquals(result.getString("revision_id"), revisionTuple[0])
        assertEquals("per-1", revisionTuple[1])
        assertEquals("enc-1", revisionTuple[2])
        assertEquals(result.getJsonObject("assessment").getString("id"), revisionTuple[3])
        assertEquals("pln-1", revisionTuple[4])
        assertEquals(result.getJsonObject("plan").getString("id"), revisionTuple[5])
        assertEquals(1, revisionTuple[6])
    }

    @Test
    fun `无频次措施仍创建活动任务`() {
        val stub = happyStub()
        val service = HealthcareService(stub.pool)
        val body = validRevisionBody(
            mapOf(
                "plan" to validRevisionBody().getJsonObject("plan").copy()
                    .put("items", JsonArray().add(JsonObject()
                        .put("action", "观察皮肤状况"))),
            ),
        )
        val result = service.createCarePlanRevision("enc-1", body)
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(1, result.getJsonArray("tasks").size())
        val task = result.getJsonArray("tasks").getJsonObject(0)
        assertEquals("ACTIVE", task.getString("status"))
        assertEquals(null, task.getString("end_date"))
        assertEquals(null, task.getString("frequency_code"))
    }

    // ——— 4. 回滚边界 ———

    @Test
    fun `修订关系写入失败时整笔事务失败且不产生孤儿写`() {
        val stub = happyStub()
        stub.failInsertFragment = "nursing_care_plan_revisions"
        val service = HealthcareService(stub.pool)
        val cause = causeOf(service.createCarePlanRevision("enc-1", validRevisionBody()))
        assertFalse(cause is IllegalArgumentException, "模拟写入失败应向上传播，got: ${cause.message}")
        // 写全部发生在事务连接上（没有 pool 直连写）
        assertTrue(stub.poolQueries.none { it.startsWith("insert") }, "写操作必须全部走事务连接")
        assertTrue(stub.poolQueries.none { it.startsWith("update") }, "写操作必须全部走事务连接")
        assertTrue(stub.connQueries.any { it.startsWith("insert into nursing.nursing_care_plan_revisions") })
    }

    // ——— 5. 路由 ———

    @Test
    fun `修订路由注册在泛型 encounter 路由之前`(vertx: Vertx, testContext: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            plans = rows(planRow()),
            planItems = rows(planItemRow()),
            tasks = rows(taskRow()),
            maxNoRows = rows(maxNoRow(0)),
            countRows = rows(countRow(0L)),
            revisionRows = rowSet(),
        )
        withServer(vertx, stub) { port ->
            // GET 列表：走修订列表路由（返回分页结构）而不是泛型 getEncounter
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/care-plan-revisions")
                .compose { (listStatus, listBody) ->
                    testContext.verify {
                        assertEquals(200, listStatus)
                        assertTrue(listBody.containsKey("records"), "应返回修订列表结构, got: $listBody")
                        assertEquals(0, listBody.getJsonArray("records").size())
                        assertEquals(0L, listBody.getJsonObject("meta").getLong("total"))
                    }
                    // POST 创建：成功 201
                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/care-plan-revisions", validRevisionBody())
                        .map { (createStatus, createBody) ->
                            testContext.verify {
                                assertEquals(201, createStatus)
                                assertNotNull(createBody.getString("revision_id"))
                                assertEquals(1, createBody.getInteger("revision_no"))
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) testContext.completeNow() else testContext.failNow(ar.cause())
        }
    }

    // ——— 6. 只读列表 / 详情 ———

    @Test
    fun `列表按修订号倒序组装记录且读取无写副作用`() {
        val stub = DatabaseStub(
            revisionRows = rows(revisionRow()),
            assessments = rows(assessmentRow()),
            plans = rows(planRow()),
            countRows = rows(countRow(1L)),
        )
        val service = HealthcareService(stub.pool)
        val list = service.listCarePlanRevisions("enc-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(1L, list.getJsonObject("meta").getLong("total"))
        assertEquals(1, list.getJsonArray("records").size())
        val record = list.getJsonArray("records").getJsonObject(0)
        assertEquals("rev-1", record.getString("id"))
        assertEquals(1, record.getInteger("revision_no"))
        assertEquals("BARTHEL", record.getJsonObject("assessment").getString("assess_type"))
        assertEquals("2026-08-05", record.getJsonObject("assessment").getString("assess_date"))
        assertEquals("DISCONTINUED", record.getJsonObject("previous_plan").getString("status"))
        assertEquals("pln-2", record.getJsonObject("new_plan").getString("id"))
        assertEquals("ACTIVE", record.getJsonObject("new_plan").getString("status"))

        // 数据查询按修订号倒序
        val dataSql = stub.poolQueries.filter { it.contains("order by") }.last()
        assertTrue(dataSql.contains("revision_no desc"), "got: $dataSql")
        // 读取路径无任何写 SQL，且不经过事务连接
        assertTrue(stub.poolQueries.none { it.startsWith("insert") || it.startsWith("update") }, "读取不得发出写 SQL")
        assertTrue(stub.connQueries.isEmpty(), "读取不得使用事务连接")
    }

    @Test
    fun `详情读取校验 period 归属且不匹配时返回409`() {
        val stubMismatch = DatabaseStub(
            revisionRows = rows(revisionRow()),
            periods = rows(periodRow(mapOf("encounter_id" to "enc-9"))),
        )
        val service = HealthcareService(stubMismatch.pool)
        val cause = causeOf(service.getCarePlanRevision("rev-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("does not belong to the bound encounter") == true, "got: ${cause.message}")

        // 修订不存在返回 404
        val stubMissing = DatabaseStub()
        val serviceMissing = HealthcareService(stubMissing.pool)
        val causeMissing = causeOf(serviceMissing.getCarePlanRevision("rev-9"))
        assertInstanceOf(NotFoundException::class.java, causeMissing)
        assertEquals("care plan revision not found: rev-9", causeMissing.message)
    }

    @Test
    fun `详情读取组装评估新旧计划措施与任务`() {
        val stub = DatabaseStub(
            revisionRows = rows(revisionRow()),
            periods = rows(periodRow()),
            assessments = rows(assessmentRow()),
            plans = rows(planRow(mapOf("status" to "DISCONTINUED"))),
            newPlanRows = rows(planRow(mapOf("id" to "pln-2", "plan_name" to "第二阶段照护计划", "status" to "ACTIVE"))),
            planItems = rows(planItemRow()),
            tasks = rows(taskRow()),
        )
        val service = HealthcareService(stub.pool)
        val detail = service.getCarePlanRevision("rev-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("rev-1", detail.getString("id"))
        assertEquals("per-1", detail.getString("period_id"))
        assertEquals(1, detail.getInteger("revision_no"))
        assertEquals("BARTHEL", detail.getJsonObject("assessment").getString("assess_type"))
        assertEquals(65.0, detail.getJsonObject("assessment").getDouble("total_score"))
        assertEquals("第一阶段照护计划", detail.getJsonObject("previous_plan").getString("plan_name"))
        assertEquals("DISCONTINUED", detail.getJsonObject("previous_plan").getString("status"))
        assertEquals("pln-2", detail.getJsonObject("plan").getString("id"))
        assertEquals("第二阶段照护计划", detail.getJsonObject("plan").getString("plan_name"))
        assertEquals("ACTIVE", detail.getJsonObject("plan").getString("status"))
        assertEquals(1, detail.getJsonObject("plan").getJsonArray("items").size())
        assertEquals("每日协助晨间洗漱", detail.getJsonObject("plan").getJsonArray("items").getJsonObject(0).getString("action"))
        assertEquals(1, detail.getJsonArray("tasks").size())
        assertEquals("tsk-1", detail.getJsonArray("tasks").getJsonObject(0).getString("id"))

        // 读取路径无写副作用
        assertTrue(stub.poolQueries.none { it.startsWith("insert") || it.startsWith("update") }, "读取不得发出写 SQL")
    }

    private fun <T> withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
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
}

// ——— 辅助（top-level，供嵌套 DatabaseStub 的默认参数使用） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
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
