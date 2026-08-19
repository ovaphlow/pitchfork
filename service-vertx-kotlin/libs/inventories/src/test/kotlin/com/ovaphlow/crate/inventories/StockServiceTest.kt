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

    @Test
    fun `request decimal accepts text and rejects JSON numbers`() {
        assertEquals(BigDecimal("99999999999999.123456"), requestDecimalText("99999999999999.123456"))
        assertNull(requestDecimalText(0.1))
        assertNull(requestDecimalText(BigDecimal("0.1")))
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
        assertEquals("48", json.getString("quantity"))
        assertEquals("5", json.getString("locked_quantity"))
        assertEquals("43", json.getString("available_quantity"))
        assertEquals("片", json.getString("unit"))
        assertEquals("5.00000000", json.getString("unit_cost"))
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
        assertEquals("0", json.getString("quantity"))
        assertEquals("0", json.getString("unit_cost"))
    }

    @Test
    fun `availableStockToJson preserves full NUMERIC precision as decimal text`() {
        val quantity = BigDecimal("99999999999999.123456")
        val mockRow = mockk<Row>(relaxed = true) {
            every { getValue("id") } returns "stock-precision"
            every { getValue("warehouse") } returns "一号护理站"
            every { getValue("material_id") } returns "mat-precision"
            every { getValue("material_code") } returns "NC-999"
            every { getValue("material_name") } returns "精度测试物资"
            every { getValue("material_category") } returns "耗材"
            every { getValue("unit") } returns "mL"
            every { getValue("quantity") } returns quantity
            every { getValue("locked_quantity") } returns BigDecimal.ZERO
            every { getValue("total_cost") } returns BigDecimal.ZERO
            every { getValue("lot_id") } returns null
            every { getValue("batch_no") } returns null
            every { getValue("expiry_date") } returns null
        }

        assertEquals(quantity.toPlainString(), StockService.availableStockToJson(mockRow).getString("quantity"))
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
        assertEquals("5", json.getString("quantity"))
        assertEquals("片", json.getString("unit"))
        assertEquals("0.85", json.getString("unit_cost"))
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
    //  批次校验（validateOutbound 批次分支，事务前失败）
    // ========================================================================

    @Test
    fun `validateOutbound rejects missing lot for batch controlled material`() {
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        // 第 1 次查询：loadStock → 存在一行无批次库存；第 2 次查询：loadMaterial → 批次控制物资
        mockClientQueries(
            conn,
            stockRowSet(lotId = null, quantity = BigDecimal.TEN, locked = BigDecimal.ZERO),
            materialRowSet(batchControl = true),
        )

        val error = failureOf(
            service.validateOutbound(
                conn,
                StockService.OutboundCommand("西药库", "mat-1", null, BigDecimal.ONE, null),
            ),
        )
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("requires a lot"), "实际: ${error.message}")
    }

    @Test
    fun `validateOutbound rejects lot for non batch controlled material`() {
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        mockClientQueries(
            conn,
            stockRowSet(lotId = "lot-1", quantity = BigDecimal.TEN, locked = BigDecimal.ZERO),
            materialRowSet(batchControl = false),
        )

        val error = failureOf(
            service.validateOutbound(
                conn,
                StockService.OutboundCommand("西药库", "mat-1", "lot-1", BigDecimal.ONE, null),
            ),
        )
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("does not use batch control"), "实际: ${error.message}")
    }

    @Test
    fun `validateOutbound accepts lot for batch controlled material`() {
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        // 批次物资 + 批次库存：批次校验通过，进入第 3 次查询（lot 归属/效期）
        mockClientQueries(
            conn,
            stockRowSet(lotId = "lot-1", quantity = BigDecimal.TEN, locked = BigDecimal.ZERO),
            materialRowSet(batchControl = true),
            lotRowSet(materialId = "mat-1", expired = false),
        )

        val result = service.validateOutbound(
            conn,
            StockService.OutboundCommand("西药库", "mat-1", "lot-1", BigDecimal.ONE, null),
        ).toCompletionStage().toCompletableFuture().get()
        assertNull(result)
    }

    @Test
    fun `validateOutbound rejects expired lot`() {
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        mockClientQueries(
            conn,
            stockRowSet(lotId = "lot-1", quantity = BigDecimal.TEN, locked = BigDecimal.ZERO),
            materialRowSet(batchControl = true),
            lotRowSet(materialId = "mat-1", expired = true),
        )

        val error = failureOf(
            service.validateOutbound(
                conn,
                StockService.OutboundCommand("西药库", "mat-1", "lot-1", BigDecimal.ONE, null),
            ),
        )
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("expired"), "实际: ${error.message}")
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

    /** 顺序 mock 客户端查询：validateOutbound 依次执行 loadStock → loadMaterial → lot 查询。 */
    private fun mockClientQueries(client: io.vertx.sqlclient.SqlClient, vararg rowSets: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>) {
        val pq = mockk<io.vertx.sqlclient.PreparedQuery<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>>()
        every { client.preparedQuery(any<String>()) } returns pq
        every { client.preparedQuery(any<String>(), any()) } returns pq
        val queue = ArrayDeque(rowSets.toList())
        every { pq.execute(any<io.vertx.sqlclient.Tuple>()) } answers {
            if (queue.isEmpty()) throw AssertionError("unexpected extra database query")
            io.vertx.core.Future.succeededFuture(queue.removeFirst())
        }
        every { pq.execute() } returns io.vertx.core.Future.succeededFuture(mockk(relaxed = true))
    }

    /** loadStock 行：getValue(0..4) 为 id/lot_id/quantity/locked_quantity/total_cost。 */
    private fun stockRowSet(lotId: String?, quantity: BigDecimal, locked: BigDecimal): io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> {
        val row = mockk<io.vertx.sqlclient.Row>(relaxed = true) {
            every { getValue(0) } returns "stock-1"
            every { getValue(1) } returns lotId
            every { getValue(2) } returns quantity
            every { getValue(3) } returns locked
            every { getValue(4) } returns BigDecimal.TEN
        }
        return mockk<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>(relaxed = true) {
            every { size() } returns 1
            every { iterator() } returns iteratorOf(row)
        }
    }

    /** loadMaterial 行：getValue(0..3) 为 status/base_unit/quantity_scale/batch_control。 */
    private fun materialRowSet(batchControl: Boolean): io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> {
        val row = mockk<io.vertx.sqlclient.Row>(relaxed = true) {
            every { getValue(0) } returns "ACTIVE"
            every { getValue(1) } returns "片"
            every { getValue(2) } returns 0
            every { getValue(3) } returns batchControl
        }
        return mockk<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>(relaxed = true) {
            every { size() } returns 1
            every { iterator() } returns iteratorOf(row)
        }
    }

    /** lot 行：getValue(0)=material_id、getValue(1)=expiry_date。 */
    private fun lotRowSet(materialId: String, expired: Boolean): io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> {
        val expiry = if (expired) java.time.LocalDate.now().minusDays(1) else java.time.LocalDate.now().plusDays(30)
        val row = mockk<io.vertx.sqlclient.Row>(relaxed = true) {
            every { getValue(0) } returns materialId
            every { getValue(1) } returns expiry
        }
        return mockk<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>(relaxed = true) {
            every { size() } returns 1
            every { iterator() } returns iteratorOf(row)
        }
    }

    private fun iteratorOf(vararg rows: io.vertx.sqlclient.Row): io.vertx.sqlclient.RowIterator<io.vertx.sqlclient.Row> {
        val delegate = rows.iterator()
        return mockk<io.vertx.sqlclient.RowIterator<io.vertx.sqlclient.Row>> {
            every { hasNext() } answers { delegate.hasNext() }
            every { next() } answers { delegate.next() }
        }
    }

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    // ========================================================================
    //  confirmPurchaseReceipt 输入校验（事务开启前即失败）
    // ========================================================================

    private fun purchaseReceiptItem(
        materialId: String = "mat-1",
        quantity: BigDecimal = BigDecimal.ONE,
        unitCost: BigDecimal = BigDecimal.TEN,
    ) = StockService.PurchaseReceiptItem(
        receiptItemId = "rec-item-1",
        materialId = materialId,
        batchNo = null,
        productionDate = null,
        expiryDate = null,
        manufacturer = null,
        quantity = quantity,
        unitCost = unitCost,
    )

    private fun purchaseReceiptCommand(
        items: List<StockService.PurchaseReceiptItem> = listOf(purchaseReceiptItem()),
    ) = StockService.PurchaseReceiptCommand(
        warehouse = "药房西药库",
        supplierName = "华康医药配送",
        purchaseOrderId = "po-1",
        purchaseOrderNo = "PH-PO-1",
        purchaseReceiptId = "rec-1",
        receiptNo = "PH-REC-1",
        receivedBy = "pharm-user",
        items = items,
    )

    @Test
    fun `confirmPurchaseReceipt rejects blank warehouse`() {
        val cmd = purchaseReceiptCommand().copy(warehouse = "")
        val error = failureOf(service.confirmPurchaseReceipt(mockk(), cmd))
        assertTrue(error.message?.contains("warehouse") == true)
    }

    @Test
    fun `confirmPurchaseReceipt rejects blank supplier`() {
        val cmd = purchaseReceiptCommand().copy(supplierName = "")
        val error = failureOf(service.confirmPurchaseReceipt(mockk(), cmd))
        assertTrue(error.message?.contains("supplier_name") == true)
    }

    @Test
    fun `confirmPurchaseReceipt rejects empty items`() {
        val cmd = purchaseReceiptCommand(items = emptyList())
        val error = failureOf(service.confirmPurchaseReceipt(mockk(), cmd))
        assertTrue(error.message?.contains("item") == true)
    }

    @Test
    fun `confirmPurchaseReceipt rejects non positive quantity`() {
        val cmd = purchaseReceiptCommand(items = listOf(purchaseReceiptItem(quantity = BigDecimal.ZERO)))
        val error = failureOf(service.confirmPurchaseReceipt(mockk(), cmd))
        assertTrue(error.message?.contains("quantity") == true)
    }

    @Test
    fun `confirmPurchaseReceipt rejects negative unit cost`() {
        val cmd = purchaseReceiptCommand(items = listOf(purchaseReceiptItem(unitCost = BigDecimal.valueOf(-1))))
        val error = failureOf(service.confirmPurchaseReceipt(mockk(), cmd))
        assertTrue(error.message?.contains("unit_cost") == true)
    }
}
