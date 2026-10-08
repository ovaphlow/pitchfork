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
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 B4：手册分发/签收路由的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. `GET /child-rehab-handout-templates` → 模板常量清单（`{records, meta:{total}}`）。
 *  2. `POST /child-rehab-handouts` → 201；正文默认取模板、`channel` 缺省 `现场`、
 *     初始**未签收**（客户端提交 `acknowledged_at` 一律忽略）；`operator` 取认证主体。
 *  3. `PATCH /child-rehab-handouts/:id/acknowledge` → 未签收 → 已签收；**已签收再签 409**；
 *     并发兜底（条件更新 0 行）→ 409；不存在 404；042 代填 `acknowledged_by` → 400。
 *  4. 台账列表空结果 `{records: [], meta: {total: 0}}`，`patient_id` 过滤走 SQL 谓词。
 */
@ExtendWith(VertxExtension::class)
class ChildRehabHandoutRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val insertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val acknowledgeTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var handoutFound = true
    private var acknowledgedAt: OffsetDateTime? = null
    private var acknowledgeRowCount = 1
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        insertTuples.clear()
        acknowledgeTuples.clear()
        patientFound = true
        handoutFound = true
        acknowledgedAt = null
        acknowledgeRowCount = 1
        countTotal = 0L
        listRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("select id from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(idRow()) else rowSet())
                sql.contains("insert into nursing.nursing_child_rehab_handouts") -> {
                    insertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("update nursing.nursing_child_rehab_handouts set acknowledged_at") -> {
                    acknowledgeTuples.add(firstArg())
                    if (acknowledgeRowCount > 0) acknowledgedAt = OffsetDateTime.parse("2026-10-06T10:00:00+08:00")
                    Future.succeededFuture(rowSet(rowCount = acknowledgeRowCount))
                }
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_child_rehab_handouts h") ->
                    if (sql.contains("where h.id = ")) {
                        Future.succeededFuture(if (handoutFound) rowSet(handoutRow()) else rowSet())
                    } else {
                        Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                    }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  模板
    // ========================================================================

    @Test
    fun `模板清单返回全部常量`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-handout-templates") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(8, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(8L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  分发
    // ========================================================================

    @Test
    fun `分发手册返回201且正文与渠道由模板派生`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("template_code", "HOME_SIT"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "分发必须 201，实际: ${body.encode()}")
                assertEquals("HOME_SIT", body.getString("template_code"))
                assertEquals("家庭独坐训练指导", body.getString("template_name"))
                assertEquals("现场", body.getString("channel"), "渠道缺省 现场")
                assertEquals("未签收", body.getString("status"), "新建台账必须未签收")
                assertNull(body.getString("acknowledged_at"))
                assertEquals("rehab01", body.getString("operator"), "operator 取认证主体")

                val tuple = insertTuples.first()
                // 列序：0 id / 1 patient_id / 2 code / 3 name / 4 content / 5 channel / 6 delivered_at / 7 operator
                assertEquals("HOME_SIT", tuple.getValue(2))
                assertTrue((tuple.getValue(4) as String).contains("独坐"), "正文必须取模板")
                assertEquals("现场", tuple.getValue(5))
                assertEquals("rehab01", tuple.getValue(7))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `分发允许个性化正文与显式渠道`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject()
                .put("patient_id", "patient-1")
                .put("template_code", "HOME_SIT")
                .put("content", "本次随访重点：每日两次靠坐")
                .put("channel", "微信"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val tuple = insertTuples.first()
                assertEquals("本次随访重点：每日两次靠坐", tuple.getValue(4))
                assertEquals("微信", tuple.getValue(5))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `分发忽略客户端提交的签收时间`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject()
                .put("patient_id", "patient-1")
                .put("template_code", "HOME_SIT")
                .put("acknowledged_at", "2026-10-06T10:00:00+08:00"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val insertSql = executed.first { it.contains("insert into nursing.nursing_child_rehab_handouts") }
                assertTrue(
                    insertSql.contains("acknowledged_at").not(),
                    "创建不得写入签收列（签收必须是显式动作）：$insertSql",
                )
                assertEquals("未签收", body.getString("status"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `分发未知模板code返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("template_code", "NOPE"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("unknown template_code"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `分发缺patient_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, JsonObject().put("template_code", "HOME_SIT")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("patient_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `分发儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(
            vertx,
            JsonObject().put("patient_id", "patient-x").put("template_code", "HOME_SIT"),
        ) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `分发拒绝非管理员代填operator`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("template_code", "HOME_SIT").put("operator", "other"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${body.encode()}")
                assertEquals("operator cannot be delegated", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  签收（单向）
    // ========================================================================

    @Test
    fun `未签收台账可签收且状态转为已签收`(vertx: Vertx, ctx: VertxTestContext) {
        acknowledgedAt = null

        patch(vertx, "/nursing/v1/child-rehab-handouts/handout-1/acknowledge") { status, body ->
            ctx.verify {
                assertEquals(200, status, "未签收 → 已签收 必须成功，实际: ${body.encode()}")
                assertEquals("已签收", body.getString("status"))
                assertEquals(
                    OffsetDateTime.parse("2026-10-06T10:00:00+08:00").toString(),
                    body.getString("acknowledged_at"),
                    "ISO 偏移时间字符串（秒为 0 时省略）",
                )
                assertEquals("rehab01", body.getString("acknowledged_by"), "签收登记人取认证主体")

                val ackSql = executed.first { it.contains("update nursing.nursing_child_rehab_handouts") }
                assertTrue(
                    ackSql.contains("acknowledged_at is null"),
                    "必须用条件更新兜底并发重复签收：$ackSql",
                )
                val tuple = acknowledgeTuples.first()
                // 列序：0 acknowledged_at / 1 acknowledged_by / 2 updated_at / 3 id
                assertEquals("rehab01", tuple.getValue(1))
                assertEquals("handout-1", tuple.getValue(3))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已签收再签返回409`(vertx: Vertx, ctx: VertxTestContext) {
        acknowledgedAt = OffsetDateTime.parse("2026-10-06T10:00:00+08:00")

        patch(vertx, "/nursing/v1/child-rehab-handouts/handout-1/acknowledge") { status, body ->
            ctx.verify {
                assertEquals(409, status, "签收单向：已签收再签必须 4xx，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("already acknowledged"), body.encode())
                assertTrue(
                    executed.none { it.contains("update nursing.nursing_child_rehab_handouts") },
                    "已签收不得再发 UPDATE：$executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `并发重复签收由条件更新0行兜底为409`(vertx: Vertx, ctx: VertxTestContext) {
        acknowledgedAt = null
        acknowledgeRowCount = 0

        patch(vertx, "/nursing/v1/child-rehab-handouts/handout-1/acknowledge") { status, body ->
            ctx.verify {
                assertEquals(409, status, "条件更新 0 行（并发下已被他人签收）必须 409，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `签收不存在的台账返回404`(vertx: Vertx, ctx: VertxTestContext) {
        handoutFound = false

        patch(vertx, "/nursing/v1/child-rehab-handouts/handout-x/acknowledge") { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `签收拒绝非管理员代填acknowledged_by`(vertx: Vertx, ctx: VertxTestContext) {
        patch(
            vertx,
            "/nursing/v1/child-rehab-handouts/handout-1/acknowledge",
            JsonObject().put("acknowledged_by", "other"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填签收人必须 400，实际: ${body.encode()}")
                assertEquals("operator cannot be delegated", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  台账列表
    // ========================================================================

    @Test
    fun `台账列表空结果返回records空与total0`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-handouts?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `台账列表按patient_id过滤`(vertx: Vertx, ctx: VertxTestContext) {
        countTotal = 1L
        listRows = listOf(handoutRow())

        get(vertx, "/nursing/v1/child-rehab-handouts?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(1, body.getJsonArray("records")?.size() ?: -1)
                val listSql = executed.last { it.contains("from nursing.nursing_child_rehab_handouts h") }
                assertTrue(listSql.contains("h.patient_id = \$1"), listSql)
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

    private fun handoutRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "handout-1"
        every { getString("patient_id") } returns "patient-1"
        every { getString("template_code") } returns "HOME_SIT"
        every { getString("template_name") } returns "家庭独坐训练指导"
        every { getString("content") } returns "靠坐 → 扶坐 → 独坐"
        every { getString("channel") } returns "现场"
        every { getOffsetDateTime("delivered_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("acknowledged_at") } returns acknowledgedAt
        every { getString("acknowledged_by") } returns if (acknowledgedAt == null) null else "rehab01"
        every { getString("operator") } returns "rehab01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun post(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/nursing/v1/child-rehab-handouts", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun patch(
        vertx: Vertx,
        path: String,
        body: JsonObject = JsonObject(),
        handler: (Int, JsonObject) -> Unit,
    ) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PATCH, path, body)
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
