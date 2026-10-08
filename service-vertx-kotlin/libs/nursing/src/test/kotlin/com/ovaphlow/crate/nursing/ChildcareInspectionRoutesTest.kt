package com.ovaphlow.crate.nursing

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
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 C2：环境安全巡查路由的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义（§5.8 隐患闭环**严格线性** `待整改 → 已整改 → 已关闭`）：
 *  1. `POST /childcare-safety-inspections` → 201，初始 `待整改`；检查项 `item_code` 走常量表、
 *     `result ∈ 合格/不合格/不适用`，落库为归一化 JSONB 数组。
 *  2. `POST /:id/items`：`待整改 → 已整改`；`已整改` 时仅追加；`已关闭` → **409**。
 *  3. `PATCH /:id/close`：仅 `已整改 → 已关闭`；`待整改` 直接关闭（跳步）与终态再关闭 → **409**。
 *  4. 列表空结果 `{records: [], meta: {total: 0}}`；筛选走 SQL 谓词。
 */
@ExtendWith(VertxExtension::class)
class ChildcareInspectionRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val insertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val itemsTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val statusTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var found = true
    private var currentStatus = "待整改"
    private var currentItems: JsonArray = JsonArray()
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        insertTuples.clear()
        itemsTuples.clear()
        statusTuples.clear()
        found = true
        currentStatus = "待整改"
        currentItems = JsonArray()
        countTotal = 0L
        listRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into nursing.nursing_childcare_safety_inspections") -> {
                    insertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("update nursing.nursing_childcare_safety_inspections set items") -> {
                    val tuple = firstArg<Tuple>()
                    itemsTuples.add(tuple)
                    currentItems = tuple.getValue(0) as JsonArray
                    currentStatus = tuple.getValue(1) as String
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("update nursing.nursing_childcare_safety_inspections set status") -> {
                    val tuple = firstArg<Tuple>()
                    statusTuples.add(tuple)
                    currentStatus = tuple.getValue(0) as String
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_childcare_safety_inspections i") ->
                    if (sql.contains("where i.id = ")) {
                        Future.succeededFuture(if (found) rowSet(inspectionRow()) else rowSet())
                    } else {
                        Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                    }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  创建
    // ========================================================================

    @Test
    fun `创建巡查返回201且初始待整改`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections",
            JsonObject().put("area", "活动室").put(
                "items",
                JsonArray().add(
                    JsonObject().put("item_code", "FLOOR_SLIP").put("result", "不合格").put("hazard", "地面有积水"),
                ),
            ),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "创建巡查必须 201，实际: ${body.encode()}")
                assertEquals("待整改", body.getString("status"), "初始状态必须待整改")
                assertEquals("trainer01", body.getString("operator"))

                val tuple = insertTuples.first()
                // 列序：0 id / 1 area / 2 inspected_at / 3 items / 4 status / 5 operator
                assertEquals("活动室", tuple.getValue(1))
                assertTrue(tuple.getValue(2) is OffsetDateTime, "inspected_at 缺省当前时刻")
                assertEquals("待整改", tuple.getValue(4))
                assertEquals("trainer01", tuple.getValue(5))

                val items = tuple.getValue(3) as JsonArray
                assertEquals(1, items.size())
                assertEquals("FLOOR_SLIP", items.getJsonObject(0).getString("item_code"))
                assertEquals("不合格", items.getJsonObject(0).getString("result"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建巡查无检查项也可落库`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "/nursing/v1/childcare-safety-inspections", JsonObject().put("area", "户外场地")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val items = insertTuples.first().getValue(3) as JsonArray
                assertEquals(0, items.size(), "未提交 items 落空数组")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未知检查项code返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections",
            JsonObject().put("area", "活动室").put(
                "items",
                JsonArray().add(JsonObject().put("item_code", "NOPE").put("result", "合格")),
            ),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "检查项必须命中常量表，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("unknown item_code"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `检查结果不在白名单返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections",
            JsonObject().put("area", "活动室").put(
                "items",
                JsonArray().add(JsonObject().put("item_code", "FLOOR_SLIP").put("result", "差不多")),
            ),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid result"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建巡查拒绝非管理员代填operator`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections",
            JsonObject().put("area", "活动室").put("operator", "other"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  隐患闭环
    // ========================================================================

    @Test
    fun `待整改追加整改项转为已整改`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "待整改"
        currentItems = JsonArray().add(JsonObject().put("item_code", "FLOOR_SLIP").put("result", "不合格"))

        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections/inspection-1/items",
            JsonObject().put("item_code", "FLOOR_SLIP").put("result", "合格").put("hazard", "已清理积水"),
        ) { status, body ->
            ctx.verify {
                assertEquals(200, status, "追加整改项必须成功，实际: ${body.encode()}")
                assertEquals("已整改", body.getString("status"), "待整改 + 整改记录 → 已整改")

                val tuple = itemsTuples.first()
                assertEquals("已整改", tuple.getValue(1))
                assertEquals(2, (tuple.getValue(0) as JsonArray).size(), "检查项必须追加而非覆盖")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已整改追加整改项状态不变`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已整改"
        currentItems = JsonArray().add(JsonObject().put("item_code", "FLOOR_SLIP").put("result", "不合格"))

        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections/inspection-1/items",
            JsonObject().put("item_code", "FLOOR_SLIP").put("result", "合格"),
        ) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals("已整改", body.getString("status"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已关闭巡查追加整改项返回409`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已关闭"

        post(
            vertx,
            "/nursing/v1/childcare-safety-inspections/inspection-1/items",
            JsonObject().put("item_code", "FLOOR_SLIP").put("result", "合格"),
        ) { status, body ->
            ctx.verify {
                assertEquals(409, status, "终态不得再改，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("already closed"), body.encode())
                assertTrue(itemsTuples.isEmpty(), "已关闭不得发 UPDATE")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已整改可关闭`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已整改"

        patch(vertx, "/nursing/v1/childcare-safety-inspections/inspection-1/close") { status, body ->
            ctx.verify {
                assertEquals(200, status, "已整改→已关闭 必须成功，实际: ${body.encode()}")
                assertEquals("已关闭", body.getString("status"))
                assertEquals("已关闭", statusTuples.first().getValue(0))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `待整改直接关闭返回409`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "待整改"

        patch(vertx, "/nursing/v1/childcare-safety-inspections/inspection-1/close") { status, body ->
            ctx.verify {
                assertEquals(409, status, "跳步（待整改→已关闭）必须 409，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid status transition"), body.encode())
                assertTrue(statusTuples.isEmpty(), "非法流转不得发 UPDATE")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `已关闭再关闭返回409`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已关闭"

        patch(vertx, "/nursing/v1/childcare-safety-inspections/inspection-1/close") { status, body ->
            ctx.verify {
                assertEquals(409, status, "终态再关闭必须 409，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `巡查不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        found = false
        patch(vertx, "/nursing/v1/childcare-safety-inspections/inspection-x/close") { status, body ->
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
    fun `巡查列表空结果与筛选谓词`(vertx: Vertx, ctx: VertxTestContext) {
        get(
            vertx,
            "/nursing/v1/childcare-safety-inspections?area=%E6%B4%BB%E5%8A%A8%E5%AE%A4&date=2026-10-06&status=%E5%BE%85%E6%95%B4%E6%94%B9",
        ) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)

                val listSql = executed.last { it.contains("from nursing.nursing_childcare_safety_inspections i") }
                assertTrue(listSql.contains("i.area = \$1"), listSql)
                assertTrue(
                    listSql.contains("(i.inspected_at at time zone 'asia/shanghai')::date = \$2"),
                    "按业务时区当日筛选：$listSql",
                )
                assertTrue(listSql.contains("i.status = \$3"), listSql)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun inspectionRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "inspection-1"
        every { getString("area") } returns "活动室"
        every { getOffsetDateTime("inspected_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getJsonArray("items") } returns currentItems
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

    private fun patch(vertx: Vertx, path: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PATCH, path, JsonObject()).map { (status, response) -> handler(status, response) }
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
