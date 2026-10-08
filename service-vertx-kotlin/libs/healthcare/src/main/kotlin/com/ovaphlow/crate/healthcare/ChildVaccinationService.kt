package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * 儿保预防接种服务（052 P3，V528）。
 *
 * 业务规则（服务端强制）：
 *  1. 只服务 `person_type=儿童` 且 `birth_date` 非空的患者（`childPatientRow`）。
 *  2. 只接受**国家免疫规划疫苗**（052 裁定 3）：`vaccine_code` 必须命中
 *     [VACCINATION_SCHEDULE]；`dose_no` 不得超过该疫苗的剂次数。疫苗名以排程为准
 *     （客户端提交的同名值只做一致性校验，不落库覆盖）。
 *  3. `(patient_id, vaccine_code, dose_no)` 唯一：重复补录返回 409（唯一索引兜底并发）。
 *  4. 记录状态只描述**已发生的事实**：已接种 / 暂缓 / 拒种；「待接种 / 临期 / 逾期」是
 *     排程与记录比对后的**读时结论**（[statusOf]），不落库、无后台定时任务（052 D4）。
 *  5. 「生成提醒」只对**逾期**项写家长通报台账并调用 Nexus message；临期项只在响应里
 *     列给护士，不生成异常台账（临期不是异常，不制造虚假告警）。
 *  6. `operator` 一律取认证主体（042 范式）。
 */
