package com.ovaphlow.crate.users

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Users
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

class UsersService(private val pool: Pool, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(UsersService::class.java)
    private val u = Users.USERS

    fun list(search: String? = null, status: String? = null, limit: Int = 50, offset: Int = 0): Future<JsonArray> {
        val conditions = mutableListOf<org.jooq.Condition>()
        search?.let { conditions.add(u.USERNAME.like("%$it%").or(u.EMAIL.like("%$it%")).or(u.PHONE.like("%$it%"))) }
        status?.let { conditions.add(u.STATUS.eq(it)) }

        val query = ctx.select(u.ID, u.EMAIL, u.USERNAME, u.PHONE, u.USER_TYPE, u.STATUS, u.DEPT_ID, u.CREATED_AT, u.UPDATED_AT)
            .from(u)
            .where(conditions)
            .orderBy(u.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> val a = JsonArray(); rows.forEach { a.add(toJson(it)) }; a }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        val query = ctx.update(u)
            .set(u.STATUS, status)
            .set(u.UPDATED_AT, java.time.OffsetDateTime.now())
            .where(u.ID.eq(id))
            .returning(u.ID, u.EMAIL, u.USERNAME, u.PHONE, u.USER_TYPE, u.STATUS, u.DEPT_ID, u.CREATED_AT, u.UPDATED_AT)
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("user not found"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    companion object {
        fun toJson(row: Row) = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("email", row.getValue("email")?.toString())
            .put("username", row.getValue("username")?.toString())
            .put("phone", row.getValue("phone")?.toString())
            .put("user_type", row.getValue("user_type")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("dept_id", row.getValue("dept_id")?.toString() ?: "")
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }
}

class NotFoundException(message: String) : Exception(message)
