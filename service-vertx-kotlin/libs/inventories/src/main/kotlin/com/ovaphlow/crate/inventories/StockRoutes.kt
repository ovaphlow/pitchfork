package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object StockRoutes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = StockService(pool)

        router.route().handler(BodyHandler.create())

        // ——— 可用库存查询 ———
        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.listAvailableStocks(
                warehouse = params.getParam("warehouse"),
                materialId = params.getParam("material_id"),
                search = params.getParam("search"),
                limit = params.getParam("limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 50,
                offset = params.getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { InventoriesRoutes.respondError(ctx, it) }
        }

        // ——— 可用护理站 ———
        router.get("/warehouses").handler { ctx ->
            service.listAvailableWarehouses()
                .onSuccess { warehouses ->
                    ctx.json(JsonArray(warehouses))
                }
                .onFailure { InventoriesRoutes.respondError(ctx, it) }
        }

        return router
    }
}
