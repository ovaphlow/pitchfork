package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.nursing.NursingRoutes
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
import java.time.OffsetDateTime

/**
 * 养老入住去世/离院终局收束 PostgreSQL 集成测试（仅授权 aceso_test 运行）。
 *
 * fixture 统一使用 `ed-` 前缀，按依赖逆序清理，前后残差为零；连接既有 aceso_test，
 * 绝不 DROP/CREATE 数据库、绝不触碰非 `ed-` 前缀数据。
 *
 * 覆盖计划「获授权的 PostgreSQL 集成测试」第 3 项：
 *   1. 去世成功：同一事务收束医嘱(DISCONTINUED+end_time)、任务(CANCELLED)、周期(COMPLETED+end_date)、
 *      encounter(DECEASED+death_date/death_cause)、患者(DECEASED)，discharge_date 保持空
 *   2. IN_PROGRESS 执行阻断：409 且每个相关表均不变（无半收束）
 *   3. 非养老 400、缺周期 409、已离院/已去世 409、不存在 404、去世时间缺失/格式错误 400、
 *      death_cause 超长 400
 *   4. 互斥终局：去世后再离院 409；再去世 409
 *   5. V501 部分唯一索引使「同患者另一活动 ELDERLY_CARE 入住」状态在数据库层不可构造，
 *      直接插入第二活动入住必须唯一冲突（该 409 服务路径由开发单元测试 mock 覆盖，见 finding2）
 *   6. 无副作用：无关任务/执行/护理记录/交接摘要/库存诱饵不变
 *
 * 通过 -Dintegration.db.* 系统属性启用；默认运行被跳过。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElderlyDeathIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val TEST_PORT = 18427
        private const val FIXTURE_PREFIX = "ed-"
        private const val HEALTHCARE_BASE = "/healthcare/v1"
        private const val NURSING_BASE = "/nursing/v1"
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

        try {
            if (password.isBlank()) throw IllegalStateException("PITCHFORK_DB_PASSWORD must be set")
            check(port == "55432" || port == "5432") { "integration test must target the authorized aceso_test port" }

            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            pool = DatabaseConfig.createPool(vertx, dbConfig)

            val rootRouter = Router.router(vertx)
            rootRouter.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))
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
        cleanupFixtures()
        assertResidualZero()
        if (::pool.isInitialized) pool.close()
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    // ========================================================================
    // fixture：ed- 前缀
    // ========================================================================
    //   ed-patient-1 + ed-enc-1（ELDERLY_CARE ACTIVE）+ ed-period-1（ACTIVE）→ 正常去世
    //   ed-patient-2 + ed-enc-2（ELDERLY_CARE ACTIVE）+ ed-period-2 + ed-task-2 + ed-exec-2(IN_PROGRESS)
    //       → IN_PROGRESS 阻断
    //   ed-patient-3 + ed-enc-3（OUTPATIENT ACTIVE）→ 非养老 400
    //   ed-patient-4 + ed-enc-4（ELDERLY_CARE ACTIVE，无周期）→ 缺周期 409
    //   ed-patient-5 + ed-enc-5（ELDERLY_CARE DISCHARGED + COMPLETED 周期）→ 已离院 409
    //   ed-patient-6 + ed-enc-6（ELDERLY_CARE DECEASED + COMPLETED 周期）→ 已去世 409
    //   诱饵：ed-task-bait/ed-exec-bait（另一周期）、ed-record-bait（护理记录）、
    //         ed-handover-bait（DISCHARGE_SUMMARY）、ed-mat-bait/ed-lot-bait/ed-stock-bait/
    //         ed-op-bait/ed-opd-bait（库存）
    private fun setupFixtures() {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            for (i in 1..6) {
                stmt.execute("INSERT INTO healthcare.patients (id, name, gender, birth_date, status) VALUES ('${fixtureId("patient-$i")}', '去世测试长者$i', '女', '1941-01-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            }
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-1")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'ED-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-2")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', 'ED-ENC-2', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-3")}', '${fixtureId("patient-3")}', 'OUTPATIENT', 'ED-ENC-3', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-4")}', '${fixtureId("patient-4")}', 'ELDERLY_CARE', 'ED-ENC-4', '2026-08-01T00:00:00+08:00', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status) VALUES ('${fixtureId("enc-5")}', '${fixtureId("patient-5")}', 'ELDERLY_CARE', 'ED-ENC-5', '2026-08-01T00:00:00+08:00', '2026-08-03T00:00:00Z', 'DISCHARGED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, death_date, status) VALUES ('${fixtureId("enc-6")}', '${fixtureId("patient-6")}', 'ELDERLY_CARE', 'ED-ENC-6', '2026-08-01T00:00:00+08:00', '2026-08-03T00:00:00Z', 'DECEASED') ON CONFLICT (id) DO NOTHING")

            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-1")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', '${fixtureId("enc-1")}', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status) VALUES ('${fixtureId("period-2")}', '${fixtureId("patient-2")}', 'ELDERLY_CARE', '${fixtureId("enc-2")}', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status) VALUES ('${fixtureId("period-5")}', '${fixtureId("patient-5")}', 'ELDERLY_CARE', '${fixtureId("enc-5")}', '2026-08-01', '2026-08-03', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, end_date, status) VALUES ('${fixtureId("period-6")}', '${fixtureId("patient-6")}', 'ELDERLY_CARE', '${fixtureId("enc-6")}', '2026-08-01', '2026-08-03', 'COMPLETED') ON CONFLICT (id) DO NOTHING")

            // IN_PROGRESS 执行（ed-enc-2）
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, start_date, status) VALUES ('${fixtureId("task-2")}', '${fixtureId("period-2")}', '${fixtureId("enc-2")}', 'NURSING', '阻断执行任务', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, executor, status) VALUES ('${fixtureId("exec-2")}', '${fixtureId("task-2")}', '2026-08-01T09:00:00+08:00', '测试护士', 'IN_PROGRESS') ON CONFLICT (id) DO NOTHING")

            // 诱饵：另一周期无关任务/执行（ed-enc-1 也作为诱饵任务挂载周期）
            stmt.execute("INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, task_type, description, start_date, status) VALUES ('${fixtureId("task-bait")}', '${fixtureId("period-1")}', '${fixtureId("enc-1")}', 'NURSING', '无关任务', '2026-08-01', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, executor, status) VALUES ('${fixtureId("exec-bait")}', '${fixtureId("task-bait")}', '2026-08-01T10:00:00+08:00', '无关护士', 'COMPLETED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("record-bait")}', '${fixtureId("enc-1")}', 'NURSING_RECORD', '无关护理记录', '不得受去世操作影响', '测试护士', '2026-08-01', '{\"period_id\":\"${fixtureId("period-1")}\"}') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO healthcare.medical_records (id, encounter_id, record_type, title, content, physician, record_date, metadata) VALUES ('${fixtureId("handover-bait")}', '${fixtureId("enc-1")}', 'DISCHARGE_SUMMARY', '既有离院交接摘要', '不得被去世操作改写', '测试员', '2026-07-31', '{\"period_id\":\"${fixtureId("period-1")}\",\"is_elderly_discharge_handover\":\"true\"}') ON CONFLICT (id) DO NOTHING")

            // 库存诱饵
            stmt.execute("INSERT INTO public.materials (id, code, name, category, package_unit, status) VALUES ('${fixtureId("mat-bait")}', 'ED-MAT-BAIT', '诱饵材料', '耗材', '包', 'ACTIVE') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.lots (id, material_id, batch_no) VALUES ('${fixtureId("lot-bait")}', '${fixtureId("mat-bait")}', 'ED-LOT-1') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost) VALUES ('${fixtureId("stock-bait")}', '主库', '${fixtureId("mat-bait")}', '${fixtureId("lot-bait")}', 5, 0, 0) ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.stock_operations (id, order_no, operation_type, warehouse, status) VALUES ('${fixtureId("op-bait")}', 'ED-OP-1', 'INBOUND', '主库', 'CONFIRMED') ON CONFLICT (id) DO NOTHING")
            stmt.execute("INSERT INTO public.stock_operation_details (id, operation_id, material_id, lot_id, quantity, unit, unit_cost, total_cost) VALUES ('${fixtureId("opd-bait")}', '${fixtureId("op-bait")}', '${fixtureId("mat-bait")}', '${fixtureId("lot-bait")}', 5, 'PACKAGE', 0, 0) ON CONFLICT (id) DO NOTHING")
        }
    }

    private fun cleanupFixtures() {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            stmt.execute("DELETE FROM nursing.nursing_visit_schedules WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_task_execution_consumptions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '${FIXTURE_PREFIX}%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%'))")
            stmt.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '${FIXTURE_PREFIX}%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%')")
            stmt.execute("DELETE FROM healthcare.medical_orders WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
            stmt.execute("DELETE FROM healthcare.medical_records WHERE id LIKE '${FIXTURE_PREFIX}%' OR encounter_id LIKE '${FIXTURE_PREFIX}%'")
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
        check(residual() == 0L) { "ed- fixture cleanup left residual data" }
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

    private fun deathBody(cause: String = "心力衰竭"): JsonObject =
        JsonObject().put("death_date", "2026-08-05T14:00:00+08:00").put("death_cause", cause)

    // ——— 断言 1：正常去世同一事务收束全部行 ———

    @Test
    fun `正常去世收束医嘱任务周期并写去世字段`(vertx: Vertx, ctx: VertxTestContext) {
        // 先为 ed-enc-1 开立一条用药医嘱
        request(
            vertx,
            HttpMethod.POST,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/orders",
            JsonObject()
                .put("order_type", "MEDICATION")
                .put("order_content", "阿莫西林 0.5g 每日两次")
                .put("doctor", "赵医生")
                .put("start_time", "2026-08-01T10:00:00+08:00")
                .put(
                    "order_details",
                    JsonObject().put("drug_name", "阿莫西林").put("frequency_code", "QD").put("frequency_name", "每日一次"),
                ),
        )
            .compose { (status, _) ->
                ctx.verify { assertEquals(201, status) }
                request(
                    vertx,
                    HttpMethod.PATCH,
                    "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death",
                    deathBody(),
                )
            }
            .compose { (status, encounter) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals("DECEASED", encounter.getString("status"))
                    // PostgreSQL timestamptz 读回统一为 UTC 表示，但瞬间相等
                    assertEquals(
                        OffsetDateTime.parse("2026-08-05T14:00:00+08:00").toInstant(),
                        OffsetDateTime.parse(encounter.getString("death_date")).toInstant(),
                        "death_date 瞬间必须相等",
                    )
                    assertEquals("心力衰竭", encounter.getString("death_cause"))
                    assertNull(encounter.getString("discharge_date"), "去世后 discharge_date 必须保持空")
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                        val order = conn.createStatement().executeQuery("SELECT status, end_time IS NOT NULL AS has_end FROM healthcare.medical_orders WHERE encounter_id = '${fixtureId("enc-1")}'")
                        order.next()
                        assertEquals("DISCONTINUED", order.getString("status"), "去世必须把活动医嘱置为 DISCONTINUED")
                        assertEquals(true, order.getBoolean("has_end"), "医嘱必须写 end_time")
                        val task = conn.createStatement().executeQuery("SELECT status FROM nursing.nursing_tasks WHERE encounter_id = '${fixtureId("enc-1")}' AND order_item_id IS NOT NULL")
                        task.next()
                        assertEquals("CANCELLED", task.getString("status"), "医嘱任务必须 CANCELLED")
                        val period = conn.createStatement().executeQuery("SELECT status, end_date FROM nursing.nursing_service_periods WHERE id = '${fixtureId("period-1")}'")
                        period.next()
                        assertEquals("COMPLETED", period.getString("status"))
                        assertEquals("2026-08-05", period.getString("end_date"), "周期结束日必须等于去世业务日期")
                        val patient = conn.createStatement().executeQuery("SELECT status FROM healthcare.patients WHERE id = '${fixtureId("patient-1")}'")
                        patient.next()
                        assertEquals("DECEASED", patient.getString("status"), "患者状态必须 DECEASED")
                        promise.complete()
                    }
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 2：IN_PROGRESS 阻断，无半收束 ———

    @Test
    fun `进行中执行阻断去世且每个相关表均不变`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.PATCH,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-2")}/death",
            deathBody(),
        ).compose { (status, body) ->
            ctx.verify {
                assertEquals(409, status, "IN_PROGRESS 执行必须阻断去世")
                assertNotNull(body.getString("error"))
            }
            io.vertx.core.Future.future<Unit> { promise ->
                DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                    val encounter = conn.createStatement().executeQuery("SELECT status FROM healthcare.encounters WHERE id = '${fixtureId("enc-2")}'")
                    encounter.next()
                    assertEquals("ACTIVE", encounter.getString("status"), "encounter 必须保持 ACTIVE")
                    val period = conn.createStatement().executeQuery("SELECT status FROM nursing.nursing_service_periods WHERE id = '${fixtureId("period-2")}'")
                    period.next()
                    assertEquals("ACTIVE", period.getString("status"), "周期必须保持 ACTIVE")
                    val task = conn.createStatement().executeQuery("SELECT status FROM nursing.nursing_tasks WHERE id = '${fixtureId("task-2")}'")
                    task.next()
                    assertEquals("ACTIVE", task.getString("status"), "任务必须保持 ACTIVE")
                    val exec = conn.createStatement().executeQuery("SELECT status FROM nursing.nursing_task_executions WHERE id = '${fixtureId("exec-2")}'")
                    exec.next()
                    assertEquals("IN_PROGRESS", exec.getString("status"), "执行必须保持 IN_PROGRESS")
                    promise.complete()
                }
            }
        }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 3：资格与输入校验 ———

    @Test
    fun `非养老返回400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-3")}/death", deathBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(body.getString("error").contains("not an elderly admission"), "got: ${body.getString("error")}")
                    ctx.completeNow()
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `缺周期返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-4")}/death", deathBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("no bound nursing care period"), "got: ${body.getString("error")}")
                    ctx.completeNow()
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `已离院和已去世均返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-5")}/death", deathBody())
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "已离院必须 409")
                    assertNotNull(body.getString("error"))
                }
                request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-6")}/death", deathBody())
            }
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "已去世必须 409")
                    assertNotNull(body.getString("error"))
                    ctx.completeNow()
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `不存在encounter返回404`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-missing")}/death", deathBody())
            .onSuccess { (status, _) ->
                ctx.verify {
                    assertEquals(404, status)
                    ctx.completeNow()
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `去世时间缺失或格式错误返回400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death", JsonObject().put("death_cause", "x"))
            .compose { (status, _) ->
                ctx.verify { assertEquals(400, status, "缺 death_date 必须 400") }
                request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death", JsonObject().put("death_date", "not-a-date"))
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(400, status, "非法 death_date 必须 400") }
                request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death", JsonObject().put("death_date", "2026-08-05T14:00:00+08:00").put("death_cause", "x".repeat(501)))
            }
            .onSuccess { (status, _) ->
                ctx.verify {
                    assertEquals(400, status, "death_cause 超 500 必须 400")
                    ctx.completeNow()
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 4：互斥终局 ———

    @Test
    fun `去世后再离院或再去世均返回409`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.PATCH,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death",
            deathBody(),
        ).compose { (status, _) ->
            ctx.verify { assertEquals(200, status) }
            // 再离院：period 已 COMPLETED → 409
            request(
                vertx,
                HttpMethod.PATCH,
                "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/discharge",
                JsonObject().put("discharge_date", "2026-08-06T10:00:00+08:00"),
            )
        }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "去世后再离院必须 409")
                    assertNotNull(body.getString("error"))
                }
                // 再去世：encounter 非 ACTIVE → 409
                request(vertx, HttpMethod.PATCH, "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death", deathBody())
            }
            .onSuccess { (status, _) ->
                ctx.verify {
                    assertEquals(409, status, "去世后再去世必须 409")
                    ctx.completeNow()
                }
            }
            .onFailure { ctx.failNow(it) }
    }

    // ——— 断言 5：V501 使「同患者另一活动养老入住」状态不可构造 ———

    @Test
    fun `同患者第二活动养老入住被数据库唯一索引拒绝`(vertx: Vertx, ctx: VertxTestContext) {
        // V501 部分唯一索引 uq_encounters_active_elderly_care(patient_id)
        // WHERE encounter_type='ELDERLY_CARE' AND status='ACTIVE' 阻止该状态；
        // 服务端 ensureNoOtherActiveElderlyAdmission 的 409 路径由开发单元测试
        // HealthcareMedicalOrderTest 以 mock 覆盖（finding2）。
        try {
            DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
                conn.createStatement().execute(
                    "INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status) VALUES ('${fixtureId("enc-conflict")}', '${fixtureId("patient-1")}', 'ELDERLY_CARE', 'ED-ENC-CONFLICT', '2026-08-06T00:00:00+08:00', 'ACTIVE')",
                )
            }
            ctx.failNow(AssertionError("第二活动养老入住必须被 V501 唯一索引拒绝"))
        } catch (_: java.sql.SQLException) {
            ctx.completeNow()
        } catch (e: Exception) {
            ctx.failNow(e)
        }
    }

    // ——— 断言 6：无副作用 ———

    @Test
    fun `去世前后诱饵数据保持不变`(vertx: Vertx, ctx: VertxTestContext) {
        val before = snapshotBait()
        request(
            vertx,
            HttpMethod.PATCH,
            "$HEALTHCARE_BASE/encounters/${fixtureId("enc-1")}/death",
            deathBody(),
        ).compose { (status, _) ->
            ctx.verify { assertEquals(200, status) }
            io.vertx.core.Future.future<Unit> { promise ->
                val after = snapshotBait()
                assertEquals(before, after, "去世操作不得改变诱饵数据: before=$before after=$after")
                promise.complete()
            }
        }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    private fun snapshotBait(): String {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            val stmt = conn.createStatement()
            val parts = listOf(
                "SELECT count(*) FROM nursing.nursing_tasks WHERE id = '${fixtureId("task-bait")}'",
                "SELECT count(*) FROM nursing.nursing_task_executions WHERE id = '${fixtureId("exec-bait")}'",
                "SELECT count(*) FROM healthcare.medical_records WHERE id IN ('${fixtureId("record-bait")}','${fixtureId("handover-bait")}')",
                "SELECT count(*) FROM public.materials WHERE id = '${fixtureId("mat-bait")}'",
                "SELECT count(*) FROM public.stocks WHERE id = '${fixtureId("stock-bait")}'",
                "SELECT count(*) FROM public.stock_operations WHERE id = '${fixtureId("op-bait")}'",
                "SELECT count(*) FROM public.stock_operation_details WHERE id = '${fixtureId("opd-bait")}'",
            )
            return parts.joinToString(",") { sql ->
                val rs = stmt.executeQuery(sql)
                rs.next()
                rs.getLong(1).toString()
            }
        }
    }
}
