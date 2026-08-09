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
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * NursingIncidentService 非数据库测试。
 *
 * 直接调用生产方法，用 mockk 桩驱动 SQL 分支：
 *   - 事件创建写入固定字段（encounter/period 精确绑定、状态=已上报、上报人=认证主体）
 *   - 创建时同事务写入首条「上报」审计事实；携带 initial_action 时推进为「处理中」
 *   - 追加处置/通知/观察的状态推进与已关闭/终态周期拒绝
 *   - 关闭的幂等与并发（已关闭重复关闭 409）
 *   - 事实表无 UPDATE/DELETE 入口（无副作用）
 *   - 列表过滤分页与详情审计事实升序
 */
class NursingIncidentServiceTest {

    private class DatabaseStub(
        var incidents: RowSet<Row> = rowSet(),
        var actions: RowSet<Row> = rowSet(),
        var periods: RowSet<Row> = rowSet(),
        var totalRows: RowSet<Row> = rowSet(),
    ) {
        val connTuples = mutableListOf<Pair<String, List<Any?>>>()
        val connQueries = mutableListOf<String>()

        private var lastSql = ""
        val conn = mockk<SqlConnection>()
        val pool = mockk<Pool>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                connTuples.add(sql to tupleValues(firstArg()))
                val branch = when {
                    sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete") -> "write"
                    sql.startsWith("select count") -> "total"
                    sql.contains("nursing_incident_actions") -> "actions"
                    sql.contains("nursing_incidents") -> "incidents"
                    sql.contains("nursing_service_periods") -> "periods"
                    else -> "else"
                }
                val result = when (branch) {
                    "write", "else" -> rowSet()
                    "total" -> totalRows
                    "actions" -> actions
                    "incidents" -> incidents
                    "periods" -> periods
                    else -> rowSet()
                }
                Future.succeededFuture(result)
            }
            every { pq.execute() } answers {
                val sql = lastSql
                connTuples.add(sql to emptyList())
                Future.succeededFuture(rowSet())
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            connQueries.add(lastSql)
        }
    }

    private fun incidentRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "inc-1",
            "encounter_id" to "enc-1",
            "period_id" to "per-1",
            "incident_type" to "跌倒/坠床",
            "severity" to "较重",
            "status" to "已上报",
            "occurred_at" to OffsetDateTime.parse("2026-08-09T10:00:00+08:00"),
            "description" to "晨间活动时跌倒",
            "reporter" to "subj-1",
            "created_at" to OffsetDateTime.parse("2026-08-09T10:05:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-09T10:05:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun actionRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "act-1",
            "incident_id" to "inc-1",
            "action_type" to "处置",
            "body" to "已通知家属并安抚老人",
            "actor" to "subj-2",
            "occurred_at" to OffsetDateTime.parse("2026-08-09T10:10:00+08:00"),
            "notified_party" to "家属",
            "notification_result" to "已接通",
            "created_at" to OffsetDateTime.parse("2026-08-09T10:10:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun periodRow(status: String = "ACTIVE"): MutableMap<String, Any?> =
        mutableMapOf("status" to status)

    private fun causeOf(future: io.vertx.core.Future<*>): Throwable {
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

    private val service = NursingIncidentService(mockk<Pool>(relaxed = true))

    private fun createInput(initialAction: NursingIncidentService.ActionInput? = null): NursingIncidentService.IncidentCreateInput =
        NursingIncidentService.IncidentCreateInput(
            encounterId = "enc-1",
            periodId = "per-1",
            periodStartDate = LocalDate.of(2026, 8, 1),
            incidentType = "跌倒/坠床",
            severity = "较重",
            occurredAt = OffsetDateTime.parse("2026-08-09T10:00:00+08:00"),
            description = "晨间活动时跌倒",
            reporter = "subj-1",
            initialAction = initialAction,
        )

    // ========================================================================
    //  创建：固定字段与首条审计事实
    // ========================================================================

    @Test
    fun `创建事件写入固定字段且状态为已上报`() {
        val stub = DatabaseStub()
        val incident = service.createIncident(stub.conn, createInput())
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("enc-1", incident.getString("encounter_id"))
        assertEquals("per-1", incident.getString("period_id"))
        assertEquals("跌倒/坠床", incident.getString("incident_type"))
        assertEquals("较重", incident.getString("severity"))
        assertEquals("已上报", incident.getString("status"))
        assertEquals("subj-1", incident.getString("reporter"))
        assertNotNull(incident.getString("id"))

        val incidentInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_incidents") }
        assertEquals("enc-1", incidentInsert.second[1])
        assertEquals("per-1", incidentInsert.second[2])
        assertEquals("跌倒/坠床", incidentInsert.second[3])
        assertEquals("较重", incidentInsert.second[4])
        assertEquals("已上报", incidentInsert.second[5])
        assertEquals("晨间活动时跌倒", incidentInsert.second[7])
        // 上报人来自认证主体参数，不由客户端提交
        assertEquals("subj-1", incidentInsert.second[8])
    }

    @Test
    fun `创建时同事务写入首条上报审计事实`() {
        val stub = DatabaseStub()
        service.createIncident(stub.conn, createInput())
            .toCompletionStage().toCompletableFuture().get()

        val actionInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_incident_actions") }
        assertEquals("上报", actionInsert.second[2])
        assertEquals("晨间活动时跌倒", actionInsert.second[3])
        assertEquals("subj-1", actionInsert.second[4])
        // 事实表只允许追加：创建流程不得发出对 actions 的 UPDATE/DELETE
        assertTrue(stub.connQueries.none { it.startsWith("update") && it.contains("nursing_incident_actions") })
        assertTrue(stub.connQueries.none { it.startsWith("delete") && it.contains("nursing_incident_actions") })
    }

    @Test
    fun `携带即时处置时事件推进为处理中`() {
        val stub = DatabaseStub()
        val incident = service.createIncident(
            stub.conn,
            createInput(NursingIncidentService.ActionInput("处置", "立即查看并安抚", null, null)),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("处理中", incident.getString("status"))
        val updates = stub.connTuples.filter { it.first.startsWith("update") }
        assertEquals(1, updates.size)
        assertEquals("处理中", updates[0].second[0])
        val actionInserts = stub.connTuples.filter { it.first.startsWith("insert into nursing.nursing_incident_actions") }
        assertEquals(2, actionInserts.size)
        assertEquals("处置", actionInserts[1].second[2])
        assertEquals("立即查看并安抚", actionInserts[1].second[3])
    }

    // ========================================================================
    //  追加处置/通知/观察：状态推进与拒绝
    // ========================================================================

    @Test
    fun `追加处置将已上报事件推进为处理中`() {
        val stub = DatabaseStub(incidents = rows(incidentRow()), periods = rows(periodRow()))
        val result = service.appendAction(
            stub.conn, "enc-1", "inc-1",
            NursingIncidentService.ActionInput("处置", "已通知家属并安抚老人", "家属", "已接通"),
            "subj-2",
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("处理中", result.getJsonObject("incident").getString("status"))
        val update = stub.connTuples.first { it.first.startsWith("update") }
        assertEquals("处理中", update.second[0])
        val actionInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_incident_actions") }
        assertEquals("处置", actionInsert.second[2])
        assertEquals("subj-2", actionInsert.second[4])
    }

    @Test
    fun `已处理中事件追加动作保持处理中`() {
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("status" to "处理中"))), periods = rows(periodRow()))
        val result = service.appendAction(
            stub.conn, "enc-1", "inc-1",
            NursingIncidentService.ActionInput("观察", "观察半小时", null, null),
            "subj-2",
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("处理中", result.getJsonObject("incident").getString("status"))
        val update = stub.connTuples.first { it.first.startsWith("update") }
        assertEquals("处理中", update.second[0])
    }

    @Test
    fun `已关闭事件追加动作返回409`() {
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("status" to "已关闭"))))
        val cause = causeOf(service.appendAction(
            stub.conn, "enc-1", "inc-1",
            NursingIncidentService.ActionInput("处置", "x", null, null),
            "subj-2",
        ))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("closed") == true, "got: ${cause.message}")
        assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") }, "已关闭事件不得追加动作")
    }

    @Test
    fun `终态周期追加非关闭动作返回409`() {
        val stub = DatabaseStub(incidents = rows(incidentRow()), periods = rows(periodRow("COMPLETED")))
        val cause = causeOf(service.appendAction(
            stub.conn, "enc-1", "inc-1",
            NursingIncidentService.ActionInput("处置", "x", null, null),
            "subj-2",
        ))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("only a closing note") == true, "got: ${cause.message}")
        assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") })
    }

    @Test
    fun `跨入住追加处置返回404且不产生事实`() {
        // inc-1 属于 enc-2；用 enc-1 作用域锁读时按双重归属过滤，返回 404
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("encounter_id" to "enc-2"))))
        val cause = causeOf(service.appendAction(
            stub.conn, "enc-1", "inc-1",
            NursingIncidentService.ActionInput("处置", "x", null, null),
            "subj-2",
        ))
        assertInstanceOf(NotFoundException::class.java, cause)
        assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") })
        // 锁读必须同时绑定 incident_id 与 encounter_id
        assertTrue(stub.connTuples.first().first.contains("encounter_id"))
    }

    // ========================================================================
    //  关闭
    // ========================================================================

    @Test
    fun `关闭事件写入关闭事实并转为已关闭`() {
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("status" to "处理中"))))
        val result = service.closeIncident(stub.conn, "enc-1", "inc-1", "已妥善处理并完成回访", "subj-3")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("已关闭", result.getJsonObject("incident").getString("status"))
        val update = stub.connTuples.first { it.first.startsWith("update") }
        assertEquals("已关闭", update.second[0])
        val actionInsert = stub.connTuples.first { it.first.startsWith("insert into nursing.nursing_incident_actions") }
        assertEquals("关闭", actionInsert.second[2])
        assertEquals("已妥善处理并完成回访", actionInsert.second[3])
        assertEquals("subj-3", actionInsert.second[4])
    }

    @Test
    fun `重复关闭返回409且不产生新事实`() {
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("status" to "已关闭"))))
        val cause = causeOf(service.closeIncident(stub.conn, "enc-1", "inc-1", "再次关闭", "subj-3"))
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("already closed") == true, "got: ${cause.message}")
        assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") })
    }

    @Test
    fun `终态周期仍允许为既有事件追加一次关闭`() {
        // 离院/去世收束周期后，既有事件只允许关闭（不允许新建或追加处置）
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("status" to "处理中"))), periods = rows(periodRow("COMPLETED")))
        val result = service.closeIncident(stub.conn, "enc-1", "inc-1", "离院前完成行政关闭", "subj-3")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("已关闭", result.getJsonObject("incident").getString("status"))
    }

    @Test
    fun `跨入住关闭返回404`() {
        val stub = DatabaseStub(incidents = rows(incidentRow(mapOf("encounter_id" to "enc-2"))))
        val cause = causeOf(service.closeIncident(stub.conn, "enc-1", "inc-1", "跨入住关闭", "subj-3"))
        assertInstanceOf(NotFoundException::class.java, cause)
        assertTrue(stub.connTuples.none { it.first.startsWith("insert into nursing.nursing_incident_actions") })
    }

    @Test
    fun `事件不存在返回404`() {
        val stub = DatabaseStub()
        val cause = causeOf(service.lockIncident(stub.conn, "missing", "enc-1"))
        assertInstanceOf(NotFoundException::class.java, cause)
    }

    // ========================================================================
    //  只读：列表过滤分页与详情
    // ========================================================================

    @Test
    fun `列表按状态过滤并分页返回总数`() {
        val stub = DatabaseStub(
            incidents = rows(incidentRow(), incidentRow(mapOf("id" to "inc-2"))),
            totalRows = rows(mutableMapOf("total" to 2L)),
        )
        // 只读路径走服务自身的 pool：绑定 stub.pool 才能驱动 SQL 分支
        val readService = NursingIncidentService(stub.pool)
        val response = readService.listIncidents("enc-1", "已上报", null, null, 50, 0)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(2, response.getJsonArray("records").size())
        assertEquals(2L, response.getJsonObject("meta").getLong("total"))
        val countTuple = stub.connTuples.first { it.first.startsWith("select count") && it.first.contains("nursing_incidents") }
        assertTrue(countTuple.second.contains("已上报"), "状态过滤应作为绑定参数进入 SQL")
        assertTrue(countTuple.second.contains("enc-1"), "encounter 过滤应作为绑定参数进入 SQL")
    }

    @Test
    fun `详情返回全部审计事实且按时间升序`() {
        val stub = DatabaseStub(
            incidents = rows(incidentRow()),
            actions = rows(
                actionRow(mapOf("id" to "act-1", "action_type" to "上报")),
                actionRow(mapOf("id" to "act-2", "action_type" to "处置")),
            ),
        )
        val readService = NursingIncidentService(stub.pool)
        val detail = readService.getIncident("enc-1", "inc-1").toCompletionStage().toCompletableFuture().get()

        assertEquals("enc-1", detail.getString("encounter_id"))
        assertEquals("per-1", detail.getString("period_id"))
        val actions = detail.getJsonArray("actions")
        assertEquals(2, actions.size())
        assertEquals("上报", actions.getJsonObject(0).getString("action_type"))
        assertEquals("处置", actions.getJsonObject(1).getString("action_type"))
    }

    // ========================================================================
    //  辅助（与既有非数据库测试一致的 mockk 行构造，位于文件顶层供嵌套 stub 默认参数使用）
    // ========================================================================
}

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
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
    val result = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) {
        result.add(tuple.getValue(i))
    }
    return result
}
