package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.healthcare.ChildHealthMessagePort
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 052 P4：家长通报的 Nexus `messages` 支撑实现。
 *
 * 只做一件事：带用户会话 cookie `POST /crate-api/v1/messages`，返回 `message_id`。
 *
 * 口径：
 *  - Nexus 写路由要求**有效会话**（`Extension<Identity>`），因此转发调用方的 Cookie，
 *    不自行铸造身份（与 `SubjectPermissionClient` / `DepartmentDirectoryClient` 同范式）；
 *  - `sender_id` 由 Nexus 从会话派生（Aceso 不提交、也提交不了）；
 *  - 失败只记错误信息，绝不记 cookie / 会话值；上游异常交给 Future 失败，
 *    由 `ChildAlertService` 记「发送失败」台账，不谎报送达（052 D6）。
 */
internal class NexusMessageClient(
    vertx: Vertx,
    nexusBaseUrl: String,
    private val timeoutMs: Long,
) : ChildHealthMessagePort {

    private val log = LoggerFactory.getLogger(NexusMessageClient::class.java)
    private val httpClient = vertx.createHttpClient(HttpClientOptions().setConnectTimeout(timeoutMs.toInt()))

    private val upstream = URI(nexusBaseUrl)
    private val host: String = requireNotNull(upstream.host) { "nexus.base-url must include a host" }
    private val port: Int = if (upstream.port == -1) 80 else upstream.port
    private val basePath: String = upstream.rawPath.orEmpty().removeSuffix("/")

    override fun send(
        messageType: String,
        receiverId: String,
        receiverType: String,
        payload: JsonObject,
        cookie: String?,
    ): Future<String> {
        if (cookie.isNullOrBlank()) {
            return Future.failedFuture("session cookie is required")
        }
        val body = JsonObject()
            .put("message_type", messageType)
            .put("sender_type", "aceso")
            .put("receiver_id", receiverId)
            .put("receiver_type", receiverType)
            .put("payload", payload)
            .encode()
        val target = "$basePath/crate-api/v1/messages"
        return httpClient.request(HttpMethod.POST, port, host, target)
            .compose { request: HttpClientRequest ->
                request.putHeader("Cookie", cookie)
                request.putHeader("Content-Type", "application/json")
                request.send(Buffer.buffer(body)).compose { response: HttpClientResponse ->
                    response.body().map { responseBody: Buffer -> Pair(response.statusCode(), responseBody) }
                }
            }
            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
            .compose { pair: Pair<Int, Buffer> ->
                if (pair.first != 201 && pair.first != 200) {
                    Future.failedFuture(
                        IllegalStateException("nexus messages returned status ${pair.first}"),
                    )
                } else {
                    val id = try {
                        pair.second.toJsonObject().getString("id")
                    } catch (_: RuntimeException) {
                        null
                    }
                    if (id.isNullOrBlank()) {
                        Future.failedFuture(IllegalStateException("nexus messages returned no id"))
                    } else {
                        Future.succeededFuture(id)
                    }
                }
            }
            .recover { error: Throwable ->
                // 只记错误信息，绝不记 cookie / 会话值。
                log.warn("nexus message dispatch failed: {}", error.message)
                Future.failedFuture<String>(error)
            }
    }
}
