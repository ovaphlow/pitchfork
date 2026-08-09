package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.nursing.tables.NursingIncidents.NURSING_INCIDENTS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingShiftHandoverItems.NURSING_SHIFT_HANDOVER_ITEMS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingShiftHandovers.NURSING_SHIFT_HANDOVERS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 长者照护时间线 — 只读聚合服务
 *
 * 将护理评估、计划、任务、执行和护理记录统一为时间线事件。
 * 所有写操作仍走其原始领域 API；本服务只做查询和标准化展示。
 */
class NursingTimelineService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val log = LoggerFactory.getLogger(NursingTimelineService::class.java)

    companion object {
        /** 事件模型列名 */
        private val STABLE_TYPES = setOf(
            "ASSESSMENT", "CARE_PLAN", "TASK", "TASK_EXECUTION", "NURSING_RECORD",
            "NURSING_INCIDENT", "SHIFT_HANDOVER",
        )
        private val businessZone = java.time.ZoneId.of("Asia/Shanghai")
    }

    /**
     * 查询时间线事件。
     *
     * @param periodId   护理服务周期 ID（必填）
     * @param encounterId 入住记录 ID（必填）
     * @param dateFrom   可选起始日期
     * @param dateTo     可选结束日期
     * @param eventType  可选事件类型（可逗号分隔多个）
     * @param limit      分页大小
     * @param offset     分页偏移
     */
    fun listTimeline(
        periodId: String?,
        encounterId: String?,
        dateFrom: String?,
        dateTo: String?,
        eventType: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        if (periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("period_id is required"))
        if (encounterId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("encounter_id is required"))

        // 过滤的事件类型集合
        val types = eventType?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim().uppercase() }
            ?.filter { it in STABLE_TYPES }
            ?: emptyList()

        return validateTimelineScope(periodId, encounterId).compose { (patientsMatch, _) ->
            if (!patientsMatch)
                return@compose Future.failedFuture(
                    IllegalArgumentException("period and encounter belong to different patients")
                )

            // 并行读取各来源
            val assessmentFuture = if (types.isEmpty() || "ASSESSMENT" in types)
                loadAssessments(periodId, dateFrom, dateTo) else Future.succeededFuture(emptyList())
            val planFuture = if (types.isEmpty() || "CARE_PLAN" in types)
                loadPlans(periodId, dateFrom, dateTo) else Future.succeededFuture(emptyList())
            val taskFuture = if (types.isEmpty() || "TASK" in types)
                loadTasks(periodId, dateFrom, dateTo) else Future.succeededFuture(emptyList())
            val executionFuture = if (types.isEmpty() || "TASK_EXECUTION" in types)
                loadExecutionsByPeriod(periodId, dateFrom, dateTo) else Future.succeededFuture(emptyList())
            val recordFuture = if (types.isEmpty() || "NURSING_RECORD" in types)
                loadNursingRecords(encounterId, periodId, dateFrom, dateTo) else Future.succeededFuture(emptyList())
            val incidentFuture = if (types.isEmpty() || "NURSING_INCIDENT" in types)
                loadIncidents(periodId, encounterId, dateFrom, dateTo) else Future.succeededFuture(emptyList())
            val handoverFuture = if (types.isEmpty() || "SHIFT_HANDOVER" in types)
                loadShiftHandovers(periodId, encounterId, dateFrom, dateTo) else Future.succeededFuture(emptyList())

            io.vertx.core.CompositeFuture.all(
                listOf(assessmentFuture, planFuture, taskFuture, executionFuture, recordFuture, incidentFuture, handoverFuture),
            )
                .compose { composite ->
                    val assessments = composite.resultAt<List<JsonObject>>(0)
                    val plans = composite.resultAt<List<JsonObject>>(1)
                    val tasks = composite.resultAt<List<JsonObject>>(2)
                    val executions = composite.resultAt<List<JsonObject>>(3)
                    val records = composite.resultAt<List<JsonObject>>(4)
                    val incidents = composite.resultAt<List<JsonObject>>(5)
                    val handovers = composite.resultAt<List<JsonObject>>(6)

                    // 合并所有事件并排序
                    val allEvents = (assessments + plans + tasks + executions + records + incidents + handovers)
                        .sortedByDescending { it.getString("occurred_at") ?: "" }

                    val total = allEvents.size.toLong()
                    val paged = allEvents.drop(offset).take(limit)

                    Future.succeededFuture(
                        JsonObject()
                            .put("records", JsonArray(paged))
                            .put("meta", JsonObject().put("total", total))
                    )
                }
        }
    }

    // ========================================================================
    //  Scope 验证
    // ========================================================================

    /** 验证 period 和 encounter 属于同一患者 */
    private fun validateTimelineScope(periodId: String, encounterId: String): Future<Pair<Boolean, String?>> {
        val pQuery = ctx.select(DSL.field("patient_id"))
            .from(DSL.table(DSL.name("nursing", "nursing_service_periods")))
            .where(DSL.field("id").eq(periodId))
        val eQuery = ctx.select(DSL.field("patient_id"))
            .from(DSL.table(DSL.name("healthcare", "encounters")))
            .where(DSL.field("id").eq(encounterId))

        return pool.preparedQuery(DatabaseConfig.sql(pQuery))
            .execute(DatabaseConfig.tuple(pQuery))
            .compose { pRows ->
                val pRow = pRows.iterator().asSequence().firstOrNull()
                if (pRow == null) return@compose Future.failedFuture(
                    NotFoundException("nursing service period not found: $periodId")
                )
                val ppId = pRow.getString("patient_id")

                pool.preparedQuery(DatabaseConfig.sql(eQuery))
                    .execute(DatabaseConfig.tuple(eQuery))
                    .map { eRows ->
                        val eRow = eRows.iterator().asSequence().firstOrNull()
                        if (eRow == null) throw NotFoundException("encounter not found: $encounterId")
                        val epId = eRow.getString("patient_id")
                        Pair(ppId == epId, ppId)
                    }
            }
    }

    // ========================================================================
    //  数据加载 — 各类型事件
    // ========================================================================

    private fun loadAssessments(periodId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(DSL.field("period_id").eq(periodId))
        dateFrom?.let { d -> try { conditions.add(DSL.field("assess_date").ge(LocalDate.parse(d))) } catch (_: Exception) {} }
        dateTo?.let { d -> try { conditions.add(DSL.field("assess_date").le(LocalDate.parse(d))) } catch (_: Exception) {} }

        val query = ctx.select(
            DSL.field("id"),
            DSL.field("assess_type"),
            DSL.field("assess_date"),
            DSL.field("assessor"),
            DSL.field("total_score"),
            DSL.field("result_level"),
            DSL.field("created_at")
        )
            .from(DSL.table(DSL.name("nursing", "nursing_assessments")))
            .where(conditions)
            .orderBy(DSL.field("assess_date").desc(), DSL.field("created_at").desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                rows.map { row -> mapAssessmentEvent(row) }
            }
    }

    private fun loadPlans(periodId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(DSL.field("period_id").eq(periodId))
        dateFrom?.let { d -> try { val ld = LocalDate.parse(d); conditions.add(DSL.field("created_at").ge(ld.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())) } catch (_: Exception) {} }
        dateTo?.let { d -> try { val ld = LocalDate.parse(d); conditions.add(DSL.field("created_at").le(ld.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())) } catch (_: Exception) {} }

        val query = ctx.select(
            DSL.field("id"),
            DSL.field("plan_name"),
            DSL.field("status"),
            DSL.field("created_by"),
            DSL.field("created_at")
        )
            .from(DSL.table(DSL.name("nursing", "nursing_plans")))
            .where(conditions)
            .orderBy(DSL.field("created_at").desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.map { row -> mapPlanEvent(row) } }
    }

    private fun loadTasks(periodId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(DSL.field("period_id").eq(periodId))
        dateFrom?.let { d -> try { val ld = LocalDate.parse(d); conditions.add(DSL.field("created_at").ge(ld.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())) } catch (_: Exception) {} }
        dateTo?.let { d -> try { val ld = LocalDate.parse(d); conditions.add(DSL.field("created_at").le(ld.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())) } catch (_: Exception) {} }

        val query = ctx.select(
            DSL.field("id"),
            DSL.field("description"),
            DSL.field("task_type"),
            DSL.field("frequency_name"),
            DSL.field("status"),
            DSL.field("created_at")
        )
            .from(DSL.table(DSL.name("nursing", "nursing_tasks")))
            .where(conditions)
            .orderBy(DSL.field("created_at").desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.map { row -> mapTaskEvent(row) } }
    }

    private fun loadExecutionsByPeriod(periodId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val dateConditions = mutableListOf<org.jooq.Condition>()

        // 通过任务关联周期，限定该周期内的执行记录
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(DSL.field("t.period_id").eq(periodId))

        dateFrom?.let { d ->
            try {
                val startOfDay = LocalDate.parse(d).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                conditions.add(DSL.field("e.planned_time").ge(startOfDay)
                    .or(DSL.field("e.actual_time").ge(startOfDay)))
            } catch (_: Exception) {}
        }
        dateTo?.let { d ->
            try {
                val endOfDay = LocalDate.parse(d).plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                conditions.add(DSL.field("e.planned_time").le(endOfDay)
                    .or(DSL.field("e.actual_time").le(endOfDay)))
            } catch (_: Exception) {}
        }

        val query = ctx.select(
            DSL.field("e.id"),
            DSL.field("e.task_id"),
            DSL.field("e.planned_time"),
            DSL.field("e.actual_time"),
            DSL.field("e.executor"),
            DSL.field("e.status"),
            DSL.field("e.note"),
            DSL.field("e.created_at"),
            DSL.field("t.description").`as`("task_description"),
            DSL.field("t.task_type").`as`("task_type")
        )
            .from(DSL.table(DSL.name("nursing", "nursing_task_executions")).`as`("e"))
            .join(DSL.table(DSL.name("nursing", "nursing_tasks")).`as`("t"))
            .on(DSL.field("e.task_id").eq(DSL.field("t.id")))
            .where(conditions)
            .orderBy(DSL.field("e.actual_time").desc().nullsLast(),
                     DSL.field("e.planned_time").desc().nullsLast(),
                     DSL.field("e.created_at").desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.map { row -> mapExecutionEvent(row) } }
    }

    private fun loadNursingRecords(encounterId: String, periodId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(DSL.field("record_type").eq("NURSING_RECORD"))
        conditions.add(DSL.field("encounter_id").eq(encounterId))
        // 只返回属于该周期的记录
        conditions.add(
            DSL.field("{0} ->> {1}", String::class.java,
                DSL.field("metadata", org.jooq.JSONB::class.java),
                DSL.`val`("period_id")
            ).eq(periodId)
        )

        dateFrom?.let { d -> try { conditions.add(DSL.field("record_date").ge(LocalDate.parse(d))) } catch (_: Exception) {} }
        dateTo?.let { d -> try { conditions.add(DSL.field("record_date").le(LocalDate.parse(d))) } catch (_: Exception) {} }

        val query = ctx.select(
            DSL.field("id"),
            DSL.field("title"),
            DSL.field("content"),
            DSL.field("physician"),
            DSL.field("record_date"),
            DSL.field("metadata"),
            DSL.field("created_at"),
            DSL.field("updated_at")
        )
            .from(DSL.table(DSL.name("healthcare", "medical_records")))
            .where(conditions)
            .orderBy(DSL.field("record_date").desc(), DSL.field("created_at").desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.map { row -> mapNursingRecordEvent(row) } }
    }

    // ========================================================================
    //  事件映射
    // ========================================================================

    private fun mapAssessmentEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val createdAt = row.getOffsetDateTime("created_at")
        val assessDate = row.getLocalDate("assess_date")
        val assessor = row.getString("assessor")
        val totalScore = row.getBigDecimal("total_score")
        val resultLevel = row.getString("result_level")

        val occurredAt = assessDate?.atStartOfDay()?.atOffset(java.time.ZoneOffset.UTC) ?: createdAt

        return JsonObject()
            .put("id", "assessment:$id")
            .put("event_type", "ASSESSMENT")
            .put("occurred_at", occurredAt.toString())
            .put("title", "${assessTypeLabel(row.getString("assess_type") ?: "")}评估")
            .put("summary", "总分：${totalScore ?: "-"} · 等级：${resultLevel ?: "-"}")
            .put("actor", assessor)
            .put("source", JsonObject().put("resource", "nursing_assessment").put("id", id))
            .put("metadata", JsonObject())
    }

    private fun mapPlanEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val createdAt = row.getOffsetDateTime("created_at") ?: OffsetDateTime.now()
        val planName = row.getString("plan_name") ?: ""
        val status = row.getString("status") ?: ""
        val createdBy = row.getString("created_by")

        return JsonObject()
            .put("id", "plan:$id")
            .put("event_type", "CARE_PLAN")
            .put("occurred_at", createdAt.toString())
            .put("title", planName)
            .put("summary", planStatusLabel(status))
            .put("actor", createdBy)
            .put("source", JsonObject().put("resource", "nursing_plan").put("id", id))
            .put("metadata", JsonObject())
    }

    private fun mapTaskEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val createdAt = row.getOffsetDateTime("created_at") ?: OffsetDateTime.now()
        val description = row.getString("description") ?: ""
        val taskType = row.getString("task_type") ?: ""
        val freqName = row.getString("frequency_name")
        val status = row.getString("status") ?: ""

        val frequencySummary = if (freqName != null) " · $freqName" else ""

        return JsonObject()
            .put("id", "task:$id")
            .put("event_type", "TASK")
            .put("occurred_at", createdAt.toString())
            .put("title", description)
            .put("summary", "${taskTypeLabel(taskType)}$frequencySummary · ${taskStatusLabel(status)}")
            .put("actor", null)
            .put("source", JsonObject().put("resource", "nursing_task").put("id", id))
            .put("metadata", JsonObject())
    }

    private fun mapExecutionEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val actualTime = row.getOffsetDateTime("actual_time")
        val plannedTime = row.getOffsetDateTime("planned_time")
        val createdAt = row.getOffsetDateTime("created_at") ?: OffsetDateTime.now()
        val executor = row.getString("executor")
        val status = row.getString("status") ?: ""
        val note = row.getString("note")
        val taskDescription = row.getString("task_description") ?: ""
        val taskId = row.getString("task_id") ?: ""

        val occurredAt = actualTime ?: plannedTime ?: createdAt

        val summaryBuilder = StringBuilder(executionStatusLabel(status))
        if (note != null && note.isNotBlank()) summaryBuilder.append(" · $note")

        return JsonObject()
            .put("id", "execution:$id")
            .put("event_type", "TASK_EXECUTION")
            .put("occurred_at", occurredAt.toString())
            .put("title", taskDescription)
            .put("summary", summaryBuilder.toString())
            .put("actor", executor)
            .put("status", status)
            .put("source", JsonObject().put("resource", "nursing_task_execution").put("id", id))
            .put("metadata", JsonObject()
                .put("task_id", taskId)
                .put("task_note", note))
    }

    private fun mapNursingRecordEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val title = row.getString("title") ?: ""
        val content = row.getString("content")
        val physician = row.getString("physician") ?: ""
        val recordDate = row.getLocalDate("record_date")
        val createdAt = row.getOffsetDateTime("created_at")
        val rowMeta = row.getValue("metadata") as? JsonObject ?: JsonObject()

        val recordKind = rowMeta.getString("record_kind")
        val recordTimeStr = rowMeta.getString("record_time")
        val correctsRecordId = rowMeta.getString("corrects_record_id")

        val occurredAt = try { OffsetDateTime.parse(recordTimeStr) } catch (_: Exception) {
            recordDate?.atStartOfDay()?.atOffset(java.time.ZoneOffset.UTC) ?: createdAt ?: OffsetDateTime.now()
        }

        val summaryParts = mutableListOf<String>()
        if (recordKind == "CORRECTION") summaryParts.add("已更正")
        if (correctsRecordId != null) summaryParts.add("更正记录")
        summaryParts.add(content?.take(80)?.replace("\n", " ") ?: "")
        if (summaryParts.first().isBlank()) summaryParts.removeAt(0)

        return JsonObject()
            .put("id", "record:$id")
            .put("event_type", "NURSING_RECORD")
            .put("occurred_at", occurredAt.toString())
            .put("title", title)
            .put("summary", summaryParts.joinToString(" · ").take(200))
            .put("actor", physician)
            .put("source", JsonObject().put("resource", "medical_record").put("id", id))
            .put("metadata", rowMeta)
    }

    // ========================================================================
    //  异常事件与班次交接时间线来源（017 计划）
    //  批量读取后内存映射；读取绝不产生新事件、交班、任务、执行、库存或护理记录。
    // ========================================================================

    private fun loadIncidents(periodId: String, encounterId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(NURSING_INCIDENTS.PERIOD_ID.eq(periodId))
        conditions.add(NURSING_INCIDENTS.ENCOUNTER_ID.eq(encounterId))
        dateFrom?.let { d ->
            try {
                conditions.add(NURSING_INCIDENTS.OCCURRED_AT.ge(LocalDate.parse(d).atStartOfDay(businessZone).toOffsetDateTime()))
            } catch (_: Exception) {}
        }
        dateTo?.let { d ->
            try {
                conditions.add(NURSING_INCIDENTS.OCCURRED_AT.lt(LocalDate.parse(d).plusDays(1).atStartOfDay(businessZone).toOffsetDateTime()))
            } catch (_: Exception) {}
        }

        val query = ctx.select(
            NURSING_INCIDENTS.ID,
            NURSING_INCIDENTS.INCIDENT_TYPE,
            NURSING_INCIDENTS.SEVERITY,
            NURSING_INCIDENTS.STATUS,
            NURSING_INCIDENTS.OCCURRED_AT,
            NURSING_INCIDENTS.DESCRIPTION,
            NURSING_INCIDENTS.REPORTER,
        )
            .from(NURSING_INCIDENTS)
            .where(conditions)
            .orderBy(NURSING_INCIDENTS.OCCURRED_AT.desc(), NURSING_INCIDENTS.CREATED_AT.desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.map { row -> mapIncidentEvent(row) } }
    }

    private fun loadShiftHandovers(periodId: String, encounterId: String, dateFrom: String?, dateTo: String?): Future<List<JsonObject>> {
        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(NURSING_SHIFT_HANDOVER_ITEMS.ENCOUNTER_ID.eq(encounterId))
        conditions.add(NURSING_SHIFT_HANDOVER_ITEMS.PERIOD_ID.eq(periodId))
        dateFrom?.let { d ->
            try {
                conditions.add(NURSING_SHIFT_HANDOVERS.HANDED_OVER_AT.ge(LocalDate.parse(d).atStartOfDay(businessZone).toOffsetDateTime()))
            } catch (_: Exception) {}
        }
        dateTo?.let { d ->
            try {
                conditions.add(NURSING_SHIFT_HANDOVERS.HANDED_OVER_AT.lt(LocalDate.parse(d).plusDays(1).atStartOfDay(businessZone).toOffsetDateTime()))
            } catch (_: Exception) {}
        }

        val query = ctx.select(
            NURSING_SHIFT_HANDOVERS.ID,
            NURSING_SHIFT_HANDOVERS.CARE_UNIT,
            NURSING_SHIFT_HANDOVERS.BUSINESS_DATE,
            NURSING_SHIFT_HANDOVERS.SHIFT,
            NURSING_SHIFT_HANDOVERS.HANDOVER_BY,
            NURSING_SHIFT_HANDOVERS.HANDED_OVER_AT,
            NURSING_SHIFT_HANDOVERS.RECEIVED_BY,
            NURSING_SHIFT_HANDOVERS.STATUS,
        )
            .from(NURSING_SHIFT_HANDOVERS)
            .join(NURSING_SHIFT_HANDOVER_ITEMS)
            .on(NURSING_SHIFT_HANDOVERS.ID.eq(NURSING_SHIFT_HANDOVER_ITEMS.HANDOVER_ID))
            .where(conditions)
            .orderBy(NURSING_SHIFT_HANDOVERS.HANDED_OVER_AT.desc(), NURSING_SHIFT_HANDOVERS.CREATED_AT.desc())

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows -> rows.map { row -> mapHandoverEvent(row) } }
    }

    private fun mapIncidentEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val occurredAt = row.getOffsetDateTime("occurred_at") ?: OffsetDateTime.now()
        val incidentType = row.getString("incident_type") ?: ""
        val severity = row.getString("severity") ?: ""
        val status = row.getString("status") ?: ""
        val description = row.getString("description") ?: ""

        return JsonObject()
            .put("id", "incident:$id")
            .put("event_type", "NURSING_INCIDENT")
            .put("occurred_at", occurredAt.toString())
            .put("title", "异常事件：$incidentType")
            .put("summary", "严重程度：$severity · 状态：$status · ${description.take(80).replace("\n", " ")}".take(200))
            .put("actor", row.getString("reporter"))
            .put("status", status)
            .put("source", JsonObject().put("resource", "nursing_incident").put("id", id))
            .put("metadata", JsonObject()
                .put("incident_type", incidentType)
                .put("severity", severity)
                .put("status", status))
    }

    private fun mapHandoverEvent(row: Row): JsonObject {
        val id = row.getString("id") ?: ""
        val handedOverAt = row.getOffsetDateTime("handed_over_at") ?: OffsetDateTime.now()
        val careUnit = row.getString("care_unit") ?: ""
        val businessDate = row.getLocalDate("business_date")?.toString() ?: ""
        val shift = row.getString("shift") ?: ""
        val status = row.getString("status") ?: ""

        return JsonObject()
            .put("id", "handover:$id")
            .put("event_type", "SHIFT_HANDOVER")
            .put("occurred_at", handedOverAt.toString())
            .put("title", "$businessDate $shift 班次交接")
            .put("summary", "照护单元：$careUnit · ${if (status == "已接班") "已接班" else "待接班"}".take(200))
            .put("actor", row.getString("handover_by"))
            .put("status", status)
            .put("source", JsonObject().put("resource", "nursing_shift_handover").put("id", id))
            .put("metadata", JsonObject()
                .put("care_unit", careUnit)
                .put("business_date", businessDate)
                .put("shift", shift)
                .put("status", status)
                .put("received_by", row.getString("received_by")))
    }

    // ========================================================================
    //  标签映射（与 NursingPage.tsx 保持一致）
    // ========================================================================

    private fun assessTypeLabel(value: String): String = when (value) {
        "ADMISSION" -> "入院"; "FALL_RISK" -> "跌倒风险"; "PRESSURE_SORE" -> "压疮"
        "PAIN" -> "疼痛"; "BARTHEL" -> "Barthel 指数"; "NUTRITION" -> "营养"
        "HOME_ENVIRONMENT" -> "居家环境"; else -> value
    }

    private fun taskTypeLabel(value: String): String = when (value) {
        "NURSING" -> "护理操作"; "REHABILITATION" -> "康复训练"
        "LIVING_CARE" -> "生活照料"; "HEALTH_EDUCATION" -> "健康教育"
        else -> "其他"
    }

    private fun executionStatusLabel(value: String): String = when (value) {
        "PENDING" -> "待执行"; "IN_PROGRESS" -> "执行中"; "COMPLETED" -> "已完成"
        "SKIPPED" -> "已跳过"; "CANCELLED" -> "已取消"
        else -> value
    }

    private fun taskStatusLabel(value: String): String = when (value) {
        "ACTIVE" -> "执行中"; "COMPLETED" -> "已完成"; "CANCELLED" -> "已取消"
        else -> value
    }

    private fun planStatusLabel(value: String): String = when (value) {
        "ACTIVE" -> "执行中"; "COMPLETED" -> "已完成"; "DISCONTINUED" -> "已终止"
        else -> value
    }
}
