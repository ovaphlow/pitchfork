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

/**
 * 016 退药回库（基础数量 INBOUND）单元测试。
 * 验证物资/批次校验、库存累加与同连接写入；不依赖真实数据库。
 */
class ReturnInboundTest {
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

    /** loadMaterial 行：status, base_unit, quantity_scale, batch_control */
    private fun materialRow(status: String = "ACTIVE", batchControl: Boolean = true): Row =
        indexedRow(mapOf(0 to status, 1 to "片", 2 to 0, 3 to batchControl))

    private fun lotRow(materialId: String = "material-1", expiry: LocalDate? = LocalDate.now().plusDays(30)): Row =
        indexedRow(mapOf(0 to materialId, 1 to expiry))

    /** loadStock 行：id, lot_id, quantity, locked_quantity, total_cost */
    private fun stockRow(quantity: BigDecimal = BigDecimal.TEN): Row =
        indexedRow(mapOf(0 to "stock-1", 1 to "lot-1", 2 to quantity, 3 to BigDecimal.ZERO, 4 to BigDecimal.valueOf(50)))

    private fun command(
        quantity: BigDecimal = BigDecimal.ONE,
        unitCost: BigDecimal = BigDecimal("3.5"),
        lotId: String? = "lot-1",
    ) = StockService.ReturnInboundCommand("西药库", "material-1", lotId, quantity, unitCost, "return test")

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    @Test
    fun `inbound rejects non positive quantity and negative cost before touching db`() {
        val quantityError = failureOf(service.confirmReturnInbound(connection, command(quantity = BigDecimal.ZERO)))
        val costError = failureOf(service.confirmReturnInbound(connection, command(unitCost = BigDecimal.valueOf(-1))))
        assertTrue(quantityError.message!!.contains("quantity"))
        assertTrue(costError.message!!.contains("unit_cost"))
        verify(exactly = 0) { connection.preparedQuery(any()) }
    }

    @Test
    fun `inbound rejects inactive material`() {
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(rowsOf(materialRow(status = "INACTIVE")))
        val error = failureOf(service.confirmReturnInbound(connection, command()))
        assertTrue(error.message!!.contains("not ACTIVE"))
        verify(exactly = 1) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `inbound rejects lot belonging to another material`() {
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(materialRow()))
                else -> Future.succeededFuture(rowsOf(lotRow(materialId = "other-material")))
            }
        }
        val error = failureOf(service.confirmReturnInbound(connection, command()))
        assertTrue(error.message!!.contains("does not belong"))
        verify(exactly = 2) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `inbound writes operation detail and updates existing stock on same connection`() {
        // 序列：物资 → 批次 → 出库单 → 明细 → 库存（FOR UPDATE）→ 库存累加
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(materialRow()))
                2 -> Future.succeededFuture(rowsOf(lotRow()))
                3, 4 -> Future.succeededFuture(emptyRows())
                5 -> Future.succeededFuture(rowsOf(stockRow()))
                else -> Future.succeededFuture(emptyRows())
            }
        }
        val result = service.confirmReturnInbound(connection, command())
        assertEquals("lot-1", result.result().lotId)
        assertEquals(0, BigDecimal("3.5").compareTo(result.result().unitCost))
        verify(exactly = 6) { connection.preparedQuery(any()) }
        verify(exactly = 6) { prepared.execute(any<Tuple>()) }
        verify(exactly = 0) { pool.preparedQuery(any()) }
    }
}
