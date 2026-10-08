package com.ovaphlow.crate.dining

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.VertxException
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
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
import java.util.Collections

/**
 * 051 §3 D2 / §5.4：膳食建档唯一约束冲突的**不连数据库**路由级护栏。
 *
 * 背景：`DiningRoutesIntegrationTest` 里没有重复建档用例，服务级的 409 用例又被
 * `@EnabledIfSystemProperty` 门控，默认验证永远跑不到 → 唯一冲突退化成 500 也无人发现。
 *
 * 这里用 mockk 桩 `Pool`：面向 `healthcare.encounters` 的校验查询返回一条在院诊断行，
 * 面向 `dining.dining_diet_profiles` 的 INSERT 直接失败。分四种失败形态锁定语义：
 * 1. 原始 `PgException(23505)` → 409（既有分支，防回归）；
 * 2. 23505 被包进 `cause` 链（`VertxException` 包裹）→ 409（051 D2 分支 A：此前漏判的真实风险）；
 * 3. `sqlState` 不是 23505、但消息里带唯一索引名 → 409（051 D2 分支 B：约束名兜底）；
 * 4. 与唯一约束无关的失败 → 500，**不得**被助手误判成 409。
 */
@ExtendWith(VertxExtension::class)
class DiningRoutesConflictTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    /** 本次 INSERT 注入的失败（null = 不该走到失败分支）。 */
    private var insertFailure: Throwable? = null

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        executed.clear()
        insertFailure = null
        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("insert into dining.dining_diet_profiles") -> {
                    val failure = insertFailure
                    if (failure != null) Future.failedFuture(failure)
                    else Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("from healthcare.encounters") -> Future.succeededFuture(rowSet(encounterRow()))
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  用例
    // ========================================================================

    @Test
    fun `重复建档命中唯一约束返回409与中文文案`(vertx: Vertx, ctx: VertxTestContext) {
        insertFailure = PgException(
            "duplicate key value violates unique constraint \"uq_dining_diet_profiles_active_patient\"",
            "ERROR",
            "23505",
            "Key (patient_id)=(patient-1) already exists.",
        )

        postDietProfile(vertx) { status, body ->
            ctx.verify {
                assertEquals(409, status, "唯一冲突必须 409，实际: ${body.encode()}")
                assertEquals("该长者已有启用的饮食档案", body.getString("error"))
                assertTrue(
                    executed.any { it.contains("insert into dining.dining_diet_profiles") },
                    "必须真的执行过 INSERT，实际: $executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `唯一约束被包装在cause链中仍返回409`(vertx: Vertx, ctx: VertxTestContext) {
        // 051 D2 分支 A：真实环境里异常可能被上层包裹，单层 `error is PgException` 会漏判 → 500。
        insertFailure = VertxException(
            "insert failed",
            PgException("duplicate key value violates unique constraint", "ERROR", "23505", "duplicate"),
        )

        postDietProfile(vertx) { status, body ->
            ctx.verify {
                assertEquals(409, status, "被包裹的 23505 必须仍映射为 409，实际: ${body.encode()}")
                assertEquals("该长者已有启用的饮食档案", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `sqlState非23505时按约束名兜底返回409`(vertx: Vertx, ctx: VertxTestContext) {
        // 051 D2 分支 B：驱动未给稳定 sqlState 时，靠约束名识别同一冲突。
        insertFailure = PgException(
            "duplicate key value violates unique constraint \"uq_dining_diet_profiles_active_patient\"",
            "ERROR",
            "23000",
            "Key (patient_id)=(patient-1) already exists.",
        )

        postDietProfile(vertx) { status, body ->
            ctx.verify {
                assertEquals(409, status, "约束名命中必须 409，实际: ${body.encode()}")
                assertEquals("该长者已有启用的饮食档案", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `与唯一约束无关的失败仍返回500`(vertx: Vertx, ctx: VertxTestContext) {
        insertFailure = IllegalStateException("connection reset")

        postDietProfile(vertx) { status, body ->
            ctx.verify {
                assertEquals(500, status, "无关失败不得被误判为冲突，实际: ${body.encode()}")
                assertEquals("internal error", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `allergies传成字符串不再500且重复建档仍409`(vertx: Vertx, ctx: VertxTestContext) {
        // 051 真实根因回归：直接调接口的客户端把 allergies 传成字符串时，
        // 旧的 body.getJsonArray("allergies") 会抛 ClassCastException → 500，
        // 把真正的唯一冲突完全掩盖。修复后字符串按逗号拆分，冲突照常 409。
        insertFailure = PgException(
            "duplicate key value violates unique constraint \"uq_dining_diet_profiles_active_patient\"",
            "ERROR",
            "23505",
            "Key (patient_id)=(patient-1) already exists.",
        )

        postDietProfile(vertx, dietProfileBody().put("allergies", "花生，海鲜")) { status, body ->
            ctx.verify {
                assertEquals(409, status, "字符串 allergies 不得再退化成 500，实际: ${body.encode()}")
                assertEquals("该长者已有启用的饮食档案", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `allergies类型不合法返回400而非500`(vertx: Vertx, ctx: VertxTestContext) {
        postDietProfile(vertx, dietProfileBody().put("allergies", 123)) { status, body ->
            ctx.verify {
                assertEquals(400, status, "非法类型必须是 400，实际: ${body.encode()}")
                assertEquals("allergies 必须是字符串数组，或逗号分隔的字符串", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun postDietProfile(
        vertx: Vertx,
        body: JsonObject = dietProfileBody(),
        handler: (Int, JsonObject) -> Unit,
    ) {
        withServer(vertx) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/dining/v1/diet-profiles", body)
                .map { (status, body) -> handler(status, body) }
        }.onFailure { /* 断言失败在 ctx 内已登记；这里仅兜住 HTTP/服务端异常 */ }
    }

    private fun dietProfileBody(): JsonObject = JsonObject()
        .put("patient_id", PATIENT_ID)
        .put("encounter_id", ENCOUNTER_ID)
        .put("meal_type", "普食")

    private fun encounterRow(): Row = mockk(relaxed = true) {
        every { getString("encounter_type") } returns "ELDERLY_CARE"
        every { getString("status") } returns "ACTIVE"
        every { getString("enc_patient_id") } returns PATIENT_ID
    }

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "127.0.0.1", path)
            .compose { req ->
                req.putHeader("Content-Type", "application/json").send(body.encode())
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

    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val authHandler = Handler<RoutingContext> { rctx ->
            rctx.put("userId", "dietitian-1")
            rctx.next()
        }
        val root = Router.router(vertx)
        root.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, authHandler))
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

    private companion object {
        const val PATIENT_ID = "patient-1"
        const val ENCOUNTER_ID = "enc-1"
    }
}
