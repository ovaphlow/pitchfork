package com.ovaphlow.crate.pharmacy

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object ReturnRoutes {

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = ReturnService(pool)

        router.route().handler(BodyHandler.create())

        router.post("/").handler { ctx ->
            val b = PharmacyRoutes.body(ctx)
            service.create(b)
                .onSuccess {
                    ctx.response().setStatusCode(201)
                    ctx.json(it)
                }
                .onFailure {
                    val msg = it.message?.lowercase() ?: ""
                    when {
                        it is IllegalArgumentException -> PharmacyRoutes.respond(ctx, 400, it.message)
                        msg.contains("unique") || msg.contains("duplicate") ->
                            PharmacyRoutes.respond(ctx, 409, it.message)
                        else -> PharmacyRoutes.respondError(ctx, it)
                    }
                }
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
                    if (it is NotFoundException) PharmacyRoutes.respond(ctx, 404, it.message)
                    else PharmacyRoutes.respondError(ctx, it)
                }
        }

        router.put("/:id/confirm").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.confirm(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> PharmacyRoutes.respond(ctx, 404, it.message)
                        is IllegalArgumentException -> PharmacyRoutes.respond(ctx, 400, it.message)
                        else -> PharmacyRoutes.respondError(ctx, it)
                    }
                }
        }

        router.put("/:id/cancel").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.cancel(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> PharmacyRoutes.respond(ctx, 404, it.message)
                        is IllegalArgumentException -> PharmacyRoutes.respond(ctx, 400, it.message)
                        else -> PharmacyRoutes.respondError(ctx, it)
                    }
                }
        }

        return router
    }
}