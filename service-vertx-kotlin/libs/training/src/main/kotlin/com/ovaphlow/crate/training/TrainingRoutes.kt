package com.ovaphlow.crate.training

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object TrainingRoutes {

    private val log = LoggerFactory.getLogger(TrainingRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = TrainingService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "training"))
        }

        // ==========================================
        // Courses
        // ==========================================

        router.get("/courses").handler { ctx ->
            service.listCourses(
                status = ctx.request().getParam("status"),
                type = ctx.request().getParam("type"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/courses").handler { ctx ->
            val b = body(ctx)
            val title = b.getString("title", "")
            val type = b.getString("type", "")
            if (title.isBlank() || type.isBlank()) {
                respond(ctx, 400, "title and type required"); return@handler
            }
            if (type != "线上" && type != "线下实操") {
                respond(ctx, 400, "type must be '线上' or '线下实操'"); return@handler
            }
            val coverImage = b.getString("cover_image", "")
            val targetPositions = b.getJsonArray("target_positions") ?: io.vertx.core.json.JsonArray()
            val completionRules = b.getJsonObject("completion_rules", JsonObject())
            val metadata = b.getJsonObject("metadata", JsonObject())
                .put("description", b.getString("description", ""))
                .put("difficulty", b.getString("difficulty", ""))
            val status = b.getString("status", "启用")
            val createdBy = b.getString("created_by", "")
            service.createCourse(title, type, coverImage, targetPositions, completionRules, metadata, status, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/courses/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getCourse(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/courses/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val title = b.getString("title")
            val type = b.getString("type")
            val coverImage = b.getString("cover_image")
            val targetPositions = b.getJsonArray("target_positions")
            val completionRules = b.getJsonObject("completion_rules")
            val metadata = b.getJsonObject("metadata", JsonObject())
                .put("description", b.getString("description", ""))
                .put("difficulty", b.getString("difficulty", ""))
            val status = b.getString("status")
            service.updateCourse(id, title, type, coverImage, targetPositions, completionRules, metadata, status)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/courses/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteCourse(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        // ==========================================
        // Chapters
        // ==========================================

        router.get("/courses/:courseId/chapters").handler { ctx ->
            val courseId = ctx.pathParam("courseId") ?: return@handler respond(ctx, 400, "courseId required")
            service.listChapters(courseId)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.post("/courses/:courseId/chapters").handler { ctx ->
            val courseId = ctx.pathParam("courseId") ?: return@handler respond(ctx, 400, "courseId required")
            val b = body(ctx)
            val title = b.getString("title", "")
            if (title.isBlank()) {
                respond(ctx, 400, "title required"); return@handler
            }
            val sortOrder = b.getInteger("sort_order", 0)
            val blocks = b.getJsonObject("blocks", JsonObject())
            val quizConfig = b.getJsonObject("quiz_config", JsonObject())
            service.createChapter(courseId, sortOrder, title, blocks, quizConfig)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/chapters/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getChapter(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/chapters/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val title = b.getString("title")
            val sortOrder = b.getInteger("sort_order")
            val blocks = b.getJsonObject("blocks")
            val quizConfig = b.getJsonObject("quiz_config")
            service.updateChapter(id, title, sortOrder, blocks, quizConfig)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/chapters/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteChapter(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        // ==========================================
        // Assignments
        // ==========================================

        router.post("/assignments").handler { ctx ->
            val b = body(ctx)
            val courseId = b.getString("course_id", "")
            val assignType = b.getString("assign_type", "")
            if (courseId.isBlank() || assignType.isBlank()) {
                respond(ctx, 400, "course_id and assign_type required"); return@handler
            }
            if (assignType != "手动指派" && assignType != "自动触发") {
                respond(ctx, 400, "assign_type must be '手动指派' or '自动触发'"); return@handler
            }
            val triggerRule = b.getJsonObject("trigger_rule", JsonObject())
            val deadline = b.getString("deadline", "")
            val targetType = b.getString("target_type", "")
            val targetIds = b.getJsonArray("target_ids") ?: io.vertx.core.json.JsonArray()
            val createdBy = b.getString("created_by", "")
            service.createAssignment(courseId, assignType, triggerRule, deadline, targetType, targetIds, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/assignments").handler { ctx ->
            service.listAssignments(
                courseId = ctx.request().getParam("course_id"),
                employeeId = ctx.request().getParam("employee_id"),
                targetType = ctx.request().getParam("target_type"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.delete("/assignments/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteAssignment(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        // ==========================================
        // Learning Progress
        // ==========================================

        router.get("/progress/:assignmentId/:employeeId").handler { ctx ->
            val assignmentId = ctx.pathParam("assignmentId") ?: return@handler respond(ctx, 400, "assignmentId required")
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            service.getProgress(assignmentId, employeeId)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.put("/progress/:assignmentId/:employeeId/:chapterId").handler { ctx ->
            val assignmentId = ctx.pathParam("assignmentId") ?: return@handler respond(ctx, 400, "assignmentId required")
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            val chapterId = ctx.pathParam("chapterId") ?: return@handler respond(ctx, 400, "chapterId required")
            val b = body(ctx)
            val progressPercent = b.getInteger("progress_percent")
            if (progressPercent == null || progressPercent < 0 || progressPercent > 100) {
                respond(ctx, 400, "progress_percent must be between 0 and 100"); return@handler
            }
            val detail = b.getJsonObject("detail", JsonObject())
            service.updateProgress(assignmentId, employeeId, chapterId, progressPercent, detail)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.post("/progress/:assignmentId/:employeeId/complete").handler { ctx ->
            val assignmentId = ctx.pathParam("assignmentId") ?: return@handler respond(ctx, 400, "assignmentId required")
            val employeeId = ctx.pathParam("employeeId") ?: return@handler respond(ctx, 400, "employeeId required")
            service.completeAllProgress(assignmentId, employeeId)
                .onSuccess { ctx.json(it) }
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
        log.error("training route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
