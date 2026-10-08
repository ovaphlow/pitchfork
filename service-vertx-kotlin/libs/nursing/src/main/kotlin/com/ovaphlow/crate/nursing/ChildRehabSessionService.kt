package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 儿童康复训练执行（会话）记录（054 B2，迁移 V410）。
 *
 * ## 唯一事实源（BL7 裁定）
 *
 * `nursing.nursing_child_rehab_sessions` 是「**是否完成训练**」的唯一事实源：
 * 儿童康复的训练执行**不复用** `nursing_task_executions` 打卡链（那条链承载养老护理任务与给药）。
 * 进度汇总一律由本表读时聚合。
 *
 * ## 规则
 *
 *  1. 会话必须挂在 `service_type = 'CHILD_REHAB'` 服务周期下的训练计划上（否则 400）；
 *     计划不存在 → 404。
 *  2. 一次训练 = 一行（append-only 台账）：同一计划同一天允许多次训练，**不设唯一约束**。
 *  3. `completion_ratio` ∈ (0, 1]（缺省 1）；`performance` / `note` 为自由文本
 *     （054 §5.8 未冻结该枚举，故不做白名单）。
 *  4. `operator` 取认证主体（042 口径）；`patient_id` 不落库，由
 *     `plan_id → nursing_plans → nursing_service_periods` 派生（与 B1 同口径）。
 *  5. **进度汇总**：`GET /child-rehab-sessions?plan_id=` 时附 `progress`
 *     （打卡次数 / 完成天数 / 计划天数 / 平均完成度 / 进度比），由 [summarize] 纯函数派生；
 *     进度比 = 完成天数 ÷ 计划天数（计划无起止日期时为 0；**不封顶**，>1 表示超出计划天数）。
 *  6. 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class ChildRehabSessionService(private val pool: Pool) {

    companion object {
        /** 完成度小数位（NUMERIC(5,4)）。 */
        private const val RATIO_SCALE = 4

        /** 平均值小数位。 */
        private const val AVG_SCALE = 2

        private const val PLAN_SQL =
            "SELECT pl.id, pl.period_id, pl.start_date, pl.end_date, pe.service_type " +
                "FROM nursing.nursing_plans pl " +
                "JOIN nursing.nursing_service_periods pe ON pe.id = pl.period_id " +
                "WHERE pl.id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_child_rehab_sessions " +
                "(id, plan_id, session_date, completion_ratio, performance, note, operator, metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val SELECT_COLUMNS =
            "s.id, s.plan_id, pe.patient_id, s.session_date, s.completion_ratio, s.performance, " +
                "s.note, s.operator, s.metadata, s.created_at"

        private const val SELECT_FROM =
            " FROM nursing.nursing_child_rehab_sessions s " +
                "JOIN nursing.nursing_plans pl ON pl.id = s.plan_id " +
                "JOIN nursing.nursing_service_periods pe ON pe.id = pl.period_id "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE s.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        /** 进度汇总用：某计划的全部会话（不受分页影响）。 */
        private const val PROGRESS_SQL =
            "SELECT session_date, completion_ratio FROM nursing.nursing_child_rehab_sessions " +
                "WHERE plan_id = \$1"

        /** 行 → 会话 JSON（`patient_id` 为 join 派生字段）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("plan_id", row.getString("plan_id"))
            .put("patient_id", row.getString("patient_id"))
            .put("session_date", row.getLocalDate("session_date")?.toString())
            .put("completion_ratio", row.getBigDecimal("completion_ratio")?.toDouble() ?: 0.0)
            .put("performance", row.getString("performance"))
            .put("note", row.getString("note"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

        /**
         * 进度汇总（纯函数，供单测直接锁定）：
         *  - `sessionCount` = 会话条数；
         *  - `completedDays` = 去重后的训练日期数；
         *  - `plannedDays` = 计划起止日期含端点的天数（缺失任一端为 `null`）；
         *  - `avgCompletionRatio` = 完成度均值（两位小数，空集为 0）；
         *  - `progressRatio` = 完成天数 ÷ 计划天数（四位小数；计划缺失或 ≤0 时为 0，不封顶）。
         */
        fun summarize(points: List<SessionPoint>, plannedDays: Int?): SessionSummary {
            val completedDays = points.map { it.sessionDate }.distinct().size
            val avg = if (points.isEmpty()) {
                0.0
            } else {
                round(points.sumOf { it.completionRatio } / points.size, AVG_SCALE)
            }
            val ratio = if (plannedDays != null && plannedDays > 0) {
                round(completedDays.toDouble() / plannedDays, RATIO_SCALE)
            } else {
                0.0
            }
            return SessionSummary(
                sessionCount = points.size,
                completedDays = completedDays,
                plannedDays = plannedDays,
                avgCompletionRatio = avg,
                progressRatio = ratio,
                lastSessionDate = points.maxOfOrNull { it.sessionDate }?.toString(),
            )
        }

        /** 计划起止（含端点）的天数；任一端缺失返回 `null`。 */
        fun plannedDaysOf(startDate: LocalDate?, endDate: LocalDate?): Int? {
            if (startDate == null || endDate == null) return null
            if (endDate.isBefore(startDate)) return null
            return (endDate.toEpochDay() - startDate.toEpochDay()).toInt() + 1
        }

        private fun round(value: Double, scale: Int): Double =
            BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_UP).toDouble()
    }

    /** 进度汇总的纯输入。 */
    data class SessionPoint(val sessionDate: LocalDate, val completionRatio: Double)

    /** 进度汇总结果（读时派生，不落库）。 */
    data class SessionSummary(
        val sessionCount: Int,
        val completedDays: Int,
        val plannedDays: Int?,
        val avgCompletionRatio: Double,
        val progressRatio: Double,
        val lastSessionDate: String?,
    )

    /**
     * 按计划打卡（追加一条会话）。参数非法抛 `IllegalArgumentException`（400）；
     * 计划不存在抛 [NotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val planId = body.getString("plan_id")?.trim()
        if (planId.isNullOrEmpty()) throw IllegalArgumentException("plan_id is required")

        val sessionDate = parseDate(body.getString("session_date"), "session_date")
        val completionRatio = parseRatio(body.getValue("completion_ratio"))
        val performance = body.getString("performance")?.trim()?.takeIf { it.isNotEmpty() }
        val note = body.getString("note")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = parseMetadata(body.getValue("metadata"))

        return loadChildRehabPlan(planId).compose {
            val id = Ulid.generate()
            val now = OffsetDateTime.now()
            val tuple = Tuple.tuple()
            tuple.addString(id)
            tuple.addString(planId)
            tuple.addLocalDate(sessionDate)
            tuple.addBigDecimal(completionRatio)
            tuple.addValue(performance)
            tuple.addValue(note)
            tuple.addValue(operator)
            tuple.addValue(metadata)
            tuple.addOffsetDateTime(now)

            pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("child rehab session not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /**
     * 列表（空结果也返回 `records: []` 与 `total: 0`）。
     * 过滤：`plan_id` / `patient_id`（跨表 join 派生）/ `from`+`to`（`session_date` 区间，含端点）。
     * 传入 `plan_id` 时附 `progress`（对该计划**全部**会话汇总，不受分页影响）。
     */
    fun list(
        planId: String? = null,
        patientId: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        planId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("s.plan_id = \$${values.size + 1}")
            values.add(it)
        }
        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("pe.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        from?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("s.session_date >= \$${values.size + 1}")
            values.add(parseDate(it, "from"))
        }
        to?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("s.session_date <= \$${values.size + 1}")
            values.add(parseDate(it, "to"))
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY s.session_date DESC, s.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        val requestedPlanId = planId?.trim()?.takeIf { it.isNotEmpty() }

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).compose { rows ->
                val result = JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))

                if (requestedPlanId == null) {
                    Future.succeededFuture(result)
                } else {
                    loadProgress(requestedPlanId).map { progress -> result.put("progress", progress) }
                }
            }
        }
    }

    // ========================================================================
    //  内部
    // ========================================================================

    private fun loadChildRehabPlan(planId: String): Future<JsonObject> =
        pool.preparedQuery(PLAN_SQL).execute(Tuple.of(planId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("child rehab plan not found: $planId"))
            if (row.getString("service_type") != ChildRehabPeriodService.SERVICE_TYPE)
                throw IllegalArgumentException("plan is not a child rehab plan: $planId")
            Future.succeededFuture(
                JsonObject()
                    .put("start_date", row.getLocalDate("start_date")?.toString())
                    .put("end_date", row.getLocalDate("end_date")?.toString()),
            )
        }

    /** 进度：对该计划全部会话汇总（不受分页影响）。 */
    private fun loadProgress(planId: String): Future<JsonObject> =
        pool.preparedQuery(PLAN_SQL).execute(Tuple.of(planId)).compose { planRows ->
            val plan = planRows.iterator().asSequence().firstOrNull()
            val plannedDays = plannedDaysOf(
                plan?.getLocalDate("start_date"),
                plan?.getLocalDate("end_date"),
            )
            pool.preparedQuery(PROGRESS_SQL).execute(Tuple.of(planId)).map { rows ->
                val points = rows.mapNotNull { row ->
                    val date = row.getLocalDate("session_date") ?: return@mapNotNull null
                    SessionPoint(
                        sessionDate = date,
                        completionRatio = row.getBigDecimal("completion_ratio")?.toDouble() ?: 0.0,
                    )
                }
                val summary = summarize(points, plannedDays)
                JsonObject()
                    .put("plan_id", planId)
                    .put("session_count", summary.sessionCount)
                    .put("completed_days", summary.completedDays)
                    .put("planned_days", summary.plannedDays)
                    .put("avg_completion_ratio", summary.avgCompletionRatio)
                    .put("progress_ratio", summary.progressRatio)
                    .put("last_session_date", summary.lastSessionDate)
            }
        }

    private fun parseDate(value: String?, field: String): LocalDate {
        val text = value?.trim()
        if (text.isNullOrEmpty()) throw IllegalArgumentException("$field is required")
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }
    }

    private fun parseRatio(value: Any?): BigDecimal {
        if (value == null) return BigDecimal.ONE.setScale(RATIO_SCALE, RoundingMode.HALF_UP)
        val number = when (value) {
            is Number -> BigDecimal(value.toString())
            is String -> value.trim().toBigDecimalOrNull()
            else -> null
        } ?: throw IllegalArgumentException("completion_ratio must be a number between 0 and 1")
        if (number <= BigDecimal.ZERO || number > BigDecimal.ONE)
            throw IllegalArgumentException("completion_ratio must be greater than 0 and at most 1")
        return number.setScale(RATIO_SCALE, RoundingMode.HALF_UP)
    }

    private fun parseMetadata(value: Any?): JsonObject? = when (value) {
        null -> null
        is JsonObject -> value
        else -> throw IllegalArgumentException("metadata must be an object")
    }

    private fun tupleOf(values: List<Any?>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
