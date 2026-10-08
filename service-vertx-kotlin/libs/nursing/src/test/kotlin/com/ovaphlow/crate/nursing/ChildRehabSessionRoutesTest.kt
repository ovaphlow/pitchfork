package com.ovaphlow.crate.nursing

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
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 B2：训练执行（会话）路由的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. `POST /child-rehab-sessions` → 201；必须挂在 `CHILD_REHAB` 周期下的计划上；
 *     `operator` 取认证主体（客户端提交他人 → 042 fail-closed 400）；`completion_ratio` 缺省 1。
 *  2. 计划不存在 404；计划不属于儿童康复 400；缺 `plan_id`/日期非法/完成度越界 400。
 *  3. `GET /child-rehab-sessions` 空结果 `{records: [], meta: {total: 0}}`；
 *     传 `plan_id` 时附 `progress`（对全部会话汇总，不受分页影响）；
 *     `patient_id`/`from`/`to` 走跨表与区间过滤。
 */
@ExtendWith(VertxExtension::class)
class ChildRehabSessionRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val insertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var planFound = true
    private var planServiceType: String? = ChildRehabPeriodService.SERVICE_TYPE
    private var planStart: LocalDate? = LocalDate.parse("2026-10-05")
    private var planEnd: LocalDate? = LocalDate.parse("2026-10-11")
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()
    private var progressRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        insertTuples.clear()
        planFound = true
        planServiceType = ChildRehabPeriodService.SERVICE_TYPE
        planStart = LocalDate.parse("2026-10-05")
        planEnd = LocalDate.parse("2026-10-11")
        countTotal = 0L
        listRows = emptyList()
        progressRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("select pl.id, pl.period_id, pl.start_date, pl.end_date, pe.service_type") ->
                    Future.succeededFuture(if (planFound) rowSet(planRow()) else rowSet())
                sql.contains("insert into nursing.nursing_child_rehab_sessions") -> {
                    insertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("select session_date, completion_ratio from nursing.nursing_child_rehab_sessions") ->
                    Future.succeededFuture(rowSet(*progressRows.toTypedArray()))
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_child_rehab_sessions s") ->
                    if (sql.contains("where s.id = ")) {
                        Future.succeededFuture(rowSet(sessionRow()))
                    } else {
                        Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                    }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  打卡
    // ========================================================================

    @Test
    fun `按计划打卡返回201且操作人与缺省完成度由服务端派生`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject()
                .put("plan_id", "plan-1")
                .put("session_date", "2026-10-06")
                .put("performance", "配合良好")
                .put("note", "完成全部动作"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "打卡必须 201，实际: ${body.encode()}")
                assertEquals("plan-1", body.getString("plan_id"))
                assertEquals("patient-1", body.getString("patient_id"), "patient_id 由 join 计划→周期派生")
                assertEquals("2026-10-06", body.getString("session_date"))

                val tuple = insertTuples.first()
                // 列序：0 id / 1 plan_id / 2 session_date / 3 completion_ratio / 4 performance / 5 note / 6 operator
                assertEquals("plan-1", tuple.getValue(1))
                assertEquals(BigDecimal("1.0000"), tuple.getValue(3), "缺省完成度为 1")
                assertEquals("rehab01", tuple.getValue(6), "operator 取认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `打卡接受显式完成度`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("plan_id", "plan-1").put("session_date", "2026-10-06").put("completion_ratio", 0.5),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                assertEquals(BigDecimal("0.5000"), insertTuples.first().getValue(3))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        planFound = false
        post(vertx, JsonObject().put("plan_id", "plan-x").put("session_date", "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(404, status, "非法计划 id 必须 4xx，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("child rehab plan not found"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划不属于儿童康复返回400`(vertx: Vertx, ctx: VertxTestContext) {
        planServiceType = "ELDERLY_CARE"
        post(vertx, JsonObject().put("plan_id", "plan-1").put("session_date", "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("not a child rehab plan"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `缺plan_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, JsonObject().put("session_date", "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("plan_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `完成度越界返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("plan_id", "plan-1").put("session_date", "2026-10-06").put("completion_ratio", 1.5),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("completion_ratio"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `打卡拒绝非管理员代填operator`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("plan_id", "plan-1").put("session_date", "2026-10-06").put("operator", "other-user"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${body.encode()}")
                assertEquals("operator cannot be delegated", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  列表与进度
    // ========================================================================

    @Test
    fun `列表空结果返回records空与total0且不带progress`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-sessions?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                assertTrue(body.getJsonObject("progress") == null, "未指定 plan_id 时不返回 progress")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `按plan_id查询附进度汇总且不受分页影响`(vertx: Vertx, ctx: VertxTestContext) {
        countTotal = 1L
        listRows = listOf(sessionRow())
        // 计划 2026-10-05..2026-10-11（7 天）；全部会话：10-06 与 10-08 各一次
        progressRows = listOf(
            progressRow("2026-10-06", "1.0"),
            progressRow("2026-10-08", "0.5"),
        )

        get(vertx, "/nursing/v1/child-rehab-sessions?plan_id=plan-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                val progress = body.getJsonObject("progress")!!
                assertEquals("plan-1", progress.getString("plan_id"))
                assertEquals(2, progress.getInteger("session_count") ?: -1)
                assertEquals(2, progress.getInteger("completed_days") ?: -1)
                assertEquals(7, progress.getInteger("planned_days") ?: -1)
                assertEquals(0.75, progress.getDouble("avg_completion_ratio") ?: -1.0, 0.001)
                assertEquals(0.2857, progress.getDouble("progress_ratio") ?: -1.0, 0.0001, "2/7")
                assertEquals("2026-10-08", progress.getString("last_session_date"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表按patient_id与日期区间过滤`(vertx: Vertx, ctx: VertxTestContext) {
        get(
            vertx,
            "/nursing/v1/child-rehab-sessions?plan_id=plan-1&patient_id=patient-1&from=2026-10-01&to=2026-10-07",
        ) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                val listSql = executed.last { it.contains("from nursing.nursing_child_rehab_sessions s") }
                assertTrue(listSql.contains("s.plan_id = \$1"), listSql)
                assertTrue(listSql.contains("pe.patient_id = \$2"), "patient_id 必须走跨表 join：$listSql")
                assertTrue(listSql.contains("s.session_date >= \$3"), listSql)
                assertTrue(listSql.contains("s.session_date <= \$4"), listSql)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法from日期返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-sessions?from=2026/10/01") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("from"), body.encode())
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun planRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "plan-1"
        every { getString("period_id") } returns "period-1"
        every { getLocalDate("start_date") } returns planStart
        every { getLocalDate("end_date") } returns planEnd
        every { getString("service_type") } returns planServiceType
    }

    private fun sessionRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "session-1"
        every { getString("plan_id") } returns "plan-1"
        every { getString("patient_id") } returns "patient-1"
        every { getLocalDate("session_date") } returns LocalDate.parse("2026-10-06")
        every { getBigDecimal("completion_ratio") } returns BigDecimal("1.0000")
        every { getString("performance") } returns "配合良好"
        every { getString("note") } returns null
        every { getString("operator") } returns "rehab01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    private fun progressRow(date: String, ratio: String): Row = mockk(relaxed = true) {
        every { getLocalDate("session_date") } returns LocalDate.parse(date)
        every { getBigDecimal("completion_ratio") } returns BigDecimal(ratio)
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun post(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/nursing/v1/child-rehab-sessions", body)
                .map { (status, response) -> handler(status, response) }
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

    /** 模拟 App 装配层的认证总闸：先写入 `userId` 再交给护理模块路由。 */
    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val root = Router.router(vertx)
        root.route("/nursing/v1/*").handler { rctx ->
            rctx.put("userId", "rehab01")
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
