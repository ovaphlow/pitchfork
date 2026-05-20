package com.example.service

import com.example.auth.AuthRoutes
import com.example.files.FileRoutes
import com.example.settings.SettingsRoutes
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.example.service.MainKt")

fun main() {
    val vertx = Vertx.vertx()
    val mainRouter = Router.router(vertx)

    val apiRouter = Router.router(vertx)
    apiRouter.route("/auth/v1/*").subRouter(AuthRoutes.create(vertx))
    apiRouter.route("/settings/v1/*").subRouter(SettingsRoutes.create(vertx))
    apiRouter.route("/files/v1/*").subRouter(FileRoutes.create(vertx))
    mainRouter.route("/crate-api/*").subRouter(apiRouter)

    mainRouter.route("/health").handler { ctx ->
        ctx.json(io.vertx.core.json.JsonObject().put("status", "ok").put("app", "service-vertx-kotlin"))
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    vertx.createHttpServer()
        .requestHandler(mainRouter)
        .listen(port) { ar ->
            if (ar.succeeded()) {
                log.info("Server started on port {}", port)
            } else {
                log.error("Failed to start server", ar.cause())
            }
        }
}
