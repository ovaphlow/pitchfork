package com.ovaphlow.crate.nursing

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object ServicePeriodRoutes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = ServicePeriodService(pool)

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
                patientId = params.getParam("patient_id"),
                serviceType = params.getParam("service_type"),
                status = params.getParam("status"),
                encounterId = params.getParam("encounter_id"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }

        // 养老入住补建周期（静态段必须在 /:id 动态路由前注册）
        router.post("/elderly-admission").handler { ctx ->
            val b = NursingRoutes.body(ctx)
            val encounterId = b.getString("encounter_id")
            if (encounterId.isNullOrBlank()) {
                NursingRoutes.respond(ctx, 400, "encounter_id is required")
                return@handler
            }
            service.enrollElderlyAdmission(encounterId)
                .onSuccess { (created, period) ->
                    ctx.response().setStatusCode(if (created) 201 else 200)
                    ctx.json(period)
                }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> NursingRoutes.respond(ctx, 400, it.message)
                        is ConflictException -> NursingRoutes.respond(ctx, 409, it.message)
                        else -> NursingRoutes.respondError(ctx, it)
                    }
                }
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

        router.patch("/:id/status").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            val b = NursingRoutes.body(ctx)
            val status = b.getString("status", "")
            if (status.isBlank()) { NursingRoutes.respond(ctx, 400, "status required"); return@handler }
            service.updateStatus(id, status)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> NursingRoutes.respond(ctx, 404, it.message)
                        is IllegalArgumentException -> NursingRoutes.respond(ctx, 400, it.message)
                        else -> NursingRoutes.respondError(ctx, it)
                    }
                }
        }

        return router
    }
}
