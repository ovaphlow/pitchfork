package com.ovaphlow.crate.pharmacy

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object RequisitionRoutes {

    /**
     * 013 申领路由。所有写路由要求已认证 principal（authHandler 校验并把
     * `userId` 放入上下文）；操作人一律取服务端解析身份，不接受客户端自由文本。
     * 错误映射：400 请求体/格式，404 单据或明细不存在，409 业务冲突。
     */
    fun create(
        vertx: Vertx,
        pool: Pool,
        inventoryPort: InventoryRequisitionTransferPort,
        authHandler: Handler<RoutingContext>,
    ): Router {
        val router = Router.router(vertx)
        val service = RequisitionService(pool, inventoryPort)

        router.route().handler(BodyHandler.create())

        router.post("/").handler(authHandler).handler { ctx ->
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            val key = ctx.request().getHeader("Idempotency-Key")
            service.create(PharmacyRoutes.body(ctx), key, userId)
                .onSuccess { result ->
                    ctx.response().setStatusCode(if (result.replayed) 200 else 201)
                    ctx.json(result.requisition)
                }
                .onFailure { respondFailure(ctx, it) }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                warehouse = params.getParam("warehouse"),
                destinationWarehouse = params.getParam("destination_warehouse"),
                department = params.getParam("department"),
                status = params.getParam("status"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0,
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.put("/:id/approve").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.approve(id, PharmacyRoutes.body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.put("/:id/dispense").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.dispense(id, userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.put("/:id/cancel").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.cancel(id, PharmacyRoutes.body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        return router
    }

    private fun userIdOf(ctx: RoutingContext): String? = ctx.get<String>("userId")

    private fun respondFailure(ctx: RoutingContext, err: Throwable?) {
        when (err) {
            is NotFoundException -> PharmacyRoutes.respond(ctx, 404, err.message)
            is ConflictException -> PharmacyRoutes.respond(ctx, 409, err.message)
            is IllegalArgumentException -> PharmacyRoutes.respond(ctx, 400, err.message)
            else -> PharmacyRoutes.respondError(ctx, err)
        }
    }
}
