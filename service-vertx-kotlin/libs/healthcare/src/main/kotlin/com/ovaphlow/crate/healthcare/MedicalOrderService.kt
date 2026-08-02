package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.MedicalOrders.MEDICAL_ORDERS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingServicePeriods.NURSING_SERVICE_PERIODS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTaskExecutions.NURSING_TASK_EXECUTIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTasks.NURSING_TASKS
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.TaskService
import com.ovaphlow.crate.nursing.TaskService.OrderTaskInput
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class MedicalOrderService(
    private val pool: Pool,
    private val taskService: TaskService,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        val VALID_ORDER_TYPES = setOf("MEDICATION", "THERAPY", "EXAMINATION", "LAB_TEST")
        val VALID_ORDER_STATUSES = setOf("ACTIVE", "DISCONTINUED", "CANCELLED", "COMPLETED")
        val STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("DISCONTINUED", "CANCELLED", "COMPLETED"),
            "DISCONTINUED" to emptyList(),
            "CANCELLED" to emptyList(),
            "COMPLETED" to emptyList(),
        )
        val ORDER_TASK_TYPE = mapOf(
            "MEDICATION" to "MEDICATION",
            "THERAPY" to "TREATMENT",
            "EXAMINATION" to "TREATMENT",
            "LAB_TEST" to "TREATMENT",
        )
        val DETAIL_WHITELIST = mapOf(
            "MEDICATION" to setOf("drug_name", "dose", "unit", "route", "frequency_code", "frequency_name", "duration_days", "remark"),
            "THERAPY" to setOf("treatment_item", "frequency_code", "frequency_name", "duration_days", "remark"),
            "EXAMINATION" to setOf("item_name", "remark"),
            "LAB_TEST" to setOf("item_name", "remark"),
        )
        val REQUIRED_DETAIL_KEY = mapOf(
            "MEDICATION" to "drug_name",
            "THERAPY" to "treatment_item",
            "EXAMINATION" to "item_name",
            "LAB_TEST" to "item_name",
        )
        private val businessZone = ZoneId.of("Asia/Shanghai")
        private val OPEN_PERIOD_STATUSES = setOf("ACTIVE", "SUSPENDED")
    }

    fun createOrder(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateCreateInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                lockPeriod(connection, encounterId).compose { period ->
                    if (period.getString("status") !in OPEN_PERIOD_STATUSES) {
                        return@compose Future.failedFuture(
                            ConflictException("nursing care period is not open: ${period.getString("status")}")
                        )
                    }
                    if (period.getString("patient_id") != encounter.getString("patient_id")) {
                        return@compose Future.failedFuture(
                            ConflictException("patient_id mismatch between period and encounter")
                        )
                    }

                    val orderId = Ulid.generate()
                    val now = OffsetDateTime.now()
                    val insertQuery = ctx.insertInto(MEDICAL_ORDERS)
                        .set(MEDICAL_ORDERS.ID, orderId)
                        .set(MEDICAL_ORDERS.ENCOUNTER_ID, encounterId)
                        .set(MEDICAL_ORDERS.ORDER_TYPE, input.orderType)
                        .set(MEDICAL_ORDERS.ORDER_CONTENT, input.orderContent)
                        .set(MEDICAL_ORDERS.ORDER_DETAILS, JSONB.valueOf(input.orderDetails.encode()))
                        .set(MEDICAL_ORDERS.START_TIME, input.startTime)
                        .set(MEDICAL_ORDERS.DOCTOR, input.doctor)
                        .set(MEDICAL_ORDERS.STATUS, "ACTIVE")
                        .set(MEDICAL_ORDERS.CREATED_AT, now)
                        .set(MEDICAL_ORDERS.UPDATED_AT, now)

                    execute(connection, insertQuery).compose {
                        val startDate = businessDate(input.startTime)
                        val endDate = if (input.orderDetails.containsKey("duration_days")) {
                            startDate.plusDays(
                                (input.orderDetails.getValue("duration_days") as Number).toLong()
                            )
                        } else {
                            null
                        }
                        taskService.createOrderTask(
                            connection,
                            OrderTaskInput(
                                periodId = requireNotNull(period.getString("id")),
                                encounterId = encounterId,
                                orderItemId = orderId,
                                taskType = ORDER_TASK_TYPE.getValue(input.orderType),
                                description = input.orderContent,
                                frequencyCode = input.orderDetails.getString("frequency_code")
                                    ?.trim()?.takeIf { it.isNotBlank() },
                                frequencyName = input.orderDetails.getString("frequency_name")
                                    ?.trim()?.takeIf { it.isNotBlank() },
                                startDate = startDate,
                                endDate = endDate,
                            ),
                        )
                    }.compose {
                        readOrderWithTask(connection, orderId).map { row ->
                            orderJson(row, row.getString("task_id"))
                        }
                    }
                }
            }
        }
    }

    fun listOrders(
        encounterId: String,
        orderType: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        conditions.add(MEDICAL_ORDERS.ENCOUNTER_ID.eq(encounterId))
        orderType?.takeIf(String::isNotBlank)?.let { conditions.add(MEDICAL_ORDERS.ORDER_TYPE.eq(it)) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(MEDICAL_ORDERS.STATUS.eq(it)) }

        val joinOn = MEDICAL_ORDERS.ID.eq(NURSING_TASKS.ORDER_ITEM_ID)
        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS).on(joinOn)
            .where(conditions)
        val dataQuery = ctx.select(orderSelectFields())
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS).on(joinOn)
            .where(conditions)
            .orderBy(MEDICAL_ORDERS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map { orderJson(it, it.getString("task_id")) }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getOrder(id: String): Future<JsonObject> {
        val query = ctx.select(orderSelectFields())
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS)
            .on(MEDICAL_ORDERS.ID.eq(NURSING_TASKS.ORDER_ITEM_ID))
            .where(MEDICAL_ORDERS.ID.eq(id))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("order not found: $id"))

            val summaryQuery = ctx.select(NURSING_TASK_EXECUTIONS.STATUS, DSL.count().`as`("cnt"))
                .from(NURSING_TASK_EXECUTIONS)
                .join(NURSING_TASKS)
                .on(NURSING_TASK_EXECUTIONS.TASK_ID.eq(NURSING_TASKS.ID))
                .where(NURSING_TASKS.ORDER_ITEM_ID.eq(id))
                .groupBy(NURSING_TASK_EXECUTIONS.STATUS)
            execute(pool, summaryQuery).map { summaryRows ->
                val summary = JsonObject()
                    .put("PENDING", 0)
                    .put("IN_PROGRESS", 0)
                    .put("COMPLETED", 0)
                    .put("SKIPPED", 0)
                    .put("CANCELLED", 0)
                for (summaryRow in summaryRows) {
                    val execStatus = summaryRow.getString("status") ?: continue
                    summary.put(execStatus, summaryRow.getLong("cnt") ?: 0L)
                }
                orderJson(row, row.getString("task_id")).put("execution_summary", summary)
            }
        }
    }

    fun updateOrderStatus(id: String, body: JsonObject): Future<JsonObject> {
        val target = try {
            requiredText(body, "status")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        if (target !in VALID_ORDER_STATUSES) {
            return Future.failedFuture(
                IllegalArgumentException("invalid order status, must be one of: $VALID_ORDER_STATUSES")
            )
        }

        return pool.withTransaction<JsonObject> { connection ->
            lockOrder(connection, id).compose { row ->
                val current = row.getString("status")
                if (current != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("order is already $current"))
                }
                if (target !in STATUS_TRANSITIONS.getValue(current)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("cannot transition from $current to $target")
                    )
                }

                val now = OffsetDateTime.now()
                val taskTarget = if (target == "COMPLETED") "COMPLETED" else "CANCELLED"
                taskService.terminateOrderTask(connection, id, taskTarget)
                    .compose {
                        val updateQuery = ctx.update(MEDICAL_ORDERS)
                            .set(MEDICAL_ORDERS.STATUS, target)
                            .set(MEDICAL_ORDERS.END_TIME, now)
                            .set(MEDICAL_ORDERS.UPDATED_AT, now)
                            .where(MEDICAL_ORDERS.ID.eq(id))
                        execute(connection, updateQuery)
                    }
                    .compose {
                        readOrderWithTask(connection, id).map { orderRow ->
                            orderJson(orderRow, orderRow.getString("task_id"))
                        }
                    }
            }
        }
    }

    /**
     * 离院/去世终局编排复用：锁读该 encounter 全部 ACTIVE 医嘱，逐条改为
     * DISCONTINUED 并同连接取消关联任务。必须在调用方外层事务内执行。
     */
    fun terminateEncounterOrders(
        client: SqlClient,
        encounterId: String,
        endTime: OffsetDateTime,
    ): Future<Void> {
        val query = ctx.selectFrom(MEDICAL_ORDERS)
            .where(MEDICAL_ORDERS.ENCOUNTER_ID.eq(encounterId))
            .and(MEDICAL_ORDERS.STATUS.eq("ACTIVE"))
            .forUpdate()
        return execute(client, query).compose { rows ->
            val now = OffsetDateTime.now()
            terminateOrders(client, rows.map { it.getString("id") }, endTime, now)
        }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    private fun lockEncounter(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(id)).forUpdate()).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("patient_id", row.getString("patient_id"))
                        .put("encounter_type", row.getString("encounter_type"))
                        .put("status", row.getString("status"))
                )
            } ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    private fun lockPeriod(client: SqlClient, encounterId: String): Future<JsonObject> =
        execute(
            client,
            ctx.selectFrom(NURSING_SERVICE_PERIODS)
                .where(NURSING_SERVICE_PERIODS.ENCOUNTER_ID.eq(encounterId))
                .forUpdate(),
        ).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(
                    ConflictException("elderly admission has no bound nursing care period: $encounterId")
                )
            } else {
                Future.succeededFuture(
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("patient_id", row.getString("patient_id"))
                        .put("status", row.getString("status"))
                )
            }
        }

    private fun lockOrder(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(MEDICAL_ORDERS).where(MEDICAL_ORDERS.ID.eq(id)).forUpdate()).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(HealthcareNotFoundException("order not found: $id"))
            } else {
                Future.succeededFuture(row)
            }
        }

    private fun readOrderWithTask(client: SqlClient, id: String): Future<Row> {
        val query = ctx.select(orderSelectFields())
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS)
            .on(MEDICAL_ORDERS.ID.eq(NURSING_TASKS.ORDER_ITEM_ID))
            .where(MEDICAL_ORDERS.ID.eq(id))
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(HealthcareNotFoundException("order not found: $id"))
            } else {
                Future.succeededFuture(row)
            }
        }
    }

    private fun terminateOrders(
        client: SqlClient,
        orderIds: List<String>,
        endTime: OffsetDateTime,
        now: OffsetDateTime,
    ): Future<Void> {
        fun loop(index: Int): Future<Void> {
            if (index >= orderIds.size) return Future.succeededFuture()
            val orderId = orderIds[index]
            val updateQuery = ctx.update(MEDICAL_ORDERS)
                .set(MEDICAL_ORDERS.STATUS, "DISCONTINUED")
                .set(MEDICAL_ORDERS.END_TIME, endTime)
                .set(MEDICAL_ORDERS.UPDATED_AT, now)
                .where(MEDICAL_ORDERS.ID.eq(orderId))
            return execute(client, updateQuery)
                .compose { taskService.terminateOrderTask(client, orderId, "CANCELLED") }
                .compose { loop(index + 1) }
        }
        return loop(0)
    }

    private fun orderSelectFields(): List<org.jooq.Field<*>> =
        MEDICAL_ORDERS.fields().toList() + NURSING_TASKS.ID.`as`("task_id")

    private fun validateCreateInput(body: JsonObject): CreateOrderInput {
        val orderType = validStatus(body.getString("order_type"), VALID_ORDER_TYPES, "order_type")
        val orderContent = requiredText(body, "order_content")
        if (orderContent.length > 2000) {
            throw IllegalArgumentException("order_content must not exceed 2000 characters")
        }
        val doctor = requiredText(body, "doctor")
        if (doctor.length > 100) {
            throw IllegalArgumentException("doctor must not exceed 100 characters")
        }
        val startTime = offsetDateTime(requiredText(body, "start_time"), "start_time")
        val orderDetails = normalizeDetails(body.getValue("order_details"), orderType)
        return CreateOrderInput(orderType, orderContent, doctor, startTime, orderDetails)
    }

    private data class CreateOrderInput(
        val orderType: String,
        val orderContent: String,
        val doctor: String,
        val startTime: OffsetDateTime,
        val orderDetails: JsonObject,
    )

    private fun normalizeDetails(value: Any?, orderType: String): JsonObject {
        val raw = when (value) {
            null -> JsonObject()
            is JsonObject -> value
            else -> throw IllegalArgumentException("order_details must be a JSON object")
        }
        val allowedKeys = DETAIL_WHITELIST.getValue(orderType)
        val unsupportedKeys = raw.fieldNames().filter { it !in allowedKeys }
        if (unsupportedKeys.isNotEmpty()) {
            throw IllegalArgumentException("order_details contains unsupported keys: ${unsupportedKeys.joinToString(", ")}")
        }

        val normalized = JsonObject()
        for (key in raw.fieldNames()) {
            val fieldValue = raw.getValue(key)
            if (key == "duration_days") {
                if (fieldValue !is Int && fieldValue !is Long) {
                    throw IllegalArgumentException("duration_days must be a positive integer")
                }
                val duration = (fieldValue as Number).toLong()
                if (duration <= 0) throw IllegalArgumentException("duration_days must be a positive integer")
                normalized.put(key, fieldValue)
                continue
            }
            if (fieldValue !is String) {
                throw IllegalArgumentException("$key must be a string")
            }
            normalized.put(key, fieldValue.trim())
        }

        val requiredKey = REQUIRED_DETAIL_KEY.getValue(orderType)
        if (normalized.getString(requiredKey).isNullOrBlank()) {
            throw IllegalArgumentException("$requiredKey is required for $orderType order")
        }

        val hasFrequencyCode = normalized.containsKey("frequency_code")
        val hasFrequencyName = normalized.containsKey("frequency_name")
        if (hasFrequencyCode != hasFrequencyName) {
            throw IllegalArgumentException("frequency_code and frequency_name must be provided together")
        }
        return normalized
    }

    private fun orderJson(row: Row, taskId: String? = null): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("order_type", row.getString("order_type"))
            .put("order_content", row.getString("order_content"))
            .put("order_details", row.getValue("order_details") as? JsonObject)
            .put("start_time", row.getOffsetDateTime("start_time")?.toString())
            .put("end_time", row.getOffsetDateTime("end_time")?.toString())
            .put("doctor", row.getString("doctor"))
            .put("status", row.getString("status"))
            .put("task_id", taskId ?: row.getString("task_id"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

    private fun businessDate(value: OffsetDateTime): LocalDate =
        value.atZoneSameInstant(businessZone).toLocalDate()

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun validStatus(value: String?, allowed: Set<String>, label: String): String =
        value?.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun offsetDateTime(value: String, field: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 offset date-time")
        }
}
