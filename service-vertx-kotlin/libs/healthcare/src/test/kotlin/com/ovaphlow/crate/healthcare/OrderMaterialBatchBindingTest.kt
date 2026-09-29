package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.TaskService
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
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 030 §4.4 历史医嘱待补绑清单 + 批量补绑的非数据库测试（mockk `Pool` 桩 + 嵌入式 HTTP）：
 *
 *   - 清单查询 SQL 形状：`MEDICATION` + `ACTIVE` + `order_details->>'material_id' is null`
 *     + `ELDERLY_CARE` 门禁 + join 患者、倒序分页与 `meta.total`；空列表仍 `records: []`
 *   - 清单 search 四列模糊匹配与 encounter_id 过滤
 *   - 批量补绑参数校验（未知键/结构错/空数组/超 100/重复 order_id/项内非法键）全 400 且无 IO
 *   - 目录解析失败（404/400/503）在任何写入之前终止：一条都不落库（全成全败）
 *   - 单条已绑定 → 409 且整体回滚；医嘱不存在 → 404
 *   - 合法批次：同一事务内先全量解析、再逐条写绑定与审计，响应含目录快照
 *   - 路由级：空体 400、合法体 200、非 JSON 对象体 400、未认证 401、端口未接线 503，
 *     静态路径不被泛型 `GET /orders/:id` 吞掉（同路径 GET 仍由泛型路由处理）
 */
@ExtendWith(VertxExtension::class)
class OrderMaterialBatchBindingTest {

    /**
     * 全库 mock 桩：conn/pool 的 `preparedQuery` 按 normalized SQL 特征分发，
     * 捕获全部 SQL（raw + normalized）与 tuple，以便断言写入顺序、参数与「一条都没写」。
     *
     * `orderDetails` 充当医嘱锁读的来源表：key 存在 = 该医嘱存在（值为其 `order_details`），
     * 缺 key = 医嘱不存在（锁读空行集 → 404），与 `bindDrugMaterial` 的真实语义一致。
     */
    private class OrderBindingStub(
        var listRows: RowSet<Row> = bindingRowSet(),
        var countRows: RowSet<Row> = bindingRowSet(),
        var orderDetails: MutableMap<String, JsonObject?> = mutableMapOf(),
        /**
         * 030 评审 P2-3 医嘱作用域门禁的桩：`order_id -> (order_type, status)`。
         * 缺省（未登记）按「MEDICATION + ACTIVE」返回，使既有用例不受新门禁影响；
         * 登记为 `null to null` 表示该医嘱行不存在（作用域查询不返回该行）。
         */
        var orderScope: MutableMap<String, Pair<String?, String?>> = mutableMapOf(),
    ) {
        val queries = mutableListOf<String>()
        val rawQueries = mutableListOf<String>()
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
                val values = bindingTupleValues(firstArg())
                tuples.add(sql to values)
                Future.succeededFuture(
                    when {
                        sql.contains("update healthcare.medical_orders") -> bindingRowSet()
                        // 030 评审 P2-3 作用域门禁：按绑定参数里的 order_id 合成 order_type/status 行。
                        // 判据必须是「单表 + id in (...)」——清单查询也以 `select …medical_orders.id` 开头，
                        // 但它带 join，不能混用同一分支。
                        sql.contains("healthcare.medical_orders.id in") && !sql.contains(" join ") -> bindingRows(
                            *values.filterIsInstance<String>().mapNotNull { orderId ->
                                val facts = orderScope[orderId]
                                if (facts != null && facts.first == null && facts.second == null) {
                                    null
                                } else {
                                    val effective = facts ?: ("MEDICATION" to "ACTIVE")
                                    mapOf(
                                        "id" to orderId,
                                        "order_type" to effective.first,
                                        "status" to effective.second,
                                    )
                                }
                            }.toTypedArray(),
                        )
                        sql.contains("for update") && sql.contains("from healthcare.medical_orders") -> {
                            val orderId = values.firstOrNull() as? String
                            if (orderId == null || !orderDetails.containsKey(orderId)) {
                                bindingRowSet()
                            } else {
                                bindingRows(
                                    mapOf("id" to orderId, "order_details" to orderDetails[orderId]),
                                )
                            }
                        }
                        sql.contains("count(*)") && sql.contains("from healthcare.medical_orders") -> countRows
                        sql.contains("from healthcare.medical_orders") -> listRows
                        else -> bindingRowSet()
                    },
                )
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                // 与 HealthcareMedicalOrderTest.DatabaseStub 同形：先取 handler 再 apply，
                // 并把返回类型显式收成 `Future<Any?>`（`withTransaction` 桩的期望返回类型）
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any?>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            rawQueries.add(sql)
            val normalizedSql = bindingNormalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }

