package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Lots
import com.ovaphlow.crate.database.gen.inventories.public_.tables.MaterialUnitSpecs
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

private fun stockDecimalValue(value: Any?): BigDecimal =
    stockDecimalValueOrNull(value) ?: BigDecimal.ZERO

private fun stockDecimalValueOrNull(value: Any?): BigDecimal? = when (value) {
    null -> null
    is BigDecimal -> value
    is Number -> value.toString().toBigDecimalOrNull()
    else -> value.toString().toBigDecimalOrNull()
}

/**
 * 明细旧列投影（计划 015 过渡）：unit 取 PACKAGE/SPLIT（默认规格/其他规格），
 * quantity 为按规格比率投影回输入单位的量化值，split_quantity 仅 SPLIT 行填充
 * 输入数量。快照列（unit_spec_id/input_* /base_*）才是权威事实，旧列只服务
 * 旧对账与展示，不参与任何基础数量计算。
 */
internal fun legacyDetailColumns(conversion: BaseQuantityCommand): Triple<String, BigDecimal, BigDecimal?> {
    val unit = if (conversion.isDefaultSpec) "PACKAGE" else "SPLIT"
    val quantity = conversion.baseQuantity.divide(conversion.conversionRatio, 4, RoundingMode.HALF_UP)
    val splitQuantity = if (unit == "SPLIT") conversion.inputQuantity else null
    return Triple(unit, quantity, splitQuantity)
}

/**
 * 明细快照列（计划 015 权威事实）统一写入：unit_spec_id / input_quantity /
 * input_unit / conversion_ratio / base_quantity / base_unit / input_unit_cost /
 * base_unit_cost。所有写入路径共用，杜绝字段遗漏与漂移。
 */
internal fun applyConversionSnapshots(
    insert: org.jooq.InsertSetMoreStep<com.ovaphlow.crate.database.gen.inventories.public_.tables.records.StockOperationDetailsRecord>,
    conversion: BaseQuantityCommand,
): org.jooq.InsertSetMoreStep<com.ovaphlow.crate.database.gen.inventories.public_.tables.records.StockOperationDetailsRecord> =
    insert
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_SPEC_ID, conversion.unitSpecId)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.INPUT_QUANTITY, conversion.inputQuantity)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.INPUT_UNIT, conversion.inputUnit)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CONVERSION_RATIO, conversion.conversionRatio)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.BASE_QUANTITY, conversion.baseQuantity)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.BASE_UNIT, conversion.baseUnit)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.INPUT_UNIT_COST, conversion.inputUnitCost)
        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.BASE_UNIT_COST, conversion.baseUnitCost)

