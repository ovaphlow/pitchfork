package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
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
import java.util.UUID

/**
 * 054 B2（儿童康复训练执行 / 会话）**真库集成测试**——唯一事实源为隔离 `aceso_test`。
 *
 * 覆盖（054 §8.2 / task-24，BL7 → L7 裁定）：
 *  1. `V410` 真迁移：`nursing.nursing_child_rehab_sessions` 建表；
 *     **「是否完成训练」的唯一事实源**——打卡**不写** `nursing_task_executions`（该表保持 0 行）；
 *     `patient_id` **不落库**，由 `plan_id → nursing_plans → nursing_service_periods` 派生。
 *  2. 按计划打卡：201、append-only（同一计划同一天允许多次训练，不设唯一约束）。
 *  3. 进度汇总与计划进度一致：`?plan_id=` 附 `progress`（session_count / completed_days /
 *     planned_days / avg_completion_ratio / progress_ratio / last_session_date），且**不受分页与区间过滤影响**。
 *  4. 非法计划 id 4xx：不存在 → 404；挂在非 `CHILD_REHAB` 周期上的计划 → 400；缺参/越界 → 400。
 *  5. 列表空结果 `{records: [], meta: {total: 0}}`；计划无起止日期时 `planned_days=null`、`progress_ratio=0`。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 nursing 段 V400–V410 + inventories V200–V204）：
 * ```
 * cd service-vertx-kotlin && set -a && . apps/aceso/.env && set +a
 * ./gradlew :libs:nursing:test --rerun --tests '*ChildRehabSessionIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 每次运行唯一后缀；测试前后自清并断言残差 0；
 * 进程内假认证（`userId` 注入），**不涉及任何 token**。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildRehabSessionIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054b2-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD_A = "${PREFIX}a-$RUN"
        private val UNKNOWN_CHILD = "${PREFIX}missing-$RUN"

        private val PERIOD_A = "${PREFIX}period-a-$RUN"
        private val PLAN_A = "${PREFIX}plan-a-$RUN"
        private val PLAN_NO_DATES = "${PREFIX}plan-nodates-$RUN"
        private val HOME_PERIOD = "${PREFIX}home-period-$RUN"
        private val HOME_PLAN = "${PREFIX}home-plan-$RUN"

        private const val TESTER = "qa054b2-tester"
        private const val SESSIONS = "/nursing/v1/child-rehab-sessions"
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
                "VALUES ('$CHILD_A', '054B2康复儿童', '男', '儿童', 'ACTIVE')",
        )
        // CHILD_REHAB 周期（encounter_id 必须为 NULL）
        exec(
            "INSERT INTO nursing.nursing_service_periods " +
                "(id, patient_id, service_type, encounter_id, start_date, end_date, status, created_at, updated_at) " +
                "VALUES ('$PERIOD_A', '$CHILD_A', 'CHILD_REHAB', NULL, DATE '2026-08-01', DATE '2026-09-30', " +
                "'ACTIVE', now(), now())",
        )
        // 计划 A：2026-08-03 ~ 2026-08-09 → planned_days = 7
        exec(
            "INSERT INTO nursing.nursing_plans " +
                "(id, period_id, encounter_id, plan_name, status, created_by, start_date, end_date, created_at, updated_at) " +
                "VALUES ('$PLAN_A', '$PERIOD_A', NULL, '054B2 训练计划A', 'ACTIVE', 'fixture', " +
                "DATE '2026-08-03', DATE '2026-08-09', now(), now())",
        )
        // 计划：无起止日期（planned_days = null）
        exec(
            "INSERT INTO nursing.nursing_plans " +
                "(id, period_id, encounter_id, plan_name, status, created_by, created_at, updated_at) " +
                "VALUES ('$PLAN_NO_DATES', '$PERIOD_A', NULL, '054B2 无起止日期计划', 'ACTIVE', 'fixture', now(), now())",
        )
        // 非 CHILD_REHAB 周期上的计划（用于 400）
        exec(
            "INSERT INTO nursing.nursing_service_periods " +
                "(id, patient_id, service_type, encounter_id, start_date, status, created_at, updated_at) " +
                "VALUES ('$HOME_PERIOD', '$CHILD_A', 'HOME_CARE', NULL, DATE '2026-08-01', 'ACTIVE', now(), now())",
        )
        exec(
            "INSERT INTO nursing.nursing_plans " +
                "(id, period_id, encounter_id, plan_name, status, created_by, created_at, updated_at) " +
                "VALUES ('$HOME_PLAN', '$HOME_PERIOD', NULL, '054B2 非儿童康复计划', 'ACTIVE', 'fixture', now(), now())",
        )
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V410 真迁移 + 唯一事实源
    // ========================================================================

    @Test
    fun `V410迁移_会话表为唯一事实源且不写打卡链`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "nursing.nursing_child_rehab_sessions",
                queryString("SELECT to_regclass('nursing.nursing_child_rehab_sessions')::text"),
                "V410 必须真实建出会话表",
            )
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'nursing' " +
                        "AND table_name = 'nursing_child_rehab_sessions' AND column_name = 'patient_id'",
                ),
                "会话表不得冗余落 patient_id（由 join 派生）",
            )
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_constraint c " +
                        "JOIN pg_class t ON t.oid = c.conrelid " +
                        "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                        "JOIN pg_class f ON f.oid = c.confrelid " +
                        "WHERE n.nspname = 'nursing' AND t.relname = 'nursing_child_rehab_sessions' " +
                        "AND c.contype = 'f' AND f.relname = 'nursing_plans'",
                ),
                "会话必须外键挂在 nursing_plans 上",
            )
        }

        post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 1.0)).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "打卡失败: ${ar.cause()?.message}")
                val (status, created) = ar.result()
                assertEquals(201, status, "打卡必须 201，实际: $created")
                assertEquals(PLAN_A, created!!.getString("plan_id"))
                assertEquals(CHILD_A, created.getString("patient_id"), "patient_id 必须由 join 派生")
                assertEquals(TESTER, created.getString("operator"), "operator 取认证主体（042）")
                // 唯一事实源：儿童康复打卡绝不写养老/给药打卡链
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM nursing.nursing_task_executions"),
                    "儿童康复训练不得写入 nursing_task_executions（BL7 唯一事实源裁定）",
                )
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_child_rehab_sessions WHERE plan_id = '$PLAN_A'",
                    ),
                    "会话必须真落 nursing_child_rehab_sessions",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  2) 打卡：append-only（同日多次训练）
    // ========================================================================

    @Test
    fun `同一计划同日多次训练_追加多行不设唯一约束`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 1.0)).compose { (s1, first) ->
            ctx.verify {
                assertEquals(201, s1, "首次打卡必须 201，实际: $first")
                assertEquals(1.0, first!!.getDouble("completion_ratio") ?: -1.0, 0.0001)
            }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 0.5).put("performance", "上午状态一般"))
        }.compose { (s2, second) ->
            ctx.verify {
                assertEquals(201, s2, "同日第二次训练必须 201（append-only），实际: $second")
                assertEquals(0.5, second!!.getDouble("completion_ratio") ?: -1.0, 0.0001)
                assertEquals("上午状态一般", second.getString("performance"))
            }
            get(vertx, "$SESSIONS?plan_id=$PLAN_A")
        }.compose { (s3, body) ->
            ctx.verify {
                assertEquals(200, s3, "列表必须 200，实际: $body")
                assertEquals(
                    2L,
                    queryLong("SELECT count(*) FROM nursing.nursing_child_rehab_sessions WHERE plan_id = '$PLAN_A'"),
                    "同一计划同一天允许多次训练（不设唯一约束）",
                )
                assertEquals(2L, body!!.getJsonObject("meta").getLong("total"))
                assertEquals(2, body.getJsonArray("records").size())
                // dev 显式标注的 DB-free 盲区：同日两次 = 2 条会话、但只算 1 个完成日
                val progress = body.getJsonObject("progress")
                assertNotNull(progress, "带 plan_id 查询必须返回 progress")
                assertEquals(2, progress!!.getInteger("session_count"), "同计划同日两次打卡 → session_count=2")
                assertEquals(1, progress.getInteger("completed_days"), "同日多次只算 1 个完成日")
                assertEquals(7, progress.getInteger("planned_days"))
                assertEquals(
                    0.1429,
                    progress.getDouble("progress_ratio") ?: -1.0,
                    0.0001,
                    "完成 1 天 / 计划 7 天 = 0.1429",
                )
            }
            // 跨表 join：?patient_id= 必须经 nursing_plans → nursing_service_periods 派生过滤
            get(vertx, "$SESSIONS?patient_id=${enc(CHILD_A)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "按 patient_id 过滤失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(2L, body!!.getJsonObject("meta").getLong("total"), "?patient_id= 必须走跨表 join 过滤")
                assertEquals(
                    CHILD_A,
                    body.getJsonArray("records").getJsonObject(0).getString("patient_id"),
                    "patient_id 为 join 派生字段",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `completion_ratio_NUMERIC5_4编解码与取整落库`(vertx: Vertx, ctx: VertxTestContext) {
        // 0.33335 在服务端 setScale(4, HALF_UP) → 0.3334；NUMERIC(5,4) 原样落库
        post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 0.33335)).compose { (s1, rounded) ->
            ctx.verify {
                assertEquals(201, s1, "打卡必须 201，实际: $rounded")
                assertEquals(0.3334, rounded!!.getDouble("completion_ratio") ?: -1.0, 0.00001, "四位小数 HALF_UP 取整")
            }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-04", 1.0))
        }.compose { (s2, integer) ->
            ctx.verify {
                assertEquals(201, s2, "整数比例必须 201，实际: $integer")
                assertEquals(1.0, integer!!.getDouble("completion_ratio") ?: -1.0, 0.0001)
            }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-05", 0.5))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "打卡失败: ${ar.cause()?.message}")
                val (status, _) = ar.result()
                assertEquals(201, status)
                assertEquals(
                    "0.3334",
                    queryString(
                        "SELECT completion_ratio::text FROM nursing.nursing_child_rehab_sessions " +
                            "WHERE plan_id = '$PLAN_A' AND session_date = DATE '2026-08-03'",
                    ),
                    "NUMERIC(5,4) 必须真落库为 0.3334",
                )
                assertEquals(
                    "1.0000",
                    queryString(
                        "SELECT completion_ratio::text FROM nursing.nursing_child_rehab_sessions " +
                            "WHERE plan_id = '$PLAN_A' AND session_date = DATE '2026-08-04'",
                    ),
                    "NUMERIC(5,4) 补零到四位",
                )
                assertEquals(
                    "0.5000",
                    queryString(
                        "SELECT completion_ratio::text FROM nursing.nursing_child_rehab_sessions " +
                            "WHERE plan_id = '$PLAN_A' AND session_date = DATE '2026-08-05'",
                    ),
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 进度汇总与计划进度一致
    // ========================================================================

    @Test
    fun `进度汇总_打卡次数完成天数与均值口径正确`(vertx: Vertx, ctx: VertxTestContext) {
        // 08-03 两次（1.0 / 0.5）+ 08-05 一次（0.8）→ 3 条会话、2 个完成日
        post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 1.0)).compose { (s1, _) ->
            ctx.verify { assertEquals(201, s1, "第 1 条必须 201") }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 0.5))
        }.compose { (s2, _) ->
            ctx.verify { assertEquals(201, s2, "第 2 条必须 201") }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-05", 0.8))
        }.compose { (s3, _) ->
            ctx.verify { assertEquals(201, s3, "第 3 条必须 201") }
            get(vertx, "$SESSIONS?plan_id=$PLAN_A")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "进度查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(3L, body!!.getJsonObject("meta").getLong("total"))
                val progress = body.getJsonObject("progress")
                assertNotNull(progress, "带 plan_id 查询必须返回 progress")
                assertEquals(PLAN_A, progress!!.getString("plan_id"))
                assertEquals(3, progress.getInteger("session_count"), "打卡次数")
                assertEquals(2, progress.getInteger("completed_days"), "去重后的完成天数")
                assertEquals(7, progress.getInteger("planned_days"), "2026-08-03~08-09 含端点 = 7 天")
                assertEquals(
                    0.77,
                    progress.getDouble("avg_completion_ratio") ?: -1.0,
                    0.001,
                    "(1.0+0.5+0.8)/3 = 0.7667 → 0.77",
                )
                assertEquals(
                    0.2857,
                    progress.getDouble("progress_ratio") ?: -1.0,
                    0.0001,
                    "完成天数/计划天数 = 2/7 = 0.2857",
                )
                assertEquals("2026-08-05", progress.getString("last_session_date"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `区间过滤只影响列表_progress仍汇总全部会话`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 1.0)).compose { (s1, _) ->
            ctx.verify { assertEquals(201, s1, "08-03 必须 201") }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-05", 1.0))
        }.compose { (s2, _) ->
            ctx.verify { assertEquals(201, s2, "08-05 必须 201") }
            post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-07", 1.0))
        }.compose { (s3, _) ->
            ctx.verify { assertEquals(201, s3, "08-07 必须 201") }
            get(vertx, "$SESSIONS?plan_id=$PLAN_A&from=${enc("2026-08-03")}&to=${enc("2026-08-05")}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "区间查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(2L, body!!.getJsonObject("meta").getLong("total"), "区间含端点：08-03、08-05 两条")
                assertEquals(2, body.getJsonArray("records").size())
                assertEquals(
                    3,
                    body.getJsonObject("progress").getInteger("session_count"),
                    "progress 汇总该计划全部会话，不受区间/分页影响",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 非法计划 / 非法参数
    // ========================================================================

    @Test
    fun `非法计划id_不存在404_非儿童康复400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, SESSIONS, sessionBody("${PREFIX}nonexistent-$RUN", "2026-08-03", 1.0))
            .compose { (s1, missing) ->
                ctx.verify {
                    assertEquals(404, s1, "计划不存在必须 404，实际: $missing")
                }
                post(vertx, SESSIONS, sessionBody(HOME_PLAN, "2026-08-03", 1.0))
            }
            .compose { (s2, wrongType) ->
                ctx.verify {
                    assertEquals(400, s2, "挂在非 CHILD_REHAB 周期必须 400，实际: $wrongType")
                    assertTrue(
                        wrongType?.getString("error")?.contains("not a child rehab plan") == true,
                        "错误文案必须指明计划类型不符，实际: ${wrongType?.getString("error")}",
                    )
                }
                post(vertx, SESSIONS, JsonObject().put("session_date", "2026-08-03"))
            }
            .compose { (s3, noPlan) ->
                ctx.verify {
                    assertEquals(400, s3, "缺 plan_id 必须 400，实际: $noPlan")
                }
                post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 0.0))
            }
            .compose { (s4, zeroRatio) ->
                ctx.verify {
                    assertEquals(400, s4, "completion_ratio=0 必须 400，实际: $zeroRatio")
                }
                post(vertx, SESSIONS, sessionBody(PLAN_A, "2026-08-03", 1.5))
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                    val (status, body) = ar.result()
                    assertEquals(400, status, "completion_ratio>1 必须 400，实际: $body")
                    assertEquals(
                        0L,
                        queryLong("SELECT count(*) FROM nursing.nursing_child_rehab_sessions WHERE plan_id = '$PLAN_A'"),
                        "非法请求不得落库",
                    )
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  5) 空结果形状 / 计划无起止日期
    // ========================================================================

    @Test
    fun `空列表形状与无起止日期计划的progress口径`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "$SESSIONS?patient_id=${enc(UNKNOWN_CHILD)}").compose { (s1, empty) ->
            ctx.verify {
                assertEquals(200, s1, "空列表必须 200，实际: $empty")
                assertEquals(0, empty!!.getJsonArray("records").size())
                assertEquals(0L, empty.getJsonObject("meta").getLong("total"))
                assertNull(empty.getJsonObject("progress"), "未传 plan_id 时不返回 progress")
            }
            get(vertx, "$SESSIONS?plan_id=$PLAN_NO_DATES")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "无起止日期计划查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(0L, body!!.getJsonObject("meta").getLong("total"))
                val progress = body.getJsonObject("progress")
                assertNotNull(progress)
                assertEquals(0, progress!!.getInteger("session_count"))
                assertNull(progress.getInteger("planned_days"), "计划无起止日期 → planned_days 为 null")
                assertEquals(0.0, progress.getDouble("progress_ratio") ?: -1.0, 0.0001, "无计划天数 → 进度比 0")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  HTTP / JDBC 辅助
    // ========================================================================

    private fun sessionBody(planId: String, date: String, ratio: Double): JsonObject = JsonObject()
        .put("plan_id", planId)
        .put("session_date", date)
        .put("completion_ratio", ratio)

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

    private fun get(vertx: Vertx, path: String): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.GET, path)

    private fun post(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.POST, path, body)

    /** 自包含：重置隔离库并应用 nursing 段迁移（V400–V410）。 */
    private fun resetTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    /** 护理服务层要 join `healthcare.patients` 取 `patient_name`，故先建最小表（无硬外键依赖）。 */
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

    /** 自带护理侧清理（会话 → 明细 → 计划 → 周期 → 患者）。 */
    private fun cleanupFixtures() {
        exec(
            "DELETE FROM nursing.nursing_child_rehab_sessions WHERE plan_id IN (" +
                "SELECT id FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'))",
        )
        exec(
            "DELETE FROM nursing.nursing_plan_items WHERE plan_id IN (" +
                "SELECT id FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'))",
        )
        exec(
            "DELETE FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%')",
        )
        exec("DELETE FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    /** 残差必须为 0；否则说明 fixture 泄漏。 */
    private fun assertNoResidual() {
        val residuals = mapOf(
            "nursing.nursing_child_rehab_sessions" to
                "SELECT count(*) FROM nursing.nursing_child_rehab_sessions WHERE plan_id IN (" +
                "SELECT id FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'))",
            "nursing.nursing_plans" to
                "SELECT count(*) FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%')",
            "nursing.nursing_service_periods" to
                "SELECT count(*) FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'",
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
