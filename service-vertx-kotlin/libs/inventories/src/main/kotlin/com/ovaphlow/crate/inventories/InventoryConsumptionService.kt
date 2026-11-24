package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * 016 护理耗材消耗：客户端只提交 `stock_id + quantity`（基础数量），单位、成本和
 * 物资精度由服务端从库存行与物资档案读取并校验；与库存操作明细同事务写入。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ；所有语句都走调用方
 * 传入的事务连接 [SqlConnection]，不在内部另起事务。
 */
class InventoryConsumptionService {

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
                        connection.preparedQuery(INSERT_OPERATION_SQL).execute(
                            Tuple.of(
                                opId, orderNo, "OUTBOUND", warehouse, "CONFIRMED", metadata, now, now,
                            )
                        )
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
    ): Future<ConsumptionResult?> =
        connection.preparedQuery(FIND_OPERATION_SQL)
            .execute(Tuple.of("OUTBOUND", "CONFIRMED", "NURSING_EXECUTION", taskExecutionId))
            .compose { opRows ->
                if (opRows.size() == 0) return@compose Future.succeededFuture(null)
                val opRow = opRows.iterator().next()
                val opId = opRow.getValue("operation_id")?.toString()
                    ?: return@compose Future.succeededFuture(null)

                connection.preparedQuery(FIND_DETAILS_SQL).execute(Tuple.of(opId))
                    .map { detailRows ->
                        val detailResults = mutableListOf<DetailResult>()
                        for (dr in detailRows) detailResults.add(rowToDetailResult(dr))
                        ConsumptionResult(
                            operationId = opId,
                            orderNo = opRow.getValue("operation_order_no")?.toString() ?: "",
                            detailResults = detailResults,
                        )
                    }
            }

    /** 锁定并按基础数量校验库存：库存预览 → 物资（FOR UPDATE）→ 批次 → 库存行（FOR UPDATE）。 */
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

            return connection.preparedQuery(STOCK_PREVIEW_SQL).execute(Tuple.of(stockId))
                .compose { previewRows ->
                    if (previewRows.size() == 0)
                        return@compose Future.failedFuture(NotFoundException("stock not found: $stockId"))

                    val preview = previewRows.iterator().next()
                    val warehouse = preview.getValue("stock_warehouse")?.toString() ?: ""
                    val materialId = preview.getValue("stock_material_id")?.toString() ?: ""
                    val lotId = preview.getValue("stock_lot_id")?.toString()
                    val previewQty = stockDecimalValue(preview.getValue("stock_quantity"))
                    val previewCost = stockDecimalValue(preview.getValue("stock_total_cost"))

                    if (validatedItems.isNotEmpty() && warehouse != validatedItems[0].warehouse) {
                        return@compose Future.failedFuture(
                            ConflictException("stock $stockId is in warehouse $warehouse, not ${validatedItems[0].warehouse}"),
                        )
                    }

                    val avgUnitCost = if (previewQty.compareTo(BigDecimal.ZERO) > 0)
                        previewCost.divide(previewQty, COST_DB_SCALE, RoundingMode.HALF_UP)
                    else BigDecimal.ZERO

                    connection.preparedQuery(MATERIAL_LOCK_SQL).execute(Tuple.of(materialId))
                        .compose { matRows ->
                            if (matRows.size() == 0)
                                return@compose Future.failedFuture(NotFoundException("material not found: $materialId"))
                            val matRow = matRows.iterator().next()
                            val matStatus = matRow.getValue("material_status")?.toString() ?: ""
                            if (matStatus != "ACTIVE")
                                return@compose Future.failedFuture(ConflictException("material $materialId is not ACTIVE"))
                            val baseUnit = matRow.getValue("material_base_unit")?.toString()
                            if (baseUnit.isNullOrBlank())
                                return@compose Future.failedFuture(ConflictException("material $materialId has no base unit"))
                            val scale = (matRow.getValue("material_quantity_scale") as? Number)?.toInt() ?: 0
                            val batchControl = matRow.getValue("material_batch_control") as? Boolean ?: false

                            validateBaseQuantity(item.quantity, scale)
                            validateUnitCost(avgUnitCost)
                            totalCostOf(item.quantity, avgUnitCost)

                            validateMaterialAndLot(connection, materialId, lotId, batchControl)
                                .compose {
                                    connection.preparedQuery(STOCK_LOCK_SQL).execute(Tuple.of(stockId))
                                }
                                .compose { stockRows ->
                                    if (stockRows.size() == 0)
                                        return@compose Future.failedFuture(NotFoundException("stock not found: $stockId"))
                                    val stockRow = stockRows.iterator().next()
                                    val lockedQty = stockDecimalValue(stockRow.getValue("stock_quantity"))
                                    val locked = stockDecimalValue(stockRow.getValue("stock_locked_quantity"))
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
                                            originalTotalCost = stockDecimalValue(stockRow.getValue("stock_total_cost")),
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

        return connection.preparedQuery(LOT_SQL).execute(Tuple.of(lotId))
            .compose { lotRows ->
                if (lotRows.size() == 0)
                    return@compose Future.failedFuture(ConflictException("lot $lotId not found"))
                val lotRow = lotRows.iterator().next()
                if (lotRow.getValue("lot_material_id")?.toString() != materialId)
                    return@compose Future.failedFuture(ConflictException("lot $lotId does not belong to material $materialId"))
                val expiry = lotRow.getValue("lot_expiry_date") as? java.time.LocalDate
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

            return connection.preparedQuery(INSERT_DETAIL_SQL).execute(
                Tuple.of(
                    detailId, opId, vi.materialId, vi.lotId, vi.quantity, vi.unit,
                    vi.unitCost, totalCost, now,
                )
            ).compose {
                val newQty = vi.originalQuantity.subtract(vi.quantity)
                val newCost = vi.originalTotalCost.subtract(totalCost).max(BigDecimal.ZERO)
                connection.preparedQuery(UPDATE_STOCK_SQL)
                    .execute(Tuple.of(newQty, newCost, now, vi.stockId))
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
            }.compose { process(index + 1) }
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
            .preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended(\$1, 0))")
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
        private const val INSERT_OPERATION_SQL =
            "INSERT INTO stock_operations " +
                "(id, order_no, operation_type, warehouse, status, metadata, confirmed_at, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val FIND_OPERATION_SQL =
            "SELECT id AS operation_id, order_no AS operation_order_no FROM stock_operations " +
                "WHERE operation_type = \$1 AND status = \$2 " +
                "AND metadata->>'source' = \$3 AND metadata->>'task_execution_id' = \$4"

        private const val FIND_DETAILS_SQL =
            "SELECT detail.id AS id, detail.material_id AS material_id, detail.lot_id AS lot_id, " +
                "detail.quantity AS quantity, detail.unit AS unit, detail.unit_cost AS unit_cost, " +
                "detail.total_cost AS total_cost, stock.id AS stock_id, operation.warehouse AS warehouse " +
                "FROM stock_operation_details detail " +
                "JOIN stock_operations operation ON operation.id = detail.operation_id " +
                "LEFT JOIN stocks stock ON stock.warehouse = operation.warehouse " +
                "AND stock.material_id = detail.material_id " +
                "AND (stock.lot_id = detail.lot_id OR (stock.lot_id IS NULL AND detail.lot_id IS NULL)) " +
                "WHERE detail.operation_id = \$1"

        private const val STOCK_PREVIEW_SQL =
            "SELECT warehouse AS stock_warehouse, material_id AS stock_material_id, " +
                "lot_id AS stock_lot_id, quantity AS stock_quantity, total_cost AS stock_total_cost " +
                "FROM stocks WHERE id = \$1"

        private const val MATERIAL_LOCK_SQL =
            "SELECT status AS material_status, base_unit AS material_base_unit, " +
                "quantity_scale AS material_quantity_scale, enable_batch_control AS material_batch_control " +
                "FROM materials WHERE id = \$1 FOR UPDATE"

        private const val LOT_SQL =
            "SELECT material_id AS lot_material_id, expiry_date AS lot_expiry_date FROM lots WHERE id = \$1"

        private const val STOCK_LOCK_SQL =
            "SELECT quantity AS stock_quantity, locked_quantity AS stock_locked_quantity, " +
                "total_cost AS stock_total_cost FROM stocks WHERE id = \$1 FOR UPDATE"

        private const val INSERT_DETAIL_SQL =
            "INSERT INTO stock_operation_details " +
                "(id, operation_id, material_id, lot_id, quantity, unit, unit_cost, total_cost, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val UPDATE_STOCK_SQL =
            "UPDATE stocks SET quantity = \$1, total_cost = \$2, last_updated = \$3 WHERE id = \$4"

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
