package com.ovaphlow.crate.common

/**
 * 业务操作人的来源判定（计划 042 §4.3 冻结的唯一口径）。
 *
 * ## 为什么需要它
 *
 * 药房/护理的「操作人 / 评估人 / 制定人 / 执行人」此前由客户端提交、服务端只校验非空，
 * 于是任何登录用户都能把操作人写成别人（伪造留痕）；而这些字段的候选列表又来自
 * **管理员接口** `GET /crate-api/identity/v1/session`→`/subjects`，非管理员一律 403，
 * 前端下拉为空且表单必填 → 业务账号做不了本职工作。
 *
 * 正确口径与仓库既有三处一致（办理入住的 `attending_physician`、V524 的
 * `discharged_by`/`deceased_by`、护理打卡的 `executor`）：**操作人的权威来源是认证会话**，
 * 客户端提交的值只是"请求"，只有平台管理员可以指定他人（代操作）。
 *
 * ## 本模块为什么是纯函数
 *
 * `libs/common` 不依赖 vertx，因此这里只做判定、不碰 `RoutingContext`；
 * 路由层负责取出 `ctx["userId"]` / `ctx["platformAdmin"]` 与请求体里的 `operator` 再调用本函数。
 * 这样判定逻辑可以在无 HTTP、无数据库的前提下被逐分支锁定。
 */
sealed interface OperatorResolution {
    /** 采用会话主体（请求体未提交，或提交的就是本人）。 */
    data class Resolved(val subjectId: String) : OperatorResolution

    /**
     * 采用**请求体提供的**操作人。两种来源都归到这里：
     * 1. 平台管理员代操作（会话存在且 `platformAdmin = true`）；
     * 2. 无认证挂载（嵌入式/集成测试路由）时的回落，保持既有测试与本地挂载语义。
     */
    data class Delegated(val requested: String) : OperatorResolution

    /** 既没有会话主体、请求体也没给：可空字段不写，必填字段由调用方按现状返回 400/401。 */
    data object Absent : OperatorResolution

    /** 非平台管理员提交了他人：调用方必须 fail-closed 返回 `400 operator cannot be delegated`。 */
    data object NotDelegatable : OperatorResolution
}

/**
 * 判定本次请求的有效操作人。
 *
 * @param actorId 认证会话主体（`ctx["userId"]`）；无认证挂载时为 `null`
 * @param platformAdmin 会话主体是否为平台管理员（`ctx["platformAdmin"]`）；未知一律传 `false`
 * @param requested 请求体里的 `operator`（或 `assessor`/`created_by`/`executor`）
 *
 * 不变式：
 * - **静默改写被禁止**：非管理员提交他人不是"悄悄换成自己"，而是 [NotDelegatable]，由调用方报错；
 * - 空白值一律视为"未提交"（与仓库其它 `trim().takeIf { it.isNotBlank() }` 口径一致）；
 * - 管理员代操作允许自由文本（历史行与嵌入式测试存在 `"审方药师"` 这类非 ULID 值），
 *   本轮只收紧**来源**，不收紧**形态**（042 §7）。
 */
fun resolveOperator(
    actorId: String?,
    platformAdmin: Boolean,
    requested: String?,
): OperatorResolution {
    val actor = actorId?.trim()?.takeIf { it.isNotBlank() }
    val ask = requested?.trim()?.takeIf { it.isNotBlank() }
    if (actor == null) {
        return ask?.let { OperatorResolution.Delegated(it) } ?: OperatorResolution.Absent
    }
    if (ask == null || ask == actor) {
        return OperatorResolution.Resolved(actor)
    }
    return if (platformAdmin) OperatorResolution.Delegated(ask) else OperatorResolution.NotDelegatable
}
