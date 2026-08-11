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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 体征异常告警闭环（VitalSignService 复核/转诊 + 异常列表/摘要 + 路由）非数据库测试。
 * 覆盖验收口径：
 *   - 状态机全路径：待复核 → 已确认 → 已转诊；待复核 → 已误报（终态）
 *   - 非异常记录复核 400；已误报/已转诊终态不可再复核；转诊前置必须已确认
 *   - 转诊单事务创建随访计划（慢病随访/门诊/assignee=认证主体），metadata 关联体征记录
 *   - 转诊计划日期不早于入住开始日（沿用 FollowupService 规则）
 *   - PATCH 修正 abnormal 翻转 → 复核状态重置为待复核并清空结论；未翻转保留
 *   - 异常列表跨老人筛选（patient_id 可选）、按 measured_at 倒序、空列表 records:[] total:0
 *   - 摘要计数（待复核/今日新增/已转诊/类型分布/状态分布）与筛选语义
 *   - 非法 review_status/result 400；写路由无认证 401；嵌入式 HTTP 全链路
 */
@ExtendWith(VertxExtension::class)
class VitalSignReviewTest {

    private class DatabaseStub(
        var patients: RowSet<Row> = rowSet(),
        var encounters: RowSet<Row> = rowSet(),
        var records: RowSet<Row> = rowSet(),
        var recordCounts: RowSet<Row> = rowSet(),
        /** baseQuery/detailQuery（带 join）的行；与 getRecordRow 的 selectFrom 区分 */
        var detailRows: RowSet<Row> = rowSet(),
        var plans: RowSet<Row> = rowSet(),
        var typeGroups: RowSet<Row> = rowSet(),
        var statusGroups: RowSet<Row> = rowSet(),
        var updateAffected: Int = 1,
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        /** 摘要/列表计数查询依次弹出的结果；为空时回退 recordCounts */
        val countQueue = ArrayDeque<RowSet<Row>>()
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
                val result = when {
                    sql.contains("insert into healthcare.followup_plans") -> rowSet()
                    sql.contains("insert into healthcare.vital_sign_records") -> rowSet()
                    sql.contains("update healthcare.vital_sign_records") -> updated(updateAffected)
                    sql.contains("from healthcare.followup_plans") -> plans
                    sql.contains("group by") && sql.contains("review_status") -> statusGroups
                    sql.contains("group by") && sql.contains("from healthcare.vital_sign_records") -> typeGroups
                    sql.contains("count(*)") && sql.contains("from healthcare.vital_sign_records") ->
                        countQueue.removeFirstOrNull() ?: recordCounts
                    sql.contains("from healthcare.vital_sign_records") && sql.contains("join") -> detailRows
                    sql.contains("from healthcare.vital_sign_records") -> records
                    sql.contains("from healthcare.encounters") -> encounters
                    sql.contains("from healthcare.patients") -> patients
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
            lastSql = normalized(sql)
            queries.add(lastSql)
        }
    }

    // ——— fixture 行 ———

    private fun patientRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "pat-1",
            "name" to "张奶奶",
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260801001",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun recordRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "vs-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "type" to "TEMPERATURE",
            "value" to BigDecimal("38.9"),
            "unit" to "℃",
            "measured_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
            "recorded_by" to "user-1",
            "abnormal" to true,
            "note" to null,
            "metadata" to null,
            "review_status" to "待复核",
            "review_result" to null,
            "review_note" to null,
            "reviewed_by" to null,
            "reviewed_at" to null,
            "deleted_at" to null,
            "created_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-10T08:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun planRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "plan-1",
            "patient_id" to "pat-1",
            "patient_name" to "张奶奶",
            "encounter_id" to "enc-1",
            "encounter_no" to "A20260801001",
            "followup_type" to "慢病随访",
            // 相对今天：避免计划日早于当天被查询计算为已逾期（日期漂移）
            "planned_date" to LocalDate.now(),
            "planned_way" to "门诊",
            "assignee" to "user-1",
            "status" to "待随访",
            "completed_at" to null,
            "cancel_reason" to null,
            "remark" to null,
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-10T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-10T09:00:00+08:00"),
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

