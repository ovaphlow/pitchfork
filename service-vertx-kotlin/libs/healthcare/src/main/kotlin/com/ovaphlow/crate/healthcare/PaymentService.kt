package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
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
 *  4. 缴费方式中文枚举：现金/转账/银行卡/微信/支付宝/押金
 *     （DB CHECK 兜底 + 应用层白名单校验 400）。其中「押金」不在客户端可提交白名单内：
 *     它只由结算收束的押金核销（[DepositOffsetService]）写入，客户端无法伪造。
 *  5. 欠费列表 = 状态待缴费且余额 > 0 的账单，分页返回
 *     {records, meta:{total}}，记录含 paid_amount（累计缴费）与 balance（余额）。
 *     034 起排除已红冲原单（`reversed_at` 非空）：它已被红字单抵消，不该再显示为欠费；
 *     红字单（负的 total_amount）靠「余额 > 0」判定天然排除。
 *  6. summary 三口径 + 减免单项：
 *     应缴 = Σ账单合计、已缴 = Σ缴费金额、欠费 = Σ待缴费账单余额，
 *     满足 应缴 − 已缴 = 欠费（已结清账单余额恒为 0，由状态机保证）。
 *     核销产生的 payments 行（method = 押金）计入「已缴」，因此该恒等式不变，
 *     此处的「已缴」意为「已收妥，含押金抵扣」，并非现金流入。
 *     减免 = Σ(已结算账单的 outstanding_amount)：收束时被放弃的未结余额快照（021 新增），
 *     减免是收束动作产生的第三项，**不并入欠费**、不改变上面的恒等式。
 *     红冲后应缴靠正负相抵归零（原单 + 红字单），欠费靠排除已红冲原单归零，
 *     两者一致，恒等式仍成立（034 §2.2/§2.6）。
 *  7. 冻结守卫：结算收束后（encounters.settled_at 非空）缴费一律 409。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class PaymentService(
    private val pool: Pool,
) {
    companion object {
        /** 缴费方式中文枚举（DB CHECK 与应用层白名单保持一致） */
        const val METHOD_CASH = "现金"
        const val METHOD_TRANSFER = "转账"
        const val METHOD_BANK_CARD = "银行卡"
        const val METHOD_WECHAT = "微信"
        const val METHOD_ALIPAY = "支付宝"

        /**
         * 结算核销的记账方式：只由 [DepositOffsetService] 在结算收束时写入，
         * **不在客户端可提交白名单内**（见 [methods]），
         * `POST /bills/:id/payments` 提交该值仍返回 400。
         */
        const val METHOD_DEPOSIT = "押金"

        /**
         * 客户端可提交的缴费方式白名单（非法值 400）：保持 5 值不变。
         * 「押金」不放行——核销是写入 method = 押金的唯一路径，DB CHECK 的放宽
         * 只为服务端内部写入服务，不构成客户端入口。
         */
        val methods = setOf(METHOD_CASH, METHOD_TRANSFER, METHOD_BANK_CARD, METHOD_WECHAT, METHOD_ALIPAY)

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        /** 写白名单：bill_id/operator/created_at/updated_at/id 由服务端管控 */
        private val createKeys = setOf("amount", "method", "remark", "metadata")

        private const val COLUMNS =
            "healthcare.payments.id, healthcare.payments.bill_id, healthcare.payments.amount, " +
                "healthcare.payments.method, healthcare.payments.operator, healthcare.payments.remark, " +
                "healthcare.payments.metadata, healthcare.payments.created_at, healthcare.payments.updated_at"

        /** 账单行锁读（事务内串行化缴费）：不存在 404；状态/合计由调用方校验。 */
        private const val REQUIRE_BILL_SQL =
            "SELECT healthcare.bills.id, healthcare.bills.encounter_id, healthcare.bills.status, " +
                "healthcare.bills.total_amount, healthcare.bills.reversal_of, healthcare.bills.reversed_at " +
                "FROM healthcare.bills WHERE healthcare.bills.id = \$1 FOR UPDATE"

        /** 事务内按 encounter 行锁读（冻结守卫）：不存在 404。 */
        private const val REQUIRE_ENCOUNTER_SQL =
            "SELECT healthcare.encounters.settled_at FROM healthcare.encounters " +
                "WHERE healthcare.encounters.id = \$1 FOR UPDATE"

        private const val PAYMENT_AMOUNTS_SQL =
            "SELECT healthcare.payments.amount FROM healthcare.payments " +
                "WHERE healthcare.payments.bill_id = \$1"

        private const val INSERT_PAYMENT_SQL =
            "INSERT INTO healthcare.payments " +
                "(id, bill_id, amount, method, operator, created_at, updated_at, remark, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val MARK_BILL_PAID_SQL =
            "UPDATE healthcare.bills SET status = \$1, updated_at = \$2 WHERE healthcare.bills.id = \$3"

        private const val COUNT_PAYMENTS_SQL =
            "SELECT count(*) AS total FROM healthcare.payments " +
                "WHERE healthcare.payments.bill_id = \$1"

        private const val LIST_PAYMENTS_SQL =
            "SELECT $COLUMNS FROM healthcare.payments WHERE healthcare.payments.bill_id = \$1 " +
                "ORDER BY healthcare.payments.created_at DESC, healthcare.payments.id DESC " +
                "OFFSET \$2 ROWS FETCH NEXT \$3 ROWS ONLY"

        /**
         * 欠费基数（数据与计数共用）：每账单累计缴费派生表 LEFT JOIN；
         * 034：排除已红冲原单（`reversed_at` 非空）——它已被红字单抵消，不再是欠费；
         * 红字单（负的 total_amount）靠 `total_amount > paid` 天然排除，不另加谓词。
         */
        private const val ARREARS_BASE_SQL =
            "SELECT healthcare.bills.id, healthcare.bills.encounter_id, healthcare.bills.period_start, " +
                "healthcare.bills.period_end, healthcare.bills.status, healthcare.bills.total_amount, " +
                "coalesce(ppb.paid, 0) AS paid_amount, " +
                "healthcare.bills.total_amount - coalesce(ppb.paid, 0) AS balance, " +
                "healthcare.bills.created_at, healthcare.bills.updated_at " +
                "FROM healthcare.bills " +
                "LEFT OUTER JOIN (" +
                "SELECT bill_id, sum(amount) AS paid FROM healthcare.payments GROUP BY bill_id" +
                ") ppb ON ppb.bill_id = healthcare.bills.id " +
                "WHERE healthcare.bills.status = \$1 " +
                "AND healthcare.bills.total_amount > coalesce(ppb.paid, 0) " +
                "AND healthcare.bills.reversed_at IS NULL"

        private const val SUMMARY_ARREARS_SQL =
            "SELECT coalesce(sum(healthcare.bills.total_amount - coalesce(ppb.paid, 0)), 0) " +
                "AS arrears_amount " +
                "FROM healthcare.bills " +
                "LEFT OUTER JOIN (" +
                "SELECT bill_id, sum(amount) AS paid FROM healthcare.payments GROUP BY bill_id" +
                ") ppb ON ppb.bill_id = healthcare.bills.id " +
                "WHERE healthcare.bills.status = \$1 " +
                "AND healthcare.bills.total_amount > coalesce(ppb.paid, 0) " +
                "AND healthcare.bills.reversed_at IS NULL"

        private const val SUMMARY_DUE_SQL =
            "SELECT coalesce(sum(healthcare.bills.total_amount), 0) AS due_amount FROM healthcare.bills"

        private const val SUMMARY_PAID_SQL =
            "SELECT coalesce(sum(healthcare.payments.amount), 0) AS paid_amount FROM healthcare.payments"

        /** 减免合计 = Σ(已结算账单的 outstanding_amount)：收束时被放弃的未结余额快照。 */
        private const val SUMMARY_WRITE_OFF_SQL =
            "SELECT coalesce(sum(healthcare.bills.outstanding_amount), 0) AS write_off_amount " +
                "FROM healthcare.bills WHERE healthcare.bills.status = \$1"

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

    /**
     * 034 §2.6 守卫：红字单与已红冲原单一律不可缴费，非正金额账单（0 元封口）
     * 无可收金额也拒绝；返回 null 表示可缴费。
     * 三类用**同一个错误码与异常类型**（400 IllegalArgumentException，与同方法
     * 既有的「非待缴费 400」同口径），逐条消息独立可映射。
     */
    private fun paymentBarrierMessage(bill: Row): String? {
        if (bill.getString("reversal_of") != null) return "bill is a reversal bill, cannot pay"
        if (bill.getOffsetDateTime("reversed_at") != null) return "bill is already reversed, cannot pay"
        val total = bill.getBigDecimal("total_amount")
        if (total != null && total.signum() <= 0) return "bill total must be positive, cannot pay"
        return null
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
                            ConflictException("本次入住的账单已结算，无法继续缴费"),
                        )
                    }
                    if (bill.getString("status") != BillingEngine.STATUS_PENDING) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("账单当前状态不允许缴费（仅待缴费状态可缴）"),
                        )
                    }
                    // 034 §2.6：红字单 / 已红冲原单 / 非正金额账单不可缴费（400，不写流水）
                    val barrier = paymentBarrierMessage(bill)
                    if (barrier != null) {
                        return@compose Future.failedFuture(IllegalArgumentException(barrier))
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
                            execute(
                                connection,
                                INSERT_PAYMENT_SQL,
                                Tuple.of(
                                    id,
                                    billId,
                                    fields.amount,
                                    fields.method,
                                    operator,
                                    now,
                                    now,
                                    fields.remark,
                                    fields.metadata,
                                ),
                            ).compose {
                                val newPaid = paid.add(fields.amount)
                                val settle = if (newPaid.compareTo(total) >= 0) {
                                    execute(
                                        connection,
                                        MARK_BILL_PAID_SQL,
                                        Tuple.of(BillingEngine.STATUS_PAID, now, billId),
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
    fun listPayments(billId: String, limit: Int = 50, offset: Int = 0): Future<JsonObject> =
        execute(pool, COUNT_PAYMENTS_SQL, Tuple.of(billId)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, LIST_PAYMENTS_SQL, Tuple.of(billId, offset, limit)).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }

    // ========================================================================
    //  欠费列表
    // ========================================================================

    /**
     * 欠费列表：状态 待缴费 且 余额 > 0 的账单（余额 = 合计 − 累计缴费），
     * 账期倒序分页，返回 {records, meta:{total}}；空列表 records: [] 且 total: 0。
     * 记录含 paid_amount（累计缴费）与 balance（余额），供结算收束读账。
     *
     * [encounterId] 可选：传入时只返回该入住的欠费账单，**数据与计数同源过滤**
     * （同一 [ARREARS_BASE_SQL] 加同一条件），`meta.total` 为过滤后的行数；
     * 省略时行为与改动前逐字段一致。不存在/非法的 id 只是过滤条件 → 空列表（不返回 404）。
     */
    fun listArrears(encounterId: String? = null, limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val args = mutableListOf<Any?>(BillingEngine.STATUS_PENDING)
        val filter = encounterId?.let {
            args.add(it)
            " AND healthcare.bills.encounter_id = \$2"
        } ?: ""
        val countSql = "SELECT count(*) AS total FROM (" + ARREARS_BASE_SQL + filter + ") arrears"
        val dataSql = ARREARS_BASE_SQL + filter +
            " ORDER BY healthcare.bills.period_start DESC, healthcare.bills.id DESC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"
        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { dataRows ->
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
     * 欠费 = Σ待缴费账单余额、减免 = Σ已结算账单的 outstanding_amount（收束时被放弃的未结快照）。
     * 恒等式 应缴 − 已缴 = 欠费 不变（已结清账单余额恒为 0，由状态机保证）；
     * 减免单独成项、不并入欠费。无数据时四项均为 0。
     */
    fun summary(): Future<JsonObject> =
        execute(pool, SUMMARY_DUE_SQL, Tuple.tuple()).compose { dueRows ->
            execute(pool, SUMMARY_PAID_SQL, Tuple.tuple()).compose { paidRows ->
                execute(pool, SUMMARY_ARREARS_SQL, Tuple.of(BillingEngine.STATUS_PENDING)).compose { arrearsRows ->
                    execute(pool, SUMMARY_WRITE_OFF_SQL, Tuple.of(BillingEngine.STATUS_SETTLED)).map { writeOffRows ->
                        JsonObject()
                            .put("due_amount", dueRows.iterator().next().getBigDecimal("due_amount"))
                            .put("paid_amount", paidRows.iterator().next().getBigDecimal("paid_amount"))
                            .put("arrears_amount", arrearsRows.iterator().next().getBigDecimal("arrears_amount"))
                            .put(
                                "write_off_amount",
                                writeOffRows.iterator().asSequence().firstOrNull()
                                    ?.getBigDecimal("write_off_amount") ?: BigDecimal.ZERO,
                            )
                    }
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
        execute(client, REQUIRE_BILL_SQL, Tuple.of(billId)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("bill not found: $billId"))
        }

    /** 事务内按 encounter 行锁读（冻结守卫）：不存在 404。 */
    private fun requireEncounter(client: SqlClient, encounterId: String): Future<Row> =
        execute(client, REQUIRE_ENCOUNTER_SQL, Tuple.of(encounterId)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    /** 账单已缴明细金额，累计由 [paidOf] 纯函数计算。 */
    private fun paymentAmounts(client: SqlClient, billId: String): Future<List<BigDecimal>> =
        execute(client, PAYMENT_AMOUNTS_SQL, Tuple.of(billId)).map { rows ->
            rows.map { row -> row.getBigDecimal("amount") }
        }

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

    private fun execute(client: SqlClient, sql: String, args: Tuple): Future<RowSet<Row>> =
        client.preparedQuery(sql).execute(args)
}
