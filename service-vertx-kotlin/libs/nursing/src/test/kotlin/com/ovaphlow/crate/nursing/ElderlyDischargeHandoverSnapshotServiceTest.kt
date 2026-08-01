package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * ElderlyDischargeHandoverSnapshotService 的非数据库单元测试。
 *
 * 不访问数据库，使用 mockk 模拟 SqlConnection/RowSet：
 *   - 空输入组装：空数组、零计数
 *   - 计划/措施、任务/执行按 ID 批量归组且保持稳定顺序
 *   - 执行状态计数覆盖全部五种状态
 *   - buildNursingSnapshot 只执行固定次数的批量查询（计划非空 5 次、计划为空 4 次），
 *     不为每条子项逐行查询
 *   - 批量 SQL 带有稳定的 ORDER BY 且执行只按周期关联
 */
class ElderlyDischargeHandoverSnapshotServiceTest {

    private val service = ElderlyDischargeHandoverSnapshotService()

    private fun rowSet(vararg rows: Row): RowSet<Row> {
        val rs = mockk<RowSet<Row>>()
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        every { rs.iterator() } returns rowIterator
        every { rs.size() } returns rows.size
        return rs
    }

    private fun mockRow(values: Map<String, Any?>): Row {
        val row = mockk<Row>()
        values.forEach { (key, value) -> every { row.getValue(key) } returns value }
        return row
    }

    private fun rows(vararg values: Map<String, Any?>): RowSet<Row> = rowSet(*values.map { mockRow(it) }.toTypedArray())

    private fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject {
        val json = JsonObject()
        pairs.forEach { (k, v) -> if (v == null) json.putNull(k) else json.put(k, v) }
        return json
    }

    /** 构造连接；每个 preparedQuery 按 SQL 匹配 byTable 返回独立 PreparedQuery，execute 返回对应 RowSet（无顺序依赖） */
    private fun stubConnection(byTable: Map<String, RowSet<Row>> = emptyMap()): SqlConnection {
        val conn = mockk<SqlConnection>()
        every { conn.preparedQuery(any<String>()) } answers {
            val sql = firstArg<String>()
            // 长 key 优先匹配：executions 的 JOIN SQL 同时包含 nursing_tasks 子串
            val rs = byTable.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { sql.contains(it.key) }?.value ?: rowSet()
            val pq = mockk<PreparedQuery<RowSet<Row>>>()
            every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(rs)
            pq
        }
        every { conn.preparedQuery(any<String>(), any()) } answers {
            val sql = firstArg<String>()
            val rs = byTable.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { sql.contains(it.key) }?.value ?: rowSet()
            val pq = mockk<PreparedQuery<RowSet<Row>>>()
            every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(rs)
            pq
        }
        return conn
    }

    private fun <T> await(future: Future<T>): T =
        future.toCompletionStage().toCompletableFuture().get()

    // ——— assembleSnapshot 纯函数 ———

