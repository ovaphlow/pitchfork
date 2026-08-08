package com.ovaphlow.crate.inventories

import io.vertx.sqlclient.SqlConnection
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * InventoryConsumptionService 016 单一基础单位单元测试。
 * 覆盖耗材输入校验与基础数量幂等比较；不依赖数据库。
 */
class InventoryConsumptionServiceTest {

    private lateinit var service: InventoryConsumptionService

    @BeforeEach
    fun setUp() {
        service = InventoryConsumptionService()
    }

    @Test
    fun `consumptionItem stores stock id and base quantity`() {
        val item = InventoryConsumptionService.ConsumptionItem(
            stockId = "stock-1",
            quantity = BigDecimal.valueOf(2),
        )
        assertEquals("stock-1", item.stockId)
        assertEquals(BigDecimal.valueOf(2), item.quantity)
    }

    @Test
    fun `nursingConsumptionCommand stores all fields`() {
        val now = OffsetDateTime.now()
        val command = InventoryConsumptionService.NursingConsumptionCommand(
            items = listOf(InventoryConsumptionService.ConsumptionItem("stock-1", BigDecimal.ONE)),
            taskExecutionId = "exec-1",
            taskId = "task-1",
            periodId = "period-1",
            patientId = "patient-1",
            executor = "user-1",
            businessTime = now,
        )
        assertEquals(1, command.items.size)
        assertEquals("exec-1", command.taskExecutionId)
        assertEquals(now, command.businessTime)
    }

    @Test
    fun `detailResult stores base quantity unit and costs`() {
        val result = InventoryConsumptionService.DetailResult(
            detailId = "detail-1",
            stockId = "stock-1",
            materialId = "mat-1",
            lotId = null,
            quantity = BigDecimal.valueOf(2),
            unit = "片",
            unitCost = BigDecimal("0.85"),
            totalCost = BigDecimal("1.7"),
            warehouse = "一号护理站",
        )
        assertEquals("detail-1", result.detailId)
        assertEquals("片", result.unit)
        assertEquals(0, BigDecimal("1.7").compareTo(result.totalCost))
    }

    @Test
    fun `consumeForNursingExecution rejects empty items without touching db`() {
        val mockConn = mockk<SqlConnection>()
        val command = InventoryConsumptionService.NursingConsumptionCommand(
            items = emptyList(),
            taskExecutionId = "exec-1",
            taskId = "task-1",
            periodId = "period-1",
            patientId = "patient-1",
            executor = "user-1",
            businessTime = OffsetDateTime.now(),
        )
        val failures = mutableListOf<Throwable>()
        service.consumeForNursingExecution(mockConn, command).onFailure { failures.add(it) }
        assertTrue(failures.isNotEmpty())
        assertTrue(failures[0].message?.contains("item") == true)
    }

    @Test
    fun `validateConsumptionItem rejects blank stock id and non positive quantity`() {
        assertTrue(service.validateConsumptionItem(InventoryConsumptionService.ConsumptionItem("   ", BigDecimal.ONE))!!.contains("stock_id"))
        assertTrue(service.validateConsumptionItem(InventoryConsumptionService.ConsumptionItem("stock-1", BigDecimal.ZERO))!!.contains("quantity"))
        assertNull(service.validateConsumptionItem(InventoryConsumptionService.ConsumptionItem("stock-1", BigDecimal.ONE)))
    }

    @Test
    fun `sameConsumptionItems compares stock id and base quantity`() {
        val existing = listOf(
            InventoryConsumptionService.DetailResult(
                detailId = "d1",
                stockId = "s1",
                materialId = "m1",
                lotId = null,
                quantity = BigDecimal("2.5"),
                unit = "mL",
                unitCost = BigDecimal.ONE,
                totalCost = BigDecimal("2.5"),
                warehouse = "w",
            ),
            InventoryConsumptionService.DetailResult(
                detailId = "d2",
                stockId = "s2",
                materialId = "m2",
                lotId = null,
                quantity = BigDecimal.ONE,
                unit = "片",
                unitCost = BigDecimal.ONE,
                totalCost = BigDecimal.ONE,
                warehouse = "w",
            ),
        )
        val same = listOf(
            InventoryConsumptionService.ConsumptionItem("s2", BigDecimal.ONE),
            InventoryConsumptionService.ConsumptionItem("s1", BigDecimal("2.5")),
        )
        val different = listOf(
            InventoryConsumptionService.ConsumptionItem("s2", BigDecimal.ONE),
            InventoryConsumptionService.ConsumptionItem("s1", BigDecimal("2.6")),
        )
        assertTrue(service.sameConsumptionItems(existing, same))
        assertFalse(service.sameConsumptionItems(existing, different))
        assertFalse(service.sameConsumptionItems(existing, listOf(InventoryConsumptionService.ConsumptionItem("s1", BigDecimal.ONE))))
    }
}
