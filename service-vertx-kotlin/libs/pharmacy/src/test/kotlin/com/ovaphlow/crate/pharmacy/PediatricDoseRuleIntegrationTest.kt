package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * 054 D 链（儿科用药规则 D1/D2）**真库集成测试**——唯一事实源为隔离 `aceso_test`。
 *
 * 覆盖（054 §8.4 / task-27）：
 *  1. `V304` 真迁移：建表、8 条 CHECK 约束、按 `(material_id, status)` 索引生效；
 *     **`material_id` 对 `public.materials` 无外键**（跨 schema 弱关联）。
 *  2. 规则库建/查/改真库闭环；`material_name` 快照、`created_by` 取会话主体、
 *     未知字段 400、数值列 6 位小数取整落库。
 *  3. `material_id` 存在性由**服务层**校验（无 FK 兜底）：不存在 → 404 且**零落库**。
 *  4. 按药品 + 体重/年龄匹配：具体度优先（双区间 > 体重 > 年龄 > 通用）、**闭区间含端点**、
 *     区间不命中回落通用规则（`GENERIC_FALLBACK`）、封顶与向下取整摊回。
 *  5. **046 冻结路由向后兼容**：`POST /dispenses` 空体仍是既有 400（非 404/500）。
 *  6. 空列表形状 `{records:[],meta:{total:0}}`；残差 0。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 pharmacy 段 V300–V304）：
 * ```
 * cd service-vertx-kotlin
 * export PITCHFORK_DB_PASSWORD=<hidden> \
 *        PITCHFORK_TEST_DB_HOST=127.0.0.1 PITCHFORK_TEST_DB_PORT=55432 \
 *        PITCHFORK_TEST_DB_DATABASE=aceso_test PITCHFORK_TEST_DB_USER=ovaphlow
 * ./gradlew :libs:pharmacy:test --rerun --tests '*PediatricDoseRuleIntegrationTest' \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * （`libs/pharmacy/build.gradle.kts` 暂不转发 `integration.db.*` 系统属性，故用环境变量口径；
 * 若 dev 补上转发，`-Dintegration.db.*` 亦可。）
 * 注：pharmacy 测试 classpath 不含 inventories 迁移，故本类自带最小 `public.materials`
 * （只含服务层读取的 `id`/`name`）作为弱关联对端；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIf("integrationDbAvailable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PediatricDoseRuleIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054d-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val MAT_A = "${PREFIX}a-$RUN"
        private val MAT_B = "${PREFIX}b-$RUN"
        private val MAT_MISSING = "${PREFIX}missing-$RUN"

        private const val TESTER = "qa054d-tester"
        private const val RULES = "/pharmacy/v1/pediatric-dose-rules"
        private const val CALC = "/pharmacy/v1/pediatric-dose-calculations"
        private const val DISPENSES = "/pharmacy/v1/dispenses"

        /**
         * 真库门控：**显式给出测试库 host 才运行**（系统属性 `integration.db.host` 或环境变量
         * `PITCHFORK_TEST_DB_HOST`），绝不回落到 5432 开发库。
         *
         * 注意：`libs/pharmacy/build.gradle.kts` 的 `tasks.withType<Test>` 目前**不转发**
         * `integration.db.*` 系统属性（dining/nursing 会转发），故本类同时支持环境变量口径。
         */
        @JvmStatic
        fun integrationDbAvailable(): Boolean = configuredHost() != null

        private fun configuredHost(): String? =
            System.getProperty("integration.db.host")?.takeIf { it.isNotBlank() }
                ?: System.getenv("PITCHFORK_TEST_DB_HOST")?.takeIf { it.isNotBlank() }

        private fun configuredPort(): String =
            System.getProperty("integration.db.port")?.takeIf { it.isNotBlank() }
                ?: System.getenv("PITCHFORK_TEST_DB_PORT")?.takeIf { it.isNotBlank() }
                ?: "55432"

        private fun configuredUser(): String =
            System.getProperty("integration.db.user")?.takeIf { it.isNotBlank() }
                ?: System.getenv("PITCHFORK_TEST_DB_USER")?.takeIf { it.isNotBlank() }
                ?: "ovaphlow"
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
        host = configuredHost() ?: "127.0.0.1"
        port = configuredPort()
        user = configuredUser()
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        Assumptions.assumeTrue(
            password.isNotBlank(),
            "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行",
        )

        try {
            resetTestDb()
            createMinimalMaterialsSchema()
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            client = vertx.createHttpClient()

            val root = Router.router(vertx)
            root.route("/pharmacy/v1/*").handler { rctx ->
                rctx.put("userId", TESTER)
                rctx.next()
            }
            root.route("/pharmacy/v1/*").subRouter(
                PharmacyRoutes.create(
                    vertx,
                    pool,
                    stubMedicalOrderReader,
                    stubOutbound,
                    stubInbound,
                    stubRequisition,
                    stubReceipt,
                    io.vertx.core.Handler<RoutingContext> { rctx -> rctx.next() },
                    null,
                    null,
                ),
            )

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
        exec("INSERT INTO public.materials (id, name) VALUES ('$MAT_A', '054D 布洛芬混悬液')")
        exec("INSERT INTO public.materials (id, name) VALUES ('$MAT_B', '054D 对乙酰氨基酚滴剂')")
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V304 真迁移
    // ========================================================================

    @Test
    fun `V304迁移_建表约束索引生效且无外键弱关联`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "pharmacy.pharmacy_pediatric_dose_rules",
                queryString("SELECT to_regclass('pharmacy.pharmacy_pediatric_dose_rules')::text"),
                "V304 必须真实建出规则表",
            )
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'pharmacy' " +
                        "AND indexname = 'idx_pediatric_dose_rules_material_status'",
                ),
                "V304 必须建出 (material_id, status) 索引",
            )
            assertEquals(
                8L,
                queryLong(
                    "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid " +
                        "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                        "WHERE n.nspname = 'pharmacy' AND t.relname = 'pharmacy_pediatric_dose_rules' " +
                        "AND c.contype = 'c'",
                ),
                "V304 的 8 条 CHECK 约束必须全部生效",
            )
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid " +
                        "JOIN pg_namespace n ON n.oid = t.relnamespace " +
                        "WHERE n.nspname = 'pharmacy' AND t.relname = 'pharmacy_pediatric_dose_rules' " +
                        "AND c.contype = 'f'",
                ),
                "material_id 对 public.materials 必须是弱关联（无外键），存在性由服务层校验",
            )
        }
        // CHECK 真生效：非法 status 与「无剂量定义」都必须被 DB 拒绝
        assertThrows(Exception::class.java) {
            exec(
                "INSERT INTO pharmacy.pharmacy_pediatric_dose_rules " +
                    "(id, material_id, material_name, unit, status, fixed_dose) " +
                    "VALUES ('${PREFIX}bad1-$RUN', '$MAT_A', 'x', 'mg', 'BOGUS', 1)",
            )
        }
        assertThrows(Exception::class.java) {
            exec(
                "INSERT INTO pharmacy.pharmacy_pediatric_dose_rules " +
                    "(id, material_id, material_name, unit, status) " +
                    "VALUES ('${PREFIX}bad2-$RUN', '$MAT_A', 'x', 'mg', '启用')",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 建查改闭环 + 数值编解码
    // ========================================================================

    @Test
    fun `规则建查改闭环_数值取整与快照与操作人`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            RULES,
            JsonObject()
                .put("material_id", MAT_A)
                .put("age_min_months", 6)
                .put("age_max_months", 12)
                .put("weight_min_kg", 5.5)
                .put("weight_max_kg", 10.25)
                .put("dose_per_kg", 0.1234567)
                .put("single_dose_max", 3)
                .put("daily_dose_max", 15)
                .put("doses_per_day", 3)
                .put("unit", "mg")
                .put("note", "054D 闭环用例"),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "创建规则必须 201，实际: $created")
                assertEquals(MAT_A, created!!.getString("material_id"))
                assertEquals("054D 布洛芬混悬液", created.getString("material_name"), "material_name 必须为目录快照")
                assertEquals(TESTER, created.getString("created_by"), "created_by 取认证主体（042）")
                assertEquals("启用", created.getString("status"), "status 缺省启用")
                assertEquals(6, created.getInteger("age_min_months") ?: -1)
                assertEquals(12, created.getInteger("age_max_months") ?: -1)
                assertEquals(3, created.getInteger("doses_per_day") ?: -1)
            }
            val ruleId = created!!.getString("id")
            // DB 侧：NUMERIC(20,6) 取整落库（0.1234567 → 0.123457）
            assertEquals(
                "0.123457",
                queryString(
                    "SELECT dose_per_kg::text FROM pharmacy.pharmacy_pediatric_dose_rules WHERE id = '$ruleId'",
                ),
                "NUMERIC(20,6) 必须按 HALF_UP 取整落库",
            )
            assertEquals(
                "5.500000",
                queryString(
                    "SELECT weight_min_kg::text FROM pharmacy.pharmacy_pediatric_dose_rules WHERE id = '$ruleId'",
                ),
                "数值列补零到 6 位",
            )
            get(vertx, "$RULES?material_id=${enc(MAT_A)}&status=${enc("启用")}")
        }.compose { (s2, listed) ->
            ctx.verify {
                assertEquals(200, s2, "列表必须 200，实际: $listed")
                assertEquals(1L, listed!!.getJsonObject("meta").getLong("total"))
                assertEquals(1, listed.getJsonArray("records").size())
                assertEquals(0.123457, listed.getJsonArray("records").getJsonObject(0).getDouble("dose_per_kg"), 0.0000001)
            }
            val rules = listed!!
            val ruleId = rules.getJsonArray("records").getJsonObject(0).getString("id")
            // PUT 语义：只覆盖请求体出现的字段；显式 null 清空可选区间
            put(
                vertx,
                "$RULES/$ruleId",
                JsonObject()
                    .put("fixed_dose", 250)
                    .put("age_min_months", null)
                    .put("age_max_months", null)
                    .put("status", "停用"),
            )
        }.compose { (s3, updated) ->
            ctx.verify {
                assertEquals(200, s3, "更新必须 200，实际: $updated")
                assertEquals("停用", updated!!.getString("status"))
                assertEquals(250.0, updated.getDouble("fixed_dose") ?: -1.0, 0.0001)
                assertEquals(null, updated.getValue("age_min_months"), "显式 null 必须清空可选字段")
                assertEquals(null, updated.getValue("age_max_months"))
                // 未出现的字段保持原值
                assertEquals(5.5, updated.getDouble("weight_min_kg") ?: -1.0, 0.000001)
                assertEquals("mg", updated.getString("unit"))
                // 停用后按 status=启用 过滤不可见，但记录仍在
                assertEquals(
                    1L,
                    queryLong("SELECT count(*) FROM pharmacy.pharmacy_pediatric_dose_rules WHERE material_id = '$MAT_A'"),
                )
            }
            get(vertx, "$RULES?material_id=${enc(MAT_A)}&status=${enc("启用")}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "停用后列表失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(0L, body!!.getJsonObject("meta").getLong("total"), "停用规则不按启用过滤返回")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未知字段与非法参数_400`(vertx: Vertx, ctx: VertxTestContext) {
        val base = JsonObject()
            .put("material_id", MAT_A)
            .put("fixed_dose", 100)
            .put("unit", "mg")

        post(vertx, RULES, JsonObject(base.encode()).put("created_by", "伪造操作人")).compose { (s1, unknown) ->
            ctx.verify {
                assertEquals(400, s1, "未知字段必须 400，实际: $unknown")
                assertTrue(unknown?.getString("error")?.contains("unknown fields") == true)
            }
            post(vertx, RULES, JsonObject(base.encode()).put("unit", ""))
        }.compose { (s2, noUnit) ->
            ctx.verify { assertEquals(400, s2, "缺 unit 必须 400，实际: $noUnit") }
            post(
                vertx,
                RULES,
                JsonObject().put("material_id", MAT_A).put("unit", "mg"),
            )
        }.compose { (s3, noDose) ->
            ctx.verify {
                assertEquals(400, s3, "既无 dose_per_kg 也无 fixed_dose 必须 400，实际: $noDose")
            }
            post(vertx, RULES, JsonObject(base.encode()).put("status", "BOGUS"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "非法 status 必须 400，实际: $body")
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM pharmacy.pharmacy_pediatric_dose_rules WHERE material_id = '$MAT_A'"),
                    "非法请求不得落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) material_id 存在性（弱关联，服务层校验）
    // ========================================================================

    @Test
    fun `material_id不存在_404且零落库`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            RULES,
            JsonObject().put("material_id", MAT_MISSING).put("fixed_dose", 100).put("unit", "mg"),
        ).compose { (s1, missing) ->
            ctx.verify {
                assertEquals(404, s1, "material_id 不存在必须 404，实际: $missing")
                assertTrue(
                    missing?.getString("error")?.contains("material not found") == true,
                    "文案必须指明药品不存在，实际: ${missing?.getString("error")}",
                )
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM pharmacy.pharmacy_pediatric_dose_rules WHERE material_id = '$MAT_MISSING'"),
                    "不存在药品不得落库",
                )
            }
            // 先建一条合法规则，再把 material_id 改成不存在的 → 404 且原值不变
            post(vertx, RULES, JsonObject().put("material_id", MAT_A).put("fixed_dose", 100).put("unit", "mg"))
        }.compose { (s2, created) ->
            ctx.verify { assertEquals(201, s2, "合法规则创建必须 201，实际: $created") }
            val ruleId = created!!.getString("id")
            put(vertx, "$RULES/$ruleId", JsonObject().put("material_id", MAT_MISSING)).map { Triple(ruleId, it.first, it.second) }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "更新请求失败: ${ar.cause()?.message}")
                val (ruleId, status, body) = ar.result()
                assertEquals(404, status, "改成不存在的药品必须 404，实际: $body")
                assertEquals(
                    MAT_A,
                    queryString("SELECT material_id FROM pharmacy.pharmacy_pediatric_dose_rules WHERE id = '$ruleId'"),
                    "404 时不得改写 material_id",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 匹配：具体度优先 + 闭区间边界 + 回落 + 封顶
    // ========================================================================

    @Test
    fun `匹配_具体度优先与闭区间边界与回落`(vertx: Vertx, ctx: VertxTestContext) {
        // 同一药品四条规则，具体度 3/2/1/0
        createRule(vertx, ctx, MAT_A, JsonObject().put("fixed_dose", 10).put("unit", "mg"))
            .compose {
                createRule(vertx, ctx, MAT_A, JsonObject().put("age_min_months", 6).put("age_max_months", 12).put("fixed_dose", 20).put("unit", "mg"))
            }
            .compose {
                createRule(vertx, ctx, MAT_A, JsonObject().put("weight_min_kg", 5).put("weight_max_kg", 10).put("fixed_dose", 30).put("unit", "mg"))
            }
            .compose {
                createRule(vertx, ctx, MAT_A, JsonObject()
                    .put("age_min_months", 6).put("age_max_months", 12)
                    .put("weight_min_kg", 5).put("weight_max_kg", 10)
                    .put("fixed_dose", 40).put("unit", "mg"))
            }
            .compose { calc(vertx, MAT_A, "5", "6") }   // 下界含端点
            .compose { (s1, r1) ->
                ctx.verify {
                    assertEquals(200, s1, "计算必须 200，实际: $r1")
                    assertEquals(40.0, r1!!.getDouble("single_dose") ?: -1.0, 0.0001, "双区间最具体，优先命中")
                    assertEquals("WEIGHT_AND_AGE_BAND", r1.getString("source"))
                }
                calc(vertx, MAT_A, "10", "12")          // 上界含端点
            }
            .compose { (s2, r2) ->
                ctx.verify {
                    assertEquals(200, s2, "实际: $r2")
                    assertEquals(40.0, r2!!.getDouble("single_dose") ?: -1.0, 0.0001, "上界闭区间含端点")
                    assertEquals("WEIGHT_AND_AGE_BAND", r2.getString("source"))
                }
                calc(vertx, MAT_A, "11", "6")           // 体重出界 → 年龄单区间
            }
            .compose { (s3, r3) ->
                ctx.verify {
                    assertEquals(200, s3, "实际: $r3")
                    assertEquals(20.0, r3!!.getDouble("single_dose") ?: -1.0, 0.0001, "体重出界 → 年龄区间")
                    assertEquals("AGE_BAND", r3.getString("source"))
                }
                calc(vertx, MAT_A, "5", "13")           // 年龄出界 → 体重单区间
            }
            .compose { (s4, r4) ->
                ctx.verify {
                    assertEquals(200, s4, "实际: $r4")
                    assertEquals(30.0, r4!!.getDouble("single_dose") ?: -1.0, 0.0001, "年龄出界 → 体重区间")
                    assertEquals("WEIGHT_BAND", r4.getString("source"))
                }
                calc(vertx, MAT_A, "11", "13")          // 全不命中 → 回落通用
            }
            .compose { (s5, r5) ->
                ctx.verify {
                    assertEquals(200, s5, "实际: $r5")
                    assertEquals(10.0, r5!!.getDouble("single_dose") ?: -1.0, 0.0001, "有区间规则全不命中 → 回落通用")
                    assertEquals("GENERIC_FALLBACK", r5.getString("source"))
                    assertEquals(false, r5.getBoolean("capped"))
                }
                calc(vertx, MAT_A, null, "7")           // 缺体重：体重规则不命中，年龄规则命中
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded(), "匹配链路失败: ${ar.cause()?.message}")
                    val (status, r6) = ar.result()
                    assertEquals(200, status, "实际: $r6")
                    assertEquals(20.0, r6!!.getDouble("single_dose") ?: -1.0, 0.0001, "缺体重时体重区间不命中")
                    assertEquals("AGE_BAND", r6.getString("source"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `匹配_体重折算与上限封顶向下取整摊回`(vertx: Vertx, ctx: VertxTestContext) {
        createRule(
            vertx,
            ctx,
            MAT_B,
            JsonObject()
                .put("weight_min_kg", 1)
                .put("weight_max_kg", 100)
                .put("dose_per_kg", 0.5)
                .put("single_dose_max", 3)
                .put("daily_dose_max", 5)
                .put("doses_per_day", 2)
                .put("unit", "mg"),
        ).compose { calc(vertx, MAT_B, "20", "24") }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded(), "计算失败: ${ar.cause()?.message}")
                    val (status, r) = ar.result()
                    assertEquals(200, status, "实际: $r")
                    // 原始单次 = 0.5 × 20 = 10 → 单次上限 3 封顶 → 日 6 > 日上限 5
                    // → 单次 = floor(5/2, 3dp) = 2.5，日 = 5（自洽且不超上限）
                    assertEquals(2.5, r!!.getDouble("single_dose") ?: -1.0, 0.0001, "日上限封顶后单次向下取整摊回")
                    assertEquals(5.0, r.getDouble("daily_dose") ?: -1.0, 0.0001, "单次 × 频次 = 日总量 = 日上限")
                    assertEquals(true, r.getBoolean("capped"), "必须标记 capped 供药师复核")
                    assertEquals("mg", r.getString("unit"))
                    assertEquals("WEIGHT_BAND", r.getString("source"))
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  5) 046 向后兼容 + 空列表形状
    // ========================================================================

    @Test
    fun `046兼容_POST_dispenses空体仍400非404_500`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, DISPENSES, JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(
                    400,
                    status,
                    "046 冻结路由 POST /dispenses 空体必须仍是既有 400（非 404 路由丢失、非 500），实际: $body",
                )
                assertNotNull(body?.getString("error"), "错误体必须是 {error}")
                assertTrue(
                    body!!.getString("error").contains("dispense_no is required"),
                    "必须是既有校验文案，实际: ${body.getString("error")}",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `空列表形状与无规则计算404`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "$RULES?material_id=${enc(MAT_MISSING)}").compose { (s1, empty) ->
            ctx.verify {
                assertEquals(200, s1, "空列表必须 200，实际: $empty")
                assertEquals(0, empty!!.getJsonArray("records").size())
                assertEquals(0L, empty.getJsonObject("meta").getLong("total"))
            }
            calc(vertx, MAT_MISSING, "10", "12")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "计算请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(404, status, "无启用规则必须 404（不返回 0 剂量），实际: $body")
                assertNotNull(body?.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 创建一条规则并断言 201；返回创建结果以便链式调用。 */
    private fun createRule(
        vertx: Vertx,
        ctx: VertxTestContext,
        materialId: String,
        extra: JsonObject,
    ): Future<JsonObject> {
        val body = JsonObject(extra.encode()).put("material_id", materialId)
        return post(vertx, RULES, body).compose { (status, created) ->
            ctx.verify { assertEquals(201, status, "规则创建必须 201，实际: $created") }
            Future.succeededFuture(created!!)
        }
    }

    private fun calc(
        vertx: Vertx,
        materialId: String,
        weightKg: String?,
        ageMonths: String?,
    ): Future<Pair<Int, JsonObject?>> {
        val query = buildString {
            append("$CALC?material_id=${enc(materialId)}")
            weightKg?.let { append("&weight_kg=${enc(it)}") }
            ageMonths?.let { append("&age_months=${enc(it)}") }
        }
        return get(vertx, query)
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

    private fun put(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.PUT, path, body)

    private fun resetTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    /**
     * pharmacy 测试 classpath 不含 inventories 迁移，故自带最小 `public.materials`
     * （只含服务层读取的 `id`/`name`）作为弱关联对端；`IF NOT EXISTS` 兼容已有真表。
     */
    private fun createMinimalMaterialsSchema() {
        exec("CREATE SCHEMA IF NOT EXISTS public")
        exec(
            "CREATE TABLE IF NOT EXISTS public.materials (" +
                "id VARCHAR(32) PRIMARY KEY, name VARCHAR NOT NULL DEFAULT '')",
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
        exec("DELETE FROM pharmacy.pharmacy_pediatric_dose_rules WHERE material_id LIKE '$PREFIX%'")
        exec("DELETE FROM public.materials WHERE id LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "pharmacy.pharmacy_pediatric_dose_rules" to
                "SELECT count(*) FROM pharmacy.pharmacy_pediatric_dose_rules WHERE material_id LIKE '$PREFIX%'",
            "public.materials" to
                "SELECT count(*) FROM public.materials WHERE id LIKE '$PREFIX%'",
        )
        val leftovers = residuals.mapNotNull { (table, sql) ->
            val count = queryLong(sql)
            if (count == 0L) null else "$table=$count"
        }
        assertTrue(leftovers.isEmpty(), "fixture 残差必须为 0，实际残留: $leftovers")
    }

    // ========================================================================
    //  端口桩（D 链用例不会触达库存/医嘱端口；空体 400 在端口之前返回）
    // ========================================================================

    private val stubMedicalOrderReader = object : MedicalOrderReader {
        override fun listMedicationOrders(
            client: SqlClient,
            encounterId: String?,
            search: String?,
            limit: Int,
            offset: Int,
        ): Future<JsonObject> = Future.failedFuture(UnsupportedOperationException("stub: listMedicationOrders"))

        override fun lockMedicationOrder(client: SqlClient, medicalOrderId: String): Future<MedicationOrderSnapshot> =
            Future.failedFuture(UnsupportedOperationException("stub: lockMedicationOrder"))
    }

    private val stubOutbound = object : InventoryOutboundPort {
        override fun validateOutbound(client: SqlClient, command: OutboundCommand): Future<Void?> =
            Future.failedFuture(UnsupportedOperationException("stub: validateOutbound"))

        override fun confirmOutbound(client: SqlClient, command: OutboundCommand): Future<OutboundResult> =
            Future.failedFuture(UnsupportedOperationException("stub: confirmOutbound"))
    }

    private val stubInbound = object : InventoryInboundPort {
        override fun confirmInbound(client: SqlClient, command: InboundCommand): Future<InboundResult> =
            Future.failedFuture(UnsupportedOperationException("stub: confirmInbound"))
    }

    private val stubRequisition = object : InventoryRequisitionTransferPort {
        override fun validateRequisitionMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> =
            Future.failedFuture(UnsupportedOperationException("stub: validateRequisitionMaterials"))

        override fun reserveStock(client: SqlClient, command: RequisitionReserveCommand): Future<Void?> =
            Future.failedFuture(UnsupportedOperationException("stub: reserveStock"))

        override fun releaseReservation(client: SqlClient, command: RequisitionReleaseCommand): Future<Void?> =
            Future.failedFuture(UnsupportedOperationException("stub: releaseReservation"))

        override fun confirmReservedTransfer(
            client: SqlClient,
            command: RequisitionTransferCommand,
        ): Future<RequisitionTransferResult> =
            Future.failedFuture(UnsupportedOperationException("stub: confirmReservedTransfer"))
    }

    private val stubReceipt = object : InventoryPurchaseReceiptPort {
        override fun validatePurchaseMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> =
            Future.failedFuture(UnsupportedOperationException("stub: validatePurchaseMaterials"))

        override fun confirmPurchaseReceipt(
            client: SqlClient,
            command: PurchaseReceiptCommand,
        ): Future<PurchaseReceiptResult> =
            Future.failedFuture(UnsupportedOperationException("stub: confirmPurchaseReceipt"))
    }
}
