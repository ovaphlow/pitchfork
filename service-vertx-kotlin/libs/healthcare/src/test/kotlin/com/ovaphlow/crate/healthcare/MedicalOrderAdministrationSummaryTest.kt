package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 医嘱侧给药汇总与给药明细（只读）的非数据库测试。
 *
 * 全库 mock 桩按 normalized SQL 特征分发：
 *   - GET /orders/:id 的 administration_summary：已给次数/已给数量/部分服/拒服/
 *     漏服/暂缓次数、已发数量与剩余数量（剩余 = 已发 - 已给，聚合派生不写药房）
 *   - GET /orders/:id/administrations 给药明细列表（按 task 关联，只读）
 *   - 历史执行无给药记录时汇总为零值，读取兼容
 */
class MedicalOrderAdministrationSummaryTest {

    companion object {
        fun row(values: Map<String, Any?>): Row {
        val row = mockk<Row>()
        for ((key, value) in values) {
            every { row.getValue(key) } returns value
            every { row.getString(key) } returns (value as? String)
            every { row.getLong(key) } returns (value as? Long)
            every { row.getOffsetDateTime(key) } returns (value as? OffsetDateTime)
        }
        return row
    }

        fun rowSet(vararg rows: Row): RowSet<Row> {
        val rs = mockk<RowSet<Row>>()
        every { rs.iterator() } answers {
            val delegate = rows.iterator()
            val rowIterator = mockk<RowIterator<Row>>()
            every { rowIterator.hasNext() } answers { delegate.hasNext() }
            every { rowIterator.next() } answers { delegate.next() }
            rowIterator
        }
        every { rs.size() } returns rows.size
        return rs
    }

        fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    }

