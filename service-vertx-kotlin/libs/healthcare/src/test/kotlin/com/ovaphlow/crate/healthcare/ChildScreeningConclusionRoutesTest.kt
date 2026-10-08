package com.ovaphlow.crate.healthcare

import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * 053 A3 筛查结论判读路由接线测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. 纯规则 `canRefer`：只有「可疑/异常」可转诊，`正常`/`null` 为 false；
 *  2. 写路由 `POST /child-developmental-screenings/:id/conclusion` 的 `userId(ctx)` 401 兜底；
 *  3. 缺 `conclusion` → 400（服务端白名单校验，进库前拒绝）；
 *  4. 多余键（如 `classification`）→ 400（客户端不得伪造判读派生字段）。
 *
 * 用 relaxed `Pool` 桩构造模块 Router；三个断言都在**进库前**失败，故无需驱动任何 SQL。
 * **不引用 jOOQ**。集成语义（checkup_id 归属校验、回读）由真库测试覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildScreeningConclusionRoutesTest {

    @Test
    fun `可疑与异常可转诊，正常与空不可`() {
        assertTrue(ChildScreeningService.canRefer("可疑"), "可疑必须可转诊")
        assertTrue(ChildScreeningService.canRefer("异常"), "异常必须可转诊")
        assertFalse(ChildScreeningService.canRefer("正常"), "正常不得转诊")
        assertFalse(ChildScreeningService.canRefer(null), "空判读不得转诊")
        assertEquals(
            setOf("可疑", "异常"),
            ChildScreeningService.referableClassifications,
            "可转诊判读白名单必须恰为「可疑/异常」",
        )
    }

    @Test
    fun `判读写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"conclusion":"可疑，建议转诊"}""")
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "未认证必须 401（判读写路由兜底不得裸奔）: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `缺 conclusion 返回 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"checkup_id":"c1"}""")
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "缺 conclusion 必须 400: $payload")
                        assertEquals("conclusion is required", payload.getString("error"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `多余键返回 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"conclusion":"可疑","classification":"异常"}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "多余键必须 400，不得静默落库: $payload")
                    assertTrue(
                        payload.getString("error")
                            ?.contains("unsupported developmental screening conclusion keys") == true,
                        "错误应指出多余键: $payload",
                    )
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/child-developmental-screenings/s1/conclusion"
    }

    private fun <T> withServer(
        vertx: Vertx,
        userId: String?,
        block: (Int) -> Future<T>,
    ): Future<T> {
        val pool: Pool = mockk(relaxed = true)
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose { value -> server.close().map(value) }
        }
    }

    private fun rawRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        rawBody: String?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (rawBody != null) req.putHeader("Content-Type", "application/json").send(rawBody) else req.send()
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer)
                    } catch (_: RuntimeException) {
                        JsonObject()
                    }
                    resp.statusCode() to json
                }
            }
            .onComplete { client.close() }
    }
}
