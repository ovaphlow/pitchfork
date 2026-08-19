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
 * 014 药房采购与供应商收货专用 HTTP 路由测试（不连接 PostgreSQL）。
 *
 * 用 mockk 桩驱动 `Pool.preparedQuery` / `Pool.withTransaction`，真实 HTTP 请求覆盖
 * 评审阻断项与契约：认证 401、Idempotency-Key 必填 400、创建 201 与幂等重放 200、
 * 同键不同内容 409、并发唯一冲突（23505）恢复为 200 重放而非 500、审核状态机 409、
 * 取消缺理由 400、收货 201/重放 200/同键异内容 409/超额 409、空列表格式与 404。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseOrdersRoutesTest {

    companion object {
        private const val PORT = 18434
        private const val BASE_AUTH = "/with-auth"
        private const val BASE_NO_AUTH = "/no-auth"
    }

    private class DatabaseStub {
        var headers: RowSet<Row> = rowSet()
        var items: RowSet<Row> = rowSet()
        var receipts: RowSet<Row> = rowSet()
        var receiptItems: RowSet<Row> = rowSet()
        var idemRows: RowSet<Row> = rowSet()
        var receiptIdemRows: RowSet<Row> = rowSet()
        var totalRows: RowSet<Row> = rowSet()

        /** 为 true 时，首个幂等键 INSERT 抛 23505（模拟并发同键第二个事务） */
        var simulateIdemConflict: Boolean = false

        val connQueries = mutableListOf<String>()
        val connTuples = mutableListOf<Pair<String, List<Any?>>>()

        val pool = mockk<Pool>()
        val conn = mockk<io.vertx.sqlclient.SqlConnection>()
        private val pq = mockk<io.vertx.sqlclient.PreparedQuery<RowSet<Row>>>()
        private var lastSql = ""

        val inventoryPort = mockk<InventoryPurchaseReceiptPort>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.withTransaction(any<Function<io.vertx.sqlclient.SqlConnection, Future<Any>>>()) } answers {
                @Suppress("UNCHECKED_CAST")
                val fn = firstArg<Function<io.vertx.sqlclient.SqlConnection, Future<Any>>>()
                val outcome = fn.apply(conn)
                if (simulateIdemConflict) {
                    Future.failedFuture(io.vertx.pgclient.PgException("idem conflict", "42501", "23505", "duplicate"))
                } else {
                    outcome
                }
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

            every { inventoryPort.validatePurchaseMaterials(any(), any()) } returns Future.succeededFuture(null)
            every { inventoryPort.confirmPurchaseReceipt(any(), any()) } answers {
                @Suppress("UNCHECKED_CAST")
                val cmd = secondArg<PurchaseReceiptCommand>()
                val results = cmd.items.map {
                    PurchaseReceiptItemResult(
                        receiptItemId = it.receiptItemId,
                        materialId = it.materialId,
                        batchNo = it.batchNo,
                        lotId = if (it.batchNo != null) "lot-${it.materialId}" else null,
                        stockOperationDetailId = "detail-${it.materialId}",
                        unitCost = it.unitCost,
                        totalCost = it.unitCost.multiply(it.quantity),
                    )
                }
                Future.succeededFuture(PurchaseReceiptResult(stockOperationId = "op-1", items = results))
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
                sql.contains("request_fingerprint") && sql.contains("purchase_receipts") -> "receiptIdem"
                sql.contains("request_fingerprint") && sql.contains("purchase_orders") -> "idem"
                sql.contains("purchase_receipt_items") -> "receiptItems"
                sql.contains("purchase_receipts") -> "receipts"
                sql.contains("purchase_order_items") -> "items"
                sql.contains("purchase_orders") -> "headers"
                else -> "else"
            }
            val result = when (branch) {
                "write", "else" -> rowSet()
                "total" -> totalRows
                "receiptItems" -> receiptItems
                "receipts" -> receipts
                "idem" -> idemRows
                "receiptIdem" -> receiptIdemRows
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
            ctx.put("userId", "pharm-user")
            ctx.next()
        }
    }

    private var server: io.vertx.core.http.HttpServer? = null

    @BeforeEach
    fun resetTraffic() {
        stub.connQueries.clear()
        stub.connTuples.clear()
        stub.simulateIdemConflict = false
        stub.headers = rowSet()
        stub.items = rowSet()
        stub.receipts = rowSet()
        stub.receiptItems = rowSet()
        stub.idemRows = rowSet()
        stub.receiptIdemRows = rowSet()
        stub.totalRows = rowSet()
    }

    @BeforeAll
    fun setup(vertx: Vertx, ctx: VertxTestContext) {
        // 镜像 PharmacyRoutes 的挂载：`/purchase-orders/*`、`/purchase-receipts/*` 前缀剥离
        val routerWithAuth = Router.router(vertx)
        val (orderRouter, receiptRouter) = PurchaseOrderRoutes.create(vertx, stub.pool, stub.inventoryPort, fakeAuthHandler)
        routerWithAuth.route("/purchase-orders/*").subRouter(orderRouter)
        routerWithAuth.route("/purchase-receipts/*").subRouter(receiptRouter)
        val routerNoAuth = Router.router(vertx)
        val (orderRouterNoAuth, receiptRouterNoAuth) = PurchaseOrderRoutes.create(
            vertx,
            stub.pool,
            stub.inventoryPort,
            Handler { ctx -> ctx.next() },
        )
        routerNoAuth.route("/purchase-orders/*").subRouter(orderRouterNoAuth)
        routerNoAuth.route("/purchase-receipts/*").subRouter(receiptRouterNoAuth)
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

    private fun orderHeaderRow(id: String, status: String): Row = mockRow(
        mapOf(
            "id" to id,
            "purchase_order_no" to "PH-PO-$id",
            "warehouse" to "药房西药库",
            "supplier_name" to "华康医药配送",
            "status" to status,
            "requester_id" to "pharm-user",
            "approved_by" to null,
            "approved_at" to null,
            "cancelled_by" to null,
            "cancelled_at" to null,
            "cancel_reason" to null,
            "closed_by" to null,
            "closed_at" to null,
            "close_reason" to null,
            "created_at" to OffsetDateTime.now(),
            "updated_at" to OffsetDateTime.now(),
        ),
    )

    private fun orderItemRow(
        id: String,
        materialId: String = "mat-$id",
        ordered: String = "100",
        received: String = "0",
    ): Row = mockRow(
        mapOf(
            "id" to id,
            "purchase_order_id" to "po-1",
            "material_id" to materialId,
            "ordered_quantity" to BigDecimal(ordered),
            "received_quantity" to BigDecimal(received),
        ),
    )

    private fun receiptRow(id: String): Row = mockRow(
        mapOf(
            "id" to id,
            "receipt_no" to "PH-REC-$id",
            "purchase_order_id" to "po-1",
            "warehouse" to "药房西药库",
            "supplier_name" to "华康医药配送",
            "received_by" to "pharm-user",
            "received_at" to OffsetDateTime.now(),
            "stock_operation_id" to "op-1",
            "created_at" to OffsetDateTime.now(),
        ),
    )

    private fun receiptItemRow(id: String, materialId: String = "mat-po-1"): Row = mockRow(
        mapOf(
            "id" to id,
            "receipt_id" to "rec-1",
            "purchase_order_item_id" to "poi-1",
            "material_id" to materialId,
            "lot_id" to "lot-$materialId",
            "received_quantity" to BigDecimal("10"),
            "unit_cost" to BigDecimal("1.25"),
            "total_cost" to BigDecimal("12.5"),
            "stock_operation_detail_id" to "detail-$materialId",
        ),
    )

    private fun orderBody(): JsonObject = JsonObject()
        .put("warehouse", "药房西药库")
        .put("supplier_name", "华康医药配送")
        .put(
            "items",
            io.vertx.core.json.JsonArray()
                .add(JsonObject().put("material_id", "mat-1").put("ordered_quantity", "100")),
        )

    private fun receiptBody(): JsonObject = JsonObject()
        .put(
            "items",
            io.vertx.core.json.JsonArray().add(
                JsonObject()
                    .put("purchase_order_item_id", "poi-1")
                    .put("received_quantity", "10")
                    .put("unit_cost", "1.25"),
            ),
        )

    private fun idemRow(fingerprint: String = "fp-1"): Row = mockRow(
        mapOf("id" to "po-1", "fingerprint" to fingerprint),
    )

    private fun receiptIdemRow(fingerprint: String = "fp-1"): Row = mockRow(
        mapOf("id" to "rec-1", "fingerprint" to fingerprint),
    )

    /** 与服务端 orderFingerprint 一致的规范化摘要：用于幂等重放用例构造已存储指纹 */
    private fun expectedOrderFingerprint(body: JsonObject): String {
        val sortedItems = (0 until body.getJsonArray("items").size())
            .map { body.getJsonArray("items").getJsonObject(it) }
            .sortedWith(compareBy({ it.getString("material_id") }))
        val canonical = JsonObject()
            .put("warehouse", body.getString("warehouse"))
            .put("supplier_name", body.getString("supplier_name"))
            .put(
                "items",
                io.vertx.core.json.JsonArray().apply {
                    for (item in sortedItems) {
                        add(
                            JsonObject()
                                .put("material_id", item.getString("material_id"))
                                .put(
                                    "ordered_quantity",
                                    BigDecimal(item.getString("ordered_quantity")).stripTrailingZeros().toPlainString(),
                                ),
                        )
                    }
                },
            )
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonical.encode().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 与服务端 receiptFingerprint 一致的规范化摘要（单订单项/单行简化） */
    private fun expectedReceiptFingerprint(orderId: String, body: JsonObject): String {
        val line = body.getJsonArray("items").getJsonObject(0)
        val canonical = JsonObject()
            .put("purchase_order_id", orderId)
            .put(
                "items",
                io.vertx.core.json.JsonArray().add(
                    JsonObject()
                        .put("purchase_order_item_id", line.getString("purchase_order_item_id"))
                        .put("received_quantity", BigDecimal(line.getString("received_quantity")).stripTrailingZeros().toPlainString())
                        .put("batch_no", line.getString("batch_no"))
                        .put("production_date", line.getString("production_date"))
                        .put("expiry_date", line.getString("expiry_date"))
                        .put("manufacturer", line.getString("manufacturer"))
                        .put("unit_cost", BigDecimal(line.getString("unit_cost")).stripTrailingZeros().toPlainString()),
                ),
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
            "$BASE_NO_AUTH/purchase-orders/",
            orderBody(),
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
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", orderBody()).onComplete { ar ->
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
            .put("supplier_name", "华康医药配送")
            .put(
                "items",
                io.vertx.core.json.JsonArray().add(JsonObject().put("material_id", "mat-1").put("ordered_quantity", 100)),
            )
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", body, mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(400, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("decimal text"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create rejects unknown fields returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        val body = orderBody().put("unit", "PACKAGE")
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", body, mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(400, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("unknown fields"))
                    ctx.completeNow()
                }
            }
    }

    // ========================================================================
    //  创建与幂等
    // ========================================================================

    @Test
    fun `create returns 201 with new order`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "DRAFT"))
        stub.items = rowSet(orderItemRow("poi-1"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", orderBody(), mapOf("Idempotency-Key" to "key-new"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(201, ar.result().first)
                    assertEquals("po-1", ar.result().second.getString("id"))
                    assertEquals("DRAFT", ar.result().second.getString("status"))
                    assertTrue(stub.connQueries.any { it.contains("request_fingerprint") })
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create with same key and same content replays 200`(vertx: Vertx, ctx: VertxTestContext) {
        stub.idemRows = rowSet(idemRow(fingerprint = expectedOrderFingerprint(orderBody())))
        stub.headers = rowSet(orderHeaderRow("po-1", "DRAFT"))
        stub.items = rowSet(orderItemRow("poi-1"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", orderBody(), mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(200, ar.result().first)
                    assertEquals("po-1", ar.result().second.getString("id"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create with same key but different content returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.idemRows = rowSet(idemRow(fingerprint = "different-fp"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", orderBody(), mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(409, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("different request"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create on concurrent idempotency conflict replays 200 instead of 500`(vertx: Vertx, ctx: VertxTestContext) {
        // 模拟并发同键第二个事务：INSERT 命中唯一索引抛 23505，recover 后回读比对指纹返回 200
        stub.simulateIdemConflict = true
        stub.idemRows = rowSet(idemRow(fingerprint = expectedOrderFingerprint(orderBody())))
        stub.headers = rowSet(orderHeaderRow("po-1", "DRAFT"))
        stub.items = rowSet(orderItemRow("poi-1"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", orderBody(), mapOf("Idempotency-Key" to "key-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(200, ar.result().first)
                    assertEquals("po-1", ar.result().second.getString("id"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `create on concurrent idempotency conflict with different content returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.simulateIdemConflict = true
        stub.idemRows = rowSet(idemRow(fingerprint = "different-fp"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/", orderBody(), mapOf("Idempotency-Key" to "key-1"))
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
    //  状态机动作
    // ========================================================================

    @Test
    fun `approve draft returns 200`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "DRAFT"))
        stub.items = rowSet(orderItemRow("poi-1"))
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/purchase-orders/po-1/approve", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(200, ar.result().first)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `approve on received returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "RECEIVED"))
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/purchase-orders/po-1/approve", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("cannot approve"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `cancel requires reason returns 400`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.PUT, "$BASE_AUTH/purchase-orders/po-1/cancel", JsonObject()).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(400, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("reason"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `cancel approved with receipts returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "APPROVED"))
        stub.items = rowSet(orderItemRow("poi-1", received = "10"))
        request(
            vertx,
            HttpMethod.PUT,
            "$BASE_AUTH/purchase-orders/po-1/cancel",
            JsonObject().put("reason", "供应商断货"),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("close the remaining"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `close fully received returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "APPROVED"))
        stub.items = rowSet(orderItemRow("poi-1", received = "100"))
        request(
            vertx,
            HttpMethod.PUT,
            "$BASE_AUTH/purchase-orders/po-1/close",
            JsonObject().put("reason", "提前结束"),
        ).onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(409, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("fully received"))
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  收货
    // ========================================================================

    @Test
    fun `receive returns 201`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "APPROVED"))
        stub.items = rowSet(orderItemRow("poi-1", ordered = "100", received = "0"))
        stub.receipts = rowSet(receiptRow("rec-1"))
        stub.receiptItems = rowSet(receiptItemRow("rec-item-1"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/po-1/receipts", receiptBody(), mapOf("Idempotency-Key" to "key-rec-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(201, ar.result().first)
                    assertEquals("rec-1", ar.result().second.getString("id"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `receive with same key and same content replays 200`(vertx: Vertx, ctx: VertxTestContext) {
        stub.receiptIdemRows = rowSet(receiptIdemRow(fingerprint = expectedReceiptFingerprint("po-1", receiptBody())))
        stub.receipts = rowSet(receiptRow("rec-1"))
        stub.receiptItems = rowSet(receiptItemRow("rec-item-1"))
        stub.headers = rowSet(orderHeaderRow("po-1", "PARTIALLY_RECEIVED"))
        stub.items = rowSet(orderItemRow("poi-1", ordered = "100", received = "10"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/po-1/receipts", receiptBody(), mapOf("Idempotency-Key" to "key-rec-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(200, ar.result().first)
                    assertEquals("rec-1", ar.result().second.getString("id"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `receive with same key but different content returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.receiptIdemRows = rowSet(receiptIdemRow(fingerprint = "different-fp"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/po-1/receipts", receiptBody(), mapOf("Idempotency-Key" to "key-rec-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(409, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("different request"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `receive over ordered quantity returns 409`(vertx: Vertx, ctx: VertxTestContext) {
        stub.headers = rowSet(orderHeaderRow("po-1", "APPROVED"))
        stub.items = rowSet(orderItemRow("poi-1", ordered = "100", received = "95"))
        val body = JsonObject()
            .put(
                "items",
                io.vertx.core.json.JsonArray().add(
                    JsonObject()
                        .put("purchase_order_item_id", "poi-1")
                        .put("received_quantity", "10")
                        .put("unit_cost", "1.25"),
                ),
            )
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/po-1/receipts", body, mapOf("Idempotency-Key" to "key-rec-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(409, ar.result().first)
                    assertTrue(ar.result().second.getString("error")!!.contains("over-receipt"))
                    ctx.completeNow()
                }
            }
    }

    @Test
    fun `receive on concurrent idempotency conflict replays 200 instead of 500`(vertx: Vertx, ctx: VertxTestContext) {
        stub.simulateIdemConflict = true
        stub.receiptIdemRows = rowSet(receiptIdemRow(fingerprint = expectedReceiptFingerprint("po-1", receiptBody())))
        stub.receipts = rowSet(receiptRow("rec-1"))
        stub.receiptItems = rowSet(receiptItemRow("rec-item-1"))
        stub.headers = rowSet(orderHeaderRow("po-1", "PARTIALLY_RECEIVED"))
        stub.items = rowSet(orderItemRow("poi-1", ordered = "100", received = "10"))
        request(vertx, HttpMethod.POST, "$BASE_AUTH/purchase-orders/po-1/receipts", receiptBody(), mapOf("Idempotency-Key" to "key-rec-1"))
            .onComplete { ar ->
                ctx.verify {
                    assertTrue(ar.succeeded())
                    assertEquals(200, ar.result().first)
                    assertEquals("rec-1", ar.result().second.getString("id"))
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
        request(vertx, HttpMethod.GET, "$BASE_AUTH/purchase-orders/").onComplete { ar ->
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
    fun `get missing order returns 404`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "$BASE_AUTH/purchase-orders/po-999").onComplete { ar ->
            ctx.verify {
                assertTrue(ar.succeeded())
                assertEquals(404, ar.result().first)
                assertTrue(ar.result().second.getString("error")!!.contains("not found"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `get missing receipt returns 404`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "$BASE_AUTH/purchase-receipts/rec-999").onComplete { ar ->
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
//  顶层辅助（与 RequisitionRoutesTest 一致的 mockk 行构造）
// ========================================================================

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    val ordered = listOf(
        values["id"],
        values["fingerprint"],
        values["purchase_order_no"],
        values["warehouse"],
        values["supplier_name"],
        values["status"],
        values["requester_id"],
        values["approved_by"],
        values["approved_at"],
        values["cancelled_by"],
        values["cancelled_at"],
        values["cancel_reason"],
        values["closed_by"],
        values["closed_at"],
        values["close_reason"],
        values["created_at"],
        values["updated_at"],
        values["purchase_order_id"],
        values["material_id"],
        values["ordered_quantity"],
        values["received_quantity"],
        values["receipt_no"],
        values["received_by"],
        values["received_at"],
        values["stock_operation_id"],
        values["receipt_id"],
        values["lot_id"],
        values["unit_cost"],
        values["total_cost"],
        values["stock_operation_detail_id"],
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
