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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 027 新增执行查询的离线 SQL 渲染断言（不访问数据库）。
 *
 * 本轮 DB 门控集成用例不执行，无法在真库上验证新增 SQL；本类用 jOOQ 离线渲染
 * （`DatabaseConfig.createDSL()` 不需要连接）+ mockk 捕获连接池上实际执行的 SQL 文本，
 * 把 §4.2 聚合与 §4.3 跨日队列的 SQL 形状固定下来：
 *   - `/overdue` 的 WHERE 只有逾期定义与参与条件，不含任何日期窗口下界；
 *   - `/today` 的 `status_totals` 与 `overdue_total_all` 在同一条聚合 SQL 内用
 *     `count(*) FILTER (WHERE ...)` 一次取回（不拉全表、不 N+1）；
 *   - `/today` 的 `total`、`overdue_total` 仍是各自独立的计数 SQL，WHERE 语义未变；
 *   - `/today` 与 `/overdue` 共用同一 FROM/JOIN 与投影列。
 */
class TaskExecutionQuerySqlTest {

    private class QueryLog {
        val statements = mutableListOf<String>()
    }

    /** 捕获池上执行的 SQL，并让每条查询都返回「一行 relaxed Row」以便链式调用走完 */
    private fun loggedService(log: QueryLog): TaskExecutionService {
        val pool = mockk<Pool>()
        val row = mockk<Row>(relaxed = true)
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } returnsMany listOf(true, false)
        every { rowIterator.next() } returns row
        val rows = mockk<RowSet<Row>>()
        every { rows.iterator() } returns rowIterator
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(rows)
        every { pool.preparedQuery(capture(log.statements)) } returns prepared
        return TaskExecutionService(pool, DatabaseConfig.createDSL())
    }

    /** 别名匹配带上词边界，避免 `"total"` 误匹配 `"total_cost"` */
    private fun hasAlias(sql: String, alias: String): Boolean =
        Regex("as\\s+\"?${Regex.escape(alias)}\"?(?![\\w_])", RegexOption.IGNORE_CASE).containsMatchIn(sql)

    private fun countMatches(sql: String, pattern: Regex): Int = pattern.findAll(sql).count()

    private val filterWhere = Regex("filter\\s*\\(\\s*where", RegexOption.IGNORE_CASE)
    private val plannedLowerBound = Regex("planned_time\"?\\s*>=", RegexOption.IGNORE_CASE)
    private val plannedUpperBound = Regex("planned_time\"?\\s*<", RegexOption.IGNORE_CASE)

    /** 提取 FROM ... WHERE 之间的 from/join 片段（不含 where 条件），用于断言共用连接 */
    private fun fromJoin(sql: String): String {
        val lower = sql.lowercase()
        val from = lower.indexOf(" from ")
        val where = lower.indexOf(" where ", from)
        return if (from < 0 || where < 0) "" else sql.substring(from, where)
    }

    @Test
    fun `overdue 队列 SQL 无日期窗口且按 planned_time 升序`() {
        val log = QueryLog()
        val service = loggedService(log)

        val result = service.overdueExecutions(periodId = "p-1", limit = 50, offset = 0).result()
        assertNotNull(result, "offline 渲染路径不应失败")

        // 只关心执行表上的 SQL（耗材摘要批量查询由 mock 行触发，不属于本次断言范围）
        val statements = log.statements.filterNot { it.contains("nursing_task_execution_consumptions") }
        statements.forEach { println("[027-sql][overdue] $it") }
        assertEquals(2, statements.size, "overdueExecutions 应只执行「计数 + 列表」两条 SQL：$log")
        for (sql in statements) {
            assertTrue(plannedUpperBound.containsMatchIn(sql), "必须包含 planned_time < now：$sql")
            assertFalse(plannedLowerBound.containsMatchIn(sql), "跨日队列不得出现日期窗口下界：$sql")
            assertTrue(hasAlias(sql, "total") || sql.contains("nursing_task_executions"), "SQL 必须落在执行表上：$sql")
            assertTrue(sql.contains("nursing_tasks"), "SQL 必须 join 任务表：$sql")
            assertTrue(sql.contains("nursing_service_periods"), "SQL 必须 join 服务期（参与条件）：$sql")
        }
        assertTrue(hasAlias(statements[0], "total"), "第一条应是计数 SQL：${statements[0]}")
        assertTrue(
            Regex("order\\s+by\\s+\"?e\"?\\.\"?planned_time\"?\\s+asc", RegexOption.IGNORE_CASE)
                .containsMatchIn(statements[1]),
            "列表 SQL 必须按 planned_time ASC 排序：${statements[1]}",
        )
    }

    @Test
    fun `today 的 status_totals 与 overdue_total_all 在一条聚合 SQL 内`() {
        val log = QueryLog()
        val service = loggedService(log)

        service.todayExecutions(date = LocalDate.of(2026, 7, 30)).result()

        val metaSqlList = log.statements.filter { it.contains("overdue_total_all") }
        assertEquals(1, metaSqlList.size, "聚合必须只用一条 SQL 取回六个计数：$metaSqlList")
        val metaSql = metaSqlList.single()

        for (alias in listOf("status_pending", "status_in_progress", "status_completed", "status_skipped", "status_cancelled", "overdue_total_all")) {
            assertTrue(hasAlias(metaSql, alias), "聚合 SQL 必须包含别名 $alias：$metaSql")
        }
        assertEquals(6, countMatches(metaSql, filterWhere), "应有 6 个 count(*) FILTER (WHERE ...)：$metaSql")
        println("[027-sql][today-meta] $metaSql")
        println("[027-sql][today-total] ${log.statements.single { hasAlias(it, "total") }}")
        println(
            "[027-sql][today-overdue_total] " +
                log.statements.single { it.contains("overdue_total") && !it.contains("overdue_total_all") },
        )
        // status_totals 落在日期窗口内；overdue_total_all 不带日期窗口
        assertEquals(5, countMatches(metaSql, plannedLowerBound), "五个状态计数必须限定在日期窗口内：$metaSql")

        // 既有 total / overdue_total 仍各自独立计数，语义未变
        val totalSql = log.statements.single { hasAlias(it, "total") }
        val overdueTotalSql = log.statements.single { it.contains("overdue_total") && !it.contains("overdue_total_all") }
        assertTrue(plannedLowerBound.containsMatchIn(totalSql), "total 仍限定当日窗口：$totalSql")
        assertTrue(plannedUpperBound.containsMatchIn(totalSql), "total 仍限定当日窗口：$totalSql")
        assertTrue(plannedLowerBound.containsMatchIn(overdueTotalSql), "overdue_total 仍限定当日窗口：$overdueTotalSql")
        assertTrue(plannedUpperBound.containsMatchIn(overdueTotalSql), "overdue_total 仍限定当日窗口：$overdueTotalSql")
        assertTrue(
            Regex("status\"?\\s+in\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(overdueTotalSql),
            "overdue_total 仍使用逾期定义（未完成状态）：$overdueTotalSql",
        )

        // status/overdue 筛选：total 增加额外条件，但聚合 SQL 必须逐字不变
        val log2 = QueryLog()
        val service2 = loggedService(log2)
        service2.todayExecutions(date = LocalDate.of(2026, 7, 30), status = "PENDING", overdue = true).result()
        val metaSql2 = log2.statements.single { it.contains("overdue_total_all") }
        assertEquals(metaSql, metaSql2, "status/overdue 筛选不得改变聚合 SQL")
    }

    @Test
    fun `today 与 overdue 共用同一 from join 与投影列`() {
        val todayLog = QueryLog()
        loggedService(todayLog).todayExecutions(date = LocalDate.of(2026, 7, 30)).result()
        val overdueLog = QueryLog()
        loggedService(overdueLog).overdueExecutions().result()

        val todayData = todayLog.statements.single { it.contains("patient_name") }
        val todayCount = todayLog.statements.single { hasAlias(it, "total") }
        val todayMeta = todayLog.statements.single { it.contains("overdue_total_all") }
        val overdueData = overdueLog.statements.single { it.contains("patient_name") }
        val overdueCount = overdueLog.statements.single { hasAlias(it, "total") }

        val shared = fromJoin(todayCount)
        assertTrue(shared.isNotBlank(), "from/join 片段应可提取：$todayCount")
        assertEquals(shared, fromJoin(todayMeta), "/today 聚合必须与计数共用同一 from/join")
        assertEquals(shared, fromJoin(todayData), "/today 列表必须与计数共用同一 from/join")
        assertEquals(shared, fromJoin(overdueCount), "/overdue 计数必须与 /today 共用同一 from/join")
        assertEquals(shared, fromJoin(overdueData), "/overdue 列表必须与 /today 共用同一 from/join")

        // 投影列共用：两条列表 SQL 的 select 列完全一致
        fun projection(sql: String) = sql.substringAfter("select ", "").substringBefore(" from ", "")
        assertEquals(projection(todayData), projection(overdueData), "/today 与 /overdue 必须共用同一投影列")
        assertTrue(projection(overdueData).contains("patient_name"), "投影必须包含 patient_name：$overdueData")
    }
}
