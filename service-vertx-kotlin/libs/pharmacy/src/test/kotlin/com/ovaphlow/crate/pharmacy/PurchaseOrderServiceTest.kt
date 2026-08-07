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
 * PurchaseOrderService 014 单元测试：状态机、幂等、白名单、余量校验、错误映射
 * 与采购入库端口连接复用。mock Pool/SqlConnection/PreparedQuery/端口，按执行
 * 次序返回 RowSet；不依赖数据库。
 */
class PurchaseOrderServiceTest {
    private lateinit var pool: Pool
    private lateinit var connection: SqlConnection
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private lateinit var inventoryPort: InventoryPurchaseReceiptPort
    private lateinit var service: PurchaseOrderService
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
        every { pool.withTransaction<PurchaseOrderService.CreateResult>(match<Function<SqlConnection, Future<PurchaseOrderService.CreateResult>>> { true }) } answers {
            firstArg<Function<SqlConnection, Future<PurchaseOrderService.CreateResult>>>().apply(connection)
        }
        every { pool.withTransaction<PurchaseOrderService.ReceiveResult>(match<Function<SqlConnection, Future<PurchaseOrderService.ReceiveResult>>> { true }) } answers {
            firstArg<Function<SqlConnection, Future<PurchaseOrderService.ReceiveResult>>>().apply(connection)
        }
        every { pool.withTransaction<JsonObject>(match<Function<SqlConnection, Future<JsonObject>>> { true }) } answers {
            firstArg<Function<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        service = PurchaseOrderService(pool, inventoryPort)
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
        id: String = "po-1",
        purchaseOrderNo: String = "PH-PO-1",
        warehouse: String = "药房西药库",
        supplierName: String = "华康医药配送",
        approvedBy: String? = null,
        cancelReason: String? = null,
        closeReason: String? = null,
    ): Row = row(
        mapOf(
            "id" to id,
            "purchase_order_no" to purchaseOrderNo,
            "warehouse" to warehouse,
            "supplier_name" to supplierName,
            "status" to status,
            "requester_id" to user,
            "approved_by" to approvedBy,
            "cancelled_by" to null,
            "cancelled_at" to null,
            "cancel_reason" to cancelReason,
            "closed_by" to null,
            "closed_at" to null,
            "close_reason" to closeReason,
            "created_at" to "2026-08-06T08:00:00+08:00",
            "updated_at" to "2026-08-06T08:00:00+08:00",
        ),
    )

    private fun orderItemRow(
        id: String = "poi-1",
        materialId: String = "m-1",
        ordered: BigDecimal = BigDecimal.TEN,
        received: BigDecimal = BigDecimal.ZERO,
    ): Row = row(
        mapOf(
            "id" to id,
            "purchase_order_id" to "po-1",
            "material_id" to materialId,
            "ordered_quantity" to ordered,
            "received_quantity" to received,
            "unit" to "PACKAGE",
        ),
    )

    private fun receiptRow(id: String = "pr-1", stockOperationId: String = "op-1"): Row = row(
        mapOf(
            "id" to id,
            "receipt_no" to "PH-REC-1",
            "purchase_order_id" to "po-1",
            "warehouse" to "药房西药库",
            "supplier_name" to "华康医药配送",
            "received_by" to user,
            "received_at" to "2026-08-07T10:00:00+08:00",
            "stock_operation_id" to stockOperationId,
            "created_at" to "2026-08-07T10:00:00+08:00",
        ),
    )

