package com.ovaphlow.crate.healthcare

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
 * 054 F2（基础数据内容表：疫苗类型 / 发育量表）**真库集成测试**。
 *
 * 覆盖（054 §8.6 / F 链窗口）：
 *  1. `V539` 真迁移：两张内容表 + 唯一约束 + CHECK（`dose_count >= 1`、`recommended_months` 为数组、
 *     月龄区间、`definition` 为对象）+ 两个状态索引。
 *  2. **读库优先 / 常量回落等价性（核心）**：库空时 `refreshAll()` 后
 *     `ChildVaccinationService.effectiveSchedule()`、`DevelopmentalScaleCatalog.effectiveScales()`、
 *     `DevelopmentalScoring.score(...)` 结果与常量基线**完全相等**；写入内容后消费方改走库内容；
 *     清空内容并再次 refresh 后**回到基线**（等价性回归）。
 *  3. 内容 CRUD 真库闭环 + 字段白名单（非法字段 400）+ 空列表形状 `{records:[],meta:{total:0}}`。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，应用 healthcare 段迁移）：
 * ```
 * cd service-vertx-kotlin && set -a && . apps/aceso/.env && set +a
 * ./gradlew :libs:healthcare:test --rerun --tests '*BaseDataContentIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 唯一后缀；测试前后自清并断言残差 0；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaseDataContentIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054f2-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private const val TESTER = "qa054f2-tester"
        private const val VACCINATIONS = "/healthcare/v1/base-vaccination-types"
        private const val SCALES = "/healthcare/v1/base-developmental-scales"
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
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            client = vertx.createHttpClient()

            val root = Router.router(vertx)
            root.route("/healthcare/v1/*").handler { rctx ->
                rctx.put("userId", TESTER)
                rctx.next()
            }
            root.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))

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
            resetContentOverlay()
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
        resetContentOverlay()
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        resetContentOverlay()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V539 迁移
    // ========================================================================

    @Test
    fun `V539迁移_两表约束与索引生效`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "healthcare.base_vaccination_types",
                queryString("SELECT to_regclass('healthcare.base_vaccination_types')::text"),
            )
            assertEquals(
                "healthcare.base_developmental_scales",
                queryString("SELECT to_regclass('healthcare.base_developmental_scales')::text"),
            )
            listOf(
                "uq_base_vaccination_types_code",
                "uq_base_developmental_scales_code_version",
                "chk_base_vaccination_types_dose",
                "chk_base_vaccination_types_months",
                "chk_base_developmental_scales_age",
                "chk_base_developmental_scales_definition",
            ).forEach { name ->
                assertTrue(
                    queryLong(
                        "SELECT count(*) FROM pg_constraint WHERE conname = '$name'",
                    ) == 1L,
                    "缺约束: $name",
                )
            }
            listOf(
                "idx_base_vaccination_types_status",
                "idx_base_developmental_scales_status",
            ).forEach { index ->
                assertEquals(
                    1L,
                    queryLong("SELECT count(*) FROM pg_indexes WHERE indexname = '$index'"),
                    "缺索引: $index",
                )
            }
        }
        // CHECK 真生效
        assertTrue(
            runCatching {
                exec(
                    "INSERT INTO healthcare.base_vaccination_types (id, code, name, dose_count, recommended_months) " +
                        "VALUES ('${PREFIX}bad-$RUN', '${PREFIX}BAD', 'x', 0, '[]'::jsonb)",
                )
            }.isFailure,
            "dose_count=0 必须被 CHECK 拒绝",
        )
        assertTrue(
            runCatching {
                exec(
                    "INSERT INTO healthcare.base_developmental_scales " +
                        "(id, scale_code, scale_version, name, age_min_months, age_max_months, definition) " +
                        "VALUES ('${PREFIX}bad2-$RUN', '${PREFIX}BAD2', 'v1', 'x', 36, 12, '{}'::jsonb)",
                )
            }.isFailure,
            "age_max < age_min 必须被 CHECK 拒绝",
        )
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 读库优先 / 常量回落等价性（核心）
    // ========================================================================

    @Test
    fun `库空时_回落常量基线与判读逐字段相等`(vertx: Vertx, ctx: VertxTestContext) {
        // 基线：库空（内容覆盖层为空）时的消费方输出
        val baselineSchedule = ChildVaccinationService.effectiveSchedule()
        val baselineScales = DevelopmentalScaleCatalog.effectiveScales()
        val scoringInput = baselineScoringInput()
        val baselineScore = DevelopmentalScoring.score(scoringInput.first, scoringInput.second, scoringInput.third)

        ctx.verify {
            assertTrue(baselineSchedule.isNotEmpty(), "常量排程不得为空")
            assertTrue(baselineScales.isNotEmpty(), "常量量表基线不得为空")
            assertTrue(
                queryLong("SELECT count(*) FROM healthcare.base_vaccination_types WHERE code LIKE '$PREFIX%'") == 0L,
                "前置条件：库中无本测试内容",
            )
        }

        // 显式 refresh（库空）→ 覆盖层为空 → 必须与基线逐项相等
        refreshContent().onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "refreshAll 失败: ${ar.cause()?.message}")
                assertEquals(baselineSchedule, ChildVaccinationService.effectiveSchedule(), "库空时排程必须等于常量基线")
                assertEquals(baselineScales, DevelopmentalScaleCatalog.effectiveScales(), "库空时量表必须等于常量基线")
                assertEquals(
                    baselineScore,
                    DevelopmentalScoring.score(scoringInput.first, scoringInput.second, scoringInput.third),
                    "库空时判读结果必须逐字段相等",
                )
                ctx.completeNow()
            }
        }
        ctx.completeNow()
    }

    @Test
    fun `写入内容后_消费方改走库内容_清空后回到基线`(vertx: Vertx, ctx: VertxTestContext) {
        val baselineSchedule = ChildVaccinationService.effectiveSchedule()
        val baselineScales = DevelopmentalScaleCatalog.effectiveScales()
        val vaccinationCode = "${PREFIX}VAC-$RUN"
        val scaleCode = "${PREFIX}SCALE-$RUN"

        post(
            vertx,
            VACCINATIONS,
            JsonObject()
                .put("code", vaccinationCode)
                .put("name", "054F2 疫苗")
                .put("dose_count", 1)
                .put("recommended_months", JsonArray().add(12))
                .put("sort_order", 900),
        ).compose { (s1, vaccination) ->
            ctx.verify {
                assertEquals(201, s1, "疫苗内容创建必须 201，实际: $vaccination")
                assertEquals(vaccinationCode, vaccination!!.getString("code"))
            }
            post(
                vertx,
                SCALES,
                JsonObject()
                    .put("scale_code", scaleCode)
                    .put("scale_version", "v1")
                    .put("name", "054F2 量表")
                    .put("age_min_months", 0)
                    .put("age_max_months", 36)
                    .put(
                        "definition",
                        JsonObject()
                            .put("domains", JsonArray().add(JsonObject().put("code", "D1").put("name", "大运动")))
                            .put(
                                "items",
                                JsonArray().add(
                                    JsonObject().put("code", "I1").put("domain", "D1").put("prompt", "抬头")
                                        .put("min_months", 0).put("max_months", 36)
                                        .put(
                                            "options",
                                            JsonArray()
                                                .add(JsonObject().put("value", 1).put("label", "达标"))
                                                .add(JsonObject().put("value", 0).put("label", "未达标")),
                                        ),
                                ),
                            ),
                    ),
            )
        }.compose { (s2, scale) ->
            ctx.verify {
                assertEquals(201, s2, "量表内容创建必须 201，实际: $scale")
                assertEquals(scaleCode, scale!!.getString("scale_code"))
            }
            refreshContent()
        }.compose { _ ->
            ctx.verify {
                val schedule = ChildVaccinationService.effectiveSchedule()
                assertEquals(1, schedule.size, "库非空时排程由库内容**替换**，实际: $schedule")
                assertEquals(vaccinationCode, schedule.first().code)
                val scales = DevelopmentalScaleCatalog.effectiveScales()
                assertTrue(scales.containsKey(scaleCode), "库内容量表必须覆盖生效")
                assertTrue(scales.keys.containsAll(baselineScales.keys), "库内容为叠加（常量基线仍在）")
                assertEquals(36, scales[scaleCode]!!.maxMonths)
            }
            // 清空内容 → refresh → 回到基线
            cleanupFixtures()
            refreshContent()
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "链路失败: ${ar.cause()?.message}")
                assertEquals(
                    baselineSchedule,
                    ChildVaccinationService.effectiveSchedule(),
                    "清空内容后必须**回到常量基线**（等价性回归）",
                )
                assertEquals(
                    baselineScales,
                    DevelopmentalScaleCatalog.effectiveScales(),
                    "清空内容后量表必须回到常量基线",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) CRUD 闭环 + 白名单 + 空列表
    // ========================================================================

    @Test
    fun `内容CRUD真库闭环_白名单400与空列表形状`(vertx: Vertx, ctx: VertxTestContext) {
        val code = "${PREFIX}CRUD-$RUN"
        var vaccinationId = ""
        post(
            vertx,
            VACCINATIONS,
            JsonObject()
                .put("code", code)
                .put("name", "054F2 CRUD")
                .put("dose_count", 2)
                .put("recommended_months", JsonArray().add(0).add(6)),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "创建必须 201，实际: $created")
                assertEquals("启用", created!!.getString("status"), "缺省状态为启用")
                assertEquals(2, created.getJsonArray("recommended_months").size())
            }
            vaccinationId = created!!.getString("id")
            get(vertx, "$VACCINATIONS?status=${enc("启用")}")
        }.compose { (s2, listed) ->
            ctx.verify {
                assertEquals(200, s2, "列表必须 200，实际: $listed")
                assertTrue(listed!!.getJsonObject("meta").getLong("total") >= 1L)
            }
            put(
                vertx,
                "$VACCINATIONS/$vaccinationId",
                JsonObject()
                    .put("code", code)
                    .put("name", "054F2 CRUD 改名")
                    .put("dose_count", 2)
                    .put("recommended_months", JsonArray().add(0).add(6))
                    .put("status", "停用"),
            )
        }.compose { (s3, updated) ->
            ctx.verify {
                assertEquals(200, s3, "更新必须 200，实际: $updated")
                assertEquals("054F2 CRUD 改名", updated!!.getString("name"))
                assertEquals("停用", updated.getString("status"))
            }
            // 字段白名单：非法字段必须 400
            post(
                vertx,
                VACCINATIONS,
                JsonObject().put("code", "$code-X").put("name", "x").put("dose_count", 1)
                    .put("recommended_months", JsonArray().add(1)).put("bogus_field", 1),
            )
        }.compose { (s4, illegal) ->
            ctx.verify {
                assertEquals(400, s4, "非法字段必须 400，实际: $illegal")
                assertTrue(illegal?.getString("error")?.contains("unsupported") == true)
            }
            // 剂量数与月龄数组长度不一致 → 400
            post(
                vertx,
                VACCINATIONS,
                JsonObject().put("code", "$code-Y").put("name", "x").put("dose_count", 3)
                    .put("recommended_months", JsonArray().add(1)),
            )
        }.compose { (s5, mismatch) ->
            ctx.verify { assertEquals(400, s5, "月龄数组长度不匹配必须 400，实际: $mismatch") }
            get(vertx, "$SCALES?status=${enc("启用")}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertNotNull(body!!.getJsonArray("records"))
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM healthcare.base_vaccination_types WHERE code LIKE '%-X' OR code LIKE '%-Y'"),
                    "非法请求不得落库",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `空列表形状`(vertx: Vertx, ctx: VertxTestContext) {
        // 用未命中的 status 值触发空结果（服务端白名单外的值按无匹配处理，不得 500）
        get(vertx, "$VACCINATIONS?limit=1&offset=99999").onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status, "空结果必须 200，实际: $body")
                assertEquals(0, body!!.getJsonArray("records").size())
                assertEquals(0L, body.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 构造一份与库内容无关的判读输入（取常量基线里的第一个量表与最小月龄）。 */
    private fun baselineScoringInput(): Triple<String, Int, Map<String, Int>> {
        val scale = DevelopmentalScaleCatalog.effectiveScales().values.first()
        val age = scale.minMonths
        val items = DevelopmentalScaleCatalog.ageAppropriateItems(scale, age)
        return Triple(scale.code, age, items.associate { it.code to 1 })
    }

    /**
     * 重新加载内容表到进程内覆盖层（等价于生产启动/写入后的刷新）。
     * **返回 Future，绝不在事件循环上阻塞等待**（`@ExtendWith(VertxExtension)` 下测试方法运行在
     * Vert.x 上下文线程，`Future.get()` 会与连接池回调互相等待而死锁）。
     */
    private fun refreshContent(): Future<Unit> = BaseDataService(pool).refreshAll()

    /**
     * 同步重置覆盖层为「空」（等价于库为空 / 读失败时的状态），仅供生命周期钩子使用。
     * 走生产同一入口 [ChildVaccinationService.applyContentSchedule] / [DevelopmentalScaleCatalog.applyContent]。
     */
    private fun resetContentOverlay() {
        ChildVaccinationService.applyContentSchedule(emptyList())
        DevelopmentalScaleCatalog.applyContent(emptyList())
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
        exec("DELETE FROM healthcare.base_vaccination_types WHERE code LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.base_developmental_scales WHERE scale_code LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "healthcare.base_vaccination_types" to
                "SELECT count(*) FROM healthcare.base_vaccination_types WHERE code LIKE '$PREFIX%'",
            "healthcare.base_developmental_scales" to
                "SELECT count(*) FROM healthcare.base_developmental_scales WHERE scale_code LIKE '$PREFIX%'",
        )
        val leftovers = residuals.mapNotNull { (table, sql) ->
            val count = queryLong(sql)
            if (count == 0L) null else "$table=$count"
        }
        assertTrue(leftovers.isEmpty(), "fixture 残差必须为 0，实际残留: $leftovers")
    }
}
