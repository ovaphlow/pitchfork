package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.healthcare.AuditEntry
import com.ovaphlow.crate.healthcare.AuditRecorder
import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import org.slf4j.LoggerFactory

/**
 * 054 F3 集中式**旁路**审计中间件（§3 Dh / §4 F3 / §8.6）。
 *
 * ## 注册位置（护栏，勿动）
 * 在 `Main.kt` 的**认证总闸之后、集中式权限门禁循环之前**挂到 `apiRouter`（`/crate-api` 子树）：
 * - 在认证之后 → `ctx["userId"]` / `ctx["platformAdmin"]` 已就绪，审计才有 `actor`；
 * - 在门禁之前 → **门禁自己 `end()` 的 403 尝试也会入账**（§8.6 ⑤）；
 * - **不得插入 `BodyHandler`、不得改动既有注册顺序**（2026-09-29 P0 根因护栏）。
 *
 * ## 行为
 * - 只审计写方法（`POST/PUT/PATCH/DELETE`）；`GET` 不入账。
 * - 在 `response().endHandler` 里落一条元数据：**每个响应恰好触发一次**，天然不重复。
 * - **绝不读取请求体**（PHI 不落库）：只取 method / path / status / actor / `:id` / `platform_admin`。
 * - 落库失败只 `log.warn`，**不阻断主请求、不修改状态码**（旁路语义）。
 * - `path` = 挂载点（`/crate-api`）+ `normalizedPath()`；兼容 `normalizedPath()` 是否已含挂载点两种实现。
 */
object AuditMiddleware {

    /** 入账的写方法；GET/HEAD/OPTIONS 不入账。 */
    val AUDITED_METHODS: Set<HttpMethod> = setOf(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.PATCH,
        HttpMethod.DELETE,
    )
}

private val log = LoggerFactory.getLogger("com.ovaphlow.crate.aceso.AuditMiddleware")

/**
 * 构造审计中间件（054 F3）。
 *
 * @param recorder 审计落库端口（生产为 `AuditLogService(pool)`；单测可用替身）
 */
fun auditMiddleware(recorder: AuditRecorder): Handler<RoutingContext> = Handler { ctx ->
    if (ctx.request().method() !in AuditMiddleware.AUDITED_METHODS) {
        ctx.next()
        return@Handler
    }
    // 旁路：只在响应结束时落库；此处不读请求体、不改响应。
    ctx.response().endHandler {
        val entry = AuditEntry(
            actor = ctx.get<String>("userId"),
            method = ctx.request().method().name(),
            path = auditedPath(ctx),
            statusCode = ctx.response().statusCode,
            resourceId = ctx.pathParam("id"),
            metadata = JsonObject().put("platform_admin", ctx.get<Boolean>("platformAdmin") ?: false),
        )
        recorder.record(entry).onFailure { error ->
            // 审计是旁路：失败只告警，主请求的响应与状态码不受影响。
            log.warn("audit log write failed (main request unaffected)", error)
        }
    }
    ctx.next()
}

/** 规范化审计路径：挂载点 + `normalizedPath()`（幂等，避免重复前缀）。 */
internal fun auditedPath(ctx: RoutingContext): String {
    val mount = ctx.mountPoint() ?: ""
    val normalized = ctx.normalizedPath() ?: ctx.request().path() ?: ""
    return when {
        mount.isEmpty() -> normalized
        normalized.startsWith(mount) -> normalized
        else -> mount + normalized
    }
}
