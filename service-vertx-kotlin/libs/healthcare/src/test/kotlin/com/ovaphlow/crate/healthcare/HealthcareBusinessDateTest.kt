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
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * P1 取日口径的非数据库测试：护理记录 `record_date`（新建与更正）与养老入住照护周期
 * `start_date` 一律按机构时区 `Asia/Shanghai` 换算，与请求体携带的 offset 无关。
 *
 * 判定样本 `2026-08-06T16:30:00Z` 的机构日是 `2026-08-07`（+08:00 的 00:30）；
 * 按请求体 offset 取日（修改前的 `OffsetDateTime.toLocalDate()`）会得到 `2026-08-06`。
 * 断言同时覆盖落库绑定值（prepared statement tuple）与响应字段，两者都不是「不校验」。
 *
 * mock 只按 normalized SQL 特征提供最小行集；取日判定本身发生在生产代码内。
 */
class HealthcareBusinessDateTest {

    /**
     * 按 normalized SQL 特征分发的 mock 桩，分派顺序即 SQL 形状的区分顺序：
     * `insert`/`update` → 咨询锁 → `for update` → 计数 → 患者 → 护理记录 → 照护周期 →
     * 按 id 读回 encounter → 床位 → 活动入住 → 住院号 → 其余 `from healthcare.encounters`。
     */
    private class DatabaseStub(
        var patients: RowSet<Row> = rowSet(),
        var activeAdmission: RowSet<Row> = rowSet(),
        var encounterNoLookup: RowSet<Row> = rowSet(),
        var bedOccupancy: RowSet<Row> = rowSet(),
        var patientOverlap: RowSet<Row> = rowSet(),
        var lockedEncounter: RowSet<Row> = rowSet(),
        var carePeriodLookup: RowSet<Row> = rowSet(),
        var encounterLookup: RowSet<Row> = rowSet(),
        var nursingRecords: RowSet<Row> = rowSet(),
        var correctionCount: RowSet<Row> = rowSet(),
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                when {
                    sql.startsWith("insert into healthcare.medical_records") -> Future.succeededFuture(updated(1))
                    sql.startsWith("insert into nursing.nursing_service_periods") -> Future.succeededFuture(updated(1))
                    sql.startsWith("insert into healthcare.encounters") -> Future.succeededFuture(updated(1))
                    sql.startsWith("update healthcare.encounters") -> Future.succeededFuture(updated(1))
                    sql.contains("pg_advisory_xact_lock") -> Future.succeededFuture(rowSet())
                    sql.contains("for update") -> Future.succeededFuture(lockedEncounter)
                    sql.contains("count(") -> Future.succeededFuture(correctionCount)
                    sql.contains("from healthcare.patients") -> Future.succeededFuture(patients)
                    sql.contains("from healthcare.medical_records") -> Future.succeededFuture(nursingRecords)
                    sql.contains("from nursing.nursing_service_periods") -> Future.succeededFuture(carePeriodLookup)
                    sql.contains("encounters.id = ") -> Future.succeededFuture(encounterLookup)
                    sql.contains("encounters.ward") || sql.contains("encounters.department") ->
                        Future.succeededFuture(bedOccupancy)
                    sql.contains("encounters.status") -> Future.succeededFuture(activeAdmission)
                    sql.contains("encounters.encounter_no = ") -> Future.succeededFuture(encounterNoLookup)
                    sql.contains("from healthcare.encounters") -> Future.succeededFuture(patientOverlap)
                    else -> Future.succeededFuture(rowSet())
                }
            }
            every { pool.withTransaction<Any>(any()) } answers {
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            queries.add(lastSql)
        }

