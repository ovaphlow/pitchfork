package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate

/**
 * 养老离院交接摘要（DISCHARGE_SUMMARY）PostgreSQL 集成测试。
 *
 * 验证：
 *   1. 首次归档创建一条 DISCHARGE_SUMMARY，record_date 等于离院日期，快照字段正确
 *   2. 快照不含另一次入住、另一长者、敏感身份数据
 *   3. 相同请求重试返回同一 ID；不同输入返回 409
 *   4. 未离院、未完成/缺失 period、日期不一致和非养老 encounter 被拒绝
 *   5. 创建摘要前后，原始数据不变；没有新生成执行或库存操作
 *   6. 摘要创建后新增护理记录更正，GET 仍返回已归档快照
 *
 * 依赖真实 PostgreSQL（与测试的 aceso_test 共享授权环境），
 * 通过 -Dintegration.db.* 系统属性启用；默认运行被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElderlyDischargeHandoverIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18425
        private const val FIXTURE_PREFIX = "dha-"
        private val BASE_PATH = "/healthcare/v1"
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: io.vertx.sqlclient.Pool
    private var server: io.vertx.core.http.HttpServer? = null

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}$suffix"

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
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)

            val healthcareRouter = HealthcareRoutes.create(vertx, pool)
            val rootRouter = Router.router(vertx)
            rootRouter.route("/healthcare/v1/*").subRouter(healthcareRouter)
            vertx.createHttpServer()
                .requestHandler(rootRouter)
                .listen(TEST_PORT)
                .onComplete { ar ->
                    if (ar.succeeded()) {
                        server = ar.result()
                        ctx.completeNow()
                    } else {
                        ctx.failNow(ar.cause())
                    }
                }
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    @BeforeEach
    fun setupTestFixtures() {
        cleanupFixtures()
        setupFixtures()
    }

    @AfterEach
    fun cleanupTestFixtures() {
        cleanupFixtures()
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        cleanupFixtures()
        if (::pool.isInitialized) pool.close()

        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    private fun cleanupFixtures() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            // 按依赖顺序清理：文书 → 执行 → 任务 → 措施 → 计划 → 评估 → 周期 → encounter → 患者
            stmt.execute("DELETE FROM healthcare.medical_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')))")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'))")
            stmt.execute("DELETE FROM nursing.nursing_plan_items WHERE plan_id IN (SELECT id FROM nursing.nursing_plans WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')))")
            stmt.execute("DELETE FROM nursing.nursing_plans WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'))")
            stmt.execute("DELETE FROM nursing.nursing_assessments WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'))")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")

            val residual = stmt.executeQuery("""
                SELECT (
                    (SELECT count(*) FROM healthcare.medical_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id IN (SELECT id FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%' OR patient_id LIKE '${FIXTURE_PREFIX}%')) +
                    (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_plan_items WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_plans WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_assessments WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%') +
                    (SELECT count(*) FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%')
                ) AS residual
            """.trimIndent())
            residual.next()
            check(residual.getLong("residual") == 0L) { "fixture cleanup left residual data" }
        }
    }

    /**
     * fixture：
     *   - dha-patient-1 + dha-enc-discharged（ELDERLY_CARE, DISCHARGED + COMPLETED 周期）→ 正常归档
     *   - dha-patient-1 + dha-enc-active（ELDERLY_CARE, ACTIVE）→ 同长者重新入住，验证隔离
     *   - dha-patient-2 + dha-enc-discharged2（ELDERLY_CARE, DISCHARGED + COMPLETED 周期）→ 另一位已离院长者
     *   - dha-patient-3 + dha-enc-noperiod（ELDERLY_CARE, DISCHARGED，无周期）→ 缺周期拒绝
     *   - dha-patient-4 + dha-enc-notcompleted（ELDERLY_CARE, DISCHARGED + ACTIVE 周期）→ 周期未完成拒绝
     *   - dha-patient-5 + dha-enc-outpatient（OUTPATIENT, DISCHARGED）→ 非养老拒绝
     *   - dha-patient-6 + dha-enc-datediff（ELDERLY_CARE, DISCHARGED，日期不一致）→ 日期不一致拒绝
     */
    private fun setupFixtures() {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()

            // 患者
            for (i in 1..7) {
                stmt.execute("INSERT INTO healthcare.patients (id, name, gender, birth_date, emergency_contact, allergies, past_history, status) VALUES ('${fixtureId("patient-$i")}', '归档测试长者$i', '男', '1940-01-01', '{\"name\":\"联系人$i\",\"phone\":\"1380000000$i\"}', '[{\"allergen\":\"青霉素\"}]', '高血压病史', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            }
            stmt.execute("UPDATE healthcare.patients SET id_card_no = 'DHA-ID-CARD-1', address = 'DHA-秘密地址', medical_insurance = 'DHA-医保号', metadata = '{\"private\":\"不得进入快照\"}' WHERE id = '${fixtureId("patient-1")}'")

            // 已离院 encounter（正常归档）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, admitting_diagnosis, discharge_diagnosis, attending_physician, status)
                VALUES ('${fixtureId("enc-discharged")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'DHA-20260731-01', '2026-07-01T00:00:00+08:00', '2026-07-31T00:00:00Z', '高血压', '病情稳定', '赵医生', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 同长者活动重新入住（验证隔离）
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
                VALUES ('${fixtureId("enc-active")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'DHA-20260801-01', '2026-08-01T00:00:00+08:00', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 另一位已离院长者
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-discharged2")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', 'DHA-20260731-02', '2026-07-01T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 缺周期 encounter
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-noperiod")}', '${fixtureId("patient-3")}', 'ELDERLY_CARE', 'DHA-20260731-03', '2026-07-31T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 周期未完成 encounter
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-notcompleted")}', '${fixtureId("patient-4")}', 'ELDERLY_CARE', 'DHA-20260731-04', '2026-07-31T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 非养老 encounter
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-outpatient")}', '${fixtureId("patient-5")}', 'OUTPATIENT', 'DHA-20260731-05', '2026-07-31T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 日期不一致 encounter
            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-datediff")}', '${fixtureId("patient-6")}', 'ELDERLY_CARE', 'DHA-20260731-06', '2026-07-01T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 已离院周期（COMPLETED，end_date = discharge_date）
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, coordinator, status)
                VALUES ('${fixtureId("period-discharged")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', '${fixtureId("enc-discharged")}', '2026-07-01', '2026-07-31', '钱协调', 'COMPLETED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 另一位已离院周期
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status)
                VALUES ('${fixtureId("period-discharged2")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', '${fixtureId("enc-discharged2")}', '2026-07-01', '2026-07-31', 'COMPLETED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 活动周期（同长者重新入住）
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status)
                VALUES ('${fixtureId("period-active")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', '${fixtureId("enc-active")}', '2026-08-01', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 未完成周期
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status)
                VALUES ('${fixtureId("period-notcompleted")}', '${fixtureId("patient-4")}', 'ELDERLY_CARE', '${fixtureId("enc-notcompleted")}', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 日期不一致周期（end_date ≠ discharge_date）
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status)
                VALUES ('${fixtureId("period-datediff")}', '${fixtureId("patient-6")}', 'ELDERLY_CARE', '${fixtureId("enc-datediff")}', '2026-07-01', '2026-08-01', 'COMPLETED')
                ON CONFLICT (id) DO NOTHING
            """)

            // 评估（已离院周期）
            stmt.execute("""
                INSERT INTO nursing.nursing_assessments (id, encounter_id, period_id, assess_type, assess_date, assessor, total_score, result_level, detail, remark)
                VALUES ('${fixtureId("assess-1")}', '${fixtureId("enc-discharged")}', '${fixtureId("period-discharged")}', 'ADMISSION', '2026-07-01', '王护士', 12.5, '中', '{"note":"入院评估"}', '无')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_assessments (id, encounter_id, period_id, assess_type, assess_date, assessor, total_score, result_level)
                VALUES ('${fixtureId("assess-2")}', '${fixtureId("enc-discharged")}', '${fixtureId("period-discharged")}', 'FALL_RISK', '2026-07-31', '王护士', 8.0, '低')
                ON CONFLICT (id) DO NOTHING
            """)

            // 计划与措施
            stmt.execute("""
                INSERT INTO nursing.nursing_plans (id, period_id, encounter_id, plan_name, goals, status, created_by, start_date)
                VALUES ('${fixtureId("plan-1")}', '${fixtureId("period-discharged")}', '${fixtureId("enc-discharged")}', '跌倒预防', '降低跌倒风险', 'COMPLETED', '李护师', '2026-07-01')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_plan_items (id, plan_id, action, frequency_code, frequency_name, duration_days, status)
                VALUES ('${fixtureId("item-1")}', '${fixtureId("plan-1")}', '床栏检查', 'QD', '每日一次', 30, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_plan_items (id, plan_id, action, frequency_code, frequency_name, duration_days, status)
                VALUES ('${fixtureId("item-2")}', '${fixtureId("plan-1")}', '夜间巡视', 'QD', '每日一次', 30, 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 任务
            stmt.execute("""
                INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, frequency_code, start_date, end_date, status)
                VALUES ('${fixtureId("task-1")}', '${fixtureId("period-discharged")}', '${fixtureId("enc-discharged")}', 'NURSING', '晨间翻身', 'QD', '2026-07-01', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, frequency_code, start_date, end_date, status)
                VALUES ('${fixtureId("task-2")}', '${fixtureId("period-discharged")}', '${fixtureId("enc-discharged")}', 'NURSING', '血压监测', 'QD', '2026-07-01', '2026-07-31', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
            """)

            // 执行（各状态）
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, actual_time, executor, status) VALUES ('${fixtureId("exec-completed")}', '${fixtureId("task-1")}', '2026-07-31T09:00:00+08:00', '2026-07-31T09:20:00+08:00', '王护士', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-pending")}', '${fixtureId("task-1")}', '2026-07-30T09:00:00+08:00', 'PENDING') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-skipped")}', '${fixtureId("task-2")}', '2026-07-29T09:00:00+08:00', 'SKIPPED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-cancelled")}', '${fixtureId("task-2")}', '2026-07-28T09:00:00+08:00', 'CANCELLED') ON CONFLICT (id) DO NOTHING")

            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-1")}', '${fixtureId("enc-discharged")}', 'NURSING_RECORD', '日常护理记录', '今日长者状态良好', '王护士', '2026-07-31', '{\"period_id\":\"${fixtureId("period-discharged")}\",\"record_kind\":\"MANUAL\",\"record_time\":\"2026-07-31T10:00:00+08:00\"}') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-1-corr")}', '${fixtureId("enc-discharged")}', 'NURSING_RECORD', '日常护理记录（更正）', '今日长者状态良好，已服药', '李护师', '2026-07-31', '{\"period_id\":\"${fixtureId("period-discharged")}\",\"record_kind\":\"CORRECTION\",\"record_time\":\"2026-07-31T11:00:00+08:00\",\"corrects_record_id\":\"${fixtureId("record-1")}\"}') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-unrelated")}', '${fixtureId("enc-discharged")}', 'ADMISSION', '其他医疗文书', '不得进入离院交接快照', '系统', '2026-07-31', '{}') ON CONFLICT (id) DO NOTHING")

            stmt.execute("INSERT INTO nursing.nursing_assessments (id, encounter_id, period_id, assess_type, assess_date, assessor, total_score, result_level) VALUES ('${fixtureId("assess-active")}', '${fixtureId("enc-active")}', '${fixtureId("period-active")}', 'OTHER', '2026-08-01', '诱饵', 1, '低') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_plans (id, period_id, encounter_id, plan_name, status, created_by, start_date) VALUES ('${fixtureId("plan-active")}', '${fixtureId("period-active")}', '${fixtureId("enc-active")}', '其他周期计划', 'ACTIVE', '诱饵', '2026-08-01') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_plan_items (id, plan_id, action, status) VALUES ('${fixtureId("item-active")}', '${fixtureId("plan-active")}', '其他周期措施', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, start_date, status) VALUES ('${fixtureId("task-active")}', '${fixtureId("period-active")}', '${fixtureId("enc-active")}', 'NURSING', '其他周期任务', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-active")}', '${fixtureId("task-active")}', '2026-08-01T09:00:00+08:00', 'PENDING') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-active")}', '${fixtureId("enc-active")}', 'NURSING_RECORD', '其他周期护理记录', '不得进入离院快照', '诱饵', '2026-08-01', '{\"period_id\":\"${fixtureId("period-active")}\",\"record_kind\":\"MANUAL\",\"record_time\":\"2026-08-01T10:00:00+08:00\"}') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_assessments (id, encounter_id, period_id, assess_type, assess_date, assessor, total_score, result_level) VALUES ('${fixtureId("assess-other")}', '${fixtureId("enc-discharged2")}', '${fixtureId("period-discharged2")}', 'OTHER', '2026-07-01', '其他长者', 2, '低') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-other")}', '${fixtureId("enc-discharged2")}', 'NURSING_RECORD', '其他长者护理记录', '不得进入本摘要', '其他长者', '2026-07-01', '{\"period_id\":\"${fixtureId("period-discharged2")}\",\"record_kind\":\"MANUAL\",\"record_time\":\"2026-07-01T10:00:00+08:00\"}') ON CONFLICT (id) DO NOTHING")

            stmt.execute("""
                INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
                VALUES ('${fixtureId("enc-mismatch")}', '${fixtureId("patient-7")}', 'ELDERLY_CARE', 'DHA-20260731-07', '2026-07-01T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED')
                ON CONFLICT (id) DO NOTHING
            """)
            stmt.execute("""
                INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status)
                VALUES ('${fixtureId("period-mismatch")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', '${fixtureId("enc-mismatch")}', '2026-07-01', '2026-07-31', 'COMPLETED')
                ON CONFLICT (id) DO NOTHING
            """)
        }
    }

    private fun request(
        vertx: Vertx,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): io.vertx.core.Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        val req = client.request(method, TEST_PORT, "localhost", path)
            .compose { r ->
                if (body != null) r.putHeader("Content-Type", "application/json").send(body.encode())
                else r.send()
            }
        return req.compose { resp ->
            resp.body().map { b ->
                val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                Pair(resp.statusCode(), json)
            }
        }.onComplete { client.close() }
    }

    // ——— 断言 1：首次归档创建正确 ———

    @Test
    fun `首次归档创建DISCHARGE_SUMMARY且快照字段正确`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover",
            JsonObject().put("author", "王护理师").put("handover_note", "已向家属说明注意事项"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(201, status, "首次创建必须 201")
                assertEquals("DISCHARGE_SUMMARY", body.getString("record_type"))
                assertEquals("养老照护离院交接摘要", body.getString("title"))
                assertEquals(fixtureId("enc-discharged"), body.getString("encounter_id"))
                assertEquals(fixtureId("period-discharged"), body.getString("period_id"))
                assertEquals("2026-07-31", body.getString("record_date"), "record_date 必须等于离院日期")
                assertEquals("王护理师", body.getString("author"))
                assertEquals("已向家属说明注意事项", body.getString("handover_note"))
                assertEquals(1, body.getInteger("snapshot_version"))

                val snapshot = body.getJsonObject("snapshot")
                assertNotNull(snapshot, "快照必须存在")

                // 患者信息
                val patient = snapshot.getJsonObject("patient")
                assertEquals(fixtureId("patient-1"), patient.getString("id"))
                assertEquals("归档测试长者1", patient.getString("name"))

                // 入住信息
                val encounter = snapshot.getJsonObject("encounter")
                assertEquals(fixtureId("enc-discharged"), encounter.getString("id"))
                assertEquals("DISCHARGED", encounter.getString("status"))

                // 周期信息
                val carePeriod = snapshot.getJsonObject("care_period")
                assertEquals(fixtureId("period-discharged"), carePeriod.getString("id"))
                assertEquals("COMPLETED", carePeriod.getString("status"))

                // 评估
                val assessments = snapshot.getJsonArray("assessments")
                assertEquals(2, assessments.size(), "必须包含 2 条评估")

                // 计划
                val plans = snapshot.getJsonArray("plans")
                assertEquals(1, plans.size(), "必须包含 1 条计划")
                val planItems = plans.getJsonObject(0).getJsonArray("items")
                assertEquals(2, planItems.size(), "计划必须包含 2 条措施")

                // 任务
                val tasks = snapshot.getJsonArray("tasks")
                assertEquals(2, tasks.size(), "必须包含 2 条任务")

                // 执行状态计数
                val execSummary = snapshot.getJsonObject("execution_summary")
                assertEquals(1, execSummary.getInteger("COMPLETED"))
                assertEquals(1, execSummary.getInteger("PENDING"))
                assertEquals(0, execSummary.getInteger("IN_PROGRESS"))
                assertEquals(1, execSummary.getInteger("SKIPPED"))
                assertEquals(1, execSummary.getInteger("CANCELLED"))

                // 护理记录
                val records = snapshot.getJsonArray("nursing_records")
                assertEquals(2, records.size(), "必须包含原始记录和更正记录")

                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ——— 断言 2：快照隔离 ———

    @Test
    fun `快照不含另一次入住或另一长者数据`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover",
            JsonObject().put("author", "测试员"),
        ).compose {
            // 先为另一位已离院长者创建摘要
            request(
                vertx,
                HttpMethod.POST,
                "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged2")}/discharge-handover",
                JsonObject().put("author", "测试员"),
            )
        }.compose {
            // 获取第一位长者的摘要
            request(vertx, HttpMethod.GET, "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover")
        }.onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(200, status)
                val snapshot = body.getJsonObject("snapshot")

                // 快照中的 patient 必须是 patient-1
                assertEquals(fixtureId("patient-1"), snapshot.getJsonObject("patient").getString("id"))

                // 快照中的 encounter 必须是 enc-discharged
                assertEquals(fixtureId("enc-discharged"), snapshot.getJsonObject("encounter").getString("id"))

                // 快照中的 care_period 必须是 period-discharged
                assertEquals(fixtureId("period-discharged"), snapshot.getJsonObject("care_period").getString("id"))

                // 不含敏感数据
                val patientJson = snapshot.getJsonObject("patient").encode()
                assertFalse(patientJson.contains("id_card_no"), "快照不得包含身份证号")
                assertFalse(patientJson.contains("address"), "快照不得包含地址")
                assertFalse(patientJson.contains("medical_insurance"), "快照不得包含医保号")
                assertFalse(patientJson.contains("DHA-秘密地址"), "快照不得包含地址值")
                assertFalse(patientJson.contains("DHA-医保号"), "快照不得包含医保值")
                assertFalse(patientJson.contains("private"), "快照不得包含患者通用 metadata")
                assertFalse(body.encode().contains("enc-active"), "快照不得混入同一患者其他入住")
                assertFalse(body.encode().contains("其他周期"), "快照不得混入其他周期护理事实")
                assertFalse(body.encode().contains("其他长者"), "快照不得混入其他患者护理事实")
                assertFalse(body.encode().contains("其他医疗文书"), "快照不得混入其他医疗文书")

                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ——— 断言 3：幂等与冲突 ———

    @Test
    fun `相同请求重试返回同一ID不同输入返回409`(vertx: Vertx, ctx: VertxTestContext) {
        val path = "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover"
        val sameBody = JsonObject().put("author", "王护理师").put("handover_note", "已向家属说明注意事项")
        request(vertx, HttpMethod.POST, path, sameBody).compose { (status1, body1) ->
            ctx.verify {
                assertEquals(201, status1, "首次创建必须 201")
                assertNotNull(body1.getString("id"), "必须返回文书 ID")
            }
            request(vertx, HttpMethod.POST, path, sameBody).compose { (status2, body2) ->
                ctx.verify {
                    assertEquals(200, status2, "相同输入重试必须 200")
                    assertEquals(body1.getString("id"), body2.getString("id"), "重试必须返回同一 ID")
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    path,
                    JsonObject().put("author", "李护理师").put("handover_note", "不同备注"),
                )
            }
        }.onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status, "不同输入必须 409")
                assertNotNull(body.getString("error"), "错误响应必须包含 error 字段")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ——— 断言 4：资格拒绝 ———

    @Test
    fun `未离院encounter返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-active")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("not discharged"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非养老encounter返回400`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-outpatient")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(400, status)
                assertTrue(body.getString("error").contains("not an elderly admission"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `缺少精确关联周期返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-noperiod")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("care period"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `未完成周期返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-notcompleted")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("not completed"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `周期结束日期不一致返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-datediff")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("end date"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `周期患者不一致返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-mismatch")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(409, status)
                assertTrue(body.getString("error").contains("patient_id mismatch"), "got: ${body.getString("error")}")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `并发相同请求只创建一条摘要并返回同一ID`(vertx: Vertx, ctx: VertxTestContext) {
        val path = "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover"
        val body = JsonObject().put("author", "并发交接人").put("handover_note", "并发备注")
        val first = request(vertx, HttpMethod.POST, path, body)
        val second = request(vertx, HttpMethod.POST, path, body)

        io.vertx.core.CompositeFuture.all(first, second).onSuccess { results ->
            ctx.verify {
                val response1 = results.resultAt<Pair<Int, JsonObject>>(0)
                val response2 = results.resultAt<Pair<Int, JsonObject>>(1)
                assertTrue(setOf(response1.first, response2.first) == setOf(201, 200), "responses: $response1 / $response2")
                assertEquals(response1.second.getString("id"), response2.second.getString("id"))

                DriverManager.getConnection("jdbc:postgresql://$host:$port/$TEST_DB", user, password).use { conn ->
                    conn.prepareStatement("SELECT count(*) FROM healthcare.medical_records WHERE record_type = 'DISCHARGE_SUMMARY' AND encounter_id = ? AND metadata ->> 'is_elderly_discharge_handover' = 'true'").use { statement ->
                        statement.setString(1, fixtureId("enc-discharged"))
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1, rows.getLong(1))
                        }
                    }
                }
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `不存在encounter返回404`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-missing")}/discharge-handover",
            JsonObject().put("author", "王护理师"),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(404, status)
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ——— 断言 5：原始数据无副作用 ———

    @Test
    fun `创建摘要前后原始数据行数不变`(vertx: Vertx, ctx: VertxTestContext) {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"

        // 记录创建前的行数
        val beforeCounts = DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
            val stmt = conn.createStatement()
            val assessments = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_assessments WHERE period_id = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            val plans = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_plans WHERE period_id = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            val tasks = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_tasks WHERE period_id = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            val executions = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_task_executions WHERE task_id IN (SELECT id FROM nursing.nursing_tasks WHERE period_id = '${fixtureId("period-discharged")}')").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            val records = stmt.executeQuery("SELECT count(*) FROM healthcare.medical_records WHERE record_type = 'NURSING_RECORD' AND encounter_id = '${fixtureId("enc-discharged")}' AND metadata ->> 'period_id' = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            mapOf("assessments" to assessments, "plans" to plans, "tasks" to tasks, "executions" to executions, "records" to records)
        }

        // 创建摘要（幂等，已存在则返回 200）
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover",
            JsonObject().put("author", "王护理师").put("handover_note", "已向家属说明注意事项"),
        ).compose {
            // 记录创建后的行数
            val afterCounts = DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                val stmt = conn.createStatement()
                val assessments = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_assessments WHERE period_id = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                val plans = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_plans WHERE period_id = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                val tasks = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_tasks WHERE period_id = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                val executions = stmt.executeQuery("SELECT count(*) FROM nursing.nursing_task_executions WHERE task_id IN (SELECT id FROM nursing.nursing_tasks WHERE period_id = '${fixtureId("period-discharged")}')").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                val records = stmt.executeQuery("SELECT count(*) FROM healthcare.medical_records WHERE record_type = 'NURSING_RECORD' AND encounter_id = '${fixtureId("enc-discharged")}' AND metadata ->> 'period_id' = '${fixtureId("period-discharged")}'").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                mapOf("assessments" to assessments, "plans" to plans, "tasks" to tasks, "executions" to executions, "records" to records)
            }

            ctx.verify {
                assertEquals(beforeCounts["assessments"], afterCounts["assessments"], "评估行数不得变化")
                assertEquals(beforeCounts["plans"], afterCounts["plans"], "计划行数不得变化")
                assertEquals(beforeCounts["tasks"], afterCounts["tasks"], "任务行数不得变化")
                assertEquals(beforeCounts["executions"], afterCounts["executions"], "执行行数不得变化")
                assertEquals(beforeCounts["records"], afterCounts["records"], "护理记录行数不得变化")
                ctx.completeNow()
            }
            io.vertx.core.Future.succeededFuture<Unit>(Unit)
        }.onFailure { ctx.failNow(it) }
    }

    // ——— 断言 6：后续更正不改写快照 ———

    @Test
    fun `归档后新增护理记录更正GET仍返回原快照`(vertx: Vertx, ctx: VertxTestContext) {
        val jdbcUrl = "jdbc:postgresql://$host:$port/$TEST_DB"
        val originalSnapshotHolder = mutableListOf<String>()

        // 首次归档并获取冻结快照
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover",
            JsonObject().put("author", "归档测试人"),
        ).compose { (status1, body1) ->
                ctx.verify {
                    assertEquals(201, status1)
                }
                originalSnapshotHolder.add(body1.getJsonObject("snapshot").encode())

                // 新增一条护理记录更正
                DriverManager.getConnection(jdbcUrl, user, password).use { conn ->
                    conn.createStatement().execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-2-corr")}', '${fixtureId("enc-discharged")}', 'NURSING_RECORD', '补充记录', '新增更正内容', '赵护师', '2026-08-01', '{\"period_id\":\"${fixtureId("period-discharged")}\",\"record_kind\":\"CORRECTION\",\"record_time\":\"2026-08-01T10:00:00+08:00\",\"corrects_record_id\":\"${fixtureId("record-1")}\"}') ON CONFLICT (id) DO NOTHING")
                }

                // 重新获取摘要
                request(vertx, HttpMethod.GET, "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged")}/discharge-handover")
            }.onSuccess { (status2, body2) ->
                ctx.verify {
                    assertEquals(200, status2)
                    val currentSnapshot = body2.getJsonObject("snapshot").encode()
                    // 快照内容必须与之前完全一致（字节语义相同）
                    assertEquals(originalSnapshotHolder[0], currentSnapshot, "归档后快照不得变化")
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ——— GET 无摘要返回 404 ———

    @Test
    fun `GET无既有摘要返回404`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "$BASE_PATH/elderly-admissions/${fixtureId("enc-discharged2")}/discharge-handover")
            .onSuccess { (status, _) ->
                ctx.verify {
                    assertEquals(404, status)
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }
}
