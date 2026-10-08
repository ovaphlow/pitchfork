package com.ovaphlow.crate.pharmacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 054 D1 儿科用量计算引擎单元测试（不访库、无 Vert.x）。
 *
 * 覆盖 §8.4 的 D1 验收点：按体重/年龄折算、上限封顶（单次 + 日总量）、区间缺失回落、
 * 非法输入报错（0 / 负数 / NaN / ±Infinity / 非数字 / 缺体重与年龄），并锁定
 * `capped` 标志与规则来源 [PediatricDoseSource]。
 */
class PediatricDoseCalculatorTest {

    private companion object {
        const val MATERIAL = "01J8ZQ6W9K7M3N5P2R4T6V8X0A"
    }

    private fun rule(
        id: String = "rule-1",
        materialId: String = MATERIAL,
        materialName: String? = "布洛芬混悬液",
        ageMinMonths: Int? = null,
        ageMaxMonths: Int? = null,
        weightMinKg: BigDecimal? = null,
        weightMaxKg: BigDecimal? = null,
        dosePerKg: BigDecimal? = null,
        fixedDose: BigDecimal? = null,
        singleDoseMax: BigDecimal? = null,
        dailyDoseMax: BigDecimal? = null,
        dosesPerDay: Int? = null,
        unit: String = "mg",
        status: String = PediatricDoseRule.STATUS_ENABLED,
        note: String? = null,
    ) = PediatricDoseRule(
        id = id,
        materialId = materialId,
        materialName = materialName,
        ageMinMonths = ageMinMonths,
        ageMaxMonths = ageMaxMonths,
        weightMinKg = weightMinKg,
        weightMaxKg = weightMaxKg,
        dosePerKg = dosePerKg,
        fixedDose = fixedDose,
        singleDoseMax = singleDoseMax,
        dailyDoseMax = dailyDoseMax,
        dosesPerDay = dosesPerDay,
        unit = unit,
        status = status,
        note = note,
    )

    private fun bd(value: String) = BigDecimal(value)

    // —— 折算 ——

