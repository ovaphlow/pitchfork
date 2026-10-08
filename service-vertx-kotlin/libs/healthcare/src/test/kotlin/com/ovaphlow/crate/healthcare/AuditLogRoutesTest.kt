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
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime

/**
 * 054 F3 审计查询路由测试（**不访问数据库**）：`GET /healthcare/v1/audit-logs`。
 *
 * 锁四件事：
 *  1. 空列表 `{records:[],meta:{total:0}}`；
 *  2. 过滤参数全部**参数化绑定**（`actor` 精确 / `method` 大写化精确 / `path` 前缀 LIKE / `from`/`to` 闭区间）；
 *  3. 非法时间与 `from > to` → 400，且**不触库**；
 *  4. 行 → JSON 形状（`status_code`/`resource_id`/`occurred_at`/`metadata`）。
 *
 * 门禁（**全系统第一条受门禁的读路由**：无 `access:manage` → 403）**不在本文件覆盖**：
 * 本文件是模块级 DB-free 测试，不经过集中式门禁。请求级门禁断言见
 * `apps/aceso/src/test/kotlin/com/ovaphlow/crate/aceso/AuditLogAccessTest.kt`
 * （生产同款 `buildApiRouter` + 桩权限客户端：无码 403 + `required_permission`、有码 200 空页、
 * 未认证 401、门禁 403 尝试由审计中间件入账）。
 */
@ExtendWith(VertxExtension::class)
class AuditLogRoutesTest {

    private val stub = AuditDatabaseStub()

    @BeforeEach
    fun reset() {
        stub.reset()
    }

    @Test
    fun `审计列表空结果返回 records 空数组与 total 0`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx) { port ->
            rawRequest(vertx, port, HttpMethod.GET, PATH, null)
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
    fun `审计过滤条件全部参数化绑定`(vertx: Vertx, ctx: VertxTestContext) {
        stub.count = 1
        stub.rows = listOf(auditRow())
        val from = "2026-01-01T00:00:00+08:00"
        val to = "2026-02-01T00:00:00+08:00"
        val encodedFrom = java.net.URLEncoder.encode(from, "UTF-8")
        val encodedTo = java.net.URLEncoder.encode(to, "UTF-8")
        val pathFilter = java.net.URLEncoder.encode("/crate-api/healthcare", "UTF-8")

        withServer(vertx) { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.GET,
                "$PATH?actor=u1&method=post&path=$pathFilter&from=$encodedFrom&to=$encodedTo&limit=10&offset=5",
                null,
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                assertEquals(1, payload.getJsonArray("records").size())
                assertEquals(1L, payload.getJsonObject("meta").getLong("total"))

                val countSql = stub.queries.first { it.contains("select count") }
                assertTrue(countSql.contains("actor = \$1"), "count SQL=$countSql")
                assertTrue(countSql.contains("method = \$2"), "count SQL=$countSql")
                assertTrue(countSql.contains("path like \$3"), "count SQL=$countSql")
                assertTrue(countSql.contains("occurred_at >= \$4"), "count SQL=$countSql")
                assertTrue(countSql.contains("occurred_at <= \$5"), "count SQL=$countSql")
                assertFalse(countSql.contains("u1"), "过滤值不得拼进 SQL 文本")

                val listQuery = stub.tuples.first { it.first.contains("limit") }
                assertEquals("u1", listQuery.second[0])
                assertEquals("POST", listQuery.second[1], "method 统一大写化")
                assertEquals("/crate-api/healthcare", listQuery.second[2])
                assertEquals(OffsetDateTime.parse(from), listQuery.second[3])
                assertEquals(OffsetDateTime.parse(to), listQuery.second[4])
                assertEquals(10, listQuery.second[5])
                assertEquals(5, listQuery.second[6])
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法时间或区间倒置 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx) { port ->
            rawRequest(vertx, port, HttpMethod.GET, "$PATH?from=not-a-time", null)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "非法 from 必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("ISO-8601"), "body=$payload")
                    }
                    rawRequest(vertx, port, HttpMethod.GET, "$PATH?from=2026-02-01T00:00:00%2B08:00&to=2026-01-01T00:00:00%2B08:00", null)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "from > to 必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("from must not be after to"), "body=$payload")
                        assertTrue(stub.queries.isEmpty(), "非法参数不得触库：${stub.queries}")
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `审计行映射为 054 契约形状`(vertx: Vertx, ctx: VertxTestContext) {
        stub.count = 1
        stub.rows = listOf(auditRow())
        withServer(vertx) { port ->
            rawRequest(vertx, port, HttpMethod.GET, PATH, null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "body=$payload")
                val record = payload.getJsonArray("records").getJsonObject(0)
                assertEquals("al-1", record.getString("id"))
                assertEquals("cashier-1", record.getString("actor"))
                assertEquals("POST", record.getString("method"))
                assertEquals("/crate-api/healthcare/v1/patients", record.getString("path"))
                assertEquals(403, record.getInteger("status_code"))
                assertEquals("p-1", record.getString("resource_id"))
                assertTrue(record.getString("occurred_at")!!.isNotBlank())
                assertFalse(record.containsKey("body"), "审计记录不得有请求体字段")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  脚手架
    // ========================================================================

    private companion object {
        const val PATH = "/healthcare/v1/audit-logs"
    }

    private class AuditDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var rows: List<Row> = emptyList()
        var count: Long = 0

        private var lastSql = ""

        init {
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { prepared.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                Future.succeededFuture(route(sql))
            }
            every { prepared.execute() } answers { Future.succeededFuture(route(lastSql)) }
        }

        fun reset() {
            queries.clear()
            tuples.clear()
            rows = emptyList()
            count = 0
            lastSql = ""
        }

        private fun route(sql: String): RowSet<Row> = when {
            sql.contains("select count") -> rowSet(mockRow(mapOf("total" to count)))
            sql.contains("from healthcare.audit_logs") -> rowSet(*rows.toTypedArray())
            else -> rowSet()
        }

        private fun record(sql: String) {
            lastSql = sql.lowercase().replace("\"", "")
            queries.add(lastSql)
        }
    }

    private fun <T> withServer(vertx: Vertx, block: (Int) -> Future<T>): Future<T> {
        val router = Router.router(vertx)
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

// ========================================================================
//  mockk 桩辅助（Kotlin 顶层 private 为文件可见，故本文件自带一份）
// ========================================================================

private fun auditRow(): Row = mockRow(
    mapOf(
        "id" to "al-1",
        "actor" to "cashier-1",
        "method" to "POST",
        "path" to "/crate-api/healthcare/v1/patients",
        "status_code" to 403,
        "resource_id" to "p-1",
        "occurred_at" to OffsetDateTime.parse("2026-01-15T10:00:00+08:00"),
        "metadata" to JsonObject().put("platform_admin", false),
    ),
)

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
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
