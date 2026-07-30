package com.ovaphlow.crate.healthcare

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

class HealthcareNotFoundException(message: String) : Exception(message)
class DuplicateNursingRecordException(message: String) : Exception(message)

object HealthcareRoutes {
    private val log = LoggerFactory.getLogger(HealthcareRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = HealthcareService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "healthcare"))
        }

        router.post("/patients").handler { ctx ->
            service.createPatient(body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/patients").handler { ctx ->
            service.listPatients(
                name = ctx.request().getParam("name"),
                status = ctx.request().getParam("status"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/patients/:id").handler { ctx ->
            service.getPatient(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.put("/patients/:id").handler { ctx ->
            service.updatePatient(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.post("/encounters").handler { ctx ->
            service.createEncounter(body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondCreateFailure(ctx, it) }
        }
        router.get("/encounters").handler { ctx ->
            service.listEncounters(
                patientId = ctx.request().getParam("patient_id"),
                encounterType = ctx.request().getParam("encounter_type"),
                status = ctx.request().getParam("status"),
                limit = limit(ctx),
                offset = offset(ctx),
                search = ctx.request().getParam("search"),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/elderly-admissions").handler { ctx ->
            service.listEncounters(
                patientId = ctx.request().getParam("patient_id"),
                encounterType = "ELDERLY_CARE",
                status = ctx.request().getParam("status") ?: "ACTIVE",
                limit = limit(ctx),
                offset = offset(ctx),
                search = ctx.request().getParam("search"),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/encounters/:id").handler { ctx ->
            service.getEncounter(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.put("/encounters/:id").handler { ctx ->
            service.updateEncounter(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/encounters/:id/discharge").handler { ctx ->
            service.dischargeEncounter(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.post("/elderly-admissions").handler { ctx ->
            service.admitElderly(body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondCreateFailure(ctx, it) }
        }

        // ——— 护理记录 (NURSING_RECORD) ———
        router.post("/nursing-records").handler { ctx ->
            service.createNursingRecord(body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondCreateFailure(ctx, it) }
        }
        router.get("/nursing-records").handler { ctx ->
            val params = ctx.request()
            service.listNursingRecords(
                periodId = params.getParam("period_id"),
                encounterId = params.getParam("encounter_id"),
                dateFrom = params.getParam("date_from"),
                dateTo = params.getParam("date_to"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/nursing-records/:id").handler { ctx ->
            service.getNursingRecord(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/nursing-records/:id/corrections").handler { ctx ->
            service.createNursingRecordCorrection(requiredId(ctx), body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondCreateFailure(ctx, it) }
        }

        return router
    }

    private fun body(ctx: RoutingContext): JsonObject = ctx.body().asJsonObject() ?: JsonObject()

    private fun requiredId(ctx: RoutingContext): String =
        ctx.pathParam("id")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("id is required")

    private fun limit(ctx: RoutingContext): Int =
        ctx.request().getParam("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50

    private fun offset(ctx: RoutingContext): Int =
        ctx.request().getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun respondFailure(ctx: RoutingContext, error: Throwable) {
        when (error) {
            is IllegalArgumentException -> respond(ctx, 400, error.message)
            is HealthcareNotFoundException -> respond(ctx, 404, error.message)
            else -> {
                log.error("healthcare route error", error)
                respond(ctx, 500, "internal error")
            }
        }
    }

    private fun respondCreateFailure(ctx: RoutingContext, error: Throwable) {
        val message = error.message?.lowercase() ?: ""
        if (message.contains("encounter_no") || message.contains("uq_encounters_encounter_no")) {
            respond(ctx, 409, "encounter_no already exists")
        } else if (error is DuplicateNursingRecordException) {
            respond(ctx, 409, error.message ?: "nursing record already exists for task execution")
        } else {
            respondFailure(ctx, error)
        }
    }

    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }
}
