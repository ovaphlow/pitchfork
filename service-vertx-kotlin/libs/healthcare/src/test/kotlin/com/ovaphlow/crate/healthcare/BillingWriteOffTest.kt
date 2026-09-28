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
 * 021 结算收束「未结快照 + 显式减免确认 + 只读预览」非数据库测试
 * （mockk 桩 + 嵌入式 HTTP；不连库，默认流水线运行）。
 *
 * 覆盖验收口径：
 *   - `BillService.parseSettlementRequest`：白名单（含 amount/operator/bill_id 等未知键）、
 *     `write_off_reason` 类型/trim/长度、`deposit_offset` 沿用 020 的数值校验文案；
 *   - 区间最终账单以 `待缴费` 建立、创建时不写 `settled_at`，且能被押金核销
 *     （核销在建单之后、冻结之前，按 SQL 序列断言）；
 *   - 核销后仍有未结且未给原因 → 409（含文案）且**未进入冻结**；
 *   - 减免落库：每张账单写 `outstanding_amount`，未结行写原因、其余写 0/NULL，
 *     状态置 `已结算` + `settled_at`；
 *   - 无未结时空体 `{}` 收束成功且全部快照为 0、原因为 NULL；
 *   - 预览与执行同源（同一区间/金额算法）、`outstanding_total`、`max_offset`、
 *     `requires_write_off`，且**不产生任何写入**、可反复调用；预览资格校验与执行一致；
 *   - 汇总新增 `write_off_amount` 且既有三口径不变；`methods` 白名单仍 5 值、
 *     `method = 押金` 经缴费接口仍 400。
 *
 * 限制说明：mockk 桩不模拟 PostgreSQL 事务回滚，409 路径在桩上仍能看到本次建单/核销写入；
 * 真实「409 后零残留」与并发收束只能由后置隔离库集成测试覆盖，本文件不声称已覆盖。
 */
