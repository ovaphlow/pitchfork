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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 054 E2 儿保服务结算单与收款测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. **金额口径**（纯函数）：明细金额 = 单价 × 数量 `ROUND_HALF_UP` 到分；合计 = 明细之和；
 *  2. **状态派生**（纯函数）：`待收款 / 部分收款 / 已结清`（零元结算单直接已结清）；
 *  3. **路由接线**：未认证写路由 401；非法入参与**超额收款 400 且零写入**；空列表
 *     `{records:[],meta:{total:0}}`；非儿童 400；患者/订阅不存在 404；作废态拒绝收款；
 *  4. **参数化 SQL**：`$1..$n` 占位 + `Tuple` 绑定值断言，SQL 文本不拼接用户输入。
 *
 * 用 mockk 桩驱动 `Pool.withTransaction` / `preparedQuery`；**不引用 jOOQ**。
 * 集中式权限声明与门禁接线由 `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildServiceSettlementRoutesTest {

    private val stub = SettlementDatabaseStub()

    @BeforeEach
    fun resetTraffic() {
        stub.reset()
    }

    // ========================================================================
    //  纯函数：金额与状态（054 §8.5）
    // ========================================================================

    @Test
    fun `明细金额按单价乘数量四舍五入到分`() {
        assertEquals(
            0,
            BigDecimal("30.00").compareTo(
                ChildServiceSettlementService.lineAmount(BigDecimal("10.00"), BigDecimal("3")),
            ),
        )
        assertEquals(
            0,
            BigDecimal("0.01").compareTo(
                ChildServiceSettlementService.lineAmount(BigDecimal("0.01"), BigDecimal("0.5")),
            ),
            "0.005 必须 ROUND_HALF_UP 到 0.01",
        )
        assertEquals(
            0,
            BigDecimal("0.03").compareTo(
                ChildServiceSettlementService.lineAmount(BigDecimal("0.10"), BigDecimal("0.333")),
            ),
            "0.0333 → 0.03",
        )
    }

    @Test
    fun `合计等于明细金额之和`() {
        val total = ChildServiceSettlementService.totalOf(
            listOf(BigDecimal("30.00"), BigDecimal("0.01"), BigDecimal("9.99")),
        )
        assertEquals(0, BigDecimal("40.00").compareTo(total))
        assertEquals(0, BigDecimal.ZERO.compareTo(ChildServiceSettlementService.totalOf(emptyList())))
    }

    @Test
    fun `收款后状态派生`() {
        fun status(total: String, paid: String): String =
            ChildServiceSettlementService.deriveStatus(BigDecimal(total), BigDecimal(paid))

        assertEquals("待收款", status("30.00", "0"))
        assertEquals("部分收款", status("30.00", "10.00"))
        assertEquals("部分收款", status("30.00", "29.99"), "差一分仍为部分收款")
        assertEquals("已结清", status("30.00", "30.00"))
        assertEquals("已结清", status("30.00", "30.01"), "超额（不应发生）仍为已结清")
        assertEquals("已结清", status("0", "0"), "零元结算单无需收款")
        assertEquals(
            setOf("待收款", "部分收款", "已结清", "已作废"),
            ChildServiceSettlementService.STATUSES,
            "状态白名单必须与 054 §5.8 一致",
        )
        assertEquals(
            setOf("现金", "转账", "银行卡", "微信", "支付宝"),
            ChildServiceSettlementService.PAYMENT_METHODS,
            "收款方式白名单必须与 V516 同口径",
        )
    }

    // ========================================================================
    //  路由：认证兜底
    // ========================================================================

    @Test
    fun `结算与收款写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, CREATE_BODY)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "POST 结算单未认证必须 401: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                    rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-1/payments", PAYMENT_BODY)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "POST 收款未认证必须 401: $payload")
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  路由：结算单
    // ========================================================================

    @Test
    fun `创建结算单 201 且合计由服务端按明细计算`(vertx: Vertx, ctx: VertxTestContext) {
        stub.settlementRow = settlementRow(total = "30.01")
        stub.itemRows = listOf(
            itemRow(itemCode = "FEE-001", unitPrice = "10.00", quantity = "3", amount = "30.00"),
            itemRow(itemCode = "FEE-002", unitPrice = "0.01", quantity = "0.5", amount = "0.01"),
        )
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, CREATE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("待收款", payload.getString("status"))
                assertEquals(2, payload.getJsonArray("items").size())
                assertEquals(
                    0,
                    BigDecimal("30.01").compareTo(BigDecimal(payload.getValue("total_amount").toString())),
                    "合计 = 明细金额之和（0.005 HALF_UP 到 0.01）",
                )

                val header = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_settlements") }
                assertEquals(26, (header.second[0] as String).length, "服务端生成 26 位 ULID")
                assertTrue((header.second[1] as String).startsWith("CSS-"), "结算单号服务端生成：${header.second[1]}")
                assertEquals("child-1", header.second[2])
                assertEquals(null, header.second[3], "未提交订阅时 subscription_id 为 null")
                assertEquals(0, BigDecimal("30.01").compareTo(header.second[4] as BigDecimal))
                assertEquals(0, BigDecimal.ZERO.compareTo(header.second[5] as BigDecimal), "paid_amount 初值 0")
                assertEquals("待收款", header.second[6])
                assertEquals("cashier-1", header.second[7], "operator 取认证主体")
                assertFalse(header.first.contains("30.01"), "SQL 文本不得拼接金额")

                val itemInserts = stub.tuples.filter { it.first.startsWith("insert into healthcare.child_service_settlement_items") }
                assertEquals(2, itemInserts.size, "两条明细各插一行")
                assertEquals(0, BigDecimal("30.00").compareTo(itemInserts[0].second[7] as BigDecimal))
                assertEquals(0, BigDecimal("0.01").compareTo(itemInserts[1].second[7] as BigDecimal))
                assertEquals(0, itemInserts[0].second[2], "sort_order 从 0 起")
                assertEquals(1, itemInserts[1].second[2])
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `创建结算单非法入参 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"items":[{"item_code":"c","item_name":"n","unit_price":"1.00","quantity":1}]}""" to "patient_id is required",
            """{"patient_id":"child-1"}""" to "items is required",
            """{"patient_id":"child-1","items":[]}""" to "items must not be empty",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","quantity":1}]}""" to "unit_price is required",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.00"}]}""" to "quantity is required",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.001","quantity":1}]}"""
                to "unit_price exceeds precision of 2 decimals",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.00","quantity":0}]}"""
                to "quantity must be positive",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.00","quantity":0.0001}]}"""
                to "quantity exceeds precision of 3 decimals",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.00","quantity":1}],"total_amount":"0.01"}"""
                to "unsupported child service settlement keys",
            """{"patient_id":"child-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.00","quantity":1}],"status":"已结清"}"""
                to "unsupported child service settlement keys",
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
    fun `非儿童患者不能建儿保结算单 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.patientRow = mockRow(mapOf("id" to "adult-1", "person_type" to "成人"))
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, CREATE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("not a child"), "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `患者不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.patientRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, CREATE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(404, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("patient not found"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `套餐订阅不存在或非本人 4xx`(vertx: Vertx, ctx: VertxTestContext) {
        val body = """{"patient_id":"child-1","subscription_id":"sub-1","items":[{"item_code":"c","item_name":"n","unit_price":"1.00","quantity":1}]}"""
        stub.subscriptionRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, body)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(404, status, "订阅不存在必须 404: $payload")
                        assertTrue(payload.getString("error")!!.contains("subscription not found"))
                    }
                    stub.subscriptionRow = mockRow(
                        mapOf("id" to "sub-1", "patient_id" to "other-child", "status" to "启用"),
                    )
                    rawRequest(vertx, port, HttpMethod.POST, PATH, body)
                }
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "订阅非本人必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("does not belong"))
                    }
                    stub.subscriptionRow = mockRow(
                        mapOf("id" to "sub-1", "patient_id" to "child-1", "status" to "停用"),
                    )
                    rawRequest(vertx, port, HttpMethod.POST, PATH, body)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "订阅停用必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("not active"))
                        assertTrue(stub.executedSql.none { it.startsWith("insert") })
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `带套餐订阅的结算单累加订阅已核销金额`(vertx: Vertx, ctx: VertxTestContext) {
        stub.subscriptionRow = mockRow(mapOf("id" to "sub-1", "patient_id" to "child-1", "status" to "启用"))
        stub.settlementRow = settlementRow(total = "30.01", subscriptionId = "sub-1")
        val body = """{"patient_id":"child-1","subscription_id":"sub-1","items":[{"item_code":"c","item_name":"n","unit_price":"30.01","quantity":1}]}"""
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PATH, body)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                val consume = stub.tuples.first {
                    it.first.startsWith("update healthcare.child_package_subscriptions") && it.first.contains("used_amount")
                }
                assertEquals("sub-1", consume.second[0])
                assertEquals(0, BigDecimal("30.01").compareTo(consume.second[1] as BigDecimal), "累加 used_amount = 结算合计")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `结算单列表空结果返回 records 空数组与 total 0`(vertx: Vertx, ctx: VertxTestContext) {
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
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `结算单列表带出明细`(vertx: Vertx, ctx: VertxTestContext) {
        stub.settlementCount = 1
        stub.settlementListRows = listOf(settlementRow(id = "st-1", total = "30.01", paid = "10.00", status = "部分收款"))
        stub.itemRows = listOf(itemRow(settlementId = "st-1", amount = "30.00"))
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, PATH, null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals(1, payload.getJsonArray("records").size())
                assertEquals("部分收款", payload.getJsonArray("records").getJsonObject(0).getString("status"))
                assertEquals(1, payload.getJsonArray("records").getJsonObject(0).getJsonArray("items").size())
                assertEquals(1L, payload.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  路由：收款
    // ========================================================================

    @Test
    fun `部分收款 201 且状态流转为部分收款`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockRow = settlementRow(id = "st-1", total = "30.01", paid = "0", status = "待收款")
        stub.settlementRow = settlementRow(id = "st-1", total = "30.01", paid = "10.00", status = "部分收款")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-1/payments", """{"amount":"10.00","method":"现金"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("部分收款", payload.getString("status"))

                val payment = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_settlement_payments") }
                assertEquals("st-1", payment.second[1])
                assertEquals(0, BigDecimal("10.00").compareTo(payment.second[2] as BigDecimal))
                assertEquals("现金", payment.second[3])
                assertEquals("cashier-1", payment.second[4], "operator 取认证主体")

                val update = stub.tuples.first { it.first.startsWith("update healthcare.child_service_settlements") }
                assertEquals("st-1", update.second[0])
                assertEquals(0, BigDecimal("10.00").compareTo(update.second[1] as BigDecimal))
                assertEquals("部分收款", update.second[2])
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `足额收款状态流转为已结清`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockRow = settlementRow(id = "st-1", total = "30.01", paid = "20.01", status = "部分收款")
        stub.settlementRow = settlementRow(id = "st-1", total = "30.01", paid = "30.01", status = "已结清")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-1/payments", """{"amount":"10.00","method":"微信"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("已结清", payload.getString("status"))
                val update = stub.tuples.first { it.first.startsWith("update healthcare.child_service_settlements") }
                assertEquals("已结清", update.second[2])
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `超额收款 400 且不写入`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockRow = settlementRow(id = "st-1", total = "30.01", paid = "20.00", status = "部分收款")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-1/payments", """{"amount":"10.02","method":"现金"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "超额收款必须 400: $payload")
                assertTrue(payload.getString("error")!!.contains("exceeds outstanding"), "body=$payload")
                assertTrue(
                    stub.executedSql.none { it.startsWith("insert into healthcare.child_service_settlement_payments") },
                    "超额收款不得写流水：${stub.executedSql}",
                )
                assertTrue(stub.executedSql.none { it.startsWith("update") }, "超额收款不得更新结算单")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `收款非法入参 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"method":"现金"}""" to "amount is required",
            """{"amount":"0","method":"现金"}""" to "amount must be positive",
            """{"amount":"-1","method":"现金"}""" to "amount must not be negative",
            """{"amount":"1.001","method":"现金"}""" to "amount exceeds precision of 2 decimals",
            """{"amount":"1.00"}""" to "method is required",
            """{"amount":"1.00","method":"刷卡"}""" to "invalid method",
            """{"amount":"1.00","method":"现金","operator":"hacker"}""" to "unsupported child service settlement payment keys",
            """{"amount":"1.00","method":"现金","paid_amount":"1"}""" to "unsupported child service settlement payment keys",
        )
        withServer(vertx, userId = "cashier-1") { port ->
            cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
                chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-1/payments", body).map { (status, payload) ->
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
                assertTrue(stub.executedSql.isEmpty(), "非法入参不得触库：${stub.executedSql}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `结算单不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-x/payments", PAYMENT_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(404, status, "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已作废结算单拒绝收款 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.lockRow = settlementRow(id = "st-1", total = "30.01", paid = "0", status = "已作废")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, "$PATH/st-1/payments", PAYMENT_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "作废态收款必须 400: $payload")
                assertTrue(payload.getString("error")!!.contains("settlement is void"), "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/child-service-settlements"
        const val CREATE_BODY =
            """{"patient_id":"child-1","items":[""" +
                """{"item_code":"FEE-001","item_name":"生长发育评估","unit_price":"10.00","quantity":3},""" +
                """{"item_code":"FEE-002","item_name":"神经心理测评","unit_price":"0.01","quantity":0.5}]}"""
        const val PAYMENT_BODY = """{"amount":"10.00","method":"现金"}"""
    }

    private class SettlementDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val conn: SqlConnection = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val executedSql = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        var patientRow: Row? = mockRow(mapOf("id" to "child-1", "person_type" to "儿童"))
        var subscriptionRow: Row? = null
        var settlementRow: Row? = null
        var lockRow: Row? = null
        var itemRows: List<Row> = emptyList()
        var settlementListRows: List<Row> = emptyList()
        var settlementCount: Long = 0

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
            subscriptionRow = null
            settlementRow = null
            lockRow = null
            itemRows = emptyList()
            settlementListRows = emptyList()
            settlementCount = 0
            lastSql = ""
        }

        private fun route(sql: String): RowSet<Row> = when {
            sql.contains("from healthcare.patients") -> patientRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("from healthcare.child_package_subscriptions") && !sql.startsWith("update") ->
                subscriptionRow?.let { rowSet(it) } ?: rowSet()
            sql.startsWith("insert into healthcare.child_service_settlements") -> rowSet()
            sql.startsWith("insert into healthcare.child_service_settlement_items") -> rowSet()
            sql.startsWith("insert into healthcare.child_service_settlement_payments") -> rowSet()
            sql.startsWith("update healthcare.child_service_settlements") -> rowSet()
            sql.startsWith("update healthcare.child_package_subscriptions") -> rowSet()
            sql.contains("select count(*) as total from healthcare.child_service_settlements") ->
                rowSet(mockRow(mapOf("total" to settlementCount)))
            sql.contains("from healthcare.child_service_settlements") && sql.contains("for update") ->
                lockRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("from healthcare.child_service_settlement_items") ->
                rowSet(*itemRows.toTypedArray())
            sql.contains("from healthcare.child_service_settlements") && sql.contains("order by") ->
                rowSet(*settlementListRows.toTypedArray())
            sql.contains("from healthcare.child_service_settlements") ->
                settlementRow?.let { rowSet(it) } ?: rowSet()
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

private fun settlementRow(
    id: String = "st-1",
    total: String = "30.01",
    paid: String = "0",
    status: String = "待收款",
    subscriptionId: String? = null,
): Row = mockRow(
    mapOf(
        "id" to id,
        "settlement_no" to "CSS-$id",
        "patient_id" to "child-1",
        "subscription_id" to subscriptionId,
        "total_amount" to BigDecimal(total),
        "paid_amount" to BigDecimal(paid),
        "status" to status,
        "operator" to "cashier-1",
        "metadata" to null,
        "created_at" to OffsetDateTime.now(),
        "updated_at" to OffsetDateTime.now(),
    ),
)

private fun itemRow(
    settlementId: String = "st-1",
    itemCode: String = "FEE-001",
    unitPrice: String = "10.00",
    quantity: String = "3",
    amount: String = "30.00",
): Row = mockRow(
    mapOf(
        "id" to "item-1",
        "settlement_id" to settlementId,
        "sort_order" to 0,
        "item_code" to itemCode,
        "item_name" to "生长发育评估",
        "unit_price" to BigDecimal(unitPrice),
        "quantity" to BigDecimal(quantity),
        "amount" to BigDecimal(amount),
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
