package com.ovaphlow.crate.inventories

import io.mockk.mockk
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith

/**
 * InventoriesRoutes 016 手工入库 400 路径的 HTTP 路由单元测试。
 *
 * 校验全部发生在库存服务调用之前，因此使用 mock Pool（任何入库执行都会失败并暴露出来），
 * 不连接数据库。覆盖旧拆零/换算/规格字段 400、数值 JSON 数量/成本 400 与必填校验 400。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoriesRoutesTest {

    companion object {
        private const val TEST_PORT = 18421
        private const val PATH = "/operations/inbound"
    }

    private var server: io.vertx.core.http.HttpServer? = null
    private var client: HttpClient? = null

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        val mockPool = mockk<Pool>()
        val router: Router = InventoriesRoutes.create(vertx, mockPool)
        client = vertx.createHttpClient()
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(TEST_PORT)
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
        client?.close()
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `inbound rejects legacy split fields with 400`(vertx: Vertx, ctx: VertxTestContext) {
        for (field in listOf("split_quantity", "split_ratio", "unit_spec_id", "input_quantity", "conversion_ratio", "unit", "base_quantity", "base_unit")) {
            val body = inboundBody().put("items", JsonArray().add(
                JsonObject()
                    .put("material_id", "mat-1")
                    .put("quantity", "5")
                    .put("unit_cost", "1.25")
                    .put(field, "x"),
            ))
            sendInbound(vertx, ctx, body) { status, json ->
                assertEquals(400, status, "旧字段 $field 应返回 400")
                assertTrue(json.getString("error")!!.contains("unsupported field"), "实际: ${json.getString("error")}")
            }
        }
    }

    @Test
    fun `inbound rejects numeric JSON quantity and unit cost`(vertx: Vertx, ctx: VertxTestContext) {
        sendInbound(vertx, ctx, inboundBody().put("items", JsonArray().add(
            JsonObject().put("material_id", "mat-1").put("quantity", 5).put("unit_cost", "1.25"),
        ))) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("decimal text"), "实际: ${json.getString("error")}")
        }

        sendInbound(vertx, ctx, inboundBody().put("items", JsonArray().add(
            JsonObject().put("material_id", "mat-1").put("quantity", "5").put("unit_cost", 1.25),
        ))) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("decimal text"), "实际: ${json.getString("error")}")
        }
    }

    @Test
    fun `inbound rejects non positive quantity and negative cost`(vertx: Vertx, ctx: VertxTestContext) {
        sendInbound(vertx, ctx, inboundBody().put("items", JsonArray().add(
            JsonObject().put("material_id", "mat-1").put("quantity", "0").put("unit_cost", "1.25"),
        ))) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("positive"), "实际: ${json.getString("error")}")
        }

        sendInbound(vertx, ctx, inboundBody().put("items", JsonArray().add(
            JsonObject().put("material_id", "mat-1").put("quantity", "5").put("unit_cost", "-1"),
        ))) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("unit_cost"), "实际: ${json.getString("error")}")
        }
    }

    @Test
    fun `inbound rejects missing warehouse items and material id`(vertx: Vertx, ctx: VertxTestContext) {
        sendInbound(vertx, ctx, JsonObject().put("items", JsonArray().add(
            JsonObject().put("material_id", "mat-1").put("quantity", "5").put("unit_cost", "1.25"),
        ))) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("warehouse"), "实际: ${json.getString("error")}")
        }

        sendInbound(vertx, ctx, JsonObject().put("warehouse", "一号护理站")) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("item"), "实际: ${json.getString("error")}")
        }

        sendInbound(vertx, ctx, inboundBody().put("items", JsonArray().add(
            JsonObject().put("quantity", "5").put("unit_cost", "1.25"),
        ))) { status, json ->
            assertEquals(400, status)
            assertTrue(json.getString("error")!!.contains("material_id"), "实际: ${json.getString("error")}")
        }
    }

    @Test
    fun `health endpoint returns ok without touching database`(vertx: Vertx, ctx: VertxTestContext) {
        client!!.request(HttpMethod.GET, TEST_PORT, "localhost", "/health")
            .compose { it.send() }
            .onComplete { ar ->
                if (ar.failed()) {
                    ctx.failNow(ar.cause())
                    return@onComplete
                }
                val resp = ar.result()
                resp.body().onSuccess { body ->
                    ctx.verify {
                        assertEquals(200, resp.statusCode())
                        val json = JsonObject(body)
                        assertEquals("ok", json.getString("status"))
                        ctx.completeNow()
                    }
                }.onFailure { ctx.failNow(it) }
            }
    }

    private fun inboundBody(): JsonObject =
        JsonObject()
            .put("warehouse", "一号护理站")
            .put("items", JsonArray().add(
                JsonObject()
                    .put("material_id", "mat-1")
                    .put("quantity", "5")
                    .put("unit_cost", "1.25"),
            ))

    private fun sendInbound(
        vertx: Vertx,
        ctx: VertxTestContext,
        body: JsonObject,
        onResponse: (Int, JsonObject) -> Unit,
    ) {
        client!!.request(HttpMethod.POST, TEST_PORT, "localhost", PATH)
            .compose { req ->
                req.putHeader("Content-Type", "application/json").send(body.encode())
            }
            .onComplete { ar ->
                if (ar.failed()) {
                    ctx.failNow(ar.cause())
                    return@onComplete
                }
                val resp = ar.result()
                resp.body().onSuccess { buf ->
                    ctx.verify {
                        onResponse(resp.statusCode(), JsonObject(buf))
                        ctx.completeNow()
                    }
                }.onFailure { ctx.failNow(it) }
            }
    }
}
