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
 * 054 A5：营养周报生成的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. 周界归一：传入任意日期都归一到该周周一，`week_end = week_start + 6`。
 *  2. **空数据周**也能生成可判定快照（`intake_days=0`、均值 0、结论「无摄入记录」），不 500。
 *  3. **重复生成同周覆盖**：走 `ON CONFLICT (patient_id, week_start)`，不重复插入。
 *  4. 均值按**有摄入记录的天数**计算（逐日复用 A3 读时分析口径）。
 *  5. 参数错误 400（缺 patient_id / 非法 week_start / 缺 birth_date）；儿童不存在 404。
 *  6. 列表形状 `{records, meta:{total}}`，空结果为 `[]`/`0`；`week_start` 过滤同样归一。
 */
@ExtendWith(VertxExtension::class)
class NutritionWeeklyReportTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val upsertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var patientBirth: LocalDate? = LocalDate.parse("2024-10-05")
    private var intakeDates: List<LocalDate> = emptyList()
    private var intakeByDate: Map<String, List<Row>> = emptyMap()
    private var dishNutrition: Map<String, JsonObject> = emptyMap()
    private var countTotal = 0L
    private var listRows: List<Row> = emptyList()

    @BeforeEach
    fun setUp() {
        executed.clear()
        upsertTuples.clear()
        patientFound = true
        patientBirth = LocalDate.parse("2024-10-05")
        intakeDates = emptyList()
        intakeByDate = emptyMap()
        dishNutrition = emptyMap()
        countTotal = 0L
        listRows = emptyList()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into dining.dining_nutrition_weekly_reports") -> {
                    upsertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(reportIdRow()))
                }
                sql.contains("count(*) as total") -> Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("select distinct intake_date from dining.dining_meal_intake_records") ->
                    Future.succeededFuture(rowSet(*intakeDates.map { dateRow(it) }.toTypedArray()))
                sql.contains("order by r.week_start desc") ->
                    Future.succeededFuture(rowSet(*listRows.toTypedArray()))
                sql.contains("from dining.dining_nutrition_weekly_reports r join healthcare.patients") ->
                    Future.succeededFuture(rowSet(reportRow()))
                sql.contains("from dining.dining_meal_intake_records") -> {
                    val date = firstArg<Tuple>().getLocalDate(1)?.toString() ?: ""
                    Future.succeededFuture(rowSet(*(intakeByDate[date] ?: emptyList()).toTypedArray()))
                }
                sql.contains("from dining.dining_dishes") -> {
                    val name = firstArg<Tuple>().getString(0)
                    val nutrition = dishNutrition[name]
                    Future.succeededFuture(if (nutrition == null) rowSet() else rowSet(dishRow(nutrition)))
                }
                sql.contains("from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(patientRow()) else rowSet())
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  生成
    // ========================================================================

    @Test
    fun `空数据周生成可判定快照且不500`(vertx: Vertx, ctx: VertxTestContext) {
        intakeDates = emptyList()

        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-05")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "空数据周必须能生成，实际: ${body.encode()}")
                assertEquals(1, upsertTuples.size, "空数据周也要落一条快照")
                val tuple = upsertTuples.first()
                assertEquals(0, tuple.getValue(4), "intake_days = 0")
                assertEquals(BigDecimal("0.00"), tuple.getValue(5), "energy_avg = 0.00")
                assertEquals("无摄入记录", tuple.getValue(10), "空数据周结论必须可判定")
                assertEquals(0, (tuple.getValue(12) as JsonArray).size(), "daily 为空数组")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周界归一到周一且周末为周日`(vertx: Vertx, ctx: VertxTestContext) {
        // 2026-10-07 是周三 → 归一为 2026-10-05（周一）
        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-07")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val tuple = upsertTuples.first()
                assertEquals(LocalDate.parse("2026-10-05"), tuple.getValue(2), "week_start 必须归一到周一")
                assertEquals(LocalDate.parse("2026-10-11"), tuple.getValue(3), "week_end = 周一 + 6")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `有数据周按摄入天数求均值`(vertx: Vertx, ctx: VertxTestContext) {
        dishNutrition = mapOf("米饭" to nutrition(energy = 200.0, protein = 10.0, fat = 5.0, carb = 20.0, sodium = 100.0))
        intakeDates = listOf(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-07"))
        intakeByDate = mapOf(
            "2026-10-06" to listOf(
                intakeRow(items("米饭" to 100.0), "1.0", energy = "200.00", protein = "10.00", fat = "5.00", carb = "20.00", sodium = "100.00"),
            ),
            "2026-10-07" to listOf(
                intakeRow(items("米饭" to 200.0), "1.0", energy = "400.00", protein = "20.00", fat = "10.00", carb = "40.00", sodium = "200.00"),
            ),
        )

        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-05")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val tuple = upsertTuples.first()
                assertEquals(2, tuple.getValue(4), "intake_days 按有记录的天数")
                // day1 200 kcal + day2 400 kcal → 均值 300
                assertEquals(BigDecimal("300.00"), tuple.getValue(5), "energy_avg = (200+400)/2")
                assertEquals(BigDecimal("15.00"), tuple.getValue(6), "protein_avg = (10+20)/2")
                assertEquals("摄入明显不足", tuple.getValue(10), "300/1100 → 明显不足")
                assertEquals(2, (tuple.getValue(12) as JsonArray).size(), "daily 必须保留逐日明细")
                assertEquals("dietitian-1", tuple.getValue(14), "generated_by 取认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `重复生成同周走ON CONFLICT覆盖不重复插入`(vertx: Vertx, ctx: VertxTestContext) {
        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-05")) { first, _ ->
            ctx.verify { assertEquals(201, first) }
            generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-05")) { second, _ ->
                ctx.verify {
                    assertEquals(201, second, "同周重复生成仍 201（覆盖更新）")
                    val upserts = executed.filter { it.contains("insert into dining.dining_nutrition_weekly_reports") }
                    assertEquals(2, upserts.size, "两次生成各执行一次 UPSERT")
                    assertTrue(
                        upserts.all { it.contains("on conflict (patient_id, week_start)") },
                        "覆盖必须由唯一键 ON CONFLICT 承载，实际: $upserts",
                    )
                    assertEquals(2, upsertTuples.size)
                    ctx.completeNow()
                }
            }
        }
    }

    @Test
    fun `生成默认取当周周一`(vertx: Vertx, ctx: VertxTestContext) {
        generate(vertx, JsonObject().put("patient_id", "patient-1")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "实际: ${body.encode()}")
                val weekStart = upsertTuples.first().getValue(2) as LocalDate
                assertEquals(1, weekStart.dayOfWeek.value, "缺省周首必须是周一，实际: $weekStart")
                assertEquals(weekStart, DiningConstants.weekStartOf(NutritionAnalysisService.today()))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `缺patient_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        generate(vertx, JsonObject().put("week_start", "2026-10-05")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("patient_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法week_start返回400`(vertx: Vertx, ctx: VertxTestContext) {
        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026/10/05")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("week_start"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-05")) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童无birth_date返回400`(vertx: Vertx, ctx: VertxTestContext) {
        patientBirth = null
        generate(vertx, JsonObject().put("patient_id", "patient-1").put("week_start", "2026-10-05")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("birth_date"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, authHandler = null) { port ->
            request(
                vertx,
                port,
                HttpMethod.POST,
                "/dining/v1/nutrition-weekly-reports/generate",
                JsonObject().put("patient_id", "patient-1"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "实际: ${body.encode()}")
                    ctx.completeNow()
                }
            }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    @Test
    fun `列表空结果返回records空与total0`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/nutrition-weekly-reports?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表按周过滤时同样归一到周一`(vertx: Vertx, ctx: VertxTestContext) {
        countTotal = 1L
        listRows = listOf(reportRow())

        get(vertx, "/dining/v1/nutrition-weekly-reports?patient_id=patient-1&week_start=2026-10-07") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(1L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                assertEquals(1, body.getJsonArray("records")?.size() ?: -1)
                assertEquals("2026-10-05", body.getJsonArray("records")!!.getJsonObject(0).getString("week_start"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun nutrition(
        energy: Double,
        protein: Double,
        fat: Double,
        carb: Double,
        sodium: Double,
    ): JsonObject = JsonObject()
        .put("basis_g", 100)
        .put("energy_kcal", energy)
        .put("protein_g", protein)
        .put("fat_g", fat)
        .put("carb_g", carb)
        .put("sodium_mg", sodium)

    private fun items(vararg pairs: Pair<String, Double>): JsonArray {
        val array = JsonArray()
        pairs.forEach { (name, amount) -> array.add(JsonObject().put("food_name", name).put("amount_g", amount)) }
        return array
    }

    private fun patientRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
        every { getLocalDate("birth_date") } returns patientBirth
    }

    private fun dateRow(date: LocalDate): Row = mockk(relaxed = true) {
        every { getLocalDate("intake_date") } returns date
    }

    /**
     * 摄入记录行：A3 修订（task-11）后总量以**记录内落库汇总**为唯一权威口径，
     * 因此 fixture 必须同时提供 `energy_kcal` 等列（与菜品库营养保持一致）。
     */
    private fun intakeRow(
        items: JsonArray,
        ratio: String,
        energy: String = "0",
        protein: String = "0",
        fat: String = "0",
        carb: String = "0",
        sodium: String = "0",
    ): Row = mockk(relaxed = true) {
        every { getJsonArray("items") } returns items
        every { getBigDecimal("intake_ratio") } returns BigDecimal(ratio)
        every { getBigDecimal("energy_kcal") } returns BigDecimal(energy)
        every { getBigDecimal("protein_g") } returns BigDecimal(protein)
        every { getBigDecimal("fat_g") } returns BigDecimal(fat)
        every { getBigDecimal("carb_g") } returns BigDecimal(carb)
        every { getBigDecimal("sodium_mg") } returns BigDecimal(sodium)
    }

    private fun dishRow(nutrition: JsonObject): Row = mockk(relaxed = true) {
        every { getJsonObject("nutrition") } returns nutrition
    }

    private fun reportIdRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns REPORT_ID
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun reportRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns REPORT_ID
        every { getString("patient_id") } returns "patient-1"
        every { getString("patient_name") } returns "小明"
        every { getLocalDate("week_start") } returns LocalDate.parse("2026-10-05")
        every { getLocalDate("week_end") } returns LocalDate.parse("2026-10-11")
        every { getInteger("intake_days") } returns 2
        every { getBigDecimal("energy_avg_kcal") } returns BigDecimal("300.00")
        every { getBigDecimal("protein_avg_g") } returns BigDecimal("15.00")
        every { getBigDecimal("fat_avg_g") } returns BigDecimal("7.50")
        every { getBigDecimal("carb_avg_g") } returns BigDecimal("30.00")
        every { getBigDecimal("sodium_avg_mg") } returns BigDecimal("150.00")
        every { getString("conclusion") } returns "摄入明显不足"
        every { getJsonArray("gaps") } returns JsonArray().add("能量")
        every { getJsonArray("daily") } returns JsonArray()
        every { getString("reference_version") } returns NutritionReference.VERSION
        every { getString("generated_by") } returns "dietitian-1"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-12T12:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-12T12:00:00+08:00")
    }

    private fun generate(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/nutrition-weekly-reports/generate", body)
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
        const val REPORT_ID = "01J8ZQ9K7M3N5P7R9T1V3X5Z73"
    }
}
