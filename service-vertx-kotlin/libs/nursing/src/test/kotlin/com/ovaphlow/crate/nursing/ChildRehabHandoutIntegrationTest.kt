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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 054 B4（家庭训练指导手册分发台账）**真库集成测试**。
 *
 * 覆盖（054 §8.2 / task-24，dev 标注的 DB-free 盲区）：
 *  1. **签收单向**：两次 `PATCH .../acknowledge` → 第二次 **409**，且 `acknowledged_at` **只写一次**；
 *     并发重复签收由 `UPDATE ... WHERE acknowledged_at IS NULL` 的**行数判定**兜底。
 *  2. `TIMESTAMPTZ` 编解码：`delivered_at` 缺省当前时刻；显式**纯日期**按 Asia/Shanghai 当日 00:00；
 *     `acknowledged_at` NULL → JSON `null`；带秒的偏移时间回读一致
 *     （**注意**：`OffsetDateTime.toString()` 在秒为 0 时会省略秒，故断言一律 `OffsetDateTime.parse` 后比较）。
 *  3. `WHERE acknowledged_at IS NULL` **部分索引**生效（`EXPLAIN` + `enable_seqscan=off` 佐证）。
 *  4. **正文快照语义**：台账存分发时的正文快照，后续同模板分发/模板修订都不改变历史行。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 nursing 段 V400–V412 + inventories V200–V204）：
 * ```
 * cd service-vertx-kotlin && export PITCHFORK_DB_PASSWORD=<hidden>
 * ./gradlew :libs:nursing:test --rerun --tests '*ChildRehabHandoutIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 每次运行唯一后缀；测试前后自清并断言残差 0；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildRehabHandoutIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054b4-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD_A = "${PREFIX}a-$RUN"
        private val UNKNOWN_CHILD = "${PREFIX}missing-$RUN"

        private const val TESTER = "qa054b4-tester"
        private const val HANDOUTS = "/nursing/v1/child-rehab-handouts"
        private const val TEMPLATES = "/nursing/v1/child-rehab-handout-templates"

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
                "VALUES ('$CHILD_A', '054B4手册儿童', '女', '儿童', 'ACTIVE')",
        )
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V412 迁移 + 部分索引
    // ========================================================================

    @Test
    fun `V412迁移_待签收部分索引存在且EXPLAIN命中`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                1L,
                queryLong(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'nursing' " +
                        "AND indexname = 'idx_child_rehab_handouts_pending'",
                ),
                "V412 必须建出待签收部分索引",
            )
            val indexDef = queryString(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'nursing' " +
                    "AND indexname = 'idx_child_rehab_handouts_pending'",
            )
            assertNotNull(indexDef)
            assertTrue(
                indexDef!!.contains("acknowledged_at IS NULL"),
                "部分索引谓词必须是 acknowledged_at IS NULL，实际: $indexDef",
            )
        }
        // EXPLAIN 佐证该索引确实可用于「未签收」谓词（关掉 seqscan 排除小表退化选择）
        val plan = explain(
            "EXPLAIN SELECT id FROM nursing.nursing_child_rehab_handouts " +
                "WHERE patient_id = '$CHILD_A' AND acknowledged_at IS NULL",
        )
        ctx.verify {
            assertTrue(
                plan.any { it.contains("idx_child_rehab_handouts_pending") },
                "EXPLAIN 必须命中 idx_child_rehab_handouts_pending，实际计划: $plan",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 签收单向
    // ========================================================================

    @Test
    fun `签收单向_第二次409且acknowledged_at只写一次`(vertx: Vertx, ctx: VertxTestContext) {
        var handoutId = ""
        post(vertx, HANDOUTS, handoutBody("HOME_HEAD_CONTROL")).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "分发必须 201，实际: $created")
                assertEquals("未签收", created!!.getString("status"))
                assertEquals(null, created.getValue("acknowledged_at"), "未签收时 JSON 必须为 null")
                assertEquals(ChildRehabHandoutService.DEFAULT_CHANNEL, created.getString("channel"))
                assertNotNull(created.getString("delivered_at"), "delivered_at 缺省当前时刻")
                assertEquals(TESTER, created.getString("operator"))
            }
            handoutId = created!!.getString("id")
            patch(vertx, "$HANDOUTS/$handoutId/acknowledge", JsonObject())
        }.compose { (s2, acked) ->
            ctx.verify {
                assertEquals(200, s2, "首次签收必须 200，实际: $acked")
                assertEquals("已签收", acked!!.getString("status"))
                assertNotNull(acked.getString("acknowledged_at"))
                assertEquals(TESTER, acked.getString("acknowledged_by"), "签收人取认证主体（042）")
            }
            // 第二次签收 → 409
            patch(vertx, "$HANDOUTS/$handoutId/acknowledge", JsonObject())
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(409, status, "已签收再签必须 409，实际: $body")
                assertTrue(
                    body?.getString("error")?.contains("already acknowledged") == true,
                    "409 文案必须明确，实际: ${body?.getString("error")}",
                )
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_child_rehab_handouts " +
                            "WHERE id = '$handoutId' AND acknowledged_at IS NOT NULL",
                    ),
                    "acknowledged_at 必须已写且仅一行",
                )
                assertEquals(
                    "已签收",
                    queryString(
                        "SELECT CASE WHEN acknowledged_at IS NULL THEN '未签收' ELSE '已签收' END " +
                            "FROM nursing.nursing_child_rehab_handouts WHERE id = '$handoutId'",
                    ),
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `并发签收_条件更新兜底恰好一个成功`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, HANDOUTS, handoutBody("HOME_ROLL_OVER")).compose { (s1, created) ->
            ctx.verify { assertEquals(201, s1, "分发必须 201，实际: $created") }
            val id = created!!.getString("id")
            // 两个并发签收：条件更新（acknowledged_at IS NULL）保证只有一个能改到行
            val first = patch(vertx, "$HANDOUTS/$id/acknowledge", JsonObject()).map { it.first }
            val second = patch(vertx, "$HANDOUTS/$id/acknowledge", JsonObject()).map { it.first }
            Future.all(first, second).onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded(), "并发请求失败: ${ar.cause()?.message}")
                    val statuses = listOf(
                        ar.result().resultAt<Int>(0),
                        ar.result().resultAt<Int>(1),
                    ).sorted()
                    assertEquals(listOf(200, 409), statuses, "并发签收必须恰好一个成功、一个 409")
                    assertEquals(
                        1L,
                        queryLong(
                            "SELECT count(*) FROM nursing.nursing_child_rehab_handouts " +
                                "WHERE id = '$id' AND acknowledged_at IS NOT NULL",
                        ),
                        "并发下 acknowledged_at 仍只写一次",
                    )
                    ctx.completeNow()
                }
            }
        }
    }

    // ========================================================================
    //  3) TIMESTAMPTZ 编解码
    // ========================================================================

    @Test
    fun `TIMESTAMPTZ编解码_缺省当前时刻与纯日期按业务时区`(vertx: Vertx, ctx: VertxTestContext) {
        val before = OffsetDateTime.now()
        post(vertx, HANDOUTS, handoutBody("HOME_SIT")).compose { (s1, defaulted) ->
            ctx.verify {
                assertEquals(201, s1, "分发必须 201，实际: $defaulted")
                val delivered = OffsetDateTime.parse(defaulted!!.getString("delivered_at"))
                assertTrue(
                    !delivered.isBefore(before.minusMinutes(5)) && !delivered.isAfter(OffsetDateTime.now().plusMinutes(5)),
                    "缺省 delivered_at 必须为当前时刻，实际: $delivered",
                )
                assertEquals(null, defaulted.getValue("acknowledged_at"))
            }
            // 显式纯日期 → Asia/Shanghai 当日 00:00
            post(vertx, HANDOUTS, handoutBody("HOME_CRAWL").put("delivered_at", "2026-08-03"))
        }.compose { (s2, pureDate) ->
            ctx.verify {
                assertEquals(201, s2, "纯日期分发必须 201，实际: $pureDate")
                val expected = LocalDate.parse("2026-08-03").atStartOfDay(BUSINESS_ZONE).toOffsetDateTime()
                // TIMESTAMPTZ 回读会被 pgclient 归一到会话时区（通常 UTC），故比较**瞬时**而非 OffsetDateTime.equals
                assertEquals(
                    expected.toInstant(),
                    OffsetDateTime.parse(pureDate!!.getString("delivered_at")).toInstant(),
                    "纯日期必须按 Asia/Shanghai 当日 00:00（注意 toString 可能省略秒，必须 parse 后比较）",
                )
            }
            // 带秒偏移时间（回读可能省略秒 → parse 后比较）
            post(
                vertx,
                HANDOUTS,
                handoutBody("HOME_STAND_WALK").put("delivered_at", "2026-08-03T10:00:00+08:00"),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "带偏移时间分发失败: ${ar.cause()?.message}")
                val (status, offsetTime) = ar.result()
                assertEquals(201, status, "实际: $offsetTime")
                assertEquals(
                    OffsetDateTime.parse("2026-08-03T10:00:00+08:00").toInstant(),
                    OffsetDateTime.parse(offsetTime!!.getString("delivered_at")).toInstant(),
                    "带偏移 ISO-8601 必须原样回读（比较瞬时，时区归一是正常行为）",
                )
                assertEquals(
                    "2026-08-03 10:00:00+08",
                    queryString(
                        "SELECT to_char(delivered_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') || '+08' " +
                            "FROM nursing.nursing_child_rehab_handouts WHERE patient_id = '$CHILD_A' " +
                            "AND template_code = 'HOME_STAND_WALK'",
                    ),
                    "TIMESTAMPTZ 必须真落库",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法delivered_at_400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, HANDOUTS, handoutBody("HOME_SPEECH").put("delivered_at", "not-a-date"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                    val (status, body) = ar.result()
                    assertEquals(400, status, "非法 delivered_at 必须 400，实际: $body")
                    assertNotNull(body?.getString("error"))
                    assertEquals(
                        0L,
                        queryLong("SELECT count(*) FROM nursing.nursing_child_rehab_handouts WHERE patient_id = '$CHILD_A'"),
                        "非法请求不得落库",
                    )
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  4) 正文快照语义
    // ========================================================================

    @Test
    fun `正文快照语义_历史台账不随模板变化`(vertx: Vertx, ctx: VertxTestContext) {
        val template = ChildRehabHandoutService.templateOf("HOME_HEAD_CONTROL")!!
        val customContent = "个性化补充：本儿童每日 2 组，家长记录完成情况。"

        post(vertx, HANDOUTS, handoutBody("HOME_HEAD_CONTROL")).compose { (s1, defaulted) ->
            ctx.verify {
                assertEquals(201, s1, "默认正文分发必须 201，实际: $defaulted")
                assertEquals(template.content, defaulted!!.getString("content"), "缺省正文取模板常量")
                assertEquals(template.name, defaulted.getString("template_name"), "模板名快照")
            }
            val defaultId = defaulted!!.getString("id")
            post(vertx, HANDOUTS, handoutBody("HOME_HEAD_CONTROL").put("content", customContent))
                .map { Triple(defaultId, it.first, it.second) }
        }.compose { (defaultId, s2, custom) ->
            ctx.verify {
                assertEquals(201, s2, "个性化正文分发必须 201，实际: $custom")
                assertEquals(customContent, custom!!.getString("content"), "非空 content 覆盖模板正文（个性化补充）")
            }
            // 再分发一册不同正文，历史行不得被改写
            post(
                vertx,
                HANDOUTS,
                handoutBody("HOME_HEAD_CONTROL").put("content", "第二版正文（后续修订）"),
            ).map { Triple(defaultId, custom!!.getString("id"), it.first) }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "分发失败: ${ar.cause()?.message}")
                val (defaultId, customId, thirdStatus) = ar.result()
                assertEquals(201, thirdStatus)
                // 历史行正文快照必须原样保留
                assertEquals(
                    template.content,
                    queryString("SELECT content FROM nursing.nursing_child_rehab_handouts WHERE id = '$defaultId'"),
                    "模板正文快照不得随后续分发变化",
                )
                assertEquals(
                    customContent,
                    queryString("SELECT content FROM nursing.nursing_child_rehab_handouts WHERE id = '$customId'"),
                    "个性化正文快照不得被后续分发覆盖",
                )
                assertEquals(
                    3L,
                    queryLong(
                        "SELECT count(*) FROM nursing.nursing_child_rehab_handouts " +
                            "WHERE patient_id = '$CHILD_A' AND template_code = 'HOME_HEAD_CONTROL'",
                    ),
                    "同一模板可分发多册（台账 append-only）",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) 模板清单 / 空结果 / 非法参数
    // ========================================================================

    @Test
    fun `模板清单与空列表形状_非法参数`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, TEMPLATES).compose { (s1, templates) ->
            ctx.verify {
                assertEquals(200, s1, "模板清单必须 200，实际: $templates")
                assertEquals(
                    ChildRehabHandoutService.TEMPLATES.size.toLong(),
                    templates!!.getJsonObject("meta").getLong("total"),
                )
                assertEquals(ChildRehabHandoutService.TEMPLATES.size, templates.getJsonArray("records").size())
                val first = templates.getJsonArray("records").getJsonObject(0)
                assertNotNull(first.getString("template_code"))
                assertNotNull(first.getString("template_name"))
                assertNotNull(first.getString("content"))
            }
            get(vertx, "$HANDOUTS?patient_id=${enc(UNKNOWN_CHILD)}")
        }.compose { (s2, empty) ->
            ctx.verify {
                assertEquals(200, s2, "空列表必须 200，实际: $empty")
                assertEquals(0, empty!!.getJsonArray("records").size())
                assertEquals(0L, empty.getJsonObject("meta").getLong("total"))
            }
            post(vertx, HANDOUTS, handoutBody("NOT_A_TEMPLATE"))
        }.compose { (s3, badTemplate) ->
            ctx.verify {
                assertEquals(400, s3, "未知 template_code 必须 400，实际: $badTemplate")
                assertTrue(badTemplate?.getString("error")?.contains("unknown template_code") == true)
            }
            post(vertx, HANDOUTS, handoutBody("HOME_SIT").put("patient_id", UNKNOWN_CHILD))
        }.compose { (s4, missingPatient) ->
            ctx.verify { assertEquals(404, s4, "儿童不存在必须 404，实际: $missingPatient") }
            patch(vertx, "$HANDOUTS/${PREFIX}nonexistent-$RUN/acknowledge", JsonObject())
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(404, status, "台账不存在签收必须 404，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun handoutBody(templateCode: String): JsonObject = JsonObject()
        .put("patient_id", CHILD_A)
        .put("template_code", templateCode)

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

    /** 关闭 seqscan 后取 EXPLAIN 计划行（证明部分索引对该谓词可用）。 */
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
        exec("DELETE FROM nursing.nursing_child_rehab_handouts WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "nursing.nursing_child_rehab_handouts" to
                "SELECT count(*) FROM nursing.nursing_child_rehab_handouts WHERE patient_id LIKE '$PREFIX%'",
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
