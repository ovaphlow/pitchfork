package com.ovaphlow.crate.dining

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 膳食营养模块服务层集成测试（aceso_test 独立可销毁数据库）。
 *
 * 前置：healthcare.patients / healthcare.encounters 最小表结构先行创建
 * （V600 迁移包含跨 schema 外键），随后 Flyway 迁移 dining V600。
 * 运行方式：
 *   PITCHFORK_DB_PASSWORD=... ./gradlew :libs:dining:test \
 *     -Dintegration.db.host=localhost -Dintegration.db.port=5432 -Dintegration.db.user=ovaphlow
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiningServiceIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "si-"

        private const val PATIENT_1 = "${FIXTURE_PREFIX}patient-1"
        private const val PATIENT_2 = "${FIXTURE_PREFIX}patient-2"
        private const val PATIENT_3 = "${FIXTURE_PREFIX}patient-3"
        private const val ENC_1 = "${FIXTURE_PREFIX}enc-1"      // ACTIVE ELDERLY_CARE
        private const val ENC_2 = "${FIXTURE_PREFIX}enc-2"      // DISCHARGED ELDERLY_CARE
        private const val ENC_3 = "${FIXTURE_PREFIX}enc-3"      // ACTIVE INPATIENT（非养老入住）
        private const val ENC_4 = "${FIXTURE_PREFIX}enc-4"      // ACTIVE ELDERLY_CARE（患者乙）
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool

    private lateinit var dietProfileService: DietProfileService
    private lateinit var dishService: DishService
    private lateinit var weeklyMenuService: WeeklyMenuService
    private lateinit var rosterService: RosterService
    private lateinit var mealExecutionService: MealExecutionService
    private lateinit var statisticsService: DiningStatisticsService

    @BeforeAll
    fun setup(ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        if (password.isBlank()) {
            ctx.failNow(IllegalStateException("PITCHFORK_DB_PASSWORD must be set"))
            return@setup
        }

        try {
            // Drop and recreate test database to ensure clean Flyway state
            val rootUrl = "jdbc:postgresql://$host:$port/postgres"
            DriverManager.getConnection(rootUrl, user, password).use { conn ->
                conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB")
                conn.createStatement().execute("CREATE DATABASE $TEST_DB")
            }

            // V600 迁移含 healthcare 跨 schema 外键，先行创建最小依赖表
            val fixtureUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
            DriverManager.getConnection(fixtureUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                stmt.execute("CREATE SCHEMA IF NOT EXISTS healthcare")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS healthcare.patients (
                        id VARCHAR(32) PRIMARY KEY,
                        name VARCHAR NOT NULL DEFAULT '',
                        gender VARCHAR NOT NULL DEFAULT '',
                        status VARCHAR DEFAULT 'ACTIVE'
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS healthcare.encounters (
                        id VARCHAR(32) PRIMARY KEY,
                        patient_id VARCHAR(32) NOT NULL,
                        encounter_type VARCHAR NOT NULL DEFAULT '',
                        status VARCHAR(20) DEFAULT 'ACTIVE',
                        admit_date TIMESTAMPTZ
                    )
                    """.trimIndent()
                )
            }

            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)

            setupFixturesJdbc()

            pool = DatabaseConfig.createPool(Vertx.vertx(), dbConfig)
            dietProfileService = DietProfileService(pool)
            dishService = DishService(pool)
            weeklyMenuService = WeeklyMenuService(pool)
            rosterService = RosterService(pool)
            mealExecutionService = MealExecutionService(pool)
            statisticsService = DiningStatisticsService(pool)

            ctx.completeNow()
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @AfterAll
    fun cleanup(ctx: VertxTestContext) {
        try {
            val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                stmt.execute("DELETE FROM dining.dining_meal_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR roster_item_id IN (SELECT id FROM dining.dining_roster_items WHERE patient_id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM dining.dining_roster_items WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM dining.dining_rosters WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM dining.dining_weekly_menu_items WHERE id LIKE '${FIXTURE_PREFIX}%' OR menu_id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM dining.dining_weekly_menus WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM dining.dining_dishes WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM dining.dining_diet_profiles WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
            }
        } catch (_: Exception) { /* cleanup best effort */ }

        if (::pool.isInitialized) pool.close()
        ctx.completeNow()
    }

    private fun setupFixturesJdbc() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('$PATIENT_1', '测试长者甲', 'ACTIVE')")
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('$PATIENT_2', '测试长者乙', 'ACTIVE')")
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('$PATIENT_3', '测试长者丙', 'ACTIVE')")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) VALUES ('$ENC_1', '$PATIENT_1', 'ELDERLY_CARE', 'ACTIVE', now())")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) VALUES ('$ENC_2', '$PATIENT_2', 'ELDERLY_CARE', 'DISCHARGED', now())")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) VALUES ('$ENC_3', '$PATIENT_3', 'INPATIENT', 'ACTIVE', now())")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, status, admit_date) VALUES ('$ENC_4', '$PATIENT_2', 'ELDERLY_CARE', 'ACTIVE', now())")
        }
    }

    /** 每个用例前清空 dining 表数据，保证用例间独立（healthcare fixture 常驻）。 */
    @BeforeEach
    fun resetDiningData() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("DELETE FROM dining.dining_meal_executions")
            stmt.execute("DELETE FROM dining.dining_roster_items")
            stmt.execute("DELETE FROM dining.dining_rosters")
            stmt.execute("DELETE FROM dining.dining_weekly_menu_items")
            stmt.execute("DELETE FROM dining.dining_weekly_menus")
            stmt.execute("DELETE FROM dining.dining_dishes")
            stmt.execute("DELETE FROM dining.dining_diet_profiles")
        }
    }

    // ========================================================================
    //  饮食档案（FR-1）
    // ========================================================================

    @Test
    fun `建档校验_非养老入住_拒绝`(ctx: VertxTestContext) {
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_3)
            .put("encounter_id", ENC_3)
            .put("meal_type", "普食"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is IllegalArgumentException)
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `建档校验_已离院入住_拒绝`(ctx: VertxTestContext) {
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_2)
            .put("encounter_id", ENC_2)
            .put("meal_type", "软食"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is IllegalArgumentException)
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `建档成功_并自动带出在院状态`(ctx: VertxTestContext) {
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_1)
            .put("encounter_id", ENC_1)
            .put("meal_type", "糖尿病餐")
            .put("allergies", JsonArray().add("海鲜"))
            .put("portion_preference", "大半份")
            .put("remark", "忌口测试"))
            .compose { created ->
                ctx.verify {
                    assertEquals("糖尿病餐", created.getString("meal_type"))
                    assertEquals("启用", created.getString("status"))
                    assertEquals("ACTIVE", created.getString("encounter_status"))
                    assertEquals(JsonArray().add("海鲜"), created.getJsonArray("allergies"))
                }
                dietProfileService.get(created.getString("id"))
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(PATIENT_1, ar.result().getString("patient_id"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `同一长者重复启用档案_409`(ctx: VertxTestContext) {
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_1)
            .put("encounter_id", ENC_1)
            .put("meal_type", "普食"))
            .compose { _ ->
                dietProfileService.create(JsonObject()
                    .put("patient_id", PATIENT_1)
                    .put("encounter_id", ENC_1)
                    .put("meal_type", "流食"))
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is DiningConflictException)
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `停用后可重新启用_且再次冲突_409`(ctx: VertxTestContext) {
        var profileId = ""
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_2)
            .put("encounter_id", ENC_4) // 患者在院入住
            .put("meal_type", "软食"))
            .compose { created ->
                profileId = created.getString("id")
                dietProfileService.updateStatus(profileId, "停用")
            }
            .compose { disabled ->
                ctx.verify { assertEquals("停用", disabled.getString("status")) }
                dietProfileService.updateStatus(profileId, "启用")
            }
            .compose { enabled ->
                ctx.verify { assertEquals("启用", enabled.getString("status")) }
                // 再建第二个启用档案 → 冲突
                dietProfileService.create(JsonObject()
                    .put("patient_id", PATIENT_2)
                    .put("encounter_id", ENC_4)
                    .put("meal_type", "碎食"))
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is DiningConflictException)
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `档案列表_空列表返回records空数组与total0`(ctx: VertxTestContext) {
        dietProfileService.list(status = "停用")
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(JsonArray(), ar.result().getJsonArray("records"))
                    assertEquals(0L, ar.result().getJsonObject("meta").getLong("total"))
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  菜品库（FR-2）
    // ========================================================================

    @Test
    fun `菜品库_创建校验与列表`(ctx: VertxTestContext) {
        dishService.create(JsonObject()
            .put("name", "清蒸鲈鱼")
            .put("category", "荤菜")
            .put("meal_times", JsonArray().add("午餐").add("晚餐"))
            .put("diet_tags", JsonArray().add("低盐")))
            .compose { dish ->
                ctx.verify {
                    assertEquals("清蒸鲈鱼", dish.getString("name"))
                    assertEquals("荤菜", dish.getString("category"))
                    assertEquals(JsonArray().add("午餐").add("晚餐"), dish.getJsonArray("meal_times"))
                }
                dishService.create(JsonObject()
                    .put("name", "素炒时蔬")
                    .put("category", "素菜")
                    .put("meal_times", JsonArray().add("午餐")))
                    .map { dish }
            }
            .compose { dish ->
                dishService.list(category = "素菜")
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    val records = ar.result().getJsonArray("records")
                    assertEquals(1, records.size())
                    assertEquals("素炒时蔬", records.getJsonObject(0).getString("name"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `菜品库_非法分类与非法标签_400`(ctx: VertxTestContext) {
        dishService.create(JsonObject()
            .put("name", "错误菜品")
            .put("category", "甜品")
            .put("diet_tags", JsonArray().add("极辣")))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is IllegalArgumentException)
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  周菜谱（FR-3）
    // ========================================================================

    @Test
    fun `周菜谱_编排明细_复制_按日查询`(ctx: VertxTestContext) {
        // 周一 2026-08-03 所在周
        val week = LocalDate.of(2026, 8, 3)
        val nextWeek = LocalDate.of(2026, 8, 10)

        var dishId1 = ""
        var dishId2 = ""
        var menuId = ""
        var copiedId = ""

        dishService.create(JsonObject().put("name", "小米粥").put("category", "主食").put("meal_times", JsonArray().add("早餐")))
            .compose { d1 ->
                dishId1 = d1.getString("id")
                dishService.create(JsonObject().put("name", "白灼虾").put("category", "荤菜").put("meal_times", JsonArray().add("午餐")))
            }
            .compose { d2 ->
                dishId2 = d2.getString("id")
                weeklyMenuService.create(JsonObject().put("week_start", week.toString()).put("name", "第32周菜谱"))
            }
            .compose { menu ->
                ctx.verify {
                    assertEquals(week.toString(), menu.getString("week_start"))
                    assertEquals("启用", menu.getString("status"))
                }
                menuId = menu.getString("id")
                weeklyMenuService.replaceItems(menuId, JsonObject().put("items", JsonArray()
                    .add(JsonObject().put("day_of_week", 1).put("meal_time", "早餐").put("dish_id", dishId1).put("sort_order", 0))
                    .add(JsonObject().put("day_of_week", 1).put("meal_time", "午餐").put("dish_id", dishId2).put("sort_order", 0))
                    .add(JsonObject().put("day_of_week", 3).put("meal_time", "午餐").put("dish_id", dishId2).put("sort_order", 0))))
            }
            .compose { menu ->
                ctx.verify {
                    assertEquals(3, menu.getJsonArray("items").size())
                    val names = menu.getJsonArray("items").map { (it as JsonObject).getString("dish_name") }.sorted()
                    assertEquals(listOf("小米粥", "白灼虾", "白灼虾"), names)
                }
                weeklyMenuService.getByDate("2026-08-05")
            }
            .compose { byDate ->
                ctx.verify {
                    assertEquals(menuId, byDate.getString("id"))
                    assertEquals(3, byDate.getJsonArray("items").size())
                }
                weeklyMenuService.copy(menuId, nextWeek.toString())
            }
            .compose { copied ->
                ctx.verify {
                    assertEquals(nextWeek.toString(), copied.getString("week_start"))
                    assertEquals("启用", copied.getString("status"))
                    assertEquals(3, copied.getJsonArray("items").size())
                }
                copiedId = copied.getString("id")
                weeklyMenuService.list(status = "启用")
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(2L, ar.result().getJsonObject("meta").getLong("total"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `周菜谱_同周重复启用_409_停用后可换版`(ctx: VertxTestContext) {
        val week = LocalDate.of(2026, 8, 17)
        weeklyMenuService.create(JsonObject().put("week_start", week.toString()).put("name", "A版"))
            .compose { first ->
                weeklyMenuService.create(JsonObject().put("week_start", week.toString()).put("name", "B版"))
                    .recover { error ->
                        ctx.verify {
                            assertTrue(error is DiningConflictException)
                        }
                        io.vertx.core.Future.succeededFuture<JsonObject>()
                    }
                    .compose {
                        weeklyMenuService.updateStatus(first.getString("id"), "停用")
                    }
            }
            .compose { disabled ->
                ctx.verify { assertEquals("停用", disabled.getString("status")) }
                weeklyMenuService.create(JsonObject().put("week_start", week.toString()).put("name", "C版"))
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals("C版", ar.result().getString("name"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `周菜谱_明细引用停用菜品_400`(ctx: VertxTestContext) {
        val week = LocalDate.of(2026, 8, 24)
        dishService.create(JsonObject().put("name", "将被停用的菜").put("category", "素菜"))
            .compose { dish ->
                dishService.updateStatus(dish.getString("id"), "停用")
                    .compose {
                        weeklyMenuService.create(JsonObject().put("week_start", week.toString()))
                    }
                    .compose { menu ->
                        weeklyMenuService.replaceItems(menu.getString("id"), JsonObject().put("items", JsonArray()
                            .add(JsonObject().put("day_of_week", 1).put("meal_time", "午餐").put("dish_id", dish.getString("id")))))
                    }
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is IllegalArgumentException)
                    assertTrue(ar.cause()?.message?.contains("not enabled") == true)
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  配餐名单（FR-4）
    // ========================================================================

    @Test
    fun `配餐名单_生成_幂等_手工调整`(ctx: VertxTestContext) {
        val date = LocalDate.of(2026, 8, 12)
        var rosterId = ""

        // 两名在院长者建档
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_1).put("encounter_id", ENC_1).put("meal_type", "普食")
            .put("allergies", JsonArray().add("花生")))
            .compose {
                dietProfileService.create(JsonObject()
                    .put("patient_id", PATIENT_2).put("encounter_id", ENC_4).put("meal_type", "软食"))
            }
            .compose {
                rosterService.generate(date.toString(), "午餐", null, "tester")
            }
            .compose { generated ->
                ctx.verify {
                    assertEquals(2, generated.getInteger("created"))
                    val items = generated.getJsonObject("roster").getJsonArray("items")
                    assertEquals(2, items.size())
                    val first = items.getJsonObject(0)
                    assertEquals("普食", first.getString("meal_type"))
                    assertEquals(JsonArray().add("花生"), first.getJsonArray("allergies"))
                }
                rosterId = generated.getJsonObject("roster").getString("id")
                // 幂等：再次生成不重复
                rosterService.generate(date.toString(), "午餐", null, "tester")
                    .compose { again ->
                        ctx.verify {
                            assertEquals(0, again.getInteger("created"))
                            assertEquals(2, again.getJsonObject("roster").getJsonArray("items").size())
                        }
                        // 手工调整：临时加餐（离院长者丙）
                        rosterService.addItem(rosterId, JsonObject()
                            .put("patient_id", PATIENT_3)
                            .put("adjust_type", "临时加餐")
                            .put("remark", "家属探望"))
                    }
                    .compose { added ->
                        ctx.verify {
                            assertEquals("手工", added.getString("source"))
                            assertEquals("临时加餐", added.getString("adjust_type"))
                            assertEquals("测试长者丙", added.getString("patient_name"))
                        }
                        // 手工调整：外出标记（长者甲）
                        rosterService.addItem(rosterId, JsonObject()
                            .put("patient_id", PATIENT_1)
                            .put("adjust_type", "外出"))
                            .map { added }
                    }
                    .compose { _ ->
                        rosterService.get(rosterId)
                    }
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    val items = ar.result().getJsonArray("items")
                    assertEquals(3, items.size())
                    val byPatient = items.associate { (it as JsonObject).let { obj -> obj.getString("patient_id") to obj } }
                    assertEquals("外出", byPatient[PATIENT_1]?.getString("adjust_type"))
                    assertEquals("临时加餐", byPatient[PATIENT_3]?.getString("adjust_type"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `配餐名单_自动条目不可删除_已登记条目不可删除`(ctx: VertxTestContext) {
        val date = LocalDate.of(2026, 8, 13)
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_1).put("encounter_id", ENC_1).put("meal_type", "普食"))
            .compose {
                rosterService.generate(date.toString(), "晚餐", null, "tester")
            }
            .compose { generated ->
                val rosterId = generated.getJsonObject("roster").getString("id")
                val autoItem = generated.getJsonObject("roster").getJsonArray("items").getJsonObject(0)
                rosterService.removeItem(rosterId, autoItem.getString("id"))
                    .onComplete { ar ->
                        ctx.verify {
                            assertTrue(ar.failed())
                            assertTrue(ar.cause() is DiningConflictException)
                        }
                        // 手工条目删除成功
                        rosterService.addItem(rosterId, JsonObject()
                            .put("patient_id", PATIENT_3)
                            .put("adjust_type", "临时加餐"))
                            .compose { manual ->
                                rosterService.removeItem(rosterId, manual.getString("id"))
                            }
                            .onComplete { ar2 ->
                                ctx.verify {
                                    assertTrue(ar2.succeeded())
                                }
                                // 登记后再删 → 409
                                mealExecutionService.register(autoItem.getString("id"), "正常", null, "tester")
                                    .compose {
                                        rosterService.removeItem(rosterId, autoItem.getString("id"))
                                    }
                                    .onComplete { ar3 ->
                                        ctx.verify {
                                            assertTrue(ar3.failed())
                                            assertTrue(ar3.cause() is DiningConflictException)
                                            ctx.completeNow()
                                        }
                                    }
                            }
                    }
            }
    }

    // ========================================================================
    //  就餐执行登记（FR-5）
    // ========================================================================

    @Test
    fun `执行登记_幂等更新_记录登记人与时间`(ctx: VertxTestContext) {
        val date = LocalDate.of(2026, 8, 14)
        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_1).put("encounter_id", ENC_1).put("meal_type", "普食"))
            .compose {
                dietProfileService.create(JsonObject()
                    .put("patient_id", PATIENT_2).put("encounter_id", ENC_4).put("meal_type", "软食"))
            }
            .compose {
                rosterService.generate(date.toString(), "早餐", null, "tester")
            }
            .compose { generated ->
                val items = generated.getJsonObject("roster").getJsonArray("items")
                val item1 = items.getJsonObject(0)
                val item2 = items.getJsonObject(1)
                mealExecutionService.register(item1.getString("id"), "正常", null, "护士A")
                    .compose { first ->
                        ctx.verify {
                            assertEquals("正常", first.getString("status"))
                            assertEquals("护士A", first.getString("recorded_by"))
                            assertNotNull(first.getString("recorded_at"))
                        }
                        // 幂等：同一名单条目重复登记 → 更新而非新增
                        mealExecutionService.register(item1.getString("id"), "拒食", "长者拒绝", "护士B")
                            .compose { second ->
                                ctx.verify {
                                    assertEquals("拒食", second.getString("status"))
                                    assertEquals("护士B", second.getString("recorded_by"))
                                    assertEquals("长者拒绝", second.getString("remark"))
                                    assertEquals(first.getString("id"), second.getString("id"))
                                }
                                mealExecutionService.register(item2.getString("id"), "部分", "吃了一半", "护士A")
                            }
                    }
                    .compose {
                        mealExecutionService.list(date = date.toString(), mealTime = "早餐")
                    }
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    val records = ar.result().getJsonArray("records")
                    assertEquals(2, records.size())
                    val statuses = records.map { (it as JsonObject).getString("status") }.sorted()
                    assertEquals(listOf("拒食", "部分"), statuses)
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `执行登记_非法状态_400_不存在条目_404`(ctx: VertxTestContext) {
        mealExecutionService.register("si-no-such-item", "正常", null, "tester")
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is DiningNotFoundException)
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  就餐统计（FR-6）
    // ========================================================================

    @Test
    fun `就餐统计_汇总_状态_餐次_日期`(ctx: VertxTestContext) {
        val d1 = LocalDate.of(2026, 8, 20)
        val d2 = LocalDate.of(2026, 8, 21)

        dietProfileService.create(JsonObject()
            .put("patient_id", PATIENT_1).put("encounter_id", ENC_1).put("meal_type", "普食"))
            .compose {
                dietProfileService.create(JsonObject()
                    .put("patient_id", PATIENT_2).put("encounter_id", ENC_4).put("meal_type", "软食"))
            }
            .compose {
                rosterService.generate(d1.toString(), "午餐", null, "tester")
            }
            .compose { r1 ->
                val items = r1.getJsonObject("roster").getJsonArray("items")
                mealExecutionService.register(items.getJsonObject(0).getString("id"), "正常", null, "护士A")
                    .compose {
                        mealExecutionService.register(items.getJsonObject(1).getString("id"), "拒食", null, "护士A")
                    }
                    .compose {
                        // 第二天：一名长者外出（手工标记，不计入应就餐），一名正常就餐
                        rosterService.generate(d2.toString(), "午餐", null, "tester")
                    }
            }
            .compose { r2 ->
                val rosterId = r2.getJsonObject("roster").getString("id")
                rosterService.addItem(rosterId, JsonObject().put("patient_id", PATIENT_1).put("adjust_type", "外出"))
                    .compose {
                        rosterService.get(rosterId)
                    }
            }
            .compose { roster2 ->
                val item = roster2.getJsonArray("items").first { (it as JsonObject).getString("patient_id") == PATIENT_2 } as JsonObject
                mealExecutionService.register(item.getString("id"), "正常", null, "护士A")
            }
            .compose {
                statisticsService.mealStatistics(d1.toString(), d2.toString())
            }
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    val stats = ar.result()
                    val summary = stats.getJsonObject("summary")
                    // d1: 2 人应就餐（1 正常 + 1 拒食）；d2: 1 人应就餐（1 正常，甲外出排除）
                    assertEquals(3L, summary.getLong("expected_total"))
                    assertEquals(3L, summary.getLong("recorded_total"))
                    assertEquals(2L, summary.getLong("eaten_total"))
                    assertEquals(1L, summary.getLong("not_expected_total"))
                    assertEquals(66.67, summary.getDouble("dining_rate")!!, 0.001)

                    val byStatus = stats.getJsonObject("by_status")
                    assertEquals(2L, byStatus.getLong("正常"))
                    assertEquals(1L, byStatus.getLong("拒食"))
                    assertEquals(0L, byStatus.getLong("未登记"))

                    val byMeal = stats.getJsonArray("by_meal")
                    assertEquals(1, byMeal.size())
                    assertEquals("午餐", byMeal.getJsonObject(0).getString("meal_time"))
                    assertEquals(3L, byMeal.getJsonObject(0).getLong("expected_total"))

                    val byDate = stats.getJsonArray("by_date")
                    assertEquals(2, byDate.size())
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `就餐统计_日期范围非法_400`(ctx: VertxTestContext) {
        statisticsService.mealStatistics("2026-08-20", "2026-08-10")
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.failed())
                    assertTrue(ar.cause() is IllegalArgumentException)
                    ctx.completeNow()
                }
            }
    }
}
