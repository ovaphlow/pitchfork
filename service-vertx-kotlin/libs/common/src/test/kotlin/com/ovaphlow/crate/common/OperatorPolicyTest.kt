package com.ovaphlow.crate.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 操作人来源判定的逐分支锁定（计划 042 §4.3 / §5 第 4、5 项）。
 *
 * 这里锁定的是**契约**而不是实现：四类结果与"静默改写被禁止"的不变式一旦被放宽，
 * 就会出现"非管理员把操作人写成别人"或"非管理员提交他人被悄悄换成自己"两类回归。
 */
class OperatorPolicyTest {

    private val actor = "01M3ZR3CDAQ6ZKHCYD065RSYMT"
    private val other = "01KYE3F6CPNJS16RHT01M64D8B"

    // ------------------------------------------------------------------
    // 有会话：以会话为准
    // ------------------------------------------------------------------

    @Test
    fun `a session without a requested operator resolves to the session subject`() {
        assertEquals(OperatorResolution.Resolved(actor), resolveOperator(actor, false, null))
        assertEquals(OperatorResolution.Resolved(actor), resolveOperator(actor, false, ""))
        assertEquals(OperatorResolution.Resolved(actor), resolveOperator(actor, false, "   "))
        assertEquals(OperatorResolution.Resolved(actor), resolveOperator(actor, true, null))
    }

    @Test
    fun `requesting yourself is the same as not requesting anyone`() {
        assertEquals(OperatorResolution.Resolved(actor), resolveOperator(actor, false, actor))
        assertEquals(
            OperatorResolution.Resolved(actor),
            resolveOperator(actor, false, "  $actor  "),
            "空白必须被 trim 后再比较，否则同一主体会被判成代操作",
        )
    }

    // ------------------------------------------------------------------
    // 有会话 + 提交他人：只有平台管理员可以代操作
    // ------------------------------------------------------------------

    @Test
    fun `a platform administrator may delegate to someone else`() {
        assertEquals(OperatorResolution.Delegated(other), resolveOperator(actor, true, other))
        assertEquals(
            OperatorResolution.Delegated("审方药师"),
            resolveOperator(actor, true, "  审方药师 "),
            "代操作允许自由文本：本轮只收紧来源、不收紧形态（历史行与嵌入式测试存在非 ULID 值）",
        )
    }

    @Test
    fun `a non administrator submitting someone else is not delegatable`() {
        assertEquals(OperatorResolution.NotDelegatable, resolveOperator(actor, false, other))
        assertEquals(
            OperatorResolution.NotDelegatable,
            resolveOperator(actor, false, "审方药师"),
            "非管理员同样不得把操作人写成自由文本",
        )
    }

    // ------------------------------------------------------------------
    // 无认证挂载（嵌入式/集成测试）：回落请求体，保持既有行为
    // ------------------------------------------------------------------

    @Test
    fun `without an authenticated session the requested operator is used as is`() {
        assertEquals(OperatorResolution.Delegated("审方药师"), resolveOperator(null, false, "审方药师"))
        assertEquals(OperatorResolution.Delegated(other), resolveOperator("   ", false, other))
        assertEquals(
            OperatorResolution.Delegated(other),
            resolveOperator(null, true, other),
            "无会话时 platformAdmin 不参与判定（嵌入式挂载没有会话，也就没有代操作语义）",
        )
    }

    @Test
    fun `without an authenticated session and without a requested operator nothing is written`() {
        assertEquals(OperatorResolution.Absent, resolveOperator(null, false, null))
        assertEquals(OperatorResolution.Absent, resolveOperator(null, false, ""))
        assertEquals(OperatorResolution.Absent, resolveOperator(null, false, "   "))
    }
}
