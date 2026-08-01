package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.MedicalRecords
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.healthcare.tables.records.EncountersRecord
import com.ovaphlow.crate.database.gen.healthcare.tables.records.MedicalRecordsRecord
import com.ovaphlow.crate.database.gen.healthcare.tables.records.PatientsRecord
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.ElderlyDischargeHandoverSnapshotService
import com.ovaphlow.crate.nursing.ServicePeriodService
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
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
import java.time.ZoneId

class HealthcareService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    private val servicePeriodService = ServicePeriodService(pool)
    private val dischargeHandoverSnapshotService = ElderlyDischargeHandoverSnapshotService()
    companion object {
        private val patientStatuses = setOf("ACTIVE", "INACTIVE", "DECEASED")
        private val encounterStatuses = setOf("ACTIVE", "DISCHARGED", "TRANSFERRED")
        private val businessZone = ZoneId.of("Asia/Shanghai")

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
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, id).compose { encounter ->
                if (encounter.getString("status") == "DISCHARGED") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is already discharged"))
                }

                // 养老入住必须在同一事务中收束精确关联的照护周期
                val closePeriodFuture: Future<Void> =
                    if (encounter.getString("encounter_type") == "ELDERLY_CARE") {
                        servicePeriodService
                            .closeElderlyCarePeriod(connection, id, businessDate(dischargeDate), now)
                            .map<Void> { null }
                    } else {
                        Future.succeededFuture()
                    }

                closePeriodFuture.compose {
                    val query = ctx.update(ENCOUNTERS)
                        .set(ENCOUNTERS.DISCHARGE_DATE, dischargeDate)
                        .set(ENCOUNTERS.DISCHARGE_DIAGNOSIS, body.getString("discharge_diagnosis"))
                        .set(ENCOUNTERS.STATUS, "DISCHARGED")
                        .set(ENCOUNTERS.UPDATED_AT, now)
                        .where(ENCOUNTERS.ID.eq(id))
                    execute(connection, query).compose { getEncounter(connection, id) }
                }
            }
        }
    }

    private fun businessDate(value: OffsetDateTime): LocalDate =
        value.atZoneSameInstant(businessZone).toLocalDate()

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
                ensureNoActiveElderlyAdmission(connection, residentId)
                    .compose { createEncounter(connection, body, residentId, "ELDERLY_CARE") }
                    .compose { encounter ->
                        val encounterId = requireNotNull(encounter.getString("id"))
                        // admit_date 是周期唯一的开始日期来源
                        val admitDate = try {
                            offsetDateTime(requiredText(body, "admit_date"), "admit_date")
                        } catch (error: IllegalArgumentException) {
                            return@compose Future.failedFuture(error)
                        }
                        servicePeriodService
                            .createElderlyCarePeriod(connection, residentId, encounterId, admitDate.toLocalDate(), OffsetDateTime.now())
                            .map<JsonObject> { (_, nursingPeriod) ->
                                JsonObject()
                                    .put("patient", resident)
                                    .put("encounter", encounter)
                                    .put("nursing_period", nursingPeriod)
                            }
                    }
            }
        }
    }

    private fun lockEncounter(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(id)).forUpdate()).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(encounterJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
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

    // ========================================================================
    //  护理记录 (NURSING_RECORD)
    // ========================================================================

    /** 创建日常护理记录 */
    fun createNursingRecord(body: JsonObject): Future<JsonObject> {
        val periodId = try { requiredText(body, "period_id") } catch (e: IllegalArgumentException) { return Future.failedFuture(e) }
        val encounterId = try { requiredText(body, "encounter_id") } catch (e: IllegalArgumentException) { return Future.failedFuture(e) }
        val title = try { requiredText(body, "title") } catch (e: IllegalArgumentException) { return Future.failedFuture(e) }
        val content = body.getString("content")?.trim() ?: return Future.failedFuture(IllegalArgumentException("content is required"))
        if (content.isBlank()) return Future.failedFuture(IllegalArgumentException("content is required"))

        val taskExecId = body.getString("task_execution_id")?.trim()?.takeIf { it.isNotBlank() }
        val recordTimeStr = body.getString("record_time")
        val now = OffsetDateTime.now()

        // 验证周期与入住归属
        return validatePeriodEncounter(periodId, encounterId).compose { (periodPatientId, encounterPatientId) ->
            if (periodPatientId != encounterPatientId)
                return@compose Future.failedFuture(IllegalArgumentException("period and encounter belong to different patients"))

            // 验证 task_execution_id 归属（如果提供）
            if (taskExecId != null) validateTaskExecutionBelonging(taskExecId, periodId, encounterId)
            else Future.succeededFuture<String?>(null)
        }.compose { taskId ->
            // 解析 record_time
            val recordTime = if (recordTimeStr != null) {
                try {
                    val t = OffsetDateTime.parse(recordTimeStr)
                    if (t.isAfter(OffsetDateTime.now())) return@compose Future.failedFuture(IllegalArgumentException("record_time cannot be in the future"))
                    t
                } catch (_: RuntimeException) {
                    return@compose Future.failedFuture(IllegalArgumentException("record_time must be an ISO-8601 offset date-time"))
                }
            } else {
                OffsetDateTime.now()
            }
            val recordDate = recordTime.toLocalDate()
            val recordKind = if (taskExecId != null) "EXECUTION" else "MANUAL"
            val author = body.getString("author")?.trim()?.takeIf { it.isNotBlank() } ?: ""

            // 构建受控 metadata
            val meta = JsonObject()
                .put("period_id", periodId)
                .put("record_kind", recordKind)
                .put("record_time", recordTime.toString())
            if (taskExecId != null) meta.put("task_execution_id", taskExecId)
            if (taskId != null) meta.put("task_id", taskId)

            val id = Ulid.generate()
            val insertQuery = ctx.insertInto(MedicalRecords.MEDICAL_RECORDS)
                .set(MedicalRecords.MEDICAL_RECORDS.ID, id)
                .set(MedicalRecords.MEDICAL_RECORDS.ENCOUNTER_ID, encounterId)
                .set(MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE, "NURSING_RECORD")
                .set(MedicalRecords.MEDICAL_RECORDS.TITLE, title.take(100))
                .set(MedicalRecords.MEDICAL_RECORDS.CONTENT, content)
                .set(MedicalRecords.MEDICAL_RECORDS.CONTENT_BLOCKS, org.jooq.JSONB.valueOf("[]"))
                .set(MedicalRecords.MEDICAL_RECORDS.PHYSICIAN, author)
                .set(MedicalRecords.MEDICAL_RECORDS.RECORD_DATE, recordDate)
                .set(MedicalRecords.MEDICAL_RECORDS.METADATA, org.jooq.JSONB.valueOf(meta.encode()))
                .set(MedicalRecords.MEDICAL_RECORDS.CREATED_AT, now)
                .set(MedicalRecords.MEDICAL_RECORDS.UPDATED_AT, now)

            execute(pool, insertQuery).map {
                nursingRecordResponse(id, encounterId, periodId, recordKind, title, content, recordTime, recordDate, author, taskExecId, taskId, null, now)
            }.recover { err ->
                // 唯一索引冲突 → 409
                val msg = err.message ?: ""
                if (msg.contains("idx_nursing_record_execution") || msg.contains("duplicate key")) {
                    Future.failedFuture(DuplicateNursingRecordException("nursing record already exists for task execution"))
                } else {
                    Future.failedFuture(err)
                }
            }
        }
    }

    /** 查询护理记录 */
    fun listNursingRecords(
        periodId: String?,
        encounterId: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE.eq("NURSING_RECORD"))
        encounterId?.takeIf(String::isNotBlank)?.let { conditions.add(MedicalRecords.MEDICAL_RECORDS.ENCOUNTER_ID.eq(it)) }

        val metadataField = MedicalRecords.MEDICAL_RECORDS.METADATA
        periodId?.takeIf(String::isNotBlank)?.let { pid ->
            conditions.add(DSL.field("{0} ->> {1}", String::class.java, metadataField, DSL.`val`("period_id")).eq(pid))
        }
        dateFrom?.takeIf(String::isNotBlank)?.let { d ->
            try { conditions.add(MedicalRecords.MEDICAL_RECORDS.RECORD_DATE.ge(LocalDate.parse(d))) }
            catch (_: Exception) { throw IllegalArgumentException("invalid date_from") }
        }
        dateTo?.takeIf(String::isNotBlank)?.let { d ->
            try { conditions.add(MedicalRecords.MEDICAL_RECORDS.RECORD_DATE.le(LocalDate.parse(d))) }
            catch (_: Exception) { throw IllegalArgumentException("invalid date_to") }
        }

        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(MedicalRecords.MEDICAL_RECORDS).where(conditions)
        val dataQuery = ctx.selectFrom(MedicalRecords.MEDICAL_RECORDS)
            .where(conditions)
            .orderBy(MedicalRecords.MEDICAL_RECORDS.RECORD_DATE.desc(), MedicalRecords.MEDICAL_RECORDS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map { nursingRecordFromRow(it) }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 获取单条护理记录 */
    fun getNursingRecord(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(MedicalRecords.MEDICAL_RECORDS)
            .where(MedicalRecords.MEDICAL_RECORDS.ID.eq(id))
            .and(MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE.eq("NURSING_RECORD"))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("nursing record not found: $id"))
            // 检查更正记录
            val correctionQuery = ctx.select(DSL.count().`as`("total"))
                .from(MedicalRecords.MEDICAL_RECORDS)
                .where(DSL.field("{0} ->> {1}", String::class.java, MedicalRecords.MEDICAL_RECORDS.METADATA, DSL.`val`("corrects_record_id")).eq(id))
                .and(MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE.eq("NURSING_RECORD"))
            execute(pool, correctionQuery).map { correctionRows ->
                val correctionCount = correctionRows.iterator().next().getLong("total") ?: 0L
                val record = nursingRecordFromRow(row)
                if (correctionCount > 0) {
                    record.put("is_corrected", true)
                    record.put("correction_count", correctionCount)
                }
                record
            }
        }
    }

    /** 创建更正记录 */
    fun createNursingRecordCorrection(recordId: String, body: JsonObject): Future<JsonObject> {
        val content = body.getString("content")?.trim() ?: return Future.failedFuture(IllegalArgumentException("content is required"))
        if (content.isBlank()) return Future.failedFuture(IllegalArgumentException("content is required"))

        val recordTimeStr = body.getString("record_time")
        val now = OffsetDateTime.now()

        return getNursingRecord(recordId).compose { original ->
            // 只允许更正 NURSING_RECORD
            if (original.getString("record_type") != "NURSING_RECORD")
                return@compose Future.failedFuture(IllegalArgumentException("only NURSING_RECORD can be corrected"))

            val encounterId = original.getString("encounter_id")
            val periodId = original.getJsonObject("metadata")?.getString("period_id")
            val recordKind = original.getJsonObject("metadata")?.getString("record_kind")
            val taskExecId = original.getJsonObject("metadata")?.getString("task_execution_id")
            val taskId = original.getJsonObject("metadata")?.getString("task_id")
            val originalTitle = original.getString("title") ?: ""
            val recordTime = if (recordTimeStr != null) {
                try {
                    val t = OffsetDateTime.parse(recordTimeStr)
                    if (t.isAfter(OffsetDateTime.now())) return@compose Future.failedFuture(IllegalArgumentException("record_time cannot be in the future"))
                    t
                } catch (_: RuntimeException) {
                    return@compose Future.failedFuture(IllegalArgumentException("record_time must be an ISO-8601 offset date-time"))
                }
            } else {
                OffsetDateTime.now()
            }
            val recordDate = recordTime.toLocalDate()

            // 构建受控 metadata
            val meta = JsonObject()
                .put("period_id", periodId ?: "")
                .put("record_kind", "CORRECTION")
                .put("record_time", recordTime.toString())
                .put("corrects_record_id", recordId)
            if (taskExecId != null) meta.put("task_execution_id", taskExecId)
            if (taskId != null) meta.put("task_id", taskId)

            val id = Ulid.generate()
            val author = body.getString("author")?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val correctionTitle = "更正：${originalTitle}"

            val insertQuery = ctx.insertInto(MedicalRecords.MEDICAL_RECORDS)
                .set(MedicalRecords.MEDICAL_RECORDS.ID, id)
                .set(MedicalRecords.MEDICAL_RECORDS.ENCOUNTER_ID, encounterId ?: "")
                .set(MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE, "NURSING_RECORD")
                .set(MedicalRecords.MEDICAL_RECORDS.TITLE, correctionTitle.take(100))
                .set(MedicalRecords.MEDICAL_RECORDS.CONTENT, content)
                .set(MedicalRecords.MEDICAL_RECORDS.CONTENT_BLOCKS, org.jooq.JSONB.valueOf("[]"))
                .set(MedicalRecords.MEDICAL_RECORDS.PHYSICIAN, author)
                .set(MedicalRecords.MEDICAL_RECORDS.RECORD_DATE, recordDate)
                .set(MedicalRecords.MEDICAL_RECORDS.METADATA, org.jooq.JSONB.valueOf(meta.encode()))
                .set(MedicalRecords.MEDICAL_RECORDS.CREATED_AT, now)
                .set(MedicalRecords.MEDICAL_RECORDS.UPDATED_AT, now)

            execute(pool, insertQuery).map {
                nursingRecordResponse(id, encounterId ?: "", periodId ?: "", "CORRECTION", correctionTitle.take(100), content, recordTime, recordDate, author, taskExecId, taskId, recordId, now)
            }
        }
    }

    // ——— 护理记录私有辅助方法 ———

    private fun nursingRecordResponse(
        id: String, encounterId: String, periodId: String?, recordKind: String?,
        title: String, content: String, recordTime: OffsetDateTime, recordDate: LocalDate,
        author: String, taskExecutionId: String?, taskId: String?,
        correctsRecordId: String?, now: OffsetDateTime,
    ): JsonObject {
        val meta = JsonObject()
            .put("period_id", periodId)
            .put("record_kind", recordKind)
            .put("record_time", recordTime.toString())
        if (taskExecutionId != null) meta.put("task_execution_id", taskExecutionId)
        if (taskId != null) meta.put("task_id", taskId)
        if (correctsRecordId != null) meta.put("corrects_record_id", correctsRecordId)

        return JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("period_id", periodId)
            .put("record_type", "NURSING_RECORD")
            .put("record_kind", recordKind)
            .put("title", title)
            .put("content", content)
            .put("record_time", recordTime.toString())
            .put("record_date", recordDate.toString())
            .put("author", author)
            .put("task_execution_id", taskExecutionId)
            .put("task_id", taskId)
            .put("corrects_record_id", correctsRecordId)
            .put("metadata", meta)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())
    }

    private fun nursingRecordFromRow(row: io.vertx.sqlclient.Row): JsonObject {
        val id = row.getString("id") ?: ""
        val encounterId = row.getString("encounter_id") ?: ""
        val title = row.getString("title") ?: ""
        val content = row.getString("content")
        val physician = row.getString("physician") ?: ""
        val recordDate = row.getLocalDate("record_date")
        val createdAt = row.getOffsetDateTime("created_at")
        val rowMeta = row.getValue("metadata") as? JsonObject ?: JsonObject()

        val periodId = rowMeta.getString("period_id")
        val recordKind = rowMeta.getString("record_kind")
        val recordTimeStr = rowMeta.getString("record_time")
        val taskExecutionId = rowMeta.getString("task_execution_id")
        val taskId = rowMeta.getString("task_id")
        val correctsRecordId = rowMeta.getString("corrects_record_id")

        return JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("period_id", periodId)
            .put("record_type", "NURSING_RECORD")
            .put("record_kind", recordKind)
            .put("title", title)
            .put("content", content)
            .put("record_time", recordTimeStr)
            .put("record_date", recordDate?.toString())
            .put("author", physician)
            .put("task_execution_id", taskExecutionId)
            .put("task_id", taskId)
            .put("corrects_record_id", correctsRecordId)
            .put("metadata", rowMeta)
            .put("created_at", createdAt?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /** 验证 period_id 和 encounter_id 的 patient_id 一致性 */
    private fun validatePeriodEncounter(periodId: String, encounterId: String): Future<Pair<String?, String?>> {
        val periodQuery = ctx.select(DSL.field("patient_id"))
            .from(DSL.table(DSL.name("nursing", "nursing_service_periods")))
            .where(DSL.field("id").eq(periodId))
        val encounterQuery = ctx.select(ENCOUNTERS.PATIENT_ID)
            .from(ENCOUNTERS)
            .where(ENCOUNTERS.ID.eq(encounterId))

        return execute(pool, periodQuery).compose { periodRows ->
            val periodRow = periodRows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("nursing service period not found: $periodId"))
            val periodPatientId = periodRow.getString("patient_id")
            execute(pool, encounterQuery).map { encounterRows ->
                val encounterRow = encounterRows.iterator().asSequence().firstOrNull()
                    ?: throw HealthcareNotFoundException("encounter not found: $encounterId")
                val encounterPatientId = encounterRow.getString("patient_id")
                Pair(periodPatientId, encounterPatientId)
            }
        }
    }

    /** 验证 task_execution_id 归属 */
    private fun validateTaskExecutionBelonging(taskExecId: String, periodId: String, encounterId: String): Future<String?> {
        val query = ctx.select(
            DSL.field("t.id"),
            DSL.field("t.period_id"),
            DSL.field("t.encounter_id")
        )
            .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
            .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
            .on(DSL.field("e.task_id").eq(DSL.field("t.id")))
            .where(DSL.field("e.id").eq(taskExecId))

        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("task execution not found: $taskExecId"))
            val taskId = row.getString("id")
            val taskPeriodId = row.getString("period_id")
            val taskEncounterId = row.getString("encounter_id")

            if (taskPeriodId != null && taskPeriodId != periodId)
                return@compose Future.failedFuture(IllegalArgumentException("task execution does not belong to the specified period"))
            if (taskEncounterId != null && taskEncounterId != encounterId)
                return@compose Future.failedFuture(IllegalArgumentException("task execution does not belong to the specified encounter"))
            return@compose Future.succeededFuture(taskId)
        }
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

    // ========================================================================
    //  养老离院交接摘要归档 (DISCHARGE_SUMMARY)
    // ========================================================================

    private val handoverTitle = "养老照护离院交接摘要"

    /** 获取既有交接摘要；资格错误按 4.1 返回 400/409，无文书返回 404 */
    fun getElderlyDischargeHandover(id: String): Future<JsonObject> {
        return pool.withTransaction<JsonObject> { connection ->
            validateHandoverEligibility(connection, id).compose { (encounter, period) ->
                val periodId = requireNotNull(period.getString("id"))
                lockExistingHandover(connection, encounter.getString("id"), periodId).compose { row ->
                    if (row == null) {
                        Future.failedFuture(HealthcareNotFoundException("elderly discharge handover not found"))
                    } else {
                        Future.succeededFuture(handoverFromRow(row))
                    }
                }
            }
        }
    }

    /** 创建或幂等读取交接摘要；首次 201，相同规范化输入重试 200，不同输入 409 */
    fun createElderlyDischargeHandover(id: String, body: JsonObject): Future<Pair<Boolean, JsonObject>> {
        val author = try {
            val value = requiredText(body, "author")
            if (value.length > 100) throw IllegalArgumentException("author must not exceed 100 characters")
            value
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val handoverNote = try {
            val noteValue = body.getValue("handover_note")
            if (noteValue != null && noteValue !is String) {
                throw IllegalArgumentException("handover_note must be a string")
            }
            noteValue?.trim()?.takeIf(String::isNotBlank)?.let {
                if (it.length > 2000) throw IllegalArgumentException("handover_note must not exceed 2000 characters")
                it
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<Pair<Boolean, JsonObject>> { connection ->
            validateHandoverEligibility(connection, id).compose { (encounter, period) ->
                val encounterId = requireNotNull(encounter.getString("id"))
                val periodId = requireNotNull(period.getString("id"))
                val dischargeDate = requireNotNull(encounter.getString("discharge_date"))

                lockExistingHandover(connection, encounterId, periodId).compose { existing ->
                    if (existing != null) {
                        return@compose compareHandoverExisting(existing, author, handoverNote)
                    }
                    buildAndInsertHandover(connection, encounter, period, author, handoverNote, dischargeDate)
                        .map { handover -> Pair(true, handover) }
                        .recover { err ->
                            // 唯一索引竞争：重新锁读既有行后按输入比较，不泄漏数据库约束文本
                            if (!isUniqueViolation(err)) return@recover Future.failedFuture(err)
                            lockExistingHandover(connection, encounterId, periodId).compose { row ->
                                if (row == null) Future.failedFuture(err)
                                else compareHandoverExisting(row, author, handoverNote)
                            }
                        }
                }
            }
        }
    }

    /** 已存在摘要时的幂等比较：相同返回 200 原文书，不同返回 409 */
    private fun compareHandoverExisting(
        row: Row,
        author: String,
        handoverNote: String?,
    ): Future<Pair<Boolean, JsonObject>> {
        val sameAuthor = row.getString("physician") == author
        val sameNote = (row.getString("content") ?: null) == handoverNote
        if (sameAuthor && sameNote) {
            return Future.succeededFuture(Pair(false, handoverFromRow(row)))
        }
        return Future.failedFuture(
            ConflictException("elderly discharge handover already exists with different author or handover note")
        )
    }

    /** 第 4.1 节资格校验：锁定 encounter → 校验养老/离院/离院日期 → 锁定精确关联已完成周期 → 患者一致 */
    private fun validateHandoverEligibility(
        client: SqlClient,
        encounterId: String,
    ): Future<Pair<JsonObject, JsonObject>> {
        return lockEncounter(client, encounterId).compose { encounter ->
            if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            }
            val dischargeDateStr = encounter.getString("discharge_date")
            if (encounter.getString("status") != "DISCHARGED" || dischargeDateStr == null) {
                return@compose Future.failedFuture(
                    ConflictException("encounter is not discharged; complete the discharge flow first")
                )
            }
            val dischargeDate = try {
                OffsetDateTime.parse(dischargeDateStr)
            } catch (_: RuntimeException) {
                return@compose Future.failedFuture(ConflictException("encounter has no valid discharge date"))
            }

            servicePeriodService.lockCompletedElderlyCarePeriodForHandover(
                client, encounterId, businessDate(dischargeDate),
            ).compose { period ->
                if (period.getString("patient_id") != encounter.getString("patient_id")) {
                    return@compose Future.failedFuture(
                        ConflictException("patient_id mismatch between period and encounter")
                    )
                }
                Future.succeededFuture(Pair(encounter, period))
            }
        }
    }

    /** 锁读既有本计划归档文书；无则返回 null */
    private fun lockExistingHandover(
        client: SqlClient,
        encounterId: String,
        periodId: String,
    ): Future<Row?> {
        val metadataField = MedicalRecords.MEDICAL_RECORDS.METADATA
        val query = ctx.selectFrom(MedicalRecords.MEDICAL_RECORDS)
            .where(
                MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE.eq("DISCHARGE_SUMMARY")
                    .and(MedicalRecords.MEDICAL_RECORDS.ENCOUNTER_ID.eq(encounterId))
                    .and(DSL.field("{0} ->> {1}", String::class.java, metadataField, DSL.`val`("period_id")).eq(periodId))
                    .and(
                        DSL.field("{0} ->> {1}", String::class.java, metadataField, DSL.`val`("is_elderly_discharge_handover"))
                            .eq("true")
                    ),
            )
            .forUpdate()
        return execute(client, query).map { rows ->
            rows.iterator().asSequence().firstOrNull()
        }
    }

    /** 组装快照、受控 metadata 并写入单条 DISCHARGE_SUMMARY，随后读取返回 */
    private fun buildAndInsertHandover(
        client: SqlClient,
        encounter: JsonObject,
        period: JsonObject,
        author: String,
        handoverNote: String?,
        dischargeDate: String,
    ): Future<JsonObject> {
        val encounterId = requireNotNull(encounter.getString("id"))
        val periodId = requireNotNull(period.getString("id"))
        val patientId = requireNotNull(encounter.getString("patient_id"))
        val recordDate = businessDate(OffsetDateTime.parse(dischargeDate))
        val now = OffsetDateTime.now()

        return dischargeHandoverSnapshotService.buildNursingSnapshot(client, periodId)
            .compose { nursing ->
                loadPatientHandoverData(client, patientId).compose { patient ->
                    loadHandoverNursingRecords(client, encounterId, periodId).compose { records ->
                        val snapshot = JsonObject()
                            .put("patient", patient)
                            .put("encounter", handoverEncounterJson(encounter))
                            .put("care_period", handoverCarePeriodJson(period))
                            .put("assessments", nursing.getJsonArray("assessments"))
                            .put("plans", nursing.getJsonArray("plans"))
                            .put("tasks", nursing.getJsonArray("tasks"))
                            .put("execution_summary", nursing.getJsonObject("execution_summary"))
                            .put("nursing_records", JsonArray(records))

                        val plans = nursing.getJsonArray("plans")
                        val tasks = nursing.getJsonArray("tasks")
                        val planItems = plans.sumOf { (it as JsonObject).getJsonArray("items").size() }
                        val taskExecutions = tasks.sumOf { (it as JsonObject).getJsonArray("executions").size() }
                        val sourceCounts = JsonObject()
                            .put("assessments", nursing.getJsonArray("assessments").size())
                            .put("plans", plans.size())
                            .put("plan_items", planItems)
                            .put("tasks", tasks.size())
                            .put("executions", taskExecutions)
                            .put("nursing_records", records.size)

                        val contentBlocks = JsonArray().add(
                            JsonObject().put("snapshot_version", 1).put("snapshot", snapshot)
                        )
                        val meta = JsonObject()
                            .put("is_elderly_discharge_handover", true)
                            .put("period_id", periodId)
                            .put("snapshot_version", 1)
                            .put("generated_at", now.toString())
                            .put("source_counts", sourceCounts)

                        val id = Ulid.generate()
                        var insert = ctx.insertInto(MedicalRecords.MEDICAL_RECORDS)
                            .set(MedicalRecords.MEDICAL_RECORDS.ID, id)
                            .set(MedicalRecords.MEDICAL_RECORDS.ENCOUNTER_ID, encounterId)
                            .set(MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE, "DISCHARGE_SUMMARY")
                            .set(MedicalRecords.MEDICAL_RECORDS.TITLE, handoverTitle)
                            .set(MedicalRecords.MEDICAL_RECORDS.CONTENT_BLOCKS, org.jooq.JSONB.valueOf(contentBlocks.encode()))
                            .set(MedicalRecords.MEDICAL_RECORDS.PHYSICIAN, author)
                            .set(MedicalRecords.MEDICAL_RECORDS.RECORD_DATE, recordDate)
                            .set(MedicalRecords.MEDICAL_RECORDS.METADATA, org.jooq.JSONB.valueOf(meta.encode()))
                            .set(MedicalRecords.MEDICAL_RECORDS.CREATED_AT, now)
                            .set(MedicalRecords.MEDICAL_RECORDS.UPDATED_AT, now)
                        if (handoverNote != null) {
                            insert = insert.set(MedicalRecords.MEDICAL_RECORDS.CONTENT, handoverNote)
                        }

                        execute(client, insert).compose {
                            val read = ctx.selectFrom(MedicalRecords.MEDICAL_RECORDS)
                                .where(MedicalRecords.MEDICAL_RECORDS.ID.eq(id))
                            execute(client, read).map { rows ->
                                handoverFromRow(rows.iterator().next())
                            }
                        }
                    }
                }
            }
    }

    private fun loadPatientHandoverData(client: SqlClient, patientId: String): Future<JsonObject> {
        val query = ctx.select(
            PATIENTS.ID,
            PATIENTS.NAME,
            PATIENTS.GENDER,
            PATIENTS.BIRTH_DATE,
            PATIENTS.EMERGENCY_CONTACT,
            PATIENTS.ALLERGIES,
            PATIENTS.PAST_HISTORY,
        )
            .from(PATIENTS)
            .where(PATIENTS.ID.eq(patientId))
        return execute(client, query).map { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: throw HealthcareNotFoundException("patient not found: $patientId")
            JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("gender", row.getString("gender"))
                .put("birth_date", row.getLocalDate("birth_date")?.toString())
                .put("emergency_contact", row.getValue("emergency_contact"))
                .put("allergies", row.getValue("allergies"))
                .put("past_history", row.getString("past_history"))
        }
    }

    /** 同时限制 encounter_id 与 metadata.period_id 的原始 NURSING_RECORD 及其更正记录 */
    private fun loadHandoverNursingRecords(
        client: SqlClient,
        encounterId: String,
        periodId: String,
    ): Future<List<JsonObject>> {
        val metadataField = MedicalRecords.MEDICAL_RECORDS.METADATA
        val recordTimeField = DSL.field("{0} ->> {1}", String::class.java, metadataField, DSL.inline("record_time"))
        val query = ctx.select(
            MedicalRecords.MEDICAL_RECORDS.ID,
            MedicalRecords.MEDICAL_RECORDS.TITLE,
            MedicalRecords.MEDICAL_RECORDS.CONTENT,
            MedicalRecords.MEDICAL_RECORDS.PHYSICIAN,
            MedicalRecords.MEDICAL_RECORDS.RECORD_DATE,
            MedicalRecords.MEDICAL_RECORDS.METADATA,
            MedicalRecords.MEDICAL_RECORDS.CREATED_AT,
        )
            .from(MedicalRecords.MEDICAL_RECORDS)
            .where(
                MedicalRecords.MEDICAL_RECORDS.RECORD_TYPE.eq("NURSING_RECORD")
                    .and(MedicalRecords.MEDICAL_RECORDS.ENCOUNTER_ID.eq(encounterId))
                    .and(DSL.field("{0} ->> {1}", String::class.java, metadataField, DSL.`val`("period_id")).eq(periodId)),
            )
            .orderBy(
                recordTimeField.asc().nullsLast(),
                MedicalRecords.MEDICAL_RECORDS.CREATED_AT.asc(),
                MedicalRecords.MEDICAL_RECORDS.ID.asc(),
            )
        return execute(client, query).map { rows ->
            rows.map { row ->
                val meta = row.getValue("metadata") as? JsonObject ?: JsonObject()
                JsonObject()
                    .put("id", row.getString("id"))
                    .put("record_kind", meta.getString("record_kind"))
                    .put("title", row.getString("title"))
                    .put("content", row.getString("content"))
                    .put("record_time", meta.getString("record_time"))
                    .put("record_date", row.getLocalDate("record_date")?.toString())
                    .put("author", row.getString("physician"))
                    .put("corrects_record_id", meta.getString("corrects_record_id"))
                    .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            }
        }
    }

    private fun handoverEncounterJson(encounter: JsonObject): JsonObject =
        JsonObject()
            .put("id", encounter.getString("id"))
            .put("encounter_no", encounter.getString("encounter_no"))
            .put("department", encounter.getString("department"))
            .put("ward", encounter.getString("ward"))
            .put("admit_date", encounter.getString("admit_date"))
            .put("discharge_date", encounter.getString("discharge_date"))
            .put("admitting_diagnosis", encounter.getString("admitting_diagnosis"))
            .put("discharge_diagnosis", encounter.getString("discharge_diagnosis"))
            .put("attending_physician", encounter.getString("attending_physician"))
            .put("status", encounter.getString("status"))

    private fun handoverCarePeriodJson(period: JsonObject): JsonObject =
        JsonObject()
            .put("id", period.getString("id"))
            .put("service_type", period.getString("service_type"))
            .put("start_date", period.getString("start_date"))
            .put("end_date", period.getString("end_date"))
            .put("coordinator", period.getString("coordinator"))
            .put("status", period.getString("status"))

    /** 从 medical_records 行映射为 API 对象（解包 content_blocks[0].snapshot 与受控 metadata） */
    private fun handoverFromRow(row: Row): JsonObject {
        val rowMeta = row.getValue("metadata") as? JsonObject ?: JsonObject()
        val contentBlocks = row.getValue("content_blocks") as? JsonArray ?: JsonArray()
        val snapshot = if (contentBlocks.size() > 0) contentBlocks.getJsonObject(0).getJsonObject("snapshot") else JsonObject()
        return JsonObject()
            .put("id", row.getString("id"))
            .put("record_type", row.getString("record_type"))
            .put("title", row.getString("title"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("period_id", rowMeta.getString("period_id"))
            .put("record_date", row.getLocalDate("record_date")?.toString())
            .put("author", row.getString("physician"))
            .put("handover_note", row.getString("content"))
            .put("generated_at", rowMeta.getString("generated_at"))
            .put("snapshot_version", rowMeta.getInteger("snapshot_version") ?: 1)
            .put("snapshot", snapshot)
    }

    private fun isUniqueViolation(err: Throwable): Boolean {
        var current: Throwable? = err
        while (current != null) {
            if (current is PgException && current.sqlState == "23505") return true
            current = current.cause
        }
        return false
    }
}
