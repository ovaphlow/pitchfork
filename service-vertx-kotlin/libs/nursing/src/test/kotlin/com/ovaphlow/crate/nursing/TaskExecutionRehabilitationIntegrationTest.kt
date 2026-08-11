package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 康复活动（机构服务项目）端到端数据库集成测试。
 *
 * 覆盖卡片「康复活动」验收标准 2 的完整闭环：
 *   创建 REHABILITATION 任务（含频次/起止日期）→ 列表可见 → generate 指定日期范围
 *   → 生成对应执行计划 → 打卡完成/跳过 → 状态、实际时间、执行人落库 → 列表与统计可见。
 *
 * 运行方式（需用户授权且使用独立可销毁的 aceso_test 测试库）：
 *
 *   export PITCHFORK_DB_PASSWORD=pitchfork-test-only
 *   ./gradlew :libs:nursing:test \
 *     -Dintegration.db.host=localhost -Dintegration.db.port=55432 \
 *     -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *     --tests "*TaskExecutionRehabilitationIntegrationTest*" --rerun-tasks
 *
 * fixture 统一使用 `rh-` 前缀，@AfterAll 校验清理无残留。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionRehabilitationIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "rh-"
        private const val ELDERLY_TASK = "rh-task-elderly" // REHABILITATION QD，挂活跃周期
        private const val ORG_TASK = "rh-task-org" // REHABILITATION BID+schedule_times，全院性（无周期）
        private const val NURSING_NP_TASK = "rh-task-nursing-np" // NURSING QD，无周期（回归：不参与）
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var taskService: TaskService
    private lateinit var executionService: TaskExecutionService

    /** 通过 TaskService.create 创建的任务（ULID），@AfterAll 按 id 清理 */
    private val createdTaskIds = mutableListOf<String>()

    @BeforeAll
    fun setup(ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        if (password.isBlank()) {
            ctx.failNow(IllegalStateException("PITCHFORK_DB_PASSWORD must be set"))
            return@setup
        }

        try {
            // 干净库：DROP 后重建并执行 Flyway 迁移
            val rootUrl = "jdbc:postgresql://$host:$port/postgres"
            DriverManager.getConnection(rootUrl, user, password).use { conn ->
                conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB")
                conn.createStatement().execute("CREATE DATABASE $TEST_DB")
            }

            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            try {
                DatabaseConfig.migrate(dbConfig)
            } catch (e: Exception) {
                // 与既有集成测试一致：V404 迁移可能因 schema 前缀失败，手工补齐
                val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val stmt = conn.createStatement()
                    stmt.execute("SET search_path TO nursing, public")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD COLUMN IF NOT EXISTS encounter_id VARCHAR(32)")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods DROP CONSTRAINT IF EXISTS nursing_service_periods_service_type_check")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD CONSTRAINT nursing_service_periods_service_type_check CHECK (service_type IN ('HOME_CARE', 'COMMUNITY_CARE', 'HOSPICE', 'ELDERLY_CARE'))")
                }
            }

            setupFixturesJdbc()

            pool = DatabaseConfig.createPool(Vertx.vertx(), dbConfig)
            taskService = TaskService(pool)
            executionService = TaskExecutionService(pool)

            ctx.completeNow()
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @AfterAll
    fun cleanup(ctx: VertxTestContext) {
        try {
            val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                conn.autoCommit = false
                val stmt = conn.createStatement()
                val createdIds = createdTaskIds.joinToString(", ") { "'$it'" }
                stmt.execute("DELETE FROM nursing.nursing_task_execution_consumptions WHERE task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE task_id LIKE '${FIXTURE_PREFIX}%' OR task_id IN ($createdIds))")
                stmt.execute("DELETE FROM nursing.nursing_visit_schedules WHERE period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE task_id LIKE '${FIXTURE_PREFIX}%' OR task_id IN ($createdIds)")
                stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR id IN ($createdIds)")
                stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
                val remaining = stmt.executeQuery(
                    """
                    SELECT (
                        (SELECT count(*) FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%') +
                        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%') +
                        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR id IN ($createdIds)) +
                        (SELECT count(*) FROM nursing.nursing_task_executions WHERE task_id LIKE '${FIXTURE_PREFIX}%' OR task_id IN ($createdIds))
                    )
                    """.trimIndent()
                ).use { result ->
                    result.next()
                    result.getLong(1)
                }
                check(remaining == 0L) { "fixture cleanup left $remaining rows" }
                conn.commit()
            }
            if (::pool.isInitialized) {
                pool.close().onComplete { ar ->
                    if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
                }
            } else {
                ctx.completeNow()
            }
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    private fun jdbcUrl() = "jdbc:postgresql://$host:$port/$TEST_DB"

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}${suffix}"

    /** 共享 fixture：老人 + 活跃周期 + 三类任务（挂周期康复活动 / 全院性康复活动 / 无周期护理任务） */
    private fun setupFixturesJdbc() {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("CREATE SCHEMA IF NOT EXISTS healthcare")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS healthcare.patients (
                    id VARCHAR(32) PRIMARY KEY,
                    name VARCHAR NOT NULL DEFAULT '',
                    gender VARCHAR NOT NULL DEFAULT '',
                    status VARCHAR DEFAULT 'ACTIVE',
                    created_at TIMESTAMPTZ DEFAULT now(),
                    updated_at TIMESTAMPTZ DEFAULT now()
                )
                """.trimIndent(),
            )
            stmt.execute(
                "INSERT INTO healthcare.patients (id, name, status) VALUES ('${fixtureId("patient")}', '康复测试长者', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
            stmt.execute(
                "INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status) " +
                    "VALUES ('${fixtureId("period")}', '${fixtureId("patient")}', 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
            // 挂活跃周期的康复活动任务（QD）
            stmt.execute(
                "INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, frequency_name, start_date, end_date, status) " +
                    "VALUES ('$ELDERLY_TASK', '${fixtureId("period")}', 'REHABILITATION', 'rh-老人肢体训练', 'QD', '每日一次', CURRENT_DATE, CURRENT_DATE + 3, 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
            // 全院性康复活动任务（无周期，BID + 自定义时段 09:00/15:00）
            stmt.execute(
                "INSERT INTO nursing.nursing_tasks (id, task_type, description, frequency_code, frequency_name, start_date, end_date, status, metadata) " +
                    "VALUES ('$ORG_TASK', 'REHABILITATION', 'rh-全院晨间太极', 'BID', '每日两次', CURRENT_DATE, CURRENT_DATE + 3, 'ACTIVE', " +
                    "'{\"schedule_times\":[\"09:00\",\"15:00\"]}'::jsonb) ON CONFLICT (id) DO NOTHING",
            )
            // 无周期的 NURSING 任务（回归：不得参与排期生成与今日看板）
            stmt.execute(
                "INSERT INTO nursing.nursing_tasks (id, task_type, description, frequency_code, start_date, status) " +
                    "VALUES ('$NURSING_NP_TASK', 'NURSING', 'rh-无周期护理任务', 'QD', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING",
            )
        }
    }

    // ========================================================================
    //  helpers
    // ========================================================================

    private fun <T> await(future: Future<T>): T =
        future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)

    /** 按 task_id 查询执行记录（JDBC，返回 id/status/planned_time/actual_time/executor/note） */
    private fun executionsOf(taskId: String): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT id, status, to_char(planned_time AT TIME ZONE 'UTC', 'HH24:MI') AS planned_hhmm, actual_time, executor, note FROM nursing.nursing_task_executions WHERE task_id = '$taskId' ORDER BY planned_time",
                ).use { rs ->
                    while (rs.next()) {
                        rows.add(
                            JsonObject()
                                .put("id", rs.getString("id"))
                                .put("status", rs.getString("status"))
                                .put("planned_hhmm", rs.getString("planned_hhmm"))
                                .put("actual_time", rs.getString("actual_time"))
                                .put("executor", rs.getString("executor"))
                                .put("note", rs.getString("note")),
                        )
                    }
                }
            }
        }
        return rows
    }

    /** 创建一条挂活跃周期的 REHABILITATION 任务（走 TaskService.create 真实路径） */
    private fun createRehabilitationTask(description: String, frequencyCode: String = "QD"): JsonObject {
        val body =
            JsonObject()
                .put("task_type", "REHABILITATION")
                .put("description", description)
                .put("frequency_code", frequencyCode)
                .put("frequency_name", "每日一次")
                .put("start_date", LocalDate.now().toString())
                .put("end_date", LocalDate.now().plusDays(3).toString())
                .put("period_id", fixtureId("period"))
        val created = await(taskService.create(body))
        createdTaskIds.add(created.getString("id"))
        return created
    }

    // ========================================================================
    //  验收标准 2 端到端闭环
    // ========================================================================

    @Test
    fun `F1-F2 创建康复活动任务后按 task_type 过滤在列表可见`(ctx: VertxTestContext) {
        val created = createRehabilitationTask("rh-创建验证活动")
        val taskId = created.getString("id")
        assertNotNull(taskId)
        assertEquals("REHABILITATION", created.getString("task_type"))
        assertEquals("ACTIVE", created.getString("status"))
        assertNotNull(created.getString("start_date"))
        assertNotNull(created.getString("end_date"))

        val rehabList = await(taskService.list(taskType = "REHABILITATION", limit = 100))
        val rehabIds = rehabList.getJsonArray("records").map { it as JsonObject }.map { it.getString("id") }
        assertTrue(rehabIds.contains(taskId), "REHABILITATION 过滤列表应包含新创建任务")
        assertTrue(rehabIds.contains(ELDERLY_TASK), "REHABILITATION 过滤列表应包含 fixture 老人任务")
        assertTrue(rehabIds.contains(ORG_TASK), "REHABILITATION 过滤列表应包含 fixture 全院活动")

        val nursingList = await(taskService.list(taskType = "NURSING", limit = 100))
        val nursingIds = nursingList.getJsonArray("records").map { it as JsonObject }.map { it.getString("id") }
        assertFalse(nursingIds.contains(taskId), "NURSING 过滤列表不应包含康复活动任务")

        ctx.completeNow()
    }

    @Test
    fun `F4 生成排期覆盖挂周期与全院性康复活动且无周期护理任务不参与`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        val result = await(executionService.ensureExecutionsForDateRange(today, today, null))
        assertTrue(result.getJsonArray("errors").isEmpty, "generate 不应产生错误: $result")

        // 挂周期康复活动：QD → 今日 1 条
        val elderly = executionsOf(ELDERLY_TASK)
        assertEquals(1, elderly.size, "挂周期 QD 康复活动今日应生成 1 条执行")
        assertEquals("PENDING", elderly[0].getString("status"))
        assertEquals("09:00", elderly[0].getString("planned_hhmm"), "QD 默认时段应为 09:00 UTC: ${elderly[0]}")

        // 全院性康复活动：BID + schedule_times 09:00/15:00 → 今日 2 条
        val org = executionsOf(ORG_TASK)
        assertEquals(2, org.size, "全院性 BID 康复活动今日应生成 2 条执行")
        val times = org.map { it.getString("planned_hhmm") }.sorted()
        assertEquals(listOf("09:00", "15:00"), times, "自定义时段应生成 09:00/15:00: $times")

        // 回归：无周期 NURSING 任务不参与生成
        assertEquals(0, executionsOf(NURSING_NP_TASK).size, "无周期 NURSING 任务不得生成执行计划")

        // 幂等：重复 generate 不产生重复记录
        val again = await(executionService.ensureExecutionsForDateRange(today, today, null))
        assertEquals(0, again.getInteger("generated"), "重复 generate 不应产生新记录")
        assertTrue(again.getJsonArray("errors").isEmpty)
        assertEquals(1, executionsOf(ELDERLY_TASK).size)
        assertEquals(2, executionsOf(ORG_TASK).size)

        ctx.completeNow()
    }

    @Test
    fun `F5 今日看板按 task_type 过滤包含全院性康复活动且无过滤时无回归`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        // 带 REHABILITATION 过滤：全院性与挂周期康复活动均可见
        val rehabToday = await(executionService.todayExecutions(date = today, taskType = "REHABILITATION", limit = 100))
        val rehabExecs = rehabToday.getJsonArray("records").map { it as JsonObject }
        assertTrue(rehabExecs.isNotEmpty(), "今日看板 REHABILITATION 过滤不应为空")
        val descriptions = rehabExecs.map { it.getString("task_description") }.toSet()
        assertTrue(descriptions.contains("rh-全院晨间太极"), "全院性康复活动应出现在今日看板: $descriptions")
        assertTrue(descriptions.contains("rh-老人肢体训练"), "挂周期康复活动应出现在今日看板: $descriptions")

        // 不带过滤：既有任务类型（NURSING 等）仍可见，无回归
        val allToday = await(executionService.todayExecutions(date = today, limit = 100))
        val allDescriptions = allToday.getJsonArray("records").map { it as JsonObject }.map { it.getString("task_description") }.toSet()
        assertTrue(allDescriptions.contains("rh-全院晨间太极"), "不带过滤时全院性康复活动仍应可见")
        assertFalse(allDescriptions.contains("rh-无周期护理任务"), "无周期 NURSING 任务不应出现在今日看板（既有行为）")

        ctx.completeNow()
    }

    @Test
    fun `F6 打卡完成落库状态实际时间备注与执行人`(ctx: VertxTestContext) {
        val taskId = createRehabilitationTask("rh-打卡完成任务").getString("id")
        val today = LocalDate.now()
        await(executionService.ensureExecutionsForDateRange(today, today, null))

        val execs = executionsOf(taskId)
        assertEquals(1, execs.size)
        val execId = execs[0].getString("id")

        // 前端打卡路径：开始 → 完成（PATCH /task-executions/:id/status，无耗材分支）。
        // 路由从认证会话（登录 subject_id）解析执行人并随状态更新落库；
        // 此处直接模拟登录用户传入 executor。
        val executor = "rh-登录执行人"
        val started = await(executionService.updateStatus(execId, "IN_PROGRESS", executor = executor))
        assertEquals("IN_PROGRESS", started.getString("status"))

        val completed = await(executionService.updateStatus(execId, "COMPLETED", "rh-完成备注", executor = executor))
        assertEquals("COMPLETED", completed.getString("status"))
        assertNotNull(completed.getString("actual_time"), "打卡完成后实际时间应落库")

        // 数据库中复查
        val rows = executionsOf(taskId)
        assertEquals("COMPLETED", rows[0].getString("status"))
        assertNotNull(rows[0].getString("actual_time"), "actual_time 应落库")
        assertEquals("rh-完成备注", rows[0].getString("note"), "完成备注应落库")
        assertEquals(executor, rows[0].getString("executor"), "执行人（登录 subject_id）应落库")

        ctx.completeNow()
    }

    @Test
    fun `F6 跳过打卡落库状态与原因`(ctx: VertxTestContext) {
        val taskId = createRehabilitationTask("rh-跳过验证活动").getString("id")
        val today = LocalDate.now()
        await(executionService.ensureExecutionsForDateRange(today, today, null))

        val execId = executionsOf(taskId)[0].getString("id")
        val skipped = await(executionService.updateStatus(execId, "SKIPPED", "rh-长者外出"))
        assertEquals("SKIPPED", skipped.getString("status"))
        assertEquals("rh-长者外出", skipped.getString("note"))

        val rows = executionsOf(taskId)
        assertEquals("SKIPPED", rows[0].getString("status"))
        assertEquals("rh-长者外出", rows[0].getString("note"))

        ctx.completeNow()
    }

    @Test
    fun `F7 统计按 task_type 过滤可见康复活动完成与跳过`(ctx: VertxTestContext) {
        val today = LocalDate.now()
        // 自包含数据：创建任务 → 生成 → 完成 1 条、跳过 1 条
        val taskId = createRehabilitationTask("rh-统计验证活动", frequencyCode = "BID").getString("id")
        await(executionService.ensureExecutionsForDateRange(today, today, null))
        val execs = executionsOf(taskId)
        assertEquals(2, execs.size, "BID 任务今日应生成 2 条")
        await(executionService.updateStatus(execs[0].getString("id"), "IN_PROGRESS"))
        await(executionService.updateStatus(execs[0].getString("id"), "COMPLETED", "rh-统计完成"))
        await(executionService.updateStatus(execs[1].getString("id"), "SKIPPED", "rh-统计跳过"))

        val stats = await(
            executionService.executionStatistics(
                dateFrom = today,
                dateTo = today,
                taskType = "REHABILITATION",
                limit = 100,
            ),
        )
        val meta = stats.getJsonObject("meta")
        assertTrue(meta.getLong("scheduled_total") >= 2, "统计应包含康复活动计划次数: $meta")
        assertTrue(meta.getLong("completed_total") >= 1, "统计应包含康复活动完成次数: $meta")
        assertTrue(meta.getLong("skipped_total") >= 1, "统计应包含康复活动跳过次数: $meta")
        assertNotNull(meta.getLong("completion_rate"), "完成率应存在")

        // 不带过滤的统计仍包含（无回归）
        val allStats = await(executionService.executionStatistics(dateFrom = today, dateTo = today, limit = 100))
        assertTrue(allStats.getJsonObject("meta").getLong("scheduled_total") >= 2)

        ctx.completeNow()
    }

    @Test
    fun `F3 取消活动任务后不再生成排期`(ctx: VertxTestContext) {
        val taskId = createRehabilitationTask("rh-取消验证活动").getString("id")
        val today = LocalDate.now()
        await(executionService.ensureExecutionsForDateRange(today, today, null))
        assertEquals(1, executionsOf(taskId).size, "ACTIVE 任务应先生成执行")

        val cancelled = await(taskService.updateStatus(taskId, "CANCELLED"))
        assertEquals("CANCELLED", cancelled.getString("status"))

        await(executionService.ensureExecutionsForDateRange(today.plusDays(1), today.plusDays(1), null))
        val afterCancel = executionsOf(taskId)
        assertEquals(1, afterCancel.size, "取消后不得再生成新的执行计划")
        assertEquals("PENDING", afterCancel[0].getString("status"), "既有执行保持原状态")

        ctx.completeNow()
    }
}
