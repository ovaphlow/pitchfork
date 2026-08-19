package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyPurchaseOrderItems
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyPurchaseOrders
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyPurchaseReceiptItems
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyPurchaseReceipts
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 014 药房采购订单服务：DRAFT → APPROVED → PARTIALLY_RECEIVED → RECEIVED/CLOSED/CANCELLED
 * 状态机、草稿编辑、审核、取消、关闭余量与供应商收货。
 *
 * `create`/`updateDraft`/`approve`/`cancel`/`close`/`receive` 均由 `pool.withTransaction`
 * 建立一次外层事务，采购入库通过 [InventoryPurchaseReceiptPort] 复用同一 SqlConnection，
 * 与收货凭证、订单进度同事务提交或回滚；端口失败使外层 Future 失败，不吞错后继续。
 *
 * 操作人一律来自认证 principal（userId），不接受客户端自由文本；创建与收货要求
 * `Idempotency-Key`，唯一约束冲突后重读比较指纹并返回原结果或 409。
 */
class PurchaseOrderService(
    private val pool: Pool,
    private val inventoryPort: InventoryPurchaseReceiptPort,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val o = PharmacyPurchaseOrders.PHARMACY_PURCHASE_ORDERS
    private val oi = PharmacyPurchaseOrderItems.PHARMACY_PURCHASE_ORDER_ITEMS
    private val pr = PharmacyPurchaseReceipts.PHARMACY_PURCHASE_RECEIPTS
    private val pri = PharmacyPurchaseReceiptItems.PHARMACY_PURCHASE_RECEIPT_ITEMS

    private val headerSelect = ctx.select(
        o.ID.`as`("id"),
        o.PURCHASE_ORDER_NO.`as`("purchase_order_no"),
        o.WAREHOUSE.`as`("warehouse"),
        o.SUPPLIER_NAME.`as`("supplier_name"),
        o.STATUS.`as`("status"),
        o.REQUESTER_ID.`as`("requester_id"),
        o.APPROVED_BY.`as`("approved_by"),
        o.APPROVED_AT.`as`("approved_at"),
        o.CANCELLED_BY.`as`("cancelled_by"),
        o.CANCELLED_AT.`as`("cancelled_at"),
        o.CANCEL_REASON.`as`("cancel_reason"),
        o.CLOSED_BY.`as`("closed_by"),
        o.CLOSED_AT.`as`("closed_at"),
        o.CLOSE_REASON.`as`("close_reason"),
        o.CREATED_AT.`as`("created_at"),
        o.UPDATED_AT.`as`("updated_at"),
    )

    private val orderItemSelect = ctx.select(
        oi.ID.`as`("id"),
        oi.PURCHASE_ORDER_ID.`as`("purchase_order_id"),
        oi.MATERIAL_ID.`as`("material_id"),
        oi.ORDERED_QUANTITY.`as`("ordered_quantity"),
        oi.RECEIVED_QUANTITY.`as`("received_quantity"),
    )

    private val receiptSelect = ctx.select(
        pr.ID.`as`("id"),
        pr.RECEIPT_NO.`as`("receipt_no"),
        pr.PURCHASE_ORDER_ID.`as`("purchase_order_id"),
        pr.WAREHOUSE.`as`("warehouse"),
        pr.SUPPLIER_NAME.`as`("supplier_name"),
        pr.RECEIVED_BY.`as`("received_by"),
        pr.RECEIVED_AT.`as`("received_at"),
        pr.STOCK_OPERATION_ID.`as`("stock_operation_id"),
        pr.CREATED_AT.`as`("created_at"),
    )

    private val receiptItemSelect = ctx.select(
        pri.ID.`as`("id"),
        pri.RECEIPT_ID.`as`("receipt_id"),
        pri.PURCHASE_ORDER_ITEM_ID.`as`("purchase_order_item_id"),
        pri.MATERIAL_ID.`as`("material_id"),
        pri.LOT_ID.`as`("lot_id"),
        pri.RECEIVED_QUANTITY.`as`("received_quantity"),
        pri.UNIT_COST.`as`("unit_cost"),
        pri.TOTAL_COST.`as`("total_cost"),
        pri.STOCK_OPERATION_DETAIL_ID.`as`("stock_operation_detail_id"),
    )

    /** 创建成功结果：`replayed` 为 true 表示 Idempotency-Key 命中并返回原订单（200）。 */
    data class CreateResult(val id: String, val replayed: Boolean, val order: JsonObject)

    /** 收货成功结果：`replayed` 为 true 表示幂等命中并返回原收货凭证（200）。 */
    data class ReceiveResult(val replayed: Boolean, val payload: JsonObject)

    // ========================================================================
    //  创建与草稿编辑
    // ========================================================================

    fun create(body: JsonObject, idempotencyKey: String?, userId: String): Future<CreateResult> {
        if (idempotencyKey.isNullOrBlank()) {
            return Future.failedFuture(IllegalArgumentException("Idempotency-Key header is required"))
        }
        validateOrderBody(body, idempotencyKey)?.let { return Future.failedFuture(it) }
        val key = idempotencyKey
        val fingerprint = orderFingerprint(body)

        return pool.withTransaction { conn: SqlConnection ->
            findOrderByIdempotencyKey(conn, key)
                .compose { existing: Row? ->
                    if (existing != null) {
                        val storedFingerprint = existing.getValue(1)?.toString()
                        if (storedFingerprint != fingerprint) {
                            Future.failedFuture(
                                ConflictException("idempotency key already used with a different request"),
                            )
                        } else {
                            loadOrderDetail(conn, existing.getValue(0)?.toString() ?: "")
                                .map { detail: JsonObject ->
                                    CreateResult(id = detail.getString("id") ?: "", replayed = true, order = detail)
                                }
                        }
                    } else {
                        doCreate(conn, body, key, userId, fingerprint)
                    }
                }
        }.recover { error: Throwable ->
            // 并发同键创建：唯一索引冲突（23505）不能以 500 泄漏。回读幂等键
            // 比对指纹后返回原订单或 409，而不是把 SQL 唯一冲突暴露给调用方。
            if (error is PgException && error.sqlState == "23505") {
                findOrderByIdempotencyKey(pool, key)
                    .compose { existing: Row? ->
                        if (existing == null) {
                            Future.failedFuture(
                                ConflictException("idempotency key raced during concurrent create"),
                            )
                        } else {
                            val storedFingerprint = existing.getValue(1)?.toString()
                            if (storedFingerprint != fingerprint) {
                                Future.failedFuture(
                                    ConflictException("idempotency key already used with a different request"),
                                )
                            } else {
                                loadOrderDetail(pool, existing.getValue(0)?.toString() ?: "")
                                    .map { detail: JsonObject ->
                                        CreateResult(id = detail.getString("id") ?: "", replayed = true, order = detail)
                                    }
                            }
                        }
                    }
            } else {
                Future.failedFuture(error)
            }
        }
    }

    private fun doCreate(
        conn: SqlConnection,
        body: JsonObject,
        idempotencyKey: String,
        userId: String,
        fingerprint: String,
    ): Future<CreateResult> {
        val orderId = Ulid.generate()
        val orderNo = "PH-PO-$orderId"
        val now = OffsetDateTime.now()
        val materialIds = body.getJsonArray("items").map { (it as JsonObject).getString("material_id") }

        return inventoryPort.validatePurchaseMaterials(conn, materialIds)
            .compose { _: Void? ->
                val insertHeader = ctx.insertInto(o)
                    .set(o.ID, orderId)
                    .set(o.PURCHASE_ORDER_NO, orderNo)
                    .set(o.WAREHOUSE, body.getString("warehouse"))
                    .set(o.SUPPLIER_NAME, body.getString("supplier_name"))
                    .set(o.STATUS, "DRAFT")
                    .set(o.REQUESTER_ID, userId)
                    .set(o.IDEMPOTENCY_KEY, idempotencyKey)
                    .set(o.REQUEST_FINGERPRINT, fingerprint)
                    .set(o.CREATED_AT, now)
                    .set(o.UPDATED_AT, now)
                conn.preparedQuery(DatabaseConfig.sql(insertHeader))
                    .execute(DatabaseConfig.tuple(insertHeader))
            }
            .compose { _: RowSet<Row> -> insertOrderItems(conn, orderId, body.getJsonArray("items"), now) }
            .compose { _: Void? -> loadOrderDetail(conn, orderId) }
            .map { detail: JsonObject ->
                CreateResult(id = orderId, replayed = false, order = detail)
            }
    }

    fun updateDraft(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        validateOrderBody(body, idempotencyKey = null)?.let { return Future.failedFuture(it) }
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    if (header.getString("status") != "DRAFT") {
                        Future.failedFuture(
                            ConflictException("only a DRAFT purchase order can be edited"),
                        )
                    } else {
                        val materialIds = body.getJsonArray("items").map { (it as JsonObject).getString("material_id") }
                        inventoryPort.validatePurchaseMaterials(conn, materialIds)
                            .compose { _: Void? ->
                                val deleteItems = ctx.deleteFrom(oi)
                                    .where(oi.PURCHASE_ORDER_ID.eq(id))
                                conn.preparedQuery(DatabaseConfig.sql(deleteItems))
                                    .execute(DatabaseConfig.tuple(deleteItems))
                            }
                            .compose { _: RowSet<Row> ->
                                insertOrderItems(conn, id, body.getJsonArray("items"), OffsetDateTime.now())
                            }
                            .compose { _: Void? ->
                                val now = OffsetDateTime.now()
                                val updateHeader = ctx.update(o)
                                    .set(o.WAREHOUSE, body.getString("warehouse"))
                                    .set(o.SUPPLIER_NAME, body.getString("supplier_name"))
                                    .set(o.UPDATED_AT, now)
                                    .where(o.ID.eq(id))
                                conn.preparedQuery(DatabaseConfig.sql(updateHeader))
                                    .execute(DatabaseConfig.tuple(updateHeader))
                            }
                            .compose { _: RowSet<Row> -> loadOrderDetail(conn, id) }
                    }
                }
        }
    }

    private fun insertOrderItems(conn: SqlConnection, orderId: String, items: JsonArray, now: OffsetDateTime): Future<Void?> {
        fun insertOne(index: Int): Future<Void?> {
            if (index >= items.size()) return Future.succeededFuture(null)
            val item = items.getJsonObject(index)
            val itemId = Ulid.generate()
            val insertItem = ctx.insertInto(oi)
                .set(oi.ID, itemId)
                .set(oi.PURCHASE_ORDER_ID, orderId)
                .set(oi.MATERIAL_ID, item.getString("material_id"))
                .set(oi.ORDERED_QUANTITY, toBigDecimal(item.getValue("ordered_quantity")))
                .set(oi.RECEIVED_QUANTITY, BigDecimal.ZERO)
            return conn.preparedQuery(DatabaseConfig.sql(insertItem))
                .execute(DatabaseConfig.tuple(insertItem))
                .compose { _: RowSet<Row> -> insertOne(index + 1) }
        }
        return insertOne(0)
    }

    // ========================================================================
    //  审核、取消与关闭
    // ========================================================================

    fun approve(id: String, userId: String): Future<JsonObject> {
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    when (header.getString("status")) {
                        "DRAFT" -> doApprove(conn, header, userId)
                        "APPROVED" -> {
                            if (header.getString("approved_by") == userId) loadOrderDetail(conn, id)
                            else Future.failedFuture(
                                ConflictException("purchase order is already APPROVED by a different user"),
                            )
                        }
                        else -> Future.failedFuture(
                            ConflictException("cannot approve a ${header.getString("status")} purchase order"),
                        )
                    }
                }
        }
    }

    private fun doApprove(conn: SqlConnection, header: Row, userId: String): Future<JsonObject> {
        val orderId = header.getString("id") ?: return Future.failedFuture(IllegalArgumentException("id required"))
        val now = OffsetDateTime.now()
        val updateHeader = ctx.update(o)
            .set(o.STATUS, "APPROVED")
            .set(o.APPROVED_BY, userId)
            .set(o.APPROVED_AT, now)
            .set(o.UPDATED_AT, now)
            .where(o.ID.eq(orderId))
        return conn.preparedQuery(DatabaseConfig.sql(updateHeader))
            .execute(DatabaseConfig.tuple(updateHeader))
            .compose { _: RowSet<Row> -> loadOrderDetail(conn, orderId) }
    }

    fun cancel(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        validateReasonBody(body)?.let { return Future.failedFuture(it) }
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    when (header.getString("status")) {
                        "CANCELLED" -> loadOrderDetail(conn, id)
                        "DRAFT" -> writeCancelled(conn, header, body.getString("reason"), userId)
                        "APPROVED" -> lockOrderItems(conn, id)
                            .compose { rows: List<Row> ->
                                if (rows.any { qtyOf(it.getValue("received_quantity"))?.compareTo(BigDecimal.ZERO) == 1 }) {
                                    Future.failedFuture(
                                        ConflictException("cannot cancel an APPROVED order with receipts; close the remaining quantity instead"),
                                    )
                                } else {
                                    writeCancelled(conn, header, body.getString("reason"), userId)
                                }
                            }
                        else -> Future.failedFuture(
                            ConflictException("cannot cancel a ${header.getString("status")} purchase order"),
                        )
                    }
                }
        }
    }

    private fun writeCancelled(conn: SqlConnection, header: Row, reason: String?, userId: String): Future<JsonObject> {
        val orderId = header.getString("id") ?: return Future.failedFuture(IllegalArgumentException("id required"))
        val now = OffsetDateTime.now()
        val update = ctx.update(o)
            .set(o.STATUS, "CANCELLED")
            .set(o.CANCELLED_BY, userId)
            .set(o.CANCELLED_AT, now)
            .set(o.CANCEL_REASON, reason)
            .set(o.UPDATED_AT, now)
            .where(o.ID.eq(orderId))
        return conn.preparedQuery(DatabaseConfig.sql(update))
            .execute(DatabaseConfig.tuple(update))
            .compose { _: RowSet<Row> -> loadOrderDetail(conn, orderId) }
    }

    fun close(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        validateReasonBody(body)?.let { return Future.failedFuture(it) }
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    when (header.getString("status")) {
                        "CLOSED" -> loadOrderDetail(conn, id)
                        "APPROVED", "PARTIALLY_RECEIVED" -> lockOrderItems(conn, id)
                            .compose { rows: List<Row> ->
                                val remaining = rows.sumOf { row ->
                                    val ordered = qtyOf(row.getValue("ordered_quantity")) ?: BigDecimal.ZERO
                                    val received = qtyOf(row.getValue("received_quantity")) ?: BigDecimal.ZERO
                                    ordered.subtract(received)
                                }
                                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                                    Future.failedFuture(
                                        ConflictException("no remaining quantity to close; the order is fully received"),
                                    )
                                } else {
                                    val now = OffsetDateTime.now()
                                    val update = ctx.update(o)
                                        .set(o.STATUS, "CLOSED")
                                        .set(o.CLOSED_BY, userId)
                                        .set(o.CLOSED_AT, now)
                                        .set(o.CLOSE_REASON, body.getString("reason"))
                                        .set(o.UPDATED_AT, now)
                                        .where(o.ID.eq(orderIdOf(header)))
                                    conn.preparedQuery(DatabaseConfig.sql(update))
                                        .execute(DatabaseConfig.tuple(update))
                                        .compose { _: RowSet<Row> -> loadOrderDetail(conn, orderIdOf(header)) }
                                }
                            }
                        else -> Future.failedFuture(
                            ConflictException("cannot close a ${header.getString("status")} purchase order"),
                        )
                    }
                }
        }
    }

    private fun orderIdOf(header: Row): String = header.getString("id") ?: ""

    // ========================================================================
    //  供应商收货
    // ========================================================================

    fun receive(id: String, body: JsonObject, idempotencyKey: String?, userId: String): Future<ReceiveResult> {
        validateReceiptBody(body, idempotencyKey)?.let { return Future.failedFuture(it) }
        val key = idempotencyKey!!
        val fingerprint = receiptFingerprint(id, body)

        return pool.withTransaction { conn: SqlConnection ->
            findReceiptByIdempotencyKey(conn, key)
                .compose { existing: Row? ->
                    if (existing != null) {
                        val storedFingerprint = existing.getValue(1)?.toString()
                        if (storedFingerprint != fingerprint) {
                            Future.failedFuture(
                                ConflictException("idempotency key already used with a different request"),
                            )
                        } else {
                            loadReceiptDetail(conn, existing.getValue(0)?.toString() ?: "")
                                .compose { payload: JsonObject ->
                                    loadOrderDetail(conn, id).map { order: JsonObject ->
                                        payload.put("order", order)
                                        ReceiveResult(replayed = true, payload = payload)
                                    }
                                }
                        }
                    } else {
                        doReceive(conn, id, body, key, userId, fingerprint)
                    }
                }
        }.recover { error: Throwable ->
            // 并发同键收货：唯一索引冲突（23505）不能以 500 泄漏。回读幂等键
            // 比对指纹后返回原收货凭证或 409，而不是把 SQL 唯一冲突暴露给调用方。
            if (error is PgException && error.sqlState == "23505") {
                findReceiptByIdempotencyKey(pool, key)
                    .compose { existing: Row? ->
                        if (existing == null) {
                            Future.failedFuture(
                                ConflictException("idempotency key raced during concurrent receive"),
                            )
                        } else {
                            val storedFingerprint = existing.getValue(1)?.toString()
                            if (storedFingerprint != fingerprint) {
                                Future.failedFuture(
                                    ConflictException("idempotency key already used with a different request"),
                                )
                            } else {
                                loadReceiptDetail(pool, existing.getValue(0)?.toString() ?: "")
                                    .compose { payload: JsonObject ->
                                        loadOrderDetail(pool, id).map { order: JsonObject ->
                                            payload.put("order", order)
                                            ReceiveResult(replayed = true, payload = payload)
                                        }
                                    }
                            }
                        }
                    }
            } else {
                Future.failedFuture(error)
            }
        }
    }

    private fun doReceive(
        conn: SqlConnection,
        orderId: String,
        body: JsonObject,
        idempotencyKey: String,
        userId: String,
        fingerprint: String,
    ): Future<ReceiveResult> {
        val now = OffsetDateTime.now()
        return lockHeader(conn, orderId)
            .compose { header: Row ->
                when (header.getString("status")) {
                    "APPROVED", "PARTIALLY_RECEIVED" -> lockOrderItems(conn, orderId)
                        .compose { rows: List<Row> ->
                            doReceiveLocked(conn, header, rows, body, idempotencyKey, userId, fingerprint, now)
                        }
                    else -> Future.failedFuture(
                        ConflictException("cannot receive a ${header.getString("status")} purchase order"),
                    )
                }
            }
    }

    private fun doReceiveLocked(
        conn: SqlConnection,
        header: Row,
        rows: List<Row>,
        body: JsonObject,
        idempotencyKey: String,
        userId: String,
        fingerprint: String,
        now: OffsetDateTime,
    ): Future<ReceiveResult> {
        val orderId = orderIdOf(header)
        val byItemId = rows.associateBy { it.getString("id") }

        // 每条到货行必须指向本订单项，且累计不超订购量
        val receiptItems = mutableListOf<PurchaseReceiptItemCommand>()
        val accumulation = mutableMapOf<String, BigDecimal>()
        for (i in 0 until body.getJsonArray("items").size()) {
            val line = body.getJsonArray("items").getJsonObject(i)
            val itemId = line.getString("purchase_order_item_id")
            val orderItem = byItemId[itemId]
                ?: return Future.failedFuture(NotFoundException("purchase order item not found: $itemId"))
            val ordered = qtyOf(orderItem.getValue("ordered_quantity")) ?: BigDecimal.ZERO
            val received = qtyOf(orderItem.getValue("received_quantity")) ?: BigDecimal.ZERO
            val qty = toBigDecimal(line.getValue("received_quantity"))
            val cumulative = (accumulation[itemId] ?: BigDecimal.ZERO).add(qty)
            if (received.add(cumulative).compareTo(ordered) > 0) {
                return Future.failedFuture(
                    ConflictException("over-receipt: item $itemId would exceed ordered quantity $ordered"),
                )
            }
            accumulation[itemId] = cumulative
            receiptItems.add(
                PurchaseReceiptItemCommand(
                    receiptItemId = Ulid.generate(),
                    materialId = orderItem.getString("material_id") ?: "",
                    batchNo = line.getString("batch_no"),
                    productionDate = line.getString("production_date")?.let { LocalDate.parse(it) },
                    expiryDate = line.getString("expiry_date")?.let { LocalDate.parse(it) },
                    manufacturer = line.getString("manufacturer"),
                    quantity = qty,
                    unitCost = toBigDecimal(line.getValue("unit_cost")),
                ),
            )
        }

        val receiptId = Ulid.generate()
        val receiptNo = "PH-REC-$receiptId"
        val command = PurchaseReceiptCommand(
            warehouse = header.getString("warehouse") ?: "",
            supplierName = header.getString("supplier_name") ?: "",
            purchaseOrderId = orderId,
            purchaseOrderNo = header.getString("purchase_order_no") ?: "",
            purchaseReceiptId = receiptId,
            receiptNo = receiptNo,
            receivedBy = userId,
            items = receiptItems,
        )

        return inventoryPort.confirmPurchaseReceipt(conn, command)
            .compose { result: PurchaseReceiptResult ->
                val insertReceipt = ctx.insertInto(pr)
                    .set(pr.ID, receiptId)
                    .set(pr.RECEIPT_NO, receiptNo)
                    .set(pr.PURCHASE_ORDER_ID, orderId)
                    .set(pr.WAREHOUSE, header.getString("warehouse"))
                    .set(pr.SUPPLIER_NAME, header.getString("supplier_name"))
                    .set(pr.RECEIVED_BY, userId)
                    .set(pr.RECEIVED_AT, now)
                    .set(pr.STOCK_OPERATION_ID, result.stockOperationId)
                    .set(pr.IDEMPOTENCY_KEY, idempotencyKey)
                    .set(pr.REQUEST_FINGERPRINT, fingerprint)
                    .set(pr.CREATED_AT, now)
                conn.preparedQuery(DatabaseConfig.sql(insertReceipt))
                    .execute(DatabaseConfig.tuple(insertReceipt))
                    .compose { _: RowSet<Row> ->
                        insertReceiptItems(conn, receiptId, result, body.getJsonArray("items"), byItemId)
                    }
                    .compose { _: Void? -> accumulateReceived(conn, orderId, accumulation) }
                    .compose { _: Void? ->
                        val fullyReceived = rows.all { row ->
                            val ordered = qtyOf(row.getValue("ordered_quantity")) ?: BigDecimal.ZERO
                            val received = qtyOf(row.getValue("received_quantity")) ?: BigDecimal.ZERO
                            val add = accumulation[row.getString("id")] ?: BigDecimal.ZERO
                            received.add(add).compareTo(ordered) >= 0
                        }
                        val status = if (fullyReceived) "RECEIVED" else "PARTIALLY_RECEIVED"
                        val updateHeader = ctx.update(o)
                            .set(o.STATUS, status)
                            .set(o.UPDATED_AT, OffsetDateTime.now())
                            .where(o.ID.eq(orderId))
                        conn.preparedQuery(DatabaseConfig.sql(updateHeader))
                            .execute(DatabaseConfig.tuple(updateHeader))
                    }
                    .compose { _: RowSet<Row> ->
                        loadReceiptDetail(conn, receiptId)
                            .compose { payload: JsonObject ->
                                loadOrderDetail(conn, orderId).map { order: JsonObject ->
                                    payload.put("order", order)
                                    ReceiveResult(replayed = false, payload = payload)
                                }
                            }
                    }
            }
    }

    private fun insertReceiptItems(
        conn: SqlConnection,
        receiptId: String,
        result: PurchaseReceiptResult,
        lines: JsonArray,
        byItemId: Map<String, Row>,
    ): Future<Void?> {
        // 端口按稳定键返回结果，请求行按客户端顺序到达；以 (material_id, batch_no)
        // 精确匹配（同一订单内 material 唯一），每条结果只用一次
        val unused = result.items.toMutableList()
        fun insertOne(index: Int): Future<Void?> {
            if (index >= lines.size()) return Future.succeededFuture(null)
            val line = lines.getJsonObject(index)
            val itemId = line.getString("purchase_order_item_id")
            val orderItem = byItemId[itemId] ?: return Future.failedFuture(
                ConflictException("receipt result missing order item $itemId"),
            )
            val materialId = orderItem.getString("material_id") ?: ""
            val batchNo = if (line.containsKey("batch_no")) line.getString("batch_no") else null
            val quantity = toBigDecimal(line.getValue("received_quantity"))
            val unitCost = toBigDecimal(line.getValue("unit_cost"))
            val idx = unused.indexOfFirst { it.materialId == materialId && it.batchNo == batchNo }
            if (idx < 0) {
                return Future.failedFuture(
                    ConflictException("receipt result missing material $materialId batch $batchNo"),
                )
            }
            val entry = unused.removeAt(idx)
            val insertDetail = ctx.insertInto(pri)
                .set(pri.ID, entry.receiptItemId)
                .set(pri.RECEIPT_ID, receiptId)
                .set(pri.PURCHASE_ORDER_ITEM_ID, itemId)
                .set(pri.MATERIAL_ID, materialId)
                .set(pri.LOT_ID, entry.lotId)
                .set(pri.RECEIVED_QUANTITY, quantity)
                .set(pri.UNIT_COST, unitCost)
                .set(pri.TOTAL_COST, unitCost.multiply(quantity))
                .set(pri.STOCK_OPERATION_DETAIL_ID, entry.stockOperationDetailId)
            return conn.preparedQuery(DatabaseConfig.sql(insertDetail))
                .execute(DatabaseConfig.tuple(insertDetail))
                .compose { _: RowSet<Row> -> insertOne(index + 1) }
        }
        return insertOne(0)
    }

    private fun accumulateReceived(
        conn: SqlConnection,
        orderId: String,
        accumulation: Map<String, BigDecimal>,
    ): Future<Void?> {
        fun updateAll(entries: List<Pair<String, BigDecimal>>, index: Int): Future<Void?> {
            if (index >= entries.size) return Future.succeededFuture(null)
            val (itemId, add) = entries[index]
            val update = ctx.update(oi)
                .set(oi.RECEIVED_QUANTITY, oi.RECEIVED_QUANTITY.add(add))
                .where(oi.ID.eq(itemId))
                .and(oi.PURCHASE_ORDER_ID.eq(orderId))
            return conn.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { _: RowSet<Row> -> updateAll(entries, index + 1) }
        }
        return updateAll(accumulation.entries.map { it.key to it.value }, 0)
    }

    // ========================================================================
    //  只读查询
    // ========================================================================

    fun list(
        warehouse: String? = null,
        supplierName: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        warehouse?.let { conditions.add(o.WAREHOUSE.eq(it)) }
        supplierName?.let { conditions.add(o.SUPPLIER_NAME.eq(it)) }
        status?.let { conditions.add(o.STATUS.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(o).where(conditions)
        val dataQuery = ctx.select(
            o.ID.`as`("id"),
            o.PURCHASE_ORDER_NO.`as`("purchase_order_no"),
            o.WAREHOUSE.`as`("warehouse"),
            o.SUPPLIER_NAME.`as`("supplier_name"),
            o.STATUS.`as`("status"),
            o.REQUESTER_ID.`as`("requester_id"),
            o.CREATED_AT.`as`("created_at"),
            o.APPROVED_AT.`as`("approved_at"),
            o.CANCELLED_AT.`as`("cancelled_at"),
            o.CLOSED_AT.`as`("closed_at"),
            o.UPDATED_AT.`as`("updated_at"),
        )
            .from(o)
            .where(conditions)
            .orderBy(o.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .compose { countRows: RowSet<Row> ->
                val total = countRows.iterator().next().getValue("total")?.toString()?.toLong() ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .compose { dataRows: RowSet<Row> ->
                        val orderIds = dataRows.map { it.getValue("id")?.toString() }.filterNotNull()
                        loadItemSummaries(pool, orderIds).map { summaries: Map<String, JsonArray> ->
                            val records = JsonArray()
                            for (row in dataRows) {
                                val id = row.getValue("id")?.toString()
                                records.add(
                                    JsonObject()
                                        .put("id", id)
                                        .put("purchase_order_no", row.getValue("purchase_order_no")?.toString())
                                        .put("warehouse", row.getValue("warehouse")?.toString())
                                        .put("supplier_name", row.getValue("supplier_name")?.toString())
                                        .put("status", row.getValue("status")?.toString())
                                        .put("requester_id", row.getValue("requester_id")?.toString())
                                        .put("created_at", row.getValue("created_at")?.toString())
                                        .put("approved_at", row.getValue("approved_at")?.toString())
                                        .put("cancelled_at", row.getValue("cancelled_at")?.toString())
                                        .put("closed_at", row.getValue("closed_at")?.toString())
                                        .put("updated_at", row.getValue("updated_at")?.toString())
                                        .put("items", summaries[id] ?: JsonArray()),
                                )
                            }
                            JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                        }
                    }
            }
    }

    private fun loadItemSummaries(client: SqlClient, orderIds: List<String>): Future<Map<String, JsonArray>> {
        if (orderIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val query = ctx.select(
            oi.PURCHASE_ORDER_ID.`as`("purchase_order_id"),
            oi.ID.`as`("id"),
            oi.MATERIAL_ID.`as`("material_id"),
            oi.ORDERED_QUANTITY.`as`("ordered_quantity"),
            oi.RECEIVED_QUANTITY.`as`("received_quantity"),
        )
            .from(oi)
            .where(oi.PURCHASE_ORDER_ID.`in`(orderIds))
            .orderBy(oi.ID)
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows: RowSet<Row> ->
                val byOrder = mutableMapOf<String, JsonArray>()
                for (row in rows) {
                    val orderId = row.getValue("purchase_order_id")?.toString() ?: continue
                    val ordered = qtyOf(row.getValue("ordered_quantity")) ?: BigDecimal.ZERO
                    val received = qtyOf(row.getValue("received_quantity")) ?: BigDecimal.ZERO
                    val array = byOrder.getOrPut(orderId) { JsonArray() }
                    array.add(
                        JsonObject()
                            .put("id", row.getValue("id")?.toString())
                            .put("material_id", row.getValue("material_id")?.toString())
                            .put("ordered_quantity", ordered.toPlainString())
                            .put("received_quantity", received.toPlainString())
                            .put("remaining_quantity", ordered.subtract(received).toPlainString()),
                    )
                }
                byOrder
            }
    }

    fun get(id: String): Future<JsonObject> = loadOrderDetail(pool, id)

    fun getReceipt(id: String): Future<JsonObject> = loadReceiptDetail(pool, id)

    // ========================================================================
    //  共享读取
    // ========================================================================

    private fun loadOrderDetail(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(DatabaseConfig.sql(headerSelect.where(o.ID.eq(id))))
            .execute(DatabaseConfig.tuple(headerSelect.where(o.ID.eq(id))))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("purchase order not found: $id"))
                } else {
                    val header = orderHeaderToJson(rows.iterator().next())
                    client.preparedQuery(DatabaseConfig.sql(orderItemSelect.where(oi.PURCHASE_ORDER_ID.eq(id)).orderBy(oi.ID)))
                        .execute(DatabaseConfig.tuple(orderItemSelect.where(oi.PURCHASE_ORDER_ID.eq(id)).orderBy(oi.ID)))
                        .compose { itemRows: RowSet<Row> ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(orderItemToJson(row))
                            header.put("items", items)
                            client.preparedQuery(
                                DatabaseConfig.sql(receiptSelect.where(pr.PURCHASE_ORDER_ID.eq(id)).orderBy(pr.RECEIVED_AT)),
                            )
                                .execute(
                                    DatabaseConfig.tuple(receiptSelect.where(pr.PURCHASE_ORDER_ID.eq(id)).orderBy(pr.RECEIVED_AT)),
                                )
                                .map { receiptRows: RowSet<Row> ->
                                    val receipts = JsonArray()
                                    for (row in receiptRows) {
                                        receipts.add(
                                            JsonObject()
                                                .put("id", row.getValue("id")?.toString())
                                                .put("receipt_no", row.getValue("receipt_no")?.toString())
                                                .put("received_by", row.getValue("received_by")?.toString())
                                                .put("received_at", row.getValue("received_at")?.toString())
                                                .put("stock_operation_id", row.getValue("stock_operation_id")?.toString())
                                                .put("created_at", row.getValue("created_at")?.toString()),
                                        )
                                    }
                                    header.put("receipts", receipts)
                                }
                        }
                }
            }

    private fun loadReceiptDetail(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(DatabaseConfig.sql(receiptSelect.where(pr.ID.eq(id))))
            .execute(DatabaseConfig.tuple(receiptSelect.where(pr.ID.eq(id))))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("purchase receipt not found: $id"))
                } else {
                    val header = receiptToJson(rows.iterator().next())
                    client.preparedQuery(DatabaseConfig.sql(receiptItemSelect.where(pri.RECEIPT_ID.eq(id)).orderBy(pri.ID)))
                        .execute(DatabaseConfig.tuple(receiptItemSelect.where(pri.RECEIPT_ID.eq(id)).orderBy(pri.ID)))
                        .map { itemRows: RowSet<Row> ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(receiptItemToJson(row))
                            header.put("items", items)
                        }
                }
            }

    private fun findOrderByIdempotencyKey(client: SqlClient, key: String): Future<Row?> =
        client.preparedQuery(
            DatabaseConfig.sql(
                ctx.select(o.ID.`as`("id"), o.REQUEST_FINGERPRINT.`as`("fingerprint"))
                    .from(o)
                    .where(o.IDEMPOTENCY_KEY.eq(key)),
            ),
        )
            .execute(
                DatabaseConfig.tuple(
                    ctx.select(o.ID.`as`("id"), o.REQUEST_FINGERPRINT.`as`("fingerprint"))
                        .from(o)
                        .where(o.IDEMPOTENCY_KEY.eq(key)),
                ),
            )
            .map { rows: RowSet<Row> -> if (rows.size() > 0) rows.iterator().next() else null }

    private fun findReceiptByIdempotencyKey(client: SqlClient, key: String): Future<Row?> =
        client.preparedQuery(
            DatabaseConfig.sql(
                ctx.select(pr.ID.`as`("id"), pr.REQUEST_FINGERPRINT.`as`("fingerprint"))
                    .from(pr)
                    .where(pr.IDEMPOTENCY_KEY.eq(key)),
            ),
        )
            .execute(
                DatabaseConfig.tuple(
                    ctx.select(pr.ID.`as`("id"), pr.REQUEST_FINGERPRINT.`as`("fingerprint"))
                        .from(pr)
                        .where(pr.IDEMPOTENCY_KEY.eq(key)),
                ),
            )
            .map { rows: RowSet<Row> -> if (rows.size() > 0) rows.iterator().next() else null }

    private fun lockHeader(client: SqlClient, id: String): Future<Row> =
        client.preparedQuery(DatabaseConfig.sql(headerSelect.where(o.ID.eq(id)).forUpdate()))
            .execute(DatabaseConfig.tuple(headerSelect.where(o.ID.eq(id)).forUpdate()))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("purchase order not found: $id"))
                } else {
                    Future.succeededFuture(rows.iterator().next())
                }
            }

    private fun lockOrderItems(client: SqlClient, orderId: String): Future<List<Row>> =
        client.preparedQuery(
            DatabaseConfig.sql(orderItemSelect.where(oi.PURCHASE_ORDER_ID.eq(orderId)).orderBy(oi.ID).forUpdate()),
        )
            .execute(
                DatabaseConfig.tuple(orderItemSelect.where(oi.PURCHASE_ORDER_ID.eq(orderId)).orderBy(oi.ID).forUpdate()),
            )
            .map { rows: RowSet<Row> -> rows.map { it } }

    // ========================================================================
    //  序列化
    // ========================================================================

    companion object {
        fun orderHeaderToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("purchase_order_no", row.getValue("purchase_order_no")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("supplier_name", row.getValue("supplier_name")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("requester_id", row.getValue("requester_id")?.toString())
                .put("approved_by", row.getValue("approved_by")?.toString())
                .put("approved_at", row.getValue("approved_at")?.toString())
                .put("cancelled_by", row.getValue("cancelled_by")?.toString())
                .put("cancelled_at", row.getValue("cancelled_at")?.toString())
                .put("cancel_reason", row.getValue("cancel_reason")?.toString())
                .put("closed_by", row.getValue("closed_by")?.toString())
                .put("closed_at", row.getValue("closed_at")?.toString())
                .put("close_reason", row.getValue("close_reason")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())

        fun orderItemToJson(row: Row): JsonObject {
            val ordered = qtyOf(row.getValue("ordered_quantity")) ?: BigDecimal.ZERO
            val received = qtyOf(row.getValue("received_quantity")) ?: BigDecimal.ZERO
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("purchase_order_id", row.getValue("purchase_order_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("ordered_quantity", ordered.toPlainString())
                .put("received_quantity", received.toPlainString())
                .put("remaining_quantity", ordered.subtract(received).toPlainString())
        }

        fun receiptToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("receipt_no", row.getValue("receipt_no")?.toString())
                .put("purchase_order_id", row.getValue("purchase_order_id")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("supplier_name", row.getValue("supplier_name")?.toString())
                .put("received_by", row.getValue("received_by")?.toString())
                .put("received_at", row.getValue("received_at")?.toString())
                .put("stock_operation_id", row.getValue("stock_operation_id")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())

        fun receiptItemToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("receipt_id", row.getValue("receipt_id")?.toString())
                .put("purchase_order_item_id", row.getValue("purchase_order_item_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("received_quantity", decimalApi(qtyOf(row.getValue("received_quantity"))))
                .put("unit_cost", decimalApi(qtyOf(row.getValue("unit_cost"))))
                .put("total_cost", decimalApi(qtyOf(row.getValue("total_cost"))))
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())

        fun qtyOf(value: Any?): BigDecimal? = when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            is String -> BigDecimal(value)
            else -> null
        }

        private fun toBigDecimal(value: Any?): BigDecimal = when (value) {
            is String -> value.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("quantity must be decimal text")
            else -> throw IllegalArgumentException("quantity must be decimal text")
        }
    }

    // ========================================================================
    //  校验
    // ========================================================================

    private fun validateOrderBody(body: JsonObject, idempotencyKey: String?): Exception? {
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            return IllegalArgumentException("Idempotency-Key header is required")
        }
        rejectUnknown(body, setOf("warehouse", "supplier_name", "items"))?.let { return it }
        if (body.getString("warehouse")?.trim().isNullOrEmpty()) {
            return IllegalArgumentException("warehouse is required")
        }
        if (body.getString("supplier_name")?.trim().isNullOrEmpty()) {
            return IllegalArgumentException("supplier_name is required")
        }
        val items = body.getJsonArray("items")
        if (items == null || items.isEmpty) return IllegalArgumentException("items is required and must not be empty")
        val materialIds = mutableSetOf<String>()
        for (i in 0 until items.size()) {
            val item = items.getJsonObject(i) ?: return IllegalArgumentException("items[$i] must be an object")
            rejectUnknown(item, setOf("material_id", "ordered_quantity"))?.let { return it }
            val materialId = item.getString("material_id")
            if (materialId.isNullOrBlank()) return IllegalArgumentException("items[$i].material_id is required")
            if (!materialIds.add(materialId)) {
                return IllegalArgumentException("duplicate material_id in request: $materialId")
            }
            val qty = try {
                toBigDecimal(item.getValue("ordered_quantity"))
            } catch (e: Exception) {
                return IllegalArgumentException("items[$i].ordered_quantity must be decimal text")
            }
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                return IllegalArgumentException("items[$i].ordered_quantity must be positive")
            }
        }
        return null
    }

    private fun validateReasonBody(body: JsonObject): Exception? {
        rejectUnknown(body, setOf("reason"))?.let { return it }
        if (body.getString("reason")?.trim().isNullOrEmpty()) return IllegalArgumentException("reason is required")
        return null
    }

    private fun validateReceiptBody(body: JsonObject, idempotencyKey: String?): Exception? {
        if (idempotencyKey.isNullOrBlank()) {
            return IllegalArgumentException("Idempotency-Key header is required")
        }
        rejectUnknown(body, setOf("items"))?.let { return it }
        val items = body.getJsonArray("items")
        if (items == null || items.isEmpty) return IllegalArgumentException("items is required and must not be empty")
        val seen = mutableSetOf<Pair<String, String?>>()
        for (i in 0 until items.size()) {
            val item = items.getJsonObject(i) ?: return IllegalArgumentException("items[$i] must be an object")
            rejectUnknown(
                item,
                setOf(
                    "purchase_order_item_id",
                    "received_quantity",
                    "batch_no",
                    "production_date",
                    "expiry_date",
                    "manufacturer",
                    "unit_cost",
                ),
            )?.let { return it }
            val itemId = item.getString("purchase_order_item_id")
            if (itemId.isNullOrBlank()) return IllegalArgumentException("items[$i].purchase_order_item_id is required")
            val batchNo = if (item.containsKey("batch_no")) item.getString("batch_no") else null
            if (!seen.add(itemId to batchNo)) {
                return IllegalArgumentException("duplicate receipt line for order item $itemId batch $batchNo")
            }
            val qty = try {
                toBigDecimal(item.getValue("received_quantity"))
            } catch (e: Exception) {
                return IllegalArgumentException("items[$i].received_quantity must be decimal text")
            }
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                return IllegalArgumentException("items[$i].received_quantity must be positive")
            }
            val cost = try {
                toBigDecimal(item.getValue("unit_cost"))
            } catch (e: Exception) {
                return IllegalArgumentException("items[$i].unit_cost must be decimal text")
            }
            if (cost.compareTo(BigDecimal.ZERO) < 0) {
                return IllegalArgumentException("items[$i].unit_cost must not be negative")
            }
            if (item.containsKey("production_date") && item.getValue("production_date") != null && !item.getString("production_date").isNullOrBlank()) {
                try {
                    LocalDate.parse(item.getString("production_date"))
                } catch (e: Exception) {
                    return IllegalArgumentException("items[$i].production_date must be a date")
                }
            }
            if (item.containsKey("expiry_date") && item.getValue("expiry_date") != null && !item.getString("expiry_date").isNullOrBlank()) {
                try {
                    LocalDate.parse(item.getString("expiry_date"))
                } catch (e: Exception) {
                    return IllegalArgumentException("items[$i].expiry_date must be a date")
                }
            }
        }
        return null
    }

    private fun rejectUnknown(body: JsonObject, allowed: Set<String>): IllegalArgumentException? {
        val unknown = body.fieldNames().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            return IllegalArgumentException("unknown fields: ${unknown.joinToString(", ")}")
        }
        return null
    }

    /** 创建订单的规范化摘要：仓库/供应商 + 按 material_id 稳定排序的订购明细 */
    private fun orderFingerprint(body: JsonObject): String {
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
                                .put("ordered_quantity", toBigDecimal(item.getValue("ordered_quantity")).stripTrailingZeros().toPlainString()),
                        )
                    }
                },
            )
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 收货的规范化摘要：订单 ID + 按订单项/批号稳定排序的到货行 */
    private fun receiptFingerprint(orderId: String, body: JsonObject): String {
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
                                .put("received_quantity", toBigDecimal(item.getValue("received_quantity")).stripTrailingZeros().toPlainString())
                                .put("batch_no", item.getString("batch_no"))
                                .put("production_date", item.getString("production_date"))
                                .put("expiry_date", item.getString("expiry_date"))
                                .put("manufacturer", item.getString("manufacturer"))
                                .put("unit_cost", toBigDecimal(item.getValue("unit_cost")).stripTrailingZeros().toPlainString()),
                        )
                    }
                },
            )
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
