package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyDispenseItems.PHARMACY_DISPENSE_ITEMS
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyDispenses.PHARMACY_DISPENSES
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyReturnItems.PHARMACY_RETURN_ITEMS
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyReturns.PHARMACY_RETURNS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.time.OffsetDateTime

class ReturnService(
    private val pool: Pool,
    private val inventoryInboundPort: InventoryInboundPort,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private data class ReturnSource(
        val returnItemId: String,
        val dispenseId: String,
        val dispenseItemId: String,
        val patientId: String,
        val warehouse: String,
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val unitCost: BigDecimal,
        val originalStockOperationDetailId: String,
        val returnStockOperationDetailId: String?,
        val returnStatus: String,
        val dispenseStatus: String,
        val unit: String?,
    )

    companion object {
        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("return_no", row.getValue("return_no")?.toString())
            .put("original_dispense_id", row.getValue("original_dispense_id")?.toString())
            .put("patient_id", row.getValue("patient_id")?.toString())
            .put("return_reason", row.getValue("return_reason")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("operator", row.getValue("operator")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("total_quantity", (row.getValue("total_quantity") as? BigDecimal)?.toDouble())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("confirmed_at", row.getValue("confirmed_at")?.toString())

        fun itemToJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("return_id", row.getValue("return_id")?.toString())
            .put("dispense_item_id", row.getValue("dispense_item_id")?.toString())
            .put("quantity", (row.getValue("quantity") as? BigDecimal)?.toDouble())
            .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
            .put("unit_cost", (row.getValue("unit_cost") as? BigDecimal)?.toDouble())
            .put("total_cost", (row.getValue("total_cost") as? BigDecimal)?.toDouble())
            .put("metadata", row.getValue("metadata") as? JsonObject)
    }

    fun createFromDispense(body: JsonObject): Future<JsonObject> {
        val dispenseId = body.getString("dispense_id")?.trim().orEmpty()
        val dispenseItemId = body.getString("dispense_item_id")?.trim().orEmpty()
        val returnReason = body.getString("return_reason")?.trim().orEmpty()
        val operator = body.getString("operator")?.trim().orEmpty()
        val quantity = body.getDouble("quantity")
        val restockable = body.getBoolean("restockable")

        if (dispenseId.isBlank()) return Future.failedFuture(IllegalArgumentException("dispense_id is required"))
        if (dispenseItemId.isBlank()) return Future.failedFuture(IllegalArgumentException("dispense_item_id is required"))
        if (returnReason.isBlank()) return Future.failedFuture(IllegalArgumentException("return_reason is required"))
        if (operator.isBlank()) return Future.failedFuture(IllegalArgumentException("operator is required"))
        if (quantity == null || quantity <= 0) return Future.failedFuture(IllegalArgumentException("quantity must be positive"))
        if (restockable != true) return Future.failedFuture(IllegalArgumentException("restockable confirmation is required"))

        return pool.withTransaction<JsonObject> { connection ->
            loadDispenseSource(connection, dispenseId, dispenseItemId).compose { source ->
                validateDispenseSource(source)
                val requested = BigDecimal.valueOf(quantity)
                if (requested > source.quantity) {
                    return@compose Future.failedFuture(ConflictException("return quantity exceeds dispensed quantity"))
                }
                loadReservedReturnQuantity(connection, dispenseItemId).compose { reserved ->
                    if (reserved.add(requested) > source.quantity) {
                        return@compose Future.failedFuture(ConflictException("return quantity exceeds remaining quantity"))
                    }
                    insertReturn(
                        connection,
                        source,
                        requested,
                        returnReason,
                        operator,
                        body,
                    )
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
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.takeIf(String::isNotBlank)?.let { conditions.add(PHARMACY_RETURNS.PATIENT_ID.eq(it)) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(PHARMACY_RETURNS.STATUS.eq(it)) }
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        val countQuery = ctx.select(count(PHARMACY_RETURNS.ID).`as`("total"))
            .from(PHARMACY_RETURNS)
            .where(conditions)
        val totalQuantity = DSL.field(
            DSL.select(DSL.coalesce(DSL.sum(PHARMACY_RETURN_ITEMS.QUANTITY), DSL.inline(BigDecimal.ZERO)))
                .from(PHARMACY_RETURN_ITEMS)
                .where(PHARMACY_RETURN_ITEMS.RETURN_ID.eq(PHARMACY_RETURNS.ID)),
        ).`as`("total_quantity")
        val dataQuery = ctx.select(PHARMACY_RETURNS.fields().toList() + totalQuantity)
            .from(PHARMACY_RETURNS)
            .where(conditions)
            .orderBy(PHARMACY_RETURNS.CREATED_AT.desc())
            .limit(safeLimit)
            .offset(safeOffset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .compose { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(toJson(row))
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> = get(pool, id)

    private fun get(client: SqlClient, id: String): Future<JsonObject> {
        val query = ctx.selectFrom(PHARMACY_RETURNS).where(PHARMACY_RETURNS.ID.eq(id))
        val itemsQuery = ctx.selectFrom(PHARMACY_RETURN_ITEMS)
            .where(PHARMACY_RETURN_ITEMS.RETURN_ID.eq(id))
            .orderBy(PHARMACY_RETURN_ITEMS.ID.asc())
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("return not found: $id"))
                } else {
                    val header = toJson(rows.iterator().next())
                    client.preparedQuery(DatabaseConfig.sql(itemsQuery))
                        .execute(DatabaseConfig.tuple(itemsQuery))
                        .map { itemRows ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(itemToJson(row))
                            header.put("items", items)
                            header
                        }
                }
            }
    }

    fun confirm(id: String, body: JsonObject): Future<JsonObject> {
        val operator = body.getString("operator")?.trim().orEmpty()
        if (operator.isBlank()) return Future.failedFuture(IllegalArgumentException("operator is required"))

        return pool.withTransaction<JsonObject> { connection ->
            lockReturn(connection, id).compose { current ->
                when (current.getString("status")) {
                    "CONFIRMED" -> get(connection, id)
                    "PENDING" -> loadReturnSource(connection, id).compose { source ->
                        validateReturnSource(source)
                        if (source.returnStockOperationDetailId != null) {
                            return@compose Future.failedFuture(ConflictException("return already has stock operation"))
                        }
                        inventoryInboundPort.confirmPackageInbound(
                            connection,
                            PackageInboundCommand(
                                warehouse = source.warehouse,
                                materialId = source.materialId,
                                lotId = source.lotId,
                                quantity = source.quantity,
                                unitCost = source.unitCost,
                                note = "pharmacy return $id",
                            ),
                        ).compose { inbound ->
                            val now = OffsetDateTime.now()
                            val itemUpdate = ctx.update(PHARMACY_RETURN_ITEMS)
                                .set(PHARMACY_RETURN_ITEMS.STOCK_OPERATION_DETAIL_ID, inbound.stockOperationDetailId)
                                .set(PHARMACY_RETURN_ITEMS.UNIT_COST, inbound.unitCost)
                                .set(PHARMACY_RETURN_ITEMS.TOTAL_COST, inbound.unitCost.multiply(source.quantity))
                                .where(PHARMACY_RETURN_ITEMS.ID.eq(source.returnItemId))
                            val headerUpdate = ctx.update(PHARMACY_RETURNS)
                                .set(PHARMACY_RETURNS.STATUS, "CONFIRMED")
                                .set(PHARMACY_RETURNS.OPERATOR, operator)
                                .set(PHARMACY_RETURNS.CONFIRMED_AT, now)
                                .where(PHARMACY_RETURNS.ID.eq(id))
                            connection.preparedQuery(DatabaseConfig.sql(itemUpdate))
                                .execute(DatabaseConfig.tuple(itemUpdate))
                                .compose { connection.preparedQuery(DatabaseConfig.sql(headerUpdate)).execute(DatabaseConfig.tuple(headerUpdate)) }
                                .compose { get(connection, id) }
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
                val query = ctx.update(PHARMACY_RETURNS)
                    .set(PHARMACY_RETURNS.STATUS, "CANCELLED")
                    .where(PHARMACY_RETURNS.ID.eq(id))
                connection.preparedQuery(DatabaseConfig.sql(query))
                    .execute(DatabaseConfig.tuple(query))
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
        val headerInsert = ctx.insertInto(PHARMACY_RETURNS)
            .set(PHARMACY_RETURNS.ID, returnId)
            .set(PHARMACY_RETURNS.RETURN_NO, returnNo)
            .set(PHARMACY_RETURNS.ORIGINAL_DISPENSE_ID, source.dispenseId)
            .set(PHARMACY_RETURNS.PATIENT_ID, source.patientId)
            .set(PHARMACY_RETURNS.RETURN_REASON, reason)
            .set(PHARMACY_RETURNS.STATUS, "PENDING")
            .set(PHARMACY_RETURNS.OPERATOR, operator)
            .set(PHARMACY_RETURNS.METADATA, JSONB.valueOf(metadata.encode()))
            .set(PHARMACY_RETURNS.CREATED_AT, now)
        val itemInsert = ctx.insertInto(PHARMACY_RETURN_ITEMS)
            .set(PHARMACY_RETURN_ITEMS.ID, Ulid.generate())
            .set(PHARMACY_RETURN_ITEMS.RETURN_ID, returnId)
            .set(PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID, source.dispenseItemId)
            .set(PHARMACY_RETURN_ITEMS.QUANTITY, quantity)
            .set(PHARMACY_RETURN_ITEMS.UNIT_COST, source.unitCost)
            .set(PHARMACY_RETURN_ITEMS.TOTAL_COST, source.unitCost.multiply(quantity))
            .set(PHARMACY_RETURN_ITEMS.METADATA, JSONB.valueOf(JsonObject().put("restockable", true).encode()))
        return connection.preparedQuery(DatabaseConfig.sql(headerInsert))
            .execute(DatabaseConfig.tuple(headerInsert))
            .compose { connection.preparedQuery(DatabaseConfig.sql(itemInsert)).execute(DatabaseConfig.tuple(itemInsert)) }
            .compose { get(connection, returnId) }
    }

    private fun lockReturn(connection: SqlConnection, id: String): Future<JsonObject> {
        val query = ctx.selectFrom(PHARMACY_RETURNS)
            .where(PHARMACY_RETURNS.ID.eq(id))
            .forUpdate()
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("return not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    private fun loadDispenseSource(
        connection: SqlConnection,
        dispenseId: String,
        dispenseItemId: String,
    ): Future<ReturnSource> {
        val query = ctx.select(
            PHARMACY_DISPENSES.ID.`as`("dispense_id"),
            PHARMACY_DISPENSES.PATIENT_ID,
            PHARMACY_DISPENSES.WAREHOUSE,
            PHARMACY_DISPENSES.STATUS.`as`("dispense_status"),
            PHARMACY_DISPENSE_ITEMS.ID.`as`("dispense_item_id"),
            PHARMACY_DISPENSE_ITEMS.MATERIAL_ID,
            PHARMACY_DISPENSE_ITEMS.LOT_ID,
            PHARMACY_DISPENSE_ITEMS.DISPENSED_QUANTITY,
            PHARMACY_DISPENSE_ITEMS.UNIT,
            PHARMACY_DISPENSE_ITEMS.UNIT_COST,
            PHARMACY_DISPENSE_ITEMS.STOCK_OPERATION_DETAIL_ID.`as`("original_stock_operation_detail_id"),
        )
            .from(PHARMACY_DISPENSES)
            .join(PHARMACY_DISPENSE_ITEMS).on(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID.eq(PHARMACY_DISPENSES.ID))
            .where(PHARMACY_DISPENSES.ID.eq(dispenseId).and(PHARMACY_DISPENSE_ITEMS.ID.eq(dispenseItemId)))
            .forUpdate()
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) return@compose Future.failedFuture(NotFoundException("dispense item not found: $dispenseItemId"))
                Future.succeededFuture(sourceFromRow(rows.iterator().next()))
            }
    }

    private fun loadReturnSource(connection: SqlConnection, returnId: String): Future<ReturnSource> {
        val query = ctx.select(
            PHARMACY_RETURNS.ID.`as`("return_id"),
            PHARMACY_RETURNS.STATUS.`as`("return_status"),
            PHARMACY_RETURNS.ORIGINAL_DISPENSE_ID.`as`("dispense_id"),
            PHARMACY_RETURNS.PATIENT_ID,
            PHARMACY_DISPENSES.WAREHOUSE,
            PHARMACY_DISPENSES.STATUS.`as`("dispense_status"),
            PHARMACY_RETURN_ITEMS.ID.`as`("return_item_id"),
            PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID.`as`("dispense_item_id"),
            PHARMACY_RETURN_ITEMS.QUANTITY.`as`("return_quantity"),
            PHARMACY_RETURN_ITEMS.STOCK_OPERATION_DETAIL_ID.`as`("return_stock_operation_detail_id"),
            PHARMACY_DISPENSE_ITEMS.MATERIAL_ID,
            PHARMACY_DISPENSE_ITEMS.LOT_ID,
            PHARMACY_DISPENSE_ITEMS.UNIT,
            PHARMACY_DISPENSE_ITEMS.UNIT_COST,
            PHARMACY_DISPENSE_ITEMS.STOCK_OPERATION_DETAIL_ID.`as`("original_stock_operation_detail_id"),
        )
            .from(PHARMACY_RETURNS)
            .join(PHARMACY_RETURN_ITEMS).on(PHARMACY_RETURN_ITEMS.RETURN_ID.eq(PHARMACY_RETURNS.ID))
            .join(PHARMACY_DISPENSE_ITEMS).on(PHARMACY_DISPENSE_ITEMS.ID.eq(PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID))
            .join(PHARMACY_DISPENSES).on(PHARMACY_DISPENSES.ID.eq(PHARMACY_RETURNS.ORIGINAL_DISPENSE_ID))
            .where(PHARMACY_RETURNS.ID.eq(returnId))
            .forUpdate()
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) return@compose Future.failedFuture(NotFoundException("return item not found: $returnId"))
                Future.succeededFuture(sourceFromRow(rows.iterator().next(), returnRow = true))
            }
    }

    private fun sourceFromRow(row: Row, returnRow: Boolean = false): ReturnSource = ReturnSource(
        returnItemId = row.getString("return_item_id") ?: "",
        dispenseId = row.getString("dispense_id") ?: "",
        dispenseItemId = row.getString("dispense_item_id") ?: "",
        patientId = row.getString("patient_id") ?: "",
        warehouse = row.getString("warehouse") ?: "",
        materialId = row.getString("material_id") ?: "",
        lotId = row.getString("lot_id"),
        quantity = if (returnRow) row.getValue("return_quantity") as? BigDecimal ?: BigDecimal.ZERO
        else row.getValue("dispensed_quantity") as? BigDecimal ?: BigDecimal.ZERO,
        unitCost = row.getValue("unit_cost") as? BigDecimal ?: BigDecimal.ZERO,
        originalStockOperationDetailId = row.getString("original_stock_operation_detail_id") ?: "",
        returnStockOperationDetailId = if (returnRow) row.getString("return_stock_operation_detail_id") else null,
        returnStatus = if (returnRow) row.getString("return_status") ?: "" else "",
        dispenseStatus = row.getString("dispense_status") ?: "",
        unit = row.getString("unit"),
    )

    private fun validateDispenseSource(source: ReturnSource) {
        if (source.dispenseStatus != "DISPENSED") throw ConflictException("only DISPENSED dispense can be returned")
        if (source.unit != "PACKAGE") throw ConflictException("only PACKAGE dispense can be returned")
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

    private fun loadReservedReturnQuantity(connection: SqlConnection, dispenseItemId: String): Future<BigDecimal> {
        val query = ctx.select(DSL.sum(PHARMACY_RETURN_ITEMS.QUANTITY).`as`("reserved_quantity"))
            .from(PHARMACY_RETURN_ITEMS)
            .join(PHARMACY_RETURNS).on(PHARMACY_RETURNS.ID.eq(PHARMACY_RETURN_ITEMS.RETURN_ID))
            .where(PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID.eq(dispenseItemId))
            .and(PHARMACY_RETURNS.STATUS.ne("CANCELLED"))
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.iterator().next().getValue("reserved_quantity") as? BigDecimal ?: BigDecimal.ZERO }
    }
}
