package com.example.settings

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

object SettingsRoutes {

    private val log = LoggerFactory.getLogger(SettingsRoutes::class.java)

    fun create(vertx: Vertx): Router {
        val router = Router.router(vertx)

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "settings"))
        }

        router.get("/profile").handler { ctx ->
            ctx.json(
                JsonObject()
                    .put("id", "user-001")
                    .put("displayName", "Test User")
                    .put("email", "test@example.com")
                    .put("locale", "zh-CN")
            )
        }

        router.put("/profile").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val displayName = body.getString("displayName")
            log.info("update profile: {}", displayName)
            ctx.json(
                JsonObject()
                    .put("id", "user-001")
                    .put("displayName", displayName)
                    .put("status", "updated")
            )
        }

        return router
    }
}
