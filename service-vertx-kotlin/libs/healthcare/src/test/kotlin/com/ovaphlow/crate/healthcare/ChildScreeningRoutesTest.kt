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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.function.Function as JavaFunction

/**
 * 053 A2 发育筛查路由接线测试（**不访问数据库**）。
 *
 * 锁四件事：
 *  1. 写路由 `userId(ctx)` 401 兜底（App 全局认证总闸之外的保底）；
 *  2. 未知 `scale_code` 在**进库前**由 A1 引擎拒绝 → 400，且不产生 INSERT；
 *  3. 缺项 / 非法分值在进库前由 A1 引擎拒绝 → 400，且不产生 INSERT；
 *  4. `GET /child-developmental-screenings` 缺 `patient_id` 由服务校验 → 400（不访库）。
 *
 * 用 mockk 桩驱动 `Pool.withTransaction` / `preparedQuery`：儿童档案查询返回固定行，
 * 其余 SQL 返回空集；引擎校验失败时事务在 INSERT 之前失败。**不引用 jOOQ**。
 *
 * 评分引擎本身的完整语义由 `DevelopmentalScoringTest`（A1）覆盖；声明与集中式门禁接线由
 * `apps/aceso` 的 `DeclaredPermissionsTest` 覆盖。
 */
@ExtendWith(VertxExtension::class)
class ChildScreeningRoutesTest {

    private val stub = ScreeningDatabaseStub()

    @BeforeEach
    fun resetTraffic() {
        stub.reset()
    }

    @Test
    fun `筛查写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","scale_code":"CHILD_DEV_PLACEHOLDER","item_scores":{}}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(401, status, "未认证必须 401（写路由兜底不得裸奔）: $payload")
                    assertEquals("authentication required", payload.getString("error"))
                    assertTrue(stub.inserted().isEmpty(), "未认证请求不得进库")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `未知量表在进库前被引擎拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","scale_code":"NOT_A_SCALE","item_scores":{"GM_HEAD_CONTROL":2}}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "未知量表必须 400，不得静默落库: $payload")
                    assertTrue(payload.getString("error")?.contains("unknown scale_code") == true)
                    assertTrue(stub.inserted().isEmpty(), "被引擎拒绝的请求不得进库: ${stub.executedSql}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `缺项在进库前被引擎拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        // 0 月龄适龄条目为 GM_HEAD_CONTROL / FM_GRASP / LA_VOICE；这里只交一项 → 缺项。
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","scale_code":"CHILD_DEV_PLACEHOLDER","item_scores":{"GM_HEAD_CONTROL":2}}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "缺项必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("missing answers") == true)
                    assertTrue(stub.inserted().isEmpty(), "被引擎拒绝的请求不得进库: ${stub.executedSql}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `非法分值在进库前被引擎拒绝 400`(vertx: Vertx, ctx: VertxTestContext) {
        // LA_VOICE 的合法取值为 0/1/2；提交 9 → 非法分值。
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                PATH,
                """{"patient_id":"p1","scale_code":"CHILD_DEV_PLACEHOLDER","item_scores":{"GM_HEAD_CONTROL":2,"FM_GRASP":2,"LA_VOICE":9}}""",
            ).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "非法分值必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("invalid score") == true)
                    assertTrue(stub.inserted().isEmpty(), "被引擎拒绝的请求不得进库: ${stub.executedSql}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `列表缺 patient_id 由服务校验返回 400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, PATH, null).map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "patient_id 必填，缺失必须 400: $payload")
                    assertEquals("patient_id is required", payload.getString("error"))
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/child-developmental-screenings"
    }

    /**
     * 用 mockk 桩驱动原生 SQL：`withTransaction` 直接执行 lambda（用 `conn`），
     * `healthcare.patients` 查询返回固定儿童行，其余返回空集。
     */
    private class ScreeningDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val conn: SqlConnection = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val executedSql = mutableListOf<String>()
        private var lastSql = ""

        private val child: Row = mockk(relaxed = true) {
            every { getString("person_type") } returns ChildHealth.PERSON_TYPE_CHILD
            every { getLocalDate("birth_date") } returns ChildHealth.today()
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
                if (lastSql.contains("from healthcare.patients")) {
                    Future.succeededFuture(rowSet(child))
                } else {
                    Future.succeededFuture(rowSet())
                }
            }
            every { prepared.execute() } answers { Future.succeededFuture(rowSet()) }
        }

        fun reset() {
            executedSql.clear()
            lastSql = ""
        }

        fun inserted(): List<String> =
            executedSql.filter { it.contains("insert into healthcare.developmental_screenings") }

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
