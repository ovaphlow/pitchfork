package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object LotRoutes {

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = LotService(pool)

        router.route().handler(BodyHandler.create())

        router.post("/").handler { ctx ->
            val b = InventoriesRoutes.body(ctx)
            val missing = mutableListOf<String>()
            if (b.getString("material_id").isNullOrBlank()) missing.add("material_id")
            if (b.getString("batch_no").isNullOrBlank()) missing.add("batch_no")
            if (missing.isNotEmpty()) {
                InventoriesRoutes.respond(ctx, 400, "required: ${missing.joinToString(", ")}"); return@handler
            }
            service.create(b)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> InventoriesRoutes.respond(ctx, 404, it.message)
                        is IllegalArgumentException -> InventoriesRoutes.respond(ctx, 400, it.message)
                        else -> InventoriesRoutes.respondError(ctx, it)
                    }
                }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                materialId = params.getParam("material_id"),
                batchNo = params.getParam("batch_no"),
                expiryBefore = params.getParam("expiry_before"),
                expiryAfter = params.getParam("expiry_after"),
                manufacturer = params.getParam("manufacturer"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { InventoriesRoutes.respondError(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) InventoriesRoutes.respond(ctx, 404, it.message)
                    else InventoriesRoutes.respondError(ctx, it)
                }
        }

        return router
    }
}
