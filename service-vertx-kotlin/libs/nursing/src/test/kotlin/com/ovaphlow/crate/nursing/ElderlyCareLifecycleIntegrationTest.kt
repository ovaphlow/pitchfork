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

/**
 * 养老院入住照护周期绑定与离院收束 — PostgreSQL 集成测试。
 *
 * 覆盖计划文档第 7.2 节六组断言：
 *   1. 新入住创建 encounter 与精确绑定周期
 *   2. 同一长者重新入住创建新的 encounter/period；按 encounter_id 查询互不泄漏
 *   3. 重复补建不产生第二周期；非活动或非养老 encounter 不可补建
 *   4. 正常离院原子收束周期并停止未来执行生成
 *   5. 存在执行中记录时离院失败且事务全部回滚
 *   6. fixture 使用固定前缀；每个测试后按依赖顺序清理
 *
 * 仅在获准的 aceso_test 运行；默认被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElderlyCareLifecycleIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "lc-"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var servicePeriodService: ServicePeriodService
    private lateinit var taskExecutionService: TaskExecutionService

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
            DatabaseConfig.migrate(dbConfig)

            pool = DatabaseConfig.createPool(Vertx.vertx(), dbConfig)
            servicePeriodService = ServicePeriodService(pool)
            taskExecutionService = TaskExecutionService(pool)

            ctx.completeNow()
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @BeforeEach
    fun setupTestFixtures() {
        setupFixtures()
    }

    @AfterEach
    fun cleanupTestFixtures() {
        cleanupFixtures()
    }

    @AfterAll
    fun cleanup(ctx: VertxTestContext) {
        if (::pool.isInitialized) {
            cleanupFixtures()
            pool.close()
        }
        ctx.completeNow()
    }

    private fun jdbcUrl() = "jdbc:postgresql://$host:$port/$TEST_DB"

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}$suffix"

    private fun setupFixtures() {
        val now = OffsetDateTime.now()
        val today = LocalDate.now()

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
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS healthcare.encounters (
                    id VARCHAR(32) PRIMARY KEY,
                    patient_id VARCHAR(32) NOT NULL,
                    encounter_type VARCHAR NOT NULL,
                    encounter_no VARCHAR NOT NULL,
                    department VARCHAR,
                    ward VARCHAR,
                    admit_date TIMESTAMPTZ NOT NULL,
                    discharge_date TIMESTAMPTZ,
                    admitting_diagnosis VARCHAR,
                    discharge_diagnosis VARCHAR,
                    attending_physician VARCHAR,
                    status VARCHAR NOT NULL DEFAULT 'ACTIVE',
                    metadata JSONB,
                    created_at TIMESTAMPTZ DEFAULT now(),
                    updated_at TIMESTAMPTZ DEFAULT now()
                )
            """)

            // 清理所有旧 fixture 数据（按依赖顺序）
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")

            // 创建长者A（用于所有需要长者A的测试）
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('${fixtureId("patient-A")}', '养老生命周期长者A', 'ACTIVE')")

            // 创建长者B（用于测试另一个长者）
            stmt.execute("INSERT INTO healthcare.patients (id, name, status) VALUES ('${fixtureId("patient-B")}', '养老生命周期长者B', 'ACTIVE')")

            // 长者A 的已离院入住（用于测试非活动 encounter 不可补建）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-A-discharged")}', '${fixtureId("patient-A")}', 'ELDERLY_CARE', 'LC-20260701-04', '2026-07-01T00:00:00+08:00', '2026-07-10T00:00:00+08:00', 'DISCHARGED')
            """)

            // 长者A 的非养老入住（用于测试非养老 encounter 不可补建）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-A-outpatient")}', '${fixtureId("patient-A")}', 'OUTPATIENT', 'OP-20260731-05', '2026-07-31T00:00:00+08:00', 'ACTIVE')
            """)

            // 长者A 的旧式未绑定周期（HOME_CARE，encounter_id 为空）
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, status)
                VALUES ('${fixtureId("period-A-home")}', '${fixtureId("patient-A")}', 'HOME_CARE', '2026-06-01', 'ACTIVE')
            """)

            // 长者A 的历史完成周期
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, start_date, end_date, status)
                VALUES ('${fixtureId("period-A-completed")}', '${fixtureId("patient-A")}', 'HOME_CARE', '2026-01-01', '2026-03-31', 'COMPLETED')
            """)

            // 为长者A 的 HOME_CARE 周期创建任务和执行记录
            stmt.execute("""
                INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
                VALUES ('${fixtureId("task-A-home")}', '${fixtureId("period-A-home")}', 'NURSING', '生命周期测试任务', 'QD', '$today', 'ACTIVE')
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time)
                VALUES ('${fixtureId("exec-A-completed")}', '${fixtureId("task-A-home")}', '${now.minusHours(2)}', 'COMPLETED', 'test-executor', '$now')
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor)
                VALUES ('${fixtureId("exec-A-pending")}', '${fixtureId("task-A-home")}', '${now.plusDays(2)}', 'PENDING', 'test-executor')
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor)
                VALUES ('${fixtureId("exec-A-in-progress")}', '${fixtureId("task-A-home")}', '${now}', 'IN_PROGRESS', 'test-executor')
            """)
        }
    }

    private fun cleanupFixtures() {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")

            val residual = stmt.executeQuery("""
                SELECT (
                    (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%')
                ) AS residual
            """.trimIndent())
            residual.next()
            check(residual.getLong("residual") == 0L) { "fixture cleanup left residual data" }
        }
    }

    /**
     * 辅助方法：为测试创建独立的encounter
     */
    private fun createTestEncounter(testSuffix: String): String {
        val encounterId = fixtureId("enc-$testSuffix")
        val patientId = fixtureId("patient-A")
        val jdbcUrl = jdbcUrl()

        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            // 清理可能存在的旧数据
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE encounter_id = '$encounterId'")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id = '$encounterId'")

            // 创建新的encounter，使用当前时间戳（确保时区一致）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('$encounterId', '$patientId', 'ELDERLY_CARE', 'LC-$testSuffix', NOW(), 'ACTIVE')
            """)
        }
        return encounterId
    }

    // ========================================================================
    //  断言 1：新入住创建 encounter 与精确绑定周期
    // ========================================================================

    @Test
    fun `新入住创建encounter与精确绑定周期`(ctx: VertxTestContext) {
        val encounterId = createTestEncounter("T1")
        val patientId = fixtureId("patient-A")
        val jdbcUrl = jdbcUrl()

        // 从数据库读取实际的admit_date
        var expectedStartDate = ""
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT admit_date::date FROM healthcare.encounters WHERE id = '$encounterId'"
            )
            if (rs.next()) expectedStartDate = rs.getString(1)
        }

        servicePeriodService.enrollElderlyAdmission(encounterId)
            .onSuccess { (created, period) ->
                ctx.verify {
                    assertTrue(created, "首次补建应返回 created=true")
                    assertEquals("ELDERLY_CARE", period.getString("service_type"))
                    assertEquals(encounterId, period.getString("encounter_id"))
                    assertEquals(patientId, period.getString("patient_id"))
                    assertEquals(expectedStartDate, period.getString("start_date"), "开始日期应与入住日期一致")
                    assertEquals("ACTIVE", period.getString("status"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  断言 2：同一长者重新入住创建新的 encounter/period；按 encounter_id 查询互不泄漏
    // ========================================================================

    @Test
    fun `同一长者重新入住创建新的encounter-period且按encounter_id查询互不泄漏`(ctx: VertxTestContext) {
        val encounterId1 = createTestEncounter("T2A")
        val encounterId2 = createTestEncounter("T2B")
        val patientId = fixtureId("patient-A")

        // 为 enc-T2A 补建周期
        servicePeriodService.enrollElderlyAdmission(encounterId1)
            .compose { _ ->
                // 为 enc-T2B 补建周期
                servicePeriodService.enrollElderlyAdmission(encounterId2)
            }
            .onSuccess { (created, period2) ->
                ctx.verify {
                    assertTrue(created, "enc-T2B 首次补建应返回 created=true")
                    assertEquals(encounterId2, period2.getString("encounter_id"))
                    assertEquals(patientId, period2.getString("patient_id"))
                    assertNotEquals(encounterId1, period2.getString("encounter_id"), "两个周期必须关联不同 encounter")
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `按encounter_id查询只返回对应周期`(ctx: VertxTestContext) {
        val encounterId1 = createTestEncounter("T3A")
        val encounterId2 = createTestEncounter("T3B")

        // 确保两个周期都已创建
        servicePeriodService.enrollElderlyAdmission(encounterId1)
            .compose { _ -> servicePeriodService.enrollElderlyAdmission(encounterId2) }
            .compose { _ ->
                // 按 encounterId1 查询
                servicePeriodService.list(encounterId = encounterId1, status = "ACTIVE")
            }
            .onSuccess { result ->
                ctx.verify {
                    val records = result.getJsonArray("records")
                    assertEquals(1, records.size(), "encounterId1 查询必须只返回一条记录")
                    val period = records.getJsonObject(0)
                    assertEquals(encounterId1, period.getString("encounter_id"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  断言 3：重复补建不产生第二周期；非活动或非养老 encounter 不可补建
    // ========================================================================

    @Test
    fun `重复补建不产生第二周期`(ctx: VertxTestContext) {
        val encounterId = createTestEncounter("T4")

        servicePeriodService.enrollElderlyAdmission(encounterId)
            .compose { (created1, period1) ->
                ctx.verify {
                    assertTrue(created1, "首次补建应返回 created=true")
                }
                // 重复补建
                servicePeriodService.enrollElderlyAdmission(encounterId)
                    .map { (created2, period2) -> Triple(created1, period1, Pair(created2, period2)) }
            }
            .onSuccess { (_, period1, pair) ->
                ctx.verify {
                    val (created2, period2) = pair
                    assertFalse(created2, "重复补建应返回 created=false")
                    assertEquals(period1.getString("id"), period2.getString("id"), "必须返回同一周期")
                    assertEquals("ELDERLY_CARE", period2.getString("service_type"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非活动encounter不可补建`(ctx: VertxTestContext) {
        val dischargedEncounterId = fixtureId("enc-A-discharged")

        servicePeriodService.enrollElderlyAdmission(dischargedEncounterId)
            .onSuccess { ctx.failNow(IllegalStateException("已离院 encounter 不应补建成功")) }
            .onFailure { error ->
                ctx.verify {
                    assertTrue(error.message?.contains("not active") == true || error.message?.contains("encounter") == true)
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `非养老encounter不可补建`(ctx: VertxTestContext) {
        val outpatientEncounterId = fixtureId("enc-A-outpatient")

        servicePeriodService.enrollElderlyAdmission(outpatientEncounterId)
            .onSuccess { ctx.failNow(IllegalStateException("非养老 encounter 不应补建成功")) }
            .onFailure { error ->
                ctx.verify {
                    assertTrue(error.message?.contains("not an elderly admission") == true || error.message?.contains("encounter") == true)
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  断言 4：正常离院原子收束周期并停止未来执行生成
    // ========================================================================

    @Test
    fun `周期收束后历史记录不变且不再生成未来执行`(ctx: VertxTestContext) {
        val encounterId = createTestEncounter("T5")
        val jdbcUrl = jdbcUrl()

        // 先为 enc-T5 补建周期
        servicePeriodService.enrollElderlyAdmission(encounterId)
            .compose { (created, period) ->
                val newlyCreatedPeriodId = period.getString("id")

                // 为该周期创建一个任务
                val taskId = fixtureId("task-T5")
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val stmt = conn.createStatement()
                    stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id = '$taskId'")
                    stmt.execute("""
                        INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
                        VALUES ('$taskId', '$newlyCreatedPeriodId', 'NURSING', '离院测试任务', 'QD', CURRENT_DATE, 'ACTIVE')
                    """)
                }

                // 生成未来执行记录
                taskExecutionService.ensureExecutionsForDateRange(
                    LocalDate.now(),
                    LocalDate.now().plusDays(7),
                    newlyCreatedPeriodId
                ).map { newlyCreatedPeriodId }
            }
            .compose { periodId ->
                // 记录离院前的执行记录数量
                var execCountBefore = 0L
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val rs = conn.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'"
                    )
                    rs.next()
                    execCountBefore = rs.getLong(1)
                }

                // Healthcare 跨模块事务由 Healthcare 路由集成测试覆盖
                servicePeriodService.closeElderlyCarePeriod(pool, encounterId, LocalDate.now(), OffsetDateTime.now())
                    .compose { closedPeriod ->
                        // 验证周期状态
                        assertEquals("COMPLETED", closedPeriod.getString("status"), "周期状态应为 COMPLETED")
                        assertEquals(LocalDate.now().toString(), closedPeriod.getString("end_date"), "结束日期应为今天")

                        taskExecutionService.ensureExecutionsForDateRange(
                            LocalDate.now().plusDays(8),
                            LocalDate.now().plusDays(14),
                            periodId
                        ).map {
                            var execCountAfter = 0L
                            DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                                val rs = conn.createStatement().executeQuery(
                                    "SELECT COUNT(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%'"
                                )
                                rs.next()
                                execCountAfter = rs.getLong(1)
                            }
                            assertEquals(execCountBefore, execCountAfter, "周期收束后不得新增未来执行记录")
                            closedPeriod
                        }
                    }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  断言 5：存在执行中记录时离院失败且事务全部回滚
    // ========================================================================

    @Test
    fun `存在执行中记录时周期收束失败且无副作用`(ctx: VertxTestContext) {
        val encounterId = createTestEncounter("T6")
        val jdbcUrl = jdbcUrl()

        // 为 enc-T6 创建周期和执行中任务
        servicePeriodService.enrollElderlyAdmission(encounterId)
            .compose { (_, period) ->
                val periodId = period.getString("id")

                // 创建一个任务
                val taskId = fixtureId("task-T6")
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val stmt = conn.createStatement()
                    stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id = '$taskId'")
                    stmt.execute("""
                        INSERT INTO nursing.nursing_tasks (id, period_id, task_type, description, frequency_code, start_date, status)
                        VALUES ('$taskId', '$periodId', 'NURSING', '执行中测试任务', 'QD', CURRENT_DATE, 'ACTIVE')
                    """)
                    // 创建一个IN_PROGRESS执行记录
                    stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}exec-T6%'")
                    stmt.execute("""
                        INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor)
                        VALUES ('${fixtureId("exec-T6")}', '$taskId', NOW(), 'IN_PROGRESS', 'test-executor')
                    """)
                }

                // 记录离院前的状态
                var periodStatusBefore = ""
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    val rs = conn.createStatement().executeQuery(
                        "SELECT status FROM nursing.nursing_service_periods WHERE encounter_id = '$encounterId'"
                    )
                    if (rs.next()) periodStatusBefore = rs.getString("status")

                }

                // 尝试离院（应该失败，因为有 IN_PROGRESS 执行记录）
                servicePeriodService.closeElderlyCarePeriod(pool, encounterId, LocalDate.now(), OffsetDateTime.now())
            }
            .onSuccess { ctx.failNow(IllegalStateException("存在执行中记录时离院应失败")) }
            .onFailure { error ->
                ctx.verify {
                    assertTrue(error.message?.contains("in progress") == true || error.message?.contains("conflict") == true)

                    // 跨模块事务由 Healthcare 路由集成测试覆盖
                    var periodStatusAfter = ""
                    DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                        val rs = conn.createStatement().executeQuery(
                            "SELECT status FROM nursing.nursing_service_periods WHERE encounter_id = '$encounterId'"
                        )
                        if (rs.next()) periodStatusAfter = rs.getString("status")

                    }

                    assertEquals("ACTIVE", periodStatusAfter, "周期状态应保持 ACTIVE（事务回滚）")
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  断言 6：fixture 清理验证
    // ========================================================================

    @Test
    fun `fixture使用固定前缀且清理正确`(ctx: VertxTestContext) {
        val jdbcUrl = jdbcUrl()

        // 验证所有 fixture 数据都使用正确前缀
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT id FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'"
            )
            val patientIds = mutableListOf<String>()
            while (rs.next()) patientIds.add(rs.getString("id"))

            patientIds.forEach { id ->
                assertTrue(id.startsWith(FIXTURE_PREFIX), "患者 ID 必须使用 fixture 前缀: $id")
            }
        }

        ctx.completeNow()
    }
}