    private fun successOf(future: Future<*>): Any? =
        try {
            future.toCompletionStage().toCompletableFuture().get()
        } catch (error: Throwable) {
            throw AssertionError("expected future to succeed, got: $error")
        }

    private val ulidPattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    // ========================================================================
    //  1. 状态机：复核（确认异常 / 误报）
    // ========================================================================

    @Test
    fun `确认异常后状态为已确认且复核人与时间留痕`() {
        val stub = DatabaseStub(
            records = rows(recordRow()),
            detailRows = rows(
                recordRow(
                    mapOf(
                        "review_status" to "已确认",
                        "review_result" to "确认异常",
                        "review_note" to "复测确认发热",
                        "reviewed_by" to "user-2",
                        "reviewed_at" to OffsetDateTime.parse("2026-08-10T09:30:00+08:00"),
                    ),
                ),
            ),
        )
        val service = VitalSignService(stub.pool)

        val result = successOf(
            service.reviewVitalSign(
                "vs-1",
                JsonObject().put("result", "确认异常").put("note", "复测确认发热"),
                "user-2",
            ),
        ) as JsonObject
        assertEquals("已确认", result.getString("review_status"))
        assertEquals("确认异常", result.getString("review_result"))
        assertEquals("复测确认发热", result.getString("review_note"))
        assertEquals("user-2", result.getString("reviewed_by"), "复核人必须取自认证主体")
        assertNotNull(result.getString("reviewed_at"), "复核时间必须留痕")

        val updateSql = stub.queries.first { it.contains("update healthcare.vital_sign_records") }
        assertTrue(updateSql.contains("review_status = $"), "复核必须更新状态: $updateSql")
        assertTrue(updateSql.contains("reviewed_by = $"), "复核人必须落库: $updateSql")
        assertTrue(updateSql.contains("reviewed_at"), "复核时间必须落库: $updateSql")
        assertTrue(updateSql.contains("deleted_at is null"), "已作废记录不可复核: $updateSql")
        val updateTuple = stub.tuples.first { it.first.contains("update healthcare.vital_sign_records") }.second
        assertTrue(updateTuple.contains("已确认"), "确认异常 → 已确认落库")
        assertTrue(updateTuple.contains("user-2"), "复核人正确落库")
        assertEquals(1, stub.transactionCalls, "复核必须单事务")
    }

    @Test
    fun `误报标记为已误报且可覆盖式重复复核`() {
        val stub = DatabaseStub(
            records = rows(recordRow()),
            detailRows = rows(
                recordRow(
                    mapOf(
                        "review_status" to "已误报",
                        "review_result" to "误报",
                        "reviewed_by" to "user-2",
                    ),
                ),
            ),
        )
        val service = VitalSignService(stub.pool)

        val result = successOf(service.reviewVitalSign("vs-1", JsonObject().put("result", "误报"), "user-2")) as JsonObject
        assertEquals("已误报", result.getString("review_status"))
        assertEquals("误报", result.getString("review_result"))
        assertEquals("user-2", result.getString("reviewed_by"))

        // 已确认 → 误报 覆盖式纠错（同一记录可再次复核）
        stub.records = rows(recordRow(mapOf("review_status" to "已确认")))
        stub.detailRows = rows(
            recordRow(
                mapOf(
                    "review_status" to "已误报",
                    "review_result" to "误报",
                    "reviewed_by" to "user-3",
                ),
            ),
        )
        val again = successOf(service.reviewVitalSign("vs-1", JsonObject().put("result", "误报"), "user-3")) as JsonObject
        assertEquals("已误报", again.getString("review_status"))
        assertEquals("user-3", again.getString("reviewed_by"), "覆盖式更新取最新复核人")
        val updateSql = stub.queries.last { it.contains("update healthcare.vital_sign_records") }
        assertTrue(updateSql.contains("review_result = $"), "复核结论必须落库: $updateSql")
    }

