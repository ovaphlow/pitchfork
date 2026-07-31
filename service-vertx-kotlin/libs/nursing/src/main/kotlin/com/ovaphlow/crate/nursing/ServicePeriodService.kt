package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.time.LocalDate
import java.time.OffsetDateTime

class ServicePeriodService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table(DSL.name("nursing", "nursing_service_periods"))

    private val cId = DSL.field("id", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cServiceType = DSL.field("service_type", String::class.java)
    private val cStartDate = DSL.field("start_date", LocalDate::class.java)
    private val cEndDate = DSL.field("end_date", LocalDate::class.java)
    private val cCoordinator = DSL.field("coordinator", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    companion object {
        private val VALID_SERVICE_TYPES = setOf("HOME_CARE", "COMMUNITY_CARE", "HOSPICE")
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("SUSPENDED", "COMPLETED", "CANCELLED"),
            "SUSPENDED" to listOf("ACTIVE", "COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        private val ELDERLY_CARE_OPEN_STATUSES = setOf("ACTIVE", "SUSPENDED")

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

        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cPatientId, patientId)
            .set(cServiceType, serviceType)
            .set(cStartDate, LocalDate.parse(body.getString("start_date")))
            .set(cEndDate, body.getString("end_date")?.let { LocalDate.parse(it) })
            .set(cCoordinator, body.getString("coordinator"))
            .set(cStatus, "ACTIVE")
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
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
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.let { conditions.add(cPatientId.eq(it)) }
        serviceType?.let { conditions.add(cServiceType.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }
        encounterId?.let { conditions.add(cEncounterId.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t)
            .where(conditions)
            .orderBy(cCreatedAt.desc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) records.add(toJson(row))
                        JsonObject().put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0)
                    Future.failedFuture(NotFoundException("service period not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cUpdatedAt, now)

            if (body.containsKey("coordinator"))
                q = q.set(cCoordinator, body.getString("coordinator"))
            if (body.containsKey("end_date"))
                q = q.set(cEndDate, body.getString("end_date")?.let { LocalDate.parse(it) })
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
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
            val updateQuery = ctx.update(t)
                .set(cStatus, newStatus)
                .set(cUpdatedAt, now)
                .where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
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
        val insertQuery = ctx.insertInto(t)
            .set(cId, id)
            .set(cPatientId, patientId)
            .set(cServiceType, "ELDERLY_CARE")
            .set(cEncounterId, encounterId)
            .set(cStartDate, startDate)
            .set(cStatus, "ACTIVE")
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)
            .onConflictDoNothing()

        return execute(client, insertQuery).compose { result ->
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
                ?.toLocalDate()
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
            val encounterQuery = ctx.select(DSL.field("patient_id"))
                .from(DSL.table(DSL.name("healthcare", "encounters")))
                .where(DSL.field("id").eq(encounterId))
            execute(client, encounterQuery).compose { encounterRows ->
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

                val inProgressQuery = ctx.selectOne()
                    .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
                    .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("tt"))
                    .on(DSL.field("e.task_id").eq(DSL.field("tt.id")))
                    .where(DSL.field("tt.period_id").eq(periodId))
                    .and(DSL.field("e.status").eq("IN_PROGRESS"))

                execute(client, inProgressQuery).compose { rows ->
                    if (rows.size() > 0)
                        return@compose Future.failedFuture(
                            ConflictException("cannot discharge elderly admission while a task execution is in progress")
                        )
                    val updateQuery = ctx.update(t)
                        .set(cStatus, "COMPLETED")
                        .set(cEndDate, endDate)
                        .set(cUpdatedAt, now)
                        .where(cId.eq(periodId))
                    execute(client, updateQuery).compose {
                        getById(client, periodId)
                    }
                }
            }
        }
    }

    // ========================================================================
    //  养老周期私有辅助
    // ========================================================================

    private fun loadElderlyEncounter(encounterId: String): Future<JsonObject> {
        val query = ctx.selectFrom(DSL.table(DSL.name("healthcare", "encounters")))
            .where(DSL.field("id").eq(encounterId))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows ->
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

    private fun getByEncounterId(client: SqlClient, encounterId: String): Future<JsonObject?> {
        val query = ctx.selectFrom(t).where(cEncounterId.eq(encounterId))
        return execute(client, query).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { toJson(it) }
        }
    }

    private fun lockAndGetByEncounterId(client: SqlClient, encounterId: String): Future<JsonObject> {
        val query = ctx.selectFrom(t)
            .where(cEncounterId.eq(encounterId))
            .forUpdate()
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(ConflictException("elderly admission has no bound nursing care period: $encounterId"))
            else Future.succeededFuture(toJson(row))
        }
    }

    private fun getById(client: SqlClient, id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(NotFoundException("service period not found: $id"))
            else Future.succeededFuture(toJson(row))
        }
    }

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))

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
