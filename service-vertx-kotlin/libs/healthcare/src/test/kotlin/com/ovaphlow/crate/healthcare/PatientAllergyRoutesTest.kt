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
 * 053 D1 药物过敏史路由接线测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. 写路由 `userId(ctx)` 401 兜底（App 全局认证总闸之外的保底）：POST / PATCH / DELETE；
 *  2. 非法 `allergen_type` / `severity` 在**进库前**由服务白名单拒绝 → 400；
 *  3. 缺 `allergen_name` → 400；多余键（如派生字段 `status`）→ 400；
 *  4. PATCH 的枚举校验同样在进库前拒绝 → 400。
 *
 * 校验全部发生在 `parseCreate` / `parseUpdate`（同步、`Future` 之前），因此这里用 relaxed
 * mockk `Pool` 即可——**合法输入之外的请求根本不触库**。**不引用 jOOQ**。
 *
 * 枚举白名单本身的完整语义由服务与真库集成覆盖；声明与集中式门禁接线由 `apps/aceso` 的
 * `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class PatientAllergyRoutesTest {

    private val pool: Pool = mockk(relaxed = true)

    @Test
    fun `过敏写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        val cases: List<Triple<HttpMethod, String, String?>> = listOf(
            Triple(
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","allergen_type":"药物","allergen_name":"青霉素"}""",
            ),
            Triple(HttpMethod.PATCH, "$PATH/a1", """{"severity":"严重"}"""),
            Triple(HttpMethod.DELETE, "$PATH/a1", null),
        )
        withServer(vertx, userId = null) { port ->
            cases.fold(Future.succeededFuture<Unit>()) { acc, (method, path, requestBody) ->
                acc.compose {
                    rawRequest(vertx, port, method, path, requestBody).map { (status, payload) ->
                        ctx.verify {
                            assertEquals(401, status, "$method $path 未认证必须 401（写路由兜底不得裸奔）: $payload")
                            assertEquals("authentication required", payload.getString("error"))
                        }
                        Unit
                    }
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `非法 allergen_type 在进库前 400`(vertx: Vertx, ctx: VertxTestContext) {
        expectBadRequest(
            vertx,
            ctx,
            HttpMethod.POST,
            PATH,
            """{"patient_id":"p1","allergen_type":"吸入","allergen_name":"花粉"}""",
            "invalid allergen_type",
        ).onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `非法 severity 在进库前 400`(vertx: Vertx, ctx: VertxTestContext) {
        expectBadRequest(
            vertx,
            ctx,
            HttpMethod.POST,
            PATH,
            """{"patient_id":"p1","allergen_type":"药物","allergen_name":"青霉素","severity":"致命"}""",
            "invalid severity",
        ).onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `缺 allergen_name 400`(vertx: Vertx, ctx: VertxTestContext) {
        expectBadRequest(
            vertx,
            ctx,
            HttpMethod.POST,
            PATH,
            """{"patient_id":"p1","allergen_type":"药物"}""",
            "allergen_name is required",
        ).onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `多余键 status 在进库前 400`(vertx: Vertx, ctx: VertxTestContext) {
        // status 是服务端派生字段，客户端提交即 400。
        expectBadRequest(
            vertx,
            ctx,
            HttpMethod.POST,
            PATH,
            """{"patient_id":"p1","allergen_type":"药物","allergen_name":"青霉素","status":"有效"}""",
            "unsupported patient allergy keys: status",
        ).onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `PATCH 非法 severity 在进库前 400`(vertx: Vertx, ctx: VertxTestContext) {
        expectBadRequest(
            vertx,
            ctx,
            HttpMethod.PATCH,
            "$PATH/a1",
            """{"severity":"致命"}""",
            "invalid severity",
        ).onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/patient-allergies"
    }

    private fun expectBadRequest(
        vertx: Vertx,
        ctx: VertxTestContext,
        method: HttpMethod,
        path: String,
        rawBody: String,
        expectedErrorFragment: String,
    ): Future<Unit> =
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(vertx, port, method, path, rawBody).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "$method $path 必须 400（进库前拒绝）: $payload")
                    assertTrue(
                        payload.getString("error")?.contains(expectedErrorFragment) == true,
                        "错误文案应含「$expectedErrorFragment」，实际：$payload",
                    )
                }
                Unit
            }
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
