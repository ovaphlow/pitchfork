package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 康复活动（机构服务项目）复用护理任务模式的非数据库单元测试：
 *   - TaskService 任务类型白名单含 REHABILITATION（F2 创建活动任务入口）
 *   - 排期生成 / 今日看板的任务参与条件：
 *     - 归属活跃照护周期的任务参与（既有行为，覆盖全部任务类型）
 *     - 无周期归属的全院性 REHABILITATION 活动参与（Q4 默认：可不挂老人）
 *     - 无周期归属的其它任务类型（NURSING 等）不参与，行为不变
 *   - FrequencyCalculator 对 REHABILITATION 活动任务按自定义时段（metadata.schedule_times）生成计划时间
 */
class TaskExecutionServiceRehabilitationTest {

    // ========================================================================
    //  TaskService 任务类型白名单
    // ========================================================================

    @Test
    fun `任务类型白名单包含 REHABILITATION`() {
        assertTrue(TaskService.VALID_TASK_TYPES.contains("REHABILITATION"))
        // 白名单保持既有五类语义，未因康复活动引入新枚举
        assertEquals(
            setOf("NURSING", "REHABILITATION", "LIVING_CARE", "HEALTH_EDUCATION", "OTHER"),
            TaskService.VALID_TASK_TYPES,
        )
    }

    // ========================================================================
    //  排期生成 / 今日看板 — 任务参与条件（纯 SQL 渲染验证）
    // ========================================================================

    private fun render(condition: org.jooq.Condition): String {
        val query = DSL.selectFrom(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t")).where(condition)
        return DatabaseConfig.sql(query)
    }

    private fun participation(
        taskPeriodId: String = "t.period_id",
        taskType: String = "t.task_type",
        periodStatus: String = "ps.status",
    ): String = render(
        TaskExecutionService.taskParticipationCondition(
            taskPeriodId = DSL.field(taskPeriodId, String::class.java),
            taskType = DSL.field(taskType, String::class.java),
            periodStatus = DSL.field(periodStatus, String::class.java),
        ),
    )

    /** 参与条件的绑定值（参数化 SQL，按出现顺序） */
    private fun participationBindValues(
        taskPeriodId: String = "t.period_id",
        taskType: String = "t.task_type",
        periodStatus: String = "ps.status",
    ): List<Any?> {
        val query = DSL.selectFrom(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t")).where(
            TaskExecutionService.taskParticipationCondition(
                taskPeriodId = DSL.field(taskPeriodId, String::class.java),
                taskType = DSL.field(taskType, String::class.java),
                periodStatus = DSL.field(periodStatus, String::class.java),
            ),
        )
        return query.getBindValues()
    }

    @Test
    fun `归属活跃周期的任务参与排期生成与今日看板`() {
        val sql = participation()
        assertTrue(sql.contains("ps.status = "), "活跃周期条件缺失: $sql")
        assertEquals(listOf("ACTIVE", "REHABILITATION"), participationBindValues())
    }

    @Test
    fun `全院性康复活动（无周期归属）参与排期生成与今日看板`() {
        val sql = participation()
        assertTrue(sql.contains("t.period_id is null"), "无周期条件缺失: $sql")
        assertTrue(sql.contains("t.task_type = "), "REHABILITATION 条件缺失: $sql")
    }

    @Test
    fun `无周期归属的其它任务类型不参与排期生成与今日看板`() {
        // 条件仅对 REHABILITATION 放开无周期归属：NURSING / MEDICATION 等无周期任务仍被排除
        val sql = participation()
        assertFalse(sql.contains("task_type = 'NURSING'"), "不得引入其它任务类型的无周期参与: $sql")
        assertFalse(sql.contains("task_type = 'MEDICATION'"), "不得引入其它任务类型的无周期参与: $sql")
    }

    // ========================================================================
    //  FrequencyCalculator — 康复活动任务计划时间
    // ========================================================================

    @Test
    fun `REHABILITATION 活动按自定义时段生成计划时间`() {
        // F2：可选自定义时段 metadata.schedule_times（ISO HH:mm 数组），缺失时使用频次默认值
        val metadata =
            io.vertx.core.json.JsonObject()
                .put("schedule_times", io.vertx.core.json.JsonArray(listOf("08:30", "15:00")))
        val times =
            FrequencyCalculator.plannedTimesForDate(
                frequencyCode = "BID",
                startDate = java.time.LocalDate.of(2026, 8, 1),
                targetDate = java.time.LocalDate.of(2026, 8, 2),
                metadata = metadata,
            )
        assertEquals(2, times.size)
        assertEquals("08:30", times[0].toLocalTime().toString())
        assertEquals("15:00", times[1].toLocalTime().toString())
    }

    @Test
    fun `REHABILITATION 活动频次缺失自定义时段时使用默认时段`() {
        val times =
            FrequencyCalculator.plannedTimesForDate(
                frequencyCode = "QD",
                startDate = java.time.LocalDate.of(2026, 8, 1),
                targetDate = java.time.LocalDate.of(2026, 8, 3),
                metadata = null,
            )
        assertEquals(1, times.size)
        assertEquals("09:00", times[0].toLocalTime().toString())
    }

    @Test
    fun `PRN 与 STAT 频次不自动生成执行计划`() {
        for (code in listOf("PRN", "STAT")) {
            val times =
                FrequencyCalculator.plannedTimesForDate(
                    frequencyCode = code,
                    startDate = java.time.LocalDate.of(2026, 8, 1),
                    targetDate = java.time.LocalDate.of(2026, 8, 3),
                    metadata = null,
                )
            assertTrue(times.isEmpty(), "$code 不应自动生成执行计划")
        }
    }
}
