package com.ovaphlow.crate.pharmacy

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object PharmacyRoutes {

    private val log = LoggerFactory.getLogger(PharmacyRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "pharmacy"))
        }

        router.route("/dispenses/*").subRouter(DispenseRoutes.create(vertx, mPool))
        router.route("/returns/*").subRouter(ReturnRoutes.create(vertx, mPool))
        router.route("/requisitions/*").subRouter(RequisitionRoutes.create(vertx, mPool))

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
        log.error("pharmacy route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}

class NotFoundException(message: String) : Exception(message)