package com.ovaphlow.crate.pharmacy

import java.math.BigDecimal

/**
 * 药房数量/成本入口的十进制文本构造（016 §4.1）。
 *
 * 禁止先取 Double 再经 `BigDecimal.valueOf` 转换：`value.toString()` 是数值的十进制最短
 * 文本，由该文本构造 [BigDecimal] 不引入二进制尾差；而 `BigDecimal.valueOf(double)` 会带入
 * double 的二进制精确值尾差（如 0.1 → 0.1000000000000000055511...）。所有进入服务端计算的
 * 数量/成本必须经此入口构造。
 */
fun decimalText(value: Any?): BigDecimal? = when (value) {
    null -> null
    is BigDecimal -> value
    else -> value.toString().toBigDecimalOrNull()
}

/** HTTP 数量/成本入口只接受 JSON 十进制文本，避免 JSON number 被解码为 Double 后丢失精度。 */
fun requestDecimalText(value: Any?): BigDecimal? =
    (value as? String)?.toBigDecimalOrNull()

fun decimalApi(value: BigDecimal?): String? = value?.toPlainString()

/** 数量列 NUMERIC(20,6)：等价 RoundingMode.UNNECESSARY —— 超 6 位小数直接拒绝，不静默进位。 */
fun validateQuantityPrecision(quantity: BigDecimal, label: String) {
    if (quantity.stripTrailingZeros().scale() > 6)
        throw IllegalArgumentException("$label exceeds precision of 6 decimals")
}

/** 成本列 NUMERIC(24,8)：超 8 位小数直接拒绝，不静默进位。 */
fun validateCostPrecision(cost: BigDecimal, label: String) {
    if (cost.stripTrailingZeros().scale() > 8)
        throw IllegalArgumentException("$label exceeds precision of 8 decimals")
}
