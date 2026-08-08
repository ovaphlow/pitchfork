package com.ovaphlow.crate.pharmacy

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * PurchaseOrderService 016 基础数量采购单元测试。
 * 覆盖订单/收货请求白名单、数量与成本校验；不依赖数据库。
 */
class PurchaseOrderServiceTest {

    private lateinit var service: PurchaseOrderService

    @BeforeEach
    fun setUp() {
        service = PurchaseOrderService(mockk<Pool>(), mockk<InventoryPurchaseReceiptPort>())
    }

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    private fun orderBody(): JsonObject =
        JsonObject()
            .put("warehouse", "西药库")
            .put("supplier_name", "测试供应商")
            .put(
                "items",
                JsonArray().add(
                    JsonObject()
                        .put("material_id", "mat-1")
                        .put("ordered_quantity", 100),
                ),
            )

    private fun receiptBody(): JsonObject =
        JsonObject()
            .put(
                "items",
                JsonArray().add(
                    JsonObject()
                        .put("purchase_order_item_id", "poi-1")
                        .put("received_quantity", 10)
                        .put("unit_cost", "1.25"),
                ),
            )

    @Test
    fun `create requires idempotency key`() {
        val error = failureOf(service.create(orderBody(), idempotencyKey = null, userId = "user-1"))
        assertTrue(error.message!!.contains("Idempotency-Key"))
    }

    @Test
    fun `create rejects unknown fields`() {
        val body = orderBody().put("unit", "PACKAGE")
        val error = failureOf(service.create(body, "key-1", "user-1"))
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `create rejects duplicate material and non positive ordered quantity`() {
        val duplicate = orderBody().put(
            "items",
            JsonArray()
                .add(JsonObject().put("material_id", "mat-1").put("ordered_quantity", 1))
                .add(JsonObject().put("material_id", "mat-1").put("ordered_quantity", 1)),
        )
        val nonPositive = orderBody().put(
            "items",
            JsonArray().add(JsonObject().put("material_id", "mat-1").put("ordered_quantity", 0)),
        )
        assertTrue(failureOf(service.create(duplicate, "key-1", "user-1")).message!!.contains("duplicate"))
        assertTrue(failureOf(service.create(nonPositive, "key-1", "user-1")).message!!.contains("positive"))
    }

    @Test
    fun `updateDraft validates body without idempotency`() {
        val error = failureOf(service.updateDraft("po-1", JsonObject().put("unit", "PACKAGE"), "user-1"))
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `receive requires idempotency key`() {
        val error = failureOf(service.receive("po-1", receiptBody(), idempotencyKey = null, userId = "user-1"))
        assertTrue(error.message!!.contains("Idempotency-Key"))
    }

    @Test
    fun `receive rejects unknown receipt fields`() {
        val body = JsonObject(
            """{"items":[{"purchase_order_item_id":"poi-1","received_quantity":10,"unit_cost":"1.25","unit":"PACKAGE"}]}""",
        )
        val error = failureOf(service.receive("po-1", body, "key-1", "user-1"))
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `receive rejects non positive quantity and negative cost`() {
        val nonPositive = receiptBody()
        nonPositive.getJsonArray("items").getJsonObject(0).put("received_quantity", 0)
        val negativeCost = receiptBody()
        negativeCost.getJsonArray("items").getJsonObject(0).put("unit_cost", -1)
        assertTrue(failureOf(service.receive("po-1", nonPositive, "key-1", "user-1")).message!!.contains("positive"))
        assertTrue(failureOf(service.receive("po-1", negativeCost, "key-1", "user-1")).message!!.contains("not be negative"))
    }
}
