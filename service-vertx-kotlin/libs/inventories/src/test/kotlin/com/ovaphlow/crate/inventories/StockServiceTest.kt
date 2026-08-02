package com.ovaphlow.crate.inventories

import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Transaction
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * StockService 单元测试。
 * 验证入库参数校验和业务规则，不依赖数据库。
 */
class StockServiceTest {

    private lateinit var service: StockService
    private lateinit var mockPool: Pool

    @BeforeEach
    fun setUp() {
        mockPool = mockk<Pool>()
        service = StockService(mockPool)
    }

    // ========================================================================
    //  confirmInbound 参数校验
    // ========================================================================

    @Test
    fun `confirmInbound rejects empty warehouse`() {
        val command = StockService.InboundCommand(
            warehouse = "",
            items = listOf(
                StockService.InboundItem("mat-1", null, BigDecimal.ONE, BigDecimal.TEN)
            ),
            note = null
        )
        val result = service.confirmInbound(command)
        val captured = mutableListOf<Throwable>()
        result.onFailure { captured.add(it) }
        assertTrue(captured.isNotEmpty())
        assertTrue(captured[0].message?.contains("warehouse") == true)
    }

    @Test
    fun `confirmInbound rejects empty items`() {
        val command = StockService.InboundCommand(
            warehouse = "一号护理站",
            items = emptyList(),
            note = null
        )
        val result = service.confirmInbound(command)
        val captured = mutableListOf<Throwable>()
        result.onFailure { captured.add(it) }
        assertTrue(captured.isNotEmpty())
        assertTrue(captured[0].message?.contains("item") == true)
    }

    @Test
    fun `confirmInbound rejects blank warehouse`() {
        val command = StockService.InboundCommand(
            warehouse = "   ",
            items = listOf(
                StockService.InboundItem("mat-1", null, BigDecimal.ONE, BigDecimal.TEN)
            ),
            note = null
        )
        val result = service.confirmInbound(command)
        val captured = mutableListOf<Throwable>()
        result.onFailure { captured.add(it) }
        assertTrue(captured.isNotEmpty())
    }

    @Test
    fun `confirmInbound rejects invalid item before opening transaction`() {
        val command = StockService.InboundCommand(
            warehouse = "一号护理站",
            items = listOf(
                StockService.InboundItem("mat-1", null, BigDecimal.ZERO, BigDecimal.TEN),
            ),
            note = null,
        )
        val result = service.confirmInbound(command)
        val captured = mutableListOf<Throwable>()
        result.onFailure { captured.add(it) }
        assertEquals(1, captured.size)
        assertTrue(captured.single().message?.contains("quantity") == true)
    }

    // ========================================================================
    //  InboundItem 数据类校验
    // ========================================================================

    @Test
    fun `inboundItem with zero quantity`() {
        val item = StockService.InboundItem("mat-1", null, BigDecimal.ZERO, BigDecimal.TEN)
        assertEquals(BigDecimal.ZERO, item.quantity)
    }

    @Test
    fun `inboundItem with negative unitCost`() {
        val item = StockService.InboundItem("mat-1", null, BigDecimal.ONE, BigDecimal.valueOf(-1))
        assertTrue(item.unitCost < BigDecimal.ZERO)
    }

    @Test
    fun `inboundItem with lotId is valid`() {
        val item = StockService.InboundItem("mat-1", "lot-1", BigDecimal.ONE, BigDecimal.TEN)
        assertEquals("lot-1", item.lotId)
    }

    @Test
    fun `inboundItem with null lotId is valid`() {
        val item = StockService.InboundItem("mat-1", null, BigDecimal.ONE, BigDecimal.TEN)
        assertNull(item.lotId)
    }

    // ========================================================================
    //  availableStockToJson 转换
    // ========================================================================

