package com.ovaphlow.crate.nursing

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
import io.vertx.ext.web.handler.BodyHandler
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
 * 042 护理侧操作人解析护栏（**不连数据库**）。
 *
 * 护理有 5 处「谁做的这件事」：评估人（新建 + 修订）、制定人（新建 + 修订）、执行人，
 * 它们共用 `NursingRoutes.withOperator` 这一个入口。本类按**三个字段名**分别锁定四类结果：
 *
 * 1. 会话是权威：有会话且请求体未提交（或提交的就是本人）→ 采用会话主体；
 * 2. 代操作只对平台管理员开放；
 * 3. 非管理员提交他人 → `400 operator cannot be delegated` 且不进业务（fail-closed，
 *    绝不静默改写——否则评估人/制定人/执行人的留痕会指向错误的人）；
 * 4. 无认证挂载回落请求体（嵌入式/集成测试路由，042 D4）。
 *
 * 复评/修订两处走 healthcare 的 `care-plan-revisions` 路由，其嵌套字段由该路由自行判定
 * （见 `HealthcareRoutes`），不经过本入口。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorDerivationTest {

    companion object {
        private const val PORT = 18462
        private const val ACTOR = "01M3ZR3CDAQ6ZKHCYD065RSYMT"
        private const val OTHER = "01KYE3F6CPNJS16RHT01M64D8B"

        /** 护理的三个操作人字段，逐个锁定，避免只测其中一个而漏掉其它。 */
        private val FIELDS = listOf("assessor", "created_by", "executor")
    }

    private lateinit var client: HttpClient
    private var server: HttpServer? = null

    private enum class Session { ABSENT, STAFF, PLATFORM_ADMIN }

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
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        // 与生产同形：BodyHandler → 会话处理器 → 探针 handler（把解析结果回显为 JSON）。
        val apiRouter = Router.router(vertx)
        apiRouter.route().handler(BodyHandler.create())
        apiRouter.route().handler(sessionHandler)
        FIELDS.forEach { field ->
            apiRouter.post("/probe/$field").handler { routingContext ->
                NursingRoutes.withOperator(routingContext, NursingRoutes.body(routingContext), field) { operator ->
                    routingContext.response()
                        .putHeader("Content-Type", "application/json")
                        .end(JsonObject().put("operator", operator).encode())
                }
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

    @Test
    fun `a staff session resolves every operator field to the session subject`() {
        for (field in FIELDS) {
            for (body in listOf(JsonObject(), JsonObject().put(field, ""), JsonObject().put(field, ACTOR))) {
                val probe = post(field, body)
                assertEquals(200, probe.status, "$field：会话主体必须能通过，实际 body=${probe.body}")
                assertEquals(
                    ACTOR,
                    JsonObject(probe.body).getString("operator"),
                    "$field：请求体=${body.encode()} 时必须采用会话主体",
                )
            }
        }
    }

    @Test
    fun `a platform administrator may delegate every operator field`() {
        session = Session.PLATFORM_ADMIN
        for (field in FIELDS) {
            val probe = post(field, JsonObject().put(field, OTHER))
            assertEquals(200, probe.status, "$field：平台管理员代操作必须放行，实际 body=${probe.body}")
            assertEquals(OTHER, JsonObject(probe.body).getString("operator"), "$field：必须采用管理员指定的值")
        }
    }

    @Test
    fun `a staff session submitting someone else is rejected for every operator field`() {
        for (field in FIELDS) {
            for (requested in listOf(OTHER, "张三")) {
                val probe = post(field, JsonObject().put(field, requested))
                assertEquals(400, probe.status, "$field：非管理员代操作必须 fail-closed，实际 body=${probe.body}")
                assertEquals(
                    "operator cannot be delegated",
                    JsonObject(probe.body).getString("error"),
                    "$field：必须是显式拒绝，而不是被静默改写成会话主体",
                )
            }
        }
    }

    @Test
    fun `without an authenticated session every operator field falls back to the request body`() {
        session = Session.ABSENT
        for (field in FIELDS) {
            val probe = post(field, JsonObject().put(field, "张三"))
            assertEquals(200, probe.status, "$field：无认证挂载必须回落请求体，实际 body=${probe.body}")
            assertEquals("张三", JsonObject(probe.body).getString("operator"))
        }
    }

    @Test
    fun `without an authenticated session and without a value nothing is written`() {
        session = Session.ABSENT
        for (field in FIELDS) {
            val probe = post(field, JsonObject())
            assertEquals(200, probe.status, "$field：实际 body=${probe.body}")
            assertEquals(
                null,
                JsonObject(probe.body).getString("operator"),
                "$field：无会话且未提交时不写该字段（可空列，与改动前\"未提交\"语义一致）",
            )
        }
    }

    private data class Probe(val status: Int, val body: String)

    private fun post(field: String, body: JsonObject): Probe {
        val future: CompletableFuture<Probe> =
            client.request(HttpMethod.POST, PORT, "127.0.0.1", "/probe/$field")
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
