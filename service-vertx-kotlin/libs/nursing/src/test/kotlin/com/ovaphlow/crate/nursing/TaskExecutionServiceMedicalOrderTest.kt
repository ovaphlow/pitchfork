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
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 医嘱任务频次与活动周期生成资格的非数据库测试：
 *   - FrequencyCalculator 可生成频次白名单与计划时间计算
 *   - QOD/QW/BIW/TIW 星期模式与 metadata.schedule_times 覆盖
 *   - ensureExecutionsForDateRange 只选 ACTIVE 任务 + ACTIVE 周期，支持 periodId 限定
 *   - PRN/STAT 医嘱任务不产生计划执行（任务保留由 TaskService 负责）
 */
class TaskExecutionServiceMedicalOrderTest {

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

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    // ——— 1. 频次可生成白名单 ———

    @Test
    fun `可生成频次白名单`() {
        for (code in listOf("QD", "BID", "TID", "QID", "QOD", "QW", "BIW", "TIW")) {
            assertTrue(FrequencyCalculator.isGeneratable(code), "$code 必须可生成")
        }
        for (code in listOf("PRN", "STAT", "UNKNOWN", "", null)) {
            assertFalse(FrequencyCalculator.isGeneratable(code), "$code 不得生成")
        }
    }

    // ——— 2. 计划时间 ———

    @Test
    fun `QD与BID在目标日生成默认时段`() {
        val date = LocalDate.of(2026, 8, 1)
        val qd = FrequencyCalculator.plannedTimesForDate("QD", null, date, null)
        assertEquals(1, qd.size)
        assertEquals("09:00", qd[0].toLocalTime().toString())
        assertEquals(date, qd[0].toLocalDate())

        val bid = FrequencyCalculator.plannedTimesForDate("BID", null, date, null)
        assertEquals(2, bid.size)
        assertEquals("09:00", bid[0].toLocalTime().toString())
        assertEquals("18:00", bid[1].toLocalTime().toString())
    }

    @Test
    fun `PRN与STAT与未知频次不生成计划执行`() {
        val date = LocalDate.of(2026, 8, 1)
        assertTrue(FrequencyCalculator.plannedTimesForDate("PRN", null, date, null).isEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("STAT", null, date, null).isEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("UNKNOWN", null, date, null).isEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate(null, null, date, null).isEmpty())
    }

    @Test
    fun `QOD以开始日为第0天隔天生成`() {
        val start = LocalDate.of(2026, 8, 1)
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, LocalDate.of(2026, 8, 1), null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, LocalDate.of(2026, 8, 2), null).isEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, LocalDate.of(2026, 8, 3), null).isNotEmpty())
    }

    @Test
    fun `QW与BIW与TIW按星期模式生成`() {
        val start = LocalDate.of(2026, 8, 3) // 周一
        val mon = LocalDate.of(2026, 8, 3)
        val tue = LocalDate.of(2026, 8, 4)
        val wed = LocalDate.of(2026, 8, 5)
        val thu = LocalDate.of(2026, 8, 6)
        val fri = LocalDate.of(2026, 8, 7)
        val nextMon = LocalDate.of(2026, 8, 10)
        val nextWed = LocalDate.of(2026, 8, 12)
        val nextThu = LocalDate.of(2026, 8, 13)
        val nextFri = LocalDate.of(2026, 8, 14)

        // QW：只在与 start 同星期（周一）生成
        assertTrue(FrequencyCalculator.plannedTimesForDate("QW", start, mon, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("QW", start, nextMon, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("QW", start, tue, null).isEmpty())

        // BIW：周一 + 3 天（周四）
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, mon, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, thu, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, nextMon, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, nextThu, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, tue, null).isEmpty())

        // TIW：周一 + 2 天（周三）+ 4 天（周五）
        for (day in listOf(mon, wed, fri, nextMon, nextWed, nextFri)) {
            assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, day, null).isNotEmpty(), "$day 必须生成")
        }
        for (day in listOf(tue, thu)) {
            assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, day, null).isEmpty(), "$day 不得生成")
        }
    }

    @Test
    fun `metadata的schedule_times覆盖默认时段`() {
        val date = LocalDate.of(2026, 8, 1)
        val meta = JsonObject().put("schedule_times", listOf("07:00", "19:00"))
        val times = FrequencyCalculator.plannedTimesForDate("BID", null, date, meta)
        assertEquals(2, times.size)
        assertEquals("07:00", times[0].toLocalTime().toString())
        assertEquals("19:00", times[1].toLocalTime().toString())
    }

    // ——— 3. ensureExecutionsForDateRange 活动资格 ———

    private fun generationStub(): Pair<Pool, MutableList<String>> {
        val pool = mockk<Pool>()
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val queries = mutableListOf<String>()
        every { pool.preparedQuery(any<String>()) } answers {
            val sql = normalized(firstArg<String>())
            queries.add(sql)
            pq
        }
        every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(rowSet())
        return pool to queries
    }

    @Test
    fun `无任务时生成查询只选活动任务与活动周期且返回零计数`() {
        val (pool, queries) = generationStub()
        val service = TaskExecutionService(pool)

        val result = service.ensureExecutionsForDateRange(
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 2),
            null,
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals(0, result.getInteger("generated"))
        assertEquals(0, result.getInteger("skipped"))
        assertEquals(0, result.getJsonArray("errors").size())

        val taskSql = queries.single()
        assertTrue(taskSql.contains("nursing_tasks"), "任务查询必须读取 nursing_tasks: $taskSql")
        assertTrue(taskSql.contains("join"), "任务查询必须 join 周期: $taskSql")
        assertTrue(taskSql.contains("t.status"), "任务查询必须过滤任务 ACTIVE: $taskSql")
        assertTrue(taskSql.contains("ps.status"), "任务查询必须过滤周期 ACTIVE: $taskSql")
        assertTrue(taskSql.split("status").size - 1 >= 2, "status 过滤条件必须出现至少两次: $taskSql")
        assertTrue(queries.none { it.contains("insert") }, "无任务时不得生成任何执行写入")
    }

    @Test
    fun `传入periodId时任务查询限定周期`() {
        val (pool, queries) = generationStub()
        val service = TaskExecutionService(pool)

        service.ensureExecutionsForDateRange(
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 1),
            "per-1",
        ).toCompletionStage().toCompletableFuture().get()

        val taskSql = queries.single()
        assertTrue(taskSql.contains("t.period_id = $"), "任务查询必须按 period_id 限定: $taskSql")
    }
}
