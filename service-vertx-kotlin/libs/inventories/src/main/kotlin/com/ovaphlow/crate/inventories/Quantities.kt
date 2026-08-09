package com.ovaphlow.crate.inventories

import java.math.BigDecimal

/**
 * 从 Vert.x 数值以原始十进制文本构造 [BigDecimal]。
 *
 * 禁止先取 Double 再经 BigDecimal.valueOf 转换：value.toString() 是数值的十进制最短
 * 文本，由该文本构造 BigDecimal 不引入二进制尾差；而 BigDecimal.valueOf(double) 会带入
 * double 的二进制精确值尾差。016 起所有数量/成本只经此入口进入服务端计算。
 */
fun jsonDecimal(value: Any?): BigDecimal? = when (value) {
    null -> null
    is BigDecimal -> value
    else -> value.toString().toBigDecimalOrNull()
}

/** HTTP 数量/成本入口只接受 JSON 十进制文本，避免 JSON number 被解码为 Double 后丢失精度。 */
fun requestDecimalText(value: Any?): BigDecimal? =
    (value as? String)?.toBigDecimalOrNull()

fun stockDecimalValue(value: Any?): BigDecimal =
    stockDecimalValueOrNull(value) ?: BigDecimal.ZERO

fun stockDecimalValueOrNull(value: Any?): BigDecimal? = when (value) {
    null -> null
    is BigDecimal -> value
    is Number -> value.toString().toBigDecimalOrNull()
    else -> value.toString().toBigDecimalOrNull()
}

/** 数量/成本允许的最大小数位（NUMERIC(20,6) / NUMERIC(24,8)） */
const val QUANTITY_DB_SCALE = 6
const val COST_DB_SCALE = 8

/**
 * 基础数量精度校验：等价于 RoundingMode.UNNECESSARY —— 结果小数位不得超过物资
 * quantity_scale 允许的精度，绝不静默四舍五入或进位。片/粒类（scale=0）提交 0.5 即拒绝。
 */
fun validateBaseQuantity(quantity: BigDecimal, quantityScale: Int) {
    if (quantityScale !in 0..6)
        throw IllegalArgumentException("quantity_scale must be between 0 and 6")
    if (quantity <= BigDecimal.ZERO)
        throw IllegalArgumentException("quantity must be positive")
    val effectiveScale = quantity.stripTrailingZeros().scale().coerceAtLeast(0)
    if (effectiveScale > quantityScale)
        throw IllegalArgumentException(
            "quantity exceeds material precision of $quantityScale decimals",
        )
}

/** 每基础单位成本校验：非负且不超过 NUMERIC(24,8) 精度。 */
fun validateUnitCost(unitCost: BigDecimal) {
    if (unitCost < BigDecimal.ZERO)
        throw IllegalArgumentException("unit_cost must not be negative")
    if (unitCost.stripTrailingZeros().scale() > COST_DB_SCALE)
        throw IllegalArgumentException("unit_cost exceeds precision of $COST_DB_SCALE decimals")
}
