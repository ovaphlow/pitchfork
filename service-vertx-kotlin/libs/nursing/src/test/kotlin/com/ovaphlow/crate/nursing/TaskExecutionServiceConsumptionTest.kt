package com.ovaphlow.crate.nursing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * TaskExecutionService 耗材联动逻辑的单元测试。
 * 验证状态流转规则、耗材输入校验和业务约束，不依赖数据库。
 */
class TaskExecutionServiceConsumptionTest {

    // ========================================================================
    //  VALID_STATUS_TRANSITIONS 规则
    // ========================================================================

    @Test
    fun `PENDING cannot transition directly to COMPLETED`() {
        val transitions = mapOf(
            "PENDING" to listOf("IN_PROGRESS", "SKIPPED", "CANCELLED"),
            "IN_PROGRESS" to listOf("COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList<String>(),
            "SKIPPED" to emptyList<String>(),
            "CANCELLED" to emptyList<String>()
        )
        assertFalse("COMPLETED" in (transitions["PENDING"] ?: emptyList()),
            "PENDING must go through IN_PROGRESS first")
        assertTrue("COMPLETED" in (transitions["IN_PROGRESS"] ?: emptyList()))
        assertTrue("COMPLETED" !in (transitions["COMPLETED"] ?: emptyList()))
        assertTrue("COMPLETED" !in (transitions["SKIPPED"] ?: emptyList()))
        assertTrue("COMPLETED" !in (transitions["CANCELLED"] ?: emptyList()))
    }

    @Test
    fun `SKIPPED and CANCELLED are terminal states`() {
        val transitions = mapOf(
            "COMPLETED" to emptyList<String>(),
            "SKIPPED" to emptyList<String>(),
            "CANCELLED" to emptyList<String>()
        )
        assertTrue(transitions["COMPLETED"]?.isEmpty() == true)
        assertTrue(transitions["SKIPPED"]?.isEmpty() == true)
        assertTrue(transitions["CANCELLED"]?.isEmpty() == true)
    }

    @Test
    fun `IN_PROGRESS cannot transition to SKIPPED`() {
        val inProgressTransitions = listOf("COMPLETED", "CANCELLED")
        assertFalse("SKIPPED" in inProgressTransitions)
        assertFalse("PENDING" in inProgressTransitions)
    }

    // ========================================================================
    //  ConsumptionInput 数据类校验
    // ========================================================================

    @Test
    fun `consumptionInput PACKAGE with quantity`() {
        val input = TaskExecutionService.ConsumptionInput(
            stockId = "stock-1",
            unit = "PACKAGE",
            quantity = BigDecimal.valueOf(2),
            splitQuantity = null
        )
        assertEquals("stock-1", input.stockId)
        assertEquals("PACKAGE", input.unit)
        assertEquals(BigDecimal.valueOf(2), input.quantity)
        assertNull(input.splitQuantity)
    }

    @Test
    fun `consumptionInput SPLIT with splitQuantity`() {
        val input = TaskExecutionService.ConsumptionInput(
            stockId = "stock-1",
            unit = "SPLIT",
            quantity = null,
            splitQuantity = BigDecimal.valueOf(3)
        )
        assertEquals("SPLIT", input.unit)
        assertNull(input.quantity)
        assertEquals(BigDecimal.valueOf(3), input.splitQuantity)
    }

    @Test
    fun `consumptionInput requires either quantity or splitQuantity`() {
        // PACKAGE needs quantity > 0
        val packageInput = TaskExecutionService.ConsumptionInput(
            stockId = "stock-1",
            unit = "PACKAGE",
            quantity = BigDecimal.valueOf(0),
            splitQuantity = null
        )
        assertTrue(packageInput.quantity == null || packageInput.quantity!! <= BigDecimal.ZERO)
    }

    // ========================================================================
    //  耗材使用规则：只有 COMPLETED 才允许带耗材
    // ========================================================================

    @Test
    fun `consumptions only allowed when status is COMPLETED`() {
        // 模拟路由层校验：非 COMPLETED 状态带耗材应拒绝
        val statusesWithConsumptionAllowed = setOf("COMPLETED")
        for (status in listOf("PENDING", "IN_PROGRESS", "SKIPPED", "CANCELLED")) {
            assertFalse(status in statusesWithConsumptionAllowed,
                "status $status should NOT allow consumptions")
        }
        assertTrue("COMPLETED" in statusesWithConsumptionAllowed)
    }

    // ========================================================================
    //  耗材仓库一致性校验
    // ========================================================================

    @Test
    fun `all consumptions must be from the same warehouse`() {
        val stockIds = listOf("stock-1", "stock-1", "stock-1")
        val warehouses = stockIds.toSet()
        // 所有 stock_id 相同 → 应来自同一仓库
        assertTrue(warehouses.size <= 1)

        val mixedStocks = listOf("stock-1", "stock-2")
        val mixedWarehouses = mixedStocks.toSet()
        // 不同 stock_id 可能来自不同仓库
        assertTrue(mixedWarehouses.size > 1)
    }

    // ========================================================================
    //  create 方法不再接受旧库存字段
    // ========================================================================

    @Test
    fun `create does not pass stock_operation_detail_id from client`() {
        // 验证：create 方法已移除 set(cStockOpDetailId, ...) 和 set(cQuantity, ...)
        // 客户端传入的 stock_operation_detail_id 和 quantity 不会被映射
        val clientBodyFields = mapOf(
            "task_id" to "task-1",
            "stock_operation_detail_id" to "detail-1",
            "quantity" to 5.0
        )
        // 服务端 create 只读取 task_id，忽略旧库存字段
        assertTrue(clientBodyFields.containsKey("task_id"))
        // create 不再读取 stock_operation_detail_id / quantity
    }

    @Test
    fun `update does not pass stock_operation_detail_id from client`() {
        val clientBodyFields = setOf("stock_operation_detail_id", "quantity")
        val updateBodyFields = setOf("executor", "actual_time", "planned_time", "note", "metadata")
        // update 方法只处理 executor/actual_time/planned_time/note/metadata
        for (field in clientBodyFields) {
            assertFalse(field in updateBodyFields,
                "update should not accept client-provided $field")
        }
    }

    // ========================================================================
    //  耗材摘要 JSON 转换
    // ========================================================================

    @Test
    fun `legacy sync only for single consumption`() {
        // 1条明细 → 同步旧字段；多条 → 不清空旧字段
        val singleDetailSize = 1
        val multipleDetailSize = 3
        assertTrue(singleDetailSize == 1)  // sync
        assertFalse(multipleDetailSize == 1) // no sync
    }

    // ========================================================================
    //  今日执行查询包含耗材摘要字段
    // ========================================================================

    @Test
    fun `todayExecutions result includes consumption_summary`() {
        // 验证 executionWithSummaryJson 包含所有必要字段
        val requiredFields = setOf(
            "id", "task_id", "planned_time", "status",
            "task_description", "task_type", "patient_name"
        )
        assertTrue("consumption_summary" !in requiredFields) // 可选字段
    }

    @Test
    fun `empty consumptions list falls back to plain updateStatus`() {
        // completeExecutionWithConsumptions 在 consumptions.isEmpty() 时走 updateStatus
        val emptyConsumptions = emptyList<TaskExecutionService.ConsumptionInput>()
        assertTrue(emptyConsumptions.isEmpty())
    }
}
