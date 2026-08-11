package com.ovaphlow.crate.dining

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager

/**
 * 膳食营养模块 HTTP 路由集成测试（嵌入式 Vert.x 服务器 + aceso_test）。
 *
 * - /dining/v1 挂载 stub 认证（userId=tester），验证写路由业务行为；
 * - /dining-open/v1 不挂认证，验证写路由 401 兜底。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiningRoutesIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18426
        private const val FIXTURE_PREFIX = "sr-"

        private const val PATIENT_1 = "${FIXTURE_PREFIX}patient-1"
        private const val ENC_1 = "${FIXTURE_PREFIX}enc-1"
        private const val ENC_2 = "${FIXTURE_PREFIX}enc-2"
    }

    private var server: io.vertx.core.http.HttpServer? = null
    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        if (password.isBlank()) {
            ctx.failNow(IllegalStateException("PITCHFORK_DB_PASSWORD must be set"))
            return@setup
        }

        try {
            ensureTestDb()
            val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                stmt.execute("CREATE SCHEMA IF NOT EXISTS healthcare")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS healthcare.patients (
                        id VARCHAR(32) PRIMARY KEY,
                        name VARCHAR NOT NULL DEFAULT '',
                        gender VARCHAR NOT NULL DEFAULT '',
                        status VARCHAR DEFAULT 'ACTIVE'
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS healthcare.encounters (
                        id VARCHAR(32) PRIMARY KEY,
                        patient_id VARCHAR(32) NOT NULL,
                        encounter_type VARCHAR NOT NULL DEFAULT '',
                        status VARCHAR(20) DEFAULT 'ACTIVE',
                        admit_date TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
                // 清空本测试类的 fixture 数据，保证可重复运行
                stmt.execute("DELETE FROM dining.dining_meal_executions WHERE roster_item_id IN (SELECT id FROM dining.dining_roster_items WHERE patient_id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM dining.dining_roster_items WHERE patient_id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM dining.dining_diet_profiles WHERE patient_id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('$PATIENT_1', '路由测试长者', 'ACTIVE')")
                stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) VALUES ('$ENC_1', '$PATIENT_1', 'ELDERLY_CARE', 'ACTIVE', now())")
                stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) VALUES ('$ENC_2', '$PATIENT_1', 'ELDERLY_CARE', 'DISCHARGED', now())")
            }

            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)

            // stub 认证：写入 userId=tester
            val authHandler = io.vertx.core.Handler<RoutingContext> { rctx ->
                rctx.put("userId", "tester")
                rctx.next()
            }
            val diningRouter = DiningRoutes.create(vertx, pool, authHandler)
            val openRouter = DiningRoutes.create(vertx, pool, null)

            val rootRouter = Router.router(vertx)
            rootRouter.route("/dining/v1/*").subRouter(diningRouter)
            rootRouter.route("/dining-open/v1/*").subRouter(openRouter)
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
            if (::pool.isInitialized) pool.close()
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    private fun ensureTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'")
            if (!rs.next()) {
                conn.createStatement().execute("CREATE DATABASE $TEST_DB")
            }
        }
    }

    private fun send(
        vertx: Vertx,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        ctx: VertxTestContext,
        handler: (Int, JsonObject?) -> Unit,
    ) {
        vertx.createHttpClient().request(method, TEST_PORT, "127.0.0.1", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json")
                (if (body != null) req.send(Buffer.buffer(body.encode())) else req.send())
                    .compose { resp ->
                        resp.body().map { buf ->
                            val payload = try {
                                JsonObject(buf.toString())
                            } catch (_: Exception) {
                                null
                            }
                            Pair(resp.statusCode(), payload)
                        }
                    }
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded(), "request failed: ${ar.cause()?.message}")
                    handler(ar.result().first, ar.result().second)
                }
            }
    }

    // ========================================================================
    //  用例
    // ========================================================================

    /** 每个用例前清空 dining 表数据，保证用例间独立（healthcare fixture 常驻）。 */
    @BeforeEach
    fun resetDiningData() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("DELETE FROM dining.dining_meal_executions")
            stmt.execute("DELETE FROM dining.dining_roster_items")
            stmt.execute("DELETE FROM dining.dining_rosters")
            stmt.execute("DELETE FROM dining.dining_weekly_menu_items")
            stmt.execute("DELETE FROM dining.dining_weekly_menus")
            stmt.execute("DELETE FROM dining.dining_dishes")
            stmt.execute("DELETE FROM dining.dining_diet_profiles")
        }
    }

    @Test
    fun `空列表返回records空数组与total0`(vertx: Vertx, ctx: VertxTestContext) {
        send(vertx, HttpMethod.GET, "/dining/v1/diet-profiles", null, ctx) { status, body ->
            assertEquals(200, status)
            assertNotNull(body)
            assertEquals(0, body!!.getJsonArray("records").size())
            assertEquals(0L, body.getJsonObject("meta").getLong("total"))
            ctx.completeNow()
        }
    }

    @Test
    fun `写路由未认证_401`(vertx: Vertx, ctx: VertxTestContext) {
        // rosters/generate 业务处理器强制 userId，未挂认证中间件时必须 401
        send(vertx, HttpMethod.POST, "/dining-open/v1/rosters/generate",
            JsonObject().put("date", "2026-08-15").put("meal_time", "午餐"),
            ctx) { status, body ->
            assertEquals(401, status)
            assertEquals("authentication required", body?.getString("error"))
            ctx.completeNow()
        }
    }

    @Test
    fun `建档_非法餐食类型_400_错误响应结构`(vertx: Vertx, ctx: VertxTestContext) {
        send(vertx, HttpMethod.POST, "/dining/v1/diet-profiles",
            JsonObject().put("patient_id", PATIENT_1).put("encounter_id", ENC_1).put("meal_type", "西餐"),
            ctx) { status, body ->
            assertEquals(400, status)
            assertNotNull(body?.getString("error"))
            ctx.completeNow()
        }
    }

    @Test
    fun `建档_已离院入住_400`(vertx: Vertx, ctx: VertxTestContext) {
        send(vertx, HttpMethod.POST, "/dining/v1/diet-profiles",
            JsonObject().put("patient_id", PATIENT_1).put("encounter_id", ENC_2).put("meal_type", "普食"),
            ctx) { status, body ->
            assertEquals(400, status)
            assertNotNull(body?.getString("error"))
            ctx.completeNow()
        }
    }

    @Test
    fun `建档_生成_登记_统计_全链路`(vertx: Vertx, ctx: VertxTestContext) {
        val date = "2026-08-15"
        var rosterItemId = ""

        // 1. 建档 → 201
        send(vertx, HttpMethod.POST, "/dining/v1/diet-profiles",
            JsonObject()
                .put("patient_id", PATIENT_1)
                .put("encounter_id", ENC_1)
                .put("meal_type", "普食")
                .put("allergies", io.vertx.core.json.JsonArray().add("花生")),
            ctx) { status, body ->
            assertEquals(201, status)
            assertEquals("启用", body!!.getString("status"))
            assertEquals("ACTIVE", body.getString("encounter_status"))

            // 2. 生成配餐名单 → 名单含 1 人，餐食类型与忌口带出
            send(vertx, HttpMethod.POST, "/dining/v1/rosters/generate",
                JsonObject().put("date", date).put("meal_time", "午餐"),
                ctx) { status2, body2 ->
                assertEquals(200, status2)
                assertEquals(1, body2!!.getInteger("created"))
                val items = body2.getJsonObject("roster").getJsonArray("items")
                assertEquals(1, items.size())
                assertEquals("普食", items.getJsonObject(0).getString("meal_type"))
                assertEquals("花生", items.getJsonObject(0).getJsonArray("allergies").getString(0))
                rosterItemId = items.getJsonObject(0).getString("id")

                // 3. 就餐登记 → 200，幂等重复登记仍 200 且同一条目
                send(vertx, HttpMethod.POST, "/dining/v1/executions",
                    JsonObject().put("roster_item_id", rosterItemId).put("status", "正常"),
                    ctx) { status3, body3 ->
                    assertEquals(200, status3)
                    assertEquals("正常", body3!!.getString("status"))
                    assertEquals("tester", body3.getString("recorded_by"))
                    val executionId = body3.getString("id")

                    send(vertx, HttpMethod.POST, "/dining/v1/executions",
                        JsonObject().put("roster_item_id", rosterItemId).put("status", "拒食").put("remark", "不想吃"),
                        ctx) { status4, body4 ->
                        assertEquals(200, status4)
                        assertEquals("拒食", body4!!.getString("status"))
                        assertEquals(executionId, body4.getString("id"))

                        // 4. 统计：应就餐 1 人、就餐 0、拒食 1、就餐率 0
                        send(vertx, HttpMethod.GET,
                            "/dining/v1/statistics/meals?date_from=$date&date_to=$date",
                            null, ctx) { status5, body5 ->
                            assertEquals(200, status5)
                            val summary = body5!!.getJsonObject("summary")
                            assertEquals(1L, summary.getLong("expected_total"))
                            assertEquals(0L, summary.getLong("eaten_total"))
                            assertEquals(1L, body5.getJsonObject("by_status").getLong("拒食"))
                            assertEquals(0.0, summary.getDouble("dining_rate")!!, 0.001)
                            ctx.completeNow()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `统计_日期范围非法_400`(vertx: Vertx, ctx: VertxTestContext) {
        send(vertx, HttpMethod.GET,
            "/dining/v1/statistics/meals?date_from=2026-08-20&date_to=2026-08-10",
            null, ctx) { status, body ->
            assertEquals(400, status)
            assertNotNull(body?.getString("error"))
            ctx.completeNow()
        }
    }
}
