package com.ovaphlow.crate.healthcare

/**
 * 052 D5 / P4：儿童生命体征阈值（年龄段分档）。
 *
 * 背景：`vital_sign_records` 的参考范围是**成人固定常量**（`VitalSignService.referenceRanges`：
 * 脉搏 60–100、呼吸 12–20），儿童（尤其婴幼儿）生理范围显著更宽，直接套用会把正常值
 * 判成异常、把真实异常漏掉。
 *
 * 052 §7 明确「不改 `vital_sign_records` 的成人全局阈值常量」——本常量表**只**用于
 * 儿保读端点（`GET /child-health-alerts`）的儿童异常识别；成人写入路径的判定完全不变。
 *
 * 分档与取值采用儿科常规参考范围（与 WHO/儿科教科书口径一致）：
 *
 * | 档位     | 天龄        | 脉搏（次/分） | 呼吸（次/分） |
 * |----------|-------------|---------------|---------------|
 * | 新生儿   | 0–27 天     | 100–160       | 30–60         |
 * | 婴儿     | 28 天–11 月 | 90–150        | 25–45         |
 * | 幼儿     | 12–35 月    | 80–130        | 20–35         |
 * | 学龄前   | 36–71 月    | 75–120        | 20–30         |
 * | 学龄及以 | ≥72 月      | 60–100（成人）| 12–20（成人） |
 *
 * 体温全档一致（36.0–37.5 ℃）：儿童发热阈值与成人同源，略宽于成人的 37.3 ℃。
 */
internal object ChildVitalThresholds {

    /** 年龄段分档。 */
    enum class Band(val label: String) {
        NEONATE("新生儿"),
        INFANT("婴儿"),
        TODDLER("幼儿"),
        PRESCHOOL("学龄前"),
        OLDER("学龄及以上"),
    }

    /** 全档一致的儿童体温范围（℃）。 */
    val temperature: Pair<Double, Double> = 36.0 to 37.5

    private val pulseRanges = mapOf(
        Band.NEONATE to (100.0 to 160.0),
        Band.INFANT to (90.0 to 150.0),
        Band.TODDLER to (80.0 to 130.0),
        Band.PRESCHOOL to (75.0 to 120.0),
        Band.OLDER to (60.0 to 100.0),
    )

    private val respirationRanges = mapOf(
        Band.NEONATE to (30.0 to 60.0),
        Band.INFANT to (25.0 to 45.0),
        Band.TODDLER to (20.0 to 35.0),
        Band.PRESCHOOL to (20.0 to 30.0),
        Band.OLDER to (12.0 to 20.0),
    )

    /** 天龄 → 分档（负数按新生儿处理，不抛错：脏数据不得让读端点 500）。 */
    fun bandFor(ageDays: Long): Band = when {
        ageDays < 28 -> Band.NEONATE
        ageDays < 365 -> Band.INFANT
        ageDays < 365 * 3 -> Band.TODDLER
        ageDays < 365 * 6 -> Band.PRESCHOOL
        else -> Band.OLDER
    }

    /** 分档 + 体征类型 → 参考范围；类型无儿童范围（血压/血氧/血糖/体重）时返回 null（不判异常）。 */
    fun rangeOf(type: String, ageDays: Long): Pair<Double, Double>? {
        val band = bandFor(ageDays)
        return when (type) {
            "TEMPERATURE" -> temperature
            "PULSE" -> pulseRanges.getValue(band)
            "RESPIRATION" -> respirationRanges.getValue(band)
            else -> null
        }
    }

    /**
     * 异常判定（含边界：`value < min || value > max`）。无儿童范围时恒 false
     * （与成人 `WEIGHT` 恒正常的口径一致）。
     */
    fun isAbnormal(type: String, value: Double, ageDays: Long): Boolean {
        val range = rangeOf(type, ageDays) ?: return false
        return value < range.first || value > range.second
    }

    /** 参考范围的中文可读文本（通报与告警卡片展示用）。 */
    fun describe(type: String, ageDays: Long): String? {
        val range = rangeOf(type, ageDays) ?: return null
        val unit = when (type) {
            "TEMPERATURE" -> "℃"
            "PULSE", "RESPIRATION" -> "次/分"
            else -> ""
        }
        return "${bandFor(ageDays).label}参考范围 ${format(range.first)}–${format(range.second)} $unit".trim()
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}
