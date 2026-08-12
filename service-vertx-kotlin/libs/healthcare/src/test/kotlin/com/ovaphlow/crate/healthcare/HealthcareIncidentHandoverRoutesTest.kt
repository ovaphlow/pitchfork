package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function

/**
 * Healthcare 异常事件与班次交接路由的嵌入式 HTTP 测试（不连接 PostgreSQL）。
 *
 * 用 mockk 桩驱动 `Pool.preparedQuery` 与 `Pool.withTransaction`，真实 HTTP 请求
 * 覆盖：认证主体缺失 401、资格校验（非养老/不存在/已离院）、精确归属写入、
 * 字段白名单、未来发生时间、交班幂等键、接班冲突与空请求体等错误映射。
 *
 * 服务器在 @BeforeAll 创建一次，挂载两个路由器：
 *   - /with-auth   注入模拟认证中间件（userId=subj-nurse）
 *   - /no-auth     不注入认证中间件（验证业务处理器 401 兜底）
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthcareIncidentHandoverRoutesTest {

    companion object {
        private const val PORT = 18431
        private const val BASE_AUTH = "/with-auth"
        private const val BASE_NO_AUTH = "/no-auth"
    }

    private class DatabaseStub {
        var encounters: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var periods: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var patients: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var incidents: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var actions: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var executions: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var records: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var handovers: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var items: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()
        var totalRows: io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> = rowSet()

        val connQueries = mutableListOf<String>()
        val connTuples = mutableListOf<Pair<String, List<Any?>>>()

        val pool = mockk<io.vertx.sqlclient.Pool>()
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        private val pq = mockk<io.vertx.sqlclient.PreparedQuery<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>>()
        private var lastSql = ""

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.withTransaction(any<Function<io.vertx.sqlclient.SqlConnection, Future<Any>>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val fn = firstArg<Function<io.vertx.sqlclient.SqlConnection, Future<Any>>>()
                fn.apply(conn)
            }
            every { pq.execute(any<io.vertx.sqlclient.Tuple>()) } answers {
                val sql = lastSql
                connTuples.add(sql to tupleValues(firstArg()))
                routeSql(sql)
            }
            every { pq.execute() } answers {
                connTuples.add(lastSql to emptyList())
                Future.succeededFuture(rowSet())
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            connQueries.add(lastSql)
        }

        private fun routeSql(sql: String): Future<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>> {
            val branch = when {
                sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete") -> "write"
                sql.startsWith("select count") -> "total"
                sql.contains("nursing_incident_actions") -> "actions"
                sql.contains("nursing_incidents") -> "incidents"
                sql.contains("nursing_task_executions") -> "executions"
                sql.contains("nursing_shift_handover_items") -> "items"
                sql.contains("nursing_shift_handovers") -> "handovers"
                sql.contains("nursing_service_periods") -> "periods"
                sql.contains("healthcare.patients") -> "patients"
                sql.contains("healthcare.medical_records") -> "records"
                sql.contains("healthcare.encounters") -> "encounters"
                else -> "else"
            }
            val result = when (branch) {
                "write", "else" -> rowSet()
                "total" -> totalRows
                "actions" -> actions
                "incidents" -> incidents
                "executions" -> executions
                "items" -> items
                "handovers" -> handovers
                "periods" -> periods
                "patients" -> patients
                "records" -> records
                "encounters" -> encounters
                else -> rowSet()
            }
            return Future.succeededFuture(result)
        }
    }

    private val stub = DatabaseStub()

    private val fakeAuthHandler = Handler<RoutingContext> { ctx ->
        if (ctx.request().getHeader("X-Simulate-Unauthorized") != null) {
            ctx.response().setStatusCode(401)
                .end(JsonObject().put("error", "authentication required").encode())
        } else {
            ctx.put("userId", "subj-nurse")
            ctx.next()
        }
    }

    private var server: io.vertx.core.http.HttpServer? = null

    @BeforeEach
    fun resetTraffic() {
        // 每个用例独立断言 SQL 流量；行数据在用例内按需设置
        stub.connQueries.clear()
        stub.connTuples.clear()
    }

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        val routerWithAuth = HealthcareRoutes.create(vertx, stub.pool, null, fakeAuthHandler)
        val routerNoAuth = HealthcareRoutes.create(vertx, stub.pool, null, null)
        val root = Router.router(vertx)
        root.route("$BASE_AUTH/*").subRouter(routerWithAuth)
        root.route("$BASE_NO_AUTH/*").subRouter(routerNoAuth)
        vertx.createHttpServer()
            .requestHandler(root)
            .listen(PORT)
            .onComplete { ar ->
                if (ar.succeeded()) {
                    server = ar.result()
                    ctx.completeNow()
                } else {
                    ctx.failNow(ar.cause())
                }
            }
    }

    @AfterAll
    fun teardown(ctx: VertxTestContext) {
        server?.close { ar ->
            if (ar.succeeded()) ctx.completeNow()
            else ctx.failNow(ar.cause())
        }
    }

    private fun request(
        vertx: Vertx,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, PORT, "localhost", path)
            .compose { req ->
                headers.forEach { (k, v) -> req.putHeader(k, v) }
                if (body != null) {
                    req.putHeader("Content-Type", "application/json")
                    req.send(body.encode())
                } else {
                    req.send()
                }
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = if (buffer.length() > 0) {
                        try {
                            buffer.toJsonObject()
                        } catch (_: RuntimeException) {
                            JsonObject()
                        }
                    } else {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    // ========================================================================
    //  fixture 行
    // ========================================================================

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "EL-20260801-01",
            "department" to "一层照护单元",
            "ward" to "101-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
            "discharge_date" to null,
            "death_date" to null,
            "death_cause" to null,
            "admitting_diagnosis" to null,
            "discharge_diagnosis" to null,
            "attending_physician" to null,
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun periodRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "per-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to null,
            "coordinator" to null,
            "encounter_id" to "enc-1",
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun handoverRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "hov-1",
            "care_unit" to "一层照护单元",
            "business_date" to LocalDate.of(2026, 8, 9),
            "shift" to "早班",
            "handover_by" to "subj-nurse",
            "handed_over_at" to OffsetDateTime.parse("2026-08-09T12:00:00+08:00"),
            "received_by" to null,
            "received_at" to null,
            "status" to "待接班",
            "idempotency_key" to "idem-1",
            "content_digest" to "digest-1",
            "created_at" to OffsetDateTime.parse("2026-08-09T12:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-09T12:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun validIncidentBody(): JsonObject =
        JsonObject()
            .put("incident_type", "跌倒/坠床")
            .put("severity", "较重")
            .put("occurred_at", "2026-08-09T10:00:00+08:00")
            .put("description", "晨间活动时跌倒")

    // ========================================================================
    //  认证
    // ========================================================================

    @Test
    fun `未注入认证中间件时业务处理器返回401`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$BASE_NO_AUTH/encounters/enc-1/nursing-incidents", validIncidentBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(401, status)
                    assertEquals("authentication required", body.getString("error"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `认证中间件拒绝无效会话返回401`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents",
            validIncidentBody(),
            headers = mapOf("X-Simulate-Unauthorized" to "1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(401, status)
                    assertEquals("authentication required", body.getString("error"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  异常事件：资格与精确归属
    // ========================================================================

    @Test
    fun `不存在encounter返回404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rowSet()
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-missing/nursing-incidents", validIncidentBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(404, status)
                    assertTrue(body.getString("error").contains("encounter not found"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非养老入住上报事件返回400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT")))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", validIncidentBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(body.getString("error").contains("not an elderly admission"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `已离院入住上报事件返回409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow(mapOf("status" to "DISCHARGED")))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", validIncidentBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("not active"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `周期不一致或缺失返回409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        stub.periods = rows(periodRow(mapOf("patient_id" to "pat-9")))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", validIncidentBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("patient_id mismatch"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `上报成功返回201且上报人来自认证主体`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        stub.periods = rows(periodRow())
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", validIncidentBody())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(201, status)
                    assertEquals("enc-1", body.getString("encounter_id"))
                    assertEquals("per-1", body.getString("period_id"))
                    assertEquals("跌倒/坠床", body.getString("incident_type"))
                    assertEquals("已上报", body.getString("status"))
                    assertEquals("subj-nurse", body.getString("reporter"))
                    assertNotNull(body.getString("id"))
                    // 同事务写入首条「上报」审计事实，且不发生任何 actions 的 UPDATE/DELETE
                    val actionInserts = stub.connTuples.filter { it.first.startsWith("insert into nursing.nursing_incident_actions") }
                    assertEquals(1, actionInserts.size)
                    assertEquals("上报", actionInserts[0].second[2])
                    assertTrue(stub.connQueries.none { it.startsWith("update") && it.contains("nursing_incident_actions") })
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `未知字段被拒绝返回400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        val body = validIncidentBody().put("reporter", "subj-hacker")
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", body)
            .onSuccess { (status, responseBody) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(responseBody.getString("error").contains("unsupported keys"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `未来发生时间返回400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        val body = validIncidentBody().put("occurred_at", OffsetDateTime.now().plusDays(1).toString())
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", body)
            .onSuccess { (status, responseBody) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(responseBody.getString("error").contains("in the future"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非法中文业务值返回400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        val body = validIncidentBody().put("incident_type", "摔倒")
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents", body)
            .onSuccess { (status, responseBody) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(responseBody.getString("error").contains("invalid incident_type"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `追加处置推进为处理中并返回201`(vertx: Vertx, ctx: VertxTestContext) {
        stub.incidents = rows(incidentRow(mapOf("status" to "已上报")))
        stub.periods = rows(periodRow())
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents/inc-1/actions",
            JsonObject().put("action_type", "处置").put("body", "立即查看并安抚老人").put("notified_party", "家属"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(201, status)
                    assertEquals("处理中", body.getJsonObject("incident").getString("status"))
                    assertEquals("处置", body.getJsonObject("action").getString("action_type"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `关闭空说明返回400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.incidents = rows(incidentRow(mapOf("status" to "处理中")))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents/inc-1/close", JsonObject().put("close_note", "  "))
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(body.getString("error").contains("close_note is required"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `重复关闭返回409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.incidents = rows(incidentRow(mapOf("status" to "已关闭")))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents/inc-1/close", JsonObject().put("close_note", "再关一次"))
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("already closed"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `跨入住事件详情返回404`(vertx: Vertx, ctx: VertxTestContext) {
        // inc-1 属于 enc-2；以 enc-1 作用域读取必须按双重归属过滤返回 404
        stub.incidents = rows(incidentRow(mapOf("encounter_id" to "enc-2")))
        request(vertx, HttpMethod.GET, "$BASE_AUTH/encounters/enc-1/nursing-incidents/inc-1")
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(404, status)
                    assertTrue(body.getString("error").contains("nursing incident not found"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `跨入住追加处置返回404且不产生新事实`(vertx: Vertx, ctx: VertxTestContext) {
        stub.incidents = rows(incidentRow(mapOf("encounter_id" to "enc-2")))
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents/inc-1/actions",
            JsonObject().put("action_type", "处置").put("body", "跨入住处置"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(404, status)
                    assertTrue(body.getString("error").contains("nursing incident not found"))
                    assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") })
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `跨入住关闭返回404且不产生新事实`(vertx: Vertx, ctx: VertxTestContext) {
        // inc-1 属于 enc-2；以 enc-1 作用域关闭必须按双重归属过滤返回 404 且不写入关闭事实
        stub.incidents = rows(incidentRow(mapOf("encounter_id" to "enc-2")))
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/encounters/enc-1/nursing-incidents/inc-1/close",
            JsonObject().put("close_note", "跨入住关闭"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(404, status)
                    assertTrue(body.getString("error").contains("nursing incident not found"))
                    assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") })
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `事件列表按encounter隔离并返回records和total`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        stub.incidents = rows(incidentRow())
        stub.totalRows = rows(mutableMapOf("total" to 1L))
        request(vertx, HttpMethod.GET, "$BASE_AUTH/encounters/enc-1/nursing-incidents?status=${URLEncoder.encode("已上报", "UTF-8")}&limit=50&offset=0")
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals(1, body.getJsonArray("records").size())
                    assertEquals(1L, body.getJsonObject("meta").getLong("total"))
                    assertEquals("enc-1", body.getJsonArray("records").getJsonObject(0).getString("encounter_id"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  班次交接
    // ========================================================================

    @Test
    fun `创建交班缺少Idempotency-Key返回400`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject().put("encounter_id", "enc-1").put("business_date", "2026-08-09").put("shift", "早班"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(body.getString("error").contains("Idempotency-Key"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `创建交班缺失encounter_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject().put("business_date", "2026-08-09").put("shift", "早班"),
            headers = mapOf("Idempotency-Key" to "idem-1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(body.getString("error").contains("encounter_id is required"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `创建交班伪造encounter_id返回404`(vertx: Vertx, ctx: VertxTestContext) {
        // 锚定入住不存在：按契约错误顺序返回 404（对象不存在优先于后续资格校验）
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject().put("encounter_id", "enc-fake").put("business_date", "2026-08-09").put("shift", "早班"),
            headers = mapOf("Idempotency-Key" to "idem-1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(404, status)
                    assertTrue(body.getString("error").contains("encounter not found"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `照护单元不可验证返回409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow(mapOf("department" to null)))
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject().put("encounter_id", "enc-1").put("business_date", "2026-08-09").put("shift", "早班"),
            headers = mapOf("Idempotency-Key" to "idem-1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("care unit is not verifiable"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `创建交班成功返回201并含快照事项`(vertx: Vertx, ctx: VertxTestContext) {
        stub.encounters = rows(encounterRow())
        stub.periods = rows(periodRow())
        stub.patients = rows(
            mutableMapOf(
                "encounter_id" to "enc-1",
                "patient_id" to "pat-1",
                "patient_name" to "测试长者",
                "encounter_no" to "EL-20260801-01",
                "ward" to "101-1",
            ),
        )
        stub.executions = rowSet()
        stub.incidents = rowSet()
        stub.records = rowSet()
        stub.handovers = rowSet()

        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject()
                .put("encounter_id", "enc-1")
                .put("business_date", "2026-08-09")
                .put("shift", "早班")
                .put("manual_items", jsonArrayOf("重点关注张三饮水")),
            headers = mapOf("Idempotency-Key" to "idem-1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(201, status)
                    assertEquals("一层照护单元", body.getString("care_unit"))
                    assertEquals("早班", body.getString("shift"))
                    assertEquals("subj-nurse", body.getString("handover_by"))
                    assertEquals("待接班", body.getString("status"))
                    assertNotNull(body.getString("id"))
                    // 快照写入：入住 1 条 + 手工 1 条
                    val itemInserts = stub.connTuples.filter { it.first.startsWith("insert into nursing.nursing_shift_handover_items") }
                    assertEquals(2, itemInserts.size)
                    assertEquals("入住", itemInserts[0].second[2])
                    assertEquals("手工", itemInserts[1].second[2])
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `同键同内容重试返回200且为同一交班单`(vertx: Vertx, ctx: VertxTestContext) {
        // 首次创建成功后，数据库已存在该交班单（模拟持久化后的锁读）
        val digest = com.ovaphlow.crate.nursing.ShiftHandoverService.sha256Digest(
            listOf("2026-08-09", "早班").plus(emptyList<String>()).joinToString("\u0001"),
        )
        stub.encounters = rows(encounterRow())
        stub.periods = rows(periodRow())
        stub.patients = rows(
            mutableMapOf(
                "encounter_id" to "enc-1",
                "patient_id" to "pat-1",
                "patient_name" to "测试长者",
                "encounter_no" to "EL-20260801-01",
                "ward" to "101-1",
            ),
        )
        stub.executions = rowSet()
        stub.incidents = rowSet()
        stub.records = rowSet()
        stub.handovers = rows(handoverRow(mapOf("idempotency_key" to "idem-1", "content_digest" to digest)))

        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject().put("encounter_id", "enc-1").put("business_date", "2026-08-09").put("shift", "早班"),
            headers = mapOf("Idempotency-Key" to "idem-1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals("hov-1", body.getString("id"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `同键不同内容返回409`(vertx: Vertx, ctx: VertxTestContext) {
        // 交班创建需先完成快照收集（照护单元活动入住），此处补齐 stub 使用例不依赖执行顺序
        stub.encounters = rows(encounterRow())
        stub.periods = rows(periodRow())
        stub.patients = rows(
            mutableMapOf(
                "encounter_id" to "enc-1",
                "patient_id" to "pat-1",
                "patient_name" to "测试长者",
                "encounter_no" to "EL-20260801-01",
                "ward" to "101-1",
            ),
        )
        stub.executions = rowSet()
        stub.incidents = rowSet()
        stub.records = rowSet()
        stub.handovers = rows(handoverRow(mapOf("idempotency_key" to "idem-1", "content_digest" to "digest-other")))
        request(
            vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers",
            JsonObject()
                .put("encounter_id", "enc-1")
                .put("business_date", "2026-08-09")
                .put("shift", "早班")
                .put("manual_items", jsonArrayOf("另一条内容")),
            headers = mapOf("Idempotency-Key" to "idem-1"),
        ).onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("already exists"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `接班请求非空对象返回400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers/hov-1/receive", JsonObject().put("foo", "bar"))
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(body.getString("error").contains("empty object"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `重复接班返回409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.handovers = rows(handoverRow(mapOf("received_by" to "subj-other", "status" to "已接班")))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers/hov-1/receive", JsonObject())
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status)
                    assertTrue(body.getString("error").contains("already been received"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `接班成功写入接班人并返回201`(vertx: Vertx, ctx: VertxTestContext) {
        stub.handovers = rows(handoverRow())
        stub.encounters = rows(encounterRow()) // 照护单元仍有活动入住 → 可接班
        stub.items = rowSet()
        request(vertx, HttpMethod.POST, "$BASE_AUTH/nursing-shift-handovers/hov-1/receive", JsonObject())
            .onSuccess { (status, _) ->
                ctx.verify {
                    assertEquals(201, status)
                    // 接班事实写入：received_by=认证主体、status=已接班
                    val update = stub.connTuples.first {
                        it.first.startsWith("update") && it.first.contains("nursing_shift_handovers")
                    }
                    assertEquals("subj-nurse", update.second[0])
                    assertEquals("已接班", update.second[2])
                    assertTrue(stub.connQueries.first { it.startsWith("update") }.contains("received_by"))
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  incident 行辅助
    // ========================================================================

    private fun incidentRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "inc-1",
            "encounter_id" to "enc-1",
            "period_id" to "per-1",
            "incident_type" to "跌倒/坠床",
            "severity" to "较重",
            "status" to "已上报",
            "occurred_at" to OffsetDateTime.parse("2026-08-09T10:00:00+08:00"),
            "description" to "晨间活动时跌倒",
            "reporter" to "subj-nurse",
            "created_at" to OffsetDateTime.parse("2026-08-09T10:05:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-09T10:05:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun jsonArrayOf(vararg values: String): io.vertx.core.json.JsonArray =
        io.vertx.core.json.JsonArray(values.toList())
}

// ========================================================================
//  顶层辅助（与既有非数据库测试一致的 mockk 行构造）
// ========================================================================

private fun mockRow(values: Map<String, Any?>): io.vertx.sqlclient.Row {
    val row = mockk<io.vertx.sqlclient.Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getBoolean(any<String>()) } answers { values[firstArg<String>()] as? Boolean }
    return row
}

private fun rowSet(vararg rows: io.vertx.sqlclient.Row): io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> {
    val rs = mockk<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<io.vertx.sqlclient.RowIterator<io.vertx.sqlclient.Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun rows(vararg values: Map<String, Any?>): io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: io.vertx.sqlclient.Tuple): List<Any?> {
    val result = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) {
        result.add(tuple.getValue(i))
    }
    return result
}
