package com.ovaphlow.crate.pharmacy

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * RequisitionService 016 基础数量申领单元测试。
 * 覆盖创建/审批/取消请求白名单与数量校验；不依赖数据库。
 */
class RequisitionServiceTest {

    private lateinit var service: RequisitionService

    @BeforeEach
    fun setUp() {
        service = RequisitionService(mockk<Pool>(), mockk<InventoryRequisitionTransferPort>())
    }

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    private fun createBody(items: JsonArray = JsonArray().add(item())): JsonObject =
        JsonObject()
            .put("warehouse", "西药库")
            .put("destination_warehouse", "一号护理站")
            .put("department", "护理部")
            .put("items", items)

    private fun item(
        materialId: String = "mat-1",
        requestedQuantity: String = "10",
    ): JsonObject =
        JsonObject()
            .put("material_id", materialId)
            .put("requested_quantity", requestedQuantity)

    @Test
    fun `create requires idempotency key`() {
        val error = failureOf(service.create(createBody(), idempotencyKey = null, userId = "user-1"))
        assertTrue(error.message!!.contains("Idempotency-Key"))
    }

    @Test
    fun `create rejects unknown fields`() {
        val body = createBody().put("unit", "PACKAGE")
        val error = failureOf(service.create(body, idempotencyKey = "key-1", userId = "user-1"))
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `create rejects same source and destination warehouse`() {
        val body = createBody()
            .put("warehouse", "西药库")
            .put("destination_warehouse", "西药库")
        val error = failureOf(service.create(body, idempotencyKey = "key-1", userId = "user-1"))
        assertTrue(error is ConflictException)
    }

    @Test
    fun `create rejects duplicate material and non positive quantity`() {
        val duplicate = createBody(
            JsonArray()
                .add(item())
                .add(item(materialId = "mat-1")),
        )
        val nonPositive = createBody(
            JsonArray().add(item(requestedQuantity = "0")),
        )
        assertTrue(failureOf(service.create(duplicate, "key-1", "user-1")).message!!.contains("duplicate"))
        assertTrue(failureOf(service.create(nonPositive, "key-1", "user-1")).message!!.contains("positive"))
    }

    @Test
    fun `approve rejects unknown fields and negative approved quantity`() {
        val unknown = JsonObject(
            """{"items":[{"id":"item-1","approved_quantity":"5","unit":"PACKAGE"}]}""",
        )
        val negative = JsonObject(
            """{"items":[{"id":"item-1","approved_quantity":"-1"}]}""",
        )
        assertTrue(failureOf(service.approve("req-1", unknown, "user-1")).message!!.contains("unknown fields"))
        assertTrue(failureOf(service.approve("req-1", negative, "user-1")).message!!.contains("not be negative"))
    }

    @Test
    fun `approve requires items`() {
        val error = failureOf(service.approve("req-1", JsonObject(), "user-1"))
        assertTrue(error.message!!.contains("items"))
    }

    @Test
    fun `cancel requires reason`() {
        val error = failureOf(service.cancel("req-1", JsonObject(), "user-1"))
        assertTrue(error.message!!.contains("reason"))
    }

    @Test
    fun `create and approve reject numeric JSON quantities`() {
        val create = createBody()
        create.getJsonArray("items").getJsonObject(0).put("requested_quantity", 10)
        val approve = JsonObject("""{"items":[{"id":"item-1","approved_quantity":5}]}""")

        assertTrue(failureOf(service.create(create, "key-1", "user-1")).message!!.contains("decimal text"))
        assertTrue(failureOf(service.approve("req-1", approve, "user-1")).message!!.contains("decimal text"))
    }
}
