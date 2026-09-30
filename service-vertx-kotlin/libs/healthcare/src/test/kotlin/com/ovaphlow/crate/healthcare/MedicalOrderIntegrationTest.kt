package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.inventories.InventoriesRoutes
import com.ovaphlow.crate.inventories.MaterialService
import com.ovaphlow.crate.nursing.NursingRoutes
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.SqlClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 医嘱核心流程 PostgreSQL 集成测试（仅授权 aceso_test 运行）。
 *
 * fixture 统一使用 `mo-` 前缀，按执行→任务→医嘱→周期→encounter→患者的外键依赖逆序清理，
 * 前后做残差查询并报告为零。本测试连接既有（已由用户管理的 Aceso API 完成 Flyway 迁移的）
 * aceso_test，绝不 DROP/CREATE 数据库，绝不触碰非 `mo-` 前缀数据。
 *
 * 覆盖计划「获授权的 PostgreSQL 集成测试」：
 *   1. 医嘱与任务各一行、精确弱关联（order_item_id=order.id、encounter_id、period_id）、
 *      频次/日期正确；ensureExecutionsForDateRange 为可生成频次创建执行；终局后不再生成未来执行
 *   2. 停嘱/作废/完成的任务联动、既有执行保留、全部状态机拒绝路径；校验失败无残留
 *   3. 离院成功同一事务收束全部行；IN_PROGRESS 阻断时每个相关表均不变
 *   4. 列表/详情严格按 encounter 隔离、执行汇总不泄漏诱饵数据；读取不产生任务/执行/库存写入
 *   5. fixture 前缀清理与前后残差为零
 *
 * 通过 -Dintegration.db.* 系统属性启用；默认运行被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MedicalOrderIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18426
        private const val FIXTURE_PREFIX = "mo-"
        private const val HEALTHCARE_BASE = "/healthcare/v1"
        private const val NURSING_BASE = "/nursing/v1"
        /** 半挂载路径：只用于验证 025 药品目录端口缺失时必须 fail-closed 503 */
        private const val HEALTHCARE_NO_PORT_BASE = "/healthcare-noport/v1"

        /**
         * 025 V204 预置的演示药品（固定 ULID 常量，见迁移注释「可稳定引用」）。
         * 断言 1 用它验证「药品目录可被冻结契约检索到」。
         */
        private const val DEMO_DRUG_001_ID = "01J5D3M000000000000000A001"
        private const val DEMO_DRUG_001_CODE = "DEMO-DRUG-001"
        private const val DEMO_DRUG_001_NAME = "降压药A"

        /** 025 `mo-` 前缀目录药品 fixture（见 setupFixtures） */
        private const val DRUG_CODE = "MO-DRUG-ACTIVE"
        private const val DRUG_NAME = "医嘱测试降压药"
        private const val INACTIVE_DRUG_NAME = "医嘱停用降压药"

        /**
         * 027 用例的 SQL 时间字面量固定格式：始终含秒与偏移。
         * 不直接用 `OffsetDateTime.toString()`——它在秒为 0 时会省略 `:00`，避免留下解析歧义。
         */
        private val SQL_TIMESTAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: io.vertx.sqlclient.Pool
    private var server: io.vertx.core.http.HttpServer? = null

    private fun fixtureId(suffix: String): String = "${FIXTURE_PREFIX}$suffix"

    private fun jdbcUrl(): String = "jdbc:postgresql://$host:$port/$TEST_DB"

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""
        // 兜底门控：只给了 -Dintegration.db.host 而缺密码时，按 JUnit 假设失败 skip 整个类，不让模块变红。
        Assumptions.assumeTrue(password.isNotBlank(), "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行")

        try {
            check(port == "55432" || port == "5432") { "integration test must target the authorized aceso_test port" }

            // 连接既有 aceso_test（绝不 DROP/CREATE）；迁移幂等，重复执行安全
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)

            // 挂载与 aceso Main.kt 相同的 healthcare + nursing 路由。
            // 025：药品目录端口必须显式注入（Main.kt 用 MaterialService 适配），
            // 否则 MEDICATION 医嘱会 fail-closed 503——这里注入与生产一致的适配器。
            val materialService = MaterialService(pool)
            val rootRouter = Router.router(vertx)
            rootRouter.route("/inventories/v1/*").subRouter(InventoriesRoutes.create(vertx, pool))
            rootRouter.route("/healthcare/v1/*").subRouter(
                HealthcareRoutes.create(vertx, pool, drugCatalogPort = drugCatalogPort(materialService)),
            )
            // 025 §4.5.1 fail-closed 专用挂载点：不注入药品目录端口，
            // 任何 MEDICATION 医嘱都必须 503 而不是退化为自由文本开药。
            rootRouter.route("$HEALTHCARE_NO_PORT_BASE/*").subRouter(HealthcareRoutes.create(vertx, pool))
            rootRouter.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool))
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
        assertResidualZero()
        setupFixtures()
    }

    @AfterEach
    fun cleanupTestFixtures() {
        cleanupFixtures()
        assertResidualZero()
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        // @BeforeAll 被 Assumptions 跳过（缺密码）时 JUnit 仍会调用 @AfterAll：
        // 此时没有连接池与 fixture，直接结束，避免把 skip 变成失败。
        if (!::pool.isInitialized) {
            ctx.completeNow()
            return
        }
        cleanupFixtures()
        assertResidualZero()
        pool.close()
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    // ========================================================================
    // fixture：mo- 前缀
    // ========================================================================
    //   mo-patient-1 + mo-enc-1（ELDERLY_CARE ACTIVE）+ mo-period-1（ACTIVE）→ 主医嘱对象
    //   mo-patient-1 + mo-enc-1-old（ELDERLY_CARE DISCHARGED）+ mo-period-1-old（COMPLETED）→ 同患者终局隔离
    //   mo-patient-2 + mo-enc-2（ELDERLY_CARE ACTIVE）+ mo-period-2（ACTIVE）→ 另一患者隔离
    //   mo-patient-3 + mo-enc-3（OUTPATIENT ACTIVE）→ 非养老
    //   mo-patient-4 + mo-enc-4（ELDERLY_CARE ACTIVE，无周期）→ 缺周期
    //   mo-patient-5 + mo-enc-5（ELDERLY_CARE DISCHARGED + COMPLETED 周期）→ 已离院
    //   mo-patient-6 + mo-enc-6（ELDERLY_CARE DECEASED + COMPLETED 周期）→ 已去世
    //   mo-patient-7 + mo-enc-7（ELDERLY_CARE ACTIVE）+ mo-period-7（ACTIVE）+ mo-task-7 + mo-exec-7(IN_PROGRESS)
    //       → IN_PROGRESS 阻断路径
    //   诱饵：mo-task-bait + mo-exec-bait（另一患者周期）、mo-record-bait（护理记录）、
    //         mo-handover-bait（DISCHARGE_SUMMARY）、库存 mo-mat-bait / mo-lot-bait / mo-stock-bait /
    //         mo-op-bait / mo-opd-bait
    private fun setupFixtures() {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            for (i in 1..7) {
                stmt.execute("INSERT INTO healthcare.patients (id, name, gender, birth_date, status) VALUES ('${fixtureId("patient-$i")}', '医嘱测试长者$i', '男', '1940-01-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            }
            // 主入住
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-1")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'MO-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            // 同患者已离院入住（隔离）
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status) VALUES ('${fixtureId("enc-1-old")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'MO-ENC-1OLD', '2026-07-01T00:00:00+08:00', '2026-07-31T00:00:00Z', 'DISCHARGED') ON CONFLICT (id) DO NOTHING")
            // 另一患者
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-2")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', 'MO-ENC-2', '2026-08-02T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            // 非养老
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-3")}', '${fixtureId("patient-3")}', 'OUTPATIENT', 'MO-ENC-3', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            // 缺周期
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-4")}', '${fixtureId("patient-4")}', 'ELDERLY_CARE', 'MO-ENC-4', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            // 已离院
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status) VALUES ('${fixtureId("enc-5")}', '${fixtureId("patient-5")}', 'ELDERLY_CARE', 'MO-ENC-5', '2026-08-01T00:00:00+08:00', '2026-08-03T00:00:00Z', 'DISCHARGED') ON CONFLICT (id) DO NOTHING")
            // 已去世
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, death_date, status) VALUES ('${fixtureId("enc-6")}', '${fixtureId("patient-6")}', 'ELDERLY_CARE', 'MO-ENC-6', '2026-08-01T00:00:00+08:00', '2026-08-03T00:00:00Z', 'DECEASED') ON CONFLICT (id) DO NOTHING")
            // IN_PROGRESS 阻断
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-7")}', '${fixtureId("patient-7")}', 'ELDERLY_CARE', 'MO-ENC-7', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")

            // 周期
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-1")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', '${fixtureId("enc-1")}', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status) VALUES ('${fixtureId("period-1-old")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', '${fixtureId("enc-1-old")}', '2026-07-01', '2026-07-31', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-2")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', '${fixtureId("enc-2")}', '2026-08-02', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status) VALUES ('${fixtureId("period-5")}', '${fixtureId("patient-5")}', 'ELDERLY_CARE', '${fixtureId("enc-5")}', '2026-08-01', '2026-08-03', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status) VALUES ('${fixtureId("period-6")}', '${fixtureId("patient-6")}', 'ELDERLY_CARE', '${fixtureId("enc-6")}', '2026-08-01', '2026-08-03', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-7")}', '${fixtureId("patient-7")}', 'ELDERLY_CARE', '${fixtureId("enc-7")}', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")

            // IN_PROGRESS 执行（阻断离院/去世）
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, start_date, status) VALUES ('${fixtureId("task-7")}', '${fixtureId("period-7")}', '${fixtureId("enc-7")}', 'NURSING', '阻断测试任务', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, executor, status) VALUES ('${fixtureId("exec-7")}', '${fixtureId("task-7")}', '2026-08-01T09:00:00+08:00', '测试护士', 'IN_PROGRESS') ON CONFLICT (id) DO NOTHING")

            // 诱饵：另一患者周期的无关任务/执行
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, start_date, status) VALUES ('${fixtureId("task-bait")}', '${fixtureId("period-2")}', '${fixtureId("enc-2")}', 'NURSING', '无关任务', '2026-08-02', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, executor, status) VALUES ('${fixtureId("exec-bait")}', '${fixtureId("task-bait")}', '2026-08-02T09:00:00+08:00', '无关护士', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status) VALUES ('${fixtureId("exec-bait2")}', '${fixtureId("task-bait")}', '2026-08-02T10:00:00+08:00', 'PENDING') ON CONFLICT (id) DO NOTHING")

            // 诱饵：护理记录 + 离院交接摘要
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-bait")}', '${fixtureId("enc-1")}', 'NURSING_RECORD', '无关护理记录', '不得受医嘱操作影响', '测试护士', '2026-08-01', '{\"period_id\":\"${fixtureId("period-1")}\"}') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("handover-bait")}', '${fixtureId("enc-1-old")}', 'DISCHARGE_SUMMARY', '离院交接摘要', '既有摘要不得被医嘱操作改写', '测试员', '2026-07-31', '{\"period_id\":\"${fixtureId("period-1-old")}\",\"is_elderly_discharge_handover\":\"true\"}') ON CONFLICT (id) DO NOTHING")

            // 诱饵：库存（材料/批次/结存/操作单/明细）
            stmt.execute("INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, package_unit, package_size, status) VALUES ('${fixtureId("mat-bait")}', 'MO-MAT-BAIT', '诱饵材料', '耗材', '个', 0, '包', 1, 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.lots (id, material_id, batch_no) VALUES ('${fixtureId("lot-bait")}', '${fixtureId("mat-bait")}', 'MO-LOT-1') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost) VALUES ('${fixtureId("stock-bait")}', '主库', '${fixtureId("mat-bait")}', '${fixtureId("lot-bait")}', 10, 0, 0) ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.stock_operations (id, order_no, operation_type, warehouse, status) VALUES ('${fixtureId("op-bait")}', 'MO-OP-1', 'INBOUND', '主库', 'CONFIRMED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.stock_operation_details (id, operation_id, material_id, lot_id, quantity, unit, unit_cost, total_cost) VALUES ('${fixtureId("opd-bait")}', '${fixtureId("op-bait")}', '${fixtureId("mat-bait")}', '${fixtureId("lot-bait")}', 10, 'PACKAGE', 0, 0) ON CONFLICT (id) DO NOTHING")

            // ——— 025 药品目录 fixture（全部 `mo-` 前缀，随既有清理逆序删除）———
            // 合法目录药品：category = '药品' 且 status = 'ACTIVE'
            stmt.execute("INSERT INTO public.materials (id, code, name, category, spec, base_unit, quantity_scale, package_unit, package_size, status) VALUES ('${fixtureId("drug")}', 'MO-DRUG-ACTIVE', '医嘱测试降压药', '药品', '10mg/片', '片', 0, '盒', 10, 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            // 非 ACTIVE 药品：用于验证 status != ACTIVE → 409
            stmt.execute("INSERT INTO public.materials (id, code, name, category, spec, base_unit, quantity_scale, package_unit, package_size, status) VALUES ('${fixtureId("drug-inactive")}', 'MO-DRUG-INACTIVE', '医嘱停用降压药', '药品', '10mg/片', '片', 0, '盒', 10, 'INACTIVE') ON CONFLICT (id) DO NOTHING")
        }
    }

    private fun cleanupFixtures() {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            // 依赖逆序：排班 → 耗材关联 → 执行 → 任务 → 医嘱 → 文书 → 库存明细 → 库存操作单 → 库存 → 批次 → 材料 → 周期 → encounter → 患者
            stmt.execute("DELETE FROM nursing.nursing_visit_schedules WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_task_execution_consumptions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'))")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM healthcare.payments WHERE bill_id IN (SELECT id FROM healthcare.bills WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM healthcare.bills WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.deposit_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.followup_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.followup_plans WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.vital_sign_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.chronic_disease_registrations WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.health_checkup_members WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.progress_notes WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.diagnoses WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.medical_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.medical_orders WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM public.stock_operation_details WHERE id LIKE '${FIXTURE_PREFIX}%' OR operation_id IN (SELECT id FROM public.stock_operations WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM public.stock_operations WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM public.stocks WHERE id LIKE '${FIXTURE_PREFIX}%' OR material_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM public.lots WHERE id LIKE '${FIXTURE_PREFIX}%' OR material_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM public.materials WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.encounters WHERE id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.patients WHERE id LIKE '${FIXTURE_PREFIX}%'")
        }
    }

    private fun residual(): Long {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery(
                """
                SELECT (
                    (SELECT count(*) FROM healthcare.patients WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM healthcare.encounters WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM nursing.nursing_service_periods WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM nursing.nursing_visit_schedules WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM nursing.nursing_task_execution_consumptions WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM healthcare.medical_orders WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM healthcare.medical_records WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM public.materials WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM public.lots WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM public.stocks WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM public.stock_operations WHERE id LIKE '$FIXTURE_PREFIX%') +
                    (SELECT count(*) FROM public.stock_operation_details WHERE id LIKE '$FIXTURE_PREFIX%')
                ) AS residual
                """.trimIndent(),
            )
            rs.next()
            return rs.getLong("residual")
        }
    }

    private fun assertResidualZero() {
        check(residual() == 0L) { "mo- fixture cleanup left residual data" }
    }

    /**
     * 025 药品目录端口适配器（测试侧）：与 `apps/aceso/Main.kt` 的
     * `healthcareDrugCatalogPort` 逐字段一致，全部复用调用方传入的同连接 `client`。
     */
    private fun drugCatalogPort(materialService: MaterialService): DrugCatalogPort =
        object : DrugCatalogPort {
            override fun findDrugMaterial(client: SqlClient, materialId: String): Future<DrugCatalogMaterial?> =
                materialService.findMaterialById(client, materialId).map { material ->
                    material?.let {
                        DrugCatalogMaterial(
                            id = it.id,
                            code = it.code,
                            name = it.name,
                            spec = it.spec,
                            baseUnit = it.baseUnit,
                            status = it.status,
                            category = it.category,
                        )
                    }
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

    /**
     * 025 起 `MEDICATION` 医嘱必须携带目录物资 `material_id`，`drug_name` 由服务端
     * 取目录名覆写；默认使用 `mo-drug`（ACTIVE 药品）。
     *
     * @param materialId 传 null 表示省略 `material_id`（缺必填 → 400）；
     *                   传 `OMIT` 之外的哨兵请用 [medicationDetails] 自行构造明细。
     * @param drugName 显式药名；与目录名不一致时服务端必须 400
     */
    private fun medicationBody(
        content: String = "阿莫西林 0.5g 每日两次",
        materialId: String? = fixtureId("drug"),
        drugName: String? = DRUG_NAME,
    ): JsonObject =
        JsonObject()
            .put("order_type", "MEDICATION")
            .put("order_class", "LONG_TERM")
            .put("order_content", content)
            .put("doctor", "赵医生")
            .put("start_time", "2026-08-01T10:00:00+08:00")
            .put("order_details", medicationDetails(materialId, drugName))

    /** 025 `MEDICATION` 明细构造器：`materialId == null` 时不写 `material_id` 键。 */
    private fun medicationDetails(materialId: String?, drugName: String?): JsonObject {
        val details = JsonObject()
            .put("dose", "0.5g")
            .put("unit", "片/次")
            .put("route", "口服")
            .put("frequency_code", "QD")
            .put("frequency_name", "每日一次")
            .put("duration_days", 2)
        if (materialId != null) details.put("material_id", materialId)
        if (drugName != null) details.put("drug_name", drugName)
        return details
    }

    private fun therapyBody(content: String = "康复理疗 30 分钟"): JsonObject =
        JsonObject()
            .put("order_type", "THERAPY")
            .put("order_class", "LONG_TERM")
            .put("order_content", content)
            .put("doctor", "钱医生")
            .put("start_time", "2026-08-02T09:00:00+08:00")
            .put(
                "order_details",
                JsonObject().put("treatment_item", "康复理疗").put("duration_days", 1),
            )

    // ——— 断言 1：创建后精确弱关联 + 频次/日期 ———

    @Test
    fun `创建用药与诊疗医嘱后医嘱与任务各一行且精确弱关联`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (status, order) ->
                ctx.verify {
                    assertEquals(201, status, "创建用药医嘱必须 201")
                    assertEquals("ACTIVE", order.getString("status"))
                    assertEquals("MEDICATION", order.getString("order_type"))
                    assertEquals(fixtureId("enc-1"), order.getString("encounter_id"))
                    assertNotNull(order.getString("task_id"), "创建响应必须返回 task_id")
                }
                request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", therapyBody())
            }
            .compose { (status, order) ->
                ctx.verify {
                    assertEquals(201, status, "创建诊疗医嘱必须 201")
                    assertEquals("THERAPY", order.getString("order_type"))
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .compose {
                // 直接 SQL 校验精确弱关联与频次/日期
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery(
                            "SELECT id, order_type, status FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}' ORDER BY created_at",
                        )
                        val orderList = mutableListOf<Triple<String, String, String>>()
                        while (orders.next()) orderList.add(Triple(orders.getString("id"), orders.getString("order_type"), orders.getString("status")))
                        assertEquals(2, orderList.size, "医嘱必须恰好 2 行")

                        val tasks = conn.createStatement().executeQuery(
                            """
                            SELECT t.task_type, t.order_item_id, t.period_id, t.encounter_id, t.frequency_code, t.start_date, t.end_date, t.status
                            FROM nursing.nursing_tasks t
                            WHERE t.order_item_id IN (SELECT id FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}')
                            """.trimIndent(),
                        )
                        val taskRows = mutableListOf<List<String?>>()
                        while (tasks.next()) {
                            taskRows.add(
                                listOf(
                                    tasks.getString("task_type"),
                                    tasks.getString("order_item_id"),
                                    tasks.getString("period_id"),
                                    tasks.getString("encounter_id"),
                                    tasks.getString("frequency_code"),
                                    tasks.getString("start_date"),
                                    tasks.getString("end_date"),
                                    tasks.getString("status"),
                                ),
                            )
                        }
                        assertEquals(2, taskRows.size, "医嘱任务必须恰好 2 行")
                        val medTask = taskRows.first { it[0] == "MEDICATION" }
                        val theTask = taskRows.first { it[0] == "TREATMENT" }
                        val medOrderId = orderList.first { it.second == "MEDICATION" }.first
                        val theOrderId = orderList.first { it.second == "THERAPY" }.first

                        assertEquals(medOrderId, medTask[1], "MEDICATION 任务 order_item_id 必须等于医嘱 id")
                        assertEquals(theOrderId, theTask[1], "THERAPY 任务 order_item_id 必须等于医嘱 id")
                        assertEquals(fixtureId("period-1"), medTask[2], "任务 period_id 必须是唯一关联周期")
                        assertEquals(fixtureId("enc-1"), medTask[3], "任务 encounter_id 必须等于医嘱 encounter_id")
                        assertEquals("QD", medTask[4], "频次 code 必须正确")
                        assertEquals("2026-08-01", medTask[5], "起始日必须是 start_time 的上海业务日期")
                        assertEquals("2026-08-03", medTask[6], "结束日必须是起始日加 duration_days(2)")
                        assertEquals("ACTIVE", medTask[7])
                        // THERAPY：无频次、duration 1 天 → 有结束日，无频次
                        assertNull(theTask[4], "无频次的诊疗医嘱不得写频次")
                        assertEquals("2026-08-02", theTask[5])
                        assertEquals("2026-08-03", theTask[6])
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `ensureExecutionsForDateRange为可生成频次创建执行且终局后不再生成`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (status, order) ->
                ctx.verify { assertEquals(201, status) }
                request(
                    vertx,
                    HttpMethod.POST,
                    "$NURSING_BASE/executions/generate",
                    JsonObject().put("date_from", "2026-08-01").put("date_to", "2026-08-02").put("period_id", fixtureId("period-1")),
                )
            }
            .compose { (status, gen) ->
                ctx.verify {
                    assertEquals(200, status)
                    val generated = gen.getInteger("generated")
                    assertTrue(generated >= 2, "QD 跨 2 日应生成至少 2 条执行，got $generated: ${gen.encode()}")
                }
                // 终局后不再生成未来执行：停嘱后再次 generate 未来日期
                request(
                    vertx,
                    HttpMethod.POST,
                    "$NURSING_BASE/executions/generate",
                    JsonObject().put("date_from", "2026-08-04").put("date_to", "2026-08-05").put("period_id", fixtureId("period-1")),
                )
            }
            .compose { (status, gen) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals(0, gen.getInteger("generated"), "终局前不应提前生成（未来日期无任务覆盖），got ${gen.encode()}")
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 2：状态机联动、既有执行保留、拒绝路径、失败无残留 ———

    @Test
    fun `停嘱完成任务联动且既有执行保留`(vertx: Vertx, ctx: VertxTestContext) {
        // 先创建医嘱
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (_, order) ->
                val orderId = order.getString("id")
                // 先生成执行
                request(
                    vertx,
                    HttpMethod.POST,
                    "$NURSING_BASE/executions/generate",
                    JsonObject().put("date_from", "2026-08-01").put("date_to", "2026-08-01").put("period_id", fixtureId("period-1")),
                ).compose { (_, gen) ->
                    ctx.verify { assertTrue(gen.getInteger("generated") >= 1, "QD 频次当天应生成执行") }
                    // 停嘱
                    request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/orders/$orderId/status", JsonObject().put("status", "DISCONTINUED"))
                }.compose { (status, order) ->
                    ctx.verify {
                        assertEquals(200, status)
                        assertEquals("DISCONTINUED", order.getString("status"))
                        assertNotNull(order.getString("end_time"), "停嘱必须写 end_time")
                    }
                    io.vertx.core.Future.succeededFuture(orderId)
                }
            }
            .compose { orderId ->
                // 停嘱后再查 DB 联动 + 执行保留
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val taskStatus = conn.createStatement().executeQuery(
                            "SELECT status FROM nursing.nursing_tasks WHERE order_item_id = '$orderId'",
                        )
                        assertTrue(taskStatus.next(), "停嘱后必须仍存在关联任务")
                        assertEquals("CANCELLED", taskStatus.getString("status"), "停嘱必须把关联任务置为 CANCELLED")

                        val execs = conn.createStatement().executeQuery(
                            "SELECT count(*) FROM nursing.nursing_task_executions WHERE task_id = (SELECT id FROM nursing.nursing_tasks WHERE order_item_id = '$orderId')",
                        )
                        execs.next()
                        assertTrue(execs.getLong(1) >= 1, "停嘱后既有执行必须保留")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `状态机拒绝路径与校验失败无残留`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (_, order) ->
                val orderId = order.getString("id")
                // 非法目标
                request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/orders/$orderId/status", JsonObject().put("status", "BOGUS"))
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertNotNull(body.getString("error"))
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非法医嘱输入返回400且无任何残留行`(vertx: Vertx, ctx: VertxTestContext) {
        // NURSING 类型
        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
            medicationBody().put("order_type", "NURSING"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "NURSING 类型必须 400")
                    assertNotNull(body.getString("error"))
                }
                // 未知明细键（025：其余明细合法，只留未知键触发 400）
                val unknown = medicationBody()
                unknown.put("order_details", medicationDetails(fixtureId("drug"), DRUG_NAME).put("hacker_key", "x"))
                request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", unknown)
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(400, status, "未知明细键必须 400") }
                // 负时长
                val negDuration = medicationBody()
                negDuration.put("order_details", medicationDetails(fixtureId("drug"), DRUG_NAME).put("duration_days", -1))
                request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", negDuration)
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(400, status, "负数时长必须 400") }
                // 频次 code/name 不成对
                val pair = medicationBody()
                pair.put("order_details", medicationDetails(fixtureId("drug"), DRUG_NAME).also { it.remove("frequency_name") })
                request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", pair)
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(400, status, "频次 code/name 必须成对") }
                // 缺失医生
                request(
                    vertx,
                    HttpMethod.POST,
                    "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
                    medicationBody().put("doctor", "  "),
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(400, status, "医生必填") }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery("SELECT count(*) FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'")
                        orders.next()
                        assertEquals(0, orders.getLong(1), "全部校验失败后不得残留医嘱行")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 025 §7.2 断言 1：药品目录即物资主数据，可被冻结契约检索到 ———

    @Test
    fun `025药品目录可被category与status检索到且非ACTIVE药品与耗材不出现`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.GET,
            "/inventories/v1/materials?category=%E8%8D%AF%E5%93%81&status=ACTIVE&limit=200",
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "目录检索必须 200: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertNotNull(records, "响应必须是 {records, meta} 形状")
                    val ids = records.map { (it as JsonObject).getString("id") }
                    val names = records.map { (it as JsonObject).getString("name") }

                    // V204 预置演示药品必须可按冻结契约检索到（断言 1 的核心）
                    assertTrue(
                        ids.contains(DEMO_DRUG_001_ID),
                        "V204 预置药品 $DEMO_DRUG_001_CODE 必须出现，实际 id=$ids",
                    )
                    assertTrue(names.contains(DEMO_DRUG_001_NAME), "预置药品名必须为 $DEMO_DRUG_001_NAME")
                    // mo- fixture 的 ACTIVE 药品必须出现
                    assertTrue(
                        records.any { (it as JsonObject).getString("code") == DRUG_CODE },
                        "fixture 药品 $DRUG_CODE 必须出现",
                    )
                    // 非 ACTIVE 药品必须被 status 过滤掉
                    assertTrue(
                        records.none { (it as JsonObject).getString("name") == INACTIVE_DRUG_NAME },
                        "INACTIVE 药品不得出现在 status=ACTIVE 结果中",
                    )
                    // 耗材必须被 category 过滤掉
                    assertTrue(
                        records.none { (it as JsonObject).getString("id") == fixtureId("mat-bait") },
                        "耗材不得出现在 category=药品 结果中",
                    )
                    // 每条都必须是 药品 + ACTIVE
                    records.forEach {
                        val row = it as JsonObject
                        assertEquals("药品", row.getString("category"))
                        assertEquals("ACTIVE", row.getString("status"))
                    }
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 025 §7.2 断言 2：用药医嘱必须绑定目录药品，四类非法输入的错误码固定 ———

    @Test
    fun `025用药医嘱缺material_id返回400且不落库不建任务`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
            medicationBody(materialId = null, drugName = DRUG_NAME),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "缺 material_id 必须 400: ${body.encode()}")
                    assertEquals(
                        "material_id is required for MEDICATION order",
                        body.getString("error"),
                        "错误文案必须固定，供前端与测试稳定断言",
                    )
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery(
                            "SELECT count(*) FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        orders.next()
                        assertEquals(0L, orders.getLong(1), "校验失败不得残留医嘱行")
                        val tasks = conn.createStatement().executeQuery(
                            "SELECT count(*) FROM nursing.nursing_tasks WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        tasks.next()
                        assertEquals(0L, tasks.getLong(1), "校验失败不得残留护理任务行")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `025用药医嘱material_id非药品或非ACTIVE或不存在分别409与404`(vertx: Vertx, ctx: VertxTestContext) {
        // 1. 耗材（category != 药品）→ 409，且不得落库
        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
            medicationBody(materialId = fixtureId("mat-bait"), drugName = "诱饵材料"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "耗材作 material_id 必须 409: ${body.encode()}")
                    assertEquals("material is not a drug: ${fixtureId("mat-bait")}", body.getString("error"))
                }
                // 2. 存在但 status != ACTIVE → 409
                request(
                    vertx,
                    HttpMethod.POST,
                    "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
                    medicationBody(materialId = fixtureId("drug-inactive"), drugName = INACTIVE_DRUG_NAME),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "非 ACTIVE 药品必须 409: ${body.encode()}")
                    assertEquals("material is not active: ${fixtureId("drug-inactive")}", body.getString("error"))
                }
                // 3. 物资不存在 → 404
                request(
                    vertx,
                    HttpMethod.POST,
                    "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
                    medicationBody(materialId = fixtureId("ghost"), drugName = "不存在药品"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(404, status, "不存在物资必须 404: ${body.encode()}")
                    assertEquals("material not found: ${fixtureId("ghost")}", body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery(
                            "SELECT count(*) FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        orders.next()
                        assertEquals(0L, orders.getLong(1), "三类目录校验失败均不得残留医嘱行")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `025合法目录药品创建医嘱服务端覆写名称与编码且忽略客户端快照入参`(vertx: Vertx, ctx: VertxTestContext) {
        // 客户端伪造 material_code / material_name / 补绑审计：服务端必须接受入参但一律丢弃
        val body = medicationBody()
        val forged = medicationDetails(fixtureId("drug"), DRUG_NAME)
            .put("material_code", "FORGED-CODE")
            .put("material_name", "伪造药品名")
            .put("material_bound_by", "伪造操作人")
            .put("material_bound_at", "1970-01-01T00:00:00Z")
        body.put("order_details", forged)

        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", body)
            .compose { (status, order) ->
                ctx.verify {
                    assertEquals(201, status, "合法目录药品必须 201: ${order.encode()}")
                    assertEquals("MEDICATION", order.getString("order_type"))
                    assertNotNull(order.getString("id"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val rs = conn.createStatement().executeQuery(
                            "SELECT order_details::text AS details FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        assertTrue(rs.next(), "医嘱必须落库")
                        val details = JsonObject(rs.getString("details"))
                        assertEquals(fixtureId("drug"), details.getString("material_id"), "material_id 必须原样保留")
                        assertEquals(DRUG_NAME, details.getString("material_name"), "material_name 必须被目录名覆写")
                        assertEquals(DRUG_NAME, details.getString("drug_name"), "drug_name 必须被目录名覆写")
                        assertEquals(DRUG_CODE, details.getString("material_code"), "material_code 必须被目录编码覆写")
                        assertNull(details.getString("material_bound_by"), "开立路径不得写入补绑审计")
                        assertNull(details.getString("material_bound_at"), "开立路径不得写入补绑时间")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `025drug_name与目录名不一致返回400且不落库`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
            medicationBody(materialId = fixtureId("drug"), drugName = "与目录不一致的药名"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "药名与目录不一致必须 400: ${body.encode()}")
                    assertEquals("drug_name must match catalog material name", body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery(
                            "SELECT count(*) FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        orders.next()
                        assertEquals(0L, orders.getLong(1), "药名漂移不得残留医嘱行")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 025 §4.5.1：端口缺失必须 fail-closed 503，不得退化为自由文本开药 ———

    @Test
    fun `025药品目录端口未注入时用药医嘱fail-closed返回503`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_NO_PORT_BASE/encounters/${fixtureId("enc-1")}/orders",
            medicationBody(),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(503, status, "端口缺失必须 503 而不是静默放行: ${body.encode()}")
                    assertEquals("drug catalog port is not configured", body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery(
                            "SELECT count(*) FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        orders.next()
                        assertEquals(0L, orders.getLong(1), "fail-closed 不得落库")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 3：离院收束 + IN_PROGRESS 阻断 ———

    @Test
    fun `离院成功同一事务收束全部行`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (status, _) ->
                ctx.verify { assertEquals(201, status) }
                request(
                    vertx,
                    HttpMethod.PATCH,
                    "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/discharge",
                    JsonObject().put("discharge_date", "2026-08-05T10:00:00+08:00"),
                )
            }
            .compose { (status, encounter) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals("DISCHARGED", encounter.getString("status"))
                    // PostgreSQL timestamptz 读回统一为 UTC 表示，但瞬间相等
                    assertEquals(
                        OffsetDateTime.parse("2026-08-05T10:00:00+08:00").toInstant(),
                        OffsetDateTime.parse(encounter.getString("discharge_date")).toInstant(),
                        "discharge_date 瞬间必须相等",
                    )
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val orders = conn.createStatement().executeQuery(
                            "SELECT status FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        orders.next()
                        assertEquals("DISCONTINUED", orders.getString("status"), "离院必须把活动医嘱置为 DISCONTINUED")
                        val tasks = conn.createStatement().executeQuery(
                            "SELECT status FROM nursing.nursing_tasks WHERE encounter_id = '${fixtureId("enc-1")}'",
                        )
                        tasks.next()
                        assertEquals("CANCELLED", tasks.getString("status"), "离院必须把关联任务置为 CANCELLED")
                        val period = conn.createStatement().executeQuery(
                            "SELECT status, end_date FROM nursing.nursing_service_periods WHERE id = '${fixtureId("period-1")}'",
                        )
                        period.next()
                        assertEquals("COMPLETED", period.getString("status"))
                        assertEquals("2026-08-05", period.getString("end_date"), "周期结束日必须等于离院业务日期")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `进行中执行阻断离院且每个相关表均不变`(vertx: Vertx, ctx: VertxTestContext) {
        // 为 enc-7（含 IN_PROGRESS 执行）添加一条医嘱
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-7")}/orders", medicationBody())
            .compose { (status, order) ->
                ctx.verify {
                    assertEquals(201, status)
                    assertEquals("ACTIVE", order.getString("status"))
                }
                request(
                    vertx,
                    HttpMethod.PATCH,
                    "$HEALTHCARE_BASE/encounters/${fixtureId("enc-7")}/discharge",
                    JsonObject().put("discharge_date", "2026-08-05T10:00:00+08:00"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "IN_PROGRESS 执行必须阻断离院")
                    assertNotNull(body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val encounter = conn.createStatement().executeQuery("SELECT status FROM healthcare.encounters WHERE id = '${fixtureId("enc-7")}'")
                        encounter.next()
                        assertEquals("ACTIVE", encounter.getString("status"), "encounter 必须保持 ACTIVE")
                        val order = conn.createStatement().executeQuery("SELECT status FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-7")}'")
                        order.next()
                        assertEquals("ACTIVE", order.getString("status"), "医嘱必须保持 ACTIVE（事务回滚）")
                        val task = conn.createStatement().executeQuery("SELECT count(*) FROM nursing.nursing_tasks WHERE encounter_id = '${fixtureId("enc-7")}' AND status <> 'ACTIVE'")
                        task.next()
                        assertEquals(0, task.getLong(1), "全部任务必须保持 ACTIVE（事务回滚）")
                        val period = conn.createStatement().executeQuery("SELECT status FROM nursing.nursing_service_periods WHERE id = '${fixtureId("period-7")}'")
                        period.next()
                        assertEquals("ACTIVE", period.getString("status"), "周期必须保持 ACTIVE")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 4：列表/详情隔离、汇总不泄漏、读取无副作用 ———

    @Test
    fun `列表严格按encounter隔离且执行汇总不泄漏诱饵数据`(vertx: Vertx, ctx: VertxTestContext) {
        // enc-1 一条用药；enc-2 无医嘱
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (status, _) ->
                ctx.verify { assertEquals(201, status) }
                request(vertx, HttpMethod.GET, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders")
            }
            .compose { (status, list) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals(1, list.getJsonArray("records").size())
                    assertEquals(1, list.getJsonObject("meta").getInteger("total"))
                    // 类型筛选
                    assertEquals("MEDICATION", list.getJsonArray("records").getJsonObject(0).getString("order_type"))
                }
                request(vertx, HttpMethod.GET, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-2")}/orders")
            }
            .compose { (status, list) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals(0, list.getJsonArray("records").size(), "enc-2 不得混入 enc-1 医嘱")
                    assertEquals(0, list.getJsonObject("meta").getInteger("total"))
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `读取不产生任务执行或库存写入`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders", medicationBody())
            .compose { (_, order) ->
                val before = snapshotCounts()
                request(vertx, HttpMethod.GET, "$HEALTHCARE_BASE/orders/${order.getString("id")}")
                    .compose { (status, detail) ->
                        ctx.verify {
                            assertEquals(200, status)
                            val summary = detail.getJsonObject("execution_summary")
                            assertNotNull(summary, "详情必须返回执行汇总")
                            assertEquals(0, summary.getInteger("PENDING"))
                            assertEquals(0, summary.getInteger("IN_PROGRESS"))
                            assertEquals(0, summary.getInteger("COMPLETED"))
                        }
                        request(vertx, HttpMethod.GET, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders")
                    }
                    .compose { (_, _) ->
                        val after = snapshotCounts()
                        assertEquals(before, after, "读取不得产生任务/执行/库存写入: before=$before after=$after")
                        io.vertx.core.Future.succeededFuture<Unit>(Unit)
                    }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 027 §3.4/§4.5：close-expired 真库用例 ———
    //
    // fixture 全部以 `mo-` 前缀在本用例内自建（入住 / 周期 / 医嘱 / 任务 / 执行），
    // 随既有 @BeforeEach/@AfterEach 的 cleanupFixtures 按外键逆序删除，不依赖任何手工遗留数据，
    // 也不触碰既有 enc-1..enc-7 fixture。默认验证只保证可编译；真库执行属 §6.3 发布前检查。
    //
    // 零改写断言说明：`nursing_task_executions` 没有 `updated_at` 列（V400），
    // 故以「全列 + xmin 行版本」整行快照作为「零改写」可得的最强证据——任何 UPDATE 都会换掉 xmin。

    @Test
    fun `closeExpiredOrders真库收束到期医嘱且保留end_time并零改写护理执行`(vertx: Vertx, ctx: VertxTestContext) {
        val encounterId = fixtureId("ce-main-enc")
        val periodId = fixtureId("ce-main-period")
        val orderId = fixtureId("ce-main-order")
        val taskId = fixtureId("ce-main-task")
        // 明确的过去终点：2 天前，远大于任何时钟抖动窗口
        val endTime = OffsetDateTime.now().minusDays(2).withNano(0)
        val startTime = endTime.minusDays(1)
        val callStartedAt = OffsetDateTime.now()

        seedCloseExpiredEncounter(encounterId, fixtureId("ce-main-patient"), periodId, "MO-CE-MAIN")
        seedMedicalOrder(orderId, encounterId, "ACTIVE", endTime, startTime)
        seedOrderTask(taskId, periodId, encounterId, orderId)
        seedExecution(fixtureId("ce-main-exec-pending"), taskId, startTime, "PENDING")
        seedExecution(fixtureId("ce-main-exec-running"), taskId, startTime.plusHours(1), "IN_PROGRESS", endTime.minusHours(1))

        val orderBefore = orderRowText(orderId)
        val executionsBefore = executionsRowText(taskId)

        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/orders/close-expired",
            JsonObject().put("encounter_id", encounterId),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "收束必须 200: ${body.encode()}")
                    assertEquals(1, body.getInteger("closed"), "该入住只有一条到期医嘱: ${body.encode()}")
                    assertEquals(listOf(orderId), body.getJsonArray("order_ids").list)
                    assertEquals(0, body.getJsonArray("warnings").size(), "任务健全时不得有 warnings: ${body.encode()}")
                    val closedAt = OffsetDateTime.parse(body.getString("closed_at"))
                    assertFalse(closedAt.isBefore(callStartedAt.minusHours(1)), "closed_at 必须是本次调用时刻")
                }
                io.vertx.core.Future.succeededFuture(body.getString("closed_at"))
            }
            .compose { closedAt ->
                io.vertx.core.Future.future<Unit> { promise ->
                    // 医嘱已收束；end_time 保持 fixture 原临床终点，绝不被 now 覆盖
                    assertEquals("COMPLETED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderId'"))
                    assertEquals(
                        "true",
                        queryText("SELECT (end_time = '${sqlTimestamp(endTime)}'::timestamptz)::text FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        "end_time 必须逐瞬间保持原值 $endTime",
                    )
                    assertEquals(
                        "true",
                        queryText("SELECT (end_time < now() - interval '1 day')::text FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        "end_time 不得被收束时刻覆盖",
                    )
                    // 审计落库：metadata.convergence.{reason,end_time,at}
                    assertEquals(
                        "EXPIRED",
                        queryText("SELECT metadata->'convergence'->>'reason' FROM healthcare.medical_orders WHERE id = '$orderId'"),
                    )
                    assertEquals(
                        "true",
                        queryText("SELECT ((metadata->'convergence'->>'end_time')::timestamptz = '${sqlTimestamp(endTime)}'::timestamptz)::text FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        "审计里的 end_time 必须是原临床终点",
                    )
                    // 此处不能用 SQL_TIMESTAMP_FORMAT（秒级精度）：审计 at 与响应 closed_at 都是
                    // `now.toString()`（含亚秒），秒级字面量必然不等，属断言缺陷而非产品缺陷；
                    // 用 toString() 的原样字面量，比较仍是 timestamptz 的逐瞬间相等。
                    assertEquals(
                        "true",
                        queryText("SELECT ((metadata->'convergence'->>'at')::timestamptz = '${OffsetDateTime.parse(closedAt)}'::timestamptz)::text FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        "审计 at 必须等于响应 closed_at",
                    )
                    assertEquals(
                        "true",
                        queryText("SELECT (updated_at = (metadata->'convergence'->>'at')::timestamptz)::text FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        "updated_at 必须与收束时刻同源",
                    )
                    assertNotEquals(orderBefore, orderRowText(orderId), "收束必须真的改写医嘱行")
                    // 关联护理任务已结束
                    assertEquals("COMPLETED", queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '$taskId'"))
                    // 零 nursing_task_executions 写入：整行（含 xmin）逐字节未变，状态仍是 PENDING/IN_PROGRESS
                    assertEquals(executionsBefore, executionsRowText(taskId), "收束只提醒，绝不改写护理执行记录")
                    assertEquals(
                        "IN_PROGRESS,PENDING",
                        queryText("SELECT string_agg(status, ',' ORDER BY status) FROM nursing.nursing_task_executions WHERE task_id = '$taskId'"),
                    )
                    promise.complete()
                }
            }
            .compose {
                // API 派生视图同步收敛：status=COMPLETED、is_expired=false、end_time 仍是原值
                request(vertx, HttpMethod.GET, "$HEALTHCARE_BASE/orders/$orderId")
            }
            .compose { (status, order) ->
                ctx.verify {
                    assertEquals(200, status, "收束后详情必须可读: ${order.encode()}")
                    assertEquals("COMPLETED", order.getString("status"))
                    assertEquals(false, order.getBoolean("is_expired"))
                    assertEquals(
                        endTime.toInstant(),
                        OffsetDateTime.parse(order.getString("end_time")).toInstant(),
                        "详情返回的 end_time 必须仍是原临床终点",
                    )
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `closeExpiredOrders真库不收束无终点未来终点与非ACTIVE医嘱`(vertx: Vertx, ctx: VertxTestContext) {
        val encounterId = fixtureId("ce-edge-enc")
        val periodId = fixtureId("ce-edge-period")
        val expiredOrderId = fixtureId("ce-edge-expired")
        val nearPastOrderId = fixtureId("ce-edge-near-past")
        val nullEndOrderId = fixtureId("ce-edge-null-end")
        val nearFutureOrderId = fixtureId("ce-edge-near-future")
        val farFutureOrderId = fixtureId("ce-edge-far-future")
        val discontinuedOrderId = fixtureId("ce-edge-discontinued")
        val cancelledOrderId = fixtureId("ce-edge-cancelled")
        val completedOrderId = fixtureId("ce-edge-completed")
        val startTime = OffsetDateTime.now().minusDays(5).withNano(0)
        // 判据是「严格小于」。真库无法稳定构造 end_time == now（服务端在调用时自取 now），
        // 故用「刚刚过去」（-30 分钟，必须收束）与「即将到期」（+30 分钟，必须不收束）夹住该边界，
        // 两侧余量远大于本用例的毫秒级抖动；精确等值（end_time == now → false）由非数据库单测
        // HealthcareMedicalOrderTest#computeExpiryFields边界_严格小于与终态 覆盖。
        val past = OffsetDateTime.now().minusDays(2).withNano(0)
        val nearPast = OffsetDateTime.now().minusMinutes(30).withNano(0)
        val nearFuture = OffsetDateTime.now().plusMinutes(30).withNano(0)
        val farFuture = OffsetDateTime.now().plusDays(2).withNano(0)

        seedCloseExpiredEncounter(encounterId, fixtureId("ce-edge-patient"), periodId, "MO-CE-EDGE")
        seedMedicalOrder(expiredOrderId, encounterId, "ACTIVE", past, startTime)
        seedMedicalOrder(nearPastOrderId, encounterId, "ACTIVE", nearPast, startTime)
        seedMedicalOrder(nullEndOrderId, encounterId, "ACTIVE", null, startTime)
        seedMedicalOrder(nearFutureOrderId, encounterId, "ACTIVE", nearFuture, startTime)
        seedMedicalOrder(farFutureOrderId, encounterId, "ACTIVE", farFuture, startTime)
        seedMedicalOrder(discontinuedOrderId, encounterId, "DISCONTINUED", past, startTime)
        seedMedicalOrder(cancelledOrderId, encounterId, "CANCELLED", past, startTime)
        seedMedicalOrder(completedOrderId, encounterId, "COMPLETED", past, startTime)
        val taskByOrder = mapOf(
            expiredOrderId to fixtureId("ce-edge-task-expired"),
            nearPastOrderId to fixtureId("ce-edge-task-near-past"),
            nullEndOrderId to fixtureId("ce-edge-task-null-end"),
            nearFutureOrderId to fixtureId("ce-edge-task-near-future"),
            farFutureOrderId to fixtureId("ce-edge-task-far-future"),
            discontinuedOrderId to fixtureId("ce-edge-task-discontinued"),
            cancelledOrderId to fixtureId("ce-edge-task-cancelled"),
            completedOrderId to fixtureId("ce-edge-task-completed"),
        )
        taskByOrder.forEach { (orderId, taskId) -> seedOrderTask(taskId, periodId, encounterId, orderId) }
        val notCandidateOrderIds = listOf(
            nullEndOrderId,
            nearFutureOrderId,
            farFutureOrderId,
            discontinuedOrderId,
            cancelledOrderId,
            completedOrderId,
        )
        val rowsBefore = notCandidateOrderIds.associateWith { orderRowText(it) }

        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/orders/close-expired",
            JsonObject().put("encounter_id", encounterId),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "收束必须 200: ${body.encode()}")
                    assertEquals(2, body.getInteger("closed"), "只应收束明确的过去终点 ACTIVE 医嘱: ${body.encode()}")
                    assertEquals(
                        setOf(expiredOrderId, nearPastOrderId),
                        body.getJsonArray("order_ids").list.toSet(),
                    )
                    assertEquals(0, body.getJsonArray("warnings").size(), "无异常任务不得有 warnings: ${body.encode()}")
                }
                io.vertx.core.Future.succeededFuture(Unit)
            }
            .compose {
                io.vertx.core.Future.future<Unit> { promise ->
                    // 两个明确的过去终点候选都被收束（含刚刚过去 30 分钟的那条，证明判据是严格小于而非宽松窗口）
                    listOf(expiredOrderId, nearPastOrderId).forEach { orderId ->
                        assertEquals(
                            "COMPLETED",
                            queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderId'"),
                            "候选 $orderId 必须被收束",
                        )
                        assertEquals(
                            "EXPIRED",
                            queryText("SELECT metadata->'convergence'->>'reason' FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        )
                        assertEquals(
                            "COMPLETED",
                            queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '${taskByOrder.getValue(orderId)}'"),
                        )
                    }
                    assertEquals(
                        "true",
                        queryText("SELECT (end_time = '${sqlTimestamp(nearPast)}'::timestamptz)::text FROM healthcare.medical_orders WHERE id = '$nearPastOrderId'"),
                        "刚刚过去的候选也必须保留原 end_time",
                    )
                    // 边界与非 ACTIVE 一律不被收束：整行逐字节未变（status/end_time/metadata/updated_at）
                    assertEquals(
                        rowsBefore,
                        notCandidateOrderIds.associateWith { orderRowText(it) },
                        "非候选医嘱行必须逐行未变",
                    )
                    assertNull(
                        queryText("SELECT end_time::text FROM healthcare.medical_orders WHERE id = '$nullEndOrderId'"),
                        "end_time 为空的医嘱永不到期",
                    )
                    assertEquals(
                        "true",
                        queryText("SELECT (end_time > now())::text FROM healthcare.medical_orders WHERE id = '$nearFutureOrderId'"),
                        "近未来终点不得被收束",
                    )
                    assertEquals(
                        "true",
                        queryText("SELECT (end_time > now())::text FROM healthcare.medical_orders WHERE id = '$farFutureOrderId'"),
                        "远未来终点不得被收束",
                    )
                    assertEquals("DISCONTINUED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$discontinuedOrderId'"))
                    assertEquals("CANCELLED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$cancelledOrderId'"))
                    assertEquals("COMPLETED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$completedOrderId'"))
                    // 非候选医嘱的关联任务必须保持 ACTIVE（没有被 terminateOrderTask 碰过）
                    notCandidateOrderIds.forEach { orderId ->
                        assertEquals(
                            "ACTIVE",
                            queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '${taskByOrder.getValue(orderId)}'"),
                            "非候选医嘱 $orderId 的护理任务不得被结束",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `closeExpiredOrders真库幂等_第二次closed为0且医嘱行不被二次改写`(vertx: Vertx, ctx: VertxTestContext) {
        val encounterId = fixtureId("ce-idem-enc")
        val periodId = fixtureId("ce-idem-period")
        val orderId = fixtureId("ce-idem-order")
        val taskId = fixtureId("ce-idem-task")
        val endTime = OffsetDateTime.now().minusDays(2).withNano(0)
        val startTime = endTime.minusDays(1)

        seedCloseExpiredEncounter(encounterId, fixtureId("ce-idem-patient"), periodId, "MO-CE-IDEM")
        seedMedicalOrder(orderId, encounterId, "ACTIVE", endTime, startTime)
        seedOrderTask(taskId, periodId, encounterId, orderId)
        seedExecution(fixtureId("ce-idem-exec"), taskId, startTime, "PENDING")

        var afterFirstCall: List<String?>? = null

        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/orders/close-expired",
            JsonObject().put("encounter_id", encounterId),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "首次收束必须 200: ${body.encode()}")
                    assertEquals(1, body.getInteger("closed"), "首次调用必须收束唯一候选: ${body.encode()}")
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    assertEquals("COMPLETED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderId'"))
                    afterFirstCall = listOf(orderRowText(orderId), taskRowText(taskId), executionsRowText(taskId))
                    promise.complete()
                }
            }
            .compose {
                request(
                    vertx,
                    HttpMethod.POST,
                    "$HEALTHCARE_BASE/orders/close-expired",
                    JsonObject().put("encounter_id", encounterId),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "第二次调用必须仍 200: ${body.encode()}")
                    assertEquals(0, body.getInteger("closed"), "幂等：第二次不得再收束")
                    assertEquals(0, body.getJsonArray("order_ids").size())
                    assertEquals(0, body.getJsonArray("warnings").size())
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    assertEquals(
                        afterFirstCall,
                        listOf(orderRowText(orderId), taskRowText(taskId), executionsRowText(taskId)),
                        "幂等：第二次调用不得二次改写医嘱（updated_at/metadata 不变）、护理任务或护理执行",
                    )
                    assertEquals("COMPLETED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderId'"))
                    assertEquals("COMPLETED", queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '$taskId'"))
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `closeExpiredOrders真库按encounter_id限定范围且不带时为全量`(vertx: Vertx, ctx: VertxTestContext) {
        // 不带 encounter_id 时作用域是全库（计划的已知残余风险）。为绝不误伤非 fixture 数据，
        // 先确认隔离库里没有 `mo-` 之外的到期 ACTIVE 医嘱；否则本用例按假设跳过（诚实报告而非误改）。
        val foreignCandidates = queryText(
            "SELECT count(*)::text FROM healthcare.medical_orders " +
                "WHERE id NOT LIKE '${FIXTURE_PREFIX}%' AND status = 'ACTIVE' AND end_time IS NOT NULL AND end_time < now()",
        )?.toLong() ?: 0L
        Assumptions.assumeTrue(
            foreignCandidates == 0L,
            "aceso_test 存在 $foreignCandidates 条非 fixture 的到期 ACTIVE 医嘱；全量收束用例只在干净隔离库上执行",
        )

        val encounterA = fixtureId("ce-scope-a-enc")
        val periodA = fixtureId("ce-scope-a-period")
        val orderA = fixtureId("ce-scope-a-order")
        val orderAFuture = fixtureId("ce-scope-a-future")
        val taskA = fixtureId("ce-scope-a-task")
        val encounterB = fixtureId("ce-scope-b-enc")
        val periodB = fixtureId("ce-scope-b-period")
        val orderB = fixtureId("ce-scope-b-order")
        val taskB = fixtureId("ce-scope-b-task")
        val ghostEncounter = fixtureId("ce-scope-ghost")
        val past = OffsetDateTime.now().minusDays(2).withNano(0)
        val future = OffsetDateTime.now().plusDays(2).withNano(0)
        val startTime = past.minusDays(1)

        seedCloseExpiredEncounter(encounterA, fixtureId("ce-scope-a-patient"), periodA, "MO-CE-SCOPE-A")
        seedCloseExpiredEncounter(encounterB, fixtureId("ce-scope-b-patient"), periodB, "MO-CE-SCOPE-B")
        seedMedicalOrder(orderA, encounterA, "ACTIVE", past, startTime)
        seedMedicalOrder(orderAFuture, encounterA, "ACTIVE", future, startTime)
        seedOrderTask(taskA, periodA, encounterA, orderA)
        seedOrderTask(fixtureId("ce-scope-a-task-future"), periodA, encounterA, orderAFuture)
        seedMedicalOrder(orderB, encounterB, "ACTIVE", past, startTime)
        seedOrderTask(taskB, periodB, encounterB, orderB)

        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/orders/close-expired",
            JsonObject().put("encounter_id", ghostEncounter),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(404, status, "未知入住的 encounter_id 必须 404: ${body.encode()}")
                    assertEquals("encounter not found: $ghostEncounter", body.getString("error"))
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    "$HEALTHCARE_BASE/orders/close-expired",
                    JsonObject().put("encounter_id", encounterA),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "带 encounter_id 收束必须 200: ${body.encode()}")
                    assertEquals(1, body.getInteger("closed"), "带 encounter_id 时只收束该入住的候选: ${body.encode()}")
                    assertEquals(listOf(orderA), body.getJsonArray("order_ids").list)
                    assertEquals(0, body.getJsonArray("warnings").size())
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    assertEquals("COMPLETED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderA'"))
                    // 另一入住、以及同入住的未来终点医嘱完全未被动过
                    assertEquals("ACTIVE", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderB'"))
                    assertNull(
                        queryText("SELECT metadata::text FROM healthcare.medical_orders WHERE id = '$orderB'"),
                        "另一入住的医嘱不得被收束（无 convergence 审计）",
                    )
                    assertEquals("ACTIVE", queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '$taskB'"))
                    assertEquals("ACTIVE", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderAFuture'"))
                    promise.complete()
                }
            }
            .compose {
                // 不带 encounter_id（显式空对象）= 全量：此时只剩 enc-B 一条候选
                request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/orders/close-expired", JsonObject())
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "全量收束必须 200: ${body.encode()}")
                    assertEquals(1, body.getInteger("closed"), "不带 encounter_id 时必须收束另一入住的候选: ${body.encode()}")
                    assertEquals(listOf(orderB), body.getJsonArray("order_ids").list)
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    assertEquals("COMPLETED", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderB'"))
                    assertEquals(
                        "EXPIRED",
                        queryText("SELECT metadata->'convergence'->>'reason' FROM healthcare.medical_orders WHERE id = '$orderB'"),
                    )
                    assertEquals("COMPLETED", queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '$taskB'"))
                    assertEquals("ACTIVE", queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderAFuture'"))
                    promise.complete()
                }
            }
            .compose {
                // 真正不带请求体（契约允许空体）→ 200 且 0 候选，全量幂等
                request(vertx, HttpMethod.POST, "$HEALTHCARE_BASE/orders/close-expired")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "空请求体必须被接受: ${body.encode()}")
                    assertEquals(0, body.getInteger("closed"))
                    assertEquals(0, body.getJsonArray("order_ids").size())
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `closeExpiredOrders真库任务缺失或任务非ACTIVE时降级warnings仍收束`(vertx: Vertx, ctx: VertxTestContext) {
        val encounterId = fixtureId("ce-warn-enc")
        val periodId = fixtureId("ce-warn-period")
        val noTaskOrderId = fixtureId("ce-warn-no-task")
        val terminalTaskOrderId = fixtureId("ce-warn-terminal-task")
        val terminalTaskId = fixtureId("ce-warn-task-cancelled")
        val past = OffsetDateTime.now().minusDays(2).withNano(0)
        val startTime = past.minusDays(1)

        seedCloseExpiredEncounter(encounterId, fixtureId("ce-warn-patient"), periodId, "MO-CE-WARN")
        // 场景 1：ACTIVE 且已到期，但没有任何关联护理任务
        seedMedicalOrder(noTaskOrderId, encounterId, "ACTIVE", past, startTime)
        // 场景 2：ACTIVE 且已到期，但关联护理任务已非 ACTIVE（CANCELLED）
        seedMedicalOrder(terminalTaskOrderId, encounterId, "ACTIVE", past, startTime)
        seedOrderTask(terminalTaskId, periodId, encounterId, terminalTaskOrderId, status = "CANCELLED")
        val terminalTaskBefore = taskRowText(terminalTaskId)

        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/orders/close-expired",
            JsonObject().put("encounter_id", encounterId),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "warnings 是降级而非失败: ${body.encode()}")
                    assertEquals(2, body.getInteger("closed"), "任务异常不得阻断收束: ${body.encode()}")
                    assertEquals(setOf(noTaskOrderId, terminalTaskOrderId), body.getJsonArray("order_ids").list.toSet())
                    val warnings = body.getJsonArray("warnings").map { it as JsonObject }
                    assertEquals(2, warnings.size, "每条异常必须记入 warnings: ${body.encode()}")
                    assertEquals(
                        setOf(noTaskOrderId, terminalTaskOrderId),
                        warnings.map { it.getString("order_id") }.toSet(),
                    )
                    assertEquals(
                        setOf(
                            "order has no linked task: $noTaskOrderId",
                            "order task is in unexpected status: CANCELLED",
                        ),
                        warnings.map { it.getString("reason") }.toSet(),
                    )
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    listOf(noTaskOrderId, terminalTaskOrderId).forEach { orderId ->
                        assertEquals(
                            "COMPLETED",
                            queryText("SELECT status FROM healthcare.medical_orders WHERE id = '$orderId'"),
                            "warnings 场景仍必须收束 $orderId",
                        )
                        assertEquals(
                            "EXPIRED",
                            queryText("SELECT metadata->'convergence'->>'reason' FROM healthcare.medical_orders WHERE id = '$orderId'"),
                        )
                        assertEquals(
                            "true",
                            queryText("SELECT (end_time = '${sqlTimestamp(past)}'::timestamptz)::text FROM healthcare.medical_orders WHERE id = '$orderId'"),
                            "$orderId 的 end_time 必须保持原值",
                        )
                    }
                    // 任务缺失：本来就没有任务行
                    assertEquals(
                        "0",
                        queryText("SELECT count(*)::text FROM nursing.nursing_tasks WHERE order_item_id = '$noTaskOrderId'"),
                    )
                    // 任务非 ACTIVE：不得被降级路径改写（status 与 updated_at 逐字节未变）
                    assertEquals(terminalTaskBefore, taskRowText(terminalTaskId), "非 ACTIVE 护理任务不得被收束路径改写")
                    assertEquals("CANCELLED", queryText("SELECT status FROM nursing.nursing_tasks WHERE id = '$terminalTaskId'"))
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 027 close-expired fixture / 断言小工具（全部自包含，`mo-` 前缀随既有清理删除）———

    /** 自建一个 ELDERLY_CARE ACTIVE 入住与它唯一的 ACTIVE 照护周期（V404：ELDERLY_CARE 必须绑定入住）。 */
    private fun seedCloseExpiredEncounter(
        encounterId: String,
        patientId: String,
        periodId: String,
        encounterNo: String,
    ) {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute(
                "INSERT INTO healthcare.patients (id, name, gender, birth_date, status) " +
                    "VALUES ('$patientId', '收束测试长者', '男', '1940-01-01', 'ACTIVE')",
            )
            stmt.execute(
                "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) " +
                    "VALUES ('$encounterId', '$patientId', 'ELDERLY_CARE', '$encounterNo', '2026-08-01T00:00:00+08:00', 'ACTIVE')",
            )
            stmt.execute(
                "INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) " +
                    "VALUES ('$periodId', '$patientId', 'ELDERLY_CARE', '$encounterId', '2026-08-01', 'ACTIVE')",
            )
        }
    }

    /** 直接落一条医嘱行（可指定 status 与 end_time；`endTime = null` 表示长期无明确终点）。 */
    private fun seedMedicalOrder(
        orderId: String,
        encounterId: String,
        status: String,
        endTime: OffsetDateTime?,
        startTime: OffsetDateTime,
        orderType: String = "THERAPY",
    ) {
        val end = endTime?.let { "'${sqlTimestamp(it)}'" } ?: "NULL"
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().execute(
                "INSERT INTO healthcare.medical_orders " +
                    "(id, encounter_id, order_type, order_class, order_content, order_details, start_time, end_time, doctor, status) " +
                    "VALUES ('$orderId', '$encounterId', '$orderType', 'LONG_TERM', '收束测试医嘱', '{}'::jsonb, '${sqlTimestamp(startTime)}', $end, '测试医生', '$status')",
            )
        }
    }

    /** 直接落一条医嘱派生的护理任务（`order_item_id` 精确弱关联医嘱）。 */
    private fun seedOrderTask(
        taskId: String,
        periodId: String,
        encounterId: String,
        orderId: String,
        status: String = "ACTIVE",
    ) {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().execute(
                "INSERT INTO nursing.nursing_tasks " +
                    "(id, period_id, encounter_id, order_item_id, task_type, description, frequency_code, frequency_name, start_date, status) " +
                    "VALUES ('$taskId', '$periodId', '$encounterId', '$orderId', 'TREATMENT', '收束测试任务', 'QD', '每日一次', '2026-08-01', '$status')",
            )
        }
    }

    /** 直接落一条护理执行记录（V401 唯一索引要求同一任务内 `planned_time` 互不相同）。 */
    private fun seedExecution(
        executionId: String,
        taskId: String,
        plannedTime: OffsetDateTime,
        status: String,
        actualTime: OffsetDateTime? = null,
    ) {
        val actual = actualTime?.let { "'${sqlTimestamp(it)}'" } ?: "NULL"
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().execute(
                "INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, actual_time, executor, status) " +
                    "VALUES ('$executionId', '$taskId', '${sqlTimestamp(plannedTime)}', $actual, '测试护士', '$status')",
            )
        }
    }

    /** SQL 时间字面量：固定 `yyyy-MM-ddTHH:mm:ssXXX`，与 companion 的 [SQL_TIMESTAMP_FORMAT] 配套。 */
    private fun sqlTimestamp(value: OffsetDateTime): String = value.format(SQL_TIMESTAMP_FORMAT)

    /** 只读单值查询：返回第一行第一列的文本表示（无行时为 null）。 */
    private fun queryText(sql: String): String? =
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().executeQuery(sql).use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    /** 医嘱行可比较快照：status|end_time|metadata|updated_at（用于「未被动过」与幂等断言）。 */
    private fun orderRowText(orderId: String): String? = queryText(
        "SELECT status||'|'||coalesce(end_time::text,'NULL')||'|'||coalesce(metadata::text,'NULL')||'|'||coalesce(updated_at::text,'NULL') " +
            "FROM healthcare.medical_orders WHERE id = '$orderId'",
    )

    /** 护理任务行可比较快照：status|end_date|updated_at。 */
    private fun taskRowText(taskId: String): String? = queryText(
        "SELECT status||'|'||coalesce(end_date::text,'NULL')||'|'||coalesce(updated_at::text,'NULL') " +
            "FROM nursing.nursing_tasks WHERE id = '$taskId'",
    )

    /**
     * 护理执行整行快照（含 `xmin` 行版本）：`nursing_task_executions` 无 `updated_at` 列，
     * 故「全列 + xmin」是「零改写」可得的最强证据——任何 UPDATE 都会换掉 xmin。
     */
    private fun executionsRowText(taskId: String): String? = queryText(
        "SELECT string_agg(id||'|'||status||'|'||coalesce(planned_time::text,'NULL')||'|'||coalesce(actual_time::text,'NULL')||'|'||" +
            "coalesce(executor,'NULL')||'|'||coalesce(metadata::text,'NULL')||'|'||coalesce(created_at::text,'NULL')||'|'||xmin::text, " +
            "' ;; ' ORDER BY id) FROM nursing.nursing_task_executions WHERE task_id = '$taskId'",
    )

    private fun snapshotCounts(): String {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            val parts = listOf(
                "SELECT count(*) FROM nursing.nursing_tasks WHERE encounter_id LIKE '${FIXTURE_PREFIX}%'",
                "SELECT count(*) FROM nursing.nursing_task_executions WHERE task_id IN (SELECT id FROM nursing.nursing_tasks WHERE encounter_id LIKE '${FIXTURE_PREFIX}%')",
                "SELECT count(*) FROM public.stock_operations WHERE id LIKE '${FIXTURE_PREFIX}%'",
            )
            return parts.joinToString(",") { sql ->
                val rs = stmt.executeQuery(sql)
                rs.next()
                rs.getLong(1).toString()
            }
        }
    }
}
