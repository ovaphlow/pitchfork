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

class PlanService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table("nursing_plans")
    private val ti = DSL.table("nursing_plan_items")

    // plan columns
    private val cId = DSL.field("id", String::class.java)
    private val cPeriodId = DSL.field("period_id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cPlanName = DSL.field("plan_name", String::class.java)
    private val cGoals = DSL.field("goals", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cCreatedBy = DSL.field("created_by", String::class.java)
    private val cStartDate = DSL.field("start_date", LocalDate::class.java)
    private val cEndDate = DSL.field("end_date", LocalDate::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    // plan item columns
    private val ciId = DSL.field("id", String::class.java)
    private val ciPlanId = DSL.field("plan_id", String::class.java)
    private val ciAction = DSL.field("action", String::class.java)
    private val ciFrequencyCode = DSL.field("frequency_code", String::class.java)
    private val ciFrequencyName = DSL.field("frequency_name", String::class.java)
    private val ciDurationDays = DSL.field("duration_days", Int::class.java)
    private val ciRemark = DSL.field("remark", String::class.java)
    private val ciItemStatus = DSL.field("status", String::class.java)
    private val ciMetadata = DSL.field("metadata", JSONB::class.java)
    private val ciCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)

    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("COMPLETED", "DISCONTINUED"),
            "COMPLETED" to emptyList(),
            "DISCONTINUED" to emptyList()
        )

        fun planToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("plan_name", row.getValue("plan_name")?.toString())
                .put("goals", row.getValue("goals")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("created_by", row.getValue("created_by")?.toString())
                .put("start_date", row.getValue("start_date")?.toString())
                .put("end_date", row.getValue("end_date")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun itemToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("plan_id", row.getValue("plan_id")?.toString())
                .put("action", row.getValue("action")?.toString())
                .put("frequency_code", row.getValue("frequency_code")?.toString())
                .put("frequency_name", row.getValue("frequency_name")?.toString())
                .put("duration_days", row.getValue("duration_days") as? Int)
                .put("remark", row.getValue("remark")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val periodId = body.getString("period_id")
        val planName = body.getString("plan_name")
        if (periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("period_id is required"))
        if (planName.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("plan_name is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cPeriodId, periodId)
            .set(cEncounterId, body.getString("encounter_id"))
            .set(cPlanName, planName)
            .set(cGoals, body.getString("goals"))
            .set(cStatus, "ACTIVE")
            .set(cCreatedBy, body.getString("created_by"))
            .set(cStartDate, body.getString("start_date")?.let { LocalDate.parse(it) })
            .set(cEndDate, body.getString("end_date")?.let { LocalDate.parse(it) })
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { _ ->
                val plan = JsonObject()
                    .put("id", id)
                    .put("period_id", periodId)
                    .put("encounter_id", body.getString("encounter_id"))
                    .put("plan_name", planName)
                    .put("goals", body.getString("goals"))
                    .put("status", "ACTIVE")
                    .put("created_by", body.getString("created_by"))
                    .put("start_date", body.getString("start_date"))
                    .put("end_date", body.getString("end_date"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
                    .put("updated_at", now.toString())

                val itemsArray = body.getJsonArray("items")
                if (itemsArray == null || itemsArray.isEmpty)
                    return@flatMap Future.succeededFuture(plan)

                val items = JsonArray()
                fun insertItem(index: Int): Future<JsonObject> {
                    if (index >= itemsArray.size()) {
                        plan.put("items", items)
                        return Future.succeededFuture(plan)
                    }
                    val itemObj = itemsArray.getJsonObject(index)
                    val itemId = Ulid.generate()
                    val itemInsert = ctx.insertInto(ti)
                        .set(ciId, itemId)
                        .set(ciPlanId, id)
                        .set(ciAction, itemObj.getString("action"))
                        .set(ciFrequencyCode, itemObj.getString("frequency_code"))
                        .set(ciFrequencyName, itemObj.getString("frequency_name"))
                        .set(ciDurationDays, itemObj.getInteger("duration_days"))
                        .set(ciRemark, itemObj.getString("remark"))
                        .set(ciItemStatus, "ACTIVE")
                        .set(ciMetadata, itemObj.containsKey("metadata")
                            .let { if (it) JSONB.valueOf(itemObj.getJsonObject("metadata").encode()) else null })
                        .set(ciCreatedAt, now)

                    return pool.preparedQuery(DatabaseConfig.sql(itemInsert))
                        .execute(DatabaseConfig.tuple(itemInsert))
                        .flatMap { _ ->
                            items.add(JsonObject()
                                .put("id", itemId)
                                .put("plan_id", id)
                                .put("action", itemObj.getString("action"))
                                .put("frequency_code", itemObj.getString("frequency_code"))
                                .put("frequency_name", itemObj.getString("frequency_name"))
                                .put("duration_days", itemObj.getInteger("duration_days"))
                                .put("remark", itemObj.getString("remark"))
                                .put("status", "ACTIVE")
                                .put("metadata", itemObj.getJsonObject("metadata"))
                                .put("created_at", now.toString()))
                            insertItem(index + 1)
                        }
                }
                insertItem(0)
            }
    }

    fun list(
        periodId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        periodId?.let { conditions.add(cPeriodId.eq(it)) }
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
                        for (row in dataRows) records.add(planToJson(row))
                        JsonObject().put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        val itemsQuery = ctx.selectFrom(ti).where(ciPlanId.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0)
                    Future.failedFuture(NotFoundException("plan not found: $id"))
                else {
                    val plan = planToJson(rows.iterator().next())
                    pool.preparedQuery(DatabaseConfig.sql(itemsQuery))
                        .execute(DatabaseConfig.tuple(itemsQuery))
                        .map { itemRows ->
                            val items = JsonArray()
                            for (row in itemRows) items.add(itemToJson(row))
                            plan.put("items", items)
                            plan
                        }
                }
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
