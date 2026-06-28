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
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

class VisitScheduleService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table("nursing_visit_schedules")

    private val cId = DSL.field("id", String::class.java)
    private val cPeriodId = DSL.field("period_id", String::class.java)
    private val cTaskExecutionId = DSL.field("task_execution_id", String::class.java)
    private val cPlannedDate = DSL.field("planned_date", LocalDate::class.java)
    private val cPlannedStart = DSL.field("planned_start", LocalTime::class.java)
    private val cPlannedEnd = DSL.field("planned_end", LocalTime::class.java)
    private val cCaregiver = DSL.field("caregiver", String::class.java)
    private val cActualStart = DSL.field("actual_start", OffsetDateTime::class.java)
    private val cActualEnd = DSL.field("actual_end", OffsetDateTime::class.java)
    private val cTravelTime = DSL.field("travel_time", Int::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "SCHEDULED" to listOf("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS" to listOf("COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("task_execution_id", row.getValue("task_execution_id")?.toString())
                .put("planned_date", row.getValue("planned_date")?.toString())
                .put("planned_start", row.getValue("planned_start")?.toString())
                .put("planned_end", row.getValue("planned_end")?.toString())
                .put("caregiver", row.getValue("caregiver")?.toString())
                .put("actual_start", row.getValue("actual_start")?.toString())
                .put("actual_end", row.getValue("actual_end")?.toString())
                .put("travel_time", row.getValue("travel_time") as? Int)
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val periodId = body.getString("period_id")
        val plannedDate = body.getString("planned_date")

        if (periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("period_id is required"))
        if (plannedDate.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("planned_date is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cPeriodId, periodId)
            .set(cTaskExecutionId, body.getString("task_execution_id"))
            .set(cPlannedDate, LocalDate.parse(plannedDate))
            .set(cPlannedStart, body.getString("planned_start")?.let { LocalTime.parse(it) })
            .set(cPlannedEnd, body.getString("planned_end")?.let { LocalTime.parse(it) })
            .set(cCaregiver, body.getString("caregiver"))
            .set(cTravelTime, body.getInteger("travel_time"))
            .set(cStatus, "SCHEDULED")
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
                JsonObject()
                    .put("id", id)
                    .put("period_id", periodId)
                    .put("task_execution_id", body.getString("task_execution_id"))
                    .put("planned_date", plannedDate)
                    .put("planned_start", body.getString("planned_start"))
                    .put("planned_end", body.getString("planned_end"))
                    .put("caregiver", body.getString("caregiver"))
                    .put("travel_time", body.getInteger("travel_time"))
                    .put("status", "SCHEDULED")
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
                    .put("updated_at", now.toString())
            }
    }

    fun list(
        periodId: String? = null,
        caregiver: String? = null,
        status: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        periodId?.let { conditions.add(cPeriodId.eq(it)) }
        caregiver?.let { conditions.add(cCaregiver.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }
        dateFrom?.let { conditions.add(cPlannedDate.ge(LocalDate.parse(it))) }
        dateTo?.let { conditions.add(cPlannedDate.le(LocalDate.parse(it))) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t)
            .where(conditions)
            .orderBy(cPlannedDate.asc(), cPlannedStart.asc())
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
                    Future.failedFuture(NotFoundException("visit schedule not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun updateStatus(id: String, body: JsonObject): Future<JsonObject> {
        val newStatus = body.getString("status")
        if (newStatus.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("status is required"))

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
            if (newStatus !in allowedNext)
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus")
                )

            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cStatus, newStatus).set(cUpdatedAt, now)

            if (newStatus == "IN_PROGRESS" && !body.containsKey("actual_start"))
                q = q.set(cActualStart, now)
            if (newStatus == "COMPLETED" && !body.containsKey("actual_end"))
                q = q.set(cActualEnd, now)

            if (body.containsKey("actual_start"))
                q = q.set(cActualStart, body.getString("actual_start")?.let { OffsetDateTime.parse(it) })
            if (body.containsKey("actual_end"))
                q = q.set(cActualEnd, body.getString("actual_end")?.let { OffsetDateTime.parse(it) })
            if (body.containsKey("travel_time"))
                q = q.set(cTravelTime, body.getInteger("travel_time"))

            val updateQuery = q.where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }
}
