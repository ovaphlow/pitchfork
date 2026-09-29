package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 医嘱核心流程的非数据库测试（mockk + 嵌入式 HTTP）：
 *   - 创建医嘱输入校验、encounter/period 资格映射与事务回滚
 *   - 状态机联动、终态拒绝、任务缺失/异常与执行保留
 *   - 详情执行汇总、列表分页过滤与查询边界（无 N+1）
 *   - 离院/去世终局收束顺序、IN_PROGRESS 阻断、互斥与无副作用
 *   - 静态/具体路由不被泛型 encounter 路由吞掉
 */
@ExtendWith(VertxExtension::class)
class HealthcareMedicalOrderTest {

    /**
     * 全库 mock 桩：conn/pool 的 preparedQuery 按 normalized SQL 特征分发，
     * 捕获全部 SQL 与 tuple 以便断言顺序、查询边界与参数。
     */
    private class DatabaseStub(
        var encounters: RowSet<Row> = rowSet(),
        var periods: RowSet<Row> = rowSet(),
        var orders: RowSet<Row> = rowSet(),
        var tasks: RowSet<Row> = rowSet(),
        var executions: RowSet<Row> = rowSet(),
        var patients: RowSet<Row> = rowSet(),
        var countRows: RowSet<Row> = rowSet(),
        var selectOneEncounters: RowSet<Row> = rowSet(),
        var onEncounterUpdate: (() -> Unit)? = null,
        var onOrderUpdate: (() -> Unit)? = null,
        var onPatientUpdate: (() -> Unit)? = null,
        var failTaskInsert: Boolean = false,
    ) {
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
                tuples.add(sql to tupleValues(firstArg()))
                if (sql.contains("insert into nursing.nursing_tasks") && failTaskInsert) {
                    Future.failedFuture(IllegalStateException("task insert failed"))
                } else {
                    val branch = when {
                        // pharmacy 发药表探测（information_schema 计数）：本文件相关用例
                        // （详情汇总、路由 200）假定 pharmacy 迁移已挂载但无发药数据，
                        // 返回 cnt=2 让 present=true，给药汇总走真实查询分支并回落零值，
                        // 不至于因空行集在 rows.iterator().next() 处抛异常导致 500。
                        sql.contains("information_schema.tables") -> "pharmacy_tables_present"
                        sql.contains("insert into healthcare.medical_orders") -> "insert_orders"
                        sql.contains("insert into nursing.nursing_tasks") -> "insert_tasks"
                        sql.contains("update healthcare.encounters") -> {
                            onEncounterUpdate?.invoke()
                            "update_encounters"
                        }
                        sql.contains("update healthcare.patients") -> {
                            onPatientUpdate?.invoke()
                            "update_patients"
                        }
                        sql.contains("update healthcare.medical_orders") -> {
                            onOrderUpdate?.invoke()
                            "update_orders"
                        }
                        sql.contains("update nursing.nursing_tasks") ||
                            sql.contains("update nursing.nursing_service_periods") -> "update_tasks_or_periods"
                        sql.contains("nursing_task_executions") -> "executions"
                        // 029：活动养老入住存在性查询由 `select 1` 改为 `select encounter_no`
                        // （409 消息需带既有住院号），故按 status 过滤识别，而不是 `select 1`。
                        sql.contains("from healthcare.encounters") &&
                            sql.contains("encounters.status = ") -> "select_one_encounters"
                        sql.contains("count(*)") && sql.contains("from healthcare.medical_orders") -> "count_rows"
                        sql.contains("from healthcare.medical_orders") -> "orders"
                        sql.contains("from healthcare.patients") -> "patients"
                        sql.contains("from healthcare.encounters") -> "encounters"
                        sql.contains("nursing_service_periods") -> "periods"
                        sql.contains("nursing_tasks") -> "tasks"
                        else -> "else"
                    }
                    val result = when (branch) {
                        "insert_orders", "insert_tasks", "update_encounters", "update_patients",
                        "update_orders", "update_tasks_or_periods", "else" -> rowSet()
                        "pharmacy_tables_present" -> rows(mapOf("cnt" to 2L))
                        "executions" -> executions
                        "select_one_encounters" -> selectOneEncounters
                        "count_rows" -> countRows
                        "patients" -> patients
                        "encounters" -> encounters
                        "orders" -> orders
                        "periods" -> periods
                        "tasks" -> tasks
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

    // ——— fixture 行 ———

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

    private fun periodRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "per-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to null,
            "coordinator" to "钱协调",
            "encounter_id" to "enc-1",
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun orderRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "ord-1",
            "encounter_id" to "enc-1",
            "order_type" to "MEDICATION",
            "order_content" to "阿司匹林 100mg 每日一次",
            "order_details" to JsonObject()
                .put("drug_name", "阿司匹林")
                .put("dose", "100mg")
                .put("frequency_code", "QD")
                .put("frequency_name", "每日一次"),
            "start_time" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "end_time" to null,
            "doctor" to "赵医生",
            "status" to "ACTIVE",
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "task_id" to "tsk-1",
        )
        base.putAll(overrides)
        return base
    }

    private fun taskRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "tsk-1",
            "period_id" to "per-1",
            "encounter_id" to "enc-1",
            "order_item_id" to "ord-1",
            "task_type" to "MEDICATION",
            "description" to "阿司匹林 100mg 每日一次",
            "frequency_code" to "QD",
            "frequency_name" to "每日一次",
            "start_date" to LocalDate.of(2026, 8, 1),
            "end_date" to LocalDate.of(2026, 8, 4),
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun medDetails(duration: Any? = null): JsonObject {
        val details = JsonObject()
            // 025 MEDICATION 必须绑定目录药品：material_id 必填，drug_name 由服务端按目录覆写
            .put("material_id", "mat-1")
            .put("drug_name", "阿司匹林")
            .put("dose", "100mg")
            .put("frequency_code", "QD")
            .put("frequency_name", "每日一次")
        if (duration != null) details.put("duration_days", duration)
        return details
    }

    /**
     * 025 目录药品端口桩：默认返回与 `drug_name` 一致的 ACTIVE 药品；
     * `materialId` 不匹配时返回 null（模拟目录中不存在）。
     */
    private fun drugCatalogPort(
        name: String? = "阿司匹林",
        category: String? = "药品",
        status: String? = "ACTIVE",
        code: String? = "DEMO-DRUG-001",
        materialId: String = "mat-1",
    ): DrugCatalogPort = object : DrugCatalogPort {
        override fun findDrugMaterial(client: io.vertx.sqlclient.SqlClient, materialId2: String) =
            Future.succeededFuture(
                if (materialId2 != materialId) {
                    null
                } else {
                    DrugCatalogMaterial(
                        id = materialId,
                        code = code,
                        name = name,
                        spec = "100mg/片",
                        baseUnit = "片",
                        status = status,
                        category = category,
                    )
                },
            )
    }

    private fun validOrderBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("order_type", "MEDICATION")
            .put("order_class", "LONG_TERM")
            .put("order_content", "阿司匹林 100mg 每日一次")
            .put("doctor", "赵医生")
            .put("start_time", "2026-08-01T09:00:00+08:00")
            .put("order_details", medDetails())
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

    // ——— 1. 输入校验 ———

    @Test
    fun `创建医嘱输入校验全部返回400且不触发SQL`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        fun expectInvalid(body: JsonObject, vararg fragments: String) {
            val cause = causeOf(service.createOrder("enc-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(validOrderBody(mapOf("order_type" to "NURSING")), "invalid order_type")
        expectInvalid(validOrderBody(mapOf("order_type" to "DIET")), "invalid order_type")
        expectInvalid(validOrderBody(mapOf("order_type" to "VACCINE")), "invalid order_type")
        expectInvalid(validOrderBody(mapOf("order_type" to null)), "invalid order_type")

        expectInvalid(validOrderBody(mapOf("order_content" to null)), "order_content is required")
        expectInvalid(validOrderBody(mapOf("order_content" to "   ")), "order_content is required")
        expectInvalid(validOrderBody(mapOf("order_content" to "a".repeat(2001))), "2000")

        expectInvalid(validOrderBody(mapOf("doctor" to null)), "doctor is required")
        expectInvalid(validOrderBody(mapOf("doctor" to "a".repeat(101))), "100")

        expectInvalid(validOrderBody(mapOf("start_time" to null)), "start_time is required")
        expectInvalid(validOrderBody(mapOf("start_time" to "2026-08-01 09:00")), "ISO-8601")

        expectInvalid(validOrderBody(mapOf("order_details" to JsonArray().add("x"))), "must be a JSON object")
        expectInvalid(validOrderBody(mapOf("order_details" to "raw")), "must be a JSON object")

        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_details" to JsonObject()
                        .put("drug_name", "阿司匹林")
                        .put("drug_code", "ASPIRIN"),
                ),
            ),
            "unsupported keys",
        )
        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_details" to JsonObject()
                        .put("drug_name", "阿司匹林")
                        .put("metadata", JsonObject().put("hacked", true)),
                ),
            ),
            "unsupported keys",
        )
        expectInvalid(
            validOrderBody(mapOf("order_details" to JsonObject().put("dose", "100mg"))),
            "material_id is required for MEDICATION order",
        )
        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_type" to "THERAPY",
                    "order_details" to JsonObject()
                        .put("frequency_code", "QD")
                        .put("frequency_name", "每日一次"),
                ),
            ),
            "treatment_item is required",
        )
        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_type" to "EXAMINATION",
                    "order_details" to JsonObject().put("remark", "空腹"),
                ),
            ),
            "item_name is required",
        )
        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_type" to "LAB_TEST",
                    "order_details" to JsonObject().put("remark", "晨尿"),
                ),
            ),
            "item_name is required",
        )

        expectInvalid(validOrderBody(mapOf("order_details" to medDetails(-1))), "positive integer")
        expectInvalid(validOrderBody(mapOf("order_details" to medDetails(0))), "positive integer")
        expectInvalid(validOrderBody(mapOf("order_details" to medDetails(1.5))), "positive integer")
        expectInvalid(validOrderBody(mapOf("order_details" to medDetails("7"))), "positive integer")

        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_details" to JsonObject()
                        .put("material_id", "mat-1")
                        .put("drug_name", "阿司匹林")
                        .put("frequency_code", "QD"),
                ),
            ),
            "provided together",
        )
        expectInvalid(
            validOrderBody(
                mapOf(
                    "order_details" to JsonObject()
                        .put("material_id", "mat-1")
                        .put("drug_name", "阿司匹林")
                        .put("dose", 100),
                ),
            ),
            "dose must be a string",
        )

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
        assertEquals(0, stub.transactionCalls)
    }

    // ——— 2. 资格映射 ———

    @Test
    fun `创建医嘱资格映射404与409`() {
        val notFound = DatabaseStub(encounters = rowSet(), periods = rowSet())
        val cause1 = causeOf(HealthcareService(notFound.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause1)
        assertTrue(cause1.message?.contains("encounter not found") == true, "got: ${cause1.message}")

        val notElderly = DatabaseStub(encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))))
        val cause2 = causeOf(HealthcareService(notElderly.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("not an elderly admission") == true, "got: ${cause2.message}")

        val notActive = DatabaseStub(
            encounters = rows(encounterRow(mapOf("status" to "DISCHARGED"))),
            periods = rows(periodRow()),
        )
        val cause3 = causeOf(HealthcareService(notActive.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(ConflictException::class.java, cause3)
        assertTrue(cause3.message?.contains("encounter is not active") == true, "got: ${cause3.message}")

        val noPeriod = DatabaseStub(encounters = rows(encounterRow()), periods = rowSet())
        val cause4 = causeOf(HealthcareService(noPeriod.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(ConflictException::class.java, cause4)
        assertTrue(cause4.message?.contains("no bound nursing care period") == true, "got: ${cause4.message}")

        val closedPeriod = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("status" to "COMPLETED"))),
        )
        val cause5 = causeOf(HealthcareService(closedPeriod.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(ConflictException::class.java, cause5)
        assertTrue(cause5.message?.contains("not open") == true, "got: ${cause5.message}")

        val cancelledPeriod = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("status" to "CANCELLED"))),
        )
        val cause6 = causeOf(HealthcareService(cancelledPeriod.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(ConflictException::class.java, cause6)
        assertTrue(cause6.message?.contains("not open") == true, "got: ${cause6.message}")

        val patientMismatch = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow(mapOf("patient_id" to "pat-other"))),
        )
        val cause7 = causeOf(HealthcareService(patientMismatch.pool).createOrder("enc-1", validOrderBody()))
        assertInstanceOf(ConflictException::class.java, cause7)
        assertTrue(cause7.message?.contains("patient_id mismatch") == true, "got: ${cause7.message}")
    }

    // ——— 3. 创建成功与事务 ———

    @Test
    fun `创建医嘱成功返回201形态对象且医嘱任务同事务写入`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
        )
        val service = HealthcareService(stub.pool, drugCatalogPort = drugCatalogPort())

        val body = validOrderBody(
            mapOf(
                "order_details" to JsonObject()
                    .put("material_id", "mat-1")
                    .put("drug_name", "阿司匹林")
                    .put("dose", "100mg")
                    .put("unit", "片")
                    .put("route", "口服")
                    .put("frequency_code", "QD")
                    .put("frequency_name", "每日一次")
                    .put("duration_days", 3),
            ),
        )
        val order = service.createOrder("enc-1", body).toCompletionStage().toCompletableFuture().get()

        assertEquals("ord-1", order.getString("id"))
        assertEquals("enc-1", order.getString("encounter_id"))
        assertEquals("MEDICATION", order.getString("order_type"))
        assertEquals("阿司匹林 100mg 每日一次", order.getString("order_content"))
        assertNotNull(order.getJsonObject("order_details"))
        assertEquals("2026-08-01T09:00+08:00", order.getString("start_time"))
        assertTrue(order.containsKey("end_time"))
        assertNull(order.getString("end_time"))
        assertEquals("赵医生", order.getString("doctor"))
        assertEquals("ACTIVE", order.getString("status"))
        assertEquals("tsk-1", order.getString("task_id"))

        val orderInsert = stub.tuples.first { it.first.contains("insert into healthcare.medical_orders") }
        val taskInsert = stub.tuples.first { it.first.contains("insert into nursing.nursing_tasks") }
        assertTrue(orderInsert.first.contains("order_details"), "医嘱 insert 必须含 order_details JSONB 列: ${orderInsert.first}")
        assertTrue(taskInsert.first.contains("order_item_id"), "任务 insert 必须含 order_item_id 列: ${taskInsert.first}")
        val orderId = orderInsert.second.filterIsInstance<String>().first { it.length == 26 }
        assertTrue(taskInsert.second.contains(orderId), "任务 order_item_id 必须等于新医嘱 id")
        assertTrue(taskInsert.second.contains("per-1"))
        assertTrue(taskInsert.second.contains("enc-1"))
        assertTrue(taskInsert.second.contains("MEDICATION"))
        assertTrue(taskInsert.second.contains("阿司匹林 100mg 每日一次"))
        assertTrue(taskInsert.second.contains("QD"))
        assertTrue(taskInsert.second.contains("每日一次"))
        assertTrue(taskInsert.second.contains(LocalDate.of(2026, 8, 1)), "start_date 必须为上海业务日")
        assertTrue(taskInsert.second.contains(LocalDate.of(2026, 8, 4)), "end_date 必须为 start+duration_days")
        assertTrue(taskInsert.second.contains("ACTIVE"))
        val details = orderInsert.second.filterIsInstance<JsonObject>().first { it.containsKey("drug_name") }
        assertEquals("阿司匹林", details.getString("drug_name"))
        // 025 目录覆写：material_id 来自客户端，code/name 一律取目录快照
        assertEquals("mat-1", details.getString("material_id"))
        assertEquals("阿司匹林", details.getString("material_name"))
        assertEquals("DEMO-DRUG-001", details.getString("material_code"))

        // 读取无副作用：不执行任何执行生成/写入
        assertTrue(stub.queries.none { it.contains("nursing_task_executions") })
        assertEquals(1, stub.transactionCalls)
    }

    @Test
    fun `无duration_days时任务end_date不绑定`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
        )
        HealthcareService(stub.pool, drugCatalogPort = drugCatalogPort()).createOrder("enc-1", validOrderBody())
            .toCompletionStage().toCompletableFuture().get()

        val taskInsert = stub.tuples.first { it.first.contains("insert into nursing.nursing_tasks") }
        assertFalse(taskInsert.first.contains("end_date"), "end_date 为 null 时不得绑定该列: ${taskInsert.first}")
    }

    @Test
    fun `THERAPY医嘱派生TREATMENT任务`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rows(orderRow(mapOf("order_type" to "THERAPY"))),
        )
        val body = validOrderBody(
            mapOf(
                "order_type" to "THERAPY",
                "order_details" to JsonObject()
                    .put("treatment_item", "推拿")
                    .put("frequency_code", "QD")
                    .put("frequency_name", "每日一次"),
            ),
        )
        HealthcareService(stub.pool).createOrder("enc-1", body)
            .toCompletionStage().toCompletableFuture().get()

        val taskInsert = stub.tuples.first { it.first.contains("insert into nursing.nursing_tasks") }
        assertTrue(taskInsert.second.contains("TREATMENT"), "THERAPY 必须派生 TREATMENT 任务")
    }

    @Test
    fun `任务写入失败时创建医嘱整体失败`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
            failTaskInsert = true,
        )
        val service = HealthcareService(stub.pool, drugCatalogPort = drugCatalogPort())

        val cause = causeOf(service.createOrder("enc-1", validOrderBody()))
        assertInstanceOf(IllegalStateException::class.java, cause)
        assertEquals("task insert failed", cause.message)
        // 医嘱 insert 已执行但整体失败被传播（mock 层无提交语义，验证 withTransaction handler 异常传播）
        assertTrue(stub.queries.any { it.contains("insert into healthcare.medical_orders") })
        assertTrue(stub.queries.any { it.contains("insert into nursing.nursing_tasks") })
        assertEquals(1, stub.transactionCalls)
    }

    // ——— 4. 状态机 ———

    @Test
    fun `ACTIVE转DISCONTINUED联动取消任务并写end_time`() {
        val order = orderRow()
        val task = taskRow()
        val stub = DatabaseStub(
            orders = rows(order),
            tasks = rows(task),
            onOrderUpdate = {
                order["status"] = "DISCONTINUED"
                order["end_time"] = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
            },
        )
        val service = HealthcareService(stub.pool)

        val updated = service.updateOrderStatus("ord-1", JsonObject().put("status", "DISCONTINUED"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("DISCONTINUED", updated.getString("status"))
        assertNotNull(updated.getString("end_time"))

        val taskUpdate = stub.queries.first { it.contains("update nursing.nursing_tasks") }
        assertTrue(taskUpdate.contains("set status = $"), "任务必须联动更新 status: $taskUpdate")
        assertTrue(stub.tuples.first { it.first.contains("update nursing.nursing_tasks") }.second.contains("CANCELLED"))
        val orderUpdate = stub.queries.first { it.contains("update healthcare.medical_orders") }
        assertTrue(orderUpdate.contains("set status = $"), "医嘱必须更新 status: $orderUpdate")
        assertTrue(
            stub.tuples.first { it.first.contains("update healthcare.medical_orders") }.second.contains("DISCONTINUED"),
            "医嘱必须绑定 DISCONTINUED",
        )
        assertTrue(orderUpdate.contains("end_time"), "医嘱更新必须写 end_time: $orderUpdate")
        // 既有执行不删除、不改写
        assertTrue(stub.queries.none { it.contains("nursing_task_executions") })
        assertTrue(stub.queries.none { it.contains("delete") })
    }

    @Test
    fun `ACTIVE转CANCELLED与COMPLETED的任务联动`() {
        val order1 = orderRow()
        val stub1 = DatabaseStub(
            orders = rows(order1),
            tasks = rows(taskRow()),
            onOrderUpdate = {
                order1["status"] = "CANCELLED"
                order1["end_time"] = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
            },
        )
        val updated1 = HealthcareService(stub1.pool)
            .updateOrderStatus("ord-1", JsonObject().put("status", "CANCELLED"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("CANCELLED", updated1.getString("status"))
        assertTrue(stub1.queries.first { it.contains("update nursing.nursing_tasks") }.contains("set status = $"))
        assertTrue(stub1.tuples.first { it.first.contains("update nursing.nursing_tasks") }.second.contains("CANCELLED"))

        val order2 = orderRow()
        val stub2 = DatabaseStub(
            orders = rows(order2),
            tasks = rows(taskRow()),
            onOrderUpdate = {
                order2["status"] = "COMPLETED"
                order2["end_time"] = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
            },
        )
        val updated2 = HealthcareService(stub2.pool)
            .updateOrderStatus("ord-1", JsonObject().put("status", "COMPLETED"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("COMPLETED", updated2.getString("status"))
        assertTrue(stub2.queries.first { it.contains("update nursing.nursing_tasks") }.contains("set status = $"))
        assertTrue(stub2.tuples.first { it.first.contains("update nursing.nursing_tasks") }.second.contains("COMPLETED"))
    }

    @Test
    fun `终态医嘱再次转换返回409`() {
        for (terminal in listOf("DISCONTINUED", "CANCELLED", "COMPLETED")) {
            val stub = DatabaseStub(orders = rows(orderRow(mapOf("status" to terminal))))
            val cause = causeOf(
                HealthcareService(stub.pool).updateOrderStatus("ord-1", JsonObject().put("status", "COMPLETED")),
            )
            assertInstanceOf(ConflictException::class.java, cause)
            assertTrue(cause.message?.contains("already") == true, "got: ${cause.message}")
        }
    }

    @Test
    fun `非法状态目标与缺失status返回400`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        val cause1 = causeOf(service.updateOrderStatus("ord-1", JsonObject()))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertEquals("status is required", cause1.message)

        val cause2 = causeOf(service.updateOrderStatus("ord-1", JsonObject().put("status", "FOO")))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("invalid order status") == true, "got: ${cause2.message}")

        val stub3 = DatabaseStub(orders = rows(orderRow()))
        val cause3 = causeOf(HealthcareService(stub3.pool).updateOrderStatus("ord-1", JsonObject().put("status", "ACTIVE")))
        assertInstanceOf(IllegalArgumentException::class.java, cause3)
        assertTrue(cause3.message?.contains("cannot transition") == true, "got: ${cause3.message}")

        assertTrue(stub.queries.isEmpty())
        assertTrue(stub3.queries.none { it.startsWith("update") }, "非法转换不得发出任何 update")
    }

    @Test
    fun `医嘱不存在返回404`() {
        val stub = DatabaseStub(orders = rowSet())
        val cause = causeOf(
            HealthcareService(stub.pool).updateOrderStatus("ord-1", JsonObject().put("status", "DISCONTINUED")),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("order not found") == true, "got: ${cause.message}")
    }

    @Test
    fun `关联任务缺失或状态异常返回409且医嘱不更新`() {
        val stub1 = DatabaseStub(orders = rows(orderRow()), tasks = rowSet())
        val cause1 = causeOf(
            HealthcareService(stub1.pool).updateOrderStatus("ord-1", JsonObject().put("status", "DISCONTINUED")),
        )
        assertInstanceOf(ConflictException::class.java, cause1)
        assertTrue(cause1.message?.contains("no linked task") == true, "got: ${cause1.message}")
        assertTrue(stub1.queries.none { it.contains("update healthcare.medical_orders") })

        val stub2 = DatabaseStub(orders = rows(orderRow()), tasks = rows(taskRow(mapOf("status" to "COMPLETED"))))
        val cause2 = causeOf(
            HealthcareService(stub2.pool).updateOrderStatus("ord-1", JsonObject().put("status", "DISCONTINUED")),
        )
        assertInstanceOf(ConflictException::class.java, cause2)
        assertTrue(cause2.message?.contains("unexpected status") == true, "got: ${cause2.message}")
        assertTrue(stub2.queries.none { it.contains("update healthcare.medical_orders") })
    }

    // ——— 5. 详情与列表 ———

    @Test
    fun `获取医嘱详情返回固定五键空执行汇总`() {
        val stub = DatabaseStub(orders = rows(orderRow()), executions = rowSet())
        val detail = HealthcareService(stub.pool).getOrder("ord-1").toCompletionStage().toCompletableFuture().get()

        assertEquals("ord-1", detail.getString("id"))
        assertEquals("tsk-1", detail.getString("task_id"))
        val summary = detail.getJsonObject("execution_summary")
        for (key in listOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")) {
            assertTrue(summary.containsKey(key), "执行汇总必须包含 $key")
            assertEquals(0L, summary.getLong(key))
        }

        val summarySql = stub.queries.first { it.contains("nursing_task_executions") && it.contains("group by") }
        assertTrue(summarySql.contains("join nursing.nursing_tasks"), "汇总必须按任务聚合: $summarySql")
        assertTrue(summarySql.contains("order_item_id = $1"), "汇总必须按 order_item_id 精确聚合: $summarySql")
        assertEquals(1, stub.queries.count { it.contains("nursing_task_executions") }, "执行汇总必须是单次聚合查询")
    }

    @Test
    fun `执行汇总按任务聚合且不泄漏其他数据`() {
        val stub = DatabaseStub(
            orders = rows(orderRow()),
            executions = rows(
                mapOf("status" to "PENDING", "cnt" to 2L),
                mapOf("status" to "COMPLETED", "cnt" to 1L),
                mapOf("status" to "CANCELLED", "cnt" to 3L),
            ),
        )
        val detail = HealthcareService(stub.pool).getOrder("ord-1").toCompletionStage().toCompletableFuture().get()
        val summary = detail.getJsonObject("execution_summary")
        assertEquals(2L, summary.getLong("PENDING"))
        assertEquals(1L, summary.getLong("COMPLETED"))
        assertEquals(3L, summary.getLong("CANCELLED"))
        assertEquals(0L, summary.getLong("IN_PROGRESS"))
        assertEquals(0L, summary.getLong("SKIPPED"))
    }

    @Test
    fun `医嘱不存在时详情返回404`() {
        val stub = DatabaseStub(orders = rowSet(), executions = rowSet())
        val cause = causeOf(HealthcareService(stub.pool).getOrder("ord-1"))
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)
        assertTrue(cause.message?.contains("order not found") == true, "got: ${cause.message}")
    }

    @Test
    fun `列表医嘱分页过滤且返回records与meta`() {
        val stub = DatabaseStub(
            orders = rows(orderRow(), orderRow(mapOf("id" to "ord-2", "task_id" to "tsk-2"))),
            countRows = rows(mapOf("total" to 2L)),
        )
        val list = HealthcareService(stub.pool)
            .listOrders("enc-1", orderType = "MEDICATION", status = "ACTIVE", limit = 10, offset = 5)
            .toCompletionStage().toCompletableFuture().get()

        val records = list.getJsonArray("records")
        assertEquals(2, records.size())
        assertEquals(2L, list.getJsonObject("meta").getLong("total"))
        assertEquals("tsk-1", records.getJsonObject(0).getString("task_id"))
        assertEquals("tsk-2", records.getJsonObject(1).getString("task_id"))

        val countSql = stub.queries.first { it.contains("count") }
        val dataSql = stub.queries.first { it.contains("fetch next") }
        assertTrue(countSql.contains("join nursing.nursing_tasks"), "列表必须 join 任务表: $countSql")
        assertTrue(countSql.contains("order_type = $"), "列表必须按 order_type 过滤: $countSql")
        assertTrue(countSql.contains("status = $"), "列表必须按 status 过滤: $countSql")
        assertTrue(dataSql.contains("offset $"), "列表必须分页 offset: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "列表必须分页 limit: $dataSql")
        assertTrue(dataSql.contains("task_id"), "列表查询必须取 task_id: $dataSql")
    }

    @Test
    fun `列表为空时返回空records与total0`() {
        val stub = DatabaseStub(orders = rowSet(), countRows = rows(mapOf("total" to 0L)))
        val list = HealthcareService(stub.pool)
            .listOrders("enc-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, list.getJsonArray("records").size())
        assertEquals(0L, list.getJsonObject("meta").getLong("total"))
    }

    @Test
    fun `待接方列表返回医嘱药品绑定字段历史医嘱为null`() {
        val bound = orderRow(
            mapOf(
                "order_id" to "ord-1",
                "order_details" to JsonObject()
                    .put("drug_name", "阿司匹林")
                    .put("material_id", "mat-1")
                    .put("material_code", "DEMO-DRUG-001")
                    .put("material_name", "阿司匹林"),
            ),
        )
        // 存量自由文本医嘱：只有 drug_name，绑定字段必须为 null（前端据此走补绑）
        val legacy = orderRow(
            mapOf(
                "id" to "ord-2",
                "order_id" to "ord-2",
                "order_details" to JsonObject().put("drug_name", "降压药A"),
            ),
        )
        val stub = DatabaseStub(orders = rows(bound, legacy), countRows = rows(mapOf("total" to 2L)))
        val result = HealthcareService(stub.pool)
            .listMedicationOrdersForPharmacy(stub.pool, null, null, 50, 0)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2L, result.getJsonObject("meta").getLong("total"))
        val records = result.getJsonArray("records")
        val boundRecord = records.getJsonObject(0)
        assertEquals("mat-1", boundRecord.getString("material_id"))
        assertEquals("DEMO-DRUG-001", boundRecord.getString("material_code"))
        assertEquals("阿司匹林", boundRecord.getString("material_name"))
        val legacyRecord = records.getJsonObject(1)
        assertNull(legacyRecord.getString("material_id"), "历史医嘱 material_id 必须为 null")
        assertNull(legacyRecord.getString("material_code"), "历史医嘱 material_code 必须为 null")
        assertNull(legacyRecord.getString("material_name"), "历史医嘱 material_name 必须为 null")
        assertEquals("降压药A", legacyRecord.getString("drug_name"), "历史自由文本药名仍原样返回")
    }

    // ——— 6. 终局编排 ———

    @Test
    fun `离院成功按序收束医嘱任务周期与encounter`() {
        val dischargeDate = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
        val enc = encounterRow()
        val stub = DatabaseStub(
            encounters = rows(enc),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
            tasks = rows(taskRow()),
            onEncounterUpdate = {
                enc["status"] = "DISCHARGED"
                enc["discharge_date"] = dischargeDate
            },
        )
        val result = HealthcareService(stub.pool)
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-08-02T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("DISCHARGED", result.getString("status"))
        assertEquals(dischargeDate.toString(), result.getString("discharge_date"))

        val orderUpdate = stub.queries.first { it.contains("update healthcare.medical_orders") }
        val taskUpdate = stub.queries.first { it.contains("update nursing.nursing_tasks") }
        val periodUpdate = stub.queries.first { it.contains("update nursing.nursing_service_periods") }
        // 结算收束先写 encounters.settled_at（冻结标记），终态更新须按 discharge_date 定位
        val encounterUpdate = stub.queries.first { it.contains("update healthcare.encounters") && it.contains("discharge_date") }
        assertTrue(orderUpdate.contains("set status = $"), "活动医嘱必须更新 status: $orderUpdate")
        assertTrue(
            stub.tuples.first { it.first.contains("update healthcare.medical_orders") }.second.contains("DISCONTINUED"),
            "活动医嘱必须绑定 DISCONTINUED",
        )
        assertTrue(orderUpdate.contains("end_time"), "医嘱 end_time 必须写入: $orderUpdate")
        assertTrue(taskUpdate.contains("set status = $"), "活动任务必须更新 status: $taskUpdate")
        assertTrue(
            stub.tuples.first { it.first.contains("update nursing.nursing_tasks") }.second.contains("CANCELLED"),
            "活动任务必须绑定 CANCELLED",
        )
        assertTrue(periodUpdate.contains("set status = $"), "周期必须更新 status: $periodUpdate")
        assertTrue(
            stub.tuples.first { it.first.contains("update nursing.nursing_service_periods") }.second.contains("COMPLETED"),
            "周期必须绑定 COMPLETED",
        )
        assertTrue(
            stub.queries.indexOf(orderUpdate) < stub.queries.indexOf(taskUpdate) &&
                stub.queries.indexOf(taskUpdate) < stub.queries.indexOf(periodUpdate) &&
                stub.queries.indexOf(periodUpdate) < stub.queries.indexOf(encounterUpdate),
            "收束顺序必须为医嘱→任务→周期→encounter",
        )
        assertTrue(
            stub.tuples.first { it.first.contains("update healthcare.medical_orders") }.second.contains(dischargeDate),
            "医嘱 end_time 必须等于离院时间",
        )

        // 无副作用：不生成/更新执行、不扣库存、不写护理记录、不归档摘要
        assertTrue(stub.queries.none { it.contains("insert into nursing.nursing_task_executions") })
        assertTrue(stub.queries.none { it.contains("update nursing.nursing_task_executions") })
        assertTrue(stub.queries.none { it.contains("stock_operation") })
        assertTrue(stub.queries.none { it.contains("medical_records") })
        assertTrue(stub.queries.none { it.contains("discharge_summary") })
        assertTrue(stub.queries.none { it.contains("inventories") })
    }

    @Test
    fun `离院遇到进行中执行整体失败且无更新发出`() {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rowSet(),
            tasks = rowSet(),
            executions = rows(mapOf("status" to "IN_PROGRESS")),
        )
        val cause = causeOf(
            HealthcareService(stub.pool)
                .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-08-02T10:00:00+08:00")),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("in progress") == true, "got: ${cause.message}")
        assertTrue(stub.queries.none { it.startsWith("update") }, "IN_PROGRESS 阻断时不得发出任何 update")
    }

    @Test
    fun `非养老离院不触碰医嘱任务与周期`() {
        val dischargeDate = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
        val enc = encounterRow(mapOf("encounter_type" to "OUTPATIENT"))
        val stub = DatabaseStub(
            encounters = rows(enc),
            onEncounterUpdate = {
                enc["status"] = "DISCHARGED"
                enc["discharge_date"] = dischargeDate
            },
        )
        val result = HealthcareService(stub.pool)
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-08-02T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("DISCHARGED", result.getString("status"))
        assertTrue(stub.queries.none { it.contains("medical_orders") })
        assertTrue(stub.queries.none { it.contains("nursing_tasks") })
        assertTrue(stub.queries.none { it.contains("nursing_service_periods") })
        assertEquals(1, stub.queries.count { it.contains("update healthcare.encounters") })
    }

    @Test
    fun `去世输入校验返回400`() {
        val stub = DatabaseStub()
        val service = HealthcareService(stub.pool)

        val cause1 = causeOf(service.deathEncounter("enc-1", JsonObject()))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("death_date is required") == true, "got: ${cause1.message}")

        val cause2 = causeOf(service.deathEncounter("enc-1", JsonObject().put("death_date", "not-a-date")))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("ISO-8601") == true, "got: ${cause2.message}")

        val cause3 = causeOf(
            service.deathEncounter(
                "enc-1",
                JsonObject().put("death_date", "2026-08-02T14:00:00+08:00").put("death_cause", 123),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause3)
        assertTrue(cause3.message?.contains("death_cause must be a string") == true, "got: ${cause3.message}")

        val cause4 = causeOf(
            service.deathEncounter(
                "enc-1",
                JsonObject().put("death_date", "2026-08-02T14:00:00+08:00").put("death_cause", "x".repeat(501)),
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause4)
        assertTrue(cause4.message?.contains("500") == true, "got: ${cause4.message}")

        assertTrue(stub.queries.isEmpty())
    }

    @Test
    fun `去世资格映射404_400_409`() {
        val notFound = DatabaseStub(encounters = rowSet())
        val cause1 = causeOf(
            HealthcareService(notFound.pool).deathEncounter("enc-1", deathBody()),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, cause1)
        assertTrue(cause1.message?.contains("encounter not found") == true, "got: ${cause1.message}")

        val notElderly = DatabaseStub(encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))))
        val cause2 = causeOf(HealthcareService(notElderly.pool).deathEncounter("enc-1", deathBody()))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("not an elderly admission") == true, "got: ${cause2.message}")

        for (terminal in listOf("DISCHARGED", "DECEASED")) {
            val stub = DatabaseStub(encounters = rows(encounterRow(mapOf("status" to terminal))))
            val cause = causeOf(HealthcareService(stub.pool).deathEncounter("enc-1", deathBody()))
            assertInstanceOf(ConflictException::class.java, cause)
            assertTrue(cause.message?.contains("encounter is not active") == true, "got: ${cause.message}")
        }

        val otherAdmission = DatabaseStub(
            encounters = rows(encounterRow()),
            selectOneEncounters = rows(mapOf("id" to "enc-other")),
        )
        val cause3 = causeOf(HealthcareService(otherAdmission.pool).deathEncounter("enc-1", deathBody()))
        assertInstanceOf(ConflictException::class.java, cause3)
        // 029 §4.2：互斥冲突统一为冻结文案（不再区分 "another"，以便前端 admissionMessages 映射）
        assertTrue(cause3.message?.contains("active elderly admission") == true, "got: ${cause3.message}")
        assertTrue(otherAdmission.queries.none { it.startsWith("update") }, "互斥失败时不得发出任何 update")
    }

    @Test
    fun `去世成功按序收束并写去世字段与患者状态`() {
        val deathDate = OffsetDateTime.parse("2026-08-02T14:00:00+08:00")
        val enc = encounterRow()
        val stub = DatabaseStub(
            encounters = rows(enc),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
            tasks = rows(taskRow()),
            onEncounterUpdate = {
                enc["status"] = "DECEASED"
                enc["death_date"] = deathDate
                enc["death_cause"] = "心力衰竭"
                enc["discharge_date"] = null
            },
        )
        val result = HealthcareService(stub.pool)
            .deathEncounter(
                "enc-1",
                JsonObject().put("death_date", "2026-08-02T14:00:00+08:00").put("death_cause", "  心力衰竭  "),
            )
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("DECEASED", result.getString("status"))
        assertEquals(deathDate.toString(), result.getString("death_date"))
        assertEquals("心力衰竭", result.getString("death_cause"))
        assertTrue(result.containsKey("discharge_date"))
        assertNull(result.getString("discharge_date"), "去世收束不得设置 discharge_date")

        val orderUpdate = stub.queries.first { it.contains("update healthcare.medical_orders") }
        val taskUpdate = stub.queries.first { it.contains("update nursing.nursing_tasks") }
        val periodUpdate = stub.queries.first { it.contains("update nursing.nursing_service_periods") }
        // 结算收束先写 encounters.settled_at（冻结标记），终态更新须按 death_date 定位
        val encounterUpdate = stub.queries.first { it.contains("update healthcare.encounters") && it.contains("death_date") }
        val patientUpdate = stub.queries.first { it.contains("update healthcare.patients") }
        assertTrue(
            stub.queries.indexOf(orderUpdate) < stub.queries.indexOf(taskUpdate) &&
                stub.queries.indexOf(taskUpdate) < stub.queries.indexOf(periodUpdate) &&
                stub.queries.indexOf(periodUpdate) < stub.queries.indexOf(encounterUpdate) &&
                stub.queries.indexOf(encounterUpdate) < stub.queries.indexOf(patientUpdate),
            "去世收束顺序必须为医嘱→任务→周期→encounter→患者",
        )
        assertTrue(encounterUpdate.contains("death_date"), "encounter 更新必须写 death_date: $encounterUpdate")
        assertTrue(encounterUpdate.contains("death_cause"), "提供死亡原因时必须写 death_cause: $encounterUpdate")
        assertFalse(encounterUpdate.contains("discharge_date"), "encounter 更新不得含 discharge_date: $encounterUpdate")
        val encounterTuple = stub.tuples.first { it.first.contains("update healthcare.encounters") && it.first.contains("death_date") }.second
        assertTrue(encounterTuple.contains(deathDate))
        assertTrue(encounterTuple.contains("心力衰竭"))
        assertTrue(
            stub.tuples.first { it.first.contains("update healthcare.patients") }.second.contains("DECEASED"),
            "患者状态必须更新为 DECEASED",
        )
    }

    @Test
    fun `去世未提供死亡原因时不写death_cause`() {
        val deathDate = OffsetDateTime.parse("2026-08-02T14:00:00+08:00")
        val enc = encounterRow()
        val stub = DatabaseStub(
            encounters = rows(enc),
            periods = rows(periodRow()),
            orders = rowSet(),
            tasks = rowSet(),
            onEncounterUpdate = {
                enc["status"] = "DECEASED"
                enc["death_date"] = deathDate
                enc["discharge_date"] = null
            },
        )
        HealthcareService(stub.pool)
            .deathEncounter(
                "enc-1",
                JsonObject().put("death_date", "2026-08-02T14:00:00+08:00").put("death_cause", "   "),
            )
            .toCompletionStage().toCompletableFuture().get()

        val encounterUpdate = stub.queries.first { it.contains("update healthcare.encounters") && it.contains("death_date") }
        assertFalse(encounterUpdate.contains("death_cause"), "空白死亡原因不得绑定 death_cause: $encounterUpdate")
        assertTrue(stub.queries.none { it.contains("insert into nursing.nursing_task_executions") })
        assertTrue(stub.queries.none { it.contains("stock_operation") })
        assertTrue(stub.queries.none { it.contains("medical_records") })
    }

    // ——— 7. 嵌入式 HTTP 路由 ———

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
        drugCatalog: DrugCatalogPort? = drugCatalogPort(),
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        // 模拟认证中间件：注入 userId 后测试核对路由的认证与授权路径
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(
            HealthcareRoutes.create(vertx, stub.pool, drugCatalogPort = drugCatalog),
        )
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose {
                server.close().map { Unit }
            }
        }
    }

    private fun deathBody(): JsonObject =
        JsonObject().put("death_date", "2026-08-02T14:00:00+08:00").put("death_cause", "心力衰竭")

    @Test
    fun `POST创建201且GET列表与详情200`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
            countRows = rows(mapOf("total" to 1L)),
            executions = rowSet(),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/orders",
                validOrderBody(),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "创建医嘱必须 201")
                    assertEquals("ord-1", body.getString("id"))
                    // 027 创建响应同样由 orderJson 输出到期派生字段（无 end_time 不到期）
                    assertFalse(body.getBoolean("is_expired"))
                    assertTrue(body.containsKey("expired_minutes"))
                    assertNull(body.getInteger("expired_minutes"))
                }
                httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/orders")
                    .compose { (listStatus, listBody) ->
                        ctx.verify {
                            assertEquals(200, listStatus)
                            assertEquals(1, listBody.getJsonArray("records").size())
                            assertEquals(1L, listBody.getJsonObject("meta").getLong("total"))
                        }
                        httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/orders/ord-1")
                            .map { (detailStatus, detailBody) ->
                                ctx.verify {
                                    assertEquals(200, detailStatus)
                                    assertEquals("tsk-1", detailBody.getString("task_id"))
                                    val summary = detailBody.getJsonObject("execution_summary")
                                    assertEquals(0L, summary.getLong("PENDING"))
                                    assertEquals(0L, summary.getLong("COMPLETED"))
                                }
                            }
                    }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `PATCH医嘱状态200`(vertx: Vertx, ctx: VertxTestContext) {
        val order = orderRow()
        val stub = DatabaseStub(
            orders = rows(order),
            tasks = rows(taskRow()),
            onOrderUpdate = {
                order["status"] = "DISCONTINUED"
                order["end_time"] = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
            },
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.PATCH,
                "/healthcare/v1/orders/ord-1/status",
                JsonObject().put("status", "DISCONTINUED"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals("DISCONTINUED", body.getString("status"))
                    // 027 终态医嘱永不到期，即使 end_time 已过
                    assertFalse(body.getBoolean("is_expired"))
                    assertNull(body.getInteger("expired_minutes"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `PATCH去世200`(vertx: Vertx, ctx: VertxTestContext) {
        val deathDate = OffsetDateTime.parse("2026-08-02T14:00:00+08:00")
        val enc = encounterRow()
        val stub = DatabaseStub(
            encounters = rows(enc),
            periods = rows(periodRow()),
            orders = rowSet(),
            tasks = rowSet(),
            onEncounterUpdate = {
                enc["status"] = "DECEASED"
                enc["death_date"] = deathDate
                enc["death_cause"] = "心力衰竭"
                enc["discharge_date"] = null
            },
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.PATCH,
                "/healthcare/v1/encounters/enc-1/death",
                deathBody(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals("DECEASED", body.getString("status"))
                    assertEquals("心力衰竭", body.getString("death_cause"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `错误路径返回404或400且错误为error对象`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(encounters = rowSet(), periods = rowSet())
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/missing/orders",
                validOrderBody(),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(404, status, "encounter 不存在必须 404")
                    assertNotNull(body.getString("error"), "错误响应必须为 { error: ... }")
                }
                httpRequest(
                    vertx, port, HttpMethod.PATCH,
                    "/healthcare/v1/encounters/missing/death",
                    JsonObject(),
                ).map { (deathStatus, deathBody) ->
                    ctx.verify {
                        assertEquals(400, deathStatus, "death_date 缺失必须 400")
                        assertNotNull(deathBody.getString("error"))
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 7b. 025 药品目录绑定路由错误码 ———

    @Test
    fun `POST用药医嘱目录校验错误码400_404_409_503`(vertx: Vertx, ctx: VertxTestContext) {
        val validStub = DatabaseStub(
            encounters = rows(encounterRow()),
            periods = rows(periodRow()),
            orders = rows(orderRow()),
            countRows = rows(mapOf("total" to 1L)),
            executions = rowSet(),
        )
        val path = "/healthcare/v1/encounters/enc-1/orders"
        withServer(vertx, validStub) { port ->
            // 缺 material_id：纯输入校验，400
            httpRequest(
                vertx, port, HttpMethod.POST, path,
                validOrderBody(mapOf("order_details" to JsonObject().put("drug_name", "阿司匹林"))),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status)
                    assertTrue(
                        body.getString("error")?.contains("material_id is required for MEDICATION order") == true,
                        "got: ${body.encode()}",
                    )
                }
                // 目录中不存在：404
                httpRequest(
                    vertx, port, HttpMethod.POST, path,
                    validOrderBody(mapOf("order_details" to JsonObject().put("material_id", "mat-missing"))),
                )
            }.compose { (status, body) ->
                ctx.verify {
                    assertEquals(404, status)
                    assertTrue(body.getString("error")?.contains("material not found") == true, "got: ${body.encode()}")
                }
                // 合法目录药品：201
                httpRequest(vertx, port, HttpMethod.POST, path, validOrderBody())
                    .map { (okStatus, okBody) ->
                        ctx.verify {
                            assertEquals(201, okStatus, "合法目录药品必须 201: ${okBody.encode()}")
                        }
                    }
            }
        }.compose {
            // 端口未接线：503（fail-closed，不得退化为 200）
            withServer(vertx, validStub, drugCatalog = null) { port ->
                httpRequest(vertx, port, HttpMethod.POST, path, validOrderBody())
                    .map { (status, body) ->
                        ctx.verify {
                            assertEquals(503, status, "缺端口必须 503: ${body.encode()}")
                        }
                    }
            }
        }.compose {
            // 非药品 / 未启用：409
            val nonDrugStub = DatabaseStub(
                encounters = rows(encounterRow()),
                periods = rows(periodRow()),
                orders = rows(orderRow()),
            )
            withServer(vertx, nonDrugStub, drugCatalog = drugCatalogPort(category = "耗材")) { port ->
                httpRequest(vertx, port, HttpMethod.POST, path, validOrderBody())
                    .map { (status, body) ->
                        ctx.verify {
                            assertEquals(409, status, "非药品须 409: ${body.encode()}")
                        }
                    }
            }
        }.compose {
            val inactiveStub = DatabaseStub(
                encounters = rows(encounterRow()),
                periods = rows(periodRow()),
                orders = rows(orderRow()),
            )
            withServer(vertx, inactiveStub, drugCatalog = drugCatalogPort(status = "INACTIVE")) { port ->
                httpRequest(vertx, port, HttpMethod.POST, path, validOrderBody())
                    .map { (status, body) ->
                        ctx.verify {
                            assertEquals(409, status, "未启用药品须 409: ${body.encode()}")
                        }
                    }
            }
        }.compose {
            val mismatchStub = DatabaseStub(
                encounters = rows(encounterRow()),
                periods = rows(periodRow()),
                orders = rows(orderRow()),
            )
            withServer(vertx, mismatchStub, drugCatalog = drugCatalogPort(name = "阿司匹林肠溶片")) { port ->
                httpRequest(vertx, port, HttpMethod.POST, path, validOrderBody())
                    .map { (status, body) ->
                        ctx.verify {
                            assertEquals(400, status, "drug_name 与目录名不一致须 400: ${body.encode()}")
                            assertTrue(
                                body.getString("error")?.contains("drug_name must match catalog material name") == true,
                                "got: ${body.encode()}",
                            )
                        }
                    }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `静态与具体路由不被泛型encounter路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {        val stub = DatabaseStub(
            encounters = rows(encounterRow()),
            orders = rows(orderRow()),
            executions = rowSet(),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1")
                .compose { (encounterStatus, encounterBody) ->
                    ctx.verify {
                        assertEquals(200, encounterStatus, "泛型 encounter GET 必须仍可用")
                        assertEquals("enc-1", encounterBody.getString("id"))
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/orders/ord-1")
                        .map { (orderStatus, orderBody) ->
                            ctx.verify {
                                assertEquals(200, orderStatus, "具体医嘱路由必须命中而非被泛型吞掉")
                                assertEquals("ord-1", orderBody.getString("id"))
                                assertNotNull(orderBody.getJsonObject("execution_summary"))
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 5. 护士核对用药医嘱（010 计划） ———

    @Test
    fun `核对拒绝非空请求体且不触发SQL`() {
        val stub = DatabaseStub()
        val cause = causeOf(
            HealthcareService(stub.pool).nurseCheckOrder("ord-1", "nurse-1", JsonObject().put("hacked", true)),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("must not contain any fields") == true, "got: ${cause.message}")
        assertTrue(stub.queries.isEmpty(), "严格请求体校验必须发生在任何 SQL 之前: ${stub.queries}")
    }

    @Test
    fun `核对资格校验顺序与错误映射`() {
        val nonMedication = DatabaseStub(
            orders = rows(orderRow(mapOf("order_type" to "LAB"))),
            encounters = rows(encounterRow()),
        )
        val cause1 = causeOf(
            HealthcareService(nonMedication.pool).nurseCheckOrder("ord-1", "nurse-1", JsonObject()),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("only MEDICATION") == true)

        val notActiveOrder = DatabaseStub(
            orders = rows(orderRow(mapOf("status" to "COMPLETED"))),
            encounters = rows(encounterRow()),
        )
        val cause2 = causeOf(
            HealthcareService(notActiveOrder.pool).nurseCheckOrder("ord-1", "nurse-1", JsonObject()),
        )
        assertInstanceOf(ConflictException::class.java, cause2)
        assertTrue(cause2.message?.contains("order is not active") == true)

        val notElderly = DatabaseStub(
            orders = rows(orderRow()),
            encounters = rows(encounterRow(mapOf("encounter_type" to "OUTPATIENT"))),
        )
        val cause3 = causeOf(
            HealthcareService(notElderly.pool).nurseCheckOrder("ord-1", "nurse-1", JsonObject()),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause3)
        assertTrue(cause3.message?.contains("not an elderly admission") == true)

        val notActiveEncounter = DatabaseStub(
            orders = rows(orderRow()),
            encounters = rows(encounterRow(mapOf("status" to "DISCHARGED"))),
        )
        val cause4 = causeOf(
            HealthcareService(notActiveEncounter.pool).nurseCheckOrder("ord-1", "nurse-1", JsonObject()),
        )
        assertInstanceOf(ConflictException::class.java, cause4)
        assertTrue(cause4.message?.contains("encounter is not active") == true)
    }

    @Test
    fun `已核对医嘱再次核对返回409且不触发更新`() {
        val stub = DatabaseStub(
            orders = rows(
                orderRow(
                    mapOf(
                        "nurse_checked_by" to "nurse-1",
                        "nurse_checked_at" to OffsetDateTime.parse("2026-08-02T10:00:00+08:00"),
                    ),
                ),
            ),
            encounters = rows(encounterRow()),
        )
        val cause = causeOf(
            HealthcareService(stub.pool).nurseCheckOrder("ord-1", "nurse-2", JsonObject()),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already nurse-checked") == true)
        assertTrue(
            stub.queries.none { it.contains("update healthcare.medical_orders") },
            "幂等冲突不得触发医嘱更新: ${stub.queries}",
        )
    }

    @Test
    fun `核对成功写入认证userId并返回核对字段且不影响临床状态`() {
        val order = orderRow()
        val stub = DatabaseStub(
            orders = rows(order),
            encounters = rows(encounterRow()),
            onOrderUpdate = {
                order["nurse_checked_by"] = "nurse-zhangsan"
                order["nurse_checked_at"] = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
            },
        )
        val updated = HealthcareService(stub.pool)
            .nurseCheckOrder("ord-1", "nurse-zhangsan", JsonObject())
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("nurse-zhangsan", updated.getString("nurse_checked_by"))
        assertNotNull(updated.getString("nurse_checked_at"))

        // 核对人必须来自认证 userId，绝不来自客户端
        val update = stub.queries.first { it.contains("update healthcare.medical_orders") }
        assertTrue(update.contains("set nurse_checked_by"), "必须写核对人: $update")
        assertTrue(update.contains("nurse_checked_at"), "必须写核对时间: $update")
        assertTrue(
            stub.tuples.first { it.first.contains("update healthcare.medical_orders") }.second.contains("nurse-zhangsan"),
            "核对人必须绑定认证 userId",
        )
        // 不影响临床状态：不改医嘱 status/end_time，不触碰护理任务，不删除数据
        assertFalse(update.contains("set status"), "核对不得改写医嘱状态: $update")
        assertTrue(stub.queries.none { it.contains("update nursing") }, "核对不得触碰护理任务: ${stub.queries}")
        assertTrue(stub.queries.none { it.contains("delete") }, "核对不得删除任何数据: ${stub.queries}")
    }

    @Test
    fun `PATCH核对未认证返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            orders = rows(orderRow()),
            encounters = rows(encounterRow()),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.PATCH,
                "/healthcare/v1/orders/ord-1/nurse-check",
                JsonObject(),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "无认证 userId 必须 401")
                    assertNotNull(body.getString("error"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `PATCH核对200且认证userId写入核对人并拒绝非空请求体`(vertx: Vertx, ctx: VertxTestContext) {
        val order = orderRow()
        val stub = DatabaseStub(
            orders = rows(order),
            encounters = rows(encounterRow()),
            onOrderUpdate = {
                order["nurse_checked_by"] = "nurse-route-1"
                order["nurse_checked_at"] = OffsetDateTime.parse("2026-08-02T10:00:00+08:00")
            },
        )
        withServer(vertx, stub, userId = "nurse-route-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.PATCH,
                "/healthcare/v1/orders/ord-1/nurse-check",
                JsonObject(),
            ).compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "核对成功必须 200")
                    assertEquals("nurse-route-1", body.getString("nurse_checked_by"))
                    assertNotNull(body.getString("nurse_checked_at"))
                }
                httpRequest(
                    vertx, port, HttpMethod.PATCH,
                    "/healthcare/v1/orders/ord-1/nurse-check",
                    JsonObject().put("hacked", true),
                ).map { (strictStatus, strictBody) ->
                    ctx.verify {
                        assertEquals(400, strictStatus, "非空请求体必须 400")
                        assertNotNull(strictBody.getString("error"))
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 6. 护士核对汇总列表（跨入住待核对医嘱） ———

    @Test
    fun `待核对汇总列表只含未核对且输出患者信息`() {
        val stub = DatabaseStub(
            orders = rows(
                orderRow(
                    mapOf(
                        "patient_id" to "pat-1",
                        "patient_name" to "张奶奶",
                        "encounter_no" to "A20260801001",
                    ),
                ),
            ),
            encounters = rows(encounterRow()),
            patients = rows(mapOf("id" to "pat-1", "name" to "张奶奶", "status" to "ACTIVE")),
            countRows = rows(mapOf("total" to 1L)),
        )
        val result = HealthcareService(stub.pool)
            .listPendingNurseCheckOrders(stub.pool, null, null, 50, 0)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(1L, result.getJsonObject("meta").getLong("total"))
        val record = result.getJsonArray("records").getJsonObject(0)
        assertEquals("ord-1", record.getString("id"))
        assertEquals("pat-1", record.getString("patient_id"))
        assertEquals("张奶奶", record.getString("patient_name"))
        assertEquals("A20260801001", record.getString("encounter_no"))
        assertNull(record.getString("nurse_checked_by"), "待核对医嘱核对人必须为 null")
        assertNull(record.getString("nurse_checked_at"), "待核对医嘱核对时间必须为 null")

        // 汇总过滤语义：MEDICATION + ACTIVE + 未核对 + 活动养老入住，join 患者取姓名
        val sqls = stub.queries.joinToString("\n")
        assertTrue(sqls.contains("nurse_checked_at is null"), "必须只列未核对医嘱: $sqls")
        assertTrue(sqls.contains("order_type = $"), "必须限定用药医嘱: $sqls")
        assertTrue(sqls.contains("join healthcare.patients"), "必须 join 患者表取姓名: $sqls")
        assertTrue(sqls.contains("encounter_type = $"), "必须限定养老入住: $sqls")
        assertTrue(sqls.contains("count(*)"), "必须带分页计数: $sqls")
    }

    @Test
    fun `待核对汇总列表每行带到期派生字段且同一响应共用一个now`() {
        val sharedEnd = OffsetDateTime.now().minusMinutes(100)
        val patient = mapOf("patient_id" to "pat-1", "patient_name" to "张奶奶", "encounter_no" to "A20260801001")
        val stub = DatabaseStub(
            orders = rows(
                orderRow(patient + mapOf("id" to "ord-expired-a", "end_time" to sharedEnd)),
                // 与上一行 end_time 完全相同：同一响应共用一个 now 时必须得到完全相同的分钟数
                orderRow(patient + mapOf("id" to "ord-expired-b", "end_time" to sharedEnd)),
                orderRow(patient + mapOf("id" to "ord-null-end", "end_time" to null)),
                orderRow(patient + mapOf("id" to "ord-future", "end_time" to OffsetDateTime.now().plusDays(1))),
                orderRow(patient + mapOf("id" to "ord-done", "status" to "COMPLETED", "end_time" to sharedEnd)),
            ),
            encounters = rows(encounterRow()),
            patients = rows(mapOf("id" to "pat-1", "name" to "张奶奶", "status" to "ACTIVE")),
            countRows = rows(mapOf("total" to 5L)),
        )
        val before = OffsetDateTime.now()
        val result = HealthcareService(stub.pool)
            .listPendingNurseCheckOrders(stub.pool, null, null, 50, 0)
            .toCompletionStage().toCompletableFuture().get()
        val after = OffsetDateTime.now()

        val records = result.getJsonArray("records").map { it as JsonObject }
            .associateBy { it.getString("id") }
        assertEquals(5, records.size)

        // 到期行：与 orderJson 同口径（ACTIVE + end_time < now），分钟数落在 [请求前, 响应后] 区间内
        val lowerBound = Duration.between(sharedEnd, before).toMinutes()
        val upperBound = Duration.between(sharedEnd, after).toMinutes()
        for (id in listOf("ord-expired-a", "ord-expired-b")) {
            val expired = records.getValue(id)
            assertTrue(expired.getBoolean("is_expired"), "$id 必须判为到期")
            assertTrue(expired.containsKey("expired_minutes"), "$id 必须含 expired_minutes")
            val minutes = expired.getLong("expired_minutes")
            assertTrue(
                minutes in lowerBound..upperBound,
                "$id expired_minutes=$minutes 必须落在 [$lowerBound, $upperBound]",
            )
        }
        // 同一响应内固定一个 now：相同 end_time 的多行必须得到完全相同的分钟数
        assertEquals(
            records.getValue("ord-expired-a").getLong("expired_minutes"),
            records.getValue("ord-expired-b").getLong("expired_minutes"),
            "同一响应内所有行必须共用同一个 now",
        )

        // 非到期行：end_time 为空 / 未来 / 终态，一律 false + null
        for (id in listOf("ord-null-end", "ord-future", "ord-done")) {
            val record = records.getValue(id)
            assertTrue(record.containsKey("is_expired"), "$id 必须返回 is_expired")
            assertFalse(record.getBoolean("is_expired"), "$id 不得判为到期")
            assertTrue(record.containsKey("expired_minutes"), "$id 必须返回 expired_minutes")
            assertNull(record.getInteger("expired_minutes"), "$id 未到期必须返回 null")
        }

        // 既有响应形状不变，且只读：计数 + 列表两条查询，无任何写语句
        val first = records.getValue("ord-expired-a")
        assertEquals("张奶奶", first.getString("patient_name"))
        assertEquals("A20260801001", first.getString("encounter_no"))
        assertEquals(5L, result.getJsonObject("meta").getLong("total"))
        assertEquals(2, stub.queries.size, "只应有计数与列表两条查询: ${stub.queries}")
        assertTrue(
            stub.queries.none { it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete") },
            "待核对汇总列表必须只读: ${stub.queries}",
        )
    }

    @Test
    fun `待核对汇总静态路由不被泛型路由吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            orders = rows(orderRow(mapOf("patient_id" to "pat-1", "patient_name" to "张奶奶", "encounter_no" to "A20260801001"))),
            encounters = rows(encounterRow()),
            patients = rows(mapOf("id" to "pat-1", "name" to "张奶奶", "status" to "ACTIVE")),
            countRows = rows(mapOf("total" to 1L)),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.GET,
                "/healthcare/v1/orders/pending-nurse-check",
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "待核对汇总路由必须命中而非被泛型 /orders/:id 吞掉")
                    assertEquals(1, body.getJsonArray("records").size())
                    val record = body.getJsonArray("records").getJsonObject(0)
                    assertEquals("张奶奶", record.getString("patient_name"))
                    assertEquals(1L, body.getJsonObject("meta").getLong("total"))
                    // 027（P2-2）：路由响应每行必须携带到期派生字段（fixture end_time = null → 恒假）
                    assertTrue(record.containsKey("is_expired"), "每行必须含 is_expired: $record")
                    assertFalse(record.getBoolean("is_expired"))
                    assertTrue(record.containsKey("expired_minutes"), "每行必须含 expired_minutes: $record")
                    assertNull(record.getInteger("expired_minutes"))
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ——— 8. 027 医嘱到期派生与显式收束 ———

    @Test
    fun `computeExpiryFields边界_严格小于与终态`() {
        val now = OffsetDateTime.parse("2026-09-29T12:00:00+08:00")

        // ACTIVE + end_time 已过 → 到期，分钟数为 end_time → now 的向下取整
        assertEquals(true to 72000, MedicalOrderService.computeExpiryFields("ACTIVE", now.minusDays(50), now))
        // 不足一分钟 → 0 分钟，但依然到期
        assertEquals(true to 0, MedicalOrderService.computeExpiryFields("ACTIVE", now.minusSeconds(30), now))
        // end_time == now 不算到期（严格小于）
        assertEquals(false to null, MedicalOrderService.computeExpiryFields("ACTIVE", now, now))
        // end_time 在未来不到期
        assertEquals(false to null, MedicalOrderService.computeExpiryFields("ACTIVE", now.plusMinutes(1), now))
        // end_time = null（长期无明确终点）永不到期
        assertEquals(false to null, MedicalOrderService.computeExpiryFields("ACTIVE", null, now))
        // 终态永不到期
        for (status in listOf("COMPLETED", "DISCONTINUED", "CANCELLED")) {
            assertEquals(
                false to null,
                MedicalOrderService.computeExpiryFields(status, now.minusDays(50), now),
                status,
            )
        }
        // status 缺失也不到期
        assertEquals(false to null, MedicalOrderService.computeExpiryFields(null, now.minusDays(50), now))
    }

    @Test
    fun `orderJson到期派生字段出现在列表与详情响应`(vertx: Vertx, ctx: VertxTestContext) {
        val expiredEnd = OffsetDateTime.now().minusDays(50)
        val stub = DatabaseStub(
            orders = rows(
                orderRow(mapOf("id" to "ord-expired", "end_time" to expiredEnd)),
                orderRow(mapOf("id" to "ord-null-end", "end_time" to null)),
                orderRow(mapOf("id" to "ord-future", "end_time" to OffsetDateTime.now().plusDays(1))),
                orderRow(mapOf("id" to "ord-done", "status" to "COMPLETED", "end_time" to expiredEnd)),
            ),
            countRows = rows(mapOf("total" to 4L)),
        )
        withServer(vertx, stub) { port ->
            // 服务端 now 必落在 [请求前, 响应后] 区间内，据此给出 expired_minutes 的精确边界
            val lowerBound = Duration.between(expiredEnd, OffsetDateTime.now()).toMinutes()
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/encounters/enc-1/orders")
                .compose { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status)
                        val records = body.getJsonArray("records").map { it as JsonObject }
                        assertEquals(4, records.size)
                        val byId = records.associateBy { it.getString("id") }

                        val expired = byId.getValue("ord-expired")
                        assertTrue(expired.getBoolean("is_expired"), "ACTIVE + end_time 已过必须到期")
                        assertTrue(expired.containsKey("expired_minutes"))
                        val upperBound = Duration.between(expiredEnd, OffsetDateTime.now()).toMinutes()
                        val minutes = expired.getLong("expired_minutes")
                        assertTrue(
                            minutes in lowerBound..upperBound,
                            "expired_minutes=$minutes 必须落在 [$lowerBound, $upperBound]",
                        )

                        for (id in listOf("ord-null-end", "ord-future", "ord-done")) {
                            val record = byId.getValue(id)
                            assertTrue(record.containsKey("is_expired"), "$id 必须返回 is_expired")
                            assertFalse(record.getBoolean("is_expired"), "$id 不得判为到期")
                            assertTrue(record.containsKey("expired_minutes"))
                            assertNull(record.getInteger("expired_minutes"), "$id 未到期必须返回 null")
                        }
                    }
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/orders/ord-expired")
                        .map { (detailStatus, detailBody) ->
                            ctx.verify {
                                assertEquals(200, detailStatus)
                                assertTrue(detailBody.getBoolean("is_expired"), "详情必须携带到期派生字段")
                                assertNotNull(detailBody.getInteger("expired_minutes"))
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `closeExpiredOrders收束到期医嘱且保留end_time并写审计`(vertx: Vertx, ctx: VertxTestContext) {
        val endTime = OffsetDateTime.now().minusDays(50)
        val order = orderRow(mapOf("id" to "ord-1", "end_time" to endTime))
        val stub = DatabaseStub(
            orders = rows(order),
            tasks = rows(taskRow()),
            onOrderUpdate = { order["status"] = "COMPLETED" },
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/orders/close-expired", JsonObject())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status)
                        assertEquals(1, body.getInteger("closed"))
                        assertEquals(listOf("ord-1"), body.getJsonArray("order_ids").list)
                        assertEquals(0, body.getJsonArray("warnings").size())
                        val closedAt = OffsetDateTime.parse(body.getString("closed_at"))
                        assertTrue(closedAt.isAfter(OffsetDateTime.now().minusMinutes(5)))

                        // 候选：ACTIVE + end_time 非空 + end_time < now，按 id 升序加锁
                        val candidateSql = stub.queries.first {
                            it.contains("from healthcare.medical_orders") && it.contains("for update")
                        }
                        assertTrue(candidateSql.contains("medical_orders.status"), "必须限定 ACTIVE: $candidateSql")
                        assertTrue(candidateSql.contains("medical_orders.end_time is not null"), "必须排除无终点: $candidateSql")
                        assertTrue(candidateSql.contains("medical_orders.end_time <"), "必须限定已到期: $candidateSql")
                        assertTrue(candidateSql.contains("order by"), "必须固定加锁顺序: $candidateSql")
                        assertFalse(
                            candidateSql.contains("medical_orders.encounter_id"),
                            "未限定入住时不得附带 encounter_id 条件: $candidateSql",
                        )
                        // R4-1：不带 encounter_id 时不校验入住，更不得锁 encounters 行
                        assertTrue(
                            stub.queries.none { it.contains("healthcare.encounters") },
                            "未限定入住时不得查询入住表: ${stub.queries}",
                        )
                        assertTrue(
                            stub.queries.none { it.contains("healthcare.encounters") && it.contains("for update") },
                            "收束流程不得锁 encounters 行（ABBA 环）: ${stub.queries}",
                        )

                        // 关联护理任务被结束为 COMPLETED
                        val taskUpdateSql = stub.queries.first { it.contains("update nursing.nursing_tasks") }
                        assertTrue(taskUpdateSql.contains("status"))
                        assertTrue(
                            stub.tuples.first { it.first == taskUpdateSql }.second.contains("COMPLETED"),
                            "护理任务必须收束为 COMPLETED",
                        )

                        // 绝不写 nursing_task_executions（无副作用）
                        val executionWrites = stub.queries.filter {
                            it.contains("nursing_task_executions") &&
                                (it.startsWith("insert") || it.startsWith("update") || it.startsWith("delete"))
                        }
                        assertTrue(executionWrites.isEmpty(), "收束不得写执行记录: $executionWrites")
                        assertTrue(
                            stub.queries.none { it.contains("nursing_task_executions") },
                            "收束流程不应触及执行记录表: ${stub.queries.filter { it.contains("nursing_task_executions") }}",
                        )

                        // 医嘱更新：status=COMPLETED、updated_at=now、metadata JSONB 合并，end_time 不被覆盖
                        val orderUpdateSql = stub.queries.first { it.contains("update healthcare.medical_orders") }
                        assertTrue(orderUpdateSql.contains("metadata"), "必须合并 metadata: $orderUpdateSql")
                        assertTrue(
                            orderUpdateSql.contains("coalesce(") &&
                                orderUpdateSql.contains("'{}'::jsonb) || cast("),
                            "必须按 COALESCE(metadata,'{}'::jsonb) || patch 合并: $orderUpdateSql",
                        )
                        assertFalse(orderUpdateSql.contains("end_time"), "end_time 必须保持原值: $orderUpdateSql")

                        val updateValues = stub.tuples.last { it.first == orderUpdateSql }.second
                        assertTrue(updateValues.contains("COMPLETED"), "医嘱状态必须写 COMPLETED")
                        val patch = updateValues.filterIsInstance<JsonObject>().single()
                        val convergence = patch.getJsonObject("convergence")
                        assertEquals("EXPIRED", convergence.getString("reason"))
                        assertEquals(endTime.toString(), convergence.getString("end_time"))
                        assertEquals(closedAt.toString(), convergence.getString("at"))
                    }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `closeExpiredOrders幂等_第二次closed为0且不重复写库`(vertx: Vertx, ctx: VertxTestContext) {
        val order = orderRow(mapOf("id" to "ord-1", "end_time" to OffsetDateTime.now().minusDays(50)))
        val stub = DatabaseStub(
            orders = rows(order),
            tasks = rows(taskRow()),
            onOrderUpdate = { order["status"] = "COMPLETED" },
        )
        withServer(vertx, stub) { port ->
            // 第一次：请求体完全为空（契约允许空体）
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/orders/close-expired")
                .compose { (firstStatus, firstBody) ->
                    ctx.verify {
                        assertEquals(200, firstStatus)
                        assertEquals(1, firstBody.getInteger("closed"))
                    }
                    // 收束后该医嘱已非 ACTIVE，不再是候选
                    stub.orders = rowSet()
                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/orders/close-expired", JsonObject())
                        .map { (secondStatus, secondBody) ->
                            ctx.verify {
                                assertEquals(200, secondStatus)
                                assertEquals(0, secondBody.getInteger("closed"))
                                assertEquals(0, secondBody.getJsonArray("order_ids").size())
                                assertEquals(0, secondBody.getJsonArray("warnings").size())
                                assertEquals(
                                    1,
                                    stub.queries.count { it.contains("update healthcare.medical_orders") },
                                    "幂等：只应写入一次医嘱收束",
                                )
                            }
                        }
                }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `closeExpiredOrders按encounter_id限定候选`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            encounters = rows(encounterRow(mapOf("id" to "enc-2"))),
            orders = rows(orderRow(mapOf("id" to "ord-2", "encounter_id" to "enc-2", "end_time" to OffsetDateTime.now().minusDays(10)))),
            tasks = rows(taskRow(mapOf("id" to "tsk-2", "order_item_id" to "ord-2"))),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/orders/close-expired",
                JsonObject().put("encounter_id", "enc-2"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status)
                    assertEquals(1, body.getInteger("closed"))
                    assertEquals("ord-2", body.getJsonArray("order_ids").getString(0))

                    // R4-1：入住只做非加锁存在性校验，且必须先于候选查询
                    val existenceIndex = stub.queries.indexOfFirst { it.contains("from healthcare.encounters") }
                    assertTrue(existenceIndex >= 0, "必须校验入住存在: ${stub.queries}")
                    val existenceSql = stub.queries[existenceIndex]
                    assertTrue(existenceSql.startsWith("select"), "入住校验必须是普通 select: $existenceSql")
                    assertFalse(
                        existenceSql.contains("for update"),
                        "入住存在性校验不得加行锁（否则与 nurseCheckOrder 构成 ABBA）: $existenceSql",
                    )
                    val candidateSql = stub.queries.first {
                        it.contains("from healthcare.medical_orders") && it.contains("for update")
                    }
                    assertTrue(
                        existenceIndex < stub.queries.indexOf(candidateSql),
                        "存在性校验必须先于候选查询: ${stub.queries}",
                    )
                    assertTrue(
                        candidateSql.contains("medical_orders.encounter_id"),
                        "必须按入住限定候选: $candidateSql",
                    )
                    assertTrue(
                        stub.tuples.first { it.first == candidateSql }.second.contains("enc-2"),
                        "候选查询必须绑定 encounter_id",
                    )
                    assertFalse(
                        candidateSql.contains("healthcare.encounters"),
                        "候选加锁语句只应锁 medical_orders: $candidateSql",
                    )
                    assertTrue(
                        stub.queries.none { it.contains("healthcare.encounters") && it.contains("for update") },
                        "收束流程不得锁 encounters 行（ABBA 环）: ${stub.queries}",
                    )
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `closeExpiredOrders任务异常降级warnings但仍收束`(vertx: Vertx, ctx: VertxTestContext) {
        val missingTaskOrder = orderRow(mapOf("id" to "ord-1", "end_time" to OffsetDateTime.now().minusDays(3)))
        val missingTaskStub = DatabaseStub(
            orders = rows(missingTaskOrder),
            tasks = rowSet(),
            onOrderUpdate = { missingTaskOrder["status"] = "COMPLETED" },
        )
        val terminatedTaskOrder = orderRow(mapOf("id" to "ord-1", "end_time" to OffsetDateTime.now().minusDays(3)))
        val terminatedTaskStub = DatabaseStub(
            orders = rows(terminatedTaskOrder),
            tasks = rows(taskRow(mapOf("status" to "COMPLETED"))),
            onOrderUpdate = { terminatedTaskOrder["status"] = "COMPLETED" },
        )
        withServer(vertx, missingTaskStub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/orders/close-expired", JsonObject())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(200, status, "任务缺失不得让收束失败")
                        assertEquals(1, body.getInteger("closed"))
                        val warnings = body.getJsonArray("warnings")
                        assertEquals(1, warnings.size())
                        val warning = warnings.getJsonObject(0)
                        assertEquals("ord-1", warning.getString("order_id"))
                        assertTrue(
                            warning.getString("reason").contains("no linked task"),
                            "告警必须记录任务缺失原因: ${warning.getString("reason")}",
                        )
                        assertTrue(
                            missingTaskStub.queries.any { it.contains("update healthcare.medical_orders") },
                            "有告警仍必须收束医嘱",
                        )
                    }
                }
        }.compose {
            withServer(vertx, terminatedTaskStub) { port ->
                httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/orders/close-expired", JsonObject())
                    .map { (status, body) ->
                        ctx.verify {
                            assertEquals(200, status)
                            assertEquals(1, body.getInteger("closed"))
                            val warning = body.getJsonArray("warnings").getJsonObject(0)
                            assertTrue(
                                warning.getString("reason").contains("unexpected status"),
                                "已终态任务也必须降级为告警: ${warning.getString("reason")}",
                            )
                        }
                    }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `closeExpiredOrders未知字段400与未知入住404`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(encounters = rowSet())
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/orders/close-expired",
                JsonObject().put("foo", "bar"),
            ).compose { (unknownStatus, unknownBody) ->
                ctx.verify {
                    assertEquals(400, unknownStatus)
                    assertEquals("unknown field: foo", unknownBody.getString("error"))
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/orders/close-expired",
                    JsonObject().put("encounter_id", "missing").put("closed", 1),
                ).compose { (mixedStatus, mixedBody) ->
                    ctx.verify {
                        assertEquals(400, mixedStatus, "未知字段优先于入住存在性校验")
                        assertEquals("unknown field: closed", mixedBody.getString("error"))
                        assertEquals(0, stub.transactionCalls, "400 不得开启事务")
                    }
                    httpRequest(
                        vertx, port, HttpMethod.POST,
                        "/healthcare/v1/orders/close-expired",
                        JsonObject().put("encounter_id", "missing"),
                    ).map { (missingStatus, missingBody) ->
                        ctx.verify {
                            assertEquals(404, missingStatus)
                            assertEquals("encounter not found: missing", missingBody.getString("error"))
                            assertEquals(1, stub.transactionCalls)
                            // R4-1：404 路径的入住校验也必须是普通 select，绝不加行锁
                            val existenceIndex = stub.queries.indexOfFirst { it.contains("from healthcare.encounters") }
                            assertTrue(existenceIndex >= 0, "必须查询入住存在性: ${stub.queries}")
                            assertFalse(
                                stub.queries[existenceIndex].contains("for update"),
                                "入住存在性校验不得加行锁: ${stub.queries[existenceIndex]}",
                            )
                            assertTrue(
                                stub.queries.none { it.contains("from healthcare.medical_orders") },
                                "入住不存在时不得查询候选医嘱",
                            )
                            assertTrue(
                                stub.queries.none { it.contains("update healthcare.medical_orders") },
                                "入住不存在时不得写医嘱",
                            )
                            assertTrue(
                                stub.queries.none { it.contains("healthcare.encounters") && it.contains("for update") },
                                "收束流程不得锁 encounters 行（ABBA 环）: ${stub.queries}",
                            )
                        }
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    @Test
    fun `close-expired静态路由不被泛型orders_id吞掉`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub()
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/orders/close-expired", JsonObject())
                .compose { (postStatus, postBody) ->
                    ctx.verify {
                        assertEquals(200, postStatus, "POST /orders/close-expired 必须命中静态路由")
                        assertEquals(0, postBody.getInteger("closed"), "无候选时 closed = 0")
                        assertTrue(postBody.containsKey("order_ids"))
                        assertTrue(postBody.containsKey("warnings"))
                        assertTrue(postBody.containsKey("closed_at"))
                        assertNull(postBody.getString("id"), "不得落入 /orders/:id 的医嘱详情响应")
                    }
                    // 同路径 GET 仍由泛型 /orders/:id 处理：两条路由并存，静态路径优先
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/orders/close-expired")
                        .map { (getStatus, getBody) ->
                            ctx.verify {
                                assertEquals(404, getStatus)
                                assertEquals("order not found: close-expired", getBody.getString("error"))
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
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
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
