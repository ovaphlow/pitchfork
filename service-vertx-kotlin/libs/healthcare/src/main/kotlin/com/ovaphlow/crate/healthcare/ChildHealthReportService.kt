package com.ovaphlow.crate.healthcare

import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 儿童健康报告装配服务（053 C1，**只读聚合**）。
 *
 * 职责：把一名儿童分散在儿保各表的片段聚合成一份可打印/可导出的报告 DTO：
 * 档案（含 B1 诊断）/ 体检 / 成长 / 接种排程状态 / 筛查（含 A3 结论）/ 转诊 / 指导（含 B3 `kind`）/ 过敏。
 *
 * 业务规则（服务端强制）：
 *  1. 报告只读、不上门禁（046 D7：读保持登录即可）；不取 `userId`、不写库。
 *  2. **隐私（053 §3 Dh / §8.6）**：`patient` 段只含 `id / name / gender / birth_date /
 *     age_months / person_type`，**不返回身份证号**（`id_card` / `id_number` 等一律不查、不拼）。
 *  3. `age_months` 由 `birth_date` 与业务今天（Asia/Shanghai）用 [ChildHealth.ageMonths] 派生；
 *     `birth_date` 为空（或异常地晚于今天）时不派生。
 *  4. 各段查询均有界（`LIMIT` [SECTION_LIMIT]，避免无界聚合）。
 *  5. 接种段的逐条排程状态与汇总复用 052 的**纯函数** [ChildVaccinationService.VACCINATION_SCHEDULE] /
 *     [ChildVaccinationService.statusOf] / [ChildVaccinationService.dueDate]，口径与 052 一致。
 *  6. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 *
 * ## 过敏段的依赖裁定
 * C1 的依赖集是 **A2、B1–B3、052（不含 D1）**，因此本服务「过敏」段读**遗留**
 * `healthcare.patients.allergies` JSONB（035 形状 `[{"allergen","reaction"}]`），
 * **不**读尚未存在的 `patient_allergies`（D1 才建）。D1 落地后如需改读规范化表，属后续任务。
 */
class ChildHealthReportService(private val pool: Pool) {

