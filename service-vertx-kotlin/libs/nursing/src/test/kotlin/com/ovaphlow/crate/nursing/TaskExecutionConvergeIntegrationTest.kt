package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 逾期执行收口（030 §4.3）的数据库集成测试：在真实 PostgreSQL 上验证
 * `convergeOverdueExecutions` 的候选谓词、`metadata.convergence` 的 JSONB 浅合并、
 * `truncated` 判定与幂等语义（这些都不是 mock 形状断言能证明的）。
 *
 * 运行方式（隔离库，`aceso_test`，与仓库 `scripts/aceso-test.sh` 同一容器约定）：
 *
 *   ./gradlew :libs:nursing:test -Dintegration.db.host=localhost -Dintegration.db.port=55432 \
 *     -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *     --tests "*TaskExecutionConvergeIntegrationTest*"
 *
 * 前置：库必须已由**含 healthcare 段**的 classpath 迁移过（本类只建 nursing 表的 fixture；
 * 依赖表由本类自行插入，故只需 nursing schema，但同库中其它模块的缺失迁移会被
 * `DatabaseConfig.migrate` 的 `ignoreMigrationPatterns("*:missing")` 忽略）。
 *
 * 自包含：fixture 统一前缀 `cv-`，`@AfterAll` 删除并自证残差为 0。
 * 未设置 `integration.db.host` 时整个类跳过（不使模块变红）。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionConvergeIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "cv-"

        /** S1：两条逾期（PENDING + IN_PROGRESS，后者带既有 metadata）+ 终态 + 未到期 */
        private const val ENC_S1 = "${FIXTURE_PREFIX}enc-s1"
        private const val TASK_S1 = "${FIXTURE_PREFIX}task-s1"
        private const val EXE_S1_A = "${FIXTURE_PREFIX}exe-s1-a"
        private const val EXE_S1_B = "${FIXTURE_PREFIX}exe-s1-b"
        private const val EXE_S1_DONE = "${FIXTURE_PREFIX}exe-s1-done"
        private const val EXE_S1_FUTURE = "${FIXTURE_PREFIX}exe-s1-future"

        /** S2：三条逾期，用于 limit 截断 + 幂等 */
        private const val ENC_S2 = "${FIXTURE_PREFIX}enc-s2"
        private const val TASK_S2 = "${FIXTURE_PREFIX}task-s2"
        private const val EXE_S2_A = "${FIXTURE_PREFIX}exe-s2-a"
        private const val EXE_S2_B = "${FIXTURE_PREFIX}exe-s2-b"
        private const val EXE_S2_C = "${FIXTURE_PREFIX}exe-s2-c"

        /** S3：阈值边界（严格小于），用 59/61 分钟夹逼避免等值抖动 */
        private const val ENC_S3 = "${FIXTURE_PREFIX}enc-s3"
        private const val TASK_S3 = "${FIXTURE_PREFIX}task-s3"
        private const val EXE_S3_NEAR = "${FIXTURE_PREFIX}exe-s3-near"
        private const val EXE_S3_BEYOND = "${FIXTURE_PREFIX}exe-s3-beyond"
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
        Assumptions.assumeTrue(password.isNotBlank(), "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行")

        try {
            DriverManager.getConnection("jdbc:postgresql://$host:$port/postgres", user, password).use { conn ->
                val exists = conn.createStatement()
                    .executeQuery("SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'").use { it.next() }
                if (!exists) conn.createStatement().execute("CREATE DATABASE $TEST_DB")
            }
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            setupFixturesJdbc()
            pool = DatabaseConfig.createPool(Vertx.vertx(), dbConfig)
            service = TaskExecutionService(pool)
            ctx.completeNow()
        } catch (error: Exception) {
            ctx.failNow(error)
        }
    }

    @AfterAll
    fun cleanup(ctx: VertxTestContext) {
        try {
            jdbc().use { conn ->
                conn.autoCommit = false
                val stmt = conn.createStatement()
                stmt.execute(
                    "DELETE FROM nursing.nursing_task_executions WHERE id LIKE '$FIXTURE_PREFIX%' " +
                        "OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '$FIXTURE_PREFIX%')",
                )
                stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '$FIXTURE_PREFIX%'")
                stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '$FIXTURE_PREFIX%'")
                val remaining = stmt.executeQuery(
                    "SELECT (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '$FIXTURE_PREFIX%') + " +
                        "(SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '$FIXTURE_PREFIX%') + " +
                        "(SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '$FIXTURE_PREFIX%')",
                ).use { rs -> rs.next(); rs.getLong(1) }
                check(remaining == 0L) { "fixture cleanup left $remaining rows" }
                conn.commit()
            }
            if (::pool.isInitialized) {
                pool.close().onComplete { if (it.succeeded()) ctx.completeNow() else ctx.failNow(it.cause()) }
            } else {
                ctx.completeNow()
            }
        } catch (error: Exception) {
            ctx.failNow(error)
        }
    }

    // ─── 用例 ───────────────────────────────────────────────────────────

    @Test
    fun `收束逾期执行并把 convergence 浅合并进 metadata`(ctx: VertxTestContext) {
        val before2 = executionText(EXE_S1_B)
        service.convergeOverdueExecutions(encounterId = ENC_S1, minOverdueMinutes = 60, limit = 200)
            .onComplete { ar ->
                ctx.verify {
                    val body = ar.result()
                    assertEquals(2, body.getInteger("converged"), "S1 有且只有两条逾期: ${body.encode()}")
                    assertEquals(setOf(EXE_S1_A, EXE_S1_B), body.getJsonArray("ids").list.toSet())
                    assertEquals(60, body.getInteger("min_overdue_minutes"))
                    assertFalse(body.getBoolean("truncated"))
                }
                ctx.verify {
                    assertEquals("SKIPPED", queryText("SELECT status FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_A'"))
                    assertEquals("SKIPPED", queryText("SELECT status FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_B'"))
                    // 终态与未到期一律未被触碰
                    assertEquals("COMPLETED", queryText("SELECT status FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_DONE'"))
                    assertEquals("PENDING", queryText("SELECT status FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_FUTURE'"))
                    // JSONB 浅合并：既有键保留，convergence 键追加
                    assertEquals("v", queryText("SELECT metadata->>'keep' FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_B'"))
                    assertEquals(
                        "逾期未执行，批量标记漏执行",
                        queryText("SELECT metadata->'convergence'->>'reason' FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_B'"),
                    )
                    assertEquals(
                        "60",
                        queryText("SELECT metadata->'convergence'->>'min_overdue_minutes' FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_B'"),
                    )
                    assertTrue(
                        queryText("SELECT metadata->'convergence'->>'at' FROM nursing.nursing_task_executions WHERE id = '$EXE_S1_B'") != null,
                        "convergence.at 必须落库",
                    )
                    assertTrue(executionText(EXE_S1_B) != before2, "被收束的行必须发生变化")
                }
                ctx.completeNow()
            }
    }

    @Test
    fun `limit 截断后 truncated 为真且二次调用收尾剩余并对已收束幂等`(ctx: VertxTestContext) {
        service.convergeOverdueExecutions(encounterId = ENC_S2, minOverdueMinutes = 60, limit = 1)
            .compose { first ->
                ctx.verify {
                    assertEquals(1, first.getInteger("converged"))
                    assertTrue(first.getBoolean("truncated"), "还有剩余逾期时必须标 truncated: ${first.encode()}")
                }
                ctx.verify { assertEquals(2L, overdueRemainingCount(TASK_S2)) }
                service.convergeOverdueExecutions(encounterId = ENC_S2, minOverdueMinutes = 60, limit = 200)
            }
            .compose { second ->
                ctx.verify {
                    assertEquals(2, second.getInteger("converged"), "剩余两条应被收束: ${second.encode()}")
                    assertFalse(second.getBoolean("truncated"))
                }
                ctx.verify { assertEquals(0L, overdueRemainingCount(TASK_S2)) }
                service.convergeOverdueExecutions(encounterId = ENC_S2, minOverdueMinutes = 60, limit = 200)
            }
            .onComplete { ar ->
                ctx.verify {
                    val third = ar.result()
                    assertEquals(0, third.getInteger("converged"), "幂等：第三次不再改动任何行")
                    assertTrue(third.getJsonArray("ids").isEmpty)
                }
                ctx.completeNow()
            }
    }

    @Test
    fun `阈值严格小于按分钟判定`(ctx: VertxTestContext) {
        service.convergeOverdueExecutions(encounterId = ENC_S3, minOverdueMinutes = 60, limit = 200)
            .onComplete { ar ->
                ctx.verify {
                    val body = ar.result()
                    assertEquals(1, body.getInteger("converged"), "只应收束 61 分钟前那条: ${body.encode()}")
                    assertEquals(listOf(EXE_S3_BEYOND), body.getJsonArray("ids").list)
                    assertEquals("SKIPPED", queryText("SELECT status FROM nursing.nursing_task_executions WHERE id = '$EXE_S3_BEYOND'"))
                    assertEquals("PENDING", queryText("SELECT status FROM nursing.nursing_task_executions WHERE id = '$EXE_S3_NEAR'"))
                }
                ctx.completeNow()
            }
    }

    // ─── fixture / 查询辅助 ─────────────────────────────────────────────

    private fun jdbc(): Connection = DriverManager.getConnection("jdbc:postgresql://$host:$port/$TEST_DB", user, password)

    private fun queryText(sql: String): String? =
        jdbc().use { conn -> conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    /** 执行行快照（status|planned_time|metadata|executor），用于「未被触碰 / 已变化」比较。 */
    private fun executionText(id: String): String? = queryText(
        "SELECT status||'|'||coalesce(planned_time::text,'NULL')||'|'||coalesce(metadata::text,'NULL')||'|'||coalesce(executor,'NULL') " +
            "FROM nursing.nursing_task_executions WHERE id = '$id'",
    )

    /** 仍未终态且已过期的执行数（仅统计本 fixture 的 task）。 */
    private fun overdueRemainingCount(taskId: String): Long =
        queryText(
            "SELECT count(*) FROM nursing.nursing_task_executions " +
                "WHERE task_id = '$taskId' AND status IN ('PENDING','IN_PROGRESS') AND planned_time < now()",
        )?.toLong() ?: -1L

    private fun setupFixturesJdbc() {
        val now = OffsetDateTime.now()
        jdbc().use { conn ->
            val stmt = conn.createStatement()
            val today = LocalDate.now()

            fun insertPeriod(periodId: String, encounterId: String) {
                stmt.execute(
                    "INSERT INTO nursing.nursing_service_periods " +
                        "(id, patient_id, service_type, encounter_id, start_date, status, created_at, updated_at) VALUES " +
                        "('$periodId', '${FIXTURE_PREFIX}pat', 'ELDERLY_CARE', '$encounterId', '$today', 'ACTIVE', now(), now())",
                )
            }

            fun insertTask(taskId: String, periodId: String, encounterId: String) {
                stmt.execute(
                    "INSERT INTO nursing.nursing_tasks " +
                        "(id, period_id, encounter_id, task_type, description, status, created_at, updated_at) VALUES " +
                        "('$taskId', '$periodId', '$encounterId', 'NURSING', '收口集成测试任务', 'ACTIVE', now(), now())",
                )
            }

            fun insertExecution(executionId: String, taskId: String, plannedTime: OffsetDateTime, status: String, metadata: String?) {
                val metadataSql = metadata?.let { "'$it'::jsonb" } ?: "NULL"
                stmt.execute(
                    "INSERT INTO nursing.nursing_task_executions " +
                        "(id, task_id, planned_time, status, metadata, created_at) VALUES " +
                        "('$executionId', '$taskId', '${plannedTime}', '$status', $metadataSql, now())",
                )
            }

            insertPeriod("${FIXTURE_PREFIX}per-s1", ENC_S1)
            insertTask(TASK_S1, "${FIXTURE_PREFIX}per-s1", ENC_S1)
            insertExecution(EXE_S1_A, TASK_S1, now.minusMinutes(120), "PENDING", null)
            insertExecution(EXE_S1_B, TASK_S1, now.minusHours(30), "IN_PROGRESS", """{"keep":"v"}""")
            insertExecution(EXE_S1_DONE, TASK_S1, now.minusHours(5), "COMPLETED", null)
            insertExecution(EXE_S1_FUTURE, TASK_S1, now.plusMinutes(60), "PENDING", null)

            insertPeriod("${FIXTURE_PREFIX}per-s2", ENC_S2)
            insertTask(TASK_S2, "${FIXTURE_PREFIX}per-s2", ENC_S2)
            insertExecution(EXE_S2_A, TASK_S2, now.minusMinutes(120), "PENDING", null)
            insertExecution(EXE_S2_B, TASK_S2, now.minusHours(3), "PENDING", null)
            insertExecution(EXE_S2_C, TASK_S2, now.minusHours(5), "IN_PROGRESS", null)

            insertPeriod("${FIXTURE_PREFIX}per-s3", ENC_S3)
            insertTask(TASK_S3, "${FIXTURE_PREFIX}per-s3", ENC_S3)
            insertExecution(EXE_S3_NEAR, TASK_S3, now.minusMinutes(59), "PENDING", null)
            insertExecution(EXE_S3_BEYOND, TASK_S3, now.minusMinutes(61), "PENDING", null)
        }
    }
}