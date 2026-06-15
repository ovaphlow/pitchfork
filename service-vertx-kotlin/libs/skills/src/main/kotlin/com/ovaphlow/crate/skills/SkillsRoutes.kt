package com.ovaphlow.crate.skills

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object SkillsRoutes {

    private val log = LoggerFactory.getLogger(SkillsRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = SkillsService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "skills"))
        }

        // ==========================================
        // Positions
        // ==========================================

        router.get("/positions").handler { ctx ->
            service.listPositions(
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/positions").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            if (name.isBlank()) {
                respond(ctx, 400, "name required"); return@handler
            }
            val parentId = b.getString("parent_id", "").ifBlank { null }
            val skillRequirements = b.getValue("skill_requirements")?.let {
                when (it) {
                    is JsonArray -> JsonObject().put("records", it)
                    is JsonObject -> it
                    else -> JsonObject()
                }
            } ?: JsonObject()
            val assessmentConfig = b.getJsonObject("assessment_config", JsonObject())
            val extra = b.getJsonObject("extra", JsonObject())
            service.createPosition(name, parentId, skillRequirements, assessmentConfig, extra)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/positions/tree").handler { ctx ->
            service.getPositionTree()
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/positions/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getPosition(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/positions/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val name = b.getString("name")
            val parentId = b.getString("parent_id")
            val skillRequirements = b.getJsonObject("skill_requirements")
            val assessmentConfig = b.getJsonObject("assessment_config")
            val extra = b.getJsonObject("extra")
            service.updatePosition(id, name, parentId, skillRequirements, assessmentConfig, extra)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/positions/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deletePosition(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Skills
        // ==========================================

        router.get("/skills").handler { ctx ->
            service.listSkills(
                category = ctx.request().getParam("category"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/skills").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            val category = b.getString("category", "")
            if (name.isBlank() || category.isBlank()) {
                respond(ctx, 400, "name and category required"); return@handler
            }
            val evaluationCriteria = b.getJsonObject("evaluation_criteria", JsonObject())
            val defaultValidity = b.getJsonObject("default_validity", JsonObject())
            service.createSkill(name, category, evaluationCriteria, defaultValidity)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/skills/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getSkill(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/skills/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val name = b.getString("name")
            val category = b.getString("category")
            val evaluationCriteria = b.getJsonObject("evaluation_criteria")
            val defaultValidity = b.getJsonObject("default_validity")
            service.updateSkill(id, name, category, evaluationCriteria, defaultValidity)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/skills/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteSkill(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Employee Skills
        // ==========================================

        router.get("/employee-skills/:employeeId").handler { ctx ->
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            service.listEmployeeSkills(employeeId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/employee-skills/:employeeId").handler { ctx ->
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            val b = body(ctx)
            val skillId = b.getString("skill_id", "")
            if (skillId.isBlank()) {
                respond(ctx, 400, "skill_id required"); return@handler
            }
            val currentLevel = b.getInteger("current_level", 1)
            val assessedDate = b.getString("assessed_date", "")
            val assessorId = b.getString("assessor_id", "")
            val expireDate = b.getString("expire_date", "")
            val assessmentRecord = b.getJsonObject("assessment_record", JsonObject())
            service.createEmployeeSkill(
                employeeId, skillId, currentLevel,
                assessedDate.ifBlank { null },
                assessorId.ifBlank { null },
                expireDate.ifBlank { null },
                assessmentRecord
            ).onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/employee-skills/:id").handler { ctx ->
            // Note: :id here refers to the employee_skill record id, not employeeId
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getEmployeeSkill(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/employee-skills/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val skillId = b.getString("skill_id")
            val currentLevel = b.getInteger("current_level")
            val assessedDate = b.getString("assessed_date")
            val assessorId = b.getString("assessor_id")
            val expireDate = b.getString("expire_date")
            val assessmentRecord = b.getJsonObject("assessment_record")
            service.updateEmployeeSkill(id, skillId, currentLevel, assessedDate, assessorId, expireDate, assessmentRecord)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/employee-skills/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteEmployeeSkill(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/employee-skills/:id/assess").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val currentLevel = b.getInteger("current_level")
            if (currentLevel == null) {
                respond(ctx, 400, "current_level required"); return@handler
            }
            val assessmentRecord = b.getJsonObject("assessment_record", JsonObject())
            service.assessEmployeeSkill(id, assessmentRecord, currentLevel)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        // ==========================================
        // Certificates
        // ==========================================

        router.get("/certificates").handler { ctx ->
            service.listCertificates(
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/certificates").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            if (name.isBlank()) {
                respond(ctx, 400, "name required"); return@handler
            }
            val validityConfig = b.getJsonObject("validity_config", JsonObject())
            val description = b.getString("description", "")
            service.createCertificate(name, validityConfig, description)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/certificates/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getCertificate(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/certificates/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val name = b.getString("name")
            val validityConfig = b.getJsonObject("validity_config")
            val description = b.getString("description")
            service.updateCertificate(id, name, validityConfig, description)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/certificates/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteCertificate(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Employee Certificates
        // ==========================================

        router.get("/employee-certificates/:employeeId").handler { ctx ->
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            service.listEmployeeCertificates(employeeId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/employee-certificates/:employeeId").handler { ctx ->
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            val b = body(ctx)
            val certificateId = b.getString("certificate_id", "")
            if (certificateId.isBlank()) {
                respond(ctx, 400, "certificate_id required"); return@handler
            }
            val issueDate = b.getString("issue_date", "")
            val expireDate = b.getString("expire_date", "")
            val attachment = b.getString("attachment", "")
            val extra = b.getJsonObject("extra", JsonObject())
            service.createEmployeeCertificate(
                employeeId, certificateId,
                issueDate.ifBlank { null },
                expireDate.ifBlank { null },
                attachment, extra
            ).onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/employee-certificates/:employeeId").handler { ctx ->
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            val certificateId = ctx.request().getParam("certificate_id")
            if (certificateId.isNullOrBlank()) {
                respond(ctx, 400, "certificate_id query parameter required"); return@handler
            }
            service.deleteEmployeeCertificate(employeeId, certificateId)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        return router
    }

    private fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    private fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("skills route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
