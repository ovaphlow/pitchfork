package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.FollowupPlans.FOLLOWUP_PLANS
import com.ovaphlow.crate.database.gen.healthcare.tables.HealthCheckupMembers.HEALTH_CHECKUP_MEMBERS
import com.ovaphlow.crate.database.gen.healthcare.tables.HealthCheckupResults.HEALTH_CHECKUP_RESULTS
import com.ovaphlow.crate.database.gen.healthcare.tables.HealthCheckups.HEALTH_CHECKUPS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.healthcare.tables.VitalSignRecords.VITAL_SIGN_RECORDS
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 体检管理服务（医疗/养老/儿保共用）。
 *
 * 业务规则（服务端强制）：
 *  1. 批次按业务年唯一（checkup_year），重复创建返回 409；状态机单向流转
 *     草稿 → 进行中 → 已完成，非法跳转 400。
 *  2. 名单为批次内人员快照（checkup_id + patient_id 唯一，可追加补录）；
 *     仅接受在册（ACTIVE）患者，活动锚点为 ELDERLY_CARE 优先、
 *     OUTPATIENT 次之的 ACTIVE 周期（可无锚点）；结果必须属于批次名单。
 *  3. 数值项异常由服务端按参考范围计算（含边界）：metadata.thresholds（按映射类型
 *     键控）> 显式 ref_min/ref_max > 体征内置参考范围常量；
 *     WEIGHT 及无范围的数值项不判异常；文本项异常由录入人显式标记。
 *  4. 转体征：仅 abnormal 数值项且 item_name 命中常量映射表；生成
 *     vital_sign_records（measured_at=体检日期、recorded_by=认证主体、
 *     metadata 含 exam_result_id/checkup_id 来源与 thresholds），
 *     结果项标记 vital_sign_id；重复转出 409（条件更新兜底并发）。
 *  5. 转随访：仅 abnormal 项；生成 followup_plans（类型 慢病随访/常规电话随访
 *     由操作者选择，planned_date 默认体检日+7 天，planned_way 默认电话），
 *     锚定成员活动 encounter，assignee=认证主体，metadata 含来源；
 *     无活动锚点 409；重复转出 409。
 *  6. 转出为事实快照：结果修正不级联修改已生成的体征/随访。
 *  7. operator/assignee 一律取认证主体；写接口按白名单校验字段；
 *     已完成批次拒绝名单追加、结果录入/修正与转出。
 */
