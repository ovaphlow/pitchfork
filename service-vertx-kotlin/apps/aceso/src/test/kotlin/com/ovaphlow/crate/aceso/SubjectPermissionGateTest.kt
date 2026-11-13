package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 037 权限闸门护栏（**不访问数据库、不连真实 Nexus**）。
 *
 * 用假 Nexus（随机端口）固定五件事：
 * 1. 有权限 → 放行并进入业务处理器；
 * 2. 无权限 → 403 + `required_permission`，且**不进入业务处理器**；
 * 3. 会话无效 → 401；上游不可用 → 503 + `Retry-After`（受保护写操作 fail-closed，不退化放行）；
 * 4. 成功结果按 TTL 缓存（同主体两次请求只打一次上游；TTL 为 0 时每次回源）；
 * 5. 失败响应必须带 `Content-Type: application/json`（045）：漏了它前端不解析错误体，
 *    403 的 `required_permission` 会丢，被翻成"该操作需要管理员权限"。
 * 6. 046 双轨（D1）：平台管理员旁路——即使没有任何权限码也放行，且**不打上游**；
 *    但"没有会话"仍然是 401（管理员也必须先登录）。
 * 另含 045 的资源级选码（任一码放行）与空码装配失败。
 */
@ExtendWith(VertxExtension::class)
class SubjectPermissionGateTest {

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private val servers = mutableListOf<HttpServer>()
    private val businessCalls = mutableListOf<String>()
    private val upstreamSubjectIds = mutableListOf<String>()

    private var upstreamStatus = 200
    private var upstreamBody = GRANTED_BODY

    private data class Probe(val status: Int, val body: String, val retryAfter: String?, val contentType: String?)

    @BeforeEach
    fun setUp(vertx: Vertx) {
        this.vertx = vertx
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
    }

    @AfterEach
    fun tearDown() {
        client.close()
        servers.forEach { it.close() }
        servers.clear()
        businessCalls.clear()
        upstreamSubjectIds.clear()
        upstreamStatus = 200
        upstreamBody = GRANTED_BODY
    }

    @Test
    fun `a granted permission reaches the business handler`() {
        val port = startGate()

        val probe = send(port, COOKIE)

        assertEquals(200, probe.status, "有权限必须放行，实际 body=${probe.body}")
        assertEquals(listOf("protected"), businessCalls, "有权限必须进入业务处理器")
        assertEquals(listOf("subject-1"), upstreamSubjectIds, "查询必须带上会话主体的 subject_id")
    }

    @Test
    fun `a missing permission is rejected with 403 and the required code`() {
        upstreamBody = """{"subject_id":"subject-1","role_codes":["pharmacy.manager"],"permission_codes":["pharmacy:manage"],"source":"nexus"}"""
        val port = startGate()

        val probe = send(port, COOKIE)

        assertEquals(403, probe.status, "缺权限必须 403，实际 body=${probe.body}")
        assertTrue(probe.body.contains(NURSING_EXECUTE_PERMISSION), "403 必须指出缺哪个权限码，实际 body=${probe.body}")
        assertTrue(businessCalls.isEmpty(), "缺权限不得进入业务处理器")
        assertNull(probe.retryAfter, "权限不足不是依赖不可用")
        // 045：没有这个头，前端 `readResponse` 不解析错误体，`required_permission` 会丢，
        // 403 被翻成"该操作需要管理员权限"。
        assertJsonContentType(probe)
    }

    @Test
    fun `a resource-level gate accepts any of the required codes`() {
        upstreamBody = """{"subject_id":"subject-1","role_codes":["rehab.staff"],"permission_codes":["rehab:execute"],"source":"nexus"}"""
        val port = startGate(permissionCodes = listOf(REHAB_PERMISSION, NURSING_EXECUTE_PERMISSION))

        val probe = send(port, COOKIE)

        assertEquals(200, probe.status, "持有任一码必须放行（康复岗只拿 rehab:execute），实际 body=${probe.body}")
        assertEquals(listOf("protected"), businessCalls)
    }

