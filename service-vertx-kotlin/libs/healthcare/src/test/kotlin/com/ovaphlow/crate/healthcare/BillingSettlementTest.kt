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
 * 离院/去世结算收束（SettlementService 集成 + 冻结守卫 + 路由）非数据库测试
 * （mockk + 嵌入式 HTTP，参照 DepositServiceTest 模式，默认流水线运行）。
 * 覆盖验收口径：
 *   - dischargeEncounter/deathEncounter 同事务（同一 withTransaction 连接）依次执行：
 *     区间最终账单生成（明细 = 残段自动计费，无计费项则 0 元封口）+ 全部 bills 置 已结算
 *     并写 settled_at；SQL 序列与区间天数覆盖正确（上期末日+1 至离院/去世日，闭区间）
 *   - 冻结后 POST /encounters/:id/bills、/bills/:id/items、/bills/:id/payments 均 409
 *   - POST /encounters/:id/billing-settlement：已离院/去世未结算 → 201 生成区间账单并冻结；
 *     已全部结算 → 409；未离院/去世 → 409；未认证 → 401
 *   - 边界：区间起 > 区间止不生成；最终区间与既有账单完全一致不重复生成；
 *     已结算账单账期覆盖收束日之后仅冻结
 *   - 回归：未冻结的既有行为不变（已离院未结算仍可生成账单并裁剪到离院日、仍可缴费）
 */
