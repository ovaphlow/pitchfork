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
 * 052 儿保路由接线测试（**不访问数据库**）。
 *
 * 锁三件事：
 *  1. 九条新增写路由的 `userId(ctx)` 401 兜底（App 全局认证总闸之外的保底）；
 *  2. `GET /child-guidance-templates` 完全走常量表 → 无库也能 200（证明路由真的挂上、
 *     且不因缺少 DB 而失败）；
 *  3. 指导记录的模板白名单校验在**进库之前**返回 400（不产生无来源的指导记录）。
 *
 * 服务层业务规则由 `ChildHealthAgeTest` / `ChildVitalThresholdsTest` /
 * `ChildVaccinationScheduleTest` / `ChildGuidanceTemplateTest` 的纯函数用例覆盖；
 * 声明与集中式门禁的接线由 `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildHealthRoutesTest {

    private val writeRoutes = listOf(
        Triple(HttpMethod.POST, "/healthcare/v1/child-health-checkups", """{"patient_id":"p1"}"""),
        Triple(HttpMethod.PATCH, "/healthcare/v1/child-health-checkups/c1", """{"conclusion":"正常"}"""),
        Triple(HttpMethod.POST, "/healthcare/v1/child-growth-measurements", """{"patient_id":"p1","height_cm":70}"""),
        Triple(HttpMethod.PATCH, "/healthcare/v1/child-growth-measurements/m1", """{"height_cm":71}"""),
        Triple(HttpMethod.DELETE, "/healthcare/v1/child-growth-measurements/m1", null),
        Triple(HttpMethod.POST, "/healthcare/v1/child-vaccinations", """{"patient_id":"p1","vaccine_code":"BCG"}"""),
        Triple(HttpMethod.POST, "/healthcare/v1/child-vaccination-status/p1/remind", """{}"""),
        Triple(HttpMethod.POST, "/healthcare/v1/child-health-notifications", """{"patient_id":"p1","alert_type":"体温异常","content":"x"}"""),
        Triple(HttpMethod.POST, "/healthcare/v1/child-guidance-records", """{"patient_id":"p1","template_code":"FEED_0_6"}"""),
    )

    @Test
    fun `儿保写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            val calls = writeRoutes.map { (method, path, body) ->
                rawRequest(vertx, port, method, path, body).map { (status, payload) -> Triple(path, status, payload) }
            }
            Future.all(calls).map { composite ->
                val results = (0 until writeRoutes.size).map { composite.resultAt<Triple<String, Int, JsonObject>>(it) }
                ctx.verify {
                    results.forEach { (path, status, payload) ->
                        assertEquals(401, status, "$path 未认证必须 401（写路由兜底不得裸奔）: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                    }
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `指导模板读路由无库也返回常量表`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/child-guidance-templates?age_months=3", null)
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(200, status, "模板路由必须命中: $payload")
                        val records = payload.getJsonArray("records")
                        assertTrue(records.size() > 0, "3 月龄必须有推荐模板")
                        assertEquals("0-6月", payload.getJsonObject("meta").getString("age_band"))
                        val first = records.getJsonObject(0)
                        assertEquals("0-6月", first.getString("age_band"))
                        assertTrue(first.getString("content")?.isNotBlank() == true)
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `未知指导模板在进库前被拒 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/healthcare/v1/child-guidance-records",
                """{"patient_id":"p1","template_code":"NOT_A_TEMPLATE"}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "未知模板必须 400，不得静默落库: $payload")
                    assertTrue(payload.getString("error")?.contains("content is required") == true)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `接种登记的疫苗白名单在进库前生效 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/healthcare/v1/child-vaccinations",
                """{"patient_id":"p1","vaccine_code":"NOT_A_VACCINE"}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "非免疫规划疫苗必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("unsupported vaccine_code") == true)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
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
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, mockk<Pool>(relaxed = true)))
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
