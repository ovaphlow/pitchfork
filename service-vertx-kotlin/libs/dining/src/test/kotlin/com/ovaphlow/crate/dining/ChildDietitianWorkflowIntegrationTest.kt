package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
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
import org.junit.jupiter.api.Assertions.assertFalse
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
 * 054 A 链（营养师 A1–A5）**真库集成测试**——唯一事实源为隔离 `aceso_test`。
 *
 * 覆盖（054 计划 §8.1 / §8.10，task-13）：
 *  1. V601 真迁移：`dining.dining_meal_intake_records` 建表、唯一索引
 *     `uq_dining_meal_intake_records_meal` 生效、`dining_dishes.nutrition/allergens` 可写可读。
 *  2. 幂等 UPSERT：同 `(patient_id, intake_date, meal_time)` 重复提交覆盖不重复。
 *  3. A3 口径：头部总量取 A1 落库汇总（唯一权威），明细之和 == 头部总量，
 *     菜品无营养快照时明细计 0 且 `matched=false`。
 *  4. A4 状态机落库：`待审批 → 已批准/已驳回 → 已终止` 合法流转成功、非法流转 400；
 *     过敏原**覆盖写既有列** `dining_diet_profiles.allergies`，不新建过敏主数据表。
 *  5. A5 周报跨周聚合：同周重复生成覆盖、空数据周可判定、`daily` 明细与均值口径。
 *
 * 运行方式（**自包含**：类自身重置 `aceso_test` 并只应用 dining 段迁移 V600/V601/V602）：
 * ```
 * cd service-vertx-kotlin
 * PITCHFORK_DB_PASSWORD=<hidden> ./gradlew :libs:dining:test --rerun \
 *   --tests '*ChildDietitianWorkflowIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * 前置：55432 上的隔离测试容器已就绪（`scripts/aceso-test.sh` 或用户起的
 * `pitchfork-aceso-test-db`）；本类**只操作 aceso_test**，绝不连接 5432/业务库。
 *
 * fixture 口径：固定前缀 [PREFIX] + 每次运行唯一后缀 [RUN]；测试前后自清并断言残差 0
 * （`AcesoDbIntegrationTestBase.cleanupAll` 无 `dining.*` 语句，故本类自带 dining 清理）。
 * 认证为进程内假认证（`userId` 注入上下文），**不涉及任何 token**。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildDietitianWorkflowIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"

        /** 与既有 dining 集成测试（18426）区分，避免同 JVM 端口冲突。 */
        private const val TEST_PORT = 18431

        /** fixture 固定前缀：清理与残差断言都按它过滤。 */
        private const val PREFIX = "qa054a-"

        /** 每次运行唯一后缀，避免上一轮遗留数据影响本轮断言。 */
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val PATIENT = "${PREFIX}p-$RUN"
        private val ENCOUNTER = "${PREFIX}e-$RUN"
        private val DISH_ID = "${PREFIX}d-$RUN"
        private val DISH_NAME = "${PREFIX}营养粥-$RUN"
        private const val TESTER = "qa054a-tester"

        /** A1 汇总口径：116 kcal / 100 g。 */
        private const val ENERGY_PER_100G = 116.0

        private const val NUTRITION_JSON =
            """{"basis_g":100,"energy_kcal":116,"protein_g":2.6,"fat_g":0.3,"carb_g":25.9,"sodium_mg":2.5}"""

        /** 内置常量表里有、但菜品库无营养快照的食物名：用于证明 A3 不再回落内置常量。 */
        private const val UNMATCHED_BUILTIN_FOOD = "苹果"
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

        // 兜底门控：只给了 -Dintegration.db.host 而缺密码时 skip 整个类，不让模块变红。
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
            // V600/V601/V602：dining 段迁移；跨 schema 外键依赖上面先建的最小 healthcare 表。
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            client = vertx.createHttpClient()

            // 进程内假认证（与 DiningRoutesIntegrationTest 同范式）；不涉及 token。
            val authHandler = io.vertx.core.Handler<RoutingContext> { rctx ->
                rctx.put("userId", TESTER)
                rctx.next()
            }
            val rootRouter = Router.router(vertx)
            rootRouter.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, authHandler))
            rootRouter.route("/dining-open/v1/*").subRouter(DiningRoutes.create(vertx, pool, null))

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
        exec(
            "INSERT INTO healthcare.patients (id, name, gender, birth_date, person_type, status) " +
                "VALUES ('$PATIENT', '054A集成测试对象', '女', DATE '2021-03-01', '长者', 'ACTIVE')",
        )
        exec(
            "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) " +
                "VALUES ('$ENCOUNTER', '$PATIENT', 'ELDERLY_CARE', 'ACTIVE', now())",
        )
        exec(
            "INSERT INTO dining.dining_dishes " +
                "(id, name, category, meal_times, diet_tags, status, nutrition, allergens) VALUES " +
                "('$DISH_ID', '$DISH_NAME', '主食', '[\"早餐\",\"午餐\",\"晚餐\",\"加餐\"]'::jsonb, " +
                "'[]'::jsonb, '启用', '$NUTRITION_JSON'::jsonb, '[]'::jsonb)",
        )
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V601 真迁移
    // ========================================================================

    @Test
    fun `V601迁移_建表唯一索引与菜品营养列可写可读`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "dining.dining_meal_intake_records",
                queryString("SELECT to_regclass('dining.dining_meal_intake_records')::text"),
                "V601 必须真实建出摄入记录表",
            )
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'dining' " +
                        "AND tablename = 'dining_meal_intake_records' " +
                        "AND indexname = 'uq_dining_meal_intake_records_meal'",
                ),
                "V601 唯一索引 uq_dining_meal_intake_records_meal 必须存在",
            )
            assertEquals(
                2L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dining' " +
                        "AND table_name = 'dining_dishes' AND column_name IN ('nutrition','allergens')",
                ),
                "V601 必须给 dining_dishes 增加 nutrition/allergens 两列",
            )
        }

        val apiDishName = "${PREFIX}菜品-api-$RUN"
        post(
            vertx,
            "/dining/v1/dishes",
            JsonObject()
                .put("name", apiDishName)
                .put("category", "汤品")
                .put("meal_times", JsonArray().add("午餐"))
                .put("nutrition", JsonObject(NUTRITION_JSON))
                .put("allergens", JsonArray().add("花生").add("牛奶")),
        ).compose { (status, body) ->
            ctx.verify {
                assertEquals(201, status, "菜品创建必须 201，实际: $body")
                assertNotNull(body)
                assertEquals(apiDishName, body!!.getString("name"))
                assertEquals(100.0, body.getJsonObject("nutrition")?.getDouble("basis_g") ?: -1.0, 0.001)
                assertEquals(ENERGY_PER_100G, body.getJsonObject("nutrition")?.getDouble("energy_kcal") ?: -1.0, 0.001)
                assertEquals(
                    listOf("花生", "牛奶"),
                    body.getJsonArray("allergens")?.map { it.toString() },
                    "allergens 必须可写可读",
                )
            }
            val id = body!!.getString("id")
            // 再按 id 读回，证明落库而非仅回显
            get(vertx, "/dining/v1/dishes/$id")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "读回菜品失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(100.0, body!!.getJsonObject("nutrition")?.getDouble("basis_g") ?: -1.0, 0.001)
                assertEquals(
                    "116.0",
                    queryString(
                        "SELECT nutrition->>'energy_kcal' FROM dining.dining_dishes " +
                            "WHERE name = '$apiDishName'",
                    ),
                    "nutrition 必须真实落库（DishService 数值统一归一为 Double）",
                )
                assertEquals(
                    "[\"花生\", \"牛奶\"]",
                    queryString(
                        "SELECT allergens::text FROM dining.dining_dishes WHERE name = '$apiDishName'",
                    ),
                    "allergens 必须真实落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  2) 幂等 UPSERT
    // ========================================================================

    @Test
    fun `摄入登记_同儿童同餐次重复提交幂等覆盖`(vertx: Vertx, ctx: VertxTestContext) {
        val date = "2026-08-03"
        val first = JsonObject()
            .put("patient_id", PATIENT)
            .put("intake_date", date)
            .put("meal_time", "午餐")
            .put("items", JsonArray().add(item(DISH_NAME, 100.0)))
            .put("intake_ratio", 1.0)
            .put("operator", "客户端伪造的操作人必须被忽略")
            .put("energy_kcal", 9999.0)

        post(vertx, "/dining/v1/meal-intake-records", first).compose { (status1, body1) ->
            ctx.verify {
                assertEquals(201, status1, "首次登记必须 201，实际: $body1")
                assertEquals(ENERGY_PER_100G, body1!!.getDouble("energy_kcal") ?: -1.0, 0.001, "服务端按菜品营养汇总")
                assertEquals(TESTER, body1.getString("operator"), "operator 取认证主体，客户端提交被忽略")
            }
            val firstId = body1!!.getString("id")

            val second = JsonObject()
                .put("patient_id", PATIENT)
                .put("intake_date", date)
                .put("meal_time", "午餐")
                .put("items", JsonArray().add(item(DISH_NAME, 200.0)))
                .put("intake_ratio", 0.5)
                .put("remark", "第二次提交覆盖")

            post(vertx, "/dining/v1/meal-intake-records", second).compose { (status2, body2) ->
                ctx.verify {
                    assertEquals(201, status2, "重复登记仍 201（幂等覆盖），实际: $body2")
                    assertEquals(firstId, body2!!.getString("id"), "同 (儿童,日期,餐次) 必须命中同一行")
                    // 200 g × 116/100 × 0.5 = 116.00
                    assertEquals(ENERGY_PER_100G, body2.getDouble("energy_kcal") ?: -1.0, 0.001)
                    assertEquals(0.5, body2.getDouble("intake_ratio") ?: -1.0, 0.0001)
                    assertEquals("第二次提交覆盖", body2.getString("remark"))
                }
                // 数据库层兜底：唯一索引保证只有一行
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM dining.dining_meal_intake_records " +
                            "WHERE patient_id = '$PATIENT' AND intake_date = DATE '$date' AND meal_time = '午餐'",
                    ),
                    "重复提交不得新增行（唯一索引 uq_dining_meal_intake_records_meal 兜底）",
                )
                get(
                    vertx,
                    "/dining/v1/meal-intake-records?patient_id=${enc(PATIENT)}" +
                        "&date=${enc(date)}&meal_time=${enc("午餐")}",
                )
            }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "列表查询失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1, body!!.getJsonArray("records").size())
                assertEquals(1L, body.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) A3 口径：总量 = 落库值；明细之和 == 总量
    // ========================================================================

    @Test
    fun `营养分析_总量取落库值且明细之和一致`(vertx: Vertx, ctx: VertxTestContext) {
        val date = "2026-08-03"
        // 匹配菜品 100 g（116 kcal）+ 内置常量里有但菜品库无快照的「苹果」→ A1 对苹果计 0
        val body = JsonObject()
            .put("patient_id", PATIENT)
            .put("intake_date", date)
            .put("meal_time", "午餐")
            .put(
                "items",
                JsonArray().add(item(DISH_NAME, 100.0)).add(item(UNMATCHED_BUILTIN_FOOD, 100.0)),
            )
            .put("intake_ratio", 1.0)

        post(vertx, "/dining/v1/meal-intake-records", body).compose { (status, saved) ->
            ctx.verify {
                assertEquals(201, status, "登记必须 201，实际: $saved")
                assertEquals(
                    ENERGY_PER_100G,
                    saved!!.getDouble("energy_kcal") ?: -1.0,
                    0.001,
                    "A1 落库总量只含匹配菜品（苹果无快照计 0）",
                )
            }
            get(
                vertx,
                "/dining/v1/nutrition-analysis?patient_id=${enc(PATIENT)}" +
                    "&date=${enc(date)}&meal_time=${enc("午餐")}",
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "分析请求失败: ${ar.cause()?.message}")
                val (status, analysis) = ar.result()
                assertEquals(200, status, "分析必须 200，实际: $analysis")

                // 头部总量 = A1 落库值（唯一权威口径，task-11 裁定）
                assertEquals(ENERGY_PER_100G, analysis!!.getDouble("energy_kcal") ?: -1.0, 0.011, "总量取落库值")
                assertEquals(2.6, analysis.getDouble("protein_g") ?: -1.0, 0.011)
                assertEquals(0.3, analysis.getDouble("fat_g") ?: -1.0, 0.011)
                assertEquals(25.9, analysis.getDouble("carb_g") ?: -1.0, 0.011)
                assertEquals(2.5, analysis.getDouble("sodium_mg") ?: -1.0, 0.011)

                // 结论/参考值由服务端派生
                assertNotNull(analysis.getString("conclusion"))
                assertNotNull(analysis.getJsonObject("reference"))
                assertEquals(NutritionReference.VERSION, analysis.getString("reference_version"))

                val items = analysis.getJsonArray("items")!!
                assertEquals(2, items.size())
                assertEquals(true, items.getJsonObject(0).getBoolean("matched") ?: false)
                assertEquals(ENERGY_PER_100G, items.getJsonObject(0).getDouble("energy_kcal") ?: -1.0, 0.011)
                // 内置常量里有、菜品库无快照 → 明细计 0 且 matched=false（不再回落内置常量）
                assertEquals(false, items.getJsonObject(1).getBoolean("matched") ?: true, "无快照必须 matched=false")
                assertEquals(0.0, items.getJsonObject(1).getDouble("energy_kcal") ?: -1.0, 0.001, "无快照明细计 0")
                assertEquals(listOf(UNMATCHED_BUILTIN_FOOD), analysis.getJsonArray("unmatched")?.map { it.toString() })

                // 明细之和 == 头部总量
                val itemSum = (0 until items.size()).sumOf { items.getJsonObject(it).getDouble("energy_kcal") ?: 0.0 }
                assertEquals(
                    analysis.getDouble("energy_kcal") ?: -1.0,
                    itemSum,
                    0.011,
                    "明细能量之和必须等于返回总量，实际: $analysis",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) A4 状态机 + 过敏原覆盖写
    // ========================================================================

    @Test
    fun `特殊膳食申请_状态机合法流转与非法流转400`(vertx: Vertx, ctx: VertxTestContext) {
        val request = JsonObject()
            .put("patient_id", PATIENT)
            .put("request_type", "过敏回避")
            .put("meal_type", "午餐")
            .put("allergens", JsonArray().add("花生"))
            .put("reason", "过敏回避申请")

        post(vertx, "/dining/v1/special-diet-requests", request).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "创建必须 201，实际: $created")
                assertEquals("待审批", created!!.getString("status"), "创建恒为待审批")
                assertEquals(TESTER, created.getString("requested_by"), "申请人取认证主体")
            }
            val approvedId = created!!.getString("id")

            // 合法：待审批 → 已批准
            patch(vertx, "/dining/v1/special-diet-requests/$approvedId/status", JsonObject().put("status", "已批准"))
                .compose { (s2, approved) ->
                    ctx.verify {
                        assertEquals(200, s2, "待审批→已批准 必须 200，实际: $approved")
                        assertEquals("已批准", approved!!.getString("status"))
                        assertEquals(TESTER, approved.getString("reviewed_by"), "审批人取认证主体")
                        assertNotNull(approved.getString("reviewed_at"), "已批准必须落 reviewed_at")
                    }
                    // 合法：已批准 → 已终止
                    patch(
                        vertx,
                        "/dining/v1/special-diet-requests/$approvedId/status",
                        JsonObject().put("status", "已终止"),
                    )
                }
                .compose { (s3, terminated) ->
                    ctx.verify {
                        assertEquals(200, s3, "已批准→已终止 必须 200，实际: $terminated")
                        assertEquals("已终止", terminated!!.getString("status"))
                    }
                    // 非法：终态 → 已批准
                    patch(
                        vertx,
                        "/dining/v1/special-diet-requests/$approvedId/status",
                        JsonObject().put("status", "已批准"),
                    )
                }
                .compose { (s4, illegal) ->
                    ctx.verify {
                        assertEquals(400, s4, "终态再流转必须 400，实际: $illegal")
                        assertNotNull(illegal?.getString("error"))
                    }
                    // 落库校验：终态确实持久化
                    assertEquals(
                        "已终止",
                        queryString("SELECT status FROM dining.dining_special_diet_requests WHERE id = '$approvedId'"),
                        "状态必须落库",
                    )
                    // 另一单：待审批 → 已驳回 → 已批准（非法）
                    post(vertx, "/dining/v1/special-diet-requests", request)
                }
                .compose { (s5, created2) ->
                    ctx.verify { assertEquals(201, s5, "第二单创建必须 201，实际: $created2") }
                    val rejectedId = created2!!.getString("id")
                    patch(
                        vertx,
                        "/dining/v1/special-diet-requests/$rejectedId/status",
                        JsonObject().put("status", "已驳回"),
                    ).compose { (s6, rejected) ->
                        ctx.verify {
                            assertEquals(200, s6, "待审批→已驳回 必须 200，实际: $rejected")
                            assertEquals("已驳回", rejected!!.getString("status"))
                        }
                        patch(
                            vertx,
                            "/dining/v1/special-diet-requests/$rejectedId/status",
                            JsonObject().put("status", "已批准"),
                        )
                    }
                }
                .compose { (s7, illegal2) ->
                    ctx.verify {
                        assertEquals(400, s7, "已驳回→已批准 非法必须 400，实际: $illegal2")
                        assertNotNull(illegal2?.getString("error"))
                    }
                    // 非法目标状态值
                    post(vertx, "/dining/v1/special-diet-requests", request)
                }
                .compose { (s8, created3) ->
                    ctx.verify { assertEquals(201, s8, "第三单创建必须 201，实际: $created3") }
                    patch(
                        vertx,
                        "/dining/v1/special-diet-requests/${created3!!.getString("id")}/status",
                        JsonObject().put("status", "已完成"),
                    )
                }
                .compose { (s9, invalidStatus) ->
                    ctx.verify {
                        assertEquals(400, s9, "非法状态值必须 400，实际: $invalidStatus")
                    }
                    get(
                        vertx,
                        "/dining/v1/special-diet-requests?patient_id=${enc(PATIENT)}" +
                            "&status=${enc("已终止")}",
                    )
                }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "状态机链路失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"), "仅一单落在已终止")
                assertEquals(1, body.getJsonArray("records").size())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `过敏原标记_覆盖写既有列且无过敏主数据表`(vertx: Vertx, ctx: VertxTestContext) {
        val profile = JsonObject()
            .put("patient_id", PATIENT)
            .put("encounter_id", ENCOUNTER)
            .put("meal_type", "普食")

        post(vertx, "/dining/v1/diet-profiles", profile).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "建档必须 201，实际: $created")
                assertEquals("启用", created!!.getString("status"))
            }
            val profileId = created!!.getString("id")

            put(
                vertx,
                "/dining/v1/diet-profiles/$profileId/allergens",
                JsonObject().put("allergies", JsonArray().add("花生").add("牛奶")),
            ).compose { (s2, first) ->
                ctx.verify {
                    assertEquals(200, s2, "过敏原写入必须 200，实际: $first")
                    assertEquals(listOf("花生", "牛奶"), first!!.getJsonArray("allergies")?.map { it.toString() })
                }
                // 覆盖式：第二次写入必须整体替换
                put(
                    vertx,
                    "/dining/v1/diet-profiles/$profileId/allergens",
                    JsonObject().put("allergies", JsonArray().add("鸡蛋")),
                )
            }.compose { (s3, second) ->
                ctx.verify {
                    assertEquals(200, s3, "覆盖写必须 200，实际: $second")
                    assertEquals(
                        listOf("鸡蛋"),
                        second!!.getJsonArray("allergies")?.map { it.toString() },
                        "覆盖式写入必须整体替换，不是追加",
                    )
                }
                // 缺 key → 400（覆盖式必须显式给出）
                put(vertx, "/dining/v1/diet-profiles/$profileId/allergens", JsonObject())
            }.compose { (s4, missing) ->
                ctx.verify {
                    assertEquals(400, s4, "缺 allergies 键必须 400，实际: $missing")
                    assertNotNull(missing?.getString("error"))
                }
                get(vertx, "/dining/v1/diet-profiles?patient_id=${enc(PATIENT)}")
            }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "过敏原链路失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(
                    "[\"鸡蛋\"]",
                    queryString("SELECT allergies::text FROM dining.dining_diet_profiles WHERE id IS NOT NULL " +
                        "AND patient_id = '$PATIENT'"),
                    "过敏原必须落在既有列 dining_diet_profiles.allergies",
                )
                assertEquals(
                    0L,
                    queryLong(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'dining' " +
                            "AND table_name LIKE '%allergen%'",
                    ),
                    "不得新建过敏主数据表（Lead 裁定 L2）",
                )
                assertFalse(body!!.getJsonArray("records").isEmpty())
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) A5 周报
    // ========================================================================

    @Test
    fun `营养周报_跨周聚合与同周重复生成覆盖`(vertx: Vertx, ctx: VertxTestContext) {
        // 2026-08-03 是周一；周界 08-03 ~ 08-09
        val registrations = listOf(
            Triple("2026-08-03", "午餐", 100.0),   // 116.00
            Triple("2026-08-05", "晚餐", 200.0),   // 232.00
            Triple("2026-08-02", "午餐", 100.0),   // 上一周，必须排除
            Triple("2026-08-10", "午餐", 100.0),   // 下一周，必须排除
        )
        var chain: Future<Unit> = Future.succeededFuture(Unit)
        registrations.forEach { (date, meal, amount) ->
            chain = chain.compose {
                post(
                    vertx,
                    "/dining/v1/meal-intake-records",
                    JsonObject()
                        .put("patient_id", PATIENT)
                        .put("intake_date", date)
                        .put("meal_time", meal)
                        .put("items", JsonArray().add(item(DISH_NAME, amount)))
                        .put("intake_ratio", 1.0),
                ).compose { (status, body) ->
                    ctx.verify { assertEquals(201, status, "登记 $date/$meal 必须 201，实际: $body") }
                    Future.succeededFuture(Unit)
                }
            }
        }

        chain.compose {
            // 传周三，验证周界归一到周一
            post(
                vertx,
                "/dining/v1/nutrition-weekly-reports/generate",
                JsonObject().put("patient_id", PATIENT).put("week_start", "2026-08-05"),
            )
        }.compose { (s1, report) ->
            ctx.verify {
                assertEquals(201, s1, "生成周报必须 201，实际: $report")
                assertEquals("2026-08-03", report!!.getString("week_start"), "周三必须归一到该周周一")
                assertEquals("2026-08-09", report.getString("week_end"))
                assertEquals(2, report.getInteger("intake_days"), "只统计本周 2 天（跨周记录排除）")
                assertEquals(174.0, report.getDouble("energy_avg_kcal") ?: -1.0, 0.011, "(116+232)/2")
                assertEquals(TESTER, report.getString("generated_by"))
                assertEquals(NutritionReference.VERSION, report.getString("reference_version"))
                assertNotNull(report.getString("conclusion"))
                val daily = report.getJsonArray("daily")!!
                assertEquals(2, daily.size(), "daily 明细必须逐日")
                assertEquals("2026-08-03", daily.getJsonObject(0).getString("intake_date"))
                assertEquals("2026-08-05", daily.getJsonObject(1).getString("intake_date"))
                assertEquals(116.0, daily.getJsonObject(0).getDouble("energy_kcal") ?: -1.0, 0.011)
                assertEquals(232.0, daily.getJsonObject(1).getDouble("energy_kcal") ?: -1.0, 0.011)
                // 均值口径 = daily 之和 / 有记录天数
                val dailySum = (0 until daily.size()).sumOf { daily.getJsonObject(it).getDouble("energy_kcal") ?: 0.0 }
                assertEquals(
                    Math.round(dailySum / daily.size() * 100.0) / 100.0,
                    report.getDouble("energy_avg_kcal") ?: -1.0,
                    0.011,
                    "均值必须按有摄入记录的天数取平均",
                )
            }
            val reportId = report!!.getString("id")
            // 同周重复生成 → 覆盖，不新增
            post(
                vertx,
                "/dining/v1/nutrition-weekly-reports/generate",
                JsonObject().put("patient_id", PATIENT).put("week_start", "2026-08-03"),
            ).compose { (s2, again) ->
                ctx.verify {
                    assertEquals(201, s2, "重复生成必须 201，实际: $again")
                    assertEquals(reportId, again!!.getString("id"), "同儿童同周必须命中同一条（覆盖）")
                }
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM dining.dining_nutrition_weekly_reports " +
                            "WHERE patient_id = '$PATIENT' AND week_start = DATE '2026-08-03'",
                    ),
                    "同周重复生成不得新增行（唯一索引 uq_dining_nutrition_weekly_reports_week）",
                )
                // 新增本周第 3 天后再生成 → 覆盖更新
                post(
                    vertx,
                    "/dining/v1/meal-intake-records",
                    JsonObject()
                        .put("patient_id", PATIENT)
                        .put("intake_date", "2026-08-07")
                        .put("meal_time", "早餐")
                        .put("items", JsonArray().add(item(DISH_NAME, 50.0)))
                        .put("intake_ratio", 1.0),
                )
            }
        }.compose { (s3, _) ->
            ctx.verify { assertEquals(201, s3, "新增第 3 天登记必须 201") }
            post(
                vertx,
                "/dining/v1/nutrition-weekly-reports/generate",
                JsonObject().put("patient_id", PATIENT).put("week_start", "2026-08-03"),
            )
        }.compose { (s4, refreshed) ->
            ctx.verify {
                assertEquals(201, s4, "覆盖生成必须 201，实际: $refreshed")
                assertEquals(3, refreshed!!.getInteger("intake_days"), "覆盖更新必须反映新增天数")
                assertEquals(135.33, refreshed.getDouble("energy_avg_kcal") ?: -1.0, 0.011, "(116+232+58)/3")
            }
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM dining.dining_nutrition_weekly_reports " +
                        "WHERE patient_id = '$PATIENT' AND week_start = DATE '2026-08-03'",
                ),
                "覆盖后仍只有一行",
            )
            get(
                vertx,
                "/dining/v1/nutrition-weekly-reports?patient_id=${enc(PATIENT)}" +
                    "&week_start=${enc("2026-08-05")}",
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "周报链路失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(1L, body!!.getJsonObject("meta").getLong("total"), "按周三过滤必须命中该周周报")
                assertEquals("2026-08-03", body.getJsonArray("records").getJsonObject(0).getString("week_start"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `营养周报_空数据周可判定不500`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/dining/v1/nutrition-weekly-reports/generate",
            JsonObject().put("patient_id", PATIENT).put("week_start", "2026-09-07"),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "空数据周生成失败: ${ar.cause()?.message}")
                val (status, report) = ar.result()
                assertEquals(201, status, "空数据周也必须 201（不 500），实际: $report")
                assertEquals(0, report!!.getInteger("intake_days"))
                assertEquals(0.0, report.getDouble("energy_avg_kcal") ?: -1.0, 0.001)
                assertEquals(0, report.getJsonArray("daily")!!.size())
                assertEquals("无摄入记录", report.getString("conclusion"), "空数据周结论必须可判定")
                assertEquals("2026-09-07", report.getString("week_start"))
                assertEquals("2026-09-13", report.getString("week_end"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  HTTP / JDBC 辅助
    // ========================================================================

    private fun item(foodName: String, amountG: Double): JsonObject =
        JsonObject().put("food_name", foodName).put("amount_g", amountG)

    /**
     * 查询参数百分号编码。
     * 中文枚举（餐次 `午餐`、状态 `已终止` 等）以裸 UTF-8 字节进 URL 时服务端解析不到白名单 → 400，
     * 故**所有**查询参数统一经此编码（与前端 `URLSearchParams`、既有路由测试同口径）。
     */
    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun send(
        vertx: Vertx,
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
        send(vertx, HttpMethod.GET, path)

    private fun post(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(vertx, HttpMethod.POST, path, body)

    private fun put(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(vertx, HttpMethod.PUT, path, body)

    private fun patch(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(vertx, HttpMethod.PATCH, path, body)

    /**
     * 自包含：重置隔离库 `aceso_test`，保证只应用 dining 段迁移（V600/V601/V602）。
     * 与 `DiningServiceIntegrationTest` 同范式；只操作 `aceso_test`，不触碰 5432/业务库。
     */
    private fun resetTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    /**
     * V600/V601/V602 含 `healthcare.patients/encounters` 跨 schema 外键，迁移前先建最小表。
     * 同模块其它集成类可能已用更少列建过同名表，故用 `ALTER ... ADD COLUMN IF NOT EXISTS` 补齐。
     */
    private fun createMinimalHealthcareSchema() {
        exec("CREATE SCHEMA IF NOT EXISTS healthcare")
        exec(
            "CREATE TABLE IF NOT EXISTS healthcare.patients (" +
                "id VARCHAR(32) PRIMARY KEY, name VARCHAR NOT NULL DEFAULT '', " +
                "gender VARCHAR NOT NULL DEFAULT '', status VARCHAR DEFAULT 'ACTIVE')",
        )
        exec("ALTER TABLE healthcare.patients ADD COLUMN IF NOT EXISTS birth_date DATE")
        // 054 A4b（DietProfileService 按 person_type 分流）要求该列存在；
        // 取值口径与 V525 一致（居民/长者/儿童，NOT NULL DEFAULT '居民'）。
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
                st.executeQuery(sql).use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    private fun queryString(sql: String): String? =
        jdbc { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    /** 自带 dining 清理（`AcesoDbIntegrationTestBase.cleanupAll` 无 `dining.*` 语句）。 */
    private fun cleanupFixtures() {
        exec("DELETE FROM dining.dining_meal_intake_records WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM dining.dining_nutrition_weekly_reports WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM dining.dining_special_diet_requests WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM dining.dining_diet_profiles WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM dining.dining_dishes WHERE id LIKE '$PREFIX%' OR name LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.encounters WHERE id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    /** 残差必须为 0；否则说明 fixture 泄漏。 */
    private fun assertNoResidual() {
        val residuals = mapOf(
            "dining.dining_meal_intake_records" to
                "SELECT count(*) FROM dining.dining_meal_intake_records WHERE patient_id LIKE '$PREFIX%'",
            "dining.dining_nutrition_weekly_reports" to
                "SELECT count(*) FROM dining.dining_nutrition_weekly_reports WHERE patient_id LIKE '$PREFIX%'",
            "dining.dining_special_diet_requests" to
                "SELECT count(*) FROM dining.dining_special_diet_requests WHERE patient_id LIKE '$PREFIX%'",
            "dining.dining_diet_profiles" to
                "SELECT count(*) FROM dining.dining_diet_profiles WHERE patient_id LIKE '$PREFIX%'",
            "dining.dining_dishes" to
                "SELECT count(*) FROM dining.dining_dishes WHERE id LIKE '$PREFIX%' OR name LIKE '$PREFIX%'",
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
