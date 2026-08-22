package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PurchaseOrderIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "po-"
    override val serverPort = 18514

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'PO-MAT', '采购测试药品', '药品', '片', 0, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() = cleanupAll(fixturePrefix)

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_purchase_receipts WHERE id LIKE '${fixturePrefix}%'") == 0L)
    }

    @Test
    fun `采购审核收货入库并生成采购入库流水`(vertx: Vertx, ctx: VertxTestContext) {
        var orderId: String? = null
        var orderItemId: String? = null
        val createBody = JsonObject()
            .put("warehouse", "主库")
            .put("supplier_name", "测试供应商")
            .put(
                "items",
                JsonArray().add(
                    JsonObject()
                        .put("material_id", id("mat"))
                        .put("ordered_quantity", "100"),
                ),
            )
        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/purchase-orders/",
            createBody,
            mapOf("Idempotency-Key" to "${fixturePrefix}key-1"),
        )
            .compose { (status, result) ->
                ctx.verify {
                    assertEquals(201, status, "创建采购订单应 201: ${result.encode()}")
                    orderId = result.getString("id")
                    orderItemId = result.getJsonArray("items").getJsonObject(0).getString("id")
                    assertNotNull(orderId)
                    assertNotNull(orderItemId)
                }
                request(vertx, HttpMethod.PUT, "/pharmacy/v1/purchase-orders/$orderId/approve", JsonObject())
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                val receiptBody = JsonObject().put(
                    "items",
                    JsonArray().add(
                        JsonObject()
                            .put("purchase_order_item_id", orderItemId)
                            .put("received_quantity", "100")
                            .put("unit_cost", "2.5"),
                    ),
                )
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/purchase-orders/$orderId/receipts",
                    receiptBody,
                    mapOf("Idempotency-Key" to "${fixturePrefix}rcv-key-1"),
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(201, status) }
                io.vertx.core.Future.future<Unit> { promise ->
                    val stockQty = countRows("SELECT quantity FROM public.stocks WHERE material_id = '${id("mat")}' AND warehouse = '主库'")
                    val stockCost = countRows("SELECT total_cost FROM public.stocks WHERE material_id = '${id("mat")}' AND warehouse = '主库'")
                    val ops = countRows(
                        "SELECT count(*) FROM public.stock_operation_details d JOIN public.stock_operations o ON d.operation_id = o.id WHERE (o.id LIKE '${fixturePrefix}%' OR o.metadata::text LIKE '%${fixturePrefix}%' OR d.material_id LIKE '${fixturePrefix}%') AND o.operation_type = 'INBOUND'",
                    )
                    val receipts = countRows("SELECT count(*) FROM pharmacy.pharmacy_purchase_receipts WHERE purchase_order_id = '$orderId'")
                    ctx.verify {
                        assertEquals(100L, stockQty)
                        assertEquals(250L, stockCost)
                        assertEquals(1L, ops)
                        assertEquals(1L, receipts)
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
