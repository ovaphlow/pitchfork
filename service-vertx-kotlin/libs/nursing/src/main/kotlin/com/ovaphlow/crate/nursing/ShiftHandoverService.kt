package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.nursing.tables.NursingIncidentActions.NURSING_INCIDENT_ACTIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingIncidents.NURSING_INCIDENTS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingShiftHandoverItems.NURSING_SHIFT_HANDOVER_ITEMS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingShiftHandovers.NURSING_SHIFT_HANDOVERS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTaskExecutions.NURSING_TASK_EXECUTIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTasks.NURSING_TASKS
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.Query
import org.jooq.impl.DSL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 班次交接 — 连接绑定协作与只读查询。
 *
 * 写路径（创建/接班/补充）的所有步骤必须在 Healthcare 外层事务传入的同一个
 * [SqlClient] 上执行，本服务不启动自己的事务。交班单以
 * `照护单元 + 上海业务日期 + 班次` 为一个永久唯一的事实单元：
 * - 创建时在只读快照查询中收集该照护单元下的未完成执行、未关闭事件、
 *   本业务日新增护理记录和活动养老入住，保存为结构化事项（绝不按
 *   读取时重新计算覆盖原交班内容）；活动入住/护理记录快照由 Healthcare
 *   连接绑定收集后传入（[HandoverCreateInput] 外的 [JsonObject] 参数）；
 *   本服务只补齐 Nursing 侧的未完成执行与未关闭事件；
 * - 快照只是交接时的事实，不会改变源任务、执行、事件或护理记录的状态；
 * - 同一键同内容重试幂等返回原交班单，同键不同内容或不同键冲突返回 409；
 * - 接班只允许一次，接班人来自认证主体；已接班交班单仍可追加新事项。
 */
