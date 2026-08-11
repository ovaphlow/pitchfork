package com.ovaphlow.crate.dining

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
import java.time.OffsetDateTime

/**
 * 长者饮食档案（FR-1）：餐食类型/忌口过敏/份量偏好/备注。
 * 建档时校验活动 ELDERLY_CARE 入住（入住自动生效）；
 * 离院后因配餐口径只纳入在院入住而自然停用，亦可显式停用/启用。
 */
class DietProfileService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = DSL.table(DSL.name("dining", "dining_diet_profiles"))
    private val cId = DSL.field("id", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cMealType = DSL.field("meal_type", String::class.java)
    private val cAllergies = DSL.field("allergies", JSONB::class.java)
    private val cPortionPreference = DSL.field("portion_preference", String::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    private val encounters = DSL.table(DSL.name("healthcare", "encounters"))
    private val cEncounterType = DSL.field("encounter_type", String::class.java)
    private val cEncounterStatus = DSL.field("status", String::class.java)
    private val patients = DSL.table(DSL.name("healthcare", "patients"))

    companion object {
        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("patient_id", row.getValue("patient_id")?.toString())
            .put("patient_name", row.getValue("patient_name")?.toString())
            .put("encounter_id", row.getValue("encounter_id")?.toString())
            .put("meal_type", row.getValue("meal_type")?.toString())
            .put("allergies", row.getValue("allergies") as? JsonArray ?: JsonArray())
            .put("portion_preference", row.getValue("portion_preference")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("encounter_status", row.getValue("encounter_status")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")
        val encounterId = body.getString("encounter_id")
        val mealType = body.getString("meal_type")
        if (patientId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        if (encounterId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("encounter_id is required"))
        if (mealType.isNullOrBlank() || mealType !in DiningConstants.MEAL_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}"))

        val allergies = stringArray(body.getJsonArray("allergies"), "allergies")
        val portion = body.getString("portion_preference")
        if (!portion.isNullOrBlank() && portion !in DiningConstants.PORTION_PREFERENCES)
            return Future.failedFuture(IllegalArgumentException("invalid portion_preference, must be one of: ${DiningConstants.PORTION_PREFERENCES}"))

        // 入住自动生效：仅允许为活动 ELDERLY_CARE 入住建档
        val encounterQuery = ctx.select(
            cEncounterType,
            cEncounterStatus,
            DSL.field("patient_id", String::class.java).`as`("enc_patient_id"),
        ).from(encounters).where(DSL.field("id").eq(encounterId))

        return execute(pool, encounterQuery).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(IllegalArgumentException("encounter not found: $encounterId"))
            if (row.getString("encounter_type") != "ELDERLY_CARE")
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            if (row.getString("status") != "ACTIVE")
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not active, 仅支持为在院入住建档"))
            if (row.getString("enc_patient_id") != patientId)
                return@compose Future.failedFuture(IllegalArgumentException("patient_id does not match the encounter"))

            val id = Ulid.generate()
            val now = OffsetDateTime.now()
            val query = ctx.insertInto(t)
                .set(cId, id)
                .set(cPatientId, patientId)
                .set(cEncounterId, encounterId)
                .set(cMealType, mealType)
                .set(cAllergies, JSONB.valueOf(allergies.encode()))
                .set(cPortionPreference, portion.takeIf { !it.isNullOrBlank() })
                .set(cRemark, body.getString("remark"))
                .set(cStatus, "启用")
                .set(cMetadata, body.containsKey("metadata").let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
                .set(cCreatedAt, now)
                .set(cUpdatedAt, now)

            execute(pool, query).compose { result ->
                if (result.rowCount() == 0)
                    return@compose Future.failedFuture(IllegalStateException("insert returned no row"))
                get(id)
            }.recover { error ->
                // 命中部分唯一索引（同一长者已有启用档案）→ 409
                if (error is io.vertx.pgclient.PgException && error.sqlState == "23505")
                    Future.failedFuture(DiningConflictException("该长者已有启用的饮食档案"))
                else Future.failedFuture(error)
            }
        }
    }

    fun list(
        patientId: String? = null,
        encounterId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("p", "patient_id")).eq(it)) }
        encounterId?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("p", "encounter_id")).eq(it)) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("p", "status")).eq(it)) }

        val e = encounters.`as`("e")
        val p = t.`as`("p")
        val pa = patients.`as`("pa")
        val joined = p
            .join(e).on(DSL.field(DSL.name("p", "encounter_id")).eq(DSL.field(DSL.name("e", "id"), String::class.java)))
            .join(pa).on(DSL.field(DSL.name("p", "patient_id")).eq(DSL.field(DSL.name("pa", "id"), String::class.java)))
        val countQuery = ctx.select(count().`as`("total")).from(joined).where(conditions)
        val dataQuery = ctx.select(
            DSL.field(DSL.name("p", "id")).`as`("id"),
            DSL.field(DSL.name("p", "patient_id")).`as`("patient_id"),
            DSL.field(DSL.name("pa", "name")).`as`("patient_name"),
            DSL.field(DSL.name("p", "encounter_id")).`as`("encounter_id"),
            DSL.field(DSL.name("p", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("p", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("p", "portion_preference")).`as`("portion_preference"),
            DSL.field(DSL.name("p", "remark")).`as`("remark"),
            DSL.field(DSL.name("p", "status")).`as`("status"),
            DSL.field(DSL.name("p", "metadata")).`as`("metadata"),
            DSL.field(DSL.name("p", "created_at")).`as`("created_at"),
            DSL.field(DSL.name("p", "updated_at")).`as`("updated_at"),
            DSL.field(DSL.name("e", "status"), String::class.java).`as`("encounter_status"),
        ).from(joined).where(conditions).orderBy(cCreatedAt.desc()).limit(limit).offset(offset)

        return execute(pool, countQuery).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        val e = encounters.`as`("e")
        val p = t.`as`("p")
        val pa = patients.`as`("pa")
        val pId = DSL.field(DSL.name("p", "id"))
        val query = ctx.select(
            pId.`as`("id"),
            DSL.field(DSL.name("p", "patient_id")).`as`("patient_id"),
            DSL.field(DSL.name("pa", "name")).`as`("patient_name"),
            DSL.field(DSL.name("p", "encounter_id")).`as`("encounter_id"),
            DSL.field(DSL.name("p", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("p", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("p", "portion_preference")).`as`("portion_preference"),
            DSL.field(DSL.name("p", "remark")).`as`("remark"),
            DSL.field(DSL.name("p", "status")).`as`("status"),
            DSL.field(DSL.name("p", "metadata")).`as`("metadata"),
            DSL.field(DSL.name("p", "created_at")).`as`("created_at"),
            DSL.field(DSL.name("p", "updated_at")).`as`("updated_at"),
            DSL.field(DSL.name("e", "status"), String::class.java).`as`("encounter_status"),
        ).from(p
            .join(e).on(DSL.field(DSL.name("p", "encounter_id")).eq(DSL.field(DSL.name("e", "id"))))
            .join(pa).on(DSL.field(DSL.name("p", "patient_id")).eq(DSL.field(DSL.name("pa", "id")))))
            .where(pId.eq(id))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(DiningNotFoundException("diet profile not found: $id"))
            else Future.succeededFuture(toJson(row))
        }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap {
            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cUpdatedAt, now)

            if (body.containsKey("meal_type")) {
                val mealType = body.getString("meal_type")
                if (mealType.isNullOrBlank() || mealType !in DiningConstants.MEAL_TYPES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}"))
                q = q.set(cMealType, mealType)
            }
            if (body.containsKey("allergies"))
                q = q.set(cAllergies, JSONB.valueOf(stringArray(body.getJsonArray("allergies"), "allergies").encode()))
            if (body.containsKey("portion_preference")) {
                val portion = body.getString("portion_preference")
                if (!portion.isNullOrBlank() && portion !in DiningConstants.PORTION_PREFERENCES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid portion_preference, must be one of: ${DiningConstants.PORTION_PREFERENCES}"))
                q = q.set(cPortionPreference, portion)
            }
            if (body.containsKey("remark"))
                q = q.set(cRemark, body.getString("remark"))
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))
            execute(pool, updateQuery).flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        if (status.isBlank() || status !in DiningConstants.ENABLE_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.ENABLE_STATUSES}"))
        return get(id).flatMap { existing ->
            val now = OffsetDateTime.now()
            val query = ctx.update(t)
                .set(cStatus, status)
                .set(cUpdatedAt, now)
                .where(cId.eq(id))
            execute(pool, query).compose { result ->
                if (result.rowCount() == 0)
                    return@compose Future.failedFuture(DiningNotFoundException("diet profile not found: $id"))
                get(id)
            }.recover { error ->
                // 重新启用时命中部分唯一索引（同一长者已有启用档案）→ 409
                if (error is io.vertx.pgclient.PgException && error.sqlState == "23505")
                    Future.failedFuture(DiningConflictException("该长者已有启用的饮食档案"))
                else Future.failedFuture(error)
            }
        }
    }

    fun delete(id: String): Future<Void?> {
        return get(id).flatMap {
            val query = ctx.deleteFrom(t).where(cId.eq(id))
            execute(pool, query).map<Void?> { null }
        }
    }

    private fun stringArray(value: JsonArray?, field: String): JsonArray {
        if (value == null) return JsonArray()
        if (!value.all { it is String })
            throw IllegalArgumentException("$field must be an array of strings")
        return JsonArray(value.map { it.toString().trim() }.filter { it.isNotEmpty() })
    }

    private fun execute(client: io.vertx.sqlclient.SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
