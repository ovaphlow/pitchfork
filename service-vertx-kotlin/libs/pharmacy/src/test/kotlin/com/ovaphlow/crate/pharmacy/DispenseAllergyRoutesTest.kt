package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
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

/**
 * 054 D3：发药确认的**过敏提醒可判定性**路由护栏（不连数据库）。
 *
 * 锁定：严重提醒未二次确认 → **409**，响应体同时给出 `allergy_warnings[]` 与
 * `requires_acknowledgement: true`，前端无需再调一次 healthcare 检查即可渲染；
 * 且**事务未开启**（无库存/单据副作用）。
 */
@ExtendWith(VertxExtension::class)
class DispenseAllergyRoutesTest {

    private lateinit var pool: Pool
    private lateinit var reader: MedicalOrderReader
    private lateinit var outboundPort: InventoryOutboundPort

    @BeforeEach
    fun setUp() {
        pool = mockk()
        reader = mockk()
        outboundPort = mockk()
    }

    @Test
    fun `严重提醒未确认时返回409且响应体含提醒与确认标记`(vertx: Vertx, ctx: VertxTestContext) {
        stubAllergyInputs("pat-1", "mat-1")
        val port = object : DrugAllergyCheckPort {
            override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> =
                Future.succeededFuture(
                    DrugAllergyCheckResult(
                        drugName = "阿莫西林",
                        drugCode = "DEMO-DRUG-002",
                        warnings = listOf(AllergyWarning("严重", "命中过敏史：青霉素", "过敏史", "青霉素")),
                    ),
                )
        }

        post(vertx, port, JsonObject()) { status, body ->
            ctx.verify {
                assertEquals(409, status, "严重提醒未确认必须 409，实际: ${body.encode()}")
                assertEquals(true, body.getBoolean("requires_acknowledgement"))
                val warnings = body.getJsonArray("allergy_warnings")
                assertEquals(1, warnings?.size() ?: -1, "错误体必须携带提醒明细")
                assertEquals("严重", warnings!!.getJsonObject(0).getString("severity"))
                assertEquals("mat-1", warnings.getJsonObject(0).getString("material_id"))
                assertTrue(
                    (body.getString("error") ?: "").isNotBlank(),
                    "错误体必须保留既有 {error} 形状",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `无提醒时不因新检查改变既有失败语义`(vertx: Vertx, ctx: VertxTestContext) {
        stubAllergyInputs("pat-1", "mat-1")
        val port = object : DrugAllergyCheckPort {
            override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> =
                Future.succeededFuture(DrugAllergyCheckResult(drugName = "阿司匹林"))
        }
        // 事务桩返回非 DISPENSING 状态前的失败路径不参与本用例：直接让事务返回成功结果
        every { pool.withTransaction<JsonObject>(any()) } returns Future.succeededFuture(
            JsonObject().put("id", "d1").put("status", "DISPENSED"),
        )

        post(vertx, port, JsonObject()) { status, body ->
            ctx.verify {
                assertEquals(200, status, "无提醒必须放行，实际: ${body.encode()}")
                assertEquals(0, body.getJsonArray("allergy_warnings")?.size() ?: -1)
                assertEquals(false, body.getBoolean("requires_acknowledgement"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `二次确认后放行`(vertx: Vertx, ctx: VertxTestContext) {
        stubAllergyInputs("pat-1", "mat-1")
        every { pool.withTransaction<JsonObject>(any()) } returns Future.succeededFuture(
            JsonObject().put("id", "d1").put("status", "DISPENSED"),
        )
        val port = object : DrugAllergyCheckPort {
            override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> =
                Future.succeededFuture(
                    DrugAllergyCheckResult(
                        drugName = "阿莫西林",
                        warnings = listOf(AllergyWarning("严重", "命中过敏史：青霉素", "过敏史", "青霉素")),
                    ),
                )
        }

        post(vertx, port, JsonObject().put("acknowledged_warnings", true)) { status, body ->
            ctx.verify {
                assertEquals(200, status, "二次确认后必须放行，实际: ${body.encode()}")
                assertEquals(false, body.getBoolean("requires_acknowledgement"))
                assertEquals(1, body.getJsonArray("allergy_warnings")?.size() ?: -1)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun stubAllergyInputs(patientId: String, materialId: String) {
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } returns pq
        every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(
            rowSet(
                mockk(relaxed = true) {
                    every { getString("patient_id") } returns patientId
                    every { getString("material_id") } returns materialId
                },
            ),
        )
    }

    private fun post(
        vertx: Vertx,
        port: DrugAllergyCheckPort,
        body: JsonObject,
        handler: (Int, JsonObject) -> Unit,
    ) {
        val root = Router.router(vertx)
        root.route("/dispenses*").subRouter(
            DispenseRoutes.create(vertx, pool, reader, outboundPort, null, port),
        )
        vertx.createHttpServer().requestHandler(root).listen(0).compose { server ->
            val client = vertx.createHttpClient()
            client.request(HttpMethod.POST, server.actualPort(), "127.0.0.1", "/dispenses/d1/confirm")
                .compose { req -> req.putHeader("Content-Type", "application/json").send(body.encode()) }
                .compose { resp ->
                    resp.body().map { buffer ->
                        val json = try {
                            JsonObject(buffer.toString())
                        } catch (_: Exception) {
                            JsonObject()
                        }
                        handler(resp.statusCode(), json)
                    }
                }
                .onComplete {
                    client.close()
                    server.close()
                }
        }
    }

    private fun rowSet(vararg rows: Row): RowSet<Row> = mockk(relaxed = true) {
        every { size() } returns rows.size
        every { rowCount() } returns rows.size
        every { iterator() } answers {
            val delegate = rows.iterator()
            mockk<RowIterator<Row>>(relaxed = true) {
                every { hasNext() } answers { delegate.hasNext() }
                every { next() } answers { delegate.next() }
            }
        }
    }
}
