package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BaseUnitCutoverIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "bu-"
    override val serverPort = 18516

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'bu-mat-1', '基础单位测试片剂', '药品', '片', 0, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() = cleanupAll(fixturePrefix)

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '${fixturePrefix}%' OR code LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM public.stocks WHERE id LIKE '${fixturePrefix}%' OR material_id LIKE '${fixturePrefix}%'") == 0L)
    }

    @Test
    fun `基础单位物资入库按base_unit记账并拒绝旧换算字段`(vertx: Vertx, ctx: VertxTestContext) {
        val inboundBody = JsonObject()
            .put("warehouse", "主库")
            .put("note", "bu-cleanup")
            .put(
                "items",
                JsonArray().add(
                    JsonObject()
                        .put("material_id", id("mat"))
                        .put("quantity", "48")
                        .put("unit_cost", "1.25"),
                ),
            )
        request(vertx, HttpMethod.POST, "/inventories/v1/operations/inbound", inboundBody)
            .compose { (status, body) ->
                ctx.verify { assertEquals(200, status, "入库应 200: ${body.encode()}") }
                io.vertx.core.Future.future<Unit> { promise ->
                    val qty = countRows("SELECT quantity FROM public.stocks WHERE material_id = '${id("mat")}' AND warehouse = '主库'")
                    val totalCost = countRows("SELECT total_cost FROM public.stocks WHERE material_id = '${id("mat")}' AND warehouse = '主库'")
                    val unit = countRows("SELECT count(*) FROM public.stock_operation_details d JOIN public.stock_operations o ON d.operation_id = o.id WHERE d.material_id = '${id("mat")}' AND d.unit = '片'")
                    ctx.verify {
                        assertEquals(48L, qty)
                        assertEquals(60L, totalCost)
                        assertEquals(1L, unit)
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