    companion object {
        /** 业务时区（与 052 儿保链一致）。 */
        private val businessZone = ZoneId.of("Asia/Shanghai")

        /** 各段查询上界（有界聚合，避免无界全表）。 */
        const val SECTION_LIMIT = 200

        /** 患者基础信息（不查身份证号；PHI 最小化，053 §3 Dh）。 */
        private const val PATIENT_SQL =
            "SELECT id, name, gender, birth_date, person_type " +
                "FROM healthcare.patients WHERE id = \$1"

        /** 过敏史：遗留 JSONB（035 形状），D1 落地前不改读规范化表。 */
        private const val ALLERGIES_SQL =
            "SELECT allergies FROM healthcare.patients WHERE id = \$1"

        private const val PROFILE_SQL =
            "SELECT id, patient_id, guardian_name, guardian_relationship, guardian_phone, " +
                "birth_weight_g, birth_height_mm, delivery_mode, feeding_method, vaccination_summary, " +
                "vaccination_records, remark, metadata, created_at, updated_at " +
                "FROM healthcare.child_health_profiles WHERE patient_id = \$1 LIMIT 1"

        private const val CHECKUPS_SQL =
            "SELECT id, patient_id, checkup_date, checkup_type, age_months, items, conclusion, " +
                "diagnosis, diagnosis_code, status, reviewed_by, reviewed_at, guidance, " +
                "next_checkup_date, operator, remark, metadata, created_at, updated_at " +
                "FROM healthcare.child_health_checkups WHERE patient_id = \$1 " +
                "ORDER BY checkup_date DESC, created_at DESC LIMIT \$2"

        /** 成长测量：软删除（deleted_at）记录作废，默认排除（与 052 查询口径一致）。 */
        private const val GROWTH_SQL =
            "SELECT id, patient_id, measured_at, age_days, height_cm, weight_kg, " +
                "head_circumference_cm, percentile_snapshot, operator, note, metadata, deleted_at, " +
                "created_at, updated_at FROM healthcare.child_growth_measurements " +
                "WHERE patient_id = \$1 AND deleted_at IS NULL " +
                "ORDER BY measured_at DESC, created_at DESC LIMIT \$2"

        private const val VACCINATION_SQL =
            "SELECT id, patient_id, vaccine_code, vaccine_name, dose_no, administered_at, facility, " +
                "batch_no, status, operator, note, metadata, created_at, updated_at " +
                "FROM healthcare.child_vaccination_records WHERE patient_id = \$1 " +
                "ORDER BY created_at ASC LIMIT \$2"

        private const val SCREENINGS_SQL =
            "SELECT id, patient_id, scale_code, scale_version, age_months, administered_at, " +
                "item_scores, domain_scores, total_score, classification, conclusion, checkup_id, " +
                "operator, metadata, created_at, updated_at " +
                "FROM healthcare.developmental_screenings WHERE patient_id = \$1 " +
                "ORDER BY administered_at DESC, created_at DESC LIMIT \$2"

        private const val REFERRALS_SQL =
            "SELECT id, patient_id, source_type, source_id, problem_summary, referral_type, target_org, " +
                "target_dept, urgency, status, referred_at, outcome, operator, metadata, " +
                "created_at, updated_at FROM healthcare.child_referrals WHERE patient_id = \$1 " +
                "ORDER BY created_at DESC, id DESC LIMIT \$2"

        private const val GUIDANCE_SQL =
            "SELECT id, patient_id, template_code, age_band, topic, kind, content, delivered_at, channel, " +
                "operator, metadata, created_at, updated_at FROM healthcare.child_health_guidance_records " +
                "WHERE patient_id = \$1 ORDER BY delivered_at DESC, created_at DESC LIMIT \$2"

        /**
         * 纯装配函数（**无 DB**，供单测）：把各段片段拼成冻结的报告形状，并计算 `meta.counts`。
         *
         * 输出：
         * `{patient, profile, checkups, growth, vaccinations, screenings, referrals, guidance,
         * allergies, meta:{generated_at, counts:{checkups,growth,screenings,referrals,guidance,allergies}}}`。
         *
         * 各数组段直接透传入参（调用方保证非 null，空输入传空 [JsonArray]，输出仍为 `[]` 而非 null）；
         * `profile` 为 null 时输出 `"profile": null`。`counts` 取各数组 `size()`。
         */
        fun assembleReport(
            patient: JsonObject,
            profile: JsonObject?,
            checkups: JsonArray,
            growth: JsonArray,
            vaccinations: JsonObject,
            screenings: JsonArray,
            referrals: JsonArray,
            guidance: JsonArray,
            allergies: JsonArray,
        ): JsonObject {
            val counts = JsonObject()
                .put("checkups", checkups.size())
                .put("growth", growth.size())
                .put("screenings", screenings.size())
                .put("referrals", referrals.size())
                .put("guidance", guidance.size())
                .put("allergies", allergies.size())

            val report = JsonObject().put("patient", patient)
            if (profile == null) {
                report.putNull("profile")
            } else {
                report.put("profile", profile)
            }
            report
                .put("checkups", checkups)
                .put("growth", growth)
                .put("vaccinations", vaccinations)
                .put("screenings", screenings)
                .put("referrals", referrals)
                .put("guidance", guidance)
                .put("allergies", allergies)
                .put(
                    "meta",
                    JsonObject()
                        .put("generated_at", OffsetDateTime.now().toString())
                        .put("counts", counts),
                )
            return report
        }

        /**
         * 患者基础段（**不含身份证号**，053 §3 Dh）。
         *
         * `age_months` 仅在 `birth_date` 非空且不晚于业务今天时派生；否则输出 null。
         */
        internal fun patientJson(row: Row): JsonObject {
            val birthDate = row.getLocalDate("birth_date")
            val obj = JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("gender", row.getString("gender"))
                .put("birth_date", birthDate?.toString())
                .put("person_type", row.getString("person_type"))
            if (birthDate == null || birthDate.isAfter(ChildHealth.today())) {
                obj.putNull("age_months")
            } else {
                obj.put("age_months", ChildHealth.ageMonths(birthDate, ChildHealth.today()))
            }
            return obj
        }

        internal fun profileJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("guardian_name", row.getString("guardian_name"))
            .put("guardian_relationship", row.getString("guardian_relationship"))
            .put("guardian_phone", row.getString("guardian_phone"))
            .put("birth_weight_g", row.getInteger("birth_weight_g"))
            .put("birth_height_mm", row.getInteger("birth_height_mm"))
            .put("delivery_mode", row.getString("delivery_mode"))
            .put("feeding_method", row.getString("feeding_method"))
            .put("vaccination_summary", row.getString("vaccination_summary"))
            .put("vaccination_records", row.getValue("vaccination_records"))
            .put("remark", row.getString("remark"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        internal fun checkupJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("checkup_date", row.getLocalDate("checkup_date")?.toString())
            .put("checkup_type", row.getString("checkup_type"))
            .put("age_months", row.getInteger("age_months"))
            .put("items", row.getValue("items"))
            .put("conclusion", row.getString("conclusion"))
            .put("diagnosis", row.getString("diagnosis"))
            .put("diagnosis_code", row.getString("diagnosis_code"))
            .put("status", row.getString("status"))
            .put("reviewed_by", row.getString("reviewed_by"))
            .put("reviewed_at", row.getOffsetDateTime("reviewed_at")?.toString())
            .put("guidance", row.getString("guidance"))
            .put("next_checkup_date", row.getLocalDate("next_checkup_date")?.toString())
            .put("operator", row.getString("operator"))
            .put("remark", row.getString("remark"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        internal fun growthJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("measured_at", row.getOffsetDateTime("measured_at")?.toString())
            .put("age_days", row.getInteger("age_days"))
            .put("height_cm", row.getBigDecimal("height_cm"))
            .put("weight_kg", row.getBigDecimal("weight_kg"))
            .put("head_circumference_cm", row.getBigDecimal("head_circumference_cm"))
            .put("percentile_snapshot", row.getValue("percentile_snapshot"))
            .put("operator", row.getString("operator"))
            .put("note", row.getString("note"))
            .put("metadata", row.getValue("metadata"))
            .put("deleted_at", row.getOffsetDateTime("deleted_at")?.toString())
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        internal fun vaccinationRecordJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
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

        internal fun screeningJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("scale_code", row.getString("scale_code"))
            .put("scale_version", row.getString("scale_version"))
            .put("age_months", row.getInteger("age_months"))
            .put("administered_at", row.getOffsetDateTime("administered_at")?.toString())
            .put("item_scores", row.getValue("item_scores"))
            .put("domain_scores", row.getValue("domain_scores"))
            .put("total_score", row.getInteger("total_score"))
            .put("classification", row.getString("classification"))
            .put("conclusion", row.getString("conclusion"))
            .put("checkup_id", row.getString("checkup_id"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        internal fun referralJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("source_type", row.getString("source_type"))
            .put("source_id", row.getString("source_id"))
            .put("problem_summary", row.getString("problem_summary"))
            .put("referral_type", row.getString("referral_type"))
            .put("target_org", row.getString("target_org"))
            .put("target_dept", row.getString("target_dept"))
            .put("urgency", row.getString("urgency"))
            .put("status", row.getString("status"))
            .put("referred_at", row.getOffsetDateTime("referred_at")?.toString())
            .put("outcome", row.getString("outcome"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        internal fun guidanceJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("template_code", row.getString("template_code"))
            .put("age_band", row.getString("age_band"))
            .put("topic", row.getString("topic"))
            .put("kind", row.getString("kind"))
            .put("content", row.getString("content"))
            .put("delivered_at", row.getOffsetDateTime("delivered_at")?.toString())
            .put("channel", row.getString("channel"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 过敏段：遗留 JSONB（035 形状）；缺省 / 非数组一律回落 `[]`。 */
        internal fun allergiesFrom(rows: Iterable<Row>): JsonArray {
            val row = rows.iterator().asSequence().firstOrNull() ?: return JsonArray()
            return row.getValue("allergies") as? JsonArray ?: JsonArray()
        }

        /**
         * 接种段：`records`（原始记录）+ `schedule`（排程逐条状态）+ `summary`（按状态汇总）。
         *
         * 排程状态复用 052 纯函数（[ChildVaccinationService.statusOf]），口径与 052 一致；
         * `birth_date` 为空（无法计算到龄日）时 `schedule` 为空、汇总全 0。
         */
        internal fun buildVaccinations(rows: Iterable<Row>, birthDate: LocalDate?): JsonObject {
            val rowList = rows.toList()
            val records = JsonArray(rowList.map(::vaccinationRecordJson))
            val schedule = JsonArray()
            val counts = linkedMapOf(
                "已接种" to 0, "待接种" to 0, "临期" to 0,
                "逾期" to 0, "暂缓" to 0, "拒种" to 0,
            )
            if (birthDate != null) {
                val today = ChildHealth.today()
                val byKey = rowList.associateBy {
                    "${it.getString("vaccine_code")}:${it.getInteger("dose_no")}"
                }
                ChildVaccinationService.VACCINATION_SCHEDULE.forEach { entry ->
                    val record = byKey["${entry.code}:${entry.doseNo}"]
                    val status = ChildVaccinationService.statusOf(
                        entry,
                        record?.getString("status"),
                        birthDate,
                        today,
                    )
                    counts[status] = (counts[status] ?: 0) + 1
                    schedule.add(
                        JsonObject()
                            .put("vaccine_code", entry.code)
                            .put("vaccine_name", entry.name)
                            .put("dose_no", entry.doseNo)
                            .put("recommended_month", entry.recommendedMonth)
                            .put("due_date", ChildVaccinationService.dueDate(birthDate, entry).toString())
                            .put("status", status),
                    )
                }
            }
            val summary = JsonObject().put("total", schedule.size())
            counts.forEach { (status, count) -> summary.put(status, count) }
            return JsonObject()
                .put("records", records)
                .put("schedule", schedule)
                .put("summary", summary)
        }
    }

    /**
     * 聚合一名儿童的健康报告（只读）。
     *
     * 在**单个事务**内按 `patient_id` 原生查询各段并调用 [assembleReport] 拼装。
     * 档案不存在 → [HealthcareNotFoundException]（路由 404）。
     *
     * 各段查询均有界（`LIMIT` [SECTION_LIMIT]）：体检 `checkup_date DESC`、成长 `measured_at DESC`、
     * 筛查 `administered_at DESC`、转诊 `created_at DESC`、指导 `delivered_at DESC`；
     * 接种 `created_at ASC`（便于按时间线阅读）。
     */
    fun getReport(patientId: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection -> assembleVia(connection, patientId) }

    /** 事务内装配：加载档案 → 并行聚合各段 → [assembleReport] 拼装。 */
    private fun assembleVia(client: SqlClient, patientId: String): Future<JsonObject> =
        client.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { patientRows ->
            val patientRow = patientRows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("patient not found: $patientId"),
                )
            val birthDate = patientRow.getLocalDate("birth_date")
            val patient = patientJson(patientRow)

            val futures = listOf(
                client.preparedQuery(PROFILE_SQL).execute(Tuple.of(patientId)),
                client.preparedQuery(CHECKUPS_SQL).execute(Tuple.of(patientId, SECTION_LIMIT)),
                client.preparedQuery(GROWTH_SQL).execute(Tuple.of(patientId, SECTION_LIMIT)),
                client.preparedQuery(VACCINATION_SQL).execute(Tuple.of(patientId, SECTION_LIMIT)),
                client.preparedQuery(SCREENINGS_SQL).execute(Tuple.of(patientId, SECTION_LIMIT)),
                client.preparedQuery(REFERRALS_SQL).execute(Tuple.of(patientId, SECTION_LIMIT)),
                client.preparedQuery(GUIDANCE_SQL).execute(Tuple.of(patientId, SECTION_LIMIT)),
                client.preparedQuery(ALLERGIES_SQL).execute(Tuple.of(patientId)),
            )
            Future.all(futures).map { composite ->
                val profileRows = composite.resultAt<RowSet<Row>>(0)
                val checkupRows = composite.resultAt<RowSet<Row>>(1)
                val growthRows = composite.resultAt<RowSet<Row>>(2)
                val vaccinationRows = composite.resultAt<RowSet<Row>>(3)
                val screeningRows = composite.resultAt<RowSet<Row>>(4)
                val referralRows = composite.resultAt<RowSet<Row>>(5)
                val guidanceRows = composite.resultAt<RowSet<Row>>(6)
                val allergyRows = composite.resultAt<RowSet<Row>>(7)

                val profile = profileRows.iterator().asSequence().firstOrNull()?.let(::profileJson)
                assembleReport(
                    patient = patient,
                    profile = profile,
                    checkups = JsonArray(checkupRows.map(::checkupJson)),
                    growth = JsonArray(growthRows.map(::growthJson)),
                    vaccinations = buildVaccinations(vaccinationRows, birthDate),
                    screenings = JsonArray(screeningRows.map(::screeningJson)),
                    referrals = JsonArray(referralRows.map(::referralJson)),
                    guidance = JsonArray(guidanceRows.map(::guidanceJson)),
                    allergies = allergiesFrom(allergyRows),
                )
            }
        }
}
