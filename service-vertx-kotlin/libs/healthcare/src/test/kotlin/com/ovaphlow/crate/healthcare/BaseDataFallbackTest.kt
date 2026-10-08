package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * 054 F2 基础数据：**读库优先 / 常量回落等价性**测试（不访问数据库）。
 *
 * 核心验收（054 §8.6）：**内容表为空（或读失败）时，052/053 的判定必须与改造前逐字节一致**——
 *  - 覆盖层置空 ⇒ [ChildVaccinationService.effectiveSchedule] **就是**常量排程（同一实例）；
 *  - 覆盖层置空 ⇒ [DevelopmentalScaleCatalog.effectiveScales] **就是**常量基线（同一实例）；
 *  - [DevelopmentalScoring] 的计分/判读结果与改造前完全相同；
 *  - 内容表有启用记录 ⇒ 读库优先（覆盖层生效，覆盖常量；常量基线仍可回落）。
 *
 * 覆盖层是进程内缓存，故每个用例前后都重置，避免污染其它测试类。
 */
class BaseDataFallbackTest {

    private var lastSql: String = ""

    @BeforeEach
    @AfterEach
    fun resetOverlays() {
        ChildVaccinationService.applyContentSchedule(emptyList())
        DevelopmentalScaleCatalog.applyContent(emptyList())
        lastSql = ""
    }

    @Test
    fun `库为空时疫苗排程与发育量表完全等价于常量基线`() {
        val placeholder = DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE
        val scale = DevelopmentalScaleCatalog.find(placeholder)!!
        val answers = scale.items
            .filter { it.minMonths <= 0 && it.maxMonths >= 0 }
            .associate { it.code to it.options.maxOf { option -> option.value } }
        val before = DevelopmentalScoring.score(placeholder, 0, answers)

        BaseDataService(emptyPool()).refreshAll().await()

        assertSame(
            ChildVaccinationService.VACCINATION_SCHEDULE,
            ChildVaccinationService.effectiveSchedule(),
            "库为空时生效排程必须就是常量排程（同一实例）",
        )
        assertSame(
            DevelopmentalScaleCatalog.scales,
            DevelopmentalScaleCatalog.effectiveScales(),
            "库为空时生效量表必须就是常量基线（同一实例）",
        )
        assertEquals(3, ChildVaccinationService.maxDose("HEPB"), "常量排程 HEPB 3 剂")
        assertEquals(
            before,
            DevelopmentalScoring.score(placeholder, 0, answers),
            "库为空时筛查判读结果必须与改造前完全一致",
        )
    }

    @Test
    fun `内容表读失败时回落常量基线`() {
        val failingPool = mockk<Pool>()
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { failingPool.preparedQuery(any<String>()) } returns prepared
        every { failingPool.preparedQuery(any<String>(), any()) } returns prepared
        every { prepared.execute() } returns Future.failedFuture(RuntimeException("db unavailable"))
        every { prepared.execute(any<Tuple>()) } returns Future.failedFuture(RuntimeException("db unavailable"))

        BaseDataService(failingPool).refreshAll().await()

        assertSame(ChildVaccinationService.VACCINATION_SCHEDULE, ChildVaccinationService.effectiveSchedule())
        assertSame(DevelopmentalScaleCatalog.scales, DevelopmentalScaleCatalog.effectiveScales())
    }

    @Test
    fun `内容表有启用记录时读库优先并覆盖常量`() {
        val pool = mockk<Pool>()
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
        every { prepared.execute() } answers {
            when {
                lastSql.contains("base_vaccination_types") ->
                    Future.succeededFuture(
                        rowSet(
                            mockRow(
                                mapOf(
                                    "id" to "vt-1",
                                    "code" to "HEPB",
                                    "name" to "乙肝疫苗(库内容)",
                                    "dose_count" to 2,
                                    "recommended_months" to JsonArray().add(0).add(1),
                                    "status" to "启用",
                                    "sort_order" to 0,
                                ),
                            ),
                        ),
                    )
                lastSql.contains("base_developmental_scales") ->
                    Future.succeededFuture(rowSet(mockRow(scaleRowValues())))
                else -> Future.succeededFuture(rowSet())
            }
        }
        every { prepared.execute(any<Tuple>()) } answers { Future.succeededFuture(rowSet()) }

        BaseDataService(pool).refreshAll().await()

        val schedule = ChildVaccinationService.effectiveSchedule()
        assertNotEquals(ChildVaccinationService.VACCINATION_SCHEDULE, schedule, "有内容时必须覆盖常量排程")
        assertEquals(2, schedule.size, "dose_count=2 → 两条排程条目")
        assertEquals("乙肝疫苗(库内容)", schedule[0].name)
        assertEquals(2, ChildVaccinationService.maxDose("HEPB"), "读库优先：HEPB 变为 2 剂")

        val dbScale = DevelopmentalScaleCatalog.find("DB_SCALE")!!
        assertEquals("库量表", dbScale.name)
        assertEquals(1, dbScale.items.size)
        assertEquals(
            DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE,
            DevelopmentalScaleCatalog.find(DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE)!!.code,
            "常量基线仍可用（覆盖层是叠加而非替换）",
        )
        val item = dbScale.items[0]
        val result = DevelopmentalScoring.score("DB_SCALE", 0, mapOf(item.code to item.options.maxOf { it.value }))
        assertEquals("DB_SCALE", result.scaleCode)
    }

    // ========================================================================
    //  脚手架
    // ========================================================================

    private fun record(sql: String) {
        lastSql = sql.lowercase().replace("\"", "")
    }

    private fun emptyPool(): Pool {
        val pool = mockk<Pool>()
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } returns prepared
        every { pool.preparedQuery(any<String>(), any()) } returns prepared
        every { prepared.execute() } returns Future.succeededFuture(rowSet())
        every { prepared.execute(any<Tuple>()) } returns Future.succeededFuture(rowSet())
        return pool
    }

    private fun <T> Future<T>.await(): T = toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
}

// ========================================================================
//  mockk 桩辅助（Kotlin 顶层 private 为文件可见，故本文件自带一份）
// ========================================================================

private fun scaleRowValues(): Map<String, Any?> = mapOf(
    "id" to "ds-1",
    "scale_code" to "DB_SCALE",
    "scale_version" to "v1",
    "name" to "库量表",
    "age_min_months" to 0,
    "age_max_months" to 36,
    "definition" to JsonObject()
        .put("domains", JsonArray().add(JsonObject().put("code", "GM").put("name", "大运动")))
        .put(
            "items",
            JsonArray().add(
                JsonObject()
                    .put("code", "DB_ITEM_1")
                    .put("domain", "GM")
                    .put("prompt", "库内容条目")
                    .put("min_months", 0)
                    .put("max_months", 36)
                    .put(
                        "options",
                        JsonArray()
                            .add(JsonObject().put("value", 0).put("label", "否"))
                            .add(JsonObject().put("value", 1).put("label", "是")),
                    ),
            ),
        ),
    "status" to "启用",
    "created_at" to OffsetDateTime.now(),
    "updated_at" to OffsetDateTime.now(),
)

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getJsonArray(any<String>()) } answers { values[firstArg<String>()] as? JsonArray }
    every { row.getJsonObject(any<String>()) } answers { values[firstArg<String>()] as? JsonObject }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val iterator = mockk<io.vertx.sqlclient.RowIterator<Row>>()
        every { iterator.hasNext() } answers { delegate.hasNext() }
        every { iterator.next() } answers { delegate.next() }
        iterator
    }
    every { rs.size() } returns rows.size
    return rs
}
