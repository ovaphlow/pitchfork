package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Lots
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import com.ovaphlow.crate.database.gen.inventories.public_.tables.StockOperationDetails
import com.ovaphlow.crate.database.gen.inventories.public_.tables.StockOperations
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Stocks
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

class InventoryConsumptionService(
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val log = LoggerFactory.getLogger(InventoryConsumptionService::class.java)

    data class ConsumptionItem(
        val stockId: String,
        val unit: String,
        val quantity: BigDecimal?,
        val splitQuantity: BigDecimal?
    )

    data class NursingConsumptionCommand(
        val items: List<ConsumptionItem>,
        val taskExecutionId: String,
        val taskId: String,
        val periodId: String,
        val patientId: String,
        val executor: String,
        val businessTime: OffsetDateTime
    )

    data class ConsumptionResult(
        val operationId: String,
        val orderNo: String,
        val detailResults: List<DetailResult>
    )

    data class DetailResult(
        val detailId: String,
        val stockId: String,
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val unit: String,
        val splitQuantity: BigDecimal?,
        val unitCost: BigDecimal,
        val totalCost: BigDecimal,
        val warehouse: String
    )

    fun consumeForNursingExecution(
        connection: SqlConnection,
        command: NursingConsumptionCommand
    ): Future<ConsumptionResult> {
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one consumption item is required"))

        val now = command.businessTime

        return findExistingOperation(connection, command.taskExecutionId)
            .compose { existing ->
                if (existing != null) return@compose Future.succeededFuture(existing)

                val opId = Ulid.generate()
                val orderNo = "NUR-${command.taskExecutionId}"

                val metadata = JsonObject()
                    .put("source", "NURSING_EXECUTION")
                    .put("task_execution_id", command.taskExecutionId)
                    .put("task_id", command.taskId)
                    .put("period_id", command.periodId)
                    .put("patient_id", command.patientId)
                    .put("executor", command.executor)

                // 先验证库存，获取 warehouse
                validateAndLockStocks(connection, command)
                    .compose { validated ->
                        if (validated.isEmpty())
                            return@compose Future.failedFuture(IllegalArgumentException("no valid stock items"))
                        val warehouse = validated[0].warehouse

                        metadata.put("warehouse", warehouse)
                        val insertOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
                            .set(StockOperations.STOCK_OPERATIONS.ID, opId)
                            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, orderNo)
                            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "OUTBOUND")
                            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, warehouse)
                            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
                            .set(StockOperations.STOCK_OPERATIONS.METADATA, JSONB.valueOf(metadata.encode()))
                            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
                            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)

                        connection.preparedQuery(DatabaseConfig.sql(insertOp))
                            .execute(DatabaseConfig.tuple(insertOp))
                            .compose { processConsumptionItems(connection, command, opId, validated, now) }
                            .map { detailResults ->
                                ConsumptionResult(operationId = opId, orderNo = orderNo, detailResults = detailResults)
                            }
                    }
            }
    }

    private fun findExistingOperation(
        connection: SqlConnection,
        taskExecutionId: String
    ): Future<ConsumptionResult?> {
        val findQuery = ctx.selectFrom(StockOperations.STOCK_OPERATIONS)
            .where(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE.eq("OUTBOUND"))
            .and(StockOperations.STOCK_OPERATIONS.STATUS.eq("CONFIRMED"))
            .and(DSL.field("metadata->>'source'").eq("NURSING_EXECUTION"))
            .and(DSL.field("metadata->>'task_execution_id'").eq(taskExecutionId))

        return connection.preparedQuery(DatabaseConfig.sql(findQuery))
            .execute(DatabaseConfig.tuple(findQuery))
            .compose { opRows ->
                if (opRows.size() == 0) return@compose Future.succeededFuture(null)

                val opRow = opRows.iterator().next()
                val opId = opRow.getValue("id")?.toString() ?: return@compose Future.succeededFuture(null)

                val findDetails = ctx.selectFrom(StockOperationDetails.STOCK_OPERATION_DETAILS)
                    .where(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID.eq(opId))
                connection.preparedQuery(DatabaseConfig.sql(findDetails))
                    .execute(DatabaseConfig.tuple(findDetails))
                    .map { detailRows ->
                        val detailResults = mutableListOf<DetailResult>()
                        for (dr in detailRows) detailResults.add(rowToDetailResult(dr))
                        ConsumptionResult(
                            operationId = opId,
                            orderNo = opRow.getValue("order_no")?.toString() ?: "",
                            detailResults = detailResults
                        )
                    }
            }
    }

    private fun validateAndLockStocks(
        connection: SqlConnection,
        command: NursingConsumptionCommand
    ): Future<List<ValidatedItem>> {
        val stockIds = command.items.map { it.stockId }.distinct()
        if (stockIds.size != command.items.size)
            return Future.failedFuture(IllegalArgumentException("duplicate stock_id"))

        val sortedIds = stockIds.sorted()
        val validatedItems = mutableListOf<ValidatedItem>()

        fun process(index: Int): Future<List<ValidatedItem>> {
            if (index >= sortedIds.size) return Future.succeededFuture(validatedItems)

            val stockId = sortedIds[index]
            val item = command.items.find { it.stockId == stockId }!!

            val lockQuery = ctx.selectFrom(Stocks.STOCKS)
                .where(Stocks.STOCKS.ID.eq(stockId))
                .forUpdate()

            return connection.preparedQuery(DatabaseConfig.sql(lockQuery))
                .execute(DatabaseConfig.tuple(lockQuery))
                .compose { stockRows ->
                    if (stockRows.size() == 0)
                        return@compose Future.failedFuture(NotFoundException("stock not found: $stockId"))

                    val stockRow = stockRows.iterator().next()
                    val warehouse = stockRow.getValue("warehouse")?.toString() ?: ""
                    val materialId = stockRow.getValue("material_id")?.toString() ?: ""
                    val lotId = stockRow.getValue("lot_id")?.toString()
                    val quantity = stockRow.getValue("quantity") as? BigDecimal ?: BigDecimal.ZERO
                    val lockedQty = stockRow.getValue("locked_quantity") as? BigDecimal ?: BigDecimal.ZERO
                    val totalCost = stockRow.getValue("total_cost") as? BigDecimal ?: BigDecimal.ZERO
                    val availableQty = quantity.subtract(lockedQty)

                    if (validatedItems.isNotEmpty()) {
                        val firstWarehouse = validatedItems[0].warehouse
                        if (warehouse != firstWarehouse)
                            return@compose Future.failedFuture(
                                ConflictException("stock $stockId is in warehouse $warehouse, not $firstWarehouse")
                            )
                    }

                    val checkMaterial = ctx.select(Materials.MATERIALS.STATUS)
                        .from(Materials.MATERIALS)
                        .where(Materials.MATERIALS.ID.eq(materialId))
                    connection.preparedQuery(DatabaseConfig.sql(checkMaterial))
                        .execute(DatabaseConfig.tuple(checkMaterial))
                        .compose { matRows ->
                            if (matRows.size() > 0) {
                                val matStatus = matRows.iterator().next().getValue("status")?.toString()
                                if (matStatus != "ACTIVE")
                                    return@compose Future.failedFuture(ConflictException("material $materialId is not ACTIVE"))
                            }

                            if (lotId != null) {
                                val checkLot = ctx.select(Lots.LOTS.EXPIRY_DATE)
                                    .from(Lots.LOTS)
                                    .where(Lots.LOTS.ID.eq(lotId))
                                connection.preparedQuery(DatabaseConfig.sql(checkLot))
                                    .execute(DatabaseConfig.tuple(checkLot))
                                    .compose { lotRows ->
                                        if (lotRows.size() > 0) {
                                            val expiry = lotRows.iterator().next().getValue("expiry_date") as? java.time.LocalDate
                                            if (expiry != null && expiry.isBefore(java.time.LocalDate.now()))
                                                return@compose Future.failedFuture(ConflictException("lot $lotId has expired on $expiry"))
                                        }
                                        calculateDemandQty(connection, materialId, item)
                                            .compose { demandQty ->
                                                if (demandQty == null || demandQty <= BigDecimal.ZERO)
                                                    return@compose Future.failedFuture(IllegalArgumentException("invalid quantity for stock $stockId"))
                                                if (availableQty < demandQty)
                                                    return@compose Future.failedFuture(ConflictException("insufficient stock: $stockId has $availableQty available, requested $demandQty"))

                                                val unitCost = if (quantity.compareTo(BigDecimal.ZERO) > 0)
                                                    totalCost.divide(quantity, 4, RoundingMode.HALF_UP)
                                                else BigDecimal.ZERO

                                                validatedItems.add(ValidatedItem(stockId, materialId, lotId, warehouse, demandQty, unitCost, quantity, totalCost))
                                                process(index + 1)
                                            }
                                    }
                            } else {
                                calculateDemandQty(connection, materialId, item)
                                    .compose { demandQty ->
                                        if (demandQty == null || demandQty <= BigDecimal.ZERO)
                                            return@compose Future.failedFuture(IllegalArgumentException("invalid quantity for stock $stockId"))
                                        if (availableQty < demandQty)
                                            return@compose Future.failedFuture(ConflictException("insufficient stock: $stockId has $availableQty available, requested $demandQty"))

                                        val unitCost = if (quantity.compareTo(BigDecimal.ZERO) > 0)
                                            totalCost.divide(quantity, 4, RoundingMode.HALF_UP)
                                        else BigDecimal.ZERO

                                        validatedItems.add(ValidatedItem(stockId, materialId, lotId, warehouse, demandQty, unitCost, quantity, totalCost))
                                        process(index + 1)
                                    }
                            }
                        }
                }
        }

        return process(0)
    }

    private fun calculateDemandQty(
        connection: SqlConnection,
        materialId: String,
        item: ConsumptionItem
    ): Future<BigDecimal> {
        if (item.unit == "PACKAGE") return Future.succeededFuture(item.quantity)

        // SPLIT — 读取 split_ratio 并换算
        val query = ctx.select(Materials.MATERIALS.SPLIT_RATIO)
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.eq(materialId))

        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                if (rows.size() == 0) throw IllegalArgumentException("material $materialId not found for split calculation")
                val ratio = rows.iterator().next().getValue("split_ratio") as? BigDecimal
                if (ratio == null || ratio <= BigDecimal.ZERO) throw IllegalArgumentException("material $materialId has no valid split_ratio for SPLIT unit")
                val splitQty = item.splitQuantity
                if (splitQty == null || splitQty <= BigDecimal.ZERO) throw IllegalArgumentException("split_quantity must be > 0 for SPLIT unit")
                splitQty.divide(ratio, 4, RoundingMode.CEILING)
            }
    }

    private fun processConsumptionItems(
        connection: SqlConnection,
        command: NursingConsumptionCommand,
        opId: String,
        validatedItems: List<ValidatedItem>,
        now: OffsetDateTime
    ): Future<List<DetailResult>> {
        val detailResults = mutableListOf<DetailResult>()

        fun process(index: Int): Future<List<DetailResult>> {
            if (index >= validatedItems.size) return Future.succeededFuture(detailResults)

            val vi = validatedItems[index]
            val ci = command.items.find { it.stockId == vi.stockId }!!
            val detailId = Ulid.generate()
            val splitQty = if (ci.unit == "SPLIT") ci.splitQuantity else null
            val totalCost = vi.unitCost.multiply(vi.demandQty)

            val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, vi.materialId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, vi.lotId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, vi.demandQty)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, ci.unit)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, splitQty)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, vi.unitCost)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, totalCost)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)

            return connection.preparedQuery(DatabaseConfig.sql(insertDetail))
                .execute(DatabaseConfig.tuple(insertDetail))
                .compose {
                    val newQty = vi.originalQuantity.subtract(vi.demandQty)
                    val costShare = vi.unitCost.multiply(vi.demandQty)
                    val newCost = vi.originalTotalCost.subtract(costShare).max(BigDecimal.ZERO)

                    val updateStock = ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, newQty)
                        .set(Stocks.STOCKS.TOTAL_COST, newCost)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                        .where(Stocks.STOCKS.ID.eq(vi.stockId))

                    connection.preparedQuery(DatabaseConfig.sql(updateStock))
                        .execute(DatabaseConfig.tuple(updateStock))
                        .flatMap {
                            detailResults.add(DetailResult(detailId, vi.stockId, vi.materialId, vi.lotId, vi.demandQty, ci.unit, splitQty, vi.unitCost, totalCost, vi.warehouse))
                            process(index + 1)
                        }
                }
        }

        return process(0)
    }

    data class ValidatedItem(
        val stockId: String,
        val materialId: String,
        val lotId: String?,
        val warehouse: String,
        val demandQty: BigDecimal,
        val unitCost: BigDecimal,
        val originalQuantity: BigDecimal,
        val originalTotalCost: BigDecimal
    )

    companion object {
        fun rowToDetailResult(row: Row): DetailResult {
            return DetailResult(
                detailId = row.getValue("id")?.toString() ?: "",
                stockId = row.getValue("stock_id")?.toString() ?: "",
                materialId = row.getValue("material_id")?.toString() ?: "",
                lotId = row.getValue("lot_id")?.toString(),
                quantity = row.getValue("quantity") as? BigDecimal ?: BigDecimal.ZERO,
                unit = row.getValue("unit")?.toString() ?: "",
                splitQuantity = row.getValue("split_quantity") as? BigDecimal,
                unitCost = row.getValue("unit_cost") as? BigDecimal ?: BigDecimal.ZERO,
                totalCost = row.getValue("total_cost") as? BigDecimal ?: BigDecimal.ZERO,
                warehouse = row.getValue("warehouse")?.toString() ?: ""
            )
        }
    }
}