        /** 取某条 SQL 的落库绑定值；必须唯一命中，否则测试自身报错。 */
        fun boundValues(sqlPrefix: String): List<Any?> =
            tuples.filter { it.first.startsWith(sqlPrefix) }
                .also { assertTrue(it.size == 1, "期望唯一命中 $sqlPrefix，实际: ${tuples.map { pair -> pair.first }}") }
                .single().second
    }

    // ——— fixture 行 ———

    private fun patientRow(): Map<String, Any?> = mapOf(
        "id" to "pat-1",
        "name" to "张奶奶",
        "status" to "ACTIVE",
    )

    private fun originalNursingRecordRow(): Map<String, Any?> = mapOf(
        "id" to "rec-1",
        "encounter_id" to "enc-1",
        "title" to "晨间护理",
        "content" to "长者进食正常",
        "physician" to "nurse-1",
        "record_date" to LocalDate.of(2026, 8, 6),
        "created_at" to OffsetDateTime.parse("2026-08-06T08:00:00+08:00"),
        "metadata" to JsonObject()
            .put("period_id", "per-1")
            .put("record_kind", "MANUAL")
            .put("record_time", "2026-08-06T08:00:00+08:00"),
    )

    private fun nursingRecordBody(recordTime: String): JsonObject = JsonObject()
        .put("period_id", "per-1")
        .put("encounter_id", "enc-1")
        .put("title", "晨间护理")
        .put("content", "长者进食正常，精神可")
        .put("author", "nurse-1")
        .put("record_time", recordTime)

    private fun admissionBody(): JsonObject = JsonObject()
        .put("patient_id", "pat-1")
        .put("encounter_no", "A20260901001")
        .put("admit_date", "2026-08-06T16:00:00Z")
        .put("department", "三楼")
        .put("ward", "301-1")

    // ——— 1. createNursingRecord：record_date 取机构日 ———

    @Test
    fun `新建护理记录按机构时区取record_date且不改写record_time`() {
        val stub = DatabaseStub(
            carePeriodLookup = rows(mapOf("patient_id" to "pat-1")),
            encounterLookup = rows(mapOf("patient_id" to "pat-1")),
        )
        val body = nursingRecordBody("2026-08-06T16:30:00Z")

        val record = successOf(HealthcareService(stub.pool).createNursingRecord(body)) as JsonObject

        // 机构日 = UTC 次日（+08:00 的 00:30）；按请求体 offset 取日会得到 2026-08-06
        assertEquals("2026-08-06T16:30Z", record.getString("record_time"), "record_time 必须逐瞬间原样保留")
        assertEquals("2026-08-07", record.getString("record_date"))
        val bound = stub.boundValues("insert into healthcare.medical_records")
        assertTrue(
            bound.contains(LocalDate.of(2026, 8, 7)),
            "落库 record_date 必须是机构日 2026-08-07，实际绑定值: $bound",
        )
        assertFalse(
            bound.contains(LocalDate.of(2026, 8, 6)),
            "落库 record_date 不得是请求体 offset 的日期 2026-08-06，实际绑定值: $bound",
        )
    }

    // ——— 2. createNursingRecordCorrection：record_date 取机构日 ———

    @Test
    fun `护理记录更正按机构时区取record_date`() {
        val stub = DatabaseStub(
            nursingRecords = rows(originalNursingRecordRow()),
            correctionCount = rows(mapOf("total" to 0L)),
        )
        val body = JsonObject().put("content", "更正：实际进食约六成").put("record_time", "2026-08-06T16:30:00Z")

        val record = successOf(
            HealthcareService(stub.pool).createNursingRecordCorrection("rec-1", body),
        ) as JsonObject

        assertEquals("2026-08-06T16:30Z", record.getString("record_time"), "record_time 必须逐瞬间原样保留")
        assertEquals("2026-08-07", record.getString("record_date"))
        assertEquals("rec-1", record.getString("corrects_record_id"), "更正记录必须指向被更正记录")
        val bound = stub.boundValues("insert into healthcare.medical_records")
        assertTrue(
            bound.contains(LocalDate.of(2026, 8, 7)),
            "落库 record_date 必须是机构日 2026-08-07，实际绑定值: $bound",
        )
        assertFalse(
            bound.contains(LocalDate.of(2026, 8, 6)),
            "落库 record_date 不得是请求体 offset 的日期 2026-08-06，实际绑定值: $bound",
        )
    }

    // ——— 3. admitElderly：照护周期 start_date 取机构日 ———

    @Test
    fun `养老入住照护周期按机构时区取start_date`() {
        val stub = DatabaseStub(patients = rows(patientRow()))

        val result = successOf(HealthcareService(stub.pool).admitElderly(admissionBody(), "user-1")) as JsonObject

        // admit_date 为 2026-08-06T16:00:00Z（+08:00 的 2026-08-07 00:00）
        assertEquals("2026-08-07", result.getJsonObject("nursing_period").getString("start_date"))
        val bound = stub.boundValues("insert into nursing.nursing_service_periods")
        assertTrue(
            bound.contains(LocalDate.of(2026, 8, 7)),
            "落库 start_date 必须是机构日 2026-08-07，实际绑定值: $bound",
        )
        assertFalse(
            bound.contains(LocalDate.of(2026, 8, 6)),
            "落库 start_date 不得是请求体 offset 的日期 2026-08-06，实际绑定值: $bound",
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
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
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
