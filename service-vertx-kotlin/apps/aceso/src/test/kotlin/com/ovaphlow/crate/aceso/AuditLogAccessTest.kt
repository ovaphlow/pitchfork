package com.ovaphlow.crate.aceso

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 054 F3 审计读路由的**请求级门禁**断言（**不访问数据库、不连真实 IdP/Nexus**）。
 *
 * 架构评审 M4：`AuditLogRoutesTest` 是模块级 DB-free 测试，**不经过集中式门禁**，
 * 无法证明「无 `access:manage` → 403」。本类用**生产同款装配** [buildApiRouter]
 * （与 `ApiBodyHandlingTest` 同一基建）补齐该覆盖：
 *
 *  1. `GET /crate-api/healthcare/v1/audit-logs` 无 `access:manage` → **403** +
 *     `required_permission = access:manage`（前端据 `problem-message` 渲染中文），**且不触库**；
 *  2. 有 `access:manage` → **非 403**（200 空页 `{records:[],meta:{total:0}}`）；
 *  3. 未认证 → 401；
 *  4. **门禁 403 尝试由审计中间件入账**（中间件在门禁之前）：写路由被 403 时
 *     `healthcare.audit_logs` 落一条 `status_code = 403`（054 §8.6 ⑤）。
 */
@ExtendWith(VertxExtension::class)
class AuditLogAccessTest {

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private val servers = mutableListOf<io.vertx.core.http.HttpServer>()

    private val cookie = "identityd_session=audit-token"

    @BeforeEach
    fun setUp(vertx: Vertx) {
        this.vertx = vertx
        this.client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
    }

    @AfterEach
    fun tearDown() {
        client.close()
        servers.forEach { it.close() }
        servers.clear()
    }

