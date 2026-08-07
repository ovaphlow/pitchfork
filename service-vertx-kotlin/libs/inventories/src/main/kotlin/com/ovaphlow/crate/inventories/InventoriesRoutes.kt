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

        // ——— 手工确认入库（计划 015 新契约） ———
        // 每个明细提交 material_id、可选 lot_id、unit_spec_id、input_quantity 与
        // input_unit_cost；旧 quantity/unit_cost 形式仅过渡映射当前默认包装规格
        // （StockService 记录弃用日志）。两种形式互斥，混合返回 400。
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
                if (materialId.isNullOrBlank()) {
                    respond(ctx, 400, "invalid item at index $i: material_id required")
                    return@handler
                }

                val unitSpecId = item.getString("unit_spec_id")
                val inputQuantity = jsonDecimal(item.getValue("input_quantity"))
                val quantity = jsonDecimal(item.getValue("quantity"))
                val unitCost = jsonDecimal(item.getValue("unit_cost")) ?: jsonDecimal(item.getValue("input_unit_cost"))
                val hasLegacyCost = item.containsKey("unit_cost")

                // 契约互斥与字段级校验
                val contractError = when {
                    unitSpecId != null && inputQuantity == null ->
                        "input_quantity required when unit_spec_id is provided"
                    unitSpecId == null && inputQuantity != null ->
                        "unit_spec_id required when input_quantity is provided"
                    unitSpecId != null && quantity != null ->
                        "must not mix unit_spec_id/input_quantity with quantity"
                    unitSpecId == null && inputQuantity == null && quantity == null ->
                        "quantity (legacy) or unit_spec_id+input_quantity (new contract) required"
                    unitSpecId != null && hasLegacyCost ->
                        "unit_cost is not supported with the new contract"
                    unitSpecId == null && item.containsKey("input_unit_cost") ->
                        "input_unit_cost requires unit_spec_id"
                    else -> null
                }
                if (contractError != null) {
                    respond(ctx, 400, "invalid item at index $i: $contractError")
                    return@handler
                }
                if (quantity != null && quantity <= BigDecimal.ZERO) {
                    respond(ctx, 400, "invalid item at index $i: quantity must be positive")
                    return@handler
                }
                if (inputQuantity != null && inputQuantity <= BigDecimal.ZERO) {
                    respond(ctx, 400, "invalid item at index $i: input_quantity must be positive")
                    return@handler
                }
                if (unitCost == null || unitCost < BigDecimal.ZERO) {
                    respond(ctx, 400, "invalid item at index $i: input_unit_cost (>=0) required")
                    return@handler
                }

                items.add(StockService.InboundItem(
                    materialId = materialId,
                    lotId = item.getString("lot_id"),
                    unitSpecId = unitSpecId,
                    inputQuantity = inputQuantity,
                    quantity = quantity,
                    unitCost = unitCost
                ))
            }

            service.confirmInbound(StockService.InboundCommand(
                warehouse = warehouse,
                items = items,
                note = b.getString("note")
            )).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
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

    /** 计划 015 统一错误映射：400 参数/精度、404 不存在、409 状态/不可变/库存不足 */
    internal fun respondFailure(ctx: RoutingContext, err: Throwable?) {
        when (err) {
            is IllegalArgumentException -> respond(ctx, 400, err.message)
            is NotFoundException -> respond(ctx, 404, err.message)
            is ConflictException -> respond(ctx, 409, err.message)
            else -> respondError(ctx, err)
        }
    }

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("inventories route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
