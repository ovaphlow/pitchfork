package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.isUniqueViolation
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 保育员班级日程服务（054 C2，迁移 V414）。
 *
 * ## 为什么是独立表
 *
 * 班级日程**不能复用 `nursing_plans`**：后者 `period_id` 为 NOT NULL（必须挂个体服务周期），
 * 而班级日程是集体排期，与服务周期无关（§7 C2 行）。故新建 `nursing_childcare_class_schedules`。
 *
 * ## 规则
 *
 *  1. **同时段冲突 409**：同 `(class_name, schedule_date, time_slot)` 且未取消只允许一行，
 *     由部分唯一索引 `uq_childcare_class_schedules_slot ... WHERE status <> '已取消'` 兜底
 *     （并发下亦成立）；命中唯一冲突 → [ConflictException]（409）。取消后时段释放，可重新排课。
 *  2. 状态（§5.8 冻结）：`草稿 / 已发布 / 已取消`；流转 **草稿→{已发布,已取消}、已发布→已取消、
 *     已取消 终态**；非法流转 → 409。创建一律落 `草稿`（客户端提交的 `status` 被忽略，发布是显式动作）。
 *  3. `time_slot` 取固定时段常量 [TIME_SLOTS]（保证冲突判定确定；§5.8 未冻结该字段，本任务定义）。
 *  4. `operator` 取认证主体（042 口径）；持久化走**原生参数化 SQL**，本文件不引入 jOOQ。
 */
class ChildcareClassScheduleService(private val pool: Pool) {

    companion object {
        /** §5.8 冻结：`ChildcareClassSchedule.status`。 */
        const val STATUS_DRAFT = "草稿"
        const val STATUS_PUBLISHED = "已发布"
        const val STATUS_CANCELLED = "已取消"

        val STATUSES = setOf(STATUS_DRAFT, STATUS_PUBLISHED, STATUS_CANCELLED)

        /** 状态机（§5.8 冻结值上的合法流转）。 */
        val TRANSITIONS: Map<String, Set<String>> = mapOf(
            STATUS_DRAFT to setOf(STATUS_PUBLISHED, STATUS_CANCELLED),
            STATUS_PUBLISHED to setOf(STATUS_CANCELLED),
            STATUS_CANCELLED to emptySet(),
        )

        /** 固定时段（§5.8 未冻结 → 本任务定义；保证同时段冲突判定确定）。 */
        val TIME_SLOTS = listOf(
            "08:00-09:00",
            "09:00-10:00",
            "10:00-11:00",
            "11:00-12:00",
            "14:00-15:00",
            "15:00-16:00",
            "16:00-17:00",
            "17:00-18:00",
        )

        /** 同时段冲突唯一索引名（V414）。 */
        const val SLOT_UNIQUE_INDEX = "uq_childcare_class_schedules_slot"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_childcare_class_schedules " +
                "(id, class_name, schedule_date, time_slot, activity_name, status, operator, " +
                "metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10)"

        private const val UPDATE_STATUS_SQL =
            "UPDATE nursing.nursing_childcare_class_schedules SET status = \$1, updated_at = \$2 " +
                "WHERE id = \$3"

        private const val SELECT_COLUMNS =
            "s.id, s.class_name, s.schedule_date, s.time_slot, s.activity_name, s.status, " +
                "s.operator, s.metadata, s.created_at, s.updated_at"

        private const val SELECT_FROM = " FROM nursing.nursing_childcare_class_schedules s "

        private const val GET_SQL = "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE s.id = \$1"

        private const val COUNT_SQL = "SELECT count(*) AS total" + SELECT_FROM

        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("class_name", row.getString("class_name"))
            .put("schedule_date", row.getLocalDate("schedule_date")?.toString())
            .put("time_slot", row.getString("time_slot"))
            .put("activity_name", row.getString("activity_name"))
            .put("status", row.getString("status"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 排课（初始状态 `草稿`）。同时段冲突 → [ConflictException]（409）。
     * 参数非法抛 `IllegalArgumentException`（400）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val className = body.getString("class_name")?.trim()
        if (className.isNullOrEmpty()) throw IllegalArgumentException("class_name is required")

        val scheduleDate = parseDate(body.getString("schedule_date"), "schedule_date")
            ?: throw IllegalArgumentException("schedule_date is required")

        val timeSlot = body.getString("time_slot")?.trim()
        if (timeSlot.isNullOrEmpty()) throw IllegalArgumentException("time_slot is required")
        if (timeSlot !in TIME_SLOTS)
            throw IllegalArgumentException("invalid time_slot, must be one of: $TIME_SLOTS")

        val activityName = body.getString("activity_name")?.trim()
        if (activityName.isNullOrEmpty()) throw IllegalArgumentException("activity_name is required")

        val metadata = parseMetadata(body.getValue("metadata"))
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(className)
        tuple.addLocalDate(scheduleDate)
        tuple.addString(timeSlot)
        tuple.addString(activityName)
        tuple.addString(STATUS_DRAFT)
        tuple.addValue(operator)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
            .recover { error ->
                if (isUniqueViolation(error, setOf(SLOT_UNIQUE_INDEX)))
                    Future.failedFuture(ConflictException("该班级该日期该时段已有日程（同时段冲突）"))
                else Future.failedFuture(error)
            }
    }

    /**
     * 状态流转（发布/取消）。非法流转 → [ConflictException]（409）；
     * 未知状态值 → `IllegalArgumentException`（400）；日程不存在 → [NotFoundException]（404）。
     */
    fun updateStatus(id: String, body: JsonObject, operator: String?): Future<JsonObject> {
        val status = body.getString("status")?.trim()
        if (status.isNullOrEmpty()) throw IllegalArgumentException("status is required")
        if (status !in STATUSES)
            throw IllegalArgumentException("invalid status, must be one of: ${STATUSES.toList()}")

        return get(id).compose { existing ->
            val current = existing.getString("status") ?: STATUS_DRAFT
            if (status !in (TRANSITIONS[current] ?: emptySet()))
                throw ConflictException("invalid status transition: $current -> $status")

            val now = OffsetDateTime.now()
            pool.preparedQuery(UPDATE_STATUS_SQL)
                .execute(Tuple.of(status, now, id))
                .compose { get(id) }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("childcare class schedule not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`class_name` / `date` / `status`（+ 附加 from/to）。 */
    fun list(
        className: String? = null,
        date: String? = null,
        status: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        className?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("s.class_name = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(date, "date")?.let {
            conditions.add("s.schedule_date = \$${values.size + 1}")
            values.add(it)
        }
        parseStatus(status)?.let {
            conditions.add("s.status = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(from, "from")?.let {
            conditions.add("s.schedule_date >= \$${values.size + 1}")
            values.add(it)
        }
        parseDate(to, "to")?.let {
            conditions.add("s.schedule_date <= \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY s.schedule_date DESC, s.time_slot LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun parseStatus(value: String?): String? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        if (text !in STATUSES)
            throw IllegalArgumentException("invalid status, must be one of: ${STATUSES.toList()}")
        return text
    }

    private fun parseDate(value: String?, field: String): LocalDate? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }
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
