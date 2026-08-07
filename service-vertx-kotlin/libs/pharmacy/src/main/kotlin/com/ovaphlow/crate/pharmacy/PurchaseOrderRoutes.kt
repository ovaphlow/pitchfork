package com.ovaphlow.crate.pharmacy

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object PurchaseOrderRoutes {

    /**
     * 014 采购订单与收货路由。所有写路由要求已认证 principal（authHandler 校验并把
     * `userId` 放入上下文）；操作人一律取服务端解析身份，不接受客户端自由文本。
     * 错误映射：400 请求体/格式，404 单据或明细不存在，409 状态/余量/幂等冲突。
     * 返回 (采购订单 router, 收货凭证 router)，由 PharmacyRoutes 分别挂载。
     */
    fun create(
        vertx: Vertx,
        pool: Pool,
        inventoryPort: InventoryPurchaseReceiptPort,
        authHandler: Handler<RoutingContext>,
    ): Pair<Router, Router> {
        val service = PurchaseOrderService(pool, inventoryPort)

        val orderRouter = Router.router(vertx)
        orderRouter.route().handler(BodyHandler.create())

        orderRouter.post("/").handler(authHandler).handler { ctx ->
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            val key = ctx.request().getHeader("Idempotency-Key")
            service.create(PharmacyRoutes.body(ctx), key, userId)
                .onSuccess { result ->
                    ctx.response().setStatusCode(if (result.replayed) 200 else 201)
                    ctx.json(result.order)
                }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.put("/:id").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.updateDraft(id, PharmacyRoutes.body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                warehouse = params.getParam("warehouse"),
                supplierName = params.getParam("supplier_name"),
                status = params.getParam("status"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0,
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.put("/:id/approve").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.approve(id, userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.put("/:id/cancel").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.cancel(id, PharmacyRoutes.body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.put("/:id/close").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            service.close(id, PharmacyRoutes.body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        orderRouter.post("/:id/receipts").handler(authHandler).handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            val userId = userIdOf(ctx)
            if (userId == null) {
                PharmacyRoutes.respond(ctx, 401, "authentication required"); return@handler
            }
            val key = ctx.request().getHeader("Idempotency-Key")
            service.receive(id, PharmacyRoutes.body(ctx), key, userId)
                .onSuccess { result ->
                    ctx.response().setStatusCode(if (result.replayed) 200 else 201)
                    ctx.json(result.payload)
                }
                .onFailure { respondFailure(ctx, it) }
        }

        val receiptRouter = Router.router(vertx)
        receiptRouter.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.getReceipt(id)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        return orderRouter to receiptRouter
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
