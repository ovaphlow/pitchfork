package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 账单计费引擎纯逻辑单元测试（不访问数据库）。
 * 覆盖验收口径：
 *   - 整月/部分月/闭区间天数折算（入住日与离院日均计费）
 *   - 护理等级按 assess_date 分段（同日多份取 created_at 最新）
 *   - 伙食费按执行状态计费（正常全额/部分半价/未就餐拒食 0）
 *   - 明细金额 ROUND_HALF_UP 到分
 *   - 合计 = 明细之和
 *   - 结算区间（离院/去世收束）：起 = MAX(已结算账期末日)+1（无则入住日）、
 *     止 = 收束日闭区间；起 > 止不生成区间账单
 */
class BillingEngineTest {

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    private fun at(value: String): OffsetDateTime = OffsetDateTime.parse(value)

    private fun assessment(day: String, time: String, level: String): BillingEngine.Assessment =
        BillingEngine.Assessment(date("2026-$day"), at("2026-${day}T$time:00+08:00"), level)

    // ——— 0. 结算区间（离院/去世收束）———

    @Test
    fun `结算区间无已结算账单时起于入住日止于收束日`() {
        val interval = BillingEngine.settlementInterval(
            admitDate = date("2026-08-01"),
            endDate = date("2026-09-20"),
            settledPeriodEnds = emptyList(),
        )
        assertEquals(date("2026-08-01"), interval?.first, "无已结算账期末日时区间起 = 入住日")
        assertEquals(date("2026-09-20"), interval?.second, "区间止 = 离院/去世日")
        assertEquals(51, BillingEngine.inclusiveDays(interval!!.first, interval.second), "闭区间 08-01..09-20 = 51 天")
    }

    @Test
    fun `结算区间起于已结算账期末日加一天`() {
        val interval = BillingEngine.settlementInterval(
            admitDate = date("2026-08-01"),
            endDate = date("2026-09-20"),
            settledPeriodEnds = listOf(date("2026-08-31")),
        )
        assertEquals(date("2026-09-01"), interval?.first, "区间起 = MAX(已结算 period_end)+1")
        assertEquals(20, BillingEngine.inclusiveDays(interval!!.first, interval.second), "闭区间 09-01..09-20 = 20 天")
    }

    @Test
    fun `结算区间取多个已结算账期末日的最大值`() {
        val interval = BillingEngine.settlementInterval(
            admitDate = date("2026-08-01"),
            endDate = date("2026-09-30"),
            settledPeriodEnds = listOf(date("2026-08-31"), date("2026-07-31"), date("2026-08-15")),
        )
        assertEquals(date("2026-09-01"), interval?.first, "取 MAX 而非首个")
    }

    @Test
    fun `已结算账期覆盖到收束日之后时区间为null不生成账单`() {
        // 已结清账单账期 08-01..08-31，收束日 08-20：区间起 09-01 > 08-20 → 不生成区间账单
        val interval = BillingEngine.settlementInterval(
            admitDate = date("2026-08-01"),
            endDate = date("2026-08-20"),
            settledPeriodEnds = listOf(date("2026-08-31")),
        )
        assertTrue(interval == null, "区间起 > 区间止时必须返回 null，不得生成区间账单")

        // 已结算账期末日恰好等于收束日：区间起 = 收束日+1 > 收束日 → 同样不生成
        val covered = BillingEngine.settlementInterval(
            admitDate = date("2026-08-01"),
            endDate = date("2026-08-31"),
            settledPeriodEnds = listOf(date("2026-08-31")),
        )
        assertTrue(covered == null)
    }

    @Test
    fun `收束日等于入住日时区间为单日闭区间`() {
        val interval = BillingEngine.settlementInterval(
            admitDate = date("2026-08-01"),
            endDate = date("2026-08-01"),
            settledPeriodEnds = emptyList(),
        )
        assertEquals(date("2026-08-01"), interval?.first)
        assertEquals(date("2026-08-01"), interval?.second)
        assertEquals(1, BillingEngine.inclusiveDays(interval!!.first, interval.second), "收束日当天入住/离院仍计 1 天")
    }

    // ——— 1. 闭区间天数折算：入住日与离院日均计费 ———

