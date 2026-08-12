package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Bills.BILLS
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Payments.PAYMENTS
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 缴费服务（收费闭环的收款环节；养老费用管理）。
 *
 * 业务规则（服务端强制）：
 *  1. 缴费为台账语义：多次部分缴费累加，余额递减；
 *     单笔缴费不得使累计缴费超过账单合计（超缴 400，不写入）；
 *     缴费记录创建后不可修改/删除；operator 一律取认证主体（客户端不得提交）。
 *  2. 余额归零后账单状态流转 待缴费 → 已结清（同一事务内更新）；
 *     部分缴费后余额 > 0 仍为待缴费；非待缴费账单不可缴费（400）。
 *  3. 金额 NUMERIC(12,2)：正数且至多两位小数，上限 9999999999.99。
 *  4. 缴费方式中文枚举：现金/转账/银行卡/微信/支付宝
 *     （DB CHECK 兜底 + 应用层白名单校验 400）。
 *  5. 欠费列表 = 状态待缴费且余额 > 0 的账单，分页返回
 *     {records, meta:{total}}，记录含 paid_amount（累计缴费）与 balance（余额）。
 *  6. summary：应缴 = Σ账单合计、已缴 = Σ缴费金额、欠费 = Σ待缴费账单余额，
 *     满足 应缴 − 已缴 = 欠费（已结清账单余额恒为 0，由状态机保证）。
 *  7. 冻结守卫：结算收束后（encounters.settled_at 非空）缴费一律 409。
 */
