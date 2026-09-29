package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

private const val TEST_PORT = 18435
private const val CONVERGE_PATH = "/converge-overdue"
private const val ENCOUNTER_A = "01J5D3M00000000000000COV01"
private const val ENCOUNTER_B = "01J5D3M00000000000000COV02"

private val ACTIVE_EXECUTION_STATUSES = setOf("PENDING", "IN_PROGRESS")
private val ALL_EXECUTION_STATUSES = setOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun rowSet(vararg rows: Row): RowSet<Row> =
    mockk(relaxed = true) {
        every { size() } returns rows.size
        every { iterator() } answers {
            val delegate = rows.iterator()
            mockk<RowIterator<Row>>(relaxed = true) {
                every { hasNext() } answers { delegate.hasNext() }
                every { next() } answers { delegate.next() }
            }
        }
    }

private fun updateResult(rowCount: Int): RowSet<Row> =
    mockk(relaxed = true) {
        every { size() } returns rowCount
        every { rowCount() } returns rowCount
    }

private fun idRow(id: String): Row =
    mockk(relaxed = true) {
        every { getValue("id") } returns id
    }

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (index in 0 until tuple.size()) {
        values.add(tuple.getValue(index))
    }
    return values
}

/**
 * 030 W5 逾期执行收口（计划 §3 D5 / §4.3）的非数据库测试。
 *
 * 全内存桩（mockk `Pool` / `SqlConnection` / `PreparedQuery`，按 normalized SQL 特征分发）：
 * 把 `nursing.nursing_task_executions` 抽象成一张可变更的表，并记录每条 SQL 与绑定参数。
 * 覆盖：
 *   - 正常收敛 N 条 + 第二次调用幂等 `converged: 0`；
 *   - 阈值过滤（未达阈值不收敛）、`encounter_id`/`task_type` 过滤、`limit` 截断与 `truncated`、
 *     `limit` 上限 200（探针 SQL 取 201 条）；
 *   - `IN_PROGRESS` 可被收敛，且状态机确实允许 `IN_PROGRESS → SKIPPED`（030 D5）；
 *   - 请求体白名单未知键 → 400、非法 `min_overdue_minutes`（0/负数/非数字）→ 400；
 *   - 终态行（COMPLETED/SKIPPED/CANCELLED）不产生任何写；
 *   - 嵌入式 Vert.x 路由测试（真实 HTTP）：与 `/today`、`/overdue` 同处静态段，
 *     不遮蔽 `/:id` 泛型路径，尾部斜杠容错。
 *
 * 不访问数据库；不校验真实 SQL 的语义（由隔离库集成测试覆盖）。
 */