    @Test
    fun `整月折算为当月天数`() {
        // 2026-08 满月 31 天；无离院日时整月计费
        assertEquals(31L, BillingEngine.inclusiveDays(date("2026-08-01"), date("2026-08-31")))
        assertEquals(30L, BillingEngine.inclusiveDays(date("2026-09-01"), date("2026-09-30")))
        assertEquals(28L, BillingEngine.inclusiveDays(date("2026-02-01"), date("2026-02-28")))
    }

    @Test
    fun `部分月按在院区间裁剪且闭区间计费`() {
        // 月中入住：08-10 入住 08-20 离院 → 11 天（入住日与离院日均计费）
        assertEquals(11L, BillingEngine.inclusiveDays(date("2026-08-10"), date("2026-08-20")))
        // 月末最后一天入住 → 1 天
        assertEquals(1L, BillingEngine.inclusiveDays(date("2026-08-31"), date("2026-08-31")))
        // 跨月边界：08-31 ～ 09-01 → 2 天
        assertEquals(2L, BillingEngine.inclusiveDays(date("2026-08-31"), date("2026-09-01")))
    }

    @Test
    fun `起止日非法时拒绝计算`() {
        assertThrows(IllegalArgumentException::class.java) {
            BillingEngine.inclusiveDays(date("2026-08-20"), date("2026-08-10"))
        }
    }

    // ——— 2. 明细金额 ROUND_HALF_UP 到分 ———

    @Test
    fun `明细金额为单价乘数量且四舍五入到分`() {
        assertEquals(0, BigDecimal("3100.00").compareTo(BillingEngine.money(BigDecimal("100"), BigDecimal("31"))))
        assertEquals(0, BigDecimal("2480.00").compareTo(BillingEngine.money(BigDecimal("80"), BigDecimal("31"))))
        assertEquals(0, BigDecimal("75.00").compareTo(BillingEngine.money(BigDecimal("30"), BigDecimal("2.5"))))
        // 半分进位：0.005 → 0.01（HALF_UP）
        assertEquals(0, BigDecimal("0.01").compareTo(BillingEngine.money(BigDecimal("0.005"), BigDecimal.ONE)))
        // 0.055 → 0.06
        assertEquals(0, BigDecimal("0.06").compareTo(BillingEngine.money(BigDecimal("0.1"), BigDecimal("0.55"))))
        // 精确到分无精度损失
        assertEquals(0, BigDecimal("999.99").compareTo(BillingEngine.money(BigDecimal("333.33"), BigDecimal("3"))))
        assertEquals(0, BigDecimal("0.30").compareTo(BillingEngine.money(BigDecimal("0.10"), BigDecimal("3"))))
    }

