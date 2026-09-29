package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 029 W1 床位占用只读查询（`GET /crate-api/healthcare/v1/bed-occupancy`）的非数据库测试。
 *
 * 契约（029 §4.1）：`encounter_type = 'ELDERLY_CARE'` 且 `discharge_date IS NULL OR discharge_date > now`；
 * `department`/`ward` 传入时按 `trim` 后精确过滤；`orderBy(department, ward, admit_date)`；
 * 响应 `{ records: [{ encounter_id, encounter_no, patient_id, patient_name, department, ward,
 * admit_date, discharge_date, status }], meta: { total } }`，空结果仍返回 `records: []` 与 `total: 0`。
 *
 * mock 只提供计数行与数据行，不访问数据库；查询形状（过滤/排序/分页）用捕获到的 SQL 与绑定参数断言。
 */
@ExtendWith(VertxExtension::class)
class BedOccupancyTest {

    private class DatabaseStub(
        var countRows: RowSet<Row> = rowSet(),
        var records: RowSet<Row> = rowSet(),
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
                    sql.contains("count(*)") && sql.contains("from healthcare.encounters") ->
                        Future.succeededFuture(countRows)
                    sql.contains("join healthcare.patients") -> Future.succeededFuture(records)
                    else -> Future.succeededFuture(rowSet())
                }
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            queries.add(lastSql)
        }

        fun dataQuery(): String = queries.single { it.contains("join healthcare.patients") }

        fun countQuery(): String = queries.single { it.contains("count(*)") }

        fun dataTuple(): List<Any?> = tuples.single { it.first.contains("join healthcare.patients") }.second
    }

    private fun occupancyRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "department" to "三楼",
            "ward" to "301-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun httpGet(vertx: Vertx, port: Int, path: String): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(HttpMethod.GET, port, "localhost", path)
            .compose { it.send() }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        block: (Int) -> Future<Unit>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { Unit } }
        }
    }

    // ========================================================================
    //  1. 契约结构
    // ========================================================================

    @Test
    fun `床位占用返回契约结构与total`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 1L)), records = rows(occupancyRow()))
        withServer(vertx, stub) { port ->
            httpGet(vertx, port, "/healthcare/v1/bed-occupancy").map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "实际: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(1, records.size())
                    val record = records.getJsonObject(0)
                    assertEquals("enc-1", record.getString("encounter_id"))
                    assertEquals("A20260801001", record.getString("encounter_no"))
                    assertEquals("pat-1", record.getString("patient_id"))
                    assertEquals("张奶奶", record.getString("patient_name"))
                    assertEquals("三楼", record.getString("department"))
                    assertEquals("301-1", record.getString("ward"))
                    assertEquals("2026-08-01T00:00+08:00", record.getString("admit_date"))
                    assertNull(record.getString("discharge_date"), "在住记录 discharge_date 为 null")
                    assertEquals("ACTIVE", record.getString("status"))
                    assertEquals(1L, body.getJsonObject("meta").getLong("total"))
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `查询按养老在住口径过滤并按department ward admit_date升序`() {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 0L)))
        successOf(HealthcareService(stub.pool).listBedOccupancy(null, null, 50, 0))

        val countSql = stub.countQuery()
        assertTrue(countSql.contains("encounters.encounter_type = \$"), "只取养老入住: $countSql")
        assertTrue(
            countSql.contains("encounters.discharge_date is null") && countSql.contains("encounters.discharge_date >"),
            "只取当前占用（null 视为 ∞）: $countSql",
        )
        assertTrue(
            stub.dataQuery().contains(
                "order by healthcare.encounters.department asc, healthcare.encounters.ward asc, healthcare.encounters.admit_date asc",
            ),
            "排序必须是 department, ward, admit_date: ${stub.dataQuery()}",
        )
        assertTrue(stub.dataQuery().contains("join healthcare.patients"), "patient_name 需 join patients")
    }

    // ========================================================================
    //  2. 过滤
    // ========================================================================

    @Test
    fun `department与ward按trim后精确过滤`() {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 0L)))
        successOf(HealthcareService(stub.pool).listBedOccupancy(" 三楼 ", " 301-1 ", 50, 0))

        val dataSql = stub.dataQuery()
        assertTrue(dataSql.contains("encounters.department = \$"), "精确过滤 department: $dataSql")
        assertTrue(dataSql.contains("encounters.ward = \$"), "精确过滤 ward: $dataSql")
        assertTrue(stub.dataTuple().contains("三楼"), "department 必须先 trim: ${stub.dataTuple()}")
        assertTrue(stub.dataTuple().contains("301-1"), "ward 必须先 trim: ${stub.dataTuple()}")
        assertFalse(stub.dataTuple().contains(" 三楼 "), "不得带空白比较: ${stub.dataTuple()}")
    }

    @Test
    fun `空白department与ward视为不过滤`() {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 0L)))
        successOf(HealthcareService(stub.pool).listBedOccupancy("   ", "", 50, 0))

        val dataSql = stub.dataQuery()
        assertFalse(dataSql.contains("encounters.department = \$"), "空白不得生成 department 过滤: $dataSql")
        assertFalse(dataSql.contains("encounters.ward = \$"), "空白不得生成 ward 过滤: $dataSql")
    }

    // ========================================================================
    //  3. 空结果
    // ========================================================================

    @Test
    fun `空结果返回空数组与total0`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 0L)))
        withServer(vertx, stub) { port ->
            httpGet(vertx, port, "/healthcare/v1/bed-occupancy?department=三楼&ward=999-9").map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals(0, body.getJsonArray("records").size(), "空结果仍是空数组: ${body.encode()}")
                    assertEquals(0L, body.getJsonObject("meta").getLong("total"))
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  4. 分页
    // ========================================================================

    @Test
    fun `分页参数透传到查询`() {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 0L)))
        successOf(HealthcareService(stub.pool).listBedOccupancy(null, null, 10, 20))

        val dataSql = stub.dataQuery()
        assertTrue(dataSql.contains("fetch next"), "必须带 limit: $dataSql")
        assertTrue(dataSql.contains("offset \$"), "必须带 offset: $dataSql")
        assertTrue(stub.dataTuple().hasNumber(10), "limit 透传: ${stub.dataTuple()}")
        assertTrue(stub.dataTuple().hasNumber(20), "offset 透传: ${stub.dataTuple()}")
    }

    @Test
    fun `路由层把limit钳制在1到100并忽略负offset`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(countRows = rows(mapOf("total" to 0L)))
        withServer(vertx, stub) { port ->
            httpGet(vertx, port, "/healthcare/v1/bed-occupancy?limit=101&offset=-5").map { (status, _) ->
                ctx.verify {
                    assertEquals(200, status)
                    val tuple = stub.dataTuple()
                    assertTrue(tuple.hasNumber(100), "limit 必须钳制到 100: $tuple")
                    assertTrue(tuple.hasNumber(0), "offset 必须钳制到 0: $tuple")
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
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

private fun rows(vararg values: Map<String, Any?>): RowSet<Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}

/** jOOQ 对 `limit`/`offset` 的绑定值可能是 Integer/Long/BigDecimal，按数值比较。 */
private fun List<Any?>.hasNumber(expected: Int): Boolean =
    any { (it as? Number)?.toInt() == expected }

private fun successOf(future: Future<*>): Any? =
    try {
        future.toCompletionStage().toCompletableFuture().get()
    } catch (error: Throwable) {
        throw AssertionError("expected future to succeed, got: $error")
    }
