package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 医嘱执行闭环给药记录（MAR）的非数据库测试。
 *
 * 全库 mock 桩（conn/pool.preparedQuery 按 normalized SQL 特征分发）：
 *   - 请求体解析与门禁：未知字段/结果白名单/数量与来源的必填/互斥/精度
 *   - 事务流程：执行状态联动（COMPLETED/SKIPPED）、医嘱/任务/发药门禁、
 *     数量对账（累计给药 ≤ 实发数量）、1:1 唯一冲突、失败整体回滚（不写给药与执行）
 */
class MedicationAdministrationServiceTest {

    companion object {
        fun row(values: Map<String, Any?>): Row {
            val row = mockk<Row>()
            for ((key, value) in values) {
                every { row.getValue(key) } returns value
            }
            every { row.toJson() } returns JsonObject(values)
            return row
        }

        fun rowSet(vararg rows: Row): RowSet<Row> {
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
    }

    /** 全库桩：按 SQL 特征分发行集，捕获全部查询与参数。 */
    private class DatabaseStub(
        var executions: RowSet<Row> = rowSet(),
        var tasks: RowSet<Row> = rowSet(),
        var orders: RowSet<Row> = rowSet(),
        var dispenseItems: RowSet<Row> = rowSet(),
        var adminSums: RowSet<Row> = rowSet(),
        var administrations: RowSet<Row> = rowSet(),
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var failAdminInsert = false

        val pool = mockk<Pool>()
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        private var lastSql = ""

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                val branch = when {
                    sql.contains("insert into nursing.medication_administrations") -> "insert_admin"
                    sql.contains("update nursing.nursing_task_executions") -> "update_exec"
                    sql.contains("sum(") -> "admin_sum"
                    sql.contains("medication_administrations") -> "admin_read"
                    sql.contains("pharmacy.pharmacy_dispense_items") -> "dispense"
                    sql.contains("from healthcare.medical_orders") -> "orders"
                    sql.contains("from nursing.nursing_tasks") -> "tasks"
                    sql.contains("nursing_task_executions") -> "executions"
                    else -> "else"
                }
                val result = when (branch) {
                    "insert_admin" ->
                        if (failAdminInsert) {
                            Future.failedFuture(
                                PgException(
                                    "duplicate key value violates unique constraint",
                                    "ERROR",
                                    "23505",
                                    "Key (task_execution_id)=(exec-1) already exists.",
                                ),
                            )
                        } else {
                            Future.succeededFuture(rowSet())
                        }

                    "update_exec" -> Future.succeededFuture(rowSet())
                    "admin_sum" -> Future.succeededFuture(adminSums)
                    "dispense" -> Future.succeededFuture(dispenseItems)
                    "orders" -> Future.succeededFuture(orders)
                    "tasks" -> Future.succeededFuture(tasks)
                    "admin_read" -> Future.succeededFuture(administrations)
                    "executions" -> Future.succeededFuture(executions)
                    else -> Future.succeededFuture(rowSet())
                }
                result
            }
            every { pool.withTransaction<Any>(any()) } answers {
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }

        private fun tupleValues(tuple: Tuple): List<Any?> {
            val values = mutableListOf<Any?>()
            for (index in 0 until tuple.size()) {
                values.add(tuple.getValue(index))
            }
            return values
        }

        private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")
    }

    // ——— fixture 行 ———

    private fun executionRow(overrides: Map<String, Any?> = emptyMap()): Row {
        val base = mutableMapOf<String, Any?>(
            "id" to "exec-1",
            "task_id" to "task-1",
            "status" to "IN_PROGRESS",
        )
        base.putAll(overrides)
        return row(base)
    }

