package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.inventories.InventoryConsumptionService
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

private fun nursingNumericDouble(value: Any?): Double? = when (value) {
    null -> null
    is Number -> value.toDouble()
    else -> value.toString().toDoubleOrNull()
}

class TaskExecutionService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val log = LoggerFactory.getLogger(TaskExecutionService::class.java)

    // ——— nursing_task_executions ———
    private val t = DSL.table(DSL.name("nursing", "nursing_task_executions"))

    private val cId = DSL.field("id", String::class.java)
    private val cTaskId = DSL.field("task_id", String::class.java)
    private val cPlannedTime = DSL.field("planned_time", OffsetDateTime::class.java)
    private val cActualTime = DSL.field("actual_time", OffsetDateTime::class.java)
    private val cExecutor = DSL.field("executor", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cStockOpDetailId = DSL.field("stock_operation_detail_id", String::class.java)
    private val cQuantity = DSL.field("quantity", BigDecimal::class.java)
    private val cNote = DSL.field("note", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)

    // ——— nursing_task_execution_consumptions ———
    private val consTable = DSL.table(DSL.name("nursing", "nursing_task_execution_consumptions"))
    private val ccId = DSL.field("cc.id", String::class.java)
    private val ccExecId = DSL.field("cc.task_execution_id", String::class.java)
    private val ccDetailId = DSL.field("cc.stock_operation_detail_id", String::class.java)
    private val ccStockId = DSL.field("cc.stock_id", String::class.java)
    private val ccMaterialId = DSL.field("cc.material_id", String::class.java)
    private val ccLotId = DSL.field("cc.lot_id", String::class.java)
    private val ccWarehouse = DSL.field("cc.warehouse", String::class.java)
    private val ccQuantity = DSL.field("cc.quantity", BigDecimal::class.java)
    private val ccUnit = DSL.field("cc.unit", String::class.java)
    private val ccSplitQty = DSL.field("cc.split_quantity", BigDecimal::class.java)
    private val ccCreatedAt = DSL.field("cc.created_at", OffsetDateTime::class.java)

    // ——— stock_operation_details ———
    private val sod = DSL.table(DSL.name("public", "stock_operation_details"))
    private val sodUnitCost = DSL.field("sod.unit_cost", BigDecimal::class.java)
    private val sodTotalCost = DSL.field("sod.total_cost", BigDecimal::class.java)

    // ——— nursing_tasks ———
    private val taskTable = DSL.table(DSL.name("nursing", "nursing_tasks"))
    private val ctId = DSL.field("t_id", String::class.java)
    private val ctPeriodId = DSL.field("t_period_id", String::class.java)
    private val ctDescription = DSL.field("t_description", String::class.java)
    private val ctTaskType = DSL.field("t_task_type", String::class.java)
    private val ctFrequencyCode = DSL.field("t_frequency_code", String::class.java)
    private val ctFrequencyName = DSL.field("t_frequency_name", String::class.java)
    private val ctStartDate = DSL.field("t_start_date", LocalDate::class.java)
    private val ctEndDate = DSL.field("t_end_date", LocalDate::class.java)
    private val ctStatus = DSL.field("t_status", String::class.java)
    private val ctMetadata = DSL.field("t_metadata", JSONB::class.java)

    // ——— nursing_service_periods ———
    private val periodTable = DSL.table(DSL.name("nursing", "nursing_service_periods"))
    private val cpPatientId = DSL.field("p_patient_id", String::class.java)

    // ——— healthcare.patients ———
    private val patientTable = DSL.table(DSL.name("healthcare", "patients"))
    private val cpatName = DSL.field("pat_name", String::class.java)

    // ——— materials ———
    private val materialsTable = DSL.table(DSL.name("public", "materials"))
    private val matName = DSL.field("mat.name", String::class.java)

    private fun executionWithSummaryJson(
        row: Row,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): JsonObject {
        val json = toJson(row)
        json.put("task_description", row.getValue("task_description")?.toString())
        json.put("task_type", row.getValue("task_type")?.toString())
        json.put("task_frequency_name", row.getValue("task_frequency_name")?.toString())
        json.put("task_period_id", row.getValue("task_period_id")?.toString())
        json.put("patient_id", row.getValue("patient_id")?.toString())
        json.put("patient_name", row.getValue("patient_name")?.toString())

        // 逾期派生字段
        val status = row.getValue("status")?.toString()
        val plannedTime =
            row.getValue("planned_time")?.let {
                if (it is OffsetDateTime) {
                    it
                } else if (it is String) {
                    try {
                        OffsetDateTime.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
        val (isOverdue, overdueMinutes) = computeOverdueFields(status, plannedTime, now)
        json.put("is_overdue", isOverdue)
        json.put("overdue_minutes", overdueMinutes)

        return json
    }

    companion object {
        private val VALID_STATUS_TRANSITIONS =
            mapOf(
                "PENDING" to listOf("IN_PROGRESS", "SKIPPED", "CANCELLED"),
                "IN_PROGRESS" to listOf("COMPLETED", "CANCELLED"),
                "COMPLETED" to emptyList(),
                "SKIPPED" to emptyList(),
                "CANCELLED" to emptyList(),
            )

        /**
         * 计算单条执行记录的逾期派生字段。
         * 纯函数，不依赖任何外部状态。
         */
        fun computeOverdueFields(
            status: String?,
            plannedTime: OffsetDateTime?,
            now: OffsetDateTime,
        ): Pair<Boolean, Int?> {
            if (plannedTime == null) return Pair(false, null)
            val isOverdue =
                (status == "PENDING" || status == "IN_PROGRESS") &&
                    plannedTime.isBefore(now)
            val minutes = if (isOverdue) Duration.between(plannedTime, now).toMinutes().toInt() else null
            return Pair(isOverdue, minutes)
        }

        fun toJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("task_id", row.getValue("task_id")?.toString())
                .put("planned_time", row.getValue("planned_time")?.toString())
                .put("actual_time", row.getValue("actual_time")?.toString())
                .put("executor", row.getValue("executor")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("quantity", nursingNumericDouble(row.getValue("quantity")))
                .put("note", row.getValue("note")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())

        /**
         * 计算计划完成率。
         * 纯函数，不依赖任何外部状态。
         *
         * @param completedDueTotal 应完成的已完成数量
         * @param dueTotal 应完成总量
         * @return 完成率百分比（四舍五入到两位小数），dueTotal 为 0 时返回 null
         */
        fun completionRate(
            completedDueTotal: Long,
            dueTotal: Long,
        ): Double? {
            if (dueTotal == 0L) return null
            return BigDecimal(completedDueTotal)
                .multiply(BigDecimal(100))
                .divide(BigDecimal(dueTotal), 2, RoundingMode.HALF_UP)
                .toDouble()
        }
    }

    // ========================================================================
    //  现有 CRUD — 保持不变，但保护旧库存字段
    // ========================================================================

    fun create(body: JsonObject): Future<JsonObject> {
        val taskId = body.getString("task_id")
        if (taskId.isNullOrBlank()) {
            return Future.failedFuture(IllegalArgumentException("task_id is required"))
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        // 客户端不再允许直接写 stock_operation_detail_id / quantity
        val query =
            ctx
                .insertInto(t)
                .set(cId, id)
                .set(cTaskId, taskId)
                .set(cPlannedTime, body.getString("planned_time")?.let { OffsetDateTime.parse(it) })
                .set(cActualTime, body.getString("actual_time")?.let { OffsetDateTime.parse(it) })
                .set(cExecutor, body.getString("executor"))
                .set(cStatus, "PENDING")
                .set(cNote, body.getString("note"))
                .set(
                    cMetadata,
                    body
                        .containsKey("metadata")
                        .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null },
                ).set(cCreatedAt, now)

        return pool
            .preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
                JsonObject()
                    .put("id", id)
                    .put("task_id", taskId)
                    .put("planned_time", body.getString("planned_time"))
                    .put("actual_time", body.getString("actual_time"))
                    .put("executor", body.getString("executor"))
                    .put("status", "PENDING")
                    .put("note", body.getString("note"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
            }
    }

    fun list(
        taskId: String? = null,
        executor: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        taskId?.let { conditions.add(cTaskId.eq(it)) }
        executor?.let { conditions.add(cExecutor.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery =
            ctx
                .selectFrom(t)
                .where(conditions)
                .orderBy(cCreatedAt.desc())
                .limit(limit)
                .offset(offset)

        return pool
            .preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool
                    .preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .flatMap { dataRows ->
                        val records = JsonArray()
                        val execIds = mutableListOf<String>()
                        for (row in dataRows) records.add(toJson(row))
                        for (row in dataRows) {
                            row.getValue("id")?.toString()?.let { execIds.add(it) }
                        }
                        // 批量加载耗材摘要
                        loadConsumptionSummaryBatch(execIds).map { summary ->
                            for (record in records) {
                                val id = (record as JsonObject).getString("id")
                                id?.let { summary[it]?.let { s -> record.put("consumption_summary", s) } }
                            }
                            JsonObject()
                                .put("records", records)
                                .put("meta", JsonObject().put("total", total))
                        }
                    }
            }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        return pool
            .preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("execution not found: $id"))
                } else {
                    val record = toJson(rows.iterator().next())
                    loadSingleConsumptionSummary(id).map { summary ->
                        if (summary != null) record.put("consumption_summary", summary)
                        record
                    }
                }
            }
    }

    fun updateStatus(
        id: String,
        newStatus: String,
        note: String? = null,
    ): Future<JsonObject> {
        if (newStatus.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("status is required"))
        }

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
            if (newStatus !in allowedNext) {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus"),
                )
            }

            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cStatus, newStatus)

            if (newStatus == "IN_PROGRESS" && existing.getString("actual_time") == null) {
                q = q.set(cActualTime, now)
            }
            if (newStatus == "COMPLETED" && existing.getString("actual_time") == null) {
                q = q.set(cActualTime, now)
            }

            // 支持保存备注/跳过/取消原因
            if (!note.isNullOrBlank()) {
                q = q.set(cNote, note.trim())
            }

            val updateQuery = q.where(cId.eq(id))

            pool
                .preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }

    fun update(
        id: String,
        body: JsonObject,
    ): Future<JsonObject> =
        get(id).flatMap { _ ->
            var q = ctx.update(t).set(cExecutor, body.getString("executor"))

            if (body.containsKey("actual_time")) {
                q = q.set(cActualTime, body.getString("actual_time")?.let { OffsetDateTime.parse(it) })
            }
            if (body.containsKey("planned_time")) {
                q = q.set(cPlannedTime, body.getString("planned_time")?.let { OffsetDateTime.parse(it) })
            }
            if (body.containsKey("note")) {
                q = q.set(cNote, body.getString("note"))
            }
            // 保护旧库存字段：拒绝外部直接写入
            if (body.containsKey("metadata")) {
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))
            }

            val updateQuery = q.where(cId.eq(id))

            pool
                .preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }

    // ========================================================================
    //  带耗材的完成任务
    // ========================================================================

    data class ConsumptionInput(
        val stockId: String,
        val unit: String,
        val quantity: BigDecimal?,
        val splitQuantity: BigDecimal?,
    )

    /**
     * 在同一事务内完成任务并扣减库存。
     */
    fun completeExecutionWithConsumptions(
        id: String,
        note: String?,
        consumptions: List<ConsumptionInput>,
        authenticatedSubject: String,
    ): Future<JsonObject> {
        // 无耗材时走原有的 updateStatus 路径
        if (consumptions.isEmpty()) {
            return updateStatus(id, "COMPLETED", note)
        }
        if (consumptions.map { it.stockId }.distinct().size != consumptions.size) {
            return Future.failedFuture(IllegalArgumentException("duplicate stock_id"))
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction { connection ->
                // 锁定执行记录
                val lockExec = ctx.selectFrom(t).where(cId.eq(id)).forUpdate()
                connection
                    .preparedQuery(DatabaseConfig.sql(lockExec))
                    .execute(DatabaseConfig.tuple(lockExec))
                    .compose { lockedRows ->
                        if (lockedRows.size() == 0) {
                            return@compose Future.failedFuture(NotFoundException("execution not found: $id"))
                        }

                        val lockedRow = lockedRows.iterator().next()
                        val execStatus = lockedRow.getValue("status")?.toString() ?: ""
                        val taskId = lockedRow.getValue("task_id")?.toString()
                            ?: return@compose Future.failedFuture(IllegalArgumentException("task_id missing"))

                        // === 幂等重试处理 ===
                        if (execStatus == "COMPLETED") {
                            // 已完成的执行 — 加载既有耗材，检查是否相同请求
                            return@compose loadExistingExecutionWithConsumption(connection, id)
                                .compose { existingResult ->
                                    val existingConsumptions = existingResult.getJsonArray("consumptions")
                                    if (existingConsumptions == null || existingConsumptions.size() == 0) {
                                        // 原本没有耗材，但重试请求带了耗材 → 冲突
                                        return@compose Future.failedFuture(
                                            ConflictException(
                                                "execution already completed without consumptions, cannot add consumptions now",
                                            ),
                                        )
                                    }
                                    // 比较耗材清单 — 按单位类型规范化 quantity
                                    val existingSummary =
                                        existingConsumptions
                                            .map {
                                                val obj = it as JsonObject
                                                val unit = obj.getString("unit") ?: "PACKAGE"
                                                val qty =
                                                    if (unit == "SPLIT") {
                                                        obj.getDouble("split_quantity") ?: 0.0
                                                    } else {
                                                        obj.getDouble("quantity") ?: 0.0
                                                    }
                                                "${obj.getString("stock_id")}|$unit|${BigDecimal.valueOf(qty).stripTrailingZeros()}"
                                            }.sorted()
                                    val newSummary =
                                        consumptions
                                            .map {
                                                val unit = it.unit
                                                val qty =
                                                    if (unit == "SPLIT") {
                                                        it.splitQuantity?.toDouble() ?: 0.0
                                                    } else {
                                                        it.quantity?.toDouble() ?: 0.0
                                                    }
                                                "${it.stockId}|$unit|${BigDecimal.valueOf(qty).stripTrailingZeros()}"
                                            }.sorted()

                                    if (existingSummary == newSummary) {
                                        // 完全相同 → 返回既有结果
                                        return@compose Future.succeededFuture(existingResult)
                                    } else {
                                        // 不一致 → 冲突
                                        return@compose Future.failedFuture(
                                            ConflictException("execution already completed with different consumptions, refresh and retry"),
                                        )
                                    }
                                }
                        }

                        // 状态校验：必须是可转为 COMPLETED 的状态
                        val currentStatus = execStatus
                        val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
                        if ("COMPLETED" !in allowedNext) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("cannot transition from $currentStatus to COMPLETED"),
                            )
                        }

                        // 查询任务和周期信息
                        val taskInfo =
                            ctx
                                .select(DSL.field("t.period_id").`as`("t_period_id"))
                                .from(taskTable.`as`("t"))
                                .where(DSL.field("t.id").eq(taskId))
                        connection
                            .preparedQuery(DatabaseConfig.sql(taskInfo))
                            .execute(DatabaseConfig.tuple(taskInfo))
                            .compose { taskRows ->
                                val periodId =
                                    if (taskRows.size() >
                                        0
                                    ) {
                                        taskRows
                                            .iterator()
                                            .next()
                                            .getValue("t_period_id")
                                            ?.toString()
                                    } else {
                                        null
                                    }

                                val periodQuery =
                                    ctx
                                        .select(DSL.field("p.patient_id").`as`("p_patient_id"))
                                        .from(periodTable.`as`("p"))
                                        .where(DSL.field("p.id").eq(periodId))
                                connection
                                    .preparedQuery(DatabaseConfig.sql(periodQuery))
                                    .execute(DatabaseConfig.tuple(periodQuery))
                                    .compose { periodRows ->
                                        val patientId: String =
                                            if (periodRows.size() >
                                                0
                                            ) {
                                                periodRows
                                                    .iterator()
                                                    .next()
                                                    .getValue("p_patient_id")
                                                    ?.toString() ?: ""
                                            } else {
                                                ""
                                            }

                                        // 调用库存消耗服务（内部验证仓库一致性、库存量、拆零换算等）
                                        val consumptionService = InventoryConsumptionService()
                                        val command =
                                            InventoryConsumptionService.NursingConsumptionCommand(
                                                items =
                                                    consumptions.map { ci ->
                                                        InventoryConsumptionService.ConsumptionItem(
                                                            stockId = ci.stockId,
                                                            unit = ci.unit,
                                                            quantity = ci.quantity,
                                                            splitQuantity = ci.splitQuantity,
                                                        )
                                                    },
                                                taskExecutionId = id,
                                                taskId = taskId,
                                                periodId = periodId ?: "",
                                                patientId = patientId,
                                                executor = authenticatedSubject,
                                                businessTime = now,
                                            )

                                        consumptionService
                                            .consumeForNursingExecution(connection, command)
                                            .compose { result ->
                                                insertConsumptions(connection, id, result, now)
                                                    .compose { syncLegacyFields(connection, id, result) }
                                                    .compose {
                                                        val updateQ =
                                                            ctx
                                                                .update(t)
                                                                .set(cStatus, "COMPLETED")
                                                                .set(cActualTime, now)
                                                                .let { q -> if (!note.isNullOrBlank()) q.set(cNote, note.trim()) else q }
                                                                .where(cId.eq(id))

                                                        connection
                                                            .preparedQuery(DatabaseConfig.sql(updateQ))
                                                            .execute(DatabaseConfig.tuple(updateQ))
                                                    }.compose { buildConsumptionResponse(connection, id, result, warehouse = "") }
                                            }
                                    }
                            }
                    }
        }.map { result: JsonObject? -> result ?: throw IllegalStateException("completion transaction returned no execution") }
    }

    /** 加载已有完成的执行及其耗材 */
    private fun loadExistingExecutionWithConsumption(
        connection: SqlConnection,
        id: String,
    ): Future<JsonObject> {
        val readQuery = ctx.selectFrom(t).where(cId.eq(id))
        return connection
            .preparedQuery(DatabaseConfig.sql(readQuery))
            .execute(DatabaseConfig.tuple(readQuery))
            .compose { rows ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(NotFoundException("execution not found"))
                }
                val record = toJson(rows.iterator().next())

                // 加载耗材明细
                val consQuery =
                    ctx
                        .select(
                            DSL.field("cc.id").`as`("id"),
                            DSL.field("cc.stock_operation_detail_id").`as`("stock_operation_detail_id"),
                            DSL.field("cc.stock_id").`as`("stock_id"),
                            DSL.field("cc.material_id").`as`("material_id"),
                            DSL.field("mat.name").`as`("material_name"),
                            DSL.field("cc.lot_id").`as`("lot_id"),
                            DSL.field("lot.batch_no").`as`("batch_no"),
                            DSL.field("cc.warehouse").`as`("warehouse"),
                            DSL.field("cc.quantity").`as`("quantity"),
                            DSL.field("cc.unit").`as`("unit"),
                            DSL.field("cc.split_quantity").`as`("split_quantity"),
                            DSL.field("sod.unit_cost").`as`("unit_cost"),
                            DSL.field("sod.total_cost").`as`("total_cost"),
                        ).from(DSL.table(DSL.name("nursing", "nursing_task_execution_consumptions")).`as`("cc"))
                        .leftJoin(DSL.table(DSL.name("public", "materials")).`as`("mat"))
                        .on(DSL.field("cc.material_id").eq(DSL.field("mat.id")))
                        .leftJoin(DSL.table(DSL.name("public", "lots")).`as`("lot"))
                        .on(DSL.field("cc.lot_id").eq(DSL.field("lot.id")))
                        .leftJoin(DSL.table(DSL.name("public", "stock_operation_details")).`as`("sod"))
                        .on(DSL.field("cc.stock_operation_detail_id").eq(DSL.field("sod.id")))
                        .where(DSL.field("cc.task_execution_id").eq(id))

                connection
                    .preparedQuery(DatabaseConfig.sql(consQuery))
                    .execute(DatabaseConfig.tuple(consQuery))
                    .map { consRows ->
                        val consumptions = JsonArray()
                        var totalCost = 0.0
                        var warehouse = ""
                        for (cr in consRows) {
                            val c =
                                JsonObject()
                                    .put("id", cr.getValue("id")?.toString())
                                    .put("stock_operation_detail_id", cr.getValue("stock_operation_detail_id")?.toString())
                                    .put("stock_id", cr.getValue("stock_id")?.toString())
                                    .put("material_id", cr.getValue("material_id")?.toString())
                                    .put("material_name", cr.getValue("material_name")?.toString())
                                    .put("lot_id", cr.getValue("lot_id")?.toString())
                                    .put("batch_no", cr.getValue("batch_no")?.toString())
                                    .put("warehouse", cr.getValue("warehouse")?.toString())
                                    .put("quantity", nursingNumericDouble(cr.getValue("quantity")))
                                    .put("unit", cr.getValue("unit")?.toString())
                                    .put("split_quantity", nursingNumericDouble(cr.getValue("split_quantity")))
                                    .put("unit_cost", nursingNumericDouble(cr.getValue("unit_cost")))
                                    .put("total_cost", nursingNumericDouble(cr.getValue("total_cost")))
                            consumptions.add(c)
                            warehouse = cr.getValue("warehouse")?.toString() ?: ""
                            totalCost += nursingNumericDouble(cr.getValue("total_cost")) ?: 0.0
                        }
                        record.put("consumptions", consumptions)
                        record.put(
                            "consumption_summary",
                            JsonObject()
                                .put("count", consumptions.size())
                                .put("warehouse", warehouse)
                                .put("total_cost", totalCost),
                        )
                        record
                    }
            }
    }

    /** 构建耗材完成响应 */
    private fun buildConsumptionResponse(
        connection: SqlConnection,
        id: String,
        result: InventoryConsumptionService.ConsumptionResult,
        warehouse: String,
    ): Future<JsonObject> {
        val readQuery = ctx.selectFrom(t).where(cId.eq(id))
        return connection
            .preparedQuery(DatabaseConfig.sql(readQuery))
            .execute(DatabaseConfig.tuple(readQuery))
            .compose { rows ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(NotFoundException("execution not found after update"))
                }
                val record = toJson(rows.iterator().next())
                val summary =
                    JsonObject()
                        .put("count", result.detailResults.size)
                        .put("warehouse", warehouse.ifBlank { result.detailResults.firstOrNull()?.warehouse ?: "" })
                        .put("total_cost", result.detailResults.sumOf { it.totalCost.toDouble() })

                val details = JsonArray()
                for (dr in result.detailResults) {
                    details.add(
                        JsonObject()
                            .put("id", dr.detailId)
                            .put("stock_operation_detail_id", dr.detailId)
                            .put("stock_id", dr.stockId)
                            .put("material_id", dr.materialId)
                            .put("lot_id", dr.lotId)
                            .put("warehouse", dr.warehouse)
                            .put("quantity", dr.quantity.toDouble())
                            .put("unit", dr.unit)
                            .put("split_quantity", dr.splitQuantity?.toDouble())
                            .put("unit_cost", dr.unitCost.toDouble())
                            .put("total_cost", dr.totalCost.toDouble()),
                    )
                }
                record.put("consumption_summary", summary)
                record.put("consumptions", details)
                Future.succeededFuture(record)
            }
    }

    /** 插入护理执行耗材关联 */
    private fun insertConsumptions(
        connection: io.vertx.sqlclient.SqlConnection,
        executionId: String,
        result: InventoryConsumptionService.ConsumptionResult,
        now: OffsetDateTime,
    ): Future<Void?> {
        fun process(index: Int): Future<Void?> {
            if (index >= result.detailResults.size) {
                return Future.succeededFuture(null as Void?)
            }

            val dr = result.detailResults[index]
            val consId = Ulid.generate()

            val insertQ =
                ctx
                    .insertInto(consTable)
                    .set(DSL.field("id"), consId)
                    .set(DSL.field("task_execution_id"), executionId)
                    .set(DSL.field("stock_operation_detail_id"), dr.detailId)
                    .set(DSL.field("stock_id"), dr.stockId)
                    .set(DSL.field("material_id"), dr.materialId)
                    .set(DSL.field("lot_id"), dr.lotId)
                    .set(DSL.field("warehouse"), dr.warehouse)
                    .set(DSL.field("quantity"), dr.quantity)
                    .set(DSL.field("unit"), dr.unit)
                    .set(DSL.field("split_quantity"), dr.splitQuantity)
                    .set(DSL.field("created_at"), now)

            return connection
                .preparedQuery(DatabaseConfig.sql(insertQ))
                .execute(DatabaseConfig.tuple(insertQ))
                .compose { process(index + 1) }
        }

        return process(0)
    }

    /** 仅有 1 条耗材时同步旧兼容字段 */
    private fun syncLegacyFields(
        connection: io.vertx.sqlclient.SqlConnection,
        executionId: String,
        result: InventoryConsumptionService.ConsumptionResult,
    ): Future<Void?> {
        if (result.detailResults.size != 1) {
            // 多条明细时清空旧字段
            val clearQ =
                ctx
                    .update(t)
                    .set(cStockOpDetailId, null as String?)
                    .set(cQuantity, null as BigDecimal?)
                    .where(cId.eq(executionId))
            return connection
                .preparedQuery(DatabaseConfig.sql(clearQ))
                .execute(DatabaseConfig.tuple(clearQ))
                .map { null as Void? }
        }

        val dr = result.detailResults.single()
        val syncQ =
            ctx
                .update(t)
                .set(cStockOpDetailId, dr.detailId)
                .set(cQuantity, dr.quantity)
                .where(cId.eq(executionId))

        return connection
            .preparedQuery(DatabaseConfig.sql(syncQ))
            .execute(DatabaseConfig.tuple(syncQ))
            .map { null as Void? }
    }

    // ========================================================================
    //  耗材摘要/明细查询
    // ========================================================================

    /** 批量加载耗材摘要 */
    fun loadConsumptionSummaryBatch(executionIds: List<String>): Future<Map<String, JsonObject>> {
        if (executionIds.isEmpty()) {
            return Future.succeededFuture(emptyMap())
        }

        val query =
            ctx
                .select(
                    DSL.field("task_execution_id"),
                    count().`as`("cnt"),
                    DSL.field("warehouse"),
                    DSL.sum(DSL.field("sod.total_cost", BigDecimal::class.java)).`as`("total_cost"),
                ).from(consTable.`as`("cc"))
                .join(sod.`as`("sod"))
                .on(DSL.field("cc.stock_operation_detail_id").eq(DSL.field("sod.id")))
                .where(DSL.field("cc.task_execution_id").`in`(executionIds))
                .groupBy(DSL.field("cc.task_execution_id"), DSL.field("cc.warehouse"))

        return pool
            .preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val result = mutableMapOf<String, JsonObject>()
                for (row in rows) {
                    val execId = row.getValue("task_execution_id")?.toString() ?: continue
                    result[execId] =
                        JsonObject()
                            .put("count", row.getValue("cnt") as? Long ?: 0L)
                            .put("warehouse", row.getValue("warehouse")?.toString())
                            .put("total_cost", nursingNumericDouble(row.getValue("total_cost")) ?: 0.0)
                }
                result
            }
    }

    /** 加载单条执行耗材摘要 */
    fun loadSingleConsumptionSummary(executionId: String): Future<JsonObject?> {
        val query =
            ctx
                .select(
                    count().`as`("cnt"),
                    DSL.field("warehouse"),
                    DSL.sum(DSL.field("sod.total_cost", BigDecimal::class.java)).`as`("total_cost"),
                ).from(consTable.`as`("cc"))
                .join(sod.`as`("sod"))
                .on(DSL.field("cc.stock_operation_detail_id").eq(DSL.field("sod.id")))
                .where(DSL.field("cc.task_execution_id").eq(executionId))
                .groupBy(DSL.field("cc.warehouse"))

        return pool
            .preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                if (rows.size() == 0) {
                    null
                } else {
                    val row = rows.iterator().next()
                    JsonObject()
                        .put("count", row.getValue("cnt") as? Long ?: 0L)
                        .put("warehouse", row.getValue("warehouse")?.toString())
                        .put("total_cost", nursingNumericDouble(row.getValue("total_cost")) ?: 0.0)
                }
            }
    }

    /** 查询执行耗材明细 */
    fun listExecutionConsumptions(executionId: String): Future<JsonObject> {
        val query =
            ctx
                .select(
                    DSL.field("cc.id").`as`("id"),
                    DSL.field("cc.stock_operation_detail_id").`as`("stock_operation_detail_id"),
                    DSL.field("cc.stock_id").`as`("stock_id"),
                    DSL.field("cc.material_id").`as`("material_id"),
                    DSL.field("cc.lot_id").`as`("lot_id"),
                    DSL.field("lot.batch_no").`as`("batch_no"),
                    DSL.field("cc.warehouse").`as`("warehouse"),
                    DSL.field("cc.quantity").`as`("quantity"),
                    DSL.field("cc.unit").`as`("unit"),
                    DSL.field("cc.split_quantity").`as`("split_quantity"),
                    DSL.field("cc.created_at").`as`("created_at"),
                    DSL.field("mat.name").`as`("material_name"),
                    DSL.field("sod.unit_cost").`as`("unit_cost"),
                    DSL.field("sod.total_cost").`as`("total_cost"),
                ).from(consTable.`as`("cc"))
                .leftJoin(materialsTable.`as`("mat"))
                .on(DSL.field("cc.material_id").eq(DSL.field("mat.id")))
                .leftJoin(DSL.table(DSL.name("public", "lots")).`as`("lot"))
                .on(DSL.field("cc.lot_id").eq(DSL.field("lot.id")))
                .leftJoin(sod.`as`("sod"))
                .on(DSL.field("cc.stock_operation_detail_id").eq(DSL.field("sod.id")))
                .where(DSL.field("cc.task_execution_id").eq(executionId))
                .orderBy(DSL.field("cc.created_at").asc())

        return pool
            .preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val details = JsonArray()
                for (row in rows) {
                    details.add(
                        JsonObject()
                            .put("id", row.getValue("id")?.toString())
                            .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                            .put("stock_id", row.getValue("stock_id")?.toString())
                            .put("material_id", row.getValue("material_id")?.toString())
                            .put("material_name", row.getValue("material_name")?.toString())
                            .put("lot_id", row.getValue("lot_id")?.toString())
                            .put("batch_no", row.getValue("batch_no")?.toString())
                            .put("warehouse", row.getValue("warehouse")?.toString())
                            .put("quantity", nursingNumericDouble(row.getValue("quantity")))
                            .put("unit", row.getValue("unit")?.toString())
                            .put("split_quantity", nursingNumericDouble(row.getValue("split_quantity")))
                            .put("unit_cost", nursingNumericDouble(row.getValue("unit_cost")))
                            .put("total_cost", nursingNumericDouble(row.getValue("total_cost")))
                            .put("created_at", row.getValue("created_at")?.toString()),
                    )
                }
                JsonObject().put("records", details).put("meta", JsonObject().put("total", details.size().toLong()))
            }
    }

    // ========================================================================
    //  执行实例幂等生成
    // ========================================================================

    fun ensureExecutionsForDateRange(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        periodId: String?,
    ): Future<JsonObject> {
        val taskConditions = mutableListOf<org.jooq.Condition>()
        taskConditions.add(ctStatus.eq("ACTIVE"))

        val taskQuery =
            ctx
                .select(
                    DSL.field("t.id").`as`("t_id"),
                    DSL.field("t.frequency_code").`as`("t_frequency_code"),
                    DSL.field("t.start_date").`as`("t_start_date"),
                    DSL.field("t.end_date").`as`("t_end_date"),
                    DSL.field("t.metadata").`as`("t_metadata"),
                    DSL.field("t.period_id").`as`("t_period_id"),
                ).from(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
                .join(DSL.table(DSL.name("nursing", "nursing_service_periods")).`as`("ps"))
                .on(DSL.field("t.period_id").eq(DSL.field("ps.id")))
                .where(DSL.field("t.status").eq("ACTIVE"))
                .and(DSL.field("ps.status").eq("ACTIVE"))
                .let { q ->
                    var qq = q
                    if (periodId != null) qq = qq.and(DSL.field("t.period_id").eq(periodId))
                    qq
                }

        return pool
            .preparedQuery(DatabaseConfig.sql(taskQuery))
            .execute(DatabaseConfig.tuple(taskQuery))
            .flatMap { taskRows ->
                var generated = 0
                var skipped = 0
                val errors = JsonArray()

                val now = OffsetDateTime.now()

                val inserts = mutableListOf<org.jooq.Query>()

                for (taskRow in taskRows) {
                    val taskId = taskRow.getValue("t_id")?.toString() ?: continue
                    val freqCode = taskRow.getValue("t_frequency_code")?.toString()
                    val startDate = (taskRow.getValue("t_start_date") as? LocalDate)
                    val endDate = (taskRow.getValue("t_end_date") as? LocalDate)
                    val metadata =
                        taskRow.getValue("t_metadata")?.let {
                            when (it) {
                                is JsonObject -> {
                                    it
                                }

                                is String -> {
                                    try {
                                        JsonObject(it)
                                    } catch (_: Exception) {
                                        null
                                    }
                                }

                                else -> {
                                    null
                                }
                            }
                        }

                    if (!FrequencyCalculator.isGeneratable(freqCode)) {
                        if (freqCode != null && freqCode !in setOf("PRN", "STAT")) {
                            errors.add(JsonObject().put("task_id", taskId).put("reason", "unknown frequency: $freqCode"))
                        }
                        continue
                    }

                    var date = dateFrom
                    while (!date.isAfter(dateTo)) {
                        if (endDate != null && date.isAfter(endDate)) {
                            date = date.plusDays(1)
                            continue
                        }
                        val times = FrequencyCalculator.plannedTimesForDate(freqCode, startDate, date, metadata)
                        for (plannedTime in times) {
                            val execId = Ulid.generate()
                            val insertQuery =
                                ctx
                                    .insertInto(t)
                                    .set(cId, execId)
                                    .set(cTaskId, taskId)
                                    .set(cPlannedTime, plannedTime)
                                    .set(cStatus, "PENDING")
                                    .set(cCreatedAt, now)
                                    .onConflict(DSL.field("task_id"), DSL.field("planned_time"))
                                    .doNothing()

                            inserts.add(insertQuery)
                        }
                        date = date.plusDays(1)
                    }
                }

                if (inserts.isEmpty()) {
                    return@flatMap Future.succeededFuture(
                        JsonObject()
                            .put("generated", 0)
                            .put("skipped", 0)
                            .put("errors", errors),
                    )
                }

                executeInsertsSequentially(inserts, 0, generated, skipped, errors)
            }
    }

    private fun executeInsertsSequentially(
        inserts: List<org.jooq.Query>,
        index: Int,
        generated: Int,
        skipped: Int,
        errors: JsonArray,
    ): Future<JsonObject> {
        if (index >= inserts.size) {
            return Future.succeededFuture(
                JsonObject()
                    .put("generated", generated)
                    .put("skipped", skipped)
                    .put("errors", errors),
            )
        }

        val query = inserts[index]
        return pool
            .preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { result ->
                val updated = result.rowCount()
                val newGenerated = generated + updated
                val newSkipped = skipped + if (updated == 0) 1 else 0
                executeInsertsSequentially(inserts, index + 1, newGenerated, newSkipped, errors)
            }
    }

    fun ensureExecutionsForDate(
        date: LocalDate,
        periodId: String?,
    ): Future<JsonObject> = ensureExecutionsForDateRange(date, date, periodId)

    // ========================================================================
    //  今日执行查询（带任务、长者摘要和耗材摘要）
    // ========================================================================

    fun todayExecutions(
        date: LocalDate,
        periodId: String? = null,
        executor: String? = null,
        status: String? = null,
        overdue: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return ensureExecutionsForDate(date, periodId).flatMap {
            val zone = java.time.ZoneOffset.UTC
            val dayStart = date.atStartOfDay().atOffset(zone)
            val dayEnd = date.plusDays(1).atStartOfDay().atOffset(zone)

            val conditions = mutableListOf<org.jooq.Condition>()
            val plannedField = DSL.field("e.planned_time", OffsetDateTime::class.java)
            conditions.add(plannedField.ge(dayStart))
            conditions.add(plannedField.lt(dayEnd))
            // 今日工作台只展示活动服务期的执行；已收束周期的历史记录仍可由时间线/统计查询
            conditions.add(DSL.field("p.status").eq("ACTIVE"))

            periodId?.let { conditions.add(DSL.field("t.period_id").eq(it)) }
            executor?.let { conditions.add(DSL.field("e.executor").eq(it)) }
            status?.let { conditions.add(DSL.field("e.status").eq(it)) }

            // 逾期筛选条件：仅未完成且计划时间已过
            if (overdue == true) {
                conditions.add(DSL.field("e.status").`in`("PENDING", "IN_PROGRESS"))
                conditions.add(plannedField.lt(now))
            }

            // 计算 overdue_total 的基础条件（忽略 status 和 overdue 筛选）
            val overdueTotalConditions = mutableListOf<org.jooq.Condition>()
            overdueTotalConditions.add(plannedField.ge(dayStart))
            overdueTotalConditions.add(plannedField.lt(dayEnd))
            overdueTotalConditions.add(DSL.field("p.status").eq("ACTIVE"))
            periodId?.let { overdueTotalConditions.add(DSL.field("t.period_id").eq(it)) }
            executor?.let { overdueTotalConditions.add(DSL.field("e.executor").eq(it)) }
            overdueTotalConditions.add(DSL.field("e.status").`in`("PENDING", "IN_PROGRESS"))
            overdueTotalConditions.add(plannedField.lt(now))

            val allColumns =
                listOf(
                    DSL.field("e.id").`as`("id"),
                    DSL.field("e.task_id").`as`("task_id"),
                    DSL.field("e.planned_time").`as`("planned_time"),
                    DSL.field("e.actual_time").`as`("actual_time"),
                    DSL.field("e.executor").`as`("executor"),
                    DSL.field("e.status").`as`("status"),
                    DSL.field("e.stock_operation_detail_id").`as`("stock_operation_detail_id"),
                    DSL.field("e.quantity").`as`("quantity"),
                    DSL.field("e.note").`as`("note"),
                    DSL.field("e.metadata").`as`("metadata"),
                    DSL.field("e.created_at").`as`("created_at"),
                    DSL.field("t.description").`as`("task_description"),
                    DSL.field("t.task_type").`as`("task_type"),
                    DSL.field("t.frequency_name").`as`("task_frequency_name"),
                    DSL.field("t.period_id").`as`("task_period_id"),
                    DSL.field("p.patient_id").`as`("patient_id"),
                    DSL.field("pat.name").`as`("patient_name"),
                )

            val baseSelect =
                ctx
                    .select(allColumns)
                    .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
                    .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
                    .on(DSL.field("e.task_id").eq(DSL.field("t.id")))
                    .leftJoin(DSL.table(DSL.name("nursing", "nursing_service_periods")).`as`("p"))
                    .on(DSL.field("t.period_id").eq(DSL.field("p.id")))
                    .leftJoin(DSL.table(DSL.name("healthcare", "patients")).`as`("pat"))
                    .on(DSL.field("p.patient_id").eq(DSL.field("pat.id")))
                    .where(conditions)

            val countQuery =
                ctx
                    .select(count().`as`("total"))
                    .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
                    .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
                    .on(DSL.field("e.task_id").eq(DSL.field("t.id")))
                    .leftJoin(DSL.table(DSL.name("nursing", "nursing_service_periods")).`as`("p"))
                    .on(DSL.field("t.period_id").eq(DSL.field("p.id")))
                    .leftJoin(DSL.table(DSL.name("healthcare", "patients")).`as`("pat"))
                    .on(DSL.field("p.patient_id").eq(DSL.field("pat.id")))
                    .where(conditions)

            // overdue_total — 独立聚合，不受 status / overdue 筛选影响
            val overdueTotalQuery =
                ctx
                    .select(count().`as`("overdue_total"))
                    .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
                    .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
                    .on(DSL.field("e.task_id").eq(DSL.field("t.id")))
                    .leftJoin(DSL.table(DSL.name("nursing", "nursing_service_periods")).`as`("p"))
                    .on(DSL.field("t.period_id").eq(DSL.field("p.id")))
                    .leftJoin(DSL.table(DSL.name("healthcare", "patients")).`as`("pat"))
                    .on(DSL.field("p.patient_id").eq(DSL.field("pat.id")))
                    .where(overdueTotalConditions)

            val dataQuery =
                baseSelect
                    .orderBy(DSL.field("e.planned_time").asc())
                    .limit(limit)
                    .offset(offset)

            pool
                .preparedQuery(DatabaseConfig.sql(countQuery))
                .execute(DatabaseConfig.tuple(countQuery))
                .flatMap { countRows ->
                    val total = countRows.iterator().next().getLong("total") ?: 0L
                    pool
                        .preparedQuery(DatabaseConfig.sql(dataQuery))
                        .execute(DatabaseConfig.tuple(dataQuery))
                        .flatMap { dataRows ->
                            val records = JsonArray()
                            val execIds = mutableListOf<String>()
                            for (row in dataRows) {
                                records.add(executionWithSummaryJson(row, now))
                                row.getValue("id")?.toString()?.let { execIds.add(it) }
                            }
                            pool
                                .preparedQuery(DatabaseConfig.sql(overdueTotalQuery))
                                .execute(DatabaseConfig.tuple(overdueTotalQuery))
                                .flatMap { otRows ->
                                    val overdueTotal = otRows.iterator().next().getLong("overdue_total") ?: 0L
                                    loadConsumptionSummaryBatch(execIds).map { summary ->
                                        for (i in 0 until records.size()) {
                                            val record = records.getJsonObject(i)
                                            val eid = record.getString("id")
                                            eid?.let { summary[it]?.let { s -> record.put("consumption_summary", s) } }
                                        }
                                        JsonObject()
                                            .put("records", records)
                                            .put(
                                                "meta",
                                                JsonObject()
                                                    .put("total", total)
                                                    .put("overdue_total", overdueTotal),
                                            )
                                    }
                                }
                        }
                }
        }
    }

    // ========================================================================
    //  执行统计（护理员工作量与计划完成率）
    // ========================================================================

    fun executionStatistics(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        periodId: String? = null,
        executor: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val zone = java.time.ZoneOffset.UTC
        val rangeStart = dateFrom.atStartOfDay().atOffset(zone)
        val rangeEnd = dateTo.plusDays(1).atStartOfDay().atOffset(zone)

        // 真实表别名：e = nursing_task_executions，t = nursing_tasks。
        // 不得复用旧查询输出别名（cStatus/cTaskId/ctId/ctPeriodId 等），
        // 这些不是本查询 FROM/JOIN 中的真实列，且 status 在执行表和任务表都存在。
        val execTable = DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e")
        val taskTableAlias = DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t")
        val eStatus = DSL.field("e.status", String::class.java)
        val ePlannedTime = DSL.field("e.planned_time", OffsetDateTime::class.java)
        val eExecutor = DSL.field("e.executor", String::class.java)
        val eTaskId = DSL.field("e.task_id", String::class.java)
        val tId = DSL.field("t.id", String::class.java)
        val tPeriodId = DSL.field("t.period_id", String::class.java)

        // 基础条件：只包含有 planned_time 的记录
        val baseConditions = mutableListOf<org.jooq.Condition>()
        baseConditions.add(ePlannedTime.ge(rangeStart))
        baseConditions.add(ePlannedTime.lt(rangeEnd))
        periodId?.let { baseConditions.add(tPeriodId.eq(it)) }
        executor?.let { baseConditions.add(eExecutor.eq(it)) }

        // 全局汇总查询
        val globalQuery =
            ctx
                .select(
                    count().`as`("scheduled_total"),
                    count().filterWhere(eStatus.eq("PENDING")).`as`("pending_total"),
                    count().filterWhere(eStatus.eq("IN_PROGRESS")).`as`("in_progress_total"),
                    count().filterWhere(eStatus.eq("COMPLETED")).`as`("completed_total"),
                    count().filterWhere(eStatus.eq("SKIPPED")).`as`("skipped_total"),
                    count().filterWhere(eStatus.eq("CANCELLED")).`as`("cancelled_total"),
                    count().filterWhere(ePlannedTime.lt(now)).`as`("due_total"),
                    count().filterWhere(eStatus.eq("COMPLETED").and(ePlannedTime.lt(now))).`as`("completed_due_total"),
                    count().filterWhere(eStatus.`in`("PENDING", "IN_PROGRESS").and(ePlannedTime.lt(now))).`as`("overdue_total"),
                ).from(execTable)
                .join(taskTableAlias)
                .on(eTaskId.eq(tId))
                .where(baseConditions)

        // 按执行人分组查询
        val groupedQuery =
            ctx
                .select(
                    DSL.field("e.executor").`as`("executor"),
                    count().`as`("scheduled_total"),
                    count().filterWhere(eStatus.eq("PENDING")).`as`("pending_total"),
                    count().filterWhere(eStatus.eq("IN_PROGRESS")).`as`("in_progress_total"),
                    count().filterWhere(eStatus.eq("COMPLETED")).`as`("completed_total"),
                    count().filterWhere(eStatus.eq("SKIPPED")).`as`("skipped_total"),
                    count().filterWhere(eStatus.eq("CANCELLED")).`as`("cancelled_total"),
                    count().filterWhere(ePlannedTime.lt(now)).`as`("due_total"),
                    count().filterWhere(eStatus.eq("COMPLETED").and(ePlannedTime.lt(now))).`as`("completed_due_total"),
                    count().filterWhere(eStatus.`in`("PENDING", "IN_PROGRESS").and(ePlannedTime.lt(now))).`as`("overdue_total"),
                ).from(execTable)
                .join(taskTableAlias)
                .on(eTaskId.eq(tId))
                .where(baseConditions)
                .groupBy(DSL.field("e.executor"))
                .orderBy(
                    DSL.field("due_total").desc(),
                    DSL.field("scheduled_total").desc(),
                    DSL.field("e.executor").asc().nullsLast(),
                ).limit(limit)
                .offset(offset)

        // 分组总数查询（不受 limit/offset 影响）
        val groupCountQuery =
            ctx
                .select(count().`as`("total"))
                .from(
                    ctx
                        .select(DSL.field("e.executor"))
                        .from(execTable)
                        .join(taskTableAlias)
                        .on(eTaskId.eq(tId))
                        .where(baseConditions)
                        .groupBy(DSL.field("e.executor")),
                )

        return pool
            .preparedQuery(DatabaseConfig.sql(globalQuery))
            .execute(DatabaseConfig.tuple(globalQuery))
            .flatMap { globalRows ->
                val global = globalRows.iterator().next()
                val scheduledTotal = global.getLong("scheduled_total") ?: 0L
                val pendingTotal = global.getLong("pending_total") ?: 0L
                val inProgressTotal = global.getLong("in_progress_total") ?: 0L
                val completedTotal = global.getLong("completed_total") ?: 0L
                val skippedTotal = global.getLong("skipped_total") ?: 0L
                val cancelledTotal = global.getLong("cancelled_total") ?: 0L
                val dueTotal = global.getLong("due_total") ?: 0L
                val completedDueTotal = global.getLong("completed_due_total") ?: 0L
                val overdueTotal = global.getLong("overdue_total") ?: 0L

                pool
                    .preparedQuery(DatabaseConfig.sql(groupCountQuery))
                    .execute(DatabaseConfig.tuple(groupCountQuery))
                    .flatMap { countRows ->
                        val groupTotal = countRows.iterator().next().getLong("total") ?: 0L

                        pool
                            .preparedQuery(DatabaseConfig.sql(groupedQuery))
                            .execute(DatabaseConfig.tuple(groupedQuery))
                            .map { dataRows ->
                                val records = JsonArray()
                                for (row in dataRows) {
                                    val ex = row.getValue("executor")?.toString()
                                    val gs = row.getLong("scheduled_total") ?: 0L
                                    val gp = row.getLong("pending_total") ?: 0L
                                    val gi = row.getLong("in_progress_total") ?: 0L
                                    val gc = row.getLong("completed_total") ?: 0L
                                    val gsk = row.getLong("skipped_total") ?: 0L
                                    val gca = row.getLong("cancelled_total") ?: 0L
                                    val gd = row.getLong("due_total") ?: 0L
                                    val gcd = row.getLong("completed_due_total") ?: 0L
                                    val gov = row.getLong("overdue_total") ?: 0L
                                    records.add(
                                        JsonObject()
                                            .put("executor", ex)
                                            .put("scheduled_total", gs)
                                            .put("pending_total", gp)
                                            .put("in_progress_total", gi)
                                            .put("completed_total", gc)
                                            .put("skipped_total", gsk)
                                            .put("cancelled_total", gca)
                                            .put("due_total", gd)
                                            .put("completed_due_total", gcd)
                                            .put("overdue_total", gov)
                                            .put("completion_rate", completionRate(gcd, gd)),
                                    )
                                }

                                val globalCompletionRate = completionRate(completedDueTotal, dueTotal)

                                JsonObject()
                                    .put("records", records)
                                    .put(
                                        "meta",
                                        JsonObject()
                                            .put("total", groupTotal)
                                            .put("date_from", dateFrom.toString())
                                            .put("date_to", dateTo.toString())
                                            .put("scheduled_total", scheduledTotal)
                                            .put("pending_total", pendingTotal)
                                            .put("in_progress_total", inProgressTotal)
                                            .put("completed_total", completedTotal)
                                            .put("skipped_total", skippedTotal)
                                            .put("cancelled_total", cancelledTotal)
                                            .put("due_total", dueTotal)
                                            .put("completed_due_total", completedDueTotal)
                                            .put("overdue_total", overdueTotal)
                                            .put("completion_rate", globalCompletionRate),
                                    )
                            }
                    }
            }
    }
}
