package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.nursing.tables.NursingIncidentActions.NURSING_INCIDENT_ACTIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingIncidents.NURSING_INCIDENTS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingServicePeriods.NURSING_SERVICE_PERIODS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 院内护理异常事件 — 连接绑定协作与只读查询。
 *
 * 写路径（创建/追加处置/关闭）的所有步骤必须在 Healthcare 外层事务传入的
 * 同一个 [SqlClient] 上执行，本服务不启动自己的事务；事件与审计事实在同一
 * 事务内收束，任一写入失败整体回滚。任何已写入的处置/通知/观察/关闭事实
 * 不得被静默覆盖或删除（服务层不提供 UPDATE/DELETE 入口）。
 *
 * 只读列表/详情走 [pool]，不产生任何写副作用。
 */
class NursingIncidentService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        val VALID_INCIDENT_TYPES = setOf("跌倒/坠床", "走失", "压疮", "误吸/噎食", "用药差错", "感染暴露", "其他")
        val VALID_SEVERITIES = setOf("一般", "较重", "严重")
        val VALID_STATUSES = setOf("已上报", "处理中", "已关闭")
        /** 客户端可追加的动作类别（上报由服务端在创建时写入，关闭走专用接口） */
        val VALID_ACTION_TYPES = setOf("处置", "通知", "观察")
        private val PERIOD_OPEN_STATUSES = setOf("ACTIVE", "SUSPENDED")
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        fun businessDateOf(value: OffsetDateTime): LocalDate =
            value.atZoneSameInstant(businessZone).toLocalDate()

        fun isPeriodOpen(status: String?): Boolean = status in PERIOD_OPEN_STATUSES
    }

    data class ActionInput(
        val actionType: String,
        val body: String,
        val notifiedParty: String?,
        val notificationResult: String?,
    )

    data class IncidentCreateInput(
        val encounterId: String,
        val periodId: String,
        val periodStartDate: LocalDate?,
        val incidentType: String,
        val severity: String,
        val occurredAt: OffsetDateTime,
        val description: String,
        val reporter: String,
        val initialAction: ActionInput?,
    )

    /**
     * 在同一事务内写入事件（状态 `已上报`）与第一条 `上报` 审计事实，
     * 以及可选的即时处置（initial_action，随后推进为 `处理中`）。
     * 调用方必须已锁定 encounter 与精确 period 并完成资格校验。
     */
    fun createIncident(client: SqlClient, input: IncidentCreateInput): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        val insert = ctx.insertInto(NURSING_INCIDENTS)
            .set(NURSING_INCIDENTS.ID, id)
            .set(NURSING_INCIDENTS.ENCOUNTER_ID, input.encounterId)
            .set(NURSING_INCIDENTS.PERIOD_ID, input.periodId)
            .set(NURSING_INCIDENTS.INCIDENT_TYPE, input.incidentType)
            .set(NURSING_INCIDENTS.SEVERITY, input.severity)
            .set(NURSING_INCIDENTS.STATUS, "已上报")
            .set(NURSING_INCIDENTS.OCCURRED_AT, input.occurredAt)
            .set(NURSING_INCIDENTS.DESCRIPTION, input.description)
            .set(NURSING_INCIDENTS.REPORTER, input.reporter)
            .set(NURSING_INCIDENTS.CREATED_AT, now)
            .set(NURSING_INCIDENTS.UPDATED_AT, now)

        return execute(client, insert).compose {
            insertAction(
                client,
                ActionInput("上报", input.description, null, null),
                incidentId = id,
                actor = input.reporter,
                occurredAt = now,
            )
        }.compose { _ ->
            val initial = input.initialAction
            if (initial == null) {
                Future.succeededFuture(incidentJsonFromInput(input, id, "已上报", now))
            } else {
                insertAction(client, initial, incidentId = id, actor = input.reporter, occurredAt = OffsetDateTime.now())
                    .compose { _ ->
                        val update = ctx.update(NURSING_INCIDENTS)
                            .set(NURSING_INCIDENTS.STATUS, "处理中")
                            .set(NURSING_INCIDENTS.UPDATED_AT, OffsetDateTime.now())
                            .where(NURSING_INCIDENTS.ID.eq(id))
                        execute(client, update).map {
                            incidentJsonFromInput(input, id, "处理中", now)
                        }
                    }
            }
        }
    }

    /**
     * 锁定事件并校验当前状态与入住归属。必须在调用方外层事务内执行。
     * 以 `incident_id + encounter_id` 双重归属锁定，事件不属于该入住时返回 404
     * （不泄漏事件在其他入住存在的事实）；只返回锁定行字段，写入决策由调用方
     * 按状态/周期状态收束。
     */
    fun lockIncident(client: SqlClient, incidentId: String, encounterId: String): Future<JsonObject> {
        val query = ctx.selectFrom(NURSING_INCIDENTS)
            .where(
                NURSING_INCIDENTS.ID.eq(incidentId)
                    .and(NURSING_INCIDENTS.ENCOUNTER_ID.eq(encounterId)),
            )
            .forUpdate()
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                return@compose Future.failedFuture(NotFoundException("nursing incident not found: $incidentId"))
            }
            val incident = incidentJsonFromRow(row)
            if (incident.getString("encounter_id") != encounterId) {
                // 与 WHERE 双重归属过滤互为双重校验：跨入住访问按未找到处理
                return@compose Future.failedFuture(NotFoundException("nursing incident not found: $incidentId"))
            }
            Future.succeededFuture(incident)
        }
    }

    /**
     * 追加处置/通知/观察。事件已关闭返回 409；关联周期处于终态（已离院/去世
     * 收束）返回 409——终态后只允许通过 [closeIncident] 追加一次关闭记录。
     * 事件必须精确归属于 [encounterId]，跨入住写入返回 404。追加成功后事件
     * 从 `已上报` 推进为 `处理中`。
     */
    fun appendAction(client: SqlClient, encounterId: String, incidentId: String, input: ActionInput, actor: String): Future<JsonObject> {
        return lockIncident(client, incidentId, encounterId).compose { incident ->
            if (incident.getString("status") == "已关闭") {
                return@compose Future.failedFuture(
                    ConflictException("incident is closed and cannot be modified")
                )
            }
            ensurePeriodNotTerminal(client, requireNotNull(incident.getString("period_id"))).compose {
                val now = OffsetDateTime.now()
                insertAction(client, input, incidentId, actor, now).compose { action ->
                    val status = if (incident.getString("status") == "已上报") "处理中" else incident.getString("status")
                    val update = ctx.update(NURSING_INCIDENTS)
                        .set(NURSING_INCIDENTS.STATUS, status)
                        .set(NURSING_INCIDENTS.UPDATED_AT, now)
                        .where(NURSING_INCIDENTS.ID.eq(incidentId))
                    execute(client, update).map {
                        JsonObject()
                            .put("incident", incident.copy().put("status", status).put("updated_at", now.toString()))
                            .put("action", action)
                    }
                }
            }
        }
    }

    /**
     * 关闭事件：填写关闭说明，写入 `关闭` 事实并将状态转为 `已关闭`。
     * 事件必须精确归属于 [encounterId]，跨入住关闭返回 404。已关闭事件返回
     * 409（关闭后不能重开、编辑或删除）；并发关闭由行锁与状态检查保证至多
     * 成功一次。周期终态后仍允许为既有事件追加一次关闭。
     */
    fun closeIncident(client: SqlClient, encounterId: String, incidentId: String, closeNote: String, actor: String): Future<JsonObject> {
        return lockIncident(client, incidentId, encounterId).compose { incident ->
            if (incident.getString("status") == "已关闭") {
                return@compose Future.failedFuture(
                    ConflictException("incident is already closed")
                )
            }
            val now = OffsetDateTime.now()
            insertAction(client, ActionInput("关闭", closeNote, null, null), incidentId, actor, now).compose { action ->
                val update = ctx.update(NURSING_INCIDENTS)
                    .set(NURSING_INCIDENTS.STATUS, "已关闭")
                    .set(NURSING_INCIDENTS.UPDATED_AT, now)
                    .where(NURSING_INCIDENTS.ID.eq(incidentId))
                execute(client, update).map {
                    JsonObject()
                        .put("incident", incident.copy().put("status", "已关闭").put("updated_at", now.toString()))
                        .put("action", action)
                }
            }
        }
    }

    // ========================================================================
    //  只读：列表与详情（走 pool，不产生任何写副作用）
    // ========================================================================

    /** 按精确 encounter_id 分页列出事件；status/date 过滤均为可选项。 */
    fun listIncidents(
        encounterId: String,
        status: String?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(NURSING_INCIDENTS.ENCOUNTER_ID.eq(encounterId))
        status?.takeIf(String::isNotBlank)?.let { conditions.add(NURSING_INCIDENTS.STATUS.eq(it)) }
        dateFrom?.let { conditions.add(NURSING_INCIDENTS.OCCURRED_AT.ge(it.atStartOfDay(businessZone).toOffsetDateTime())) }
        dateTo?.let { conditions.add(NURSING_INCIDENTS.OCCURRED_AT.lt(it.plusDays(1).atStartOfDay(businessZone).toOffsetDateTime())) }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(NURSING_INCIDENTS).where(conditions)
        val dataQuery = ctx.selectFrom(NURSING_INCIDENTS)
            .where(conditions)
            .orderBy(NURSING_INCIDENTS.OCCURRED_AT.desc(), NURSING_INCIDENTS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map { incidentJsonFromRow(it) }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 事件详情：主事实 + 全部追加审计事实（只读，按时间升序）。
     *  事件必须精确归属于 [encounterId]，跨入住读取返回 404。 */
    fun getIncident(encounterId: String, id: String): Future<JsonObject> {
        val incidentQuery = ctx.selectFrom(NURSING_INCIDENTS)
            .where(
                NURSING_INCIDENTS.ID.eq(id)
                    .and(NURSING_INCIDENTS.ENCOUNTER_ID.eq(encounterId)),
            )
        return execute(pool, incidentQuery).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("nursing incident not found: $id"))
            val incident = incidentJsonFromRow(row)
            if (incident.getString("encounter_id") != encounterId) {
                // 与 WHERE 双重归属过滤互为双重校验：跨入住读取按未找到处理
                return@compose Future.failedFuture(NotFoundException("nursing incident not found: $id"))
            }
            val actionsQuery = ctx.selectFrom(NURSING_INCIDENT_ACTIONS)
                .where(NURSING_INCIDENT_ACTIONS.INCIDENT_ID.eq(id))
                .orderBy(NURSING_INCIDENT_ACTIONS.CREATED_AT.asc(), NURSING_INCIDENT_ACTIONS.ID.asc())
            execute(pool, actionsQuery).map { actionRows ->
                incident.put("actions", JsonArray(actionRows.map { actionJsonFromRow(it) }))
            }
        }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    /** 事件关联周期是否处于终态；终态时非关闭动作一律 409。 */
    private fun ensurePeriodNotTerminal(client: SqlClient, periodId: String): Future<Void> {
        val query = ctx.select(NURSING_SERVICE_PERIODS.STATUS)
            .from(NURSING_SERVICE_PERIODS)
            .where(NURSING_SERVICE_PERIODS.ID.eq(periodId))
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            val status = row?.getString("status")
            if (row == null || !isPeriodOpen(status)) {
                return@compose Future.failedFuture(
                    ConflictException("care period is not active; only a closing note may be appended")
                )
            }
            Future.succeededFuture()
        }
    }

    private fun insertAction(
        client: SqlClient,
        input: ActionInput,
        incidentId: String,
        actor: String,
        occurredAt: OffsetDateTime,
    ): Future<JsonObject> {
        val id = Ulid.generate()
        var insert = ctx.insertInto(NURSING_INCIDENT_ACTIONS)
            .set(NURSING_INCIDENT_ACTIONS.ID, id)
            .set(NURSING_INCIDENT_ACTIONS.INCIDENT_ID, incidentId)
            .set(NURSING_INCIDENT_ACTIONS.ACTION_TYPE, input.actionType)
            .set(NURSING_INCIDENT_ACTIONS.BODY, input.body)
            .set(NURSING_INCIDENT_ACTIONS.ACTOR, actor)
            .set(NURSING_INCIDENT_ACTIONS.OCCURRED_AT, occurredAt)
            .set(NURSING_INCIDENT_ACTIONS.CREATED_AT, occurredAt)
        input.notifiedParty?.let { insert = insert.set(NURSING_INCIDENT_ACTIONS.NOTIFIED_PARTY, it) }
        input.notificationResult?.let { insert = insert.set(NURSING_INCIDENT_ACTIONS.NOTIFICATION_RESULT, it) }

        return execute(client, insert).map {
            JsonObject()
                .put("id", id)
                .put("incident_id", incidentId)
                .put("action_type", input.actionType)
                .put("body", input.body)
                .put("actor", actor)
                .put("occurred_at", occurredAt.toString())
                .put("notified_party", input.notifiedParty)
                .put("notification_result", input.notificationResult)
                .put("created_at", occurredAt.toString())
        }
    }

    private fun incidentJsonFromInput(
        input: IncidentCreateInput,
        id: String,
        status: String,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("encounter_id", input.encounterId)
            .put("period_id", input.periodId)
            .put("incident_type", input.incidentType)
            .put("severity", input.severity)
            .put("status", status)
            .put("occurred_at", input.occurredAt.toString())
            .put("description", input.description)
            .put("reporter", input.reporter)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun incidentJsonFromRow(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("period_id", row.getString("period_id"))
            .put("incident_type", row.getString("incident_type"))
            .put("severity", row.getString("severity"))
            .put("status", row.getString("status"))
            .put("occurred_at", row.getOffsetDateTime("occurred_at")?.toString())
            .put("description", row.getString("description"))
            .put("reporter", row.getString("reporter"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

    private fun actionJsonFromRow(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("incident_id", row.getString("incident_id"))
            .put("action_type", row.getString("action_type"))
            .put("body", row.getString("body"))
            .put("actor", row.getString("actor"))
            .put("occurred_at", row.getOffsetDateTime("occurred_at")?.toString())
            .put("notified_party", row.getString("notified_party"))
            .put("notification_result", row.getString("notification_result"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
