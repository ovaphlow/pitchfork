package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
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
import java.util.function.Function as JavaFunction

/**
 * 账单红冲（034 A2 反向单）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径（034 §2/§4/§6）：
 *   - 红字单构造：total_amount = 原单取反、账期复制、status = 待缴费、reversal_of = 原单 id；
 *     原单 total_amount/status 不变，只写 reversal_reason/reversed_by/reversed_at 留痕
 *   - reason 三类非法输入（非字符串 / trim 后空 / 超 500）+ 缺 reason + 非白名单键 → 400 且不触发 SQL
 *   - 红字单不可再红冲 400；已红冲 409；非待缴费 409；已关账 409；有缴费记录 409；非正金额 400；不存在 404；未认证 401
 *   - addItem 守卫：红字单 / 已红冲原单 → 400
 *   - 红冲后同账期可重新生成（generate + precheck 同口径）；收束规划仍按「任何账单」判定，不重出最终账单
 *   - outstandingBills 排除已红冲原单（不把已冲销金额算成未结）
 *   - 列表与详情返回 reversal_of/reversal_reason/reversed_by/reversed_at 与派生的 reversal_bill_id
 */
@ExtendWith(VertxExtension::class)
class BillReversalServiceTest {

    /**
     * 全库 mock 桩：按 normalized SQL 特征分发。`bills` 台账带 V523 红冲列，
     * LEFT JOIN 派生的 `reversal_bill_id` 与「已红冲原单是否计入」都由 stub 按
     * 捕获到的 SQL 谓词模拟（谓词被删掉时对应断言会失败）。
     */
    private class DatabaseStub(
        var encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(encounterRow()),
        var bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var billItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var payments: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var deposits: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var feeItems: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        /** 事务连接：单元测试直接调用 outstandingBills/previewSettlement 时传入。 */
        val connection: SqlConnection get() = conn

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
                    sql.contains("insert into healthcare.payments") -> {
                        payments.add(
                            mutableMapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "amount" to values[2],
                                "method" to values[3],
                                "operator" to values[4],
                                "created_at" to values[5],
                                "updated_at" to values[6],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
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
                                "reversal_of" to values.getOrNull(8),
                                "reversal_reason" to null,
                                "reversed_by" to null,
                                "reversed_at" to null,
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
                    sql.contains("update healthcare.bills") && sql.contains("reversal_reason") -> {
                        // 红冲留痕：只写红冲三列 + updated_at（不碰 total_amount/status）
                        bills.firstOrNull { it["id"] == values.last() }?.let { target ->
                            target["reversal_reason"] = values[0]
                            target["reversed_by"] = values[1]
                            target["reversed_at"] = values[2]
                            target["updated_at"] = values[3]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") && sql.contains("total_amount") -> {
                        // 加项后重算合计
                        bills.firstOrNull { it["id"] == values.last() }?.let { target ->
                            target["total_amount"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") -> {
                        bills.firstOrNull { it["id"] == values.last() }?.let { target ->
                            target["status"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.payments") -> {
                        val count = payments.count { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    sql.contains("max(") && sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (it["status"] == values.getOrNull(1) || it["status"] == values.getOrNull(2))
                        }
                        val maxEnd = scoped.mapNotNull { it["period_end"] as? LocalDate }.maxOrNull()
                        Future.succeededFuture(rowSet(mockRow(mapOf("max_end" to maxEnd))))
                    }
                    // 同账期存在性：带「有效原单」谓词的走 effective（红字单与已红冲原单不算），
                    // 不带谓词的走 exact（任何账单都算已存在）
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") && sql.contains("period_start") -> {
                        val effectiveOnly = sql.contains("reversal_of is null")
                        val count = bills.count {
                            it["encounter_id"] == values.getOrNull(0) &&
                                it["period_start"] == values.getOrNull(1) &&
                                it["period_end"] == values.getOrNull(2) &&
                                (!effectiveOnly || (it["reversal_of"] == null && it["reversed_at"] == null))
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") -> {
                        val count = bills.count { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    sql.contains("from healthcare.fee_items") && sql.contains("status = $") ->
                        Future.succeededFuture(
                            rowSet(*feeItems.filter { it["status"] == values.getOrNull(0) }.map { mockRow(it) }.toTypedArray()),
                        )
                    sql.contains("from healthcare.fee_items") -> {
                        val item = feeItems.firstOrNull { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(item?.let { rowSet(mockRow(it)) } ?: rowSet())
                    }
                    sql.contains("from nursing.nursing_assessments") -> Future.succeededFuture(rowSet())
                    sql.contains("from dining.dining_meal_executions") -> Future.succeededFuture(rowSet())
                    // 未结/欠费聚合（LEFT JOIN payments + group by）：谓词带 reversed_at is null 时排除已红冲原单
                    sql.contains("left outer join") && sql.contains("group by") -> {
                        val excludesReversed = sql.contains("reversed_at is null")
                        val encounterId = encounterIdParam(sql, values)
                        val scoped = bills
                            .filter { it["encounter_id"] == encounterId && it["status"] == BillingEngine.STATUS_PENDING }
                            .filter { !excludesReversed || it["reversed_at"] == null }
                            .mapNotNull { bill ->
                                val paid = payments
                                    .filter { it["bill_id"] == bill["id"] }
                                    .fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment["amount"] as BigDecimal) }
                                val total = bill["total_amount"] as BigDecimal
                                val balance = total.subtract(paid)
                                if (balance.signum() <= 0) {
                                    null
                                } else {
                                    mapOf(
                                        "id" to bill["id"],
                                        "period_start" to bill["period_start"],
                                        "period_end" to bill["period_end"],
                                        "total_amount" to total,
                                        "paid_amount" to paid,
                                        "balance" to balance,
                                    )
                                }
                            }
                            .sortedWith(
                                compareBy<Map<String, Any?>> { it["period_start"] as LocalDate }
                                    .thenBy { it["id"] as String },
                            )
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("sum(") && sql.contains("from healthcare.bill_items") -> {
                        val total = billItems
                            .filter { it["bill_id"] == values.getOrNull(0) }
                            .fold(BigDecimal.ZERO) { acc, item -> acc.add(item["amount"] as BigDecimal) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to total))))
                    }
                    sql.contains("from healthcare.bill_items") -> {
                        val scoped = billItems.filter { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from healthcare.deposit_records") -> {
                        val scoped = deposits.filter { it["encounter_id"] == encounterIdParam(sql, values) }
                        Future.succeededFuture(
                            rowSet(*scoped.map { mockRow(mapOf("type" to it["type"], "amount" to it["amount"])) }.toTypedArray()),
                        )
                    }
                    // 账单列表（LEFT JOIN 红字单 + 分页）：按 encounter 过滤，账期倒序
                    sql.contains("from healthcare.bills") && sql.contains("order by") -> {
                        val scoped = bills
                            .filter { it["encounter_id"] == values.getOrNull(0) }
                            .sortedWith(
                                compareByDescending<MutableMap<String, Any?>> { it["period_start"] as LocalDate }
                                    .thenByDescending { it["id"] as String },
                            )
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(billRowWithDerived(it)) }.toTypedArray()))
                    }
                    // 账单详情（LEFT JOIN 红字单）：按 id 取单
                    sql.contains("from healthcare.bills") -> {
                        val row = bills.firstOrNull { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(row?.let { rowSet(mockRow(billRowWithDerived(it))) } ?: rowSet())
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

        /** LEFT JOIN 派生列：本单是已红冲原单时指向冲销它的红字单。 */
        private fun billRowWithDerived(bill: MutableMap<String, Any?>): Map<String, Any?> =
            bill + ("reversal_bill_id" to bills.firstOrNull { it["reversal_of"] == bill["id"] }?.get("id"))

        /** 取 SQL 里 `encounter_id = $N` 的绑定值（聚合查询的绑定顺序随列数变化）。 */
        private fun encounterIdParam(sql: String, values: List<Any?>): String? {
            val match = Regex("""encounter_id = \$(\d+)""").find(sql) ?: return null
            val position = match.groupValues[1].toIntOrNull() ?: return null
            return values.getOrNull(position - 1) as? String
        }
    }

    // ——— fixture 构造（顶层私有函数，见文件末尾） ———

    private fun reversalBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject().put("reason", "重复计费，按实际在院区间重算")
        overrides.forEach { (key, value) -> if (value == null) body.remove(key) else body.put(key, value) }
        return body
    }

    private fun addBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject().put("item_id", "fee-other").put("unit_price", 500).put("quantity", 1)
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

    private fun amount(value: Any?): BigDecimal = BigDecimal(value.toString())

    /** 单张待缴费原单 + 可计费字典（床位费 100/天 = 31 天 3100.00）。 */
    private fun reversalStub(): DatabaseStub = DatabaseStub(
        bills = mutableListOf(billRow()),
        feeItems = mutableListOf(feeItemRow("fee-bed", "床位费", "标准床位", "100")),
    )

    // ========================================================================
    //  1. 红冲构造：红字单 + 原单留痕
    // ========================================================================

    @Test
    fun `红冲成功生成红字单并原单留痕`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = reversalStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/reversal", reversalBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "红冲成功必须 201 并直接返回红字单")
                        // 红字单：金额取反、账期复制、状态待缴费、reversal_of 指向原单
                        assertEquals(0, BigDecimal("-3100.00").compareTo(amount(body.getValue("total_amount"))), "红字单金额必须为原单取反")
                        assertEquals("2026-08-01", body.getString("period_start"), "账期起复制原单")
                        assertEquals("2026-08-31", body.getString("period_end"), "账期止复制原单")
                        assertEquals(BillingEngine.STATUS_PENDING, body.getString("status"), "红字单状态必须是 待缴费")
                        assertEquals("bill-1", body.getString("reversal_of"), "红字单必须指向原单")
                        assertEquals("enc-1", body.getString("encounter_id"))
                        // 红字单自身不是「已红冲原单」：留痕三列与派生列必须为 null
                        assertNull(body.getString("reversal_reason"))
                        assertNull(body.getString("reversed_by"))
                        assertNull(body.getString("reversed_at"))
                        assertNull(body.getString("reversal_bill_id"), "红字单没有冲销它的红字单")
                        assertEquals(0, body.getJsonArray("items").size(), "红字单不带明细")
                        assertTrue(body.getString("id").length == 26, "红字单 id 必须是 26 位 ULID")

                        // 入库形态：插入的 total_amount 为负、reversal_of 指向原单
                        val insert = stub.tuples.first { it.first.contains("insert into healthcare.bills") }
                        assertTrue(
                            insert.first.contains("reversal_of) values"),
                            "reversal_of 必须落在 INSERT 列清单（而非被当成未知列）: ${insert.first}",
                        )
                        assertEquals(0, BigDecimal("-3100.00").compareTo(insert.second[5] as BigDecimal))
                        assertEquals("bill-1", insert.second[8], "插入必须写 reversal_of = 原单 id")
                        // 原单留痕：只写红冲三列 + updated_at
                        val update = stub.tuples.first { it.first.contains("update healthcare.bills") }
                        assertEquals("重复计费，按实际在院区间重算", update.second[0], "reason 必须 trim 后写原单")
                        assertEquals("cashier-route-1", update.second[1], "reversed_by 必须取认证主体")
                        assertNotNull(update.second[2], "reversed_at 必须写入")
                        assertEquals(
                            5,
                            update.second.size,
                            "原单只允许被更新 红冲三列 + updated_at（第 5 个绑定是 where id）",
                        )
                        assertEquals("bill-1", update.second[4], "留痕必须写在原单 id 上")
                        assertTrue(
                            update.first.contains("set reversal_reason = \$1, reversed_by = \$2, reversed_at = "),
                            "SET 子句必须是裸列名（PostgreSQL 的 SET 不接受表名前缀）: ${update.first}",
                        )
                        assertFalse(
                            update.first.contains("total_amount"),
                            "原单 total_amount 不得被改写: ${update.first}",
                        )
                        assertFalse(update.first.contains("set status"), "原单 status 不得被改写: ${update.first}")
                        val original = stub.bills.first { it["id"] == "bill-1" }
                        assertEquals(0, BigDecimal("3100.00").compareTo(original["total_amount"] as BigDecimal), "原单金额不变")
                        assertEquals(BillingEngine.STATUS_PENDING, original["status"], "原单状态不变")
                        assertEquals("cashier-route-1", original["reversed_by"])
                        assertNotNull(original["reversed_at"])
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `红冲成功返回红字单且列表与详情带派生字段`() {
        val stub = reversalStub()
        val service = BillService(stub.pool)
        val red = service.reverseBill("bill-1", reversalBody(), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        val redId = red.getString("id")
        assertEquals(0, BigDecimal("-3100.00").compareTo(amount(red.getValue("total_amount"))))

        // 原单详情：留痕 + 派生 reversal_bill_id 指向红字单
        val original = service.getBill("bill-1").toCompletionStage().toCompletableFuture().get()
        assertEquals("重复计费，按实际在院区间重算", original.getString("reversal_reason"))
        assertEquals("cashier-1", original.getString("reversed_by"))
        assertNotNull(original.getString("reversed_at"))
        assertNull(original.getString("reversal_of"))
        assertEquals(redId, original.getString("reversal_bill_id"), "已红冲原单必须派生指向红字单的 id")

        // 列表：两张单都返回，字段名与 034 §4 表格逐字一致
        val list = service.listBills("enc-1", limit = 10, offset = 0)
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(2L, list.getJsonObject("meta").getLong("total"))
        val records = list.getJsonArray("records")
        val listed = records.map { it as JsonObject }.associateBy { it.getString("id") }
        val originalRecord = listed.getValue("bill-1")
        val redRecord = listed.getValue(redId)
        for (row in listOf(originalRecord, redRecord)) {
            for (field in listOf("reversal_of", "reversal_reason", "reversed_by", "reversed_at", "reversal_bill_id")) {
                assertTrue(row.containsKey(field), "列表记录必须含 $field（契约字段名）")
            }
        }
        assertEquals(redId, originalRecord.getString("reversal_bill_id"), "原单列表记录派生指向红字单")
        assertEquals("bill-1", redRecord.getString("reversal_of"), "红字单列表记录指向原单")
        assertNull(redRecord.getString("reversal_bill_id"))
        assertEquals(redId, originalRecord.getString("reversal_bill_id"), "原单列表记录派生指向红字单")
        // reversal_bill_id 必须是服务端 LEFT JOIN 派生（034 §4：只读字段，不由客户端提交）
        val derivedSql = stub.queries.first { it.contains("reversal_bill_id") }
        assertTrue(derivedSql.contains("left outer join"), "reversal_bill_id 必须由 LEFT JOIN 派生: $derivedSql")
        assertTrue(
            derivedSql.contains("red_bill.reversal_of = healthcare.bills.id"),
            "红字单自连接条件必须是 red_bill.reversal_of = 原单 id: $derivedSql",
        )
    }

    // ========================================================================
    //  2. 请求体校验（400 且不触发 SQL）
    // ========================================================================

    @Test
    fun `红冲请求体非法一律400且不触发SQL`() {
        val stub = reversalStub()
        val service = BillService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.reverseBill("bill-1", body, "cashier-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(JsonObject(), "reason is required")
        expectInvalid(JsonObject().put("reason", 12), "reason must be a string")
        expectInvalid(JsonObject().put("reason", "   "), "reason must not be blank")
        expectInvalid(JsonObject().put("reason", "x".repeat(501)), "reason must not exceed 500 characters")
        expectInvalid(reversalBody(mapOf("reason" to null)), "reason is required")
        // 白名单严格：operator / reversal_of / total_amount 等一律不得由客户端提交
        expectInvalid(reversalBody(mapOf("operator" to "cashier-9")), "unsupported bill reversal keys: operator")
        expectInvalid(reversalBody(mapOf("reversal_of" to "bill-2")), "unsupported bill reversal keys: reversal_of")
        expectInvalid(
            reversalBody(mapOf("total_amount" to -3100)),
            "unsupported bill reversal keys: total_amount",
        )

        assertTrue(stub.queries.isEmpty(), "请求体校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls, "请求体校验失败不得开启事务")
        assertTrue(stub.bills.single()["reversed_at"] == null, "校验失败不得写原单留痕")
    }

    // ========================================================================
    //  3. 红冲前提：逐条独立报错
    // ========================================================================

    @Test
    fun `红字单不可再红冲返回400`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow(), redBillRow()))
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-red", reversalBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("cannot reverse a reversal bill") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") }, "不得写入新账单")
    }

    @Test
    fun `已红冲原单再红冲返回409`() {
        val stub = DatabaseStub(bills = mutableListOf(reversedBillRow(), redBillRow()))
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-1", reversalBody(), "cashier-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already reversed") == true, "got: ${cause.message}")
        assertTrue(
            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
            "不得写入第二张红字单",
        )
        assertNotNull(stub.bills.first { it["id"] == "bill-1" }["reversed_at"], "原单留痕不得被覆盖")
    }

    @Test
    fun `非待缴费账单红冲返回409`() {
        for (status in listOf(BillingEngine.STATUS_PAID, BillingEngine.STATUS_SETTLED)) {
            val stub = DatabaseStub(bills = mutableListOf(billRow(status = status)))
            val cause = causeOf(BillService(stub.pool).reverseBill("bill-1", reversalBody(), "cashier-1"))
            assertInstanceOf(ConflictException::class.java, cause)
            assertTrue(
                cause.message?.contains("bill status is not ${BillingEngine.STATUS_PENDING}, cannot reverse") == true,
                "status=$status got: ${cause.message}",
            )
            assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
        }
    }

    @Test
    fun `已关账encounter红冲返回409`() {
        val stub = DatabaseStub(
            encounters = mutableListOf(encounterRow(mapOf("settled_at" to OffsetDateTime.parse("2026-09-30T10:00:00+08:00")))),
            bills = mutableListOf(billRow()),
        )
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-1", reversalBody(), "cashier-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("encounter billing is settled, cannot reverse") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
    }

    @Test
    fun `已有缴费记录的账单红冲返回409`() {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow()),
            payments = mutableListOf(paymentRow("bill-1")),
        )
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-1", reversalBody(), "cashier-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("bill has payments, cannot reverse") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
        assertTrue(stub.bills.single()["reversed_at"] == null, "有缴费记录时不得写留痕")
    }

