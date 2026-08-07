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

private fun inventoryDecimalValue(value: Any?): BigDecimal =
    inventoryDecimalValueOrNull(value) ?: BigDecimal.ZERO

private fun inventoryDecimalValueOrNull(value: Any?): BigDecimal? = when (value) {
    null -> null
    is BigDecimal -> value
    is Number -> value.toString().toBigDecimalOrNull()
    else -> value.toString().toBigDecimalOrNull()
}

class InventoryConsumptionService(
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val log = LoggerFactory.getLogger(InventoryConsumptionService::class.java)

    /**
     * 耗材消费明细输入（计划 015 新契约）：
     *  - 新契约：unit_spec_id + input_quantity（按规格精确换算基础数量）
     *  - 旧契约（仅过渡兼容）：unit=PACKAGE/SPLIT + quantity/splitQuantity，
     *    映射到当前默认包装规格 / 基础单位规格
     * 两种形式互斥，混合或无法精确转换返回 400。
     */
    data class ConsumptionItem(
        val stockId: String,
        val unitSpecId: String? = null,
        val inputQuantity: BigDecimal? = null,
        val unit: String? = null,
        val quantity: BigDecimal? = null,
        val splitQuantity: BigDecimal? = null
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

    /** 耗材明细结果：保留历史投影列，同时返回基础数量与输入快照（权威列） */
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
        val warehouse: String,
        val unitSpecId: String? = null,
        val inputQuantity: BigDecimal? = null,
        val inputUnit: String? = null,
        val conversionRatio: BigDecimal? = null,
        val baseQuantity: BigDecimal? = null,
        val baseUnit: String? = null,
        val inputUnitCost: BigDecimal? = null,
        val baseUnitCost: BigDecimal? = null,
    )

    fun consumeForNursingExecution(
        connection: SqlConnection,
        command: NursingConsumptionCommand
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
                    detail.SPLIT_QUANTITY,
                    detail.UNIT_COST,
                    detail.TOTAL_COST,
                    detail.UNIT_SPEC_ID,
                    detail.INPUT_QUANTITY,
                    detail.INPUT_UNIT,
                    detail.CONVERSION_RATIO,
                    detail.BASE_QUANTITY.`as`("detail_base_quantity"),
                    detail.BASE_UNIT,
                    detail.INPUT_UNIT_COST,
                    detail.BASE_UNIT_COST,
                    stock.ID.`as`("stock_id"),
                    operation.WAREHOUSE.`as`("warehouse"),
                )
                    .from(detail)
                    .join(operation).on(detail.OPERATION_ID.eq(operation.ID))
                    .leftJoin(stock).on(
                        stock.WAREHOUSE.eq(operation.WAREHOUSE)
                            .and(stock.MATERIAL_ID.eq(detail.MATERIAL_ID))
                            .and(
                                stock.LOT_ID.eq(detail.LOT_ID)
                                    .or(stock.LOT_ID.isNull.and(detail.LOT_ID.isNull)),
                            ),
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
                            detailResults = detailResults
                        )
                    }
            }
    }

    /**
     * 锁定并按基础数量校验库存（计划 015）：
     *  1. 无锁预览 stock 行，确定 warehouse / material / lot 与平均成本（换算输入用）；
     *  2. 解析换算（锁定 material + spec —— 锁序：换算先于库存行）；
     *  3. 只读校验批控与批次归属/过期；
     *  4. FOR UPDATE 重锁库存行，用权威 base_quantity - locked_base_quantity
     *     校验基础可用量（并发窗口内的成本差异可接受，扣减有 .max(ZERO) 保护）。
     */
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
                    val previewQty = inventoryDecimalValue(preview.getValue(3))
                    val previewCost = inventoryDecimalValue(preview.getValue(4))

                    if (validatedItems.isNotEmpty()) {
                        val firstWarehouse = validatedItems[0].warehouse
                        if (warehouse != firstWarehouse)
                            return@compose Future.failedFuture(
                                ConflictException("stock $stockId is in warehouse $warehouse, not $firstWarehouse")
                            )
                    }

                    val avgUnitCost = if (previewQty.compareTo(BigDecimal.ZERO) > 0)
                        previewCost.divide(previewQty, 4, RoundingMode.HALF_UP)
                    else BigDecimal.ZERO

                    // 换算先于库存行锁定（锁序：material+spec → stock）
                    resolveConsumptionConversion(connection, materialId, item, avgUnitCost)
                        .compose { conversion ->
                            validateMaterialAndLot(connection, materialId, lotId).compose {
                                val lockQuery = ctx.select(
                                    Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
                                    Stocks.STOCKS.BASE_QUANTITY.`as`("stock_base_quantity"),
                                    Stocks.STOCKS.LOCKED_BASE_QUANTITY.`as`("stock_locked_base_quantity"),
                                    Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
                                )
                                    .from(Stocks.STOCKS)
                                    .where(Stocks.STOCKS.ID.eq(stockId))
                                    .forUpdate()

                                connection.preparedQuery(DatabaseConfig.sql(lockQuery))
                                    .execute(DatabaseConfig.tuple(lockQuery))
                                    .compose { stockRows ->
                                        if (stockRows.size() == 0)
                                            return@compose Future.failedFuture(NotFoundException("stock not found: $stockId"))

                                        val stockRow = stockRows.iterator().next()
                                        val lockedBaseQty = inventoryDecimalValue(stockRow.getValue(1))
                                        val lockedBase = inventoryDecimalValue(stockRow.getValue(2))
                                        val availableBase = lockedBaseQty.subtract(lockedBase)
                                        if (availableBase < conversion.baseQuantity)
                                            return@compose Future.failedFuture(
                                                ConflictException(
                                                    "insufficient stock: $stockId has $availableBase base units available, requested ${conversion.baseQuantity}",
                                                ),
                                            )

                                        validatedItems.add(
                                            ValidatedItem(
                                                stockId = stockId,
                                                materialId = materialId,
                                                lotId = lotId,
                                                warehouse = warehouse,
                                                conversion = conversion,
                                                originalQuantity = inventoryDecimalValue(stockRow.getValue(0)),
                                                originalBaseQuantity = lockedBaseQty,
                                                originalTotalCost = inventoryDecimalValue(stockRow.getValue(3)),
                                            ),
                                        )
                                        process(index + 1)
                                    }
                            }
                        }
                }
        }

        return process(0)
    }

    /** 解析耗材消费的换算：新契约按规格精确换算；旧 PACKAGE/SPLIT 映射默认包装/基础规格 */
    private fun resolveConsumptionConversion(
        connection: SqlConnection,
        materialId: String,
        item: ConsumptionItem,
        avgUnitCost: BigDecimal,
    ): Future<BaseQuantityCommand> {
        val conversion = UnitConversionService(ctx)
        return if (item.unitSpecId != null) {
            conversion.convert(connection, materialId, item.unitSpecId, item.inputQuantity!!, avgUnitCost)
        } else if (item.unit == "PACKAGE") {
            log.warn("legacy PACKAGE consumption for stock {}; mapping to current default package spec", item.stockId)
            conversion.resolvePackagePort(connection, materialId, item.quantity!!, avgUnitCost)
        } else if (item.unit == "SPLIT") {
            log.warn("legacy SPLIT consumption for stock {}; mapping to base unit spec (no more CEILING)", item.stockId)
            conversion.resolveSplitPort(connection, materialId, item.splitQuantity!!, avgUnitCost)
        } else {
            Future.failedFuture(IllegalArgumentException("unit must be PACKAGE or SPLIT"))
        }
    }

    /** 只读校验物资状态 / 批控开关与批次归属 / 过期（换算服务已校验物资 ACTIVE 与计量模型） */
    private fun validateMaterialAndLot(
        connection: SqlConnection,
        materialId: String,
        lotId: String?,
    ): Future<Void?> {
        val checkMaterial = ctx.select(
            Materials.MATERIALS.STATUS.`as`("material_status"),
            Materials.MATERIALS.ENABLE_BATCH_CONTROL.`as`("material_batch_control"),
        )
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.eq(materialId))
        return connection.preparedQuery(DatabaseConfig.sql(checkMaterial))
            .execute(DatabaseConfig.tuple(checkMaterial))
            .compose { matRows ->
                if (matRows.size() == 0)
                    return@compose Future.failedFuture(ConflictException("material $materialId not found"))
                val materialRow = matRows.iterator().next()
                val matStatus = materialRow.getValue(0)?.toString()
                if (matStatus != "ACTIVE")
                    return@compose Future.failedFuture(ConflictException("material $materialId is not ACTIVE"))
                val batchControlled = materialRow.getValue(1) as? Boolean ?: false
                if (batchControlled && lotId == null)
                    return@compose Future.failedFuture(ConflictException("material $materialId requires a lot"))
                if (!batchControlled && lotId != null)
                    return@compose Future.failedFuture(ConflictException("material $materialId does not use batch control"))

                if (lotId == null) return@compose Future.succeededFuture(null as Void?)

                val checkLot = ctx.select(
                    Lots.LOTS.MATERIAL_ID.`as`("lot_material_id"),
                    Lots.LOTS.EXPIRY_DATE.`as`("lot_expiry_date"),
                )
                    .from(Lots.LOTS)
                    .where(Lots.LOTS.ID.eq(lotId))
                connection.preparedQuery(DatabaseConfig.sql(checkLot))
                    .execute(DatabaseConfig.tuple(checkLot))
                    .compose { lotRows ->
                        if (lotRows.size() == 0)
                            return@compose Future.failedFuture(ConflictException("lot $lotId not found"))
                        val lotRow = lotRows.iterator().next()
                        val lotMaterialId = lotRow.getValue(0)?.toString()
                        if (lotMaterialId != materialId)
                            return@compose Future.failedFuture(ConflictException("lot $lotId does not belong to material $materialId"))
                        val expiry = lotRow.getValue(1) as? java.time.LocalDate
                        if (expiry != null && expiry.isBefore(java.time.LocalDate.now()))
                            return@compose Future.failedFuture(ConflictException("lot $lotId has expired on $expiry"))
                        Future.succeededFuture(null as Void?)
                    }
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
            val conversion = vi.conversion
            val detailId = Ulid.generate()
            val legacy = legacyDetailColumns(conversion)
            val totalCost = conversion.totalCost

            // 明细：旧列投影（unit/quantity/split_quantity）供历史展示，快照列为权威
            val insertDetail = applyConversionSnapshots(
                ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, vi.materialId)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, vi.lotId)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, legacy.second)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, legacy.first)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, legacy.third)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, conversion.inputUnitCost)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, totalCost)
                    .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now),
                conversion,
            )

            return connection.preparedQuery(DatabaseConfig.sql(insertDetail))
                .execute(DatabaseConfig.tuple(insertDetail))
                .compose {
                    val newQty = vi.originalQuantity.subtract(legacy.second)
                    val newBase = vi.originalBaseQuantity.subtract(conversion.baseQuantity)
                    val newCost = vi.originalTotalCost.subtract(totalCost).max(BigDecimal.ZERO)

                    val updateStock = ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, newQty)
                        .set(Stocks.STOCKS.BASE_QUANTITY, newBase)
                        .set(Stocks.STOCKS.TOTAL_COST, newCost)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                        .where(Stocks.STOCKS.ID.eq(vi.stockId))

                    connection.preparedQuery(DatabaseConfig.sql(updateStock))
                        .execute(DatabaseConfig.tuple(updateStock))
                        .flatMap {
                            detailResults.add(
                                DetailResult(
                                    detailId = detailId,
                                    stockId = vi.stockId,
                                    materialId = vi.materialId,
                                    lotId = vi.lotId,
                                    quantity = legacy.second,
                                    unit = legacy.first,
                                    splitQuantity = legacy.third,
                                    unitCost = conversion.inputUnitCost,
                                    totalCost = totalCost,
                                    warehouse = vi.warehouse,
                                    unitSpecId = conversion.unitSpecId,
                                    inputQuantity = conversion.inputQuantity,
                                    inputUnit = conversion.inputUnit,
                                    conversionRatio = conversion.conversionRatio,
                                    baseQuantity = conversion.baseQuantity,
                                    baseUnit = conversion.baseUnit,
                                    inputUnitCost = conversion.inputUnitCost,
                                    baseUnitCost = conversion.baseUnitCost,
                                ),
                            )
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
        /** 权威换算命令（快照 + 基础数量） */
        val conversion: BaseQuantityCommand,
        /** 锁后权威包装数量 */
        val originalQuantity: BigDecimal,
        /** 锁后权威基础数量 */
        val originalBaseQuantity: BigDecimal,
        val originalTotalCost: BigDecimal
    )

    private fun lockExecutionKey(
        connection: SqlConnection,
        taskExecutionId: String,
    ): Future<Void?> {
        return connection
            .preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .execute(Tuple.of(taskExecutionId))
            .map { null as Void? }
    }

    /** 新契约校验：unit_spec_id + input_quantity（或旧 PACKAGE/SPLIT 过渡），互斥 */
    internal fun validateConsumptionItem(item: ConsumptionItem): String? {
        if (item.stockId.isBlank()) return "stock_id is required"
        val hasNew = item.unitSpecId != null || item.inputQuantity != null
        val hasOld = item.unit != null || item.quantity != null || item.splitQuantity != null
        if (hasNew && hasOld)
            return "must not mix unit_spec_id/input_quantity with legacy unit/quantity"
        if (hasNew) {
            return when {
                item.unitSpecId == null -> "unit_spec_id is required when input_quantity is provided"
                item.inputQuantity == null -> "input_quantity is required when unit_spec_id is provided"
                item.inputQuantity <= BigDecimal.ZERO -> "input_quantity must be > 0"
                else -> null
            }
        }
        return when (item.unit) {
            "PACKAGE" -> when {
                item.quantity == null || item.quantity <= BigDecimal.ZERO ->
                    "quantity must be > 0 for PACKAGE unit"
                item.splitQuantity != null ->
                    "split_quantity is not allowed for PACKAGE unit"
                else -> null
            }
            "SPLIT" -> when {
                item.splitQuantity == null || item.splitQuantity <= BigDecimal.ZERO ->
                    "split_quantity must be > 0 for SPLIT unit"
                item.quantity != null ->
                    "quantity is not allowed for SPLIT unit"
                else -> null
            }
            else -> "unit must be PACKAGE or SPLIT (or provide unit_spec_id + input_quantity)"
        }
    }

    /**
     * 幂等比较：新契约按规格 + 输入数量快照比较；旧契约按历史投影列比较。
     * 计划 015 起不再使用向上取整的包装数算法。
     */
    internal fun sameConsumptionItems(
        existing: List<DetailResult>,
        requested: List<ConsumptionItem>,
    ): Boolean {
        if (existing.size != requested.size) return false
        val existingByStock = existing.associateBy { it.stockId }
        if (existingByStock.size != existing.size) return false
        return requested.all { item ->
            val detail = existingByStock[item.stockId] ?: return@all false
            if (item.unitSpecId != null || item.inputQuantity != null) {
                // 新契约：比较快照（unit_spec_id + input_quantity）
                detail.unitSpecId == item.unitSpecId &&
                    detail.inputQuantity != null &&
                    detail.inputQuantity.compareTo(item.inputQuantity!!) == 0
            } else if (detail.unit != item.unit) {
                false
            } else if (item.unit == "SPLIT") {
                item.splitQuantity?.let { requested ->
                    detail.splitQuantity?.compareTo(requested) == 0
                } ?: false
            } else {
                item.quantity?.let { requested -> detail.quantity.compareTo(requested) == 0 } ?: false
            }
        }
    }

    companion object {
        fun rowToDetailResult(row: Row): DetailResult {
            return DetailResult(
                detailId = row.getValue("id")?.toString() ?: "",
                stockId = row.getValue("stock_id")?.toString() ?: "",
                materialId = row.getValue("material_id")?.toString() ?: "",
                lotId = row.getValue("lot_id")?.toString(),
                quantity = inventoryDecimalValue(row.getValue("quantity")),
                unit = row.getValue("unit")?.toString() ?: "",
                splitQuantity = inventoryDecimalValueOrNull(row.getValue("split_quantity")),
                unitCost = inventoryDecimalValue(row.getValue("unit_cost")),
                totalCost = inventoryDecimalValue(row.getValue("total_cost")),
                warehouse = row.getValue("warehouse")?.toString() ?: "",
                unitSpecId = row.getValue("unit_spec_id")?.toString(),
                inputQuantity = inventoryDecimalValueOrNull(row.getValue("input_quantity")),
                inputUnit = row.getValue("input_unit")?.toString(),
                conversionRatio = inventoryDecimalValueOrNull(row.getValue("conversion_ratio")),
                baseQuantity = inventoryDecimalValueOrNull(row.getValue("detail_base_quantity")),
                baseUnit = row.getValue("base_unit")?.toString(),
                inputUnitCost = inventoryDecimalValueOrNull(row.getValue("input_unit_cost")),
                baseUnitCost = inventoryDecimalValueOrNull(row.getValue("base_unit_cost")),
            )
        }
    }

}
