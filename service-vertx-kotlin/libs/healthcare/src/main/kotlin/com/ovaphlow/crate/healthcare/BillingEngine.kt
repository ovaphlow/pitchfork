package com.ovaphlow.crate.healthcare

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * 账单计费引擎（纯逻辑，不访问数据库）。
 *
 * 按月账单自动计费口径（与 V515 契约一致）：
 *  1. 账期 = 自然月，首/尾月按实际在院日裁剪；
 *     在院天数 = 闭区间（入住日与离院日均计费）。
 *  2. 床位费 = 床位单价 × 账期内在院天数。
 *  3. 护理费 = 按 nursing_assessments.result_level（中文值）分段计费：
 *     评估生效日 = assess_date，同日多份取 created_at 最新；
 *     等级变更按生效日切分区间，每区间 = 对应等级字典单价 × 天数。
 *     区间天数由账期前最后一次评估等级兜底（无评估覆盖的天数不计费）。
 *  4. 伙食费 = 账期内就餐执行折合餐次 × 伙食单价：
 *     正常=全额(1)、部分=半价(0.5)、未就餐/拒食=0。
 *  5. 明细金额 = 单价 × 数量，ROUND_HALF_UP 到分；合计 = 明细之和。
 */
object BillingEngine {
    /** 明细来源 */
    const val SOURCE_AUTO = "自动"
    const val SOURCE_MANUAL = "手工"

    /** 账单状态（初始 待缴费；流转由缴费/结算子任务处理） */
    const val STATUS_PENDING = "待缴费"
    const val STATUS_PAID = "已结清"
    const val STATUS_SETTLED = "已结算"

    /** 就餐执行状态 → 餐次折算：正常全额、部分半价、未就餐/拒食 0 */
    val mealRates = mapOf(
        "正常" to BigDecimal("1.0"),
        "部分" to BigDecimal("0.5"),
        "未就餐" to BigDecimal.ZERO,
        "拒食" to BigDecimal.ZERO,
    )

    /** 闭区间天数：起止日均计费（end 早于 start 时抛 IllegalArgumentException）。 */
    fun inclusiveDays(start: LocalDate, end: LocalDate): Long {
        require(!end.isBefore(start)) { "period end must not be before period start" }
        return ChronoUnit.DAYS.between(start, end) + 1
    }

    /**
     * 结算区间（离院/去世结算收束的区间最终账单账期）：
     *  - 区间起 = MAX(已结算账期末日)+1；无已结算账单（已结清/已结算）时取入住日；
     *  - 区间止 = 离院/去世日（闭区间，收束日计费）；
     *  - 区间起 > 区间止（已结算账单账期已覆盖到收束日之后）时返回 null，不生成区间账单。
     */
    fun settlementInterval(
        admitDate: LocalDate,
        endDate: LocalDate,
        settledPeriodEnds: List<LocalDate>,
    ): Pair<LocalDate, LocalDate>? {
        val start = settledPeriodEnds.maxOrNull()?.plusDays(1) ?: admitDate
        return if (start.isAfter(endDate)) null else start to endDate
    }

    /** 明细金额 = 单价 × 数量，ROUND_HALF_UP 到分。 */
    fun money(unitPrice: BigDecimal, quantity: BigDecimal): BigDecimal =
        unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP)

    /** 合计 = 明细之和（各明细金额已四舍五入到分）。 */
    fun totalOf(amounts: List<BigDecimal>): BigDecimal =
        amounts.fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }

    /** 伙食折合餐次：正常=1、部分=0.5、未就餐/拒食=0；未知状态按 0 计。 */
    fun mealQuantity(statuses: List<String>): BigDecimal =
        statuses.fold(BigDecimal.ZERO) { acc, status -> acc.add(mealRates[status] ?: BigDecimal.ZERO) }

    /** 护理评估（计费引擎输入）：生效日 = assess_date，同日多份取 created_at 最新。 */
    data class Assessment(val date: LocalDate, val createdAt: OffsetDateTime, val level: String)

    /** 护理计费区间：等级、起止日与闭区间天数。 */
    data class Segment(val level: String, val start: LocalDate, val end: LocalDate, val days: Long)

    /**
     * 护理等级分段：
     *  - 同日多份评估只保留 created_at 最新一份；
     *  - 账期内每次评估为一次等级变更点，区间 = [变更日, 下次变更日前一日]，尾段到账期止；
     *  - 账期首段（账期起 ～ 首次变更前一日）沿用账期前最后一次评估的等级；
     *  - 无评估覆盖的日期不产生区间（不计费）。
     */
    fun nursingSegments(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        assessments: List<Assessment>,
    ): List<Segment> {
        require(!periodEnd.isBefore(periodStart)) { "period end must not be before period start" }
        if (assessments.isEmpty()) return emptyList()

        // 同日多份取 created_at 最新，再按生效日升序
        val byDate = assessments
            .groupBy { it.date }
            .map { (date, sameDay) -> date to sameDay.maxBy { it.createdAt } }
            .sortedBy { it.first }

        // 账期前最后一次评估：决定账期首段等级（若无则首段不计费）
        val initial = byDate.lastOrNull { it.first.isBefore(periodStart) }?.second
        // 账期内变更点：生效日落在账期内
        val changes = byDate.filter { (date, _) -> !date.isBefore(periodStart) && !date.isAfter(periodEnd) }

        if (changes.isEmpty()) {
            // 账期内无新评估：整段沿用账期前最后一次评估等级
            return if (initial == null) emptyList()
            else listOf(Segment(initial.level, periodStart, periodEnd, inclusiveDays(periodStart, periodEnd)))
        }

        val segments = mutableListOf<Segment>()
        val firstChangeDate = changes.first().first
        if (initial != null && firstChangeDate.isAfter(periodStart)) {
            val end = firstChangeDate.minusDays(1)
            segments += Segment(initial.level, periodStart, end, inclusiveDays(periodStart, end))
        }
        for (index in changes.indices) {
            val (date, assessment) = changes[index]
            val end = if (index + 1 < changes.size) changes[index + 1].first.minusDays(1) else periodEnd
            if (end.isBefore(date)) continue // 相邻变更日无区间（防御）
            segments += Segment(assessment.level, date, end, inclusiveDays(date, end))
        }
        return segments
    }
}
