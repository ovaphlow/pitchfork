package com.ovaphlow.crate.dining

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
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
 * 054 A4b（儿保过敏原可达）**真库集成测试**——唯一事实源为隔离 `aceso_test`。
 *
 * 为什么必须真库（DB-free mock 桩测不到的点）：
 *  1. `V603` 的 `DROP NOT NULL` 是真 DDL；只有真库能证明儿童档案 `encounter_id IS NULL` 可落库。
 *  2. `get()/list()` 若对 `healthcare.encounters` 用 **INNER JOIN**，`encounter_id IS NULL`
 *     的儿童档案会被丢弃 → `PUT /diet-profiles/by-patient/:patient_id/allergens` 内部回读 404、
 *     `GET /diet-profiles?patient_id=` 列表不可见。本类**显式护栏**这两条（Lead 追加要求）。
 *  3. `ON CONFLICT (patient_id) WHERE status='启用'` 与部分唯一索引的推断谓词必须真库匹配，
 *     且 JSONB 必须绑 Vert.x `JsonArray`/`JsonObject`（绑 `org.jooq.JSONB` 时 pgclient 无编解码器 → 真库失败）。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 dining 段迁移 V600–V603）：
 * ```
 * cd service-vertx-kotlin && set -a && . apps/aceso/.env && set +a
 * ./gradlew :libs:dining:test --rerun --tests '*ChildDietAllergenByPatientIntegrationTest' \
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
class ChildDietAllergenByPatientIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18432
        private const val PREFIX = "qa054a4b-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD = "${PREFIX}child-$RUN"
        private val CHILD_2 = "${PREFIX}child2-$RUN"
        private val ELDER = "${PREFIX}elder-$RUN"
        private val ELDER_NO_ENC = "${PREFIX}elder-ne-$RUN"
        private val ENCOUNTER = "${PREFIX}enc-$RUN"

        private const val TESTER = "qa054a4b-tester"

        /** 本类专用的配餐日期，便于按日期精确回收名单容器。 */
        private const val ROSTER_DATE = "2026-08-14"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var client: HttpClient
    private var server: io.vertx.core.http.HttpServer? = null

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

            val authHandler = io.vertx.core.Handler<RoutingContext> { rctx ->
                rctx.put("userId", TESTER)
                rctx.next()
            }
            val rootRouter = Router.router(vertx)
            rootRouter.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, authHandler))

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
        // 儿童：person_type='儿童'，**无任何入住周期**（A4b 核心场景）
        insertPatient(CHILD, "054A4b儿童甲", "儿童")
        insertPatient(CHILD_2, "054A4b儿童乙", "儿童")
        // 长者：一个有 ACTIVE ELDERLY_CARE 入住（回归路径），一个无入住（400 文案路径）
        insertPatient(ELDER, "054A4b长者甲", "长者")
        insertPatient(ELDER_NO_ENC, "054A4b长者乙", "长者")
        exec(
            "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) " +
                "VALUES ('$ENCOUNTER', '$ELDER', 'ELDERLY_CARE', 'ACTIVE', now())",
        )
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V603 真迁移
    // ========================================================================

    @Test
    fun `V603迁移_encounter_id可空且部分唯一索引仍在`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "YES",
                queryString(
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'dining' " +
                        "AND table_name = 'dining_diet_profiles' AND column_name = 'encounter_id'",
                ),
                "V603 的 DROP NOT NULL 必须真迁移生效（儿童无入住可建档）",
            )
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'dining' " +
                        "AND tablename = 'dining_diet_profiles' " +
                        "AND indexname = 'uq_dining_diet_profiles_active_patient'",
                ),
                "部分唯一索引必须保留（同一对象仍只允许一份启用档案）",
            )
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'dining' " +
                        "AND table_name LIKE '%allergen%'",
                ),
                "不得新建过敏主数据表（Lead 裁定 L2）",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) A4b 核心：儿童无入住 → 按 patient upsert 档案 + 覆盖式写过敏原
    // ========================================================================

    @Test
    fun `儿童无入住_按patient覆盖写过敏原并真库落库`(vertx: Vertx, ctx: VertxTestContext) {
        put(
            vertx,
            "/dining/v1/diet-profiles/by-patient/${enc(CHILD)}/allergens",
            JsonObject().put("allergies", JsonArray().add("花生").add("牛奶")),
        ).compose { (s1, first) ->
            ctx.verify {
                assertEquals(200, s1, "儿童无入住过敏原写入必须 200，实际: $first")
                assertNotNull(first)
                assertNull(first!!.getString("encounter_id"), "儿童档案 encounter_id 必须为 null")
                assertEquals("普食", first.getString("meal_type"), "最小档案缺省 meal_type=普食")
                assertEquals("启用", first.getString("status"))
                assertEquals(
                    listOf("花生", "牛奶"),
                    first.getJsonArray("allergies")?.map { it.toString() },
                    "allergies 必须可写可读（真库 JSONB 编解码）",
                )
            }
            val profileId = first!!.getString("id")

            // 覆盖式：第二次写入整体替换，不追加、不新增档案
            put(
                vertx,
                "/dining/v1/diet-profiles/by-patient/${enc(CHILD)}/allergens",
                JsonObject().put("allergies", JsonArray().add("鸡蛋")),
            ).compose { (s2, second) ->
                ctx.verify {
                    assertEquals(200, s2, "覆盖写必须 200，实际: $second")
                    assertEquals(profileId, second!!.getString("id"), "同儿童必须命中同一条（upsert）")
                    assertEquals(
                        listOf("鸡蛋"),
                        second.getJsonArray("allergies")?.map { it.toString() },
                        "覆盖式必须整体替换，不是追加",
                    )
                }
                // 真库落库校验（JSONB 必须真写进去，而不是仅回显）
                ctx.verify {
                    assertEquals(
                        1L,
                        queryLong(
                            "SELECT count(*) FROM dining.dining_diet_profiles " +
                                "WHERE patient_id = '$CHILD' AND status = '启用'",
                        ),
                        "同儿童只允许一份启用档案",
                    )
                    assertEquals(
                        "[\"鸡蛋\"]",
                        queryString(
                            "SELECT allergies::text FROM dining.dining_diet_profiles WHERE patient_id = '$CHILD'",
                        ),
                        "过敏原必须落在既有列 dining_diet_profiles.allergies",
                    )
                    assertEquals(
                        1L,
                        queryLong(
                            "SELECT count(*) FROM dining.dining_diet_profiles " +
                                "WHERE patient_id = '$CHILD' AND encounter_id IS NULL",
                        ),
                        "儿童档案 encounter_id 必须真为 NULL（V603 生效）",
                    )
                }
                // Lead 追加护栏 2：列表必须能看到该儿童档案
                get(vertx, "/dining/v1/diet-profiles?patient_id=${enc(CHILD)}")
            }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "列表查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(
                    1L,
                    body!!.getJsonObject("meta").getLong("total"),
                    "encounter_id IS NULL 的儿童档案必须在列表可见（INNER JOIN 会丢弃 → 本护栏防回归）",
                )
                assertEquals(1, body.getJsonArray("records").size())
                val record = body.getJsonArray("records").getJsonObject(0)
                assertEquals(CHILD, record.getString("patient_id"))
                assertNull(record.getString("encounter_id"))
                assertEquals(listOf("鸡蛋"), record.getJsonArray("allergies")?.map { it.toString() })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童无入住_POST建档成功且encounter为空`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/dining/v1/diet-profiles",
            JsonObject().put("patient_id", CHILD_2).put("meal_type", "软食"),
        ).compose { (status, created) ->
            ctx.verify {
                assertEquals(201, status, "儿童无入住建档必须 201，实际: $created")
                assertNull(created!!.getString("encounter_id"))
                assertEquals("软食", created.getString("meal_type"))
                assertEquals("启用", created.getString("status"))
            }
            // 建档后按 id 读回（get() 也必须能取到 encounter_id IS NULL 的档案）
            get(vertx, "/dining/v1/diet-profiles/${created!!.getString("id")}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "读回失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status, "get() 必须能取到儿童档案（INNER JOIN 会 404 → 本护栏防回归）")
                assertEquals(CHILD_2, body!!.getString("patient_id"))
                assertNull(body.getString("encounter_id"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 非儿童无入住：既有 400 文案不变
    // ========================================================================

    @Test
    fun `非儿童无入住_建档400_文案encounter_id_is_required`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/dining/v1/diet-profiles",
            JsonObject().put("patient_id", ELDER_NO_ENC).put("meal_type", "普食"),
        ).compose { (status, body) ->
            ctx.verify {
                assertEquals(400, status, "非儿童无入住必须 400，实际: $body")
                assertTrue(
                    body?.getString("error")?.contains("encounter_id is required") == true,
                    "错误文案必须保持 encounter_id is required，实际: ${body?.getString("error")}",
                )
            }
            // 按 patient upsert：非儿童且无既有启用档案 → 400（不新建档案）
            put(
                vertx,
                "/dining/v1/diet-profiles/by-patient/${enc(ELDER_NO_ENC)}/allergens",
                JsonObject().put("allergies", JsonArray().add("花生")),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "非儿童无档案不得静默建档，实际: $body")
                assertNotNull(body?.getString("error"))
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM dining.dining_diet_profiles WHERE patient_id = '$ELDER_NO_ENC'"),
                    "非儿童无入住不得产生档案",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 长者（有入住）路径回归
    // ========================================================================

    @Test
    fun `长者有入住_建档与过敏原路径行为不变`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/dining/v1/diet-profiles",
            JsonObject()
                .put("patient_id", ELDER)
                .put("encounter_id", ENCOUNTER)
                .put("meal_type", "普食")
                .put("allergies", JsonArray().add("花生")),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "长者建档必须 201，实际: $created")
                assertEquals(ENCOUNTER, created!!.getString("encounter_id"))
                assertEquals("ACTIVE", created.getString("encounter_status"), "长者档案带出入住状态")
            }
            val profileId = created!!.getString("id")
            put(
                vertx,
                "/dining/v1/diet-profiles/$profileId/allergens",
                JsonObject().put("allergies", JsonArray().add("牛奶")),
            ).compose { (s2, updated) ->
                ctx.verify {
                    assertEquals(200, s2, "既有按 id 覆盖写必须仍 200，实际: $updated")
                    assertEquals(listOf("牛奶"), updated!!.getJsonArray("allergies")?.map { it.toString() })
                    assertEquals(ENCOUNTER, updated.getString("encounter_id"), "长者档案 encounter_id 不变")
                }
                get(vertx, "/dining/v1/diet-profiles?patient_id=${enc(ELDER)}")
            }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "列表查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"))
                assertEquals(ENCOUNTER, body.getJsonArray("records").getJsonObject(0).getString("encounter_id"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) 配餐不受影响：encounter_id IS NULL 的儿童档案不进养老配餐
    // ========================================================================

    @Test
    fun `配餐不受影响_儿童档案不进养老配餐名单`(vertx: Vertx, ctx: VertxTestContext) {
        // 儿童档案（无入住）
        put(
            vertx,
            "/dining/v1/diet-profiles/by-patient/${enc(CHILD)}/allergens",
            JsonObject().put("allergies", JsonArray().add("花生")),
        ).compose { (s1, childProfile) ->
            ctx.verify {
                assertEquals(200, s1, "儿童档案写入必须 200，实际: $childProfile")
            }
            // 长者档案（有入住）——用于对照：长者应进名单，儿童不应进
            post(
                vertx,
                "/dining/v1/diet-profiles",
                JsonObject().put("patient_id", ELDER).put("encounter_id", ENCOUNTER).put("meal_type", "普食"),
            )
        }.compose { (s2, elderProfile) ->
            ctx.verify { assertEquals(201, s2, "长者建档必须 201，实际: $elderProfile") }
            post(
                vertx,
                "/dining/v1/rosters/generate",
                JsonObject().put("date", ROSTER_DATE).put("meal_time", "午餐"),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "名单生成失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status, "名单生成必须 200，实际: $body")
                assertEquals(1, body!!.getInteger("created"), "只有有 ACTIVE 入住的长者进名单")
                val items = body.getJsonObject("roster").getJsonArray("items")
                assertEquals(1, items.size(), "儿童档案（encounter_id IS NULL）不得进养老配餐")
                assertEquals(ELDER, items.getJsonObject(0).getString("patient_id"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  HTTP / JDBC 辅助
    // ========================================================================

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject?>> =
        client.request(method, TEST_PORT, "127.0.0.1", path).compose { req ->
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

    private fun put(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.PUT, path, body)

    private fun insertPatient(id: String, name: String, personType: String) {
        exec(
            "INSERT INTO healthcare.patients (id, name, gender, birth_date, person_type, status) " +
                "VALUES ('$id', '$name', '女', DATE '2021-03-01', '$personType', 'ACTIVE')",
        )
    }

    /** 自包含：重置隔离库并只应用 dining 段迁移（V600–V603）。 */
    private fun resetTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    /**
     * V600–V603 含 `healthcare.patients/encounters` 跨 schema 外键，迁移前先建最小表；
     * `person_type`（V525 口径）与 `birth_date` 为 054 服务层所需列。
     */
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
        exec(
            "CREATE TABLE IF NOT EXISTS healthcare.encounters (" +
                "id VARCHAR(32) PRIMARY KEY, patient_id VARCHAR(32) NOT NULL, " +
                "encounter_type VARCHAR NOT NULL DEFAULT '', status VARCHAR(20) DEFAULT 'ACTIVE', " +
                "admit_date TIMESTAMPTZ)",
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

    /** 自带 dining 清理（`AcesoDbIntegrationTestBase.cleanupAll` 无 `dining.*` 语句）。 */
    private fun cleanupFixtures() {
        exec(
            "DELETE FROM dining.dining_meal_executions WHERE roster_item_id IN " +
                "(SELECT id FROM dining.dining_roster_items WHERE patient_id LIKE '$PREFIX%')",
        )
        exec("DELETE FROM dining.dining_roster_items WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM dining.dining_rosters WHERE menu_date = DATE '$ROSTER_DATE'")
        exec("DELETE FROM dining.dining_diet_profiles WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM dining.dining_dishes WHERE id LIKE '$PREFIX%' OR name LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.encounters WHERE id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    /** 残差必须为 0；否则说明 fixture 泄漏。 */
    private fun assertNoResidual() {
        val residuals = mapOf(
            "dining.dining_diet_profiles" to
                "SELECT count(*) FROM dining.dining_diet_profiles WHERE patient_id LIKE '$PREFIX%'",
            "dining.dining_roster_items" to
                "SELECT count(*) FROM dining.dining_roster_items WHERE patient_id LIKE '$PREFIX%'",
            "dining.dining_rosters" to
                "SELECT count(*) FROM dining.dining_rosters WHERE menu_date = DATE '$ROSTER_DATE'",
            "healthcare.encounters" to
                "SELECT count(*) FROM healthcare.encounters WHERE id LIKE '$PREFIX%'",
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
