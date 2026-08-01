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

/**
 * ServicePeriodRoutes 养老入住补建周期路由的嵌入式 HTTP 测试。
 *
 * 启动嵌入式 Vert.x HTTP 服务器，用 Vert.x HttpClient 发起真实 HTTP 请求验证：
 *   - POST /periods/elderly-admission 静态段不会被 /:id 动态路由吞掉
 *   - encounter_id 为空 / 不存在 / 非养老 / 已离院 → 400
 *   - 首次补建 201，重复补建 200（幂等返回同一周期）
 *   - GET /periods?encounter_id= 精确过滤，不匹配同患者其它周期
 *
 * 依赖真实 PostgreSQL（与测试的 aceso_test 共享授权环境），
 * 通过 -Dintegration.db.* 系统属性启用；默认运行被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServicePeriodRoutesElderlyAdmissionTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18423
        private const val FIXTURE_PREFIX = "el-"
        private val BASE_PATH = "/nursing/v1/periods"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private var server: io.vertx.core.http.HttpServer? = null

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}$suffix"

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        try {
            if (password.isBlank()) throw IllegalStateException("PITCHFORK_DB_PASSWORD must be set")

            val rootUrl = "jdbc:postgresql://$host:$port/postgres"
            DriverManager.getConnection(rootUrl, user, password).use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'"
                )
                if (!rs.next()) {
                    conn.createStatement().execute("CREATE DATABASE $TEST_DB")
                }
            }

            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            val pool = DatabaseConfig.createPool(vertx, dbConfig)

            setupFixtures()

            val nursingRouter = NursingRoutes.create(vertx, pool)
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
        try {
            val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
            }
        } catch (_: Exception) { /* cleanup best effort */ }

        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    /** fixture：两名长者、一个活动养老入住、一个已离院养老入住、一个非养老入住、一条 HOME_CARE 周期 */
    private fun setupFixtures() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('${fixtureId("patient-1")}', '养老测试长者一', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('${fixtureId("patient-2")}', '养老测试长者二', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            // 活动养老入住（可补建）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-active")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'EL-20260731-01', '2026-07-31T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            // 已离院养老入住（不可补建）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-discharged")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'EL-20260701-02', '2026-07-01T00:00:00+08:00', '2026-07-10T00:00:00+08:00', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)
            // 非养老入住（不可补建）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-outpatient")}', '${fixtureId("patient-2")}', 'OUTPATIENT', 'OP-20260731-03', '2026-07-31T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            // 同患者另一类型的旧周期（不能与养老周期混淆；encounter_id 必须为空）
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
                VALUES ('${fixtureId("period-home")}', '${fixtureId("patient-1")}', 'HOME_CARE', '2026-06-01', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
        }
    }

    private fun post(vertx: Vertx, path: String, body: JsonObject): io.vertx.core.Future<io.vertx.core.http.HttpClientResponse> {
        val client = vertx.createHttpClient()
        return client.request(HttpMethod.POST, TEST_PORT, "localhost", path)
            .compose { req -> req.putHeader("Content-Type", "application/json").send(body.encode()) }
            .onComplete { client.close() }
    }

    private fun get(vertx: Vertx, path: String): io.vertx.core.Future<io.vertx.core.http.HttpClientResponse> {
        val client = vertx.createHttpClient()
        return client.request(HttpMethod.GET, TEST_PORT, "localhost", path)
            .compose { req -> req.send() }
            .onComplete { client.close() }
    }

    @Test
    fun `补建空encounter_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject())
            .compose { resp -> resp.body().map { Pair(resp.statusCode(), it) } }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertEquals("encounter_id is required", JsonObject(body).getString("error"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `补建不存在的encounter返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", "${FIXTURE_PREFIX}missing"))
            .compose { resp -> resp.body().map { Pair(resp.statusCode(), it) } }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(JsonObject(body).getString("error").contains("encounter not found"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `补建非养老入住返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", fixtureId("enc-outpatient")))
            .compose { resp -> resp.body().map { Pair(resp.statusCode(), it) } }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(JsonObject(body).getString("error").contains("not an elderly admission"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `补建已离院入住返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", fixtureId("enc-discharged")))
            .compose { resp -> resp.body().map { Pair(resp.statusCode(), it) } }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(JsonObject(body).getString("error").contains("not active"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `首次补建返回201且周期字段正确`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", fixtureId("enc-active")))
            .compose { resp -> resp.body().map { Pair(resp.statusCode(), it) } }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(201, status)
                    val period = JsonObject(body)
                    assertEquals("ELDERLY_CARE", period.getString("service_type"))
                    assertEquals(fixtureId("enc-active"), period.getString("encounter_id"))
                    assertEquals(fixtureId("patient-1"), period.getString("patient_id"))
                    // 开始日期从入住业务日期派生，而非当天
                    assertEquals("2026-07-31", period.getString("start_date"))
                    assertEquals("ACTIVE", period.getString("status"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `重复补建返回200且为同一周期`(vertx: Vertx, ctx: VertxTestContext) {
        val encounterId = fixtureId("enc-active")
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", encounterId))
            .compose { first -> first.body().map { Pair(first.statusCode(), it) } }
            .compose { (_, firstBody) ->
                val firstPeriod = JsonObject(firstBody)
                post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", encounterId))
                    .compose { second -> second.body().map { Triple(second.statusCode(), firstPeriod, it) } }
            }
            .onSuccess { (status, firstPeriod, secondBody) ->
                ctx.verify {
                    assertEquals(200, status, "重复补建应返回 200")
                    val secondPeriod = JsonObject(secondBody)
                    assertEquals(firstPeriod.getString("id"), secondPeriod.getString("id"), "必须返回同一周期")
                    assertEquals("ELDERLY_CARE", secondPeriod.getString("service_type"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `encounter_id精确过滤不混入同患者其它周期`(vertx: Vertx, ctx: VertxTestContext) {
        val encounterId = fixtureId("enc-active")
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", encounterId))
            .compose { resp -> resp.body().map { resp.statusCode() } }
            .compose { status ->
                get(vertx, "$BASE_PATH?encounter_id=$encounterId&status=ACTIVE")
                    .compose { resp -> resp.body().map { Pair(resp.statusCode(), it) } }
            }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    val json = JsonObject(body)
                    val records = json.getJsonArray("records")
                    assertEquals(1, records.size(), "encounter_id 过滤必须精确返回一条记录")
                    val period = records.getJsonObject(0)
                    assertEquals(fixtureId("enc-active"), period.getString("encounter_id"))
                    assertEquals("ELDERLY_CARE", period.getString("service_type"))
                    // 同患者 HOME_CARE 周期不得混入
                    assertNotEquals(fixtureId("period-home"), period.getString("id"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `静态路由不被动态路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        // POST /periods/elderly-admission 必须命中静态处理器（400/201/200），而非 404 或 405
        post(vertx, "$BASE_PATH/elderly-admission", JsonObject().put("encounter_id", "bad"))
            .compose { resp -> resp.body().map { resp.statusCode() } }
            .onSuccess { status ->
                ctx.verify {
                    assertNotEquals(404, status, "静态段不应被 /:id 动态路由吞掉")
                    assertNotEquals(405, status)
                    assertEquals(400, status)
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }
}
