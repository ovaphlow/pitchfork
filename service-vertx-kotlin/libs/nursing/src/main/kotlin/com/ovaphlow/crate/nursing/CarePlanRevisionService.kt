package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.nursing.tables.NursingAssessments.NURSING_ASSESSMENTS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingCarePlanRevisions.NURSING_CARE_PLAN_REVISIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingPlanItems.NURSING_PLAN_ITEMS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingPlans.NURSING_PLANS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingServicePeriods.NURSING_SERVICE_PERIODS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTaskExecutions.NURSING_TASK_EXECUTIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTasks.NURSING_TASKS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 复评与照护计划修订 — 连接绑定协作与修订关系读写。
 *
 * 写路径的所有步骤必须在 Healthcare 外层事务传入的同一个 [SqlClient] 上执行；
 * 本服务不启动自己的事务，也不按 patient_id 猜测周期。
 */
class CarePlanRevisionService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 复评允许的评估类型（不含 ADMISSION：入住评估不是复评） */
        val VALID_ASSESS_TYPES = setOf(
            "FALL_RISK", "PRESSURE_SORE", "PAIN",
            "BARTHEL", "NUTRITION", "HOME_ENVIRONMENT", "OTHER"
        )

        fun isValidAssessType(value: String): Boolean = value in VALID_ASSESS_TYPES
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
        val query = ctx.selectFrom(NURSING_SERVICE_PERIODS)
            .where(NURSING_SERVICE_PERIODS.ENCOUNTER_ID.eq(encounterId))
            .forUpdate()
        return execute(client, query).compose { rows ->
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
        val query = ctx.selectOne()
            .from(NURSING_TASK_EXECUTIONS)
            .where(NURSING_TASK_EXECUTIONS.TASK_ID.`in`(taskIds))
            .and(NURSING_TASK_EXECUTIONS.STATUS.eq("IN_PROGRESS"))
        return execute(client, query).compose { rows ->
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
        val query = ctx.select(DSL.coalesce(DSL.max(NURSING_CARE_PLAN_REVISIONS.REVISION_NO), 0).`as`("max_no"))
            .from(NURSING_CARE_PLAN_REVISIONS)
            .where(NURSING_CARE_PLAN_REVISIONS.PERIOD_ID.eq(periodId))
        return execute(client, query).map { rows ->
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
        var insert = ctx.insertInto(NURSING_ASSESSMENTS)
            .set(NURSING_ASSESSMENTS.ID, id)
            .set(NURSING_ASSESSMENTS.ENCOUNTER_ID, input.encounterId)
            .set(NURSING_ASSESSMENTS.PERIOD_ID, input.periodId)
            .set(NURSING_ASSESSMENTS.ASSESS_TYPE, input.assessType)
            .set(NURSING_ASSESSMENTS.ASSESS_DATE, input.assessDate)
            .set(NURSING_ASSESSMENTS.CREATED_AT, now)
        input.assessor?.let { insert = insert.set(NURSING_ASSESSMENTS.ASSESSOR, it) }
        input.totalScore?.let { insert = insert.set(NURSING_ASSESSMENTS.TOTAL_SCORE, BigDecimal.valueOf(it)) }
        input.resultLevel?.let { insert = insert.set(NURSING_ASSESSMENTS.RESULT_LEVEL, it) }
        input.detail?.let { insert = insert.set(NURSING_ASSESSMENTS.DETAIL, JSONB.valueOf(it.encode())) }
        input.remark?.let { insert = insert.set(NURSING_ASSESSMENTS.REMARK, it) }

        return execute(client, insert).map {
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
        val insert = ctx.insertInto(NURSING_CARE_PLAN_REVISIONS)
            .set(NURSING_CARE_PLAN_REVISIONS.ID, id)
            .set(NURSING_CARE_PLAN_REVISIONS.PERIOD_ID, periodId)
            .set(NURSING_CARE_PLAN_REVISIONS.ENCOUNTER_ID, encounterId)
            .set(NURSING_CARE_PLAN_REVISIONS.ASSESSMENT_ID, assessmentId)
            .set(NURSING_CARE_PLAN_REVISIONS.PREVIOUS_PLAN_ID, previousPlanId)
            .set(NURSING_CARE_PLAN_REVISIONS.NEW_PLAN_ID, newPlanId)
            .set(NURSING_CARE_PLAN_REVISIONS.REVISION_NO, revisionNo)
            .set(NURSING_CARE_PLAN_REVISIONS.CREATED_AT, now)

        return execute(client, insert).map {
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
        val condition = NURSING_CARE_PLAN_REVISIONS.ENCOUNTER_ID.eq(encounterId)

        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(NURSING_CARE_PLAN_REVISIONS)
            .where(condition)
        val dataQuery = ctx.select(
            NURSING_CARE_PLAN_REVISIONS.fields().toList()
                + NURSING_ASSESSMENTS.ASSESS_TYPE
                + NURSING_ASSESSMENTS.ASSESS_DATE
                + NURSING_ASSESSMENTS.ASSESSOR
                + NURSING_ASSESSMENTS.RESULT_LEVEL
                + NURSING_PLANS.PLAN_NAME.`as`("new_plan_name")
                + NURSING_PLANS.STATUS.`as`("new_plan_status")
                + DSL.field(DSL.name("prev", "plan_name"), String::class.java).`as`("prev_plan_name")
                + DSL.field(DSL.name("prev", "status"), String::class.java).`as`("prev_plan_status")
        )
            .from(NURSING_CARE_PLAN_REVISIONS)
            .join(NURSING_ASSESSMENTS)
            .on(NURSING_CARE_PLAN_REVISIONS.ASSESSMENT_ID.eq(NURSING_ASSESSMENTS.ID))
            .join(NURSING_PLANS)
            .on(NURSING_CARE_PLAN_REVISIONS.NEW_PLAN_ID.eq(NURSING_PLANS.ID))
            .leftJoin(DSL.table(DSL.name("nursing", "nursing_plans")).`as`("prev"))
            .on(NURSING_CARE_PLAN_REVISIONS.PREVIOUS_PLAN_ID.eq(DSL.field(DSL.name("prev", "id"), String::class.java)))
            .where(condition)
            .orderBy(NURSING_CARE_PLAN_REVISIONS.REVISION_NO.desc())

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
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
        val revisionQuery = ctx.selectFrom(NURSING_CARE_PLAN_REVISIONS)
            .where(NURSING_CARE_PLAN_REVISIONS.ID.eq(id))
        return execute(pool, revisionQuery).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("care plan revision not found: $id"))

            val periodId = requireNotNull(row.getString("period_id"))
            val encounterId = requireNotNull(row.getString("encounter_id"))
            val assessmentId = requireNotNull(row.getString("assessment_id"))
            val newPlanId = requireNotNull(row.getString("new_plan_id"))
            val previousPlanId = row.getString("previous_plan_id")

            val periodQuery = ctx.select(NURSING_SERVICE_PERIODS.ENCOUNTER_ID)
                .from(NURSING_SERVICE_PERIODS)
                .where(NURSING_SERVICE_PERIODS.ID.eq(periodId))
            execute(pool, periodQuery).compose { periodRows ->
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

    private fun readAssessment(assessmentId: String): Future<JsonObject> {
        val query = ctx.selectFrom(NURSING_ASSESSMENTS)
            .where(NURSING_ASSESSMENTS.ID.eq(assessmentId))
        return execute(pool, query).map { rows ->
            AssessmentService.toJson(rows.iterator().next())
        }
    }

    private fun readPlanWithItems(planId: String): Future<JsonObject> {
        val planQuery = ctx.selectFrom(NURSING_PLANS)
            .where(NURSING_PLANS.ID.eq(planId))
        val itemsQuery = ctx.selectFrom(NURSING_PLAN_ITEMS)
            .where(NURSING_PLAN_ITEMS.PLAN_ID.eq(planId))
            .orderBy(NURSING_PLAN_ITEMS.CREATED_AT.asc())
        return execute(pool, planQuery).compose { planRows ->
            val planRow = planRows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("nursing plan not found: $planId"))
            execute(pool, itemsQuery).map { itemRows ->
                val items = JsonArray()
                for (itemRow in itemRows) items.add(PlanService.itemToJson(itemRow))
                PlanService.planToJson(planRow).put("items", items)
            }
        }
    }

    private fun readPlanTasks(planId: String): Future<JsonArray> {
        val query = ctx.select(NURSING_TASKS.fields().toList())
            .from(NURSING_TASKS)
            .join(NURSING_PLAN_ITEMS).on(NURSING_TASKS.PLAN_ITEM_ID.eq(NURSING_PLAN_ITEMS.ID))
            .where(NURSING_PLAN_ITEMS.PLAN_ID.eq(planId))
            .orderBy(NURSING_TASKS.CREATED_AT.asc())
        return execute(pool, query).map { rows ->
            val tasks = JsonArray()
            for (row in rows) tasks.add(TaskService.toJson(row))
            tasks
        }
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

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
