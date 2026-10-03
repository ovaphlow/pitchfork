package com.ovaphlow.crate.pharmacy

import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 042 路由层操作人解析护栏（**不连数据库、不起服务之外的东西**）。
 *
 * 锁的是 `PharmacyRoutes.withOperator` 这个唯一入口，也就是药房 6 条写路由
 * （审方/调配/发药确认/发药取消/退药创建/退药确认）共用的判定：
 *
 * 1. **会话是权威**：有会话且请求体未提交（或提交的就是本人）→ 采用会话主体；
 * 2. **代操作只对平台管理员开放**：`platformAdmin = true` 时请求体他人被采纳；
 * 3. **非管理员提交他人 → 400 且不进业务**：fail-closed，绝不静默改写成自己
 *    （否则留痕会指向错误的人）；
 * 4. **无认证挂载回落请求体**：嵌入式/集成测试路由没有会话，保持既有语义（042 D4）。
 *
 * 这里刻意只探测解析结果（探针 handler 把有效操作人回显为 JSON），
 * 不复制 DispenseService/ReturnService 的数据库交互——那部分由
 * `DispenseServiceTest` / `ReturnServiceTest` 与真库验收覆盖。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorDerivationTest {

    companion object {
        private const val PORT = 18461
        private const val ACTOR = "01M3ZR3CDAQ6ZKHCYD065RSYMT"
        private const val OTHER = "01KYE3F6CPNJS16RHT01M64D8B"
    }

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private var server: HttpServer? = null

    /** 会话形态：无认证挂载 / 普通业务账号 / 平台管理员。 */
    private enum class Session {
        ABSENT,
        STAFF,
        PLATFORM_ADMIN,
    }

    private var session = Session.STAFF

    private val sessionHandler = Handler<RoutingContext> { ctx ->
        when (session) {
            Session.ABSENT -> ctx.next()
            Session.STAFF -> {
                ctx.put("userId", ACTOR)
                ctx.put("platformAdmin", false)
                ctx.next()
            }
            Session.PLATFORM_ADMIN -> {
                ctx.put("userId", ACTOR)
                ctx.put("platformAdmin", true)
                ctx.next()
            }
        }
    }

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        this.vertx = vertx
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        // 与生产同形的挂载：BodyHandler → 会话处理器 → 探针 handler（只回显解析结果）。
        val apiRouter = Router.router(vertx)
        apiRouter.route().handler(io.vertx.ext.web.handler.BodyHandler.create())
        apiRouter.route().handler(sessionHandler)
        apiRouter.post("/probe").handler { routingContext ->
            PharmacyRoutes.withOperator(routingContext, PharmacyRoutes.body(routingContext)) { operator ->
                routingContext.response()
                    .putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("operator", operator).encode())
            }
        }

        vertx.createHttpServer()
            .requestHandler(apiRouter)
            .listen(PORT)
            .onComplete { ar ->
                if (ar.succeeded()) {
                    server = ar.result()
                    ctx.completeNow()
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        client.close()
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @BeforeEach
    fun resetSession() {
        session = Session.STAFF
    }

    // ------------------------------------------------------------------
    // 1. 会话是权威
    // ------------------------------------------------------------------

    @Test
    fun `a staff session resolves the operator to the session subject`() {
        for (body in listOf(JsonObject(), JsonObject().put("operator", ""), JsonObject().put("operator", ACTOR))) {
            val probe = post(body)
            assertEquals(200, probe.status, "会话主体必须能通过，实际 body=${probe.body}")
            assertEquals(ACTOR, JsonObject(probe.body).getString("operator"), "请求体=${body.encode()} 时必须采用会话主体")
        }
    }

    // ------------------------------------------------------------------
    // 2/3. 代操作门禁
    // ------------------------------------------------------------------

    @Test
    fun `a platform administrator may delegate to someone else`() {
        session = Session.PLATFORM_ADMIN
        val probe = post(JsonObject().put("operator", OTHER))
        assertEquals(200, probe.status, "平台管理员代操作必须放行，实际 body=${probe.body}")
        assertEquals(OTHER, JsonObject(probe.body).getString("operator"))
    }

    @Test
    fun `a staff session submitting someone else is rejected without touching business logic`() {
        val probe = post(JsonObject().put("operator", OTHER))
        assertEquals(400, probe.status, "非管理员代操作必须 fail-closed，实际 body=${probe.body}")
        assertEquals(
            "operator cannot be delegated",
            JsonObject(probe.body).getString("error"),
            "必须是显式拒绝，而不是被静默改写成会话主体（否则留痕指向错误的人）",
        )
    }

    @Test
    fun `a staff session submitting free text is rejected as well`() {
        val probe = post(JsonObject().put("operator", "审方药师"))
        assertEquals(400, probe.status, "非管理员的自由文本同样不得被采纳，实际 body=${probe.body}")
        assertEquals("operator cannot be delegated", JsonObject(probe.body).getString("error"))
    }

    // ------------------------------------------------------------------
    // 4. 无认证挂载回落
    // ------------------------------------------------------------------

    @Test
    fun `without an authenticated session the requested operator is used`() {
        session = Session.ABSENT
        val probe = post(JsonObject().put("operator", "审方药师"))
        assertEquals(200, probe.status, "无认证挂载必须回落请求体（嵌入式/集成测试语义），实际 body=${probe.body}")
        assertEquals("审方药师", JsonObject(probe.body).getString("operator"))
    }

    @Test
    fun `without an authenticated session and without a requested operator nothing is resolved`() {
        session = Session.ABSENT
        val probe = post(JsonObject())
        assertEquals(200, probe.status)
        assertEquals(
            null,
            JsonObject(probe.body).getString("operator"),
            "无会话且未提交时返回 null：必填端点由服务端保持既有 400 operator is required 口径",
        )
    }

    // ------------------------------------------------------------------
    // HTTP 单次请求
    // ------------------------------------------------------------------

    private data class Probe(val status: Int, val body: String)

    private fun post(body: JsonObject): Probe {
        val future: CompletableFuture<Probe> =
            client.request(HttpMethod.POST, PORT, "127.0.0.1", "/probe")
                .compose { request ->
                    request.putHeader("Content-Type", "application/json")
                    request.send(body.encode())
                }
                .compose { response: HttpClientResponse ->
                    response.body().map { buffer -> Probe(response.statusCode(), buffer.toString()) }
                }
                .toCompletionStage()
                .toCompletableFuture()
        return future.get(10, TimeUnit.SECONDS)
    }
}
