package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 保育员日常活动日志服务（054 C1，迁移 V413）。
 *
 * 规则：
 *  1. **班级级台账**：`class_name` + `activity_name` 必填；`patient_id` 可空
 *     （班级集体活动不针对个体儿童），提交了就必须是存在的儿童（否则 404）。
 *  2. `activity_date` 缺省**业务当天**（Asia/Shanghai）；`participant_count` 缺省 0 且不得为负。
 *  3. 同一班级同一天可有多条（不同活动）→ **不设唯一约束**，列表按日期倒序。
 *  4. 筛选：`patient_id` / `class_name` / `date`（另有附加的 `from`+`to` 区间）。
 *  5. `operator` 取认证主体（042 口径）；持久化走**原生参数化 SQL**，本文件不引入 jOOQ。
 */
class ChildcareActivityLogService(private val pool: Pool) {

    companion object {
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        fun today(): LocalDate = LocalDate.now(businessZone)

        private const val PATIENT_SQL = "SELECT id FROM healthcare.patients WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_childcare_activity_logs " +
                "(id, patient_id, class_name, activity_date, activity_name, participant_count, " +
                "note, operator, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

        private const val SELECT_COLUMNS =
            "a.id, a.patient_id, a.class_name, a.activity_date, a.activity_name, " +
                "a.participant_count, a.note, a.operator, a.metadata, a.created_at, a.updated_at"

        private const val SELECT_FROM = " FROM nursing.nursing_childcare_activity_logs a "

        private const val GET_SQL = "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE a.id = \$1"

        private const val COUNT_SQL = "SELECT count(*) AS total" + SELECT_FROM

        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("class_name", row.getString("class_name"))
            .put("activity_date", row.getLocalDate("activity_date")?.toString())
            .put("activity_name", row.getString("activity_name"))
            .put("participant_count", row.getInteger("participant_count") ?: 0)
            .put("note", row.getString("note"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 登记活动日志。
     * 参数非法抛 `IllegalArgumentException`（400）；`patient_id` 指向不存在的儿童 → [NotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val className = body.getString("class_name")?.trim()
        if (className.isNullOrEmpty()) throw IllegalArgumentException("class_name is required")

        val activityName = body.getString("activity_name")?.trim()
        if (activityName.isNullOrEmpty()) throw IllegalArgumentException("activity_name is required")

        val patientId = body.getString("patient_id")?.trim()?.takeIf { it.isNotEmpty() }
        val activityDate = parseDate(body.getString("activity_date")) ?: today()
        val participantCount = parseParticipantCount(body.getValue("participant_count"))
        val note = body.getString("note")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addValue(patientId)
        tuple.addString(className)
        tuple.addLocalDate(activityDate)
        tuple.addString(activityName)
        tuple.addInteger(participantCount)
        tuple.addValue(note)
        tuple.addValue(operator)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        val guard = patientId?.let { verifyPatient(it) } ?: Future.succeededFuture(Unit)
        return guard.compose {
            pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("childcare activity log not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /**
     * 列表（空结果也返回 `records: []` 与 `total: 0`）。
     * 过滤：`patient_id` / `class_name` / `date`（单日）/ `from`+`to`（区间，含端点）。
     */
    fun list(
        patientId: String? = null,
        className: String? = null,
        date: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("a.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        className?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("a.class_name = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(date)?.let {
            conditions.add("a.activity_date = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(from)?.let {
            conditions.add("a.activity_date >= \$${values.size + 1}")
            values.add(it)
        }
        parseDate(to)?.let {
            conditions.add("a.activity_date <= \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY a.activity_date DESC, a.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

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

    private fun verifyPatient(patientId: String): Future<Unit> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            if (rows.iterator().asSequence().firstOrNull() == null)
                Future.failedFuture(NotFoundException("patient not found: $patientId"))
            else Future.succeededFuture(Unit)
        }

    /** 空值返回 `null`（由调用方决定缺省）；非空但格式非法 → 400。 */
    private fun parseDate(value: String?): LocalDate? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("date must be a valid ISO date (yyyy-MM-dd)")
        }
    }

    private fun parseParticipantCount(value: Any?): Int {
        if (value == null) return 0
        val count = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("participant_count must be a non-negative integer")
        if (count < 0) throw IllegalArgumentException("participant_count must be a non-negative integer")
        return count
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
