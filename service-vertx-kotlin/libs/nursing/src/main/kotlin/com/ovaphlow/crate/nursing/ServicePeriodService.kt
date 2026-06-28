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
import java.time.OffsetDateTime

class ServicePeriodService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table("nursing_service_periods")

    private val cId = DSL.field("id", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cServiceType = DSL.field("service_type", String::class.java)
    private val cStartDate = DSL.field("start_date", LocalDate::class.java)
    private val cEndDate = DSL.field("end_date", LocalDate::class.java)
    private val cCoordinator = DSL.field("coordinator", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    companion object {
        private val VALID_SERVICE_TYPES = setOf("HOME_CARE", "COMMUNITY_CARE", "HOSPICE")
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("SUSPENDED", "COMPLETED", "CANCELLED"),
            "SUSPENDED" to listOf("ACTIVE", "COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("patient_id", row.getValue("patient_id")?.toString())
                .put("service_type", row.getValue("service_type")?.toString())
                .put("start_date", row.getValue("start_date")?.toString())
                .put("end_date", row.getValue("end_date")?.toString())
                .put("coordinator", row.getValue("coordinator")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")
        val serviceType = body.getString("service_type")

        if (patientId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        if (serviceType.isNullOrBlank() || serviceType !in VALID_SERVICE_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid service_type, must be one of: $VALID_SERVICE_TYPES"))
        if (body.getString("start_date").isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("start_date is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cPatientId, patientId)
            .set(cServiceType, serviceType)
            .set(cStartDate, LocalDate.parse(body.getString("start_date")))
            .set(cEndDate, body.getString("end_date")?.let { LocalDate.parse(it) })
            .set(cCoordinator, body.getString("coordinator"))
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
                    .put("patient_id", patientId)
                    .put("service_type", serviceType)
                    .put("start_date", body.getString("start_date"))
                    .put("end_date", body.getString("end_date"))
                    .put("coordinator", body.getString("coordinator"))
                    .put("status", "ACTIVE")
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
                    .put("updated_at", now.toString())
            }
    }

    fun list(
        patientId: String? = null,
        serviceType: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.let { conditions.add(cPatientId.eq(it)) }
        serviceType?.let { conditions.add(cServiceType.eq(it)) }
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
                    Future.failedFuture(NotFoundException("service period not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cUpdatedAt, now)

            if (body.containsKey("coordinator"))
                q = q.set(cCoordinator, body.getString("coordinator"))
            if (body.containsKey("end_date"))
                q = q.set(cEndDate, body.getString("end_date")?.let { LocalDate.parse(it) })
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
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
}
