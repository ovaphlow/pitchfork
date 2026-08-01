package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 养老离院交接摘要的 Nursing 只读快照服务。
 *
 * 仅供 Healthcare 归档编排调用，不注册任何新的 `/crate-api/nursing/...` 公开路由，
 * 不依赖 Healthcare Kotlin 库。所有方法都必须使用调用方外层事务传入的同一
 * [SqlClient] 执行，只读批量读取护理评估、计划及措施、任务及执行，并组装
 * 第 4.3 节定义的 Nursing 部分快照 JSON。
 *
 * 查询次数固定：评估 1 次、计划 1 次、措施 1 次（计划非空时）、任务 1 次、
 * 执行 1 次（JOIN 任务按 period 关联，不按患者或日期猜测）；不产生任何写
 * 副作用，不调用任务生成、状态流转、库存或时间线能力。
 */
class ElderlyDischargeHandoverSnapshotService(
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 执行状态计数键的稳定顺序（PENDING/IN_PROGRESS/COMPLETED/SKIPPED/CANCELLED） */
        private val EXECUTION_STATUSES = listOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")

        private fun rowString(row: Row, name: String): String? = row.getValue(name)?.toString()
        private fun rowDate(row: Row, name: String): String? = (row.getValue(name) as? LocalDate)?.toString()
        private fun rowTime(row: Row, name: String): String? = (row.getValue(name) as? OffsetDateTime)?.toString()
        private fun rowScore(row: Row, name: String): Double? = (row.getValue(name) as? BigDecimal)?.toDouble()
        private fun rowDays(row: Row, name: String): Int? = (row.getValue(name) as? Number)?.toInt()
        private fun rowJson(row: Row, name: String): JsonObject? = row.getValue(name) as? JsonObject

        fun assessmentToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", rowString(row, "id"))
                .put("assess_type", rowString(row, "assess_type"))
                .put("assess_date", rowDate(row, "assess_date"))
                .put("assessor", rowString(row, "assessor"))
                .put("total_score", rowScore(row, "total_score"))
                .put("result_level", rowString(row, "result_level"))
                .put("detail", rowJson(row, "detail"))
                .put("remark", rowString(row, "remark"))
                .put("created_at", rowTime(row, "created_at"))

        fun planToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", rowString(row, "id"))
                .put("plan_name", rowString(row, "plan_name"))
                .put("goals", rowString(row, "goals"))
                .put("status", rowString(row, "status"))
                .put("created_by", rowString(row, "created_by"))
                .put("start_date", rowDate(row, "start_date"))
                .put("end_date", rowDate(row, "end_date"))
                .put("created_at", rowTime(row, "created_at"))
                .put("items", JsonArray())

        fun itemToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", rowString(row, "id"))
                .put("plan_id", rowString(row, "plan_id"))
                .put("action", rowString(row, "action"))
                .put("frequency_code", rowString(row, "frequency_code"))
                .put("frequency_name", rowString(row, "frequency_name"))
                .put("duration_days", rowDays(row, "duration_days"))
                .put("remark", rowString(row, "remark"))
                .put("status", rowString(row, "status"))
                .put("created_at", rowTime(row, "created_at"))

        fun taskToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", rowString(row, "id"))
                .put("description", rowString(row, "description"))
                .put("task_type", rowString(row, "task_type"))
                .put("frequency_code", rowString(row, "frequency_code"))
                .put("frequency_name", rowString(row, "frequency_name"))
                .put("start_date", rowDate(row, "start_date"))
                .put("end_date", rowDate(row, "end_date"))
                .put("status", rowString(row, "status"))
                .put("created_at", rowTime(row, "created_at"))
                .put("executions", JsonArray())

        fun executionToJson(row: Row): JsonObject =
            JsonObject()
                .put("id", rowString(row, "id"))
                .put("task_id", rowString(row, "task_id"))
                .put("planned_time", rowTime(row, "planned_time"))
                .put("actual_time", rowTime(row, "actual_time"))
                .put("executor", rowString(row, "executor"))
                .put("status", rowString(row, "status"))
                .put("note", rowString(row, "note"))
                .put("created_at", rowTime(row, "created_at"))

        /**
         * 内存组装 Nursing 快照：计划/措施、任务/执行分别按 ID 归组，
         * 执行状态计数始终包含全部五种状态（缺省为零）。
         * 输入行顺序由调用方的批量 SQL 稳定排序保证，本函数不重新查询。
         */
        fun assembleSnapshot(
            assessments: List<JsonObject>,
            plans: List<JsonObject>,
            items: List<JsonObject>,
            tasks: List<JsonObject>,
            executions: List<JsonObject>,
        ): JsonObject {
            val itemsByPlan = items.groupBy { it.getString("plan_id") }
            val plansJson = JsonArray()
            for (plan in plans) {
                val copy = plan.copy()
                val planItems = JsonArray(itemsByPlan[plan.getString("id")] ?: emptyList<JsonObject>())
                copy.put("items", planItems)
                plansJson.add(copy)
            }

            val executionsByTask = executions.groupBy { it.getString("task_id") }
            val tasksJson = JsonArray()
            for (task in tasks) {
                val copy = task.copy()
                val taskExecutions = JsonArray(executionsByTask[task.getString("id")] ?: emptyList<JsonObject>())
                copy.put("executions", taskExecutions)
                tasksJson.add(copy)
            }

            val summary = JsonObject()
            for (status in EXECUTION_STATUSES) {
                summary.put(status, executions.count { it.getString("status") == status })
            }

            return JsonObject()
                .put("assessments", JsonArray(assessments))
                .put("plans", plansJson)
                .put("tasks", tasksJson)
                .put("execution_summary", summary)
        }
    }

    /**
     * 批量读取该 [periodId] 的全部评估、计划/措施、任务/执行并组装快照。
     * 任一源表查询失败都会使返回的 Future 失败，从而回滚外层归档事务。
     */
    fun buildNursingSnapshot(client: SqlClient, periodId: String): Future<JsonObject> {
        val assessmentsF = loadAssessments(client, periodId)
        val plansF = loadPlans(client, periodId)
        val tasksF = loadTasks(client, periodId)
        val executionsF = loadExecutions(client, periodId)

        return CompositeFuture.all(assessmentsF, plansF, tasksF, executionsF).compose { composite ->
            val assessments = composite.resultAt<List<JsonObject>>(0)
            val plans = composite.resultAt<List<JsonObject>>(1)
            val tasks = composite.resultAt<List<JsonObject>>(2)
            val executions = composite.resultAt<List<JsonObject>>(3)

            if (plans.isEmpty()) {
                Future.succeededFuture(assembleSnapshot(assessments, plans, emptyList(), tasks, executions))
            } else {
                loadItems(client, plans.map { requireNotNull(it.getString("id")) }).map { items ->
                    assembleSnapshot(assessments, plans, items, tasks, executions)
                }
            }
        }
    }

    private fun loadAssessments(client: SqlClient, periodId: String): Future<List<JsonObject>> {
        val query = ctx.select(
            DSL.field("id"),
            DSL.field("assess_type"),
            DSL.field("assess_date"),
            DSL.field("assessor"),
            DSL.field("total_score"),
            DSL.field("result_level"),
            DSL.field("detail"),
            DSL.field("remark"),
            DSL.field("created_at"),
        )
            .from(DSL.table(DSL.name("nursing", "nursing_assessments")))
            .where(DSL.field("period_id").eq(periodId))
            .orderBy(
                DSL.field("assess_date").asc(),
                DSL.field("created_at").asc(),
                DSL.field("id").asc(),
            )
        return execute(client, query).map { rows -> rows.map { assessmentToJson(it) } }
    }

    private fun loadPlans(client: SqlClient, periodId: String): Future<List<JsonObject>> {
        val query = ctx.select(
            DSL.field("id"),
            DSL.field("plan_name"),
            DSL.field("goals"),
            DSL.field("status"),
            DSL.field("created_by"),
            DSL.field("start_date"),
            DSL.field("end_date"),
            DSL.field("created_at"),
        )
            .from(DSL.table(DSL.name("nursing", "nursing_plans")))
            .where(DSL.field("period_id").eq(periodId))
            .orderBy(
                DSL.field("created_at").asc(),
                DSL.field("id").asc(),
            )
        return execute(client, query).map { rows -> rows.map { planToJson(it) } }
    }

    private fun loadItems(client: SqlClient, planIds: List<String>): Future<List<JsonObject>> {
        val query = ctx.select(
            DSL.field("id"),
            DSL.field("plan_id"),
            DSL.field("action"),
            DSL.field("frequency_code"),
            DSL.field("frequency_name"),
            DSL.field("duration_days"),
            DSL.field("remark"),
            DSL.field("status"),
            DSL.field("created_at"),
        )
            .from(DSL.table(DSL.name("nursing", "nursing_plan_items")))
            .where(DSL.field("plan_id").`in`(planIds))
            .orderBy(
                DSL.field("created_at").asc(),
                DSL.field("id").asc(),
            )
        return execute(client, query).map { rows -> rows.map { itemToJson(it) } }
    }

    private fun loadTasks(client: SqlClient, periodId: String): Future<List<JsonObject>> {
        val query = ctx.select(
            DSL.field("id"),
            DSL.field("description"),
            DSL.field("task_type"),
            DSL.field("frequency_code"),
            DSL.field("frequency_name"),
            DSL.field("start_date"),
            DSL.field("end_date"),
            DSL.field("status"),
            DSL.field("created_at"),
        )
            .from(DSL.table(DSL.name("nursing", "nursing_tasks")))
            .where(DSL.field("period_id").eq(periodId))
            .orderBy(
                DSL.field("created_at").asc(),
                DSL.field("id").asc(),
            )
        return execute(client, query).map { rows -> rows.map { taskToJson(it) } }
    }

    private fun loadExecutions(client: SqlClient, periodId: String): Future<List<JsonObject>> {
        val query = ctx.select(
            DSL.field("e.id"),
            DSL.field("e.task_id"),
            DSL.field("e.planned_time"),
            DSL.field("e.actual_time"),
            DSL.field("e.executor"),
            DSL.field("e.status"),
            DSL.field("e.note"),
            DSL.field("e.created_at"),
        )
            .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
            .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
            .on(DSL.field("e.task_id").eq(DSL.field("t.id")))
            .where(DSL.field("t.period_id").eq(periodId))
            .orderBy(
                DSL.field("e.actual_time").asc().nullsLast(),
                DSL.field("e.planned_time").asc().nullsLast(),
                DSL.field("e.created_at").asc(),
                DSL.field("e.id").asc(),
            )
        return execute(client, query).map { rows -> rows.map { executionToJson(it) } }
    }

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
