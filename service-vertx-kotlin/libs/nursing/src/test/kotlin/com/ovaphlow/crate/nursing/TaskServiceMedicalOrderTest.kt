package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 医嘱派生护理任务（连接绑定内部协作）的非数据库测试：
 *   - createOrderTask 输入校验与插入 SQL/参数（含 nullable 字段不绑定）
 *   - terminateOrderTask 合法目标联动、非法目标、缺失/异常任务
 *   - 公共 create 白名单仍拒绝 MEDICATION/TREATMENT，既有五类不受影响
 */
class TaskServiceMedicalOrderTest {

    private class TaskStub(var lockTasks: RowSet<Row> = rowSet()) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        private var lastSql = ""
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val client = mockk<SqlClient>()

        init {
            every { client.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { client.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                val result = if (sql.contains("from nursing.nursing_tasks") && sql.contains("for update")) lockTasks
                else rowSet()
                Future.succeededFuture(result)
            }
        }

        private fun record(sql: String) {
            val normalizedSql = normalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }
    }

    private fun taskRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "tsk-1",
            "period_id" to "per-1",
            "encounter_id" to "enc-1",
            "plan_item_id" to null,
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

    private fun input(
        taskType: String? = "MEDICATION",
        orderItemId: String = "ord-1",
        description: String = "阿司匹林 100mg 每日一次",
        frequencyCode: String? = "QD",
        frequencyName: String? = "每日一次",
        startDate: LocalDate? = LocalDate.of(2026, 8, 1),
        endDate: LocalDate? = LocalDate.of(2026, 8, 4),
    ): TaskService.OrderTaskInput =
        TaskService.OrderTaskInput(
            periodId = "per-1",
            encounterId = "enc-1",
            orderItemId = orderItemId,
            taskType = taskType ?: "",
            description = description,
            frequencyCode = frequencyCode,
            frequencyName = frequencyName,
            startDate = startDate,
            endDate = endDate,
        )

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

    // ——— 1. createOrderTask 校验 ———

