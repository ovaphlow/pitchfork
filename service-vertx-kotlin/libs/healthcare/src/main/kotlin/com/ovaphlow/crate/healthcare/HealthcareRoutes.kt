package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.NotFoundException
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
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
        followupAuthHandler: Handler<RoutingContext>? = null,
        vitalSignAuthHandler: Handler<RoutingContext>? = null,
        chronicDiseaseAuthHandler: Handler<RoutingContext>? = null,
        checkupAuthHandler: Handler<RoutingContext>? = null,
        depositAuthHandler: Handler<RoutingContext>? = null,
        feeItemAuthHandler: Handler<RoutingContext>? = null,
        billAuthHandler: Handler<RoutingContext>? = null,
        paymentAuthHandler: Handler<RoutingContext>? = null,
    ): Router {
        val router = Router.router(vertx)
        val service = HealthcareService(pool)
        val chronicDiseaseService = ChronicDiseaseService(pool)
        val followupService = FollowupService(pool, chronicDiseaseService = chronicDiseaseService)
        val vitalSignService = VitalSignService(pool)
        val checkupService = CheckupService(pool)
        val depositService = DepositService(pool)
        val feeItemService = FeeItemService(pool)
        val billService = BillService(pool)
        val paymentService = PaymentService(pool)

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
        // 给药明细（只读）：本卡片的给药汇总与明细，按 task 关联不串其他入住
        router.get("/orders/:id/administrations").handler { ctx ->
            service.getOrderAdministrations(requiredId(ctx))
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

        // ========================================================================
        //  随访管理 (Followup) — 养老/福利院方向
        //  写路由的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        // ========================================================================
        if (followupAuthHandler != null) {
            router.post("/followup-plans").handler(followupAuthHandler)
            router.patch("/followup-plans/:id/status").handler(followupAuthHandler)
            router.post("/followup-records").handler(followupAuthHandler)
        }
        // 静态路径 stats 必须先于泛型 /followup-plans/:id
        router.get("/followup-plans/stats").handler { ctx ->
            followupService.getPlanStats()
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/followup-plans").handler { ctx ->
            followupService.listPlans(
                status = ctx.request().getParam("status"),
                followupType = ctx.request().getParam("followup_type"),
                patientId = ctx.request().getParam("patient_id"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
                overdue = ctx.request().getParam("overdue"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/followup-plans").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            followupService.createPlan(body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/followup-plans/:id").handler { ctx ->
            followupService.getPlan(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/followup-plans/:id/status").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            followupService.updatePlanStatus(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/followup-records").handler { ctx ->
            followupService.listRecords(
                patientId = ctx.request().getParam("patient_id"),
                encounterId = ctx.request().getParam("encounter_id"),
                followupType = ctx.request().getParam("followup_type"),
                result = ctx.request().getParam("result"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/followup-records").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            followupService.createRecord(body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/followup-records/:id").handler { ctx ->
            followupService.getRecord(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 老人随访历史时间线（详情页用）
        router.get("/patients/:id/followups").handler { ctx ->
            followupService.listPatientFollowups(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  慢病档案 (Chronic Disease Registrations) — 养老方向
        //  慢病登记 + 病程记录，与随访联动（登记/随访完成自动生成慢病随访计划）。
        //  写路由的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        // ========================================================================
        if (chronicDiseaseAuthHandler != null) {
            router.post("/chronic-diseases").handler(chronicDiseaseAuthHandler)
            router.patch("/chronic-diseases/:id/status").handler(chronicDiseaseAuthHandler)
        }
        router.post("/chronic-diseases").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            chronicDiseaseService.createRegistration(body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/chronic-diseases").handler { ctx ->
            chronicDiseaseService.listRegistrations(
                patientId = ctx.request().getParam("patient_id"),
                diseaseName = ctx.request().getParam("disease_name"),
                controlStatus = ctx.request().getParam("control_status"),
                status = ctx.request().getParam("status"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 档案时间线：静态多段路径位于泛型 /chronic-diseases/:id 之前
        router.get("/chronic-diseases/:id/timeline").handler { ctx ->
            chronicDiseaseService.getRegistrationTimeline(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/chronic-diseases/:id").handler { ctx ->
            chronicDiseaseService.getRegistration(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/chronic-diseases/:id/status").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            chronicDiseaseService.updateRegistrationStatus(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  生命体征 (Vital Signs) — 养老方向
        //  写路由（创建/修正/删除）的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        // ========================================================================
        if (vitalSignAuthHandler != null) {
            router.post("/vital-signs").handler(vitalSignAuthHandler)
            router.patch("/vital-signs/:id").handler(vitalSignAuthHandler)
            router.delete("/vital-signs/:id").handler(vitalSignAuthHandler)
            router.post("/vital-signs/:id/review").handler(vitalSignAuthHandler)
            router.post("/vital-signs/:id/refer").handler(vitalSignAuthHandler)
        }
        // 批量创建：请求体为 JSON 数组（血压一次提交收缩/舒张两条）
        router.post("/vital-signs").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            val body = try {
                ctx.body().asJsonArray()
            } catch (_: RuntimeException) {
                null
            }
            if (body == null) {
                respond(ctx, 400, "body must be a JSON array of vital sign records")
                return@handler
            }
            vitalSignService.createVitalSigns(body, userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/vital-signs").handler { ctx ->
            vitalSignService.listVitalSigns(
                patientId = ctx.request().getParam("patient_id"),
                type = ctx.request().getParam("type"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 异常告警：静态路径必须先于泛型 /vital-signs/:id
        router.get("/vital-signs/abnormal/summary").handler { ctx ->
            vitalSignService.getAbnormalSummary()
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/vital-signs/abnormal").handler { ctx ->
            vitalSignService.listAbnormalSigns(
                patientId = ctx.request().getParam("patient_id"),
                type = ctx.request().getParam("type"),
                reviewStatus = ctx.request().getParam("review_status"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/vital-signs/:id").handler { ctx ->
            vitalSignService.getVitalSign(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/vital-signs/:id").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            vitalSignService.updateVitalSign(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 复核/转诊：写操作走认证 handler（401 兑底），复核人/责任人取认证主体
        router.post("/vital-signs/:id/review").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            vitalSignService.reviewVitalSign(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.post("/vital-signs/:id/refer").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            vitalSignService.referVitalSign(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.delete("/vital-signs/:id").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            vitalSignService.deleteVitalSign(requiredId(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 快照/趋势：静态路径位于泛型路由之前（/patients/:id 为单段，无冲突）
        router.get("/patients/:id/vital-signs/snapshot").handler { ctx ->
            vitalSignService.getSnapshot(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/patients/:id/vital-signs/trend").handler { ctx ->
            vitalSignService.getTrend(
                patientId = requiredId(ctx),
                type = ctx.request().getParam("type"),
                dateFrom = ctx.request().getParam("date_from"),
                dateTo = ctx.request().getParam("date_to"),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  体检管理 (Health Checkup) — 医疗/养老/儿保共用
        //  批次（年度唯一）+ 参检名单快照 + 结果录入；异常项转体征/转随访。
        //  写路由的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        // ========================================================================
        if (checkupAuthHandler != null) {
            router.post("/health-checkups").handler(checkupAuthHandler)
            router.patch("/health-checkups/:id/status").handler(checkupAuthHandler)
            router.post("/health-checkups/:id/members").handler(checkupAuthHandler)
            router.post("/health-checkups/:id/results").handler(checkupAuthHandler)
            router.patch("/health-checkup-results/:id").handler(checkupAuthHandler)
            router.post("/health-checkup-results/:id/to-vital-sign").handler(checkupAuthHandler)
            router.post("/health-checkup-results/:id/to-followup").handler(checkupAuthHandler)
        }
        router.post("/health-checkups").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            checkupService.createCheckup(body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondCheckupCreateFailure(ctx, it) }
        }
        router.get("/health-checkups").handler { ctx ->
            checkupService.listCheckups(
                status = ctx.request().getParam("status"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/health-checkups/:id").handler { ctx ->
            checkupService.getCheckup(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.patch("/health-checkups/:id/status").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            checkupService.updateCheckupStatus(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 批次只读统计（应检/已检/完成率/异常汇总）
        router.get("/health-checkups/:id/stats").handler { ctx ->
            checkupService.getCheckupStats(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 名单补录（幂等跳过已存在成员）
        router.post("/health-checkups/:id/members").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            checkupService.addMembers(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/health-checkups/:id/members").handler { ctx ->
            checkupService.listMembers(
                id = requiredId(ctx),
                checked = ctx.request().getParam("checked"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 结果录入：单条对象或数组批量
        router.post("/health-checkups/:id/results").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            val raw = ctx.body().buffer()?.toString()?.trim() ?: ""
            val parsed: Any? = try {
                JsonObject(raw)
            } catch (_: RuntimeException) {
                try {
                    JsonArray(raw)
                } catch (_: RuntimeException) {
                    null
                }
            }
            if (parsed == null) {
                respond(ctx, 400, "body must be a JSON object or an array of results")
                return@handler
            }
            checkupService.createResults(requiredId(ctx), parsed, userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/health-checkups/:id/results").handler { ctx ->
            checkupService.listResults(
                checkupId = requiredId(ctx),
                abnormal = ctx.request().getParam("abnormal"),
                patientId = ctx.request().getParam("patient_id"),
                itemCategory = ctx.request().getParam("item_category"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        router.get("/health-checkup-results/:id").handler { ctx ->
            checkupService.getResult(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 修正结果（重算异常标记；不级联已生成的体征/随访）
        router.patch("/health-checkup-results/:id").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            checkupService.updateResult(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 异常转体征（幂等，重复 409）
        router.post("/health-checkup-results/:id/to-vital-sign").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            checkupService.toVitalSign(requiredId(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 异常转随访（幂等，重复 409；体 {followup_type, planned_date?, planned_way?, remark?}）
        router.post("/health-checkup-results/:id/to-followup").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            checkupService.toFollowup(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  押金登记与退押 (Deposit) — 养老费用管理独立子任务
        //  挂 encounter（不强制关联费用项目字典，结算收束不自动冲抵押金）。
        //  写路由的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        //  退押为独立操作：不校验 encounter 收束状态，离院/去世后仍可退押。
        // ========================================================================
        if (depositAuthHandler != null) {
            router.post("/encounters/:id/deposits").handler(depositAuthHandler)
            router.post("/encounters/:id/deposits/refunds").handler(depositAuthHandler)
        }
        // 登记押金：体 {amount, remark?, metadata?}；operator 取认证主体
        router.post("/encounters/:id/deposits").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            depositService.createDeposit(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 退押：累计退押不得超过当前余额；离院/去世后仍可退押
        router.post("/encounters/:id/deposits/refunds").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            depositService.createRefund(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 台账：{records, meta:{total, balance}}；空台账 records: [] 且 total: 0
        router.get("/encounters/:id/deposits").handler { ctx ->
            depositService.listDeposits(
                encounterId = requiredId(ctx),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  费用项目字典 (Fee Items) — 养老收费基础数据，为账单自动计费提供单价来源
        //  写路由的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        //  状态流转（启用/停用）通过 PATCH /:id/status 独立进行；
        //  账单明细为快照，字典改价/停用不影响已生成账单。
        // ========================================================================
        if (feeItemAuthHandler != null) {
            router.post("/fee-items").handler(feeItemAuthHandler)
            router.put("/fee-items/:id").handler(feeItemAuthHandler)
            router.delete("/fee-items/:id").handler(feeItemAuthHandler)
            router.patch("/fee-items/:id/status").handler(feeItemAuthHandler)
        }
        // 创建：体 {category, name, unit_price, remark?, metadata?}；状态默认 启用
        router.post("/fee-items").handler { ctx ->
            userId(ctx) ?: return@handler
            feeItemService.createItem(body(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 列表：{records, meta:{total}}；支持 category/status 过滤；空列表 records: [] 且 total: 0
        router.get("/fee-items").handler { ctx ->
            feeItemService.listItems(
                category = ctx.request().getParam("category"),
                status = ctx.request().getParam("status"),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 详情
        router.get("/fee-items/:id").handler { ctx ->
            feeItemService.getItem(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 更新：全量替换字典字段；状态只能走 PATCH /:id/status
        router.put("/fee-items/:id").handler { ctx ->
            userId(ctx) ?: return@handler
            feeItemService.updateItem(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 删除
        router.delete("/fee-items/:id").handler { ctx ->
            userId(ctx) ?: return@handler
            feeItemService.deleteItem(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 状态流转：体 {status: 启用|停用}；非法状态值 400
        router.patch("/fee-items/:id/status").handler { ctx ->
            userId(ctx) ?: return@handler
            feeItemService.updateItemStatus(requiredId(ctx), body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  账单生成与手工加项 (Bills) — 按月自动计费（床位/护理/伙食）+ 手工加项
        //  写路由的认证中间件由 App 编排层注入；未注入时业务处理器保持 401 兜底。
        //  同 encounter 同账期唯一，重复生成 409；停用字典项不可用于新账单/加项 400；
        //  结算收束冻结（encounters.settled_at 非空）后生成/加项/补结算 409。
        // ========================================================================
        if (billAuthHandler != null) {
            router.post("/encounters/:id/bills").handler(billAuthHandler)
            router.post("/bills/:id/items").handler(billAuthHandler)
            router.post("/encounters/:id/billing-settlement").handler(billAuthHandler)
        }
        // 生成账单：体 {month: "YYYY-MM"}；自动计费床位/护理/伙食并落明细快照；状态初始 待缴费
        router.post("/encounters/:id/bills").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            billService.generate(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 手工加项：体 {item_id, unit_price?, quantity?, remark?}；unit_price 可覆盖字典单价
        router.post("/bills/:id/items").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            billService.addItem(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 补结算（无请求体）：已离院/去世未结算 → 生成区间最终账单并冻结；
        // 已全部结算 409；未离院/去世 409；未认证 401。
        router.post("/encounters/:id/billing-settlement").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            service.settleEncounterBilling(requiredId(ctx))
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 账单详情（含明细快照）
        router.get("/bills/:id").handler { ctx ->
            billService.getBill(requiredId(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 账单列表：{records, meta:{total}}；账期倒序分页
        router.get("/encounters/:id/bills").handler { ctx ->
            billService.listBills(
                encounterId = requiredId(ctx),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ========================================================================
        //  缴费与欠费 (Payments) — 收费闭环收款环节
        //  多次部分缴费累加，余额递减；超缴 400 不写入；余额归零账单流转 已结清。
        //  欠费列表与汇总注册在 /payments/arrears、/payments/summary 独立路径，
        //  避免与账单卡的 /bills/:id 通配路由冲突；缴费流水按账单挂载。
        //  写路由与只读端点的认证中间件均由 App 编排层注入；
        //  未注入时业务处理器保持 401 兜底。
        // ========================================================================
        if (paymentAuthHandler != null) {
            router.post("/bills/:id/payments").handler(paymentAuthHandler)
            router.get("/bills/:id/payments").handler(paymentAuthHandler)
            router.get("/payments/arrears").handler(paymentAuthHandler)
            router.get("/payments/summary").handler(paymentAuthHandler)
        }
        // 缴费：体 {amount, method, remark?, metadata?}；operator 取认证主体；
        // 超缴 400 不写入；余额归零后账单状态 待缴费 → 已结清；
        // 结算收束冻结（encounters.settled_at 非空）后缴费 409。
        router.post("/bills/:id/payments").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            paymentService.createPayment(requiredId(ctx), body(ctx), userId)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 缴费流水（按账单）：{records, meta:{total}}；空流水 records: [] 且 total: 0
        router.get("/bills/:id/payments").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            paymentService.listPayments(
                billId = requiredId(ctx),
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 欠费列表：{records, meta:{total}}；空列表 records: [] 且 total: 0
        router.get("/payments/arrears").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            paymentService.listArrears(
                limit = limit(ctx),
                offset = offset(ctx),
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }
        // 汇总：{due_amount, paid_amount, arrears_amount}；无数据时三项均为 0
        router.get("/payments/summary").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            paymentService.summary()
                .onSuccess { ctx.json(it) }
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
            is DuplicateBillException -> respond(ctx, 409, error.message)
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

    private fun respondCheckupCreateFailure(ctx: RoutingContext, error: Throwable) {
        val message = error.message?.lowercase() ?: ""
        if (message.contains("uq_health_checkups_year")) {
            respond(ctx, 409, "health checkup for this year already exists")
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
