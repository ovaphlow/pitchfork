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
 * 儿童康复服务周期（054 B1，迁移 V409）。
 *
 * 规则：
 *  1. `service_type` 恒为 `CHILD_REHAB`（英文 code，§5.8 N15），客户端提交即忽略。
 *  2. **`encounter_id` 恒为 NULL**：儿童社区/门诊康复没有入住周期，V404 的
 *     `chk_elderly_care_encounter_link` 第二分支（非 ELDERLY_CARE ⇒ encounter_id IS NULL）强制此约束。
 *  3. **同儿童至多一个 `ACTIVE` 周期**：由部分唯一索引
 *     `uq_nursing_service_periods_active_child_rehab (patient_id) WHERE service_type='CHILD_REHAB' AND status='ACTIVE'`
 *     兜底（并发安全）；服务层把 23505 映射为 [ConflictException]（路由 409），
 *     包括「暂停中的周期被重新启用」这一路径。
 *  4. 状态沿用既有英文枚举 `ACTIVE / SUSPENDED / COMPLETED / CANCELLED`，流转与
 *     [ServicePeriodService] 同口径：ACTIVE ⇄ SUSPENDED，二者 → COMPLETED/CANCELLED；
 *     终态不可再流转（非法流转 → 400）。
 *  5. 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class ChildRehabPeriodService(private val pool: Pool) {

    companion object {
        /** 儿童康复服务类型（英文 code，§5.8 冻结）。 */
        const val SERVICE_TYPE = "CHILD_REHAB"

        /** 周期状态白名单（沿用既有列，不新增枚举）。 */
        val STATUSES = setOf("ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED")

        /** 状态机（与 `ServicePeriodService.VALID_STATUS_TRANSITIONS` 同口径）。 */
        val TRANSITIONS: Map<String, Set<String>> = mapOf(
            "ACTIVE" to setOf("SUSPENDED", "COMPLETED", "CANCELLED"),
            "SUSPENDED" to setOf("ACTIVE", "COMPLETED", "CANCELLED"),
            "COMPLETED" to emptySet(),
            "CANCELLED" to emptySet(),
        )

        /** 部分唯一索引名（409 判定与真库验收共用同一常量）。 */
        const val ACTIVE_UNIQUE_INDEX = "uq_nursing_service_periods_active_child_rehab"

        fun allowedTargets(current: String): Set<String> = TRANSITIONS[current] ?: emptySet()

        fun canTransition(from: String, to: String): Boolean = to in allowedTargets(from)

        private const val PATIENT_SQL =
            "SELECT id, name FROM healthcare.patients WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_service_periods " +
                "(id, patient_id, service_type, encounter_id, start_date, end_date, coordinator, " +
                "status, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, NULL, \$4, \$5, \$6, \$7, \$8, \$9, \$10)"

        private const val SELECT_COLUMNS =
            "p.id, p.patient_id, pa.name AS patient_name, p.service_type, p.start_date, p.end_date, " +
                "p.coordinator, p.status, p.metadata, p.created_at, p.updated_at"

        private const val SELECT_FROM =
            " FROM nursing.nursing_service_periods p " +
                "JOIN healthcare.patients pa ON pa.id = p.patient_id "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE p.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        private const val CURRENT_STATUS_SQL =
            "SELECT status FROM nursing.nursing_service_periods WHERE id = \$1"

        /** 行 → 周期 JSON（字段缺失不抛）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("service_type", row.getString("service_type"))
            .put("start_date", row.getLocalDate("start_date")?.toString())
            .put("end_date", row.getLocalDate("end_date")?.toString())
            .put("coordinator", row.getString("coordinator"))
            .put("status", row.getString("status"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 创建儿童康复周期：`service_type=CHILD_REHAB`、`encounter_id=NULL`、`status=ACTIVE`。
     * 参数非法抛 `IllegalArgumentException`（400）；儿童不存在抛 [NotFoundException]（404）；
     * 同儿童已有启用周期抛 [ConflictException]（409）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val startDate = parseDate(body.getString("start_date"), "start_date")
        val endDate = body.getString("end_date")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { parseDate(it, "end_date") }
        if (endDate != null && endDate.isBefore(startDate))
            throw IllegalArgumentException("end_date must not be before start_date")

        val coordinator = body.getString("coordinator")?.trim()?.takeIf { it.isNotEmpty() } ?: operator
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(patientId)
        tuple.addString(SERVICE_TYPE)
        tuple.addLocalDate(startDate)
        tuple.addValue(endDate)
        tuple.addValue(coordinator)
        tuple.addString("ACTIVE")
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return verifyPatient(patientId).compose {
            pool.preparedQuery(INSERT_SQL).execute(tuple)
                .compose { get(id) }
                .recover { error ->
                    if (isUniqueViolation(error, setOf(ACTIVE_UNIQUE_INDEX)))
                        Future.failedFuture(ConflictException("该儿童已有启用中的康复服务周期"))
                    else Future.failedFuture(error)
                }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("child rehab period not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`patient_id` / `status`。 */
    fun list(
        patientId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf("p.service_type = \$1")
        val values = mutableListOf<Any>(SERVICE_TYPE)

        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("p.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        status?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in STATUSES)
                return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: $STATUSES"))
            conditions.add("p.status = \$${values.size + 1}")
            values.add(it)
        }

        val where = " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY p.start_date DESC, p.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

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
     * 不存在抛 [NotFoundException]（404）；重新启用时命中部分唯一索引 → [ConflictException]（409）。
     */
    fun updateStatus(id: String, status: String?): Future<JsonObject> {
        val target = status?.trim()
        if (target.isNullOrEmpty() || target !in STATUSES)
            throw IllegalArgumentException("invalid status, must be one of: $STATUSES")

        return pool.preparedQuery(CURRENT_STATUS_SQL).execute(Tuple.of(id)).compose { rows ->
            val current = rows.iterator().asSequence().firstOrNull()?.getString("status")
                ?: return@compose Future.failedFuture(NotFoundException("child rehab period not found: $id"))
            if (!canTransition(current, target))
                throw IllegalArgumentException(
                    "cannot transition from $current to $target",
                )

            val now = OffsetDateTime.now()
            val sql = "UPDATE nursing.nursing_service_periods SET status = \$1, updated_at = \$2 WHERE id = \$3"
            pool.preparedQuery(sql).execute(Tuple.of(target, now, id))
                .compose { get(id) }
                .recover { error ->
                    if (isUniqueViolation(error, setOf(ACTIVE_UNIQUE_INDEX)))
                        Future.failedFuture(ConflictException("该儿童已有启用中的康复服务周期"))
                    else Future.failedFuture(error)
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

    private fun parseDate(value: String?, field: String): LocalDate {
        val text = value?.trim()
        if (text.isNullOrEmpty()) throw IllegalArgumentException("$field is required")
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
