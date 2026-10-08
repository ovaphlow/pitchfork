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
import java.util.UUID

/**
 * 054 C2（保育员班级日程与环境安全巡查）**真库集成测试**。
 *
 * 覆盖（054 §8.3 / task-24 C 链）：
 *  1. `V414` 真迁移：`uq_childcare_class_schedules_slot`（**部分唯一索引**，谓词 `status <> '已取消'`）
 *     与 `idx_childcare_safety_inspections_open` 生效，`EXPLAIN` 佐证可命中。
 *  2. **同时段冲突并发 409**：同 `(class_name, schedule_date, time_slot)` 两次并发 POST →
 *     恰一成功一 409；`已取消` 后同槽位**可再建**（部分索引释放时段）。
 *  3. **items JSONB 往返**：`{item_code,result,hazard}`，`hazard` 缺省写 `null` **不丢键**，回读一致。
 *  4. **业务时区当日筛选跨 UTC 日界**：`23:30+08:00` 与 `00:30+08:00` 的 UTC 日期相同、
 *     业务日不同（`(inspected_at AT TIME ZONE 'Asia/Shanghai')::date`）。
 *  5. **状态机真库闭环**：巡查 `待整改 →(items)→ 已整改 →(close)→ 已关闭`，每步 status/updated_at 变化；
 *     **跳步（待整改 → close）409 且无 UPDATE**；日程 `草稿→已发布→已取消`，非法流转 409。
 *  6. 空列表形状 `{records:[],meta:{total:0}}`；残差 0。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 nursing 段 V400–V414 + inventories V200–V204）：
 * ```
 * cd service-vertx-kotlin && export PITCHFORK_DB_PASSWORD=<hidden>
 * ./gradlew :libs:nursing:test --rerun --tests '*ChildcareScheduleAndSafetyIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 唯一后缀；测试前后自清并断言残差 0；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildcareScheduleAndSafetyIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054c2-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CLASS_A = "${PREFIX}班-$RUN"
        private val AREA_A = "${PREFIX}区域-$RUN"
        private val SLOT = "09:00-10:00"
        private val SCHEDULE_DATE = "2026-08-10"

        private const val TESTER = "qa054c2-tester"
        private const val SCHEDULES = "/nursing/v1/childcare-class-schedules"
        private const val INSPECTIONS = "/nursing/v1/childcare-safety-inspections"
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
    fun cleanupBeforeEach() {
        cleanupFixtures()
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V414 迁移：部分唯一索引
    // ========================================================================

    @Test
    fun `V414迁移_部分唯一索引与巡查开放索引生效`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "nursing.nursing_childcare_class_schedules",
                queryString("SELECT to_regclass('nursing.nursing_childcare_class_schedules')::text"),
            )
            assertEquals(
                "nursing.nursing_childcare_safety_inspections",
                queryString("SELECT to_regclass('nursing.nursing_childcare_safety_inspections')::text"),
            )
            val slotIndex = queryString(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'nursing' " +
                    "AND indexname = 'uq_childcare_class_schedules_slot'",
            )
            assertNotNull(slotIndex, "V414 必须建出同时段唯一索引")
            assertTrue(
                slotIndex!!.contains("已取消") && slotIndex.contains("WHERE"),
                "唯一索引必须是部分索引且谓词含 status <> '已取消'，实际: $slotIndex",
            )
            val openIndex = queryString(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'nursing' " +
                    "AND indexname = 'idx_childcare_safety_inspections_open'",
            )
            assertNotNull(openIndex)
            assertTrue(
                openIndex!!.contains("已关闭") && openIndex.contains("WHERE"),
                "巡查开放索引必须是部分索引且谓词含 status <> '已关闭'，实际: $openIndex",
            )
        }
        // EXPLAIN 佐证：关掉 seqscan 后同时段冲突查询必须命中该部分唯一索引
        val plan = explain(
            "EXPLAIN SELECT id FROM nursing.nursing_childcare_class_schedules " +
                "WHERE class_name = '$CLASS_A' AND schedule_date = DATE '$SCHEDULE_DATE' " +
                "AND time_slot = '$SLOT' AND status <> '已取消'",
        )
        ctx.verify {
            assertTrue(
                plan.any { it.contains("uq_childcare_class_schedules_slot") },
                "EXPLAIN 必须命中 uq_childcare_class_schedules_slot，实际计划: $plan",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 同时段冲突并发 409 + 取消后可再排
    // ========================================================================

    @Test
    fun `同时段冲突_并发恰一成功一409_取消后可再排`(vertx: Vertx, ctx: VertxTestContext) {
        val body = scheduleBody("集体音乐活动")
        val first = post(vertx, SCHEDULES, body).map { it.first }
        val second = post(vertx, SCHEDULES, body).map { it.first }
        Future.all(first, second).compose { composite ->
            ctx.verify {
                assertTrue(composite.succeeded(), "并发请求失败: ${composite.cause()?.message}")
                val statuses = listOf(
                    composite.resultAt<Int>(0),
                    composite.resultAt<Int>(1),
                ).sorted()
                assertEquals(listOf(201, 409), statuses, "同槽位并发必须恰一成功一 409")
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_childcare_class_schedules " +
                            "WHERE class_name = '$CLASS_A' AND status <> '已取消'",
                    ),
                    "部分唯一索引必须兜底为一行",
                )
            }
            get(vertx, "$SCHEDULES?class_name=${enc(CLASS_A)}")
        }.compose { (s2, listed) ->
            ctx.verify {
                assertEquals(200, s2, "列表必须 200，实际: $listed")
                assertEquals(1L, listed!!.getJsonObject("meta").getLong("total"))
            }
            val id = listed!!.getJsonArray("records").getJsonObject(0).getString("id")
            patch(vertx, "$SCHEDULES/$id/status", JsonObject().put("status", "已取消"))
        }.compose { (s3, cancelled) ->
            ctx.verify {
                assertEquals(200, s3, "取消必须 200，实际: $cancelled")
                assertEquals("已取消", cancelled!!.getString("status"))
            }
            // 取消后时段释放：同槽位可再排
            post(vertx, SCHEDULES, scheduleBody("重排的集体活动"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "重排失败: ${ar.cause()?.message}")
                val (status, reScheduled) = ar.result()
                assertEquals(201, status, "已取消后同槽位必须可再建（部分索引谓词生效），实际: $reScheduled")
                assertEquals(1L, queryLong(
                    "SELECT count(*) FROM nursing.nursing_childcare_class_schedules " +
                        "WHERE class_name = '$CLASS_A' AND status <> '已取消'",
                ), "释放后未取消行仍为 1")
                assertEquals(2L, queryLong(
                    "SELECT count(*) FROM nursing.nursing_childcare_class_schedules WHERE class_name = '$CLASS_A'",
                ), "历史取消行保留（append 台账）")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 日程状态机
    // ========================================================================

    @Test
    fun `日程状态机_草稿到已发布到已取消_非法流转409`(vertx: Vertx, ctx: VertxTestContext) {
        // 客户端提交 status 必须被忽略：创建恒为草稿
        post(
            vertx,
            SCHEDULES,
            scheduleBody("手工活动").put("status", "已发布"),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "排课必须 201，实际: $created")
                assertEquals("草稿", created!!.getString("status"), "创建恒为草稿（客户端 status 被忽略）")
                assertEquals(TESTER, created.getString("operator"))
            }
            val id = created!!.getString("id")
            patch(vertx, "$SCHEDULES/$id/status", JsonObject().put("status", "已发布"))
                .map { Triple(id, it.first, it.second) }
        }.compose { (id, s2, published) ->
            ctx.verify {
                assertEquals(200, s2, "草稿→已发布 必须 200，实际: $published")
                assertEquals("已发布", published!!.getString("status"))
            }
            // 已发布 → 草稿（回退）非法
            patch(vertx, "$SCHEDULES/$id/status", JsonObject().put("status", "草稿")).map { Triple(id, it.first, it.second) }
        }.compose { (id, s3, illegalBack) ->
            ctx.verify {
                assertEquals(409, s3, "已发布→草稿 非法必须 409，实际: $illegalBack")
                assertTrue(illegalBack?.getString("error")?.contains("invalid status transition") == true)
            }
            patch(vertx, "$SCHEDULES/$id/status", JsonObject().put("status", "已取消")).map { Triple(id, it.first, it.second) }
        }.compose { (id, s4, cancelled) ->
            ctx.verify {
                assertEquals(200, s4, "已发布→已取消 必须 200，实际: $cancelled")
                assertEquals("已取消", cancelled!!.getString("status"))
            }
            // 终态再流转非法
            patch(vertx, "$SCHEDULES/$id/status", JsonObject().put("status", "已发布")).map { Triple(id, it.first, it.second) }
        }.compose { (id, s5, terminal) ->
            ctx.verify {
                assertEquals(409, s5, "终态再流转必须 409，实际: $terminal")
            }
            // 未知状态值 → 400
            patch(vertx, "$SCHEDULES/$id/status", JsonObject().put("status", "BOGUS"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "未知状态值必须 400，实际: $body")
                assertEquals(
                    "已取消",
                    queryString("SELECT status FROM nursing.nursing_childcare_class_schedules WHERE class_name = '$CLASS_A'"),
                    "状态必须真落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 巡查隐患闭环 + 跳步 409 且无 UPDATE
    // ========================================================================

    @Test
    fun `巡查状态机_跳步409无UPDATE与逐步落库`(vertx: Vertx, ctx: VertxTestContext) {
        var inspectionId = ""
        post(vertx, INSPECTIONS, inspectionBody()).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "巡查创建必须 201，实际: $created")
                assertEquals("待整改", created!!.getString("status"))
                assertEquals(0, created.getJsonArray("items").size(), "items 缺省落 []")
            }
            inspectionId = created!!.getString("id")
            // 跳步：待整改 → close 必须 409，且不得产生 UPDATE
            patch(vertx, "$INSPECTIONS/$inspectionId/close", JsonObject())
        }.compose { (s2, skipped) ->
            val before = queryString("SELECT updated_at::text FROM nursing.nursing_childcare_safety_inspections WHERE id = '$inspectionId'")
            ctx.verify {
                assertEquals(409, s2, "跳步（待整改→已关闭）必须 409，实际: $skipped")
                assertEquals(
                    before,
                    queryString("SELECT updated_at::text FROM nursing.nursing_childcare_safety_inspections WHERE id = '$inspectionId'"),
                    "跳步 409 不得产生 UPDATE（updated_at 不变）",
                )
                assertEquals(
                    "待整改",
                    queryString("SELECT status FROM nursing.nursing_childcare_safety_inspections WHERE id = '$inspectionId'"),
                )
            }
            post(
                vertx,
                "$INSPECTIONS/$inspectionId/items",
                JsonObject()
                    .put("item_code", "FLOOR_SLIP")
                    .put("result", "不合格")
                    .put("hazard", "活动室地面有积水"),
            )
        }.compose { (s3, rectified) ->
            ctx.verify {
                assertEquals(200, s3, "追加整改项必须 200，实际: $rectified")
                assertEquals("已整改", rectified!!.getString("status"), "待整改 →(items)→ 已整改")
                assertEquals(1, rectified.getJsonArray("items").size())
            }
            assertTrue(
                queryString("SELECT status FROM nursing.nursing_childcare_safety_inspections WHERE id = '$inspectionId'") == "已整改",
                "已整改必须落库",
            )
            patch(vertx, "$INSPECTIONS/$inspectionId/close", JsonObject())
        }.compose { (s4, closed) ->
            ctx.verify {
                assertEquals(200, s4, "已整改 → 已关闭 必须 200，实际: $closed")
                assertEquals("已关闭", closed!!.getString("status"))
            }
            // 终态：再追加 / 再关闭都 409
            post(vertx, "$INSPECTIONS/$inspectionId/items", JsonObject().put("item_code", "SANITATION").put("result", "合格"))
        }.compose { (s5, appendAfterClose) ->
            ctx.verify { assertEquals(409, s5, "已关闭后再追加必须 409，实际: $appendAfterClose") }
            patch(vertx, "$INSPECTIONS/$inspectionId/close", JsonObject())
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(409, status, "已关闭再关闭必须 409，实际: $body")
                assertEquals(
                    "已关闭",
                    queryString("SELECT status FROM nursing.nursing_childcare_safety_inspections WHERE id = '$inspectionId'"),
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) items JSONB 往返（hazard 缺省不丢键）
    // ========================================================================

    @Test
    fun `巡查items_JSONB往返_hazard缺省不丢键`(vertx: Vertx, ctx: VertxTestContext) {
        val items = JsonArray()
            .add(JsonObject().put("item_code", "FLOOR_SLIP").put("result", "不合格").put("hazard", "地面湿滑"))
            .add(JsonObject().put("item_code", "ELECTRIC_SOCKET").put("result", "合格"))

        post(vertx, INSPECTIONS, inspectionBody().put("items", items)).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "创建必须 201，实际: $created")
                val returned = created!!.getJsonArray("items")
                assertEquals(2, returned.size(), "items 必须原样回读")
                assertEquals("FLOOR_SLIP", returned.getJsonObject(0).getString("item_code"))
                assertEquals("不合格", returned.getJsonObject(0).getString("result"))
                assertEquals("地面湿滑", returned.getJsonObject(0).getString("hazard"))
                // hazard 缺省必须写 null 且**保留键**
                assertTrue(returned.getJsonObject(1).containsKey("hazard"), "hazard 缺省也必须保留键（不丢键）")
                assertNull(returned.getJsonObject(1).getValue("hazard"), "hazard 缺省值为 null")
            }
            get(vertx, "$INSPECTIONS?area=${enc(AREA_A)}")
        }.compose { (s2, listed) ->
            ctx.verify {
                assertEquals(200, s2, "列表必须 200，实际: $listed")
                assertEquals(1L, listed!!.getJsonObject("meta").getLong("total"))
                val returned = listed.getJsonArray("records").getJsonObject(0).getJsonArray("items")
                assertEquals(2, returned.size(), "列表回读 items 一致")
                assertTrue(returned.getJsonObject(1).containsKey("hazard"))
            }
            // DB 侧：jsonb 文本必须含 "hazard": null（键未丢）
            val dbText = queryString(
                "SELECT items::text FROM nursing.nursing_childcare_safety_inspections WHERE area = '$AREA_A'",
            )
            assertNotNull(dbText)
            assertTrue(
                dbText!!.contains("\"hazard\": null"),
                "JSONB 必须真落 hazard:null 键，实际: $dbText",
            )
            assertTrue(dbText.contains("地面湿滑"), "hazard 文本必须落库")
            // 非法 item_code / result → 400
            post(vertx, INSPECTIONS, inspectionBody().put("items", JsonArray().add(
                JsonObject().put("item_code", "NOT_IN_CATALOG").put("result", "合格"),
            )))
        }.compose { (s3, badCode) ->
            ctx.verify {
                assertEquals(400, s3, "未知 item_code 必须 400，实际: $badCode")
                assertTrue(badCode?.getString("error")?.contains("unknown item_code") == true)
            }
            post(vertx, INSPECTIONS, inspectionBody().put("items", JsonArray().add(
                JsonObject().put("item_code", "FLOOR_SLIP").put("result", "BOGUS"),
            )))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "非法 result 必须 400，实际: $body")
                assertEquals(
                    1L,
                    queryLong("SELECT count(*) FROM nursing.nursing_childcare_safety_inspections WHERE area = '$AREA_A'"),
                    "非法 items 不得落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  6) 业务时区当日筛选跨 UTC 日界（巡查）
    // ========================================================================

    @Test
    fun `巡查_业务时区当日筛选跨UTC日界`(vertx: Vertx, ctx: VertxTestContext) {
        // 2026-08-03T23:30+08:00 → UTC 08-03T15:30；2026-08-04T00:30+08:00 → UTC 08-03T16:30
        // 两者 UTC 日期同为 08-03，业务日必须分别为 08-03 / 08-04
        post(
            vertx,
            INSPECTIONS,
            inspectionBody().put("inspected_at", "2026-08-03T23:30:00+08:00"),
        ).compose { (s1, late) ->
            ctx.verify { assertEquals(201, s1, "创建必须 201，实际: $late") }
            post(
                vertx,
                INSPECTIONS,
                inspectionBody().put("inspected_at", "2026-08-04T00:30:00+08:00"),
            )
        }.compose { (s2, early) ->
            ctx.verify { assertEquals(201, s2, "创建必须 201，实际: $early") }
            get(vertx, "$INSPECTIONS?area=${enc(AREA_A)}&date=${enc("2026-08-03")}")
        }.compose { (s3, day3) ->
            ctx.verify {
                assertEquals(200, s3, "实际: $day3")
                assertEquals(1L, day3!!.getJsonObject("meta").getLong("total"), "08-03 业务日只有 1 条（UTC 切日会误并 2 条）")
            }
            get(vertx, "$INSPECTIONS?area=${enc(AREA_A)}&date=${enc("2026-08-04")}")
        }.compose { (s4, day4) ->
            ctx.verify {
                assertEquals(200, s4, "实际: $day4")
                assertEquals(1L, day4!!.getJsonObject("meta").getLong("total"), "00:30+08:00 必须归入 08-04 业务日")
            }
            get(vertx, "$INSPECTIONS?area=${enc(AREA_A)}&status=${enc("待整改")}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "状态过滤失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status)
                assertEquals(2L, body!!.getJsonObject("meta").getLong("total"), "status=待整改 命中 2 条")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  7) 空列表 + 非法参数
    // ========================================================================

    @Test
    fun `空列表形状与非法参数`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "$SCHEDULES?class_name=${enc("${PREFIX}missing-$RUN")}").compose { (s1, emptySchedules) ->
            ctx.verify {
                assertEquals(200, s1, "实际: $emptySchedules")
                assertEquals(0, emptySchedules!!.getJsonArray("records").size())
                assertEquals(0L, emptySchedules.getJsonObject("meta").getLong("total"))
            }
            get(vertx, "$INSPECTIONS?area=${enc("${PREFIX}missing-$RUN")}")
        }.compose { (s2, emptyInspections) ->
            ctx.verify {
                assertEquals(200, s2, "实际: $emptyInspections")
                assertEquals(0, emptyInspections!!.getJsonArray("records").size())
                assertEquals(0L, emptyInspections.getJsonObject("meta").getLong("total"))
            }
            post(vertx, SCHEDULES, JsonObject().put("class_name", CLASS_A).put("time_slot", SLOT).put("activity_name", "x"))
        }.compose { (s3, noDate) ->
            ctx.verify { assertEquals(400, s3, "缺 schedule_date 必须 400，实际: $noDate") }
            post(vertx, SCHEDULES, scheduleBody("x").put("time_slot", "03:00-04:00"))
        }.compose { (s4, badSlot) ->
            ctx.verify { assertEquals(400, s4, "非法 time_slot 必须 400，实际: $badSlot") }
            post(vertx, INSPECTIONS, JsonObject().put("items", JsonArray()))
        }.compose { (s5, noArea) ->
            ctx.verify { assertEquals(400, s5, "缺 area 必须 400，实际: $noArea") }
            patch(vertx, "$SCHEDULES/${PREFIX}missing-$RUN/status", JsonObject().put("status", "已发布"))
        }.compose { (s6, missingSchedule) ->
            ctx.verify { assertEquals(404, s6, "日程不存在必须 404，实际: $missingSchedule") }
            patch(vertx, "$INSPECTIONS/${PREFIX}missing-$RUN/close", JsonObject())
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(404, status, "巡查不存在必须 404，实际: $body")
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM nursing.nursing_childcare_class_schedules WHERE class_name LIKE '$PREFIX%'"),
                    "非法请求不得落库",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun scheduleBody(activityName: String): JsonObject = JsonObject()
        .put("class_name", CLASS_A)
        .put("schedule_date", SCHEDULE_DATE)
        .put("time_slot", SLOT)
        .put("activity_name", activityName)

    private fun inspectionBody(): JsonObject = JsonObject().put("area", AREA_A)

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

    private fun explain(sql: String): List<String> = jdbc { conn ->
        conn.createStatement().use { st ->
            st.execute("SET enable_seqscan = off")
            st.executeQuery(sql).use { rs ->
                val lines = mutableListOf<String>()
                while (rs.next()) lines.add(rs.getString(1))
                lines
            }
        }
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
        exec("DELETE FROM nursing.nursing_childcare_class_schedules WHERE class_name LIKE '$PREFIX%'")
        exec("DELETE FROM nursing.nursing_childcare_safety_inspections WHERE area LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "nursing.nursing_childcare_class_schedules" to
                "SELECT count(*) FROM nursing.nursing_childcare_class_schedules WHERE class_name LIKE '$PREFIX%'",
            "nursing.nursing_childcare_safety_inspections" to
                "SELECT count(*) FROM nursing.nursing_childcare_safety_inspections WHERE area LIKE '$PREFIX%'",
        )
        val leftovers = residuals.mapNotNull { (table, sql) ->
            val count = queryLong(sql)
            if (count == 0L) null else "$table=$count"
        }
        assertTrue(leftovers.isEmpty(), "fixture 残差必须为 0，实际残留: $leftovers")
    }
}
