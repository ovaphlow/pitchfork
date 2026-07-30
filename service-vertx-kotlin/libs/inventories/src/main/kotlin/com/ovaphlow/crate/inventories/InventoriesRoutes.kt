package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory
import java.math.BigDecimal

object InventoriesRoutes {

    private val log = LoggerFactory.getLogger(InventoriesRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "inventories"))
        }

        router.route("/materials/*").subRouter(MaterialRoutes.create(vertx, mPool))
        router.route("/lots/*").subRouter(LotRoutes.create(vertx, mPool))
        router.route("/stocks/*").subRouter(StockRoutes.create(vertx, mPool))

        // ——— 手工确认入库 ———
        router.post("/operations/inbound").handler { ctx ->
            val b = body(ctx)
            val warehouse = b.getString("warehouse")
            if (warehouse.isNullOrBlank()) {
                respond(ctx, 400, "warehouse is required")
                return@handler
            }

            val itemsJson = b.getJsonArray("items")
            if (itemsJson == null || itemsJson.size() == 0) {
                respond(ctx, 400, "at least one item is required")
                return@handler
            }

            val service = StockService(mPool)
            val items = mutableListOf<StockService.InboundItem>()
            for (i in 0 until itemsJson.size()) {
                val item = itemsJson.getJsonObject(i)
                val materialId = item.getString("material_id")
                val quantity = item.getDouble("quantity")
                val unitCost = item.getDouble("unit_cost")

                if (materialId.isNullOrBlank() || quantity == null || quantity <= 0 || unitCost == null || unitCost < 0) {
                    respond(ctx, 400, "invalid item at index $i: material_id, quantity (>0) and unit_cost (>=0) required")
                    return@handler
                }

                items.add(StockService.InboundItem(
                    materialId = materialId,
                    lotId = item.getString("lot_id"),
                    quantity = BigDecimal.valueOf(quantity),
                    unitCost = BigDecimal.valueOf(unitCost)
                ))
            }

            service.confirmInbound(StockService.InboundCommand(
                warehouse = warehouse,
                items = items,
                note = b.getString("note")
            )).onSuccess { ctx.json(it) }
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

    internal fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    internal fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("inventories route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
