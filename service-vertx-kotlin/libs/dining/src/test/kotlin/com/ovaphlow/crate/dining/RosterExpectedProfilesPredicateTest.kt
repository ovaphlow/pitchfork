package com.ovaphlow.crate.dining

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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 计划 R16（054 rev4）：配餐口径**显式谓词**护栏（**不连数据库**）。
 *
 * 背景：A4b 之后儿童饮食档案 `encounter_id` 为 NULL；它们不进养老配餐名单，靠的是
 * `dining_diet_profiles → healthcare.encounters` 的 **INNER JOIN**（隐式丢行）。
 * 本测试把该口径固定为**显式代码意图**，并防止后人误加行为变更：
 *
 *  1. `RosterService.loadExpectedProfiles`（名单生成）与 `buildManualItem`（手工条目）
 *     两处 SQL **必须**含显式 `p.encounter_id is not null`；
 *  2. 两处 SQL **不得**出现 `encounter_type`（现状没有该过滤；加上会排除
 *     「绑定 ACTIVE 非 ELDERLY_CARE 入住」的档案 = 行为变更）；
 *  3. 两处仍是 INNER JOIN（不得改成 `left outer join`，否则儿童档案会重新进入名单）。
 */
class RosterExpectedProfilesPredicateTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    /** 已执行的 normalized SQL（按执行顺序）。 */
    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        executed.clear()
        pool = mockk<Pool>()
        prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } answers {
            executed.add(normalized(firstArg<String>()))
            prepared
        }
        // 本测试只关心「发了哪些 SQL」：名单容器返回一行（让流程走到档案查询），档案查询返回空
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("dining_diet_profiles") -> Future.succeededFuture(rowSet())
                sql.contains("dining_roster_items") -> Future.succeededFuture(rowSet())
                sql.contains("dining_rosters") -> Future.succeededFuture(rowSet(rosterRow()))
                else -> Future.succeededFuture(rowSet())
            }
        }
        every { prepared.execute() } answers { Future.succeededFuture(rowSet()) }
    }

    @Test
    fun `名单生成的配餐口径含显式encounter_id谓词且不过滤encounter_type`() {
        generate()

        val sql = executed.firstOrNull { it.contains("dining_diet_profiles") && it.contains("healthcare.encounters") }
        assertTrue(sql != null, "生成名单必须查启用档案 ∩ 在院入住，实际执行: $executed")
        assertTrue(
            sql!!.contains("p.encounter_id is not null"),
            "必须显式写出 `p.encounter_id IS NOT NULL`（儿童档案不进养老配餐的显式意图）：$sql",
        )
        assertFalse(
            sql.contains("encounter_type"),
            "不得引入 encounter_type 过滤（现状没有该过滤，加上会改变行为）：$sql",
        )
        assertFalse(
            sql.contains("left outer join") || sql.contains("left join"),
            "在院口径依赖 INNER JOIN；改成 LEFT JOIN 会让儿童档案重新进名单：$sql",
        )
        // jOOQ 把 ACTIVE 作为绑定参数（`e.status = $n`），SQL 文本里看不到字面量
        assertTrue(sql.contains("e.status = "), "在院条件 e.status（绑定 ACTIVE）必须保留：$sql")
    }

    @Test
    fun `手工条目的资料口径同样含显式encounter_id谓词且不过滤encounter_type`() {
        addManualItem()

        val sql = executed.firstOrNull { it.contains("dining_diet_profiles") && it.contains("healthcare.encounters") }
        assertTrue(sql != null, "手工加人必须查启用档案（在院口径），实际执行: $executed")
        assertTrue(
            sql!!.contains("p.encounter_id is not null"),
            "手工条目同样必须显式写出 `p.encounter_id IS NOT NULL`：$sql",
        )
        assertFalse(
            sql.contains("encounter_type"),
            "不得引入 encounter_type 过滤：$sql",
        )
        assertTrue(sql.contains("p.patient_id = "), "手工条目必须按 patient_id 定位：$sql")
        assertTrue(sql.contains("e.status = "), "在院条件 e.status（绑定 ACTIVE）必须保留：$sql")
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 触发 `loadExpectedProfiles`（名单生成路径）。 */
    private fun generate() {
        runCatching {
            RosterService(pool)
                .generate("2026-10-06", "午餐", null, "user-a")
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
        }
    }

    /** 触发 `buildManualItem`（手工加人路径：adjust_type ≠ 临时加餐 且该长者不在名单中）。 */
    private fun addManualItem() {
        runCatching {
            RosterService(pool)
                .addItem("01J5D3M00000000000000RST01", JsonObject().put("patient_id", "pat-1").put("adjust_type", "外出"))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
        }
    }

    /** 名单容器行：让 `generate`/`addItem` 走到档案查询，而不是在容器查找处短路。 */
    private fun rosterRow(): Row = mockk(relaxed = true) {
        every { getValue("id") } returns "01J5D3M00000000000000RST01"
        every { getValue("menu_date") } returns "2026-10-06"
        every { getValue("meal_time") } returns "午餐"
        every { getValue("status") } returns "启用"
    }

    private fun rowSet(vararg rows: Row): RowSet<Row> = mockk(relaxed = true) {        every { size() } returns rows.size
        every { rowCount() } returns rows.size
        every { iterator() } answers {
            val delegate = rows.iterator()
            mockk<RowIterator<Row>>(relaxed = true) {
                every { hasNext() } answers { delegate.hasNext() }
                every { next() } answers { delegate.next() }
            }
        }
    }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")
}
