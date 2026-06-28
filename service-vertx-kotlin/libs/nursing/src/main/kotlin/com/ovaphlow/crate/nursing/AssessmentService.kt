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
import java.time.LocalDate
import java.time.OffsetDateTime

class AssessmentService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table("nursing_assessments")

    private val cId = DSL.field("id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cPeriodId = DSL.field("period_id", String::class.java)
    private val cAssessType = DSL.field("assess_type", String::class.java)
    private val cAssessDate = DSL.field("assess_date", LocalDate::class.java)
    private val cAssessor = DSL.field("assessor", String::class.java)
    private val cTotalScore = DSL.field("total_score", BigDecimal::class.java)
    private val cResultLevel = DSL.field("result_level", String::class.java)
    private val cDetail = DSL.field("detail", JSONB::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)

    companion object {
        private val VALID_ASSESS_TYPES = setOf(
            "ADMISSION", "FALL_RISK", "PRESSURE_SORE", "PAIN",
            "BARTHEL", "NUTRITION", "HOME_ENVIRONMENT", "OTHER"
        )

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("assess_type", row.getValue("assess_type")?.toString())
                .put("assess_date", row.getValue("assess_date")?.toString())
                .put("assessor", row.getValue("assessor")?.toString())
                .put("total_score", (row.getValue("total_score") as? BigDecimal)?.toDouble())
                .put("result_level", row.getValue("result_level")?.toString())
                .put("detail", row.getValue("detail") as? JsonObject)
                .put("remark", row.getValue("remark")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val assessType = body.getString("assess_type")
        val encounterId = body.getString("encounter_id")
        val periodId = body.getString("period_id")

        if (assessType.isNullOrBlank() || assessType !in VALID_ASSESS_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid assess_type, must be one of: $VALID_ASSESS_TYPES"))
        if (body.getString("assess_date").isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("assess_date is required"))
        if (encounterId.isNullOrBlank() && periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("either encounter_id or period_id is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cEncounterId, encounterId?.takeIf { it.isNotBlank() })
            .set(cPeriodId, periodId?.takeIf { it.isNotBlank() })
            .set(cAssessType, assessType)
            .set(cAssessDate, LocalDate.parse(body.getString("assess_date")))
            .set(cAssessor, body.getString("assessor"))
            .set(cTotalScore, body.getDouble("total_score")?.let { BigDecimal.valueOf(it) })
            .set(cResultLevel, body.getString("result_level"))
            .set(cDetail, body.containsKey("detail")
                .let { if (it) JSONB.valueOf(body.getJsonObject("detail").encode()) else null })
            .set(cRemark, body.getString("remark"))
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
                JsonObject()
                    .put("id", id)
                    .put("encounter_id", encounterId?.takeIf { it.isNotBlank() })
                    .put("period_id", periodId?.takeIf { it.isNotBlank() })
                    .put("assess_type", assessType)
                    .put("assess_date", body.getString("assess_date"))
                    .put("assessor", body.getString("assessor"))
                    .put("total_score", body.getDouble("total_score"))
                    .put("result_level", body.getString("result_level"))
                    .put("detail", body.getJsonObject("detail"))
                    .put("remark", body.getString("remark"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
            }
    }

    fun list(
        encounterId: String? = null,
        periodId: String? = null,
        assessType: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        encounterId?.let { conditions.add(cEncounterId.eq(it)) }
        periodId?.let { conditions.add(cPeriodId.eq(it)) }
        assessType?.let { conditions.add(cAssessType.eq(it)) }

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
                    Future.failedFuture(NotFoundException("assessment not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            var q = ctx.update(t).set(cAssessor, body.getString("assessor"))

            if (body.containsKey("total_score"))
                q = q.set(cTotalScore, body.getDouble("total_score")?.let { BigDecimal.valueOf(it) })
            if (body.containsKey("result_level"))
                q = q.set(cResultLevel, body.getString("result_level"))
            if (body.containsKey("detail"))
                q = q.set(cDetail, JSONB.valueOf(body.getJsonObject("detail").encode()))
            if (body.containsKey("remark"))
                q = q.set(cRemark, body.getString("remark"))
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }

    fun delete(id: String): Future<Void?> {
        val query = ctx.deleteFrom(t).where(cId.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }
}
