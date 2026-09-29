package com.ovaphlow.crate.aceso

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
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
import java.util.function.Function as JavaFunction

/**
 * P0 回归护栏（**不访问数据库、不连真实 IdP**）：带请求体的写请求必须穿过
 * 「默认拒绝认证总闸（异步）+ 模块子路由里的 BodyHandler」，并拿到响应。
 *
 * ## 为什么必须有这个护栏
 *
 * 2026-09-29 线上复现：费用项目新增 / 押金登记 / 健康监测保存三个写操作全部永久挂起
 * （按钮停在「创建中」，前端只剩 pending，服务端无异常、无日志、无数据库写入），
 * 而同一会话的 GET 全部正常（63–73ms）。
 *
 * 根因：`BodyHandler` 用固定 handler id 在 `RoutingContext` 上去重，只有首个生效。
 * 认证总闸（`apiAuthenticationGate` → `idpSessionAuthHandler`）是**异步**的，
 * 它让出事件循环后请求体已被读完；路由恢复时 `request.isEnded()` 已为 `true`，
 * 模块子路由里的 `BodyHandlerImpl` 会走「请求已结束」分支直接 `return`（不 `next()`），
 * 于是没有任何处理器写响应 —— 请求永久挂起。GET 无请求体所以不受影响。
 *
 * 024 的既有护栏（`DefaultAuthenticationTest`）用**手抄的镜像装配 + 同步假认证**，
 * 因此恰好漏掉了「真实装配 + 异步认证 + 真实带体写路由」这一组合。本类改为直接调用
 * **生产同款装配函数** [buildApiRouter]（`internal`，`Main.kt` 里被 `main()` 使用），
 * 只用 mockk 的 `Pool` 桩和本地假 IdP 顶掉数据库与 IdP，从而真正锁住该组合。
 *
 * 覆盖：用户报告的三个挂起端点（费用项目 / 押金 / 体征）+ 认证红线（未认证写请求 401
 * 且不落库）+ GET 不受影响。
 */
@ExtendWith(VertxExtension::class)
class ApiBodyHandlingTest {

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private val servers = mutableListOf<io.vertx.core.http.HttpServer>()

    private val authenticatedCookie = "identityd_session=guard-token"

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

    // ------------------------------------------------------------------
    // 1. P0 主线：三个被报告挂起的写端点都必须返回响应（且真正走到 SQL）
    // ------------------------------------------------------------------

