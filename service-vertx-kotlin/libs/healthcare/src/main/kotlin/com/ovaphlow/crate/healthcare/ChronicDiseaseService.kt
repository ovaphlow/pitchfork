package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.ChronicDiseaseRegistrations.CHRONIC_DISEASE_REGISTRATIONS
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.FollowupPlans.FOLLOWUP_PLANS
import com.ovaphlow.crate.database.gen.healthcare.tables.FollowupRecords.FOLLOWUP_RECORDS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.healthcare.tables.ProgressNotes.PROGRESS_NOTES
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 慢病登记档案服务（养老方向）。
 *
 * 业务规则（服务端强制）：
 *  1. 慢病档案是患者级、跨入住周期的长期档案：patient_id 长期锚定；
 *     encounter_id 为登记时的活动入住锚点，必须归属该患者且为 ELDERLY_CARE。
 *  2. 登记只允许活动入住（encounter.status=ACTIVE），DECEASED 患者拒绝；
 *     同一患者同一病种（disease_name）仅允许一条「管理中」档案（防重复建档）。
 *  3. 随访频率为中文枚举：每月/每两月/每季度/每半年/每年；缺省时按服务端
 *     病种默认频率常量表取，metadata.followup_frequency 可覆盖默认值。
 *  4. 登记成功后同一事务内自动生成首轮「慢病随访」计划（planned_date=
 *     登记日+频率，且不早于入住开始日，沿用 V508 规则），计划 metadata 记录
 *     chronic_disease_id 与 source=慢病登记 供追溯。
 *  5. 随访完成联动（由 FollowupService 在完成事务内调用）：慢病随访计划完成
 *     时按记录 next_followup_date（优先）或档案频率滚动生成下一轮；同档案
 *     同 planned_date 已存在未取消计划时幂等跳过；档案非「管理中」时停止生成。
 *  6. 档案状态机：管理中 → 已缓解 | 已停管；已缓解/已停管 → 管理中（恢复）；
 *     软停管不物理删除，审计一致；停管后不再自动生成新计划。
 *  7. 病程记录复用 progress_notes（note_type=CHRONIC，metadata.chronic_disease_id
 *     关联本档案），只增不改。
 */