    @Test
    fun `合计为明细之和`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(BillingEngine.totalOf(emptyList())))
        assertEquals(
            0,
            BigDecimal("350.50").compareTo(
                BillingEngine.totalOf(
                    listOf(BigDecimal("100.00"), BigDecimal("200.00"), BigDecimal("50.50")),
                ),
            ),
        )
        // 明细先各自四舍五入再求和（0.105→0.11 而非 0.10）
        assertEquals(
            0,
            BigDecimal("0.11").compareTo(
                BillingEngine.totalOf(listOf(BillingEngine.money(BigDecimal("0.105"), BigDecimal.ONE))),
            ),
        )
    }

    // ——— 3. 伙食费按执行状态计费：正常全额/部分半价/未就餐拒食 0 ———

    @Test
    fun `伙食执行状态折合餐次`() {
        assertEquals(0, BigDecimal("2.5").compareTo(BillingEngine.mealQuantity(listOf("正常", "正常", "部分"))))
        assertEquals(0, BigDecimal("1.5").compareTo(BillingEngine.mealQuantity(listOf("正常", "部分", "未就餐", "拒食"))))
        assertEquals(0, BigDecimal.ZERO.compareTo(BillingEngine.mealQuantity(listOf("未就餐", "拒食"))))
        assertEquals(0, BigDecimal.ZERO.compareTo(BillingEngine.mealQuantity(emptyList())))
        assertEquals(0, BigDecimal("1.0").compareTo(BillingEngine.mealQuantity(listOf("正常"))))
    }

    // ——— 4. 护理等级分段：生效日 = assess_date，同日多份取最新 created_at ———

    @Test
    fun `账期内无新评估时整段沿用账期前最后一次评估等级`() {
        val segments = BillingEngine.nursingSegments(
            date("2026-08-01"),
            date("2026-08-31"),
            listOf(assessment("07-20", "09:00", "轻度依赖")),
        )
        assertEquals(1, segments.size)
        val segment = segments.single()
        assertEquals("轻度依赖", segment.level)
        assertEquals(date("2026-08-01"), segment.start)
        assertEquals(date("2026-08-31"), segment.end)
        assertEquals(31L, segment.days)
    }

    @Test
    fun `账期内无评估且账期前无评估则不计护理费`() {
        assertTrue(BillingEngine.nursingSegments(date("2026-08-01"), date("2026-08-31"), emptyList()).isEmpty())
        assertTrue(
            BillingEngine.nursingSegments(
                date("2026-08-01"),
                date("2026-08-31"),
                listOf(assessment("09-05", "09:00", "中度依赖")), // 账期后评估不影响本期
            ).isEmpty(),
        )
    }

    @Test
    fun `等级变更按生效日切分区间且天数闭合到整月`() {
        val segments = BillingEngine.nursingSegments(
            date("2026-08-01"),
            date("2026-08-31"),
            listOf(
                assessment("07-20", "09:00", "轻度依赖"),
                assessment("08-10", "09:00", "中度依赖"),
                assessment("08-20", "09:00", "重度依赖"),
            ),
        )
        assertEquals(
            listOf("轻度依赖", "中度依赖", "重度依赖"),
            segments.map { it.level },
        )
        assertEquals(listOf(9L, 10L, 12L), segments.map { it.days })
        assertEquals(date("2026-08-01"), segments[0].start)
        assertEquals(date("2026-08-09"), segments[0].end)
        assertEquals(date("2026-08-10"), segments[1].start)
        assertEquals(date("2026-08-19"), segments[1].end)
        assertEquals(date("2026-08-20"), segments[2].start)
        assertEquals(date("2026-08-31"), segments[2].end)
        assertEquals(31L, segments.sumOf { it.days }, "区间天数必须闭合到整月")
    }

    @Test
    fun `同日多份评估取created_at最新一份`() {
        val segments = BillingEngine.nursingSegments(
            date("2026-08-01"),
            date("2026-08-31"),
            listOf(
                assessment("07-20", "09:00", "轻度依赖"),
                BillingEngine.Assessment(date("2026-08-10"), at("2026-08-10T09:00:00+08:00"), "中度依赖"),
                BillingEngine.Assessment(date("2026-08-10"), at("2026-08-10T15:30:00+08:00"), "重度依赖"),
            ),
        )
        assertEquals(2, segments.size)
        // 08-10 同日两份：取 15:30 的重度依赖
        assertEquals("轻度依赖", segments[0].level)
        assertEquals("重度依赖", segments[1].level)
        assertEquals(date("2026-08-10"), segments[1].start)
        assertEquals(22L, segments[1].days)
    }

    @Test
    fun `生效日等于账期起时不产生首段`() {
        val segments = BillingEngine.nursingSegments(
            date("2026-08-01"),
            date("2026-08-31"),
            listOf(assessment("08-01", "09:00", "中度依赖")),
        )
        assertEquals(1, segments.size)
        assertEquals("中度依赖", segments.single().level)
        assertEquals(date("2026-08-01"), segments.single().start)
        assertEquals(31L, segments.single().days)
    }

    @Test
    fun `无账期前评估时变更前的日期不计护理费`() {
        val segments = BillingEngine.nursingSegments(
            date("2026-08-01"),
            date("2026-08-31"),
            listOf(assessment("08-10", "09:00", "中度依赖")),
        )
        assertEquals(1, segments.size)
        assertEquals("中度依赖", segments.single().level)
        assertEquals(date("2026-08-10"), segments.single().start)
        assertEquals(22L, segments.single().days)
    }

    @Test
    fun `相邻变更日各自成段且尾段到账期止`() {
        val segments = BillingEngine.nursingSegments(
            date("2026-08-01"),
            date("2026-08-31"),
            listOf(
                assessment("08-10", "09:00", "中度依赖"),
                assessment("08-11", "09:00", "重度依赖"),
            ),
        )
        assertEquals(2, segments.size)
        assertEquals("中度依赖", segments[0].level)
        assertEquals(1L, segments[0].days)
        assertEquals(date("2026-08-10"), segments[0].start)
        assertEquals(date("2026-08-10"), segments[0].end)
        assertEquals("重度依赖", segments[1].level)
        assertEquals(21L, segments[1].days)
    }
}