    @Test
    fun `authenticated writes with a body answer instead of hanging`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool)

        // 费用项目新增：字典插入 + 201（P0 报告路径 1）
        val feeItem = send(
            port,
            HttpMethod.POST,
            "/crate-api/healthcare/v1/fee-items",
            authenticatedCookie,
            """{"category":"床位费","name":"床位费-标准间","unit_price":120.00}""",
        )
        assertEquals(201, feeItem.first, "费用项目新增不得挂起，实际 body=${feeItem.second}")
        assertTrue(
            stub.queries.any { it.contains("insert into") && it.contains("fee_items") },
            "费用项目新增必须走到 INSERT，实际 SQL=${stub.queries}",
        )

        // 押金登记（P0 报告路径 2）：encounter 不存在 → 404/400 都算合格，唯独不允许挂起
        val deposit = send(
            port,
            HttpMethod.POST,
            "/crate-api/healthcare/v1/encounters/01J0000000000000000000000/deposits",
            authenticatedCookie,
            """{"amount":5000.00}""",
        )
        assertTrue(deposit.first in 200..499, "押金登记不得挂起，实际 status=${deposit.first}")

        // 体征保存（P0 报告路径 3）：请求体是数组；同样只要求"有响应、不挂起"
        val vitalSigns = send(
            port,
            HttpMethod.POST,
            "/crate-api/healthcare/v1/vital-signs",
            authenticatedCookie,
            """[{"patient_id":"01J0000000000000000000000","type":"体温","value_numeric":36.8,"recorded_at":"2026-09-29T17:00:00+08:00"}]""",
        )
        assertTrue(vitalSigns.first in 200..499, "体征保存不得挂起，实际 status=${vitalSigns.first}")
    }

    @Test
    fun `authenticated writes with an empty body also answer`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool)

        // 显式空体（Content-Length: 0，浏览器/curl 对不带 body 的写请求就是这个形态）：
        // 业务处理器应给出 400（体征请求体必须是 JSON 数组），而不是永远不返回。
        val response = send(
            port,
            HttpMethod.POST,
            "/crate-api/healthcare/v1/vital-signs",
            authenticatedCookie,
            body = "",
        )
        assertEquals(400, response.first, "空体写请求不得挂起，实际 body=${response.second}")
    }

    // ------------------------------------------------------------------
    // 2. GET 不受影响（无请求体路径不能因为本次修复而改变）
    // ------------------------------------------------------------------

    @Test
    fun `authenticated reads keep working`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool)

        val list = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/fee-items", authenticatedCookie)
        assertEquals(200, list.first, "已认证 GET 必须仍 200，实际 body=${list.second}")
        assertEquals(0L, JsonObject(list.second).getJsonObject("meta").getLong("total"))
    }

    // ------------------------------------------------------------------
    // 3. 认证红线不因本次修复松动：未认证写请求 401，且不产生任何 SQL
    //    注意：请求体在总闸之前已被读入缓冲区（这是修复的前提），
    //    但未认证请求仍然在进入业务处理器之前被拦下。
    // ------------------------------------------------------------------

    @Test
    fun `unauthenticated writes are still rejected before any business handler`() {
        val stub = DatabaseStub()
        val port = startAcesoShape(stub.pool)

        val response = send(
            port,
            HttpMethod.POST,
            "/crate-api/healthcare/v1/fee-items",
            cookie = null,
            body = """{"category":"床位费","name":"匿名写入","unit_price":1}""",
        )

        assertEquals(401, response.first, "未认证写请求必须 401，实际 body=${response.second}")
        assertEquals("""{"error":"authentication required"}""", response.second)
        assertTrue(stub.queries.isEmpty(), "未认证写请求不得产生任何 SQL，实际=${stub.queries}")
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /** 生产形态：mainRouter(CORS) -> crate-api 子树 -> [buildApiRouter]（生产同款函数）。 */
    private fun startAcesoShape(pool: Pool): Int {
        val apiRouter = buildApiRouter(
            vertx,
            pool,
            "http://127.0.0.1:${startStubIdp()}",
            "http://127.0.0.1:1",
        )
        val mainRouter = Router.router(vertx)
        mainRouter.route("/crate-api/*").subRouter(apiRouter)
        val server = vertx.createHttpServer().requestHandler(mainRouter)
            .listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    /**
     * 假 IdP：只应答 `GET /crate-api/identity/v1/session` → `{"subject_id":"guard-user"}`。
     * 幂等且无状态，替换真实 IdP 只为去掉外部依赖，不放宽认证语义。
     */
    private fun startStubIdp(): Int {
        val server = vertx.createHttpServer().requestHandler { req ->
            if (req.path().endsWith("/session")) {
                req.response().putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("subject_id", "guard-user").encode())
            } else {
                req.response().setStatusCode(404).end()
            }
        }.listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        servers += server
        return server.actualPort()
    }

    // ------------------------------------------------------------------
    // HTTP 单次请求：整条链在事件循环上用 compose 串起来，只在最后 await 一次
    // （避免"等响应头"与"读响应体"两段 await 之间的竞态，见 DefaultAuthenticationTest 的说明）
    // ------------------------------------------------------------------

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
            throw AssertionError(
                "$method $path 挂起：10 秒内没有任何响应 —— 即 2026-09-29 P0 的复现形态" +
                    "（BodyHandler 必须在认证总闸之前读完请求体）",
                e,
            )
        }
    }

    // ------------------------------------------------------------------
    // 数据库桩：只记录 SQL 并返回空结果，绝不做任何真实连接
    // ------------------------------------------------------------------

    private class DatabaseStub {
        val queries = mutableListOf<String>()

        private val preparedQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val connection = mockk<SqlConnection>()

        /** count(*) 查询必须返回一行（服务端直接 next().getLong("total")），其余查询返回空结果。 */
        private val countRow: Row = mockk { every { getLong("total") } returns 0L }
        private val emptySet: RowSet<Row> = rowSetOf(emptyList())
        private val countSet: RowSet<Row> = rowSetOf(listOf(countRow))

        val pool: Pool = mockk<Pool>().also { pool ->
            recordOn(pool)
            recordOn(connection)
            every { preparedQuery.execute(any<Tuple>()) } answers {
                Future.succeededFuture(if (queries.last().contains("count(")) countSet else emptySet)
            }
            every { pool.withTransaction<Any>(any()) } answers {
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(connection)
            }
        }

        private fun recordOn(client: io.vertx.sqlclient.SqlClient) {
            every { client.preparedQuery(any<String>()) } answers {
                queries.add(firstArg<String>())
                preparedQuery
            }
            every { client.preparedQuery(any<String>(), any()) } answers {
                queries.add(firstArg<String>())
                preparedQuery
            }
            every { client.query(any<String>()) } answers {
                queries.add(firstArg<String>())
                preparedQuery
            }
        }

        private fun rowSetOf(rows: List<Row>): RowSet<Row> {
            val set = mockk<RowSet<Row>>()
            every { set.iterator() } answers {
                val iterator = mockk<RowIterator<Row>>()
                var index = 0
                every { iterator.hasNext() } answers { index < rows.size }
                every { iterator.next() } answers { rows[index++] }
                iterator
            }
            every { set.rowCount() } returns rows.size
            every { set.size() } returns rows.size
            return set
        }
    }
}