class CheckupService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        private val businessZone = ZoneId.of("Asia/Shanghai")

        /** 批次状态中文枚举与单向流转 */
        val statuses = setOf("草稿", "进行中", "已完成")
        private val statusTransitions = mapOf(
            "草稿" to setOf("进行中"),
            "进行中" to setOf("已完成"),
        )

        /** 转随访允许的随访类型（V508 白名单子集）与方式 */
        val followupTypes = setOf("慢病随访", "常规电话随访")
        private val followupWays = setOf("电话", "上门", "门诊")

        /**
         * 体检项目 → 体征类型映射表（服务端常量）。
         * 仅命中该表且为数值项的结果可转体征；映射表之外的数值项可录入但不可转体征。
         */
        internal val itemToVitalSignMap = mapOf(
            "体温" to "TEMPERATURE",
            "心率" to "PULSE",
            "呼吸" to "RESPIRATION",
            "呼吸频率" to "RESPIRATION",
            "收缩压" to "SYSTOLIC_BP",
            "舒张压" to "DIASTOLIC_BP",
            "血氧饱和度" to "SPO2",
            "血氧" to "SPO2",
            "血糖" to "BLOOD_GLUCOSE",
            "空腹血糖" to "BLOOD_GLUCOSE",
            "体重" to "WEIGHT",
        )

        /** 创建批次写白名单 */
        private val checkupCreateKeys = setOf(
            "checkup_year", "name", "start_date", "end_date", "snapshot", "metadata",
        )

        /** 状态流转写白名单 */
        private val statusWriteKeys = setOf("status")

        /** 名单补录写白名单 */
        private val memberWriteKeys = setOf("patient_ids")

        /** 结果录入写白名单 */
        private val resultCreateKeys = setOf(
            "patient_id", "item_name", "item_category", "value", "unit",
            "text_value", "ref_min", "ref_max", "abnormal", "exam_date", "metadata",
        )

        /** 结果修正写白名单（abnormal 数值项服务端重算，客户端不得提交） */
        private val resultPatchKeys = setOf(
            "value", "unit", "text_value", "abnormal", "ref_min", "ref_max",
            "exam_date", "metadata",
        )

        /** 转随访写白名单 */
        private val followupWriteKeys = setOf("followup_type", "planned_date", "planned_way", "remark")

        private fun today(): LocalDate = LocalDate.now(businessZone)

        private fun checkupJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("checkup_year", (row.getValue("checkup_year") as? Number)?.toInt())
                .put("name", row.getString("name"))
                .put("status", row.getString("status"))
                .put("start_date", row.getLocalDate("start_date")?.toString())
                .put("end_date", row.getLocalDate("end_date")?.toString())
                .put("operator", row.getString("operator"))
                .put("metadata", row.getValue("metadata"))
                .put("member_total", row.getLong("member_total") ?: 0L)
                .put("checked_total", row.getLong("checked_total") ?: 0L)
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        private fun memberJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("checkup_id", row.getString("checkup_id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("encounter_id", row.getString("encounter_id"))
                .put("encounter_no", row.getString("encounter_no"))
                .put("checked", row.getBoolean("checked"))
                .put("checked_at", row.getOffsetDateTime("checked_at")?.toString())
                .put("operator", row.getString("operator"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        private fun resultJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("checkup_id", row.getString("checkup_id"))
                .put("member_id", row.getString("member_id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("item_name", row.getString("item_name"))
                .put("item_category", row.getString("item_category"))
                .put("value", row.getBigDecimal("value"))
                .put("unit", row.getString("unit"))
                .put("text_value", row.getString("text_value"))
                .put("ref_min", row.getBigDecimal("ref_min"))
                .put("ref_max", row.getBigDecimal("ref_max"))
                .put("abnormal", row.getBoolean("abnormal"))
                .put("exam_date", row.getLocalDate("exam_date")?.toString())
                .put("operator", row.getString("operator"))
                .put("vital_sign_id", row.getString("vital_sign_id"))
                .put("followup_plan_id", row.getString("followup_plan_id"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  体检批次
    // ========================================================================

    /**
     * 创建批次：checkup_year 必填（2000–2100）、同机构同一年度唯一（预检 409 +
     * 唯一索引兜底）；status 默认 草稿；snapshot=true（默认）时同事务快照
     * 本机构在册人员（ACTIVE 患者，活动 ELDERLY_CARE 优先 / OUTPATIENT 次之锚点）。
     */
    fun createCheckup(body: JsonObject, operator: String): Future<JsonObject> {
        val year: Int
        val name: String
        val startDate: LocalDate?
        val endDate: LocalDate?
        val snapshot: Boolean
        val metadata: JsonObject?
        try {
            rejectForbiddenKeys(body, checkupCreateKeys, "health checkup")
            year = (body.getValue("checkup_year") as? Number)?.toInt()
                ?: throw IllegalArgumentException("checkup_year is required")
            if (year < 2000 || year > 2100) {
                throw IllegalArgumentException("checkup_year must be between 2000 and 2100")
            }
            name = requiredText(body, "name").also {
                if (it.length > 100) throw IllegalArgumentException("name must not exceed 100 characters")
            }
            startDate = body.getString("start_date")?.let { localDate(it, "start_date") }
            endDate = body.getString("end_date")?.let { localDate(it, "end_date") }
            if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
                throw IllegalArgumentException("end_date must not be earlier than start_date")
            }
            // 显式 JSON null（如 snapshot:null）等价于省略，回退默认 true
            snapshot = body.getBoolean("snapshot", true) ?: true
            metadata = jsonObject(body, "metadata")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            execute(connection, yearCountQuery(year)).compose { rows ->
                val existing = rows.iterator().next().getLong("total") ?: 0L
                if (existing > 0) {
                    Future.failedFuture(ConflictException("health checkup for year $year already exists"))
                } else {
                    execute(connection, checkupInsert(id, year, name, startDate, endDate, metadata, operator, now))
                        .compose {
                            if (!snapshot) {
                                Future.succeededFuture(checkupCreatedJson(id, year, name, startDate, endDate, operator, metadata, now, 0))
                            } else {
                                snapshotMembers(connection, id, operator, now).map { memberTotal ->
                                    checkupCreatedJson(id, year, name, startDate, endDate, operator, metadata, now, memberTotal)
                                }
                            }
                        }
                }
            }
        }
    }

    fun listCheckups(status: String?, limit: Int, offset: Int): Future<JsonObject> {
        val conditions = try {
            mutableListOf<Condition>().also { conditions ->
                status?.takeIf(String::isNotBlank)?.let {
                    conditions += HEALTH_CHECKUPS.STATUS.eq(validValue(it, statuses, "status"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val countQuery = ctx.select(DSL.count().`as`("total")).from(HEALTH_CHECKUPS).where(conditions)
        val dataQuery = listQuery(conditions, limit, offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::checkupJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getCheckup(id: String): Future<JsonObject> = getCheckupVia(pool, id)

    private fun getCheckupVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, checkupDetailQuery(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(checkupJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("health checkup not found: $id"))
        }

    /** 状态流转：草稿 → 进行中 → 已完成，单向不可回退；已完成为终态 */
    fun updateCheckupStatus(id: String, body: JsonObject): Future<JsonObject> {
        val status: String
        try {
            rejectForbiddenKeys(body, statusWriteKeys, "checkup status")
            status = validValue(requiredText(body, "status"), statuses, "status")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getCheckupRow(connection, id).compose { row ->
                val current = row.getString("status")
                val allowed = statusTransitions[current]
                if (allowed == null || status !in allowed) {
                    Future.failedFuture(
                        IllegalArgumentException("invalid status transition from $current to $status"),
                    )
                } else {
                    execute(
                        connection,
                        ctx.update(HEALTH_CHECKUPS)
                            .set(HEALTH_CHECKUPS.STATUS, status)
                            .set(HEALTH_CHECKUPS.UPDATED_AT, now)
                            .where(HEALTH_CHECKUPS.ID.eq(id)),
                    ).compose { rows ->
                        if (rows.rowCount() == 1) getCheckupVia(connection, id)
                        else Future.failedFuture(HealthcareNotFoundException("health checkup not found: $id"))
                    }
                }
            }
        }
    }

    /** 只读统计：应检/已检/完成率/异常数/已转体征/已转随访 */
    fun getCheckupStats(id: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection ->
            getCheckupRow(connection, id).compose {
                val memberQuery = ctx.select(
                    DSL.count().`as`("member_total"),
                    DSL.count().filterWhere(HEALTH_CHECKUP_MEMBERS.CHECKED.isTrue).`as`("checked_total"),
                ).from(HEALTH_CHECKUP_MEMBERS).where(HEALTH_CHECKUP_MEMBERS.CHECKUP_ID.eq(id))
                val resultQuery = ctx.select(
                    DSL.count().filterWhere(HEALTH_CHECKUP_RESULTS.ABNORMAL.isTrue).`as`("abnormal_total"),
                    DSL.count().filterWhere(HEALTH_CHECKUP_RESULTS.VITAL_SIGN_ID.isNotNull).`as`("vital_sign_total"),
                    DSL.count().filterWhere(HEALTH_CHECKUP_RESULTS.FOLLOWUP_PLAN_ID.isNotNull).`as`("followup_total"),
                ).from(HEALTH_CHECKUP_RESULTS).where(HEALTH_CHECKUP_RESULTS.CHECKUP_ID.eq(id))
                execute(connection, memberQuery).compose { memberRows ->
                    execute(connection, resultQuery).map { resultRows ->
                        val memberTotal = memberRows.iterator().next().getLong("member_total") ?: 0L
                        val checkedTotal = memberRows.iterator().next().getLong("checked_total") ?: 0L
                        val abnormalTotal = resultRows.iterator().next().getLong("abnormal_total") ?: 0L
                        val vitalSignTotal = resultRows.iterator().next().getLong("vital_sign_total") ?: 0L
                        val followupTotal = resultRows.iterator().next().getLong("followup_total") ?: 0L
                        val completionRate =
                            if (memberTotal == 0L) 0L else (checkedTotal * 100L) / memberTotal
                        JsonObject()
                            .put("member_total", memberTotal)
                            .put("checked_total", checkedTotal)
                            .put("completion_rate", completionRate)
                            .put("abnormal_total", abnormalTotal)
                            .put("vital_sign_total", vitalSignTotal)
                            .put("followup_total", followupTotal)
                    }
                }
            }
        }

    // ========================================================================
    //  参检名单
    // ========================================================================

    /**
     * 补录名单：仅接受在册（ACTIVE）患者；活动锚点 ELDERLY_CARE 优先、
     * OUTPATIENT 次之（无活动周期则无锚点）；已存在成员幂等跳过。
     * 已完成批次拒绝追加。
     */
    fun addMembers(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val patientIds: List<String>
        try {
            rejectForbiddenKeys(body, memberWriteKeys, "checkup members")
            patientIds = body.getJsonArray("patient_ids")?.mapNotNull { entry ->
                (entry as? String)?.trim()?.takeIf(String::isNotBlank)
            } ?: throw IllegalArgumentException("patient_ids is required")
            if (patientIds.isEmpty()) throw IllegalArgumentException("patient_ids must not be empty")
            if (patientIds.size > 200) throw IllegalArgumentException("patient_ids must not exceed 200 entries")
            if (patientIds.distinct().size != patientIds.size) {
                throw IllegalArgumentException("patient_ids must not contain duplicates")
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getCheckupRow(connection, id).compose { checkup ->
                if (checkup.getString("status") == "已完成") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("cannot add members to a completed checkup"),
                    )
                }
                Future.all(patientIds.map { resolveMemberAnchor(connection, it) }).compose { results ->
                    val resolved = patientIds.mapIndexed { index, patientId ->
                        patientId to results.list<ResolvedAnchor>()[index]
                    }
                    val inserts = resolved.map { (patientId, anchor) ->
                        execute(
                            connection,
                            memberInsert(Ulid.generate(), id, patientId, anchor.encounterId, operator, now),
                        )
                    }
                    Future.all(inserts).map { insertRows ->
                        val created = resolved.mapIndexed { index, (patientId, anchor) ->
                            val rowSet = insertRows.list<RowSet<Row>>()[index]
                            memberCreatedJson(
                                id = id,
                                checkupId = id,
                                patientId = patientId,
                                patientName = anchor.patientName,
                                encounterId = anchor.encounterId,
                                operator = operator,
                                now = now,
                                inserted = rowSet.rowCount() > 0,
                            )
                        }
                        JsonObject().put("records", JsonArray(created))
                    }
                }
            }
        }
    }

    fun listMembers(id: String, checked: String?, limit: Int, offset: Int): Future<JsonObject> {
        val conditions = try {
            mutableListOf<Condition>(
                HEALTH_CHECKUP_MEMBERS.CHECKUP_ID.eq(id),
            ).also { conditions ->
                checked?.takeIf(String::isNotBlank)?.let {
                    when (it) {
                        "true" -> conditions += HEALTH_CHECKUP_MEMBERS.CHECKED.eq(true)
                        "false" -> conditions += HEALTH_CHECKUP_MEMBERS.CHECKED.eq(false)
                        else -> throw IllegalArgumentException("checked must be true or false")
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val countQuery = ctx.select(DSL.count().`as`("total")).from(HEALTH_CHECKUP_MEMBERS).where(conditions)
        val dataQuery = memberBaseQuery()
            .where(conditions)
            .orderBy(HEALTH_CHECKUP_MEMBERS.CREATED_AT.desc(), HEALTH_CHECKUP_MEMBERS.ID.asc())
            .limit(limit)
            .offset(offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::memberJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  体检结果
    // ========================================================================

    /**
     * 结果录入：body 为单条对象或数组；patient 必须属于批次名单；
     * 数值项 value+unit+服务端异常判定，文本项 text_value+人工 abnormal；
     * 录入成功后成员标记已检（checked=true，保留首次 checked_at）。
     * 已完成批次拒绝录入。
     */
    fun createResults(checkupId: String, body: Any, operator: String): Future<JsonObject> {
        val items: List<JsonObject> = try {
            when (body) {
                is JsonArray -> body.map { entry ->
                    entry as? JsonObject ?: throw IllegalArgumentException("each result must be a JSON object")
                }
                is JsonObject -> listOf(body)
                else -> throw IllegalArgumentException("body must be a JSON object or an array of result objects")
            }.also {
                if (it.isEmpty()) throw IllegalArgumentException("results must not be empty")
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val validated: List<ResultFields> = try {
            items.map { validateResultItem(it) }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getCheckupRow(connection, checkupId).compose { checkup ->
                if (checkup.getString("status") == "已完成") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("cannot add results to a completed checkup"),
                    )
                }
                val patientIds = validated.map { it.patientId }.distinct()
                val memberQuery = ctx.select(HEALTH_CHECKUP_MEMBERS.ID, HEALTH_CHECKUP_MEMBERS.PATIENT_ID)
                    .from(HEALTH_CHECKUP_MEMBERS)
                    .where(HEALTH_CHECKUP_MEMBERS.CHECKUP_ID.eq(checkupId).and(HEALTH_CHECKUP_MEMBERS.PATIENT_ID.`in`(patientIds)))
                execute(connection, memberQuery).compose { memberRows ->
                    val memberByPatient = memberRows.associate { row ->
                        row.getString("patient_id") to row.getString("id")
                    }
                    val missing = patientIds.filter { it !in memberByPatient }
                    if (missing.isNotEmpty()) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException(
                                "patient(s) not in this checkup roster: ${missing.joinToString(", ")}",
                            ),
                        )
                    }
                    val inserts = validated.map { fields ->
                        val resultId = Ulid.generate()
                        execute(
                            connection,
                            resultInsert(resultId, checkupId, memberByPatient.getValue(fields.patientId), fields, operator, now),
                        ).map<Any> { resultId }
                    }
                    val checkedUpdates = patientIds.map { patientId ->
                        execute(connection, markCheckedUpdate(checkupId, patientId, now)).map<Any> { Unit }
                    }
                    Future.all(inserts).compose { insertResults ->
                        Future.all(checkedUpdates).map {
                            val created = validated.mapIndexed { index, fields ->
                                resultCreatedJson(insertResults.list<String>()[index], fields, now, operator)
                            }
                            JsonObject().put("records", JsonArray(created))
                        }
                    }
                }
            }
        }
    }

    fun getResult(id: String): Future<JsonObject> = getResultVia(pool, id)

    private fun getResultVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, resultDetailQuery(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(resultJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup result not found: $id"))
        }

    fun listResults(
        checkupId: String,
        abnormal: String?,
        patientId: String?,
        itemCategory: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = try {
            mutableListOf<Condition>(
                HEALTH_CHECKUP_RESULTS.CHECKUP_ID.eq(checkupId),
            ).also { conditions ->
                abnormal?.takeIf(String::isNotBlank)?.let {
                    when (it) {
                        "true" -> conditions += HEALTH_CHECKUP_RESULTS.ABNORMAL.eq(true)
                        "false" -> conditions += HEALTH_CHECKUP_RESULTS.ABNORMAL.eq(false)
                        else -> throw IllegalArgumentException("abnormal must be true or false")
                    }
                }
                patientId?.takeIf(String::isNotBlank)?.let { conditions += HEALTH_CHECKUP_RESULTS.PATIENT_ID.eq(it) }
                itemCategory?.takeIf(String::isNotBlank)?.let {
                    conditions += HEALTH_CHECKUP_RESULTS.ITEM_CATEGORY.eq(validValue(it, setOf("数值", "文本"), "item_category"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val countQuery = ctx.select(DSL.count().`as`("total")).from(HEALTH_CHECKUP_RESULTS).where(conditions)
        val dataQuery = resultBaseQuery()
            .where(conditions)
            .orderBy(HEALTH_CHECKUP_RESULTS.CREATED_AT.desc(), HEALTH_CHECKUP_RESULTS.ID.asc())
            .limit(limit)
            .offset(offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::resultJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 修正结果：数值项重算 abnormal（ref_min/ref_max 或 metadata.thresholds
     * 变更时按新范围判定）；文本项可改 text_value/abnormal/exam_date/metadata。
     * 转出记录为快照，修正不级联已生成的体征/随访。已完成批次拒绝修正。
     */
    fun updateResult(id: String, body: JsonObject): Future<JsonObject> {
        try {
            rejectForbiddenKeys(body, resultPatchKeys, "checkup result update")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getResultRow(connection, id).compose { result ->
                getCheckupRow(connection, result.getString("checkup_id")).compose { checkup ->
                    if (checkup.getString("status") == "已完成") {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("cannot update results of a completed checkup"),
                        )
                    }
                    val category = result.getString("item_category")
                    val update: Query = if (category == "数值") {
                        numericPatchQuery(result, body, now)
                    } else {
                        textPatchQuery(result, body, now)
                    }
                    execute(connection, update).compose { rows ->
                        if (rows.rowCount() == 1) getResultVia(connection, id)
                        else Future.failedFuture(HealthcareNotFoundException("checkup result not found: $id"))
                    }
                }
            }
        }
    }

    // ========================================================================
    //  异常转体征 / 转随访
    // ========================================================================

    /**
     * 转体征：仅 abnormal 数值项且 item_name 命中常量映射表；
     * 生成 vital_sign_records（measured_at=体检日期、recorded_by=认证主体、
     * abnormal 按原参考范围判定、metadata 含来源与 thresholds），
     * 结果项标记 vital_sign_id；同项重复转出 409（条件更新兜底并发）。
     */
    fun toVitalSign(id: String, operator: String): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getResultRow(connection, id).compose { result ->
                if (result.getString("vital_sign_id") != null) {
                    return@compose Future.failedFuture(
                        ConflictException("this result has already been converted to a vital sign"),
                    )
                }
                if (result.getBoolean("abnormal") != true) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only abnormal results can be converted to a vital sign"),
                    )
                }
                if (result.getString("item_category") != "数值") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only numeric items can be converted to a vital sign"),
                    )
                }
                val mappedType = itemToVitalSignMap[result.getString("item_name")]
                if (mappedType == null) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("item ${result.getString("item_name")} is not mappable to a vital sign type"),
                    )
                }
                getCheckupRow(connection, result.getString("checkup_id")).compose { checkup ->
                    if (checkup.getString("status") == "已完成") {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("cannot convert results of a completed checkup"),
                        )
                    }
                    getMemberRow(connection, result.getString("member_id")).compose { member ->
                        val vitalSignId = Ulid.generate()
                        val examDate = result.getLocalDate("exam_date") ?: today()
                        val measuredAt = examDate.atStartOfDay(businessZone).toOffsetDateTime()
                        val unit = result.getString("unit") ?: VitalSignService.defaultUnits.getValue(mappedType)
                        val metadata = conversionMetadata(id, result.getString("checkup_id"), mappedType, result)
                        execute(
                            connection,
                            vitalSignInsert(vitalSignId, result, member, mappedType, unit, measuredAt, operator, metadata, now),
                        ).compose {
                            execute(
                                connection,
                                ctx.update(HEALTH_CHECKUP_RESULTS)
                                    .set(HEALTH_CHECKUP_RESULTS.VITAL_SIGN_ID, vitalSignId)
                                    .set(HEALTH_CHECKUP_RESULTS.UPDATED_AT, now)
                                    .where(HEALTH_CHECKUP_RESULTS.ID.eq(id).and(HEALTH_CHECKUP_RESULTS.VITAL_SIGN_ID.isNull)),
                            )
                        }.compose { rows ->
                            if (rows.rowCount() != 1) {
                                Future.failedFuture(
                                    ConflictException("this result has already been converted to a vital sign"),
                                )
                            } else {
                                getMemberVia(connection, member.getString("id")).compose { memberDetail ->
                                    getResultVia(connection, id).map { resultJson ->
                                        JsonObject()
                                            .put(
                                                "vital_sign",
                                                vitalSignCreatedJson(
                                                    id = vitalSignId,
                                                    patientId = result.getString("patient_id"),
                                                    patientName = memberDetail.getString("patient_name"),
                                                    encounterId = memberDetail.getString("encounter_id"),
                                                    encounterNo = memberDetail.getString("encounter_no"),
                                                    type = mappedType,
                                                    value = result.getBigDecimal("value"),
                                                    unit = unit,
                                                    measuredAt = measuredAt,
                                                    recordedBy = operator,
                                                    abnormal = abnormalOf(result, mappedType),
                                                    metadata = metadata,
                                                    now = now,
                                                ),
                                            )
                                            .put("result", resultJson)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 转随访：仅 abnormal 项；生成 followup_plans（followup_type 由操作者选择，
     * planned_date 默认体检日+7 天、planned_way 默认电话、assignee=认证主体），
     * 锚定成员活动 encounter（无锚点 409），metadata 含来源；
     * 同项重复转出 409（条件更新兜底并发）。
     */
    fun toFollowup(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val followupType: String
        val plannedDate: LocalDate?
        val plannedWay: String
        val remark: String?
        try {
            rejectForbiddenKeys(body, followupWriteKeys, "followup conversion")
            followupType = validValue(requiredText(body, "followup_type"), followupTypes, "followup_type")
            plannedDate = body.getString("planned_date")?.let { localDate(it, "planned_date") }
            plannedWay = body.getString("planned_way")?.trim()?.takeIf(String::isNotBlank)
                ?.let { validValue(it, followupWays, "planned_way") }
                ?: "电话"
            remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
                if (it.length > 1000) throw IllegalArgumentException("remark must not exceed 1000 characters")
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getResultRow(connection, id).compose { result ->
                if (result.getString("followup_plan_id") != null) {
                    return@compose Future.failedFuture(
                        ConflictException("this result has already been converted to a followup plan"),
                    )
                }
                if (result.getBoolean("abnormal") != true) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only abnormal results can be converted to a followup plan"),
                    )
                }
                getCheckupRow(connection, result.getString("checkup_id")).compose { checkup ->
                    if (checkup.getString("status") == "已完成") {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("cannot convert results of a completed checkup"),
                        )
                    }
                    getMemberRow(connection, result.getString("member_id")).compose { member ->
                        val encounterId = member.getString("encounter_id")
                        if (encounterId == null) {
                            return@compose Future.failedFuture(
                                ConflictException("patient has no active encounter, cannot create a followup plan"),
                            )
                        }
                        val patientId = result.getString("patient_id")
                        getPatient(connection, patientId).compose { patient ->
                            if (patient.getString("status") == "DECEASED") {
                                return@compose Future.failedFuture(
                                    IllegalArgumentException("cannot create followup plan for a deceased patient"),
                                )
                            }
                            validateEncounterOwnership(connection, patientId, encounterId).compose { encounter ->
                                val admitDate = patientAdmitDate(encounter)
                                val planDate = plannedDate ?: (result.getLocalDate("exam_date")?.plusDays(7) ?: today())
                                if (admitDate != null && planDate.isBefore(admitDate)) {
                                    return@compose Future.failedFuture(
                                        IllegalArgumentException("planned_date must not be earlier than the admission start date"),
                                    )
                                }
                                val planId = Ulid.generate()
                                val metadata = JsonObject()
                                    .put("source", "体检异常转随访")
                                    .put("exam_result_id", id)
                                    .put("checkup_id", result.getString("checkup_id"))
                                execute(
                                    connection,
                                    followupPlanInsert(planId, result, member, planDate, followupType, plannedWay, remark, operator, metadata, now),
                                ).compose {
                                    execute(
                                        connection,
                                        ctx.update(HEALTH_CHECKUP_RESULTS)
                                            .set(HEALTH_CHECKUP_RESULTS.FOLLOWUP_PLAN_ID, planId)
                                            .set(HEALTH_CHECKUP_RESULTS.UPDATED_AT, now)
                                            .where(HEALTH_CHECKUP_RESULTS.ID.eq(id).and(HEALTH_CHECKUP_RESULTS.FOLLOWUP_PLAN_ID.isNull)),
                                    )
                                }.compose { rows ->
                                    if (rows.rowCount() != 1) {
                                        Future.failedFuture(
                                            ConflictException("this result has already been converted to a followup plan"),
                                        )
                                    } else {
                                        getMemberVia(connection, member.getString("id")).compose { memberDetail ->
                                            getResultVia(connection, id).map { resultJson ->
                                                JsonObject()
                                                    .put(
                                                        "followup_plan",
                                                        followupPlanCreatedJson(
                                                            id = planId,
                                                            patientId = patientId,
                                                            patientName = memberDetail.getString("patient_name"),
                                                            encounterId = encounterId,
                                                            encounterNo = memberDetail.getString("encounter_no"),
                                                            followupType = followupType,
                                                            plannedDate = planDate,
                                                            plannedWay = plannedWay,
                                                            assignee = operator,
                                                            remark = remark,
                                                            metadata = metadata,
                                                            now = now,
                                                        ),
                                                    )
                                                    .put("result", resultJson)
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

    // ========================================================================
    //  校验
    // ========================================================================

    private data class ResultFields(
        val patientId: String,
        val itemName: String,
        val itemCategory: String,
        val value: BigDecimal?,
        val unit: String?,
        val textValue: String?,
        val refMin: BigDecimal?,
        val refMax: BigDecimal?,
        val abnormal: Boolean,
        val examDate: LocalDate,
        val metadata: JsonObject?,
    )

    private fun validateResultItem(body: JsonObject): ResultFields {
        rejectForbiddenKeys(body, resultCreateKeys, "checkup result")
        val patientId = requiredText(body, "patient_id")
        val itemName = requiredText(body, "item_name").also {
            if (it.length > 100) throw IllegalArgumentException("item_name must not exceed 100 characters")
        }
        val itemCategory = validValue(requiredText(body, "item_category"), setOf("数值", "文本"), "item_category")
        val examDate = body.getString("exam_date")?.let { localDate(it, "exam_date") } ?: today()
        if (examDate.isAfter(today())) {
            throw IllegalArgumentException("exam_date must not be in the future")
        }
        val metadata = jsonObject(body, "metadata")
        val mappedType = itemToVitalSignMap[itemName]
        return when (itemCategory) {
            "数值" -> {
                if (body.containsKey("abnormal")) {
                    throw IllegalArgumentException("abnormal is computed by the server for numeric items")
                }
                if (body.containsKey("text_value")) {
                    throw IllegalArgumentException("text_value is only allowed for text items")
                }
                val value = numericValue(body, "value")
                if (mappedType == "SPO2" && (value < BigDecimal.ZERO || value > BigDecimal("100"))) {
                    throw IllegalArgumentException("SPO2 must be between 0 and 100")
                }
                val refMin = rangeValue(body, "ref_min")
                val refMax = rangeValue(body, "ref_max")
                if ((refMin == null) != (refMax == null)) {
                    throw IllegalArgumentException("ref_min and ref_max must be provided together")
                }
                if (refMin != null && refMax != null && refMin > refMax) {
                    throw IllegalArgumentException("ref_min must not be greater than ref_max")
                }
                validateThresholds(metadata, mappedType)
                val unit = body.getString("unit")?.trim()?.takeIf(String::isNotBlank)?.also {
                    if (it.length > 20) throw IllegalArgumentException("unit must not exceed 20 characters")
                } ?: mappedType?.let { VitalSignService.defaultUnits[it] }
                    ?: throw IllegalArgumentException("unit is required for numeric items without a built-in default")
                ResultFields(
                    patientId = patientId,
                    itemName = itemName,
                    itemCategory = itemCategory,
                    value = value,
                    unit = unit,
                    textValue = null,
                    refMin = refMin,
                    refMax = refMax,
                    abnormal = abnormalOf(mappedType, value, refMin, refMax, metadata),
                    examDate = examDate,
                    metadata = metadata,
                )
            }
            else -> {
                if (body.containsKey("value") || body.containsKey("unit") ||
                    body.containsKey("ref_min") || body.containsKey("ref_max")
                ) {
                    throw IllegalArgumentException("value/unit/ref_min/ref_max are only allowed for numeric items")
                }
                val textValue = requiredText(body, "text_value").also {
                    if (it.length > 2000) throw IllegalArgumentException("text_value must not exceed 2000 characters")
                }
                val abnormal = body.getValue("abnormal") as? Boolean
                    ?: throw IllegalArgumentException("abnormal is required for text items")
                ResultFields(
                    patientId = patientId,
                    itemName = itemName,
                    itemCategory = itemCategory,
                    value = null,
                    unit = null,
                    textValue = textValue,
                    refMin = null,
                    refMax = null,
                    abnormal = abnormal,
                    examDate = examDate,
                    metadata = metadata,
                )
            }
        }
    }

    /** 数值项异常判定：metadata.thresholds（按映射类型，可覆盖显式范围）> 显式 ref_min/ref_max > 体征内置范围；WEIGHT/无范围恒正常 */
    private fun abnormalOf(
        mappedType: String?,
        value: BigDecimal,
        refMin: BigDecimal?,
        refMax: BigDecimal?,
        metadata: JsonObject?,
    ): Boolean {
        val range = resolveRange(mappedType, refMin, refMax, metadata) ?: return false
        val v = value.toDouble()
        return v < range.first || v > range.second
    }

    /** 结果行重算（转体征时按原参考范围判定） */
    private fun abnormalOf(result: Row, mappedType: String): Boolean =
        abnormalOf(
            mappedType = mappedType,
            value = result.getBigDecimal("value") ?: return false,
            refMin = result.getBigDecimal("ref_min"),
            refMax = result.getBigDecimal("ref_max"),
            metadata = resultMetadata(result),
        )

    /** 生效参考范围（不抛错，写入侧已校验完整性）：null 表示无范围（不判异常）。
     * 优先级：metadata.thresholds（按映射类型，可覆盖显式范围）> 显式 ref_min/ref_max > 体征内置范围。 */
    private fun resolveRange(
        mappedType: String?,
        refMin: BigDecimal?,
        refMax: BigDecimal?,
        metadata: JsonObject?,
    ): Pair<Double, Double>? {
        val thresholds = metadata?.getJsonObject("thresholds")?.getJsonObject(mappedType)
        val tMin = thresholds?.getValue("min")?.let { (it as? Number)?.toDouble() }
        val tMax = thresholds?.getValue("max")?.let { (it as? Number)?.toDouble() }
        if (tMin != null && tMax != null) return tMin to tMax
        if (refMin != null && refMax != null) return refMin.toDouble() to refMax.toDouble()
        return VitalSignService.referenceRanges[mappedType]
    }

    private fun validateThresholds(metadata: JsonObject?, mappedType: String?) {
        val thresholds = metadata?.getJsonObject("thresholds")?.getJsonObject(mappedType) ?: return
        val tMin = thresholds.getValue("min")?.let { (it as? Number)?.toDouble() }
        val tMax = thresholds.getValue("max")?.let { (it as? Number)?.toDouble() }
        if (tMin == null || tMax == null) {
            throw IllegalArgumentException("thresholds min and max are required together")
        }
        if (tMin > tMax) {
            throw IllegalArgumentException("thresholds min must not be greater than max")
        }
    }

    private fun resultMetadata(result: Row): JsonObject? =
        (result.getValue("metadata") as? JsonObject) ?: runCatching {
            val raw = result.getValue("metadata")
            if (raw is String) JsonObject(raw) else null
        }.getOrNull()

    /** 转体征 metadata：来源引用 + 生效参考范围（供体征记录自解释与复核） */
    private fun conversionMetadata(
        resultId: String,
        checkupId: String?,
        mappedType: String,
        result: Row,
    ): JsonObject {
        val metadata = JsonObject()
            .put("source", "体检异常转体征")
            .put("exam_result_id", resultId)
            .put("checkup_id", checkupId)
        val range = resolveRange(mappedType, result.getBigDecimal("ref_min"), result.getBigDecimal("ref_max"), resultMetadata(result))
        if (range != null) {
            metadata.put(
                "thresholds",
                JsonObject().put(mappedType, JsonObject().put("min", range.first).put("max", range.second)),
            )
        }
        return metadata
    }

    private fun numericPatchQuery(result: Row, body: JsonObject, now: OffsetDateTime): Query {
        if (body.containsKey("text_value") || body.containsKey("abnormal")) {
            throw IllegalArgumentException("text_value/abnormal are only allowed for text items")
        }
        val mappedType = itemToVitalSignMap[result.getString("item_name")]
        val value = body.getValue("value")?.let { numericValue(body, "value") } ?: result.getBigDecimal("value")
        if (mappedType == "SPO2" && (value < BigDecimal.ZERO || value > BigDecimal("100"))) {
            throw IllegalArgumentException("SPO2 must be between 0 and 100")
        }
        val refMin = body.getValue("ref_min")?.let { rangeValue(body, "ref_min") } ?: result.getBigDecimal("ref_min")
        val refMax = body.getValue("ref_max")?.let { rangeValue(body, "ref_max") } ?: result.getBigDecimal("ref_max")
        if ((refMin == null) != (refMax == null)) {
            throw IllegalArgumentException("ref_min and ref_max must be provided together")
        }
        if (refMin != null && refMax != null && refMin > refMax) {
            throw IllegalArgumentException("ref_min must not be greater than ref_max")
        }
        // 未提交 metadata 时沿用已存阈值（含 thresholds 覆盖），保证修正重算口径与录入一致
        val metadata = body.getValue("metadata")?.let { jsonObject(body, "metadata") } ?: resultMetadata(result)
        if (body.containsKey("metadata") && metadata != null) validateThresholds(metadata, mappedType)
        val abnormal = abnormalOf(mappedType, value, refMin, refMax, metadata)
        var query = ctx.update(HEALTH_CHECKUP_RESULTS)
            .set(HEALTH_CHECKUP_RESULTS.VALUE, value)
            .set(HEALTH_CHECKUP_RESULTS.ABNORMAL, abnormal)
            .set(HEALTH_CHECKUP_RESULTS.UPDATED_AT, now)
        body.getString("unit")?.trim()?.takeIf(String::isNotBlank)?.let {
            if (it.length > 20) throw IllegalArgumentException("unit must not exceed 20 characters")
            query = query.set(HEALTH_CHECKUP_RESULTS.UNIT, it)
        }
        query = query.set(HEALTH_CHECKUP_RESULTS.REF_MIN, refMin).set(HEALTH_CHECKUP_RESULTS.REF_MAX, refMax)
        body.getString("exam_date")?.let { raw ->
            val examDate = localDate(raw, "exam_date")
            if (examDate.isAfter(today())) throw IllegalArgumentException("exam_date must not be in the future")
            query = query.set(HEALTH_CHECKUP_RESULTS.EXAM_DATE, examDate)
        }
        if (body.containsKey("metadata")) {
            if (metadata != null) query = query.set(HEALTH_CHECKUP_RESULTS.METADATA, JSONB.valueOf(metadata.encode()))
            else query = query.setNull(HEALTH_CHECKUP_RESULTS.METADATA)
        }
        return query.where(HEALTH_CHECKUP_RESULTS.ID.eq(result.getString("id")))
    }

    private fun textPatchQuery(result: Row, body: JsonObject, now: OffsetDateTime): Query {
        if (body.containsKey("value") || body.containsKey("unit") ||
            body.containsKey("ref_min") || body.containsKey("ref_max")
        ) {
            throw IllegalArgumentException("value/unit/ref_min/ref_max are only allowed for numeric items")
        }
        var query = ctx.update(HEALTH_CHECKUP_RESULTS).set(HEALTH_CHECKUP_RESULTS.UPDATED_AT, now)
        body.getString("text_value")?.trim()?.takeIf(String::isNotBlank)?.let {
            if (it.length > 2000) throw IllegalArgumentException("text_value must not exceed 2000 characters")
            query = query.set(HEALTH_CHECKUP_RESULTS.TEXT_VALUE, it)
        }
        body.getValue("abnormal")?.let {
            query = query.set(HEALTH_CHECKUP_RESULTS.ABNORMAL, it as? Boolean
                ?: throw IllegalArgumentException("abnormal must be a boolean"))
        }
        body.getString("exam_date")?.let { raw ->
            val examDate = localDate(raw, "exam_date")
            if (examDate.isAfter(today())) throw IllegalArgumentException("exam_date must not be in the future")
            query = query.set(HEALTH_CHECKUP_RESULTS.EXAM_DATE, examDate)
        }
        body.getValue("metadata")?.let {
            val metadata = jsonObject(body, "metadata")
                ?: throw IllegalArgumentException("metadata must be a JSON object")
            query = query.set(HEALTH_CHECKUP_RESULTS.METADATA, JSONB.valueOf(metadata.encode()))
        }
        return query.where(HEALTH_CHECKUP_RESULTS.ID.eq(result.getString("id")))
    }

    private data class ResolvedAnchor(val patientName: String, val encounterId: String?)

    /** 解析在册患者与活动锚点：ELDERLY_CARE 优先、OUTPATIENT 次之（可无锚点） */
    private fun resolveMemberAnchor(client: SqlClient, patientId: String): Future<ResolvedAnchor> {
        val preference = DSL.case_(ENCOUNTERS.ENCOUNTER_TYPE).`when`("ELDERLY_CARE", 0).else_(1)
        val query = ctx.select(PATIENTS.NAME, PATIENTS.STATUS, ENCOUNTERS.ID.`as`("encounter_id"))
            .distinctOn(PATIENTS.ID)
            .from(PATIENTS)
            .leftJoin(ENCOUNTERS).on(
                ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID)
                    .and(ENCOUNTERS.STATUS.eq("ACTIVE"))
                    .and(ENCOUNTERS.ENCOUNTER_TYPE.`in`("ELDERLY_CARE", "OUTPATIENT")),
            )
            .where(PATIENTS.ID.eq(patientId))
            .orderBy(PATIENTS.ID.asc(), preference.asc())
        return execute(client, query).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                if (row.getString("status") != "ACTIVE") {
                    Future.failedFuture(
                        IllegalArgumentException("patient is not in the active registry: $patientId"),
                    )
                } else {
                    Future.succeededFuture(ResolvedAnchor(row.getString("name"), row.getString("encounter_id")))
                }
            } ?: Future.failedFuture(HealthcareNotFoundException("patient not found: $patientId"))
        }
    }

    /** 创建时快照本机构在册人员：ACTIVE 患者 + 活动锚点（ELDERLY_CARE 优先） */
    private fun snapshotMembers(
        client: SqlClient,
        checkupId: String,
        operator: String,
        now: OffsetDateTime,
    ): Future<Int> {
        val preference = DSL.case_(ENCOUNTERS.ENCOUNTER_TYPE).`when`("ELDERLY_CARE", 0).else_(1)
        val query = ctx.select(PATIENTS.ID, ENCOUNTERS.ID.`as`("encounter_id"))
            .distinctOn(PATIENTS.ID)
            .from(PATIENTS)
            .leftJoin(ENCOUNTERS).on(
                ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID)
                    .and(ENCOUNTERS.STATUS.eq("ACTIVE"))
                    .and(ENCOUNTERS.ENCOUNTER_TYPE.`in`("ELDERLY_CARE", "OUTPATIENT")),
            )
            .where(PATIENTS.STATUS.eq("ACTIVE"))
            .orderBy(PATIENTS.ID.asc(), preference.asc())
        return execute(client, query).compose { rows ->
            val inserts = rows.map { row ->
                execute(
                    client,
                    memberInsert(Ulid.generate(), checkupId, row.getString("id"), row.getString("encounter_id"), operator, now),
                )
            }
            Future.all(inserts).map { rows.size() }
        }
    }

    // ========================================================================
    //  查询构造
    // ========================================================================

    private fun yearCountQuery(year: Int) =
        ctx.select(DSL.count().`as`("total")).from(HEALTH_CHECKUPS).where(HEALTH_CHECKUPS.CHECKUP_YEAR.eq(year))

    private fun listQuery(conditions: List<Condition>, limit: Int, offset: Int) = ctx.select(
        HEALTH_CHECKUPS.fields().toList() +
            listOf(
                DSL.count(HEALTH_CHECKUP_MEMBERS.ID).`as`("member_total"),
                DSL.count().filterWhere(HEALTH_CHECKUP_MEMBERS.CHECKED.isTrue).`as`("checked_total"),
            ),
    )
        .from(HEALTH_CHECKUPS)
        .leftJoin(HEALTH_CHECKUP_MEMBERS).on(HEALTH_CHECKUP_MEMBERS.CHECKUP_ID.eq(HEALTH_CHECKUPS.ID))
        .where(conditions)
        .groupBy(HEALTH_CHECKUPS.fields().toList())
        .orderBy(HEALTH_CHECKUPS.CHECKUP_YEAR.desc(), HEALTH_CHECKUPS.CREATED_AT.desc())
        .limit(limit)
        .offset(offset)

    /** 详情查询：与列表同口径带应检/已检聚合（member_total/checked_total），避免详情页显示 0 */
    private fun checkupDetailQuery(id: String) = ctx.select(
        HEALTH_CHECKUPS.fields().toList() +
            listOf(
                DSL.count(HEALTH_CHECKUP_MEMBERS.ID).`as`("member_total"),
                DSL.count().filterWhere(HEALTH_CHECKUP_MEMBERS.CHECKED.isTrue).`as`("checked_total"),
            ),
    )
        .from(HEALTH_CHECKUPS)
        .leftJoin(HEALTH_CHECKUP_MEMBERS).on(HEALTH_CHECKUP_MEMBERS.CHECKUP_ID.eq(HEALTH_CHECKUPS.ID))
        .where(HEALTH_CHECKUPS.ID.eq(id))
        .groupBy(HEALTH_CHECKUPS.fields().toList())

    private fun memberBaseQuery() = ctx.select(
        HEALTH_CHECKUP_MEMBERS.fields().toList() +
            listOf(PATIENTS.NAME.`as`("patient_name"), ENCOUNTERS.ENCOUNTER_NO.`as`("encounter_no")),
    )
        .from(HEALTH_CHECKUP_MEMBERS)
        .join(PATIENTS).on(HEALTH_CHECKUP_MEMBERS.PATIENT_ID.eq(PATIENTS.ID))
        .leftJoin(ENCOUNTERS).on(HEALTH_CHECKUP_MEMBERS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))

    private fun resultBaseQuery() = ctx.select(
        HEALTH_CHECKUP_RESULTS.fields().toList() +
            listOf(PATIENTS.NAME.`as`("patient_name")),
    )
        .from(HEALTH_CHECKUP_RESULTS)
        .join(PATIENTS).on(HEALTH_CHECKUP_RESULTS.PATIENT_ID.eq(PATIENTS.ID))

    private fun resultDetailQuery(id: String) = resultBaseQuery().where(HEALTH_CHECKUP_RESULTS.ID.eq(id))

    private fun checkupInsert(
        id: String,
        year: Int,
        name: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        metadata: JsonObject?,
        operator: String,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(HEALTH_CHECKUPS)
            .set(HEALTH_CHECKUPS.ID, id)
            .set(HEALTH_CHECKUPS.CHECKUP_YEAR, year)
            .set(HEALTH_CHECKUPS.NAME, name)
            .set(HEALTH_CHECKUPS.STATUS, "草稿")
            .set(HEALTH_CHECKUPS.OPERATOR, operator)
            .set(HEALTH_CHECKUPS.CREATED_AT, now)
            .set(HEALTH_CHECKUPS.UPDATED_AT, now)
        startDate?.let { query = query.set(HEALTH_CHECKUPS.START_DATE, it) }
        endDate?.let { query = query.set(HEALTH_CHECKUPS.END_DATE, it) }
        metadata?.let { query = query.set(HEALTH_CHECKUPS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    private fun memberInsert(
        id: String,
        checkupId: String,
        patientId: String,
        encounterId: String?,
        operator: String,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(HEALTH_CHECKUP_MEMBERS)
            .set(HEALTH_CHECKUP_MEMBERS.ID, id)
            .set(HEALTH_CHECKUP_MEMBERS.CHECKUP_ID, checkupId)
            .set(HEALTH_CHECKUP_MEMBERS.PATIENT_ID, patientId)
            .set(HEALTH_CHECKUP_MEMBERS.CHECKED, false)
            .set(HEALTH_CHECKUP_MEMBERS.OPERATOR, operator)
            .set(HEALTH_CHECKUP_MEMBERS.CREATED_AT, now)
            .set(HEALTH_CHECKUP_MEMBERS.UPDATED_AT, now)
        encounterId?.let { query = query.set(HEALTH_CHECKUP_MEMBERS.ENCOUNTER_ID, it) }
        // 幂等：已存在成员（唯一索引 checkup_id+patient_id）静默跳过
        return query.onDuplicateKeyIgnore()
    }

    private fun markCheckedUpdate(checkupId: String, patientId: String, now: OffsetDateTime): Query =
        ctx.update(HEALTH_CHECKUP_MEMBERS)
            .set(HEALTH_CHECKUP_MEMBERS.CHECKED, true)
            .set(HEALTH_CHECKUP_MEMBERS.CHECKED_AT, now)
            .set(HEALTH_CHECKUP_MEMBERS.UPDATED_AT, now)
            .where(
                HEALTH_CHECKUP_MEMBERS.CHECKUP_ID.eq(checkupId)
                    .and(HEALTH_CHECKUP_MEMBERS.PATIENT_ID.eq(patientId))
                    .and(HEALTH_CHECKUP_MEMBERS.CHECKED.eq(false)),
            )

    private fun resultInsert(
        id: String,
        checkupId: String,
        memberId: String,
        fields: ResultFields,
        operator: String,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(HEALTH_CHECKUP_RESULTS)
            .set(HEALTH_CHECKUP_RESULTS.ID, id)
            .set(HEALTH_CHECKUP_RESULTS.CHECKUP_ID, checkupId)
            .set(HEALTH_CHECKUP_RESULTS.MEMBER_ID, memberId)
            .set(HEALTH_CHECKUP_RESULTS.PATIENT_ID, fields.patientId)
            .set(HEALTH_CHECKUP_RESULTS.ITEM_NAME, fields.itemName)
            .set(HEALTH_CHECKUP_RESULTS.ITEM_CATEGORY, fields.itemCategory)
            .set(HEALTH_CHECKUP_RESULTS.ABNORMAL, fields.abnormal)
            .set(HEALTH_CHECKUP_RESULTS.EXAM_DATE, fields.examDate)
            .set(HEALTH_CHECKUP_RESULTS.OPERATOR, operator)
            .set(HEALTH_CHECKUP_RESULTS.CREATED_AT, now)
            .set(HEALTH_CHECKUP_RESULTS.UPDATED_AT, now)
        fields.value?.let { query = query.set(HEALTH_CHECKUP_RESULTS.VALUE, it) }
        fields.unit?.let { query = query.set(HEALTH_CHECKUP_RESULTS.UNIT, it) }
        fields.textValue?.let { query = query.set(HEALTH_CHECKUP_RESULTS.TEXT_VALUE, it) }
        fields.refMin?.let { query = query.set(HEALTH_CHECKUP_RESULTS.REF_MIN, it) }
        fields.refMax?.let { query = query.set(HEALTH_CHECKUP_RESULTS.REF_MAX, it) }
        fields.metadata?.let { query = query.set(HEALTH_CHECKUP_RESULTS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    private fun vitalSignInsert(
        id: String,
        result: Row,
        member: Row,
        type: String,
        unit: String,
        measuredAt: OffsetDateTime,
        recordedBy: String,
        metadata: JsonObject,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(VITAL_SIGN_RECORDS)
            .set(VITAL_SIGN_RECORDS.ID, id)
            .set(VITAL_SIGN_RECORDS.PATIENT_ID, result.getString("patient_id"))
            .set(VITAL_SIGN_RECORDS.TYPE, type)
            .set(VITAL_SIGN_RECORDS.VALUE, result.getBigDecimal("value"))
            .set(VITAL_SIGN_RECORDS.UNIT, unit)
            .set(VITAL_SIGN_RECORDS.MEASURED_AT, measuredAt)
            .set(VITAL_SIGN_RECORDS.RECORDED_BY, recordedBy)
            .set(VITAL_SIGN_RECORDS.ABNORMAL, abnormalOf(result, type))
            .set(VITAL_SIGN_RECORDS.METADATA, JSONB.valueOf(metadata.encode()))
            .set(VITAL_SIGN_RECORDS.CREATED_AT, now)
            .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
        member.getString("encounter_id")?.let { query = query.set(VITAL_SIGN_RECORDS.ENCOUNTER_ID, it) }
        return query
    }

    private fun followupPlanInsert(
        id: String,
        result: Row,
        member: Row,
        plannedDate: LocalDate,
        followupType: String,
        plannedWay: String,
        remark: String?,
        assignee: String,
        metadata: JsonObject,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(FOLLOWUP_PLANS)
            .set(FOLLOWUP_PLANS.ID, id)
            .set(FOLLOWUP_PLANS.PATIENT_ID, result.getString("patient_id"))
            .set(FOLLOWUP_PLANS.ENCOUNTER_ID, member.getString("encounter_id"))
            .set(FOLLOWUP_PLANS.FOLLOWUP_TYPE, followupType)
            .set(FOLLOWUP_PLANS.PLANNED_DATE, plannedDate)
            .set(FOLLOWUP_PLANS.PLANNED_WAY, plannedWay)
            .set(FOLLOWUP_PLANS.ASSIGNEE, assignee)
            .set(FOLLOWUP_PLANS.STATUS, "待随访")
            .set(FOLLOWUP_PLANS.METADATA, JSONB.valueOf(metadata.encode()))
            .set(FOLLOWUP_PLANS.CREATED_AT, now)
            .set(FOLLOWUP_PLANS.UPDATED_AT, now)
        remark?.let { query = query.set(FOLLOWUP_PLANS.REMARK, it) }
        return query
    }

    // ========================================================================
    //  行读取与通用辅助
    // ========================================================================

    private fun getCheckupRow(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(HEALTH_CHECKUPS).where(HEALTH_CHECKUPS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("health checkup not found: $id"))
        }

    private fun getMemberRow(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(HEALTH_CHECKUP_MEMBERS).where(HEALTH_CHECKUP_MEMBERS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup member not found: $id"))
        }

    private fun getMemberVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, memberBaseQuery().where(HEALTH_CHECKUP_MEMBERS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(memberJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup member not found: $id"))
        }

    private fun getResultRow(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(HEALTH_CHECKUP_RESULTS).where(HEALTH_CHECKUP_RESULTS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup result not found: $id"))
        }

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

    private fun patientAdmitDate(encounter: Row): LocalDate? =
        encounter.getOffsetDateTime("admit_date")?.atZoneSameInstant(businessZone)?.toLocalDate()

    private fun checkupCreatedJson(
        id: String,
        year: Int,
        name: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        operator: String,
        metadata: JsonObject?,
        now: OffsetDateTime,
        memberTotal: Int,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("checkup_year", year)
            .put("name", name)
            .put("status", "草稿")
            .put("start_date", startDate?.toString())
            .put("end_date", endDate?.toString())
            .put("operator", operator)
            .put("metadata", metadata)
            .put("member_total", memberTotal)
            .put("checked_total", 0)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun memberCreatedJson(
        id: String,
        checkupId: String,
        patientId: String,
        patientName: String?,
        encounterId: String?,
        operator: String,
        now: OffsetDateTime,
        inserted: Boolean,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("checkup_id", checkupId)
            .put("patient_id", patientId)
            .put("patient_name", patientName)
            .put("encounter_id", encounterId)
            .put("encounter_no", null as String?)
            .put("checked", false)
            .put("checked_at", null as String?)
            .put("operator", operator)
            .put("metadata", null as String?)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())
            .put("skipped", !inserted)

    private fun resultCreatedJson(id: String, fields: ResultFields, now: OffsetDateTime, operator: String): JsonObject =
        JsonObject()
            .put("id", id)
            .put("checkup_id", null as String?)
            .put("member_id", null as String?)
            .put("patient_id", fields.patientId)
            .put("patient_name", null as String?)
            .put("item_name", fields.itemName)
            .put("item_category", fields.itemCategory)
            .put("value", fields.value)
            .put("unit", fields.unit)
            .put("text_value", fields.textValue)
            .put("ref_min", fields.refMin)
            .put("ref_max", fields.refMax)
            .put("abnormal", fields.abnormal)
            .put("exam_date", fields.examDate.toString())
            .put("operator", operator)
            .put("vital_sign_id", null as String?)
            .put("followup_plan_id", null as String?)
            .put("metadata", fields.metadata)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun vitalSignCreatedJson(
        id: String,
        patientId: String,
        patientName: String?,
        encounterId: String?,
        encounterNo: String?,
        type: String,
        value: BigDecimal?,
        unit: String,
        measuredAt: OffsetDateTime,
        recordedBy: String,
        abnormal: Boolean,
        metadata: JsonObject,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("patient_id", patientId)
            .put("patient_name", patientName)
            .put("encounter_id", encounterId)
            .put("encounter_no", encounterNo)
            .put("type", type)
            .put("value", value)
            .put("unit", unit)
            .put("measured_at", measuredAt.toString())
            .put("recorded_by", recordedBy)
            .put("abnormal", abnormal)
            .put("note", null as String?)
            .put("metadata", metadata)
            .put("review_status", "待复核")
            .put("review_result", null as String?)
            .put("review_note", null as String?)
            .put("reviewed_by", null as String?)
            .put("reviewed_at", null as String?)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun followupPlanCreatedJson(
        id: String,
        patientId: String,
        patientName: String?,
        encounterId: String,
        encounterNo: String?,
        followupType: String,
        plannedDate: LocalDate,
        plannedWay: String,
        assignee: String,
        remark: String?,
        metadata: JsonObject,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("patient_id", patientId)
            .put("patient_name", patientName)
            .put("encounter_id", encounterId)
            .put("encounter_no", encounterNo)
            .put("followup_type", followupType)
            .put("planned_date", plannedDate.toString())
            .put("planned_way", plannedWay)
            .put("assignee", assignee)
            .put("status", "待随访")
            .put("completed_at", null as String?)
            .put("cancel_reason", null as String?)
            .put("remark", remark)
            .put("metadata", metadata)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

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

    private fun rangeValue(body: JsonObject, key: String): BigDecimal? {
        val raw = body.getValue(key) ?: return null
        val value = (raw as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$key must be a number")
        if (!value.isFinite() || value < 0) {
            throw IllegalArgumentException("$key must be a non-negative number")
        }
        val decimal = BigDecimal.valueOf(value)
        if (decimal.scale() > 2) {
            throw IllegalArgumentException("$key must have at most 2 decimal places")
        }
        return decimal
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
