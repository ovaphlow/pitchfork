package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class RequisitionIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "rq-"
    override val serverPort = 18513

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'RQ-MAT', '测试药品', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot")}', '${id("mat")}', 'RQ-LOT-1', CURRENT_DATE + 30)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost)
            VALUES ('${id("src")}', '主库', '${id("mat")}', '${id("lot")}', 100, 0, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() = cleanupAll(fixturePrefix)

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_requisitions WHERE id LIKE '${fixturePrefix}%'") == 0L)
    }

    @Test
    fun `申领审批调拨扣减源库存并增加目标库存`(vertx: Vertx, ctx: VertxTestContext) {
        var requisitionId: String? = null
        val createBody = JsonObject()
            .put("warehouse", "主库")
            .put("destination_warehouse", "护理站-1")
            .put("department", "护理站-1")
            .put(
                "items",
                JsonArray().add(
                    JsonObject()
                        .put("material_id", id("mat"))
                        .put("requested_quantity", "20"),
                ),
            )
        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/requisitions/",
            createBody,
            mapOf("Idempotency-Key" to "${fixturePrefix}key-1"),
        )
            .compose { (status, result) ->
                ctx.verify {
                    assertEquals(201, status, "创建申领单应 201: ${result.encode()}")
                    requisitionId = result.getString("id")
                    assertNotNull(requisitionId)
                }
                val itemId = result.getJsonArray("items").getJsonObject(0).getString("id")
                val approveBody = JsonObject().put(
                    "items",
                    JsonArray().add(
                        JsonObject()
                            .put("id", itemId)
                            .put("approved_quantity", "20")
                            .put("lot_id", id("lot")),
                    ),
                )
                request(
                    vertx,
                    HttpMethod.PUT,
                    "/pharmacy/v1/requisitions/$requisitionId/approve",
                    approveBody,
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                request(vertx, HttpMethod.PUT, "/pharmacy/v1/requisitions/$requisitionId/dispense", JsonObject())
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                io.vertx.core.Future.future<Unit> { promise ->
                    val srcQty = countRows("SELECT quantity FROM public.stocks WHERE id = '${id("src")}'")
                    val srcLocked = countRows("SELECT locked_quantity FROM public.stocks WHERE id = '${id("src")}'")
                    val dstQty = countRows("SELECT quantity FROM public.stocks WHERE warehouse = '护理站-1' AND material_id = '${id("mat")}' AND lot_id = '${id("lot")}'")
                    val ops = countRows(
                        "SELECT count(*) FROM public.stock_operations o WHERE o.id LIKE '${fixturePrefix}%' OR o.metadata::text LIKE '%${fixturePrefix}%' OR EXISTS (SELECT 1 FROM public.stock_operation_details d WHERE d.operation_id = o.id AND d.material_id LIKE '${fixturePrefix}%')",
                    )
                    ctx.verify {
                        assertEquals(80L, srcQty)
                        assertEquals(0L, srcLocked)
                        assertEquals(20L, dstQty)
                        assertEquals(2L, ops, "应生成源 OUTBOUND 和目标 INBOUND 两笔库存操作")
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
