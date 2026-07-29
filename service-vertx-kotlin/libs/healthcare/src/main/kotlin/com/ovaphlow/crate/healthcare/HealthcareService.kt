package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.healthcare.tables.records.EncountersRecord
import com.ovaphlow.crate.database.gen.healthcare.tables.records.PatientsRecord
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.InsertSetMoreStep
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

class HealthcareService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        private val patientStatuses = setOf("ACTIVE", "INACTIVE", "DECEASED")
        private val encounterStatuses = setOf("ACTIVE", "DISCHARGED", "TRANSFERRED")

        private fun patientJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("gender", row.getString("gender"))
                .put("birth_date", row.getLocalDate("birth_date")?.toString())
                .put("id_card_no", row.getString("id_card_no"))
                .put("phone", row.getString("phone"))
                .put("address", row.getString("address"))
                .put("emergency_contact", row.getValue("emergency_contact"))
                .put("medical_insurance", row.getString("medical_insurance"))
                .put("allergies", row.getValue("allergies"))
                .put("past_history", row.getString("past_history"))
                .put("metadata", row.getValue("metadata"))
                .put("status", row.getString("status"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        private fun encounterJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("encounter_type", row.getString("encounter_type"))
                .put("encounter_no", row.getString("encounter_no"))
                .put("department", row.getString("department"))
                .put("ward", row.getString("ward"))
                .put("admit_date", row.getOffsetDateTime("admit_date")?.toString())
                .put("discharge_date", row.getOffsetDateTime("discharge_date")?.toString())
                .put("admitting_diagnosis", row.getString("admitting_diagnosis"))
                .put("discharge_diagnosis", row.getString("discharge_diagnosis"))
                .put("attending_physician", row.getString("attending_physician"))
                .put("status", row.getString("status"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    fun createPatient(body: JsonObject): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return execute(pool, patientInsert(body, id, now))
            .map { patientResponse(body, id, now) }
    }

    fun listPatients(
        name: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        name?.takeIf(String::isNotBlank)?.let { conditions += PATIENTS.NAME.containsIgnoreCase(it) }
        status?.takeIf(String::isNotBlank)?.let { conditions += PATIENTS.STATUS.eq(it) }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(PATIENTS).where(conditions)
        val dataQuery = ctx.selectFrom(PATIENTS)
            .where(conditions)
            .orderBy(PATIENTS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::patientJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getPatient(id: String): Future<JsonObject> = getPatient(pool, id)

    fun updatePatient(id: String, body: JsonObject): Future<JsonObject> {
        validatePatientUpdate(body)
        return getPatient(id).compose {
            execute(pool, patientUpdate(body, id, OffsetDateTime.now()))
                .compose { getPatient(id) }
        }
    }

    fun createEncounter(body: JsonObject): Future<JsonObject> {
        val patientId = try {
            requiredText(body, "patient_id")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return getPatient(patientId).compose {
            createEncounter(pool, body, patientId)
        }
    }

    fun listEncounters(
        patientId: String?,
        encounterType: String?,
        status: String?,
        limit: Int,
        offset: Int,
        search: String? = null,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        patientId?.takeIf(String::isNotBlank)?.let { conditions += ENCOUNTERS.PATIENT_ID.eq(it) }
        encounterType?.takeIf(String::isNotBlank)?.let { conditions += ENCOUNTERS.ENCOUNTER_TYPE.eq(it) }
        status?.takeIf(String::isNotBlank)?.let { conditions += ENCOUNTERS.STATUS.eq(it) }
        search?.takeIf(String::isNotBlank)?.let {
            conditions += ENCOUNTERS.ENCOUNTER_NO.containsIgnoreCase(it)
                .or(PATIENTS.NAME.containsIgnoreCase(it))
                .or(PATIENTS.ID_CARD_NO.containsIgnoreCase(it))
        }

        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(ENCOUNTERS)
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(conditions)
        val dataQuery = ctx.select(ENCOUNTERS.fields().toList())
            .from(ENCOUNTERS)
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(conditions)
            .orderBy(ENCOUNTERS.ADMIT_DATE.desc(), ENCOUNTERS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::encounterJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getEncounter(id: String): Future<JsonObject> = getEncounter(pool, id)

    fun updateEncounter(id: String, body: JsonObject): Future<JsonObject> {
        validateEncounterUpdate(body)
        return getEncounter(id).compose {
            execute(pool, encounterUpdate(body, id, OffsetDateTime.now()))
                .compose { getEncounter(id) }
        }
    }

    fun dischargeEncounter(id: String, body: JsonObject): Future<JsonObject> {
        val dischargeDate = body.getString("discharge_date")?.let { offsetDateTime(it, "discharge_date") }
            ?: OffsetDateTime.now()
        return getEncounter(id).compose { encounter ->
            if (encounter.getString("status") == "DISCHARGED") {
                return@compose Future.failedFuture(IllegalArgumentException("encounter is already discharged"))
            }
            val query = ctx.update(ENCOUNTERS)
                .set(ENCOUNTERS.DISCHARGE_DATE, dischargeDate)
                .set(ENCOUNTERS.DISCHARGE_DIAGNOSIS, body.getString("discharge_diagnosis"))
                .set(ENCOUNTERS.STATUS, "DISCHARGED")
                .set(ENCOUNTERS.UPDATED_AT, OffsetDateTime.now())
                .where(ENCOUNTERS.ID.eq(id))
            execute(pool, query).compose { getEncounter(id) }
        }
    }

    fun admitElderly(body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.takeIf(String::isNotBlank)
        val patient = body.getJsonObject("patient")
        if ((patientId == null) == (patient == null)) {
            return Future.failedFuture(IllegalArgumentException("provide exactly one of patient_id or patient"))
        }
        try {
            requiredText(body, "encounter_no")
            requiredText(body, "admit_date")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<JsonObject> { connection ->
            val patientFuture: Future<JsonObject> = if (patientId != null) {
                getPatient(connection, patientId)
            } else {
                val patientBody = requireNotNull(patient)
                val id = Ulid.generate()
                val now = OffsetDateTime.now()
                execute(connection, patientInsert(patientBody, id, now))
                    .map { patientResponse(patientBody, id, now) }
            }

            patientFuture.compose { resident ->
                val residentId = requireNotNull(resident.getString("id"))
                ensureNoActiveElderlyAdmission(connection, residentId).compose {
                    createEncounter(connection, body, residentId, "ELDERLY_CARE")
                }
                    .map<JsonObject> { encounter ->
                        JsonObject()
                            .put("patient", resident)
                            .put("encounter", encounter)
                    }
            }
        }
    }

    private fun getPatient(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(PATIENTS).where(PATIENTS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(patientJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("patient not found: $id"))
        }

    private fun getEncounter(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(encounterJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    private fun ensureNoActiveElderlyAdmission(client: SqlClient, patientId: String): Future<Void> {
        val query = ctx.selectOne()
            .from(ENCOUNTERS)
            .where(
                ENCOUNTERS.PATIENT_ID.eq(patientId)
                    .and(ENCOUNTERS.ENCOUNTER_TYPE.eq("ELDERLY_CARE"))
                    .and(ENCOUNTERS.STATUS.eq("ACTIVE")),
            )
        return execute(client, query).compose { rows ->
            if (rows.size() == 0) Future.succeededFuture()
            else Future.failedFuture(IllegalArgumentException("patient already has an active elderly admission"))
        }
    }

    private fun createEncounter(
        client: SqlClient,
        body: JsonObject,
        patientId: String,
        forcedType: String? = null,
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val encounterNo = requiredText(body, "encounter_no")
        return ensureEncounterNoAvailable(client, encounterNo).compose {
            execute(client, encounterInsert(body, id, patientId, forcedType, now))
                .map { encounterResponse(body, id, patientId, forcedType, now) }
        }
    }

    private fun ensureEncounterNoAvailable(client: SqlClient, encounterNo: String): Future<Void> {
        val query = ctx.selectOne()
            .from(ENCOUNTERS)
            .where(ENCOUNTERS.ENCOUNTER_NO.eq(encounterNo))
        return execute(client, query).compose { rows ->
            if (rows.size() == 0) Future.succeededFuture()
            else Future.failedFuture(IllegalArgumentException("encounter_no already exists"))
        }
    }

    private fun patientInsert(
        body: JsonObject,
        id: String,
        now: OffsetDateTime,
    ): InsertSetMoreStep<PatientsRecord> {
        val name = requiredText(body, "name")
        var query = ctx.insertInto(PATIENTS)
            .set(PATIENTS.ID, id)
            .set(PATIENTS.NAME, name)
            .set(PATIENTS.GENDER, body.getString("gender", ""))
            .set(PATIENTS.STATUS, "ACTIVE")
            .set(PATIENTS.CREATED_AT, now)
            .set(PATIENTS.UPDATED_AT, now)
        body.getString("birth_date")?.let { query = query.set(PATIENTS.BIRTH_DATE, localDate(it, "birth_date")) }
        body.getString("id_card_no")?.let { query = query.set(PATIENTS.ID_CARD_NO, it) }
        body.getString("phone")?.let { query = query.set(PATIENTS.PHONE, it) }
        body.getString("address")?.let { query = query.set(PATIENTS.ADDRESS, it) }
        jsonObject(body, "emergency_contact")?.let { query = query.set(PATIENTS.EMERGENCY_CONTACT, JSONB.valueOf(it.encode())) }
        body.getString("medical_insurance")?.let { query = query.set(PATIENTS.MEDICAL_INSURANCE, it) }
        jsonArray(body, "allergies")?.let { query = query.set(PATIENTS.ALLERGIES, JSONB.valueOf(it.encode())) }
        body.getString("past_history")?.let { query = query.set(PATIENTS.PAST_HISTORY, it) }
        jsonObject(body, "metadata")?.let { query = query.set(PATIENTS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    private fun patientUpdate(body: JsonObject, id: String, now: OffsetDateTime): Query {
        var query = ctx.update(PATIENTS).set(PATIENTS.UPDATED_AT, now)
        if (body.containsKey("name")) query = query.set(PATIENTS.NAME, requiredText(body, "name"))
        if (body.containsKey("gender")) query = query.set(PATIENTS.GENDER, body.getString("gender", ""))
        if (body.containsKey("birth_date")) query = query.set(PATIENTS.BIRTH_DATE, localDate(requiredText(body, "birth_date"), "birth_date"))
        if (body.containsKey("id_card_no")) query = query.set(PATIENTS.ID_CARD_NO, body.getString("id_card_no"))
        if (body.containsKey("phone")) query = query.set(PATIENTS.PHONE, body.getString("phone"))
        if (body.containsKey("address")) query = query.set(PATIENTS.ADDRESS, body.getString("address"))
        if (body.containsKey("emergency_contact")) query = query.set(PATIENTS.EMERGENCY_CONTACT, JSONB.valueOf(requireNotNull(jsonObject(body, "emergency_contact", true)).encode()))
        if (body.containsKey("medical_insurance")) query = query.set(PATIENTS.MEDICAL_INSURANCE, body.getString("medical_insurance"))
        if (body.containsKey("allergies")) query = query.set(PATIENTS.ALLERGIES, JSONB.valueOf(requireNotNull(jsonArray(body, "allergies", true)).encode()))
        if (body.containsKey("past_history")) query = query.set(PATIENTS.PAST_HISTORY, body.getString("past_history"))
        if (body.containsKey("metadata")) query = query.set(PATIENTS.METADATA, JSONB.valueOf(requireNotNull(jsonObject(body, "metadata", true)).encode()))
        if (body.containsKey("status")) query = query.set(PATIENTS.STATUS, validStatus(body.getString("status"), patientStatuses, "patient status"))
        return query.where(PATIENTS.ID.eq(id))
    }

    private fun encounterInsert(
        body: JsonObject,
        id: String,
        patientId: String,
        forcedType: String?,
        now: OffsetDateTime,
    ): InsertSetMoreStep<EncountersRecord> {
        val encounterType = forcedType ?: requiredText(body, "encounter_type")
        val encounterNo = requiredText(body, "encounter_no")
        val admitDate = offsetDateTime(requiredText(body, "admit_date"), "admit_date")
        var query = ctx.insertInto(ENCOUNTERS)
            .set(ENCOUNTERS.ID, id)
            .set(ENCOUNTERS.PATIENT_ID, patientId)
            .set(ENCOUNTERS.ENCOUNTER_TYPE, encounterType)
            .set(ENCOUNTERS.ENCOUNTER_NO, encounterNo)
            .set(ENCOUNTERS.ADMIT_DATE, admitDate)
            .set(ENCOUNTERS.STATUS, "ACTIVE")
            .set(ENCOUNTERS.CREATED_AT, now)
            .set(ENCOUNTERS.UPDATED_AT, now)
        body.getString("department")?.let { query = query.set(ENCOUNTERS.DEPARTMENT, it) }
        body.getString("ward")?.let { query = query.set(ENCOUNTERS.WARD, it) }
        body.getString("admitting_diagnosis")?.let { query = query.set(ENCOUNTERS.ADMITTING_DIAGNOSIS, it) }
        body.getString("attending_physician")?.let { query = query.set(ENCOUNTERS.ATTENDING_PHYSICIAN, it) }
        jsonObject(body, "metadata")?.let { query = query.set(ENCOUNTERS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    private fun encounterUpdate(body: JsonObject, id: String, now: OffsetDateTime): Query {
        var query = ctx.update(ENCOUNTERS).set(ENCOUNTERS.UPDATED_AT, now)
        if (body.containsKey("department")) query = query.set(ENCOUNTERS.DEPARTMENT, body.getString("department"))
        if (body.containsKey("ward")) query = query.set(ENCOUNTERS.WARD, body.getString("ward"))
        if (body.containsKey("admitting_diagnosis")) query = query.set(ENCOUNTERS.ADMITTING_DIAGNOSIS, body.getString("admitting_diagnosis"))
        if (body.containsKey("attending_physician")) query = query.set(ENCOUNTERS.ATTENDING_PHYSICIAN, body.getString("attending_physician"))
        if (body.containsKey("metadata")) query = query.set(ENCOUNTERS.METADATA, JSONB.valueOf(requireNotNull(jsonObject(body, "metadata", true)).encode()))
        if (body.containsKey("status")) query = query.set(ENCOUNTERS.STATUS, validStatus(body.getString("status"), encounterStatuses - "DISCHARGED", "encounter status"))
        return query.where(ENCOUNTERS.ID.eq(id))
    }

    private fun patientResponse(body: JsonObject, id: String, now: OffsetDateTime): JsonObject =
        JsonObject()
            .put("id", id)
            .put("name", body.getString("name"))
            .put("gender", body.getString("gender", ""))
            .put("birth_date", body.getString("birth_date"))
            .put("id_card_no", body.getString("id_card_no"))
            .put("phone", body.getString("phone"))
            .put("address", body.getString("address"))
            .put("emergency_contact", body.getJsonObject("emergency_contact"))
            .put("medical_insurance", body.getString("medical_insurance", ""))
            .put("allergies", body.getJsonArray("allergies"))
            .put("past_history", body.getString("past_history"))
            .put("metadata", body.getJsonObject("metadata"))
            .put("status", "ACTIVE")
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun encounterResponse(
        body: JsonObject,
        id: String,
        patientId: String,
        forcedType: String?,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("patient_id", patientId)
            .put("encounter_type", forcedType ?: body.getString("encounter_type"))
            .put("encounter_no", requiredText(body, "encounter_no"))
            .put("department", body.getString("department"))
            .put("ward", body.getString("ward"))
            .put("admit_date", body.getString("admit_date"))
            .put("discharge_date", null)
            .put("admitting_diagnosis", body.getString("admitting_diagnosis"))
            .put("discharge_diagnosis", null)
            .put("attending_physician", body.getString("attending_physician"))
            .put("status", "ACTIVE")
            .put("metadata", body.getJsonObject("metadata"))
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun validatePatientUpdate(body: JsonObject) {
        if (body.isEmpty) throw IllegalArgumentException("at least one patient field is required")
        body.getString("status")?.let { validStatus(it, patientStatuses, "patient status") }
        if (body.containsKey("emergency_contact")) jsonObject(body, "emergency_contact", true)
        if (body.containsKey("allergies")) jsonArray(body, "allergies", true)
        if (body.containsKey("metadata")) jsonObject(body, "metadata", true)
    }

    private fun validateEncounterUpdate(body: JsonObject) {
        if (body.isEmpty) throw IllegalArgumentException("at least one encounter field is required")
        if (body.containsKey("encounter_no")) throw IllegalArgumentException("encounter_no cannot be modified")
        if (body.containsKey("status")) validStatus(body.getString("status"), encounterStatuses - "DISCHARGED", "encounter status")
        if (body.containsKey("metadata")) jsonObject(body, "metadata", true)
    }

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun validStatus(value: String?, allowed: Set<String>, label: String): String =
        value?.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun localDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 date")
        }

    private fun offsetDateTime(value: String, field: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 offset date-time")
        }

    private fun jsonObject(body: JsonObject, key: String, required: Boolean = false): JsonObject? {
        val value = body.getValue(key)
        if (value == null && !required) return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun jsonArray(body: JsonObject, key: String, required: Boolean = false): JsonArray? {
        val value = body.getValue(key)
        if (value == null && !required) return null
        return value as? JsonArray ?: throw IllegalArgumentException("$key must be a JSON array")
    }
}
