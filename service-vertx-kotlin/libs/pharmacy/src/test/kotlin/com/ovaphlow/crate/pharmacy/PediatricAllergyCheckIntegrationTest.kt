package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 054 D3（发药前药物过敏交叉检查）**真库集成测试**——覆盖 dev 标注的 7 条 DB-free 盲区。
 *
 * 覆盖（054 D3 / E 链窗口）：
 *  ① 预读 SQL `pharmacy_dispenses ⨝ pharmacy_dispense_items`：**无明细时返回空 → 跳过检查**（不误 409）。
 *  ③ **域判定 → `null` → 不阻断**（N18 关键回归）：端口返回 null 时照常放行 2xx。
 *  ④ **严重提醒未二次确认 → 409 且零副作用**：`status` 仍 `DISPENSING`、`dispensed_at` NULL、
 *     `stock_operation_detail_id` 未回写、**出库端口未被调用**（409 发生在事务之前）。
 *  ⑤ **确认后放行**：出库明细 ID 回写 + `status=DISPENSED` + 响应带 `allergy_warnings`。
 *  ⑥ **二次确认未落库**（已知残差，只记录不断言行为）：表中无 `acknowledged_warnings` 列。
 *  ⑦ 并发 `confirm` 无新竞态（恰好一个完成出库）。
 *  另含 **046 兼容回归**：`POST /dispenses` 空体仍 400（非 404/500）。
 *
 * 端口注入：本库内定义 [DrugAllergyCheckPort] / [MedicalOrderReader] / [InventoryOutboundPort]，
 * 由测试注入桩（真实适配器在 Aceso `Main.kt` 装配）。
 *
 * 运行方式（自包含：类自身重置 `aceso_test`，只应用 pharmacy 段 V300–V304）：
 * ```
 * cd service-vertx-kotlin
 * export PITCHFORK_DB_PASSWORD=<hidden> PITCHFORK_TEST_DB_HOST=127.0.0.1 \
 *        PITCHFORK_TEST_DB_PORT=55432 PITCHFORK_TEST_DB_DATABASE=aceso_test PITCHFORK_TEST_DB_USER=ovaphlow
 * ./gradlew :libs:pharmacy:test --rerun --tests '*PediatricAllergyCheckIntegrationTest' \
 *   --no-daemon -Pkotlin.compiler.execution.strategy=in-process
 * ```
 */
