package com.ovaphlow.crate.pharmacy

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 054 D1 儿科用量计算引擎（**纯逻辑**：无 DB、无 IO、无 Vert.x）。
 *
 * ## 安全边界（054 §12 R6）
 * 本引擎只做**辅助计算 + 上限提示**：输出带 [PediatricDoseCalculation.capped] 与规则来源
 * [PediatricDoseCalculation.source]。**不写医嘱、不替代药师判断**；计算结果一律由药师复核后使用，
 * 不得直接作为处方或发药依据。
 *
 * ## 计算口径（冻结）
 * 1. **选规则** [selectRule]：只取同 `materialId`、`status = 启用` 的规则；命中具体度
 *    「体重+年龄区间 > 体重区间 > 年龄区间 > 无区间通用」；同具体度取列表首个（调用方应按
 *    规则更新时间/优先级排序）。**区间缺失回落**：有区间规则全部不命中（含输入缺体重/年龄、
 *    规则区间不含该值）时，回落到同药品的**无区间通用规则**，来源标记
 *    [PediatricDoseSource.GENERIC_FALLBACK]；连通用规则也没有 → 返回 `null`（路由层映射 404）。
 * 2. **折算**：体重依赖规则 `单次 = dose_per_kg × weight_kg`；固定剂量规则 `单次 = fixed_dose`。
 * 3. **封顶**：先按 `single_dose_max` 封单次，再按 `daily_dose_max` 封日总量；任一触发即
 *    `capped = true`。日总量被日上限封顶时，单次按 `daily_dose_max ÷ 频次` **向下取整**到 3 位小数
 *    摊回（`RoundingMode.FLOOR`），并重算日总量，保证「单次 × 频次 = 日总量」自洽且**不超上限**。
 *    规则未给频次时按**每日 1 次**处理。
 * 4. **精度**：输出剂量统一 3 位小数 HALF_UP（摊回场景 FLOOR）后去尾零（`187.50 → 187.5`）。
 * 5. **非法输入**：体重 `<= 0`、年龄 `< 0`、NaN / ±Infinity / 非数字、体重依赖规则缺体重、
 *    规则既无 `dose_per_kg` 也无 `fixed_dose`、频次 `<= 0`、剂量/上限非正数、区间上下界倒置
 *    —— 一律抛 [IllegalArgumentException]（中文可读消息，路由层映射 400）。
 *
 * 区间上下界均为**闭区间**（`min <= v <= max`）。
 */
object PediatricDoseCalculator {

    /** 输出剂量保留的小数位（054 D1 冻结口径）。 */
    const val DOSE_SCALE: Int = 3

    /**
     * 选择适用于该儿童（体重/年龄）的规则；无任何可用规则时返回 `null`。
     *
     * @param materialId 药品（`inventories.materials.id`）弱关联 ID，必须非空
     * @param rules      该药品的候选规则集合（本函数只使用 `materialId` 匹配的子集）
     * @param weightKg   体重（kg）；`null` 表示缺失 —— 带体重区间的规则不会命中
     * @param ageMonths  月龄；`null` 表示缺失 —— 带年龄区间的规则不会命中
     */
    fun selectRule(
        materialId: String,
        rules: List<PediatricDoseRule>,
        weightKg: BigDecimal?,
        ageMonths: Int?,
    ): PediatricDoseRule? {
        require(materialId.isNotBlank()) { "material_id is required" }
        if (weightKg != null && weightKg.signum() <= 0) {
            throw IllegalArgumentException("weight_kg must be positive")
        }
        if (ageMonths != null && ageMonths < 0) {
            throw IllegalArgumentException("age_months must not be negative")
        }

        val enabled = rules.filter { it.materialId == materialId && it.status == PediatricDoseRule.STATUS_ENABLED }
        val candidates = enabled.filter {
            withinAgeBand(ageMonths, it.ageMinMonths, it.ageMaxMonths) &&
                withinWeightBand(weightKg, it.weightMinKg, it.weightMaxKg)
        }
        // maxByOrNull 在并列时返回首个元素 —— 同具体度取列表首个（确定性）
        candidates.maxByOrNull { specificity(it) }?.let { return it }

        // 区间缺失回落：无区间通用规则（specificity == 0）
        return enabled.firstOrNull { specificity(it) == 0 }
    }