    @Test
    fun `非异常记录复核返回400`() {
        val stub = DatabaseStub(records = rows(recordRow(mapOf("abnormal" to false))))
        val cause = causeOf(
            VitalSignService(stub.pool).reviewVitalSign("vs-1", JsonObject().put("result", "确认异常"), "user-2"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("only abnormal records can be reviewed") == true)
        assertTrue(stub.queries.none { it.contains("update healthcare.vital_sign_records") }, "非法复核不得触发更新")
    }

    @Test
    fun `已误报与已转诊为终态不可再复核`() {
        val service = VitalSignService(DatabaseStub(records = rows(recordRow(mapOf("review_status" to "已误报")))).pool)
        val misreported = causeOf(service.reviewVitalSign("vs-1", JsonObject().put("result", "确认异常"), "user-2"))
        assertInstanceOf(IllegalArgumentException::class.java, misreported)
        assertTrue(misreported.message?.contains("terminal") == true, "got: ${misreported.message}")

        val referred = causeOf(
            VitalSignService(DatabaseStub(records = rows(recordRow(mapOf("review_status" to "已转诊")))).pool)
                .reviewVitalSign("vs-1", JsonObject().put("result", "误报"), "user-2"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, referred)
        assertTrue(referred.message?.contains("terminal") == true, "got: ${referred.message}")
    }

    @Test
    fun `复核拒绝白名单外字段与非法结果及超长备注`() {
        val stub = DatabaseStub(records = rows(recordRow()))
        val service = VitalSignService(stub.pool)

        val extra = causeOf(
            service.reviewVitalSign("vs-1", JsonObject().put("result", "确认异常").put("reviewed_by", "hacked"), "user-2"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, extra)
        assertTrue(extra.message?.contains("unsupported vital sign review keys") == true, "got: ${extra.message}")

        val invalid = causeOf(service.reviewVitalSign("vs-1", JsonObject().put("result", "已核实"), "user-2"))
        assertInstanceOf(IllegalArgumentException::class.java, invalid)
        assertTrue(invalid.message?.contains("invalid result") == true, "got: ${invalid.message}")

        val missing = causeOf(service.reviewVitalSign("vs-1", JsonObject(), "user-2"))
        assertInstanceOf(IllegalArgumentException::class.java, missing)
        assertTrue(missing.message?.contains("result is required") == true)

        val longNote = causeOf(
            service.reviewVitalSign("vs-1", JsonObject().put("result", "确认异常").put("note", "a".repeat(501)), "user-2"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, longNote)
        assertTrue(longNote.message?.contains("500 characters") == true)
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    @Test
    fun `复核不存在或已作废记录返回404`() {
        val stub = DatabaseStub(records = rowSet())
        val cause = causeOf(
            VitalSignService(stub.pool).reviewVitalSign("vs-missing", JsonObject().put("result", "确认异常"), "user-2"),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, cause)

        stub.records = rows(recordRow(mapOf("deleted_at" to OffsetDateTime.now())))
        val deleted = causeOf(
            VitalSignService(stub.pool).reviewVitalSign("vs-1", JsonObject().put("result", "确认异常"), "user-2"),
        )
        assertInstanceOf(HealthcareNotFoundException::class.java, deleted)
    }

    // ========================================================================
    //  2. 状态机：转诊
    // ========================================================================

    @Test
    fun `已确认转诊后状态为已转诊且事务内创建随访计划并关联体征记录`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            records = rows(recordRow(mapOf("review_status" to "已确认", "reviewed_by" to "user-2"))),
            detailRows = rows(recordRow(mapOf("review_status" to "已转诊"))),
            plans = rows(
                planRow(
                    mapOf(
                        "id" to "plan-1",
                        "assignee" to "user-3",
                        "metadata" to JsonObject()
                            .put("vital_sign_record_id", "vs-1")
                            .put("source", "体征异常告警"),
                    ),
                ),
            ),
        )
        val service = VitalSignService(stub.pool)

        val result = successOf(service.referVitalSign("vs-1", JsonObject(), "user-3")) as JsonObject

        // 记录：已确认 → 已转诊
        val record = result.getJsonObject("record")
        assertEquals("已转诊", record.getString("review_status"))
        // 随访计划：慢病随访 / 门诊 / assignee=认证主体 / 待随访
        val plan = result.getJsonObject("followup_plan")
        assertEquals("plan-1", plan.getString("id"))
        assertEquals("慢病随访", plan.getString("followup_type"))
        assertEquals("门诊", plan.getString("planned_way"))
        assertEquals("user-3", plan.getString("assignee"), "转诊责任人必须取自认证主体")
        assertEquals("待随访", plan.getString("status"))
        assertEquals("张奶奶", plan.getString("patient_name"))

        // 事务内同时落库：记录状态更新 + 随访计划插入
        assertEquals(1, stub.transactionCalls, "转诊必须单事务")
        val planInsert = stub.tuples.first { it.first.contains("insert into healthcare.followup_plans") }
        assertTrue(planInsert.second.contains("慢病随访"), "计划类型为慢病随访")
        assertTrue(planInsert.second.contains("门诊"), "计划方式为门诊")
        assertTrue(planInsert.second.contains("user-3"), "assignee 为认证主体")
        val metadata = planInsert.second.filterIsInstance<JsonObject>().firstOrNull { it.containsKey("vital_sign_record_id") }
        assertNotNull(metadata, "计划 metadata 必须关联体征记录")
        assertEquals("vs-1", metadata?.getString("vital_sign_record_id"), "metadata 关联体征记录 id")
        assertEquals("体征异常告警", metadata?.getString("source"), "metadata 标记来源")
        val recordUpdate = stub.tuples.first { it.first.contains("update healthcare.vital_sign_records") }
        assertTrue(recordUpdate.second.contains("已转诊"), "记录状态更新为已转诊")
        assertTrue(ulidPattern.matches(planInsert.second.first() as? String ?: ""), "计划 id 必须为 ULID")
    }

    @Test
    fun `转诊支持自定义计划日期与备注`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            records = rows(recordRow(mapOf("review_status" to "已确认"))),
            detailRows = rows(recordRow(mapOf("review_status" to "已转诊"))),
            plans = rows(planRow(mapOf("planned_date" to LocalDate.parse("2026-08-11"), "remark" to "发热待查"))),
        )
        val result = successOf(
            VitalSignService(stub.pool).referVitalSign(
                "vs-1",
                JsonObject().put("planned_date", "2026-08-11").put("remark", "发热待查"),
                "user-3",
            ),
        ) as JsonObject
        assertEquals("2026-08-11", result.getJsonObject("followup_plan").getString("planned_date"))
        assertEquals("发热待查", result.getJsonObject("followup_plan").getString("remark"))
        val insert = stub.tuples.first { it.first.contains("insert into healthcare.followup_plans") }
        assertTrue(insert.second.contains(LocalDate.parse("2026-08-11")), "自定义计划日期落库")
    }

    @Test
    fun `转诊前置状态非已确认返回400`() {
        val stub = DatabaseStub(records = rows(recordRow(mapOf("review_status" to "待复核"))))
        val cause = causeOf(VitalSignService(stub.pool).referVitalSign("vs-1", JsonObject(), "user-3"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("review_status=已确认") == true, "got: ${cause.message}")
        assertTrue(stub.queries.none { it.contains("insert into healthcare.followup_plans") }, "前置不满足不得创建计划")

        // 已转诊为终态，不可重复转诊
        val terminal = causeOf(
            VitalSignService(DatabaseStub(records = rows(recordRow(mapOf("review_status" to "已转诊")))).pool)
                .referVitalSign("vs-1", JsonObject(), "user-3"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, terminal)
    }

    @Test
    fun `转诊计划日期不得早于入住开始日`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            records = rows(recordRow(mapOf("review_status" to "已确认"))),
        )
        val cause = causeOf(
            VitalSignService(stub.pool).referVitalSign(
                "vs-1",
                JsonObject().put("planned_date", "2026-07-01"),
                "user-3",
            ),
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("planned_date must not be earlier than the admission start date") == true)
    }

    @Test
    fun `体征记录无入住周期时转诊返回400`() {
        val stub = DatabaseStub(
            records = rows(recordRow(mapOf("review_status" to "已确认", "encounter_id" to null))),
        )
        val cause = causeOf(VitalSignService(stub.pool).referVitalSign("vs-1", JsonObject(), "user-3"))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("has no encounter") == true, "got: ${cause.message}")
    }

