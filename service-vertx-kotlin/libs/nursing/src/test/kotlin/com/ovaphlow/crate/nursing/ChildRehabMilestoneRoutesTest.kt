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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 B3：里程碑路由的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. `POST /child-rehab-milestones` → 201；`milestone_name`/`expected_age_months` 由常量表**服务端派生**；
 *     `status` 与 `achieved_at` 同步；`operator` 取认证主体；同一儿童同一 code 走 `ON CONFLICT` 幂等。
 *  2. 未知 code / 缺字段 / 非法日期 400；儿童不存在 404；非管理员代填 operator 400（042）。
 *  3. `GET /child-rehab-milestone-status/:patient_id` 返回**常量表全量**的读时四态
 *     （未登记的按 未达/临近/逾期 判定），并带 `age_months`/`as_of`。
 *  4. 列表空结果 `{records: [], meta: {total: 0}}`。
 */
@ExtendWith(VertxExtension::class)
class ChildRehabMilestoneRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val upsertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var birthDate: LocalDate? = null
    private var achievedRows: List<Row> = emptyList()
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()

    /** 业务当天（与 `ChildRehabMilestoneService.today()` 同源），用于构造确定月龄。 */
    private val asOf: LocalDate = ChildRehabMilestoneService.today()

    private fun birthDateForAge(months: Int): LocalDate = asOf.minusMonths(months.toLong())

    @BeforeEach
    fun setUp() {
        executed.clear()
        upsertTuples.clear()
        patientFound = true
        birthDate = birthDateForAge(23)
        achievedRows = emptyList()
        countTotal = 0L
        listRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("select id, birth_date from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(patientRow()) else rowSet())
                sql.contains("insert into nursing.nursing_child_rehab_milestones") -> {
                    upsertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(milestoneIdRow()))
                }
                sql.contains("select milestone_code, achieved_at from nursing.nursing_child_rehab_milestones") ->
                    Future.succeededFuture(rowSet(*achievedRows.toTypedArray()))
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_child_rehab_milestones m") ->
                    if (sql.contains("where m.id = ")) {
                        Future.succeededFuture(rowSet(milestoneRow()))
                    } else {
                        Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                    }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  登记
    // ========================================================================

    @Test
    fun `登记里程碑返回201且名称与期望月龄由常量表派生`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject()
                .put("patient_id", "patient-1")
                .put("milestone_code", "STAIRS_UP_DOWN")
                .put("milestone_name", "客户端乱填的名字")
                .put("expected_age_months", 999)
                .put("achieved_at", "2026-10-06"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "登记必须 201，实际: ${body.encode()}")
                assertEquals("STAIRS_UP_DOWN", body.getString("milestone_code"))
                assertEquals("上下楼梯", body.getString("milestone_name"), "名称必须取常量表")
                assertEquals(24, body.getInteger("expected_age_months") ?: -1, "期望月龄必须取常量表")

                val sql = executed.first { it.contains("insert into nursing.nursing_child_rehab_milestones") }
                assertTrue(
                    sql.contains("on conflict (patient_id, milestone_code)"),
                    "同儿童同 code 必须幂等 upsert：$sql",
                )
                val tuple = upsertTuples.first()
                // 列序：0 id / 1 patient_id / 2 code / 3 name / 4 expected / 5 achieved_at / 6 status / 7 operator
                assertEquals("STAIRS_UP_DOWN", tuple.getValue(2))
                assertEquals("上下楼梯", tuple.getValue(3))
                assertEquals(24, tuple.getValue(4))
                assertEquals(LocalDate.parse("2026-10-06"), tuple.getValue(5))
                assertEquals("已达成", tuple.getValue(6), "有达成日期 → 登记态已达成")
                assertEquals("rehab01", tuple.getValue(7), "operator 取认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `不传达成日期时登记态为未达成`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("milestone_code", "WALK_INDEPENDENT"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val tuple = upsertTuples.first()
                assertEquals(null, tuple.getValue(5), "achieved_at 为空")
                assertEquals("未达成", tuple.getValue(6))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未知里程碑code返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("milestone_code", "NOT_A_MILESTONE"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("unknown milestone_code"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `缺字段返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, JsonObject().put("milestone_code", "HEAD_CONTROL")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("patient_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
        post(vertx, JsonObject().put("patient_id", "patient-1")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("milestone_code is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `达成日期非法返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("milestone_code", "HEAD_CONTROL").put("achieved_at", "2026/10/06"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("achieved_at"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(
            vertx,
            JsonObject().put("patient_id", "patient-x").put("milestone_code", "HEAD_CONTROL"),
        ) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `登记拒绝非管理员代填operator`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("milestone_code", "HEAD_CONTROL").put("operator", "other"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${body.encode()}")
                assertEquals("operator cannot be delegated", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  读时状态
    // ========================================================================

    @Test
    fun `状态接口返回常量表全量的读时四态`(vertx: Vertx, ctx: VertxTestContext) {
        // 月龄 23：期望 4/6/9/10/12/18 → 逾期；期望 24 → 临近；期望 30/36 → 未达
        birthDate = birthDateForAge(23)
        achievedRows = listOf(achievedRow("ROLL_OVER", "2025-04-06"))

        get(vertx, "/nursing/v1/child-rehab-milestone-status/patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals("patient-1", body.getString("patient_id"))
                assertEquals(23, body.getInteger("age_months") ?: -1)
                assertEquals(asOf.toString(), body.getString("as_of"))

                val items = body.getJsonArray("items")!!
                assertEquals(ChildRehabMilestone.CATALOG.size, items.size(), "必须返回常量表全量")

                fun stateOf(code: String): String? = (0 until items.size())
                    .map { items.getJsonObject(it) }
                    .firstOrNull { it.getString("milestone_code") == code }
                    ?.getString("state")

                assertEquals("已达成", stateOf("ROLL_OVER"), "已登记达成")
                assertEquals("逾期", stateOf("HEAD_CONTROL"), "期望 4 < 当前 23 且未达成")
                assertEquals("逾期", stateOf("CRAWL"), "期望 10 < 当前 23")
                assertEquals("临近", stateOf("STAIRS_UP_DOWN"), "期望 24、当前 23 → 临近")
                assertEquals("未达", stateOf("SPEECH_SENTENCE"), "期望 30 → 未达")
                assertEquals("未达", stateOf("RUN_JUMP"), "期望 36 → 未达")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `状态接口在常量表之外保留已登记的自定义code`(vertx: Vertx, ctx: VertxTestContext) {
        achievedRows = listOf(achievedRow("LEGACY_CUSTOM", "2025-01-01"))

        get(vertx, "/nursing/v1/child-rehab-milestone-status/patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                val items = body.getJsonArray("items")!!
                val custom = (0 until items.size())
                    .map { items.getJsonObject(it) }
                    .firstOrNull { it.getString("milestone_code") == "LEGACY_CUSTOM" }
                assertTrue(custom != null, "常量表之外的已登记 code 不得丢失")
                assertEquals("已达成", custom!!.getString("state"))
                assertEquals(ChildRehabMilestone.CATALOG.size + 1, items.size())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `状态接口儿童无birth_date返回400`(vertx: Vertx, ctx: VertxTestContext) {
        birthDate = null
        get(vertx, "/nursing/v1/child-rehab-milestone-status/patient-1") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("birth_date"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `状态接口儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        get(vertx, "/nursing/v1/child-rehab-milestone-status/patient-x") { status, body ->
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
    fun `列表空结果返回records空与total0`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-milestones?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun patientRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
        every { getLocalDate("birth_date") } returns birthDate
    }

    private fun milestoneIdRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "milestone-1"
    }

    private fun achievedRow(code: String, achievedAt: String): Row = mockk(relaxed = true) {
        every { getString("milestone_code") } returns code
        every { getLocalDate("achieved_at") } returns LocalDate.parse(achievedAt)
    }

    private fun milestoneRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "milestone-1"
        every { getString("patient_id") } returns "patient-1"
        every { getString("milestone_code") } returns "STAIRS_UP_DOWN"
        every { getString("milestone_name") } returns "上下楼梯"
        every { getInteger("expected_age_months") } returns 24
        every { getLocalDate("achieved_at") } returns LocalDate.parse("2026-10-06")
        every { getString("status") } returns "已达成"
        every { getString("operator") } returns "rehab01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun post(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/nursing/v1/child-rehab-milestones", body)
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
