package com.ovaphlow.crate.nursing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * 054 B3：里程碑常量表与**读时四态派生**纯函数单测（不访库）。
 *
 * 四态（§5.8 冻结）：`未达 / 临近 / 已达成 / 逾期`；「临近」窗口 = 1 个月。
 */
class ChildRehabMilestoneTest {

    // ========================================================================
    //  常量表
    // ========================================================================

    @Test
    fun `四态白名单为冻结值`() {
        assertEquals(setOf("未达", "临近", "已达成", "逾期"), ChildRehabMilestone.STATES)
        assertEquals(setOf("未达成", "已达成"), ChildRehabMilestone.PERSISTED_STATUSES)
        assertEquals(1, ChildRehabMilestone.NEAR_WINDOW_MONTHS)
    }

    @Test
    fun `常量表按期望月龄升序且code唯一`() {
        val catalog = ChildRehabMilestone.CATALOG
        assertEquals(11, catalog.size, "常量表规模变化需同步本断言与文档")
        assertEquals(catalog.sortedWith(compareBy({ it.expectedAgeMonths }, { it.code })), catalog)
        assertEquals(catalog.size, catalog.map { it.code }.distinct().size, "code 必须唯一")
        assertTrue(catalog.all { it.name.isNotBlank() && it.expectedAgeMonths > 0 })
        assertEquals("HEAD_CONTROL", catalog.first().code, "最小期望月龄的里程碑")
        assertEquals("RUN_JUMP", catalog.last().code, "最大期望月龄的里程碑")
    }

    @Test
    fun `按code取定义支持trim且未知返回null`() {
        assertEquals("抬头控制", ChildRehabMilestone.definitionOf("HEAD_CONTROL")?.name)
        assertEquals(24, ChildRehabMilestone.definitionOf(" STAIRS_UP_DOWN ")?.expectedAgeMonths)
        assertNull(ChildRehabMilestone.definitionOf("NOT_A_MILESTONE"))
    }

    // ========================================================================
    //  四态派生
    // ========================================================================

    @Test
    fun `已达成优先于月龄判定`() {
        assertEquals("已达成", ChildRehabMilestone.stateOf(4, 24, achieved = true), "逾期月龄但已达成 → 已达成")
        assertEquals("已达成", ChildRehabMilestone.stateOf(null, 0, achieved = true), "无期望月龄但已达成 → 已达成")
    }

    @Test
    fun `超过期望月龄为逾期`() {
        assertEquals("逾期", ChildRehabMilestone.stateOf(6, 6, achieved = false), "恰好到达期望月龄即逾期")
        assertEquals("逾期", ChildRehabMilestone.stateOf(6, 7, achieved = false))
        assertEquals("逾期", ChildRehabMilestone.stateOf(4, 24, achieved = false))
    }

    @Test
    fun `临近窗口为期望月龄前一个月`() {
        assertEquals("临近", ChildRehabMilestone.stateOf(6, 5, achieved = false), "期望 6、当前 5 → 临近")
        assertEquals("未达", ChildRehabMilestone.stateOf(6, 4, achieved = false), "差 2 个月 → 未达")
        assertEquals("临近", ChildRehabMilestone.stateOf(24, 23, achieved = false))
        assertEquals("未达", ChildRehabMilestone.stateOf(24, 22, achieved = false))
    }

    @Test
    fun `远未到期望月龄为未达`() {
        assertEquals("未达", ChildRehabMilestone.stateOf(36, 0, achieved = false))
        assertEquals("未达", ChildRehabMilestone.stateOf(36, 12, achieved = false))
    }

    @Test
    fun `无期望月龄时只能未达或已达成`() {
        assertEquals("未达", ChildRehabMilestone.stateOf(null, 0, achieved = false))
        assertEquals("未达", ChildRehabMilestone.stateOf(null, 60, achieved = false))
    }

    @Test
    fun `负月龄按0处理`() {
        assertEquals("未达", ChildRehabMilestone.stateOf(6, -3, achieved = false))
    }

    // ========================================================================
    //  月龄（与 healthcare 同口径）
    // ========================================================================

    @Test
    fun `月龄按日历差计算`() {
        assertEquals(24, ChildRehabMilestone.ageMonths(LocalDate.parse("2024-10-06"), LocalDate.parse("2026-10-06")))
        assertEquals(23, ChildRehabMilestone.ageMonths(LocalDate.parse("2024-10-06"), LocalDate.parse("2026-10-05")), "未到当月同日减 1")
        assertEquals(0, ChildRehabMilestone.ageMonths(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-06")))
    }

    @Test
    fun `查询日早于出生日报错`() {
        val error = assertThrows<IllegalArgumentException> {
            ChildRehabMilestone.ageMonths(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-05"))
        }
        assertTrue(error.message!!.contains("birth_date"), error.message)
    }

    // ========================================================================
    //  服务层同步校验（不触库）
    // ========================================================================

    @Test
    fun `状态与登记入参非法时同步报错`() {
        val service = ChildRehabMilestoneService(io.mockk.mockk<io.vertx.sqlclient.Pool>(relaxed = true))
        assertTrue(
            assertThrows<IllegalArgumentException> { service.status(null) }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { service.status("  ") }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(io.vertx.core.json.JsonObject().put("milestone_code", "HEAD_CONTROL"), "rehab01")
            }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(io.vertx.core.json.JsonObject().put("patient_id", "p1"), "rehab01")
            }.message!!.contains("milestone_code"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    io.vertx.core.json.JsonObject().put("patient_id", "p1").put("milestone_code", "NOPE"),
                    "rehab01",
                )
            }.message!!.contains("unknown milestone_code"),
        )
    }
}
