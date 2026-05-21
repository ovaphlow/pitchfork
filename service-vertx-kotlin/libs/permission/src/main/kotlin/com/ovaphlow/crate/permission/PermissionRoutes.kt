package com.ovaphlow.crate.permission

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object PermissionRoutes {

    private val log = LoggerFactory.getLogger(PermissionRoutes::class.java)

    fun create(vertx: Vertx, pool: Pool, jwtAuth: JWTAuth): Router {
        val router = Router.router(vertx)
        val service = PermissionService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "permission"))
        }

        // ──────────────── Authorization ────────────────────────

        router.get("/authorize").handler { ctx ->
            val userId = ctx.request().getParam("user_id")
            val action = ctx.request().getParam("action")
            val resourceType = ctx.request().getParam("resource_type")
            val resourceId = ctx.request().getParam("resource_id")

            if (userId == null || action == null || resourceType == null) {
                ctx.response().setStatusCode(400)
                    .end(JsonObject().put("error", "user_id, action, resource_type required").encode())
                return@handler
            }
            val context = JsonObject()
            ctx.request().params().forEach { entry ->
                if (entry.key.startsWith("ctx.")) {
                    context.put(entry.key.removePrefix("ctx."), entry.value)
                }
            }
            service.authorize(userId, action, resourceType, resourceId,
                JsonObject().put("context", context))
                .onSuccess { ctx.json(JsonObject()
                    .put("allowed", it.allowed)
                    .put("reason", it.reason)
                    .put("engine", it.engine)) }
                .onFailure { respondError(ctx, it) }
        }

        // ──────────────── Roles ────────────────────────────────

        router.post("/roles").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            if (name.isBlank()) {
                respond(ctx, 400, "name required"); return@handler
            }
            service.createRole(name, b.getString("description", ""))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is DuplicateException) respond(ctx, 409, it.message) else respondError(ctx, it) }
        }

        router.get("/roles").handler { ctx ->
            service.listRoles().onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.get("/roles/:id").handler { ctx ->
            service.getRole(ctx.pathParam("id"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.put("/roles/:id").handler { ctx ->
            val b = body(ctx)
            service.updateRole(ctx.pathParam("id"), b.getString("name"), b.getString("description"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.delete("/roles/:id").handler { ctx ->
            service.deleteRole(ctx.pathParam("id"))
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ──────────────── Permissions ──────────────────────────

        router.post("/permissions").handler { ctx ->
            val b = body(ctx)
            val name = b.getString("name", "")
            val resource = b.getString("resource", "")
            val action = b.getString("action", "")
            if (name.isBlank() || resource.isBlank() || action.isBlank()) {
                respond(ctx, 400, "name, resource, action required"); return@handler
            }
            service.createPermission(name, b.getString("description", ""), resource, action)
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is DuplicateException) respond(ctx, 409, it.message) else respondError(ctx, it) }
        }

        router.get("/permissions").handler { ctx ->
            service.listPermissions().onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.get("/permissions/:id").handler { ctx ->
            service.getPermission(ctx.pathParam("id"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.delete("/permissions/:id").handler { ctx ->
            service.deletePermission(ctx.pathParam("id"))
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ──────────────── Role ↔ Permission ────────────────────

        router.post("/roles/:id/permissions").handler { ctx ->
            val permId = body(ctx).getString("permission_id", "")
            if (permId.isBlank()) {
                respond(ctx, 400, "permission_id required"); return@handler
            }
            service.assignPermissionToRole(ctx.pathParam("id"), permId)
                .onSuccess { ctx.json(JsonObject().put("status", "assigned")) }
                .onFailure { respondError(ctx, it) }
        }

        router.delete("/roles/:id/permissions/:permId").handler { ctx ->
            service.removePermissionFromRole(ctx.pathParam("id"), ctx.pathParam("permId"))
                .onSuccess { ctx.json(JsonObject().put("status", "removed")) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/roles/:id/permissions").handler { ctx ->
            service.getRolePermissions(ctx.pathParam("id"))
                .onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        // ──────────────── RBAC assignments ─────────────────────

        router.post("/assignments").handler { ctx ->
            val b = body(ctx)
            val uid = b.getString("user_id", "")
            val rid = b.getString("role_id", "")
            if (uid.isBlank() || rid.isBlank()) {
                respond(ctx, 400, "user_id, role_id required"); return@handler
            }
            service.assignRole(uid, rid, b.getString("scope_type", "global"), b.getString("scope_id", "0"))
                .onSuccess { ctx.json(JsonObject().put("status", "assigned")) }
                .onFailure { respondError(ctx, it) }
        }

        router.delete("/assignments").handler { ctx ->
            val uid = ctx.request().getParam("user_id")
            val rid = ctx.request().getParam("role_id")
            val scopeType = ctx.request().getParam("scope_type") ?: "global"
            val scopeId = ctx.request().getParam("scope_id") ?: "0"
            if (uid == null || rid == null) {
                respond(ctx, 400, "user_id, role_id required"); return@handler
            }
            service.unassignRole(uid, rid, scopeType, scopeId)
                .onSuccess { ctx.json(JsonObject().put("status", "removed")) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/users/:userId/assignments").handler { ctx ->
            service.getUserAssignments(ctx.pathParam("userId"))
                .onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        // ──────────────── ReBAC relations ──────────────────────

        router.post("/relations").handler { ctx ->
            val b = body(ctx)
            val st = b.getString("subject_type", "")
            val si = b.getString("subject_id", "")
            val rel = b.getString("relation", "")
            val ot = b.getString("object_type", "")
            val oi = b.getString("object_id", "")
            if (st.isBlank() || si.isBlank() || rel.isBlank() || ot.isBlank() || oi.isBlank()) {
                respond(ctx, 400, "subject_type, subject_id, relation, object_type, object_id required"); return@handler
            }
            service.createRelation(st, si, rel, ot, oi)
                .onSuccess { ctx.json(JsonObject().put("status", "created")) }
                .onFailure { respondError(ctx, it) }
        }

        router.delete("/relations").handler { ctx ->
            val st = ctx.request().getParam("subject_type")
            val si = ctx.request().getParam("subject_id")
            val rel = ctx.request().getParam("relation")
            val ot = ctx.request().getParam("object_type")
            val oi = ctx.request().getParam("object_id")
            if (st == null || si == null || rel == null || ot == null || oi == null) {
                respond(ctx, 400, "subject_type, subject_id, relation, object_type, object_id required"); return@handler
            }
            service.deleteRelation(st, si, rel, ot, oi)
                .onSuccess { ctx.json(JsonObject().put("status", "deleted")) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/relations").handler { ctx ->
            service.listRelations(
                ctx.request().getParam("subject_type"),
                ctx.request().getParam("subject_id"),
                ctx.request().getParam("object_type"),
                ctx.request().getParam("object_id")
            ).onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        // ──────────────── ABAC policies ────────────────────────

        router.post("/policies").handler { ctx ->
            val b = body(ctx)
            val rt = b.getString("resource_type", "")
            val ac = b.getString("action", "")
            if (rt.isBlank() || ac.isBlank()) {
                respond(ctx, 400, "resource_type, action required"); return@handler
            }
            service.createPolicy(rt, ac,
                b.getString("effect", "allow"),
                b.getInteger("priority", 0),
                b.getJsonObject("condition_json", JsonObject()),
                b.getString("description", ""))
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/policies").handler { ctx ->
            service.listPolicies().onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.get("/policies/:id").handler { ctx ->
            service.getPolicy(ctx.pathParam("id"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.put("/policies/:id").handler { ctx ->
            val b = body(ctx)
            service.updatePolicy(ctx.pathParam("id"),
                b.getString("resource_type"), b.getString("action"), b.getString("effect"),
                b.getInteger("priority"), b.getJsonObject("condition_json"), b.getString("description"))
                .onSuccess { ctx.json(it) }
                .onFailure { if (it is NotFoundException) respond(ctx, 404, it.message) else respondError(ctx, it) }
        }

        router.delete("/policies/:id").handler { ctx ->
            service.deletePolicy(ctx.pathParam("id"))
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { respondError(ctx, it) }
        }

        // ──────────────── User attributes ──────────────────────

        router.put("/users/:userId/attributes").handler { ctx ->
            service.setUserAttributes(ctx.pathParam("userId"), body(ctx))
                .onSuccess { ctx.json(JsonObject().put("status", "saved")) }
                .onFailure { respondError(ctx, it) }
        }

        router.get("/users/:userId/attributes").handler { ctx ->
            service.getUserAttributes(ctx.pathParam("userId"))
                .onSuccess { ctx.json(it) }.onFailure { respondError(ctx, it) }
        }

        router.delete("/users/:userId/attributes/:key").handler { ctx ->
            service.deleteUserAttribute(ctx.pathParam("userId"), ctx.pathParam("key"))
                .onSuccess { ctx.json(JsonObject().put("status", "deleted")) }
                .onFailure { respondError(ctx, it) }
        }

        return router
    }

    private fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .end(JsonObject().put("error", message).encode())
    }

    private fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("permission route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
