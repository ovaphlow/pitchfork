package com.ovaphlow.crate.knowledge

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object KnowledgeRoutes {

    private val log = LoggerFactory.getLogger(KnowledgeRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = KnowledgeService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "knowledge"))
        }

        // ==========================================
        // Categories
        // ==========================================

        router.get("/categories").handler { ctx ->
            service.listCategories()
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.post("/categories").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            if (name.isBlank()) {
                respond(ctx, 400, "name required"); return@handler
            }
            val parentId = b.getString("parent_id", "")
            val sortOrder = b.getInteger("sort_order", 0)
            val description = b.getString("description", "")
            service.createCategory(name, parentId.ifBlank { null }, sortOrder, description.ifBlank { null })
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.put("/categories/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val name = b.getString("name")
            val parentId = b.getString("parent_id")
            val sortOrder = b.getInteger("sort_order")
            val description = b.getString("description")
            service.updateCategory(id, name, parentId, sortOrder, description)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/categories/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteCategory(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        // ==========================================
        // Entries
        // ==========================================

        router.get("/entries").handler { ctx ->
            val tagsParam = ctx.request().getParam("tags")
            val tags = tagsParam?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            service.listEntries(
                type = ctx.request().getParam("type"),
                status = ctx.request().getParam("status"),
                search = ctx.request().getParam("search"),
                categoryId = ctx.request().getParam("category_id"),
                tags = tags,
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.post("/entries").handler { ctx ->
            val b = body(ctx)
            val title = b.getString("title", "")
            val type = b.getString("type", "")
            if (title.isBlank() || type.isBlank()) {
                respond(ctx, 400, "title and type required"); return@handler
            }
            val categoryIds = b.getJsonArray("category_ids")?.map { it.toString() } ?: emptyList()
            val deviceIds = b.getJsonArray("device_ids")?.map { it.toString() } ?: emptyList()
            val positionIds = b.getJsonArray("position_ids")?.map { it.toString() } ?: emptyList()
            val tags = b.getJsonArray("tags")?.map { it.toString() } ?: emptyList()
            val extra = b.getJsonObject("extra", JsonObject())
            val createdBy = b.getString("created_by", "")
            service.createEntry(title, type, categoryIds, deviceIds, positionIds, tags, extra, createdBy, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/entries/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.getEntry(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.put("/entries/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val title = b.getString("title")
            val type = b.getString("type")
            val categoryIds = b.getJsonArray("category_ids")?.map { it.toString() }
            val deviceIds = b.getJsonArray("device_ids")?.map { it.toString() }
            val positionIds = b.getJsonArray("position_ids")?.map { it.toString() }
            val tags = b.getJsonArray("tags")?.map { it.toString() }
            val extra = b.getJsonObject("extra")
            val updatedBy = b.getString("updated_by", "")
            service.updateEntry(id, title, type, categoryIds, deviceIds, positionIds, tags, extra, updatedBy)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.patch("/entries/:id/status").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val status = b.getString("status", "")
            if (status.isBlank()) {
                respond(ctx, 400, "status required"); return@handler
            }
            service.updateEntryStatus(id, status)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.delete("/entries/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.deleteEntry(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ==========================================
        // Versions
        // ==========================================

        router.get("/entries/:id/versions").handler { ctx ->
            val entryId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "entry id required")
            service.listVersions(entryId)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.post("/entries/:id/versions").handler { ctx ->
            val entryId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "entry id required")
            val b = body(ctx)
            val content = b.getString("content", "")
            val contentBlocks = b.getJsonObject("content_blocks", JsonObject())
            val attachmentFiles = b.getJsonObject("attachment_files", JsonObject())
            val changeNote = b.getString("change_note", "")
            val versionNumber = b.getInteger("version_number")
            val createdBy = b.getString("created_by", "")
            service.createVersion(entryId, versionNumber, content, contentBlocks, attachmentFiles, changeNote, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.post("/entries/:id/versions/:vid/approve").handler { ctx ->
            val entryId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "entry id required")
            val versionId = ctx.pathParam("vid") ?: return@handler respond(ctx, 400, "version id required")
            val b = body(ctx)
            val approvedBy = b.getString("approved_by", "")
            service.approveVersion(entryId, versionId, approvedBy)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.post("/entries/:id/versions/:vid/reject").handler { ctx ->
            val entryId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "entry id required")
            val versionId = ctx.pathParam("vid") ?: return@handler respond(ctx, 400, "version id required")
            service.rejectVersion(entryId, versionId)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        // ==========================================
        // Feedbacks
        // ==========================================

        router.get("/entries/:id/feedbacks").handler { ctx ->
            val entryId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "entry id required")
            service.listFeedbacks(entryId)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
        }

        router.post("/entries/:id/feedbacks").handler { ctx ->
            val entryId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "entry id required")
            val b = body(ctx)
            val type = b.getString("type", "")
            val content = b.getString("content", "")
            if (type.isBlank() || content.isBlank()) {
                respond(ctx, 400, "type and content required"); return@handler
            }
            val createdBy = b.getString("created_by", "")
            service.createFeedback(entryId, type, content, createdBy)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> respond(ctx, 400, it.message)
                        is NotFoundException -> respond(ctx, 404, it.message)
                        else -> respondError(ctx, it)
                    }
                }
        }

        router.post("/feedbacks/:id/reply").handler { ctx ->
            val feedbackId = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "feedback id required")
            val b = body(ctx)
            val reply = b.getJsonObject("reply", JsonObject())
            service.replyFeedback(feedbackId, reply)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) respond(ctx, 404, it.message)
                    else respondError(ctx, it)
                }
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
        log.error("knowledge route error", err)
        ctx.response().setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", "internal error").encode())
    }
}
