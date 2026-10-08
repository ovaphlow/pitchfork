package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 052 儿保公共口径的纯函数测试（**不访问数据库**）。
 *
 * 锁定月龄/天龄派生与前端 `lib/age.ts` 的 `formatAge` **同一日历差口径**：
 * 不足整月不进位（`2026-01-31 → 2026-02-28` 记 0 月），出生当天为 0 天。
 */
class ChildHealthAgeTest {

    @Test
    fun `出生当天为 0 月 0 天`() {
        val birth = LocalDate.of(2026, 3, 15)
        assertEquals(0, ChildHealth.ageMonths(birth, birth))
        assertEquals(0L, ChildHealth.ageDays(birth, birth))
    }

    @Test
    fun `不足整月不进位`() {
        val birth = LocalDate.of(2026, 2, 16)
        assertEquals(0, ChildHealth.ageMonths(birth, LocalDate.of(2026, 3, 15)))
        assertEquals(27L, ChildHealth.ageDays(birth, LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun `月末边界与前端 age_test 同口径`() {
        // 2026-01-31 → 2026-02-28：日 28 < 31，未满整月 → 0 月
        assertEquals(0, ChildHealth.ageMonths(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)))
        // 恰 1 个月
        assertEquals(1, ChildHealth.ageMonths(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15)))
        // 23 月 / 24 月
        assertEquals(23, ChildHealth.ageMonths(LocalDate.of(2024, 4, 15), LocalDate.of(2026, 3, 15)))
        assertEquals(24, ChildHealth.ageMonths(LocalDate.of(2024, 3, 15), LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun `闰日出生按日历月推进`() {
        // 2024-02-29 → 2025-02-28：日 28 < 29 → 11 月（与前端锁定一致）
        assertEquals(11, ChildHealth.ageMonths(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28)))
    }

    @Test
    fun `日期早于出生日期被拒绝`() {
        val birth = LocalDate.of(2026, 3, 15)
        assertThrows(IllegalArgumentException::class.java) {
            ChildHealth.ageMonths(birth, LocalDate.of(2026, 3, 14))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChildHealth.ageDays(birth, LocalDate.of(2026, 3, 14))
        }
    }
}
