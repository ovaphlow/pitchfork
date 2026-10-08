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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * 053 B1 儿保体检诊断路由接线测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. 写路由 `userId(ctx)` 401 兜底（App 全局认证总闸之外的保底）；
 *  2. 缺 `diagnosis` 在**进库前**被服务白名单拒绝 → 400；
 *  3. 客户端提交服务端派生字段（`reviewed_by` / `status` / `reviewed_at`）→ 400（多余键）；
 *  4. 诊断状态白名单与 V532 注释一致（待诊断/已完成，单向流转）。
 *
 * 校验类请求在进库前被拒，故用 relaxed `Pool` 桩，**不引用 jOOQ**。
 * 集中式权限声明与门禁接线由 `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildCheckupDiagnosisRoutesTest {

    /** 校验失败发生在 `pool.withTransaction` 之前，故 relaxed 桩即可，无需驱动任何 SQL。 */
    private val pool: Pool = mockk(relaxed = true)

    @Test
    fun `诊断写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"diagnosis":"上呼吸道感染"}""")
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "未认证必须 401（写路由兜底不得裸奔）: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `缺 diagnosis 在进库前被拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"diagnosis_code":"J06.9"}""")
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "缺 diagnosis 必须 400: $payload")
                        assertEquals("diagnosis is required", payload.getString("error"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `客户端提交服务端派生字段被拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            val cases = listOf(
                """{"diagnosis":"上呼吸道感染","reviewed_by":"user-9"}""",
                """{"diagnosis":"上呼吸道感染","status":"待诊断"}""",
                """{"diagnosis":"上呼吸道感染","reviewed_at":"2026-10-06T00:00:00+08:00"}""",
            )
            var chain: Future<Unit> = Future.succeededFuture()
            cases.forEach { requestBody ->
                chain = chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, PATH, requestBody).map { (status, payload) ->
                        ctx.verify {
                            assertEquals(400, status, "客户端提交派生字段必须 400: $requestBody -> $payload")
                            assertTrue(
                                payload.getString("error")?.contains("unsupported") == true,
                                "必须是白名单拒绝（unsupported ... keys）: $payload",
                            )
                        }
                        Unit
                    }
                }
            }
            chain
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `诊断状态白名单为待诊断与已完成`() {
        assertEquals(
            setOf("待诊断", "已完成"),
            ChildHealthCheckupService.checkupStatuses,
            "诊断状态白名单必须与 V532 注释一致（应用层管控，单向流转）",
        )
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/child-health-checkups/c1/diagnosis"
    }

    private fun <T> withServer(
        vertx: Vertx,
        userId: String?,
        block: (Int) -> Future<T>,
    ): Future<T> {
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
