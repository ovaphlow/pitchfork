package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

/**
 * RequisitionService 013 单元测试：状态机、幂等、白名单、错误映射与端口连接复用。
 * mock Pool/SqlConnection/PreparedQuery，按执行次序返回 RowSet；不依赖数据库。
 */
class RequisitionServiceTest {
    private lateinit var pool: Pool
    private lateinit var connection: SqlConnection
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private lateinit var inventoryPort: InventoryRequisitionTransferPort
    private lateinit var service: RequisitionService
    private val executeCalls = AtomicInteger()
    private val user = "user-1"

    @BeforeEach
    fun setUp() {
        pool = mockk()
        connection = mockk()
        prepared = mockk()
        inventoryPort = mockk()
        executeCalls.set(0)
        every { connection.preparedQuery(any()) } returns prepared
        every { prepared.execute(any<Tuple>()) } answers {
            executeCalls.incrementAndGet()
            Future.succeededFuture(emptyRows())
        }
        every { pool.withTransaction<RequisitionService.CreateResult>(match<Function<SqlConnection, Future<RequisitionService.CreateResult>>> { true }) } answers {
            firstArg<Function<SqlConnection, Future<RequisitionService.CreateResult>>>().apply(connection)
        }
        every { pool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) } answers {
            firstArg<Function<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        service = RequisitionService(pool, inventoryPort)
    }

    // ── Row mocks ──────────────────────────────────────────────────────────

