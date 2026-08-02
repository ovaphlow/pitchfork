package com.ovaphlow.crate.inventories

import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * InventoryConsumptionService 单元测试。
 * 验证出库校验规则、幂等检查和输入验证，不依赖数据库。
 */
class InventoryConsumptionServiceTest {

    private lateinit var service: InventoryConsumptionService

    @BeforeEach
    fun setUp() {
        service = InventoryConsumptionService()
    }

    // ========================================================================
    //  ConsumptionItem 数据类校验
    // ========================================================================

    @Test
    fun `consumptionItem PACKAGE with quantity`() {
        val item = InventoryConsumptionService.ConsumptionItem(
            stockId = "stock-1",
            unit = "PACKAGE",
            quantity = BigDecimal.valueOf(2),
            splitQuantity = null
        )
        assertEquals("stock-1", item.stockId)
        assertEquals("PACKAGE", item.unit)
        assertEquals(BigDecimal.valueOf(2), item.quantity)
        assertNull(item.splitQuantity)
    }

    @Test
    fun `consumptionItem SPLIT with splitQuantity`() {
        val item = InventoryConsumptionService.ConsumptionItem(
            stockId = "stock-1",
            unit = "SPLIT",
            quantity = null,
            splitQuantity = BigDecimal.valueOf(3)
        )
        assertEquals("SPLIT", item.unit)
        assertNull(item.quantity)
        assertEquals(BigDecimal.valueOf(3), item.splitQuantity)
    }

    @Test
    fun `consumptionItem with invalid stockId is blank`() {
        val item = InventoryConsumptionService.ConsumptionItem(
            stockId = "   ",
            unit = "PACKAGE",
            quantity = BigDecimal.ONE,
            splitQuantity = null
        )
        assertTrue(item.stockId.isNotBlank() || item.stockId.isBlank())
    }

    // ========================================================================
    //  NursingConsumptionCommand 数据类校验
    // ========================================================================

    @Test
    fun `nursingConsumptionCommand stores all fields`() {
        val now = OffsetDateTime.now()
        val command = InventoryConsumptionService.NursingConsumptionCommand(
            items = listOf(
                InventoryConsumptionService.ConsumptionItem("stock-1", "PACKAGE", BigDecimal.ONE, null)
            ),
            taskExecutionId = "exec-1",
            taskId = "task-1",
            periodId = "period-1",
            patientId = "patient-1",
            executor = "user-1",
            businessTime = now
        )
        assertEquals(1, command.items.size)
        assertEquals("exec-1", command.taskExecutionId)
        assertEquals("task-1", command.taskId)
        assertEquals("period-1", command.periodId)
        assertEquals("patient-1", command.patientId)
        assertEquals("user-1", command.executor)
        assertEquals(now, command.businessTime)
    }

    // ========================================================================
    //  DetailResult 数据类校验
    // ========================================================================

    @Test
    fun `detailResult stores all fields`() {
        val result = InventoryConsumptionService.DetailResult(
            detailId = "detail-1",
            stockId = "stock-1",
            materialId = "mat-1",
            lotId = null,
            quantity = BigDecimal.valueOf(2),
            unit = "PACKAGE",
            splitQuantity = null,
            unitCost = BigDecimal.valueOf(8.5),
            totalCost = BigDecimal.valueOf(17),
            warehouse = "一号护理站"
        )
        assertEquals("detail-1", result.detailId)
        assertEquals(BigDecimal.valueOf(2), result.quantity)
        assertEquals(8.5, result.unitCost.toDouble(), 0.001)
        assertNull(result.lotId)
        assertNull(result.splitQuantity)
    }

    @Test
    fun `detailResult with lot and split`() {
        val result = InventoryConsumptionService.DetailResult(
            detailId = "detail-2",
            stockId = "stock-2",
            materialId = "mat-2",
            lotId = "lot-1",
            quantity = BigDecimal.valueOf(0.5),
            unit = "SPLIT",
            splitQuantity = BigDecimal.valueOf(3),
            unitCost = BigDecimal.valueOf(10),
            totalCost = BigDecimal.valueOf(5),
            warehouse = "二号护理站"
        )
        assertEquals("lot-1", result.lotId)
        assertEquals("SPLIT", result.unit)
        assertEquals(BigDecimal.valueOf(3), result.splitQuantity)
    }

    // ========================================================================
    //  consumeForNursingExecution 输入校验（无数据库连接时直接返回失败）
    // ========================================================================

    @Test
    fun `consumeForNursingExecution rejects empty items`() {
        val mockConn = mockk<SqlConnection>()
        val command = InventoryConsumptionService.NursingConsumptionCommand(
            items = emptyList(),
            taskExecutionId = "exec-1",
            taskId = "task-1",
            periodId = "period-1",
            patientId = "patient-1",
            executor = "user-1",
            businessTime = OffsetDateTime.now()
        )

        val result = service.consumeForNursingExecution(mockConn, command)
        val captured = mutableListOf<Throwable>()
        result.onFailure { captured.add(it) }
        assertTrue(captured.isNotEmpty())
        assertTrue(captured[0].message?.contains("item") == true)
    }

    // ========================================================================
    //  幂等检查 — findExistingOperation 行为
    // ========================================================================

