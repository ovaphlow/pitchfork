package com.ovaphlow.crate.pharmacy

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object ReturnRoutes {

    fun create(vertx: Vertx, pool: Pool, inventoryInboundPort: InventoryInboundPort): Router {
        val router = Router.router(vertx)
        val service = ReturnService(pool, inventoryInboundPort)

        router.route().handler(BodyHandler.create())

        router.post("/").handler { ctx ->
            PharmacyRoutes.respond(ctx, 400, "use /returns/from-dispense")
        }

        router.post("/from-dispense").handler { ctx ->
            service.createFromDispense(PharmacyRoutes.body(ctx))
                .onSuccess {
                    ctx.response().setStatusCode(201)
                    ctx.json(it)
                }
                .onFailure { respondFailure(ctx, it) }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                patientId = params.getParam("patient_id"),
                status = params.getParam("status"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { PharmacyRoutes.respondError(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    respondFailure(ctx, it)
                }
        }

        router.put("/:id/confirm").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.confirm(id, PharmacyRoutes.body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.put("/:id/cancel").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.cancel(id)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        return router
    }

    private fun respondFailure(ctx: io.vertx.ext.web.RoutingContext, error: Throwable) {
        when (error) {
            is NotFoundException -> PharmacyRoutes.respond(ctx, 404, error.message)
            is ConflictException -> PharmacyRoutes.respond(ctx, 409, error.message)
            is IllegalArgumentException -> PharmacyRoutes.respond(ctx, 400, error.message)
            else -> PharmacyRoutes.respondError(ctx, error)
        }
    }
}
