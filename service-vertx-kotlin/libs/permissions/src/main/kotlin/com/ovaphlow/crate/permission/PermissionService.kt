package com.ovaphlow.crate.permission

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.settings.public_.tables.Settings
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

data class CheckResult(val allowed: Boolean, val reason: String = "", val engine: String = "")

class PermissionService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {
    private val s = Settings.SETTINGS


    private val log = LoggerFactory.getLogger(PermissionService::class.java)

    // ────────────────────────────────────────────────────────────
    //  Unified authorization entry: deny > allow,
    //  priority RBAC > ReBAC > ABAC
    // ────────────────────────────────────────────────────────────

    fun authorize(
        userId: String,
        action: String,
        resourceType: String,
        resourceId: String? = null,
        context: JsonObject = JsonObject()
    ): Future<CheckResult> {
        // 1. ABAC deny policies (highest override)
        return checkAbacDeny(userId, action, resourceType, context)
            .flatMap { denyResult ->
                if (denyResult.allowed) {
                    return@flatMap Future.succeededFuture(
                        CheckResult(false, "denied by ABAC policy", "abac")
                    )
                }
                // 2. RBAC
                checkRbac(userId, action, resourceType)
                    .flatMap { rbac ->
                        if (rbac.allowed) {
                            return@flatMap Future.succeededFuture(rbac)
                        }
                        // 3. ReBAC
                        if (resourceId != null) {
                            checkRebac(userId, action, resourceType, resourceId)
                                .flatMap { rebac ->
                                    if (rebac.allowed) {
                                        return@flatMap Future.succeededFuture(rebac)
                                    }
                                    // 4. ABAC allow policies
                                    checkAbacAllow(userId, action, resourceType, context)
                                }
                        } else {
                            checkAbacAllow(userId, action, resourceType, context)
                        }
                    }
            }
    }

    // ────────────────────────────────────────────────────────────
    //  RBAC check
    // ────────────────────────────────────────────────────────────

