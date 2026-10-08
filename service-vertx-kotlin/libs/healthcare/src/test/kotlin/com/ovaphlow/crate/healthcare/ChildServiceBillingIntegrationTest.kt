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
 * 054 E 链（收银：套餐/订阅 E1、结算收款 E2、发票 E3）**真库集成测试**。
 *
 * 覆盖（054 §8.5 / E 链窗口）：
 *  1. `V536–V538` 真迁移：六张表、唯一约束、CHECK 与索引；**`bills.encounter_id` 仍 NOT NULL**（养老账期链未被动）。
 *  2. E1 付费状态**四态读时派生**（未付/部分/已付/已用完），含 E1↔E2 的 `used_amount`/`paid_amount` 联动。
 *  3. E2 结算单**金额 = 明细之和**（单价×数量 `ROUND_HALF_UP` 到分）；**超额收款 400 且不写流水、不更新头**。
 *  4. E3 发票**票号唯一 409**、**状态单向 已开具→已作废**（反向 409）、可关联结算单（金额服务端派生）。
 *  5. **`bills` / `bill_items` / `payments` / `settlements` 行数不变**（E 链独立于养老账期链的回归断言）。
 *  6. 空列表形状 `{records:[],meta:{total:0}}`；残差 0。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，healthcare 段迁移 V500–V539 + nursing/inventories 依赖段）：
 * ```
 * cd service-vertx-kotlin && set -a && . apps/aceso/.env && set +a
 * ./gradlew :libs:healthcare:test --rerun --tests '*ChildServiceBillingIntegrationTest' \
 *   -Dintegration.db.host=127.0.0.1 -Dintegration.db.port=55432 \
 *   -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 * fixture：固定前缀 [PREFIX] + 唯一后缀；测试前后自清并断言残差 0；进程内假认证，无 token。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildServiceBillingIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054e-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val CHILD = "${PREFIX}c1-$RUN"
        private val CHILD_2 = "${PREFIX}c2-$RUN"
        private val ADULT = "${PREFIX}adult-$RUN"
        private val UNKNOWN = "${PREFIX}missing-$RUN"

        private const val TESTER = "qa054e-tester"
        private const val PACKAGES = "/healthcare/v1/child-service-packages"
        private const val SUBSCRIPTIONS = "/healthcare/v1/child-package-subscriptions"
        private const val SETTLEMENTS = "/healthcare/v1/child-service-settlements"
        private const val INVOICES = "/healthcare/v1/invoices"
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
        insertPatient(CHILD, "054E儿童甲", "儿童")
        insertPatient(CHILD_2, "054E儿童乙", "儿童")
        insertPatient(ADULT, "054E成人", "居民")
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  1) V536–V538 迁移 + bills 未被动
    // ========================================================================

    @Test
    fun `V536_V538迁移_六表约束索引生效且bills未被动`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            listOf(
                "healthcare.child_service_packages",
                "healthcare.child_package_subscriptions",
                "healthcare.child_service_settlements",
                "healthcare.child_service_settlement_items",
                "healthcare.child_service_settlement_payments",
                "healthcare.child_service_invoices",
            ).forEach { table ->
                assertEquals(table, queryString("SELECT to_regclass('$table')::text"), "缺表: $table")
            }
            listOf(
                "uq_child_service_packages_code",
                "uq_child_service_settlements_no",
                "uq_child_service_invoices_no",
            ).forEach { index ->
                assertEquals(
                    1L,
                    queryLong("SELECT count(*) FROM pg_indexes WHERE indexname = '$index'"),
                    "缺唯一索引: $index",
                )
            }
            // 养老账期链回归：bills.encounter_id 仍为 NOT NULL（E 链不得放宽）
            assertEquals(
                "NO",
                queryString(
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'healthcare' " +
                        "AND table_name = 'bills' AND column_name = 'encounter_id'",
                ),
                "E 链不得放宽 bills.encounter_id NOT NULL",
            )
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'healthcare' " +
                        "AND table_name = 'child_package_subscriptions' AND column_name = 'pay_state'",
                ),
                "pay_state 必须是读时派生、不得落库",
            )
        }
        // CHECK 真生效：负价套餐 / items 非数组
        assertTrue(
            runCatching {
                exec(
                    "INSERT INTO healthcare.child_service_packages (id, code, name, price, items, status) " +
                        "VALUES ('${PREFIX}bad-$RUN', '${PREFIX}bad', 'x', -1, '[]'::jsonb, '启用')",
                )
            }.isFailure,
            "负价套餐必须被 CHECK 拒绝",
        )
        ctx.completeNow()
    }

    // ========================================================================
    //  2) E1 套餐/订阅 + 付费状态四态（含 E1↔E2 联动）
    // ========================================================================

    @Test
    fun `E1付费状态四态派生与E2联动`(vertx: Vertx, ctx: VertxTestContext) {
        var subscriptionId = ""
        createPackage(vertx, ctx, price = 100.0, suffix = "p100").compose { pkg ->
            createSubscription(vertx, ctx, CHILD, pkg)
        }.compose { subscription ->
            ctx.verify {
                assertEquals(100.0, subscription.getDouble("amount") ?: -1.0, 0.001, "订阅 amount 必须是套餐价快照")
                assertEquals(0.0, subscription.getDouble("paid_amount") ?: -1.0, 0.001)
                assertEquals(0.0, subscription.getDouble("used_amount") ?: -1.0, 0.001)
                assertEquals("未付", subscription.getString("pay_state"), "初始未付")
            }
            subscriptionId = subscription.getString("id")
            // 结算核销 40 → used_amount 40（仍 < 100）→ 仍「未付」
            createSettlement(vertx, ctx, CHILD, subscriptionId, listOf(Triple("ITEM-A", 40.0, 1.0)))
        }.compose { settlement ->
            ctx.verify {
                assertEquals(40.0, settlement.getDouble("total_amount") ?: -1.0, 0.001)
                assertEquals("待收款", settlement.getString("status"))
            }
            val settlementId = settlement.getString("id")
            get(vertx, "$SUBSCRIPTIONS?patient_id=${enc(CHILD)}").compose { (s, list) ->
                ctx.verify {
                    assertEquals(200, s)
                    assertEquals("未付", list!!.getJsonArray("records").getJsonObject(0).getString("pay_state"), "仅核销不收款 → 未付")
                }
                pay(vertx, settlementId, 30.0)
            }
        }.compose { (s2, paid30) ->
            ctx.verify {
                assertEquals(201, s2, "收款必须 201，实际: $paid30")
                assertEquals(30.0, paid30!!.getDouble("paid_amount") ?: -1.0, 0.001)
                assertEquals("部分收款", paid30.getString("status"))
            }
            get(vertx, "$SUBSCRIPTIONS?patient_id=${enc(CHILD)}")
        }.compose { (s3, list) ->
            ctx.verify {
                assertEquals(200, s3)
                val sub = list!!.getJsonArray("records").getJsonObject(0)
                assertEquals(30.0, sub.getDouble("paid_amount") ?: -1.0, 0.001, "收款必须累加订阅 paid_amount")
                assertEquals("部分", sub.getString("pay_state"), "paid 30 / amount 100 → 部分")
            }
            val settlementId = queryString(
                "SELECT id FROM healthcare.child_service_settlements WHERE patient_id = '$CHILD' ORDER BY created_at LIMIT 1",
            )!!
            pay(vertx, settlementId, 10.0).map { Triple(settlementId, it.first, it.second) }
        }.compose { (settlementId, s4, settled) ->
            ctx.verify {
                assertEquals(201, s4, "补足收款必须 201，实际: $settled")
                assertEquals("已结清", settled!!.getString("status"))
            }
            // 再核销 60（同订阅）→ used_amount 100 → 已用完
            createSettlement(vertx, ctx, CHILD, subscriptionId, listOf(Triple("ITEM-B", 60.0, 1.0)))
        }.compose { second ->
            ctx.verify {
                assertEquals(60.0, second.getDouble("total_amount") ?: -1.0, 0.001)
            }
            assertEquals(
                "100.00/40.00",
                queryString(
                    "SELECT used_amount::text || '/' || paid_amount::text FROM healthcare.child_package_subscriptions " +
                        "WHERE patient_id = '$CHILD'",
                ),
                "used 累计 100、paid 累计 40（NUMERIC(12,2)）",
            )
            get(vertx, "$SUBSCRIPTIONS?patient_id=${enc(CHILD)}")
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "订阅查询失败: ${ar.cause()?.message}")
                val (status, list) = ar.result()
                assertEquals(200, status)
                assertEquals(
                    "已用完",
                    list!!.getJsonArray("records").getJsonObject(0).getString("pay_state"),
                    "used_amount >= amount → 已用完（优先级高于已付/部分）",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `E1免费套餐视为已付_订阅状态机非法流转400`(vertx: Vertx, ctx: VertxTestContext) {
        createPackage(vertx, ctx, price = 0.0, suffix = "free").compose { pkg ->
            createSubscription(vertx, ctx, CHILD, pkg)
        }.compose { subscription ->
            ctx.verify {
                assertEquals("已付", subscription.getString("pay_state"), "amount=0 免费套餐视为已付")
            }
            val id = subscription.getString("id")
            patch(vertx, "$SUBSCRIPTIONS/$id/status", JsonObject().put("status", "停用"))
        }.compose { (s2, disabled) ->
            val disabledId = disabled!!.getString("id")
            ctx.verify {
                assertEquals(200, s2, "启用→停用 必须 200，实际: $disabled")
                assertEquals("停用", disabled.getString("status"))
            }
            // 同状态重复提交 → 非法流转 400
            patch(vertx, "$SUBSCRIPTIONS/$disabledId/status", JsonObject().put("status", "停用"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "同状态重复提交必须 400，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  3) E2 金额取整与合计 + 超额收款零副作用
    // ========================================================================

    @Test
    fun `E2结算单_明细取整与合计`(vertx: Vertx, ctx: VertxTestContext) {
        // 10.01 × 1.005 = 10.06005 → HALF_UP 到分 = 10.06；0.01 × 3 = 0.03；合计 10.09
        createSettlement(
            vertx,
            ctx,
            CHILD,
            null,
            listOf(Triple("ITEM-X", 10.01, 1.005), Triple("ITEM-Y", 0.01, 3.0)),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "建单失败: ${ar.cause()?.message}")
                val settlement = ar.result()
                assertEquals(10.09, settlement.getDouble("total_amount") ?: -1.0, 0.0001, "合计 = 明细之和")
                val items = settlement.getJsonArray("items")
                assertEquals(2, items.size())
                assertEquals(10.06, items.getJsonObject(0).getDouble("amount") ?: -1.0, 0.0001, "10.01×1.005 ROUND_HALF_UP")
                assertEquals(0.03, items.getJsonObject(1).getDouble("amount") ?: -1.0, 0.0001)
                assertEquals(
                    "10.09",
                    queryString(
                        "SELECT total_amount::text FROM healthcare.child_service_settlements WHERE patient_id = '$CHILD'",
                    ),
                    "NUMERIC(12,2) 必须真落库",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `E2超额收款400_不写流水不更新头`(vertx: Vertx, ctx: VertxTestContext) {
        var settlementId = ""
        createSettlement(vertx, ctx, CHILD, null, listOf(Triple("ITEM-Z", 10.0, 1.0))).compose { settlement ->
            settlementId = settlement.getString("id")
            pay(vertx, settlementId, 6.0)
        }.compose { (s1, partial) ->
            ctx.verify {
                assertEquals(201, s1, "首次收款必须 201，实际: $partial")
                assertEquals("部分收款", partial!!.getString("status"))
            }
            val before = queryString("SELECT updated_at::text FROM healthcare.child_service_settlements WHERE id = '$settlementId'")
            // 再收 5 → 累计 11 > 10 → 400
            pay(vertx, settlementId, 5.0).map { Triple(before, it.first, it.second) }
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (before, status, body) = ar.result()
                assertEquals(400, status, "超额收款必须 400，实际: $body")
                assertTrue(body?.getString("error")?.contains("exceeds outstanding") == true)
                assertEquals(
                    1L,
                    queryLong("SELECT count(*) FROM healthcare.child_service_settlement_payments WHERE settlement_id = '$settlementId'"),
                    "超额收款不得写收款流水",
                )
                assertEquals(
                    "6.00",
                    queryString("SELECT paid_amount::text FROM healthcare.child_service_settlements WHERE id = '$settlementId'"),
                    "超额收款不得更新结算单累计",
                )
                assertEquals(
                    before,
                    queryString("SELECT updated_at::text FROM healthcare.child_service_settlements WHERE id = '$settlementId'"),
                    "超额收款不得更新 updated_at",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  4) E3 发票：票号唯一 + 状态单向 + 关联结算单
    // ========================================================================

    @Test
    fun `E3发票_票号唯一409与状态单向`(vertx: Vertx, ctx: VertxTestContext) {
        val invoiceNo = "${PREFIX}INV-$RUN"
        post(
            vertx,
            INVOICES,
            JsonObject().put("invoice_no", invoiceNo).put("patient_id", CHILD)
                .put("title", "054E 发票").put("amount", 12.34),
        ).compose { (s1, created) ->
            ctx.verify {
                assertEquals(201, s1, "开票必须 201，实际: $created")
                assertEquals(invoiceNo, created!!.getString("invoice_no"))
                assertEquals("已开具", created.getString("status"))
                assertEquals(12.34, created.getDouble("amount") ?: -1.0, 0.0001)
                assertNotNull(created.getString("issued_at"), "issued_at 服务端派生")
                assertEquals(TESTER, created.getString("operator"))
            }
            val id = created!!.getString("id")
            // 票号唯一（换患者仍冲突）→ 409
            post(
                vertx,
                INVOICES,
                JsonObject().put("invoice_no", invoiceNo).put("patient_id", CHILD_2)
                    .put("title", "重复票号").put("amount", 1.0),
            ).map { Triple(id, it.first, it.second) }
        }.compose { (id, s2, duplicate) ->
            ctx.verify {
                assertEquals(409, s2, "重复票号必须 409，实际: $duplicate")
                assertTrue(duplicate?.getString("error")?.contains("already exists") == true)
            }
            patch(vertx, "$INVOICES/$id/status", JsonObject().put("status", "已作废")).map { Triple(id, it.first, it.second) }
        }.compose { (id, s3, voided) ->
            ctx.verify {
                assertEquals(200, s3, "已开具→已作废 必须 200，实际: $voided")
                assertEquals("已作废", voided!!.getString("status"))
            }
            // 反向流转非法
            patch(vertx, "$INVOICES/$id/status", JsonObject().put("status", "已开具"))
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "已作废→已开具 必须被拒绝（状态单向，服务端映射 400），实际: $body")
                assertTrue(body?.getString("error")?.contains("invalid status transition") == true)
                assertEquals(
                    "已作废",
                    queryString("SELECT status FROM healthcare.child_service_invoices WHERE invoice_no = '$invoiceNo'"),
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `E3发票_关联结算单金额服务端派生`(vertx: Vertx, ctx: VertxTestContext) {
        createSettlement(vertx, ctx, CHILD, null, listOf(Triple("ITEM-I", 25.5, 2.0))).compose { settlement ->
            ctx.verify { assertEquals(51.0, settlement.getDouble("total_amount") ?: -1.0, 0.0001) }
            post(
                vertx,
                INVOICES,
                JsonObject().put("patient_id", CHILD).put("title", "关联结算单开票")
                    .put("settlement_id", settlement.getString("id")),
            ).map { Triple(settlement.getString("id"), it.first, it.second) }
        }.compose { (settlementId, s1, invoice) ->
            ctx.verify {
                assertEquals(201, s1, "关联开票必须 201，实际: $invoice")
                assertEquals(51.0, invoice!!.getDouble("amount") ?: -1.0, 0.0001, "金额必须由结算单派生")
                assertEquals(settlementId, invoice.getString("settlement_id"))
            }
            // 已有关联时再提交 amount → 400（避免两个金额事实源）
            post(
                vertx,
                INVOICES,
                JsonObject().put("patient_id", CHILD).put("title", "x")
                    .put("settlement_id", settlementId).put("amount", 1.0),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "已有关联再传 amount 必须 400，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  5) 养老账期链回归 + 空列表 + 非法参数
    // ========================================================================

    @Test
    fun `E链不动养老账期链_bills行数不变`(vertx: Vertx, ctx: VertxTestContext) {
        val before = listOf("bills", "bill_items", "payments").associateWith { table ->
            queryLong("SELECT count(*) FROM healthcare.$table")
        }
        createPackage(vertx, ctx, price = 10.0, suffix = "reg").compose { pkg ->
            createSubscription(vertx, ctx, CHILD, pkg)
        }.compose { sub ->
            createSettlement(vertx, ctx, CHILD, sub.getString("id"), listOf(Triple("ITEM-R", 10.0, 1.0)))
        }.compose { settlement ->
            pay(vertx, settlement.getString("id"), 10.0)
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "E 链失败: ${ar.cause()?.message}")
                val (status, _) = ar.result()
                assertEquals(201, status)
                before.forEach { (table, count) ->
                    assertEquals(
                        count,
                        queryLong("SELECT count(*) FROM healthcare.$table"),
                        "E 链不得写入养老账期链表 $table",
                    )
                }
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `空列表形状与非法参数`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "$SUBSCRIPTIONS?patient_id=${enc(UNKNOWN)}").compose { (s1, subs) ->
            ctx.verify {
                assertEquals(200, s1, "实际: $subs")
                assertEquals(0, subs!!.getJsonArray("records").size())
                assertEquals(0L, subs.getJsonObject("meta").getLong("total"))
            }
            get(vertx, "$SETTLEMENTS?patient_id=${enc(UNKNOWN)}")
        }.compose { (s2, settlements) ->
            ctx.verify {
                assertEquals(200, s2, "实际: $settlements")
                assertEquals(0, settlements!!.getJsonArray("records").size())
                assertEquals(0L, settlements.getJsonObject("meta").getLong("total"))
            }
            get(vertx, "$INVOICES?patient_id=${enc(UNKNOWN)}")
        }.compose { (s3, invoices) ->
            ctx.verify {
                assertEquals(200, s3, "实际: $invoices")
                assertEquals(0, invoices!!.getJsonArray("records").size())
                assertEquals(0L, invoices.getJsonObject("meta").getLong("total"))
            }
            // 非儿童订阅 → 400
            createPackage(vertx, ctx, price = 1.0, suffix = "adult").compose { pkg ->
                post(
                    vertx,
                    SUBSCRIPTIONS,
                    JsonObject().put("patient_id", ADULT).put("package_id", pkg),
                )
            }
        }.compose { (s4, adultSub) ->
            ctx.verify {
                assertEquals(400, s4, "非儿童订阅必须 400，实际: $adultSub")
                assertTrue(adultSub?.getString("error")?.contains("not a child") == true)
            }
            // 空明细结算 → 400
            post(
                vertx,
                SETTLEMENTS,
                JsonObject().put("patient_id", CHILD).put("items", JsonArray()),
            )
        }.onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "空明细必须 400，实际: $body")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun insertPatient(id: String, name: String, personType: String) {
        exec(
            "INSERT INTO healthcare.patients (id, name, gender, person_type, status) " +
                "VALUES ('$id', '$name', '女', '$personType', 'ACTIVE')",
        )
    }

    private fun createPackage(vertx: Vertx, ctx: VertxTestContext, price: Double, suffix: String): Future<String> {
        val code = "$PREFIX$suffix-$RUN"
        return post(
            vertx,
            PACKAGES,
            JsonObject()
                .put("code", code)
                .put("name", "054E 套餐-$suffix")
                .put("price", price)
                .put("items", JsonArray().add(
                    JsonObject().put("item_code", "SVC-1").put("item_name", "服务项").put("quantity", 1),
                )),
        ).compose { (status, created) ->
            ctx.verify { assertEquals(201, status, "套餐创建必须 201，实际: $created") }
            Future.succeededFuture(created!!.getString("id"))
        }
    }

    private fun createSubscription(
        vertx: Vertx,
        ctx: VertxTestContext,
        patientId: String,
        packageId: String,
    ): Future<JsonObject> =
        post(vertx, SUBSCRIPTIONS, JsonObject().put("patient_id", patientId).put("package_id", packageId))
            .compose { (status, created) ->
                ctx.verify { assertEquals(201, status, "订阅创建必须 201，实际: $created") }
                Future.succeededFuture(created!!)
            }

    private fun createSettlement(
        vertx: Vertx,
        ctx: VertxTestContext,
        patientId: String,
        subscriptionId: String?,
        lines: List<Triple<String, Double, Double>>,
    ): Future<JsonObject> {
        val items = JsonArray()
        lines.forEach { (code, unitPrice, qty) ->
            items.add(
                JsonObject().put("item_code", code).put("item_name", "项目-$code")
                    .put("unit_price", unitPrice).put("quantity", qty),
            )
        }
        val body = JsonObject().put("patient_id", patientId).put("items", items)
        subscriptionId?.let { body.put("subscription_id", it) }
        return post(vertx, SETTLEMENTS, body).compose { (status, created) ->
            ctx.verify { assertEquals(201, status, "结算单创建必须 201，实际: $created") }
            Future.succeededFuture(created!!)
        }
    }

    private fun pay(vertx: Vertx, settlementId: String, amount: Double): Future<Pair<Int, JsonObject?>> =
        post(
            vertx,
            "$SETTLEMENTS/$settlementId/payments",
            JsonObject().put("amount", amount).put("method", "现金"),
        )

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
        exec(
            "DELETE FROM healthcare.child_service_invoices WHERE patient_id LIKE '$PREFIX%' " +
                "OR invoice_no LIKE '$PREFIX%'",
        )
        exec(
            "DELETE FROM healthcare.child_service_settlement_payments WHERE settlement_id IN (" +
                "SELECT id FROM healthcare.child_service_settlements WHERE patient_id LIKE '$PREFIX%')",
        )
        exec(
            "DELETE FROM healthcare.child_service_settlement_items WHERE settlement_id IN (" +
                "SELECT id FROM healthcare.child_service_settlements WHERE patient_id LIKE '$PREFIX%')",
        )
        exec("DELETE FROM healthcare.child_service_settlements WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.child_package_subscriptions WHERE patient_id LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.child_service_packages WHERE code LIKE '$PREFIX%'")
        exec("DELETE FROM healthcare.patients WHERE id LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "child_service_invoices" to
                "SELECT count(*) FROM healthcare.child_service_invoices WHERE patient_id LIKE '$PREFIX%' OR invoice_no LIKE '$PREFIX%'",
            "child_service_settlements" to
                "SELECT count(*) FROM healthcare.child_service_settlements WHERE patient_id LIKE '$PREFIX%'",
            "child_package_subscriptions" to
                "SELECT count(*) FROM healthcare.child_package_subscriptions WHERE patient_id LIKE '$PREFIX%'",
            "child_service_packages" to
                "SELECT count(*) FROM healthcare.child_service_packages WHERE code LIKE '$PREFIX%'",
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
