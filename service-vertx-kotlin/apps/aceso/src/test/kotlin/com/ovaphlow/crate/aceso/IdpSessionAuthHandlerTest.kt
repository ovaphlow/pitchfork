package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * IdP 依赖降级护栏（**不访问数据库、不连真实 IdP**）：真实的 [idpSessionAuthHandler]
 * 面对上游不同应答时，本地状态码必须区分「会话无效」与「依赖不可用」。
 *
 * ## 为什么必须有这个护栏
 *
 * 前端 `ui-astro/packages/shared/src/aceso.ts` 只对 `401` 跳登录。改动前该 handler 把
 * 「上游 401」「上游 5xx」「连接失败」「超时」**一律**回 401，于是 IdP 抖动被当成会话失效 ——
 * 用户被无差别登出（假掉线），且上游无响应时请求无限挂起。
 *
 * `DefaultAuthenticationTest` 用的是**假 sessionAuth**（覆盖挂载方式与顺序，不覆盖本 handler），
 * 所以本类直接对真实实现断言：起假 IdP（随机端口）+ 生产同款挂载（
 * `mainRouter` → `/crate-api` 子树 → 默认拒绝总闸 → 模块子路由）。
 */
@ExtendWith(VertxExtension::class)
class IdpSessionAuthHandlerTest {

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private val servers = mutableListOf<HttpServer>()

    /** 假 IdP 收到的未应答请求（`HANG` 行为下挂住的那些），tearDown 里释放。 */
    private val heldRequests = mutableListOf<HttpServerRequest>()

    /** 假 IdP 实际收到的 `Cookie` 头：证明会话 cookie 确实被转发（而不是在本地被短路）。 */
    private val upstreamCookies = mutableListOf<String?>()

    /** 真正被执行到的业务处理器：证明 401/503 都发生在进入业务逻辑之前。 */
    private val businessCalls = mutableListOf<String>()

    /** 假 IdP 的应答行为，由各用例在发请求前设置。 */
    private var upstreamBehavior = UpstreamBehavior.SESSION_OK

    private enum class UpstreamBehavior {
        /** `200 {"subject_id": ...}`：会话有效。 */
        SESSION_OK,

        /** `401`：会话失效。 */
        INVALID_SESSION,

        /** `403`：会话失效（上游有权限语义时同样属于「无效」而非「不可用」）。 */
        FORBIDDEN,

        /** `500`：上游内部错误，属于依赖不可用。 */
        SERVER_ERROR,

        /** 挂了但不回任何响应（连响应头都不给），模拟 IdP 无响应。 */
        HANG,
    }

    private val authenticatedCookie = "identityd_session=opaque-test-token"

    @BeforeEach
    fun setUp(vertx: Vertx) {
        this.vertx = vertx
        // keepAlive=false：测试只关心闸门判定，不测连接复用语义（同 DefaultAuthenticationTest）。
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
    }

    @AfterEach
    fun tearDown() {
        heldRequests.forEach { req -> if (!req.response().ended()) req.response().close() }
        heldRequests.clear()
        client.close()
        servers.forEach { it.close() }
        servers.clear()
    }

    // ------------------------------------------------------------------
    // 1. 会话有效：200 透传，subject_id 成为业务侧 userId
    // ------------------------------------------------------------------

    @Test
    fun `a valid upstream session passes the request through with the subject id as user id`() {
        upstreamBehavior = UpstreamBehavior.SESSION_OK
        val port = startAcesoWithFakeIdp()

        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", authenticatedCookie)

        assertEquals(200, probe.status, "上游会话有效必须放行，实际 body=${probe.body}")
        assertEquals(listOf("healthcare:GET /patients"), businessCalls, "必须进入业务处理器")
        assertEquals(
            "idp-user-1",
            JsonObject(probe.body).getString("user_id"),
            "上游 subject_id 必须成为业务侧 userId，实际 body=${probe.body}",
        )
        assertEquals(listOf(authenticatedCookie), upstreamCookies, "会话 cookie 必须原样转发给 IdP")
    }

    // ------------------------------------------------------------------
    // 2. 会话失效：上游 401/403 → 本地 401（前端登出，行为不变）
    // ------------------------------------------------------------------

    @Test
    fun `an upstream 401 stays a local 401 so the frontend logs out`() {
        upstreamBehavior = UpstreamBehavior.INVALID_SESSION
        val port = startAcesoWithFakeIdp()

        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", authenticatedCookie)

        assertEquals(401, probe.status, "上游 401 是会话失效，实际 body=${probe.body}")
        assertEquals("""{"error":"authentication required"}""", probe.body, "错误响应外形不得变")
        assertEquals(null, probe.retryAfter, "会话失效不是依赖不可用，不得带 Retry-After")
        assertTrue(businessCalls.isEmpty(), "会话失效不得进入业务处理器")
    }

