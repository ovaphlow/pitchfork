package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
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
import java.time.OffsetDateTime

/**
 * TaskExecutionRoutes 逾期参数校验与跨日逾期队列（§4.3）的 HTTP 路由集成测试。
 *
 * 启动嵌入式 Vert.x HTTP 服务器，用 Vert.x HttpClient 发起真实 HTTP 请求验证：
 *   - overdue 格式错误 → 400
 *   - overdue=true + 终态 status → 400
 *   - overdue=true + PENDING/IN_PROGRESS → 正常请求
 *   - /executions/overdue：无日期窗口、按计划时间升序、忽略 date、尾部斜杠容错、limit 收敛 1..200
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
        private val OVERDUE_PATH = "/nursing/v1/executions/overdue"

        /** 本类 fixture 前缀，避免与其它测试类互相污染 */
        private const val FIXTURE_PREFIX = "trovr-"
    }

    private var server: io.vertx.core.http.HttpServer? = null

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")

        try {
            ensureTestDb(host, port, user)
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            ensureHealthcarePatientsTable(host, port, user)
            setupOverdueFixtures()
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
        val cleanupError = try {
            cleanupFixtures()
            null
        } catch (e: Exception) {
            e
        }
        val current = server
        if (current == null) {
            if (cleanupError != null) ctx.failNow(cleanupError) else ctx.completeNow()
            return
        }
        current.close { ar ->
            when {
                cleanupError != null -> ctx.failNow(cleanupError)
                ar.succeeded() -> ctx.completeNow()
                else -> ctx.failNow(ar.cause())
            }
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

    // ========================================================================
    //  fixture 与 HTTP 辅助（只使用本类前缀 trovr-，用完即清）
    // ========================================================================

    private fun jdbcUrl() = "jdbc:postgresql://$host:$port/$TEST_DB"

    private fun dbPassword(): String = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

    private fun fixtureId(suffix: String): String = "$FIXTURE_PREFIX$suffix"

    /** 造一条 51 天前的跨日逾期 PENDING（外加一条长挂 IN_PROGRESS），任务归属活跃周期 */
    private fun setupOverdueFixtures() {
        val now = OffsetDateTime.now()
        val patientId = fixtureId("patient")
        val periodId = fixtureId("period")
        val taskId = fixtureId("task")

        DriverManager.getConnection(jdbcUrl(), user, dbPassword()).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute(
                "INSERT INTO healthcare.patients (id, name, status) VALUES ('$patientId', '跨日逾期路由测试患者', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
            stmt.execute(
                "INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status) VALUES ('$periodId', '$patientId', 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
            stmt.execute(
                "INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status) VALUES ('$taskId', '$periodId', 'NURSING', '跨日逾期路由测试任务', 'QD', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
            stmt.execute(
                "INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-pending-51d")}', '$taskId', '${now.minusDays(51)}', 'PENDING') ON CONFLICT (id) DO NOTHING",
            )
            stmt.execute(
                "INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, actual_time) VALUES ('${fixtureId("exec-stale")}', '$taskId', '${now.minusDays(2)}', 'IN_PROGRESS', '${now.minusDays(2).minusHours(1)}') ON CONFLICT (id) DO NOTHING",
            )
        }
    }

    /** 清理本类全部 fixture（含 /today 期间为 fixture 任务派生的执行记录） */
    private fun cleanupFixtures() {
        DriverManager.getConnection(jdbcUrl(), user, dbPassword()).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("DELETE FROM nursing.nursing_task_execution_consumptions WHERE task_execution_id LIKE '$FIXTURE_PREFIX%'")
            stmt.execute("DELETE FROM nursing.nursing_visit_schedules WHERE period_id LIKE '$FIXTURE_PREFIX%'")
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '$FIXTURE_PREFIX%' OR task_id LIKE '$FIXTURE_PREFIX%'")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '$FIXTURE_PREFIX%'")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '$FIXTURE_PREFIX%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '$FIXTURE_PREFIX%'")
        }
    }

    /** 发起一个 GET 并读回「状态码 + 响应体文本」 */
    private fun get(client: HttpClient, path: String): Future<Pair<Int, String>> =
        client.request(HttpMethod.GET, TEST_PORT, "localhost", path)
            .compose { it.send() }
            .compose { resp -> resp.body().map { body -> Pair(resp.statusCode(), body?.toString() ?: "") } }

    private fun recordIds(json: JsonObject): Set<String> {
        val records = json.getJsonArray("records") ?: return emptySet()
        val ids = mutableSetOf<String>()
        for (i in 0 until records.size()) {
            ids.add(records.getJsonObject(i).getString("id") ?: "")
        }
        return ids
    }

    private fun metaTotal(json: JsonObject): Long = json.getJsonObject("meta")?.getLong("total") ?: -1L

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

    // ========================================================================
    //  027 跨日逾期队列（§4.3）
    // ========================================================================

    @Test
    fun `跨日逾期记录出现在overdue端点但不在today窗口`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        val crossDayId = fixtureId("exec-pending-51d")
        val periodId = fixtureId("period")

        get(client, "$OVERDUE_PATH?period_id=$periodId")
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "overdue 端点应返回 200，响应：$body")
                    assertTrue(
                        recordIds(JsonObject(body)).contains(crossDayId),
                        "51 天前的逾期记录应出现在 overdue 端点，响应：$body",
                    )
                    assertTrue(metaTotal(JsonObject(body)) >= 1L, "meta.total 应至少包含该跨日记录")
                }
                get(client, "$BASE_PATH?date=${LocalDate.now()}&period_id=$periodId")
            }
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "today 端点应返回 200，响应：$body")
                    assertFalse(
                        recordIds(JsonObject(body)).contains(crossDayId),
                        "51 天前的记录不应出现在今日窗口（/today 语义不变）",
                    )
                }
            }
            .onSuccess {
                client.close()
                ctx.completeNow()
            }
            .onFailure {
                client.close()
                ctx.failNow(it)
            }
    }

    @Test
    fun `overdue端点静默忽略date参数`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        val periodId = fixtureId("period")

        get(client, "$OVERDUE_PATH?period_id=$periodId")
            .compose { (_, body) ->
                val baseline = metaTotal(JsonObject(body))
                assertTrue(baseline >= 1L, "baseline meta.total 应 >= 1，响应：$body")
                get(client, "$OVERDUE_PATH?period_id=$periodId&date=2020-01-01")
                    .map { (status, withDate) -> Triple(baseline, status, withDate) }
            }
            .compose { (baseline, status, body) ->
                ctx.verify {
                    assertEquals(200, status, "date 参数应被静默忽略，响应：$body")
                    assertEquals(baseline, metaTotal(JsonObject(body)), "date 参数不得改变跨日逾期队列")
                }
                get(client, "$OVERDUE_PATH?period_id=$periodId&date=not-a-date")
            }
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "非法 date 也应被静默忽略（不返回 400），响应：$body")
                    assertTrue(JsonObject(body).containsKey("records"), "响应必须包含 records")
                }
            }
            .onSuccess {
                client.close()
                ctx.completeNow()
            }
            .onFailure {
                client.close()
                ctx.failNow(it)
            }
    }

    @Test
    fun `overdue端点尾部斜杠容错`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()

        get(client, "$OVERDUE_PATH/?period_id=${fixtureId("period")}")
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "带尾部斜杠的 /overdue/ 应返回 200，响应：$body")
                    val json = JsonObject(body)
                    assertTrue(json.containsKey("records"), "响应必须包含 records")
                    assertNotNull(json.getJsonObject("meta")?.getLong("total"), "meta.total 必须存在")
                }
            }
            .onSuccess {
                client.close()
                ctx.completeNow()
            }
            .onFailure {
                client.close()
                ctx.failNow(it)
            }
    }

    @Test
    fun `overdue端点limit收敛到1至200`(vertx: Vertx, ctx: VertxTestContext) {
        val client = vertx.createHttpClient()
        val periodId = fixtureId("period")

        get(client, "$OVERDUE_PATH?period_id=$periodId&limit=0")
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "limit=0 应被收敛为 1（不报错），响应：$body")
                    assertTrue(
                        JsonObject(body).getJsonArray("records").size() <= 1,
                        "limit=0 应收敛到 1 条记录",
                    )
                }
                get(client, "$OVERDUE_PATH?period_id=$periodId&limit=9999&offset=-5")
            }
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "limit=9999 应被收敛为 200、offset=-5 收敛为 0，响应：$body")
                    assertTrue(
                        JsonObject(body).getJsonArray("records").size() <= 200,
                        "limit 上限为 200",
                    )
                }
            }
            .onSuccess {
                client.close()
                ctx.completeNow()
            }
            .onFailure {
                client.close()
                ctx.failNow(it)
            }
    }
}
