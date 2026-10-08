package com.ovaphlow.crate.nursing

import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 054 B1：儿童康复周期**状态机与入参校验**的纯逻辑单测（不访库）。
 *
 * 状态机与既有 `ServicePeriodService.VALID_STATUS_TRANSITIONS` 同口径：
 * `ACTIVE ⇄ SUSPENDED`，二者 → `COMPLETED` / `CANCELLED`；终态不可再流转。
 */
class ChildRehabPeriodServiceTest {

    /** 校验路径不触库：入参非法必须在访问 `pool` 之前同步抛出。 */
    private val periodService = ChildRehabPeriodService(mockk<Pool>(relaxed = true))
    private val planService = ChildRehabPlanService(mockk<Pool>(relaxed = true))

    // ========================================================================
    //  常量与状态机
    // ========================================================================

    @Test
    fun `儿童康复服务类型为英文code且状态沿用既有枚举`() {
        assertEquals("CHILD_REHAB", ChildRehabPeriodService.SERVICE_TYPE)
        assertEquals(
            setOf("ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED"),
            ChildRehabPeriodService.STATUSES,
        )
        assertEquals(
            "uq_nursing_service_periods_active_child_rehab",
            ChildRehabPeriodService.ACTIVE_UNIQUE_INDEX,
        )
    }

    @Test
    fun `启用与暂停可互转且都能终结`() {
        assertTrue(ChildRehabPeriodService.canTransition("ACTIVE", "SUSPENDED"))
        assertTrue(ChildRehabPeriodService.canTransition("ACTIVE", "COMPLETED"))
        assertTrue(ChildRehabPeriodService.canTransition("ACTIVE", "CANCELLED"))
        assertTrue(ChildRehabPeriodService.canTransition("SUSPENDED", "ACTIVE"))
        assertTrue(ChildRehabPeriodService.canTransition("SUSPENDED", "COMPLETED"))
        assertTrue(ChildRehabPeriodService.canTransition("SUSPENDED", "CANCELLED"))
    }

    @Test
    fun `完成与取消是终态`() {
        for (terminal in listOf("COMPLETED", "CANCELLED")) {
            assertTrue(ChildRehabPeriodService.allowedTargets(terminal).isEmpty(), "$terminal 必须是终态")
            for (target in ChildRehabPeriodService.STATUSES) {
                assertFalse(
                    ChildRehabPeriodService.canTransition(terminal, target),
                    "$terminal 不得流转到 $target",
                )
            }
        }
        // 未知状态不可流转
        assertFalse(ChildRehabPeriodService.canTransition("UNKNOWN", "ACTIVE"))
    }

    @Test
    fun `流转到非法状态值同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            periodService.updateStatus("period-1", "PENDING")
        }
        assertTrue(error.message!!.contains("invalid status"), error.message)
        assertThrows<IllegalArgumentException> { periodService.updateStatus("period-1", null) }
        assertThrows<IllegalArgumentException> { periodService.updateStatus("period-1", "  ") }
    }

    // ========================================================================
    //  周期创建校验
    // ========================================================================

    @Test
    fun `创建周期缺少必填字段报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                periodService.create(JsonObject().put("start_date", "2026-10-06"), "rehab01")
            }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                periodService.create(JsonObject().put("patient_id", "p1"), "rehab01")
            }.message!!.contains("start_date"),
        )
    }

    @Test
    fun `创建周期日期非法或倒置报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                periodService.create(JsonObject().put("patient_id", "p1").put("start_date", "2026/10/06"), "rehab01")
            }.message!!.contains("start_date"),
        )
        val reversed = assertThrows<IllegalArgumentException> {
            periodService.create(
                JsonObject().put("patient_id", "p1").put("start_date", "2026-10-06").put("end_date", "2026-10-01"),
                "rehab01",
            )
        }
        assertTrue(reversed.message!!.contains("end_date"), reversed.message)
    }

    @Test
    fun `创建周期metadata类型非法报错`() {
        val error = assertThrows<IllegalArgumentException> {
            periodService.create(
                JsonObject().put("patient_id", "p1").put("start_date", "2026-10-06").put("metadata", "x"),
                "rehab01",
            )
        }
        assertTrue(error.message!!.contains("metadata"), error.message)
    }

    // ========================================================================
    //  计划创建校验
    // ========================================================================

    @Test
    fun `创建计划缺少period_id或plan_name报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                planService.create(JsonObject().put("plan_name", "训练计划"), "rehab01")
            }.message!!.contains("period_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                planService.create(JsonObject().put("period_id", "period-1"), "rehab01")
            }.message!!.contains("plan_name"),
        )
    }

    @Test
    fun `计划明细action必填且duration_days必须为正整数`() {
        val missingAction = assertThrows<IllegalArgumentException> {
            planService.create(
                JsonObject()
                    .put("period_id", "period-1")
                    .put("plan_name", "训练计划")
                    .put("items", io.vertx.core.json.JsonArray().add(JsonObject().put("frequency_code", "QD"))),
                "rehab01",
            )
        }
        assertTrue(missingAction.message!!.contains("action"), missingAction.message)

        val badDuration = assertThrows<IllegalArgumentException> {
            planService.create(
                JsonObject()
                    .put("period_id", "period-1")
                    .put("plan_name", "训练计划")
                    .put(
                        "items",
                        io.vertx.core.json.JsonArray()
                            .add(JsonObject().put("action", "被动关节活动").put("duration_days", 0)),
                    ),
                "rehab01",
            )
        }
        assertTrue(badDuration.message!!.contains("duration_days"), badDuration.message)
    }

    @Test
    fun `计划明细非数组报错`() {
        val error = assertThrows<IllegalArgumentException> {
            planService.create(
                JsonObject().put("period_id", "period-1").put("plan_name", "训练计划").put("items", "被动关节活动"),
                "rehab01",
            )
        }
        assertTrue(error.message!!.contains("items"), error.message)
    }
}