    private fun receiptItemRow(
        id: String = "ri-1",
        itemId: String = "poi-1",
        materialId: String = "m-1",
        lotId: String? = "lot-1",
        quantity: BigDecimal = BigDecimal.TEN,
        unitCost: BigDecimal = BigDecimal.valueOf(12.5),
    ): Row = row(
        mapOf(
            "id" to id,
            "receipt_id" to "pr-1",
            "purchase_order_item_id" to itemId,
            "material_id" to materialId,
            "lot_id" to lotId,
            "received_quantity" to quantity,
            "unit" to "PACKAGE",
            "unit_cost" to unitCost,
            "total_cost" to unitCost.multiply(quantity),
            "stock_operation_detail_id" to "detail-1",
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

    private fun createBody(vararg items: JsonObject): JsonObject = JsonObject()
        .put("warehouse", "药房西药库")
        .put("supplier_name", "华康医药配送")
        .put("items", JsonArray().apply { items.forEach { add(it) } })

    private fun orderItem(materialId: String, quantity: Int): JsonObject =
        JsonObject().put("material_id", materialId).put("ordered_quantity", quantity)

    private fun receiptBody(vararg lines: JsonObject): JsonObject =
        JsonObject().put("items", JsonArray().apply { lines.forEach { add(it) } })

    private fun receiptLine(
        itemId: String = "poi-1",
        quantity: Int = 10,
        batchNo: String? = "A240801",
        expiryDate: String? = "2028-03-31",
        unitCost: Number = 12.5,
    ): JsonObject = JsonObject()
        .put("purchase_order_item_id", itemId)
        .put("received_quantity", quantity)
        .put("batch_no", batchNo)
        .put("expiry_date", expiryDate)
        .put("unit_cost", unitCost)

    private fun stubPortSuccess(quantity: BigDecimal = BigDecimal.TEN) {
        every { inventoryPort.confirmPackagePurchaseReceipt(connection, any()) } returns Future.succeededFuture(
            PurchaseReceiptResult(
                stockOperationId = "op-1",
                items = listOf(
                    PurchaseReceiptItemResult(
                        receiptItemId = "ri-1",
                        materialId = "m-1",
                        batchNo = "A240801",
                        lotId = "lot-1",
                        stockOperationDetailId = "detail-1",
                        unitCost = BigDecimal.valueOf(12.5),
                        totalCost = BigDecimal.valueOf(12.5).multiply(quantity),
                    ),
                ),
            ),
        )
    }

    // ========================================================================
    //  创建
    // ========================================================================

    @Test
    fun `create requires Idempotency-Key before transaction`() {
        val error = failureOf(service.create(createBody(orderItem("m-1", 10)), null, user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("Idempotency-Key"))
        verify(exactly = 0) { pool.withTransaction<PurchaseOrderService.CreateResult>(match<Function<SqlConnection, Future<PurchaseOrderService.CreateResult>>> { true }) }
    }

    @Test
    fun `create rejects unknown fields before transaction`() {
        val body = createBody(orderItem("m-1", 10)).put("status", "APPROVED")
        val error = failureOf(service.create(body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("unknown fields"))
        verify(exactly = 0) { pool.withTransaction<PurchaseOrderService.CreateResult>(match<Function<SqlConnection, Future<PurchaseOrderService.CreateResult>>> { true }) }
    }

    @Test
    fun `create rejects duplicate material in items`() {
        val body = createBody(orderItem("m-1", 10), orderItem("m-1", 5))
        val error = failureOf(service.create(body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("duplicate"))
    }

    @Test
    fun `create rejects non-positive ordered quantity`() {
        val body = createBody(orderItem("m-1", 0))
        val error = failureOf(service.create(body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("positive"))
    }

    @Test
    fun `create inserts draft header and items and validates materials on transaction connection`() {
        sequence(
            emptyRows(),                                    // 1 findByIdempotencyKey
            emptyRows(),                                    // 2 insertHeader
            emptyRows(),                                    // 3 insertItems
            rowsOf(headerRow()),                            // 4 loadDetail header
            rowsOf(orderItemRow()),                         // 5 loadDetail items
            emptyRows(),                                    // 6 loadDetail receipts
        )
        every { inventoryPort.validatePurchaseMaterials(connection, listOf("m-1")) } returns Future.succeededFuture(null)

        val result = service.create(createBody(orderItem("m-1", 10)), "key-1", user).result()

        assertEquals(false, result.replayed)
        assertEquals("DRAFT", result.order.getString("status"))
        assertTrue(result.order.getString("purchase_order_no")!!.startsWith("PH-PO-"))
        assertEquals(user, result.order.getString("requester_id"))
        verify(exactly = 1) { inventoryPort.validatePurchaseMaterials(connection, listOf("m-1")) }
    }

    @Test
    fun `create replay with same fingerprint returns original order`() {
        val body = createBody(orderItem("m-1", 10))
        sequence(
            rowsOf(idempotencyRow("po-1", fingerprintOf(body))), // 1 findByIdempotencyKey 命中
            rowsOf(headerRow()),                                 // 2 loadDetail header
            rowsOf(orderItemRow()),                              // 3 loadDetail items
            emptyRows(),                                         // 4 loadDetail receipts
        )
        val result = service.create(body, "key-1", user).result()
        assertEquals(true, result.replayed)
        assertEquals("po-1", result.id)
        verify(exactly = 0) { inventoryPort.validatePurchaseMaterials(any(), any()) }
        assertEquals(4, executeCalls.get())
    }

    @Test
    fun `create replay with different fingerprint conflicts`() {
        sequence(rowsOf(idempotencyRow("po-1", "different-fingerprint")))
        val conflict = failureOf(service.create(createBody(orderItem("m-1", 10)), "key-1", user))
        assertTrue(conflict is ConflictException)
        assertTrue(conflict.message!!.contains("idempotency key already used"))
    }

    @Test
    fun `create propagates material validation failure`() {
        sequence(
            emptyRows(), // 1 findByIdempotencyKey
        )
        every { inventoryPort.validatePurchaseMaterials(connection, listOf("m-1")) } returns
            Future.failedFuture(ConflictException("materials not found or not ACTIVE: m-9"))
        val error = failureOf(service.create(createBody(orderItem("m-1", 10)), "key-1", user))
        assertTrue(error is ConflictException)
        assertEquals(1, executeCalls.get())
    }

    // ========================================================================
    //  草稿编辑
    // ========================================================================

    @Test
    fun `updateDraft rejects non-draft order`() {
        sequence(rowsOf(headerRow(status = "APPROVED", approvedBy = user)))
        val error = failureOf(service.updateDraft("po-1", createBody(orderItem("m-1", 10)), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("DRAFT"))
    }

    @Test
    fun `updateDraft replaces items and header on transaction connection`() {
        sequence(
            rowsOf(headerRow()),                            // 1 lockHeader
            emptyRows(),                                    // 2 deleteItems
            emptyRows(),                                    // 3 insertItems
            emptyRows(),                                    // 4 updateHeader
            rowsOf(headerRow()),                            // 5 loadDetail header
            rowsOf(orderItemRow()),                         // 6 loadDetail items
            emptyRows(),                                    // 7 loadDetail receipts
        )
        every { inventoryPort.validatePurchaseMaterials(connection, listOf("m-1")) } returns Future.succeededFuture(null)

        val result = service.updateDraft("po-1", createBody(orderItem("m-1", 10)), user).result()

        assertEquals("DRAFT", result.getString("status"))
        verify(exactly = 1) { inventoryPort.validatePurchaseMaterials(connection, listOf("m-1")) }
    }

    // ========================================================================
    //  审核
    // ========================================================================

    @Test
    fun `approve marks draft order approved with audit identity`() {
        sequence(
            rowsOf(headerRow()),                                    // 1 lockHeader
            emptyRows(),                                            // 2 updateHeader
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 3 loadDetail header
            rowsOf(orderItemRow()),                                 // 4 loadDetail items
            emptyRows(),                                            // 5 loadDetail receipts
        )
        val result = service.approve("po-1", user).result()
        assertEquals("APPROVED", result.getString("status"))
        assertEquals(user, result.getString("approved_by"))
    }

    @Test
    fun `approve replay by same user returns original order`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 1 lockHeader
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 2 loadDetail header
            rowsOf(orderItemRow()),                                    // 3 loadDetail items
            emptyRows(),                                               // 4 loadDetail receipts
        )
        val result = service.approve("po-1", user).result()
        assertEquals("APPROVED", result.getString("status"))
        // 幂等重放：lockHeader + loadOrderDetail 三次查询，共 4 次 execute，不写库
        assertEquals(4, executeCalls.get())
    }

    @Test
    fun `approve rejects already approved by different user`() {
        sequence(rowsOf(headerRow(status = "APPROVED", approvedBy = "other-user")))
        val error = failureOf(service.approve("po-1", user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("different user"))
    }

    @Test
    fun `approve rejects received order`() {
        sequence(rowsOf(headerRow(status = "RECEIVED")))
        val error = failureOf(service.approve("po-1", user))
        assertTrue(error is ConflictException)
    }

    // ========================================================================
    //  取消
    // ========================================================================

    @Test
    fun `cancel requires reason`() {
        val error = failureOf(service.cancel("po-1", JsonObject(), user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("reason"))
    }

    @Test
    fun `cancel draft order`() {
        sequence(
            rowsOf(headerRow()),                                   // 1 lockHeader
            emptyRows(),                                           // 2 updateHeader
            rowsOf(headerRow(status = "CANCELLED", cancelReason = "不再需要")), // 3 loadDetail header
            rowsOf(orderItemRow()),                                // 4 loadDetail items
            emptyRows(),                                           // 5 loadDetail receipts
        )
        val result = service.cancel("po-1", JsonObject().put("reason", "不再需要"), user).result()
        assertEquals("CANCELLED", result.getString("status"))
        assertEquals("不再需要", result.getString("cancel_reason"))
    }

    @Test
    fun `cancel approved order with zero receipts`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 1 lockHeader
            rowsOf(orderItemRow(received = BigDecimal.ZERO)),          // 2 lockOrderItems
            emptyRows(),                                               // 3 updateHeader
            rowsOf(headerRow(status = "CANCELLED", cancelReason = "供应商无法供货")), // 4 loadDetail header
            rowsOf(orderItemRow()),                                    // 5 loadDetail items
            emptyRows(),                                               // 6 loadDetail receipts
        )
        val result = service.cancel("po-1", JsonObject().put("reason", "供应商无法供货"), user).result()
        assertEquals("CANCELLED", result.getString("status"))
    }

    @Test
    fun `cancel approved order with receipts conflicts`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 1 lockHeader
            rowsOf(orderItemRow(received = BigDecimal.ONE)),           // 2 lockOrderItems
        )
        val error = failureOf(service.cancel("po-1", JsonObject().put("reason", "取消"), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("close the remaining quantity"))
    }

    @Test
    fun `cancel received order conflicts`() {
        sequence(rowsOf(headerRow(status = "RECEIVED")))
        val error = failureOf(service.cancel("po-1", JsonObject().put("reason", "取消"), user))
        assertTrue(error is ConflictException)
    }

    // ========================================================================
    //  关闭
    // ========================================================================

    @Test
    fun `close partially received order with remaining quantity`() {
        sequence(
            rowsOf(headerRow(status = "PARTIALLY_RECEIVED", approvedBy = user)), // 1 lockHeader
            rowsOf(orderItemRow(ordered = BigDecimal.TEN, received = BigDecimal(6))), // 2 lockOrderItems
            emptyRows(),                                                          // 3 updateHeader
            rowsOf(headerRow(status = "CLOSED", closeReason = "余量不再到货")),     // 4 loadDetail header
            rowsOf(orderItemRow(ordered = BigDecimal.TEN, received = BigDecimal(6))), // 5 loadDetail items
            emptyRows(),                                                          // 6 loadDetail receipts
        )
        val result = service.close("po-1", JsonObject().put("reason", "余量不再到货"), user).result()
        assertEquals("CLOSED", result.getString("status"))
        assertEquals("余量不再到货", result.getString("close_reason"))
    }

    @Test
    fun `close fully received order conflicts`() {
        sequence(
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 1 lockHeader
            rowsOf(orderItemRow(received = BigDecimal.TEN)),           // 2 lockOrderItems：无剩余
        )
        val error = failureOf(service.close("po-1", JsonObject().put("reason", "关闭"), user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("no remaining quantity"))
    }

    // ========================================================================
    //  收货
    // ========================================================================

    @Test
    fun `receive requires Idempotency-Key`() {
        val error = failureOf(service.receive("po-1", receiptBody(receiptLine()), null, user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("Idempotency-Key"))
    }

    @Test
    fun `receive rejects duplicate line for same order item and batch`() {
        val body = receiptBody(receiptLine(), receiptLine())
        val error = failureOf(service.receive("po-1", body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("duplicate"))
    }

    @Test
    fun `receive rejects unknown fields`() {
        val body = receiptBody(receiptLine()).put("supplier_name", "伪造供应商")
        val error = failureOf(service.receive("po-1", body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `receive rejects non-positive quantity`() {
        val body = receiptBody(receiptLine(quantity = 0))
        val error = failureOf(service.receive("po-1", body, "key-1", user))
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("positive"))
    }

    @Test
    fun `receive rejects non-draft order status`() {
        sequence(
            emptyRows(), // 1 findReceiptByIdempotencyKey 未命中（空集）
            rowsOf(headerRow(status = "DRAFT")),  // 2 lockHeader
        )
        val error = failureOf(service.receive("po-1", receiptBody(receiptLine()), "key-1", user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("cannot receive"))
        verify(exactly = 0) { inventoryPort.confirmPackagePurchaseReceipt(any(), any()) }
    }

    @Test
    fun `receive rejects order item not belonging to order`() {
        sequence(
            emptyRows(),                       // 1 findReceiptByIdempotencyKey 未命中（空集）
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),  // 2 lockHeader
            rowsOf(orderItemRow()),                                     // 3 lockOrderItems
        )
        val error = failureOf(
            service.receive("po-1", receiptBody(receiptLine(itemId = "poi-9")), "key-1", user),
        )
        assertTrue(error is NotFoundException)
        assertTrue(error.message!!.contains("poi-9"))
        verify(exactly = 0) { inventoryPort.confirmPackagePurchaseReceipt(any(), any()) }
    }

    @Test
    fun `receive rejects over-receipt beyond ordered quantity`() {
        sequence(
            emptyRows(),                       // 1 findReceiptByIdempotencyKey 未命中（空集）
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),  // 2 lockHeader
            rowsOf(orderItemRow()),                                     // 3 lockOrderItems：ordered 10
        )
        val future = service.receive("po-1", receiptBody(receiptLine(quantity = 11)), "key-1", user)
        future.onFailure { e -> println("DEBUG over-receipt error=${e::class.java.name} msg=${e.message}") }
        Thread.sleep(50)
        val error = failureOf(future)
        assertTrue(error is ConflictException, "expected ConflictException but was ${error::class.java.name}: ${error.message}")
        assertTrue(error.message!!.contains("over-receipt"))
        verify(exactly = 0) { inventoryPort.confirmPackagePurchaseReceipt(any(), any()) }
    }

    @Test
    fun `receive calls port on transaction connection and persists receipt and progress`() {
        stubPortSuccess()
        sequence(
            emptyRows(),                               // 1 findReceiptByIdempotencyKey 未命中（空集）
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),          // 2 lockHeader
            rowsOf(orderItemRow()),                                             // 3 lockOrderItems
            emptyRows(),                                                        // 4 insertReceipt
            emptyRows(),                                                        // 5 insertReceiptItems
            emptyRows(),                                                        // 6 accumulateReceived
            emptyRows(),                                                        // 7 updateHeader
            rowsOf(receiptRow()),                                               // 8 loadReceiptDetail header
            rowsOf(receiptItemRow()),                                           // 9 loadReceiptDetail items
            rowsOf(headerRow(status = "RECEIVED", approvedBy = user)),          // 10 loadOrderDetail header
            rowsOf(orderItemRow(received = BigDecimal.TEN)),                    // 11 loadOrderDetail items
            emptyRows(),                                                        // 12 loadOrderDetail receipts
        )
        val result = service.receive("po-1", receiptBody(receiptLine()), "key-1", user).result()

        assertEquals(false, result.replayed)
        assertEquals("PH-REC-1", result.payload.getString("receipt_no"))
        assertEquals("op-1", result.payload.getString("stock_operation_id"))
        assertEquals("RECEIVED", result.payload.getJsonObject("order").getString("status"))
        verify(exactly = 1) {
            inventoryPort.confirmPackagePurchaseReceipt(connection, withArg { command ->
                assertEquals("药房西药库", command.warehouse)
                assertEquals("华康医药配送", command.supplierName)
                assertEquals("po-1", command.purchaseOrderId)
                assertEquals(user, command.receivedBy)
                assertEquals(1, command.items.size)
                assertEquals("m-1", command.items[0].materialId)
                assertEquals("A240801", command.items[0].batchNo)
            })
        }
    }

    @Test
    fun `receive marks order partially received when quantity remains`() {
        stubPortSuccess(quantity = BigDecimal(6))
        sequence(
            emptyRows(),                      // 1 findReceiptByIdempotencyKey 未命中（空集）
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 2 lockHeader
            rowsOf(orderItemRow()),                                    // 3 lockOrderItems：ordered 10，收 6
            emptyRows(),                                               // 4 insertReceipt
            emptyRows(),                                               // 5 insertReceiptItems
            emptyRows(),                                               // 6 accumulateReceived
            emptyRows(),                                               // 7 updateHeader
            rowsOf(receiptRow()),                                      // 8 loadReceiptDetail header
            rowsOf(receiptItemRow(quantity = BigDecimal(6))),          // 9 loadReceiptDetail items
            rowsOf(headerRow(status = "PARTIALLY_RECEIVED", approvedBy = user)), // 10 loadOrderDetail header
            rowsOf(orderItemRow(received = BigDecimal(6))),            // 11 loadOrderDetail items
            emptyRows(),                                               // 12 loadOrderDetail receipts
        )
        val result = service.receive("po-1", receiptBody(receiptLine(quantity = 6)), "key-1", user).result()
        assertEquals("PARTIALLY_RECEIVED", result.payload.getJsonObject("order").getString("status"))
    }

    @Test
    fun `receive replay with same fingerprint returns original receipt`() {
        val body = receiptBody(receiptLine())
        val fingerprint = fingerprintOfReceipt("po-1", body)
        sequence(
            rowsOf(idempotencyRow("pr-1", fingerprint)),              // 1 findReceiptByIdempotencyKey 命中
            rowsOf(receiptRow()),                                     // 2 loadReceiptDetail header
            rowsOf(receiptItemRow()),                                 // 3 loadReceiptDetail items
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)), // 4 loadOrderDetail header
            rowsOf(orderItemRow(received = BigDecimal.TEN)),          // 5 loadOrderDetail items
            emptyRows(),                                              // 6 loadOrderDetail receipts
        )
        val result = service.receive("po-1", body, "key-1", user).result()

        assertEquals(true, result.replayed)
        assertEquals("PH-REC-1", result.payload.getString("receipt_no"))
        verify(exactly = 0) { inventoryPort.confirmPackagePurchaseReceipt(any(), any()) }
    }

    @Test
    fun `receive replay with different fingerprint conflicts`() {
        sequence(rowsOf(idempotencyRow("pr-1", "different-fingerprint")))
        val conflict = failureOf(service.receive("po-1", receiptBody(receiptLine()), "key-1", user))
        assertTrue(conflict is ConflictException)
        assertTrue(conflict.message!!.contains("idempotency key already used"))
    }

    @Test
    fun `receive propagates port failure without persisting receipt`() {
        every { inventoryPort.confirmPackagePurchaseReceipt(connection, any()) } returns
            Future.failedFuture(ConflictException("material m-1 is not ACTIVE"))
        sequence(
            emptyRows(),                       // 1 findReceiptByIdempotencyKey 未命中（空集）
            rowsOf(headerRow(status = "APPROVED", approvedBy = user)),  // 2 lockHeader
            rowsOf(orderItemRow()),                                     // 3 lockOrderItems
        )
        val error = failureOf(service.receive("po-1", receiptBody(receiptLine()), "key-1", user))
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("not ACTIVE"))
        // 端口失败后不再写收货凭证
        assertEquals(3, executeCalls.get())
    }

    // ========================================================================
    //  复刻服务端指纹
    // ========================================================================

    private fun fingerprintOf(body: JsonObject): String {
        val sortedItems = (0 until body.getJsonArray("items").size())
            .map { body.getJsonArray("items").getJsonObject(it) }
            .sortedWith(compareBy({ it.getString("material_id") }))
        val canonical = JsonObject()
            .put("warehouse", body.getString("warehouse"))
            .put("supplier_name", body.getString("supplier_name"))
            .put(
                "items",
                JsonArray().apply {
                    for (item in sortedItems) {
                        add(
                            JsonObject()
                                .put("material_id", item.getString("material_id"))
                                .put("ordered_quantity", BigDecimal(item.getValue("ordered_quantity").toString()).stripTrailingZeros().toPlainString()),
                        )
                    }
                },
            )
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun fingerprintOfReceipt(orderId: String, body: JsonObject): String {
        val sortedItems = (0 until body.getJsonArray("items").size())
            .map { body.getJsonArray("items").getJsonObject(it) }
            .sortedWith(
                compareBy<JsonObject>({ it.getString("purchase_order_item_id") })
                    .thenBy({ it.getString("batch_no") }),
            )
        val canonical = JsonObject()
            .put("purchase_order_id", orderId)
            .put(
                "items",
                JsonArray().apply {
                    for (item in sortedItems) {
                        add(
                            JsonObject()
                                .put("purchase_order_item_id", item.getString("purchase_order_item_id"))
                                .put("received_quantity", BigDecimal(item.getValue("received_quantity").toString()).stripTrailingZeros().toPlainString())
                                .put("batch_no", item.getString("batch_no"))
                                .put("production_date", item.getString("production_date"))
                                .put("expiry_date", item.getString("expiry_date"))
                                .put("manufacturer", item.getString("manufacturer"))
                                .put("unit_cost", BigDecimal(item.getValue("unit_cost").toString()).stripTrailingZeros().toPlainString()),
                        )
                    }
                },
            )
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
