package com.ovaphlow.crate.inventories

import io.vertx.core.Future
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * StockService 013 护理站申领：同连接预留/释放/双仓调拨单元测试。
 * mock SqlConnection + PreparedQuery，按执行次序返回 RowSet；不依赖数据库。
 */
class RequisitionStockServiceTest {
    private lateinit var service: StockService
    private lateinit var client: SqlConnection
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private val executeCalls = AtomicInteger()
    private val tuples = mutableListOf<Tuple>()

    @BeforeEach
    fun setUp() {
        client = mockk()
        prepared = mockk()
        executeCalls.set(0)
        tuples.clear()
        every { client.preparedQuery(any()) } returns prepared
        every { prepared.execute(any<Tuple>()) } answers {
            executeCalls.incrementAndGet()
            tuples.add(firstArg<Tuple>())
            Future.succeededFuture(emptyRows())
        }
        service = StockService(mockk<Pool>())
    }

    private fun rowOf(vararg values: Any?): Row = mockk {
        every { getValue(any<Int>()) } answers { values.getOrNull(firstArg<Int>()) }
    }

    private fun rowIterator(rows: Iterator<Row>): RowIterator<Row> = mockk {
        every { hasNext() } answers { rows.hasNext() }
        every { next() } answers { rows.next() }
    }

    private fun emptyRows(): RowSet<Row> = mockk {
        every { size() } returns 0
        every { iterator() } returns rowIterator(emptyList<Row>().iterator())
    }

    private fun rowsOf(vararg rows: Row): RowSet<Row> = mockk {
        every { size() } returns rows.size
        every { iterator() } returns rowIterator(rows.iterator())
    }

    /** 按执行次序分发 RowSet；未登记的执行返回空集 */
    private fun sequence(vararg rowsets: RowSet<Row>) {
        every { prepared.execute(any<Tuple>()) } answers {
            executeCalls.incrementAndGet()
            tuples.add(firstArg<Tuple>())
            Future.succeededFuture(rowsets.getOrNull(executeCalls.get() - 1) ?: emptyRows())
        }
    }

    private fun stockRow(
        id: String,
        lotId: String?,
        qty: Int,
        locked: Int,
        cost: Int,
        base: Int = qty * 10,
        lockedBase: Int = 0,
    ): Row = rowOf(id, lotId, BigDecimal(qty), BigDecimal(locked), BigDecimal(cost), BigDecimal(base), BigDecimal(lockedBase))

    private fun materialRow(status: String, batchControl: Boolean): Row = rowOf(status, batchControl)

    private fun lotRow(materialId: String, expiry: LocalDate): Row = rowOf(materialId, expiry)

    /** UnitConversionService.loadMaterial 的物资行：status, unit_model_status, base_unit, base_quantity_scale */
    private fun modelMaterialRow(status: String = "ACTIVE", modelStatus: String = "ACTIVE"): Row =
        rowOf(status, modelStatus, "片", 4)

    /** UnitConversionService.loadDefaultSpec 的规格行：spec_id, input_unit, base_ratio, is_default, status */
    private fun specRow(ratio: BigDecimal = BigDecimal.TEN): Row =
        rowOf("spec-1", "盒", ratio, true, "ACTIVE")

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    // ========================================================================
    //  validateRequisitionMaterials
    // ========================================================================

    @Test
    fun `validateRequisitionMaterials passes when every material is active`() {
        sequence(rowsOf(rowOf("m-1"), rowOf("m-2")))
        val future = service.validateRequisitionMaterials(client, listOf("m-1", "m-2"))
        assertEquals(true, future.succeeded())
        assertEquals(1, executeCalls.get())
    }

