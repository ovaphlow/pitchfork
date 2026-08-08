package com.ovaphlow.crate.inventories

import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * StockService 016 单一基础单位单元测试。
 * 覆盖输入校验、精度拒绝、基础数量序列化；不依赖数据库。
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
    //  confirmInbound 参数校验（事务开启前即失败）
    // ========================================================================

    @Test
    fun `confirmInbound rejects empty warehouse`() {
        val result = service.confirmInbound(
            StockService.InboundCommand(
                warehouse = "",
                items = listOf(inboundItem(quantity = BigDecimal.ONE)),
                note = null,
            ),
        )
        val error = failureOf(result)
        assertTrue(error.message?.contains("warehouse") == true)
    }

    @Test
    fun `confirmInbound rejects empty items`() {
        val result = service.confirmInbound(
            StockService.InboundCommand(warehouse = "一号护理站", items = emptyList(), note = null),
        )
        val error = failureOf(result)
        assertTrue(error.message?.contains("item") == true)
    }

    @Test
    fun `confirmInbound rejects non positive quantity`() {
        val result = service.confirmInbound(
            StockService.InboundCommand(
                warehouse = "一号护理站",
                items = listOf(inboundItem(quantity = BigDecimal.ZERO)),
                note = null,
            ),
        )
        val error = failureOf(result)
        assertTrue(error.message?.contains("quantity") == true)
    }

    @Test
    fun `confirmInbound rejects negative unit cost`() {
        val result = service.confirmInbound(
            StockService.InboundCommand(
                warehouse = "一号护理站",
                items = listOf(inboundItem(quantity = BigDecimal.ONE, unitCost = BigDecimal.valueOf(-1))),
                note = null,
            ),
        )
        val error = failureOf(result)
        assertTrue(error.message?.contains("unit_cost") == true)
    }

    // ========================================================================
    //  基础数量精度与成本精度
    // ========================================================================

    @Test
    fun `base quantity precision rejects fraction for whole unit material`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateBaseQuantity(BigDecimal("0.5"), quantityScale = 0)
        }
        assertTrue(error.message!!.contains("precision"))
    }

    @Test
    fun `base quantity precision accepts configured decimal scale`() {
        validateBaseQuantity(BigDecimal("0.125"), quantityScale = 3)
    }

    @Test
    fun `base quantity precision rejects more decimals than configured`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBaseQuantity(BigDecimal("0.1234"), quantityScale = 3)
        }
    }

    @Test
    fun `unit cost rejects negative and over precision`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateUnitCost(BigDecimal.valueOf(-1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUnitCost(BigDecimal("0.123456789"))
        }
        validateUnitCost(BigDecimal("0.12345678"))
    }

    // ========================================================================
    //  availableStockToJson：单一基础数量口径，无包装投影
    // ========================================================================

    @Test
    fun `availableStockToJson exposes base quantity and unit only`() {
        val mockRow = mockk<Row>(relaxed = true) {
            every { getValue("id") } returns "stock-1"
            every { getValue("warehouse") } returns "一号护理站"
            every { getValue("material_id") } returns "mat-1"
            every { getValue("material_code") } returns "NC-001"
            every { getValue("material_name") } returns "阿莫西林"
            every { getValue("material_category") } returns "药品"
            every { getValue("unit") } returns "片"
            every { getValue("quantity") } returns BigDecimal.valueOf(48)
            every { getValue("locked_quantity") } returns BigDecimal.valueOf(5)
            every { getValue("total_cost") } returns BigDecimal.valueOf(240)
            every { getValue("lot_id") } returns null
            every { getValue("batch_no") } returns null
            every { getValue("expiry_date") } returns null
        }

        val json = StockService.availableStockToJson(mockRow)
        assertEquals("stock-1", json.getString("id"))
        assertEquals(48.0, json.getDouble("quantity"), 0.001)
        assertEquals(5.0, json.getDouble("locked_quantity"), 0.001)
        assertEquals(43.0, json.getDouble("available_quantity"), 0.001)
        assertEquals("片", json.getString("unit"))
        assertEquals(5.0, json.getDouble("unit_cost"), 0.001)
        assertNull(json.getValue("package_unit"))
        assertNull(json.getValue("base_quantity"))
        assertNull(json.getValue("split_ratio"))
    }

    @Test
    fun `availableStockToJson handles zero quantity`() {
        val mockRow = mockk<Row>(relaxed = true) {
            every { getValue("id") } returns "stock-2"
            every { getValue("warehouse") } returns "一号护理站"
            every { getValue("material_id") } returns "mat-2"
            every { getValue("material_code") } returns "NC-002"
            every { getValue("material_name") } returns "护理垫"
            every { getValue("material_category") } returns "耗材"
            every { getValue("unit") } returns "包"
            every { getValue("quantity") } returns BigDecimal.ZERO
            every { getValue("locked_quantity") } returns BigDecimal.ZERO
            every { getValue("total_cost") } returns BigDecimal.ZERO
            every { getValue("lot_id") } returns null
            every { getValue("batch_no") } returns null
            every { getValue("expiry_date") } returns null
        }

        val json = StockService.availableStockToJson(mockRow)
        assertEquals(0.0, json.getDouble("quantity"), 0.001)
        assertEquals(0.0, json.getDouble("unit_cost"), 0.001)
    }

    // ========================================================================
    //  operationToJson / detailToJson：基础数量快照
    // ========================================================================

    @Test
    fun `detailToJson exposes base quantity unit and costs without package fields`() {
        val mockRow = mockk<Row>(relaxed = true) {
            every { getValue("id") } returns "detail-1"
            every { getValue("operation_id") } returns "op-1"
            every { getValue("material_id") } returns "mat-1"
            every { getValue("lot_id") } returns null
            every { getValue("quantity") } returns BigDecimal.valueOf(5)
            every { getValue("unit") } returns "片"
            every { getValue("unit_cost") } returns BigDecimal("0.85")
            every { getValue("total_cost") } returns BigDecimal("4.25")
            every { getValue("created_at") } returns "2026-08-07T10:00:00Z"
        }

        val json = StockService.detailToJson(mockRow)
        assertEquals(5.0, json.getDouble("quantity"), 0.001)
        assertEquals("片", json.getString("unit"))
        assertEquals(0.85, json.getDouble("unit_cost"), 0.001)
        assertNull(json.getValue("split_quantity"))
        assertNull(json.getValue("unit_spec_id"))
        assertNull(json.getValue("base_quantity"))
    }

    // ========================================================================
    //  出库/退药端口输入校验（事务前失败）
    // ========================================================================

    @Test
    fun `validateOutbound rejects non positive quantity`() {
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        val result = service.validateOutbound(
            conn,
            StockService.OutboundCommand("西药库", "mat-1", null, BigDecimal.ZERO, null),
        )
        assertTrue(failureOf(result).message?.contains("quantity") == true)
    }

    @Test
    fun `confirmReturnInbound rejects non positive quantity and negative cost`() {
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        val qtyError = failureOf(
            service.confirmReturnInbound(
                conn,
                StockService.ReturnInboundCommand("西药库", "mat-1", null, BigDecimal.ZERO, BigDecimal.ONE, null),
            ),
        )
        val costError = failureOf(
            service.confirmReturnInbound(
                conn,
                StockService.ReturnInboundCommand("西药库", "mat-1", null, BigDecimal.ONE, BigDecimal.valueOf(-1), null),
            ),
        )
        assertTrue(qtyError.message?.contains("quantity") == true)
        assertTrue(costError.message?.contains("unit_cost") == true)
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun inboundItem(
        materialId: String = "mat-1",
        lotId: String? = null,
        quantity: BigDecimal,
        unitCost: BigDecimal = BigDecimal.TEN,
    ) = StockService.InboundItem(materialId, lotId, quantity, unitCost)

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }
}
