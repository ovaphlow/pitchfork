package com.ovaphlow.crate.database

import io.vertx.sqlclient.DatabaseException

/**
 * 唯一约束冲突判定（051 §3 D2）。
 *
 * Vert.x PG 驱动正常情况下把冲突直接抛成 [DatabaseException]（PG 场景为
 * `io.vertx.pgclient.PgException`），`sqlState == "23505"`；但异常也可能被
 * 上层包装（`cause` 链），或驱动只给出约束名而没有稳定的 sqlState。此前 dining
 * 各处只用**单层** `error is PgException && sqlState == "23505"` 判定，被包装
 * 时就漏判 → 唯一冲突退化成 500。
 *
 * 这里把判定收敛为一个纯函数：遍历 `cause` 链（含自身），命中
 * `sqlState == "23505"`，或消息里含任一 [constraintNames]（约束名兜底）。
 *
 * @param error 待判定的异常
 * @param constraintNames 该冲突点对应的唯一索引名（可选，用于驱动未给 sqlState 的兜底）
 */
fun isUniqueViolation(error: Throwable, constraintNames: Set<String> = emptySet()): Boolean {
    var current: Throwable? = error
    // 防自引用 cause 链导致的死循环（异常链理论上有限，这里仍兜一层）。
    var guard = 0
    while (current != null && guard < 64) {
        if (current is DatabaseException && current.sqlState == "23505") return true
        val message = current.message
        if (message != null && constraintNames.any(message::contains)) return true
        current = current.cause
        guard++
    }
    return false
}
