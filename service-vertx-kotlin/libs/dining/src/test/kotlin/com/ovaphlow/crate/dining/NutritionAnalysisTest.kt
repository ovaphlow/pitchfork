package com.ovaphlow.crate.dining

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 054 A2：营养分析引擎的纯逻辑单测（**不访库、不起服务**）。
 *
 * 覆盖（任务验收）：营养素折算（份量 × 摄入比例）、年龄段边界、缺项、非法份量/参数报错、
 * 结论与缺口项由服务端派生。
 */
class NutritionAnalysisTest {

    private fun item(name: String, amount: Double) = NutritionAnalysis.ItemInput(foodName = name, amountG = amount)

    // ========================================================================
    //  折算
    // ========================================================================

    @Test
    fun `按份量折算各营养素`() {
        val result = NutritionAnalysis.analyze(
            ageMonths = 12,
            intakeRatio = 1.0,
            items = listOf(item("米饭", 150.0), item("苹果", 100.0)),
        )
        // 米饭 116*1.5 + 苹果 53*1.0 = 227.00
        assertEquals(227.00, result.totals.energyKcal, 0.011, "能量折算")
        assertEquals(4.10, result.totals.proteinG, 0.011, "蛋白质折算")
        assertEquals(0.65, result.totals.fatG, 0.011, "脂肪折算")
        assertEquals(52.35, result.totals.carbG, 0.011, "碳水折算")
        assertEquals(5.35, result.totals.sodiumMg, 0.011, "钠折算")
        assertEquals(listOf("米饭", "苹果"), result.items.map { it.foodName })
        assertTrue(result.items.all { it.matched }, "两项都应命中成分表")
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun `摄入比例对全部营养素等比缩放`() {
        val full = NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", 150.0), item("苹果", 100.0)))
        val half = NutritionAnalysis.analyze(12, 0.5, listOf(item("米饭", 150.0), item("苹果", 100.0)))
        assertEquals(113.50, half.totals.energyKcal, 0.011, "能量 × 0.5")
        assertEquals(2.05, half.totals.proteinG, 0.011, "蛋白质 × 0.5")
        assertEquals(0.33, half.totals.fatG, 0.011, "脂肪 × 0.5")
        assertEquals(26.18, half.totals.carbG, 0.011, "碳水 × 0.5")
        assertEquals(2.68, half.totals.sodiumMg, 0.011, "钠 × 0.5")
        // 等比关系（允许两位小数取整误差）
        assertEquals(full.totals.energyKcal / 2.0, half.totals.energyKcal, 0.02)
        assertEquals(full.totals.proteinG / 2.0, half.totals.proteinG, 0.02)
    }

    @Test
    fun `自定义成分映射优先于内置常量表`() {
        val override = mapOf(
            "米饭" to NutritionReference.FoodComposition("米饭", 100.0, 100.0, 1.0, 0.0, 20.0, 0.0),
        )
        val result = NutritionAnalysis.analyze(
            ageMonths = 12,
            intakeRatio = 1.0,
            items = listOf(item("米饭", 100.0)),
            compositions = override,
        )
        assertEquals(100.0, result.totals.energyKcal, 0.001, "应使用调用方传入的成分，而非内置 116")
        assertEquals(20.0, result.totals.carbG, 0.001)
    }

    // ========================================================================
    //  年龄段边界
    // ========================================================================

    @Test
    fun `年龄段按月龄左闭右开划分`() {
        assertEquals("0-6m", NutritionReference.ageBandOf(0).code)
        assertEquals("0-6m", NutritionReference.ageBandOf(5).code)
        assertEquals("7-12m", NutritionReference.ageBandOf(6).code, "6 月整属于 7–12 月档")
        assertEquals("7-12m", NutritionReference.ageBandOf(11).code)
        assertEquals("1-3y", NutritionReference.ageBandOf(12).code, "12 月整属于 1–3 岁档")
        assertEquals("1-3y", NutritionReference.ageBandOf(35).code)
        assertEquals("4-6y", NutritionReference.ageBandOf(36).code, "36 月整属于 4–6 岁档")
        assertEquals("4-6y", NutritionReference.ageBandOf(71).code)
        assertEquals("7-10y", NutritionReference.ageBandOf(72).code, "72 月整属于 7–10 岁档")
        assertEquals("7-10y", NutritionReference.ageBandOf(119).code)
    }

    @Test
    fun `月龄越界明确报错不静默回落`() {
        val tooOld = assertThrows<IllegalArgumentException> { NutritionReference.ageBandOf(120) }
        assertTrue(tooOld.message!!.contains("age_months"), tooOld.message)
        assertThrows<IllegalArgumentException> { NutritionReference.ageBandOf(-1) }
        assertThrows<IllegalArgumentException> { NutritionAnalysis.analyze(120, 1.0, listOf(item("米饭", 100.0))) }
    }

    @Test
    fun `参考值按供能比派生脂肪与碳水`() {
        val infant = NutritionReference.referenceOf(0)
        assertEquals(500.0, infant.energyKcal, 0.001)
        assertEquals(9.0, infant.proteinG, 0.001)
        assertEquals(170.0, infant.sodiumMg, 0.001)
        assertEquals(26.67, infant.fatG, 0.011, "500 × 0.48 / 9")
        assertEquals(58.75, infant.carbG, 0.011, "500 × 0.47 / 4")

        val toddler = NutritionReference.referenceOf(12)
        assertEquals(1100.0, toddler.energyKcal, 0.001)
        assertEquals(25.0, toddler.proteinG, 0.001)
        assertEquals(700.0, toddler.sodiumMg, 0.001)
        assertEquals(40.33, toddler.fatG, 0.011, "1100 × 0.33 / 9")
        assertEquals(151.25, toddler.carbG, 0.011, "1100 × 0.55 / 4")

        val school = NutritionReference.referenceOf(72)
        assertEquals(1700.0, school.energyKcal, 0.001)
        assertEquals(47.22, school.fatG, 0.011, "1700 × 0.25 / 9")
        assertEquals(233.75, school.carbG, 0.011, "1700 × 0.55 / 4")
    }

    @Test
    fun `分析结果带上年龄段与参考值版本`() {
        val result = NutritionAnalysis.analyze(36, 1.0, listOf(item("米饭", 100.0)))
        assertEquals("4-6y", result.bandCode)
        assertEquals("4–6 岁", result.bandLabel)
        assertEquals(NutritionReference.VERSION, result.referenceVersion)
        assertEquals(NutritionReference.referenceOf(36).energyKcal, result.reference.energyKcal, 0.001)
    }

    // ========================================================================
    //  缺项
    // ========================================================================

    @Test
    fun `食物成分为空时items必须非空`() {
        val error = assertThrows<IllegalArgumentException> { NutritionAnalysis.analyze(12, 1.0, emptyList()) }
        assertEquals("items must not be empty", error.message)
    }

    @Test
    fun `未收录食物记为未匹配计0且不报错`() {
        val result = NutritionAnalysis.analyze(
            ageMonths = 12,
            intakeRatio = 1.0,
            items = listOf(item("米饭", 100.0), item("火龙果", 200.0)),
        )
        assertEquals(listOf("火龙果"), result.unmatched, "未收录食物必须登记在 unmatched")
        assertEquals(116.0, result.totals.energyKcal, 0.011, "未匹配项计 0，不影响已匹配项")
        assertFalse(result.items[1].matched)
        assertEquals(0.0, result.items[1].energyKcal, 0.001)
    }

    @Test
    fun `内置成分表查不到时返回null`() {
        assertNull(NutritionReference.compositionOf("火龙果"))
        assertTrue(NutritionReference.compositionOf(" 米饭 ") != null, "查表按 trim 后匹配")
        assertEquals(12, NutritionReference.FOOD_COMPOSITIONS.size, "常量表规模变化需同步本断言")
    }

    // ========================================================================
    //  非法份量 / 参数
    // ========================================================================

    @Test
    fun `份量非正或非数报错`() {
        for (amount in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val error = assertThrows<IllegalArgumentException> {
                NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", amount)))
            }
            assertTrue(error.message!!.contains("amount_g"), "份量 $amount 必须报错，实际: ${error.message}")
        }
    }

    @Test
    fun `食物名为空报错`() {
        val error = assertThrows<IllegalArgumentException> {
            NutritionAnalysis.analyze(12, 1.0, listOf(NutritionAnalysis.ItemInput("   ", 100.0)))
        }
        assertTrue(error.message!!.contains("food_name"), error.message)
    }

    @Test
    fun `摄入比例越界报错`() {
        for (ratio in listOf(0.0, -0.5, 1.5, Double.NaN)) {
            val error = assertThrows<IllegalArgumentException> {
                NutritionAnalysis.analyze(12, ratio, listOf(item("米饭", 100.0)))
            }
            assertTrue(error.message!!.contains("intake_ratio"), "比例 $ratio 必须报错，实际: ${error.message}")
        }
        // 边界值 1.0 合法
        assertEquals(1.0, NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", 100.0))).intakeRatio, 0.0)
    }

    // ========================================================================
    //  结论与缺口项（服务端派生）
    // ========================================================================

    @Test
    fun `低摄入判定为摄入明显不足且四项缺口`() {
        val result = NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", 10.0)))
        assertEquals("摄入明显不足", result.conclusion)
        assertEquals(listOf("能量", "蛋白质", "脂肪", "碳水化合物"), result.gaps)
    }

    @Test
    fun `达标摄入判定为摄入充足`() {
        val result = NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", 900.0)))
        // 116 × 9 = 1044 kcal / 1100 = 94.9% → 摄入充足
        assertEquals("摄入充足", result.conclusion)
        assertFalse(result.gaps.contains("能量"), "能量不应记缺口，实际: ${result.gaps}")
    }

    @Test
    fun `钠超参考值120%时结论与缺口都标注钠偏高`() {
        val result = NutritionAnalysis.analyze(12, 1.0, listOf(item("鸡蛋", 700.0)))
        // 钠 131.5 × 7 = 920.5 mg > 700 × 1.2 = 840
        assertTrue(result.conclusion.endsWith("；钠摄入偏高"), result.conclusion)
        assertTrue(result.gaps.contains("钠摄入偏高"), "缺口项必须含钠偏高，实际: ${result.gaps}")
    }

    @Test
    fun `结论是总量与参考值的纯函数由服务端派生`() {
        val result = NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", 150.0), item("苹果", 100.0)))
        // 引擎不接收任何结论入参；结论恒等于按总量派生的结果（客户端提交的结论无法影响）
        assertEquals(NutritionAnalysis.conclusionOf(result.totals, result.reference), result.conclusion)
        assertEquals(NutritionAnalysis.gapsOf(result.totals, result.reference), result.gaps)

        val low = NutritionAnalysis.analyze(12, 1.0, listOf(item("米饭", 10.0)))
        assertEquals(NutritionAnalysis.conclusionOf(low.totals, low.reference), low.conclusion)
    }

    @Test
    fun `空摄入零值结果用于空数据不报错`() {
        val result = NutritionAnalysis.emptyResult(12)
        assertEquals(0.0, result.totals.energyKcal, 0.001)
        assertEquals(0.0, result.totals.proteinG, 0.001)
        assertEquals(0.0, result.totals.sodiumMg, 0.001)
        assertEquals("无摄入记录", result.conclusion)
        assertTrue(result.items.isEmpty())
        assertTrue(result.unmatched.isEmpty())
        assertEquals(1100.0, result.reference.energyKcal, 0.001, "空结果仍要带参考值，供前端展示口径")
        assertEquals(listOf("能量", "蛋白质", "脂肪", "碳水化合物"), result.gaps)
    }
}
