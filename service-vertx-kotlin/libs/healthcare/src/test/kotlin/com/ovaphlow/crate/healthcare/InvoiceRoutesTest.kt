package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 054 E3 儿保发票台账测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. **状态单向**（纯函数）：`已开具 → 已作废`；同状态 / 反向 / 未知状态一律非法；
 *  2. **票号唯一**：客户端提交纸质票号重复 → 409 且不写库；缺省由服务端生成 `INV-<ULID>`；
 *  3. **金额派生**：关联结算单取 `total_amount` 快照、关联订阅取 `amount` 快照；
 *     有链接时再提交 `amount` → 400（避免两个金额事实源）；两链接互斥；
 *  4. **路由接线**：未认证写路由 401；非法入参（含客户端提交 `id/status/issued_at/operator`）400
 *     且零触库；空列表 `{records:[],meta:{total:0}}`。
 *
 * 用 mockk 桩驱动 `Pool.withTransaction` / `preparedQuery`；**不引用 jOOQ**。
 * 集中式权限声明与门禁接线由 `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class InvoiceRoutesTest {

    private val stub = InvoiceDatabaseStub()

    @BeforeEach
    fun resetTraffic() {
        stub.reset()
    }

    // ========================================================================
    //  纯函数：状态单向（054 §8.5）
    // ========================================================================

    @Test
    fun `发票状态白名单与单向流转`() {
        assertEquals(
            setOf("已开具", "已作废"),
            InvoiceService.STATUSES,
            "状态白名单必须与 054 §5.8 一致",
        )
        assertTrue(InvoiceService.canTransition("已开具", "已作废"), "已开具 → 已作废 合法")
        assertFalse(InvoiceService.canTransition("已开具", "已开具"), "同状态重复提交非法")
        assertFalse(InvoiceService.canTransition("已作废", "已开具"), "已作废是终态，不得回退")
        assertFalse(InvoiceService.canTransition("已作废", "已作废"))
        assertFalse(InvoiceService.canTransition("不存在", "已作废"), "未知状态按最严处理")
    }

    // ========================================================================
    //  路由：认证兜底
    // ========================================================================

    @Test
    fun `发票写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00"}""")
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "POST 发票未认证必须 401: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                    rawRequest(vertx, port, HttpMethod.PATCH, "$PATH/inv-1/status", """{"status":"已作废"}""")
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "PATCH 发票状态未认证必须 401: $payload")
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  路由：开具
    // ========================================================================

    @Test
    fun `开具发票 201 且票号金额与状态落库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.invoiceRow = invoiceRow()
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"invoice_no":"PAPER-0001","patient_id":"child-1","title":"儿保服务费","amount":"10.00"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("PAPER-0001", payload.getString("invoice_no"))
                assertEquals("已开具", payload.getString("status"), "新建发票恒为已开具")

                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_invoices") }
                val values = insert.second
                assertEquals(26, (values[0] as String).length, "服务端生成 26 位 ULID")
                assertEquals("PAPER-0001", values[1], "客户端纸质票号原样落库")
                assertEquals("child-1", values[2])
                assertEquals("儿保服务费", values[3])
                assertEquals(0, BigDecimal("10.00").compareTo(values[4] as BigDecimal))
                assertEquals("已开具", values[5])
                assertEquals(null, values[6], "未关联结算单")
                assertEquals(null, values[7], "未关联订阅")
                assertNotNull(values[8], "issued_at 服务端派生")
                assertEquals("cashier-1", values[9], "operator 取认证主体")
                assertTrue(insert.first.contains("\$13"), "必须使用 \$1..\$n 参数化占位：${insert.first}")
                assertFalse(insert.first.contains("PAPER-0001"), "SQL 文本不得拼接用户输入")
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `缺省票号由服务端生成`(vertx: Vertx, ctx: VertxTestContext) {
        stub.invoiceRow = invoiceRow(invoiceNo = "INV-GENERATED")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_invoices") }
                val invoiceNo = insert.second[1] as String
                assertTrue(invoiceNo.startsWith("INV-"), "缺省票号必须服务端生成 INV-<ULID>：$invoiceNo")
                assertEquals(30, invoiceNo.length, "INV- + 26 位 ULID")
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `票号重复 409 且不写库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.invoiceNoTaken = true
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"invoice_no":"PAPER-0001","patient_id":"child-1","title":"儿保服务费","amount":"10.00"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(409, status, "票号重复必须 409: $payload")
                assertTrue(payload.getString("error")!!.contains("invoice_no already exists"), "body=$payload")
                assertTrue(
                    stub.executedSql.none { it.startsWith("insert into") },
                    "票号冲突时不得写库：${stub.executedSql}",
                )
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `开具发票非法入参 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"title":"儿保服务费","amount":"10.00"}""" to "patient_id is required",
            """{"patient_id":"child-1","amount":"10.00"}""" to "title is required",
            """{"patient_id":"child-1","title":"儿保服务费"}""" to "amount is required when no settlement_id/subscription_id is given",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"0"}""" to "amount must be positive",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"-1"}""" to "amount must not be negative",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"10.001"}""" to "amount exceeds precision of 2 decimals",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00","settlement_id":"st-1","subscription_id":"sub-1"}"""
                to "mutually exclusive",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00","settlement_id":"st-1"}"""
                to "amount must not be provided when settlement_id/subscription_id is set",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00","status":"已作废"}"""
                to "unsupported child service invoice keys",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00","issued_at":"2026-01-01T00:00:00+08:00"}"""
                to "unsupported child service invoice keys",
            """{"patient_id":"child-1","title":"儿保服务费","amount":"10.00","operator":"hacker"}"""
                to "unsupported child service invoice keys",
            """{"id":"inv-1","patient_id":"child-1","title":"儿保服务费","amount":"10.00"}"""
                to "unsupported child service invoice keys",
        )
        withServer(vertx, userId = "cashier-1") { port ->
            cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
                chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, PATH, body).map { (status, payload) ->
                        assertEquals(400, status, "body=$body response=$payload")
                        assertTrue(
                            payload.getString("error")!!.contains(expected),
                            "期望错误含 '$expected'，实际: $payload",
                        )
                    }
                }
            }
        }.onComplete { ar ->
            ctx.verify {
                ar.result()
                assertTrue(stub.executedSql.isEmpty(), "非法入参必须在进库前被拒绝：${stub.executedSql}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童患者不能开票 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.patientRow = mockRow(mapOf("id" to "adult-1", "person_type" to "成人"))
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"patient_id":"adult-1","title":"儿保服务费","amount":"10.00"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("not a child"), "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `患者不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.patientRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, """{"patient_id":"child-x","title":"儿保服务费","amount":"10.00"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(404, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("patient not found"))
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  路由：金额派生与关联
    // ========================================================================

    @Test
    fun `关联结算单时金额取结算单合计快照`(vertx: Vertx, ctx: VertxTestContext) {
        stub.settlementRow = mockRow(
            mapOf("id" to "st-1", "patient_id" to "child-1", "total_amount" to BigDecimal("30.01")),
        )
        stub.invoiceRow = invoiceRow(amount = "30.01", settlementId = "st-1")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"child-1","title":"儿保服务费","settlement_id":"st-1"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("st-1", payload.getString("settlement_id"))
                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_invoices") }
                assertEquals(0, BigDecimal("30.01").compareTo(insert.second[4] as BigDecimal), "金额取结算单快照")
                assertEquals("st-1", insert.second[6])
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `关联订阅时金额取订阅金额快照`(vertx: Vertx, ctx: VertxTestContext) {
        stub.subscriptionRow = mockRow(
            mapOf("id" to "sub-1", "patient_id" to "child-1", "amount" to BigDecimal("199.00")),
        )
        stub.invoiceRow = invoiceRow(amount = "199.00", subscriptionId = "sub-1")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"child-1","title":"儿保套餐费","subscription_id":"sub-1"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("sub-1", payload.getString("subscription_id"))
                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_invoices") }
                assertEquals(0, BigDecimal("199.00").compareTo(insert.second[4] as BigDecimal), "金额取订阅快照")
                assertEquals("sub-1", insert.second[7])
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `关联单据不存在或非本人 4xx`(vertx: Vertx, ctx: VertxTestContext) {
        val body = """{"patient_id":"child-1","title":"儿保服务费","settlement_id":"st-1"}"""
        stub.settlementRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, body)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(404, status, "结算单不存在必须 404: $payload")
                        assertTrue(payload.getString("error")!!.contains("settlement not found"))
                    }
                    stub.settlementRow = mockRow(
                        mapOf("id" to "st-1", "patient_id" to "other-child", "total_amount" to BigDecimal("30.01")),
                    )
                    rawRequest(vertx, port, HttpMethod.POST, PATH, body)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "结算单非本人必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("does not belong"))
                        assertTrue(stub.executedSql.none { it.startsWith("insert") })
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  路由：台账查询与作废
    // ========================================================================

    @Test
    fun `发票列表空结果返回 records 空数组与 total 0`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$PATH?patient_id=child-1", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals(0, payload.getJsonArray("records").size())
                assertEquals(0L, payload.getJsonObject("meta").getLong("total"))
                val countQuery = stub.tuples.first { it.first.contains("select count") }
                assertEquals(listOf("child-1"), countQuery.second, "patient_id 必须参数化绑定")
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `发票列表按患者与状态过滤`(vertx: Vertx, ctx: VertxTestContext) {
        stub.invoiceCount = 1
        stub.invoiceListRows = listOf(invoiceRow())
        val statusParam = java.net.URLEncoder.encode("已开具", "UTF-8")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$PATH?patient_id=child-1&status=$statusParam&limit=10&offset=5", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals(1, payload.getJsonArray("records").size())
                assertEquals(1L, payload.getJsonObject("meta").getLong("total"))
                val listQuery = stub.tuples.first { it.first.contains("order by") }
                assertEquals(listOf("child-1", "已开具", 10, 5), listQuery.second)
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `发票列表非法 status 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$PATH?status=ISSUED", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("invalid status"))
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `作废发票 200 且状态单向`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockStatus = "已开具"
        stub.invoiceRow = invoiceRow(status = "已作废")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.PATCH, "$PATH/inv-1/status", """{"status":"已作废"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "已开具 → 已作废 合法: $payload")
                assertEquals("已作废", payload.getString("status"))
                val update = stub.tuples.first { it.first.startsWith("update healthcare.child_service_invoices") }
                assertEquals("inv-1", update.second[0])
                assertEquals("已作废", update.second[1])
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `同状态或反向流转 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockStatus = "已开具"
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.PATCH, "$PATH/inv-1/status", """{"status":"已开具"}""")
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "同状态重复提交必须 400: $payload")
                        assertEquals("invalid status transition: 已开具 -> 已开具", payload.getString("error"))
                    }
                    stub.lockStatus = "已作废"
                    rawRequest(vertx, port, HttpMethod.PATCH, "$PATH/inv-1/status", """{"status":"已开具"}""")
                }
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "终态回退必须 400: $payload")
                        assertEquals("invalid status transition: 已作废 -> 已开具", payload.getString("error"))
                    }
                    rawRequest(vertx, port, HttpMethod.PATCH, "$PATH/inv-1/status", """{"status":"UNKNOWN"}""")
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "非法状态值必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("invalid status"))
                        assertTrue(stub.executedSql.none { it.startsWith("update") })
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `发票不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockStatus = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.PATCH, "$PATH/inv-x/status", """{"status":"已作废"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(404, status, "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("update") })
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/invoices"
    }

    private class InvoiceDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val conn: SqlConnection = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val executedSql = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        var patientRow: Row? = mockRow(mapOf("id" to "child-1", "person_type" to "儿童"))
        var settlementRow: Row? = null
        var subscriptionRow: Row? = null
        var invoiceNoTaken: Boolean = false
        var invoiceRow: Row? = null
        var invoiceListRows: List<Row> = emptyList()
        var invoiceCount: Long = 0
        var lockStatus: String? = null

        private var lastSql = ""

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { pool.withTransaction<JsonObject>(any()) } answers {
                firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(conn)
            }
            every { prepared.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                Future.succeededFuture(route(sql))
            }
            every { prepared.execute() } answers { Future.succeededFuture(rowSet()) }
        }

        fun reset() {
            executedSql.clear()
            tuples.clear()
            patientRow = mockRow(mapOf("id" to "child-1", "person_type" to "儿童"))
            settlementRow = null
            subscriptionRow = null
            invoiceNoTaken = false
            invoiceRow = null
            invoiceListRows = emptyList()
            invoiceCount = 0
            lockStatus = null
            lastSql = ""
        }

        private fun route(sql: String): RowSet<Row> = when {
            sql.contains("from healthcare.patients") -> patientRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("from healthcare.child_service_settlements") ->
                settlementRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("from healthcare.child_package_subscriptions") ->
                subscriptionRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("invoice_no = ") ->
                if (invoiceNoTaken) rowSet(mockRow(mapOf("id" to "other-inv"))) else rowSet()
            sql.startsWith("insert into healthcare.child_service_invoices") -> rowSet()
            sql.startsWith("update healthcare.child_service_invoices") -> rowSet()
            sql.contains("select count(*) as total from healthcare.child_service_invoices") ->
                rowSet(mockRow(mapOf("total" to invoiceCount)))
            sql.contains("from healthcare.child_service_invoices") && sql.contains("for update") ->
                lockStatus?.let { rowSet(mockRow(mapOf("status" to it))) } ?: rowSet()
            sql.contains("from healthcare.child_service_invoices") && sql.contains("order by") ->
                rowSet(*invoiceListRows.toTypedArray())
            sql.contains("from healthcare.child_service_invoices") ->
                invoiceRow?.let { rowSet(it) } ?: rowSet()
            else -> rowSet()
        }

        private fun record(sql: String) {
            lastSql = sql.lowercase().replace("\"", "")
            executedSql.add(lastSql)
        }
    }

    private fun <T> withServer(
        vertx: Vertx,
        userId: String?,
        block: (Int) -> Future<T>,
    ): Future<T> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            // 054 F2：路由装配会 fire-and-forget 加载基础数据内容；清掉装配期查询，
            // 使「未认证/非法入参不得触库」断言只覆盖**请求期**行为。
            stub.executedSql.clear()
            block(server.actualPort()).compose { value -> server.close().map(value) }
        }
    }

    private fun rawRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        rawBody: String?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (rawBody != null) req.putHeader("Content-Type", "application/json").send(rawBody) else req.send()
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer)
                    } catch (_: RuntimeException) {
                        JsonObject()
                    }
                    resp.statusCode() to json
                }
            }
            .onComplete { client.close() }
    }
}

// ========================================================================
//  mockk 桩辅助（Kotlin 顶层 private 为文件可见，故本文件自带一份）
// ========================================================================

private fun invoiceRow(
    id: String = "inv-1",
    invoiceNo: String = "PAPER-0001",
    amount: String = "10.00",
    status: String = "已开具",
    settlementId: String? = null,
    subscriptionId: String? = null,
): Row = mockRow(
    mapOf(
        "id" to id,
        "invoice_no" to invoiceNo,
        "patient_id" to "child-1",
        "title" to "儿保服务费",
        "amount" to BigDecimal(amount),
        "status" to status,
        "settlement_id" to settlementId,
        "subscription_id" to subscriptionId,
        "issued_at" to OffsetDateTime.now(),
        "operator" to "cashier-1",
        "metadata" to null,
        "created_at" to OffsetDateTime.now(),
        "updated_at" to OffsetDateTime.now(),
    ),
)

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getJsonArray(any<String>()) } answers { values[firstArg<String>()] as? JsonArray }
    every { row.getJsonObject(any<String>()) } answers { values[firstArg<String>()] as? JsonObject }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val iterator = mockk<io.vertx.sqlclient.RowIterator<Row>>()
        every { iterator.hasNext() } answers { delegate.hasNext() }
        every { iterator.next() } answers { delegate.next() }
        iterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun tupleValues(tuple: Tuple): List<Any?> {
    val result = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) {
        result.add(tuple.getValue(i))
    }
    return result
}
