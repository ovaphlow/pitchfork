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

        // 用 /xxx*（非 /xxx/*）挂载：Vertx 的 /* 不匹配裸路径 /xxx，
        // 而前端按 REST 习惯调用 /inventories/v1/stocks 这类无尾斜杠路径。
        router.route("/materials*").subRouter(MaterialRoutes.create(vertx, mPool))
        router.route("/lots*").subRouter(LotRoutes.create(vertx, mPool))
        router.route("/stocks*").subRouter(StockRoutes.create(vertx, mPool))

        // ——— 手工确认入库（016 单一基础单位契约） ———
        // 每个明细提交 material_id、可选 lot_id、quantity（基础数量）与
        // unit_cost（每基础单位成本）；包装/拆零/换算/规格字段一律 400。
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

                val legacyField = item.fieldNames().firstOrNull {
                    it in setOf(
                        "unit_spec_id", "input_quantity", "input_unit_cost", "input_unit",
                        "conversion_ratio", "split_quantity", "unit", "base_quantity", "base_unit",
                    )
                }
                if (legacyField != null) {
                    respond(ctx, 400, "invalid item at index $i: unsupported field $legacyField")
                    return@handler
                }
                val quantity = requestDecimalText(item.getValue("quantity"))
                val unitCost = requestDecimalText(item.getValue("unit_cost"))
                if (quantity == null) {
                    respond(ctx, 400, "invalid item at index $i: quantity must be a decimal text")
                    return@handler
                }
                if (quantity <= BigDecimal.ZERO) {
                    respond(ctx, 400, "invalid item at index $i: quantity must be positive")
                    return@handler
                }
                if (unitCost == null || unitCost < BigDecimal.ZERO) {
                    respond(ctx, 400, "invalid item at index $i: unit_cost must be decimal text (>=0)")
                    return@handler
                }

                items.add(StockService.InboundItem(
                    materialId = materialId,
                    lotId = item.getString("lot_id"),
                    quantity = quantity,
                    unitCost = unitCost,
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
