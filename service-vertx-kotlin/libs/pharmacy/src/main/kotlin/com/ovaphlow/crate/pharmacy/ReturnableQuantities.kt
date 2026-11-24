package com.ovaphlow.crate.pharmacy

import io.vertx.core.Future
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal

/**
 * 012 退药可退数量口径（ADR-001）：退药必须引用原发药明细并校验未退数量，
 * 已给药的数量不得作为可用库存退回。
 *
 *     剩余可退 = 实发数量 − 累计已给药数量 − 未取消退药数量（待确认 + 已确认）
 *
 * 「已给药」来自护理给药记录 `nursing.medication_administrations` 的实际给药量
 * （结果「已服 / 部分服」），按 `dispense_item_id` 与原发药明细关联；退药侧只读，
 * 不写护理数据、不改库存。所有查询必须复用调用方事务连接，自身不开启新事务；
 * 一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
internal object ReturnableQuantities {

    /** 消耗发药剂量的给药结果：已服 / 部分服（拒服、漏服、暂缓不消耗数量）。 */
    private val ADMINISTERED_RESULTS = listOf("已服", "部分服")

    /** 剩余可退数量。历史数据可能算出负值，由调用方按业务口径拒绝或截断。 */
    fun remaining(dispensed: BigDecimal, administered: BigDecimal, reserved: BigDecimal): BigDecimal =
        dispensed.subtract(administered).subtract(reserved)

    /**
     * 创建退药单 / 确认入库共用的超退校验。
     *
     * 返回 null 表示可以退；否则返回带中文业务说明的 409 冲突。
     * 提示里同时给出实发／已给药／已退，便于药房判断是补给药记录还是退多了。
     */
    fun overReturnError(
        requested: BigDecimal,
        dispensed: BigDecimal,
        administered: BigDecimal,
        reserved: BigDecimal,
    ): ConflictException? {
        val returnable = remaining(dispensed, administered, reserved)
        if (returnable <= BigDecimal.ZERO) {
            return ConflictException(
                "该发药明细已无可退数量（实发 ${decimalApi(dispensed)}，" +
                    "已给药 ${decimalApi(administered)}，已退 ${decimalApi(reserved)}）",
            )
        }
        if (requested > returnable) {
            return ConflictException(
                "退药数量超出剩余可退数量（剩余可退 ${decimalApi(returnable)}，" +
                    "已给药 ${decimalApi(administered)}）",
            )
        }
        return null
    }

    /** 单条发药明细的累计已给药数量；无给药记录返回 0。 */
    fun administeredQuantity(client: SqlClient, dispenseItemId: String): Future<BigDecimal> =
        administeredQuantities(client, listOf(dispenseItemId))
            .map { it[dispenseItemId] ?: BigDecimal.ZERO }

    /** 批量累计已给药数量；只返回存在给药记录的明细。 */
    fun administeredQuantities(
        client: SqlClient,
        dispenseItemIds: List<String>,
    ): Future<Map<String, BigDecimal>> {
        if (dispenseItemIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val idPlaceholders = dispenseItemIds.indices.joinToString(", ") { "\$${it + 1}" }
        val resultPlaceholders = ADMINISTERED_RESULTS.indices
            .joinToString(", ") { "\$${dispenseItemIds.size + it + 1}" }
        val sql =
            "SELECT ma.dispense_item_id AS dispense_item_id, " +
                "sum(ma.administered_quantity) AS administered_quantity " +
                "FROM nursing.medication_administrations ma " +
                "WHERE ma.dispense_item_id IN ($idPlaceholders) " +
                "AND ma.result IN ($resultPlaceholders) " +
                "GROUP BY ma.dispense_item_id"
        val args = dispenseItemIds.toMutableList<Any>().apply { addAll(ADMINISTERED_RESULTS) }
        return client.preparedQuery(sql).execute(Tuple.from(args))
            .map { rows ->
                val quantities = mutableMapOf<String, BigDecimal>()
                for (row in rows) {
                    val id = row.getString("dispense_item_id") ?: continue
                    quantities[id] = row.getBigDecimal("administered_quantity") ?: BigDecimal.ZERO
                }
                quantities
            }
    }

    /** 单条发药明细的未取消退药数量（待确认 + 已确认）。 */
    fun reservedQuantity(
        client: SqlClient,
        dispenseItemId: String,
        excludeReturnId: String? = null,
    ): Future<BigDecimal> =
        reservedQuantities(client, listOf(dispenseItemId), excludeReturnId)
            .map { it[dispenseItemId] ?: BigDecimal.ZERO }

    /**
     * 批量未取消退药数量（待确认 + 已确认）。
     *
     * 确认入库时传 [excludeReturnId] 排除本单，再叠加本单数量，得到确认后的累计已退，
     * 避免「创建退药单后又补水给药记录」绕过校验。
     */
    fun reservedQuantities(
        client: SqlClient,
        dispenseItemIds: List<String>,
        excludeReturnId: String? = null,
    ): Future<Map<String, BigDecimal>> {
        if (dispenseItemIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val args = dispenseItemIds.toMutableList<Any?>()
        val idPlaceholders = dispenseItemIds.indices.joinToString(", ") { "\$${it + 1}" }
        args.add("CANCELLED")
        val statusPlaceholder = "\$${args.size}"
        var excludeClause = ""
        if (excludeReturnId != null) {
            args.add(excludeReturnId)
            excludeClause = " AND r.id <> \$${args.size}"
        }
        val sql =
            "SELECT ri.dispense_item_id AS dispense_item_id, sum(ri.quantity) AS reserved_quantity " +
                "FROM pharmacy.pharmacy_return_items ri " +
                "JOIN pharmacy.pharmacy_returns r ON r.id = ri.return_id " +
                "WHERE ri.dispense_item_id IN ($idPlaceholders) AND r.status <> $statusPlaceholder" +
                excludeClause +
                " GROUP BY ri.dispense_item_id"
        return client.preparedQuery(sql).execute(Tuple.from(args))
            .map { rows ->
                val quantities = mutableMapOf<String, BigDecimal>()
                for (row in rows) {
                    val id = row.getString("dispense_item_id") ?: continue
                    quantities[id] = row.getBigDecimal("reserved_quantity") ?: BigDecimal.ZERO
                }
                quantities
            }
    }
}
