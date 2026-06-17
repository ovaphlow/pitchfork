package com.ovaphlow.crate.analytics

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Courses
import com.ovaphlow.crate.database.gen.public_.tables.Employees
import com.ovaphlow.crate.database.gen.public_.tables.EmployeeSkills
import com.ovaphlow.crate.database.gen.public_.tables.ExamRecords
import com.ovaphlow.crate.database.gen.public_.tables.LearningProgress
import com.ovaphlow.crate.database.gen.public_.tables.Positions
import com.ovaphlow.crate.database.gen.public_.tables.TrainingAssignments
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

class AnalyticsService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(AnalyticsService::class.java)

    // ==============================
    // Training Summary
    // ==============================

    fun getTrainingSummary(): Future<JsonObject> {
        val coursesQuery = ctx.select(DSL.count().`as`("cnt")).from(Courses.COURSES)
        val assignmentsQuery = ctx.select(DSL.count().`as`("cnt")).from(TrainingAssignments.TRAINING_ASSIGNMENTS)
        val completionQuery = ctx.select(
            DSL.coalesce(
                DSL.count().filterWhere(LearningProgress.LEARNING_PROGRESS.STATUS.eq("已完成")).mul(1.0)
                    .div(DSL.nullif(DSL.count(), 0)),
                0.0
            ).`as`("rate")
        ).from(LearningProgress.LEARNING_PROGRESS)
        val avgScoreQuery = ctx.select(
            DSL.coalesce(DSL.avg(ExamRecords.EXAM_RECORDS.SCORE), DSL.inline(java.math.BigDecimal.ZERO)).`as`("avg_score")
        ).from(ExamRecords.EXAM_RECORDS)
        val activeQuery = ctx.select(DSL.count().`as`("cnt"))
            .from(LearningProgress.LEARNING_PROGRESS)
            .where(LearningProgress.LEARNING_PROGRESS.STATUS.eq("学习中"))
        val employeesQuery = ctx.select(DSL.count().`as`("cnt")).from(Employees.EMPLOYEES)

        return pool.preparedQuery(DatabaseConfig.sql(coursesQuery))
            .execute(DatabaseConfig.tuple(coursesQuery))
            .flatMap { coursesRows ->
                val totalCourses = coursesRows.iterator().next().getLong("cnt") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(assignmentsQuery))
                    .execute(DatabaseConfig.tuple(assignmentsQuery))
                    .flatMap { assignRows ->
                        val totalAssignments = assignRows.iterator().next().getLong("cnt") ?: 0L
                        pool.preparedQuery(DatabaseConfig.sql(completionQuery))
                            .execute(DatabaseConfig.tuple(completionQuery))
                            .flatMap { compRows ->
                                val completionRate = compRows.iterator().next().getDouble("rate") ?: 0.0
                                pool.preparedQuery(DatabaseConfig.sql(avgScoreQuery))
                                    .execute(DatabaseConfig.tuple(avgScoreQuery))
                                    .flatMap { scoreRows ->
                                        val avgScore = scoreRows.iterator().next().getDouble("avg_score") ?: 0.0
                                        pool.preparedQuery(DatabaseConfig.sql(activeQuery))
                                            .execute(DatabaseConfig.tuple(activeQuery))
                                            .flatMap { activeRows ->
                                                val activeTraining = activeRows.iterator().next().getLong("cnt") ?: 0L
                                                pool.preparedQuery(DatabaseConfig.sql(employeesQuery))
                                                    .execute(DatabaseConfig.tuple(employeesQuery))
                                                    .map { empRows ->
                                                        val totalEmployees = empRows.iterator().next().getLong("cnt") ?: 0L
                                                        JsonObject()
                                                            .put("total_courses", totalCourses)
                                                            .put("total_assignments", totalAssignments)
                                                            .put("completion_rate", completionRate)
                                                            .put("avg_score", avgScore)
                                                            .put("active_training", activeTraining)
                                                            .put("total_employees", totalEmployees)
                                                    }
                                            }
                                    }
                            }
                    }
            }
    }

    // ==============================
    // Skill Heatmap
    // ==============================

    fun getSkillHeatmap(departmentId: String?): Future<JsonObject> {
        val emp = Employees.EMPLOYEES
        val pos = Positions.POSITIONS
        val es = EmployeeSkills.EMPLOYEE_SKILLS

        val empConditions = mutableListOf<Condition>()
        departmentId?.takeIf { it.isNotBlank() }?.let { empConditions.add(emp.DEPARTMENT_ID.eq(it)) }

        val employeesQuery = ctx.select(emp.ID, emp.NAME)
            .from(emp)
            .where(empConditions)
            .orderBy(emp.NAME.asc())
        val positionsQuery = ctx.select(pos.ID, pos.NAME)
            .from(pos)
            .orderBy(pos.NAME.asc())

        return pool.preparedQuery(DatabaseConfig.sql(employeesQuery))
            .execute(DatabaseConfig.tuple(employeesQuery))
            .flatMap { empRows: RowSet<Row> ->
                val employees = mutableListOf<JsonObject>()
                for (row in empRows) {
                    employees.add(JsonObject()
                        .put("id", row.getValue("id")?.toString())
                        .put("name", row.getValue("name")?.toString()))
                }

                pool.preparedQuery(DatabaseConfig.sql(positionsQuery))
                    .execute(DatabaseConfig.tuple(positionsQuery))
                    .flatMap { posRows: RowSet<Row> ->
                        val positions = mutableListOf<JsonObject>()
                        for (row in posRows) {
                            positions.add(JsonObject()
                                .put("id", row.getValue("id")?.toString())
                                .put("name", row.getValue("name")?.toString()))
                        }

                        if (positions.isEmpty() || employees.isEmpty()) {
                            return@flatMap Future.succeededFuture(JsonObject()
                                .put("positions", JsonArray())
                                .put("employees", JsonArray())
                                .put("matrix", JsonArray()))
                        }

                        val posIds = positions.map { it.getString("id") }
                        val empIds = employees.map { it.getString("id") }

                        val skillQuery = ctx.select(
                                es.SKILL_ID.`as`("position_id"), es.EMPLOYEE_ID,
                                DSL.coalesce(es.CURRENT_LEVEL, 0.toShort()).`as`("skill_level")
                            )
                            .from(es)
                            .where(es.SKILL_ID.`in`(posIds).and(es.EMPLOYEE_ID.`in`(empIds)))

                        pool.preparedQuery(DatabaseConfig.sql(skillQuery))
                            .execute(DatabaseConfig.tuple(skillQuery))
                            .map { skillRows: RowSet<Row> ->
                                val skillMap = mutableMapOf<String, MutableMap<String, Int>>()
                                for (row in skillRows) {
                                    val pid = row.getValue("position_id")?.toString() ?: continue
                                    val uid = row.getValue("employee_id")?.toString() ?: continue
                                    val level = row.getValue("skill_level")?.let { (it as Number).toInt() } ?: 0
                                    skillMap.getOrPut(pid) { mutableMapOf() }[uid] = level
                                }

                                val matrix = JsonArray()
                                for (pos in positions) {
                                    val pid = pos.getString("id")
                                    val rowArr = JsonArray()
                                    for (emp in employees) {
                                        val uid = emp.getString("id")
                                        val level = skillMap[pid]?.get(uid) ?: 0
                                        rowArr.add(level)
                                    }
                                    matrix.add(rowArr)
                                }

                                JsonObject()
                                    .put("positions", JsonArray(positions))
                                    .put("employees", JsonArray(employees))
                                    .put("matrix", matrix)
                            }
                    }
            }
    }

    // ==============================
    // Quality Correlation
    // ==============================

    fun getQualityCorrelation(departmentId: String?): Future<JsonObject> {
        val e = Employees("e")
        val es = EmployeeSkills("es")
        val er = ExamRecords("er")

        val conditions = mutableListOf<Condition>()
        departmentId?.takeIf { it.isNotBlank() }?.let { conditions.add(e.DEPARTMENT_ID.eq(it)) }

        val skillSub = DSL.field(
            DSL.select(
                DSL.count().filterWhere(es.CURRENT_LEVEL.ge(2.toShort()))
                    .mul(1.0)
                    .div(DSL.nullif(DSL.count(), DSL.inline(0)))
            ).from(es).where(es.EMPLOYEE_ID.eq(e.ID))
        )

        val examSub = DSL.field(
            DSL.select(
                DSL.count().filterWhere(er.PASSED.eq(true))
                    .mul(1.0)
                    .div(DSL.nullif(DSL.count(), DSL.inline(0)))
            ).from(er).where(er.EMPLOYEE_ID.eq(e.ID))
        )

        val query = ctx.select(
                e.ID.`as`("employee_id"),
                e.NAME.`as`("employee_name"),
                DSL.coalesce(skillSub, DSL.inline(0.0)).`as`("skill_compliance_rate"),
                DSL.coalesce(examSub, DSL.inline(0.0)).`as`("exam_pass_rate")
            ).from(e)
            .where(conditions)
            .orderBy(e.NAME.asc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows: RowSet<Row> ->
                val records = JsonArray()
                var totalCompliance = 0.0
                var totalExamPass = 0.0
                var count = 0
                for (row in rows) {
                    val compliance = row.getDouble("skill_compliance_rate") ?: 0.0
                    val examPass = row.getDouble("exam_pass_rate") ?: 0.0
                    records.add(JsonObject()
                        .put("employee_id", row.getValue("employee_id")?.toString())
                        .put("employee_name", row.getValue("employee_name")?.toString())
                        .put("skill_compliance_rate", compliance)
                        .put("exam_pass_rate", examPass))
                    totalCompliance += compliance
                    totalExamPass += examPass
                    count++
                }
                val avgCompliance = if (count > 0) totalCompliance / count else 0.0
                val avgExamPass = if (count > 0) totalExamPass / count else 0.0
                JsonObject()
                    .put("records", records)
                    .put("summary", JsonObject()
                        .put("avg_skill_compliance_rate", avgCompliance)
                        .put("avg_exam_pass_rate", avgExamPass)
                        .put("employee_count", count))
            }
    }
}
