package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 052 P3：预防接种排程与读时状态判定测试（**不访问数据库**）。
 *
 * 锁定验收口径 §9.3：`vaccinationStatus` 对「未到龄 / 已接种 / 临期 / 逾期 / 暂缓 / 拒种」
 * 判定正确，且排程只含国家免疫规划疫苗、剂次连续（052 裁定 3）。
 */
class ChildVaccinationScheduleTest {

    private val birth = LocalDate.of(2025, 1, 15)

    @Test
    fun `排程只含免疫规划疫苗且剂次连续`() {
        val byCode = ChildVaccinationService.VACCINATION_SCHEDULE.groupBy { it.code }
        byCode.forEach { (code, entries) ->
            val doses = entries.map { it.doseNo }.sorted()
            assertEquals(
                (1..doses.size).toList(),
                doses,
                "$code 的剂次必须从 1 起连续：$doses",
            )
            assertEquals(
                entries.size,
                ChildVaccinationService.maxDose(code),
                "$code 的最大剂次必须等于排程剂次数",
            )
            assertTrue(entries.all { it.recommendedMonth in 0..60 }, "$code 必须落在 0–60 月 MVP 区间")
        }
        // 唯一 (code, dose)
        val keys = ChildVaccinationService.VACCINATION_SCHEDULE.map { it.code to it.doseNo }
        assertEquals(keys.size, keys.toSet().size, "排程 (code, dose) 必须唯一")
    }

    @Test
    fun `排程含 0-5 岁的核心疫苗`() {
        val codes = ChildVaccinationService.VACCINATION_SCHEDULE.map { it.code }.toSet()
        assertEquals(
            setOf("HEPB", "BCG", "POLIO", "DTAP", "MENINGOCOCCAL_A", "MMR", "JE", "HEPA", "MENINGOCOCCAL_AC"),
            codes,
        )
        // 6 岁及以上的剂次不列入 0–5 岁 MVP
        assertTrue(ChildVaccinationService.VACCINATION_SCHEDULE.none { it.code == "DT" }, "白破（6 岁）不列入 0–5 岁 MVP")
    }

    @Test
    fun `推荐接种日按日历月推进`() {
        val hepb2 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "HEPB" && it.doseNo == 2 }
        assertEquals(LocalDate.of(2025, 2, 15), ChildVaccinationService.dueDate(birth, hepb2))
        val dtap4 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "DTAP" && it.doseNo == 4 }
        assertEquals(LocalDate.of(2026, 7, 15), ChildVaccinationService.dueDate(birth, dtap4))
    }

    @Test
    fun `未到龄远期为待接种`() {
        val dtap1 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "DTAP" && it.doseNo == 1 }
        // 出生当天：DTAP 第 1 剂推荐 3 月（4-15），距今 > 30 天
        assertEquals("待接种", ChildVaccinationService.statusOf(dtap1, null, birth, LocalDate.of(2025, 1, 15)))
    }

    @Test
    fun `到龄前 30 天内为临期`() {
        val dtap1 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "DTAP" && it.doseNo == 1 }
        val due = LocalDate.of(2025, 4, 15)
        assertEquals("临期", ChildVaccinationService.statusOf(dtap1, null, birth, due.minusDays(30)))
        assertEquals("待接种", ChildVaccinationService.statusOf(dtap1, null, birth, due.minusDays(31)))
    }

    @Test
    fun `到龄当天与宽限期内为待接种`() {
        val dtap1 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "DTAP" && it.doseNo == 1 }
        val due = LocalDate.of(2025, 4, 15)
        assertEquals("待接种", ChildVaccinationService.statusOf(dtap1, null, birth, due))
        assertEquals("待接种", ChildVaccinationService.statusOf(dtap1, null, birth, due.plusDays(30)))
    }

    @Test
    fun `超过宽限期为逾期`() {
        val dtap1 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "DTAP" && it.doseNo == 1 }
        val due = LocalDate.of(2025, 4, 15)
        assertEquals("逾期", ChildVaccinationService.statusOf(dtap1, null, birth, due.plusDays(31)))
    }

    @Test
    fun `记录状态优先于排程推算`() {
        val dtap1 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "DTAP" && it.doseNo == 1 }
        val late = LocalDate.of(2026, 1, 1)
        assertEquals("已接种", ChildVaccinationService.statusOf(dtap1, "已接种", birth, late))
        assertEquals("暂缓", ChildVaccinationService.statusOf(dtap1, "暂缓", birth, late))
        assertEquals("拒种", ChildVaccinationService.statusOf(dtap1, "拒种", birth, late))
        // 非法记录状态不参与判定（回落排程推算），不静默当已接种
        assertEquals("逾期", ChildVaccinationService.statusOf(dtap1, "unknown", birth, late))
    }

    @Test
    fun `月龄小于推荐月龄的排程条目为未到龄`() {
        // 新生儿：乙肝第 1 剂推荐 0 月 → 到龄当天
        val hepb1 = ChildVaccinationService.VACCINATION_SCHEDULE.first { it.code == "HEPB" && it.doseNo == 1 }
        assertEquals("待接种", ChildVaccinationService.statusOf(hepb1, null, birth, birth))
        assertNotNull(ChildVaccinationService.maxDose("HEPB"))
        assertNull(ChildVaccinationService.maxDose("NOT_A_VACCINE"))
    }
}
