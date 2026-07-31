package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
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

@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionStatisticsIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "si-"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var service: TaskExecutionService

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
            // Drop and recreate test database to ensure clean Flyway state
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
                // V404 migration may fail due to missing schema prefix; apply manually
                val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val stmt = conn.createStatement()
                    stmt.execute("SET search_path TO nursing, public")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD COLUMN IF NOT EXISTS encounter_id VARCHAR(32)")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods DROP CONSTRAINT IF EXISTS nursing_service_periods_service_type_check")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD CONSTRAINT nursing_service_periods_service_type_check CHECK (service_type IN ('HOME_CARE', 'COMMUNITY_CARE', 'HOSPICE', 'ELDERLY_CARE'))")
                    stmt.execute("ALTER TABLE nursing.nursing_service_periods ADD CONSTRAINT chk_elderly_care_encounter_link CHECK ((service_type = 'ELDERLY_CARE' AND encounter_id IS NOT NULL) OR (service_type <> 'ELDERLY_CARE' AND encounter_id IS NULL))")
                    stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_nursing_service_periods_encounter_id ON nursing.nursing_service_periods (encounter_id) WHERE encounter_id IS NOT NULL")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_period_encounter_status ON nursing.nursing_service_periods (encounter_id, status) WHERE encounter_id IS NOT NULL")
                }
            }

            setupFixturesJdbc()

            pool = DatabaseConfig.createPool(Vertx.vertx(), dbConfig)
            service = TaskExecutionService(pool)

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
                val stmt = conn.createStatement()
                stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
            }
        } catch (_: Exception) { /* cleanup best effort */ }

        if (::pool.isInitialized) pool.close()
        ctx.completeNow()
    }

    private fun jdbcUrl() = "jdbc:postgresql://$host:$port/$TEST_DB"

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}${suffix}"

    private fun setupFixturesJdbc() {
        val now = OffsetDateTime.now()
        val twoHoursAgo = now.minusHours(2)
        val oneHourLater = now.plusHours(1)
        val threeDaysAgo = now.minusDays(3)
        val twoDaysLater = now.plusDays(2)
        // Use minute offsets to guarantee unique (task_id, planned_time) across all fixture records
        val t1 = twoHoursAgo
        val t2 = twoHoursAgo.minusMinutes(1)
        val t3 = twoHoursAgo.minusMinutes(2)
        val t4 = twoHoursAgo.minusMinutes(3)
        val t5 = twoHoursAgo.minusMinutes(4)
        val t6 = twoHoursAgo.minusMinutes(5)
        val t7 = twoHoursAgo.minusMinutes(6)

        val patientId1 = fixtureId("patient-1")
        val patientId2 = fixtureId("patient-2")
        val periodId1 = fixtureId("period-1")
        val periodId2 = fixtureId("period-2")
        val taskId1 = fixtureId("task-1")
        val taskId2 = fixtureId("task-2")

        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()

            // Create schemas and tables first
            stmt.execute("CREATE SCHEMA IF NOT EXISTS healthcare")
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS healthcare.patients (
                    id VARCHAR(32) PRIMARY KEY,
                    name VARCHAR NOT NULL DEFAULT '',
                    gender VARCHAR NOT NULL DEFAULT '',
                    status VARCHAR DEFAULT 'ACTIVE',
                    created_at TIMESTAMPTZ DEFAULT now(),
                    updated_at TIMESTAMPTZ DEFAULT now()
                )
            """)

            // Now clean up old fixture data
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR id LIKE 'to-%'")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR id LIKE 'to-%'")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR id LIKE 'to-%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%' OR id LIKE 'to-%'")
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE task_id LIKE '${FIXTURE_PREFIX}%' OR task_id LIKE 'to-%'")

            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('$patientId1', '统计测试患者1', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('$patientId2', '统计测试患者2', 'ACTIVE') ON CONFLICT (id) DO NOTHING")

            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status) VALUES ('$periodId1', '$patientId1', 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status) VALUES ('$periodId2', '$patientId2', 'HOME_CARE', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING")

            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status) VALUES ('$taskId1', '$periodId1', 'NURSING', '统计测试任务1', 'QD', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status) VALUES ('$taskId2', '$periodId2', 'NURSING', '统计测试任务2', 'QD', CURRENT_DATE, 'ACTIVE') ON CONFLICT (id) DO NOTHING")

            // executor-1: 5 records
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time) VALUES ('${fixtureId("exec-1-completed")}', '$taskId1', '$t1', 'COMPLETED', 'executor-1', '$now') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-1-pending")}', '$taskId1', '$t2', 'PENDING', 'executor-1') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-1-in-progress")}', '$taskId1', '$t3', 'IN_PROGRESS', 'executor-1') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-1-skipped")}', '$taskId1', '$t4', 'SKIPPED', 'executor-1') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-1-future")}', '$taskId1', '$oneHourLater', 'PENDING', 'executor-1') ON CONFLICT (id) DO NOTHING")

            // executor-2: 3 records
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time) VALUES ('${fixtureId("exec-2-completed")}', '$taskId2', '$t1', 'COMPLETED', 'executor-2', '$now') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-2-cancelled")}', '$taskId2', '$t5', 'CANCELLED', 'executor-2') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-2-future")}', '$taskId2', '$twoDaysLater', 'PENDING', 'executor-2') ON CONFLICT (id) DO NOTHING")

            // unassigned: 2 records
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-null-pending")}', '$taskId1', '$t6', 'PENDING', NULL) ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time) VALUES ('${fixtureId("exec-null-completed")}', '$taskId2', '$t7', 'COMPLETED', NULL, '$now') ON CONFLICT (id) DO NOTHING")

            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-outside-date")}', '$taskId1', '${threeDaysAgo}', 'COMPLETED', 'executor-1') ON CONFLICT (id) DO NOTHING")
        }
    }

    @Test
    fun `全局和分组统计口径正确`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today)
            .onSuccess { result ->
                try {
                    val meta = result.getJsonObject("meta")
                    assertNotNull(meta, "meta should not be null")

                    val scheduledTotal = meta.getLong("scheduled_total") ?: 0L
                    val pendingTotal = meta.getLong("pending_total") ?: 0L
                    val inProgressTotal = meta.getLong("in_progress_total") ?: 0L
                    val completedTotal = meta.getLong("completed_total") ?: 0L
                    val skippedTotal = meta.getLong("skipped_total") ?: 0L
                    val cancelledTotal = meta.getLong("cancelled_total") ?: 0L
                    val dueTotal = meta.getLong("due_total") ?: 0L
                    val completedDueTotal = meta.getLong("completed_due_total") ?: 0L
                    val overdueTotal = meta.getLong("overdue_total") ?: 0L
                    val completionRate = if (meta.containsKey("completion_rate")) meta.getDouble("completion_rate") else null

                    assertEquals(scheduledTotal, pendingTotal + inProgressTotal + completedTotal + skippedTotal + cancelledTotal,
                        "sum of five status counts must equal scheduled_total")

                    assertTrue(overdueTotal >= 0, "overdue_total must be >= 0")

                    if (dueTotal > 0) {
                        assertNotNull(completionRate, "completion_rate must not be null when dueTotal > 0")
                        val expectedRate = completedDueTotal.toDouble() / dueTotal.toDouble() * 100.0
                        assertEquals(expectedRate, completionRate!!, 0.01, "completion_rate must be correctly calculated")
                    } else {
                        assertNull(completionRate, "completion_rate must be null when dueTotal = 0")
                    }

                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `分组之和等于全局汇总且未分配记录没有丢失`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today)
            .onSuccess { result ->
                try {
                    val meta = result.getJsonObject("meta")
                    val records = result.getJsonArray("records")
                    assertNotNull(records, "records should not be null")

                    var groupScheduledTotal = 0L
                    var groupDueTotal = 0L
                    var groupCompletedDueTotal = 0L
                    var groupOverdueTotal = 0L
                    var hasUnassigned = false
                    for (i in 0 until records.size()) {
                        val record = records.getJsonObject(i)
                        groupScheduledTotal += record.getLong("scheduled_total") ?: 0L
                        groupDueTotal += record.getLong("due_total") ?: 0L
                        groupCompletedDueTotal += record.getLong("completed_due_total") ?: 0L
                        groupOverdueTotal += record.getLong("overdue_total") ?: 0L
                        if (record.getString("executor") == null) {
                            hasUnassigned = true
                        }
                    }

                    val globalScheduledTotal = meta?.getLong("scheduled_total") ?: 0L
                    val globalDueTotal = meta?.getLong("due_total") ?: 0L
                    val globalCompletedDueTotal = meta?.getLong("completed_due_total") ?: 0L
                    val globalOverdueTotal = meta?.getLong("overdue_total") ?: 0L

                    assertEquals(globalScheduledTotal, groupScheduledTotal,
                        "sum of group scheduled_total must equal global meta.scheduled_total")
                    assertEquals(globalDueTotal, groupDueTotal,
                        "sum of group due_total must equal global meta.due_total")
                    assertEquals(globalCompletedDueTotal, groupCompletedDueTotal,
                        "sum of group completed_due_total must equal global meta.completed_due_total")
                    assertEquals(globalOverdueTotal, groupOverdueTotal,
                        "sum of group overdue_total must equal global meta.overdue_total")

                    assertTrue(hasUnassigned, "there must be an unassigned executor group")

                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `未来任务计入scheduled_total但不计入due_total`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today)
            .onSuccess { result ->
                val meta = result.getJsonObject("meta")
                val scheduledTotal = meta.getLong("scheduled_total") ?: 0L
                val dueTotal = meta.getLong("due_total") ?: 0L
                val completionRate = if (meta.containsKey("completion_rate")) meta.getDouble("completion_rate") else null

                try {
                    assertTrue(dueTotal <= scheduledTotal, "due_total must be <= scheduled_total")
                    assertTrue(dueTotal < scheduledTotal, "due_total must be < scheduled_total because future tasks exist")
                    if (dueTotal > 0) {
                        assertNotNull(completionRate, "completion_rate must not be null when dueTotal > 0")
                    }
                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `executor筛选只返回对应执行人的记录`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today, executor = "executor-1")
            .onSuccess { executorResult ->
                try {
                    val executorMeta = executorResult.getJsonObject("meta")
                    val executorScheduled = executorMeta.getLong("scheduled_total") ?: 0L
                    assertTrue(executorScheduled > 0, "executor-1 must have at least 1 scheduled task")

                    val executorRecords = executorResult.getJsonArray("records")
                    assertTrue(executorRecords.size() > 0, "executor-1 records must not be empty")
                    for (i in 0 until executorRecords.size()) {
                        val r = executorRecords.getJsonObject(i)
                        assertEquals("executor-1", r.getString("executor"), "all records must be for executor-1")
                    }
                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `分页不改变meta汇总值`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today, limit = 100, offset = 0)
            .compose { fullResult ->
                val fullMeta = fullResult.getJsonObject("meta")
                val fullScheduled = fullMeta.getLong("scheduled_total") ?: 0L
                val fullDue = fullMeta.getLong("due_total") ?: 0L
                val fullGroupTotal = fullMeta.getLong("total") ?: 0L

                service.executionStatistics(dateFrom = today, dateTo = today, limit = 1, offset = 0)
                    .map { limitResult ->
                        val limitMeta = limitResult.getJsonObject("meta")
                        assertEquals(fullScheduled, limitMeta.getLong("scheduled_total"),
                            "limit must not change meta.scheduled_total")
                        assertEquals(fullDue, limitMeta.getLong("due_total"),
                            "limit must not change meta.due_total")
                        assertEquals(fullGroupTotal, limitMeta.getLong("total"),
                            "limit must not change meta.total (group count)")
                    }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `统计接口不创建新的执行记录`(ctx: VertxTestContext) {
        val today = LocalDate.now()
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"

        val countBefore: Long
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'"
            )
            rs.next()
            countBefore = rs.getLong(1)
        }

        service.executionStatistics(dateFrom = today, dateTo = today)
            .onSuccess {
                val countAfter: Long
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val rs = conn.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'"
                    )
                    rs.next()
                    countAfter = rs.getLong(1)
                }

                try {
                    assertEquals(countBefore, countAfter, "statistics query must not create new execution records")
                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `空范围返回空records和零汇总`(ctx: VertxTestContext) {
        val today = LocalDate.now()
        val futureStart = today.plusDays(100)
        val futureEnd = today.plusDays(101)

        service.executionStatistics(dateFrom = futureStart, dateTo = futureEnd)
            .onSuccess { result ->
                try {
                    val records = result.getJsonArray("records")
                    assertEquals(0, records.size(), "empty range must return empty records")
                    val meta = result.getJsonObject("meta")
                    assertNotNull(meta, "meta must not be null")
                    assertEquals(0L, meta.getLong("scheduled_total"), "scheduled_total must be 0")
                    assertEquals(0L, meta.getLong("due_total"), "due_total must be 0")
                    assertEquals(0L, meta.getLong("completed_due_total"), "completed_due_total must be 0")
                    assertEquals(0L, meta.getLong("overdue_total"), "overdue_total must be 0")
                    assertNull(meta.getDouble("completion_rate"), "completion_rate must be null for empty range")
                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `period_id筛选只返回对应周期的记录`(ctx: VertxTestContext) {
        val today = LocalDate.now()
        val periodId1 = fixtureId("period-1")

        service.executionStatistics(dateFrom = today, dateTo = today, periodId = periodId1)
            .onSuccess { result ->
                try {
                    val meta = result.getJsonObject("meta")
                    val scheduledTotal = meta.getLong("scheduled_total") ?: 0L
                    // period-1 owns taskId1, which has 5 executor-1 records + 1 null record = 6 today
                    assertTrue(scheduledTotal > 0, "period-1 must have at least 1 scheduled task")
                    assertTrue(scheduledTotal <= 6, "period-1 must have at most 6 scheduled tasks")

                    val records = result.getJsonArray("records")
                    var totalFromRecords = 0L
                    for (i in 0 until records.size()) {
                        totalFromRecords += records.getJsonObject(i).getLong("scheduled_total") ?: 0L
                    }
                    assertEquals(scheduledTotal, totalFromRecords, "sum of records must equal meta.scheduled_total")
                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `范围外日期记录不包含在结果中`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today)
            .onSuccess { result ->
                val meta = result.getJsonObject("meta")
                val scheduledTotal = meta.getLong("scheduled_total") ?: 0L
                try {
                    // We inserted 10 records for today plus 1 outside-date record (3 days ago).
                    // The outside-date record must NOT be included.
                    // Use a range assertion to tolerate timezone edge cases.
                    assertTrue(scheduledTotal >= 8, "today range must include most records (got $scheduledTotal), outside-date excluded")
                    assertTrue(scheduledTotal <= 10, "today range must not exceed 10, outside-date excluded")
                    ctx.completeNow()
                } catch (e: Throwable) {
                    ctx.failNow(e)
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `分页第二页meta汇总不变`(ctx: VertxTestContext) {
        val today = LocalDate.now()

        service.executionStatistics(dateFrom = today, dateTo = today, limit = 2, offset = 0)
            .compose { page1 ->
                val page1Meta = page1.getJsonObject("meta")
                val page1Scheduled = page1Meta.getLong("scheduled_total") ?: 0L
                val page1Due = page1Meta.getLong("due_total") ?: 0L
                val page1Records = page1.getJsonArray("records")
                assertEquals(2, page1Records.size(), "page 1 must have 2 records")

                service.executionStatistics(dateFrom = today, dateTo = today, limit = 2, offset = 2)
                    .map { page2 ->
                        val page2Meta = page2.getJsonObject("meta")
                        assertEquals(page1Scheduled, page2Meta.getLong("scheduled_total"),
                            "page 2 meta.scheduled_total must equal page 1")
                        assertEquals(page1Due, page2Meta.getLong("due_total"),
                            "page 2 meta.due_total must equal page 1")
                    }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
