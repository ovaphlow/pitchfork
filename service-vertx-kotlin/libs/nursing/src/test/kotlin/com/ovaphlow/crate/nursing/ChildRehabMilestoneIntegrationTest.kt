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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 054 B3（儿童康复里程碑：登记态落库 + 读时四态派生）**真库集成测试**。
 *
 * 覆盖（054 §8.2 / task-24，dev 标注的 DB-free 盲区）：
 *  1. `V411` 唯一索引 `uq_child_rehab_milestones_patient_code` + `ON CONFLICT`：
 *     同 `(patient_id, milestone_code)` POST 两次 → **只一行**、第二次覆盖 `achieved_at`。
 *  2. `expected_age_months INT` / `achieved_at DATE` 编解码回读与 [ChildRehabMilestone.CATALOG] 一致。
 *  3. **读时四态跨月推进**：同一儿童推进月龄后 `临近 → 逾期`，且落库 `status` **保持登记态不变**（状态不落库）。
 *  4. 常量表之外的 code 在 `GET /child-rehab-milestone-status/:patient_id` 里**不丢**。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 nursing 段 V400–V411 + inventories V200–V204）：
 * ```
 * cd service-vertx-kotlin && export PITCHFORK_DB_PASSWORD=<hidden>
 * ./gradlew :libs:nursing:test --rerun --tests '*ChildRehabMilestoneIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 每次运行唯一后缀；测试前后自清并断言残差 0；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildRehabMilestoneIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054b3-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD_A = "${PREFIX}a-$RUN"
        private val UNKNOWN_CHILD = "${PREFIX}missing-$RUN"
        private val CUSTOM_CODE = "CUSTOM_QA054B3_$RUN"

        private const val TESTER = "qa054b3-tester"
        private const val MILESTONES = "/nursing/v1/child-rehab-milestones"
        private const val MILESTONE_STATUS = "/nursing/v1/child-rehab-milestone-status"

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

    /** 业务当天（与服务端 `today()` 同口径），用于动态构造月龄 fixture。 */
    private val today: LocalDate = LocalDate.now(BUSINESS_ZONE)

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
        insertPatient(CHILD_A, "054B3里程碑儿童", birthDateForAge(3))
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) 唯一索引 + ON CONFLICT 幂等覆盖
    // ========================================================================

    @Test
    fun `V411迁移_唯一索引与ON_CONFLICT幂等覆盖`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'nursing' " +
                        "AND indexname = 'uq_child_rehab_milestones_patient_code'",
                ),
                "V411 必须建出 (patient_id, milestone_code) 唯一索引",
            )
        }

        post(vertx, MILESTONES, milestoneBody(CHILD_A, "HEAD_CONTROL")).compose { (s1, first) ->
            ctx.verify {
                assertEquals(201, s1, "首次登记必须 201，实际: $first")
                assertEquals("HEAD_CONTROL", first!!.getString("milestone_code"))
                assertEquals("未达成", first.getString("status"), "未传 achieved_at → 登记态未达成")
                assertNull(first.getString("achieved_at"))
            }
            val firstId = first!!.getString("id")
            post(
                vertx,
                MILESTONES,
                milestoneBody(CHILD_A, "HEAD_CONTROL").put("achieved_at", "2026-08-05"),
            ).compose { (s2, second) ->
                ctx.verify {
                    assertEquals(201, s2, "重复登记必须 201（幂等 upsert），实际: $second")
                    assertEquals(firstId, second!!.getString("id"), "同 (儿童, code) 必须命中同一行")
                    assertEquals("已达成", second.getString("status"))
                    assertEquals("2026-08-05", second.getString("achieved_at"))
                }
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_child_rehab_milestones " +
                            "WHERE patient_id = '$CHILD_A' AND milestone_code = 'HEAD_CONTROL'",
                    ),
                    "同 (儿童, 里程碑) 只允许一行（唯一索引兜底）",
                )
                assertEquals(
                    "2026-08-05",
                    queryString(
                        "SELECT achieved_at::text FROM nursing.nursing_child_rehab_milestones " +
                            "WHERE patient_id = '$CHILD_A' AND milestone_code = 'HEAD_CONTROL'",
                    ),
                    "第二次登记必须覆盖 achieved_at",
                )
                get(vertx, "$MILESTONES?patient_id=${enc(CHILD_A)}")
            }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "列表失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  2) INT / DATE 编解码与常量表一致
    // ========================================================================

    @Test
    fun `INT与DATE编解码回读与常量表一致`(vertx: Vertx, ctx: VertxTestContext) {
        val definition = ChildRehabMilestone.definitionOf("WALK_INDEPENDENT")!!
        post(
            vertx,
            MILESTONES,
            milestoneBody(CHILD_A, "WALK_INDEPENDENT").put("achieved_at", "2026-09-01"),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "登记失败: ${ar.cause()?.message}")
                val (status, created) = ar.result()
                assertEquals(201, status, "实际: $created")
                assertEquals(definition.name, created!!.getString("milestone_name"), "名称必须取常量表")
                assertEquals(
                    definition.expectedAgeMonths,
                    created.getInteger("expected_age_months"),
                    "expected_age_months 必须取常量表（INT 编解码）",
                )
                assertEquals("2026-09-01", created.getString("achieved_at"), "DATE 回读必须为 yyyy-MM-dd")
                assertEquals(
                    definition.expectedAgeMonths.toString(),
                    queryString(
                        "SELECT expected_age_months::text FROM nursing.nursing_child_rehab_milestones " +
                            "WHERE patient_id = '$CHILD_A' AND milestone_code = 'WALK_INDEPENDENT'",
                    ),
                    "INT 必须真落库",
                )
                assertEquals(
                    "2026-09-01",
                    queryString(
                        "SELECT achieved_at::text FROM nursing.nursing_child_rehab_milestones " +
                            "WHERE patient_id = '$CHILD_A' AND milestone_code = 'WALK_INDEPENDENT'",
                    ),
                    "DATE 必须真落库",
                )
                // 常量表派生字段忽略客户端提交
                assertEquals(
                    definition.name,
                    queryString(
                        "SELECT milestone_name FROM nursing.nursing_child_rehab_milestones " +
                            "WHERE patient_id = '$CHILD_A' AND milestone_code = 'WALK_INDEPENDENT'",
                    ),
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 读时四态跨月推进（状态不落库）
    // ========================================================================

    @Test
    fun `读时四态_临近转逾期且状态不落库`(vertx: Vertx, ctx: VertxTestContext) {
        // HEAD_CONTROL 期望 4 月龄；当前 3 月龄 → 临近（窗口 1）
        post(vertx, MILESTONES, milestoneBody(CHILD_A, "HEAD_CONTROL")).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "登记必须 201，实际: $created")
                assertEquals("未达成", created!!.getString("status"), "落库只记登记态")
            }
            get(vertx, "$MILESTONE_STATUS/${enc(CHILD_A)}")
        }.compose { (s2, statusBody) ->
            ctx.verify {
                assertEquals(200, s2, "状态查询必须 200，实际: $statusBody")
                assertEquals(3, statusBody!!.getInteger("age_months"), "fixture 月龄必须为 3")
                assertEquals(
                    ChildRehabMilestone.STATE_NEAR,
                    itemState(statusBody, "HEAD_CONTROL"),
                    "3 月龄未达成、期望 4 → 临近",
                )
            }
            // 推进月龄到 4 → 逾期（同一行、无任何写入）
            exec("UPDATE healthcare.patients SET birth_date = DATE '${birthDateForAge(4)}' WHERE id = '$CHILD_A'")
            get(vertx, "$MILESTONE_STATUS/${enc(CHILD_A)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "推进月龄后查询失败: ${ar.cause()?.message}")
                val (status, statusBody) = ar.result()
                assertEquals(200, status)
                assertEquals(4, statusBody!!.getInteger("age_months"))
                assertEquals(
                    ChildRehabMilestone.STATE_OVERDUE,
                    itemState(statusBody, "HEAD_CONTROL"),
                    "4 月龄未达成、期望 4 → 逾期",
                )
                assertEquals(
                    "未达成",
                    queryString(
                        "SELECT status FROM nursing.nursing_child_rehab_milestones " +
                            "WHERE patient_id = '$CHILD_A' AND milestone_code = 'HEAD_CONTROL'",
                    ),
                    "四态为读时派生，落库 status 必须保持登记态（不随月龄推进）",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `读时四态_已达成优先与未达边界`(vertx: Vertx, ctx: VertxTestContext) {
        // 0 月龄：期望 4 的 HEAD_CONTROL → 未达；期望 18 的 WALK → 未达
        exec("UPDATE healthcare.patients SET birth_date = DATE '${birthDateForAge(0)}' WHERE id = '$CHILD_A'")
        post(vertx, MILESTONES, milestoneBody(CHILD_A, "ROLL_OVER")).compose { (s1, created) ->
            ctx.verify { assertEquals(201, s1, "登记必须 201，实际: $created") }
            // 已达成优先于任何月龄判定
            post(
                vertx,
                MILESTONES,
                milestoneBody(CHILD_A, "HEAD_CONTROL").put("achieved_at", today.toString()),
            )
        }.compose { (s2, achieved) ->
            ctx.verify {
                assertEquals(201, s2, "登记达成必须 201，实际: $achieved")
                assertEquals("已达成", achieved!!.getString("status"))
            }
            get(vertx, "$MILESTONE_STATUS/${enc(CHILD_A)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "状态查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(0, body!!.getInteger("age_months"))
                assertEquals(
                    ChildRehabMilestone.STATE_ACHIEVED,
                    itemState(body, "HEAD_CONTROL"),
                    "已登记达成 → 已达成（优先）",
                )
                assertEquals(
                    ChildRehabMilestone.STATE_NOT_REACHED,
                    itemState(body, "ROLL_OVER"),
                    "0 月龄未达成、期望 6 → 未达",
                )
                assertEquals(
                    ChildRehabMilestone.STATE_NOT_REACHED,
                    itemState(body, "WALK_INDEPENDENT"),
                    "0 月龄未达成、期望 18 → 未达",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 常量表之外的 code 不丢
    // ========================================================================

    @Test
    fun `常量表之外的code在status中不丢`(vertx: Vertx, ctx: VertxTestContext) {
        exec(
            "INSERT INTO nursing.nursing_child_rehab_milestones " +
                "(id, patient_id, milestone_code, milestone_name, expected_age_months, achieved_at, " +
                "status, operator, created_at, updated_at) " +
                "VALUES ('${PREFIX}custom-$RUN', '$CHILD_A', '$CUSTOM_CODE', '自定义里程碑', NULL, NULL, " +
                "'未达成', 'fixture', now(), now())",
        )

        get(vertx, "$MILESTONE_STATUS/${enc(CHILD_A)}").onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "状态查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                val items = body!!.getJsonArray("items")
                assertEquals(
                    ChildRehabMilestone.CATALOG.size + 1,
                    items.size(),
                    "常量表全量 + 1 条自定义 code（不丢数据）",
                )
                assertEquals(
                    ChildRehabMilestone.STATE_NOT_REACHED,
                    itemState(body, CUSTOM_CODE),
                    "无期望月龄且未达成 → 未达",
                )
                val custom = (0 until items.size())
                    .map { items.getJsonObject(it) }
                    .first { it.getString("milestone_code") == CUSTOM_CODE }
                assertNull(custom.getInteger("expected_age_months"), "自定义 code 期望月龄为 null")
                assertEquals(CUSTOM_CODE, custom.getString("milestone_name"), "自定义 code 名称回落到 code")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) 非法参数与空结果
    // ========================================================================

    @Test
    fun `非法code与儿童不存在_空列表形状`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, MILESTONES, milestoneBody(CHILD_A, "NOT_IN_CATALOG")).compose { (s1, badCode) ->
            ctx.verify {
                assertEquals(400, s1, "常量表之外的 code 登记必须 400，实际: $badCode")
                assertTrue(badCode?.getString("error")?.contains("unknown milestone_code") == true)
            }
            post(vertx, MILESTONES, milestoneBody(UNKNOWN_CHILD, "HEAD_CONTROL"))
        }.compose { (s2, missingPatient) ->
            ctx.verify { assertEquals(404, s2, "儿童不存在必须 404，实际: $missingPatient") }
            get(vertx, "$MILESTONES?patient_id=${enc(UNKNOWN_CHILD)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "空列表失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(0, body!!.getJsonArray("records").size())
                assertEquals(0L, body.getJsonObject("meta").getLong("total"))
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM nursing.nursing_child_rehab_milestones WHERE patient_id = '$CHILD_A'"),
                    "非法请求不得落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 找到「日历月龄恰好等于 [target]」的出生日期（月末回退边界也稳）。 */
    private fun birthDateForAge(target: Int): LocalDate {
        var candidate = today.minusMonths(target.toLong())
        var guard = 0
        while (ChildRehabMilestone.ageMonths(candidate, today) < target && guard++ < 70) {
            candidate = candidate.minusDays(1)
        }
        guard = 0
        while (ChildRehabMilestone.ageMonths(candidate, today) > target && guard++ < 70) {
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    private fun milestoneBody(patientId: String, code: String): JsonObject = JsonObject()
        .put("patient_id", patientId)
        .put("milestone_code", code)

    /** 从 status 响应里取某 code 的读时 state。 */
    private fun itemState(body: JsonObject, code: String): String? {
        val items = body.getJsonArray("items") ?: return null
        for (i in 0 until items.size()) {
            val item = items.getJsonObject(i)
            if (item.getString("milestone_code") == code) return item.getString("state")
        }
        return null
    }

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

    private fun insertPatient(id: String, name: String, birthDate: LocalDate) {
        exec(
            "INSERT INTO healthcare.patients (id, name, gender, person_type, birth_date, status) " +
                "VALUES ('$id', '$name', '男', '儿童', DATE '$birthDate', 'ACTIVE')",
        )
    }

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
        exec("ALTER TABLE healthcare.patients ADD COLUMN IF NOT EXISTS birth_date DATE")
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
        exec("DELETE FROM nursing.nursing_child_rehab_milestones WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "nursing.nursing_child_rehab_milestones" to
                "SELECT count(*) FROM nursing.nursing_child_rehab_milestones WHERE patient_id LIKE '$PREFIX%'",
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
