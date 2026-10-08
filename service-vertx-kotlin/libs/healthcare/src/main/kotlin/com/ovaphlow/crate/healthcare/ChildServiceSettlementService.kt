package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 儿保服务结算单与收款服务（054 E2，迁移 `V537`）。
 *
 * ## 范围与边界（054 §3 Df / §8.5）
 * 儿保**即时结算独立于 `bills`**：本服务只读写
 * `healthcare.child_service_settlements` / `child_service_settlement_items` /
 * `child_service_settlement_payments`，**不触碰** `bills` / `bill_items` / `payments` /
 * `encounters`，因此不放宽 `bills.encounter_id NOT NULL`，也不影响养老账期链；
 * **不复刻**账期、押金、红冲、结算关账。
 *
 * ## 金额口径（054 §8.5）
 * - 明细金额 = `unit_price × quantity`，`ROUND_HALF_UP` 到分（复用 [BillingEngine.money]）；
 * - 结算单合计 = 明细金额之和（复用 [BillingEngine.totalOf]）——**服务端计算，客户端提交
 *   `total_amount` / `paid_amount` / `status` / `settlement_no` 一律 400**；
 * - 单笔收款不得使累计收款超过合计 → `IllegalArgumentException`（400，不写入）。
 *
 * ## 状态派生（054 §5.8，收款后服务端派生）
 * ```
 * total_amount = 0                     → 已结清（零元结算单无需收款）
 * paid_amount = 0                      → 待收款
 * paid_amount >= total_amount          → 已结清
 * 否则                                  → 部分收款
 * ```
 * `已作废` 为保留态（054 §5.5 未冻结作废路由，本轮不可达）；收款遇 `已作废` 按最严拒绝（400）。
 *
 * ## 套餐核销联动（054 E1 `pay_state` 闭环）
 * 结算单可带可选 `subscription_id`（054 E1 套餐订阅，须同患者且 `启用`）：
 * 建单时累加订阅 `used_amount`（消费核销），收款时累加订阅 `paid_amount`（实收）；
 * E1 的 `pay_state` 读时派生据此可达「已用完」。
 *
 * ## 其它
 * - 结算单只服务 `person_type=儿童` 的患者；收款方式白名单与 V516 同口径。
 * - `operator` 一律取认证主体（042 口径），客户端不得提交。
 * - 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class ChildServiceSettlementService(private val pool: Pool) {

    companion object {
        /** 结算单状态白名单（054 §5.8）。 */
        val STATUSES: Set<String> = linkedSetOf("待收款", "部分收款", "已结清", "已作废")

        /** 收款方式白名单（与 V516 `payments.method` 同口径）。 */
        val PAYMENT_METHODS: Set<String> = linkedSetOf("现金", "转账", "银行卡", "微信", "支付宝")

        const val STATUS_PENDING = "待收款"
        const val STATUS_PARTIAL = "部分收款"
        const val STATUS_SETTLED = "已结清"
        const val STATUS_VOID = "已作废"

        /**
         * 收款后状态派生（纯函数，供单测与路由复用）。
         *
         * 零元结算单直接 `已结清`；`已作废` 不由本函数产生（保留态）。
         */
        fun deriveStatus(totalAmount: BigDecimal, paidAmount: BigDecimal): String = when {
            totalAmount.signum() == 0 -> STATUS_SETTLED
            paidAmount.signum() <= 0 -> STATUS_PENDING
            paidAmount >= totalAmount -> STATUS_SETTLED
            else -> STATUS_PARTIAL
        }

        /** 明细金额 = 单价 × 数量，`ROUND_HALF_UP` 到分（与 [BillingEngine.money] 同口径）。 */
        fun lineAmount(unitPrice: BigDecimal, quantity: BigDecimal): BigDecimal =
            BillingEngine.money(unitPrice, quantity)

        /** 结算单合计 = 明细金额之和。 */
        fun totalOf(amounts: List<BigDecimal>): BigDecimal = BillingEngine.totalOf(amounts)

        /** 结算单创建写白名单（`id`/`settlement_no`/金额/状态由服务端派生）。 */
        private val CREATE_KEYS = setOf("patient_id", "subscription_id", "items", "metadata")

        /** 收款写白名单（`id`/`settlement_id`/`operator` 由服务端派生）。 */
        private val PAYMENT_KEYS = setOf("amount", "method", "remark", "metadata")

        private const val SETTLEMENT_COLUMNS =
            "id, settlement_no, patient_id, subscription_id, total_amount, paid_amount, status, " +
                "operator, metadata, created_at, updated_at"

        private const val ITEM_COLUMNS =
            "id, settlement_id, sort_order, item_code, item_name, unit_price, quantity, amount"

        private const val SETTLEMENT_BY_ID_SQL =
            "SELECT $SETTLEMENT_COLUMNS FROM healthcare.child_service_settlements WHERE id = \$1"

        private const val SETTLEMENT_LOCK_SQL =
            "SELECT $SETTLEMENT_COLUMNS FROM healthcare.child_service_settlements WHERE id = \$1 FOR UPDATE"

        private const val ITEMS_BY_SETTLEMENT_SQL =
            "SELECT $ITEM_COLUMNS FROM healthcare.child_service_settlement_items " +
                "WHERE settlement_id = \$1 ORDER BY sort_order ASC, id ASC"

        private const val INSERT_SETTLEMENT_SQL =
            "INSERT INTO healthcare.child_service_settlements " +
                "(id, settlement_no, patient_id, subscription_id, total_amount, paid_amount, status, " +
                "operator, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

        private const val INSERT_ITEM_SQL =
            "INSERT INTO healthcare.child_service_settlement_items " +
                "(id, settlement_id, sort_order, item_code, item_name, unit_price, quantity, amount) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val INSERT_PAYMENT_SQL =
            "INSERT INTO healthcare.child_service_settlement_payments " +
                "(id, settlement_id, amount, method, operator, remark, metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val UPDATE_SETTLEMENT_PAID_SQL =
            "UPDATE healthcare.child_service_settlements SET paid_amount = \$2, status = \$3, updated_at = \$4 " +
                "WHERE id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.child_service_settlements"

        private const val PATIENT_SQL =
            "SELECT id, person_type FROM healthcare.patients WHERE id = \$1"

        private const val SUBSCRIPTION_SQL =
            "SELECT id, patient_id, status FROM healthcare.child_package_subscriptions WHERE id = \$1"

        private const val ADD_SUBSCRIPTION_USED_SQL =
            "UPDATE healthcare.child_package_subscriptions " +
                "SET used_amount = used_amount + \$2, updated_at = \$3 WHERE id = \$1"

        private const val ADD_SUBSCRIPTION_PAID_SQL =
            "UPDATE healthcare.child_package_subscriptions " +
                "SET paid_amount = paid_amount + \$2, updated_at = \$3 WHERE id = \$1"

        /** 行 → 结算单头 JSON（不含明细，明细由 [attachItems] 装配）。 */
        internal fun settlementJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("settlement_no", row.getString("settlement_no"))
            .put("patient_id", row.getString("patient_id"))
            .put("subscription_id", row.getString("subscription_id"))
            .put("total_amount", row.getBigDecimal("total_amount"))
            .put("paid_amount", row.getBigDecimal("paid_amount"))
            .put("status", row.getString("status"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 行 → 明细 JSON（054 §5.7：`item_code/item_name/unit_price/quantity/amount`）。 */
        internal fun itemJson(row: Row): JsonObject = JsonObject()
            .put("item_code", row.getString("item_code"))
            .put("item_name", row.getString("item_name"))
            .put("unit_price", row.getBigDecimal("unit_price"))
            .put("quantity", row.getBigDecimal("quantity"))
            .put("amount", row.getBigDecimal("amount"))
    }

    // ========================================================================
    //  结算单
    // ========================================================================

    /**
     * 创建结算单：校验儿童档案（+ 可选套餐订阅）→ 服务端计算明细金额与合计 → 落库。
     *
     * 患者/订阅不存在 → [HealthcareNotFoundException]（404）；非儿童 / 订阅非本人或停用 /
     * 参数非法（含客户端提交服务端字段）→ `IllegalArgumentException`（400）。
     */
    fun create(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            loadChild(connection, parsed.patientId).compose { _ ->
                val subscriptionCheck: Future<Unit> = parsed.subscriptionId?.let { subscriptionId ->
                    loadSubscription(connection, subscriptionId, parsed.patientId)
                } ?: Future.succeededFuture(Unit)

                subscriptionCheck.compose {
                    val headerTuple = Tuple.tuple()
                    headerTuple.addString(id)
                    headerTuple.addString("CSS-$id")
                    headerTuple.addString(parsed.patientId)
                    headerTuple.addValue(parsed.subscriptionId)
                    headerTuple.addValue(parsed.totalAmount)
                    headerTuple.addValue(BigDecimal.ZERO)
                    headerTuple.addString(STATUS_PENDING)
                    headerTuple.addString(operator)
                    headerTuple.addValue(parsed.metadata)
                    headerTuple.addOffsetDateTime(now)
                    headerTuple.addOffsetDateTime(now)
                    connection.preparedQuery(INSERT_SETTLEMENT_SQL).execute(headerTuple).compose {
                        insertItems(connection, id, parsed.lines).compose {
                            val consume: Future<Unit> = parsed.subscriptionId?.let { subscriptionId ->
                                connection.preparedQuery(ADD_SUBSCRIPTION_USED_SQL)
                                    .execute(Tuple.of(subscriptionId, parsed.totalAmount, now))
                                    .map { Unit }
                            } ?: Future.succeededFuture(Unit)
                            consume.compose { getSettlementVia(connection, id) }
                        }
                    }
                }
            }
        }
    }

    /** 按 id 取结算单（含明细）；不存在 → [HealthcareNotFoundException]（404）。 */
    fun getSettlement(id: String): Future<JsonObject> = getSettlementVia(pool, id)

    /** 结算单列表（`patient_id` / `status` 可选，含明细）；空结果返回 `records: []` 与 `total: 0`。 */
    fun list(patientId: String?, status: String?, limit: Int, offset: Int): Future<JsonObject> {
        val patientFilter = patientId?.trim()?.takeIf(String::isNotBlank)
        val statusFilter = status?.trim()?.takeIf(String::isNotBlank)
        if (statusFilter != null && statusFilter !in STATUSES) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $statusFilter"))
        }
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()
        patientFilter?.let { conditions.add("patient_id = \$${values.size + 1}"); values.add(it) }
        statusFilter?.let { conditions.add("status = \$${values.size + 1}"); values.add(it) }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT $SETTLEMENT_COLUMNS FROM healthcare.child_service_settlements" + where +
            " ORDER BY created_at DESC, id DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"
        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).compose { rows ->
                val records = rows.map(Companion::settlementJson)
                attachItems(pool, records).map {
                    JsonObject()
                        .put("records", JsonArray(records))
                        .put("meta", JsonObject().put("total", total))
                }
            }
        }
    }

    // ========================================================================
    //  收款
    // ========================================================================

    /**
     * 登记一笔收款：行锁读取结算单 → 校验（作废态 / 超额）→ 落收款流水 → 更新累计与状态
     * →（带套餐时）累加订阅实收 → 回读结算单。
     *
     * 结算单不存在 → [HealthcareNotFoundException]（404）；金额/方式非法或超额收款 →
     * `IllegalArgumentException`（400，不写入）。
     */
    fun registerPayment(settlementId: String, body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parsePayment(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(SETTLEMENT_LOCK_SQL).execute(Tuple.of(settlementId))
                .compose<JsonObject> { rows ->
                    val current = rows.iterator().asSequence().firstOrNull()
                        ?: return@compose Future.failedFuture(
                            HealthcareNotFoundException("child service settlement not found: $settlementId"),
                        )
                    if (current.getString("status") == STATUS_VOID) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("settlement is void: $settlementId"),
                        )
                    }
                    val total = current.getBigDecimal("total_amount") ?: BigDecimal.ZERO
                    val paid = current.getBigDecimal("paid_amount") ?: BigDecimal.ZERO
                    val newPaid = paid.add(parsed.amount)
                    if (newPaid > total) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException(
                                "payment exceeds outstanding amount: paid=$paid amount=${parsed.amount} total=$total",
                            ),
                        )
                    }
                    val paymentTuple = Tuple.tuple()
                    paymentTuple.addString(Ulid.generate())
                    paymentTuple.addString(settlementId)
                    paymentTuple.addValue(parsed.amount)
                    paymentTuple.addString(parsed.method)
                    paymentTuple.addString(operator)
                    paymentTuple.addValue(parsed.remark)
                    paymentTuple.addValue(parsed.metadata)
                    paymentTuple.addOffsetDateTime(now)
                    connection.preparedQuery(INSERT_PAYMENT_SQL).execute(paymentTuple).compose {
                        val status = deriveStatus(total, newPaid)
                        connection.preparedQuery(UPDATE_SETTLEMENT_PAID_SQL)
                            .execute(Tuple.of(settlementId, newPaid, status, now)).compose {
                                val subscriptionId = current.getString("subscription_id")
                                val addPaid: Future<Unit> = if (subscriptionId == null) {
                                    Future.succeededFuture(Unit)
                                } else {
                                    connection.preparedQuery(ADD_SUBSCRIPTION_PAID_SQL)
                                        .execute(Tuple.of(subscriptionId, parsed.amount, now)).map { Unit }
                                }
                                addPaid.compose { getSettlementVia(connection, id = settlementId) }
                            }
                    }
                }
        }
    }

    // ========================================================================
    //  内部：读取与装配
    // ========================================================================

    private fun getSettlementVia(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(SETTLEMENT_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("child service settlement not found: $id"),
                )
            val json = settlementJson(row)
            client.preparedQuery(ITEMS_BY_SETTLEMENT_SQL).execute(Tuple.of(id)).map { itemRows ->
                json.put("items", JsonArray(itemRows.map(Companion::itemJson)))
            }
        }

    /** 批量装配明细：一次 `IN ($1..$n)` 查询后按 `settlement_id` 分组回填（避免 N+1）。 */
    private fun attachItems(client: SqlClient, records: List<JsonObject>): Future<Unit> {
        if (records.isEmpty()) return Future.succeededFuture(Unit)
        val ids = records.mapNotNull { it.getString("id") }
        if (ids.isEmpty()) return Future.succeededFuture(Unit)
        val placeholders = ids.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = "SELECT $ITEM_COLUMNS FROM healthcare.child_service_settlement_items " +
            "WHERE settlement_id IN ($placeholders) ORDER BY settlement_id ASC, sort_order ASC, id ASC"
        return client.preparedQuery(sql).execute(tupleOf(ids)).map { rows ->
            val grouped = mutableMapOf<String, JsonArray>()
            rows.forEach { row ->
                grouped.getOrPut(row.getString("settlement_id")) { JsonArray() }.add(itemJson(row))
            }
            records.forEach { record ->
                record.put("items", grouped[record.getString("id")] ?: JsonArray())
            }
            Unit
        }
    }

    private fun insertItems(client: SqlClient, settlementId: String, lines: List<ParsedLine>): Future<Unit> {
        if (lines.isEmpty()) return Future.succeededFuture(Unit)
        var chain: Future<Unit> = Future.succeededFuture(Unit)
        lines.forEachIndexed { index, line ->
            chain = chain.compose {
                val tuple = Tuple.tuple()
                tuple.addString(Ulid.generate())
                tuple.addString(settlementId)
                tuple.addValue(index)
                tuple.addString(line.itemCode)
                tuple.addString(line.itemName)
                tuple.addValue(line.unitPrice)
                tuple.addValue(line.quantity)
                tuple.addValue(line.amount)
                client.preparedQuery(INSERT_ITEM_SQL).execute(tuple).map { Unit }
            }
        }
        return chain
    }

    /** 儿童档案校验：不存在 → 404；非 `person_type=儿童` → 400。 */
    private fun loadChild(client: SqlClient, patientId: String): Future<Row> =
        client.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("patient not found: $patientId"),
                )
            if (row.getString("person_type") != ChildHealth.PERSON_TYPE_CHILD) {
                return@compose Future.failedFuture(
                    IllegalArgumentException("patient is not a child (person_type must be 儿童): $patientId"),
                )
            }
            Future.succeededFuture(row)
        }

    /** 可选套餐订阅校验：不存在 → 404；非本人 / 非启用 → 400。 */
    private fun loadSubscription(client: SqlClient, subscriptionId: String, patientId: String): Future<Unit> =
        client.preparedQuery(SUBSCRIPTION_SQL).execute(Tuple.of(subscriptionId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("child package subscription not found: $subscriptionId"),
                )
            if (row.getString("patient_id") != patientId) {
                return@compose Future.failedFuture(
                    IllegalArgumentException("subscription does not belong to patient: $subscriptionId"),
                )
            }
            if (row.getString("status") != "启用") {
                return@compose Future.failedFuture(
                    IllegalArgumentException("subscription is not active: $subscriptionId"),
                )
            }
            Future.succeededFuture(Unit)
        }

    // ========================================================================
    //  内部：解析与校验
    // ========================================================================

    private data class ParsedLine(
        val itemCode: String,
        val itemName: String,
        val unitPrice: BigDecimal,
        val quantity: BigDecimal,
        val amount: BigDecimal,
    )

    private data class ParsedCreate(
        val patientId: String,
        val subscriptionId: String?,
        val lines: List<ParsedLine>,
        val totalAmount: BigDecimal,
        val metadata: JsonObject?,
    )

    private data class ParsedPayment(
        val amount: BigDecimal,
        val method: String,
        val remark: String?,
        val metadata: JsonObject?,
    )

    private fun parseCreate(body: JsonObject): ParsedCreate {
        rejectUnsupported(body, CREATE_KEYS, "child service settlement")
        val patientId = requiredText(body, "patient_id")
        val subscriptionId = body.getString("subscription_id")?.trim()?.takeIf(String::isNotBlank)
        val rawItems = body.getValue("items")
            ?: throw IllegalArgumentException("items is required")
        val items = rawItems as? JsonArray ?: throw IllegalArgumentException("items must be a JSON array")
        if (items.isEmpty) throw IllegalArgumentException("items must not be empty")

        val lines = items.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("items[$index] must be a JSON object")
            val itemCode = item.getString("item_code")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("items[$index].item_code is required")
            val itemName = item.getString("item_name")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("items[$index].item_name is required")
            val unitPrice = parseMoney(
                item.getValue("unit_price") ?: throw IllegalArgumentException("items[$index].unit_price is required"),
                "items[$index].unit_price",
            )
            val quantity = parseQuantity(
                item.getValue("quantity") ?: throw IllegalArgumentException("items[$index].quantity is required"),
                "items[$index].quantity",
            )
            ParsedLine(
                itemCode = itemCode,
                itemName = itemName,
                unitPrice = unitPrice,
                quantity = quantity,
                amount = lineAmount(unitPrice, quantity),
            )
        }
        return ParsedCreate(
            patientId = patientId,
            subscriptionId = subscriptionId,
            lines = lines,
            totalAmount = totalOf(lines.map { it.amount }),
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    private fun parsePayment(body: JsonObject): ParsedPayment {
        rejectUnsupported(body, PAYMENT_KEYS, "child service settlement payment")
        val amount = parseMoney(
            body.getValue("amount") ?: throw IllegalArgumentException("amount is required"),
            "amount",
        )
        if (amount.signum() <= 0) throw IllegalArgumentException("amount must be positive")
        val method = requiredText(body, "method")
        if (method !in PAYMENT_METHODS) {
            throw IllegalArgumentException("invalid method: $method (expected ${PAYMENT_METHODS.joinToString("/")})")
        }
        return ParsedPayment(
            amount = amount,
            method = method,
            remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank),
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    /** 金额入参：非负、最多 2 位小数（超出直接 400，不静默进位），归一为 2 位小数。 */
    private fun parseMoney(raw: Any?, label: String): BigDecimal {
        val value = decimalValue(raw, label)
        if (value.signum() < 0) throw IllegalArgumentException("$label must not be negative")
        if (value.stripTrailingZeros().scale() > 2) {
            throw IllegalArgumentException("$label exceeds precision of 2 decimals")
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP)
    }

    /** 数量入参：正数、最多 3 位小数（与 `NUMERIC(20,3)` 对齐）。 */
    private fun parseQuantity(raw: Any?, label: String): BigDecimal {
        val value = decimalValue(raw, label)
        if (value.signum() <= 0) throw IllegalArgumentException("$label must be positive")
        if (value.stripTrailingZeros().scale() > 3) {
            throw IllegalArgumentException("$label exceeds precision of 3 decimals")
        }
        return value
    }

    private fun decimalValue(raw: Any?, label: String): BigDecimal = when (raw) {
        is BigDecimal -> raw
        is Number -> raw.toString().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$label must be a number")
        is String -> raw.trim().toBigDecimalOrNull()
            ?: throw IllegalArgumentException("$label must be a number")
        else -> throw IllegalArgumentException("$label must be a number")
    }

    private fun optionalJsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectUnsupported(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    private fun tupleOf(values: List<Any>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
