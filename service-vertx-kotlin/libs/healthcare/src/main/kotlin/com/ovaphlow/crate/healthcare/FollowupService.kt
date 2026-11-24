package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 随访管理服务（养老/福利院方向）。
 *
 * 业务规则（服务端强制）：
 *  1. patient_id / encounter_id 必须存在且一致：encounter 归属该 patient、
 *     类型为 ELDERLY_CARE，不允许跨对象伪造归属。
 *  2. assignee / operator 一律取 JWT 认证上下文（userId），客户端不得提交；
 *     写接口按白名单校验字段，拒绝 assignee/operator/created_at 等字段。
 *  3. 创建计划时 planned_date 不得早于入住开始日；允许为 DISCHARGED 入住创建
 *     离院随访计划（离院随访核心场景），但不得为已 DECEASED 患者创建。
 *  4. 随访记录时间不得晚于当前时间；记录只增不改、不可删除（审计）。
 *  5. 状态机收敛：待随访 → 已完成 | 已取消；取消必须带原因；
 *     「已逾期」由查询时计算（待随访 且 planned_date < 今天），不落库。
 *  6. 无计划的临时随访允许直接新增记录（plan_id 为空）。
 *  7. 「记录随访同时完成计划」单事务；同一计划并发只能完成一次。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class FollowupService(
    private val pool: Pool,
    private val chronicDiseaseService: ChronicDiseaseService? = null,
) {
    companion object {
        private val businessZone = ZoneId.of("Asia/Shanghai")

        /**
         * 转诊要自动创建随访计划，计划必须归属一条养老入住记录。
         * 居家／社区及已离院长者没有在住记录时，给中文业务提示而不是后台英文校验文案。
         */
        const val REFERRAL_WITHOUT_ADMISSION_MESSAGE =
            "该长者没有在住的养老入住记录，无法生成随访计划；请先办理入住后重试"

        val followupTypes = setOf("出院后随访", "慢病随访", "常规电话随访")
        private val followupWays = setOf("电话", "上门", "门诊")
        private val followupResults = setOf("正常", "异常", "需复访", "需转诊")
        private val planStatuses = setOf("待随访", "已完成", "已取消")

        private const val STATUS_PENDING = "待随访"
        private const val STATUS_COMPLETED = "已完成"
        private const val STATUS_CANCELLED = "已取消"

        /** 随访计划写接口白名单：assignee/status/created_at 等一律由服务端管控 */
        private val planWriteKeys = setOf(
            "patient_id", "encounter_id", "followup_type", "planned_date",
            "planned_way", "remark", "metadata",
        )

        /** 随访记录写接口白名单：operator/created_at 等一律由服务端管控 */
        private val recordWriteKeys = setOf(
            "plan_id", "patient_id", "encounter_id", "followup_type", "followup_way",
            "followup_date", "contact_object", "condition_summary", "vitals",
            "guidance", "result", "next_followup_date", "metadata",
        )

        /** 计划状态流转白名单：record 为内联随访记录（patient/encounter 取自计划） */
        private val statusWriteKeys = setOf("status", "record_id", "record", "cancel_reason")

        private const val PLAN_COLUMNS =
            "healthcare.followup_plans.id, healthcare.followup_plans.patient_id, healthcare.followup_plans.encounter_id, healthcare.followup_plans.followup_type, healthcare.followup_plans.planned_date, healthcare.followup_plans.planned_way, " +
                "healthcare.followup_plans.assignee, healthcare.followup_plans.status, healthcare.followup_plans.completed_at, healthcare.followup_plans.cancel_reason, healthcare.followup_plans.remark, healthcare.followup_plans.metadata, " +
                "healthcare.followup_plans.created_at, healthcare.followup_plans.updated_at"

        private const val RECORD_COLUMNS =
            "healthcare.followup_records.id, healthcare.followup_records.plan_id, healthcare.followup_records.patient_id, healthcare.followup_records.encounter_id, healthcare.followup_records.followup_type, healthcare.followup_records.followup_way, " +
                "healthcare.followup_records.followup_date, healthcare.followup_records.contact_object, healthcare.followup_records.condition_summary, healthcare.followup_records.vitals, healthcare.followup_records.guidance, " +
                "healthcare.followup_records.result, healthcare.followup_records.next_followup_date, healthcare.followup_records.operator, healthcare.followup_records.metadata, healthcare.followup_records.created_at, healthcare.followup_records.updated_at"

        /** 计划基础查询：挂患者名与住院号（列表/详情/时间线共用）。 */
        private const val PLAN_BASE_SQL =
            "SELECT $PLAN_COLUMNS, healthcare.patients.name AS patient_name, healthcare.encounters.encounter_no AS encounter_no " +
                "FROM healthcare.followup_plans " +
                "JOIN healthcare.patients ON healthcare.patients.id = healthcare.followup_plans.patient_id " +
                "JOIN healthcare.encounters ON healthcare.encounters.id = healthcare.followup_plans.encounter_id"

        /** 记录基础查询：挂患者名与住院号（列表/详情/时间线共用）。 */
        private const val RECORD_BASE_SQL =
            "SELECT $RECORD_COLUMNS, healthcare.patients.name AS patient_name, healthcare.encounters.encounter_no AS encounter_no " +
                "FROM healthcare.followup_records " +
                "JOIN healthcare.patients ON healthcare.patients.id = healthcare.followup_records.patient_id " +
                "JOIN healthcare.encounters ON healthcare.encounters.id = healthcare.followup_records.encounter_id"

        private const val INSERT_PLAN_SQL =
            "INSERT INTO healthcare.followup_plans " +
                "(id, patient_id, encounter_id, followup_type, planned_date, planned_way, assignee, " +
                "status, created_at, updated_at, remark, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        private const val INSERT_RECORD_SQL =
            "INSERT INTO healthcare.followup_records " +
                "(id, patient_id, encounter_id, followup_type, followup_way, followup_date, result, " +
                "operator, created_at, updated_at, plan_id, contact_object, condition_summary, guidance, " +
                "next_followup_date, vitals, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14, " +
                "\$15, \$16, \$17)"

        /** 完成计划（条件更新保证幂等：只有 待随访 能转 已完成）。 */
        private const val COMPLETE_PLAN_SQL =
            "UPDATE healthcare.followup_plans SET status = \$2, completed_at = \$3, updated_at = \$4 " +
                "WHERE id = \$1 AND status = \$5"

        /** 取消计划（同样的条件更新守卫）。 */
        private const val CANCEL_PLAN_SQL =
            "UPDATE healthcare.followup_plans SET status = \$2, cancel_reason = \$3, updated_at = \$4 " +
                "WHERE id = \$1 AND status = \$5"

        /** 引用既有记录完成计划：只允许关联尚未挂计划的记录。 */
        private const val LINK_RECORD_SQL =
            "UPDATE healthcare.followup_records SET plan_id = \$2, updated_at = \$3 " +
                "WHERE id = \$1 AND plan_id IS NULL"

        private fun today(): LocalDate = LocalDate.now(businessZone)

        private fun planJson(row: Row): JsonObject {
            val plannedDate = row.getLocalDate("planned_date")
            val status = row.getString("status")
            val computedStatus =
                if (status == STATUS_PENDING && plannedDate != null && plannedDate.isBefore(today())) "已逾期" else status
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
    }

    // ========================================================================
    //  随访计划
    // ========================================================================

    fun createPlan(body: JsonObject, assignee: String): Future<JsonObject> {
        val patientId: String
        val encounterId: String
        val followupType: String
        val plannedDate: LocalDate
        val plannedWay: String
        try {
            rejectForbiddenKeys(body, planWriteKeys, "followup plan")
            patientId = requiredText(body, "patient_id")
            encounterId = requiredText(body, "encounter_id")
            followupType = validValue(requiredText(body, "followup_type"), followupTypes, "followup_type")
            plannedDate = localDate(requiredText(body, "planned_date"), "planned_date")
            plannedWay = body.getString("planned_way")?.trim()?.takeIf(String::isNotBlank)
                ?.let { validValue(it, followupWays, "planned_way") }
                ?: "电话"
            body.getString("remark")?.let {
                if (it.length > 1000) throw IllegalArgumentException("remark must not exceed 1000 characters")
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getPatient(connection, patientId).compose { patient ->
                if (patient.getString("status") == "DECEASED") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("cannot create followup plan for a deceased patient"),
                    )
                }
                validateEncounterOwnership(connection, patientId, encounterId).compose {
                    val admitDate = patientAdmitDate(it)
                    if (admitDate != null && plannedDate.isBefore(admitDate)) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("planned_date must not be earlier than the admission start date"),
                        )
                    }
                    execute(
                        connection,
                        INSERT_PLAN_SQL,
                        planInsertArgs(
                            body, id, patientId, encounterId, followupType, plannedDate, plannedWay, assignee, now,
                        ),
                    ).map { planCreatedJson(id, body, patient.getString("name"), plannedWay, assignee, now) }
                }
            }
        }
    }

    fun getPlan(id: String): Future<JsonObject> = getPlanVia(pool, id)

    /**
     * 体征异常转诊专用：在调用方提供的事务内创建随访计划
     * （followup_type=慢病随访、planned_way=门诊、assignee=认证主体），
     * 计划 metadata 记录 {"vital_sign_record_id": ..., "source": "体征异常告警"} 供追溯。
     * 校验与 createPlan 一致：非 DECEASED、encounter 归属 ELDERLY_CARE、
     * planned_date 不早于入住开始日。
     */
    fun createReferralPlan(
        client: SqlClient,
        patientId: String,
        encounterId: String,
        plannedDate: LocalDate,
        assignee: String,
        remark: String?,
        vitalSignRecordId: String,
        now: OffsetDateTime,
    ): Future<JsonObject> {
        val body = JsonObject()
            .put("patient_id", patientId)
            .put("encounter_id", encounterId)
            .put("followup_type", "慢病随访")
            .put("planned_date", plannedDate.toString())
            .put("planned_way", "门诊")
            .put(
                "metadata",
                JsonObject()
                    .put("vital_sign_record_id", vitalSignRecordId)
                    .put("source", "体征异常告警"),
            )
        remark?.let { body.put("remark", it) }
        val id = Ulid.generate()
        return getPatient(client, patientId).compose { patient ->
            if (patient.getString("status") == "DECEASED") {
                return@compose Future.failedFuture(
                    IllegalArgumentException("cannot create followup plan for a deceased patient"),
                )
            }
            loadReferralEncounter(client, patientId, encounterId).compose { encounter ->
                val admitDate = patientAdmitDate(encounter)
                if (admitDate != null && plannedDate.isBefore(admitDate)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("planned_date must not be earlier than the admission start date"),
                    )
                }
                execute(
                    client,
                    INSERT_PLAN_SQL,
                    planInsertArgs(body, id, patientId, encounterId, "慢病随访", plannedDate, "门诊", assignee, now),
                ).compose { getPlanVia(client, id) }
            }
        }
    }

    private fun getPlanVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, "$PLAN_BASE_SQL WHERE healthcare.followup_plans.id = \$1", Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(planJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("followup plan not found: $id"))
        }

    fun listPlans(
        status: String?,
        followupType: String?,
        patientId: String?,
        dateFrom: String?,
        dateTo: String?,
        overdue: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val filters = mutableListOf<String>()
        try {
            status?.takeIf(String::isNotBlank)?.let {
                if (it == "已逾期") {
                    throw IllegalArgumentException("status 已逾期 is computed, use overdue=true")
                }
                filters += "healthcare.followup_plans.status = ${bind(validValue(it, planStatuses, "status"))}"
            }
            followupType?.takeIf(String::isNotBlank)?.let { filters += "healthcare.followup_plans.followup_type = ${bind(it)}" }
            patientId?.takeIf(String::isNotBlank)?.let { filters += "healthcare.followup_plans.patient_id = ${bind(it)}" }
            dateFrom?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.followup_plans.planned_date >= " +
                    "CAST(${bind(localDate(it, "date_from"))} AS DATE)"
            }
            dateTo?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.followup_plans.planned_date <= " +
                    "CAST(${bind(localDate(it, "date_to"))} AS DATE)"
            }
            if (overdue == "true") {
                val statusPlaceholder = bind(STATUS_PENDING)
                val datePlaceholder = bind(today())
                filters += "healthcare.followup_plans.status = $statusPlaceholder " +
                    "AND healthcare.followup_plans.planned_date < CAST($datePlaceholder AS DATE)"
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val where = if (filters.isEmpty()) "" else " WHERE ${filters.joinToString(" AND ")}"
        val countSql = "SELECT count(*) AS total FROM ($PLAN_BASE_SQL$where) plans"
        val dataSql = "$PLAN_BASE_SQL$where ORDER BY healthcare.followup_plans.planned_date DESC, healthcare.followup_plans.created_at DESC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::planJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 顶部概览计数：今日待随访 / 已逾期 / 本月已完成 */
    fun getPlanStats(): Future<JsonObject> {
        val todayDate = today()
        val monthStart = todayDate.withDayOfMonth(1).atStartOfDay(businessZone).toOffsetDateTime()
        val nextMonthStart = todayDate.withDayOfMonth(1).plusMonths(1).atStartOfDay(businessZone).toOffsetDateTime()

        fun count(sql: String, args: Tuple): Future<Long> =
            execute(pool, sql, args).map { rows -> rows.iterator().next().getLong("total") ?: 0L }

        val todayPending = count(
            "SELECT count(*) AS total FROM healthcare.followup_plans WHERE status = \$1 AND planned_date = \$2",
            Tuple.of(STATUS_PENDING, todayDate),
        )
        val overdue = count(
            "SELECT count(*) AS total FROM healthcare.followup_plans WHERE status = \$1 AND planned_date < \$2",
            Tuple.of(STATUS_PENDING, todayDate),
        )
        val monthCompleted = count(
            "SELECT count(*) AS total FROM healthcare.followup_plans " +
                "WHERE status = \$1 AND completed_at >= \$2 AND completed_at < \$3",
            Tuple.of(STATUS_COMPLETED, monthStart, nextMonthStart),
        )
        return todayPending.compose { tp ->
            overdue.compose { od ->
                monthCompleted.map { mc ->
                    JsonObject()
                        .put("today_pending", tp)
                        .put("overdue", od)
                        .put("month_completed", mc)
                }
            }
        }
    }

    /**
     * 状态流转：待随访 → 已完成 | 已取消。
     *  - 已完成：必须同时提交内联随访记录（record）或引用既有记录（record_id）；
     *    与计划同事务完成，并发下只有一次成功（条件更新保证幂等）。
     *  - 已取消：必须填写 cancel_reason。
     */
    fun updatePlanStatus(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val status: String
        val cancelReason: String?
        val recordId: String?
        val inlineRecord: JsonObject?
        try {
            rejectForbiddenKeys(body, statusWriteKeys, "status update")
            status = validValue(requiredText(body, "status"), setOf("已完成", "已取消"), "status")
            cancelReason = body.getString("cancel_reason")?.trim()?.takeIf(String::isNotBlank)
            recordId = body.getString("record_id")?.trim()?.takeIf(String::isNotBlank)
            inlineRecord = body.getJsonObject("record")
            inlineRecord?.let { record ->
                val extra = record.fieldNames().filter { it !in recordWriteKeys - setOf("plan_id", "patient_id", "encounter_id") }.sorted()
                if (extra.isNotEmpty()) {
                    throw IllegalArgumentException("unsupported inline record keys: ${extra.joinToString(", ")}")
                }
            }
            when (status) {
                "已取消" -> {
                    if (recordId != null || inlineRecord != null) {
                        throw IllegalArgumentException("record/record_id are only allowed when completing a plan")
                    }
                    if (cancelReason == null) {
                        throw IllegalArgumentException("cancel_reason is required to cancel a plan")
                    }
                    if (cancelReason.length > 500) {
                        throw IllegalArgumentException("cancel_reason must not exceed 500 characters")
                    }
                }
                "已完成" -> {
                    if (recordId != null && inlineRecord != null) {
                        throw IllegalArgumentException("record and record_id are mutually exclusive")
                    }
                    if (recordId == null && inlineRecord == null) {
                        throw IllegalArgumentException("completing a plan requires a record or record_id")
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getPlanRow(connection, id).compose { plan ->
                when (status) {
                    "已取消" -> execute(
                        connection,
                        CANCEL_PLAN_SQL,
                        Tuple.of(id, STATUS_CANCELLED, requireNotNull(cancelReason), now, STATUS_PENDING),
                    ).compose { rows ->
                        if (rows.rowCount() == 1) getPlanVia(connection, id)
                        else Future.failedFuture(ConflictException("followup plan is not 待随访, cannot be cancelled"))
                    }
                    else -> completePlanWithRecord(connection, plan, recordId, inlineRecord, operator, now)
                }
            }
        }
    }

    // ========================================================================
    //  随访记录
    // ========================================================================

    fun createRecord(body: JsonObject, operator: String): Future<JsonObject> {
        val validated: RecordFields
        try {
            rejectForbiddenKeys(body, recordWriteKeys, "followup record")
            validated = validateRecordFields(body, planId = body.getString("plan_id")?.trim()?.takeIf(String::isNotBlank))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val planId = validated.planId
        if (planId == null) {
            // 无计划的临时随访
            return pool.withTransaction<JsonObject> { connection ->
                validatePatientEncounter(connection, validated.patientId, validated.encounterId).compose {
                    execute(connection, INSERT_RECORD_SQL, recordInsertArgs(body, id, validated, operator, now))
                        .map { recordCreatedJson(id, body, validated, operator, now) }
                }
            }
        }
        // 带计划：单事务内记录落库并将计划置为已完成（回填实际随访时间）
        return pool.withTransaction<JsonObject> { connection ->
            getPlanRow(connection, planId).compose { plan ->
                val planPatientId = plan.getString("patient_id")
                val planEncounterId = plan.getString("encounter_id")
                if (planPatientId != validated.patientId || planEncounterId != validated.encounterId) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("record patient/encounter must match the followup plan"),
                    )
                }
                execute(connection, INSERT_RECORD_SQL, recordInsertArgs(body, id, validated, operator, now)).compose {
                    execute(
                        connection,
                        COMPLETE_PLAN_SQL,
                        Tuple.of(planId, STATUS_COMPLETED, validated.followupDate, now, STATUS_PENDING),
                    )
                }.compose { rows ->
                    if (rows.rowCount() == 1) {
                        // 慢病随访完成联动：滚动生成下一轮计划（同事务，失败回滚）
                        chronicDiseaseService?.generateNextPlanAfterCompletion(
                            connection, plan, validated.nextFollowupDate, operator, now,
                        ) ?: Future.succeededFuture(false)
                    } else {
                        Future.failedFuture(ConflictException("followup plan is not 待随访, already completed or cancelled"))
                    }
                }.compose {
                    getRecordVia(connection, id)
                }
            }
        }
    }

    fun getRecord(id: String): Future<JsonObject> = getRecordVia(pool, id)

    private fun getRecordVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, "$RECORD_BASE_SQL WHERE healthcare.followup_records.id = \$1", Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(recordJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("followup record not found: $id"))
        }

    fun listRecords(
        patientId: String?,
        encounterId: String?,
        followupType: String?,
        result: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val filters = mutableListOf<String>()
        try {
            patientId?.takeIf(String::isNotBlank)?.let { filters += "healthcare.followup_records.patient_id = ${bind(it)}" }
            encounterId?.takeIf(String::isNotBlank)?.let { filters += "healthcare.followup_records.encounter_id = ${bind(it)}" }
            followupType?.takeIf(String::isNotBlank)?.let { filters += "healthcare.followup_records.followup_type = ${bind(it)}" }
            result?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.followup_records.result = ${bind(validValue(it, followupResults, "result"))}"
            }
            dateFrom?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.followup_records.followup_date >= ${bind(offsetDateTime(it, "date_from"))}"
            }
            dateTo?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.followup_records.followup_date <= ${bind(offsetDateTime(it, "date_to"))}"
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val where = if (filters.isEmpty()) "" else " WHERE ${filters.joinToString(" AND ")}"
        val countSql = "SELECT count(*) AS total FROM ($RECORD_BASE_SQL$where) records"
        val dataSql = "$RECORD_BASE_SQL$where ORDER BY healthcare.followup_records.followup_date DESC, healthcare.followup_records.created_at DESC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 老人随访历史时间线：计划 + 记录，各按时间倒序（详情页用） */
    fun listPatientFollowups(patientId: String): Future<JsonObject> =
        execute(
            pool,
            "SELECT 1 FROM healthcare.patients WHERE healthcare.patients.id = \$1",
            Tuple.of(patientId),
        ).compose { rows ->
            if (rows.size() == 0) {
                Future.failedFuture(HealthcareNotFoundException("patient not found: $patientId"))
            } else {
                val plansSql = "$PLAN_BASE_SQL WHERE healthcare.followup_plans.patient_id = \$1 " +
                    "ORDER BY healthcare.followup_plans.planned_date DESC, healthcare.followup_plans.created_at DESC"
                val recordsSql = "$RECORD_BASE_SQL WHERE healthcare.followup_records.patient_id = \$1 " +
                    "ORDER BY healthcare.followup_records.followup_date DESC, healthcare.followup_records.created_at DESC"
                execute(pool, plansSql, Tuple.of(patientId)).compose { planRows ->
                    execute(pool, recordsSql, Tuple.of(patientId)).map { recordRows ->
                        JsonObject()
                            .put("plans", JsonArray(planRows.map(::planJson)))
                            .put("records", JsonArray(recordRows.map(::recordJson)))
                    }
                }
            }
        }

    // ========================================================================
    //  写入语句参数
    // ========================================================================

    private fun planInsertArgs(
        body: JsonObject,
        id: String,
        patientId: String,
        encounterId: String,
        followupType: String,
        plannedDate: LocalDate,
        plannedWay: String,
        assignee: String,
        now: OffsetDateTime,
    ): Tuple = Tuple.of(
        id,
        patientId,
        encounterId,
        followupType,
        plannedDate,
        plannedWay,
        assignee,
        STATUS_PENDING,
        now,
        now,
        body.getString("remark"),
        jsonObject(body, "metadata"),
    )

    private fun recordInsertArgs(
        body: JsonObject,
        id: String,
        fields: RecordFields,
        operator: String,
        now: OffsetDateTime,
    ): Tuple = Tuple.of(
        id,
        fields.patientId,
        fields.encounterId,
        fields.followupType,
        fields.followupWay,
        fields.followupDate,
        fields.result,
        operator,
        now,
        now,
        fields.planId,
        fields.contactObject,
        fields.conditionSummary,
        fields.guidance,
        fields.nextFollowupDate,
        jsonObject(body, "vitals"),
        jsonObject(body, "metadata"),
    )

    /** 已完成：内联随访记录或引用既有记录，与计划同事务完成 */
    private fun completePlanWithRecord(
        connection: SqlClient,
        plan: Row,
        recordId: String?,
        inlineRecord: JsonObject?,
        operator: String,
        now: OffsetDateTime,
    ): Future<JsonObject> {
        val planId = plan.getString("id")
        val planPatientId = plan.getString("patient_id")
        val planEncounterId = plan.getString("encounter_id")
        if (recordId != null) {
            return getRecordRow(connection, recordId).compose { record ->
                val recordPatientId = record.getString("patient_id")
                val recordEncounterId = record.getString("encounter_id")
                if (recordPatientId != planPatientId || recordEncounterId != planEncounterId) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("record does not belong to the same patient/encounter as the plan"),
                    )
                }
                val recordPlanId = record.getString("plan_id")
                if (recordPlanId != null && recordPlanId != planId) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("record already belongs to another followup plan"),
                    )
                }
                execute(connection, LINK_RECORD_SQL, Tuple.of(recordId, planId, now)).compose { linkRows ->
                    if (linkRows.rowCount() != 1) {
                        return@compose Future.failedFuture(
                            ConflictException("record already belongs to another followup plan"),
                        )
                    }
                    execute(
                        connection,
                        COMPLETE_PLAN_SQL,
                        Tuple.of(
                            planId,
                            STATUS_COMPLETED,
                            record.getOffsetDateTime("followup_date"),
                            now,
                            STATUS_PENDING,
                        ),
                    )
                        .compose { rows ->
                            if (rows.rowCount() == 1) {
                                // 慢病随访完成联动：滚动生成下一轮计划（同事务，失败回滚）
                                chronicDiseaseService?.generateNextPlanAfterCompletion(
                                    connection, plan, record.getLocalDate("next_followup_date"), operator, now,
                                ) ?: Future.succeededFuture(false)
                            } else {
                                Future.failedFuture(ConflictException("followup plan is not 待随访, already completed or cancelled"))
                            }
                        }
                        .compose { getPlanVia(connection, planId) }
                }
            }
        }
        // 内联记录：patient/encounter/followup_type 取自计划
        val recordIdNew = Ulid.generate()
        val mergedBody = JsonObject()
            .put("followup_type", plan.getString("followup_type"))
            .put("followup_way", "电话")
            .put("followup_date", now.toString())
        inlineRecord?.forEach { entry -> mergedBody.put(entry.key, entry.value) }
        val validated: RecordFields
        try {
            validated = validateRecordFields(
                mergedBody,
                planId = planId,
                patientId = planPatientId,
                encounterId = planEncounterId,
            )
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return execute(connection, INSERT_RECORD_SQL, recordInsertArgs(mergedBody, recordIdNew, validated, operator, now))
            .compose {
                execute(
                    connection,
                    COMPLETE_PLAN_SQL,
                    Tuple.of(planId, STATUS_COMPLETED, validated.followupDate, now, STATUS_PENDING),
                )
            }
            .compose { rows ->
                if (rows.rowCount() == 1) {
                    // 慢病随访完成联动：滚动生成下一轮计划（同事务，失败回滚）
                    chronicDiseaseService?.generateNextPlanAfterCompletion(
                        connection, plan, validated.nextFollowupDate, operator, now,
                    ) ?: Future.succeededFuture(false)
                } else {
                    Future.failedFuture(ConflictException("followup plan is not 待随访, already completed or cancelled"))
                }
            }
            .compose { getPlanVia(connection, planId) }
    }

    // ========================================================================
    //  校验与行读取
    // ========================================================================

    private data class RecordFields(
        val planId: String?,
        val patientId: String,
        val encounterId: String,
        val followupType: String,
        val followupWay: String,
        val followupDate: OffsetDateTime,
        val result: String,
        val contactObject: String?,
        val conditionSummary: String?,
        val guidance: String?,
        val nextFollowupDate: LocalDate?,
    )

    private fun validateRecordFields(
        body: JsonObject,
        planId: String?,
        patientId: String = requiredText(body, "patient_id"),
        encounterId: String = requiredText(body, "encounter_id"),
    ): RecordFields {
        // plan_id 属于记录写白名单（新增记录时随 body 提交，服务端据此关联计划）
        rejectForbiddenKeys(body, recordWriteKeys, "followup record")
        val followupType = validValue(requiredText(body, "followup_type"), followupTypes, "followup_type")
        val followupWay = body.getString("followup_way")?.trim()?.takeIf(String::isNotBlank)
            ?.let { validValue(it, followupWays, "followup_way") }
            ?: "电话"
        val result = validValue(requiredText(body, "result"), followupResults, "result")
        val followupDate = body.getString("followup_date")?.let { offsetDateTime(it, "followup_date") }
            ?: OffsetDateTime.now()
        if (followupDate.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("followup_date must not be in the future")
        }
        val contactObject = body.getString("contact_object")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 100) throw IllegalArgumentException("contact_object must not exceed 100 characters")
        }
        val conditionSummary = body.getString("condition_summary")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 2000) throw IllegalArgumentException("condition_summary must not exceed 2000 characters")
        }
        val guidance = body.getString("guidance")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 2000) throw IllegalArgumentException("guidance must not exceed 2000 characters")
        }
        val nextFollowupDate = body.getString("next_followup_date")?.let { localDate(it, "next_followup_date") }
        jsonObject(body, "vitals") // 校验 vitals 必须是 JSON 对象
        return RecordFields(
            planId = planId,
            patientId = patientId,
            encounterId = encounterId,
            followupType = followupType,
            followupWay = followupWay,
            followupDate = followupDate,
            result = result,
            contactObject = contactObject,
            conditionSummary = conditionSummary,
            guidance = guidance,
            nextFollowupDate = nextFollowupDate,
        )
    }

    /** 校验 encounter 归属该 patient 且为 ELDERLY_CARE，返回 encounter 行 */
    private fun validateEncounterOwnership(
        client: SqlClient,
        patientId: String,
        encounterId: String,
    ): Future<Row> =
        getEncounterRow(client, encounterId).compose { encounter ->
            if (encounter.getString("patient_id") != patientId) {
                return@compose Future.failedFuture(
                    IllegalArgumentException("encounter does not belong to the specified patient"),
                )
            }
            if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(
                    IllegalArgumentException("encounter must be of type ELDERLY_CARE"),
                )
            }
            Future.succeededFuture(encounter)
        }

    /**
     * 转诊专用入住校验：入住记录缺失、不属于该长者或不是养老入住时统一返回中文业务提示
     * （[REFERRAL_WITHOUT_ADMISSION_MESSAGE]），不把后台英文校验文案透出到界面。
     */
    private fun loadReferralEncounter(client: SqlClient, patientId: String, encounterId: String): Future<Row> =
        getEncounterRow(client, encounterId)
            .recover { Future.failedFuture<Row>(ConflictException(REFERRAL_WITHOUT_ADMISSION_MESSAGE)) }
            .compose { encounter ->
                val isOwnedElderlyCare = encounter.getString("patient_id") == patientId &&
                    encounter.getString("encounter_type") == "ELDERLY_CARE"
                if (isOwnedElderlyCare) {
                    Future.succeededFuture(encounter)
                } else {
                    Future.failedFuture(ConflictException(REFERRAL_WITHOUT_ADMISSION_MESSAGE))
                }
            }

    private fun validatePatientEncounter(client: SqlClient, patientId: String, encounterId: String): Future<Row> =
        getPatient(client, patientId).compose {
            validateEncounterOwnership(client, patientId, encounterId)
        }

    private fun getPatient(client: SqlClient, id: String): Future<JsonObject> =
        execute(
            client,
            "SELECT id, name, status FROM healthcare.patients WHERE id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("name", row.getString("name"))
                        .put("status", row.getString("status")),
                )
            } ?: Future.failedFuture(HealthcareNotFoundException("patient not found: $id"))
        }

    private fun getEncounterRow(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            "SELECT id, patient_id, encounter_type, status, admit_date " +
                "FROM healthcare.encounters WHERE healthcare.encounters.id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    /** 计划行（不含患者名/住院号）：完成/取消/新增记录前读取计划归属。 */
    private fun getPlanRow(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            "SELECT $PLAN_COLUMNS FROM healthcare.followup_plans WHERE healthcare.followup_plans.id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("followup plan not found: $id"))
        }

    /** 记录行（不含患者名/住院号）：引用既有记录完成计划前读取归属。 */
    private fun getRecordRow(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            "SELECT $RECORD_COLUMNS FROM healthcare.followup_records WHERE healthcare.followup_records.id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("followup record not found: $id"))
        }

    private fun patientAdmitDate(encounter: Row): LocalDate? =
        encounter.getOffsetDateTime("admit_date")?.atZoneSameInstant(businessZone)?.toLocalDate()

    private fun planCreatedJson(
        id: String,
        body: JsonObject,
        patientName: String?,
        plannedWay: String,
        assignee: String,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("patient_id", body.getString("patient_id"))
            .put("patient_name", patientName)
            .put("encounter_id", body.getString("encounter_id"))
            .put("encounter_no", null as String?)
            .put("followup_type", body.getString("followup_type"))
            .put("planned_date", body.getString("planned_date"))
            .put("planned_way", plannedWay)
            .put("assignee", assignee)
            .put("status", STATUS_PENDING)
            .put("completed_at", null as String?)
            .put("cancel_reason", null as String?)
            .put("remark", body.getString("remark"))
            .put("metadata", body.getJsonObject("metadata"))
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun recordCreatedJson(
        id: String,
        body: JsonObject,
        fields: RecordFields,
        operator: String,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("plan_id", fields.planId)
            .put("patient_id", fields.patientId)
            .put("patient_name", null as String?)
            .put("encounter_id", fields.encounterId)
            .put("encounter_no", null as String?)
            .put("followup_type", fields.followupType)
            .put("followup_way", fields.followupWay)
            .put("followup_date", fields.followupDate.toString())
            .put("contact_object", fields.contactObject)
            .put("condition_summary", fields.conditionSummary)
            .put("vitals", body.getJsonObject("vitals"))
            .put("guidance", fields.guidance)
            .put("result", fields.result)
            .put("next_followup_date", fields.nextFollowupDate?.toString())
            .put("operator", operator)
            .put("metadata", body.getJsonObject("metadata"))
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    // ========================================================================
    //  通用辅助
    // ========================================================================

    private fun execute(client: SqlClient, sql: String, args: Tuple): Future<RowSet<Row>> =
        client.preparedQuery(sql).execute(args)

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

    private fun offsetDateTime(value: String, field: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 offset date-time")
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
