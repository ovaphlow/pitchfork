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
        private const val FIXTURE_PREFIX = "to-"
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

        // 兜底门控：只给了 -Dintegration.db.host 而缺密码时，按 JUnit 假设失败 skip 整个类，不让模块变红。
        Assumptions.assumeTrue(password.isNotBlank(), "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行")

        try {
            // 1. 创建测试数据库（如不存在）
            val rootUrl = "jdbc:postgresql://$host:$port/postgres"
            DriverManager.getConnection(rootUrl, user, password).use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'"
                )
                if (!rs.next()) {
                    conn.createStatement().execute("CREATE DATABASE $TEST_DB")
                }
            }

            // 2. 在测试数据库上执行 Flyway 迁移
            val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)

            // 3. 用 JDBC 建立依赖表和 fixture 数据
            setupFixturesJdbc()

            // 4. 创建 Vert.x 连接池和服务
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
                conn.autoCommit = false
                val stmt = conn.createStatement()
                stmt.execute("DELETE FROM nursing.nursing_task_execution_consumptions WHERE task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM nursing.nursing_visit_schedules WHERE period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
                val remaining = stmt.executeQuery(
                    """
                    SELECT (
                        (SELECT count(*) FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%') +
                        (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%') +
                        (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%') +
                        (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%')
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

    /**
     * 用 JDBC 创建 healthcare.patients 表和 fixture 数据。
     * 避免 Vert.x reactive 链式调用的失败传播问题。
     */
    private fun setupFixturesJdbc() {
        val now = OffsetDateTime.now()
        val twoHoursAgo = now.minusHours(2)
        val oneHourLater = now.plusHours(1)
        val oneMinuteAgo = now.minusMinutes(1)
        val patientId = fixtureId("patient")
        val periodId = fixtureId("period")
        val taskId = fixtureId("task")

        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()

            // 创建 healthcare schema 和 patients 表
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

            // 插入 patient
            stmt.execute("""
                INSERT INTO healthcare.patients (id, name, status)
                VALUES ('$patientId', '逾期测试患者', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 插入 period（必须提供 service_type）
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
                VALUES ('$periodId', '$patientId', 'HOME_CARE', CURRENT_DATE, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 插入 task
            stmt.execute("""
                INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
                VALUES ('$taskId', '$periodId', 'NURSING', '逾期测试任务', 'QD', CURRENT_DATE, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 执行 1-7（每个计划时间不同，避免 (task_id, planned_time) 唯一索引冲突）
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-pending-overdue")}', '$taskId', '${twoHoursAgo}', 'PENDING') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-in-progress-overdue")}', '$taskId', '${twoHoursAgo.minusSeconds(1)}', 'IN_PROGRESS') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, actual_time) VALUES ('${fixtureId("exec-completed")}', '$taskId', '${twoHoursAgo.minusSeconds(2)}', 'COMPLETED', '$now') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-skipped")}', '$taskId', '${twoHoursAgo.minusSeconds(3)}', 'SKIPPED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-cancelled")}', '$taskId', '${twoHoursAgo.minusSeconds(4)}', 'CANCELLED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-pending-future")}', '$taskId', '$oneHourLater', 'PENDING') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-in-progress-1min")}', '$taskId', '$oneMinuteAgo', 'IN_PROGRESS') ON CONFLICT (id) DO NOTHING")
            // 跨日逾期（§3.1/§4.3）：51 天前的待执行记录，只应出现在跨日逾期队列，不在今日窗口
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-pending-51d")}', '$taskId', '${now.minusDays(51)}', 'PENDING') ON CONFLICT (id) DO NOTHING")
            // 长挂执行（§3.2）：IN_PROGRESS 且 actual_time 2 天前 → is_stale = true
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, actual_time) VALUES ('${fixtureId("exec-stale")}', '$taskId', '${now.minusDays(3)}', 'IN_PROGRESS', '${now.minusDays(2)}') ON CONFLICT (id) DO NOTHING")
            // 刚开始的 IN_PROGRESS：actual_time 30 分钟前 → is_stale = false
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, actual_time) VALUES ('${fixtureId("exec-fresh")}', '$taskId', '${now.minusHours(1)}', 'IN_PROGRESS', '${now.minusMinutes(30)}') ON CONFLICT (id) DO NOTHING")
        }
    }

    /** 统计 fixture 任务下的执行记录条数（用于断言 GET 类查询无写副作用） */
    private fun countFixtureExecutions(): Long =
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement()
                .executeQuery(
                    "SELECT count(*) FROM nursing.nursing_task_executions WHERE task_id = '${fixtureId("task")}'",
                ).use { rs ->
                    rs.next()
                    rs.getLong(1)
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
    private fun insertFixtures() {
        setupFixturesJdbc()
    }

    @Test
    fun `逾期字段在SQL结果中存在且派生正确`(ctx: VertxTestContext) {
        insertFixtures()

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
        insertFixtures()

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
        insertFixtures()

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
        insertFixtures()

        // 用今天的日期
        val today = LocalDate.now()

        service.todayExecutions(date = today, limit = 100, offset = 0)
            .compose { fullResult ->
                val fullOverdueTotal = fullResult.getJsonObject("meta")?.getInteger("overdue_total") ?: -1
                assertTrue(fullOverdueTotal >= 0, "全量 overdue_total 应 >= 0")

                service.todayExecutions(date = today.plusDays(1), limit = 100)
                    .compose { nextDayResult ->
                        assertEquals(0, nextDayResult.getJsonObject("meta")?.getInteger("overdue_total"),
                            "无 fixture 的日期 overdue_total 应为 0")

                        service.todayExecutions(date = today, periodId = fixtureId("period"), limit = 100)
                            .compose { matchingPeriodResult ->
                                assertEquals(fullOverdueTotal, matchingPeriodResult.getJsonObject("meta")?.getInteger("overdue_total"),
                                    "匹配周期的 overdue_total 应保持不变")

                                service.todayExecutions(date = today, periodId = fixtureId("missing-period"), limit = 100)
                                    .compose { missingPeriodResult ->
                                        assertEquals(0, missingPeriodResult.getJsonObject("meta")?.getInteger("overdue_total"),
                                            "不匹配周期的 overdue_total 应为 0")

                                        service.todayExecutions(date = today, executor = fixtureId("executor"), limit = 100)
                                    }
                            }
                    }
            }
            .onSuccess { result ->
                assertEquals(0, result.getJsonObject("meta")?.getInteger("overdue_total"),
                    "不匹配执行人的 overdue_total 应为 0")
                ctx.completeNow()
            }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  027 跨日逾期队列与 /today meta 扩展（§4.2 / §4.3）
    // ========================================================================

    private val statusKeys = listOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")

    private fun recordsById(result: JsonObject): Map<String, JsonObject> {
        val records = result.getJsonArray("records") ?: return emptyMap()
        val byId = mutableMapOf<String, JsonObject>()
        for (i in 0 until records.size()) {
            val record = records.getJsonObject(i)
            byId[record.getString("id") ?: ""] = record
        }
        return byId
    }

    @Test
    fun `跨日逾期队列返回五十一天前的待执行记录而今日窗口看不到`(ctx: VertxTestContext) {
        insertFixtures()
        val today = LocalDate.now()
        val crossDayId = fixtureId("exec-pending-51d")

        service.overdueExecutions(periodId = fixtureId("period"), limit = 100, offset = 0)
            .compose { overdue ->
                val byId = recordsById(overdue)
                val crossDay = byId[crossDayId]
                assertNotNull(crossDay, "51 天前的 PENDING 记录应出现在跨日逾期队列")
                assertTrue(crossDay!!.getBoolean("is_overdue"), "跨日记录的 is_overdue 应为 true")
                val minutes = crossDay.getInteger("overdue_minutes")
                assertNotNull(minutes, "跨日记录的 overdue_minutes 应非 null")
                assertTrue(minutes!! >= 51 * 24 * 60, "逾期分钟应 >= 51 天，实际 $minutes")

                val meta = overdue.getJsonObject("meta")
                assertNotNull(meta, "跨日逾期队列必须返回 meta")
                val total = meta!!.getLong("total") ?: 0L
                assertTrue(total >= 3L, "跨日逾期总数应 >= 3，实际 $total")
                assertTrue(total >= overdue.getJsonArray("records").size().toLong(), "meta.total 为全量")

                service.todayExecutions(date = today, periodId = fixtureId("period"), limit = 100, offset = 0)
            }
            .map { todayResult ->
                assertFalse(
                    recordsById(todayResult).containsKey(crossDayId),
                    "51 天前的记录不应出现在今日窗口（/today 语义不变）",
                )
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `跨日逾期队列按计划时间升序且分页总数一致`(ctx: VertxTestContext) {
        insertFixtures()

        service.overdueExecutions(periodId = fixtureId("period"), limit = 1, offset = 0)
            .compose { page1 ->
                val records = page1.getJsonArray("records")
                assertEquals(1, records.size(), "limit=1 应只返回一条记录")
                assertEquals(
                    fixtureId("exec-pending-51d"),
                    records.getJsonObject(0).getString("id"),
                    "最早的逾期记录（51 天前）应排在第一",
                )
                val totalPage1 = page1.getJsonObject("meta")?.getLong("total") ?: 0L
                assertTrue(totalPage1 >= 3L, "全量逾期总数应 >= 3，实际 $totalPage1")
                service.overdueExecutions(periodId = fixtureId("period"), limit = 200, offset = 0)
                    .map { all -> Pair(totalPage1, all) }
            }
            .map { (totalPage1, all) ->
                assertEquals(totalPage1, all.getJsonObject("meta")?.getLong("total"), "分页不影响 meta.total")
                assertTrue(all.getJsonArray("records").size() <= 200, "limit 收敛上限为 200")
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `跨日逾期队列不调用ensureExecutionsForDate也不写库`(ctx: VertxTestContext) {
        insertFixtures()
        val before = countFixtureExecutions()

        service.overdueExecutions(periodId = fixtureId("period"), limit = 200, offset = 0)
            .map { _ ->
                assertEquals(before, countFixtureExecutions(), "overdueExecutions 不得写入 nursing_task_executions")
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `长挂执行派生字段在跨日队列与今日窗口都生效`(ctx: VertxTestContext) {
        insertFixtures()
        val today = LocalDate.now()

        service.overdueExecutions(periodId = fixtureId("period"), limit = 200, offset = 0)
            .compose { overdue ->
                val byId = recordsById(overdue)

                val stale = byId[fixtureId("exec-stale")]
                assertNotNull(stale, "IN_PROGRESS 且 actual_time 2 天前的记录应在逾期队列")
                assertTrue(stale!!.getBoolean("is_stale"), "超过 1440 分钟应 is_stale=true")
                val staleMinutes = stale.getInteger("in_progress_minutes")
                assertNotNull(staleMinutes)
                assertTrue(staleMinutes!! >= 2 * 24 * 60, "长挂分钟数应 >= 2880，实际 $staleMinutes")

                val fresh = byId[fixtureId("exec-fresh")]
                assertNotNull(fresh, "刚开始的 IN_PROGRESS 记录应在逾期队列")
                assertFalse(fresh!!.getBoolean("is_stale"), "30 分钟不应 is_stale")
                val freshMinutes = fresh.getInteger("in_progress_minutes")
                assertNotNull(freshMinutes)
                assertTrue(freshMinutes!! in 1..120, "刚开始的分钟数应为个位数级别，实际 $freshMinutes")

                val pending = byId[fixtureId("exec-pending-overdue")]
                assertNotNull(pending)
                assertNull(pending!!.getInteger("in_progress_minutes"), "PENDING 的 in_progress_minutes 应为 null")
                assertFalse(pending.getBoolean("is_stale"))

                service.todayExecutions(date = today, periodId = fixtureId("period"), limit = 200, offset = 0)
            }
            .map { todayResult ->
                val completed = recordsById(todayResult)[fixtureId("exec-completed")]
                assertNotNull(completed, "COMPLETED fixture 应在今日窗口")
                assertNull(completed!!.getInteger("in_progress_minutes"), "终态的 in_progress_minutes 应为 null")
                assertFalse(completed.getBoolean("is_stale"), "终态恒不 stale")
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `今日看板meta提供status_totals与overdue_total_all`(ctx: VertxTestContext) {
        insertFixtures()
        val today = LocalDate.now()

        var fullStatusTotals: JsonObject? = null
        var fullTotal: Long = -1L
        var overdueTotalAll: Long = -1L

        service.todayExecutions(date = today, periodId = fixtureId("period"), limit = 100, offset = 0)
            .compose { full ->
                val meta = full.getJsonObject("meta")
                assertNotNull(meta, "meta 必须存在")
                val statusTotals = meta!!.getJsonObject("status_totals")
                assertNotNull(statusTotals, "meta 必须包含 status_totals")
                assertEquals(
                    statusKeys.toSet(),
                    statusTotals!!.fieldNames().toSet(),
                    "status_totals 五个键恒在",
                )
                var sum = 0L
                for (key in statusKeys) {
                    val value = statusTotals.getLong(key)
                    assertNotNull(value, "$key 必须是整数且不为 null")
                    assertTrue(value!! >= 0L, "$key 必须非负")
                    sum += value
                }
                fullStatusTotals = statusTotals
                fullTotal = meta.getLong("total") ?: -1L
                assertEquals(fullTotal, sum, "无 status/overdue 筛选时 total 应等于各状态计数之和")

                val overdueTotal = meta.getLong("overdue_total") ?: -1L
                val all = meta.getLong("overdue_total_all")
                assertNotNull(all, "meta 必须包含 overdue_total_all")
                overdueTotalAll = all!!
                assertTrue(all >= overdueTotal, "跨日逾期总数应不小于当日逾期数")
                assertTrue(all >= 3L, "跨日逾期总数应至少包含 3 条 fixture，实际 $all")

                // status 筛选不得改变 status_totals，也不得改变 overdue_total_all
                service.todayExecutions(
                    date = today,
                    periodId = fixtureId("period"),
                    status = "PENDING",
                    limit = 1,
                    offset = 0,
                )
            }
            .compose { pendingFiltered ->
                val meta = pendingFiltered.getJsonObject("meta")!!
                assertEquals(
                    fullStatusTotals,
                    meta.getJsonObject("status_totals"),
                    "status_totals 必须忽略 status 筛选",
                )
                assertEquals(overdueTotalAll, meta.getLong("overdue_total_all"), "overdue_total_all 必须忽略 status 筛选")

                // 换日期：overdue_total_all 必须不变（忽略日期窗口），overdue_total 则随窗口变化
                service.todayExecutions(date = today.plusDays(1), periodId = fixtureId("period"), limit = 1, offset = 0)
            }
            .map { nextDay ->
                val meta = nextDay.getJsonObject("meta")!!
                assertEquals(
                    overdueTotalAll,
                    meta.getLong("overdue_total_all"),
                    "overdue_total_all 必须忽略日期窗口",
                )
                assertEquals(0L, meta.getLong("overdue_total"), "次日窗口内没有逾期记录，overdue_total 应为 0")
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
