package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.DepositRecords.DEPOSIT_RECORDS
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
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
 * 押金台账服务（养老费用管理独立子任务）。
 *
 * 业务规则（服务端强制）：
 *  1. 登记与退押为同表两类记录（type 中文枚举 登记/退押，应用层白名单管控）；
 *     挂 encounter，不强制关联费用项目字典，结算收束不自动冲抵押金。
 *  2. 金额 NUMERIC(12,2)：登记/退押均为正数且至多两位小数；
 *     余额 = Σ登记 − Σ退押，退押不得超余额（事务内按 encounter 行锁串行化，
 *     余额不为负）。
 *  3. 退押为独立操作：不校验 encounter 收束状态（status/discharge_date/
 *     death_date），离院/去世后仍可退押。
 *  4. operator 一律取认证主体（userId），客户端不得提交；
 *     写接口按白名单校验字段（amount/remark/metadata）。
 *  5. 台账按 encounter 倒序分页查询，返回 {records, meta:{total, balance}}；
 *     空台账 records: [] 且 total: 0。
 */
class DepositService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        const val TYPE_DEPOSIT = "登记"
        const val TYPE_REFUND = "退押"

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        /** 写白名单：operator/type/created_at/updated_at/id 由服务端管控 */
        private val createKeys = setOf("amount", "remark", "metadata")

        /**
         * 余额计算（纯函数）：余额 = Σ登记 − Σ退押。
         * 调用方保证不出现使余额为负的退押（createRefund 事务内校验）。
         */
        internal fun balanceOf(entries: List<Pair<String, BigDecimal>>): BigDecimal {
            var balance = BigDecimal.ZERO
            for ((type, amount) in entries) {
                balance = when (type) {
                    TYPE_DEPOSIT -> balance.add(amount)
                    TYPE_REFUND -> balance.subtract(amount)
                    else -> balance
                }
            }
            return balance
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
                execute(connection, insertQuery(id, encounterId, TYPE_DEPOSIT, fields, operator, now))
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
                                "refund exceeds deposit balance: available $balance, requested ${fields.amount}",
                            ),
                        )
                    } else {
                        execute(connection, insertQuery(id, encounterId, TYPE_REFUND, fields, operator, now))
                            .map { createdJson(id, encounterId, TYPE_REFUND, fields, operator, now) }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  台账
    // ========================================================================

    /** 按 encounter 查询押金台账（登记+退押倒序分页），返回 {records, meta:{total, balance}}。 */
    fun listDeposits(encounterId: String, limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(DSL.count().`as`("total")).from(DEPOSIT_RECORDS)
            .where(DEPOSIT_RECORDS.ENCOUNTER_ID.eq(encounterId))
        val dataQuery = ctx.select(
            DEPOSIT_RECORDS.ID,
            DEPOSIT_RECORDS.ENCOUNTER_ID,
            DEPOSIT_RECORDS.TYPE,
            DEPOSIT_RECORDS.AMOUNT,
            DEPOSIT_RECORDS.OPERATOR,
            DEPOSIT_RECORDS.REMARK,
            DEPOSIT_RECORDS.METADATA,
            DEPOSIT_RECORDS.CREATED_AT,
            DEPOSIT_RECORDS.UPDATED_AT,
        ).from(DEPOSIT_RECORDS)
            .where(DEPOSIT_RECORDS.ENCOUNTER_ID.eq(encounterId))
            .orderBy(DEPOSIT_RECORDS.CREATED_AT.desc(), DEPOSIT_RECORDS.ID.desc())
            .limit(limit)
            .offset(offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            ledgerRows(pool, encounterId).compose { rows ->
                val balance = balanceOf(rows)
                execute(pool, dataQuery).map { dataRows ->
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
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId)).forUpdate()).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    /** 台账明细行（type/amount），余额由 [balanceOf] 纯函数计算。 */
    private fun ledgerRows(client: SqlClient, encounterId: String): Future<List<Pair<String, BigDecimal>>> {
        val query = ctx.select(DEPOSIT_RECORDS.TYPE, DEPOSIT_RECORDS.AMOUNT)
            .from(DEPOSIT_RECORDS)
            .where(DEPOSIT_RECORDS.ENCOUNTER_ID.eq(encounterId))
        return execute(client, query).map { rows ->
            rows.map { row ->
                row.getString("type") to row.getBigDecimal("amount")
            }
        }
    }

    private fun insertQuery(
        id: String,
        encounterId: String,
        type: String,
        fields: Fields,
        operator: String,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.insertInto(DEPOSIT_RECORDS)
            .set(DEPOSIT_RECORDS.ID, id)
            .set(DEPOSIT_RECORDS.ENCOUNTER_ID, encounterId)
            .set(DEPOSIT_RECORDS.TYPE, type)
            .set(DEPOSIT_RECORDS.AMOUNT, fields.amount)
            .set(DEPOSIT_RECORDS.OPERATOR, operator)
            .set(DEPOSIT_RECORDS.CREATED_AT, now)
            .set(DEPOSIT_RECORDS.UPDATED_AT, now)
        fields.remark?.let { query = query.set(DEPOSIT_RECORDS.REMARK, it) }
        fields.metadata?.let { query = query.set(DEPOSIT_RECORDS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

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

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
