package com.ovaphlow.crate.dining

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 054 A2：儿童营养参考值与食物成分**内置常量表**（计划 §11.2 Q6 推荐口径：内置常量、纯函数，
 * 不做外部知识库采购；无 DB、无 IO）。
 *
 * ## 口径与边界（务必与结论文案一起使用，不得当作医学结论）
 *
 * - 年龄段按**月龄左闭右开**划分，覆盖 `0–119` 月（0–10 岁）；超出范围由
 *   [ageBandOf] 明确报错，**不静默回落**到最近档（避免把成人/超龄数据按儿童档误判）。
 * - 能量 / 蛋白质 / 钠取中国居民膳食营养素参考摄入量（DRIs）儿童年龄段的**简化合并口径**
 *   （男女合并取中位；婴儿期把「每公斤体重 AI」按参考体重折算为每日值）。
 * - 脂肪 / 碳水**不直接给克数**，而由**供能比中位值**推导：脂肪 9 kcal/g、碳水 4 kcal/g。
 *   这样常量表只维护可追溯的原始口径，克数由 [referenceOf] 派生，避免两处手工数字漂移。
 * - 常量**版本化**（[VERSION]）：口径变更必须同时改版本号，并在结论文案里体现口径来源。
 * - 本表是**决策支持提示**的输入，不是诊断、处方或治疗依据（054 §12 R5）。
 *
 * ## 与 A1 落库口径的关系
 *
 * A1 的营养汇总读 `dining.dining_dishes.nutrition`（菜品库自维护的快照）；
 * 本表是**分析侧的内置回落**：当菜品没有营养快照、或按食物名分析自由文本摄入时使用。
 * [NutritionAnalysis.analyze] 允许调用方传入 `compositions`（例如由 `dining_dishes.nutrition`
 * 组装的映射），**优先用调用方数据，本表仅作缺省与回落**。
 */
object NutritionReference {

    /** 常量版本；口径变更必须同步（054 §12 R5：常量版本化）。 */
    const val VERSION = "CN-DRIS-2026.10"

    /** 支持的月龄下限（含）。 */
    const val MIN_AGE_MONTHS = 0

    /** 支持的月龄上限（含），即 119 月 = 9 岁 11 月；`>= 120` 报错。 */
    const val MAX_AGE_MONTHS = 119

    /** 年龄段（月龄左闭右开）。 */
    data class AgeBand(
        val code: String,
        val label: String,
        val minMonths: Int,
        val maxMonthsExclusive: Int,
    ) {
        fun contains(ageMonths: Int): Boolean = ageMonths in minMonths until maxMonthsExclusive
    }

    val AGE_BANDS: List<AgeBand> = listOf(
        AgeBand("0-6m", "0–6 月", 0, 6),
        AgeBand("7-12m", "7–12 月", 6, 12),
        AgeBand("1-3y", "1–3 岁", 12, 36),
        AgeBand("4-6y", "4–6 岁", 36, 72),
        AgeBand("7-10y", "7–10 岁", 72, 120),
    )

    /**
     * 每个年龄段的原始口径：能量 kcal/d、蛋白质 g/d、钠 mg/d、脂肪与碳水**供能比**。
     * 供能比取该年龄段推荐区间中位值（婴儿期按母乳/辅食过渡口径）。
     */
    private data class BandIntake(
        val energyKcal: Double,
        val proteinG: Double,
        val sodiumMg: Double,
        val fatEnergyRatio: Double,
        val carbEnergyRatio: Double,
    )

    private val INTAKES: Map<String, BandIntake> = mapOf(
        "0-6m" to BandIntake(energyKcal = 500.0, proteinG = 9.0, sodiumMg = 170.0, fatEnergyRatio = 0.48, carbEnergyRatio = 0.47),
        "7-12m" to BandIntake(energyKcal = 700.0, proteinG = 20.0, sodiumMg = 350.0, fatEnergyRatio = 0.38, carbEnergyRatio = 0.55),
        "1-3y" to BandIntake(energyKcal = 1100.0, proteinG = 25.0, sodiumMg = 700.0, fatEnergyRatio = 0.33, carbEnergyRatio = 0.55),
        "4-6y" to BandIntake(energyKcal = 1400.0, proteinG = 30.0, sodiumMg = 900.0, fatEnergyRatio = 0.28, carbEnergyRatio = 0.55),
        "7-10y" to BandIntake(energyKcal = 1700.0, proteinG = 40.0, sodiumMg = 1200.0, fatEnergyRatio = 0.25, carbEnergyRatio = 0.55),
    )

