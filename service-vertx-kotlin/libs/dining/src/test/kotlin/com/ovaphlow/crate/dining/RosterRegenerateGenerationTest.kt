package com.ovaphlow.crate.dining

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 配餐名单「重新生成」时生成留痕（`generated_by`/`generated_at`）的刷新护栏（**不连数据库**）。
 *
 * 背景：UI 的「生成人/生成时间」两列就是这两个字段。接线前 `getOrCreateRoster` 对已存在的
 * （日期, 餐次）**原样返回**，重新生成只刷新条目 → 两列永远停在首次生成的人与时刻。
 *
 * 用 mockk 桩 `Pool` 按 normalized SQL 分发行集（回读行与查找行**故意不同**，
 * 这样"响应里的生成人是旧值"才会被抓到），锁五件事：
 * 1. 名单已存在 → 发出一条 UPDATE，且同时写 `generated_at` 与 `generated_by`；
 * 2. 该 UPDATE 之后重新读名单容器，响应里的生成人取自更新后的行（不是查找时的旧行）；
 * 3. 刷新路径不发 INSERT（不会第二次插入名单容器）；
 * 4. 主体未知（无认证挂载的直接服务调用）→ 只推进 `generated_at`，**不覆盖** `generated_by`（042 口径）；
 * 5. 首次生成（名单不存在）不发 UPDATE；并发插入冲突（rowCount 0）同样刷新生成留痕。
 */
class RosterRegenerateGenerationTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    /** 名单容器是否已存在（已存在 = 走"重新生成"路径）。 */
    private var rosterExists = true

    /** 插入被唯一键撞掉（并发创建）：`getOrCreateRoster` 的冲突分支。 */
    private var insertConflicts = false

    /** 名单容器查找次数：第 2 次只可能出现在并发冲突分支。 */
    private var lookups = 0

    /** 已执行的 normalized SQL（按执行顺序）。 */
    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        executed.clear()
        rosterExists = true
        insertConflicts = false
        lookups = 0
        pool = mockk<Pool>()
        prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } answers {
            executed.add(normalized(firstArg<String>()))
            prepared
        }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.startsWith("update dining.dining_rosters") -> Future.succeededFuture(rowSet(rowCount = 1))
                sql.startsWith("insert into dining.dining_rosters") ->
                    Future.succeededFuture(rowSet(rowCount = if (insertConflicts) 0 else 1))
                sql.contains("dining_roster_items") -> Future.succeededFuture(rowSet())
                sql.contains("dining_diet_profiles") -> Future.succeededFuture(rowSet())
                // 查找（按 menu_date + meal_time）：首个主体决定是否走重新生成；
                // 并发冲突分支的第二次查找必然能读到刚被他人插入的那一行
                sql.contains("dining.dining_rosters") && sql.contains("menu_date") -> {
                    lookups++
                    Future.succeededFuture(if (lookups == 1 && !rosterExists) rowSet() else rowSet(staleRow()))
                }
                // 按 id 回读（插入后 / 刷新后）：读到更新后的行
                sql.contains("dining.dining_rosters") -> Future.succeededFuture(rowSet(freshRow()))
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `重新生成刷新生成人与生成时间并回读最新行`() {
        val result = generate("user-B")

        val update = executed.firstOrNull { it.startsWith("update dining.dining_rosters") }
        assertTrue(update != null, "名单已存在时必须发 UPDATE，实际执行: $executed")
        assertTrue(update!!.contains("generated_at"), "重新生成必须推进生成时间: $update")
        assertTrue(update.contains("generated_by"), "重新生成必须写入本次生成人: $update")
        assertFalse(
            executed.any { it.startsWith("insert into dining.dining_rosters") },
            "刷新路径不得再插一条名单容器，实际执行: $executed",
        )

        val updateIndex = executed.indexOfFirst { it.startsWith("update dining.dining_rosters") }
        val readsAfter = executed.withIndex()
            .filter { it.value.contains("dining.dining_rosters") && !it.value.startsWith("update") && it.index > updateIndex }
        assertTrue(
            readsAfter.isNotEmpty(),
            "刷新后必须重新读名单容器（不能沿用刷新前的旧行），实际执行: $executed",
        )
        assertEquals(
            FRESH_GENERATOR,
            result.getJsonObject("roster").getString("generated_by"),
            "响应里的生成人必须来自更新后的行（查找行是 $STALE_GENERATOR），实际执行: $executed",
        )
    }

    @Test
    fun `生成人未知时只推进生成时间不覆盖旧生成人`() {
        generate("   ")

        val update = executed.firstOrNull { it.startsWith("update dining.dining_rosters") }
        assertTrue(update != null, "名单已存在时必须发 UPDATE，实际执行: $executed")
        assertTrue(update!!.contains("generated_at"), "本次生成确实发生，生成时间仍要推进: $update")
        assertFalse(
            update.contains("generated_by"),
            "主体未知时不得把生成人写成空值（042「操作人未知则不写」口径），实际: $update",
        )
    }

    @Test
    fun `首次生成只插入名单容器不额外更新`() {
        rosterExists = false

        generate("user-A")

        assertTrue(
            executed.any { it.startsWith("insert into dining.dining_rosters") },
            "首次生成必须插入名单容器，实际执行: $executed",
        )
        assertFalse(
            executed.any { it.startsWith("update dining.dining_rosters") },
            "首次生成不需要额外 UPDATE（生成人/生成时间已随 INSERT 落库），实际执行: $executed",
        )
    }

    @Test
    fun `并发插入冲突同样刷新生成留痕`() {
        rosterExists = false
        insertConflicts = true

        val result = generate("user-C")

        assertEquals(2, lookups, "冲突分支必须重新查找既有名单，实际执行: $executed")
        assertTrue(
            executed.any { it.startsWith("update dining.dining_rosters") },
            "并发冲突后拿到的既有名单也要刷新生成人/生成时间，实际执行: $executed",
        )
        assertEquals(
            FRESH_GENERATOR,
            result.getJsonObject("roster").getString("generated_by"),
            "响应里的生成人必须来自更新后的行，实际执行: $executed",
        )
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun generate(userId: String) =
        RosterService(pool)
            .generate(DATE, "午餐", null, userId)
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)

    /** 查找时读到的行：上一次生成的留痕（重新生成后不得出现在响应里）。 */
    private fun staleRow(): Row =
        mockk(relaxed = true) {
            every { getValue("id") } returns ROSTER_ID
            every { getValue("menu_date") } returns DATE
            every { getValue("meal_time") } returns "午餐"
            every { getValue("generated_by") } returns STALE_GENERATOR
            every { getValue("generated_at") } returns OffsetDateTime.parse("2026-10-04T08:00:00+08:00")
        }

    /** 更新后回读的行。 */
    private fun freshRow(): Row =
        mockk(relaxed = true) {
            every { getValue("id") } returns ROSTER_ID
            every { getValue("menu_date") } returns DATE
            every { getValue("meal_time") } returns "午餐"
            every { getValue("generated_by") } returns FRESH_GENERATOR
            every { getValue("generated_at") } returns OffsetDateTime.parse("2026-10-04T12:00:00+08:00")
        }

    private fun rowSet(vararg rows: Row, rowCount: Int = 0): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { rowCount() } returns rowCount
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    private companion object {
        const val ROSTER_ID = "01J5D3M00000000000000RST01"
        const val DATE = "2026-10-04"
        const val STALE_GENERATOR = "subject-stale-generator"
        const val FRESH_GENERATOR = "subject-fresh-generator"
    }
}
