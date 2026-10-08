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

/**
 * 儿童康复训练计划（054 B1，迁移 V409；**复用** `nursing_plans` / `nursing_plan_items`）。
 *
 * 规则：
 *  1. **不给 `nursing_plans` 加 `patient_id` 列**（BL6 → L8 裁定）：`patient_id` 是
 *     join `nursing_service_periods` 派生出来的响应字段，列表按 `?patient_id=` 走跨表查询。
 *  2. 计划必须挂在 `service_type = 'CHILD_REHAB'` 的服务周期上（否则 400）；
 *     `nursing_plans.encounter_id` 恒为 NULL（儿童康复无入住周期）。
 *  3. 计划状态沿用既有列枚举 `ACTIVE / COMPLETED / DISCONTINUED`，创建恒为 `ACTIVE`；
 *     `created_by` 取认证主体。
 *  4. 明细写 `nursing_plan_items`（`action` 必填；`frequency_code/frequency_name/duration_days/remark` 可选）。
 *  5. 本服务**不引入**「同一周期只允许一个 ACTIVE 计划」的限制——054 §8.2 未要求，
 *     且康复场景允许多条并行训练计划；周期级唯一性由 V409 的部分唯一索引单独承载。
 *  6. 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class ChildRehabPlanService(private val pool: Pool) {

    companion object {
        /** 计划状态白名单（沿用 `nursing_plans.status` 既有英文枚举）。 */
        val STATUSES = setOf("ACTIVE", "COMPLETED", "DISCONTINUED")

        private const val PERIOD_SQL =
            "SELECT id, patient_id, service_type FROM nursing.nursing_service_periods WHERE id = \$1"

        private const val INSERT_PLAN_SQL =
            "INSERT INTO nursing.nursing_plans " +
                "(id, period_id, encounter_id, plan_name, goals, status, created_by, start_date, " +
                "end_date, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, NULL, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

        private const val INSERT_ITEM_SQL =
            "INSERT INTO nursing.nursing_plan_items " +
                "(id, plan_id, action, frequency_code, frequency_name, duration_days, remark, status, " +
                "metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10)"

        private const val SELECT_COLUMNS =
            "pl.id, pl.period_id, pe.patient_id, pl.plan_name, pl.goals, pl.status, pl.created_by, " +
                "pl.start_date, pl.end_date, pl.metadata, pl.created_at, pl.updated_at"

        private const val SELECT_FROM =
            " FROM nursing.nursing_plans pl " +
                "JOIN nursing.nursing_service_periods pe ON pe.id = pl.period_id "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE pl.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        private const val ITEMS_SQL =
            "SELECT id, plan_id, action, frequency_code, frequency_name, duration_days, remark, status, " +
                "metadata, created_at FROM nursing.nursing_plan_items WHERE plan_id = \$1 ORDER BY created_at"

        /** 行 → 计划 JSON（`patient_id` 为 join 派生字段）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("period_id", row.getString("period_id"))
            .put("patient_id", row.getString("patient_id"))
            .put("plan_name", row.getString("plan_name"))
            .put("goals", row.getString("goals"))
            .put("status", row.getString("status"))
            .put("created_by", row.getString("created_by"))
            .put("start_date", row.getLocalDate("start_date")?.toString())
            .put("end_date", row.getLocalDate("end_date")?.toString())
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 行 → 计划明细 JSON。 */
        internal fun itemToJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("plan_id", row.getString("plan_id"))
            .put("action", row.getString("action"))
            .put("frequency_code", row.getString("frequency_code"))
            .put("frequency_name", row.getString("frequency_name"))
            .put("duration_days", row.getInteger("duration_days"))
            .put("remark", row.getString("remark"))
            .put("status", row.getString("status"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
    }

    /**
     * 创建训练计划（含明细）。参数非法抛 `IllegalArgumentException`（400）；
     * 服务周期不存在抛 [NotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val periodId = body.getString("period_id")?.trim()
        if (periodId.isNullOrEmpty()) throw IllegalArgumentException("period_id is required")

        val planName = body.getString("plan_name")?.trim()
        if (planName.isNullOrEmpty()) throw IllegalArgumentException("plan_name is required")

        val startDate = body.getString("start_date")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { parseDate(it, "start_date") }
        val endDate = body.getString("end_date")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { parseDate(it, "end_date") }
        if (startDate != null && endDate != null && endDate.isBefore(startDate))
            throw IllegalArgumentException("end_date must not be before start_date")

        val goals = body.getString("goals")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = parseMetadata(body.getValue("metadata"))
        val items = normalizeItems(body.getValue("items"))

        return loadChildRehabPeriod(periodId).compose {
            val planId = Ulid.generate()
            val now = OffsetDateTime.now()
            val planTuple = Tuple.tuple()
            planTuple.addString(planId)
            planTuple.addString(periodId)
            planTuple.addString(planName)
            planTuple.addValue(goals)
            planTuple.addString("ACTIVE")
            planTuple.addValue(operator)
            planTuple.addValue(startDate)
            planTuple.addValue(endDate)
            planTuple.addValue(metadata)
            planTuple.addOffsetDateTime(now)
            planTuple.addOffsetDateTime(now)

            pool.preparedQuery(INSERT_PLAN_SQL).execute(planTuple).compose {
                insertItems(planId, items, now).compose { get(planId) }
            }
        }
    }

    /** 按 id 取单条（含明细）；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("child rehab plan not found: $id"))
            loadItems(listOf(id)).map { itemsById ->
                toJson(row).put("items", itemsById[id] ?: JsonArray())
            }
        }

    /**
     * 列表（空结果也返回 `records: []` 与 `total: 0`）。
     * 过滤：`period_id` / `patient_id`（后者**跨表** join 服务周期派生，不给 `nursing_plans` 加列）。
     * 每条计划附带 `items` 明细。
     */
    fun list(
        periodId: String? = null,
        patientId: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        periodId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("pl.period_id = \$${values.size + 1}")
            values.add(it)
        }
        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("pe.patient_id = \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY pl.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).compose { rows ->
                val plans = rows.map(Companion::toJson)
                loadItems(plans.mapNotNull { it.getString("id") }).map { itemsById ->
                    JsonObject()
                        .put(
                            "records",
                            JsonArray(plans.map { plan -> plan.copy().put("items", itemsById[plan.getString("id")] ?: JsonArray()) }),
                        )
                        .put("meta", JsonObject().put("total", total))
                }
            }
        }
    }

    // ========================================================================
    //  内部
    // ========================================================================

    private fun loadChildRehabPeriod(periodId: String): Future<JsonObject> =
        pool.preparedQuery(PERIOD_SQL).execute(Tuple.of(periodId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("service period not found: $periodId"))
            if (row.getString("service_type") != ChildRehabPeriodService.SERVICE_TYPE)
                throw IllegalArgumentException("period is not a child rehab period: $periodId")
            Future.succeededFuture(JsonObject().put("patient_id", row.getString("patient_id")))
        }

    /** 明细批量读取：按 `plan_id IN ($1..$n)` 一次取回（无 `= ANY`，避免数组绑定）。 */
    private fun loadItems(planIds: List<String>): Future<Map<String, JsonArray>> {
        val distinct = planIds.filter { !it.isNullOrEmpty() }.distinct()
        if (distinct.isEmpty()) return Future.succeededFuture(emptyMap())

        val placeholders = distinct.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = "SELECT id, plan_id, action, frequency_code, frequency_name, duration_days, remark, " +
            "status, metadata, created_at FROM nursing.nursing_plan_items " +
            "WHERE plan_id IN ($placeholders) ORDER BY created_at"

        return pool.preparedQuery(sql).execute(tupleOf(distinct)).map { rows ->
            val grouped = linkedMapOf<String, JsonArray>()
            for (row in rows) {
                val planId = row.getString("plan_id") ?: continue
                grouped.getOrPut(planId) { JsonArray() }.add(itemToJson(row))
            }
            grouped
        }
    }

    private fun insertItems(planId: String, items: List<JsonObject>, now: OffsetDateTime): Future<Unit> {
        var result: Future<Unit> = Future.succeededFuture(Unit)
        for (item in items) {
            result = result.compose {
                val tuple = Tuple.tuple()
                tuple.addString(Ulid.generate())
                tuple.addString(planId)
                tuple.addString(item.getString("action"))
                tuple.addValue(item.getString("frequency_code"))
                tuple.addValue(item.getString("frequency_name"))
                tuple.addValue(item.getValue("duration_days"))
                tuple.addValue(item.getString("remark"))
                tuple.addString("ACTIVE")
                tuple.addValue(item.getValue("metadata"))
                tuple.addOffsetDateTime(now)
                pool.preparedQuery(INSERT_ITEM_SQL).execute(tuple).map { Unit }
            }
        }
        return result
    }

    /** 明细入参：`action` 必填；`duration_days` 若给必须为正整数。 */
    private fun normalizeItems(value: Any?): List<JsonObject> {
        if (value == null) return emptyList()
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("items must be an array")
        val normalized = mutableListOf<JsonObject>()
        for (index in 0 until array.size()) {
            val raw = array.getValue(index) as? JsonObject
                ?: throw IllegalArgumentException("items[$index] must be an object")
            val action = raw.getString("action")?.trim()
            if (action.isNullOrEmpty())
                throw IllegalArgumentException("items[$index].action is required")

            val duration = raw.getValue("duration_days")
            val durationDays = when (duration) {
                null -> null
                is Number -> duration.toInt()
                is String -> duration.trim().toIntOrNull()
                else -> null
            }
            if (duration != null && (durationDays == null || durationDays <= 0))
                throw IllegalArgumentException("items[$index].duration_days must be a positive integer")

            normalized.add(
                JsonObject()
                    .put("action", action)
                    .put("frequency_code", raw.getString("frequency_code")?.trim()?.takeIf { it.isNotEmpty() })
                    .put("frequency_name", raw.getString("frequency_name")?.trim()?.takeIf { it.isNotEmpty() })
                    .put("duration_days", durationDays)
                    .put("remark", raw.getString("remark")?.trim()?.takeIf { it.isNotEmpty() })
                    .put("metadata", raw.getValue("metadata")),
            )
        }
        return normalized
    }

    private fun parseDate(value: String, field: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
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
