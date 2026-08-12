package com.ovaphlow.crate.healthcare

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
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 账单生成与手工加项（BillService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 生成 201 且自动明细金额正确（床位/护理/伙食）；首/尾月账期裁剪
 *   - 手工加项成功且可覆盖单价（缺省取字典单价）
 *   - 同 encounter 同账期重复生成 409
 *   - 护理等级无对应启用字典单价 400；停用字典项不可用于新账单/加项 400
 *   - 未认证 401；输入校验 400 不触发 SQL
 */
@ExtendWith(VertxExtension::class)
class BillServiceTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * insert 成功后自动追加到 bills/billItems 状态，查询据此派生，
     * 使「生成 → 加项 → 重算合计 → 详情」链路可程序化演进。
     */
    private class DatabaseStub(
        var encounters: RowSet<Row> = rowSet(),
        var feeItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var assessments: MutableList<Map<String, Any?>> = mutableListOf(),
        var mealsByEncounter: MutableMap<String, List<String>> = mutableMapOf(),
        var bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var billItems: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                val values = tupleValues(firstArg())
                tuples.add(sql to values)
                when {
                    sql.contains("insert into healthcare.bills") -> {
                        bills.add(
                            mutableMapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "period_start" to values[2],
                                "period_end" to values[3],
                                "status" to values[4],
                                "total_amount" to values[5],
                                "created_at" to values[6],
                                "updated_at" to values[7],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("insert into healthcare.bill_items") -> {
                        billItems.add(
                            mapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "source" to values[2],
                                "item_code" to values[3],
                                "item_name" to values[4],
                                "unit_price" to values[5],
                                "quantity" to values[6],
                                "amount" to values[7],
                                "created_at" to values[8],
                                "updated_at" to values[9],
                                "remark" to values.getOrNull(10),
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["total_amount"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("from healthcare.encounters") -> Future.succeededFuture(encounters)
                    sql.contains("from healthcare.fee_items") && sql.contains("status = $") ->
                        Future.succeededFuture(
                            rowSet(*feeItems.filter { it["status"] == values.getOrNull(0) }.map { mockRow(it) }.toTypedArray()),
                        )
                    sql.contains("from healthcare.fee_items") ->
                        Future.succeededFuture(
                            feeItems.firstOrNull { it["id"] == values.getOrNull(0) }?.let { rowSet(mockRow(it)) } ?: rowSet(),
                        )
                    sql.contains("from nursing.nursing_assessments") -> {
                        val scoped = assessments.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (values.getOrNull(1) == null || !(it["assess_date"] as LocalDate).isAfter(values[1] as LocalDate))
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from dining.dining_meal_executions") -> {
                        val statuses = mealsByEncounter[values.getOrNull(0)] ?: emptyList()
                        Future.succeededFuture(rowSet(*statuses.map { mockRow(mapOf("status" to it)) }.toTypedArray()))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") && sql.contains("period_start") -> {
                        val count = bills.count {
                            it["encounter_id"] == values.getOrNull(0) &&
                                it["period_start"] == values.getOrNull(1) &&
                                it["period_end"] == values.getOrNull(2)
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") -> {
                        val count = bills.count { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    sql.contains("sum(") && sql.contains("from healthcare.bill_items") -> {
                        val scoped = billItems.filter { it["bill_id"] == values.getOrNull(0) }
                        val total = scoped.fold(BigDecimal.ZERO) { acc, item ->
                            acc.add(item["amount"] as BigDecimal)
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to total))))
                    }
                    sql.contains("from healthcare.bill_items") -> {
                        val scoped = billItems.filter { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from healthcare.bills") && sql.contains("order by") -> {
                        val scoped = bills
                            .filter { it["encounter_id"] == values.getOrNull(0) }
                            .sortedWith(
                                compareByDescending<MutableMap<String, Any?>> { it["period_start"] as LocalDate }
                                    .thenByDescending { it["id"] as String },
                            )
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from healthcare.bills") -> {
                        val row = bills.firstOrNull { it["id"] == values.getOrNull(0) }?.let { mockRow(it) }
                        Future.succeededFuture(row?.let { rowSet(it) } ?: rowSet())
                    }
                    else -> Future.succeededFuture(rowSet())
                }
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }
    }

    // ——— fixture 构造 ———

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
        )
        base.putAll(overrides)
        return base
    }

    private fun feeItemRow(
        id: String,
        category: String,
        name: String,
        price: String,
        status: String = "启用",
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "category" to category,
            "name" to name,
            "unit_price" to BigDecimal(price),
            "status" to status,
        )

    private fun assessmentRow(encounterId: String, date: String, createdAt: String, level: String): Map<String, Any?> =
        mapOf(
            "encounter_id" to encounterId,
            "assess_date" to LocalDate.parse(date),
            "created_at" to OffsetDateTime.parse(createdAt),
            "result_level" to level,
        )

    private fun billRow(
        id: String,
        encounterId: String,
        periodStart: String,
        periodEnd: String,
        total: String,
        status: String = "待缴费",
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to id,
            "encounter_id" to encounterId,
            "period_start" to LocalDate.parse(periodStart),
            "period_end" to LocalDate.parse(periodEnd),
            "status" to status,
            "total_amount" to BigDecimal(total),
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    private fun billItemRow(
        id: String,
        billId: String,
        source: String,
        itemCode: String,
        itemName: String,
        price: String,
        qty: String,
        amount: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "bill_id" to billId,
            "source" to source,
            "item_code" to itemCode,
            "item_name" to itemName,
            "unit_price" to BigDecimal(price),
            "quantity" to BigDecimal(qty),
            "amount" to BigDecimal(amount),
            "remark" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    /** 标准满月计费环境：床位 100/天、护理 中度依赖 80/天、伙食 30/餐。 */
    private fun fullMonthStub(): DatabaseStub = DatabaseStub(
        encounters = rows(encounterRow()),
        feeItems = mutableListOf(
            feeItemRow("fee-bed", "床位费", "标准床位", "100"),
            feeItemRow("fee-nurse", "护理费", "中度依赖", "80"),
            feeItemRow("fee-meal", "伙食费", "三餐", "30"),
        ),
        assessments = mutableListOf(
            assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "中度依赖"),
        ),
        mealsByEncounter = mutableMapOf("enc-1" to listOf("正常", "正常", "部分")),
    )

    private fun generateBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject().put("month", "2026-08")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun addBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject().put("item_id", "fee-other").put("unit_price", 500).put("quantity", 2).put("remark", "自费药")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun causeOf(future: Future<*>): Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get()
            throw AssertionError("expected future to fail")
        } catch (error: Throwable) {
            var cause = error
            while (cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException) {
                cause = cause.cause ?: break
            }
            return cause
        }
    }

    private fun amount(value: Any?): BigDecimal =
        BigDecimal.valueOf((value as Number).toDouble())

    // ——— 1. 生成：输入校验与资格 ———

    @Test
    fun `生成校验失败返回400且不触发SQL`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val service = BillService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.generate("enc-1", body, "cashier-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(JsonObject(), "month is required")
        expectInvalid(generateBody(mapOf("month" to "2026-13")), "YYYY-MM")
        expectInvalid(generateBody(mapOf("month" to "2026-8")), "YYYY-MM")
        expectInvalid(generateBody(mapOf("month" to "abc")), "YYYY-MM")
        expectInvalid(generateBody(mapOf("period_start" to "2026-08-01")), "unsupported bill keys")
        expectInvalid(generateBody(mapOf("month" to 202608)), "month must be a string")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
    }

    @Test
    fun `encounter不存在返回404`() {
        val stub = DatabaseStub(encounters = rowSet())
        val cause = causeOf(BillService(stub.pool).generate("missing", generateBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("encounter not found") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
    }

    @Test
    fun `encounter无入住日期或账期无交集返回400`() {
        // 无入住日期
        val noAdmit = DatabaseStub(encounters = rows(encounterRow(mapOf("admit_date" to null))))
        val cause1 = causeOf(BillService(noAdmit.pool).generate("enc-1", generateBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("no admit date") == true, "got: ${cause1.message}")

        // 账期前已离院
        val dischargedBefore = DatabaseStub(
            encounters = rows(
                encounterRow(
                    mapOf("discharge_date" to OffsetDateTime.parse("2026-07-31T10:00:00+08:00")),
                ),
            ),
        )
        val cause2 = causeOf(BillService(dischargedBefore.pool).generate("enc-1", generateBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("does not overlap") == true, "got: ${cause2.message}")

        // 入住日期晚于账期
        val admittedAfter = DatabaseStub(
            encounters = rows(
                encounterRow(mapOf("admit_date" to OffsetDateTime.parse("2026-09-01T00:00:00+08:00"))),
            ),
        )
        val cause3 = causeOf(BillService(admittedAfter.pool).generate("enc-1", generateBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause3)
        assertTrue(cause3.message?.contains("does not overlap") == true, "got: ${cause3.message}")

        for (stub in listOf(noAdmit, dischargedBefore, admittedAfter)) {
            assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
        }
    }

    // ——— 2. 生成：自动计费金额正确 ———

    @Test
    fun `生成账单201自动明细金额正确床位护理伙食`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fullMonthStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", generateBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "生成账单必须 201")
                        assertEquals("enc-1", body.getString("encounter_id"))
                        assertEquals("2026-08-01", body.getString("period_start"))
                        assertEquals("2026-08-31", body.getString("period_end"))
                        assertEquals("待缴费", body.getString("status"), "账单状态初始必须为 待缴费")
                        assertTrue(body.getString("id").length == 26, "账单必须生成 26 位 ULID")
                        assertNotNull(body.getString("created_at"))

                        val items = body.getJsonArray("items")
                        assertEquals(3, items.size(), "自动明细必须包含床位/护理/伙食三项")

                        val bed = items.getJsonObject(0)
                        assertEquals("自动", bed.getString("source"))
                        assertEquals("fee-bed", bed.getString("item_code"))
                        assertEquals("标准床位", bed.getString("item_name"))
                        assertEquals(0, BigDecimal("100").compareTo(amount(bed.getValue("unit_price"))))
                        assertEquals(0, BigDecimal("31").compareTo(amount(bed.getValue("quantity"))), "床位数量 = 整月 31 天")
                        assertEquals(0, BigDecimal("3100.00").compareTo(amount(bed.getValue("amount"))))

                        val nursing = items.getJsonObject(1)
                        assertEquals("自动", nursing.getString("source"))
                        assertEquals("fee-nurse", nursing.getString("item_code"))
                        assertEquals("中度依赖", nursing.getString("item_name"))
                        assertEquals(0, BigDecimal("31").compareTo(amount(nursing.getValue("quantity"))))
                        assertEquals(0, BigDecimal("2480.00").compareTo(amount(nursing.getValue("amount"))))

                        val meal = items.getJsonObject(2)
                        assertEquals("自动", meal.getString("source"))
                        assertEquals("fee-meal", meal.getString("item_code"))
                        assertEquals("三餐", meal.getString("item_name"))
                        assertEquals(0, BigDecimal("2.5").compareTo(amount(meal.getValue("quantity"))), "正常×2 + 部分×1 = 2.5 餐")
                        assertEquals(0, BigDecimal("75.00").compareTo(amount(meal.getValue("amount"))))

                        assertEquals(
                            0,
                            BigDecimal("5655.00").compareTo(amount(body.getValue("total_amount"))),
                            "合计 = 3100 + 2480 + 75",
                        )

                        // 落库：1 账单 + 3 自动明细，状态 待缴费
                        assertEquals(1, stub.bills.size)
                        assertEquals("待缴费", stub.bills.single()["status"])
                        assertEquals(3, stub.billItems.size)
                        assertTrue(stub.billItems.all { it["source"] == "自动" })
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `首尾月账期裁剪到实际在院区间`(vertx: Vertx, ctx: VertxTestContext) {
        // 08-10 入住、08-20 离院：账期裁剪为 08-10..08-20，床位 11 天；无评估不计护理
        val stub = DatabaseStub(
            encounters = rows(
                encounterRow(
                    mapOf(
                        "admit_date" to OffsetDateTime.parse("2026-08-10T09:00:00+08:00"),
                        "discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                    ),
                ),
            ),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-meal", "伙食费", "三餐", "30"),
            ),
            mealsByEncounter = mutableMapOf("enc-1" to listOf("正常")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", generateBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status)
                        assertEquals("2026-08-10", body.getString("period_start"), "账期起裁剪到入住日")
                        assertEquals("2026-08-20", body.getString("period_end"), "账期止裁剪到离院日")
                        val items = body.getJsonArray("items")
                        assertEquals(2, items.size(), "无护理评估时只有床位与伙食")
                        val bed = items.getJsonObject(0)
                        assertEquals(0, BigDecimal("11").compareTo(amount(bed.getValue("quantity"))), "闭区间 08-10..08-20 = 11 天")
                        assertEquals(0, BigDecimal("1100.00").compareTo(amount(bed.getValue("amount"))))
                        assertEquals(0, BigDecimal("30.00").compareTo(amount(items.getJsonObject(1).getValue("amount"))))
                        assertEquals(0, BigDecimal("1130.00").compareTo(amount(body.getValue("total_amount"))))
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `伙食无就餐执行时不产生伙食明细`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-nurse", "护理费", "中度依赖", "80"),
            ),
            assessments = mutableListOf(
                assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "中度依赖"),
            ),
        )
        val bill = BillService(stub.pool)
            .generate("enc-1", generateBody(), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2, bill.getJsonArray("items").size(), "无就餐执行不得产生伙食明细")
        assertEquals(0, BigDecimal("5580.00").compareTo(amount(bill.getValue("total_amount"))), "合计 = 3100 + 2480")
        // 伙食费字典项缺失也不报错（无餐次无需单价）
        assertEquals(2, stub.billItems.size)
    }

    // ——— 3. 重复生成 409 / 字典单价缺失 400 ———

    @Test
    fun `同encounter同账期重复生成返回409`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fullMonthStub()
        stub.bills.add(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00"))
        val insertsBefore = stub.tuples.count { it.first.contains("insert into healthcare.bills") }
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", generateBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "同账期重复生成必须 409")
                        assertTrue(body.getString("error")?.contains("already exists") == true, "got: ${body.getString("error")}")
                        assertEquals(
                            insertsBefore,
                            stub.tuples.count { it.first.contains("insert into healthcare.bills") },
                            "重复生成不得写入新账单",
                        )
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `护理等级无对应字典单价返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(feeItemRow("fee-bed", "床位费", "标准床位", "100")),
            assessments = mutableListOf(
                assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "特级护理"),
            ),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", generateBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "护理等级无对应启用字典单价必须 400")
                        assertTrue(
                            body.getString("error")?.contains("no enabled fee item for nursing level") == true,
                            "got: ${body.getString("error")}",
                        )
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `停用字典项不可用于新账单返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(feeItemRow("fee-bed", "床位费", "标准床位", "100", status = "停用")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", generateBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "床位费无启用字典项必须 400")
                        assertTrue(
                            body.getString("error")?.contains("no enabled fee item for category 床位费") == true,
                            "got: ${body.getString("error")}",
                        )
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `分类存在多个启用字典项时返回400`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed-1", "床位费", "标准床位", "100"),
                feeItemRow("fee-bed-2", "床位费", "豪华床位", "200"),
            ),
        )
        val cause = causeOf(BillService(stub.pool).generate("enc-1", generateBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("multiple enabled fee items for category 床位费") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
    }

    // ——— 4. 手工加项 ———

    @Test
    fun `手工加项成功且可覆盖单价`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-other", "其他", "自费药", "50"),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "3100.00")),
            billItems = mutableListOf(
                billItemRow("item-bed", "bill-1", "自动", "fee-bed", "标准床位", "100", "31", "3100.00"),
            ),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/items", addBody())
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "手工加项必须 201")
                        val items = body.getJsonArray("items")
                        assertEquals(2, items.size())
                        val added = items.getJsonObject(1)
                        assertEquals("手工", added.getString("source"))
                        assertEquals("fee-other", added.getString("item_code"))
                        assertEquals("自费药", added.getString("item_name"))
                        assertEquals(0, BigDecimal("500").compareTo(amount(added.getValue("unit_price"))), "加项可覆盖字典单价 50 → 500")
                        assertEquals(0, BigDecimal("2").compareTo(amount(added.getValue("quantity"))))
                        assertEquals(0, BigDecimal("1000.00").compareTo(amount(added.getValue("amount"))))
                        assertEquals("自费药", added.getString("remark"))
                        assertEquals(0, BigDecimal("4100.00").compareTo(amount(body.getValue("total_amount"))), "合计重算 = 3100 + 1000")
                    }
                    // 缺省 unit_price 时取字典单价 50 × 1
                    httpRequest(
                        vertx, port, HttpMethod.POST,
                        "/healthcare/v1/bills/bill-1/items",
                        addBody(mapOf("unit_price" to null, "quantity" to 1)).also { it.remove("unit_price") },
                    ).map { (secondStatus, secondBody) ->
                        ctx.verify {
                            assertEquals(201, secondStatus)
                            val second = secondBody.getJsonArray("items").getJsonObject(2)
                            assertEquals(0, BigDecimal("50").compareTo(amount(second.getValue("unit_price"))), "缺省单价取字典单价")
                            assertEquals(0, BigDecimal("50.00").compareTo(amount(second.getValue("amount"))))
                            assertEquals(0, BigDecimal("4150.00").compareTo(amount(secondBody.getValue("total_amount"))))
                        }
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `停用字典项不可用于加项返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(feeItemRow("fee-other", "其他", "自费药", "50", status = "停用")),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "0.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/items", addBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "停用字典项加项必须 400")
                        assertTrue(body.getString("error")?.contains("disabled") == true, "got: ${body.getString("error")}")
                        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bill_items") })
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `加项时账单不存在404且非待缴费400`() {
        // 账单不存在
        val missing = DatabaseStub(encounters = rows(encounterRow()))
        val cause1 = causeOf(BillService(missing.pool).addItem("bill-missing", addBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause1)
        assertTrue(cause1.message?.contains("bill not found") == true, "got: ${cause1.message}")

        // 已结清账单不可加项
        val settled = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(feeItemRow("fee-other", "其他", "自费药", "50")),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "3100.00", status = "已结清")),
        )
        val cause2 = causeOf(BillService(settled.pool).addItem("bill-1", addBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("not 待缴费") == true, "got: ${cause2.message}")
        assertTrue(settled.tuples.none { it.first.contains("insert into healthcare.bill_items") })
    }

    @Test
    fun `加项字典项不存在返回404`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "0.00")),
        )
        val cause = causeOf(BillService(stub.pool).addItem("bill-1", addBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("fee item not found") == true, "got: ${cause.message}")
    }

    @Test
    fun `加项输入校验失败返回400且不触发SQL`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val service = BillService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.addItem("bill-1", body, "cashier-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(JsonObject(), "item_id is required")
        expectInvalid(addBody(mapOf("item_id" to "")), "item_id must not be blank")
        expectInvalid(addBody(mapOf("item_id" to "x".repeat(33))), "32")
        expectInvalid(addBody(mapOf("unit_price" to 0)), "positive")
        expectInvalid(addBody(mapOf("unit_price" to -1)), "positive")
        expectInvalid(addBody(mapOf("unit_price" to 1.999)), "at most 2 decimal places")
        expectInvalid(addBody(mapOf("quantity" to 0)), "positive")
        expectInvalid(addBody(mapOf("quantity" to "2")), "quantity must be a number")
        expectInvalid(addBody(mapOf("source" to "自动")), "unsupported bill item keys: source")
        expectInvalid(addBody(mapOf("amount" to 999)), "unsupported bill item keys: amount")
        expectInvalid(addBody(mapOf("remark" to "x".repeat(501))), "500")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
    }

    // ——— 5. 未认证 401 ———

    @Test
    fun `生成与加项未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fullMonthStub()
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", generateBody())
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status, "无认证 userId 生成账单必须 401")
                        assertNotNull(body.getString("error"))
                    }
                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/items", addBody())
                        .map { (itemStatus, itemBody) ->
                            ctx.verify {
                                assertEquals(401, itemStatus, "无认证 userId 手工加项必须 401")
                                assertNotNull(itemBody.getString("error"))
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 6. 查询：详情与列表 ———

    @Test
    fun `账单详情含明细且不存在404`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "4100.00")),
            billItems = mutableListOf(
                billItemRow("item-bed", "bill-1", "自动", "fee-bed", "标准床位", "100", "31", "3100.00"),
                billItemRow("item-extra", "bill-1", "手工", "fee-other", "自费药", "500", "2", "1000.00"),
            ),
        )
        val service = BillService(stub.pool)
        val bill = service.getBill("bill-1").toCompletionStage().toCompletableFuture().get()
        assertEquals("待缴费", bill.getString("status"))
        assertEquals("2026-08-01", bill.getString("period_start"))
        assertEquals("2026-08-31", bill.getString("period_end"))
        assertEquals(2, bill.getJsonArray("items").size())
        assertEquals("自动", bill.getJsonArray("items").getJsonObject(0).getString("source"))
        assertEquals("手工", bill.getJsonArray("items").getJsonObject(1).getString("source"))
        assertEquals(0, BigDecimal("4100.00").compareTo(amount(bill.getValue("total_amount"))))

        val missing = causeOf(service.getBill("bill-missing"))
        assertInstanceOf(HealthcareNotFoundException::class.java, missing)
        assertTrue(missing.message?.contains("bill not found") == true, "got: ${missing.message}")
    }

    @Test
    fun `账单列表分页返回records与meta`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            bills = mutableListOf(
                billRow("bill-2", "enc-1", "2026-07-01", "2026-07-31", "3000.00"),
                billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00"),
            ),
        )
        val list = BillService(stub.pool)
            .listBills("enc-1", limit = 10, offset = 0)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2, list.getJsonArray("records").size())
        assertEquals(2L, list.getJsonObject("meta").getLong("total"))
        val records = list.getJsonArray("records")
        assertEquals("bill-1", records.getJsonObject(0).getString("id"))
        assertEquals("bill-2", records.getJsonObject(1).getString("id"))

        val dataSql = stub.queries.first { it.contains("fetch next") }
        assertTrue(dataSql.contains("order by"), "列表必须排序: $dataSql")
        assertTrue(dataSql.contains("offset $"), "列表必须分页 offset: $dataSql")
    }

    @Test
    fun `空账单列表返回空records与total0`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val list = BillService(stub.pool)
            .listBills("enc-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(0, list.getJsonArray("records").size())
        assertEquals(0L, list.getJsonObject("meta").getLong("total"))
    }

    // ——— 嵌入式 HTTP 基础设施 ———

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body.encode())
                else req.send()
            }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun <T> withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        userId: String? = null,
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose {
                server.close().map { Unit }
            }
        }
    }
}

// ——— mock 基础设施（顶层函数） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun rows(vararg values: Map<String, Any?>): RowSet<Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}
