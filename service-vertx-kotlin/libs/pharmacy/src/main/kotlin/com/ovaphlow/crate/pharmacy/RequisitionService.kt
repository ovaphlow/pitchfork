package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyRequisitionItems
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyRequisitions
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
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * 013 护理站申领服务：DRAFT → APPROVED → DISPENSED 状态机与取消。
 *
 * `create`/`approve`/`dispense`/`cancel` 均由 `pool.withTransaction` 建立一次外层
 * 事务，库存预留/释放/调拨通过 [InventoryRequisitionTransferPort] 复用同一
 * SqlConnection，与申领单状态变更同事务提交或回滚；任何端口失败都会使外层
 * Future 失败，不吞错后继续更新。
 *
 * 操作人一律来自认证 principal（userId），不接受客户端自由文本。
 */
class RequisitionService(
    private val pool: Pool,
    private val inventoryPort: InventoryRequisitionTransferPort,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val r = PharmacyRequisitions.PHARMACY_REQUISITIONS
    private val ri = PharmacyRequisitionItems.PHARMACY_REQUISITION_ITEMS

    private val headerSelect = ctx.select(
        r.ID.`as`("id"),
        r.REQUISITION_NO.`as`("requisition_no"),
        r.WAREHOUSE.`as`("warehouse"),
        r.DEPARTMENT.`as`("department"),
        r.STATUS.`as`("status"),
        r.REQUESTER.`as`("requester"),
        r.METADATA.`as`("metadata"),
        r.CREATED_AT.`as`("created_at"),
        r.DISPENSED_AT.`as`("dispensed_at"),
        r.DESTINATION_WAREHOUSE.`as`("destination_warehouse"),
        r.REQUESTER_ID.`as`("requester_id"),
        r.APPROVED_BY.`as`("approved_by"),
        r.APPROVED_AT.`as`("approved_at"),
        r.DISPENSED_BY.`as`("dispensed_by"),
        r.CANCELLED_BY.`as`("cancelled_by"),
        r.CANCELLED_AT.`as`("cancelled_at"),
        r.CANCEL_REASON.`as`("cancel_reason"),
        r.UPDATED_AT.`as`("updated_at"),
    )
        .from(r)

    private val itemSelect = ctx.select(
        ri.ID.`as`("id"),
        ri.REQUISITION_ID.`as`("requisition_id"),
        ri.MATERIAL_ID.`as`("material_id"),
        ri.REQUESTED_QUANTITY.`as`("requested_quantity"),
        ri.APPROVED_QUANTITY.`as`("approved_quantity"),
        ri.DISPENSED_QUANTITY.`as`("dispensed_quantity"),
        ri.STOCK_OPERATION_DETAIL_ID.`as`("stock_operation_detail_id"),
        ri.LOT_ID.`as`("lot_id"),
        ri.OUTBOUND_STOCK_OPERATION_DETAIL_ID.`as`("outbound_stock_operation_detail_id"),
        ri.INBOUND_STOCK_OPERATION_DETAIL_ID.`as`("inbound_stock_operation_detail_id"),
        ri.METADATA.`as`("metadata"),
    )
        .from(ri)

    /** 创建成功结果：`replayed` 为 true 表示 Idempotency-Key 命中并返回原单据（200）。 */
    data class CreateResult(val id: String, val replayed: Boolean, val requisition: JsonObject)

    // ========================================================================
    //  创建
    // ========================================================================

    fun create(body: JsonObject, idempotencyKey: String?, userId: String): Future<CreateResult> {
        validateCreateBody(body, idempotencyKey)?.let { return Future.failedFuture(it) }
        val key = idempotencyKey!!
        val now = OffsetDateTime.now()
        val fingerprint = requestFingerprint(body)

        return pool.withTransaction { conn: SqlConnection ->
            findByIdempotencyKey(conn, key)
                .compose { existing: Row? ->
                    if (existing != null) {
                        val storedFingerprint = existing.getValue(1)?.toString()
                        if (storedFingerprint != fingerprint) {
                            Future.failedFuture(
                                ConflictException("idempotency key already used with a different request"),
                            )
                        } else {
                            loadDetail(conn, existing.getValue(0)?.toString() ?: "")
                                .map { detail: JsonObject ->
                                    CreateResult(
                                        id = detail.getString("id") ?: "",
                                        replayed = true,
                                        requisition = detail,
                                    )
                                }
                        }
                    } else {
                        doCreate(conn, body, key, userId, fingerprint, now)
                    }
                }
        }.recover { error: Throwable ->
            // 并发同键创建：唯一索引冲突（23505）不能以 500 泄漏。回读幂等键
            // 比对指纹后返回原单据或 409，而不是把 SQL 唯一冲突暴露给调用方。
            if (error is PgException && error.sqlState == "23505") {
                findByIdempotencyKey(pool, key)
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
                                loadDetail(pool, existing.getValue(0)?.toString() ?: "")
                                    .map { detail: JsonObject ->
                                        CreateResult(
                                            id = detail.getString("id") ?: "",
                                            replayed = true,
                                            requisition = detail,
                                        )
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
        now: OffsetDateTime,
    ): Future<CreateResult> {
        val reqId = Ulid.generate()
        val requisitionNo = "PH-REQ-$reqId"
        val materialIds = body.getJsonArray("items").map { (it as JsonObject).getString("material_id") }

        return inventoryPort.validateRequisitionMaterials(conn, materialIds)
            .compose { _: Void? ->
                val insertHeader = ctx.insertInto(r)
                    .set(r.ID, reqId)
                    .set(r.REQUISITION_NO, requisitionNo)
                    .set(r.WAREHOUSE, body.getString("warehouse"))
                    .set(r.DEPARTMENT, body.getString("department"))
                    .set(r.STATUS, "DRAFT")
                    .set(r.CREATED_AT, now)
                    .set(r.DESTINATION_WAREHOUSE, body.getString("destination_warehouse"))
                    .set(r.REQUESTER_ID, userId)
                    .set(r.IDEMPOTENCY_KEY, idempotencyKey)
                    .set(r.REQUEST_FINGERPRINT, fingerprint)
                    .set(r.UPDATED_AT, now)
                conn.preparedQuery(DatabaseConfig.sql(insertHeader))
                    .execute(DatabaseConfig.tuple(insertHeader))
            }
            .compose { _: RowSet<Row> -> insertItems(conn, reqId, body.getJsonArray("items"), now) }
            .compose { _: Void? -> loadDetail(conn, reqId) }
            .map { detail: JsonObject ->
                CreateResult(id = reqId, replayed = false, requisition = detail)
            }
    }

    private fun insertItems(conn: SqlConnection, requisitionId: String, items: JsonArray, now: OffsetDateTime): Future<Void?> {
        fun insertOne(index: Int): Future<Void?> {
            if (index >= items.size()) return Future.succeededFuture(null)
            val item = items.getJsonObject(index)
            val itemId = Ulid.generate()
            val insertItem = ctx.insertInto(ri)
                .set(ri.ID, itemId)
                .set(ri.REQUISITION_ID, requisitionId)
                .set(ri.MATERIAL_ID, item.getString("material_id"))
                .set(ri.REQUESTED_QUANTITY, toBigDecimal(item.getValue("requested_quantity")))
            return conn.preparedQuery(DatabaseConfig.sql(insertItem))
                .execute(DatabaseConfig.tuple(insertItem))
                .compose { _: RowSet<Row> -> insertOne(index + 1) }
        }
        return insertOne(0)
    }

    // ========================================================================
    //  审批
    // ========================================================================

    fun approve(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        validateApproveBody(body)?.let { return Future.failedFuture(it) }
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    when (header.getString("status")) {
                        "APPROVED" -> idempotentApprove(conn, header, body, userId)
                        "DISPENSED", "CANCELLED" -> Future.failedFuture(
                            ConflictException("cannot approve a ${header.getString("status")} requisition"),
                        )
                        else -> doApprove(conn, header, body, userId)
                    }
                }
        }
    }

    private fun idempotentApprove(
        conn: SqlConnection,
        header: Row,
        body: JsonObject,
        userId: String,
    ): Future<JsonObject> {
        if (header.getString("approved_by") != userId) {
            return Future.failedFuture(
                ConflictException("requisition is already APPROVED by a different user"),
            )
        }
        val requested = requestedApproveMap(body)
        return lockItems(conn, header.getString("id") ?: "")
            .compose { rows: List<Row> ->
                // 请求明细集合必须与已审批明细完全一致：既不能缺失也不能多出。
                // 缺失/多出的项目属于“不同审批集合”，应返回 409 而非接受额外明细。
                val existingIds = rows.map { it.getString("id") }.toSet()
                val extra = requested.keys.filter { it !in existingIds }
                val missing = existingIds.filter { it !in requested.keys }
                if (extra.isNotEmpty() || missing.isNotEmpty()) {
                    return@compose Future.failedFuture(
                        ConflictException(
                            "requisition is already APPROVED with a different approval set" +
                                (if (extra.isNotEmpty()) " (extra items: ${extra.joinToString()})" else ""),
                        ),
                    )
                }
                val matches = rows.all { row ->
                    val entry = requested[row.getString("id")]
                    entry != null &&
                        entry.first == qtyOf(row.getValue("approved_quantity")) &&
                        entry.second == row.getString("lot_id")
                }
                if (matches) loadDetail(conn, header.getString("id") ?: "")
                else Future.failedFuture(
                    ConflictException("requisition is already APPROVED with a different approval set"),
                )
            }
    }

    private fun doApprove(conn: SqlConnection, header: Row, body: JsonObject, userId: String): Future<JsonObject> {
        val reqId = header.getString("id") ?: return Future.failedFuture(IllegalArgumentException("id required"))
        val approveMap = requestedApproveMap(body)
        return lockItems(conn, reqId)
            .compose { rows: List<Row> ->
                // 请求必须覆盖全部明细：无缺失、无多余、无重复（重复已在校验阶段拒绝）
                val existingIds = rows.map { it.getString("id") }.toSet()
                val unknown = approveMap.keys.filter { it !in existingIds }
                if (unknown.isNotEmpty()) {
                    return@compose Future.failedFuture(NotFoundException("requisition item not found: ${unknown.first()}"))
                }
                if (approveMap.size != existingIds.size) {
                    return@compose Future.failedFuture(IllegalArgumentException("approval must cover every requisition item"))
                }
                // 每项批准量不得超过申领量（计划 3.1：0 <= approved <= requested）
                val overApproved = rows.firstOrNull { row ->
                    val entry = approveMap[row.getString("id")]!!
                    val requested = qtyOf(row.getValue("requested_quantity")) ?: BigDecimal.ZERO
                    entry.first.compareTo(requested) > 0
                }
                if (overApproved != null) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException(
                            "approved_quantity must not exceed requested_quantity for item ${overApproved.getString("id")}",
                        ),
                    )
                }
                val approved = rows.mapNotNull { row ->
                    val entry = approveMap[row.getString("id")]!!
                    if (entry.first.compareTo(BigDecimal.ZERO) > 0) {
                        RequisitionReserveItem(
                            materialId = row.getString("material_id") ?: "",
                            lotId = entry.second,
                            quantity = entry.first,
                        )
                    } else {
                        null
                    }
                }
                if (approved.isEmpty()) {
                    return@compose Future.failedFuture(ConflictException("no positive approved quantity"))
                }
                inventoryPort.reserveStock(
                    conn,
                    RequisitionReserveCommand(warehouse = header.getString("warehouse") ?: "", items = approved),
                ).compose { _: Void? ->
                    updateApprovedItems(conn, reqId, rows, approveMap)
                        .compose { _: Void? ->
                            val now = OffsetDateTime.now()
                            val updateHeader = ctx.update(r)
                                .set(r.STATUS, "APPROVED")
                                .set(r.APPROVED_BY, userId)
                                .set(r.APPROVED_AT, now)
                                .set(r.UPDATED_AT, now)
                                .where(r.ID.eq(reqId))
                            conn.preparedQuery(DatabaseConfig.sql(updateHeader))
                                .execute(DatabaseConfig.tuple(updateHeader))
                        }
                        .compose { _: RowSet<Row> -> loadDetail(conn, reqId) }
                }
            }
    }

    /** 按明细 ID 写批准数量和批次；lot_id 由审批人在本次请求中确定并持久化 */
    private fun updateApprovedItems(
        conn: SqlConnection,
        requisitionId: String,
        rows: List<Row>,
        approveMap: Map<String, Pair<BigDecimal, String?>>,
    ): Future<Void?> {
        fun updateOne(index: Int): Future<Void?> {
            if (index >= rows.size) return Future.succeededFuture(null)
            val row = rows[index]
            val entry = approveMap[row.getString("id")]!!
            val update = ctx.update(ri)
                .set(ri.APPROVED_QUANTITY, entry.first)
                .set(ri.LOT_ID, entry.second)
                .where(ri.ID.eq(row.getString("id") ?: ""))
                .and(ri.REQUISITION_ID.eq(requisitionId))
            return conn.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { _: RowSet<Row> -> updateOne(index + 1) }
        }
        return updateOne(0)
    }

    // ========================================================================
    //  确认调拨
    // ========================================================================

    fun dispense(id: String, userId: String): Future<JsonObject> {
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    when (header.getString("status")) {
                        "DISPENSED" -> loadDetail(conn, id)
                        "APPROVED" -> doDispense(conn, header, userId)
                        else -> Future.failedFuture(
                            ConflictException("cannot dispense a ${header.getString("status")} requisition"),
                        )
                    }
                }
        }
    }

    private fun doDispense(conn: SqlConnection, header: Row, userId: String): Future<JsonObject> {
        val reqId = header.getString("id") ?: return Future.failedFuture(IllegalArgumentException("id required"))
        return lockItems(conn, reqId)
            .compose { rows: List<Row> ->
                val items = rows.mapNotNull { row ->
                    val approvedQty = qtyOf(row.getValue("approved_quantity")) ?: BigDecimal.ZERO
                    if (approvedQty.compareTo(BigDecimal.ZERO) > 0) {
                        RequisitionTransferItem(
                            materialId = row.getString("material_id") ?: "",
                            lotId = row.getString("lot_id"),
                            quantity = approvedQty,
                        )
                    } else {
                        null
                    }
                }
                if (items.isEmpty()) {
                    return@compose Future.failedFuture(ConflictException("no approved items to transfer"))
                }
                inventoryPort.confirmReservedTransfer(
                    conn,
                    RequisitionTransferCommand(
                        sourceWarehouse = header.getString("warehouse") ?: "",
                        destinationWarehouse = header.getString("destination_warehouse") ?: "",
                        requisitionId = reqId,
                        requisitionNo = header.getString("requisition_no") ?: "",
                        dispensedBy = userId,
                        items = items,
                    ),
                ).compose { result: RequisitionTransferResult ->
                    writeTransferDetails(conn, reqId, rows, result)
                        .compose { _: Void? ->
                            val now = OffsetDateTime.now()
                            val updateHeader = ctx.update(r)
                                .set(r.STATUS, "DISPENSED")
                                .set(r.DISPENSED_BY, userId)
                                .set(r.DISPENSED_AT, now)
                                .set(r.UPDATED_AT, now)
                                .where(r.ID.eq(reqId))
                            conn.preparedQuery(DatabaseConfig.sql(updateHeader))
                                .execute(DatabaseConfig.tuple(updateHeader))
                        }
                        .compose { _: RowSet<Row> -> loadDetail(conn, reqId) }
                }
            }
    }

    /** 按 material_id 匹配（单内物资唯一）回写每项双向库存操作明细 ID 与实发数量；零批准量行不参与调拨，跳过不回写 */
    private fun writeTransferDetails(
        conn: SqlConnection,
        requisitionId: String,
        rows: List<Row>,
        result: RequisitionTransferResult,
    ): Future<Void?> {
        val byMaterial = result.items.associateBy { it.materialId }
        fun updateOne(index: Int): Future<Void?> {
            if (index >= rows.size) return Future.succeededFuture(null)
            val row = rows[index]
            val approvedQty = qtyOf(row.getValue("approved_quantity")) ?: BigDecimal.ZERO
            if (approvedQty.compareTo(BigDecimal.ZERO) <= 0) {
                return updateOne(index + 1)
            }
            val itemResult = byMaterial[row.getString("material_id")]
            if (itemResult == null) {
                return Future.failedFuture(
                    ConflictException("transfer result missing material ${row.getString("material_id")}"),
                )
            }
            val update = ctx.update(ri)
                .set(ri.DISPENSED_QUANTITY, approvedQty)
                .set(ri.OUTBOUND_STOCK_OPERATION_DETAIL_ID, itemResult.outboundStockOperationDetailId)
                .set(ri.INBOUND_STOCK_OPERATION_DETAIL_ID, itemResult.inboundStockOperationDetailId)
                .where(ri.ID.eq(row.getString("id") ?: ""))
                .and(ri.REQUISITION_ID.eq(requisitionId))
            return conn.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { _: RowSet<Row> -> updateOne(index + 1) }
        }
        return updateOne(0)
    }

    // ========================================================================
    //  取消
    // ========================================================================

    fun cancel(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        validateCancelBody(body)?.let { return Future.failedFuture(it) }
        return pool.withTransaction { conn: SqlConnection ->
            lockHeader(conn, id)
                .compose { header: Row ->
                    when (header.getString("status")) {
                        "CANCELLED" -> loadDetail(conn, id)
                        "DISPENSED" -> Future.failedFuture(ConflictException("cannot cancel a DISPENSED requisition"))
                        "DRAFT" -> writeCancelled(conn, header, body.getString("reason"), userId, release = false)
                        else -> writeCancelled(conn, header, body.getString("reason"), userId, release = true)
                    }
                }
        }
    }

    private fun writeCancelled(
        conn: SqlConnection,
        header: Row,
        reason: String?,
        userId: String,
        release: Boolean,
    ): Future<JsonObject> {
        val reqId = header.getString("id") ?: return Future.failedFuture(IllegalArgumentException("id required"))
        val afterRelease: Future<Void?> = if (release) {
            lockItems(conn, reqId)
                .compose { rows: List<Row> ->
                    val items = rows.mapNotNull { row ->
                        val approvedQty = qtyOf(row.getValue("approved_quantity")) ?: BigDecimal.ZERO
                        if (approvedQty.compareTo(BigDecimal.ZERO) > 0) {
                            RequisitionReleaseItem(
                                materialId = row.getString("material_id") ?: "",
                                lotId = row.getString("lot_id"),
                                quantity = approvedQty,
                            )
                        } else {
                            null
                        }
                    }
                    inventoryPort.releaseReservation(
                        conn,
                        RequisitionReleaseCommand(warehouse = header.getString("warehouse") ?: "", items = items),
                    )
                }
        } else {
            Future.succeededFuture(null)
        }

        return afterRelease.compose { _: Void? ->
            val now = OffsetDateTime.now()
            val update = ctx.update(r)
                .set(r.STATUS, "CANCELLED")
                .set(r.CANCELLED_BY, userId)
                .set(r.CANCELLED_AT, now)
                .set(r.CANCEL_REASON, reason)
                .set(r.UPDATED_AT, now)
                .where(r.ID.eq(reqId))
            conn.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
        }.compose { _: RowSet<Row> -> loadDetail(conn, reqId) }
    }

    // ========================================================================
    //  只读查询
    // ========================================================================

    fun list(
        warehouse: String? = null,
        destinationWarehouse: String? = null,
        department: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        warehouse?.let { conditions.add(r.WAREHOUSE.eq(it)) }
        destinationWarehouse?.let { conditions.add(r.DESTINATION_WAREHOUSE.eq(it)) }
        department?.let { conditions.add(r.DEPARTMENT.eq(it)) }
        status?.let { conditions.add(r.STATUS.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(r).where(conditions)
        val dataQuery = ctx.select(
            r.ID.`as`("id"),
            r.REQUISITION_NO.`as`("requisition_no"),
            r.WAREHOUSE.`as`("warehouse"),
            r.DEPARTMENT.`as`("department"),
            r.STATUS.`as`("status"),
            r.DESTINATION_WAREHOUSE.`as`("destination_warehouse"),
            r.REQUESTER_ID.`as`("requester_id"),
            r.CREATED_AT.`as`("created_at"),
            r.APPROVED_AT.`as`("approved_at"),
            r.DISPENSED_AT.`as`("dispensed_at"),
            r.CANCELLED_AT.`as`("cancelled_at"),
            r.UPDATED_AT.`as`("updated_at"),
        )
            .from(r)
            .where(conditions)
            .orderBy(r.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .compose { countRows: RowSet<Row> ->
                val total = countRows.iterator().next().getValue("total")?.toString()?.toLong() ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows: RowSet<Row> ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(
                                JsonObject()
                                    .put("id", row.getValue("id")?.toString())
                                    .put("requisition_no", row.getValue("requisition_no")?.toString())
                                    .put("warehouse", row.getValue("warehouse")?.toString())
                                    .put("destination_warehouse", row.getValue("destination_warehouse")?.toString())
                                    .put("department", row.getValue("department")?.toString())
                                    .put("status", row.getValue("status")?.toString())
                                    .put("requester_id", row.getValue("requester_id")?.toString())
                                    .put("created_at", row.getValue("created_at")?.toString())
                                    .put("approved_at", row.getValue("approved_at")?.toString())
                                    .put("dispensed_at", row.getValue("dispensed_at")?.toString())
                                    .put("cancelled_at", row.getValue("cancelled_at")?.toString())
                                    .put("updated_at", row.getValue("updated_at")?.toString()),
                            )
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> = loadDetail(pool, id)

    // ========================================================================
    //  共享读取
    // ========================================================================

    private fun loadDetail(client: SqlClient, id: String): Future<JsonObject> {
        val headerQuery = headerSelect.where(r.ID.eq(id))
        return client.preparedQuery(DatabaseConfig.sql(headerQuery))
            .execute(DatabaseConfig.tuple(headerQuery))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("requisition not found: $id"))
                } else {
                    val header = headerToJson(rows.iterator().next())
                    val itemQuery = itemSelect.where(ri.REQUISITION_ID.eq(id))
                    client.preparedQuery(DatabaseConfig.sql(itemQuery))
                        .execute(DatabaseConfig.tuple(itemQuery))
                        .map { itemRows: RowSet<Row> ->
                            val items = JsonArray()
                            for (row in itemRows) {
                                items.add(itemToJson(row))
                            }
                            header.put("items", items)
                        }
                }
            }
    }

    private fun findByIdempotencyKey(client: SqlClient, key: String): Future<Row?> =
        client.preparedQuery(
            DatabaseConfig.sql(
                ctx.select(r.ID.`as`("id"), r.REQUEST_FINGERPRINT.`as`("fingerprint"))
                    .from(r)
                    .where(r.IDEMPOTENCY_KEY.eq(key)),
            ),
        )
            .execute(
                DatabaseConfig.tuple(
                    ctx.select(r.ID.`as`("id"), r.REQUEST_FINGERPRINT.`as`("fingerprint"))
                        .from(r)
                        .where(r.IDEMPOTENCY_KEY.eq(key)),
                ),
            )
            .map { rows: RowSet<Row> -> if (rows.size() > 0) rows.iterator().next() else null }

    private fun lockHeader(client: SqlClient, id: String): Future<Row> {
        val lockHeaderQuery = headerSelect.where(r.ID.eq(id)).forUpdate()
        return client.preparedQuery(DatabaseConfig.sql(lockHeaderQuery))
            .execute(DatabaseConfig.tuple(lockHeaderQuery))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("requisition not found: $id"))
                } else {
                    Future.succeededFuture(rows.iterator().next())
                }
            }
    }

    private fun lockItems(client: SqlClient, requisitionId: String): Future<List<Row>> {
        val lockItemsQuery = itemSelect.where(ri.REQUISITION_ID.eq(requisitionId)).orderBy(ri.ID).forUpdate()
        return client.preparedQuery(DatabaseConfig.sql(lockItemsQuery))
            .execute(DatabaseConfig.tuple(lockItemsQuery))
            .map { rows: RowSet<Row> -> rows.map { it } }
    }

    // ========================================================================
    //  序列化
    // ========================================================================

    companion object {
        fun headerToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("requisition_no", row.getValue("requisition_no")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("destination_warehouse", row.getValue("destination_warehouse")?.toString())
                .put("department", row.getValue("department")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("requester", row.getValue("requester")?.toString())
                .put("requester_id", row.getValue("requester_id")?.toString())
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getValue("created_at")?.toString())
                .put("approved_by", row.getValue("approved_by")?.toString())
                .put("approved_at", row.getValue("approved_at")?.toString())
                .put("dispensed_by", row.getValue("dispensed_by")?.toString())
                .put("dispensed_at", row.getValue("dispensed_at")?.toString())
                .put("cancelled_by", row.getValue("cancelled_by")?.toString())
                .put("cancelled_at", row.getValue("cancelled_at")?.toString())
                .put("cancel_reason", row.getValue("cancel_reason")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())

        fun itemToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("requisition_id", row.getValue("requisition_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("requested_quantity", decimalApi(qtyOf(row.getValue("requested_quantity"))))
                .put("approved_quantity", decimalApi(qtyOf(row.getValue("approved_quantity"))))
                .put("dispensed_quantity", decimalApi(qtyOf(row.getValue("dispensed_quantity"))))
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("outbound_stock_operation_detail_id", row.getValue("outbound_stock_operation_detail_id")?.toString())
                .put("inbound_stock_operation_detail_id", row.getValue("inbound_stock_operation_detail_id")?.toString())
                // 历史 V300 记录的兼容读取；013 新记录不回填
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("metadata", row.getValue("metadata"))

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

    private fun validateCreateBody(body: JsonObject, idempotencyKey: String?): Exception? {
        if (idempotencyKey.isNullOrBlank()) {
            return IllegalArgumentException("Idempotency-Key header is required")
        }
        rejectUnknown(body, setOf("warehouse", "destination_warehouse", "department", "items"))?.let { return it }
        if (body.getString("warehouse").isNullOrBlank()) return IllegalArgumentException("warehouse is required")
        if (body.getString("destination_warehouse").isNullOrBlank()) {
            return IllegalArgumentException("destination_warehouse is required")
        }
        if (body.getString("warehouse") == body.getString("destination_warehouse")) {
            return ConflictException("source warehouse must differ from destination warehouse")
        }
        if (body.getString("department").isNullOrBlank()) return IllegalArgumentException("department is required")
        val items = body.getJsonArray("items")
        if (items == null || items.isEmpty) return IllegalArgumentException("items is required and must not be empty")
        val materialIds = mutableSetOf<String>()
        for (i in 0 until items.size()) {
            val item = items.getJsonObject(i) ?: return IllegalArgumentException("items[$i] must be an object")
            rejectUnknown(item, setOf("material_id", "requested_quantity"))?.let { return it }
            val materialId = item.getString("material_id")
            if (materialId.isNullOrBlank()) return IllegalArgumentException("items[$i].material_id is required")
            if (!materialIds.add(materialId)) {
                return IllegalArgumentException("duplicate material_id in request: $materialId")
            }
            val qty = try {
                toBigDecimal(item.getValue("requested_quantity"))
            } catch (e: Exception) {
                return IllegalArgumentException("items[$i].requested_quantity must be decimal text")
            }
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                return IllegalArgumentException("items[$i].requested_quantity must be positive")
            }
        }
        return null
    }

    private fun validateApproveBody(body: JsonObject): Exception? {
        rejectUnknown(body, setOf("items"))?.let { return it }
        val items = body.getJsonArray("items")
        if (items == null || items.isEmpty) {
            return IllegalArgumentException("items with approved_quantity is required")
        }
        val ids = mutableSetOf<String>()
        for (i in 0 until items.size()) {
            val item = items.getJsonObject(i) ?: return IllegalArgumentException("items[$i] must be an object")
            rejectUnknown(item, setOf("id", "approved_quantity", "lot_id"))?.let { return it }
            val itemId = item.getString("id")
            if (itemId.isNullOrBlank()) return IllegalArgumentException("items[$i].id is required")
            if (!ids.add(itemId)) return IllegalArgumentException("duplicate item id in request: $itemId")
            val qty = try {
                toBigDecimal(item.getValue("approved_quantity"))
            } catch (e: Exception) {
                return IllegalArgumentException("items[$i].approved_quantity must be decimal text")
            }
            if (qty.compareTo(BigDecimal.ZERO) < 0) {
                return IllegalArgumentException("items[$i].approved_quantity must not be negative")
            }
            if (item.containsKey("lot_id") && item.getValue("lot_id") != null && item.getValue("lot_id") !is String) {
                return IllegalArgumentException("items[$i].lot_id must be a string or null")
            }
        }
        return null
    }

    private fun validateCancelBody(body: JsonObject): Exception? {
        rejectUnknown(body, setOf("reason"))?.let { return it }
        if (body.getString("reason").isNullOrBlank()) return IllegalArgumentException("reason is required")
        return null
    }

    private fun rejectUnknown(body: JsonObject, allowed: Set<String>): IllegalArgumentException? {
        val unknown = body.fieldNames().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            return IllegalArgumentException("unknown fields: ${unknown.joinToString(", ")}")
        }
        return null
    }

    /** 规范化审批集合：id → (approved_quantity, lot_id)，供幂等比较 */
    private fun requestedApproveMap(body: JsonObject): Map<String, Pair<BigDecimal, String?>> {
        val map = mutableMapOf<String, Pair<BigDecimal, String?>>()
        for (i in 0 until body.getJsonArray("items").size()) {
            val item = body.getJsonArray("items").getJsonObject(i)
            val lotId = if (item.containsKey("lot_id")) item.getValue("lot_id")?.toString() else null
            map[item.getString("id")] = Pair(toBigDecimal(item.getValue("approved_quantity")), lotId)
        }
        return map
    }

    /** 规范化创建请求内容摘要：固定字段顺序 + SHA-256，供幂等键比较 */
    private fun requestFingerprint(body: JsonObject): String {
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
                                .put("requested_quantity", toBigDecimal(item.getValue("requested_quantity")).stripTrailingZeros().toPlainString()),
                        )
                    }
                },
            )
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