    /**
     * 按**已选定**的规则折算剂量（调用方自行完成选规则，例如按业务优先级挑规则）。
     *
     * 规则来源按规则自身区间具体度派生：双区间 → [PediatricDoseSource.WEIGHT_AND_AGE_BAND]、
     * 体重区间 → [PediatricDoseSource.WEIGHT_BAND]、年龄区间 → [PediatricDoseSource.AGE_BAND]、
     * 无区间 → [PediatricDoseSource.GENERIC]。
     */
    fun calculate(
        materialId: String,
        weightKg: BigDecimal?,
        ageMonths: Int?,
        rule: PediatricDoseRule,
    ): PediatricDoseCalculation {
        val source = when (specificity(rule)) {
            3 -> PediatricDoseSource.WEIGHT_AND_AGE_BAND
            2 -> PediatricDoseSource.WEIGHT_BAND
            1 -> PediatricDoseSource.AGE_BAND
            else -> PediatricDoseSource.GENERIC
        }
        return compute(materialId, weightKg, ageMonths, rule, source)
    }

    /**
     * 选规则 + 折算的一站式入口；**无任何可用规则时返回 `null`**（不是 0 剂量 —— 0 剂量会被误读）。
     *
     * 若命中无区间通用规则且该药品存在区间规则（即发生了区间缺失回落），来源标记
     * [PediatricDoseSource.GENERIC_FALLBACK]。
     */
    fun calculate(
        materialId: String,
        weightKg: BigDecimal?,
        ageMonths: Int?,
        rules: List<PediatricDoseRule>,
    ): PediatricDoseCalculation? {
        val selected = selectRule(materialId, rules, weightKg, ageMonths) ?: return null
        val hasBanded = rules.any {
            it.materialId == materialId && it.status == PediatricDoseRule.STATUS_ENABLED && specificity(it) > 0
        }
        val source = when {
            specificity(selected) == 3 -> PediatricDoseSource.WEIGHT_AND_AGE_BAND
            specificity(selected) == 2 -> PediatricDoseSource.WEIGHT_BAND
            specificity(selected) == 1 -> PediatricDoseSource.AGE_BAND
            hasBanded -> PediatricDoseSource.GENERIC_FALLBACK
            else -> PediatricDoseSource.GENERIC
        }
        return compute(materialId, weightKg, ageMonths, selected, source)
    }

    /**
     * 解析客户端原始 `weight_kg` 输入（查询参数/JSON 值）为 [BigDecimal]。
     *
     * `null` → `null`（缺失，是否必需由规则决定）；非数字、NaN、±Infinity → [IllegalArgumentException]；
     * `<= 0` → [IllegalArgumentException]。十进制文本直接构造，不经 Double 尾差（见 [decimalText]）。
     */
    fun parseWeightKg(raw: Any?): BigDecimal? {
        if (raw == null) return null
        val parsed = decimalText(raw)
            ?: throw IllegalArgumentException("weight_kg must be a number")
        if (parsed.signum() <= 0) {
            throw IllegalArgumentException("weight_kg must be positive")
        }
        return parsed
    }