class ChildVaccinationService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 排程条目：疫苗编码 × 剂次 × 推荐月龄。 */
        data class ScheduleEntry(
            val code: String,
            val name: String,
            val doseNo: Int,
            val recommendedMonth: Int,
        )

        /**
         * 国家免疫规划疫苗排程（0–5 岁 MVP，052 裁定 2/3）。
         *
         * 推荐月龄取《国家免疫规划疫苗儿童免疫程序及说明》的常规月龄；6 岁及以上的剂次
         * （白破、A群C群流脑第 2 剂）不列入本表——0–5 岁 MVP 下它们只会显示成无意义的
         * 「待接种」（052 裁定 2：超出 5 岁列入后续）。
         */
        val VACCINATION_SCHEDULE: List<ScheduleEntry> = listOf(
            ScheduleEntry("HEPB", "乙肝疫苗", 1, 0),
            ScheduleEntry("BCG", "卡介苗", 1, 0),
            ScheduleEntry("HEPB", "乙肝疫苗", 2, 1),
            ScheduleEntry("POLIO", "脊灰疫苗", 1, 2),
            ScheduleEntry("DTAP", "百白破疫苗", 1, 3),
            ScheduleEntry("POLIO", "脊灰疫苗", 2, 3),
            ScheduleEntry("DTAP", "百白破疫苗", 2, 4),
            ScheduleEntry("POLIO", "脊灰疫苗", 3, 4),
            ScheduleEntry("DTAP", "百白破疫苗", 3, 5),
            ScheduleEntry("HEPB", "乙肝疫苗", 3, 6),
            ScheduleEntry("MENINGOCOCCAL_A", "A群流脑多糖疫苗", 1, 6),
            ScheduleEntry("MMR", "麻腮风疫苗", 1, 8),
            ScheduleEntry("JE", "乙脑减毒活疫苗", 1, 8),
            ScheduleEntry("MENINGOCOCCAL_A", "A群流脑多糖疫苗", 2, 9),
            ScheduleEntry("HEPA", "甲肝减毒活疫苗", 1, 18),
            ScheduleEntry("DTAP", "百白破疫苗", 4, 18),
            ScheduleEntry("MMR", "麻腮风疫苗", 2, 18),
            ScheduleEntry("JE", "乙脑减毒活疫苗", 2, 24),
            ScheduleEntry("MENINGOCOCCAL_AC", "A群C群流脑多糖疫苗", 1, 36),
            ScheduleEntry("POLIO", "脊灰疫苗", 4, 48),
        )

        /** 记录状态中文白名单（事实状态）。 */
        val recordStatuses = setOf("已接种", "暂缓", "拒种")

        /**
         * 054 F2 内容表覆盖层（进程内缓存）：由 `BaseDataService` 在路由装配时与每次内容写入后刷新。
         *
         * **空 = 库为空或读失败 → 完全等价于常量排程 [VACCINATION_SCHEDULE]**（052 已交付的接种
         * 状态与提醒判定不变）。
         */
        @Volatile
        private var contentSchedule: List<ScheduleEntry> = emptyList()

        /** 生效排程：覆盖层非空时用库内容，否则回落常量排程。 */
        fun effectiveSchedule(): List<ScheduleEntry> =
            if (contentSchedule.isEmpty()) VACCINATION_SCHEDULE else contentSchedule

        /** 054 F2：应用内容表加载结果；传空列表即回落常量排程（库空 / 读失败 / 测试重置）。 */
        internal fun applyContentSchedule(content: List<ScheduleEntry>) {
            contentSchedule = content
        }

        /** 读时结论中文枚举（排程 × 记录比对结果）。 */
        val derivedStatuses = setOf("已接种", "待接种", "临期", "逾期", "暂缓", "拒种")

        /** 到龄前多少天内算「临期」；到龄后多少天内仍算「待接种」（宽限期），超过即「逾期」。 */
        const val DUE_SOON_DAYS = 30L
        const val GRACE_DAYS = 30L

        /** 创建写白名单：id/operator/created_at/updated_at 由服务端管控。 */
        private val createKeys = setOf(
            "patient_id", "vaccine_code", "vaccine_name", "dose_no",
            "administered_at", "facility", "batch_no", "status", "note", "metadata",
        )

        /** 该疫苗的最大剂次（读库优先、常量回落）。 */
        fun maxDose(code: String): Int? =
            effectiveSchedule().filter { it.code == code }.maxOfOrNull { it.doseNo }

        /** 推荐接种日 = 出生日期 + 推荐月龄（日历月推进）。 */
        fun dueDate(birthDate: LocalDate, entry: ScheduleEntry): LocalDate =
            birthDate.plusMonths(entry.recommendedMonth.toLong())

        /**
         * 读时状态判定（纯函数，逐条单测锁定）。
         *
         * 记录状态优先（已接种/暂缓/拒种直接返回）；无记录时按排程与「今天」比对：
         *  - 未到龄：距到龄 ≤ [DUE_SOON_DAYS] 天 → 临期，否则 待接种；
         *  - 已到龄但在宽限期（[GRACE_DAYS] 天）内 → 待接种；
         *  - 超过宽限期 → 逾期。
         */
        fun statusOf(
            entry: ScheduleEntry,
            recordStatus: String?,
            birthDate: LocalDate,
            today: LocalDate,
        ): String {
            if (recordStatus != null && recordStatus in recordStatuses) return recordStatus
            val due = dueDate(birthDate, entry)
            val daysUntil = ChronoUnit.DAYS.between(today, due)
            return when {
                daysUntil > 0 -> if (daysUntil <= DUE_SOON_DAYS) "临期" else "待接种"
                -daysUntil <= GRACE_DAYS -> "待接种"
                else -> "逾期"
            }
        }

        internal fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("vaccine_code", row.getString("vaccine_code"))
                .put("vaccine_name", row.getString("vaccine_name"))
                .put("dose_no", row.getInteger("dose_no"))
                .put("administered_at", row.getLocalDate("administered_at")?.toString())
                .put("facility", row.getString("facility"))
                .put("batch_no", row.getString("batch_no"))
                .put("status", row.getString("status"))
                .put("operator", row.getString("operator"))
                .put("note", row.getString("note"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    private val table = DSL.table(DSL.name("healthcare", "child_vaccination_records"))

    private val fId = DSL.field(DSL.name("child_vaccination_records", "id"), String::class.java)
    private val fPatientId = DSL.field(DSL.name("child_vaccination_records", "patient_id"), String::class.java)
    private val fVaccineCode = DSL.field(DSL.name("child_vaccination_records", "vaccine_code"), String::class.java)
    private val fVaccineName = DSL.field(DSL.name("child_vaccination_records", "vaccine_name"), String::class.java)
    private val fDoseNo = DSL.field(DSL.name("child_vaccination_records", "dose_no"), Int::class.javaObjectType)
    private val fAdministeredAt = DSL.field(DSL.name("child_vaccination_records", "administered_at"), LocalDate::class.java)
    private val fFacility = DSL.field(DSL.name("child_vaccination_records", "facility"), String::class.java)
    private val fBatchNo = DSL.field(DSL.name("child_vaccination_records", "batch_no"), String::class.java)
    private val fStatus = DSL.field(DSL.name("child_vaccination_records", "status"), String::class.java)
    private val fOperator = DSL.field(DSL.name("child_vaccination_records", "operator"), String::class.java)
    private val fNote = DSL.field(DSL.name("child_vaccination_records", "note"), String::class.java)
    private val fMetadata = DSL.field(DSL.name("child_vaccination_records", "metadata"), JSONB::class.java)
    private val fCreatedAt = DSL.field(DSL.name("child_vaccination_records", "created_at"), OffsetDateTime::class.java)
    private val fUpdatedAt = DSL.field(DSL.name("child_vaccination_records", "updated_at"), OffsetDateTime::class.java)

    private val selectFields: List<Field<*>> = listOf(
        fId, fPatientId, fVaccineCode, fVaccineName, fDoseNo, fAdministeredAt,
        fFacility, fBatchNo, fStatus, fOperator, fNote, fMetadata, fCreatedAt, fUpdatedAt,
    )

    private val wId = DSL.field(DSL.name("id"), String::class.java)
    private val wPatientId = DSL.field(DSL.name("patient_id"), String::class.java)
    private val wVaccineCode = DSL.field(DSL.name("vaccine_code"), String::class.java)
    private val wVaccineName = DSL.field(DSL.name("vaccine_name"), String::class.java)
    private val wDoseNo = DSL.field(DSL.name("dose_no"), Int::class.javaObjectType)
    private val wAdministeredAt = DSL.field(DSL.name("administered_at"), LocalDate::class.java)
    private val wFacility = DSL.field(DSL.name("facility"), String::class.java)
    private val wBatchNo = DSL.field(DSL.name("batch_no"), String::class.java)
    private val wStatus = DSL.field(DSL.name("status"), String::class.java)
    private val wOperator = DSL.field(DSL.name("operator"), String::class.java)
    private val wNote = DSL.field(DSL.name("note"), String::class.java)
    private val wMetadata = DSL.field(DSL.name("metadata"), JSONB::class.java)
    private val wCreatedAt = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
    private val wUpdatedAt = DSL.field(DSL.name("updated_at"), OffsetDateTime::class.java)

    // ========================================================================
    //  创建
    // ========================================================================

    fun createRecord(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed: Parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        return pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, parsed.patientId).compose { patient ->
                if (parsed.administeredAt != null && parsed.administeredAt.isBefore(patient.getLocalDate("birth_date"))) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("administered_at must not be before child birth_date"),
                    )
                }
                val insert = ctx.insertInto(table)
                    .set(wId, id)
                    .set(wPatientId, parsed.patientId)
                    .set(wVaccineCode, parsed.entry.code)
                    .set(wVaccineName, parsed.entry.name)
                    .set(wDoseNo, parsed.entry.doseNo)
                    .set(wStatus, parsed.status)
                    .set(wOperator, operator)
                    .set(wCreatedAt, now)
                    .set(wUpdatedAt, now)
                    .apply {
                        parsed.administeredAt?.let { set(wAdministeredAt, it) }
                        parsed.facility?.let { set(wFacility, it) }
                        parsed.batchNo?.let { set(wBatchNo, it) }
                        parsed.note?.let { set(wNote, it) }
                        parsed.metadata?.let { set(wMetadata, JSONB.valueOf(it.encode())) }
                    }
                executeChildQuery(connection, insert).compose { getRecordVia(connection, id) }
            }
        }.recover { error ->
            val message = error.message?.lowercase() ?: ""
            if (message.contains("uq_child_vaccination_records")) {
                Future.failedFuture(
                    ConflictException(
                        "vaccination record already exists: ${parsed.entry.name} 第 ${parsed.entry.doseNo} 剂",
                    ),
                )
            } else {
                Future.failedFuture(error)
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    fun listRecords(patientId: String?, limit: Int, offset: Int): Future<JsonObject> {
        val conditions = try {
            val patient = patientId?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("patient_id is required")
            mutableListOf<Condition>(fPatientId.eq(patient))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(table).where(conditions)
        val dataQuery = baseQuery()
            .where(conditions)
            .orderBy(fAdministeredAt.desc().nullsLast(), fCreatedAt.desc())
            .limit(limit)
            .offset(offset)

        return executeChildQuery(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            executeChildQuery(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getRecord(id: String): Future<JsonObject> = getRecordVia(pool, id)

    private fun getRecordVia(client: SqlClient, id: String): Future<JsonObject> =
        executeChildQuery(client, baseQuery().where(fId.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(recordJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child vaccination record not found: $id"))
        }

    /**
     * 逐条排程状态 + 逾期汇总。
     *
     * 返回 `{patient_id, birth_date, age_months, records: [...], meta: {total, administered,
     * pending, due_soon, overdue, deferred, refused}}`；`records` 为**排程条目**（不是接种记录），
     * 每条的 `status` 是 [statusOf] 的读时结论，`record_id` 指向已发生的记录（无记录为 null）。
     */
    fun getVaccinationStatus(patientId: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, patientId).compose { patient ->
                val birthDate = patient.getLocalDate("birth_date")
                val today = ChildHealth.today()
                val ageMonths = ChildHealth.ageMonths(birthDate, today)
                executeChildQuery(
                    connection,
                    baseQuery().where(fPatientId.eq(patientId)).orderBy(fCreatedAt.asc()),
                ).map { rows ->
                    // 同一 (code, dose) 至多一条（唯一索引）；按 code+dose 建索引取记录。
                    val byKey = rows.associateBy { "${it.getString("vaccine_code")}:${it.getInteger("dose_no")}" }
                    val records = JsonArray()
                    val counts = linkedMapOf(
                        "已接种" to 0, "待接种" to 0, "临期" to 0,
                        "逾期" to 0, "暂缓" to 0, "拒种" to 0,
                    )
                    effectiveSchedule().forEach { entry ->
                        val record = byKey["${entry.code}:${entry.doseNo}"]
                        val status = statusOf(entry, record?.getString("status"), birthDate, today)
                        counts[status] = (counts[status] ?: 0) + 1
                        records.add(
                            JsonObject()
                                .put("vaccine_code", entry.code)
                                .put("vaccine_name", entry.name)
                                .put("dose_no", entry.doseNo)
                                .put("recommended_month", entry.recommendedMonth)
                                .put("due_date", dueDate(birthDate, entry).toString())
                                .put("status", status)
                                .put("record_id", record?.getString("id"))
                                .put("administered_at", record?.getLocalDate("administered_at")?.toString())
                                .put("facility", record?.getString("facility"))
                                .put("batch_no", record?.getString("batch_no"))
                                .put("note", record?.getString("note")),
                        )
                    }
                    JsonObject()
                        .put("patient_id", patientId)
                        .put("patient_name", patient.getString("name"))
                        .put("birth_date", birthDate.toString())
                        .put("age_months", ageMonths)
                        .put("records", records)
                        .put(
                            "meta",
                            JsonObject()
                                .put("total", records.size())
                                .put("administered", counts["已接种"] ?: 0)
                                .put("pending", counts["待接种"] ?: 0)
                                .put("due_soon", counts["临期"] ?: 0)
                                .put("overdue", counts["逾期"] ?: 0)
                                .put("deferred", counts["暂缓"] ?: 0)
                                .put("refused", counts["拒种"] ?: 0),
                        )
                }
            }
        }

    /**
     * 「生成提醒」的候选项：逾期项（`overdue`）与临期项（`due_soon`）。
     *
     * 由路由层把结果交给 [ChildAlertService] 写台账（逾期项）与调用 Nexus message；
     * 临期项只在响应里回给护士，不生成异常台账。
     */
    fun reminderItems(status: JsonObject): Pair<JsonArray, JsonArray> {
        val records = status.getJsonArray("records") ?: JsonArray()
        val overdue = JsonArray()
        val dueSoon = JsonArray()
        records.forEach { entry ->
            val item = entry as? JsonObject ?: return@forEach
            when (item.getString("status")) {
                "逾期" -> overdue.add(item)
                "临期" -> dueSoon.add(item)
            }
        }
        return overdue to dueSoon
    }

    // ========================================================================
    //  解析与校验
    // ========================================================================

    private data class Parsed(
        val patientId: String,
        val entry: ScheduleEntry,
        val administeredAt: LocalDate?,
        val facility: String?,
        val batchNo: String?,
        val status: String,
        val note: String?,
        val metadata: JsonObject?,
    )

    private fun parseCreate(body: JsonObject): Parsed {
        rejectForbiddenKeys(body, createKeys, "child vaccination record")
        val patientId = body.getString("patient_id")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("patient_id is required")
        val code = body.getString("vaccine_code")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("vaccine_code is required")
        val maxDose = maxDose(code)
            ?: throw IllegalArgumentException(
                "unsupported vaccine_code (国家免疫规划疫苗 only): $code",
            )
        val doseNo = if (body.containsKey("dose_no") && body.getValue("dose_no") != null) {
            (body.getValue("dose_no") as? Number)?.toInt()
                ?: throw IllegalArgumentException("dose_no must be a number")
        } else {
            1
        }
        if (doseNo < 1 || doseNo > maxDose) {
            throw IllegalArgumentException("dose_no must be between 1 and $maxDose for $code")
        }
        val entry = effectiveSchedule().first { it.code == code && it.doseNo == doseNo }
        body.getString("vaccine_name")?.trim()?.takeIf(String::isNotBlank)?.let { provided ->
            if (provided != entry.name) {
                throw IllegalArgumentException("vaccine_name does not match the schedule for $code: ${entry.name}")
            }
        }
        val status = body.getString("status")?.trim()?.takeIf(String::isNotBlank)
            ?.let { it.takeIf { value -> value in recordStatuses }
                ?: throw IllegalArgumentException("invalid status, must be one of: $recordStatuses") }
            ?: "已接种"
        val administeredAt = body.getString("administered_at")?.trim()?.takeIf(String::isNotBlank)?.let {
            localDate(it, "administered_at").also { date ->
                if (date.isAfter(ChildHealth.today())) {
                    throw IllegalArgumentException("administered_at must not be in the future")
                }
            }
        }
        if (status == "已接种" && administeredAt == null) {
            throw IllegalArgumentException("administered_at is required when status is 已接种")
        }
        return Parsed(
            patientId = patientId,
            entry = entry,
            administeredAt = if (status == "已接种") administeredAt else null,
            facility = optionalText(body, "facility", 200),
            batchNo = optionalText(body, "batch_no", 100),
            status = status,
            note = optionalText(body, "note", 1000),
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun baseQuery() = ctx.select(
        selectFields + listOf(DSL.field(DSL.name("patients", "name"), String::class.java).`as`("patient_name")),
    )
        .from(table)
        .join(DSL.table(DSL.name("healthcare", "patients")))
        .on(fPatientId.eq(DSL.field(DSL.name("patients", "id"), String::class.java)))

    private fun optionalText(body: JsonObject, key: String, max: Int): String? =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > max) throw IllegalArgumentException("$key must not exceed $max characters")
        }

    private fun localDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 date")
        }

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }
}