    @Test
    fun `转诊拒绝白名单外字段与非法日期`() {
        val stub = DatabaseStub(records = rows(recordRow(mapOf("review_status" to "已确认"))))
        val service = VitalSignService(stub.pool)

        val extra = causeOf(
            service.referVitalSign("vs-1", JsonObject().put("assignee", "hacked"), "user-3"),
        )
        assertInstanceOf(IllegalArgumentException::class.java, extra)
        assertTrue(extra.message?.contains("unsupported vital sign refer keys") == true, "got: ${extra.message}")

        val badDate = causeOf(service.referVitalSign("vs-1", JsonObject().put("planned_date", "2026-7-1"), "user-3"))
        assertInstanceOf(IllegalArgumentException::class.java, badDate)
        assertTrue(badDate.message?.contains("ISO-8601 date") == true, "got: ${badDate.message}")
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    // ========================================================================
    //  3. 修正触发状态重置
    // ========================================================================

    @Test
    fun `修正导致abnormal翻转时重置复核状态并清空结论`() {
        val stub = DatabaseStub(
            records = rows(
                recordRow(
                    mapOf(
                        "value" to BigDecimal("38.9"),
                        "abnormal" to true,
                        "review_status" to "已确认",
                        "review_result" to "确认异常",
                        "reviewed_by" to "user-2",
                    ),
                ),
            ),
            detailRows = rows(recordRow(mapOf("value" to BigDecimal("36.5"), "abnormal" to false))),
        )
        val result = successOf(
            VitalSignService(stub.pool).updateVitalSign("vs-1", JsonObject().put("value", 36.5), "user-1"),
        ) as JsonObject
        assertFalse(result.getBoolean("abnormal"), "修正后 36.5℃ 不判异常")

        val updateSql = stub.queries.first { it.contains("update healthcare.vital_sign_records") }
        assertTrue(updateSql.contains("review_status = $"), "abnormal 翻转必须重置 review_status: $updateSql")
        assertTrue(updateSql.contains("review_result"), "abnormal 翻转必须清空复核结论: $updateSql")
        assertTrue(updateSql.contains("reviewed_by"), "abnormal 翻转必须清空复核人: $updateSql")
        assertTrue(updateSql.contains("reviewed_at"), "abnormal 翻转必须清空复核时间: $updateSql")
        val updateTuple = stub.tuples.first { it.first.contains("update healthcare.vital_sign_records") }.second
        assertTrue(updateTuple.contains("待复核"), "状态重置为待复核")
    }

    @Test
    fun `修正未翻转abnormal时保留复核状态`() {
        val stub = DatabaseStub(
            records = rows(
                recordRow(
                    mapOf(
                        "value" to BigDecimal("38.9"),
                        "abnormal" to true,
                        "review_status" to "已确认",
                    ),
                ),
            ),
            detailRows = rows(recordRow(mapOf("value" to BigDecimal("39.1"), "abnormal" to true))),
        )
        successOf(VitalSignService(stub.pool).updateVitalSign("vs-1", JsonObject().put("value", 39.1), "user-1"))
        val updateSql = stub.queries.first { it.contains("update healthcare.vital_sign_records") }
        assertTrue(updateSql.contains("abnormal = $"), "修正必须重算 abnormal: $updateSql")
        assertTrue(!updateSql.contains("review_status = $"), "abnormal 未翻转不得重置复核状态: $updateSql")
    }

    // ========================================================================
    //  4. 异常列表
    // ========================================================================

    @Test
    fun `异常列表跨老人筛选支持可选patient与状态类型时间过滤`() {
        val stub = DatabaseStub(
            recordCounts = rows(mapOf("total" to 2L)),
            detailRows = rows(
                recordRow(),
                recordRow(mapOf("id" to "vs-2", "patient_id" to "pat-2", "patient_name" to "李大爷", "type" to "PULSE")),
            ),
        )
        val result = successOf(
            VitalSignService(stub.pool).listAbnormalSigns(
                patientId = "pat-1",
                type = "TEMPERATURE",
                reviewStatus = "已确认",
                dateFrom = "2026-08-01T00:00:00+08:00",
                dateTo = "2026-08-31T23:59:59+08:00",
                limit = 20,
                offset = 0,
            ),
        ) as JsonObject
        assertEquals(2, result.getJsonArray("records").size())
        assertEquals(2L, result.getJsonObject("meta").getLong("total"))

        val countSql = stub.queries.first { it.contains("count(*)") && it.contains("from healthcare.vital_sign_records") }
        assertTrue(countSql.contains("abnormal = $"), "异常列表固定 abnormal=true 过滤: $countSql")
        assertTrue(countSql.contains("deleted_at is null"), "必须排除已作废记录: $countSql")
        assertTrue(countSql.contains("patient_id = $"), "patient_id 过滤生效（可选）: $countSql")
        assertTrue(countSql.contains("type = $"), "类型过滤生效: $countSql")
        assertTrue(countSql.contains("review_status = $"), "复核状态过滤生效: $countSql")
        assertTrue(countSql.contains("measured_at >= cast($") && countSql.contains("measured_at <= cast($"), "时间范围过滤生效: $countSql")
        val countTuple = stub.tuples.first { it.first == countSql }.second
        assertTrue(countTuple.contains(true) && countTuple.contains("已确认") && countTuple.contains("pat-1"), "过滤参数正确落库")

        val dataSql = stub.queries.last { it.contains("from healthcare.vital_sign_records") && it.contains("join") }
        assertTrue(dataSql.contains("measured_at") && dataSql.contains("desc"), "异常列表按测量时间倒序: $dataSql")
        assertTrue(dataSql.contains("offset $"), "分页生效: $dataSql")

        // patient_id 可选：不传仍可查询
        val withoutPatient = successOf(
            VitalSignService(stub.pool).listAbnormalSigns(null, null, null, null, null, 50, 0),
        ) as JsonObject
        assertEquals(2, withoutPatient.getJsonArray("records").size())
    }

    @Test
    fun `异常列表空返回records空数组与total0`() {
        val stub = DatabaseStub(recordCounts = rows(mapOf("total" to 0L)), detailRows = rowSet())
        val result = successOf(
            VitalSignService(stub.pool).listAbnormalSigns(null, null, null, null, null, 50, 0),
        ) as JsonObject
        assertEquals(0, result.getJsonArray("records").size())
        assertEquals(0L, result.getJsonObject("meta").getLong("total"))
    }

    @Test
    fun `非法复核状态与类型过滤返回400`() {
        val stub = DatabaseStub()
        val service = VitalSignService(stub.pool)

        val badStatus = causeOf(service.listAbnormalSigns(null, null, "已处理", null, null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, badStatus)
        assertTrue(badStatus.message?.contains("invalid review_status") == true, "got: ${badStatus.message}")

        val badType = causeOf(service.listAbnormalSigns(null, "HEART_RATE", null, null, null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, badType)
        assertTrue(badType.message?.contains("invalid type") == true)

        val badDate = causeOf(service.listAbnormalSigns(null, null, null, "2026-08-10", null, 50, 0))
        assertInstanceOf(IllegalArgumentException::class.java, badDate)
        assertTrue(badDate.message?.contains("ISO-8601 offset date-time") == true)
        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL")
    }

    // ========================================================================
    //  5. 统计摘要
    // ========================================================================

    @Test
    fun `摘要统计待复核今日新增已转诊与分组分布`() {
        val stub = DatabaseStub(
            typeGroups = rows(
                mapOf("type" to "TEMPERATURE", "count" to 4L),
                mapOf("type" to "PULSE", "count" to 1L),
            ),
            statusGroups = rows(
                mapOf("review_status" to "待复核", "count" to 3L),
                mapOf("review_status" to "已转诊", "count" to 2L),
            ),
        )
        stub.countQueue.addAll(
            listOf(
                rows(mapOf("total" to 3L)), // 待复核
                rows(mapOf("total" to 5L)), // 今日新增
                rows(mapOf("total" to 2L)), // 已转诊
            ),
        )
        val result = successOf(VitalSignService(stub.pool).getAbnormalSummary()) as JsonObject

        assertEquals(3L, result.getLong("pending_total"))
        assertEquals(5L, result.getLong("today_total"))
        assertEquals(2L, result.getLong("referred_total"))

        val byType = result.getJsonArray("by_type")
        assertEquals(2, byType.size())
        assertEquals("TEMPERATURE", byType.getJsonObject(0).getString("type"))
        assertEquals(4L, byType.getJsonObject(0).getLong("count"))
        val byStatus = result.getJsonArray("by_status")
        assertEquals(2, byStatus.size())
        assertEquals("待复核", byStatus.getJsonObject(0).getString("status"))
        assertEquals(3L, byStatus.getJsonObject(0).getLong("count"))

        // 各计数查询语义：同一时间基准 + abnormal=true（分组查询另计）
        val countQueries = stub.queries.filter {
            it.contains("count(*)") && it.contains("from healthcare.vital_sign_records") && !it.contains("group by")
        }
        assertEquals(3, countQueries.size, "三个计数查询: $countQueries")
        countQueries.forEach { sql ->
            assertTrue(sql.contains("abnormal = $"), "计数必须限定异常记录: $sql")
            assertTrue(sql.contains("deleted_at is null"), "计数必须排除已作废记录: $sql")
        }
        assertTrue(countQueries[0].contains("review_status = $"), "待复核按状态过滤: ${countQueries[0]}")
        assertTrue(countQueries[1].contains("measured_at >= cast($") && countQueries[1].contains("measured_at < cast($"), "今日新增按当天区间: ${countQueries[1]}")
        assertTrue(countQueries[2].contains("review_status = $"), "已转诊按状态过滤: ${countQueries[2]}")
        // pending 与 referred 的 SQL 结构相同（仅绑定值不同）：用 first/last 区分执行序
        assertEquals("待复核", stub.tuples.first { it.first == countQueries[0] }.second.first { it == "待复核" })
        assertEquals("已转诊", stub.tuples.last { it.first == countQueries[2] }.second.first { it == "已转诊" })

        val typeSql = stub.queries.first { it.contains("group by") && it.contains("from healthcare.vital_sign_records") && !it.contains("review_status") }
        assertTrue(typeSql.contains("group by healthcare.vital_sign_records.type"), "类型分布按 type 分组: $typeSql")
        val statusSql = stub.queries.first { it.contains("group by") && it.contains("review_status") }
        assertTrue(statusSql.contains("group by healthcare.vital_sign_records.review_status"), "状态分布按 review_status 分组: $statusSql")
    }

    // ========================================================================
    //  6. 路由
    // ========================================================================

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body)
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
    fun `复核与转诊路由无认证上下文返回401`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub()
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs/vs-1/review", JsonObject().put("result", "确认异常").encode())
                .compose { (reviewStatus, reviewBody) ->
                    ctx.verify {
                        assertEquals(401, reviewStatus)
                        assertEquals("authentication required", reviewBody.getString("error"))
                    }
                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs/vs-1/refer", "{}")
                        .map { (referStatus, referBody) ->
                            ctx.verify {
                                assertEquals(401, referStatus)
                                assertEquals("authentication required", referBody.getString("error"))
                                ctx.completeNow()
                            }
                        }
                }
        }
    }

