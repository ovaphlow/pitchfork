package com.ovaphlow.crate.dining

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 膳食营养模块纯函数单元测试（不访问数据库）。
 */
class DiningUnitTest {

    // ========================================================================
    //  业务枚举（一律中文值）
    // ========================================================================

    @Test
    fun `餐食类型为中文枚举`() {
        assertEquals(setOf("普食", "软食", "碎食", "流食", "糖尿病餐"), DiningConstants.MEAL_TYPES)
    }

    @Test
    fun `就餐状态为四种中文状态`() {
        assertEquals(setOf("正常", "部分", "未就餐", "拒食"), DiningConstants.MEAL_STATUSES)
    }

    @Test
    fun `餐次与菜品分类为中文枚举`() {
        assertEquals(setOf("早餐", "午餐", "晚餐", "加餐"), DiningConstants.MEAL_TIMES)
        assertEquals(setOf("荤菜", "素菜", "汤品", "主食", "加餐"), DiningConstants.DISH_CATEGORIES)
    }

    // ========================================================================
    //  就餐率
    // ========================================================================

    @Test
    fun `expected为0时就餐率为null`() {
        assertNull(DiningConstants.diningRate(0, 0), "应就餐人次为 0 时就餐率应为 null")
        assertNull(DiningConstants.diningRate(5, 0))
    }

    @Test
    fun `无人就餐时就餐率为0`() {
        assertEquals(0.0, DiningConstants.diningRate(0, 10)!!, 0.001)
    }

    @Test
    fun `全部就餐时就餐率为100`() {
        assertEquals(100.0, DiningConstants.diningRate(10, 10)!!, 0.001)
    }

    @Test
    fun `就餐率按百分比计算并四舍五入到两位小数`() {
        // 7/10 = 70%
        assertEquals(70.0, DiningConstants.diningRate(7, 10)!!, 0.001)
        // 1/3 = 33.333... → 33.33
        assertEquals(33.33, DiningConstants.diningRate(1, 3)!!, 0.001)
        // 2/3 = 66.666... → 66.67
        assertEquals(66.67, DiningConstants.diningRate(2, 3)!!, 0.001)
        // 1/6 = 16.666... → 16.67
        assertEquals(16.67, DiningConstants.diningRate(1, 6)!!, 0.001)
    }

    // ========================================================================
    //  周起始日
    // ========================================================================

    @Test
    fun `周起始日为周一`() {
        // 2026-08-03 是周一
        val monday = LocalDate.of(2026, 8, 3)
        assertEquals(monday, DiningConstants.weekStartOf(monday))
        // 2026-08-07 是周五，同属 8/3 那周
        assertEquals(monday, DiningConstants.weekStartOf(LocalDate.of(2026, 8, 7)))
        // 2026-08-02 是周日，属上一周（7/27 周一）
        assertEquals(LocalDate.of(2026, 7, 27), DiningConstants.weekStartOf(LocalDate.of(2026, 8, 2)))
    }
}
