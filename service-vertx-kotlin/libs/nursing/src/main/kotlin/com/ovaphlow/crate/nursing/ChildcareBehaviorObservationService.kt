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
 * 保育员行为观测记录服务（054 C1，迁移 V413）。
 *
 * 规则：
 *  1. **儿童级 append-only**：`patient_id` / `category` / `description` 必填；
 *     儿童不存在 → 404；无 `updated_at`（记录一经登记不修改）。
 *  2. `level` 走 §5.8 **冻结白名单**：`一般 / 关注 / 需干预`（可空；给了就必须命中，否则 400）。
 *  3. `observed_at` 缺省**当前时刻**；接受带偏移的 ISO-8601 时间或纯日期（按业务时区当日 00:00）。
 *  4. `category` 未列入 §5.8 → **自由文本**（前端建议：情绪/社交/饮食/睡眠/行为/其他）。
 *  5. 筛选：`patient_id` / `date`（业务时区当日）/ `level`（另有附加的 `from`+`to` 区间）。
 *  6. `operator` 取认证主体（042 口径）；持久化走**原生参数化 SQL**，本文件不引入 jOOQ。
 */
class ChildcareBehaviorObservationService(private val pool: Pool) {

    companion object {
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        /** §5.8 冻结白名单。 */
        val LEVELS = setOf("一般", "关注", "需干预")

        /** 业务时区当日谓词（`observed_at` 为 TIMESTAMPTZ，按业务时区取日）。 */
        private const val DAY_EXPR = "(o.observed_at AT TIME ZONE 'Asia/Shanghai')::date"

        private const val PATIENT_SQL = "SELECT id FROM healthcare.patients WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_childcare_behavior_observations " +
                "(id, patient_id, observed_at, category, description, level, operator, metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val SELECT_COLUMNS =
            "o.id, o.patient_id, o.observed_at, o.category, o.description, o.level, " +
                "o.operator, o.metadata, o.created_at"

        private const val SELECT_FROM = " FROM nursing.nursing_childcare_behavior_observations o "

        private const val GET_SQL = "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE o.id = \$1"

        private const val COUNT_SQL = "SELECT count(*) AS total" + SELECT_FROM

        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("observed_at", row.getOffsetDateTime("observed_at")?.toString())
            .put("category", row.getString("category"))
            .put("description", row.getString("description"))
            .put("level", row.getString("level"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
    }

    /**
     * 登记行为观测。
     * 参数非法抛 `IllegalArgumentException`（400）；儿童不存在 → [NotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val category = body.getString("category")?.trim()
        if (category.isNullOrEmpty()) throw IllegalArgumentException("category is required")

        val description = body.getString("description")?.trim()
        if (description.isNullOrEmpty()) throw IllegalArgumentException("description is required")

        val level = parseLevel(body.getString("level"))
        val observedAt = parseInstant(body.getString("observed_at"))
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(patientId)
        tuple.addOffsetDateTime(observedAt)
        tuple.addString(category)
        tuple.addString(description)
        tuple.addValue(level)
        tuple.addValue(operator)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)

        return verifyPatient(patientId).compose {
            pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("childcare behavior observation not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /**
     * 列表（空结果也返回 `records: []` 与 `total: 0`）。
     * 过滤：`patient_id` / `date`（业务时区当日）/ `level` / `from`+`to`（区间，含端点）。
     */
    fun list(
        patientId: String? = null,
        date: String? = null,
        level: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("o.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(date)?.let {
            conditions.add("$DAY_EXPR = \$${values.size + 1}")
            values.add(it)
        }
        parseLevel(level)?.let {
            conditions.add("o.level = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(from)?.let {
            conditions.add("$DAY_EXPR >= \$${values.size + 1}")
            values.add(it)
        }
        parseDate(to)?.let {
            conditions.add("$DAY_EXPR <= \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY o.observed_at DESC, o.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

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

    /** 可空；非空但不在 §5.8 白名单 → 400。 */
    private fun parseLevel(value: String?): String? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        if (text !in LEVELS)
            throw IllegalArgumentException("invalid level, must be one of: ${LEVELS.toList()}")
        return text
    }

    private fun parseDate(value: String?): LocalDate? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("date must be a valid ISO date (yyyy-MM-dd)")
        }
    }

    /** 接受带偏移的 ISO-8601 时间或纯日期；缺省当前时刻。 */
    private fun parseInstant(value: String?): OffsetDateTime {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return OffsetDateTime.now()
        runCatching { return OffsetDateTime.parse(text) }
        runCatching { return LocalDate.parse(text).atStartOfDay(businessZone).toOffsetDateTime() }
        throw IllegalArgumentException("observed_at must be a valid ISO-8601 date or date-time")
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
