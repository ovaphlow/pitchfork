package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.healthcare.HealthcareRoutes
import com.ovaphlow.crate.inventories.InventoriesRoutes
import com.ovaphlow.crate.log.Log
import com.ovaphlow.crate.nursing.NursingRoutes
import com.ovaphlow.crate.pharmacy.PharmacyRoutes
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.CorsHandler
import org.slf4j.LoggerFactory

private val log = Log.getLogger("com.ovaphlow.crate.aceso.MainKt")

fun main() {
    val vertx = Vertx.vertx()
    val configPath =
        System.getenv("PITCHFORK_CONFIG")?.takeIf(String::isNotBlank) ?: "config.json"

    val retriever =
        ConfigRetriever.create(
            vertx,
            ConfigRetrieverOptions().addStore(
                ConfigStoreOptions()
                    .setType("file")
                    .setFormat("json")
                    .setConfig(JsonObject().put("path", configPath)),
            ),
        )

    val config =
        retriever
            .getConfig()
            .toCompletionStage()
            .toCompletableFuture()
            .get()

    val consoleLevel = config.getString("console-level", "DEBUG")
    val ctx = LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level =
        ch.qos.logback.classic.Level
            .toLevel(consoleLevel)

    val dbConfig = config.getJsonObject("database", JsonObject())
    DatabaseConfig.migrate(dbConfig)
    val pool = DatabaseConfig.createPool(vertx, dbConfig)

    val mainRouter = Router.router(vertx)

    // --- CORS ---
    val corsHandler = CorsHandler.create()
    val defaultCorsOrigins = JsonArray()
        .add("http://localhost:4324")
        .add("http://127.0.0.1:4324")
    config
        .getJsonObject("server", JsonObject())
        .getJsonArray("cors-origins", defaultCorsOrigins)
        .forEach { origin -> corsHandler.addOrigin(origin.toString()) }
    mainRouter.route().handler(
        corsHandler
            .allowCredentials(true)
            .allowedMethod(HttpMethod.GET)
            .allowedMethod(HttpMethod.POST)
            .allowedMethod(HttpMethod.PUT)
            .allowedMethod(HttpMethod.PATCH)
            .allowedMethod(HttpMethod.DELETE)
            .allowedMethod(HttpMethod.OPTIONS)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization")
            .allowedHeader("X-CSRF-Token"),
    )

    val apiRouter = Router.router(vertx)
    val nexusBaseUrl = config.getJsonObject("nexus", JsonObject()).getString("base-url", "http://127.0.0.1:8421")
    val idpBaseUrl = config.getJsonObject("identity", JsonObject()).getString("base-url", "http://127.0.0.1:8420")
    apiRouter.route("/identity/v1/*").subRouter(
        ServiceProxyRoutes.create(
            vertx,
            idpBaseUrl,
            "identity",
            "/crate-api/identity/v1/problems/identity-unavailable",
        ),
    )
    apiRouter.route("/shared/v1/*").subRouter(
        ServiceProxyRoutes.create(
            vertx,
            nexusBaseUrl,
            "nexus",
            "/crate-api/shared/v1/problems/nexus-unavailable",
        ),
    )
    apiRouter.route("/inventories/v1/*").subRouter(InventoriesRoutes.create(vertx, pool))
    apiRouter.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))
    apiRouter.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool))
    apiRouter.route("/pharmacy/v1/*").subRouter(PharmacyRoutes.create(vertx, pool))
    mainRouter.route("/crate-api/*").subRouter(apiRouter)

    mainRouter.route("/health").handler { ctx ->
        ctx.json(JsonObject().put("status", "ok").put("app", "aceso"))
    }

    mainRouter.route().failureHandler { ctx ->
        val statusCode = ctx.statusCode() ?: 500
        val err = ctx.failure()
        log.error(
            "request exception: {} {} -> {}: {}",
            ctx.request().method(),
            ctx.request().path(),
            statusCode,
            err?.message ?: "unknown",
            err,
        )
        if (!ctx.response().ended()) {
            ctx.response().setStatusCode(statusCode).end(
                JsonObject()
                    .put("error", if (statusCode == 500) "internal error" else (err?.message ?: "unknown"))
                    .encode(),
            )
        }
    }

    val port = config.getJsonObject("server", JsonObject()).getInteger("port", 8080)
    val server =
        vertx
            .createHttpServer()
            .requestHandler(mainRouter)
            .listen(port)
            .toCompletionStage()
            .toCompletableFuture()
            .get()
    log.info("Server started on port {}", server.actualPort())
}