        /** 绑定写入（`update healthcare.medical_orders ...`）的全部语句 */
        fun updateQueries(): List<String> = queries.filter { it.startsWith("update healthcare.medical_orders") }

        fun writesOn(orderId: String): Int =
            tuples.count { (sql, values) ->
                sql.startsWith("update healthcare.medical_orders") && values.last() == orderId
            }
    }

    /** 目录端口桩：记录调用顺序，便于断言「解析阶段先于写入阶段」 */
    private class FakeCatalogPort(
        private val materials: Map<String, DrugCatalogMaterial>,
    ) : DrugCatalogPort {
        val calls = mutableListOf<String>()

        override fun findDrugMaterial(client: SqlClient, materialId: String): Future<DrugCatalogMaterial?> {
            calls.add(materialId)
            return Future.succeededFuture(materials[materialId])
        }
    }

    private fun drugMaterial(
        id: String,
        code: String? = "CODE-$id",
        name: String? = "药品-$id",
        category: String? = "药品",
        status: String? = "ACTIVE",
    ): DrugCatalogMaterial = DrugCatalogMaterial(
        id = id,
        code = code,
        name = name,
        spec = "100mg/片",
        baseUnit = "片",
        status = status,
        category = category,
    )

    private fun service(
        stub: OrderBindingStub,
        port: DrugCatalogPort?,
    ): MedicalOrderService = MedicalOrderService(stub.pool, TaskService(stub.pool), drugCatalogPort = port)

    private fun unboundOrderRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "ord-1",
            "encounter_id" to "enc-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_no" to "A20260801001",
            "order_content" to "高血压引起的头痛",
            "order_details" to JsonObject().put("drug_name", "阿司匹林").put("dose", "100mg"),
            "start_time" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "end_time" to null,
            "nurse_checked_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
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

    private fun await(future: Future<JsonObject>): JsonObject =
        future.toCompletionStage().toCompletableFuture().get()

    // ========================================================================
    //  1. 待补绑清单（只读）
    // ========================================================================

    @Test
    fun `待补绑清单SQL形状与分页与记录字段`() {
        val stub = OrderBindingStub(
            listRows = bindingRows(unboundOrderRow()),
            countRows = bindingRows(mapOf("total" to 1L)),
        )
        val result = await(
            service(stub, FakeCatalogPort(emptyMap()))
                .listUnboundMaterialOrders(stub.pool, null, null, 50, 0),
        )

        assertEquals(1L, result.getJsonObject("meta").getLong("total"))
        val record = result.getJsonArray("records").getJsonObject(0)
        assertEquals("ord-1", record.getString("id"))
        assertEquals("enc-1", record.getString("encounter_id"))
        assertEquals("A20260801001", record.getString("encounter_no"))
        assertEquals("pat-1", record.getString("patient_id"))
        assertEquals("张奶奶", record.getString("patient_name"))
        assertEquals("高血压引起的头痛", record.getString("order_content"))
        assertEquals("阿司匹林", record.getString("drug_name"))
        // 时间字段与同模块既有响应一致：OffsetDateTime.toString()（整秒为零时省略 `:00`）
        assertEquals(OffsetDateTime.parse("2026-08-01T09:00:00+08:00").toString(), record.getString("start_time"))
        assertNull(record.getString("end_time"))
        assertEquals(
            OffsetDateTime.parse("2026-08-01T10:00:00+08:00").toString(),
            record.getString("nurse_checked_at"),
        )
        assertEquals(OffsetDateTime.parse("2026-08-01T09:00:00+08:00").toString(), record.getString("created_at"))

        assertEquals(2, stub.queries.size, "只应有计数与列表两条查询: ${stub.queries}")
        assertTrue(
            stub.queries.none { it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete") },
            "待补绑清单必须只读: ${stub.queries}",
        )

        val countSql = stub.queries.first { it.contains("count(*)") }
        val dataSql = stub.queries.first { it.contains("fetch next") }
        for (sql in listOf(countSql, dataSql)) {
            assertTrue(
                sql.contains("order_details->>'material_id' is null"),
                "必须限定未绑目录（material_id 为空）: $sql",
            )
            assertTrue(sql.contains("order_type = "), "必须限定用药医嘱: $sql")
            assertTrue(sql.contains("status = "), "必须限定 ACTIVE: $sql")
            assertTrue(sql.contains("encounter_type = "), "必须限定养老入住: $sql")
            assertTrue(sql.contains("join healthcare.encounters"), "必须 join 入住表: $sql")
            assertTrue(sql.contains("join healthcare.patients"), "必须 join 患者表取姓名: $sql")
        }
        // jsonb 谓词必须是 plain SQL 表达式，不能被引号变成标识符
        assertTrue(
            stub.rawQueries.any { it.contains("order_details->>'material_id' is null") },
            "jsonb 谓词必须按表达式渲染: ${stub.rawQueries}",
        )
        assertTrue(dataSql.contains("order by"), "必须固定排序: $dataSql")
        assertTrue(
            dataSql.contains("medical_orders.created_at desc"),
            "created_at 必须倒序: $dataSql",
        )
        assertTrue(dataSql.contains("offset $"), "必须分页 offset: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "必须分页 limit: $dataSql")

        val countValues = stub.tuples.first { it.first == countSql }.second
        assertTrue(countValues.contains("MEDICATION"), "必须绑定 order_type: $countValues")
        assertTrue(countValues.contains("ACTIVE"), "必须绑定 status: $countValues")
        assertTrue(countValues.contains("ELDERLY_CARE"), "必须绑定 encounter_type: $countValues")
        val dataValues = stub.tuples.first { it.first == dataSql }.second
        assertTrue(dataValues.contains(50L) || dataValues.contains(50), "必须绑定 limit=50: $dataValues")
        assertTrue(dataValues.contains(0L) || dataValues.contains(0), "必须绑定 offset=0: $dataValues")
        assertFalse(
            countValues.any { it == null },
            "未绑目录谓词不得退化为绑定 null 参数: $countValues",
        )
    }

    @Test
    fun `待补绑清单search四列模糊匹配与encounter_id过滤`() {
        val stub = OrderBindingStub(
            listRows = bindingRowSet(),
            countRows = bindingRows(mapOf("total" to 0L)),
        )
        val result = await(
            service(stub, FakeCatalogPort(emptyMap()))
                .listUnboundMaterialOrders(stub.pool, "enc-9", " 张 ", 10, 20),
        )

        assertEquals(0, result.getJsonArray("records").size(), "空列表必须返回 records: []")
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))
        val dataSql = stub.queries.first { it.contains("fetch next") }
        assertTrue(dataSql.contains("medical_orders.order_content like $"), "必须匹配医嘱正文: $dataSql")
        assertTrue(
            dataSql.contains("order_details->>'drug_name'") && dataSql.contains("like $"),
            "必须匹配药品名: $dataSql",
        )
        assertTrue(dataSql.contains("healthcare.patients.name like $"), "必须匹配患者姓名: $dataSql")
        assertTrue(
            dataSql.contains("healthcare.encounters.encounter_no like $"),
            "必须匹配住院号: $dataSql",
        )
        val values = stub.tuples.first { it.first == dataSql }.second
        assertTrue(values.contains("enc-9"), "必须按 encounter_id 过滤: $values")
        assertTrue(values.contains("% 张 %"), "search 必须原样绑定模糊模式: $values")
    }

    @Test
    fun `待补绑清单空白encounter_id视为未提供`() {
        // 030 评审 P2-8：`?encounter_id=` 不得退化成 `encounter_id = ''`（会恒返回空集）
        val stub = OrderBindingStub(listRows = bindingRowSet(), countRows = bindingRows(mapOf("total" to 0L)))
        await(service(stub, FakeCatalogPort(emptyMap())).listUnboundMaterialOrders(stub.pool, "   ", null, 50, 0))

        val dataSql = stub.queries.first { it.contains("fetch next") }
        // 注意：join 谓词本身是 `medical_orders.encounter_id = healthcare.encounters.id`（无绑定参数），
        // 因此判据必须锚定「带占位符的过滤条件」，不能只看列名。
        assertFalse(
            dataSql.contains("medical_orders.encounter_id = $"),
            "空白 encounter_id 不得生成过滤条件: $dataSql",
        )
        assertTrue(
            stub.tuples.first { it.first == dataSql }.second.none { it == "" || it == "   " },
            "不得绑定空白 encounter_id: ${stub.tuples.first { it.first == dataSql }.second}",
        )
    }

    @Test
    fun `待补绑清单drug_name缺失时为null`() {
        val stub = OrderBindingStub(
            listRows = bindingRows(unboundOrderRow(mapOf("order_details" to JsonObject()))),
            countRows = bindingRows(mapOf("total" to 1L)),
        )
        val record = await(
            service(stub, FakeCatalogPort(emptyMap()))
                .listUnboundMaterialOrders(stub.pool, null, null, 50, 0),
        ).getJsonArray("records").getJsonObject(0)

        assertNull(record.getString("drug_name"), "存量医嘱无明细快照时 drug_name 必须为 null")
    }

    // ========================================================================
    //  2. 批量补绑：参数校验
    // ========================================================================

    @Test
    fun `批量补绑参数校验全部400且不触发SQL与事务`() {
        val stub = OrderBindingStub()
        val port = FakeCatalogPort(mapOf("mat-1" to drugMaterial("mat-1")))
        val target = service(stub, port)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(target.bindMaterialBatch(body, "pharmacist-1"))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(
                    cause.message?.contains(fragment) == true,
                    "期望文案含 [$fragment]，实际: ${cause.message}",
                )
            }
        }

        val item = JsonObject().put("order_id", "ord-1").put("material_id", "mat-1")

        expectInvalid(JsonObject().put("foo", "bar"), "unknown field: foo")
        expectInvalid(JsonObject(), "items is required")
        expectInvalid(JsonObject().put("items", null), "items is required")
        expectInvalid(JsonObject().put("items", "ord-1"), "items must be an array")
        expectInvalid(JsonObject().put("items", JsonObject()), "items must be an array")
        expectInvalid(JsonObject().put("items", JsonArray()), "items must not be empty")
        expectInvalid(
            JsonObject().put("items", JsonArray(List(101) { item })),
            "items must not exceed 100",
        )
        expectInvalid(
            JsonObject().put("items", JsonArray().add(item).add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-2"))),
            "duplicate order_id: ord-1",
        )
        expectInvalid(JsonObject().put("items", JsonArray().add("ord-1")), "items[0] must be an object")
        expectInvalid(
            JsonObject().put("items", JsonArray().add(JsonObject().put("order_id", "ord-1"))),
            "items[0].material_id is required",
        )
        expectInvalid(
            JsonObject().put("items", JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "  "))),
            "items[0].material_id is required",
        )
        expectInvalid(
            JsonObject().put("items", JsonArray().add(JsonObject().put("order_id", 1).put("material_id", "mat-1"))),
            "items[0].order_id must be a string",
        )
        expectInvalid(
            JsonObject().put("items", JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-1").put("dose", "100mg"))),
            "items[0] contains unsupported key: dose",
        )

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls, "校验失败不得开启事务")
        assertTrue(port.calls.isEmpty(), "校验失败不得读取目录")
    }

    // ========================================================================
    //  3. 批量补绑：全成全败
    // ========================================================================

    @Test
    fun `批量补绑目录解析失败时一条都不写入`() {
        val cases = listOf(
            Triple("物资不存在", emptyMap<String, DrugCatalogMaterial>(), HealthcareNotFoundException::class.java),
            Triple(
                "非药品",
                mapOf("mat-bad" to drugMaterial("mat-bad", category = "耗材")),
                IllegalArgumentException::class.java,
            ),
            Triple(
                "非启用",
                mapOf("mat-bad" to drugMaterial("mat-bad", status = "INACTIVE")),
                IllegalArgumentException::class.java,
            ),
        )
        for ((label, materials, expected) in cases) {
            val stub = OrderBindingStub(
                orderDetails = mutableMapOf("ord-1" to JsonObject(), "ord-2" to JsonObject()),
            )
            val port = FakeCatalogPort(materials + mapOf("mat-a" to drugMaterial("mat-a")))
            val body = JsonObject().put(
                "items",
                JsonArray()
                    .add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a"))
                    .add(JsonObject().put("order_id", "ord-2").put("material_id", "mat-bad")),
            )

            val cause = causeOf(service(stub, port).bindMaterialBatch(body, "pharmacist-1"))

            assertInstanceOf(expected, cause, label)
            assertTrue(
                cause.message?.contains("mat-bad") == true,
                "$label 错误文案必须指明物资: ${cause.message}",
            )
            assertEquals(1, stub.transactionCalls, "$label 必须在事务内失败以便整体回滚")
            assertEquals(
                listOf("mat-a", "mat-bad"),
                port.calls,
                "$label 必须先全量解析（含失败项）再写入",
            )
            assertTrue(
                stub.updateQueries().isEmpty(),
                "$label 时不得写入任何一条绑定: ${stub.updateQueries()}",
            )
            assertTrue(
                stub.queries.none { it.contains("for update") },
                "$label 时不得锁读医嘱：解析阶段失败即终止",
            )
        }
    }

    @Test
    fun `批量补绑端口未接线503且不开事务`() {
        val stub = OrderBindingStub()
        val body = JsonObject().put(
            "items",
            JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
        )
        val cause = causeOf(service(stub, null).bindMaterialBatch(body, "pharmacist-1"))

        assertInstanceOf(DrugCatalogUnavailableException::class.java, cause)
        assertEquals("drug catalog port is not configured", cause.message)
        assertEquals(0, stub.transactionCalls, "端口未接线时不得开启事务")
        assertTrue(stub.updateQueries().isEmpty(), "端口未接线时不得写入: ${stub.updateQueries()}")
    }

    @Test
    fun `批量补绑已绑定返回409并整体回滚`() {
        val stub = OrderBindingStub(
            orderDetails = mutableMapOf(
                "ord-1" to JsonObject(),
                "ord-2" to JsonObject().put("material_id", "mat-existing"),
            ),
        )
        val port = FakeCatalogPort(
            mapOf("mat-a" to drugMaterial("mat-a"), "mat-c" to drugMaterial("mat-c")),
        )
        val body = JsonObject().put(
            "items",
            JsonArray()
                .add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a"))
                .add(JsonObject().put("order_id", "ord-2").put("material_id", "mat-c")),
        )

        val cause = causeOf(service(stub, port).bindMaterialBatch(body, "pharmacist-1"))

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals(
            "order already bound to a drug material: mat-existing",
            cause.message,
            "已绑定必须沿用单条补绑原文案",
        )
        assertEquals(1, stub.transactionCalls, "整批必须在同一事务内，失败即整体回滚")
        assertEquals(1, stub.updateQueries().size, "冲突项之后的条目不得继续写入: ${stub.updateQueries()}")
        assertEquals(1, stub.writesOn("ord-1"), "冲突前的条目写入由事务回滚撤销")
        assertEquals(0, stub.writesOn("ord-2"), "冲突项自身不得写入")
    }

    @Test
    fun `批量补绑医嘱不存在404且不写入`() {
        val stub = OrderBindingStub(
            orderDetails = mutableMapOf("ord-1" to JsonObject()),
            // 作用域查询不会为不存在的医嘱返回行（P2-3 收口后由该查询先行判定 404）
            orderScope = mutableMapOf("ord-missing" to (null to null)),
        )
        val port = FakeCatalogPort(mapOf("mat-a" to drugMaterial("mat-a"), "mat-b" to drugMaterial("mat-b")))
        val body = JsonObject().put(
            "items",
            JsonArray()
                .add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a"))
                .add(JsonObject().put("order_id", "ord-missing").put("material_id", "mat-b")),
        )

        val cause = causeOf(service(stub, port).bindMaterialBatch(body, "pharmacist-1"))

        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertEquals("order not found: ord-missing", cause.message)
        assertEquals(1, stub.transactionCalls)
        // 030 评审 P2-3 收口后：作用域门禁（存在性/用药/活动）在任何写入之前判定，
        // 因此「批内第 2 条不存在」不再出现「先写第 1 条再回滚」，而是整批零写入。
        assertEquals(0, stub.updateQueries().size, "作用域门禁失败时整批不得写入任何一条: ${stub.updateQueries()}")
        assertEquals(0, stub.writesOn("ord-1"), "存在性未通过时整批零写入")
        assertEquals(0, stub.writesOn("ord-missing"), "不存在的医嘱不得写入")
    }

    @Test
    fun `批量补绑非用药医嘱400且零写入`() {
        val stub = OrderBindingStub(
            orderDetails = mutableMapOf("ord-1" to JsonObject()),
            orderScope = mutableMapOf("ord-1" to ("NURSING" to "ACTIVE")),
        )
        val port = FakeCatalogPort(mapOf("mat-a" to drugMaterial("mat-a")))
        val body = JsonObject().put(
            "items",
            JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
        )

        val cause = causeOf(service(stub, port).bindMaterialBatch(body, "pharmacist-1"))

        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertEquals("order is not a medication order: ord-1", cause.message)
        assertEquals(0, port.calls.size, "作用域不合格时不得调用目录端口: ${port.calls}")
        assertEquals(0, stub.updateQueries().size, "非用药医嘱不得写入")
    }

    @Test
    fun `批量补绑非活动医嘱409且零写入`() {
        val stub = OrderBindingStub(
            orderDetails = mutableMapOf("ord-1" to JsonObject()),
            orderScope = mutableMapOf("ord-1" to ("MEDICATION" to "COMPLETED")),
        )
        val port = FakeCatalogPort(mapOf("mat-a" to drugMaterial("mat-a")))
        val body = JsonObject().put(
            "items",
            JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
        )

        val cause = causeOf(service(stub, port).bindMaterialBatch(body, "pharmacist-1"))

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("order is not active: ord-1", cause.message)
        assertEquals(0, port.calls.size, "作用域不合格时不得调用目录端口: ${port.calls}")
        assertEquals(0, stub.updateQueries().size, "终态医嘱不得写入")
    }

    // ========================================================================
    //  4. 批量补绑：合法批次
    // ========================================================================

    @Test
    fun `批量补绑先全量解析再逐条写绑定与审计`() {
        val stub = OrderBindingStub(
            orderDetails = mutableMapOf("ord-1" to JsonObject(), "ord-2" to JsonObject()),
        )
        val port = FakeCatalogPort(
            mapOf(
                "mat-a" to drugMaterial("mat-a", code = "DEMO-DRUG-001", name = "阿司匹林"),
                "mat-b" to drugMaterial("mat-b", code = null, name = "布洛芬"),
            ),
        )
        val body = JsonObject().put(
            "items",
            JsonArray()
                .add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a"))
                .add(JsonObject().put("order_id", "ord-2").put("material_id", "mat-b")),
        )

        val result = await(service(stub, port).bindMaterialBatch(body, " pharmacist-1 "))

        assertEquals(2, result.getInteger("bound"))
        val items = result.getJsonArray("items")
        assertEquals(2, items.size())
        assertEquals("ord-1", items.getJsonObject(0).getString("order_id"))
        assertEquals("mat-a", items.getJsonObject(0).getString("material_id"))
        assertEquals("DEMO-DRUG-001", items.getJsonObject(0).getString("material_code"))
        assertEquals("阿司匹林", items.getJsonObject(0).getString("material_name"))
        assertEquals("ord-2", items.getJsonObject(1).getString("order_id"))
        assertEquals("mat-b", items.getJsonObject(1).getString("material_id"))
        assertNull(items.getJsonObject(1).getString("material_code"), "目录无编码时必须返回 null")
        assertEquals("布洛芬", items.getJsonObject(1).getString("material_name"))

        // 解析阶段先于写入阶段：目录调用顺序 = 请求顺序，且全部发生在锁读之前
        assertEquals(listOf("mat-a", "mat-b"), port.calls)
        assertEquals(1, stub.transactionCalls, "整批必须在同一事务内")
        val firstLock = stub.queries.indexOfFirst { it.contains("for update") }
        assertTrue(firstLock >= 0, "必须锁读医嘱: ${stub.queries}")
        assertTrue(
            stub.queries.drop(firstLock).all { it.contains("for update") || it.startsWith("update healthcare.medical_orders") },
            "锁读之后的语句只应有绑定写入: ${stub.queries}",
        )
        assertEquals(2, stub.updateQueries().size, "两条都必须写入: ${stub.updateQueries()}")

        val firstUpdate = stub.tuples.first { (sql, _) -> sql.startsWith("update healthcare.medical_orders") }
        assertEquals("ord-1", firstUpdate.second.last(), "绑定写入必须按请求顺序")
        val details = firstUpdate.second.filterIsInstance<JsonObject>().single()
        assertEquals("mat-a", details.getString("material_id"))
        assertEquals("DEMO-DRUG-001", details.getString("material_code"))
        assertEquals("阿司匹林", details.getString("material_name"))
        assertEquals("pharmacist-1", details.getString("material_bound_by"), "操作人必须 trim 后落库")
        assertNotNull(details.getString("material_bound_at"))
        offsetDateTime(details.getString("material_bound_at"))

        // 单条绑定只写 order_details 与 updated_at，不改临床 status/end_time
        val updateSql = firstUpdate.first
        assertFalse(updateSql.contains("end_time"), "不得改写临床终点: $updateSql")
        assertFalse(updateSql.contains("medical_orders.status"), "不得改写医嘱状态: $updateSql")
    }

    @Test
    fun `批量补绑空操作人为400不写库`() {
        val stub = OrderBindingStub(orderDetails = mutableMapOf("ord-1" to JsonObject()))
        val body = JsonObject().put(
            "items",
            JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
        )
        val cause = causeOf(
            service(stub, FakeCatalogPort(mapOf("mat-a" to drugMaterial("mat-a"))))
                .bindMaterialBatch(body, "   "),
        )

        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("operator must not be blank") == true, "got: ${cause.message}")
        assertEquals(0, stub.transactionCalls)
        assertTrue(stub.queries.isEmpty())
    }

    // ========================================================================
    //  5. 路由级
    // ========================================================================

    @Test
    fun `批量补绑路由空体400合法体200`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = OrderBindingStub(orderDetails = mutableMapOf("ord-1" to JsonObject()))
        val port = FakeCatalogPort(mapOf("mat-a" to drugMaterial("mat-a", code = "DEMO-DRUG-001", name = "阿司匹林")))
        withServer(vertx, stub, userId = "pharmacist-1", catalog = port) { serverPort ->
            // 空体：契约要求 400（items 必填），不得 NPE/500，也不得开启事务
            rawRequest(vertx, serverPort, HttpMethod.POST, "/healthcare/v1/orders/bind-material-batch", null)
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "空体必须 400: $body")
                        assertEquals("items is required", body.getString("error"))
                        assertEquals(0, stub.transactionCalls, "参数校验失败不得开启事务")
                    }
                    rawRequest(
                        vertx,
                        serverPort,
                        HttpMethod.POST,
                        "/healthcare/v1/orders/bind-material-batch",
                        JsonObject().put(
                            "items",
                            JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
                        ).encode(),
                    )
                }
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status, "合法体必须 200: $body")
                        assertEquals(1, body.getInteger("bound"))
                        assertEquals("ord-1", body.getJsonArray("items").getJsonObject(0).getString("order_id"))
                        assertEquals("pharmacist-1", boundOperator(stub), "操作人必须取认证主体")
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `批量补绑路由非JSON对象体400`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = OrderBindingStub()
        withServer(vertx, stub, userId = "pharmacist-1", catalog = FakeCatalogPort(emptyMap())) { serverPort ->
            rawRequest(
                vertx,
                serverPort,
                HttpMethod.POST,
                "/healthcare/v1/orders/bind-material-batch",
                JsonArray().add("ord-1").encode(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "JSON 数组体必须 400: $body")
                    assertEquals("body must be a JSON object", body.getString("error"))
                    assertTrue(stub.queries.isEmpty(), "结构错误不得触发 SQL: ${stub.queries}")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `批量补绑路由未认证401兜底`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = OrderBindingStub(orderDetails = mutableMapOf("ord-1" to JsonObject()))
        withServer(vertx, stub, userId = null, catalog = FakeCatalogPort(mapOf("mat-a" to drugMaterial("mat-a")))) { serverPort ->
            rawRequest(
                vertx,
                serverPort,
                HttpMethod.POST,
                "/healthcare/v1/orders/bind-material-batch",
                JsonObject().put(
                    "items",
                    JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
                ).encode(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "未注入认证中间件必须 401 兜底: $body")
                    assertEquals("authentication required", body.getString("error"))
                    assertEquals(0, stub.transactionCalls, "未认证不得写库")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `批量补绑路由目录端口未接线503`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = OrderBindingStub()
        withServer(vertx, stub, userId = "pharmacist-1", catalog = null) { serverPort ->
            rawRequest(
                vertx,
                serverPort,
                HttpMethod.POST,
                "/healthcare/v1/orders/bind-material-batch",
                JsonObject().put(
                    "items",
                    JsonArray().add(JsonObject().put("order_id", "ord-1").put("material_id", "mat-a")),
                ).encode(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(503, status, "端口未接线必须 fail-closed 503: $body")
                    assertEquals("drug catalog port is not configured", body.getString("error"))
                    assertEquals(0, stub.transactionCalls)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `待补绑静态路由不被泛型orders_id吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = OrderBindingStub(
            listRows = bindingRows(unboundOrderRow()),
            countRows = bindingRows(mapOf("total" to 1L)),
        )
        withServer(vertx, stub, userId = "pharmacist-1", catalog = FakeCatalogPort(emptyMap())) { serverPort ->
            rawRequest(vertx, serverPort, HttpMethod.GET, "/healthcare/v1/orders/unbound-material", null)
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status, "待补绑清单必须命中静态路由: $body")
                        assertEquals(1, body.getJsonArray("records").size())
                        assertEquals(1L, body.getJsonObject("meta").getLong("total"))
                        assertNull(body.getString("id"), "不得落入 /orders/:id 的医嘱详情响应")
                    }
                    // POST 静态段命中：未知键由服务端给出 400，而不是 404/405
                    rawRequest(
                        vertx,
                        serverPort,
                        HttpMethod.POST,
                        "/healthcare/v1/orders/bind-material-batch",
                        JsonObject().put("foo", "bar").encode(),
                    )
                }
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(400, status, "POST 静态段必须命中并返回校验 400: $body")
                        assertEquals("unknown field: foo", body.getString("error"))
                    }
                    // 同一路径的 GET 仍由泛型 /orders/:id 处理：两条路由并存，静态段优先。
                    // 清空详情行集，令泛型路由按「医嘱不存在」的既有语义返回 404。
                    stub.listRows = bindingRowSet()
                    rawRequest(vertx, serverPort, HttpMethod.GET, "/healthcare/v1/orders/bind-material-batch", null)
                }
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(404, status)
                        assertEquals("order not found: bind-material-batch", body.getString("error"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  嵌入式 HTTP 测试基础设施
    // ========================================================================

    private fun boundOperator(stub: OrderBindingStub): String? =
        stub.tuples
            .first { (sql, _) -> sql.startsWith("update healthcare.medical_orders") }
            .second
            .filterIsInstance<JsonObject>()
            .single()
            .getString("material_bound_by")

    private fun rawRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        rawBody: String?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (rawBody != null) req.putHeader("Content-Type", "application/json").send(rawBody) else req.send()
            }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try {
                        JsonObject(b)
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun <T> withServer(
        vertx: Vertx,
        stub: OrderBindingStub,
        userId: String?,
        catalog: DrugCatalogPort?,
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(
            HealthcareRoutes.create(vertx, stub.pool, drugCatalogPort = catalog),
        )
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose {
                server.close().map(Unit)
            }
        }
    }

    private fun offsetDateTime(value: String?) {
        OffsetDateTime.parse(requireNotNull(value) { "审计时间必须落库" })
    }
}

// ——— mock 基础设施（本文件私有，与 HealthcareMedicalOrderTest 的同名 helper 互不影响） ———

private fun bindingRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun bindingRowSet(vararg rows: Row): RowSet<Row> {
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

private fun bindingRows(vararg values: Map<String, Any?>): RowSet<Row> =
    bindingRowSet(*values.map { bindingRow(it) }.toTypedArray())

private fun bindingNormalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun bindingTupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}
