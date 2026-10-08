package com.ovaphlow.crate.dining

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
 * 特殊膳食申请（054 A4，迁移 V602）。
 *
 * ## 状态机（应用层白名单，中文枚举，054 §5.8 冻结）
 *
 * ```
 * 待审批 ──► 已批准 ──► 已终止
 *    │          ▲
 *    ├──► 已驳回 ┘
 *    └──► 已终止（申请人撤回）
 * ```
 * 即：`待审批 → 已批准 / 已驳回 / 已终止`；`已批准 → 已终止`；`已驳回 → 已终止`；
 * `已终止` 为终态。**非法流转一律 `IllegalArgumentException`（路由 400）**，
 * 不允许回到 `待审批`、不允许 `已批准 ⇄ 已驳回`、不允许从终态再流转。
 *
 * ## 其它规则
 *
 * - `request_type ∈ {过敏回避, 治疗膳食, 其他}`、`meal_type ∈ DiningConstants.MEAL_TIMES`。
 * - `status` 由服务端管控：创建恒为 `待审批`，只能经 [updateStatus] 流转；客户端提交即忽略。
 * - `requested_by` 取认证主体；流转到 `已批准/已驳回` 时写 `reviewed_by/reviewed_at`（042 口径）。
 * - `allergens` 为字符串数组（兼容逗号分隔字符串），复用 `dining_diet_profiles.allergies` 口径，
 *   **不新建过敏主数据表**（Lead 裁定 L2 / §11.2 Q7）。
 * - 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class SpecialDietRequestService(private val pool: Pool) {

    companion object {
        /** 申请类型白名单（§5.8 冻结）。 */
        val REQUEST_TYPES = setOf("过敏回避", "治疗膳食", "其他")

        /** 状态白名单（§5.8 冻结）。 */
        val STATUSES = setOf("待审批", "已批准", "已驳回", "已终止")

        /** 终态。 */
        const val TERMINAL_STATUS = "已终止"

        /** 需要写审批人/审批时刻的目标状态。 */
        val REVIEWED_STATUSES = setOf("已批准", "已驳回")

        /** 状态机：当前状态 → 允许的目标状态集合。 */
        val TRANSITIONS: Map<String, Set<String>> = mapOf(
            "待审批" to setOf("已批准", "已驳回", "已终止"),
            "已批准" to setOf("已终止"),
            "已驳回" to setOf("已终止"),
            "已终止" to emptySet(),
        )

        /** 当前状态允许的目标状态；未知状态返回空集（不可流转）。 */
        fun allowedTargets(current: String): Set<String> = TRANSITIONS[current] ?: emptySet()

        /** 合法流转判定（纯函数，供单测与路由复用）。 */
        fun canTransition(from: String, to: String): Boolean = to in allowedTargets(from)

        private const val PATIENT_SQL =
            "SELECT id FROM healthcare.patients WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO dining.dining_special_diet_requests " +
                "(id, patient_id, request_type, meal_type, allergens, reason, status, requested_by, " +
                "effective_from, effective_to, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13)"

        private const val SELECT_COLUMNS =
            "r.id, r.patient_id, p.name AS patient_name, r.request_type, r.meal_type, r.allergens, " +
                "r.reason, r.status, r.requested_by, r.reviewed_by, r.reviewed_at, r.effective_from, " +
                "r.effective_to, r.metadata, r.created_at, r.updated_at"

        private const val SELECT_FROM =
            " FROM dining.dining_special_diet_requests r " +
                "JOIN healthcare.patients p ON p.id = r.patient_id "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE r.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        private const val CURRENT_STATUS_SQL =
            "SELECT status FROM dining.dining_special_diet_requests WHERE id = \$1"

        /** 行 → 申请单 JSON（字段缺失不抛）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("request_type", row.getString("request_type"))
            .put("meal_type", row.getString("meal_type"))
            .put("allergens", row.getJsonArray("allergens") ?: JsonArray())
            .put("reason", row.getString("reason"))
            .put("status", row.getString("status"))
            .put("requested_by", row.getString("requested_by"))
            .put("reviewed_by", row.getString("reviewed_by"))
            .put("reviewed_at", row.getOffsetDateTime("reviewed_at")?.toString())
            .put("effective_from", row.getLocalDate("effective_from")?.toString())
            .put("effective_to", row.getLocalDate("effective_to")?.toString())
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 创建申请单：状态恒为 `待审批`，申请人取认证主体。
     * 参数非法抛 `IllegalArgumentException`（400）；儿童不存在抛 [DiningNotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val requestType = body.getString("request_type")?.trim()
        if (requestType.isNullOrEmpty() || requestType !in REQUEST_TYPES)
            throw IllegalArgumentException("invalid request_type, must be one of: $REQUEST_TYPES")

        val mealType = body.getString("meal_type")?.trim()
        if (mealType.isNullOrEmpty() || mealType !in DiningConstants.MEAL_TIMES)
            throw IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TIMES}")

        val allergens = allergenList(body.getValue("allergens"))
        val reason = body.getString("reason")?.trim()?.takeIf { it.isNotEmpty() }
        val effectiveFrom = parseOptionalDate(body.getString("effective_from"), "effective_from")
        val effectiveTo = parseOptionalDate(body.getString("effective_to"), "effective_to")
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom))
            throw IllegalArgumentException("effective_to must not be before effective_from")
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(patientId)
        tuple.addString(requestType)
        tuple.addString(mealType)
        tuple.addValue(allergens)
        tuple.addValue(reason)
        tuple.addString("待审批")
        tuple.addString(operator)
        tuple.addValue(effectiveFrom)
        tuple.addValue(effectiveTo)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return verifyPatient(patientId).compose {
            pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
        }
    }

    /** 按 id 取单条；不存在 → [DiningNotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("special diet request not found: $id"),
                )
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`patient_id` / `status`。 */
    fun list(
        patientId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("r.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        status?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in STATUSES)
                return Future.failedFuture(
                    IllegalArgumentException("invalid status, must be one of: $STATUSES"),
                )
            conditions.add("r.status = \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY r.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 状态流转。非法目标状态或非法流转抛 `IllegalArgumentException`（400）；
     * 申请单不存在抛 [DiningNotFoundException]（404）。
     */
    fun updateStatus(id: String, status: String?, operator: String): Future<JsonObject> {
        val target = status?.trim()
        if (target.isNullOrEmpty() || target !in STATUSES)
            throw IllegalArgumentException("invalid status, must be one of: $STATUSES")

        return pool.preparedQuery(CURRENT_STATUS_SQL).execute(Tuple.of(id)).compose { rows ->
            val current = rows.iterator().asSequence().firstOrNull()?.getString("status")
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("special diet request not found: $id"),
                )
            if (!canTransition(current, target))
                throw IllegalArgumentException(
                    "invalid status transition: $current → $target, allowed: ${allowedTargets(current)}",
                )

            val now = OffsetDateTime.now()
            val reviewed = target in REVIEWED_STATUSES
            val sql = if (reviewed) {
                "UPDATE dining.dining_special_diet_requests SET status = \$1, reviewed_by = \$2, " +
                    "reviewed_at = \$3, updated_at = \$4 WHERE id = \$5"
            } else {
                "UPDATE dining.dining_special_diet_requests SET status = \$1, updated_at = \$2 WHERE id = \$3"
            }
            val tuple = if (reviewed) {
                Tuple.of(target, operator, now, now, id)
            } else {
                Tuple.of(target, now, id)
            }
            pool.preparedQuery(sql).execute(tuple).compose { get(id) }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun verifyPatient(patientId: String): Future<Unit> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            if (rows.iterator().asSequence().firstOrNull() == null)
                Future.failedFuture(DiningNotFoundException("patient not found: $patientId"))
            else Future.succeededFuture(Unit)
        }

    /** 过敏原：字符串数组或逗号（半/全角）分隔字符串；其它类型 400。 */
    private fun allergenList(value: Any?): JsonArray = when (value) {
        null -> JsonArray()
        is JsonArray -> {
            if (!value.all { it is String })
                throw IllegalArgumentException("allergens must be an array of strings")
            JsonArray(value.map { it.toString().trim() }.filter { it.isNotEmpty() })
        }
        is String -> JsonArray(value.split(',', '，').map(String::trim).filter(String::isNotEmpty))
        else -> throw IllegalArgumentException("allergens 必须是字符串数组，或逗号分隔的字符串")
    }

    private fun parseOptionalDate(value: String?, field: String): LocalDate? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
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
