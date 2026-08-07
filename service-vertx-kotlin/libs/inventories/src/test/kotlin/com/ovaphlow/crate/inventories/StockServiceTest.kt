package com.ovaphlow.crate.inventories

import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import io.vertx.sqlclient.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

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
                StockService.InboundItem(materialId = "mat-1", lotId = null, quantity = BigDecimal.ONE, unitCost = BigDecimal.TEN)
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
                StockService.InboundItem(materialId = "mat-1", lotId = null, quantity = BigDecimal.ONE, unitCost = BigDecimal.TEN)
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
                StockService.InboundItem(materialId = "mat-1", lotId = null, quantity = BigDecimal.ZERO, unitCost = BigDecimal.TEN),
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
        val item = StockService.InboundItem(materialId = "mat-1", lotId = null, quantity = BigDecimal.ZERO, unitCost = BigDecimal.TEN)
        assertEquals(BigDecimal.ZERO, item.quantity)
    }

    @Test
    fun `inboundItem with negative unitCost`() {
        val item = StockService.InboundItem(materialId = "mat-1", lotId = null, quantity = BigDecimal.ONE, unitCost = BigDecimal.valueOf(-1))
        assertTrue(item.unitCost < BigDecimal.ZERO)
    }

    @Test
    fun `inboundItem with lotId is valid`() {
        val item = StockService.InboundItem(materialId = "mat-1", lotId = "lot-1", quantity = BigDecimal.ONE, unitCost = BigDecimal.TEN)
        assertEquals("lot-1", item.lotId)
    }

    @Test
    fun `inboundItem with null lotId is valid`() {
        val item = StockService.InboundItem(materialId = "mat-1", lotId = null, quantity = BigDecimal.ONE, unitCost = BigDecimal.TEN)
        assertNull(item.lotId)
    }

    // ========================================================================
    //  availableStockToJson 转换
    // ========================================================================

    @Test
    fun `availableStockToJson computes available_quantity correctly`() {
        // 验证静态转换方法 - 使用 mock Row（relaxed 补齐 015 新增快照列，null 即旧行语义）
        val mockRow = mockk<Row>(relaxed = true) {
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
        val mockRow = mockk<Row>(relaxed = true) {
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
        val mockRow = mockk<Row>(relaxed = true) {
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

    // ========================================================================
    //  confirmPackageOutbound（011 药房同连接出库端口）
    //  client 参数必须收到调用方传入的 SqlConnection，禁止内部从 Pool 开新连接
    // ========================================================================

    private lateinit var outboundConn: SqlConnection
    private lateinit var outboundPrepared: PreparedQuery<RowSet<Row>>
    private val outboundCalls = AtomicInteger()
    private val outboundSql = mutableListOf<String>()

    private fun outboundRowSet(vararg rows: Row): RowSet<Row> {
        val backing = rows.iterator()
        return mockk {
            every { size() } returns rows.size
            every { iterator() } returns mockk<io.vertx.sqlclient.RowIterator<Row>> {
                every { hasNext() } answers { backing.hasNext() }
                every { next() } answers { backing.next() }
            }
        }
    }

    private fun outboundRow(values: Map<Int, Any?>): Row = mockk {
        every { getValue(any<Int>()) } answers { values[firstArg<Int>()] }
    }

    /** 默认桩：锁库存行（可用 10-0）、物资 ACTIVE 非批次管控；执行顺序见用例 */
    private fun stubOutboundChain(vararg sequences: RowSet<Row>) {
        outboundConn = mockk<SqlConnection>()
        outboundPrepared = mockk<PreparedQuery<RowSet<Row>>>()
        outboundCalls.set(0)
        outboundSql.clear()
        every { outboundConn.preparedQuery(any()) } answers {
            outboundSql.add(firstArg<String>())
            outboundPrepared
        }
        every { outboundPrepared.execute(any<Tuple>()) } answers {
            val index = outboundCalls.incrementAndGet()
            if (index <= sequences.size) Future.succeededFuture(sequences[index - 1]) else Future.succeededFuture(mockk())
        }
    }

    private fun stockRow(
        lotId: String? = null,
        quantity: BigDecimal = BigDecimal.TEN,
        locked: BigDecimal = BigDecimal.ZERO,
        totalCost: BigDecimal = BigDecimal.valueOf(35),
        baseQuantity: BigDecimal = quantity.multiply(BigDecimal.TEN),
        lockedBase: BigDecimal = BigDecimal.ZERO,
    ): Row = outboundRow(
        mapOf(
            0 to "stock-1",
            1 to lotId,
            2 to quantity,
            3 to locked,
            4 to totalCost,
            5 to baseQuantity,
            6 to lockedBase,
        ),
    )

    private fun materialRow(status: String = "ACTIVE", batchControl: Boolean = false): Row =
        outboundRow(mapOf(0 to status, 1 to batchControl))

    /** UnitConversionService.loadMaterial 的物资行：status, unit_model_status, base_unit, base_quantity_scale */
    private fun modelMaterialRow(status: String = "ACTIVE", modelStatus: String = "ACTIVE"): Row =
        outboundRow(mapOf(0 to status, 1 to modelStatus, 2 to "片", 3 to 4))

    /** UnitConversionService.loadDefaultSpec 的规格行：spec_id, input_unit, base_ratio, is_default, status */
    private fun specRow(ratio: BigDecimal = BigDecimal.TEN): Row =
        outboundRow(mapOf(0 to "spec-1", 1 to "盒", 2 to ratio, 3 to true, 4 to "ACTIVE"))

    private fun lotRow(materialId: String = "mat-1", expiry: LocalDate? = LocalDate.now().plusDays(30)): Row =
        outboundRow(mapOf(0 to materialId, 1 to expiry))

    private fun packageCommand(
        lotId: String? = "lot-1",
        quantity: BigDecimal = BigDecimal.ONE,
    ): StockService.PackageOutboundCommand =
        StockService.PackageOutboundCommand(
            warehouse = "西药库",
            materialId = "mat-1",
            lotId = lotId,
            quantity = quantity,
            note = "pharmacy dispense test",
        )

    private fun failureOf(future: Future<*>): Throwable {
        val captured = mutableListOf<Throwable>()
        future.onFailure { captured.add(it) }
        return captured.single()
    }

    @Test
    fun `confirmPackageOutbound rejects blank warehouse`() {
        val error = failureOf(
            service.confirmPackageOutbound(
                mockk<SqlConnection>(),
                StockService.PackageOutboundCommand("", "mat-1", null, BigDecimal.ONE, null),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("warehouse") == true)
    }

    @Test
    fun `confirmPackageOutbound rejects blank material_id`() {
        val error = failureOf(
            service.confirmPackageOutbound(
                mockk<SqlConnection>(),
                StockService.PackageOutboundCommand("西药库", "", null, BigDecimal.ONE, null),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("material_id") == true)
    }

    @Test
    fun `confirmPackageOutbound rejects non-positive quantity`() {
        val error = failureOf(
            service.confirmPackageOutbound(
                mockk<SqlConnection>(),
                StockService.PackageOutboundCommand("西药库", "mat-1", null, BigDecimal.ZERO, null),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("quantity") == true)
    }

    @Test
    fun `validatePackageOutbound fails when no stock row found`() {
        stubOutboundChain(outboundRowSet())

        val error = failureOf(service.validatePackageOutbound(outboundConn, packageCommand()))

        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("insufficient stock") == true)
        verify(exactly = 1) { outboundPrepared.execute(any<Tuple>()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `validatePackageOutbound rejects insufficient available quantity without writes`() {
        // 序列：预览库存 → 物资批控校验 → 默认规格解析（物资+规格）→ 基础可用量校验失败
        stubOutboundChain(
            outboundRowSet(stockRow(quantity = BigDecimal.ONE)),
            outboundRowSet(materialRow()),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
        )

        val error = failureOf(
            service.validatePackageOutbound(outboundConn, packageCommand(lotId = null, quantity = BigDecimal.valueOf(2))),
        )

        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("insufficient stock") == true)
        verify(exactly = 4) { outboundPrepared.execute(any<Tuple>()) }
        assertTrue(outboundSql.all { it.trimStart().startsWith("select", ignoreCase = true) })
    }

    @Test
    fun `validatePackageOutbound rejects missing lot for batch-controlled material`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = null)),
            outboundRowSet(materialRow(batchControl = true)),
        )

        val error = failureOf(service.validatePackageOutbound(outboundConn, packageCommand(lotId = null)))

        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("requires a lot") == true)
        verify(exactly = 2) { outboundPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `validatePackageOutbound accepts valid stock using read-only queries`() {
        // 序列：预览库存 → 物资批控校验 → 批次 → 默认规格解析（物资+规格）
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotRow()),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
        )

        val result = service.validatePackageOutbound(outboundConn, packageCommand())

        assertTrue(result.succeeded())
        verify(exactly = 5) { outboundPrepared.execute(any<Tuple>()) }
        assertTrue(outboundSql.all { it.trimStart().startsWith("select", ignoreCase = true) })
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackageOutbound fails when no stock row found`() {
        stubOutboundChain(outboundRowSet())
        val error = failureOf(service.confirmPackageOutbound(outboundConn, packageCommand()))
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("insufficient stock") == true)
    }

    @Test
    fun `confirmPackageOutbound fails when available quantity insufficient`() {
        // 序列：预览库存 → 默认规格解析（物资+规格）→ 锁库存 → 物资批控校验 → 基础可用量校验失败
        stubOutboundChain(
            outboundRowSet(stockRow(quantity = BigDecimal.valueOf(1))),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(quantity = BigDecimal.valueOf(1))),
            outboundRowSet(materialRow()),
        )
        // 库存 1 - 0 = 1，请求 2；非批次物资须不带 lot，否则先命中"does not use batch control"
        val error = failureOf(
            service.confirmPackageOutbound(outboundConn, packageCommand(lotId = null, quantity = BigDecimal.valueOf(2))),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("insufficient stock") == true)
    }

    @Test
    fun `confirmPackageOutbound fails when batch-controlled material has no lot`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = null)),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = null)),
            outboundRowSet(materialRow(batchControl = true)),
        )
        val error = failureOf(service.confirmPackageOutbound(outboundConn, packageCommand(lotId = null)))
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("requires a lot") == true)
    }

    @Test
    fun `confirmPackageOutbound fails when non-batch material has lot`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(materialRow(batchControl = false)),
        )
        val error = failureOf(service.confirmPackageOutbound(outboundConn, packageCommand(lotId = "lot-1")))
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("does not use batch control") == true)
    }

    @Test
    fun `confirmPackageOutbound fails when lot not found`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(),
        )
        val error = failureOf(service.confirmPackageOutbound(outboundConn, packageCommand()))
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("not found") == true)
    }

    @Test
    fun `confirmPackageOutbound fails when lot belongs to another material`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotRow(materialId = "mat-other")),
        )
        val error = failureOf(service.confirmPackageOutbound(outboundConn, packageCommand()))
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("does not belong") == true)
    }

    @Test
    fun `confirmPackageOutbound fails on expired lot`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotRow(expiry = LocalDate.now().minusDays(1))),
        )
        val error = failureOf(service.confirmPackageOutbound(outboundConn, packageCommand()))
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("expired") == true)
    }

    @Test
    fun `confirmPackageOutbound writes outbound on transaction connection and returns result`() {
        // 015 序列：预览库存 → 物资+规格解析 → 锁库存 → 物资批控 → 批次 → 出库单 → 明细 → 库存扣减
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = "lot-1")),
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotRow()),
            outboundRowSet(),
            outboundRowSet(),
            outboundRowSet(),
        )
        val result = service.confirmPackageOutbound(outboundConn, packageCommand())
        // 全部 9 步（预览库存、物资、规格、锁库存、物资批控、批次、出库单、出库明细、库存扣减）都走传入的连接
        verify(exactly = 9) { outboundConn.preparedQuery(any()) }
        verify(exactly = 9) { outboundPrepared.execute(any<Tuple>()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
        assertNotNull(result.result().stockOperationDetailId)
        assertEquals("lot-1", result.result().lotId)
        // unitCost = totalCost.divide(quantity, 4, HALF_UP)，scale 为 4，须用 compareTo 比较
        assertEquals(0, BigDecimal.valueOf(3.5).compareTo(result.result().unitCost))
    }

    @Test
    fun `confirmPackageOutbound computes unit cost from stock totals`() {
        stubOutboundChain(
            outboundRowSet(stockRow(lotId = null, quantity = BigDecimal.valueOf(5), totalCost = BigDecimal.valueOf(20))),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow(lotId = null, quantity = BigDecimal.valueOf(5), totalCost = BigDecimal.valueOf(20))),
            outboundRowSet(materialRow(batchControl = false)),
            outboundRowSet(),
            outboundRowSet(),
            outboundRowSet(),
        )
        val result = service.confirmPackageOutbound(outboundConn, packageCommand(lotId = null))
        assertEquals(0, BigDecimal.valueOf(4.0).compareTo(result.result().unitCost))
    }

    // ========================================================================
    //  confirmPackagePurchaseReceipt（014 采购收货批量入库端口）
    //  client 必须收到调用方传入的 SqlConnection；一次收货只写一张 INBOUND 操作
    // ========================================================================

    private lateinit var purchaseConn: SqlConnection
    private lateinit var purchasePrepared: PreparedQuery<RowSet<Row>>
    private val purchaseCalls = AtomicInteger()

    /** 默认桩：按调用顺序返回各次 execute 的结果；超出时返回空 RowSet */
    private fun stubPurchaseChain(vararg sequences: RowSet<Row>) {
        purchaseConn = mockk<SqlConnection>()
        purchasePrepared = mockk<PreparedQuery<RowSet<Row>>>()
        purchaseCalls.set(0)
        every { purchaseConn.preparedQuery(any()) } answers {
            purchasePrepared
        }
        every { purchasePrepared.execute(any<Tuple>()) } answers {
            val index = purchaseCalls.incrementAndGet()
            if (index <= sequences.size) Future.succeededFuture(sequences[index - 1]) else Future.succeededFuture(mockk())
        }
    }

    /** ensureLot 的批次事实行：lot_id, production_date, expiry_date, manufacturer, supplier */
    private fun lotFactRow(
        lotId: String = "lot-9",
        productionDate: LocalDate? = LocalDate.of(2026, 4, 1),
        expiryDate: LocalDate? = LocalDate.now().plusDays(60),
        manufacturer: String? = "某制药厂",
        supplier: String? = "华康医药配送",
    ): Row = outboundRow(mapOf(0 to lotId, 1 to productionDate, 2 to expiryDate, 3 to manufacturer, 4 to supplier))

    private fun purchaseItem(
        receiptItemId: String = "ri-1",
        materialId: String = "mat-1",
        batchNo: String? = null,
        productionDate: LocalDate? = null,
        expiryDate: LocalDate? = null,
        manufacturer: String? = null,
        quantity: BigDecimal = BigDecimal.TEN,
        unitCost: BigDecimal = BigDecimal.valueOf(5),
    ): StockService.PurchaseReceiptItem =
        StockService.PurchaseReceiptItem(
            receiptItemId = receiptItemId,
            materialId = materialId,
            batchNo = batchNo,
            productionDate = productionDate,
            expiryDate = expiryDate,
            manufacturer = manufacturer,
            quantity = quantity,
            unitCost = unitCost,
        )

    private fun purchaseCommand(items: List<StockService.PurchaseReceiptItem>): StockService.PurchaseReceiptCommand =
        StockService.PurchaseReceiptCommand(
            warehouse = "药房西药库",
            supplierName = "华康医药配送",
            purchaseOrderId = "po-1",
            purchaseOrderNo = "PH-PO-0001",
            purchaseReceiptId = "pr-1",
            receiptNo = "PH-REC-0001",
            receivedBy = "user-1",
            items = items,
        )

    @Test
    fun `confirmPackagePurchaseReceipt rejects blank warehouse`() {
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                mockk<SqlConnection>(),
                purchaseCommand(listOf(purchaseItem())).copy(warehouse = ""),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("warehouse") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects empty items`() {
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(mockk<SqlConnection>(), purchaseCommand(emptyList())),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("item") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects non-positive quantity`() {
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                mockk<SqlConnection>(),
                purchaseCommand(listOf(purchaseItem(quantity = BigDecimal.ZERO))),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("quantity") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects negative unit cost`() {
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                mockk<SqlConnection>(),
                purchaseCommand(listOf(purchaseItem(unitCost = BigDecimal.valueOf(-1)))),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message?.contains("unit_cost") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt writes single inbound with detail and stock for non-batch material`() {
        // 非批次物资：物资批控校验 → 默认规格解析（物资+规格）→ 目标库存 → 操作单 → 明细 → 库存累加
        stubPurchaseChain(
            outboundRowSet(materialRow()),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow()),
            outboundRowSet(),
            outboundRowSet(),
            outboundRowSet(),
        )
        val result = service.confirmPackagePurchaseReceipt(purchaseConn, purchaseCommand(listOf(purchaseItem())))

        assertTrue(result.succeeded())
        assertNotNull(result.result().stockOperationId)
        assertEquals(1, result.result().items.size)
        val itemResult = result.result().items[0]
        assertEquals("ri-1", itemResult.receiptItemId)
        assertNull(itemResult.lotId)
        assertNotNull(itemResult.stockOperationDetailId)
        assertEquals(0, BigDecimal.valueOf(50).compareTo(itemResult.totalCost))
        verify(exactly = 7) { purchaseConn.preparedQuery(any()) }
        verify(exactly = 7) { purchasePrepared.execute(any<Tuple>()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackagePurchaseReceipt creates lot for batch-controlled material`() {
        // 批次物资：物资批控校验 → 批次查询为空 → 新建批次 → 默认规格解析（物资+规格）→ 目标库存 → 操作单 → 明细 → 库存累加
        stubPurchaseChain(
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(),
            outboundRowSet(),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow()),
            outboundRowSet(),
            outboundRowSet(),
            outboundRowSet(),
        )
        val result = service.confirmPackagePurchaseReceipt(
            purchaseConn,
            purchaseCommand(
                listOf(
                    purchaseItem(
                        batchNo = "A240801",
                        productionDate = LocalDate.of(2026, 4, 1),
                        expiryDate = LocalDate.now().plusDays(60),
                        manufacturer = "某制药厂",
                    ),
                ),
            ),
        )

        assertTrue(result.succeeded())
        val itemResult = result.result().items[0]
        assertNotNull(itemResult.lotId)
        assertNotNull(itemResult.stockOperationDetailId)
        verify(exactly = 9) { purchaseConn.preparedQuery(any()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackagePurchaseReceipt reuses existing lot with matching facts`() {
        // 批次物资：物资批控校验 → 批次已存在且事实一致 → 默认规格解析（物资+规格）→ 目标库存 → 操作单 → 明细 → 库存累加
        stubPurchaseChain(
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotFactRow()),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
            outboundRowSet(stockRow()),
            outboundRowSet(),
            outboundRowSet(),
            outboundRowSet(),
        )
        val result = service.confirmPackagePurchaseReceipt(
            purchaseConn,
            purchaseCommand(
                listOf(
                    purchaseItem(
                        batchNo = "A240801",
                        productionDate = LocalDate.of(2026, 4, 1),
                        expiryDate = LocalDate.now().plusDays(60),
                        manufacturer = "某制药厂",
                    ),
                ),
            ),
        )

        assertTrue(result.succeeded())
        assertEquals("lot-9", result.result().items[0].lotId)
        verify(exactly = 8) { purchaseConn.preparedQuery(any()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects conflicting existing lot facts`() {
        // 批次查询在规格解析之前 eager 执行，但规格解析查询（loadMaterial→loadDefaultSpec）
        // 已先发出；冲突在批次事实校验（lotFuture compose）时抛出
        stubPurchaseChain(
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotFactRow(expiryDate = LocalDate.now().plusDays(200))),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
        )
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                purchaseConn,
                purchaseCommand(
                    listOf(
                        purchaseItem(
                            batchNo = "A240801",
                            expiryDate = LocalDate.now().plusDays(60),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(error is ConflictException, "got ${error.javaClass.name}: ${error.message}")
        assertTrue(error.message?.contains("conflicting") == true)
        verify(exactly = 4) { purchasePrepared.execute(any<Tuple>()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects expired existing lot`() {
        stubPurchaseChain(
            outboundRowSet(materialRow(batchControl = true)),
            outboundRowSet(lotFactRow(expiryDate = LocalDate.now().minusDays(1))),
            outboundRowSet(modelMaterialRow()),
            outboundRowSet(specRow()),
        )
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                purchaseConn,
                purchaseCommand(
                    listOf(purchaseItem(batchNo = "A240801", expiryDate = LocalDate.now().plusDays(60))),
                ),
            ),
        )
        assertTrue(error is ConflictException, "got ${error.javaClass.name}: ${error.message}")
        assertTrue(error.message?.contains("expired") == true)
        verify(exactly = 4) { purchasePrepared.execute(any<Tuple>()) }
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects missing batch_no for batch material`() {
        stubPurchaseChain(outboundRowSet(materialRow(batchControl = true)))
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(purchaseConn, purchaseCommand(listOf(purchaseItem()))),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("batch_no") == true)
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects past expiry_date for batch material`() {
        stubPurchaseChain(outboundRowSet(materialRow(batchControl = true)))
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                purchaseConn,
                purchaseCommand(
                    listOf(
                        purchaseItem(
                            batchNo = "A240801",
                            expiryDate = LocalDate.now().minusDays(1),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("expiry_date later than today") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects batch facts for non-batch material`() {
        stubPurchaseChain(outboundRowSet(materialRow()))
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(
                purchaseConn,
                purchaseCommand(
                    listOf(
                        purchaseItem(
                            batchNo = "A240801",
                            expiryDate = LocalDate.now().plusDays(60),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("does not use batch control") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects missing material`() {
        stubPurchaseChain(outboundRowSet())
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(purchaseConn, purchaseCommand(listOf(purchaseItem()))),
        )
        assertTrue(error is NotFoundException)
        assertTrue(error.message?.contains("material not found") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt rejects non-active material`() {
        stubPurchaseChain(outboundRowSet(materialRow(status = "DISABLED")))
        val error = failureOf(
            service.confirmPackagePurchaseReceipt(purchaseConn, purchaseCommand(listOf(purchaseItem()))),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message?.contains("not ACTIVE") == true)
    }

    @Test
    fun `confirmPackagePurchaseReceipt resolves items in stable material order`() {
        // 传入顺序 mat-b 在前，稳定顺序应为 mat-a 先解析、先写明细。
        // 每件：物资批控 → 默认规格解析（物资+规格）→ 目标库存（空 → 建行）
        stubPurchaseChain(
            outboundRowSet(materialRow()),       // mat-a 物资批控
            outboundRowSet(modelMaterialRow()),  // mat-a 规格解析物资
            outboundRowSet(specRow()),           // mat-a 规格解析规格
            outboundRowSet(),                    // mat-a 目标库存（空）
            outboundRowSet(),                    // mat-a 建库存行
            outboundRowSet(materialRow()),       // mat-b 物资批控
            outboundRowSet(modelMaterialRow()),  // mat-b 规格解析物资
            outboundRowSet(specRow()),           // mat-b 规格解析规格
            outboundRowSet(),                    // mat-b 目标库存（空）
            outboundRowSet(),                    // mat-b 建库存行
            outboundRowSet(),                    // 操作单
            outboundRowSet(),                    // mat-a 明细
            outboundRowSet(),                    // mat-a 库存累加
            outboundRowSet(),                    // mat-b 明细
            outboundRowSet(),                    // mat-b 库存累加
        )
        val result = service.confirmPackagePurchaseReceipt(
            purchaseConn,
            purchaseCommand(
                listOf(
                    purchaseItem(receiptItemId = "ri-b", materialId = "mat-b"),
                    purchaseItem(receiptItemId = "ri-a", materialId = "mat-a"),
                ),
            ),
        )

        assertTrue(result.succeeded())
        assertEquals(
            listOf("mat-a", "mat-b"),
            result.result().items.map { it.materialId },
        )
        assertEquals(
            listOf("ri-a", "ri-b"),
            result.result().items.map { it.receiptItemId },
        )
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }
}
