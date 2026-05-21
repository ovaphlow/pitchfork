package com.ovaphlow.crate.auth

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

object AuthRoutes {

    private val log = LoggerFactory.getLogger(AuthRoutes::class.java)

    fun create(vertx: Vertx): Router {
        val router = Router.router(vertx)

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "auth"))
        }

        router.post("/login").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val username = body.getString("username", "unknown")
            log.info("login request: {}", username)
            ctx.json(
                JsonObject()
                    .put("token", "mock-jwt-token-$username")
                    .put("type", "Bearer")
            )
        }

        router.post("/sign-up").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val username = body.getString("username")
            log.info("register request: {}", username)
            ctx.json(
                JsonObject()
                    .put("id", "user-${System.currentTimeMillis()}")
                    .put("username", username)
                    .put("status", "created")
            )
        }

        router.get("/verify").handler { ctx ->
            val token = ctx.request().getHeader("Authorization")
            if (token == null) {
                ctx.response().setStatusCode(401).end(JsonObject().put("error", "missing token").encode())
            } else {
                ctx.json(JsonObject().put("valid", true).put("sub", "mock-user"))
            }
        }

        return router
    }
}
