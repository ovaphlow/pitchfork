package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.inventories.InventoryConsumptionService
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

/** 数量/成本序列化：以十进制文本（toPlainString）无损表达，不经过 Double。 */
private fun nursingDecimalText(value: Any?): String? = when (value) {
    null -> null
    else -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        ?: value.toString()
}

/** 十进制文本规范化（去除尾零与小数位差异），用于幂等比较键，不经过 Double */
private fun decimalNorm(value: Any?): String =
    value?.toString()?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: ""

/**
 * 护理任务执行服务。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 * `/today` 与 `/overdue` 共用同一份投影列与 FROM/JOIN（[EXECUTION_PROJECTION] /
 * [EXECUTION_FROM]），两个入口禁止各自复制查询。
 */
class TaskExecutionService(
    private val pool: Pool,
) {
    private val log = LoggerFactory.getLogger(TaskExecutionService::class.java)

    /** 把结果行中的时间列（TIMESTAMPTZ / 文本）统一解析为 OffsetDateTime */
    private fun rowTimestamp(value: Any?): OffsetDateTime? = when (value) {
        null -> null
        is OffsetDateTime -> value
        is String -> {
            try {
                OffsetDateTime.parse(value)
            } catch (_: Exception) {
                null
            }
        }

        else -> null
    }

    private fun executionWithSummaryJson(
        row: Row,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): JsonObject {
        val json = toJson(row)
        json.put("task_description", row.getValue("task_description")?.toString())
        json.put("task_type", row.getValue("task_type")?.toString())
        json.put("task_frequency_name", row.getValue("task_frequency_name")?.toString())
        json.put("task_period_id", row.getValue("task_period_id")?.toString())
        json.put("patient_id", row.getValue("patient_id")?.toString())
        json.put("patient_name", row.getValue("patient_name")?.toString())
        // 绑定医嘱的结构化明细与类型：任务描述是医生手写的自由文本，护士执行用药时
        // 必须能看到药品、剂量与途径（今日看板「药品 / 项目」列与执行弹窗使用）。
        json.put("order_details", row.getValue("order_details") as? JsonObject)
        json.put("order_type", row.getValue("order_type")?.toString())

        // 只读派生字段：全部复用同一次响应内固定的 now，避免同一响应内时间漂移
        val status = row.getValue("status")?.toString()
        val plannedTime = rowTimestamp(row.getValue("planned_time"))
        val (isOverdue, overdueMinutes) = computeOverdueFields(status, plannedTime, now)
        json.put("is_overdue", isOverdue)
        json.put("overdue_minutes", overdueMinutes)

        // 长挂执行派生字段（§3.2）：仅提醒，不触发任何写入
        val actualTime = rowTimestamp(row.getValue("actual_time"))
        val (inProgressMinutes, isStale) = computeInProgressFields(status, actualTime, now)
        json.put("in_progress_minutes", inProgressMinutes)
        json.put("is_stale", isStale)

        return json
    }

    companion object {
        /** 长挂执行阈值：进入 IN_PROGRESS 后超过该分钟数即标记 is_stale（1440 分钟 = 24 小时） */
        const val STALE_IN_PROGRESS_MINUTES = 1440

        /** 跨日逾期队列分页默认值 */
        const val OVERDUE_LIMIT_DEFAULT = 50

        /** 跨日逾期队列分页上限 */
        const val OVERDUE_LIMIT_MAX = 200

        /** 逾期收口默认阈值：1440 分钟 = 24 小时，与 [STALE_IN_PROGRESS_MINUTES] 同口径（030 §3 D5） */
        const val CONVERGE_MIN_OVERDUE_MINUTES_DEFAULT = 1440

        /** 逾期收口单次上限与默认值（030 §4.3：默认 200、最大 200） */
        const val CONVERGE_LIMIT_DEFAULT = 200

        /** 逾期收口单次上限（030 §4.3） */
        const val CONVERGE_LIMIT_MAX = 200

        /** 逾期收口留痕原因（030 §4.3 冻结文案，不得改写） */
        const val CONVERGE_REASON = "逾期未执行，批量标记漏执行"

        /** `nursing.nursing_task_executions` 显式列清单（与 [toJson] 读取的键一致）。 */
        private const val EXECUTION_COLUMNS =
            "id, task_id, planned_time, actual_time, executor, status, stock_operation_detail_id, " +
                "quantity, note, metadata, created_at"

        private const val EXECUTION_BY_ID_SQL =
            "SELECT $EXECUTION_COLUMNS FROM nursing.nursing_task_executions WHERE id = \$1"

        private const val EXECUTION_BY_ID_LOCKED_SQL =
            "SELECT $EXECUTION_COLUMNS FROM nursing.nursing_task_executions WHERE id = \$1 FOR UPDATE"

        private const val EXECUTION_INSERT_SQL =
            "INSERT INTO nursing.nursing_task_executions " +
                "(id, task_id, planned_time, actual_time, executor, status, note, metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        /** 排期生成：`(task_id, planned_time)` 唯一索引保证幂等（V401），冲突即跳过。 */
        private const val PLANNED_EXECUTION_INSERT_SQL =
            "INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, created_at) " +
                "VALUES (\$1, \$2, \$3, 'PENDING', \$4) ON CONFLICT (task_id, planned_time) DO NOTHING"

        /** 执行记录 → 任务类型（打卡权限门禁选码用），只读一列。 */
        private const val TASK_TYPE_OF_SQL =
            "SELECT t.task_type AS task_type FROM nursing.nursing_task_executions AS e " +
                "JOIN nursing.nursing_tasks AS t ON t.id = e.task_id WHERE e.id = \$1"

        private const val TASK_PERIOD_SQL =
            "SELECT t.period_id AS t_period_id FROM nursing.nursing_tasks AS t WHERE t.id = \$1"

        private const val PERIOD_PATIENT_SQL =
            "SELECT p.patient_id AS p_patient_id FROM nursing.nursing_service_periods AS p WHERE p.id = \$1"

        /** 耗材明细显式列清单（含 public.materials / public.lots 回显列）。 */
        private const val CONSUMPTION_COLUMNS =
            "cc.id AS id, cc.stock_operation_detail_id AS stock_operation_detail_id, " +
                "cc.stock_id AS stock_id, cc.material_id AS material_id, " +
                "mat.name AS material_name, cc.lot_id AS lot_id, lot.batch_no AS batch_no, " +
                "cc.warehouse AS warehouse, cc.quantity AS quantity, cc.unit AS unit, " +
                "cc.unit_cost AS unit_cost, cc.total_cost AS total_cost, cc.created_at AS created_at"

        private const val CONSUMPTION_FROM =
            " FROM nursing.nursing_task_execution_consumptions AS cc" +
                " LEFT JOIN public.materials AS mat ON mat.id = cc.material_id" +
                " LEFT JOIN public.lots AS lot ON lot.id = cc.lot_id"

        private const val EXECUTION_CONSUMPTIONS_SQL =
            "SELECT $CONSUMPTION_COLUMNS$CONSUMPTION_FROM WHERE cc.task_execution_id = \$1"

        private const val EXECUTION_CONSUMPTIONS_ORDERED_SQL =
            "SELECT $CONSUMPTION_COLUMNS$CONSUMPTION_FROM " +
                "WHERE cc.task_execution_id = \$1 ORDER BY cc.created_at ASC"

        private const val CONSUMPTION_SUMMARY_SQL =
            "SELECT count(*) AS cnt, cc.warehouse AS warehouse, " +
                "sum(cc.total_cost) AS total_cost " +
                "FROM nursing.nursing_task_execution_consumptions AS cc " +
                "WHERE cc.task_execution_id = \$1 GROUP BY cc.warehouse"

        private const val CONSUMPTION_SUMMARY_BATCH_SQL =
            "SELECT cc.task_execution_id AS task_execution_id, count(*) AS cnt, " +
                "cc.warehouse AS warehouse, sum(cc.total_cost) AS total_cost " +
                "FROM nursing.nursing_task_execution_consumptions AS cc " +
                "WHERE cc.task_execution_id IN (%s) " +
                "GROUP BY cc.task_execution_id, cc.warehouse"

        /** 016 单一基础单位：一条执行耗材关联（快照数量/单位/成本同事务写入）。 */
        private const val CONSUMPTION_INSERT_SQL =
            "INSERT INTO nursing.nursing_task_execution_consumptions " +
                "(id, task_execution_id, stock_operation_detail_id, stock_id, material_id, lot_id, " +
                "warehouse, quantity, unit, unit_cost, total_cost, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        /** 多条耗材时清空旧兼容字段。 */
        private const val CLEAR_LEGACY_FIELDS_SQL =
            "UPDATE nursing.nursing_task_executions " +
                "SET stock_operation_detail_id = NULL, quantity = NULL WHERE id = \$1"

        /** 仅有 1 条耗材时同步旧兼容字段。 */
        private const val SYNC_LEGACY_FIELDS_SQL =
            "UPDATE nursing.nursing_task_executions " +
                "SET stock_operation_detail_id = \$2, quantity = \$3 WHERE id = \$1"

        /**
         * 执行记录列表的统一投影列：列名与 `toJson` 期望的键一一对应。
         * `/today`、`/overdue` 必须共用，禁止各自复制一份查询。
         */
        private const val EXECUTION_PROJECTION =
            "e.id AS id, e.task_id AS task_id, e.planned_time AS planned_time, " +
                "e.actual_time AS actual_time, e.executor AS executor, e.status AS status, " +
                "e.stock_operation_detail_id AS stock_operation_detail_id, " +
                "e.quantity AS quantity, e.note AS note, e.metadata AS metadata, " +
                "e.created_at AS created_at, t.description AS task_description, " +
                "t.task_type AS task_type, t.frequency_name AS task_frequency_name, " +
                "t.period_id AS task_period_id, p.patient_id AS patient_id, " +
                "pat.name AS patient_name, mo.order_details AS order_details, " +
                "mo.order_type AS order_type"

        /**
         * 执行记录查询的公共 FROM/JOIN：
         * e = nursing.nursing_task_executions，t = nursing.nursing_tasks，
         * p = nursing.nursing_service_periods，pat = healthcare.patients，
         * mo = healthcare.medical_orders（经 t.order_item_id 绑定，未绑定医嘱时为 null）。
         * `/today` 与 `/overdue` 的列表、计数与聚合全部复用，保证口径一致。
         *
         * mo 的 JOIN 落在 `medical_orders.id`（V500 已确认是 PRIMARY KEY）上，至多 1:1，
         * 因此计数/聚合行数不变；共用同一 FROM 是本节既有约定（两个入口禁止各自复制查询）。
         */
        private const val EXECUTION_FROM =
            " FROM nursing.nursing_task_executions AS e" +
                " JOIN nursing.nursing_tasks AS t ON t.id = e.task_id" +
                " LEFT JOIN nursing.nursing_service_periods AS p ON p.id = t.period_id" +
                " LEFT JOIN healthcare.patients AS pat ON pat.id = p.patient_id" +
                " LEFT JOIN healthcare.medical_orders AS mo ON t.order_item_id = mo.id"

        /**
         * §4.2 聚合：日期窗口内的各状态计数 + 全量逾期数，一条 SQL 取回（`count(*) FILTER (WHERE ...)`）。
         *
         * `$1` = 日期窗口下界，`$2` = 上界，`$3` = now；
         * `status_totals` 落在日期窗口内，`overdue_total_all` 不带日期窗口。
         */
        private const val TODAY_META_SQL =
            "SELECT" +
                " count(*) FILTER (WHERE e.planned_time >= \$1 AND e.planned_time < \$2" +
                " AND e.status = 'PENDING') AS status_pending," +
                " count(*) FILTER (WHERE e.planned_time >= \$1 AND e.planned_time < \$2" +
                " AND e.status = 'IN_PROGRESS') AS status_in_progress," +
                " count(*) FILTER (WHERE e.planned_time >= \$1 AND e.planned_time < \$2" +
                " AND e.status = 'COMPLETED') AS status_completed," +
                " count(*) FILTER (WHERE e.planned_time >= \$1 AND e.planned_time < \$2" +
                " AND e.status = 'SKIPPED') AS status_skipped," +
                " count(*) FILTER (WHERE e.planned_time >= \$1 AND e.planned_time < \$2" +
                " AND e.status = 'CANCELLED') AS status_cancelled," +
                " count(*) FILTER (WHERE e.status IN ('PENDING', 'IN_PROGRESS')" +
                " AND e.planned_time < \$3) AS overdue_total_all" +
                EXECUTION_FROM

        /** 逾期收口候选（多取 1 条仅用于判定 truncated）。 */
        private const val CONVERGE_CANDIDATE_SQL =
            "SELECT e.id AS id FROM nursing.nursing_task_executions AS e" +
                " JOIN nursing.nursing_tasks AS t ON t.id = e.task_id" +
                " LEFT JOIN nursing.nursing_service_periods AS p ON p.id = t.period_id" +
                " WHERE e.status IN ('PENDING', 'IN_PROGRESS') AND e.planned_time < \$1"

        /** 逾期收口：`metadata.convergence` 浅合并，不覆盖其它键；终态守卫保证幂等。 */
        private const val CONVERGE_UPDATE_SQL =
            "UPDATE nursing.nursing_task_executions SET status = \$1, " +
                "metadata = COALESCE(metadata, '{}'::jsonb) || \$2::jsonb " +
                "WHERE id = \$3 AND status IN ('PENDING', 'IN_PROGRESS')"

        /**
         * 执行统计聚合投影（全局汇总与按执行人分组逐字共用）。
         *
         * 045（2026-10-04 QA 口径修订）：应完成 = 已到计划时间 **或 已完成**
         * （`$1` = now）；分子 completed_due_total 只认状态，不再受 planned_time 限制。
         * 逾期定义不变：仍只认 PENDING/IN_PROGRESS 且已过计划时间。
         */
        private const val STATISTICS_AGGREGATES =
            "count(*) AS scheduled_total," +
                " count(*) FILTER (WHERE e.status = 'PENDING') AS pending_total," +
                " count(*) FILTER (WHERE e.status = 'IN_PROGRESS') AS in_progress_total," +
                " count(*) FILTER (WHERE e.status = 'COMPLETED') AS completed_total," +
                " count(*) FILTER (WHERE e.status = 'SKIPPED') AS skipped_total," +
                " count(*) FILTER (WHERE e.status = 'CANCELLED') AS cancelled_total," +
                " count(*) FILTER (WHERE e.planned_time < \$1 OR e.status = 'COMPLETED')" +
                " AS due_total," +
                " count(*) FILTER (WHERE e.status = 'COMPLETED') AS completed_due_total," +
                " count(*) FILTER (WHERE e.status IN ('PENDING', 'IN_PROGRESS')" +
                " AND e.planned_time < \$1) AS overdue_total"

        private const val STATISTICS_FROM =
            " FROM nursing.nursing_task_executions AS e" +
                " JOIN nursing.nursing_tasks AS t ON t.id = e.task_id"

        /**
         * 执行状态机（003 冻结）。
         *
         * `IN_PROGRESS → SKIPPED` 的扩条来自计划
         * `docs/plans/030.aceso-fee-catalog-and-operational-gaps.md` §3 D5 / §4.3：
         * 逾期收口要把「已开始但逾期未完成」的执行显式置为漏执行（`SKIPPED`），
         * 否则收口覆盖不到 `IN_PROGRESS` 行（027 只交付了跨日逾期队列与 `is_stale` 派生，
         * 没有任何终态收口入口）。这是有意为之的口径统一：通用 `PATCH /:id/status`
         * 因此也支持该转移，仅新增一条允许转移，其余语义逐字不变（终态仍无出边）。
         */
        private val VALID_STATUS_TRANSITIONS =
            mapOf(
                "PENDING" to listOf("IN_PROGRESS", "SKIPPED", "CANCELLED"),
                "IN_PROGRESS" to listOf("COMPLETED", "SKIPPED", "CANCELLED"),
                "COMPLETED" to emptyList(),
                "SKIPPED" to emptyList(),
                "CANCELLED" to emptyList(),
            )

        /** 生成 `$1, $2, ...` 占位符串（[count] 个）。 */
        private fun placeholders(count: Int): String = (1..count).joinToString(", ") { "\$$it" }

        /**
         * 计算单条执行记录的逾期派生字段。
         * 纯函数，不依赖任何外部状态。
         */
        /**
         * 排期生成与今日看板的任务参与条件：
         * - 任务归属活跃照护周期（既有行为，覆盖 NURSING / MEDICATION / LIVING_CARE 等全部类型）；
         * - 或为无周期归属的全院性康复活动（task_type = REHABILITATION 且 period_id 为空，
         *   机构服务项目不挂老人，如集体操课、文娱活动）。
         * 仅 REHABILITATION 允许无周期归属参与排期与今日看板，其它任务类型行为不变。
         *
         * 原生参数化 SQL 片段（枚举值为固定字面量，无绑定参数）：
         * 调用方提供任务表与周期表的别名，避免两处口径漂移。
         */
        fun taskParticipationCondition(
            taskAlias: String = "t",
            periodAlias: String = "p",
        ): String =
            "$periodAlias.status = 'ACTIVE'" +
                " OR ($taskAlias.period_id IS NULL AND $taskAlias.task_type = 'REHABILITATION')"

        fun computeOverdueFields(
            status: String?,
            plannedTime: OffsetDateTime?,
            now: OffsetDateTime,
        ): Pair<Boolean, Int?> {
            if (plannedTime == null) return Pair(false, null)
            val isOverdue =
                (status == "PENDING" || status == "IN_PROGRESS") &&
                    plannedTime.isBefore(now)
            val minutes = if (isOverdue) Duration.between(plannedTime, now).toMinutes().toInt() else null
            return Pair(isOverdue, minutes)
        }

        /**
         * 计算单条执行记录的长挂派生字段（§3.2）。
         * 纯函数，不依赖任何外部状态：
         * - 仅 `IN_PROGRESS` 且 `actual_time != null` 才产出分钟数，其余返回 null；
         * - 分钟数取 `Duration.between(actual_time, now).toMinutes()` 并截断到 0 以上，
         *   因此 `actual_time` 晚于 `now` 时为 0（不出现负数）；
         * - `is_stale` 仅在分钟数 >= [STALE_IN_PROGRESS_MINUTES] 时为真，终态恒为假。
         */
        fun computeInProgressFields(
            status: String?,
            actualTime: OffsetDateTime?,
            now: OffsetDateTime,
        ): Pair<Int?, Boolean> {
            if (status != "IN_PROGRESS" || actualTime == null) return Pair(null, false)
            val minutes = maxOf(0L, Duration.between(actualTime, now).toMinutes()).toInt()
            return Pair(minutes, minutes >= STALE_IN_PROGRESS_MINUTES)
        }

        /**
         * 跨日逾期队列分页收敛（§4.3）：limit 默认 50 并收敛到 1..200，offset 默认 0 且不小于 0。
         * 纯函数，便于单测覆盖边界。
         */
        fun normalizeOverduePaging(
            limit: Int?,
            offset: Int?,
        ): Pair<Int, Int> {
            val normalizedLimit = (limit ?: OVERDUE_LIMIT_DEFAULT).coerceIn(1, OVERDUE_LIMIT_MAX)
            val normalizedOffset = (offset ?: 0).coerceAtLeast(0)
            return Pair(normalizedLimit, normalizedOffset)
        }

        /**
         * 逾期收口请求归一化（030 §4.3），纯函数，便于单测覆盖边界：
         * - `minOverdueMinutes` 默认 [CONVERGE_MIN_OVERDUE_MINUTES_DEFAULT]（1440）；
         *   显式值必须 `>= 1`，否则抛 [IllegalArgumentException]（路由层映射 400）；
         * - `limit` 默认 [CONVERGE_LIMIT_DEFAULT]（200）并收敛到 `1..`[CONVERGE_LIMIT_MAX]，
         *   沿用 [normalizeOverduePaging] 的「越界钳制」口径（不报错）。
         */
        fun normalizeConvergeRequest(
            minOverdueMinutes: Int?,
            limit: Int?,
        ): Pair<Int, Int> {
            val effectiveMinutes = minOverdueMinutes ?: CONVERGE_MIN_OVERDUE_MINUTES_DEFAULT
            require(effectiveMinutes >= 1) { "min_overdue_minutes must be an integer >= 1" }
            val effectiveLimit = (limit ?: CONVERGE_LIMIT_DEFAULT).coerceIn(1, CONVERGE_LIMIT_MAX)
            return Pair(effectiveMinutes, effectiveLimit)
        }

        /**
         * 逾期收口留痕载荷（030 §4.3）：合并写入 `metadata.convergence`，不覆盖其它键。纯函数。
         *
         * @param at 收敛时刻，序列化为 ISO-8601（[OffsetDateTime.toString]）
         */
        fun convergencePayload(
            minOverdueMinutes: Int,
            at: OffsetDateTime,
        ): JsonObject =
            JsonObject()
                .put("reason", CONVERGE_REASON)
                .put("at", at.toString())
                .put("min_overdue_minutes", minOverdueMinutes)

        fun toJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("task_id", row.getValue("task_id")?.toString())
                .put("planned_time", row.getValue("planned_time")?.toString())
                .put("actual_time", row.getValue("actual_time")?.toString())
                .put("executor", row.getValue("executor")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("quantity", nursingDecimalText(row.getValue("quantity")))
                .put("note", row.getValue("note")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())

        /**
         * 计算计划完成率。
         * 纯函数，不依赖任何外部状态。
         *
         * @param completedDueTotal 应完成的已完成数量
         * @param dueTotal 应完成总量
         * @return 完成率百分比（四舍五入到两位小数），dueTotal 为 0 时返回 null
         */
        fun completionRate(
            completedDueTotal: Long,
            dueTotal: Long,
        ): Double? {
            if (dueTotal == 0L) return null
            return BigDecimal(completedDueTotal)
                .multiply(BigDecimal(100))
                .divide(BigDecimal(dueTotal), 2, RoundingMode.HALF_UP)
                .toDouble()
        }
    }

    // ========================================================================
    //  现有 CRUD — 保持不变，但保护旧库存字段
    // ========================================================================

    fun create(body: JsonObject, executor: String?): Future<JsonObject> {
        val taskId = body.getString("task_id")
        if (taskId.isNullOrBlank()) {
            return Future.failedFuture(IllegalArgumentException("task_id is required"))
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        // 客户端不再允许直接写 stock_operation_detail_id / quantity
        val tuple = Tuple.of(
            id,
            taskId,
            body.getString("planned_time")?.let { OffsetDateTime.parse(it) },
            body.getString("actual_time")?.let { OffsetDateTime.parse(it) },
            executor,
            "PENDING",
            body.getString("note"),
            if (body.containsKey("metadata")) body.getJsonObject("metadata") else null,
            now,
        )

        return pool.preparedQuery(EXECUTION_INSERT_SQL).execute(tuple).map {
            JsonObject()
                .put("id", id)
                .put("task_id", taskId)
                .put("planned_time", body.getString("planned_time"))
                .put("actual_time", body.getString("actual_time"))
                .put("executor", executor)
                .put("status", "PENDING")
                .put("note", body.getString("note"))
                .put("metadata", body.getJsonObject("metadata"))
                .put("created_at", now.toString())
        }
    }

    fun list(
        taskId: String? = null,
        executor: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        taskId?.let { where.add("task_id = ${bind(it)}") }
        executor?.let { where.add("executor = ${bind(it)}") }
        status?.let { where.add("status = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_task_executions$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $EXECUTION_COLUMNS FROM nursing.nursing_task_executions$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
                    .flatMap { dataRows ->
                        val records = JsonArray()
                        val execIds = mutableListOf<String>()
                        for (row in dataRows) records.add(toJson(row))
                        for (row in dataRows) {
                            row.getValue("id")?.toString()?.let { execIds.add(it) }
                        }
                        // 批量加载耗材摘要
                        loadConsumptionSummaryBatch(execIds).map { summary ->
                            for (record in records) {
                                val id = (record as JsonObject).getString("id")
                                id?.let { summary[it]?.let { s -> record.put("consumption_summary", s) } }
                            }
                            JsonObject()
                                .put("records", records)
                                .put("meta", JsonObject().put("total", total))
                        }
                    }
            }
    }

    /**
     * 045：单条执行记录所属任务的 `task_type`（`nursing_task_executions` → `nursing_tasks`）。
     *
     * 用途：打卡（`PATCH /executions/:id/status`）的权限门禁按任务类型选码，
     * 而 task_type 在任务表上，所以门禁前必须先把"这条记录是什么任务"读出来。
     * 只读一列，不做任何权限判定；记录不存在或 join 不到任务时回 `null`，
     * 由调用方按最严的权限码处理（不借 403/404 差异泄露记录是否存在）。
     */
    fun taskTypeOf(id: String): Future<String?> =
        pool.preparedQuery(TASK_TYPE_OF_SQL).execute(Tuple.of(id)).map { rows ->
            if (rows.size() == 0) null else rows.iterator().next().getValue("task_type")?.toString()
        }

    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(EXECUTION_BY_ID_SQL).execute(Tuple.of(id))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("execution not found: $id"))
                } else {
                    val record = toJson(rows.iterator().next())
                    loadSingleConsumptionSummary(id).map { summary ->
                        if (summary != null) record.put("consumption_summary", summary)
                        record
                    }
                }
            }

    /**
     * 更新执行状态（打卡路径）。
     *
     * @param executor 执行人（登录 subject_id），由路由从认证会话解析传入；
     *   非空时随状态流转落库（开始/完成/跳过/取消均记录操作人）。
     */
    fun updateStatus(
        id: String,
        newStatus: String,
        note: String? = null,
        executor: String? = null,
    ): Future<JsonObject> {
        if (newStatus.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("status is required"))
        }

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
            if (newStatus !in allowedNext) {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus"),
                )
            }

            val now = OffsetDateTime.now()
            val sets = mutableListOf<String>()
            val args = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            sets.add("status = ${bind(newStatus)}")

            if (newStatus == "IN_PROGRESS" && existing.getString("actual_time") == null) {
                sets.add("actual_time = ${bind(now)}")
            }
            if (newStatus == "COMPLETED" && existing.getString("actual_time") == null) {
                sets.add("actual_time = ${bind(now)}")
            }

            // 支持保存备注/跳过/取消原因
            if (!note.isNullOrBlank()) {
                sets.add("note = ${bind(note.trim())}")
            }

            // 执行人（登录 subject_id）：无耗材打卡路径此前不落库，F6 验收要求记录
            if (!executor.isNullOrBlank()) {
                sets.add("executor = ${bind(executor.trim())}")
            }

            val idPlaceholder = bind(id)
            val updateSql =
                "UPDATE nursing.nursing_task_executions SET ${sets.joinToString(", ")} " +
                    "WHERE id = $idPlaceholder"

            pool.preparedQuery(updateSql).execute(Tuple.from(args))
                .flatMap { get(id) }
        }
    }

    fun update(
        id: String,
        body: JsonObject,
        executor: String?,
    ): Future<JsonObject> =
        get(id).flatMap { _ ->
            val sets = mutableListOf<String>()
            val args = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            sets.add("executor = ${bind(executor)}")

            if (body.containsKey("actual_time")) {
                sets.add("actual_time = ${bind(body.getString("actual_time")?.let { OffsetDateTime.parse(it) })}")
            }
            if (body.containsKey("planned_time")) {
                sets.add("planned_time = ${bind(body.getString("planned_time")?.let { OffsetDateTime.parse(it) })}")
            }
            if (body.containsKey("note")) {
                sets.add("note = ${bind(body.getString("note"))}")
            }
            // 保护旧库存字段：拒绝外部直接写入
            if (body.containsKey("metadata")) {
                sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")
            }

            val idPlaceholder = bind(id)
            val updateSql =
                "UPDATE nursing.nursing_task_executions SET ${sets.joinToString(", ")} " +
                    "WHERE id = $idPlaceholder"

            pool.preparedQuery(updateSql).execute(Tuple.from(args))
                .flatMap { get(id) }
        }

    // ========================================================================
    //  带耗材的完成任务
    // ========================================================================

    /** 016 单一基础单位：客户端只提交 stock_id + quantity（基础数量）。 */
    data class ConsumptionInput(
        val stockId: String,
        val quantity: BigDecimal,
    )

    /**
     * 在同一事务内完成任务并扣减库存。
     */
    fun completeExecutionWithConsumptions(
        id: String,
        note: String?,
        consumptions: List<ConsumptionInput>,
        authenticatedSubject: String,
    ): Future<JsonObject> {
        // 无耗材时走原有的 updateStatus 路径（同样记录执行人）
        if (consumptions.isEmpty()) {
            return updateStatus(id, "COMPLETED", note, executor = authenticatedSubject)
        }
        if (consumptions.map { it.stockId }.distinct().size != consumptions.size) {
            return Future.failedFuture(IllegalArgumentException("duplicate stock_id"))
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction { connection ->
                // 锁定执行记录
                connection.preparedQuery(EXECUTION_BY_ID_LOCKED_SQL).execute(Tuple.of(id))
                    .compose { lockedRows ->
                        if (lockedRows.size() == 0) {
                            return@compose Future.failedFuture(NotFoundException("execution not found: $id"))
                        }

                        val lockedRow = lockedRows.iterator().next()
                        val execStatus = lockedRow.getValue("status")?.toString() ?: ""
                        val taskId = lockedRow.getValue("task_id")?.toString()
                            ?: return@compose Future.failedFuture(IllegalArgumentException("task_id missing"))

                        // === 幂等重试处理 ===
                        if (execStatus == "COMPLETED") {
                            // 已完成的执行 — 加载既有耗材，检查是否相同请求
                            return@compose loadExistingExecutionWithConsumption(connection, id)
                                .compose { existingResult ->
                                    val existingConsumptions = existingResult.getJsonArray("consumptions")
                                    if (existingConsumptions == null || existingConsumptions.size() == 0) {
                                        // 原本没有耗材，但重试请求带了耗材 → 冲突
                                        return@compose Future.failedFuture(
                                            ConflictException(
                                                "execution already completed without consumptions, cannot add consumptions now",
                                            ),
                                        )
                                    }
                                    // 比较耗材清单 — 按 stock_id + 基础数量（十进制文本规范化，
                                    // 不经过 Double，避免二进制尾差）
                                    val existingSummary =
                                        existingConsumptions
                                            .map {
                                                val obj = it as JsonObject
                                                "${obj.getString("stock_id")}|${decimalNorm(obj.getValue("quantity"))}"
                                            }.sorted()
                                    val newSummary =
                                        consumptions
                                            .map {
                                                "${it.stockId}|${decimalNorm(it.quantity)}"
                                            }.sorted()

                                    if (existingSummary == newSummary) {
                                        // 完全相同 → 返回既有结果
                                        return@compose Future.succeededFuture(existingResult)
                                    } else {
                                        // 不一致 → 冲突
                                        return@compose Future.failedFuture(
                                            ConflictException("execution already completed with different consumptions, refresh and retry"),
                                        )
                                    }
                                }
                        }

                        // 状态校验：必须是可转为 COMPLETED 的状态
                        val currentStatus = execStatus
                        val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
                        if ("COMPLETED" !in allowedNext) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("cannot transition from $currentStatus to COMPLETED"),
                            )
                        }

                        // 查询任务和周期信息
                        connection.preparedQuery(TASK_PERIOD_SQL).execute(Tuple.of(taskId))
                            .compose { taskRows ->
                                val periodId =
                                    if (taskRows.size() > 0) {
                                        taskRows.iterator().next().getValue("t_period_id")?.toString()
                                    } else {
                                        null
                                    }

                                connection.preparedQuery(PERIOD_PATIENT_SQL).execute(Tuple.of(periodId))
                                    .compose { periodRows ->
                                        val patientId: String =
                                            if (periodRows.size() > 0) {
                                                periodRows.iterator().next().getValue("p_patient_id")?.toString() ?: ""
                                            } else {
                                                ""
                                            }

                                        // 调用库存消耗服务（内部验证仓库一致性、库存量、拆零换算等）
                                        val consumptionService = InventoryConsumptionService()
                                        val command =
                                            InventoryConsumptionService.NursingConsumptionCommand(
                                                    items =
                                                        consumptions.map { ci ->
                                                            InventoryConsumptionService.ConsumptionItem(
                                                                stockId = ci.stockId,
                                                                quantity = ci.quantity,
                                                            )
                                                        },
                                                taskExecutionId = id,
                                                taskId = taskId,
                                                periodId = periodId ?: "",
                                                patientId = patientId,
                                                executor = authenticatedSubject,
                                                businessTime = now,
                                            )

                                        consumptionService
                                            .consumeForNursingExecution(connection, command)
                                            .compose { result ->
                                                insertConsumptions(connection, id, result, now)
                                                    .compose { syncLegacyFields(connection, id, result) }
                                                    .compose {
                                                        val sets = mutableListOf(
                                                            "status = 'COMPLETED'",
                                                            "actual_time = \$2",
                                                        )
                                                        val args = mutableListOf<Any?>(id, now)
                                                        if (!note.isNullOrBlank()) {
                                                            sets.add("note = \$3")
                                                            args.add(note.trim())
                                                        }
                                                        val updateSql =
                                                            "UPDATE nursing.nursing_task_executions " +
                                                                "SET ${sets.joinToString(", ")} WHERE id = \$1"
                                                        connection.preparedQuery(updateSql)
                                                            .execute(Tuple.from(args))
                                                    }.compose { buildConsumptionResponse(connection, id, result, warehouse = "") }
                                            }
                                    }
                            }
                    }
        }.map { result: JsonObject? -> result ?: throw IllegalStateException("completion transaction returned no execution") }
    }

    /** 加载已有完成的执行及其耗材 */
    private fun loadExistingExecutionWithConsumption(
        connection: SqlConnection,
        id: String,
    ): Future<JsonObject> =
        connection.preparedQuery(EXECUTION_BY_ID_SQL).execute(Tuple.of(id))
            .compose { rows ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(NotFoundException("execution not found"))
                }
                val record = toJson(rows.iterator().next())

                // 加载耗材明细
                connection.preparedQuery(EXECUTION_CONSUMPTIONS_SQL).execute(Tuple.of(id))
                    .map { consRows ->
                        val consumptions = JsonArray()
                        var totalCost = BigDecimal.ZERO
                        var warehouse = ""
                        for (cr in consRows) {
                            val c =
                                JsonObject()
                                    .put("id", cr.getValue("id")?.toString())
                                    .put("stock_operation_detail_id", cr.getValue("stock_operation_detail_id")?.toString())
                                    .put("stock_id", cr.getValue("stock_id")?.toString())
                                    .put("material_id", cr.getValue("material_id")?.toString())
                                    .put("material_name", cr.getValue("material_name")?.toString())
                                    .put("lot_id", cr.getValue("lot_id")?.toString())
                                    .put("batch_no", cr.getValue("batch_no")?.toString())
                                    .put("warehouse", cr.getValue("warehouse")?.toString())
                                    .put("quantity", nursingDecimalText(cr.getValue("quantity")))
                                    .put("unit", cr.getValue("unit")?.toString())
                                    .put("unit_cost", nursingDecimalText(cr.getValue("unit_cost")))
                                    .put("total_cost", nursingDecimalText(cr.getValue("total_cost")))
                            consumptions.add(c)
                            warehouse = cr.getValue("warehouse")?.toString() ?: ""
                            totalCost = totalCost.add(
                                cr.getValue("total_cost")?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                            )
                        }
                        record.put("consumptions", consumptions)
                        record.put(
                            "consumption_summary",
                            JsonObject()
                                .put("count", consumptions.size())
                                .put("warehouse", warehouse)
                                .put("total_cost", totalCost.stripTrailingZeros().toPlainString()),
                        )
                        record
                    }
            }

    /** 构建耗材完成响应 */
    private fun buildConsumptionResponse(
        connection: SqlConnection,
        id: String,
        result: InventoryConsumptionService.ConsumptionResult,
        warehouse: String,
    ): Future<JsonObject> =
        connection.preparedQuery(EXECUTION_BY_ID_SQL).execute(Tuple.of(id))
            .compose { rows ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(NotFoundException("execution not found after update"))
                }
                val record = toJson(rows.iterator().next())
                val summary =
                    JsonObject()
                        .put("count", result.detailResults.size)
                        .put("warehouse", warehouse.ifBlank { result.detailResults.firstOrNull()?.warehouse ?: "" })
                        .put("total_cost", nursingDecimalText(result.detailResults.fold(BigDecimal.ZERO) { total, detail -> total.add(detail.totalCost) }))

                val details = JsonArray()
                for (dr in result.detailResults) {
                    details.add(
                        JsonObject()
                            .put("id", dr.detailId)
                            .put("stock_operation_detail_id", dr.detailId)
                            .put("stock_id", dr.stockId)
                            .put("material_id", dr.materialId)
                            .put("lot_id", dr.lotId)
                            .put("warehouse", dr.warehouse)
                            .put("quantity", nursingDecimalText(dr.quantity))
                            .put("unit", dr.unit)
                            .put("unit_cost", nursingDecimalText(dr.unitCost))
                            .put("total_cost", nursingDecimalText(dr.totalCost))
                    )
                }
                record.put("consumption_summary", summary)
                record.put("consumptions", details)
                Future.succeededFuture(record)
            }

    /** 插入护理执行耗材关联 */
    private fun insertConsumptions(
        connection: SqlConnection,
        executionId: String,
        result: InventoryConsumptionService.ConsumptionResult,
        now: OffsetDateTime,
    ): Future<Void?> {
        fun process(index: Int): Future<Void?> {
            if (index >= result.detailResults.size) {
                return Future.succeededFuture(null as Void?)
            }

            val dr = result.detailResults[index]
            val tuple = Tuple.of(
                Ulid.generate(), executionId, dr.detailId, dr.stockId, dr.materialId, dr.lotId,
                dr.warehouse, dr.quantity, dr.unit, dr.unitCost, dr.totalCost, now,
            )

            return connection.preparedQuery(CONSUMPTION_INSERT_SQL).execute(tuple)
                .compose { process(index + 1) }
        }

        return process(0)
    }

    /** 仅有 1 条耗材时同步旧兼容字段 */
    private fun syncLegacyFields(
        connection: SqlConnection,
        executionId: String,
        result: InventoryConsumptionService.ConsumptionResult,
    ): Future<Void?> {
        if (result.detailResults.size != 1) {
            // 多条明细时清空旧字段
            return connection.preparedQuery(CLEAR_LEGACY_FIELDS_SQL).execute(Tuple.of(executionId))
                .map { null as Void? }
        }

        val dr = result.detailResults.single()
        return connection.preparedQuery(SYNC_LEGACY_FIELDS_SQL)
            .execute(Tuple.of(executionId, dr.detailId, dr.quantity))
            .map { null as Void? }
    }

    // ========================================================================
    //  耗材摘要/明细查询
    // ========================================================================

    /** 批量加载耗材摘要 */
    fun loadConsumptionSummaryBatch(executionIds: List<String>): Future<Map<String, JsonObject>> {
        if (executionIds.isEmpty()) {
            return Future.succeededFuture(emptyMap())
        }

        val sql = CONSUMPTION_SUMMARY_BATCH_SQL.format(placeholders(executionIds.size))
        return pool.preparedQuery(sql).execute(Tuple.from(executionIds))
            .map { rows ->
                val result = mutableMapOf<String, JsonObject>()
                for (row in rows) {
                    val execId = row.getValue("task_execution_id")?.toString() ?: continue
                    result[execId] =
                        JsonObject()
                            .put("count", row.getValue("cnt") as? Long ?: 0L)
                            .put("warehouse", row.getValue("warehouse")?.toString())
                            .put("total_cost", nursingDecimalText(row.getValue("total_cost")) ?: "0")
                }
                result
            }
    }

    /** 加载单条执行耗材摘要 */
    fun loadSingleConsumptionSummary(executionId: String): Future<JsonObject?> =
        pool.preparedQuery(CONSUMPTION_SUMMARY_SQL).execute(Tuple.of(executionId))
            .map { rows ->
                if (rows.size() == 0) {
                    null
                } else {
                    val row = rows.iterator().next()
                    JsonObject()
                        .put("count", row.getValue("cnt") as? Long ?: 0L)
                        .put("warehouse", row.getValue("warehouse")?.toString())
                        .put("total_cost", nursingDecimalText(row.getValue("total_cost")) ?: "0")
                }
            }

    /** 查询执行耗材明细 */
    fun listExecutionConsumptions(executionId: String): Future<JsonObject> =
        pool.preparedQuery(EXECUTION_CONSUMPTIONS_ORDERED_SQL).execute(Tuple.of(executionId))
            .map { rows ->
                val details = JsonArray()
                for (row in rows) {
                    details.add(
                        JsonObject()
                            .put("id", row.getValue("id")?.toString())
                            .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                            .put("stock_id", row.getValue("stock_id")?.toString())
                            .put("material_id", row.getValue("material_id")?.toString())
                            .put("material_name", row.getValue("material_name")?.toString())
                            .put("lot_id", row.getValue("lot_id")?.toString())
                            .put("batch_no", row.getValue("batch_no")?.toString())
                            .put("warehouse", row.getValue("warehouse")?.toString())
                            .put("quantity", nursingDecimalText(row.getValue("quantity")))
                            .put("unit", row.getValue("unit")?.toString())
                            .put("unit_cost", nursingDecimalText(row.getValue("unit_cost")))
                            .put("total_cost", nursingDecimalText(row.getValue("total_cost")))
                            .put("created_at", row.getValue("created_at")?.toString()),
                    )
                }
                JsonObject().put("records", details).put("meta", JsonObject().put("total", details.size().toLong()))
            }

    // ========================================================================
    //  执行实例幂等生成
    // ========================================================================

    fun ensureExecutionsForDateRange(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        periodId: String?,
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        where.add("t.status = 'ACTIVE'")
        where.add(taskParticipationCondition(taskAlias = "t", periodAlias = "ps"))
        periodId?.let { where.add("t.period_id = ${bind(it)}") }

        val taskSql =
            "SELECT t.id AS t_id, t.frequency_code AS t_frequency_code, " +
                "t.start_date AS t_start_date, t.end_date AS t_end_date, " +
                "t.metadata AS t_metadata, t.period_id AS t_period_id " +
                "FROM nursing.nursing_tasks AS t " +
                "LEFT JOIN nursing.nursing_service_periods AS ps ON ps.id = t.period_id " +
                "WHERE " + where.joinToString(" AND ")

        return pool.preparedQuery(taskSql).execute(Tuple.from(args))
            .flatMap { taskRows ->
                val errors = JsonArray()

                val now = OffsetDateTime.now()

                // 每条待生成执行：绑定参数（id, task_id, planned_time, created_at）
                val inserts = mutableListOf<Tuple>()

                for (taskRow in taskRows) {
                    val taskId = taskRow.getValue("t_id")?.toString() ?: continue
                    val freqCode = taskRow.getValue("t_frequency_code")?.toString()
                    val startDate = (taskRow.getValue("t_start_date") as? LocalDate)
                    val endDate = (taskRow.getValue("t_end_date") as? LocalDate)
                    val metadata =
                        taskRow.getValue("t_metadata")?.let {
                            when (it) {
                                is JsonObject -> {
                                    it
                                }

                                is String -> {
                                    try {
                                        JsonObject(it)
                                    } catch (_: Exception) {
                                        null
                                    }
                                }

                                else -> {
                                    null
                                }
                            }
                        }

                    if (!FrequencyCalculator.isGeneratable(freqCode)) {
                        if (freqCode != null && freqCode !in setOf("PRN", "STAT")) {
                            errors.add(JsonObject().put("task_id", taskId).put("reason", "unknown frequency: $freqCode"))
                        }
                        continue
                    }

                    var date = dateFrom
                    while (!date.isAfter(dateTo)) {
                        if (endDate != null && date.isAfter(endDate)) {
                            date = date.plusDays(1)
                            continue
                        }
                        val times = FrequencyCalculator.plannedTimesForDate(freqCode, startDate, date, metadata)
                        for (plannedTime in times) {
                            inserts.add(Tuple.of(Ulid.generate(), taskId, plannedTime, now))
                        }
                        date = date.plusDays(1)
                    }
                }

                if (inserts.isEmpty()) {
                    return@flatMap Future.succeededFuture(
                        JsonObject()
                            .put("generated", 0)
                            .put("skipped", 0)
                            .put("errors", errors),
                    )
                }

                executeInsertsSequentially(inserts, 0, 0, 0, errors)
            }
    }

    private fun executeInsertsSequentially(
        inserts: List<Tuple>,
        index: Int,
        generated: Int,
        skipped: Int,
        errors: JsonArray,
    ): Future<JsonObject> {
        if (index >= inserts.size) {
            return Future.succeededFuture(
                JsonObject()
                    .put("generated", generated)
                    .put("skipped", skipped)
                    .put("errors", errors),
            )
        }

        return pool.preparedQuery(PLANNED_EXECUTION_INSERT_SQL).execute(inserts[index])
            .flatMap { result ->
                val updated = result.rowCount()
                val newGenerated = generated + updated
                val newSkipped = skipped + if (updated == 0) 1 else 0
                executeInsertsSequentially(inserts, index + 1, newGenerated, newSkipped, errors)
            }
    }

    fun ensureExecutionsForDate(
        date: LocalDate,
        periodId: String?,
    ): Future<JsonObject> = ensureExecutionsForDateRange(date, date, periodId)

    // ========================================================================
    //  执行查询公共片段（`/today` 与 `/overdue` 共用投影列、from/join 与耗材摘要）
    // ========================================================================

    /** 执行单条聚合/计数查询（聚合与计数恒返回一行） */
    private fun fetchRow(
        sql: String,
        args: Tuple,
    ): Future<Row> =
        pool.preparedQuery(sql).execute(args).map { it.iterator().next() }

    /** 结果行 → 响应记录，并批量补齐耗材摘要（`/today` 与 `/overdue` 共用，避免 N+1） */
    private fun recordsWithConsumptionSummary(
        rows: RowSet<Row>,
        now: OffsetDateTime,
    ): Future<JsonArray> {
        val records = JsonArray()
        val execIds = mutableListOf<String>()
        for (row in rows) {
            records.add(executionWithSummaryJson(row, now))
            row.getValue("id")?.toString()?.let { execIds.add(it) }
        }
        return loadConsumptionSummaryBatch(execIds).map { summary ->
            for (i in 0 until records.size()) {
                val record = records.getJsonObject(i)
                record.getString("id")?.let { id -> summary[id]?.let { s -> record.put("consumption_summary", s) } }
            }
            records
        }
    }

    /**
     * 任务参与条件 + 可选周期/执行人/任务类型：`/today` 与 `/overdue` 的公共基础范围（不含日期窗口）。
     * 每次调用返回独立的 SQL 片段与绑定值，避免调用方互相污染。
     */
    private fun participationConditions(
        periodId: String?,
        executor: String?,
        taskType: String?,
        where: MutableList<String>,
        bind: (Any?) -> String,
    ) {
        // 只看活动服务期的执行；全院性康复活动（period_id 为空）同样参与。
        // 已收束周期的历史记录仍可由时间线/统计查询。
        where.add(taskParticipationCondition(taskAlias = "t", periodAlias = "p"))
        periodId?.let { where.add("t.period_id = ${bind(it)}") }
        executor?.let { where.add("e.executor = ${bind(it)}") }
        taskType?.let { where.add("t.task_type = ${bind(it)}") }
    }

    /** §3.1 逾期定义：status ∈ {PENDING, IN_PROGRESS} 且 planned_time < now（严格小于） */
    private fun overdueDefinitionSql(
        now: OffsetDateTime,
        bind: (Any?) -> String,
    ): String = "e.status IN ('PENDING', 'IN_PROGRESS') AND e.planned_time < ${bind(now)}"

    // ========================================================================
    //  今日执行查询（带任务、长者摘要和耗材摘要）
    // ========================================================================

    fun todayExecutions(
        date: LocalDate,
        periodId: String? = null,
        executor: String? = null,
        status: String? = null,
        overdue: Boolean? = null,
        taskType: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return ensureExecutionsForDate(date, periodId).flatMap {
            val zone = java.time.ZoneOffset.UTC
            val dayStart = date.atStartOfDay().atOffset(zone)
            val dayEnd = date.plusDays(1).atStartOfDay().atOffset(zone)

            // 既有 total 语义（003 已冻结）：日期 + 参与条件 + 周期/执行人/任务类型 + status/overdue 筛选
            val dataWhere = mutableListOf<String>()
            val dataArgs = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bindData(value: Any?): String = "\$${dataArgs.size + 1}".also { dataArgs.add(value) }

            participationConditions(periodId, executor, taskType, dataWhere, ::bindData)
            dataWhere.add("e.planned_time >= ${bindData(dayStart)}")
            dataWhere.add("e.planned_time < ${bindData(dayEnd)}")
            status?.let { dataWhere.add("e.status = ${bindData(it)}") }
            // 逾期筛选条件：仅未完成且计划时间已过
            if (overdue == true) {
                dataWhere.add(overdueDefinitionSql(now, ::bindData))
            }

            // 既有 overdue_total 语义（003 已冻结）：当天 + 参与条件 + 周期/执行人/任务类型范围内的逾期数，
            // 忽略 status 与 overdue 筛选
            val overdueWhere = mutableListOf<String>()
            val overdueArgs = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bindOverdue(value: Any?): String = "\$${overdueArgs.size + 1}".also { overdueArgs.add(value) }

            participationConditions(periodId, executor, taskType, overdueWhere, ::bindOverdue)
            overdueWhere.add("e.planned_time >= ${bindOverdue(dayStart)}")
            overdueWhere.add("e.planned_time < ${bindOverdue(dayEnd)}")
            overdueWhere.add(overdueDefinitionSql(now, ::bindOverdue))

            // §4.2 新增聚合，全部在 SQL 内完成（禁止拉全表内存计数、禁止 N+1）：
            // - status_totals：日期窗口 + 参与条件 + 周期/执行人/任务类型内各状态计数，忽略 status/overdue 筛选；
            // - overdue_total_all：忽略日期窗口，仅保留参与条件 + 周期/执行人/任务类型 + 逾期定义。
            // 两者日期范围不同，因此以「无日期窗口的基础范围」为 FROM，用 count(*) FILTER (WHERE ...) 一次取回。
            val metaWhere = mutableListOf<String>()
            val metaArgs = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符（聚合投影已占用 $1..$3，WHERE 从 $4 起）。 */
            fun bindMeta(value: Any?): String = "\$${metaArgs.size + 4}".also { metaArgs.add(value) }

            participationConditions(periodId, executor, taskType, metaWhere, ::bindMeta)
            val metaSql = TODAY_META_SQL + " WHERE " + metaWhere.joinToString(" AND ")
            val metaTuple = Tuple.from(listOf<Any?>(dayStart, dayEnd, now) + metaArgs)

            val countSql = "SELECT count(*) AS total$EXECUTION_FROM WHERE " +
                dataWhere.joinToString(" AND ")
            val countArgs = dataArgs.toList()
            val overdueTotalSql = "SELECT count(*) AS overdue_total$EXECUTION_FROM WHERE " +
                overdueWhere.joinToString(" AND ")
            val dataSql =
                "SELECT $EXECUTION_PROJECTION$EXECUTION_FROM WHERE " +
                    dataWhere.joinToString(" AND ") +
                    " ORDER BY e.planned_time ASC " +
                    "LIMIT ${bindData(limit)} OFFSET ${bindData(offset)}"

            fetchRow(countSql, Tuple.from(countArgs))
                .compose { countRow ->
                    val total = countRow.getLong("total") ?: 0L
                    fetchRow(metaSql, metaTuple).map { metaRow -> Pair(total, metaRow) }
                }.compose { (total, metaRow) ->
                    fetchRow(overdueTotalSql, Tuple.from(overdueArgs)).compose { overdueRow ->
                        val overdueTotal = overdueRow.getLong("overdue_total") ?: 0L
                        pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
                            .compose { dataRows -> recordsWithConsumptionSummary(dataRows, now) }
                            .map { records ->
                                JsonObject()
                                    .put("records", records)
                                    .put(
                                        "meta",
                                        JsonObject()
                                            .put("total", total)
                                            .put("overdue_total", overdueTotal)
                                            .put("overdue_total_all", metaRow.getLong("overdue_total_all") ?: 0L)
                                            .put(
                                                "status_totals",
                                                JsonObject()
                                                    .put("PENDING", metaRow.getLong("status_pending") ?: 0L)
                                                    .put("IN_PROGRESS", metaRow.getLong("status_in_progress") ?: 0L)
                                                    .put("COMPLETED", metaRow.getLong("status_completed") ?: 0L)
                                                    .put("SKIPPED", metaRow.getLong("status_skipped") ?: 0L)
                                                    .put("CANCELLED", metaRow.getLong("status_cancelled") ?: 0L),
                                            ),
                                    )
                            }
                    }
                }
        }
    }

    // ========================================================================
    //  跨日逾期队列（§4.3）：无日期窗口、按 planned_time ASC、只读无写副作用
    // ========================================================================

    /**
     * 跨日全量逾期队列：逾期定义（§3.1）+ 任务参与条件 + 可选 period_id/executor/task_type。
     *
     * - 不施加任何日期窗口（忽略调用方可能传入的 date，路由层直接不读取该参数）；
     * - 不调用 `ensureExecutionsForDate`，因此不产生任何写副作用；
     * - `limit` 默认 50 并收敛到 1..200，`offset` 默认 0（见 [normalizeOverduePaging]）。
     */
    fun overdueExecutions(
        periodId: String? = null,
        executor: String? = null,
        taskType: String? = null,
        limit: Int? = OVERDUE_LIMIT_DEFAULT,
        offset: Int? = 0,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val (effectiveLimit, effectiveOffset) = normalizeOverduePaging(limit, offset)

        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        participationConditions(periodId, executor, taskType, where, ::bind)
        where.add(overdueDefinitionSql(now, ::bind))
        val whereClause = " WHERE " + where.joinToString(" AND ")

        val countSql = "SELECT count(*) AS total$EXECUTION_FROM$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $EXECUTION_PROJECTION$EXECUTION_FROM$whereClause " +
                "ORDER BY e.planned_time ASC LIMIT ${bind(effectiveLimit)} OFFSET ${bind(effectiveOffset)}"
        val dataArgs = args.toList()

        return fetchRow(countSql, Tuple.from(countArgs))
            .compose { countRow ->
                val total = countRow.getLong("total") ?: 0L
                pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
                    .compose { dataRows -> recordsWithConsumptionSummary(dataRows, now) }
                    .map { records ->
                        JsonObject()
                            .put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    // ========================================================================
    //  逾期执行收口（030 §3 D5 / §4.3）：幂等批量置 SKIPPED，metadata.convergence 留痕
    // ========================================================================

    /**
     * 把「逾期未终态」的执行批量置为 `SKIPPED`（漏执行），幂等。
     *
     * 口径（030 §4.3 冻结契约，逐字实现）：
     * - 命中 `status ∈ {PENDING, IN_PROGRESS}` 且 `planned_time < now - minOverdueMinutes`；
     * - 可选 `encounterId`（任务的 `encounter_id` 或任务所属周期的 `encounter_id`，
     *   与 MAR 查询同口径）与 `taskType` 过滤；两者同时给出时为「与」关系；
     * - 按 `planned_time ASC` 取前 `limit` 条（同刻以 `id ASC` 兜底，保证可复现）；
     * - 逐条 `status = 'SKIPPED'`，并把
     *   `metadata.convergence = {reason, at, min_overdue_minutes}` **合并**写入既有 `metadata`
     *   （`COALESCE(metadata,'{}'::jsonb) || patch`，只替换 `convergence` 键，不覆盖其它键）；
     * - 「选取候选 + 逐条更新」在**同一事务**内提交（[Pool.withTransaction]）；
     * - UPDATE 自带 `status IN ('PENDING','IN_PROGRESS')` 守卫：终态行（`COMPLETED`/`SKIPPED`/
     *   `CANCELLED`）永远不产生任何写；被并发流转的行 `rowCount = 0`，不计入 `converged`；
     * - 幂等：上次已收敛的行不再命中，第二次调用 `converged` 自然为 0。
     *
     * 有意不叠加 027 的「活跃周期参与条件」：本端点是人工触发的运维收口动作
     * （030 §2.2 明确不做定时任务），命中条件以本契约逐字为准。
     *
     * @return `{"converged": N, "ids": [...], "truncated": bool, "min_overdue_minutes": n}`
     */
    fun convergeOverdueExecutions(
        encounterId: String? = null,
        taskType: String? = null,
        minOverdueMinutes: Int? = null,
        limit: Int? = null,
    ): Future<JsonObject> {
        val effectiveMinutes: Int
        val effectiveLimit: Int
        try {
            val normalized = normalizeConvergeRequest(minOverdueMinutes, limit)
            effectiveMinutes = normalized.first
            effectiveLimit = normalized.second
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val threshold = now.minusMinutes(effectiveMinutes.toLong())
        val convergencePatch = JsonObject().put("convergence", convergencePayload(effectiveMinutes, now))

        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        where.add("e.planned_time < ${bind(threshold)}")
        encounterId?.takeIf { it.isNotBlank() }?.let { id ->
            val placeholder = bind(id)
            where.add("(p.encounter_id = $placeholder OR t.encounter_id = $placeholder)")
        }
        taskType?.takeIf { it.isNotBlank() }?.let { where.add("t.task_type = ${bind(it)}") }

        // 多取 1 条仅用于判定 truncated；该行不参与收敛
        val candidateSql =
            CONVERGE_CANDIDATE_SQL + " AND " + where.joinToString(" AND ") +
                " ORDER BY e.planned_time ASC, e.id ASC LIMIT ${bind(effectiveLimit + 1)}"

        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(candidateSql).execute(Tuple.from(args))
                .compose { rows ->
                    val candidates = mutableListOf<String>()
                    for (row in rows) {
                        row.getValue("id")?.toString()?.let { candidates.add(it) }
                    }
                    convergeSequentially(
                        connection = connection,
                        ids = candidates.take(effectiveLimit),
                        index = 0,
                        convergedIds = mutableListOf(),
                        convergencePatch = convergencePatch,
                        minOverdueMinutes = effectiveMinutes,
                        truncated = candidates.size > effectiveLimit,
                    )
                }
        }
    }

    /**
     * 在调用方事务内逐条把执行置为 `SKIPPED` 并合并 `metadata.convergence`。
     * 只有 `rowCount > 0` 的行计入 `converged`/`ids`（并发流转走或已是终态的行自动跳过）。
     */
    private fun convergeSequentially(
        connection: SqlConnection,
        ids: List<String>,
        index: Int,
        convergedIds: MutableList<String>,
        convergencePatch: JsonObject,
        minOverdueMinutes: Int,
        truncated: Boolean,
    ): Future<JsonObject> {
        if (index >= ids.size) {
            return Future.succeededFuture(
                JsonObject()
                    .put("converged", convergedIds.size)
                    .put("ids", JsonArray(convergedIds.toList()))
                    .put("truncated", truncated)
                    .put("min_overdue_minutes", minOverdueMinutes),
            )
        }

        val id = ids[index]
        return connection.preparedQuery(CONVERGE_UPDATE_SQL)
            .execute(Tuple.of("SKIPPED", convergencePatch, id))
            .compose { result ->
                if (result.rowCount() > 0) convergedIds.add(id)
                convergeSequentially(
                    connection = connection,
                    ids = ids,
                    index = index + 1,
                    convergedIds = convergedIds,
                    convergencePatch = convergencePatch,
                    minOverdueMinutes = minOverdueMinutes,
                    truncated = truncated,
                )
            }
    }

    // ========================================================================
    //  执行统计（护理员工作量与计划完成率）
    // ========================================================================

    fun executionStatistics(
        dateFrom: LocalDate,
        dateTo: LocalDate,
        periodId: String? = null,
        executor: String? = null,
        taskType: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val zone = java.time.ZoneOffset.UTC
        val rangeStart = dateFrom.atStartOfDay().atOffset(zone)
        val rangeEnd = dateTo.plusDays(1).atStartOfDay().atOffset(zone)

        /**
         * 统计查询的公共 WHERE：`prefix` 为已占用的绑定值（聚合投影把 `now` 放 $1）。
         * 占位符按 `prefix.size + 1` 起编号，使同一份条件既能接在聚合投影之后，
         * 也能单独用于没有聚合投影的分组计数子查询。
         */
        fun buildWhere(prefix: MutableList<Any?>): String {
            val where = mutableListOf<String>()
            val args = prefix

            /** 追加一个绑定值并返回其占位符。 */
            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            where.add("e.planned_time >= ${bind(rangeStart)}")
            where.add("e.planned_time < ${bind(rangeEnd)}")
            periodId?.let { where.add("t.period_id = ${bind(it)}") }
            executor?.let { where.add("e.executor = ${bind(it)}") }
            taskType?.let { where.add("t.task_type = ${bind(it)}") }
            return " WHERE " + where.joinToString(" AND ")
        }

        // 聚合查询：$1 恒为 now（应完成/逾期分界）；WHERE 绑定从 $2 起。
        val statsArgs = mutableListOf<Any?>(now)
        val whereClause = buildWhere(statsArgs)
        val whereArgs = statsArgs.toList()

        // 全局汇总查询
        val globalSql = "SELECT $STATISTICS_AGGREGATES$STATISTICS_FROM$whereClause"

        // 按执行人分组查询
        val groupedSql =
            "SELECT e.executor AS executor, $STATISTICS_AGGREGATES$STATISTICS_FROM$whereClause " +
                "GROUP BY e.executor " +
                "ORDER BY due_total DESC, scheduled_total DESC, e.executor ASC NULLS LAST " +
                "LIMIT \$${statsArgs.size + 1} OFFSET \$${statsArgs.size + 2}"
        statsArgs.add(limit)
        statsArgs.add(offset)

        // 分组总数查询（不受 limit/offset 影响；无聚合投影，占位符从 $1 起）
        val countArgs = mutableListOf<Any?>()
        val countWhereClause = buildWhere(countArgs)
        val groupCountSql =
            "SELECT count(*) AS total FROM (" +
                "SELECT e.executor$STATISTICS_FROM$countWhereClause GROUP BY e.executor" +
                ") AS grouped"

        return pool.preparedQuery(globalSql).execute(Tuple.from(whereArgs))
            .flatMap { globalRows ->
                val global = globalRows.iterator().next()
                val scheduledTotal = global.getLong("scheduled_total") ?: 0L
                val pendingTotal = global.getLong("pending_total") ?: 0L
                val inProgressTotal = global.getLong("in_progress_total") ?: 0L
                val completedTotal = global.getLong("completed_total") ?: 0L
                val skippedTotal = global.getLong("skipped_total") ?: 0L
                val cancelledTotal = global.getLong("cancelled_total") ?: 0L
                val dueTotal = global.getLong("due_total") ?: 0L
                val completedDueTotal = global.getLong("completed_due_total") ?: 0L
                val overdueTotal = global.getLong("overdue_total") ?: 0L

                pool.preparedQuery(groupCountSql).execute(Tuple.from(countArgs))
                    .flatMap { countRows ->
                        val groupTotal = countRows.iterator().next().getLong("total") ?: 0L

                        pool.preparedQuery(groupedSql).execute(Tuple.from(statsArgs))
                            .map { dataRows ->
                                val records = JsonArray()
                                for (row in dataRows) {
                                    val ex = row.getValue("executor")?.toString()
                                    val gs = row.getLong("scheduled_total") ?: 0L
                                    val gp = row.getLong("pending_total") ?: 0L
                                    val gi = row.getLong("in_progress_total") ?: 0L
                                    val gc = row.getLong("completed_total") ?: 0L
                                    val gsk = row.getLong("skipped_total") ?: 0L
                                    val gca = row.getLong("cancelled_total") ?: 0L
                                    val gd = row.getLong("due_total") ?: 0L
                                    val gcd = row.getLong("completed_due_total") ?: 0L
                                    val gov = row.getLong("overdue_total") ?: 0L
                                    records.add(
                                        JsonObject()
                                            .put("executor", ex)
                                            .put("scheduled_total", gs)
                                            .put("pending_total", gp)
                                            .put("in_progress_total", gi)
                                            .put("completed_total", gc)
                                            .put("skipped_total", gsk)
                                            .put("cancelled_total", gca)
                                            .put("due_total", gd)
                                            .put("completed_due_total", gcd)
                                            .put("overdue_total", gov)
                                            .put("completion_rate", completionRate(gcd, gd)),
                                    )
                                }

                                val globalCompletionRate = completionRate(completedDueTotal, dueTotal)

                                JsonObject()
                                    .put("records", records)
                                    .put(
                                        "meta",
                                        JsonObject()
                                            .put("total", groupTotal)
                                            .put("date_from", dateFrom.toString())
                                            .put("date_to", dateTo.toString())
                                            .put("scheduled_total", scheduledTotal)
                                            .put("pending_total", pendingTotal)
                                            .put("in_progress_total", inProgressTotal)
                                            .put("completed_total", completedTotal)
                                            .put("skipped_total", skippedTotal)
                                            .put("cancelled_total", cancelledTotal)
                                            .put("due_total", dueTotal)
                                            .put("completed_due_total", completedDueTotal)
                                            .put("overdue_total", overdueTotal)
                                            .put("completion_rate", globalCompletionRate),
                                    )
                            }
                    }
            }
    }
}
