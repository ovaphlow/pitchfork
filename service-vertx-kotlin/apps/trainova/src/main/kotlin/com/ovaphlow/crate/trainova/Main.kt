package com.ovaphlow.crate.trainova

import com.ovaphlow.crate.analytics.AnalyticsRoutes
import com.ovaphlow.crate.auth.AuthRoutes
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.exam.ExamRoutes
import com.ovaphlow.crate.knowledge.KnowledgeRoutes
import com.ovaphlow.crate.onsite.OnsiteRoutes
import com.ovaphlow.crate.permission.PermissionRoutes
import com.ovaphlow.crate.skills.SkillsRoutes
import com.ovaphlow.crate.training.TrainingRoutes
import com.ovaphlow.crate.users.UsersRoutes
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.CorsHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.ovaphlow.crate.trainova.MainKt")

private fun requiredEnvironment(name: String): String =
    System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("$name must be set")

fun main() {
    val vertx = Vertx.vertx()
    val configPath = requiredEnvironment("PITCHFORK_CONFIG")

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
    mainRouter.route().handler(
        CorsHandler
            .create()
            .addOrigin("*")
            .allowedMethod(HttpMethod.GET)
            .allowedMethod(HttpMethod.POST)
            .allowedMethod(HttpMethod.PUT)
            .allowedMethod(HttpMethod.PATCH)
            .allowedMethod(HttpMethod.DELETE)
            .allowedMethod(HttpMethod.OPTIONS)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization"),
    )

    val jwtSecret = requiredEnvironment("PITCHFORK_JWT_SECRET")
    val jwtAuth =
        JWTAuth.create(
            vertx,
            JWTAuthOptions()
                .addPubSecKey(
                    PubSecKeyOptions()
                        .setAlgorithm("HS256")
                        .setBuffer(jwtSecret)
                        .setSymmetric(true),
                ).setJWTOptions(JWTOptions().setExpiresInSeconds(86400)),
        )

    val apiRouter = Router.router(vertx)
    apiRouter.route("/auth/v1/*").subRouter(AuthRoutes.create(vertx, pool, jwtSecret))
    apiRouter.route("/permission/v1/*").subRouter(PermissionRoutes.create(vertx, pool, jwtAuth))
    apiRouter.route("/users/v1/*").subRouter(UsersRoutes.create(vertx, pool))
    apiRouter.route("/knowledge/v1/*").subRouter(KnowledgeRoutes.create(vertx, pool))
    apiRouter.route("/skills/v1/*").subRouter(SkillsRoutes.create(vertx, pool))
    apiRouter.route("/training/v1/*").subRouter(TrainingRoutes.create(vertx, pool))
    apiRouter.route("/exam/v1/*").subRouter(ExamRoutes.create(vertx, pool))
    apiRouter.route("/onsite/v1/*").subRouter(OnsiteRoutes.create(vertx, pool))
    apiRouter.route("/analytics/v1/*").subRouter(AnalyticsRoutes.create(vertx, pool))
    mainRouter.route("/crate-api/*").subRouter(apiRouter)

    mainRouter.route("/health").handler { ctx ->
        ctx.json(JsonObject().put("status", "ok").put("app", "trainova"))
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
