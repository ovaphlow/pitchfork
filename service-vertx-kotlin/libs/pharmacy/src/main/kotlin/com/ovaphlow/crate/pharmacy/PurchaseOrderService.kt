package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
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
) {

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
                        val storedFingerprint = existing.getValue("fingerprint")?.toString()
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
                            val storedFingerprint = existing.getValue("fingerprint")?.toString()
                            if (storedFingerprint != fingerprint) {
                                Future.failedFuture(
                                    ConflictException("idempotency key already used with a different request"),
                                )
                            } else {
                                loadOrderDetail(pool, existing.getValue("id")?.toString() ?: "")
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
                conn.preparedQuery(INSERT_ORDER_SQL).execute(
                    Tuple.of(
                        orderId, orderNo, body.getString("warehouse"), body.getString("supplier_name"),
                        "DRAFT", userId, idempotencyKey, fingerprint, now, now,
                    )
                )
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
                                conn.preparedQuery(DELETE_ORDER_ITEMS_SQL).execute(Tuple.of(id))
                            }
                            .compose { _: RowSet<Row> ->
                                insertOrderItems(conn, id, body.getJsonArray("items"), OffsetDateTime.now())
                            }
                            .compose { _: Void? ->
                                conn.preparedQuery(UPDATE_DRAFT_SQL).execute(
                                    Tuple.of(body.getString("warehouse"), body.getString("supplier_name"), OffsetDateTime.now(), id)
                                )
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
            return conn.preparedQuery(INSERT_ORDER_ITEM_SQL).execute(
                Tuple.of(
                    itemId, orderId, item.getString("material_id"),
                    toBigDecimal(item.getValue("ordered_quantity")), BigDecimal.ZERO,
                )
            )
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
        return conn.preparedQuery(APPROVE_SQL)
            .execute(Tuple.of("APPROVED", userId, now, now, orderId))
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
        return conn.preparedQuery(CANCEL_SQL)
            .execute(Tuple.of("CANCELLED", userId, now, reason, now, orderId))
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
                                    conn.preparedQuery(CLOSE_SQL).execute(
                                        Tuple.of("CLOSED", userId, now, body.getString("reason"), now, orderIdOf(header))
                                    )
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
                        val storedFingerprint = existing.getValue("fingerprint")?.toString()
                        if (storedFingerprint != fingerprint) {
                            Future.failedFuture(
                                ConflictException("idempotency key already used with a different request"),
                            )
                        } else {
                            loadReceiptDetail(conn, existing.getValue("id")?.toString() ?: "")
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
                            val storedFingerprint = existing.getValue("fingerprint")?.toString()
                            if (storedFingerprint != fingerprint) {
                                Future.failedFuture(
                                    ConflictException("idempotency key already used with a different request"),
                                )
                            } else {
                                loadReceiptDetail(pool, existing.getValue("id")?.toString() ?: "")
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
                conn.preparedQuery(INSERT_RECEIPT_SQL).execute(
                    Tuple.of(
                        receiptId, receiptNo, orderId, header.getString("warehouse"),
                        header.getString("supplier_name"), userId, now, result.stockOperationId,
                        idempotencyKey, fingerprint, now,
                    )
                )
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
                        conn.preparedQuery(UPDATE_STATUS_SQL).execute(
                            Tuple.of(status, OffsetDateTime.now(), orderId)
                        )
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
            return conn.preparedQuery(INSERT_RECEIPT_ITEM_SQL).execute(
                Tuple.of(
                    entry.receiptItemId, receiptId, itemId, materialId, entry.lotId,
                    quantity, unitCost, unitCost.multiply(quantity), entry.stockOperationDetailId,
                )
            )
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
            return conn.preparedQuery(ACCUMULATE_RECEIVED_SQL).execute(Tuple.of(add, itemId, orderId))
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        warehouse?.let { where.add("warehouse = ${bind(it)}") }
        supplierName?.let { where.add("supplier_name = ${bind(it)}") }
        status?.let { where.add("status = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM pharmacy.pharmacy_purchase_orders$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $LIST_COLUMNS FROM pharmacy.pharmacy_purchase_orders$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql)
            .execute(Tuple.from(countArgs))
            .compose { countRows: RowSet<Row> ->
                val total = countRows.iterator().next().getValue("total")?.toString()?.toLong() ?: 0L
                pool.preparedQuery(dataSql)
                    .execute(Tuple.from(dataArgs))
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
        val placeholders = orderIds.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "SELECT purchase_order_id, id, material_id, ordered_quantity, received_quantity " +
                "FROM pharmacy.pharmacy_purchase_order_items " +
                "WHERE purchase_order_id IN ($placeholders) ORDER BY id"
        return client.preparedQuery(sql)
            .execute(Tuple.from(orderIds))
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
                            .put("ordered_quantity", decimalApi(ordered))
                            .put("received_quantity", decimalApi(received))
                            .put("remaining_quantity", decimalApi(ordered.subtract(received))),
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

    private fun loadOrderDetail(client: SqlClient, id: String): Future<JsonObject> {
        return client.preparedQuery("$ORDER_HEADER_SELECT WHERE id = \$1")
            .execute(Tuple.of(id))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("purchase order not found: $id"))
                } else {
                    val header = orderHeaderToJson(rows.iterator().next())
                    client.preparedQuery("$ORDER_ITEM_SELECT WHERE purchase_order_id = \$1 ORDER BY id")
                        .execute(Tuple.of(id))
                        .compose { itemRows: RowSet<Row> ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(orderItemToJson(row))
                            header.put("items", items)
                            client.preparedQuery("$RECEIPT_SELECT WHERE purchase_order_id = \$1 ORDER BY received_at")
                                .execute(Tuple.of(id))
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
    }

    private fun loadReceiptDetail(client: SqlClient, id: String): Future<JsonObject> {
        return client.preparedQuery("$RECEIPT_SELECT WHERE id = \$1")
            .execute(Tuple.of(id))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("purchase receipt not found: $id"))
                } else {
                    val header = receiptToJson(rows.iterator().next())
                    client.preparedQuery("$RECEIPT_ITEM_SELECT WHERE receipt_id = \$1 ORDER BY id")
                        .execute(Tuple.of(id))
                        .map { itemRows: RowSet<Row> ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(receiptItemToJson(row))
                            header.put("items", items)
                        }
                }
            }
    }

    private fun findOrderByIdempotencyKey(client: SqlClient, key: String): Future<Row?> =
        client.preparedQuery(
            "SELECT id AS id, request_fingerprint AS fingerprint " +
                "FROM pharmacy.pharmacy_purchase_orders WHERE idempotency_key = \$1"
        )
            .execute(Tuple.of(key))
            .map { rows: RowSet<Row> -> if (rows.size() > 0) rows.iterator().next() else null }

    private fun findReceiptByIdempotencyKey(client: SqlClient, key: String): Future<Row?> =
        client.preparedQuery(
            "SELECT id AS id, request_fingerprint AS fingerprint " +
                "FROM pharmacy.pharmacy_purchase_receipts WHERE idempotency_key = \$1"
        )
            .execute(Tuple.of(key))
            .map { rows: RowSet<Row> -> if (rows.size() > 0) rows.iterator().next() else null }

    private fun lockHeader(client: SqlClient, id: String): Future<Row> =
        client.preparedQuery("$ORDER_HEADER_SELECT WHERE id = \$1 FOR UPDATE")
            .execute(Tuple.of(id))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("purchase order not found: $id"))
                } else {
                    Future.succeededFuture(rows.iterator().next())
                }
            }

    private fun lockOrderItems(client: SqlClient, orderId: String): Future<List<Row>> {
        return client.preparedQuery("$ORDER_ITEM_SELECT WHERE purchase_order_id = \$1 ORDER BY id FOR UPDATE")
            .execute(Tuple.of(orderId))
            .map { rows: RowSet<Row> -> rows.map { it } }
    }

    // ========================================================================
    //  序列化
    // ========================================================================

    companion object {
        private const val ORDER_HEADER_COLUMNS =
            "id, purchase_order_no, warehouse, supplier_name, status, requester_id, approved_by, " +
                "approved_at, cancelled_by, cancelled_at, cancel_reason, closed_by, closed_at, " +
                "close_reason, created_at, updated_at"

        private const val ORDER_ITEM_COLUMNS =
            "id, purchase_order_id, material_id, ordered_quantity, received_quantity"

        private const val RECEIPT_COLUMNS =
            "id, receipt_no, purchase_order_id, warehouse, supplier_name, received_by, received_at, " +
                "stock_operation_id, created_at"

        private const val RECEIPT_ITEM_COLUMNS =
            "id, receipt_id, purchase_order_item_id, material_id, lot_id, received_quantity, " +
                "unit_cost, total_cost, stock_operation_detail_id"

        private const val LIST_COLUMNS =
            "id, purchase_order_no, warehouse, supplier_name, status, requester_id, created_at, " +
                "approved_at, cancelled_at, closed_at, updated_at"

        private const val ORDER_HEADER_SELECT =
            "SELECT $ORDER_HEADER_COLUMNS FROM pharmacy.pharmacy_purchase_orders"

        private const val ORDER_ITEM_SELECT =
            "SELECT $ORDER_ITEM_COLUMNS FROM pharmacy.pharmacy_purchase_order_items"

        private const val RECEIPT_SELECT =
            "SELECT $RECEIPT_COLUMNS FROM pharmacy.pharmacy_purchase_receipts"

        private const val RECEIPT_ITEM_SELECT =
            "SELECT $RECEIPT_ITEM_COLUMNS FROM pharmacy.pharmacy_purchase_receipt_items"

        private const val INSERT_ORDER_SQL =
            "INSERT INTO pharmacy.pharmacy_purchase_orders " +
                "(id, purchase_order_no, warehouse, supplier_name, status, requester_id, " +
                "idempotency_key, request_fingerprint, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10)"

        private const val INSERT_ORDER_ITEM_SQL =
            "INSERT INTO pharmacy.pharmacy_purchase_order_items " +
                "(id, purchase_order_id, material_id, ordered_quantity, received_quantity) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5)"

        private const val DELETE_ORDER_ITEMS_SQL =
            "DELETE FROM pharmacy.pharmacy_purchase_order_items WHERE purchase_order_id = \$1"

        private const val UPDATE_DRAFT_SQL =
            "UPDATE pharmacy.pharmacy_purchase_orders " +
                "SET warehouse = \$1, supplier_name = \$2, updated_at = \$3 WHERE id = \$4"

        private const val APPROVE_SQL =
            "UPDATE pharmacy.pharmacy_purchase_orders " +
                "SET status = \$1, approved_by = \$2, approved_at = \$3, updated_at = \$4 WHERE id = \$5"

        private const val CANCEL_SQL =
            "UPDATE pharmacy.pharmacy_purchase_orders " +
                "SET status = \$1, cancelled_by = \$2, cancelled_at = \$3, cancel_reason = \$4, " +
                "updated_at = \$5 WHERE id = \$6"

        private const val CLOSE_SQL =
            "UPDATE pharmacy.pharmacy_purchase_orders " +
                "SET status = \$1, closed_by = \$2, closed_at = \$3, close_reason = \$4, " +
                "updated_at = \$5 WHERE id = \$6"

        private const val UPDATE_STATUS_SQL =
            "UPDATE pharmacy.pharmacy_purchase_orders SET status = \$1, updated_at = \$2 WHERE id = \$3"

        private const val INSERT_RECEIPT_SQL =
            "INSERT INTO pharmacy.pharmacy_purchase_receipts " +
                "(id, receipt_no, purchase_order_id, warehouse, supplier_name, received_by, " +
                "received_at, stock_operation_id, idempotency_key, request_fingerprint, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

        private const val INSERT_RECEIPT_ITEM_SQL =
            "INSERT INTO pharmacy.pharmacy_purchase_receipt_items " +
                "(id, receipt_id, purchase_order_item_id, material_id, lot_id, received_quantity, " +
                "unit_cost, total_cost, stock_operation_detail_id) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val ACCUMULATE_RECEIVED_SQL =
            "UPDATE pharmacy.pharmacy_purchase_order_items " +
                "SET received_quantity = received_quantity + \$1 WHERE id = \$2 AND purchase_order_id = \$3"

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
                .put("ordered_quantity", decimalApi(ordered))
                .put("received_quantity", decimalApi(received))
                .put("remaining_quantity", decimalApi(ordered.subtract(received)))
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