    @Test
    fun `按体重折算单次与日总量`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("12.5"),
            60,
            rule(dosePerKg = bd("15"), dosesPerDay = 3, unit = "mg"),
        )

        assertEquals(bd("187.5"), result.singleDose)
        assertEquals(bd("562.5"), result.dailyDose)
        assertEquals("mg", result.unit)
        assertFalse(result.capped)
        assertEquals("rule-1", result.ruleId)
        assertEquals(PediatricDoseSource.GENERIC, result.source)
    }

    @Test
    fun `体重依赖规则折算保持十进制精确并去尾零`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("1.005"),
            null,
            rule(dosePerKg = bd("3")),
        )

        assertEquals(bd("3.015"), result.singleDose)
        assertEquals(bd("3.015"), result.dailyDose)
    }

    @Test
    fun `未给频次时按每日一次处理`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("10"),
            null,
            rule(dosePerKg = bd("2.5")),
        )

        assertEquals(bd("25"), result.singleDose)
        assertEquals(bd("25"), result.dailyDose)
    }

    @Test
    fun `固定剂量规则优先使用固定剂量`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("10"),
            24,
            rule(ageMinMonths = 12, ageMaxMonths = 36, fixedDose = bd("5"), unit = "mL"),
        )

        assertEquals(bd("5"), result.singleDose)
        assertEquals(PediatricDoseSource.AGE_BAND, result.source)
    }

    @Test
    fun `同时给体重依赖与固定剂量时优先体重依赖`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("10"),
            null,
            rule(dosePerKg = bd("2"), fixedDose = bd("999")),
        )

        assertEquals(bd("20"), result.singleDose)
    }

    // —— 上限封顶 ——

    @Test
    fun `单次上限封顶并置 capped`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("40"),
            null,
            rule(dosePerKg = bd("15"), singleDoseMax = bd("400"), dosesPerDay = 2),
        )

        assertEquals(bd("400"), result.singleDose)
        assertEquals(bd("800"), result.dailyDose)
        assertTrue(result.capped)
    }

    @Test
    fun `日总量上限封顶时单次向下摊回且自洽不超上限`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("10"),
            null,
            rule(dosePerKg = bd("20"), dosesPerDay = 3, dailyDoseMax = bd("500")),
        )

        assertTrue(result.capped)
        assertEquals(bd("166.666"), result.singleDose)
        assertEquals(bd("499.998"), result.dailyDose)
        assertTrue(result.dailyDose.compareTo(bd("500")) <= 0, "日总量不得超上限，实际: ${result.dailyDose}")
        assertEquals(
            0,
            result.singleDose.multiply(bd("3")).compareTo(result.dailyDose),
            "单次 × 频次 必须等于日总量",
        )
    }

    @Test
    fun `单次上限与日上限叠加封顶`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("10"),
            null,
            rule(
                dosePerKg = bd("20"),
                singleDoseMax = bd("180"),
                dailyDoseMax = bd("300"),
                dosesPerDay = 2,
            ),
        )

        assertTrue(result.capped)
        assertEquals(bd("150"), result.singleDose)
        assertEquals(bd("300"), result.dailyDose)
    }

    @Test
    fun `未触发上限时 capped 为 false`() {
        val result = PediatricDoseCalculator.calculate(
            MATERIAL,
            bd("10"),
            null,
            rule(dosePerKg = bd("2"), singleDoseMax = bd("100"), dailyDoseMax = bd("300"), dosesPerDay = 3),
        )

        assertFalse(result.capped)
        assertEquals(bd("20"), result.singleDose)
        assertEquals(bd("60"), result.dailyDose)
    }

    // —— 规则选择与区间缺失回落 ——

    @Test
    fun `命中体重与年龄双区间规则`() {
        val rules = listOf(
            rule(id = "generic", fixedDose = bd("5")),
            rule(id = "band", ageMinMonths = 12, ageMaxMonths = 36, weightMinKg = bd("8"), weightMaxKg = bd("16"), dosePerKg = bd("10")),
        )

        val result = PediatricDoseCalculator.calculate(MATERIAL, bd("12"), 24, rules)!!

        assertEquals("band", result.ruleId)
        assertEquals(bd("120"), result.singleDose)
        assertEquals(PediatricDoseSource.WEIGHT_AND_AGE_BAND, result.source)
    }

    @Test
    fun `具体度优先 双区间胜过单区间`() {
        val rules = listOf(
            rule(id = "weight-only", weightMinKg = bd("5"), weightMaxKg = bd("20"), fixedDose = bd("7")),
            rule(id = "both", ageMinMonths = 0, ageMaxMonths = 120, weightMinKg = bd("5"), weightMaxKg = bd("20"), fixedDose = bd("9")),
        )

        val result = PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, rules)!!

        assertEquals("both", result.ruleId)
        assertEquals(PediatricDoseSource.WEIGHT_AND_AGE_BAND, result.source)
    }

    @Test
    fun `区间边界为闭区间`() {
        val rules = listOf(
            rule(id = "band", weightMinKg = bd("10"), weightMaxKg = bd("20"), fixedDose = bd("4")),
            rule(id = "generic", fixedDose = bd("1")),
        )

        assertEquals("band", PediatricDoseCalculator.selectRule(MATERIAL, rules, bd("10"), null)!!.id)
        assertEquals("band", PediatricDoseCalculator.selectRule(MATERIAL, rules, bd("20"), null)!!.id)
        assertEquals("generic", PediatricDoseCalculator.selectRule(MATERIAL, rules, bd("20.001"), null)!!.id)
        assertEquals("generic", PediatricDoseCalculator.selectRule(MATERIAL, rules, bd("9.999"), null)!!.id)
    }

    @Test
    fun `区间缺失时回落到无区间通用规则并标记 GENERIC_FALLBACK`() {
        val rules = listOf(
            rule(id = "child-band", ageMinMonths = 6, ageMaxMonths = 12, fixedDose = bd("2")),
            rule(id = "generic", fixedDose = bd("5")),
        )

        val result = PediatricDoseCalculator.calculate(MATERIAL, null, 36, rules)!!

        assertEquals("generic", result.ruleId)
        assertEquals(PediatricDoseSource.GENERIC_FALLBACK, result.source)
        assertEquals(bd("5"), result.singleDose)
    }

    @Test
    fun `缺体重时带体重区间的规则不命中并回落通用规则`() {
        val rules = listOf(
            rule(id = "weight-band", weightMinKg = bd("5"), weightMaxKg = bd("20"), dosePerKg = bd("10")),
            rule(id = "generic", fixedDose = bd("3")),
        )

        val result = PediatricDoseCalculator.calculate(MATERIAL, null, 24, rules)!!

        assertEquals("generic", result.ruleId)
        assertEquals(PediatricDoseSource.GENERIC_FALLBACK, result.source)
    }

    @Test
    fun `只有通用规则时来源为 GENERIC 而非回落`() {
        val rules = listOf(rule(id = "generic", fixedDose = bd("5")))

        val result = PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, rules)!!

        assertEquals(PediatricDoseSource.GENERIC, result.source)
    }

    @Test
    fun `无任何可用规则时返回 null 而不是 0 剂量`() {
        val rules = listOf(rule(id = "band", ageMinMonths = 6, ageMaxMonths = 12, fixedDose = bd("2")))

        assertNull(PediatricDoseCalculator.calculate(MATERIAL, null, 36, rules))
        assertNull(PediatricDoseCalculator.selectRule(MATERIAL, rules, null, 36))
        assertNull(PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, emptyList()))
    }

    @Test
    fun `停用规则不参与选择`() {
        val rules = listOf(
            rule(id = "disabled", fixedDose = bd("5"), status = PediatricDoseRule.STATUS_DISABLED),
        )

        assertNull(PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, rules))
    }

    @Test
    fun `停用的区间规则不阻断通用规则回落`() {
        val rules = listOf(
            rule(id = "disabled-band", ageMinMonths = 6, ageMaxMonths = 12, fixedDose = bd("2"), status = PediatricDoseRule.STATUS_DISABLED),
            rule(id = "generic", fixedDose = bd("5")),
        )

        val result = PediatricDoseCalculator.calculate(MATERIAL, null, 36, rules)!!

        assertEquals("generic", result.ruleId)
        assertEquals(PediatricDoseSource.GENERIC, result.source, "区间规则已停用，不算发生区间回落")
    }

    @Test
    fun `其它药品的规则不参与选择`() {
        val rules = listOf(rule(id = "other", materialId = "OTHER-MATERIAL", fixedDose = bd("5")))

        assertNull(PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, rules))
    }

    @Test
    fun `同具体度并列时取列表首个`() {
        val rules = listOf(
            rule(id = "first", weightMinKg = bd("5"), weightMaxKg = bd("20"), fixedDose = bd("1")),
            rule(id = "second", weightMinKg = bd("5"), weightMaxKg = bd("20"), fixedDose = bd("2")),
        )

        assertEquals("first", PediatricDoseCalculator.selectRule(MATERIAL, rules, bd("10"), null)!!.id)
    }

    // —— 非法输入 ——

    @Test
    fun `体重为零或负数报错`() {
        listOf(bd("0"), bd("-1"), bd("-0.001")).forEach { weight ->
            val error = assertInstanceOf(
                IllegalArgumentException::class.java,
                runCatching { PediatricDoseCalculator.calculate(MATERIAL, weight, null, rule(dosePerKg = bd("10"))) }
                    .exceptionOrNull(),
            )
            assertTrue(error.message!!.contains("weight_kg"), "实际: ${error.message}")
        }
    }

    @Test
    fun `年龄为负报错`() {
        val error = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { PediatricDoseCalculator.calculate(MATERIAL, bd("10"), -1, rule(dosePerKg = bd("10"))) }
                .exceptionOrNull(),
        )
        assertTrue(error.message!!.contains("age_months"))
    }

    @Test
    fun `体重依赖规则缺体重报错`() {
        val error = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { PediatricDoseCalculator.calculate(MATERIAL, null, 24, rule(dosePerKg = bd("10"))) }
                .exceptionOrNull(),
        )
        assertTrue(error.message!!.contains("weight_kg is required"))
    }

    @Test
    fun `规则无剂量定义报错`() {
        val error = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, rule()) }
                .exceptionOrNull(),
        )
        assertTrue(error.message!!.contains("no dose definition"))
    }

    @Test
    fun `频次为零或负数报错`() {
        listOf(0, -2).forEach { frequency ->
            val error = assertInstanceOf(
                IllegalArgumentException::class.java,
                runCatching {
                    PediatricDoseCalculator.calculate(MATERIAL, bd("10"), null, rule(dosePerKg = bd("10"), dosesPerDay = frequency))
                }.exceptionOrNull(),
            )
            assertTrue(error.message!!.contains("doses_per_day"))
        }
    }

    @Test
    fun `剂量与上限非正数报错`() {
        val cases = listOf(
            rule(dosePerKg = bd("0")),
            rule(fixedDose = bd("-1")),
            rule(fixedDose = bd("5"), singleDoseMax = bd("0")),
            rule(fixedDose = bd("5"), dailyDoseMax = bd("-3")),
        )
        cases.forEach { candidate ->
            assertInstanceOf(
                IllegalArgumentException::class.java,
                runCatching { PediatricDoseCalculator.calculate(MATERIAL, bd("10"), null, candidate) }.exceptionOrNull(),
            )
        }
    }

    @Test
    fun `区间上下界倒置报错`() {
        val ageInverted = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching {
                PediatricDoseCalculator.calculate(MATERIAL, bd("10"), 24, rule(ageMinMonths = 36, ageMaxMonths = 6, fixedDose = bd("5")))
            }.exceptionOrNull(),
        )
        assertTrue(ageInverted.message!!.contains("age band is inverted"))

        val weightInverted = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching {
                PediatricDoseCalculator.calculate(MATERIAL, bd("10"), null, rule(weightMinKg = bd("20"), weightMaxKg = bd("5"), fixedDose = bd("5")))
            }.exceptionOrNull(),
        )
        assertTrue(weightInverted.message!!.contains("weight band is inverted"))
    }

    @Test
    fun `规则药品与入参药品不一致报错`() {
        val error = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching {
                PediatricDoseCalculator.calculate(MATERIAL, bd("10"), null, rule(materialId = "OTHER", fixedDose = bd("5")))
            }.exceptionOrNull(),
        )
        assertTrue(error.message!!.contains("does not belong"))
    }

    @Test
    fun `药品ID为空报错`() {
        assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { PediatricDoseCalculator.calculate("", bd("10"), null, rule(fixedDose = bd("5"))) }.exceptionOrNull(),
        )
        assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { PediatricDoseCalculator.selectRule("", emptyList(), bd("10"), null) }.exceptionOrNull(),
        )
    }

    @Test
    fun `parseWeightKg 接受十进制文本并拒绝 NaN 与无穷`() {
        assertEquals(bd("12.5"), PediatricDoseCalculator.parseWeightKg("12.5"))
        assertEquals(bd("12.5"), PediatricDoseCalculator.parseWeightKg(12.5))
        assertEquals(0, PediatricDoseCalculator.parseWeightKg(bd("12.500"))!!.compareTo(bd("12.5")))
        assertNull(PediatricDoseCalculator.parseWeightKg(null))

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "abc", true).forEach { raw ->
            val error = assertInstanceOf(
                IllegalArgumentException::class.java,
                runCatching { PediatricDoseCalculator.parseWeightKg(raw) }.exceptionOrNull(),
                "raw=$raw 必须被拒绝",
            )
            assertTrue(error.message!!.contains("weight_kg"), "实际: ${error.message}")
        }

        listOf("0", 0, 0.0, "-2", -2.5).forEach { raw ->
            val error = assertInstanceOf(
                IllegalArgumentException::class.java,
                runCatching { PediatricDoseCalculator.parseWeightKg(raw) }.exceptionOrNull(),
                "raw=$raw 必须被拒绝",
            )
            assertTrue(error.message!!.contains("positive"), "实际: ${error.message}")
        }
    }

    @Test
    fun `parseAgeMonths 只接受整数月龄并拒绝 NaN 与无穷`() {
        assertEquals(24, PediatricDoseCalculator.parseAgeMonths(24))
        assertEquals(24, PediatricDoseCalculator.parseAgeMonths(24L))
        assertEquals(24, PediatricDoseCalculator.parseAgeMonths(24.0))
        assertEquals(24, PediatricDoseCalculator.parseAgeMonths("24"))
        assertEquals(0, PediatricDoseCalculator.parseAgeMonths(0), "0 月龄（新生儿）合法")
        assertNull(PediatricDoseCalculator.parseAgeMonths(null))

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "abc", true, 24.5, "24.5").forEach { raw ->
            assertInstanceOf(
                IllegalArgumentException::class.java,
                runCatching { PediatricDoseCalculator.parseAgeMonths(raw) }.exceptionOrNull(),
                "raw=$raw 必须被拒绝",
            )
        }

        val negative = assertInstanceOf(
            IllegalArgumentException::class.java,
            runCatching { PediatricDoseCalculator.parseAgeMonths(-3) }.exceptionOrNull(),
        )
        assertTrue(negative.message!!.contains("must not be negative"))
    }

    @Test
    fun `缺体重与年龄时带区间规则不命中且无通用规则返回 null`() {
        val rules = listOf(
            rule(id = "age-band", ageMinMonths = 6, ageMaxMonths = 12, fixedDose = bd("2")),
            rule(id = "weight-band", weightMinKg = bd("5"), weightMaxKg = bd("20"), fixedDose = bd("3")),
        )

        assertNull(PediatricDoseCalculator.calculate(MATERIAL, null, null, rules))
    }
}
