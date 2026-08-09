package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.NotFoundException
import io.vertx.core.Handler
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

    fun create(
        vertx: Vertx,
        pool: Pool,
        nurseCheckAuthHandler: Handler<RoutingContext>? = null,
        incidentHandoverAuthHandler: Handler<RoutingContext>? = null,
    ): Router {
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
        // 养老离院交接摘要：静态路径必须位于泛型 encounter 路由之前
        router.get("/elderly-admissions/:id/discharge-handover").handler { ctx ->
            service.getElderlyDischargeHandover(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/elderly-admissions/:id/discharge-handover").handler { ctx ->
            service.createElderlyDischargeHandover(requiredId(ctx), body(ctx))
                .onSuccess { (created, handover) ->
                    if (created) ctx.response().setStatusCode(201)
                    ctx.json(handover)
                }
                .onFailure { respondFailure(ctx, it) }
        }
        // 医嘱与去世路由：静态/具体路径必须位于泛型 encounter 路由之前
        router.post("/encounters/:id/orders").handler { ctx ->
            service.createOrder(requiredId(ctx), body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondCreateFailure(ctx, it) }
        }
        router.get("/encounters/:id/orders").handler { ctx ->
            service.listOrders(
                encounterId = requiredId(ctx),
                orderType = ctx.request().getParam("order_type"),
                status = ctx.request().getParam("status"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 护士核对汇总列表：跨入住待核对用药医嘱。静态路径必须先于泛型 /orders/:id
        router.get("/orders/pending-nurse-check").handler { ctx ->
            service.listPendingNurseCheckOrders(
                pool,
                encounterId = ctx.request().getParam("encounter_id"),
                search = ctx.request().getParam("search"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/orders/:id").handler { ctx ->
            service.getOrder(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/orders/:id/status").handler { ctx ->
            service.updateOrderStatus(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 护士核对用药医嘱：核对人取自认证中间件写入的 userId，缺失返回 401。
        // 认证中间件（IDP 会话校验）由 App 编排层注入；未注入时保持原有 401 兜底。
        if (nurseCheckAuthHandler != null) {
            router.patch("/orders/:id/nurse-check").handler(nurseCheckAuthHandler)
        }
        router.patch("/orders/:id/nurse-check").handler { ctx ->
            val userId = ctx.get<String>("userId")
            if (userId.isNullOrBlank()) {
                respond(ctx, 401, "authentication required")
                return@handler
            }
            service.nurseCheckOrder(requiredId(ctx), userId, body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/encounters/:id/death").handler { ctx ->
            service.deathEncounter(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 复评与照护计划修订：具体路由必须位于泛型 encounter 详情路由之前
        router.post("/encounters/:id/care-plan-revisions").handler { ctx ->
            service.createCarePlanRevision(requiredId(ctx), body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/encounters/:id/care-plan-revisions").handler { ctx ->
            service.listCarePlanRevisions(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/care-plan-revisions/:id").handler { ctx ->
            service.getCarePlanRevision(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 医生病程记录：具体路径必须位于泛型 encounter 详情路由之前
        router.post("/encounters/:id/progress-notes").handler { ctx ->
            service.createProgressNote(requiredId(ctx), body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/encounters/:id/progress-notes").handler { ctx ->
            service.listProgressNotes(
                encounterId = requiredId(ctx),
                noteType = ctx.request().getParam("note_type"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/progress-notes/:id").handler { ctx ->
            service.getProgressNote(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 诊断：具体路径必须位于泛型 encounter 详情路由之前
        router.post("/encounters/:id/diagnoses").handler { ctx ->
            service.createDiagnosis(requiredId(ctx), body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/encounters/:id/diagnoses").handler { ctx ->
            service.listDiagnoses(
                encounterId = requiredId(ctx),
                diagnosisType = ctx.request().getParam("diagnosis_type"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/diagnoses/:id").handler { ctx ->
            service.getDiagnosis(requiredId(ctx))
                .onSuccess { ctx.json(it) }
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

        // ========================================================================
        //  017 院内护理异常事件与班次交接
        //  认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        // ========================================================================
        if (incidentHandoverAuthHandler != null) {
            router.post("/encounters/:id/nursing-incidents").handler(incidentHandoverAuthHandler)
            router.get("/encounters/:id/nursing-incidents").handler(incidentHandoverAuthHandler)
            router.get("/encounters/:id/nursing-incidents/:incidentId").handler(incidentHandoverAuthHandler)
            router.post("/encounters/:id/nursing-incidents/:incidentId/actions").handler(incidentHandoverAuthHandler)
            router.post("/encounters/:id/nursing-incidents/:incidentId/close").handler(incidentHandoverAuthHandler)
            router.post("/nursing-shift-handovers").handler(incidentHandoverAuthHandler)
            router.get("/nursing-shift-handovers").handler(incidentHandoverAuthHandler)
            router.get("/nursing-shift-handovers/:id").handler(incidentHandoverAuthHandler)
            router.post("/nursing-shift-handovers/:id/receive").handler(incidentHandoverAuthHandler)
            router.post("/nursing-shift-handovers/:id/items").handler(incidentHandoverAuthHandler)
        }

        // 异常事件：创建（只接受 incident_type/severity/occurred_at/description/initial_action）
        router.post("/encounters/:id/nursing-incidents").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.createIncident(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/encounters/:id/nursing-incidents").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.listIncidents(
                encounterId = requiredId(ctx),
                status = ctx.request().getParam("status"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 事件详情/追加处置/关闭：作用域路径（encounter + incidentId），跨入住读写一律 404
        router.get("/encounters/:id/nursing-incidents/:incidentId").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.getIncident(requiredId(ctx), requiredPathParam(ctx, "incidentId"))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/encounters/:id/nursing-incidents/:incidentId/actions").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.addIncidentAction(requiredId(ctx), requiredPathParam(ctx, "incidentId"), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/encounters/:id/nursing-incidents/:incidentId/close").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.closeIncident(requiredId(ctx), requiredPathParam(ctx, "incidentId"), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // 班次交接：创建（Idempotency-Key 幂等）/列表/详情/接班/补充
        router.post("/nursing-shift-handovers").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.createShiftHandover(body(ctx), userId, ctx.request().getHeader("Idempotency-Key"))
                .onSuccess { (created, handover) ->
                    if (created) ctx.response().setStatusCode(201)
                    ctx.json(handover)
                }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/nursing-shift-handovers").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.listShiftHandovers(
                careUnit = ctx.request().getParam("care_unit"),
                businessDate = ctx.request().getParam("business_date"),
                shift = ctx.request().getParam("shift"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/nursing-shift-handovers/:id").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.getShiftHandover(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/nursing-shift-handovers/:id/receive").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.receiveShiftHandover(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/nursing-shift-handovers/:id/items").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.appendShiftHandoverItem(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        return router
    }

    private fun userId(ctx: RoutingContext): String? {
        val value = ctx.get<String>("userId")
        if (value.isNullOrBlank()) {
            respond(ctx, 401, "authentication required")
            return null
        }
        return value
    }

    private fun body(ctx: RoutingContext): JsonObject = ctx.body().asJsonObject() ?: JsonObject()

    private fun requiredId(ctx: RoutingContext): String =
        ctx.pathParam("id")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("id is required")

    private fun requiredPathParam(ctx: RoutingContext, name: String): String =
        ctx.pathParam(name)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$name is required")

    private fun limit(ctx: RoutingContext): Int =
        ctx.request().getParam("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50

    private fun offset(ctx: RoutingContext): Int =
        ctx.request().getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun respondFailure(ctx: RoutingContext, error: Throwable) {
        when (error) {
            is IllegalArgumentException -> respond(ctx, 400, error.message)
            is HealthcareNotFoundException -> respond(ctx, 404, error.message)
            is NotFoundException -> respond(ctx, 404, error.message)
            is ConflictException -> respond(ctx, 409, error.message)
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
