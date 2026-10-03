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
 *
 * 042：操作人不再是请求体字段，而是路由层从认证会话派生后传入的参数
 * （`PharmacyRoutes.withOperator`）；本类以参数形式提供它，并单独锁定
 * "请求体里的 operator 不再具有权威性"。
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
        val error = failureOf(service.createFromDispense(JsonObject(), null))
        assertTrue(error.message!!.contains("dispense_id"))
    }

    @Test
    fun `createFromDispense rejects missing reason`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1"),
                null,
            ),
        )
        assertTrue(error.message!!.contains("return_reason"))
    }

    @Test
    fun `createFromDispense ignores a body operator when no resolved operator is passed`() {
        // 042：请求体里的 operator 只是"请求"，权威值由路由层从会话派生后以参数传入。
        // 因此即使请求体带着 operator，参数为 null 时服务端仍必须拒绝——否则非管理员提交他人
        // 就能绕过 withOperator 的代操作门禁直接落库。
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1")
                    .put("return_reason", "不良反应")
                    .put("operator", "他人")
                    .put("quantity", "1")
                    .put("restockable", true),
                null,
            ),
        )
        assertTrue(error.message!!.contains("operator"), "got: ${error.message}")
    }

    @Test
    fun `createFromDispense rejects non positive quantity`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1")
                    .put("return_reason", "不良反应")
                    .put("quantity", "0")
                    .put("restockable", true),
                "user-1",
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
                    .put("quantity", "2"),
                "user-1",
            ),
        )
        assertTrue(error.message!!.contains("restockable"))
    }

    @Test
    fun `createFromDispense 拒绝超过6位小数的退药数量`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1")
                    .put("return_reason", "不良反应")
                    .put("quantity", "0.1234567")
                    .put("restockable", true),
                "user-1",
            ),
        )
        assertTrue(error.message!!.contains("6 decimals"), "got: ${error.message}")
    }

    @Test
    fun `confirm requires operator`() {
        val error = failureOf(service.confirm("return-1", JsonObject(), null))
        assertTrue(error.message!!.contains("operator"))
    }

    @Test
    fun `createFromDispense rejects numeric JSON quantity`() {
        val error = failureOf(
            service.createFromDispense(
                JsonObject()
                    .put("dispense_id", "d-1")
                    .put("dispense_item_id", "di-1")
                    .put("return_reason", "不良反应")
                    .put("quantity", 2)
                    .put("restockable", true),
                "user-1",
            ),
        )
        assertTrue(error.message!!.contains("decimal text"))
    }
}
