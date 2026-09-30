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
 * 离院/去世结算收束（结算收束端点 + 冻结守卫 + 路由）非数据库测试
 * （mockk + 嵌入式 HTTP，参照 DepositServiceTest 模式，默认流水线运行）。
 *
 * **023 决策 A**：离院/去世不再收束账单（原 `BillService.settleEncounter` 已删除），
 * 账单收尾统一由 `POST /encounters/:id/billing-settlement`（`HealthcareService.settleEncounterBilling`
 * 三步路径：建最终账单 → 核销 → 未结判定 → 冻结）承担。原先写在离院/去世用例里的
 * 「区间最终账单生成 / 冻结 / 快照」断言已迁移到收束端点用例（见下方同名意图用例）。
 *
 * 覆盖验收口径：
 *   - dischargeEncounter/deathEncounter 只做业务事实与照护流程收尾（状态/日期/诊断写入、
 *     医嘱终止、护理周期关闭）：**不生成区间账单、不冻结账单、不写 encounters.settled_at**，
 *     账单与押金台账逐行未变
 *   - 冻结后 POST /encounters/:id/bills、/bills/:id/items、/bills/:id/payments 均 409
 *   - POST /encounters/:id/billing-settlement：已离院/去世未结算 → 201 生成区间账单并冻结；
 *     已全部结算 → 409；未离院/去世 → 409；未认证 → 401
 *   - 边界：区间起 > 区间止不生成；最终区间与既有账单完全一致不重复生成；
 *     已结算账单账期覆盖收束日之后仅冻结；区间起取「已结清账期末日 + 1」
 *   - 回归：未冻结的既有行为不变（已离院未结算仍可生成账单并裁剪到离院日、仍可缴费）
 *   - 结算携带 deposit_offset（**021 次序变更**：先建区间最终账单 → 再核销 → 再判定未结 →
 *     再冻结，因此核销的分配目标包含最终账期）；核销与收束共用同一 withTransaction 与
 *     同一连接，核销写入（payments/deposit_records）按 SQL 序列先于冻结；
 *     收束资格不满足（未离院/去世 409）时错误被传播、核销不执行、不进入冻结。
 *     mockk 桩不模拟数据库回滚，核销/建单写入在桩上仍可见——真实「失败后零残差」只能由
 *     后置 PostgreSQL 集成测试覆盖，本文件不作回滚断言。
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
        var deposits: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val encounters: MutableList<MutableMap<String, Any?>> = encounters
        val queries = mutableListOf<String>()
        /** 仅经由 pool（而非事务连接 conn）下发的 SQL：用于断言核销与收束共用同一连接。 */
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
                    // 021：区间最终账单以 待缴费 建立、insert 不含 settled_at（与 generate 同形状）
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
                    // 021：冻结逐张 UPDATE（status/settled_at/outstanding_amount/updated_at[/write_off_reason]）
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
                    // ——— 未结/核销目标账单：待缴费 且 余额 > 0（必须先于通用 bills 分支） ———
                    // 034：账单详情/列表新增 red_bill LEFT JOIN 派生 reversal_bill_id，
                    // 用 red_bill 排除，避免把账单读查询误判成未结聚合
                    sql.contains("from healthcare.bills") && sql.contains("left outer join") &&
                        !sql.contains("red_bill") -> {
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
                    // ——— 冻结前的全部账单 id（021：逐张写未结快照；必须先于通用 bills 分支） ———
                    sql.contains("select healthcare.bills.id from healthcare.bills") -> {
                        val scoped = bills.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(
                            rowSet(*scoped.map { mockRow(mapOf("id" to it["id"])) }.toTypedArray()),
                        )
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
                    // ——— payments（含核销写入：8 个绑定值，metadata 在 $6） ———
                    sql.contains("insert into healthcare.payments") -> {
                        val hasMetadata = values.size >= 8
                        payments.add(
                            mapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "amount" to values[2],
                                "method" to values[3],
                                "operator" to values[4],
                                "metadata" to if (hasMetadata) values[5] else null,
                                "created_at" to if (hasMetadata) values[6] else values[5],
                                "updated_at" to if (hasMetadata) values[7] else values[6],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    // ——— deposit_records（结算核销写入 type = 核销） ———
                    sql.contains("insert into healthcare.deposit_records") -> {
                        deposits.add(
                            mapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "type" to values[2],
                                "amount" to values[3],
                                "operator" to values[4],
                                "metadata" to values.getOrNull(5),
                                "created_at" to values.getOrNull(6),
                                "updated_at" to values.getOrNull(7),
                            ),
                        )
                        Future.succeededFuture(rowSet())
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

        private fun record(sql: String, viaPool: Boolean = false) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
            if (viaPool) poolQueries.add(normalizedSql)
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
                feeItemRow("fee-nurse", "护理费", "中度依赖", "80", nursingLevel = "中度依赖"),
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

    /** 区间最终账单的落库元组（021：以 待缴费 建立，insert 不含 settled_at）。 */
    private fun finalBillTuple(stub: DatabaseStub): Pair<String, List<Any?>> =
        stub.tuples.first { it.first.contains("insert into healthcare.bills") }

    // ========================================================================
    //  1. 023 解耦：离院/去世不再收束账单；账单收尾由「结算收束」端点承担
    // ========================================================================

    @Test
    fun `离院不再收束账单且账单未被触碰`() {
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        stub.deposits.add(depositRow("登记", "5000.00"))
        val service = HealthcareService(stub.pool)

        val encounter = service
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-09-20T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()

        // 离院本身成功且语义不变（状态 / 离院日期写入）
        assertEquals("DISCHARGED", encounter.getString("status"))
        assertEquals("2026-09-20T10:00+08:00", encounter.getString("discharge_date"))
        assertNull(encounter.getString("settled_at"), "023：离院不得写 encounters.settled_at")
        assertNull(stub.encounters.single()["settled_at"], "023：离院不得写 encounters.settled_at")

        // 没有账单插入 / 账单状态更新 / 冻结写入，也没有核销台账与缴费流水
        assertTrue(
            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
            "023：离院不得生成区间最终账单: ${stub.tuples.map { it.first }}",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.bills") },
            "023：离院不得更新任何账单（冻结未发生）: ${stub.tuples.map { it.first }}",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.encounters") && it.first.contains("settled_at") },
            "023：离院不得写 encounters.settled_at",
        )
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })

        // 既有账单逐行未变（冻结会写 settled_at/outstanding_amount/write_off_reason）
        val bill = stub.bills.single()
        assertEquals("待缴费", bill["status"], "023：离院后账单保持原状态")
        assertNull(bill["settled_at"], "023：离院后账单不得写 settled_at")
        assertEquals(0, BigDecimal("5655.00").compareTo(amount(bill["total_amount"])))
        assertNull(bill["outstanding_amount"], "023：离院后未结快照不得写入")
        assertNull(bill["write_off_reason"], "023：离院后减免原因不得写入")

        // 押金台账未变
        assertEquals(1, stub.deposits.size, "023：离院不得写押金台账")
        assertEquals(0, stub.payments.size, "023：离院不得写缴费流水")

        // 离院仍在同一个 withTransaction 连接内完成
        assertEquals(1, stub.transactionCalls)
    }

    @Test
    fun `结算收束生成区间账单并冻结全部账单`() {
        // 结算断言自「离院收束同事务生成区间账单并冻结全部账单」迁移而来，依据 023 解耦：
        // 离院不再收束（上一个用例已固定），同一 fixture 改由收束端点完成账单收尾。
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        val service = HealthcareService(stub.pool)
        val discharged = service
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-09-20T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()
        assertNull(discharged.getString("settled_at"), "023：离院不写 settled_at（收束前可收费）")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })

        // 收束：未结余额存在 → 必须显式减免确认（021 门禁）
        val encounter = service
            .settleEncounterBilling(
                "enc-1",
                JsonObject().put("write_off_reason", "离院结算，家属书面确认不再追收"),
                "cashier-route-1",
            )
            .toCompletionStage().toCompletableFuture().get()
        assertNotNull(encounter.getString("settled_at"), "结算收束必须写 encounters.settled_at")

        // 区间最终账单：无已结算账期 → 起 = 入住日 08-01，止 = 离院日 09-20（闭区间 51 天）
        val (sql, values) = finalBillTuple(stub)
        assertEquals(LocalDate.parse("2026-08-01"), values[2], "区间起 = 无已结算账期时取入住日")
        assertEquals(LocalDate.parse("2026-09-20"), values[3], "区间止 = 离院日")
        assertEquals(51, BillingEngine.inclusiveDays(values[2] as LocalDate, values[3] as LocalDate), "闭区间 08-01..09-20 = 51 天")
        // 021 行为变更：区间最终账单改为以 待缴费 建立，创建时不写 settled_at（由冻结阶段统一写）
        assertEquals("待缴费", values[4], "021：区间最终账单以 待缴费 建立")
        assertFalse(sql.contains("settled_at"), "021：区间最终账单创建时不写 settled_at: $sql")
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
        // 收束端点强制减免留痕：区间最终账单与既有未结账单都写未结快照 + 同一条减免原因
        val reason = "离院结算，家属书面确认不再追收"
        val finalBillRow = stub.bills.first { it["id"] == values[0] }
        assertEquals(0, BigDecimal("9255.00").compareTo(amount(finalBillRow["outstanding_amount"])))
        assertEquals(reason, finalBillRow["write_off_reason"])
        val existingBillRow = stub.bills.first { it["id"] == "bill-1" }
        assertEquals(0, BigDecimal("5655.00").compareTo(amount(existingBillRow["outstanding_amount"])))
        assertEquals(reason, existingBillRow["write_off_reason"], "021：未结行必须带减免原因（不允许静默结转）")

        // SQL 序列：区间账单生成 → 账单冻结 → encounter 冻结
        val insertIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.bills") }
        val freezeBillsIndex = stub.queries.indexOfFirst { it.contains("update healthcare.bills") && it.contains("settled_at") }
        val freezeEncounterIndex = stub.queries.indexOfFirst { it.contains("update healthcare.encounters") && it.contains("settled_at") }
        assertTrue(insertIndex in 0 until freezeBillsIndex, "区间账单必须先于冻结: ${stub.queries}")
        assertTrue(freezeBillsIndex < freezeEncounterIndex, "账单冻结必须先于 encounter 冻结: ${stub.queries}")
    }

    @Test
    fun `去世不再收束账单且账单未被触碰`() {
        // 023 决策 A：去世与离院同构，只标记事实，账单收尾统一由「结算收束」承担
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
        assertNull(encounter.getString("settled_at"), "023：去世不得写 encounters.settled_at")
        assertNull(stub.encounters.single()["settled_at"], "023：去世不得写 encounters.settled_at")

        assertTrue(
            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
            "023：去世不得生成区间最终账单: ${stub.tuples.map { it.first }}",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.bills") },
            "023：去世不得更新任何账单（冻结未发生）: ${stub.tuples.map { it.first }}",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.encounters") && it.first.contains("settled_at") },
            "023：去世不得写 encounters.settled_at",
        )
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })

        val bill = stub.bills.single()
        assertEquals("已结清", bill["status"], "023：去世后账单保持原状态")
        assertNull(bill["settled_at"], "023：去世后账单不得写 settled_at")
        assertNull(bill["outstanding_amount"])
        assertNull(bill["write_off_reason"])
        assertEquals(0, stub.payments.size)
        assertEquals(1, stub.transactionCalls, "去世仍在同一个 withTransaction 连接内完成")
    }

    @Test
    fun `结算收束按已结清账期末日加一生成区间账单并冻结`() {
        // 结算断言自「去世收束同事务按已结清账期末日加一生成区间账单并冻结」迁移而来，
        // 依据 023 解耦：去世不再收束，同一 fixture 改由收束端点完成账单收尾。
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结清")),
        )
        val service = HealthcareService(stub.pool)
        service
            .deathEncounter(
                "enc-1",
                JsonObject()
                    .put("death_date", "2026-09-20T14:00:00+08:00")
                    .put("death_cause", "心脏骤停"),
            )
            .toCompletionStage().toCompletableFuture().get()

        val encounter = service
            .settleEncounterBilling(
                "enc-1",
                JsonObject().put("write_off_reason", "去世结算，剩余未结确认为减免"),
                "cashier-route-1",
            )
            .toCompletionStage().toCompletableFuture().get()
        assertNotNull(encounter.getString("settled_at"), "结算收束必须写 settled_at")

        // 区间 = MAX(已结清账期末日 08-31) + 1 至 去世日 09-20（闭区间 20 天）
        val (sql, values) = finalBillTuple(stub)
        assertEquals(LocalDate.parse("2026-09-01"), values[2], "区间起 = 已结清账期末日 08-31 + 1")
        assertEquals(LocalDate.parse("2026-09-20"), values[3], "区间止 = 去世日")
        assertEquals(20, BillingEngine.inclusiveDays(values[2] as LocalDate, values[3] as LocalDate), "闭区间 09-01..09-20 = 20 天")
        // 021 行为变更：区间最终账单以 待缴费 建立、创建时不写 settled_at
        assertEquals("待缴费", values[4])
        assertFalse(sql.contains("settled_at"), "021：区间最终账单创建时不写 settled_at: $sql")
        assertEquals(
            0,
            BigDecimal("3675.00").compareTo(values[5] as BigDecimal),
            "区间账单自动计费 = 床位 20 天×100 + 护理 20 天×80 + 伙食 2.5×30",
        )
        // 已结清账单也按「全部账单 = 已结算」冻结（未结为 0、无减免原因）
        val billOne = stub.bills.first { it["id"] == "bill-1" }
        assertEquals("已结算", billOne["status"])
        assertNotNull(billOne["settled_at"])
        assertEquals(0, BigDecimal.ZERO.compareTo(amount(billOne["outstanding_amount"])))
        assertNull(billOne["write_off_reason"])
        val finalBillRow = stub.bills.first { it["id"] == values[0] }
        assertEquals(0, BigDecimal("3675.00").compareTo(amount(finalBillRow["outstanding_amount"])))
        assertEquals("去世结算，剩余未结确认为减免", finalBillRow["write_off_reason"])
    }

    @Test
    fun `收束时区间起大于区间止不生成区间账单仅冻结`() {
        // 023 解耦后该边界由收束端点承载：已离院（离院日 08-20）且已结清账单账期
        // 覆盖到收束日之后（08-31 > 08-20）→ 区间起 09-01 > 止 08-20
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-20T10:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结清")),
        )
        HealthcareService(stub.pool)
            .settleEncounterBilling("enc-1", JsonObject(), "cashier-route-1")
            .toCompletionStage().toCompletableFuture().get()

        assertTrue(
            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
            "区间起 > 区间止时不得生成区间账单: ${stub.tuples.map { it.first }}",
        )
        assertEquals("已结算", stub.bills.single()["status"])
        assertNotNull(stub.bills.single()["settled_at"])
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
        // 三参签名：body 省略核销（空对象 = 不核销），operator 取既有认证主体字面量
        val cause = causeOf(HealthcareService(stub.pool).settleEncounterBilling("enc-1", JsonObject(), "cashier-route-1"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already settled") == true, "got: ${cause.message}")
    }

    // ========================================================================
    //  2.1 结算携带押金核销：同事务编排与失败传播
    // ========================================================================

    private fun depositRow(type: String, amount: String): Map<String, Any?> =
        mapOf(
            "id" to "dep-$type-$amount",
            "encounter_id" to "enc-1",
            "type" to type,
            "amount" to BigDecimal(amount),
            "operator" to "cashier-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )

    @Test
    fun `结算携带核销时与收束同事务且核销先于冻结`() {
        // 已有 待缴费 账单账期 07-01..07-31（早于收束区间 08-01..09-20）：
        // 核销目标按账期升序，先行命中的必然是 bill-1，断言不依赖 ULID 排序
        val stub = settlementStub(
            encounters = mutableListOf(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
                    ),
                ),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-07-01", "2026-07-31", "5655.00")),
        )
        stub.deposits.add(depositRow("登记", "5000.00"))

        // 021 行为变更：核销后仍有未结（bill-1 余额 4655 + 最终账单 9255）必须显式提供减免原因
        val encounter = HealthcareService(stub.pool)
            .settleEncounterBilling(
                "enc-1",
                JsonObject()
                    .put("deposit_offset", 1000)
                    .put("write_off_reason", "离院结算，家属书面确认不再追收"),
                "cashier-route-1",
            )
            .toCompletionStage().toCompletableFuture().get()

        assertNotNull(encounter.getString("settled_at"), "结算收束必须写 settled_at")

        // 同一事务、同一连接：只进入一次 withTransaction，且全部 SQL 经事务连接下发
        assertEquals(1, stub.transactionCalls, "核销与收束必须共用同一个 withTransaction")
        assertTrue(stub.poolQueries.isEmpty(), "核销与收束不得经 pool 执行 SQL: ${stub.poolQueries}")

        // 核销写入：payments(method = 押金) + deposit_records(type = 核销)，operator 取认证主体
        val paymentInserts = stub.tuples.filter { it.first.contains("insert into healthcare.payments") }
        assertEquals(1, paymentInserts.size, "核销额 1000 < 账单余额 5655 → 命中一笔")
        assertEquals(PaymentService.METHOD_DEPOSIT, paymentInserts.single().second[3])
        assertEquals("cashier-route-1", paymentInserts.single().second[4])
        assertEquals("bill-1", paymentInserts.single().second[1])
        assertEquals(0, BigDecimal("1000").compareTo(amount(paymentInserts.single().second[2])))
        val paymentMetadata = paymentInserts.single().second[5] as JsonObject
        assertEquals(true, paymentMetadata.getBoolean("deposit_offset"))
        assertEquals("enc-1", paymentMetadata.getString("encounter_id"))

        val depositInserts = stub.tuples.filter { it.first.contains("insert into healthcare.deposit_records") }
        assertEquals(1, depositInserts.size)
        assertEquals(DepositOffsetService.TYPE_OFFSET, depositInserts.single().second[2])
        val depositMetadata = depositInserts.single().second[5] as JsonObject
        assertEquals("bill-1", depositMetadata.getString("bill_id"))
        assertEquals(paymentInserts.single().second[0], depositMetadata.getString("payment_id"))
        assertEquals("2026-07-01", depositMetadata.getString("period_start"))
        assertEquals("2026-07-31", depositMetadata.getString("period_end"))

        // SQL 序列（021 次序变更）：区间账单生成 → 核销写入 → 账单冻结 → encounter 冻结
        val offsetIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.payments") }
        val insertBillIndex = stub.queries.indexOfFirst { it.contains("insert into healthcare.bills") }
        val freezeBillsIndex =
            stub.queries.indexOfFirst { it.contains("update healthcare.bills") && it.contains("settled_at") }
        val freezeEncounterIndex =
            stub.queries.indexOfFirst { it.contains("update healthcare.encounters") && it.contains("settled_at") }
        assertTrue(insertBillIndex in 0 until offsetIndex, "021：区间最终账单生成必须先于核销: ${stub.queries}")
        assertTrue(offsetIndex < freezeBillsIndex, "核销必须先于账单冻结")
        assertTrue(freezeBillsIndex < freezeEncounterIndex, "账单冻结先于 encounter 冻结")

        // 冻结后该 encounter 全部账单为 已结算 + settled_at（收束语义未被核销改变）
        for (bill in stub.bills) {
            assertEquals("已结算", bill["status"], "冻结后该 encounter 全部账单必须为 已结算")
            assertNotNull(bill["settled_at"], "冻结后每张账单必须写 settled_at")
        }
        // 未结行带减免原因、已结清行保持 NULL
        val billOne = stub.bills.first { it["id"] == "bill-1" }
        assertEquals(0, BigDecimal("4655.00").compareTo(amount(billOne["outstanding_amount"])))
        assertEquals("离院结算，家属书面确认不再追收", billOne["write_off_reason"])
    }

    @Test
    fun `结算资格不满足时错误被传播且不进入冻结`() {
        // encounter 在住（ACTIVE，未离院/去世）→ 阶段一资格校验不满足 409
        val stub = settlementStub(
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        stub.deposits.add(depositRow("登记", "5000.00"))

        val cause = causeOf(
            HealthcareService(stub.pool)
                .settleEncounterBilling("enc-1", JsonObject().put("deposit_offset", 100), "cashier-route-1"),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("not discharged or deceased") == true, "got: ${cause.message}")

        // 同一事务、同一连接：只开一次事务；失败被原样传播，且不继续执行冻结
        assertEquals(1, stub.transactionCalls)
        assertTrue(stub.poolQueries.isEmpty(), "核销与收束不得经 pool 执行 SQL: ${stub.poolQueries}")
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.bills") && it.first.contains("settled_at") },
            "收束失败不得冻结账单",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.encounters") && it.first.contains("settled_at") },
            "收束失败不得写 encounters.settled_at",
        )
        assertNull(stub.encounters.single()["settled_at"])

        // 021 次序变更：资格校验是第一步，失败即中止，核销与建单都不执行
        // （020 是「先核销 → 再收束」，资格不满足时核销写入已发生）。
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") }, "资格不满足不得生成区间账单")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") }, "资格不满足不得核销")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })
        // 限制说明：mockk 桩不模拟 PostgreSQL 事务回滚，本用例在桩上未产生任何核销/建单写入，
        // 因此「未进入写入」可在桩上断言；真实「失败后零残差」仍由后置 PostgreSQL 集成测试覆盖。
        assertEquals(1, stub.deposits.size, "只有预置的登记记录，本次核销未执行")
        assertEquals(0, stub.payments.size, "本次核销未执行")
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
            // 021 行为变更：存在未结账单必须显式带上 write_off_reason，否则 409
            httpRequest(
                vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement",
                JsonObject().put("write_off_reason", "离院结算，家属书面确认不再追收"),
            )
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "补结算必须 201")
                        assertNotNull(body.getString("settled_at"), "响应 encounter 必须带 settled_at 冻结标记")
                        // 区间账单 08-01..09-20（无已结算账期 → 起 = 入住日）以 待缴费 建立后被冻结
                        val (sql, values) = finalBillTuple(stub)
                        assertEquals(LocalDate.parse("2026-08-01"), values[2])
                        assertEquals(LocalDate.parse("2026-09-20"), values[3])
                        assertEquals("待缴费", values[4], "021：区间最终账单以 待缴费 建立")
                        assertFalse(sql.contains("settled_at"), "021：创建时不写 settled_at: $sql")
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
            // 021：区间与既有账单一致（不生成最终账单），bill-1 未结 → 需显式减免确认
            httpRequest(
                vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement",
                JsonObject().put("write_off_reason", "区间已封口，剩余未结确认为减免"),
            )
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
            // 021：bill-1 未结 1500 → 必须显式减免确认
            httpRequest(
                vertx, port, HttpMethod.POST, "/healthcare/v1/encounters/enc-1/billing-settlement",
                JsonObject().put("write_off_reason", "无计费字典，剩余未结确认为减免"),
            )
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "无可计费项也必须成功收束（0 元封口账单）")
                        val (sql, values) = finalBillTuple(stub)
                        assertEquals(LocalDate.parse("2026-08-01"), values[2])
                        assertEquals(LocalDate.parse("2026-08-20"), values[3], "封口账单账期仍须正确（闭区间）")
                        assertEquals("待缴费", values[4], "021：封口账单同样以 待缴费 建立")
                        assertEquals(0, BigDecimal.ZERO.compareTo(values[5] as BigDecimal), "封口账单 0 元")
                        assertFalse(sql.contains("settled_at"), "021：创建时不写 settled_at: $sql")
                        // 无自动明细
                        assertTrue(
                            stub.billItems.none { it["bill_id"] == values[0] },
                            "0 元封口账单不得产生自动明细",
                        )
                        for (bill in stub.bills) {
                            assertEquals("已结算", bill["status"])
                            assertNotNull(bill["settled_at"])
                        }
                        // 0 元封口账单的未结快照为 0、无减免原因
                        val sealed = stub.bills.first { it["id"] == values[0] }
                        assertEquals(0, BigDecimal.ZERO.compareTo(amount(sealed["outstanding_amount"])))
                        assertNull(sealed["write_off_reason"])
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
