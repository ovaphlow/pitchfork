package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.nursing.TaskExecutionService
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
 * HealthcareRoutes 养老入住创建与离院收束的嵌入式 HTTP 测试。
 *
 * 验证：
 *   - 养老入住创建在同一事务返回 encounter + nursing_period，周期开始日期等于入住日期
 *   - 养老入住缺少绑定周期时离院返回 409（不猜测关闭任意旧周期）
 *   - 存在 IN_PROGRESS 执行时离院返回 409，encounter 与周期均不变
 *   - 正常离院原子收束周期为 COMPLETED 并保留历史执行
 *   - 非养老 encounter 离院保持原行为，重复离院仍冲突
 *
 * 依赖真实 PostgreSQL（与测试的 aceso_test 共享授权环境），
 * 通过 -Dintegration.db.* 系统属性启用；默认运行被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthcareRoutesElderlyCareDischargeTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18424
        private const val FIXTURE_PREFIX = "hd-"
        private val BASE_PATH = "/healthcare/v1"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: io.vertx.sqlclient.Pool
    private lateinit var taskExecutionService: TaskExecutionService
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
                conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB")
                conn.createStatement().execute("CREATE DATABASE $TEST_DB")
            }

            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            taskExecutionService = TaskExecutionService(pool)

            val healthcareRouter = HealthcareRoutes.create(vertx, pool)
            val rootRouter = Router.router(vertx)
            rootRouter.route("/healthcare/v1/*").subRouter(healthcareRouter)
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

    @BeforeEach
    fun setupTestFixtures() {
        cleanupFixtures()
        setupFixtures()
    }

    @AfterEach
    fun cleanupTestFixtures() {
        cleanupFixtures()
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        cleanupFixtures()
        if (::pool.isInitialized) pool.close()

        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    private fun cleanupFixtures() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')))")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'))")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")

            val residual = stmt.executeQuery("""
                SELECT (
                    (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%')
                ) AS residual
            """.trimIndent())
            residual.next()
            check(residual.getLong("residual") == 0L) { "fixture cleanup left residual data" }
        }
    }

    /**
     * fixture：
     *   - hd-patient-1 + hd-enc-noperiod（ELDERLY_CARE, ACTIVE，无周期）→ 缺周期离院 409
     *   - hd-patient-2 + hd-enc-busy（ELDERLY_CARE, ACTIVE + ACTIVE 周期 + IN_PROGRESS 执行）→ 离院 409 无副作用
     *   - hd-patient-3 + hd-enc-ok（ELDERLY_CARE, ACTIVE + ACTIVE 周期 + COMPLETED 执行）→ 正常离院
     *   - hd-patient-4 + hd-enc-outpatient（OUTPATIENT, ACTIVE）→ 非养老离院保持原行为
     */
    private fun setupFixtures() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            for (i in 1..5) {
                stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('${fixtureId("patient-$i")}', '离院测试长者$i', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            }
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-noperiod")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'HD-20260731-01', '2026-07-31T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-busy")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', 'HD-20260731-02', '2026-07-31T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-ok")}', '${fixtureId("patient-3")}', 'ELDERLY_CARE', 'HD-20260731-03', '2026-07-31T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-outpatient")}', '${fixtureId("patient-4")}', 'OUTPATIENT', 'HD-20260731-04', '2026-07-31T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 忙碌周期的任务与执行
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status)
                VALUES ('${fixtureId("period-busy")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', '${fixtureId("enc-busy")}', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, frequency_code, start_date, status)
                VALUES ('${fixtureId("task-busy")}', '${fixtureId("period-busy")}', '${fixtureId("enc-busy")}', 'NURSING', '忙碌期任务', 'QD', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                VALUES ('${fixtureId("exec-busy")}', '${fixtureId("task-busy")}', '2026-07-31T09:00:00+08:00', 'IN_PROGRESS')
                ON CONFLICT (id) DO NOTHING
            """)

            // 正常离院周期的任务与历史完成执行
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status)
                VALUES ('${fixtureId("period-ok")}', '${fixtureId("patient-3")}', 'ELDERLY_CARE', '${fixtureId("enc-ok")}', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, frequency_code, start_date, status)
                VALUES ('${fixtureId("task-ok")}', '${fixtureId("period-ok")}', '${fixtureId("enc-ok")}', 'NURSING', '历史任务', 'QD', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, actual_time, status)
                VALUES ('${fixtureId("exec-ok")}', '${fixtureId("task-ok")}', '2026-07-31T09:00:00+08:00', '2026-07-31T09:30:00+08:00', 'COMPLETED')
                ON CONFLICT (id) DO NOTHING
            """)
        }
    }

    private fun request(
        vertx: Vertx,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): io.vertx.core.Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        val req = client.request(method, TEST_PORT, "localhost", path)
            .compose { r ->
                if (body != null) r.putHeader("Content-Type", "application/json").send(body.encode())
                else r.send()
            }
        return req.compose { resp ->
            resp.body().map { b ->
                val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                Pair(resp.statusCode(), json)
            }
        }.onComplete { client.close() }
    }

    @Test
    fun `创建养老入住返回nursing_period且开始日期等于入住日期`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions",
            JsonObject()
                .put("patient_id", fixtureId("patient-5"))
                .put("encounter_no", "HD-20260801-NEW")
                .put("admit_date", "2026-08-01T00:00:00+08:00")
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(201, status)
                val nursingPeriod = body.getJsonObject("nursing_period")
                assertNotNull(nursingPeriod, "响应必须包含 nursing_period")
                assertEquals("ELDERLY_CARE", nursingPeriod.getString("service_type"))
                assertEquals(body.getJsonObject("encounter").getString("id"), nursingPeriod.getString("encounter_id"))
                assertEquals("2026-08-01", nursingPeriod.getString("start_date"), "周期开始日期必须从入住日期派生")
                assertEquals("ACTIVE", nursingPeriod.getString("status"))
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `养老入住缺少绑定周期时离院返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.PATCH,
            "$BASE_PATH/encounters/${fixtureId("enc-noperiod")}/discharge",
            JsonObject().put("discharge_date", "2026-08-10T00:00:00+08:00")
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("no bound nursing care period"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `存在执行中任务时离院返回409且无副作用`(vertx: Vertx, ctx: VertxTestContext) {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        request(
            vertx,
            HttpMethod.PATCH,
            "$BASE_PATH/encounters/${fixtureId("enc-busy")}/discharge",
            JsonObject().put("discharge_date", "2026-08-10T00:00:00+08:00")
        ).compose { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("task execution is in progress"), "got: ${body.getString("error")}")
            }
            // 断言任何表均未改变：encounter 仍 ACTIVE、周期仍 ACTIVE、执行仍 IN_PROGRESS
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                val encStatus = stmt.executeQuery("SELECT status FROM healthcare.encounters WHERE id = '${fixtureId("enc-busy")}'")
                    .use { rs -> if (rs.next()) rs.getString(1) else null }
                val periodStatus = stmt.executeQuery("SELECT status FROM nursing.nursing_service_periods WHERE id = '${fixtureId("period-busy")}'")
                    .use { rs -> if (rs.next()) rs.getString(1) else null }
                val execStatus = stmt.executeQuery("SELECT status FROM nursing.nursing_task_executions WHERE id = '${fixtureId("exec-busy")}'")
                    .use { rs -> if (rs.next()) rs.getString(1) else null }
                ctx.verify {
                    assertEquals("ACTIVE", encStatus, "encounter 必须保持不变")
                    assertEquals("ACTIVE", periodStatus, "周期必须保持不变")
                    assertEquals("IN_PROGRESS", execStatus, "执行记录必须保持不变")
                }
            }
            ctx.completeNow()
            io.vertx.core.Future.succeededFuture<Unit>(Unit)
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `正常离院收束周期并保留历史执行`(vertx: Vertx, ctx: VertxTestContext) {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        taskExecutionService.ensureExecutionsForDateRange(
            LocalDate.now(),
            LocalDate.now().plusDays(7),
            fixtureId("period-ok")
        ).compose {
            request(
                vertx,
                HttpMethod.PATCH,
                "$BASE_PATH/encounters/${fixtureId("enc-ok")}/discharge",
                JsonObject().put("discharge_date", "2026-08-10T00:00:00+08:00")
            )
        }.compose { (status, body) ->
            ctx.verify {
                assertEquals(200, status)
                assertEquals("DISCHARGED", body.getString("status"))
            }
            val executionCountAfterDischarge = DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                val periodStatus = stmt.executeQuery("SELECT status, end_date FROM nursing.nursing_service_periods WHERE id = '${fixtureId("period-ok")}'")
                    .use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getString(2)) else null }
                val execCount = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_task_executions WHERE id = '${fixtureId("exec-ok")}'")
                    .use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                val encounterStatus = stmt.executeQuery("SELECT status FROM healthcare.encounters WHERE id = '${fixtureId("enc-ok")}'")
                    .use { rs -> if (rs.next()) rs.getString(1) else null }
                ctx.verify {
                    assertEquals("COMPLETED", periodStatus?.first, "周期必须收束为 COMPLETED")
                    assertEquals("2026-08-10", periodStatus?.second, "结束日期必须等于离院业务日期")
                    assertEquals(1L, execCount, "历史执行必须保留")
                    assertEquals("DISCHARGED", encounterStatus, "encounter 必须同步收束")
                }
                stmt.executeQuery("SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'")
                    .use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
            taskExecutionService.ensureExecutionsForDateRange(
                LocalDate.now().plusDays(8),
                LocalDate.now().plusDays(14),
                fixtureId("period-ok")
            ).map {
                val executionCountAfterRetry = DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    conn.createStatement().executeQuery("SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'")
                        .use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                }
                ctx.verify {
                    assertEquals(executionCountAfterDischarge, executionCountAfterRetry, "关闭周期不得生成未来执行")
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非养老encounter离院保持原行为`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.PATCH,
            "$BASE_PATH/encounters/${fixtureId("enc-outpatient")}/discharge",
            JsonObject().put("discharge_date", "2026-08-10T00:00:00+08:00")
        ).compose { (status, body) ->
            ctx.verify {
                assertEquals(200, status)
                assertEquals("DISCHARGED", body.getString("status"))
            }
            // 重复离院仍冲突
            request(
                vertx,
                HttpMethod.PATCH,
                "$BASE_PATH/encounters/${fixtureId("enc-outpatient")}/discharge",
                JsonObject().put("discharge_date", "2026-08-11T00:00:00+08:00")
            )
        }.onSuccess { (secondStatus, secondBody) ->
            ctx.verify {
                assertEquals(400, secondStatus)
                assertTrue(secondBody.getString("error").contains("already discharged"))
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }
}
