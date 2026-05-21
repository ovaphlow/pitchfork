package com.ovaphlow.crate.messages

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object MessagesRoutes {

    private val log = LoggerFactory.getLogger(MessagesRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = MessagesService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "messages"))
        }

        router.post("/messages").handler { ctx ->
            val b = body(ctx)
            val messageType = b.getString("message_type", "")
            val senderId = b.getString("sender_id", "")
            val senderType = b.getString("sender_type", "")
            val receiverId = b.getString("receiver_id", "")
            val receiverType = b.getString("receiver_type", "")
            if (messageType.isBlank() || senderId.isBlank() || senderType.isBlank() || receiverId.isBlank() || receiverType.isBlank()) {
                respond(ctx, 400, "message_type, sender_id, sender_type, receiver_id, receiver_type required"); return@handler
            }
            service.create(messageType, senderId, senderType, receiverId, receiverType, b.getJsonObject("payload", JsonObject()))
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/messages").handler { ctx ->
            service.list(
                messageType = ctx.request().getParam("message_type"),
                senderId = ctx.request().getParam("sender_id"),
                senderType = ctx.request().getParam("sender_type"),
                receiverId = ctx.request().getParam("receiver_id"),
                receiverType = ctx.request().getParam("receiver_type"),
                status = ctx.request().getParam("status"),
                limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50,
                offset = ctx.request().getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.get("/messages/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.put("/messages/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            service.update(id, b.getString("status"), b.getJsonObject("payload"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.delete("/messages/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            service.delete(id)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        router.patch("/messages/:id/status").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler respond(ctx, 400, "id required")
            val b = body(ctx)
            val status = b.getString("status", "")
            if (status.isBlank()) {
                respond(ctx, 400, "status required"); return@handler
            }
            service.updateStatus(id, status)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        return router
    }

    private fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .end(JsonObject().put("error", message).encode())
    }

    private fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("messages route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