@ExtendWith(VertxExtension::class)
@org.junit.jupiter.api.condition.EnabledIf("integrationDbAvailable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PediatricAllergyCheckIntegrationTest {

    companion object {
        private const val TEST_DB = "aceso_test"
        private const val PREFIX = "qa054d3-"
        private val RUN: String = UUID.randomUUID().toString().replace("-", "").take(6)

        private val DISPENSE_A = "${PREFIX}disp-$RUN"
        private val DISPENSE_B = "${PREFIX}disp2-$RUN"
        private val DISPENSE_EMPTY = "${PREFIX}disp-empty-$RUN"
        private val PATIENT = "${PREFIX}patient-$RUN"
        private val MATERIAL = "${PREFIX}material-$RUN"
        private const val SEVERE = "严重"
        private const val MILD = "一般"

        /**
         * 真库门控：显式给出测试库 host 才运行（系统属性 `integration.db.host` 或环境变量
         * `PITCHFORK_TEST_DB_HOST`）；`libs/pharmacy/build.gradle.kts` 不转发 `-D`，故以环境变量为主。
         * **绝不回落到 5432 开发库。**
         */
        @JvmStatic
        fun integrationDbAvailable(): Boolean =
            System.getProperty("integration.db.host")?.takeIf { it.isNotBlank() } != null ||
                System.getenv("PITCHFORK_TEST_DB_HOST")?.takeIf { it.isNotBlank() } != null
    }

    private lateinit var host: String
    private lateinit var port: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var pool: Pool
    private lateinit var client: HttpClient
    private var server: io.vertx.core.http.HttpServer? = null
    private var serverPort: Int = 0

    /** 桩：过敏检查结果（可切换）；`allergyCalls` 记录端口被调用次数。 */
    private var allergyResult: DrugAllergyCheckResult? = null
    private val allergyCalls = AtomicInteger(0)
    private val outboundCalls = AtomicInteger(0)

    // ========================================================================
    //  生命周期
    // ========================================================================

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        host = System.getProperty("integration.db.host")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PITCHFORK_TEST_DB_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        port = System.getProperty("integration.db.port")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PITCHFORK_TEST_DB_PORT")?.takeIf { it.isNotBlank() } ?: "55432"
        user = System.getProperty("integration.db.user")?.takeIf { it.isNotBlank() }
            ?: System.getenv("PITCHFORK_TEST_DB_USER")?.takeIf { it.isNotBlank() } ?: "ovaphlow"
        password = System.getenv("PITCHFORK_DB_PASSWORD") ?: ""

        Assumptions.assumeTrue(
            password.isNotBlank(),
            "integration test skipped: 需要 PITCHFORK_DB_PASSWORD 才会运行",
        )

        try {
            resetTestDb()
            val dbConfig = JsonObject()
                .put("host", host)
                .put("port", port.toInt())
                .put("database", TEST_DB)
                .put("user", user)
            DatabaseConfig.migrate(dbConfig)
            createPeerSchema()
            pool = DatabaseConfig.createPool(vertx, dbConfig)
            client = vertx.createHttpClient()

            val root = Router.router(vertx)
            root.route("/pharmacy/v1/dispenses*").subRouter(
                DispenseRoutes.create(vertx, pool, stubMedicalOrderReader, stubOutbound, null, stubAllergyPort),
            )

            vertx.createHttpServer()
                .requestHandler(root)
                .listen(0)
                .onComplete { ar ->
                    if (ar.succeeded()) {
                        server = ar.result()
                        serverPort = ar.result().actualPort()
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
        try {
            cleanupFixtures()
            assertNoResidual()
        } finally {
            if (::pool.isInitialized) pool.close()
            if (::client.isInitialized) client.close()
        }
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        } ?: ctx.completeNow()
    }

    @BeforeEach
    fun setupFixtures() {
        cleanupFixtures()
        allergyResult = null
        allergyCalls.set(0)
        outboundCalls.set(0)
    }

    @AfterEach
    fun cleanupAndAssertResidual() {
        cleanupFixtures()
        assertNoResidual()
    }

    // ========================================================================
    //  ① 预读 join：无明细 → 跳过检查
    // ========================================================================

    @Test
    fun `D3预读join_无明细时跳过检查不误409`(vertx: Vertx, ctx: VertxTestContext) {
        seedDispense(DISPENSE_EMPTY, "DISPENSING", withItem = false)
        allergyResult = severeResult()
        post(vertx, "$DISPENSES/$DISPENSE_EMPTY/confirm", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(
                    409,
                    status,
                    "无明细发药单本就不能确认（既有 409 dispense has no items）",
                )
                assertTrue(
                    body?.getString("error")?.contains("dispense has no items") == true,
                    "必须是既有的「无明细」409，而**不是**过敏未确认 409，实际: $body",
                )
                assertEquals(0, allergyCalls.get(), "无明细时预读 SQL 返回空 → 不得调用过敏端口")
                assertEquals(
                    "DISPENSING",
                    queryString("SELECT status FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_EMPTY'"),
                    "无明细 409 不得产生副作用",
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  ③ 域判定 null → 不阻断（N18 关键回归）
    // ========================================================================

    @Test
    fun `D3域判定null_不阻断发药`(vertx: Vertx, ctx: VertxTestContext) {
        seedDispense(DISPENSE_A, "DISPENSING", withItem = true)
        allergyResult = null
        post(vertx, "$DISPENSES/$DISPENSE_A/confirm", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status, "端口返回 null（无法判定）必须不阻断发药，实际: $body")
                assertEquals(1, allergyCalls.get(), "有明细时必须调用过敏端口")
                assertEquals(0, body!!.getJsonArray("allergy_warnings").size(), "null → 无提醒")
                assertEquals(false, body.getBoolean("requires_acknowledgement"))
                assertEquals(
                    "DISPENSED",
                    queryString("SELECT status FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_A'"),
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  ④ 严重提醒未确认 → 409 且零副作用
    // ========================================================================

    @Test
    fun `D3严重提醒未确认_409且零副作用`(vertx: Vertx, ctx: VertxTestContext) {
        seedDispense(DISPENSE_A, "DISPENSING", withItem = true)
        allergyResult = severeResult()
        post(vertx, "$DISPENSES/$DISPENSE_A/confirm", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(409, status, "严重提醒未二次确认必须 409，实际: $body")
                assertTrue(
                    body?.getJsonArray("allergy_warnings")?.size() ?: 0 > 0,
                    "409 必须随错误体返回提醒内容，实际: $body",
                )
                // 零副作用：409 在事务之前失败
                assertEquals(
                    "DISPENSING",
                    queryString("SELECT status FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_A'"),
                    "409 不得改变 status",
                )
                assertNull(
                    queryString("SELECT dispensed_at::text FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_A'"),
                    "409 不得写 dispensed_at",
                )
                assertNull(
                    queryString(
                        "SELECT stock_operation_detail_id FROM pharmacy.pharmacy_dispense_items " +
                            "WHERE dispense_id = '$DISPENSE_A'",
                    ),
                    "409 不得回写 stock_operation_detail_id",
                )
                assertEquals(0, outboundCalls.get(), "409 不得调用出库端口")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  ⑤ 已确认 → 放行（出库回写 + DISPENSED + allergy_warnings）
    // ========================================================================

    @Test
    fun `D3严重提醒已确认_放行并回写出库`(vertx: Vertx, ctx: VertxTestContext) {
        seedDispense(DISPENSE_A, "DISPENSING", withItem = true)
        allergyResult = severeResult()
        post(
            vertx,
            "$DISPENSES/$DISPENSE_A/confirm",
            JsonObject().put("acknowledged_warnings", true),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status, "已二次确认必须放行，实际: $body")
                assertEquals(1, body!!.getJsonArray("allergy_warnings").size(), "响应必须带提醒")
                assertEquals(false, body.getBoolean("requires_acknowledgement"))
                assertEquals(1, outboundCalls.get(), "放行时必须调用出库端口")
                assertEquals(
                    "DISPENSED",
                    queryString("SELECT status FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_A'"),
                )
                assertTrue(
                    queryString("SELECT dispensed_at::text FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_A'") != null,
                    "放行必须写 dispensed_at",
                )
                assertEquals(
                    "STK-QA054D3",
                    queryString(
                        "SELECT stock_operation_detail_id FROM pharmacy.pharmacy_dispense_items " +
                            "WHERE dispense_id = '$DISPENSE_A'",
                    ),
                    "出库明细 ID 必须回写",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `D3一般提醒_不阻断并随响应返回`(vertx: Vertx, ctx: VertxTestContext) {
        seedDispense(DISPENSE_B, "DISPENSING", withItem = true)
        allergyResult = DrugAllergyCheckResult(
            drugName = "054D3 药品",
            drugCode = "QA054D3",
            warnings = listOf(AllergyWarning(severity = MILD, reason = "轻微交叉反应", source = "过敏史", matched = "青霉素")),
        )
        post(vertx, "$DISPENSES/$DISPENSE_B/confirm", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(200, status, "一般提醒不得阻断，实际: $body")
                assertEquals(1, body!!.getJsonArray("allergy_warnings").size())
                assertEquals(
                    MILD,
                    body.getJsonArray("allergy_warnings").getJsonObject(0).getString("severity"),
                )
                assertEquals(MATERIAL, body.getJsonArray("allergy_warnings").getJsonObject(0).getString("material_id"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  ⑥ 二次确认未落库（已知残差）+ ⑦ 并发 confirm
    // ========================================================================

    @Test
    fun `D3二次确认未落库_已知残差只记录`(vertx: Vertx, ctx: VertxTestContext) {
        ctx.verify {
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'pharmacy' " +
                        "AND table_name IN ('pharmacy_dispenses','pharmacy_dispense_items') " +
                        "AND column_name LIKE '%acknowledg%'",
                ),
                "已知残差（只记录不断言行为）：二次确认**不落库**，表中无 acknowledged_* 列",
            )
        }
        ctx.completeNow()
    }

    @Test
    fun `D3并发confirm_恰好一次出库`(vertx: Vertx, ctx: VertxTestContext) {
        seedDispense(DISPENSE_A, "DISPENSING", withItem = true)
        allergyResult = null
        val first = post(vertx, "$DISPENSES/$DISPENSE_A/confirm", JsonObject()).map { it.first }
        val second = post(vertx, "$DISPENSES/$DISPENSE_A/confirm", JsonObject()).map { it.first }
        Future.all(first, second).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "并发请求失败: ${ar.cause()?.message}")
                val statuses = listOf(
                    ar.result().resultAt<Int>(0),
                    ar.result().resultAt<Int>(1),
                )
                assertTrue(statuses.all { it == 200 }, "并发 confirm 必须都成功（幂等），实际: $statuses")
                assertEquals(1, outboundCalls.get(), "并发 confirm 只能出库一次（无新竞态）")
                assertEquals(
                    "DISPENSED",
                    queryString("SELECT status FROM pharmacy.pharmacy_dispenses WHERE id = '$DISPENSE_A'"),
                )
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  046 兼容回归
    // ========================================================================

    @Test
    fun `046兼容_POST_dispenses空体仍400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, DISPENSES, JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded(), "请求失败: ${ar.cause()?.message}")
                val (status, body) = ar.result()
                assertEquals(400, status, "046 冻结路由空体必须仍是既有 400（非 404/500），实际: $body")
                assertTrue(body?.getString("error")?.contains("dispense_no is required") == true)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private val DISPENSES = "/pharmacy/v1/dispenses"

    private fun severeResult() = DrugAllergyCheckResult(
        drugName = "054D3 药品",
        drugCode = "QA054D3",
        warnings = listOf(
            AllergyWarning(severity = SEVERE, reason = "明确过敏史", source = "过敏史", matched = "青霉素"),
        ),
    )

    /** 直接落库造发药单（`DISPENSING`）与明细（可无明细）。 */
    private fun seedDispense(dispenseId: String, status: String, withItem: Boolean) {
        exec(
            "INSERT INTO pharmacy.pharmacy_dispenses " +
                "(id, dispense_no, patient_id, dispense_type, status, warehouse) " +
                "VALUES ('$dispenseId', '$dispenseId-no', '$PATIENT', 'OUTPATIENT', '$status', '主药房')",
        )
        if (withItem) {
            exec(
                "INSERT INTO pharmacy.pharmacy_dispense_items " +
                    "(id, dispense_id, order_item_id, material_id, lot_id, dispensed_quantity) " +
                    "VALUES ('$dispenseId-item', '$dispenseId', '$dispenseId-order', '$MATERIAL', 'LOT-1', 2)",
            )
        }
    }

    private fun send(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject?>> =
        client.request(method, serverPort, "127.0.0.1", path).compose { req ->
            if (body != null) req.putHeader("Content-Type", "application/json")
            (if (body != null) req.send(Buffer.buffer(body.encode())) else req.send()).compose { resp ->
                resp.body().map { buf ->
                    val payload = try {
                        JsonObject(buf.toString())
                    } catch (_: Exception) {
                        null
                    }
                    Pair(resp.statusCode(), payload)
                }
            }
        }

    private fun post(vertx: Vertx, path: String, body: JsonObject): Future<Pair<Int, JsonObject?>> =
        send(HttpMethod.POST, path, body)

    /**
     * 跨 schema 最小 peer 表（§9.6 第 7 条）：发药确认路径经 `ReturnableQuantities` 读取
     * `nursing.medication_administrations` 计算累计已给药量；`libs/pharmacy` 类路径不含 nursing
     * 迁移，故由 fixture 建最小表（无记录 → 已给药量 0）。
     */
    private fun createPeerSchema() {
        exec("CREATE SCHEMA IF NOT EXISTS nursing")
        exec(
            "CREATE TABLE IF NOT EXISTS nursing.medication_administrations (" +
                "id VARCHAR(32) PRIMARY KEY, dispense_item_id VARCHAR(32), " +
                "administered_quantity NUMERIC(20,6), result VARCHAR)",
        )
    }

    private fun resetTestDb() {
        val rootUrl = "jdbc:postgresql://$host:$port/postgres"
        DriverManager.getConnection(rootUrl, user, password).use { conn ->
            conn.createStatement().execute("DROP DATABASE IF EXISTS $TEST_DB WITH (FORCE)")
            conn.createStatement().execute("CREATE DATABASE $TEST_DB")
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:postgresql://$host:$port/$TEST_DB", user, password).use(block)

    private fun exec(sql: String) {
        jdbc { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    private fun queryLong(sql: String): Long =
        jdbc { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    private fun queryString(sql: String): String? =
        jdbc { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun cleanupFixtures() {
        exec("DELETE FROM pharmacy.pharmacy_dispense_items WHERE dispense_id LIKE '$PREFIX%'")
        exec("DELETE FROM pharmacy.pharmacy_dispenses WHERE id LIKE '$PREFIX%'")
    }

    private fun assertNoResidual() {
        val residuals = mapOf(
            "pharmacy.pharmacy_dispense_items" to
                "SELECT count(*) FROM pharmacy.pharmacy_dispense_items WHERE dispense_id LIKE '$PREFIX%'",
            "pharmacy.pharmacy_dispenses" to
                "SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE id LIKE '$PREFIX%'",
        )
        val leftovers = residuals.mapNotNull { (table, sql) ->
            val count = queryLong(sql)
            if (count == 0L) null else "$table=$count"
        }
        assertTrue(leftovers.isEmpty(), "fixture 残差必须为 0，实际残留: $leftovers")
    }

    // ========================================================================
    //  端口桩
    // ========================================================================

    private val stubAllergyPort = object : DrugAllergyCheckPort {
        override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> {
            allergyCalls.incrementAndGet()
            return Future.succeededFuture(allergyResult)
        }
    }

    private val stubOutbound = object : InventoryOutboundPort {
        override fun validateOutbound(client: SqlClient, command: OutboundCommand): Future<Void?> =
            Future.succeededFuture(null)

        override fun confirmOutbound(client: SqlClient, command: OutboundCommand): Future<OutboundResult> {
            outboundCalls.incrementAndGet()
            return Future.succeededFuture(
                OutboundResult(
                    stockOperationDetailId = "STK-QA054D3",
                    lotId = command.lotId,
                    unitCost = BigDecimal("1.50000000"),
                ),
            )
        }
    }

    private val stubMedicalOrderReader = object : MedicalOrderReader {
        override fun listMedicationOrders(
            client: SqlClient,
            encounterId: String?,
            search: String?,
            limit: Int,
            offset: Int,
        ): Future<JsonObject> = Future.succeededFuture(JsonObject().put("records", JsonArray()).put("meta", JsonObject().put("total", 0)))

        override fun lockMedicationOrder(client: SqlClient, medicalOrderId: String): Future<MedicationOrderSnapshot> =
            Future.succeededFuture(
                MedicationOrderSnapshot(
                    orderId = medicalOrderId,
                    encounterId = "qa054d3-enc",
                    patientId = PATIENT,
                    patientName = "054D3 患者",
                    encounterNo = "QA054D3",
                    encounterType = "ELDERLY_CARE",
                    encounterStatus = "ACTIVE",
                    orderType = "MEDICATION",
                    orderClass = "LONG_TERM",
                    orderStatus = "ACTIVE",
                    orderContent = "054D3 医嘱",
                    doctor = "qa054d3-doctor",
                    startTime = OffsetDateTime.now(),
                    endTime = null,
                    orderDetails = JsonObject(),
                    nurseCheckedBy = "qa054d3-nurse",
                    nurseCheckedAt = OffsetDateTime.now(),
                    materialId = MATERIAL,
                ),
            )
    }
}
