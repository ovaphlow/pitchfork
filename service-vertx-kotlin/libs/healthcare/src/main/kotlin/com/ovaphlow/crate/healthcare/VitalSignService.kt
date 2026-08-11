package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.healthcare.tables.VitalSignRecords.VITAL_SIGN_RECORDS
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 生命体征记录服务（养老方向）。
 *
 * 业务规则（服务端强制）：
 *  1. 体征类型为英文枚举（TEMPERATURE/PULSE/RESPIRATION/SYSTOLIC_BP/DIASTOLIC_BP/
 *     SPO2/BLOOD_GLUCOSE/WEIGHT），血压按收缩/舒张两条独立记录建模。
 *  2. patient_id 必填且必须存在；encounter_id 可空，提供时必须属于该老人。
 *  3. value 必须为合法正数；SPO2 限制 0–100；measured_at 默认当前时间且不得晚于当前。
 *  4. abnormal 由服务端按类型内置参考范围计算（MVP 常量），允许 metadata.thresholds
 *     按类型覆盖 {min,max}；WEIGHT 不判异常。客户端不得提交 abnormal。
 *  5. recorded_by 一律取认证上下文（userId），写接口按白名单校验字段，
 *     拒绝 recorded_by/abnormal/created_at/updated_at/id/deleted_at。
 *  6. 删除为软删除（deleted_at），默认查询均排除已作废记录（数据可追溯）。
 *  7. 异常处理状态机（abnormal=true 的记录）：待复核 ─复核→ 已确认 ─转诊→ 已转诊；
 *     待复核 ─复核→ 已误报（终态）。复核可重复执行（覆盖式更新，取最新复核人/时间）；
 *     转诊前置 review_status=已确认，事务内创建随访计划（慢病随访/门诊），
 *     计划 metadata 关联体征记录 id；已转诊/已误报为终态不可再复核。
 *  8. PATCH 修正导致 abnormal 翻转时，review_status 重置为待复核并清空复核/转诊结论。
 */