    @Test
    fun `a platform admin bypasses the permission lookup`() {
        // 平台管理员没有任何权限码；上游若被调用会回空集 → 403。双轨必须短路放行且不打上游。
        upstreamBody = """{"subject_id":"subject-1","role_codes":[],"permission_codes":[],"source":"nexus"}"""
        val port = startGate(platformAdmin = true)

        val probe = send(port, COOKIE)

        assertEquals(200, probe.status, "平台管理员必须放行（双轨引导者），实际 body=${probe.body}")
        assertEquals(listOf("protected"), businessCalls)
        assertTrue(upstreamSubjectIds.isEmpty(), "平台管理员旁路必须发生在查询 Nexus 之前，少一次上游调用")
    }

    @Test
    fun `a platform admin without a session is still rejected`() {
        val port = startGate(userId = null, platformAdmin = true)

        val probe = send(port, COOKIE)

        assertEquals(401, probe.status, "没有会话主体时即使是平台管理员也必须 401，实际 body=${probe.body}")
        assertTrue(upstreamSubjectIds.isEmpty(), "没有会话不得查询权限")
        assertTrue(businessCalls.isEmpty())
    }

    @Test
    fun `a resource-level 403 enumerates every acceptable code`() {
        upstreamBody = """{"subject_id":"subject-1","role_codes":["pharmacy.manager"],"permission_codes":["pharmacy:manage"],"source":"nexus"}"""
        val port = startGate(permissionCodes = listOf(REHAB_PERMISSION, NURSING_EXECUTE_PERMISSION))

        val probe = send(port, COOKIE)

        assertEquals(403, probe.status, "两码都没有必须 403，实际 body=${probe.body}")
        assertTrue(probe.body.contains(REHAB_PERMISSION), "403 必须列出康复码，实际 body=${probe.body}")
        assertTrue(
            probe.body.contains(NURSING_EXECUTE_PERMISSION),
            "403 必须列出护理执行码（业务账号才知道该要哪个），实际 body=${probe.body}",
        )
        assertJsonContentType(probe)
        assertTrue(businessCalls.isEmpty())
    }

    @Test
    fun `a gate without any permission code fails at wiring time`() {
        val permissionClient = SubjectPermissionClient(vertx, "http://127.0.0.1:1", 100)

        assertThrows(IllegalArgumentException::class.java) {
            requireAnyPermission(permissionClient, emptyList())
        }
    }

    @Test
    fun `a subject without any role is rejected`() {
        upstreamBody = """{"subject_id":"subject-1","role_codes":[],"permission_codes":[],"source":"nexus"}"""
        val port = startGate()

        val probe = send(port, COOKIE)

        assertEquals(403, probe.status, "没有任何角色必须 403，实际 body=${probe.body}")
        assertTrue(businessCalls.isEmpty())
    }

    @Test
    fun `an unavailable permission service fails closed with 503`() {
        upstreamStatus = 500
        val port = startGate()

        val probe = send(port, COOKIE)

        assertEquals(503, probe.status, "上游故障时受保护写操作必须 fail-closed，实际 body=${probe.body}")
        assertEquals("5", probe.retryAfter, "503 必须带 Retry-After，前端不登出、只报错")
        assertTrue(businessCalls.isEmpty(), "上游故障不得退化放行")
        assertJsonContentType(probe)
    }

    @Test
    fun `an invalid session upstream stays a local 401`() {
        upstreamStatus = 401
        val port = startGate()

        val probe = send(port, COOKIE)

        assertEquals(401, probe.status, "上游 401 是会话失效，实际 body=${probe.body}")
        assertNull(probe.retryAfter, "会话失效不是依赖不可用")
        assertTrue(businessCalls.isEmpty())
        assertJsonContentType(probe)
    }

