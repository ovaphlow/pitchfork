package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.ChronicDiseaseRegistrations.CHRONIC_DISEASE_REGISTRATIONS
import com.ovaphlow.crate.database.gen.healthcare.tables.MedicalRecords
import com.ovaphlow.crate.database.gen.healthcare.tables.MedicalRecords.MEDICAL_RECORDS
import com.ovaphlow.crate.database.gen.healthcare.tables.Diagnoses.DIAGNOSES
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.healthcare.tables.ProgressNotes.PROGRESS_NOTES
import com.ovaphlow.crate.database.gen.healthcare.tables.records.EncountersRecord
import com.ovaphlow.crate.database.gen.healthcare.tables.records.MedicalRecordsRecord
import com.ovaphlow.crate.database.gen.healthcare.tables.records.PatientsRecord
import com.ovaphlow.crate.database.gen.nursing.tables.NursingServicePeriods.NURSING_SERVICE_PERIODS
import com.ovaphlow.crate.nursing.CarePlanRevisionService
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.ElderlyDischargeHandoverSnapshotService
import com.ovaphlow.crate.nursing.NursingIncidentService
import com.ovaphlow.crate.nursing.PlanService
import com.ovaphlow.crate.nursing.ServicePeriodService
import com.ovaphlow.crate.nursing.ShiftHandoverService
import com.ovaphlow.crate.nursing.TaskService
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
    private val taskService = TaskService(pool)
    private val planService = PlanService(pool)
    private val medicalOrderService = MedicalOrderService(pool, taskService)
    private val carePlanRevisionService = CarePlanRevisionService(pool)
    private val nursingIncidentService = NursingIncidentService(pool)
    private val shiftHandoverService = ShiftHandoverService(
        pool,
        ensureCareUnitActive = { client, careUnit -> ensureCareUnitActiveForHandover(client, careUnit) },
    )
    private val billService = BillService(pool)
    companion object {
        private val patientStatuses = setOf("ACTIVE", "INACTIVE", "DECEASED")
        private val encounterStatuses = setOf("ACTIVE", "DISCHARGED", "TRANSFERRED")
        private val diagnosisTypes = setOf("PRIMARY", "SECONDARY")
        private val businessZone = ZoneId.of("Asia/Shanghai")
        /** 交班快照只收集开放照护周期下的活动养老入住。 */
        private val HANDOVER_PERIOD_OPEN_STATUSES = setOf("ACTIVE", "SUSPENDED")

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
                .put("death_date", row.getOffsetDateTime("death_date")?.toString())
                .put("death_cause", row.getString("death_cause"))
                .put("admitting_diagnosis", row.getString("admitting_diagnosis"))
                .put("discharge_diagnosis", row.getString("discharge_diagnosis"))
                .put("attending_physician", row.getString("attending_physician"))
                .put("status", row.getString("status"))
                .put("settled_at", row.getOffsetDateTime("settled_at")?.toString())
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
                        medicalOrderService
                            .terminateEncounterOrders(connection, id, dischargeDate)
                            .map<Void> { null }
                            .compose {
                                servicePeriodService
                                    .closeElderlyCarePeriod(connection, id, businessDate(dischargeDate), now)
                                    .map<Void> { null }
                            }
                    } else {
                        Future.succeededFuture()
                    }

                closePeriodFuture.compose {
                    // 结算收束：养老入住同事务生成区间最终账单并冻结全部账单（同一连接）
                    val settlementFuture: Future<Void> =
                        if (encounter.getString("encounter_type") == "ELDERLY_CARE") {
                            billService
                                .settleEncounter(
                                    connection,
                                    id,
                                    now,
                                    requireTerminalStatus = false,
                                    endDate = businessDate(dischargeDate),
                                )
                                .map<Void> { null }
                        } else {
                            Future.succeededFuture()
                        }
                    settlementFuture.compose {
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
    }

    fun deathEncounter(id: String, body: JsonObject): Future<JsonObject> {
        val deathDate = try {
            offsetDateTime(requiredText(body, "death_date"), "death_date")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val deathCause = try {
            body.getValue("death_cause")?.let { value ->
                if (value !is String) throw IllegalArgumentException("death_cause must be a string")
                value.trim().takeIf { it.isNotBlank() }?.let { trimmed ->
                    if (trimmed.length > 500) throw IllegalArgumentException("death_cause must not exceed 500 characters")
                    trimmed
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, id).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }

                val patientId = requireNotNull(encounter.getString("patient_id"))
                val now = OffsetDateTime.now()
                ensureNoOtherActiveElderlyAdmission(connection, patientId, id)
                    .compose {
                        medicalOrderService.terminateEncounterOrders(connection, id, deathDate)
                    }
                    .compose {
                        servicePeriodService.closeElderlyCarePeriod(connection, id, businessDate(deathDate), now)
                    }
                    .compose {
                        // 结算收束：同事务生成区间最终账单并冻结全部账单（同一连接）
                        billService
                            .settleEncounter(
                                connection,
                                id,
                                now,
                                requireTerminalStatus = false,
                                endDate = businessDate(deathDate),
                            )
                            .map<Void> { null }
                    }
                    .compose {
                        var query = ctx.update(ENCOUNTERS)
                            .set(ENCOUNTERS.DEATH_DATE, deathDate)
                            .set(ENCOUNTERS.STATUS, "DECEASED")
                            .set(ENCOUNTERS.UPDATED_AT, now)
                        if (deathCause != null) query = query.set(ENCOUNTERS.DEATH_CAUSE, deathCause)
                        execute(connection, query.where(ENCOUNTERS.ID.eq(id)))
                    }
                    .compose {
                        val patientQuery = ctx.update(PATIENTS)
                            .set(PATIENTS.STATUS, "DECEASED")
                            .set(PATIENTS.UPDATED_AT, now)
                            .where(PATIENTS.ID.eq(patientId))
                        execute(connection, patientQuery)
                    }
                    .compose {
                        getEncounter(connection, id)
                    }
            }
        }
    }

    private fun businessDate(value: OffsetDateTime): LocalDate =
        value.atZoneSameInstant(businessZone).toLocalDate()

    /**
     * 补结算：已离院/去世但未结算的养老入住 → 生成区间最终账单并冻结全部账单。
     * 已全部结算 409；未离院/去世 409；非养老入住 400。
     */
    fun settleEncounterBilling(id: String): Future<JsonObject> =
        pool.withTransaction { connection ->
            billService.settleEncounter(connection, id, OffsetDateTime.now(), requireTerminalStatus = true)
        }

    // ——— 医嘱读取/创建/状态机委托（实现位于 MedicalOrderService） ———
    fun createOrder(encounterId: String, body: JsonObject): Future<JsonObject> =
        medicalOrderService.createOrder(encounterId, body)

    fun listOrders(
        encounterId: String,
        orderType: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> = medicalOrderService.listOrders(encounterId, orderType, status, limit, offset)

    fun getOrder(id: String): Future<JsonObject> = medicalOrderService.getOrder(id)

    /** 医嘱给药明细列表（只读，含批次/药品来源摘要） */
    fun getOrderAdministrations(id: String): Future<JsonObject> =
        medicalOrderService.listOrderAdministrations(id)

    fun updateOrderStatus(id: String, body: JsonObject): Future<JsonObject> =
        medicalOrderService.updateOrderStatus(id, body)

    fun nurseCheckOrder(id: String, userId: String, body: JsonObject): Future<JsonObject> =
        medicalOrderService.nurseCheckOrder(id, userId, body)

    /** 护士核对汇总列表：跨入住待核对用药医嘱，供护理汇总页核对 */
    fun listPendingNurseCheckOrders(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> =
        medicalOrderService.listPendingNurseCheckOrders(client, encounterId, search, limit, offset)

    // ——— 011 药房接方/发药内部端口（只供 App 编排调用，不注册为 Healthcare 路由） ———

    fun listMedicationOrdersForPharmacy(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> =
        medicalOrderService.listMedicationOrdersForPharmacy(client, encounterId, search, limit, offset)

    fun lockMedicationOrderForPharmacy(client: SqlClient, medicalOrderId: String): Future<MedicationOrderLockSnapshot> =
        medicalOrderService.lockMedicationOrderForPharmacy(client, medicalOrderId)

    // ========================================================================
    //  医生病程记录 (progress_notes)
    // ========================================================================

    /** 创建医生病程记录：只允许活动养老入住，追加写入、只读历史语义 */
    fun createProgressNote(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateProgressNoteInput(body)
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
                val id = Ulid.generate()
                val now = OffsetDateTime.now()
                // 慢病病程：校验档案存在且属于该老人（不同老人/不同档案严格隔离）
                val chronicCheck: Future<Void> = if (input.noteType == "CHRONIC") {
                    ensureChronicRegistrationBelongs(connection, input.chronicDiseaseId!!, encounter.getString("patient_id"))
                } else {
                    Future.succeededFuture()
                }
                chronicCheck.compose {
                    var insert = ctx.insertInto(PROGRESS_NOTES)
                        .set(PROGRESS_NOTES.ID, id)
                        .set(PROGRESS_NOTES.ENCOUNTER_ID, encounterId)
                        .set(PROGRESS_NOTES.NOTE_TYPE, input.noteType)
                        .set(PROGRESS_NOTES.CONTENT, input.content)
                        .set(PROGRESS_NOTES.PHYSICIAN, input.physician)
                        .set(PROGRESS_NOTES.RECORD_TIME, input.recordTime)
                        .set(PROGRESS_NOTES.CREATED_AT, now)
                    input.metadata?.let { metadata ->
                        insert = insert.set(PROGRESS_NOTES.METADATA, JSONB.valueOf(metadata.encode()))
                    }
                    execute(connection, insert).map {
                        progressNoteResponse(id, encounterId, input, now)
                    }
                }
            }
        }
    }

    /** 按精确 encounter_id 读取病程记录，record_time DESC 分页 */
    fun listProgressNotes(
        encounterId: String,
        noteType: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        conditions.add(PROGRESS_NOTES.ENCOUNTER_ID.eq(encounterId))
        noteType?.takeIf(String::isNotBlank)?.let { conditions.add(PROGRESS_NOTES.NOTE_TYPE.eq(it)) }
        val from = try {
            dateFrom?.takeIf(String::isNotBlank)?.let {
                LocalDate.parse(it).atStartOfDay(businessZone).toOffsetDateTime()
            }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_from"))
        }
        val to = try {
            dateTo?.takeIf(String::isNotBlank)?.let {
                LocalDate.parse(it).plusDays(1).atStartOfDay(businessZone).toOffsetDateTime()
            }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_to"))
        }
        from?.let { conditions.add(PROGRESS_NOTES.RECORD_TIME.ge(it)) }
        to?.let { conditions.add(PROGRESS_NOTES.RECORD_TIME.lt(it)) }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(PROGRESS_NOTES).where(conditions)
        val dataQuery = ctx.selectFrom(PROGRESS_NOTES)
            .where(conditions)
            .orderBy(PROGRESS_NOTES.RECORD_TIME.desc(), PROGRESS_NOTES.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::progressNoteJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 病程记录详情：按 ID 读取，不校验入住状态（历史只读） */
    fun getProgressNote(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(PROGRESS_NOTES).where(PROGRESS_NOTES.ID.eq(id))
        return execute(pool, query).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(progressNoteJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("progress note not found: $id"))
        }
    }

    // ========================================================================
    //  诊断 (diagnoses)
    // ========================================================================

    /** 创建诊断：只允许活动养老入住，追加写入、不提供覆盖式编辑和删除 */
    fun createDiagnosis(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateDiagnosisInput(body)
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
                val id = Ulid.generate()
                val now = OffsetDateTime.now()
                var insert = ctx.insertInto(DIAGNOSES)
                    .set(DIAGNOSES.ID, id)
                    .set(DIAGNOSES.ENCOUNTER_ID, encounterId)
                    .set(DIAGNOSES.DIAGNOSIS_TYPE, input.diagnosisType)
                    .set(DIAGNOSES.DIAGNOSIS_TEXT, input.diagnosisText)
                    .set(DIAGNOSES.DIAGNOSIS_DATE, input.diagnosisDate)
                    .set(DIAGNOSES.PHYSICIAN, input.physician)
                    .set(DIAGNOSES.CREATED_AT, now)
                input.icdCode?.let { insert = insert.set(DIAGNOSES.ICD_CODE, it) }
                input.isMajor?.let { insert = insert.set(DIAGNOSES.IS_MAJOR, it) }
                input.remark?.let { insert = insert.set(DIAGNOSES.METADATA, JSONB.valueOf(JsonObject().put("remark", it).encode())) }
                execute(connection, insert).map {
                    diagnosisResponse(id, encounterId, input, now)
                }
            }
        }
    }

    /** 按精确 encounter_id 读取诊断，诊断日期倒序分页 */
    fun listDiagnoses(
        encounterId: String,
        diagnosisType: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        conditions.add(DIAGNOSES.ENCOUNTER_ID.eq(encounterId))
        diagnosisType?.takeIf(String::isNotBlank)?.let { conditions.add(DIAGNOSES.DIAGNOSIS_TYPE.eq(it)) }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(DIAGNOSES).where(conditions)
        val dataQuery = ctx.selectFrom(DIAGNOSES)
            .where(conditions)
            .orderBy(DIAGNOSES.DIAGNOSIS_DATE.desc(), DIAGNOSES.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::diagnosisJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 诊断详情：按 ID 读取，不校验入住状态（历史只读） */
    fun getDiagnosis(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(DIAGNOSES).where(DIAGNOSES.ID.eq(id))
        return execute(pool, query).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(diagnosisJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("diagnosis not found: $id"))
        }
    }

    // ========================================================================
    //  复评与照护计划修订
    // ========================================================================

    /**
     * 在单个数据库事务内完成：锁定 encounter/period → 锁定唯一活动计划 →
     * 检查 IN_PROGRESS 执行 → 写入复评 → 收束旧计划/措施/任务 →
     * 创建新计划/措施/任务 → 写入修订关系。任一步失败整笔回滚。
     */
    fun createCarePlanRevision(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateCarePlanRevisionInput(body)
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
                val patientId = requireNotNull(encounter.getString("patient_id"))
                carePlanRevisionService
                    .lockPeriodForEncounter(connection, encounterId, patientId)
                    .compose { period ->
                        val periodId = requireNotNull(period.getString("id"))
                        val periodStartDate = period.getString("start_date")?.let { LocalDate.parse(it) }
                        if (periodStartDate != null && input.assessment.assessDate.isBefore(periodStartDate)) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("assess_date cannot be earlier than the care period start date")
                            )
                        }
                        val today = businessDate(OffsetDateTime.now())
                        if (input.assessment.assessDate.isAfter(today)) {
                            return@compose Future.failedFuture(IllegalArgumentException("assess_date cannot be in the future"))
                        }
                        if (input.plan.startDate.isBefore(input.assessment.assessDate)) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("plan.start_date cannot be earlier than assess_date")
                            )
                        }
                        if (input.plan.endDate != null && input.plan.endDate.isBefore(input.plan.startDate)) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("plan.end_date cannot be earlier than start_date")
                            )
                        }

                        planService.lockActivePlan(connection, periodId).compose { oldPlan ->
                            val oldPlanId = requireNotNull(oldPlan.getString("id"))
                            planService.lockActivePlanItems(connection, oldPlanId).compose { _ ->
                                taskService.lockActivePlanTasks(connection, oldPlanId).compose { oldTasks ->
                                    val oldTaskIds = oldTasks.map { it.getString("id")!! }
                                    carePlanRevisionService.checkNoInProgressExecution(connection, oldTaskIds).compose {
                                        carePlanRevisionService.nextRevisionNo(connection, periodId).compose { revisionNo ->
                                            carePlanRevisionService.createAssessment(
                                                connection,
                                                CarePlanRevisionService.AssessmentCreateInput(
                                                    encounterId = encounterId,
                                                    periodId = periodId,
                                                    assessType = input.assessment.assessType,
                                                    assessDate = input.assessment.assessDate,
                                                    assessor = input.assessment.assessor,
                                                    totalScore = input.assessment.totalScore,
                                                    resultLevel = input.assessment.resultLevel,
                                                    detail = input.assessment.detail,
                                                    remark = input.assessment.remark,
                                                ),
                                            ).compose { assessment ->
                                                planService.terminatePlan(connection, oldPlanId).compose {
                                                    taskService.cancelPlanTasks(connection, oldTaskIds).compose {
                                                        planService.createPlanWithItems(
                                                            connection,
                                                            PlanService.PlanCreateInput(
                                                                periodId = periodId,
                                                                encounterId = encounterId,
                                                                planName = input.plan.planName,
                                                                goals = input.plan.goals,
                                                                createdBy = input.plan.createdBy,
                                                                startDate = input.plan.startDate,
                                                                endDate = input.plan.endDate,
                                                                items = input.plan.items.map { item ->
                                                                    PlanService.PlanItemInput(
                                                                        action = item.action,
                                                                        frequencyCode = item.frequencyCode,
                                                                        frequencyName = item.frequencyName,
                                                                        durationDays = item.durationDays,
                                                                        remark = item.remark,
                                                                    )
                                                                },
                                                            ),
                                                        ).compose { newPlan ->
                                                            val newPlanId = requireNotNull(newPlan.getString("id"))
                                                            createPlanTasks(
                                                                connection,
                                                                periodId,
                                                                encounterId,
                                                                newPlan.getJsonArray("items") ?: JsonArray(),
                                                                input,
                                                            ).compose { tasks ->
                                                                    carePlanRevisionService.insertRevision(
                                                                        connection,
                                                                        periodId,
                                                                        encounterId,
                                                                        requireNotNull(assessment.getString("id")),
                                                                        oldPlanId,
                                                                        newPlanId,
                                                                        revisionNo,
                                                                    ).map { revision ->
                                                                        JsonObject()
                                                                            .put("revision_id", revision.getString("id"))
                                                                            .put("revision_no", revisionNo)
                                                                            .put("assessment", assessment)
                                                                            .put("previous_plan", JsonObject()
                                                                                .put("id", oldPlanId)
                                                                                .put("status", "DISCONTINUED"))
                                                                            .put("plan", newPlan)
                                                                            .put("items", newPlan.getJsonArray("items") ?: JsonArray())
                                                                            .put("tasks", tasks)
                                                                    }
                                                                }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    /** 修订历史列表：按修订号倒序，空列表返回空 records 和 total 0 */
    fun listCarePlanRevisions(encounterId: String): Future<JsonObject> =
        carePlanRevisionService.listRevisions(encounterId)

    /** 修订历史详情：按修订 ID 读取，服务端再次校验 period/encounter 归属 */
    fun getCarePlanRevision(id: String): Future<JsonObject> =
        carePlanRevisionService.getRevision(id)

    /**
     * 为新计划每条措施派生一条 `NURSING` 任务，精确写入 plan_item_id / period_id / encounter_id；
     * 任务结束日期按措施 duration_days 计算（计划开始日 + 天数），未提供时为空。
     */
    private fun createPlanTasks(
        connection: SqlClient,
        periodId: String,
        encounterId: String,
        planItems: JsonArray,
        input: RevisionCreateInput,
    ): Future<JsonArray> {
        val newPlan = input.plan
        fun loop(index: Int, acc: JsonArray): Future<JsonArray> {
            if (index >= planItems.size()) return Future.succeededFuture(acc)
            val item = planItems.getJsonObject(index)
            val itemId = requireNotNull(item.getString("id"))
            val durationDays = item.getInteger("duration_days")
            val taskEndDate = durationDays?.let { newPlan.startDate.plusDays(it.toLong()) }
            return taskService.createPlanTask(
                connection,
                TaskService.PlanTaskInput(
                    periodId = periodId,
                    encounterId = encounterId,
                    planItemId = itemId,
                    description = requireNotNull(item.getString("action")),
                    frequencyCode = item.getString("frequency_code"),
                    frequencyName = item.getString("frequency_name"),
                    startDate = newPlan.startDate,
                    endDate = taskEndDate,
                ),
            ).compose { task ->
                loop(index + 1, acc.add(task))
            }
        }
        return loop(0, JsonArray())
    }

    private fun validateCarePlanRevisionInput(body: JsonObject): RevisionCreateInput {
        rejectUnknownKeys(body, setOf("assessment", "plan"), "request")

        val assessmentValue = body.getValue("assessment")
        if (assessmentValue !is JsonObject) throw IllegalArgumentException("assessment is required")
        rejectUnknownKeys(
            assessmentValue,
            setOf("assess_type", "assess_date", "assessor", "total_score", "result_level", "detail", "remark"),
            "assessment",
        )

        val assessType = requiredText(assessmentValue, "assess_type")
        if (!CarePlanRevisionService.isValidAssessType(assessType)) {
            throw IllegalArgumentException(
                "invalid assess_type, must be one of: ${CarePlanRevisionService.VALID_ASSESS_TYPES}"
            )
        }
        val assessDate = localDate(requiredText(assessmentValue, "assess_date"), "assess_date")
        val assessor = optionalText(assessmentValue, "assessor")
        val totalScore = optionalNumber(assessmentValue, "total_score")
        val resultLevel = optionalText(assessmentValue, "result_level")
        val detail = optionalJsonObject(assessmentValue, "detail")
        val remark = optionalText(assessmentValue, "remark")

        val planValue = body.getValue("plan")
        if (planValue !is JsonObject) throw IllegalArgumentException("plan is required")
        rejectUnknownKeys(
            planValue,
            setOf("plan_name", "goals", "created_by", "start_date", "end_date", "items"),
            "plan",
        )

        val planName = requiredText(planValue, "plan_name")
        val goals = optionalText(planValue, "goals")
        val createdBy = optionalText(planValue, "created_by")
        val startDate = localDate(requiredText(planValue, "start_date"), "start_date")
        val endDate = planValue.getValue("end_date")?.let {
            if (it !is String) throw IllegalArgumentException("end_date must be a string")
            localDate(it, "end_date")
        }
        val items = parsePlanItems(planValue.getValue("items"))

        return RevisionCreateInput(
            RevisionAssessmentInput(assessType, assessDate, assessor, totalScore, resultLevel, detail, remark),
            RevisionPlanInput(planName, goals, createdBy, startDate, endDate, items),
        )
    }

    private fun parsePlanItems(value: Any?): List<RevisionPlanItemInput> {
        if (value == null) return emptyList()
        if (value !is JsonArray) throw IllegalArgumentException("plan.items must be an array")
        val result = mutableListOf<RevisionPlanItemInput>()
        for (raw in value) {
            if (raw !is JsonObject) throw IllegalArgumentException("plan items must be objects")
            rejectUnknownKeys(raw, setOf("action", "frequency_code", "frequency_name", "duration_days", "remark"), "plan items")
            val action = requiredText(raw, "action")
            val frequencyCode = optionalText(raw, "frequency_code")
            val frequencyName = optionalText(raw, "frequency_name")
            val durationDays = raw.getValue("duration_days")?.let { v ->
                if (v !is Int && v !is Long) throw IllegalArgumentException("duration_days must be a non-negative integer")
                val days = (v as Number).toInt()
                if (days < 0) throw IllegalArgumentException("duration_days must be a non-negative integer")
                days
            }
            val remark = optionalText(raw, "remark")
            result.add(RevisionPlanItemInput(action, frequencyCode, frequencyName, durationDays, remark))
        }
        return result
    }

    private fun rejectUnknownKeys(obj: JsonObject, allowed: Set<String>, label: String) {
        val unknown = obj.fieldNames().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("unsupported keys in $label: ${unknown.joinToString(", ")}")
        }
    }

    private fun optionalText(obj: JsonObject, key: String): String? {
        if (!obj.containsKey(key) || obj.getValue(key) == null) return null
        val value = obj.getValue(key)
        if (value !is String) throw IllegalArgumentException("$key must be a string")
        return value.trim().takeIf(String::isNotBlank)
    }

    private fun optionalNumber(obj: JsonObject, key: String): Double? {
        if (!obj.containsKey(key) || obj.getValue(key) == null) return null
        val value = obj.getValue(key)
        if (value !is Number) throw IllegalArgumentException("$key must be a number")
        return value.toDouble()
    }

    private fun optionalJsonObject(obj: JsonObject, key: String): JsonObject? {
        if (!obj.containsKey(key) || obj.getValue(key) == null) return null
        val value = obj.getValue(key)
        if (value !is JsonObject) throw IllegalArgumentException("$key must be a JSON object")
        return value
    }

    private data class RevisionAssessmentInput(
        val assessType: String,
        val assessDate: LocalDate,
        val assessor: String?,
        val totalScore: Double?,
        val resultLevel: String?,
        val detail: JsonObject?,
        val remark: String?,
    )

    private data class RevisionPlanItemInput(
        val action: String,
        val frequencyCode: String?,
        val frequencyName: String?,
        val durationDays: Int?,
        val remark: String?,
    )

    private data class RevisionPlanInput(
        val planName: String,
        val goals: String?,
        val createdBy: String?,
        val startDate: LocalDate,
        val endDate: LocalDate?,
        val items: List<RevisionPlanItemInput>,
    )

    private data class RevisionCreateInput(
        val assessment: RevisionAssessmentInput,
        val plan: RevisionPlanInput,
    )

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

    private fun ensureNoOtherActiveElderlyAdmission(
        client: SqlClient,
        patientId: String,
        excludeEncounterId: String,
    ): Future<Void> {
        val query = ctx.selectOne()
            .from(ENCOUNTERS)
            .where(
                ENCOUNTERS.PATIENT_ID.eq(patientId)
                    .and(ENCOUNTERS.ENCOUNTER_TYPE.eq("ELDERLY_CARE"))
                    .and(ENCOUNTERS.STATUS.eq("ACTIVE"))
                    .and(ENCOUNTERS.ID.ne(excludeEncounterId)),
            )
        return execute(client, query).compose { rows ->
            if (rows.size() == 0) Future.succeededFuture()
            else Future.failedFuture(ConflictException("patient already has another active elderly admission"))
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

    // ========================================================================
    //  017 院内护理异常事件
    //  创建/追加处置/关闭走外层事务；列表与详情只读，绝不产生写副作用。
    // ========================================================================

    /** 上报异常事件：锁定 encounter 与精确 period，写入事件与首条审计事实。 */
    fun createIncident(encounterId: String, body: JsonObject, userId: String): Future<JsonObject> {
        val request = try {
            validateIncidentCreateInput(body)
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
                val patientId = requireNotNull(encounter.getString("patient_id"))
                carePlanRevisionService.lockPeriodForEncounter(connection, encounterId, patientId).compose { period ->
                    val periodStart = period.getString("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val occurredBusinessDate = NursingIncidentService.businessDateOf(request.occurredAt)
                    if (periodStart != null && occurredBusinessDate.isBefore(periodStart)) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("occurred_at cannot be earlier than the care period start date")
                        )
                    }
                    nursingIncidentService.createIncident(
                        connection,
                        NursingIncidentService.IncidentCreateInput(
                            encounterId = encounterId,
                            periodId = requireNotNull(period.getString("id")),
                            periodStartDate = periodStart,
                            incidentType = request.incidentType,
                            severity = request.severity,
                            occurredAt = request.occurredAt,
                            description = request.description,
                            reporter = userId,
                            initialAction = request.initialAction,
                        ),
                    )
                }
            }
        }
    }

    /** 按精确 encounter 分页列出异常事件；status/date 过滤与分页参数均受控。 */
    fun listIncidents(
        encounterId: String,
        status: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        status?.takeIf(String::isNotBlank)?.let {
            if (it !in NursingIncidentService.VALID_STATUSES) {
                return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${NursingIncidentService.VALID_STATUSES}"))
            }
        }
        val from = try {
            dateFrom?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it) }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_from"))
        }
        val to = try {
            dateTo?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it) }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_to"))
        }
        return getEncounter(encounterId).compose { encounter ->
            if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            }
            nursingIncidentService.listIncidents(encounterId, status, from, to, limit, offset)
        }
    }

    /** 事件详情：主事实 + 全部追加审计事实（只读，按 encounter 精确隔离）。 */
    fun getIncident(encounterId: String, id: String): Future<JsonObject> =
        nursingIncidentService.getIncident(encounterId, id)

    /** 追加处置/通知/观察；事件必须归属 [encounterId]，跨入住返回 404；
     *  事件已关闭或周期终态返回 409。 */
    fun addIncidentAction(encounterId: String, incidentId: String, body: JsonObject, userId: String): Future<JsonObject> {
        val input = try {
            validateIncidentActionInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            nursingIncidentService.appendAction(connection, encounterId, incidentId, input, userId)
        }
    }

    /** 关闭事件：只接受关闭说明；事件必须归属 [encounterId]，跨入住返回 404；
     *  重复关闭返回 409。 */
    fun closeIncident(encounterId: String, incidentId: String, body: JsonObject, userId: String): Future<JsonObject> {
        val closeNote = try {
            rejectUnknownKeys(body, setOf("close_note"), "request")
            val note = requiredText(body, "close_note")
            if (note.length > 2000) throw IllegalArgumentException("close_note must not exceed 2000 characters")
            note
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            nursingIncidentService.closeIncident(connection, encounterId, incidentId, closeNote, userId)
        }
    }

    // ========================================================================
    //  017 班次交接
    //  创建要求 Idempotency-Key；照护单元由服务端从锚定入住 department 推导。
    // ========================================================================

    /**
     * 创建班次交接单：首次 201，同键同内容重试 200，其余冲突 409。
     * 活动养老入住与护理记录快照由本服务在连接上收集（生成表类），
     * Nursing 侧只补齐未完成执行与未关闭事件后冻结为交班事项。
     */
    fun createShiftHandover(body: JsonObject, userId: String, idempotencyKey: String?): Future<Pair<Boolean, JsonObject>> {
        val request = try {
            validateHandoverCreateInput(body, idempotencyKey)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<Pair<Boolean, JsonObject>> { connection ->
            lockEncounter(connection, request.encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                val careUnit = encounter.getString("department")?.trim()?.takeIf(String::isNotBlank)
                    ?: return@compose Future.failedFuture(
                        ConflictException("care unit is not verifiable for this admission")
                    )
                collectHandoverSnapshot(connection, careUnit, request.businessDate).compose { snapshot ->
                    if (snapshot.getJsonArray("admissions").isEmpty()) {
                        return@compose Future.failedFuture(
                            ConflictException("care unit has no active elderly admission; cannot create handover")
                        )
                    }
                    shiftHandoverService.createHandover(
                        connection,
                        ShiftHandoverService.HandoverCreateInput(
                            encounterId = request.encounterId,
                            careUnit = careUnit,
                            businessDate = request.businessDate,
                            shift = request.shift,
                            manualItems = request.manualItems,
                            handoverBy = userId,
                            idempotencyKey = request.idempotencyKey,
                        ),
                        snapshot,
                    )
                }
            }
        }
    }

    /** 按照护单元分页列出交接单；business_date/shift 过滤均为可选项。 */
    fun listShiftHandovers(
        careUnit: String?,
        businessDate: String?,
        shift: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        if (careUnit.isNullOrBlank()) {
            return Future.failedFuture(IllegalArgumentException("care_unit is required"))
        }
        shift?.takeIf(String::isNotBlank)?.let {
            if (it !in ShiftHandoverService.VALID_SHIFTS) {
                return Future.failedFuture(IllegalArgumentException("invalid shift, must be one of: ${ShiftHandoverService.VALID_SHIFTS}"))
            }
        }
        val date = try {
            businessDate?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it) }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid business_date"))
        }
        return shiftHandoverService.listHandovers(careUnit, date, shift, limit, offset)
    }

    /** 交接单详情：头 + 全部冻结/补充事项（只读）。 */
    fun getShiftHandover(id: String): Future<JsonObject> = shiftHandoverService.getHandover(id)

    /** 接班：接班人来自认证主体，只允许一次；请求体必须为空对象。 */
    fun receiveShiftHandover(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        if (!body.isEmpty) {
            return Future.failedFuture(IllegalArgumentException("receive request must be an empty object"))
        }
        return pool.withTransaction<JsonObject> { connection ->
            shiftHandoverService.receiveHandover(connection, id, userId)
        }
    }

    /** 补充事项：只接受正文；来源关联与创建人由服务端写入。 */
    fun appendShiftHandoverItem(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        val content = try {
            rejectUnknownKeys(body, setOf("content"), "request")
            val text = requiredText(body, "content")
            if (text.length > 2000) throw IllegalArgumentException("content must not exceed 2000 characters")
            text
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            shiftHandoverService.appendItem(connection, id, content, userId)
        }
    }

    // ——— 017 交班快照收集（Healthcare 侧，生成表类；Nursing 不直接读 Healthcare 表） ———

    /**
     * 在调用方连接上收集交班只读快照：该照护单元下活动养老入住（含精确
     * 开放照护周期）与本业务日新增护理记录。返回
     * `{ "admissions": [...], "records": [...] }`，供 [ShiftHandoverService]
     * 补齐 Nursing 侧执行/事件后冻结为交班事项。绝不产生任何写副作用。
     */
    private fun collectHandoverSnapshot(client: SqlClient, careUnit: String, businessDate: LocalDate): Future<JsonObject> {
        val encounterQuery = ctx.select(
            ENCOUNTERS.ID.`as`("encounter_id"),
            ENCOUNTERS.PATIENT_ID,
            ENCOUNTERS.ENCOUNTER_NO,
            ENCOUNTERS.WARD,
            PATIENTS.NAME.`as`("patient_name"),
        )
            .from(ENCOUNTERS)
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(
                ENCOUNTERS.ENCOUNTER_TYPE.eq("ELDERLY_CARE")
                    .and(ENCOUNTERS.STATUS.eq("ACTIVE"))
                    .and(ENCOUNTERS.DEPARTMENT.eq(careUnit)),
            )
            .orderBy(ENCOUNTERS.ADMIT_DATE.asc(), ENCOUNTERS.ID.asc())

        return execute(client, encounterQuery).compose { rows ->
            val admissions = rows.map { row ->
                JsonObject()
                    .put("encounter_id", row.getString("encounter_id"))
                    .put("patient_id", row.getString("patient_id"))
                    .put("patient_name", row.getString("patient_name"))
                    .put("encounter_no", row.getString("encounter_no"))
                    .put("ward", row.getString("ward"))
            }
            if (admissions.isEmpty()) {
                return@compose Future.succeededFuture(
                    JsonObject().put("admissions", JsonArray()).put("records", JsonArray())
                )
            }
            val encounterIds = admissions.mapNotNull { it.getString("encounter_id") }
            val periodQuery = ctx.selectFrom(NURSING_SERVICE_PERIODS)
                .where(
                    NURSING_SERVICE_PERIODS.ENCOUNTER_ID.`in`(encounterIds)
                        .and(NURSING_SERVICE_PERIODS.STATUS.`in`(HANDOVER_PERIOD_OPEN_STATUSES)),
                )
            execute(client, periodQuery).compose { periodRows ->
                val periodByEncounter = periodRows.associateBy { it.getString("encounter_id") }
                val withPeriods = admissions.mapNotNull { enc ->
                    val period = periodByEncounter[enc.getString("encounter_id")] ?: return@mapNotNull null
                    enc.copy()
                        .put("period_id", period.getString("id"))
                        .put("period_start_date", period.getLocalDate("start_date")?.toString())
                }
                val periodIds = withPeriods.mapNotNull { it.getString("period_id") }
                if (periodIds.isEmpty()) {
                    return@compose Future.succeededFuture(
                        JsonObject().put("admissions", JsonArray(withPeriods)).put("records", JsonArray())
                    )
                }
                val periodIdField = DSL.field("{0} ->> {1}", String::class.java, MEDICAL_RECORDS.METADATA, DSL.inline("period_id"))
                val recordsQuery = ctx.select(
                    MEDICAL_RECORDS.ID,
                    MEDICAL_RECORDS.TITLE,
                    MEDICAL_RECORDS.CONTENT,
                    MEDICAL_RECORDS.RECORD_DATE,
                    MEDICAL_RECORDS.METADATA,
                )
                    .from(MEDICAL_RECORDS)
                    .where(
                        MEDICAL_RECORDS.RECORD_TYPE.eq("NURSING_RECORD")
                            .and(MEDICAL_RECORDS.RECORD_DATE.eq(businessDate))
                            .and(periodIdField.`in`(periodIds)),
                    )
                    .orderBy(MEDICAL_RECORDS.CREATED_AT.asc(), MEDICAL_RECORDS.ID.asc())
                execute(client, recordsQuery).map { recordRows ->
                    val records = recordRows.map { row ->
                        val meta = row.getValue("metadata") as? JsonObject ?: JsonObject()
                        JsonObject()
                            .put("id", row.getString("id"))
                            .put("period_id", meta.getString("period_id"))
                            .put("title", row.getString("title"))
                            .put("content", row.getString("content"))
                            .put("record_date", row.getLocalDate("record_date")?.toString())
                    }
                    JsonObject().put("admissions", JsonArray(withPeriods)).put("records", JsonArray(records))
                }
            }
        }
    }

    /** 校验照护单元仍存在活动养老入住（读 healthcare.encounters，生成表类）。
     *  由 [ShiftHandoverService] 以连接绑定端口调用；无活动入住时交接单只读。 */
    private fun ensureCareUnitActiveForHandover(client: SqlClient, careUnit: String): Future<Void> {
        val query = ctx.selectOne()
            .from(ENCOUNTERS)
            .where(
                ENCOUNTERS.ENCOUNTER_TYPE.eq("ELDERLY_CARE")
                    .and(ENCOUNTERS.STATUS.eq("ACTIVE"))
                    .and(ENCOUNTERS.DEPARTMENT.eq(careUnit)),
            )
        return execute(client, query).compose { rows ->
            if (rows.size() == 0) {
                return@compose Future.failedFuture(
                    ConflictException("care unit has no active elderly admission; handover is read-only")
                )
            }
            Future.succeededFuture()
        }
    }

    // ——— 017 私有校验 ———

    private data class IncidentRequest(
        val incidentType: String,
        val severity: String,
        val occurredAt: OffsetDateTime,
        val description: String,
        val initialAction: NursingIncidentService.ActionInput?,
    )

    private data class HandoverRequest(
        val encounterId: String,
        val businessDate: LocalDate,
        val shift: String,
        val manualItems: List<String>,
        val idempotencyKey: String,
    )

    private fun validateIncidentCreateInput(body: JsonObject): IncidentRequest {
        rejectUnknownKeys(body, setOf("incident_type", "severity", "occurred_at", "description", "initial_action"), "request")
        val incidentType = requiredText(body, "incident_type")
        if (incidentType !in NursingIncidentService.VALID_INCIDENT_TYPES) {
            throw IllegalArgumentException("invalid incident_type, must be one of: ${NursingIncidentService.VALID_INCIDENT_TYPES}")
        }
        val severity = requiredText(body, "severity")
        if (severity !in NursingIncidentService.VALID_SEVERITIES) {
            throw IllegalArgumentException("invalid severity, must be one of: ${NursingIncidentService.VALID_SEVERITIES}")
        }
        val occurredAt = offsetDateTime(requiredText(body, "occurred_at"), "occurred_at")
        if (occurredAt.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("occurred_at cannot be in the future")
        }
        val description = requiredText(body, "description")
        if (description.length > 2000) {
            throw IllegalArgumentException("description must not exceed 2000 characters")
        }
        val initialAction = body.getValue("initial_action")?.let { value ->
            if (value !is JsonObject) throw IllegalArgumentException("initial_action must be a JSON object")
            validateIncidentActionInput(value)
        }
        return IncidentRequest(incidentType, severity, occurredAt, description, initialAction)
    }

    private fun validateIncidentActionInput(body: JsonObject): NursingIncidentService.ActionInput {
        rejectUnknownKeys(body, setOf("action_type", "body", "notified_party", "notification_result"), "request")
        val actionType = requiredText(body, "action_type")
        if (actionType !in NursingIncidentService.VALID_ACTION_TYPES) {
            throw IllegalArgumentException("invalid action_type, must be one of: ${NursingIncidentService.VALID_ACTION_TYPES}")
        }
        val actionBody = requiredText(body, "body")
        if (actionBody.length > 2000) {
            throw IllegalArgumentException("body must not exceed 2000 characters")
        }
        val notifiedParty = optionalText(body, "notified_party")?.let {
            if (it.length > 200) throw IllegalArgumentException("notified_party must not exceed 200 characters")
            it
        }
        val notificationResult = optionalText(body, "notification_result")?.let {
            if (it.length > 500) throw IllegalArgumentException("notification_result must not exceed 500 characters")
            it
        }
        return NursingIncidentService.ActionInput(actionType, actionBody, notifiedParty, notificationResult)
    }

    private fun validateHandoverCreateInput(body: JsonObject, idempotencyKey: String?): HandoverRequest {
        if (idempotencyKey.isNullOrBlank()) {
            throw IllegalArgumentException("Idempotency-Key header is required")
        }
        rejectUnknownKeys(body, setOf("encounter_id", "business_date", "shift", "manual_items"), "request")
        val encounterId = requiredText(body, "encounter_id")
        val businessDate = localDate(requiredText(body, "business_date"), "business_date")
        if (businessDate.isAfter(businessDate(OffsetDateTime.now()))) {
            throw IllegalArgumentException("business_date cannot be in the future")
        }
        val shift = requiredText(body, "shift")
        if (shift !in ShiftHandoverService.VALID_SHIFTS) {
            throw IllegalArgumentException("invalid shift, must be one of: ${ShiftHandoverService.VALID_SHIFTS}")
        }
        val manualItems = body.getValue("manual_items")?.let { value ->
            if (value !is JsonArray) throw IllegalArgumentException("manual_items must be an array of strings")
            value.map { item ->
                if (item !is String) throw IllegalArgumentException("manual_items must be an array of strings")
                val trimmed = item.trim()
                if (trimmed.isBlank()) throw IllegalArgumentException("manual_items must not contain blank strings")
                if (trimmed.length > 500) throw IllegalArgumentException("manual_items must not exceed 500 characters per item")
                trimmed
            }
        } ?: emptyList()
        if (manualItems.size > 50) throw IllegalArgumentException("manual_items must not exceed 50 items")
        return HandoverRequest(encounterId, businessDate, shift, manualItems, idempotencyKey.trim())
    }

    // ——— 病程记录与诊断私有辅助方法 ———

    private data class ProgressNoteCreateInput(
        val noteType: String,
        val content: String,
        val physician: String,
        val recordTime: OffsetDateTime,
        val metadata: JsonObject?,
        val chronicDiseaseId: String?,
    )

    private fun validateProgressNoteInput(body: JsonObject): ProgressNoteCreateInput {
        rejectUnknownKeys(body, setOf("note_type", "content", "physician", "record_time", "metadata"), "request")
        val noteType = requiredText(body, "note_type")
        if (noteType !in setOf("DAILY", "CHRONIC")) {
            throw IllegalArgumentException("invalid note_type, must be one of: [DAILY, CHRONIC]")
        }
        val content = requiredText(body, "content")
        if (content.length > 2000) {
            throw IllegalArgumentException("content must not exceed 2000 characters")
        }
        val physician = requiredText(body, "physician")
        if (physician.length > 100) {
            throw IllegalArgumentException("physician must not exceed 100 characters")
        }
        val recordTime = body.getString("record_time")?.let { offsetDateTime(it, "record_time") }
            ?: OffsetDateTime.now()
        val metadata = jsonObject(body, "metadata")
        val chronicDiseaseId = if (noteType == "CHRONIC") {
            val value = metadata?.getString("chronic_disease_id")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("metadata.chronic_disease_id is required for CHRONIC notes")
            if (value.length > 32) throw IllegalArgumentException("chronic_disease_id must not exceed 32 characters")
            value
        } else {
            null
        }
        return ProgressNoteCreateInput(noteType, content, physician, recordTime, metadata, chronicDiseaseId)
    }

    /** 慢病病程校验：档案存在且属于该 encounter 的老人（跨档案/跨老人一律拒绝） */
    private fun ensureChronicRegistrationBelongs(client: SqlClient, chronicDiseaseId: String, patientId: String?): Future<Void> {
        val query = ctx.selectOne()
            .from(CHRONIC_DISEASE_REGISTRATIONS)
            .where(
                CHRONIC_DISEASE_REGISTRATIONS.ID.eq(chronicDiseaseId)
                    .and(CHRONIC_DISEASE_REGISTRATIONS.PATIENT_ID.eq(patientId)),
            )
        return execute(client, query).compose { rows ->
            if (rows.size() == 0) {
                Future.failedFuture(
                    HealthcareNotFoundException("chronic disease registration not found for this patient"),
                )
            } else {
                Future.succeededFuture()
            }
        }
    }

    private fun progressNoteJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("note_type", row.getString("note_type"))
            .put("content", row.getString("content"))
            .put("physician", row.getString("physician"))
            .put("record_time", row.getOffsetDateTime("record_time")?.toString())
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

    private fun progressNoteResponse(
        id: String,
        encounterId: String,
        input: ProgressNoteCreateInput,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("note_type", input.noteType)
            .put("content", input.content)
            .put("physician", input.physician)
            .put("record_time", input.recordTime.toString())
            .put("metadata", input.metadata)
            .put("created_at", now.toString())

    private data class DiagnosisCreateInput(
        val diagnosisType: String,
        val diagnosisText: String,
        val diagnosisDate: LocalDate,
        val physician: String,
        val icdCode: String?,
        val isMajor: Boolean?,
        val remark: String?,
    )

    private fun validateDiagnosisInput(body: JsonObject): DiagnosisCreateInput {
        rejectUnknownKeys(
            body,
            setOf("diagnosis_type", "diagnosis_text", "icd_code", "diagnosis_date", "physician", "is_major", "remark"),
            "request",
        )
        val diagnosisType = requiredText(body, "diagnosis_type")
        if (diagnosisType !in diagnosisTypes) {
            throw IllegalArgumentException("invalid diagnosis_type, must be one of: $diagnosisTypes")
        }
        val diagnosisText = requiredText(body, "diagnosis_text")
        if (diagnosisText.length > 2000) {
            throw IllegalArgumentException("diagnosis_text must not exceed 2000 characters")
        }
        val diagnosisDate = localDate(requiredText(body, "diagnosis_date"), "diagnosis_date")
        val physician = requiredText(body, "physician")
        if (physician.length > 100) {
            throw IllegalArgumentException("physician must not exceed 100 characters")
        }
        val icdCode = optionalText(body, "icd_code")?.let {
            if (it.length > 32) throw IllegalArgumentException("icd_code must not exceed 32 characters")
            it
        }
        val isMajor = body.getValue("is_major")?.let { value ->
            if (value !is Boolean) throw IllegalArgumentException("is_major must be a boolean")
            value
        }
        val remark = optionalText(body, "remark")?.let {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
            it
        }
        return DiagnosisCreateInput(diagnosisType, diagnosisText, diagnosisDate, physician, icdCode, isMajor, remark)
    }

    private fun diagnosisJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("diagnosis_type", row.getString("diagnosis_type"))
            .put("icd_code", row.getString("icd_code"))
            .put("diagnosis_text", row.getString("diagnosis_text"))
            .put("diagnosis_date", row.getLocalDate("diagnosis_date")?.toString())
            .put("physician", row.getString("physician"))
            .put("is_major", row.getBoolean("is_major"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

    private fun diagnosisResponse(
        id: String,
        encounterId: String,
        input: DiagnosisCreateInput,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("diagnosis_type", input.diagnosisType)
            .put("icd_code", input.icdCode)
            .put("diagnosis_text", input.diagnosisText)
            .put("diagnosis_date", input.diagnosisDate.toString())
            .put("physician", input.physician)
            .put("is_major", input.isMajor ?: false)
            .put("metadata", input.remark?.let { JsonObject().put("remark", it) })
            .put("created_at", now.toString())

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
