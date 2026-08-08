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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * StockService 013 护理站申领：基础数量预留/释放/调拨单元测试。
 * mock SqlConnection + PreparedQuery，按执行次序返回 RowSet；不依赖数据库。
 */
class RequisitionStockServiceTest {
    private lateinit var service: StockService
    private lateinit var client: SqlConnection
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private val executeCalls = AtomicInteger()

    @BeforeEach
    fun setUp() {
        client = mockk()
        prepared = mockk()
        executeCalls.set(0)
        every { client.preparedQuery(any()) } returns prepared
        every { prepared.execute(any<Tuple>()) } answers {
            executeCalls.incrementAndGet()
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

    private fun sequence(vararg rowsets: RowSet<Row>) {
        every { prepared.execute(any<Tuple>()) } answers {
            executeCalls.incrementAndGet()
            Future.succeededFuture(rowsets.getOrNull(executeCalls.get() - 1) ?: emptyRows())
        }
    }

    /** loadStock 行：id, lot_id, quantity, locked_quantity, total_cost */
    private fun stockRow(
        id: String,
        lotId: String?,
        qty: BigDecimal,
        locked: BigDecimal = BigDecimal.ZERO,
        totalCost: BigDecimal = BigDecimal.ZERO,
    ): Row = rowOf(id, lotId, qty, locked, totalCost)

    /** loadMaterial 行：status, base_unit, quantity_scale, batch_control */
    private fun materialRow(
        status: String = "ACTIVE",
        baseUnit: String = "片",
        scale: Int = 0,
        batchControl: Boolean = false,
    ): Row = rowOf(status, baseUnit, scale, batchControl)

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    // ========================================================================
    //  输入校验（不触数据库）
    // ========================================================================

    @Test
    fun `reserveStock rejects blank warehouse and empty items`() {
        val emptyItems = failureOf(
            service.reserveStock(
                client,
                StockService.RequisitionReserveCommand("", items = emptyList()),
            ),
        )
        val noItems = failureOf(
            service.reserveStock(
                client,
                StockService.RequisitionReserveCommand("西药库", items = emptyList()),
            ),
        )
        assertTrue(emptyItems.message!!.contains("warehouse"))
        assertTrue(noItems.message!!.contains("item"))
        verify(exactly = 0) { client.preparedQuery(any()) }
    }

    @Test
    fun `releaseReservation rejects invalid item`() {
        val error = failureOf(
            service.releaseReservation(
                client,
                StockService.RequisitionReleaseCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReleaseItem("mat-1", null, BigDecimal.ZERO)),
                ),
            ),
        )
        assertTrue(error.message!!.contains("quantity"))
    }

    @Test
    fun `confirmReservedTransfer rejects same source and destination warehouse`() {
        val error = failureOf(
            service.confirmReservedTransfer(
                client,
                StockService.RequisitionTransferCommand(
                    sourceWarehouse = "西药库",
                    destinationWarehouse = "西药库",
                    requisitionId = "req-1",
                    requisitionNo = "PH-REQ-1",
                    dispensedBy = "user-1",
                    items = listOf(StockService.RequisitionTransferItem("mat-1", null, BigDecimal.ONE)),
                ),
            ),
        )
        assertTrue(error is ConflictException)
    }

    // ========================================================================
    //  预留流程（基础数量，无批次）
    //  序列：库存预览 → 物资 → 库存锁定 → 更新 locked_quantity
    // ========================================================================

    @Test
    fun `reserveStock increments locked base quantity`() {
        sequence(
            rowsOf(stockRow("stock-1", null, BigDecimal.TEN, locked = BigDecimal.ZERO, totalCost = BigDecimal.valueOf(20))),
            rowsOf(materialRow()),
            rowsOf(stockRow("stock-1", null, BigDecimal.TEN, locked = BigDecimal.ZERO, totalCost = BigDecimal.valueOf(20))),
            emptyRows(),
        )
        val result = service.reserveStock(
            client,
            StockService.RequisitionReserveCommand(
                warehouse = "西药库",
                items = listOf(StockService.RequisitionReserveItem("mat-1", null, BigDecimal.valueOf(3))),
            ),
        )
        assertTrue(result.succeeded())
        verify(exactly = 4) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `reserveStock rejects insufficient availability`() {
        sequence(
            rowsOf(stockRow("stock-1", null, BigDecimal.ONE, locked = BigDecimal.ZERO, totalCost = BigDecimal.ONE)),
            rowsOf(materialRow()),
            rowsOf(stockRow("stock-1", null, BigDecimal.ONE, locked = BigDecimal.ZERO, totalCost = BigDecimal.ONE)),
        )
        val error = failureOf(
            service.reserveStock(
                client,
                StockService.RequisitionReserveCommand(
                    warehouse = "西药库",
                    items = listOf(StockService.RequisitionReserveItem("mat-1", null, BigDecimal.valueOf(2))),
                ),
            ),
        )
        assertTrue(error.message!!.contains("insufficient stock"))
    }

    @Test
    fun `releaseReservation decreases locked quantity`() {
        sequence(
            rowsOf(materialRow()),
            rowsOf(stockRow("stock-1", null, BigDecimal.TEN, locked = BigDecimal.valueOf(3))),
            emptyRows(),
        )
        val result = service.releaseReservation(
            client,
            StockService.RequisitionReleaseCommand(
                warehouse = "西药库",
                items = listOf(StockService.RequisitionReleaseItem("mat-1", null, BigDecimal.valueOf(3))),
            ),
        )
        assertTrue(result.succeeded())
        verify(exactly = 3) { prepared.execute(any<Tuple>()) }
    }
}