    private fun checkRbac(userId: String, action: String, resourceType: String): Future<CheckResult> {
        val query = ctx.selectOne()
            .from(DSL.table("rbac_assignments"))
            .join(DSL.table("role_permissions")).on(DSL.field("role_permissions.role_id").eq(DSL.field("rbac_assignments.role_id")))
            .join(DSL.table("permissions")).on(DSL.field("permissions.id").eq(DSL.field("role_permissions.permission_id")))
            .where(DSL.field("rbac_assignments.user_id").eq(userId))
            .and(DSL.field("permissions.resource").eq(resourceType).or(DSL.field("permissions.resource").eq("*")))
            .and(DSL.field("permissions.action").eq(action).or(DSL.field("permissions.action").eq("*")))
            .limit(1)
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                if (rows.size() > 0) CheckResult(true, "allowed by RBAC", "rbac")
                else CheckResult(false, "no RBAC match", "rbac")
            }
    }

    // ────────────────────────────────────────────────────────────
    //  ReBAC check (direct + team transitive)
    // ────────────────────────────────────────────────────────────

    private val actionToMinRelation = mapOf(
        "read" to 1, "view" to 1, "list" to 1,
        "write" to 2, "update" to 2, "create" to 2,
        "delete" to 3, "remove" to 3,
        "manage" to 4, "admin" to 4
    )

    private val relationLevel = mapOf(
        "viewer" to 1, "reader" to 1,
        "member" to 2, "contributor" to 2, "editor" to 2,
        "owner" to 4, "admin" to 4
    )

    private fun checkRebac(
        userId: String,
        action: String,
        objectType: String,
        objectId: String
    ): Future<CheckResult> {
        val neededLevel = actionToMinRelation[action] ?: return Future.succeededFuture(
            CheckResult(false, "unknown action", "rebac")
        )

        // direct: (user, userId, relation, objectType, objectId)
        // transitive: (team, teamId, relation, objectType, objectId)
        //   where (user, userId, member, team, teamId)
        val subquery = ctx.select(DSL.field("t.subject_id"))
            .from(DSL.table("rebac_relations").`as`("t"))
            .where(DSL.field("t.subject_type").eq("user"))
            .and(DSL.field("t.subject_id").eq(userId))
            .and(DSL.field("t.relation").eq("member"))
            .and(DSL.field("t.object_type").eq("team"))

        val query = ctx.select(DSL.field("rr.relation"))
            .from(DSL.table("rebac_relations").`as`("rr"))
            .where(DSL.field("rr.object_type").eq(objectType))
            .and(DSL.field("rr.object_id").eq(objectId))
            .and(
                DSL.field("rr.subject_type").eq("user").and(DSL.field("rr.subject_id").eq(userId))
                    .or(
                        DSL.field("rr.subject_type").eq("team")
                            .and(DSL.field("rr.subject_id").`in`(subquery))
                    )
            )
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                if (rows.size() == 0) return@map CheckResult(false, "no ReBAC relation", "rebac")
                var maxLevel = 0
                rows.forEach { row ->
                    val rel = row.getString("relation")
                    val level = relationLevel[rel] ?: 0
                    if (level > maxLevel) maxLevel = level
                }
                if (maxLevel >= neededLevel) CheckResult(true, "allowed by ReBAC", "rebac")
                else CheckResult(false, "insufficient ReBAC relation level", "rebac")
            }
    }

    // ────────────────────────────────────────────────────────────
    //  ABAC check
    // ────────────────────────────────────────────────────────────

    private fun checkAbacDeny(userId: String, action: String, resourceType: String, context: JsonObject): Future<CheckResult> {
        return checkAbacPolicies(userId, action, resourceType, context, "deny")
    }

    private fun checkAbacAllow(userId: String, action: String, resourceType: String, context: JsonObject): Future<CheckResult> {
        return checkAbacPolicies(userId, action, resourceType, context, "allow")
    }

    private fun checkAbacPolicies(
        userId: String,
        action: String,
        resourceType: String,
        context: JsonObject,
        effect: String
    ): Future<CheckResult> {
        val query = ctx.select(
                DSL.field("id"), DSL.field("resource_type"), DSL.field("action"),
                DSL.field("effect"), DSL.field("priority"), DSL.field("condition_json"),
                DSL.field("description"), DSL.field("created_at"), DSL.field("updated_at")
            )
            .from(DSL.table("abac_policies"))
            .where(DSL.field("resource_type").eq(resourceType).or(DSL.field("resource_type").eq("*")))
            .and(DSL.field("action").eq(action).or(DSL.field("action").eq("*")))
            .and(DSL.field("effect").eq(effect))
            .orderBy(DSL.field("priority").desc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    return@flatMap Future.succeededFuture(CheckResult(false, "no ABAC match", "abac"))
                }
                resolveUserAttrs(userId).map { userAttrs ->
                    val ctx = JsonObject()
                        .put("user", userAttrs)
                        .put("resource", context.getJsonObject("resource") ?: JsonObject())
                        .put("context", context.getJsonObject("context") ?: JsonObject())
                        .put("now", System.currentTimeMillis())

                    var matched = false
                    rows.forEach { row ->
                        val cond = row.getValue("condition_json") as? JsonObject ?: JsonObject()
                        if (cond.isEmpty() || evaluateConditionTree(cond, ctx)) {
                            matched = true
                            return@forEach
                        }
                    }
                    if (matched) CheckResult(true, "matched ABAC $effect policy", "abac")
                    else CheckResult(false, "no ABAC condition matched", "abac")
                }
            }
    }

    // ────────────────────────────────────────────────────────────
    //  ABAC condition tree evaluation
    // ────────────────────────────────────────────────────────────

    private fun evaluateConditionTree(cond: JsonObject, ctx: JsonObject): Boolean {
        if (cond.containsKey("all")) {
            val list = cond.getJsonArray("all")
            if (list == null || list.size() == 0) return true
            return list.all { (it as? JsonObject)?.let { evaluateConditionTree(it, ctx) } ?: false }
        }
        if (cond.containsKey("any")) {
            val list = cond.getJsonArray("any")
            if (list == null || list.size() == 0) return true
            return list.any { (it as? JsonObject)?.let { evaluateConditionTree(it, ctx) } ?: false }
        }
        if (cond.containsKey("not")) {
            return !evaluateConditionTree(cond.getJsonObject("not"), ctx)
        }

        val attr = cond.getString("attr") ?: return false
        val op = cond.getString("op") ?: return false
        val valRaw = cond.getValue("val")

        val actual = resolveAttrPath(attr, ctx)
        return if (actual == null) false else evaluateOp(actual, op, valRaw)
    }

    private fun resolveAttrPath(path: String, ctx: JsonObject): Any? {
        val parts = path.split(".")
        var cur: Any? = ctx
        for (p in parts) {
            cur = when (cur) {
                is JsonObject -> cur.getValue(p)
                is JsonArray -> try { cur.getInteger(p.toIntOrNull() ?: return null) } catch (_: Exception) { null }
                else -> return null
            }
            if (cur == null) return null
        }
        return cur
    }

    private fun evaluateOp(actual: Any, op: String, expected: Any?): Boolean {
        if (expected == null) return false
        return try {
            when (op) {
                "eq"    -> compareEq(actual, expected)
                "neq"   -> !compareEq(actual, expected)
                "gt"    -> compareNum(actual, expected) > 0
                "gte"   -> compareNum(actual, expected) >= 0
                "lt"    -> compareNum(actual, expected) < 0
                "lte"   -> compareNum(actual, expected) <= 0
                "in" -> {
                    val arr = when (expected) {
                        is JsonArray -> expected
                        is String -> JsonArray(expected)
                        else -> return false
                    }
                    arr.stream().anyMatch { compareEq(actual, it) }
                }
                "contains" -> {
                    when {
                        actual is String && expected is String -> actual.contains(expected)
                        actual is JsonArray -> actual.stream().anyMatch { compareEq(it, expected) }
                        else -> false
                    }
                }
                "startswith" -> actual is String && expected is String && actual.startsWith(expected)
                "endswith"   -> actual is String && expected is String && actual.endsWith(expected)
                "exists"     -> true
                else -> false
            }
        } catch (_: Exception) { false }
    }

    private fun compareEq(a: Any, b: Any): Boolean {
        return when {
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is Boolean && b is Boolean -> a == b
            else -> a.toString() == b.toString()
        }
    }

    private fun compareNum(a: Any, b: Any): Int {
        val da = when (a) { is Number -> a.toDouble(); is String -> a.toDoubleOrNull() ?: return 0; else -> return 0 }
        val db = when (b) { is Number -> b.toDouble(); is String -> b.toDoubleOrNull() ?: return 0; else -> return 0 }
        return da.compareTo(db)
    }

    // ────────────────────────────────────────────────────────────
    //  User attributes
    // ────────────────────────────────────────────────────────────

    private fun resolveUserAttrs(userId: String): Future<JsonObject> {
        val query = ctx.select(DSL.field("attr_key"), DSL.field("attr_value"))
            .from(DSL.table("user_attributes"))
            .where(DSL.field("user_id").eq(userId))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                val obj = JsonObject()
                rows.forEach { row ->
                    val key = row.getString("attr_key")
                    val value = row.getString("attr_value")
                    if (key != null) obj.put(key, value.tryParseValue())
                }
                obj
            }
    }

    fun setUserAttributes(userId: String, attrs: JsonObject): Future<Void> {
        val delQuery = ctx.deleteFrom(DSL.table("user_attributes"))
            .where(DSL.field("user_id").eq(userId))
        return pool.preparedQuery(DatabaseConfig.sql(delQuery))
            .execute(DatabaseConfig.tuple(delQuery))
            .flatMap {
                val futures = mutableListOf<Future<*>>()
                attrs.fieldNames().forEach { key ->
                    val value = attrs.getValue(key)?.toString() ?: ""
                    val insertQuery = ctx.insertInto(
                            DSL.table("user_attributes"),
                            DSL.field("user_id"), DSL.field("attr_key"), DSL.field("attr_value")
                        )
                        .values(userId, key, value)
                        .onConflict(DSL.field("user_id"), DSL.field("attr_key"))
                        .doUpdate().set(DSL.field("attr_value"), value)
                    futures.add(
                        pool.preparedQuery(DatabaseConfig.sql(insertQuery))
                            .execute(DatabaseConfig.tuple(insertQuery))
                    )
                }
                if (futures.isEmpty()) Future.succeededFuture()
                else io.vertx.core.CompositeFuture.all(futures).map { null }
            }
    }

    fun getUserAttributes(userId: String): Future<JsonObject> = resolveUserAttrs(userId)

    fun deleteUserAttribute(userId: String, key: String): Future<Void> {
        val query = ctx.deleteFrom(DSL.table("user_attributes"))
            .where(DSL.field("user_id").eq(userId))
            .and(DSL.field("attr_key").eq(key))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    // ────────────────────────────────────────────────────────────
    //  Roles CRUD (stored in settings table, category="role")
    // ────────────────────────────────────────────────────────────

    fun createRole(name: String, description: String): Future<JsonObject> {
        val checkQuery = ctx.selectCount()
            .from(s)
            .where(s.CATEGORY.eq("role"))
            .and(DSL.field("payload->>'name'").eq(name))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.iterator().next().getLong(0) > 0)
                    return@flatMap Future.failedFuture(DuplicateException("role name already exists"))
                val id = Ulid.generate()
                val payload = JsonObject().put("name", name).put("description", description)
                val insertQuery = ctx.insertInto(s, s.ID, s.CATEGORY, s.CODE, s.PAYLOAD)
                    .values(id, "role", id, JSONB.valueOf(payload.encode()))
                    .returning(s.ID, s.PAYLOAD, s.CODE, s.CREATED_AT, s.UPDATED_AT)
                pool.preparedQuery(DatabaseConfig.sql(insertQuery))
                    .execute(DatabaseConfig.tuple(insertQuery))
                    .map { toRoleJson(it.iterator().next()) }
            }
    }

    fun listRoles(): Future<JsonArray> {
        val query = ctx.select(s.ID, s.PAYLOAD, s.CODE, s.CREATED_AT, s.UPDATED_AT)
            .from(s)
            .where(s.CATEGORY.eq("role"))
            .orderBy(s.CREATED_AT.asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toRoleJson(it)) }; a }
    }

    fun getRole(id: String): Future<JsonObject> {
        val query = ctx.select(s.ID, s.PAYLOAD, s.CODE, s.CREATED_AT, s.UPDATED_AT)
            .from(s)
            .where(s.CATEGORY.eq("role"))
            .and(s.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("role not found"))
                else Future.succeededFuture(toRoleJson(rows.iterator().next()))
            }
    }

    fun updateRole(id: String, name: String?, description: String?): Future<JsonObject> {
        return getRole(id).flatMap { existing ->
            val payload = existing.getJsonObject("payload", JsonObject())
            val newName = name ?: payload.getString("name", "")
            val newDesc = description ?: payload.getString("description", "")
            val newPayload = JsonObject().put("name", newName).put("description", newDesc)
            val query = ctx.update(s)
                .set(s.PAYLOAD, JSONB.valueOf(newPayload.encode()))
                .set(s.UPDATED_AT, java.time.LocalDateTime.now())
                .where(s.CATEGORY.eq("role"))
                .and(s.ID.eq(id))
                .returning(s.ID, s.PAYLOAD, s.CODE, s.CREATED_AT, s.UPDATED_AT)
            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { rows ->
                    if (rows.size() == 0) Future.failedFuture(NotFoundException("role not found"))
                    else Future.succeededFuture(toRoleJson(rows.iterator().next()))
                }
        }
    }

    // deleteRole also cleans up RBAC assignments and role-permission links
    fun deleteRole(id: String): Future<Void> {
        val delRbac = ctx.deleteFrom(DSL.table("rbac_assignments")).where(DSL.field("role_id").eq(id))
        val delRp = ctx.deleteFrom(DSL.table("role_permissions")).where(DSL.field("role_id").eq(id))
        val delSetting = ctx.deleteFrom(s).where(s.CATEGORY.eq("role")).and(s.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(delRbac))
            .execute(DatabaseConfig.tuple(delRbac))
            .flatMap { pool.preparedQuery(DatabaseConfig.sql(delRp)).execute(DatabaseConfig.tuple(delRp)) }
            .flatMap { pool.preparedQuery(DatabaseConfig.sql(delSetting)).execute(DatabaseConfig.tuple(delSetting)) }
            .map { null }
    }

    // ────────────────────────────────────────────────────────────
    //  Permissions CRUD
    // ────────────────────────────────────────────────────────────

    fun createPermission(name: String, description: String, resource: String, action: String): Future<JsonObject> {
        val checkQuery = ctx.selectCount()
            .from(DSL.table("permissions"))
            .where(DSL.field("name").eq(name))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.iterator().next().getLong(0) > 0)
                    return@flatMap Future.failedFuture(DuplicateException("permission name already exists"))
                val id = Ulid.generate()
                val insertQuery = ctx.insertInto(
                        DSL.table("permissions"),
                        DSL.field("id"), DSL.field("name"), DSL.field("description"),
                        DSL.field("resource"), DSL.field("action")
                    )
                    .values(id, name, description, resource, action)
                    .returning(DSL.field("id"), DSL.field("name"), DSL.field("description"),
                        DSL.field("resource"), DSL.field("action"), DSL.field("created_at"))
                pool.preparedQuery(DatabaseConfig.sql(insertQuery))
                    .execute(DatabaseConfig.tuple(insertQuery))
                    .map { toPermissionJson(it.iterator().next()) }
            }
    }

    fun listPermissions(): Future<JsonArray> {
        val query = ctx.select(
                DSL.field("id"), DSL.field("name"), DSL.field("description"),
                DSL.field("resource"), DSL.field("action"), DSL.field("created_at")
            )
            .from(DSL.table("permissions"))
            .orderBy(DSL.field("resource").asc(), DSL.field("action").asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toPermissionJson(it)) }; a }
    }

    fun getPermission(id: String): Future<JsonObject> {
        val query = ctx.select(
                DSL.field("id"), DSL.field("name"), DSL.field("description"),
                DSL.field("resource"), DSL.field("action"), DSL.field("created_at")
            )
            .from(DSL.table("permissions"))
            .where(DSL.field("id").eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("permission not found"))
                else Future.succeededFuture(toPermissionJson(rows.iterator().next()))
            }
    }

    fun deletePermission(id: String): Future<Void> {
        val query = ctx.deleteFrom(DSL.table("permissions")).where(DSL.field("id").eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    // ────────────────────────────────────────────────────────────
    //  Role ↔ Permission
    // ────────────────────────────────────────────────────────────

    fun assignPermissionToRole(roleId: String, permissionId: String): Future<Void> {
        val query = ctx.insertInto(
                DSL.table("role_permissions"),
                DSL.field("role_id"), DSL.field("permission_id")
            )
            .values(roleId, permissionId)
            .onConflictDoNothing()
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    fun removePermissionFromRole(roleId: String, permissionId: String): Future<Void> {
        val query = ctx.deleteFrom(DSL.table("role_permissions"))
            .where(DSL.field("role_id").eq(roleId))
            .and(DSL.field("permission_id").eq(permissionId))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    fun getRolePermissions(roleId: String): Future<JsonArray> {
        val p = DSL.table("permissions").`as`("p")
        val rp = DSL.table("role_permissions").`as`("rp")
        val query = ctx.select(
                DSL.field("p.id"), DSL.field("p.name"), DSL.field("p.description"),
                DSL.field("p.resource"), DSL.field("p.action"), DSL.field("p.created_at")
            )
            .from(p)
            .join(rp).on(DSL.field("rp.permission_id").eq(DSL.field("p.id")))
            .where(DSL.field("rp.role_id").eq(roleId))
            .orderBy(DSL.field("p.resource").asc(), DSL.field("p.action").asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toPermissionJson(it)) }; a }
    }

    // ────────────────────────────────────────────────────────────
    //  RBAC assignments
    // ────────────────────────────────────────────────────────────

    fun assignRole(userId: String, roleId: String, scopeType: String = "global", scopeId: String = "0"): Future<Void> {
        val query = ctx.insertInto(
                DSL.table("rbac_assignments"),
                DSL.field("user_id"), DSL.field("role_id"), DSL.field("scope_type"), DSL.field("scope_id")
            )
            .values(userId, roleId, scopeType, scopeId)
            .onConflictDoNothing()
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    fun unassignRole(userId: String, roleId: String, scopeType: String = "global", scopeId: String = "0"): Future<Void> {
        val query = ctx.deleteFrom(DSL.table("rbac_assignments"))
            .where(DSL.field("user_id").eq(userId))
            .and(DSL.field("role_id").eq(roleId))
            .and(DSL.field("scope_type").eq(scopeType))
            .and(DSL.field("scope_id").eq(scopeId))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    fun getUserAssignments(userId: String): Future<JsonArray> {
        val ra = DSL.table("rbac_assignments").`as`("ra")
        val query = ctx.select(
                DSL.field("ra.user_id"), DSL.field("ra.role_id"), DSL.field("ra.scope_type"),
                DSL.field("ra.scope_id"), DSL.field("ra.created_at"),
                DSL.field("s.payload->>'name'").`as`("role_name")
            )
            .from(ra)
            .join(s).on(DSL.field("ra.role_id").eq(s.ID).and(s.CATEGORY.eq("role")))
            .where(DSL.field("ra.user_id").eq(userId))
            .orderBy(DSL.field("ra.scope_type").asc(), DSL.field("ra.scope_id").asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toAssignmentJson(it)) }; a }
    }

    // ────────────────────────────────────────────────────────────
    //  ReBAC relations
    // ────────────────────────────────────────────────────────────

    fun createRelation(subjectType: String, subjectId: String, relation: String, objectType: String, objectId: String): Future<Void> {
        val query = ctx.insertInto(
                DSL.table("rebac_relations"),
                DSL.field("subject_type"), DSL.field("subject_id"), DSL.field("relation"),
                DSL.field("object_type"), DSL.field("object_id")
            )
            .values(subjectType, subjectId, relation, objectType, objectId)
            .onConflictDoNothing()
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    fun deleteRelation(subjectType: String, subjectId: String, relation: String, objectType: String, objectId: String): Future<Void> {
        val query = ctx.deleteFrom(DSL.table("rebac_relations"))
            .where(DSL.field("subject_type").eq(subjectType))
            .and(DSL.field("subject_id").eq(subjectId))
            .and(DSL.field("relation").eq(relation))
            .and(DSL.field("object_type").eq(objectType))
            .and(DSL.field("object_id").eq(objectId))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    fun listRelations(subjectType: String? = null, subjectId: String? = null, objectType: String? = null, objectId: String? = null): Future<JsonArray> {
        val conditions = mutableListOf<Condition>()
        subjectType?.let { conditions.add(DSL.field("subject_type").eq(it)) }
        subjectId?.let { conditions.add(DSL.field("subject_id").eq(it)) }
        objectType?.let { conditions.add(DSL.field("object_type").eq(it)) }
        objectId?.let { conditions.add(DSL.field("object_id").eq(it)) }
        val query = ctx.select(
                DSL.field("subject_type"), DSL.field("subject_id"), DSL.field("relation"),
                DSL.field("object_type"), DSL.field("object_id"), DSL.field("created_at")
            )
            .from(DSL.table("rebac_relations"))
            .where(conditions)
            .orderBy(DSL.field("created_at").asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toRelationJson(it)) }; a }
    }

    // ────────────────────────────────────────────────────────────
    //  ABAC policies
    // ────────────────────────────────────────────────────────────

    fun createPolicy(resourceType: String, action: String, effect: String = "allow",
                     priority: Int = 0, conditionJson: JsonObject = JsonObject(), description: String = ""): Future<JsonObject> {
        val id = Ulid.generate()
        val query = ctx.insertInto(
                DSL.table("abac_policies"),
                DSL.field("id"), DSL.field("resource_type"), DSL.field("action"),
                DSL.field("effect"), DSL.field("priority"), DSL.field("condition_json"),
                DSL.field("description")
            )
            .values(id, resourceType, action, effect, priority, JSONB.valueOf(conditionJson.encode()), description)
            .returning(DSL.field("id"), DSL.field("resource_type"), DSL.field("action"),
                DSL.field("effect"), DSL.field("priority"), DSL.field("condition_json"),
                DSL.field("description"), DSL.field("created_at"), DSL.field("updated_at"))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { toPolicyJson(it.iterator().next()) }
    }

    fun listPolicies(): Future<JsonArray> {
        val query = ctx.select(
                DSL.field("id"), DSL.field("resource_type"), DSL.field("action"),
                DSL.field("effect"), DSL.field("priority"), DSL.field("condition_json"),
                DSL.field("description"), DSL.field("created_at"), DSL.field("updated_at")
            )
            .from(DSL.table("abac_policies"))
            .orderBy(DSL.field("priority").desc(), DSL.field("created_at").asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toPolicyJson(it)) }; a }
    }

    fun getPolicy(id: String): Future<JsonObject> {
        val query = ctx.select(
                DSL.field("id"), DSL.field("resource_type"), DSL.field("action"),
                DSL.field("effect"), DSL.field("priority"), DSL.field("condition_json"),
                DSL.field("description"), DSL.field("created_at"), DSL.field("updated_at")
            )
            .from(DSL.table("abac_policies"))
            .where(DSL.field("id").eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("policy not found"))
                else Future.succeededFuture(toPolicyJson(rows.iterator().next()))
            }
    }
    fun updatePolicy(id: String, resourceType: String?, action: String?, effect: String?,
                     priority: Int?, conditionJson: JsonObject?, description: String?): Future<JsonObject> {
        val map = mutableMapOf<org.jooq.Field<*>, Any?>()
        resourceType?.let { map[DSL.field("resource_type")] = it }
        action?.let { map[DSL.field("action")] = it }
        effect?.let { map[DSL.field("effect")] = it }
        priority?.let { map[DSL.field("priority")] = it }
        conditionJson?.let { map[DSL.field("condition_json")] = JSONB.valueOf(it.encode()) }
        description?.let { map[DSL.field("description")] = it }
        if (map.isEmpty()) return getPolicy(id)
        map[DSL.field("updated_at")] = DSL.now()

        val query = ctx.update(DSL.table("abac_policies"))
            .set(map)
            .where(DSL.field("id").eq(id))
            .returning(
                DSL.field("id"), DSL.field("resource_type"), DSL.field("action"),
                DSL.field("effect"), DSL.field("priority"), DSL.field("condition_json"),
                DSL.field("description"), DSL.field("created_at"), DSL.field("updated_at")
            )
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("policy not found"))
                else Future.succeededFuture(toPolicyJson(rows.iterator().next()))
            }
    }

    fun deletePolicy(id: String): Future<Void> {
        val query = ctx.deleteFrom(DSL.table("abac_policies")).where(DSL.field("id").eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query)).map { null }
    }

    // ────────────────────────────────────────────────────────────
    //  Row mappers
    // ────────────────────────────────────────────────────────────

    companion object {
        fun toRoleJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("name", (row.getValue("payload") as? JsonObject)?.getString("name") ?: "")
            .put("description", (row.getValue("payload") as? JsonObject)?.getString("description") ?: "")
            .put("code", row.getValue("code")?.toString() ?: "")
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())

        fun toPermissionJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("name", row.getValue("name")?.toString())
            .put("description", row.getValue("description")?.toString())
            .put("resource", row.getValue("resource")?.toString())
            .put("action", row.getValue("action")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())

        fun toAssignmentJson(row: Row) = JsonObject()
            .put("user_id", row.getValue("user_id")?.toString())
            .put("role_id", row.getValue("role_id")?.toString())
            .put("role_name", row.getValue("role_name")?.toString())
            .put("scope_type", row.getValue("scope_type")?.toString())
            .put("scope_id", row.getValue("scope_id")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())

        fun toRelationJson(row: Row) = JsonObject()
            .put("subject_type", row.getValue("subject_type")?.toString())
            .put("subject_id", row.getValue("subject_id")?.toString())
            .put("relation", row.getValue("relation")?.toString())
            .put("object_type", row.getValue("object_type")?.toString())
            .put("object_id", row.getValue("object_id")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())

        fun toPolicyJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("resource_type", row.getValue("resource_type")?.toString())
            .put("action", row.getValue("action")?.toString())
            .put("effect", row.getValue("effect")?.toString())
            .put("priority", row.getValue("priority") as? Int ?: 0)
            .put("condition_json", row.getValue("condition_json") as? JsonObject ?: JsonObject())
            .put("description", row.getValue("description")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }
}

private fun Any?.tryParseValue(): Any? {
    if (this == null) return null
    val s = toString()
    return when {
        s == "true" -> true
        s == "false" -> false
        s.toLongOrNull() != null -> s.toLong()
        s.toDoubleOrNull() != null -> s.toDouble()
        else -> s
    }
}

class DuplicateException(message: String) : Exception(message)
class NotFoundException(message: String) : Exception(message)
