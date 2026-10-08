package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.isUniqueViolation
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * 儿保服务发票台账服务（054 E3，迁移 `V538`）。
 *
 * ## 范围与边界（054 §3 Df / §8.5 / §11.2 Q9）
 * 儿保发票**独立于 `bills`**：本服务只读写 `healthcare.child_service_invoices`，
 * **不触碰** `bills` / `bill_items` / `payments` / `encounters`；只做
 * **发票台账 + 票号 + 状态**，**不做税控对接 / 电子发票开票**（前端本地打印）。
 *
 * ## 规则
 * - `invoice_no` **业务唯一**：客户端可提交纸质票号，缺省服务端生成 `INV-<ULID>`；
 *   重复 → [ConflictException]（409，含并发撞唯一索引的兜底翻译）。
 * - `status` **单向**：`已开具 → 已作废`，`已作废` 为终态；同状态重复提交或反向流转 → 400。
 * - `amount` 服务端派生：关联 `settlement_id` 时取结算单 `total_amount` 快照；
 *   关联 `subscription_id` 时取订阅 `amount` 快照；两者至多其一（DB CHECK 兜底）；
 *   无关联时由客户端提交（正数、至多两位小数）。**已有关联时再提交 `amount` 一律 400**
 *   （避免两个金额事实源）。
 * - 发票只服务 `person_type=儿童` 的患者；`issued_at` / `operator` 服务端派生。
 * - 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class InvoiceService(private val pool: Pool) {

    companion object {
        /** 发票状态白名单（054 §5.8）。 */
        val STATUSES: Set<String> = linkedSetOf("已开具", "已作废")

        const val STATUS_ISSUED = "已开具"
        const val STATUS_VOID = "已作废"

        /** 状态机（纯函数）：仅 `已开具 → 已作废`；同状态/反向/未知一律 `false`。 */
        fun canTransition(from: String, to: String): Boolean = from == STATUS_ISSUED && to == STATUS_VOID

        /** 创建写白名单（`id`/`status`/`issued_at`/`operator`/时间戳由服务端派生）。 */
        private val CREATE_KEYS =
            setOf("invoice_no", "patient_id", "title", "amount", "settlement_id", "subscription_id", "metadata")

        /** 状态更新写白名单。 */
        private val STATUS_KEYS = setOf("status", "metadata")

        private const val MAX_INVOICE_NO = 64

        private const val COLUMNS =
            "id, invoice_no, patient_id, title, amount, status, settlement_id, subscription_id, " +
                "issued_at, operator, metadata, created_at, updated_at"

        private const val BY_ID_SQL =
            "SELECT $COLUMNS FROM healthcare.child_service_invoices WHERE id = \$1"

        private const val LOCK_SQL =
            "SELECT status FROM healthcare.child_service_invoices WHERE id = \$1 FOR UPDATE"

        private const val NO_TAKEN_SQL =
            "SELECT id FROM healthcare.child_service_invoices WHERE invoice_no = \$1 AND id <> \$2"

        private const val INSERT_SQL =
            "INSERT INTO healthcare.child_service_invoices " +
                "(id, invoice_no, patient_id, title, amount, status, settlement_id, subscription_id, " +
                "issued_at, operator, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13)"

        private const val UPDATE_STATUS_SQL =
            "UPDATE healthcare.child_service_invoices SET status = \$2, metadata = \$3, updated_at = \$4 " +
                "WHERE id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.child_service_invoices"

        private const val PATIENT_SQL =
            "SELECT id, person_type FROM healthcare.patients WHERE id = \$1"

        private const val SETTLEMENT_SQL =
            "SELECT id, patient_id, total_amount FROM healthcare.child_service_settlements WHERE id = \$1"

        private const val SUBSCRIPTION_SQL =
            "SELECT id, patient_id, amount FROM healthcare.child_package_subscriptions WHERE id = \$1"

        /** 行 → 发票 JSON（054 §5.7 形状）。 */
        internal fun invoiceJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("invoice_no", row.getString("invoice_no"))
            .put("patient_id", row.getString("patient_id"))
            .put("title", row.getString("title"))
            .put("amount", row.getBigDecimal("amount"))
            .put("status", row.getString("status"))
            .put("settlement_id", row.getString("settlement_id"))
            .put("subscription_id", row.getString("subscription_id"))
            .put("issued_at", row.getOffsetDateTime("issued_at")?.toString())
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  开具与查询
    // ========================================================================

    /**
     * 开具发票：校验儿童档案 → 解析关联与金额（服务端派生）→ 票号唯一 → 落库。
     *
     * 患者/关联单据不存在 → [HealthcareNotFoundException]（404）；非儿童 / 关联非本人 /
     * 票号重复 → [ConflictException]（409）；参数非法 → `IllegalArgumentException`（400）。
     */
    fun create(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val invoiceNo = parsed.invoiceNo ?: "INV-$id"
        return pool.withTransaction<JsonObject> { connection ->
            loadChild(connection, parsed.patientId).compose { _ ->
                resolveAmount(connection, parsed).compose { amount ->
                    ensureInvoiceNoAvailable(connection, invoiceNo).compose {
                        val tuple = Tuple.tuple()
                        tuple.addString(id)
                        tuple.addString(invoiceNo)
                        tuple.addString(parsed.patientId)
                        tuple.addString(parsed.title)
                        tuple.addValue(amount)
                        tuple.addString(STATUS_ISSUED)
                        tuple.addValue(parsed.settlementId)
                        tuple.addValue(parsed.subscriptionId)
                        tuple.addOffsetDateTime(now)
                        tuple.addString(operator)
                        tuple.addValue(parsed.metadata)
                        tuple.addOffsetDateTime(now)
                        tuple.addOffsetDateTime(now)
                        connection.preparedQuery(INSERT_SQL).execute(tuple)
                            .compose { getVia(connection, id) }
                            .mapUniqueViolation("uq_child_service_invoices_no", "invoice_no already exists: $invoiceNo")
                    }
                }
            }
        }
    }

    /** 按 id 取发票；不存在 → [HealthcareNotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> = getVia(pool, id)

    /** 发票台账列表（`patient_id` / `status` 可选）；空结果返回 `records: []` 与 `total: 0`。 */
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
        val listSql = "SELECT $COLUMNS FROM healthcare.child_service_invoices" + where +
            " ORDER BY created_at DESC, id DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"
        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::invoiceJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 发票作废：行锁读取 → 单向流转判定（`已开具 → 已作废`）→ 更新。
     *
     * 不存在 → [HealthcareNotFoundException]（404）；非法状态 / 同状态重复提交 / 反向流转 →
     * `IllegalArgumentException`（400）。
     */
    fun updateStatus(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val request = try {
            rejectUnsupported(body, STATUS_KEYS, "invoice status")
            val next = body.getString("status")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("status is required")
            if (next !in STATUSES) throw IllegalArgumentException("invalid status: $next")
            next
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val metadata = body.getValue("metadata")?.let {
            it as? JsonObject ?: return Future.failedFuture(
                IllegalArgumentException("metadata must be a JSON object"),
            )
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(LOCK_SQL).execute(Tuple.of(id)).compose<JsonObject> { rows ->
                val current = rows.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(
                        HealthcareNotFoundException("child service invoice not found: $id"),
                    )
                val from = current.getString("status")
                if (!canTransition(from, request)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("invalid status transition: $from -> $request"),
                    )
                }
                val tuple = Tuple.of(id, request)
                tuple.addValue(metadata)
                tuple.addOffsetDateTime(now)
                connection.preparedQuery(UPDATE_STATUS_SQL).execute(tuple)
                    .compose { getVia(connection, id) }
            }
        }
    }

    // ========================================================================
    //  内部：读取、校验与解析
    // ========================================================================

    private fun getVia(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(invoiceJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child service invoice not found: $id"))
        }

    private fun ensureInvoiceNoAvailable(client: SqlClient, invoiceNo: String): Future<Unit> =
        client.preparedQuery(NO_TAKEN_SQL).execute(Tuple.of(invoiceNo, "")).compose { rows ->
            if (rows.iterator().hasNext()) {
                Future.failedFuture(ConflictException("invoice_no already exists: $invoiceNo"))
            } else {
                Future.succeededFuture(Unit)
            }
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

    /** 金额解析：关联结算单/订阅时取被关联单据金额快照（服务端派生）；否则用客户端提交金额。 */
    private fun resolveAmount(client: SqlClient, parsed: ParsedInvoice): Future<BigDecimal> {
        parsed.settlementId?.let { settlementId ->
            return client.preparedQuery(SETTLEMENT_SQL).execute(Tuple.of(settlementId)).compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(
                        HealthcareNotFoundException("child service settlement not found: $settlementId"),
                    )
                if (row.getString("patient_id") != parsed.patientId) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("settlement does not belong to patient: $settlementId"),
                    )
                }
                Future.succeededFuture(row.getBigDecimal("total_amount") ?: BigDecimal.ZERO)
            }
        }
        parsed.subscriptionId?.let { subscriptionId ->
            return client.preparedQuery(SUBSCRIPTION_SQL).execute(Tuple.of(subscriptionId)).compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(
                        HealthcareNotFoundException("child package subscription not found: $subscriptionId"),
                    )
                if (row.getString("patient_id") != parsed.patientId) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("subscription does not belong to patient: $subscriptionId"),
                    )
                }
                Future.succeededFuture(row.getBigDecimal("amount") ?: BigDecimal.ZERO)
            }
        }
        return Future.succeededFuture(parsed.amount ?: BigDecimal.ZERO)
    }

    private data class ParsedInvoice(
        val invoiceNo: String?,
        val patientId: String,
        val title: String,
        val amount: BigDecimal?,
        val settlementId: String?,
        val subscriptionId: String?,
        val metadata: JsonObject?,
    )

    private fun parseCreate(body: JsonObject): ParsedInvoice {
        rejectUnsupported(body, CREATE_KEYS, "child service invoice")
        val patientId = requiredText(body, "patient_id")
        val title = requiredText(body, "title")
        val settlementId = body.getString("settlement_id")?.trim()?.takeIf(String::isNotBlank)
        val subscriptionId = body.getString("subscription_id")?.trim()?.takeIf(String::isNotBlank)
        if (settlementId != null && subscriptionId != null) {
            throw IllegalArgumentException("settlement_id and subscription_id are mutually exclusive")
        }
        val hasAmount = body.containsKey("amount") && body.getValue("amount") != null
        if ((settlementId != null || subscriptionId != null) && hasAmount) {
            throw IllegalArgumentException(
                "amount must not be provided when settlement_id/subscription_id is set (server derives it)",
            )
        }
        val amount = if (hasAmount) parseMoney(body.getValue("amount"), "amount") else null
        if (amount != null && amount.signum() <= 0) throw IllegalArgumentException("amount must be positive")
        if (settlementId == null && subscriptionId == null && amount == null) {
            throw IllegalArgumentException("amount is required when no settlement_id/subscription_id is given")
        }
        val invoiceNo = body.getString("invoice_no")?.trim()?.takeIf(String::isNotBlank)
        if (invoiceNo != null && invoiceNo.length > MAX_INVOICE_NO) {
            throw IllegalArgumentException("invoice_no must be at most $MAX_INVOICE_NO characters")
        }
        return ParsedInvoice(
            invoiceNo = invoiceNo,
            patientId = patientId,
            title = title,
            amount = amount,
            settlementId = settlementId,
            subscriptionId = subscriptionId,
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    /** 金额入参：非负、最多 2 位小数（超出直接 400，不静默进位），归一为 2 位小数。 */
    private fun parseMoney(raw: Any?, label: String): BigDecimal {
        val value = when (raw) {
            is BigDecimal -> raw
            is Number -> raw.toString().toBigDecimalOrNull()
                ?: throw IllegalArgumentException("$label must be a number")
            is String -> raw.trim().toBigDecimalOrNull()
                ?: throw IllegalArgumentException("$label must be a number")
            else -> throw IllegalArgumentException("$label must be a number")
        }
        if (value.signum() < 0) throw IllegalArgumentException("$label must not be negative")
        if (value.stripTrailingZeros().scale() > 2) {
            throw IllegalArgumentException("$label exceeds precision of 2 decimals")
        }
        return value.setScale(2, RoundingMode.HALF_UP)
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

    /** 唯一索引兜底（并发同票号）：委托共享判定 [isUniqueViolation]（遍历 cause 链 + `sqlState == 23505` + 约束名兜底）。 */
    private fun <T> Future<T>.mapUniqueViolation(constraint: String, message: String): Future<T> =
        recover { error ->
            if (isUniqueViolation(error, setOf(constraint))) {
                Future.failedFuture(ConflictException(message))
            } else {
                Future.failedFuture(error)
            }
        }
}
