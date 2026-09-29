package com.ovaphlow.crate.nursing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TaskExecutionService 长挂执行派生字段（§3.2）的单元测试。
 *
 * 直接调用生产纯函数 `TaskExecutionService.computeInProgressFields`，
 * 不复制算法；不访问数据库。
 *
 * 契约：
 *   in_progress_minutes = status == "IN_PROGRESS" && actual_time != null
 *                         ? max(0, floor(Duration.between(actual_time, now).toMinutes())) : null
 *   is_stale            = in_progress_minutes != null && in_progress_minutes >= 1440
 */
class TaskExecutionServiceStaleTest {

    private val now = OffsetDateTime.of(2026, 7, 30, 10, 0, 0, 0, ZoneOffset.ofHours(8))

    private val terminalStatuses = listOf("COMPLETED", "SKIPPED", "CANCELLED")

    // ========================================================================
    //  长挂判定 — 直接调用 TaskExecutionService.computeInProgressFields
    // ========================================================================

    @Test
    fun `阈值常量固定为 1440 分钟`() {
        assertEquals(1440, TaskExecutionService.STALE_IN_PROGRESS_MINUTES)
    }

    @Test
    fun `IN_PROGRESS over 24 hours is stale`() {
        val actualTime = now.minusHours(25) // 1500 分钟
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", actualTime, now)
        assertEquals(1500, minutes)
        assertTrue(isStale)
    }

    @Test
    fun `IN_PROGRESS exactly 1440 minutes is stale boundary inclusive`() {
        val actualTime = now.minusMinutes(TaskExecutionService.STALE_IN_PROGRESS_MINUTES.toLong())
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", actualTime, now)
        assertEquals(1440, minutes)
        assertTrue(isStale, "恰好 1440 分钟应判定为 is_stale=true（>= 阈值）")
    }

    @Test
    fun `IN_PROGRESS 1439 minutes is not stale`() {
        val actualTime = now.minusMinutes(1439)
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", actualTime, now)
        assertEquals(1439, minutes)
        assertFalse(isStale)
    }

    @Test
    fun `in_progress_minutes floors sub-minute remainder`() {
        // 1440 分 59 秒 → floor = 1440，仍为 stale
        val justOverThreshold = now.minusMinutes(1440).minusSeconds(59)
        val (overMinutes, overStale) =
            TaskExecutionService.computeInProgressFields("IN_PROGRESS", justOverThreshold, now)
        assertEquals(1440, overMinutes)
        assertTrue(overStale)

        // 1439 分 59 秒 → floor = 1439，不是 stale
        val justUnderThreshold = now.minusMinutes(1439).minusSeconds(59)
        val (underMinutes, underStale) =
            TaskExecutionService.computeInProgressFields("IN_PROGRESS", justUnderThreshold, now)
        assertEquals(1439, underMinutes)
        assertFalse(underStale)
    }

    @Test
    fun `IN_PROGRESS one minute in gives one minute and not stale`() {
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", now.minusMinutes(1), now)
        assertEquals(1, minutes)
        assertFalse(isStale)
    }

    @Test
    fun `IN_PROGRESS with null actual time yields null and not stale`() {
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", null, now)
        assertNull(minutes)
        assertFalse(isStale)
    }

    @Test
    fun `IN_PROGRESS with actual time equal to now yields zero and not stale`() {
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", now, now)
        assertEquals(0, minutes)
        assertFalse(isStale)
    }

    @Test
    fun `actual time later than now yields zero and not stale`() {
        val future = now.plusHours(10)
        val (minutes, isStale) = TaskExecutionService.computeInProgressFields("IN_PROGRESS", future, now)
        assertEquals(0, minutes, "actual_time 晚于 now 时应截断为 0，不出现负数")
        assertFalse(isStale)
    }

    @Test
    fun `PENDING is never stale even with old actual time`() {
        val (minutes, isStale) =
            TaskExecutionService.computeInProgressFields("PENDING", now.minusDays(2), now)
        assertNull(minutes)
        assertFalse(isStale)
    }

    @ParameterizedTest
    @CsvSource("COMPLETED", "SKIPPED", "CANCELLED")
    fun `terminal statuses are never stale`(status: String) {
        val (minutes, isStale) =
            TaskExecutionService.computeInProgressFields(status, now.minusDays(2), now)
        assertNull(minutes, "$status 的 in_progress_minutes 应为 null")
        assertFalse(isStale, "$status 的 is_stale 应恒为 false")
    }

    @Test
    fun `null or unknown status is never stale`() {
        val (nullMinutes, nullStale) =
            TaskExecutionService.computeInProgressFields(null, now.minusDays(2), now)
        assertNull(nullMinutes)
        assertFalse(nullStale)

        val (unknownMinutes, unknownStale) =
            TaskExecutionService.computeInProgressFields("WHATEVER", now.minusDays(2), now)
        assertNull(unknownMinutes)
        assertFalse(unknownStale)
    }

    @Test
    fun `terminal statuses list matches contract`() {
        // 终态集合与 §3.1/§3.2 一致：终态永不 stale
        for (status in terminalStatuses) {
            val (_, isStale) =
                TaskExecutionService.computeInProgressFields(status, now.minusMinutes(10_000), now)
            assertFalse(isStale, "$status 不应 stale")
        }
    }

    // ========================================================================
    //  附加：/overdue 分页收敛（§4.3，同为零依赖纯函数）
    // ========================================================================

    @Test
    fun `overdue paging defaults to 50 and 0`() {
        assertEquals(Pair(50, 0), TaskExecutionService.normalizeOverduePaging(null, null))
    }

    @Test
    fun `overdue paging clamps limit into 1 to 200`() {
        assertEquals(1, TaskExecutionService.normalizeOverduePaging(0, null).first)
        assertEquals(1, TaskExecutionService.normalizeOverduePaging(-5, null).first)
        assertEquals(1, TaskExecutionService.normalizeOverduePaging(1, null).first)
        assertEquals(200, TaskExecutionService.normalizeOverduePaging(200, null).first)
        assertEquals(200, TaskExecutionService.normalizeOverduePaging(201, null).first)
        assertEquals(200, TaskExecutionService.normalizeOverduePaging(9999, null).first)
    }

    @Test
    fun `overdue paging keeps non negative offset`() {
        assertEquals(0, TaskExecutionService.normalizeOverduePaging(null, -7).second)
        assertEquals(0, TaskExecutionService.normalizeOverduePaging(null, 0).second)
        assertEquals(37, TaskExecutionService.normalizeOverduePaging(null, 37).second)
    }
}