    @Test
    fun `无 access_manage 访问审计查询 403 且不触库`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool, granted = listOf("billing:write"), subject = "audit-no-code")

        val (status, body) = send(port, HttpMethod.GET, AUDIT_PATH, cookie)

        assertEquals(403, status, "无 access:manage 必须 403：body=$body")
        val payload = JsonObject(body)
        assertEquals("forbidden", payload.getString("error"))
        assertEquals(
            "access:manage",
            payload.getString("required_permission"),
            "403 必须指明缺失的权限码（前端据此渲染中文）：body=$body",
        )
        assertTrue(
            stub.queries.none { it.contains("audit_logs") },
            "门禁拒绝不得触库：${stub.queries}",
        )
    }

    @Test
    fun `有 access_manage 访问审计查询非 403 且空页形状正确`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool, granted = listOf("access:manage"), subject = "audit-with-code")

        val (status, body) = send(port, HttpMethod.GET, AUDIT_PATH, cookie)

        assertTrue(status != 403, "有 access:manage 不得被门禁拒绝：status=$status body=$body")
        assertEquals(200, status, "审计查询应返回 200：body=$body")
        val payload = JsonObject(body)
        assertEquals(0, payload.getJsonArray("records").size())
        assertEquals(0L, payload.getJsonObject("meta").getLong("total"))
        assertTrue(
            stub.queries.any { it.contains("from healthcare.audit_logs") },
            "有码时必须真正走到审计查询：${stub.queries}",
        )
    }

    @Test
    fun `未认证访问审计查询 401`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool, granted = listOf("access:manage"), subject = "audit-unauth")

        val (status, body) = send(port, HttpMethod.GET, AUDIT_PATH, cookie = null)

        assertEquals(401, status, "未认证必须 401：body=$body")
        assertTrue(
            stub.queries.none { it.contains("audit_logs") },
            "未认证不得触库：${stub.queries}",
        )
    }

    @Test
    fun `门禁 403 尝试由审计中间件入账`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool, granted = listOf("billing:write"), subject = "audit-gate-403")

        val (status, body) = send(
            port,
            HttpMethod.POST,
            "/crate-api/healthcare/v1/base-vaccination-types",
            cookie,
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":1,"recommended_months":[0]}""",
        )
        assertEquals(403, status, "无 base_data:manage 必须 403：body=$body")

        // 审计落库发生在响应 endHandler（异步）：轮询等待，最多 3 秒
        val entry = awaitAuditEntry(stub, "/crate-api/healthcare/v1/base-vaccination-types")
        assertTrue(entry != null, "403 尝试必须入账（中间件在门禁之前）：${stub.queries}")
        assertEquals("POST", entry!![2], "审计记录 method")
        assertEquals(403, entry[4], "审计记录 status_code 必须为门禁的 403")
        assertEquals("audit-gate-403", entry[1], "审计记录 actor 取认证主体")
    }

    // ========================================================================
    //  装配（生产同款）
    // ========================================================================

    /**
     * 生产形态：`/crate-api/<module>` 子树 → [buildApiRouter]（与 `Main.kt` 同一函数）。
     *
     * [subject] 每次装配都不同：`SubjectPermissionClient` 按 subjectId 缓存权限（TTL 60s），
     * 同一主体先命中「无码」缓存后再断言「有码」会得到假 403；不同主体可彻底规避该缓存干扰
     * （本类每次调用都新建 `buildApiRouter` → 新客户端 → 空缓存，此处再显式隔离一层）。
     */
    private fun startAcesoShape(pool: Pool, granted: List<String>, subject: String = "audit-user"): Int {
        val apiRouter = buildApiRouter(
            vertx,
            pool,
            "http://127.0.0.1:${startStubIdp(subject)}",
            "http://127.0.0.1:${startStubNexus(granted, subject)}",
        )
        val mainRouter = Router.router(vertx)
        mainRouter.route("/crate-api/*").subRouter(apiRouter)
        val server = vertx.createHttpServer().requestHandler(mainRouter)
            .listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    /** 假 IdP：只应答 `GET .../session` → `{"subject_id":"<subject>"}`。 */
    private fun startStubIdp(subject: String = "audit-user"): Int {
        val server = vertx.createHttpServer().requestHandler { req ->
            if (req.path().endsWith("/session")) {
                req.response().putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("subject_id", subject).encode())
            } else {
                req.response().setStatusCode(404).end()
            }
        }.listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    /** 假 Nexus：只应答权限查询，授予 [granted] 指定的码（可精确控制有无 `access:manage`）。 */
    private fun startStubNexus(granted: List<String>, subject: String = "audit-user"): Int {
        val codes = JsonArray()
        granted.forEach { codes.add(it) }
        val server = vertx.createHttpServer().requestHandler { req ->
            if (req.path().endsWith("/subject-permissions")) {
                req.response().putHeader("Content-Type", "application/json")
                    .end(
                        JsonObject().put("subject_id", subject).put("permission_codes", codes).encode(),
                    )
            } else {
                req.response().setStatusCode(404).end()
            }
        }.listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    // ========================================================================
    //  HTTP 与桩
    // ========================================================================

    private fun send(
        port: Int,
        method: HttpMethod,
        path: String,
        cookie: String?,
        body: String? = null,
    ): Pair<Int, String> {
        val future: CompletableFuture<Pair<Int, String>> = client
            .request(method, port, "127.0.0.1", path)
            .compose { request ->
                if (cookie != null) request.putHeader("Cookie", cookie)
                if (body == null) {
                    request.send()
                } else {
                    request.putHeader("Content-Type", "application/json")
                    request.send(body)
                }
            }
            .compose { response ->
                if (response.statusCode() == 204) {
                    Future.succeededFuture(response.statusCode() to "")
                } else {
                    response.body().map { buffer: Buffer -> response.statusCode() to buffer.toString() }
                }
            }
            .toCompletionStage().toCompletableFuture()
        return try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError("$method $path 挂起：10 秒内无响应", e)
        }
    }

    /** 轮询等待审计落库（响应 `endHandler` 异步写入）。返回命中的审计插入绑定值，未命中返回 `null`。 */
    private fun awaitAuditEntry(stub: DatabaseStub, path: String): List<Any?>? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            stub.auditTuples.firstOrNull { it[3] == path }?.let { return it }
            Thread.sleep(50)
        }
        return stub.auditTuples.firstOrNull { it[3] == path }
    }

    /** 数据库桩：只记录 SQL 与绑定值；`count(*)` 返回 total=0，其余返回空集，绝不真实连接。 */
    private class DatabaseStub {
        val pool: Pool = mockk()
        private val prepared = mockk<PreparedQuery<RowSet<Row>>>()

        val queries = mutableListOf<String>()
        /** 审计 INSERT 的绑定值（列序：id, actor, method, path, status_code, resource_id, occurred_at, metadata）。 */
        val auditTuples = mutableListOf<List<Any?>>()

        private var lastSql = ""

        init {
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { prepared.execute(any<Tuple>()) } answers {
                val sql = lastSql
                val values = tupleValues(firstArg())
                if (sql.contains("audit_logs") && sql.startsWith("insert")) auditTuples.add(values)
                Future.succeededFuture(route(sql))
            }
            every { prepared.execute() } answers { Future.succeededFuture(route(lastSql)) }
        }

        private fun route(sql: String): RowSet<Row> =
            if (sql.contains("select count")) {
                rowSet(mockRow(mapOf("total" to 0L)))
            } else {
                rowSet()
            }

        private fun record(sql: String) {
            lastSql = sql.lowercase().replace("\"", "")
            queries.add(lastSql)
        }
    }

    private companion object {
        const val AUDIT_PATH = "/crate-api/healthcare/v1/audit-logs"
    }
}

// ========================================================================
//  mockk 桩辅助（Kotlin 顶层 private 为文件可见，故本文件自带一份）
// ========================================================================

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getJsonArray(any<String>()) } answers { values[firstArg<String>()] as? JsonArray }
    every { row.getJsonObject(any<String>()) } answers { values[firstArg<String>()] as? JsonObject }
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
