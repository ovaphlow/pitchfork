package com.ovaphlow.crate.nursing

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

class NotFoundException(message: String) : Exception(message)

object NursingRoutes {

    private val log = LoggerFactory.getLogger(NursingRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "nursing"))
        }

        router.route("/periods/*").subRouter(ServicePeriodRoutes.create(vertx, mPool))
        router.route("/assessments/*").subRouter(AssessmentRoutes.create(vertx, mPool))
        router.route("/plans/*").subRouter(PlanRoutes.create(vertx, mPool))
        router.route("/tasks/*").subRouter(TaskRoutes.create(vertx, mPool))
        router.route("/executions/*").subRouter(TaskExecutionRoutes.create(vertx, mPool))
        router.route("/visit-schedules/*").subRouter(VisitScheduleRoutes.create(vertx, mPool))

        return router
    }

    internal fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    internal fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("nursing route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
