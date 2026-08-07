package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

class ReturnServiceTest {
    private lateinit var pool: Pool
    private lateinit var connection: SqlConnection
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private lateinit var inboundPort: InventoryInboundPort
    private lateinit var service: ReturnService
    private val executeCalls = AtomicInteger()

    @BeforeEach
    fun setUp() {
        pool = mockk()
        connection = mockk()
        prepared = mockk()
        executeCalls.set(0)
        inboundPort = mockk()
        every { connection.preparedQuery(any()) } returns prepared
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(emptyRows())
        every { pool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) } answers {
            firstArg<Function<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        service = ReturnService(pool, inboundPort)
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

    private fun row(values: Map<String, Any?>): Row = mockk {
        every { getValue(any<String>()) } answers { values[firstArg<String>()] }
        every { getString(any<String>()) } answers { values[firstArg<String>()]?.toString() }
    }

    private fun sourceRow(
        dispenseStatus: String = "DISPENSED",
        unit: String = "PACKAGE",
        stockDetail: String? = "out-detail-1",
        quantity: BigDecimal = BigDecimal.TEN,
        unitCost: BigDecimal = BigDecimal.valueOf(3.5),
    ): Row = row(
        mapOf(
            "dispense_id" to "dispense-1",
            "dispense_item_id" to "dispense-item-1",
            "patient_id" to "patient-1",
            "warehouse" to "西药库",
            "dispense_status" to dispenseStatus,
            "material_id" to "material-1",
            "lot_id" to "lot-1",
            "dispensed_quantity" to quantity,
            "unit" to unit,
            "unit_cost" to unitCost,
            "original_stock_operation_detail_id" to stockDetail,
        ),
    )

    private fun returnHeader(status: String = "PENDING"): Row = row(
        mapOf(
            "id" to "return-1",
            "return_no" to "RT-1",
            "original_dispense_id" to "dispense-1",
            "patient_id" to "patient-1",
            "return_reason" to "老人未使用",
            "status" to status,
            "operator" to "护士甲",
            "metadata" to null,
            "created_at" to "2026-08-06T08:00:00+08:00",
            "confirmed_at" to null,
        ),
    )

    private fun returnSourceRow(): Row = row(
        mapOf(
            "return_item_id" to "return-item-1",
            "return_status" to "PENDING",
            "dispense_id" to "dispense-1",
            "dispense_item_id" to "dispense-item-1",
            "patient_id" to "patient-1",
            "warehouse" to "西药库",
            "dispense_status" to "DISPENSED",
            "return_quantity" to BigDecimal.ONE,
            "return_stock_operation_detail_id" to null,
            "material_id" to "material-1",
            "lot_id" to "lot-1",
            "unit" to "PACKAGE",
            "unit_cost" to BigDecimal.valueOf(3.5),
            "original_stock_operation_detail_id" to "out-detail-1",
        ),
    )

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    @Test
    fun `create validates required fields before transaction`() {
        val error = failureOf(service.createFromDispense(JsonObject().put("dispense_id", "dispense-1")))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("dispense_item_id"))
        verify(exactly = 0) { pool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `create rejects non dispensed source without writing return`() {
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(rowsOf(sourceRow(dispenseStatus = "DISPENSING")))
        val error = failureOf(service.createFromDispense(validBody()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("DISPENSED"))
        verify(exactly = 1) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `create rejects quantity beyond remaining source quantity`() {
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(sourceRow(quantity = BigDecimal.ONE)))
                2 -> Future.succeededFuture(rowsOf(row(mapOf("reserved_quantity" to BigDecimal.ONE))))
                else -> Future.succeededFuture(emptyRows())
            }
        }
        val error = failureOf(service.createFromDispense(validBody().put("quantity", 1.0)))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("remaining"))
        verify(exactly = 2) { prepared.execute(any<Tuple>()) }
    }

    @Test
    fun `confirm calls inbound port on transaction connection and returns confirmed record`() {
        every { prepared.execute(any<Tuple>()) } answers {
            when (executeCalls.incrementAndGet()) {
                1 -> Future.succeededFuture(rowsOf(returnHeader("PENDING")))
                2 -> Future.succeededFuture(rowsOf(returnSourceRow()))
                5 -> Future.succeededFuture(rowsOf(returnHeader("CONFIRMED")))
                else -> Future.succeededFuture(emptyRows())
            }
        }
        every { inboundPort.confirmPackageInbound(connection, any()) } returns Future.succeededFuture(
            PackageInboundResult("in-detail-1", "lot-1", BigDecimal.valueOf(3.5)),
        )

        val result = service.confirm("return-1", JsonObject().put("operator", "药师乙"))

        verify(exactly = 1) {
            inboundPort.confirmPackageInbound(connection, withArg {
                assertEquals("西药库", it.warehouse)
                assertEquals("material-1", it.materialId)
                assertEquals("lot-1", it.lotId)
                assertEquals(0, BigDecimal.ONE.compareTo(it.quantity))
                assertEquals(0, BigDecimal.valueOf(3.5).compareTo(it.unitCost))
            })
        }
        assertEquals("CONFIRMED", result.result().getString("status"))
    }

    @Test
    fun `confirm retry does not call inbound port twice`() {
        every { prepared.execute(any<Tuple>()) } answers { Future.succeededFuture(rowsOf(returnHeader("CONFIRMED"))) }
        val result = service.confirm("return-1", JsonObject().put("operator", "药师乙"))
        verify(exactly = 0) { inboundPort.confirmPackageInbound(any(), any()) }
        assertEquals("CONFIRMED", result.result().getString("status"))
    }

    @Test
    fun `cancel rejects confirmed return`() {
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(rowsOf(returnHeader("CONFIRMED")))
        val error = failureOf(service.cancel("return-1"))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot cancel"))
    }

    private fun validBody(): JsonObject = JsonObject()
        .put("dispense_id", "dispense-1")
        .put("dispense_item_id", "dispense-item-1")
        .put("quantity", 1.0)
        .put("return_reason", "老人未使用")
        .put("operator", "护士甲")
        .put("restockable", true)
}