class ShiftHandoverService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
    /**
     * Healthcare 注入的连接绑定端口：校验照护单元仍有活动养老入住。
     * 读取 healthcare.encounters 的职责归 Healthcare 侧，Nursing 不直接访问
     * Healthcare 表（§3.1）；本服务只保留 Nursing 事实表的读写。
     */
    private val ensureCareUnitActive: (SqlClient, String) -> Future<Void>,
) {
    companion object {
        val VALID_SHIFTS = setOf("早班", "中班", "夜班")
        private val EXECUTION_OPEN_STATUSES = setOf("PENDING", "IN_PROGRESS")
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        fun sha256Digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    data class HandoverCreateInput(
        val encounterId: String,
        val careUnit: String,
        val businessDate: LocalDate,
        val shift: String,
        val manualItems: List<String>,
        val handoverBy: String,
        val idempotencyKey: String,
    )

    /**
     * 创建交班单：先锁读范围唯一行做幂等比较，未存在则插入头部并写入
     * 自动快照 + 手工事项；并发唯一冲突时重新锁读比较，保证至多一张。
     * [snapshot] 由 Healthcare 在连接上收集（活动养老入住 + 本业务日护理记录），
     * 本服务在同一个 [SqlClient] 上补齐未完成执行与未关闭事件后冻结为事项。
     * 快照读取只读，不改变任何源记录。
     */
    fun createHandover(client: SqlClient, input: HandoverCreateInput, snapshot: JsonObject): Future<Pair<Boolean, JsonObject>> {
        val digest = normalizeDigest(input)
        return lockByScope(client, input.careUnit, input.businessDate, input.shift).compose { existing ->
            if (existing != null) {
                return@compose compareExisting(existing, input, digest)
            }
            buildSnapshot(client, snapshot).compose { fullSnapshot ->
                val now = OffsetDateTime.now()
                val id = Ulid.generate()
                val insertHead = ctx.insertInto(NURSING_SHIFT_HANDOVERS)
                    .set(NURSING_SHIFT_HANDOVERS.ID, id)
                    .set(NURSING_SHIFT_HANDOVERS.CARE_UNIT, input.careUnit)
                    .set(NURSING_SHIFT_HANDOVERS.BUSINESS_DATE, input.businessDate)
                    .set(NURSING_SHIFT_HANDOVERS.SHIFT, input.shift)
                    .set(NURSING_SHIFT_HANDOVERS.HANDOVER_BY, input.handoverBy)
                    .set(NURSING_SHIFT_HANDOVERS.HANDED_OVER_AT, now)
                    .set(NURSING_SHIFT_HANDOVERS.STATUS, "待接班")
                    .set(NURSING_SHIFT_HANDOVERS.IDEMPOTENCY_KEY, input.idempotencyKey)
                    .set(NURSING_SHIFT_HANDOVERS.CONTENT_DIGEST, digest)
                    .set(NURSING_SHIFT_HANDOVERS.CREATED_AT, now)
                    .set(NURSING_SHIFT_HANDOVERS.UPDATED_AT, now)
                execute(client, insertHead).compose { _ ->
                    insertItems(client, id, input.handoverBy, now, fullSnapshot, input.manualItems).map {
                        Pair(true, handoverResponse(id, input, digest, now, fullSnapshot))
                    }
                }.recover { err ->
                    if (!isUniqueViolation(err)) {
                        return@recover Future.failedFuture(err)
                    }
                    // 并发创建：重新锁读并按输入比较，不泄漏数据库约束文本
                    lockByScope(client, input.careUnit, input.businessDate, input.shift).compose { row ->
                        if (row == null) Future.failedFuture(err)
                        else compareExisting(row, input, digest)
                    }
                }
            }
        }
    }

    /**
     * 接班：只允许一次，接班人由调用方从认证主体传入。
     * 周期终态（照护单元不再有活动养老入住）后交班单只读，返回 409。
     */
    fun receiveHandover(client: SqlClient, id: String, receiver: String): Future<JsonObject> {
        return lockHandover(client, id).compose { handover ->
            if (handover.getString("received_by") != null) {
                return@compose Future.failedFuture(
                    ConflictException("handover has already been received by ${handover.getString("received_by")}")
                )
            }
            ensureCareUnitActive(client, requireNotNull(handover.getString("care_unit"))).compose {
                val now = OffsetDateTime.now()
                val update = ctx.update(NURSING_SHIFT_HANDOVERS)
                    .set(NURSING_SHIFT_HANDOVERS.RECEIVED_BY, receiver)
                    .set(NURSING_SHIFT_HANDOVERS.RECEIVED_AT, now)
                    .set(NURSING_SHIFT_HANDOVERS.STATUS, "已接班")
                    .set(NURSING_SHIFT_HANDOVERS.UPDATED_AT, now)
                    .where(NURSING_SHIFT_HANDOVERS.ID.eq(id))
                execute(client, update).compose { getHandover(client, id) }
            }
        }
    }

    /**
     * 补充事项：只接受正文，来源关联与创建人由服务端写入。
     * 已接班交班单仍可追加新事项（只读快照内容不可变）；周期终态后只读。
     */
    fun appendItem(client: SqlClient, id: String, content: String, createdBy: String): Future<JsonObject> {
        return lockHandover(client, id).compose { handover ->
            ensureCareUnitActive(client, requireNotNull(handover.getString("care_unit"))).compose {
                val now = OffsetDateTime.now()
                val itemId = Ulid.generate()
                val insert = ctx.insertInto(NURSING_SHIFT_HANDOVER_ITEMS)
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.ID, itemId)
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID, id)
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.ITEM_KIND, "手工")
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.SUMMARY, content)
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.CREATED_BY, createdBy)
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.SNAPSHOT_AT, now)
                    .set(NURSING_SHIFT_HANDOVER_ITEMS.CREATED_AT, now)
                execute(client, insert).compose {
                    val update = ctx.update(NURSING_SHIFT_HANDOVERS)
                        .set(NURSING_SHIFT_HANDOVERS.UPDATED_AT, now)
                        .where(NURSING_SHIFT_HANDOVERS.ID.eq(id))
                    execute(client, update).compose { getHandover(client, id) }
                }
            }
        }
    }

    /** 锁定交接单头。必须在调用方外层事务内执行。 */
    fun lockHandover(client: SqlClient, id: String): Future<JsonObject> {
        val query = ctx.selectFrom(NURSING_SHIFT_HANDOVERS)
            .where(NURSING_SHIFT_HANDOVERS.ID.eq(id))
            .forUpdate()
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                return@compose Future.failedFuture(NotFoundException("shift handover not found: $id"))
            }
            Future.succeededFuture(handoverHeadFromRow(row))
        }
    }

    // ========================================================================
    //  只读：列表与详情（走 pool，不产生任何写副作用）
    // ========================================================================

    /** 按照护单元分页列出交班单；business_date/shift 过滤均为可选项。 */
    fun listHandovers(
        careUnit: String,
        businessDate: LocalDate?,
        shift: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(NURSING_SHIFT_HANDOVERS.CARE_UNIT.eq(careUnit))
        businessDate?.let { conditions.add(NURSING_SHIFT_HANDOVERS.BUSINESS_DATE.eq(it)) }
        shift?.takeIf(String::isNotBlank)?.let { conditions.add(NURSING_SHIFT_HANDOVERS.SHIFT.eq(it)) }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(NURSING_SHIFT_HANDOVERS).where(conditions)
        val dataQuery = ctx.selectFrom(NURSING_SHIFT_HANDOVERS)
            .where(conditions)
            .orderBy(NURSING_SHIFT_HANDOVERS.BUSINESS_DATE.desc(), NURSING_SHIFT_HANDOVERS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).compose { rows ->
                val heads = rows.map { handoverHeadFromRow(it) }
                if (heads.isEmpty()) {
                    return@compose Future.succeededFuture(
                        JsonObject().put("records", JsonArray()).put("meta", JsonObject().put("total", total))
                    )
                }
                countItems(pool, heads.mapNotNull { it.getString("id") }).map { counts ->
                    val records = JsonArray()
                    for (head in heads) {
                        records.add(head.copy().put("item_count", counts[head.getString("id")] ?: 0L))
                    }
                    JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                }
            }
        }
    }

    /** 交接单详情：头 + 全部事项（只读）。 */
    fun getHandover(id: String): Future<JsonObject> = getHandover(pool, id)

    private fun getHandover(client: SqlClient, id: String): Future<JsonObject> {
        val headQuery = ctx.selectFrom(NURSING_SHIFT_HANDOVERS)
            .where(NURSING_SHIFT_HANDOVERS.ID.eq(id))
        return execute(client, headQuery).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("shift handover not found: $id"))
            val itemsQuery = ctx.selectFrom(NURSING_SHIFT_HANDOVER_ITEMS)
                .where(NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID.eq(id))
                .orderBy(NURSING_SHIFT_HANDOVER_ITEMS.CREATED_AT.asc(), NURSING_SHIFT_HANDOVER_ITEMS.ID.asc())
            execute(client, itemsQuery).map { itemRows ->
                handoverHeadFromRow(row)
                    .put("items", JsonArray(itemRows.map { handoverItemFromRow(it) }))
            }
        }
    }

    // ========================================================================
    //  快照装配（只读批量查询，固定查询次数，不产生任何写副作用）
    //  Healthcare 已在连接上收集活动养老入住与本业务日护理记录（生成表类），
    //  本服务只补齐 Nursing 事实表的未完成执行与未关闭事件。
    // ========================================================================

    private fun buildSnapshot(client: SqlClient, base: JsonObject): Future<JsonObject> {
        val admissions = base.getJsonArray("admissions")
        val records = base.getJsonArray("records")
        val periodIds = admissions.mapNotNull { (it as JsonObject).getString("period_id") }
        if (periodIds.isEmpty()) {
            return Future.succeededFuture(
                JsonObject()
                    .put("admissions", admissions)
                    .put("executions", JsonArray())
                    .put("incidents", JsonArray())
                    .put("records", records)
            )
        }
        val executionsF = loadOpenExecutions(client, periodIds)
        val incidentsF = loadOpenIncidents(client, periodIds)
        return CompositeFuture.all(listOf(executionsF, incidentsF)).map { composite ->
            JsonObject()
                .put("admissions", admissions)
                .put("executions", JsonArray(composite.resultAt<List<JsonObject>>(0)))
                .put("incidents", JsonArray(composite.resultAt<List<JsonObject>>(1)))
                .put("records", records)
        }
    }

    /** 本班尚未完成或进行中的护理执行（PENDING / IN_PROGRESS）。 */
    private fun loadOpenExecutions(client: SqlClient, periodIds: List<String>): Future<List<JsonObject>> {
        val query = ctx.select(
            NURSING_TASK_EXECUTIONS.ID,
            NURSING_TASK_EXECUTIONS.TASK_ID,
            NURSING_TASK_EXECUTIONS.PLANNED_TIME,
            NURSING_TASK_EXECUTIONS.ACTUAL_TIME,
            NURSING_TASK_EXECUTIONS.EXECUTOR,
            NURSING_TASK_EXECUTIONS.STATUS,
            NURSING_TASK_EXECUTIONS.NOTE,
            NURSING_TASKS.DESCRIPTION.`as`("task_description"),
            NURSING_TASKS.PERIOD_ID,
        )
            .from(NURSING_TASK_EXECUTIONS)
            .join(NURSING_TASKS).on(NURSING_TASK_EXECUTIONS.TASK_ID.eq(NURSING_TASKS.ID))
            .where(
                NURSING_TASKS.PERIOD_ID.`in`(periodIds)
                    .and(NURSING_TASK_EXECUTIONS.STATUS.`in`(EXECUTION_OPEN_STATUSES)),
            )
            .orderBy(NURSING_TASK_EXECUTIONS.PLANNED_TIME.asc().nullsLast(), NURSING_TASK_EXECUTIONS.CREATED_AT.asc())
        return execute(client, query).map { rows ->
            rows.map { row ->
                JsonObject()
                    .put("id", row.getString("id"))
                    .put("task_id", row.getString("task_id"))
                    .put("period_id", row.getString("period_id"))
                    .put("task_description", row.getString("task_description"))
                    .put("status", row.getString("status"))
                    .put("planned_time", row.getOffsetDateTime("planned_time")?.toString())
                    .put("executor", row.getString("executor"))
            }
        }
    }

    /** 尚未关闭的异常事件及最新处置摘要（两批查询，内存归组，无 N+1）。 */
    private fun loadOpenIncidents(client: SqlClient, periodIds: List<String>): Future<List<JsonObject>> {
        val incidentQuery = ctx.selectFrom(NURSING_INCIDENTS)
            .where(
                NURSING_INCIDENTS.PERIOD_ID.`in`(periodIds)
                    .and(NURSING_INCIDENTS.STATUS.ne("已关闭")),
            )
            .orderBy(NURSING_INCIDENTS.OCCURRED_AT.asc(), NURSING_INCIDENTS.CREATED_AT.asc())
        return execute(client, incidentQuery).compose { incidentRows ->
            val incidents = incidentRows.map { row ->
                JsonObject()
                    .put("id", row.getString("id"))
                    .put("period_id", row.getString("period_id"))
                    .put("incident_type", row.getString("incident_type"))
                    .put("severity", row.getString("severity"))
                    .put("status", row.getString("status"))
                    .put("occurred_at", row.getOffsetDateTime("occurred_at")?.toString())
                    .put("description", row.getString("description"))
            }
            if (incidents.isEmpty()) {
                return@compose Future.succeededFuture(emptyList())
            }
            val incidentIds = incidents.mapNotNull { it.getString("id") }
            val actionQuery = ctx.selectFrom(NURSING_INCIDENT_ACTIONS)
                .where(NURSING_INCIDENT_ACTIONS.INCIDENT_ID.`in`(incidentIds))
                .orderBy(NURSING_INCIDENT_ACTIONS.CREATED_AT.asc(), NURSING_INCIDENT_ACTIONS.ID.asc())
            execute(client, actionQuery).map { actionRows ->
                val latestByIncident = mutableMapOf<String, JsonObject>()
                for (actionRow in actionRows) {
                    latestByIncident[actionRow.getString("incident_id")] = JsonObject()
                        .put("action_type", actionRow.getString("action_type"))
                        .put("body", actionRow.getString("body"))
                        .put("actor", actionRow.getString("actor"))
                }
                incidents.map { incident ->
                    incident.copy().put("latest_action", latestByIncident[incident.getString("id")])
                }
            }
        }
    }

    // ========================================================================
    //  事项写入与响应组装
    // ========================================================================

    private fun insertItems(
        client: SqlClient,
        handoverId: String,
        createdBy: String,
        now: OffsetDateTime,
        snapshot: JsonObject,
        manualItems: List<String>,
    ): Future<Void> {
        val items = mutableListOf<JsonObject>()

        val admissions = snapshot.getJsonArray("admissions")
        for (raw in admissions) {
            val admission = raw as JsonObject
            items.add(JsonObject()
                .put("item_kind", "入住")
                .put("encounter_id", admission.getString("encounter_id"))
                .put("period_id", admission.getString("period_id"))
                .put("source_id", admission.getString("encounter_id"))
                .put("summary", admissionSummary(admission)))
        }
        val executions = snapshot.getJsonArray("executions")
        for (raw in executions) {
            val execution = raw as JsonObject
            items.add(JsonObject()
                .put("item_kind", "执行")
                .put("encounter_id", null)
                .put("period_id", execution.getString("period_id"))
                .put("source_id", execution.getString("id"))
                .put("summary", executionSummary(execution)))
        }
        val incidents = snapshot.getJsonArray("incidents")
        for (raw in incidents) {
            val incident = raw as JsonObject
            items.add(JsonObject()
                .put("item_kind", "事件")
                .put("encounter_id", null)
                .put("period_id", incident.getString("period_id"))
                .put("source_id", incident.getString("id"))
                .put("summary", incidentSummary(incident)))
        }
        val records = snapshot.getJsonArray("records")
        for (raw in records) {
            val record = raw as JsonObject
            items.add(JsonObject()
                .put("item_kind", "护理记录")
                .put("encounter_id", null)
                .put("period_id", record.getString("period_id"))
                .put("source_id", record.getString("id"))
                .put("summary", recordSummary(record)))
        }
        for (manual in manualItems) {
            items.add(JsonObject()
                .put("item_kind", "手工")
                .put("encounter_id", null)
                .put("period_id", null)
                .put("source_id", null)
                .put("summary", manual))
        }

        fun loop(index: Int): Future<Void> {
            if (index >= items.size) return Future.succeededFuture()
            val item = items[index]
            var insert = ctx.insertInto(NURSING_SHIFT_HANDOVER_ITEMS)
                .set(NURSING_SHIFT_HANDOVER_ITEMS.ID, Ulid.generate())
                .set(NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID, handoverId)
                .set(NURSING_SHIFT_HANDOVER_ITEMS.ITEM_KIND, item.getString("item_kind"))
                .set(NURSING_SHIFT_HANDOVER_ITEMS.SUMMARY, requireNotNull(item.getString("summary")))
                .set(NURSING_SHIFT_HANDOVER_ITEMS.CREATED_BY, createdBy)
                .set(NURSING_SHIFT_HANDOVER_ITEMS.SNAPSHOT_AT, now)
                .set(NURSING_SHIFT_HANDOVER_ITEMS.CREATED_AT, now)
            item.getString("encounter_id")?.let { insert = insert.set(NURSING_SHIFT_HANDOVER_ITEMS.ENCOUNTER_ID, it) }
            item.getString("period_id")?.let { insert = insert.set(NURSING_SHIFT_HANDOVER_ITEMS.PERIOD_ID, it) }
            item.getString("source_id")?.let { insert = insert.set(NURSING_SHIFT_HANDOVER_ITEMS.SOURCE_ID, it) }
            return execute(client, insert).compose { loop(index + 1) }
        }
        return loop(0)
    }

    private fun admissionSummary(admission: JsonObject): String {
        val name = admission.getString("patient_name") ?: ""
        val encounterNo = admission.getString("encounter_no") ?: ""
        val ward = admission.getString("ward")
        val base = "${name}（住院号 ${encounterNo}）"
        return if (!ward.isNullOrBlank()) "$base · 房间床位 ${ward}" else base
    }

    private fun executionSummary(execution: JsonObject): String {
        val description = execution.getString("task_description") ?: ""
        val status = execution.getString("status") ?: ""
        val statusLabel = if (status == "IN_PROGRESS") "执行中" else "待执行"
        val planned = execution.getString("planned_time")
        val timePart = planned?.let { " · 计划 ${formatCompactTime(it)}" } ?: ""
        return "未完成执行：$description · $statusLabel$timePart"
    }

    private fun incidentSummary(incident: JsonObject): String {
        val type = incident.getString("incident_type") ?: ""
        val severity = incident.getString("severity") ?: ""
        val status = incident.getString("status") ?: ""
        val latest = incident.getJsonObject("latest_action")
        val latestPart = latest?.getString("body")?.take(80)?.replace("\n", " ")?.let { " · $it" } ?: ""
        return "异常事件：$type · $severity · $status$latestPart"
    }

    private fun recordSummary(record: JsonObject): String {
        val title = record.getString("title") ?: ""
        val content = record.getString("content")?.take(80)?.replace("\n", " ") ?: ""
        return "护理记录：$title${if (content.isNotBlank()) " · $content" else ""}"
    }

    private fun formatCompactTime(value: String): String =
        try {
            OffsetDateTime.parse(value).atZoneSameInstant(businessZone).toLocalDateTime().toString().replace("T", " ")
        } catch (_: RuntimeException) {
            value
        }

    private fun handoverResponse(
        id: String,
        input: HandoverCreateInput,
        digest: String,
        now: OffsetDateTime,
        snapshot: JsonObject,
    ): JsonObject {
        val items = JsonArray()
        val itemCount = snapshot.getJsonArray("admissions").size() +
            snapshot.getJsonArray("executions").size() +
            snapshot.getJsonArray("incidents").size() +
            snapshot.getJsonArray("records").size() +
            input.manualItems.size
        return JsonObject()
            .put("id", id)
            .put("care_unit", input.careUnit)
            .put("business_date", input.businessDate.toString())
            .put("shift", input.shift)
            .put("handover_by", input.handoverBy)
            .put("handed_over_at", now.toString())
            .put("received_by", null)
            .put("received_at", null)
            .put("status", "待接班")
            .put("item_count", itemCount)
            .put("items", items)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())
    }

    private fun compareExisting(
        existing: Row,
        input: HandoverCreateInput,
        digest: String,
    ): Future<Pair<Boolean, JsonObject>> {
        val sameKey = existing.getString("idempotency_key") == input.idempotencyKey
        val sameContent = existing.getString("content_digest") == digest
        if (sameKey && sameContent) {
            return Future.succeededFuture(Pair(false, handoverHeadFromRow(existing)))
        }
        return Future.failedFuture(
            ConflictException("shift handover already exists for this care unit, business date and shift")
        )
    }

    private fun lockByScope(
        client: SqlClient,
        careUnit: String,
        businessDate: LocalDate,
        shift: String,
    ): Future<Row?> {
        val query = ctx.selectFrom(NURSING_SHIFT_HANDOVERS)
            .where(
                NURSING_SHIFT_HANDOVERS.CARE_UNIT.eq(careUnit)
                    .and(NURSING_SHIFT_HANDOVERS.BUSINESS_DATE.eq(businessDate))
                    .and(NURSING_SHIFT_HANDOVERS.SHIFT.eq(shift)),
            )
            .forUpdate()
        return execute(client, query).map { rows ->
            rows.iterator().asSequence().firstOrNull()
        }
    }

    /** 周期终态判定由 Healthcare 注入的端口完成（读 healthcare.encounters），本服务不再直接访问。 */
    private fun countItems(client: SqlClient, handoverIds: List<String>): Future<Map<String, Long>> {
        val query = ctx.select(
            NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID,
            DSL.count().`as`("item_count"),
        )
            .from(NURSING_SHIFT_HANDOVER_ITEMS)
            .where(NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID.`in`(handoverIds))
            .groupBy(NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID)
        return execute(client, query).map { rows ->
            rows.associate { it.getString("handover_id") to (it.getLong("item_count") ?: 0L) }
        }
    }

    private fun handoverHeadFromRow(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("care_unit", row.getString("care_unit"))
            .put("business_date", row.getLocalDate("business_date")?.toString())
            .put("shift", row.getString("shift"))
            .put("handover_by", row.getString("handover_by"))
            .put("handed_over_at", row.getOffsetDateTime("handed_over_at")?.toString())
            .put("received_by", row.getString("received_by"))
            .put("received_at", row.getOffsetDateTime("received_at")?.toString())
            .put("status", row.getString("status"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

    private fun handoverItemFromRow(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("handover_id", row.getString("handover_id"))
            .put("item_kind", row.getString("item_kind"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("period_id", row.getString("period_id"))
            .put("source_id", row.getString("source_id"))
            .put("summary", row.getString("summary"))
            .put("created_by", row.getString("created_by"))
            .put("snapshot_at", row.getOffsetDateTime("snapshot_at")?.toString())
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

    private fun normalizeDigest(input: HandoverCreateInput): String =
        sha256Digest(
            listOf(
                input.businessDate.toString(),
                input.shift,
            ).plus(input.manualItems.sorted()).joinToString("\u0001"),
        )

    private fun isUniqueViolation(err: Throwable): Boolean {
        var current: Throwable? = err
        while (current != null) {
            if (current is PgException && current.sqlState == "23505") return true
            current = current.cause
        }
        return false
    }

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
