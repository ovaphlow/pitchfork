package com.ovaphlow.crate.aceso

import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// 036/037 角色消费试点：把「主体 → 角色 → 权限码」接到真实请求上。
//
// 权限码的权威是 platformd 共享角色目录（roles.permission_codes），产品后端不自己定义角色语义，
// 只声明「这个路由需要哪个权限码」。本轮只接护理执行的两条写路由（记录给药、打卡状态更新），
// 读路由与其它模块行为完全不变。
//
// 045：打卡路由改为**资源级选码**——同一条 `PATCH /executions/:id/status` 按执行记录的
// `task_type` 取权限码（康复活动可由康复岗或护理岗完成，见 `DeclaredPermissions.kt`）。

/** 护理执行权限码；格式为「域:动作」，与角色目录里的写法一致。 */
internal const val NURSING_EXECUTE_PERMISSION = "nursing:execute"

// 权限查询缓存 TTL：角色调整最多滞后这么久生效。会话吊销不受影响——每个请求都先过 IdP
// 会话校验，缓存里只有「权限码集合」。
internal const val PERMISSION_CACHE_TTL_MS = 60_000L

private const val PERMISSION_UNAVAILABLE_RETRY_AFTER_SECONDS = "5"

/** 一次权限查询的结论：三态而不是抛异常，便于把状态码映射集中在一处并测试。 */
internal sealed interface PermissionLookup {
    /** 查到权限码集合（可能是空集：用户没有任何角色）。 */
    data class Granted(val permissionCodes: Set<String>) : PermissionLookup

    /** Nexus 明确回答「会话无效」（401）：按未认证处理，让前端跳登录。 */
    data object Unauthenticated : PermissionLookup

    /** 上游不可用（5xx / 超时 / 连接失败 / 响应不可解析）：受保护写操作 fail-closed。 */
    data object Unavailable : PermissionLookup
}

/**
 * 从 platformd 共享 API 取「该主体当前拥有的权限码集合」。
 *
 * 只做一件事：发一次带用户会话 cookie 的 GET，按状态码分类，并把成功结果按 TTL 缓存。
 * 缓存无主动失效（platformd 侧变更靠 TTL 收敛），键是 subjectId，规模等于登录用户数。
 */
internal class SubjectPermissionClient(
    vertx: Vertx,
    nexusBaseUrl: String,
    private val timeoutMs: Long,
    private val cacheTtlMs: Long = PERMISSION_CACHE_TTL_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    private val log = LoggerFactory.getLogger(SubjectPermissionClient::class.java)
    private val httpClient = vertx.createHttpClient(HttpClientOptions().setConnectTimeout(timeoutMs.toInt()))
    private val cache = ConcurrentHashMap<String, CachedPermissions>()

    private val upstream = URI(nexusBaseUrl)
    private val host: String = requireNotNull(upstream.host) { "nexus.base-url must include a host" }
    private val port: Int = if (upstream.port == -1) 80 else upstream.port
    private val basePath: String = upstream.rawPath.orEmpty().removeSuffix("/")

    private class CachedPermissions(val expiresAtNanos: Long, val permissionCodes: Set<String>)

    fun permissions(subjectId: String, cookie: String): Future<PermissionLookup> {
        val cached = cache[subjectId]
        if (cached != null && cached.expiresAtNanos > nanoTime()) {
            return Future.succeededFuture(PermissionLookup.Granted(cached.permissionCodes))
        }
        val encoded = URLEncoder.encode(subjectId, StandardCharsets.UTF_8)
        val target = "$basePath/crate-api/v1/subject-permissions?subject_id=$encoded"
        return httpClient.request(HttpMethod.GET, port, host, target)
            .compose { request: HttpClientRequest ->
                request.putHeader("Cookie", cookie)
                request.putHeader("X-App-Code", "aceso")
                request.send().compose { response: HttpClientResponse ->
                    response.body().map { body: Buffer -> Pair(response.statusCode(), body) }
                }
            }
            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
            .map { pair: Pair<Int, Buffer> -> classify(subjectId, pair.first, pair.second) }
            .recover { error: Throwable ->
                // 只记 subjectId 与错误信息，绝不记 cookie / 会话值。
                log.warn("permission lookup failed for subject {}: {}", subjectId, error.message)
                Future.succeededFuture<PermissionLookup>(PermissionLookup.Unavailable)
            }
    }

    private fun classify(subjectId: String, statusCode: Int, body: Buffer): PermissionLookup =
        when (statusCode) {
            200 -> {
                val codes = parsePermissionCodes(body)
                cache[subjectId] = CachedPermissions(nanoTime() + cacheTtlMs * 1_000_000L, codes)
                PermissionLookup.Granted(codes)
            }
            401 -> PermissionLookup.Unauthenticated
            else -> {
                log.warn("nexus permission lookup returned status {} for subject {}", statusCode, subjectId)
                PermissionLookup.Unavailable
            }
        }

    private fun parsePermissionCodes(body: Buffer): Set<String> {
        val array = body.toJsonObject().getJsonArray("permission_codes") ?: return emptySet()
        return (0 until array.size())
            .mapNotNull { index -> array.getString(index)?.trim() }
            .filter { code -> code.isNotEmpty() }
            .toSet()
    }
}

