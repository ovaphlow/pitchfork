package com.ovaphlow.crate.nursing

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * [ServicePeriodService.enrollElderlyAdmission] 取日口径的非数据库测试。
 *
 * `encounters.admit_date` 是 TIMESTAMPTZ，驱动回读的库值字符串按 UTC 解释，
 * 因此服务周期 `start_date` 必须换算到机构时区 `Asia/Shanghai` 后再取日。
 *
 * 判定样本 `2026-08-06T16:00:00Z` 的机构日是 `2026-08-07`（+08:00 的 00:00）；
 * 直接 `OffsetDateTime.toLocalDate()` 会得到 `2026-08-06`，服务周期整体早一天。
 * 断言同时覆盖落库绑定值与响应字段，二者任一处回退到 UTC 取日都会失败。
 *
 * 反向对照用例 `2026-08-06T02:00:00Z`（机构本地 10:00，同一天）锁定「不得无脑加一天」。
 *
 * mock 只按 normalized SQL 特征提供最小行集，取日判定本身发生在生产代码内。
 */
class ServicePeriodBusinessDateTest {

    /**
     * 按 normalized SQL 特征分发的 mock 桩：先读 encounter，再查是否已有绑定周期，
     * 无既有周期时插入 `ELDERLY_CARE` 周期（`on conflict do nothing` 命中 1 行）。
     */
    private class DatabaseStub(
        var encounter: RowSet<Row> = rowSet(),
        var existingPeriod: RowSet<Row> = rowSet(),
    ) {
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        private var lastSql = ""
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                when {
                    sql.startsWith("insert into nursing.nursing_service_periods") ->
                        Future.succeededFuture(updated(1))
                    sql.contains("from healthcare.encounters") -> Future.succeededFuture(encounter)
                    sql.contains("from nursing.nursing_service_periods") -> Future.succeededFuture(existingPeriod)
                    else -> Future.succeededFuture(rowSet())
                }
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
        }

        /** 取某条 SQL 的落库绑定值；必须唯一命中，否则测试自身报错。 */
        fun boundValues(sqlPrefix: String): List<Any?> =
            tuples.filter { it.first.startsWith(sqlPrefix) }
                .also { assertTrue(it.size == 1, "期望唯一命中 $sqlPrefix，实际: ${tuples.map { pair -> pair.first }}") }
                .single().second
    }

    /** 活动养老入住 encounter，`admit_date` 采用驱动回读的 UTC 解释形式。 */
    private fun elderlyEncounterRow(admitDate: String): Map<String, Any?> = mapOf(
        "id" to "enc-1",
        "patient_id" to "pat-1",
        "encounter_type" to "ELDERLY_CARE",
        "status" to "ACTIVE",
        "admit_date" to OffsetDateTime.parse(admitDate),
    )

    private fun enroll(admitDate: String): Pair<DatabaseStub, JsonObject> {
        val stub = DatabaseStub(encounter = rows(elderlyEncounterRow(admitDate)))
        val (created, period) =
            successOf(ServicePeriodService(stub.pool).enrollElderlyAdmission("enc-1")) as Pair<*, *>
        assertEquals(true, created, "首次补建必须标记为已创建")
        return stub to (period as JsonObject)
    }

    @Test
    fun `养老入住补建周期按机构时区取start_date`() {
        val (stub, period) = enroll("2026-08-06T16:00:00Z")

        // 2026-08-06T16:00Z 即机构本地 2026-08-07 00:00
        assertEquals("2026-08-07", period.getString("start_date"), "响应 start_date 必须是机构日")
        assertEquals("ELDERLY_CARE", period.getString("service_type"))
        assertEquals("enc-1", period.getString("encounter_id"))

        val bound = stub.boundValues("insert into nursing.nursing_service_periods")
        assertTrue(
            bound.contains(LocalDate.of(2026, 8, 7)),
            "落库 start_date 必须是机构日 2026-08-07，实际绑定值: $bound",
        )
        assertFalse(
            bound.contains(LocalDate.of(2026, 8, 6)),
            "落库 start_date 不得是 UTC 取日得到的 2026-08-06，实际绑定值: $bound",
        )
    }

    @Test
    fun `机构本地同日入院不得被加一天`() {
        val (stub, period) = enroll("2026-08-06T02:00:00Z")

        // 2026-08-06T02:00Z 即机构本地 2026-08-06 10:00，机构日仍是 2026-08-06
        assertEquals("2026-08-06", period.getString("start_date"), "响应 start_date 必须是机构日")
        val bound = stub.boundValues("insert into nursing.nursing_service_periods")
        assertTrue(
            bound.contains(LocalDate.of(2026, 8, 6)),
            "落库 start_date 必须是机构日 2026-08-06，实际绑定值: $bound",
        )
        assertFalse(
            bound.contains(LocalDate.of(2026, 8, 7)),
            "落库 start_date 不得被无脑加一天，实际绑定值: $bound",
        )
    }
}

// ——— mock 基础设施（文件级 private：与其它测试文件的同名工具互不可见） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    every { rs.rowCount() } returns rows.size
    return rs
}

private fun updated(affected: Int): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = emptyList<Row>().iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns 0
    every { rs.rowCount() } returns affected
    return rs
}

private fun rows(vararg values: Map<String, Any?>): RowSet<Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}

private fun successOf(future: Future<*>): Any? =
    try {
        future.toCompletionStage().toCompletableFuture().get()
    } catch (error: Throwable) {
        throw AssertionError("expected future to succeed, got: $error")
    }
