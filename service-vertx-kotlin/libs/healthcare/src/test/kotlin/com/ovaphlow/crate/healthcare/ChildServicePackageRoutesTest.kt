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
 * 054 E1 儿保服务套餐与订阅测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. **付费状态读时派生**（纯函数）：未付 / 部分 / 已付 / 已用完 及其边界（足额、超额、免费套餐）；
 *  2. **订阅状态机**（纯函数）：`启用 ⇄ 停用`，同状态/未知状态一律非法；
 *  3. **路由接线**：未认证写路由 401；非法入参在**进库前** 400 且零触库；空列表 `{records:[],meta:{total:0}}`；
 *     套餐编码重复 409；订阅创建以**套餐价快照**为应付金额（客户端 `amount` 一律 400）；
 *  4. **参数化 SQL**：`$1..$n` 占位 + `Tuple` 绑定值断言，SQL 文本不拼接用户输入。
 *
 * 用 mockk 桩驱动 `Pool.withTransaction` / `preparedQuery`；**不引用 jOOQ**。
 * 集中式权限声明与门禁接线由 `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildServicePackageRoutesTest {

    private val stub = PackageDatabaseStub()

    @BeforeEach
    fun resetTraffic() {
        stub.reset()
    }

    // ========================================================================
    //  纯函数：付费状态派生（054 §5.8 / §8.5）
    // ========================================================================

    @Test
    fun `付费状态派生覆盖未付 部分 已付 已用完`() {
        fun state(amount: String, paid: String, used: String): String =
            ChildServicePackageService.derivePayState(
                BigDecimal(amount),
                BigDecimal(paid),
                BigDecimal(used),
            )

        assertEquals("未付", state("100", "0", "0"))
        assertEquals("部分", state("100", "40", "0"))
        assertEquals("已付", state("100", "100", "0"))
        assertEquals("已付", state("100", "120", "0"), "超额收款仍为已付")
        assertEquals("已付", state("100", "100", "50"), "已付但未用完")
        assertEquals("已用完", state("100", "100", "100"), "核销足额即已用完")
        assertEquals("已用完", state("100", "100", "120"), "超额核销仍为已用完")
        assertEquals("已付", state("0", "0", "0"), "免费套餐无需收款 → 已付")
    }

    @Test
    fun `付费状态派生边界取分位`() {
        assertEquals(
            "部分",
            ChildServicePackageService.derivePayState(
                BigDecimal("100.00"),
                BigDecimal("99.99"),
                BigDecimal.ZERO,
            ),
            "差一分仍为部分",
        )
        assertEquals(
            "未付",
            ChildServicePackageService.derivePayState(
                BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal("99.99"),
            ),
            "未收款时核销不足额仍为未付",
        )
    }

    @Test
    fun `订阅状态机仅允许启用与停用互转`() {
        assertEquals(
            setOf("启用", "停用"),
            ChildServicePackageService.SUBSCRIPTION_STATUSES,
            "订阅状态白名单必须与 054 §5.8 一致",
        )
        assertTrue(ChildServicePackageService.canTransitionSubscription("启用", "停用"))
        assertTrue(ChildServicePackageService.canTransitionSubscription("停用", "启用"))
        assertFalse(ChildServicePackageService.canTransitionSubscription("启用", "启用"), "同状态重复提交非法")
        assertFalse(ChildServicePackageService.canTransitionSubscription("停用", "停用"))
        assertFalse(ChildServicePackageService.canTransitionSubscription("不存在", "停用"), "未知状态按最严处理")
    }

    // ========================================================================
    //  路由：认证兜底
    // ========================================================================

    @Test
    fun `套餐与订阅写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(vertx, port, HttpMethod.POST, PACKAGE_PATH, PACKAGE_BODY)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "POST 套餐未认证必须 401: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                    rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, SUBSCRIPTION_BODY)
                }
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "POST 订阅未认证必须 401: $payload")
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                    rawRequest(vertx, port, HttpMethod.PATCH, "$SUBSCRIPTION_PATH/sub-1/status", """{"status":"停用"}""")
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "PATCH 订阅状态未认证必须 401: $payload")
                        assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                    }
                    ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  路由：套餐定义
    // ========================================================================

    @Test
    fun `创建套餐 201 且金额与服务项落库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.packageRow = packageRow()
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PACKAGE_PATH, PACKAGE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("PKG-CHILD-001", payload.getString("code"))
                assertEquals("启用", payload.getString("status"), "status 缺省必须是启用")
                assertEquals(0, BigDecimal("199.00").compareTo(BigDecimal(payload.getValue("price").toString())))

                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.child_service_packages") }
                val values = insert.second
                assertEquals(26, (values[0] as String).length, "服务端生成 26 位 ULID")
                assertEquals("PKG-CHILD-001", values[1])
                assertEquals("儿保基础套餐", values[2])
                assertEquals(0, BigDecimal("199.00").compareTo(values[3] as BigDecimal))
                assertEquals("启用", values[5])
                assertEquals("cashier-1", values[7], "created_by 取认证主体")
                assertTrue(insert.first.contains("\$10"), "必须使用 \$1..\$n 参数化占位：${insert.first}")
                assertFalse(insert.first.contains("PKG-CHILD-001"), "SQL 文本不得拼接用户输入")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建套餐非法入参 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"name":"套餐","price":"10.00","items":[{"item_code":"c","item_name":"n","quantity":1}]}"""
                to "code is required",
            """{"code":"C1","price":"10.00","items":[{"item_code":"c","item_name":"n","quantity":1}]}"""
                to "name is required",
            """{"code":"C1","name":"套餐","items":[{"item_code":"c","item_name":"n","quantity":1}]}"""
                to "price is required",
            """{"code":"C1","name":"套餐","price":"10.00"}""" to "items is required",
            """{"code":"C1","name":"套餐","price":"10.00","items":[]}""" to "items must not be empty",
            """{"code":"C1","name":"套餐","price":"10.00","items":[{"item_code":"c","item_name":"n","quantity":0}]}"""
                to "quantity must be positive",
            """{"code":"C1","name":"套餐","price":"10.00","items":[{"item_code":"c","item_name":"n"}]}"""
                to "quantity is required",
            """{"code":"C1","name":"套餐","price":"10.001","items":[{"item_code":"c","item_name":"n","quantity":1}]}"""
                to "exceeds precision of 2 decimals",
            """{"code":"C1","name":"套餐","price":"-1","items":[{"item_code":"c","item_name":"n","quantity":1}]}"""
                to "must not be negative",
            """{"code":"C1","name":"套餐","price":"10.00","items":[{"item_code":"c","item_name":"n","quantity":1}],"status":"ACTIVE"}"""
                to "invalid status",
            """{"code":"C1","name":"套餐","price":"10.00","items":[{"item_code":"c","item_name":"n","quantity":1}],"created_by":"hacker"}"""
                to "unsupported child service package keys",
        )

        withServer(vertx, userId = "cashier-1") { port ->
            cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
                chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, PACKAGE_PATH, body).map { (status, payload) ->
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
    fun `套餐编码重复 409 且不写库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.codeTaken = true
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, PACKAGE_PATH, PACKAGE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(409, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("package code already exists"), "body=$payload")
                assertTrue(
                    stub.executedSql.none { it.startsWith("insert into") },
                    "编码冲突时不得写库：${stub.executedSql}",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `套餐列表空结果返回 records 空数组与 total 0`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, PACKAGE_PATH, null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals(0, payload.getJsonArray("records").size())
                assertEquals(0L, payload.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `套餐列表按 status 过滤且绑定值参数化`(vertx: Vertx, ctx: VertxTestContext) {
        stub.packageCount = 1
        stub.packageListRows = listOf(packageRow())
        // 中文枚举必须按 UTF-8 百分号编码进入查询串（HTTP 请求行不允许裸非 ASCII）
        val statusParam = java.net.URLEncoder.encode("启用", "UTF-8")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$PACKAGE_PATH?status=$statusParam&limit=10&offset=5", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals(1, payload.getJsonArray("records").size())
                assertEquals(1L, payload.getJsonObject("meta").getLong("total"))

                val countQuery = stub.tuples.first { it.first.contains("select count") }
                assertEquals(listOf("启用"), countQuery.second)
                val listQuery = stub.tuples.first { it.first.contains("order by") }
                assertEquals(listOf("启用", 10, 5), listQuery.second)
                assertTrue(listQuery.first.contains("\$3"), "过滤值必须参数化：${listQuery.first}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `套餐列表非法 status 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$PACKAGE_PATH?status=ACTIVE", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("invalid status"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `修改套餐 200 且未提交字段保持原值`(vertx: Vertx, ctx: VertxTestContext) {
        stub.packageRow = packageRow(status = "停用", price = "299.00")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "$PACKAGE_PATH/pkg-1",
                """{"price":"299.00","status":"停用"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals("停用", payload.getString("status"))

                val update = stub.tuples.first { it.first.startsWith("update healthcare.child_service_packages") }
                assertEquals("pkg-1", update.second[0])
                assertEquals("PKG-CHILD-001", update.second[1], "未提交 code 时保持原值")
                assertEquals("儿保基础套餐", update.second[2], "未提交 name 时保持原值")
                assertEquals(0, BigDecimal("299.00").compareTo(update.second[3] as BigDecimal))
                assertEquals("停用", update.second[5])
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `修改不存在的套餐 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.packageRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.PUT, "$PACKAGE_PATH/pkg-x", """{"status":"停用"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(404, status, "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("update") })
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  路由：套餐订阅
    // ========================================================================

    @Test
    fun `购买套餐 201 且应付金额取套餐价快照`(vertx: Vertx, ctx: VertxTestContext) {
        stub.packageRow = packageRow()
        stub.subscriptionRow = subscriptionRow(amount = "199.00", paid = "0", used = "0")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, SUBSCRIPTION_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("未付", payload.getString("pay_state"), "初值 0 收款 → 未付")
                assertEquals("启用", payload.getString("status"))

                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.child_package_subscriptions") }
                val values = insert.second
                assertEquals(26, (values[0] as String).length)
                assertEquals("child-1", values[1])
                assertEquals("pkg-1", values[2])
                assertEquals("PKG-CHILD-001", values[3], "套餐编码快照")
                assertEquals("儿保基础套餐", values[4], "套餐名称快照")
                assertEquals(0, BigDecimal("199.00").compareTo(values[6] as BigDecimal), "应付金额取套餐价快照")
                assertEquals(0, BigDecimal.ZERO.compareTo(values[7] as BigDecimal), "paid_amount 初值 0")
                assertEquals(0, BigDecimal.ZERO.compareTo(values[8] as BigDecimal), "used_amount 初值 0")
                assertEquals("启用", values[9])
                assertEquals("cashier-1", values[11], "operator 取认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `购买套餐拒绝客户端提交金额与服务端字段`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"patient_id":"child-1","package_id":"pkg-1","amount":"0.01"}""" to "unsupported child package subscription keys",
            """{"patient_id":"child-1","package_id":"pkg-1","paid_amount":"1"}""" to "unsupported child package subscription keys",
            """{"patient_id":"child-1","package_id":"pkg-1","status":"停用"}""" to "unsupported child package subscription keys",
            """{"patient_id":"child-1"}""" to "package_id is required",
        )
        withServer(vertx, userId = "cashier-1") { port ->
            cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
                chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, body).map { (status, payload) ->
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
    fun `非儿童患者不能购买儿保套餐 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.patientRow = mockRow(
            mapOf("id" to "adult-1", "person_type" to "成人", "birth_date" to null),
        )
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, SUBSCRIPTION_BODY)
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
    fun `患者或套餐不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.patientRow = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, SUBSCRIPTION_BODY)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(404, status, "患者不存在必须 404: $payload")
                        assertTrue(payload.getString("error")!!.contains("patient not found"))
                    }
                    stub.patientRow = childRow()
                    stub.packageRow = null
                    rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, SUBSCRIPTION_BODY)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(404, status, "套餐不存在必须 404: $payload")
                        assertTrue(payload.getString("error")!!.contains("child service package not found"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `套餐已停用不能购买 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.packageRow = packageRow(status = "停用")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, SUBSCRIPTION_PATH, SUBSCRIPTION_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(400, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("package is not active"), "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `订阅列表空结果返回 records 空数组与 total 0`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$SUBSCRIPTION_PATH?patient_id=child-1", null)
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
    fun `订阅列表派生 pay_state`(vertx: Vertx, ctx: VertxTestContext) {
        stub.subscriptionCount = 2
        stub.subscriptionListRows = listOf(
            subscriptionRow(id = "sub-1", paid = "40", used = "0"),
            subscriptionRow(id = "sub-2", paid = "199.00", used = "199.00"),
        )
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, SUBSCRIPTION_PATH, null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                val records = payload.getJsonArray("records")
                assertEquals("部分", records.getJsonObject(0).getString("pay_state"))
                assertEquals("已用完", records.getJsonObject(1).getString("pay_state"))
                assertEquals(2L, payload.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `订阅停用 200 且同状态重复提交 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.subscriptionLockStatus = "启用"
        stub.subscriptionRow = subscriptionRow(status = "停用")
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.PATCH, "$SUBSCRIPTION_PATH/sub-1/status", """{"status":"停用"}""")
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(200, status, "启用 → 停用 合法: $payload")
                        assertEquals("停用", payload.getString("status"))
                        val update = stub.tuples.first { it.first.startsWith("update healthcare.child_package_subscriptions") }
                        assertEquals("sub-1", update.second[0])
                        assertEquals("停用", update.second[1])
                    }
                    stub.subscriptionLockStatus = "停用"
                    rawRequest(vertx, port, HttpMethod.PATCH, "$SUBSCRIPTION_PATH/sub-1/status", """{"status":"停用"}""")
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "同状态重复提交必须 400: $payload")
                        assertEquals("invalid status transition: 停用 -> 停用", payload.getString("error"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `订阅不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.subscriptionLockStatus = null
        withServer(vertx, userId = "cashier-1") { port ->
            rawRequest(vertx, port, HttpMethod.PATCH, "$SUBSCRIPTION_PATH/sub-x/status", """{"status":"停用"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(404, status, "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("update") })
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PACKAGE_PATH = "/healthcare/v1/child-service-packages"
        const val SUBSCRIPTION_PATH = "/healthcare/v1/child-package-subscriptions"
        const val PACKAGE_BODY =
            """{"code":"PKG-CHILD-001","name":"儿保基础套餐","price":"199.00",""" +
                """"items":[{"item_code":"FEE-001","item_name":"生长发育评估","quantity":3}]}"""
        const val SUBSCRIPTION_BODY = """{"patient_id":"child-1","package_id":"pkg-1"}"""
    }

    /**
     * 用 mockk 桩驱动原生 SQL：`withTransaction` 直接执行 lambda（用 `conn`），
     * 按 SQL 形状返回可配置的行；`FOR UPDATE` 行用于订阅状态机。
     */
    private class PackageDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val conn: SqlConnection = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val executedSql = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        var patientRow: Row? = childRow()
        var packageRow: Row? = null
        var codeTaken: Boolean = false
        var packageCount: Long = 0
        var packageListRows: List<Row> = emptyList()
        var subscriptionRow: Row? = null
        var subscriptionCount: Long = 0
        var subscriptionListRows: List<Row> = emptyList()
        var subscriptionLockStatus: String? = null

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
            patientRow = childRow()
            packageRow = null
            codeTaken = false
            packageCount = 0
            packageListRows = emptyList()
            subscriptionRow = null
            subscriptionCount = 0
            subscriptionListRows = emptyList()
            subscriptionLockStatus = null
            lastSql = ""
        }

        private fun route(sql: String): RowSet<Row> = when {
            sql.contains("from healthcare.patients") ->
                patientRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("child_service_packages where code =") ->
                if (codeTaken) rowSet(mockRow(mapOf("id" to "other-pkg"))) else rowSet()
            sql.startsWith("insert into healthcare.child_service_packages") -> rowSet()
            sql.startsWith("update healthcare.child_service_packages") -> rowSet()
            sql.contains("select count(*) as total from healthcare.child_service_packages") ->
                rowSet(mockRow(mapOf("total" to packageCount)))
            sql.contains("from healthcare.child_service_packages") && sql.contains("order by") ->
                rowSet(*packageListRows.toTypedArray())
            sql.contains("from healthcare.child_service_packages") ->
                packageRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("select count(*) as total from healthcare.child_package_subscriptions") ->
                rowSet(mockRow(mapOf("total" to subscriptionCount)))
            sql.contains("from healthcare.child_package_subscriptions") && sql.contains("for update") ->
                subscriptionLockStatus?.let { rowSet(mockRow(mapOf("status" to it))) } ?: rowSet()
            sql.startsWith("insert into healthcare.child_package_subscriptions") -> rowSet()
            sql.startsWith("update healthcare.child_package_subscriptions") -> rowSet()
            sql.contains("from healthcare.child_package_subscriptions") && sql.contains("order by") ->
                rowSet(*subscriptionListRows.toTypedArray())
            sql.contains("from healthcare.child_package_subscriptions") ->
                subscriptionRow?.let { rowSet(it) } ?: rowSet()
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

private fun childRow(): Row = mockRow(
    mapOf("id" to "child-1", "person_type" to "儿童", "birth_date" to ChildHealth.today()),
)

private fun packageRow(
    id: String = "pkg-1",
    code: String = "PKG-CHILD-001",
    name: String = "儿保基础套餐",
    price: String = "199.00",
    status: String = "启用",
): Row = mockRow(
    mapOf(
        "id" to id,
        "code" to code,
        "name" to name,
        "price" to BigDecimal(price),
        "items" to JsonArray()
            .add(JsonObject().put("item_code", "FEE-001").put("item_name", "生长发育评估").put("quantity", 3)),
        "status" to status,
        "metadata" to null,
        "created_by" to "cashier-1",
        "created_at" to OffsetDateTime.now(),
        "updated_at" to OffsetDateTime.now(),
    ),
)

private fun subscriptionRow(
    id: String = "sub-1",
    amount: String = "199.00",
    paid: String = "0",
    used: String = "0",
    status: String = "启用",
): Row = mockRow(
    mapOf(
        "id" to id,
        "patient_id" to "child-1",
        "package_id" to "pkg-1",
        "package_code" to "PKG-CHILD-001",
        "package_name" to "儿保基础套餐",
        "purchased_at" to OffsetDateTime.now(),
        "amount" to BigDecimal(amount),
        "paid_amount" to BigDecimal(paid),
        "used_amount" to BigDecimal(used),
        "status" to status,
        "metadata" to null,
        "operator" to "cashier-1",
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
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? java.time.LocalDate }
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