    @Test
    fun `availableStockToJson computes available_quantity correctly`() {
        // 验证静态转换方法 - 使用 mock Row
        val mockRow = mockk<Row> {
            every { getValue("quantity") } returns BigDecimal.valueOf(10)
            every { getValue("locked_quantity") } returns BigDecimal.valueOf(2)
            every { getValue("total_cost") } returns BigDecimal.valueOf(100)
            every { getValue("id") } returns "stock-1"
            every { getValue("warehouse") } returns "一号护理站"
            every { getValue("material_id") } returns "mat-1"
            every { getValue("material_code") } returns "NC-001"
            every { getValue("material_name") } returns "一次性手套"
            every { getValue("material_category") } returns "耗材"
            every { getValue("package_unit") } returns "盒"
            every { getValue("split_unit") } returns null
            every { getValue("split_ratio") } returns null
            every { getValue("lot_id") } returns null
            every { getValue("batch_no") } returns null
            every { getValue("expiry_date") } returns null
        }

        val json = StockService.availableStockToJson(mockRow)
        assertEquals("stock-1", json.getString("id"))
        assertEquals(10.0, json.getDouble("quantity"), 0.001)
        assertEquals(2.0, json.getDouble("locked_quantity"), 0.001)
        assertEquals(8.0, json.getDouble("available_quantity"), 0.001)
        assertEquals(10.0, json.getDouble("unit_cost"), 0.001)
        assertEquals("NC-001", json.getString("material_code"))
    }

    @Test
    fun `availableStockToJson handles zero quantity`() {
        val mockRow = mockk<Row> {
            every { getValue("quantity") } returns BigDecimal.ZERO
            every { getValue("locked_quantity") } returns BigDecimal.ZERO
            every { getValue("total_cost") } returns BigDecimal.ZERO
            every { getValue("id") } returns "stock-2"
            every { getValue("warehouse") } returns "一号护理站"
            every { getValue("material_id") } returns "mat-2"
            every { getValue("material_code") } returns "NC-002"
            every { getValue("material_name") } returns "护理垫"
            every { getValue("material_category") } returns "耗材"
            every { getValue("package_unit") } returns "包"
            every { getValue("split_unit") } returns null
            every { getValue("split_ratio") } returns null
            every { getValue("lot_id") } returns null
            every { getValue("batch_no") } returns null
            every { getValue("expiry_date") } returns null
        }

        val json = StockService.availableStockToJson(mockRow)
        assertEquals(0.0, json.getDouble("quantity"), 0.001)
        assertEquals(0.0, json.getDouble("available_quantity"), 0.001)
        assertEquals(0.0, json.getDouble("unit_cost"), 0.001)
    }

    // ========================================================================
    //  operationToJson / detailToJson 转换
    // ========================================================================

    @Test
    fun `operationToJson converts row correctly`() {
        val mockRow = mockk<Row> {
            every { getValue("id") } returns "op-1"
            every { getValue("order_no") } returns "NUR-exec-1"
            every { getValue("operation_type") } returns "OUTBOUND"
            every { getValue("warehouse") } returns "一号护理站"
            every { getValue("status") } returns "CONFIRMED"
            every { getValue("metadata") } returns JsonObject().put("source", "NURSING_EXECUTION")
            every { getValue("created_at") } returns "2026-07-30T10:00:00Z"
            every { getValue("confirmed_at") } returns "2026-07-30T10:00:00Z"
        }

        val json = StockService.operationToJson(mockRow)
        assertEquals("op-1", json.getString("id"))
        assertEquals("OUTBOUND", json.getString("operation_type"))
        assertEquals("CONFIRMED", json.getString("status"))
        assertEquals("NURSING_EXECUTION", json.getJsonObject("metadata")?.getString("source"))
    }

    @Test
    fun `detailToJson converts row correctly`() {
        val mockRow = mockk<Row> {
            every { getValue("id") } returns "detail-1"
            every { getValue("operation_id") } returns "op-1"
            every { getValue("material_id") } returns "mat-1"
            every { getValue("lot_id") } returns null
            every { getValue("quantity") } returns BigDecimal.valueOf(2)
            every { getValue("unit") } returns "PACKAGE"
            every { getValue("split_quantity") } returns null
            every { getValue("unit_cost") } returns BigDecimal.valueOf(8.5)
            every { getValue("total_cost") } returns BigDecimal.valueOf(17)
            every { getValue("created_at") } returns "2026-07-30T10:00:00Z"
        }

        val json = StockService.detailToJson(mockRow)
        assertEquals("detail-1", json.getString("id"))
        assertEquals(2.0, json.getDouble("quantity"), 0.001)
        assertEquals("PACKAGE", json.getString("unit"))
        assertEquals(8.5, json.getDouble("unit_cost"), 0.001)
        assertEquals(17.0, json.getDouble("total_cost"), 0.001)
    }
}
