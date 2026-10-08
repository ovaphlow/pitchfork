package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 054 C1（保育员日常活动日志与行为观测）**真库集成测试**。
 *
 * 覆盖（054 §8.3 / task-24 C 链）：
 *  1. `V413` 真迁移：活动日志（班级级，`patient_id` 可空、有 `updated_at`）与行为观测
 *     （儿童级 **append-only，无 `updated_at`**）表结构与索引。
 *  2. **默认值**：`participant_count` 不传落 0；`activity_date` 缺省**业务当天**（Asia/Shanghai）。
 *  3. **班级级可见性**：`patient_id` 为 NULL 的日志在 `?class_name=` 可见、`?patient_id=` 不可见。
 *  4. **业务时区当日筛选跨 UTC 日界**：`observed_at = 2026-08-03T23:30+08:00` 与
 *     `2026-08-04T00:30+08:00` 的 **UTC 日期同为 08-03**，但业务日分别为 08-03 / 08-04。
 *  5. append-only：观测记录无更新接口（路由 404）。
 *  6. 空列表形状 `{records:[],meta:{total:0}}`；残差 0。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 nursing 段 V400–V414 + inventories V200–V204）：
 * ```
 * cd service-vertx-kotlin && export PITCHFORK_DB_PASSWORD=<hidden>
 * ./gradlew :libs:nursing:test --rerun --tests '*ChildcareActivityLogIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 唯一后缀；测试前后自清并断言残差 0；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildcareActivityLogIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054c1-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD_A = "${PREFIX}child-$RUN"
        private val UNKNOWN_CHILD = "${PREFIX}missing-$RUN"
        private val CLASS_A = "${PREFIX}班-$RUN"

        private const val TESTER = "qa054c1-tester"
        private const val ACTIVITY_LOGS = "/nursing/v1/childcare-activity-logs"
        private const val OBSERVATIONS = "/nursing/v1/childcare-behavior-observations"

        private val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var client: HttpClient
    private var server: io.vertx.core.http.HttpServer? = null
    private var serverPort: Int = 0

    // ========================================================================
    //  生命周期
    // ========================================================================

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        Assumptions.assumeTrue(
            password.isNotBlank(),
            "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行",
        )

        try {
            resetTestDb()
            createMinimalHealthcareSchema()
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            client = vertx.createHttpClient()

            val root = Router.router(vertx)
            root.route("/nursing/v1/*").handler { rctx ->
                rctx.put("userId", TESTER)
                rctx.next()
            }
            root.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool))

            vertx.createHttpServer()
                .requestHandler(root)
                .listen(0)
                .onComplete { ar ->
                    if (ar.succeeded()) {
                        server = ar.result()
                        serverPort = ar.result().actualPort()
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
            cleanupFixtures()
            assertNoResidual()
        } finally {
            if (::pool.isInitialized) pool.close()
            if (::client.isInitialized) client.close()
        }
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        } ?: ctx.completeNow()
    }

    @BeforeEach
    fun setupFixtures() {
        cleanupFixtures()
        exec(
            "INSERT INTO healthcare.patients (id, name, gender, person_type, status) " +
                "VALUES ('$CHILD_A', '054C1儿童', '女', '儿童', 'ACTIVE')",
        )
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V413 表结构 + append-only
    // ========================================================================

    @Test
    fun `V413迁移_活动日志可空患者_观测append_only无updated_at`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "nursing.nursing_childcare_activity_logs",
                queryString("SELECT to_regclass('nursing.nursing_childcare_activity_logs')::text"),
            )
            assertEquals(
                "nursing.nursing_childcare_behavior_observations",
                queryString("SELECT to_regclass('nursing.nursing_childcare_behavior_observations')::text"),
            )
            // 活动日志：班级级台账，patient_id 可空 + 有 updated_at
            assertEquals(
                "YES",
                queryString(
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'nursing' " +
                        "AND table_name = 'nursing_childcare_activity_logs' AND column_name = 'patient_id'",
                ),
                "活动日志 patient_id 必须可空（班级集体活动）",
            )
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'nursing' " +
                        "AND table_name = 'nursing_childcare_activity_logs' AND column_name = 'updated_at'",
                ),
            )
            // 行为观测：append-only，**不得有 updated_at 列**
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'nursing' " +
                        "AND table_name = 'nursing_childcare_behavior_observations' AND column_name = 'updated_at'",
                ),
                "行为观测是 append-only，不得有 updated_at 列",
            )
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'nursing' " +
                        "AND indexname = 'idx_childcare_behavior_observations_patient'",
                ),
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 默认值 + 班级级可见性
    // ========================================================================

    @Test
    fun `活动日志_默认值与班级级患者可见性`(vertx: Vertx, ctx: VertxTestContext) {
        val businessToday = LocalDate.now(BUSINESS_ZONE).toString()

        post(
            vertx,
            ACTIVITY_LOGS,
            JsonObject().put("class_name", CLASS_A).put("activity_name", "户外集体游戏"),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "登记必须 201，实际: $created")
                assertEquals(0, created!!.getInteger("participant_count") ?: -1, "participant_count 缺省落 0")
                assertNull(created.getString("patient_id"), "班级级日志 patient_id 为 null")
                assertEquals(businessToday, created.getString("activity_date"), "activity_date 缺省业务当天（Asia/Shanghai）")
                assertEquals(TESTER, created.getString("operator"), "operator 取认证主体（042）")
            }
            // 班级级日志：?class_name= 可见、?patient_id= 不可见
            get(vertx, "$ACTIVITY_LOGS?class_name=${enc(CLASS_A)}")
        }.compose { (s2, byClass) ->
            ctx.verify {
                assertEquals(200, s2, "按班级查询必须 200，实际: $byClass")
                assertEquals(1L, byClass!!.getJsonObject("meta").getLong("total"), "班级级日志必须可按 class_name 查到")
            }
            get(vertx, "$ACTIVITY_LOGS?patient_id=${enc(CHILD_A)}")
        }.compose { (s3, byPatient) ->
            ctx.verify {
                assertEquals(200, s3, "按儿童查询必须 200，实际: $byPatient")
                assertEquals(0L, byPatient!!.getJsonObject("meta").getLong("total"), "patient_id 为 NULL 的日志不得出现在 ?patient_id= 结果里")
            }
            // 儿童级日志：?patient_id= 可见
            post(
                vertx,
                ACTIVITY_LOGS,
                JsonObject()
                    .put("class_name", CLASS_A)
                    .put("activity_name", "个别化训练")
                    .put("patient_id", CHILD_A)
                    .put("participant_count", 1),
            )
        }.compose { (s4, childLog) ->
            ctx.verify {
                assertEquals(201, s4, "儿童级日志必须 201，实际: $childLog")
                assertEquals(CHILD_A, childLog!!.getString("patient_id"))
                assertEquals(1, childLog.getInteger("participant_count") ?: -1)
            }
            get(vertx, "$ACTIVITY_LOGS?patient_id=${enc(CHILD_A)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"), "儿童级日志必须可按 patient_id 查到")
                assertEquals(2L, queryLong(
                    "SELECT count(*) FROM nursing.nursing_childcare_activity_logs WHERE class_name LIKE '$PREFIX%'",
                ), "同班级同日可多条（无唯一约束）")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 业务时区当日筛选跨 UTC 日界（观测）
    // ========================================================================

    @Test
    fun `观测_业务时区当日筛选跨UTC日界`(vertx: Vertx, ctx: VertxTestContext) {
        // 2026-08-03T23:30+08:00 = 2026-08-03T15:30Z；2026-08-04T00:30+08:00 = 2026-08-03T16:30Z
        // → 两者 UTC 日期同为 08-03，但业务日分别为 08-03 / 08-04（必须按业务时区切日）
        post(
            vertx,
            OBSERVATIONS,
            JsonObject()
                .put("patient_id", CHILD_A)
                .put("category", "情绪")
                .put("description", "傍晚情绪波动")
                .put("level", "关注")
                .put("observed_at", "2026-08-03T23:30:00+08:00"),
        ).compose { (s1, late) ->
            ctx.verify {
                assertEquals(201, s1, "登记必须 201，实际: $late")
                assertEquals("关注", late!!.getString("level"))
            }
            post(
                vertx,
                OBSERVATIONS,
                JsonObject()
                    .put("patient_id", CHILD_A)
                    .put("category", "睡眠")
                    .put("description", "午夜后入睡")
                    .put("observed_at", "2026-08-04T00:30:00+08:00"),
            )
        }.compose { (s2, early) ->
            ctx.verify { assertEquals(201, s2, "登记必须 201，实际: $early") }
            get(vertx, "$OBSERVATIONS?patient_id=${enc(CHILD_A)}&date=${enc("2026-08-03")}")
        }.compose { (s3, day3) ->
            ctx.verify {
                assertEquals(200, s3, "实际: $day3")
                assertEquals(1L, day3!!.getJsonObject("meta").getLong("total"), "08-03 业务日只有 1 条（UTC 切日会误并成 2 条）")
            }
            get(vertx, "$OBSERVATIONS?patient_id=${enc(CHILD_A)}&date=${enc("2026-08-04")}")
        }.compose { (s4, day4) ->
            ctx.verify {
                assertEquals(200, s4, "实际: $day4")
                assertEquals(1L, day4!!.getJsonObject("meta").getLong("total"), "00:30+08:00 必须归入 08-04 业务日")
            }
            // level 过滤
            get(vertx, "$OBSERVATIONS?patient_id=${enc(CHILD_A)}&level=${enc("关注")}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "level 过滤失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"), "level=关注 只命中 1 条")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `观测_缺省当前时刻与纯日期按业务时区_非法参数400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            OBSERVATIONS,
            JsonObject()
                .put("patient_id", CHILD_A)
                .put("category", "社交")
                .put("description", "主动分享玩具"),
        ).compose { (s1, defaulted) ->
            ctx.verify {
                assertEquals(201, s1, "登记必须 201，实际: $defaulted")
                assertNotNull(defaulted!!.getString("observed_at"), "observed_at 缺省当前时刻")
                assertNull(defaulted.getString("level"), "level 可空")
            }
            // 纯日期 → 业务时区当日 00:00（比较瞬时，避免时区归一误判）
            post(
                vertx,
                OBSERVATIONS,
                JsonObject()
                    .put("patient_id", CHILD_A)
                    .put("category", "饮食")
                    .put("description", "午餐进食正常")
                    .put("observed_at", "2026-08-05"),
            )
        }.compose { (s2, pureDate) ->
            ctx.verify {
                assertEquals(201, s2, "纯日期登记必须 201，实际: $pureDate")
                val expected = LocalDate.parse("2026-08-05").atStartOfDay(BUSINESS_ZONE).toOffsetDateTime()
                assertEquals(
                    expected.toInstant(),
                    java.time.OffsetDateTime.parse(pureDate!!.getString("observed_at")).toInstant(),
                    "纯日期必须按 Asia/Shanghai 当日 00:00（比较瞬时）",
                )
            }
            post(
                vertx,
                OBSERVATIONS,
                JsonObject().put("patient_id", CHILD_A).put("category", "情绪")
                    .put("description", "x").put("level", "BOGUS"),
            )
        }.compose { (s3, badLevel) ->
            ctx.verify { assertEquals(400, s3, "非法 level 必须 400，实际: $badLevel") }
            post(vertx, OBSERVATIONS, JsonObject().put("category", "情绪").put("description", "缺 patient"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "缺 patient_id 必须 400，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) append-only：无更新接口 + 空列表 + 非法参数
    // ========================================================================

    @Test
    fun `观测_append_only无更新接口与空列表形状`(vertx: Vertx, ctx: VertxTestContext) {
        var observationId = ""
        post(
            vertx,
            OBSERVATIONS,
            JsonObject().put("patient_id", CHILD_A).put("category", "行为").put("description", "记录一条"),
        ).compose { (s1, created) ->
            ctx.verify { assertEquals(201, s1, "登记必须 201，实际: $created") }
            observationId = created!!.getString("id")
            // 观测是 append-only：没有更新路由 → 路由 404（空体），不是业务 404
            patch(vertx, "$OBSERVATIONS/$observationId", JsonObject().put("description", "试图修改"))
        }.compose { (s2, noRoute) ->
            ctx.verify {
                assertEquals(404, s2, "观测不得有更新接口（应路由 404），实际: $noRoute")
                assertNull(noRoute, "路由 404 响应体为空（区别于业务 {error} 404）")
            }
            get(vertx, "$OBSERVATIONS?patient_id=${enc(UNKNOWN_CHILD)}")
        }.compose { (s3, empty) ->
            ctx.verify {
                assertEquals(200, s3, "空列表必须 200，实际: $empty")
                assertEquals(0, empty!!.getJsonArray("records").size())
                assertEquals(0L, empty.getJsonObject("meta").getLong("total"))
            }
            post(vertx, ACTIVITY_LOGS, JsonObject().put("class_name", CLASS_A))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "缺 activity_name 必须 400，实际: $body")
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM nursing.nursing_childcare_activity_logs WHERE class_name LIKE '$PREFIX%'"),
                    "非法请求不得落库",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `活动日志_儿童不存在404与负人数400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            ACTIVITY_LOGS,
            JsonObject().put("class_name", CLASS_A).put("activity_name", "x").put("patient_id", UNKNOWN_CHILD),
        ).compose { (s1, missing) ->
            ctx.verify {
                assertEquals(404, s1, "patient_id 不存在必须 404，实际: $missing")
                assertTrue(missing?.getString("error")?.contains("patient not found") == true)
            }
            post(
                vertx,
                ACTIVITY_LOGS,
                JsonObject().put("class_name", CLASS_A).put("activity_name", "x").put("participant_count", -1),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "负 participant_count 必须 400，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject?>> =
        client.request(method, serverPort, "127.0.0.1", path).compose { req ->
            if (body != null) req.putHeader("Content-Type", "application/json")
            (if (body != null) req.send(Buffer.buffer(body.encode())) else req.send()).compose { resp ->
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

    private fun get(vertx: Vertx, path: String): Future<Pair<Int, JsonObject?>> = send(HttpMethod.GET, path)

    private fun post(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.POST, path, body)

    private fun patch(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.PATCH, path, body)

    private fun resetTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    private fun createMinimalHealthcareSchema() {
        exec("CREATE SCHEMA IF NOT EXISTS healthcare")
        exec(
            "CREATE TABLE IF NOT EXISTS healthcare.patients (" +
                "id VARCHAR(32) PRIMARY KEY, name VARCHAR NOT NULL DEFAULT '', " +
                "gender VARCHAR NOT NULL DEFAULT '', status VARCHAR DEFAULT 'ACTIVE')",
        )
        exec(
            "ALTER TABLE healthcare.patients ADD COLUMN IF NOT EXISTS " +
                "person_type VARCHAR NOT NULL DEFAULT '居民'",
        )
    }

    private fun <T> jdbc(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:postgresql://$host:$port/$TEST_DB", user, password).use(block)

    private fun exec(sql: String) {
        jdbc { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    private fun queryLong(sql: String): Long =
        jdbc { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    private fun queryString(sql: String): String? =
        jdbc { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun cleanupFixtures() {
        exec("DELETE FROM nursing.nursing_childcare_activity_logs WHERE class_name LIKE '$PREFIX%'")
        exec("DELETE FROM nursing.nursing_childcare_behavior_observations WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "nursing.nursing_childcare_activity_logs" to
                "SELECT count(*) FROM nursing.nursing_childcare_activity_logs WHERE class_name LIKE '$PREFIX%'",
            "nursing.nursing_childcare_behavior_observations" to
                "SELECT count(*) FROM nursing.nursing_childcare_behavior_observations WHERE patient_id LIKE '$PREFIX%'",
            "healthcare.patients" to
                "SELECT count(*) FROM healthcare.patients WHERE id LIKE '$PREFIX%'",
        )
        val leftovers = residuals.mapNotNull { (table, sql) ->
            val count = queryLong(sql)
            if (count == 0L) null else "$table=$count"
        }
        assertTrue(leftovers.isEmpty(), "fixture 残差必须为 0，实际残留: $leftovers")
    }
}
