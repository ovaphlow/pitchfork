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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 结算收束押金核销（DepositOffsetService + 结算编排 + 路由）非数据库测试
 * （mockk 全库桩 + 嵌入式 HTTP，参照 DepositServiceTest / BillingSettlementTest 模式）。
 *
 * 覆盖验收口径：
 *   - parseOffset 请求体白名单与金额校验（未知键 / 缺键 / null / 0 / 非数字 / 负数 /
 *     超两位小数 / 越界 / 上限边界）
 *   - allocate 纯函数：覆盖全部/部分/不足第一笔/为 0/空余额，等长、合计 = min(核销额, 余额合计)、
 *     不制造负数
 *   - 金额为 0：不查库不写库，返回 amount=0/count=0/allocations=[]
 *   - 资格：encounter 不存在 404、已收束 409（均不写入）
 *   - 上限 = min(押金余额, 目标账单余额合计)：超额 400 不部分执行；边界相等通过；
 *     押金余额为 0 与无欠费（余额合计 0）时任何 >0 请求 400
 *   - 逐笔分配与双台账写入：payments（method=押金）/ deposit_records（type=核销）逐笔对应，
 *     仅被全额覆盖的账单转 已结清
 *   - 路由：POST /encounters/:id/billing-settlement 带 deposit_offset → 201 且写入核销台账；
 *     未认证 401；未知键 400 不触发 SQL；空请求体等价于不核销
 */
@ExtendWith(VertxExtension::class)
class DepositOffsetServiceTest {

    // ========================================================================
    //  1. parseOffset：请求体白名单与金额校验
    // ========================================================================

    private fun causeOfParse(body: JsonObject): Throwable {
        try {
            DepositOffsetService.parseOffset(body)
            throw AssertionError("expected parseOffset to throw")
        } catch (error: IllegalArgumentException) {
            return error
        }
    }

    @Test
    fun `parseOffset未知键一律拒绝并列出键名`() {
        val amount = causeOfParse(JsonObject().put("deposit_offset", 100).put("amount", 1))
        assertEquals("unsupported settlement keys: amount", amount.message)

        val operator = causeOfParse(JsonObject().put("operator", "hacker"))
        assertEquals("unsupported settlement keys: operator", operator.message)

        val billId = causeOfParse(JsonObject().put("bill_id", "bill-1"))
        assertEquals("unsupported settlement keys: bill_id", billId.message)

        // 多键按字典序拼接（客户端无法伪造 operator / bill_id / amount）
        val multiple = causeOfParse(
            JsonObject().put("deposit_offset", 100).put("b", 2).put("a", 1).put("operator", "x"),
        )
        assertEquals("unsupported settlement keys: a, b, operator", multiple.message)
    }

