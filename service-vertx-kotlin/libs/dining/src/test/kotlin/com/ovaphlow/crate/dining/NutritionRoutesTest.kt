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
import java.util.Collections

/**
 * 054 A3：`GET /dining/v1/nutrition-analysis` 的**不连数据库**路由级护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. 读 `dining_meal_intake_records` → A2 引擎 → 返回热量/蛋白/脂肪/碳水/钠 + 年龄段参考值 +
 *     结论 + 缺口项 + 逐项能量（契约 §5.7）。
 *  2. 食物成分**菜品库优先**（`dining_dishes.nutrition`），未维护回落内置常量表。
 *  3. 每条记录的 `intake_ratio` 折算到份量上。
 *  4. **空数据不 500**：当天无记录 → 200 + 零值 + 「无摄入记录」。
 *  5. 结论由服务端派生（本接口是 GET，不接受任何结论入参）。
 *  6. 参数错误 400（缺 patient_id / 非法日期 / 非法餐次 / 缺 birth_date）；儿童不存在 404。
 */
@ExtendWith(VertxExtension::class)
class NutritionRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var patientBirth: LocalDate? = LocalDate.parse("2024-10-06")
    private var intakeRows: List<Row> = emptyList()
    private var dishNutrition: Map<String, JsonObject> = emptyMap()

    @BeforeEach
    fun setUp() {
        executed.clear()
        patientFound = true
        patientBirth = LocalDate.parse("2024-10-06")
        intakeRows = emptyList()
        dishNutrition = emptyMap()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(patientRow()) else rowSet())
                sql.contains("from dining.dining_meal_intake_records") ->
                    Future.succeededFuture(rowSet(*intakeRows.toTypedArray()))
                sql.contains("from dining.dining_dishes") -> {
                    val name = firstArg<Tuple>().getString(0)
                    val nutrition = dishNutrition[name]
                    Future.succeededFuture(if (nutrition == null) rowSet() else rowSet(dishRow(nutrition)))
                }
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `返回热量蛋白脂肪碳水钠与年龄段参考值和缺口项`(vertx: Vertx, ctx: VertxTestContext) {
        dishNutrition = mapOf("米饭" to nutrition(energy = 200.0, protein = 10.0, fat = 5.0, carb = 20.0, sodium = 100.0))
        // 记录内落库汇总（A1 登记时算出）：200 kcal/100g × 150g = 300
        intakeRows = listOf(
            intakeRow(
                items = items("米饭" to 150.0),
                ratio = "1.0",
                energy = "300.00",
                protein = "15.00",
                fat = "7.50",
                carb = "30.00",
                sodium = "150.00",
            ),
        )

        get(vertx, path(date = "2026-10-06", mealTime = "午餐")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "分析必须 200，实际: ${body.encode()}")
                assertEquals("patient-1", body.getString("patient_id"))
                assertEquals("小明", body.getString("patient_name"))
                assertEquals("2026-10-06", body.getString("intake_date"))
                assertEquals("午餐", body.getString("meal_time"))
                assertEquals(24, body.getInteger("age_months") ?: -1, "2024-10-06 → 2026-10-06 为 24 月")
                assertEquals("1-3y", body.getJsonObject("band")?.getString("code"))

                // 头部总量 = 记录内已落库的汇总（唯一权威口径）
                assertEquals(300.0, body.getDouble("energy_kcal") ?: -1.0, 0.011)
                assertEquals(15.0, body.getDouble("protein_g") ?: -1.0, 0.011)
                assertEquals(7.5, body.getDouble("fat_g") ?: -1.0, 0.011)
                assertEquals(30.0, body.getDouble("carb_g") ?: -1.0, 0.011)
                assertEquals(150.0, body.getDouble("sodium_mg") ?: -1.0, 0.011)

                // 年龄段参考值（1–3 岁）
                val reference = body.getJsonObject("reference")!!
                assertEquals(1100.0, reference.getDouble("energy_kcal") ?: -1.0, 0.001)
                assertEquals(25.0, reference.getDouble("protein_g") ?: -1.0, 0.001)
                assertEquals(700.0, reference.getDouble("sodium_mg") ?: -1.0, 0.001)

                assertEquals("摄入明显不足", body.getString("conclusion"), "结论必须服务端派生")
                assertEquals(
                    listOf("能量", "蛋白质", "脂肪", "碳水化合物"),
                    body.getJsonArray("gaps")?.map { it.toString() },
                )
                assertEquals(NutritionReference.VERSION, body.getString("reference_version"))

                val item = body.getJsonArray("items")!!.getJsonObject(0)
                assertEquals("米饭", item.getString("food_name"))
                assertEquals(300.0, item.getDouble("energy_kcal") ?: -1.0, 0.011)
                assertEquals(true, item.getBoolean("matched") ?: false)
                // 明细之和 == 返回总量
                assertEquals(
                    body.getDouble("energy_kcal") ?: -1.0,
                    itemEnergySum(body),
                    0.011,
                    "明细能量之和必须等于返回总量，实际: ${body.encode()}",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `菜品库无营养快照时明细计0但总量仍等于落库值`(vertx: Vertx, ctx: VertxTestContext) {
        // 米饭有菜品库快照；苹果没有 → 明细计 0 且 matched=false，且**不再**回落 A2 内置常量。
        // 总量以落库值为准（A1 登记时苹果同样计 0）。
        dishNutrition = mapOf("米饭" to nutrition(energy = 200.0, protein = 10.0, fat = 5.0, carb = 20.0, sodium = 100.0))
        intakeRows = listOf(
            intakeRow(
                items = items("米饭" to 100.0, "苹果" to 100.0),
                ratio = "1.0",
                energy = "200.00",
                protein = "10.00",
                fat = "5.00",
                carb = "20.00",
                sodium = "100.00",
            ),
        )

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(200.0, body.getDouble("energy_kcal") ?: -1.0, 0.011, "总量取落库值，不回落常量 53")
                assertEquals(listOf("苹果"), body.getJsonArray("unmatched")?.map { it.toString() })
                val items = body.getJsonArray("items")!!
                assertEquals(0.0, items.getJsonObject(1).getDouble("energy_kcal") ?: -1.0, 0.001, "无快照明细计 0")
                assertEquals(false, items.getJsonObject(1).getBoolean("matched") ?: true)
                assertEquals(body.getDouble("energy_kcal") ?: -1.0, itemEnergySum(body), 0.011, "明细之和 == 总量")
                assertEquals("全部", body.getString("meal_time"), "未指定餐次时聚合当天全部餐次")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `摄入比例折算到份量且明细与总量一致`(vertx: Vertx, ctx: VertxTestContext) {
        dishNutrition = mapOf("米饭" to nutrition(energy = 200.0, protein = 10.0, fat = 5.0, carb = 20.0, sodium = 100.0))
        // 记录落库值已是比例折算后的结果（300 × 0.5 = 150）
        intakeRows = listOf(
            intakeRow(
                items = items("米饭" to 150.0),
                ratio = "0.5",
                energy = "150.00",
                protein = "7.50",
                fat = "3.75",
                carb = "15.00",
                sodium = "75.00",
            ),
        )

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(150.0, body.getDouble("energy_kcal") ?: -1.0, 0.011, "落库值 300 × 0.5")
                assertEquals(7.5, body.getDouble("protein_g") ?: -1.0, 0.011)
                assertEquals(150.0, itemEnergySum(body), 0.011, "明细按 intake_ratio 折算后与总量一致")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `一天多条记录时总量等于记录内存量之和`(vertx: Vertx, ctx: VertxTestContext) {
        dishNutrition = mapOf("米饭" to nutrition(energy = 200.0, protein = 10.0, fat = 5.0, carb = 20.0, sodium = 100.0))
        intakeRows = listOf(
            intakeRow(items("米饭" to 100.0), "1.0", energy = "200.00", protein = "10.00", fat = "5.00", carb = "20.00", sodium = "100.00"),
            intakeRow(items("米饭" to 200.0), "1.0", energy = "400.00", protein = "20.00", fat = "10.00", carb = "40.00", sodium = "200.00"),
        )

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(600.0, body.getDouble("energy_kcal") ?: -1.0, 0.011, "200 + 400")
                assertEquals(30.0, body.getDouble("protein_g") ?: -1.0, 0.011, "10 + 20")
                assertEquals(2, body.getJsonArray("items")?.size() ?: -1)
                assertEquals(600.0, itemEnergySum(body), 0.011, "明细之和 == 返回总量")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `菜品营养登记后被清空时总量仍以落库值为准`(vertx: Vertx, ctx: VertxTestContext) {
        // 极端情形：登记后菜品营养被清空 → 明细无法复算（计 0、matched=false），
        // 但总量仍是登记时的冻结快照（Lead 裁定：落库值为唯一权威口径）。
        dishNutrition = emptyMap()
        intakeRows = listOf(
            intakeRow(items("米饭" to 100.0), "1.0", energy = "500.00", protein = "20.00", fat = "10.00", carb = "60.00", sodium = "5.00"),
        )

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(500.0, body.getDouble("energy_kcal") ?: -1.0, 0.011, "总量必须等于落库值")
                assertEquals(listOf("米饭"), body.getJsonArray("unmatched")?.map { it.toString() })
                assertEquals(0.0, itemEnergySum(body), 0.011, "无快照时明细计 0（预期差异，总量为权威）")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `当天无摄入记录返回零值结论且不500`(vertx: Vertx, ctx: VertxTestContext) {
        intakeRows = emptyList()

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "空数据不得 500，实际: ${body.encode()}")
                assertEquals(0.0, body.getDouble("energy_kcal") ?: -1.0, 0.001)
                assertEquals(0.0, body.getDouble("protein_g") ?: -1.0, 0.001)
                assertEquals(0.0, body.getDouble("sodium_mg") ?: -1.0, 0.001)
                assertEquals("无摄入记录", body.getString("conclusion"))
                assertEquals(0, body.getJsonArray("items")?.size() ?: -1)
                assertTrue(body.getJsonArray("gaps")!!.size() > 0, "空数据仍有缺口项，便于前端展示")
                assertEquals(1100.0, body.getJsonObject("reference")?.getDouble("energy_kcal") ?: -1.0, 0.001)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未收录食物计入unmatched且不报错`(vertx: Vertx, ctx: VertxTestContext) {
        intakeRows = listOf(intakeRow(items = items("火龙果" to 200.0), ratio = "1.0"))

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "未收录食物不得报错，实际: ${body.encode()}")
                assertEquals(listOf("火龙果"), body.getJsonArray("unmatched")?.map { it.toString() })
                assertEquals(0.0, body.getDouble("energy_kcal") ?: -1.0, 0.001)
                assertEquals("无摄入记录", body.getString("conclusion"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `结论随摄入量变化由服务端派生`(vertx: Vertx, ctx: VertxTestContext) {
        dishNutrition = mapOf("米饭" to nutrition(energy = 116.0, protein = 2.6, fat = 0.3, carb = 25.9, sodium = 2.5))
        intakeRows = listOf(
            intakeRow(
                items = items("米饭" to 900.0),
                ratio = "1.0",
                energy = "1044.00",
                protein = "23.40",
                fat = "2.70",
                carb = "233.10",
                sodium = "22.50",
            ),
        )

        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(1044.0, body.getDouble("energy_kcal") ?: -1.0, 0.011)
                assertEquals("摄入充足", body.getString("conclusion"), "94.9% → 摄入充足")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `缺patient_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/nutrition-analysis") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("patient_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法日期返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/nutrition-analysis?patient_id=patient-1&date=2026-13-45") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("date"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法餐次返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/dining/v1/nutrition-analysis?patient_id=patient-1&meal_time=${encode("夜宵")}") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid meal_time"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("patient not found"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童无birth_date返回400`(vertx: Vertx, ctx: VertxTestContext) {
        patientBirth = null
        get(vertx, path(date = "2026-10-06")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "无生日无法定年龄段，必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("birth_date"), body.encode())
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun path(date: String, mealTime: String? = null): String {
        val base = "/dining/v1/nutrition-analysis?patient_id=patient-1&date=$date"
        return if (mealTime == null) base else "$base&meal_time=${encode(mealTime)}"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

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
        pairs.forEach { (name, amount) ->
            array.add(JsonObject().put("food_name", name).put("amount_g", amount))
        }
        return array
    }

    private fun patientRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
        every { getString("name") } returns "小明"
        every { getLocalDate("birth_date") } returns patientBirth
    }

    /**
     * 摄入记录行：`items` + `intake_ratio` + **记录内已落库的汇总**（A1 的权威口径）。
     * 汇总缺省为 0，便于「未收录食物计 0」等用例。
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

    /** 明细能量之和（用于断言「明细之和 == 返回总量」）。 */
    private fun itemEnergySum(body: JsonObject): Double {
        val items = body.getJsonArray("items") ?: JsonArray()
        var sum = 0.0
        for (index in 0 until items.size()) {
            sum += items.getJsonObject(index).getDouble("energy_kcal") ?: 0.0
        }
        return sum
    }

    private fun dishRow(nutrition: JsonObject): Row = mockk(relaxed = true) {
        every { getJsonObject("nutrition") } returns nutrition
    }

    private fun get(vertx: Vertx, path: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, path).map { (status, body) -> handler(status, body) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun request(vertx: Vertx, port: Int, path: String): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(HttpMethod.GET, port, "127.0.0.1", path)
            .compose { req -> req.send() }
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
        val authHandler = Handler<RoutingContext> { rctx ->
            rctx.put("userId", "dietitian-1")
            rctx.next()
        }
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
}
