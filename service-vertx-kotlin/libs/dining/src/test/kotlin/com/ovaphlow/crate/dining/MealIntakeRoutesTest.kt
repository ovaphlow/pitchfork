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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 A1：每餐膳食摄入记录的**不连数据库**路由级护栏（mockk 桩 `Pool`，范式同
 * `DiningRoutesConflictTest`）。
 *
 * 锁定语义：
 *  1. `POST /dining/v1/meal-intake-records` → 201；`operator` 取认证主体（客户端提交即丢弃）；
 *     营养素由服务端按「食物项份量 × 菜品营养 × 摄入比例」汇总（直接断言绑定的 INSERT 参数）。
 *  2. 未匹配到菜品营养的食物项按 0 计入，不报错。
 *  3. 同一儿童同一餐次走 `ON CONFLICT` 覆盖更新（幂等 SQL 形态）。
 *  4. 参数校验 400（缺 patient_id / 非法餐次 / 非法份量 / 比例越界 / items 非数组）；
 *     儿童不存在 404；未认证 401。
 *  5. 列表空结果 `{records: [], meta: {total: 0}}`。
 */
@ExtendWith(VertxExtension::class)
class MealIntakeRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val insertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    /** 桩状态 */
    private var patientFound = true
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        insertTuples.clear()
        patientFound = true
        countTotal = 0L
        listRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into dining.dining_meal_intake_records") -> {
                    insertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(idRow()))
                }
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                // 按 id 取单条（UPSERT 后的回读）
                sql.contains("from dining.dining_meal_intake_records x join healthcare.patients") &&
                    sql.contains("where x.id = ") ->
                    Future.succeededFuture(rowSet(recordRow()))
                sql.contains("from dining.dining_meal_intake_records x join healthcare.patients") ->
                    Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                sql.contains("from dining.dining_dishes") -> {
                    val name = firstArg<Tuple>().getString(0)
                    Future.succeededFuture(if (name == DISH_NAME) rowSet(dishNutritionRow()) else rowSet())
                }
                sql.contains("from dining.dining_roster_items") -> Future.succeededFuture(rowSet())
                sql.contains("from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(patientRow()) else rowSet())
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `创建摄入记录返回201且操作人取认证主体与营养汇总由服务端派生`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, intakeBody().put("operator", "client-operator")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "创建必须 201，实际: ${body.encode()}")
                assertEquals("dietitian-1", body.getString("operator"), "operator 必须取认证主体")
                assertEquals("小明", body.getString("patient_name"))
                assertEquals(RECORD_ID, body.getString("id"))
                assertEquals(1, insertTuples.size, "必须执行一次 UPSERT")
                val tuple = insertTuples.first()
                // 列序：0 id / 1 patient_id / 2 roster_item_id / 3 intake_date / 4 meal_time /
                //       5 items / 6 intake_ratio / 7 energy / 8 protein / 9 fat / 10 carb / 11 sodium / 12 operator
                assertEquals(BigDecimal("150.00"), tuple.getValue(7), "energy = 200 * 150/100 * 0.5")
                assertEquals(BigDecimal("7.50"), tuple.getValue(8), "protein = 10 * 150/100 * 0.5")
                assertEquals(BigDecimal("3.75"), tuple.getValue(9), "fat = 5 * 150/100 * 0.5")
                assertEquals(BigDecimal("15.00"), tuple.getValue(10), "carb = 20 * 150/100 * 0.5")
                assertEquals(BigDecimal("75.00"), tuple.getValue(11), "sodium = 100 * 150/100 * 0.5")
                assertEquals("dietitian-1", tuple.getValue(12), "绑定参数里的操作人必须是认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未匹配菜品营养的食物项按0计入且不报错`(vertx: Vertx, ctx: VertxTestContext) {
        // intakeBody 含「米饭」（有营养）与「苹果」（无营养）两项：
        // 苹果无菜品营养快照 → 计 0，总量与仅米饭一致。
        post(vertx) { status, body ->
            ctx.verify {
                assertEquals(201, status, "未匹配项不得报错，实际: ${body.encode()}")
                val tuple = insertTuples.first()
                assertEquals(BigDecimal("150.00"), tuple.getValue(7))
                assertEquals(BigDecimal("7.50"), tuple.getValue(8))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `重复提交走ON CONFLICT覆盖更新保证幂等`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx) { firstStatus, _ ->
            ctx.verify { assertEquals(201, firstStatus) }
            post(vertx) { secondStatus, _ ->
                ctx.verify {
                    assertEquals(201, secondStatus, "同餐次重复提交仍 201（覆盖更新）")
                    val upserts = executed.filter { it.contains("insert into dining.dining_meal_intake_records") }
                    assertEquals(2, upserts.size, "两次提交各执行一次 UPSERT")
                    assertTrue(
                        upserts.all { it.contains("on conflict (patient_id, intake_date, meal_time)") },
                        "幂等必须由唯一键 ON CONFLICT 承载，实际: $upserts",
                    )
                    assertEquals(2, insertTuples.size)
                    ctx.completeNow()
                }
            }
        }
    }

    @Test
    fun `缺patient_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, intakeBody().apply { remove("patient_id") }) { status, body ->
            ctx.verify {
                assertEquals(400, status, "缺 patient_id 必须 400，实际: ${body.encode()}")
                assertEquals("patient_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法餐次返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, intakeBody().put("meal_time", "夜宵")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "非法餐次必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid meal_time"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法份量返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val body = intakeBody().put(
            "items",
            JsonArray().add(JsonObject().put("food_name", DISH_NAME).put("amount_g", 0)),
        )
        post(vertx, body) { status, response ->
            ctx.verify {
                assertEquals(400, status, "amount_g=0 必须 400，实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("amount_g"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `摄入比例越界返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, intakeBody().put("intake_ratio", 1.5)) { status, body ->
            ctx.verify {
                assertEquals(400, status, "ratio>1 必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("intake_ratio"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `items非数组返回400而非500`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, intakeBody().put("items", "米饭150克")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "items 非数组必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("items"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(vertx) { status, body ->
            ctx.verify {
                assertEquals(404, status, "儿童不存在必须 404，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("patient not found"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, authHandler = null) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/meal-intake-records", intakeBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status, "未认证必须 401，实际: ${body.encode()}")
                        assertEquals("authentication required", body.getString("error"))
                        ctx.completeNow()
                    }
                }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    @Test
    fun `列表空结果返回records空与total0`(vertx: Vertx, ctx: VertxTestContext) {
        countTotal = 0L
        listRows = emptyList()
        get(vertx, "/dining/v1/meal-intake-records?patient_id=$PATIENT_ID") { status, body ->
            ctx.verify {
                assertEquals(200, status, "查询必须 200，实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1, body.encode())
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L, body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表带过滤参数且命中返回记录与total`(vertx: Vertx, ctx: VertxTestContext) {
        countTotal = 1L
        listRows = listOf(recordRow())
        val path = "/dining/v1/meal-intake-records?patient_id=$PATIENT_ID&date=2026-10-06" +
            "&meal_time=${encode("午餐")}&from=2026-10-01&to=2026-10-07"
        get(vertx, path) { status, body ->
            ctx.verify {
                assertEquals(200, status, "查询必须 200，实际: ${body.encode()}")
                assertEquals(1L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                assertEquals(1, body.getJsonArray("records")?.size() ?: -1)
                val sql = executed.last { it.contains("from dining.dining_meal_intake_records x join healthcare.patients") }
                assertTrue(sql.contains("x.patient_id = \$1"), sql)
                assertTrue(sql.contains("x.intake_date = \$2"), sql)
                assertTrue(sql.contains("x.meal_time = \$3"), sql)
                assertTrue(sql.contains("x.intake_date >= \$4"), sql)
                assertTrue(sql.contains("x.intake_date <= \$5"), sql)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表非法餐次返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/meal-intake-records?meal_time=${encode("夜宵")}") { status, body ->
            ctx.verify {
                assertEquals(400, status, "非法餐次过滤必须 400，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 非 ASCII 查询参数必须百分号编码，否则 Vert.x 解析出的不是原文。 */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun intakeBody(): JsonObject = JsonObject()
        .put("patient_id", PATIENT_ID)
        .put("intake_date", "2026-10-06")
        .put("meal_time", "午餐")
        .put("items", JsonArray()
            .add(JsonObject().put("food_name", DISH_NAME).put("amount_g", 150))
            .add(JsonObject().put("food_name", "苹果").put("amount_g", 80)))
        .put("intake_ratio", 0.5)
        .put("remark", "食欲一般")

    private fun patientRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns PATIENT_ID
        every { getString("name") } returns "小明"
    }

    private fun dishNutritionRow(): Row = mockk(relaxed = true) {
        every { getJsonObject("nutrition") } returns JsonObject()
            .put("basis_g", 100)
            .put("energy_kcal", 200)
            .put("protein_g", 10)
            .put("fat_g", 5)
            .put("carb_g", 20)
            .put("sodium_mg", 100)
    }

    private fun idRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns RECORD_ID
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun recordRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns RECORD_ID
        every { getString("patient_id") } returns PATIENT_ID
        every { getString("patient_name") } returns "小明"
        every { getString("meal_time") } returns "午餐"
        every { getLocalDate("intake_date") } returns LocalDate.parse("2026-10-06")
        every { getJsonArray("items") } returns JsonArray()
            .add(JsonObject().put("food_name", DISH_NAME).put("amount_g", 150.0))
        every { getBigDecimal("intake_ratio") } returns BigDecimal("0.5000")
        every { getBigDecimal("energy_kcal") } returns BigDecimal("150.00")
        every { getBigDecimal("protein_g") } returns BigDecimal("7.50")
        every { getBigDecimal("fat_g") } returns BigDecimal("3.75")
        every { getBigDecimal("carb_g") } returns BigDecimal("15.00")
        every { getBigDecimal("sodium_mg") } returns BigDecimal("75.00")
        every { getString("operator") } returns "dietitian-1"
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    private fun post(vertx: Vertx, body: JsonObject = intakeBody(), handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/meal-intake-records", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun get(vertx: Vertx, path: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.GET, path, null)
                .map { (status, response) -> handler(status, response) }
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
        const val PATIENT_ID = "patient-1"
        const val RECORD_ID = "01J8ZQ9K7M3N5P7R9T1V3X5Z70"
        const val DISH_NAME = "米饭"
    }
}
