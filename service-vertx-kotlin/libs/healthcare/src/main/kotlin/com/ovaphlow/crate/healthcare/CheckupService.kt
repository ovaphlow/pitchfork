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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 体检管理服务（056 后仅养老在用；医疗服务保留）。
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
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class CheckupService(
    private val pool: Pool,
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

        private const val CHECKUP_COLUMNS =
            "healthcare.health_checkups.id, healthcare.health_checkups.checkup_year, healthcare.health_checkups.name, healthcare.health_checkups.status, healthcare.health_checkups.start_date, healthcare.health_checkups.end_date, healthcare.health_checkups.operator, " +
                "healthcare.health_checkups.metadata, healthcare.health_checkups.created_at, healthcare.health_checkups.updated_at"

        private const val MEMBER_COLUMNS =
            "healthcare.health_checkup_members.id, healthcare.health_checkup_members.checkup_id, healthcare.health_checkup_members.patient_id, healthcare.health_checkup_members.encounter_id, healthcare.health_checkup_members.checked, healthcare.health_checkup_members.checked_at, " +
                "healthcare.health_checkup_members.operator, healthcare.health_checkup_members.metadata, healthcare.health_checkup_members.created_at, healthcare.health_checkup_members.updated_at"

        private const val RESULT_COLUMNS =
            "healthcare.health_checkup_results.id, healthcare.health_checkup_results.checkup_id, healthcare.health_checkup_results.member_id, healthcare.health_checkup_results.patient_id, healthcare.health_checkup_results.item_name, healthcare.health_checkup_results.item_category, " +
                "healthcare.health_checkup_results.value, healthcare.health_checkup_results.unit, healthcare.health_checkup_results.text_value, healthcare.health_checkup_results.ref_min, healthcare.health_checkup_results.ref_max, healthcare.health_checkup_results.abnormal, healthcare.health_checkup_results.exam_date, " +
                "healthcare.health_checkup_results.operator, healthcare.health_checkup_results.vital_sign_id, healthcare.health_checkup_results.followup_plan_id, healthcare.health_checkup_results.metadata, healthcare.health_checkup_results.created_at, healthcare.health_checkup_results.updated_at"

        /**
         * 批次基础查询：带应检/已检聚合（列表与详情同口径，避免详情页显示 0）。
         * 过滤条件占位符由调用方按出现顺序追加（`$1..`）。
         */
        private const val CHECKUP_BASE_SQL =
            "SELECT $CHECKUP_COLUMNS, " +
                "count(healthcare.health_checkup_members.id) AS member_total, " +
                "count(*) FILTER (WHERE healthcare.health_checkup_members.checked) AS checked_total " +
                "FROM healthcare.health_checkups " +
                "LEFT JOIN healthcare.health_checkup_members ON healthcare.health_checkup_members.checkup_id = healthcare.health_checkups.id"

        private const val CHECKUP_GROUP_BY =
            " GROUP BY healthcare.health_checkups.id, healthcare.health_checkups.checkup_year, healthcare.health_checkups.name, healthcare.health_checkups.status, healthcare.health_checkups.start_date, healthcare.health_checkups.end_date, healthcare.health_checkups.operator, " +
                "healthcare.health_checkups.metadata, healthcare.health_checkups.created_at, healthcare.health_checkups.updated_at"

        /** 名单基础查询：挂患者名（内连）与住院号（左连，锚点可空）。 */
        private const val MEMBER_BASE_SQL =
            "SELECT $MEMBER_COLUMNS, healthcare.patients.name AS patient_name, healthcare.encounters.encounter_no AS encounter_no " +
                "FROM healthcare.health_checkup_members " +
                "JOIN healthcare.patients ON healthcare.patients.id = healthcare.health_checkup_members.patient_id " +
                "LEFT JOIN healthcare.encounters ON healthcare.encounters.id = healthcare.health_checkup_members.encounter_id"

        /** 结果基础查询：挂患者名（内连）。 */
        private const val RESULT_BASE_SQL =
            "SELECT $RESULT_COLUMNS, healthcare.patients.name AS patient_name " +
                "FROM healthcare.health_checkup_results " +
                "JOIN healthcare.patients ON healthcare.patients.id = healthcare.health_checkup_results.patient_id"

        private const val INSERT_CHECKUP_SQL =
            "INSERT INTO healthcare.health_checkups " +
                "(id, checkup_year, name, status, operator, created_at, updated_at, start_date, end_date, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10)"

        private const val INSERT_MEMBER_SQL =
            "INSERT INTO healthcare.health_checkup_members " +
                "(id, checkup_id, patient_id, checked, operator, created_at, updated_at, encounter_id) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8) ON CONFLICT DO NOTHING"

        private const val INSERT_RESULT_SQL =
            "INSERT INTO healthcare.health_checkup_results " +
                "(id, checkup_id, member_id, patient_id, item_name, item_category, abnormal, exam_date, " +
                "operator, created_at, updated_at, value, unit, text_value, ref_min, ref_max, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14, " +
                "\$15, \$16, \$17)"

        private const val INSERT_VITAL_SIGN_SQL =
            "INSERT INTO healthcare.vital_sign_records " +
                "(id, patient_id, type, value, unit, measured_at, recorded_by, abnormal, metadata, " +
                "created_at, updated_at, encounter_id) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        private const val INSERT_FOLLOWUP_PLAN_SQL =
            "INSERT INTO healthcare.followup_plans " +
                "(id, patient_id, encounter_id, followup_type, planned_date, planned_way, assignee, " +
                "status, metadata, created_at, updated_at, remark) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        private const val MARK_CHECKED_SQL =
            "UPDATE healthcare.health_checkup_members SET checked = true, checked_at = \$3, updated_at = \$4 " +
                "WHERE checkup_id = \$1 AND patient_id = \$2 AND checked = false"

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

        /**
         * 活动锚点优先级排序键：ELDERLY_CARE=0、其余(OUTPATIENT)=1。
         *
         * 字面量必须内联渲染——若走绑定参数，PostgreSQL 无法从
         * `CASE <varchar> WHEN $n THEN $m ELSE $k END` 的上下文推断类型，
         * 会按 SQL 规范回落为 `text`；Vert.x PG 客户端据此要求 String，
         * 遇到 Integer 0/1 直接在编码期抛编码异常，使创建批次（默认快照名单）
         * 与名单补录一律 500。这里是常量，不含任何用户输入。
         */
        private const val ANCHOR_PREFERENCE_SQL =
            "CASE healthcare.encounters.encounter_type WHEN 'ELDERLY_CARE' THEN 0 ELSE 1 END ASC"
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
            execute(
                connection,
                "SELECT count(*) AS total FROM healthcare.health_checkups WHERE checkup_year = \$1",
                Tuple.of(year),
            ).compose { rows ->
                val existing = rows.iterator().next().getLong("total") ?: 0L
                if (existing > 0) {
                    Future.failedFuture(ConflictException("health checkup for year $year already exists"))
                } else {
                    execute(
                        connection,
                        INSERT_CHECKUP_SQL,
                        Tuple.of(
                            id,
                            year,
                            name,
                            "草稿",
                            operator,
                            now,
                            now,
                            startDate,
                            endDate,
                            metadata,
                        ),
                    ).compose {
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
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val filters = mutableListOf<String>()
        try {
            status?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.health_checkups.status = ${bind(validValue(it, statuses, "status"))}"
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val where = if (filters.isEmpty()) "" else " WHERE ${filters.joinToString(" AND ")}"
        val countSql = "SELECT count(*) AS total FROM healthcare.health_checkups$where"
        val dataSql = "$CHECKUP_BASE_SQL$where$CHECKUP_GROUP_BY " +
            "ORDER BY healthcare.health_checkups.checkup_year DESC, healthcare.health_checkups.created_at DESC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"
        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::checkupJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getCheckup(id: String): Future<JsonObject> = getCheckupVia(pool, id)

    private fun getCheckupVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, "$CHECKUP_BASE_SQL WHERE healthcare.health_checkups.id = \$1$CHECKUP_GROUP_BY", Tuple.of(id)).compose { rows ->
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
                        "UPDATE healthcare.health_checkups SET status = \$2, updated_at = \$3 WHERE id = \$1",
                        Tuple.of(id, status, now),
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
                val memberSql = "SELECT count(*) AS member_total, " +
                    "count(*) FILTER (WHERE checked) AS checked_total " +
                    "FROM healthcare.health_checkup_members WHERE checkup_id = \$1"
                val resultSql = "SELECT count(*) FILTER (WHERE abnormal) AS abnormal_total, " +
                    "count(*) FILTER (WHERE vital_sign_id IS NOT NULL) AS vital_sign_total, " +
                    "count(*) FILTER (WHERE followup_plan_id IS NOT NULL) AS followup_total " +
                    "FROM healthcare.health_checkup_results WHERE checkup_id = \$1"
                execute(connection, memberSql, Tuple.of(id)).compose { memberRows ->
                    execute(connection, resultSql, Tuple.of(id)).map { resultRows ->
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
                            INSERT_MEMBER_SQL,
                            Tuple.of(
                                Ulid.generate(),
                                id,
                                patientId,
                                false,
                                operator,
                                now,
                                now,
                                anchor.encounterId,
                            ),
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
        val args = mutableListOf<Any?>(id)
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val filters = mutableListOf("healthcare.health_checkup_members.checkup_id = \$1")
        try {
            checked?.takeIf(String::isNotBlank)?.let {
                when (it) {
                    "true" -> filters += "healthcare.health_checkup_members.checked = ${bind(true)}"
                    "false" -> filters += "healthcare.health_checkup_members.checked = ${bind(false)}"
                    else -> throw IllegalArgumentException("checked must be true or false")
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val where = " WHERE ${filters.joinToString(" AND ")}"
        val countSql = "SELECT count(*) AS total FROM ($MEMBER_BASE_SQL$where) members"
        val dataSql = "$MEMBER_BASE_SQL$where ORDER BY healthcare.health_checkup_members.created_at DESC, healthcare.health_checkup_members.id ASC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"
        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
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
                val memberArgs = mutableListOf<Any?>(checkupId)
                memberArgs.addAll(patientIds)
                val placeholders = patientIds.indices.joinToString(", ") { "\$${it + 2}" }
                val memberSql = "SELECT id, patient_id FROM healthcare.health_checkup_members " +
                    "WHERE checkup_id = \$1 AND patient_id IN ($placeholders)"
                execute(connection, memberSql, Tuple.from(memberArgs)).compose { memberRows ->
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
                            INSERT_RESULT_SQL,
                            resultInsertArgs(
                                resultId,
                                checkupId,
                                memberByPatient.getValue(fields.patientId),
                                fields,
                                operator,
                                now,
                            ),
                        ).map<Any> { resultId }
                    }
                    val checkedUpdates = patientIds.map { patientId ->
                        execute(connection, MARK_CHECKED_SQL, Tuple.of(checkupId, patientId, now, now)).map<Any> { Unit }
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
        execute(client, "$RESULT_BASE_SQL WHERE healthcare.health_checkup_results.id = \$1", Tuple.of(id)).compose { rows ->
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
        val args = mutableListOf<Any?>(checkupId)
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val filters = mutableListOf("healthcare.health_checkup_results.checkup_id = \$1")
        try {
            abnormal?.takeIf(String::isNotBlank)?.let {
                when (it) {
                    "true" -> filters += "healthcare.health_checkup_results.abnormal = ${bind(true)}"
                    "false" -> filters += "healthcare.health_checkup_results.abnormal = ${bind(false)}"
                    else -> throw IllegalArgumentException("abnormal must be true or false")
                }
            }
            patientId?.takeIf(String::isNotBlank)?.let { filters += "healthcare.health_checkup_results.patient_id = ${bind(it)}" }
            itemCategory?.takeIf(String::isNotBlank)?.let {
                filters += "healthcare.health_checkup_results.item_category = ${bind(validValue(it, setOf("数值", "文本"), "item_category"))}"
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val where = " WHERE ${filters.joinToString(" AND ")}"
        val countSql = "SELECT count(*) AS total FROM ($RESULT_BASE_SQL$where) results"
        val dataSql = "$RESULT_BASE_SQL$where ORDER BY healthcare.health_checkup_results.created_at DESC, healthcare.health_checkup_results.id ASC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"
        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
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
                    val (sql, args) = if (category == "数值") {
                        numericPatch(result, body, now)
                    } else {
                        textPatch(result, body, now)
                    }
                    execute(connection, sql, args).compose { rows ->
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
                            INSERT_VITAL_SIGN_SQL,
                            Tuple.of(
                                vitalSignId,
                                result.getString("patient_id"),
                                mappedType,
                                result.getBigDecimal("value"),
                                unit,
                                measuredAt,
                                operator,
                                abnormalOf(result, mappedType),
                                metadata,
                                now,
                                now,
                                member.getString("encounter_id"),
                            ),
                        ).compose {
                            execute(
                                connection,
                                "UPDATE healthcare.health_checkup_results SET vital_sign_id = \$2, " +
                                    "updated_at = \$3 WHERE id = \$1 AND vital_sign_id IS NULL",
                                Tuple.of(id, vitalSignId, now),
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
                                    INSERT_FOLLOWUP_PLAN_SQL,
                                    Tuple.of(
                                        planId,
                                        patientId,
                                        encounterId,
                                        followupType,
                                        planDate,
                                        plannedWay,
                                        operator,
                                        "待随访",
                                        metadata,
                                        now,
                                        now,
                                        remark,
                                    ),
                                ).compose {
                                    execute(
                                        connection,
                                        "UPDATE healthcare.health_checkup_results SET followup_plan_id = \$2, " +
                                            "updated_at = \$3 WHERE id = \$1 AND followup_plan_id IS NULL",
                                        Tuple.of(id, planId, now),
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

    /** 数值项修正语句（占位符 `$1`=id，位置固定；可选列按出现顺序追加）。 */
    private fun numericPatch(result: Row, body: JsonObject, now: OffsetDateTime): Pair<String, Tuple> {
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
        val args = mutableListOf<Any?>(result.getString("id"), value, abnormal, now)
        fun bind(v: Any?): String = "\$${args.size + 1}".also { args.add(v) }
        val sets = mutableListOf("value = \$2", "abnormal = \$3", "updated_at = \$4")
        body.getString("unit")?.trim()?.takeIf(String::isNotBlank)?.let {
            if (it.length > 20) throw IllegalArgumentException("unit must not exceed 20 characters")
            sets += "unit = ${bind(it)}"
        }
        sets += "ref_min = ${bind(refMin)}"
        sets += "ref_max = ${bind(refMax)}"
        body.getString("exam_date")?.let { raw ->
            val examDate = localDate(raw, "exam_date")
            if (examDate.isAfter(today())) throw IllegalArgumentException("exam_date must not be in the future")
            sets += "exam_date = ${bind(examDate)}"
        }
        if (body.containsKey("metadata")) {
            if (metadata != null) sets += "metadata = ${bind(metadata)}" else sets += "metadata = NULL"
        }
        val sql = "UPDATE healthcare.health_checkup_results SET ${sets.joinToString(", ")} WHERE id = \$1"
        return sql to Tuple.from(args)
    }

    /** 文本项修正语句（占位符 `$1`=id、`$2`=updated_at；可选列按出现顺序追加）。 */
    private fun textPatch(result: Row, body: JsonObject, now: OffsetDateTime): Pair<String, Tuple> {
        if (body.containsKey("value") || body.containsKey("unit") ||
            body.containsKey("ref_min") || body.containsKey("ref_max")
        ) {
            throw IllegalArgumentException("value/unit/ref_min/ref_max are only allowed for numeric items")
        }
        val args = mutableListOf<Any?>(result.getString("id"), now)
        fun bind(v: Any?): String = "\$${args.size + 1}".also { args.add(v) }
        val sets = mutableListOf("updated_at = \$2")
        body.getString("text_value")?.trim()?.takeIf(String::isNotBlank)?.let {
            if (it.length > 2000) throw IllegalArgumentException("text_value must not exceed 2000 characters")
            sets += "text_value = ${bind(it)}"
        }
        body.getValue("abnormal")?.let {
            sets += "abnormal = ${bind(it as? Boolean
                ?: throw IllegalArgumentException("abnormal must be a boolean"))}"
        }
        body.getString("exam_date")?.let { raw ->
            val examDate = localDate(raw, "exam_date")
            if (examDate.isAfter(today())) throw IllegalArgumentException("exam_date must not be in the future")
            sets += "exam_date = ${bind(examDate)}"
        }
        body.getValue("metadata")?.let {
            val metadata = jsonObject(body, "metadata")
                ?: throw IllegalArgumentException("metadata must be a JSON object")
            sets += "metadata = ${bind(metadata)}"
        }
        val sql = "UPDATE healthcare.health_checkup_results SET ${sets.joinToString(", ")} WHERE id = \$1"
        return sql to Tuple.from(args)
    }

    private data class ResolvedAnchor(val patientName: String, val encounterId: String?)

    /** 解析在册患者与活动锚点：ELDERLY_CARE 优先、OUTPATIENT 次之（可无锚点） */
    private fun resolveMemberAnchor(client: SqlClient, patientId: String): Future<ResolvedAnchor> {
        val sql = "SELECT DISTINCT ON (healthcare.patients.id) healthcare.patients.id AS patient_id, healthcare.patients.name, healthcare.patients.status, healthcare.encounters.id AS encounter_id " +
            "FROM healthcare.patients " +
            "LEFT JOIN healthcare.encounters ON healthcare.encounters.patient_id = healthcare.patients.id " +
            "AND healthcare.encounters.status = 'ACTIVE' AND healthcare.encounters.encounter_type IN ('ELDERLY_CARE', 'OUTPATIENT') " +
            "WHERE healthcare.patients.id = \$1 ORDER BY healthcare.patients.id ASC, $ANCHOR_PREFERENCE_SQL"
        return execute(client, sql, Tuple.of(patientId)).compose { rows ->
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
        val sql = "SELECT DISTINCT ON (healthcare.patients.id) healthcare.patients.id AS patient_id, healthcare.encounters.id AS encounter_id " +
            "FROM healthcare.patients " +
            "LEFT JOIN healthcare.encounters ON healthcare.encounters.patient_id = healthcare.patients.id " +
            "AND healthcare.encounters.status = 'ACTIVE' AND healthcare.encounters.encounter_type IN ('ELDERLY_CARE', 'OUTPATIENT') " +
            "WHERE healthcare.patients.status = 'ACTIVE' ORDER BY healthcare.patients.id ASC, $ANCHOR_PREFERENCE_SQL"
        return execute(client, sql, Tuple.tuple()).compose { rows ->
            val inserts = rows.map { row ->
                execute(
                    client,
                    INSERT_MEMBER_SQL,
                    Tuple.of(
                        Ulid.generate(),
                        checkupId,
                        row.getString("patient_id"),
                        false,
                        operator,
                        now,
                        now,
                        row.getString("encounter_id"),
                    ),
                )
            }
            Future.all(inserts).map { rows.size() }
        }
    }

    // ========================================================================
    //  行读取与通用辅助
    // ========================================================================

    private fun getCheckupRow(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            "SELECT id, checkup_year, name, status, start_date, end_date, operator, metadata, " +
                "created_at, updated_at FROM healthcare.health_checkups WHERE id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("health checkup not found: $id"))
        }

    private fun getMemberRow(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            "SELECT id, checkup_id, patient_id, encounter_id, checked, checked_at, operator, metadata, " +
                "created_at, updated_at FROM healthcare.health_checkup_members WHERE id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup member not found: $id"))
        }

    private fun getMemberVia(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, "$MEMBER_BASE_SQL WHERE healthcare.health_checkup_members.id = \$1", Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(memberJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup member not found: $id"))
        }

    private fun getResultRow(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            "SELECT id, checkup_id, member_id, patient_id, item_name, item_category, value, unit, " +
                "text_value, ref_min, ref_max, abnormal, exam_date, operator, vital_sign_id, " +
                "followup_plan_id, metadata, created_at, updated_at " +
                "FROM healthcare.health_checkup_results WHERE id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("checkup result not found: $id"))
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

    private fun validateEncounterOwnership(client: SqlClient, patientId: String, encounterId: String): Future<Row> =
        execute(
            client,
            "SELECT id, patient_id, admit_date FROM healthcare.encounters WHERE id = \$1",
            Tuple.of(encounterId),
        ).compose { rows ->
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

    /** 结果行写入参数（可空列按位置绑定 null，占位符顺序固定）。 */
    private fun resultInsertArgs(
        id: String,
        checkupId: String,
        memberId: String,
        fields: ResultFields,
        operator: String,
        now: OffsetDateTime,
    ): Tuple = Tuple.of(
        id,
        checkupId,
        memberId,
        fields.patientId,
        fields.itemName,
        fields.itemCategory,
        fields.abnormal,
        fields.examDate,
        operator,
        now,
        now,
        fields.value,
        fields.unit,
        fields.textValue,
        fields.refMin,
        fields.refMax,
        fields.metadata,
    )

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