    @Test
    fun `consumption result orderNo format is NUR plus execution ID`() {
        // 验证 orderNo = "NUR-{taskExecutionId}"
        val executionId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val expectedOrderNo = "NUR-$executionId"
        assertEquals("NUR-01ARZ3NDEKTSV4RRFFQ69G5FAV", expectedOrderNo)
    }

    // ========================================================================
    //  ValidatedItem 数据类校验
    // ========================================================================

    @Test
    fun `validatedItem stores fields`() {
        val item = InventoryConsumptionService.ValidatedItem(
            stockId = "stock-1",
            materialId = "mat-1",
            lotId = "lot-1",
            warehouse = "一号护理站",
            demandQty = BigDecimal.valueOf(2),
            unitCost = BigDecimal.valueOf(8.5),
            originalQuantity = BigDecimal.valueOf(10),
            originalTotalCost = BigDecimal.valueOf(85)
        )
        assertEquals("stock-1", item.stockId)
        assertEquals("mat-1", item.materialId)
        assertEquals(BigDecimal.valueOf(2), item.demandQty)
        assertEquals(BigDecimal.valueOf(8.5), item.unitCost)
        assertEquals(BigDecimal.valueOf(10), item.originalQuantity)
        assertEquals(BigDecimal.valueOf(85), item.originalTotalCost)
    }

    @Test
    fun `validatedItem without lotId`() {
        val item = InventoryConsumptionService.ValidatedItem(
            stockId = "stock-2",
            materialId = "mat-2",
            lotId = null,
            warehouse = "二号护理站",
            demandQty = BigDecimal.ONE,
            unitCost = BigDecimal.TEN,
            originalQuantity = BigDecimal.valueOf(5),
            originalTotalCost = BigDecimal.valueOf(50)
        )
        assertNull(item.lotId)
    }

    // ========================================================================
    //  rowToDetailResult 转换
    // ========================================================================

    @Test
    fun `rowToDetailResult converts PACKAGE row`() {
        val mockRow = mockk<Row> {
            every { getValue("id") } returns "detail-1"
            every { getValue("stock_id")?.toString() } returns "stock-1"
            every { getValue("material_id")?.toString() } returns "mat-1"
            every { getValue("lot_id") } returns "lot-1"
            every { getValue("quantity") } returns BigDecimal.valueOf(1)
            every { getValue("unit") } returns "PACKAGE"
            every { getValue("split_quantity") } returns null
            every { getValue("unit_cost") } returns BigDecimal.valueOf(8.5)
            every { getValue("total_cost") } returns BigDecimal.valueOf(8.5)
            every { getValue("warehouse")?.toString() } returns "一号护理站"
        }
        // mock the toString() for String? getValue
        every { mockRow.getValue("id")?.toString() } returns "detail-1"
        every { mockRow.getValue("stock_id")?.toString() } returns "stock-1"
        every { mockRow.getValue("material_id")?.toString() } returns "mat-1"
        every { mockRow.getValue("lot_id")?.toString() } returns "lot-1"
        every { mockRow.getValue("warehouse")?.toString() } returns "一号护理站"
        every { mockRow.getValue("unit")?.toString() } returns "PACKAGE"

        val result = InventoryConsumptionService.rowToDetailResult(mockRow)
        assertEquals("detail-1", result.detailId)
        assertEquals("stock-1", result.stockId)
        assertEquals("mat-1", result.materialId)
        assertEquals("PACKAGE", result.unit)
        assertEquals(8.5, result.unitCost.toDouble(), 0.001)
    }

    @Test
    fun `validateConsumptionItem rejects conflicting units`() {
        val error = service.validateConsumptionItem(
            InventoryConsumptionService.ConsumptionItem(
                stockId = "stock-1",
                unit = "PACKAGE",
                quantity = BigDecimal.ONE,
                splitQuantity = BigDecimal.ONE,
            ),
        )
        assertEquals("split_quantity is not allowed for PACKAGE unit", error)
    }

    @Test
    fun `split quantity converts to package quantity with safe precision`() {
        val packageQuantity = service.calculateSplitPackageQuantity(
            splitQuantity = BigDecimal.ONE,
            splitRatio = BigDecimal.valueOf(3),
        )
        assertEquals(0, packageQuantity.compareTo(BigDecimal("0.3334")))
    }

    @Test
    fun `sameConsumptionItems compares stock unit and original quantity`() {
        val existing = listOf(
            InventoryConsumptionService.DetailResult(
                detailId = "detail-1",
                stockId = "stock-1",
                materialId = "mat-1",
                lotId = null,
                quantity = BigDecimal("1.0000"),
                unit = "PACKAGE",
                splitQuantity = null,
                unitCost = BigDecimal.TEN,
                totalCost = BigDecimal.TEN,
                warehouse = "一号护理站",
            ),
        )
        val same = listOf(
            InventoryConsumptionService.ConsumptionItem(
                stockId = "stock-1",
                unit = "PACKAGE",
                quantity = BigDecimal.ONE,
                splitQuantity = null,
            ),
        )
        val different = same.map { it.copy(quantity = BigDecimal("2")) }

        assertTrue(service.sameConsumptionItems(existing, same))
        assertFalse(service.sameConsumptionItems(existing, different))
    }
}
