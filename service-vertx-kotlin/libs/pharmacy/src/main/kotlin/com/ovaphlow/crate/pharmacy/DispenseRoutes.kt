package com.ovaphlow.crate.pharmacy

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object DispenseRoutes {

    fun create(
        vertx: Vertx,
        pool: Pool,
        medicalOrderReader: MedicalOrderReader,
        inventoryOutboundPort: InventoryOutboundPort,
        drugCatalogPort: DrugCatalogPort? = null,
    ): Router {
        val router = Router.router(vertx)
        val service = DispenseService(pool, medicalOrderReader, inventoryOutboundPort, drugCatalogPort)

        router.route().handler(BodyHandler.create())

        // ─── 4.1 待接方用药医嘱 ──────────────────────────────────────────────
        router.get("/medication-orders").handler { ctx ->
            val params = ctx.request()
            service.listMedicationOrders(
                encounterId = params.getParam("encounter_id"),
                search = params.getParam("search"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0,
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ─── 4.2 从医嘱创建发药单 ───────────────────────────────────────────
        // 025 补绑路径需要可信操作人：取服务端解析的认证身份（ctx["userId"]），
        // 不接受客户端自由文本；无身份且医嘱未绑定时由服务返回 401。
        router.post("/from-medical-order").handler { ctx ->
            service.createFromMedicalOrder(PharmacyRoutes.body(ctx), ctx.get<String>("userId"))
                .onSuccess { result ->
                    ctx.response().setStatusCode(201)
                    ctx.json(result)
                }
                .onFailure { respondFailure(ctx, it) }
        }

        // ─── 4.3 审方、开始调配、发药确认和取消 ────────────────────────────
        router.post("/:id/review").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.review(id, PharmacyRoutes.body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.post("/:id/start").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.start(id, PharmacyRoutes.body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.post("/:id/confirm").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.confirm(id, PharmacyRoutes.body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.post("/:id/cancel").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.cancel(id, PharmacyRoutes.body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        // ─── 4.4 只读查询与兼容路由 ────────────────────────────────────────
        router.post("/").handler { ctx ->
            service.create(PharmacyRoutes.body(ctx))
                .onSuccess { result ->
                    ctx.response().setStatusCode(201)
                    ctx.json(result)
                }
                .onFailure { respondFailure(ctx, it) }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                patientId = params.getParam("patient_id"),
                encounterId = params.getParam("encounter_id"),
                dispenseType = params.getParam("dispense_type"),
                status = params.getParam("status"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0,
            ).onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        router.put("/:id/status").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler PharmacyRoutes.respond(ctx, 400, "id required")
            service.updateStatus(id, PharmacyRoutes.body(ctx))
                .onSuccess { ctx.json(it) }
                .onFailure { respondFailure(ctx, it) }
        }

        return router
    }

    internal fun respondFailure(ctx: io.vertx.ext.web.RoutingContext, err: Throwable?) {
        when (err) {
            is NotFoundException -> PharmacyRoutes.respond(ctx, 404, err.message)
            is ConflictException -> PharmacyRoutes.respond(ctx, 409, err.message)
            is IllegalArgumentException -> PharmacyRoutes.respond(ctx, 400, err.message)
            // 025 补绑路径缺少可信身份：与 Requisition/PurchaseOrder 的 401 形状一致
            is UnauthorizedException -> PharmacyRoutes.respond(ctx, 401, err.message)
            // 025 药品目录端口未接线：fail-closed，不降级为跳过校验/补绑
            is DrugCatalogUnavailableException -> PharmacyRoutes.respond(ctx, 503, err.message)
            else -> {
                val msg = err?.message?.lowercase() ?: ""
                if (msg.contains("unique") || msg.contains("duplicate")) {
                    PharmacyRoutes.respond(ctx, 409, err?.message)
                } else {
                    PharmacyRoutes.respondError(ctx, err)
                }
            }
        }
    }
}