class PaymentService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 缴费方式中文枚举（DB CHECK 与应用层白名单保持一致） */
        const val METHOD_CASH = "现金"
        const val METHOD_TRANSFER = "转账"
        const val METHOD_BANK_CARD = "银行卡"
        const val METHOD_WECHAT = "微信"
        const val METHOD_ALIPAY = "支付宝"

        /** 缴费方式白名单：非法值 400 */
        val methods = setOf(METHOD_CASH, METHOD_TRANSFER, METHOD_BANK_CARD, METHOD_WECHAT, METHOD_ALIPAY)

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        /** 写白名单：bill_id/operator/created_at/updated_at/id 由服务端管控 */
        private val createKeys = setOf("amount", "method", "remark", "metadata")

        private fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("bill_id", row.getString("bill_id"))
                .put("amount", row.getBigDecimal("amount"))
                .put("method", row.getString("method"))
                .put("operator", row.getString("operator"))
                .put("remark", row.getString("remark"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 已缴合计（纯函数）：累计缴费 = Σ缴费金额。 */
        internal fun paidOf(amounts: List<BigDecimal>): BigDecimal =
            amounts.fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }
    }

    // ========================================================================
    //  缴费
    // ========================================================================

    /**
     * 缴费：体 {amount, method, remark?, metadata?}；operator 取认证主体。
     * 账单必须存在（404）且状态 待缴费（400）；
     * 累计缴费 + 本次金额不得超过账单合计（超缴 400，不写入）；
     * 余额归零后同事务更新账单状态为 已结清。
     */
    fun createPayment(billId: String, body: JsonObject, operator: String): Future<JsonObject> {
        val fields = try {
            validate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireBill(connection, billId).compose { bill ->
                // 冻结守卫：结算收束后（encounters.settled_at 非空）一律 409，事务内行锁后判定防并发
                requireEncounter(connection, bill.getString("encounter_id")).compose { encounter ->
                    if (encounter.getOffsetDateTime("settled_at") != null) {
                        return@compose Future.failedFuture(
                            ConflictException("encounter billing is settled, cannot pay"),
                        )
                    }
                    if (bill.getString("status") != BillingEngine.STATUS_PENDING) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("bill status is not ${BillingEngine.STATUS_PENDING}, cannot pay"),
                        )
                    }
                    val total = bill.getBigDecimal("total_amount")
                    paymentAmounts(connection, billId).compose { amounts ->
                        val paid = paidOf(amounts)
                        if (fields.amount.add(paid).compareTo(total) > 0) {
                            Future.failedFuture(
                                IllegalArgumentException(
                                    "payment exceeds bill total: bill $total, already paid $paid, requested ${fields.amount}",
                                ),
                            )
                        } else {
                            execute(connection, insertQuery(id, billId, fields, operator, now)).compose {
                                val newPaid = paid.add(fields.amount)
                                val settle = if (newPaid.compareTo(total) >= 0) {
                                    execute(
                                        connection,
                                        ctx.update(BILLS)
                                            .set(BILLS.STATUS, BillingEngine.STATUS_PAID)
                                            .set(BILLS.UPDATED_AT, now)
                                            .where(BILLS.ID.eq(billId)),
                                    ).map { Unit }
                                } else {
                                    Future.succeededFuture()
                                }
                                settle.map { createdJson(id, billId, fields, operator, now) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  缴费流水
    // ========================================================================

    /** 按账单查询缴费流水（倒序分页），返回 {records, meta:{total}}；空流水 records: [] 且 total: 0。 */
    fun listPayments(billId: String, limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(DSL.count().`as`("total")).from(PAYMENTS)
            .where(PAYMENTS.BILL_ID.eq(billId))
        val dataQuery = ctx.select(
            PAYMENTS.ID,
            PAYMENTS.BILL_ID,
            PAYMENTS.AMOUNT,
            PAYMENTS.METHOD,
            PAYMENTS.OPERATOR,
            PAYMENTS.REMARK,
            PAYMENTS.METADATA,
            PAYMENTS.CREATED_AT,
            PAYMENTS.UPDATED_AT,
        ).from(PAYMENTS)
            .where(PAYMENTS.BILL_ID.eq(billId))
            .orderBy(PAYMENTS.CREATED_AT.desc(), PAYMENTS.ID.desc())
            .limit(limit)
            .offset(offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  欠费列表
    // ========================================================================

    /**
     * 欠费列表：状态 待缴费 且 余额 > 0 的账单（余额 = 合计 − 累计缴费），
     * 账期倒序分页，返回 {records, meta:{total}}；空列表 records: [] 且 total: 0。
     * 记录含 paid_amount（累计缴费）与 balance（余额），供结算收束读账。
     */
    fun listArrears(limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        return execute(pool, arrearsCountQuery()).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, arrearsQuery(limit, offset)).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::arrearsJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  汇总
    // ========================================================================

    /**
     * 汇总：应缴 = Σ账单合计（全部账单）、已缴 = Σ缴费金额（全部流水）、
     * 欠费 = Σ待缴费账单余额；恒等式 应缴 − 已缴 = 欠费
     * （已结清账单余额恒为 0，由状态机保证）。无数据时三项均为 0。
     */
    fun summary(): Future<JsonObject> =
        execute(pool, summaryDueQuery()).compose { dueRows ->
            execute(pool, summaryPaidQuery()).compose { paidRows ->
                execute(pool, summaryArrearsQuery()).compose { arrearsRows ->
                    Future.succeededFuture(
                        JsonObject()
                            .put("due_amount", dueRows.iterator().next().getBigDecimal("due_amount"))
                            .put("paid_amount", paidRows.iterator().next().getBigDecimal("paid_amount"))
                            .put("arrears_amount", arrearsRows.iterator().next().getBigDecimal("arrears_amount")),
                    )
                }
            }
        }

    // ========================================================================
    //  内部实现：校验
    // ========================================================================

    private data class Fields(
        val amount: BigDecimal,
        val method: String,
        val remark: String?,
        val metadata: JsonObject?,
    )

    private fun validate(body: JsonObject): Fields {
        rejectForbiddenKeys(body, createKeys, "payment")
        val amount = numericAmount(body)
        val method = paymentMethod(body)
        val remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
        }
        val metadata = jsonObject(body, "metadata")
        return Fields(amount, method, remark, metadata)
    }

    private fun numericAmount(body: JsonObject): BigDecimal {
        val raw = body.getValue("amount") ?: throw IllegalArgumentException("amount is required")
        val value = (raw as? Number)?.toDouble()
            ?: throw IllegalArgumentException("amount must be a number")
        if (!value.isFinite() || value <= 0) {
            throw IllegalArgumentException("amount must be a positive number")
        }
        val decimal = BigDecimal.valueOf(value)
        if (decimal.scale() > 2) {
            throw IllegalArgumentException("amount must have at most 2 decimal places")
        }
        if (decimal > maxAmount) {
            throw IllegalArgumentException("amount must not exceed $maxAmount")
        }
        return decimal
    }

    private fun paymentMethod(body: JsonObject): String {
        val raw = body.getValue("method") ?: throw IllegalArgumentException("method is required")
        val method = raw as? String ?: throw IllegalArgumentException("method must be a string")
        if (method !in methods) {
            throw IllegalArgumentException("method must be one of: ${methods.joinToString("、")}")
        }
        return method
    }

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key)
        if (value == null) return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    // ========================================================================
    //  内部实现：数据库访问
    // ========================================================================

    /** 账单行锁读（事务内串行化缴费）：不存在 404；状态/合计由调用方校验。 */
    private fun requireBill(client: SqlClient, billId: String): Future<Row> =
        execute(
            client,
            ctx.select(
                BILLS.ID,
                BILLS.ENCOUNTER_ID,
                BILLS.STATUS,
                BILLS.TOTAL_AMOUNT,
            ).from(BILLS)
                .where(BILLS.ID.eq(billId))
                .forUpdate(),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("bill not found: $billId"))
        }

    /** 事务内按 encounter 行锁读（冻结守卫）：不存在 404。 */
    private fun requireEncounter(client: SqlClient, encounterId: String): Future<Row> =
        execute(
            client,
            ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId)).forUpdate(),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    /** 账单已缴明细金额，累计由 [paidOf] 纯函数计算。 */
    private fun paymentAmounts(client: SqlClient, billId: String): Future<List<BigDecimal>> {
        val query = ctx.select(PAYMENTS.AMOUNT)
            .from(PAYMENTS)
            .where(PAYMENTS.BILL_ID.eq(billId))
        return execute(client, query).map { rows ->
            rows.map { row -> row.getBigDecimal("amount") }
        }
    }

    /** 账单每账单累计缴费（派生表），欠费列表/汇总共用。 */
    private val paidPerBill = DSL.table(
        DSL.select(PAYMENTS.BILL_ID.`as`("bill_id"), DSL.sum(PAYMENTS.AMOUNT).`as`("paid"))
            .from(PAYMENTS)
            .groupBy(PAYMENTS.BILL_ID),
    ).`as`("ppb")

    private val ppbBillId = DSL.field(DSL.name("ppb", "bill_id"), String::class.java)
    private val ppbPaid = DSL.field(DSL.name("ppb", "paid"), BigDecimal::class.java)

    private fun arrearsBase() =
        ctx.select(
            BILLS.ID,
            BILLS.ENCOUNTER_ID,
            BILLS.PERIOD_START,
            BILLS.PERIOD_END,
            BILLS.STATUS,
            BILLS.TOTAL_AMOUNT,
            DSL.coalesce(ppbPaid, BigDecimal.ZERO).`as`("paid_amount"),
            BILLS.TOTAL_AMOUNT.subtract(DSL.coalesce(ppbPaid, BigDecimal.ZERO)).`as`("balance"),
            BILLS.CREATED_AT,
            BILLS.UPDATED_AT,
        ).from(BILLS)
            .leftJoin(paidPerBill).on(ppbBillId.eq(BILLS.ID))
            .where(BILLS.STATUS.eq(BillingEngine.STATUS_PENDING))
            .and(BILLS.TOTAL_AMOUNT.gt(DSL.coalesce(ppbPaid, BigDecimal.ZERO)))

    private fun arrearsQuery(limit: Int, offset: Int): Query =
        arrearsBase()
            .orderBy(BILLS.PERIOD_START.desc(), BILLS.ID.desc())
            .limit(limit)
            .offset(offset)

    private fun arrearsCountQuery(): Query =
        ctx.select(DSL.count().`as`("total")).from(arrearsBase().asTable("arrears"))

    private fun summaryDueQuery(): Query =
        ctx.select(DSL.coalesce(DSL.sum(BILLS.TOTAL_AMOUNT), BigDecimal.ZERO).`as`("due_amount")).from(BILLS)

    private fun summaryPaidQuery(): Query =
        ctx.select(DSL.coalesce(DSL.sum(PAYMENTS.AMOUNT), BigDecimal.ZERO).`as`("paid_amount")).from(PAYMENTS)

    private fun summaryArrearsQuery(): Query =
        ctx.select(
            DSL.coalesce(
                DSL.sum(BILLS.TOTAL_AMOUNT.subtract(DSL.coalesce(ppbPaid, BigDecimal.ZERO))),
                BigDecimal.ZERO,
            ).`as`("arrears_amount"),
        ).from(BILLS)
            .leftJoin(paidPerBill).on(ppbBillId.eq(BILLS.ID))
            .where(BILLS.STATUS.eq(BillingEngine.STATUS_PENDING))
            .and(BILLS.TOTAL_AMOUNT.gt(DSL.coalesce(ppbPaid, BigDecimal.ZERO)))

    private fun arrearsJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("period_start", row.getLocalDate("period_start")?.toString())
            .put("period_end", row.getLocalDate("period_end")?.toString())
            .put("status", row.getString("status"))
            .put("total_amount", row.getBigDecimal("total_amount"))
            .put("paid_amount", row.getBigDecimal("paid_amount"))
            .put("balance", row.getBigDecimal("balance"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

    private fun insertQuery(
        id: String,
        billId: String,
        fields: Fields,
        operator: String,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(PAYMENTS)
            .set(PAYMENTS.ID, id)
            .set(PAYMENTS.BILL_ID, billId)
            .set(PAYMENTS.AMOUNT, fields.amount)
            .set(PAYMENTS.METHOD, fields.method)
            .set(PAYMENTS.OPERATOR, operator)
            .set(PAYMENTS.CREATED_AT, now)
            .set(PAYMENTS.UPDATED_AT, now)
        fields.remark?.let { query = query.set(PAYMENTS.REMARK, it) }
        fields.metadata?.let { query = query.set(PAYMENTS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    private fun createdJson(
        id: String,
        billId: String,
        fields: Fields,
        operator: String,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("bill_id", billId)
            .put("amount", fields.amount)
            .put("method", fields.method)
            .put("operator", operator)
            .put("remark", fields.remark)
            .put("metadata", fields.metadata)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
