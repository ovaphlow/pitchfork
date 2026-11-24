package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 护理服务周期服务。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 */
class ServicePeriodService(
    private val pool: Pool,
) {
    companion object {
        private val VALID_SERVICE_TYPES = setOf("HOME_CARE", "COMMUNITY_CARE", "HOSPICE")
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("SUSPENDED", "COMPLETED", "CANCELLED"),
            "SUSPENDED" to listOf("ACTIVE", "COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        private val ELDERLY_CARE_OPEN_STATUSES = setOf("ACTIVE", "SUSPENDED")

        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        /** `nursing.nursing_service_periods` 显式列清单（与 [toJson] 读取的键一致）。 */
        private const val PERIOD_COLUMNS =
            "id, patient_id, service_type, start_date, end_date, coordinator, encounter_id, " +
                "status, metadata, created_at, updated_at"

        private const val PERIOD_BY_ID_SQL =
            "SELECT $PERIOD_COLUMNS FROM nursing.nursing_service_periods WHERE id = \$1"

        private const val PERIOD_BY_ENCOUNTER_SQL =
            "SELECT $PERIOD_COLUMNS FROM nursing.nursing_service_periods WHERE encounter_id = \$1"

        private const val PERIOD_BY_ENCOUNTER_LOCKED_SQL =
            "SELECT $PERIOD_COLUMNS FROM nursing.nursing_service_periods " +
                "WHERE encounter_id = \$1 FOR UPDATE"

        private const val PERIOD_INSERT_SQL =
            "INSERT INTO nursing.nursing_service_periods ($PERIOD_COLUMNS) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

        /** 养老入住补建：并发冲突由 `uq_nursing_service_periods_encounter_id` 兜底。 */
        private const val ELDERLY_PERIOD_INSERT_SQL =
            "INSERT INTO nursing.nursing_service_periods " +
                "(id, patient_id, service_type, encounter_id, start_date, status, created_at, updated_at) " +
                "VALUES (\$1, \$2, 'ELDERLY_CARE', \$3, \$4, 'ACTIVE', \$5, \$6) ON CONFLICT DO NOTHING"

        private const val COMPLETE_PERIOD_SQL =
            "UPDATE nursing.nursing_service_periods " +
                "SET status = 'COMPLETED', end_date = \$2, updated_at = \$3 WHERE id = \$1"

        private const val ENCOUNTER_SQL =
            "SELECT id, patient_id, encounter_type, status, admit_date " +
                "FROM healthcare.encounters WHERE id = \$1"

        private const val ENCOUNTER_PATIENT_SQL =
            "SELECT patient_id FROM healthcare.encounters WHERE id = \$1"

        private const val IN_PROGRESS_EXECUTION_SQL =
            "SELECT 1 AS found FROM nursing.nursing_task_executions e " +
                "JOIN nursing.nursing_tasks tt ON tt.id = e.task_id " +
                "WHERE tt.period_id = \$1 AND e.status = 'IN_PROGRESS'"

        /**
         * 从 TIMESTAMPTZ 取业务日（机构时区 [businessZone] 的本地日期）。
         * 库值以 UTC 解释（如 `2026-08-06T16:00:00Z` 即机构本地 `2026-08-07 00:00`），
         * 因此**禁止**直接 `OffsetDateTime.toLocalDate()` —— 那会得到早一天的服务周期开始日。
         */
        private fun businessDate(value: OffsetDateTime): LocalDate =
            value.atZoneSameInstant(businessZone).toLocalDate()

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("patient_id", row.getValue("patient_id")?.toString())
                .put("service_type", row.getValue("service_type")?.toString())
                .put("start_date", row.getValue("start_date")?.toString())
                .put("end_date", row.getValue("end_date")?.toString())
                .put("coordinator", row.getValue("coordinator")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")
        val serviceType = body.getString("service_type")

        if (patientId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        if (serviceType.isNullOrBlank() || serviceType !in VALID_SERVICE_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid service_type, must be one of: $VALID_SERVICE_TYPES"))
        if (body.getString("start_date").isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("start_date is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val tuple = Tuple.of(
            id,
            patientId,
            serviceType,
            LocalDate.parse(body.getString("start_date")),
            body.getString("end_date")?.let { LocalDate.parse(it) },
            body.getString("coordinator"),
            null,
            "ACTIVE",
            if (body.containsKey("metadata")) body.getJsonObject("metadata") else null,
            now,
            now,
        )

        return pool.preparedQuery(PERIOD_INSERT_SQL).execute(tuple).map {
            JsonObject()
                .put("id", id)
                .put("patient_id", patientId)
                .put("service_type", serviceType)
                .put("start_date", body.getString("start_date"))
                .put("end_date", body.getString("end_date"))
                .put("coordinator", body.getString("coordinator"))
                .put("status", "ACTIVE")
                .put("metadata", body.getJsonObject("metadata"))
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
        }
    }

    fun list(
        patientId: String? = null,
        serviceType: String? = null,
        status: String? = null,
        encounterId: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        patientId?.let { where.add("patient_id = ${bind(it)}") }
        serviceType?.let { where.add("service_type = ${bind(it)}") }
        status?.let { where.add("status = ${bind(it)}") }
        encounterId?.let { where.add("encounter_id = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_service_periods$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $PERIOD_COLUMNS FROM nursing.nursing_service_periods$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        return pool.preparedQuery(PERIOD_BY_ID_SQL).execute(Tuple.of(id)).flatMap { rows ->
            if (rows.size() == 0)
                Future.failedFuture(NotFoundException("service period not found: $id"))
            else Future.succeededFuture(toJson(rows.iterator().next()))
        }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            val now = OffsetDateTime.now()
            val sets = mutableListOf<String>()
            val args = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            sets.add("updated_at = ${bind(now)}")
            if (body.containsKey("coordinator"))
                sets.add("coordinator = ${bind(body.getString("coordinator"))}")
            if (body.containsKey("end_date"))
                sets.add("end_date = ${bind(body.getString("end_date")?.let { LocalDate.parse(it) })}")
            if (body.containsKey("metadata"))
                sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")

            val idPlaceholder = bind(id)
            val updateSql =
                "UPDATE nursing.nursing_service_periods SET ${sets.joinToString(", ")} " +
                    "WHERE id = $idPlaceholder"

            pool.preparedQuery(updateSql).execute(Tuple.from(args))
                .flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, newStatus: String): Future<JsonObject> {
        if (newStatus.isBlank())
            return Future.failedFuture(IllegalArgumentException("status is required"))

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
            if (newStatus !in allowedNext)
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus")
                )

            val now = OffsetDateTime.now()
            val updateSql =
                "UPDATE nursing.nursing_service_periods SET status = \$1, updated_at = \$2 WHERE id = \$3"

            pool.preparedQuery(updateSql).execute(Tuple.of(newStatus, now, id))
                .flatMap { get(id) }
        }
    }

    // ========================================================================
    //  养老院入住照护周期 — 连接绑定服务（必须在调用方外层事务内执行）
    // ========================================================================

    /**
     * 在调用方传入的连接上创建 `ELDERLY_CARE` 周期并写回固定字段。
     * 参数必须已由调用方验证；[startDate] 是周期唯一的开始日期来源。
     * 通过唯一约束（部分索引 uq_nursing_service_periods_encounter_id）处理并发补建：
     * 冲突时锁定并读取同一关联周期幂等返回，绝不按患者查找替代。
     */
    fun createElderlyCarePeriod(
        client: SqlClient,
        patientId: String,
        encounterId: String,
        startDate: LocalDate,
        now: OffsetDateTime,
    ): Future<Pair<Boolean, JsonObject>> {
        val id = Ulid.generate()
        val tuple = Tuple.of(id, patientId, encounterId, startDate, now, now)
        return client.preparedQuery(ELDERLY_PERIOD_INSERT_SQL).execute(tuple).compose { result ->
            if (result.rowCount() > 0) {
                Future.succeededFuture(
                    Pair(true, periodJson(id, patientId, encounterId, startDate, null, "ACTIVE", now))
                )
            } else {
                // 并发补建冲突：锁定并读取同一关联周期，幂等返回
                lockAndGetByEncounterId(client, encounterId).map { Pair(false, it) }
            }
        }
    }

    /**
     * 养老入住补建照护周期（幂等）：
     * - 首次成功返回 `(true, period)`，路由映射为 201；
     * - 已存在开放周期（ACTIVE/SUSPENDED）返回 `(false, period)`，路由映射为 200；
     * - 已离院、非养老入住、入住不活动返回 400；
     * - 已存在但周期处于终态返回 409。
     */
    fun enrollElderlyAdmission(encounterId: String): Future<Pair<Boolean, JsonObject>> {
        if (encounterId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("encounter_id is required"))

        return loadElderlyEncounter(encounterId).compose { encounter ->
            val patientId = encounter.getString("patient_id")
            val startDate = encounter.getString("admit_date")
                ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                ?.let { businessDate(it) }
                ?: return@compose Future.failedFuture(IllegalArgumentException("encounter has no admit_date"))

            getByEncounterId(pool, encounterId).compose { existing ->
                if (existing != null) {
                    if (existing.getString("status") !in ELDERLY_CARE_OPEN_STATUSES)
                        return@compose Future.failedFuture(
                            ConflictException("nursing care period is not open for this admission")
                        )
                    return@compose Future.succeededFuture(Pair(false, existing))
                }
                createElderlyCarePeriod(pool, patientId, encounterId, startDate, OffsetDateTime.now())
            }
        }
    }

    /**
     * 锁定关联养老周期并收束为 `COMPLETED`；拒绝缺失、终态和存在执行中任务的情况。
     * 必须在调用方外层事务内执行。
     */
    fun closeElderlyCarePeriod(
        client: SqlClient,
        encounterId: String,
        endDate: LocalDate,
        now: OffsetDateTime,
    ): Future<JsonObject> {
        return lockAndGetByEncounterId(client, encounterId).compose { period ->
            val periodId = period.getString("id")
            if (period.getString("status") !in ELDERLY_CARE_OPEN_STATUSES)
                return@compose Future.failedFuture(
                    ConflictException("nursing care period is not open: ${period.getString("status")}")
                )

            // 校验患者一致性：周期 patient_id 必须与 encounter 的 patient_id 一致
            client.preparedQuery(ENCOUNTER_PATIENT_SQL).execute(Tuple.of(encounterId)).compose { encounterRows ->
                val encounterRow = encounterRows.iterator().asSequence().firstOrNull()
                val encounterPatientId = encounterRow?.getValue("patient_id")?.toString()
                if (encounterPatientId == null)
                    return@compose Future.failedFuture(
                        ConflictException("encounter not found: $encounterId")
                    )
                if (encounterPatientId != period.getString("patient_id"))
                    return@compose Future.failedFuture(
                        ConflictException("patient_id mismatch between period and encounter")
                    )

                client.preparedQuery(IN_PROGRESS_EXECUTION_SQL).execute(Tuple.of(periodId)).compose { rows ->
                    if (rows.size() > 0)
                        return@compose Future.failedFuture(
                            ConflictException("cannot discharge elderly admission while a task execution is in progress")
                        )
                    client.preparedQuery(COMPLETE_PERIOD_SQL)
                        .execute(Tuple.of(periodId, endDate, now))
                        .compose { getById(client, periodId) }
                }
            }
        }
    }

    /**
     * 锁定并验证已完成、可归档的养老照护周期（供离院交接归档使用）。
     * 必须在调用方外层事务内执行；只复用 [encounterId] 的精确关联关系，
     * 不按患者猜测或查找同长者其它入住。
     * - 关联周期缺失、服务类型非养老、状态非 COMPLETED 或结束日期不等于
     *   [expectedEndDate] 时返回 [ConflictException]（409）。
     */
    fun lockCompletedElderlyCarePeriodForHandover(
        client: SqlClient,
        encounterId: String,
        expectedEndDate: LocalDate,
    ): Future<JsonObject> {
        return lockAndGetByEncounterId(client, encounterId).compose { period ->
            if (period.getString("service_type") != "ELDERLY_CARE")
                return@compose Future.failedFuture(
                    ConflictException("nursing care period is not an elderly care period")
                )
            if (period.getString("status") != "COMPLETED")
                return@compose Future.failedFuture(
                    ConflictException("nursing care period is not completed: ${period.getString("status")}")
                )
            if (period.getString("end_date") != expectedEndDate.toString())
                return@compose Future.failedFuture(
                    ConflictException("nursing care period end date does not match discharge date")
                )
            Future.succeededFuture(period)
        }
    }

    // ========================================================================
    //  养老周期私有辅助
    // ========================================================================

    private fun loadElderlyEncounter(encounterId: String): Future<JsonObject> {
        return pool.preparedQuery(ENCOUNTER_SQL).execute(Tuple.of(encounterId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(IllegalArgumentException("encounter not found: $encounterId"))
            val encounterType = row.getString("encounter_type")
            if (encounterType != "ELDERLY_CARE")
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            if (row.getString("status") != "ACTIVE")
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not active"))
            Future.succeededFuture(JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("encounter_type", encounterType)
                .put("status", row.getString("status"))
                .put("admit_date", row.getOffsetDateTime("admit_date")?.toString()))
        }
    }

    private fun getByEncounterId(client: SqlClient, encounterId: String): Future<JsonObject?> =
        client.preparedQuery(PERIOD_BY_ENCOUNTER_SQL).execute(Tuple.of(encounterId)).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { toJson(it) }
        }

    private fun lockAndGetByEncounterId(client: SqlClient, encounterId: String): Future<JsonObject> =
        client.preparedQuery(PERIOD_BY_ENCOUNTER_LOCKED_SQL).execute(Tuple.of(encounterId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(ConflictException("elderly admission has no bound nursing care period: $encounterId"))
            else Future.succeededFuture(toJson(row))
        }

    private fun getById(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(PERIOD_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(NotFoundException("service period not found: $id"))
            else Future.succeededFuture(toJson(row))
        }

    private fun periodJson(
        id: String,
        patientId: String,
        encounterId: String,
        startDate: LocalDate,
        endDate: LocalDate?,
        status: String,
        now: OffsetDateTime,
    ): JsonObject = JsonObject()
        .put("id", id)
        .put("patient_id", patientId)
        .put("service_type", "ELDERLY_CARE")
        .put("start_date", startDate.toString())
        .put("end_date", endDate?.toString())
        .put("coordinator", null)
        .put("encounter_id", encounterId)
        .put("status", status)
        .put("metadata", null)
        .put("created_at", now.toString())
        .put("updated_at", now.toString())
}
