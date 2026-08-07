package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 复评计划修订 Nursing 侧协作方法的非数据库测试：
 *   - 锁定 period 的活动状态、服务类型与患者一致性校验
 *   - 活动计划数量校验（无基线/多个活动计划拒绝）
 *   - 收束 SQL 与参数（计划/措施 DISCONTINUED、任务 CANCELLED）
 *   - 新计划/新任务绑定参数（plan_item_id/period_id/encounter_id/task_type=NURSING）
 *   - 修订号递增与 IN_PROGRESS 执行阻断
 */
class CarePlanRevisionServiceTest {

    private class DatabaseStub(
        var periods: RowSet<Row> = rowSet(),
        var plans: RowSet<Row> = rowSet(),
        var maxNoRows: RowSet<Row> = rowSet(),
        var inProgressRows: RowSet<Row> = rowSet(),
    ) {
        val connTuples = mutableListOf<Pair<String, List<Any?>>>()
        val connQueries = mutableListOf<String>()

        private var lastSql = ""
        val conn = mockk<SqlConnection>()
        val pool = mockk<Pool>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                connTuples.add(sql to tupleValues(firstArg()))
                val branch = when {
                    sql.startsWith("insert into") -> "insert"
                    sql.startsWith("update") -> "update"
                    sql.contains("from nursing.nursing_task_executions") -> "in_progress"
                    sql.contains("coalesce(max(") -> "max_no"
                    sql.contains("from nursing.nursing_service_periods") -> "periods"
                    sql.contains("from nursing.nursing_plans") -> "plans"
                    else -> "else"
                }
                val result = when (branch) {
                    "insert", "update", "else" -> rowSet()
                    "in_progress" -> inProgressRows
                    "max_no" -> maxNoRows
                    "periods" -> periods
                    "plans" -> plans
                    else -> rowSet()
                }
                Future.succeededFuture(result)
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            connQueries.add(lastSql)
        }
    }

    private fun periodRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "per-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to null,
            "coordinator" to null,
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
            "created_by" to "护理员",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to LocalDate.of(2026, 8, 14),
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun maxNoRow(value: Int): MutableMap<String, Any?> =
        mutableMapOf("max_no" to value)

    private fun causeOf(future: io.vertx.core.Future<*>): Throwable {
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

    private val planService = PlanService(mockk<Pool>(relaxed = true))
    private val taskService = TaskService(mockk<Pool>(relaxed = true))

    // ——— 锁定 period ———

    @Test
    fun `锁定周期校验服务类型、活动状态与患者一致性`() {
        val service = CarePlanRevisionService(mockk<Pool>(relaxed = true))

        val stubNoPeriod = DatabaseStub()
        val causeMissing = causeOf(service.lockPeriodForEncounter(stubNoPeriod.conn, "enc-1", "pat-1"))
        assertInstanceOf(ConflictException::class.java, causeMissing)
        assertTrue(causeMissing.message?.contains("no bound nursing care period") == true, "got: ${causeMissing.message}")

        val stubWrongType = DatabaseStub(periods = rows(periodRow(mapOf("service_type" to "HOME_CARE"))))
        val causeType = causeOf(service.lockPeriodForEncounter(stubWrongType.conn, "enc-1", "pat-1"))
        assertInstanceOf(ConflictException::class.java, causeType)
        assertTrue(causeType.message?.contains("not an elderly care period") == true, "got: ${causeType.message}")

        val stubClosed = DatabaseStub(periods = rows(periodRow(mapOf("status" to "COMPLETED"))))
        val causeClosed = causeOf(service.lockPeriodForEncounter(stubClosed.conn, "enc-1", "pat-1"))
        assertInstanceOf(ConflictException::class.java, causeClosed)
        assertTrue(causeClosed.message?.contains("not active") == true, "got: ${causeClosed.message}")

        val stubMismatch = DatabaseStub(periods = rows(periodRow(mapOf("patient_id" to "pat-9"))))
        val causeMismatch = causeOf(service.lockPeriodForEncounter(stubMismatch.conn, "enc-1", "pat-1"))
        assertInstanceOf(ConflictException::class.java, causeMismatch)
        assertTrue(causeMismatch.message?.contains("patient_id mismatch") == true, "got: ${causeMismatch.message}")

        val stubOk = DatabaseStub(periods = rows(periodRow()))
        val period = service.lockPeriodForEncounter(stubOk.conn, "enc-1", "pat-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("per-1", period.getString("id"))
        assertEquals("2026-08-01", period.getString("start_date"))
    }

    // ——— 活动计划数量 ———

    @Test
    fun `锁定活动计划拒绝无基线或多个活动计划`() {
        val stubNone = DatabaseStub()
        val causeNone = causeOf(planService.lockActivePlan(stubNone.conn, "per-1"))
        assertInstanceOf(ConflictException::class.java, causeNone)
        assertTrue(causeNone.message?.contains("no active plan") == true, "got: ${causeNone.message}")

        val stubMany = DatabaseStub(plans = rows(planRow(), planRow(mapOf("id" to "pln-2"))))
        val causeMany = causeOf(planService.lockActivePlan(stubMany.conn, "per-1"))
        assertInstanceOf(ConflictException::class.java, causeMany)
        assertTrue(causeMany.message?.contains("multiple active plans") == true, "got: ${causeMany.message}")

        val stubOne = DatabaseStub(plans = rows(planRow()))
        val plan = planService.lockActivePlan(stubOne.conn, "per-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("pln-1", plan.getString("id"))
        assertTrue(stubOne.connQueries.first { it.contains("from nursing.nursing_plans") }.contains("for update"))
    }

    // ——— 收束 ———

    @Test
    fun `终止计划将计划与活动措施收束为 DISCONTINUED`() {
        val stub = DatabaseStub()
        planService.terminatePlan(stub.conn, "pln-1")
            .toCompletionStage().toCompletableFuture().get()
        val updates = stub.connTuples.filter { it.first.startsWith("update") }
        assertEquals(2, updates.size)
        val planUpdate = updates.first { it.first.contains("nursing_plans") }
        assertEquals("DISCONTINUED", planUpdate.second[0])
        assertEquals("pln-1", planUpdate.second[2])
        val itemUpdate = updates.first { it.first.contains("nursing_plan_items") }
        assertEquals("DISCONTINUED", itemUpdate.second[0])
        assertEquals("pln-1", itemUpdate.second[1])
        // 只收束活动措施
        assertEquals("ACTIVE", itemUpdate.second[2])
    }

    @Test
    fun `取消任务只作用于活动任务并绑定任务ID`() {
        val stub = DatabaseStub()
        taskService.cancelPlanTasks(stub.conn, listOf("tsk-1", "tsk-2"))
            .toCompletionStage().toCompletableFuture().get()
        val update = stub.connTuples.first { it.first.startsWith("update") }
        assertEquals("CANCELLED", update.second[0])
        assertEquals("tsk-1", update.second[2])
        assertEquals("tsk-2", update.second[3])
        // 只取消活动任务
        assertEquals("ACTIVE", update.second[4])

        // 空列表不触发 SQL
        val stubEmpty = DatabaseStub()
        taskService.cancelPlanTasks(stubEmpty.conn, emptyList())
            .toCompletionStage().toCompletableFuture().get()
        assertTrue(stubEmpty.connQueries.isEmpty(), "空任务列表不得发出 SQL")
    }

    // ——— 新计划 / 新任务绑定参数 ———

    @Test
    fun `创建新计划写入固定字段并创建措施`() {
        val stub = DatabaseStub()
        val plan = planService.createPlanWithItems(
            stub.conn,
            PlanService.PlanCreateInput(
                periodId = "per-1",
                encounterId = "enc-1",
                planName = "第二阶段照护计划",
                goals = "提高日常活动能力",
                createdBy = "护理员",
                startDate = LocalDate.of(2026, 8, 5),
                endDate = LocalDate.of(2026, 8, 19),
                items = listOf(
                    PlanService.PlanItemInput("每日协助步行训练", "QD", "每日一次", 14, "根据耐受程度调整"),
                    PlanService.PlanItemInput("观察皮肤状况", null, null, null, null),
                ),
            ),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("ACTIVE", plan.getString("status"))
        assertEquals("per-1", plan.getString("period_id"))
        assertEquals("enc-1", plan.getString("encounter_id"))
        assertEquals(2, plan.getJsonArray("items").size())

        val planInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_plans") }
        assertEquals("per-1", planInsert.second[1])
        assertEquals("enc-1", planInsert.second[2])
        assertEquals("第二阶段照护计划", planInsert.second[3])
        assertEquals("ACTIVE", planInsert.second[4])

        val itemInserts = stub.connTuples.filter { it.first.startsWith("insert into nursing.nursing_plan_items") }
        assertEquals(2, itemInserts.size)
        // 措施精确绑定新计划 ID
        assertEquals(plan.getString("id"), itemInserts[0].second[1])
        assertEquals("每日协助步行训练", itemInserts[0].second[2])
        assertEquals("ACTIVE", itemInserts[0].second[3])
        assertEquals("QD", itemInserts[0].second[5])
        assertEquals(14, itemInserts[0].second[7])
        // 无频次措施不绑定频次字段
        assertEquals("观察皮肤状况", itemInserts[1].second[2])
    }

    @Test
    fun `创建计划任务绑定 plan_item_id 且类型为 NURSING`() {
        val stub = DatabaseStub()
        val task = taskService.createPlanTask(
            stub.conn,
            TaskService.PlanTaskInput(
                periodId = "per-1",
                encounterId = "enc-1",
                planItemId = "pmi-9",
                description = "每日协助步行训练",
                frequencyCode = "QD",
                frequencyName = "每日一次",
                startDate = LocalDate.of(2026, 8, 5),
                endDate = LocalDate.of(2026, 8, 19),
            ),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("pmi-9", task.getString("plan_item_id"))
        assertEquals("NURSING", task.getString("task_type"))
        assertEquals("ACTIVE", task.getString("status"))
        assertEquals("2026-08-19", task.getString("end_date"))

        val insert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_tasks") }
        assertEquals("per-1", insert.second[1])
        assertEquals("enc-1", insert.second[2])
        assertEquals("pmi-9", insert.second[3])
        assertEquals("NURSING", insert.second[4])
        assertEquals("每日协助步行训练", insert.second[5])
        assertEquals("ACTIVE", insert.second[6])
        assertEquals("QD", insert.second[9])
        assertEquals("每日一次", insert.second[10])
        assertEquals(LocalDate.of(2026, 8, 5), insert.second[11])
        assertEquals(LocalDate.of(2026, 8, 19), insert.second[12])

        // 空白 plan_item_id 拒绝
        val cause = causeOf(
            taskService.createPlanTask(
                stub.conn,
                TaskService.PlanTaskInput(
                    periodId = "per-1",
                    encounterId = "enc-1",
                    planItemId = "  ",
                    description = "x",
                    frequencyCode = null,
                    frequencyName = null,
                    startDate = null,
                    endDate = null,
                ),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertEquals("plan_item_id is required", cause.message)
    }

    // ——— 修订号与执行阻断 ———

    @Test
    fun `修订号首次为1并随最大值递增`() {
        val service = CarePlanRevisionService(mockk<Pool>(relaxed = true))
        val stubFirst = DatabaseStub(maxNoRows = rows(maxNoRow(0)))
        assertEquals(1, service.nextRevisionNo(stubFirst.conn, "per-1")
            .toCompletionStage().toCompletableFuture().get())

        val stubThird = DatabaseStub(maxNoRows = rows(maxNoRow(2)))
        assertEquals(3, service.nextRevisionNo(stubThird.conn, "per-1")
            .toCompletionStage().toCompletableFuture().get())
        assertTrue(stubThird.connQueries.first().contains("nursing_care_plan_revisions"))
    }

    @Test
    fun `存在 IN_PROGRESS 执行时拒绝修订`() {
        val service = CarePlanRevisionService(mockk<Pool>(relaxed = true))
        val stubClean = DatabaseStub(inProgressRows = rowSet())
        service.checkNoInProgressExecution(stubClean.conn, listOf("tsk-1"))
            .toCompletionStage().toCompletableFuture().get()

        val stubBlocked = DatabaseStub(inProgressRows = rows(mutableMapOf("one" to 1)))
        val cause = causeOf(service.checkNoInProgressExecution(stubBlocked.conn, listOf("tsk-1")))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("execution is in progress") == true, "got: ${cause.message}")
    }

    @Test
    fun `创建复评写入固定字段并返回评估`() {
        val service = CarePlanRevisionService(mockk<Pool>(relaxed = true))
        val stub = DatabaseStub()
        val assessment = service.createAssessment(
            stub.conn,
            CarePlanRevisionService.AssessmentCreateInput(
                encounterId = "enc-1",
                periodId = "per-1",
                assessType = "BARTHEL",
                assessDate = LocalDate.of(2026, 8, 5),
                assessor = "护理员",
                totalScore = 65.0,
                resultLevel = "中度依赖",
                detail = JsonObject().put("note", "近期步行能力下降"),
                remark = "复评说明",
            ),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("BARTHEL", assessment.getString("assess_type"))
        assertEquals("2026-08-05", assessment.getString("assess_date"))
        assertEquals(65.0, assessment.getDouble("total_score"))
        assertEquals("中度依赖", assessment.getString("result_level"))
        assertEquals("复评说明", assessment.getString("remark"))
        assertNotNull(assessment.getString("id"))

        val insert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_assessments") }
        assertEquals("enc-1", insert.second[1])
        assertEquals("per-1", insert.second[2])
        assertEquals("BARTHEL", insert.second[3])
        assertEquals(LocalDate.of(2026, 8, 5), insert.second[4])
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
