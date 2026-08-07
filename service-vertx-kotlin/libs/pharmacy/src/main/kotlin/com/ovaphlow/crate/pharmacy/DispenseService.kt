package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyDispenseItems.PHARMACY_DISPENSE_ITEMS
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyDispenses.PHARMACY_DISPENSES
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 011 发药服务：待接方用药医嘱、从医嘱接方、审方/调配/发药确认/取消。
 *
 * - 患者、入住、药名、医生和医嘱内容全部从锁定医嘱读取，不接受请求体覆盖；
 * - 创建、状态动作和发药确认全部在单一数据库事务内完成；
 * - 发药确认通过 [InventoryOutboundPort] 同连接出库并回写 `stock_operation_detail_id`；
 * - 旧通用创建入口禁止 `ELDERLY_ROUTINE`，该类型只能走 `from-medical-order`。
 */
class DispenseService(
    private val pool: Pool,
    private val medicalOrderReader: MedicalOrderReader,
    private val inventoryOutboundPort: InventoryOutboundPort,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "PENDING" to listOf("REVIEWED", "CANCELLED"),
            "REVIEWED" to listOf("DISPENSING", "CANCELLED"),
            "DISPENSING" to listOf("DISPENSED", "CANCELLED"),
            "DISPENSED" to emptyList(),
            "CANCELLED" to emptyList(),
        )

        private val VALID_DISPENSE_TYPES = setOf(
            "OUTPATIENT", "INPATIENT", "WARD_BATCH", "ELDERLY_ROUTINE",
        )

        private const val ELDERLY_ROUTINE = "ELDERLY_ROUTINE"

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("dispense_no", row.getValue("dispense_no")?.toString())
                .put("patient_id", row.getValue("patient_id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("dispense_type", row.getValue("dispense_type")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("pharmacist", row.getValue("pharmacist")?.toString())
                .put("reviewer", row.getValue("reviewer")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("dispensed_at", row.getValue("dispensed_at")?.toString())
        }

        fun itemToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("dispense_id", row.getValue("dispense_id")?.toString())
                .put("order_item_id", row.getValue("order_item_id")?.toString())
                .put("order_execution_id", row.getValue("order_execution_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("prescribed_quantity", (row.getValue("prescribed_quantity") as? BigDecimal)?.toDouble())
                .put("dispensed_quantity", (row.getValue("dispensed_quantity") as? BigDecimal)?.toDouble())
                .put("unit", row.getValue("unit")?.toString())
                .put("split_quantity", (row.getValue("split_quantity") as? BigDecimal)?.toDouble())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("unit_cost", (row.getValue("unit_cost") as? BigDecimal)?.toDouble())
                .put("total_cost", (row.getValue("total_cost") as? BigDecimal)?.toDouble())
                .put("metadata", row.getValue("metadata") as? JsonObject)
        }
    }

    // ========================================================================
    //  4.1 待接方用药医嘱
    // ========================================================================

    /**
     * 只返回活动养老入住下的 ACTIVE MEDICATION 医嘱；已存在未取消发药单的医嘱
     * 附带 dispense_id / dispense_status（页面显示已接方）。读取不写医嘱、不锁库存。
     */
    fun listMedicationOrders(
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        return medicalOrderReader.listMedicationOrders(pool, encounterId, search, limit, offset)
            .compose { result ->
                val orderIds = result.getJsonArray("records").mapNotNull { it as? JsonObject }
                    .mapNotNull { it.getString("order_id") }
                if (orderIds.isEmpty()) {
                    return@compose Future.succeededFuture(result)
                }
                val dispenseQuery = ctx.select(
                    PHARMACY_DISPENSE_ITEMS.ORDER_ITEM_ID,
                    PHARMACY_DISPENSES.ID.`as`("dispense_id"),
                    PHARMACY_DISPENSES.STATUS.`as`("dispense_status"),
                )
                    .from(PHARMACY_DISPENSE_ITEMS)
                    .join(PHARMACY_DISPENSES)
                    .on(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID.eq(PHARMACY_DISPENSES.ID))
                    .where(PHARMACY_DISPENSE_ITEMS.ORDER_ITEM_ID.`in`(orderIds))
                    .and(PHARMACY_DISPENSES.STATUS.ne("CANCELLED"))
                    .orderBy(PHARMACY_DISPENSES.CREATED_AT.desc())
                pool.preparedQuery(DatabaseConfig.sql(dispenseQuery))
                    .execute(DatabaseConfig.tuple(dispenseQuery))
                    .map { rows ->
                        val byOrder = LinkedHashMap<String, Pair<String, String>>()
                        for (row in rows) {
                            val orderId = row.getValue("order_item_id")?.toString() ?: continue
                            if (!byOrder.containsKey(orderId)) {
                                byOrder[orderId] =
                                    (row.getValue("dispense_id")?.toString() ?: "") to
                                        (row.getValue("dispense_status")?.toString() ?: "")
                            }
                        }
                        val records = JsonArray()
                        for (entry in result.getJsonArray("records")) {
                            val record = entry as JsonObject
                            val linked = byOrder[record.getString("order_id")]
                            record.put("dispense_id", linked?.first)
                                .put("dispense_status", linked?.second)
                            records.add(record)
                        }
                        JsonObject().put("records", records).put("meta", result.getJsonObject("meta"))
                    }
            }
    }

    // ========================================================================
    //  4.2 从医嘱创建发药单
    // ========================================================================

    fun createFromMedicalOrder(body: JsonObject): Future<JsonObject> {
        val medicalOrderId = body.getString("medical_order_id")?.trim().orEmpty()
        val warehouse = body.getString("warehouse")?.trim().orEmpty()
        val materialId = body.getString("material_id")?.trim().orEmpty()
        val unit = body.getString("unit") ?: "PACKAGE"
        val dispensedQuantity = body.getDouble("dispensed_quantity")
        val lotId = body.getString("lot_id")?.trim()?.takeIf(String::isNotBlank)

        if (medicalOrderId.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("medical_order_id is required"))
        }
        if (warehouse.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("warehouse is required"))
        }
        if (materialId.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("material_id is required"))
        }
        if (dispensedQuantity == null || dispensedQuantity <= 0) {
            return Future.failedFuture(IllegalArgumentException("dispensed_quantity must be positive"))
        }
        if (unit != "PACKAGE") {
            return Future.failedFuture(IllegalArgumentException("only PACKAGE unit is supported in this version"))
        }

        return pool.withTransaction<JsonObject> { connection ->
            medicalOrderReader.lockMedicationOrder(connection, medicalOrderId)
                .compose { snapshot ->
                    validateOrderForDispensing(snapshot)
                        .compose {
                            inventoryOutboundPort.validatePackageOutbound(
                                connection,
                                PackageOutboundCommand(
                                    warehouse = warehouse,
                                    materialId = materialId,
                                    lotId = lotId,
                                    quantity = BigDecimal.valueOf(dispensedQuantity),
                                    note = "pharmacy dispense for medical order $medicalOrderId",
                                ),
                            )
                        }
                        .compose { rejectDuplicate(connection, medicalOrderId) }
                        .compose { insertDispenseAndItem(connection, snapshot, warehouse, materialId, lotId, BigDecimal.valueOf(dispensedQuantity)) }
                }
        }
    }

    private fun validateOrderForDispensing(snapshot: MedicationOrderSnapshot): Future<Void?> {
        if (snapshot.encounterType != "ELDERLY_CARE") {
            return Future.failedFuture(IllegalArgumentException("order is not under an elderly admission"))
        }
        if (snapshot.encounterStatus != "ACTIVE") {
            return Future.failedFuture(ConflictException("encounter is not active"))
        }
        if (snapshot.orderType != "MEDICATION") {
            return Future.failedFuture(IllegalArgumentException("order is not a medication order"))
        }
        if (snapshot.orderStatus != "ACTIVE") {
            return Future.failedFuture(ConflictException("order is not active: ${snapshot.orderStatus}"))
        }
        return Future.succeededFuture(null)
    }

    /**
     * 同一医嘱已有未取消发药单时拒绝重复接方。并发安全依赖外层已锁定的医嘱行：
     * 同一医嘱的并发创建在医嘱行锁上串行，后到的事务必然看到先到事务提交的发药单。
     */
    private fun rejectDuplicate(connection: SqlConnection, medicalOrderId: String): Future<Void?> {
        val query = ctx.select(PHARMACY_DISPENSE_ITEMS.ID)
            .from(PHARMACY_DISPENSE_ITEMS)
            .join(PHARMACY_DISPENSES)
            .on(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID.eq(PHARMACY_DISPENSES.ID))
            .where(PHARMACY_DISPENSE_ITEMS.ORDER_ITEM_ID.eq(medicalOrderId))
            .and(PHARMACY_DISPENSES.STATUS.ne("CANCELLED"))
            .limit(1)
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() > 0) {
                    Future.failedFuture(ConflictException("order already has a non-cancelled dispense: $medicalOrderId"))
                } else {
                    Future.succeededFuture(null)
                }
            }
    }

    private fun insertDispenseAndItem(
        connection: SqlConnection,
        snapshot: MedicationOrderSnapshot,
        warehouse: String,
        materialId: String,
        lotId: String?,
        dispensedQuantity: BigDecimal,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val headerId = Ulid.generate()
        val itemId = Ulid.generate()
        val dispenseNo = "DS-$headerId"

        val headerInsert = ctx.insertInto(PHARMACY_DISPENSES)
            .set(PHARMACY_DISPENSES.ID, headerId)
            .set(PHARMACY_DISPENSES.DISPENSE_NO, dispenseNo)
            .set(PHARMACY_DISPENSES.PATIENT_ID, snapshot.patientId)
            .set(PHARMACY_DISPENSES.ENCOUNTER_ID, snapshot.encounterId)
            .set(PHARMACY_DISPENSES.DISPENSE_TYPE, ELDERLY_ROUTINE)
            .set(PHARMACY_DISPENSES.STATUS, "PENDING")
            .set(PHARMACY_DISPENSES.WAREHOUSE, warehouse)
            .set(PHARMACY_DISPENSES.METADATA, JSONB.valueOf(
                JsonObject()
                    .put("medical_order_id", snapshot.orderId)
                    .put("drug_name", snapshot.orderDetails.getString("drug_name"))
                    .put("order_content", snapshot.orderContent)
                    .put("doctor", snapshot.doctor)
                    .encode(),
            ))
            .set(PHARMACY_DISPENSES.CREATED_AT, now)

        val itemInsert = ctx.insertInto(PHARMACY_DISPENSE_ITEMS)
            .set(PHARMACY_DISPENSE_ITEMS.ID, itemId)
            .set(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID, headerId)
            .set(PHARMACY_DISPENSE_ITEMS.ORDER_ITEM_ID, snapshot.orderId)
            .set(PHARMACY_DISPENSE_ITEMS.MATERIAL_ID, materialId)
            .set(PHARMACY_DISPENSE_ITEMS.LOT_ID, lotId)
            .set(PHARMACY_DISPENSE_ITEMS.DISPENSED_QUANTITY, dispensedQuantity)
            .set(PHARMACY_DISPENSE_ITEMS.UNIT, "PACKAGE")

        return connection.preparedQuery(DatabaseConfig.sql(headerInsert))
            .execute(DatabaseConfig.tuple(headerInsert))
            .compose { connection.preparedQuery(DatabaseConfig.sql(itemInsert)).execute(DatabaseConfig.tuple(itemInsert)) }
            .map {
                JsonObject()
                    .put("id", headerId)
                    .put("dispense_no", dispenseNo)
                    .put("patient_id", snapshot.patientId)
                    .put("encounter_id", snapshot.encounterId)
                    .put("dispense_type", ELDERLY_ROUTINE)
                    .put("status", "PENDING")
                    .put("warehouse", warehouse)
                    .put("created_at", now.toString())
                    .put("items", JsonArray().add(
                        JsonObject()
                            .put("id", itemId)
                            .put("dispense_id", headerId)
                            .put("order_item_id", snapshot.orderId)
                            .put("material_id", materialId)
                            .put("lot_id", lotId)
                            .put("dispensed_quantity", dispensedQuantity.toDouble())
                            .put("unit", "PACKAGE"),
                    ))
            }
    }

    // ========================================================================
    //  4.3 审方、开始调配、发药确认和取消
    // ========================================================================

    fun review(id: String, body: JsonObject): Future<JsonObject> {
        val operator = try {
            requiredOperator(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockDispense(connection, id).compose { current ->
                if (current.getString("status") != "PENDING") {
                    return@compose Future.failedFuture(
                        ConflictException("cannot review dispense in status ${current.getString("status")}"),
                    )
                }
                val updateQuery = ctx.update(PHARMACY_DISPENSES)
                    .set(PHARMACY_DISPENSES.REVIEWER, operator)
                    .set(PHARMACY_DISPENSES.STATUS, "REVIEWED")
                    .where(PHARMACY_DISPENSES.ID.eq(id))
                connection.preparedQuery(DatabaseConfig.sql(updateQuery))
                    .execute(DatabaseConfig.tuple(updateQuery))
                    .compose { get(connection, id) }
            }
        }
    }

    fun start(id: String, body: JsonObject): Future<JsonObject> {
        val operator = try {
            requiredOperator(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockDispense(connection, id).compose { current ->
                if (current.getString("status") != "REVIEWED") {
                    return@compose Future.failedFuture(
                        ConflictException("cannot start dispense in status ${current.getString("status")}"),
                    )
                }
                val updateQuery = ctx.update(PHARMACY_DISPENSES)
                    .set(PHARMACY_DISPENSES.PHARMACIST, operator)
                    .set(PHARMACY_DISPENSES.STATUS, "DISPENSING")
                    .where(PHARMACY_DISPENSES.ID.eq(id))
                connection.preparedQuery(DatabaseConfig.sql(updateQuery))
                    .execute(DatabaseConfig.tuple(updateQuery))
                    .compose { get(connection, id) }
            }
        }
    }

    /**
     * 发药确认：锁定药房单、明细和医嘱，调用同连接库存出库，回写
     * `stock_operation_detail_id`，最后置为 DISPENSED —— 全部在同一事务内提交或回滚。
     * 已是 DISPENSED 的重试直接返回已有结果，不重复扣库存。
     */
    fun confirm(id: String, body: JsonObject): Future<JsonObject> {
        return pool.withTransaction<JsonObject> { connection ->
            lockDispense(connection, id).compose { current ->
                if (current.getString("status") == "DISPENSED") {
                    return@compose get(connection, id)
                }
                if (current.getString("status") != "DISPENSING") {
                    return@compose Future.failedFuture(
                        ConflictException("cannot confirm dispense in status ${current.getString("status")}"),
                    )
                }
                val warehouse = current.getString("warehouse")
                if (warehouse.isNullOrBlank()) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("dispense has no warehouse, cannot confirm"),
                    )
                }
                loadDispenseItem(connection, id).compose { item ->
                    val orderItemId = item.getString("order_item_id")
                    if (orderItemId.isNullOrBlank()) {
                        return@compose Future.failedFuture(
                            ConflictException("dispense item has no medical order link"),
                        )
                    }
                    val materialId = item.getString("material_id")
                    val lotId = item.getString("lot_id")
                    val quantityDouble = item.getDouble("dispensed_quantity")
                    if (materialId.isNullOrBlank() || quantityDouble == null || quantityDouble <= 0) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("dispense item has no material_id or invalid quantity"),
                        )
                    }
                    if (item.getString("unit") != "PACKAGE") {
                        return@compose Future.failedFuture(
                            ConflictException("only PACKAGE unit outbound is supported in this version"),
                        )
                    }
                    val quantity = BigDecimal.valueOf(quantityDouble)
                    medicalOrderReader.lockMedicationOrder(connection, orderItemId)
                        .compose(::validateOrderForDispensing)
                        .compose {
                            inventoryOutboundPort.confirmPackageOutbound(
                                connection,
                                PackageOutboundCommand(
                                    warehouse = warehouse,
                                    materialId = materialId,
                                    lotId = lotId,
                                    quantity = quantity,
                                    note = "pharmacy dispense $id",
                                ),
                            )
                        }
                        .compose { outbound ->
                            writeConfirmResult(connection, id, item.getString("id")!!, outbound, quantity)
                        }
                        .compose { get(connection, id) }
                }
            }
        }
    }

    private fun writeConfirmResult(
        connection: SqlConnection,
        dispenseId: String,
        itemId: String,
        outbound: PackageOutboundResult,
        quantity: BigDecimal,
    ): Future<Void?> {
        val now = OffsetDateTime.now()
        val totalCost = outbound.unitCost.multiply(quantity)
        val itemUpdate = ctx.update(PHARMACY_DISPENSE_ITEMS)
            .set(PHARMACY_DISPENSE_ITEMS.STOCK_OPERATION_DETAIL_ID, outbound.stockOperationDetailId)
            .set(PHARMACY_DISPENSE_ITEMS.UNIT_COST, outbound.unitCost)
            .set(PHARMACY_DISPENSE_ITEMS.TOTAL_COST, totalCost)
            .where(PHARMACY_DISPENSE_ITEMS.ID.eq(itemId))
        val headerUpdate = ctx.update(PHARMACY_DISPENSES)
            .set(PHARMACY_DISPENSES.STATUS, "DISPENSED")
            .set(PHARMACY_DISPENSES.DISPENSED_AT, now)
            .where(PHARMACY_DISPENSES.ID.eq(dispenseId))
        return connection.preparedQuery(DatabaseConfig.sql(itemUpdate))
            .execute(DatabaseConfig.tuple(itemUpdate))
            .compose { connection.preparedQuery(DatabaseConfig.sql(headerUpdate)).execute(DatabaseConfig.tuple(headerUpdate)) }
            .map { null }
    }

    fun cancel(id: String, body: JsonObject): Future<JsonObject> {
        return pool.withTransaction<JsonObject> { connection ->
            lockDispense(connection, id).compose { current ->
                val status = current.getString("status")
                if (status !in setOf("PENDING", "REVIEWED", "DISPENSING")) {
                    return@compose Future.failedFuture(
                        ConflictException("cannot cancel dispense in status $status"),
                    )
                }
                hasStockOperation(connection, id).compose { hasOp ->
                    if (hasOp) {
                        return@compose Future.failedFuture(
                            ConflictException("cannot cancel dispense with stock operation, use return flow"),
                        )
                    }
                    val updateQuery = ctx.update(PHARMACY_DISPENSES)
                        .set(PHARMACY_DISPENSES.STATUS, "CANCELLED")
                        .where(PHARMACY_DISPENSES.ID.eq(id))
                    connection.preparedQuery(DatabaseConfig.sql(updateQuery))
                        .execute(DatabaseConfig.tuple(updateQuery))
                        .compose { get(connection, id) }
                }
            }
        }
    }

    // ========================================================================
    //  旧兼容路由与只读查询
    // ========================================================================

    /**
     * 兼容 `PUT /dispenses/:id/status`：不允许直接跳到终态，DISPENSED/REVIEWED/
     * DISPENSING 目标一律转发到与动作接口相同的校验和事务逻辑。
     */
    fun updateStatus(id: String, body: JsonObject): Future<JsonObject> {
        val target = body.getString("status")?.trim().orEmpty()
        if (target.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("status is required"))
        }
        return when (target) {
            "REVIEWED" -> review(id, body)
            "DISPENSING" -> start(id, body)
            "DISPENSED" -> confirm(id, body)
            "CANCELLED" -> cancel(id, body)
            else -> Future.failedFuture(
                IllegalArgumentException("invalid status target: $target"),
            )
        }
    }

    /**
     * 旧通用创建入口：`ELDERLY_ROUTINE` 只能通过 `from-medical-order` 创建，
     * 防止绕过医嘱校验伪造患者、入住或药品来源；其他历史类型保持既有行为。
     */
    fun create(body: JsonObject): Future<JsonObject> {
        val dispenseType = body.getString("dispense_type")
        if (dispenseType == ELDERLY_ROUTINE) {
            return Future.failedFuture(
                IllegalArgumentException("ELDERLY_ROUTINE must be created via /dispenses/from-medical-order"),
            )
        }
        return legacyCreate(body)
    }

    private fun legacyCreate(body: JsonObject): Future<JsonObject> {
        val dispenseNo = body.getString("dispense_no")
        val patientId = body.getString("patient_id")
        val dispenseType = body.getString("dispense_type")

        if (dispenseNo.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("dispense_no is required"))
        if (patientId.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        if (dispenseType.isNullOrBlank() || dispenseType !in VALID_DISPENSE_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid dispense_type, must be one of: $VALID_DISPENSE_TYPES"))

        val itemsArray = body.getJsonArray("items")
        if (itemsArray == null || itemsArray.isEmpty)
            return Future.failedFuture(IllegalArgumentException("items is required and must not be empty"))

        val now = OffsetDateTime.now()
        val headerId = Ulid.generate()

        val headerInsert = ctx.insertInto(PHARMACY_DISPENSES)
            .set(PHARMACY_DISPENSES.ID, headerId)
            .set(PHARMACY_DISPENSES.DISPENSE_NO, dispenseNo)
            .set(PHARMACY_DISPENSES.PATIENT_ID, patientId)
            .set(PHARMACY_DISPENSES.ENCOUNTER_ID, body.getString("encounter_id"))
            .set(PHARMACY_DISPENSES.DISPENSE_TYPE, dispenseType)
            .set(PHARMACY_DISPENSES.STATUS, "PENDING")
            .set(PHARMACY_DISPENSES.PHARMACIST, body.getString("pharmacist"))
            .set(PHARMACY_DISPENSES.REVIEWER, body.getString("reviewer"))
            .set(PHARMACY_DISPENSES.METADATA, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(PHARMACY_DISPENSES.CREATED_AT, now)

        return pool.preparedQuery(DatabaseConfig.sql(headerInsert))
            .execute(DatabaseConfig.tuple(headerInsert))
            .flatMap { _ ->
                val header = JsonObject()
                    .put("id", headerId)
                    .put("dispense_no", dispenseNo)
                    .put("patient_id", patientId)
                    .put("encounter_id", body.getString("encounter_id"))
                    .put("dispense_type", dispenseType)
                    .put("status", "PENDING")
                    .put("pharmacist", body.getString("pharmacist"))
                    .put("reviewer", body.getString("reviewer"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())

                val items = JsonArray()

                fun insertItem(index: Int): Future<JsonObject> {
                    if (index >= itemsArray.size()) {
                        header.put("items", items)
                        return Future.succeededFuture(header)
                    }

                    val itemObj = itemsArray.getJsonObject(index)
                    val itemId = Ulid.generate()
                    val prescribedQty = itemObj.getDouble("prescribed_quantity")
                    val dispensedQty = itemObj.getDouble("dispensed_quantity")
                    val unitCost = itemObj.getDouble("unit_cost")
                    val totalCost = if (dispensedQty != null && unitCost != null)
                        BigDecimal.valueOf(dispensedQty * unitCost) else null

                    val itemInsert = ctx.insertInto(PHARMACY_DISPENSE_ITEMS)
                        .set(PHARMACY_DISPENSE_ITEMS.ID, itemId)
                        .set(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID, headerId)
                        .set(PHARMACY_DISPENSE_ITEMS.ORDER_ITEM_ID, itemObj.getString("order_item_id"))
                        .set(PHARMACY_DISPENSE_ITEMS.ORDER_EXECUTION_ID, itemObj.getString("order_execution_id"))
                        .set(PHARMACY_DISPENSE_ITEMS.MATERIAL_ID, itemObj.getString("material_id"))
                        .set(PHARMACY_DISPENSE_ITEMS.LOT_ID, itemObj.getString("lot_id"))
                        .set(PHARMACY_DISPENSE_ITEMS.PRESCRIBED_QUANTITY, prescribedQty?.let { BigDecimal.valueOf(it) })
                        .set(PHARMACY_DISPENSE_ITEMS.DISPENSED_QUANTITY, dispensedQty?.let { BigDecimal.valueOf(it) })
                        .set(PHARMACY_DISPENSE_ITEMS.UNIT, itemObj.getString("unit"))
                        .set(PHARMACY_DISPENSE_ITEMS.SPLIT_QUANTITY, itemObj.getDouble("split_quantity")?.let { BigDecimal.valueOf(it) })
                        .set(PHARMACY_DISPENSE_ITEMS.UNIT_COST, unitCost?.let { BigDecimal.valueOf(it) })
                        .set(PHARMACY_DISPENSE_ITEMS.TOTAL_COST, totalCost)
                        .set(PHARMACY_DISPENSE_ITEMS.METADATA, itemObj.containsKey("metadata")
                            .let { if (it) JSONB.valueOf(itemObj.getJsonObject("metadata").encode()) else null })

                    return pool.preparedQuery(DatabaseConfig.sql(itemInsert))
                        .execute(DatabaseConfig.tuple(itemInsert))
                        .flatMap { _ ->
                            items.add(JsonObject()
                                .put("id", itemId)
                                .put("dispense_id", headerId)
                                .put("order_item_id", itemObj.getString("order_item_id"))
                                .put("order_execution_id", itemObj.getString("order_execution_id"))
                                .put("material_id", itemObj.getString("material_id"))
                                .put("lot_id", itemObj.getString("lot_id"))
                                .put("prescribed_quantity", itemObj.getDouble("prescribed_quantity"))
                                .put("dispensed_quantity", itemObj.getDouble("dispensed_quantity"))
                                .put("unit", itemObj.getString("unit"))
                                .put("split_quantity", itemObj.getDouble("split_quantity"))
                                .put("stock_operation_detail_id", itemObj.getString("stock_operation_detail_id"))
                                .put("unit_cost", itemObj.getDouble("unit_cost"))
                                .put("total_cost", totalCost?.toDouble()))
                            insertItem(index + 1)
                        }
                }

                insertItem(0)
            }
    }

    fun list(
        patientId: String? = null,
        encounterId: String? = null,
        dispenseType: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.let { conditions.add(PHARMACY_DISPENSES.PATIENT_ID.eq(it)) }
        encounterId?.let { conditions.add(PHARMACY_DISPENSES.ENCOUNTER_ID.eq(it)) }
        dispenseType?.let { conditions.add(PHARMACY_DISPENSES.DISPENSE_TYPE.eq(it)) }
        status?.let { conditions.add(PHARMACY_DISPENSES.STATUS.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(PHARMACY_DISPENSES).where(conditions)
        val dataQuery = ctx.selectFrom(PHARMACY_DISPENSES)
            .where(conditions)
            .orderBy(PHARMACY_DISPENSES.CREATED_AT.desc())
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
                        for (row in dataRows) {
                            records.add(toJson(row))
                        }
                        JsonObject().put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> = get(pool, id)

    private fun get(client: SqlClient, id: String): Future<JsonObject> {
        val query = ctx.selectFrom(PHARMACY_DISPENSES).where(PHARMACY_DISPENSES.ID.eq(id))
        val itemsQuery = ctx.selectFrom(PHARMACY_DISPENSE_ITEMS)
            .where(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID.eq(id))
            .orderBy(PHARMACY_DISPENSE_ITEMS.ID.asc())

        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("dispense not found: $id"))
                } else {
                    val header = toJson(rows.iterator().next())
                    client.preparedQuery(DatabaseConfig.sql(itemsQuery))
                        .execute(DatabaseConfig.tuple(itemsQuery))
                        .map { itemRows ->
                            val items = JsonArray()
                            for (row in itemRows) {
                                items.add(itemToJson(row))
                            }
                            header.put("items", items)
                            header
                        }
                }
            }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    private fun lockDispense(connection: SqlConnection, id: String): Future<JsonObject> {
        val query = ctx.selectFrom(PHARMACY_DISPENSES)
            .where(PHARMACY_DISPENSES.ID.eq(id))
            .forUpdate()
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("dispense not found: $id"))
                } else {
                    Future.succeededFuture(toJson(rows.iterator().next()))
                }
            }
    }

    private fun loadDispenseItem(connection: SqlConnection, dispenseId: String): Future<JsonObject> {
        val query = ctx.selectFrom(PHARMACY_DISPENSE_ITEMS)
            .where(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID.eq(dispenseId))
            .limit(1)
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(ConflictException("dispense has no items"))
                } else {
                    Future.succeededFuture(itemToJson(rows.iterator().next()))
                }
            }
    }

    private fun hasStockOperation(connection: SqlConnection, dispenseId: String): Future<Boolean> {
        val query = ctx.select(PHARMACY_DISPENSE_ITEMS.ID)
            .from(PHARMACY_DISPENSE_ITEMS)
            .where(PHARMACY_DISPENSE_ITEMS.DISPENSE_ID.eq(dispenseId))
            .and(PHARMACY_DISPENSE_ITEMS.STOCK_OPERATION_DETAIL_ID.isNotNull)
            .limit(1)
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { it.size() > 0 }
    }

    private fun requiredOperator(body: JsonObject): String =
        body.getString("operator")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("operator is required")
}
