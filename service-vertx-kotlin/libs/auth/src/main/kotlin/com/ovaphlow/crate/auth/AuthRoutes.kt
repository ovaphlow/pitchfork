package com.ovaphlow.crate.auth

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object AuthRoutes {

    private val log = LoggerFactory.getLogger(AuthRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool, authConfig: JsonObject = JsonObject()): Router {
        val router = Router.router(vertx)
        val jwtSecret = authConfig.getString("jwt-secret", "crate-default-secret")
        val jwtAuth = JWTAuth.create(vertx, JWTAuthOptions()
            .addPubSecKey(PubSecKeyOptions()
                .setAlgorithm("HS256")
                .setBuffer(jwtSecret)
                .setSymmetric(true))
            .setJWTOptions(JWTOptions().setExpiresInSeconds(86400)))
        val service = AuthService(pool, jwtAuth)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "auth"))
        }

        router.post("/login").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val email = body.getString("email", "")
            val password = body.getString("password", "")

            if (email.isBlank() || password.isBlank()) {
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "email and password required").encode())
                return@handler
            }

            service.login(email, password, ctx.request().remoteAddress().toString())
                .onSuccess { result ->
                    ctx.json(JsonObject()
                        .put("token", result.token)
                        .put("type", "Bearer")
                        .put("user", result.user))
                }
                .onFailure { err ->
                    when (err) {
                        is InvalidCredentialsException ->
                            ctx.response().setStatusCode(401)
                                .end(JsonObject().put("error", err.message).encode())
                        else -> {
                            log.error("Login failed", err)
                            ctx.response().setStatusCode(500)
                                .end(JsonObject().put("error", "internal error").encode())
                        }
                    }
                }
        }

        router.post("/sign-up").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val email = body.getString("email", "")
            val password = body.getString("password", "")

            if (email.isBlank() || password.isBlank()) {
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "email and password required").encode())
                return@handler
            }
            if (password.length < 6) {
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "password must be at least 6 characters").encode())
                return@handler
            }

            service.signUp(email, password)
                .onSuccess {
                    ctx.response().setStatusCode(201)
                        .end(JsonObject().put("status", "created").put("user", it.userJson).encode())
                }
                .onFailure { err ->
                    when (err) {
                        is EmailAlreadyRegisteredException ->
                            ctx.response().setStatusCode(409)
                                .end(JsonObject().put("error", err.message).encode())
                        else -> {
                            log.error("Sign-up failed", err)
                            ctx.response().setStatusCode(500)
                                .end(JsonObject().put("error", "internal error").encode())
                        }
                    }
                }
        }

        router.get("/verify").handler { ctx ->
            val authHeader = ctx.request().getHeader("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.response().setStatusCode(401)
                    .end(JsonObject().put("error", "missing or invalid token").encode())
                return@handler
            }
            val token = authHeader.removePrefix("Bearer ")

            service.verify(token)
                .onSuccess { result ->
                    ctx.json(JsonObject().put("valid", true).put("sub", result.sub))
                }
                .onFailure {
                    ctx.response().setStatusCode(401)
                        .end(JsonObject().put("error", "invalid token").encode())
                }
        }

        return router
    }
}