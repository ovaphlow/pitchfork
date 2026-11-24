package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * 押金台账服务（养老费用管理独立子任务）。
 *
 * 业务规则（服务端强制）：
 *  1. 登记与退押为同表两类记录（type 中文枚举 登记/退押，应用层白名单管控）；
 *     挂 encounter，不强制关联费用项目字典。核销为第三类记录（type 核销）：
 *     由 [DepositOffsetService] 在结算收束时按账期逐笔写入，**不提供独立核销入口**，
 *     登记/退押接口不接受该 type。
 *  2. 金额 NUMERIC(12,2)：登记/退押/核销均为正数且至多两位小数；
 *     余额 = Σ登记 − Σ退押 − Σ核销，退押不得超余额（事务内按 encounter 行锁串行化，
 *     余额不为负）。核销计入减项后，退押上限判定自动按收紧后的余额生效。
 *  3. 退押为独立操作：不校验 encounter 收束状态（status/discharge_date/
 *     death_date），离院/去世后仍可退押。
 *  4. operator 一律取认证主体（userId），客户端不得提交；
 *     写接口按白名单校验字段（amount/remark/metadata）。
 *  5. 台账按 encounter 倒序分页查询，返回 {records, meta:{total, balance}}；
 *     核销记录出现在同一台账（metadata 带 bill_id/payment_id/period_start/period_end）；
 *     空台账 records: [] 且 total: 0。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class DepositService(
    private val pool: Pool,
) {
    companion object {
        const val TYPE_DEPOSIT = "登记"
        const val TYPE_REFUND = "退押"

        /** 结算收束的押金核销减项（由 [DepositOffsetService] 写入） */
        const val TYPE_OFFSET = "核销"

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        /** 写白名单：operator/type/created_at/updated_at/id 由服务端管控 */
        private val createKeys = setOf("amount", "remark", "metadata")

        private const val COLUMNS =
            "id, encounter_id, type, amount, operator, remark, metadata, created_at, updated_at"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.deposit_records WHERE encounter_id = \$1"

        private const val LEDGER_AMOUNTS_SQL =
            "SELECT type, amount FROM healthcare.deposit_records WHERE encounter_id = \$1"

        private const val LIST_SQL =
            "SELECT $COLUMNS FROM healthcare.deposit_records WHERE encounter_id = \$1 " +
                "ORDER BY created_at DESC, id DESC LIMIT \$2 OFFSET \$3"

        /** 事务内按 encounter 行锁读（不校验收束状态：离院/去世仍可退押）。 */
        private const val ENCOUNTER_LOCK_SQL =
            "SELECT id FROM healthcare.encounters WHERE id = \$1 FOR UPDATE"

        /** 台账记录写入：remark/metadata 可空，占位符位置固定以便按位置绑定。 */
        private const val INSERT_SQL =
            "INSERT INTO healthcare.deposit_records " +
                "(id, encounter_id, type, amount, operator, created_at, updated_at, remark, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        /**
         * 余额计算（纯函数）：余额 = Σ登记 − Σ退押 − Σ核销。
         * 调用方保证不出现使余额为负的退押（createRefund 事务内校验）。
         */
        internal fun balanceOf(entries: List<Pair<String, BigDecimal>>): BigDecimal {
            var balance = BigDecimal.ZERO
            for ((type, amount) in entries) {
                balance = when (type) {
                    TYPE_DEPOSIT -> balance.add(amount)
                    TYPE_REFUND -> balance.subtract(amount)
                    TYPE_OFFSET -> balance.subtract(amount)
                    else -> balance
                }
            }
            return balance
        }

        /**
         * 押金余额（只读）：口径唯一来自 [balanceOf]（Σ登记 − Σ退押 − Σ核销）。
         *
         * 与实例台账读取（[ledgerRows] / [listDeposits]）同表、同过滤器、同一余额公式，
         * 不写任何行、可反复调用；供结算预览（`BillService.previewSettlement`）与
         * 核销上限（[DepositOffsetService]）共用，避免各处重复查询与重复计算余额。
         */
        fun balance(client: SqlClient, encounterId: String): Future<BigDecimal> =
            client.preparedQuery(LEDGER_AMOUNTS_SQL).execute(Tuple.of(encounterId))
                .map { rows ->
                    balanceOf(rows.map { row -> row.getString("type") to row.getBigDecimal("amount") })
                }

        private fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("encounter_id", row.getString("encounter_id"))
                .put("type", row.getString("type"))
                .put("amount", row.getBigDecimal("amount"))
                .put("operator", row.getString("operator"))
                .put("remark", row.getString("remark"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  登记押金
    // ========================================================================

    /** 登记押金：encounter 必须存在（404）；金额正数（400）；operator 取认证主体。 */
    fun createDeposit(encounterId: String, body: JsonObject, operator: String): Future<JsonObject> {
        val fields = try {
            validate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireEncounter(connection, encounterId).compose {
                val (sql, args) = insertStatement(id, encounterId, TYPE_DEPOSIT, fields, operator, now)
                execute(connection, sql, args)
                    .map { createdJson(id, encounterId, TYPE_DEPOSIT, fields, operator, now) }
            }
        }
    }

    // ========================================================================
    //  退押
    // ========================================================================

    /**
     * 退押：encounter 必须存在（404）；金额正数（400）；
     * 累计退押不得超过当前余额（400）。不校验 encounter 收束状态，
     * 离院/去世后仍可退押（退押为独立操作，与结算收束无关）。
     */
    fun createRefund(encounterId: String, body: JsonObject, operator: String): Future<JsonObject> {
        val fields = try {
            validate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireEncounter(connection, encounterId).compose {
                ledgerRows(connection, encounterId).compose { rows ->
                    val balance = balanceOf(rows)
                    if (fields.amount > balance) {
                        Future.failedFuture(
                            IllegalArgumentException(
                                "退押金额超出押金余额（可用 ${balance.money()}，本次申请 ${fields.amount.money()}）",
                            ),
                        )
                    } else {
                        val (sql, args) = insertStatement(id, encounterId, TYPE_REFUND, fields, operator, now)
                        execute(connection, sql, args)
                            .map { createdJson(id, encounterId, TYPE_REFUND, fields, operator, now) }
                    }
                }
            }
        }
    }

    /** 金额文案统一两位小数（与 `amount must have at most 2 decimal places` 口径一致）。 */
    private fun BigDecimal.money(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()

    // ========================================================================
    //  台账
    // ========================================================================

    /** 按 encounter 查询押金台账（登记+退押倒序分页），返回 {records, meta:{total, balance}}。 */
    fun listDeposits(encounterId: String, limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        return execute(pool, COUNT_SQL, Tuple.of(encounterId)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            ledgerRows(pool, encounterId).compose { rows ->
                val balance = balanceOf(rows)
                execute(pool, LIST_SQL, Tuple.of(encounterId, limit, offset)).map { dataRows ->
                    JsonObject()
                        .put("records", JsonArray(dataRows.map(::recordJson)))
                        .put("meta", JsonObject().put("total", total).put("balance", balance))
                }
            }
        }
    }

    // ========================================================================
    //  内部实现
    // ========================================================================

    private data class Fields(val amount: BigDecimal, val remark: String?, val metadata: JsonObject?)

    private fun validate(body: JsonObject): Fields {
        rejectForbiddenKeys(body, createKeys, "deposit")
        val amount = numericAmount(body)
        val remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
        }
        val metadata = jsonObject(body, "metadata")
        return Fields(amount, remark, metadata)
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

    /** 事务内按 encounter 行锁读：不存在 404；不校验收束状态（离院/去世仍可退押）。 */
    private fun requireEncounter(client: SqlClient, encounterId: String): Future<Row> =
        execute(client, ENCOUNTER_LOCK_SQL, Tuple.of(encounterId)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    /** 台账明细行（type/amount），余额由 [balanceOf] 纯函数计算。 */
    private fun ledgerRows(client: SqlClient, encounterId: String): Future<List<Pair<String, BigDecimal>>> =
        execute(client, LEDGER_AMOUNTS_SQL, Tuple.of(encounterId)).map { rows ->
            rows.map { row ->
                row.getString("type") to row.getBigDecimal("amount")
            }
        }

    private fun insertStatement(
        id: String,
        encounterId: String,
        type: String,
        fields: Fields,
        operator: String,
        now: OffsetDateTime,
    ): Pair<String, Tuple> = INSERT_SQL to Tuple.of(
        id,
        encounterId,
        type,
        fields.amount,
        operator,
        now,
        now,
        fields.remark,
        fields.metadata,
    )

    private fun createdJson(
        id: String,
        encounterId: String,
        type: String,
        fields: Fields,
        operator: String,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("type", type)
            .put("amount", fields.amount)
            .put("operator", operator)
            .put("remark", fields.remark)
            .put("metadata", fields.metadata)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun execute(client: SqlClient, sql: String, args: Tuple): Future<RowSet<Row>> =
        client.preparedQuery(sql).execute(args)
}
