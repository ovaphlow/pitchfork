package com.ovaphlow.crate.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 012 退药可退数量口径单元测试（不依赖数据库）。
 *
 * 可退 = 实发 − 累计已给药（已服/部分服）− 未取消退药（待确认 + 已确认）；
 * 已给药的数量不得退回库存，退药数量不能只和实发数量比较。
 */
class ReturnableQuantitiesTest {

    @Test
    fun `remaining 扣除已给药与已退数量`() {
        assertEquals(
            BigDecimal("2"),
            ReturnableQuantities.remaining(BigDecimal("10"), BigDecimal("5"), BigDecimal("3")),
        )
    }

    @Test
    fun `overReturnError 允许退到剩余可退上限`() {
        assertNull(
            ReturnableQuantities.overReturnError(
                requested = BigDecimal("2"),
                dispensed = BigDecimal("5"),
                administered = BigDecimal("2"),
                reserved = BigDecimal("1"),
            ),
        )
    }

    @Test
    fun `overReturnError 已给药吃满实发数量时拒绝退药`() {
        val error = ReturnableQuantities.overReturnError(
            requested = BigDecimal("3"),
            dispensed = BigDecimal("3"),
            administered = BigDecimal("3"),
            reserved = BigDecimal.ZERO,
        )
        assertTrue(error?.message?.contains("已无可退数量") == true, "got: ${error?.message}")
        assertTrue(error?.message?.contains("已给药 3") == true, "got: ${error?.message}")
    }

    @Test
    fun `overReturnError 退药量超过剩余可退时给出剩余数量`() {
        val error = ReturnableQuantities.overReturnError(
            requested = BigDecimal("3"),
            dispensed = BigDecimal("5"),
            administered = BigDecimal("2"),
            reserved = BigDecimal("1"),
        )
        assertTrue(error?.message?.contains("剩余可退 2") == true, "got: ${error?.message}")
    }
}