@ExtendWith(VertxExtension::class)
class BillingSettlementTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * bills/billItems/encounters 状态随 insert/update 演进，供同事务 SQL 序列与
     * 区间天数覆盖断言。
     */
    private class DatabaseStub(
        encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var periods: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var orders: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var patients: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var feeItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var assessments: MutableList<Map<String, Any?>> = mutableListOf(),
        var mealsByEncounter: MutableMap<String, List<String>> = mutableMapOf(),
        var bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var billItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var payments: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val encounters: MutableList<MutableMap<String, Any?>> = encounters
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
                    // ——— encounters ———
                    sql.contains("update healthcare.encounters") && sql.contains("settled_at") -> {
                        val target = encounters.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["settled_at"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.encounters") && sql.contains("death_date") -> {
                        val target = encounters.firstOrNull { it["id"] == values.last() }
                        if (target != null) {
                            target["death_date"] = values[0]
                            target["status"] = values[1]
                            target["updated_at"] = values[2]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.encounters") -> {
                        val target = encounters.firstOrNull { it["id"] == values.getOrNull(4) }
                        if (target != null) {
                            target["discharge_date"] = values[0]
                            target["discharge_diagnosis"] = values[1]
                            target["status"] = values[2]
                            target["updated_at"] = values[3]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("select 1") && sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter {
                            it["patient_id"] == values.getOrNull(0) &&
                                it["encounter_type"] == values.getOrNull(1) &&
                                it["status"] == values.getOrNull(2) &&
                                it["id"] != values.getOrNull(3)
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— patients ———
                    sql.contains("update healthcare.patients") -> Future.succeededFuture(rowSet())
                    sql.contains("from healthcare.patients") -> {
                        val scoped = patients.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— medical orders（收束终止医嘱；默认无活动医嘱） ———
                    sql.contains("from healthcare.medical_orders") -> {
                        val scoped = orders.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— nursing 照护周期 ———
                    sql.contains("update nursing.nursing_service_periods") -> Future.succeededFuture(rowSet())
                    sql.contains("from nursing.nursing_task_executions") -> Future.succeededFuture(rowSet())
                    sql.contains("from nursing.nursing_service_periods") -> {
                        val scoped = if (sql.contains("where id")) {
                            periods.filter { it["id"] == values.getOrNull(0) }
                        } else {
                            periods.filter { it["encounter_id"] == values.getOrNull(0) }
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 费用字典 ———
                    sql.contains("from healthcare.fee_items") && sql.contains("status = $") ->
                        Future.succeededFuture(
                            rowSet(*feeItems.filter { it["status"] == values.getOrNull(0) }.map { mockRow(it) }.toTypedArray()),
                        )
                    sql.contains("from healthcare.fee_items") ->
                        Future.succeededFuture(
                            feeItems.firstOrNull { it["id"] == values.getOrNull(0) }?.let { rowSet(mockRow(it)) } ?: rowSet(),
                        )
                    // ——— 护理评估 ———
                    sql.contains("from nursing.nursing_assessments") -> {
                        val scoped = assessments.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (values.getOrNull(1) == null || !(it["assess_date"] as LocalDate).isAfter(values[1] as LocalDate))
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 就餐执行 ———
                    sql.contains("from dining.dining_meal_executions") -> {
                        val statuses = mealsByEncounter[values.getOrNull(0)] ?: emptyList()
                        Future.succeededFuture(rowSet(*statuses.map { mockRow(mapOf("status" to it)) }.toTypedArray()))
                    }
                    // ——— bills ———
                    sql.contains("insert into healthcare.bills") && sql.contains("settled_at") -> {
                        bills.add(
                            mutableMapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "period_start" to values[2],
                                "period_end" to values[3],
                                "status" to values[4],
                                "total_amount" to values[5],
                                "settled_at" to values[6],
                                "created_at" to values[7],
                                "updated_at" to values[8],
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
                                "settled_at" to null,
                                "created_at" to values[6],
                                "updated_at" to values[7],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") && sql.contains("settled_at") -> {
                        val scoped = bills.filter { it["encounter_id"] == values.getOrNull(3) }
                        for (bill in scoped) {
                            bill["status"] = values[0]
                            bill["settled_at"] = values[1]
                            bill["updated_at"] = values[2]
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
                    sql.contains("update healthcare.bills") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["status"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("max(") && sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (it["status"] == values.getOrNull(1) || it["status"] == values.getOrNull(2))
                        }
                        val maxEnd = scoped.mapNotNull { it["period_end"] as? LocalDate }.maxOrNull()
                        Future.succeededFuture(rowSet(mockRow(mapOf("max_end" to maxEnd))))
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
                    sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— bill items ———
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
                    // ——— payments ———
                    sql.contains("insert into healthcare.payments") -> {
                        payments.add(
                            mapOf(
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
    }

    // ——— fixture 构造 ———

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
            "admitting_diagnosis" to "高血压",
            "discharge_diagnosis" to null,
            "attending_physician" to "赵医生",
            "status" to "ACTIVE",
            "settled_at" to null,
            "metadata" to JsonObject(),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun periodRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "period-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.parse("2026-08-01"),
            "end_date" to null,
            "coordinator" to null,
            "encounter_id" to "enc-1",
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
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
            "settled_at" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    /** 标准计费环境：床位 100/天、护理 中度依赖 80/天、伙食 30/餐、评估 08-01 中度依赖、餐次 正常×2+部分×1。 */
    private fun settlementStub(
        encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(encounterRow()),
        bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
    ): DatabaseStub =
        DatabaseStub(
            encounters = encounters,
            periods = mutableListOf(periodRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-nurse", "护理费", "中度依赖", "80"),
                feeItemRow("fee-meal", "伙食费", "三餐", "30"),
            ),
            assessments = mutableListOf(
                assessmentRow("enc-1", "2026-08-01", "2026-08-01T09:00:00+08:00", "中度依赖"),
            ),
            mealsByEncounter = mutableMapOf("enc-1" to listOf("正常", "正常", "部分")),
            bills = bills,
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

    private fun amount(value: Any?): BigDecimal =
        BigDecimal.valueOf((value as Number).toDouble())

    /** 区间最终账单的落库元组（含 settled_at 的 insert）。 */
    private fun finalBillTuple(stub: DatabaseStub): Pair<String, List<Any?>> =
        stub.tuples.first { it.first.contains("insert into healthcare.bills") && it.first.contains("settled_at") }

    // ========================================================================
    //  1. 离院/去世收束：同事务生成区间账单并冻结
    // ========================================================================

    @Test
    fun `离院收束同事务生成区间账单并冻结全部账单`() {
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        val service = HealthcareService(stub.pool)

        val encounter = service
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-09-20T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()

        // 响应：离院状态 + 冻结标记
        assertEquals("DISCHARGED", encounter.getString("status"))
        assertEquals("2026-09-20T10:00+08:00", encounter.getString("discharge_date"))
        assertNotNull(encounter.getString("settled_at"), "离院收束必须写 encounters.settled_at")

        // 区间最终账单：无已结算账期 → 起 = 入住日 08-01，止 = 离院日 09-20（闭区间 51 天）
        val (sql, values) = finalBillTuple(stub)
        assertEquals(LocalDate.parse("2026-08-01"), values[2], "区间起 = 无已结算账期时取入住日")
        assertEquals(LocalDate.parse("2026-09-20"), values[3], "区间止 = 离院日")
        assertEquals(51, BillingEngine.inclusiveDays(values[2] as LocalDate, values[3] as LocalDate), "闭区间 08-01..09-20 = 51 天")
        assertEquals("已结算", values[4], "区间最终账单创建即 已结算")
        assertNotNull(values[6], "区间最终账单必须写 settled_at")
        assertEquals(
            0,
            BigDecimal("9255.00").compareTo(values[5] as BigDecimal),
            "区间账单自动计费 = 床位 51×100 + 护理 51×80 + 伙食 2.5×30",
        )
        // 明细快照落库：床位/护理/伙食
        val finalBillItems = stub.billItems.filter { it["bill_id"] == values[0] }
        assertEquals(3, finalBillItems.size)
        assertEquals(0, BigDecimal("51").compareTo(amount(finalBillItems[0].getValue("quantity"))))
        assertEquals(0, BigDecimal("51").compareTo(amount(finalBillItems[1].getValue("quantity"))))
        assertEquals(0, BigDecimal("2.5").compareTo(amount(finalBillItems[2].getValue("quantity"))))

        // 既有未结账单直接冻结（不重算/不裁剪），全部 bills = 已结算 + settled_at
        assertEquals(2, stub.bills.size)
        for (bill in stub.bills) {
            assertEquals("已结算", bill["status"], "冻结后该 encounter 全部账单必须为 已结算")
            assertNotNull(bill["settled_at"], "冻结后每张账单必须写 settled_at")
        }

        // SQL 序列：区间账单生成 → 账单冻结 → encounter 冻结 → 离院状态更新，全程同一事务连接
        val insertIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.bills") }
        val freezeBillsIndex = stub.queries.indexOfFirst { it.contains("update healthcare.bills") && it.contains("settled_at") }
        val freezeEncounterIndex = stub.queries.indexOfFirst { it.contains("update healthcare.encounters") && it.contains("settled_at") }
        val dischargeIndex = stub.queries.indexOfFirst { it.contains("update healthcare.encounters") && it.contains("discharge_date") }
        assertTrue(insertIndex in 0 until freezeBillsIndex, "区间账单必须先于冻结: ${stub.queries}")
        assertTrue(freezeBillsIndex < freezeEncounterIndex, "账单冻结必须先于 encounter 冻结: ${stub.queries}")
        assertTrue(freezeEncounterIndex < dischargeIndex, "结算冻结必须先于离院状态更新: ${stub.queries}")
        assertEquals(1, stub.transactionCalls, "整个离院收束必须在同一个 withTransaction 连接内")
    }

    @Test
    fun `去世收束同事务按已结清账期末日加一生成区间账单并冻结`() {
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结清")),
        )
        val service = HealthcareService(stub.pool)

        val encounter = service
            .deathEncounter(
                "enc-1",
                JsonObject()
                    .put("death_date", "2026-09-20T14:00:00+08:00")
                    .put("death_cause", "心脏骤停"),
            )
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("DECEASED", encounter.getString("status"))
        assertEquals("2026-09-20T14:00+08:00", encounter.getString("death_date"))
        assertNotNull(encounter.getString("settled_at"), "去世收束必须写 encounters.settled_at")

        // 区间 = MAX(已结清 period_end)+1 = 09-01 ～ 去世日 09-20（闭区间 20 天）
        val (sql, values) = finalBillTuple(stub)
        assertEquals(LocalDate.parse("2026-09-01"), values[2], "区间起 = 已结清账期末日 08-31 + 1")
        assertEquals(LocalDate.parse("2026-09-20"), values[3], "区间止 = 去世日")
        assertEquals(20, BillingEngine.inclusiveDays(values[2] as LocalDate, values[3] as LocalDate), "闭区间 09-01..09-20 = 20 天")
        assertEquals("已结算", values[4])
        assertNotNull(values[6])
        assertEquals(
            0,
            BigDecimal("3675.00").compareTo(values[5] as BigDecimal),
            "区间账单自动计费 = 床位 20×100 + 护理 20×80 + 伙食 2.5×30",
        )
        // 已结清账单也按「全部账单 = 已结算」冻结
        assertEquals("已结算", stub.bills.first { it["id"] == "bill-1" }["status"])
        assertNotNull(stub.bills.first { it["id"] == "bill-1" }["settled_at"])
        assertEquals(1, stub.transactionCalls, "整个去世收束必须在同一个 withTransaction 连接内")
    }

    @Test
    fun `收束时区间起大于区间止不生成区间账单仅冻结`() {
        // 已结清账单账期已覆盖到收束日之后（08-31 > 08-20）：区间起 09-01 > 止 08-20
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "ACTIVE",
                        "discharge_date" to null,
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结清")),
        )
        HealthcareService(stub.pool)
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-08-20T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()

        assertTrue(
            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
            "区间起 > 区间止时不得生成区间账单: ${stub.tuples.map { it.first }}",
        )
        assertEquals("已结算", stub.bills.single()["status"])
        assertNotNull(stub.encounters.single()["settled_at"])
    }

    // ========================================================================
    //  2. 冻结守卫（单元级）
    // ========================================================================

    @Test
    fun `冻结后生成加项缴费均返回409`() {
        val frozen = OffsetDateTime.parse("2026-09-20T18:00:00+08:00")
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
                        "settled_at" to frozen,
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        val billService = BillService(stub.pool)
        val paymentService = PaymentService(stub.pool)

        // 生成
        val generateCause = causeOf(billService.generate("enc-1", JsonObject().put("month", "2026-08"), "cashier-1"))
        assertInstanceOf(ConflictException::class.java, generateCause)
        assertTrue(generateCause.message?.contains("settled") == true, "got: ${generateCause.message}")

        // 手工加项
        val addCause = causeOf(
            billService.addItem(
                "bill-1",
                JsonObject().put("item_id", "fee-other").put("quantity", 1),
                "cashier-1",
            ),
        )
        assertInstanceOf(ConflictException::class.java, addCause)
        assertTrue(addCause.message?.contains("settled") == true, "got: ${addCause.message}")

        // 缴费
        val payCause = causeOf(
            paymentService.createPayment("bill-1", JsonObject().put("amount", 100).put("method", "现金"), "cashier-1"),
        )
        assertInstanceOf(ConflictException::class.java, payCause)
        assertTrue(payCause.message?.contains("settled") == true, "got: ${payCause.message}")

        // 冻结后任何写入都不得落库
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bill_items") })
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
    }

    @Test
    fun `加项对已结算账单返回409且其他非待缴费状态保持400`() {
        // encounter 未冻结、账单状态 已结算 → 409（由现行 400 改为 409）
        val settledBill = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结算")),
        )
        val settledCause = causeOf(
            BillService(settledBill.pool)
                .addItem("bill-1", JsonObject().put("item_id", "fee-other").put("quantity", 1), "cashier-1"),
        )
        assertInstanceOf(ConflictException::class.java, settledCause)

        // encounter 未冻结、账单状态 已结清 → 保持 400（非待缴费不可加项）
        val paidBill = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结清")),
        )
        val paidCause = causeOf(
            BillService(paidBill.pool)
                .addItem("bill-1", JsonObject().put("item_id", "fee-other").put("quantity", 1), "cashier-1"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, paidCause)
        assertTrue(paidCause.message?.contains("待缴费") == true, "got: ${paidCause.message}")
    }

    @Test
    fun `补结算服务对已结算encounter返回409`() {
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
                        "settled_at" to OffsetDateTime.parse("2026-09-20T18:00:00+08:00"),
                    ),
                ),
            ),
        )
        val cause = causeOf(HealthcareService(stub.pool).settleEncounterBilling("enc-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already settled") == true, "got: ${cause.message}")
    }

    // ========================================================================
    //  3. 嵌入式 HTTP 路由
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

    @Test
    fun `补结算成功生成区间账单并冻结`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "补结算必须 201")
                        assertNotNull(body.getString("settled_at"), "响应 encounter 必须带 settled_at 冻结标记")
                        // 区间账单 08-01..09-20（无已结算账期 → 起 = 入住日）已结算并冻结
                        val (sql, values) = finalBillTuple(stub)
                        assertEquals(LocalDate.parse("2026-08-01"), values[2])
                        assertEquals(LocalDate.parse("2026-09-20"), values[3])
                        assertEquals("已结算", values[4])
                        assertEquals(0, BigDecimal("9255.00").compareTo(values[5] as BigDecimal))
                        for (bill in stub.bills) {
                            assertEquals("已结算", bill["status"])
                            assertNotNull(bill["settled_at"])
                        }
                        assertNotNull(stub.encounters.single()["settled_at"])
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `补结算已全部结算返回409`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
                        "settled_at" to OffsetDateTime.parse("2026-09-20T18:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结算")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "已全部结算重复调用必须 409（幂等口径：明确 409 而非幂等成功）")
                        assertTrue(body.getString("error")?.contains("already settled") == true, "got: ${body.getString("error")}")
                        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `补结算未离院去世返回409`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = settlementStub()
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "未离院/去世的 encounter 补结算必须 409")
                        assertTrue(body.getString("error")?.contains("not discharged or deceased") == true, "got: ${body.getString("error")}")
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `补结算未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = settlementStub()
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(401, status, "无认证 userId 补结算必须 401")
                        assertNotNull(body.getString("error"))
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `补结算区间与既有账单完全一致时不重复生成直接冻结`(vertx: Vertx, ctx: VertxTestContext) {
        // 无已结算账期 → 区间 = 入住日 08-01 ～ 离院日 08-31，与既有账单账期完全一致
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-31T10:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status)
                        assertTrue(
                            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
                            "最终区间与既有账单完全一致时不得重复生成（唯一约束防冲突），直接冻结",
                        )
                        assertEquals(1, stub.bills.size)
                        assertEquals("已结算", stub.bills.single()["status"])
                        assertNotNull(stub.bills.single()["settled_at"])
                        assertNotNull(stub.encounters.single()["settled_at"])
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `补结算无计费字典时生成零元封口账单`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                    ),
                ),
            ),
            periods = mutableListOf(periodRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-15", "1500.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement")
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "无可计费项也必须成功收束（0 元封口账单）")
                        val (sql, values) = finalBillTuple(stub)
                        assertEquals(LocalDate.parse("2026-08-01"), values[2])
                        assertEquals(LocalDate.parse("2026-08-20"), values[3], "封口账单账期仍须正确（闭区间）")
                        assertEquals("已结算", values[4])
                        assertEquals(0, BigDecimal.ZERO.compareTo(values[5] as BigDecimal), "封口账单 0 元")
                        assertNotNull(values[6])
                        // 无自动明细
                        assertTrue(
                            stub.billItems.none { it["bill_id"] == values[0] },
                            "0 元封口账单不得产生自动明细",
                        )
                        for (bill in stub.bills) {
                            assertEquals("已结算", bill["status"])
                            assertNotNull(bill["settled_at"])
                        }
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `冻结后新增账单手工加项缴费均返回409`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
                        "settled_at" to OffsetDateTime.parse("2026-09-20T18:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结算")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", JsonObject().put("month", "2026-08"))
                .compose { (billStatus, billBody) ->
                    ctx.verify {
                        assertEquals(409, billStatus, "冻结后新增账单必须 409")
                        assertTrue(billBody.getString("error")?.contains("settled") == true, "got: ${billBody.getString("error")}")
                    }
                    httpRequest(
                        vertx, port, HttpMethod.POST,
                        "/healthcare/v1/bills/bill-1/items",
                        JsonObject().put("item_id", "fee-other").put("quantity", 1),
                    ).compose { (itemStatus, itemBody) ->
                        ctx.verify {
                            assertEquals(409, itemStatus, "冻结后手工加项必须 409")
                            assertTrue(itemBody.getString("error")?.contains("settled") == true, "got: ${itemBody.getString("error")}")
                        }
                        httpRequest(
                            vertx, port, HttpMethod.POST,
                            "/healthcare/v1/bills/bill-1/payments",
                            JsonObject().put("amount", 100).put("method", "现金"),
                        ).map { (payStatus, payBody) ->
                            ctx.verify {
                                assertEquals(409, payStatus, "冻结后缴费必须 409")
                                assertTrue(payBody.getString("error")?.contains("settled") == true, "got: ${payBody.getString("error")}")
                            }
                        }
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `未冻结已离院encounter仍可生成账单并裁剪到离院日`(vertx: Vertx, ctx: VertxTestContext) {
        // 回归：未冻结的既有行为不变
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                    ),
                ),
            ),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/bills", JsonObject().put("month", "2026-08"))
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "未冻结的已离院 encounter 仍可生成账单")
                        assertEquals("2026-08-01", body.getString("period_start"))
                        assertEquals("2026-08-20", body.getString("period_end"), "账期止裁剪到离院日")
                        assertEquals("待缴费", body.getString("status"))
                        assertEquals(
                            0,
                            BigDecimal("3675.00").compareTo(amount(body.getValue("total_amount"))),
                            "床位 20 天×100 + 护理 20 天×80 + 伙食 2.5×30",
                        )
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `未冻结待缴费账单仍可缴费并流转已结清`(vertx: Vertx, ctx: VertxTestContext) {
        // 回归：冻结守卫不得改变未冻结缴费行为
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/bills/bill-1/payments",
                JsonObject().put("amount", 5655).put("method", "现金"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "未冻结待缴费账单仍可缴费")
                    assertEquals(0, BigDecimal("5655").compareTo(amount(body.getValue("amount"))))
                    assertEquals("已结清", stub.bills.single()["status"], "余额归零后账单流转 已结清")
                    assertTrue(stub.encounters.single()["settled_at"] == null, "缴费不得写结算冻结标记")
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }
}

// ——— mock 基础设施（顶层函数，供本测试类与嵌套 stub 共用） ———

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
