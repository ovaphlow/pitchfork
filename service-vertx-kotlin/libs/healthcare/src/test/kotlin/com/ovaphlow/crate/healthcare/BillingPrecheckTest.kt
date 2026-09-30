package com.ovaphlow.crate.healthcare

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
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.function.Function as JavaFunction

/**
 * 生成账单前置校验（BillService.precheckBillGeneration + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 *
 * 覆盖：
 *   - 槽位推导（唯一实现）：床位费 + 本账期生效等级逐个护理费 + 有餐次时的伙食费；
 *     同日多份评估取 created_at 最新、被取代的历史等级不产生槽位
 *   - 字典齐全 → can_generate = true / blocked_by = null / 每个 required 槽位 enabled_count = 1
 *   - 缺字典（0 条）与多条启用（2 条）→ 200 + blocked_by = missing_fee_items + satisfied = false
 *   - 账期内无就餐 → 伙食费 required = false，无启用伙食费项时仍可生成
 *   - 各阻断分支：settled / not_overlapping / no_admit_date / not_elderly_admission / already_exists
 *   - month 缺失或非法 400、encounter 不存在 404、未认证 401（均不触发 SQL）
 *   - 纯读无副作用（反复调用无任何 insert/update）
 *   - precheck 与真实生成路径槽位同源（同一份推导：取项一一对应、输入查询参数一致）
 *   - precheck 与真实生成的账期裁剪一致（跨月 / 首月 / 尾月）
 */
