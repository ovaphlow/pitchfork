package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 营养周报（054 A5，消费 A4 的 V602 `dining_nutrition_weekly_reports`，无新迁移）。
 *
 * 规则（054 §3 Dg / §11.2 Q10）：
 *  1. **显式生成 + 快照落库**：不接后台定时任务；同一儿童同一周重复生成 = **覆盖更新**
 *     （唯一索引 `uq_dining_nutrition_weekly_reports_week` + `ON CONFLICT`），不重复插入。
 *  2. 周界口径复用 [DiningConstants.weekStartOf]（周一为周首）；传入非周一的日期会被
 *     归一到该周周一，`week_end = week_start + 6`。
 *  3. 逐日聚合复用 [NutritionAnalysisService]（读时分析口径与 A3 完全一致，
 *     含「菜品库营养优先、内置常量回落」）；**空数据周**（当周无任何摄入记录）
 *     仍生成可判定快照：`intake_days=0`、均值 0、结论「无摄入记录」，**不 500**。
 *  4. 均值口径：**按有摄入记录的天数**取平均（不是按 7 天），并在 `daily` 里保留逐日明细，
 *     避免「没记录的天」把均值稀释成不可解释的数字。
 *  5. 结论与缺口项由 [NutritionAnalysis] 服务端派生（客户端提交的结论一律忽略）。
 *  6. 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class NutritionWeeklyReportService(private val pool: Pool) {

    companion object {
        private const val AMOUNT_SCALE = 2

        private const val PATIENT_SQL =
            "SELECT id, birth_date FROM healthcare.patients WHERE id = \$1"

        private const val DATES_SQL =
            "SELECT DISTINCT intake_date FROM dining.dining_meal_intake_records " +
                "WHERE patient_id = \$1 AND intake_date >= \$2 AND intake_date <= \$3 " +
                "ORDER BY intake_date"

        private const val UPSERT_SQL =
            "INSERT INTO dining.dining_nutrition_weekly_reports " +
                "(id, patient_id, week_start, week_end, intake_days, energy_avg_kcal, protein_avg_g, " +
                "fat_avg_g, carb_avg_g, sodium_avg_mg, conclusion, gaps, daily, reference_version, " +
                "generated_by, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14, " +
                "\$15, \$16, \$17, \$18) " +
                "ON CONFLICT (patient_id, week_start) DO UPDATE SET " +
                "week_end = EXCLUDED.week_end, intake_days = EXCLUDED.intake_days, " +
                "energy_avg_kcal = EXCLUDED.energy_avg_kcal, protein_avg_g = EXCLUDED.protein_avg_g, " +
                "fat_avg_g = EXCLUDED.fat_avg_g, carb_avg_g = EXCLUDED.carb_avg_g, " +
                "sodium_avg_mg = EXCLUDED.sodium_avg_mg, conclusion = EXCLUDED.conclusion, " +
                "gaps = EXCLUDED.gaps, daily = EXCLUDED.daily, " +
                "reference_version = EXCLUDED.reference_version, generated_by = EXCLUDED.generated_by, " +
                "metadata = EXCLUDED.metadata, updated_at = EXCLUDED.updated_at " +
                "RETURNING id"

        private const val SELECT_COLUMNS =
            "r.id, r.patient_id, p.name AS patient_name, r.week_start, r.week_end, r.intake_days, " +
                "r.energy_avg_kcal, r.protein_avg_g, r.fat_avg_g, r.carb_avg_g, r.sodium_avg_mg, " +
                "r.conclusion, r.gaps, r.daily, r.reference_version, r.generated_by, r.metadata, " +
                "r.created_at, r.updated_at"

        private const val SELECT_FROM =
            " FROM dining.dining_nutrition_weekly_reports r " +
                "JOIN healthcare.patients p ON p.id = r.patient_id "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE r.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        /** 行 → 周报 JSON（字段缺失不抛）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("week_start", row.getLocalDate("week_start")?.toString())
            .put("week_end", row.getLocalDate("week_end")?.toString())
            .put("intake_days", row.getInteger("intake_days") ?: 0)
            .put("energy_avg_kcal", row.getBigDecimal("energy_avg_kcal")?.toDouble() ?: 0.0)
            .put("protein_avg_g", row.getBigDecimal("protein_avg_g")?.toDouble() ?: 0.0)
            .put("fat_avg_g", row.getBigDecimal("fat_avg_g")?.toDouble() ?: 0.0)
            .put("carb_avg_g", row.getBigDecimal("carb_avg_g")?.toDouble() ?: 0.0)
            .put("sodium_avg_mg", row.getBigDecimal("sodium_avg_mg")?.toDouble() ?: 0.0)
            .put("conclusion", row.getString("conclusion"))
            .put("gaps", row.getJsonArray("gaps") ?: JsonArray())
            .put("daily", row.getJsonArray("daily") ?: JsonArray())
            .put("reference_version", row.getString("reference_version"))
            .put("generated_by", row.getString("generated_by"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 两位小数 `HALF_UP`。 */
        private fun round2(value: Double): Double =
            BigDecimal(value.toString()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * 生成（幂等覆盖）某儿童某周的营养周报。
     * 参数非法抛 `IllegalArgumentException`（400）；儿童不存在抛 [DiningNotFoundException]（404）。
     */
    fun generate(body: JsonObject, operator: String): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val requested = parseOptionalDate(body.getString("week_start"), "week_start")
        val weekStart = DiningConstants.weekStartOf(requested ?: NutritionAnalysisService.today())
        val weekEnd = weekStart.plusDays(6)

        return loadPatient(patientId).compose { patientRow ->
            val birthDate = patientRow.getLocalDate("birth_date")
                ?: throw IllegalArgumentException("patient birth_date is required for nutrition weekly report")
            val ageMonths = NutritionAnalysisService.ageMonths(birthDate, weekStart)
            val reference = NutritionReference.referenceOf(ageMonths)

            loadIntakeDates(patientId, weekStart, weekEnd).compose { dates ->
                analyzeDays(patientId, dates).compose { daily ->
                    val averages = average(daily)
                    val totals = NutritionAnalysis.Totals(
                        energyKcal = averages.energyKcal,
                        proteinG = averages.proteinG,
                        fatG = averages.fatG,
                        carbG = averages.carbG,
                        sodiumMg = averages.sodiumMg,
                    )
                    val snapshot = Snapshot(
                        patientId = patientId,
                        weekStart = weekStart,
                        weekEnd = weekEnd,
                        intakeDays = dates.size,
                        averages = averages,
                        conclusion = NutritionAnalysis.conclusionOf(totals, reference),
                        gaps = NutritionAnalysis.gapsOf(totals, reference),
                        daily = daily,
                        referenceVersion = NutritionReference.VERSION,
                    )
                    upsert(snapshot, operator)
                }
            }
        }
    }

    /** 按 id 取单条；不存在 → [DiningNotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("nutrition weekly report not found: $id"),
                )
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`patient_id` / `week_start`。 */
    fun list(
        patientId: String? = null,
        weekStart: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("r.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        weekStart?.trim()?.takeIf { it.isNotEmpty() }?.let {
            // 过滤按周界归一，传周三也能查到该周周报
            conditions.add("r.week_start = \$${values.size + 1}")
            values.add(DiningConstants.weekStartOf(parseDate(it, "week_start")))
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY r.week_start DESC, r.created_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  内部
    // ========================================================================

    /** 周报快照（生成入参 → 落库）。 */
    private data class Snapshot(
        val patientId: String,
        val weekStart: LocalDate,
        val weekEnd: LocalDate,
        val intakeDays: Int,
        val averages: Averages,
        val conclusion: String,
        val gaps: List<String>,
        val daily: List<JsonObject>,
        val referenceVersion: String,
    )

    private data class Averages(
        val energyKcal: Double,
        val proteinG: Double,
        val fatG: Double,
        val carbG: Double,
        val sodiumMg: Double,
    )

    private fun loadPatient(patientId: String): Future<Row> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("patient not found: $patientId"),
                )
            Future.succeededFuture(row)
        }

    /** 当周有摄入记录的所有日期（升序）。 */
    private fun loadIntakeDates(patientId: String, weekStart: LocalDate, weekEnd: LocalDate): Future<List<LocalDate>> =
        pool.preparedQuery(DATES_SQL).execute(Tuple.of(patientId, weekStart, weekEnd)).map { rows ->
            rows.mapNotNull { it.getLocalDate("intake_date") }
        }

    /** 逐日复用 A3 的读时分析（同一口径），空数据周返回空列表。 */
    private fun analyzeDays(patientId: String, dates: List<LocalDate>): Future<List<JsonObject>> {
        val service = NutritionAnalysisService(pool)
        val entries = mutableListOf<JsonObject>()
        var result: Future<Unit> = Future.succeededFuture(Unit)
        for (date in dates) {
            result = result.compose {
                service.analyze(patientId, date.toString(), null).map { analysis ->
                    entries.add(dailyEntry(date, analysis))
                    Unit
                }
            }
        }
        return result.map { entries.toList() }
    }

    /** 单日快照（逐日明细只保留口径需要的字段）。 */
    private fun dailyEntry(date: LocalDate, analysis: JsonObject): JsonObject = JsonObject()
        .put("intake_date", date.toString())
        .put("energy_kcal", analysis.getDouble("energy_kcal") ?: 0.0)
        .put("protein_g", analysis.getDouble("protein_g") ?: 0.0)
        .put("fat_g", analysis.getDouble("fat_g") ?: 0.0)
        .put("carb_g", analysis.getDouble("carb_g") ?: 0.0)
        .put("sodium_mg", analysis.getDouble("sodium_mg") ?: 0.0)
        .put("conclusion", analysis.getString("conclusion"))

    /** 均值口径：按**有摄入记录的天数**平均；无记录 → 全 0。 */
    private fun average(daily: List<JsonObject>): Averages {
        if (daily.isEmpty()) return Averages(0.0, 0.0, 0.0, 0.0, 0.0)
        fun sum(key: String): Double = daily.sumOf { it.getDouble(key) ?: 0.0 }
        val days = daily.size.toDouble()
        return Averages(
            energyKcal = round2(sum("energy_kcal") / days),
            proteinG = round2(sum("protein_g") / days),
            fatG = round2(sum("fat_g") / days),
            carbG = round2(sum("carb_g") / days),
            sodiumMg = round2(sum("sodium_mg") / days),
        )
    }

    private fun upsert(snapshot: Snapshot, operator: String): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(snapshot.patientId)
        tuple.addLocalDate(snapshot.weekStart)
        tuple.addLocalDate(snapshot.weekEnd)
        tuple.addInteger(snapshot.intakeDays)
        tuple.addBigDecimal(BigDecimal(snapshot.averages.energyKcal.toString()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
        tuple.addBigDecimal(BigDecimal(snapshot.averages.proteinG.toString()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
        tuple.addBigDecimal(BigDecimal(snapshot.averages.fatG.toString()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
        tuple.addBigDecimal(BigDecimal(snapshot.averages.carbG.toString()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
        tuple.addBigDecimal(BigDecimal(snapshot.averages.sodiumMg.toString()).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP))
        tuple.addString(snapshot.conclusion)
        tuple.addValue(JsonArray(snapshot.gaps.toList()))
        tuple.addValue(JsonArray(snapshot.daily.toList()))
        tuple.addString(snapshot.referenceVersion)
        tuple.addString(operator)
        tuple.addValue(null as JsonObject?)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return pool.preparedQuery(UPSERT_SQL).execute(tuple).compose { rows ->
            val savedId = rows.iterator().asSequence().firstOrNull()?.getString("id") ?: id
            get(savedId)
        }
    }

    private fun parseDate(value: String, field: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
    }

    private fun parseOptionalDate(value: String?, field: String): LocalDate? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseDate(text, field)
    }

    private fun tupleOf(values: List<Any?>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
