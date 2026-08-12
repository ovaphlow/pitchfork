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
 * 押金登记与退押（DepositService + 路由）非数据库测试（mockk + 嵌入式 HTTP）。
 * 覆盖验收口径：
 *   - 余额计算（纯函数 + 服务链路）：多次登记累加、多次退押、超额退押边界、余额不为负
 *   - 登记/退押校验：缺必填、金额 ≤ 0 / 小数位超限 / 越界、白名单字段拒绝
 *   - 已收束（离院/去世，discharge_date/death_date 非空）encounter 仍可退押（201）
 *   - 路由：POST 登记 201、缺必填/金额非法 400 {error}、未认证 401、
 *     累计退押超余额 400、台账 {records, meta:{total}}、空台账 records:[] total:0
 */
@ExtendWith(VertxExtension::class)
class DepositServiceTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发；
     * insert 成功后自动追加到台账（records），余额查询/数据查询/计数均据此派生，
     * 使「多次登记累加、多次退押」的余额链路可程序化演进。
     */
    private class DatabaseStub(
        var encounters: RowSet<Row> = rowSet(),
        records: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val records: MutableList<Map<String, Any?>> = records
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
                if (sql.contains("insert into healthcare.deposit_records")) {
                    records.add(
                        mapOf(
                            "id" to values[0],
                            "encounter_id" to values[1],
                            "type" to values[2],
                            "amount" to values[3],
                            "operator" to values[4],
                            "remark" to values.getOrNull(7),
                            "metadata" to values.getOrNull(8),
                            "created_at" to values[5],
                            "updated_at" to values[6],
                        ),
                    )
                    Future.succeededFuture(rowSet())
                } else {
                    val encounterId = values.getOrNull(0) as? String
                    val scoped = records.filter { it["encounter_id"] == encounterId }
                    val result = when {
                        sql.contains("from healthcare.encounters") -> encounters
                        sql.contains("count(*)") && sql.contains("from healthcare.deposit_records") ->
                            rowSet(mockRow(mapOf("total" to scoped.size.toLong())))
                        sql.contains("select type, amount from healthcare.deposit_records") ->
                            rowSet(*scoped.map { mockRow(mapOf("type" to it["type"], "amount" to it["amount"])) }.toTypedArray())
                        sql.contains("from healthcare.deposit_records") ->
                            rowSet(*scoped.map { mockRow(it) }.toTypedArray())
                        else -> rowSet()
                    }
                    Future.succeededFuture(result)
                }
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
    }

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260801001",
            "department" to "三楼",
            "ward" to "301-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "death_date" to null,
            "death_cause" to null,
            "admitting_diagnosis" to "高血压",
            "discharge_diagnosis" to null,
            "attending_physician" to "赵医生",
            "status" to "ACTIVE",
            "metadata" to JsonObject(),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun depositBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject().put("amount", 1000).put("remark", "入住押金")
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

    // ——— 1. 余额计算：多次登记累加、多次退押、超额退押边界、余额不为负 ———

    @Test
    fun `多次登记累加且余额为登记之和`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val service = DepositService(stub.pool)

        val first = service.createDeposit("enc-1", depositBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(DepositService.TYPE_DEPOSIT, first.getString("type"))
        assertEquals(0, BigDecimal("1000").compareTo(first.getValue("amount") as BigDecimal))
        assertEquals("cashier-1", first.getString("operator"))
        assertTrue(first.getString("id").length == 26, "登记必须生成 26 位 ULID")

        val second = service.createDeposit("enc-1", depositBody(mapOf("amount" to 2000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, BigDecimal("2000").compareTo(second.getValue("amount") as BigDecimal))

        // 余额 = 1000 + 2000 = 3000；等额退押成功
        val refund = service.createRefund("enc-1", depositBody(mapOf("amount" to 3000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(DepositService.TYPE_REFUND, refund.getString("type"))
        assertEquals(0, BigDecimal("3000").compareTo(refund.getValue("amount") as BigDecimal))

        assertEquals(3, stub.records.size)
        assertEquals(listOf("登记", "登记", "退押"), stub.records.map { it["type"] })
        assertEquals(0, DepositService.balanceOf(stub.records.map { it["type"] as String to it["amount"] as BigDecimal }).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `多次退押累减且超额退押返回400不写入`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            records = mutableListOf(
                mapOf(
                    "id" to "rec-dep-1", "encounter_id" to "enc-1", "type" to "登记",
                    "amount" to BigDecimal("3000.00"), "operator" to "cashier-1", "remark" to null,
                    "metadata" to null,
                    "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                    "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                ),
            ),
        )
        val service = DepositService(stub.pool)

        service.createRefund("enc-1", depositBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        service.createRefund("enc-1", depositBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        // 余额恰好归零：等额退押成功（边界）
        service.createRefund("enc-1", depositBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, DepositService.balanceOf(stub.records.map { it["type"] as String to it["amount"] as BigDecimal }).compareTo(BigDecimal.ZERO))

        // 超额 0.01 元 → 400，且不触发 insert
        val insertsBefore = stub.tuples.count { it.first.contains("insert into healthcare.deposit_records") }
        val cause = causeOf(service.createRefund("enc-1", depositBody(mapOf("amount" to 0.01)), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("exceeds") == true, "got: ${cause.message}")
        assertEquals(
            insertsBefore,
            stub.tuples.count { it.first.contains("insert into healthcare.deposit_records") },
            "超额退押不得写入任何记录",
        )
    }

    @Test
    fun `余额函数累计口径与不为负约束`() {
        // 纯函数口径：余额 = Σ登记 − Σ退押
        assertEquals(
            0,
            DepositService.balanceOf(emptyList()).compareTo(BigDecimal.ZERO),
            "空台账余额为 0",
        )
        assertEquals(
            0,
            DepositService.balanceOf(listOf("登记" to BigDecimal("100"), "退押" to BigDecimal("100"))).compareTo(BigDecimal.ZERO),
            "登记与退押等额时余额为 0",
        )
        assertEquals(
            0,
            DepositService.balanceOf(
                listOf(
                    "登记" to BigDecimal("100"),
                    "退押" to BigDecimal("50"),
                    "登记" to BigDecimal("200"),
                ),
            ).compareTo(BigDecimal("250")),
        )
        // 服务链路保证余额不为负：余额为 0 时任何退押都被拒绝
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            records = mutableListOf(
                mapOf(
                    "id" to "rec-dep-1", "encounter_id" to "enc-1", "type" to "登记",
                    "amount" to BigDecimal("100.00"), "operator" to "cashier-1", "remark" to null,
                    "metadata" to null,
                    "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                    "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                ),
                mapOf(
                    "id" to "rec-ref-1", "encounter_id" to "enc-1", "type" to "退押",
                    "amount" to BigDecimal("100.00"), "operator" to "cashier-1", "remark" to null,
                    "metadata" to null,
                    "created_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                    "updated_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                ),
            ),
        )
        val cause = causeOf(DepositService(stub.pool).createRefund("enc-1", depositBody(mapOf("amount" to 1)), "cashier-1"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("exceeds") == true, "got: ${cause.message}")
    }

    // ——— 2. 输入校验 ———

    @Test
    fun `登记与退押输入校验全部返回400且不触发SQL`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val service = DepositService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            for (invoker in listOf(
                { b: JsonObject -> service.createDeposit("enc-1", b, "cashier-1") },
                { b: JsonObject -> service.createRefund("enc-1", b, "cashier-1") },
            )) {
                val cause = causeOf(invoker(body))
                assertInstanceOf(IllegalArgumentException::class.java, cause)
                for (fragment in fragments) {
                    assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
                }
            }
        }

        expectInvalid(JsonObject(), "amount is required")
        expectInvalid(depositBody(mapOf("amount" to 0)), "positive")
        expectInvalid(depositBody(mapOf("amount" to -1)), "positive")
        expectInvalid(depositBody(mapOf("amount" to "1000")), "must be a number")
        expectInvalid(depositBody(mapOf("amount" to 1.999)), "at most 2 decimal places")
        expectInvalid(depositBody(mapOf("amount" to 10000000000.0)), "must not exceed")
        expectInvalid(depositBody(mapOf("type" to "退押")), "unsupported deposit keys: type")
        expectInvalid(depositBody(mapOf("operator" to "hacker")), "unsupported deposit keys: operator")
        expectInvalid(depositBody(mapOf("id" to "hacked")), "unsupported deposit keys: id")
        expectInvalid(depositBody(mapOf("remark" to "x".repeat(501))), "500")
        expectInvalid(depositBody(mapOf("metadata" to JsonArray().add(1))), "metadata must be a JSON object")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
    }

    // ——— 3. 资格映射 ———

    @Test
    fun `encounter不存在时登记与退押均返回404`() {
        val stub = DatabaseStub(encounters = rowSet())
        val service = DepositService(stub.pool)

        val cause1 = causeOf(service.createDeposit("missing", depositBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause1)
        assertTrue(cause1.message?.contains("encounter not found") == true, "got: ${cause1.message}")

        val cause2 = causeOf(service.createRefund("missing", depositBody(), "cashier-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause2)
        assertTrue(cause2.message?.contains("encounter not found") == true, "got: ${cause2.message}")
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })
    }

    @Test
    fun `已收束encounter仍可退押不校验离院去世状态`() {
        // 已离院（DISCHARGED + discharge_date 非空）
        val discharged = DatabaseStub(
            encounters = rows(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
                    ),
                ),
            ),
            records = mutableListOf(depositRecord("1000.00")),
        )
        val refund1 = DepositService(discharged.pool)
            .createRefund("enc-1", depositBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(DepositService.TYPE_REFUND, refund1.getString("type"))

        // 已去世（DECEASED + death_date 非空）
        val deceased = DatabaseStub(
            encounters = rows(
                encounterRow(
                    mapOf(
                        "status" to "DECEASED",
                        "death_date" to OffsetDateTime.parse("2026-08-11T14:00:00+08:00"),
                    ),
                ),
            ),
            records = mutableListOf(depositRecord("1000.00")),
        )
        val refund2 = DepositService(deceased.pool)
            .createRefund("enc-1", depositBody(mapOf("amount" to 1000)), "cashier-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(DepositService.TYPE_REFUND, refund2.getString("type"))

        // 服务绝不按收束状态做门槛：WHERE 子句不得引用 discharge_date/death_date/status
        for (stub in listOf(discharged, deceased)) {
            assertTrue(
                stub.queries.none { q ->
                    val where = q.substringAfter("where", "")
                    where.contains("discharge_date") || where.contains("death_date") || where.contains("status")
                },
                "退押不得按收束状态过滤: ${stub.queries}",
            )
        }
    }

    private fun depositRecord(amount: String, type: String = "登记"): Map<String, Any?> =
        mapOf(
            "id" to "rec-1",
            "encounter_id" to "enc-1",
            "type" to type,
            "amount" to BigDecimal(amount),
            "operator" to "cashier-1",
            "remark" to null,
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )

    // ——— 4. 台账 ———

    @Test
    fun `台账分页返回records与meta包含total和balance`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            records = mutableListOf(
                depositRecord("1000.00"),
                depositRecord("500.00", type = "退押"),
            ),
        )
        val ledger = DepositService(stub.pool)
            .listDeposits("enc-1", limit = 10, offset = 5)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2, ledger.getJsonArray("records").size())
        val meta = ledger.getJsonObject("meta")
        assertEquals(2L, meta.getLong("total"))
        assertEquals(0, BigDecimal("500.00").compareTo(meta.getValue("balance") as BigDecimal), "台账余额 = 1000 − 500")

        val record = ledger.getJsonArray("records").getJsonObject(0)
        assertEquals("rec-1", record.getString("id"))
        assertEquals("enc-1", record.getString("encounter_id"))
        assertEquals("登记", record.getString("type"))
        assertEquals("cashier-1", record.getString("operator"))
        assertNotNull(record.getString("created_at"))

        val dataSql = stub.queries.first { it.contains("fetch next") }
        assertTrue(dataSql.contains("offset $"), "台账必须分页 offset: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "台账必须分页 limit: $dataSql")
        assertTrue(dataSql.contains("order by"), "台账必须倒序: $dataSql")
        assertTrue(stub.queries.first { it.contains("count(*)") }.contains("encounter_id = $1"), "计数必须按 encounter 过滤")
    }

    @Test
    fun `空台账返回空records与total0且余额为0`() {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        val ledger = DepositService(stub.pool)
            .listDeposits("enc-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(0, ledger.getJsonArray("records").size())
        assertEquals(0L, ledger.getJsonObject("meta").getLong("total"))
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.getJsonObject("meta").getValue("balance") as BigDecimal))
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
    fun `POST登记201且返回记录`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/deposits",
                depositBody(),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "登记押金必须 201")
                    assertEquals("登记", body.getString("type"))
                    assertTrue(body.getString("id").length == 26)
                    assertEquals("cashier-route-1", body.getString("operator"), "操作人必须取认证主体")
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/encounters/enc-1/deposits/refunds",
                    depositBody(mapOf("amount" to 1000)),
                ).map { (refundStatus, refundBody) ->
                    ctx.verify {
                        assertEquals(201, refundStatus, "退押必须 201")
                        assertEquals("退押", refundBody.getString("type"))
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `缺必填与金额非法返回400且错误为error对象`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/deposits",
                JsonObject(),
            ).compose { (missingStatus, missingBody) ->
                ctx.verify {
                    assertEquals(400, missingStatus, "缺必填必须 400")
                    assertNotNull(missingBody.getString("error"), "错误响应必须为 { error: ... }")
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/encounters/enc-1/deposits",
                    depositBody(mapOf("amount" to 0)),
                ).map { (zeroStatus, zeroBody) ->
                    ctx.verify {
                        assertEquals(400, zeroStatus, "金额为 0 必须 400")
                        assertNotNull(zeroBody.getString("error"))
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `登记与退押未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/deposits",
                depositBody(),
            ).compose { (depositStatus, depositBody) ->
                ctx.verify {
                    assertEquals(401, depositStatus, "无认证 userId 登记必须 401")
                    assertNotNull(depositBody.getString("error"))
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/encounters/enc-1/deposits/refunds",
                    depositBody(mapOf("amount" to 100)),
                ).map { (refundStatus, refundBody) ->
                    ctx.verify {
                        assertEquals(401, refundStatus, "无认证 userId 退押必须 401")
                        assertNotNull(refundBody.getString("error"))
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `累计退押超余额返回400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            records = mutableListOf(depositRecord("100.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/deposits/refunds",
                depositBody(mapOf("amount" to 100.01)),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "累计退押超余额必须 400")
                    assertTrue(body.getString("error")?.contains("exceeds") == true, "got: ${body.getString("error")}")
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `已收束encounter退押返回201`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(
                encounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
                    ),
                ),
            ),
            records = mutableListOf(depositRecord("1000.00")),
        )
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/deposits/refunds",
                depositBody(mapOf("amount" to 1000)),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "已离院 encounter 仍可退押，必须 201")
                    assertEquals("退押", body.getString("type"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `GET台账返回records与meta且空台账为空`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            records = mutableListOf(
                depositRecord("1000.00"),
                depositRecord("300.00", type = "退押"),
            ),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/deposits")
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status)
                        assertEquals(2, body.getJsonArray("records").size())
                        assertEquals(2L, body.getJsonObject("meta").getLong("total"))
                        assertEquals(0, BigDecimal("700.00").compareTo(BigDecimal.valueOf((body.getJsonObject("meta").getValue("balance") as Number).toDouble())))
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-2/deposits")
                        .map { (emptyStatus, emptyBody) ->
                            ctx.verify {
                                assertEquals(200, emptyStatus, "空台账必须 200 而非 404")
                                assertEquals(0, emptyBody.getJsonArray("records").size(), "空台账 records 必须为 []")
                                assertEquals(0L, emptyBody.getJsonObject("meta").getLong("total"), "空台账 total 必须为 0")
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `押金路由不被泛型encounter路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(encounters = rows(encounterRow()))
        withServer(vertx, stub, userId = "cashier-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/deposits",
                depositBody(),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "押金登记必须命中专用路由")
                    assertEquals("登记", body.getString("type"))
                }
                httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/deposits")
                    .map { (ledgerStatus, ledgerBody) ->
                        ctx.verify {
                            assertEquals(200, ledgerStatus)
                            assertEquals(1, ledgerBody.getJsonArray("records").size())
                        }
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

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
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
