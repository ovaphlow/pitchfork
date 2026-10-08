package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.util.Collections

/**
 * 045 打卡路由**资源级权限选码**的嵌入式路由测试（**不连数据库、不连 Nexus**）。
 *
 * 被锁的行为（`TaskExecutionRoutes.create` 的 `permissionHandlerForTaskType` 分支）：
 * 1. 权限判定前先读执行记录的 `task_type`，并把**原始任务类型**交给 App 注入的工厂；
 * 2. 读不到（记录不存在 / 查询失败）时传 `null`，由 App 侧按最严处理；
 * 3. 认证中间件先于权限工厂执行；认证/权限任一拒绝都**不得进入业务处理器**；
 * 4. 每个请求各自按自己的执行记录选码（不做跨请求的中间件缓存，避免多 event loop 并发写）；
 * 5. 未注入工厂时打卡路由沿用固定权限中间件，且**不产生** task_type 查询（接线前行为不变）。
 *
 * 用 mockk 桩 `Pool`：命中 "join nursing.nursing_tasks" 的查询回一行 `task_type`，
 * 其余查询回空集（业务处理器读到 0 行 → 404，足以证明"业务处理器已进入"）。
 * 路由器按用例新建（端口 listen(0)）以保证中间件状态不跨用例。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionStatusPermissionWiringTest {

    companion object {
        private const val EXECUTION_ID = "01J5D3M00000000000000EXE01"
        private const val SUBJECT = "subject-nurse-1"
        private const val REHAB = "REHABILITATION"
    }

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private lateinit var client: HttpClient
    private val servers = mutableListOf<HttpServer>()

    /** 每个用例设置的桩状态：任务类型（null = 查不到）与"查库失败"。 */
    private var taskType: String? = REHAB
    private var lookupFails = false

    /** 工厂收到的 task_type（按调用顺序）与权限判定时看到的会话主体。 */
    private val factoryCalls: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val factorySubjects: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** 权限放行的任务类型集合：默认只放康复活动。 */
    private var allowedTaskTypes: Set<String?> = setOf(REHAB)

    /** 已执行的 normalized SQL（按执行顺序），用于断言"读没读库/进没进业务"。 */
    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeAll
    fun setup(vertx: Vertx) {
        pool = mockk<Pool>()
        prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } answers {
            executed.add(normalized(firstArg<String>()))
            prepared
        }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                lookupFails && sql.contains("join nursing.nursing_tasks") ->
                    Future.failedFuture<RowSet<Row>>(RuntimeException("db down"))
                sql.contains("join nursing.nursing_tasks") ->
                    Future.succeededFuture(if (taskType == null) rowSet() else rowSet(taskTypeRow(taskType!!)))
                // 业务处理器 `get(id)`：0 行 → NotFoundException → 404（证明业务处理器已进入）
                else -> Future.succeededFuture(rowSet())
            }
        }
        client = vertx.createHttpClient()
    }

    @AfterAll
    fun teardown() {
        client.close()
    }

    @AfterEach
    fun closeServers() {
        servers.forEach { it.close() }
        servers.clear()
    }

    @BeforeEach
    fun reset() {
        taskType = REHAB
        lookupFails = false
        allowedTaskTypes = setOf(REHAB)
        factoryCalls.clear()
        factorySubjects.clear()
        executed.clear()
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `康复活动执行：原始 task_type 交给权限工厂，放行后进入业务处理器`(vertx: Vertx, ctx: VertxTestContext) {
        resourcePort(vertx).compose { port -> status(vertx, port) }.onSuccess { (code, body) ->
            ctx.verify {
                assertEquals(listOf(REHAB), factoryCalls, "工厂必须拿到执行记录的原始 task_type")
                assertEquals(listOf(SUBJECT), factorySubjects, "认证中间件必须先跑，权限判定才拿得到会话主体")
                assertEquals(404, code, "权限放行后应进入业务处理器并因记录不存在回 404；实际 body=$body")
                assertTrue(
                    executed.any { it.contains("join nursing.nursing_tasks") },
                    "权限判定前必须先读 task_type，实际执行: $executed",
                )
                assertTrue(
                    executed.any { it.contains("from nursing.nursing_task_executions") && !it.contains("join") },
                    "权限放行后必须真的进入业务处理器（读执行记录），实际执行: $executed",
                )
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非康复任务类型：工厂拿到的是任务表里的原值`(vertx: Vertx, ctx: VertxTestContext) {
        taskType = "MEDICATION"
        allowedTaskTypes = setOf("MEDICATION")

        resourcePort(vertx).compose { port -> status(vertx, port) }.onSuccess { (code, body) ->
            ctx.verify {
                assertEquals(listOf("MEDICATION"), factoryCalls, "任务类型不得被路由提前折叠成布尔量")
                assertEquals(404, code, "放行后进入业务处理器；实际 body=$body")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `权限工厂拒绝：403 且不得进入业务处理器`(vertx: Vertx, ctx: VertxTestContext) {
        allowedTaskTypes = emptySet()

        resourcePort(vertx).compose { port -> status(vertx, port) }.onSuccess { (code, body) ->
            ctx.verify {
                assertEquals(403, code, "权限不足必须 403；实际 body=$body")
                assertEquals(listOf(REHAB), factoryCalls)
                assertFalse(
                    executed.any { it.contains("from nursing.nursing_task_executions") && !it.contains("join") },
                    "被拒绝的请求不得读执行记录（更不得写），实际执行: $executed",
                )
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `查不到 task_type：按 null 交给工厂（最严处理）`(vertx: Vertx, ctx: VertxTestContext) {
        taskType = null
        allowedTaskTypes = setOf(null)

        resourcePort(vertx).compose { port -> status(vertx, port) }.onSuccess { (code, body) ->
            ctx.verify {
                assertEquals(listOf<String?>(null), factoryCalls, "读不到任务类型必须传 null，由 App 侧按最严处理")
                assertEquals(404, code, "放行后进入业务处理器；实际 body=$body")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `查询失败：同样按 null 交给工厂，不静默放行`(vertx: Vertx, ctx: VertxTestContext) {
        lookupFails = true
        allowedTaskTypes = setOf(null)

        resourcePort(vertx).compose { port -> status(vertx, port) }.onSuccess { (code, body) ->
            ctx.verify {
                assertEquals(listOf<String?>(null), factoryCalls, "查库失败必须回落成\"未知任务类型\"")
                assertEquals(1, factorySubjects.size, "未知任务类型仍要过一次权限判定，不是无条件放行")
                assertEquals(404, code, "最严码通过后才进业务处理器；实际 body=$body")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `连续两次请求各自按自己的记录选码`(vertx: Vertx, ctx: VertxTestContext) {
        resourcePort(vertx)
            .compose { port ->
                status(vertx, port).compose { status(vertx, port) }
            }.onSuccess { (code, body) ->
                ctx.verify {
                    assertEquals(
                        listOf(REHAB, REHAB),
                        factoryCalls,
                        "每个请求都必须重新按 task_type 选码，不得粘住上一个请求的判定",
                    )
                    assertEquals(404, code, "实际 body=$body")
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `未注入工厂：沿用固定权限中间件且不查 task_type（接线前行为）`(vertx: Vertx, ctx: VertxTestContext) {
        fixedPort(vertx).compose { port -> status(vertx, port) }.onSuccess { (code, body) ->
            ctx.verify {
                assertEquals(403, code, "固定权限中间件拒绝；实际 body=$body")
                assertTrue(factoryCalls.isEmpty(), "未注入工厂时不得调用工厂")
                assertFalse(
                    executed.any { it.contains("join nursing.nursing_tasks") },
                    "旧路径不得新增 task_type 查询，实际执行: $executed",
                )
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `认证中间件先于权限判定：无会话时 401 且不查 task_type`(vertx: Vertx, ctx: VertxTestContext) {
        resourcePort(vertx)
            .compose { port -> status(vertx, port, subject = null) }
            .onSuccess { (code, body) ->
                ctx.verify {
                    assertEquals(401, code, "无会话必须 401；实际 body=$body")
                    assertTrue(factoryCalls.isEmpty(), "认证不过时不得进行权限判定")
                    assertTrue(executed.isEmpty(), "认证不过时不得读库，实际执行: $executed")
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 045 资源级分支：每个用例新建路由器（权限中间件缓存不跨用例）。 */
    private fun resourcePort(vertx: Vertx): Future<Int> =
        start(
            vertx,
            TaskExecutionRoutes.create(
                vertx,
                pool,
                authHandler = testAuthHandler(),
                permissionHandlerForTaskType = { type -> factoryHandler(type) },
            ),
        )

    /** 接线前分支：只注入固定权限中间件。 */
    private fun fixedPort(vertx: Vertx): Future<Int> =
        start(
            vertx,
            TaskExecutionRoutes.create(
                vertx,
                pool,
                authHandler = testAuthHandler(),
                permissionHandler = fixedPermissionHandler(),
            ),
        )

    private fun start(vertx: Vertx, router: Router): Future<Int> =
        vertx.createHttpServer().requestHandler(router).listen(0).map { server ->
            servers.add(server)
            server.actualPort()
        }

    /** 测试认证中间件：`X-Test-Subject` 存在则写入 ctx userId（与生产 IDP 会话处理器同形）。 */
    private fun testAuthHandler(): Handler<RoutingContext> =
        Handler { ctx ->
            val subject = ctx.request().getHeader("X-Test-Subject")
            if (subject.isNullOrBlank()) {
                ctx.response().setStatusCode(401).putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("error", "authentication required").encode())
            } else {
                ctx.put("userId", subject)
                ctx.next()
            }
        }

    /** 假权限工厂：记录收到的 task_type，放行 [allowedTaskTypes]，否则按闸门形状 403。 */
    private fun factoryHandler(type: String?): Handler<RoutingContext> {
        factoryCalls.add(type)
        return Handler { ctx ->
            factorySubjects.add(ctx.get<String>("userId"))
            if (allowedTaskTypes.contains(type)) {
                ctx.next()
            } else {
                ctx.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                    .end(
                        JsonObject()
                            .put("error", "forbidden")
                            .put("required_permission", "rehab:execute 或 nursing:execute")
                            .encode(),
                    )
            }
        }
    }

    /** 固定码分支的假中间件：一律 403（未注入工厂时的接线前行为）。 */
    private fun fixedPermissionHandler(): Handler<RoutingContext> =
        Handler { ctx ->
            ctx.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                .end(JsonObject().put("error", "forbidden").put("required_permission", "nursing:execute").encode())
        }

    private fun status(
        vertx: Vertx,
        port: Int,
        subject: String? = SUBJECT,
    ): Future<Pair<Int, JsonObject>> =
        client.request(HttpMethod.PATCH, port, "127.0.0.1", "/$EXECUTION_ID/status")
            .compose { request ->
                if (subject != null) request.putHeader("X-Test-Subject", subject)
                request.putHeader("Content-Type", "application/json")
                request.send(JsonObject().put("status", "COMPLETED").encode())
                    .compose { response ->
                        response.body().map { buffer -> Pair(response.statusCode(), JsonObject(buffer)) }
                    }
            }

    private fun taskTypeRow(value: String): Row =
        mockk(relaxed = true) {
            every { getValue("task_type") } returns value
        }

    private fun rowSet(vararg rows: Row): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")
}
