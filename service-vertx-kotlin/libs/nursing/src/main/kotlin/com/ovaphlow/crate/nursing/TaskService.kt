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
 * 护理任务服务。
 *
 * 读路径（list/get）带 `LEFT JOIN healthcare.medical_orders` 取出医嘱类型与结构化明细；
 * 别名 `nt` / `mo` 用于消歧（未限定列名在 PG 会因同名列报错）。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 */
class TaskService(
    private val pool: Pool,
) {
    companion object {
        internal val VALID_TASK_TYPES = setOf("NURSING", "REHABILITATION", "LIVING_CARE", "HEALTH_EDUCATION", "OTHER")
        private val ORDER_TASK_TYPES = setOf("MEDICATION", "TREATMENT")
        private val ORDER_TERMINAL_STATUSES = setOf("CANCELLED", "COMPLETED")
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        /** `nursing.nursing_tasks` 显式列清单（与 [toJson] 读取的键一致）。 */
        internal const val TASK_COLUMNS =
            "id, period_id, encounter_id, plan_item_id, order_item_id, task_type, description, " +
                "frequency_code, frequency_name, start_date, end_date, status, metadata, " +
                "created_at, updated_at"

        /** 与 [TASK_COLUMNS] 同序，带 `nt.` 表别名限定（JOIN 读路径用）。 */
        internal const val TASK_COLUMNS_NT =
            "nt.id, nt.period_id, nt.encounter_id, nt.plan_item_id, nt.order_item_id, nt.task_type, " +
                "nt.description, nt.frequency_code, nt.frequency_name, nt.start_date, nt.end_date, " +
                "nt.status, nt.metadata, nt.created_at, nt.updated_at"

        private const val JOINED_TASK_SQL =
            "SELECT $TASK_COLUMNS_NT, mo.order_details AS order_details, mo.order_type AS order_type " +
                "FROM nursing.nursing_tasks AS nt " +
                "LEFT JOIN healthcare.medical_orders AS mo ON nt.order_item_id = mo.id "

        private const val TASK_BY_ID_SQL = JOINED_TASK_SQL + "WHERE nt.id = \$1"

        private const val LOCK_ORDER_TASK_SQL =
            "SELECT $TASK_COLUMNS FROM nursing.nursing_tasks " +
                "WHERE order_item_id = \$1 FOR UPDATE"

        private const val TASK_STATUS_UPDATE_SQL =
            "UPDATE nursing.nursing_tasks SET status = \$1, updated_at = \$2 " +
                "WHERE nursing.nursing_tasks.id = \$3"

        private const val LOCK_ACTIVE_PLAN_TASKS_SQL =
            "SELECT t.id, t.period_id, t.encounter_id, t.plan_item_id, t.order_item_id, t.task_type, " +
                "t.description, t.frequency_code, t.frequency_name, t.start_date, t.end_date, " +
                "t.status, t.metadata, t.created_at, t.updated_at " +
                "FROM nursing.nursing_tasks t " +
                "JOIN nursing.nursing_plan_items i ON i.id = t.plan_item_id " +
                "WHERE i.plan_id = \$1 AND t.status = 'ACTIVE' FOR UPDATE"

        fun toJson(row: Row): JsonObject = toJson(row, withBoundOrder = false)

        /**
         * 任务行 → 响应 JSON。
         *
         * `withBoundOrder = true` 只用于带 `LEFT JOIN healthcare.medical_orders` 的读路径
         * （`list`/`get`）。其余路径（`lockOrderTask` / `lockActivePlanTasks` /
         * `CarePlanRevisionService.readPlanTasks` / create 系列）的行里没有这两列，按显式
         * 开关直接返回 null——**不去探测行里有没有该列**：`Row.getColumnIndex` 是驱动实现
         * 细节，靠它探测会让所有 mock 行与其它驱动的行都变脆。
         */
        fun toJson(row: Row, withBoundOrder: Boolean): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("plan_item_id", row.getValue("plan_item_id")?.toString())
                .put("order_item_id", row.getValue("order_item_id")?.toString())
                .put("task_type", row.getValue("task_type")?.toString())
                .put("description", row.getValue("description")?.toString())
                .put("frequency_code", row.getValue("frequency_code")?.toString())
                .put("frequency_name", row.getValue("frequency_name")?.toString())
                .put("start_date", row.getValue("start_date")?.toString())
                .put("end_date", row.getValue("end_date")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
                // 绑定医嘱的结构化明细与类型：只有 JOIN 读路径才取值，其它路径恒 null，
                // 两种情况都保留这两个键以维持同一返回形状。
                .put(
                    "order_details",
                    if (withBoundOrder) row.getValue("order_details") as? JsonObject else null,
                )
                .put(
                    "order_type",
                    if (withBoundOrder) row.getValue("order_type")?.toString() else null,
                )
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val taskType = body.getString("task_type")
        val description = body.getString("description")

        if (taskType.isNullOrBlank() || taskType !in VALID_TASK_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid task_type, must be one of: $VALID_TASK_TYPES"))
        if (description.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("description is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val sql =
            "INSERT INTO nursing.nursing_tasks ($TASK_COLUMNS) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14, \$15)"
        val tuple = Tuple.of(
            id,
            body.getString("period_id"),
            body.getString("encounter_id"),
            body.getString("plan_item_id"),
            body.getString("order_item_id"),
            taskType,
            description,
            body.getString("frequency_code"),
            body.getString("frequency_name"),
            body.getString("start_date")?.let { LocalDate.parse(it) },
            body.getString("end_date")?.let { LocalDate.parse(it) },
            "ACTIVE",
            if (body.containsKey("metadata")) body.getJsonObject("metadata") else null,
            now,
            now,
        )

        return pool.preparedQuery(sql).execute(tuple).map {
            JsonObject()
                .put("id", id)
                .put("period_id", body.getString("period_id"))
                .put("encounter_id", body.getString("encounter_id"))
                .put("plan_item_id", body.getString("plan_item_id"))
                .put("order_item_id", body.getString("order_item_id"))
                .put("task_type", taskType)
                .put("description", description)
                .put("frequency_code", body.getString("frequency_code"))
                .put("frequency_name", body.getString("frequency_name"))
                .put("start_date", body.getString("start_date"))
                .put("end_date", body.getString("end_date"))
                .put("status", "ACTIVE")
                .put("metadata", body.getJsonObject("metadata"))
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
                .put("order_details", null)
                .put("order_type", null)
        }
    }

    fun list(
        periodId: String? = null,
        encounterId: String? = null,
        taskType: String? = null,
        status: String? = null,
        planItemId: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        // 过滤条件按「列名 → 值」收集：计数查询沿用未限定的 nursing_tasks 列（SQL 与
        // 行数语义不变），明细查询带 LEFT JOIN，必须用别名 nt 限定列名。
        val filters = linkedMapOf<String, String>()
        periodId?.let { filters["period_id"] = it }
        encounterId?.let { filters["encounter_id"] = it }
        taskType?.let { filters["task_type"] = it }
        status?.let { filters["status"] = it }
        planItemId?.let { filters["plan_item_id"] = it }

        val countWhere = mutableListOf<String>()
        val countArgs = mutableListOf<Any?>()
        val dataWhere = mutableListOf<String>()
        val dataArgs = mutableListOf<Any?>()

        for ((column, value) in filters) {
            countWhere.add("$column = \$${countArgs.size + 1}")
            countArgs.add(value)
            dataWhere.add("nt.$column = \$${dataArgs.size + 1}")
            dataArgs.add(value)
        }

        val countWhereClause = if (countWhere.isEmpty()) "" else " WHERE " + countWhere.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_tasks$countWhereClause"
        val dataWhereClause = if (dataWhere.isEmpty()) "" else " WHERE " + dataWhere.joinToString(" AND ")
        val dataSql =
            JOINED_TASK_SQL + dataWhereClause +
                " ORDER BY nt.created_at DESC LIMIT \$${dataArgs.size + 1} OFFSET \$${dataArgs.size + 2}"

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            val dataTuple = Tuple.from(dataArgs.toList() + listOf(limit, offset))
            pool.preparedQuery(dataSql).execute(dataTuple).map { dataRows ->
                val records = JsonArray()
                // 该查询带 LEFT JOIN healthcare.medical_orders，行里确有这两列
                for (row in dataRows) records.add(toJson(row, withBoundOrder = true))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        return pool.preparedQuery(TASK_BY_ID_SQL).execute(Tuple.of(id)).flatMap { rows ->
            if (rows.size() == 0)
                Future.failedFuture(NotFoundException("task not found: $id"))
            // 该查询带 LEFT JOIN healthcare.medical_orders，行里确有这两列
            else Future.succeededFuture(toJson(rows.iterator().next(), withBoundOrder = true))
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

            pool.preparedQuery(TASK_STATUS_UPDATE_SQL).execute(Tuple.of(newStatus, now, id))
                .flatMap { get(id) }
        }
    }

    // ========================================================================
    //  医嘱派生护理任务 — 连接绑定内部协作（仅供 Healthcare 在同一事务内调用）
    // ========================================================================

    data class OrderTaskInput(
        val periodId: String,
        val encounterId: String,
        val orderItemId: String,
        val taskType: String,
        val description: String,
        val frequencyCode: String?,
        val frequencyName: String?,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
    )

    fun createOrderTask(client: SqlClient, input: OrderTaskInput): Future<JsonObject> {
        if (input.taskType !in ORDER_TASK_TYPES) {
            return Future.failedFuture(
                IllegalArgumentException("invalid order task_type, must be MEDICATION or TREATMENT")
            )
        }
        if (input.orderItemId.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("order_item_id is required"))
        }
        if (input.description.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("description is required"))
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val columns = mutableListOf(
            "id", "period_id", "encounter_id", "order_item_id", "task_type", "description",
            "status", "created_at", "updated_at",
        )
        val values = mutableListOf<Any?>(
            id, input.periodId, input.encounterId, input.orderItemId, input.taskType,
            input.description, "ACTIVE", now, now,
        )
        input.frequencyCode?.let { columns.add("frequency_code"); values.add(it) }
        input.frequencyName?.let { columns.add("frequency_name"); values.add(it) }
        input.startDate?.let { columns.add("start_date"); values.add(it) }
        input.endDate?.let { columns.add("end_date"); values.add(it) }

        val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "INSERT INTO nursing.nursing_tasks (${columns.joinToString(", ")}) VALUES ($placeholders)"

        return client.preparedQuery(sql).execute(Tuple.from(values)).map {
            JsonObject()
                .put("id", id)
                .put("period_id", input.periodId)
                .put("encounter_id", input.encounterId)
                .put("order_item_id", input.orderItemId)
                .put("task_type", input.taskType)
                .put("description", input.description)
                .put("frequency_code", input.frequencyCode)
                .put("frequency_name", input.frequencyName)
                .put("start_date", input.startDate?.toString())
                .put("end_date", input.endDate?.toString())
                .put("status", "ACTIVE")
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
                .put("order_details", null)
                .put("order_type", null)
        }
    }

    fun lockOrderTask(client: SqlClient, orderItemId: String): Future<JsonObject?> {
        return client.preparedQuery(LOCK_ORDER_TASK_SQL).execute(Tuple.of(orderItemId)).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { toJson(it) }
        }
    }

    fun terminateOrderTask(client: SqlClient, orderItemId: String, targetStatus: String): Future<Void> {
        if (targetStatus !in ORDER_TERMINAL_STATUSES) {
            return Future.failedFuture(
                IllegalArgumentException("invalid target status, must be CANCELLED or COMPLETED")
            )
        }
        return lockOrderTask(client, orderItemId).compose { task ->
            if (task == null) {
                return@compose Future.failedFuture(ConflictException("order has no linked task: $orderItemId"))
            }
            val currentStatus = task.getString("status")
            if (currentStatus != "ACTIVE") {
                return@compose Future.failedFuture(
                    ConflictException("order task is in unexpected status: $currentStatus")
                )
            }
            val now = OffsetDateTime.now()
            client.preparedQuery(TASK_STATUS_UPDATE_SQL)
                .execute(Tuple.of(targetStatus, now, requireNotNull(task.getString("id"))))
                .map<Void> { null }
        }
    }

    // ========================================================================
    //  复评计划修订 — 计划任务协作（仅供 Healthcare 在同一事务内调用）
    // ========================================================================

    data class PlanTaskInput(
        val periodId: String,
        val encounterId: String,
        val planItemId: String,
        val description: String,
        val frequencyCode: String?,
        val frequencyName: String?,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
    )

    /**
     * 为一条计划措施派生 `NURSING` 任务，精确写入 plan_item_id / period_id / encounter_id。
     * 必须在调用方外层事务内执行。
     */
    fun createPlanTask(client: SqlClient, input: PlanTaskInput): Future<JsonObject> {
        if (input.planItemId.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("plan_item_id is required"))
        }
        if (input.description.isBlank()) {
            return Future.failedFuture(IllegalArgumentException("description is required"))
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val columns = mutableListOf(
            "id", "period_id", "encounter_id", "plan_item_id", "task_type", "description",
            "status", "created_at", "updated_at",
        )
        val values = mutableListOf<Any?>(
            id, input.periodId, input.encounterId, input.planItemId, "NURSING",
            input.description, "ACTIVE", now, now,
        )
        input.frequencyCode?.let { columns.add("frequency_code"); values.add(it) }
        input.frequencyName?.let { columns.add("frequency_name"); values.add(it) }
        input.startDate?.let { columns.add("start_date"); values.add(it) }
        input.endDate?.let { columns.add("end_date"); values.add(it) }

        val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "INSERT INTO nursing.nursing_tasks (${columns.joinToString(", ")}) VALUES ($placeholders)"

        return client.preparedQuery(sql).execute(Tuple.from(values)).map {
            JsonObject()
                .put("id", id)
                .put("period_id", input.periodId)
                .put("encounter_id", input.encounterId)
                .put("plan_item_id", input.planItemId)
                .put("order_item_id", null)
                .put("task_type", "NURSING")
                .put("description", input.description)
                .put("frequency_code", input.frequencyCode)
                .put("frequency_name", input.frequencyName)
                .put("start_date", input.startDate?.toString())
                .put("end_date", input.endDate?.toString())
                .put("status", "ACTIVE")
                .put("metadata", null)
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
                .put("order_details", null)
                .put("order_type", null)
        }
    }

    /**
     * 锁定某计划（经 plan_item_id）的全部活动任务。必须在调用方外层事务内执行。
     */
    fun lockActivePlanTasks(client: SqlClient, planId: String): Future<List<JsonObject>> {
        return client.preparedQuery(LOCK_ACTIVE_PLAN_TASKS_SQL).execute(Tuple.of(planId)).map { rows ->
            val tasks = mutableListOf<JsonObject>()
            for (row in rows) tasks.add(toJson(row))
            tasks
        }
    }

    /**
     * 批量将活动任务收束为 `CANCELLED`（只作用于仍为 ACTIVE 的任务）。
     * 必须在调用方外层事务内执行。
     */
    fun cancelPlanTasks(client: SqlClient, taskIds: List<String>): Future<Void> {
        if (taskIds.isEmpty()) return Future.succeededFuture()
        val now = OffsetDateTime.now()
        // 绑定顺序与 jOOQ 版逐位对齐：SET 列（status → updated_at）→ IN 列表 → 活动态守卫
        val placeholders = taskIds.indices.joinToString(", ") { "\$${it + 3}" }
        val sql =
            "UPDATE nursing.nursing_tasks SET status = \$1, updated_at = \$2 " +
                "WHERE id IN ($placeholders) AND status = \$${taskIds.size + 3}"
        return client.preparedQuery(sql)
            .execute(Tuple.from(listOf<Any?>("CANCELLED", now) + taskIds + listOf("ACTIVE")))
            .map<Void> { null }
    }
}
