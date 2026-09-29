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
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.function.Function as JavaFunction

/**
 * 床位主数据路由接线测试（030 §4.5 W8，**不访问数据库**）。
 *
 * 本类只证明「路由真的挂上了」与「既有 respondFailure 的错误映射对床位生效」：
 *  - `/healthcare/v1/beds` 与 `/beds/:id` 段数不同，不被其它泛型路由吞掉；
 *  - 写路由的 `userId(ctx)` 401 兜底（App 全局认证总闸之外的保底）；
 *  - 参数校验 400、不存在 404。服务层行为由 `BedServiceTest` 的 17 例覆盖。
 *
 * 桩只返回空结果集 + `count(*) = 0`，故全部用例都不需要真实数据库。
 */
@ExtendWith(VertxExtension::class)
class BedRoutesTest {

    @Test
    fun `列表路由已接线且返回空页`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = EmptyBedStub()
        withServer(vertx, stub, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/beds", null).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "列表路由必须命中: $body")
                    assertEquals(0, body.getJsonArray("records").size(), "空列表必须返回 records: []")
                    assertEquals(0L, body.getJsonObject("meta").getLong("total"))
                    assertTrue(
                        stub.queries.any { it.contains("count(*)") && it.contains("healthcare.beds") },
                        "必须命中床位表的 count 查询: ${stub.queries}",
                    )
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `详情不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = EmptyBedStub()
        withServer(vertx, stub, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/beds/does-not-exist", null).map { (status, body) ->
                ctx.verify {
                    assertEquals(404, status, "详情不存在必须 404: $body")
                    assertTrue(body.getString("error")?.contains("bed not found") == true)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `创建未认证返回401且不落库`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = EmptyBedStub()
        withServer(vertx, stub, userId = null) { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/healthcare/v1/beds",
                JsonObject().put("department", "东区").put("ward", "001").encode(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "未注入认证中间件必须 401 兜底: $body")
                    assertEquals("authentication required", body.getString("error"))
                    assertTrue(stub.queries.isEmpty(), "未认证请求不得触发任何 SQL: ${stub.queries}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `创建多余键返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = EmptyBedStub()
        withServer(vertx, stub, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/healthcare/v1/beds",
                JsonObject()
                    .put("department", "东区")
                    .put("ward", "001")
                    .put("metadata", JsonObject())
                    .encode(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "白名单外的键必须 400: $body")
                    assertTrue(body.getString("error")?.contains("unsupported") == true)
                    assertTrue(stub.queries.isEmpty(), "参数校验失败必须发生在任何 SQL 之前: ${stub.queries}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `创建非JSON对象请求体返回400而不是500`(vertx: Vertx, ctx: VertxTestContext) {
        // 030 评审 P2-1 收口：数组 / 字符串 / 畸形 JSON 必须 400，不得因 asJsonObject() 抛异常退化成 500
        val stub = EmptyBedStub()
        val payloads = listOf("[1,2,3]", "\"just-a-string\"", "{not-json")

        fun step(index: Int): Future<Unit> {
            if (index >= payloads.size) return Future.succeededFuture()
            return withServer(vertx, stub, userId = "user-1") { port ->
                rawRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/beds", payloads[index]).map { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "非 JSON 对象体必须 400（${payloads[index]}）: $body")
                        assertEquals("body must be a JSON object", body.getString("error"))
                    }
                }
            }.compose { step(index + 1) }
        }

        step(0).onSuccess { ctx.completeNow() }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `状态流转非法值返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = EmptyBedStub()
        withServer(vertx, stub, userId = "user-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.PATCH,
                "/healthcare/v1/beds/bed-1/status",
                JsonObject().put("status", "悬挂").encode(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "非法状态值必须 400: $body")
                    assertTrue(body.getString("error")?.contains("status must be one of") == true)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `删除不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = EmptyBedStub()
        withServer(vertx, stub, userId = "user-1") { port ->
            rawRequest(vertx, port, HttpMethod.DELETE, "/healthcare/v1/beds/bed-1", null).map { (status, body) ->
                ctx.verify {
                    assertEquals(404, status, "删除不存在必须 404: $body")
                    assertTrue(body.getString("error")?.contains("bed not found") == true)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ─── 桩与请求辅助 ───────────────────────────────────────────────────

    /** 空结果集桩：`count(*)` 返回 total=0 的一行，其余查询返回空集；记录全部 SQL 以便断言「零 SQL」。 */
    private class EmptyBedStub {
        val queries = mutableListOf<String>()
        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                Future.succeededFuture(
                    if (lastSql.contains("count(*)")) bedRouteRowSet(bedRouteRow(mapOf("total" to 0L)))
                    else bedRouteRowSet(),
                )
            }
            every { pool.withTransaction<Any>(any()) } answers {
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any?>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            lastSql = sql.lowercase().replace("\"", "")
            queries.add(lastSql)
        }
    }

    private fun <T> withServer(
        vertx: Vertx,
        stub: EmptyBedStub,
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
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }
}

// ——— mock 基础设施（本文件私有；与 BedServiceTest / OrderMaterialBatchBindingTest 的 helper 互不影响） ———

private fun bedRouteRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun bedRouteRowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    return rs
}
