package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.time.OffsetDateTime

class TaskExecutionService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table("nursing_task_executions")

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

    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "PENDING" to listOf("IN_PROGRESS", "SKIPPED", "CANCELLED"),
            "IN_PROGRESS" to listOf("COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "SKIPPED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("task_id", row.getValue("task_id")?.toString())
                .put("planned_time", row.getValue("planned_time")?.toString())
                .put("actual_time", row.getValue("actual_time")?.toString())
                .put("executor", row.getValue("executor")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("quantity", (row.getValue("quantity") as? BigDecimal)?.toDouble())
                .put("note", row.getValue("note")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val taskId = body.getString("task_id")
        if (taskId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("task_id is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cTaskId, taskId)
            .set(cPlannedTime, body.getString("planned_time")?.let { OffsetDateTime.parse(it) })
            .set(cActualTime, body.getString("actual_time")?.let { OffsetDateTime.parse(it) })
            .set(cExecutor, body.getString("executor"))
            .set(cStatus, "PENDING")
            .set(cStockOpDetailId, body.getString("stock_operation_detail_id"))
            .set(cQuantity, body.getDouble("quantity")?.let { BigDecimal.valueOf(it) })
            .set(cNote, body.getString("note"))
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
                JsonObject()
                    .put("id", id)
                    .put("task_id", taskId)
                    .put("planned_time", body.getString("planned_time"))
                    .put("actual_time", body.getString("actual_time"))
                    .put("executor", body.getString("executor"))
                    .put("status", "PENDING")
                    .put("stock_operation_detail_id", body.getString("stock_operation_detail_id"))
                    .put("quantity", body.getDouble("quantity"))
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
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        taskId?.let { conditions.add(cTaskId.eq(it)) }
        executor?.let { conditions.add(cExecutor.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }

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
                    Future.failedFuture(NotFoundException("execution not found: $id"))
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
            var q = ctx.update(t).set(cStatus, newStatus)

            if (newStatus == "IN_PROGRESS" && existing.getString("actual_time") == null)
                q = q.set(cActualTime, now)
            if (newStatus == "COMPLETED" && existing.getString("actual_time") == null)
                q = q.set(cActualTime, now)

            val updateQuery = q.where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            var q = ctx.update(t).set(cExecutor, body.getString("executor"))

            if (body.containsKey("actual_time"))
                q = q.set(cActualTime, body.getString("actual_time")?.let { OffsetDateTime.parse(it) })
            if (body.containsKey("planned_time"))
                q = q.set(cPlannedTime, body.getString("planned_time")?.let { OffsetDateTime.parse(it) })
            if (body.containsKey("note"))
                q = q.set(cNote, body.getString("note"))
            if (body.containsKey("quantity"))
                q = q.set(cQuantity, body.getDouble("quantity")?.let { BigDecimal.valueOf(it) })
            if (body.containsKey("stock_operation_detail_id"))
                q = q.set(cStockOpDetailId, body.getString("stock_operation_detail_id"))
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }
}
