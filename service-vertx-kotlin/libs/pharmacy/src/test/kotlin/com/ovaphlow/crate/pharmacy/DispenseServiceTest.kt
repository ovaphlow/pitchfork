package com.ovaphlow.crate.pharmacy

import io.vertx.core.Future
import io.vertx.core.json.JsonObject
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.function.Function

/**
 * DispenseService 单元测试。
 * 覆盖订单归属/类型校验、重复接方、状态机、数量/批次校验、同连接内部端口调用、
 * confirm 幂等与失败回滚、旧通用入口阻断；全部不依赖数据库。
 *
 * 事务证据约定：createFromMedicalOrder / review / start / confirm / cancel 的事务内
 * 端口调用必须收到 `withTransaction` 传入的 SqlConnection，而不是调用 Pool ——
 * 测试通过 verify(reader/outboundPort)(mockConn) 断言复用外层连接。
 */
class DispenseServiceTest {

    private lateinit var service: DispenseService
    private lateinit var mockPool: Pool
    private lateinit var mockConn: SqlConnection
    private lateinit var connPrepared: PreparedQuery<RowSet<Row>>
    private lateinit var poolPrepared: PreparedQuery<RowSet<Row>>
    private lateinit var medicalOrderReader: MedicalOrderReader
    private lateinit var inventoryOutboundPort: InventoryOutboundPort

