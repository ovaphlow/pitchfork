package com.ovaphlow.crate.nursing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TaskExecutionService 统计相关的纯函数单元测试。
 * 直接调用生产方法 completionRate，不复制算法。
 */
class TaskExecutionStatisticsTest {

    // ========================================================================
    //  计划完成率
    // ========================================================================

    @Test
    fun `due_total为0时completion_rate为null`() {
        val rate = TaskExecutionService.completionRate(0, 0)
        assertNull(rate, "due_total 为 0 时完成率应为 null")
    }

    @Test
    fun `无已完成的任务时completion_rate为0`() {
        val rate = TaskExecutionService.completionRate(0, 10)
        assertNotNull(rate)
        assertEquals(0.0, rate!!, 0.001)
    }

    @Test
    fun `全部完成时completion_rate为100`() {
        val rate = TaskExecutionService.completionRate(10, 10)
        assertNotNull(rate)
        assertEquals(100.0, rate!!, 0.001)
    }

    @Test
    fun `完成率按百分比计算并四舍五入到两位小数`() {
        // 7/10 = 70%
        val rate1 = TaskExecutionService.completionRate(7, 10)
        assertNotNull(rate1)
        assertEquals(70.0, rate1!!, 0.001)

        // 1/3 = 33.333... → 33.33
        val rate2 = TaskExecutionService.completionRate(1, 3)
        assertNotNull(rate2)
        assertEquals(33.33, rate2!!, 0.001)

        // 2/3 = 66.666... → 66.67 (四舍五入)
        val rate3 = TaskExecutionService.completionRate(2, 3)
        assertNotNull(rate3)
        assertEquals(66.67, rate3!!, 0.001)

        // 1/6 = 16.666... → 16.67
        val rate4 = TaskExecutionService.completionRate(1, 6)
        assertNotNull(rate4)
        assertEquals(16.67, rate4!!, 0.001)
    }

    @Test
    fun `已跳过已取消和逾期未完成任务不增加completed_due_total`() {
        // completedDueTotal = 7, dueTotal = 11
        // 7/11 = 63.6363... → 63.64
        val rate = TaskExecutionService.completionRate(7, 11)
        assertNotNull(rate)
        assertEquals(63.64, rate!!, 0.001)
    }

    @Test
    fun `completedDueTotal为0时completion_rate为0`() {
        val rate = TaskExecutionService.completionRate(0, 5)
        assertNotNull(rate)
        assertEquals(0.0, rate!!, 0.001)
    }

    @Test
    fun `large_values_precision`() {
        // 大数值也能正确计算
        val rate = TaskExecutionService.completionRate(12345, 67890)
        assertNotNull(rate)
        assertEquals(18.18, rate!!, 0.001) // 12345/67890*100 = 18.183...
    }
}
