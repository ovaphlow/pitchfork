package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
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
import java.util.function.Function as JavaFunction

/**
 * 053 B2 健康问题转诊路由接线测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. 纯状态机：合法/非法流转、同状态重复提交、终态不可再转（无 DB）；
 *  2. 写路由 `userId(ctx)` 401 兜底（POST / PATCH）；
 *  3. 非法 `source_type` / `referral_type` 在**进库前**被服务拒绝 → 400，且完全不触库；
 *  4. 非法状态流转在服务层（读当前状态后）被拒绝 → 400（桩返回终态行）。
 *
 * 用 mockk 桩驱动 `Pool.withTransaction` / `preparedQuery`；**不引用 jOOQ**。
 * 集中式权限声明与门禁接线由 `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildReferralRoutesTest {

    private val stub = ReferralDatabaseStub()

    @BeforeEach
    fun resetTraffic() {
        stub.reset()
    }

    // ========================================================================
    //  状态机（纯逻辑）
    // ========================================================================

    @Test
    fun `状态机仅允许白名单内的流转`() {
        assertEquals(
            setOf("待转诊", "已转诊", "已完成", "已取消"),
            ChildReferralService.statuses,
            "状态白名单必须与 V533 注释一致",
        )
        assertEquals("待转诊", ChildReferralService.createStatus, "创建固定落在待转诊")
        assertEquals(
            ChildReferralService.statuses,
            ChildReferralService.transitions.keys,
            "状态机的每个状态（含终态）都必须有出口定义",
        )

        val legal = listOf(
            "待转诊" to "已转诊",
            "待转诊" to "已取消",
            "已转诊" to "已完成",
        )
        legal.forEach { (from, to) ->
            assertTrue(
                ChildReferralService.canTransition(from, to),
                "$from -> $to 是合法流转",
            )
        }

        val illegal = listOf(
            "待转诊" to "已完成",
            "待转诊" to "待转诊",
            "已转诊" to "已取消",
            "已转诊" to "已转诊",
            "已完成" to "已转诊",
            "已完成" to "已取消",
            "已完成" to "已完成",
            "已取消" to "已转诊",
            "已取消" to "已完成",
            "已取消" to "已取消",
        )
        illegal.forEach { (from, to) ->
            assertFalse(
                ChildReferralService.canTransition(from, to),
                "$from -> $to 必须被拒绝（跨级 / 同状态 / 终态再转）",
            )
        }
        assertFalse(
            ChildReferralService.canTransition("不存在", "已转诊"),
            "未知来源状态必须按最严处理",
        )
    }

    // ========================================================================
    //  路由：认证兜底
    // ========================================================================

    @Test
    fun `转诊写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","source_type":"手动","problem_summary":"发热","referral_type":"院内转诊"}""",
            ).compose { (status, payload) ->
                ctx.verify {
                    assertEquals(401, status, "POST 未认证必须 401（写路由兜底不得裸奔）: $payload")
                    assertEquals("authentication required", payload.getString("error"))
                    assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                }
                rawRequest(
                    vertx,
                    port,
                    HttpMethod.PATCH,
                    "$PATH/ref-1/status",
                    """{"status":"已转诊"}""",
                )
            }.map { (status, payload) ->
                ctx.verify {
                    assertEquals(401, status, "PATCH 未认证必须 401: $payload")
                    assertEquals("authentication required", payload.getString("error"))
                    assertTrue(stub.executedSql.isEmpty(), "未认证请求不得触库")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  路由：进库前拒绝
    // ========================================================================

    @Test
    fun `非法 source_type 在进库前被拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","source_type":"入院","problem_summary":"发热","referral_type":"院内转诊"}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "非法 source_type 必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("invalid source_type") == true)
                    assertTrue(stub.executedSql.isEmpty(), "被拒绝的请求不得触库: ${stub.executedSql}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `非法 referral_type 在进库前被拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","source_type":"手动","problem_summary":"发热","referral_type":"随便转"}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "非法 referral_type 必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("invalid referral_type") == true)
                    assertTrue(stub.executedSql.isEmpty(), "被拒绝的请求不得触库: ${stub.executedSql}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `非法状态流转在服务层被拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        // 桩返回终态「已完成」；再转「已转诊」必须被状态机拒绝。
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.PATCH,
                "$PATH/ref-1/status",
                """{"status":"已转诊"}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "终态再转必须 400: $payload")
                    assertEquals(
                        "invalid status transition: 已完成 -> 已转诊",
                        payload.getString("error"),
                    )
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/child-referrals"
    }

    /**
     * 用 mockk 桩驱动原生 SQL：`withTransaction` 直接执行 lambda（用 `conn`），
     * `healthcare.patients` 查询返回固定儿童行，`FOR UPDATE` 状态读返回终态「已完成」，
     * 其余返回空集。
     */
    private class ReferralDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val conn: SqlConnection = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val executedSql = mutableListOf<String>()
        private var lastSql = ""

        private val child: Row = mockk(relaxed = true) {
            every { getString("person_type") } returns ChildHealth.PERSON_TYPE_CHILD
            every { getLocalDate("birth_date") } returns ChildHealth.today()
        }

        private val terminal: Row = mockk(relaxed = true) {
            every { getString("status") } returns "已完成"
        }

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { pool.withTransaction<JsonObject>(any()) } answers {
                firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(conn)
            }
            every { prepared.execute(any<Tuple>()) } answers {
                when {
                    lastSql.contains("from healthcare.patients") ->
                        Future.succeededFuture(rowSet(child))
                    lastSql.contains("from healthcare.child_referrals") && lastSql.contains("for update") ->
                        Future.succeededFuture(rowSet(terminal))
                    else -> Future.succeededFuture(rowSet())
                }
            }
            every { prepared.execute() } answers { Future.succeededFuture(rowSet()) }
        }

        fun reset() {
            executedSql.clear()
            lastSql = ""
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

/** 构造一个仅含给定行的 `RowSet<Row>`（mockk 桩，不连库）。 */
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
