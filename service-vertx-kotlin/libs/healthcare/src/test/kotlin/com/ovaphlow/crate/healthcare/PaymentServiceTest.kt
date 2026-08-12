package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
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
 * 缴费与欠费（PaymentService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 多次部分缴费累加（余额递减）；部分缴费后账单状态仍为待缴费；
 *     余额归零后账单状态 = 已结清（同事务更新）
 *   - 超缴 400（单笔缴费使累计缴费 > 账单合计）且不写入；
 *     金额非法（≤ 0、超两位小数、越界）、缺必填 400 {error}
 *   - 缴费方式白名单：非法 400；五种合法方式均缴费成功
 *   - 缴费流水按账单分页 {records, meta:{total}}；空流水 records:[] total:0
 *   - 欠费列表（待缴费且余额 > 0）{records, meta:{total}}，分页 limit/offset 生效
 *   - summary 应缴/已缴/欠费总额（应缴 − 已缴 = 欠费）；无数据时三项均为 0
 *   - 未认证访问缴费/流水/欠费列表/summary 端点均 401
 */
@ExtendWith(VertxExtension::class)
class PaymentServiceTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * insert 成功后自动追加到 payments，update 更新 bills 状态，
     * 欠费/汇总/流水/已缴聚合均据此派生，使「多次部分缴费 → 结清 → 欠费/汇总」
     * 链路可程序化演进。
     */
    private class DatabaseStub(
        var bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var payments: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(
            mutableMapOf("id" to "enc-1", "settled_at" to null),
        ),
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
                                "remark" to values.getOrNull(7),
                                "metadata" to values.getOrNull(8),
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["status"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("from healthcare.bills") && sql.contains("for update") -> {
                        val row = bills.firstOrNull { it["id"] == values.getOrNull(0) }?.let { mockRow(it) }
                        Future.succeededFuture(row?.let { rowSet(it) } ?: rowSet())
                    }
                    sql.contains("from healthcare.encounters") && sql.contains("for update") -> {
                        // 冻结守卫：requireEncounter 事务内行锁读；settled_at 非空 = 已冻结
                        val row = encounters.firstOrNull { it["id"] == values.getOrNull(0) }?.let { mockRow(it) }
                        Future.succeededFuture(row?.let { rowSet(it) } ?: rowSet())
                    }
                    sql.contains("arrears_amount") -> {
                        val arrears = arrearsList()
                        val total = arrears.fold(BigDecimal.ZERO) { acc, b ->
                            acc.add((b["total_amount"] as BigDecimal).subtract(b["paid_amount"] as BigDecimal))
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("arrears_amount" to total))))
                    }
                    sql.contains("count(*)") && sql.contains("left outer join") ->
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to arrearsList().size.toLong()))))
                    sql.contains("left outer join") ->
                        Future.succeededFuture(rowSet(*arrearsList().map { mockRow(it) }.toTypedArray()))
                    sql.contains("due_amount") -> {
                        val total = bills.fold(BigDecimal.ZERO) { acc, b -> acc.add(b["total_amount"] as BigDecimal) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("due_amount" to total))))
                    }
                    sql.contains("paid_amount") -> {
                        val total = payments.fold(BigDecimal.ZERO) { acc, p -> acc.add(p["amount"] as BigDecimal) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("paid_amount" to total))))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.payments") -> {
                        val scoped = payments.filter { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to scoped.size.toLong()))))
                    }
                    sql.contains("from healthcare.payments") && sql.contains("order by") -> {
                        val scoped = payments
                            .filter { it["bill_id"] == values.getOrNull(0) }
                            .sortedWith(
                                compareByDescending<MutableMap<String, Any?>> { it["created_at"] as OffsetDateTime }
                                    .thenByDescending { it["id"] as String },
                            )
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from healthcare.payments") -> {
                        val scoped = payments.filter { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(
                            rowSet(*scoped.map { mockRow(mapOf("amount" to it["amount"])) }.toTypedArray()),
                        )
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

        /** 欠费口径：状态 待缴费 且 余额 > 0；余额 = 合计 − 累计缴费。 */
        private fun arrearsList(): List<MutableMap<String, Any?>> =
            bills.mapNotNull { b ->
                if (b["status"] != BillingEngine.STATUS_PENDING) return@mapNotNull null
                val paid = payments
                    .filter { it["bill_id"] == b["id"] }
                    .fold(BigDecimal.ZERO) { acc, p -> acc.add(p["amount"] as BigDecimal) }
                val total = b["total_amount"] as BigDecimal
                if (total.compareTo(paid) <= 0) return@mapNotNull null
                mutableMapOf(
                    "id" to b["id"],
                    "encounter_id" to b["encounter_id"],
                    "period_start" to b["period_start"],
                    "period_end" to b["period_end"],
                    "status" to b["status"],
                    "total_amount" to total,
                    "paid_amount" to paid,
                    "balance" to total.subtract(paid),
                    "created_at" to b["created_at"],
                    "updated_at" to b["updated_at"],
                )
            }
    }

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "settled_at" to null,
        )
        base.putAll(overrides)
        return base
    }

    private fun billRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "bill-1",
            "encounter_id" to "enc-1",
            "period_start" to LocalDate.parse("2026-08-01"),
            "period_end" to LocalDate.parse("2026-08-31"),
            "status" to BillingEngine.STATUS_PENDING,
            "total_amount" to BigDecimal("1000.00"),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun paymentBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject().put("amount", 100).put("method", "现金").put("remark", "缴费")
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

    // ——— 1. 多次部分缴费累加、余额递减与状态机 ———

    @Test
    fun `多次部分缴费累加余额递减且部分缴费后账单仍为待缴费`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        val service = PaymentService(stub.pool)

        val first = service.createPayment("bill-1", paymentBody(mapOf("amount" to 300, "method" to "现金")), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("现金", first.getString("method"))
        assertEquals("cashier-1", first.getString("operator"))
        assertEquals("bill-1", first.getString("bill_id"))
        assertTrue(first.getString("id").length == 26, "缴费必须生成 26 位 ULID")

        val second = service.createPayment("bill-1", paymentBody(mapOf("amount" to 200, "method" to "转账")), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, BigDecimal("200").compareTo(second.getValue("amount") as BigDecimal))

        // 累计缴费 = 300 + 200 = 500；余额 = 1000 − 500 = 500 > 0 → 仍为待缴费
        assertEquals(2, stub.payments.size)
        assertEquals(0, PaymentService.paidOf(stub.payments.map { it["amount"] as BigDecimal }).compareTo(BigDecimal("500")))
        assertEquals(BillingEngine.STATUS_PENDING, stub.bills.single()["status"], "部分缴费后账单必须仍为待缴费")
        assertTrue(stub.tuples.none { it.first.contains("update healthcare.bills") }, "部分缴费不得触发状态更新")
    }

    @Test
    fun `余额归零后账单状态流转为已结清`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        val service = PaymentService(stub.pool)

        service.createPayment("bill-1", paymentBody(mapOf("amount" to 400)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        service.createPayment("bill-1", paymentBody(mapOf("amount" to 600)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2, stub.payments.size)
        assertEquals(BillingEngine.STATUS_PAID, stub.bills.single()["status"], "余额归零后账单必须流转为已结清")
        val update = stub.tuples.first { it.first.contains("update healthcare.bills") }
        assertEquals(BillingEngine.STATUS_PAID, update.second[0], "状态更新必须写 已结清")
    }

    @Test
    fun `单笔缴费恰好结清边界一次缴费即流转已结清`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        val payment = PaymentService(stub.pool)
            .createPayment("bill-1", paymentBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, BigDecimal("1000").compareTo(payment.getValue("amount") as BigDecimal))
        assertEquals(BillingEngine.STATUS_PAID, stub.bills.single()["status"])
    }

    @Test
    fun `超缴返回400且不写入`() {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow()),
            payments = mutableListOf(paymentRecord("600.00")),
        )
        val service = PaymentService(stub.pool)

        val insertsBefore = stub.tuples.count { it.first.contains("insert into healthcare.payments") }
        val cause = causeOf(service.createPayment("bill-1", paymentBody(mapOf("amount" to 400.01)), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("exceeds") == true, "got: ${cause.message}")
        assertEquals(
            insertsBefore,
            stub.tuples.count { it.first.contains("insert into healthcare.payments") },
            "超缴不得写入任何缴费记录",
        )
        assertEquals(BillingEngine.STATUS_PENDING, stub.bills.single()["status"], "超缴不得改变账单状态")

        // 全新账单直接超缴同样 400
        val cause2 = causeOf(PaymentService(DatabaseStub(bills = mutableListOf(billRow())).pool)
            .createPayment("bill-1", paymentBody(mapOf("amount" to 1000.01)), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("exceeds") == true, "got: ${cause2.message}")
    }

    @Test
    fun `已结清账单不可再缴费返回400`() {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow(mapOf("status" to BillingEngine.STATUS_PAID))),
            payments = mutableListOf(paymentRecord("1000.00")),
        )
        val cause = causeOf(PaymentService(stub.pool)
            .createPayment("bill-1", paymentBody(mapOf("amount" to 1)), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("待缴费") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
    }

    @Test
    fun `账单不存在返回404`() {
        val stub = DatabaseStub()
        val cause = causeOf(PaymentService(stub.pool)
            .createPayment("missing", paymentBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("bill not found") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
    }

    // ——— 2. 输入校验 ———

    @Test
    fun `缺必填与金额非法返回400且不触发SQL`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        val service = PaymentService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.createPayment("bill-1", body, "cashier-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(JsonObject(), "amount is required")
        expectInvalid(paymentBody(mapOf("amount" to 0)), "positive")
        expectInvalid(paymentBody(mapOf("amount" to -1)), "positive")
        expectInvalid(paymentBody(mapOf("amount" to "100")), "must be a number")
        expectInvalid(paymentBody(mapOf("amount" to 1.999)), "at most 2 decimal places")
        expectInvalid(paymentBody(mapOf("amount" to 10000000000.0)), "must not exceed")
        expectInvalid(paymentBody(mapOf("method" to null)), "method is required")
        expectInvalid(paymentBody(mapOf("method" to "信用卡")), "method must be one of")
        expectInvalid(paymentBody(mapOf("operator" to "hacker")), "unsupported payment keys: operator")
        expectInvalid(paymentBody(mapOf("id" to "hacked")), "unsupported payment keys: id")
        expectInvalid(paymentBody(mapOf("bill_id" to "other")), "unsupported payment keys: bill_id")
        expectInvalid(paymentBody(mapOf("remark" to "x".repeat(501))), "500")
        expectInvalid(paymentBody(mapOf("metadata" to JsonArray().add(1))), "metadata must be a JSON object")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
    }

    // ——— 3. 缴费方式白名单 ———

    @Test
    fun `五种合法缴费方式均缴费成功`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow(mapOf("total_amount" to BigDecimal("5000.00")))))
        val service = PaymentService(stub.pool)

        for (method in PaymentService.methods) {
            val payment = service.createPayment("bill-1", paymentBody(mapOf("amount" to 1000, "method" to method)), "cashier-1")
                .toCompletionStage().toCompletableFuture().get()
            assertEquals(method, payment.getString("method"))
        }

        assertEquals(5, stub.payments.size)
        assertEquals(
            listOf("现金", "转账", "银行卡", "微信", "支付宝"),
            stub.payments.map { it["method"] },
        )
        assertEquals(BillingEngine.STATUS_PAID, stub.bills.single()["status"], "5000 分五次缴清后必须已结清")
    }

    @Test
    fun `非法缴费方式返回400且不触发SQL`() {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        for (method in listOf("信用卡", "花呗", "白条", "现金券", "")) {
            val cause = causeOf(PaymentService(stub.pool)
                .createPayment("bill-1", paymentBody(mapOf("method" to method)), "cashier-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains("method must be one of") == true, "got: ${cause.message}")
        }
        assertTrue(stub.queries.isEmpty(), "非法缴费方式不得触发任何 SQL: ${stub.queries}")
    }

    // ——— 4. 缴费流水 ———

    @Test
    fun `缴费流水按账单分页返回records与meta且空流水为空`() {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow()),
            payments = mutableListOf(
                paymentRecord("100.00", id = "pay-1", createdAt = "2026-08-01T10:00:00+08:00"),
                paymentRecord("200.00", id = "pay-2", createdAt = "2026-08-01T11:00:00+08:00"),
                paymentRecord("300.00", id = "pay-3", createdAt = "2026-08-01T12:00:00+08:00"),
            ),
        )
        val ledger = PaymentService(stub.pool)
            .listPayments("bill-1", limit = 10, offset = 5)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(3, ledger.getJsonArray("records").size())
        assertEquals(3L, ledger.getJsonObject("meta").getLong("total"))

        val record = ledger.getJsonArray("records").getJsonObject(0)
        assertEquals("pay-3", record.getString("id"), "流水必须按时间倒序，最新在前")
        assertEquals("bill-1", record.getString("bill_id"))
        assertEquals("现金", record.getString("method"))
        assertNotNull(record.getString("created_at"))
        assertTrue(record.containsKey("remark"), "流水记录必须包含 remark 字段")

        val dataSql = stub.queries.first { it.contains("fetch next") && it.contains("from healthcare.payments") }
        assertTrue(dataSql.contains("offset $"), "流水必须分页 offset: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "流水必须分页 limit: $dataSql")
        assertTrue(dataSql.contains("order by"), "流水必须倒序: $dataSql")
        assertTrue(
            stub.queries.first { it.contains("count(*)") && it.contains("from healthcare.payments") }.contains("bill_id = $1"),
            "流水计数必须按账单过滤",
        )

        // 其他账单空流水：records: [] 且 total: 0
        val empty = PaymentService(stub.pool)
            .listPayments("bill-other")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, empty.getJsonArray("records").size())
        assertEquals(0L, empty.getJsonObject("meta").getLong("total"))
    }

    // ——— 5. 欠费列表 ———

    @Test
    fun `欠费列表只含待缴费且余额大于0的账单`() {
        val stub = DatabaseStub(
            bills = mutableListOf(
                billRow(mapOf("id" to "bill-1", "total_amount" to BigDecimal("1000.00"))),
                billRow(mapOf("id" to "bill-2", "total_amount" to BigDecimal("500.00"), "status" to BillingEngine.STATUS_PAID)),
                billRow(mapOf("id" to "bill-3", "total_amount" to BigDecimal("200.00"))),
                billRow(mapOf("id" to "bill-4", "total_amount" to BigDecimal("800.00"), "status" to BillingEngine.STATUS_SETTLED)),
            ),
            payments = mutableListOf(
                paymentRecord("300.00", billId = "bill-1"),
                paymentRecord("500.00", billId = "bill-2"),
            ),
        )
        val arrears = PaymentService(stub.pool)
            .listArrears(limit = 10, offset = 0)
            .toCompletionStage().toCompletableFuture().get()

        // 已结清（bill-2）与已结算（bill-4）不欠费；bill-1 欠 700、bill-3 欠 200
        assertEquals(2, arrears.getJsonArray("records").size())
        assertEquals(2L, arrears.getJsonObject("meta").getLong("total"))
        val ids = arrears.getJsonArray("records").map { it as JsonObject }.map { it.getString("id") }.toSet()
        assertEquals(setOf("bill-1", "bill-3"), ids)

        val bill1 = arrears.getJsonArray("records").map { it as JsonObject }.first { it.getString("id") == "bill-1" }
        assertEquals("待缴费", bill1.getString("status"))
        assertEquals(0, BigDecimal("1000.00").compareTo(bill1.getValue("total_amount") as BigDecimal))
        assertEquals(0, BigDecimal("300.00").compareTo(bill1.getValue("paid_amount") as BigDecimal), "累计缴费必须随流水派生")
        assertEquals(0, BigDecimal("700.00").compareTo(bill1.getValue("balance") as BigDecimal), "余额 = 合计 − 累计缴费")

        val dataSql = stub.queries.first { it.contains("left outer join") && it.contains("fetch next") }
        assertTrue(dataSql.contains("offset $"), "欠费列表必须分页 offset: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "欠费列表必须分页 limit: $dataSql")
        assertTrue(dataSql.contains("status = $"), "欠费列表必须按状态过滤: $dataSql")
        val countSql = stub.queries.first { it.contains("count(*)") && it.contains("left outer join") }
        assertTrue(countSql.contains("status = $"), "欠费计数必须按状态过滤: $countSql")
    }

    @Test
    fun `空欠费列表返回空records与total0`() {
        val stub = DatabaseStub(
            bills = mutableListOf(
                billRow(mapOf("status" to BillingEngine.STATUS_PAID)),
                billRow(mapOf("id" to "bill-2", "status" to BillingEngine.STATUS_PENDING, "total_amount" to BigDecimal("0.00"))),
            ),
        )
        val arrears = PaymentService(stub.pool)
            .listArrears()
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, arrears.getJsonArray("records").size())
        assertEquals(0L, arrears.getJsonObject("meta").getLong("total"))
    }

    // ——— 6. 汇总 ———

    @Test
    fun `summary应缴已缴欠费满足恒等式`() {
        val stub = DatabaseStub(
            bills = mutableListOf(
                billRow(mapOf("id" to "bill-1", "total_amount" to BigDecimal("1000.00"))),
                billRow(mapOf("id" to "bill-2", "total_amount" to BigDecimal("500.00"), "status" to BillingEngine.STATUS_PAID)),
                billRow(mapOf("id" to "bill-3", "total_amount" to BigDecimal("200.00"))),
            ),
            payments = mutableListOf(
                paymentRecord("300.00", billId = "bill-1"),
                paymentRecord("500.00", billId = "bill-2"),
            ),
        )
        val summary = PaymentService(stub.pool)
            .summary()
            .toCompletionStage().toCompletableFuture().get()

        // 应缴 = Σ账单合计 = 1700；已缴 = Σ缴费金额 = 800；
        // 欠费 = 待缴费余额 = (1000−300) + (200−0) = 900；恒等式 1700 − 800 = 900
        val due = summary.getValue("due_amount") as BigDecimal
        val paid = summary.getValue("paid_amount") as BigDecimal
        val arrears = summary.getValue("arrears_amount") as BigDecimal
        assertEquals(0, BigDecimal("1700.00").compareTo(due), "应缴 = Σ账单合计")
        assertEquals(0, BigDecimal("800.00").compareTo(paid), "已缴 = Σ缴费金额")
        assertEquals(0, BigDecimal("900.00").compareTo(arrears), "欠费 = Σ待缴费账单余额")
        assertEquals(0, due.subtract(paid).compareTo(arrears), "应缴 − 已缴 = 欠费")

        assertTrue(stub.queries.any { it.contains("due_amount") && it.contains("from healthcare.bills") }, "应缴按账单合计聚合")
        assertTrue(stub.queries.any { it.contains("paid_amount") && it.contains("from healthcare.payments") }, "已缴按缴费金额聚合")
    }

    @Test
    fun `无数据时summary三项均为0`() {
        val stub = DatabaseStub()
        val summary = PaymentService(stub.pool)
            .summary()
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getValue("due_amount") as BigDecimal))
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getValue("paid_amount") as BigDecimal))
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.getValue("arrears_amount") as BigDecimal))
    }

    // ——— 7. 嵌入式 HTTP 路由 ———

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

    @Test
    fun `POST缴费201且operator取认证主体`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/bills/bill-1/payments",
                paymentBody(mapOf("amount" to 300)),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "缴费成功必须 201")
                    assertEquals("bill-1", body.getString("bill_id"))
                    assertEquals("现金", body.getString("method"))
                    assertEquals("cashier-route-1", body.getString("operator"), "操作人必须取认证主体")
                    assertTrue(body.getString("id").length == 26)
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `POST超缴与缺必填返回400且错误为error对象`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(bills = mutableListOf(billRow(mapOf("total_amount" to BigDecimal("100.00")))))
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/bills/bill-1/payments",
                paymentBody(mapOf("amount" to 100.01)),
            ).compose { (overStatus, overBody) ->
                ctx.verify {
                    assertEquals(400, overStatus, "超缴必须 400")
                    assertTrue(overBody.getString("error")?.contains("exceeds") == true, "got: ${overBody.getString("error")}")
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/bills/bill-1/payments",
                    JsonObject(),
                ).map { (missingStatus, missingBody) ->
                    ctx.verify {
                        assertEquals(400, missingStatus, "缺必填必须 400")
                        assertNotNull(missingBody.getString("error"), "错误响应必须为 { error: ... }")
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `GET流水与欠费列表返回records和meta`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow(mapOf("total_amount" to BigDecimal("1000.00")))),
            payments = mutableListOf(paymentRecord("300.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/bills/bill-1/payments")
                .compose { (paymentsStatus, paymentsBody) ->
                    ctx.verify {
                        assertEquals(200, paymentsStatus)
                        assertEquals(1, paymentsBody.getJsonArray("records").size())
                        assertEquals(1L, paymentsBody.getJsonObject("meta").getLong("total"))
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/payments/arrears")
                        .map { (arrearsStatus, arrearsBody) ->
                            ctx.verify {
                                assertEquals(200, arrearsStatus)
                                assertEquals(1, arrearsBody.getJsonArray("records").size())
                                assertEquals(1L, arrearsBody.getJsonObject("meta").getLong("total"))
                                val record = arrearsBody.getJsonArray("records").getJsonObject(0)
                                assertEquals(0, BigDecimal("700.00").compareTo(BigDecimal.valueOf((record.getValue("balance") as Number).toDouble())))
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `GET summary返回三项总额`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            bills = mutableListOf(
                billRow(mapOf("total_amount" to BigDecimal("1000.00"))),
                billRow(mapOf("id" to "bill-2", "total_amount" to BigDecimal("500.00"))),
            ),
            payments = mutableListOf(paymentRecord("300.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/payments/summary")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status)
                        assertEquals(0, BigDecimal("1500.00").compareTo(BigDecimal.valueOf((body.getValue("due_amount") as Number).toDouble())))
                        assertEquals(0, BigDecimal("300.00").compareTo(BigDecimal.valueOf((body.getValue("paid_amount") as Number).toDouble())))
                        assertEquals(0, BigDecimal("1200.00").compareTo(BigDecimal.valueOf((body.getValue("arrears_amount") as Number).toDouble())))
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `缴费流水欠费列表summary未认证均返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(bills = mutableListOf(billRow()))
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/bills/bill-1/payments",
                paymentBody(),
            ).compose { (payStatus, payBody) ->
                ctx.verify {
                    assertEquals(401, payStatus, "无认证 userId 缴费必须 401")
                    assertNotNull(payBody.getString("error"))
                }
                httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/bills/bill-1/payments")
                    .compose { (flowStatus, flowBody) ->
                        ctx.verify {
                            assertEquals(401, flowStatus, "无认证 userId 流水必须 401")
                            assertNotNull(flowBody.getString("error"))
                        }
                        httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/payments/arrears")
                            .compose { (arrearsStatus, arrearsBody) ->
                                ctx.verify {
                                    assertEquals(401, arrearsStatus, "无认证 userId 欠费列表必须 401")
                                    assertNotNull(arrearsBody.getString("error"))
                                }
                                httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/payments/summary")
                                    .map { (summaryStatus, summaryBody) ->
                                        ctx.verify {
                                            assertEquals(401, summaryStatus, "无认证 userId summary 必须 401")
                                            assertNotNull(summaryBody.getString("error"))
                                        }
                                    }
                            }
                    }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `欠费路由不被bills通配路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow(mapOf("status" to BillingEngine.STATUS_PAID))),
            payments = mutableListOf(paymentRecord("1000.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/payments/arrears")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status, "欠费列表必须命中独立路径而非 /bills/:id")
                        assertEquals(0L, body.getJsonObject("meta").getLong("total"))
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 8. 冻结守卫（encounters.settled_at 非空 → 409，不写入） ———

    @Test
    fun `结算冻结后缴费返回409且不写入`() {
        val frozen = DatabaseStub(
            bills = mutableListOf(billRow()),
            encounters = mutableListOf(
                encounterRow(mapOf("settled_at" to OffsetDateTime.parse("2026-08-10T09:00:00+08:00"))),
            ),
        )
        val cause = causeOf(PaymentService(frozen.pool)
            .createPayment("bill-1", paymentBody(mapOf("amount" to 100)), "cashier-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("settled") == true, "got: ${cause.message}")
        assertTrue(
            frozen.tuples.none { it.first.contains("insert into healthcare.payments") },
            "冻结后缴费不得写入",
        )
        assertEquals(BillingEngine.STATUS_PENDING, frozen.bills.single()["status"], "冻结后缴费不得改变账单状态")
    }

    @Test
    fun `未冻结encounter可正常缴费且settled_at为空语义成立`() {
        val stub = DatabaseStub(
            bills = mutableListOf(billRow()),
            encounters = mutableListOf(
                encounterRow(),
                encounterRow(mapOf("id" to "enc-other", "settled_at" to null)),
            ),
        )
        val payment = PaymentService(stub.pool)
            .createPayment("bill-1", paymentBody(mapOf("amount" to 300, "method" to "转账")), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("转账", payment.getString("method"))
        assertEquals(1, stub.payments.size)
        assertEquals(BillingEngine.STATUS_PENDING, stub.bills.single()["status"])
    }

    private fun paymentRecord(
        amount: String,
        billId: String = "bill-1",
        id: String = "pay-1",
        method: String = "现金",
        createdAt: String = "2026-08-01T10:00:00+08:00",
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to id,
            "bill_id" to billId,
            "amount" to BigDecimal(amount),
            "method" to method,
            "operator" to "cashier-1",
            "remark" to null,
            "metadata" to null,
            "created_at" to OffsetDateTime.parse(createdAt),
            "updated_at" to OffsetDateTime.parse(createdAt),
        )
}

// ——— mock 基础设施（顶层函数，供测试类与嵌套 stub 共用） ———

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
