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
 * TaskExecutionRoutes /statistics 的 HTTP 路由集成测试。
 *
 * 启动嵌入式 Vert.x HTTP 服务器，用 Vert.x HttpClient 发起真实 HTTP 请求验证：
 *   - 缺失参数 → 400
 *   - 非法日期格式 → 400
 *   - 倒置范围 → 400
 *   - 超过 31 天范围 → 400
 *   - 合法请求返回 200
 *
 * 依赖真实的 PostgreSQL 数据库（与集成测试共享 aceso_test）。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionRoutesStatisticsTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18423
        private val BASE_PATH = "/nursing/v1/executions/statistics"
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
            try {
                DatabaseConfig.migrate(dbConfig)
            } catch (e: Exception) {
                val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
                DriverManager.getConnection(jdbcUrl, user, System.getenv("PITCHFORK_DB_PASSWORD") ?: "").use { conn ->
                    val stmt = conn.createStatement()
                    stmt.execute("SET search_path TO nursing, public")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD COLUMN IF NOT EXISTS encounter_id VARCHAR(32)")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods DROP CONSTRAINT IF EXISTS nursing_service_periods_service_type_check")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD CONSTRAINT nursing_service_periods_service_type_check CHECK (service_type IN ('HOME_CARE', 'COMMUNITY_CARE', 'HOSPICE', 'ELDERLY_CARE'))")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD CONSTRAINT chk_elderly_care_encounter_link CHECK ((service_type = 'ELDERLY_CARE' AND encounter_id IS NOT NULL) OR (service_type <> 'ELDERLY_CARE' AND encounter_id IS NULL))")
                    stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_nursing_service_periods_encounter_id ON nursing.nursing_service_periods (encounter_id) WHERE encounter_id IS NOT NULL")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_period_encounter_status ON nursing.nursing_service_periods (encounter_id, status) WHERE encounter_id IS NOT NULL")
                }
            }
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
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    private fun assertErrorResponse(respCode: Int, body: String, expectedMsg: String) {
        assertEquals(respCode, 400)
        val json = JsonObject(body)
        assertEquals(expectedMsg, json.getString("error"))
    }

    @Test
    fun `date_from缺失返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_to=2026-07-30")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "date_from is required")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `date_to缺失返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-30")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "date_to is required")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `非法日期格式返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=invalid&date_to=2026-07-30")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "invalid date format, expected YYYY-MM-DD")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `倒置范围返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-30&date_to=2026-07-28")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "date_from must not be after date_to")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `超过31天范围返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-08-02")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "date range must not exceed 31 days")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `31天闭区间边界允许`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-07-31")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(200, resp.statusCode(), "31 个闭区间日历日应返回 200")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `32天闭区间边界返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-08-01")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "date range must not exceed 31 days")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `非法limit返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-07-31&limit=abc")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "limit must be a non-negative integer")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `负数limit返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-07-31&limit=-1")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "limit must be a non-negative integer")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `非法offset返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-07-31&offset=xyz")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "offset must be a non-negative integer")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `负数offset返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=2026-07-01&date_to=2026-07-31&offset=-5")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertErrorResponse(resp.statusCode(), body.toString(), "offset must be a non-negative integer")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `合法请求返回200包含records和meta`(vertx: Vertx, ctx: VertxTestContext) {
        val today = LocalDate.now().toString()
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=$today&date_to=$today")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    val resp = ar.result()
                    resp.body().onSuccess { body ->
                        ctx.verify {
                            assertEquals(200, resp.statusCode(), "合法请求应返回 200")
                            val json = JsonObject(body)
                            assertNotNull(json.getJsonArray("records"), "records 不应为 null")
                            val meta = json.getJsonObject("meta")
                            assertNotNull(meta, "meta 不应为 null")
                            assertTrue(meta!!.containsKey("scheduled_total"), "meta 应包含 scheduled_total")
                            assertTrue(meta.containsKey("date_from"), "meta 应包含 date_from")
                            assertTrue(meta.containsKey("date_to"), "meta 应包含 date_to")
                            ctx.completeNow()
                        }
                    }.onFailure { ctx.failNow(it) }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `executor参数正确传递`(vertx: Vertx, ctx: VertxTestContext) {
        val today = LocalDate.now().toString()
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=$today&date_to=$today&executor=test-executor")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    ctx.verify {
                        assertEquals(200, ar.result().statusCode(), "带 executor 参数应返回 200")
                        ctx.completeNow()
                    }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @Test
    fun `limit和offset不改变meta汇总值`(vertx: Vertx, ctx: VertxTestContext) {
        val today = LocalDate.now().toString()
        val client = vertx.createHttpClient()
        // 先获取全量
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=$today&date_to=$today&limit=100")
            .compose { fullReq -> fullReq.send() }
            .compose { fullResp ->
                fullResp.body().compose { fullBody ->
                    val fullJson = JsonObject(fullBody)
                    val fullMeta = fullJson.getJsonObject("meta")
                    val fullScheduled = fullMeta?.getLong("scheduled_total") ?: 0L

                    // 带 limit=1 获取，meta 汇总应不变
                    client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=$today&date_to=$today&limit=1")
                        .compose { limitReq -> limitReq.send() }
                        .map { limitResp ->
                            limitResp.body().onSuccess { limitBody ->
                                ctx.verify {
                                    assertEquals(200, limitResp.statusCode())
                                    val limitJson = JsonObject(limitBody)
                                    val limitMeta = limitJson.getJsonObject("meta")
                                    assertEquals(fullScheduled, limitMeta?.getLong("scheduled_total"),
                                        "limit 不应改变 meta 汇总值")
                                    ctx.completeNow()
                                }
                            }
                        }
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `period_id参数正确传递`(vertx: Vertx, ctx: VertxTestContext) {
        val today = LocalDate.now().toString()
        val client = vertx.createHttpClient()
        client.request(HttpMethod.GET, TEST_PORT, "localhost", "$BASE_PATH?date_from=$today&date_to=$today&period_id=test-period")
            .compose { req -> req.send() }
            .onComplete { ar ->
                client.close()
                if (ar.succeeded()) {
                    ctx.verify {
                        assertEquals(200, ar.result().statusCode(), "带 period_id 参数应返回 200")
                        ctx.completeNow()
                    }
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }
}
