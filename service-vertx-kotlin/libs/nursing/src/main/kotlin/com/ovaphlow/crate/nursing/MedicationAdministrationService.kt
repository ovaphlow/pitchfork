package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 医嘱执行闭环：护士给药记录（MAR）。
 *
 * 在既有「开嘱 → 核对 → 发药」链路上补齐护士给药执行与给药记录，与药房发药联动：
 *
 * - 给药记录与护理执行 1:1（`task_execution_id` UNIQUE），结果存中文值
 *   （已服/部分服/拒服/漏服/暂缓），给药人取自认证 `userId`，给药时间为服务端当前时间；
 * - 已服/部分服必须引用该医嘱已 DISPENSED 且未给完的发药明细，累计给药数量
 *   ≤ 发药明细 `dispensed_quantity`（事务内锁定发药明细行对账，并发安全）；
 * - 给药不写库存、不改发药单状态、不改医嘱状态机；给药记录失败整体回滚，
 *   执行、任务、发药单、库存均不变。
 *
 * 锁顺序（与药房 011 保持一致，避免 AB-BA 死锁）：发药明细/发药单 → 医嘱 → 执行。
 * 药房发药确认锁定顺序为 发药单 → 医嘱；本服务先锁发药明细（含发药单）再锁医嘱，
 * 与药房方向一致；执行行只被护理侧流程锁定，不与药房/库存锁形成环。
 */
