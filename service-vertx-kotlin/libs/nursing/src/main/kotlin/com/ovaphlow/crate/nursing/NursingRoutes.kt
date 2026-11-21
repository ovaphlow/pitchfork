package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.OperatorResolution
import com.ovaphlow.crate.common.resolveOperator
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
     * @param permissionHandler 权限中间件（由 App 编排层注入，Aceso 为角色权限码校验）：
     *   紧跟在 [authHandler] 之后挂在同样两条写路由上；未注入时行为与既有完全一致。
     * @param permissionHandlerForTaskType 045：按执行记录任务类型选权限中间件的工厂
     *   （见 `TaskExecutionRoutes.create`）；非 null 时打卡路由改用资源级选码。
     */
    fun create(
        vertx: Vertx,
        pool: Pool,
        authHandler: Handler<RoutingContext>? = null,
        permissionHandler: Handler<RoutingContext>? = null,
        permissionHandlerForTaskType: ((String?) -> Handler<RoutingContext>)? = null,
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
        router.route("/executions/*").subRouter(
            TaskExecutionRoutes.create(vertx, mPool, authHandler, permissionHandler, permissionHandlerForTaskType),
        )
        router.route("/visit-schedules/*").subRouter(VisitScheduleRoutes.create(vertx, mPool))

        return router
    }

    private fun limit(ctx: RoutingContext): Int =
        ctx.request().getParam("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50

    private fun offset(ctx: RoutingContext): Int =
        ctx.request().getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun requiredId(ctx: RoutingContext): String =
        ctx.pathParam("id")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("id is required")

    /**
     * 统一执行服务调用：同步抛出的参数校验异常也映射为 400/404，
     * 避免 Vert.x 把同步异常当作 500（与 dining 模块同范式）。
     */
    private fun handle(
        ctx: RoutingContext,
        action: () -> io.vertx.core.Future<JsonObject>,
        success: (JsonObject) -> Unit,
    ) {
        try {
            action().onSuccess(success).onFailure { respondFailure(ctx, it) }
        } catch (error: IllegalArgumentException) {
            respond(ctx, 400, error.message)
        } catch (error: NotFoundException) {
            respond(ctx, 404, error.message)
        } catch (error: Throwable) {
            respondError(ctx, error)
        }
    }

    private fun respondFailure(ctx: RoutingContext, error: Throwable?) {
        when (error) {
            is IllegalArgumentException -> respond(ctx, 400, error.message)
            is NotFoundException -> respond(ctx, 404, error.message)
            is ConflictException -> respond(ctx, 409, error.message)
            else -> respondError(ctx, error)
        }
    }

    internal fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    internal fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    /**
     * 042：解析本次请求的**有效操作人**并把"非管理员代操作"当场拒掉。
     *
     * 与药房 `PharmacyRoutes.withOperator` 完全同源（同一个 `resolveOperator` 纯策略）：
     * 权威来源是认证会话（`ctx["userId"]`），请求体里的字段只是"请求"；
     * 只有平台管理员（`ctx["platformAdmin"]`，来自 IDP `/session`）可以指定他人；
     * 非管理员提交他人 → `400 operator cannot be delegated`（fail-closed，**不静默改写**）；
     * 无认证挂载（嵌入式/集成测试路由）时回落请求体，保持既有测试语义（042 D4）。
     *
     * [block] 只在解析成功后执行，参数为有效操作人；`null` 表示既无会话也未提交，
     * 此时**不写该字段**（评估人/制定人/执行人都是可空列，与改动前"未提交"语义一致）。
     *
     * @param field 请求体里的操作人字段名：`assessor`（评估人）/ `created_by`（制定人）/ `executor`（执行人）
     */
    internal fun withOperator(
        ctx: RoutingContext,
        body: JsonObject,
        field: String,
        block: (String?) -> Unit,
    ) {
        when (
            val resolution = resolveOperator(
                actorId = ctx.get<String>("userId"),
                platformAdmin = ctx.get<Boolean>("platformAdmin") ?: false,
                requested = body.getString(field),
            )
        ) {
            is OperatorResolution.Resolved -> block(resolution.subjectId)
            is OperatorResolution.Delegated -> block(resolution.requested)
            OperatorResolution.Absent -> block(null)
            OperatorResolution.NotDelegatable -> respond(ctx, 400, "operator cannot be delegated")
        }
    }

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("nursing route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
