package com.ovaphlow.crate.analytics

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object AnalyticsRoutes {

    private val log = LoggerFactory.getLogger(AnalyticsRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = AnalyticsService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "analytics"))
        }

        // ==========================================
        // Training Summary
        // ==========================================

        router.get("/training/summary").handler { ctx ->
            service.getTrainingSummary()
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Skill Heatmap
        // ==========================================

        router.get("/skill-heatmap").handler { ctx ->
            val departmentId = ctx.request().getParam("department_id")
            service.getSkillHeatmap(departmentId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Quality Correlation
        // ==========================================

        router.get("/quality-correlation").handler { ctx ->
            val departmentId = ctx.request().getParam("department_id")
            service.getQualityCorrelation(departmentId)
                .onSuccess { ctx.json(it) }
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
        log.error("analytics route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
