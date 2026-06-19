package com.ovaphlow.crate.exam

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object ExamRoutes {

    private val log = LoggerFactory.getLogger(ExamRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = ExamService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "exam"))
        }

        // ==========================================
        // Questions
        // ==========================================

        router.get("/questions").handler { ctx ->
            service.listQuestions(
                type = ctx.request().getParam("type"),
                difficulty = ctx.request().getParam("difficulty")?.toIntOrNull(),
                tag = ctx.request().getParam("tag"),
                queryStr = ctx.request().getParam("query"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/questions").handler { ctx ->
            val b = body(ctx)
            val type = b.getString("type", "")
            val difficulty = b.getInteger("difficulty", 1)
            val tags = b.getJsonArray("tags", JsonArray())
            val content = b.getJsonObject("content", JsonObject())
            val options = b.getJsonObject("options", JsonObject())
            val answer = b.getJsonObject("answer", JsonObject())
            val explanation = b.getString("explanation", "")
            val createdBy = b.getString("created_by", "")

            if (type.isBlank()) {
                respond(ctx, 400, "type required"); return@handler
            }
            val validTypes = listOf("单选", "多选", "判断", "填空", "看图识错")
            if (type !in validTypes) {
                respond(ctx, 400, "type must be one of $validTypes"); return@handler
            }
            if (difficulty < 1 || difficulty > 5) {
                respond(ctx, 400, "difficulty must be between 1 and 5"); return@handler
            }

            service.createQuestion(type, difficulty, tags, content, options, answer, explanation, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/questions/import").handler { ctx ->
            val b = body(ctx)
            val questions = b.getJsonArray("questions", JsonArray())
            if (questions.size() == 0) {
                respond(ctx, 400, "questions array required"); return@handler
            }
            service.importQuestions(questions)
                .onSuccess { ctx.json(JsonObject().put("imported", it)) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/questions/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getQuestion(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/questions/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            service.updateQuestion(
                id = id,
                type = b.getString("type"),
                difficulty = b.getInteger("difficulty"),
                tags = b.getJsonArray("tags"),
                content = b.getJsonObject("content"),
                options = b.getJsonObject("options"),
                answer = b.getJsonObject("answer"),
                explanation = b.getString("explanation")
            ).onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/questions/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteQuestion(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        // ==========================================
        // Exam Papers
        // ==========================================

        router.get("/papers").handler { ctx ->
            service.listPapers(
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/papers").handler { ctx ->
            val b = body(ctx)
            val title = b.getString("title", "")
            val durationMinutes = b.getInteger("duration_minutes", 0)
            val passScore = b.getInteger("pass_score", 60)
            val generationStrategy = b.getJsonObject("generation_strategy", JsonObject())
            val antiCheatConfig = b.getJsonObject("anti_cheat_config", JsonObject())
            val extraRules = b.getJsonObject("extra_rules", JsonObject())
            val createdBy = b.getString("created_by", "")

            if (title.isBlank()) {
                respond(ctx, 400, "title required"); return@handler
            }
            if (durationMinutes <= 0) {
                respond(ctx, 400, "duration_minutes must be positive"); return@handler
            }

            service.createPaper(title, durationMinutes, passScore, generationStrategy, antiCheatConfig, extraRules, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/papers/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getPaper(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/papers/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            service.updatePaper(
                id = id,
                title = b.getString("title"),
                durationMinutes = b.getInteger("duration_minutes"),
                passScore = b.getInteger("pass_score"),
                generationStrategy = b.getJsonObject("generation_strategy"),
                antiCheatConfig = b.getJsonObject("anti_cheat_config"),
                extraRules = b.getJsonObject("extra_rules")
            ).onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/papers/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deletePaper(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.post("/papers/:id/generate").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.generatePaperQuestions(id)
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
        // Exam Records
        // ==========================================

        router.get("/records").handler { ctx ->
            service.listRecords(
                employeeId = ctx.request().getParam("employee_id"),
                paperId = ctx.request().getParam("paper_id"),
                passed = ctx.request().getParam("passed")?.toBooleanStrictOrNull(),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/records").handler { ctx ->
            val b = body(ctx)
            val employeeId = b.getString("employee_id", "")
            val paperId = b.getString("paper_id", "")
            if (employeeId.isBlank() || paperId.isBlank()) {
                respond(ctx, 400, "employee_id and paper_id required"); return@handler
            }
            service.startExam(employeeId, paperId)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/records/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getRecord(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.post("/records/:id/submit").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val answers = b.getJsonArray("answers", JsonArray())
            if (answers.size() == 0) {
                respond(ctx, 400, "answers array required"); return@handler
            }
            service.submitExam(id, answers)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/records/:id/result").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getExamResult(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
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
        log.error("exam route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
