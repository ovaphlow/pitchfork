package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate

/**
 * TaskExecutionRoutes 逾期参数校验的 HTTP 路由集成测试。
 *
 * 启动嵌入式 Vert.x HTTP 服务器，用 Vert.x HttpClient 发起真实 HTTP 请求验证：
 *   - overdue 格式错误 → 400
 *   - overdue=true + 终态 status → 400
 *   - overdue=true + PENDING/IN_PROGRESS → 正常请求
 *
 * 依赖真实的 PostgreSQL 数据库（与集成测试共享 aceso_test）。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionRoutesOverdueTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18422
        private val BASE_PATH = "/nursing/v1/executions/today"
    }

    private var server: io.vertx.core.http.HttpServer? = null

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        val host = System.getProperty("integration.db.host", "localhost")
        val port = System.getProperty("integration.db.port", "5432")
        val user = System.getProperty("integration.db.user", "ovaphlow")

        try {
            ensureTestDb(host, port, user)
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            ensureHealthcarePatientsTable(host, port, user)
            val pool = DatabaseConfig.createPool(vertx, dbConfig)

            val nursingRouter = NursingRoutes.create(vertx, pool)
            // 挂载到 /nursing/v1/ 子路径下，与生产环境一致
            val rootRouter = Router.router(vertx)
            rootRouter.route("/nursing/v1/*").subRouter(nursingRouter)
            vertx.createHttpServer()
                .requestHandler(rootRouter)
                .listen(TEST_PORT)
                .onComplete { ar ->
                    if (ar.succeeded()) {
                        server = ar.result()
                        ctx.completeNow()
                    } else {
                        ctx.failNow(ar.cause())
                    }
                }
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    private fun ensureTestDb(host: String, port: String, user: String) {
        val password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""
        val jdbcUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'"
            )
            if (!rs.next()) {
                conn.createStatement().execute("CREATE DATABASE $TEST_DB")
            }
        }
    }

    private fun ensureHealthcarePatientsTable(host: String, port: String, user: String) {
        val password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS healthcare")
            conn.createStatement().execute(
                """
                CREATE TABLE IF NOT EXISTS healthcare.patients (
                    id VARCHAR(32) PRIMARY KEY,
                    name VARCHAR NOT NULL DEFAULT '',
                    status VARCHAR DEFAULT 'ACTIVE'
                )
                """.trimIndent()
            )
        }
    }

    @Test
    fun `overdue参数非法值返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=invalid")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(400, resp.statusCode(), "非法 overdue 值应返回 400")
                            val json = JsonObject(body)
                            assertEquals("overdue must be true or false", json.getString("error"))
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `overdue=true与终态COMPLETED组合返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=true&status=COMPLETED")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(400, resp.statusCode())
                            val json = JsonObject(body)
                            assertEquals("overdue cannot be combined with terminal status", json.getString("error"))
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `overdue=true与终态SKIPPED组合返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=true&status=SKIPPED")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    ctx.verify {
                        assertEquals(400, ar.result().statusCode())
                        ctx.completeNow()
                    }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `overdue=true与终态CANCELLED组合返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=true&status=CANCELLED")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    ctx.verify {
                        assertEquals(400, ar.result().statusCode())
                        ctx.completeNow()
                    }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `overdue=true与PENDING可正常组合`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=true&status=PENDING")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(200, resp.statusCode(), "overdue=true + PENDING 应返回 200，响应：$body")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `overdue=true与IN_PROGRESS可正常组合`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=true&status=IN_PROGRESS")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(200, resp.statusCode(), "overdue=true + IN_PROGRESS 应返回 200，响应：$body")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `不传overdue时正常返回含overdue字段`(vertx: Vertx, ctx: VertxTestContext) {
        val today = LocalDate.now().toString()
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?date=$today")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(200, resp.statusCode(), "正常请求应返回 200，响应：$body")
                            val json = JsonObject(body)
                            val meta = json.getJsonObject("meta")
                            assertNotNull(meta, "meta 应为非 null")
                            assertTrue(meta!!.containsKey("overdue_total"), "meta 应包含 overdue_total")
                            assertNotNull(meta.getInteger("overdue_total"), "overdue_total 应为非 null")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `overdue=false时正常返回`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "${BASE_PATH}?overdue=false")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(200, resp.statusCode(), "overdue=false 应返回 200，响应：$body")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }
}
