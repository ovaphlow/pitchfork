package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 B1：儿童康复周期与训练计划的**不连数据库**路由级护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. 周期创建：`service_type=CHILD_REHAB`、`encounter_id` 由 SQL 写死 `NULL`、`status=ACTIVE`；
 *     客户端提交的同名字段一律忽略；`coordinator` 缺省取认证主体。
 *  2. **同儿童重复启用周期 409**：唯一索引冲突（含被 `cause` 链包裹）都映射为 409 中文文案。
 *  3. 周期状态流转：合法 200、非法 400、终态拒绝；重新启用撞唯一索引 → 409。
 *  4. 计划创建：必须挂在 `CHILD_REHAB` 周期上；`patient_id` 由 join 周期**派生**（不写 `nursing_plans`）；
 *     `created_by` 取认证主体；明细写 `nursing_plan_items`。
 *  5. 列表空结果 `{records: [], meta: {total: 0}}`；`?patient_id=` 走跨表 join 过滤。
 */
@ExtendWith(VertxExtension::class)
class ChildRehabRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val periodTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val planTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val itemTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var patientFound = true
    private var periodFound = true
    private var periodStatus: String? = "ACTIVE"
    private var planPeriodType: String? = ChildRehabPeriodService.SERVICE_TYPE
    private var insertFailure: Throwable? = null
    private var updateFailure: Throwable? = null
    private var countTotal = 0L
    private var periodListRows: List<Row> = emptyList()
    private var planListRows: List<Row> = emptyList()
    /** 是否返回计划明细；明细行按**请求的 plan_id** 现造（计划 id 由服务端生成 ULID）。 */
    private var itemsEnabled = false

    @BeforeEach
    fun setUp() {
        executed.clear()
        periodTuples.clear()
        planTuples.clear()
        itemTuples.clear()
        patientFound = true
        periodFound = true
        periodStatus = "ACTIVE"
        planPeriodType = ChildRehabPeriodService.SERVICE_TYPE
        insertFailure = null
        updateFailure = null
        countTotal = 0L
        periodListRows = emptyList()
        planListRows = emptyList()
        itemsEnabled = false

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into nursing.nursing_service_periods") -> {
                    periodTuples.add(firstArg())
                    val failure = insertFailure
                    if (failure != null) Future.failedFuture(failure)
                    else Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("insert into nursing.nursing_plans") -> {
                    planTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("insert into nursing.nursing_plan_items") -> {
                    itemTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("update nursing.nursing_service_periods") -> {
                    val failure = updateFailure
                    if (failure != null) Future.failedFuture(failure)
                    else Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("select status from nursing.nursing_service_periods") ->
                    Future.succeededFuture(
                        if (periodFound && periodStatus != null) rowSet(statusRow(periodStatus!!)) else rowSet(),
                    )
                sql.contains("select id, patient_id, service_type from nursing.nursing_service_periods") ->
                    Future.succeededFuture(
                        if (periodFound) rowSet(planPeriodRow()) else rowSet(),
                    )
                sql.contains("count(*) as total") ->
                    Future.succeededFuture(rowSet(longRow("total", countTotal)))
                sql.contains("from nursing.nursing_plan_items") -> {
                    val planId = firstArg<Tuple>().getString(0)
                    Future.succeededFuture(if (itemsEnabled) rowSet(itemRow(planId)) else rowSet())
                }
                sql.contains("from nursing.nursing_plans pl") ->
                    if (sql.contains("where pl.id = ")) {
                        Future.succeededFuture(rowSet(planRow()))
                    } else {
                        Future.succeededFuture(rowSet(*planListRows.toTypedArray()))
                    }
                sql.contains("from nursing.nursing_service_periods p join healthcare.patients") ->
                    if (sql.contains("where p.id = ")) {
                        Future.succeededFuture(rowSet(periodRow()))
                    } else {
                        Future.succeededFuture(rowSet(*periodListRows.toTypedArray()))
                    }
                sql.contains("from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(patientRow()) else rowSet())
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  周期
    // ========================================================================

    @Test
    fun `创建周期返回201且类型与空入住由服务端强制`(vertx: Vertx, ctx: VertxTestContext) {
        val body = periodBody()
            .put("service_type", "ELDERLY_CARE")
            .put("encounter_id", "enc-1")
            .put("status", "COMPLETED")

        post(vertx, "/nursing/v1/child-rehab-periods", body) { status, response ->
            ctx.verify {
                assertEquals(201, status, "创建必须 201，实际: ${response.encode()}")
                assertEquals(ChildRehabPeriodService.SERVICE_TYPE, response.getString("service_type"))
                assertEquals("ACTIVE", response.getString("status"))
                assertEquals("patient-1", response.getString("patient_id"))
                assertEquals("小明", response.getString("patient_name"))

                val insertSql = executed.first { it.contains("insert into nursing.nursing_service_periods") }
                assertTrue(insertSql.contains("null"), "encounter_id 必须由 SQL 写死 NULL：$insertSql")
                val tuple = periodTuples.first()
                assertEquals(ChildRehabPeriodService.SERVICE_TYPE, tuple.getValue(2), "服务类型服务端强制")
                assertEquals("ACTIVE", tuple.getValue(6), "状态服务端强制")
                assertEquals("rehab01", tuple.getValue(5), "coordinator 缺省取认证主体")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `同儿童已有启用周期返回409`(vertx: Vertx, ctx: VertxTestContext) {
        insertFailure = PgException(
            "duplicate key value violates unique constraint \"uq_nursing_service_periods_active_child_rehab\"",
            "ERROR",
            "23505",
            "Key (patient_id)=(patient-1) already exists.",
        )

        post(vertx, "/nursing/v1/child-rehab-periods", periodBody()) { status, response ->
            ctx.verify {
                assertEquals(409, status, "唯一冲突必须 409，实际: ${response.encode()}")
                assertEquals("该儿童已有启用中的康复服务周期", response.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `被cause链包裹的唯一冲突仍返回409`(vertx: Vertx, ctx: VertxTestContext) {
        insertFailure = io.vertx.core.VertxException(
            "insert failed",
            PgException("duplicate key value violates unique constraint", "ERROR", "23505", "duplicate"),
        )

        post(vertx, "/nursing/v1/child-rehab-periods", periodBody()) { status, response ->
            ctx.verify {
                assertEquals(409, status, "被包裹的 23505 必须仍映射 409，实际: ${response.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建周期缺patient_id返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, "/nursing/v1/child-rehab-periods", JsonObject().put("start_date", "2026-10-06")) { status, response ->
            ctx.verify {
                assertEquals(400, status, "实际: ${response.encode()}")
                assertEquals("patient_id is required", response.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `创建周期儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false
        post(vertx, "/nursing/v1/child-rehab-periods", periodBody()) { status, response ->
            ctx.verify {
                assertEquals(404, status, "实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("patient not found"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期列表空结果返回records空与total0`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-periods?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                val listSql = executed.last { it.contains("from nursing.nursing_service_periods p join healthcare.patients") }
                assertTrue(listSql.contains("p.service_type = \$1"), "列表必须限定 CHILD_REHAB：$listSql")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期列表非法状态返回400`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-periods?status=PENDING") { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("invalid status"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期状态合法流转返回200`(vertx: Vertx, ctx: VertxTestContext) {
        periodStatus = "ACTIVE"
        patch(vertx, "/nursing/v1/child-rehab-periods/period-1/status", JsonObject().put("status", "SUSPENDED")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "合法流转必须 200，实际: ${body.encode()}")
                assertTrue(
                    executed.any { it.contains("update nursing.nursing_service_periods") },
                    "必须真的执行过 UPDATE，实际: $executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期终态非法流转返回400`(vertx: Vertx, ctx: VertxTestContext) {
        periodStatus = "COMPLETED"
        patch(vertx, "/nursing/v1/child-rehab-periods/period-1/status", JsonObject().put("status", "ACTIVE")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "终态必须拒绝流转，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("cannot transition"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期重新启用撞唯一索引返回409`(vertx: Vertx, ctx: VertxTestContext) {
        periodStatus = "SUSPENDED"
        updateFailure = PgException(
            "duplicate key value violates unique constraint \"uq_nursing_service_periods_active_child_rehab\"",
            "ERROR",
            "23505",
            "duplicate",
        )
        patch(vertx, "/nursing/v1/child-rehab-periods/period-1/status", JsonObject().put("status", "ACTIVE")) { status, body ->
            ctx.verify {
                assertEquals(409, status, "重新启用撞唯一索引必须 409，实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `周期不存在流转返回404`(vertx: Vertx, ctx: VertxTestContext) {
        periodFound = false
        patch(vertx, "/nursing/v1/child-rehab-periods/period-1/status", JsonObject().put("status", "SUSPENDED")) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  计划
    // ========================================================================

    @Test
    fun `创建训练计划返回201且patient_id由join周期派生`(vertx: Vertx, ctx: VertxTestContext) {
        itemsEnabled = true
        val body = JsonObject()
            .put("period_id", "period-1")
            .put("plan_name", "术后下肢训练计划")
            .put("goals", "恢复独立步行")
            .put("start_date", "2026-10-06")
            .put(
                "items",
                JsonArray().add(
                    JsonObject()
                        .put("action", "被动关节活动")
                        .put("frequency_code", "QD")
                        .put("frequency_name", "每日一次")
                        .put("duration_days", 14),
                ),
            )

        post(vertx, "/nursing/v1/child-rehab-plans", body) { status, response ->
            ctx.verify {
                assertEquals(201, status, "创建必须 201，实际: ${response.encode()}")
                assertEquals("patient-1", response.getString("patient_id"), "patient_id 必须由 join 周期派生")
                assertEquals("period-1", response.getString("period_id"))
                assertEquals(1, response.getJsonArray("items")?.size() ?: -1)

                val planSql = executed.first { it.contains("insert into nursing.nursing_plans") }
                assertTrue(planSql.contains("null"), "nursing_plans.encounter_id 必须写死 NULL：$planSql")
                assertTrue(
                    planSql.contains("patient_id").not(),
                    "不得给 nursing_plans 加 patient_id 列：$planSql",
                )
                val planTuple = planTuples.first()
                assertEquals("period-1", planTuple.getValue(1))
                assertEquals("ACTIVE", planTuple.getValue(4), "状态服务端强制 ACTIVE")
                assertEquals("rehab01", planTuple.getValue(5), "created_by 取认证主体，忽略客户端提交")
                assertEquals(1, itemTuples.size, "必须写入一条明细")
                assertEquals("被动关节活动", itemTuples.first().getValue(2))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划创建拒绝非管理员代填created_by`(vertx: Vertx, ctx: VertxTestContext) {
        post(
            vertx,
            "/nursing/v1/child-rehab-plans",
            JsonObject().put("period_id", "period-1").put("plan_name", "训练计划").put("created_by", "other-user"),
        ) { status, response ->
            ctx.verify {
                assertEquals(400, status, "042：非管理员代填操作人必须 400，实际: ${response.encode()}")
                assertEquals("operator cannot be delegated", response.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划挂到非儿童康复周期返回400`(vertx: Vertx, ctx: VertxTestContext) {        planPeriodType = "ELDERLY_CARE"
        post(
            vertx,
            "/nursing/v1/child-rehab-plans",
            JsonObject().put("period_id", "period-1").put("plan_name", "训练计划"),
        ) { status, response ->
            ctx.verify {
                assertEquals(400, status, "非儿童康复周期必须 400，实际: ${response.encode()}")
                assertTrue(response.getString("error")!!.contains("not a child rehab period"), response.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划周期不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        periodFound = false
        post(
            vertx,
            "/nursing/v1/child-rehab-plans",
            JsonObject().put("period_id", "period-1").put("plan_name", "训练计划"),
        ) { status, response ->
            ctx.verify {
                assertEquals(404, status, "实际: ${response.encode()}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划列表按patient_id跨表过滤且空结果形状正确`(vertx: Vertx, ctx: VertxTestContext) {
        get(vertx, "/nursing/v1/child-rehab-plans?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("records")?.size() ?: -1)
                assertEquals(0L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                val listSql = executed.last { it.contains("from nursing.nursing_plans pl") }
                assertTrue(
                    listSql.contains("join nursing.nursing_service_periods pe"),
                    "patient_id 必须走跨表 join：$listSql",
                )
                assertTrue(listSql.contains("pe.patient_id = \$1"), "按 patient_id 过滤必须落到周期表：$listSql")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `计划列表命中返回明细`(vertx: Vertx, ctx: VertxTestContext) {
        countTotal = 1L
        planListRows = listOf(planRow())
        itemsEnabled = true

        get(vertx, "/nursing/v1/child-rehab-plans?period_id=period-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertEquals(1L, body.getJsonObject("meta")?.getLong("total") ?: -1L)
                val record = body.getJsonArray("records")!!.getJsonObject(0)
                assertEquals("patient-1", record.getString("patient_id"))
                assertEquals(1, record.getJsonArray("items")?.size() ?: -1)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun periodBody(): JsonObject = JsonObject()
        .put("patient_id", "patient-1")
        .put("start_date", "2026-10-06")
        .put("end_date", "2026-12-31")

    private fun patientRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
        every { getString("name") } returns "小明"
    }

    private fun statusRow(status: String): Row = mockk(relaxed = true) {
        every { getString("status") } returns status
    }

    private fun longRow(column: String, value: Long): Row = mockk(relaxed = true) {
        every { getLong(column) } returns value
    }

    private fun periodRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "period-1"
        every { getString("patient_id") } returns "patient-1"
        every { getString("patient_name") } returns "小明"
        every { getString("service_type") } returns ChildRehabPeriodService.SERVICE_TYPE
        every { getLocalDate("start_date") } returns LocalDate.parse("2026-10-06")
        every { getLocalDate("end_date") } returns LocalDate.parse("2026-12-31")
        every { getString("coordinator") } returns "rehab01"
        every { getString("status") } returns "ACTIVE"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    /** 计划创建时读取周期（`id, patient_id, service_type`）。 */
    private fun planPeriodRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "period-1"
        every { getString("patient_id") } returns "patient-1"
        every { getString("service_type") } returns planPeriodType
    }

    private fun planRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "plan-1"
        every { getString("period_id") } returns "period-1"
        every { getString("patient_id") } returns "patient-1"
        every { getString("plan_name") } returns "术后下肢训练计划"
        every { getString("goals") } returns "恢复独立步行"
        every { getString("status") } returns "ACTIVE"
        every { getString("created_by") } returns "rehab01"
        every { getLocalDate("start_date") } returns LocalDate.parse("2026-10-06")
        every { getLocalDate("end_date") } returns null
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    private fun itemRow(planId: String): Row = mockk(relaxed = true) {
        every { getString("id") } returns "item-1"
        every { getString("plan_id") } returns planId
        every { getString("action") } returns "被动关节活动"
        every { getString("frequency_code") } returns "QD"
        every { getString("frequency_name") } returns "每日一次"
        every { getInteger("duration_days") } returns 14
        every { getString("remark") } returns null
        every { getString("status") } returns "ACTIVE"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    private fun post(vertx: Vertx, path: String, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, path, body).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun patch(vertx: Vertx, path: String, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PATCH, path, body).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun get(vertx: Vertx, path: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.GET, path, null).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun request(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "127.0.0.1", path)
            .compose { req ->
                req.putHeader("Content-Type", "application/json")
                if (body == null) req.send() else req.send(body.encode())
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer.toString())
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    /** 模拟 App 装配层的认证总闸：先写入 `userId` 再交给护理模块路由。 */
    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val root = Router.router(vertx)
        root.route("/nursing/v1/*").handler { rctx ->
            rctx.put("userId", "rehab01")
            rctx.next()
        }
        root.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool))
        return vertx.createHttpServer().requestHandler(root).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { Unit } }
        }
    }

    private fun record(sql: String) {
        executed.add(normalized(sql))
    }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    private fun rowSet(vararg rows: Row, rowCount: Int = rows.size): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { rowCount() } returns rowCount
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }
}
