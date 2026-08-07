package com.ovaphlow.crate.inventories

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class PackageInboundTest {
    private lateinit var pool: Pool
    private lateinit var connection: SqlConnection
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private lateinit var service: StockService
    private val executeCalls = AtomicInteger()

    @BeforeEach
    fun setUp() {
        pool = mockk()
        connection = mockk()
        prepared = mockk()
        executeCalls.set(0)
        every { connection.preparedQuery(any()) } returns prepared
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(emptyRows())
        service = StockService(pool)
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

    private fun indexedRow(values: Map<Int, Any?>): Row = mockk {
        every { getValue(any<Int>()) } answers { values[firstArg<Int>()] }
    }

    private fun materialRow(status: String = "ACTIVE", batchControl: Boolean = true): Row = indexedRow(mapOf(0 to status, 1 to batchControl))
    private fun lotRow(materialId: String = "material-1", expiry: LocalDate? = LocalDate.now().plusDays(30)): Row = indexedRow(mapOf(0 to materialId, 1 to expiry))
    private fun stockRow(): Row = indexedRow(mapOf(0 to "stock-1", 1 to BigDecimal.ONE, 2 to BigDecimal.TEN))
    /** UnitConversionService.loadMaterial 的物资行：status, unit_model_status, base_unit, base_quantity_scale */
    private fun modelMaterialRow(status: String = "ACTIVE", modelStatus: String = "ACTIVE"): Row =
        indexedRow(mapOf(0 to status, 1 to modelStatus, 2 to "片", 3 to 4))
    /** UnitConversionService.loadDefaultSpec 的规格行：spec_id, input_unit, base_ratio, is_default, status */
    private fun specRow(ratio: BigDecimal = BigDecimal.TEN): Row =
        indexedRow(mapOf(0 to "spec-1", 1 to "盒", 2 to ratio, 3 to true, 4 to "ACTIVE"))

    private fun command(
        quantity: BigDecimal = BigDecimal.ONE,
        unitCost: BigDecimal = BigDecimal.valueOf(3.5),
        lotId: String? = "lot-1",
    ) = StockService.PackageInboundCommand("西药库", "material-1", lotId, quantity, unitCost, "return test")

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    @Test
    fun `inbound rejects non positive quantity and negative cost`() {
        val quantityError = failureOf(service.confirmPackageInbound(connection, command(quantity = BigDecimal.ZERO)))
        val costError = failureOf(service.confirmPackageInbound(connection, command(unitCost = BigDecimal.valueOf(-1))))
        assertTrue(quantityError.message!!.contains("quantity"))
        assertTrue(costError.message!!.contains("unit_cost"))
        verify(exactly = 0) { connection.preparedQuery(any()) }
    }

    @Test
    fun `inbound rejects inactive material`() {
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(rowsOf(materialRow(status = "INACTIVE")))
        val error = failureOf(service.confirmPackageInbound(connection, command()))
        assertTrue(error.message!!.contains("not ACTIVE"))
        verify(exactly = 1) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `inbound rejects lot belonging to another material`() {
        // 015 序列：规格解析（物资+规格）→ 物资批控 → 批次
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(modelMaterialRow()))
                2 -> Future.succeededFuture(rowsOf(specRow()))
                3 -> Future.succeededFuture(rowsOf(materialRow()))
                else -> Future.succeededFuture(rowsOf(lotRow(materialId = "other-material")))
            }
        }
        val error = failureOf(service.confirmPackageInbound(connection, command()))
        assertTrue(error.message!!.contains("does not belong"))
        verify(exactly = 4) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `inbound writes operation detail and updates existing stock on same connection`() {
        // 015 序列：规格解析（物资+规格）→ 物资批控 → 批次 → 库存查询 → 出库单 → 明细 → 库存累加
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(modelMaterialRow()))
                2 -> Future.succeededFuture(rowsOf(specRow()))
                3 -> Future.succeededFuture(rowsOf(materialRow()))
                4 -> Future.succeededFuture(rowsOf(lotRow()))
                5 -> Future.succeededFuture(rowsOf(stockRow()))
                else -> Future.succeededFuture(emptyRows())
            }
        }
        val result = service.confirmPackageInbound(connection, command())
        assertEquals("lot-1", result.result().lotId)
        assertEquals(0, BigDecimal.valueOf(3.5).compareTo(result.result().unitCost))
        verify(exactly = 8) { connection.preparedQuery(any()) }
        verify(exactly = 8) { prepared.execute(any<Tuple>()) }
        verify(exactly = 0) { pool.preparedQuery(any()) }
    }

    @Test
    fun `inbound creates stock row when no existing row`() {
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(modelMaterialRow()))
                2 -> Future.succeededFuture(rowsOf(specRow()))
                3 -> Future.succeededFuture(rowsOf(materialRow()))
                4 -> Future.succeededFuture(rowsOf(lotRow()))
                5 -> Future.succeededFuture(emptyRows())
                else -> Future.succeededFuture(emptyRows())
            }
        }
        val result = service.confirmPackageInbound(connection, command())
        assertTrue(result.succeeded())
        verify(exactly = 8) { connection.preparedQuery(any()) }
    }
}
