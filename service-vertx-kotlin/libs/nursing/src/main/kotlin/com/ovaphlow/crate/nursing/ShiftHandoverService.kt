package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
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
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 */
class ShiftHandoverService(
    private val pool: Pool,
    /**
     * Healthcare 注入的连接绑定端口：校验照护单元仍有活动养老入住。
     * 读取 healthcare.encounters 的职责归 Healthcare 侧，Nursing 不直接访问
     * Healthcare 表（§3.1）；本服务只保留 Nursing 事实表的读写。
     */
    private val ensureCareUnitActive: (SqlClient, String) -> Future<Void>,
) {
    companion object {
        val VALID_SHIFTS = setOf("早班", "中班", "夜班")
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        /** `nursing.nursing_shift_handovers` 显式列清单（与 [handoverHeadFromRow] 读取的键一致）。 */
        private const val HANDOVER_COLUMNS =
            "id, care_unit, business_date, shift, handover_by, handed_over_at, received_by, " +
                "received_at, status, created_at, updated_at"

        /** `nursing.nursing_shift_handover_items` 显式列清单。 */
        private const val ITEM_COLUMNS =
            "id, handover_id, item_kind, encounter_id, period_id, source_id, summary, " +
                "created_by, snapshot_at, created_at"

        private const val HANDOVER_BY_ID_SQL =
            "SELECT $HANDOVER_COLUMNS FROM nursing.nursing_shift_handovers WHERE id = \$1"

        private const val HANDOVER_BY_ID_LOCKED_SQL =
            "SELECT $HANDOVER_COLUMNS FROM nursing.nursing_shift_handovers WHERE id = \$1 FOR UPDATE"

        private const val HANDOVER_BY_SCOPE_LOCKED_SQL =
            "SELECT $HANDOVER_COLUMNS FROM nursing.nursing_shift_handovers " +
                "WHERE care_unit = \$1 AND business_date = \$2 AND shift = \$3 FOR UPDATE"

        private const val HANDOVER_ITEMS_SQL =
            "SELECT $ITEM_COLUMNS FROM nursing.nursing_shift_handover_items " +
                "WHERE handover_id = \$1 ORDER BY created_at ASC, id ASC"

        private const val HANDOVER_INSERT_SQL =
            "INSERT INTO nursing.nursing_shift_handovers " +
                "(id, care_unit, business_date, shift, handover_by, handed_over_at, status, " +
                "idempotency_key, content_digest, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, '待接班', \$7, \$8, \$9, \$10)"

        /** 手工补充事项：来源关联恒空，创建人/快照时间由服务端写入。 */
        private const val MANUAL_ITEM_INSERT_SQL =
            "INSERT INTO nursing.nursing_shift_handover_items " +
                "(id, handover_id, item_kind, summary, created_by, snapshot_at, created_at) " +
                "VALUES (\$1, \$2, '手工', \$3, \$4, \$5, \$6)"

        private const val RECEIVE_SQL =
            "UPDATE nursing.nursing_shift_handovers " +
                "SET received_by = \$2, received_at = \$3, status = '已接班', updated_at = \$4 " +
                "WHERE id = \$1"

        private const val TOUCH_HANDOVER_SQL =
            "UPDATE nursing.nursing_shift_handovers SET updated_at = \$2 WHERE id = \$1"

        private const val OPEN_EXECUTIONS_SQL =
            "SELECT e.id, e.task_id, e.planned_time, e.actual_time, e.executor, e.status, e.note, " +
                "t.description AS task_description, t.period_id " +
                "FROM nursing.nursing_task_executions e " +
                "JOIN nursing.nursing_tasks t ON t.id = e.task_id " +
                "WHERE t.period_id IN (%s) AND e.status IN ('PENDING', 'IN_PROGRESS') " +
                "ORDER BY e.planned_time ASC NULLS LAST, e.created_at ASC"

        private const val OPEN_INCIDENTS_SQL =
            "SELECT id, period_id, incident_type, severity, status, occurred_at, description " +
                "FROM nursing.nursing_incidents " +
                "WHERE period_id IN (%s) AND status <> '已关闭' " +
                "ORDER BY occurred_at ASC, created_at ASC"

        private const val INCIDENT_ACTIONS_SQL =
            "SELECT incident_id, action_type, body, actor " +
                "FROM nursing.nursing_incident_actions " +
                "WHERE incident_id IN (%s) ORDER BY created_at ASC, id ASC"

        private const val ITEM_COUNTS_SQL =
            "SELECT handover_id, count(*) AS item_count " +
                "FROM nursing.nursing_shift_handover_items " +
                "WHERE handover_id IN (%s) GROUP BY handover_id"

        fun sha256Digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 生成 `$1, $2, ...` 占位符串（[count] 个）。 */
        private fun placeholders(count: Int): String =
            (1..count).joinToString(", ") { "\$$it" }
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
                val tuple = Tuple.of(
                    id, input.careUnit, input.businessDate, input.shift, input.handoverBy, now,
                    input.idempotencyKey, digest, now, now,
                )
                client.preparedQuery(HANDOVER_INSERT_SQL).execute(tuple).compose { _ ->
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
                client.preparedQuery(RECEIVE_SQL).execute(Tuple.of(id, receiver, now, now))
                    .compose { getHandover(client, id) }
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
                client.preparedQuery(MANUAL_ITEM_INSERT_SQL)
                    .execute(Tuple.of(itemId, id, content, createdBy, now, now))
                    .compose {
                        client.preparedQuery(TOUCH_HANDOVER_SQL).execute(Tuple.of(id, now))
                            .compose { getHandover(client, id) }
                    }
            }
        }
    }

    /** 锁定交接单头。必须在调用方外层事务内执行。 */
    fun lockHandover(client: SqlClient, id: String): Future<JsonObject> {
        return client.preparedQuery(HANDOVER_BY_ID_LOCKED_SQL).execute(Tuple.of(id)).compose { rows ->
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>(careUnit)

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        businessDate?.let { where.add("business_date = ${bind(it)}") }
        shift?.takeIf(String::isNotBlank)?.let { where.add("shift = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_shift_handovers$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $HANDOVER_COLUMNS FROM nursing.nursing_shift_handovers$whereClause " +
                "ORDER BY business_date DESC, created_at DESC " +
                "LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).compose { rows ->
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
        return client.preparedQuery(HANDOVER_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("shift handover not found: $id"))
            client.preparedQuery(HANDOVER_ITEMS_SQL).execute(Tuple.of(id)).map { itemRows ->
                handoverHeadFromRow(row)
                    .put("items", JsonArray(itemRows.map { handoverItemFromRow(it) }))
            }
        }
    }

    // ========================================================================
    //  快照装配（只读批量查询，固定查询次数，不产生任何写副作用）
    //  Healthcare 已在连接上收集活动养老入住与本业务日护理记录，
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
        val sql = OPEN_EXECUTIONS_SQL.format(placeholders(periodIds.size))
        return client.preparedQuery(sql).execute(Tuple.from(periodIds)).map { rows ->
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
        val incidentSql = OPEN_INCIDENTS_SQL.format(placeholders(periodIds.size))
        return client.preparedQuery(incidentSql).execute(Tuple.from(periodIds)).compose { incidentRows ->
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
            val actionSql = INCIDENT_ACTIONS_SQL.format(placeholders(incidentIds.size))
            client.preparedQuery(actionSql).execute(Tuple.from(incidentIds)).map { actionRows ->
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
            // 可空来源关联仅在存在时入列，避免绑定 null（原生参数化 SQL，$1..$n 位置绑定）。
            val columns = mutableListOf(
                "id", "handover_id", "item_kind", "summary", "created_by", "snapshot_at", "created_at",
            )
            val values = mutableListOf<Any?>(
                Ulid.generate(), handoverId, item.getString("item_kind"),
                requireNotNull(item.getString("summary")), createdBy, now, now,
            )
            item.getString("encounter_id")?.let { columns.add("encounter_id"); values.add(it) }
            item.getString("period_id")?.let { columns.add("period_id"); values.add(it) }
            item.getString("source_id")?.let { columns.add("source_id"); values.add(it) }

            val sql =
                "INSERT INTO nursing.nursing_shift_handover_items " +
                    "(${columns.joinToString(", ")}) VALUES (${placeholders(values.size)})"

            return client.preparedQuery(sql).execute(Tuple.from(values)).compose { loop(index + 1) }
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
    ): Future<Row?> =
        client.preparedQuery(HANDOVER_BY_SCOPE_LOCKED_SQL)
            .execute(Tuple.of(careUnit, businessDate, shift))
            .map { rows -> rows.iterator().asSequence().firstOrNull() }

    /** 交接单「事项数」：按 handover_id 归组统计，避免列表接口带出全部明细。 */
    private fun countItems(client: SqlClient, handoverIds: List<String>): Future<Map<String, Long>> {
        val sql = ITEM_COUNTS_SQL.format(placeholders(handoverIds.size))
        return client.preparedQuery(sql).execute(Tuple.from(handoverIds)).map { rows ->
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
}
