package com.ovaphlow.crate.aiassistant

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object AiAssistantRoutes {

    private val log = LoggerFactory.getLogger(AiAssistantRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = AiAssistantService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "ai-assistant"))
        }

        // ==========================================
        // QA
        // ==========================================

        router.post("/ask").handler { ctx ->
            val b = body(ctx)
            val userId = b.getString("user_id", "")
            val question = b.getString("question", "")
            if (userId.isBlank() || question.isBlank()) {
                respond(ctx, 400, "user_id and question required"); return@handler
            }
            service.askQuestion(userId, question)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/qa/:id/feedback").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val feedback = ctx.request().getParam("feedback")
            if (feedback.isNullOrBlank()) {
                respond(ctx, 400, "feedback query parameter required (有用|没用)"); return@handler
            }
            service.submitFeedback(id, feedback)
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
        // FAQ
        // ==========================================

        router.get("/faq").handler { ctx ->
            service.listFaq(
                search = ctx.request().getParam("search"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/faq").handler { ctx ->
            val b = body(ctx)
            val question = b.getString("question", "")
            val answer = b.getString("answer", "")
            if (question.isBlank() || answer.isBlank()) {
                respond(ctx, 400, "question and answer required"); return@handler
            }
            val tags = b.getJsonArray("tags")?.map { it.toString() } ?: emptyList()
            val enabled = b.getBoolean("enabled", true)
            val createdBy = b.getString("created_by", "")
            service.createFaq(question, answer, tags, enabled, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/faq/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getFaq(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/faq/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val question = b.getString("question")
            val answer = b.getString("answer")
            val tags = b.getJsonArray("tags")?.map { it.toString() }
            val enabled = b.getBoolean("enabled")
            service.updateFaq(id, question, answer, tags, enabled)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.delete("/faq/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteFaq(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Preventive Push Rules
        // ==========================================

        router.get("/push-rules").handler { ctx ->
            val enabledParam = ctx.request().getParam("enabled")
            val enabled = enabledParam?.toBooleanStrictOrNull()
            service.listPushRules(
                enabled = enabled,
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/push-rules").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            val triggerMetric = b.getString("trigger_metric", "")
            val threshold = b.getDouble("threshold", 0.0)
            if (name.isBlank() || triggerMetric.isBlank()) {
                respond(ctx, 400, "name and trigger_metric required"); return@handler
            }
            val targetPositions = b.getJsonArray("target_positions")?.map { it.toString() } ?: emptyList()
            val targetCourseId = b.getString("target_course_id")
            val enabled = b.getBoolean("enabled", true)
            val extra = b.getJsonObject("extra", JsonObject())
            service.createPushRule(name, triggerMetric, threshold, targetPositions, targetCourseId, enabled, extra)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/push-rules/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getPushRule(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/push-rules/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val name = b.getString("name")
            val triggerMetric = b.getString("trigger_metric")
            val threshold = b.getDouble("threshold")
            val targetPositions = b.getJsonArray("target_positions")?.map { it.toString() }
            val targetCourseId = b.getString("target_course_id")
            val enabled = b.getBoolean("enabled")
            val extra = b.getJsonObject("extra")
            service.updatePushRule(id, name, triggerMetric, threshold, targetPositions, targetCourseId, enabled, extra)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.delete("/push-rules/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deletePushRule(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
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
        log.error("ai-assistant route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
