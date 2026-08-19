package com.ovaphlow.crate.pharmacy

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
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.function.Function

/**
 * 013 护理站申领专用 HTTP 路由测试（不连接 PostgreSQL）。
 *
 * 用 mockk 桩驱动 `Pool.preparedQuery` / `Pool.withTransaction`，真实 HTTP 请求覆盖
 * 评审阻断项与契约：认证 401、Idempotency-Key 必填 400、创建 201 与幂等重放 200、
 * 同键不同内容 409、审批超申领量 400、重复审批额外明细 409、非法状态转换 409、
 * 明细不存在 404、错误响应统一 `{ "error": ... }` 与空列表格式。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequisitionRoutesTest {

    companion object {
        private const val PORT = 18433
        private const val BASE_AUTH = "/with-auth"
        private const val BASE_NO_AUTH = "/no-auth"
    }

    private class DatabaseStub {
        var headers: RowSet<Row> = rowSet()
        var items: RowSet<Row> = rowSet()
        var idemRows: RowSet<Row> = rowSet()
        var totalRows: RowSet<Row> = rowSet()

        val connQueries = mutableListOf<String>()
        val connTuples = mutableListOf<Pair<String, List<Any?>>>()

        val pool = mockk<Pool>()
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        private val pq = mockk<io.vertx.sqlclient.PreparedQuery<RowSet<Row>>>()
        private var lastSql = ""

        val inventoryPort = mockk<InventoryRequisitionTransferPort>()

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

            every { inventoryPort.validateRequisitionMaterials(any(), any()) } returns Future.succeededFuture(null)
            every { inventoryPort.reserveStock(any(), any()) } returns Future.succeededFuture(null)
            every { inventoryPort.releaseReservation(any(), any()) } returns Future.succeededFuture(null)
            every { inventoryPort.confirmReservedTransfer(any(), any()) } answers {
                @Suppress("UNCHECKED_CAST")
                val cmd = secondArg<RequisitionTransferCommand>()
                val results = cmd.items.map {
                    RequisitionTransferItemResult(
                        materialId = it.materialId,
                        lotId = it.lotId,
                        outboundStockOperationDetailId = "out-${it.materialId}",
                        inboundStockOperationDetailId = "in-${it.materialId}",
                        unitCost = BigDecimal("2.5"),
                    )
                }
                Future.succeededFuture(
                    RequisitionTransferResult(
                        outboundOperationId = "op-out",
                        inboundOperationId = "op-in",
                        items = results,
                    ),
                )
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            connQueries.add(lastSql)
        }

        private fun routeSql(sql: String): Future<RowSet<Row>> {
            val branch = when {
                sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete") -> "write"
                sql.contains("select count") -> "total"
                sql.contains("request_fingerprint") -> "idem"
                sql.contains("pharmacy_requisition_items") -> "items"
                sql.contains("pharmacy_requisitions") -> "headers"
                else -> "else"
            }
            val result = when (branch) {
                "write", "else" -> rowSet()
                "total" -> totalRows
                "idem" -> idemRows
                "items" -> items
                "headers" -> headers
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
            ctx.put("userId", "req-user")
            ctx.next()
        }
    }

    private var server: io.vertx.core.http.HttpServer? = null

    @BeforeEach
    fun resetTraffic() {
        stub.connQueries.clear()
        stub.connTuples.clear()
        stub.headers = rowSet()
        stub.items = rowSet()
        stub.idemRows = rowSet()
        stub.totalRows = rowSet()
    }

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        // 镜像 PharmacyRoutes 的挂载：`/requisitions/*` 前缀剥离后进入 RequisitionRoutes 的 `/` 路由
        val routerWithAuth = Router.router(vertx)
        routerWithAuth.route("/requisitions/*").subRouter(
            RequisitionRoutes.create(vertx, stub.pool, stub.inventoryPort, fakeAuthHandler),
        )
        val routerNoAuth = Router.router(vertx)
        routerNoAuth.route("/requisitions/*").subRouter(
            RequisitionRoutes.create(vertx, stub.pool, stub.inventoryPort, Handler { ctx -> ctx.next() }),
        )
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
                        } catch (e: Exception) {
                            JsonObject().put("raw", buffer.toString())
                        }
                    } else {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
    }

    private fun headerRow(
        id: String,
        status: String,
        approvedBy: String? = null,
    ): Row = mockRow(
        mapOf(
            "id" to id,
            "requisition_no" to "PH-REQ-$id",
            "warehouse" to "药房西药库",
            "destination_warehouse" to "一护理站库存",
            "department" to "一护理站",
            "status" to status,
            "requester" to "护士A",
            "requester_id" to "req-user",
            "metadata" to null,
            "created_at" to OffsetDateTime.now(),
            "dispensed_at" to null,
            "approved_by" to approvedBy,
            "approved_at" to (if (approvedBy != null) OffsetDateTime.now() else null),
            "dispensed_by" to null,
            "cancelled_by" to null,
            "cancelled_at" to null,
            "cancel_reason" to null,
            "updated_at" to OffsetDateTime.now(),
        ),
    )

    private fun itemRow(
        id: String,
        materialId: String = "mat-$id",
        requested: String = "10",
        approved: String? = null,
        lotId: String? = null,
    ): Row = mockRow(
        mapOf(
            "id" to id,
            "requisition_id" to "req-1",
            "material_id" to materialId,
            "requested_quantity" to BigDecimal(requested),
            "approved_quantity" to approved?.let { BigDecimal(it) },
            "dispensed_quantity" to null,
            "stock_operation_detail_id" to null,
            "lot_id" to lotId,
            "outbound_stock_operation_detail_id" to null,
            "inbound_stock_operation_detail_id" to null,
            "metadata" to null,
        ),
    )

    private fun createBody(): JsonObject = JsonObject()
        .put("warehouse", "药房西药库")
        .put("destination_warehouse", "一护理站库存")
        .put("department", "一护理站")
        .put(
            "items",
            io.vertx.core.json.JsonArray()
                .add(JsonObject().put("material_id", "mat-1").put("requested_quantity", "10")),
        )

    private fun idemRow(fingerprint: String = "fp-1"): Row = mockRow(
        mapOf(
            "id" to "req-1",
            "fingerprint" to fingerprint,
        ),
    )

    /** 与服务端 requestFingerprint 一致的规范化摘要：用于幂等重放用例构造已存储指纹 */
    private fun expectedFingerprint(body: JsonObject): String {
        val canonical = JsonObject()
            .put("warehouse", body.getString("warehouse"))
            .put("destination_warehouse", body.getString("destination_warehouse"))
            .put("department", body.getString("department"))
            .put(
                "items",
                io.vertx.core.json.JsonArray().apply {
                    for (i in 0 until body.getJsonArray("items").size()) {
                        val item = body.getJsonArray("items").getJsonObject(i)
                        add(
                            JsonObject()
                                .put("material_id", item.getString("material_id"))
                                .put(
                                    "requested_quantity",
                                    BigDecimal(item.getString("requested_quantity")).stripTrailingZeros().toPlainString(),
                                ),
                        )
                    }
                },
            )
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ========================================================================
    //  认证与请求体
    // ========================================================================

    @Test
    fun `create without auth returns 401`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "$BASE_NO_AUTH/requisitions/",
            createBody(),
            mapOf("Idempotency-Key" to "key-1"),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(401, ar.result().first)
                assertEquals("authentication required", ar.result().second.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `create without idempotency key returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.POST, "$BASE_AUTH/requisitions/", createBody()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(400, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("Idempotency-Key"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `create with numeric quantity returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        val body = JsonObject()
            .put("warehouse", "药房西药库")
            .put("destination_warehouse", "一护理站库存")
            .put("department", "一护理站")
            .put(
                "items",
                io.vertx.core.json.JsonArray().add(JsonObject().put("material_id", "mat-1").put("requested_quantity", 10)),
            )
        request(vertx, HttpMethod.POST, "$BASE_AUTH/requisitions/", body, mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(400, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("decimal text"))
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  创建与幂等
    // ========================================================================

    @Test
    fun `create returns 201 with new requisition`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DRAFT"))
        stub.items = rowSet(itemRow("item-1"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/requisitions/", createBody(), mapOf("Idempotency-Key" to "key-new"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(201, ar.result().first)
                    assertEquals("req-1", ar.result().second.getString("id"))
                    assertEquals("DRAFT", ar.result().second.getString("status"))
                    assertTrue(stub.connQueries.any { it.contains("request_fingerprint") })
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create with same key and same content replays 200`(vertx: Vertx, ctx: VertxTestContext) {
        stub.idemRows = rowSet(idemRow(fingerprint = expectedFingerprint(createBody())))
        stub.headers = rowSet(headerRow("req-1", "DRAFT"))
        stub.items = rowSet(itemRow("item-1"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/requisitions/", createBody(), mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(200, ar.result().first)
                    assertEquals("req-1", ar.result().second.getString("id"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create with same key but different content returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.idemRows = rowSet(idemRow(fingerprint = "different-fp"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/requisitions/", createBody(), mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(409, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("different request"))
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  审批
    // ========================================================================

    @Test
    fun `approve exceeding requested quantity returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DRAFT"))
        stub.items = rowSet(itemRow("item-1", requested = "10"))
        val body = JsonObject("""{"items":[{"id":"item-1","approved_quantity":"12","lot_id":null}]}""")
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/approve", body).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(400, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("must not exceed requested_quantity"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `approve with extra unknown item returns 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DRAFT"))
        stub.items = rowSet(itemRow("item-1", requested = "10"))
        val body = JsonObject(
            """{"items":[{"id":"item-1","approved_quantity":"8","lot_id":null},{"id":"item-999","approved_quantity":"1","lot_id":null}]}""",
        )
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/approve", body).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(404, ar.result().first)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `approve not covering every item returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DRAFT"))
        stub.items = rowSet(itemRow("item-1", requested = "10"), itemRow("item-2", requested = "5"))
        val body = JsonObject("""{"items":[{"id":"item-1","approved_quantity":"8","lot_id":null}]}""")
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/approve", body).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(400, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("cover every"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `re-approve with extra item returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "APPROVED", approvedBy = "req-user"))
        stub.items = rowSet(itemRow("item-1", requested = "10", approved = "8"))
        val body = JsonObject(
            """{"items":[{"id":"item-1","approved_quantity":"8","lot_id":null},{"id":"item-999","approved_quantity":"1","lot_id":null}]}""",
        )
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/approve", body).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("different approval set"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `re-approve by different user returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "APPROVED", approvedBy = "other-user"))
        val body = JsonObject("""{"items":[{"id":"item-1","approved_quantity":"8","lot_id":null}]}""")
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/approve", body).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("different user"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `approve on dispensed requisition returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DISPENSED"))
        val body = JsonObject("""{"items":[{"id":"item-1","approved_quantity":"8","lot_id":null}]}""")
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/approve", body).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("cannot approve"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  确认调拨与取消
    // ========================================================================

    @Test
    fun `dispense on draft returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DRAFT"))
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/dispense", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("cannot dispense"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `dispense approved returns 200 with dual detail ids`(vertx: Vertx, ctx: VertxTestContext) {
        // DISPENSED 重试路径：路由返回已持久化的双向明细 ID（幂等，不重复调库存端口）
        stub.headers = rowSet(headerRow("req-1", "DISPENSED", approvedBy = "req-user"))
        stub.items = rowSet(
            mockRow(
                mapOf(
                    "id" to "item-1",
                    "requisition_id" to "req-1",
                    "material_id" to "mat-item-1",
                    "requested_quantity" to BigDecimal("10"),
                    "approved_quantity" to BigDecimal("8"),
                    "dispensed_quantity" to BigDecimal("8"),
                    "stock_operation_detail_id" to null,
                    "lot_id" to "lot-1",
                    "outbound_stock_operation_detail_id" to "out-mat-item-1",
                    "inbound_stock_operation_detail_id" to "in-mat-item-1",
                    "metadata" to null,
                ),
            ),
        )
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/dispense", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(200, ar.result().first)
                assertEquals("DISPENSED", ar.result().second.getString("status"))
                val items = ar.result().second.getJsonArray("items")
                assertEquals("out-mat-item-1", items.getJsonObject(0).getString("outbound_stock_operation_detail_id"))
                assertEquals("in-mat-item-1", items.getJsonObject(0).getString("inbound_stock_operation_detail_id"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `cancel requires reason returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/requisitions/req-1/cancel", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(400, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("reason"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `cancel dispensed returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(headerRow("req-1", "DISPENSED"))
        request(
            vertx,
            HttpMethod.PUT,
            "$BASE_AUTH/requisitions/req-1/cancel",
            JsonObject().put("reason", "取消"),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("cannot cancel"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  列表与 404
    // ========================================================================

    @Test
    fun `list returns records and meta total even when empty`(vertx: Vertx, ctx: VertxTestContext) {
        stub.totalRows = rowSet(mockRow(mapOf("total" to 0L)))
        request(vertx, HttpMethod.GET, "$BASE_AUTH/requisitions/").onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(200, ar.result().first)
                assertEquals(0, ar.result().second.getJsonArray("records").size())
                assertEquals(0L, ar.result().second.getJsonObject("meta").getLong("total"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `get missing requisition returns 404`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "$BASE_AUTH/requisitions/req-999").onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(404, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("not found"))
                ctx.completeNow()
            }
        }
    }
}

