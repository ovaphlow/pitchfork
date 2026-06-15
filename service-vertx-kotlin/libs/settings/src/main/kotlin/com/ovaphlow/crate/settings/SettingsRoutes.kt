package com.ovaphlow.crate.settings

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object SettingsRoutes {

    private val log = LoggerFactory.getLogger(SettingsRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = SettingsService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "settings"))
        }

        // ---- Departments ----

        router.get("/departments").handler { ctx ->
            service.listDepartments()
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/departments").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            val code = b.getString("code", "")
            val parentCode = b.getString("parent_code", "")
            val description = b.getString("description", "")
            if (name.isBlank() || code.isBlank()) {
                respond(ctx, 400, "name and code required"); return@handler
            }
            service.createDepartment(name, code, parentCode, description)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is IllegalArgumentException) respond(ctx, 400, it.message) else respondError(ctx, it) }
        }

        router.put("/departments/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            service.updateDepartment(id, b.getString("name"), b.getString("code"), b.getString("parent_code"), b.getString("description"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.delete("/departments/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteDepartment(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ---- Knowledge Categories ----

        router.get("/knowledge-categories").handler { ctx ->
            service.listByCategory("knowledge-category")
                .onSuccess { ctx.json(JsonObject().put("records", it)) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/knowledge-categories").handler { ctx ->
            val b = body(ctx)
            val code = b.getString("code", "")
            val name = b.getString("name", "")
            if (code.isBlank() || name.isBlank()) {
                respond(ctx, 400, "code and name required"); return@handler
            }
            service.createSetting("knowledge-category", code, name)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is IllegalArgumentException) respond(ctx, 400, it.message) else respondError(ctx, it) }
        }

        router.put("/knowledge-categories/:code").handler { ctx ->
            val code = ctx.pathParam("code") ?: return@handler respond(ctx, 400, "code required")
            val b = body(ctx)
            val name = b.getString("name", "")
            if (name.isBlank()) { respond(ctx, 400, "name required"); return@handler }
            service.updateSetting("knowledge-category", code, name)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.delete("/knowledge-categories/:code").handler { ctx ->
            val code = ctx.pathParam("code") ?: return@handler respond(ctx, 400, "code required")
            service.deleteSetting("knowledge-category", code)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ---- Knowledge Tags ----

        router.get("/knowledge-tags").handler { ctx ->
            service.listByCategory("knowledge-tag")
                .onSuccess { ctx.json(JsonObject().put("records", it)) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/knowledge-tags").handler { ctx ->
            val b = body(ctx)
            val code = b.getString("code", "")
            val name = b.getString("name", "")
            if (code.isBlank() || name.isBlank()) {
                respond(ctx, 400, "code and name required"); return@handler
            }
            service.createSetting("knowledge-tag", code, name)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is IllegalArgumentException) respond(ctx, 400, it.message) else respondError(ctx, it) }
        }

        router.delete("/knowledge-tags/:code").handler { ctx ->
            val code = ctx.pathParam("code") ?: return@handler respond(ctx, 400, "code required")
            service.deleteSetting("knowledge-tag", code)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        return router
    }

    private fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    private fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("settings route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
