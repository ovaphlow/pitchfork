package com.ovaphlow.crate.dining

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 A4：特殊膳食申请 + 过敏原标记的**不连数据库**路由级护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. `POST /special-diet-requests` → 201，`status` 恒为 `待审批`、`requested_by` 取认证主体
 *     （客户端提交的同名字段一律忽略）。
 *  2. `PATCH /special-diet-requests/:id/status` 合法流转 200 且写 `reviewed_by/reviewed_at`；
 *     非法流转 400（中文/英文明确文案）；不存在 404。
 *  3. `GET /special-diet-requests` 空结果 `{records: [], meta: {total: 0}}`；非法状态过滤 400。
 *  4. `PUT /diet-profiles/:id/allergens` 覆盖式写**既有列** `dining_diet_profiles.allergies`
 *     （不新建过敏主数据表）；缺 `allergies` 键 400。
 *  5. 未认证 401。
 */
@ExtendWith(VertxExtension::class)
class SpecialDietRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val writeTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var currentStatus: String? = "待审批"
    private var requestFound = true
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()
    private var profileFound = true

    @BeforeEach
    fun setUp() {
        executed.clear()
        writeTuples.clear()
        patientFound = true
        currentStatus = "待审批"
        requestFound = true
        countTotal = 0L
        listRows = emptyList()
        profileFound = true

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into dining.dining_special_diet_requests") -> {
                    writeTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("update dining.dining_special_diet_requests") -> {
                    writeTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("select status from dining.dining_special_diet_requests") ->
                    Future.succeededFuture(
                        if (requestFound && currentStatus != null) rowSet(statusRow(currentStatus!!)) else rowSet(),
                    )
                sql.contains("count(*) as total") -> Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("order by r.created_at desc") ->
                    Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                sql.contains("from dining.dining_special_diet_requests r join healthcare.patients") ->
                    Future.succeededFuture(if (requestFound) rowSet(requestRow()) else rowSet())
                sql.contains("from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(patientRow()) else rowSet())
                // —— DietProfileService（legacy jOOQ 渲染 SQL）——
                sql.contains("update dining.dining_diet_profiles") -> {
                    writeTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("from dining.dining_diet_profiles") ->
                    Future.succeededFuture(if (profileFound) rowSet(profileRow()) else rowSet())
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  特殊膳食申请
    // ========================================================================

    @Test
    fun `创建申请单返回201且状态与申请人由服务端派生`(vertx: Vertx, ctx: VertxTestContext) {
        val body = requestBody()
            .put("status", "已批准")
            .put("requested_by", "client-operator")
            .put("reviewed_by", "client-reviewer")

        post(vertx, body) { status, response ->
            ctx.verify {
                assertEquals(201, status, "创建必须 201，实际: ${response.encode()}")
                assertEquals("待审批", response.getString("status"), "状态必须服务端派生为待审批")
                assertEquals("dietitian-1", response.getString("requested_by"), "申请人必须取认证主体")
                assertEquals("过敏回避", response.getString("request_type"))
                assertEquals(listOf("花生", "牛奶"), response.getJsonArray("allergens")?.map { it.toString() })

                val insertSql = executed.first { it.contains("insert into dining.dining_special_diet_requests") }
                assertTrue(insertSql.contains("待审批").not(), "状态是绑定参数，不写死在 SQL 里：$insertSql")
                val tuple = writeTuples.first()
                assertEquals("待审批", tuple.getValue(6), "绑定参数里的状态必须是待审批")
                assertEquals("dietitian-1", tuple.getValue(7), "绑定参数里的申请人必须是认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建时过敏原可用逗号分隔字符串`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, requestBody().put("allergens", "花生，牛奶")) { status, response ->
            ctx.verify {
                assertEquals(201, status, "实际: ${response.encode()}")
                val tuple = writeTuples.first()
                assertEquals(listOf("花生", "牛奶"), (tuple.getValue(4) as JsonArray).map { it.toString() })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建校验失败返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, requestBody().put("request_type", "忌口")) { status, response ->
            ctx.verify {
                assertEquals(400, status, "实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("request_type"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(vertx, requestBody()) { status, response ->
            ctx.verify {
                assertEquals(404, status, "实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("patient not found"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `合法流转待审批到已批准写入审批人`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "待审批"
        patchStatus(vertx, "已批准") { status, response ->
            ctx.verify {
                assertEquals(200, status, "合法流转必须 200，实际: ${response.encode()}")
                val updateSql = executed.first { it.contains("update dining.dining_special_diet_requests") }
                assertTrue(updateSql.contains("reviewed_by"), "批准必须写审批人：$updateSql")
                assertTrue(updateSql.contains("reviewed_at"), "批准必须写审批时刻：$updateSql")
                val tuple = writeTuples.first()
                assertEquals("已批准", tuple.getValue(0))
                assertEquals("dietitian-1", tuple.getValue(1), "审批人取认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `合法流转到已终止不写审批人`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已批准"
        patchStatus(vertx, "已终止") { status, response ->
            ctx.verify {
                assertEquals(200, status, "实际: ${response.encode()}")
                val updateSql = executed.first { it.contains("update dining.dining_special_diet_requests") }
                assertTrue(updateSql.contains("reviewed_by").not(), "终止不是审批，不写审批人：$updateSql")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法流转返回400`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已批准"
        patchStatus(vertx, "已驳回") { status, response ->
            ctx.verify {
                assertEquals(400, status, "已批准 → 已驳回 必须 400，实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("invalid status transition"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `终态不可再流转返回400`(vertx: Vertx, ctx: VertxTestContext) {
        currentStatus = "已终止"
        patchStatus(vertx, "已批准") { status, response ->
            ctx.verify {
                assertEquals(400, status, "终态必须拒绝流转，实际: ${response.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `流转到白名单外状态返回400`(vertx: Vertx, ctx: VertxTestContext) {
        patchStatus(vertx, "已审批") { status, response ->
            ctx.verify {
                assertEquals(400, status, "实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("invalid status"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `申请单不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        requestFound = false
        patchStatus(vertx, "已批准") { status, response ->
            ctx.verify {
                assertEquals(404, status, "实际: ${response.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表空结果返回records空与total0`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/special-diet-requests?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表非法状态过滤返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/special-diet-requests?status=已审批") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid status"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, authHandler = null) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/special-diet-requests", requestBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status, "实际: ${body.encode()}")
                        ctx.completeNow()
                    }
                }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    // ========================================================================
    //  过敏原标记（复用既有列）
    // ========================================================================

    @Test
    fun `过敏原标记覆盖写既有allergies列`(vertx: Vertx, ctx: VertxTestContext) {
        putAllergens(vertx, JsonObject().put("allergies", JsonArray().add("花生").add("海鲜"))) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                val updateSql = executed.first { it.contains("update dining.dining_diet_profiles") }
                assertTrue(updateSql.contains("allergies"), "必须写 allergies 列：$updateSql")
                assertTrue(
                    updateSql.contains("dining_diet_profiles"),
                    "必须写既有表 dining_diet_profiles，不得新建过敏主数据表：$updateSql",
                )
                val tuple = writeTuples.first()
                val bound = (0 until tuple.size()).mapNotNull { tuple.getValue(it) }
                    .filterIsInstance<JsonArray>()
                    .firstOrNull()
                assertEquals(listOf("花生", "海鲜"), bound?.map { it.toString() })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `过敏原标记接受逗号分隔字符串`(vertx: Vertx, ctx: VertxTestContext) {
        putAllergens(vertx, JsonObject().put("allergies", "花生，海鲜")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                val tuple = writeTuples.first()
                val bound = (0 until tuple.size()).mapNotNull { tuple.getValue(it) }
                    .filterIsInstance<JsonArray>()
                    .firstOrNull()
                assertEquals(listOf("花生", "海鲜"), bound?.map { it.toString() })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `过敏原标记缺少allergies键返回400`(vertx: Vertx, ctx: VertxTestContext) {
        putAllergens(vertx, JsonObject()) { status, body ->
            ctx.verify {
                assertEquals(400, status, "覆盖式写入必须显式给出 allergies，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("allergies"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `过敏原标记类型非法返回400而非500`(vertx: Vertx, ctx: VertxTestContext) {
        putAllergens(vertx, JsonObject().put("allergies", 123)) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("allergies"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `过敏原标记档案不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        profileFound = false
        putAllergens(vertx, JsonObject().put("allergies", JsonArray().add("花生"))) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun requestBody(): JsonObject = JsonObject()
        .put("patient_id", "patient-1")
        .put("request_type", "过敏回避")
        .put("meal_type", "午餐")
        .put("allergens", JsonArray().add("花生").add("牛奶"))
        .put("reason", "既往花生过敏")
        .put("effective_from", "2026-10-06")

    private fun patientRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
    }

    private fun statusRow(status: String): Row = mockk(relaxed = true) {
        every { getString("status") } returns status
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun requestRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns REQUEST_ID
        every { getString("patient_id") } returns "patient-1"
        every { getString("patient_name") } returns "小明"
        every { getString("request_type") } returns "过敏回避"
        every { getString("meal_type") } returns "午餐"
        every { getJsonArray("allergens") } returns JsonArray().add("花生").add("牛奶")
        every { getString("reason") } returns "既往花生过敏"
        every { getString("status") } returns "待审批"
        every { getString("requested_by") } returns "dietitian-1"
        // relaxed mockk 会为 final 时间类型返回「空壳 mock」，其 toString() 会 NPE：
        // 可空字段必须显式 stub 成 null。
        every { getString("reviewed_by") } returns null
        every { getOffsetDateTime("reviewed_at") } returns null
        every { getLocalDate("effective_from") } returns null
        every { getLocalDate("effective_to") } returns null
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    /** DietProfileService.toJson 走 `row.getValue(...)`。 */
    private fun profileRow(): Row = mockk(relaxed = true) {
        every { getValue("id") } returns "profile-1"
        every { getValue("patient_id") } returns "patient-1"
        every { getValue("patient_name") } returns "小明"
        every { getValue("encounter_id") } returns "enc-1"
        every { getValue("meal_type") } returns "普食"
        every { getValue("allergies") } returns JsonArray().add("花生").add("海鲜")
        every { getValue("portion_preference") } returns "标准"
        every { getValue("status") } returns "启用"
    }

    private fun post(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/special-diet-requests", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun patchStatus(vertx: Vertx, status: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(
                vertx,
                port,
                HttpMethod.PATCH,
                "/dining/v1/special-diet-requests/$REQUEST_ID/status",
                JsonObject().put("status", status),
            ).map { (code, response) -> handler(code, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun putAllergens(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PUT, "/dining/v1/diet-profiles/profile-1/allergens", body)
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

    private fun withServer(
        vertx: Vertx,
        authHandler: Handler<RoutingContext>? = Handler<RoutingContext> { rctx ->
            rctx.put("userId", "dietitian-1")
            rctx.next()
        },
        block: (Int) -> Future<Unit>,
    ): Future<Unit> {
        val root = Router.router(vertx)
        root.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, authHandler))
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

    private companion object {
        const val REQUEST_ID = "01J8ZQ9K7M3N5P7R9T1V3X5Z72"
    }
}
