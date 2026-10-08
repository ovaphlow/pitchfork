package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 执行统计「应完成/已完成」口径的离线 SQL 断言（**不访问数据库**）。
 *
 * 045（2026-10-04 QA）：康复活动页三处进度互相矛盾 —— 同一个活动上，今日看板显示 完成 1/1，
 * 任务列表显示 已完成 1/2，进度统计显示 应完成 1、已完成 0、完成率 0.00%，执行人分组显示
 * 「暂无应完成任务」。根因之一是分母口径：`due_total` 只认 `planned_time < now`，
 * **提前完成**的那条既不在分子也不在分母。
 *
 * 新口径（本测试锁定）：`应完成 = 已到计划时间 或 已完成`，且全局汇总与按执行人分组
 * 必须用同一份定义；`逾期` 定义不变（仍只认 PENDING/IN_PROGRESS 且已过计划时间）。
 *
 * 用 jOOQ 离线渲染 + mockk 捕获池上实际执行的 SQL 文本与绑定参数（与 `TaskExecutionQuerySqlTest` 同法），
 * 因为 DB 门控集成用例默认不执行，本断言是这套口径在无库环境下的唯一护栏。
 */
class TaskExecutionStatisticsSqlTest {

    private class QueryLog {
        val statements = mutableListOf<String>()
    }

    private class QueryLogWithParams {
        val statements = mutableListOf<String>()
        val tuples = mutableListOf<Tuple>()
    }

    private fun loggedService(log: QueryLogWithParams): TaskExecutionService {
        val pool = mockk<Pool>()
        val row = mockk<Row>(relaxed = true)
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } returnsMany listOf(true, false)
        every { rowIterator.next() } returns row
        val rows = mockk<RowSet<Row>>()
        every { rows.iterator() } returns rowIterator
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { prepared.execute(capture(log.tuples)) } returns Future.succeededFuture(rows)
        every { pool.preparedQuery(capture(log.statements)) } returns prepared
        return TaskExecutionService(pool, DatabaseConfig.createDSL())
    }

    private fun statisticsSql(log: QueryLogWithParams): List<String> =
        log.statements.filter { it.contains("filter") && it.contains("scheduled_total") }

    /**
     * 取出某个聚合别名对应的 `filter (where …)` 片段。
     *
     * 从该别名**向前**找最近的一个 `filter`：正则从最左侧开始匹配会把前面几个别名
     * 一起吞进来（各 FILTER 表达式形状不同，逐个断言前必须先精确定位）。
     */
    private fun filterFor(sql: String, alias: String): String {
        val aliasIndex = sql.indexOf("as \"$alias\"")
        if (aliasIndex < 0) return ""
        val head = sql.substring(0, aliasIndex)
        val filterIndex = head.lastIndexOf("filter")
        return if (filterIndex < 0) "" else head.substring(filterIndex)
    }

    private fun runStatistics(log: QueryLogWithParams) {
        val service = loggedService(log)
        val result = service
            .executionStatistics(
                dateFrom = LocalDate.parse("2026-09-28"),
                dateTo = LocalDate.parse("2026-10-04"),
                taskType = "REHABILITATION",
            ).result()
        assertNotNull(result, "offline 渲染路径不应失败")
    }

    @Test
    fun `应完成的口径是已到计划时间或已完成，且全局与分组一致`() {
        val log = QueryLogWithParams()
        runStatistics(log)

        val aggregates = statisticsSql(log)
        assertEquals(2, aggregates.size, "全局汇总与按执行人分组各一条聚合 SQL：$aggregates")

        for (sql in aggregates) {
            println("[045-sql][statistics] $sql")

            val due = filterFor(sql, "due_total")
            assertTrue(due.isNotBlank(), "必须能取到应完成的 FILTER 表达式：$sql")
            assertTrue(due.contains("planned_time"), "应完成仍要包含「已到计划时间」：$due")
            assertTrue(
                Regex("\\bor\\b", RegexOption.IGNORE_CASE).containsMatchIn(due),
                "应完成必须是「已到计划时间 或 …」的析取式：$due",
            )
            assertTrue(
                Regex("status\"?\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(due),
                "应完成的另一个分支必须是状态判定：$due",
            )

            val completedDue = filterFor(sql, "completed_due_total")
            assertFalse(
                completedDue.contains("planned_time"),
                "分子不能再受 planned_time 限制（否则提前完成又被排除，回到旧缺陷）：$completedDue",
            )
            assertTrue(
                Regex("status\"?\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(completedDue),
                "分子必须是状态判定：$completedDue",
            )

            val overdue = filterFor(sql, "overdue_total")
            assertTrue(overdue.contains("planned_time"), "逾期定义不变（仍以计划时间为界）：$overdue")
            assertTrue(
                Regex("status\"?\\s+in\\b", RegexOption.IGNORE_CASE).containsMatchIn(overdue),
                "逾期定义不变（仍只认 PENDING/IN_PROGRESS）：$overdue",
            )
        }
    }

    @Test
    fun `按执行人分组的聚合与全局用同一份应完成定义`() {
        val log = QueryLogWithParams()
        runStatistics(log)

        val aggregates = statisticsSql(log)
        val globalSql = aggregates.single { !it.contains("group by") }
        val groupedSql = aggregates.single { it.contains("group by") }

        assertEquals(
            filterFor(globalSql, "due_total").replace(Regex("\\s+"), " ").trim(),
            filterFor(groupedSql, "due_total").replace(Regex("\\s+"), " ").trim(),
            "两处「应完成」口径必须逐字一致，否则全局与执行人表会再次打架",
        )
    }

    @Test
    fun `应完成的析取分支绑定的是已完成状态`() {
        val log = QueryLogWithParams()
        runStatistics(log)

        // 绑定参数按 SQL 文本出现顺序编号，聚合投影固定为
        // scheduled/pending/in_progress/completed/skipped/cancelled → due_total 的 OR 分支是第 7 个：
        // $6 = now（planned_time < now），$7 = 应完成析取分支的状态值。
        val tuples = log.tuples.filter { it.size() > 7 }
        assertTrue(tuples.isNotEmpty(), "必须捕获到聚合查询的绑定参数")
        for (tuple in tuples) {
            assertEquals(
                "COMPLETED",
                tuple.getValue(6)?.toString(),
                "应完成的 OR 分支必须绑定「已完成」，实际绑定：${tuple.getValue(6)}",
            )
        }
    }
}
