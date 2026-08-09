package com.ovaphlow.crate.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 药房数量/成本精度入口（016 §4.1）单元测试。
 *
 * 十进制文本直接构造 [BigDecimal]：0.1 必须是精确 0.1，绝不带 double 二进制尾差；
 * 超 NUMERIC(20,6) / NUMERIC(24,8) 精度的输入直接拒绝（UNNECESSARY），不静默进位。
 */
class QuantitiesTest {

    @Test
    fun `decimalText 从十进制文本构造且不引入二进制尾差`() {
        assertEquals(BigDecimal("0.1"), decimalText(0.1), "Double 0.1 的十进制文本必须是精确 0.1")
        assertEquals(BigDecimal("0.1"), decimalText("0.1"), "字符串 0.1 直接构造")
        assertEquals(BigDecimal("3"), decimalText(3), "整数")
        assertEquals(BigDecimal("1.005"), decimalText(1.005), "1.005 不得变成 1.0049999999999999")
        assertEquals(BigDecimal("12.50"), decimalText("12.50"))
    }

    @Test
    fun `decimalText 对 null 与非法文本返回 null`() {
        assertNull(decimalText(null))
        assertNull(decimalText("abc"))
        assertNull(decimalText(JsonObjectValue()))
    }

    @Test
    fun `decimalText 不产生 double 尾差`() {
        val parsed = decimalText(0.1)!!
        // 尾差形式（如 0.1000000000000000055511...）在 6 位小数内必然出现多余位
        assertTrue(parsed.stripTrailingZeros().scale() <= 6, "0.1 不应带出 double 二进制尾差，实际: $parsed")
        assertTrue(parsed.compareTo(BigDecimal("0.1")) == 0)
    }

    @Test
    fun `requestDecimalText only accepts JSON decimal text`() {
        assertEquals(BigDecimal("0.1"), requestDecimalText("0.1"))
        assertNull(requestDecimalText(0.1))
        assertNull(requestDecimalText(BigDecimal("0.1")))
    }

    @Test
    fun `decimalApi 保留完整 NUMERIC 精度`() {
        assertEquals("99999999999999.123456", decimalApi(BigDecimal("99999999999999.123456")))
    }

    @Test
    fun `数量精度校验拒绝超过6位小数`() {
        validateQuantityPrecision(BigDecimal("123.123456"), "quantity")
        validateQuantityPrecision(BigDecimal("0.100000"), "quantity")
        validateQuantityPrecision(BigDecimal("5"), "quantity")

        val error = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { validateQuantityPrecision(BigDecimal("0.1234567"), "quantity") }.exceptionOrNull(),
        )
        assertTrue(error.message!!.contains("6 decimals"))
    }

    @Test
    fun `成本精度校验拒绝超过8位小数`() {
        validateCostPrecision(BigDecimal("99.12345678"), "unit_cost")
        validateCostPrecision(BigDecimal("0"), "unit_cost")

        val error = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { validateCostPrecision(BigDecimal("0.123456789"), "unit_cost") }.exceptionOrNull(),
        )
        assertTrue(error.message!!.contains("8 decimals"))
    }

    private class JsonObjectValue {
        override fun toString(): String = "not-a-number"
    }
}