    @Test
    fun `非正金额账单红冲返回400`() {
        // 0 元封口账单（结算路径无可用字典单价时生成）：没有可冲销金额，
        // 且红字单会被 V523 的 CHECK(reversal_of IS NULL OR total_amount < 0) 判为非法
        val stub = DatabaseStub(bills = mutableListOf(billRow(total = "0.00")))
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-1", reversalBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("bill total must be positive, cannot reverse") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
    }

    @Test
    fun `账单不存在红冲返回404`() {
        val stub = DatabaseStub()
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-missing", reversalBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("bill not found") == true, "got: ${cause.message}")
    }

    @Test
    fun `红冲前提按固定顺序判定`() {
        // 目标既是红字单又带 0 金额与留痕（DB 的 CHECK 禁止，这里锁定服务端判定顺序）：
        // 红字单判定（400）先于金额（400）与已红冲（409）
        val stub = DatabaseStub(
            bills = mutableListOf(billRow(id = "bill-red", total = "0.00", reversalOf = "bill-1", reversedBy = "x", reversedAt = "2026-09-30T10:00:00+08:00", reversalReason = "重复计费")),
        )
        val cause = causeOf(BillService(stub.pool).reverseBill("bill-red", reversalBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("cannot reverse a reversal bill") == true, "got: ${cause.message}")

        // 非正金额（400）先于已红冲（409）
        val zeroReversed = DatabaseStub(
            bills = mutableListOf(billRow(total = "0.00", reversedBy = "x", reversedAt = "2026-09-30T10:00:00+08:00", reversalReason = "重复计费")),
        )
        val second = causeOf(BillService(zeroReversed.pool).reverseBill("bill-1", reversalBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, second)
        assertTrue(second.message?.contains("bill total must be positive") == true, "got: ${second.message}")
    }

    // ========================================================================
    //  4. 三处守卫：不可加项 / 不可缴费 / 不可核销
    // ========================================================================

    @Test
    fun `红字单与已红冲原单不可加项而普通账单可加项`() {
        val stub = DatabaseStub(
            bills = mutableListOf(redBillRow(), reversedBillRow(), billRow(id = "bill-plain")),
            feeItems = mutableListOf(feeItemRow("fee-other", "其他", "自费药", "50")),
        )
        val service = BillService(stub.pool)

        val red = causeOf(service.addItem("bill-red", addBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, red)
        assertTrue(red.message?.contains("bill is a reversal bill, cannot add items") == true, "got: ${red.message}")

        val reversed = causeOf(service.addItem("bill-1", addBody(), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, reversed)
        assertTrue(reversed.message?.contains("bill is already reversed, cannot add items") == true, "got: ${reversed.message}")

        // 对照：未被红冲的待缴费账单仍可加项（守卫不误伤）
        assertNotNull(
            service.addItem("bill-plain", addBody(), "cashier-1").toCompletionStage().toCompletableFuture().get(),
            "普通待缴费账单必须仍可加项",
        )
        assertTrue(
            stub.tuples.any { it.first.contains("insert into healthcare.bill_items") },
            "对照用例必须真的写入明细",
        )
    }

    // ========================================================================
    //  5. 红冲后可重算 + 收束口径
    // ========================================================================

    @Test
    fun `红冲后同账期可重新生成且precheck同口径`() {
        val stub = DatabaseStub(
            bills = mutableListOf(reversedBillRow(), redBillRow()),
            feeItems = mutableListOf(feeItemRow("fee-bed", "床位费", "标准床位", "100")),
        )
        val service = BillService(stub.pool)

        // precheck：已被红冲的原单与红字单都不算「已存在」→ 可生成
        val precheck = service.precheckBillGeneration("enc-1", "2026-08").toCompletionStage().toCompletableFuture().get()
        assertEquals(true, precheck.getBoolean("can_generate"), "红冲后同账期必须可生成: $precheck")
        assertNull(precheck.getString("blocked_by"))

        // generate：同账期重新生成成功（部分唯一索引与应用层预检同口径）
        val regenerated = service.generate("enc-1", JsonObject().put("month", "2026-08"), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(BillingEngine.STATUS_PENDING, regenerated.getString("status"))
        assertNull(regenerated.getString("reversal_of"), "重算出的新单是「有效原单」")
        val effective = stub.bills.filter { it["reversal_of"] == null && it["reversed_at"] == null }
        assertEquals(1, effective.size, "同账期只能有一张有效原单")
        // 预检谓词必须与部分唯一索引同口径
        val existsSql = stub.queries.last { it.contains("count(*)") && it.contains("period_start") }
        assertTrue(
            existsSql.contains("reversal_of is null") && existsSql.contains("reversed_at is null"),
            "有效原单预检必须与部分唯一索引同谓词: $existsSql",
        )
    }

    @Test
    fun `红冲后同账期已有有效原单时生成仍409`() {
        // 对照：未被红冲的原单仍挡重复生成（谓词只放过「已红冲原单 + 红字单」）
        val stub = DatabaseStub(
            bills = mutableListOf(billRow()),
            feeItems = mutableListOf(feeItemRow("fee-bed", "床位费", "标准床位", "100")),
        )
        val cause = causeOf(BillService(stub.pool).generate("enc-1", JsonObject().put("month", "2026-08"), "cashier-1"))
        assertInstanceOf(DuplicateBillException::class.java, cause)
        assertTrue(cause.message?.contains("already exists") == true, "got: ${cause.message}")
    }

    @Test
    fun `收束预览不重出最终账单且已红冲原单不计未结`() {
        val stub = DatabaseStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-31T00:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(reversedBillRow(), redBillRow()),
            deposits = mutableListOf(depositRow("登记", "5000.00")),
        )
        val preview = BillService(stub.pool).previewSettlement(stub.connection, "enc-1", requireTerminalStatus = true)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(amount(preview.getValue("final_bill_total"))),
            "收束区间恰好等于被红冲账期时不得重出最终账单（§2.7 不做自动重算）: $preview",
        )
        assertNull(preview.getString("final_bill_id"))
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(preview.getValue("pending_balance"))), "已红冲原单不是未结")
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(preview.getValue("outstanding_total"))))
        assertEquals(false, preview.getBoolean("requires_write_off"), "无幻影未结则不需要减免原因")
    }

    @Test
    fun `未结口径排除已红冲原单`() {
        val stub = DatabaseStub(bills = mutableListOf(reversedBillRow(), redBillRow()))
        val outstanding = BillService(stub.pool).outstandingBills(stub.connection, "enc-1")
            .toCompletionStage().toCompletableFuture().get()
        assertTrue(outstanding.isEmpty(), "已红冲原单与红字单都不得算作未结: $outstanding")
        val sql = stub.queries.first { it.contains("group by") }
        assertTrue(sql.contains("reversed_at is null"), "未结查询必须排除已红冲原单: $sql")

        // 未红冲的待缴费账单仍照常计入
        val payable = DatabaseStub(bills = mutableListOf(billRow()))
        val rows = BillService(payable.pool).outstandingBills(payable.connection, "enc-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(1, rows.size)
        assertEquals(0, BigDecimal("3100.00").compareTo(rows.single().balance))
    }

    // ========================================================================
    //  6. 路由：鉴权与会话挂载
    // ========================================================================

    @Test
    fun `红冲未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = reversalStub()
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/reversal", reversalBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status, "无认证 userId 红冲必须 401")
                        assertNotNull(body.getString("error"))
                        assertTrue(stub.queries.isEmpty(), "未认证不得触发任何 SQL: ${stub.queries}")
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `红冲路由错误码映射`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = reversalStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            // 目标不存在 → 404
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-missing/reversal", reversalBody())
                .compose { (missingStatus, missingBody) ->
                    ctx.verify {
                        assertEquals(404, missingStatus)
                        assertTrue(missingBody.getString("error")?.contains("bill not found") == true)
                    }
                    // 缺 reason → 400
                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/reversal", JsonObject())
                        .compose { (invalidStatus, invalidBody) ->
                            ctx.verify {
                                assertEquals(400, invalidStatus)
                                assertTrue(invalidBody.getString("error")?.contains("reason is required") == true)
                            }
                            // 已红冲 → 409
                            stub.bills.first { it["id"] == "bill-1" }["reversed_at"] =
                                OffsetDateTime.parse("2026-09-30T10:00:00+08:00")
                            stub.bills.first { it["id"] == "bill-1" }["reversed_by"] = "cashier-1"
                            stub.bills.first { it["id"] == "bill-1" }["reversal_reason"] = "重复计费"
                            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/bills/bill-1/reversal", reversalBody())
                                .map { (conflictStatus, conflictBody) ->
                                    ctx.verify {
                                        assertEquals(409, conflictStatus)
                                        assertTrue(conflictBody.getString("error")?.contains("already reversed") == true)
                                    }
                                }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ========================================================================
    //  嵌入式 HTTP 基础设施
    // ========================================================================

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

// ——— fixture 构造（与同目录其它测试同写法：顶层私有） ———

private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
    val base = mutableMapOf<String, Any?>(
        "id" to "enc-1",
        "encounter_type" to "ELDERLY_CARE",
        "status" to "ADMITTED",
        "settled_at" to null,
        "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
        "discharge_date" to null,
    )
    base.putAll(overrides)
    return base
}

private fun billRow(
    id: String = "bill-1",
    periodStart: String = "2026-08-01",
    periodEnd: String = "2026-08-31",
    total: String = "3100.00",
    status: String = BillingEngine.STATUS_PENDING,
    reversalOf: String? = null,
    reversalReason: String? = null,
    reversedBy: String? = null,
    reversedAt: String? = null,
): MutableMap<String, Any?> =
    mutableMapOf(
        "id" to id,
        "encounter_id" to "enc-1",
        "period_start" to LocalDate.parse(periodStart),
        "period_end" to LocalDate.parse(periodEnd),
        "status" to status,
        "total_amount" to BigDecimal(total),
        "reversal_of" to reversalOf,
        "reversal_reason" to reversalReason,
        "reversed_by" to reversedBy,
        "reversed_at" to reversedAt?.let(OffsetDateTime::parse),
        "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
    )

/** 红字单（金额取反、reversal_of 指向原单）。 */
private fun redBillRow(id: String = "bill-red", reversalOf: String = "bill-1", total: String = "-3100.00") =
    billRow(id = id, total = total, reversalOf = reversalOf)

/** 已红冲原单（原单金额与状态不动 + 红冲三列留痕）。 */
private fun reversedBillRow(
    id: String = "bill-1",
    reason: String = "重复计费，按实际在院区间重算",
    reversedBy: String = "cashier-1",
) = billRow(
    id = id,
    reversalReason = reason,
    reversedBy = reversedBy,
    reversedAt = "2026-09-30T10:00:00+08:00",
)

private fun paymentRow(billId: String, amount: String = "100.00"): MutableMap<String, Any?> =
    mutableMapOf(
        "id" to "pay-$billId",
        "bill_id" to billId,
        "amount" to BigDecimal(amount),
        "method" to "现金",
        "operator" to "cashier-1",
        "created_at" to OffsetDateTime.parse("2026-09-01T10:00:00+08:00"),
        "updated_at" to OffsetDateTime.parse("2026-09-01T10:00:00+08:00"),
    )

private fun depositRow(type: String, amount: String): MutableMap<String, Any?> =
    mutableMapOf(
        "id" to "dep-$type-$amount",
        "encounter_id" to "enc-1",
        "type" to type,
        "amount" to BigDecimal(amount),
    )

private fun feeItemRow(id: String, category: String, name: String, price: String, status: String = "启用") =
    mapOf(
        "id" to id,
        "category" to category,
        "name" to name,
        "unit_price" to BigDecimal(price),
        "status" to status,
        "nursing_level" to null,
    )

// ——— mock 基础设施（顶层函数，与同目录其它测试同写法） ———

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

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}