    @Test
    fun `空输入时快照返回空数组和零计数`() {
        val snapshot = ElderlyDischargeHandoverSnapshotService.assembleSnapshot(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        assertEquals(0, snapshot.getJsonArray("assessments").size())
        assertEquals(0, snapshot.getJsonArray("plans").size())
        assertEquals(0, snapshot.getJsonArray("tasks").size())
        val summary = snapshot.getJsonObject("execution_summary")
        for (status in listOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")) {
            assertEquals(0, summary.getInteger(status), "$status 计数必须为零")
        }
    }

    @Test
    fun `计划措施按ID归组且保持输入顺序`() {
        val plan1 = jsonOf("id" to "p1", "plan_name" to "跌倒预防")
        val plan2 = jsonOf("id" to "p2", "plan_name" to "营养支持")
        val item1 = jsonOf("id" to "i1", "plan_id" to "p1", "action" to "床栏检查")
        val item2 = jsonOf("id" to "i2", "plan_id" to "p2", "action" to "高蛋白加餐")
        val item3 = jsonOf("id" to "i3", "plan_id" to "p1", "action" to "夜间巡视")

        val snapshot = ElderlyDischargeHandoverSnapshotService.assembleSnapshot(
            emptyList(), listOf(plan1, plan2), listOf(item1, item2, item3), emptyList(), emptyList(),
        )
        val plans = snapshot.getJsonArray("plans")
        assertEquals(2, plans.size())
        assertEquals("p1", plans.getJsonObject(0).getString("id"))
        val p1Items = plans.getJsonObject(0).getJsonArray("items")
        assertEquals(listOf("i1", "i3"), p1Items.map { (it as JsonObject).getString("id") }, "p1 的措施按输入顺序归组")
        val p2Items = plans.getJsonObject(1).getJsonArray("items")
        assertEquals(listOf("i2"), p2Items.map { (it as JsonObject).getString("id") })
        assertEquals(0, plans.getJsonObject(1).getJsonArray("items").size() - 1, "p2 只有一条措施")
    }

    @Test
    fun `任务执行按ID归组且执行状态计数覆盖全部状态`() {
        val task1 = jsonOf("id" to "t1", "description" to "晨间翻身")
        val task2 = jsonOf("id" to "t2", "description" to "血压监测")
        val exec1 = jsonOf("id" to "e1", "task_id" to "t1", "status" to "COMPLETED")
        val exec2 = jsonOf("id" to "e2", "task_id" to "t1", "status" to "PENDING")
        val exec3 = jsonOf("id" to "e3", "task_id" to "t2", "status" to "IN_PROGRESS")
        val exec4 = jsonOf("id" to "e4", "task_id" to "t2", "status" to "CANCELLED")
        val exec5 = jsonOf("id" to "e5", "task_id" to "t2", "status" to "SKIPPED")

        val snapshot = ElderlyDischargeHandoverSnapshotService.assembleSnapshot(
            emptyList(), emptyList(), emptyList(), listOf(task1, task2), listOf(exec1, exec2, exec3, exec4, exec5),
        )
        val tasks = snapshot.getJsonArray("tasks")
        assertEquals(2, tasks.size())
        assertEquals(listOf("e1", "e2"), tasks.getJsonObject(0).getJsonArray("executions").map { (it as JsonObject).getString("id") })
        assertEquals(listOf("e3", "e4", "e5"), tasks.getJsonObject(1).getJsonArray("executions").map { (it as JsonObject).getString("id") })

        val summary = snapshot.getJsonObject("execution_summary")
        assertEquals(1, summary.getInteger("PENDING"))
        assertEquals(1, summary.getInteger("IN_PROGRESS"))
        assertEquals(1, summary.getInteger("COMPLETED"))
        assertEquals(1, summary.getInteger("SKIPPED"))
        assertEquals(1, summary.getInteger("CANCELLED"))
    }

    // ——— buildNursingSnapshot 批量查询 ———

    @Test
    fun `计划非空时固定五次批量查询且结果组装正确`() {
        val byTable = mapOf(
            "nursing_assessments" to rows(
                mapOf("id" to "a1", "assess_type" to "ADMISSION", "assess_date" to LocalDate.of(2026, 7, 1),
                    "assessor" to "王护士", "total_score" to BigDecimal("12.5"), "result_level" to "中",
                    "detail" to JsonObject().put("note", "x"), "remark" to null,
                    "created_at" to OffsetDateTime.parse("2026-07-01T09:00:00+08:00")),
            ),
            "nursing_plans" to rows(
                mapOf("id" to "p1", "plan_name" to "跌倒预防", "goals" to "g", "status" to "ACTIVE",
                    "created_by" to "李护师", "start_date" to LocalDate.of(2026, 7, 1), "end_date" to null,
                    "created_at" to OffsetDateTime.parse("2026-07-01T09:30:00+08:00")),
            ),
            "nursing_plan_items" to rows(
                mapOf("id" to "i1", "plan_id" to "p1", "action" to "床栏检查", "frequency_code" to "QD",
                    "frequency_name" to "每日一次", "duration_days" to 30, "remark" to "r", "status" to "ACTIVE",
                    "created_at" to OffsetDateTime.parse("2026-07-01T09:31:00+08:00")),
            ),
            "nursing_tasks" to rows(
                mapOf("id" to "t1", "description" to "晨间翻身", "task_type" to "NURSING",
                    "frequency_code" to "QD", "frequency_name" to "每日一次",
                    "start_date" to LocalDate.of(2026, 7, 1), "end_date" to LocalDate.of(2026, 7, 31),
                    "status" to "ACTIVE", "created_at" to OffsetDateTime.parse("2026-07-01T10:00:00+08:00")),
            ),
            "nursing_task_executions" to rows(
                mapOf("id" to "e1", "task_id" to "t1", "planned_time" to OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                    "actual_time" to OffsetDateTime.parse("2026-07-01T09:20:00+08:00"), "executor" to "王护士",
                    "status" to "COMPLETED", "note" to "ok", "created_at" to OffsetDateTime.parse("2026-07-01T09:21:00+08:00")),
            ),
        )
        val conn = stubConnection(byTable)

        val snapshot = await(service.buildNursingSnapshot(conn, "period-1"))

        assertEquals(1, snapshot.getJsonArray("assessments").size())
        assertEquals("ADMISSION", snapshot.getJsonArray("assessments").getJsonObject(0).getString("assess_type"))
        assertEquals(1, snapshot.getJsonArray("plans").size())
        assertEquals("i1", snapshot.getJsonArray("plans").getJsonObject(0).getJsonArray("items").getJsonObject(0).getString("id"))
        assertEquals(1, snapshot.getJsonArray("tasks").size())
        assertEquals("e1", snapshot.getJsonArray("tasks").getJsonObject(0).getJsonArray("executions").getJsonObject(0).getString("id"))
        assertEquals(1, snapshot.getJsonObject("execution_summary").getInteger("COMPLETED"))

        // 评估 + 计划 + 措施 + 任务 + 执行 = 恰好 5 次批量查询
        verify(exactly = 5) { conn.preparedQuery(any<String>()) }
    }

    @Test
    fun `计划为空时跳过措施查询只执行四次批量查询`() {
        val byTable = mapOf(
            "nursing_assessments" to rowSet(),
            "nursing_plans" to rowSet(),
            "nursing_tasks" to rowSet(),
            "nursing_task_executions" to rowSet(),
        )
        val conn = stubConnection(byTable)

        val snapshot = await(service.buildNursingSnapshot(conn, "period-empty"))

        assertEquals(0, snapshot.getJsonArray("assessments").size())
        assertEquals(0, snapshot.getJsonArray("plans").size())
        assertEquals(0, snapshot.getJsonArray("tasks").size())
        assertEquals(0, snapshot.getJsonObject("execution_summary").getInteger("COMPLETED"))

        verify(exactly = 4) { conn.preparedQuery(any<String>()) }
        // 措施查询从未触发
        verify(exactly = 0) { conn.preparedQuery(match { it.contains("nursing_plan_items") }) }
    }

    @Test
    fun `批量SQL包含稳定排序且执行只按周期关联`() {
        val conn = mockk<SqlConnection>()
        val queries = mutableListOf<String>()
        every { conn.preparedQuery(any<String>()) } answers {
            val sql = firstArg<String>()
            queries.add(sql)
            val pq = mockk<PreparedQuery<RowSet<Row>>>()
            every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(rowSet())
            pq
        }
        await(service.buildNursingSnapshot(conn, "period-1"))

        val assessmentsSql = queries.first { it.contains("nursing_assessments") }
        val executionsSql = queries.first { it.contains("nursing_task_executions") }
        val lowerA = assessmentsSql.lowercase()
        val lowerE = executionsSql.lowercase()
        org.junit.jupiter.api.Assertions.assertTrue(
            lowerA.contains("order by") && lowerA.indexOf("assess_date") < lowerA.indexOf("created_at"),
            "评估必须按 assess_date、created_at 升序稳定排序: $assessmentsSql",
        )
        // 执行通过任务 period_id 关联，不按患者或日期猜测
        org.junit.jupiter.api.Assertions.assertTrue(
            lowerE.contains("t.period_id") && lowerE.contains("order by") && lowerE.contains("actual_time"),
            "执行必须按任务 period_id 关联并稳定排序: $executionsSql",
        )
    }
}