    /** 按 SQL 特征分发行集；queryRows 覆盖全部 select 查询。 */
    private class DatabaseStub(
        val queryRows: MutableMap<String, RowSet<Row>> = mutableMapOf(),
        var countRows: RowSet<Row> = rowSet(),
    ) {
        val queries = mutableListOf<String>()
        val pool = mockk<Pool>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()

        init {
            every { pool.preparedQuery(any<String>()) } answers {
                queries.add(normalized(firstArg<String>()))
                pq
            }
            every { pool.preparedQuery(any<String>(), any()) } answers {
                queries.add(normalized(firstArg<String>()))
                pq
            }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = queries.last()
                val branch = when {
                    sql.contains("sum(") && sql.contains("medication_administrations") ->
                        queryRows["administration"] ?: rowSet()

                    sql.contains("count(*)") && sql.contains("medication_administrations") -> countRows
                    sql.contains("count(*)") && sql.contains("nursing_task_executions") ->
                        queryRows["execution_summary"] ?: rowSet()

                    sql.contains("medication_administrations") -> queryRows["administration"] ?: rowSet()
                    sql.contains("pharmacy.pharmacy_dispense_items") -> queryRows["dispensed"] ?: rowSet()
                    sql.contains("from healthcare.medical_orders") -> queryRows["order"] ?: rowSet()
                    sql.contains("nursing_task_executions") -> queryRows["execution_summary"] ?: rowSet()
                    else -> rowSet()
                }
                Future.succeededFuture(branch)
            }
        }
    }

    // ——— fixture 行 ———

    private fun orderRow(): Row = row(
        mapOf(
            "id" to "ord-1",
            "encounter_id" to "enc-1",
            "order_type" to "MEDICATION",
            "order_class" to "LONG_TERM",
            "order_content" to "阿司匹林 100mg 每日一次",
            "order_details" to JsonObject().put("drug_name", "阿司匹林").put("unit", "片"),
            "start_time" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "end_time" to null,
            "doctor" to "赵医生",
            "status" to "ACTIVE",
            "nurse_checked_by" to "nurse-1",
            "nurse_checked_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "task_id" to "task-1",
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        ),
    )

    private fun execSummaryRow(status: String, count: Long): Row =
        row(mapOf("status" to status, "cnt" to count))

    private fun adminSummaryRow(result: String, count: Long, qty: BigDecimal?): Row =
        row(mapOf("result" to result, "cnt" to count, "qty" to qty))

    private fun dispensedRow(qty: BigDecimal?): Row =
        row(mapOf("dispensed_qty" to qty))

    private fun administrationRow(overrides: Map<String, Any?> = emptyMap()): Row {
        val base = mutableMapOf<String, Any?>(
            "id" to "adm-1",
            "task_execution_id" to "exec-1",
            "result" to "已服",
            "administered_quantity" to BigDecimal("2"),
            "unit" to "片",
            "dispense_item_id" to "di-1",
            "lot_id" to "lot-1",
            "warehouse" to "中心药房",
            "administered_by" to "user-1",
            "administered_at" to OffsetDateTime.parse("2026-08-01T11:00:00+08:00"),
            "reason" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T11:00:00+08:00"),
            "planned_time" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "task_description" to "阿司匹林 100mg 每日一次",
            "material_id" to "mat-1",
            "material_name" to "阿司匹林",
            "batch_no" to "B20260801",
            "dispense_no" to "DS-1",
        )
        base.putAll(overrides)
        return row(base)
    }

    // ========================================================================
    //  getOrder — administration_summary
    // ========================================================================

    @Test
    fun `医嘱详情给药汇总与实际给药事实一致`() {
        val stub = DatabaseStub(
            queryRows = mutableMapOf(
                "order" to rowSet(orderRow()),
                "execution_summary" to rowSet(
                    execSummaryRow("COMPLETED", 3),
                    execSummaryRow("SKIPPED", 2),
                ),
                // 已服×2 共 4 片 + 部分服×1 共 1 片；拒服 2、漏服 1、暂缓 1
                "administration" to rowSet(
                    adminSummaryRow("已服", 2, BigDecimal("4")),
                    adminSummaryRow("部分服", 1, BigDecimal("1")),
                    adminSummaryRow("拒服", 2, null),
                    adminSummaryRow("漏服", 1, null),
                    adminSummaryRow("暂缓", 1, null),
                ),
                "dispensed" to rowSet(dispensedRow(BigDecimal("10"))),
            ),
        )
        val result = HealthcareService(stub.pool).getOrder("ord-1")
            .toCompletionStage().toCompletableFuture().get()

        val summary = result.getJsonObject("administration_summary")
        assertEquals(3L, summary.getLong("administered_count"), "已给次数 = 已服+部分服")
        assertEquals("5", summary.getString("administered_quantity"), "已给数量 = 已服+部分服数量之和")
        assertEquals(1L, summary.getLong("partial_count"))
        assertEquals(2L, summary.getLong("refused_count"))
        assertEquals(1L, summary.getLong("missed_count"))
        assertEquals(1L, summary.getLong("deferred_count"))
        assertEquals("10", summary.getString("dispensed_quantity"))
        assertEquals("5", summary.getString("remaining_quantity"), "剩余数量 = 已发 - 已给")
        // 执行汇总与既有行为不变
        assertEquals(3L, result.getJsonObject("execution_summary").getLong("COMPLETED"))
        assertEquals(2L, result.getJsonObject("execution_summary").getLong("SKIPPED"))
    }

    @Test
    fun `无给药记录时给药汇总为零值不报错`() {
        val stub = DatabaseStub(
            queryRows = mutableMapOf(
                "order" to rowSet(orderRow()),
                "execution_summary" to rowSet(execSummaryRow("COMPLETED", 1)),
                "dispensed" to rowSet(dispensedRow(BigDecimal("5"))),
            ),
        )
        val result = HealthcareService(stub.pool).getOrder("ord-1")
            .toCompletionStage().toCompletableFuture().get()

        val summary = result.getJsonObject("administration_summary")
        assertEquals(0L, summary.getLong("administered_count"))
        assertEquals("0", summary.getString("administered_quantity"))
        assertEquals(0L, summary.getLong("refused_count"))
        assertEquals(0L, summary.getLong("missed_count"))
        assertEquals(0L, summary.getLong("deferred_count"))
        assertEquals("5", summary.getString("remaining_quantity"))
        assertEquals(0L, result.getJsonObject("execution_summary").getLong("SKIPPED"))
    }

    @Test
    fun `未发药时剩余数量为空且给药数量为零`() {
        val stub = DatabaseStub(
            queryRows = mutableMapOf(
                "order" to rowSet(orderRow()),
                "execution_summary" to rowSet(),
                "administration" to rowSet(adminSummaryRow("拒服", 1, null)),
                "dispensed" to rowSet(dispensedRow(null)),
            ),
        )
        val result = HealthcareService(stub.pool).getOrder("ord-1")
            .toCompletionStage().toCompletableFuture().get()

        val summary = result.getJsonObject("administration_summary")
        assertEquals(1L, summary.getLong("refused_count"))
        assertEquals(0L, summary.getLong("administered_count"))
        assertEquals("0", summary.getString("administered_quantity"))
        assertNull(summary.getString("dispensed_quantity"), "未发药无已发数量")
        assertNull(summary.getString("remaining_quantity"), "未发药无剩余数量")
    }

    // ========================================================================
    //  listOrderAdministrations — 给药明细（只读）
    // ========================================================================

    @Test
    fun `医嘱给药明细列表按task关联返回批次与药品摘要`() {
        val stub = DatabaseStub(
            queryRows = mutableMapOf(
                "administration" to rowSet(
                    administrationRow(),
                    administrationRow(
                        mapOf(
                            "id" to "adm-2",
                            "result" to "拒服",
                            "administered_quantity" to null,
                            "administered_by" to "user-2",
                            "reason" to "长者拒绝服药",
                        ),
                    ),
                ),
            ),
            countRows = rowSet(row(mapOf("total" to 2L))),
        )
        val result = HealthcareService(stub.pool).getOrderAdministrations("ord-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2L, result.getJsonObject("meta").getLong("total"))
        val records = result.getJsonArray("records")
        val first = records.getJsonObject(0)
        assertEquals("已服", first.getString("result"))
        assertEquals("2", first.getString("administered_quantity"))
        assertEquals("B20260801", first.getString("batch_no"))
        assertEquals("阿司匹林", first.getString("material_name"))
        assertEquals("DS-1", first.getString("dispense_no"))
        assertEquals("user-1", first.getString("administered_by"))
        val second = records.getJsonObject(1)
        assertEquals("拒服", second.getString("result"))
        assertNull(second.getString("administered_quantity"), "拒服无给药数量")
        assertEquals("长者拒绝服药", second.getString("reason"))
        // 查询必须按 task 关联过滤，不串其他医嘱
        assertTrue(stub.queries.any { it.contains("t.order_item_id = $") && it.contains("medication_administrations") })
    }
}