class StockService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
    private val conversionService: UnitConversionService = UnitConversionService(),
) {
    private val log = LoggerFactory.getLogger(StockService::class.java)

    // ========================================================================
    //  手工确认入库
    //  新契约（计划 015）：material_id + lot_id? + unit_spec_id + input_quantity
    //    + input_unit_cost；旧 quantity/unit_cost 形式仅在过渡窗口映射当前默认
    //    包装规格并记录弃用日志。两种形式互斥，混合提交返回 400。
    // ========================================================================

    data class InboundItem(
        val materialId: String,
        val lotId: String?,
        /** 新契约：活动规格 ID，与 [inputQuantity] 同现 */
        val unitSpecId: String? = null,
        /** 新契约：规格单位下的输入数量 */
        val inputQuantity: BigDecimal? = null,
        /** 旧契约过渡：包装数量（使用当前默认规格换算） */
        val quantity: BigDecimal? = null,
        /** 输入单位成本（新契约 input_unit_cost；旧契约 unit_cost） */
        val unitCost: BigDecimal
    )

    data class InboundCommand(
        val warehouse: String,
        val items: List<InboundItem>,
        val note: String?
    )

    /** 新/旧入库契约互斥校验：返回错误描述或 null */
    private fun InboundItem.validateContract(): String? {
        val hasNew = unitSpecId != null || inputQuantity != null
        val hasOld = quantity != null
        return when {
            hasNew && hasOld -> "must not mix unit_spec_id/input_quantity (new contract) with quantity (legacy)"
            hasNew && (unitSpecId == null || inputQuantity == null) ->
                "unit_spec_id and input_quantity must be provided together"
            hasNew && inputQuantity!! <= BigDecimal.ZERO -> "input_quantity must be positive"
            hasOld && quantity!! <= BigDecimal.ZERO -> "quantity must be positive"
            !hasNew && !hasOld -> "either quantity (legacy) or unit_spec_id+input_quantity (new contract) is required"
            else -> null
        }
    }

    // ========================================================================
    //  011 药房发药：同连接包装单位出库（禁止端口内部重新取 Pool）
    // ========================================================================

    data class PackageOutboundCommand(
        val warehouse: String,
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val note: String?
    )

    data class PackageOutboundResult(
        val stockOperationDetailId: String,
        val lotId: String?,
        val unitCost: BigDecimal
    )

    data class PackageInboundCommand(
        val warehouse: String,
        val materialId: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val unitCost: BigDecimal,
        val note: String?,
    )

    data class PackageInboundResult(
        val stockOperationDetailId: String,
        val lotId: String?,
        val unitCost: BigDecimal,
    )

    private data class PackageStock(
        val id: String,
        val lotId: String?,
        val quantity: BigDecimal,
        val lockedQuantity: BigDecimal,
        val totalCost: BigDecimal,
        /** 基础结存（计划 015）：存量与锁定按基础数量权威计量 */
        val baseQuantity: BigDecimal,
        val lockedBaseQuantity: BigDecimal,
    )

    /**
     * 在创建药房发药单前只读校验物资、批次和当前可用库存。
     *
     * 此检查不锁定、不扣减库存，也不写库存操作。确认发药时仍须重新锁定并校验，
     * 以避免创建与确认之间的库存并发变化造成超发。
     */
    fun validatePackageOutbound(
        client: SqlClient,
        command: PackageOutboundCommand,
    ): Future<Void?> {
        validatePackageOutboundInput(command)?.let { return Future.failedFuture(it) }

        // 计划 015：先解析当前默认包装规格（锁物资+规格，校验计量模型状态），
        // 再按基础数量校验可用量。库存行不锁定（只读校验）。
        return loadPackageStock(client, command, forUpdate = false)
            .compose { stock ->
                validateMaterialAndLot(client, command, stock.lotId)
                    .compose {
                        conversionService.resolvePackagePort(
                            client,
                            command.materialId,
                            command.quantity,
                            avgUnitCost(stock),
                        )
                    }
                    .compose { conversion -> validateAvailableQuantity(stock, conversion.baseQuantity) }
            }
    }

    /**
     * 在调用方外层事务连接内完成一次包装单位（PACKAGE）出库：
     * 锁定目标库存行、校验物资/批次/可用量，写 OUTBOUND 操作与明细，扣减库存并返回关联 ID。
     * 任何一步失败均由调用方事务整体回滚；本方法自身不开启新事务。
     *
     * 锁序（计划 015）：物资+默认规格（UnitConversionService）→ 库存行。
     * 平均包装成本以无锁预览为准，基础数量校验/扣减以 FOR UPDATE 重读为准。
     */
    fun confirmPackageOutbound(
        client: SqlClient,
        command: PackageOutboundCommand,
    ): Future<PackageOutboundResult> {
        validatePackageOutboundInput(command)?.let { return Future.failedFuture(it) }

        val now = OffsetDateTime.now()

        return loadPackageStock(client, command, forUpdate = false)
            .compose { preview ->
                conversionService.resolvePackagePort(
                    client,
                    command.materialId,
                    command.quantity,
                    avgUnitCost(preview),
                ).compose { conversion ->
                    loadPackageStock(client, command, forUpdate = true)
                        .compose { stock ->
                            validateMaterialAndLot(client, command, stock.lotId)
                                .compose { validateAvailableQuantity(stock, conversion.baseQuantity) }
                                .compose { outboundWrite(client, command, stock, conversion, now) }
                        }
                }
            }
    }

    /**
     * 在调用方外层事务连接内完成一次退药包装回库。
     * 物资、批次和单位成本由 Pharmacy 从原发药明细推导；本方法不启动新事务。
     */
    fun confirmPackageInbound(
        client: SqlClient,
        command: PackageInboundCommand,
    ): Future<PackageInboundResult> {
        if (command.warehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        if (command.materialId.isBlank())
            return Future.failedFuture(IllegalArgumentException("material_id is required"))
        if (command.quantity <= BigDecimal.ZERO)
            return Future.failedFuture(IllegalArgumentException("quantity must be positive"))
        if (command.unitCost < BigDecimal.ZERO)
            return Future.failedFuture(IllegalArgumentException("unit_cost must not be negative"))

        val validationCommand = PackageOutboundCommand(
            warehouse = command.warehouse,
            materialId = command.materialId,
            lotId = command.lotId,
            quantity = command.quantity,
            note = command.note,
        )
        // 计划 015：先解析当前默认包装规格（锁物资+规格），再锁定库存行写入
        return conversionService.resolvePackagePort(
            client,
            command.materialId,
            command.quantity,
            command.unitCost,
        ).compose { conversion ->
            validateMaterialAndLot(client, validationCommand, command.lotId)
                .compose { writePackageInbound(client, command, conversion) }
        }
    }

    private fun writePackageInbound(
        client: SqlClient,
        command: PackageInboundCommand,
        conversion: BaseQuantityCommand,
    ): Future<PackageInboundResult> {
        val now = OffsetDateTime.now()
        val operationId = Ulid.generate()
        val detailId = Ulid.generate()
        val legacy = legacyDetailColumns(conversion)
        val stockQuery = ctx.select(
            Stocks.STOCKS.ID.`as`("stock_id"),
            Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
            Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
            Stocks.STOCKS.BASE_QUANTITY.`as`("stock_base_quantity"),
        )
            .from(Stocks.STOCKS)
            .where(
                Stocks.STOCKS.WAREHOUSE.eq(command.warehouse)
                    .and(Stocks.STOCKS.MATERIAL_ID.eq(command.materialId)),
            )
            .let { query ->
                if (command.lotId != null) query.and(Stocks.STOCKS.LOT_ID.eq(command.lotId))
                else query.and(Stocks.STOCKS.LOT_ID.isNull)
            }
            .forUpdate()

        val insertOperation = ctx.insertInto(StockOperations.STOCK_OPERATIONS)
            .set(StockOperations.STOCK_OPERATIONS.ID, operationId)
            .set(StockOperations.STOCK_OPERATIONS.ORDER_NO, "PH-RETURN-$operationId")
            .set(StockOperations.STOCK_OPERATIONS.OPERATION_TYPE, "INBOUND")
            .set(StockOperations.STOCK_OPERATIONS.WAREHOUSE, command.warehouse)
            .set(StockOperations.STOCK_OPERATIONS.STATUS, "CONFIRMED")
            .set(
                StockOperations.STOCK_OPERATIONS.METADATA,
                JSONB.valueOf(JsonObject()
                    .put("source", "PHARMACY_RETURN")
                    .put("note", command.note)
                    .encode()),
            )
            .set(StockOperations.STOCK_OPERATIONS.CONFIRMED_AT, now)
            .set(StockOperations.STOCK_OPERATIONS.CREATED_AT, now)
        val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, operationId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, command.materialId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, command.lotId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, legacy.second)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, legacy.first)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, legacy.third)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, conversion.inputUnitCost)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, conversion.totalCost)
            .let { applyConversionSnapshots(it, conversion) }
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)

        return client.preparedQuery(DatabaseConfig.sql(stockQuery))
            .execute(DatabaseConfig.tuple(stockQuery))
            .compose { stockRows ->
                val stock = stockRows.iterator().asSequence().firstOrNull()
                val stockId = stock?.getValue(0)?.toString() ?: Ulid.generate()
                val oldQuantity = stock?.let { stockDecimalValue(it.getValue(1)) } ?: BigDecimal.ZERO
                val oldTotalCost = stock?.let { stockDecimalValue(it.getValue(2)) } ?: BigDecimal.ZERO
                val oldBase = stock?.let { stockDecimalValue(it.getValue(3)) } ?: BigDecimal.ZERO
                val stockWrite = if (stock == null) {
                    ctx.insertInto(Stocks.STOCKS)
                        .set(Stocks.STOCKS.ID, stockId)
                        .set(Stocks.STOCKS.WAREHOUSE, command.warehouse)
                        .set(Stocks.STOCKS.MATERIAL_ID, command.materialId)
                        .set(Stocks.STOCKS.LOT_ID, command.lotId)
                        .set(Stocks.STOCKS.QUANTITY, command.quantity)
                        .set(Stocks.STOCKS.LOCKED_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.BASE_QUANTITY, conversion.baseQuantity)
                        .set(Stocks.STOCKS.LOCKED_BASE_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.UNIT_MODEL_STATUS, "ACTIVE")
                        .set(Stocks.STOCKS.TOTAL_COST, conversion.totalCost)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                } else {
                    ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, oldQuantity.add(command.quantity))
                        .set(Stocks.STOCKS.BASE_QUANTITY, oldBase.add(conversion.baseQuantity))
                        .set(Stocks.STOCKS.TOTAL_COST, oldTotalCost.add(conversion.totalCost))
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                        .where(Stocks.STOCKS.ID.eq(stockId))
                }

                client.preparedQuery(DatabaseConfig.sql(insertOperation))
                    .execute(DatabaseConfig.tuple(insertOperation))
                    .compose { client.preparedQuery(DatabaseConfig.sql(insertDetail)).execute(DatabaseConfig.tuple(insertDetail)) }
                    .compose { client.preparedQuery(DatabaseConfig.sql(stockWrite)).execute(DatabaseConfig.tuple(stockWrite)) }
                    .map { PackageInboundResult(detailId, command.lotId, conversion.inputUnitCost) }
            }
    }

    private fun validatePackageOutboundInput(command: PackageOutboundCommand): IllegalArgumentException? =
        when {
            command.warehouse.isBlank() -> IllegalArgumentException("warehouse is required")
            command.materialId.isBlank() -> IllegalArgumentException("material_id is required")
            command.quantity <= BigDecimal.ZERO -> IllegalArgumentException("quantity must be positive")
            else -> null
        }

    private fun loadPackageStock(
        client: SqlClient,
        command: PackageOutboundCommand,
        forUpdate: Boolean,
    ): Future<PackageStock> {
        val stockQuery = ctx.select(
            Stocks.STOCKS.ID.`as`("stock_id"),
            Stocks.STOCKS.LOT_ID.`as`("stock_lot_id"),
            Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
            Stocks.STOCKS.LOCKED_QUANTITY.`as`("stock_locked_quantity"),
            Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
            Stocks.STOCKS.BASE_QUANTITY.`as`("stock_base_quantity"),
            Stocks.STOCKS.LOCKED_BASE_QUANTITY.`as`("stock_locked_base_quantity"),
        )
            .from(Stocks.STOCKS)
            .where(
                Stocks.STOCKS.WAREHOUSE.eq(command.warehouse)
                    .and(Stocks.STOCKS.MATERIAL_ID.eq(command.materialId)),
            )
            .let { q ->
                if (command.lotId != null) q.and(Stocks.STOCKS.LOT_ID.eq(command.lotId))
                else q.and(Stocks.STOCKS.LOT_ID.isNull)
            }
        val query = if (forUpdate) stockQuery.forUpdate() else stockQuery

        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { stockRows ->
                if (stockRows.size() == 0) {
                    return@compose Future.failedFuture(
                        ConflictException(
                            "insufficient stock: no stock for material ${command.materialId} in warehouse ${command.warehouse}",
                        ),
                    )
                }
                val stockRow = stockRows.iterator().next()
                Future.succeededFuture(
                    PackageStock(
                        id = stockRow.getValue(0)?.toString() ?: "",
                        lotId = stockRow.getValue(1)?.toString(),
                        quantity = stockDecimalValue(stockRow.getValue(2)),
                        lockedQuantity = stockDecimalValue(stockRow.getValue(3)),
                        totalCost = stockDecimalValue(stockRow.getValue(4)),
                        baseQuantity = stockDecimalValue(stockRow.getValue(5)),
                        lockedBaseQuantity = stockDecimalValue(stockRow.getValue(6)),
                    ),
                )
            }
    }

    /** 库存行平均包装成本（旧列投影，仅用于出库单位成本推导） */
    private fun avgUnitCost(stock: PackageStock): BigDecimal =
        if (stock.quantity.compareTo(BigDecimal.ZERO) > 0)
            stock.totalCost.divide(stock.quantity, 4, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

    /**
     * 基础数量可用量校验：权威口径 base_quantity - locked_base_quantity >= 所需基础数量。
     * 历史行 base 列 NULL（LEGACY/BLOCKED 物资）按 0 处理 —— 此类物资已被
     * UnitConversionService 前置拦截，到达此处时 base 必已回填。
     */
    private fun validateAvailableQuantity(
        stock: PackageStock,
        requiredBase: BigDecimal,
    ): Future<Void?> {
        val availableBase = stock.baseQuantity.subtract(stock.lockedBaseQuantity)
        return if (availableBase < requiredBase) {
            Future.failedFuture(
                ConflictException("insufficient stock: only $availableBase base available, requested $requiredBase"),
            )
        } else {
            Future.succeededFuture(null)
        }
    }

    private fun validateMaterialAndLot(
        client: SqlClient,
        command: PackageOutboundCommand,
        stockLotId: String?,
    ): Future<Void?> {
        val materialQuery = ctx.select(
            Materials.MATERIALS.STATUS.`as`("material_status"),
            Materials.MATERIALS.ENABLE_BATCH_CONTROL.`as`("material_batch_control"),
        )
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.eq(command.materialId))
        return client.preparedQuery(DatabaseConfig.sql(materialQuery))
            .execute(DatabaseConfig.tuple(materialQuery))
            .compose { matRows ->
                if (matRows.size() == 0)
                    throw NotFoundException("material not found: ${command.materialId}")
                val matRow = matRows.iterator().next()
                val matStatus = matRow.getValue(0)?.toString()
                if (matStatus != "ACTIVE")
                    throw ConflictException("material ${command.materialId} is not ACTIVE")
                val batchControlled = matRow.getValue(1) as? Boolean ?: false
                if (batchControlled && stockLotId == null)
                    throw ConflictException("material ${command.materialId} requires a lot")
                if (!batchControlled && command.lotId != null)
                    throw ConflictException("material ${command.materialId} does not use batch control")

                if (stockLotId != null) {
                    val lotQuery = ctx.select(
                        Lots.LOTS.MATERIAL_ID.`as`("lot_material_id"),
                        Lots.LOTS.EXPIRY_DATE.`as`("lot_expiry_date"),
                    )
                        .from(Lots.LOTS)
                        .where(Lots.LOTS.ID.eq(stockLotId))
                    client.preparedQuery(DatabaseConfig.sql(lotQuery))
                        .execute(DatabaseConfig.tuple(lotQuery))
                        .compose { lotRows ->
                            if (lotRows.size() == 0)
                                throw ConflictException("lot $stockLotId not found")
                            val lotRow = lotRows.iterator().next()
                            if (lotRow.getValue(0)?.toString() != command.materialId)
                                throw ConflictException("lot $stockLotId does not belong to material ${command.materialId}")
                            val expiry = lotRow.getValue(1) as? java.time.LocalDate
                            if (expiry != null && expiry.isBefore(java.time.LocalDate.now()))
                                throw ConflictException("lot $stockLotId has expired on $expiry")
                            Future.succeededFuture(null as Void?)
                        }
                } else {
                    Future.succeededFuture(null as Void?)
                }
            }
    }

    private fun outboundWrite(
        client: SqlClient,
        command: PackageOutboundCommand,
        stock: PackageStock,
        conversion: BaseQuantityCommand,
        now: OffsetDateTime,
    ): Future<PackageOutboundResult> {
        val opId = Ulid.generate()
        val detailId = Ulid.generate()
        val legacy = legacyDetailColumns(conversion)

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
        val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, command.materialId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, stock.lotId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, legacy.second)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, legacy.first)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, legacy.third)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, conversion.inputUnitCost)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, conversion.totalCost)
            .let { applyConversionSnapshots(it, conversion) }
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)
        val updateStock = ctx.update(Stocks.STOCKS)
            .set(Stocks.STOCKS.QUANTITY, stock.quantity.subtract(command.quantity))
            .set(Stocks.STOCKS.BASE_QUANTITY, stock.baseQuantity.subtract(conversion.baseQuantity))
            .set(Stocks.STOCKS.TOTAL_COST, stock.totalCost.subtract(conversion.totalCost).max(BigDecimal.ZERO))
            .set(Stocks.STOCKS.LAST_UPDATED, now)
            .where(Stocks.STOCKS.ID.eq(stock.id))

        return client.preparedQuery(DatabaseConfig.sql(insertOp))
            .execute(DatabaseConfig.tuple(insertOp))
            .compose { client.preparedQuery(DatabaseConfig.sql(insertDetail)).execute(DatabaseConfig.tuple(insertDetail)) }
            .compose { client.preparedQuery(DatabaseConfig.sql(updateStock)).execute(DatabaseConfig.tuple(updateStock)) }
            .map { PackageOutboundResult(stockOperationDetailId = detailId, lotId = stock.lotId, unitCost = conversion.inputUnitCost) }
    }

    fun confirmInbound(command: InboundCommand): Future<JsonObject> {
        if (command.warehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one item is required"))
        command.items.firstOrNull { item ->
            item.materialId.isBlank() || item.unitCost < BigDecimal.ZERO || item.validateContract() != null
        }?.let { item ->
            return Future.failedFuture(
                IllegalArgumentException("invalid inbound item: " + (item.validateContract() ?: "material_id and non-negative unit_cost required")),
            )
        }

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
        }.map { result: JsonObject? -> result ?: throw IllegalStateException("inbound transaction returned no operation") }
    }

    private fun processInboundItems(
        connection: SqlConnection,
        command: InboundCommand,
        opId: String,
        now: OffsetDateTime
    ): Future<List<String>> {
        val detailIds = mutableListOf<String>()
        // 计划 015：按稳定顺序（material_id、unit_spec_id）逐项处理，先锁物资+规格
        // （UnitConversionService），再写明细与库存，避免与出库/调拨路径死锁
        val ordered = command.items.sortedWith(
            compareBy<InboundItem> { it.materialId }
                .thenBy { it.unitSpecId == null }
                .thenBy { it.unitSpecId },
        )

        fun processSequentially(index: Int): Future<List<String>> {
            if (index >= ordered.size)
                return Future.succeededFuture(detailIds)

            val item = ordered[index]
            // 验证批次管控 — 融入 compose 链，不嵌套
            val batchCtrlQuery = ctx.select(Materials.MATERIALS.ENABLE_BATCH_CONTROL.`as`("material_batch_control"))
                .from(Materials.MATERIALS)
                .where(Materials.MATERIALS.ID.eq(item.materialId))

            return connection.preparedQuery(DatabaseConfig.sql(batchCtrlQuery))
                .execute(DatabaseConfig.tuple(batchCtrlQuery))
                .compose { rows ->
                    if (rows.size() == 0)
                        throw NotFoundException("material not found: ${item.materialId}")

                    val batchCtrl = rows.iterator().next().getValue(0) as? Boolean ?: false
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
                .compose { resolveInboundConversion(connection, item) }
                .compose { conversion ->
                    val detailId = Ulid.generate()
                    detailIds.add(detailId)
                    val legacy = legacyDetailColumns(conversion)

                    val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, item.materialId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, item.lotId)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, legacy.second)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, legacy.first)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, legacy.third)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, conversion.inputUnitCost)
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, conversion.totalCost)
                        .let { applyConversionSnapshots(it, conversion) }
                        .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)

                    connection.preparedQuery(DatabaseConfig.sql(insertDetail))
                        .execute(DatabaseConfig.tuple(insertDetail))
                        .compose { upsertStock(connection, command.warehouse, item.materialId, item.lotId, conversion, legacy.second) }
                }
                .compose { processSequentially(index + 1) }
        }

        return processSequentially(0)
    }

    /**
     * 新契约（unit_spec_id + input_quantity）走指定规格换算；
     * 旧契约（quantity）过渡：映射当前默认包装规格并记录弃用日志。
     */
    private fun resolveInboundConversion(
        connection: SqlConnection,
        item: InboundItem,
    ): Future<BaseQuantityCommand> {
        if (item.unitSpecId == null) {
            log.warn(
                "deprecated inbound contract: material {} submitted legacy quantity {}; mapping to current default unit spec",
                item.materialId,
                item.quantity,
            )
            return conversionService.resolvePackagePort(connection, item.materialId, item.quantity!!, item.unitCost)
        }
        return conversionService.convert(connection, item.materialId, item.unitSpecId, item.inputQuantity!!, item.unitCost)
    }

    private fun upsertStock(
        connection: SqlConnection,
        warehouse: String,
        materialId: String,
        lotId: String?,
        conversion: BaseQuantityCommand,
        addPackageQty: BigDecimal,
    ): Future<Void?> {
        val now = OffsetDateTime.now()

        val findQuery = ctx.select(
            Stocks.STOCKS.ID.`as`("stock_id"),
            Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
            Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
            Stocks.STOCKS.BASE_QUANTITY.`as`("stock_base_quantity"),
        )
            .from(Stocks.STOCKS)
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
                    val stockId = row.getValue(0)?.toString()
                    val oldQty = stockDecimalValue(row.getValue(1))
                    val oldCost = stockDecimalValue(row.getValue(2))
                    val oldBase = stockDecimalValue(row.getValue(3))

                    val updateQ = ctx.update(Stocks.STOCKS)
                        .set(Stocks.STOCKS.QUANTITY, oldQty.add(addPackageQty))
                        .set(Stocks.STOCKS.BASE_QUANTITY, oldBase.add(conversion.baseQuantity))
                        .set(Stocks.STOCKS.TOTAL_COST, oldCost.add(conversion.totalCost))
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
                        .set(Stocks.STOCKS.QUANTITY, addPackageQty)
                        .set(Stocks.STOCKS.LOCKED_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.BASE_QUANTITY, conversion.baseQuantity)
                        .set(Stocks.STOCKS.LOCKED_BASE_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.UNIT_MODEL_STATUS, "ACTIVE")
                        .set(Stocks.STOCKS.TOTAL_COST, conversion.totalCost)
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
        val ds = MaterialUnitSpecs.MATERIAL_UNIT_SPECS.`as`("ds")

        val conditions = mutableListOf<org.jooq.Condition>()
        // 计划 015：可用量以基础数量权威口径筛选；MIGRATION_BLOCKED 物资不进入可用列表
        conditions.add(s.BASE_QUANTITY.gt(s.LOCKED_BASE_QUANTITY))
        conditions.add(DSL.field("m.status").eq("ACTIVE"))
        conditions.add(DSL.field("m.unit_model_status").ne("MIGRATION_BLOCKED"))

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
            s.BASE_QUANTITY, s.LOCKED_BASE_QUANTITY, s.UNIT_MODEL_STATUS,
            DSL.field("m.code").`as`("material_code"),
            DSL.field("m.name").`as`("material_name"),
            DSL.field("m.category").`as`("material_category"),
            DSL.field("m.package_unit").`as`("package_unit"),
            DSL.field("m.split_unit").`as`("split_unit"),
            DSL.field("m.split_ratio").`as`("split_ratio"),
            DSL.field("m.base_unit").`as`("base_unit"),
            DSL.field("m.base_quantity_scale").`as`("base_quantity_scale"),
            ds.ID.`as`("default_spec_id"),
            ds.INPUT_UNIT.`as`("default_spec_unit"),
            ds.BASE_RATIO.`as`("default_spec_base_ratio"),
            DSL.field("l.batch_no").`as`("batch_no"),
            DSL.field("l.expiry_date").`as`("expiry_date")
        )

        val baseFrom = ctx.select(columns)
            .from(s)
            .join(m).on(s.MATERIAL_ID.eq(DSL.field("m.id", String::class.java)))
            .leftJoin(l).on(s.LOT_ID.eq(DSL.field("l.id", String::class.java)))
            .leftJoin(ds).on(
                ds.MATERIAL_ID.eq(s.MATERIAL_ID)
                    .and(ds.IS_DEFAULT.eq(true))
                    .and(ds.STATUS.eq("ACTIVE")),
            )
            .where(conditions)

        val countQuery = ctx.select(count().`as`("total"))
            .from(s)
            .join(m).on(s.MATERIAL_ID.eq(DSL.field("m.id", String::class.java)))
            .leftJoin(l).on(s.LOT_ID.eq(DSL.field("l.id", String::class.java)))
            .leftJoin(ds).on(
                ds.MATERIAL_ID.eq(s.MATERIAL_ID)
                    .and(ds.IS_DEFAULT.eq(true))
                    .and(ds.STATUS.eq("ACTIVE")),
            )
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
        val s = Stocks.STOCKS.`as`("s")
        val m = Materials.MATERIALS.`as`("m")
        val l = Lots.LOTS.`as`("l")
        val query = ctx.selectDistinct(s.WAREHOUSE)
            .from(s)
            .join(m).on(s.MATERIAL_ID.eq(m.ID))
            .leftJoin(l).on(s.LOT_ID.eq(l.ID))
            .where(s.BASE_QUANTITY.gt(s.LOCKED_BASE_QUANTITY))
            .and(m.STATUS.eq("ACTIVE"))
            .and(m.UNIT_MODEL_STATUS.ne("MIGRATION_BLOCKED"))
            .and(DSL.or(l.EXPIRY_DATE.isNull, l.EXPIRY_DATE.ge(java.time.LocalDate.now())))
            .orderBy(s.WAREHOUSE.asc())

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

    // ========================================================================
    //  013 护理站申领：同连接预留、释放与整单 PACKAGE 双仓调拨
    //  所有方法复用 Pharmacy 外层事务连接，自身不开启新事务。
    //  命令/结果类型定义在本服务内，由 Aceso `Main.kt` 适配器与 Pharmacy 端口互转。
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

    /**
     * 创建申领单时的只读校验：全部物资必须存在且为 ACTIVE。不锁库存、不写任何表。
     * 任一物资缺失或未启用返回 ConflictException。
     */
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

    /**
     * 审批时按稳定键 `(warehouse, material_id, lot_id)` 锁定源库存并原子增加
     * `locked_quantity`，源 `quantity` 不变。物资非 ACTIVE、批次不归属/已过期或
     * 可用量不足时返回 ConflictException，由外层事务整体回滚。
     */
    fun reservePackageStock(client: SqlClient, command: RequisitionReserveCommand): Future<Void?> {
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
        val outbound = PackageOutboundCommand(
            warehouse = warehouse,
            materialId = item.materialId,
            lotId = item.lotId,
            quantity = item.quantity,
            note = null,
        )
        // 计划 015：锁序 物资+默认规格 → 库存行；锁定按基础数量权威计量
        return loadPackageStock(client, outbound, forUpdate = false)
            .compose { preview ->
                conversionService.resolvePackagePort(client, item.materialId, item.quantity, avgUnitCost(preview))
                    .compose { conversion ->
                        loadPackageStock(client, outbound, forUpdate = true)
                            .compose { stock: PackageStock ->
                                validateMaterialAndLot(client, outbound, stock.lotId)
                                    .compose { _: Void? -> validateAvailableQuantity(stock, conversion.baseQuantity) }
                                    .compose { _: Void? ->
                                        val update = ctx.update(Stocks.STOCKS)
                                            .set(Stocks.STOCKS.LOCKED_QUANTITY, stock.lockedQuantity.add(item.quantity))
                                            .set(Stocks.STOCKS.LOCKED_BASE_QUANTITY, stock.lockedBaseQuantity.add(conversion.baseQuantity))
                                            .set(Stocks.STOCKS.LAST_UPDATED, OffsetDateTime.now())
                                            .where(Stocks.STOCKS.ID.eq(stock.id))
                                        client.preparedQuery(DatabaseConfig.sql(update))
                                            .execute(DatabaseConfig.tuple(update))
                                            .map { null as Void? }
                                    }
                            }
                    }
            }
            .compose { _: Void? -> reserveOne(client, warehouse, ordered, index + 1) }
    }

    /**
     * 取消已审批单据时释放预留：锁定源库存并原子减少 `locked_quantity`，不产生
     * 任何库存操作。预留被异常破坏（locked_quantity 不足）时返回 ConflictException。
     */
    fun releasePackageReservation(client: SqlClient, command: RequisitionReleaseCommand): Future<Void?> {
        if (command.warehouse.isBlank())
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        if (command.items.isEmpty())
            return Future.failedFuture(IllegalArgumentException("at least one item is required"))
        command.items.firstOrNull { it.materialId.isBlank() || it.quantity <= BigDecimal.ZERO }?.let {
            return Future.failedFuture(IllegalArgumentException("invalid release item: material_id and positive quantity required"))
        }
        val ordered = stableOrder(
            command.items.map { RequisitionReserveItem(it.materialId, it.lotId, it.quantity) },
        )
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
        val outbound = PackageOutboundCommand(
            warehouse = warehouse,
            materialId = item.materialId,
            lotId = item.lotId,
            quantity = item.quantity,
            note = null,
        )
        // 计划 015：释放按当前默认规格换算基础数量并对称扣减锁定。
        // 默认规格切换在存在未结算锁定时会被 MaterialService 拦截，此处换算与
        // 预留时一致。
        return loadPackageStock(client, outbound, forUpdate = false)
            .compose { preview ->
                conversionService.resolvePackagePort(client, item.materialId, item.quantity, BigDecimal.ZERO)
                    .compose { conversion ->
                        loadPackageStock(client, outbound, forUpdate = true)
                            .compose { stock: PackageStock ->
                                if (stock.lockedQuantity < item.quantity || stock.lockedBaseQuantity < conversion.baseQuantity) {
                                    Future.failedFuture(
                                        ConflictException(
                                            "reservation corrupted: ${stock.lockedQuantity} locked / ${stock.lockedBaseQuantity} base locked, releasing ${item.quantity}",
                                        ),
                                    )
                                } else {
                                    val update = ctx.update(Stocks.STOCKS)
                                        .set(Stocks.STOCKS.LOCKED_QUANTITY, stock.lockedQuantity.subtract(item.quantity))
                                        .set(Stocks.STOCKS.LOCKED_BASE_QUANTITY, stock.lockedBaseQuantity.subtract(conversion.baseQuantity))
                                        .set(Stocks.STOCKS.LAST_UPDATED, OffsetDateTime.now())
                                        .where(Stocks.STOCKS.ID.eq(stock.id))
                                    client.preparedQuery(DatabaseConfig.sql(update))
                                        .execute(DatabaseConfig.tuple(update))
                                        .map { null as Void? }
                                }
                            }
                    }
            }
            .compose { _: Void? -> releaseOne(client, warehouse, ordered, index + 1) }
    }

    /**
     * 确认调拨：锁全部源/目标库存（稳定顺序），写一张源 OUTBOUND 与一张目标
     * INBOUND 操作及逐项双向明细，扣减源库存、增加目标库存，成本守恒。
     * 目标库存行不存在时以并发安全 upsert/冲突重读创建；任一失败由外层事务整体回滚。
     */
    fun confirmReservedPackageTransfer(client: SqlClient, command: RequisitionTransferCommand): Future<RequisitionTransferResult> {
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
        val ordered = stableOrder(
            command.items.map { RequisitionReserveItem(it.materialId, it.lotId, it.quantity) },
        )
        // 计划 015：先按稳定顺序解析全部默认规格换算（锁物资+规格），再锁源/目标库存
        return resolveConversions(client, command.sourceWarehouse, ordered, 0, emptyList())
            .compose { conversions: List<BaseQuantityCommand> ->
                lockAllSources(client, command, ordered, conversions, 0, emptyList())
                    .compose { sources: List<PackageStock> ->
                        prepareTargets(client, command.destinationWarehouse, ordered, 0, emptyList())
                            .compose { targets: List<Pair<PackageStock, RequisitionReserveItem>> ->
                                writeTransfer(client, command, ordered, sources, targets, conversions, OffsetDateTime.now())
                            }
                    }
            }
    }

    /**
     * 计划 015：按稳定顺序（material_id 升序）为每项解析当前默认包装规格，
     * 生成基础数量写入命令。平均包装成本以无锁库存预览推导；解析本身
     * FOR UPDATE 锁定物资+规格行。
     */
    private fun resolveConversions(
        client: SqlClient,
        warehouse: String,
        ordered: List<RequisitionReserveItem>,
        index: Int,
        acc: List<BaseQuantityCommand>,
    ): Future<List<BaseQuantityCommand>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        val outbound = PackageOutboundCommand(
            warehouse = warehouse,
            materialId = item.materialId,
            lotId = item.lotId,
            quantity = item.quantity,
            note = null,
        )
        return loadPackageStock(client, outbound, forUpdate = false)
            .compose { preview ->
                conversionService.resolvePackagePort(client, item.materialId, item.quantity, avgUnitCost(preview))
                    .compose { conversion ->
                        resolveConversions(client, warehouse, ordered, index + 1, acc + conversion)
                    }
            }
    }

    private fun lockAllSources(
        client: SqlClient,
        command: RequisitionTransferCommand,
        ordered: List<RequisitionReserveItem>,
        conversions: List<BaseQuantityCommand>,
        index: Int,
        acc: List<PackageStock>,
    ): Future<List<PackageStock>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        val conversion = conversions[index]
        val outbound = PackageOutboundCommand(
            warehouse = command.sourceWarehouse,
            materialId = item.materialId,
            lotId = item.lotId,
            quantity = item.quantity,
            note = null,
        )
        return loadPackageStock(client, outbound, forUpdate = true)
            .compose { stock: PackageStock ->
                validateMaterialAndLot(client, outbound, stock.lotId)
                    .compose { _: Void? ->
                        if (stock.lockedQuantity < item.quantity || stock.lockedBaseQuantity < conversion.baseQuantity) {
                            Future.failedFuture(
                                ConflictException(
                                    "insufficient reservation: only ${stock.lockedQuantity} locked / ${stock.lockedBaseQuantity} base locked, required ${item.quantity}",
                                ),
                            )
                        } else {
                            lockAllSources(client, command, ordered, conversions, index + 1, acc + stock)
                        }
                    }
            }
    }

    private fun prepareTargets(
        client: SqlClient,
        destinationWarehouse: String,
        ordered: List<RequisitionReserveItem>,
        index: Int,
        acc: List<Pair<PackageStock, RequisitionReserveItem>>,
    ): Future<List<Pair<PackageStock, RequisitionReserveItem>>> {
        if (index >= ordered.size) return Future.succeededFuture(acc)
        val item = ordered[index]
        return ensureTargetStock(client, destinationWarehouse, item.materialId, item.lotId, OffsetDateTime.now())
            .compose { target: PackageStock ->
                prepareTargets(client, destinationWarehouse, ordered, index + 1, acc + Pair(target, item))
            }
    }

    private fun validateReserveInput(command: RequisitionReserveCommand): IllegalArgumentException? = when {
        command.warehouse.isBlank() -> IllegalArgumentException("warehouse is required")
        command.items.isEmpty() -> IllegalArgumentException("at least one item is required")
        command.items.firstOrNull { it.materialId.isBlank() || it.quantity <= BigDecimal.ZERO } != null ->
            IllegalArgumentException("invalid reserve item: material_id and positive quantity required")
        else -> null
    }

    /** 稳定顺序：material_id 升序，lot_id 空值在前，避免死锁且不依赖客户端明细顺序 */
    private fun stableOrder(items: List<RequisitionReserveItem>): List<RequisitionReserveItem> =
        items.sortedWith(
            compareBy<RequisitionReserveItem> { it.materialId }
                .thenBy { it.lotId == null }
                .thenBy { it.lotId },
        )

    /**
     * 目标库存：先 SELECT FOR UPDATE；不存在则 INSERT，唯一冲突（部分唯一索引）时
     * 冲突重读后再锁定并 UPDATE，避免“先 SELECT、未找到再裸 INSERT”的并发竞争。
     */
    private fun ensureTargetStock(
        client: SqlClient,
        warehouse: String,
        materialId: String,
        lotId: String?,
        now: OffsetDateTime,
    ): Future<PackageStock> {
        val findQuery = ctx.select(
            Stocks.STOCKS.ID.`as`("stock_id"),
            Stocks.STOCKS.LOT_ID.`as`("stock_lot_id"),
            Stocks.STOCKS.QUANTITY.`as`("stock_quantity"),
            Stocks.STOCKS.LOCKED_QUANTITY.`as`("stock_locked_quantity"),
            Stocks.STOCKS.TOTAL_COST.`as`("stock_total_cost"),
            Stocks.STOCKS.BASE_QUANTITY.`as`("stock_base_quantity"),
            Stocks.STOCKS.LOCKED_BASE_QUANTITY.`as`("stock_locked_base_quantity"),
        )
            .from(Stocks.STOCKS)
            .where(Stocks.STOCKS.WAREHOUSE.eq(warehouse).and(Stocks.STOCKS.MATERIAL_ID.eq(materialId)))
            .let { q ->
                if (lotId != null) q.and(Stocks.STOCKS.LOT_ID.eq(lotId))
                else q.and(Stocks.STOCKS.LOT_ID.isNull)
            }
            .forUpdate()

        fun toStock(row: Row): PackageStock =
            PackageStock(
                id = row.getValue(0)?.toString() ?: "",
                lotId = row.getValue(1)?.toString(),
                quantity = stockDecimalValue(row.getValue(2)),
                lockedQuantity = stockDecimalValue(row.getValue(3)),
                totalCost = stockDecimalValue(row.getValue(4)),
                baseQuantity = stockDecimalValue(row.getValue(5)),
                lockedBaseQuantity = stockDecimalValue(row.getValue(6)),
            )

        fun readLocked(): Future<PackageStock> =
            client.preparedQuery(DatabaseConfig.sql(findQuery))
                .execute(DatabaseConfig.tuple(findQuery))
                .compose { rows: RowSet<Row> ->
                    if (rows.size() == 0) {
                        return@compose Future.failedFuture(
                            ConflictException("target stock disappeared during transfer for material $materialId"),
                        )
                    }
                    Future.succeededFuture(toStock(rows.iterator().next()))
                }

        return client.preparedQuery(DatabaseConfig.sql(findQuery))
            .execute(DatabaseConfig.tuple(findQuery))
            .compose { rows: RowSet<Row> ->
                if (rows.size() > 0) {
                    Future.succeededFuture(toStock(rows.iterator().next()))
                } else {
                    val stockId = Ulid.generate()
                    val insertQ = ctx.insertInto(Stocks.STOCKS)
                        .set(Stocks.STOCKS.ID, stockId)
                        .set(Stocks.STOCKS.WAREHOUSE, warehouse)
                        .set(Stocks.STOCKS.MATERIAL_ID, materialId)
                        .set(Stocks.STOCKS.LOT_ID, lotId)
                        .set(Stocks.STOCKS.QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.LOCKED_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.BASE_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.LOCKED_BASE_QUANTITY, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.UNIT_MODEL_STATUS, "ACTIVE")
                        .set(Stocks.STOCKS.TOTAL_COST, BigDecimal.ZERO)
                        .set(Stocks.STOCKS.LAST_UPDATED, now)
                    val insertOutcome: Future<PackageStock> = client.preparedQuery(DatabaseConfig.sql(insertQ))
                        .execute(DatabaseConfig.tuple(insertQ))
                        .compose { _: RowSet<Row> ->
                            Future.succeededFuture(
                                PackageStock(stockId, lotId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                            )
                        }
                    fun onInsertError(error: Throwable): Future<PackageStock> {
                        if (error is PgException && error.sqlState == "23505") {
                            return readLocked()
                        }
                        return Future.failedFuture(error)
                    }
                    insertOutcome.recover(::onInsertError)
                }
            }
    }

    private fun writeTransfer(
        client: SqlClient,
        command: RequisitionTransferCommand,
        ordered: List<RequisitionReserveItem>,
        sources: List<PackageStock>,
        targets: List<Pair<PackageStock, RequisitionReserveItem>>,
        conversions: List<BaseQuantityCommand>,
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
            val conversion = conversions[index]
            return writeTransferDetail(client, outboundOpId, item, conversion, now)
                .compose { outboundDetailId: String ->
                    writeTransferDetail(client, inboundOpId, item, conversion, now)
                        .map { inboundDetailId: String ->
                            itemResults.add(
                                RequisitionTransferItemResult(
                                    materialId = item.materialId,
                                    lotId = item.lotId,
                                    outboundStockOperationDetailId = outboundDetailId,
                                    inboundStockOperationDetailId = inboundDetailId,
                                    unitCost = conversion.inputUnitCost,
                                ),
                            )
                        }
                }
                .compose { _: Boolean -> writeItems(index + 1) }
        }

        fun updateSources(index: Int): Future<Void?> {
            if (index >= ordered.size) return Future.succeededFuture(null)
            val item = ordered[index]
            val source = sources[index]
            val conversion = conversions[index]
            val update = ctx.update(Stocks.STOCKS)
                .set(Stocks.STOCKS.QUANTITY, source.quantity.subtract(item.quantity))
                .set(Stocks.STOCKS.BASE_QUANTITY, source.baseQuantity.subtract(conversion.baseQuantity))
                .set(Stocks.STOCKS.LOCKED_QUANTITY, source.lockedQuantity.subtract(item.quantity))
                .set(Stocks.STOCKS.LOCKED_BASE_QUANTITY, source.lockedBaseQuantity.subtract(conversion.baseQuantity))
                .set(Stocks.STOCKS.TOTAL_COST, source.totalCost.subtract(conversion.totalCost).max(BigDecimal.ZERO))
                .set(Stocks.STOCKS.LAST_UPDATED, now)
                .where(Stocks.STOCKS.ID.eq(source.id))
            return client.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { _: RowSet<Row> -> updateSources(index + 1) }
        }

        fun updateTargets(index: Int): Future<Void?> {
            if (index >= ordered.size) return Future.succeededFuture(null)
            val item = ordered[index]
            val target = targets[index].first
            val conversion = conversions[index]
            val update = ctx.update(Stocks.STOCKS)
                .set(Stocks.STOCKS.QUANTITY, target.quantity.add(item.quantity))
                .set(Stocks.STOCKS.BASE_QUANTITY, target.baseQuantity.add(conversion.baseQuantity))
                .set(Stocks.STOCKS.TOTAL_COST, target.totalCost.add(conversion.totalCost))
                .set(Stocks.STOCKS.LAST_UPDATED, now)
                .where(Stocks.STOCKS.ID.eq(target.id))
            return client.preparedQuery(DatabaseConfig.sql(update))
                .execute(DatabaseConfig.tuple(update))
                .compose { _: RowSet<Row> -> updateTargets(index + 1) }
        }

        return client.preparedQuery(DatabaseConfig.sql(insertOutboundOp))
            .execute(DatabaseConfig.tuple(insertOutboundOp))
            .compose { _: RowSet<Row> -> client.preparedQuery(DatabaseConfig.sql(insertInboundOp)).execute(DatabaseConfig.tuple(insertInboundOp)) }
            .compose { _: RowSet<Row> -> writeItems(0) }
            .compose { items: List<RequisitionTransferItemResult> -> updateSources(0).map { _: Void? -> items } }
            .compose { items: List<RequisitionTransferItemResult> -> updateTargets(0).map { _: Void? -> items } }
            .map { _: List<RequisitionTransferItemResult> ->
                RequisitionTransferResult(
                    outboundOperationId = outboundOpId,
                    inboundOperationId = inboundOpId,
                    items = itemResults,
                )
            }
    }

    private fun writeTransferDetail(
        client: SqlClient,
        operationId: String,
        item: RequisitionReserveItem,
        conversion: BaseQuantityCommand,
        now: OffsetDateTime,
    ): Future<String> {
        val detailId = Ulid.generate()
        val legacy = legacyDetailColumns(conversion)
        val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, operationId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, item.materialId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, item.lotId)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, legacy.second)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, legacy.first)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, legacy.third)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, conversion.inputUnitCost)
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, conversion.totalCost)
            .let { applyConversionSnapshots(it, conversion) }
            .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)
        return client.preparedQuery(DatabaseConfig.sql(insertDetail))
            .execute(DatabaseConfig.tuple(insertDetail))
            .map { detailId }
    }

    // ========================================================================
    //  014 药房采购：供应商收货批量入库
    //  一次收货只写一张 INBOUND 操作与逐条明细；批次与目标库存行按稳定键解析/
    //  创建，唯一冲突后重读并比对事实；quantity/total_cost 累加，locked 不变。
    //  复用 Pharmacy 外层事务连接，自身不开启新事务。
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
        val lotId: String?,
        val stock: PackageStock,
        /** 计划 015：当前默认包装规格换算快照（写明细与基础结存的事实来源） */
        val conversion: BaseQuantityCommand,
    )

    /**
     * 创建/编辑采购订单时的只读校验：全部物资必须存在且为 ACTIVE。不锁库存、不写
     * 任何表；任一物资缺失或未启用返回 ConflictException。
     */
    fun validatePurchaseMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> {
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

    /**
     * 采购收货批量入库：校验物资 ACTIVE 与批次规则，解析/创建批次与目标库存行
     * （稳定顺序，避免死锁），随后写一张 INBOUND 操作、逐条明细并累加库存。
     * 任一步失败由外层事务整体回滚；自身不启动新事务。
     */
    fun confirmPackagePurchaseReceipt(client: SqlClient, command: PurchaseReceiptCommand): Future<PurchaseReceiptResult> {
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

    /** 稳定顺序：material_id 升序、batch_no 空值在前，不依赖客户端明细顺序 */
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
            .compose { resolved: PurchaseResolvedItem ->
                resolveAll(client, command, ordered, index + 1, acc + resolved)
            }
    }

    private fun resolvePurchaseItem(
        client: SqlClient,
        command: PurchaseReceiptCommand,
        item: PurchaseReceiptItem,
    ): Future<PurchaseResolvedItem> {
        val materialQuery = ctx.select(
            Materials.MATERIALS.STATUS.`as`("material_status"),
            Materials.MATERIALS.ENABLE_BATCH_CONTROL.`as`("material_batch_control"),
        )
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.eq(item.materialId))
        return client.preparedQuery(DatabaseConfig.sql(materialQuery))
            .execute(DatabaseConfig.tuple(materialQuery))
            .compose { matRows ->
                if (matRows.size() == 0)
                    throw NotFoundException("material not found: ${item.materialId}")
                val matRow = matRows.iterator().next()
                if (matRow.getValue(0)?.toString() != "ACTIVE")
                    throw ConflictException("material ${item.materialId} is not ACTIVE")
                val batchControlled = matRow.getValue(1) as? Boolean ?: false
                val now = OffsetDateTime.now()
                val lotFuture: Future<String?> = if (batchControlled) {
                    if (item.batchNo.isNullOrBlank())
                        throw ConflictException("material ${item.materialId} requires a batch_no")
                    if (item.expiryDate == null || !item.expiryDate.isAfter(LocalDate.now()))
                        throw ConflictException("material ${item.materialId} requires expiry_date later than today")
                    ensureLot(client, item.materialId, item.batchNo, item.productionDate, item.expiryDate, item.manufacturer, command.supplierName)
                        .map { lotId: String -> lotId }
                } else {
                    if (item.batchNo != null || item.productionDate != null || item.expiryDate != null || item.manufacturer != null)
                        throw ConflictException("material ${item.materialId} does not use batch control")
                    Future.succeededFuture(null)
                }
                // 计划 015：先解析当前默认包装规格（锁物资+规格），再解析/创建批次与
                // 目标库存行 —— 锁序：物资+规格 → 批次 → 库存行
                conversionService.resolvePackagePort(client, item.materialId, item.quantity, item.unitCost)
                    .compose { conversion ->
                        lotFuture.compose { lotId: String? ->
                            ensureTargetStock(client, command.warehouse, item.materialId, lotId, now)
                                .map { stock: PackageStock ->
                                    PurchaseResolvedItem(item = item, lotId = lotId, stock = stock, conversion = conversion)
                                }
                        }
                    }
            }
    }

    /**
     * 批次解析/创建：同一 material_id + batch_no 复用既有批次或新建；存在时校验
     * 事实（生产/有效期/生产企业/供应商）不矛盾、未过期，矛盾返回 ConflictException，
     * 绝不覆盖旧批次字段。并发首次创建由 UNIQUE(material_id, batch_no) 兜底，
     * 23505 后重读并比对事实。
     */
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
            val conversion = entry.conversion
            val legacy = legacyDetailColumns(conversion)
            val detailId = Ulid.generate()
            val insertDetail = ctx.insertInto(StockOperationDetails.STOCK_OPERATION_DETAILS)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.ID, detailId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.OPERATION_ID, opId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.MATERIAL_ID, item.materialId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.LOT_ID, entry.lotId)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.QUANTITY, legacy.second)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT, legacy.first)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.SPLIT_QUANTITY, legacy.third)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.UNIT_COST, conversion.inputUnitCost)
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.TOTAL_COST, conversion.totalCost)
                .let { applyConversionSnapshots(it, conversion) }
                .set(StockOperationDetails.STOCK_OPERATION_DETAILS.CREATED_AT, now)
            val updateStock = ctx.update(Stocks.STOCKS)
                .set(Stocks.STOCKS.QUANTITY, entry.stock.quantity.add(item.quantity))
                .set(Stocks.STOCKS.BASE_QUANTITY, entry.stock.baseQuantity.add(conversion.baseQuantity))
                .set(Stocks.STOCKS.TOTAL_COST, entry.stock.totalCost.add(conversion.totalCost))
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
                                    unitCost = conversion.inputUnitCost,
                                    totalCost = conversion.totalCost,
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
            // 计划 015：基础数量为权威口径；旧 quantity 系列字段为过渡期只读投影
            // （以当前默认包装规格从基础数量投影，量化 4 位）
            val baseQty = stockDecimalValueOrNull(row.getValue("base_quantity"))
            val lockedBase = stockDecimalValueOrNull(row.getValue("locked_base_quantity"))
            val defaultRatio = stockDecimalValueOrNull(row.getValue("default_spec_base_ratio"))
            val validRatio = defaultRatio != null && defaultRatio > BigDecimal.ZERO

            val projectedQty: BigDecimal? =
                if (baseQty != null && validRatio) baseQty.divide(defaultRatio, 4, RoundingMode.HALF_UP) else null
            val projectedLocked: BigDecimal? =
                if (lockedBase != null && validRatio) lockedBase.divide(defaultRatio, 4, RoundingMode.HALF_UP) else null

            val qty = projectedQty ?: stockDecimalValue(row.getValue("quantity"))
            val locked = projectedLocked ?: stockDecimalValue(row.getValue("locked_quantity"))
            val available = qty.subtract(locked)
            val totalCost = stockDecimalValue(row.getValue("total_cost"))
            val unitCost = if (qty.compareTo(BigDecimal.ZERO) > 0)
                totalCost.divide(qty, 4, RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            val availableBase = if (baseQty != null && lockedBase != null) baseQty.subtract(lockedBase) else null

            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("material_code", row.getValue("material_code")?.toString())
                .put("material_name", row.getValue("material_name")?.toString())
                .put("category", row.getValue("material_category")?.toString())
                .put("package_unit", row.getValue("package_unit")?.toString())
                .put("split_unit", row.getValue("split_unit")?.toString())
                .put("split_ratio", stockDecimalValueOrNull(row.getValue("split_ratio"))?.toDouble())
                .put("base_unit", row.getValue("base_unit")?.toString())
                .put("base_quantity", baseQty?.toDouble())
                .put("locked_base_quantity", lockedBase?.toDouble())
                .put("available_base_quantity", availableBase?.toDouble())
                .put("unit_model_status", row.getValue("unit_model_status")?.toString())
                .put("default_spec_id", row.getValue("default_spec_id")?.toString())
                .put("default_spec_unit", row.getValue("default_spec_unit")?.toString())
                .put("default_spec_ratio", defaultRatio?.toDouble())
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
                .put("quantity", stockDecimalValueOrNull(row.getValue("quantity"))?.toDouble())
                .put("unit", row.getValue("unit")?.toString())
                .put("split_quantity", stockDecimalValueOrNull(row.getValue("split_quantity"))?.toDouble())
                .put("unit_cost", stockDecimalValueOrNull(row.getValue("unit_cost"))?.toDouble())
                .put("total_cost", stockDecimalValueOrNull(row.getValue("total_cost"))?.toDouble())
                // 计划 015 快照列（权威事实；历史行可能为 null）
                .put("unit_spec_id", row.getValue("unit_spec_id")?.toString())
                .put("input_quantity", stockDecimalValueOrNull(row.getValue("input_quantity"))?.toDouble())
                .put("input_unit", row.getValue("input_unit")?.toString())
                .put("conversion_ratio", stockDecimalValueOrNull(row.getValue("conversion_ratio"))?.toDouble())
                .put("base_quantity", stockDecimalValueOrNull(row.getValue("base_quantity"))?.toDouble())
                .put("base_unit", row.getValue("base_unit")?.toString())
                .put("input_unit_cost", stockDecimalValueOrNull(row.getValue("input_unit_cost"))?.toDouble())
                .put("base_unit_cost", stockDecimalValueOrNull(row.getValue("base_unit_cost"))?.toDouble())
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }

}
