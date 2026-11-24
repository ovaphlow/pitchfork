package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 复评与照护计划修订 — 连接绑定协作与修订关系读写。
 *
 * 写路径的所有步骤必须在 Healthcare 外层事务传入的同一个 [SqlClient] 上执行；
 * 本服务不启动自己的事务，也不按 patient_id 猜测周期。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），本文件无任何 jOOQ 引用。
 */
class CarePlanRevisionService(
    private val pool: Pool,
) {
    companion object {
        /** 复评允许的评估类型（不含 ADMISSION：入住评估不是复评） */
        val VALID_ASSESS_TYPES = setOf(
            "FALL_RISK", "PRESSURE_SORE", "PAIN",
            "BARTHEL", "NUTRITION", "HOME_ENVIRONMENT", "OTHER"
        )

        fun isValidAssessType(value: String): Boolean = value in VALID_ASSESS_TYPES

        private const val PERIOD_COLUMNS = "id, patient_id, service_type, start_date, status"

        private const val REVISION_COLUMNS =
            "id, period_id, encounter_id, assessment_id, previous_plan_id, new_plan_id, " +
                "revision_no, created_at"

        /** 与 `AssessmentService.COLUMNS` 同序（评估行回读按此清单取列）。 */
        private const val ASSESSMENT_COLUMNS =
            "id, encounter_id, period_id, assess_type, assess_date, assessor, " +
                "total_score, result_level, detail, remark, metadata, created_at"

        private const val LOCK_PERIOD_SQL =
            "SELECT $PERIOD_COLUMNS FROM nursing.nursing_service_periods " +
                "WHERE encounter_id = \$1 FOR UPDATE"

        private const val IN_PROGRESS_SQL =
            "SELECT 1 AS found FROM nursing.nursing_task_executions " +
                "WHERE task_id IN (%s) AND status = 'IN_PROGRESS'"

        private const val NEXT_REVISION_NO_SQL =
            "SELECT COALESCE(max(revision_no), 0) AS max_no " +
                "FROM nursing.nursing_care_plan_revisions WHERE period_id = \$1"

        private const val REVISION_INSERT_SQL =
            "INSERT INTO nursing.nursing_care_plan_revisions ($REVISION_COLUMNS) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val REVISION_BY_ID_SQL =
            "SELECT $REVISION_COLUMNS FROM nursing.nursing_care_plan_revisions WHERE id = \$1"

        private const val REVISION_LIST_SQL =
            "SELECT r.id, r.period_id, r.encounter_id, r.assessment_id, r.previous_plan_id, " +
                "r.new_plan_id, r.revision_no, r.created_at, " +
                "a.assess_type, a.assess_date, a.assessor, a.result_level, " +
                "p.plan_name AS new_plan_name, p.status AS new_plan_status, " +
                "prev.plan_name AS prev_plan_name, prev.status AS prev_plan_status " +
                "FROM nursing.nursing_care_plan_revisions r " +
                "JOIN nursing.nursing_assessments a ON a.id = r.assessment_id " +
                "JOIN nursing.nursing_plans p ON p.id = r.new_plan_id " +
                "LEFT JOIN nursing.nursing_plans prev ON prev.id = r.previous_plan_id " +
                "WHERE r.encounter_id = \$1 ORDER BY r.revision_no DESC"

        private const val REVISION_COUNT_SQL =
            "SELECT count(*) AS total FROM nursing.nursing_care_plan_revisions WHERE encounter_id = \$1"

        private const val PERIOD_ENCOUNTER_SQL =
            "SELECT encounter_id FROM nursing.nursing_service_periods WHERE id = \$1"

        private const val ASSESSMENT_BY_ID_SQL =
            "SELECT $ASSESSMENT_COLUMNS FROM nursing.nursing_assessments WHERE id = \$1"

        private const val PLAN_BY_ID_SQL =
            "SELECT ${PlanService.PLAN_COLUMNS} FROM nursing.nursing_plans WHERE id = \$1"

        private const val PLAN_ITEMS_SQL =
            "SELECT ${PlanService.ITEM_COLUMNS} FROM nursing.nursing_plan_items " +
                "WHERE plan_id = \$1 ORDER BY created_at ASC"

        private const val PLAN_TASK_FIELDS =
            "t.id, t.period_id, t.encounter_id, t.plan_item_id, t.order_item_id, t.task_type, " +
                "t.description, t.frequency_code, t.frequency_name, t.start_date, t.end_date, " +
                "t.status, t.metadata, t.created_at, t.updated_at"

        private const val PLAN_TASKS_SQL =
            "SELECT $PLAN_TASK_FIELDS FROM nursing.nursing_tasks t " +
                "JOIN nursing.nursing_plan_items i ON i.id = t.plan_item_id " +
                "WHERE i.plan_id = \$1 ORDER BY t.created_at ASC"
    }

    data class AssessmentCreateInput(
        val encounterId: String,
        val periodId: String,
        val assessType: String,
        val assessDate: LocalDate,
        val assessor: String?,
        val totalScore: Double?,
        val resultLevel: String?,
        val detail: JsonObject?,
        val remark: String?,
    )

    /**
     * 锁定该 encounter 精确关联的养老照护周期，并校验活动状态与患者一致性。
     * 必须在调用方外层事务内执行。
     */
    fun lockPeriodForEncounter(
        client: SqlClient,
        encounterId: String,
        expectedPatientId: String,
    ): Future<JsonObject> {
        return client.preparedQuery(LOCK_PERIOD_SQL).execute(Tuple.of(encounterId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                return@compose Future.failedFuture(
                    ConflictException("elderly admission has no bound nursing care period: $encounterId")
                )
            }
            if (row.getString("service_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(
                    ConflictException("nursing care period is not an elderly care period")
                )
            }
            if (row.getString("status") != "ACTIVE") {
                return@compose Future.failedFuture(
                    ConflictException("nursing care period is not active: ${row.getString("status")}")
                )
            }
            if (row.getString("patient_id") != expectedPatientId) {
                return@compose Future.failedFuture(
                    ConflictException("patient_id mismatch between period and encounter")
                )
            }
            Future.succeededFuture(
                JsonObject()
                    .put("id", row.getString("id"))
                    .put("patient_id", row.getString("patient_id"))
                    .put("start_date", row.getLocalDate("start_date")?.toString())
                    .put("status", row.getString("status"))
            )
        }
    }

    /**
     * 检查任务集合是否存在 `IN_PROGRESS` 执行；存在则整体拒绝。
     * 必须在调用方外层事务内执行。
     */
    fun checkNoInProgressExecution(client: SqlClient, taskIds: List<String>): Future<Void> {
        if (taskIds.isEmpty()) return Future.succeededFuture()
        val placeholders = taskIds.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = IN_PROGRESS_SQL.format(placeholders)
        return client.preparedQuery(sql).execute(Tuple.from(taskIds)).compose { rows ->
            if (rows.size() > 0) {
                Future.failedFuture(
                    ConflictException("cannot revise care plan while a task execution is in progress")
                )
            } else {
                Future.succeededFuture()
            }
        }
    }

    /**
     * 计算该周期下一个修订号（首次为 1）。调用方必须已锁定精确 period，
     * 数据库唯一约束 (period_id, revision_no) 作为并发兜底。
     */
    fun nextRevisionNo(client: SqlClient, periodId: String): Future<Int> {
        return client.preparedQuery(NEXT_REVISION_NO_SQL).execute(Tuple.of(periodId)).map { rows ->
            val maxNo = rows.iterator().next().getInteger("max_no") ?: 0
            maxNo + 1
        }
    }

    /**
     * 写入复评。必须在调用方外层事务内执行；assess_type 已由调用方白名单校验。
     */
    fun createAssessment(client: SqlClient, input: AssessmentCreateInput): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        // 固定列 + 仅在提供时追加的可空列（原生参数化 SQL，占位符按入列顺序编号）。
        val columns = mutableListOf(
            "id", "encounter_id", "period_id", "assess_type", "assess_date", "created_at",
        )
        val values = mutableListOf<Any?>(
            id, input.encounterId, input.periodId, input.assessType, input.assessDate, now,
        )
        input.assessor?.let { columns.add("assessor"); values.add(it) }
        input.totalScore?.let { columns.add("total_score"); values.add(BigDecimal.valueOf(it)) }
        input.resultLevel?.let { columns.add("result_level"); values.add(it) }
        input.detail?.let { columns.add("detail"); values.add(it) }
        input.remark?.let { columns.add("remark"); values.add(it) }

        val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "INSERT INTO nursing.nursing_assessments (${columns.joinToString(", ")}) " +
                "VALUES ($placeholders)"

        return client.preparedQuery(sql).execute(Tuple.from(values)).map {
            JsonObject()
                .put("id", id)
                .put("encounter_id", input.encounterId)
                .put("period_id", input.periodId)
                .put("assess_type", input.assessType)
                .put("assess_date", input.assessDate.toString())
                .put("assessor", input.assessor)
                .put("total_score", input.totalScore)
                .put("result_level", input.resultLevel)
                .put("detail", input.detail)
                .put("remark", input.remark)
                .put("metadata", null)
                .put("created_at", now.toString())
        }
    }

    /**
     * 写入修订关系。必须在调用方外层事务内执行；所有 ID 由服务端从锁定数据推导。
     */
    fun insertRevision(
        client: SqlClient,
        periodId: String,
        encounterId: String,
        assessmentId: String,
        previousPlanId: String,
        newPlanId: String,
        revisionNo: Int,
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.of(
            id, periodId, encounterId, assessmentId, previousPlanId, newPlanId, revisionNo, now,
        )
        return client.preparedQuery(REVISION_INSERT_SQL).execute(tuple).map {
            JsonObject()
                .put("id", id)
                .put("period_id", periodId)
                .put("encounter_id", encounterId)
                .put("assessment_id", assessmentId)
                .put("previous_plan_id", previousPlanId)
                .put("new_plan_id", newPlanId)
                .put("revision_no", revisionNo)
                .put("created_at", now.toString())
        }
    }

    // ========================================================================
    //  只读：修订历史列表与详情（不生成任务、执行或库存写入）
    // ========================================================================

    /**
     * 按修订号倒序返回该 encounter 的修订历史。
     * 空列表返回空 `records` 和 `total: 0`。
     */
    fun listRevisions(encounterId: String): Future<JsonObject> {
        return pool.preparedQuery(REVISION_COUNT_SQL).execute(Tuple.of(encounterId)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(REVISION_LIST_SQL).execute(Tuple.of(encounterId)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(listItemFromRow(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 读取单条修订详情；再次校验 period 的 encounter 归属与修订记录一致。
     */
    fun getRevision(id: String): Future<JsonObject> {
        return pool.preparedQuery(REVISION_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("care plan revision not found: $id"))

            val periodId = requireNotNull(row.getString("period_id"))
            val encounterId = requireNotNull(row.getString("encounter_id"))
            val assessmentId = requireNotNull(row.getString("assessment_id"))
            val newPlanId = requireNotNull(row.getString("new_plan_id"))
            val previousPlanId = row.getString("previous_plan_id")

            pool.preparedQuery(PERIOD_ENCOUNTER_SQL).execute(Tuple.of(periodId)).compose { periodRows ->
                val periodRow = periodRows.iterator().asSequence().firstOrNull()
                val boundEncounterId = periodRow?.getString("encounter_id")
                if (boundEncounterId == null || boundEncounterId != encounterId) {
                    return@compose Future.failedFuture(
                        ConflictException("care plan revision does not belong to the bound encounter")
                    )
                }
                val revision = JsonObject()
                    .put("id", row.getString("id"))
                    .put("period_id", periodId)
                    .put("encounter_id", encounterId)
                    .put("assessment_id", assessmentId)
                    .put("previous_plan_id", previousPlanId)
                    .put("new_plan_id", newPlanId)
                    .put("revision_no", row.getInteger("revision_no"))
                    .put("created_at", row.getOffsetDateTime("created_at")?.toString())

                readAssessment(assessmentId).compose { assessment ->
                    val previousFuture = if (previousPlanId != null) {
                        readPlanWithItems(previousPlanId).map { it }
                    } else {
                        Future.succeededFuture(null as JsonObject?)
                    }
                    previousFuture.compose { previousPlan ->
                        readPlanWithItems(newPlanId).compose { newPlan ->
                            readPlanTasks(newPlanId).map { tasks ->
                                revision
                                    .put("assessment", assessment)
                                    .put("previous_plan", previousPlan)
                                    .put("plan", newPlan)
                                    .put("tasks", tasks)
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    private fun readAssessment(assessmentId: String): Future<JsonObject> =
        pool.preparedQuery(ASSESSMENT_BY_ID_SQL).execute(Tuple.of(assessmentId)).map { rows ->
            AssessmentService.toJson(rows.iterator().next())
        }

    private fun readPlanWithItems(planId: String): Future<JsonObject> =
        pool.preparedQuery(PLAN_BY_ID_SQL).execute(Tuple.of(planId)).compose { planRows ->
            val planRow = planRows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("nursing plan not found: $planId"))
            pool.preparedQuery(PLAN_ITEMS_SQL).execute(Tuple.of(planId)).map { itemRows ->
                val items = JsonArray()
                for (itemRow in itemRows) items.add(PlanService.itemToJson(itemRow))
                PlanService.planToJson(planRow).put("items", items)
            }
        }

    private fun readPlanTasks(planId: String): Future<JsonArray> =
        pool.preparedQuery(PLAN_TASKS_SQL).execute(Tuple.of(planId)).map { rows ->
            val tasks = JsonArray()
            for (row in rows) tasks.add(TaskService.toJson(row))
            tasks
        }

    private fun listItemFromRow(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("period_id", row.getString("period_id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("revision_no", row.getInteger("revision_no"))
            .put("assessment_id", row.getString("assessment_id"))
            .put("assessment", JsonObject()
                .put("assess_type", row.getString("assess_type"))
                .put("assess_date", row.getLocalDate("assess_date")?.toString())
                .put("assessor", row.getString("assessor"))
                .put("result_level", row.getString("result_level")))
            .put("previous_plan_id", row.getString("previous_plan_id"))
            .put("previous_plan", if (row.getString("prev_plan_name") != null)
                JsonObject()
                    .put("id", row.getString("previous_plan_id"))
                    .put("plan_name", row.getString("prev_plan_name"))
                    .put("status", row.getString("prev_plan_status"))
            else
                null)
            .put("new_plan_id", row.getString("new_plan_id"))
            .put("new_plan", JsonObject()
                .put("id", row.getString("new_plan_id"))
                .put("plan_name", row.getString("new_plan_name"))
                .put("status", row.getString("new_plan_status")))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
}
