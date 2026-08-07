package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool

object MaterialRoutes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = MaterialService(pool)

        router.route().handler(BodyHandler.create())

        router.post("/").handler { ctx ->
            val b = InventoriesRoutes.body(ctx)
            val code = b.getString("code", "")
            val name = b.getString("name", "")
            val category = b.getString("category", "")
            val packageUnit = b.getString("package_unit", "")

            val missing = mutableListOf<String>()
            if (code.isBlank()) missing.add("code")
            if (name.isBlank()) missing.add("name")
            if (category.isBlank()) missing.add("category")
            if (packageUnit.isBlank()) missing.add("package_unit")
            if (missing.isNotEmpty()) {
                InventoriesRoutes.respond(ctx, 400, "required: ${missing.joinToString(", ")}"); return@handler
            }

            service.create(b)
                .onSuccess {
                    ctx.response().setStatusCode(201)
                    ctx.json(it)
                }
                .onFailure {
                    val msg = it.message?.lowercase() ?: ""
                    if (msg.contains("unique") || msg.contains("duplicate")) {
                        InventoriesRoutes.respond(ctx, 409, "code already exists")
                    } else {
                        InventoriesRoutes.respondError(ctx, it)
                    }
                }
        }

        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                code = params.getParam("code"),
                name = params.getParam("name"),
                category = params.getParam("category"),
                status = params.getParam("status"),
                enableBatchControl = params.getParam("enable_batch_control")?.toBooleanStrictOrNull(),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { InventoriesRoutes.respondError(ctx, it) }
        }

        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) InventoriesRoutes.respond(ctx, 404, it.message)
                    else InventoriesRoutes.respondError(ctx, it)
                }
        }

        router.put("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            val b = InventoriesRoutes.body(ctx)
            service.update(id, b)
                .onSuccess { ctx.json(it) }
                .onFailure { InventoriesRoutes.respondFailure(ctx, it) }
        }

        // ——— 计划 015：单位模型与包装规格生命周期 ———

        // GET /materials/:id/unit-specs：基础单位、精度、迁移状态与全部规格（只读）
        router.get("/:id/unit-specs").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.listUnitSpecs(id)
                .onSuccess { ctx.json(it) }
                .onFailure { InventoriesRoutes.respondFailure(ctx, it) }
        }

        // PUT /materials/:id/unit-model：仅在无计量事实时创建首次基础单位模型
        router.put("/:id/unit-model").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            val b = InventoriesRoutes.body(ctx)
            service.activateUnitModel(id, b)
                .onSuccess { ctx.json(it) }
                .onFailure { InventoriesRoutes.respondFailure(ctx, it) }
        }

        // POST /materials/:id/unit-specs：新增一条包装规格
        router.post("/:id/unit-specs").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            val b = InventoriesRoutes.body(ctx)
            service.createUnitSpec(id, b)
                .onSuccess {
                    ctx.response().setStatusCode(201)
                    ctx.json(it)
                }
                .onFailure { InventoriesRoutes.respondFailure(ctx, it) }
        }

        // POST /materials/:id/unit-specs/:specId/retire：停用包装规格（不可删除）
        router.post("/:id/unit-specs/:specId/retire").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            val specId = ctx.pathParam("specId") ?: return@handler InventoriesRoutes.respond(ctx, 400, "specId required")
            service.retireUnitSpec(id, specId)
                .onSuccess { ctx.json(it) }
                .onFailure { InventoriesRoutes.respondFailure(ctx, it) }
        }

        router.delete("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler InventoriesRoutes.respond(ctx, 400, "id required")
            service.delete(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    val msg = it.message?.lowercase() ?: ""
                    if (msg.contains("foreign")) {
                        InventoriesRoutes.respond(ctx, 409, it.message)
                    } else {
                        InventoriesRoutes.respondError(ctx, it)
                    }
                }
        }

        return router
    }
}
