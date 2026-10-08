package com.ovaphlow.crate.dining

import io.mockk.mockk
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 054 A4：特殊膳食申请**状态机与入参校验**的纯逻辑单测（不访库）。
 *
 * 状态机（054 §5.8 冻结）：`待审批 → 已批准 / 已驳回 / 已终止`；`已批准 / 已驳回 → 已终止`；
 * `已终止` 为终态。非法流转一律 `IllegalArgumentException`（路由 400）。
 */
class SpecialDietRequestServiceTest {

    /** 校验路径不触库：入参非法必须在访问 `pool` 之前同步抛出。 */
    private val service = SpecialDietRequestService(mockk<Pool>(relaxed = true))

    // ========================================================================
    //  白名单
    // ========================================================================

    @Test
    fun `申请类型与状态白名单为中文冻结值`() {
        assertEquals(setOf("过敏回避", "治疗膳食", "其他"), SpecialDietRequestService.REQUEST_TYPES)
        assertEquals(
            setOf("待审批", "已批准", "已驳回", "已终止"),
            SpecialDietRequestService.STATUSES,
        )
        assertEquals("已终止", SpecialDietRequestService.TERMINAL_STATUS)
    }

    // ========================================================================
    //  状态机
    // ========================================================================

    @Test
    fun `待审批可批准可驳回可撤回`() {
        assertTrue(SpecialDietRequestService.canTransition("待审批", "已批准"))
        assertTrue(SpecialDietRequestService.canTransition("待审批", "已驳回"))
        assertTrue(SpecialDietRequestService.canTransition("待审批", "已终止"))
    }

    @Test
    fun `已批准与已驳回只能流转到已终止`() {
        assertTrue(SpecialDietRequestService.canTransition("已批准", "已终止"))
        assertTrue(SpecialDietRequestService.canTransition("已驳回", "已终止"))
        assertEquals(setOf("已终止"), SpecialDietRequestService.allowedTargets("已批准"))
        assertEquals(setOf("已终止"), SpecialDietRequestService.allowedTargets("已驳回"))
    }

    @Test
    fun `已终止是终态`() {
        assertTrue(SpecialDietRequestService.allowedTargets("已终止").isEmpty())
        for (target in SpecialDietRequestService.STATUSES) {
            assertFalse(
                SpecialDietRequestService.canTransition("已终止", target),
                "终态不得流转到 $target",
            )
        }
    }

    @Test
    fun `非法流转一律拒绝`() {
        // 回退到待审批
        assertFalse(SpecialDietRequestService.canTransition("已批准", "待审批"))
        assertFalse(SpecialDietRequestService.canTransition("已驳回", "待审批"))
        // 批准与驳回之间互转
        assertFalse(SpecialDietRequestService.canTransition("已批准", "已驳回"))
        assertFalse(SpecialDietRequestService.canTransition("已驳回", "已批准"))
        // 同状态重复流转
        assertFalse(SpecialDietRequestService.canTransition("待审批", "待审批"))
        // 未知状态不可流转
        assertFalse(SpecialDietRequestService.canTransition("未知", "已批准"))
        assertTrue(SpecialDietRequestService.allowedTargets("未知").isEmpty())
    }

    @Test
    fun `流转到非法状态值同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.updateStatus("req-1", "已审批", "dietitian-1")
        }
        assertTrue(error.message!!.contains("invalid status"), error.message)
        assertThrows<IllegalArgumentException> { service.updateStatus("req-1", null, "dietitian-1") }
        assertThrows<IllegalArgumentException> { service.updateStatus("req-1", "  ", "dietitian-1") }
    }

    // ========================================================================
    //  创建入参校验（同步，不触库）
    // ========================================================================

    @Test
    fun `创建缺少必填字段报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("request_type", "过敏回避").put("meal_type", "午餐"), "op")
            }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("patient_id", "p1").put("meal_type", "午餐"), "op")
            }.message!!.contains("request_type"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("patient_id", "p1").put("request_type", "过敏回避"), "op")
            }.message!!.contains("meal_type"),
        )
    }

    @Test
    fun `创建拒绝白名单外的类型与餐次`() {
        assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("patient_id", "p1").put("request_type", "忌口").put("meal_type", "午餐"),
                "op",
            )
        }
        assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("patient_id", "p1").put("request_type", "过敏回避").put("meal_type", "夜宵"),
                "op",
            )
        }
    }

    @Test
    fun `过敏原非数组非字符串或含非字符串元素时报错`() {
        // 类型非法 → 400（同步）
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject()
                    .put("patient_id", "p1")
                    .put("request_type", "过敏回避")
                    .put("meal_type", "午餐")
                    .put("allergens", 123),
                "op",
            )
        }
        assertTrue(error.message!!.contains("allergens"), error.message)

        val elementError = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject()
                    .put("patient_id", "p1")
                    .put("request_type", "过敏回避")
                    .put("meal_type", "午餐")
                    .put("allergens", JsonArray().add(JsonObject().put("allergen", "花生"))),
                "op",
            )
        }
        assertTrue(elementError.message!!.contains("allergens"), elementError.message)
    }

    @Test
    fun `生效区间倒置报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject()
                    .put("patient_id", "p1")
                    .put("request_type", "治疗膳食")
                    .put("meal_type", "晚餐")
                    .put("effective_from", "2026-10-10")
                    .put("effective_to", "2026-10-01"),
                "op",
            )
        }
        assertTrue(error.message!!.contains("effective_to"), error.message)
    }

    @Test
    fun `生效日期非法格式报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject()
                    .put("patient_id", "p1")
                    .put("request_type", "治疗膳食")
                    .put("meal_type", "晚餐")
                    .put("effective_from", "2026/10/10"),
                "op",
            )
        }
        assertTrue(error.message!!.contains("effective_from"), error.message)
    }
}