    /**
     * 解析客户端原始 `age_months` 输入为整数月龄。
     *
     * `null` → `null`；NaN / ±Infinity / 非整数 / 非数字 / 负数 → [IllegalArgumentException]。
     */
    fun parseAgeMonths(raw: Any?): Int? {
        if (raw == null) return null
        val parsed: Long = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Number -> {
                val d = raw.toDouble()
                if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("age_months must be a number")
                if (d != Math.floor(d)) throw IllegalArgumentException("age_months must be a whole number")
                d.toLong()
            }
            is String -> raw.trim().toLongOrNull()
                ?: throw IllegalArgumentException("age_months must be a whole number")
            else -> throw IllegalArgumentException("age_months must be a number")
        }
        if (parsed < 0) throw IllegalArgumentException("age_months must not be negative")
        if (parsed > Int.MAX_VALUE) throw IllegalArgumentException("age_months is out of range")
        return parsed.toInt()
    }

    // —— 内部实现 ——

    private fun compute(
        materialId: String,
        weightKg: BigDecimal?,
        ageMonths: Int?,
        rule: PediatricDoseRule,
        source: PediatricDoseSource,
    ): PediatricDoseCalculation {
        require(materialId.isNotBlank()) { "material_id is required" }
        require(rule.materialId == materialId) { "rule does not belong to material $materialId" }
        if (weightKg != null && weightKg.signum() <= 0) {
            throw IllegalArgumentException("weight_kg must be positive")
        }
        if (ageMonths != null && ageMonths < 0) {
            throw IllegalArgumentException("age_months must not be negative")
        }
        validateRule(rule)

        val rawSingle: BigDecimal = when {
            rule.dosePerKg != null -> {
                val weight = weightKg
                    ?: throw IllegalArgumentException("weight_kg is required for weight-based pediatric dose rule")
                rule.dosePerKg.multiply(weight)
            }
            rule.fixedDose != null -> rule.fixedDose
            else -> throw IllegalArgumentException("rule has no dose definition (dose_per_kg or fixed_dose)")
        }

        val frequency = rule.dosesPerDay ?: 1
        var capped = false

        var singleDose = roundDose(rawSingle)
        rule.singleDoseMax?.let { max ->
            if (singleDose > max) {
                singleDose = roundDose(max)
                capped = true
            }
        }

        var dailyDose = roundDose(singleDose.multiply(BigDecimal(frequency)))
        rule.dailyDoseMax?.let { max ->
            if (dailyDose > max) {
                capped = true
                // 日上限封顶：单次向下取整摊回，保证 单次 × 频次 <= 日上限
                singleDose = roundDose(max.divide(BigDecimal(frequency), DOSE_SCALE, RoundingMode.FLOOR))
                rule.singleDoseMax?.let { singleMax -> if (singleDose > singleMax) singleDose = roundDose(singleMax) }
                dailyDose = roundDose(singleDose.multiply(BigDecimal(frequency)))
            }
        }

        return PediatricDoseCalculation(
            materialId = materialId,
            weightKg = weightKg,
            ageMonths = ageMonths,
            singleDose = singleDose,
            dailyDose = dailyDose,
            unit = rule.unit,
            capped = capped,
            ruleId = rule.id,
            source = source,
        )
    }

    private fun validateRule(rule: PediatricDoseRule) {
        if (rule.id.isBlank()) throw IllegalArgumentException("rule id is required")
        if (rule.unit.isBlank()) throw IllegalArgumentException("rule unit is required")
        rule.ageMinMonths?.let { min -> rule.ageMaxMonths?.let { max ->
            if (min > max) throw IllegalArgumentException("rule age band is inverted (age_min_months > age_max_months)")
        } }
        rule.weightMinKg?.let { min -> rule.weightMaxKg?.let { max ->
            if (min > max) throw IllegalArgumentException("rule weight band is inverted (weight_min_kg > weight_max_kg)")
        } }
        if (rule.dosePerKg != null && rule.dosePerKg.signum() <= 0) {
            throw IllegalArgumentException("dose_per_kg must be positive")
        }
        if (rule.fixedDose != null && rule.fixedDose.signum() <= 0) {
            throw IllegalArgumentException("fixed_dose must be positive")
        }
        if (rule.singleDoseMax != null && rule.singleDoseMax.signum() <= 0) {
            throw IllegalArgumentException("single_dose_max must be positive")
        }
        if (rule.dailyDoseMax != null && rule.dailyDoseMax.signum() <= 0) {
            throw IllegalArgumentException("daily_dose_max must be positive")
        }
        if (rule.dosesPerDay != null && rule.dosesPerDay <= 0) {
            throw IllegalArgumentException("doses_per_day must be positive")
        }
    }

    /**
     * 3 位小数 HALF_UP 后去尾零；`stripTrailingZeros()` 可能产出负 scale（`150.000 → 1.5E+2`），
     * 统一归一到 `scale >= 0`，保证输出可预测（`150` 而不是科学计数法）。
     */
    private fun roundDose(value: BigDecimal): BigDecimal {
        val stripped = value.setScale(DOSE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    /** 规则具体度：双区间 3 > 体重区间 2 > 年龄区间 1 > 无区间 0。 */
    private fun specificity(rule: PediatricDoseRule): Int {
        val hasWeight = rule.weightMinKg != null || rule.weightMaxKg != null
        val hasAge = rule.ageMinMonths != null || rule.ageMaxMonths != null
        return when {
            hasWeight && hasAge -> 3
            hasWeight -> 2
            hasAge -> 1
            else -> 0
        }
    }

    /** 闭区间判定；无区间约束恒真；输入缺失（null）时带约束的区间不命中。 */
    private fun withinWeightBand(value: BigDecimal?, min: BigDecimal?, max: BigDecimal?): Boolean {
        if (min == null && max == null) return true
        val v = value ?: return false
        if (min != null && v < min) return false
        if (max != null && v > max) return false
        return true
    }

    /** 闭区间判定；无区间约束恒真；输入缺失（null）时带约束的区间不命中。 */
    private fun withinAgeBand(value: Int?, min: Int?, max: Int?): Boolean {
        if (min == null && max == null) return true
        val v = value ?: return false
        if (min != null && v < min) return false
        if (max != null && v > max) return false
        return true
    }
}

/**
 * 儿科用药规则（054 D1/D2 共用形状；D2 由 `pharmacy.pharmacy_pediatric_dose_rules` 装配）。
 *
 * 剂量定义二选一：体重依赖 [dosePerKg]（需体重）或固定剂量 [fixedDose]；两者可同时存在时
 * **优先体重依赖**（[dosePerKg]）。上限为可选提示位：[singleDoseMax] 封单次、[dailyDoseMax] 封日总量。
 *
 * @param status [STATUS_ENABLED] / [STATUS_DISABLED]（054 §5.8 中文枚举）
 */
data class PediatricDoseRule(
    val id: String,
    val materialId: String,
    val materialName: String? = null,
    val ageMinMonths: Int? = null,
    val ageMaxMonths: Int? = null,
    val weightMinKg: BigDecimal? = null,
    val weightMaxKg: BigDecimal? = null,
    val dosePerKg: BigDecimal? = null,
    val fixedDose: BigDecimal? = null,
    val singleDoseMax: BigDecimal? = null,
    val dailyDoseMax: BigDecimal? = null,
    val dosesPerDay: Int? = null,
    val unit: String,
    val status: String = STATUS_ENABLED,
    val note: String? = null,
) {
    companion object {
        const val STATUS_ENABLED = "启用"
        const val STATUS_DISABLED = "停用"
    }
}

/**
 * 儿科用量计算结果（对应 054 §5.7 `PediatricDoseCalculation` 的 7 个字段 + 规则来源诊断位）。
 *
 * @param capped 是否触发过任一上限封顶（单次或日总量）；`true` 表示药师必须人工复核上限
 * @param ruleId 实际命中的规则 ID；无规则不会产出结果（[PediatricDoseCalculator.calculate] 返回 `null`）
 * @param source 规则来源诊断码（[PediatricDoseSource]）；**只读诊断字段**，不属于 054 §5.8 业务枚举
 */
data class PediatricDoseCalculation(
    val materialId: String,
    val weightKg: BigDecimal?,
    val ageMonths: Int?,
    val singleDose: BigDecimal,
    val dailyDose: BigDecimal,
    val unit: String,
    val capped: Boolean,
    val ruleId: String?,
    val source: PediatricDoseSource,
)

/**
 * 规则来源诊断码（054 D1 内部词汇，非业务枚举、不落库、不由客户端提交）。
 *
 * - [WEIGHT_AND_AGE_BAND]：命中「体重 + 年龄」双区间规则
 * - [WEIGHT_BAND]：命中「体重」单区间规则
 * - [AGE_BAND]：命中「年龄」单区间规则
 * - [GENERIC]：直接命中无区间通用规则（该药品没有区间规则）
 * - [GENERIC_FALLBACK]：**区间缺失回落** —— 有区间规则但均不命中，回落到无区间通用规则
 */
enum class PediatricDoseSource {
    WEIGHT_AND_AGE_BAND,
    WEIGHT_BAND,
    AGE_BAND,
    GENERIC,
    GENERIC_FALLBACK,
}
