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
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 016 单一基础单位库存服务。
 *
 * 所有数量、锁定量、单位成本和总成本均直接按物资基础单位记账；物资单位从
 * `materials.base_unit` 读取并写入库存操作明细快照。不存在包装/拆零换算、双写
 * 或旧字段投影。所有写入复用调用方外层事务连接（药房/护理端口），自身事务仅用于
 * 手工入库与只读查询。
 */
class StockService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val log = LoggerFactory.getLogger(StockService::class.java)

    // ========================================================================
    //  手工确认入库
    // ========================================================================

    data class InboundItem(
        val materialId: String,
        val lotId: String?,
        /** 基础数量 */
        val quantity: BigDecimal,
        /** 每基础单位成本 */
        val unitCost: BigDecimal,
    )

    data class InboundCommand(
        val warehouse: String,
        val items: List<InboundItem>,
        val note: String?,
    )

    fun confirmInbound(command: InboundCommand): Future<JsonObject> {
        validateInbound(command)?.let { return Future.failedFuture(it) }
        val now = OffsetDateTime.now()
        val opId = Ulid.generate()
        val orderNo = "IN-$opId"

        return pool.withTransaction { connection ->
            val insertOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
                .set(StockOperations.STOCK_OPERATIONS.ID, opId)
                .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, orderNo)
                .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "INBOUND")
                .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.warehouse)
                .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
                .set(
                    StockOperations.STOCK_OPERATIONS.METADATA,
                    JSONB.valueOf(
                        JsonObject()
                            .put("source", "MANUAL_INBOUND")
                            .put("note", command.note)
                            .encode(),
                    ),
                )
                .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
                .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)

            connection.preparedQuery(DatabaseConfig.sql(insertOp))
                .execute(DatabaseConfig.tuple(insertOp))
                .compose { processInboundItems(connection, command, opId, now) }
                .compose { loadOperation(connection, opId) }
        }.map { result: JsonObject? -> result ?: throw IllegalStateException("inbound transaction returned no operation") }
    }

    private fun validateInbound(command: InboundCommand): IllegalArgumentException? = when {
        command.warehouse.isBlank() -> IllegalArgumentException("warehouse is required")
        command.items.isEmpty() -> IllegalArgumentException("at least one item is required")
        command.items.firstOrNull {
            it.materialId.isBlank() ||
                it.quantity <= BigDecimal.ZERO ||
                it.unitCost < BigDecimal.ZERO
        } != null ->
            IllegalArgumentException("invalid inbound item: material_id, positive quantity and non-negative unit_cost required")
        else -> null
    }

    private fun processInboundItems(
        connection: SqlConnection,
        command: InboundCommand,
        opId: String,
        now: OffsetDateTime,
    ): Future<List<String>> {
        val detailIds = mutableListOf<String>()
        val ordered = command.items.sortedWith(compareBy<InboundItem> { it.materialId }.thenBy { it.lotId == null }.thenBy { it.lotId })

        fun processSequentially(index: Int): Future<List<String>> {
            if (index >= ordered.size) return Future.succeededFuture(detailIds)
            val item = ordered[index]

            return loadMaterial(connection, item.materialId, forUpdate = true)
                .compose { material ->
                    validateQuantityAndCost(item.quantity, item.unitCost, material)
                    validateMaterialAndLot(connection, material, item.lotId)
                        .compose {
                            val detailId = Ulid.generate()
                            val totalCost = totalCostOf(item.quantity, item.unitCost)
                            val insertDetail = detailInsert(connection, opId, item.materialId, item.lotId, item.quantity, material.baseUnit, item.unitCost, totalCost, now, detailId)
                            connection.preparedQuery(DatabaseConfig.sql(insertDetail))
                                .execute(DatabaseConfig.tuple(insertDetail))
                                .compose { upsertStock(connection, command.warehouse, item.materialId, item.lotId, item.quantity, item.unitCost, now) }
                                .map {
                                    detailIds.add(detailId)
                                }
                        }
                }
                .compose { processSequentially(index + 1) }
        }
        return processSequentially(0)
    }

    private fun detailInsert(
        client: SqlClient,
        operationId: String,
        materialId: String,
        lotId: String?,
        quantity: BigDecimal,
        unit: String,
        unitCost: BigDecimal,
        totalCost: BigDecimal,
        now: OffsetDateTime,
        detailId: String = Ulid.generate(),
    ): org.jooq.InsertSetMoreStep<com.ovaphlow.crate.database.gen.inventories.public_.tables.records.StockOperationDetailsRecord> =
        ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, operationId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, materialId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, lotId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, quantity)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, unit)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, unitCost)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, totalCost)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)

    private fun upsertStock(
        client: SqlClient,
        warehouse: String,
        materialId: String,
        lotId: String?,
        addQuantity: BigDecimal,
        unitCost: BigDecimal,
        now: OffsetDateTime,
    ): Future<Void?> {
        val addTotalCost = totalCostOf(addQuantity, unitCost)
        return loadStock(client, warehouse, materialId, lotId, forUpdate = true)
            .compose { stock ->
                if (stock != null) {
                    val update = ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, stock.quantity.add(addQuantity))
                        .set(Stocks.STOCKS.TOTAL_COST, stock.totalCost.add(addTotalCost))
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                        .where(Stocks.STOCKS.ID.eq(stock.id))
            client.preparedQuery(DatabaseConfig.sql(update))
                        .execute(DatabaseConfig.tuple(update))
                        .map { null as Void? }
                } else {
                    val stockId = Ulid.generate()
                    val insert = ctx.insertInto(Stocks.STOCKS)
                        .set(Stocks.STOCKS.ID, stockId)
                        .set(Stocks.STOCKS.WAREHOUSE, warehouse)
                        .set(Stocks.STOCKS.MATERIAL_ID, materialId)
                        .set(Stocks.STOCKS.LOT_ID, lotId)
                        .set(Stocks.STOCKS.QUANTITY, addQuantity)
                        .set(Stocks.STOCKS.LOCKED_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.TOTAL_COST, addTotalCost)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                    client.preparedQuery(DatabaseConfig.sql(insert))
                        .execute(DatabaseConfig.tuple(insert))
                        .map { null as Void? }
                }
            }
    }

    // ========================================================================
    //  药房同连接端口：发药出库 / 退药回库
    // ========================================================================

    data class OutboundCommand(
        val warehouse: String,
        val materialId: String,
        val lotId: String?,
        /** 基础数量 */
        val quantity: BigDecimal,
        val note: String?,
    )

    data class OutboundResult(
        val stockOperationDetailId: String,
        val lotId: String?,
        val unitCost: BigDecimal,
    )

    data class ReturnInboundCommand(
        val warehouse: String,
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val unitCost: BigDecimal,
        val note: String?,
    )

    data class ReturnInboundResult(
        val stockOperationDetailId: String,
        val lotId: String?,
        val unitCost: BigDecimal,
    )

    private fun validateOutboundInput(command: OutboundCommand): IllegalArgumentException? = when {
        command.warehouse.isBlank() -> IllegalArgumentException("warehouse is required")
        command.materialId.isBlank() -> IllegalArgumentException("material_id is required")
        command.quantity <= BigDecimal.ZERO -> IllegalArgumentException("quantity must be positive")
        else -> null
    }

    /** 创建发药单前的只读校验：不锁定、不扣减、不写库存。 */
    fun validateOutbound(client: SqlClient, command: OutboundCommand): Future<Void?> {
        validateOutboundInput(command)?.let { return Future.failedFuture(it) }
        return loadStock(client, command.warehouse, command.materialId, command.lotId, forUpdate = false)
            .compose { stock ->
                if (stock == null) {
                    Future.failedFuture(
                        ConflictException("insufficient stock: no stock for material ${command.materialId} in warehouse ${command.warehouse}"),
                    )
                } else {
                    loadMaterial(client, command.materialId, forUpdate = false)
                        .compose { material ->
                            validateMaterialAndLot(client, material, stock.lotId)
                                .compose {
                                    val unitCost = avgUnitCost(stock)
                                    validateQuantityAndCost(command.quantity, unitCost, material)
                                    validateAvailable(stock, command.quantity)
                                }
                        }
                }
            }
    }

    /** 发药确认出库：调用方外层事务内锁定并扣减，任何失败整体回滚。 */
    fun confirmOutbound(client: SqlClient, command: OutboundCommand): Future<OutboundResult> {
        validateOutboundInput(command)?.let { return Future.failedFuture(it) }
        val now = OffsetDateTime.now()

        return loadStock(client, command.warehouse, command.materialId, command.lotId, forUpdate = false)
            .compose { preview ->
                if (preview == null) {
                    Future.failedFuture(
                        ConflictException("insufficient stock: no stock for material ${command.materialId} in warehouse ${command.warehouse}"),
                    )
                } else {
                    val unitCost = avgUnitCost(preview)
                    loadMaterial(client, command.materialId, forUpdate = true)
                        .compose { material ->
                            validateQuantityAndCost(command.quantity, unitCost, material)
                            validateMaterialAndLot(client, material, preview.lotId)
                                .compose {
                                    loadStock(client, command.warehouse, command.materialId, command.lotId, forUpdate = true)
                                }
                                .compose { stock ->
                                    if (stock == null) {
                                        Future.failedFuture(
                                            ConflictException("insufficient stock: no stock for material ${command.materialId} in warehouse ${command.warehouse}"),
                                        )
                                    } else {
                                        validateAvailable(stock, command.quantity)
                                            .compose { writeOutbound(client, command, stock, material, unitCost, now) }
                                    }
                                }
                        }
                }
            }
    }

    private fun writeOutbound(
        client: SqlClient,
        command: OutboundCommand,
        stock: StockRow,
        material: MaterialSnapshot,
        unitCost: BigDecimal,
        now: OffsetDateTime,
    ): Future<OutboundResult> {
        val opId = Ulid.generate()
        val detailId = Ulid.generate()
        val totalCost = totalCostOf(command.quantity, unitCost)
        val metadata = JsonObject()
            .put("source", "PHARMACY_DISPENSE")
            .put("note", command.note)
        val insertOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
            .set(StockOperations.STOCK_OPERATIONS.ID, opId)
            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, "PH-$opId")
            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "OUTBOUND")
            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.warehouse)
            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
            .set(StockOperations.STOCK_OPERATIONS.METADATA, JSONB.valueOf(metadata.encode()))
            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)
        val insertDetail = detailInsert(client, opId, command.materialId, stock.lotId, command.quantity, material.baseUnit, unitCost, totalCost, now, detailId)
        val updateStock = ctx.update(Stocks.STOCKS)
            .set(Stocks.STOCKS.QUANTITY, stock.quantity.subtract(command.quantity))
            .set(Stocks.STOCKS.TOTAL_COST, stock.totalCost.subtract(totalCost).max(BigDecimal.ZERO))
            .set(Stocks.STOCKS.LAST_UPDATED, now)
            .where(Stocks.STOCKS.ID.eq(stock.id))

        return client.preparedQuery(DatabaseConfig.sql(insertOp))
            .execute(DatabaseConfig.tuple(insertOp))
            .compose { client.preparedQuery(DatabaseConfig.sql(insertDetail)).execute(DatabaseConfig.tuple(insertDetail)) }
            .compose { client.preparedQuery(DatabaseConfig.sql(updateStock)).execute(DatabaseConfig.tuple(updateStock)) }
            .map { OutboundResult(stockOperationDetailId = detailId, lotId = stock.lotId, unitCost = unitCost) }
    }

    /** 退药回库：物资、批次和单位成本由药房从原发药明细推导；本方法不启动新事务。 */
    fun confirmReturnInbound(client: SqlClient, command: ReturnInboundCommand): Future<ReturnInboundResult> {
        if (command.warehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        if (command.materialId.isBlank())
            return Future.failedFuture(IllegalArgumentException("material_id is required"))
        if (command.quantity <= BigDecimal.ZERO)
            return Future.failedFuture(IllegalArgumentException("quantity must be positive"))
        if (command.unitCost < BigDecimal.ZERO)
            return Future.failedFuture(IllegalArgumentException("unit_cost must not be negative"))

        val now = OffsetDateTime.now()
        return loadMaterial(client, command.materialId, forUpdate = true)
            .compose { material ->
                validateQuantityAndCost(command.quantity, command.unitCost, material)
                validateMaterialAndLot(client, material, command.lotId)
                    .compose {
                        val opId = Ulid.generate()
                        val detailId = Ulid.generate()
                        val totalCost = totalCostOf(command.quantity, command.unitCost)
                        val insertOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
                            .set(StockOperations.STOCK_OPERATIONS.ID, opId)
                            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, "PH-RETURN-$opId")
                            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "INBOUND")
                            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.warehouse)
                            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
                            .set(
                                StockOperations.STOCK_OPERATIONS.METADATA,
                                JSONB.valueOf(
                                    JsonObject()
                                        .put("source", "PHARMACY_RETURN")
                                        .put("note", command.note)
                                        .encode(),
                                ),
                            )
                            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
                            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)
                        val insertDetail = detailInsert(client, opId, command.materialId, command.lotId, command.quantity, material.baseUnit, command.unitCost, totalCost, now, detailId)

                        client.preparedQuery(DatabaseConfig.sql(insertOp))
                            .execute(DatabaseConfig.tuple(insertOp))
                            .compose { client.preparedQuery(DatabaseConfig.sql(insertDetail)).execute(DatabaseConfig.tuple(insertDetail)) }
                            .compose { upsertStock(client, command.warehouse, command.materialId, command.lotId, command.quantity, command.unitCost, now) }
                            .map { ReturnInboundResult(detailId, command.lotId, command.unitCost) }
                    }
            }
    }

    // ========================================================================
    //  013 护理站申领：预留、释放与整单双仓调拨
    // ========================================================================

    data class RequisitionReserveCommand(
        val warehouse: String,
        val items: List<RequisitionReserveItem>,
    )

    data class RequisitionReserveItem(
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
    )

    data class RequisitionReleaseCommand(
        val warehouse: String,
        val items: List<RequisitionReleaseItem>,
    )

    data class RequisitionReleaseItem(
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
    )

    data class RequisitionTransferCommand(
        val sourceWarehouse: String,
        val destinationWarehouse: String,
        val requisitionId: String,
        val requisitionNo: String,
        val dispensedBy: String,
        val items: List<RequisitionTransferItem>,
    )

    data class RequisitionTransferItem(
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
    )

    data class RequisitionTransferItemResult(
        val materialId: String,
        val lotId: String?,
        val outboundStockOperationDetailId: String,
        val inboundStockOperationDetailId: String,
        val unitCost: BigDecimal,
    )

    data class RequisitionTransferResult(
        val outboundOperationId: String,
        val inboundOperationId: String,
        val items: List<RequisitionTransferItemResult>,
    )

    fun validateRequisitionMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> {
        if (materialIds.isEmpty()) return Future.succeededFuture(null)
        val query = ctx.select(Materials.MATERIALS.ID)
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.`in`(materialIds).and(Materials.MATERIALS.STATUS.eq("ACTIVE")))
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows: RowSet<Row> ->
                val found = rows.mapNotNull { it.getValue(0)?.toString() }.toSet()
                if (found.size != materialIds.size) {
                    val missing = materialIds.filter { it !in found }
                    throw ConflictException("materials not found or not ACTIVE: ${missing.joinToString()}")
                }
                null as Void?
            }
    }

    fun reserveStock(client: SqlClient, command: RequisitionReserveCommand): Future<Void?> {
        validateReserveInput(command)?.let { return Future.failedFuture(it) }
        return reserveOne(client, command.warehouse, stableOrder(command.items), 0)
    }

    private fun reserveOne(
        client: SqlClient,
        warehouse: String,
        ordered: List<RequisitionReserveItem>,
        index: Int,
    ): Future<Void?> {
        if (index >= ordered.size) return Future.succeededFuture(null)
        val item = ordered[index]
        return loadStock(client, warehouse, item.materialId, item.lotId, forUpdate = false)
            .compose { preview ->
                if (preview == null) {
                    Future.failedFuture(
                        ConflictException("insufficient stock: no stock for material ${item.materialId} in warehouse $warehouse"),
                    )
                } else {
                    val unitCost = avgUnitCost(preview)
                    loadMaterial(client, item.materialId, forUpdate = true)
                        .compose { material ->
                            validateQuantityAndCost(item.quantity, unitCost, material)
                            validateMaterialAndLot(client, material, preview.lotId)
                                .compose {
                                    loadStock(client, warehouse, item.materialId, item.lotId, forUpdate = true)
                                }
                                .compose { stock ->
                                    if (stock == null) {
                                        Future.failedFuture(
                                            ConflictException("insufficient stock: no stock for material ${item.materialId} in warehouse $warehouse"),
                                        )
                                    } else {
                                        validateAvailable(stock, item.quantity)
                                            .compose {
                                                val update = ctx.update(Stocks.STOCKS)
                                                    .set(Stocks.STOCKS.LOCKED_QUANTITY, stock.lockedQuantity.add(item.quantity))
                                                    .set(Stocks.STOCKS.LAST_UPDATED, OffsetDateTime.now())
                                                    .where(Stocks.STOCKS.ID.eq(stock.id))
                                                client.preparedQuery(DatabaseConfig.sql(update))
                                                    .execute(DatabaseConfig.tuple(update))
                                                    .map { null as Void? }
                                            }
                                    }
                                }
                        }
                }
            }
            .compose { reserveOne(client, warehouse, ordered, index + 1) }
    }

    fun releaseReservation(client: SqlClient, command: RequisitionReleaseCommand): Future<Void?> {
        if (command.warehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one item is required"))
        command.items.firstOrNull { it.materialId.isBlank() || it.quantity <= BigDecimal.ZERO }?.let {
            return Future.failedFuture(IllegalArgumentException("invalid release item: material_id and positive quantity required"))
        }
        val ordered = stableOrder(command.items.map { RequisitionReserveItem(it.materialId, it.lotId, it.quantity) })
        return releaseOne(client, command.warehouse, ordered, 0)
    }

    private fun releaseOne(
        client: SqlClient,
        warehouse: String,
        ordered: List<RequisitionReserveItem>,
        index: Int,
    ): Future<Void?> {
        if (index >= ordered.size) return Future.succeededFuture(null)
        val item = ordered[index]
        return loadMaterial(client, item.materialId, forUpdate = true)
            .compose { material ->
                validateQuantityAndCost(item.quantity, BigDecimal.ZERO, material)
                loadStock(client, warehouse, item.materialId, item.lotId, forUpdate = true)
                    .compose { stock ->
                        if (stock == null) {
                            Future.failedFuture(
                                ConflictException("reservation corrupted: no stock for material ${item.materialId} in warehouse $warehouse"),
                            )
                        } else if (stock.lockedQuantity < item.quantity) {
                            Future.failedFuture(
                                ConflictException("reservation corrupted: ${stock.lockedQuantity} locked, releasing ${item.quantity}"),
                            )
                        } else {
                            val update = ctx.update(Stocks.STOCKS)
                                .set(Stocks.STOCKS.LOCKED_QUANTITY, stock.lockedQuantity.subtract(item.quantity))
                                .set(Stocks.STOCKS.LAST_UPDATED, OffsetDateTime.now())
                                .where(Stocks.STOCKS.ID.eq(stock.id))
                            client.preparedQuery(DatabaseConfig.sql(update))
                                .execute(DatabaseConfig.tuple(update))
                                .map { null as Void? }
                        }
                    }
            }
            .compose { releaseOne(client, warehouse, ordered, index + 1) }
    }

    fun confirmReservedTransfer(client: SqlClient, command: RequisitionTransferCommand): Future<RequisitionTransferResult> {
        if (command.sourceWarehouse.isBlank() || command.destinationWarehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("source_warehouse and destination_warehouse are required"))
        if (command.sourceWarehouse == command.destinationWarehouse)
            return Future.failedFuture(ConflictException("source warehouse must differ from destination warehouse"))
        if (command.requisitionId.isBlank())
            return Future.failedFuture(IllegalArgumentException("requisition_id is required"))
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one transfer item is required"))
        command.items.firstOrNull { it.materialId.isBlank() || it.quantity <= BigDecimal.ZERO }?.let {
            return Future.failedFuture(IllegalArgumentException("invalid transfer item: material_id and positive quantity required"))
        }
        val ordered = stableOrder(command.items.map { RequisitionReserveItem(it.materialId, it.lotId, it.quantity) })
        return resolveTransferItems(client, command.sourceWarehouse, ordered, 0, emptyList())
            .compose { resolved ->
                lockAllSources(client, command.sourceWarehouse, ordered, resolved, 0, emptyList())
                    .compose { sources ->
                        prepareTargets(client, command.destinationWarehouse, ordered, 0, emptyList())
                            .compose { targets ->
                                writeTransfer(client, command, ordered, sources, targets, resolved, OffsetDateTime.now())
                            }
                    }
            }
    }

    private data class ResolvedTransferItem(
        val material: MaterialSnapshot,
        val unitCost: BigDecimal,
    )

    private fun resolveTransferItems(
        client: SqlClient,
        sourceWarehouse: String,
        ordered: List<RequisitionReserveItem>,
        index: Int,
        acc: List<ResolvedTransferItem>,
    ): Future<List<ResolvedTransferItem>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        return loadStock(client, sourceWarehouse, item.materialId, item.lotId, forUpdate = false)
            .compose { preview ->
                if (preview == null) {
                    Future.failedFuture(
                        ConflictException("insufficient stock: no stock for material ${item.materialId} in warehouse $sourceWarehouse"),
                    )
                } else {
                    val unitCost = avgUnitCost(preview)
                    loadMaterial(client, item.materialId, forUpdate = true)
                        .compose { material ->
                            validateQuantityAndCost(item.quantity, unitCost, material)
                            validateMaterialAndLot(client, material, preview.lotId)
                                .compose {
                                    resolveTransferItems(client, sourceWarehouse, ordered, index + 1, acc + ResolvedTransferItem(material, unitCost))
                                }
                        }
                }
            }
    }

    private fun lockAllSources(
        client: SqlClient,
        sourceWarehouse: String,
        ordered: List<RequisitionReserveItem>,
        resolved: List<ResolvedTransferItem>,
        index: Int,
        acc: List<StockRow>,
    ): Future<List<StockRow>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        return loadStock(client, sourceWarehouse, item.materialId, item.lotId, forUpdate = true)
            .compose { stock ->
                if (stock == null) {
                    Future.failedFuture(
                        ConflictException("insufficient stock: no stock for material ${item.materialId} in warehouse $sourceWarehouse"),
                    )
                } else if (stock.lockedQuantity < item.quantity) {
                    Future.failedFuture(
                        ConflictException("insufficient reservation: only ${stock.lockedQuantity} locked, required ${item.quantity}"),
                    )
                } else {
                    lockAllSources(client, sourceWarehouse, ordered, resolved, index + 1, acc + stock)
                }
            }
    }

    private fun prepareTargets(
        client: SqlClient,
        destinationWarehouse: String,
        ordered: List<RequisitionReserveItem>,
        index: Int,
        acc: List<Pair<StockRow, RequisitionReserveItem>>,
    ): Future<List<Pair<StockRow, RequisitionReserveItem>>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        return ensureStock(client, destinationWarehouse, item.materialId, item.lotId, OffsetDateTime.now())
            .compose { target ->
                prepareTargets(client, destinationWarehouse, ordered, index + 1, acc + Pair(target, item))
            }
    }

    private fun writeTransfer(
        client: SqlClient,
        command: RequisitionTransferCommand,
        ordered: List<RequisitionReserveItem>,
        sources: List<StockRow>,
        targets: List<Pair<StockRow, RequisitionReserveItem>>,
        resolved: List<ResolvedTransferItem>,
        now: OffsetDateTime,
    ): Future<RequisitionTransferResult> {
        val outboundOpId = Ulid.generate()
        val inboundOpId = Ulid.generate()
        val itemResults = mutableListOf<RequisitionTransferItemResult>()

        val outboundMetadata = JSONB.valueOf(
            JsonObject()
                .put("source", "PHARMACY_REQUISITION_TRANSFER")
                .put("requisition_id", command.requisitionId)
                .put("requisition_no", command.requisitionNo)
                .put("destination_warehouse", command.destinationWarehouse)
                .put("dispensed_by", command.dispensedBy)
                .encode(),
        )
        val inboundMetadata = JSONB.valueOf(
            JsonObject()
                .put("source", "PHARMACY_REQUISITION_TRANSFER")
                .put("requisition_id", command.requisitionId)
                .put("requisition_no", command.requisitionNo)
                .put("source_warehouse", command.sourceWarehouse)
                .put("dispensed_by", command.dispensedBy)
                .encode(),
        )
        val insertOutboundOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
            .set(StockOperations.STOCK_OPERATIONS.ID, outboundOpId)
            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, "PH-REQ-$outboundOpId")
            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "OUTBOUND")
            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.sourceWarehouse)
            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
            .set(StockOperations.STOCK_OPERATIONS.METADATA, outboundMetadata)
            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)
        val insertInboundOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
            .set(StockOperations.STOCK_OPERATIONS.ID, inboundOpId)
            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, "PH-REQ-$inboundOpId")
            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "INBOUND")
            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.destinationWarehouse)
            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
            .set(StockOperations.STOCK_OPERATIONS.METADATA, inboundMetadata)
            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)

        fun writeItems(index: Int): Future<List<RequisitionTransferItemResult>> {
            if (index >= ordered.size) return Future.succeededFuture(itemResults)
            val item = ordered[index]
            val resolvedItem = resolved[index]
            val totalCost = totalCostOf(item.quantity, resolvedItem.unitCost)
            val outboundDetailId = Ulid.generate()
            val inboundDetailId = Ulid.generate()
            val outboundDetail = detailInsert(client, outboundOpId, item.materialId, item.lotId, item.quantity, resolvedItem.material.baseUnit, resolvedItem.unitCost, totalCost, now, outboundDetailId)
            val inboundDetail = detailInsert(client, inboundOpId, item.materialId, item.lotId, item.quantity, resolvedItem.material.baseUnit, resolvedItem.unitCost, totalCost, now, inboundDetailId)
            return client.preparedQuery(DatabaseConfig.sql(outboundDetail))
                .execute(DatabaseConfig.tuple(outboundDetail))
                .compose { _: RowSet<Row> ->
                    client.preparedQuery(DatabaseConfig.sql(inboundDetail))
                        .execute(DatabaseConfig.tuple(inboundDetail))
                }
                .map { _: RowSet<Row> ->
                    itemResults.add(
                        RequisitionTransferItemResult(
                            materialId = item.materialId,
                            lotId = item.lotId,
                            outboundStockOperationDetailId = outboundDetailId,
                            inboundStockOperationDetailId = inboundDetailId,
                            unitCost = resolvedItem.unitCost,
                        ),
                    )
                }
                .compose { writeItems(index + 1) }
        }

        fun updateSources(index: Int): Future<Void?> {
            if (index >= ordered.size) return Future.succeededFuture(null)
            val item = ordered[index]
            val source = sources[index]
            val resolvedItem = resolved[index]
            val totalCost = totalCostOf(item.quantity, resolvedItem.unitCost)
            val update = ctx.update(Stocks.STOCKS)
                .set(Stocks.STOCKS.QUANTITY, source.quantity.subtract(item.quantity))
                .set(Stocks.STOCKS.LOCKED_QUANTITY, source.lockedQuantity.subtract(item.quantity))
                .set(Stocks.STOCKS.TOTAL_COST, source.totalCost.subtract(totalCost).max(BigDecimal.ZERO))
                .set(Stocks.STOCKS.LAST_UPDATED, now)
                .where(Stocks.STOCKS.ID.eq(source.id))
            return client.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { updateSources(index + 1) }
        }

        fun updateTargets(index: Int): Future<Void?> {
            if (index >= ordered.size) return Future.succeededFuture(null)
            val item = ordered[index]
            val target = targets[index].first
            val resolvedItem = resolved[index]
            val totalCost = totalCostOf(item.quantity, resolvedItem.unitCost)
            val update = ctx.update(Stocks.STOCKS)
                .set(Stocks.STOCKS.QUANTITY, target.quantity.add(item.quantity))
                .set(Stocks.STOCKS.TOTAL_COST, target.totalCost.add(totalCost))
                .set(Stocks.STOCKS.LAST_UPDATED, now)
                .where(Stocks.STOCKS.ID.eq(target.id))
            return client.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { updateTargets(index + 1) }
        }

        return client.preparedQuery(DatabaseConfig.sql(insertOutboundOp))
            .execute(DatabaseConfig.tuple(insertOutboundOp))
            .compose { _: RowSet<Row> -> client.preparedQuery(DatabaseConfig.sql(insertInboundOp)).execute(DatabaseConfig.tuple(insertInboundOp)) }
            .compose { _: RowSet<Row> -> writeItems(0) }
            .compose { items: List<RequisitionTransferItemResult> -> updateSources(0).map { items } }
            .compose { items: List<RequisitionTransferItemResult> -> updateTargets(0).map { items } }
            .map { _: List<RequisitionTransferItemResult> ->
                RequisitionTransferResult(
                    outboundOperationId = outboundOpId,
                    inboundOperationId = inboundOpId,
                    items = itemResults,
                )
            }
    }

    private fun validateReserveInput(command: RequisitionReserveCommand): IllegalArgumentException? = when {
        command.warehouse.isBlank() -> IllegalArgumentException("warehouse is required")
        command.items.isEmpty() -> IllegalArgumentException("at least one item is required")
        command.items.firstOrNull { it.materialId.isBlank() || it.quantity <= BigDecimal.ZERO } != null ->
            IllegalArgumentException("invalid reserve item: material_id and positive quantity required")
        else -> null
    }

    private fun stableOrder(items: List<RequisitionReserveItem>): List<RequisitionReserveItem> =
        items.sortedWith(
            compareBy<RequisitionReserveItem> { it.materialId }
                .thenBy { it.lotId == null }
                .thenBy { it.lotId },
        )

    // ========================================================================
    //  014 药房采购收货
    // ========================================================================

    data class PurchaseReceiptItem(
        val receiptItemId: String,
        val materialId: String,
        val batchNo: String?,
        val productionDate: LocalDate?,
        val expiryDate: LocalDate?,
        val manufacturer: String?,
        val quantity: BigDecimal,
        val unitCost: BigDecimal,
    )

    data class PurchaseReceiptCommand(
        val warehouse: String,
        val supplierName: String,
        val purchaseOrderId: String,
        val purchaseOrderNo: String,
        val purchaseReceiptId: String,
        val receiptNo: String,
        val receivedBy: String,
        val items: List<PurchaseReceiptItem>,
    )

    data class PurchaseReceiptItemResult(
        val receiptItemId: String,
        val materialId: String,
        val batchNo: String?,
        val lotId: String?,
        val stockOperationDetailId: String,
        val unitCost: BigDecimal,
        val totalCost: BigDecimal,
    )

    data class PurchaseReceiptResult(
        val stockOperationId: String,
        val items: List<PurchaseReceiptItemResult>,
    )

    private data class PurchaseResolvedItem(
        val item: PurchaseReceiptItem,
        val material: MaterialSnapshot,
        val lotId: String?,
        val stock: StockRow,
    )

    fun validatePurchaseMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> =
        validateRequisitionMaterials(client, materialIds)

    fun confirmPurchaseReceipt(client: SqlClient, command: PurchaseReceiptCommand): Future<PurchaseReceiptResult> {
        validatePurchaseReceiptInput(command)?.let { return Future.failedFuture(it) }
        val ordered = purchaseStableOrder(command.items)
        return resolveAll(client, command, ordered, 0, emptyList())
            .compose { resolved: List<PurchaseResolvedItem> ->
                writePurchaseReceipt(client, command, resolved, OffsetDateTime.now())
            }
    }

    private fun validatePurchaseReceiptInput(command: PurchaseReceiptCommand): IllegalArgumentException? = when {
        command.warehouse.isBlank() -> IllegalArgumentException("warehouse is required")
        command.supplierName.isBlank() -> IllegalArgumentException("supplier_name is required")
        command.purchaseOrderId.isBlank() -> IllegalArgumentException("purchase_order_id is required")
        command.purchaseOrderNo.isBlank() -> IllegalArgumentException("purchase_order_no is required")
        command.purchaseReceiptId.isBlank() -> IllegalArgumentException("purchase_receipt_id is required")
        command.receiptNo.isBlank() -> IllegalArgumentException("receipt_no is required")
        command.receivedBy.isBlank() -> IllegalArgumentException("received_by is required")
        command.items.isEmpty() -> IllegalArgumentException("at least one receipt item is required")
        command.items.firstOrNull {
            it.receiptItemId.isBlank() || it.materialId.isBlank() ||
                it.quantity <= BigDecimal.ZERO || it.unitCost < BigDecimal.ZERO
        } != null ->
            IllegalArgumentException("invalid receipt item: receipt_item_id, material_id, positive quantity and non-negative unit_cost required")
        else -> null
    }

    private fun purchaseStableOrder(items: List<PurchaseReceiptItem>): List<PurchaseReceiptItem> =
        items.sortedWith(
            compareBy<PurchaseReceiptItem> { it.materialId }
                .thenBy { it.batchNo == null }
                .thenBy { it.batchNo },
        )

    private fun resolveAll(
        client: SqlClient,
        command: PurchaseReceiptCommand,
        ordered: List<PurchaseReceiptItem>,
        index: Int,
        acc: List<PurchaseResolvedItem>,
    ): Future<List<PurchaseResolvedItem>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        return resolvePurchaseItem(client, command, item)
            .compose { resolved ->
                resolveAll(client, command, ordered, index + 1, acc + resolved)
            }
    }

    private fun resolvePurchaseItem(
        client: SqlClient,
        command: PurchaseReceiptCommand,
        item: PurchaseReceiptItem,
    ): Future<PurchaseResolvedItem> {
        return loadMaterial(client, item.materialId, forUpdate = true)
            .compose { material ->
                validateQuantityAndCost(item.quantity, item.unitCost, material)
                val now = OffsetDateTime.now()
                val lotFuture: Future<String?> = if (material.batchControl) {
                    if (item.batchNo.isNullOrBlank())
                        throw ConflictException("material ${item.materialId} requires a batch_no")
                    if (item.expiryDate == null || !item.expiryDate.isAfter(LocalDate.now()))
                        throw ConflictException("material ${item.materialId} requires expiry_date later than today")
                    ensureLot(client, item.materialId, item.batchNo, item.productionDate, item.expiryDate, item.manufacturer, command.supplierName)
                        .map { it as String? }
                } else {
                    if (item.batchNo != null || item.productionDate != null || item.expiryDate != null || item.manufacturer != null)
                        throw ConflictException("material ${item.materialId} does not use batch control")
                    Future.succeededFuture(null)
                }
                lotFuture.compose { lotId ->
                    ensureStock(client, command.warehouse, item.materialId, lotId, now)
                        .map { stock ->
                            PurchaseResolvedItem(item = item, material = material, lotId = lotId, stock = stock)
                        }
                }
            }
    }

    private fun ensureLot(
        client: SqlClient,
        materialId: String,
        batchNo: String,
        productionDate: LocalDate?,
        expiryDate: LocalDate?,
        manufacturer: String?,
        supplier: String,
    ): Future<String> {
        val findQuery = ctx.select(
            Lots.LOTS.ID.`as`("lot_id"),
            Lots.LOTS.PRODUCTION_DATE.`as`("lot_production_date"),
            Lots.LOTS.EXPIRY_DATE.`as`("lot_expiry_date"),
            Lots.LOTS.MANUFACTURER.`as`("lot_manufacturer"),
            Lots.LOTS.SUPPLIER.`as`("lot_supplier"),
        )
            .from(Lots.LOTS)
            .where(Lots.LOTS.MATERIAL_ID.eq(materialId).and(Lots.LOTS.BATCH_NO.eq(batchNo)))

        fun verify(row: Row): Future<String> {
            val lotId = row.getValue(0)?.toString() ?: ""
            val existingProduction = row.getValue(1) as? LocalDate
            val existingExpiry = row.getValue(2) as? LocalDate
            val existingManufacturer = row.getValue(3)?.toString()
            val existingSupplier = row.getValue(4)?.toString()
            if (existingExpiry != null && existingExpiry.isBefore(LocalDate.now()))
                throw ConflictException("lot $batchNo for material $materialId has expired on $existingExpiry")
            if (productionDate != null && existingProduction != null && productionDate != existingProduction)
                throw ConflictException("lot $batchNo for material $materialId has conflicting production_date $existingProduction")
            if (expiryDate != null && existingExpiry != null && expiryDate != existingExpiry)
                throw ConflictException("lot $batchNo for material $materialId has conflicting expiry_date $existingExpiry")
            if (manufacturer != null && existingManufacturer != null && manufacturer != existingManufacturer)
                throw ConflictException("lot $batchNo for material $materialId has conflicting manufacturer $existingManufacturer")
            if (supplier != null && existingSupplier != null && supplier != existingSupplier)
                throw ConflictException("lot $batchNo for material $materialId has conflicting supplier $existingSupplier")
            return Future.succeededFuture(lotId)
        }

        fun readAfterConflict(): Future<String> =
            client.preparedQuery(DatabaseConfig.sql(findQuery))
                .execute(DatabaseConfig.tuple(findQuery))
                .compose { rows: RowSet<Row> ->
                    if (rows.size() == 0) {
                        Future.failedFuture(
                            ConflictException("lot $batchNo vanished during create for material $materialId"),
                        )
                    } else {
                        verify(rows.iterator().next())
                    }
                }

        return client.preparedQuery(DatabaseConfig.sql(findQuery))
            .execute(DatabaseConfig.tuple(findQuery))
            .compose { rows: RowSet<Row> ->
                if (rows.size() > 0) {
                    verify(rows.iterator().next())
                } else {
                    val lotId = Ulid.generate()
                    val insertQ = ctx.insertInto(Lots.LOTS)
                        .set(Lots.LOTS.ID, lotId)
                        .set(Lots.LOTS.MATERIAL_ID, materialId)
                        .set(Lots.LOTS.BATCH_NO, batchNo)
                        .set(Lots.LOTS.PRODUCTION_DATE, productionDate)
                        .set(Lots.LOTS.EXPIRY_DATE, expiryDate)
                        .set(Lots.LOTS.MANUFACTURER, manufacturer)
                        .set(Lots.LOTS.SUPPLIER, supplier)
                    client.preparedQuery(DatabaseConfig.sql(insertQ))
                        .execute(DatabaseConfig.tuple(insertQ))
                        .map { lotId }
                        .recover { error: Throwable ->
                            if (error is PgException && error.sqlState == "23505") readAfterConflict()
                            else Future.failedFuture(error)
                        }
                }
            }
    }

    private fun writePurchaseReceipt(
        client: SqlClient,
        command: PurchaseReceiptCommand,
        resolved: List<PurchaseResolvedItem>,
        now: OffsetDateTime,
    ): Future<PurchaseReceiptResult> {
        val opId = Ulid.generate()
        val metadata = JSONB.valueOf(
            JsonObject()
                .put("source", "PHARMACY_PURCHASE_RECEIPT")
                .put("purchase_order_id", command.purchaseOrderId)
                .put("purchase_order_no", command.purchaseOrderNo)
                .put("purchase_receipt_id", command.purchaseReceiptId)
                .put("receipt_no", command.receiptNo)
                .put("supplier_name", command.supplierName)
                .put("warehouse", command.warehouse)
                .put("received_by", command.receivedBy)
                .encode(),
        )
        val insertOp = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
            .set(StockOperations.STOCK_OPERATIONS.ID, opId)
            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, "PH-PO-$opId")
            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "INBOUND")
            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.warehouse)
            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
            .set(StockOperations.STOCK_OPERATIONS.METADATA, metadata)
            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)

        val itemResults = mutableListOf<PurchaseReceiptItemResult>()

        fun writeOne(index: Int): Future<Void?> {
            if (index >= resolved.size) return Future.succeededFuture(null)
            val entry = resolved[index]
            val item = entry.item
            val detailId = Ulid.generate()
            val totalCost = totalCostOf(item.quantity, item.unitCost)
            val insertDetail = detailInsert(client, opId, item.materialId, entry.lotId, item.quantity, entry.material.baseUnit, item.unitCost, totalCost, now, detailId)
            val updateStock = ctx.update(Stocks.STOCKS)
                .set(Stocks.STOCKS.QUANTITY, entry.stock.quantity.add(item.quantity))
                .set(Stocks.STOCKS.TOTAL_COST, entry.stock.totalCost.add(totalCost))
                .set(Stocks.STOCKS.LAST_UPDATED, now)
                .where(Stocks.STOCKS.ID.eq(entry.stock.id))
            return client.preparedQuery(DatabaseConfig.sql(insertDetail))
                .execute(DatabaseConfig.tuple(insertDetail))
                .compose { _: RowSet<Row> ->
                    client.preparedQuery(DatabaseConfig.sql(updateStock))
                        .execute(DatabaseConfig.tuple(updateStock))
                        .map { _: RowSet<Row> ->
                            itemResults.add(
                                PurchaseReceiptItemResult(
                                    receiptItemId = item.receiptItemId,
                                    materialId = item.materialId,
                                    batchNo = item.batchNo,
                                    lotId = entry.lotId,
                                    stockOperationDetailId = detailId,
                                    unitCost = item.unitCost,
                                    totalCost = totalCost,
                                ),
                            )
                            null as Void?
                        }
                }
                .compose { _: Void? -> writeOne(index + 1) }
        }

        return client.preparedQuery(DatabaseConfig.sql(insertOp))
            .execute(DatabaseConfig.tuple(insertOp))
            .compose { _: RowSet<Row> -> writeOne(0) }
            .map { _: Void? ->
                PurchaseReceiptResult(stockOperationId = opId, items = itemResults.toList())
            }
    }

    // ========================================================================
    //  只读查询
    // ========================================================================

    fun listAvailableStocks(
        warehouse: String? = null,
        materialId: String? = null,
        search: String? = null,
        limit: Int = 50,
        offset: Int = 0,
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
                    DSL.field("l.batch_no").like("%$search%"),
                ),
            )
        }
        conditions.add(
            DSL.or(
                DSL.field("l.expiry_date").isNull,
                DSL.field("l.expiry_date").ge(LocalDate.now()),
            ),
        )

        val columns = listOf(
            s.ID, s.WAREHOUSE, s.MATERIAL_ID, s.LOT_ID, s.QUANTITY, s.LOCKED_QUANTITY, s.TOTAL_COST, s.LAST_UPDATED,
            DSL.field("m.code").`as`("material_code"),
            DSL.field("m.name").`as`("material_name"),
            DSL.field("m.category").`as`("material_category"),
            DSL.field("m.base_unit").`as`("unit"),
            DSL.field("l.batch_no").`as`("batch_no"),
            DSL.field("l.expiry_date").`as`("expiry_date"),
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
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun listAvailableWarehouses(): Future<List<String>> {
        val s = Stocks.STOCKS.`as`("s")
        val m = Materials.MATERIALS.`as`("m")
        val l = Lots.LOTS.`as`("l")
        val query = ctx.selectDistinct(s.WAREHOUSE)
            .from(s)
            .join(m).on(s.MATERIAL_ID.eq(m.ID))
            .leftJoin(l).on(s.LOT_ID.eq(l.ID))
            .where(s.QUANTITY.gt(s.LOCKED_QUANTITY))
            .and(m.STATUS.eq("ACTIVE"))
            .and(DSL.or(l.EXPIRY_DATE.isNull, l.EXPIRY_DATE.ge(LocalDate.now())))
            .orderBy(s.WAREHOUSE.asc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val warehouses = mutableListOf<String>()
                for (row in rows) row.getValue("warehouse")?.toString()?.let { warehouses.add(it) }
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

    // ========================================================================
    //  内部共享
    // ========================================================================

    private data class MaterialSnapshot(
        val id: String,
        val status: String,
        val baseUnit: String,
        val quantityScale: Int,
        val batchControl: Boolean,
    )

    private data class StockRow(
        val id: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val lockedQuantity: BigDecimal,
        val totalCost: BigDecimal,
    )

    private fun loadMaterial(
        client: SqlClient,
        materialId: String,
        forUpdate: Boolean,
    ): Future<MaterialSnapshot> {
        val query = ctx.select(
            Materials.MATERIALS.STATUS.`as`("material_status"),
            Materials.MATERIALS.BASE_UNIT.`as`("material_base_unit"),
            Materials.MATERIALS.QUANTITY_SCALE.`as`("material_quantity_scale"),
            Materials.MATERIALS.ENABLE_BATCH_CONTROL.`as`("material_batch_control"),
        )
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.eq(materialId))
        val q = if (forUpdate) query.forUpdate() else query
        return client.preparedQuery(DatabaseConfig.sql(q))
            .execute(DatabaseConfig.tuple(q))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("material not found: $materialId"))
                } else {
                    val row = rows.iterator().next()
                    val status = row.getValue(0)?.toString() ?: ""
                    if (status != "ACTIVE") {
                        Future.failedFuture(ConflictException("material $materialId is not ACTIVE"))
                    } else {
                        val baseUnit = row.getValue(1)?.toString()
                        if (baseUnit.isNullOrBlank()) {
                            Future.failedFuture(ConflictException("material $materialId has no base unit"))
                        } else {
                            Future.succeededFuture(
                                MaterialSnapshot(
                                    id = materialId,
                                    status = status,
                                    baseUnit = baseUnit,
                                    quantityScale = (row.getValue(2) as? Number)?.toInt() ?: 0,
                                    batchControl = row.getValue(3) as? Boolean ?: false,
                                ),
                            )
                        }
                    }
                }
            }
    }

    private fun validateMaterialAndLot(
        client: SqlClient,
        material: MaterialSnapshot,
        lotId: String?,
    ): Future<Void?> {
        if (material.batchControl && lotId == null)
            throw ConflictException("material ${material.id} requires a lot")
        if (!material.batchControl && lotId != null)
            throw ConflictException("material ${material.id} does not use batch control")
        if (lotId == null) return Future.succeededFuture(null)

        val lotQuery = ctx.select(
            Lots.LOTS.MATERIAL_ID.`as`("lot_material_id"),
            Lots.LOTS.EXPIRY_DATE.`as`("lot_expiry_date"),
        )
            .from(Lots.LOTS)
            .where(Lots.LOTS.ID.eq(lotId))
        return client.preparedQuery(DatabaseConfig.sql(lotQuery))
            .execute(DatabaseConfig.tuple(lotQuery))
            .compose { lotRows ->
                if (lotRows.size() == 0)
                    throw ConflictException("lot $lotId not found")
                val lotRow = lotRows.iterator().next()
                if (lotRow.getValue(0)?.toString() != material.id)
                    throw ConflictException("lot $lotId does not belong to material ${material.id}")
                val expiry = lotRow.getValue(1) as? LocalDate
                if (expiry != null && expiry.isBefore(LocalDate.now()))
                    throw ConflictException("lot $lotId has expired on $expiry")
                Future.succeededFuture(null as Void?)
            }
    }

    private fun loadStock(
        client: SqlClient,
        warehouse: String,
        materialId: String,
        lotId: String?,
        forUpdate: Boolean,
    ): Future<StockRow?> {
        val query = ctx.select(
            Stocks.STOCKS.ID.`as`("stock_id"),
            Stocks.STOCKS.LOT_ID.`as`("stock_lot_id"),
            Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
            Stocks.STOCKS.LOCKED_QUANTITY.`as`("stock_locked_quantity"),
            Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
        )
            .from(Stocks.STOCKS)
            .where(Stocks.STOCKS.WAREHOUSE.eq(warehouse).and(Stocks.STOCKS.MATERIAL_ID.eq(materialId)))
            .let { q ->
                if (lotId != null) q.and(Stocks.STOCKS.LOT_ID.eq(lotId))
                else q.and(Stocks.STOCKS.LOT_ID.isNull)
            }
        val q = if (forUpdate) query.forUpdate() else query
        return client.preparedQuery(DatabaseConfig.sql(q))
            .execute(DatabaseConfig.tuple(q))
            .map { rows ->
                if (rows.size() == 0) null
                else {
                    val row = rows.iterator().next()
                    StockRow(
                        id = row.getValue(0)?.toString() ?: "",
                        lotId = row.getValue(1)?.toString(),
                        quantity = stockDecimalValue(row.getValue(2)),
                        lockedQuantity = stockDecimalValue(row.getValue(3)),
                        totalCost = stockDecimalValue(row.getValue(4)),
                    )
                }
            }
    }

    /** 目标库存：先 SELECT FOR UPDATE；不存在则插入，唯一冲突后重读锁定。 */
    private fun ensureStock(
        client: SqlClient,
        warehouse: String,
        materialId: String,
        lotId: String?,
        now: OffsetDateTime,
    ): Future<StockRow> {
        fun toStock(row: Row): StockRow =
            StockRow(
                id = row.getValue(0)?.toString() ?: "",
                lotId = row.getValue(1)?.toString(),
                quantity = stockDecimalValue(row.getValue(2)),
                lockedQuantity = stockDecimalValue(row.getValue(3)),
                totalCost = stockDecimalValue(row.getValue(4)),
            )

        fun readLocked(): Future<StockRow> =
            loadStock(client, warehouse, materialId, lotId, forUpdate = true)
                .map { it ?: throw ConflictException("target stock disappeared during transfer for material $materialId") }

        return loadStock(client, warehouse, materialId, lotId, forUpdate = true)
            .compose { existing ->
                if (existing != null) {
                    Future.succeededFuture(existing)
                } else {
                    val stockId = Ulid.generate()
                    val insertQ = ctx.insertInto(Stocks.STOCKS)
                        .set(Stocks.STOCKS.ID, stockId)
                        .set(Stocks.STOCKS.WAREHOUSE, warehouse)
                        .set(Stocks.STOCKS.MATERIAL_ID, materialId)
                        .set(Stocks.STOCKS.LOT_ID, lotId)
                        .set(Stocks.STOCKS.QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.LOCKED_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.TOTAL_COST, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                    client.preparedQuery(DatabaseConfig.sql(insertQ))
                        .execute(DatabaseConfig.tuple(insertQ))
                        .map {
                            StockRow(stockId, lotId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                        }
                        .recover { error: Throwable ->
                            if (error is PgException && error.sqlState == "23505") readLocked()
                            else Future.failedFuture(error)
                        }
                }
            }
    }

    private fun validateQuantityAndCost(
        quantity: BigDecimal,
        unitCost: BigDecimal,
        material: MaterialSnapshot,
    ) {
        validateBaseQuantity(quantity, material.quantityScale)
        validateUnitCost(unitCost)
        totalCostOf(quantity, unitCost)
    }

    private fun totalCostOf(quantity: BigDecimal, unitCost: BigDecimal): BigDecimal {
        val total = quantity.multiply(unitCost)
        if (total.stripTrailingZeros().scale() > COST_DB_SCALE)
            throw IllegalArgumentException("total_cost exceeds precision of $COST_DB_SCALE decimals")
        return total
    }

    private fun avgUnitCost(stock: StockRow): BigDecimal =
        if (stock.quantity.compareTo(BigDecimal.ZERO) > 0)
            stock.totalCost.divide(stock.quantity, COST_DB_SCALE, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

    private fun validateAvailable(stock: StockRow, required: BigDecimal): Future<Void?> {
        val available = stock.quantity.subtract(stock.lockedQuantity)
        return if (available < required) {
            Future.failedFuture(
                ConflictException("insufficient stock: only $available available, requested $required"),
            )
        } else {
            Future.succeededFuture(null)
        }
    }

    companion object {
        fun availableStockToJson(row: Row): JsonObject {
            val qty = stockDecimalValue(row.getValue("quantity"))
            val locked = stockDecimalValue(row.getValue("locked_quantity"))
            val available = qty.subtract(locked)
            val totalCost = stockDecimalValue(row.getValue("total_cost"))
            val unitCost = if (qty.compareTo(BigDecimal.ZERO) > 0)
                totalCost.divide(qty, COST_DB_SCALE, RoundingMode.HALF_UP)
            else BigDecimal.ZERO

            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("material_code", row.getValue("material_code")?.toString())
                .put("material_name", row.getValue("material_name")?.toString())
                .put("category", row.getValue("material_category")?.toString())
                .put("unit", row.getValue("unit")?.toString())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("batch_no", row.getValue("batch_no")?.toString())
                .put("expiry_date", row.getValue("expiry_date")?.toString())
                .put("quantity", qty.toDouble())
                .put("locked_quantity", locked.toDouble())
                .put("available_quantity", available.toDouble())
                .put("unit_cost", unitCost.toDouble())
        }

        fun operationToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("order_no", row.getValue("order_no")?.toString())
                .put("operation_type", row.getValue("operation_type")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("confirmed_at", row.getValue("confirmed_at")?.toString())

        fun detailToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("operation_id", row.getValue("operation_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("quantity", stockDecimalValueOrNull(row.getValue("quantity"))?.toDouble())
                .put("unit", row.getValue("unit")?.toString())
                .put("unit_cost", stockDecimalValueOrNull(row.getValue("unit_cost"))?.toDouble())
                .put("total_cost", stockDecimalValueOrNull(row.getValue("total_cost"))?.toDouble())
                .put("created_at", row.getValue("created_at")?.toString())
    }
}
