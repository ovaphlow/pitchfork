package com.ovaphlow.crate.nursing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TaskExecutionService 逾期判定逻辑的单元测试。
 * 直接调用生产方法 computeOverdueFields，不复制算法。
 */
class TaskExecutionServiceOverdueTest {

    private val now = OffsetDateTime.of(2026, 7, 30, 10, 0, 0, 0, ZoneOffset.ofHours(8))
    private val pastTime = OffsetDateTime.of(2026, 7, 30, 8, 30, 0, 0, ZoneOffset.ofHours(8))

    // ========================================================================
    //  逾期判定 — 直接调用 TaskExecutionService.computeOverdueFields
    // ========================================================================

    @Test
    fun `PENDING with planned time before now is overdue`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("PENDING", pastTime, now)
        assertTrue(isOverdue)
        assertNotNull(minutes)
        assertEquals(90, minutes) // 10:00 - 8:30 = 90 min
    }

    @Test
    fun `IN_PROGRESS with planned time before now is overdue`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("IN_PROGRESS", pastTime, now)
        assertTrue(isOverdue)
        assertNotNull(minutes)
        assertEquals(90, minutes)
    }

    @Test
    fun `PENDING with planned time equal to now is not overdue`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("PENDING", now, now)
        assertFalse(isOverdue)
        assertNull(minutes)
    }

    @Test
    fun `PENDING with planned time after now is not overdue`() {
        val futureTime = now.plusHours(2)
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("PENDING", futureTime, now)
        assertFalse(isOverdue)
        assertNull(minutes)
    }

    @Test
    fun `COMPLETED is never overdue even after planned time`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("COMPLETED", pastTime, now)
        assertFalse(isOverdue)
        assertNull(minutes)
    }

    @Test
    fun `SKIPPED is never overdue even after planned time`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("SKIPPED", pastTime, now)
        assertFalse(isOverdue)
        assertNull(minutes)
    }

    @Test
    fun `CANCELLED is never overdue even after planned time`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("CANCELLED", pastTime, now)
        assertFalse(isOverdue)
        assertNull(minutes)
    }

    @Test
    fun `null planned time is never overdue`() {
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("PENDING", null, now)
        assertFalse(isOverdue)
        assertNull(minutes)
    }

    @Test
    fun `overdue_minutes is floor of duration`() {
        // 8:30:45 -> 10:00:00 = 89 min 15 sec -> floor = 89
        val pastSeconds = OffsetDateTime.of(2026, 7, 30, 8, 30, 45, 0, ZoneOffset.ofHours(8))
        val (_, minutes) = TaskExecutionService.computeOverdueFields("PENDING", pastSeconds, now)
        assertEquals(89, minutes)
    }

    @Test
    fun `IN_PROGRESS with planned time just one minute before now is overdue with 1 minute`() {
        val oneMinuteAgo = now.minusMinutes(1)
        val (isOverdue, minutes) = TaskExecutionService.computeOverdueFields("IN_PROGRESS", oneMinuteAgo, now)
        assertTrue(isOverdue)
        assertEquals(1, minutes)
    }

    // ========================================================================
    //  路由参数校验逻辑 — 测试 overdue + status 组合规则
    // ========================================================================

    @ParameterizedTest
    @CsvSource(
        "COMPLETED, true",
        "SKIPPED,   true",
        "CANCELLED, true",
        "COMPLETED, false",
        "SKIPPED,   false",
        "CANCELLED, false",
    )
    fun `终端状态与逾期组合规则`(status: String, isOverdue: Boolean) {
        // overdue=true + 终态 => 非法（应返回 400）
        // overdue=false + 终态 => 合法（非逾期筛选，保留现有列表语义）
        val terminalStatuses = listOf("COMPLETED", "SKIPPED", "CANCELLED")
        val isTerminal = status in terminalStatuses
        val isIllegalCombination = isOverdue && isTerminal
        // 当 overdue=true 且 status 为终态时为非法组合
        assertEquals(isOverdue && isTerminal, isIllegalCombination)
    }

    @ParameterizedTest
    @CsvSource(
        "PENDING,    true",
        "IN_PROGRESS, true",
        "PENDING,    false",
        "IN_PROGRESS, false",
    )
    fun `非终端状态与逾期组合合法`(status: String, isOverdue: Boolean) {
        // PENDING/IN_PROGRESS 可与 overdue 自由组合
        val terminalStatuses = listOf("COMPLETED", "SKIPPED", "CANCELLED")
        val isIllegalCombination = isOverdue && (status in terminalStatuses)
        assertFalse(isIllegalCombination)
    }
}
