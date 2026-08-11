package com.ovaphlow.crate.dining

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 膳食营养模块业务枚举（一律中文值，不引入英文业务 code）。
 */
object DiningConstants {

    /** 餐食类型（长者饮食档案） */
    val MEAL_TYPES = setOf("普食", "软食", "碎食", "流食", "糖尿病餐")

    /** 份量偏好 */
    val PORTION_PREFERENCES = setOf("标准", "大半份", "小半份")

    /** 菜品分类 */
    val DISH_CATEGORIES = setOf("荤菜", "素菜", "汤品", "主食", "加餐")

    /** 餐次 */
    val MEAL_TIMES = setOf("早餐", "午餐", "晚餐", "加餐")

    /** 饮食标签 */
    val DIET_TAGS = setOf("低盐", "低糖", "无糖", "清真", "高蛋白", "少油", "无辣")

    /** 就餐执行状态 */
    val MEAL_STATUSES = setOf("正常", "部分", "未就餐", "拒食")

    /** 手工调整类型 */
    val ADJUST_TYPES = setOf("外出", "请假", "临时加餐")

    /** 启用状态（档案/菜品/菜谱共用） */
    val ENABLE_STATUSES = setOf("启用", "停用")

    /** 配餐口径排除的调整类型：这些长者本餐不就餐，不纳入应就餐人次 */
    val NOT_EXPECTED_ADJUST_TYPES = setOf("外出", "请假")

    /** 就餐率分子状态 */
    val EATEN_STATUSES = setOf("正常", "部分")

    /**
     * 计算就餐率（百分比，保留两位小数）；应就餐人次为 0 时返回 null。
     */
    fun diningRate(eaten: Long, expected: Long): Double? {
        if (expected <= 0) return null
        if (eaten <= 0) return 0.0
        return Math.round(eaten * 10000.0 / expected) / 100.0
    }

    /**
     * 返回 [date] 所在周的周一。
     */
    fun weekStartOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
}
