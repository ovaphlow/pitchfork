package com.ovaphlow.crate.settings

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Settings
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class SettingsService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(SettingsService::class.java)
    private val s = Settings.SETTINGS

    fun listByCategory(category: String): Future<JsonArray> {
        val query = ctx.select(
                s.ID, s.CATEGORY, s.CODE, s.PARENT_CODE, s.PAYLOAD,
                org.jooq.impl.DSL.field("created_at"),
                org.jooq.impl.DSL.field("updated_at")
            )
            .from(s)
            .where(s.CATEGORY.eq(category))
            .orderBy(s.CODE.asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toSettingJson(it)) }; a }
    }

    fun createSetting(category: String, code: String, name: String): Future<JsonObject> {
        val checkQuery = ctx.selectOne().from(s).where(s.CATEGORY.eq(category)).and(s.CODE.eq(code))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.iterator().hasNext()) {
                    Future.failedFuture(IllegalArgumentException("code '$code' already exists"))
                } else {
                    val id = Ulid.generate()
                    val payload = JsonObject().put("name", name)
                    val query = ctx.insertInto(s, s.ID, s.CATEGORY, s.CODE, s.PAYLOAD)
                        .values(id, category, code, JSONB.valueOf(payload.encode()))
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map { payload.put("id", id).put("code", code) }
                }
            }
    }

    fun updateSetting(category: String, code: String, name: String): Future<JsonObject> {
        val payload = JsonObject().put("name", name)
        val query = ctx.update(s)
            .set(s.PAYLOAD, JSONB.valueOf(payload.encode()))
            .set(org.jooq.impl.DSL.field("updated_at"), LocalDateTime.now())
            .where(s.CATEGORY.eq(category)).and(s.CODE.eq(code))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("setting not found"))
                else Future.succeededFuture(payload.put("code", code))
            }
    }

    fun deleteSetting(category: String, code: String): Future<Void> {
        val query = ctx.deleteFrom(s).where(s.CATEGORY.eq(category)).and(s.CODE.eq(code))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null }
    }

    fun listDepartments(): Future<JsonArray> {
        val query = ctx.select(
                s.ID, s.CATEGORY, s.CODE, s.PARENT_CODE, s.PAYLOAD,
                org.jooq.impl.DSL.field("created_at"),
                org.jooq.impl.DSL.field("updated_at")
            )
            .from(s)
            .where(s.CATEGORY.eq("department"))
            .orderBy(org.jooq.impl.DSL.field("created_at").asc())
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toDeptJson(it)) }; a }
    }

    fun createDepartment(name: String, code: String, parentCode: String = "", description: String = ""): Future<JsonObject> {
        val checkQuery = ctx.selectOne().from(s).where(s.CATEGORY.eq("department")).and(s.CODE.eq(code))
        return pool.preparedQuery(DatabaseConfig.sql(checkQuery))
            .execute(DatabaseConfig.tuple(checkQuery))
            .flatMap { rows ->
                if (rows.iterator().hasNext()) {
                    Future.failedFuture(IllegalArgumentException("部门编码 '$code' 已存在"))
                } else {
                    val id = Ulid.generate()
                    val payload = JsonObject()
                        .put("name", name)
                        .put("description", description)
                    val now = LocalDateTime.now().toString()
                    val query = ctx.insertInto(s, s.ID, s.CATEGORY, s.CODE, s.PARENT_CODE, s.PAYLOAD)
                        .values(id, "department", code, parentCode, JSONB.valueOf(payload.encode()))
                    pool.preparedQuery(DatabaseConfig.sql(query))
                        .execute(DatabaseConfig.tuple(query))
                        .map {
                            JsonObject()
                                .put("id", id)
                                .put("category", "department")
                                .put("code", code)
                                .put("parent_code", parentCode)
                                .put("root_code", "")
                                .put("payload", payload)
                                .put("created_at", now)
                                .put("updated_at", now)
                        }
                }
            }
    }

    fun updateDepartment(id: String, name: String? = null, code: String? = null, parentCode: String? = null, description: String? = null): Future<JsonObject> {
        return getDepartment(id).flatMap { existing ->
            val currentPayload = existing.getJsonObject("payload", JsonObject())
            val mergedPayload = JsonObject()
            val payloadName = name ?: currentPayload.getString("name", "")
            val payloadDesc = description ?: currentPayload.getString("description", "")
            mergedPayload.put("name", payloadName).put("description", payloadDesc)

            val newCode = code ?: existing.getString("code", "")
            val newParentCode = parentCode ?: existing.getString("parent_code", "")

            val q1 = ctx.update(s).set(org.jooq.impl.DSL.field("updated_at"), LocalDateTime.now())
                .set(s.PAYLOAD, JSONB.valueOf(mergedPayload.encode()))
            val q2 = if (code != null) q1.set(s.CODE, code) else q1
            val q3 = if (parentCode != null) q2.set(s.PARENT_CODE, parentCode) else q2
            val query = q3.where(s.ID.eq(id)).and(s.CATEGORY.eq("department"))

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .map {
                    existing
                        .put("code", newCode)
                        .put("parent_code", newParentCode)
                        .put("payload", mergedPayload)
                        .put("updated_at", LocalDateTime.now().toString())
                }
        }
    }

    private fun getDepartment(id: String): Future<JsonObject> {
        val query = ctx.select(
                s.ID, s.CATEGORY, s.CODE, s.PARENT_CODE, s.PAYLOAD,
                org.jooq.impl.DSL.field("created_at"),
                org.jooq.impl.DSL.field("updated_at")
            )
            .from(s).where(s.ID.eq(id)).and(s.CATEGORY.eq("department"))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("department not found"))
                else Future.succeededFuture(toDeptJson(rows.iterator().next()))
            }
    }

    fun deleteDepartment(id: String): Future<Void> {
        val query = ctx.deleteFrom(s).where(s.ID.eq(id)).and(s.CATEGORY.eq("department"))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null }
    }

    companion object {
        fun toSettingJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("category", row.getValue("category")?.toString())
            .put("code", row.getValue("code")?.toString())
            .put("payload", row.getValue("payload") as? JsonObject ?: JsonObject())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())

        fun toDeptJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("category", row.getValue("category")?.toString())
            .put("code", row.getValue("code")?.toString())
            .put("parent_code", row.getValue("parent_code")?.toString() ?: "")
            .put("payload", row.getValue("payload") as? JsonObject ?: JsonObject())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }
}

class NotFoundException(message: String) : Exception(message)
