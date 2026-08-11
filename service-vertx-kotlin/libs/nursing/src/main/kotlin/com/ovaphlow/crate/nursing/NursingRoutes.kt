package com.ovaphlow.crate.nursing

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory


object NursingRoutes {

    private val log = LoggerFactory.getLogger(NursingRoutes::class.java)

    /**
     * @param authHandler 认证中间件（由 App 编排层注入，Aceso 为 IDP 会话校验，
     *   写入 ctx userId 作为操作人）：保护护理执行的两条写路由——记录给药与打卡状态更新。
     */
    fun create(
        vertx: Vertx,
        pool: Pool,
        authHandler: Handler<RoutingContext>? = null,
    ): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "nursing"))
        }

        // 时间线路由（静态段必须在 /:id 前注册）
        val timelineService = NursingTimelineService(mPool)
        router.get("/timeline").handler { ctx ->
            val params = ctx.request()
            val limit = params.getParam("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50
            val offset = params.getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            timelineService.listTimeline(
                periodId = params.getParam("period_id"),
                encounterId = params.getParam("encounter_id"),
                dateFrom = params.getParam("date_from"),
                dateTo = params.getParam("date_to"),
                eventType = params.getParam("event_type"),
                limit = limit,
                offset = offset
            ).onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.route("/periods/*").subRouter(ServicePeriodRoutes.create(vertx, mPool))
        router.route("/assessments/*").subRouter(AssessmentRoutes.create(vertx, mPool))
        router.route("/plans/*").subRouter(PlanRoutes.create(vertx, mPool))
        router.route("/tasks/*").subRouter(TaskRoutes.create(vertx, mPool))
        router.route("/executions/*").subRouter(TaskExecutionRoutes.create(vertx, mPool, authHandler))
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
