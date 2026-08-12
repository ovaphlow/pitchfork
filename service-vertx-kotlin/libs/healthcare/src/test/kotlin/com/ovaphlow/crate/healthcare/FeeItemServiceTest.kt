package com.ovaphlow.crate.healthcare

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
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 费用项目字典（FeeItemService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 创建 201、26 位 ULID、状态默认 启用
 *   - 列表 {records, meta:{total}}；空列表 records: [] 且 total: 0
 *   - 查询/更新/删除闭环（含不存在 404）
 *   - 分类非法枚举 400 {error}；单价 ≤ 0 400 {error}；未认证 401
 *   - 启用/停用流转：停用成功、非法状态值 400
 */
@ExtendWith(VertxExtension::class)
class FeeItemServiceTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * insert 自动追加到字典（items），select/update/delete/count 均据此派生，
     * 使 CRUD 与状态流转链路可程序化演进。
     */
    private class DatabaseStub(
        items: MutableList<MutableMap<String, Any?>> = mutableListOf(),
    ) {
        val items: MutableList<MutableMap<String, Any?>> = items
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                val values = tupleValues(firstArg())
                tuples.add(sql to values)
                val result: RowSet<Row> = when {
                    sql.contains("insert into healthcare.fee_items") -> {
                        items.add(insertedRecord(sql, values))
                        rowSet()
                    }
                    sql.contains("delete from healthcare.fee_items") -> {
                        val id = boundId(sql, values) ?: ""
                        val removed = items.removeIf { it["id"] == id }
                        rowSet(rowCount = if (removed) 1 else 0)
                    }
                    sql.contains("update healthcare.fee_items") -> {
                        val id = boundId(sql, values) ?: ""
                        val target = items.firstOrNull { it["id"] == id }
                        if (target != null) {
                            applyUpdate(target, sql, values)
                            rowSet(rowCount = 1)
                        } else {
                            rowSet(rowCount = 0)
                        }
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.fee_items") ->
                        rowSet(mockRow(mapOf("total" to filtered(sql, values).size.toLong())))
                    sql.contains("from healthcare.fee_items") -> rows(*filtered(sql, values).toTypedArray())
                    else -> rowSet()
                }
                Future.succeededFuture(result)
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }

        /** insert 列清单跟随 set 顺序渲染，按列名逐位回填。 */
        private fun insertedRecord(sql: String, values: List<Any?>): MutableMap<String, Any?> {
            val match = Regex("insert into healthcare\\.fee_items \\(([^)]+)\\) values").find(sql)
                ?: error("cannot parse insert columns: $sql")
            val cols = match.groupValues[1].split(",").map { it.trim() }
            val record = mutableMapOf<String, Any?>()
            cols.forEachIndexed { index, col -> record[col] = values.getOrNull(index) }
            return record
        }

        /** where id = $n 的绑定序号（列名带 schema 前缀）。 */
        private fun boundId(sql: String, values: List<Any?>): String? {
            val match = Regex("id = \\$(\\d+)").find(sql) ?: return null
            return values.getOrNull(match.groupValues[1].toInt() - 1) as? String
        }

        /** 按 where 子句中的 category/status 条件过滤。 */
        private fun filtered(sql: String, values: List<Any?>): List<MutableMap<String, Any?>> {
            val category = boundField(sql, values, "category")
            val status = boundField(sql, values, "status")
            return items.filter { item ->
                (category == null || item["category"] == category) &&
                    (status == null || item["status"] == status)
            }.sortedWith(
                compareByDescending<MutableMap<String, Any?>> { it["created_at"] as? OffsetDateTime }
                    .thenByDescending { it["id"] as? String },
            )
        }

        private fun boundField(sql: String, values: List<Any?>, field: String): String? {
            val match = Regex("$field = \\$(\\d+)").find(sql) ?: return null
            return values.getOrNull(match.groupValues[1].toInt() - 1) as? String
        }

        /** 按 set 子句逐字段回填（setNull 渲染为 field = null；cast($n as ...) 取绑定值）。 */
        private fun applyUpdate(target: MutableMap<String, Any?>, sql: String, values: List<Any?>) {
            val setPart = sql.substringAfter(" set ", "").substringBefore(" where")
            for (part in setPart.split(",")) {
                val eq = part.indexOf(" = ")
                if (eq <= 0) continue
                val field = part.substring(0, eq).trim()
                val rawValue = part.substring(eq + 3).trim()
                val value: Any? = when {
                    rawValue == "null" -> null
                    rawValue.startsWith("$") -> values.getOrNull(rawValue.removePrefix("$").toInt() - 1)
                    rawValue.startsWith("cast(") -> {
                        val bind = Regex("cast\\((\\$\\d+) as").find(rawValue)
                            ?.groupValues?.get(1)?.removePrefix("$")?.toInt()
                        bind?.let { values.getOrNull(it - 1) }
                    }
                    else -> null
                }
                target[field] = value
            }
        }
    }

    private fun feeItemRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "fee-1",
            "category" to "床位费",
            "name" to "双人间床位费",
            "unit_price" to BigDecimal("80.00"),
            "status" to "启用",
            "remark" to null,
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun feeItemBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("category", "护理费")
            .put("name", "一级护理费")
            .put("unit_price", 120)
            .put("remark", "按日计费")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    private fun causeOf(future: Future<*>): Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get()
            throw AssertionError("expected future to fail")
        } catch (error: Throwable) {
            var cause = error
            while (cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException) {
                cause = cause.cause ?: break
            }
            return cause
        }
    }

    // ——— 1. 创建 ———

    @Test
    fun `创建返回26位ULID且状态默认启用`() {
        val stub = DatabaseStub()
        val created = FeeItemService(stub.pool)
            .createItem(feeItemBody())
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("护理费", created.getString("category"))
        assertEquals("一级护理费", created.getString("name"))
        assertEquals(0, BigDecimal("120.00").compareTo(created.getValue("unit_price") as BigDecimal))
        assertEquals(FeeItemService.STATUS_ENABLED, created.getString("status"), "新建项目状态必须默认 启用")
        assertTrue(created.getString("id").length == 26, "必须生成 26 位 ULID")
        assertNotNull(created.getString("created_at"))
        assertNotNull(created.getString("updated_at"))

        assertEquals(1, stub.items.size)
        assertEquals("启用", stub.items.single()["status"])
    }

    @Test
    fun `创建与更新与状态流转输入校验全部返回400且不触发SQL`() {
        val stub = DatabaseStub()
        val service = FeeItemService(stub.pool)

        fun expectCreateInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.createItem(body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectCreateInvalid(JsonObject(), "category is required")
        expectCreateInvalid(feeItemBody(mapOf("category" to "杂费")), "category must be one of")
        expectCreateInvalid(feeItemBody(mapOf("category" to 123)), "category must be a string")
        expectCreateInvalid(feeItemBody(mapOf("name" to null)), "name is required")
        expectCreateInvalid(feeItemBody(mapOf("name" to "   ")), "must not be blank")
        expectCreateInvalid(feeItemBody(mapOf("name" to "x".repeat(101))), "100")
        expectCreateInvalid(feeItemBody(mapOf("unit_price" to null)), "unit_price is required")
        expectCreateInvalid(feeItemBody(mapOf("unit_price" to 0)), "positive")
        expectCreateInvalid(feeItemBody(mapOf("unit_price" to -1)), "positive")
        expectCreateInvalid(feeItemBody(mapOf("unit_price" to "120")), "must be a number")
        expectCreateInvalid(feeItemBody(mapOf("unit_price" to 1.999)), "at most 2 decimal places")
        expectCreateInvalid(feeItemBody(mapOf("unit_price" to 10000000000.0)), "must not exceed")
        expectCreateInvalid(feeItemBody(mapOf("status" to "停用")), "unsupported fee item keys: status")
        expectCreateInvalid(feeItemBody(mapOf("id" to "hacked")), "unsupported fee item keys: id")
        expectCreateInvalid(feeItemBody(mapOf("remark" to "x".repeat(501))), "500")
        expectCreateInvalid(feeItemBody(mapOf("metadata" to JsonArray().add(1))), "metadata must be a JSON object")

        // 状态流转白名单：非法状态值/多余字段/类型错误
        fun expectStatusInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.updateItemStatus("fee-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectStatusInvalid(JsonObject(), "status is required")
        expectStatusInvalid(JsonObject().put("status", "下架"), "status must be one of")
        expectStatusInvalid(JsonObject().put("status", 1), "status must be a string")
        expectStatusInvalid(JsonObject().put("status", "停用").put("name", "x"), "unsupported status keys: name")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
    }

    // ——— 2. 列表 ———

    @Test
    fun `列表返回records与meta包含total且支持过滤`() {
        val stub = DatabaseStub(
            mutableListOf(
                feeItemRow(mapOf("id" to "fee-1", "category" to "床位费", "status" to "启用")),
                feeItemRow(
                    mapOf(
                        "id" to "fee-2",
                        "category" to "护理费",
                        "name" to "一级护理费",
                        "unit_price" to BigDecimal("120.00"),
                        "status" to "停用",
                        "created_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                        "updated_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                    ),
                ),
            ),
        )
        val service = FeeItemService(stub.pool)

        val page = service.listItems(limit = 10, offset = 0)
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(2, page.getJsonArray("records").size())
        assertEquals(2L, page.getJsonObject("meta").getLong("total"))

        val record = page.getJsonArray("records").getJsonObject(0)
        assertEquals("fee-2", record.getString("id"), "列表必须按 created_at 倒序")
        assertEquals("护理费", record.getString("category"))
        assertEquals("一级护理费", record.getString("name"))
        assertEquals(0, BigDecimal("120.00").compareTo(record.getValue("unit_price") as BigDecimal))
        assertEquals("停用", record.getString("status"))
        assertNotNull(record.getString("created_at"))

        val dataSql = stub.queries.first { it.contains("fetch next") }
        assertTrue(dataSql.contains("offset $"), "列表必须分页 offset: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "列表必须分页 limit: $dataSql")
        assertTrue(dataSql.contains("order by"), "列表必须倒序: $dataSql")
        assertTrue(stub.queries.first { it.contains("count(*)") }.contains("from healthcare.fee_items"))

        // 过滤：category/status 条件必须落到 SQL
        service.listItems(category = "床位费", status = "启用")
            .toCompletionStage().toCompletableFuture().get()
        val filteredSql = stub.queries.last { it.contains("count(*)") }
        assertTrue(filteredSql.contains("category = $"), "计数必须按 category 过滤: $filteredSql")
        assertTrue(filteredSql.contains("status = $"), "计数必须按 status 过滤: $filteredSql")
    }

    @Test
    fun `空列表返回空records与total0`() {
        val stub = DatabaseStub()
        val page = FeeItemService(stub.pool)
            .listItems()
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(0, page.getJsonArray("records").size())
        assertEquals(0L, page.getJsonObject("meta").getLong("total"))
    }

    // ——— 3. 查询/更新/删除 ———

    @Test
    fun `查询不存在项目返回404且不写入`() {
        val stub = DatabaseStub()
        val service = FeeItemService(stub.pool)

        for (invoker in listOf(
            { service.getItem("missing") },
            { service.updateItem("missing", feeItemBody()) },
            { service.deleteItem("missing") },
            { service.updateItemStatus("missing", JsonObject().put("status", "停用")) },
        )) {
            val cause = causeOf(invoker())
            assertInstanceOf(HealthcareNotFoundException::class.java, cause)
            assertTrue(cause.message?.contains("fee item not found") == true, "got: ${cause.message}")
        }
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.fee_items") })
    }

    @Test
    fun `更新全量替换字段并可清空备注与扩展且不改状态`() {
        val stub = DatabaseStub(mutableListOf(feeItemRow()))
        val service = FeeItemService(stub.pool)

        val updated = service.updateItem(
            "fee-1",
            feeItemBody(
                mapOf(
                    "category" to "伙食费",
                    "name" to "三餐伙食费",
                    "unit_price" to 35.5,
                    "remark" to null,
                    "metadata" to JsonObject().put("billing", "daily"),
                ),
            ),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("伙食费", updated.getString("category"))
        assertEquals("三餐伙食费", updated.getString("name"))
        assertEquals(0, BigDecimal("35.50").compareTo(updated.getValue("unit_price") as BigDecimal))
        assertNull(updated.getValue("remark"), "提交 null 必须清空备注")
        assertEquals("daily", updated.getJsonObject("metadata").getString("billing"))
        assertEquals("启用", updated.getString("status"), "PUT 更新不得改动状态")

        // 再清空 metadata：setNull 生效
        val cleared = service.updateItem(
            "fee-1",
            feeItemBody(mapOf("category" to "伙食费", "name" to "三餐伙食费", "unit_price" to 35.5, "remark" to "调价", "metadata" to null)),
        ).toCompletionStage().toCompletableFuture().get()
        assertNull(cleared.getValue("metadata"), "提交 null 必须清空扩展元数据")
        assertEquals("调价", cleared.getString("remark"))

        // jOOQ setNull 渲染为绑定 null：第一次更新 remark 为 null，第二次 metadata 为 null
        val updateTuples = stub.tuples.filter { it.first.contains("update healthcare.fee_items") }
        assertEquals(2, updateTuples.size)
        assertNull(updateTuples[0].second[4], "第一次更新 remark 必须绑定 null（setNull）")
        assertNull(updateTuples[1].second[5], "第二次更新 metadata 必须绑定 null（setNull）")
    }

    // ——— 4. 状态流转（启用/停用） ———

    @Test
    fun `状态流转停用与启用成功`() {
        val stub = DatabaseStub(mutableListOf(feeItemRow()))
        val service = FeeItemService(stub.pool)

        val disabled = service.updateItemStatus("fee-1", JsonObject().put("status", "停用"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(FeeItemService.STATUS_DISABLED, disabled.getString("status"))
        assertEquals("停用", stub.items.single()["status"])

        val enabled = service.updateItemStatus("fee-1", JsonObject().put("status", "启用"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(FeeItemService.STATUS_ENABLED, enabled.getString("status"))

        val statusSql = stub.tuples.filter { it.first.contains("update healthcare.fee_items") }
        assertTrue(statusSql.all { it.first.contains("id = $") }, "状态流转必须按 id 定位: ${statusSql.map { it.first }}")
    }

    // ——— 5. 嵌入式 HTTP 路由 ———

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body.encode())
                else req.send()
            }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun <T> withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        userId: String? = null,
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose {
                server.close().map { Unit }
            }
        }
    }

    @Test
    fun `POST创建201且返回记录`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub()
        withServer(vertx, stub, userId = "billing-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/fee-items",
                feeItemBody(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "创建必须 201")
                    assertTrue(body.getString("id").length == 26)
                    assertEquals("护理费", body.getString("category"))
                    assertEquals("一级护理费", body.getString("name"))
                    assertEquals("启用", body.getString("status"), "新建项目状态必须默认 启用")
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `GET列表返回records与meta且空列表为空`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            mutableListOf(
                feeItemRow(),
                feeItemRow(
                    mapOf(
                        "id" to "fee-2",
                        "category" to "护理费",
                        "name" to "一级护理费",
                        "unit_price" to BigDecimal("120.00"),
                        "created_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                        "updated_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                    ),
                ),
            ),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/fee-items")
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status)
                        assertEquals(2, body.getJsonArray("records").size())
                        assertEquals(2L, body.getJsonObject("meta").getLong("total"))
                        assertEquals("fee-2", body.getJsonArray("records").getJsonObject(0).getString("id"))
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/fee-items?limit=10&offset=0")
                        .map { (_, emptyBody) -> emptyBody }
                }
                .compose { emptyBody ->
                    ctx.verify {
                        assertEquals(2L, emptyBody.getJsonObject("meta").getLong("total"), "分页参数必须生效")
                    }
                    val emptyStub = DatabaseStub()
                    withServer(vertx, emptyStub) { emptyPort ->
                        httpRequest(vertx, emptyPort, HttpMethod.GET, "/healthcare/v1/fee-items")
                            .map { (emptyStatus, body) ->
                                ctx.verify {
                                    assertEquals(200, emptyStatus, "空字典必须 200 而非 404")
                                    assertEquals(0, body.getJsonArray("records").size(), "空字典 records 必须为 []")
                                    assertEquals(0L, body.getJsonObject("meta").getLong("total"), "空字典 total 必须为 0")
                                }
                            }
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `GET详情PUT更新DELETE删除闭环`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(mutableListOf(feeItemRow()))
        withServer(vertx, stub, userId = "billing-route-1") { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/fee-items/fee-1")
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status)
                        assertEquals("床位费", body.getString("category"))
                        assertEquals("双人间床位费", body.getString("name"))
                    }
                    httpRequest(
                        vertx, port, HttpMethod.PUT,
                        "/healthcare/v1/fee-items/fee-1",
                        feeItemBody(mapOf("category" to "伙食费", "name" to "三餐伙食费", "unit_price" to 35.5)),
                    )
                }
                .compose { (putStatus, putBody) ->
                    ctx.verify {
                        assertEquals(200, putStatus, "更新必须 200")
                        assertEquals("伙食费", putBody.getString("category"))
                        assertEquals(0, BigDecimal("35.50").compareTo(BigDecimal.valueOf((putBody.getValue("unit_price") as Number).toDouble())))
                    }
                    httpRequest(vertx, port, HttpMethod.DELETE, "/healthcare/v1/fee-items/fee-1")
                }
                .compose { (deleteStatus, deleteBody) ->
                    ctx.verify {
                        assertEquals(200, deleteStatus, "删除必须 200")
                        assertEquals("fee-1", deleteBody.getString("id"))
                    }
                    httpRequest(vertx, port, HttpMethod.DELETE, "/healthcare/v1/fee-items/fee-1")
                }
                .map { (againStatus, againBody) ->
                    ctx.verify {
                        assertEquals(404, againStatus, "重复删除必须 404")
                        assertNotNull(againBody.getString("error"))
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `分类非法枚举与金额非法返回400且错误为error对象`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub()
        withServer(vertx, stub, userId = "billing-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/fee-items",
                feeItemBody(mapOf("category" to "杂费")),
            ).compose { (categoryStatus, categoryBody) ->
                ctx.verify {
                    assertEquals(400, categoryStatus, "分类非法枚举必须 400")
                    assertTrue(categoryBody.getString("error")?.contains("category must be one of") == true, "got: ${categoryBody.getString("error")}")
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/fee-items",
                    feeItemBody(mapOf("unit_price" to 0)),
                ).map { (priceStatus, priceBody) ->
                    ctx.verify {
                        assertEquals(400, priceStatus, "单价为 0 必须 400")
                        assertTrue(priceBody.getString("error")?.contains("unit_price") == true, "got: ${priceBody.getString("error")}")
                        assertNotNull(priceBody.getString("error"), "错误响应必须为 { error: ... }")
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `未认证写操作返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(mutableListOf(feeItemRow()))
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/fee-items",
                feeItemBody(),
            ).compose { (createStatus, createBody) ->
                ctx.verify {
                    assertEquals(401, createStatus, "无认证 userId 创建必须 401")
                    assertNotNull(createBody.getString("error"))
                }
                httpRequest(
                    vertx, port, HttpMethod.PUT,
                    "/healthcare/v1/fee-items/fee-1",
                    feeItemBody(),
                )
            }.compose { (updateStatus, updateBody) ->
                ctx.verify {
                    assertEquals(401, updateStatus, "无认证 userId 更新必须 401")
                    assertNotNull(updateBody.getString("error"))
                }
                httpRequest(vertx, port, HttpMethod.DELETE, "/healthcare/v1/fee-items/fee-1")
            }.compose { (deleteStatus, deleteBody) ->
                ctx.verify {
                    assertEquals(401, deleteStatus, "无认证 userId 删除必须 401")
                    assertNotNull(deleteBody.getString("error"))
                }
                httpRequest(
                    vertx, port, HttpMethod.PATCH,
                    "/healthcare/v1/fee-items/fee-1/status",
                    JsonObject().put("status", "停用"),
                )
            }.map { (statusStatus, statusBody) ->
                ctx.verify {
                    assertEquals(401, statusStatus, "无认证 userId 状态流转必须 401")
                    assertNotNull(statusBody.getString("error"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `状态流转停用成功且非法状态值400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(mutableListOf(feeItemRow()))
        withServer(vertx, stub, userId = "billing-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.PATCH,
                "/healthcare/v1/fee-items/fee-1/status",
                JsonObject().put("status", "停用"),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "停用流转必须成功")
                    assertEquals("停用", body.getString("status"))
                    assertEquals("双人间床位费", body.getString("name"), "状态流转不得改动其他字段")
                }
                httpRequest(
                    vertx, port, HttpMethod.PATCH,
                    "/healthcare/v1/fee-items/fee-1/status",
                    JsonObject().put("status", "下架"),
                )
            }.compose { (invalidStatus, invalidBody) ->
                ctx.verify {
                    assertEquals(400, invalidStatus, "非法状态值必须 400")
                    assertTrue(invalidBody.getString("error")?.contains("status must be one of") == true, "got: ${invalidBody.getString("error")}")
                }
                httpRequest(
                    vertx, port, HttpMethod.PATCH,
                    "/healthcare/v1/fee-items/fee-1/status",
                    JsonObject(),
                )
            }.map { (missingStatus, missingBody) ->
                ctx.verify {
                    assertEquals(400, missingStatus, "缺状态必须 400")
                    assertNotNull(missingBody.getString("error"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }
}

// ——— mock 基础设施（顶层函数，供测试类与嵌套 stub 共用） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun rowSet(vararg rows: Row, rowCount: Int = 0): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    every { rs.rowCount() } returns rowCount
    return rs
}

private fun rows(vararg values: Map<String, Any?>): RowSet<Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}
