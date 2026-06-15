package com.ovaphlow.crate.skills

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Certificates
import com.ovaphlow.crate.database.gen.public_.tables.EmployeeCertificates
import com.ovaphlow.crate.database.gen.public_.tables.EmployeeSkills
import com.ovaphlow.crate.database.gen.public_.tables.Positions
import com.ovaphlow.crate.database.gen.public_.tables.Skills
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.time.LocalDate
import org.jooq.impl.DSL.count

class SkillsService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(SkillsService::class.java)
    private val p = Positions.POSITIONS
    private val s = Skills.SKILLS
    private val es = EmployeeSkills.EMPLOYEE_SKILLS
    private val c = Certificates.CERTIFICATES
    private val ec = EmployeeCertificates.EMPLOYEE_CERTIFICATES

    // ==========================================
    // Positions
    // ==========================================

    fun listPositions(limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(count().`as`("total")).from(p)
        val dataQuery = ctx.select(p.ID, p.NAME, p.PARENT_ID, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)
            .from(p)
            .orderBy(p.NAME.asc())
            .limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(positionToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createPosition(
        name: String,
        parentId: String? = null,
        skillRequirements: JsonObject = JsonObject(),
        assessmentConfig: JsonObject = JsonObject(),
        extra: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val query = if (parentId != null) {
            ctx.insertInto(p)
                .columns(p.ID, p.NAME, p.PARENT_ID, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)
                .values(id, name, parentId, JSONB.valueOf(skillRequirements.encode()), JSONB.valueOf(assessmentConfig.encode()), JSONB.valueOf(extra.encode()))
        } else {
            ctx.insertInto(p)
                .columns(p.ID, p.NAME, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)
                .values(id, name, JSONB.valueOf(skillRequirements.encode()), JSONB.valueOf(assessmentConfig.encode()), JSONB.valueOf(extra.encode()))
        }
        query.returning(p.ID, p.NAME, p.PARENT_ID, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> positionToJson(rows.iterator().next()) }
    }

    fun getPosition(id: String): Future<JsonObject> {
        val query = ctx.select(p.ID, p.NAME, p.PARENT_ID, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)
            .from(p)
            .where(p.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("position not found"))
                else Future.succeededFuture(positionToJson(rows.iterator().next()))
            }
    }

    fun updatePosition(
        id: String,
        name: String? = null,
        parentId: String? = null,
        skillRequirements: JsonObject? = null,
        assessmentConfig: JsonObject? = null,
        extra: JsonObject? = null
    ): Future<JsonObject> {
        return getPosition(id).flatMap { existing: JsonObject ->
            val newName = name ?: existing.getString("name")
            val newParentId = if (parentId != null) parentId else existing.getString("parent_id") ?: ""
            val newSkillRequirements = skillRequirements ?: existing.getJsonObject("skill_requirements") ?: JsonObject()
            val newAssessmentConfig = assessmentConfig ?: existing.getJsonObject("assessment_config") ?: JsonObject()
            val newExtra = extra ?: existing.getJsonObject("extra") ?: JsonObject()

            val query = ctx.update(p)
                .set(p.NAME, newName)
                .set(p.PARENT_ID, newParentId)
                .set(p.SKILL_REQUIREMENTS, JSONB.valueOf(newSkillRequirements.encode()))
                .set(p.ASSESSMENT_CONFIG, JSONB.valueOf(newAssessmentConfig.encode()))
                .set(p.EXTRA, JSONB.valueOf(newExtra.encode()))
                .where(p.ID.eq(id))
                .returning(p.ID, p.NAME, p.PARENT_ID, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("position not found"))
                    else Future.succeededFuture(positionToJson(rows.iterator().next()))
                }
        }
    }

    fun deletePosition(id: String): Future<Void> {
        val sql = "DELETE FROM positions WHERE id = ?"
        return pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(id))
            .map { null as Void? }
    }

    fun getPositionTree(): Future<JsonArray> {
        val query = ctx.select(p.ID, p.NAME, p.PARENT_ID, p.SKILL_REQUIREMENTS, p.ASSESSMENT_CONFIG, p.EXTRA)
            .from(p)
            .orderBy(p.NAME.asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val all = mutableListOf<JsonObject>()
                for (row in rows) {
                    all.add(positionToJson(row))
                }
                val map = all.associateBy { it.getString("id") }
                val roots = JsonArray()
                for (node in all) {
                    val pid = node.getString("parent_id")
                    if (pid.isNullOrBlank()) {
                        roots.add(node)
                    } else {
                        val parent = map[pid]
                        if (parent != null) {
                            val children = parent.getJsonArray("children")
                            children.add(node)
                        } else {
                            roots.add(node)
                        }
                    }
                }
                roots
            }
    }

    // ==========================================
    // Skills
    // ==========================================

    fun listSkills(category: String? = null, limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        if (!category.isNullOrBlank()) {
            conditions.add(s.CATEGORY.eq(category))
        }

        val countQuery = ctx.select(count().`as`("total")).from(s).where(conditions)
        val dataQuery = ctx.select(s.ID, s.NAME, s.CATEGORY, s.EVALUATION_CRITERIA, s.DEFAULT_VALIDITY)
            .from(s)
            .where(conditions)
            .orderBy(s.CATEGORY.asc(), s.NAME.asc())
            .limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(skillToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createSkill(
        name: String,
        category: String,
        evaluationCriteria: JsonObject = JsonObject(),
        defaultValidity: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val validCategories = listOf("操作", "安全", "维保", "其它")
        if (category !in validCategories) {
            return Future.failedFuture(IllegalArgumentException("invalid category: $category, must be one of $validCategories"))
        }
        val id = Ulid.generate()
        val query = ctx.insertInto(s)
            .columns(s.ID, s.NAME, s.CATEGORY, s.EVALUATION_CRITERIA, s.DEFAULT_VALIDITY)
            .values(id, name, category, JSONB.valueOf(evaluationCriteria.encode()), JSONB.valueOf(defaultValidity.encode()))
            .returning(s.ID, s.NAME, s.CATEGORY, s.EVALUATION_CRITERIA, s.DEFAULT_VALIDITY)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> skillToJson(rows.iterator().next()) }
    }

    fun getSkill(id: String): Future<JsonObject> {
        val query = ctx.select(s.ID, s.NAME, s.CATEGORY, s.EVALUATION_CRITERIA, s.DEFAULT_VALIDITY)
            .from(s)
            .where(s.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("skill not found"))
                else Future.succeededFuture(skillToJson(rows.iterator().next()))
            }
    }

    fun updateSkill(
        id: String,
        name: String? = null,
        category: String? = null,
        evaluationCriteria: JsonObject? = null,
        defaultValidity: JsonObject? = null
    ): Future<JsonObject> {
        return getSkill(id).flatMap { existing: JsonObject ->
            val newName = name ?: existing.getString("name")
            val newCategory = category ?: existing.getString("category")
            if (category != null) {
                val validCategories = listOf("操作", "安全", "维保", "其它")
                if (category !in validCategories) {
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid category: $category, must be one of $validCategories"))
                }
            }
            val newEvaluationCriteria = evaluationCriteria ?: existing.getJsonObject("evaluation_criteria") ?: JsonObject()
            val newDefaultValidity = defaultValidity ?: existing.getJsonObject("default_validity") ?: JsonObject()

            val query = ctx.update(s)
                .set(s.NAME, newName)
                .set(s.CATEGORY, newCategory)
                .set(s.EVALUATION_CRITERIA, JSONB.valueOf(newEvaluationCriteria.encode()))
                .set(s.DEFAULT_VALIDITY, JSONB.valueOf(newDefaultValidity.encode()))
                .where(s.ID.eq(id))
                .returning(s.ID, s.NAME, s.CATEGORY, s.EVALUATION_CRITERIA, s.DEFAULT_VALIDITY)

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("skill not found"))
                    else Future.succeededFuture(skillToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteSkill(id: String): Future<Void> {
        val sql = "DELETE FROM skills WHERE id = ?"
        return pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(id))
            .map { null as Void? }
    }

    // ==========================================
    // Employee Skills
    // ==========================================

    fun listEmployeeSkills(employeeId: String): Future<JsonObject> {
        val query = ctx.select(
                es.ID, es.EMPLOYEE_ID, es.SKILL_ID,
                s.NAME.`as`("skill_name"), s.CATEGORY.`as`("skill_category"),
                es.CURRENT_LEVEL, es.ASSESSED_DATE, es.ASSESSOR_ID, es.EXPIRE_DATE, es.ASSESSMENT_RECORD
            )
            .from(es)
            .leftJoin(s).on(s.ID.eq(es.SKILL_ID))
            .where(es.EMPLOYEE_ID.eq(employeeId))
            .orderBy(s.CATEGORY.asc(), s.NAME.asc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val records = JsonArray()
                for (row in rows) {
                    records.add(employeeSkillToJson(row))
                }
                JsonObject().put("records", records)
            }
    }

    fun createEmployeeSkill(
        employeeId: String,
        skillId: String,
        currentLevel: Int = 1,
        assessedDate: String? = null,
        assessorId: String? = null,
        expireDate: String? = null,
        assessmentRecord: JsonObject = JsonObject()
    ): Future<JsonObject> {
        if (currentLevel < 1 || currentLevel > 4) {
            return Future.failedFuture(IllegalArgumentException("current_level must be between 1 and 4"))
        }
        val id = Ulid.generate()
        val sql = """
            INSERT INTO employee_skills (id, employee_id, skill_id, current_level, assessed_date, assessor_id, expire_date, assessment_record)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8::jsonb)
            RETURNING id, employee_id, skill_id, current_level, assessed_date, assessor_id, expire_date, assessment_record
        """.trimIndent()
        val tuple = io.vertx.sqlclient.Tuple.of(
            id, employeeId, skillId, currentLevel.toShort(),
            assessedDate ?: "", assessorId ?: "", expireDate ?: "", assessmentRecord.encode()
        )
        return pool.preparedQuery(sql)
            .execute(tuple)
            .map { rows -> employeeSkillToJson(rows.iterator().next()) }
            .recover { err ->
                val msg = err.message ?: ""
                if (msg.contains("duplicate key") || msg.contains("unique") || msg.contains("UNIQUE")) {
                    Future.failedFuture(IllegalArgumentException("employee already has this skill"))
                } else {
                    Future.failedFuture(err)
                }
            }
    }

    fun getEmployeeSkill(id: String): Future<JsonObject> {
        val query = ctx.select(
                es.ID, es.EMPLOYEE_ID, es.SKILL_ID,
                s.NAME.`as`("skill_name"), s.CATEGORY.`as`("skill_category"),
                es.CURRENT_LEVEL, es.ASSESSED_DATE, es.ASSESSOR_ID, es.EXPIRE_DATE, es.ASSESSMENT_RECORD
            )
            .from(es)
            .leftJoin(s).on(s.ID.eq(es.SKILL_ID))
            .where(es.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("employee skill not found"))
                else Future.succeededFuture(employeeSkillToJson(rows.iterator().next()))
            }
    }

    fun updateEmployeeSkill(
        id: String,
        skillId: String? = null,
        currentLevel: Int? = null,
        assessedDate: String? = null,
        assessorId: String? = null,
        expireDate: String? = null,
        assessmentRecord: JsonObject? = null
    ): Future<JsonObject> {
        return getEmployeeSkill(id).flatMap { existing: JsonObject ->
            if (currentLevel != null && (currentLevel < 1 || currentLevel > 4)) {
                return@flatMap Future.failedFuture(IllegalArgumentException("current_level must be between 1 and 4"))
            }
            val newSkillId = skillId ?: existing.getString("skill_id")
            val newCurrentLevel = currentLevel ?: existing.getInteger("current_level")
            val newAssessedDate = assessedDate ?: existing.getString("assessed_date") ?: ""
            val newAssessorId = assessorId ?: existing.getString("assessor_id") ?: ""
            val newExpireDate = expireDate ?: existing.getString("expire_date") ?: ""
            val newAssessmentRecord = assessmentRecord ?: existing.getJsonObject("assessment_record") ?: JsonObject()

            val sql = """
                UPDATE employee_skills
                SET skill_id = ${'$'}1, current_level = ${'$'}2, assessed_date = ${'$'}3, assessor_id = ${'$'}4, expire_date = ${'$'}5, assessment_record = ${'$'}6::jsonb
                WHERE id = ${'$'}7
                RETURNING id, employee_id, skill_id, current_level, assessed_date, assessor_id, expire_date, assessment_record
            """.trimIndent()
            val tuple = io.vertx.sqlclient.Tuple.of(newSkillId, newCurrentLevel.toShort(), newAssessedDate, newAssessorId, newExpireDate, newAssessmentRecord.encode(), id)
            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("employee skill not found"))
                    else Future.succeededFuture(employeeSkillToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteEmployeeSkill(id: String): Future<Void> {
        val sql = "DELETE FROM employee_skills WHERE id = ?"
        return pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(id))
            .map { null as Void? }
    }

    fun assessEmployeeSkill(id: String, assessmentRecord: JsonObject, currentLevel: Int): Future<JsonObject> {
        if (currentLevel < 1 || currentLevel > 4) {
            return Future.failedFuture(IllegalArgumentException("current_level must be between 1 and 4"))
        }
        return getEmployeeSkill(id).flatMap { existing: JsonObject ->
            val now = LocalDate.now().toString()
            val sql = """
                UPDATE employee_skills
                SET current_level = ${'$'}1, assessed_date = ${'$'}2, assessment_record = ${'$'}3::jsonb
                WHERE id = ${'$'}4
                RETURNING id, employee_id, skill_id, current_level, assessed_date, assessor_id, expire_date, assessment_record
            """.trimIndent()
            val tuple = io.vertx.sqlclient.Tuple.of(currentLevel.toShort(), now, assessmentRecord.encode(), id)
            pool.preparedQuery(sql)
                .execute(tuple)
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("employee skill not found"))
                    else Future.succeededFuture(employeeSkillToJson(rows.iterator().next()))
                }
        }
    }

    // ==========================================
    // Certificates
    // ==========================================

    fun listCertificates(limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(count().`as`("total")).from(c)
        val dataQuery = ctx.select(c.ID, c.NAME, c.VALIDITY_CONFIG, c.DESCRIPTION)
            .from(c)
            .orderBy(c.NAME.asc())
            .limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(certificateToJson(row))
                        }
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun createCertificate(
        name: String,
        validityConfig: JsonObject = JsonObject(),
        description: String = ""
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val query = ctx.insertInto(c)
            .columns(c.ID, c.NAME, c.VALIDITY_CONFIG, c.DESCRIPTION)
            .values(id, name, JSONB.valueOf(validityConfig.encode()), description)
            .returning(c.ID, c.NAME, c.VALIDITY_CONFIG, c.DESCRIPTION)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> certificateToJson(rows.iterator().next()) }
    }

    fun getCertificate(id: String): Future<JsonObject> {
        val query = ctx.select(c.ID, c.NAME, c.VALIDITY_CONFIG, c.DESCRIPTION)
            .from(c)
            .where(c.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("certificate not found"))
                else Future.succeededFuture(certificateToJson(rows.iterator().next()))
            }
    }

    fun updateCertificate(
        id: String,
        name: String? = null,
        validityConfig: JsonObject? = null,
        description: String? = null
    ): Future<JsonObject> {
        return getCertificate(id).flatMap { existing: JsonObject ->
            val newName = name ?: existing.getString("name")
            val newValidityConfig = validityConfig ?: existing.getJsonObject("validity_config") ?: JsonObject()
            val newDescription = description ?: existing.getString("description") ?: ""

            val query = ctx.update(c)
                .set(c.NAME, newName)
                .set(c.VALIDITY_CONFIG, JSONB.valueOf(newValidityConfig.encode()))
                .set(c.DESCRIPTION, newDescription)
                .where(c.ID.eq(id))
                .returning(c.ID, c.NAME, c.VALIDITY_CONFIG, c.DESCRIPTION)

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("certificate not found"))
                    else Future.succeededFuture(certificateToJson(rows.iterator().next()))
                }
        }
    }

    fun deleteCertificate(id: String): Future<Void> {
        val sql = "DELETE FROM certificates WHERE id = ?"
        return pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(id))
            .map { null as Void? }
    }

    // ==========================================
    // Employee Certificates
    // ==========================================

    fun listEmployeeCertificates(employeeId: String): Future<JsonObject> {
        val query = ctx.select(
                ec.ID, ec.EMPLOYEE_ID, ec.CERTIFICATE_ID,
                c.NAME.`as`("certificate_name"),
                ec.ISSUE_DATE, ec.EXPIRE_DATE, ec.ATTACHMENT, ec.EXTRA
            )
            .from(ec)
            .leftJoin(c).on(c.ID.eq(ec.CERTIFICATE_ID))
            .where(ec.EMPLOYEE_ID.eq(employeeId))
            .orderBy(c.NAME.asc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val records = JsonArray()
                for (row in rows) {
                    records.add(employeeCertificateToJson(row))
                }
                JsonObject().put("records", records)
            }
    }

    fun createEmployeeCertificate(
        employeeId: String,
        certificateId: String,
        issueDate: String? = null,
        expireDate: String? = null,
        attachment: String = "",
        extra: JsonObject = JsonObject()
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val sql = """
            INSERT INTO employee_certificates (id, employee_id, certificate_id, issue_date, expire_date, attachment, extra)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7::jsonb)
            RETURNING id, employee_id, certificate_id, issue_date, expire_date, attachment, extra
        """.trimIndent()
        val tuple = io.vertx.sqlclient.Tuple.of(id, employeeId, certificateId, issueDate ?: "", expireDate ?: "", attachment, extra.encode())
        return pool.preparedQuery(sql)
            .execute(tuple)
            .map { rows -> employeeCertificateToJson(rows.iterator().next()) }
            .recover { err ->
                val msg = err.message ?: ""
                if (msg.contains("duplicate key") || msg.contains("unique") || msg.contains("UNIQUE")) {
                    Future.failedFuture(IllegalArgumentException("employee already has this certificate"))
                } else {
                    Future.failedFuture(err)
                }
            }
    }

    fun deleteEmployeeCertificate(employeeId: String, certificateId: String): Future<Void> {
        val sql = "DELETE FROM employee_certificates WHERE employee_id = ? AND certificate_id = ?"
        return pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(employeeId, certificateId))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("employee certificate not found"))
                else Future.succeededFuture(null as Void?)
            }
    }

    // ==========================================
    // JSON Conversions
    // ==========================================

    companion object {
        fun positionToJson(row: Row): JsonObject {
            val children = JsonArray()
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("parent_id", row.getValue("parent_id")?.toString() ?: "")
                .put("skill_requirements", row.getValue("skill_requirements") as? JsonObject ?: JsonObject())
                .put("assessment_config", row.getValue("assessment_config") as? JsonObject ?: JsonObject())
                .put("extra", row.getValue("extra") as? JsonObject ?: JsonObject())
                .put("children", children)
        }

        fun skillToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("category", row.getValue("category")?.toString())
                .put("evaluation_criteria", row.getValue("evaluation_criteria") as? JsonObject ?: JsonObject())
                .put("default_validity", row.getValue("default_validity") as? JsonObject ?: JsonObject())
        }

        fun employeeSkillToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("employee_id", row.getValue("employee_id")?.toString())
                .put("skill_id", row.getValue("skill_id")?.toString())
                .put("skill_name", row.getValue("skill_name")?.toString() ?: "")
                .put("skill_category", row.getValue("skill_category")?.toString() ?: "")
                .put("current_level", row.getValue("current_level") as? Int ?: 1)
                .put("assessed_date", row.getValue("assessed_date")?.toString() ?: "")
                .put("assessor_id", row.getValue("assessor_id")?.toString() ?: "")
                .put("expire_date", row.getValue("expire_date")?.toString() ?: "")
                .put("assessment_record", row.getValue("assessment_record") as? JsonObject ?: JsonObject())
        }

        fun certificateToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("validity_config", row.getValue("validity_config") as? JsonObject ?: JsonObject())
                .put("description", row.getValue("description")?.toString() ?: "")
        }

        fun employeeCertificateToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("employee_id", row.getValue("employee_id")?.toString())
                .put("certificate_id", row.getValue("certificate_id")?.toString())
                .put("certificate_name", row.getValue("certificate_name")?.toString() ?: "")
                .put("issue_date", row.getValue("issue_date")?.toString() ?: "")
                .put("expire_date", row.getValue("expire_date")?.toString() ?: "")
                .put("attachment", row.getValue("attachment")?.toString() ?: "")
                .put("extra", row.getValue("extra") as? JsonObject ?: JsonObject())
        }
    }
}

class NotFoundException(message: String) : Exception(message)
