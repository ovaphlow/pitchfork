package com.ovaphlow.crate.onsite

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object OnsiteRoutes {

    private val log = LoggerFactory.getLogger(OnsiteRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = OnsiteService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "onsite"))
        }

        // ==========================================
        // Devices
        // ==========================================

        router.get("/devices").handler { ctx ->
            service.listDevices(
                search = ctx.request().getParam("search"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/devices").handler { ctx ->
            val b = body(ctx)
            val deviceId = b.getString("device_id", "")
            val code = b.getString("code", "")
            if (deviceId.isBlank() || code.isBlank()) {
                respond(ctx, 400, "device_id and code required"); return@handler
            }
            val linkedKnowledgeIds = b.getJsonArray("linked_knowledge_ids")?.map { it.toString() } ?: emptyList()
            val offlineCacheConfig = b.getJsonObject("offline_cache_config", JsonObject())
            service.createDevice(deviceId, code, linkedKnowledgeIds, offlineCacheConfig)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/devices/scan").handler { ctx ->
            val code = ctx.request().getParam("code")
            if (code.isNullOrBlank()) {
                respond(ctx, 400, "code query parameter required"); return@handler
            }
            service.scanDevice(code)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.get("/devices/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getDevice(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/devices/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val deviceId = b.getString("device_id")
            val code = b.getString("code")
            val linkedKnowledgeIds = b.getJsonArray("linked_knowledge_ids")?.map { it.toString() }
            val offlineCacheConfig = b.getJsonObject("offline_cache_config")
            service.updateDevice(id, deviceId, code, linkedKnowledgeIds, offlineCacheConfig)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.delete("/devices/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteDevice(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Cache Policies
        // ==========================================

        router.get("/cache-policies").handler { ctx ->
            service.listCachePolicies()
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/cache-policies").handler { ctx ->
            val b = body(ctx)
            val positionId = b.getString("position_id", "")
            if (positionId.isBlank()) {
                respond(ctx, 400, "position_id required"); return@handler
            }
            val cacheSizeLimitMb = b.getInteger("cache_size_limit_mb", 100)
            val includeKnowledgeTypes = b.getJsonArray("include_knowledge_types")?.map { it.toString() } ?: emptyList()
            val includeRecentDays = b.getInteger("include_recent_days", 30)
            val extra = b.getJsonObject("extra", JsonObject())
            service.createCachePolicy(positionId, cacheSizeLimitMb, includeKnowledgeTypes, includeRecentDays, extra)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/cache-policies/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getCachePolicy(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/cache-policies/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val positionId = b.getString("position_id")
            val cacheSizeLimitMb = b.getInteger("cache_size_limit_mb")
            val includeKnowledgeTypes = b.getJsonArray("include_knowledge_types")?.map { it.toString() }
            val includeRecentDays = b.getInteger("include_recent_days")
            val extra = b.getJsonObject("extra")
            service.updateCachePolicy(id, positionId, cacheSizeLimitMb, includeKnowledgeTypes, includeRecentDays, extra)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.delete("/cache-policies/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteCachePolicy(id)
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
        log.error("onsite route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