    @Test
    fun `validateRequisitionMaterials fails when a material is missing or inactive`() {
        sequence(rowsOf(rowOf("m-1")))
        val error = failureOf(service.validateRequisitionMaterials(client, listOf("m-1", "m-2")))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("m-2"))
    }

    // ========================================================================
    //  reservePackageStock
    // ========================================================================

    @Test
    fun `reservePackageStock rejects invalid input before touching connection`() {
        val error = failureOf(
            service.reservePackageStock(client, StockService.RequisitionReserveCommand("", emptyList())),
        )
        assertTrue(error is IllegalArgumentException)
        verify(exactly = 0) { client.preparedQuery(any()) }
    }

    @Test
    fun `reservePackageStock locks quantity on transaction connection`() {
        // 015 序列：预览库存 → 规格解析（物资+规格）→ 锁库存 → 物资批控 → UPDATE
        sequence(
            rowsOf(stockRow("s-1", null, 10, 0, 50)),
            rowsOf(modelMaterialRow()),
            rowsOf(specRow()),
            rowsOf(stockRow("s-1", null, 10, 0, 50)),
            rowsOf(materialRow("ACTIVE", false)),
        )
        val future = service.reservePackageStock(
            client,
            StockService.RequisitionReserveCommand(
                warehouse = "西药库",
                items = listOf(StockService.RequisitionReserveItem("m-1", null, BigDecimal(5))),
            ),
        )
        assertEquals(true, future.succeeded())
        assertEquals(6, executeCalls.get())
        // 第 6 次执行为 UPDATE：LOCKED_QUANTITY=0+5、LOCKED_BASE_QUANTITY=0+50、LAST_UPDATED、WHERE ID
        assertEquals(0, BigDecimal(5).compareTo(tuples[5].getValue(0) as BigDecimal))
        assertEquals(0, BigDecimal(50).compareTo(tuples[5].getValue(1) as BigDecimal))
        assertEquals("s-1", tuples[5].getValue(3))
    }

    @Test
    fun `reservePackageStock fails when stock missing`() {
        sequence(emptyRows())
        val error = failureOf(
            service.reservePackageStock(
                client,
                StockService.RequisitionReserveCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReserveItem("m-1", null, BigDecimal(5))),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("no stock"))
    }

    @Test
    fun `reservePackageStock fails when available quantity insufficient`() {
        // base=30（10 盒 × 3），请求 5 盒 → 需 50 基础数量 > 30-0 可用
        sequence(
            rowsOf(stockRow("s-1", null, 10, 8, 50, base = 30)),
            rowsOf(modelMaterialRow()),
            rowsOf(specRow()),
            rowsOf(stockRow("s-1", null, 10, 8, 50, base = 30)),
            rowsOf(materialRow("ACTIVE", false)),
        )
        val error = failureOf(
            service.reservePackageStock(
                client,
                StockService.RequisitionReserveCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReserveItem("m-1", null, BigDecimal(5))),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("insufficient stock"))
        // 校验失败即停，不产生 UPDATE
        assertEquals(5, executeCalls.get())
    }

    @Test
    fun `reservePackageStock fails when material not active`() {
        sequence(
            rowsOf(stockRow("s-1", null, 10, 0, 50)),
            rowsOf(modelMaterialRow(status = "PENDING")),
        )
        val error = failureOf(
            service.reservePackageStock(
                client,
                StockService.RequisitionReserveCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReserveItem("m-1", null, BigDecimal(5))),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("not ACTIVE"))
    }

    @Test
    fun `reservePackageStock requires a lot for batch controlled material`() {
        sequence(
            rowsOf(stockRow("s-1", null, 10, 0, 50)),
            rowsOf(modelMaterialRow()),
            rowsOf(specRow()),
            rowsOf(stockRow("s-1", null, 10, 0, 50)),
            rowsOf(materialRow("ACTIVE", true)),
        )
        val error = failureOf(
            service.reservePackageStock(
                client,
                StockService.RequisitionReserveCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReserveItem("m-1", null, BigDecimal(5))),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("requires a lot"))
    }

    // ========================================================================
    //  releasePackageReservation
    // ========================================================================

    @Test
    fun `releasePackageReservation decrements locked quantity`() {
        // 015 序列：预览库存 → 规格解析（物资+规格）→ 锁库存 → UPDATE
        sequence(
            rowsOf(stockRow("s-1", null, 10, 10, 50, lockedBase = 100)),
            rowsOf(modelMaterialRow()),
            rowsOf(specRow()),
            rowsOf(stockRow("s-1", null, 10, 10, 50, lockedBase = 100)),
        )
        val future = service.releasePackageReservation(
            client,
            StockService.RequisitionReleaseCommand(
                warehouse = "西药库",
                items = listOf(StockService.RequisitionReleaseItem("m-1", null, BigDecimal(4))),
            ),
        )
        assertEquals(true, future.succeeded())
        assertEquals(5, executeCalls.get())
        // 第 5 次为 UPDATE：LOCKED_QUANTITY=10-4、LOCKED_BASE_QUANTITY=100-40
        assertEquals(0, BigDecimal(6).compareTo(tuples[4].getValue(0) as BigDecimal))
        assertEquals(0, BigDecimal(60).compareTo(tuples[4].getValue(1) as BigDecimal))
    }

    @Test
    fun `releasePackageReservation fails when locked quantity insufficient`() {
        sequence(
            rowsOf(stockRow("s-1", null, 10, 3, 50)),
            rowsOf(modelMaterialRow()),
            rowsOf(specRow()),
            rowsOf(stockRow("s-1", null, 10, 3, 50)),
        )
        val error = failureOf(
            service.releasePackageReservation(
                client,
                StockService.RequisitionReleaseCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReleaseItem("m-1", null, BigDecimal(4))),
                ),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("reservation corrupted"))
    }

    @Test
    fun `releasePackageReservation rejects invalid input before touching connection`() {
        val error = failureOf(
            service.releasePackageReservation(client, StockService.RequisitionReleaseCommand("", emptyList())),
        )
        assertTrue(error is IllegalArgumentException)
        verify(exactly = 0) { client.preparedQuery(any()) }
    }

    // ========================================================================
    //  confirmReservedPackageTransfer
    // ========================================================================

    private fun transferCommand(vararg items: StockService.RequisitionTransferItem): StockService.RequisitionTransferCommand =
        StockService.RequisitionTransferCommand(
            sourceWarehouse = "西药库",
            destinationWarehouse = "一护理站",
            requisitionId = "req-1",
            requisitionNo = "PH-REQ-1",
            dispensedBy = "user-1",
            items = items.toList(),
        )

    @Test
    fun `confirmReservedPackageTransfer rejects same warehouse before touching connection`() {
        val command = StockService.RequisitionTransferCommand(
            sourceWarehouse = "西药库",
            destinationWarehouse = "西药库",
            requisitionId = "req-1",
            requisitionNo = "PH-REQ-1",
            dispensedBy = "user-1",
            items = listOf(StockService.RequisitionTransferItem("m-1", null, BigDecimal(5))),
        )
        val error = failureOf(service.confirmReservedPackageTransfer(client, command))
        assertTrue(error is ConflictException)
        verify(exactly = 0) { client.preparedQuery(any()) }
    }

    @Test
    fun `confirmReservedPackageTransfer rejects invalid item`() {
        val error = failureOf(
            service.confirmReservedPackageTransfer(
                client,
                transferCommand(StockService.RequisitionTransferItem("", null, BigDecimal.ZERO)),
            ),
        )
        assertTrue(error is IllegalArgumentException)
        verify(exactly = 0) { client.preparedQuery(any()) }
    }

    @Test
    fun `confirmReservedPackageTransfer writes both operations and both sides details`() {
        // 015 序列：两件逐一 预览库存→规格解析（物资+规格）→ 锁源库存→物资批控→批次 → 目标库存
        sequence(
            rowsOf(stockRow("src-1", null, 10, 5, 30, base = 100, lockedBase = 50)),   // 1 预览源 m-1
            rowsOf(modelMaterialRow()),                                                  // 2 规格解析 m-1
            rowsOf(specRow()),                                                           // 3 规格解析 m-1
            rowsOf(stockRow("src-2", "lot-2", 10, 5, 0, base = 100, lockedBase = 50)),  // 4 预览源 m-2
            rowsOf(modelMaterialRow()),                                                  // 5 规格解析 m-2
            rowsOf(specRow()),                                                           // 6 规格解析 m-2
            rowsOf(stockRow("src-1", null, 10, 5, 30, base = 100, lockedBase = 50)),    // 7 锁源 m-1
            rowsOf(materialRow("ACTIVE", false)),                                        // 8 物资 m-1
            rowsOf(stockRow("src-2", "lot-2", 10, 5, 0, base = 100, lockedBase = 50)),  // 9 锁源 m-2
            rowsOf(materialRow("ACTIVE", true)),                                         // 10 物资 m-2
            rowsOf(lotRow("m-2", LocalDate.of(2099, 1, 1))),                            // 11 批次 m-2
            rowsOf(stockRow("dst-1", null, 0, 0, 0)),                                    // 12 目标 m-1
            rowsOf(stockRow("dst-2", "lot-2", 0, 0, 0)),                                 // 13 目标 m-2
        )
        val future = service.confirmReservedPackageTransfer(
            client,
            transferCommand(
                StockService.RequisitionTransferItem("m-1", null, BigDecimal(5)),
                StockService.RequisitionTransferItem("m-2", "lot-2", BigDecimal(5)),
            ),
        )
        assertEquals(true, future.succeeded())
        val result = future.result()
        assertNotNull(result.outboundOperationId)
        assertNotNull(result.inboundOperationId)
        assertTrue(result.outboundOperationId != result.inboundOperationId)
        assertEquals(2, result.items.size)
        // stableOrder 按 material_id 升序
        assertEquals("m-1", result.items[0].materialId)
        assertEquals("m-2", result.items[1].materialId)
        // 成本守恒：m-1 单价 30/10=3；m-2 库存成本 0
        assertEquals(0, BigDecimal(3.0).setScale(4).compareTo(result.items[0].unitCost))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.items[1].unitCost))
        assertEquals(23, executeCalls.get())
        // updateSources(1) 为第 20 次执行：QUANTITY=10-5、BASE=100-50、LOCKED=5-5、LOCKED_BASE=50-50、TOTAL_COST=30-15
        assertEquals(0, BigDecimal(5).compareTo(tuples[19].getValue(0) as BigDecimal))
        assertEquals(0, BigDecimal(50).compareTo(tuples[19].getValue(1) as BigDecimal))
        assertEquals(0, BigDecimal.ZERO.compareTo(tuples[19].getValue(2) as BigDecimal))
        assertEquals(0, BigDecimal.ZERO.compareTo(tuples[19].getValue(3) as BigDecimal))
        assertEquals(0, BigDecimal(15).compareTo(tuples[19].getValue(4) as BigDecimal))
        // updateTargets(1) 为第 22 次执行：QUANTITY=0+5、BASE=0+50、TOTAL_COST=0+15
        assertEquals(0, BigDecimal(5).compareTo(tuples[21].getValue(0) as BigDecimal))
        assertEquals(0, BigDecimal(50).compareTo(tuples[21].getValue(1) as BigDecimal))
        assertEquals(0, BigDecimal(15).compareTo(tuples[21].getValue(2) as BigDecimal))
    }

    @Test
    fun `confirmReservedPackageTransfer creates missing target stock row`() {
        sequence(
            rowsOf(stockRow("src-1", null, 10, 5, 30, lockedBase = 50)),  // 1 预览源
            rowsOf(modelMaterialRow()),                                    // 2 规格解析物资
            rowsOf(specRow()),                                             // 3 规格解析规格
            rowsOf(stockRow("src-1", null, 10, 5, 30, lockedBase = 50)),  // 4 锁源
            rowsOf(materialRow("ACTIVE", false)),                          // 5 物资
            emptyRows(),                                                   // 6 目标查询：不存在
        )
        val future = service.confirmReservedPackageTransfer(
            client,
            transferCommand(StockService.RequisitionTransferItem("m-1", null, BigDecimal(5))),
        )
        assertEquals(true, future.succeeded())
        assertEquals(13, executeCalls.get())
        // 目标 INSERT 为第 7 次执行：QUANTITY=0、LOCKED=0、TOTAL_COST=0
        assertEquals(0, BigDecimal.ZERO.compareTo(tuples[6].getValue(4) as BigDecimal))
        assertEquals(0, BigDecimal.ZERO.compareTo(tuples[6].getValue(5) as BigDecimal))
    }

    @Test
    fun `confirmReservedPackageTransfer fails when reservation insufficient`() {
        sequence(
            rowsOf(stockRow("src-1", null, 10, 3, 30)),   // 1 预览源
            rowsOf(modelMaterialRow()),                   // 2 规格解析物资
            rowsOf(specRow()),                            // 3 规格解析规格
            rowsOf(stockRow("src-1", null, 10, 3, 30)),   // 4 锁源 locked=3 < 5
            rowsOf(materialRow("ACTIVE", false)),         // 5 物资
        )
        val error = failureOf(
            service.confirmReservedPackageTransfer(
                client,
                transferCommand(StockService.RequisitionTransferItem("m-1", null, BigDecimal(5))),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("insufficient reservation"))
    }

    @Test
    fun `confirmReservedPackageTransfer fails when expired lot`() {
        sequence(
            rowsOf(stockRow("src-1", "lot-1", 10, 5, 30)),                 // 1 预览源
            rowsOf(modelMaterialRow()),                                    // 2 规格解析物资
            rowsOf(specRow()),                                             // 3 规格解析规格
            rowsOf(stockRow("src-1", "lot-1", 10, 5, 30)),                 // 4 锁源
            rowsOf(materialRow("ACTIVE", true)),                           // 5 物资（批次管控）
            rowsOf(lotRow("m-1", LocalDate.of(2020, 1, 1))),               // 6 批次已过期
        )
        val error = failureOf(
            service.confirmReservedPackageTransfer(
                client,
                transferCommand(StockService.RequisitionTransferItem("m-1", "lot-1", BigDecimal(5))),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("expired"))
    }
}
