package com.ovaphlow.crate.analytics

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class AnalyticsService(private val pool: Pool) {

    private val log = LoggerFactory.getLogger(AnalyticsService::class.java)

    // ==============================
    // Training Summary
    // ==============================

    fun getTrainingSummary(): Future<JsonObject> {
        val coursesSql = "SELECT COUNT(*) AS cnt FROM courses"
        val assignmentsSql = "SELECT COUNT(*) AS cnt FROM training_assignments"
        val completionSql = """
            SELECT COALESCE(
                COUNT(*) FILTER (WHERE status = '已完成') * 1.0 / NULLIF(COUNT(*), 0), 0.0
            ) AS rate FROM learning_progress
        """.trimIndent()
        val avgScoreSql = "SELECT COALESCE(AVG(score), 0) AS avg_score FROM exam_records"
        val activeSql = "SELECT COUNT(*) AS cnt FROM learning_progress WHERE status = '学习中'"
        val employeesSql = "SELECT COUNT(*) AS cnt FROM employees"

        return pool.preparedQuery(coursesSql)
            .execute()
            .flatMap { coursesRows ->
                val totalCourses = coursesRows.iterator().next().getLong("cnt") ?: 0L
                pool.preparedQuery(assignmentsSql)
                    .execute()
                    .flatMap { assignRows ->
                        val totalAssignments = assignRows.iterator().next().getLong("cnt") ?: 0L
                        pool.preparedQuery(completionSql)
                            .execute()
                            .flatMap { compRows ->
                                val completionRate = compRows.iterator().next().getDouble("rate") ?: 0.0
                                pool.preparedQuery(avgScoreSql)
                                    .execute()
                                    .flatMap { scoreRows ->
                                        val avgScore = scoreRows.iterator().next().getDouble("avg_score") ?: 0.0
                                        pool.preparedQuery(activeSql)
                                            .execute()
                                            .flatMap { activeRows ->
                                                val activeTraining = activeRows.iterator().next().getLong("cnt") ?: 0L
                                                pool.preparedQuery(employeesSql)
                                                    .execute()
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
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        var idx = 1

        if (!departmentId.isNullOrBlank()) {
            conditions.add("emp.department_id = \${$idx}")
            params.add(departmentId)
            idx++
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val employeesSql = "SELECT id, name FROM employees emp $whereClause ORDER BY name ASC"
        val positionsSql = "SELECT id, name FROM positions ORDER BY name ASC"

        return pool.preparedQuery(employeesSql)
            .execute(buildTuple(params))
            .flatMap { empRows: RowSet<Row> ->
                val employees = mutableListOf<JsonObject>()
                for (row in empRows) {
                    employees.add(JsonObject()
                        .put("id", row.getValue("id")?.toString())
                        .put("name", row.getValue("name")?.toString()))
                }

                pool.preparedQuery(positionsSql)
                    .execute()
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

                        val placeholders = posIds.mapIndexed { i, _ -> "\${${i + 1}}" }.joinToString(", ")
                        val empPlaceholders = empIds.mapIndexed { i, _ -> "\${${posIds.size + i + 1}}" }.joinToString(", ")

                        val skillSql = """
                            SELECT position_id, employee_id, COALESCE(current_level, 0) AS skill_level
                            FROM employee_skills
                            WHERE position_id IN ($placeholders) AND employee_id IN ($empPlaceholders)
                        """.trimIndent()

                        val skillTuple = Tuple.tuple()
                        for (pid in posIds) skillTuple.addString(pid)
                        for (eid in empIds) skillTuple.addString(eid)

                        pool.preparedQuery(skillSql)
                            .execute(skillTuple)
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
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        var idx = 1

        if (!departmentId.isNullOrBlank()) {
            conditions.add("e.department_id = \${$idx}")
            params.add(departmentId)
            idx++
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        // 关联 employees → employee_skills（技能达标率）+ exam_records（考试合格率）
        val sql = """
            SELECT 
                e.id AS employee_id,
                e.name AS employee_name,
                COALESCE(
                    (SELECT COUNT(*) FILTER (WHERE es.current_level >= 2) * 1.0 / NULLIF(COUNT(*), 0)
                     FROM employee_skills es WHERE es.employee_id = e.id),
                    0.0
                ) AS skill_compliance_rate,
                COALESCE(
                    (SELECT COUNT(*) FILTER (WHERE er.passed = TRUE) * 1.0 / NULLIF(COUNT(*), 0)
                     FROM exam_records er WHERE er.employee_id = e.id),
                    0.0
                ) AS exam_pass_rate
            FROM employees e
            $whereClause
            ORDER BY e.name ASC
        """.trimIndent()

        val tuple = buildTuple(params)
        return pool.preparedQuery(sql)
            .execute(tuple)
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

    // ==============================
    // Helpers
    // ==============================

    private fun buildTuple(params: List<Any?>): Tuple {
        val tuple = Tuple.tuple()
        for (p in params) {
            when (p) {
                is String -> tuple.addString(p)
                is Int -> tuple.addInteger(p)
                is Long -> tuple.addLong(p)
                is Boolean -> tuple.addBoolean(p)
                is Float -> tuple.addFloat(p)
                is Double -> tuple.addDouble(p)
                is OffsetDateTime -> tuple.addOffsetDateTime(p)
                else -> tuple.addValue(p)
            }
        }
        return tuple
    }
}
