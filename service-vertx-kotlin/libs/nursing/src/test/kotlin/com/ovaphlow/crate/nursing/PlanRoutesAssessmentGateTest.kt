package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.util.Collections

/**
 * 照护计划前置校验（029 §2.1-10 / D10）的嵌入式 HTTP 路由测试。
 *
 * 用 mockk 桩 Pool（按 normalized SQL 特征分发行集）启动真实 Vert.x 服务器，
 * 以真实 HTTP 请求验证 `POST /`：
 *   - 该周期/入住 0 条护理评估 → 409（中文可展示消息）且**不插入**任何计划/措施；
 *   - 按 `period_id` 命中 1 条评估 → 201；
 *   - 评估只带 `encounter_id`（V400 `chk_assess_ref` 允许）时按 encounter_id 命中 → 201；
 *   - 已有活动计划时仍 409（既有行为不变），且不再查评估、不插入。
 *
 * 不连接数据库；`PlanService.create` 是 `libs:nursing` 内唯一创建护理计划的通用入口，
 * 内部自动创建走 `createPlanWithItems`（Healthcare 复评修订），不经此门禁。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanRoutesAssessmentGateTest {

    companion object {
        private const val TEST_PORT = 18429
        private const val PERIOD_ID = "01J5D3M00000000000000PLN01"
        private const val ENCOUNTER_ID = "01J5D3M00000000000000ENC01"
    }

    private lateinit var prepared: PreparedQuery<RowSet<Row>>
    private var server: io.vertx.core.http.HttpServer? = null
    private var client: HttpClient? = null

    /** 每个用例设置的桩状态 */
    private var assessmentTotal: Long = 0
    private var hasActivePlan: Boolean = false

    /** 已执行查询的 normalized SQL（按执行顺序），用于断言「不插入」 */
    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        val pool = mockk<Pool>()
        prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } answers {
            executed.add(normalized(firstArg<String>()))
            prepared
        }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            val result = when {
                sql.contains("nursing_assessments") -> Future.succeededFuture(rowSet(countRow(assessmentTotal)))
                sql.contains("insert into") -> Future.succeededFuture(rowSet())
                sql.contains("nursing_plans") -> Future.succeededFuture(
                    if (hasActivePlan) rowSet(planRow()) else rowSet(),
                )
                else -> Future.succeededFuture(rowSet())
            }
            result
        }

        client = vertx.createHttpClient()
        vertx.createHttpServer()
            .requestHandler(PlanRoutes.create(vertx, pool))
            .listen(TEST_PORT)
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
        client?.close()
        val current = server
        if (current == null) {
            ctx.completeNow()
            return
        }
        current.close { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @BeforeEach
    fun reset() {
        assessmentTotal = 0
        hasActivePlan = false
        executed.clear()
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `0条护理评估时创建照护计划返回409且不插入`(vertx: Vertx, ctx: VertxTestContext) {
        assessmentTotal = 0
        post(vertx, planBody().put("period_id", PERIOD_ID))
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "0 条评估必须拒绝创建")
                    assertEquals(
                        "照护计划必须先有护理评估：该服务周期/入住暂无任何评估记录",
                        body.getString("error"),
                        "消息必须中文、可直接展示",
                    )
                    assertFalse(
                        executed.any { it.contains("insert into") },
                        "被拒绝的请求不得插入计划或措施，实际执行: $executed",
                    )
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `按period_id有评估时创建返回201`(vertx: Vertx, ctx: VertxTestContext) {
        assessmentTotal = 1
        post(vertx, planBody().put("period_id", PERIOD_ID))
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "有评估时必须正常创建；实际: $body")
                    assertNotNull(body.getString("id"))
                    assertEquals("ACTIVE", body.getString("status"))
                    assertTrue(
                        executed.any { it.contains("insert into") && it.contains("nursing_plans") },
                        "必须落库插入计划，实际执行: $executed",
                    )
                    val gate = executed.first { it.contains("nursing_assessments") }
                    assertTrue(gate.contains("period_id"), "前置校验必须按 period_id 计数: $gate")
                    assertFalse(gate.contains("encounter_id"), "未提供 encounter_id 时不按入住过滤: $gate")
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `评估只带encounter_id时按入住命中并创建返回201`(vertx: Vertx, ctx: VertxTestContext) {
        assessmentTotal = 1
        post(
            vertx,
            planBody()
                .put("period_id", PERIOD_ID)
                .put("encounter_id", ENCOUNTER_ID),
        ).onSuccess { (status, body) ->
            ctx.verify {
                assertEquals(201, status, "仅按 encounter_id 归属的评估也必须被认可；实际: $body")
                assertEquals(ENCOUNTER_ID, body.getString("encounter_id"))
                val gate = executed.first { it.contains("nursing_assessments") }
                assertTrue(gate.contains("period_id"), "必须同时按 period_id 计数: $gate")
                assertTrue(
                    gate.contains("encounter_id"),
                    "评估允许只带 encounter_id（V400 chk_assess_ref），必须同时按入住计数: $gate",
                )
                assertTrue(gate.contains(" or "), "口径是 period_id 或 encounter_id: $gate")
                ctx.completeNow()
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `已有活动计划时仍返回409且不查评估不插入`(vertx: Vertx, ctx: VertxTestContext) {
        hasActivePlan = true
        assessmentTotal = 1
        post(vertx, planBody().put("period_id", PERIOD_ID))
            .onSuccess { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "既有行为：活动计划冲突仍 409")
                    assertTrue(
                        body.getString("error")!!.contains("already has an active plan"),
                        "既有英文错误串不变；实际: ${body.getString("error")}",
                    )
                    assertFalse(
                        executed.any { it.contains("nursing_assessments") },
                        "活动计划冲突先于评估校验短路，实际执行: $executed",
                    )
                    assertFalse(
                        executed.any { it.contains("insert into") },
                        "不得插入，实际执行: $executed",
                    )
                    ctx.completeNow()
                }
            }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun planBody(): JsonObject =
        JsonObject()
            .put("plan_name", "入住照护计划")
            .put("created_by", "user-1")

    private fun post(
        vertx: Vertx,
        body: JsonObject,
    ): io.vertx.core.Future<Pair<Int, JsonObject>> {
        val shared = client ?: vertx.createHttpClient().also { client = it }
        return shared.request(HttpMethod.POST, TEST_PORT, "localhost", "/")
            .compose { r ->
                r.putHeader("Content-Type", "application/json").send(body.encode())
                    .compose { resp ->
                        resp.body().map { buffer -> Pair(resp.statusCode(), JsonObject(buffer)) }
                    }
            }
    }

    private fun countRow(total: Long): Row =
        mockk(relaxed = true) {
            every { getLong("total") } returns total
        }

    /** 活动计划检查只读 `size()`，行内容不参与判定 */
    private fun planRow(): Row = mockk(relaxed = true) {
        every { getValue("id") } returns "plan-existing"
    }

    private fun rowSet(vararg rows: Row): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")
}
