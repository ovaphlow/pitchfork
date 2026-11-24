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

/**
 * 护理照护计划服务。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 */
class PlanService(
    private val pool: Pool,
) {
    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("COMPLETED", "DISCONTINUED"),
            "COMPLETED" to emptyList(),
            "DISCONTINUED" to emptyList()
        )

        /** `nursing.nursing_plans` 显式列清单（与 [planToJson] 读取的键一致）。 */
        internal const val PLAN_COLUMNS =
            "id, period_id, encounter_id, plan_name, goals, status, created_by, " +
                "start_date, end_date, metadata, created_at, updated_at"

        /** `nursing.nursing_plan_items` 显式列清单（与 [itemToJson] 读取的键一致）。 */
        internal const val ITEM_COLUMNS =
            "id, plan_id, action, frequency_code, frequency_name, duration_days, " +
                "remark, status, metadata, created_at"

        private const val ACTIVE_PLAN_SQL =
            "SELECT id FROM nursing.nursing_plans WHERE period_id = \$1 AND status = 'ACTIVE'"

        private const val ASSESS_COUNT_BY_PERIOD_SQL =
            "SELECT count(*) AS total FROM nursing.nursing_assessments WHERE period_id = \$1"

        private const val ASSESS_COUNT_BY_PERIOD_OR_ENCOUNTER_SQL =
            "SELECT count(*) AS total FROM nursing.nursing_assessments " +
                "WHERE period_id = \$1 OR encounter_id = \$2"

        private const val PLAN_BY_ID_SQL =
            "SELECT $PLAN_COLUMNS FROM nursing.nursing_plans WHERE id = \$1"

        private const val PLAN_ITEMS_SQL =
            "SELECT $ITEM_COLUMNS FROM nursing.nursing_plan_items WHERE plan_id = \$1"

        private const val LOCK_ACTIVE_PLAN_SQL =
            "SELECT $PLAN_COLUMNS FROM nursing.nursing_plans " +
                "WHERE period_id = \$1 AND status = 'ACTIVE' FOR UPDATE"

        private const val LOCK_ACTIVE_ITEMS_SQL =
            "SELECT $ITEM_COLUMNS FROM nursing.nursing_plan_items " +
                "WHERE plan_id = \$1 AND status = 'ACTIVE' FOR UPDATE"

        private const val TERMINATE_PLAN_SQL =
            "UPDATE nursing.nursing_plans SET status = \$1, updated_at = \$2 WHERE id = \$3"

        private const val TERMINATE_ITEMS_SQL =
            "UPDATE nursing.nursing_plan_items SET status = \$1 " +
                "WHERE plan_id = \$2 AND status = \$3"

        fun planToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("plan_name", row.getValue("plan_name")?.toString())
                .put("goals", row.getValue("goals")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("created_by", row.getValue("created_by")?.toString())
                .put("start_date", row.getValue("start_date")?.toString())
                .put("end_date", row.getValue("end_date")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun itemToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("plan_id", row.getValue("plan_id")?.toString())
                .put("action", row.getValue("action")?.toString())
                .put("frequency_code", row.getValue("frequency_code")?.toString())
                .put("frequency_name", row.getValue("frequency_name")?.toString())
                .put("duration_days", row.getValue("duration_days") as? Int)
                .put("remark", row.getValue("remark")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }

    fun create(body: JsonObject, createdBy: String?): Future<JsonObject> {
        val periodId = body.getString("period_id")
        val planName = body.getString("plan_name")
        if (periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("period_id is required"))
        if (planName.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("plan_name is required"))

        val encounterId = body.getString("encounter_id")?.takeIf { it.isNotBlank() }

        // 通用入口只允许初次计划：该 period 已有活动计划时必须走 Healthcare 复评修订接口
        return pool.preparedQuery(ACTIVE_PLAN_SQL).execute(Tuple.of(periodId)).compose { activeRows ->
            if (activeRows.size() > 0) {
                return@compose Future.failedFuture(
                    ConflictException("period already has an active plan; create a new plan version via care plan revision")
                )
            }
            requireAssessment(periodId, encounterId)
                .compose { doCreate(body, periodId, planName, createdBy) }
        }
    }

    /**
     * 照护计划前置校验（D10）：该服务周期/入住必须至少有一条护理评估，否则以
     * [ConflictException] 拒绝（路由映射 409，消息可直接展示给用户）。
     *
     * `AssessmentService.create` 允许评估只带 `period_id` 或只带 `encounter_id`
     * （V400 `chk_assess_ref`），只按 `period_id` 计数会漏掉只带 `encounter_id` 的评估，
     * 因此请求体提供了 `encounter_id` 时按 `period_id = ? OR encounter_id = ?` 计数。
     */
    private fun requireAssessment(periodId: String, encounterId: String?): Future<Void> {
        val sql = if (encounterId != null) ASSESS_COUNT_BY_PERIOD_OR_ENCOUNTER_SQL else ASSESS_COUNT_BY_PERIOD_SQL
        val tuple = if (encounterId != null) Tuple.of(periodId, encounterId) else Tuple.of(periodId)
        return pool.preparedQuery(sql).execute(tuple).compose { rows ->
            val total = rows.iterator().next().getLong("total") ?: 0L
            if (total <= 0L) {
                Future.failedFuture(
                    ConflictException("照护计划必须先有护理评估：该服务周期/入住暂无任何评估记录")
                )
            } else {
                Future.succeededFuture<Void>(null)
            }
        }
    }

    private fun doCreate(body: JsonObject, periodId: String, planName: String, createdBy: String?): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val columns = mutableListOf(
            "id", "period_id", "encounter_id", "plan_name", "goals", "status",
            "created_by", "start_date", "end_date", "created_at", "updated_at",
        )
        val values = mutableListOf<Any?>(
            id, periodId, body.getString("encounter_id"), planName, body.getString("goals"),
            "ACTIVE", createdBy,
            body.getString("start_date")?.let { LocalDate.parse(it) },
            body.getString("end_date")?.let { LocalDate.parse(it) },
            now, now,
        )
        if (body.containsKey("metadata")) {
            columns.add("metadata")
            values.add(body.getJsonObject("metadata"))
        }
        val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "INSERT INTO nursing.nursing_plans (${columns.joinToString(", ")}) VALUES ($placeholders)"

        return pool.preparedQuery(sql).execute(Tuple.from(values)).flatMap { _ ->
            val plan = JsonObject()
                .put("id", id)
                .put("period_id", periodId)
                .put("encounter_id", body.getString("encounter_id"))
                .put("plan_name", planName)
                .put("goals", body.getString("goals"))
                .put("status", "ACTIVE")
                .put("created_by", createdBy)
                .put("start_date", body.getString("start_date"))
                .put("end_date", body.getString("end_date"))
                .put("metadata", body.getJsonObject("metadata"))
                .put("created_at", now.toString())
                .put("updated_at", now.toString())

            val itemsArray = body.getJsonArray("items")
            if (itemsArray == null || itemsArray.isEmpty)
                return@flatMap Future.succeededFuture(plan)

            val items = JsonArray()
            fun insertItem(index: Int): Future<JsonObject> {
                if (index >= itemsArray.size()) {
                    plan.put("items", items)
                    return Future.succeededFuture(plan)
                }
                val itemObj = itemsArray.getJsonObject(index)
                val itemId = Ulid.generate()
                val itemColumns = mutableListOf(
                    "id", "plan_id", "action", "frequency_code", "frequency_name",
                    "duration_days", "remark", "status", "created_at",
                )
                val itemValues = mutableListOf<Any?>(
                    itemId, id, itemObj.getString("action"),
                    itemObj.getString("frequency_code"), itemObj.getString("frequency_name"),
                    itemObj.getInteger("duration_days"), itemObj.getString("remark"),
                    "ACTIVE", now,
                )
                if (itemObj.containsKey("metadata")) {
                    itemColumns.add("metadata")
                    itemValues.add(itemObj.getJsonObject("metadata"))
                }
                val itemPlaceholders = itemValues.indices.joinToString(", ") { "\$${it + 1}" }
                val itemSql =
                    "INSERT INTO nursing.nursing_plan_items (${itemColumns.joinToString(", ")}) " +
                        "VALUES ($itemPlaceholders)"

                return pool.preparedQuery(itemSql).execute(Tuple.from(itemValues)).flatMap { _ ->
                    items.add(JsonObject()
                        .put("id", itemId)
                        .put("plan_id", id)
                        .put("action", itemObj.getString("action"))
                        .put("frequency_code", itemObj.getString("frequency_code"))
                        .put("frequency_name", itemObj.getString("frequency_name"))
                        .put("duration_days", itemObj.getInteger("duration_days"))
                        .put("remark", itemObj.getString("remark"))
                        .put("status", "ACTIVE")
                        .put("metadata", itemObj.getJsonObject("metadata"))
                        .put("created_at", now.toString()))
                    insertItem(index + 1)
                }
            }
            insertItem(0)
        }
    }

    fun list(
        periodId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        periodId?.let { where.add("period_id = ${bind(it)}") }
        status?.let { where.add("status = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_plans$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $PLAN_COLUMNS FROM nursing.nursing_plans$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(planToJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        return pool.preparedQuery(PLAN_BY_ID_SQL).execute(Tuple.of(id)).flatMap { rows ->
            if (rows.size() == 0)
                Future.failedFuture(NotFoundException("plan not found: $id"))
            else {
                val plan = planToJson(rows.iterator().next())
                pool.preparedQuery(PLAN_ITEMS_SQL).execute(Tuple.of(id)).map { itemRows ->
                    val items = JsonArray()
                    for (row in itemRows) items.add(itemToJson(row))
                    plan.put("items", items)
                    plan
                }
            }
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
                "UPDATE nursing.nursing_plans SET status = \$1, updated_at = \$2 WHERE id = \$3"

            pool.preparedQuery(updateSql).execute(Tuple.of(newStatus, now, id))
                .flatMap { get(id) }
        }
    }

    // ========================================================================
    //  复评计划修订 — 连接绑定内部协作（仅供 Healthcare 在同一事务内调用）
    // ========================================================================

    data class PlanItemInput(
        val action: String,
        val frequencyCode: String?,
        val frequencyName: String?,
        val durationDays: Int?,
        val remark: String?,
    )

    data class PlanCreateInput(
        val periodId: String,
        val encounterId: String,
        val planName: String,
        val goals: String?,
        val createdBy: String?,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val items: List<PlanItemInput>,
    )

    /**
     * 锁定该 period 的活动计划；返回恰好一个活动计划，否则以 [ConflictException] 拒绝。
     * 必须在调用方外层事务内执行。
     */
    fun lockActivePlan(client: SqlClient, periodId: String): Future<JsonObject> {
        return client.preparedQuery(LOCK_ACTIVE_PLAN_SQL).execute(Tuple.of(periodId)).compose { rows ->
            val active = rows.map { planToJson(it) }
            when {
                active.isEmpty() -> Future.failedFuture(
                    ConflictException("period has no active plan; create the initial plan first")
                )
                active.size > 1 -> Future.failedFuture(
                    ConflictException("period has multiple active plans; data needs manual consolidation")
                )
                else -> Future.succeededFuture(active[0])
            }
        }
    }

    /**
     * 在同一连接上创建新计划及其措施（供复评修订使用，不做活动计划冲突检查）。
     */
    fun createPlanWithItems(client: SqlClient, input: PlanCreateInput): Future<JsonObject> {
        val planId = Ulid.generate()
        val now = OffsetDateTime.now()
        val columns = mutableListOf(
            "id", "period_id", "encounter_id", "plan_name", "status", "created_at", "updated_at",
        )
        val values = mutableListOf<Any?>(
            planId, input.periodId, input.encounterId, input.planName, "ACTIVE", now, now,
        )
        input.goals?.let { columns.add("goals"); values.add(it) }
        input.createdBy?.let { columns.add("created_by"); values.add(it) }
        input.startDate?.let { columns.add("start_date"); values.add(it) }
        input.endDate?.let { columns.add("end_date"); values.add(it) }

        val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "INSERT INTO nursing.nursing_plans (${columns.joinToString(", ")}) VALUES ($placeholders)"

        return client.preparedQuery(sql).execute(Tuple.from(values)).compose {
            insertPlanItems(client, planId, input.items, now)
        }.map { created ->
            val plan = JsonObject()
                .put("id", planId)
                .put("period_id", input.periodId)
                .put("encounter_id", input.encounterId)
                .put("plan_name", input.planName)
                .put("goals", input.goals)
                .put("status", "ACTIVE")
                .put("created_by", input.createdBy)
                .put("start_date", input.startDate?.toString())
                .put("end_date", input.endDate?.toString())
                .put("metadata", null)
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
            plan.put("items", created)
            plan
        }
    }

    /**
     * 收束旧计划：计划与活动措施均转为 `DISCONTINUED`。必须在调用方外层事务内执行。
     */
    fun terminatePlan(client: SqlClient, planId: String): Future<Void> {
        val now = OffsetDateTime.now()
        return client.preparedQuery(TERMINATE_PLAN_SQL)
            .execute(Tuple.of("DISCONTINUED", now, planId)).compose {
                client.preparedQuery(TERMINATE_ITEMS_SQL)
                    .execute(Tuple.of("DISCONTINUED", planId, "ACTIVE"))
                    .map<Void> { null }
            }
    }

    /** 锁定旧计划的全部活动措施，返回措施列表。必须在调用方外层事务内执行。 */
    fun lockActivePlanItems(client: SqlClient, planId: String): Future<List<JsonObject>> {
        return client.preparedQuery(LOCK_ACTIVE_ITEMS_SQL).execute(Tuple.of(planId)).map { rows ->
            val items = mutableListOf<JsonObject>()
            for (row in rows) items.add(itemToJson(row))
            items
        }
    }

    private fun insertPlanItems(
        client: SqlClient,
        planId: String,
        items: List<PlanItemInput>,
        now: OffsetDateTime,
    ): Future<List<JsonObject>> {
        fun loop(index: Int, acc: List<JsonObject>): Future<List<JsonObject>> {
            if (index >= items.size) return Future.succeededFuture(acc)
            val item = items[index]
            val itemId = Ulid.generate()
            val columns = mutableListOf("id", "plan_id", "action", "status", "created_at")
            val values = mutableListOf<Any?>(itemId, planId, item.action, "ACTIVE", now)
            item.frequencyCode?.let { columns.add("frequency_code"); values.add(it) }
            item.frequencyName?.let { columns.add("frequency_name"); values.add(it) }
            item.durationDays?.let { columns.add("duration_days"); values.add(it) }
            item.remark?.let { columns.add("remark"); values.add(it) }

            val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
            val sql =
                "INSERT INTO nursing.nursing_plan_items (${columns.joinToString(", ")}) " +
                    "VALUES ($placeholders)"

            return client.preparedQuery(sql).execute(Tuple.from(values)).compose {
                val updated = ArrayList<JsonObject>(acc.size + 1)
                updated.addAll(acc)
                updated.add(itemJson(itemId, planId, item, now))
                loop(index + 1, updated)
            }
        }
        return loop(0, emptyList())
    }

    private fun itemJson(itemId: String, planId: String, item: PlanItemInput, now: OffsetDateTime): JsonObject =
        JsonObject()
            .put("id", itemId)
            .put("plan_id", planId)
            .put("action", item.action)
            .put("frequency_code", item.frequencyCode)
            .put("frequency_name", item.frequencyName)
            .put("duration_days", item.durationDays)
            .put("remark", item.remark)
            .put("status", "ACTIVE")
            .put("metadata", null)
            .put("created_at", now.toString())
}
