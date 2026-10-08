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
import org.junit.jupiter.api.Assertions.assertThrows
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
 * 054 B1（儿童康复服务周期 + 训练计划）**真库集成测试**——唯一事实源为隔离 `aceso_test`。
 *
 * 覆盖（054 §8.2 / task-24）：
 *  1. `V409` 真迁移：`service_type` CHECK 重建**含 `CHILD_REHAB`**（且仍拒绝未知类型）；
 *     部分唯一索引 `uq_nursing_service_periods_active_child_rehab` 生效。
 *  2. 周期 `encounter_id IS NULL`、`service_type='CHILD_REHAB'`、`status='ACTIVE'` 真落库。
 *  3. 同儿童重复启用周期 → **409**，DB 仅一行；「暂停后重新启用」撞已有启用周期 → 409；
 *     **并发创建**下由唯一索引兜底（恰好一个 201、一个 409）。
 *  4. 计划/明细复用 `nursing_plans` / `nursing_plan_items`，`patient_id` 由 join 周期**派生**
 *     （`nursing_plans` **无** `patient_id` 列）；`?patient_id=` 走跨表过滤。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 nursing 段 V400–V410 + inventories V200–V204）：
 * ```
 * cd service-vertx-kotlin && set -a && . apps/aceso/.env && set +a
 * ./gradlew :libs:nursing:test --rerun --tests '*ChildRehabPeriodIntegrationTest' \
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
class ChildRehabPeriodIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054b-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD_A = "${PREFIX}a-$RUN"
        private val CHILD_B = "${PREFIX}b-$RUN"
        private val CHILD_C = "${PREFIX}c-$RUN"
        private val UNKNOWN_CHILD = "${PREFIX}missing-$RUN"
        private val HOME_CARE_PERIOD = "${PREFIX}home-period-$RUN"

        private const val TESTER = "qa054b-tester"

        private const val PERIODS = "/nursing/v1/child-rehab-periods"
        private const val PLANS = "/nursing/v1/child-rehab-plans"
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
            // 模拟 App 装配层的认证总闸：先写入 userId（042 口径），再交给护理模块路由
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
        insertPatient(CHILD_A, "054B康复儿童甲")
        insertPatient(CHILD_B, "054B康复儿童乙")
        insertPatient(CHILD_C, "054B康复儿童丙")
        // T7 用：非儿童康复类型（HOME_CARE）的周期，encounter_id 允许为空
        exec(
            "INSERT INTO nursing.nursing_service_periods " +
                "(id, patient_id, service_type, encounter_id, start_date, status, created_at, updated_at) " +
                "VALUES ('$HOME_CARE_PERIOD', '$CHILD_A', 'HOME_CARE', NULL, DATE '2026-08-03', 'ACTIVE', now(), now())",
        )
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V409 真迁移
    // ========================================================================

    @Test
    fun `V409迁移_CHILD_REHAB进CHECK且部分唯一索引生效`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            val checkDef = queryString(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                    "WHERE conname = 'nursing_service_periods_service_type_check'",
            )
            assertNotNull(checkDef, "V409 必须重建 service_type CHECK")
            assertTrue(
                checkDef!!.contains("CHILD_REHAB"),
                "CHECK 白名单必须含 CHILD_REHAB，实际: $checkDef",
            )
            val indexDef = queryString(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'nursing' " +
                    "AND indexname = 'uq_nursing_service_periods_active_child_rehab'",
            )
            assertNotNull(indexDef, "V409 必须建出 uq_nursing_service_periods_active_child_rehab")
            assertTrue(
                indexDef!!.contains("CHILD_REHAB") && indexDef.contains("ACTIVE"),
                "唯一索引必须带 service_type=CHILD_REHAB 与 status=ACTIVE 谓词，实际: $indexDef",
            )
        }
        // CHECK 仍拒绝未知服务类型（白名单不是「全放开」）
        assertThrows(Exception::class.java) {
            exec(
                "INSERT INTO nursing.nursing_service_periods " +
                    "(id, patient_id, service_type, encounter_id, start_date, status) " +
                    "VALUES ('${PREFIX}bogus-$RUN', '$CHILD_A', 'BOGUS_TYPE', NULL, CURRENT_DATE, 'ACTIVE')",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 周期创建
    // ========================================================================

    @Test
    fun `创建儿童康复周期_service_type与encounter为空且操作人取会话`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, PERIODS, periodBody(CHILD_A)).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "创建失败: ${ar.cause()?.message}")
                val (status, created) = ar.result()
                assertEquals(201, status, "创建必须 201，实际: $created")
                assertEquals(ChildRehabPeriodService.SERVICE_TYPE, created!!.getString("service_type"))
                assertEquals("ACTIVE", created.getString("status"))
                assertEquals("2026-08-03", created.getString("start_date"))
                assertEquals(TESTER, created.getString("coordinator"), "coordinator 缺省取认证主体（042）")

                // 真库落库：encounter_id 必须为 NULL（V404 第二分支 + V409 组合）
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_service_periods WHERE id = '${created.getString("id")}' " +
                            "AND encounter_id IS NULL AND service_type = 'CHILD_REHAB' AND status = 'ACTIVE'",
                    ),
                    "周期必须 encounter_id IS NULL、service_type=CHILD_REHAB、status=ACTIVE",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `同儿童重复启用周期_409且仅一行`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, PERIODS, periodBody(CHILD_A)).compose { (s1, first) ->
            ctx.verify { assertEquals(201, s1, "首次创建必须 201，实际: $first") }
            post(vertx, PERIODS, periodBody(CHILD_A))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "第二次请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(409, status, "同儿童重复启用周期必须 409，实际: $body")
                assertTrue(
                    body?.getString("error")?.contains("已有启用中的康复服务周期") == true,
                    "409 文案必须明确，实际: ${body?.getString("error")}",
                )
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_service_periods " +
                            "WHERE patient_id = '$CHILD_A' AND service_type = 'CHILD_REHAB' AND status = 'ACTIVE'",
                    ),
                    "部分唯一索引必须兜底为一行",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `暂停后重新启用撞已有启用周期_409`(vertx: Vertx, ctx: VertxTestContext) {
        var periodA = ""
        post(vertx, PERIODS, periodBody(CHILD_A)).compose { (s1, first) ->
            ctx.verify { assertEquals(201, s1, "周期 A 创建必须 201，实际: $first") }
            periodA = first!!.getString("id")
            patch(vertx, "$PERIODS/$periodA/status", JsonObject().put("status", "SUSPENDED"))
        }.compose { (s2, suspended) ->
            ctx.verify {
                assertEquals(200, s2, "ACTIVE→SUSPENDED 必须 200，实际: $suspended")
                assertEquals("SUSPENDED", suspended!!.getString("status"))
            }
            // 暂停后可为同儿童再建一个 ACTIVE 周期
            post(vertx, PERIODS, periodBody(CHILD_A))
        }.compose { (s3, second) ->
            ctx.verify { assertEquals(201, s3, "暂停后新建 ACTIVE 必须 201，实际: $second") }
            // 再启用周期 A → 撞已有 ACTIVE → 409
            patch(vertx, "$PERIODS/$periodA/status", JsonObject().put("status", "ACTIVE"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "重新启用请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(409, status, "重新启用撞已有启用周期必须 409，实际: $body")
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_service_periods " +
                            "WHERE patient_id = '$CHILD_A' AND service_type = 'CHILD_REHAB' AND status = 'ACTIVE'",
                    ),
                    "重新启用被拒后仍只有一行 ACTIVE",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `并发创建_唯一索引兜底恰好一个成功`(vertx: Vertx, ctx: VertxTestContext) {
        val first = post(vertx, PERIODS, periodBody(CHILD_C)).map { it.first }
        val second = post(vertx, PERIODS, periodBody(CHILD_C)).map { it.first }
        Future.all(first, second).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "并发请求失败: ${ar.cause()?.message}")
                val statuses = listOf(
                    ar.result().resultAt<Int>(0),
                    ar.result().resultAt<Int>(1),
                ).sorted()
                assertEquals(listOf(201, 409), statuses, "并发下必须恰好一个成功、一个 409")
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_service_periods " +
                            "WHERE patient_id = '$CHILD_C' AND service_type = 'CHILD_REHAB' AND status = 'ACTIVE'",
                    ),
                    "并发下 DB 仍只有一行（唯一索引兜底）",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期状态机_终态不可再流转且非法值400`(vertx: Vertx, ctx: VertxTestContext) {
        var periodId = ""
        post(vertx, PERIODS, periodBody(CHILD_B)).compose { (s1, created) ->
            ctx.verify { assertEquals(201, s1, "创建必须 201，实际: $created") }
            periodId = created!!.getString("id")
            patch(vertx, "$PERIODS/$periodId/status", JsonObject().put("status", "COMPLETED"))
        }.compose { (s2, completed) ->
            ctx.verify {
                assertEquals(200, s2, "ACTIVE→COMPLETED 必须 200，实际: $completed")
                assertEquals("COMPLETED", completed!!.getString("status"))
            }
            patch(vertx, "$PERIODS/$periodId/status", JsonObject().put("status", "ACTIVE"))
        }.compose { (s3, illegal) ->
            ctx.verify {
                assertEquals(400, s3, "终态再流转必须 400，实际: $illegal")
                assertNotNull(illegal?.getString("error"))
            }
            patch(vertx, "$PERIODS/$periodId/status", JsonObject().put("status", "NOT_A_STATUS"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "非法状态值请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "非法状态值必须 400，实际: $body")
                assertEquals(
                    "COMPLETED",
                    queryString("SELECT status FROM nursing.nursing_service_periods WHERE id = '$periodId'"),
                    "状态必须真落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 计划与明细
    // ========================================================================

    @Test
    fun `训练计划复用nursing_plans_patient_id由join派生`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'nursing' " +
                        "AND table_name = 'nursing_plans' AND column_name = 'patient_id'",
                ),
                "nursing_plans 不得新增 patient_id 列（L8 裁定：由 join 周期派生）",
            )
        }

        var periodId = ""
        post(vertx, PERIODS, periodBody(CHILD_A)).compose { (s1, period) ->
            ctx.verify { assertEquals(201, s1, "周期创建必须 201，实际: $period") }
            periodId = period!!.getString("id")
            post(
                vertx,
                PLANS,
                JsonObject()
                    .put("period_id", periodId)
                    .put("plan_name", "054B 上肢功能训练")
                    .put("goals", "改善关节活动度")
                    .put("start_date", "2026-08-03")
                    .put("items", JsonArray()
                        .add(JsonObject().put("action", "关节活动度训练").put("frequency_code", "QD")
                            .put("frequency_name", "每日一次").put("duration_days", 30))
                        .add(JsonObject().put("action", "肌力训练").put("duration_days", 14))),
            )
        }.compose { (s2, plan) ->
            ctx.verify {
                assertEquals(201, s2, "计划创建必须 201，实际: $plan")
                assertEquals(CHILD_A, plan!!.getString("patient_id"), "patient_id 必须由 join 周期派生")
                assertEquals(periodId, plan.getString("period_id"))
                assertEquals("ACTIVE", plan.getString("status"))
                assertEquals(TESTER, plan.getString("created_by"), "created_by 取认证主体")
                val items = plan.getJsonArray("items")
                assertEquals(2, items.size(), "明细必须一并写入并读回")
                assertEquals("关节活动度训练", items.getJsonObject(0).getString("action"))
            }
            // 跨表派生筛选
            get(vertx, "$PLANS?patient_id=${enc(CHILD_A)}")
        }.compose { (s3, listed) ->
            ctx.verify {
                assertEquals(200, s3, "列表必须 200，实际: $listed")
                assertEquals(1L, listed!!.getJsonObject("meta").getLong("total"), "?patient_id= 必须走 join 派生过滤")
                assertEquals(CHILD_A, listed.getJsonArray("records").getJsonObject(0).getString("patient_id"))
                assertEquals(
                    2,
                    listed.getJsonArray("records").getJsonObject(0).getJsonArray("items").size(),
                )
            }
            // DB 侧：计划挂 CHILD_REHAB 周期、encounter_id 为 NULL、明细 2 行
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM nursing.nursing_plans WHERE period_id = '$periodId' AND encounter_id IS NULL",
                ),
                "nursing_plans.encounter_id 必须为 NULL（儿童康复无入住）",
            )
            assertEquals(
                2L,
                queryLong(
                    "SELECT count(*) FROM nursing.nursing_plan_items WHERE plan_id IN " +
                        "(SELECT id FROM nursing.nursing_plans WHERE period_id = '$periodId')",
                ),
                "明细必须真落 nursing_plan_items",
            )
            get(vertx, "$PLANS?period_id=$periodId")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "按周期过滤失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划挂非儿童康复周期400_周期不存在404`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            PLANS,
            JsonObject().put("period_id", HOME_CARE_PERIOD).put("plan_name", "挂错类型"),
        ).compose { (s1, wrongType) ->
            ctx.verify {
                assertEquals(400, s1, "挂在非 CHILD_REHAB 周期必须 400，实际: $wrongType")
                assertTrue(
                    wrongType?.getString("error")?.contains("not a child rehab period") == true,
                    "错误文案必须指明周期类型不符，实际: ${wrongType?.getString("error")}",
                )
            }
            post(
                vertx,
                PLANS,
                JsonObject().put("period_id", "${PREFIX}nonexistent-$RUN").put("plan_name", "不存在周期"),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "不存在周期请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(404, status, "周期不存在必须 404，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 列表空结果形状
    // ========================================================================

    @Test
    fun `列表空结果返回records空数组与total0`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "$PERIODS?patient_id=${enc(UNKNOWN_CHILD)}").compose { (s1, periods) ->
            ctx.verify {
                assertEquals(200, s1, "周期列表必须 200，实际: $periods")
                assertEquals(0, periods!!.getJsonArray("records").size())
                assertEquals(0L, periods.getJsonObject("meta").getLong("total"))
            }
            get(vertx, "$PLANS?patient_id=${enc(UNKNOWN_CHILD)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "计划列表失败: ${ar.cause()?.message}")
                val (status, plans) = ar.result()
                assertEquals(200, status)
                assertEquals(0, plans!!.getJsonArray("records").size())
                assertEquals(0L, plans.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  HTTP / JDBC 辅助
    // ========================================================================

    private fun periodBody(patientId: String): JsonObject = JsonObject()
        .put("patient_id", patientId)
        .put("start_date", "2026-08-03")
        .put("end_date", "2026-11-03")

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

    private fun patch(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.PATCH, path, body)

    private fun insertPatient(id: String, name: String) {
        exec(
            "INSERT INTO healthcare.patients (id, name, gender, person_type, status) " +
                "VALUES ('$id', '$name', '男', '儿童', 'ACTIVE')",
        )
    }

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

    /** 自带护理侧清理（按患者前缀串联：明细 → 计划 → 周期 → 患者）。 */
    private fun cleanupFixtures() {
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
            "nursing.nursing_service_periods" to
                "SELECT count(*) FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'",
            "nursing.nursing_plans" to
                "SELECT count(*) FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%')",
            "nursing.nursing_plan_items" to
                "SELECT count(*) FROM nursing.nursing_plan_items WHERE plan_id IN (" +
                "SELECT id FROM nursing.nursing_plans WHERE period_id IN (" +
                "SELECT id FROM nursing.nursing_service_periods WHERE patient_id LIKE '$PREFIX%'))",
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
