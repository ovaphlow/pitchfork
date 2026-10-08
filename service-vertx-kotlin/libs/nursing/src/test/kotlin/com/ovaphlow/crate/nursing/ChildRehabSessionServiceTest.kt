package com.ovaphlow.crate.nursing

import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * 054 B2：训练执行**进度汇总纯函数**与入参校验的单测（不访库）。
 *
 * 进度口径：`progressRatio = 完成天数 ÷ 计划天数`（四位小数，计划缺失为 0，**不封顶**）。
 */
class ChildRehabSessionServiceTest {

    /** 校验路径不触库：入参非法必须在访问 `pool` 之前同步抛出。 */
    private val service = ChildRehabSessionService(mockk<Pool>(relaxed = true))

    private fun point(date: String, ratio: Double) =
        ChildRehabSessionService.SessionPoint(LocalDate.parse(date), ratio)

    // ========================================================================
    //  计划天数
    // ========================================================================

    @Test
    fun `计划天数按含端点计算`() {
        assertEquals(1, ChildRehabSessionService.plannedDaysOf(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-06")) ?: -1)
        assertEquals(7, ChildRehabSessionService.plannedDaysOf(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-11")) ?: -1)
        assertNull(ChildRehabSessionService.plannedDaysOf(LocalDate.parse("2026-10-05"), null))
        assertNull(ChildRehabSessionService.plannedDaysOf(null, LocalDate.parse("2026-10-11")))
        assertNull(
            ChildRehabSessionService.plannedDaysOf(LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-05")),
            "结束早于开始视为无有效计划窗口",
        )
    }

    // ========================================================================
    //  进度汇总
    // ========================================================================

    @Test
    fun `空会话汇总为零且不报错`() {
        val summary = ChildRehabSessionService.summarize(emptyList(), plannedDays = 10)
        assertEquals(0, summary.sessionCount)
        assertEquals(0, summary.completedDays)
        assertEquals(10, summary.plannedDays ?: -1)
        assertEquals(0.0, summary.avgCompletionRatio, 0.001)
        assertEquals(0.0, summary.progressRatio, 0.001)
        assertNull(summary.lastSessionDate)
    }

    @Test
    fun `完成天数去重且进度比等于完成天数除以计划天数`() {
        val summary = ChildRehabSessionService.summarize(
            listOf(
                point("2026-10-06", 1.0),
                point("2026-10-06", 0.5),
                point("2026-10-08", 0.8),
            ),
            plannedDays = 4,
        )
        assertEquals(3, summary.sessionCount, "同一天两次训练算两条会话")
        assertEquals(2, summary.completedDays, "完成天数按日期去重")
        assertEquals(4, summary.plannedDays ?: -1)
        assertEquals(0.77, summary.avgCompletionRatio, 0.001, "(1.0+0.5+0.8)/3 两位小数")
        assertEquals(0.5, summary.progressRatio, 0.0001, "2/4")
        assertEquals("2026-10-08", summary.lastSessionDate)
    }

    @Test
    fun `计划窗口缺失或为零时进度比为0`() {
        val sessions = listOf(point("2026-10-06", 1.0))
        assertEquals(0.0, ChildRehabSessionService.summarize(sessions, plannedDays = null).progressRatio, 0.0001)
        assertEquals(0.0, ChildRehabSessionService.summarize(sessions, plannedDays = 0).progressRatio, 0.0001)
        assertNull(ChildRehabSessionService.summarize(sessions, plannedDays = null).plannedDays)
    }

    @Test
    fun `超出计划天数时进度比不封顶`() {
        val summary = ChildRehabSessionService.summarize(
            listOf(point("2026-10-06", 1.0), point("2026-10-07", 1.0), point("2026-10-08", 1.0)),
            plannedDays = 2,
        )
        assertEquals(1.5, summary.progressRatio, 0.0001, "3/2 → 1.5，暴露超出计划天数")
    }

    // ========================================================================
    //  创建校验
    // ========================================================================

    @Test
    fun `创建会话缺少plan_id或session_date报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("session_date", "2026-10-06"), "rehab01")
            }.message!!.contains("plan_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("plan_id", "plan-1"), "rehab01")
            }.message!!.contains("session_date"),
        )
    }

    @Test
    fun `创建会话日期非法报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(JsonObject().put("plan_id", "plan-1").put("session_date", "2026/10/06"), "rehab01")
        }
        assertTrue(error.message!!.contains("session_date"), error.message)
    }

    @Test
    fun `完成度越界报错且缺省为1`() {
        for (ratio in listOf(0, -0.1, 1.5, "abc")) {
            val error = assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("plan_id", "plan-1").put("session_date", "2026-10-06").put("completion_ratio", ratio),
                    "rehab01",
                )
            }
            assertTrue(error.message!!.contains("completion_ratio"), "完成度 $ratio 必须报错，实际: ${error.message}")
        }
    }

    @Test
    fun `metadata类型非法报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("plan_id", "plan-1").put("session_date", "2026-10-06").put("metadata", "x"),
                "rehab01",
            )
        }
        assertTrue(error.message!!.contains("metadata"), error.message)
    }
}
