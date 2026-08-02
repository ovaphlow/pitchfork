package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTasks.NURSING_TASKS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.time.LocalDate
import java.time.OffsetDateTime

class TaskService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table(DSL.name("nursing", "nursing_tasks"))

    private val cId = DSL.field("id", String::class.java)
    private val cPeriodId = DSL.field("period_id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cPlanItemId = DSL.field("plan_item_id", String::class.java)
    private val cOrderItemId = DSL.field("order_item_id", String::class.java)
    private val cTaskType = DSL.field("task_type", String::class.java)
    private val cDescription = DSL.field("description", String::class.java)
    private val cFrequencyCode = DSL.field("frequency_code", String::class.java)
    private val cFrequencyName = DSL.field("frequency_name", String::class.java)
    private val cStartDate = DSL.field("start_date", LocalDate::class.java)
    private val cEndDate = DSL.field("end_date", LocalDate::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    companion object {
        private val VALID_TASK_TYPES = setOf("NURSING", "REHABILITATION", "LIVING_CARE", "HEALTH_EDUCATION", "OTHER")
        private val ORDER_TASK_TYPES = setOf("MEDICATION", "TREATMENT")
        private val ORDER_TERMINAL_STATUSES = setOf("CANCELLED", "COMPLETED")
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("plan_item_id", row.getValue("plan_item_id")?.toString())
                .put("order_item_id", row.getValue("order_item_id")?.toString())
                .put("task_type", row.getValue("task_type")?.toString())
                .put("description", row.getValue("description")?.toString())
                .put("frequency_code", row.getValue("frequency_code")?.toString())
                .put("frequency_name", row.getValue("frequency_name")?.toString())
                .put("start_date", row.getValue("start_date")?.toString())
                .put("end_date", row.getValue("end_date")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val taskType = body.getString("task_type")
        val description = body.getString("description")

        if (taskType.isNullOrBlank() || taskType !in VALID_TASK_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid task_type, must be one of: $VALID_TASK_TYPES"))
        if (description.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("description is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cPeriodId, body.getString("period_id"))
            .set(cEncounterId, body.getString("encounter_id"))
            .set(cPlanItemId, body.getString("plan_item_id"))
            .set(cOrderItemId, body.getString("order_item_id"))
            .set(cTaskType, taskType)
            .set(cDescription, description)
            .set(cFrequencyCode, body.getString("frequency_code"))
            .set(cFrequencyName, body.getString("frequency_name"))
            .set(cStartDate, body.getString("start_date")?.let { LocalDate.parse(it) })
            .set(cEndDate, body.getString("end_date")?.let { LocalDate.parse(it) })
            .set(cStatus, "ACTIVE")
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
                JsonObject()
                    .put("id", id)
                    .put("period_id", body.getString("period_id"))
                    .put("encounter_id", body.getString("encounter_id"))
                    .put("plan_item_id", body.getString("plan_item_id"))
                    .put("order_item_id", body.getString("order_item_id"))
                    .put("task_type", taskType)
                    .put("description", description)
                    .put("frequency_code", body.getString("frequency_code"))
                    .put("frequency_name", body.getString("frequency_name"))
                    .put("start_date", body.getString("start_date"))
                    .put("end_date", body.getString("end_date"))
                    .put("status", "ACTIVE")
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
                    .put("updated_at", now.toString())
            }
    }

    fun list(
        periodId: String? = null,
        encounterId: String? = null,
        taskType: String? = null,
        status: String? = null,
        planItemId: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        periodId?.let { conditions.add(cPeriodId.eq(it)) }
        encounterId?.let { conditions.add(cEncounterId.eq(it)) }
        taskType?.let { conditions.add(cTaskType.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }
        planItemId?.let { conditions.add(cPlanItemId.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t)
            .where(conditions)
            .orderBy(cCreatedAt.desc())
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
                        for (row in dataRows) records.add(toJson(row))
                        JsonObject().put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0)
                    Future.failedFuture(NotFoundException("task not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun updateStatus(id: String, newStatus: String): Future<JsonObject> {
        if (newStatus.isBlank())
            return Future.failedFuture(IllegalArgumentException("status is required"))

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
            if (newStatus !in allowedNext)
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus")
                )

            val now = OffsetDateTime.now()
            val updateQuery = ctx.update(t)
                .set(cStatus, newStatus)
                .set(cUpdatedAt, now)
                .where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }

    // ========================================================================
    //  医嘱派生护理任务 — 连接绑定内部协作（仅供 Healthcare 在同一事务内调用）
    // ========================================================================

    data class OrderTaskInput(
        val periodId: String,
        val encounterId: String,
        val orderItemId: String,
        val taskType: String,
        val description: String,
        val frequencyCode: String?,
        val frequencyName: String?,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
    )

    fun createOrderTask(client: SqlClient, input: OrderTaskInput): Future<JsonObject> {
        if (input.taskType !in ORDER_TASK_TYPES) {
            return Future.failedFuture(
                IllegalArgumentException("invalid order task_type, must be MEDICATION or TREATMENT")
            )
        }
        if (input.orderItemId.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("order_item_id is required"))
        }
        if (input.description.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("description is required"))
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        var insertQuery = ctx.insertInto(NURSING_TASKS)
            .set(NURSING_TASKS.ID, id)
            .set(NURSING_TASKS.PERIOD_ID, input.periodId)
            .set(NURSING_TASKS.ENCOUNTER_ID, input.encounterId)
            .set(NURSING_TASKS.ORDER_ITEM_ID, input.orderItemId)
            .set(NURSING_TASKS.TASK_TYPE, input.taskType)
            .set(NURSING_TASKS.DESCRIPTION, input.description)
            .set(NURSING_TASKS.STATUS, "ACTIVE")
            .set(NURSING_TASKS.CREATED_AT, now)
            .set(NURSING_TASKS.UPDATED_AT, now)
        input.frequencyCode?.let { insertQuery = insertQuery.set(NURSING_TASKS.FREQUENCY_CODE, it) }
        input.frequencyName?.let { insertQuery = insertQuery.set(NURSING_TASKS.FREQUENCY_NAME, it) }
        input.startDate?.let { insertQuery = insertQuery.set(NURSING_TASKS.START_DATE, it) }
        input.endDate?.let { insertQuery = insertQuery.set(NURSING_TASKS.END_DATE, it) }

        return execute(client, insertQuery).map {
            JsonObject()
                .put("id", id)
                .put("period_id", input.periodId)
                .put("encounter_id", input.encounterId)
                .put("order_item_id", input.orderItemId)
                .put("task_type", input.taskType)
                .put("description", input.description)
                .put("frequency_code", input.frequencyCode)
                .put("frequency_name", input.frequencyName)
                .put("start_date", input.startDate?.toString())
                .put("end_date", input.endDate?.toString())
                .put("status", "ACTIVE")
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
        }
    }

    fun lockOrderTask(client: SqlClient, orderItemId: String): Future<JsonObject?> {
        val query = ctx.selectFrom(NURSING_TASKS)
            .where(NURSING_TASKS.ORDER_ITEM_ID.eq(orderItemId))
            .forUpdate()
        return execute(client, query).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { toJson(it) }
        }
    }

    fun terminateOrderTask(client: SqlClient, orderItemId: String, targetStatus: String): Future<Void> {
        if (targetStatus !in ORDER_TERMINAL_STATUSES) {
            return Future.failedFuture(
                IllegalArgumentException("invalid target status, must be CANCELLED or COMPLETED")
            )
        }
        return lockOrderTask(client, orderItemId).compose { task ->
            if (task == null) {
                return@compose Future.failedFuture(ConflictException("order has no linked task: $orderItemId"))
            }
            val currentStatus = task.getString("status")
            if (currentStatus != "ACTIVE") {
                return@compose Future.failedFuture(
                    ConflictException("order task is in unexpected status: $currentStatus")
                )
            }
            val now = OffsetDateTime.now()
            val updateQuery = ctx.update(NURSING_TASKS)
                .set(NURSING_TASKS.STATUS, targetStatus)
                .set(NURSING_TASKS.UPDATED_AT, now)
                .where(NURSING_TASKS.ID.eq(requireNotNull(task.getString("id"))))
            execute(client, updateQuery).map<Void> { null }
        }
    }

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
