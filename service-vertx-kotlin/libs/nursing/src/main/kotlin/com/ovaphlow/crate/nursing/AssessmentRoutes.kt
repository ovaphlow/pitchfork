package com.ovaphlow.crate.nursing

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object AssessmentRoutes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = AssessmentService(pool)

        router.route().handler(BodyHandler.create())

        router.post("/").handler { ctx ->
            val b = NursingRoutes.body(ctx)
            service.create(b)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> NursingRoutes.respond(ctx, 400, it.message)
                        else -> NursingRoutes.respondError(ctx, it)
                    }
                }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                encounterId = params.getParam("encounter_id"),
                periodId = params.getParam("period_id"),
                assessType = params.getParam("assess_type"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) NursingRoutes.respond(ctx, 404, it.message)
                    else NursingRoutes.respondError(ctx, it)
                }
        }

        router.put("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            val b = NursingRoutes.body(ctx)
            service.update(id, b)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) NursingRoutes.respond(ctx, 404, it.message)
                    else NursingRoutes.respondError(ctx, it)
                }
        }

        router.delete("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            service.delete(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }

        return router
    }
}