    /** 每日参考摄入量（脂肪/碳水由供能比派生，两位小数）。 */
    data class DailyReference(
        val energyKcal: Double,
        val proteinG: Double,
        val fatG: Double,
        val carbG: Double,
        val sodiumMg: Double,
    )

    /** 食物成分（每 [basisG] 克可食部）。 */
    data class FoodComposition(
        val foodName: String,
        val basisG: Double,
        val energyKcal: Double,
        val proteinG: Double,
        val fatG: Double,
        val carbG: Double,
        val sodiumMg: Double,
    )

    /**
     * 常见儿童餐食物成分常量表（每 100 g 可食部，中国食物成分表简化口径）。
     * 只收高频食材；缺失食材由 [NutritionAnalysis] 记为「未匹配」计 0，不报错。
     */
    val FOOD_COMPOSITIONS: Map<String, FoodComposition> = listOf(
        FoodComposition("米饭", 100.0, 116.0, 2.6, 0.3, 25.9, 2.5),
        FoodComposition("小米粥", 100.0, 46.0, 1.4, 0.7, 8.4, 2.5),
        FoodComposition("面条", 100.0, 110.0, 3.6, 0.4, 22.5, 100.0),
        FoodComposition("鸡蛋", 100.0, 144.0, 13.3, 8.8, 2.8, 131.5),
        FoodComposition("牛奶", 100.0, 54.0, 3.0, 3.2, 3.4, 37.2),
        FoodComposition("豆腐", 100.0, 82.0, 8.1, 3.7, 4.2, 7.2),
        FoodComposition("猪瘦肉", 100.0, 143.0, 20.3, 6.2, 1.5, 57.5),
        FoodComposition("鸡肉", 100.0, 167.0, 19.3, 9.4, 1.3, 63.3),
        FoodComposition("草鱼", 100.0, 113.0, 16.6, 5.2, 0.0, 46.0),
        FoodComposition("白菜", 100.0, 17.0, 1.5, 0.1, 3.2, 57.5),
        FoodComposition("胡萝卜", 100.0, 39.0, 1.0, 0.2, 8.8, 71.4),
        FoodComposition("苹果", 100.0, 53.0, 0.2, 0.2, 13.5, 1.6),
    ).associateBy { it.foodName }

    /** 两位小数 `HALF_UP`（与 A1 落库口径一致）。 */
    internal fun round2(value: Double): Double =
        BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()

    /**
     * 取月龄所在年龄段；超出 `0–119` 抛 `IllegalArgumentException`。
     * 边界语义：`0..5 → 0–6 月`、`6..11 → 7–12 月`、`12..35 → 1–3 岁`、
     * `36..71 → 4–6 岁`、`72..119 → 7–10 岁`。
     */
    fun ageBandOf(ageMonths: Int): AgeBand =
        AGE_BANDS.firstOrNull { it.contains(ageMonths) }
            ?: throw IllegalArgumentException(
                "age_months must be between $MIN_AGE_MONTHS and $MAX_AGE_MONTHS (0–10 岁), actual: $ageMonths",
            )

    /** 取月龄对应的每日参考摄入量（脂肪/碳水按供能比派生）。 */
    fun referenceOf(ageMonths: Int): DailyReference {
        val band = ageBandOf(ageMonths)
        val intake = INTAKES.getValue(band.code)
        return DailyReference(
            energyKcal = intake.energyKcal,
            proteinG = intake.proteinG,
            fatG = round2(intake.energyKcal * intake.fatEnergyRatio / 9.0),
            carbG = round2(intake.energyKcal * intake.carbEnergyRatio / 4.0),
            sodiumMg = intake.sodiumMg,
        )
    }

    /** 按食物名查内置成分；未收录返回 `null`（调用方按「未匹配」处理，不报错）。 */
    fun compositionOf(foodName: String): FoodComposition? = FOOD_COMPOSITIONS[foodName.trim()]
}
