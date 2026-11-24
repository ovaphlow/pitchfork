package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 退药（012）：从原发药明细退回到药房库存，带可退数量校验与库存 INBOUND 联动。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ；所有写路径复用调用方
 * 事务连接，行锁用 `FOR UPDATE`。
 */
class ReturnService(
    private val pool: Pool,
    private val inventoryInboundPort: InventoryInboundPort,
) {
    private data class ReturnSource(
        val returnItemId: String,
        val dispenseId: String,
        val dispenseItemId: String,
        val patientId: String,
        val warehouse: String,
        val materialId: String,
        val lotId: String?,
        /** 创建退药单时为本单退药数量，确认入库时为原发药明细实发数量 */
        val quantity: BigDecimal,
        /** 原发药明细实发数量：可退数量上限的基数 */
        val dispensedQuantity: BigDecimal,
        val unitCost: BigDecimal,
        val originalStockOperationDetailId: String,
        val returnStockOperationDetailId: String?,
        val returnStatus: String,
        val dispenseStatus: String,
    )

    companion object {
        private const val RETURN_COLUMNS =
            "id, return_no, original_dispense_id, patient_id, return_reason, status, operator, " +
                "metadata, created_at, confirmed_at"

        private const val ITEM_COLUMNS =
            "id, return_id, dispense_item_id, quantity, stock_operation_detail_id, unit_cost, " +
                "total_cost, metadata"

        private const val INSERT_RETURN_SQL =
            "INSERT INTO pharmacy.pharmacy_returns " +
                "(id, return_no, original_dispense_id, patient_id, return_reason, status, operator, " +
                "metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val INSERT_ITEM_SQL =
            "INSERT INTO pharmacy.pharmacy_return_items " +
                "(id, return_id, dispense_item_id, quantity, unit_cost, total_cost, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7)"

        private const val CONFIRM_ITEM_SQL =
            "UPDATE pharmacy.pharmacy_return_items " +
                "SET stock_operation_detail_id = \$1, unit_cost = \$2, total_cost = \$3 WHERE id = \$4"

        private const val CONFIRM_RETURN_SQL =
            "UPDATE pharmacy.pharmacy_returns SET status = \$1, operator = \$2, confirmed_at = \$3 " +
                "WHERE id = \$4"

        private const val CANCEL_SQL =
            "UPDATE pharmacy.pharmacy_returns SET status = \$1 WHERE id = \$2"

        /** 发药明细来源（创建退药单）：发药单与明细同锁。 */
        private const val DISPENSE_SOURCE_SQL =
            "SELECT d.id AS dispense_id, d.patient_id AS patient_id, d.warehouse AS warehouse, " +
                "d.status AS dispense_status, i.id AS dispense_item_id, i.material_id AS material_id, " +
                "i.lot_id AS lot_id, i.dispensed_quantity AS dispensed_quantity, i.unit_cost AS unit_cost, " +
                "i.stock_operation_detail_id AS original_stock_operation_detail_id " +
                "FROM pharmacy.pharmacy_dispenses d " +
                "JOIN pharmacy.pharmacy_dispense_items i ON i.dispense_id = d.id " +
                "WHERE d.id = \$1 AND i.id = \$2 FOR UPDATE"

        /** 退药来源（确认入库）：退药单/明细、发药明细与发药单一起锁。 */
        private const val RETURN_SOURCE_SQL =
            "SELECT r.id AS return_id, r.status AS return_status, " +
                "r.original_dispense_id AS dispense_id, r.patient_id AS patient_id, " +
                "d.warehouse AS warehouse, d.status AS dispense_status, " +
                "ri.id AS return_item_id, ri.dispense_item_id AS dispense_item_id, " +
                "ri.quantity AS return_quantity, " +
                "ri.stock_operation_detail_id AS return_stock_operation_detail_id, " +
                "i.material_id AS material_id, i.lot_id AS lot_id, " +
                "i.dispensed_quantity AS dispensed_quantity, i.unit_cost AS unit_cost, " +
                "i.stock_operation_detail_id AS original_stock_operation_detail_id " +
                "FROM pharmacy.pharmacy_returns r " +
                "JOIN pharmacy.pharmacy_return_items ri ON ri.return_id = r.id " +
                "JOIN pharmacy.pharmacy_dispense_items i ON i.id = ri.dispense_item_id " +
                "JOIN pharmacy.pharmacy_dispenses d ON d.id = r.original_dispense_id " +
                "WHERE r.id = \$1 FOR UPDATE"

        private const val TOTAL_QUANTITY_SUBQUERY =
            "(SELECT COALESCE(SUM(ri.quantity), 0) FROM pharmacy.pharmacy_return_items ri " +
                "WHERE ri.return_id = pharmacy_returns.id) AS total_quantity"

        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("return_no", row.getValue("return_no")?.toString())
            .put("original_dispense_id", row.getValue("original_dispense_id")?.toString())
            .put("patient_id", row.getValue("patient_id")?.toString())
            .put("return_reason", row.getValue("return_reason")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("operator", row.getValue("operator")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("total_quantity", if (row.getColumnIndex("total_quantity") >= 0) decimalApi(row.getBigDecimal("total_quantity")) else null)
            .put("created_at", row.getValue("created_at")?.toString())
            .put("confirmed_at", row.getValue("confirmed_at")?.toString())

        fun itemToJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("return_id", row.getValue("return_id")?.toString())
            .put("dispense_item_id", row.getValue("dispense_item_id")?.toString())
            .put("quantity", decimalApi(row.getBigDecimal("quantity")))
            .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
            .put("unit_cost", decimalApi(row.getBigDecimal("unit_cost")))
            .put("total_cost", decimalApi(row.getBigDecimal("total_cost")))
            .put("metadata", row.getValue("metadata") as? JsonObject)
    }

    fun createFromDispense(body: JsonObject, operator: String?): Future<JsonObject> {
        rejectUnknown(body, setOf("dispense_id", "dispense_item_id", "quantity", "return_reason", "operator", "restockable", "remark"))?.let {
            return Future.failedFuture(it)
        }
        val dispenseId = body.getString("dispense_id")?.trim().orEmpty()
        val dispenseItemId = body.getString("dispense_item_id")?.trim().orEmpty()
        val returnReason = body.getString("return_reason")?.trim().orEmpty()
        // 042：操作人由路由层从认证会话派生（`operator` 参数），请求体里的 `operator`
        // 只是它的来源之一（平台管理员代操作 / 无认证挂载回落），不再作为权威值直接落库。
        val operatorValue = operator?.trim().orEmpty()
        val quantity = requestDecimalText(body.getValue("quantity"))
        val restockable = body.getBoolean("restockable")

        if (dispenseId.isBlank()) return Future.failedFuture(IllegalArgumentException("dispense_id is required"))
        if (dispenseItemId.isBlank()) return Future.failedFuture(IllegalArgumentException("dispense_item_id is required"))
        if (returnReason.isBlank()) return Future.failedFuture(IllegalArgumentException("return_reason is required"))
        if (operatorValue.isBlank()) return Future.failedFuture(IllegalArgumentException("operator is required"))
        if (quantity == null) return Future.failedFuture(IllegalArgumentException("quantity must be decimal text"))
        if (quantity <= BigDecimal.ZERO) return Future.failedFuture(IllegalArgumentException("quantity must be positive"))
        try {
            validateQuantityPrecision(quantity, "quantity")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        if (restockable != true) return Future.failedFuture(IllegalArgumentException("restockable confirmation is required"))

        return pool.withTransaction<JsonObject> { connection ->
            loadDispenseSource(connection, dispenseId, dispenseItemId).compose { source ->
                validateDispenseSource(source)
                val requested = quantity
                // 可退口径：实发 − 累计已给药 − 未取消退药（待确认 + 已确认）；已给药不得退回库存。
                ReturnableQuantities.administeredQuantity(connection, dispenseItemId).compose { administered ->
                    ReturnableQuantities.reservedQuantity(connection, dispenseItemId).compose { reserved ->
                        ReturnableQuantities.overReturnError(requested, source.dispensedQuantity, administered, reserved)
                            ?.let { return@compose Future.failedFuture(it) }
                        insertReturn(
                            connection,
                            source,
                            requested,
                            returnReason,
                            operatorValue,
                            body,
                        )
                    }
                }
            }
        }
    }

    fun list(
        patientId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        patientId?.takeIf(String::isNotBlank)?.let { where.add("patient_id = ${bind(it)}") }
        status?.takeIf(String::isNotBlank)?.let { where.add("status = ${bind(it)}") }
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(id) AS total FROM pharmacy.pharmacy_returns$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $RETURN_COLUMNS, $TOTAL_QUANTITY_SUBQUERY FROM pharmacy.pharmacy_returns$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(safeLimit)} OFFSET ${bind(safeOffset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs))
            .compose { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(toJson(row))
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> = get(pool, id)

    private fun get(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery("SELECT $RETURN_COLUMNS FROM pharmacy.pharmacy_returns WHERE id = \$1")
            .execute(Tuple.of(id))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("return not found: $id"))
                } else {
                    val header = toJson(rows.iterator().next())
                    client.preparedQuery(
                        "SELECT $ITEM_COLUMNS FROM pharmacy.pharmacy_return_items " +
                            "WHERE return_id = \$1 ORDER BY id ASC"
                    ).execute(Tuple.of(id))
                        .map { itemRows ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(itemToJson(row))
                            header.put("items", items)
                            header
                        }
                }
            }

    fun confirm(id: String, body: JsonObject, operator: String?): Future<JsonObject> {
        rejectUnknown(body, setOf("operator", "remark"))?.let { return Future.failedFuture(it) }
        // 042：口径同 [createFromDispense]。
        val operatorValue = operator?.trim().orEmpty()
        if (operatorValue.isBlank()) return Future.failedFuture(IllegalArgumentException("operator is required"))

        return pool.withTransaction<JsonObject> { connection ->
            lockReturn(connection, id).compose { current ->
                when (current.getString("status")) {
                    "CONFIRMED" -> get(connection, id)
                    "PENDING" -> loadReturnSource(connection, id).compose { source ->
                        validateReturnSource(source)
                        if (source.returnStockOperationDetailId != null) {
                            return@compose Future.failedFuture(ConflictException("return already has stock operation"))
                        }
                        // 待确认期间可能补水给药记录：确认前按同一可退口径复验（本单排除后叠加本单数量）。
                        ReturnableQuantities.administeredQuantity(connection, source.dispenseItemId)
                            .compose { administered ->
                                ReturnableQuantities.reservedQuantity(
                                    connection,
                                    source.dispenseItemId,
                                    excludeReturnId = id,
                                ).compose { reservedByOthers ->
                                    ReturnableQuantities.overReturnError(
                                        source.quantity,
                                        source.dispensedQuantity,
                                        administered,
                                        reservedByOthers,
                                    )?.let { return@compose Future.failedFuture(it) }
                                    inventoryInboundPort.confirmInbound(
                                        connection,
                                        InboundCommand(
                                            warehouse = source.warehouse,
                                            materialId = source.materialId,
                                            lotId = source.lotId,
                                            quantity = source.quantity,
                                            unitCost = source.unitCost,
                                            note = "pharmacy return $id",
                                        ),
                                    ).compose { inbound ->
                                        val now = OffsetDateTime.now()
                                        connection.preparedQuery(CONFIRM_ITEM_SQL).execute(
                                            Tuple.of(
                                                inbound.stockOperationDetailId,
                                                inbound.unitCost,
                                                inbound.unitCost.multiply(source.quantity),
                                                source.returnItemId,
                                            )
                                        )
                                            .compose {
                                                connection.preparedQuery(CONFIRM_RETURN_SQL).execute(
                                                    Tuple.of("CONFIRMED", operatorValue, now, id)
                                                )
                                            }
                                            .compose { get(connection, id) }
                                    }
                                }
                            }
                    }
                    else -> Future.failedFuture(ConflictException("cannot confirm return in status ${current.getString("status")}"))
                }
            }
        }
    }

    fun cancel(id: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection ->
            lockReturn(connection, id).compose { current ->
                if (current.getString("status") != "PENDING") {
                    return@compose Future.failedFuture(ConflictException("cannot cancel return in status ${current.getString("status")}"))
                }
                connection.preparedQuery(CANCEL_SQL).execute(Tuple.of("CANCELLED", id))
                    .compose { get(connection, id) }
            }
        }

    private fun insertReturn(
        connection: SqlConnection,
        source: ReturnSource,
        quantity: BigDecimal,
        reason: String,
        operator: String,
        body: JsonObject,
    ): Future<JsonObject> {
        val returnId = Ulid.generate()
        val returnNo = "RT-$returnId"
        val now = OffsetDateTime.now()
        val metadata = JsonObject()
            .put("restockable", true)
            .put("dispense_id", source.dispenseId)
        body.getString("remark")?.takeIf(String::isNotBlank)?.let { metadata.put("remark", it) }
        return connection.preparedQuery(INSERT_RETURN_SQL).execute(
            Tuple.of(
                returnId, returnNo, source.dispenseId, source.patientId, reason, "PENDING",
                operator, metadata, now,
            )
        )
            .compose {
                connection.preparedQuery(INSERT_ITEM_SQL).execute(
                    Tuple.of(
                        Ulid.generate(), returnId, source.dispenseItemId, quantity,
                        source.unitCost, source.unitCost.multiply(quantity),
                        JsonObject().put("restockable", true),
                    )
                )
            }
            .compose { get(connection, returnId) }
    }

    private fun lockReturn(connection: SqlConnection, id: String): Future<JsonObject> =
        connection.preparedQuery(
            "SELECT $RETURN_COLUMNS FROM pharmacy.pharmacy_returns WHERE id = \$1 FOR UPDATE"
        ).execute(Tuple.of(id))
            .compose { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("return not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }

    private fun loadDispenseSource(
        connection: SqlConnection,
        dispenseId: String,
        dispenseItemId: String,
    ): Future<ReturnSource> =
        connection.preparedQuery(DISPENSE_SOURCE_SQL).execute(Tuple.of(dispenseId, dispenseItemId))
            .compose { rows ->
                if (rows.size() == 0) return@compose Future.failedFuture(NotFoundException("dispense item not found: $dispenseItemId"))
                Future.succeededFuture(sourceFromRow(rows.iterator().next()))
            }

    private fun loadReturnSource(connection: SqlConnection, returnId: String): Future<ReturnSource> =
        connection.preparedQuery(RETURN_SOURCE_SQL).execute(Tuple.of(returnId))
            .compose { rows ->
                if (rows.size() == 0) return@compose Future.failedFuture(NotFoundException("return item not found: $returnId"))
                Future.succeededFuture(sourceFromRow(rows.iterator().next(), returnRow = true))
            }

    private fun sourceFromRow(row: Row, returnRow: Boolean = false): ReturnSource = ReturnSource(
        returnItemId = if (row.getColumnIndex("return_item_id") >= 0) row.getString("return_item_id") ?: "" else "",
        dispenseId = row.getString("dispense_id") ?: "",
        dispenseItemId = row.getString("dispense_item_id") ?: "",
        patientId = row.getString("patient_id") ?: "",
        warehouse = row.getString("warehouse") ?: "",
        materialId = row.getString("material_id") ?: "",
        lotId = row.getString("lot_id"),
        quantity = if (returnRow) row.getBigDecimal("return_quantity") ?: BigDecimal.ZERO
        else row.getBigDecimal("dispensed_quantity") ?: BigDecimal.ZERO,
        dispensedQuantity = row.getBigDecimal("dispensed_quantity") ?: BigDecimal.ZERO,
        unitCost = row.getBigDecimal("unit_cost") ?: BigDecimal.ZERO,
        originalStockOperationDetailId = row.getString("original_stock_operation_detail_id") ?: "",
        returnStockOperationDetailId = if (returnRow) row.getString("return_stock_operation_detail_id") else null,
        returnStatus = if (returnRow) row.getString("return_status") ?: "" else "",
        dispenseStatus = row.getString("dispense_status") ?: "",
    )

    private fun validateDispenseSource(source: ReturnSource) {
        if (source.dispenseStatus != "DISPENSED") throw ConflictException("only DISPENSED dispense can be returned")
        if (source.warehouse.isBlank()) throw ConflictException("dispense has no warehouse")
        if (source.materialId.isBlank()) throw ConflictException("dispense item has no material")
        if (source.originalStockOperationDetailId.isBlank()) throw ConflictException("dispense item has no stock operation")
        if (source.quantity <= BigDecimal.ZERO) throw ConflictException("dispense item has invalid quantity")
        if (source.unitCost < BigDecimal.ZERO) throw ConflictException("dispense item has invalid unit cost")
    }

    private fun validateReturnSource(source: ReturnSource) {
        validateDispenseSource(source)
        if (source.returnStatus != "PENDING") throw ConflictException("return is not pending")
        if (source.returnItemId.isBlank()) throw ConflictException("return has no item")
    }

    private fun rejectUnknown(body: JsonObject, allowed: Set<String>): IllegalArgumentException? {
        val unknown = body.fieldNames().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            return IllegalArgumentException("unknown fields: ${unknown.joinToString(", ")}")
        }
        return null
    }
}