    @Test
    fun `parseOffset缺键与null与0均视为不核销`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(DepositOffsetService.parseOffset(JsonObject())))
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", null as Any?)),
            ),
        )
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", 0))),
        )
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", 0.0))),
        )
    }

    @Test
    fun `parseOffset非数字返回必须为数字`() {
        for (raw in listOf<Any>("1000", true, false, JsonObject(), listOf(1))) {
            val cause = causeOfParse(JsonObject().put("deposit_offset", raw))
            assertEquals("deposit_offset must be a number", cause.message, "raw=$raw")
        }
    }

    @Test
    fun `parseOffset负数与超两位小数与越界一律拒绝`() {
        val negative = causeOfParse(JsonObject().put("deposit_offset", -1))
        assertEquals("deposit_offset must not be negative", negative.message)

        val decimal = causeOfParse(JsonObject().put("deposit_offset", 1.999))
        assertEquals("deposit_offset must have at most 2 decimal places", decimal.message)

        val overflow = causeOfParse(JsonObject().put("deposit_offset", 10000000000.0))
        assertEquals("deposit_offset must not exceed 9999999999.99", overflow.message)
    }

    @Test
    fun `parseOffset合法值返回对应金额且上限边界相等通过`() {
        assertEquals(0, BigDecimal("100").compareTo(DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", 100))))
        assertEquals(
            0,
            BigDecimal("123.45").compareTo(DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", 123.45))),
        )
        assertEquals(
            0,
            BigDecimal("3000.00").compareTo(DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", 3000.00))),
        )
        // 边界：恰好等于 9999999999.99 必须通过（未越界）
        assertEquals(
            0,
            DepositOffsetService.maxAmount.compareTo(
                DepositOffsetService.parseOffset(JsonObject().put("deposit_offset", 9999999999.99)),
            ),
        )
        assertEquals(BigDecimal("9999999999.99"), DepositOffsetService.maxAmount)
    }

    // ========================================================================
    //  2. allocate：分配纯函数
    // ========================================================================

    private fun alloc(offset: String, vararg balances: String): List<BigDecimal> =
        DepositOffsetService.allocate(BigDecimal(offset), balances.map { BigDecimal(it) })

    @Test
    fun `allocate覆盖全部余额按序吃满`() {
        val allocations = alloc("100", "50", "30", "40")
        assertEquals(listOf("50", "30", "20"), allocations.map { it.toPlainString() })
        assertEquals(0, allocations.fold(BigDecimal.ZERO) { a, b -> a.add(b) }.compareTo(BigDecimal("100")))
        assertEquals(3, allocations.size, "分配列表长度必须与 balances 一致")
    }

    @Test
    fun `allocate部分覆盖与不足第一笔`() {
        // 核销额小于第一笔余额：只吃第一笔
        assertEquals(listOf("40", "0", "0"), alloc("40", "50", "30", "40").map { it.toPlainString() })
        // 存量不足：能吃多少吃多少，不制造负数
        assertEquals(listOf("10", "0"), alloc("10", "50", "30").map { it.toPlainString() })
        // 恰好等于余额合计
        assertEquals(listOf("50", "30"), alloc("80", "50", "30").map { it.toPlainString() })
    }

    @Test
    fun `allocate核销额为0或余额为空返回全0`() {
        assertEquals(listOf("0", "0"), alloc("0", "50", "30").map { it.toPlainString() })
        assertEquals(listOf("0", "0"), alloc("-5", "50", "30").map { it.toPlainString() }, "非正核销额不得分配")
        assertEquals(emptyList<BigDecimal>(), alloc("100"), "空余额列表返回空分配列表")
    }

    @Test
    fun `allocate跳过余额非正的行且合计不超过核销额`() {
        val allocations = alloc("100", "0", "-5", "50")
        assertEquals(listOf("0", "0", "50"), allocations.map { it.toPlainString() })
        assertTrue(allocations.all { it.signum() >= 0 }, "分配不得自造负数")
        assertEquals(3, allocations.size)
    }

    // ========================================================================
    //  3. 全库 mock 桩（offsetArrears + 结算收束共用）
    // ========================================================================

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * payments / deposit_records 的 insert 自动追加到内存台账，update bills 演进账单状态，
     * 目标账单余额与押金余额均据此派生，使「逐笔分配 → 双台账写入 → 余额归零转已结清」
     * 的链路可程序化演进。
     *
     * `poolQueries` 单独记录经由 pool（而非事务连接 conn）下发的 SQL，用于断言
     * 「核销与收束共用同一连接」；`preparedQueryCalls` 用于断言「金额为 0 时不查库不写库」。
     */
    private class OffsetStub(
        val encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(encounterRow()),
        val bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        val payments: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        val deposits: MutableList<MutableMap<String, Any?>> = mutableListOf(),
    ) {
        val queries = mutableListOf<String>()
        val poolQueries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set
        var preparedQueryCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        /** 事务连接：单元测试直接调用 offsetArrears 时传入，等价于 withTransaction 的入参。 */
        val connection: SqlConnection get() = conn

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>(), viaPool = false); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>(), viaPool = false); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>(), viaPool = true); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>(), viaPool = true); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                val values = tupleValues(firstArg())
                tuples.add(sql to values)
                when {
                    // ——— 核销：双台账写入 ———
                    sql.contains("insert into healthcare.payments") -> {
                        payments.add(
                            mutableMapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "amount" to values[2],
                                "method" to values[3],
                                "operator" to values[4],
                                "metadata" to values[5],
                                "created_at" to values[6],
                                "updated_at" to values[7],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("insert into healthcare.deposit_records") -> {
                        deposits.add(
                            mutableMapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "type" to values[2],
                                "amount" to values[3],
                                "operator" to values[4],
                                "metadata" to values[5],
                                "created_at" to values[6],
                                "updated_at" to values[7],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    // ——— 结算冻结（021）：逐张账单写 status/settled_at/outstanding_amount/updated_at[/reason] ———
                    sql.contains("update healthcare.bills") && sql.contains("settled_at") -> {
                        val target = bills.firstOrNull { it["id"] == values.last() }
                        if (target != null) {
                            target["status"] = values[0]
                            target["settled_at"] = values[1]
                            target["outstanding_amount"] = values[2]
                            target["updated_at"] = values[3]
                            target["write_off_reason"] = if (values.size >= 6) values[4] else null
                        }
                        Future.succeededFuture(rowSet())
                    }
                    // ——— 核销：单张账单余额归零 → 已结清 ———
                    sql.contains("update healthcare.bills") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["status"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.encounters") && sql.contains("settled_at") -> {
                        val target = encounters.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["settled_at"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    // ——— encounters 行锁读 / 回读（requireEncounter / lockEncounter / getEncounter） ———
                    sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 已结算账期末日（结算区间推导） ———
                    sql.contains("max(") && sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (it["status"] == values.getOrNull(1) || it["status"] == values.getOrNull(2))
                        }
                        val maxEnd = scoped.mapNotNull { it["period_end"] as? LocalDate }.maxOrNull()
                        Future.succeededFuture(rowSet(mockRow(mapOf("max_end" to maxEnd))))
                    }
                    // ——— 021：冻结前的全部账单 id（冻结逐张写未结快照） ———
                    sql.contains("select healthcare.bills.id from healthcare.bills") -> {
                        val scoped = bills.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(mapOf("id" to it["id"])) }.toTypedArray()))
                    }
                    // ——— 押金余额（口径唯一来自 DepositService.balanceOf） ———
                    sql.contains("from healthcare.deposit_records") -> {
                        val scoped = deposits.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(
                            rowSet(
                                *scoped.map {
                                    mockRow(mapOf("type" to it["type"], "amount" to it["amount"]))
                                }.toTypedArray(),
                            ),
                        )
                    }
                    // ——— 核销目标账单：待缴费 且 余额 > 0，按账期升序 ———
                    // 034：已红冲原单不是核销目标（生产 SQL 已带 reversed_at is null，本 stub 同口径模拟）
                    sql.contains("from healthcare.bills") && sql.contains("left outer join") -> {
                        val scoped = bills
                            .filter { it["encounter_id"] == values.getOrNull(2) && it["status"] == values.getOrNull(3) }
                            .filter { it["reversed_at"] == null }
                            .mapNotNull { bill ->
                                val paid = payments
                                    .filter { it["bill_id"] == bill["id"] }
                                    .fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment["amount"] as BigDecimal) }
                                val balance = (bill["total_amount"] as BigDecimal).subtract(paid)
                                if (balance.signum() <= 0) {
                                    null
                                } else {
                                    mapOf(
                                        "id" to bill["id"],
                                        "period_start" to bill["period_start"],
                                        "period_end" to bill["period_end"],
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
                    else -> Future.succeededFuture(rowSet())
                }
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String, viaPool: Boolean) {
            preparedQueryCalls++
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
            if (viaPool) poolQueries.add(normalizedSql)
        }

        fun insertsOf(table: String): List<List<Any?>> =
            tuples.filter { it.first.contains("insert into healthcare.$table") }.map { it.second }
    }

    // ========================================================================
    //  fixture 构造
    // ========================================================================

    private fun billRow(
        id: String,
        periodStart: String,
        periodEnd: String,
        total: String,
        status: String = BillingEngine.STATUS_PENDING,
        reversedAt: String? = null,
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to id,
            "encounter_id" to "enc-1",
            "period_start" to LocalDate.parse(periodStart),
            "period_end" to LocalDate.parse(periodEnd),
            "status" to status,
            "total_amount" to BigDecimal(total),
            "reversed_at" to reversedAt?.let(OffsetDateTime::parse),
            "settled_at" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    private fun depositRow(type: String, amount: String): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to "dep-$type-$amount",
            "encounter_id" to "enc-1",
            "type" to type,
            "amount" to BigDecimal(amount),
            "operator" to "cashier-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )

    private fun paymentRow(billId: String, amount: String): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to "pay-$billId-$amount",
            "bill_id" to billId,
            "amount" to BigDecimal(amount),
            "method" to PaymentService.METHOD_CASH,
            "operator" to "cashier-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )

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

    private fun offset(
        stub: OffsetStub,
        amount: String,
        encounterId: String = "enc-1",
        operator: String = "cashier-1",
    ): JsonObject =
        DepositOffsetService()
            .offsetArrears(stub.connection, encounterId, BigDecimal(amount), operator, OffsetDateTime.now())
            .toCompletionStage().toCompletableFuture().get()

    private fun amountOf(value: Any?): BigDecimal =
        value as? BigDecimal ?: BigDecimal.valueOf((value as Number).toDouble())

    private fun metadataOf(value: Any?): JsonObject =
        value as? JsonObject ?: throw AssertionError("metadata 必须落库为 JSON 对象，实际: $value")

    // ========================================================================
    //  4. 金额为 0：不查库不写库
    // ========================================================================

    @Test
    fun `核销金额为0时不查库不写库并返回空结果`() {
        val stub = OffsetStub(
            bills = mutableListOf(billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00")),
            deposits = mutableListOf(depositRow("登记", "5000.00")),
        )
        val result = offset(stub, "0")

        assertEquals(0, stub.preparedQueryCalls, "金额为 0 必须不触发任何 preparedQuery: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
        assertEquals("enc-1", result.getString("encounter_id"))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getValue("amount") as BigDecimal))
        assertEquals(0, result.getInteger("count"))
        assertEquals(0, result.getJsonArray("allocations").size())
        assertTrue(stub.payments.isEmpty(), "金额为 0 不得写 payments")
        assertTrue(stub.insertsOf("deposit_records").isEmpty(), "金额为 0 不得写 deposit_records")
        assertTrue(
            stub.deposits.none { it["type"] == DepositOffsetService.TYPE_OFFSET },
            "金额为 0 不得产生 核销 台账记录",
        )
    }

    // ========================================================================
    //  5. 核销资格
    // ========================================================================

    // ——— 034：已红冲原单不再是核销目标 ———

    @Test
    fun `已红冲原单不再是押金核销目标`() {
        // 原单已被红冲（reversed_at 非空）且有正余额：它不应构成可核销欠费，
        // 否则操作为已经冲销的金额搬押金，并掩盖红冲事实。
        val stub = OffsetStub(
            bills = mutableListOf(
                billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00", reversedAt = "2026-09-30T10:00:00+08:00"),
            ),
            deposits = mutableListOf(depositRow("登记", "5000.00")),
        )
        val cause = causeOf(
            DepositOffsetService().offsetArrears(
                stub.connection,
                "enc-1",
                BigDecimal("1000.00"),
                "cashier-1",
                OffsetDateTime.now(),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(
            cause.message?.contains("exceeds available") == true,
            "已红冲原单不得构成可核销欠费: ${cause.message}",
        )
        assertTrue(stub.payments.isEmpty(), "不得写缴费流水")
        assertTrue(stub.insertsOf("deposit_records").isEmpty(), "不得写押金核销台账")
        val targetSql = stub.queries.first { it.contains("left outer join") }
        assertTrue(
            targetSql.contains("reversed_at is null"),
            "核销目标查询必须排除已红冲原单: $targetSql",
        )
        assertFalse(
            targetSql.contains("reversal_of is null"),
            "红字单靠余额非正天然排除，不得额外加 reversal_of 谓词: $targetSql",
        )
    }

    @Test
    fun `encounter不存在返回404且不写入`() {
        val stub = OffsetStub(encounters = mutableListOf())
        val cause = causeOf(
            DepositOffsetService().offsetArrears(
                stub.connection, "missing", BigDecimal("100"), "cashier-1", OffsetDateTime.now(),
            ),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertEquals("encounter not found: missing", cause.message)
        assertTrue(stub.payments.isEmpty())
        assertTrue(stub.deposits.isEmpty())
    }

    @Test
    fun `已收束encounter返回409且不写入`() {
        val stub = OffsetStub(
            encounters = mutableListOf(
                encounterRow(mapOf("settled_at" to OffsetDateTime.parse("2026-08-20T18:00:00+08:00"))),
            ),
            bills = mutableListOf(billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00")),
            deposits = mutableListOf(depositRow("登记", "5000.00")),
        )
        val cause = causeOf(
            DepositOffsetService().offsetArrears(
                stub.connection, "enc-1", BigDecimal("100"), "cashier-1", OffsetDateTime.now(),
            ),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("encounter billing is already settled", cause.message)
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })
    }

    // ========================================================================
    //  6. 上限 = min(押金余额, 目标账单余额合计)
    // ========================================================================

    @Test
    fun `核销额超过上限返回400且不部分执行`() {
        // 情形 1：押金余额 0（无登记）而请求 > 0 → limit = 0
        val noDeposit = OffsetStub(
            bills = mutableListOf(billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00")),
        )
        val cause1 = causeOf(
            DepositOffsetService().offsetArrears(
                noDeposit.connection, "enc-1", BigDecimal("100"), "cashier-1", OffsetDateTime.now(),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertEquals("deposit offset exceeds available: available 0, requested 100", cause1.message)
        assertTrue(noDeposit.payments.isEmpty(), "超上限不得写入 payments")
        assertTrue(noDeposit.deposits.isEmpty(), "超上限不得写入 deposit_records")

        // 情形 2：无欠费（目标账单余额合计 0）→ limit = 0
        val noArrears = OffsetStub(deposits = mutableListOf(depositRow("登记", "5000.00")))
        val cause2 = causeOf(
            DepositOffsetService().offsetArrears(
                noArrears.connection, "enc-1", BigDecimal("100"), "cashier-1", OffsetDateTime.now(),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertEquals("deposit offset exceeds available: available 0, requested 100", cause2.message)
        assertTrue(noArrears.payments.isEmpty())

        // 情形 3：欠费大于押金余额 → limit = 押金余额
        val depositBound = OffsetStub(
            bills = mutableListOf(billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00")),
            deposits = mutableListOf(depositRow("登记", "300.00")),
        )
        val cause3 = causeOf(
            DepositOffsetService().offsetArrears(
                depositBound.connection, "enc-1", BigDecimal("300.01"), "cashier-1", OffsetDateTime.now(),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause3)
        assertEquals("deposit offset exceeds available: available 300.00, requested 300.01", cause3.message)
        assertTrue(depositBound.payments.isEmpty())
        assertTrue(depositBound.insertsOf("deposit_records").isEmpty(), "超上限不得写入 deposit_records")
    }

    @Test
    fun `核销额等于上限边界必须通过`() {
        // 边界 = 押金余额（小于欠费合计）
        val stub = OffsetStub(
            bills = mutableListOf(billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00")),
            deposits = mutableListOf(depositRow("登记", "1000.00")),
        )
        val result = offset(stub, "1000")
        assertEquals(1, result.getInteger("count"))
        assertEquals(0, BigDecimal("1000.00").compareTo(result.getValue("amount") as BigDecimal))
        assertEquals(1, stub.insertsOf("payments").size)
        assertEquals(1, stub.insertsOf("deposit_records").size)
    }

    // ========================================================================
    //  7. 逐笔分配与双台账写入
    // ========================================================================

    @Test
    fun `逐笔分配双台账写入且仅全额覆盖的账单转已结清`() {
        val stub = OffsetStub(
            bills = mutableListOf(
                billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00"),
                billRow("bill-2", "2026-09-01", "2026-09-30", "500.00"),
                billRow("bill-3", "2026-10-01", "2026-10-31", "400.00"),
            ),
            deposits = mutableListOf(depositRow("登记", "1500.00")),
        )
        // bill-2 已缴 200 → 余额 300；欠费合计 1700；押金 1500；核销 1200
        // 分配：bill-1 min(1200,1000)=1000 → 余额归零转 已结清；bill-2 min(200,300)=200 → 部分；
        //       bill-3 未命中
        stub.payments.add(paymentRow("bill-2", "200.00"))

        val result = offset(stub, "1200", operator = "cashier-op-1")

        // 返回 JSON 与写入逐笔对应
        assertEquals(2, result.getInteger("count"))
        assertEquals(0, BigDecimal("1200").compareTo(result.getValue("amount") as BigDecimal))
        val allocations = result.getJsonArray("allocations").map { it as JsonObject }
        assertEquals(listOf("bill-1", "bill-2"), allocations.map { it.getString("bill_id") })
        assertEquals("2026-08-01", allocations[0].getString("period_start"))
        assertEquals("2026-08-31", allocations[0].getString("period_end"))
        assertEquals(0, BigDecimal("1000").compareTo(amountOf(allocations[0].getValue("amount"))))
        assertEquals(0, BigDecimal("200").compareTo(amountOf(allocations[1].getValue("amount"))))

        // payments：命中笔数、method = 押金、operator 取认证主体、金额与分配一致、metadata 关联
        val paymentInserts = stub.insertsOf("payments")
        assertEquals(2, paymentInserts.size, "payments 行数必须等于命中笔数")
        assertEquals(listOf("押金", "押金"), paymentInserts.map { it[3] })
        assertEquals(listOf("cashier-op-1", "cashier-op-1"), paymentInserts.map { it[4] })
        assertEquals(
            listOf("1000.00", "200.00"),
            paymentInserts.map { BigDecimal(it[2].toString()).toPlainString() },
        )
        assertEquals(listOf("bill-1", "bill-2"), paymentInserts.map { it[1] })
        for (insert in paymentInserts) {
            val metadata = metadataOf(insert[5])
            assertEquals(true, metadata.getBoolean("deposit_offset"))
            assertEquals("enc-1", metadata.getString("encounter_id"))
        }
        assertTrue(
            paymentInserts.all { (it[0] as String).length == 26 },
            "核销缴费必须生成 26 位 ULID",
        )

        // deposit_records：同样笔数、type = 核销、metadata 关联账单与缴费
        val depositInserts = stub.insertsOf("deposit_records")
        assertEquals(2, depositInserts.size, "deposit_records 行数必须等于命中笔数")
        assertEquals(listOf("核销", "核销"), depositInserts.map { it[2] })
        assertEquals(listOf("cashier-op-1", "cashier-op-1"), depositInserts.map { it[4] })
        assertEquals(listOf("enc-1", "enc-1"), depositInserts.map { it[1] })
        for ((index, insert) in depositInserts.withIndex()) {
            val metadata = metadataOf(insert[5])
            assertEquals(paymentInserts[index][1], metadata.getString("bill_id"))
            assertEquals(paymentInserts[index][0], metadata.getString("payment_id"))
            assertEquals(allocations[index].getString("period_start"), metadata.getString("period_start"))
            assertEquals(allocations[index].getString("period_end"), metadata.getString("period_end"))
        }

        // 账单状态：bill-1 全额覆盖 → 已结清；bill-2 部分 → 不更新；bill-3 未命中 → 不更新
        val billUpdates = stub.tuples.filter { it.first.contains("update healthcare.bills") }
        assertEquals(1, billUpdates.size, "只有被全额覆盖的账单才被 UPDATE: ${billUpdates.map { it.second }}")
        assertEquals("bill-1", billUpdates.single().second[2])
        assertEquals(BillingEngine.STATUS_PAID, billUpdates.single().second[0])
        assertEquals(BillingEngine.STATUS_PAID, stub.bills.first { it["id"] == "bill-1" }["status"])
        assertEquals(BillingEngine.STATUS_PENDING, stub.bills.first { it["id"] == "bill-2" }["status"])
        assertEquals(BillingEngine.STATUS_PENDING, stub.bills.first { it["id"] == "bill-3" }["status"])

        // 双台账净额：押金余额 = 1500 − 1200 = 300；账单余额合计 = 1700 − 1200 = 500
        assertEquals(0, BigDecimal("300.00").compareTo(DepositService.balanceOf(stub.deposits.map { it["type"] as String to it["amount"] as BigDecimal })))
    }

    @Test
    fun `部分核销不转已结清且只写一笔`() {
        val stub = OffsetStub(
            bills = mutableListOf(billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00")),
            deposits = mutableListOf(depositRow("登记", "1000.00")),
        )
        val result = offset(stub, "300")

        assertEquals(1, result.getInteger("count"))
        assertEquals(0, BigDecimal("300").compareTo(result.getValue("amount") as BigDecimal))
        assertEquals(1, stub.insertsOf("payments").size)
        assertEquals(1, stub.insertsOf("deposit_records").size)
        assertEquals(
            0,
            stub.tuples.count { it.first.contains("update healthcare.bills") },
            "部分核销不得改变账单状态",
        )
        assertEquals(BillingEngine.STATUS_PENDING, stub.bills.single()["status"])
    }

    @Test
    fun `已结清与余额为零的账单不作为核销目标`() {
        val stub = OffsetStub(
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-07-31", "800.00", status = BillingEngine.STATUS_PAID),
                billRow("bill-settled", "2026-06-01", "2026-06-30", "900.00", status = BillingEngine.STATUS_SETTLED),
                billRow("bill-zero", "2026-05-01", "2026-05-31", "100.00"),
                billRow("bill-1", "2026-08-01", "2026-08-31", "1000.00"),
            ),
            deposits = mutableListOf(depositRow("登记", "1000.00")),
        )
        // bill-zero 已缴满 → 余额 0，不是目标；已结清/已结算账单也不是目标
        stub.payments.add(paymentRow("bill-zero", "100.00"))

        val result = offset(stub, "1000")
        assertEquals(1, result.getInteger("count"))
        assertEquals("bill-1", result.getJsonArray("allocations").getJsonObject(0).getString("bill_id"))
    }

    // ========================================================================
    //  8. 嵌入式 HTTP 路由
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
        stub: OffsetStub,
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

    /**
     * 路由级场景：已离院 encounter，已结清账单账期覆盖到离院日之后（区间为 null，只冻结），
     * 另有一张待缴费账单作为核销目标；押金登记 5000。
     */
    private fun routeStub(): OffsetStub =
        OffsetStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-08-31", "3000.00", status = BillingEngine.STATUS_PAID),
                billRow("bill-1", "2026-09-01", "2026-09-30", "1000.00"),
            ),
            deposits = mutableListOf(depositRow("登记", "5000.00")),
        )

    @Test
    fun `POST结算带核销返回201且写入双台账且核销先于冻结`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = routeStub()
        val writeOffReason = "离院结算，家属确认不再追收"
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/billing-settlement",
                // 021 依据：收束后有未结余额必须显式提供减免原因，否则 409；
                // 本用例原意（同事务、双台账、核销先于冻结）不变，仅补上原因使收束通过。
                JsonObject().put("deposit_offset", 100).put("write_off_reason", writeOffReason),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "带核销的补结算必须 201")
                    assertNotNull(body.getString("settled_at"), "响应必须带冻结标记")

                    // 核销写入：payments(method=押金) 与 deposit_records(type=核销)
                    val paymentInserts = stub.insertsOf("payments")
                    assertEquals(1, paymentInserts.size)
                    assertEquals(PaymentService.METHOD_DEPOSIT, paymentInserts.single()[3])
                    assertEquals("cashier-route-1", paymentInserts.single()[4], "operator 必须取认证主体")
                    assertEquals("bill-1", paymentInserts.single()[1])
                    assertEquals(0, BigDecimal("100").compareTo(amountOf(paymentInserts.single()[2])))
                    assertEquals(true, metadataOf(paymentInserts.single()[5]).getBoolean("deposit_offset"))
                    assertEquals("enc-1", metadataOf(paymentInserts.single()[5]).getString("encounter_id"))

                    val depositInserts = stub.insertsOf("deposit_records")
                    assertEquals(1, depositInserts.size)
                    assertEquals(DepositOffsetService.TYPE_OFFSET, depositInserts.single()[2])
                    assertEquals("enc-1", depositInserts.single()[1])
                    assertEquals("bill-1", metadataOf(depositInserts.single()[5]).getString("bill_id"))
                    assertEquals(paymentInserts.single()[0], metadataOf(depositInserts.single()[5]).getString("payment_id"))

                    // 核销先于冻结（按 SQL 序列断言）
                    val offsetIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.payments") }
                    val freezeBillsIndex =
                        stub.queries.indexOfFirst { it.contains("update healthcare.bills") && it.contains("settled_at") }
                    val freezeEncounterIndex =
                        stub.queries.indexOfFirst { it.contains("update healthcare.encounters") && it.contains("settled_at") }
                    assertTrue(offsetIndex in 0 until freezeBillsIndex, "核销必须先于账单冻结: ${stub.queries}")
                    assertTrue(freezeBillsIndex < freezeEncounterIndex, "账单冻结先于 encounter 冻结")
                    assertTrue(
                        stub.poolQueries.isEmpty(),
                        "核销与收束必须共用事务连接，不得经 pool 执行: ${stub.poolQueries}",
                    )
                    assertEquals(1, stub.transactionCalls, "核销与收束必须只开一次事务")

                    // 021 新增落库：冻结逐张写未结快照与减免原因
                    // （绑定顺序 = status, settled_at, outstanding_amount, updated_at[, write_off_reason], id）
                    val billFreeze = stub.tuples.first {
                        it.first.contains("update healthcare.bills") &&
                            it.first.contains("settled_at") &&
                            it.second.last() == "bill-1"
                    }
                    assertEquals(
                        0,
                        BigDecimal("900").compareTo(billFreeze.second[2] as BigDecimal),
                        "未结快照 = 账单合计 1000 − 核销 100",
                    )
                    assertEquals(writeOffReason, billFreeze.second[4], "未结行必须写入 trim 后的减免原因")

                    // 冻结后的账单状态（021：未结行落快照 + 原因）
                    val frozen = stub.bills.first { it["id"] == "bill-1" }
                    assertEquals(BillingEngine.STATUS_SETTLED, frozen["status"], "冻结后账单置 已结算")
                    assertNotNull(frozen["settled_at"], "冻结后账单写 settled_at")
                    assertEquals(
                        0,
                        BigDecimal("900").compareTo(frozen["outstanding_amount"] as BigDecimal),
                        "该账单冻结后 outstanding_amount = 1000 − 100",
                    )
                    assertEquals(writeOffReason, frozen["write_off_reason"], "未结行写减免原因")
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `POST结算未认证返回401且不触发任何SQL`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = routeStub()
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/billing-settlement",
                JsonObject().put("deposit_offset", 100),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "无认证 userId 必须 401")
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
    fun `POST结算请求体含未知键返回400且不触发任何SQL`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = routeStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/billing-settlement",
                JsonObject().put("deposit_offset", 100).put("operator", "hacker"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "未知字段必须 400")
                    assertEquals("unsupported settlement keys: operator", body.getString("error"))
                    assertTrue(stub.queries.isEmpty(), "白名单校验必须先于一切 SQL: ${stub.queries}")
                    assertTrue(stub.payments.isEmpty())
                    assertTrue(stub.deposits.size == 1, "只有预置的登记记录，不得写入核销")
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `POST结算空请求体等价于不核销`(vertx: Vertx, ctx: VertxTestContext) {
        // 021 依据：存在未结余额时收束必须显式减免确认，因此本用例把 fixture 调整为
        // 「收束后无未结」（两张账单均已结清），从而空请求体仍不需要原因、走到 201；
        // 原意「空请求体 ⇒ 不核销」不变（见下方对核销写入的断言）。
        val stub = OffsetStub(
            encounters = mutableListOf(
                encounterRow(mapOf("discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"))),
            ),
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-08-31", "3000.00", status = BillingEngine.STATUS_PAID),
                billRow("bill-1", "2026-09-01", "2026-09-30", "1000.00", status = BillingEngine.STATUS_PAID),
            ),
            deposits = mutableListOf(depositRow("登记", "5000.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/billing-settlement",
                JsonObject(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "空请求体必须与不带核销等价")
                    assertNotNull(body.getString("settled_at"))
                    assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
                    assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })
                    // 021 起收束必然读取一次未结（outstandingBills 的 LEFT JOIN 只读查询），
                    // 故原「不得出现 left outer join」的断言不再成立；改为断言「未发生核销写入」。
                    assertTrue(
                        stub.queries.none { it.contains("insert into healthcare.payments") },
                        "空请求体不得写缴费/核销流水: ${stub.queries}",
                    )
                    assertTrue(
                        stub.queries.none { it.contains("insert into healthcare.deposit_records") },
                        "空请求体不得写押金核销台账: ${stub.queries}",
                    )
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }
}

// ——— mock 基础设施（顶层函数，供本测试类与嵌套 stub 共用） ———

/** encounter fixture：顶层函数以便嵌套 OffsetStub 的默认参数引用（嵌套类无外部实例）。 */
private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
    val base = mutableMapOf<String, Any?>(
        "id" to "enc-1",
        "patient_id" to "pat-1",
        "encounter_type" to "ELDERLY_CARE",
        "encounter_no" to "A20260801001",
        "department" to "三楼",
        "ward" to "301-1",
        "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
        "discharge_date" to null,
        "death_date" to null,
        "admitting_diagnosis" to "高血压",
        "discharge_diagnosis" to null,
        "attending_physician" to "赵医生",
        "status" to "DISCHARGED",
        "settled_at" to null,
        "metadata" to JsonObject(),
        "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
    )
    base.putAll(overrides)
    return base
}

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
