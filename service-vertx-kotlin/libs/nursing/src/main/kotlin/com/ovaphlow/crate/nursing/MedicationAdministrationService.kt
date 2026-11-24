package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

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
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 */
class MedicationAdministrationService(
    private val pool: Pool,
) {
    companion object {
        /** 机构业务时区（与 HealthcareService 等全仓口径一致） */
        private val businessZone = ZoneId.of("Asia/Shanghai")

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

        /**
         * 十进制文本入口（016 口径）：请求只接受 JSON 十进制文本；行值兼容 Vert.x pgclient
         * 对 NUMERIC 的解码（`io.vertx.sqlclient.data.Numeric`，经 toString 取十进制文本，
         * 不经 Double，避免二进制尾差）。
         */
        fun decimalText(value: Any?): BigDecimal? = when (value) {
            null -> null
            is BigDecimal -> value
            is Number -> value.toString().toBigDecimalOrNull()
            is String -> value.toBigDecimalOrNull()
            else -> null
        }

        /** 数量列 NUMERIC(20,6)：超 6 位小数直接拒绝，不静默进位 */
        fun validateQuantityPrecision(quantity: BigDecimal, label: String) {
            if (quantity.stripTrailingZeros().scale() > 6) {
                throw IllegalArgumentException("$label exceeds precision of 6 decimals")
            }
        }

        /** `nursing.medication_administrations` 与关联读列的显式清单。 */
        private const val ADMINISTRATION_COLUMNS =
            "ma.id AS id, ma.task_execution_id AS task_execution_id, " +
                "ma.medical_order_id AS medical_order_id, ma.result AS result, " +
                "ma.administered_quantity AS administered_quantity, ma.unit AS unit, " +
                "ma.dispense_item_id AS dispense_item_id, ma.lot_id AS lot_id, " +
                "ma.warehouse AS warehouse, ma.administered_by AS administered_by, " +
                "ma.administered_at AS administered_at, ma.reason AS reason, " +
                "ma.created_at AS created_at, ma.updated_at AS updated_at, " +
                "e.planned_time AS planned_time, t.description AS task_description, " +
                "mo.order_details AS order_details, mo.order_type AS order_type, " +
                "pat.name AS patient_name, di.material_id AS material_id, " +
                "mat.name AS material_name, lot.batch_no AS batch_no, " +
                "d.dispense_no AS dispense_no"

        /** 明细读路径 FROM（含周期/患者/医嘱/发药来源的全部 LEFT JOIN）。 */
        private const val ADMINISTRATION_FROM =
            " FROM nursing.medication_administrations AS ma" +
                " JOIN nursing.nursing_task_executions AS e ON e.id = ma.task_execution_id" +
                " JOIN nursing.nursing_tasks AS t ON t.id = e.task_id" +
                " LEFT JOIN nursing.nursing_service_periods AS p ON p.id = t.period_id" +
                " LEFT JOIN healthcare.patients AS pat ON pat.id = p.patient_id" +
                " LEFT JOIN healthcare.medical_orders AS mo ON mo.id = t.order_item_id" +
                " LEFT JOIN pharmacy.pharmacy_dispense_items AS di ON di.id = ma.dispense_item_id" +
                " LEFT JOIN public.materials AS mat ON mat.id = di.material_id" +
                " LEFT JOIN public.lots AS lot ON lot.id = di.lot_id" +
                " LEFT JOIN pharmacy.pharmacy_dispenses AS d ON d.id = di.dispense_id"

        /** 计数读路径 FROM（只保留过滤所需的连接）。 */
        private const val ADMINISTRATION_COUNT_FROM =
            " FROM nursing.medication_administrations AS ma" +
                " JOIN nursing.nursing_task_executions AS e ON e.id = ma.task_execution_id" +
                " JOIN nursing.nursing_tasks AS t ON t.id = e.task_id" +
                " LEFT JOIN nursing.nursing_service_periods AS p ON p.id = t.period_id"

        private const val ADMINISTRATION_BY_EXECUTION_SQL =
            "SELECT $ADMINISTRATION_COLUMNS$ADMINISTRATION_FROM WHERE ma.task_execution_id = \$1"

        private const val EXECUTION_SQL =
            "SELECT e.id AS id, e.task_id AS task_id, e.status AS status " +
                "FROM nursing.nursing_task_executions AS e WHERE e.id = \$1"

        private const val EXECUTION_LOCKED_SQL = "$EXECUTION_SQL FOR UPDATE"

        private const val TASK_SQL =
            "SELECT t.id AS id, t.task_type AS task_type, t.order_item_id AS order_item_id, " +
                "t.status AS status FROM nursing.nursing_tasks AS t WHERE t.id = \$1"

        private const val ORDER_LOCKED_SQL =
            "SELECT mo.id AS id, mo.order_type AS order_type, mo.status AS status, " +
                "mo.nurse_checked_by AS nurse_checked_by, mo.nurse_checked_at AS nurse_checked_at, " +
                "mo.order_details AS order_details " +
                "FROM healthcare.medical_orders AS mo WHERE mo.id = \$1 FOR UPDATE"

        /** 锁读发药明细 + 发药单：状态门禁、医嘱归属、实发数量 */
        private const val DISPENSE_ITEM_LOCKED_SQL =
            "SELECT di.id AS id, di.order_item_id AS order_item_id, di.lot_id AS lot_id, " +
                "di.dispensed_quantity AS dispensed_quantity, d.status AS dispense_status, " +
                "d.warehouse AS warehouse " +
                "FROM pharmacy.pharmacy_dispense_items AS di " +
                "JOIN pharmacy.pharmacy_dispenses AS d ON d.id = di.dispense_id " +
                "WHERE di.id = \$1 FOR UPDATE OF di, d"

        private const val ADMINISTERED_SUM_BY_ITEM_SQL =
            "SELECT sum(ma.administered_quantity) AS administered_sum " +
                "FROM nursing.medication_administrations AS ma " +
                "WHERE ma.dispense_item_id = \$1 AND ma.result IN ('已服', '部分服')"

        private const val EXECUTION_STATUS_UPDATE_SQL =
            "UPDATE nursing.nursing_task_executions SET status = \$1, actual_time = \$2 WHERE id = \$3"

        private const val ADMINISTERED_SUBQUERY =
            "SELECT ma.dispense_item_id AS dispense_item_id, " +
                "sum(ma.administered_quantity) AS administered_sum " +
                "FROM nursing.medication_administrations AS ma " +
                "WHERE ma.result IN ('已服', '部分服') AND ma.dispense_item_id IS NOT NULL " +
                "GROUP BY ma.dispense_item_id"

        private const val ADMINISTRATION_SOURCES_SQL =
            "SELECT di.id AS id, di.dispense_id AS dispense_id, d.dispense_no AS dispense_no, " +
                "d.dispensed_at AS dispensed_at, di.material_id AS material_id, " +
                "mat.name AS material_name, di.lot_id AS lot_id, lot.batch_no AS batch_no, " +
                "d.warehouse AS warehouse, mo.order_details ->> 'unit' AS unit, " +
                "mo.order_details ->> 'dose_quantity' AS dose_quantity, " +
                "di.dispensed_quantity AS dispensed_quantity, " +
                "a.administered_sum AS administered_quantity " +
                "FROM pharmacy.pharmacy_dispense_items AS di " +
                "JOIN pharmacy.pharmacy_dispenses AS d ON d.id = di.dispense_id " +
                "JOIN healthcare.medical_orders AS mo ON mo.id = di.order_item_id " +
                "LEFT JOIN public.materials AS mat ON mat.id = di.material_id " +
                "LEFT JOIN public.lots AS lot ON lot.id = di.lot_id " +
                "LEFT JOIN ($ADMINISTERED_SUBQUERY) AS a ON a.dispense_item_id = di.id " +
                "WHERE di.order_item_id = \$1 AND d.status = 'DISPENSED' " +
                "ORDER BY d.dispensed_at DESC, di.id ASC"

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
                // 请求体只接受 JSON 十进制文本（拒绝 JSON number，避免 Double 精度损失）；
                // 行值读取走 decimalText 的 Number 分支，二者口径不同。
                val value = (body.getValue("administered_quantity") as? String)?.toBigDecimalOrNull()
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        encounterId?.takeIf(String::isNotBlank)?.let { where.add("p.encounter_id = ${bind(it)}") }
        medicalOrderId?.takeIf(String::isNotBlank)?.let { where.add("ma.medical_order_id = ${bind(it)}") }
        result?.takeIf(String::isNotBlank)?.let { where.add("ma.result = ${bind(it)}") }
        if (date != null) {
            val dayStart = date.atStartOfDay(businessZone).toOffsetDateTime()
            val dayEnd = date.plusDays(1).atStartOfDay(businessZone).toOffsetDateTime()
            where.add("ma.administered_at >= ${bind(dayStart)}")
            where.add("ma.administered_at < ${bind(dayEnd)}")
        }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total$ADMINISTRATION_COUNT_FROM$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $ADMINISTRATION_COLUMNS$ADMINISTRATION_FROM$whereClause " +
                "ORDER BY ma.administered_at DESC, ma.id DESC " +
                "LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
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
                pool.preparedQuery(ADMINISTRATION_SOURCES_SQL).execute(Tuple.of(orderId))
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
                                    .put(
                                        "dose_quantity",
                                        decimalApi(decimalText(row.getValue("dose_quantity"))),
                                    )
                                    .put("dispensed_quantity", decimalApi(dispensed))
                                    .put("administered_quantity", decimalApi(administered))
                                    .put("remaining_quantity", decimalApi(remaining)),
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

    private fun loadExecution(client: SqlClient, executionId: String): Future<JsonObject> {
        return client.preparedQuery(EXECUTION_SQL).execute(Tuple.of(executionId))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("execution not found: $executionId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    private fun lockExecution(connection: SqlConnection, executionId: String): Future<JsonObject> {
        return connection.preparedQuery(EXECUTION_LOCKED_SQL).execute(Tuple.of(executionId))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("execution not found: $executionId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    private fun loadTask(client: SqlClient, taskId: String): Future<JsonObject> {
        return client.preparedQuery(TASK_SQL).execute(Tuple.of(taskId))
            .compose { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("task not found: $taskId"))
                } else {
                    Future.succeededFuture(rows.iterator().next().toJson())
                }
            }
    }

    private fun lockMedicalOrder(connection: SqlConnection, orderId: String): Future<JsonObject> {
        return connection.preparedQuery(ORDER_LOCKED_SQL).execute(Tuple.of(orderId))
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
        return connection.preparedQuery(DISPENSE_ITEM_LOCKED_SQL).execute(Tuple.of(dispenseItemId))
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
        return connection.preparedQuery(ADMINISTERED_SUM_BY_ITEM_SQL)
            .execute(Tuple.of(input.dispenseItemId))
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
        // 固定列 + 仅在提供时追加的可空列（原生参数化 SQL，占位符按入列顺序编号）。
        val columns = mutableListOf(
            "id", "task_execution_id", "medical_order_id", "result",
            "administered_by", "administered_at", "created_at", "updated_at",
        )
        val values = mutableListOf<Any?>(
            id, executionId, orderId, input.result, userId, now, now, now,
        )
        input.dispenseItemId?.let { columns.add("dispense_item_id"); values.add(it) }
        source?.lotId?.let { columns.add("lot_id"); values.add(it) }
        source?.warehouse?.let { columns.add("warehouse"); values.add(it) }
        input.administeredQuantity?.let { columns.add("administered_quantity"); values.add(it) }
        unit?.takeIf(String::isNotBlank)?.let { columns.add("unit"); values.add(it) }
        input.reason?.let { columns.add("reason"); values.add(it) }

        val sql =
            "INSERT INTO nursing.medication_administrations (${columns.joinToString(", ")}) " +
                "VALUES (${values.indices.joinToString(", ") { "\$${it + 1}" }})"

        return connection.preparedQuery(sql).execute(Tuple.from(values))
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
        return connection.preparedQuery(EXECUTION_STATUS_UPDATE_SQL)
            .execute(Tuple.of(target, now, executionId))
            .map { null as Void? }
    }

    private fun readAdministration(client: SqlClient, executionId: String): Future<JsonObject> {
        return client.preparedQuery(ADMINISTRATION_BY_EXECUTION_SQL).execute(Tuple.of(executionId))
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
            .put("order_details", row.getValue("order_details") as? JsonObject)
            .put("order_type", row.getValue("order_type")?.toString())
            .put("patient_name", row.getValue("patient_name")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())

    /**
     * 数量响应文本（D5）：去尾零后以十进制文本输出（`1100.000000` → `"1100"`）。
     * 用 `toPlainString()` 避免 `stripTrailingZeros()` 后的科学计数法；全程不经 Double。
     */
    private fun decimalApi(value: Any?): String? =
        value?.let {
            when (it) {
                is BigDecimal -> it
                else -> it.toString().toBigDecimalOrNull()
            }?.stripTrailingZeros()?.toPlainString()
        }

    private fun isUniqueViolation(error: Throwable): Boolean =
        error is PgException && error.sqlState == "23505"
}