    @BeforeEach
    fun setUp() {
        mockPool = mockk<Pool>()
        mockConn = mockk<SqlConnection>()
        connPrepared = mockk<PreparedQuery<RowSet<Row>>>()
        poolPrepared = mockk<PreparedQuery<RowSet<Row>>>()
        medicalOrderReader = mockk<MedicalOrderReader>()
        inventoryOutboundPort = mockk<InventoryOutboundPort>()

        // 事务内的所有 SQL 都走同一个 mockConn；insert/update 的执行结果不读行值
        every { mockConn.preparedQuery(any()) } returns connPrepared
        every { connPrepared.execute(any<Tuple>()) } returns Future.succeededFuture(emptyRowSet())
        every { mockPool.preparedQuery(any()) } returns poolPrepared

        // withTransaction 直接在当前线程回调，lambda 收到 mockConn；
        // 用 match 通配参数（DispenseService 传入的 SAM 实例非 mock 引用），
        // answers 里取出实际传入的 Function 在 mockConn 上执行
        every {
            mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true })
        } answers {
            firstArg<Function<SqlConnection, Future<JsonObject>>>().apply(mockConn)
        }
        every { inventoryOutboundPort.validatePackageOutbound(mockConn, any()) } returns
            Future.succeededFuture(null as Void?)

        service = DispenseService(mockPool, medicalOrderReader, inventoryOutboundPort)
    }

    // ========================================================================
    //  测试辅助
    // ========================================================================

    /** RowSet.iterator() 返回 vertx 特有的 RowIterator，用真实迭代器委托 */
    private fun rowIterator(backing: Iterator<Row>): RowIterator<Row> = mockk {
        every { hasNext() } answers { backing.hasNext() }
        every { next() } answers { backing.next() }
    }

    private fun emptyRowSet(): RowSet<Row> = mockk {
        every { iterator() } returns rowIterator(emptyList<Row>().iterator())
        every { size() } returns 0
    }

    private fun rowsOf(vararg rows: Row): RowSet<Row> = mockk {
        every { iterator() } returns rowIterator(rows.iterator())
        every { size() } returns rows.size
    }

    private fun rowOf(values: Map<String, Any?>): Row = mockk {
        every { getValue(any<String>()) } answers { values[firstArg<String>()] }
    }

    private fun snapshot(
        encounterType: String = "ELDERLY_CARE",
        encounterStatus: String = "ACTIVE",
        orderType: String = "MEDICATION",
        orderStatus: String = "ACTIVE",
    ): MedicationOrderSnapshot =
        MedicationOrderSnapshot(
            orderId = "order-1",
            encounterId = "enc-1",
            patientId = "pat-1",
            patientName = "李阿姨",
            encounterNo = "A20260801001",
            encounterType = encounterType,
            encounterStatus = encounterStatus,
            orderType = orderType,
            orderClass = "LONG_TERM",
            orderStatus = orderStatus,
            orderContent = "降压药每日一次",
            doctor = "张医生",
            startTime = null,
            endTime = null,
            orderDetails = JsonObject().put("drug_name", "降压药"),
        )

    private fun validCreateBody(): JsonObject =
        JsonObject()
            .put("medical_order_id", "order-1")
            .put("warehouse", "西药库")
            .put("material_id", "mat-1")
            .put("lot_id", "lot-1")
            .put("dispensed_quantity", 1.0)
            .put("unit", "PACKAGE")

    /** 收集 Future 的失败异常；mock 环境同步完成，onSuccess 已执行 */
    private fun failureOf(future: Future<*>): Throwable {
        val captured = mutableListOf<Throwable>()
        future.onFailure { captured.add(it) }
        return captured.single()
    }

    // ========================================================================
    //  入参校验（事务前，不触碰 Pool / 端口）
    // ========================================================================

    @Test
    fun `createFromMedicalOrder rejects missing medical_order_id`() {
        val body = validCreateBody().apply { remove("medical_order_id") }
        val error = failureOf(service.createFromMedicalOrder(body))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("medical_order_id"))
        verify(exactly = 0) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `createFromMedicalOrder rejects missing warehouse`() {
        val body = validCreateBody().apply { remove("warehouse") }
        assertTrue(failureOf(service.createFromMedicalOrder(body)) is IllegalArgumentException)
        verify(exactly = 0) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `createFromMedicalOrder rejects missing material_id`() {
        val body = validCreateBody().apply { remove("material_id") }
        assertTrue(failureOf(service.createFromMedicalOrder(body)) is IllegalArgumentException)
        verify(exactly = 0) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `createFromMedicalOrder rejects non-positive quantity`() {
        val body = validCreateBody().put("dispensed_quantity", 0.0)
        val error = failureOf(service.createFromMedicalOrder(body))
        assertTrue(error.message!!.contains("dispensed_quantity"))
        verify(exactly = 0) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `createFromMedicalOrder rejects non-PACKAGE unit`() {
        val body = validCreateBody().put("unit", "SPLIT")
        val error = failureOf(service.createFromMedicalOrder(body))
        assertTrue(error.message!!.contains("PACKAGE"))
        verify(exactly = 0) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    // ========================================================================
    //  订单归属与药品类型校验（快照字段 → 失败且不写药房单）
    // ========================================================================

    @Test
    fun `createFromMedicalOrder rejects non-elderly encounter`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot(encounterType = "INPATIENT"))
        val error = failureOf(service.createFromMedicalOrder(validCreateBody()))
        assertTrue(error.message!!.contains("elderly"))
        // 校验失败后不得执行任何写入
        verify(exactly = 0) { connPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `createFromMedicalOrder rejects inactive encounter`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot(encounterStatus = "DISCHARGED"))
        val error = failureOf(service.createFromMedicalOrder(validCreateBody()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("encounter"))
        verify(exactly = 0) { connPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `createFromMedicalOrder rejects non-medication order`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot(orderType = "THERAPY"))
        val error = failureOf(service.createFromMedicalOrder(validCreateBody()))
        assertTrue(error.message!!.contains("medication"))
        verify(exactly = 0) { connPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `createFromMedicalOrder rejects inactive order`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot(orderStatus = "DISCONTINUED"))
        val error = failureOf(service.createFromMedicalOrder(validCreateBody()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("active"))
        verify(exactly = 0) { connPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `createFromMedicalOrder locks via transaction connection not pool`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot())
        val result = service.createFromMedicalOrder(validCreateBody())
        // 端口必须收到 withTransaction 传入的连接 —— 同连接证据，禁止端口内开新事务
        verify(exactly = 1) { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") }
        verify(exactly = 1) {
            inventoryOutboundPort.validatePackageOutbound(
                mockConn,
                withArg {
                    assertEquals("西药库", it.warehouse)
                    assertEquals("mat-1", it.materialId)
                    assertEquals("lot-1", it.lotId)
                    assertEquals(0, BigDecimal.ONE.compareTo(it.quantity))
                },
            )
        }
        verify(exactly = 1) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
        val header = result.result()
        assertEquals("PENDING", header.getString("status"))
        assertEquals("order-1", header.getJsonArray("items").getJsonObject(0).getString("order_item_id"))
        assertEquals("PACKAGE", header.getJsonArray("items").getJsonObject(0).getString("unit"))
    }

    @Test
    fun `createFromMedicalOrder rejects invalid inventory before writing pharmacy records`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot())
        every { inventoryOutboundPort.validatePackageOutbound(mockConn, any()) } returns
            Future.failedFuture(ConflictException("insufficient stock"))

        val error = failureOf(service.createFromMedicalOrder(validCreateBody()))

        assertTrue(error is ConflictException)
        verify(exactly = 1) { inventoryOutboundPort.validatePackageOutbound(mockConn, any()) }
        verify(exactly = 0) { connPrepared.execute(any<Tuple>()) }
    }

    // ========================================================================
    //  重复接方
    // ========================================================================

    @Test
    fun `createFromMedicalOrder rejects duplicate non-cancelled dispense`() {
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot())
        // 第一次 execute 是 rejectDuplicate 查询，返回 1 行 → 冲突
        every { connPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(rowsOf(rowOf(mapOf("id" to "dispense-1"))))
        val error = failureOf(service.createFromMedicalOrder(validCreateBody()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("already has"))
        // 冲突后不得插入药房单/明细（每次 execute 都应为查询，不存在 insert）
        verify(exactly = 1) { connPrepared.execute(any<Tuple>()) }
    }

    // ========================================================================
    //  状态机
    // ========================================================================

    private fun dispenseHeaderRow(status: String, warehouse: String? = "西药库"): Row =
        rowOf(
            mapOf(
                "id" to "dispense-1",
                "dispense_no" to "DS-1",
                "patient_id" to "pat-1",
                "encounter_id" to "enc-1",
                "dispense_type" to "ELDERLY_ROUTINE",
                "status" to status,
                "pharmacist" to null,
                "reviewer" to null,
                "warehouse" to warehouse,
                "metadata" to null,
                "created_at" to "2026-08-06T08:00:00+08:00",
                "dispensed_at" to null,
            ),
        )

    @Test
    fun `review rejects non-PENDING status`() {
        every { connPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
        val error = failureOf(service.review("dispense-1", JsonObject().put("operator", "张药师")))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot review"))
    }

    @Test
    fun `review requires operator`() {
        val error = failureOf(service.review("dispense-1", JsonObject()))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("operator"))
        // 缺 operator 时不得开启事务
        verify(exactly = 0) { mockPool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `start rejects non-REVIEWED status`() {
        every { connPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(rowsOf(dispenseHeaderRow("PENDING")))
        val error = failureOf(service.start("dispense-1", JsonObject().put("operator", "张药师")))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot start"))
    }

    @Test
    fun `confirm rejects non-DISPENSING status`() {
        every { connPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(rowsOf(dispenseHeaderRow("REVIEWED")))
        val error = failureOf(service.confirm("dispense-1", JsonObject()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot confirm"))
    }

    @Test
    fun `cancel rejects DISPENSED status`() {
        every { connPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSED")))
        val error = failureOf(service.cancel("dispense-1", JsonObject()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot cancel"))
    }

    @Test
    fun `cancel rejects dispense with stock operation`() {
        every { connPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
        // 第二次 execute 是 hasStockOperation 查询，返回 1 行 → 已有库存操作
        every { connPrepared.execute(any<Tuple>()) } answers {
            val call = callCount(connPrepared)
            if (call == 1) Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
            else Future.succeededFuture(rowsOf(rowOf(mapOf("id" to "stock-op-1"))))
        }
        val error = failureOf(service.cancel("dispense-1", JsonObject()))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("use return flow"))
    }

    // ========================================================================
    //  confirm 同连接端口调用、幂等与回滚
    // ========================================================================

    private fun dispenseItemRow(): Row =
        rowOf(
            mapOf(
                "id" to "item-1",
                "dispense_id" to "dispense-1",
                "order_item_id" to "order-1",
                "material_id" to "mat-1",
                "lot_id" to "lot-1",
                "dispensed_quantity" to BigDecimal.ONE,
                "unit" to "PACKAGE",
            ),
        )

    @Test
    fun `confirm calls ports on transaction connection and writes result`() {
        // 顺序：lockDispense → loadDispenseItem → 写回 item/header → 同连接 get(header/items)
        every { connPrepared.execute(any<Tuple>()) } answers {
            when (callCount(connPrepared)) {
                1 -> Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
                2 -> Future.succeededFuture(rowsOf(dispenseItemRow()))
                5 -> Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSED")))
                else -> Future.succeededFuture(emptyRowSet())
            }
        }
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot())
        every { inventoryOutboundPort.confirmPackageOutbound(mockConn, any()) } returns
            Future.succeededFuture(
                PackageOutboundResult(
                    stockOperationDetailId = "stock-op-1",
                    lotId = "lot-1",
                    unitCost = BigDecimal.valueOf(3.5),
                ),
            )
        val result = service.confirm("dispense-1", JsonObject())
        verify(exactly = 1) { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") }
        verify(exactly = 1) {
            inventoryOutboundPort.confirmPackageOutbound(
                mockConn,
                withArg {
                    assertEquals("西药库", it.warehouse)
                    assertEquals("mat-1", it.materialId)
                    assertEquals("lot-1", it.lotId)
                    // quantity 来自 BigDecimal.valueOf(1.0)（scale 1），须用 compareTo 比较
                    assertEquals(0, BigDecimal.ONE.compareTo(it.quantity))
                },
            )
        }
        assertEquals("DISPENSED", result.result().getString("status"))
        verify(exactly = 0) { poolPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `confirm retry on DISPENSED does not deduct stock again`() {
        // lockDispense 读到已是 DISPENSED → 直接返回已有结果
        every { connPrepared.execute(any<Tuple>()) } answers {
            if (callCount(connPrepared) <= 2) Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSED")))
            else Future.succeededFuture(emptyRowSet())
        }

        val result = service.confirm("dispense-1", JsonObject())
        verify(exactly = 0) { inventoryOutboundPort.confirmPackageOutbound(any(), any()) }
        verify(exactly = 0) { medicalOrderReader.lockMedicationOrder(any(), any()) }
        assertEquals("DISPENSED", result.result().getString("status"))
        verify(exactly = 0) { poolPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `confirm rejects no longer active medical order before inventory outbound`() {
        every { connPrepared.execute(any<Tuple>()) } answers {
            if (callCount(connPrepared) == 1) Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
            else Future.succeededFuture(rowsOf(dispenseItemRow()))
        }
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot(orderStatus = "DISCONTINUED"))

        val error = failureOf(service.confirm("dispense-1", JsonObject()))

        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("not active"))
        verify(exactly = 0) { inventoryOutboundPort.confirmPackageOutbound(any(), any()) }
    }

    @Test
    fun `confirm propagates outbound failure for transaction rollback`() {
        every { connPrepared.execute(any<Tuple>()) } answers {
            if (callCount(connPrepared) == 1) Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
            else Future.succeededFuture(rowsOf(dispenseItemRow()))
        }
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot())
        every { inventoryOutboundPort.confirmPackageOutbound(mockConn, any()) } returns
            Future.failedFuture(ConflictException("insufficient stock"))

        val error = failureOf(service.confirm("dispense-1", JsonObject()))
        assertTrue(error is ConflictException)
        // 出库失败后不得执行回写（writeConfirmResult 的两个 update）
        verify(exactly = 2) { connPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `confirm propagates lock failure before any port call`() {
        // 顺序：lockDispense(header DISPENSING) → loadDispenseItem（必须返回 item 行，
        // 否则读 header 会因 order_item_id=null 抛 ConflictException，掩盖锁失败）
        every { connPrepared.execute(any<Tuple>()) } answers {
            if (callCount(connPrepared) == 1) Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
            else Future.succeededFuture(rowsOf(dispenseItemRow()))
        }
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.failedFuture(NotFoundException("order not found"))
        val error = failureOf(service.confirm("dispense-1", JsonObject()))
        assertTrue(error is NotFoundException)
        verify(exactly = 0) { inventoryOutboundPort.confirmPackageOutbound(any(), any()) }
    }

    // ========================================================================
    //  兼容入口
    // ========================================================================

    @Test
    fun `legacy create blocks ELDERLY_ROUTINE without touching pool`() {
        val body =
            JsonObject()
                .put("dispense_type", "ELDERLY_ROUTINE")
                .put("dispense_no", "DS-X")
                .put("patient_id", "pat-1")
                .put("items", io.vertx.core.json.JsonArray().add(JsonObject()))
        val error = failureOf(service.create(body))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("from-medical-order"))
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
        verify(exactly = 0) { connPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `legacy create validates dispense_no for other types`() {
        val body = JsonObject().put("dispense_type", "OUTPATIENT")
        val error = failureOf(service.create(body))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("dispense_no"))
        verify(exactly = 0) { mockPool.preparedQuery(any()) }
    }

    @Test
    fun `updateStatus forwards DISPENSED target to confirm stock transaction`() {
        // 目标 DISPENSED 必须转发 confirm 的完整库存事务；若保留旧的无库存直接置终态，
        // 出库端口不会被调用。
        every { connPrepared.execute(any<Tuple>()) } answers {
            when (callCount(connPrepared)) {
                1 -> Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSING")))
                2 -> Future.succeededFuture(rowsOf(dispenseItemRow()))
                5 -> Future.succeededFuture(rowsOf(dispenseHeaderRow("DISPENSED")))
                else -> Future.succeededFuture(emptyRowSet())
            }
        }
        every { medicalOrderReader.lockMedicationOrder(mockConn, "order-1") } returns
            Future.succeededFuture(snapshot())
        every { inventoryOutboundPort.confirmPackageOutbound(mockConn, any()) } returns
            Future.succeededFuture(
                PackageOutboundResult("stock-op-1", "lot-1", BigDecimal.valueOf(3.5)),
            )
        val result = service.updateStatus("dispense-1", JsonObject().put("status", "DISPENSED"))
        verify(exactly = 1) { inventoryOutboundPort.confirmPackageOutbound(mockConn, any()) }
        assertEquals("DISPENSED", result.result().getString("status"))
        verify(exactly = 0) { poolPrepared.execute(any<Tuple>()) }
    }

    @Test
    fun `updateStatus rejects invalid target`() {
        val error = failureOf(service.updateStatus("dispense-1", JsonObject().put("status", "SHIPPED")))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("invalid status target"))
    }

    // ========================================================================
    //  待接方医嘱列表（只读；端口传 Pool，附加接方状态）
    // ========================================================================

    @Test
    fun `listMedicationOrders attaches dispense link from pharmacy tables`() {
        every {
            medicalOrderReader.listMedicationOrders(mockPool, null, null, 50, 0)
        } returns Future.succeededFuture(
            JsonObject()
                .put(
                    "records",
                    io.vertx.core.json.JsonArray()
                        .add(JsonObject().put("order_id", "order-1").put("drug_name", "降压药")),
                )
                .put("meta", JsonObject().put("total", 1)),
        )
        every { poolPrepared.execute(any<Tuple>()) } returns
            Future.succeededFuture(
                rowsOf(
                    rowOf(
                        mapOf(
                            "order_item_id" to "order-1",
                            "dispense_id" to "dispense-1",
                            "dispense_status" to "PENDING",
                        ),
                    ),
                ),
            )
        val result = service.listMedicationOrders(null, null, 50, 0).result()
        val record = result.getJsonArray("records").getJsonObject(0)
        assertEquals("dispense-1", record.getString("dispense_id"))
        assertEquals("PENDING", record.getString("dispense_status"))
        assertEquals(1L, result.getJsonObject("meta").getLong("total"))
    }

    @Test
    fun `listMedicationOrders keeps records when no linked dispense`() {
        every {
            medicalOrderReader.listMedicationOrders(mockPool, "enc-1", null, 50, 0)
        } returns Future.succeededFuture(
            JsonObject()
                .put("records", io.vertx.core.json.JsonArray().add(JsonObject().put("order_id", "order-2")))
                .put("meta", JsonObject().put("total", 1)),
        )
        every { poolPrepared.execute(any<Tuple>()) } returns Future.succeededFuture(emptyRowSet())
        val record = service.listMedicationOrders("enc-1", null, 50, 0).result()
            .getJsonArray("records").getJsonObject(0)
        assertNull(record.getString("dispense_id"))
        assertNull(record.getString("dispense_status"))
    }

    // ========================================================================
    //  私有辅助：mockk 调用计数（按对象，简单可靠）
    // ========================================================================

    private val connCalls = java.util.concurrent.atomic.AtomicInteger()
    private val poolCalls = java.util.concurrent.atomic.AtomicInteger()

    private fun callCount(target: Any): Int =
        when (target) {
            connPrepared -> connCalls.incrementAndGet()
            poolPrepared -> poolCalls.incrementAndGet()
            else -> 0
        }
}