class VitalSignService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
    private val followupService: FollowupService = FollowupService(pool, ctx),
) {
    companion object {
        private val businessZone = ZoneId.of("Asia/Shanghai")

        val types = setOf(
            "TEMPERATURE", "PULSE", "RESPIRATION", "SYSTOLIC_BP",
            "DIASTOLIC_BP", "SPO2", "BLOOD_GLUCOSE", "WEIGHT",
        )

        /** 各类型标准单位；客户端省略 unit 时按类型默认 */
        private val defaultUnits = mapOf(
            "TEMPERATURE" to "℃",
            "PULSE" to "次/分",
            "RESPIRATION" to "次/分",
            "SYSTOLIC_BP" to "mmHg",
            "DIASTOLIC_BP" to "mmHg",
            "SPO2" to "%",
            "BLOOD_GLUCOSE" to "mmol/L",
            "WEIGHT" to "kg",
        )

        /** 内置参考范围（含边界）：value < min 或 value > max 记为异常；WEIGHT 不判异常 */
        private val referenceRanges = mapOf(
            "TEMPERATURE" to (36.0 to 37.3),
            "PULSE" to (60.0 to 100.0),
            "RESPIRATION" to (12.0 to 20.0),
            "SYSTOLIC_BP" to (90.0 to 140.0),
            "DIASTOLIC_BP" to (60.0 to 90.0),
            "SPO2" to (95.0 to 100.0),
            "BLOOD_GLUCOSE" to (3.9 to 6.1),
        )

        /** 复核状态枚举（中文白名单）：待复核/已确认/已误报/已转诊 */
        val reviewStatuses = setOf("待复核", "已确认", "已误报", "已转诊")

        /** 复核结论枚举（中文白名单） */
        val reviewResults = setOf("确认异常", "误报")

        /** 创建白名单：recorded_by/abnormal/created_at/updated_at/id/deleted_at 及复核字段由服务端管控 */
        private val createKeys = setOf(
            "patient_id", "encounter_id", "type", "value", "unit",
            "measured_at", "note", "metadata",
        )

        /** 修正白名单：只允许改值/单位/时间/备注/元数据 */
        private val patchKeys = setOf("value", "unit", "measured_at", "note", "metadata")

        /** 复核写白名单：reviewed_by/reviewed_at/review_status 由服务端管控 */
        private val reviewKeys = setOf("result", "note")

        /** 转诊写白名单：planned_date 可选，默认当天；remark 可选 */
        private val referKeys = setOf("planned_date", "remark")

        private fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("encounter_id", row.getString("encounter_id"))
                .put("encounter_no", row.getString("encounter_no"))
                .put("type", row.getString("type"))
                .put("value", row.getBigDecimal("value"))
                .put("unit", row.getString("unit"))
                .put("measured_at", row.getOffsetDateTime("measured_at")?.toString())
                .put("recorded_by", row.getString("recorded_by"))
                .put("abnormal", row.getBoolean("abnormal"))
                .put("note", row.getString("note"))
                .put("metadata", row.getValue("metadata"))
                .put("review_status", row.getString("review_status") ?: "待复核")
                .put("review_result", row.getString("review_result"))
                .put("review_note", row.getString("review_note"))
                .put("reviewed_by", row.getString("reviewed_by"))
                .put("reviewed_at", row.getOffsetDateTime("reviewed_at")?.toString())
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  创建（支持单次提交多条，血压收缩/舒张一次两条）
    // ========================================================================

    fun createVitalSigns(body: JsonArray, recordedBy: String): Future<JsonObject> {
        val records: List<RecordFields> = try {
            if (body.isEmpty) throw IllegalArgumentException("records must not be empty")
            body.map { entry ->
                val item = entry as? JsonObject
                    ?: throw IllegalArgumentException("each record must be a JSON object")
                validateRecord(item)
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val ids = records.map { Ulid.generate() }
        return pool.withTransaction<JsonObject> { connection ->
            val uniquePatientIds = records.map { it.patientId }.distinct()
            Future.all(uniquePatientIds.map { patientId ->
                getPatientRow(connection, patientId)
            }).compose {
                // 校验 encounter 归属（提供时）
                val checks = records.filter { it.encounterId != null }.map { record ->
                    validateEncounterOwnership(connection, record.patientId, record.encounterId!!)
                }
                if (checks.isEmpty()) {
                    Future.succeededFuture<Unit>(Unit)
                } else {
                    Future.all(checks).map { Unit }
                }
            }.compose {
                val inserts = records.mapIndexed { index, record ->
                    execute(
                        connection,
                        insertQuery(record, ids[index], recordedBy, now),
                    )
                }
                Future.all(inserts).map {
                    val created = records.mapIndexed { index, record ->
                        createdJson(ids[index], record, recordedBy, now)
                    }
                    JsonObject().put("records", JsonArray(created))
                }
            }
        }
    }

    fun getVitalSign(id: String): Future<JsonObject> = getVitalSignVia(pool, id)

    private fun getVitalSignVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, detailQuery(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(recordJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("vital sign record not found: $id"))
        }

    fun listVitalSigns(
        patientId: String?,
        type: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = try {
            mutableListOf<Condition>(
                VITAL_SIGN_RECORDS.DELETED_AT.isNull,
            ).also { conditions ->
                val patient = patientId?.trim()?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("patient_id is required")
                conditions += VITAL_SIGN_RECORDS.PATIENT_ID.eq(patient)
                type?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.TYPE.eq(validType(it))
                }
                dateFrom?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.MEASURED_AT.ge(offsetDateTime(it, "date_from"))
                }
                dateTo?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.MEASURED_AT.le(offsetDateTime(it, "date_to"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(VITAL_SIGN_RECORDS).where(conditions)
        val dataQuery = baseQuery()
            .where(conditions)
            .orderBy(VITAL_SIGN_RECORDS.MEASURED_AT.desc(), VITAL_SIGN_RECORDS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 异常告警列表：跨老人查询 abnormal=true 的记录（deleted_at 非空排除）。
     * patient_id 为可选过滤（与既有强制 patient_id 的列表契约区分）；
     * review_status 非法值时返回 400；按 measured_at DESC 排序。
     */
    fun listAbnormalSigns(
        patientId: String?,
        type: String?,
        reviewStatus: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = try {
            mutableListOf<Condition>(
                VITAL_SIGN_RECORDS.DELETED_AT.isNull,
                VITAL_SIGN_RECORDS.ABNORMAL.eq(true),
            ).also { conditions ->
                patientId?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.PATIENT_ID.eq(it)
                }
                type?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.TYPE.eq(validType(it))
                }
                reviewStatus?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.REVIEW_STATUS.eq(validReviewStatus(it))
                }
                dateFrom?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.MEASURED_AT.ge(offsetDateTime(it, "date_from"))
                }
                dateTo?.takeIf(String::isNotBlank)?.let {
                    conditions += VITAL_SIGN_RECORDS.MEASURED_AT.le(offsetDateTime(it, "date_to"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(VITAL_SIGN_RECORDS).where(conditions)
        val dataQuery = baseQuery()
            .where(conditions)
            .orderBy(VITAL_SIGN_RECORDS.MEASURED_AT.desc(), VITAL_SIGN_RECORDS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 异常告警统计摘要：与列表共用同一服务端时间基准（Asia/Shanghai 当天）。
     * 返回 {pending_total, today_total, referred_total, by_type, by_status}。
     */
    fun getAbnormalSummary(): Future<JsonObject> {
        val todayStart = LocalDate.now(businessZone).atStartOfDay(businessZone).toOffsetDateTime()
        val tomorrowStart = todayStart.plusDays(1)
        val base = VITAL_SIGN_RECORDS.DELETED_AT.isNull.and(VITAL_SIGN_RECORDS.ABNORMAL.eq(true))

        fun count(condition: Condition): Future<Long> {
            val query = ctx.select(DSL.count().`as`("total")).from(VITAL_SIGN_RECORDS).where(condition)
            return execute(pool, query).map { rows -> rows.iterator().next().getLong("total") ?: 0L }
        }

        val pending = count(base.and(VITAL_SIGN_RECORDS.REVIEW_STATUS.eq("待复核")))
        val today = count(base.and(VITAL_SIGN_RECORDS.MEASURED_AT.ge(todayStart)).and(VITAL_SIGN_RECORDS.MEASURED_AT.lt(tomorrowStart)))
        val referred = count(base.and(VITAL_SIGN_RECORDS.REVIEW_STATUS.eq("已转诊")))

        val byTypeQuery = ctx.select(VITAL_SIGN_RECORDS.TYPE, DSL.count().`as`("count"))
            .from(VITAL_SIGN_RECORDS)
            .where(base)
            .groupBy(VITAL_SIGN_RECORDS.TYPE)
        val byStatusQuery = ctx.select(VITAL_SIGN_RECORDS.REVIEW_STATUS, DSL.count().`as`("count"))
            .from(VITAL_SIGN_RECORDS)
            .where(base)
            .groupBy(VITAL_SIGN_RECORDS.REVIEW_STATUS)

        return pending.compose { pendingTotal ->
            today.compose { todayTotal ->
                referred.compose { referredTotal ->
                    execute(pool, byTypeQuery).compose { typeRows ->
                        execute(pool, byStatusQuery).map { statusRows ->
                            JsonObject()
                                .put("pending_total", pendingTotal)
                                .put("today_total", todayTotal)
                                .put("referred_total", referredTotal)
                                .put(
                                    "by_type",
                                    JsonArray(
                                        typeRows.map { row ->
                                            JsonObject()
                                                .put("type", row.getString("type"))
                                                .put("count", row.getLong("count") ?: 0L)
                                        },
                                    ),
                                )
                                .put(
                                    "by_status",
                                    JsonArray(
                                        statusRows.map { row ->
                                            JsonObject()
                                                .put("status", row.getString("review_status"))
                                                .put("count", row.getLong("count") ?: 0L)
                                        },
                                    ),
                                )
                        }
                    }
                }
            }
        }
    }

    /**
     * 最新体征快照：每种类型最近一条（含 deleted_at 过滤），供首页/老人详情卡片展示。
     * 返回 {records: [...]}，按类型分组各一条。
     */
    fun getSnapshot(patientId: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection ->
            getPatientRow(connection, patientId).compose {
                val latest = ctx.select(VITAL_SIGN_RECORDS.fields().toList())
                    .distinctOn(VITAL_SIGN_RECORDS.TYPE)
                    .from(VITAL_SIGN_RECORDS)
                    .where(
                        VITAL_SIGN_RECORDS.PATIENT_ID.eq(patientId)
                            .and(VITAL_SIGN_RECORDS.DELETED_AT.isNull),
                    )
                    .orderBy(
                        VITAL_SIGN_RECORDS.TYPE.asc(),
                        VITAL_SIGN_RECORDS.MEASURED_AT.desc(),
                        VITAL_SIGN_RECORDS.CREATED_AT.desc(),
                    )
                    .asTable("latest")
                val query = ctx.select(
                    latest.fields().toList() +
                        listOf(
                            PATIENTS.NAME.`as`("patient_name"),
                            ENCOUNTERS.ENCOUNTER_NO.`as`("encounter_no"),
                        ),
                )
                    .from(latest)
                    .join(PATIENTS).on(latest.field(VITAL_SIGN_RECORDS.PATIENT_ID)!!.eq(PATIENTS.ID))
                    .leftJoin(ENCOUNTERS).on(latest.field(VITAL_SIGN_RECORDS.ENCOUNTER_ID)!!.eq(ENCOUNTERS.ID))
                    .orderBy(latest.field(VITAL_SIGN_RECORDS.TYPE)!!.asc())
                execute(connection, query).map { rows ->
                    JsonObject().put("records", JsonArray(rows.map(::recordJson)))
                }
            }
        }

    /**
     * 趋势序列：指定老人、指定体征类型在一段时间内的有序测量点（时间升序），供绘图。
     */
    fun getTrend(
        patientId: String,
        type: String?,
        dateFrom: String?,
        dateTo: String?,
    ): Future<JsonObject> {
        val validTypeValue: String = try {
            validType(type?.trim()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("type is required"))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            getPatientRow(connection, patientId).compose {
                val conditions = mutableListOf<Condition>(
                    VITAL_SIGN_RECORDS.PATIENT_ID.eq(patientId),
                    VITAL_SIGN_RECORDS.TYPE.eq(validTypeValue),
                    VITAL_SIGN_RECORDS.DELETED_AT.isNull,
                )
                try {
                    dateFrom?.takeIf(String::isNotBlank)?.let {
                        conditions += VITAL_SIGN_RECORDS.MEASURED_AT.ge(offsetDateTime(it, "date_from"))
                    }
                    dateTo?.takeIf(String::isNotBlank)?.let {
                        conditions += VITAL_SIGN_RECORDS.MEASURED_AT.le(offsetDateTime(it, "date_to"))
                    }
                } catch (error: IllegalArgumentException) {
                    return@compose Future.failedFuture(error)
                }
                val query = baseQuery()
                    .where(conditions)
                    .orderBy(VITAL_SIGN_RECORDS.MEASURED_AT.asc(), VITAL_SIGN_RECORDS.CREATED_AT.asc())
                execute(connection, query).map { rows ->
                    JsonObject().put("records", JsonArray(rows.map(::recordJson)))
                }
            }
        }
    }

    /**
     * 修正记录：value 必填（重新计算 abnormal）；unit/measured_at/note/metadata 省略时
     * 保留原值，note/metadata 显式传 null 时清空。已作废（deleted_at 非空）记录不可修正。
     */
    fun updateVitalSign(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        val value: BigDecimal
        val note: String?
        val metadata: JsonObject?
        val newUnit: String?
        val newMeasuredAt: OffsetDateTime?
        try {
            rejectForbiddenKeys(body, patchKeys, "vital sign update")
            value = numericValue(body, "value")
            note = body.getString("note")?.trim()?.takeIf(String::isNotBlank)?.also {
                if (it.length > 1000) throw IllegalArgumentException("note must not exceed 1000 characters")
            }
            metadata = jsonObject(body, "metadata")
            newUnit = body.getString("unit")?.trim()?.takeIf(String::isNotBlank)?.also {
                if (it.length > 20) throw IllegalArgumentException("unit must not exceed 20 characters")
            }
            newMeasuredAt = body.getString("measured_at")?.let { offsetDateTime(it, "measured_at") }?.also {
                if (it.isAfter(OffsetDateTime.now())) {
                    throw IllegalArgumentException("measured_at must not be in the future")
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getRecordRow(connection, id).compose { record ->
                val type = record.getString("type") ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("vital sign record not found: $id"),
                )
                sanityCheck(type, value)
                val unit = newUnit ?: record.getString("unit") ?: defaultUnits.getValue(type)
                val measuredAt = newMeasuredAt ?: record.getOffsetDateTime("measured_at") ?: now
                val abnormal = isAbnormal(type, value, metadata)
                val oldAbnormal = record.getBoolean("abnormal")
                execute(
                    connection,
                    ctx.update(VITAL_SIGN_RECORDS)
                        .set(VITAL_SIGN_RECORDS.VALUE, value)
                        .set(VITAL_SIGN_RECORDS.UNIT, unit)
                        .set(VITAL_SIGN_RECORDS.MEASURED_AT, measuredAt)
                        .set(VITAL_SIGN_RECORDS.ABNORMAL, abnormal)
                        .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
                        .apply {
                            if (note != null) set(VITAL_SIGN_RECORDS.NOTE, note) else setNull(VITAL_SIGN_RECORDS.NOTE)
                            if (metadata != null) set(VITAL_SIGN_RECORDS.METADATA, JSONB.valueOf(metadata.encode()))
                            else setNull(VITAL_SIGN_RECORDS.METADATA)
                            // abnormal 翻转时重置复核状态，防止陈旧结论污染闭环
                            if (abnormal != oldAbnormal) {
                                set(VITAL_SIGN_RECORDS.REVIEW_STATUS, "待复核")
                                setNull(VITAL_SIGN_RECORDS.REVIEW_RESULT)
                                setNull(VITAL_SIGN_RECORDS.REVIEW_NOTE)
                                setNull(VITAL_SIGN_RECORDS.REVIEWED_BY)
                                setNull(VITAL_SIGN_RECORDS.REVIEWED_AT)
                            }
                        }
                        .where(VITAL_SIGN_RECORDS.ID.eq(id).and(VITAL_SIGN_RECORDS.DELETED_AT.isNull)),
                ).compose { rows ->
                    if (rows.rowCount() == 1) getVitalSignVia(connection, id)
                    else Future.failedFuture(HealthcareNotFoundException("vital sign record not found: $id"))
                }
            }
        }
    }

    /**
     * 复核异常记录：待复核 → 已确认 | 已误报（覆盖式更新，复核人/时间取最新）。
     * 前置条件：abnormal=true；已误报/已转诊为终态，不可再复核（应走修正流程重置）。
     * 请求体 {result: 确认异常|误报, note?: ≤500 字}；复核结论不改变 abnormal 本身。
     */
    fun reviewVitalSign(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        val result: String
        val note: String?
        try {
            rejectForbiddenKeys(body, reviewKeys, "vital sign review")
            result = validValue(requiredText(body, "result"), reviewResults, "result")
            note = body.getString("note")?.trim()?.takeIf(String::isNotBlank)?.also {
                if (it.length > 500) throw IllegalArgumentException("note must not exceed 500 characters")
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getRecordRow(connection, id).compose { record ->
                if (record.getBoolean("abnormal") != true) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only abnormal records can be reviewed"),
                    )
                }
                val currentStatus = record.getString("review_status") ?: "待复核"
                if (currentStatus == "已误报" || currentStatus == "已转诊") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("review_status $currentStatus is terminal, correct the record instead"),
                    )
                }
                val reviewStatus = if (result == "确认异常") "已确认" else "已误报"
                execute(
                    connection,
                    ctx.update(VITAL_SIGN_RECORDS)
                        .set(VITAL_SIGN_RECORDS.REVIEW_STATUS, reviewStatus)
                        .set(VITAL_SIGN_RECORDS.REVIEW_RESULT, result)
                        .set(VITAL_SIGN_RECORDS.REVIEWED_BY, userId)
                        .set(VITAL_SIGN_RECORDS.REVIEWED_AT, now)
                        .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
                        .apply {
                            if (note != null) set(VITAL_SIGN_RECORDS.REVIEW_NOTE, note)
                            else setNull(VITAL_SIGN_RECORDS.REVIEW_NOTE)
                        }
                        .where(VITAL_SIGN_RECORDS.ID.eq(id).and(VITAL_SIGN_RECORDS.DELETED_AT.isNull)),
                ).compose { rows ->
                    if (rows.rowCount() == 1) getVitalSignVia(connection, id)
                    else Future.failedFuture(HealthcareNotFoundException("vital sign record not found: $id"))
                }
            }
        }
    }

    /**
     * 转诊：review_status=已确认 → 已转诊；单事务内创建随访计划
     * （followup_type=慢病随访、planned_way=门诊、assignee=认证主体），
     * 计划 metadata 记录 {"vital_sign_record_id": id, "source": "体征异常告警"}。
     * planned_date 默认当天（业务时区），提供时不得早于入住开始日；已转诊为终态。
     */
    fun referVitalSign(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        val plannedDate: LocalDate
        val remark: String?
        try {
            rejectForbiddenKeys(body, referKeys, "vital sign refer")
            plannedDate = body.getString("planned_date")?.let { localDate(it, "planned_date") }
                ?: LocalDate.now(businessZone)
            remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
                if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getRecordRow(connection, id).compose { record ->
                if (record.getString("review_status") != "已确认") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only records with review_status=已确认 can be referred"),
                    )
                }
                val patientId = record.getString("patient_id")
                val encounterId = record.getString("encounter_id")
                if (encounterId == null) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("vital sign record has no encounter, cannot create a followup plan"),
                    )
                }
                execute(
                    connection,
                    ctx.update(VITAL_SIGN_RECORDS)
                        .set(VITAL_SIGN_RECORDS.REVIEW_STATUS, "已转诊")
                        .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
                        .where(VITAL_SIGN_RECORDS.ID.eq(id).and(VITAL_SIGN_RECORDS.DELETED_AT.isNull)),
                ).compose { rows ->
                    if (rows.rowCount() != 1) {
                        return@compose Future.failedFuture(
                            HealthcareNotFoundException("vital sign record not found: $id"),
                        )
                    }
                    followupService.createReferralPlan(
                        client = connection,
                        patientId = patientId,
                        encounterId = encounterId,
                        plannedDate = plannedDate,
                        assignee = userId,
                        remark = remark,
                        vitalSignRecordId = id,
                        now = now,
                    )
                }.compose { plan ->
                    getVitalSignVia(connection, id).map { recordJson ->
                        JsonObject()
                            .put("record", recordJson)
                            .put("followup_plan", plan)
                    }
                }
            }
        }
    }

    /**
     * 删除（软删除）：置 deleted_at，默认查询不再返回；数据可追溯。
     * 返回 {id, deleted_at}。
     */
    fun deleteVitalSign(id: String, userId: String): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            execute(
                connection,
                ctx.update(VITAL_SIGN_RECORDS)
                    .set(VITAL_SIGN_RECORDS.DELETED_AT, now)
                    .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
                    .where(VITAL_SIGN_RECORDS.ID.eq(id).and(VITAL_SIGN_RECORDS.DELETED_AT.isNull)),
            ).compose { rows ->
                if (rows.rowCount() == 1) {
                    Future.succeededFuture(
                        JsonObject().put("id", id).put("deleted_at", now.toString()),
                    )
                } else {
                    Future.failedFuture(HealthcareNotFoundException("vital sign record not found: $id"))
                }
            }
        }
    }

    // ========================================================================
    //  校验
    // ========================================================================

    private data class RecordFields(
        val patientId: String,
        val encounterId: String?,
        val type: String,
        val value: BigDecimal,
        val unit: String,
        val measuredAt: OffsetDateTime,
        val note: String?,
        val metadata: JsonObject?,
    )

    private fun validateRecord(body: JsonObject): RecordFields {
        rejectForbiddenKeys(body, createKeys, "vital sign record")
        val patientId = requiredText(body, "patient_id")
        val encounterId = body.getString("encounter_id")?.trim()?.takeIf(String::isNotBlank)
        val type = validType(requiredText(body, "type"))
        val value = numericValue(body, "value")
        sanityCheck(type, value)
        val unit = unitValue(body, value)
        val measuredAt = measuredTime(body)
        val note = body.getString("note")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 1000) throw IllegalArgumentException("note must not exceed 1000 characters")
        }
        val metadata = jsonObject(body, "metadata")
        return RecordFields(patientId, encounterId, type, value, unit, measuredAt, note, metadata)
    }

    /** 各类型的物理合理性校验：SPO2 限制 0–100 */
    private fun sanityCheck(type: String, value: BigDecimal) {
        if (type == "SPO2" && (value < BigDecimal.ZERO || value > BigDecimal("100"))) {
            throw IllegalArgumentException("SPO2 must be between 0 and 100")
        }
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun validType(value: String): String =
        value.takeIf { it in types }
            ?: throw IllegalArgumentException("invalid type, must be one of: $types")

    private fun validReviewStatus(value: String): String =
        value.takeIf { it in reviewStatuses }
            ?: throw IllegalArgumentException("invalid review_status, must be one of: $reviewStatuses")

    private fun validValue(value: String, allowed: Set<String>, label: String): String =
        value.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun localDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 date")
        }

    private fun numericValue(body: JsonObject, key: String): BigDecimal {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val value = (raw as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$key must be a number")
        if (!value.isFinite() || value <= 0) {
            throw IllegalArgumentException("$key must be a positive number")
        }
        val decimal = BigDecimal.valueOf(value)
        if (decimal.scale() > 2) {
            throw IllegalArgumentException("$key must have at most 2 decimal places")
        }
        return decimal
    }

    private fun unitValue(body: JsonObject, value: BigDecimal): String {
        val provided = body.getString("unit")?.trim()?.takeIf(String::isNotBlank)
        if (provided != null) {
            if (provided.length > 20) throw IllegalArgumentException("unit must not exceed 20 characters")
            return provided
        }
        val type = body.getString("type")?.trim()
        if (type == null || type !in defaultUnits) {
            throw IllegalArgumentException("unit is required when type is invalid or omitted")
        }
        return defaultUnits.getValue(type)
    }

    private fun measuredTime(body: JsonObject): OffsetDateTime {
        val measuredAt = body.getString("measured_at")?.let { offsetDateTime(it, "measured_at") }
            ?: OffsetDateTime.now()
        if (measuredAt.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("measured_at must not be in the future")
        }
        return measuredAt
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

    /**
     * 异常判定：内置参考范围（含边界）外为异常；metadata.thresholds 可按类型覆盖
     * {min,max}（缺省一侧回退内置值）；WEIGHT 无参考范围恒为正常。
     */
    private fun isAbnormal(type: String, value: BigDecimal, metadata: JsonObject?): Boolean {
        val range = referenceRanges[type] ?: return false
        val thresholds = metadata?.getJsonObject("thresholds")?.getJsonObject(type)
        val min = thresholds?.getValue("min")?.let { (it as? Number)?.toDouble() } ?: range.first
        val max = thresholds?.getValue("max")?.let { (it as? Number)?.toDouble() } ?: range.second
        val v = value.toDouble()
        return v < min || v > max
    }

    // ========================================================================
    //  查询构造
    // ========================================================================

    private fun baseQuery() = ctx.select(
        VITAL_SIGN_RECORDS.fields().toList() +
            listOf(PATIENTS.NAME.`as`("patient_name"), ENCOUNTERS.ENCOUNTER_NO.`as`("encounter_no")),
    )
        .from(VITAL_SIGN_RECORDS)
        .join(PATIENTS).on(VITAL_SIGN_RECORDS.PATIENT_ID.eq(PATIENTS.ID))
        .leftJoin(ENCOUNTERS).on(VITAL_SIGN_RECORDS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))

    private fun detailQuery(id: String) = baseQuery()
        .where(VITAL_SIGN_RECORDS.ID.eq(id).and(VITAL_SIGN_RECORDS.DELETED_AT.isNull))

    private fun insertQuery(
        record: RecordFields,
        id: String,
        recordedBy: String,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(VITAL_SIGN_RECORDS)
            .set(VITAL_SIGN_RECORDS.ID, id)
            .set(VITAL_SIGN_RECORDS.PATIENT_ID, record.patientId)
            .set(VITAL_SIGN_RECORDS.TYPE, record.type)
            .set(VITAL_SIGN_RECORDS.VALUE, record.value)
            .set(VITAL_SIGN_RECORDS.UNIT, record.unit)
            .set(VITAL_SIGN_RECORDS.MEASURED_AT, record.measuredAt)
            .set(VITAL_SIGN_RECORDS.RECORDED_BY, recordedBy)
            .set(VITAL_SIGN_RECORDS.ABNORMAL, isAbnormal(record.type, record.value, record.metadata))
            .set(VITAL_SIGN_RECORDS.CREATED_AT, now)
            .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
        record.encounterId?.let { query = query.set(VITAL_SIGN_RECORDS.ENCOUNTER_ID, it) }
        record.note?.let { query = query.set(VITAL_SIGN_RECORDS.NOTE, it) }
        record.metadata?.let { query = query.set(VITAL_SIGN_RECORDS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    private fun createdJson(
        id: String,
        record: RecordFields,
        recordedBy: String,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("patient_id", record.patientId)
            .put("patient_name", null as String?)
            .put("encounter_id", record.encounterId)
            .put("encounter_no", null as String?)
            .put("type", record.type)
            .put("value", record.value)
            .put("unit", record.unit)
            .put("measured_at", record.measuredAt.toString())
            .put("recorded_by", recordedBy)
            .put("abnormal", isAbnormal(record.type, record.value, record.metadata))
            .put("note", record.note)
            .put("metadata", record.metadata)
            .put("review_status", "待复核")
            .put("review_result", null as String?)
            .put("review_note", null as String?)
            .put("reviewed_by", null as String?)
            .put("reviewed_at", null as String?)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    // ========================================================================
    //  行读取与执行
    // ========================================================================

    private fun getRecordRow(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(VITAL_SIGN_RECORDS).where(VITAL_SIGN_RECORDS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                if (row.getOffsetDateTime("deleted_at") != null) {
                    Future.failedFuture(HealthcareNotFoundException("vital sign record not found: $id"))
                } else {
                    Future.succeededFuture(row)
                }
            } ?: Future.failedFuture(HealthcareNotFoundException("vital sign record not found: $id"))
        }

    private fun getPatientRow(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(PATIENTS).where(PATIENTS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("patient not found: $id"))
        }

    private fun validateEncounterOwnership(client: SqlClient, patientId: String, encounterId: String): Future<Row> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { encounter ->
                if (encounter.getString("patient_id") != patientId) {
                    Future.failedFuture(
                        IllegalArgumentException("encounter does not belong to the specified patient"),
                    )
                } else {
                    Future.succeededFuture(encounter)
                }
            } ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
