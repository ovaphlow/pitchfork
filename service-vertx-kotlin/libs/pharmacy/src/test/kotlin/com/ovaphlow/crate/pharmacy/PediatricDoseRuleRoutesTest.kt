package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 054 D2 儿科用药规则库专用 HTTP 路由测试（**不连接 PostgreSQL**）。
 *
 * 镜像生产装配：把 `PharmacyRoutes` **聚合路由**挂到 `/crate-api/pharmacy/v1` 前缀下（与 `Main.kt` 一致），
 * 用 mockk 桩驱动 `Pool.preparedQuery(...).execute(Tuple)`，真实 HTTP 请求覆盖契约：
 *
 * - 规则建/查/改（201 / 列表 `{records, meta:{total}}` / 200）、空列表 `records:[]` + `total:0`；
 * - `material_id` 对 `public.materials` 的存在性校验（未知药品 404，创建/改号两条路径都校验）；
 * - 参数化 SQL（`$1..$n` + Tuple 绑定值断言，无字符串拼接）；
 * - `created_by` 取认证主体，客户端提交 `operator`/`created_by` 一律拒绝；
 * - 用量计算：按体重折算、`capped`、规则来源、区间缺失回落、无规则 404、非法体重 400；
 * - **046 向后兼容**：既有 `POST /dispenses` 不传新字段仍是既有 400 校验口径（不是 404/500）。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PediatricDoseRuleRoutesTest {

    private companion object {
        private const val PORT = 18462
        private const val BASE = "/crate-api/pharmacy/v1"
        private const val MATERIAL = "01J8ZQ6W9K7M3N5P2R4T6V8X0A"
        private const val RULE_ID = "01J8ZQ6W9K7M3N5P2R4T6V8X0B"
        private const val USER = "pharm-user"
    }

    private class DatabaseStub {
        var materialRows: RowSet<Row> = rowSet()
        var ruleRows: RowSet<Row> = rowSet()
        var listRows: RowSet<Row> = rowSet()
        var countRows: RowSet<Row> = rowSet(mockRow(mapOf("total" to 0L)))

        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        val pool = mockk<Pool>()
        private val pq = mockk<io.vertx.sqlclient.PreparedQuery<RowSet<Row>>>()
        private var lastSql = ""

        init {
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                Future.succeededFuture(routeSql(sql))
            }
            every { pq.execute() } answers {
                tuples.add(lastSql to emptyList())
                Future.succeededFuture(rowSet())
            }
        }

        fun insertTuple(): List<Any?> =
            tuples.first { it.first.startsWith("insert into pharmacy.pharmacy_pediatric_dose_rules") }.second

        fun updateTuple(): List<Any?> =
            tuples.first { it.first.startsWith("update pharmacy.pharmacy_pediatric_dose_rules") }.second

        private fun record(sql: String) {
            lastSql = normalized(sql)
            queries.add(lastSql)
        }

        private fun routeSql(sql: String): RowSet<Row> = when {
            sql.contains("public.materials") -> materialRows
            sql.contains("select count") -> countRows
            sql.startsWith("insert into pharmacy.pharmacy_pediatric_dose_rules") -> rowSet()
            sql.startsWith("update pharmacy.pharmacy_pediatric_dose_rules") -> rowSet()
            sql.contains("from pharmacy.pharmacy_pediatric_dose_rules") && sql.contains("limit") -> listRows
            sql.contains("from pharmacy.pharmacy_pediatric_dose_rules") -> ruleRows
            else -> rowSet()
        }
    }

    private val stub = DatabaseStub()
    private var server: io.vertx.core.http.HttpServer? = null

    @BeforeEach
    fun resetTraffic() {
        stub.queries.clear()
        stub.tuples.clear()
        stub.materialRows = rowSet()
        stub.ruleRows = rowSet()
        stub.listRows = rowSet()
        stub.countRows = rowSet(mockRow(mapOf("total" to 0L)))
    }

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        val aggregate = PharmacyRoutes.create(
            vertx,
            stub.pool,
            mockk<MedicalOrderReader>(relaxed = true),
            mockk<InventoryOutboundPort>(relaxed = true),
            mockk<InventoryInboundPort>(relaxed = true),
            mockk<InventoryRequisitionTransferPort>(relaxed = true),
            mockk<InventoryPurchaseReceiptPort>(relaxed = true),
            Handler { it.next() },
        )
        val root = Router.router(vertx)
        // 生产同款：认证总闸（提供 ctx["userId"]）→ `/crate-api/pharmacy/v1/*` 聚合路由
        root.route().handler(
            Handler<RoutingContext> { ctx2 ->
                ctx2.put("userId", USER)
                ctx2.next()
            },
        )
        root.route("$BASE/*").subRouter(aggregate)
        vertx.createHttpServer().requestHandler(root).listen(PORT).onComplete { ar ->
            if (ar.succeeded()) {
                server = ar.result()
                ctx.completeNow()
            } else {
                ctx.failNow(ar.cause())
            }
        }
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // —— 创建 ——

    @Test
    fun `创建规则 201 并落目录名称快照`(vertx: Vertx, ctx: VertxTestContext) {
        stub.materialRows = rowSet(mockRow(mapOf("id" to MATERIAL, "name" to "布洛芬混悬液")))
        stub.ruleRows = rowSet(ruleRow(id = RULE_ID, materialName = "布洛芬混悬液"))

        request(
            vertx,
            HttpMethod.POST,
            "$BASE/pediatric-dose-rules",
            JsonObject()
                .put("material_id", MATERIAL)
                .put("dose_per_kg", 15)
                .put("single_dose_max", 400)
                .put("daily_dose_max", 1200)
                .put("doses_per_day", 3)
                .put("unit", "mg"),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(201, status, "body=$body")
                assertEquals(RULE_ID, body.getString("id"))
                assertEquals("布洛芬混悬液", body.getString("material_name"))
                assertEquals("启用", body.getString("status"), "status 缺省必须是启用")

                val inserted = stub.insertTuple()
                assertEquals(26, (inserted[0] as String).length, "服务端生成 26 位 ULID")
                assertEquals(MATERIAL, inserted[1])
                assertEquals("布洛芬混悬液", inserted[2], "material_name 取自 public.materials 快照")
                assertEquals(0, (inserted[7] as BigDecimal).compareTo(BigDecimal("15")))
                assertEquals(3, inserted[11])
                assertEquals("mg", inserted[12])
                assertEquals("启用", inserted[13])
                assertEquals(USER, inserted[15], "created_by 取认证主体")

                val insertSql = stub.queries.first { it.startsWith("insert into pharmacy.pharmacy_pediatric_dose_rules") }
                assertTrue(insertSql.contains("\$18"), "必须使用 \$1..\$n 参数化占位：$insertSql")
                assertFalse(insertSql.contains(MATERIAL), "SQL 文本不得拼接用户输入")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建规则时未知药品 404 且不写库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.materialRows = rowSet()

        request(
            vertx,
            HttpMethod.POST,
            "$BASE/pediatric-dose-rules",
            JsonObject().put("material_id", "UNKNOWN-MATERIAL").put("fixed_dose", 5).put("unit", "mL"),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(404, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("material not found"), "body=$body")
                assertTrue(
                    stub.tuples.none { it.first.startsWith("insert into") },
                    "药品不存在时不得写规则表",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建规则参数校验失败 400`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            JsonObject().put("unit", "mg").put("fixed_dose", 5) to "material_id is required",
            JsonObject().put("material_id", MATERIAL).put("fixed_dose", 5) to "unit is required",
            JsonObject().put("material_id", MATERIAL).put("unit", "mg") to "dose_per_kg or fixed_dose is required",
            JsonObject()
                .put("material_id", MATERIAL).put("unit", "mg").put("fixed_dose", 5)
                .put("age_min_months", 36).put("age_max_months", 6) to "age_min_months",
            JsonObject()
                .put("material_id", MATERIAL).put("unit", "mg").put("fixed_dose", 5)
                .put("weight_min_kg", 20).put("weight_max_kg", 5) to "weight_min_kg",
            JsonObject().put("material_id", MATERIAL).put("unit", "mg").put("fixed_dose", 0) to "fixed_dose",
            JsonObject()
                .put("material_id", MATERIAL).put("unit", "mg").put("fixed_dose", 5)
                .put("doses_per_day", 0) to "doses_per_day",
            JsonObject().put("material_id", MATERIAL).put("unit", "mg").put("fixed_dose", 5)
                .put("status", "ACTIVE") to "invalid status",
        )

        cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
            chain.compose {
                request(vertx, HttpMethod.POST, "$BASE/pediatric-dose-rules", body).map { (status, response) ->
                    assertEquals(400, status, "body=$body response=$response")
                    assertTrue(
                        response.getString("error")!!.contains(expected),
                        "期望错误含 '$expected'，实际: $response",
                    )
                }
            }
        }.onComplete { ar ->
            ctx.verify {
                ar.result()
                assertTrue(stub.tuples.none { it.first.startsWith("insert into") }, "非法输入不得写库")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建规则拒绝客户端提交的服务端字段`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE/pediatric-dose-rules",
            JsonObject()
                .put("material_id", MATERIAL).put("unit", "mg").put("fixed_dose", 5)
                .put("created_by", "hacker"),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(400, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("unknown fields"), "body=$body")
                assertTrue(stub.tuples.none { it.first.startsWith("insert into") })
                ctx.completeNow()
            }
        }
    }

    // —— 查询 ——

    @Test
    fun `列表空结果返回 records 空数组与 total 0`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "$BASE/pediatric-dose-rules").onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(200, status, "body=$body")
                assertEquals(0, body.getJsonArray("records").size())
                assertEquals(0L, body.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表按 material_id 与 status 过滤且绑定值参数化`(vertx: Vertx, ctx: VertxTestContext) {
        stub.countRows = rowSet(mockRow(mapOf("total" to 1L)))
        stub.listRows = rowSet(ruleRow(id = RULE_ID))

        // 中文枚举必须按 UTF-8 百分号编码进入查询串（HTTP 请求行不允许裸非 ASCII）
        val statusParam = java.net.URLEncoder.encode("启用", "UTF-8")
        request(
            vertx,
            HttpMethod.GET,
            "$BASE/pediatric-dose-rules?material_id=$MATERIAL&status=$statusParam&limit=10&offset=5",
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(200, status, "body=$body")
                assertEquals(1, body.getJsonArray("records").size())
                assertEquals(1L, body.getJsonObject("meta").getLong("total"))

                val countSql = stub.queries.first { it.contains("select count") }
                assertTrue(countSql.contains("material_id = \$1"), "count SQL=$countSql")
                assertTrue(countSql.contains("status = \$2"), "count SQL=$countSql")
                assertFalse(countSql.contains(MATERIAL), "过滤值不得拼进 SQL")

                val listQuery = stub.tuples.first { it.first.contains("limit") }
                assertEquals(listOf(MATERIAL, "启用", 10, 5), listQuery.second)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `列表非法 status 400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "$BASE/pediatric-dose-rules?status=ACTIVE").onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(400, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("invalid status"))
                ctx.completeNow()
            }
        }
    }

    // —— 修改 ——

    @Test
    fun `修改规则 200 并更新白名单字段`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet(ruleRow(id = RULE_ID, status = "停用", dosePerKg = BigDecimal("12")))

        request(
            vertx,
            HttpMethod.PUT,
            "$BASE/pediatric-dose-rules/$RULE_ID",
            JsonObject().put("dose_per_kg", 12).put("status", "停用").put("doses_per_day", 4),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(200, status, "body=$body")
                assertEquals("停用", body.getString("status"))

                val updated = stub.updateTuple()
                assertEquals(RULE_ID, updated[0])
                assertEquals(MATERIAL, updated[1], "未提交 material_id 时保持原值")
                assertEquals(0, (updated[7] as BigDecimal).compareTo(BigDecimal("12")))
                assertEquals(4, updated[11])
                assertEquals("停用", updated[13])
                assertNotNull(updated[15], "updated_at 必须写入")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `修改不存在的规则 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet()

        request(
            vertx,
            HttpMethod.PUT,
            "$BASE/pediatric-dose-rules/$RULE_ID",
            JsonObject().put("status", "停用"),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(404, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("not found"))
                assertTrue(stub.tuples.none { it.first.startsWith("update") })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `改号到不存在的药品 404 且不写库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet(ruleRow(id = RULE_ID, dosePerKg = BigDecimal("10")))
        stub.materialRows = rowSet()

        request(
            vertx,
            HttpMethod.PUT,
            "$BASE/pediatric-dose-rules/$RULE_ID",
            JsonObject().put("material_id", "UNKNOWN-MATERIAL"),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(404, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("material not found"), "body=$body")
                assertTrue(stub.tuples.none { it.first.startsWith("update") })
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `修改后剂量定义被清空 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet(ruleRow(id = RULE_ID, dosePerKg = null, fixedDose = BigDecimal("5")))

        request(
            vertx,
            HttpMethod.PUT,
            "$BASE/pediatric-dose-rules/$RULE_ID",
            JsonObject().put("fixed_dose", null),
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(400, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("dose_per_kg or fixed_dose is required"), "body=$body")
                ctx.completeNow()
            }
        }
    }

    // —— 用量计算 ——

    @Test
    fun `按体重折算并返回上限标志与规则来源`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet(
            ruleRow(
                id = RULE_ID,
                weightMinKg = BigDecimal("5"),
                weightMaxKg = BigDecimal("20"),
                dosePerKg = BigDecimal("15"),
                singleDoseMax = BigDecimal("400"),
                dosesPerDay = 3,
            ),
        )

        request(
            vertx,
            HttpMethod.GET,
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=12.5&age_months=24",
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(200, status, "body=$body")
                assertEquals(MATERIAL, body.getString("material_id"))
                assertEquals(187.5, (body.getValue("single_dose") as Number).toDouble(), 0.0001)
                assertEquals(562.5, (body.getValue("daily_dose") as Number).toDouble(), 0.0001)
                assertEquals("mg", body.getString("unit"))
                assertFalse(body.getBoolean("capped"), "body=$body")
                assertEquals(RULE_ID, body.getString("rule_id"))
                assertEquals("WEIGHT_BAND", body.getString("source"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `触发上限时 capped 为 true`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet(
            ruleRow(id = RULE_ID, dosePerKg = BigDecimal("15"), singleDoseMax = BigDecimal("100")),
        )

        request(
            vertx,
            HttpMethod.GET,
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=40",
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(200, status, "body=$body")
                assertEquals(100.0, (body.getValue("single_dose") as Number).toDouble(), 0.0001)
                assertTrue(body.getBoolean("capped"), "body=$body")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `区间缺失回落到通用规则并标记来源`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet(
            ruleRow(id = "band-rule", ageMinMonths = 6, ageMaxMonths = 12, fixedDose = BigDecimal("2")),
            ruleRow(id = "generic-rule", fixedDose = BigDecimal("5"), dosesPerDay = 2),
        )

        request(
            vertx,
            HttpMethod.GET,
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&age_months=36",
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(200, status, "body=$body")
                assertEquals("generic-rule", body.getString("rule_id"))
                assertEquals("GENERIC_FALLBACK", body.getString("source"))
                assertEquals(5.0, (body.getValue("single_dose") as Number).toDouble(), 0.0001)
                assertEquals(10.0, (body.getValue("daily_dose") as Number).toDouble(), 0.0001)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `无可用规则 404 而不是 0 剂量`(vertx: Vertx, ctx: VertxTestContext) {
        stub.ruleRows = rowSet()

        request(
            vertx,
            HttpMethod.GET,
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=12",
        ).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(404, status, "body=$body")
                assertTrue(body.getString("error")!!.contains("no enabled pediatric dose rule"), "body=$body")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计算入口非法参数 400`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            "$BASE/pediatric-dose-calculations?weight_kg=12" to "material_id is required",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=0" to "weight_kg must be positive",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=-3" to "weight_kg must be positive",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=abc" to "weight_kg must be a number",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=NaN" to "weight_kg must be a number",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&weight_kg=Infinity" to "weight_kg must be a number",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&age_months=-1" to "age_months must not be negative",
            "$BASE/pediatric-dose-calculations?material_id=$MATERIAL&age_months=abc" to "age_months must be a whole number",
        )

        cases.fold(Future.succeededFuture<Unit>()) { chain, (path, expected) ->
            chain.compose {
                request(vertx, HttpMethod.GET, path).map { (status, response) ->
                    assertEquals(400, status, "path=$path response=$response")
                    assertTrue(
                        response.getString("error")!!.contains(expected),
                        "期望错误含 '$expected'，实际: $response",
                    )
                }
            }
        }.onComplete { ar ->
            ctx.verify {
                ar.result()
                assertTrue(stub.tuples.none { it.first.contains("from pharmacy.pharmacy_pediatric_dose_rules") })
                ctx.completeNow()
            }
        }
    }

    // —— 046 向后兼容（D2 不修改既有发药链）——

    @Test
    fun `既有 dispenses 路由不传新字段仍是既有校验口径`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$BASE/dispenses", JsonObject()).onComplete { ar ->
            ctx.verify {
                val (status, body) = ar.result()
                assertEquals(400, status, "既有消费方不传新字段不得变成 404/500：body=$body")
                assertEquals("dispense_no is required", body.getString("error"), "既有校验口径不得改变")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `未知药房路由仍 404`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$BASE/not-a-pharmacy-route", JsonObject()).onComplete { ar ->
            ctx.verify {
                val (status, _) = ar.result()
                assertEquals(404, status, "对照：404 只应来自未注册路由")
                ctx.completeNow()
            }
        }
    }

    // —— HTTP 客户端 ——

    private fun request(
        vertx: Vertx,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> =
        vertx.createHttpClient()
            .request(method, PORT, "localhost", path)
            .compose { req ->
                if (body != null) {
                    req.putHeader("Content-Type", "application/json")
                    req.send(body.encode())
                } else {
                    req.send()
                }
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = if (buffer.length() > 0) {
                        try {
                            buffer.toJsonObject()
                        } catch (e: Exception) {
                            JsonObject().put("raw", buffer.toString())
                        }
                    } else {
                        JsonObject()
                    }
                    resp.statusCode() to json
                }
            }
}

