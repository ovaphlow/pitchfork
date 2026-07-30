package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Lots
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import com.ovaphlow.crate.database.gen.inventories.public_.tables.StockOperationDetails
import com.ovaphlow.crate.database.gen.inventories.public_.tables.StockOperations
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Stocks
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

class StockService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val log = LoggerFactory.getLogger(StockService::class.java)

    // ========================================================================
    //  手工确认入库
    // ========================================================================

    data class InboundItem(
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val unitCost: BigDecimal
    )

    data class InboundCommand(
        val warehouse: String,
        val items: List<InboundItem>,
        val note: String?
    )

    fun confirmInbound(command: InboundCommand): Future<JsonObject> {
        if (command.warehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one item is required"))

        val now = OffsetDateTime.now()
        val opId = Ulid.generate()
        val orderNo = "IN-${opId}"

        return pool.withTransaction { connection ->
            val insertOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
                .set(StockOperations.STOCK_OPERATIONS.ID, opId)
                .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, orderNo)
                .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "INBOUND")
                .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.warehouse)
                .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
                .set(StockOperations.STOCK_OPERATIONS.METADATA, JSONB.valueOf(JsonObject()
                    .put("source", "MANUAL_INBOUND")
                    .put("note", command.note)
                    .encode()))
                .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
                .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)

            connection.preparedQuery(DatabaseConfig.sql(insertOp))
                .execute(DatabaseConfig.tuple(insertOp))
                .compose { processInboundItems(connection, command, opId, now) }
                .compose { loadOperation(connection, opId) }
        }
    }

    private fun processInboundItems(
        connection: SqlConnection,
        command: InboundCommand,
        opId: String,
        now: OffsetDateTime
    ): Future<List<String>> {
        val detailIds = mutableListOf<String>()

        fun processSequentially(index: Int): Future<List<String>> {
            if (index >= command.items.size)
                return Future.succeededFuture(detailIds)

            val item = command.items[index]
            // 验证批次管控 — 融入 compose 链，不嵌套
            val batchCtrlQuery = ctx.select(Materials.MATERIALS.ENABLE_BATCH_CONTROL)
                .from(Materials.MATERIALS)
                .where(Materials.MATERIALS.ID.eq(item.materialId))

            return connection.preparedQuery(DatabaseConfig.sql(batchCtrlQuery))
                .execute(DatabaseConfig.tuple(batchCtrlQuery))
                .compose { rows ->
                    if (rows.size() == 0)
                        throw NotFoundException("material not found: ${item.materialId}")

                    val batchCtrl = rows.iterator().next().getValue("enable_batch_control") as? Boolean ?: false
                    if (batchCtrl && item.lotId == null)
                        throw IllegalArgumentException("material ${item.materialId} requires batch control, lot_id is required")
                    if (!batchCtrl && item.lotId != null)
                        throw IllegalArgumentException("material ${item.materialId} does not use batch control, lot_id must not be provided")

                    if (item.lotId != null) {
                        val lotCheckQuery = ctx.select(Lots.LOTS.ID)
                            .from(Lots.LOTS)
                            .where(Lots.LOTS.ID.eq(item.lotId))
                            .and(Lots.LOTS.MATERIAL_ID.eq(item.materialId))
                        connection.preparedQuery(DatabaseConfig.sql(lotCheckQuery))
                            .execute(DatabaseConfig.tuple(lotCheckQuery))
                            .map { lotRows ->
                                if (lotRows.size() == 0)
                                    throw IllegalArgumentException("lot ${item.lotId} does not belong to material ${item.materialId}")
                            }
                    } else {
                        Future.succeededFuture(Unit)
                    }
                }
                .compose {
                    val detailId = Ulid.generate()
                    detailIds.add(detailId)
                    val totalCost = item.unitCost.multiply(item.quantity)

                    val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, item.materialId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, item.lotId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, item.quantity)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, "PACKAGE")
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, item.unitCost)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, totalCost)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)

                    connection.preparedQuery(DatabaseConfig.sql(insertDetail))
                        .execute(DatabaseConfig.tuple(insertDetail))
                        .compose { upsertStock(connection, command.warehouse, item.materialId, item.lotId, item.quantity, totalCost) }
                }
                .compose { processSequentially(index + 1) }
        }

        return processSequentially(0)
    }

    private fun upsertStock(
        connection: SqlConnection,
        warehouse: String,
        materialId: String,
        lotId: String?,
        addQty: BigDecimal,
        addCost: BigDecimal
    ): Future<Void?> {
        val now = OffsetDateTime.now()

        val findQuery = ctx.selectFrom(Stocks.STOCKS)
            .where(Stocks.STOCKS.WAREHOUSE.eq(warehouse)
                .and(Stocks.STOCKS.MATERIAL_ID.eq(materialId)))
            .let { q ->
                if (lotId != null) q.and(Stocks.STOCKS.LOT_ID.eq(lotId))
                else q.and(Stocks.STOCKS.LOT_ID.isNull)
            }

        return connection.preparedQuery(DatabaseConfig.sql(findQuery))
            .execute(DatabaseConfig.tuple(findQuery))
            .compose { rows ->
                if (rows.size() > 0) {
                    val row = rows.iterator().next()
                    val oldQty = row.getValue("quantity") as? BigDecimal ?: BigDecimal.ZERO
                    val oldCost = row.getValue("total_cost") as? BigDecimal ?: BigDecimal.ZERO
                    val stockId = row.getValue("id")?.toString()

                    val updateQ = ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, oldQty.add(addQty))
                        .set(Stocks.STOCKS.TOTAL_COST, oldCost.add(addCost))
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                        .where(Stocks.STOCKS.ID.eq(stockId))

                    connection.preparedQuery(DatabaseConfig.sql(updateQ))
                        .execute(DatabaseConfig.tuple(updateQ))
                        .map { null as Void? }
                } else {
                    val stockId = Ulid.generate()
                    val insertQ = ctx.insertInto(Stocks.STOCKS)
                        .set(Stocks.STOCKS.ID, stockId)
                        .set(Stocks.STOCKS.WAREHOUSE, warehouse)
                        .set(Stocks.STOCKS.MATERIAL_ID, materialId)
                        .set(Stocks.STOCKS.LOT_ID, lotId)
                        .set(Stocks.STOCKS.QUANTITY, addQty)
                        .set(Stocks.STOCKS.LOCKED_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.TOTAL_COST, addCost)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)

                    connection.preparedQuery(DatabaseConfig.sql(insertQ))
                        .execute(DatabaseConfig.tuple(insertQ))
                        .map { null as Void? }
                }
            }
    }

    // ========================================================================
    //  可用库存查询
    // ========================================================================

    fun listAvailableStocks(
        warehouse: String? = null,
        materialId: String? = null,
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val s = Stocks.STOCKS.`as`("s")
        val m = Materials.MATERIALS.`as`("m")
        val l = Lots.LOTS.`as`("l")

        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(s.QUANTITY.gt(s.LOCKED_QUANTITY))
        conditions.add(DSL.field("m.status").eq("ACTIVE"))

        warehouse?.let { conditions.add(s.WAREHOUSE.eq(it)) }
        materialId?.let { conditions.add(s.MATERIAL_ID.eq(it)) }

        if (!search.isNullOrBlank()) {
            conditions.add(
                DSL.or(
                    DSL.field("m.code").like("%$search%"),
                    DSL.field("m.name").like("%$search%"),
                    DSL.field("l.batch_no").like("%$search%")
                )
            )
        }

        conditions.add(
            DSL.or(
                DSL.field("l.expiry_date").isNull,
                DSL.field("l.expiry_date").ge(java.time.LocalDate.now())
            )
        )

        val columns = listOf(
            s.ID, s.WAREHOUSE, s.MATERIAL_ID, s.LOT_ID, s.QUANTITY, s.LOCKED_QUANTITY, s.TOTAL_COST, s.LAST_UPDATED,
            DSL.field("m.code").`as`("material_code"),
            DSL.field("m.name").`as`("material_name"),
            DSL.field("m.category").`as`("material_category"),
            DSL.field("m.package_unit").`as`("package_unit"),
            DSL.field("m.split_unit").`as`("split_unit"),
            DSL.field("m.split_ratio").`as`("split_ratio"),
            DSL.field("l.batch_no").`as`("batch_no"),
            DSL.field("l.expiry_date").`as`("expiry_date")
        )

        val baseFrom = ctx.select(columns)
            .from(s)
            .join(m).on(s.MATERIAL_ID.eq(DSL.field("m.id", String::class.java)))
            .leftJoin(l).on(s.LOT_ID.eq(DSL.field("l.id", String::class.java)))
            .where(conditions)

        val countQuery = ctx.select(count().`as`("total"))
            .from(s)
            .join(m).on(s.MATERIAL_ID.eq(DSL.field("m.id", String::class.java)))
            .leftJoin(l).on(s.LOT_ID.eq(DSL.field("l.id", String::class.java)))
            .where(conditions)

        val dataQuery = baseFrom
            .orderBy(DSL.field("m.name").asc(), DSL.field("l.batch_no").asc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(availableStockToJson(row))
                        JsonObject().put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun listAvailableWarehouses(): Future<List<String>> {
        val query = ctx.selectDistinct(Stocks.STOCKS.WAREHOUSE)
            .from(Stocks.STOCKS)
            .where(Stocks.STOCKS.QUANTITY.gt(Stocks.STOCKS.LOCKED_QUANTITY))
            .orderBy(Stocks.STOCKS.WAREHOUSE.asc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val warehouses = mutableListOf<String>()
                for (row in rows) {
                    row.getValue("warehouse")?.toString()?.let { warehouses.add(it) }
                }
                warehouses
            }
    }

    fun loadOperation(connection: SqlConnection, opId: String): Future<JsonObject> {
        val findOp = ctx.selectFrom(StockOperations.STOCK_OPERATIONS)
            .where(StockOperations.STOCK_OPERATIONS.ID.eq(opId))
        return connection.preparedQuery(DatabaseConfig.sql(findOp))
            .execute(DatabaseConfig.tuple(findOp))
            .compose { opRows ->
                if (opRows.size() == 0)
                    return@compose Future.failedFuture(NotFoundException("operation not found"))

                val opRow = opRows.iterator().next()
                val result = operationToJson(opRow)

                val findDetails = ctx.selectFrom(StockOperationDetails.STOCK_OPERATION_DETAILS)
                    .where(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID.eq(opId))
                connection.preparedQuery(DatabaseConfig.sql(findDetails))
                    .execute(DatabaseConfig.tuple(findDetails))
                    .map { detailRows ->
                        val detailArray = JsonArray()
                        for (dr in detailRows) detailArray.add(detailToJson(dr))
                        result.put("details", detailArray)
                        result
                    }
            }
    }

    companion object {
        fun availableStockToJson(row: Row): JsonObject {
            val qty = row.getValue("quantity") as? BigDecimal ?: BigDecimal.ZERO
            val locked = row.getValue("locked_quantity") as? BigDecimal ?: BigDecimal.ZERO
            val available = qty.subtract(locked)
            val totalCost = row.getValue("total_cost") as? BigDecimal ?: BigDecimal.ZERO
            val unitCost = if (qty.compareTo(BigDecimal.ZERO) > 0)
                totalCost.divide(qty, 4, RoundingMode.HALF_UP)
            else BigDecimal.ZERO

            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("material_code", row.getValue("material_code")?.toString())
                .put("material_name", row.getValue("material_name")?.toString())
                .put("category", row.getValue("material_category")?.toString())
                .put("package_unit", row.getValue("package_unit")?.toString())
                .put("split_unit", row.getValue("split_unit")?.toString())
                .put("split_ratio", (row.getValue("split_ratio") as? BigDecimal)?.toDouble())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("batch_no", row.getValue("batch_no")?.toString())
                .put("expiry_date", row.getValue("expiry_date")?.toString())
                .put("quantity", qty.toDouble())
                .put("locked_quantity", locked.toDouble())
                .put("available_quantity", available.toDouble())
                .put("unit_cost", unitCost.toDouble())
        }

        fun operationToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("order_no", row.getValue("order_no")?.toString())
                .put("operation_type", row.getValue("operation_type")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("confirmed_at", row.getValue("confirmed_at")?.toString())
        }

        fun detailToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("operation_id", row.getValue("operation_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("quantity", (row.getValue("quantity") as? BigDecimal)?.toDouble())
                .put("unit", row.getValue("unit")?.toString())
                .put("split_quantity", (row.getValue("split_quantity") as? BigDecimal)?.toDouble())
                .put("unit_cost", (row.getValue("unit_cost") as? BigDecimal)?.toDouble())
                .put("total_cost", (row.getValue("total_cost") as? BigDecimal)?.toDouble())
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }
}