    private fun taskRow(overrides: Map<String, Any?> = emptyMap()): Row {
        val base = mutableMapOf<String, Any?>(
            "id" to "task-1",
            "task_type" to "MEDICATION",
            "order_item_id" to "ord-1",
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return row(base)
    }

    private fun orderRow(overrides: Map<String, Any?> = emptyMap()): Row {
        val base = mutableMapOf<String, Any?>(
            "id" to "ord-1",
            "order_type" to "MEDICATION",
            "status" to "ACTIVE",
            "nurse_checked_by" to "nurse-1",
            "nurse_checked_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "order_details" to JsonObject().put("drug_name", "阿司匹林").put("dose", "100mg").put("unit", "片"),
        )
        base.putAll(overrides)
        return row(base)
    }

    private fun dispenseItemRow(overrides: Map<String, Any?> = emptyMap()): Row {
        val base = mutableMapOf<String, Any?>(
            "id" to "di-1",
            "order_item_id" to "ord-1",
            "lot_id" to "lot-1",
            "dispensed_quantity" to BigDecimal("10"),
            "dispense_status" to "DISPENSED",
            "warehouse" to "中心药房",
        )
        base.putAll(overrides)
        return row(base)
    }

    private fun adminRow(overrides: Map<String, Any?> = emptyMap()): Row {
        val base = mutableMapOf<String, Any?>(
            "id" to "adm-1",
            "task_execution_id" to "exec-1",
            "medical_order_id" to "ord-1",
            "result" to "已服",
            "administered_quantity" to BigDecimal("2"),
            "unit" to "片",
            "dispense_item_id" to "di-1",
            "dispense_no" to "DS-1",
            "material_id" to "mat-1",
            "material_name" to "阿司匹林",
            "lot_id" to "lot-1",
            "batch_no" to "B20260801",
            "warehouse" to "中心药房",
            "administered_by" to "user-1",
            "administered_at" to OffsetDateTime.parse("2026-08-01T11:00:00+08:00"),
            "reason" to null,
            "planned_time" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "task_description" to "阿司匹林 100mg 每日一次",
            "patient_name" to "张三",
            "created_at" to OffsetDateTime.parse("2026-08-01T11:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T11:00:00+08:00"),
        )
        base.putAll(overrides)
        return row(base)
    }

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    // ========================================================================
    //  1. 请求体解析（纯函数）
    // ========================================================================

    @Test
    fun `服务端受控字段与未知字段一律拒绝`() {
        for (field in MedicationAdministrationService.SERVER_CONTROLLED_FIELDS) {
            val error = failureOf(
                MedicationAdministrationService(mockk<Pool>())
                    .recordAdministration("exec-1", "user-1", JsonObject().put("result", "已服").put(field, "hacked")),
            )
            assertTrue(error.message!!.contains("unknown fields: $field"), "$field 必须被拒绝: ${error.message}")
        }
        val error = failureOf(
            MedicationAdministrationService(mockk<Pool>())
                .recordAdministration("exec-1", "user-1", JsonObject().put("result", "已服").put("hacked", 1)),
        )
        assertTrue(error.message!!.contains("unknown fields: hacked"))
    }

    @Test
    fun `结果必填且必须在白名单内`() {
        assertTrue(
            failureOf(MedicationAdministrationService(mockk<Pool>())
                .recordAdministration("exec-1", "user-1", JsonObject()))
                .message!!.contains("result is required"),
        )
        assertTrue(
            failureOf(MedicationAdministrationService(mockk<Pool>())
                .recordAdministration("exec-1", "user-1", JsonObject().put("result", "已吃")))
                .message!!.contains("invalid result"),
        )
    }

    @Test
    fun `已服部分服必填来源与数量且数量为十进制文本`() {
        val service = MedicationAdministrationService(mockk<Pool>())
        // 已服缺来源
        assertTrue(
            failureOf(service.recordAdministration("exec-1", "user-1", JsonObject().put("result", "已服").put("administered_quantity", "2")))
                .message!!.contains("dispense_item_id is required"),
        )
        // 已服缺数量
        assertTrue(
            failureOf(service.recordAdministration("exec-1", "user-1", JsonObject().put("result", "已服").put("dispense_item_id", "di-1")))
                .message!!.contains("administered_quantity is required"),
        )
        // 数量为 JSON number 而非十进制文本
        assertTrue(
            failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", 2),
                ),
            ).message!!.contains("administered_quantity must be decimal text"),
        )
        // 数量必须为正
        assertTrue(
            failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "0"),
                ),
            ).message!!.contains("must be positive"),
        )
        // 超过 6 位小数拒绝
        assertTrue(
            failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "1.1234567"),
                ),
            ).message!!.contains("exceeds precision"),
        )
    }

    @Test
    fun `拒服漏服暂缓不得带来源与数量且必填原因`() {
        val service = MedicationAdministrationService(mockk<Pool>())
        for (result in listOf("拒服", "漏服", "暂缓")) {
            val withSource = failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", result).put("dispense_item_id", "di-1").put("reason", "长者拒绝"),
                ),
            )
            assertTrue(withSource.message!!.contains("dispense_item_id is not allowed"), "$result 不得带来源")
            val withQuantity = failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", result).put("administered_quantity", "2").put("reason", "长者拒绝"),
                ),
            )
            assertTrue(withQuantity.message!!.contains("administered_quantity is not allowed"), "$result 不得带数量")
            val withoutReason = failureOf(
                service.recordAdministration("exec-1", "user-1", JsonObject().put("result", result)),
            )
            assertTrue(withoutReason.message!!.contains("reason is required"), "$result 必填原因")
        }
        // 部分服同样必填原因
        assertTrue(
            failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", "部分服").put("dispense_item_id", "di-1").put("administered_quantity", "1"),
                ),
            ).message!!.contains("reason is required"),
        )
        // 已服原因可选
        val input = MedicationAdministrationService.parseAdministrationBody(
            JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
        )
        assertEquals("已服", input.result)
        assertEquals("di-1", input.dispenseItemId)
        assertEquals(BigDecimal("2"), input.administeredQuantity)
        assertNull(input.reason)
    }

    @Test
    fun `给药结果到执行状态的联动映射`() {
        assertEquals("COMPLETED", MedicationAdministrationService.targetStatusFor("已服"))
        assertEquals("COMPLETED", MedicationAdministrationService.targetStatusFor("部分服"))
        assertEquals("SKIPPED", MedicationAdministrationService.targetStatusFor("拒服"))
        assertEquals("SKIPPED", MedicationAdministrationService.targetStatusFor("漏服"))
        assertEquals("SKIPPED", MedicationAdministrationService.targetStatusFor("暂缓"))
        assertNull(MedicationAdministrationService.targetStatusFor("已吃"))
    }

    // ========================================================================
    //  2. 记录给药事务流程
    // ========================================================================

    @Test
    fun `已服成功：执行转COMPLETED并写入给药人与服务端时间`() {
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            dispenseItems = rowSet(dispenseItemRow()),
            adminSums = rowSet(row(mapOf("administered_sum" to BigDecimal("0")))),
            administrations = rowSet(adminRow()),
        )
        val service = MedicationAdministrationService(stub.pool)
        val result = service.recordAdministration(
            "exec-1", "user-1",
            JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
        ).toCompletionStage().toCompletableFuture().get()

        // 响应含给药记录与来源摘要
        assertEquals("已服", result.getString("result"))
        assertEquals("user-1", result.getString("administered_by"))
        assertEquals("di-1", result.getString("dispense_item_id"))
        assertEquals("B20260801", result.getString("batch_no"))
        assertEquals("2", result.getString("administered_quantity"))

        // 给药记录插入：给药人/时间/数量/单位由服务端写入
        val insert = stub.tuples.first { it.first.contains("insert into nursing.medication_administrations") }
        val insertValues = insert.second
        assertTrue(insertValues.contains("user-1"), "插入必须含认证给药人: $insertValues")
        assertTrue(insertValues.contains(BigDecimal("2")), "插入必须含实际数量: $insertValues")
        assertTrue(insertValues.contains("已服"), "插入必须含给药结果: $insertValues")
        assertTrue(insertValues.contains("ord-1"), "插入必须含医嘱归属: $insertValues")
        assertTrue(insertValues.contains("片"), "插入必须含单位快照: $insertValues")
        assertTrue(insertValues.any { it is OffsetDateTime }, "给药时间必须是服务端时间: $insertValues")

        // 执行状态联动 COMPLETED
        val update = stub.tuples.first { it.first.contains("update nursing.nursing_task_executions") }
        assertEquals("COMPLETED", update.second.first(), "执行必须联动为 COMPLETED: ${update.second}")

        // 锁顺序：发药明细（含发药单）→ 医嘱 → 执行
        val dispenseLock = stub.queries.first { it.contains("pharmacy.pharmacy_dispense_items") }
        assertTrue(dispenseLock.contains("for update"), "发药明细必须行锁: $dispenseLock")
        val orderLock = stub.queries.first { it.contains("from healthcare.medical_orders") }
        assertTrue(orderLock.contains("for update"), "医嘱必须锁读: $orderLock")
        val executionIndex = stub.queries.indexOfFirst { it.contains("nursing_task_executions") && it.contains("for update") }
        val orderIndex = stub.queries.indexOfFirst { it.contains("from healthcare.medical_orders") }
        val dispenseIndex = stub.queries.indexOfFirst { it.contains("pharmacy.pharmacy_dispense_items") }
        assertTrue(
            dispenseIndex < orderIndex && orderIndex < executionIndex,
            "锁顺序必须为 发药明细 → 医嘱 → 执行（与药房 011 同向，避免 AB-BA 死锁）",
        )
    }

    @Test
    fun `同一执行重复给药返回409且不产生第二次插入`() {
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            dispenseItems = rowSet(dispenseItemRow()),
            adminSums = rowSet(row(mapOf("administered_sum" to BigDecimal("0")))),
        )
        stub.failAdminInsert = true
        val service = MedicationAdministrationService(stub.pool)
        val error = failureOf(
            service.recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("already has an administration record"))
        // 失败整体回滚：执行状态更新不得执行
        assertTrue(stub.tuples.none { it.first.contains("update nursing.nursing_task_executions") }, "给药失败不得改写执行状态")
    }

    @Test
    fun `终态执行不可记录给药`() {
        for (terminal in listOf("COMPLETED", "SKIPPED", "CANCELLED")) {
            val stub = DatabaseStub(
                executions = rowSet(executionRow(mapOf("status" to terminal))),
                tasks = rowSet(taskRow()),
                orders = rowSet(orderRow()),
                dispenseItems = rowSet(dispenseItemRow()),
                adminSums = rowSet(row(mapOf("administered_sum" to BigDecimal("0")))),
            )
            val service = MedicationAdministrationService(stub.pool)
            val error = failureOf(
                service.recordAdministration(
                    "exec-1", "user-1",
                    JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
                ),
            )
            assertTrue(error is ConflictException, "$terminal 终态必须拒绝: ${error.message}")
            assertTrue(stub.tuples.none { it.first.contains("insert into nursing.medication_administrations") })
            assertTrue(stub.tuples.none { it.first.contains("update nursing.nursing_task_executions") })
        }
    }

    @Test
    fun `非MEDICATION任务与未关联医嘱的执行被拒`() {
        // 非 MEDICATION 任务
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow(mapOf("task_type" to "LIVING_CARE"))),
        )
        val service = MedicationAdministrationService(stub.pool)
        val notMedication = failureOf(
            service.recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(notMedication.message!!.contains("only MEDICATION tasks"))

        // 任务未关联医嘱
        val stub2 = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow(mapOf("order_item_id" to null))),
        )
        val noOrder = failureOf(
            MedicationAdministrationService(stub2.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(noOrder.message!!.contains("not linked to a medical order"))

        // 任务已终止
        val stub3 = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow(mapOf("status" to "CANCELLED"))),
        )
        val cancelled = failureOf(
            MedicationAdministrationService(stub3.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(cancelled is ConflictException)
    }

    @Test
    fun `未核对或非活动医嘱被拒`() {
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow(mapOf("nurse_checked_by" to null, "nurse_checked_at" to null))),
        )
        val notChecked = failureOf(
            MedicationAdministrationService(stub.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(notChecked is ConflictException && notChecked.message!!.contains("nurse-checked"))

        val stub2 = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow(mapOf("status" to "DISCONTINUED"))),
        )
        val stopped = failureOf(
            MedicationAdministrationService(stub2.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(stopped is ConflictException && stopped.message!!.contains("not active"))

        val stub3 = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow(mapOf("order_type" to "THERAPY"))),
        )
        val notMedication = failureOf(
            MedicationAdministrationService(stub3.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(notMedication.message!!.contains("not a medication order"))
    }

    @Test
    fun `非DISPENSED或不属于该医嘱的发药来源被拒`() {
        // 非 DISPENSED
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            dispenseItems = rowSet(dispenseItemRow(mapOf("dispense_status" to "PENDING"))),
        )
        val notDispensed = failureOf(
            MedicationAdministrationService(stub.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
            ),
        )
        assertTrue(notDispensed is ConflictException && notDispensed.message!!.contains("not DISPENSED"))

        // 来源明细不属于该医嘱
        val stub2 = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            dispenseItems = rowSet(dispenseItemRow(mapOf("order_item_id" to "ord-other"))),
        )
        val wrongOrder = failureOf(
            MedicationAdministrationService(stub2.pool).recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
            ),
        )
        assertTrue(wrongOrder is ConflictException && wrongOrder.message!!.contains("does not belong"))
    }

    @Test
    fun `累计给药超过实发数量返回409且执行状态不变`() {
        // 已给 9，本次 2，实发 10 → 超发
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            dispenseItems = rowSet(dispenseItemRow()),
            adminSums = rowSet(row(mapOf("administered_sum" to BigDecimal("9")))),
        )
        val service = MedicationAdministrationService(stub.pool)
        val error = failureOf(
            service.recordAdministration(
                "exec-1", "user-1",
                JsonObject().put("result", "已服").put("dispense_item_id", "di-1").put("administered_quantity", "2"),
            ),
        )
        assertTrue(error is ConflictException)
        assertTrue(error.message!!.contains("exceeds dispensed remaining quantity"))
        // 给药失败整体回滚：无插入、无执行更新
        assertTrue(stub.tuples.none { it.first.contains("insert into nursing.medication_administrations") })
        assertTrue(stub.tuples.none { it.first.contains("update nursing.nursing_task_executions") })

        // 恰好等于剩余数量时允许
        val stub2 = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            dispenseItems = rowSet(dispenseItemRow()),
            adminSums = rowSet(row(mapOf("administered_sum" to BigDecimal("8")))),
            administrations = rowSet(adminRow(mapOf("result" to "部分服"))),
        )
        val ok = MedicationAdministrationService(stub2.pool).recordAdministration(
            "exec-1", "user-1",
            JsonObject().put("result", "部分服").put("dispense_item_id", "di-1").put("administered_quantity", "2").put("reason", "部分服用"),
        ).toCompletionStage().toCompletableFuture().get()
        assertEquals("部分服", ok.getString("result"))
    }

    @Test
    fun `拒服不锁发药明细不消耗数量且执行转SKIPPED`() {
        val stub = DatabaseStub(
            executions = rowSet(executionRow()),
            tasks = rowSet(taskRow()),
            orders = rowSet(orderRow()),
            administrations = rowSet(adminRow(mapOf("result" to "拒服", "dispense_item_id" to null, "dispense_no" to null))),
        )
        val service = MedicationAdministrationService(stub.pool)
        val result = service.recordAdministration(
            "exec-1", "user-1",
            JsonObject().put("result", "拒服").put("reason", "长者拒绝服药"),
        ).toCompletionStage().toCompletableFuture().get()

        // 不锁发药明细、不对账（读回 join 药房表属正常只读）
        assertTrue(stub.queries.none { it.contains("pharmacy.pharmacy_dispense_items") && it.contains("for update") }, "拒服不得锁发药明细")
        assertTrue(stub.tuples.none { it.first.contains("sum(") }, "拒服不得做数量对账")
        // 执行转 SKIPPED
        val update = stub.tuples.first { it.first.contains("update nursing.nursing_task_executions") }
        assertEquals("SKIPPED", update.second.first(), "拒服必须联动为 SKIPPED: ${update.second}")
        // 原因写入给药记录
        val insert = stub.tuples.first { it.first.contains("insert into nursing.medication_administrations") }
        assertTrue(insert.second.contains("长者拒绝服药"), "拒服原因必须入库: ${insert.second}")
        assertEquals("拒服", result.getString("result"))
    }

    @Test
    fun `执行不存在或给药记录不存在返回404`() {
        val service = MedicationAdministrationService(DatabaseStub().pool)
        val notFound = failureOf(
            service.recordAdministration(
                "exec-missing", "user-1",
                JsonObject().put("result", "拒服").put("reason", "长者拒绝"),
            ),
        )
        assertTrue(notFound is NotFoundException)

        val getMissing = failureOf(service.getAdministrationByExecution("exec-1"))
        assertTrue(getMissing is NotFoundException && getMissing.message!!.contains("administration not found"))
    }
}