@ExtendWith(VertxExtension::class)
class BillingWriteOffTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * bills/payments/deposits 随 insert/update 演进，供 SQL 序列与落库值断言。
     */
    private class DatabaseStub(
        var encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var feeItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var assessments: MutableList<Map<String, Any?>> = mutableListOf(),
        var mealsByEncounter: MutableMap<String, List<String>> = mutableMapOf(),
        var bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var billItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var payments: MutableList<Map<String, Any?>> = mutableListOf(),
        var deposits: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val queries = mutableListOf<String>()
        /** 仅经 pool（非事务连接）下发的 SQL：用于断言收束全程同连接。 */
        val poolQueries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

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
                    // ——— 汇总口径（必须先于 bills/payments 通用分支） ———
                    // 减免合计：只统计 WHERE 状态（绑定值里唯一的字符串）的账单
                    sql.contains("write_off_amount") -> {
                        val status = values.filterIsInstance<String>().firstOrNull() ?: BillingEngine.STATUS_SETTLED
                        val total = bills
                            .filter { it["status"] == status }
                            .fold(BigDecimal.ZERO) { acc, bill -> acc.add(bill["outstanding_amount"] as BigDecimal) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("write_off_amount" to total))))
                    }
                    sql.contains("arrears_amount") -> {
                        val total = pendingBalances().fold(BigDecimal.ZERO) { acc, b -> acc.add(b) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("arrears_amount" to total))))
                    }
                    // ——— 未结账单：待缴费 且 余额 > 0（与 DepositOffsetService 同口径） ———
                    sql.contains("from healthcare.bills") && sql.contains("left outer join") -> {
                        val scoped = bills
                            .filter { it["encounter_id"] == values.getOrNull(2) && it["status"] == values.getOrNull(3) }
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
                    sql.contains("due_amount") -> {
                        val total = bills.fold(BigDecimal.ZERO) { acc, b -> acc.add(b["total_amount"] as BigDecimal) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("due_amount" to total))))
                    }
                    sql.contains("paid_amount") -> {
                        val total = payments.fold(BigDecimal.ZERO) { acc, p -> acc.add(p["amount"] as BigDecimal) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("paid_amount" to total))))
                    }
                    // ——— 区间最终账单以 待缴费 建立（insert 不含 settled_at） ———
                    sql.contains("insert into healthcare.bills") -> {
                        bills.add(
                            mutableMapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "period_start" to values[2],
                                "period_end" to values[3],
                                "status" to values[4],
                                "total_amount" to values[5],
                                "settled_at" to null,
                                "outstanding_amount" to BigDecimal.ZERO,
                                "write_off_reason" to null,
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
                    // ——— 核销双台账 ———
                    sql.contains("insert into healthcare.payments") -> {
                        payments.add(
                            mapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "amount" to values[2],
                                "method" to values[3],
                                "operator" to values[4],
                                "metadata" to values.getOrNull(5),
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("insert into healthcare.deposit_records") -> {
                        deposits.add(
                            mapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "type" to values[2],
                                "amount" to values[3],
                                "operator" to values[4],
                                "metadata" to values.getOrNull(5),
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    // ——— encounter 冻结 ———
                    sql.contains("update healthcare.encounters") && sql.contains("settled_at") -> {
                        val target = encounters.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["settled_at"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    // ——— 冻结逐张 UPDATE：status/settled_at/outstanding_amount/updated_at[/reason] ———
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
                    sql.contains("update healthcare.bills") && sql.contains("total_amount") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["total_amount"] = values[0]
                            target["updated_at"] = values[1]
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
                    // ——— 已结算/已结清账期末日 ———
                    sql.contains("max(") && sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (it["status"] == values.getOrNull(1) || it["status"] == values.getOrNull(2))
                        }
                        val maxEnd = scoped.mapNotNull { it["period_end"] as? LocalDate }.maxOrNull()
                        Future.succeededFuture(rowSet(mockRow(mapOf("max_end" to maxEnd))))
                    }
                    // ——— 同账期账单是否存在 ———
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") -> {
                        val count = bills.count {
                            it["encounter_id"] == values.getOrNull(0) &&
                                it["period_start"] == values.getOrNull(1) &&
                                it["period_end"] == values.getOrNull(2)
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    // ——— 冻结前的全部账单 id ———
                    sql.contains("select healthcare.bills.id from healthcare.bills") -> {
                        val scoped = bills.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(mapOf("id" to it["id"])) }.toTypedArray()))
                    }
                    // ——— encounter 行锁读/回读 ———
                    sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 费用字典 / 护理评估 / 就餐执行 ———
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
                    // ——— 押金台账余额（口径唯一来自 DepositService.balanceOf） ———
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

        private fun record(sql: String, viaPool: Boolean) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
            if (viaPool) poolQueries.add(normalizedSql)
        }

        /** 待缴费且余额 > 0 的账单余额（汇总欠费口径）。 */
        private fun pendingBalances(): List<BigDecimal> =
            bills.mapNotNull { bill ->
                if (bill["status"] != BillingEngine.STATUS_PENDING) return@mapNotNull null
                val paid = payments
                    .filter { it["bill_id"] == bill["id"] }
                    .fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment["amount"] as BigDecimal) }
                val balance = (bill["total_amount"] as BigDecimal).subtract(paid)
                if (balance.signum() <= 0) null else balance
            }
    }

    // ========================================================================
    //  fixture 构造
    // ========================================================================

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
            "death_cause" to null,
            "status" to "DISCHARGED",
            "settled_at" to null,
            "metadata" to JsonObject(),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun dischargedEncounter(): MutableMap<String, Any?> =
        encounterRow(mapOf("discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00")))

    private fun billRow(
        id: String,
        periodStart: String,
        periodEnd: String,
        total: String,
        status: String = BillingEngine.STATUS_PENDING,
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to id,
            "encounter_id" to "enc-1",
            "period_start" to LocalDate.parse(periodStart),
            "period_end" to LocalDate.parse(periodEnd),
            "status" to status,
            "total_amount" to BigDecimal(total),
            "settled_at" to null,
            "outstanding_amount" to BigDecimal.ZERO,
            "write_off_reason" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    /** 费用字典：仅床位费 100/天（无护理评估、无就餐执行 → 区间账单只含床位费）。 */
    private fun bedOnlyFeeItem(): Map<String, Any?> =
        mapOf(
            "id" to "fee-bed",
            "category" to "床位费",
            "name" to "标准床位",
            "unit_price" to BigDecimal("100"),
            "status" to "启用",
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
        )

    /**
     * 主线 fixture：已离院（08-20）；已结清账期 07-01..07-31（区间起 08-01）、
     * 待缴费账单 06-01..06-30 = 1000；押金登记 5000；床位 100/天 → 区间账单 08-01..08-20 = 2000。
     */
    private fun writeOffStub(
        encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(dischargedEncounter()),
        feeItems: MutableList<Map<String, Any?>> = mutableListOf(bedOnlyFeeItem()),
        bills: MutableList<MutableMap<String, Any?>> = mutableListOf(
            billRow("bill-paid", "2026-07-01", "2026-07-31", "500.00", status = BillingEngine.STATUS_PAID),
            billRow("bill-pending", "2026-06-01", "2026-06-30", "1000.00"),
        ),
        payments: MutableList<Map<String, Any?>> = mutableListOf(paymentRow("bill-paid", "500.00")),
        deposits: MutableList<Map<String, Any?>> = mutableListOf(depositRow("登记", "5000.00")),
    ): DatabaseStub =
        DatabaseStub(
            encounters = encounters,
            feeItems = feeItems,
            bills = bills,
            payments = payments,
            deposits = deposits,
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

    private fun await(future: Future<JsonObject>): JsonObject =
        future.toCompletionStage().toCompletableFuture().get()

    private fun amount(value: Any?): BigDecimal =
        value as? BigDecimal ?: BigDecimal.valueOf((value as Number).toDouble())

    private fun freezeTuples(stub: DatabaseStub): List<List<Any?>> =
        stub.tuples
            .filter { it.first.contains("update healthcare.bills") && it.first.contains("settled_at") }
            .map { it.second }

    // ========================================================================
    //  1. 请求体解析（parseSettlementRequest）
    // ========================================================================

    @Test
    fun `结算请求体白名单拒绝未知键`() {
        val single = try {
            BillService.parseSettlementRequest(JsonObject().put("amount", 100))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("unsupported settlement keys: amount", single?.message)

        val multiple = try {
            BillService.parseSettlementRequest(
                JsonObject().put("deposit_offset", 100).put("operator", "hacker").put("bill_id", "bill-1"),
            )
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals(
            "unsupported settlement keys: bill_id, operator",
            multiple?.message,
            "未知键必须按排序后拼接上报",
        )
    }

    @Test
    fun `结算请求体省略两个键等价于不核销且原因未提供`() {
        val empty = BillService.parseSettlementRequest(JsonObject())
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.depositOffset), "省略 deposit_offset = 不核销")
        assertNull(empty.writeOffReason)

        val onlyOffset = BillService.parseSettlementRequest(JsonObject().put("deposit_offset", 3000))
        assertEquals(0, BigDecimal("3000").compareTo(onlyOffset.depositOffset))
        assertNull(onlyOffset.writeOffReason)

        val blankReason = BillService.parseSettlementRequest(JsonObject().put("write_off_reason", "   "))
        assertEquals(0, BigDecimal.ZERO.compareTo(blankReason.depositOffset))
        assertNull(blankReason.writeOffReason, "trim 后为空视为未提供")
    }

    @Test
    fun `write_off_reason必须是字符串且trim后不超过500字符`() {
        val notString = try {
            BillService.parseSettlementRequest(JsonObject().put("write_off_reason", 123))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("write_off_reason must be a string", notString?.message)

        val tooLong = try {
            BillService.parseSettlementRequest(JsonObject().put("write_off_reason", "长".repeat(501)))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("write_off_reason must not exceed 500 characters", tooLong?.message)

        val maxLength = BillService.parseSettlementRequest(JsonObject().put("write_off_reason", "长".repeat(500)))
        assertEquals(500, maxLength.writeOffReason?.length, "500 字符边界必须通过")

        val trimmed = BillService.parseSettlementRequest(
            JsonObject().put("write_off_reason", "  离院结算，家属书面确认不再追收  "),
        )
        assertEquals("离院结算，家属书面确认不再追收", trimmed.writeOffReason, "落库必须为 trim 后的原文")
    }

    @Test
    fun `deposit_offset非法值沿用020文案`() {
        val negative = try {
            BillService.parseSettlementRequest(JsonObject().put("deposit_offset", -1))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("deposit_offset must not be negative", negative?.message)

        val notNumber = try {
            BillService.parseSettlementRequest(JsonObject().put("deposit_offset", "100"))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("deposit_offset must be a number", notNumber?.message)

        val tooManyDecimals = try {
            BillService.parseSettlementRequest(JsonObject().put("deposit_offset", 1.234))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("deposit_offset must have at most 2 decimal places", tooManyDecimals?.message)

        val tooLarge = try {
            BillService.parseSettlementRequest(JsonObject().put("deposit_offset", BigDecimal("10000000000.00")))
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertEquals("deposit_offset must not exceed 9999999999.99", tooLarge?.message)
    }

    // ========================================================================
    //  2. 最终账单以 待缴费 建立、先建后核销、再冻结
    // ========================================================================

    @Test
    fun `区间最终账单以待缴费建立且在核销之前冻结之前`() {
        val stub = writeOffStub()
        val encounter = await(
            HealthcareService(stub.pool).settleEncounterBilling(
                "enc-1",
                JsonObject().put("deposit_offset", 500).put("write_off_reason", "离院结算，家属书面确认不再追收"),
                "cashier-route-1",
            ),
        )
        assertNotNull(encounter.getString("settled_at"), "收束必须写 encounters.settled_at")

        // 最终账单：无已结算账期时区间起 = 入住日 08-01，止 = 离院日 08-20；床位 100 × 20 = 2000
        val finalInsert = stub.tuples.first { it.first.contains("insert into healthcare.bills") }
        assertEquals(LocalDate.parse("2026-08-01"), finalInsert.second[2])
        assertEquals(LocalDate.parse("2026-08-20"), finalInsert.second[3])
        assertEquals(BillingEngine.STATUS_PENDING, finalInsert.second[4], "021：最终账单以 待缴费 建立")
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(finalInsert.second[5])), "床位 20 天 × 100")
        assertFalse(finalInsert.first.contains("settled_at"), "021：创建区间最终账单时不写 settled_at")

        // SQL 序列：建最终账单 → 核销 → 冻结（021 次序；使最终账期也能被核销）
        val insertBillIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.bills") }
        val offsetIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.payments") }
        val freezeIndex = stub.queries.indexOfFirst {
            it.contains("update healthcare.bills") && it.contains("settled_at")
        }
        assertTrue(insertBillIndex in 0 until offsetIndex, "核销必须在最终账单之后: ${stub.queries}")
        assertTrue(offsetIndex in 0 until freezeIndex, "核销必须在冻结之前: ${stub.queries}")
        assertEquals(1, stub.transactionCalls, "建单、核销、判定、冻结必须同一事务")
        assertTrue(stub.poolQueries.isEmpty(), "收束不得经 pool 执行 SQL: ${stub.poolQueries}")

        // 核销 500 命中最早账期的待缴费账单（bill-pending 06-01..06-30）
        val paymentInsert = stub.tuples.first { it.first.contains("insert into healthcare.payments") }
        assertEquals("bill-pending", paymentInsert.second[1])
        assertEquals(PaymentService.METHOD_DEPOSIT, paymentInsert.second[3])
        assertEquals(0, BigDecimal("500").compareTo(amount(paymentInsert.second[2])))
    }

    // ========================================================================
    //  3. 未结判定：无原因 → 409 且不冻结
    // ========================================================================

    @Test
    fun `核销后仍有未结且未给原因返回409且不进入冻结`() {
        val stub = writeOffStub()
        val cause = causeOf(
            HealthcareService(stub.pool).settleEncounterBilling(
                "enc-1",
                JsonObject().put("deposit_offset", 500),
                "cashier-route-1",
            ),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(
            cause.message?.contains("unsettled bills require explicit write-off") == true,
            "got: ${cause.message}",
        )
        assertEquals(
            "unsettled bills require explicit write-off: outstanding 2500.00",
            cause.message,
            "未结合计 = bill-pending 余额 500 + 最终账单 2000",
        )

        // 未进入冻结：账单状态与 encounter 冻结标记都不写
        assertTrue(freezeTuples(stub).isEmpty(), "409 不得冻结账单: ${stub.queries}")
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.encounters") && it.first.contains("settled_at") },
            "409 不得写 encounters.settled_at",
        )
        assertNull(stub.encounters.single()["settled_at"])
        assertTrue(stub.bills.none { it["status"] == BillingEngine.STATUS_SETTLED }, "409 不得把账单置 已结算")
    }

    @Test
    fun `空体且存在未结返回409并带上全部未结合计`() {
        val stub = writeOffStub()
        val cause = causeOf(
            HealthcareService(stub.pool).settleEncounterBilling("enc-1", JsonObject(), "cashier-route-1"),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals(
            "unsettled bills require explicit write-off: outstanding 3000.00",
            cause.message,
            "未结合计 = bill-pending 1000 + 最终账单 2000（未核销）",
        )
        assertTrue(freezeTuples(stub).isEmpty())
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") }, "空体不得核销")
    }

    // ========================================================================
    //  4. 减免落库：逐张快照与原因
    // ========================================================================

    @Test
    fun `减免原因写入未结账单而已结清账单快照为零且原因为空`() {
        val stub = writeOffStub()
        val reason = "离院结算，家属书面确认不再追收"
        await(
            HealthcareService(stub.pool).settleEncounterBilling(
                "enc-1",
                JsonObject().put("deposit_offset", 500).put("write_off_reason", reason),
                "cashier-route-1",
            ),
        )

        // 每张账单都写了未结快照；未结行写原因，已结清行写 0/NULL；状态 已结算 + settled_at
        assertEquals(3, stub.bills.size, "2 张既有账单 + 1 张区间最终账单")
        assertEquals(3, freezeTuples(stub).size, "冻结必须逐张写快照")
        for (bill in stub.bills) {
            assertEquals(BillingEngine.STATUS_SETTLED, bill["status"], "冻结后全部账单 已结算")
            assertNotNull(bill["settled_at"], "冻结后全部账单写 settled_at")
            assertNotNull(bill["outstanding_amount"], "冻结必须写 outstanding_amount")
        }
        val paidBill = stub.bills.first { it["id"] == "bill-paid" }
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(paidBill["outstanding_amount"])), "已结清行快照为 0")
        assertNull(paidBill["write_off_reason"], "已结清行原因为 NULL")
        val pendingBill = stub.bills.first { it["id"] == "bill-pending" }
        assertEquals(0, BigDecimal("500.00").compareTo(amount(pendingBill["outstanding_amount"])), "1000 − 核销 500")
        assertEquals(reason, pendingBill["write_off_reason"])
        val finalBill = stub.bills.first { it["id"] != "bill-paid" && it["id"] != "bill-pending" }
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(finalBill["outstanding_amount"])))
        assertEquals(reason, finalBill["write_off_reason"])
    }

    @Test
    fun `无未结时空体收束成功且全部快照为零原因为空`() {
        val stub = writeOffStub(
            feeItems = mutableListOf(),
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-07-31", "500.00", status = BillingEngine.STATUS_PAID),
            ),
        )
        val encounter = await(
            HealthcareService(stub.pool).settleEncounterBilling("enc-1", JsonObject(), "cashier-route-1"),
        )
        assertNotNull(encounter.getString("settled_at"))
        assertEquals(2, stub.bills.size, "0 元封口账单仍必须生成")
        for (bill in stub.bills) {
            assertEquals(BillingEngine.STATUS_SETTLED, bill["status"])
            assertEquals(0, BigDecimal.ZERO.compareTo(amount(bill["outstanding_amount"])))
            assertNull(bill["write_off_reason"], "无未结时不得写减免原因")
        }
        // 无原因时按 NULL 落库（不做 null 绑定参数）
        assertTrue(
            stub.queries.any { it.contains("update healthcare.bills") && it.contains("cast(null as varchar)") },
            "write_off_reason 必须以显式 NULL 写入: ${stub.queries}",
        )
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") }, "空体不得核销")
    }

    // ========================================================================
    //  5. 预览（只读，与执行同源）
    // ========================================================================

    @Test
    fun `预览返回区间金额押金余额与最大可核销额且不写任何行`() {
        val stub = writeOffStub(
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-07-31", "500.00", status = BillingEngine.STATUS_PAID),
            ),
            payments = mutableListOf(paymentRow("bill-paid", "500.00")),
        )
        val preview = await(HealthcareService(stub.pool).previewEncounterBilling("enc-1"))

        val period = preview.getJsonObject("settlement_period")
        assertNotNull(period)
        assertEquals("2026-08-01", period.getString("start"))
        assertEquals("2026-08-20", period.getString("end"))
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(preview.getValue("final_bill_total"))))
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(preview.getValue("pending_balance"))))
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(preview.getValue("outstanding_total"))))
        assertEquals(0, BigDecimal("5000.00").compareTo(amount(preview.getValue("deposit_balance"))))
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(preview.getValue("max_offset"))), "min(5000, 2000)")
        assertEquals(false, preview.getBoolean("requires_write_off"), "押金足够覆盖全部未结")

        // 只读：不产生任何 insert/update，且可反复调用得到同一结果
        assertTrue(
            stub.tuples.none { it.first.contains("insert into ") || it.first.contains("update ") },
            "预览不得产生任何写入: ${stub.queries}",
        )
        val second = await(HealthcareService(stub.pool).previewEncounterBilling("enc-1"))
        assertEquals(preview.encode(), second.encode(), "预览可反复调用且结果一致")
        assertNull(stub.bills.single()["settled_at"], "预览不得冻结账单")
    }

    @Test
    fun `预览区间为null或账期已存在时最终账单金额为零`() {
        // 情形 1：既有账单账期与解析区间完全一致 → 不生成最终账单
        val exact = writeOffStub(
            bills = mutableListOf(
                billRow("bill-same", "2026-08-01", "2026-08-20", "2000.00"),
            ),
        )
        val exactPreview = await(HealthcareService(exact.pool).previewEncounterBilling("enc-1"))
        assertNotNull(exactPreview.getJsonObject("settlement_period"), "区间仍照实回显")
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(exactPreview.getValue("final_bill_total"))), "同账期已存在 → 0")
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(exactPreview.getValue("pending_balance"))))
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(exactPreview.getValue("outstanding_total"))))
        assertTrue(exact.tuples.none { it.first.contains("insert into healthcare.bills") }, "预览不得建单")

        // 情形 2：已结清账期覆盖到离院日之后 → 区间起 > 区间止 → 区间为 null
        val noInterval = writeOffStub(
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-08-31", "3000.00", status = BillingEngine.STATUS_PAID),
                billRow("bill-pending", "2026-07-01", "2026-08-31", "1500.00"),
            ),
            payments = mutableListOf(paymentRow("bill-paid", "3000.00")),
        )
        val nullPreview = await(HealthcareService(noInterval.pool).previewEncounterBilling("enc-1"))
        assertNull(nullPreview.getJsonObject("settlement_period"), "区间起 > 区间止 → settlement_period = null")
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(nullPreview.getValue("final_bill_total"))))
        assertEquals(0, BigDecimal("1500.00").compareTo(amount(nullPreview.getValue("pending_balance"))))
        assertEquals(0, BigDecimal("1500.00").compareTo(amount(nullPreview.getValue("outstanding_total"))))
    }

    @Test
    fun `预览requires_write_off与max_offset按押金余额取值`() {
        val stub = writeOffStub(
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-07-31", "500.00", status = BillingEngine.STATUS_PAID),
            ),
            payments = mutableListOf(paymentRow("bill-paid", "500.00")),
            deposits = mutableListOf(depositRow("登记", "500.00")),
        )
        val preview = await(HealthcareService(stub.pool).previewEncounterBilling("enc-1"))
        assertEquals(0, BigDecimal("500.00").compareTo(amount(preview.getValue("deposit_balance"))))
        assertEquals(0, BigDecimal("500.00").compareTo(amount(preview.getValue("max_offset"))), "min(500, 2000)")
        assertEquals(true, preview.getBoolean("requires_write_off"), "2000 > 500 → 需显式减免")
        assertEquals(
            0,
            BigDecimal("1500.00").compareTo(
                amount(preview.getValue("outstanding_total")).subtract(amount(preview.getValue("max_offset"))),
            ),
            "核销后仍未结 1500",
        )
    }

    @Test
    fun `预览资格校验与执行一致`() {
        // 非养老入住 → 400
        val notElderly = writeOffStub(
            encounters = mutableListOf(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))),
        )
        val notElderlyCause = causeOf(HealthcareService(notElderly.pool).previewEncounterBilling("enc-1"))
        assertInstanceOf(IllegalArgumentException::class.java, notElderlyCause)
        assertEquals("encounter is not an elderly admission", notElderlyCause.message)

        // encounter 不存在 → 404
        val missing = writeOffStub(encounters = mutableListOf())
        val missingCause = causeOf(HealthcareService(missing.pool).previewEncounterBilling("enc-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, missingCause)

        // 未离院/去世 → 409
        val active = writeOffStub(
            encounters = mutableListOf(
                encounterRow(mapOf("status" to "ACTIVE", "discharge_date" to null)),
            ),
        )
        val activeCause = causeOf(HealthcareService(active.pool).previewEncounterBilling("enc-1"))
        assertInstanceOf(ConflictException::class.java, activeCause)
        assertTrue(activeCause.message?.contains("not discharged or deceased") == true)

        // 已收束 → 409
        val settled = writeOffStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                        "settled_at" to OffsetDateTime.parse("2026-08-20T18:00:00+08:00"),
                    ),
                ),
            ),
        )
        val settledCause = causeOf(HealthcareService(settled.pool).previewEncounterBilling("enc-1"))
        assertInstanceOf(ConflictException::class.java, settledCause)
        assertEquals("encounter billing is already settled", settledCause.message)

        // 资格校验失败同样不得产生写入
        for (stub in listOf(notElderly, active, settled)) {
            assertTrue(stub.tuples.none { it.first.contains("insert into ") || it.first.contains("update ") })
        }
    }

    // ========================================================================
    //  6. 汇总口径
    // ========================================================================

    @Test
    fun `汇总新增减免金额且既有三口径不变`() {
        val stub = DatabaseStub(
            bills = mutableListOf(
                billRow("bill-pending", "2026-08-01", "2026-08-31", "1000.00"),
                billRow("bill-paid", "2026-07-01", "2026-07-31", "500.00", status = BillingEngine.STATUS_PAID),
                billRow("bill-settled", "2026-06-01", "2026-06-30", "2000.00", status = BillingEngine.STATUS_SETTLED)
                    .also { it["outstanding_amount"] = BigDecimal("800.00") },
                billRow("bill-settled-clear", "2026-05-01", "2026-05-31", "300.00", status = BillingEngine.STATUS_SETTLED),
            ),
            payments = mutableListOf(
                paymentRow("bill-paid", "500.00"),
                paymentRow("bill-settled", "1200.00"),
                paymentRow("bill-settled-clear", "300.00"),
            ),
        )
        val summary = await(PaymentService(stub.pool).summary())

        // 既有三口径不变：应缴 = Σ账单合计 = 3800；已缴 = Σ缴费金额 = 2000；
        // 欠费 = Σ待缴费账单余额 = 1000（仅 待缴费 计入欠费）
        assertEquals(0, BigDecimal("3800.00").compareTo(amount(summary.getValue("due_amount"))))
        assertEquals(0, BigDecimal("2000.00").compareTo(amount(summary.getValue("paid_amount"))))
        assertEquals(0, BigDecimal("1000.00").compareTo(amount(summary.getValue("arrears_amount"))))
        // 减免 = Σ(已结算账单 outstanding_amount) = 800 + 0，单独成项、不并入欠费
        assertEquals(0, BigDecimal("800.00").compareTo(amount(summary.getValue("write_off_amount"))))
        assertTrue(
            stub.queries.any { it.contains("write_off_amount") && it.contains("outstanding_amount") },
            "减免必须按已结算账单的 outstanding_amount 聚合: ${stub.queries}",
        )
        // 口径关系（含减免时不并入欠费）：应缴 − 已缴 = 欠费 + 减免
        assertEquals(
            0,
            amount(summary.getValue("due_amount"))
                .subtract(amount(summary.getValue("paid_amount")))
                .compareTo(
                    amount(summary.getValue("arrears_amount")).add(amount(summary.getValue("write_off_amount"))),
                ),
            "减免单独成项：应缴 − 已缴 = 欠费 + 减免",
        )
    }

    // ========================================================================
    //  7. 回归：核销白名单不受影响
    // ========================================================================

    @Test
    fun `methods白名单仍为5值且押金不可经缴费接口提交`() {
        assertEquals(5, PaymentService.methods.size, "客户端可提交缴费方式白名单必须保持 5 值")
        assertFalse(PaymentService.METHOD_DEPOSIT in PaymentService.methods, "押金只由结算核销写入")

        val stub = DatabaseStub(bills = mutableListOf(billRow("bill-pending", "2026-08-01", "2026-08-31", "1000.00")))
        val cause = causeOf(
            PaymentService(stub.pool).createPayment(
                "bill-pending",
                JsonObject().put("amount", 100).put("method", PaymentService.METHOD_DEPOSIT),
                "cashier-1",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("method must be one of") == true, "got: ${cause.message}")
        assertTrue(stub.tuples.isEmpty(), "白名单校验必须先于一切 SQL")
    }

    // ========================================================================
    //  8. 只读预览路由
    // ========================================================================

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req -> req.send() }
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
    fun `预览路由返回200且不被泛型encounter读路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = writeOffStub(
            bills = mutableListOf(
                billRow("bill-paid", "2026-07-01", "2026-07-31", "500.00", status = BillingEngine.STATUS_PAID),
            ),
            payments = mutableListOf(paymentRow("bill-paid", "500.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/billing-settlement/preview")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status, "预览必须 200")
                        // 响应字段齐全（前端按此对接）
                        assertNotNull(body.getJsonObject("settlement_period"))
                        assertNotNull(body.getValue("final_bill_total"))
                        assertNotNull(body.getValue("pending_balance"))
                        assertNotNull(body.getValue("outstanding_total"))
                        assertNotNull(body.getValue("deposit_balance"))
                        assertNotNull(body.getValue("max_offset"))
                        assertNotNull(body.getValue("requires_write_off"))
                        // 未被泛型 /encounters/:id 读路由吞掉：预览响应是预览形状，不是 encounter 形状
                        assertFalse(body.containsKey("encounter_type"), "不得命中泛型 encounter 读路由: $body")
                        assertFalse(body.containsKey("patient_id"), "不得命中泛型 encounter 读路由: $body")
                        assertFalse(body.containsKey("admit_date"), "不得命中泛型 encounter 读路由: $body")
                        // 只读：路由调用不产生任何写入
                        assertTrue(
                            stub.tuples.none { it.first.contains("insert into ") || it.first.contains("update ") },
                            "预览路由不得产生写入: ${stub.queries}",
                        )
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `预览路由未认证返回401且不触发SQL`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = writeOffStub()
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/billing-settlement/preview")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status, "无认证 userId 预览必须 401")
                        assertNotNull(body.getString("error"))
                        assertTrue(stub.queries.isEmpty(), "未认证不得触发任何 SQL: ${stub.queries}")
                        assertEquals(0, stub.transactionCalls)
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }
}

// ——— mock 基础设施（顶层函数，本文件私有） ———

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
