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
import io.vertx.sqlclient.Tuple
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * 016 护理耗材消耗：客户端只提交 `stock_id + quantity`（基础数量），单位、成本和
 * 物资精度由服务端从库存行与物资档案读取并校验；与库存操作明细同事务写入。
 */
class InventoryConsumptionService(
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val log = LoggerFactory.getLogger(InventoryConsumptionService::class.java)

    data class ConsumptionItem(
        val stockId: String,
        /** 基础数量 */
        val quantity: BigDecimal,
    )

    data class NursingConsumptionCommand(
        val items: List<ConsumptionItem>,
        val taskExecutionId: String,
        val taskId: String,
        val periodId: String,
        val patientId: String,
        val executor: String,
        val businessTime: OffsetDateTime,
    )

    data class ConsumptionResult(
        val operationId: String,
        val orderNo: String,
        val detailResults: List<DetailResult>,
    )

    data class DetailResult(
        val detailId: String,
        val stockId: String,
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val unit: String,
        val unitCost: BigDecimal,
        val totalCost: BigDecimal,
        val warehouse: String,
    )

    fun consumeForNursingExecution(
        connection: SqlConnection,
        command: NursingConsumptionCommand,
    ): Future<ConsumptionResult> {
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one consumption item is required"))
        command.items.firstNotNullOfOrNull(::validateConsumptionItem)?.let { message ->
            return Future.failedFuture(IllegalArgumentException(message))
        }

        val now = command.businessTime

        return lockExecutionKey(connection, command.taskExecutionId)
            .compose { findExistingOperation(connection, command.taskExecutionId) }
            .compose { existing ->
                if (existing != null) {
                    if (!sameConsumptionItems(existing.detailResults, command.items)) {
                        return@compose Future.failedFuture(
                            ConflictException("nursing execution already has different consumptions"),
                        )
                    }
                    return@compose Future.succeededFuture(existing)
                }

                val opId = Ulid.generate()
                val orderNo = "NUR-${command.taskExecutionId}"

                val metadata = JsonObject()
                    .put("source", "NURSING_EXECUTION")
                    .put("task_execution_id", command.taskExecutionId)
                    .put("task_id", command.taskId)
                    .put("period_id", command.periodId)
                    .put("patient_id", command.patientId)
                    .put("executor", command.executor)

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
        taskExecutionId: String,
    ): Future<ConsumptionResult?> {
        val findQuery = ctx.select(
            StockOperations.STOCK_OPERATIONS.ID.`as`("operation_id"),
            StockOperations.STOCK_OPERATIONS.ORDER_NO.`as`("operation_order_no"),
        )
            .from(StockOperations.STOCK_OPERATIONS)
            .where(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE.eq("OUTBOUND"))
            .and(StockOperations.STOCK_OPERATIONS.STATUS.eq("CONFIRMED"))
            .and(DSL.field("metadata->>'source'").eq("NURSING_EXECUTION"))
            .and(DSL.field("metadata->>'task_execution_id'").eq(taskExecutionId))

        return connection.preparedQuery(DatabaseConfig.sql(findQuery))
            .execute(DatabaseConfig.tuple(findQuery))
            .compose { opRows ->
                if (opRows.size() == 0) return@compose Future.succeededFuture(null)
                val opRow = opRows.iterator().next()
                val opId = opRow.getValue(0)?.toString() ?: return@compose Future.succeededFuture(null)

                val detail = StockOperationDetails.STOCK_OPERATION_DETAILS.`as`("detail")
                val operation = StockOperations.STOCK_OPERATIONS.`as`("operation")
                val stock = Stocks.STOCKS.`as`("stock")
                val findDetails = ctx.select(
                    detail.ID,
                    detail.MATERIAL_ID,
                    detail.LOT_ID,
                    detail.QUANTITY,
                    detail.UNIT,
                    detail.UNIT_COST,
                    detail.TOTAL_COST,
                    stock.ID.`as`("stock_id"),
                    operation.WAREHOUSE.`as`("warehouse"),
                )
                    .from(detail)
                    .join(operation).on(detail.OPERATION_ID.eq(operation.ID))
                    .leftJoin(stock).on(
                        stock.WAREHOUSE.eq(operation.WAREHOUSE)
                            .and(stock.MATERIAL_ID.eq(detail.MATERIAL_ID))
                            .and(stock.LOT_ID.eq(detail.LOT_ID).or(stock.LOT_ID.isNull.and(detail.LOT_ID.isNull))),
                    )
                    .where(detail.OPERATION_ID.eq(opId))
                connection.preparedQuery(DatabaseConfig.sql(findDetails))
                    .execute(DatabaseConfig.tuple(findDetails))
                    .map { detailRows ->
                        val detailResults = mutableListOf<DetailResult>()
                        for (dr in detailRows) detailResults.add(rowToDetailResult(dr))
                        ConsumptionResult(
                            operationId = opId,
                            orderNo = opRow.getValue(1)?.toString() ?: "",
                            detailResults = detailResults,
                        )
                    }
            }
    }

    /** 锁定并按基础数量校验库存：物资（FOR UPDATE）→ 库存行（FOR UPDATE）。 */
    private fun validateAndLockStocks(
        connection: SqlConnection,
        command: NursingConsumptionCommand,
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

            val previewQuery = ctx.select(
                Stocks.STOCKS.WAREHOUSE.`as`("stock_warehouse"),
                Stocks.STOCKS.MATERIAL_ID.`as`("stock_material_id"),
                Stocks.STOCKS.LOT_ID.`as`("stock_lot_id"),
                Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
                Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
            )
                .from(Stocks.STOCKS)
                .where(Stocks.STOCKS.ID.eq(stockId))

            return connection.preparedQuery(DatabaseConfig.sql(previewQuery))
                .execute(DatabaseConfig.tuple(previewQuery))
                .compose { previewRows ->
                    if (previewRows.size() == 0)
                        return@compose Future.failedFuture(NotFoundException("stock not found: $stockId"))

                    val preview = previewRows.iterator().next()
                    val warehouse = preview.getValue(0)?.toString() ?: ""
                    val materialId = preview.getValue(1)?.toString() ?: ""
                    val lotId = preview.getValue(2)?.toString()
                    val previewQty = stockDecimalValue(preview.getValue(3))
                    val previewCost = stockDecimalValue(preview.getValue(4))

                    if (validatedItems.isNotEmpty() && warehouse != validatedItems[0].warehouse) {
                        return@compose Future.failedFuture(
                            ConflictException("stock $stockId is in warehouse $warehouse, not ${validatedItems[0].warehouse}"),
                        )
                    }

                    val avgUnitCost = if (previewQty.compareTo(BigDecimal.ZERO) > 0)
                        previewCost.divide(previewQty, COST_DB_SCALE, RoundingMode.HALF_UP)
                    else BigDecimal.ZERO

                    val materialQuery = ctx.select(
                        Materials.MATERIALS.STATUS.`as`("material_status"),
                        Materials.MATERIALS.BASE_UNIT.`as`("material_base_unit"),
                        Materials.MATERIALS.QUANTITY_SCALE.`as`("material_quantity_scale"),
                        Materials.MATERIALS.ENABLE_BATCH_CONTROL.`as`("material_batch_control"),
                    )
                        .from(Materials.MATERIALS)
                        .where(Materials.MATERIALS.ID.eq(materialId))
                        .forUpdate()
                    connection.preparedQuery(DatabaseConfig.sql(materialQuery))
                        .execute(DatabaseConfig.tuple(materialQuery))
                        .compose { matRows ->
                            if (matRows.size() == 0)
                                return@compose Future.failedFuture(NotFoundException("material not found: $materialId"))
                            val matRow = matRows.iterator().next()
                            val matStatus = matRow.getValue(0)?.toString() ?: ""
                            if (matStatus != "ACTIVE")
                                return@compose Future.failedFuture(ConflictException("material $materialId is not ACTIVE"))
                            val baseUnit = matRow.getValue(1)?.toString()
                            if (baseUnit.isNullOrBlank())
                                return@compose Future.failedFuture(ConflictException("material $materialId has no base unit"))
                            val scale = (matRow.getValue(2) as? Number)?.toInt() ?: 0
                            val batchControl = matRow.getValue(3) as? Boolean ?: false

                            validateBaseQuantity(item.quantity, scale)
                            validateUnitCost(avgUnitCost)
                            totalCostOf(item.quantity, avgUnitCost)

                            validateMaterialAndLot(connection, materialId, lotId, batchControl)
                                .compose {
                                    val lockQuery = ctx.select(
                                        Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
                                        Stocks.STOCKS.LOCKED_QUANTITY.`as`("stock_locked_quantity"),
                                        Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
                                    )
                                        .from(Stocks.STOCKS)
                                        .where(Stocks.STOCKS.ID.eq(stockId))
                                        .forUpdate()
                                    connection.preparedQuery(DatabaseConfig.sql(lockQuery))
                                        .execute(DatabaseConfig.tuple(lockQuery))
                                }
                                .compose { stockRows ->
                                    if (stockRows.size() == 0)
                                        return@compose Future.failedFuture(NotFoundException("stock not found: $stockId"))
                                    val stockRow = stockRows.iterator().next()
                                    val lockedQty = stockDecimalValue(stockRow.getValue(0))
                                    val locked = stockDecimalValue(stockRow.getValue(1))
                                    val available = lockedQty.subtract(locked)
                                    if (available < item.quantity)
                                        return@compose Future.failedFuture(
                                            ConflictException("insufficient stock: $stockId has $available available, requested ${item.quantity}"),
                                        )

                                    validatedItems.add(
                                        ValidatedItem(
                                            stockId = stockId,
                                            materialId = materialId,
                                            lotId = lotId,
                                            warehouse = warehouse,
                                            quantity = item.quantity,
                                            unit = baseUnit,
                                            unitCost = avgUnitCost,
                                            originalQuantity = lockedQty,
                                            originalTotalCost = stockDecimalValue(stockRow.getValue(2)),
                                        ),
                                    )
                                    process(index + 1)
                                }
                        }
                }
        }

        return process(0)
    }

    private fun totalCostOf(quantity: BigDecimal, unitCost: BigDecimal): BigDecimal {
        val total = quantity.multiply(unitCost)
        if (total.stripTrailingZeros().scale() > COST_DB_SCALE)
            throw IllegalArgumentException("total_cost exceeds precision of $COST_DB_SCALE decimals")
        return total
    }

    private fun validateMaterialAndLot(
        connection: SqlConnection,
        materialId: String,
        lotId: String?,
        batchControl: Boolean,
    ): Future<Void?> {
        if (batchControl && lotId == null)
            return Future.failedFuture(ConflictException("material $materialId requires a lot"))
        if (!batchControl && lotId != null)
            return Future.failedFuture(ConflictException("material $materialId does not use batch control"))
        if (lotId == null) return Future.succeededFuture(null)

        val checkLot = ctx.select(
            Lots.LOTS.MATERIAL_ID.`as`("lot_material_id"),
            Lots.LOTS.EXPIRY_DATE.`as`("lot_expiry_date"),
        )
            .from(Lots.LOTS)
            .where(Lots.LOTS.ID.eq(lotId))
        return connection.preparedQuery(DatabaseConfig.sql(checkLot))
            .execute(DatabaseConfig.tuple(checkLot))
            .compose { lotRows ->
                if (lotRows.size() == 0)
                    return@compose Future.failedFuture(ConflictException("lot $lotId not found"))
                val lotRow = lotRows.iterator().next()
                if (lotRow.getValue(0)?.toString() != materialId)
                    return@compose Future.failedFuture(ConflictException("lot $lotId does not belong to material $materialId"))
                val expiry = lotRow.getValue(1) as? java.time.LocalDate
                if (expiry != null && expiry.isBefore(java.time.LocalDate.now()))
                    return@compose Future.failedFuture(ConflictException("lot $lotId has expired on $expiry"))
                Future.succeededFuture(null as Void?)
            }
    }

    private fun processConsumptionItems(
        connection: SqlConnection,
        command: NursingConsumptionCommand,
        opId: String,
        validatedItems: List<ValidatedItem>,
        now: OffsetDateTime,
    ): Future<List<DetailResult>> {
        val detailResults = mutableListOf<DetailResult>()

        fun process(index: Int): Future<List<DetailResult>> {
            if (index >= validatedItems.size) return Future.succeededFuture(detailResults)
            val vi = validatedItems[index]
            val detailId = Ulid.generate()
            val totalCost = totalCostOf(vi.quantity, vi.unitCost)

            val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, vi.materialId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, vi.lotId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, vi.quantity)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, vi.unit)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, vi.unitCost)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, totalCost)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)

            return connection.preparedQuery(DatabaseConfig.sql(insertDetail))
                .execute(DatabaseConfig.tuple(insertDetail))
                .compose {
                    val newQty = vi.originalQuantity.subtract(vi.quantity)
                    val newCost = vi.originalTotalCost.subtract(totalCost).max(BigDecimal.ZERO)
                    val updateStock = ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, newQty)
                        .set(Stocks.STOCKS.TOTAL_COST, newCost)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                        .where(Stocks.STOCKS.ID.eq(vi.stockId))
                    connection.preparedQuery(DatabaseConfig.sql(updateStock))
                        .execute(DatabaseConfig.tuple(updateStock))
                        .map {
                            detailResults.add(
                                DetailResult(
                                    detailId = detailId,
                                    stockId = vi.stockId,
                                    materialId = vi.materialId,
                                    lotId = vi.lotId,
                                    quantity = vi.quantity,
                                    unit = vi.unit,
                                    unitCost = vi.unitCost,
                                    totalCost = totalCost,
                                    warehouse = vi.warehouse,
                                ),
                            )
                        }
                }
                .compose { process(index + 1) }
        }

        return process(0)
    }

    data class ValidatedItem(
        val stockId: String,
        val materialId: String,
        val lotId: String?,
        val warehouse: String,
        val quantity: BigDecimal,
        val unit: String,
        val unitCost: BigDecimal,
        val originalQuantity: BigDecimal,
        val originalTotalCost: BigDecimal,
    )

    private fun lockExecutionKey(
        connection: SqlConnection,
        taskExecutionId: String,
    ): Future<Void?> =
        connection
            .preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .execute(Tuple.of(taskExecutionId))
            .map { null as Void? }

    internal fun validateConsumptionItem(item: ConsumptionItem): String? {
        if (item.stockId.isBlank()) return "stock_id is required"
        if (item.quantity == null || item.quantity <= BigDecimal.ZERO) return "quantity must be > 0"
        return null
    }

    /** 幂等比较：同一 stock_id 且基础数量（十进制规范化）一致。 */
    internal fun sameConsumptionItems(
        existing: List<DetailResult>,
        requested: List<ConsumptionItem>,
    ): Boolean {
        if (existing.size != requested.size) return false
        val existingByStock = existing.associateBy { it.stockId }
        if (existingByStock.size != existing.size) return false
        return requested.all { item ->
            val detail = existingByStock[item.stockId] ?: return@all false
            detail.quantity.compareTo(item.quantity) == 0
        }
    }

    companion object {
        fun rowToDetailResult(row: Row): DetailResult =
            DetailResult(
                detailId = row.getValue("id")?.toString() ?: "",
                stockId = row.getValue("stock_id")?.toString() ?: "",
                materialId = row.getValue("material_id")?.toString() ?: "",
                lotId = row.getValue("lot_id")?.toString(),
                quantity = stockDecimalValue(row.getValue("quantity")),
                unit = row.getValue("unit")?.toString() ?: "",
                unitCost = stockDecimalValue(row.getValue("unit_cost")),
                totalCost = stockDecimalValue(row.getValue("total_cost")),
                warehouse = row.getValue("warehouse")?.toString() ?: "",
            )
    }
}
