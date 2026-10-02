package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.pharmacy.DepartmentDirectoryPort
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.TimeUnit

/** 部门目录缓存 TTL：部门增删最多滞后这么久生效。 */
internal const val DEPARTMENT_CACHE_TTL_MS = 60_000L

/**
 * 组织部门目录的 Nexus 支撑实现（040）。
 *
 * 只做一件事：带用户会话 cookie 拉一次 `/crate-api/shared/v1/departments` 的 code 集合，
 * 按 TTL 缓存后回答「这个 code 存在吗」。上游异常交给 Future 失败，
 * 调用方按 503 fail-closed——宁可不建单，也不落一个没有出处的科室。
 */
internal class DepartmentDirectoryClient(
    vertx: Vertx,
    nexusBaseUrl: String,
    private val timeoutMs: Long,
    private val cacheTtlMs: Long = DEPARTMENT_CACHE_TTL_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) : DepartmentDirectoryPort {

    private val log = LoggerFactory.getLogger(DepartmentDirectoryClient::class.java)
    private val httpClient = vertx.createHttpClient(HttpClientOptions().setConnectTimeout(timeoutMs.toInt()))

    @Volatile
    private var cachedCodes: Set<String>? = null

    @Volatile
    private var expiresAtNanos: Long = 0

    private val upstream = URI(nexusBaseUrl)
    private val host: String = requireNotNull(upstream.host) { "nexus.base-url must include a host" }
    private val port: Int = if (upstream.port == -1) 80 else upstream.port
    private val basePath: String = upstream.rawPath.orEmpty().removeSuffix("/")

    override fun exists(code: String, cookie: String?): Future<Boolean> {
        if (cookie.isNullOrBlank()) {
            return Future.failedFuture("session cookie is required")
        }
        val cached = cachedCodes
        if (cached != null && expiresAtNanos > nanoTime()) {
            return Future.succeededFuture(cached.contains(code))
        }

        val target = "$basePath/crate-api/shared/v1/departments?page=1&page_size=100"
        return httpClient.request(HttpMethod.GET, port, host, target)
            .compose { request: HttpClientRequest ->
                request.putHeader("Cookie", cookie)
                request.send().compose { response: HttpClientResponse ->
                    response.body().map { body: Buffer -> Pair(response.statusCode(), body) }
                }
            }
            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
            .map { pair: Pair<Int, Buffer> ->
                if (pair.first != 200) {
                    throw IllegalStateException("department directory returned status ${pair.first}")
                }
                val codes = parseCodes(pair.second)
                cachedCodes = codes
                expiresAtNanos = nanoTime() + cacheTtlMs * 1_000_000L
                codes.contains(code)
            }
            .recover { error: Throwable ->
                // 只记错误信息，绝不记 cookie / 会话值。
                log.warn("department directory lookup failed: {}", error.message)
                Future.failedFuture<Boolean>(error)
            }
    }

    private fun parseCodes(body: Buffer): Set<String> {
        val records = body.toJsonObject().getJsonArray("records") ?: return emptySet()
        return (0 until records.size())
            .mapNotNull { index -> records.getJsonObject(index)?.getString("code")?.trim() }
            .filter { code -> code.isNotEmpty() }
            .toSet()
    }
}