// ========================================================================
//  mockk 桩辅助（与 PurchaseOrdersRoutesTest / RequisitionRoutesTest 同构；
//  Kotlin 顶层 private 为文件可见，故本文件自带一份）
// ========================================================================

private fun ruleRow(
    id: String,
    materialId: String = "01J8ZQ6W9K7M3N5P2R4T6V8X0A",
    materialName: String = "布洛芬混悬液",
    ageMinMonths: Int? = null,
    ageMaxMonths: Int? = null,
    weightMinKg: BigDecimal? = null,
    weightMaxKg: BigDecimal? = null,
    dosePerKg: BigDecimal? = null,
    fixedDose: BigDecimal? = null,
    singleDoseMax: BigDecimal? = null,
    dailyDoseMax: BigDecimal? = null,
    dosesPerDay: Int? = null,
    unit: String = "mg",
    status: String = "启用",
    note: String? = null,
): Row = mockRow(
    mapOf(
        "id" to id,
        "material_id" to materialId,
        "material_name" to materialName,
        "age_min_months" to ageMinMonths,
        "age_max_months" to ageMaxMonths,
        "weight_min_kg" to weightMinKg,
        "weight_max_kg" to weightMaxKg,
        "dose_per_kg" to dosePerKg,
        "fixed_dose" to fixedDose,
        "single_dose_max" to singleDoseMax,
        "daily_dose_max" to dailyDoseMax,
        "doses_per_day" to dosesPerDay,
        "unit" to unit,
        "status" to status,
        "note" to note,
        "created_by" to "pharm-user",
        "created_at" to OffsetDateTime.now(),
        "updated_at" to OffsetDateTime.now(),
    ),
)

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getBoolean(any<String>()) } answers { values[firstArg<String>()] as? Boolean }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<io.vertx.sqlclient.RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val result = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) {
        result.add(tuple.getValue(i))
    }
    return result
}