/**
 * 路由级权限判定：先读会话处理器写入的 `userId`，再查权限码。
 *
 * - 有权限 → `ctx.next()`（同路由的后续处理器继续执行）；
 * - 无权限 → `403` + `required_permission`，前端能明确指出缺哪个权限；
 * - 会话无效 → `401`；上游不可用 → `503`（fail-closed，不退化放行）。
 */
internal fun requirePermission(
    client: SubjectPermissionClient,
    permissionCode: String,
): Handler<RoutingContext> = requireAnyPermission(client, listOf(permissionCode))

/**
 * 045：资源级门禁——满足 [permissionCodes] 中**任一**码即放行。
 *
 * 场景是同一条路由对不同资源需要不同的码：`PATCH /nursing/v1/executions/:id/status` 的打卡
 * 按执行记录的 `task_type` 选码，康复活动（REHABILITATION）允许康复岗（`rehab:execute`）
 * 或护理岗（`nursing:execute`）完成，因此是"任一"而不是"全部"。
 *
 * 046 D1：判定为**双轨**——`ctx["platformAdmin"] == true` 的平台管理员**直接放行**
 * （与 043 D2 的 Nexus 裁定一致；平台管理员是引导者，接线当刻不会"含 admin 在内全员 403"），
 * 否则再查 Nexus 权限码。短路发生在取到会话主体之后、查询 Nexus **之前**（少一次上游调用）。
 *
 * 语义与 [requirePermission] 完全一致（401/403/503 三种失败路径、成功按主体缓存）；
 * 区别只在 403 的 `required_permission` 是若干个码的枚举（`"a 或 b"`），前端原样读给用户。
 *
 * [permissionCodes] 为空属于接线错误（会让 403 说不出缺什么码），在**装配时**直接失败而不是运行时静默放行。
 */
internal fun requireAnyPermission(
    client: SubjectPermissionClient,
    permissionCodes: List<String>,
): Handler<RoutingContext> {
    require(permissionCodes.isNotEmpty()) { "permission gate needs at least one permission code" }
    val requiredLabel = permissionCodes.distinct().joinToString(" 或 ")
    return Handler { ctx ->
        val subjectId = ctx.get<String>("userId")
        val cookie = ctx.request().getHeader("Cookie")
        if (subjectId.isNullOrBlank() || cookie.isNullOrBlank()) {
            respondPermissionUnauthorized(ctx)
            return@Handler
        }
        // 046 D1 双轨：平台管理员旁路（口令仍是"必须先有会话"）。判定不出管理员一律按 false。
        if (ctx.get<Boolean>("platformAdmin") == true) {
            ctx.next()
            return@Handler
        }
        client.permissions(subjectId, cookie).onComplete { result ->
            when (val lookup = result.result()) {
                is PermissionLookup.Granted ->
                    if (permissionCodes.any { lookup.permissionCodes.contains(it) }) {
                        ctx.next()
                    } else {
                        respondPermissionForbidden(ctx, requiredLabel)
                    }
                PermissionLookup.Unauthenticated -> respondPermissionUnauthorized(ctx)
                PermissionLookup.Unavailable -> respondPermissionUnavailable(ctx)
                null -> respondPermissionUnavailable(ctx)
            }
        }
    }
}

private fun respondPermissionForbidden(ctx: RoutingContext, requiredPermission: String) {
    respondJson(
        ctx,
        403,
        JsonObject().put("error", "forbidden").put("required_permission", requiredPermission),
    )
}

private fun respondPermissionUnauthorized(ctx: RoutingContext) {
    respondJson(ctx, 401, JsonObject().put("error", "authentication required"))
}

/** 依赖不可用：`503` + `Retry-After`，与 IdP 会话校验的降级形状一致（前端不登出、只报错）。 */
private fun respondPermissionUnavailable(ctx: RoutingContext) {
    respondJson(
        ctx,
        503,
        JsonObject().put("error", "permission service unavailable"),
        retryAfterSeconds = PERMISSION_UNAVAILABLE_RETRY_AFTER_SECONDS,
    )
}

/**
 * 闸门失败响应的统一出口。
 *
 * **必须带 `Content-Type: application/json`**：前端 `packages/shared/src/aceso.ts` 的
 * `readResponse` 只在 Content-Type 声明 JSON 时解析错误体；漏掉这个头会让 403 的
 * `required_permission` 丢失，前端退化成"该操作需要管理员权限"的错误文案（045 实测修复）。
 * `response().end(String)` 不会自行推断类型，与 `RoutingContext.json()` 的差别就在这里。
 */
private fun respondJson(
    ctx: RoutingContext,
    status: Int,
    body: JsonObject,
    retryAfterSeconds: String? = null,
) {
    if (ctx.response().ended()) return
    val response = ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json")
    if (retryAfterSeconds != null) response.putHeader("Retry-After", retryAfterSeconds)
    response.end(body.encode())
}
