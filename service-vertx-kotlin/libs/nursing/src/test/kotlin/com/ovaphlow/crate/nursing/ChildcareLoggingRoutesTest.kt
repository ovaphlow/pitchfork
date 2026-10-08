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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 C1：保育员活动日志与行为观测路由的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. `POST /childcare-activity-logs` → 201；`patient_id` 可空、`activity_date` 缺省业务当天、
 *     `participant_count` 缺省 0；`operator` 取认证主体；042 代填 → 400。
 *  2. `POST /childcare-behavior-observations` → 201；`level` 走 §5.8 白名单；儿童不存在 404。
 *  3. 两个列表空结果 `{records: [], meta: {total: 0}}`；筛选走 SQL 谓词
 *     （活动日志：patient_id/class_name/date；观测：patient_id/业务时区当日/level）。
 */
@ExtendWith(VertxExtension::class)
class ChildcareLoggingRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val activityTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val observationTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var countTotal = 0L
    private var activityListRows: List<Row> = emptyList()
    private var observationListRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        activityTuples.clear()
        observationTuples.clear()
        patientFound = true
        countTotal = 0L
        activityListRows = emptyList()
        observationListRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("select id from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(idRow()) else rowSet())
                sql.contains("insert into nursing.nursing_childcare_activity_logs") -> {
                    activityTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("insert into nursing.nursing_childcare_behavior_observations") -> {
                    observationTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_childcare_activity_logs a") ->
                    if (sql.contains("where a.id = ")) {
                        Future.succeededFuture(rowSet(activityRow()))
                    } else {
                        Future.succeededFuture(rowSet(*activityListRows.toTypedArray()))
                    }
                sql.contains("from nursing.nursing_childcare_behavior_observations o") ->
                    if (sql.contains("where o.id = ")) {
                        Future.succeededFuture(rowSet(observationRow()))
                    } else {
                        Future.succeededFuture(rowSet(*observationListRows.toTypedArray()))
                    }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  活动日志
    // ========================================================================

    @Test
    fun `登记活动日志返回201且日期与人数由服务端派生`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-activity-logs",
            JsonObject().put("class_name", "小班A").put("activity_name", "户外游戏"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "登记必须 201，实际: ${body.encode()}")
                assertEquals("小班A", body.getString("class_name"))
                assertNull(body.getString("patient_id"), "班级级活动 patient_id 可空")
                assertEquals("trainer01", body.getString("operator"), "operator 取认证主体")

                val tuple = activityTuples.first()
                // 列序：0 id / 1 patient_id / 2 class_name / 3 activity_date / 4 activity_name / 5 participant_count
                assertNull(tuple.getValue(1), "未提交 patient_id → NULL")
                assertEquals("小班A", tuple.getValue(2))
                assertEquals(ChildcareActivityLogService.today(), tuple.getValue(3), "缺省业务当天")
                assertEquals("户外游戏", tuple.getValue(4))
                assertEquals(0, tuple.getValue(5), "缺省参与人数 0")
                assertEquals("trainer01", tuple.getValue(7))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记活动日志支持个体儿童与显式日期人数`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-activity-logs",
            JsonObject()
                .put("patient_id", "patient-1")
                .put("class_name", "小班A")
                .put("activity_name", "感统训练")
                .put("activity_date", "2026-10-06")
                .put("participant_count", 12),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val tuple = activityTuples.first()
                assertEquals("patient-1", tuple.getValue(1))
                assertEquals(LocalDate.parse("2026-10-06"), tuple.getValue(3))
                assertEquals(12, tuple.getValue(5))
                assertTrue(
                    executed.any { it.contains("select id from healthcare.patients") },
                    "提交 patient_id 必须校验儿童存在：$executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记活动日志儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(
            vertx,
            "/nursing/v1/childcare-activity-logs",
            JsonObject().put("patient_id", "patient-x").put("class_name", "小班A").put("activity_name", "户外游戏"),
        ) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记活动日志缺班级返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "/nursing/v1/childcare-activity-logs", JsonObject().put("activity_name", "户外游戏")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("class_name is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记活动日志人数为负返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-activity-logs",
            JsonObject().put("class_name", "小班A").put("activity_name", "户外游戏").put("participant_count", -3),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("participant_count"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记活动日志拒绝非管理员代填operator`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-activity-logs",
            JsonObject().put("class_name", "小班A").put("activity_name", "户外游戏").put("operator", "other"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${body.encode()}")
                assertEquals("operator cannot be delegated", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `活动日志列表空结果与筛选谓词`(vertx: Vertx, ctx: VertxTestContext) {
        get(
            vertx,
            "/nursing/v1/childcare-activity-logs?patient_id=patient-1&class_name=%E5%B0%8F%E7%8F%ADA&date=2026-10-06",
        ) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)

                val listSql = executed.last { it.contains("from nursing.nursing_childcare_activity_logs a") }
                assertTrue(listSql.contains("a.patient_id = \$1"), listSql)
                assertTrue(listSql.contains("a.class_name = \$2"), "班级筛选：$listSql")
                assertTrue(listSql.contains("a.activity_date = \$3"), "日期筛选：$listSql")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `活动日志列表日期非法返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/childcare-activity-logs?date=2026/10/06") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  行为观测
    // ========================================================================

    @Test
    fun `登记行为观测返回201且等级与观测时刻正确`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-behavior-observations",
            JsonObject()
                .put("patient_id", "patient-1")
                .put("category", "情绪")
                .put("description", "入园哭闹约 5 分钟")
                .put("level", "关注"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "登记必须 201，实际: ${body.encode()}")
                assertEquals("patient-1", body.getString("patient_id"))
                assertEquals("关注", body.getString("level"))

                val tuple = observationTuples.first()
                // 列序：0 id / 1 patient_id / 2 observed_at / 3 category / 4 description / 5 level / 6 operator
                assertEquals("patient-1", tuple.getValue(1))
                assertTrue(tuple.getValue(2) is OffsetDateTime, "observed_at 缺省为当前时刻")
                assertEquals("情绪", tuple.getValue(3))
                assertEquals("关注", tuple.getValue(5))
                assertEquals("trainer01", tuple.getValue(6))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记行为观测等级不在白名单返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-behavior-observations",
            JsonObject().put("patient_id", "patient-1").put("category", "情绪")
                .put("description", "入园哭闹").put("level", "严重"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "§5.8 白名单外必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid level"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记行为观测缺描述返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-behavior-observations",
            JsonObject().put("patient_id", "patient-1").put("category", "情绪"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("description is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记行为观测儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(
            vertx,
            "/nursing/v1/childcare-behavior-observations",
            JsonObject().put("patient_id", "patient-x").put("category", "情绪").put("description", "入园哭闹"),
        ) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `行为观测列表空结果与筛选谓词`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/childcare-behavior-observations?patient_id=patient-1&date=2026-10-06&level=%E5%85%B3%E6%B3%A8") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)

                val listSql = executed.last { it.contains("from nursing.nursing_childcare_behavior_observations o") }
                assertTrue(listSql.contains("o.patient_id = \$1"), listSql)
                assertTrue(
                    listSql.contains("(o.observed_at at time zone 'asia/shanghai')::date = \$2"),
                    "按业务时区当日筛选：$listSql",
                )
                assertTrue(listSql.contains("o.level = \$3"), "等级筛选：$listSql")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `行为观测列表等级非法返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/childcare-behavior-observations?level=bad") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun idRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
    }

    private fun activityRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "log-1"
        every { getString("patient_id") } returns null
        every { getString("class_name") } returns "小班A"
        every { getLocalDate("activity_date") } returns LocalDate.parse("2026-10-06")
        every { getString("activity_name") } returns "户外游戏"
        every { getInteger("participant_count") } returns 12
        every { getString("note") } returns null
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }

    private fun observationRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "obs-1"
        every { getString("patient_id") } returns "patient-1"
        every { getOffsetDateTime("observed_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getString("category") } returns "情绪"
        every { getString("description") } returns "入园哭闹约 5 分钟"
        every { getString("level") } returns "关注"
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun post(vertx: Vertx, path: String, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, path, body)
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
