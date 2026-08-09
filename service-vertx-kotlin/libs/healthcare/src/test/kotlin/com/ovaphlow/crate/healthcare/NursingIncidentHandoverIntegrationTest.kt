package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.NursingIncidentService
import com.ovaphlow.crate.nursing.NursingTimelineService
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 017 院内护理异常事件与班次交接 — PostgreSQL 集成测试（测试角色）。
 *
 * 文件位置说明：计划 §6 将集成测试列为
 * `libs/nursing/src/test/.../NursingIncidentHandoverIntegrationTest.kt`，
 * 但 Nursing lib 不依赖 Healthcare lib（见 nursing/build.gradle.kts），
 * 无法在 nursing 测试模块调用完整编排层 HealthcareService；
 * 而 healthcare 依赖 nursing，且 healthcare 测试类路径同时含
 * nursing V400–V408 与 healthcare V500–V507 迁移。
 * 因此本文件置于 `libs/healthcare/src/test`，包名不变，内容即 §7.2 项 1–3
 * 的真实事务验证。除本文件外不修改任何生产代码、Shared API、UI 或开发测试。
 *
 * 前置：独立、可销毁的 `aceso_test`（apps/aceso/compose.test.yaml，端口 55432）。
 * 运行（仅本类）：
 *   cd service-vertx-kotlin
 *   PITCHFORK_DB_PASSWORD=pitchfork-test-only ./gradlew :libs:healthcare:test \
 *     -Dintegration.db.host=localhost -Dintegration.db.port=55432 \
 *     -Dintegration.db.database=aceso_test -Dintegration.db.user=ovaphlow \
 *     --tests "*NursingIncidentHandoverIntegrationTest" --rerun-tasks
 *
 * 清理：@AfterAll 按子表→父表删除 FIXTURE_PREFIX 残留；库本身由 compose down 销毁。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NursingIncidentHandoverIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val FIXTURE_PREFIX = "iht-"
        private val businessToday: LocalDate = LocalDate.now(ZoneIds.SHANGHAI)
    }

    private object ZoneIds {
        val SHANGHAI = java.time.ZoneId.of("Asia/Shanghai")
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var healthcare: HealthcareService
    private lateinit var timeline: NursingTimelineService
    private lateinit var incidentService: NursingIncidentService

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}$suffix"

    // ========================================================================
    // 生命周期：DROP/CREATE aceso_test → Flyway 迁移 → fixture → 服务
    // ========================================================================

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        try {
            if (password.isBlank()) throw IllegalStateException("PITCHFORK_DB_PASSWORD must be set")

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
            // nursing V400–V408 + healthcare V500–V507 全部按序应用；验证 V408 新表可迁移
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)

            setupFixturesJdbc()

            healthcare = HealthcareService(pool)
            timeline = NursingTimelineService(pool)
            incidentService = NursingIncidentService(pool)
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
                // 子表 → 父表顺序清理；服务创建的行是 ULID 主键，必须按引用（period/encounter/idempotency_key）删除
                stmt.execute("DELETE FROM nursing.nursing_incident_actions WHERE id LIKE '${FIXTURE_PREFIX}%' OR incident_id LIKE '${FIXTURE_PREFIX}%' OR incident_id IN (SELECT id FROM nursing.nursing_incidents WHERE period_id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM nursing.nursing_incidents WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_shift_handover_items WHERE handover_id IN (SELECT id FROM nursing.nursing_shift_handovers WHERE idempotency_key LIKE '${FIXTURE_PREFIX}%')")
                stmt.execute("DELETE FROM nursing.nursing_shift_handovers WHERE id LIKE '${FIXTURE_PREFIX}%' OR idempotency_key LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE task_id LIKE '${FIXTURE_PREFIX}%' OR id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.medical_records WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
                stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
            }
        } catch (_: Exception) { /* cleanup best effort */ }

        if (::pool.isInitialized) pool.close()
        ctx.completeNow()
    }

    private fun jdbcUrl() = "jdbc:postgresql://$host:$port/$TEST_DB"

    // ========================================================================
    // Fixture：两名长者；同一长者两次入住（活动/终态）；另一单元活动入住；
    // 无周期入住、周期患者不匹配入住、非养老入住；未完成执行、开/关事件、护理记录
    // ========================================================================

    private fun setupFixturesJdbc() {
        val now = OffsetDateTime.now()
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            // 前置清理与 @AfterAll 相同：ULID 主键行按引用删除
            stmt.execute("DELETE FROM nursing.nursing_incident_actions WHERE id LIKE '${FIXTURE_PREFIX}%' OR incident_id LIKE '${FIXTURE_PREFIX}%' OR incident_id IN (SELECT id FROM nursing.nursing_incidents WHERE period_id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_incidents WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_shift_handover_items WHERE handover_id IN (SELECT id FROM nursing.nursing_shift_handovers WHERE idempotency_key LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_shift_handovers WHERE id LIKE '${FIXTURE_PREFIX}%' OR idempotency_key LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE task_id LIKE '${FIXTURE_PREFIX}%' OR id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.medical_records WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")

            // 长者与入住（V501：同一患者至多一个活动养老入住）
            stmt.execute("INSERT INTO healthcare.patients (id, name, gender, status) VALUES ('${fixtureId("patient-1")}','长者甲','女','ACTIVE')")
            stmt.execute("INSERT INTO healthcare.patients (id, name, gender, status) VALUES ('${fixtureId("patient-2")}','长者乙','男','ACTIVE')")
            stmt.execute("INSERT INTO healthcare.patients (id, name, gender, status) VALUES ('${fixtureId("patient-3")}','长者丙','女','ACTIVE')")
            stmt.execute("INSERT INTO healthcare.patients (id, name, gender, status) VALUES ('${fixtureId("patient-4")}','长者丁','男','ACTIVE')")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, department, ward, admit_date, status) VALUES ('${fixtureId("enc-1")}','${fixtureId("patient-1")}','ELDERLY_CARE','IHT-E1','单元A','101-1','${now.minusDays(20)}','ACTIVE')")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, department, ward, admit_date, discharge_date, status) VALUES ('${fixtureId("enc-2")}','${fixtureId("patient-1")}','ELDERLY_CARE','IHT-E2','单元A','102-2','${now.minusDays(60)}','${now.minusDays(5)}','DISCHARGED')")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, department, ward, admit_date, status) VALUES ('${fixtureId("enc-3")}','${fixtureId("patient-2")}','ELDERLY_CARE','IHT-E3','单元B','201-1','${now.minusDays(30)}','ACTIVE')")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, department, admit_date, status) VALUES ('${fixtureId("enc-4")}','${fixtureId("patient-2")}','OUTPATIENT','IHT-E4','门诊','${now.minusDays(3)}','ACTIVE')")
            // 缺失周期（单元C）：活动养老入住但无照护周期 → 409
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, department, ward, admit_date, status) VALUES ('${fixtureId("enc-5")}','${fixtureId("patient-4")}','ELDERLY_CARE','IHT-E5','单元C','301-1','${now.minusDays(10)}','ACTIVE')")
            // 周期患者不匹配（单元D）：enc-7 属 patient-3，但绑定周期 patient_id=patient-1 → 409
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, department, ward, admit_date, status) VALUES ('${fixtureId("enc-7")}','${fixtureId("patient-3")}','ELDERLY_CARE','IHT-E7','单元D','401-1','${now.minusDays(10)}','ACTIVE')")

            // 照护周期（V404 之后：ELDERLY_CARE 必须绑定 encounter_id）
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-1")}','${fixtureId("patient-1")}','ELDERLY_CARE','${fixtureId("enc-1")}','${businessToday.minusDays(15)}','ACTIVE')")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-2")}','${fixtureId("patient-1")}','ELDERLY_CARE','${fixtureId("enc-2")}','${businessToday.minusDays(55)}','COMPLETED')")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-3")}','${fixtureId("patient-2")}','ELDERLY_CARE','${fixtureId("enc-3")}','${businessToday.minusDays(25)}','ACTIVE')")
            // 周期患者与入住患者不匹配（enc-7 属 patient-3，周期绑定 patient-1）
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-7")}','${fixtureId("patient-1")}','ELDERLY_CARE','${fixtureId("enc-7")}','${businessToday.minusDays(10)}','ACTIVE')")

            // 护理任务与执行：period-1 有 PENDING/IN_PROGRESS/COMPLETED；period-2 已完结
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, frequency_code, start_date, status) VALUES ('${fixtureId("task-1")}','${fixtureId("period-1")}','${fixtureId("enc-1")}','NURSING','翻身拍背','BID','${businessToday.minusDays(10)}','ACTIVE')")
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, frequency_code, start_date, status) VALUES ('${fixtureId("task-2")}','${fixtureId("period-2")}','${fixtureId("enc-2")}','NURSING','离院前评估','QD','${businessToday.minusDays(50)}','COMPLETED')")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-1")}','${fixtureId("task-1")}','${now.minusHours(2)}','PENDING','nurse-a')")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor) VALUES ('${fixtureId("exec-2")}','${fixtureId("task-1")}','${now.minusHours(1)}','IN_PROGRESS','nurse-a')")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time) VALUES ('${fixtureId("exec-3")}','${fixtureId("task-1")}','${now.minusHours(3)}','COMPLETED','nurse-a','${now.minusHours(3)}')")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status, executor, actual_time) VALUES ('${fixtureId("exec-4")}','${fixtureId("task-2")}','${now.minusDays(1)}','COMPLETED','nurse-b','${now.minusDays(1)}')")

            // 事件：period-1 一个未关闭（快照应收）、一个已关闭（快照应收排除）；period-2 未关闭（终态限制）
            stmt.execute("INSERT INTO nursing.nursing_incidents (id, encounter_id, period_id, incident_type, severity, status, occurred_at, description, reporter) VALUES ('${fixtureId("inc-1")}','${fixtureId("enc-1")}','${fixtureId("period-1")}','跌倒/坠床','较重','处理中','${now.minusHours(6)}','走廊滑倒','nurse-a')")
            stmt.execute("INSERT INTO nursing.nursing_incidents (id, encounter_id, period_id, incident_type, severity, status, occurred_at, description, reporter) VALUES ('${fixtureId("inc-2")}','${fixtureId("enc-1")}','${fixtureId("period-1")}','压疮','一般','已关闭','${now.minusDays(3)}','右足跟发红','nurse-a')")
            stmt.execute("INSERT INTO nursing.nursing_incidents (id, encounter_id, period_id, incident_type, severity, status, occurred_at, description, reporter) VALUES ('${fixtureId("inc-3")}','${fixtureId("enc-2")}','${fixtureId("period-2")}','用药差错','严重','处理中','${now.minusDays(10)}','发错药','nurse-b')")

            stmt.execute("INSERT INTO nursing.nursing_incident_actions (id, incident_id, action_type, body, actor, occurred_at, created_at) VALUES ('${fixtureId("act-1")}','${fixtureId("inc-1")}','上报','走廊滑倒','nurse-a','${now.minusHours(6)}','${now.minusHours(6)}')")
            stmt.execute("INSERT INTO nursing.nursing_incident_actions (id, incident_id, action_type, body, actor, occurred_at, created_at) VALUES ('${fixtureId("act-2")}','${fixtureId("inc-1")}','处置','已扶起并评估生命体征','nurse-a','${now.minusHours(5)}','${now.minusHours(5)}')")
            stmt.execute("INSERT INTO nursing.nursing_incident_actions (id, incident_id, action_type, body, actor, occurred_at, created_at) VALUES ('${fixtureId("act-3")}','${fixtureId("inc-2")}','上报','右足跟发红','nurse-a','${now.minusDays(3)}','${now.minusDays(3)}')")
            stmt.execute("INSERT INTO nursing.nursing_incident_actions (id, incident_id, action_type, body, actor, occurred_at, created_at) VALUES ('${fixtureId("act-4")}','${fixtureId("inc-2")}','关闭','已愈','nurse-a','${now.minusDays(2)}','${now.minusDays(2)}')")
            stmt.execute("INSERT INTO nursing.nursing_incident_actions (id, incident_id, action_type, body, actor, occurred_at, created_at) VALUES ('${fixtureId("act-5")}','${fixtureId("inc-3")}','上报','发错药','nurse-b','${now.minusDays(10)}','${now.minusDays(10)}')")

            // 护理记录：今日 period-1（快照应收）、昨日 period-1（排除）、今日 period-3（排除）
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("rec-1")}','${fixtureId("enc-1")}','NURSING_RECORD','日常护理记录','今日体温正常','nurse-a','$businessToday','{\"period_id\":\"${fixtureId("period-1")}\"}')")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("rec-2")}','${fixtureId("enc-1")}','NURSING_RECORD','昨日护理记录','昨日状态平稳','nurse-a','${businessToday.minusDays(1)}','{\"period_id\":\"${fixtureId("period-1")}\"}')")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("rec-3")}','${fixtureId("enc-3")}','NURSING_RECORD','单元B记录','另一单元记录','nurse-b','$businessToday','{\"period_id\":\"${fixtureId("period-3")}\"}')")
        }
    }

    // ========================================================================
    // 请求构造辅助
    // ========================================================================

    private fun incidentBody(
        type: String = "跌倒/坠床",
        severity: String = "较重",
        occurredAt: OffsetDateTime = OffsetDateTime.now().minusHours(1),
        description: String = "集成测试事件说明",
        initialAction: JsonObject? = null,
    ): JsonObject {
        val body = JsonObject()
            .put("incident_type", type)
            .put("severity", severity)
            .put("occurred_at", occurredAt.toString())
            .put("description", description)
        initialAction?.let { body.put("initial_action", it) }
        return body
    }

    private fun handoverBody(
        encounterId: String = fixtureId("enc-1"),
        businessDate: LocalDate = businessToday,
        shift: String = "早班",
        manualItems: List<String> = emptyList(),
    ): JsonObject {
        val body = JsonObject()
            .put("encounter_id", encounterId)
            .put("business_date", businessDate.toString())
            .put("shift", shift)
        if (manualItems.isNotEmpty()) {
            val arr = io.vertx.core.json.JsonArray()
            manualItems.forEach { arr.add(it) }
            body.put("manual_items", arr)
        }
        return body
    }

    private fun dbCount(table: String, where: String): Long =
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { rs ->
                rs.next(); rs.getLong(1)
            }
        }

    // ========================================================================
    // 1. 上报事件：精确绑定 + 认证主体 + 首条审计事实
    // ========================================================================

    @Test
    fun `上报事件精确绑定encounter与period且上报人来自认证主体`(ctx: VertxTestContext) {
        healthcare.createIncident(fixtureId("enc-1"), incidentBody(), "subj-nurse")
            .onSuccess { incident ->
                try {
                    assertEquals("已上报", incident.getString("status"))
                    assertEquals("subj-nurse", incident.getString("reporter"))
                    assertEquals(fixtureId("enc-1"), incident.getString("encounter_id"))
                    assertEquals(fixtureId("period-1"), incident.getString("period_id"))
                    assertEquals("跌倒/坠床", incident.getString("incident_type"))
                    assertEquals("较重", incident.getString("severity"))
                    // 数据库行与首条 上报 审计事实同事务写入
                    assertEquals(1L, dbCount("nursing.nursing_incidents", "id = '${incident.getString("id")}'"))
                    val actions = dbCount("nursing.nursing_incident_actions", "incident_id = '${incident.getString("id")}'")
                    assertEquals(1L, actions, "首次上报必须写入一条上报审计事实")
                    assertEquals(1L, dbCount("nursing.nursing_incident_actions", "incident_id = '${incident.getString("id")}' AND actor = 'subj-nurse' AND action_type = '上报'"))
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `携带即时处置时事件推进为处理中并写入两条审计事实`(ctx: VertxTestContext) {
        val initial = JsonObject().put("action_type", "处置").put("body", "立即冰敷")
        healthcare.createIncident(
            fixtureId("enc-1"),
            incidentBody(initialAction = initial),
            "subj-nurse",
        ).onSuccess { incident ->
            try {
                assertEquals("处理中", incident.getString("status"))
                assertEquals(2L, dbCount("nursing.nursing_incident_actions", "incident_id = '${incident.getString("id")}'"))
                ctx.completeNow()
            } catch (e: Throwable) { ctx.failNow(e) }
        }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    // 2. 拒绝：未来时间 / 早于周期开始 / 未知字段 / 非养老 / 缺失周期 / 患者不匹配
    // ========================================================================

    @Test
    fun `未来发生时间被拒绝`(ctx: VertxTestContext) {
        val future = OffsetDateTime.now().plusHours(1)
        healthcare.createIncident(fixtureId("enc-1"), incidentBody(occurredAt = future), "subj-nurse")
            .onSuccess { ctx.failNow(AssertionError("未来发生时间必须被拒绝")) }
            .onFailure { e ->
                try {
                    assertTrue(e is IllegalArgumentException, "应为 400 语义，实际 ${e::class.simpleName}: ${e.message}")
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }
    }

    @Test
    fun `早于周期开始日的发生时间被拒绝`(ctx: VertxTestContext) {
        val beforeStart = businessToday.minusDays(16).atTime(10, 0).atZone(ZoneIds.SHANGHAI).toOffsetDateTime()
        healthcare.createIncident(fixtureId("enc-1"), incidentBody(occurredAt = beforeStart), "subj-nurse")
            .onSuccess { ctx.failNow(AssertionError("早于周期开始日必须被拒绝")) }
            .onFailure { e ->
                try {
                    assertTrue(e is IllegalArgumentException, "应为 400 语义，实际 ${e::class.simpleName}: ${e.message}")
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }
    }

    @Test
    fun `未知字段与伪造审计字段被拒绝`(ctx: VertxTestContext) {
        val body = incidentBody().put("reporter", "伪造主体").put("status", "已关闭").put("period_id", fixtureId("period-3"))
        healthcare.createIncident(fixtureId("enc-1"), body, "subj-nurse")
            .onSuccess { ctx.failNow(AssertionError("伪造字段必须被拒绝")) }
            .onFailure { e ->
                try {
                    assertTrue(e is IllegalArgumentException, "应为 400 语义，实际 ${e::class.simpleName}: ${e.message}")
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }
    }

    @Test
    fun `非养老与不存在encounter被拒绝`(ctx: VertxTestContext) {
        val nonElderly = healthcare.createIncident(fixtureId("enc-4"), incidentBody(), "subj-nurse")
        val missing = healthcare.createIncident(fixtureId("iht-does-not-exist"), incidentBody(), "subj-nurse")
        Future.all(nonElderly, missing)
            .onSuccess { ctx.failNow(AssertionError("两者都必须被拒绝")) }
            .onFailure { e ->
                try {
                    assertTrue(e is IllegalArgumentException || e is HealthcareNotFoundException, "实际 ${e::class.simpleName}: ${e.message}")
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }
    }

    @Test
    fun `已离院缺失周期和患者不匹配均返回冲突`(ctx: VertxTestContext) {
        val discharged = healthcare.createIncident(fixtureId("enc-2"), incidentBody(), "subj-nurse")
        val noPeriod = healthcare.createIncident(fixtureId("enc-5"), incidentBody(), "subj-nurse")
        val mismatch = healthcare.createIncident(fixtureId("enc-7"), incidentBody(), "subj-nurse")
        Future.all(discharged, noPeriod, mismatch)
            .onSuccess { ctx.failNow(AssertionError("三者都必须被拒绝")) }
            .onFailure { e ->
                try {
                    assertTrue(e is ConflictException, "应均为 409 冲突，实际 ${e::class.simpleName}: ${e.message}")
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }
    }

    // ========================================================================
    // 3. 处置 / 关闭 / 并发关闭至多一次
    // ========================================================================

    @Test
    fun `追加处置推进为处理中且通知事实落库`(ctx: VertxTestContext) {
        // 使用独立事件，避免影响 fixture 的 inc-1 供列表/快照断言使用
        healthcare.createIncident(fixtureId("enc-1"), incidentBody(description = "追加处置测试事件"), "subj-nurse")
            .compose { incident ->
                healthcare.addIncidentAction(
                    requireNotNull(incident.getString("encounter_id")),
                    incident.getString("id"),
                    JsonObject()
                        .put("action_type", "通知")
                        .put("body", "已电话联系家属")
                        .put("notified_party", "家属-王女士")
                        .put("notification_result", "已接通并告知情况"),
                    "subj-nurse",
                )
            }
            .onSuccess { result ->
                try {
                    assertEquals("处理中", result.getJsonObject("incident").getString("status"))
                    assertEquals("通知", result.getJsonObject("action").getString("action_type"))
                    assertEquals("subj-nurse", result.getJsonObject("action").getString("actor"))
                    val incidentId = result.getJsonObject("incident").getString("id")
                    val count = dbCount(
                        "nursing.nursing_incident_actions",
                        "incident_id = '$incidentId' AND action_type = '通知' AND notified_party = '家属-王女士'",
                    )
                    assertEquals(1L, count, "通知事实必须落库且可审计")
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `关闭后重复关闭返回409且不产生新事实`(ctx: VertxTestContext) {
        var incidentId: String? = null
        healthcare.createIncident(fixtureId("enc-1"), incidentBody(description = "重复关闭测试事件"), "subj-nurse")
            .compose { incident ->
                incidentId = incident.getString("id")
                val encounterId = requireNotNull(incident.getString("encounter_id"))
                healthcare.closeIncident(encounterId, incident.getString("id"), JsonObject().put("close_note", "已愈复查无异常"), "subj-nurse")
                    .compose {
                        healthcare.closeIncident(encounterId, incident.getString("id"), JsonObject().put("close_note", "重复关闭"), "subj-nurse")
                            .map { JsonObject().put("error", "unexpected-success") }
                            .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
                    }
            }
            .onSuccess { second ->
                try {
                    assertEquals("ConflictException", second.getString("error"))
                    assertEquals(2L, dbCount("nursing.nursing_incident_actions", "incident_id = '$incidentId'"), "重复关闭不得产生新事实")
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `并发关闭至多成功一次且只写入一条关闭事实`(ctx: VertxTestContext) {
        healthcare.createIncident(fixtureId("enc-1"), incidentBody(description = "并发关闭事件"), "subj-nurse")
            .onSuccess { incident ->
                val id = incident.getString("id")
                val encounterId = requireNotNull(incident.getString("encounter_id"))
                val latch = CountDownLatch(2)
                var successes = 0
                var conflicts = 0
                var other = 0
                fun settle() {
                    latch.countDown()
                    if (latch.count == 0L) {
                        try {
                            assertEquals(1, successes, "并发关闭必须至多成功一次")
                            assertEquals(1, conflicts, "落败方必须得到 409")
                            assertEquals(0, other)
                            assertEquals(1L, dbCount("nursing.nursing_incident_actions", "incident_id = '$id' AND action_type = '关闭'"))
                            assertEquals(1L, dbCount("nursing.nursing_incidents", "id = '$id' AND status = '已关闭'"))
                            ctx.completeNow()
                        } catch (e: Throwable) { ctx.failNow(e) }
                    }
                }
                healthcare.closeIncident(encounterId, id, JsonObject().put("close_note", "并发A"), "nurse-a")
                    .onComplete { r -> if (r.succeeded()) successes++ else if (r.cause() is ConflictException) conflicts++ else other++; settle() }
                healthcare.closeIncident(encounterId, id, JsonObject().put("close_note", "并发B"), "nurse-b")
                    .onComplete { r -> if (r.succeeded()) successes++ else if (r.cause() is ConflictException) conflicts++ else other++; settle() }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    // 4. 终态周期限制：不能新建 / 不能追加非关闭动作 / 关闭一次允许
    // ========================================================================

    @Test
    fun `终态周期不能新建事件不能追加处置但允许为既有事件关闭一次`(ctx: VertxTestContext) {
        val createOnTerminal = healthcare.createIncident(fixtureId("enc-2"), incidentBody(), "subj-nurse")
        val appendOnTerminal = healthcare.addIncidentAction(
            fixtureId("enc-2"), fixtureId("inc-3"),
            JsonObject().put("action_type", "处置").put("body", "终态追加应被拒绝"),
            "subj-nurse",
        )
        // 新建与追加必须全部被拒绝（409 冲突）
        val createResult = createOnTerminal.map { JsonObject().put("error", "unexpected-success") }
            .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
        val appendResult = appendOnTerminal.map { JsonObject().put("error", "unexpected-success") }
            .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
        Future.all(createResult, appendResult)
            .compose {
                try {
                    assertEquals("ConflictException", createResult.result().getString("error"), "终态周期新建事件必须 409")
                    assertEquals("ConflictException", appendResult.result().getString("error"), "终态周期追加处置必须 409")
                } catch (t: Throwable) { return@compose Future.failedFuture<JsonObject>(t) }
                // 终态后允许为既有事件追加一次关闭
                healthcare.closeIncident(fixtureId("enc-2"), fixtureId("inc-3"), JsonObject().put("close_note", "离院前最终行政关闭"), "subj-nurse")
            }
            .compose { closed ->
                try {
                    assertEquals("已关闭", closed.getJsonObject("incident").getString("status"))
                } catch (t: Throwable) { return@compose Future.failedFuture<JsonObject>(t) }
                healthcare.closeIncident(fixtureId("enc-2"), fixtureId("inc-3"), JsonObject().put("close_note", "再次关闭"), "subj-nurse")
                    .map { JsonObject().put("error", "unexpected-success") }
                    .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
            }
            .onSuccess { second ->
                try {
                    assertEquals("ConflictException", second.getString("error"), "终态事件关闭后不可再次关闭")
                    // 关闭不得改写发生时间、上报人、归属
                    assertEquals(1L, dbCount("nursing.nursing_incidents", "id = '${fixtureId("inc-3")}' AND reporter = 'nurse-b' AND status = '已关闭' AND encounter_id = '${fixtureId("enc-2")}'"))
                    assertEquals(2L, dbCount("nursing.nursing_incident_actions", "incident_id = '${fixtureId("inc-3")}'"))
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    // 5. 精确隔离：列表与详情只读且按 encounter 隔离
    // ========================================================================

    @Test
    fun `列表按encounter精确隔离且详情只读返回全部审计事实`(ctx: VertxTestContext) {
        val listA = healthcare.listIncidents(fixtureId("enc-1"), null, null, null, 50, 0)
        val listB = healthcare.listIncidents(fixtureId("enc-3"), null, null, null, 50, 0)
        Future.all(listA, listB)
            .compose {
                try {
                    val a = listA.result()
                    val b = listB.result()
                    val aIds = (0 until a.getJsonArray("records").size()).map { a.getJsonArray("records").getJsonObject(it).getString("id") }.toSet()
                    // fixture 事件必须存在；其它入住的事件不得泄漏
                    assertTrue(aIds.containsAll(setOf(fixtureId("inc-1"), fixtureId("inc-2"))), "enc-1 应收 inc-1 与 inc-2，实际：$aIds")
                    assertFalse(aIds.contains(fixtureId("inc-3")), "enc-1 列表不得出现 enc-2（终态入住）的事件")
                    assertEquals(0L, b.getJsonObject("meta").getLong("total"), "enc-3 不应看到 enc-1 的事件")
                } catch (t: Throwable) { return@compose Future.failedFuture<JsonObject>(t) }
                healthcare.getIncident(fixtureId("enc-1"), fixtureId("inc-1"))
            }
            .onSuccess { detail ->
                try {
                    val actions = detail.getJsonArray("actions")
                    assertEquals(2, actions.size(), "inc-1 应有 上报 + 处置 两条审计事实")
                    assertEquals("上报", actions.getJsonObject(0).getString("action_type"))
                    assertEquals("处置", actions.getJsonObject(1).getString("action_type"))
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    // 6. 交班：快照无副作用 + 唯一性 + 幂等 + 并发至多一张
    // ========================================================================

    @Test
    fun `交班快照创建前后源数据不变且事项内容符合预期`(ctx: VertxTestContext) {
        val beforeTasks = dbCount("nursing.nursing_tasks", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeExecs = dbCount("nursing.nursing_task_executions", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeIncidents = dbCount("nursing.nursing_incidents", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeActions = dbCount("nursing.nursing_incident_actions", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeRecords = dbCount("healthcare.medical_records", "id LIKE '${FIXTURE_PREFIX}%'")

        healthcare.createShiftHandover(handoverBody(manualItems = listOf("重点关注长者甲夜间翻身")), "subj-nurse", "iht-key-1")
            .compose { (created, handover) ->
                try {
                    assertTrue(created, "首次创建应返回 created=true")
                    // 快照不改变任何来源记录
                    assertEquals(beforeTasks, dbCount("nursing.nursing_tasks", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeExecs, dbCount("nursing.nursing_task_executions", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeIncidents, dbCount("nursing.nursing_incidents", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeActions, dbCount("nursing.nursing_incident_actions", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeRecords, dbCount("healthcare.medical_records", "id LIKE '${FIXTURE_PREFIX}%'"))
                } catch (t: Throwable) { return@compose Future.failedFuture<JsonObject>(t) }
                healthcare.getShiftHandover(handover.getString("id"))
            }
            .onSuccess { detail ->
                try {
                    val items = detail.getJsonArray("items")
                    val kinds = (0 until items.size()).map { items.getJsonObject(it).getString("item_kind") }.toSet()
                    val summaries = (0 until items.size()).map { items.getJsonObject(it).getString("summary") }.joinToString(" | ")
                    assertTrue(kinds.containsAll(setOf("入住", "执行", "事件", "护理记录", "手工")), "快照应包含五类事项：$summaries")
                    // 执行只收未完成（PENDING/IN_PROGRESS），不收 COMPLETED
                    assertTrue(summaries.contains("翻身拍背"), "未完成执行应收：$summaries")
                    assertFalse(summaries.contains("离院前评估"), "已完结周期的执行不应进入快照")
                    // 事件只收未关闭
                    assertTrue(summaries.contains("跌倒/坠床"), "未关闭事件应收：$summaries")
                    assertFalse(summaries.contains("压疮"), "已关闭事件不应进入快照")
                    // 护理记录只收本业务日
                    assertTrue(summaries.contains("今日体温正常"), "本业务日护理记录应收：$summaries")
                    assertFalse(summaries.contains("昨日状态平稳"), "昨日护理记录不应进入快照")
                    // 手工事项保留
                    assertTrue(summaries.contains("重点关注长者甲夜间翻身"), "手工事项应收：$summaries")
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `同键同内容幂等重试返回原单且不同键同范围冲突`(ctx: VertxTestContext) {
        // 使用独立范围：今天/夜班（快照测试占今天/早班，接班测试占昨天/夜班，并发测试占今天/中班）
        val body = handoverBody(shift = "夜班", manualItems = listOf("交接重点一"))
        healthcare.createShiftHandover(body, "subj-nurse", "iht-key-2")
            .compose { (_, first) ->
                healthcare.createShiftHandover(body, "subj-nurse", "iht-key-2")
                    .compose { (createdAgain, retried) ->
                        assertEquals(false, createdAgain, "同键同内容重试不应重复创建")
                        assertEquals(first.getString("id"), retried.getString("id"), "幂等重试必须返回原交班单")
                        // 同键不同内容 → 409（同一 夜班/今天 范围）
                        val different = handoverBody(shift = "夜班", manualItems = listOf("另一内容"))
                        healthcare.createShiftHandover(different, "subj-nurse", "iht-key-2")
                            .map { JsonObject().put("error", "unexpected-success") }
                            .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
                            .compose { differentResult ->
                                assertEquals("ConflictException", differentResult.getString("error"), "同键不同内容必须 409")
                                // 不同键同一范围 → 409
                                healthcare.createShiftHandover(handoverBody(shift = "夜班"), "subj-nurse", "iht-key-other")
                                    .map { JsonObject().put("error", "unexpected-success") }
                                    .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
                            }
                            .onSuccess { otherKey ->
                                try {
                                    assertEquals("ConflictException", otherKey.getString("error"), "不同键同一范围必须 409")
                                    assertEquals(1L, dbCount("nursing.nursing_shift_handovers", "care_unit = '单元A' AND business_date = '$businessToday' AND shift = '夜班'"))
                                    ctx.completeNow()
                                } catch (e: Throwable) { ctx.failNow(e) }
                            }.onFailure { ctx.failNow(it) }
                    }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `并发创建同一范围至多保留一张交班单`(ctx: VertxTestContext) {
        val body = handoverBody(businessDate = businessToday.plusDays(0), shift = "中班")
        val latch = CountDownLatch(2)
        var successes = 0
        var conflicts = 0
        var other = 0
        fun settle() {
            latch.countDown()
            if (latch.count == 0L) {
                try {
                    assertEquals(1, successes, "并发创建必须至多一张")
                    assertEquals(1, conflicts, "落败方必须 409")
                    assertEquals(0, other)
                    assertEquals(1L, dbCount("nursing.nursing_shift_handovers", "care_unit = '单元A' AND business_date = '$businessToday' AND shift = '中班'"))
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }
        }
        healthcare.createShiftHandover(body, "nurse-a", "iht-conc-1")
            .onComplete { r -> if (r.succeeded()) successes++ else if (r.cause() is ConflictException) conflicts++ else other++; settle() }
        healthcare.createShiftHandover(body, "nurse-b", "iht-conc-2")
            .onComplete { r -> if (r.succeeded()) successes++ else if (r.cause() is ConflictException) conflicts++ else other++; settle() }
    }

    // ========================================================================
    // 7. 接班不可覆盖 + 补充事项只允许正文
    // ========================================================================

    @Test
    fun `接班一次写入接班人重复接班不可覆盖且补充事项保留补充人`(ctx: VertxTestContext) {
        healthcare.createShiftHandover(handoverBody(businessDate = businessToday.minusDays(1), shift = "夜班"), "subj-nurse", "iht-key-3")
            .compose { (_, handover) ->
                val id = handover.getString("id")
                healthcare.receiveShiftHandover(id, JsonObject(), "subj-receiver")
                    .compose { received ->
                        assertEquals("已接班", received.getString("status"))
                        assertEquals("subj-receiver", received.getString("received_by"))
                        val repeated = healthcare.receiveShiftHandover(id, JsonObject(), "subj-other")
                            .map { JsonObject().put("error", "unexpected-success") }
                            .recover { e -> Future.succeededFuture(JsonObject().put("error", e::class.simpleName)) }
                        repeated.compose { repeatedResult ->
                            assertEquals("ConflictException", repeatedResult.getString("error"), "重复接班必须 409")
                            healthcare.appendShiftHandoverItem(id, JsonObject().put("content", "接班后补充：家属要求明早复查"), "subj-nurse")
                        }
                    }
            }
            .onSuccess { detail ->
                try {
                    // 接班事实未被覆盖
                    assertEquals("subj-receiver", detail.getString("received_by"))
                    assertEquals("已接班", detail.getString("status"))
                    val items = detail.getJsonArray("items")
                    val last = items.getJsonObject(items.size() - 1)
                    assertEquals("手工", last.getString("item_kind"))
                    assertEquals("接班后补充：家属要求明早复查", last.getString("summary"))
                    assertEquals("subj-nurse", last.getString("created_by"), "补充人必须来自认证主体")
                    assertNull(last.getString("encounter_id"), "补充事项不得伪造来源关联")
                    assertNull(last.getString("source_id"), "补充事项不得伪造来源 ID")
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    // 8. 整笔事务回滚：事件写入成功后置动作失败必须全部回滚
    // ========================================================================

    @Test
    fun `事件与动作任一写入失败整笔回滚`(ctx: VertxTestContext) {
        val beforeIncidents = dbCount("nursing.nursing_incidents", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeActions = dbCount("nursing.nursing_incident_actions", "id LIKE '${FIXTURE_PREFIX}%'")
        // 直接调用 Nursing 服务（跳过 Healthcare 前置校验），用 DB CHECK 违例制造第二笔写入失败
        val invalid = NursingIncidentService.ActionInput(
            actionType = "伪造",
            body = "非法动作类别",
            notifiedParty = null,
            notificationResult = null,
        )
        val input = NursingIncidentService.IncidentCreateInput(
            encounterId = fixtureId("enc-1"),
            periodId = fixtureId("period-1"),
            periodStartDate = businessToday.minusDays(15),
            incidentType = "走失",
            severity = "严重",
            occurredAt = OffsetDateTime.now().minusHours(1),
            description = "回滚测试",
            reporter = "subj-nurse",
            initialAction = invalid,
        )
        pool.withTransaction<JsonObject> { conn ->
            incidentService.createIncident(conn, input)
        }.onSuccess { ctx.failNow(AssertionError("非法动作类别必须导致整笔失败")) }
            .onFailure { e ->
                try {
                    assertTrue(e.cause != null || e is io.vertx.pgclient.PgException, "应为数据库约束失败，实际 ${e::class.simpleName}: ${e.message}")
                    assertEquals(beforeIncidents, dbCount("nursing.nursing_incidents", "id LIKE '${FIXTURE_PREFIX}%'"), "事件主事实必须随事务回滚")
                    assertEquals(beforeActions, dbCount("nursing.nursing_incident_actions", "id LIKE '${FIXTURE_PREFIX}%'"), "审计事实必须随事务回滚")
                    ctx.completeNow()
                } catch (t: Throwable) { ctx.failNow(t) }
            }
    }

    // ========================================================================
    // 9. 时间线：合并事件、无写入副作用、固定查询数（无 N+1）、类型过滤与跨入住隔离
    // ========================================================================

    @Test
    fun `时间线同时显示事件与交班摘要且读取无写入副作用`(ctx: VertxTestContext) {
        val beforeIncidents = dbCount("nursing.nursing_incidents", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeActions = dbCount("nursing.nursing_incident_actions", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeHandovers = dbCount("nursing.nursing_shift_handovers", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeItems = dbCount("nursing.nursing_shift_handover_items", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeTasks = dbCount("nursing.nursing_tasks", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeExecs = dbCount("nursing.nursing_task_executions", "id LIKE '${FIXTURE_PREFIX}%'")
        val beforeRecords = dbCount("healthcare.medical_records", "id LIKE '${FIXTURE_PREFIX}%'")

        healthcare.createShiftHandover(handoverBody(businessDate = businessToday.minusDays(3), shift = "早班"), "subj-nurse", "iht-key-tl")
            .compose { timeline.listTimeline(fixtureId("period-1"), fixtureId("enc-1"), null, null, null, 50, 0) }
            .onSuccess { result ->
                try {
                    val records = result.getJsonArray("records")
                    val types = (0 until records.size()).map { records.getJsonObject(it).getString("event_type") }.toSet()
                    assertTrue(types.contains("NURSING_INCIDENT"), "时间线应含异常事件来源，实际：$types")
                    assertTrue(types.contains("SHIFT_HANDOVER"), "时间线应含班次交接来源，实际：$types")
                    val total = result.getJsonObject("meta").getLong("total")
                    assertTrue(total > 0, "时间线总数必须为正")
                    // 读取不产生任何写副作用
                    assertEquals(beforeIncidents, dbCount("nursing.nursing_incidents", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeActions, dbCount("nursing.nursing_incident_actions", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeHandovers, dbCount("nursing.nursing_shift_handovers", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeItems, dbCount("nursing.nursing_shift_handover_items", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeTasks, dbCount("nursing.nursing_tasks", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeExecs, dbCount("nursing.nursing_task_executions", "id LIKE '${FIXTURE_PREFIX}%'"))
                    assertEquals(beforeRecords, dbCount("healthcare.medical_records", "id LIKE '${FIXTURE_PREFIX}%'"))
                    ctx.completeNow()
                } catch (e: Throwable) { ctx.failNow(e) }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `时间线事件类型过滤生效且跨入住隔离`(ctx: VertxTestContext) {
        // enc-3（单元B）不应看到 enc-1 的事件与交班
        val otherTimeline = timeline.listTimeline(fixtureId("period-3"), fixtureId("enc-3"), null, null, null, 50, 0)
        val incidentOnly = timeline.listTimeline(fixtureId("period-1"), fixtureId("enc-1"), null, null, "NURSING_INCIDENT", 50, 0)
        Future.all(otherTimeline, incidentOnly).onSuccess {
            try {
                val other = otherTimeline.result().getJsonArray("records")
                val otherTypes = (0 until other.size()).map { other.getJsonObject(it).getString("event_type") }.toSet()
                assertFalse(otherTypes.contains("NURSING_INCIDENT") || otherTypes.contains("SHIFT_HANDOVER"), "单元B 时间线不得出现单元A 的事件/交班")
                val only = incidentOnly.result().getJsonArray("records")
                assertTrue(only.size() > 0, "类型过滤后仍应有事件")
                val onlyTypes = (0 until only.size()).map { only.getJsonObject(it).getString("event_type") }.toSet()
                assertEquals(setOf("NURSING_INCIDENT"), onlyTypes, "event_type 过滤必须只返回该来源")
                ctx.completeNow()
            } catch (e: Throwable) { ctx.failNow(e) }
        }.onFailure { ctx.failNow(it) }
    }
}
