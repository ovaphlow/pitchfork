package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Bills.BILLS
import com.ovaphlow.crate.database.gen.healthcare.tables.DepositRecords.DEPOSIT_RECORDS
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Payments.PAYMENTS
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 押金核销服务（养老收费的结算收束子任务）。
 *
 * 核销是押金余额与账单余额同减的内部结转：不产生现金流入，不改变账单合计；
 * 每笔分配写入 payments（method = 押金）与 deposit_records（type = 核销），
 * 账单余额归零转 已结清（BillingEngine.STATUS_PAID）。
 *
 * 业务规则（服务端强制）：
 *  1. 核销只能由结算收束路径触发；`POST /bills/:id/payments` 的缴费方式白名单
 *     不放行「押金」，客户端无法伪造核销。
 *  2. 本服务**不自己开启事务**：全部读写都落在调用方传入的 [SqlClient]（同一连接）上，
 *     与 `HealthcareService.settleEncounterBilling` 的收束三步共用事务，任一环节失败整笔回滚。
 *  3. 分配目标：该 encounter 全部 `待缴费` 且余额 > 0 的账单，按 period_start 升序、
 *     同账期按 id 升序逐笔分配；单笔分配额 = min(剩余核销额, 该账单余额)。
 *  4. 上限 = min(押金余额，目标账单余额合计)；请求金额超上限 → 400，不部分执行。
 *  5. 核销金额必须 > 0、至多两位小数、不超过 9999999999.99；省略或 0 表示不核销
 *     （不查库、不写库），因此空请求体与改动前行为完全一致。
 *  6. 押金余额口径唯一来自 [DepositService.balanceOf]（余额 = Σ登记 − Σ退押 − Σ核销）。
 */
