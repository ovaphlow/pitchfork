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
// 权限码的权威是 Nexus 共享角色目录（roles.permission_codes），产品后端不自己定义角色语义，
// 只声明「这个路由需要哪个权限码」。本轮只接护理执行的两条写路由（记录给药、打卡状态更新），
// 读路由与其它模块行为完全不变。

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
 * 从 Nexus 取「该主体当前拥有的权限码集合」。
 *
 * 只做一件事：发一次带用户会话 cookie 的 GET，按状态码分类，并把成功结果按 TTL 缓存。
 * 缓存无主动失效（Nexus 侧变更靠 TTL 收敛），键是 subjectId，规模等于登录用户数。
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
        val target = "$basePath/crate-api/shared/v1/subject-permissions?subject_id=$encoded"
        return httpClient.request(HttpMethod.GET, port, host, target)
            .compose { request: HttpClientRequest ->
                request.putHeader("Cookie", cookie)
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
): Handler<RoutingContext> =
    Handler { ctx ->
        val subjectId = ctx.get<String>("userId")
        val cookie = ctx.request().getHeader("Cookie")
        if (subjectId.isNullOrBlank() || cookie.isNullOrBlank()) {
            respondPermissionUnauthorized(ctx)
            return@Handler
        }
        client.permissions(subjectId, cookie).onComplete { result ->
            when (val lookup = result.result()) {
                is PermissionLookup.Granted ->
                    if (lookup.permissionCodes.contains(permissionCode)) {
                        ctx.next()
                    } else {
                        respondPermissionForbidden(ctx, permissionCode)
                    }
                PermissionLookup.Unauthenticated -> respondPermissionUnauthorized(ctx)
                PermissionLookup.Unavailable -> respondPermissionUnavailable(ctx)
                null -> respondPermissionUnavailable(ctx)
            }
        }
    }

private fun respondPermissionForbidden(ctx: RoutingContext, permissionCode: String) {
    if (!ctx.response().ended()) {
        ctx.response()
            .setStatusCode(403)
            .end(
                JsonObject()
                    .put("error", "forbidden")
                    .put("required_permission", permissionCode)
                    .encode(),
            )
    }
}

private fun respondPermissionUnauthorized(ctx: RoutingContext) {
    if (!ctx.response().ended()) {
        ctx.response().setStatusCode(401).end(JsonObject().put("error", "authentication required").encode())
    }
}

/** 依赖不可用：`503` + `Retry-After`，与 IdP 会话校验的降级形状一致（前端不登出、只报错）。 */
private fun respondPermissionUnavailable(ctx: RoutingContext) {
    if (!ctx.response().ended()) {
        ctx.response()
            .setStatusCode(503)
            .putHeader("Retry-After", PERMISSION_UNAVAILABLE_RETRY_AFTER_SECONDS)
            .end(JsonObject().put("error", "permission service unavailable").encode())
    }
}
