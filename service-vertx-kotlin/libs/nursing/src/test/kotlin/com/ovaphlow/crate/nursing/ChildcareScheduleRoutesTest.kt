package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.VertxException
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 C2：班级日程路由的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. `POST /childcare-class-schedules` → 201 且**一律落 `草稿`**（客户端 `status` 被忽略）；
 *     同时段冲突（部分唯一索引 `uq_childcare_class_schedules_slot`，含 cause 链包裹）→ **409**。
 *  2. `PATCH /childcare-class-schedules/:id/status`：草稿→{已发布,已取消}、已发布→已取消 合法；
 *     已取消 终态、已发布→草稿 跳步 → **409**；未知状态值 400；不存在 404。
 *  3. 列表空结果 `{records: [], meta: {total: 0}}`；筛选走 SQL 谓词。
 */
@ExtendWith(VertxExtension::class)
class ChildcareScheduleRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val insertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val statusTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var insertFailure: Throwable? = null
    private var found = true
    private var currentStatus = "草稿"
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        insertTuples.clear()
        statusTuples.clear()
        insertFailure = null
        found = true
        currentStatus = "草稿"
        countTotal = 0L
        listRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into nursing.nursing_childcare_class_schedules") -> {
                    insertTuples.add(firstArg())
                    insertFailure?.let { Future.failedFuture(it) } ?: Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("update nursing.nursing_childcare_class_schedules set status") -> {
                    statusTuples.add(firstArg())
                    currentStatus = firstArg<Tuple>().getString(0)
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_childcare_class_schedules s") ->
                    if (sql.contains("where s.id = ")) {
                        Future.succeededFuture(if (found) rowSet(scheduleRow()) else rowSet())
                    } else {
                        Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                    }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  排课
    // ========================================================================

    @Test
    fun `排课返回201且状态一律落草稿`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-class-schedules",
            JsonObject()
                .put("class_name", "小班A")
                .put("schedule_date", "2026-10-06")
                .put("time_slot", "09:00-10:00")
                .put("activity_name", "户外游戏")
                .put("status", "已发布"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "排课必须 201，实际: ${body.encode()}")
                assertEquals("草稿", body.getString("status"), "创建一律草稿（发布是显式动作）")
                assertEquals("trainer01", body.getString("operator"))

                val tuple = insertTuples.first()
                // 列序：0 id / 1 class_name / 2 schedule_date / 3 time_slot / 4 activity_name / 5 status / 6 operator
                assertEquals("小班A", tuple.getValue(1))
                assertEquals(LocalDate.parse("2026-10-06"), tuple.getValue(2))
                assertEquals("09:00-10:00", tuple.getValue(3))
                assertEquals("户外游戏", tuple.getValue(4))
                assertEquals("草稿", tuple.getValue(5), "客户端提交的 status 必须被忽略")
                assertEquals("trainer01", tuple.getValue(6))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `同时段冲突返回409`(vertx: Vertx, ctx: VertxTestContext) {
        insertFailure = PgException(
            "duplicate key value violates unique constraint \"uq_childcare_class_schedules_slot\"",
            "ERROR",
            "23505",
            "Key (class_name, schedule_date, time_slot)=(小班A, 2026-10-06, 09:00-10:00) already exists.",
        )

        post(vertx, "/nursing/v1/childcare-class-schedules", scheduleBody()) { status, body ->
            ctx.verify {
                assertEquals(409, status, "同时段冲突必须 409，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("同时段冲突"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `被cause链包裹的唯一冲突仍返回409`(vertx: Vertx, ctx: VertxTestContext) {
        insertFailure = VertxException(
            "insert failed",
            PgException("duplicate key value violates unique constraint", "ERROR", "23505", "duplicate"),
        )

        post(vertx, "/nursing/v1/childcare-class-schedules", scheduleBody()) { status, body ->
            ctx.verify {
                assertEquals(409, status, "被包裹的 23505 必须仍映射 409，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法时段返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-class-schedules",
            scheduleBody().put("time_slot", "12:30-13:30"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid time_slot"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `排课拒绝非管理员代填operator`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-class-schedules",
            scheduleBody().put("operator", "other"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${body.encode()}")
                assertEquals("operator cannot be delegated", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  状态流转
    // ========================================================================

    @Test
    fun `草稿发布为已发布`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "草稿"

        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-1/status", JsonObject().put("status", "已发布")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "草稿→已发布 必须成功，实际: ${body.encode()}")
                assertEquals("已发布", body.getString("status"))
                assertEquals("已发布", statusTuples.first().getValue(0))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已发布可取消`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已发布"

        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-1/status", JsonObject().put("status", "已取消")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "已发布→已取消 必须成功，实际: ${body.encode()}")
                assertEquals("已取消", body.getString("status"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `草稿可直接取消`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "草稿"

        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-1/status", JsonObject().put("status", "已取消")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "草稿→已取消 必须成功，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已取消为终态再流转返回409`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已取消"

        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-1/status", JsonObject().put("status", "已发布")) { status, body ->
            ctx.verify {
                assertEquals(409, status, "终态再流转必须 409，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid status transition"), body.encode())
                assertTrue(statusTuples.isEmpty(), "非法流转不得发 UPDATE")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已发布不得退回草稿`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已发布"

        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-1/status", JsonObject().put("status", "草稿")) { status, body ->
            ctx.verify {
                assertEquals(409, status, "已发布→草稿 非法，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未知状态值返回400`(vertx: Vertx, ctx: VertxTestContext) {
        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-1/status", JsonObject().put("status", "已归档")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid status"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `日程不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        found = false
        patch(vertx, "/nursing/v1/childcare-class-schedules/schedule-x/status", JsonObject().put("status", "已发布")) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  列表
    // ========================================================================

    @Test
    fun `日程列表空结果与筛选谓词`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/childcare-class-schedules?class_name=%E5%B0%8F%E7%8F%ADA&date=2026-10-06&status=%E8%8D%89%E7%A8%BF") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)

                val listSql = executed.last { it.contains("from nursing.nursing_childcare_class_schedules s") }
                assertTrue(listSql.contains("s.class_name = \$1"), listSql)
                assertTrue(listSql.contains("s.schedule_date = \$2"), listSql)
                assertTrue(listSql.contains("s.status = \$3"), listSql)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun scheduleBody(): JsonObject = JsonObject()
        .put("class_name", "小班A")
        .put("schedule_date", "2026-10-06")
        .put("time_slot", "09:00-10:00")
        .put("activity_name", "户外游戏")

    private fun scheduleRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "schedule-1"
        every { getString("class_name") } returns "小班A"
        every { getLocalDate("schedule_date") } returns LocalDate.parse("2026-10-06")
        every { getString("time_slot") } returns "09:00-10:00"
        every { getString("activity_name") } returns "户外游戏"
        every { getString("status") } returns currentStatus
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun post(vertx: Vertx, path: String, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, path, body).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun patch(vertx: Vertx, path: String, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PATCH, path, body).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun get(vertx: Vertx, path: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.GET, path, null).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun request(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "127.0.0.1", path)
            .compose { req ->
                req.putHeader("Content-Type", "application/json")
                if (body == null) req.send() else req.send(body.encode())
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer.toString())
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val root = Router.router(vertx)
        root.route("/nursing/v1/*").handler { rctx ->
            rctx.put("userId", "trainer01")
            rctx.next()
        }
        root.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool))
        return vertx.createHttpServer().requestHandler(root).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { Unit } }
        }
    }

    private fun record(sql: String) {
        executed.add(normalized(sql))
    }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    private fun rowSet(vararg rows: Row, rowCount: Int = rows.size): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { rowCount() } returns rowCount
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }
}
