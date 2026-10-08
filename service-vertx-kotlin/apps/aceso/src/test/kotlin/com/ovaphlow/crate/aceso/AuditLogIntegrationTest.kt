package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.healthcare.AuditLogService
import com.ovaphlow.crate.healthcare.HealthcareRoutes
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * 054 F3（集中式旁路审计）**真库集成测试** —— 覆盖 dev2 的 9 条盲区。
 *
 * ## 装配口径
 * 与 `Main.kt` **逐序等价**（不插 `BodyHandler`、不改顺序）：
 * ```
 * apiRouter.route().handler(BodyHandler)                     // 最顶层
 * apiRouter.route().handler(apiAuthenticationGate(...))      // → 测试用假认证（写入 userId/platformAdmin）
 * apiRouter.route().handler(auditMiddleware(AuditLogService(pool)))   // F3 审计（认证后、门禁前）
 * DECLARED_PERMISSIONS.filter{CENTRAL}.groupBy{method,path}.forEach{ requireAnyPermission(...) }  // 真实声明循环
 * apiRouter.route("/healthcare/v1/<resource>").subRouter(HealthcareRoutes.create(...))
 * root.route("/crate-api/<module>").subRouter(apiRouter)     // 挂载点 /crate-api
 * ```
 * 权限上游用**本地桩 Nexus**（可切换返回的 `permission_codes`），真实走
 * [SubjectPermissionClient] → [requireAnyPermission]，不做替身。
 *
 * ## 覆盖
 *  1. `V540` 真迁移：表 + **3 个索引** + 列类型（`status_code INTEGER`、`occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()`、`metadata JSONB`）。
 *  2. 写方法 POST/PUT/PATCH/DELETE 各落**恰好一条**且 method/path/status 与响应一致；**GET 零落库**。
 *  3. **门禁 403 尝试入账**（审计在门禁之前）：无权限 → 403 且落一条 `status_code=403`。
 *  4. **读路由门禁**：`GET /healthcare/v1/audit-logs` 无 `access:manage` → 403；有码 → 2xx；`count(method='GET')==1`。
 *  5. **不落请求体（PHI 护栏）**：写请求体中的敏感值不得出现在该行任何列文本中。
 *  6. 过滤真库语义：`path` 前缀 LIKE、`from`/`to` 闭区间、`actor`/`method` 精确；空结果形状。
 *  7. 落库失败不阻断：**未覆盖 + 理由**（见 [未覆盖说明]）。
 *  8. 残差 0：按测试专用 `actor` 前缀清理与过滤。
 *  9. 异步时序：落库在 `endHandler`，断言前**轮询等待 ≤5s**。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，app 类路径应用全量迁移）：
 * ```
 * cd service-vertx-kotlin && set -a && . apps/aceso/.env && set +a
 * ./gradlew :apps:aceso:test --rerun --tests '*AuditLogIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogIntegrationTest {

    /**
     * [未覆盖说明] 盲区 7「落库失败不阻断」未覆盖，理由：审计落库失败需要让
     * `AuditLogService.record` 的 INSERT 失败（如库不可达 / 表被 DROP），而在本测试进程内
     * 复用同一 `Pool` 时无法在不破坏同批断言的前提下稳定注入；`AuditMiddleware` 的
     * `onFailure { log.warn }` 旁路语义已由 dev 的 DB-free 单测 `AuditMiddlewareTest` 覆盖
     * （替身 recorder 返回失败 future）。故本条**标注未覆盖**，不计入本次真库通过项。
     */
    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054f3-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private const val TESTER = "$PREFIX" + "tester"
        private const val NOBODY = "$PREFIX" + "nobody"
        private const val COOKIE = "qa054f3-session"
        private const val ACCESS_MANAGE = "access:manage"
        private const val MOUNT = "/crate-api"
        private const val AUDIT_LOGS = "$MOUNT/healthcare/v1/audit-logs"
        private const val PACKAGES = "$MOUNT/healthcare/v1/child-service-packages"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var client: HttpClient
    private var server: io.vertx.core.http.HttpServer? = null
    private var nexus: io.vertx.core.http.HttpServer? = null
    private var serverPort: Int = 0
    private var nexusPort: Int = 0

    /** 桩 Nexus 返回的权限码（可切换）。 */
    private var grantedCodes: JsonArray = JsonArray()
    private var currentSubject: String = TESTER

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

            // 桩 Nexus：GET /crate-api/shared/v1/subject-permissions → {"permission_codes":[...]}
            val nexusRouter = Router.router(vertx)
            nexusRouter.route().handler { rctx ->
                rctx.response().putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("permission_codes", grantedCodes).encode())
            }
            vertx.createHttpServer().requestHandler(nexusRouter).listen(0).compose { ns ->
                nexus = ns
                nexusPort = ns.actualPort()

                val permissionClient = SubjectPermissionClient(vertx, "http://127.0.0.1:$nexusPort", 2_000)

                // ── 与 Main.kt 逐序等价的 apiRouter ──────────────────────────────
                val apiRouter = Router.router(vertx)
                apiRouter.route().handler(BodyHandler.create())
                apiRouter.route().handler { rctx ->
                    // 生产为 apiAuthenticationGate(sessionAuth)：此处假认证，写入 userId/platformAdmin
                    rctx.put("userId", currentSubject)
                    rctx.put("platformAdmin", false)
                    rctx.next()
                }
                // F3 审计：认证后、门禁前（护栏：不插 BodyHandler、不改顺序）
                apiRouter.route().handler(auditMiddleware(AuditLogService(pool)))
                // 046 D2 真实声明驱动门禁循环（与 Main.kt 同一份 DECLARED_PERMISSIONS）
                DECLARED_PERMISSIONS
                    .filter { it.wiring == PermissionWiring.CENTRAL }
                    .groupBy { it.method to it.path }
                    .forEach { (key, declarations) ->
                        apiRouter.route(HttpMethod.valueOf(key.first), key.second)
                            .handler(requireAnyPermission(permissionClient, declarations.map { it.permissionCode }))
                    }
                apiRouter.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))

                val root = Router.router(vertx)
                root.route("$MOUNT/*").subRouter(apiRouter)

                vertx.createHttpServer().requestHandler(root).listen(0)
            }.onComplete { ar ->
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
        server?.close()
        nexus?.close()
        ctx.completeNow()
    }

    @BeforeEach
    fun setupFixtures() {
        cleanupFixtures()
        grantedCodes = JsonArray().add(ACCESS_MANAGE).add("billing:write").add("base_data:manage")
        currentSubject = TESTER
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V540 迁移
    // ========================================================================

    @Test
    fun `V540迁移_审计表三索引与列类型生效`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                "healthcare.audit_logs",
                queryString("SELECT to_regclass('healthcare.audit_logs')::text"),
            )
            listOf(
                "idx_audit_logs_occurred",
                "idx_audit_logs_actor_occurred",
                "idx_audit_logs_method_path_occurred",
            ).forEach { index ->
                assertTrue(
                    queryLong("SELECT count(*) FROM pg_indexes WHERE indexname = '$index'") == 1L,
                    "缺索引: $index",
                )
            }
            val occurredDef = queryString(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_audit_logs_occurred'",
            )
            assertTrue(
                occurredDef!!.contains("occurred_at") && occurredDef.contains("DESC"),
                "occurred_at 索引必须为 DESC，实际: $occurredDef",
            )
            val actorDef = queryString(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_audit_logs_actor_occurred'",
            )
            assertTrue(actorDef!!.contains("actor") && actorDef.contains("occurred_at"), "实际: $actorDef")
            val methodDef = queryString(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_audit_logs_method_path_occurred'",
            )
            assertTrue(
                methodDef!!.contains("method") && methodDef.contains("path") && methodDef.contains("occurred_at"),
                "实际: $methodDef",
            )
            // 列类型
            assertEquals("integer", queryString(colType("status_code")), "status_code 必须 INTEGER")
            assertEquals("timestamp with time zone", queryString(colType("occurred_at")), "occurred_at 必须 TIMESTAMPTZ")
            assertEquals("jsonb", queryString(colType("metadata")), "metadata 必须 JSONB")
            assertEquals("NO", queryString(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema='healthcare' " +
                    "AND table_name='audit_logs' AND column_name='occurred_at'",
            ), "occurred_at 必须 NOT NULL")
            assertTrue(
                queryString(
                    "SELECT column_default FROM information_schema.columns WHERE table_schema='healthcare' " +
                        "AND table_name='audit_logs' AND column_name='occurred_at'",
                )!!.contains("now()"),
                "occurred_at 缺省必须 now()",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  1b) 声明表口径：全系统被声明的 GET 路由恰 1 条（与「审计表 GET 行=0」是两件事）
    // ========================================================================

    @Test
    fun `声明表GET路由恰一条且为审计日志读路由`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            val declaredGets = DECLARED_PERMISSIONS.filter { it.method == "GET" }
            assertEquals(
                1,
                declaredGets.size,
                "全系统**被声明**的 GET 路由恰 1 条，实际: ${declaredGets.map { it.path }}",
            )
            assertEquals(
                AUDIT_LOGS.removePrefix(MOUNT),
                declaredGets.first().path,
                "唯一受门禁的读路由必须是审计日志查询",
            )
            assertEquals(ACCESS_MANAGE, declaredGets.first().permissionCode)
        }
        // 与之对照：审计表里 GET 行必须为 0（中间件只审计写方法）
        ctx.verify {
            assertEquals(
                0L,
                queryLong("SELECT count(*) FROM healthcare.audit_logs WHERE method = 'GET'"),
                "审计表 GET 行必须为 0",
            )
        }
        ctx.completeNow()
    }

    // ========================================================================
    //  2) 写方法各落一条；GET 零落库
    // ========================================================================

    @Test
    fun `写方法各落一条且GET零落库`(vertx: Vertx, ctx: VertxTestContext) {
        val code = "$PREFIX" + "pkg-$RUN"
        var packageId = ""
        post(
            vertx,
            PACKAGES,
            JsonObject().put("code", code).put("name", "054F3 套餐").put("price", 1.0)
                .put("items", serviceItems()),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "POST 必须 201，实际: $created")
                packageId = created!!.getString("id")
            }
            put(
                vertx,
                "$PACKAGES/$packageId",
                JsonObject().put("code", code).put("name", "054F3 套餐改名").put("price", 2.0)
                    .put("items", serviceItems()),
            )
        }.compose { (s2, _) ->
            ctx.verify { assertEquals(200, s2, "PUT 必须 200") }
            patch(vertx, "$MOUNT/healthcare/v1/invoices/${PREFIX}missing/status", JsonObject().put("status", "已作废"))
        }.compose { (s3, _) ->
            ctx.verify { assertEquals(404, s3, "PATCH 不存在的发票必须 404（仍应入账）") }
            send(HttpMethod.DELETE, "$MOUNT/healthcare/v1/child-service-packages/${PREFIX}missing", null)
        }.compose { (s4, _) ->
            // 该路径无 DELETE 路由 → Vert.x 返回 405（Method Not Allowed）；无论状态码，写方法都必须入账
            ctx.verify { assertEquals(405, s4, "DELETE 无匹配路由必须 405（仍应入账）") }
            // GET 必须零落库
            get(vertx, "$MOUNT/healthcare/v1/child-service-packages?limit=1")
        }.compose { (s5, _) ->
            ctx.verify { assertEquals(200, s5, "GET 必须 200") }
            pollUntil(vertx, 5_000) {
                queryLong(
                    "SELECT count(*) FROM healthcare.audit_logs WHERE actor LIKE '$PREFIX%' AND method <> 'GET'",
                ) == 4L
            }
        }.compose {
            ctx.verify {
                listOf("POST", "PUT", "PATCH", "DELETE").forEach { method ->
                    assertEquals(
                        1L,
                        queryLong(
                            "SELECT count(*) FROM healthcare.audit_logs WHERE actor = '$TESTER' AND method = '$method'",
                        ),
                        "$method 必须恰好落一条",
                    )
                }
                val postRow = queryString(
                    "SELECT path || '|' || status_code FROM healthcare.audit_logs " +
                        "WHERE actor = '$TESTER' AND method = 'POST'",
                )
                assertEquals("$PACKAGES|201", postRow, "POST 行 path 必须为挂载点+规范化路径，status 与响应一致")
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM healthcare.audit_logs WHERE method = 'GET' AND actor LIKE '$PREFIX%'"),
                    "GET 必须零落库",
                )
                assertEquals(
                    "false",
                    queryString(
                        "SELECT metadata->>'platform_admin' FROM healthcare.audit_logs " +
                            "WHERE actor = '$TESTER' AND method = 'POST'",
                    ),
                    "metadata 必须记录 platform_admin",
                )
            }
            Future.succeededFuture<Unit>(Unit)
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "链路失败: ${ar.cause()?.message}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) 门禁 403 尝试入账
    // ========================================================================

    @Test
    fun `门禁403尝试入账`(vertx: Vertx, ctx: VertxTestContext) {
        // 无 billing:write → 门禁 403；审计在门禁之前，故必须入账
        grantedCodes = JsonArray().add(ACCESS_MANAGE)
        currentSubject = NOBODY
        post(
            vertx,
            PACKAGES,
            JsonObject().put("code", "$PREFIX" + "denied-$RUN").put("name", "x").put("price", 1.0)
                .put("items", serviceItems()),
        ).compose { (status, body) ->
            ctx.verify {
                assertEquals(403, status, "缺权限必须 403，实际: $body")
                assertEquals("forbidden", body!!.getString("error"))
                assertTrue(
                    body.getString("required_permission")?.contains("billing:write") == true,
                    "403 必须带 required_permission，实际: $body",
                )
            }
            pollUntil(vertx, 5_000) {
                queryLong("SELECT count(*) FROM healthcare.audit_logs WHERE actor = '$NOBODY' AND status_code = 403") == 1L
            }
        }.compose {
            ctx.verify {
                val row = queryString(
                    "SELECT method || '|' || status_code || '|' || actor FROM healthcare.audit_logs " +
                        "WHERE actor = '$NOBODY'",
                )
                assertEquals("POST|403|$NOBODY", row, "门禁 403 尝试必须入账（证明中间件在门禁之前）")
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM healthcare.child_service_packages WHERE code LIKE '$PREFIX%'"),
                    "403 不得产生业务副作用",
                )
            }
            Future.succeededFuture<Unit>(Unit)
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "链路失败: ${ar.cause()?.message}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) 读路由门禁
    // ========================================================================

    @Test
    fun `读路由门禁_无码403有码2xx且GET零入账`(vertx: Vertx, ctx: VertxTestContext) {
        // 注意：`SubjectPermissionClient` 按 subjectId 缓存（TTL 60s），故「无码」与「有码」必须用
        // **不同主体**，否则第二次会命中第一次的缓存。
        grantedCodes = JsonArray()          // 无任何权限码
        currentSubject = NOBODY
        get(vertx, AUDIT_LOGS).compose { (s1, denied) ->
            ctx.verify {
                assertEquals(403, s1, "无 access:manage 必须 403，实际: $denied")
                assertEquals("forbidden", denied!!.getString("error"))
                assertEquals(ACCESS_MANAGE, denied.getString("required_permission"))
            }
            grantedCodes = JsonArray().add(ACCESS_MANAGE)
            currentSubject = TESTER
            get(vertx, AUDIT_LOGS)
        }.compose { (s2, allowed) ->
            ctx.verify {
                assertEquals(200, s2, "有 access:manage 必须 2xx，实际: $allowed")
                assertTrue(allowed!!.getJsonArray("records") != null, "列表形状必须为 {records,meta}")
                assertTrue(allowed.getJsonObject("meta") != null, "列表形状必须含 meta.total")
            }
            Future.succeededFuture<Unit>(Unit)
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "链路失败: ${ar.cause()?.message}")
                // 审计只记写方法：GET（含受门禁的读路由）**零入账**
                assertEquals(
                    0L,
                    queryLong("SELECT count(*) FROM healthcare.audit_logs WHERE method = 'GET'"),
                    "GET 不得入账（含唯一受门禁读路由的两次 GET：403 与 2xx）",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) 不落请求体（PHI 护栏）
    // ========================================================================

    @Test
    fun `不落请求体_PHI护栏`(vertx: Vertx, ctx: VertxTestContext) {
        val secretName = "054F3敏感姓名"
        val secretIdCard = "110101199001011234"
        post(
            vertx,
            PACKAGES,
            JsonObject().put("code", "$PREFIX" + "phi-$RUN").put("name", secretName).put("price", 3.0)
                .put("items", JsonArray().add(
                    JsonObject().put("item_code", "SVC-1").put("item_name", secretIdCard).put("quantity", 1),
                )),
        ).compose { (status, body) ->
            ctx.verify { assertEquals(201, status, "写入必须 201，实际: $body") }
            pollUntil(vertx, 5_000) {
                queryLong("SELECT count(*) FROM healthcare.audit_logs WHERE actor = '$TESTER' AND method = 'POST'") == 1L
            }
        }.compose {
            val rowText = queryString(
                "SELECT coalesce(id,'') || coalesce(actor,'') || method || path || status_code::text || " +
                    "coalesce(resource_id,'') || coalesce(metadata::text,'') " +
                    "FROM healthcare.audit_logs WHERE actor = '$TESTER' AND method = 'POST'",
            )
            ctx.verify {
                assertTrue(rowText != null, "必须已入账")
                assertFalse(rowText!!.contains(secretName), "审计行任何列不得含请求体中的姓名，实际: $rowText")
                assertFalse(rowText.contains(secretIdCard), "审计行任何列不得含请求体中的证件号，实际: $rowText")
            }
            Future.succeededFuture<Unit>(Unit)
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "链路失败: ${ar.cause()?.message}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  6) 过滤真库语义
    // ========================================================================

    @Test
    fun `过滤真库语义_前缀闭区间精确与空结果`(vertx: Vertx, ctx: VertxTestContext) {
        // 造两条不同 actor 的审计行（直接落库，避免依赖门禁路径）
        insertAudit("$PREFIX" + "a1", "POST", "$MOUNT/healthcare/v1/base-vaccination-types", 201)
        insertAudit("$PREFIX" + "a2", "GET", "$MOUNT/healthcare/v1/base-developmental-scales", 200)
        val iso = "YYYY-MM-DD\"T\"HH24:MI:SS.USOF"
        val from = queryString(
            "SELECT to_char(min(occurred_at), '$iso') FROM healthcare.audit_logs WHERE actor LIKE '$PREFIX%'",
        )!!
        val to = queryString(
            "SELECT to_char(max(occurred_at), '$iso') FROM healthcare.audit_logs WHERE actor LIKE '$PREFIX%'",
        )!!

        get(
            vertx,
            "$AUDIT_LOGS?path=${enc("$MOUNT/healthcare/v1/base-vaccination")}&limit=50",
        ).compose { (s1, byPrefix) ->
            ctx.verify {
                assertEquals(200, s1, "path 前缀过滤必须 200，实际: $byPrefix")
                assertEquals(1L, byPrefix!!.getJsonObject("meta").getLong("total"), "path LIKE 前缀必须只命中 1 条")
            }
            get(vertx, "$AUDIT_LOGS?actor=${enc("$PREFIX" + "a2")}&method=GET&limit=50")
        }.compose { (s2, byExact) ->
            ctx.verify {
                assertEquals(200, s2, "actor+method 精确过滤必须 200，实际: $byExact")
                assertEquals(1L, byExact!!.getJsonObject("meta").getLong("total"))
                assertEquals("$PREFIX" + "a2", byExact.getJsonArray("records").getJsonObject(0).getString("actor"))
            }
            get(vertx, "$AUDIT_LOGS?from=${enc(from)}&to=${enc(to)}&limit=50")
        }.compose { (s3, byRange) ->
            ctx.verify {
                assertEquals(200, s3, "闭区间过滤必须 200，实际: $byRange")
                assertTrue(
                    byRange!!.getJsonObject("meta").getLong("total") >= 2L,
                    "闭区间 [from,to] 必须包含边界（至少命中 2 条）",
                )
            }
            get(vertx, "$AUDIT_LOGS?actor=${enc("$PREFIX" + "no-such-actor")}")
        }.compose { (s4, empty) ->
            ctx.verify {
                assertEquals(200, s4, "空结果必须 200，实际: $empty")
                assertEquals(0, empty!!.getJsonArray("records").size())
                assertEquals(0L, empty.getJsonObject("meta").getLong("total"))
            }
            Future.succeededFuture<Unit>(Unit)
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "链路失败: ${ar.cause()?.message}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 套餐 `items` 合法最小数组（服务端要求非空且含 item_name）。 */
    private fun serviceItems(): JsonArray = JsonArray().add(
        JsonObject().put("item_code", "SVC-1").put("item_name", "服务项").put("quantity", 1),
    )

    private fun colType(column: String): String =
        "SELECT data_type FROM information_schema.columns WHERE table_schema='healthcare' " +
            "AND table_name='audit_logs' AND column_name='$column'"

    private fun insertAudit(actor: String, method: String, path: String, statusCode: Int) {
        exec(
            "INSERT INTO healthcare.audit_logs (id, actor, method, path, status_code, occurred_at) " +
                "VALUES ('$PREFIX${UUID.randomUUID().toString().replace("-", "").take(20)}', " +
                "'$actor', '$method', '$path', $statusCode, now())",
        )
    }

    /** 异步轮询（≤[timeoutMs]）：审计落库发生在 `endHandler`，不能立即断言。 */
    private fun pollUntil(vertx: Vertx, timeoutMs: Long, check: () -> Boolean): Future<Unit> {
        val promise = Promise.promise<Unit>()
        val deadline = System.currentTimeMillis() + timeoutMs
        fun tick() {
            if (check()) {
                promise.complete()
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                promise.fail(AssertionError("审计落库轮询超时（≤${timeoutMs}ms）"))
                return
            }
            vertx.setTimer(100) { tick() }
        }
        tick()
        return promise.future()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject?,
    ): Future<Pair<Int, JsonObject?>> =
        client.request(method, serverPort, "127.0.0.1", path).compose { req ->
            req.putHeader("Cookie", COOKIE)
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

    private fun get(vertx: Vertx, path: String): Future<Pair<Int, JsonObject?>> = send(HttpMethod.GET, path, null)

    private fun post(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.POST, path, body)

    private fun put(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.PUT, path, body)

    private fun patch(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.PATCH, path, body)

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

    /** 残差清理：按测试专用 `actor` 前缀 + 测试 `path`/`code` 前缀（中间件会记录测试自身的写请求）。 */
    private fun cleanupFixtures() {
        exec("DELETE FROM healthcare.audit_logs WHERE actor LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.child_service_packages WHERE code LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "healthcare.audit_logs" to
                "SELECT count(*) FROM healthcare.audit_logs WHERE actor LIKE '$PREFIX%'",
            "healthcare.child_service_packages" to
                "SELECT count(*) FROM healthcare.child_service_packages WHERE code LIKE '$PREFIX%'",
        )
        val leftovers = residuals.mapNotNull { (table, sql) ->
            val count = queryLong(sql)
            if (count == 0L) null else "$table=$count"
        }
        assertTrue(leftovers.isEmpty(), "fixture 残差必须为 0，实际残留: $leftovers")
    }
}
