package com.ovaphlow.crate.auth

import com.ovaphlow.crate.common.RsaCrypto
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
import java.security.PrivateKey

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

        // 生成 RSA 密钥对用于密码传输加密
        val keyPair = RsaCrypto.generateKeyPair()
        val publicKeyBase64 = RsaCrypto.encodePublicKeyBase64(keyPair.publicKey)
        val privateKey = keyPair.privateKey

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "auth"))
        }

        /** 获取 RSA 公钥（Base64 编码，X.509 SubjectPublicKeyInfo 格式） */
        router.get("/public-key").handler { ctx ->
            ctx.json(JsonObject().put("publicKey", publicKeyBase64))
        }

        /** 解密密码，失败则抛出异常 */
        fun decryptPassword(encrypted: String, ctx: io.vertx.ext.web.RoutingContext): String? {
            return try {
                RsaCrypto.decrypt(encrypted, privateKey)
            } catch (e: Exception) {
                log.error("Password decryption failed", e)
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "password decryption failed").encode())
                null
            }
        }

        router.post("/login").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val email = body.getString("email", "")
            val encryptedPassword = body.getString("password", "")

            if (email.isBlank() || encryptedPassword.isBlank()) {
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "email and password required").encode())
                return@handler
            }

            val password = decryptPassword(encryptedPassword, ctx) ?: return@handler

            service.login(email, password, ctx.request().remoteAddress().toString())
                .onSuccess { result ->
                    ctx.json(JsonObject()
                        .put("token", result.token)
                        .put("type", "Bearer")
                        .put("user", result.user))
                }
                .onFailure { err ->
                    log.error("Login failed", err)
                    when (err) {
                        is InvalidCredentialsException ->
                            ctx.response().setStatusCode(401)
                                .end(JsonObject().put("error", err.message).encode())
                        else -> {
                            ctx.response().setStatusCode(500)
                                .end(JsonObject().put("error", "internal error").encode())
                        }
                    }
                }
        }

        router.post("/sign-up").handler { ctx ->
            val body = ctx.body().asJsonObject()
            val email = body.getString("email", "")
            val encryptedPassword = body.getString("password", "")

            if (email.isBlank() || encryptedPassword.isBlank()) {
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "email and password required").encode())
                return@handler
            }

            val password = decryptPassword(encryptedPassword, ctx) ?: return@handler

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
                    log.error("Sign-up failed", err)
                    when (err) {
                        is EmailAlreadyRegisteredException ->
                            ctx.response().setStatusCode(409)
                                .end(JsonObject().put("error", err.message).encode())
                        else -> {
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
                .onFailure { err ->
                    log.error("Token verification failed", err)
                    ctx.response().setStatusCode(401)
                        .end(JsonObject().put("error", "invalid token").encode())
                }
        }

        return router
    }
}