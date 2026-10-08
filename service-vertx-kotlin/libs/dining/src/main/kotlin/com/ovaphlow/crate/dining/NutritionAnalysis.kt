package com.ovaphlow.crate.dining

/**
 * 054 A2：营养分析**纯函数引擎**（无 DB、无 IO、无状态、不依赖 Vert.x）。
 *
 * 职责（计划 §3 Dg、§8.1）：
 *  1. **折算**：`食物项份量(amount_g) ÷ 成分基准(basis_g) × 成分值 × 摄入比例(intake_ratio)`；
 *     总量两位小数 `HALF_UP`，与 A1 落库口径一致。
 *  2. **比对**：按**月龄**取 [NutritionReference] 的年龄段每日参考值。
 *  3. **派生**：缺口项与**结论**一律由本引擎服务端派生。本 API **不接收任何结论入参**，
 *     客户端提交的 `conclusion` 在类型层面就无法影响结果（A3 路由必须丢弃该字段）。
 *  4. **缺项容错**：食物名未收录 → 该食物项计 0 并登记到 [Result.unmatched]，不报错；
 *     `items` 为空 / 份量非正 / 比例越界 / 月龄越界 → 抛 `IllegalArgumentException`（路由 400）。
 *
 * 数据源优先级：调用方通过 [analyze] 的 `compositions` 传入（例如 A3 由
 * `dining.dining_dishes.nutrition` 组装）→ 未传时回落到 [NutritionReference.FOOD_COMPOSITIONS]。
 */
object NutritionAnalysis {

    /** 缺口判定阈值：低于参考值的 80% 记为缺口。 */
    const val GAP_THRESHOLD = 0.8

    /** 钠偏高判定阈值：高于参考值的 120% 记为偏高。 */
    const val SODIUM_HIGH_THRESHOLD = 1.2

    /** 缺口项 / 营养素的展示名（顺序即返回顺序）。 */
    val NUTRIENT_LABELS: List<String> = listOf("能量", "蛋白质", "脂肪", "碳水化合物")

    /** 单项输入（份量克数 + 可选备注）。 */
    data class ItemInput(
        val foodName: String,
        val amountG: Double,
        val note: String? = null,
    )

    /** 单项折算结果（[matched] = false 表示食物成分缺项，各项为 0）。 */
    data class ItemResult(
        val foodName: String,
        val amountG: Double,
        val matched: Boolean,
        val energyKcal: Double,
        val proteinG: Double,
        val fatG: Double,
        val carbG: Double,
        val sodiumMg: Double,
    )

    /** 营养素总量（两位小数）。 */
    data class Totals(
        val energyKcal: Double,
        val proteinG: Double,
        val fatG: Double,
        val carbG: Double,
        val sodiumMg: Double,
    )

    /** 分析结果。 */
    data class Result(
        val ageMonths: Int,
        val bandCode: String,
        val bandLabel: String,
        val intakeRatio: Double,
        val totals: Totals,
        val reference: NutritionReference.DailyReference,
        /** 低于参考值 80% 的缺口项；钠偏高时含「钠摄入偏高」。 */
        val gaps: List<String>,
        /** 服务端派生结论（不宣称医学结论，仅作决策支持提示）。 */
        val conclusion: String,
        val items: List<ItemResult>,
        /** 未收录食物成分的食物名（去重、保持首次出现顺序）。 */
        val unmatched: List<String>,
        /** 参考值常量版本（结论文案需标注口径来源）。 */
        val referenceVersion: String,
    )

    /**
     * 分析一餐/一日的摄入。
     *
     * @param ageMonths 月龄（0–119，越界报错）
     * @param intakeRatio 摄入比例 (0, 1]
     * @param items 食物项（非空）
     * @param compositions 食物成分映射；缺省用内置常量表
     */
    fun analyze(
        ageMonths: Int,
        intakeRatio: Double = 1.0,
        items: List<ItemInput>,
        compositions: Map<String, NutritionReference.FoodComposition> = NutritionReference.FOOD_COMPOSITIONS,
    ): Result {
        val band = NutritionReference.ageBandOf(ageMonths)
        val reference = NutritionReference.referenceOf(ageMonths)
        requireRatio(intakeRatio)
        if (items.isEmpty()) throw IllegalArgumentException("items must not be empty")

        val results = items.mapIndexed { index, item -> convert(index, item, intakeRatio, compositions) }
        val totals = sum(results)
        return Result(
            ageMonths = ageMonths,
            bandCode = band.code,
            bandLabel = band.label,
            intakeRatio = intakeRatio,
            totals = totals,
            reference = reference,
            gaps = gapsOf(totals, reference),
            conclusion = conclusionOf(totals, reference),
            items = results.map { it.item },
            unmatched = results.filter { !it.item.matched }.map { it.item.foodName }.distinct(),
            referenceVersion = NutritionReference.VERSION,
        )
    }

    /**
     * 空摄入（当天无任何记录）的零值结果：供 A3 路由在无数据时直接返回，
     * 保证「空数据不 500」且结论明确为「无摄入记录」。
     */
    fun emptyResult(ageMonths: Int, intakeRatio: Double = 1.0): Result {
        val band = NutritionReference.ageBandOf(ageMonths)
        requireRatio(intakeRatio)
        val reference = NutritionReference.referenceOf(ageMonths)
        val totals = Totals(0.0, 0.0, 0.0, 0.0, 0.0)
        return Result(
            ageMonths = ageMonths,
            bandCode = band.code,
            bandLabel = band.label,
            intakeRatio = intakeRatio,
            totals = totals,
            reference = reference,
            gaps = gapsOf(totals, reference),
            conclusion = conclusionOf(totals, reference),
            items = emptyList(),
            unmatched = emptyList(),
            referenceVersion = NutritionReference.VERSION,
        )
    }

