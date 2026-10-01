package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * 体检批次创建 / 名单补录的 PostgreSQL 集成测试（仅授权 aceso_test 运行）。
 *
 * 由来（2026-10-01 缺陷）：活动锚点优先级写成
 * `case encounter_type when :5 then :6 else :7 end`，其中 `then/else` 的 0/1
 * 走了绑定参数。PostgreSQL 对无法从上下文推断类型的参数按 SQL 规范回落为 `text`，
 * Vert.x PG 客户端据此要求 String，绑定 Integer 时在**编码期**抛
 * `can not be coerced to the expected class = [java.lang.String]`，
 * 于是 `POST /healthcare/v1/health-checkups` 一律 500（默认 snapshot=true 必走该查询）。
 *
 * 该类缺陷只在「真库 + 真 Vert.x 客户端」出现：`CheckupServiceTest` 用 mock Pool
 * 直接返回成功，跳过了参数编码，无法发现。本文件把口径钉在真库上。
 *
 * fixture 统一 `hci-` 前缀 + 专属业务年，按依赖逆序清理，前后残差为零；
 * 只操作既有 aceso_test，绝不 DROP/CREATE 数据库、绝不触碰非 `hci-` 前缀数据。
 * 通过 `-Dintegration.db.*` 启用；默认运行被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckupCreateIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "hci-"

        /** 专属业务年：`uq_health_checkups_year` 全局唯一，固定年份避免与任何数据抢号 */
        private const val FIXTURE_YEAR = 2097

        /** 在册（ACTIVE）：同时有 ACTIVE 的养老入住与门诊周期，用于验证锚点优先级 */
        private const val PATIENT_ANCHORED = "${FIXTURE_PREFIX}patient-1"

        /** 在册（ACTIVE）但无任何活动周期：应入册、锚点为空、不得报错 */
        private const val PATIENT_BARE = "${FIXTURE_PREFIX}patient-2"

        /** 非在册（DISCHARGED）：即使有 ACTIVE 周期也不得入册 */
        private const val PATIENT_INACTIVE = "${FIXTURE_PREFIX}patient-3"

        private const val ANCHORED_ELDERLY_ENCOUNTER = "${FIXTURE_PREFIX}enc-1-elderly"
        private const val ANCHORED_OUTPATIENT_ENCOUNTER = "${FIXTURE_PREFIX}enc-1-outpatient"
        private const val INACTIVE_ENCOUNTER = "${FIXTURE_PREFIX}enc-3"
        private const val OPERATOR = "${FIXTURE_PREFIX}user"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool

    private fun jdbcUrl(): String = "jdbc:postgresql://$host:$port/$TEST_DB"

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""
        // 兜底门控：只给 -Dintegration.db.host 而缺密码时按假设失败 skip 整类，不让模块变红。
        Assumptions.assumeTrue(password.isNotBlank(), "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行")

        try {
            check(port == "55432" || port == "5432") { "integration test must target the authorized aceso_test port" }
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            ctx.completeNow()
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @BeforeEach
    fun setupFixtures() {
        cleanupFixtures()
        assertResidualZero()
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO healthcare.patients (id, name, gender, birth_date, status) " +
                        "VALUES ('$PATIENT_ANCHORED', '集成体检长者一', '女', '1940-01-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
                )
                stmt.execute(
                    "INSERT INTO healthcare.patients (id, name, gender, birth_date, status) " +
                        "VALUES ('$PATIENT_BARE', '集成体检长者二', '男', '1942-02-02', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
                )
                stmt.execute(
                    "INSERT INTO healthcare.patients (id, name, gender, birth_date, status) " +
                        "VALUES ('$PATIENT_INACTIVE', '集成体检长者三', '女', '1943-03-03', 'DISCHARGED') ON CONFLICT (id) DO NOTHING",
                )
                stmt.execute(
                    "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) " +
                        "VALUES ('$ANCHORED_ELDERLY_ENCOUNTER', '$PATIENT_ANCHORED', 'ELDERLY_CARE', 'HCI-ENC-1-E', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
                )
                stmt.execute(
                    "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) " +
                        "VALUES ('$ANCHORED_OUTPATIENT_ENCOUNTER', '$PATIENT_ANCHORED', 'OUTPATIENT', 'HCI-ENC-1-O', '2026-08-02T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
                )
                stmt.execute(
                    "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) " +
                        "VALUES ('$INACTIVE_ENCOUNTER', '$PATIENT_INACTIVE', 'OUTPATIENT', 'HCI-ENC-3', '2026-08-03T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING",
                )
            }
        }
    }

    @AfterEach
    fun cleanupTestFixtures() {
        cleanupFixtures()
        assertResidualZero()
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        // @BeforeAll 被 Assumptions 跳过时 JUnit 仍会调用 @AfterAll：此时无连接池，直接结束。
        if (!::pool.isInitialized) {
            ctx.completeNow()
            return
        }
        cleanupFixtures()
        assertResidualZero()
        pool.close().onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `创建批次默认快照在册人员并优先锚定养老入住周期`() {
        val service = CheckupService(pool)
        val created = await(
            service.createCheckup(
                JsonObject()
                    .put("checkup_year", FIXTURE_YEAR)
                    .put("name", "集成体检批次")
                    .put("start_date", "2026-10-01")
                    .put("end_date", "2026-10-31"),
                OPERATOR,
            ),
        )

        assertEquals("草稿", created.getString("status"))
        val checkupId = created.getString("id")
        assertTrue(checkupId.matches(Regex("^[0-9A-HJKMNP-TV-Z]{26}$")), "id 必须是 26 位 ULID：$checkupId")

        // 在册 ACTIVE 入册、非在册不入册（快照口径）
        assertTrue(memberSnapshot(checkupId, PATIENT_ANCHORED).exists, "在册长者一必须入册")
        assertTrue(memberSnapshot(checkupId, PATIENT_BARE).exists, "在册长者二必须入册（可无锚点）")
        assertFalse(memberSnapshot(checkupId, PATIENT_INACTIVE).exists, "非在册长者不得入册")

        // 修复点：同一患者有多条活动周期时优先锚定 ELDERLY_CARE；
        // 修复前该排序键把 0/1 当绑定参数 → 编码期抛错 → 请求 500。
        assertEquals(
            ANCHORED_ELDERLY_ENCOUNTER,
            memberSnapshot(checkupId, PATIENT_ANCHORED).encounterId,
            "锚点必须取养老入住周期",
        )
        assertNull(memberSnapshot(checkupId, PATIENT_BARE).encounterId, "无活动周期时锚点为空")
    }

    @Test
    fun `关闭快照后补录名单同样解析养老锚点`() {
        val service = CheckupService(pool)
        val created = await(
            service.createCheckup(
                JsonObject()
                    .put("checkup_year", FIXTURE_YEAR)
                    .put("name", "集成体检批次（关闭快照）")
                    .put("snapshot", false),
                OPERATOR,
            ),
        )
        assertEquals(0, created.getInteger("member_total"), "snapshot=false 不得生成名单")

        // 补录走 resolveMemberAnchor（与快照同一处 CASE 排序键）
        val added = await(
            service.addMembers(
                created.getString("id"),
                JsonObject().put("patient_ids", JsonArray().add(PATIENT_ANCHORED)),
                OPERATOR,
            ),
        )
        val record = added.getJsonArray("records").getJsonObject(0)
        assertEquals(PATIENT_ANCHORED, record.getString("patient_id"))
        assertEquals(ANCHORED_ELDERLY_ENCOUNTER, record.getString("encounter_id"), "补录锚点必须取养老入住周期")
    }

    // ========================================================================
    //  fixture 辅助
    // ========================================================================

    private fun <T> await(future: Future<T>): T =
        future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(jdbcUrl(), user, password).use(block)

    /** 名单快照：区分「未入册」与「已入册但锚点为空」 */
    private data class MemberSnapshot(val exists: Boolean, val encounterId: String?)

    private fun memberSnapshot(checkupId: String, patientId: String): MemberSnapshot =
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT encounter_id FROM healthcare.health_checkup_members WHERE checkup_id = ? AND patient_id = ?",
            ).use { ps ->
                ps.setString(1, checkupId)
                ps.setString(2, patientId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) MemberSnapshot(false, null) else MemberSnapshot(true, rs.getString(1))
                }
            }
        }

    private fun cleanupFixtures() {
        withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM healthcare.health_checkup_results WHERE checkup_id IN " +
                        "(SELECT id FROM healthcare.health_checkups WHERE checkup_year = $FIXTURE_YEAR)",
                )
                stmt.execute(
                    "DELETE FROM healthcare.health_checkup_members WHERE patient_id LIKE '$FIXTURE_PREFIX%' OR checkup_id IN " +
                        "(SELECT id FROM healthcare.health_checkups WHERE checkup_year = $FIXTURE_YEAR)",
                )
                stmt.execute("DELETE FROM healthcare.health_checkups WHERE checkup_year = $FIXTURE_YEAR")
                stmt.execute(
                    "DELETE FROM healthcare.encounters WHERE id LIKE '$FIXTURE_PREFIX%' OR patient_id LIKE '$FIXTURE_PREFIX%'",
                )
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '$FIXTURE_PREFIX%'")
            }
        }
    }

    private fun assertResidualZero() {
        val residual = withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT (
                        (SELECT count(*) FROM healthcare.patients WHERE id LIKE '$FIXTURE_PREFIX%') +
                        (SELECT count(*) FROM healthcare.encounters WHERE id LIKE '$FIXTURE_PREFIX%' OR patient_id LIKE '$FIXTURE_PREFIX%') +
                        (SELECT count(*) FROM healthcare.health_checkups WHERE checkup_year = $FIXTURE_YEAR) +
                        (SELECT count(*) FROM healthcare.health_checkup_members WHERE patient_id LIKE '$FIXTURE_PREFIX%') +
                        (SELECT count(*) FROM healthcare.health_checkup_results WHERE patient_id LIKE '$FIXTURE_PREFIX%')
                    ) AS residual
                    """.trimIndent(),
                ).use { rs ->
                    rs.next()
                    rs.getLong("residual")
                }
            }
        }
        assertEquals(0L, residual, "$FIXTURE_PREFIX fixture 残差必须为零")
    }
}
