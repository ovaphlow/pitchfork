package com.ovaphlow.crate.permission

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory

data class CheckResult(val allowed: Boolean, val reason: String = "", val engine: String = "")

class PermissionService(private val pool: Pool) {

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
        return pool.preparedQuery("""
            SELECT 1 FROM rbac_assignments ra
            JOIN role_permissions rp ON rp.role_id = ra.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE ra.user_id = $1
              AND (p.resource = $2 OR p.resource = '*')
              AND (p.action = $3 OR p.action = '*')
            LIMIT 1
        """.trimIndent())
            .execute(Tuple.of(userId, resourceType, action))
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
        return pool.preparedQuery("""
            SELECT rr.relation FROM rebac_relations rr
            WHERE rr.object_type = $1 AND rr.object_id = $2
              AND (
                (rr.subject_type = 'user' AND rr.subject_id = $3)
                OR
                (rr.subject_type = 'team' AND rr.subject_id IN (
                  SELECT t.subject_id FROM rebac_relations t
                  WHERE t.subject_type = 'user' AND t.subject_id = $3
                    AND t.relation = 'member' AND t.object_type = 'team'
                ))
              )
        """.trimIndent())
            .execute(Tuple.of(objectType, objectId, userId))
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
        return pool.preparedQuery("""
            SELECT * FROM abac_policies
            WHERE (resource_type = $1 OR resource_type = '*')
              AND (action = $2 OR action = '*')
              AND effect = $3
            ORDER BY priority DESC
        """.trimIndent())
            .execute(Tuple.of(resourceType, action, effect))
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
            return list.all { evaluateConditionTree(it as JsonObject, ctx) }
        }
        if (cond.containsKey("any")) {
            val list = cond.getJsonArray("any")
            if (list == null || list.size() == 0) return true
            return list.any { evaluateConditionTree(it as JsonObject, ctx) }
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
        return pool.preparedQuery("SELECT attr_key, attr_value FROM user_attributes WHERE user_id = $1")
            .execute(Tuple.of(userId))
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
        return pool.preparedQuery("DELETE FROM user_attributes WHERE user_id = $1")
            .execute(Tuple.of(userId))
            .flatMap {
                val futures = mutableListOf<Future<*>>()
                attrs.fieldNames().forEach { key ->
                    val value = attrs.getValue(key)?.toString() ?: ""
                    futures.add(
                        pool.preparedQuery("INSERT INTO user_attributes (user_id, attr_key, attr_value) VALUES ($1, $2, $3) ON CONFLICT (user_id, attr_key) DO UPDATE SET attr_value = $3")
                            .execute(Tuple.of(userId, key, value))
                    )
                }
                if (futures.isEmpty()) Future.succeededFuture()
                else io.vertx.core.CompositeFuture.all(futures).map { null }
            }
    }

    fun getUserAttributes(userId: String): Future<JsonObject> = resolveUserAttrs(userId)

    fun deleteUserAttribute(userId: String, key: String): Future<Void> {
        return pool.preparedQuery("DELETE FROM user_attributes WHERE user_id = $1 AND attr_key = $2")
            .execute(Tuple.of(userId, key)).map { null }
    }

    // ────────────────────────────────────────────────────────────
    //  Roles CRUD
    // ────────────────────────────────────────────────────────────

    fun createRole(name: String, description: String): Future<JsonObject> {
        return pool.preparedQuery("SELECT count(*) FROM roles WHERE name = $1")
            .execute(Tuple.of(name))
            .flatMap { rows ->
                if (rows.iterator().next().getLong(0) > 0)
                    return@flatMap Future.failedFuture(DuplicateException("role name already exists"))
                val id = Ulid.generate()
                pool.preparedQuery("INSERT INTO roles (id, name, description) VALUES ($1, $2, $3) RETURNING *")
                    .execute(Tuple.of(id, name, description))
                    .map { toRoleJson(it.iterator().next()) }
            }
    }

    fun listRoles(): Future<JsonArray> = pool.preparedQuery("SELECT * FROM roles ORDER BY created_at")
        .execute().map { rows -> val a = JsonArray(); rows.forEach { a.add(toRoleJson(it)) }; a }

    fun getRole(id: String): Future<JsonObject> = pool.preparedQuery("SELECT * FROM roles WHERE id = $1")
        .execute(Tuple.of(id)).flatMap { rows ->
            if (rows.size() == 0) Future.failedFuture(NotFoundException("role not found"))
            else Future.succeededFuture(toRoleJson(rows.iterator().next()))
        }

    fun updateRole(id: String, name: String?, description: String?): Future<JsonObject> {
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any>()
        var idx = 1
        name?.let { sets.add("name = $${idx++}"); params.add(it) }
        description?.let { sets.add("description = $${idx++}"); params.add(it) }
        if (sets.isEmpty()) return getRole(id)
        sets.add("updated_at = now()")
        params.add(id)
        val sql = "UPDATE roles SET ${sets.joinToString(", ")} WHERE id = $${idx} RETURNING *"
        val tuple = Tuple.tuple(); params.forEach { tuple.addValue(it) }
        return pool.preparedQuery(sql).execute(tuple).flatMap { rows ->
            if (rows.size() == 0) Future.failedFuture(NotFoundException("role not found"))
            else Future.succeededFuture(toRoleJson(rows.iterator().next()))
        }
    }

    fun deleteRole(id: String): Future<Void> =
        pool.preparedQuery("DELETE FROM roles WHERE id = $1").execute(Tuple.of(id)).map { null }

    // ────────────────────────────────────────────────────────────
    //  Permissions CRUD
    // ────────────────────────────────────────────────────────────

    fun createPermission(name: String, description: String, resource: String, action: String): Future<JsonObject> {
        return pool.preparedQuery("SELECT count(*) FROM permissions WHERE name = $1")
            .execute(Tuple.of(name)).flatMap { rows ->
                if (rows.iterator().next().getLong(0) > 0)
                    return@flatMap Future.failedFuture(DuplicateException("permission name already exists"))
                val id = Ulid.generate()
                pool.preparedQuery("INSERT INTO permissions (id, name, description, resource, action) VALUES ($1, $2, $3, $4, $5) RETURNING *")
                    .execute(Tuple.of(id, name, description, resource, action))
                    .map { toPermissionJson(it.iterator().next()) }
            }
    }

    fun listPermissions(): Future<JsonArray> =
        pool.preparedQuery("SELECT * FROM permissions ORDER BY resource, action").execute()
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toPermissionJson(it)) }; a }

    fun getPermission(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT * FROM permissions WHERE id = $1").execute(Tuple.of(id)).flatMap { rows ->
            if (rows.size() == 0) Future.failedFuture(NotFoundException("permission not found"))
            else Future.succeededFuture(toPermissionJson(rows.iterator().next()))
        }

    fun deletePermission(id: String): Future<Void> =
        pool.preparedQuery("DELETE FROM permissions WHERE id = $1").execute(Tuple.of(id)).map { null }

    // ────────────────────────────────────────────────────────────
    //  Role ↔ Permission
    // ────────────────────────────────────────────────────────────

    fun assignPermissionToRole(roleId: String, permissionId: String): Future<Void> =
        pool.preparedQuery("INSERT INTO role_permissions VALUES ($1, $2) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(roleId, permissionId)).map { null }

    fun removePermissionFromRole(roleId: String, permissionId: String): Future<Void> =
        pool.preparedQuery("DELETE FROM role_permissions WHERE role_id = $1 AND permission_id = $2")
            .execute(Tuple.of(roleId, permissionId)).map { null }

    fun getRolePermissions(roleId: String): Future<JsonArray> =
        pool.preparedQuery("""
            SELECT p.* FROM permissions p JOIN role_permissions rp ON rp.permission_id = p.id
            WHERE rp.role_id = $1 ORDER BY p.resource, p.action
        """.trimIndent()).execute(Tuple.of(roleId))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toPermissionJson(it)) }; a }

    // ────────────────────────────────────────────────────────────
    //  RBAC assignments
    // ────────────────────────────────────────────────────────────

    fun assignRole(userId: String, roleId: String, scopeType: String = "global", scopeId: String = "0"): Future<Void> =
        pool.preparedQuery("INSERT INTO rbac_assignments (user_id, role_id, scope_type, scope_id) VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(userId, roleId, scopeType, scopeId)).map { null }

    fun unassignRole(userId: String, roleId: String, scopeType: String = "global", scopeId: String = "0"): Future<Void> =
        pool.preparedQuery("DELETE FROM rbac_assignments WHERE user_id = $1 AND role_id = $2 AND scope_type = $3 AND scope_id = $4")
            .execute(Tuple.of(userId, roleId, scopeType, scopeId)).map { null }

    fun getUserAssignments(userId: String): Future<JsonArray> =
        pool.preparedQuery("""
            SELECT ra.*, r.name AS role_name FROM rbac_assignments ra
            JOIN roles r ON r.id = ra.role_id WHERE ra.user_id = $1 ORDER BY ra.scope_type, ra.scope_id
        """.trimIndent()).execute(Tuple.of(userId))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toAssignmentJson(it)) }; a }

    // ────────────────────────────────────────────────────────────
    //  ReBAC relations
    // ────────────────────────────────────────────────────────────

    fun createRelation(subjectType: String, subjectId: String, relation: String, objectType: String, objectId: String): Future<Void> =
        pool.preparedQuery("INSERT INTO rebac_relations VALUES ($1, $2, $3, $4, $5) ON CONFLICT DO NOTHING")
            .execute(Tuple.of(subjectType, subjectId, relation, objectType, objectId)).map { null }

    fun deleteRelation(subjectType: String, subjectId: String, relation: String, objectType: String, objectId: String): Future<Void> =
        pool.preparedQuery("DELETE FROM rebac_relations WHERE subject_type = $1 AND subject_id = $2 AND relation = $3 AND object_type = $4 AND object_id = $5")
            .execute(Tuple.of(subjectType, subjectId, relation, objectType, objectId)).map { null }

    fun listRelations(subjectType: String? = null, subjectId: String? = null, objectType: String? = null, objectId: String? = null): Future<JsonArray> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        var idx = 1
        subjectType?.let { clauses.add("subject_type = $${idx++}"); params.add(it) }
        subjectId?.let { clauses.add("subject_id = $${idx++}"); params.add(it) }
        objectType?.let { clauses.add("object_type = $${idx++}"); params.add(it) }
        objectId?.let { clauses.add("object_id = $${idx++}"); params.add(it) }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val tuple = Tuple.tuple(); params.forEach { tuple.addValue(it) }
        return pool.preparedQuery("SELECT * FROM rebac_relations $where ORDER BY created_at").execute(tuple)
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toRelationJson(it)) }; a }
    }

    // ────────────────────────────────────────────────────────────
    //  ABAC policies
    // ────────────────────────────────────────────────────────────

    fun createPolicy(resourceType: String, action: String, effect: String = "allow",
                     priority: Int = 0, conditionJson: JsonObject = JsonObject(), description: String = ""): Future<JsonObject> {
        val id = Ulid.generate()
        return pool.preparedQuery(
            "INSERT INTO abac_policies (id, resource_type, action, effect, priority, condition_json, description) VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7) RETURNING *"
        ).execute(Tuple.of(id, resourceType, action, effect, priority, conditionJson, description))
            .map { toPolicyJson(it.iterator().next()) }
    }

    fun listPolicies(): Future<JsonArray> =
        pool.preparedQuery("SELECT * FROM abac_policies ORDER BY priority DESC, created_at").execute()
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toPolicyJson(it)) }; a }

    fun getPolicy(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT * FROM abac_policies WHERE id = $1").execute(Tuple.of(id)).flatMap { rows ->
            if (rows.size() == 0) Future.failedFuture(NotFoundException("policy not found"))
            else Future.succeededFuture(toPolicyJson(rows.iterator().next()))
        }

    fun updatePolicy(id: String, resourceType: String?, action: String?, effect: String?,
                     priority: Int?, conditionJson: JsonObject?, description: String?): Future<JsonObject> {
        val sets = mutableListOf<String>()
        val params = mutableListOf<Any>()
        var idx = 1
        resourceType?.let { sets.add("resource_type = $${idx++}"); params.add(it) }
        action?.let { sets.add("action = $${idx++}"); params.add(it) }
        effect?.let { sets.add("effect = $${idx++}"); params.add(it) }
        priority?.let { sets.add("priority = $${idx++}"); params.add(it) }
        conditionJson?.let { sets.add("condition_json = $${idx++}::jsonb"); params.add(it) }
        description?.let { sets.add("description = $${idx++}"); params.add(it) }
        if (sets.isEmpty()) return getPolicy(id)
        sets.add("updated_at = now()")
        params.add(id)
        val sql = "UPDATE abac_policies SET ${sets.joinToString(", ")} WHERE id = $${idx} RETURNING *"
        val tuple = Tuple.tuple(); params.forEach { tuple.addValue(it) }
        return pool.preparedQuery(sql).execute(tuple).flatMap { rows ->
            if (rows.size() == 0) Future.failedFuture(NotFoundException("policy not found"))
            else Future.succeededFuture(toPolicyJson(rows.iterator().next()))
        }
    }

    fun deletePolicy(id: String): Future<Void> =
        pool.preparedQuery("DELETE FROM abac_policies WHERE id = $1").execute(Tuple.of(id)).map { null }

    // ────────────────────────────────────────────────────────────
    //  Row mappers
    // ────────────────────────────────────────────────────────────

    companion object {
        fun toRoleJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("name", row.getValue("name")?.toString())
            .put("description", row.getValue("description")?.toString())
            .put("is_system", row.getValue("is_system") as? Boolean ?: false)
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
