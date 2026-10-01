package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyReturnItems.PHARMACY_RETURN_ITEMS
import com.ovaphlow.crate.database.gen.pharmacy.tables.PharmacyReturns.PHARMACY_RETURNS
import io.vertx.core.Future
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal

/**
 * 012 退药可退数量口径（ADR-001）：退药必须引用原发药明细并校验未退数量，
 * 已给药的数量不得作为可用库存退回。
 *
 *     剩余可退 = 实发数量 − 累计已给药数量 − 未取消退药数量（待确认 + 已确认）
 *
 * 「已给药」来自护理给药记录 `nursing.medication_administrations` 的实际给药量
 * （结果「已服 / 部分服」），按 `dispense_item_id` 与原发药明细关联；退药侧只读，
 * 不写护理数据、不改库存。所有查询必须复用调用方事务连接，自身不开启新事务。
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
    fun administeredQuantity(client: SqlClient, ctx: DSLContext, dispenseItemId: String): Future<BigDecimal> =
        administeredQuantities(client, ctx, listOf(dispenseItemId))
            .map { it[dispenseItemId] ?: BigDecimal.ZERO }

    /** 批量累计已给药数量；只返回存在给药记录的明细。 */
    fun administeredQuantities(
        client: SqlClient,
        ctx: DSLContext,
        dispenseItemIds: List<String>,
    ): Future<Map<String, BigDecimal>> {
        if (dispenseItemIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val table = DSL.table(DSL.name("nursing", "medication_administrations")).`as`("ma")
        val dispenseItemIdField = DSL.field("ma.dispense_item_id", String::class.java)
        val administeredField = DSL.field("ma.administered_quantity", BigDecimal::class.java)
        val resultField = DSL.field("ma.result", String::class.java)
        val query = ctx.select(
            dispenseItemIdField.`as`("dispense_item_id"),
            DSL.sum(administeredField).`as`("administered_quantity"),
        )
            .from(table)
            .where(dispenseItemIdField.`in`(dispenseItemIds))
            .and(resultField.`in`(ADMINISTERED_RESULTS))
            .groupBy(dispenseItemIdField)
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
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
        ctx: DSLContext,
        dispenseItemId: String,
        excludeReturnId: String? = null,
    ): Future<BigDecimal> =
        reservedQuantities(client, ctx, listOf(dispenseItemId), excludeReturnId)
            .map { it[dispenseItemId] ?: BigDecimal.ZERO }

    /**
     * 批量未取消退药数量（待确认 + 已确认）。
     *
     * 确认入库时传 [excludeReturnId] 排除本单，再叠加本单数量，得到确认后的累计已退，
     * 避免「创建退药单后又补水给药记录」绕过校验。
     */
    fun reservedQuantities(
        client: SqlClient,
        ctx: DSLContext,
        dispenseItemIds: List<String>,
        excludeReturnId: String? = null,
    ): Future<Map<String, BigDecimal>> {
        if (dispenseItemIds.isEmpty()) return Future.succeededFuture(emptyMap())
        var condition: Condition = PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID.`in`(dispenseItemIds)
            .and(PHARMACY_RETURNS.STATUS.ne("CANCELLED"))
        excludeReturnId?.let { condition = condition.and(PHARMACY_RETURNS.ID.ne(it)) }
        val query = ctx.select(
            PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID.`as`("dispense_item_id"),
            DSL.sum(PHARMACY_RETURN_ITEMS.QUANTITY).`as`("reserved_quantity"),
        )
            .from(PHARMACY_RETURN_ITEMS)
            .join(PHARMACY_RETURNS).on(PHARMACY_RETURNS.ID.eq(PHARMACY_RETURN_ITEMS.RETURN_ID))
            .where(condition)
            .groupBy(PHARMACY_RETURN_ITEMS.DISPENSE_ITEM_ID)
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
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