    @Test
    fun `createOrderTask校验拒绝非法输入且不触发SQL`() {
        val stub = TaskStub()
        val service = TaskService(mockk<Pool>())

        fun expectInvalid(input: TaskService.OrderTaskInput, fragment: String) {
            val cause = causeOf(service.createOrderTask(stub.client, input))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
        }

        expectInvalid(input(taskType = "NURSING"), "invalid order task_type")
        expectInvalid(input(taskType = null), "invalid order task_type")
        expectInvalid(input(orderItemId = "   "), "order_item_id is required")
        expectInvalid(input(description = "  "), "description is required")

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    // ——— 2. createOrderTask 成功 ———

    @Test
    fun `createOrderTask成功插入任务并返回全字段`() {
        val stub = TaskStub()
        val service = TaskService(mockk<Pool>())

        val result = service.createOrderTask(stub.client, input())
            .toCompletionStage().toCompletableFuture().get()

        assertNotNull(result.getString("id"))
        assertEquals("per-1", result.getString("period_id"))
        assertEquals("enc-1", result.getString("encounter_id"))
        assertEquals("ord-1", result.getString("order_item_id"))
        assertEquals("MEDICATION", result.getString("task_type"))
        assertEquals("阿司匹林 100mg 每日一次", result.getString("description"))
        assertEquals("QD", result.getString("frequency_code"))
        assertEquals("每日一次", result.getString("frequency_name"))
        assertEquals("2026-08-01", result.getString("start_date"))
        assertEquals("2026-08-04", result.getString("end_date"))
        assertEquals("ACTIVE", result.getString("status"))

        val (sql, values) = stub.tuples.single()
        assertTrue(sql.contains("insert into nursing.nursing_tasks"), "必须插入 nursing_tasks: $sql")
        for (column in listOf("id", "period_id", "encounter_id", "order_item_id", "task_type", "description", "status")) {
            assertTrue(sql.contains(column), "insert 必须含列 $column: $sql")
        }
        assertTrue(values.contains("per-1"))
        assertTrue(values.contains("enc-1"))
        assertTrue(values.contains("ord-1"))
        assertTrue(values.contains("MEDICATION"))
        assertTrue(values.contains("阿司匹林 100mg 每日一次"))
        assertTrue(values.contains("QD"))
        assertTrue(values.contains("每日一次"))
        assertTrue(values.contains(LocalDate.of(2026, 8, 1)))
        assertTrue(values.contains(LocalDate.of(2026, 8, 4)))
        assertTrue(values.contains("ACTIVE"))
    }

    @Test
    fun `createOrderTask空频次与日期不绑定对应列`() {
        val stub = TaskStub()
        val service = TaskService(mockk<Pool>())

        service.createOrderTask(
            stub.client,
            input(frequencyCode = null, frequencyName = null, startDate = null, endDate = null),
        ).toCompletionStage().toCompletableFuture().get()

        val (sql, values) = stub.tuples.single()
        assertFalse(sql.contains("frequency_code"), "frequency_code 为 null 时不得绑定: $sql")
        assertFalse(sql.contains("frequency_name"), "frequency_name 为 null 时不得绑定: $sql")
        assertFalse(sql.contains("start_date"), "start_date 为 null 时不得绑定: $sql")
        assertFalse(sql.contains("end_date"), "end_date 为 null 时不得绑定: $sql")
        assertTrue(values.contains("ord-1"))
        assertTrue(values.contains("MEDICATION"))
    }

    // ——— 3. terminateOrderTask ———

    @Test
    fun `terminateOrderTask成功取消或完成任务`() {
        val stub1 = TaskStub(lockTasks = rows(taskRow()))
        TaskService(mockk<Pool>()).terminateOrderTask(stub1.client, "ord-1", "CANCELLED")
            .toCompletionStage().toCompletableFuture().get()
        val update1 = stub1.queries.first { it.contains("update nursing.nursing_tasks") }
        assertTrue(update1.contains("set status = $"), "任务更新必须更新 status: $update1")
        assertTrue(update1.contains("where nursing.nursing_tasks.id = $"), "任务更新必须按任务 id 定位: $update1")
        assertTrue(stub1.tuples.first { it.first.contains("update nursing.nursing_tasks") }.second.contains("CANCELLED"))

        val stub2 = TaskStub(lockTasks = rows(taskRow()))
        TaskService(mockk<Pool>()).terminateOrderTask(stub2.client, "ord-1", "COMPLETED")
            .toCompletionStage().toCompletableFuture().get()
        val update2 = stub2.queries.first { it.contains("update nursing.nursing_tasks") }
        assertTrue(update2.contains("set status = $"), "任务必须更新 status: $update2")
        assertTrue(stub2.tuples.first { it.first.contains("update nursing.nursing_tasks") }.second.contains("COMPLETED"))
    }

    @Test
    fun `terminateOrderTask拒绝非法目标`() {
        val stub = TaskStub()
        val cause = causeOf(TaskService(mockk<Pool>()).terminateOrderTask(stub.client, "ord-1", "ACTIVE"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("invalid target status") == true, "got: ${cause.message}")
        assertTrue(stub.queries.isEmpty())
    }

    @Test
    fun `terminateOrderTask任务缺失返回冲突`() {
        val stub = TaskStub(lockTasks = rowSet())
        val cause = causeOf(TaskService(mockk<Pool>()).terminateOrderTask(stub.client, "ord-1", "CANCELLED"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("no linked task") == true, "got: ${cause.message}")
    }

    @Test
    fun `terminateOrderTask任务非活动返回冲突`() {
        for (terminal in listOf("CANCELLED", "COMPLETED")) {
            val stub = TaskStub(lockTasks = rows(taskRow(mapOf("status" to terminal))))
            val cause = causeOf(TaskService(mockk<Pool>()).terminateOrderTask(stub.client, "ord-1", "CANCELLED"))
            assertInstanceOf(ConflictException::class.java, cause)
            assertTrue(cause.message?.contains("unexpected status") == true, "got: ${cause.message}")
        }
    }

    // ——— 4. 公共 create 白名单 ———

    @Test
    fun `公共任务接口拒绝MEDICATION与TREATMENT`() {
        val service = TaskService(mockk<Pool>())

        val cause1 = causeOf(service.create(JsonObject().put("task_type", "MEDICATION").put("description", "x")))
        assertInstanceOf(IllegalArgumentException::class.java, cause1)
        assertTrue(cause1.message?.contains("invalid task_type") == true, "got: ${cause1.message}")

        val cause2 = causeOf(service.create(JsonObject().put("task_type", "TREATMENT").put("description", "x")))
        assertInstanceOf(IllegalArgumentException::class.java, cause2)
        assertTrue(cause2.message?.contains("invalid task_type") == true, "got: ${cause2.message}")
    }

    @Test
    fun `公共任务接口仍接受既有五类并到达写入层`() {
        val pool = mockk<Pool>()
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        var preparedQueries = 0
        every { pool.preparedQuery(any<String>()) } answers {
            preparedQueries++
            pq
        }
        every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(rowSet())
        val service = TaskService(pool)

        val result = service.create(
            JsonObject()
                .put("task_type", "NURSING")
                .put("description", "翻身护理")
                .put("period_id", "per-1")
                .put("encounter_id", "enc-1"),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("NURSING", result.getString("task_type"))
        assertEquals("ACTIVE", result.getString("status"))
        assertNotNull(result.getString("id"))
        assertEquals(1, preparedQueries, "公共接口必须到达 pool.preparedQuery 写入层")
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