@ExtendWith(VertxExtension::class)
class BillingPrecheckTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * 写入（insert/update）落到 bills/billItems 状态并可从 [writeStatements] 观察，
     * 使「precheck 只读」与「precheck → 生成」链路可断言。
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
                        val total = scoped.fold(BigDecimal.ZERO) { acc, item -> acc.add(item["amount"] as BigDecimal) }
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

        /** 全部写入语句（precheck 必须为空）：insert / update。 */
        fun writeStatements(): List<Pair<String, List<Any?>>> =
            tuples.filter { it.first.startsWith("insert into") || it.first.startsWith("update ") }

        /** 指定 SQL 特征命中的查询参数序列（用于断言 precheck 与生成同源输入）。 */
        fun queryParams(fragment: String): List<List<Any?>> =
            tuples.filter { it.first.contains(fragment) }.map { it.second }
    }

    // ——— fixture 构造 ———

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "encounter_type" to "ELDERLY_CARE",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "settled_at" to null,
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
        nursingLevel: String? = null,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "category" to category,
            "name" to name,
            "unit_price" to BigDecimal(price),
            "status" to status,
            // 030 W1：护理费按 metadata.nursing_level 匹配（名称只作描述文本）
            "nursing_level" to nursingLevel,
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
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to id,
            "encounter_id" to encounterId,
            "period_start" to LocalDate.parse(periodStart),
            "period_end" to LocalDate.parse(periodEnd),
            "status" to "待缴费",
            "total_amount" to BigDecimal("0.00"),
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    /**
     * 标准满月 fixture：床位 100 / 护理 高风险 & 中风险 / 伙食 30（护理字典名称与等级不同名，
     * 用于锁定「按 metadata.nursing_level 匹配、名称不参与」）；
     * 08-01 同日两份评估（09:00 低风险被 10:00 高风险取代）+ 09-10 中风险；
     * 账期内 2.5 折合餐次。
     */
    private fun fullStub(): DatabaseStub = DatabaseStub(
        encounters = rows(encounterRow()),
        feeItems = mutableListOf(
            feeItemRow("fee-bed", "床位费", "标准床位", "100"),
            feeItemRow("fee-nurse-top", "护理费", "护理费·高风险", "200", nursingLevel = "高风险"),
            feeItemRow("fee-nurse-mid", "护理费", "护理费·中风险", "80", nursingLevel = "中风险"),
            feeItemRow("fee-nurse-low", "护理费", "护理费·低风险", "50", nursingLevel = "低风险"),
            feeItemRow("fee-meal", "伙食费", "三餐", "30"),
        ),
        assessments = mutableListOf(
            assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "低风险"),
            assessmentRow("enc-1", "2026-08-01", "2026-08-01T10:00:00+08:00", "高风险"),
            assessmentRow("enc-1", "2026-09-10", "2026-09-10T08:00:00+08:00", "中风险"),
        ),
        mealsByEncounter = mutableMapOf("enc-1" to listOf("正常", "正常", "部分")),
    )

    private fun generateBody(month: String = "2026-09"): JsonObject = JsonObject().put("month", month)

    private fun requirementsOf(body: JsonObject): List<JsonObject> =
        body.getJsonArray("requirements").map { it as JsonObject }

    private fun requirementOf(body: JsonObject, category: String, level: String? = null): JsonObject =
        requirementsOf(body).first {
            it.getString("category") == category && it.getString("level") == level
        }

    private fun noticesOf(body: JsonObject): List<JsonObject> =
        body.getJsonArray("notices").map { it as JsonObject }

    private fun noticeOf(body: JsonObject, code: String): JsonObject =
        noticesOf(body).first { it.getString("code") == code }

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

    private fun completed(future: Future<JsonObject>): JsonObject =
        future.toCompletionStage().toCompletableFuture().get()

    /** 机构时区当前月偏移（用于未来/历史账期测试，避免依赖真实当前日期漂移）。 */
    private fun businessMonth(offsetMonths: Long): String {
        val base = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusMonths(offsetMonths)
        return "%04d-%02d".format(base.year, base.monthValue)
    }

    // ——— W4 未来账期（保守缺省） ———

    @Test
    fun `precheck未来账期返回future_month且不触发SQL`() {
        val stub = fullStub()
        val futureMonth = businessMonth(1)
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", futureMonth))

        assertFalse(body.getBoolean("can_generate"))
        assertEquals("future_month", body.getString("blocked_by"))
        assertNull(body.getString("period_start"), "未来账期早退不计算区间")
        assertNull(body.getString("period_end"))
        assertEquals(0, requirementsOf(body).size, "未来账期早退不推算槽位")
        assertEquals(0, noticesOf(body).size, "未来账期早退 notices 为空数组")
        assertTrue(stub.writeStatements().isEmpty())
        assertTrue(stub.queries.isEmpty(), "未来账期被拒不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls, "未来账期被拒不得开启事务")
    }

    @Test
    fun `precheck当前月与历史月仍可生成`() {
        val stub = fullStub()
        val current = completed(BillService(stub.pool).precheckBillGeneration("enc-1", businessMonth(0)))
        assertTrue(current.getBoolean("can_generate"), "当前月必须可生成")
        assertNull(current.getString("blocked_by"))

        val previous = completed(BillService(stub.pool).precheckBillGeneration("enc-1", businessMonth(-1)))
        assertTrue(previous.getBoolean("can_generate"), "历史月必须可生成")
        assertNull(previous.getString("blocked_by"))
    }

    // ——— 1. 槽位推导：字典齐全 + 账期合法 ———

    @Test
    fun `precheck字典齐全账期合法可生成且每个槽位恰好一条启用`() {
        val stub = fullStub()
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))

        assertEquals("2026-09", body.getString("month"))
        assertEquals("2026-09-01", body.getString("period_start"), "账期起 = 月首（入住日更早）")
        assertEquals("2026-09-30", body.getString("period_end"), "无离院日时账期止 = 月末")
        assertTrue(body.getBoolean("can_generate"), "字典齐全且账期合法必须可生成")
        assertTrue(body.containsKey("blocked_by"), "blocked_by 字段必须存在（可生成时为 null）")
        assertNull(body.getString("blocked_by"), "可生成时 blocked_by 必须为 null")

        // 槽位顺序：床位 → 账期内生效等级逐个护理 → 伙食
        val categories = requirementsOf(body).map {
            it.getString("category") to it.getString("level")
        }
        assertEquals(
            listOf(
                "床位费" to null,
                "护理费" to "高风险",
                "护理费" to "中风险",
                "伙食费" to null,
            ),
            categories,
            "槽位必须为 床位 + 本账期每个生效等级护理 + 伙食",
        )

        for (category in listOf("床位费", "护理费", "伙食费")) {
            val slots = requirementsOf(body).filter { it.getString("category") == category }
            assertTrue(slots.isNotEmpty(), "$category 必须有槽位")
            for (slot in slots) {
                assertTrue(slot.getBoolean("required"), "账期内生效的 $category 槽位必须 required")
                assertEquals(1, slot.getInteger("enabled_count"), "槽位 $slot 必须恰好一条启用字典项")
                assertTrue(slot.getBoolean("satisfied"), "enabled_count = 1 必须 satisfied")
            }
        }

        // 同日多份取 created_at 最新：09:00 的低风险被 10:00 的高风险取代，不产生槽位
        assertTrue(
            requirementsOf(body).none { it.getString("level") == "低风险" },
            "被同日后一份评估取代的历史等级不得产生槽位",
        )

        // notices：本账期护理有评估、伙食有餐次 → 口径提示必须为空数组（键仍必须存在）
        assertTrue(body.containsKey("notices"), "notices 字段必须始终存在")
        assertEquals(emptyList<String>(), noticesOf(body).map { it.getString("code") })

        assertTrue(
            stub.writeStatements().isEmpty(),
            "precheck 不得产生任何写入: ${stub.writeStatements()}",
        )
    }

    // ——— 1b. 口径提示 notices（030 W2 契约冻结 D8） ———

    @Test
    fun `precheck无护理评估时给出未计费notice且不产生护理槽位`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-meal", "伙食费", "三餐", "30"),
            ),
            mealsByEncounter = mutableMapOf("enc-1" to listOf("正常")),
        )
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))

        assertTrue(body.getBoolean("can_generate"), "无护理评估不阻断生成（D3：不计费但必须显式告知）")
        assertNull(body.getString("blocked_by"))
        assertEquals(
            listOf("床位费" to null, "伙食费" to null),
            requirementsOf(body).map { it.getString("category") to it.getString("level") },
            "无评估时根本不产生护理费槽位（护理费静默消失必须由 notice 显式表达）",
        )
        assertEquals(
            listOf("nursing_fee_not_billed_no_assessment"),
            noticesOf(body).map { it.getString("code") },
        )
        val notice = noticeOf(body, "nursing_fee_not_billed_no_assessment")
        assertEquals("护理费", notice.getString("category"))
        assertTrue(notice.containsKey("level"), "notice 必须含 level 键（显式 null）")
        assertNull(notice.getString("level"))
        assertTrue(stub.writeStatements().isEmpty())
    }

    @Test
    fun `precheck无评估且无就餐记录时两条notice同时给出`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(feeItemRow("fee-bed", "床位费", "标准床位", "100")),
        )
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))

        assertTrue(body.getBoolean("can_generate"), "护理/伙食本账期不计费，缺字典也不阻断")
        assertEquals(
            listOf("床位费" to null, "伙食费" to null),
            requirementsOf(body).map { it.getString("category") to it.getString("level") },
            "无餐次时伙食费槽位仍存在但 required = false（不构成缺项）",
        )
        assertFalse(requirementOf(body, "伙食费").getBoolean("required"))
        assertEquals(
            listOf("nursing_fee_not_billed_no_assessment", "meal_fee_not_billed_no_dining_record"),
            noticesOf(body).map { it.getString("code") },
            "护理费提示在前、伙食费提示在后",
        )
        assertEquals("伙食费", noticeOf(body, "meal_fee_not_billed_no_dining_record").getString("category"))
        assertTrue(
            noticesOf(body).all { it.containsKey("code") && it.containsKey("category") && it.containsKey("level") },
            "notice 元素形状必须为 {code, category, level}: ${noticesOf(body)}",
        )
    }

    // ——— 2. 缺字典 / 多条启用 → 200 + missing_fee_items（含路由未被泛型路由吞掉） ———

    @Test
    fun `precheck缺床位费返回200且blocked为missing_fee_items`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-nurse-mid", "护理费", "护理费·中风险", "80", nursingLevel = "中风险"),
                feeItemRow("fee-meal", "伙食费", "三餐", "30"),
            ),
            assessments = mutableListOf(
                assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "中风险"),
            ),
            mealsByEncounter = mutableMapOf("enc-1" to listOf("正常")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.GET,
                "/healthcare/v1/encounters/enc-1/bills/precheck?month=2026-09",
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "缺字典是被查询的业务状态，必须 200 而非 4xx")
                    assertFalse(body.getBoolean("can_generate"))
                    assertEquals("missing_fee_items", body.getString("blocked_by"))
                    assertNotNull(body.getJsonArray("requirements"), "必须返回 requirements 数据")
                    val bed = requirementOf(body, "床位费")
                    assertTrue(bed.getBoolean("required"), "床位费槽位在本账期必需")
                    assertEquals(0, bed.getInteger("enabled_count"))
                    assertFalse(bed.getBoolean("satisfied"), "enabled_count = 0 必须不满足")
                    assertNull(bed.getString("level"), "非护理槽位 level 必须为 null")
                    assertNotNull(body.getJsonArray("notices"), "notices 字段必须始终存在（可为空数组）")
                    assertEquals(0, noticesOf(body).size, "有评估 + 有餐次 → 无口径提示")
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `precheck存在两条启用床位费时槽位计数为2且不满足`() {
        val stub = fullStub()
        stub.feeItems.add(feeItemRow("fee-bed-2", "床位费", "豪华床位", "200"))
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))

        // 服务端只返回数据：HTTP 200 由路由用例覆盖（缺字典不是请求错误）
        assertFalse(body.getBoolean("can_generate"))
        assertEquals("missing_fee_items", body.getString("blocked_by"))
        val bed = requirementOf(body, "床位费")
        assertEquals(2, bed.getInteger("enabled_count"), "两条启用必须计数为 2")
        assertFalse(bed.getBoolean("satisfied"), "多条启用同样不满足（后端只给数据）")
        // 其余槽位仍为满足状态，错误语义完全由该槽位承担
        assertTrue(requirementOf(body, "护理费", "高风险").getBoolean("satisfied"))
        assertTrue(requirementOf(body, "伙食费").getBoolean("satisfied"))
    }

    // ——— 3. 账期内无就餐：伙食费非必需 ———

    @Test
    fun `precheck账期内无就餐记录时伙食费非必需`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-nurse-mid", "护理费", "护理费·中风险", "80", nursingLevel = "中风险"),
            ),
            assessments = mutableListOf(
                assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "中风险"),
            ),
        )
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))

        assertTrue(body.getBoolean("can_generate"), "无餐次时缺启用伙食费项也必须可生成")
        assertNull(body.getString("blocked_by"))
        val meal = requirementOf(body, "伙食费")
        assertFalse(meal.getBoolean("required"), "账期内无就餐记录时伙食费 required = false")
        assertEquals(0, meal.getInteger("enabled_count"), "无启用伙食费项时计数为 0")
        assertFalse(meal.getBoolean("satisfied"), "satisfied 仍由 enabled_count == 1 判定")
        assertTrue(requirementOf(body, "床位费").getBoolean("satisfied"))
        assertTrue(requirementOf(body, "护理费", "中风险").getBoolean("satisfied"))

        // 有评估（无护理提示）+ 无就餐记录 → 只给伙食费未计费提示
        assertEquals(
            listOf("meal_fee_not_billed_no_dining_record"),
            noticesOf(body).map { it.getString("code") },
            "无就餐登记必须显式告知伙食费不计（槽位 required = false 不足以让页面说明口径）",
        )
        val notice = noticeOf(body, "meal_fee_not_billed_no_dining_record")
        assertEquals("伙食费", notice.getString("category"))
        assertNull(notice.getString("level"))
    }

    // ——— 4. 各 blocked_by 分支（顺序与真实生成校验一致） ———

    @Test
    fun `precheck已收束入住返回settled且先于缺入住日期`() {
        val settled = fullStub()
        settled.encounters = rows(
            encounterRow(mapOf("settled_at" to OffsetDateTime.parse("2026-10-01T09:00:00+08:00"))),
        )
        val body = completed(BillService(settled.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertFalse(body.getBoolean("can_generate"))
        assertEquals("settled", body.getString("blocked_by"))
        assertEquals(0, requirementsOf(body).size, "早退阻断不推算槽位")
        assertEquals(0, noticesOf(body).size, "早退阻断分支 notices 为空数组（键必须存在）")
        assertTrue(settled.writeStatements().isEmpty())

        // 同时缺 admit_date 时仍报 settled（与生成路径的 409 先于 400 一致）
        val settledNoAdmit = fullStub()
        settledNoAdmit.encounters = rows(
            encounterRow(
                mapOf(
                    "admit_date" to null,
                    "settled_at" to OffsetDateTime.parse("2026-10-01T09:00:00+08:00"),
                ),
            ),
        )
        val second = completed(BillService(settledNoAdmit.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertEquals("settled", second.getString("blocked_by"), "已收束必须先于缺入住日期（生成 409 → 400）")
    }

    @Test
    fun `precheck账期与在院区间无重合返回not_overlapping`() {
        // 账期前已离院
        val dischargedBefore = fullStub()
        dischargedBefore.encounters = rows(
            encounterRow(mapOf("discharge_date" to OffsetDateTime.parse("2026-08-31T10:00:00+08:00"))),
        )
        val first = completed(BillService(dischargedBefore.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertEquals("not_overlapping", first.getString("blocked_by"))
        assertFalse(first.getBoolean("can_generate"))
        assertNull(first.getString("period_start"), "无重合时账期取不到 → null")
        assertNull(first.getString("period_end"))

        // 入住日期晚于账期
        val admittedAfter = fullStub()
        admittedAfter.encounters = rows(
            encounterRow(mapOf("admit_date" to OffsetDateTime.parse("2026-10-05T00:00:00+08:00"))),
        )
        val second = completed(BillService(admittedAfter.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertEquals("not_overlapping", second.getString("blocked_by"))
    }

    @Test
    fun `precheck缺入住日期返回no_admit_date`() {
        val stub = fullStub()
        stub.encounters = rows(encounterRow(mapOf("admit_date" to null)))
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertFalse(body.getBoolean("can_generate"))
        assertEquals("no_admit_date", body.getString("blocked_by"))
        assertNull(body.getString("period_start"))
        assertNull(body.getString("period_end"))
    }

    @Test
    fun `precheck非养老入住返回not_elderly_admission`() {
        val stub = fullStub()
        stub.encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT")))
        val body = completed(BillService(stub.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertFalse(body.getBoolean("can_generate"))
        assertEquals("not_elderly_admission", body.getString("blocked_by"))
    }

    @Test
    fun `precheck该账期账单已存在返回already_exists且优先于缺字典`() {
        // 字典齐全但该账期已有账单
        val existing = fullStub()
        existing.bills.add(billRow("bill-1", "enc-1", "2026-09-01", "2026-09-30"))
        val body = completed(BillService(existing.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertFalse(body.getBoolean("can_generate"))
        assertEquals("already_exists", body.getString("blocked_by"), "与生成路径的 409 同源（exactBillExists）")
        assertEquals("2026-09-01", body.getString("period_start"))
        assertEquals("2026-09-30", body.getString("period_end"))

        // 同时缺床位费字典：already_exists 必须优先（与生成的重复 409 先于缺字典 400 一致）
        val missingDict = DatabaseStub(
            encounters = rows(encounterRow()),
            feeItems = mutableListOf(feeItemRow("fee-meal", "伙食费", "三餐", "30")),
            mealsByEncounter = mutableMapOf("enc-1" to listOf("正常")),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-09-01", "2026-09-30")),
        )
        val second = completed(BillService(missingDict.pool).precheckBillGeneration("enc-1", "2026-09"))
        assertEquals("already_exists", second.getString("blocked_by"), "已存在账单必须优先于缺字典")
    }

    // ——— 5. 请求级拒绝：400 / 404 / 401 ———

    @Test
    fun `precheck月缺失或非法返回400且不触发SQL`() {
        val stub = fullStub()
        val service = BillService(stub.pool)

        for (month in listOf(null, "", "2026-13", "2026-8", "abc", "2026-09-01")) {
            val cause = causeOf(service.precheckBillGeneration("enc-1", month))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(
                cause.message?.contains("month must be in YYYY-MM format") == true,
                "month = $month got: ${cause.message}",
            )
        }

        assertTrue(stub.queries.isEmpty(), "月份校验必须先于任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls, "月份校验失败不得开启事务")
    }

    @Test
    fun `precheck encounter不存在返回404`() {
        val stub = DatabaseStub(encounters = rowSet())
        val cause = causeOf(BillService(stub.pool).precheckBillGeneration("missing", "2026-09"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("encounter not found") == true, "got: ${cause.message}")
        assertTrue(stub.writeStatements().isEmpty())
    }

    @Test
    fun `precheck未认证返回401且不触发SQL`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fullStub()
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.GET,
                "/healthcare/v1/encounters/enc-1/bills/precheck?month=2026-09",
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "无认证 userId 的 precheck 必须 401")
                    assertNotNull(body.getString("error"))
                    assertTrue(stub.queries.isEmpty(), "未认证不得触发任何 SQL: ${stub.queries}")
                    assertEquals(0, stub.transactionCalls)
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `precheck月缺失路由返回400且错误为error对象`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fullStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/bills/precheck")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "缺 month 查询参数必须 400")
                        assertTrue(
                            body.getString("error")?.contains("month must be in YYYY-MM format") == true,
                            "got: ${body.getString("error")}",
                        )
                        assertTrue(stub.queries.isEmpty())
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 6. 路由未被泛型 encounter 路由吞掉 ———

    @Test
    fun `precheck路由未被泛型encounter路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fullStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.GET,
                "/healthcare/v1/encounters/enc-1/bills/precheck?month=2026-09",
            ).compose { (precheckStatus, precheckBody) ->
                ctx.verify {
                    assertEquals(200, precheckStatus)
                    assertTrue(precheckBody.containsKey("can_generate"), "必须命中 precheck 处理器而非泛型 encounter 读")
                    assertNotNull(precheckBody.getJsonArray("requirements"))
                    assertFalse(
                        precheckBody.containsKey("encounter_type"),
                        "命中泛型 encounter 读路由会返回 encounter 形状（含 encounter_type）",
                    )
                }
                // 对照：泛型 encounter 读路由返回 encounter 形状（无 can_generate）
                httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1")
                    .map { (encounterStatus, encounterBody) ->
                        ctx.verify {
                            assertEquals(200, encounterStatus)
                            assertTrue(encounterBody.containsKey("encounter_type"), "泛型路由返回 encounter 形状")
                            assertFalse(encounterBody.containsKey("can_generate"), "泛型路由不返回 precheck 形状")
                        }
                    }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 7. 纯读：反复调用无任何写入 ———

    @Test
    fun `precheck为纯读反复调用不产生任何写入`() {
        val stub = fullStub()
        val service = BillService(stub.pool)

        val first = completed(service.precheckBillGeneration("enc-1", "2026-09"))
        val second = completed(service.precheckBillGeneration("enc-1", "2026-09"))
        val third = completed(service.precheckBillGeneration("enc-1", "2026-09"))

        assertEquals(first.encode(), second.encode(), "反复调用必须返回同一结果")
        assertEquals(first.encode(), third.encode())
        assertTrue(stub.writeStatements().isEmpty(), "precheck 不得产生 insert/update: ${stub.writeStatements()}")
        assertTrue(stub.bills.isEmpty(), "precheck 不得新建账单")
        assertTrue(stub.billItems.isEmpty(), "precheck 不得新建明细")
    }

    // ——— 8. 一致性：precheck 与真实生成同源 ———

    @Test
    fun `precheck与真实生成路径槽位同源且可生成必然生成成功`() {
        val stub = fullStub()
        val service = BillService(stub.pool)

        val precheck = completed(service.precheckBillGeneration("enc-1", "2026-09"))
        val afterPrecheck = stub.tuples.toList()

        assertTrue(precheck.getBoolean("can_generate"))
        // precheck.can_generate == true → 同一 (encounter, month) 的真实生成必须成功（不因缺字典 400）
        val bill = completed(service.generate("enc-1", generateBody("2026-09"), "cashier-1"))

        // 1) 显现输入同源：护理评估与就餐查询的参数逐字一致（同一裁剪区间、同一 encounter）
        val precheckPhase = afterPrecheck
        val generatePhase = stub.tuples.drop(afterPrecheck.size)
        for (fragment in listOf("from nursing.nursing_assessments", "from dining.dining_meal_executions")) {
            val precheckParams = precheckPhase.filter { it.first.contains(fragment) }.map { it.second }
            val generateParams = generatePhase.filter { it.first.contains(fragment) }.map { it.second }
            assertEquals(precheckParams, generateParams, "$fragment 的取项输入必须与生成路径一致")
        }

        // 2) 槽位集合同源：每个自动明细恰好对应 precheck 的一个 required 槽位，数量一致
        val requirements = requirementsOf(precheck)
        val itemFixtures = stub.feeItems.associateBy { it["id"] }
        val billItems = bill.getJsonArray("items").map { it as JsonObject }
        val matches = billItems.map { item ->
            val fixture = itemFixtures.getValue(item.getString("item_code"))
            // 030 W1：护理槽位按字典 metadata.nursing_level 匹配（名称不参与）
            requirements.count { slot ->
                slot.getString("category") == fixture["category"] &&
                    (slot.getString("level") == null || slot.getString("level") == fixture["nursing_level"])
            }
        }
        assertTrue(matches.all { it == 1 }, "每个自动明细必须恰好命中一个 precheck 槽位: $matches")
        assertEquals(
            requirements.count { it.getBoolean("required") },
            billItems.size,
            "precheck 的 required 槽位数必须等于生成明细数（同一份推导）",
        )
    }

    // ——— 9. 账期裁剪一致（跨月 / 首月 / 尾月） ———

    @Test
    fun `precheck与生成账单的账期裁剪一致`() {
        data class Case(
            val name: String,
            val admitDate: String,
            val dischargeDate: String?,
            val month: String,
            val expectedStart: String,
            val expectedEnd: String,
        )
        val cases = listOf(
            Case("跨月两端裁剪", "2026-08-10", "2026-09-20", "2026-09", "2026-09-01", "2026-09-20"),
            Case("首月裁剪到入住日", "2026-09-05", null, "2026-09", "2026-09-05", "2026-09-30"),
            Case("尾月裁剪到离院日", "2026-08-01", "2026-09-10", "2026-09", "2026-09-01", "2026-09-10"),
        )

        for (case in cases) {
            val stub = fullStub()
            stub.encounters = rows(
                encounterRow(
                    mapOf(
                        "admit_date" to OffsetDateTime.parse("${case.admitDate}T00:00:00+08:00"),
                        "discharge_date" to case.dischargeDate?.let {
                            OffsetDateTime.parse("${it}T10:00:00+08:00")
                        },
                    ),
                ),
            )
            val service = BillService(stub.pool)
            val precheck = completed(service.precheckBillGeneration("enc-1", case.month))
            assertEquals(case.expectedStart, precheck.getString("period_start"), "${case.name}: precheck 账期起")
            assertEquals(case.expectedEnd, precheck.getString("period_end"), "${case.name}: precheck 账期止")
            assertTrue(precheck.getBoolean("can_generate"), "${case.name}: 字典齐全必须可生成")

            val bill = completed(service.generate("enc-1", generateBody(case.month), "cashier-1"))
            assertEquals(
                bill.getString("period_start"),
                precheck.getString("period_start"),
                "${case.name}: 生成账单账期起必须与 precheck 一致",
            )
            assertEquals(
                bill.getString("period_end"),
                precheck.getString("period_end"),
                "${case.name}: 生成账单账期止必须与 precheck 一致",
            )
        }
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