// ========================================================================
//  顶层辅助（与既有非数据库测试一致的 mockk 行构造）
// ========================================================================

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    val ordered = listOf(
        values["id"],
        values["fingerprint"],
        values["requisition_no"],
        values["warehouse"],
        values["destination_warehouse"],
        values["department"],
        values["status"],
        values["requester"],
        values["requester_id"],
        values["metadata"],
        values["created_at"],
        values["dispensed_at"],
        values["approved_by"],
        values["approved_at"],
        values["dispensed_by"],
        values["cancelled_by"],
        values["cancelled_at"],
        values["cancel_reason"],
        values["updated_at"],
        values["requisition_id"],
        values["material_id"],
        values["requested_quantity"],
        values["approved_quantity"],
        values["dispensed_quantity"],
        values["stock_operation_detail_id"],
        values["lot_id"],
        values["outbound_stock_operation_detail_id"],
        values["inbound_stock_operation_detail_id"],
    )
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getValue(any<Int>()) } answers {
        val idx = firstArg<Int>()
        if (idx in ordered.indices) ordered[idx] else null
    }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? java.time.LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getBoolean(any<String>()) } answers { values[firstArg<String>()] as? Boolean }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<io.vertx.sqlclient.RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: io.vertx.sqlclient.Tuple): List<Any?> {
    val result = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) {
        result.add(tuple.getValue(i))
    }
    return result
}