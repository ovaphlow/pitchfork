package com.ovaphlow.crate.pharmacy

import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ReturnService 016 基础数量退药单元测试。
 * 覆盖退药创建请求校验；不依赖数据库。
 */
class ReturnServiceTest {

    private lateinit var service: ReturnService

    @BeforeEach
    fun setUp() {
        service = ReturnService(mockk<Pool>(), mockk<InventoryInboundPort>())
    }

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    @Test
    fun `createFromDispense rejects missing dispense id`() {
        val error = failureOf(service.createFromDispense(JsonObject()))
        assertTrue(error.message!!.contains("dispense_id"))
    }

    @Test
    fun `createFromDispense rejects missing reason and operator`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1"),
            ),
        )
        assertTrue(error.message!!.contains("return_reason"))
    }

    @Test
    fun `createFromDispense rejects non positive quantity`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1")
                    .put("return_reason", "不良反应")
                    .put("operator", "user-1")
                    .put("quantity", 0)
                    .put("restockable", true),
            ),
        )
        assertTrue(error.message!!.contains("quantity"))
    }

    @Test
    fun `createFromDispense requires restockable confirmation`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1")
                    .put("return_reason", "不良反应")
                    .put("operator", "user-1")
                    .put("quantity", 2),
            ),
        )
        assertTrue(error.message!!.contains("restockable"))
    }

    @Test
    fun `confirm requires operator`() {
        val error = failureOf(service.confirm("return-1", JsonObject()))
        assertTrue(error.message!!.contains("operator"))
    }
}