    @Test
    fun `an upstream 403 stays a local 401 so the frontend logs out`() {
        upstreamBehavior = UpstreamBehavior.FORBIDDEN
        val port = startAcesoWithFakeIdp()

        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", authenticatedCookie)

        assertEquals(401, probe.status, "上游 403 同样属于会话无效，实际 body=${probe.body}")
        assertEquals("""{"error":"authentication required"}""", probe.body)
        assertTrue(businessCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. 依赖不可用：上游 5xx / 连接失败 / 挂起 → 本地 503 + Retry-After（前端不登出）
    // ------------------------------------------------------------------

    @Test
    fun `an upstream 500 becomes a local 503 with Retry-After instead of a false logout`() {
        upstreamBehavior = UpstreamBehavior.SERVER_ERROR
        val port = startAcesoWithFakeIdp()

        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", authenticatedCookie)

        assertEquals(503, probe.status, "上游 5xx 是依赖不可用，不得当会话失效，实际 body=${probe.body}")
        assertEquals("5", probe.retryAfter, "503 必须带 Retry-After: 5")
        assertEquals("""{"error":"identity service unavailable"}""", probe.body, "错误响应外形仍为 {error}")
        assertTrue(businessCalls.isEmpty(), "依赖不可用时不得进入业务处理器")
    }

    @Test
    fun `an unreachable IdP becomes a local 503 with Retry-After`() {
        // 先起一个临时 server 取到端口再关掉：该端口上没有监听者，连接必然被拒。
        val port = startAceso("http://127.0.0.1:${closedPort()}")

        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", authenticatedCookie)

        assertEquals(503, probe.status, "连接失败是依赖不可用，实际 body=${probe.body}")
        assertEquals("5", probe.retryAfter)
        assertEquals("""{"error":"identity service unavailable"}""", probe.body)
        assertTrue(businessCalls.isEmpty())
    }

    @Test
    fun `a hanging upstream returns 503 within the configured timeout window`() {
        upstreamBehavior = UpstreamBehavior.HANG
        // 400ms 的窗口刻意远小于默认 3000ms：若 handler 忽略传入的超时，
        // 实测耗时会是 ~3000ms，下面的 2000ms 上界就会失败。
        val timeoutMs = 400L
        val port = startAcesoWithFakeIdp(timeoutMs)

        val startedAtNanos = System.nanoTime()
        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", authenticatedCookie)
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000

        assertEquals(503, probe.status, "上游挂起必须降级为 503，实际 body=${probe.body}")
        assertEquals("5", probe.retryAfter)
        assertEquals("""{"error":"identity service unavailable"}""", probe.body)
        assertTrue(
            elapsedMs < 2_000,
            "超时后必须有界返回且遵守配置的超时窗口（timeout=${timeoutMs}ms），实测 ${elapsedMs}ms",
        )
        assertTrue(businessCalls.isEmpty(), "依赖不可用时不得进入业务处理器")
        assertEquals(1, upstreamCookies.size, "上游请求必须真的发出过（挂住的是响应）")
        assertEquals(1, heldRequests.size, "假 IdP 必须真的收到并挂住该请求，实测 ${heldRequests.size}")
    }

    // ------------------------------------------------------------------
    // 4. 无会话 cookie：本地 401，不打 IdP（既有行为不变）
    // ------------------------------------------------------------------

    @Test
    fun `a request without the session cookie is rejected locally without contacting the IdP`() {
        upstreamBehavior = UpstreamBehavior.SESSION_OK
        val port = startAcesoWithFakeIdp()

        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", cookie = null)

        assertEquals(401, probe.status, "未携带会话 cookie 必须 401")
        assertEquals("""{"error":"authentication required"}""", probe.body)
        assertTrue(upstreamCookies.isEmpty(), "无会话 cookie 时不应打 IdP")
        assertTrue(businessCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. 超时配置：默认 3000ms，`identity.session-timeout-ms` 可覆盖
    // ------------------------------------------------------------------

    @Test
    fun `the session timeout defaults to 3000ms and is overridable by identity session-timeout-ms`() {
        assertEquals(3000L, DEFAULT_IDP_SESSION_TIMEOUT_MS, "默认超时口径为 3000ms")
        assertEquals(
            DEFAULT_IDP_SESSION_TIMEOUT_MS,
            resolveIdpSessionTimeoutMs(JsonObject().put("base-url", "http://127.0.0.1:8420")),
            "缺失 identity.session-timeout-ms 时用默认值",
        )
        assertEquals(
            750L,
            resolveIdpSessionTimeoutMs(
                JsonObject()
                    .put("base-url", "http://127.0.0.1:8420")
                    .put("session-timeout-ms", 750),
            ),
            "identity.session-timeout-ms 必须覆盖默认值",
        )
    }

    // ------------------------------------------------------------------
    // 装配：生产同款路由树 + 假 IdP
    // ------------------------------------------------------------------

    /** 生产同款的本地路由树（挂载点、顺序与 `Main.kt` 一致），指向给定的上游。 */
    private fun startAceso(upstreamBaseUrl: String, timeoutMs: Long = DEFAULT_IDP_SESSION_TIMEOUT_MS): Int {
        val apiRouter = Router.router(vertx)
        apiRouter.route().handler(
            apiAuthenticationGate(idpSessionAuthHandler(vertx, upstreamBaseUrl, timeoutMs)),
        )
        apiRouter.route("/healthcare/v1/*").subRouter(healthcareRouter())
        val mainRouter = Router.router(vertx)
        mainRouter.route("/crate-api/*").subRouter(apiRouter)
        val server = vertx.createHttpServer().requestHandler(mainRouter)
            .listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    private fun startAcesoWithFakeIdp(timeoutMs: Long = DEFAULT_IDP_SESSION_TIMEOUT_MS): Int =
        startAceso("http://127.0.0.1:${startFakeIdp()}", timeoutMs)

    private fun healthcareRouter(): Router {
        val router = Router.router(vertx)
        router.route(HttpMethod.GET, "/patients").handler { ctx ->
            businessCalls += "healthcare:GET /patients"
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(
                    JsonObject()
                        .put("handler", "healthcare:GET /patients")
                        .put("user_id", ctx.get<String>("userId"))
                        .encode(),
                )
        }
        return router
    }

    /**
     * 假 IdP：只应答 `GET /crate-api/identity/v1/session`，行为由 [upstreamBehavior] 决定。
     * 幂等且无状态，替换真实 IdP 只为去掉外部依赖，**不放宽**任何认证/降级语义。
     */
    private fun startFakeIdp(): Int {
        val server = vertx.createHttpServer().requestHandler { req ->
            upstreamCookies += req.getHeader("Cookie")
            when (upstreamBehavior) {
                UpstreamBehavior.SESSION_OK ->
                    req.response()
                        .putHeader("Content-Type", "application/json")
                        .end(JsonObject().put("subject_id", "idp-user-1").encode())

                UpstreamBehavior.INVALID_SESSION ->
                    req.response()
                        .setStatusCode(401)
                        .end(JsonObject().put("error", "authentication required").encode())

                UpstreamBehavior.FORBIDDEN -> req.response().setStatusCode(403).end()

                UpstreamBehavior.SERVER_ERROR ->
                    req.response()
                        .setStatusCode(500)
                        .end(JsonObject().put("error", "internal error").encode())

                UpstreamBehavior.HANG -> heldRequests += req
            }
        }.listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    /** 起一个临时 server 取到端口后立刻关闭，返回一个没有监听者的端口（连接被拒）。 */
    private fun closedPort(): Int {
        // Vert.x 要求 `listen` 之前必须设置 request handler；本 server 只为占端口，从不接收请求。
        val server = vertx.createHttpServer().requestHandler { req -> req.response().setStatusCode(500).end() }
            .listen(0)
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        val port = server.actualPort()
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        return port
    }

    // ------------------------------------------------------------------
    // HTTP 单次请求：整条链在事件循环上用 compose 串起来，只在最后 await 一次
    // （避免「等响应头」与「读响应体」两段 await 之间的竞态，见 DefaultAuthenticationTest 的说明）
    // ------------------------------------------------------------------

    private data class Probe(val status: Int, val body: String, val retryAfter: String?)

    private fun send(port: Int, method: HttpMethod, path: String, cookie: String? = null): Probe {
        val future: CompletableFuture<Probe> =
            client
                .request(method, port, "127.0.0.1", path)
                .compose { request ->
                    if (cookie != null) request.putHeader("Cookie", cookie)
                    request.send()
                }
                .compose { response: HttpClientResponse ->
                    response.body().map { buffer: Buffer ->
                        Probe(response.statusCode(), buffer.toString(), response.getHeader("Retry-After"))
                    }
                }
                .toCompletionStage()
                .toCompletableFuture()

        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError("$method $path 挂起：10 秒内没有任何响应（依赖降级必须是有界返回）", e)
        }
    }
}
