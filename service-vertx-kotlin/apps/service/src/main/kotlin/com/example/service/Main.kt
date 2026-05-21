package com.example.service

import com.example.auth.AuthRoutes
import com.example.database.DatabaseConfig
import com.example.files.FileRoutes
import com.example.settings.SettingsRoutes
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.example.service.MainKt")

fun main() {
    val vertx = Vertx.vertx()

    val retriever = ConfigRetriever.create(vertx,
        ConfigRetrieverOptions().addStore(
            ConfigStoreOptions()
                .setType("file")
                .setFormat("hocon")
                .setConfig(JsonObject().put("path", "config.json"))
        )
    )

    val config = retriever.getConfig().toCompletionStage().toCompletableFuture().get()
    val dbConfig = config.getJsonObject("database", JsonObject())
    val pool = DatabaseConfig.createPool(vertx, dbConfig)
    val dsl = DatabaseConfig.createDSL()

    val mainRouter = Router.router(vertx)

    val apiRouter = Router.router(vertx)
    apiRouter.route("/auth/v1/*").subRouter(AuthRoutes.create(vertx))
    apiRouter.route("/settings/v1/*").subRouter(SettingsRoutes.create(vertx))
    apiRouter.route("/files/v1/*").subRouter(FileRoutes.create(vertx))
    mainRouter.route("/crate-api/*").subRouter(apiRouter)

    mainRouter.route("/health").handler { ctx ->
        ctx.json(JsonObject().put("status", "ok").put("app", "service-vertx-kotlin"))
    }

    val port = config.getInteger("server.port", 8080)
    val server = vertx.createHttpServer()
        .requestHandler(mainRouter)
        .listen(port)
        .toCompletionStage()
        .toCompletableFuture()
        .get()
    log.info("Server started on port {}", server.actualPort())
}