    // ========================================================================
    //  派生（服务端唯一口径）
    // ========================================================================

    /** 缺口项：能量/蛋白质/脂肪/碳水低于参考值 80%；钠高于参考值 120% 记「钠摄入偏高」。 */
    fun gapsOf(totals: Totals, reference: NutritionReference.DailyReference): List<String> {
        val gaps = mutableListOf<String>()
        if (below(totals.energyKcal, reference.energyKcal)) gaps.add(NUTRIENT_LABELS[0])
        if (below(totals.proteinG, reference.proteinG)) gaps.add(NUTRIENT_LABELS[1])
        if (below(totals.fatG, reference.fatG)) gaps.add(NUTRIENT_LABELS[2])
        if (below(totals.carbG, reference.carbG)) gaps.add(NUTRIENT_LABELS[3])
        if (reference.sodiumMg > 0.0 && totals.sodiumMg > reference.sodiumMg * SODIUM_HIGH_THRESHOLD) {
            gaps.add("钠摄入偏高")
        }
        return gaps
    }

    /**
     * 结论派生（服务端唯一来源）：
     * 能量覆盖率 ≥90% 摄入充足、≥70% 基本充足、≥50% 不足、>0 明显不足、=0 无摄入记录；
     * 钠超参考值 120% 追加「钠摄入偏高」。文案只描述「相对参考值」的摄入情况，不作医学判断。
     */
    fun conclusionOf(totals: Totals, reference: NutritionReference.DailyReference): String {
        val coverage = if (reference.energyKcal <= 0.0) 0.0 else totals.energyKcal / reference.energyKcal
        val base = when {
            coverage >= 0.9 -> "摄入充足"
            coverage >= 0.7 -> "摄入基本充足"
            coverage >= 0.5 -> "摄入不足"
            coverage > 0.0 -> "摄入明显不足"
            else -> "无摄入记录"
        }
        val sodiumHigh = reference.sodiumMg > 0.0 &&
            totals.sodiumMg > reference.sodiumMg * SODIUM_HIGH_THRESHOLD
        return if (sodiumHigh) "$base；钠摄入偏高" else base
    }

    // ========================================================================
    //  内部
    // ========================================================================

    /** 单项折算结果 + **未取整**中间值（总量按未取整值求和后再取整，避免累积偏差）。 */
    private data class Converted(val item: ItemResult, val raw: Totals)

    private fun requireRatio(intakeRatio: Double) {
        if (!intakeRatio.isFinite() || intakeRatio <= 0.0 || intakeRatio > 1.0)
            throw IllegalArgumentException("intake_ratio must be greater than 0 and at most 1")
    }

    private fun convert(
        index: Int,
        item: ItemInput,
        intakeRatio: Double,
        compositions: Map<String, NutritionReference.FoodComposition>,
    ): Converted {
        val foodName = item.foodName.trim()
        if (foodName.isEmpty())
            throw IllegalArgumentException("items[$index].food_name is required")
        if (!item.amountG.isFinite() || item.amountG <= 0.0)
            throw IllegalArgumentException("items[$index].amount_g must be a positive number")

        val composition = compositions[foodName]
        if (composition == null || !composition.basisG.isFinite() || composition.basisG <= 0.0) {
            return Converted(
                item = ItemResult(
                    foodName = foodName,
                    amountG = NutritionReference.round2(item.amountG),
                    matched = false,
                    energyKcal = 0.0,
                    proteinG = 0.0,
                    fatG = 0.0,
                    carbG = 0.0,
                    sodiumMg = 0.0,
                ),
                raw = Totals(0.0, 0.0, 0.0, 0.0, 0.0),
            )
        }

        val factor = item.amountG / composition.basisG * intakeRatio
        val rawEnergy = composition.energyKcal * factor
        val rawProtein = composition.proteinG * factor
        val rawFat = composition.fatG * factor
        val rawCarb = composition.carbG * factor
        val rawSodium = composition.sodiumMg * factor
        return Converted(
            item = ItemResult(
                foodName = foodName,
                amountG = NutritionReference.round2(item.amountG),
                matched = true,
                energyKcal = NutritionReference.round2(rawEnergy),
                proteinG = NutritionReference.round2(rawProtein),
                fatG = NutritionReference.round2(rawFat),
                carbG = NutritionReference.round2(rawCarb),
                sodiumMg = NutritionReference.round2(rawSodium),
            ),
            raw = Totals(rawEnergy, rawProtein, rawFat, rawCarb, rawSodium),
        )
    }

    /** 总量按**未取整**中间值求和后再取整（与 A1 落库口径一致）。 */
    private fun sum(results: List<Converted>): Totals {
        var energy = 0.0
        var protein = 0.0
        var fat = 0.0
        var carb = 0.0
        var sodium = 0.0
        for (converted in results) {
            energy += converted.raw.energyKcal
            protein += converted.raw.proteinG
            fat += converted.raw.fatG
            carb += converted.raw.carbG
            sodium += converted.raw.sodiumMg
        }
        return Totals(
            energyKcal = NutritionReference.round2(energy),
            proteinG = NutritionReference.round2(protein),
            fatG = NutritionReference.round2(fat),
            carbG = NutritionReference.round2(carb),
            sodiumMg = NutritionReference.round2(sodium),
        )
    }

    private fun below(actual: Double, reference: Double): Boolean =
        reference > 0.0 && actual < reference * GAP_THRESHOLD
}
