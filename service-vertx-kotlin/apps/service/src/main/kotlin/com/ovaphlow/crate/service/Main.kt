package com.ovaphlow.crate.service

import com.ovaphlow.crate.auth.AuthRoutes
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.files.FileRoutes
import com.ovaphlow.crate.settings.SettingsRoutes
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.ovaphlow.crate.service.MainKt")

fun main() {
    val vertx = Vertx.vertx()

    val retriever = ConfigRetriever.create(vertx,
        ConfigRetrieverOptions().addStore(
            ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(JsonObject().put("path", "config.json"))
        )
    )

    val config = retriever.getConfig().toCompletionStage().toCompletableFuture().get()

    val consoleLevel = config.getString("console-level", "DEBUG")
    val ctx = LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = ch.qos.logback.classic.Level.toLevel(consoleLevel)

    val dbConfig = config.getJsonObject("database", JsonObject())
    DatabaseConfig.migrate(dbConfig)
    val pool = DatabaseConfig.createPool(vertx, dbConfig)

    val mainRouter = Router.router(vertx)

    val apiRouter = Router.router(vertx)
    val authConfig = config.getJsonObject("auth", JsonObject())
    apiRouter.route("/auth/v1/*").subRouter(AuthRoutes.create(vertx, pool, authConfig))
    apiRouter.route("/settings/v1/*").subRouter(SettingsRoutes.create(vertx))
    apiRouter.route("/files/v1/*").subRouter(FileRoutes.create(vertx))
    mainRouter.route("/crate-api/*").subRouter(apiRouter)

    mainRouter.route("/health").handler { ctx ->
        ctx.json(JsonObject().put("status", "ok").put("app", "service-vertx-kotlin"))
    }

    val port = config.getJsonObject("server", JsonObject()).getInteger("port", 8080)
    val server = vertx.createHttpServer()
        .requestHandler(mainRouter)
        .listen(port)
        .toCompletionStage()
        .toCompletableFuture()
        .get()
    log.info("Server started on port {}", server.actualPort())
}
