package com.ovaphlow.crate.inventories

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
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class StockService(
    private val pool: Pool,
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
            val metadata = JsonObject()
                .put("source", "MANUAL_INBOUND")
                .put("note", command.note)
            connection.preparedQuery(INSERT_OPERATION_SQL).execute(
                Tuple.of(opId, orderNo, "INBOUND", command.warehouse, "CONFIRMED", metadata, now, now)
            )
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
                            insertDetail(
                                connection, opId, item.materialId, item.lotId, item.quantity,
                                material.baseUnit, item.unitCost, totalCost, now, detailId,
                            )
                                .compose {
                                    upsertStock(
                                        connection, command.warehouse, item.materialId, item.lotId,
                                        item.quantity, item.unitCost, now,
                                    )
                                }
                                .map {
                                    detailIds.add(detailId)
                                }
                        }
                }
                .compose { processSequentially(index + 1) }
        }
        return processSequentially(0)
    }

    private fun insertDetail(
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
    ): Future<RowSet<Row>> =
        client.preparedQuery(INSERT_DETAIL_SQL).execute(
            Tuple.of(detailId, operationId, materialId, lotId, quantity, unit, unitCost, totalCost, now)
        )

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
                    client.preparedQuery(UPDATE_STOCK_SQL).execute(
                        Tuple.of(stock.quantity.add(addQuantity), stock.totalCost.add(addTotalCost), now, stock.id)
                    ).map { null as Void? }
                } else {
                    val stockId = Ulid.generate()
                    client.preparedQuery(INSERT_STOCK_SQL).execute(
                        Tuple.of(
                            stockId, warehouse, materialId, lotId, addQuantity,
                            BigDecimal.ZERO, addTotalCost, now,
                        )
                    ).map { null as Void? }
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

        return client.preparedQuery(INSERT_OPERATION_SQL).execute(
            Tuple.of(opId, "PH-$opId", "OUTBOUND", command.warehouse, "CONFIRMED", metadata, now, now)
        )
            .compose {
                insertDetail(
                    client, opId, command.materialId, stock.lotId, command.quantity,
                    material.baseUnit, unitCost, totalCost, now, detailId,
                )
            }
            .compose {
                client.preparedQuery(UPDATE_STOCK_SQL).execute(
                    Tuple.of(
                        stock.quantity.subtract(command.quantity),
                        stock.totalCost.subtract(totalCost).max(BigDecimal.ZERO),
                        now, stock.id,
                    )
                )
            }
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
                        val metadata = JsonObject()
                            .put("source", "PHARMACY_RETURN")
                            .put("note", command.note)

                        client.preparedQuery(INSERT_OPERATION_SQL).execute(
                            Tuple.of(
                                opId, "PH-RETURN-$opId", "INBOUND", command.warehouse,
                                "CONFIRMED", metadata, now, now,
                            )
                        )
                            .compose {
                                insertDetail(
                                    client, opId, command.materialId, command.lotId, command.quantity,
                                    material.baseUnit, command.unitCost, totalCost, now, detailId,
                                )
                            }
                            .compose {
                                upsertStock(
                                    client, command.warehouse, command.materialId, command.lotId,
                                    command.quantity, command.unitCost, now,
                                )
                            }
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
        val placeholders = materialIds.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = "SELECT id FROM materials WHERE status = \$${materialIds.size + 1} " +
            "AND id IN ($placeholders)"
        val args = materialIds.toMutableList<Any>().apply { add("ACTIVE") }
        return client.preparedQuery(sql).execute(Tuple.from(args))
            .map { rows: RowSet<Row> ->
                val found = rows.mapNotNull { it.getValue("id")?.toString() }.toSet()
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
                                                client.preparedQuery(UPDATE_LOCKED_SQL).execute(
                                                    Tuple.of(
                                                        stock.lockedQuantity.add(item.quantity),
                                                        OffsetDateTime.now(), stock.id,
                                                    )
                                                ).map { null as Void? }
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
                            client.preparedQuery(UPDATE_LOCKED_SQL).execute(
                                Tuple.of(
                                    stock.lockedQuantity.subtract(item.quantity),
                                    OffsetDateTime.now(), stock.id,
                                )
                            ).map { null as Void? }
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
                } else if (stock.quantity < item.quantity) {
                    // 预留损坏（locked_quantity 高于实际 quantity）时，出库会扣出负库存；
                    // 必须以源库存 quantity 校验兜底，而不是照常扣减。
                    Future.failedFuture(
                        ConflictException("reservation corrupted: source quantity ${stock.quantity} below transfer ${item.quantity}"),
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

        val outboundMetadata = JsonObject()
            .put("source", "PHARMACY_REQUISITION_TRANSFER")
            .put("requisition_id", command.requisitionId)
            .put("requisition_no", command.requisitionNo)
            .put("destination_warehouse", command.destinationWarehouse)
            .put("dispensed_by", command.dispensedBy)
        val inboundMetadata = JsonObject()
            .put("source", "PHARMACY_REQUISITION_TRANSFER")
            .put("requisition_id", command.requisitionId)
            .put("requisition_no", command.requisitionNo)
            .put("source_warehouse", command.sourceWarehouse)
            .put("dispensed_by", command.dispensedBy)

        fun writeItems(index: Int): Future<List<RequisitionTransferItemResult>> {
            if (index >= ordered.size) return Future.succeededFuture(itemResults)
            val item = ordered[index]
            val resolvedItem = resolved[index]
            val totalCost = totalCostOf(item.quantity, resolvedItem.unitCost)
            val outboundDetailId = Ulid.generate()
            val inboundDetailId = Ulid.generate()
            return insertDetail(
                client, outboundOpId, item.materialId, item.lotId, item.quantity,
                resolvedItem.material.baseUnit, resolvedItem.unitCost, totalCost, now, outboundDetailId,
            )
                .compose {
                    insertDetail(
                        client, inboundOpId, item.materialId, item.lotId, item.quantity,
                        resolvedItem.material.baseUnit, resolvedItem.unitCost, totalCost, now, inboundDetailId,
                    )
                }
                .map {
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
            return client.preparedQuery(UPDATE_TRANSFER_SOURCE_SQL).execute(
                Tuple.of(
                    source.quantity.subtract(item.quantity),
                    source.lockedQuantity.subtract(item.quantity),
                    source.totalCost.subtract(totalCost).max(BigDecimal.ZERO),
                    now, source.id,
                )
            ).compose { updateSources(index + 1) }
        }

        fun updateTargets(index: Int): Future<Void?> {
            if (index >= ordered.size) return Future.succeededFuture(null)
            val item = ordered[index]
            val target = targets[index].first
            val resolvedItem = resolved[index]
            val totalCost = totalCostOf(item.quantity, resolvedItem.unitCost)
            return client.preparedQuery(UPDATE_STOCK_SQL).execute(
                Tuple.of(
                    target.quantity.add(item.quantity),
                    target.totalCost.add(totalCost),
                    now, target.id,
                )
            ).compose { updateTargets(index + 1) }
        }

        return client.preparedQuery(INSERT_OPERATION_SQL).execute(
            Tuple.of(
                outboundOpId, "PH-REQ-$outboundOpId", "OUTBOUND", command.sourceWarehouse,
                "CONFIRMED", outboundMetadata, now, now,
            )
        )
            .compose {
                client.preparedQuery(INSERT_OPERATION_SQL).execute(
                    Tuple.of(
                        inboundOpId, "PH-REQ-$inboundOpId", "INBOUND", command.destinationWarehouse,
                        "CONFIRMED", inboundMetadata, now, now,
                    )
                )
            }
            .compose { writeItems(0) }
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
        fun verify(row: Row): Future<String> {
            val lotId = row.getValue("lot_id")?.toString() ?: ""
            val existingProduction = row.getValue("lot_production_date") as? LocalDate
            val existingExpiry = row.getValue("lot_expiry_date") as? LocalDate
            val existingManufacturer = row.getValue("lot_manufacturer")?.toString()
            val existingSupplier = row.getValue("lot_supplier")?.toString()
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
            client.preparedQuery(LOT_FIND_SQL).execute(Tuple.of(materialId, batchNo))
                .compose { rows: RowSet<Row> ->
                    if (rows.size() == 0) {
                        Future.failedFuture(
                            ConflictException("lot $batchNo vanished during create for material $materialId"),
                        )
                    } else {
                        verify(rows.iterator().next())
                    }
                }

        return client.preparedQuery(LOT_FIND_SQL).execute(Tuple.of(materialId, batchNo))
            .compose { rows: RowSet<Row> ->
                if (rows.size() > 0) {
                    verify(rows.iterator().next())
                } else {
                    val lotId = Ulid.generate()
                    client.preparedQuery(LOT_INSERT_SQL).execute(
                        Tuple.of(
                            lotId, materialId, batchNo, productionDate, expiryDate,
                            manufacturer, supplier,
                        )
                    )
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
        val metadata = JsonObject()
            .put("source", "PHARMACY_PURCHASE_RECEIPT")
            .put("purchase_order_id", command.purchaseOrderId)
            .put("purchase_order_no", command.purchaseOrderNo)
            .put("purchase_receipt_id", command.purchaseReceiptId)
            .put("receipt_no", command.receiptNo)
            .put("supplier_name", command.supplierName)
            .put("warehouse", command.warehouse)
            .put("received_by", command.receivedBy)

        val itemResults = mutableListOf<PurchaseReceiptItemResult>()

        fun writeOne(index: Int): Future<Void?> {
            if (index >= resolved.size) return Future.succeededFuture(null)
            val entry = resolved[index]
            val item = entry.item
            val detailId = Ulid.generate()
            val totalCost = totalCostOf(item.quantity, item.unitCost)
            return insertDetail(
                client, opId, item.materialId, entry.lotId, item.quantity,
                entry.material.baseUnit, item.unitCost, totalCost, now, detailId,
            )
                .compose {
                    client.preparedQuery(UPDATE_STOCK_SQL).execute(
                        Tuple.of(
                            entry.stock.quantity.add(item.quantity),
                            entry.stock.totalCost.add(totalCost),
                            now, entry.stock.id,
                        )
                    )
                }
                .map {
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
                .compose { _: Void? -> writeOne(index + 1) }
        }

        return client.preparedQuery(INSERT_OPERATION_SQL).execute(
            Tuple.of(opId, "PH-PO-$opId", "INBOUND", command.warehouse, "CONFIRMED", metadata, now, now)
        )
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        where.add("s.quantity > s.locked_quantity")
        where.add("m.status = ${bind("ACTIVE")}")
        warehouse?.let { where.add("s.warehouse = ${bind(it)}") }
        materialId?.let { where.add("s.material_id = ${bind(it)}") }
        if (!search.isNullOrBlank()) {
            val keyword = bind(search)
            where.add(
                "(m.code LIKE '%' || $keyword || '%' OR m.name LIKE '%' || $keyword || '%' " +
                    "OR l.batch_no LIKE '%' || $keyword || '%')"
            )
        }
        where.add("(l.expiry_date IS NULL OR l.expiry_date >= ${bind(LocalDate.now())})")

        val whereClause = " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total$AVAILABLE_FROM$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $AVAILABLE_COLUMNS$AVAILABLE_FROM$whereClause " +
                "ORDER BY m.name ASC, l.batch_no ASC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(availableStockToJson(row))
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun loadOperation(connection: SqlConnection, opId: String): Future<JsonObject> =
        connection.preparedQuery("SELECT * FROM stock_operations WHERE id = \$1").execute(Tuple.of(opId))
            .compose { opRows ->
                if (opRows.size() == 0)
                    return@compose Future.failedFuture(NotFoundException("operation not found"))

                val opRow = opRows.iterator().next()
                val result = operationToJson(opRow)
                connection.preparedQuery("SELECT * FROM stock_operation_details WHERE operation_id = \$1")
                    .execute(Tuple.of(opId))
                    .map { detailRows ->
                        val detailArray = JsonArray()
                        for (dr in detailRows) detailArray.add(detailToJson(dr))
                        result.put("details", detailArray)
                        result
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
        val sql = MATERIAL_SQL + if (forUpdate) " FOR UPDATE" else ""
        return client.preparedQuery(sql).execute(Tuple.of(materialId))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("material not found: $materialId"))
                } else {
                    val row = rows.iterator().next()
                    val status = row.getValue("material_status")?.toString() ?: ""
                    if (status != "ACTIVE") {
                        Future.failedFuture(ConflictException("material $materialId is not ACTIVE"))
                    } else {
                        val baseUnit = row.getValue("material_base_unit")?.toString()
                        if (baseUnit.isNullOrBlank()) {
                            Future.failedFuture(ConflictException("material $materialId has no base unit"))
                        } else {
                            Future.succeededFuture(
                                MaterialSnapshot(
                                    id = materialId,
                                    status = status,
                                    baseUnit = baseUnit,
                                    quantityScale = (row.getValue("material_quantity_scale") as? Number)?.toInt() ?: 0,
                                    batchControl = row.getValue("material_batch_control") as? Boolean ?: false,
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

        return client.preparedQuery(LOT_EXPIRY_SQL).execute(Tuple.of(lotId))
            .compose { lotRows ->
                if (lotRows.size() == 0)
                    throw ConflictException("lot $lotId not found")
                val lotRow = lotRows.iterator().next()
                if (lotRow.getValue("lot_material_id")?.toString() != material.id)
                    throw ConflictException("lot $lotId does not belong to material ${material.id}")
                val expiry = lotRow.getValue("lot_expiry_date") as? LocalDate
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
        val sql = if (lotId != null) STOCK_BY_LOT_SQL else STOCK_BY_LOT_NULL_SQL
        val args = if (lotId != null) Tuple.of(warehouse, materialId, lotId) else Tuple.of(warehouse, materialId)
        val finalSql = sql + if (forUpdate) " FOR UPDATE" else ""
        return client.preparedQuery(finalSql).execute(args)
            .map { rows ->
                if (rows.size() == 0) null
                else toStockRow(rows.iterator().next())
            }
    }

    private fun toStockRow(row: Row): StockRow =
        StockRow(
            id = row.getValue("stock_id")?.toString() ?: "",
            lotId = row.getValue("stock_lot_id")?.toString(),
            quantity = stockDecimalValue(row.getValue("stock_quantity")),
            lockedQuantity = stockDecimalValue(row.getValue("stock_locked_quantity")),
            totalCost = stockDecimalValue(row.getValue("stock_total_cost")),
        )

    /** 目标库存：先 SELECT FOR UPDATE；不存在则插入，唯一冲突后重读锁定。 */
    private fun ensureStock(
        client: SqlClient,
        warehouse: String,
        materialId: String,
        lotId: String?,
        now: OffsetDateTime,
    ): Future<StockRow> {
        fun readLocked(): Future<StockRow> =
            loadStock(client, warehouse, materialId, lotId, forUpdate = true)
                .map { it ?: throw ConflictException("target stock disappeared during transfer for material $materialId") }

        return loadStock(client, warehouse, materialId, lotId, forUpdate = true)
            .compose { existing ->
                if (existing != null) {
                    Future.succeededFuture(existing)
                } else {
                    val stockId = Ulid.generate()
                    client.preparedQuery(INSERT_STOCK_SQL).execute(
                        Tuple.of(
                            stockId, warehouse, materialId, lotId,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, now,
                        )
                    )
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
        private const val INSERT_OPERATION_SQL =
            "INSERT INTO stock_operations " +
                "(id, order_no, operation_type, warehouse, status, metadata, confirmed_at, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val INSERT_DETAIL_SQL =
            "INSERT INTO stock_operation_details " +
                "(id, operation_id, material_id, lot_id, quantity, unit, unit_cost, total_cost, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val INSERT_STOCK_SQL =
            "INSERT INTO stocks " +
                "(id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost, last_updated) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val UPDATE_STOCK_SQL =
            "UPDATE stocks SET quantity = \$1, total_cost = \$2, last_updated = \$3 WHERE id = \$4"

        private const val UPDATE_LOCKED_SQL =
            "UPDATE stocks SET locked_quantity = \$1, last_updated = \$2 WHERE id = \$3"

        private const val UPDATE_TRANSFER_SOURCE_SQL =
            "UPDATE stocks SET quantity = \$1, locked_quantity = \$2, total_cost = \$3, " +
                "last_updated = \$4 WHERE id = \$5"

        private const val MATERIAL_SQL =
            "SELECT status AS material_status, base_unit AS material_base_unit, " +
                "quantity_scale AS material_quantity_scale, enable_batch_control AS material_batch_control " +
                "FROM materials WHERE id = \$1"

        private const val LOT_EXPIRY_SQL =
            "SELECT material_id AS lot_material_id, expiry_date AS lot_expiry_date FROM lots WHERE id = \$1"

        private const val LOT_FIND_SQL =
            "SELECT id AS lot_id, production_date AS lot_production_date, expiry_date AS lot_expiry_date, " +
                "manufacturer AS lot_manufacturer, supplier AS lot_supplier " +
                "FROM lots WHERE material_id = \$1 AND batch_no = \$2"

        private const val LOT_INSERT_SQL =
            "INSERT INTO lots (id, material_id, batch_no, production_date, expiry_date, manufacturer, supplier) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7)"

        private const val STOCK_BY_LOT_SQL =
            "SELECT id AS stock_id, lot_id AS stock_lot_id, quantity AS stock_quantity, " +
                "locked_quantity AS stock_locked_quantity, total_cost AS stock_total_cost " +
                "FROM stocks WHERE warehouse = \$1 AND material_id = \$2 AND lot_id = \$3"

        private const val STOCK_BY_LOT_NULL_SQL =
            "SELECT id AS stock_id, lot_id AS stock_lot_id, quantity AS stock_quantity, " +
                "locked_quantity AS stock_locked_quantity, total_cost AS stock_total_cost " +
                "FROM stocks WHERE warehouse = \$1 AND material_id = \$2 AND lot_id IS NULL"

        private const val AVAILABLE_COLUMNS =
            "s.id AS id, s.warehouse AS warehouse, s.material_id AS material_id, s.lot_id AS lot_id, " +
                "s.quantity AS quantity, s.locked_quantity AS locked_quantity, s.total_cost AS total_cost, " +
                "s.last_updated AS last_updated, m.code AS material_code, m.name AS material_name, " +
                "m.category AS material_category, m.base_unit AS unit, l.batch_no AS batch_no, " +
                "l.expiry_date AS expiry_date"

        private const val AVAILABLE_FROM =
            " FROM stocks s JOIN materials m ON m.id = s.material_id " +
                "LEFT JOIN lots l ON l.id = s.lot_id"

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
                .put("quantity", decimalText(qty))
                .put("locked_quantity", decimalText(locked))
                .put("available_quantity", decimalText(available))
                .put("unit_cost", decimalText(unitCost))
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
                .put("quantity", decimalText(stockDecimalValueOrNull(row.getValue("quantity"))))
                .put("unit", row.getValue("unit")?.toString())
                .put("unit_cost", decimalText(stockDecimalValueOrNull(row.getValue("unit_cost"))))
                .put("total_cost", decimalText(stockDecimalValueOrNull(row.getValue("total_cost"))))
                .put("created_at", row.getValue("created_at")?.toString())
    }
}