class MedicationAdministrationService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 给药结果（遵循项目惯例存中文值，不引入英文业务 code 存库） */
        val VALID_RESULTS = setOf("已服", "部分服", "拒服", "漏服", "暂缓")

        /** 消耗发药数量的给药结果 */
        val CONSUMING_RESULTS = setOf("已服", "部分服")

        /** 不消耗发药数量的给药结果 */
        val NON_CONSUMING_RESULTS = setOf("拒服", "漏服", "暂缓")

        /** 必填原因的给药结果 */
        val REASON_REQUIRED_RESULTS = setOf("部分服", "拒服", "漏服", "暂缓")

        /** 请求体允许的字段；其余（含服务端受控字段）一律 400 */
        val ADMINISTRATION_FIELDS = setOf("result", "dispense_item_id", "administered_quantity", "reason")

        /** 服务端受控字段：客户端提交一律 400（未知字段拒绝覆盖） */
        val SERVER_CONTROLLED_FIELDS = setOf(
            "id", "task_execution_id", "medical_order_id", "administered_by",
            "administered_at", "created_at", "updated_at", "lot_id", "warehouse", "unit",
        )

        /** 给药结果 → 执行状态联动（4.1） */
        fun targetStatusFor(result: String): String? = when (result) {
            "已服", "部分服" -> "COMPLETED"
            "拒服", "漏服", "暂缓" -> "SKIPPED"
            else -> null
        }

        /** 十进制文本入口（016 口径）：请求只接受 JSON 十进制文本；行值兼容 BigDecimal */
        fun decimalText(value: Any?): BigDecimal? = when (value) {
            null -> null
            is BigDecimal -> value
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

        /** 数量列 NUMERIC(20,6)：超 6 位小数直接拒绝，不静默进位 */
        fun validateQuantityPrecision(quantity: BigDecimal, label: String) {
            if (quantity.stripTrailingZeros().scale() > 6) {
                throw IllegalArgumentException("$label exceeds precision of 6 decimals")
            }
        }

        /**
         * 校验给药请求体并解析为受控输入；非法抛 [IllegalArgumentException]。
         * 纯函数，不依赖数据库，供路由与单元测试共用。
         */
        fun parseAdministrationBody(body: JsonObject): AdministrationInput {
            val unknown = body.fieldNames().filter { it !in ADMINISTRATION_FIELDS }
            if (unknown.isNotEmpty()) {
                throw IllegalArgumentException("unknown fields: ${unknown.joinToString(", ")}")
            }
            val result = body.getString("result")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("result is required")
            if (result !in VALID_RESULTS) {
                throw IllegalArgumentException("invalid result, must be one of: ${VALID_RESULTS.joinToString("/")}")
            }
            val dispenseItemId = body.getString("dispense_item_id")?.trim()?.takeIf(String::isNotBlank)
            val quantity = if (body.containsKey("administered_quantity")) {
                val value = decimalText(body.getValue("administered_quantity"))
                    ?: throw IllegalArgumentException("administered_quantity must be decimal text")
                if (value <= BigDecimal.ZERO) {
                    throw IllegalArgumentException("administered_quantity must be positive")
                }
                validateQuantityPrecision(value, "administered_quantity")
                value
            } else {
                null
            }
            val reason = body.getString("reason")?.trim()?.takeIf(String::isNotBlank)

            if (result in CONSUMING_RESULTS) {
                if (dispenseItemId == null) {
                    throw IllegalArgumentException("dispense_item_id is required for result $result")
                }
                if (quantity == null) {
                    throw IllegalArgumentException("administered_quantity is required for result $result")
                }
            } else {
                if (dispenseItemId != null) {
                    throw IllegalArgumentException("dispense_item_id is not allowed for result $result")
                }
                if (quantity != null) {
                    throw IllegalArgumentException("administered_quantity is not allowed for result $result")
                }
            }
            if (result in REASON_REQUIRED_RESULTS && reason == null) {
                throw IllegalArgumentException("reason is required for result $result")
            }
            return AdministrationInput(
                result = result,
                dispenseItemId = dispenseItemId,
                administeredQuantity = quantity,
                reason = reason,
            )
        }
    }

    /** 受控给药输入：服务端派生字段（给药人/时间/医嘱归属）不在其中 */
    data class AdministrationInput(
        val result: String,
        val dispenseItemId: String?,
        val administeredQuantity: BigDecimal?,
        val reason: String?,
    )

    // ——— 表与字段引用（raw DSL，与 TaskExecutionService 同风格） ———

    private val maTable = DSL.table(DSL.name("nursing", "medication_administrations"))
    private val fMaId = DSL.field("ma.id", String::class.java)
    private val fMaExecId = DSL.field("ma.task_execution_id", String::class.java)
    private val fMaOrderId = DSL.field("ma.medical_order_id", String::class.java)
    private val fMaDispenseItemId = DSL.field("ma.dispense_item_id", String::class.java)
    private val fMaLotId = DSL.field("ma.lot_id", String::class.java)
    private val fMaWarehouse = DSL.field("ma.warehouse", String::class.java)
    private val fMaResult = DSL.field("ma.result", String::class.java)
    private val fMaQuantity = DSL.field("ma.administered_quantity", BigDecimal::class.java)
    private val fMaUnit = DSL.field("ma.unit", String::class.java)
    private val fMaAdministeredBy = DSL.field("ma.administered_by", String::class.java)
    private val fMaAdministeredAt = DSL.field("ma.administered_at", OffsetDateTime::class.java)
    private val fMaReason = DSL.field("ma.reason", String::class.java)
    private val fMaCreatedAt = DSL.field("ma.created_at", OffsetDateTime::class.java)
    private val fMaUpdatedAt = DSL.field("ma.updated_at", OffsetDateTime::class.java)

    private val execTable = DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e")
    private val execUpdateTable = DSL.table(DSL.name("nursing", "nursing_task_executions"))
    private val taskTable = DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t")
    private val periodTable = DSL.table(DSL.name("nursing", "nursing_service_periods")).`as`("p")
    private val patientTable = DSL.table(DSL.name("healthcare", "patients")).`as`("pat")
    private val orderTable = DSL.table(DSL.name("healthcare", "medical_orders")).`as`("mo")
    private val dispenseTable = DSL.table(DSL.name("pharmacy", "pharmacy_dispenses")).`as`("d")
    private val dispenseItemTable = DSL.table(DSL.name("pharmacy", "pharmacy_dispense_items")).`as`("di")
    private val materialsTable = DSL.table(DSL.name("public", "materials")).`as`("mat")
    private val lotsTable = DSL.table(DSL.name("public", "lots")).`as`("lot")

    // ========================================================================
    //  记录给药（事务：锁定执行 + 关联医嘱 + 发药明细，顺序校验与对账）
    // ========================================================================

    /**
     * 记录一次给药。请求体不接受 administered_by / administered_at / id /
     * medical_order_id 等覆盖（未知字段 400）；给药人与给药时间由服务端写入。
     */
    fun recordAdministration(executionId: String, userId: String, body: JsonObject): Future<JsonObject> {
        if (userId.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("authentication required"))
        }
        val input = try {
            parseAdministrationBody(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            loadExecution(connection, executionId).compose { execution ->
                // 1. 执行状态：终态不可记录给药
                val currentStatus = execution.getString("status") ?: ""
                if (currentStatus !in setOf("PENDING", "IN_PROGRESS")) {
                    return@compose Future.failedFuture(
                        ConflictException("execution is already $currentStatus, cannot record administration"),
                    )
                }
                val taskId = execution.getString("task_id")
                    ?: return@compose Future.failedFuture(IllegalArgumentException("execution has no task"))
                loadTask(connection, taskId).compose { task ->
                    // 2. 任务：必须 MEDICATION 且关联医嘱，任务未终止
                    if (task.getString("task_type") != "MEDICATION") {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("only MEDICATION tasks can record administration"),
                        )
                    }
                    val orderId = task.getString("order_item_id")
                        ?: return@compose Future.failedFuture(
                            IllegalArgumentException("task is not linked to a medical order"),
                        )
                    if (task.getString("status") != "ACTIVE") {
                        return@compose Future.failedFuture(
                            ConflictException("task is not active: ${task.getString("status")}"),
                        )
                    }
                    // 3. 发药来源（已服/部分服）：先锁发药明细行，再做数量对账。
                    //    锁顺序与药房 011（发药单 → 医嘱）同向，避免 AB-BA 死锁。
                    val sourceFuture =
                        if (input.result in CONSUMING_RESULTS) {
                            lockDispenseItem(connection, requireNotNull(input.dispenseItemId), orderId)
                                .compose { source ->
                                    reconcileQuantity(connection, input, source).map { source }
                                }
                        } else {
                            Future.succeededFuture(null as DispenseSource?)
                        }
                    sourceFuture.compose { source ->
                        // 4. 医嘱门禁：锁读（与停嘱/核对/发药确认串行）
                        lockMedicalOrder(connection, orderId).compose { order ->
                            if (order.getString("order_type") != "MEDICATION") {
                                return@compose Future.failedFuture(
                                    IllegalArgumentException("order is not a medication order"),
                                )
                            }
                            if (order.getString("status") != "ACTIVE") {
                                return@compose Future.failedFuture(
                                    ConflictException("order is not active: ${order.getString("status")}"),
                                )
                            }
                            if (order.getString("nurse_checked_by") == null || order.getValue("nurse_checked_at") == null) {
                                return@compose Future.failedFuture(
                                    ConflictException("order has not been nurse-checked"),
                                )
                            }
                            val unit = (order.getValue("order_details") as? JsonObject)?.getString("unit")
                            // 5. 锁定执行行（与并发给药串行，配合 1:1 唯一约束至多一条成功）
                            lockExecution(connection, executionId).compose { lockedExecution ->
                                val lockedStatus = lockedExecution.getString("status") ?: ""
                                if (lockedStatus !in setOf("PENDING", "IN_PROGRESS")) {
                                    return@compose Future.failedFuture(
                                        ConflictException("execution is already $lockedStatus, cannot record administration"),
                                    )
                                }
                                insertAdministration(
                                    connection, executionId, orderId, userId, input, source, unit, now,
                                ).compose {
                                    updateExecutionStatus(connection, executionId, input.result, now)
                                }.compose {
                                    readAdministration(connection, executionId)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    /** 单条给药记录（含来源发药明细摘要）；不存在返回 NotFoundException */
    fun getAdministrationByExecution(executionId: String): Future<JsonObject> = readAdministration(pool, executionId)

    /**
     * MAR 查询：按老人（encounter_id）/医嘱/给药日期/结果过滤给药明细。
     * 只读，join 周期与患者不串其他入住。
     */
    fun listAdministrations(
        encounterId: String? = null,
        medicalOrderId: String? = null,
        date: LocalDate? = null,
        result: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        encounterId?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field("p.encounter_id").eq(it)) }
        medicalOrderId?.takeIf(String::isNotBlank)?.let { conditions.add(fMaOrderId.eq(it)) }
        result?.takeIf(String::isNotBlank)?.let { conditions.add(fMaResult.eq(it)) }
        if (date != null) {
            val dayStart = date.atStartOfDay().atOffset(ZoneOffset.UTC)
            val dayEnd = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
            conditions.add(fMaAdministeredAt.ge(dayStart))
            conditions.add(fMaAdministeredAt.lt(dayEnd))
        }

        val baseFrom = administrationSelect()
            .from(maTable.`as`("ma"))
            .join(execTable).on(DSL.field("e.id").eq(fMaExecId))
            .join(taskTable).on(DSL.field("t.id").eq(DSL.field("e.task_id")))
            .leftJoin(periodTable).on(DSL.field("p.id").eq(DSL.field("t.period_id")))
            .leftJoin(patientTable).on(DSL.field("pat.id").eq(DSL.field("p.patient_id")))
            .leftJoin(dispenseItemTable).on(DSL.field("di.id").eq(fMaDispenseItemId))
            .leftJoin(materialsTable).on(DSL.field("mat.id").eq(DSL.field("di.material_id")))
            .leftJoin(lotsTable).on(DSL.field("lot.id").eq(DSL.field("di.lot_id")))
            .leftJoin(dispenseTable).on(DSL.field("d.id").eq(DSL.field("di.dispense_id")))
            .where(conditions)

        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(maTable.`as`("ma"))
            .join(execTable).on(DSL.field("e.id").eq(fMaExecId))
            .join(taskTable).on(DSL.field("t.id").eq(DSL.field("e.task_id")))
            .leftJoin(periodTable).on(DSL.field("p.id").eq(DSL.field("t.period_id")))
            .where(conditions)
        val dataQuery = baseFrom
            .orderBy(fMaAdministeredAt.desc(), fMaId.desc())
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
                        for (row in dataRows) records.add(administrationJson(row))
                        JsonObject()
                            .put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    /**
     * 给药来源选择器：该执行所属医嘱「已 DISPENSED 且剩余数量 > 0」的发药明细，
     * 附带药品、批次、仓库、已发/已给/剩余数量。只读；提交时仍以事务对账为准。
     */
    fun listAdministrationSources(executionId: String): Future<JsonObject> {
        return loadExecution(pool, executionId).compose { execution ->
            val taskId = execution.getString("task_id")
                ?: return@compose Future.failedFuture(IllegalArgumentException("execution has no task"))
            loadTask(pool, taskId).compose { task ->
                if (task.getString("task_type") != "MEDICATION") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only MEDICATION tasks have administration sources"),
                    )
                }
                val orderId = task.getString("order_item_id")
                if (orderId.isNullOrBlank()) {
                    return@compose Future.succeededFuture(
                        JsonObject().put("records", JsonArray()).put("meta", JsonObject().put("total", 0)),
                    )
                }
                val administeredSub = ctx.select(
                    fMaDispenseItemId,
                    DSL.sum(fMaQuantity).`as`("administered_sum"),
                )
                    .from(maTable.`as`("ma"))
                    .where(fMaResult.`in`(CONSUMING_RESULTS))
                    .and(fMaDispenseItemId.isNotNull)
                    .groupBy(fMaDispenseItemId)
                val query = ctx.select(
                    DSL.field("di.id").`as`("id"),
                    DSL.field("di.dispense_id").`as`("dispense_id"),
                    DSL.field("d.dispense_no").`as`("dispense_no"),
                    DSL.field("d.dispensed_at").`as`("dispensed_at"),
                    DSL.field("di.material_id").`as`("material_id"),
                    DSL.field("mat.name").`as`("material_name"),
                    DSL.field("di.lot_id").`as`("lot_id"),
                    DSL.field("lot.batch_no").`as`("batch_no"),
                    DSL.field("d.warehouse").`as`("warehouse"),
                    DSL.field("mo.order_details ->> 'unit'").`as`("unit"),
                    DSL.field("di.dispensed_quantity").`as`("dispensed_quantity"),
                    DSL.field("a.administered_sum").`as`("administered_quantity"),
                )
                    .from(dispenseItemTable)
                    .join(dispenseTable).on(DSL.field("d.id").eq(DSL.field("di.dispense_id")))
                    .join(orderTable).on(DSL.field("mo.id").eq(DSL.field("di.order_item_id")))
                    .leftJoin(materialsTable).on(DSL.field("mat.id").eq(DSL.field("di.material_id")))
                    .leftJoin(lotsTable).on(DSL.field("lot.id").eq(DSL.field("di.lot_id")))
                    .leftJoin(DSL.table(administeredSub).`as`("a")).on(DSL.field("a.dispense_item_id").eq(DSL.field("di.id")))
                    .where(DSL.field("di.order_item_id").eq(orderId))
                    .and(DSL.field("d.status").eq("DISPENSED"))
                    .orderBy(DSL.field("d.dispensed_at").desc(), DSL.field("di.id").asc())

                pool.preparedQuery(DatabaseConfig.sql(query))
                    .execute(DatabaseConfig.tuple(query))
                    .map { rows ->
                        val records = JsonArray()
                        for (row in rows) {
                            val dispensed = decimalText(row.getValue("dispensed_quantity")) ?: BigDecimal.ZERO
                            val administered = decimalText(row.getValue("administered_quantity")) ?: BigDecimal.ZERO
                            val remaining = dispensed.subtract(administered)
                            if (remaining <= BigDecimal.ZERO) continue // 已给完的来源不可再选
                            records.add(
                                JsonObject()
                                    .put("id", row.getValue("id")?.toString())
                                    .put("dispense_id", row.getValue("dispense_id")?.toString())
                                    .put("dispense_no", row.getValue("dispense_no")?.toString())
                                    .put("dispensed_at", row.getValue("dispensed_at")?.toString())
                                    .put("material_id", row.getValue("material_id")?.toString())
                                    .put("material_name", row.getValue("material_name")?.toString())
                                    .put("lot_id", row.getValue("lot_id")?.toString())
                                    .put("batch_no", row.getValue("batch_no")?.toString())
                                    .put("warehouse", row.getValue("warehouse")?.toString())
                                    .put("unit", row.getValue("unit")?.toString())
                                    .put("dispensed_quantity", dispensed.toPlainString())
                                    .put("administered_quantity", administered.toPlainString())
                                    .put("remaining_quantity", remaining.toPlainString()),
                            )
                        }
                        JsonObject()
                            .put("records", records)
                            .put("meta", JsonObject().put("total", records.size().toLong()))
                    }
            }
        }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    private data class DispenseSource(
        val lotId: String?,
        val warehouse: String?,
        val dispensedQuantity: BigDecimal,
    )

    private fun administrationSelect() =
        ctx.select(
            fMaId,
            fMaExecId,
            fMaOrderId,
            fMaResult,
            fMaQuantity,
            fMaUnit,
            fMaDispenseItemId,
            fMaLotId,
            fMaWarehouse,
            fMaAdministeredBy,
            fMaAdministeredAt,
            fMaReason,
            fMaCreatedAt,
            fMaUpdatedAt,
            DSL.field("e.planned_time").`as`("planned_time"),
            DSL.field("t.description").`as`("task_description"),
            DSL.field("pat.name").`as`("patient_name"),
            DSL.field("di.material_id").`as`("material_id"),
            DSL.field("mat.name").`as`("material_name"),
            DSL.field("lot.batch_no").`as`("batch_no"),
            DSL.field("d.dispense_no").`as`("dispense_no"),
        )

    private fun loadExecution(client: SqlClient, executionId: String): Future<JsonObject> {
        val query = ctx.select(
            DSL.field("e.id").`as`("id"),
            DSL.field("e.task_id").`as`("task_id"),
            DSL.field("e.status").`as`("status"),
        ).from(execTable).where(DSL.field("e.id").eq(executionId))
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("execution not found: $executionId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    private fun lockExecution(connection: SqlConnection, executionId: String): Future<JsonObject> {
        val query = ctx.select(
            DSL.field("e.id").`as`("id"),
            DSL.field("e.task_id").`as`("task_id"),
            DSL.field("e.status").`as`("status"),
        ).from(execTable).where(DSL.field("e.id").eq(executionId)).forUpdate()
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("execution not found: $executionId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    private fun loadTask(client: SqlClient, taskId: String): Future<JsonObject> {
        val query = ctx.select(
            DSL.field("t.id").`as`("id"),
            DSL.field("t.task_type").`as`("task_type"),
            DSL.field("t.order_item_id").`as`("order_item_id"),
            DSL.field("t.status").`as`("status"),
        ).from(taskTable).where(DSL.field("t.id").eq(taskId))
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("task not found: $taskId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    private fun lockMedicalOrder(connection: SqlConnection, orderId: String): Future<JsonObject> {
        val query = ctx.select(
            DSL.field("mo.id").`as`("id"),
            DSL.field("mo.order_type").`as`("order_type"),
            DSL.field("mo.status").`as`("status"),
            DSL.field("mo.nurse_checked_by").`as`("nurse_checked_by"),
            DSL.field("mo.nurse_checked_at").`as`("nurse_checked_at"),
            DSL.field("mo.order_details").`as`("order_details"),
        ).from(orderTable).where(DSL.field("mo.id").eq(orderId)).forUpdate()
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("medical order not found: $orderId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    /** 锁读发药明细 + 发药单：状态门禁、医嘱归属、实发数量 */
    private fun lockDispenseItem(
        connection: SqlConnection,
        dispenseItemId: String,
        orderId: String,
    ): Future<DispenseSource> {
        val query = ctx.select(
            DSL.field("di.id").`as`("id"),
            DSL.field("di.order_item_id").`as`("order_item_id"),
            DSL.field("di.lot_id").`as`("lot_id"),
            DSL.field("di.dispensed_quantity").`as`("dispensed_quantity"),
            DSL.field("d.status").`as`("dispense_status"),
            DSL.field("d.warehouse").`as`("warehouse"),
        )
            .from(dispenseItemTable)
            .join(dispenseTable).on(DSL.field("d.id").eq(DSL.field("di.dispense_id")))
            .where(DSL.field("di.id").eq(dispenseItemId))
            .forUpdate()
            .of(DSL.field("di"), DSL.field("d"))
        return connection.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(NotFoundException("dispense item not found: $dispenseItemId"))
                }
                val row = rows.iterator().next()
                if (row.getValue("order_item_id")?.toString() != orderId) {
                    return@compose Future.failedFuture(
                        ConflictException("dispense item does not belong to this medical order"),
                    )
                }
                if (row.getValue("dispense_status")?.toString() != "DISPENSED") {
                    return@compose Future.failedFuture(
                        ConflictException("dispense is not DISPENSED, cannot administer from it"),
                    )
                }
                val dispensed = decimalText(row.getValue("dispensed_quantity"))
                if (dispensed == null || dispensed <= BigDecimal.ZERO) {
                    return@compose Future.failedFuture(
                        ConflictException("dispense item has no dispensed quantity"),
                    )
                }
                Future.succeededFuture(
                    DispenseSource(
                        lotId = row.getValue("lot_id")?.toString(),
                        warehouse = row.getValue("warehouse")?.toString(),
                        dispensedQuantity = dispensed,
                    ),
                )
            }
    }

    /** 数量对账：累计给药（已服/部分服）+ 本次 ≤ 实发数量；行锁已保证并发安全 */
    private fun reconcileQuantity(
        connection: SqlConnection,
        input: AdministrationInput,
        source: DispenseSource,
    ): Future<Void?> {
        val sumQuery = ctx.select(DSL.sum(fMaQuantity).`as`("administered_sum"))
            .from(maTable.`as`("ma"))
            .where(fMaDispenseItemId.eq(input.dispenseItemId))
            .and(fMaResult.`in`(CONSUMING_RESULTS))
        return connection.preparedQuery(DatabaseConfig.sql(sumQuery))
            .execute(DatabaseConfig.tuple(sumQuery))
            .map { rows ->
                val administered = decimalText(rows.iterator().next().getValue("administered_sum")) ?: BigDecimal.ZERO
                val remaining = source.dispensedQuantity.subtract(administered)
                val requested = input.administeredQuantity
                if (requested == null || requested > remaining) {
                    throw ConflictException(
                        "administered quantity exceeds dispensed remaining quantity (remaining: ${remaining.toPlainString()})",
                    )
                }
                null
            }
    }

    private fun insertAdministration(
        connection: SqlConnection,
        executionId: String,
        orderId: String,
        userId: String,
        input: AdministrationInput,
        source: DispenseSource?,
        unit: String?,
        now: OffsetDateTime,
    ): Future<Void?> {
        val id = Ulid.generate()
        val insert = ctx.insertInto(maTable)
            .set(fMaId, id)
            .set(fMaExecId, executionId)
            .set(fMaOrderId, orderId)
            .set(fMaResult, input.result)
            .set(fMaAdministeredBy, userId)
            .set(fMaAdministeredAt, now)
            .set(fMaCreatedAt, now)
            .set(fMaUpdatedAt, now)
        input.dispenseItemId?.let { insert.set(fMaDispenseItemId, it) }
        source?.lotId?.let { insert.set(fMaLotId, it) }
        source?.warehouse?.let { insert.set(fMaWarehouse, it) }
        input.administeredQuantity?.let { insert.set(fMaQuantity, it) }
        unit?.takeIf(String::isNotBlank)?.let { insert.set(fMaUnit, it) }
        input.reason?.let { insert.set(fMaReason, it) }

        return connection.preparedQuery(DatabaseConfig.sql(insert))
            .execute(DatabaseConfig.tuple(insert))
            .recover { error ->
                if (isUniqueViolation(error)) {
                    Future.failedFuture(
                        ConflictException("execution already has an administration record, cannot record twice"),
                    )
                } else {
                    Future.failedFuture(error)
                }
            }
            .map { null as Void? }
    }

    private fun updateExecutionStatus(
        connection: SqlConnection,
        executionId: String,
        result: String,
        now: OffsetDateTime,
    ): Future<Void?> {
        val target = targetStatusFor(result)
            ?: return Future.failedFuture(IllegalArgumentException("invalid result: $result"))
        val update = ctx.update(execTable)
            .set(DSL.field("e.status"), target)
            .set(DSL.field("e.actual_time"), now)
            .where(DSL.field("e.id").eq(executionId))
        return connection.preparedQuery(DatabaseConfig.sql(update))
            .execute(DatabaseConfig.tuple(update))
            .map { null as Void? }
    }

    private fun readAdministration(client: SqlClient, executionId: String): Future<JsonObject> {
        val query = administrationSelect()
            .from(maTable.`as`("ma"))
            .join(execTable).on(DSL.field("e.id").eq(fMaExecId))
            .join(taskTable).on(DSL.field("t.id").eq(DSL.field("e.task_id")))
            .leftJoin(periodTable).on(DSL.field("p.id").eq(DSL.field("t.period_id")))
            .leftJoin(patientTable).on(DSL.field("pat.id").eq(DSL.field("p.patient_id")))
            .leftJoin(dispenseItemTable).on(DSL.field("di.id").eq(fMaDispenseItemId))
            .leftJoin(materialsTable).on(DSL.field("mat.id").eq(DSL.field("di.material_id")))
            .leftJoin(lotsTable).on(DSL.field("lot.id").eq(DSL.field("di.lot_id")))
            .leftJoin(dispenseTable).on(DSL.field("d.id").eq(DSL.field("di.dispense_id")))
            .where(fMaExecId.eq(executionId))
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("administration not found for execution: $executionId"))
                } else {
                    Future.succeededFuture(administrationJson(rows.iterator().next()))
                }
            }
    }

    private fun administrationJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("task_execution_id", row.getValue("task_execution_id")?.toString())
            .put("medical_order_id", row.getValue("medical_order_id")?.toString())
            .put("result", row.getValue("result")?.toString())
            .put("administered_quantity", decimalApi(row.getValue("administered_quantity")))
            .put("unit", row.getValue("unit")?.toString())
            .put("dispense_item_id", row.getValue("dispense_item_id")?.toString())
            .put("dispense_no", row.getValue("dispense_no")?.toString())
            .put("material_id", row.getValue("material_id")?.toString())
            .put("material_name", row.getValue("material_name")?.toString())
            .put("lot_id", row.getValue("lot_id")?.toString())
            .put("batch_no", row.getValue("batch_no")?.toString())
            .put("warehouse", row.getValue("warehouse")?.toString())
            .put("administered_by", row.getValue("administered_by")?.toString())
            .put("administered_at", row.getValue("administered_at")?.toString())
            .put("reason", row.getValue("reason")?.toString())
            .put("planned_time", row.getValue("planned_time")?.toString())
            .put("task_description", row.getValue("task_description")?.toString())
            .put("patient_name", row.getValue("patient_name")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())

    private fun decimalApi(value: Any?): String? =
        value?.let {
            when (it) {
                is BigDecimal -> it
                else -> it.toString().toBigDecimalOrNull()
            }?.toPlainString()
        }

    private fun isUniqueViolation(error: Throwable): Boolean =
        error is io.vertx.pgclient.PgException && error.sqlState == "23505"
}