    @Test
    fun `a missing cookie is rejected without calling upstream`() {
        val port = startGate()

        val probe = send(port, cookie = null)

        assertEquals(401, probe.status, "没有会话 cookie 必须 401，实际 body=${probe.body}")
        assertTrue(upstreamSubjectIds.isEmpty(), "没有会话不得查询权限")
        assertTrue(businessCalls.isEmpty())
        assertJsonContentType(probe)
    }

    @Test
    fun `a successful lookup is cached for the ttl`() {
        val port = startGate(cacheTtlMs = PERMISSION_CACHE_TTL_MS)

        send(port, COOKIE)
        send(port, COOKIE)

        assertEquals(1, upstreamSubjectIds.size, "TTL 内两次请求只允许一次上游查询")
        assertEquals(listOf("protected", "protected"), businessCalls)
    }

    @Test
    fun `an expired entry is looked up again`() {
        val port = startGate(cacheTtlMs = 0)

        send(port, COOKIE)
        send(port, COOKIE)

        assertEquals(2, upstreamSubjectIds.size, "TTL 过期后必须回源，角色调整不能永久滞后")
    }

    private fun startGate(
        permissionCodes: List<String> = listOf(NURSING_EXECUTE_PERMISSION),
        userId: String? = "subject-1",
        platformAdmin: Boolean = false,
        cacheTtlMs: Long = PERMISSION_CACHE_TTL_MS,
    ): Int {
        val nexusPort = startFakeNexus()
        val permissionClient = SubjectPermissionClient(
            vertx,
            "http://127.0.0.1:$nexusPort",
            2_000,
            cacheTtlMs,
        )

        val router = Router.router(vertx)
        router.post("/protected").handler { ctx ->
            if (userId != null) {
                ctx.put("userId", userId)
            }
            // 生产由 idpSessionAuthHandler 同时写 userId 与 platformAdmin；这里照抄该口径。
            ctx.put("platformAdmin", platformAdmin)
            ctx.next()
        }
        router.post("/protected").handler(requireAnyPermission(permissionClient, permissionCodes))
        router.post("/protected").handler { ctx ->
            businessCalls.add("protected")
            ctx.response().setStatusCode(200).end("""{"ok":true}""")
        }
        val server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
        servers.add(server)
        return server.actualPort()
    }

    private fun startFakeNexus(): Int {
        val router = Router.router(vertx)
        router.get("/crate-api/v1/subject-permissions").handler { ctx ->
            upstreamSubjectIds.add(ctx.queryParams().get("subject_id") ?: "")
            ctx.response()
                .setStatusCode(upstreamStatus)
                .putHeader("Content-Type", "application/json")
                .end(upstreamBody)
        }
        val server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
        servers.add(server)
        return server.actualPort()
    }

    private fun send(port: Int, cookie: String?): Probe {
        val result = CompletableFuture<Probe>()
        client.request(HttpMethod.POST, port, "127.0.0.1", "/protected").onSuccess { request ->
            if (cookie != null) {
                request.putHeader("Cookie", cookie)
            }
            request.send().onSuccess { response ->
                response.body().onSuccess { body ->
                    result.complete(
                        Probe(
                            response.statusCode(),
                            body.toString(),
                            response.getHeader("Retry-After"),
                            response.getHeader("Content-Type"),
                        ),
                    )
                }.onFailure(result::completeExceptionally)
            }.onFailure(result::completeExceptionally)
        }.onFailure(result::completeExceptionally)
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun assertJsonContentType(probe: Probe) {
        val contentType = probe.contentType.orEmpty()
        assertTrue(
            contentType.contains("application/json"),
            "失败响应必须声明 application/json，否则前端不解析错误体（实际 Content-Type=$contentType，body=${probe.body}）",
        )
    }

    private companion object {
        const val COOKIE = "platformd_session=opaque-test-token"
        const val REHAB_PERMISSION = "rehab:execute"
        const val GRANTED_BODY =
            """{"subject_id":"subject-1","role_codes":["nursing.staff"],"permission_codes":["nursing:execute"],"source":"nexus"}"""
    }
}