    private fun row(values: Map<String, Any?>): Row = mockk {
        every { getValue(any<String>()) } answers { values[firstArg<String>()] }
        every { getString(any<String>()) } answers { values[firstArg<String>()]?.toString() }
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
            Future.succeededFuture(rowsets.getOrNull(executeCalls.get() - 1) ?: emptyRows())
        }
    }

    private fun headerRow(
        status: String = "DRAFT",
        id: String = "req-1",
        warehouse: String = "西药库",
        destinationWarehouse: String = "一护理站",
        requisitionNo: String = "PH-REQ-1",
        approvedBy: String? = null,
        dispensedBy: String? = null,
        cancelReason: String? = null,
    ): Row = row(
        mapOf(
            "id" to id,
            "requisition_no" to requisitionNo,
            "warehouse" to warehouse,
            "destination_warehouse" to destinationWarehouse,
            "department" to "一护理站",
            "status" to status,
            "requester" to "护士甲",
            "requester_id" to user,
            "approved_by" to approvedBy,
            "dispensed_by" to dispensedBy,
            "cancel_reason" to cancelReason,
            "created_at" to "2026-08-06T08:00:00+08:00",
        ),
    )

    private fun itemRow(
        id: String,
        materialId: String,
        requested: BigDecimal,
        approved: BigDecimal? = null,
        lotId: String? = null,
    ): Row = row(
        mapOf(
            "id" to id,
            "requisition_id" to "req-1",
            "material_id" to materialId,
            "requested_quantity" to requested,
            "approved_quantity" to approved,
            "dispensed_quantity" to null,
            "unit" to "PACKAGE",
            "lot_id" to lotId,
            "stock_operation_detail_id" to null,
            "outbound_stock_operation_detail_id" to null,
            "inbound_stock_operation_detail_id" to null,
        ),
    )

    /** findByIdempotencyKey 行：按索引读取 id、fingerprint */
    private fun idempotencyRow(id: String, fingerprint: String?): Row = mockk {
        every { getValue(0) } returns id
        every { getValue(1) } returns fingerprint
    }

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    private fun createBody(): JsonObject = JsonObject()
        .put("warehouse", "西药库")
        .put("destination_warehouse", "一护理站")
        .put("department", "一护理站")
        .put(
            "items",
            JsonArray().add(
                JsonObject().put("material_id", "m-1").put("requested_quantity", 5),
            ),
        )

    private fun approveBody(vararg items: JsonObject): JsonObject = JsonObject().put("items", JsonArray().apply { items.forEach { add(it) } })

    private fun approveItem(id: String, quantity: Int, lotId: String? = null): JsonObject =
        JsonObject().put("id", id).put("approved_quantity", quantity).put("lot_id", lotId)

    /** 复刻服务端 requestFingerprint：固定字段顺序 canonical JSON + SHA-256 */
    private fun fingerprintOf(body: JsonObject): String {
        val canonical = JsonObject()
            .put("warehouse", body.getString("warehouse"))
            .put("destination_warehouse", body.getString("destination_warehouse"))
            .put("department", body.getString("department"))
            .put(
                "items",
                JsonArray().apply {
                    for (i in 0 until body.getJsonArray("items").size()) {
                        val item = body.getJsonArray("items").getJsonObject(i)
                        add(
                            JsonObject()
                                .put("material_id", item.getString("material_id"))
                                .put("requested_quantity", BigDecimal(item.getValue("requested_quantity").toString()).stripTrailingZeros().toPlainString()),
                        )
                    }
                },
            )
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ========================================================================
    //  创建
    // ========================================================================

    @Test
    fun `create requires Idempotency-Key before transaction`() {
        val error = failureOf(service.create(createBody(), null, user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("Idempotency-Key"))
        verify(exactly = 0) { pool.withTransaction<RequisitionService.CreateResult>(match<Function<SqlConnection, Future<RequisitionService.CreateResult>>> { true }) }
    }

    @Test
    fun `create rejects unknown fields before transaction`() {
        val body = createBody().put("status", "DISPENSED")
        val error = failureOf(service.create(body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("unknown fields"))
        verify(exactly = 0) { pool.withTransaction<RequisitionService.CreateResult>(match<Function<SqlConnection, Future<RequisitionService.CreateResult>>> { true }) }
    }

    @Test
    fun `create rejects same source and destination warehouse`() {
        val body = createBody().put("destination_warehouse", "西药库")
        val error = failureOf(service.create(body, "key-1", user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("differ"))
    }

    @Test
    fun `create rejects duplicate material in items`() {
        val body = JsonObject()
            .put("warehouse", "西药库")
            .put("destination_warehouse", "一护理站")
            .put("department", "一护理站")
            .put(
                "items",
                JsonArray()
                    .add(JsonObject().put("material_id", "m-1").put("requested_quantity", 1))
                    .add(JsonObject().put("material_id", "m-1").put("requested_quantity", 2)),
            )
        val error = failureOf(service.create(body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("duplicate"))
    }

    @Test
    fun `create inserts draft header and items and validates materials on transaction connection`() {
        sequence(
            emptyRows(),                                   // 1 findByIdempotencyKey
            emptyRows(),                                   // 2 insertHeader
            emptyRows(),                                   // 3 insertItems
            rowsOf(headerRow()),                           // 4 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))), // 5 loadDetail items
        )
        every { inventoryPort.validateRequisitionMaterials(connection, listOf("m-1")) } returns Future.succeededFuture(null)

        val result = service.create(createBody(), "key-1", user).result()

        assertEquals(false, result.replayed)
        assertEquals("DRAFT", result.requisition.getString("status"))
        assertTrue(result.requisition.getString("requisition_no")!!.startsWith("PH-REQ-"))
        assertEquals(user, result.requisition.getString("requester_id"))
        verify(exactly = 1) { inventoryPort.validateRequisitionMaterials(connection, listOf("m-1")) }
    }

    @Test
    fun `create replay with same fingerprint returns original requisition`() {
        val body = createBody()
        sequence(
            rowsOf(idempotencyRow("req-1", fingerprintOf(body))), // 1 findByIdempotencyKey 命中
            rowsOf(headerRow()),                                   // 2 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))),       // 3 loadDetail items
        )
        val result = service.create(body, "key-1", user).result()
        assertEquals(true, result.replayed)
        assertEquals("req-1", result.id)
        // 重放不触碰端口，也不写库
        verify(exactly = 0) { inventoryPort.validateRequisitionMaterials(any(), any()) }
        assertEquals(3, executeCalls.get())
    }

    @Test
    fun `create replay with different fingerprint conflicts`() {
        sequence(rowsOf(idempotencyRow("req-1", "different-fingerprint")))
        val conflict = failureOf(service.create(createBody(), "key-1", user))
        assertTrue(conflict is ConflictException)
        assertTrue(conflict.message!!.contains("idempotency key already used"))
    }

    // ========================================================================
    //  审批
    // ========================================================================

    @Test
    fun `approve rejects unknown fields before transaction`() {
        val error = failureOf(service.approve("req-1", JsonObject().put("status", "APPROVED"), user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `approve reserves stock and marks header approved on transaction connection`() {
        sequence(
            rowsOf(headerRow()),                                         // 1 lockHeader
            rowsOf(                                                        // 2 lockItems
                itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5)),
                itemRow("item-2", "m-2", BigDecimal(5), BigDecimal.ZERO),
            ),
            emptyRows(),                                                 // 3 updateApprovedItems item-1
            emptyRows(),                                                 // 4 updateApprovedItems item-2
            emptyRows(),                                                 // 5 updateHeader
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),   // 6 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5))), // 7 loadDetail items
        )
        every { inventoryPort.reservePackageStock(connection, any()) } returns Future.succeededFuture(null)

        val result = service.approve(
            "req-1",
            approveBody(approveItem("item-1", 5), approveItem("item-2", 0)),
            user,
        ).result()

        assertEquals("APPROVED", result.getString("status"))
        assertEquals(user, result.getString("approved_by"))
        verify(exactly = 1) {
            inventoryPort.reservePackageStock(connection, withArg { command ->
                assertEquals("西药库", command.warehouse)
                assertEquals(1, command.items.size)
                assertEquals("m-1", command.items[0].materialId)
                assertEquals(0, BigDecimal(5).compareTo(command.items[0].quantity))
            })
        }
    }

    @Test
    fun `approve rejects unknown item id with 404 semantics`() {
        sequence(
            rowsOf(headerRow()),
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))),
        )
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-9", 5)), user))
        assertTrue(error is NotFoundException)
        assertTrue(error.message!!.contains("item-9"))
        verify(exactly = 0) { inventoryPort.reservePackageStock(any(), any()) }
    }

    @Test
    fun `approve requires covering every item`() {
        sequence(
            rowsOf(headerRow()),
            rowsOf(
                itemRow("item-1", "m-1", BigDecimal(5)),
                itemRow("item-2", "m-2", BigDecimal(5)),
            ),
        )
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-1", 5)), user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("cover every"))
    }

    @Test
    fun `approve rejects dispensed requisition`() {
        sequence(rowsOf(headerRow(status = "DISPENSED")))
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-1", 5)), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot approve"))
    }

    @Test
    fun `approve rejects all zero approval set`() {
        sequence(
            rowsOf(headerRow()),
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))),
        )
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-1", 0)), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("no positive approved quantity"))
    }

    @Test
    fun `approve is idempotent when same user and same approval set`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),          // 1 lockHeader
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5), "lot-1")), // 2 lockItems
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),          // 3 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5), "lot-1")), // 4 loadDetail items
        )
        val result = service.approve(
            "req-1",
            approveBody(approveItem("item-1", 5, "lot-1")),
            user,
        ).result()
        assertEquals("APPROVED", result.getString("status"))
        verify(exactly = 0) { inventoryPort.reservePackageStock(any(), any()) }
    }

    @Test
    fun `approve conflicts when approval set differs`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5), "lot-1")),
        )
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-1", 6, "lot-1")), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("different approval set"))
    }

    @Test
    fun `approve conflicts when approved by different user`() {
        sequence(rowsOf(headerRow(status = "APPROVED", approvedBy = "user-2")))
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-1", 5)), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("different user"))
    }

    // ========================================================================
    //  确认调拨
    // ========================================================================

    @Test
    fun `dispense transfers only approved items and writes both detail ids`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED")),                                       // 1 lockHeader
            rowsOf(                                                                        // 2 lockItems
                itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5), "lot-1"),
                itemRow("item-2", "m-2", BigDecimal(5), BigDecimal.ZERO),
            ),
            emptyRows(),                                                                   // 3 writeTransferDetails item-1
            emptyRows(),                                                                   // 4 updateHeader
            rowsOf(headerRow(status = "DISPENSED", dispensedBy = user)),                   // 5 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5), "lot-1")),       // 6 loadDetail items
        )
        every { inventoryPort.confirmReservedPackageTransfer(connection, any()) } returns Future.succeededFuture(
            RequisitionTransferResult(
                outboundOperationId = "op-out-1",
                inboundOperationId = "op-in-1",
                items = listOf(
                    RequisitionTransferItemResult("m-1", "lot-1", "detail-out-1", "detail-in-1", BigDecimal(3.0)),
                ),
            ),
        )

        val result = service.dispense("req-1", user).result()

        assertEquals("DISPENSED", result.getString("status"))
        assertEquals(user, result.getString("dispensed_by"))
        verify(exactly = 1) {
            inventoryPort.confirmReservedPackageTransfer(connection, withArg { command ->
                assertEquals("西药库", command.sourceWarehouse)
                assertEquals("一护理站", command.destinationWarehouse)
                assertEquals("PH-REQ-1", command.requisitionNo)
                assertEquals(user, command.dispensedBy)
                assertEquals(1, command.items.size)
                assertEquals("m-1", command.items[0].materialId)
            })
        }
        // 零批准项跳过回写：writeTransferDetails 只执行 1 次（item-1）
        assertEquals(6, executeCalls.get())
    }

    @Test
    fun `dispense is idempotent and does not call transfer port again`() {
        sequence(
            rowsOf(headerRow(status = "DISPENSED")),                       // 1 lockHeader
            rowsOf(headerRow(status = "DISPENSED")),                       // 2 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))),               // 3 loadDetail items
        )
        val result = service.dispense("req-1", user).result()
        assertEquals("DISPENSED", result.getString("status"))
        verify(exactly = 0) { inventoryPort.confirmReservedPackageTransfer(any(), any()) }
    }

    @Test
    fun `dispense rejects draft requisition`() {
        sequence(rowsOf(headerRow(status = "DRAFT")))
        val error = failureOf(service.dispense("req-1", user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot dispense"))
    }

    @Test
    fun `dispense rejects requisition without approved items`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED")),
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal.ZERO)),
        )
        val error = failureOf(service.dispense("req-1", user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("no approved items"))
        verify(exactly = 0) { inventoryPort.confirmReservedPackageTransfer(any(), any()) }
    }

    // ========================================================================
    //  取消
    // ========================================================================

    @Test
    fun `cancel requires reason before transaction`() {
        val error = failureOf(service.cancel("req-1", JsonObject(), user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("reason"))
        verify(exactly = 0) { pool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) }
    }

    @Test
    fun `cancel draft does not release reservation`() {
        sequence(
            rowsOf(headerRow()),                                        // 1 lockHeader
            emptyRows(),                                                // 2 updateHeader
            rowsOf(headerRow(status = "CANCELLED", cancelReason = "护理站取消")), // 3 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))),            // 4 loadDetail items
        )
        val result = service.cancel("req-1", JsonObject().put("reason", "护理站取消"), user).result()
        assertEquals("CANCELLED", result.getString("status"))
        assertEquals("护理站取消", result.getString("cancel_reason"))
        verify(exactly = 0) { inventoryPort.releasePackageReservation(any(), any()) }
    }

    @Test
    fun `cancel approved releases reservation on transaction connection`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED")),                                       // 1 lockHeader
            rowsOf(                                                                        // 2 lockItems
                itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5), "lot-1"),
                itemRow("item-2", "m-2", BigDecimal(5), BigDecimal.ZERO),
            ),
            emptyRows(),                                                                   // 3 updateHeader
            rowsOf(headerRow(status = "CANCELLED")),                                       // 4 loadDetail header
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5))),                               // 5 loadDetail items
        )
        every { inventoryPort.releasePackageReservation(connection, any()) } returns Future.succeededFuture(null)

        val result = service.cancel("req-1", JsonObject().put("reason", "药房撤回"), user).result()

        assertEquals("CANCELLED", result.getString("status"))
        verify(exactly = 1) {
            inventoryPort.releasePackageReservation(connection, withArg { command ->
                assertEquals("西药库", command.warehouse)
                assertEquals(1, command.items.size)
                assertEquals("m-1", command.items[0].materialId)
                assertEquals("lot-1", command.items[0].lotId)
            })
        }
    }

    @Test
    fun `cancel rejects dispensed requisition`() {
        sequence(rowsOf(headerRow(status = "DISPENSED")))
        val error = failureOf(service.cancel("req-1", JsonObject().put("reason", "x"), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot cancel"))
        verify(exactly = 0) { inventoryPort.releasePackageReservation(any(), any()) }
    }

    // ========================================================================
    //  端口失败向服务层传播（由 Main.kt 适配器映射为 Pharmacy 异常）
    // ========================================================================

    @Test
    fun `reserve failure fails the whole approve future`() {
        sequence(
            rowsOf(headerRow()),
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5))),
        )
        every { inventoryPort.reservePackageStock(connection, any()) } returns Future.failedFuture(
            ConflictException("insufficient stock: only 2 available, requested 5"),
        )
        val error = failureOf(service.approve("req-1", approveBody(approveItem("item-1", 5)), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("insufficient stock"))
    }

    @Test
    fun `transfer failure fails the whole dispense future`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED")),
            rowsOf(itemRow("item-1", "m-1", BigDecimal(5), BigDecimal(5))),
        )
        every { inventoryPort.confirmReservedPackageTransfer(connection, any()) } returns Future.failedFuture(
            ConflictException("insufficient reservation"),
        )
        val error = failureOf(service.dispense("req-1", user))
        assertTrue(error is ConflictException)
    }
}
