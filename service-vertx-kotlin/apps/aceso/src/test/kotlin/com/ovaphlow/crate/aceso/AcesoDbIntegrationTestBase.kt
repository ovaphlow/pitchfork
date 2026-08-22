package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.sql.DriverManager

/**
 * 共享的 Aceso 数据库集成测试基类。
 * 仅连接用户授权的独立 aceso_test，不 DROP/CREATE 数据库；
 * 每个测试类使用固定 prefix 的 fixture，并在每个测试前后清理和断言残差为零。
 */
@ExtendWith(VertxExtension::class)
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AcesoDbIntegrationTestBase {

    abstract val fixturePrefix: String
    abstract val serverPort: Int

    protected lateinit var host: String
    protected lateinit var port: String
    protected lateinit var user: String
    protected lateinit var password: String
    protected lateinit var pool: io.vertx.sqlclient.Pool
    private var server: io.vertx.core.http.HttpServer? = null

    protected fun jdbcUrl(): String = "jdbc:postgresql://$host:$port/aceso_test"

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host", "localhost")
        port = System.getProperty("integration.db.port", "5432")
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""
        try {
            if (password.isBlank()) throw IllegalStateException("PITCHFORK_DB_PASSWORD must be set")
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", "aceso_test")
                .put("user", user)
            AcesoIntegrationTestSupport.migrate(dbConfig)
            pool = com.ovaphlow.crate.database.DatabaseConfig.createPool(vertx, dbConfig)
            val rootRouter = Router.router(vertx)
            rootRouter.route("/*").subRouter(AcesoIntegrationTestSupport.createRouter(vertx, pool))
            vertx.createHttpServer()
                .requestHandler(rootRouter)
                .listen(serverPort)
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

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        cleanupFixtures()
        assertNoResidual()
        if (::pool.isInitialized) pool.close()
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    @BeforeEach
    fun beforeEach() {
        cleanupFixtures()
        assertNoResidual()
        setupFixtures()
    }

    @AfterEach
    fun afterEach() {
        cleanupFixtures()
        assertNoResidual()
    }

    abstract fun setupFixtures()
    abstract fun cleanupFixtures()
    abstract fun assertNoResidual()

    protected fun request(
        vertx: Vertx,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): io.vertx.core.Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        val req = client.request(method, serverPort, "localhost", path)
            .compose { r ->
                headers.forEach { (k, v) -> r.putHeader(k, v) }
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


    protected fun cleanupAll(prefix: String) {
        val p = prefix
        val stmts = listOf(
            // 药房子表 → 父表（ULID 主键通过 idempotency_key/material_id/encounter_id 等业务前缀识别）
            "DELETE FROM pharmacy.pharmacy_purchase_receipt_items WHERE id LIKE '$p%' OR material_id LIKE '$p%' OR receipt_id IN (SELECT id FROM pharmacy.pharmacy_purchase_receipts WHERE id LIKE '$p%' OR purchase_order_id IN (SELECT id FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE '$p%' OR idempotency_key LIKE '$p%'))",
            "DELETE FROM pharmacy.pharmacy_purchase_receipts WHERE id LIKE '$p%' OR purchase_order_id IN (SELECT id FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE '$p%' OR idempotency_key LIKE '$p%')",
            "DELETE FROM pharmacy.pharmacy_purchase_order_items WHERE id LIKE '$p%' OR material_id LIKE '$p%' OR purchase_order_id IN (SELECT id FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE '$p%' OR idempotency_key LIKE '$p%')",
            "DELETE FROM pharmacy.pharmacy_purchase_orders WHERE id LIKE '$p%' OR idempotency_key LIKE '$p%'",
            "DELETE FROM pharmacy.pharmacy_return_items WHERE id LIKE '$p%' OR return_id IN (SELECT id FROM pharmacy.pharmacy_returns WHERE id LIKE '$p%' OR original_dispense_id LIKE '$p%')",
            "DELETE FROM pharmacy.pharmacy_returns WHERE id LIKE '$p%' OR original_dispense_id LIKE '$p%'",
            "DELETE FROM pharmacy.pharmacy_requisition_items WHERE id LIKE '$p%' OR material_id LIKE '$p%' OR requisition_id IN (SELECT id FROM pharmacy.pharmacy_requisitions WHERE id LIKE '$p%' OR idempotency_key LIKE '$p%')",
            "DELETE FROM pharmacy.pharmacy_requisitions WHERE id LIKE '$p%' OR idempotency_key LIKE '$p%'",
            "DELETE FROM pharmacy.pharmacy_dispense_items WHERE id LIKE '$p%' OR material_id LIKE '$p%' OR dispense_id IN (SELECT id FROM pharmacy.pharmacy_dispenses WHERE id LIKE '$p%' OR encounter_id LIKE '$p%' OR patient_id LIKE '$p%')",
            "DELETE FROM pharmacy.pharmacy_dispenses WHERE id LIKE '$p%' OR encounter_id LIKE '$p%' OR patient_id LIKE '$p%'",
            // 医疗子表 → 父表
            "DELETE FROM healthcare.payments WHERE bill_id IN (SELECT id FROM healthcare.bills WHERE id LIKE '$p%' OR encounter_id LIKE '$p%')",
            "DELETE FROM healthcare.bill_items WHERE bill_id IN (SELECT id FROM healthcare.bills WHERE id LIKE '$p%' OR encounter_id LIKE '$p%')",
            "DELETE FROM healthcare.bills WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.deposit_records WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.followup_records WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.followup_plans WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.vital_sign_records WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.chronic_disease_registrations WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.health_checkup_members WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.health_checkup_results WHERE checkup_id IN (SELECT id FROM healthcare.health_checkups WHERE id LIKE '$p%')",
            "DELETE FROM healthcare.health_checkups WHERE id LIKE '$p%'",
            "DELETE FROM healthcare.progress_notes WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.diagnoses WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.medical_records WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM healthcare.medical_orders WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            // 护理子表 → 父表
            "DELETE FROM nursing.nursing_shift_handover_items WHERE id LIKE '$p%' OR handover_id IN (SELECT id FROM nursing.nursing_shift_handovers WHERE id LIKE '$p%' OR encounter_id LIKE '$p%')",
            "DELETE FROM nursing.nursing_shift_handovers WHERE id LIKE '$p%'",
            "DELETE FROM nursing.nursing_incident_actions WHERE id LIKE '$p%' OR incident_id IN (SELECT id FROM nursing.nursing_incidents WHERE id LIKE '$p%' OR encounter_id LIKE '$p%')",
            "DELETE FROM nursing.nursing_incidents WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM nursing.nursing_care_plan_revisions WHERE id LIKE '$p%' OR period_id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM nursing.nursing_visit_schedules WHERE id LIKE '$p%' OR period_id IN (SELECT id FROM nursing.nursing_service_periods WHERE id LIKE '$p%' OR encounter_id LIKE '$p%')",
            "DELETE FROM nursing.nursing_task_execution_consumptions WHERE id LIKE '$p%' OR task_execution_id IN (SELECT id FROM nursing.nursing_task_executions WHERE id LIKE '$p%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '$p%' OR period_id LIKE '$p%'))",
            "DELETE FROM nursing.nursing_task_executions WHERE id LIKE '$p%' OR task_id IN (SELECT id FROM nursing.nursing_tasks WHERE id LIKE '$p%' OR period_id LIKE '$p%' OR encounter_id LIKE '$p%')",
            "DELETE FROM nursing.nursing_tasks WHERE id LIKE '$p%' OR period_id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM nursing.nursing_plan_items WHERE id LIKE '$p%' OR plan_id IN (SELECT id FROM nursing.nursing_plans WHERE id LIKE '$p%' OR period_id LIKE '$p%')",
            "DELETE FROM nursing.nursing_plans WHERE id LIKE '$p%' OR period_id LIKE '$p%'",
            "DELETE FROM nursing.nursing_assessments WHERE id LIKE '$p%' OR period_id LIKE '$p%' OR encounter_id LIKE '$p%'",
            "DELETE FROM nursing.nursing_service_periods WHERE id LIKE '$p%' OR encounter_id LIKE '$p%'",
            // 库存
            "DELETE FROM public.stock_operation_details WHERE id LIKE '$p%' OR material_id LIKE '$p%' OR operation_id IN (SELECT id FROM public.stock_operations WHERE id LIKE '$p%' OR metadata::text LIKE '%$p%')",
            "DELETE FROM public.stock_operations WHERE id LIKE '$p%' OR metadata::text LIKE '%$p%'",
            "DELETE FROM public.stocks WHERE id LIKE '$p%' OR material_id LIKE '$p%'",
            "DELETE FROM public.lots WHERE id LIKE '$p%' OR material_id LIKE '$p%'",
            "DELETE FROM public.materials WHERE id LIKE '$p%'",
            // 基础档案最后删
            "DELETE FROM healthcare.encounters WHERE id LIKE '$p%' OR patient_id LIKE '$p%'",
            "DELETE FROM healthcare.patients WHERE id LIKE '$p%'",
        )
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().use { st ->
                stmts.forEach { st.execute(it) }
            }
        }
    }

    protected fun executeSql(sql: String) {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    protected fun countRows(sql: String): Long {
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }
}