@ExtendWith(VertxExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskExecutionConvergeTest {

    // ========================================================================
    //  全内存数据库桩
    // ========================================================================

    private class Execution(
        val id: String,
        var status: String,
        val plannedTime: OffsetDateTime,
        val encounterId: String? = null,
        val taskType: String? = null,
        var metadata: JsonObject? = null,
    )

    /**
     * 桩：`nursing.nursing_task_executions` 的内存副本。
     *
     * - 候选 SELECT：按 SQL 文本里是否出现 `encounter_id` / `task_type` 决定过滤列，
     *   阈值取绑定参数中唯一的 `OffsetDateTime`，状态只放行 PENDING / IN_PROGRESS；
     * - UPDATE：复刻 `WHERE id = ? AND status IN ('PENDING','IN_PROGRESS')` 守卫
     *   与 `COALESCE(metadata,'{}'::jsonb) || patch` 浅合并语义（终态行必然返回 rowCount 0）。
     */
    private class ConvergeDatabase {
        val pool = mockk<Pool>()
        private val connection = mockk<SqlConnection>()
        private val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        private var lastSql = ""

        var executions: MutableList<Execution> = mutableListOf()

        /** 已执行的语句：(normalized SQL, 绑定参数)，按执行顺序 */
        val selects = mutableListOf<Pair<String, List<Any?>>>()
        val updates = mutableListOf<Pair<String, List<Any?>>>()

        init {
            every { connection.preparedQuery(any<String>()) } answers {
                lastSql = normalized(firstArg<String>())
                prepared
            }
            every { pool.preparedQuery(any<String>()) } answers {
                lastSql = normalized(firstArg<String>())
                prepared
            }
            every { pool.withTransaction<Any>(any()) } answers {
                firstArg<JavaFunction<SqlConnection, Future<Any>>>().apply(connection)
            }
            every { prepared.execute(any<Tuple>()) } answers {
                val values = tupleValues(firstArg())
                val sql = lastSql
                when {
                    sql.startsWith("update") -> {
                        updates.add(sql to values)
                        Future.succeededFuture(applyUpdate(values))
                    }

                    sql.contains("nursing_task_execution_consumptions") -> Future.succeededFuture(rowSet())
                    sql.contains("nursing_task_executions") && sql.contains(" join ") -> {
                        selects.add(sql to values)
                        Future.succeededFuture(candidateRows(sql, values))
                    }

                    sql.contains("nursing_task_executions") -> Future.succeededFuture(executionRowById(values))
                    else -> Future.succeededFuture(rowSet())
                }
            }
        }

        private fun candidateRows(
            sql: String,
            values: List<Any?>,
        ): RowSet<Row> {
            val threshold = values.filterIsInstance<OffsetDateTime>().first()
            val boundTexts = values.filterIsInstance<String>()
            val filterByEncounter = sql.contains("encounter_id")
            val filterByTaskType = sql.contains("task_type")
            val matched =
                executions
                    .filter { it.status in ACTIVE_EXECUTION_STATUSES }
                    .filter { it.plannedTime.isBefore(threshold) }
                    .filter { !filterByEncounter || (it.encounterId != null && it.encounterId in boundTexts) }
                    .filter { !filterByTaskType || (it.taskType != null && it.taskType in boundTexts) }
                    .sortedWith(compareBy({ it.plannedTime }, { it.id }))
            return rowSet(*matched.map { idRow(it.id) }.toTypedArray())
        }

        private fun executionRowById(values: List<Any?>): RowSet<Row> {
            val id = values.filterIsInstance<String>().firstOrNull { candidate -> executions.any { it.id == candidate } }
            val execution = executions.firstOrNull { it.id == id } ?: return rowSet()
            val fields =
                mapOf<String, Any?>(
                    "id" to execution.id,
                    "task_id" to "task-${execution.id}",
                    "planned_time" to execution.plannedTime,
                    "actual_time" to null,
                    "executor" to null,
                    "status" to execution.status,
                    "stock_operation_detail_id" to null,
                    "quantity" to null,
                    "note" to null,
                    "metadata" to execution.metadata,
                    "created_at" to execution.plannedTime,
                )
            val row = mockk<Row>()
            for ((key, value) in fields) {
                every { row.getValue(key) } returns value
            }
            return rowSet(row)
        }

        /**
         * 复刻 UPDATE 语义。新状态取绑定参数中第一个状态字面量（jOOQ 按 SQL 出现顺序绑定，
         * SET 子句先于 WHERE 子句）。
         */
        private fun applyUpdate(values: List<Any?>): RowSet<Row> {
            val id = values.filterIsInstance<String>().firstOrNull { candidate -> executions.any { it.id == candidate } }
            val target = executions.firstOrNull { it.id == id } ?: return updateResult(0)
            if (target.status !in ACTIVE_EXECUTION_STATUSES) return updateResult(0)
            values.filterIsInstance<String>().firstOrNull { it in ALL_EXECUTION_STATUSES }?.let { target.status = it }
            values.filterIsInstance<JsonObject>().firstOrNull()?.let { patch ->
                val merged = JsonObject(target.metadata?.encode() ?: "{}")
                for (key in patch.fieldNames()) {
                    merged.put(key, patch.getValue(key))
                }
                target.metadata = merged
            }
            return updateResult(1)
        }
    }

    // ========================================================================
    //  fixture 与辅助
    // ========================================================================

    private val database = ConvergeDatabase()

    private var server: io.vertx.core.http.HttpServer? = null
    private var client: HttpClient? = null

    private fun execution(
        id: String,
        status: String = "PENDING",
        minutesAgo: Long = 3000,
        encounterId: String? = ENCOUNTER_A,
        taskType: String? = "NURSING",
        metadata: JsonObject? = null,
    ): Execution =
        Execution(
            id = id,
            status = status,
            plannedTime = OffsetDateTime.now().minusMinutes(minutesAgo),
            encounterId = encounterId,
            taskType = taskType,
            metadata = metadata,
        )

    private fun service(): TaskExecutionService = TaskExecutionService(database.pool, DatabaseConfig.createDSL())

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    // ========================================================================
    //  服务层：收敛行为
    // ========================================================================

    @Test
    fun `收口把逾期未终态执行置SKIPPED并合并metadata`() {
        database.executions =
            mutableListOf(
                execution("exec-older", minutesAgo = 3000, metadata = JsonObject().put("note", "保留我")),
                execution("exec-newer", status = "IN_PROGRESS", minutesAgo = 2000),
                // 终态行必须完全不被触碰
                execution("exec-completed", status = "COMPLETED", minutesAgo = 5000),
                execution("exec-skipped", status = "SKIPPED", minutesAgo = 5000),
                execution("exec-cancelled", status = "CANCELLED", minutesAgo = 5000),
                // 未达默认阈值（1440 分钟）
                execution("exec-fresh", minutesAgo = 10),
            )

        val result = service().convergeOverdueExecutions().result()

        assertNotNull(result, "离线渲染路径不应失败")
        assertEquals(2, result.getInteger("converged"))
        assertEquals(listOf("exec-older", "exec-newer"), result.getJsonArray("ids").map { it.toString() })
        assertFalse(result.getBoolean("truncated"), "全部候选均被收敛时 truncated 必须为 false")
        assertEquals(TaskExecutionService.CONVERGE_MIN_OVERDUE_MINUTES_DEFAULT, result.getInteger("min_overdue_minutes"))

        // 逐条置 SKIPPED（按 planned_time ASC）
        assertEquals("SKIPPED", database.executions.single { it.id == "exec-older" }.status)
        assertEquals("SKIPPED", database.executions.single { it.id == "exec-newer" }.status)

        // metadata 合并：既有键保留，只新增 convergence
        val merged = database.executions.single { it.id == "exec-older" }.metadata!!
        assertEquals("保留我", merged.getString("note"), "既有 metadata 键不得被覆盖")
        val convergence = merged.getJsonObject("convergence")!!
        assertEquals("逾期未执行，批量标记漏执行", convergence.getString("reason"))
        assertEquals(1440, convergence.getInteger("min_overdue_minutes"))
        assertNotNull(OffsetDateTime.parse(convergence.getString("at")), "at 必须是 ISO-8601")
        val patchKeys =
            database.updates
                .flatMap { (_, values) -> values.filterIsInstance<JsonObject>() }
                .flatMap { it.fieldNames() }
                .toSet()
        assertEquals(setOf("convergence"), patchKeys, "合并补丁只应携带 convergence 键，不得覆盖其它键")

        // 终态行状态未被改写，且不出现在任何 UPDATE 绑定参数中
        assertEquals("COMPLETED", database.executions.single { it.id == "exec-completed" }.status)
        assertEquals("SKIPPED", database.executions.single { it.id == "exec-skipped" }.status)
        assertEquals("CANCELLED", database.executions.single { it.id == "exec-cancelled" }.status)
        val touchedIdsAcrossUpdates =
            database.updates
                .flatMap { (_, values) -> values.filterIsInstance<String>() }
                .filter { it.startsWith("exec-") }
                .toSet()
        assertEquals(
            setOf("exec-older", "exec-newer"),
            touchedIdsAcrossUpdates,
            "终态行（COMPLETED/SKIPPED/CANCELLED）不得出现在任何 UPDATE 绑定参数中",
        )

        // SQL 形状：候选 SELECT + 带守卫的 JSONB 合并 UPDATE
        val candidateSql = database.selects.single().first
        assertTrue(candidateSql.contains("from nursing.nursing_task_executions as e"), candidateSql)
        assertTrue(candidateSql.contains("join nursing.nursing_tasks as t"), candidateSql)
        assertTrue(
            Regex("order\\s+by\\s+e\\.planned_time\\s+asc").containsMatchIn(candidateSql),
            "候选必须按 planned_time ASC：$candidateSql",
        )
        assertTrue(candidateSql.contains("planned_time <"), "候选必须限定 planned_time 阈值：$candidateSql")
        assertTrue(
            Regex("status\\s+in\\s*\\(").containsMatchIn(candidateSql),
            "候选必须限定未终态状态：$candidateSql",
        )

        assertEquals(2, database.updates.size, "必须逐条更新：${database.updates}")
        for ((sql, values) in database.updates) {
            assertTrue(sql.startsWith("update nursing.nursing_task_executions"), sql)
            assertTrue(
                Regex("status\\s+in\\s*\\(").containsMatchIn(sql),
                "UPDATE 必须自带非终态守卫，终态行永不被写：$sql",
            )
            assertTrue(sql.contains("'{}'::jsonb) ||"), "metadata 必须用 JSONB 浅合并，而不是整列覆盖：$sql")
            val touchedIds =
                values
                    .filterIsInstance<String>()
                    .filter { it.startsWith("exec-") }
                    .toSet()
            assertEquals(1, touchedIds.size, "每条 UPDATE 只应命中一条执行：$values")
            assertTrue(
                touchedIds.single() in setOf("exec-older", "exec-newer"),
                "UPDATE 不得命中终态行：$values",
            )
            assertEquals(
                setOf("convergence"),
                values.filterIsInstance<JsonObject>().single().fieldNames(),
                "补丁只带 convergence 键：$values",
            )
        }
    }

    @Test
    fun `重复调用第二次converged为0`() {
        database.executions =
            mutableListOf(
                execution("exec-1", minutesAgo = 3000),
                execution("exec-2", status = "IN_PROGRESS", minutesAgo = 2000),
            )
        val service = service()

        val first = service.convergeOverdueExecutions().result()
        assertEquals(2, first.getInteger("converged"))
        val updatesAfterFirst = database.updates.size
        assertEquals(2, updatesAfterFirst, "首轮必须逐条更新两条执行：${database.updates}")

        val second = service.convergeOverdueExecutions().result()
        assertEquals(0, second.getInteger("converged"), "第二次调用必须幂等为 0")
        assertTrue(second.getJsonArray("ids").isEmpty, "第二次调用不得返回 id")
        assertFalse(second.getBoolean("truncated"))
        assertEquals(updatesAfterFirst, database.updates.size, "第二次调用不得再产生任何 UPDATE：${database.updates}")
    }

    @Test
    fun `阈值过滤未达阈值的执行不收敛`() {
        database.executions =
            mutableListOf(
                execution("exec-59min", minutesAgo = 59),
                execution("exec-61min", minutesAgo = 61),
            )

        val byThreshold = service().convergeOverdueExecutions(minOverdueMinutes = 60).result()
        assertEquals(listOf("exec-61min"), byThreshold.getJsonArray("ids").map { it.toString() })
        assertEquals(60, byThreshold.getInteger("min_overdue_minutes"))
        assertEquals("PENDING", database.executions.single { it.id == "exec-59min" }.status, "未达阈值不得收敛")

        // 默认阈值 1440 分钟：30 分钟前的逾期不属于「逾期超过 24 小时」
        database.executions = mutableListOf(execution("exec-30min", minutesAgo = 30))
        val byDefault = service().convergeOverdueExecutions().result()
        assertEquals(0, byDefault.getInteger("converged"))
        assertEquals(1440, byDefault.getInteger("min_overdue_minutes"))
    }

    @Test
    fun `encounter_id与task_type过滤`() {
        fun seed() {
            database.executions =
                mutableListOf(
                    execution("exec-a-nursing", encounterId = ENCOUNTER_A, taskType = "NURSING"),
                    execution("exec-a-rehab", encounterId = ENCOUNTER_A, taskType = "REHABILITATION"),
                    execution("exec-b-nursing", encounterId = ENCOUNTER_B, taskType = "NURSING"),
                )
        }

        seed()
        val byEncounter = service().convergeOverdueExecutions(encounterId = ENCOUNTER_A).result()
        assertEquals(
            listOf("exec-a-nursing", "exec-a-rehab"),
            byEncounter.getJsonArray("ids").map { it.toString() },
        )
        assertEquals("PENDING", database.executions.single { it.id == "exec-b-nursing" }.status)

        seed()
        val byTaskType = service().convergeOverdueExecutions(taskType = "NURSING").result()
        assertEquals(
            listOf("exec-a-nursing", "exec-b-nursing"),
            byTaskType.getJsonArray("ids").map { it.toString() },
        )

        seed()
        database.selects.clear()
        val byBoth = service().convergeOverdueExecutions(encounterId = ENCOUNTER_A, taskType = "NURSING").result()
        assertEquals(listOf("exec-a-nursing"), byBoth.getJsonArray("ids").map { it.toString() })

        val filteredSql = database.selects.single().first
        assertTrue(filteredSql.contains("encounter_id"), "必须按 encounter_id 过滤：$filteredSql")
        assertTrue(filteredSql.contains("task_type"), "必须按 task_type 过滤：$filteredSql")

        // 无过滤时不引入多余条件
        seed()
        database.selects.clear()
        service().convergeOverdueExecutions().result()
        val plainSql = database.selects.single().first
        assertFalse(plainSql.contains("encounter_id"), "未传 encounter_id 时不得过滤：$plainSql")
        assertFalse(plainSql.contains("task_type"), "未传 task_type 时不得过滤：$plainSql")
    }

    @Test
    fun `limit截断与truncated`() {
        // 三条都超过默认阈值（1440 分钟），按 planned_time ASC 应为 oldest → middle → newest
        database.executions =
            mutableListOf(
                execution("exec-oldest", minutesAgo = 5000),
                execution("exec-middle", minutesAgo = 4000),
                execution("exec-newest", minutesAgo = 3000),
            )
        val service = service()

        val first = service.convergeOverdueExecutions(limit = 2).result()
        assertEquals(2, first.getInteger("converged"))
        assertEquals(listOf("exec-oldest", "exec-middle"), first.getJsonArray("ids").map { it.toString() })
        assertTrue(first.getBoolean("truncated"), "还有候选未处理时必须告知 truncated=true")
        assertEquals("PENDING", database.executions.single { it.id == "exec-newest" }.status)

        val second = service.convergeOverdueExecutions(limit = 2).result()
        assertEquals(1, second.getInteger("converged"))
        assertEquals(listOf("exec-newest"), second.getJsonArray("ids").map { it.toString() })
        assertFalse(second.getBoolean("truncated"), "候选取尽后 truncated 必须为 false")
    }

    @Test
    fun `limit上限收敛为200`() {
        database.executions = mutableListOf(execution("exec-1", minutesAgo = 3000), execution("exec-2", minutesAgo = 2000))

        val result = service().convergeOverdueExecutions(limit = 9999).result()

        assertEquals(2, result.getInteger("converged"))
        assertFalse(result.getBoolean("truncated"))
        val (sql, values) = database.selects.single()
        // Postgres 方言下 jOOQ 把 limit 渲染为 `fetch next $n rows only` 并作为绑定参数
        val probeLimit = values.filterIsInstance<Number>().map { it.toInt() }
        assertTrue(
            sql.contains("limit 201") || probeLimit.contains(201),
            "候选探针应为 200 + 1 条：$sql / $values",
        )
    }

    // ========================================================================
    //  服务层：状态机（030 D5）
    // ========================================================================

    @Test
    fun `IN_PROGRESS可被收敛且状态机允许该转移`() {
        database.executions = mutableListOf(execution("exec-running", status = "IN_PROGRESS", minutesAgo = 3000))
        val service = service()

        val converged = service.convergeOverdueExecutions().result()
        assertEquals(1, converged.getInteger("converged"), "IN_PROGRESS 逾期行必须能收口")
        assertEquals("SKIPPED", database.executions.single { it.id == "exec-running" }.status)

        // 通用 PATCH /:id/status 路径：IN_PROGRESS → SKIPPED（030 §3 D5 有意口径统一）
        database.executions = mutableListOf(execution("exec-patch", status = "IN_PROGRESS", minutesAgo = 10))
        val updated = service.updateStatus("exec-patch", "SKIPPED", note = "人工跳过").result()
        assertNotNull(updated, "IN_PROGRESS → SKIPPED 必须被状态机允许")
        assertEquals("SKIPPED", updated.getString("status"))
        assertEquals("SKIPPED", database.executions.single { it.id == "exec-patch" }.status)

        // 终态仍无出边：COMPLETED 不得再转 SKIPPED
        database.executions = mutableListOf(execution("exec-completed", status = "COMPLETED", minutesAgo = 10))
        val error = failureOf(service.updateStatus("exec-completed", "SKIPPED"))
        assertTrue(error is IllegalArgumentException, "终态不得再转移：$error")
        assertEquals(
            "cannot transition from COMPLETED to SKIPPED",
            error.message,
        )
    }

    // ========================================================================
    //  服务层：纯函数（请求归一化与留痕载荷）
    // ========================================================================

    @Test
    fun `normalizeConvergeRequest默认值与边界`() {
        assertEquals(
            Pair(1440, 200),
            TaskExecutionService.normalizeConvergeRequest(null, null),
            "默认阈值 1440、默认 limit 200",
        )
        assertEquals(Pair(1, 1), TaskExecutionService.normalizeConvergeRequest(1, 1))
        assertEquals(Pair(60, 200), TaskExecutionService.normalizeConvergeRequest(60, 9999), "limit 上限 200")
        assertEquals(Pair(60, 1), TaskExecutionService.normalizeConvergeRequest(60, 0), "limit 下限钳制为 1")
        assertEquals(Pair(60, 1), TaskExecutionService.normalizeConvergeRequest(60, -5))

        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            val error = assertThrows<IllegalArgumentException> {
                TaskExecutionService.normalizeConvergeRequest(invalid, null)
            }
            assertEquals("min_overdue_minutes must be an integer >= 1", error.message)
        }
    }

    @Test
    fun `convergencePayload为冻结文案与ISO8601`() {
        val at = OffsetDateTime.parse("2026-09-29T10:20:30.400+08:00")
        val payload = TaskExecutionService.convergencePayload(1440, at)

        assertEquals(setOf("reason", "at", "min_overdue_minutes"), payload.fieldNames())
        assertEquals("逾期未执行，批量标记漏执行", payload.getString("reason"))
        assertEquals("2026-09-29T10:20:30.400+08:00", payload.getString("at"))
        assertEquals(1440, payload.getInteger("min_overdue_minutes"))
        assertEquals(at, OffsetDateTime.parse(payload.getString("at")))
    }

    @Test
    fun `optionalInt只接受整数`() {
        assertNull(TaskExecutionRoutes.optionalInt(JsonObject(), "limit"), "缺键应为 null")
        assertNull(TaskExecutionRoutes.optionalInt(JsonObject().put("limit", null as Any?), "limit"))
        assertEquals(5, TaskExecutionRoutes.optionalInt(JsonObject().put("limit", 5), "limit"))
        assertEquals(5, TaskExecutionRoutes.optionalInt(JsonObject().put("limit", 5L), "limit"))
        assertEquals(0, TaskExecutionRoutes.optionalInt(JsonObject().put("limit", 0), "limit"), "0 交由服务层拒绝")

        for (raw in listOf("abc", "1.5", true, 1.5)) {
            val error = assertThrows<IllegalArgumentException> {
                TaskExecutionRoutes.optionalInt(JsonObject().put("limit", raw), "limit")
            }
            assertEquals("limit must be an integer", error.message)
        }
    }

    @Test
    fun `optionalText只接受字符串`() {
        assertNull(TaskExecutionRoutes.optionalText(JsonObject(), "encounter_id"), "缺键应为 null")
        assertNull(TaskExecutionRoutes.optionalText(JsonObject().put("encounter_id", null as Any?), "encounter_id"))
        assertEquals("enc-1", TaskExecutionRoutes.optionalText(JsonObject().put("encounter_id", " enc-1 "), "encounter_id"))
        assertNull(TaskExecutionRoutes.optionalText(JsonObject().put("encounter_id", "   "), "encounter_id"), "空白视为未提供")
        assertNull(TaskExecutionRoutes.optionalText(JsonObject().put("encounter_id", ""), "encounter_id"))

        for (raw in listOf(123, 1L, true, JsonObject(), io.vertx.core.json.JsonArray())) {
            val error = assertThrows<IllegalArgumentException> {
                TaskExecutionRoutes.optionalText(JsonObject().put("encounter_id", raw), "encounter_id")
            }
            assertEquals("encounter_id must be a string", error.message)
        }
    }

    // ========================================================================
    //  嵌入式路由测试（真实 HTTP，不访问数据库）
    // ========================================================================

    @BeforeAll
    fun startServer(
        vertx: Vertx,
        ctx: VertxTestContext,
    ) {
        client = vertx.createHttpClient()
        vertx
            .createHttpServer()
            .requestHandler(TaskExecutionRoutes.create(vertx, database.pool))
            .listen(TEST_PORT)
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
    fun stopServer(ctx: VertxTestContext) {
        client?.close()
        val current = server
        if (current == null) {
            ctx.completeNow()
            return
        }
        current.close { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @BeforeEach
    fun resetDatabase() {
        database.executions = mutableListOf()
        database.selects.clear()
        database.updates.clear()
    }

    private fun post(
        vertx: Vertx,
        body: JsonObject,
        path: String = CONVERGE_PATH,
    ): Future<Pair<Int, JsonObject>> = postRaw(vertx, body.encode(), path)

    private fun postRaw(
        vertx: Vertx,
        rawBody: String,
        path: String = CONVERGE_PATH,
    ): Future<Pair<Int, JsonObject>> {
        val shared = client ?: vertx.createHttpClient().also { client = it }
        return shared
            .request(HttpMethod.POST, TEST_PORT, "localhost", path)
            .compose { request ->
                request
                    .putHeader("Content-Type", "application/json")
                    .send(rawBody)
                    .compose { response ->
                        response.body().map { buffer -> Pair(response.statusCode(), JsonObject(buffer)) }
                    }
            }
    }

    private fun get(
        vertx: Vertx,
        path: String,
    ): Future<Pair<Int, JsonObject>> {
        val shared = client ?: vertx.createHttpClient().also { client = it }
        return shared
            .request(HttpMethod.GET, TEST_PORT, "localhost", path)
            .compose { it.send() }
            .compose { response -> response.body().map { buffer -> Pair(response.statusCode(), JsonObject(buffer)) } }
    }

    @Test
    fun `路由正常收敛且第二次幂等`(vertx: Vertx, ctx: VertxTestContext) {
        database.executions =
            mutableListOf(
                execution("exec-late", status = "IN_PROGRESS", minutesAgo = 3000),
                execution("exec-earlier", minutesAgo = 5000),
            )

        post(vertx, JsonObject())
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "正常请求应 200：$body")
                    assertEquals(2, body.getInteger("converged"))
                    assertEquals(listOf("exec-earlier", "exec-late"), body.getJsonArray("ids").map { it.toString() })
                    assertFalse(body.getBoolean("truncated"))
                    assertEquals(1440, body.getInteger("min_overdue_minutes"))
                    assertEquals("SKIPPED", database.executions.single { it.id == "exec-late" }.status)
                }
                post(vertx, JsonObject())
            }.compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "第二次调用仍应 200：$body")
                    assertEquals(0, body.getInteger("converged"), "第二次调用必须幂等为 0")
                    assertTrue(body.getJsonArray("ids").isEmpty)
                }
                // 静态段不得遮蔽泛型 /:id
                get(vertx, "/exec-earlier")
            }.map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "GET /:id 泛型路径必须仍然可用：$body")
                    assertEquals("exec-earlier", body.getString("id"))
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非JSON对象请求体返回400而不是500`(vertx: Vertx, ctx: VertxTestContext) {
        // 030 评审 P2-1 收口：数组 / 字符串 / 畸形 JSON 必须 400，不得因 asJsonObject() 抛异常退化成 500
        val payloads = listOf("[1,2,3]", "\"just-a-string\"", "{not-json")

        fun step(index: Int): Future<Unit> {
            if (index >= payloads.size) return Future.succeededFuture()
            return postRaw(vertx, payloads[index]).compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "非 JSON 对象体必须 400（${payloads[index]}）：$body")
                    assertEquals("body must be a JSON object", body.getString("error"))
                }
                step(index + 1)
            }
        }

        step(0).onSuccess { ctx.completeNow() }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `未知键返回400`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, JsonObject().put("foo", 1))
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "未知键必须 400：$body")
                    assertEquals("unsupported converge keys: foo", body.getString("error"))
                }
                post(vertx, JsonObject().put("limit", 1).put("bar", 2).put("baz", 3))
            }.map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "未知键必须 400：$body")
                    assertEquals("unsupported converge keys: bar, baz", body.getString("error"))
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非法min_overdue_minutes返回400`(vertx: Vertx, ctx: VertxTestContext) {
        // 0 / 负数由服务层契约拒绝，非数字由路由层类型校验拒绝
        val payloads = listOf("{\"min_overdue_minutes\":0}", "{\"min_overdue_minutes\":-5}", "{\"min_overdue_minutes\":\"abc\"}")

        fun step(index: Int): Future<Unit> {
            if (index >= payloads.size) return Future.succeededFuture()
            return postRaw(vertx, payloads[index]).compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "非法 min_overdue_minutes 必须 400（${payloads[index]}）：$body")
                    assertNotNull(body.getString("error"))
                }
                step(index + 1)
            }
        }

        database.executions = mutableListOf(execution("exec-late", minutesAgo = 3000))
        step(0)
            .map {
                ctx.verify {
                    assertTrue(database.updates.isEmpty(), "被拒绝的请求不得产生任何写：${database.updates}")
                    assertEquals("PENDING", database.executions.single().status)
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非字符串encounter_id返回400而不是500`(vertx: Vertx, ctx: VertxTestContext) {
        database.executions = mutableListOf(execution("exec-late", minutesAgo = 3000))

        post(vertx, JsonObject().put("encounter_id", 123))
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "非字符串 encounter_id 必须 400（不得 500）：$body")
                    assertEquals("encounter_id must be a string", body.getString("error"))
                    assertTrue(database.updates.isEmpty(), "被拒绝的请求不得产生任何写：${database.updates}")
                }
                postRaw(vertx, "{\"task_type\":[\"MEDICATION\"]}")
            }.map { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "非字符串 task_type 必须 400（不得 500）：$body")
                    assertEquals("task_type must be a string", body.getString("error"))
                    assertEquals("PENDING", database.executions.single().status)
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `空白task_type按未提供处理`(vertx: Vertx, ctx: VertxTestContext) {
        fun step(
            index: Int,
            payloads: List<JsonObject>,
        ): Future<Unit> {
            if (index >= payloads.size) return Future.succeededFuture()
            // 每次都重置为未收敛状态，两次调用都应是「未提供 → 不过滤 → 收敛 1 条」
            database.executions = mutableListOf(execution("exec-late", minutesAgo = 3000))
            return post(vertx, payloads[index]).compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "空白 task_type 应视为未提供并正常收敛：$body")
                    assertEquals(1, body.getInteger("converged"))
                    val sql = database.selects.last().first
                    assertFalse(sql.contains("task_type"), "空白 task_type 不得参与过滤：$sql")
                }
                step(index + 1, payloads)
            }
        }

        step(0, listOf(JsonObject().put("task_type", ""), JsonObject().put("task_type", "   ")))
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `路由limit截断与truncated`(vertx: Vertx, ctx: VertxTestContext) {
        database.executions =
            mutableListOf(
                execution("exec-oldest", minutesAgo = 5000),
                execution("exec-middle", minutesAgo = 4000),
                execution("exec-newest", minutesAgo = 3000),
            )

        post(vertx, JsonObject().put("limit", 2))
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "limit=2 应 200：$body")
                    assertEquals(2, body.getInteger("converged"))
                    assertTrue(body.getBoolean("truncated"), "还有候选时必须 truncated=true")
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `路由按encounter与task_type过滤`(vertx: Vertx, ctx: VertxTestContext) {
        database.executions =
            mutableListOf(
                execution("exec-a-nursing", encounterId = ENCOUNTER_A, taskType = "NURSING"),
                execution("exec-b-nursing", encounterId = ENCOUNTER_B, taskType = "NURSING"),
                execution("exec-a-rehab", encounterId = ENCOUNTER_A, taskType = "REHABILITATION"),
            )

        post(vertx, JsonObject().put("encounter_id", ENCOUNTER_A).put("task_type", "NURSING"))
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "过滤请求应 200：$body")
                    assertEquals(listOf("exec-a-nursing"), body.getJsonArray("ids").map { it.toString() })
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `路由尾部斜杠容错`(vertx: Vertx, ctx: VertxTestContext) {
        database.executions = mutableListOf(execution("exec-late", minutesAgo = 3000))

        post(vertx, JsonObject(), path = "$CONVERGE_PATH/")
            .map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "带尾部斜杠的 /converge-overdue/ 应 200：$body")
                    assertEquals(1, body.getInteger("converged"))
                }
            }.onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
