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
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

object AuthRoutes {

    private val log = LoggerFactory.getLogger(AuthRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool, dsl: DSLContext, authConfig: JsonObject = JsonObject()): Router {
        val router = Router.router(vertx)
        val jwtSecret = authConfig.getString("jwt-secret", "crate-default-secret")
        val jwtAuth = JWTAuth.create(vertx, JWTAuthOptions()
            .addPubSecKey(PubSecKeyOptions()
                .setAlgorithm("HS256")
                .setBuffer(jwtSecret)
                .setSymmetric(true))
            .setJWTOptions(JWTOptions().setExpiresInSeconds(86400)))

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "auth"))
        }

        router.post("/login").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val email = body.getString("email", "")
            val password = body.getString("password", "")

            if (email.isBlank() || password.isBlank()) {
                ctx.response().setStatusCode(400).end(JsonObject().put("error", "email and password required").encode())
                return@handler
            }

            pool.preparedQuery("SELECT * FROM users WHERE email = \$1")
                .execute(io.vertx.sqlclient.Tuple.of(email))
                .onSuccess { rows ->
                    if (rows.size() == 0) {
                        ctx.response().setStatusCode(401).end(JsonObject().put("error", "invalid credentials").encode())
                        return@onSuccess
                    }
                    val row = rows.iterator().next()
                    val hash = row.getValue("password_hash") as String

                    val bcrypt = at.favre.lib.crypto.bcrypt.BCrypt.verifyer()
                    if (!bcrypt.verify(password.toCharArray(), hash).verified) {
                        ctx.response().setStatusCode(401).end(JsonObject().put("error", "invalid credentials").encode())
                        return@onSuccess
                    }

                    val userId = row.getValue("id") as String
                    val activityRaw = row.getValue("activity_info")?.toString()
                    val activityJson = if (activityRaw != null) JsonObject(activityRaw) else JsonObject()
                    val loginCount = activityJson.getInteger("login_count", 0) + 1

                    val newActivity = JsonObject()
                        .put("login_count", loginCount)
                        .put("last_login_at", OffsetDateTime.now().toString())
                        .put("last_login_ip", ctx.request().remoteAddress().toString())
                        .put("last_password_change", activityJson.getValue("last_password_change"))

                    pool.preparedQuery("UPDATE users SET activity_info = \$1::jsonb, updated_at = now() WHERE id = \$2")
                        .execute(io.vertx.sqlclient.Tuple.of(newActivity.encode(), userId))
                        .onSuccess {
                            val token = jwtAuth.generateToken(JsonObject().put("sub", userId).put("email", email))
                            ctx.json(JsonObject()
                                .put("token", token)
                                .put("type", "Bearer")
                                .put("user", toUserJson(row)))
                        }
                }
                .onFailure { err ->
                    log.error("Login failed", err)
                    ctx.response().setStatusCode(500).end(JsonObject().put("error", "internal error").encode())
                }
        }

        router.post("/sign-up").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val email = body.getString("email", "")
            val password = body.getString("password", "")

            if (email.isBlank() || password.isBlank()) {
                ctx.response().setStatusCode(400).end(JsonObject().put("error", "email and password required").encode())
                return@handler
            }
            if (password.length < 6) {
                ctx.response().setStatusCode(400).end(JsonObject().put("error", "password must be at least 6 characters").encode())
                return@handler
            }

            pool.preparedQuery("SELECT count(*) FROM users WHERE email = \$1")
                .execute(io.vertx.sqlclient.Tuple.of(email))
                .onSuccess { rows ->
                    if (rows.iterator().next().getLong(0) > 0) {
                        ctx.response().setStatusCode(409).end(JsonObject().put("error", "email already registered").encode())
                        return@onSuccess
                    }

                    val hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, password.toCharArray())
                    val now = OffsetDateTime.now()
                    val id = java.util.UUID.randomUUID().toString().replace("-", "")

                    val insertSql = "INSERT INTO users (id, email, username, phone, password_hash, user_type, status, created_at, updated_at) VALUES (\$1, \$2, '', '', \$3, 'regular', 'pending', \$4, \$4) RETURNING id"
                    pool.preparedQuery(insertSql)
                        .execute(io.vertx.sqlclient.Tuple.of(id, email, hash, now))
                        .onSuccess { insertResult ->
                            val newId = insertResult.iterator().next().getValue("id").toString()
                            pool.preparedQuery("SELECT * FROM users WHERE id = \$1")
                                .execute(io.vertx.sqlclient.Tuple.of(newId))
                                .onSuccess { userRows ->
                                    ctx.response().setStatusCode(201).end(JsonObject()
                                        .put("status", "created")
                                        .put("user", toUserJson(userRows.iterator().next())).encode())
                                }
                                .onFailure { err ->
                                    log.error("Failed to fetch new user", err)
                                    ctx.response().setStatusCode(500).end(JsonObject().put("error", "internal error").encode())
                                }
                        }
                        .onFailure { err ->
                            log.error("Insert failed", err)
                            ctx.response().setStatusCode(500).end(JsonObject().put("error", "internal error").encode())
                        }
                }
                .onFailure { err ->
                    log.error("Sign-up failed", err)
                    ctx.response().setStatusCode(500).end(JsonObject().put("error", "internal error").encode())
                }
        }

        router.get("/verify").handler { ctx ->
            val authHeader = ctx.request().getHeader("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.response().setStatusCode(401).end(JsonObject().put("error", "missing or invalid token").encode())
                return@handler
            }
            val token = authHeader.removePrefix("Bearer ")
            jwtAuth.authenticate(JsonObject().put("token", token))
                .onSuccess { user ->
                    ctx.json(JsonObject().put("valid", true).put("sub", user.principal().getString("sub")))
                }
                .onFailure {
                    ctx.response().setStatusCode(401).end(JsonObject().put("error", "invalid token").encode())
                }
        }

        return router
    }

    private fun toUserJson(row: io.vertx.sqlclient.Row): JsonObject {
        return JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("email", row.getValue("email")?.toString())
            .put("username", row.getValue("username")?.toString())
            .put("phone", row.getValue("phone")?.toString())
            .put("user_type", row.getValue("user_type")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("security_info", row.getValue("security_info")?.let { JsonObject(it.toString()) })
            .put("verification_info", row.getValue("verification_info")?.let { JsonObject(it.toString()) })
            .put("password_reset_info", row.getValue("password_reset_info")?.let { JsonObject(it.toString()) })
            .put("activity_info", row.getValue("activity_info")?.let { JsonObject(it.toString()) })
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }
}