    @Test
    fun `异常告警路由全链路可用`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounters = rows(encounterRow()),
            recordCounts = rows(mapOf("total" to 1L)),
            detailRows = rows(recordRow()),
            typeGroups = rows(mapOf("type" to "TEMPERATURE", "count" to 1L)),
            statusGroups = rows(mapOf("review_status" to "待复核", "count" to 1L)),
        )
        // 队列顺序：异常列表计数、待复核、今日新增、已转诊
        stub.countQueue.addAll(
            listOf(
                rows(mapOf("total" to 1L)),
                rows(mapOf("total" to 1L)),
                rows(mapOf("total" to 1L)),
                rows(mapOf("total" to 0L)),
            ),
        )
        withServer(vertx, stub, userId = "user-2") { port ->
            // 1) 异常列表（静态路径先于 /vital-signs/:id，命中 abnormal 路由；中文参数需 URL 编码）
            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/vital-signs/abnormal?review_status=%E5%BE%85%E5%A4%8D%E6%A0%B8&limit=10&offset=0")
                .compose { (listStatus, list) ->
                    ctx.verify {
                        assertEquals(200, listStatus)
                        assertEquals(1, list.getJsonArray("records").size())
                        assertEquals("待复核", list.getJsonArray("records").getJsonObject(0).getString("review_status"))
                        assertEquals(1L, list.getJsonObject("meta").getLong("total"))
                    }
                    // 2) 统计摘要
                    httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/vital-signs/abnormal/summary")
                        .compose { (summaryStatus, summary) ->
                            ctx.verify {
                                assertEquals(200, summaryStatus)
                                assertEquals(1L, summary.getLong("pending_total"))
                                assertEquals(1L, summary.getLong("today_total"))
                                assertEquals(0L, summary.getLong("referred_total"))
                                assertEquals(1, summary.getJsonArray("by_type").size())
                            }
                            // 3) 复核：确认异常 → 已确认
                            stub.records = rows(recordRow(mapOf("review_status" to "待复核")))
                            stub.detailRows = rows(recordRow(mapOf("review_status" to "已确认", "review_result" to "确认异常", "reviewed_by" to "user-2")))
                            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs/vs-1/review", JsonObject().put("result", "确认异常").encode())
                                .compose { (reviewStatus, reviewed) ->
                                    ctx.verify {
                                        assertEquals(200, reviewStatus)
                                        assertEquals("已确认", reviewed.getString("review_status"))
                                        assertEquals("user-2", reviewed.getString("reviewed_by"))
                                    }
                                    // 4) 转诊：已确认 → 已转诊 + 随访计划
                                    stub.records = rows(recordRow(mapOf("review_status" to "已确认")))
                                    stub.detailRows = rows(recordRow(mapOf("review_status" to "已转诊")))
                                    stub.plans = rows(
                                        planRow(
                                            mapOf(
                                                "assignee" to "user-2",
                                                "metadata" to JsonObject()
                                                    .put("vital_sign_record_id", "vs-1")
                                                    .put("source", "体征异常告警"),
                                            ),
                                        ),
                                    )
                                    httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/vital-signs/vs-1/refer", "{}")
                                        .compose { (referStatus, referred) ->
                                            ctx.verify {
                                                assertEquals(200, referStatus)
                                                assertEquals("已转诊", referred.getJsonObject("record").getString("review_status"))
                                                assertEquals("慢病随访", referred.getJsonObject("followup_plan").getString("followup_type"))
                                                assertEquals("user-2", referred.getJsonObject("followup_plan").getString("assignee"))
                                            }
                                            // 5) 非法复核状态过滤 → 400
                                            httpRequest(vertx, port, HttpMethod.GET, "/healthcare/v1/vital-signs/abnormal?review_status=%E5%B7%B2%E5%A4%84%E7%90%86")
                                                .map { (badStatus, badBody) ->
                                                    ctx.verify {
                                                        assertEquals(400, badStatus)
                                                        assertTrue(badBody.getString("error")?.contains("invalid review_status") == true)
                                                        ctx.completeNow()
                                                    }
                                                }
                                        }
                                }
                        }
                }
        }
    }
}

// ——— mock 基础设施（顶层函数：与 VitalSignServiceTest 同款，文件内私有） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getBoolean(any<String>()) } answers { values[firstArg<String>()] as? Boolean }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
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
    every { rs.rowCount() } returns rows.size
    return rs
}

private fun updated(affected: Int): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = emptyList<Row>().iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns 0
    every { rs.rowCount() } returns affected
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