class DepositOffsetService(
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** deposit_records.type：押金核销减项 */
        const val TYPE_OFFSET = "核销"

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        /** 结算请求体唯一允许的键 */
        private const val KEY_OFFSET = "deposit_offset"

        /**
         * 解析并校验结算请求体：只允许 deposit_offset 一个键，返回核销额（省略/0 = 不核销）。
         *
         * 非法输入一律 [IllegalArgumentException]（路由映射 400）。客户端不得提交
         * operator/bill_id/amount 等任何其它键。
         */
        fun parseOffset(body: JsonObject): BigDecimal {
            val extra = body.fieldNames().filter { it != KEY_OFFSET }.sorted()
            if (extra.isNotEmpty()) {
                throw IllegalArgumentException("unsupported settlement keys: ${extra.joinToString(", ")}")
            }
            val raw = body.getValue(KEY_OFFSET) ?: return BigDecimal.ZERO
            val value = (raw as? Number)?.toDouble()
                ?: throw IllegalArgumentException("$KEY_OFFSET must be a number")
            if (!value.isFinite() || value < 0) {
                throw IllegalArgumentException("$KEY_OFFSET must not be negative")
            }
            val decimal = BigDecimal.valueOf(value)
            if (decimal.scale() > 2) {
                throw IllegalArgumentException("$KEY_OFFSET must have at most 2 decimal places")
            }
            if (decimal > maxAmount) {
                throw IllegalArgumentException("$KEY_OFFSET must not exceed $maxAmount")
            }
            // 0 视为不核销
            return if (decimal.signum() == 0) BigDecimal.ZERO else decimal
        }

        /**
         * 分配纯函数：按账单余额顺序逐笔吃掉核销额，返回与 [balances] 等长的分配额列表。
         * `offset` ≤ 0 或 `balances` 为空时返回全 0 列表（调用方据此跳过全部写入）。
         */
        internal fun allocate(offset: BigDecimal, balances: List<BigDecimal>): List<BigDecimal> {
            if (offset.signum() <= 0) return balances.map { BigDecimal.ZERO }
            var remaining = offset
            return balances.map { balance ->
                if (remaining.signum() <= 0 || balance.signum() <= 0) {
                    BigDecimal.ZERO
                } else {
                    val allocated = remaining.min(balance)
                    remaining = remaining.subtract(allocated)
                    allocated
                }
            }
        }
    }

    /** 目标账单及其当前余额（余额 = 合计 − 累计缴费，只保留余额 > 0 的行）。 */
    private data class TargetBill(
        val id: String,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val balance: BigDecimal,
    )

    /**
     * 在调用方事务内执行押金核销（同连接）。
     *
     * 返回 `{"encounter_id":..., "amount": 实际核销合计, "count": 笔数,
     * "allocations": [{"bill_id","period_start","period_end","amount"}]}`。
     * 不核销（金额为 0）时返回 amount=0、count=0、allocations=[] 且不写任何行。
     */
    fun offsetArrears(
        client: SqlClient,
        encounterId: String,
        amount: BigDecimal,
        operator: String,
        now: OffsetDateTime,
    ): Future<JsonObject> {
        if (amount.signum() == 0) {
            return Future.succeededFuture(emptyResult(encounterId))
        }
        return lockEncounter(client, encounterId).compose {
            targetBills(client, encounterId).compose { targets ->
                depositBalance(client, encounterId).compose { balance ->
                    val targetTotal = targets.fold(BigDecimal.ZERO) { acc, bill -> acc.add(bill.balance) }
                    val limit = balance.min(targetTotal)
                    if (amount > limit) {
                        Future.failedFuture(
                            IllegalArgumentException(
                                "deposit offset exceeds available: available $limit, requested $amount",
                            ),
                        )
                    } else {
                        val allocations = allocate(amount, targets.map { it.balance })
                        val writes = targets.zip(allocations).filter { (_, allocation) ->
                            allocation.signum() > 0
                        }
                        var chain: Future<Unit> = Future.succeededFuture(Unit)
                        for ((bill, allocation) in writes) {
                            chain = chain.compose { writeAllocation(client, encounterId, bill, allocation, operator, now) }
                        }
                        val written = writes.map { (bill, allocation) -> bill to allocation }
                        chain.map { result(encounterId, written) }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  内部实现：查询与校验
    // ========================================================================

    /** 事务内按 encounter 行锁读：不存在 404；已收束 409。 */
    private fun lockEncounter(client: SqlClient, encounterId: String): Future<Row> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId)).forUpdate()).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("encounter not found: $encounterId"),
                )
            if (row.getOffsetDateTime("settled_at") != null) {
                Future.failedFuture(ConflictException("encounter billing is already settled"))
            } else {
                Future.succeededFuture(row)
            }
        }

    /**
     * 该 encounter 全部 `待缴费` 账单及其累计缴费与余额，一次查询取回；
     * 只保留余额 > 0 的行，按 period_start 升序、同账期按 id 升序。
     */
    private fun targetBills(client: SqlClient, encounterId: String): Future<List<TargetBill>> {
        val paid = DSL.coalesce(DSL.sum(PAYMENTS.AMOUNT), BigDecimal.ZERO)
        val query = ctx.select(
            BILLS.ID,
            BILLS.PERIOD_START,
            BILLS.PERIOD_END,
            BILLS.TOTAL_AMOUNT,
            paid.`as`("paid_amount"),
            BILLS.TOTAL_AMOUNT.subtract(paid).`as`("balance"),
        ).from(BILLS)
            .leftJoin(PAYMENTS).on(PAYMENTS.BILL_ID.eq(BILLS.ID))
            .where(BILLS.ENCOUNTER_ID.eq(encounterId))
            .and(BILLS.STATUS.eq(BillingEngine.STATUS_PENDING))
            .groupBy(BILLS.ID, BILLS.PERIOD_START, BILLS.PERIOD_END, BILLS.TOTAL_AMOUNT)
            .orderBy(BILLS.PERIOD_START.asc(), BILLS.ID.asc())
        return execute(client, query).map { rows ->
            rows.mapNotNull { row ->
                val balance = row.getBigDecimal("balance")
                if (balance == null || balance.signum() <= 0) {
                    null
                } else {
                    TargetBill(
                        id = row.getString("id"),
                        periodStart = row.getLocalDate("period_start"),
                        periodEnd = row.getLocalDate("period_end"),
                        balance = balance,
                    )
                }
            }
        }
    }

    /** 押金余额：口径唯一来自 [DepositService.balance]（Σ登记 − Σ退押 − Σ核销）。 */
    private fun depositBalance(client: SqlClient, encounterId: String): Future<BigDecimal> =
        DepositService.balance(client, encounterId)

    // ========================================================================
    //  内部实现：双台账写入
    // ========================================================================

    /**
     * 单笔分配写入：payments 一行 + deposit_records 一行；
     * 分配额使该账单余额归零时同事务把账单状态置 已结清。
     */
    private fun writeAllocation(
        client: SqlClient,
        encounterId: String,
        bill: TargetBill,
        amount: BigDecimal,
        operator: String,
        now: OffsetDateTime,
    ): Future<Unit> {
        val paymentId = Ulid.generate()
        val paymentMetadata = JsonObject()
            .put("deposit_offset", true)
            .put("encounter_id", encounterId)
        val paymentQuery = ctx.insertInto(PAYMENTS)
            .set(PAYMENTS.ID, paymentId)
            .set(PAYMENTS.BILL_ID, bill.id)
            .set(PAYMENTS.AMOUNT, amount)
            .set(PAYMENTS.METHOD, PaymentService.METHOD_DEPOSIT)
            .set(PAYMENTS.OPERATOR, operator)
            .set(PAYMENTS.METADATA, JSONB.valueOf(paymentMetadata.encode()))
            .set(PAYMENTS.CREATED_AT, now)
            .set(PAYMENTS.UPDATED_AT, now)

        val depositId = Ulid.generate()
        val depositMetadata = JsonObject()
            .put("bill_id", bill.id)
            .put("payment_id", paymentId)
            .put("period_start", bill.periodStart.toString())
            .put("period_end", bill.periodEnd.toString())
        val depositQuery = ctx.insertInto(DEPOSIT_RECORDS)
            .set(DEPOSIT_RECORDS.ID, depositId)
            .set(DEPOSIT_RECORDS.ENCOUNTER_ID, encounterId)
            .set(DEPOSIT_RECORDS.TYPE, TYPE_OFFSET)
            .set(DEPOSIT_RECORDS.AMOUNT, amount)
            .set(DEPOSIT_RECORDS.OPERATOR, operator)
            .set(DEPOSIT_RECORDS.METADATA, JSONB.valueOf(depositMetadata.encode()))
            .set(DEPOSIT_RECORDS.CREATED_AT, now)
            .set(DEPOSIT_RECORDS.UPDATED_AT, now)

        return execute(client, paymentQuery)
            .compose { execute(client, depositQuery) }
            .compose {
                if (amount.compareTo(bill.balance) == 0) {
                    execute(
                        client,
                        ctx.update(BILLS)
                            .set(BILLS.STATUS, BillingEngine.STATUS_PAID)
                            .set(BILLS.UPDATED_AT, now)
                            .where(BILLS.ID.eq(bill.id)),
                    ).map { Unit }
                } else {
                    Future.succeededFuture(Unit)
                }
            }
    }

    // ========================================================================
    //  内部实现：响应
    // ========================================================================

    private fun emptyResult(encounterId: String): JsonObject =
        JsonObject()
            .put("encounter_id", encounterId)
            .put("amount", BigDecimal.ZERO)
            .put("count", 0)
            .put("allocations", JsonArray())

    private fun result(encounterId: String, written: List<Pair<TargetBill, BigDecimal>>): JsonObject {
        val total = written.fold(BigDecimal.ZERO) { acc, (_, amount) -> acc.add(amount) }
        val allocations = JsonArray(
            written.map { (bill, amount) ->
                JsonObject()
                    .put("bill_id", bill.id)
                    .put("period_start", bill.periodStart.toString())
                    .put("period_end", bill.periodEnd.toString())
                    .put("amount", amount)
            },
        )
        return JsonObject()
            .put("encounter_id", encounterId)
            .put("amount", total)
            .put("count", written.size)
            .put("allocations", allocations)
    }

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
