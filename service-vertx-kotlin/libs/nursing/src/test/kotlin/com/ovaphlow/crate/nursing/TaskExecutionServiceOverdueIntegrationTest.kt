package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * TaskExecutionService 逾期提醒的数据库集成测试。
 *
 * 需要真实 PostgreSQL 18.4+ 数据库，通过 Gradle 系统属性传递连接参数：
 *
 *   ./gradlew :libs:nursing:test
 *     -Dintegration.db.host=localhost
 *     -Dintegration.db.port=5432
 *     -Dintegration.db.user=ovaphlow
 *     --tests "*OverdueIntegrationTest*"
 *
 * 环境变量 PITCHFORK_DB_PASSWORD 必须设置为数据库密码。
 *
 * 测试行为：
 *   1. 在 @BeforeAll 中自动创建独立测试数据库 aceso_test（如不存在）
 *   2. 执行 Flyway 迁移创建 nursing schema 和表
 *   3. 插入隔离的 fixture 数据（ID 以 test- 前缀标记）
 *   4. 执行查询并验证逾期判定逻辑
 *   5. 在 @AfterAll 中清理 fixture 数据
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionServiceOverdueIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        /** fixture 数据统一前缀，用于清理 */
        private const val FIXTURE_PREFIX = "test-overdue-"
        /** ULID 格式的时间戳前缀（2026年7月的示例值），仅用于排序 */
        private val BASE_TIME = OffsetDateTime.of(2026, 7, 30, 8, 0, 0, 0, ZoneOffset.ofHours(8))
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var pool: Pool
    private lateinit var service: TaskExecutionService
    private var password: String = ""

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
            // 1. 创建测试数据库（如不存在）
            val jdbcUrl = "jdbc:postgresql://$host:$port/postgres"
            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'"
                )
                if (!rs.next()) {
                    conn.createStatement().execute("CREATE DATABASE $TEST_DB")
                }
            }

            // 2. 在测试数据库上执行 Flyway 迁移
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)

            // 3. 创建 Vert.x 连接池
            pool = DatabaseConfig.createPool(
                Vertx.vertx(),
                dbConfig
            )
            service = TaskExecutionService(pool)

            ctx.completeNow()
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @AfterAll
    fun cleanup(ctx: VertxTestContext) {
        // 清理所有 fixture 数据
        if (::pool.isInitialized) {
            pool.withTransaction { conn ->
                conn.query("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'").execute()
                    .compose { conn.query("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'").execute() }
                    .compose { conn.query("DELETE FROM nursing.nursing_plan_items WHERE id LIKE '${FIXTURE_PREFIX}%'").execute() }
                    .compose { conn.query("DELETE FROM nursing.nursing_plans WHERE id LIKE '${FIXTURE_PREFIX}%'").execute() }
                    .compose { conn.query("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'").execute() }
                    .compose { conn.query("DELETE FROM nursing.nursing_assessments WHERE id LIKE '${FIXTURE_PREFIX}%'").execute() }
            }.onComplete { ar ->
                pool.close()
                if (ar.succeeded()) ctx.completeNow()
                else ctx.failNow(ar.cause())
            }
        } else {
            ctx.completeNow()
        }
    }

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}${suffix}"

    /**
     * 准备测试 fixture：创建必需的依赖表和测试执行记录。
     *
     * 创建以下记录：
     *   - PENDING, 计划时间 2 小时前 → 应逾期
     *   - IN_PROGRESS, 计划时间 2 小时前 → 应逾期
     *   - COMPLETED, 计划时间 2 小时前 → 不应逾期
     *   - SKIPPED, 计划时间 2 小时前 → 不应逾期
     *   - CANCELLED, 计划时间 2 小时前 → 不应逾期
     *   - PENDING, 计划时间 1 小时后 → 不应逾期
     *   - IN_PROGRESS, 计划时间刚过 1 分钟 → 应逾期 1 分钟
     */
    private fun insertFixtures(ctx: VertxTestContext) {
        val now = OffsetDateTime.now()
        val twoHoursAgo = now.minusHours(2)
        val oneHourLater = now.plusHours(1)
        val oneMinuteAgo = now.minusMinutes(1)

        val patientId = fixtureId("patient")
        val periodId = fixtureId("period")
        val taskId = fixtureId("task")

        pool.withTransaction { conn ->
            // 创建 healthcare schema 和 patients 表
            conn.query("""
                CREATE SCHEMA IF NOT EXISTS healthcare
            """).execute()
                .compose { conn.query("""
                    CREATE TABLE IF NOT EXISTS healthcare.patients (
                        id VARCHAR(32) PRIMARY KEY,
                        name VARCHAR NOT NULL DEFAULT '',
                        gender VARCHAR NOT NULL DEFAULT '',
                        status VARCHAR DEFAULT 'ACTIVE',
                        created_at TIMESTAMPTZ DEFAULT now(),
                        updated_at TIMESTAMPTZ DEFAULT now()
                    )
                """).execute() }
                .compose { conn.query("""
                    INSERT INTO healthcare.patients (id, name, status)
                    VALUES ('$patientId', '逾期测试患者', 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
                    VALUES ('$periodId', '$patientId', 'HOME_CARE', CURRENT_DATE, 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
                    VALUES ('$taskId', '$periodId', 'NURSING', '逾期测试任务', 'QD', CURRENT_DATE, 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 1: PENDING + 2小时前 → 逾期
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                    VALUES ('${fixtureId("exec-pending-overdue")}', '$taskId', '${twoHoursAgo}', 'PENDING')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 2: IN_PROGRESS + 2小时前 → 逾期
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                    VALUES ('${fixtureId("exec-in-progress-overdue")}', '$taskId', '${twoHoursAgo}', 'IN_PROGRESS')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 3: COMPLETED + 2小时前 → 不逾期
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, actual_time)
                    VALUES ('${fixtureId("exec-completed")}', '$taskId', '${twoHoursAgo}', 'COMPLETED', '$now')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 4: SKIPPED + 2小时前 → 不逾期
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                    VALUES ('${fixtureId("exec-skipped")}', '$taskId', '${twoHoursAgo}', 'SKIPPED')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 5: CANCELLED + 2小时前 → 不逾期
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                    VALUES ('${fixtureId("exec-cancelled")}', '$taskId', '${twoHoursAgo}', 'CANCELLED')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 6: PENDING + 1小时后 → 不逾期
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                    VALUES ('${fixtureId("exec-pending-future")}', '$taskId', '${oneHourLater}', 'PENDING')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
                // 执行 7: IN_PROGRESS + 1分钟前 → 逾期 1 分钟
                .compose { conn.query("""
                    INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                    VALUES ('${fixtureId("exec-in-progress-1min")}', '$taskId', '${oneMinuteAgo}', 'IN_PROGRESS')
                    ON CONFLICT (id) DO NOTHING
                """).execute() }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `逾期字段在SQL结果中存在且派生正确`(ctx: VertxTestContext) {
        insertFixtures(ctx)

        // 等待 fixture 插入完成后再执行查询
        // 使用 VertxTestContext 的延迟完成机制
        val today = LocalDate.now()

        service.todayExecutions(date = today, limit = 100, offset = 0)
            .onSuccess { result ->
                try {
                    val records = result.getJsonArray("records")
                    assertNotNull(records, "records 不能为空")
                    assertTrue(records.size() >= 7, "至少应有 7 条 fixture 记录")

                    // 构建 ID 索引
                    val byId = mutableMapOf<String, JsonObject>()
                    for (i in 0 until records.size()) {
                        val r = records.getJsonObject(i)
                        byId[r.getString("id") ?: ""] = r
                    }

                    // 1. PENDING + 过去时间 → 逾期
                    val pendOverdue = byId[fixtureId("exec-pending-overdue")]
                    assertNotNull(pendOverdue, "PENDING 逾期记录应存在")
                    assertTrue(pendOverdue!!.getBoolean("is_overdue"), "PENDING + 过去时间应为逾期")
                    assertNotNull(pendOverdue.getInteger("overdue_minutes"), "逾期分钟数应非 null")
                    assertTrue(pendOverdue.getInteger("overdue_minutes") >= 119, "逾期分钟应 >= 119（2小时-1秒）")

                    // 2. IN_PROGRESS + 过去时间 → 逾期
                    val ipOverdue = byId[fixtureId("exec-in-progress-overdue")]
                    assertNotNull(ipOverdue, "IN_PROGRESS 逾期记录应存在")
                    assertTrue(ipOverdue!!.getBoolean("is_overdue"), "IN_PROGRESS + 过去时间应为逾期")

                    // 3. COMPLETED + 过去时间 → 不逾期
                    val completed = byId[fixtureId("exec-completed")]
                    assertNotNull(completed, "COMPLETED 记录应存在")
                    assertFalse(completed!!.getBoolean("is_overdue"), "COMPLETED + 过去时间不应逾期")
                    assertNull(completed.getInteger("overdue_minutes"), "COMPLETED 的 overdue_minutes 应为 null")

                    // 4. SKIPPED + 过去时间 → 不逾期
                    val skipped = byId[fixtureId("exec-skipped")]
                    assertNotNull(skipped, "SKIPPED 记录应存在")
                    assertFalse(skipped!!.getBoolean("is_overdue"), "SKIPPED + 过去时间不应逾期")

                    // 5. CANCELLED + 过去时间 → 不逾期
                    val cancelled = byId[fixtureId("exec-cancelled")]
                    assertNotNull(cancelled, "CANCELLED 记录应存在")
                    assertFalse(cancelled!!.getBoolean("is_overdue"), "CANCELLED + 过去时间不应逾期")

                    // 6. PENDING + 未来时间 → 不逾期
                    val future = byId[fixtureId("exec-pending-future")]
                    assertNotNull(future, "PENDING 未来记录应存在")
                    assertFalse(future!!.getBoolean("is_overdue"), "PENDING + 未来时间不应逾期")

                    // 7. IN_PROGRESS + 1分钟前 → 逾期 1 分钟
                    val oneMin = byId[fixtureId("exec-in-progress-1min")]
                    assertNotNull(oneMin, "IN_PROGRESS 1分钟前记录应存在")
                    assertTrue(oneMin!!.getBoolean("is_overdue"), "IN_PROGRESS + 1分钟前应为逾期")
                    assertEquals(1, oneMin.getInteger("overdue_minutes"), "逾期应为 1 分钟")

                    // 验证 meta.overdue_total 存在且 >= 3（至少 3 条逾期）
                    val meta = result.getJsonObject("meta")
                    assertNotNull(meta, "meta 不应为 null")
                    val overdueTotal = meta?.getInteger("overdue_total") ?: 0
                    assertTrue(overdueTotal >= 3, "overdue_total 应 >= 3，实际为 $overdueTotal")

                    ctx.completeNow()
                } catch (e: Exception) {
                    ctx.failNow(e)
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `overdue筛选只返回逾期记录且overdue_total不受status影响`(ctx: VertxTestContext) {
        insertFixtures(ctx)

        val today = LocalDate.now()

        // Step 1: 获取全量列表
        service.todayExecutions(date = today, limit = 100, offset = 0)
            .compose { fullResult ->
                val fullMeta = fullResult.getJsonObject("meta")
                val fullOverdueTotal = fullMeta?.getInteger("overdue_total") ?: 0
                val fullTotal = fullMeta?.getInteger("total") ?: 0

                // Step 2: overdue=true 筛选
                service.todayExecutions(date = today, overdue = true, limit = 100, offset = 0)
                    .compose { overdueResult ->
                        val overdueMeta = overdueResult.getJsonObject("meta")
                        val overdueTotal = overdueMeta?.getInteger("overdue_total") ?: -1

                        // overdue_total 应与全量查询一致（不受 overdue 筛选影响）
                        assertEquals(fullOverdueTotal, overdueTotal,
                            "overdue_total 不应受 overdue 参数影响")

                        // overdue=true 时所有记录必须 is_overdue=true
                        val records = overdueResult.getJsonArray("records")
                        for (i in 0 until records.size()) {
                            val record = records.getJsonObject(i)
                            assertTrue(record.getBoolean("is_overdue"),
                                "overdue=true 时记录 $i 应为逾期: ${record.getString("id")}")
                        }

                        // total 应只包含逾期记录数
                        assertEquals(records.size(), overdueMeta?.getInteger("total"),
                            "overdue=true 时 total 应等于逾期记录数")

                        // Step 3: 使用 status=PENDING 筛选 → overdue_total 不变
                        service.todayExecutions(date = today, status = "PENDING", limit = 100, offset = 0)
                            .map { statusResult ->
                                val statusMeta = statusResult.getJsonObject("meta")
                                assertEquals(fullOverdueTotal, statusMeta?.getInteger("overdue_total"),
                                    "overdue_total 不应受 status=PENDING 筛选影响")
                            }
                    }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `分页第二页不改变overdue_total`(ctx: VertxTestContext) {
        insertFixtures(ctx)

        val today = LocalDate.now()

        service.todayExecutions(date = today, limit = 1, offset = 0)
            .compose { page1 ->
                val page1Meta = page1.getJsonObject("meta")
                val page1OverdueTotal = page1Meta?.getInteger("overdue_total") ?: 0

                service.todayExecutions(date = today, limit = 1, offset = 1)
                    .map { page2 ->
                        val page2Meta = page2.getJsonObject("meta")
                        assertEquals(page1OverdueTotal, page2Meta?.getInteger("overdue_total"),
                            "分页第二页的 overdue_total 应与第一页相同")
                    }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `指定日期范围不影响overdue_total存在性`(ctx: VertxTestContext) {
        insertFixtures(ctx)

        // 用今天的日期
        val today = LocalDate.now()

        service.todayExecutions(date = today, limit = 100, offset = 0)
            .onSuccess { result ->
                try {
                    val meta = result.getJsonObject("meta")
                    assertNotNull(meta, "meta 应为非 null")
                    val overdueTotal = meta?.getInteger("overdue_total")
                    assertNotNull(overdueTotal, "overdue_total 应为非 null")
                    assertTrue(overdueTotal!! >= 0, "overdue_total 应 >= 0")
                    ctx.completeNow()
                } catch (e: Exception) {
                    ctx.failNow(e)
                }
            }.onFailure { ctx.failNow(it) }
    }
}
