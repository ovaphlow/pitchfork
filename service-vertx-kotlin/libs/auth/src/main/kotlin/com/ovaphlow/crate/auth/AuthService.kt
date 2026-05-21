package com.ovaphlow.crate.auth

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

data class LoginResult(val token: String, val user: JsonObject)
data class SignUpResult(val id: String, val userJson: JsonObject)
data class VerifyResult(val sub: String)

class AuthService(private val pool: Pool, private val jwtAuth: JWTAuth) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    fun login(email: String, password: String, loginIp: String = ""): Future<LoginResult> {
        return pool.preparedQuery("SELECT * FROM users WHERE email = \$1")
            .execute(Tuple.of(email))
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

                pool.preparedQuery("UPDATE users SET activity_info = \$1::jsonb, updated_at = now() WHERE id = \$2")
                    .execute(Tuple.of(newActivity, userId))
                    .map {
                        val token = jwtAuth.generateToken(JsonObject().put("sub", userId).put("email", email))
                        LoginResult(token = token, user = toUserJson(row))
                    }
            }
    }

    fun signUp(email: String, password: String): Future<SignUpResult> {
        return pool.preparedQuery("SELECT count(*) FROM users WHERE email = \$1")
            .execute(Tuple.of(email))
            .flatMap { rows ->
                if (rows.iterator().next().getLong(0) > 0) {
                    return@flatMap Future.failedFuture(EmailAlreadyRegisteredException())
                }

                val hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, password.toCharArray())
                val now = OffsetDateTime.now()
                val id = Ulid.generate()

                val insertSql = "INSERT INTO users (id, email, username, phone, password_hash, user_type, status, created_at, updated_at) VALUES (\$1, \$2, '', '', \$3, 'regular', 'pending', \$4, \$4) RETURNING id"
                pool.preparedQuery(insertSql)
                    .execute(Tuple.of(id, email, hash, now))
                    .flatMap {
                        pool.preparedQuery("SELECT * FROM users WHERE id = \$1")
                            .execute(Tuple.of(id))
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