class ChronicDiseaseService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        private val businessZone = ZoneId.of("Asia/Shanghai")

        val controlStatuses = setOf("良好", "一般", "较差", "未控制")
        val followupFrequencies = setOf("每月", "每两月", "每季度", "每半年", "每年")
        val registrationStatuses = setOf("管理中", "已缓解", "已停管")

        /** 病种默认随访频率（服务端常量表，可被请求字段/metadata 覆盖） */
        val defaultFrequencies = mapOf(
            "高血压" to "每月",
            "糖尿病" to "每季度",
            "冠心病" to "每季度",
            "脑卒中" to "每季度",
            "慢阻肺" to "每季度",
            "高脂血症" to "每半年",
            "骨质疏松" to "每半年",
        )

        /** 登记写接口白名单：id/status/created_at 等一律由服务端管控 */
        private val createKeys = setOf(
            "patient_id", "encounter_id", "disease_name", "icd_code",
            "confirmed_date", "control_status", "followup_frequency",
            "physician", "remark", "metadata",
        )

        /** 状态 PATCH 白名单 */
        private val statusKeys = setOf("status")

        private fun today(): LocalDate = LocalDate.now(businessZone)

        /** 频率 → 月数（滚动生成下一轮计划用） */
        fun monthsOf(frequency: String): Long =
            when (frequency) {
                "每月" -> 1
                "每两月" -> 2
                "每季度" -> 3
                "每半年" -> 6
                "每年" -> 12
                else -> throw IllegalArgumentException("invalid followup_frequency: $frequency")
            }

        private fun chronicIdOf(metadata: Any?): String? {
            if (metadata == null) return null
            val value = try {
                when (metadata) {
                    is JsonObject -> metadata
                    is JSONB -> JsonObject(metadata.data())
                    else -> return null
                }
            } catch (_: RuntimeException) {
                return null
            }
            return value.getString("chronic_disease_id")?.trim()?.takeIf(String::isNotBlank)
        }

        /** PG 特定：JSONB 对象键提取（metadata ->> 'chronic_disease_id'） */
        private fun chronicDiseaseIdField(field: org.jooq.Field<JSONB>): org.jooq.Field<String> =
            DSL.field("{0} ->> 'chronic_disease_id'", String::class.java, field)
    }

    // ========================================================================
    //  慢病登记
    // ========================================================================

    /**
     * 登记慢病档案：校验归属（patient/encounter 一致、ELDERLY_CARE、活动入住、
     * 非 DECEASED）、同一患者同一病种防重复建档；同一事务内自动生成首轮
     * 「慢病随访」计划（planned_date = 登记日 + 频率，且不早于入住开始日）。
     */
    fun createRegistration(body: JsonObject, userId: String): Future<JsonObject> {
        val input = try {
            validateCreateInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val registrationDate = today()
        return pool.withTransaction<JsonObject> { connection ->
            getPatient(connection, input.patientId).compose { patient ->
                if (patient.getString("status") == "DECEASED") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("cannot register a chronic disease for a deceased patient"),
                    )
                }
                lockEncounter(connection, input.encounterId).compose { encounter ->
                    if (encounter.getString("patient_id") != input.patientId) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("encounter does not belong to the specified patient"),
                        )
                    }
                    if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("encounter must be of type ELDERLY_CARE"),
                        )
                    }
                    if (encounter.getString("status") != "ACTIVE") {
                        return@compose Future.failedFuture(
                            ConflictException("chronic disease registration requires an active admission"),
                        )
                    }
                    ensureNoActiveRegistration(connection, input.patientId, input.diseaseName).compose {
                        execute(connection, registrationInsert(body, id, input, now))
                            .compose {
                                // 自动生成首轮随访计划（同事务，失败整体回滚）
                                createFirstFollowupPlan(
                                    connection,
                                    registrationId = id,
                                    patientId = input.patientId,
                                    encounterId = input.encounterId,
                                    frequency = input.followupFrequency,
                                    registrationDate = registrationDate,
                                    admitDate = patientAdmitDate(encounter),
                                    assignee = userId,
                                    now = now,
                                )
                            }
                            .map { registrationCreatedJson(id, input, patient.getString("name"), now) }
                    }
                }
            }
        }
    }

    /**
     * 档案列表（按患者聚合展示）：支持按患者/病种/控制状态/档案状态筛选，
     * 附带「下次随访日（待随访计划最早日期）/最近随访结果/是否逾期」聚合字段。
     */
    fun listRegistrations(
        patientId: String?,
        diseaseName: String?,
        controlStatus: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = try {
            mutableListOf<Condition>().also { list ->
                patientId?.takeIf(String::isNotBlank)?.let { list += CHRONIC_DISEASE_REGISTRATIONS.PATIENT_ID.eq(it) }
                diseaseName?.takeIf(String::isNotBlank)?.let {
                    list += CHRONIC_DISEASE_REGISTRATIONS.DISEASE_NAME.containsIgnoreCase(it)
                }
                controlStatus?.takeIf(String::isNotBlank)?.let {
                    list += CHRONIC_DISEASE_REGISTRATIONS.CONTROL_STATUS.eq(validValue(it, controlStatuses, "control_status"))
                }
                status?.takeIf(String::isNotBlank)?.let {
                    list += CHRONIC_DISEASE_REGISTRATIONS.STATUS.eq(validValue(it, registrationStatuses, "status"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(CHRONIC_DISEASE_REGISTRATIONS).where(conditions)
        val dataQuery = registrationBaseQuery(conditions)
            .orderBy(CHRONIC_DISEASE_REGISTRATIONS.CONFIRMED_DATE.desc(), CHRONIC_DISEASE_REGISTRATIONS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::registrationJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 档案详情：按 ID 读取（历史只读，不校验入住状态） */
    fun getRegistration(id: String): Future<JsonObject> =
        execute(pool, registrationBaseQuery(listOf(CHRONIC_DISEASE_REGISTRATIONS.ID.eq(id)))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(registrationJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("chronic disease registration not found: $id"))
        }

    /**
     * 档案详情时间线：慢病病程记录（progress_notes note_type=CHRONIC 且
     * metadata.chronic_disease_id=本档案）+ 随访计划/记录（followup_plans/records
     * 中 followup_type=慢病随访 且 metadata.chronic_disease_id=本档案），各按时间倒序。
     */
    fun getRegistrationTimeline(id: String): Future<JsonObject> =
        execute(pool, ctx.selectOne().from(CHRONIC_DISEASE_REGISTRATIONS).where(CHRONIC_DISEASE_REGISTRATIONS.ID.eq(id)))
            .compose { rows ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(
                        HealthcareNotFoundException("chronic disease registration not found: $id"),
                    )
                }
                val chronicCondition = chronicDiseaseIdField(FOLLOWUP_PLANS.METADATA).eq(id)
                val notesQuery = ctx.selectFrom(PROGRESS_NOTES)
                    .where(chronicDiseaseIdField(PROGRESS_NOTES.METADATA).eq(id))
                    .orderBy(PROGRESS_NOTES.RECORD_TIME.desc(), PROGRESS_NOTES.CREATED_AT.desc())
                val plansQuery = planBaseQuery()
                    .where(chronicCondition)
                    .orderBy(FOLLOWUP_PLANS.PLANNED_DATE.desc(), FOLLOWUP_PLANS.CREATED_AT.desc())
                val recordsQuery = recordBaseQuery()
                    .where(chronicCondition)
                    .orderBy(FOLLOWUP_RECORDS.FOLLOWUP_DATE.desc(), FOLLOWUP_RECORDS.CREATED_AT.desc())
                execute(pool, notesQuery).compose { noteRows ->
                    execute(pool, plansQuery).compose { planRows ->
                        execute(pool, recordsQuery).map { recordRows ->
                            JsonObject()
                                .put("chronic_disease_id", id)
                                .put("progress_notes", JsonArray(noteRows.map(::progressNoteJson)))
                                .put("followup_plans", JsonArray(planRows.map(::planJson)))
                                .put("followup_records", JsonArray(recordRows.map(::recordJson)))
                        }
                    }
                }
            }

    /**
     * 档案状态 PATCH：管理中 → 已缓解 | 已停管；已缓解/已停管 → 管理中。
     * 恢复为「管理中」时校验不与同患者同病种既有管理中档案重复。
     */
    fun updateRegistrationStatus(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        val status: String
        try {
            rejectForbiddenKeys(body, statusKeys, "status update")
            status = validValue(requiredText(body, "status"), registrationStatuses, "status")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            lockRegistration(connection, id).compose { registration ->
                val current = registration.getString("status")
                if (current == status) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("chronic disease registration is already $status"),
                    )
                }
                if (status == "管理中" && current != "管理中") {
                    ensureNoActiveRegistration(
                        connection,
                        registration.getString("patient_id"),
                        registration.getString("disease_name"),
                        excludeId = id,
                    )
                } else {
                    Future.succeededFuture()
                }.compose {
                    execute(
                        connection,
                        ctx.update(CHRONIC_DISEASE_REGISTRATIONS)
                            .set(CHRONIC_DISEASE_REGISTRATIONS.STATUS, status)
                            .set(CHRONIC_DISEASE_REGISTRATIONS.UPDATED_AT, now)
                            .where(CHRONIC_DISEASE_REGISTRATIONS.ID.eq(id)),
                    ).compose {
                        getRegistrationVia(connection, id)
                    }
                }
            }
        }
    }

    // ========================================================================
    //  随访联动：完成慢病随访计划后滚动生成下一轮（由 FollowupService 调用）
    // ========================================================================

    /**
     * 随访完成联动（与完成同事务）：仅当计划为「慢病随访」且 metadata 关联
     * 本档案、档案状态为「管理中」时生成下一轮计划。
     *  - 下一轮日期：随访记录 next_followup_date 优先，否则计划日 + 档案频率；
     *    且不早于入住开始日（沿用 V508 规则）。
     *  - 幂等：同档案同 planned_date 已存在未取消（待随访/已完成）计划时跳过。
     *  - 返回 true 表示生成了新计划，false 表示跳过（不阻断完成事务）。
     */
    fun generateNextPlanAfterCompletion(
        client: SqlClient,
        plan: Row,
        nextFollowupDate: LocalDate?,
        operator: String,
        now: OffsetDateTime,
    ): Future<Boolean> {
        if (plan.getString("followup_type") != "慢病随访") {
            return Future.succeededFuture(false)
        }
        val chronicId = chronicIdOf(plan.getValue("metadata"))
        if (chronicId == null) {
            return Future.succeededFuture(false)
        }
        return execute(
            client,
            ctx.selectFrom(CHRONIC_DISEASE_REGISTRATIONS).where(CHRONIC_DISEASE_REGISTRATIONS.ID.eq(chronicId)),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { registration ->
                if (registration.getString("status") != "管理中") {
                    // 停管/已缓解后停止自动生成
                    return@compose Future.succeededFuture(false)
                }
                val frequency = registration.getString("followup_frequency")
                    ?: return@compose Future.succeededFuture(false)
                val plannedDate = try {
                    // next_followup_date 以记录为准直接作为下一轮；否则按档案频率滚动
                    nextFollowupDate ?: (plan.getLocalDate("planned_date")
                        ?: return@compose Future.succeededFuture(false))
                        .plusMonths(monthsOf(frequency))
                } catch (error: IllegalArgumentException) {
                    return@compose Future.succeededFuture(false)
                }
                ensureNoActivePlan(client, chronicId, plannedDate).compose { allowed ->
                    if (!allowed) {
                        return@compose Future.succeededFuture(false)
                    }
                    val encounterId = plan.getString("encounter_id")
                    val admitDate = getAdmitDate(client, encounterId)
                    admitDate.compose { admit ->
                        val effectiveDate = if (admit != null && plannedDate.isBefore(admit)) admit else plannedDate
                        val planId = Ulid.generate()
                        val metadata = JsonObject()
                            .put("chronic_disease_id", chronicId)
                            .put("source", "慢病随访完成")
                        execute(
                            client,
                            ctx.insertInto(FOLLOWUP_PLANS)
                                .set(FOLLOWUP_PLANS.ID, planId)
                                .set(FOLLOWUP_PLANS.PATIENT_ID, plan.getString("patient_id"))
                                .set(FOLLOWUP_PLANS.ENCOUNTER_ID, encounterId)
                                .set(FOLLOWUP_PLANS.FOLLOWUP_TYPE, "慢病随访")
                                .set(FOLLOWUP_PLANS.PLANNED_DATE, effectiveDate)
                                .set(FOLLOWUP_PLANS.PLANNED_WAY, "电话")
                                .set(FOLLOWUP_PLANS.ASSIGNEE, operator)
                                .set(FOLLOWUP_PLANS.STATUS, "待随访")
                                .set(FOLLOWUP_PLANS.METADATA, JSONB.valueOf(metadata.encode()))
                                .set(FOLLOWUP_PLANS.CREATED_AT, now)
                                .set(FOLLOWUP_PLANS.UPDATED_AT, now),
                        ).map { true }
                    }
                }
            } ?: Future.succeededFuture(false)
        }
    }

    // ========================================================================
    //  查询构造
    // ========================================================================

    private fun registrationBaseQuery(conditions: List<Condition>): org.jooq.SelectConditionStep<org.jooq.Record> {
        val chronicIdOfPlan = chronicDiseaseIdField(FOLLOWUP_PLANS.METADATA)
        val nextPlanDate = DSL.select(DSL.min(FOLLOWUP_PLANS.PLANNED_DATE))
            .from(FOLLOWUP_PLANS)
            .where(
                chronicIdOfPlan.eq(CHRONIC_DISEASE_REGISTRATIONS.ID)
                    .and(FOLLOWUP_PLANS.STATUS.eq("待随访")),
            )
        val recentRecordDate = DSL.select(DSL.max(FOLLOWUP_RECORDS.FOLLOWUP_DATE))
            .from(FOLLOWUP_RECORDS)
            .join(FOLLOWUP_PLANS).on(FOLLOWUP_RECORDS.PLAN_ID.eq(FOLLOWUP_PLANS.ID))
            .where(chronicIdOfPlan.eq(CHRONIC_DISEASE_REGISTRATIONS.ID))
        val recentRecordResult = DSL.select(FOLLOWUP_RECORDS.RESULT)
            .from(FOLLOWUP_RECORDS)
            .join(FOLLOWUP_PLANS).on(FOLLOWUP_RECORDS.PLAN_ID.eq(FOLLOWUP_PLANS.ID))
            .where(chronicIdOfPlan.eq(CHRONIC_DISEASE_REGISTRATIONS.ID))
            .orderBy(FOLLOWUP_RECORDS.FOLLOWUP_DATE.desc())
            .limit(1)
        return ctx.select(
            CHRONIC_DISEASE_REGISTRATIONS.fields().toList() +
                listOf(
                    PATIENTS.NAME.`as`("patient_name"),
                    ENCOUNTERS.ENCOUNTER_NO.`as`("encounter_no"),
                    ENCOUNTERS.ADMIT_DATE.`as`("admit_date"),
                    DSL.field(nextPlanDate).`as`("next_followup_date"),
                    DSL.field(recentRecordDate).`as`("recent_followup_date"),
                    DSL.field(recentRecordResult).`as`("recent_followup_result"),
                ),
        )
            .from(CHRONIC_DISEASE_REGISTRATIONS)
            .join(PATIENTS).on(CHRONIC_DISEASE_REGISTRATIONS.PATIENT_ID.eq(PATIENTS.ID))
            .join(ENCOUNTERS).on(CHRONIC_DISEASE_REGISTRATIONS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))
            .where(conditions)
    }

    private fun planBaseQuery() = ctx.select(
        FOLLOWUP_PLANS.fields().toList() +
            listOf(PATIENTS.NAME.`as`("patient_name"), ENCOUNTERS.ENCOUNTER_NO.`as`("encounter_no")),
    )
        .from(FOLLOWUP_PLANS)
        .join(PATIENTS).on(FOLLOWUP_PLANS.PATIENT_ID.eq(PATIENTS.ID))
        .join(ENCOUNTERS).on(FOLLOWUP_PLANS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))

    private fun recordBaseQuery() = ctx.select(
        FOLLOWUP_RECORDS.fields().toList() +
            listOf(PATIENTS.NAME.`as`("patient_name"), ENCOUNTERS.ENCOUNTER_NO.`as`("encounter_no")),
    )
        .from(FOLLOWUP_RECORDS)
        .join(FOLLOWUP_PLANS).on(FOLLOWUP_RECORDS.PLAN_ID.eq(FOLLOWUP_PLANS.ID))
        .join(PATIENTS).on(FOLLOWUP_RECORDS.PATIENT_ID.eq(PATIENTS.ID))
        .join(ENCOUNTERS).on(FOLLOWUP_RECORDS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))

    private fun getRegistrationVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, registrationBaseQuery(listOf(CHRONIC_DISEASE_REGISTRATIONS.ID.eq(id)))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(registrationJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("chronic disease registration not found: $id"))
        }

    // ========================================================================
    //  自动生成首轮随访计划（登记事务内）
    // ========================================================================

    private fun createFirstFollowupPlan(
        client: SqlClient,
        registrationId: String,
        patientId: String,
        encounterId: String,
        frequency: String,
        registrationDate: LocalDate,
        admitDate: LocalDate?,
        assignee: String,
        now: OffsetDateTime,
    ): Future<Void?> {
        val plannedDate = registrationDate.plusMonths(monthsOf(frequency))
        val effectiveDate = if (admitDate != null && plannedDate.isBefore(admitDate)) admitDate else plannedDate
        val planId = Ulid.generate()
        val metadata = JsonObject()
            .put("chronic_disease_id", registrationId)
            .put("source", "慢病登记")
        return execute(
            client,
            ctx.insertInto(FOLLOWUP_PLANS)
                .set(FOLLOWUP_PLANS.ID, planId)
                .set(FOLLOWUP_PLANS.PATIENT_ID, patientId)
                .set(FOLLOWUP_PLANS.ENCOUNTER_ID, encounterId)
                .set(FOLLOWUP_PLANS.FOLLOWUP_TYPE, "慢病随访")
                .set(FOLLOWUP_PLANS.PLANNED_DATE, effectiveDate)
                .set(FOLLOWUP_PLANS.PLANNED_WAY, "电话")
                .set(FOLLOWUP_PLANS.ASSIGNEE, assignee)
                .set(FOLLOWUP_PLANS.STATUS, "待随访")
                .set(FOLLOWUP_PLANS.METADATA, JSONB.valueOf(metadata.encode()))
                .set(FOLLOWUP_PLANS.CREATED_AT, now)
                .set(FOLLOWUP_PLANS.UPDATED_AT, now),
        ).map { null }
    }

    /** 幂等：同档案同 planned_date 不存在未取消（待随访/已完成）计划 */
    private fun ensureNoActivePlan(client: SqlClient, chronicId: String, plannedDate: LocalDate): Future<Boolean> {
        val query = ctx.selectOne()
            .from(FOLLOWUP_PLANS)
            .where(
                chronicDiseaseIdField(FOLLOWUP_PLANS.METADATA).eq(chronicId)
                    .and(FOLLOWUP_PLANS.PLANNED_DATE.eq(plannedDate))
                    .and(FOLLOWUP_PLANS.STATUS.ne("已取消")),
            )
        return execute(client, query).map { rows -> rows.size() == 0 }
    }

    /** 同一患者同一病种仅允许一条「管理中」档案 */
    private fun ensureNoActiveRegistration(
        client: SqlClient,
        patientId: String,
        diseaseName: String,
        excludeId: String? = null,
    ): Future<Void> {
        var condition: Condition = CHRONIC_DISEASE_REGISTRATIONS.PATIENT_ID.eq(patientId)
            .and(CHRONIC_DISEASE_REGISTRATIONS.DISEASE_NAME.eq(diseaseName))
            .and(CHRONIC_DISEASE_REGISTRATIONS.STATUS.eq("管理中"))
        if (excludeId != null) {
            condition = condition.and(CHRONIC_DISEASE_REGISTRATIONS.ID.ne(excludeId))
        }
        val query = ctx.selectOne().from(CHRONIC_DISEASE_REGISTRATIONS).where(condition)
        return execute(client, query).compose { rows ->
            if (rows.size() == 0) {
                Future.succeededFuture()
            } else {
                Future.failedFuture(
                    ConflictException("chronic disease registration already exists for this patient and disease"),
                )
            }
        }
    }

    // ========================================================================
    //  校验
    // ========================================================================

    private data class CreateInput(
        val patientId: String,
        val encounterId: String,
        val diseaseName: String,
        val icdCode: String?,
        val confirmedDate: LocalDate,
        val controlStatus: String,
        val followupFrequency: String,
        val physician: String?,
        val remark: String?,
        val metadata: JsonObject?,
    )

    private fun validateCreateInput(body: JsonObject): CreateInput {
        rejectForbiddenKeys(body, createKeys, "chronic disease registration")
        val patientId = requiredText(body, "patient_id")
        val encounterId = requiredText(body, "encounter_id")
        val diseaseName = requiredText(body, "disease_name").also {
            if (it.length > 100) throw IllegalArgumentException("disease_name must not exceed 100 characters")
        }
        val icdCode = body.getString("icd_code")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 32) throw IllegalArgumentException("icd_code must not exceed 32 characters")
        }
        val confirmedDate = localDate(requiredText(body, "confirmed_date"), "confirmed_date")
        val controlStatus = body.getString("control_status")?.trim()?.takeIf(String::isNotBlank)
            ?.let { validValue(it, controlStatuses, "control_status") }
            ?: "良好"
        // 频率：请求字段优先 → metadata.followup_frequency 覆盖 → 病种默认频率表
        val frequencyOverride = body.getJsonObject("metadata")?.getString("followup_frequency")?.trim()?.takeIf(String::isNotBlank)
        val frequency = body.getString("followup_frequency")?.trim()?.takeIf(String::isNotBlank)
            ?: frequencyOverride
            ?: defaultFrequencies[diseaseName]
            ?: throw IllegalArgumentException("followup_frequency is required")
        val followupFrequency = validValue(frequency, followupFrequencies, "followup_frequency")
        val physician = body.getString("physician")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 100) throw IllegalArgumentException("physician must not exceed 100 characters")
        }
        val remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
        }
        val metadata = jsonObject(body, "metadata")
        return CreateInput(
            patientId, encounterId, diseaseName, icdCode, confirmedDate,
            controlStatus, followupFrequency, physician, remark, metadata,
        )
    }

    private fun registrationInsert(body: JsonObject, id: String, input: CreateInput, now: OffsetDateTime): Query {
        var query = ctx.insertInto(CHRONIC_DISEASE_REGISTRATIONS)
            .set(CHRONIC_DISEASE_REGISTRATIONS.ID, id)
            .set(CHRONIC_DISEASE_REGISTRATIONS.PATIENT_ID, input.patientId)
            .set(CHRONIC_DISEASE_REGISTRATIONS.ENCOUNTER_ID, input.encounterId)
            .set(CHRONIC_DISEASE_REGISTRATIONS.DISEASE_NAME, input.diseaseName)
            .set(CHRONIC_DISEASE_REGISTRATIONS.CONFIRMED_DATE, input.confirmedDate)
            .set(CHRONIC_DISEASE_REGISTRATIONS.CONTROL_STATUS, input.controlStatus)
            .set(CHRONIC_DISEASE_REGISTRATIONS.FOLLOWUP_FREQUENCY, input.followupFrequency)
            .set(CHRONIC_DISEASE_REGISTRATIONS.STATUS, "管理中")
            .set(CHRONIC_DISEASE_REGISTRATIONS.CREATED_AT, now)
            .set(CHRONIC_DISEASE_REGISTRATIONS.UPDATED_AT, now)
        input.icdCode?.let { query = query.set(CHRONIC_DISEASE_REGISTRATIONS.ICD_CODE, it) }
        input.physician?.let { query = query.set(CHRONIC_DISEASE_REGISTRATIONS.PHYSICIAN, it) }
        input.remark?.let { query = query.set(CHRONIC_DISEASE_REGISTRATIONS.REMARK, it) }
        // 扩展 metadata 与「登记来源诊断」合并：metadata.diagnosis_id 保留追溯
        val metadata = JsonObject()
        input.metadata?.forEach { entry -> metadata.put(entry.key, entry.value) }
        val diagnosisId = input.metadata?.getString("diagnosis_id")?.trim()?.takeIf(String::isNotBlank)
        if (diagnosisId != null) {
            metadata.put("diagnosis_id", diagnosisId)
        }
        if (metadata.size() > 0) {
            query = query.set(CHRONIC_DISEASE_REGISTRATIONS.METADATA, JSONB.valueOf(metadata.encode()))
        }
        return query
    }

    // ========================================================================
    //  行读取与 JSON 转换
    // ========================================================================

    private fun getPatient(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(PATIENTS).where(PATIENTS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("name", row.getString("name"))
                        .put("status", row.getString("status")),
                )
            } ?: Future.failedFuture(HealthcareNotFoundException("patient not found: $id"))
        }

    private fun lockEncounter(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(id)).forUpdate()).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    private fun lockRegistration(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(CHRONIC_DISEASE_REGISTRATIONS).where(CHRONIC_DISEASE_REGISTRATIONS.ID.eq(id)).forUpdate())
            .compose { rows ->
                rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                    ?: Future.failedFuture(HealthcareNotFoundException("chronic disease registration not found: $id"))
            }

    private fun getAdmitDate(client: SqlClient, encounterId: String): Future<LocalDate?> =
        execute(client, ctx.select(ENCOUNTERS.ADMIT_DATE).from(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId))).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { patientAdmitDate(it) }
        }

    private fun patientAdmitDate(encounter: Row): LocalDate? =
        encounter.getOffsetDateTime("admit_date")?.atZoneSameInstant(businessZone)?.toLocalDate()

    private fun registrationJson(row: Row): JsonObject {
        val nextDate = row.getLocalDate("next_followup_date")
        return JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("encounter_no", row.getString("encounter_no"))
            .put("disease_name", row.getString("disease_name"))
            .put("icd_code", row.getString("icd_code"))
            .put("confirmed_date", row.getLocalDate("confirmed_date")?.toString())
            .put("control_status", row.getString("control_status"))
            .put("followup_frequency", row.getString("followup_frequency"))
            .put("physician", row.getString("physician"))
            .put("remark", row.getString("remark"))
            .put("status", row.getString("status"))
            .put("metadata", row.getValue("metadata"))
            .put("next_followup_date", nextDate?.toString())
            .put("is_overdue", nextDate != null && nextDate.isBefore(today()))
            .put("recent_followup_date", row.getOffsetDateTime("recent_followup_date")?.toString())
            .put("recent_followup_result", row.getString("recent_followup_result"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    private fun registrationCreatedJson(
        id: String,
        input: CreateInput,
        patientName: String?,
        now: OffsetDateTime,
    ): JsonObject {
        val metadata = JsonObject()
        input.metadata?.forEach { entry -> metadata.put(entry.key, entry.value) }
        return JsonObject()
            .put("id", id)
            .put("patient_id", input.patientId)
            .put("patient_name", patientName)
            .put("encounter_id", input.encounterId)
            .put("encounter_no", null as String?)
            .put("disease_name", input.diseaseName)
            .put("icd_code", input.icdCode)
            .put("confirmed_date", input.confirmedDate.toString())
            .put("control_status", input.controlStatus)
            .put("followup_frequency", input.followupFrequency)
            .put("physician", input.physician)
            .put("remark", input.remark)
            .put("status", "管理中")
            .put("metadata", if (metadata.size() > 0) metadata else null)
            .put("next_followup_date", null as String?)
            .put("is_overdue", false)
            .put("recent_followup_date", null as String?)
            .put("recent_followup_result", null as String?)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())
    }

    private fun planJson(row: Row): JsonObject {
        val plannedDate = row.getLocalDate("planned_date")
        val status = row.getString("status")
        val computedStatus =
            if (status == "待随访" && plannedDate != null && plannedDate.isBefore(today())) "已逾期" else status
        return JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("encounter_no", row.getString("encounter_no"))
            .put("followup_type", row.getString("followup_type"))
            .put("planned_date", plannedDate?.toString())
            .put("planned_way", row.getString("planned_way"))
            .put("assignee", row.getString("assignee"))
            .put("status", computedStatus)
            .put("completed_at", row.getOffsetDateTime("completed_at")?.toString())
            .put("cancel_reason", row.getString("cancel_reason"))
            .put("remark", row.getString("remark"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    private fun recordJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("plan_id", row.getString("plan_id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("encounter_no", row.getString("encounter_no"))
            .put("followup_type", row.getString("followup_type"))
            .put("followup_way", row.getString("followup_way"))
            .put("followup_date", row.getOffsetDateTime("followup_date")?.toString())
            .put("contact_object", row.getString("contact_object"))
            .put("condition_summary", row.getString("condition_summary"))
            .put("vitals", row.getValue("vitals"))
            .put("guidance", row.getString("guidance"))
            .put("result", row.getString("result"))
            .put("next_followup_date", row.getLocalDate("next_followup_date")?.toString())
            .put("operator", row.getString("operator"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

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

    // ========================================================================
    //  通用辅助
    // ========================================================================

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun validValue(value: String, allowed: Set<String>, label: String): String =
        value.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun localDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 date")
        }

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key)
        if (value == null) return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }
}
