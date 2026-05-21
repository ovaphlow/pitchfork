package com.ovaphlow.crate.permission

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

class PermissionGuard(private val vertx: Vertx, private val pool: Pool, private val jwtAuth: JWTAuth) {

    private val log = LoggerFactory.getLogger(PermissionGuard::class.java)
    private val service = PermissionService(pool)

    fun requirePermission(
        resourceType: String,
        action: String,
        contextProvider: (RoutingContext) -> JsonObject = { JsonObject() }
    ): Handler<RoutingContext> {
        return Handler { ctx ->
            val authHeader = ctx.request().getHeader("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.response().setStatusCode(401)
                    .end(JsonObject().put("error", "missing or invalid token").encode())
                return@Handler
            }
            val token = authHeader.removePrefix("Bearer ")

            jwtAuth.authenticate(JsonObject().put("token", token))
                .onSuccess { user ->
                    val principal = user.principal()
                    val userId = principal.getString("sub")

                    ctx.put("userId", userId)
                    ctx.put("token", token)
                    ctx.put("principal", principal)

                    val abacCtx = contextProvider(ctx)
                    val resourceId = abacCtx.getString("resource_id") ?: abacCtx.getJsonObject("resource")?.getString("id")
                    val fullCtx = JsonObject()
                        .put("resource", abacCtx.getJsonObject("resource") ?: JsonObject())
                        .put("context", abacCtx.getJsonObject("context") ?: JsonObject())

                    service.authorize(userId, action, resourceType, resourceId, fullCtx)
                        .onSuccess { result ->
                            if (result.allowed) {
                                ctx.next()
                            } else {
                                ctx.response().setStatusCode(403)
                                    .end(JsonObject().put("error", "forbidden")
                                        .put("reason", result.reason).put("engine", result.engine).encode())
                            }
                        }
                        .onFailure { err ->
                            log.error("authorize check failed", err)
                            ctx.response().setStatusCode(500)
                                .end(JsonObject().put("error", "internal error").encode())
                        }
                }
                .onFailure {
                    ctx.response().setStatusCode(401)
                        .end(JsonObject().put("error", "invalid token").encode())
                }
        }
    }

    fun authenticate(): Handler<RoutingContext> {
        return Handler { ctx ->
            val authHeader = ctx.request().getHeader("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.response().setStatusCode(401)
                    .end(JsonObject().put("error", "missing or invalid token").encode())
                return@Handler
            }
            val token = authHeader.removePrefix("Bearer ")

            jwtAuth.authenticate(JsonObject().put("token", token))
                .onSuccess { user ->
                    val principal = user.principal()
                    ctx.put("userId", principal.getString("sub"))
                    ctx.put("token", token)
                    ctx.put("principal", principal)
                    ctx.next()
                }
                .onFailure {
                    ctx.response().setStatusCode(401)
                        .end(JsonObject().put("error", "invalid token").encode())
                }
        }
    }
}
