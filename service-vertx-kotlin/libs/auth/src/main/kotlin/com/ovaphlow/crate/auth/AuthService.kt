package com.ovaphlow.crate.auth

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.public_.tables.Users
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

data class LoginResult(val token: String, val user: JsonObject)
data class SignUpResult(val id: String, val userJson: JsonObject)
data class VerifyResult(val sub: String)

class AuthService(private val pool: Pool, private val jwtAuth: JWTAuth, private val ctx: DSLContext = DatabaseConfig.createDSL()) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val u = Users.USERS

    fun login(email: String, password: String, loginIp: String = ""): Future<LoginResult> {
        val query = ctx.selectFrom(u).where(u.EMAIL.eq(email))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    return@flatMap Future.failedFuture(InvalidCredentialsException())
                }
                val row = rows.iterator().next()
                val hash = row.getValue("password_hash") as String

                val bcrypt = at.favre.lib.crypto.bcrypt.BCrypt.verifyer()
                if (!bcrypt.verify(password.toCharArray(), hash).verified) {
                    return@flatMap Future.failedFuture(InvalidCredentialsException())
                }

                val userId = row.getValue("id") as String
                val activityJson = row.getValue("activity_info") as? JsonObject ?: JsonObject()
                val loginCount = activityJson.getInteger("login_count", 0) + 1

                val newActivity = JsonObject()
                    .put("login_count", loginCount)
                    .put("last_login_at", OffsetDateTime.now().toString())
                    .put("last_login_ip", loginIp)
                    .put("last_password_change", activityJson.getValue("last_password_change"))

                val updateQuery = ctx.update(u)
                    .set(u.ACTIVITY_INFO, JSONB.valueOf(newActivity.encode()))
                    .set(u.UPDATED_AT, OffsetDateTime.now())
                    .where(u.ID.eq(userId))

                pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                    .execute(DatabaseConfig.tuple(updateQuery))
                    .map {
                        val token = jwtAuth.generateToken(JsonObject().put("sub", userId).put("email", email))
                        LoginResult(token = token, user = toUserJson(row))
                    }
            }
    }

    fun signUp(email: String, password: String): Future<SignUpResult> {
        val countQuery = ctx.selectCount().from(u).where(u.EMAIL.eq(email))
        System.err.println("=== COUNT SQL: ${DatabaseConfig.sql(countQuery)}")
        System.err.println("=== COUNT BIND: ${countQuery.getBindValues()}")
        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { rows ->
                if (rows.iterator().next().getLong(0) > 0) {
                    return@flatMap Future.failedFuture(EmailAlreadyRegisteredException())
                }

                val hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, password.toCharArray())
                val now = OffsetDateTime.now()
                val id = Ulid.generate()

                val insertQuery = ctx.insertInto(
                    u, u.ID, u.EMAIL, u.USERNAME, u.PHONE, u.PASSWORD_HASH, u.USER_TYPE, u.STATUS, u.CREATED_AT, u.UPDATED_AT
                ).values(id, email, "", "", hash, "regular", "pending", now, now)
                    .returning(u.ID)
                System.err.println("=== INSERT SQL: ${DatabaseConfig.sql(insertQuery)}")
                System.err.println("=== INSERT BIND values: ${insertQuery.getBindValues()}")

                pool.preparedQuery(DatabaseConfig.sql(insertQuery))
                    .execute(DatabaseConfig.tuple(insertQuery))
                    .flatMap {
                        val getQuery = ctx.selectFrom(u).where(u.ID.eq(id))
                        pool.preparedQuery(DatabaseConfig.sql(getQuery))
                            .execute(DatabaseConfig.tuple(getQuery))
                    }
                    .map { userRows ->
                        val row = userRows.iterator().next()
                        SignUpResult(id = id, userJson = toUserJson(row))
                    }
            }
    }

    fun verify(token: String): Future<VerifyResult> {
        return jwtAuth.authenticate(JsonObject().put("token", token))
            .map { user ->
                VerifyResult(sub = user.principal().getString("sub"))
            }
    }

    companion object {
        fun toUserJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("email", row.getValue("email")?.toString())
                .put("username", row.getValue("username")?.toString())
                .put("phone", row.getValue("phone")?.toString())
                .put("user_type", row.getValue("user_type")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("security_info", row.getValue("security_info") as? JsonObject)
                .put("verification_info", row.getValue("verification_info") as? JsonObject)
                .put("password_reset_info", row.getValue("password_reset_info") as? JsonObject)
                .put("activity_info", row.getValue("activity_info") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }
}

class InvalidCredentialsException : Exception("invalid credentials")
class EmailAlreadyRegisteredException : Exception("email already registered")
