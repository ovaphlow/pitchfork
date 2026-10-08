package com.ovaphlow.crate.dining

import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * 营养分析接口服务（054 A3，读时聚合，无迁移、无权限码）。
 *
 * 读 `dining.dining_meal_intake_records`（A1 的 V601）→ 交给 A2 的纯函数引擎
 * [NutritionAnalysis] 产出**逐项明细**与派生结论 → 返回分析 DTO（契约见 054 §5.7）。
 *
 * ## 总量口径（Lead 裁定，task-11）
 *
 * **摄入记录的落库汇总值是唯一权威口径**：头部 `energy_kcal/protein_g/fat_g/carb_g/sodium_mg`
 * 直接累加记录内 A1 已落库的对应列，**不在读时重算**；`items[]` 明细只作展示与核对。
 * 因此「明细之和 == 落库总量 == 返回总量」在菜品营养未被改动的前提下成立；
 * 若菜品营养在摄入登记**之后**被改写，明细会反映**当前**菜品营养，而总量仍是**登记时**的
 * 冻结快照——这是本裁定的预期行为（总量为权威），前端应以此为准。
 *
 * 规则：
 *  1. `patient_id` 必填；儿童必须存在且有 `birth_date`（月龄是年龄段参考值的前提）。
 *  2. `date` 缺省为业务时区（Asia/Shanghai）当天；`meal_time` 缺省聚合当天全部餐次，
 *     响应里以「全部」表示不分餐次。
 *  3. 每条摄入记录的 `intake_ratio` 在**入参折算前**乘到食物项份量上
 *     （线性等价，且支持同一天多条记录各自比例不同）。
 *  4. `items[]` 明细来源：`dining_dishes.nutrition`（菜品库自维护快照）按 `food_name` 精确匹配，
 *     未匹配计 0 且 `matched=false`；**不使用 A2 内置常量回落**（A2 的常量能力保留给纯引擎独立使用）。
 *  5. **结论与缺口项一律服务端派生**（基于落库总量）：本接口为 `GET`，不接受任何结论入参。
 *  6. 当天无任何摄入记录 → 返回零值 + `无摄入记录` 结论（**不 500**，见 054 §8.1）。
 *  7. 持久化按 `service-vertx-kotlin/AGENTS.md` 走原生参数化 SQL（`$1..$n` + `Tuple`），
 *     本文件不引入 jOOQ。
 */
class NutritionAnalysisService(private val pool: Pool) {

    companion object {
        /** 业务时区：与 healthcare 侧 `ChildHealthSupport`/`ChildScreeningService` 同口径。 */
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        private const val PATIENT_SQL =
            "SELECT id, name, birth_date FROM healthcare.patients WHERE id = \$1"

        private const val INTAKE_SQL =
            "SELECT items, intake_ratio, energy_kcal, protein_g, fat_g, carb_g, sodium_mg " +
                "FROM dining.dining_meal_intake_records " +
                "WHERE patient_id = \$1 AND intake_date = \$2 ORDER BY meal_time, created_at"

        private const val INTAKE_BY_MEAL_SQL =
            "SELECT items, intake_ratio, energy_kcal, protein_g, fat_g, carb_g, sodium_mg " +
                "FROM dining.dining_meal_intake_records " +
                "WHERE patient_id = \$1 AND intake_date = \$2 AND meal_time = \$3 ORDER BY created_at"

        private const val DISH_NUTRITION_SQL =
            "SELECT nutrition FROM dining.dining_dishes " +
                "WHERE name = \$1 AND nutrition IS NOT NULL ORDER BY created_at LIMIT 1"

        /** 业务时区当天。 */
        fun today(): LocalDate = LocalDate.now(businessZone)

        /**
         * 月龄（日历差，出生当天为 0）：与 052 `ChildHealthSupport.ageMonths` 同口径。
         * dining 不依赖 healthcare lib，故在此镜像实现（口径必须保持一致）。
         */
        fun ageMonths(birthDate: LocalDate, on: LocalDate): Int {
            if (on.isBefore(birthDate))
                throw IllegalArgumentException("date must not be before child birth_date")
            var months = (on.year - birthDate.year) * 12 + (on.monthValue - birthDate.monthValue)
            if (on.dayOfMonth < birthDate.dayOfMonth) months -= 1
            return months.coerceAtLeast(0)
        }
    }

    /**
     * 按儿童 + 日期（+ 可选餐次）读时聚合营养分析。
     * 参数非法抛 `IllegalArgumentException`（路由 400）；儿童不存在抛 [DiningNotFoundException]（路由 404）。
     */
    fun analyze(patientId: String?, date: String?, mealTime: String?): Future<JsonObject> {
        val patient = patientId?.trim()
        if (patient.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val analysisDate = date?.trim()?.takeIf { it.isNotEmpty() }?.let { parseDate(it) } ?: today()
        val meal = mealTime?.trim()?.takeIf { it.isNotEmpty() }
        if (meal != null && meal !in DiningConstants.MEAL_TIMES)
            throw IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}")

        return loadPatient(patient).compose { patientRow ->
            val birthDate = patientRow.getLocalDate("birth_date")
                ?: throw IllegalArgumentException("patient birth_date is required for nutrition analysis")
            val patientName = patientRow.getString("name") ?: ""
            val ageMonths = ageMonths(birthDate, analysisDate)

            loadIntakeData(patient, analysisDate, meal).compose { data ->
                loadDishCompositions(data.items.map { it.foodName }.distinct()).map { dishCompositions ->
                    // 1) 头部总量 = 摄入记录里已落库的服务端汇总（唯一权威口径，不读时重算）。
                    // 2) 逐项明细按 A1 同一规则：菜品库 `nutrition` 精确匹配、未匹配计 0 且 matched=false；
                    //    **不使用 A2 内置常量回落**，保证「明细之和 == 落库总量 == 返回总量」。
                    val stored = data.stored
                    val base = if (data.items.isEmpty()) {
                        NutritionAnalysis.emptyResult(ageMonths)
                    } else {
                        // 每条记录的 intake_ratio 已在 loadIntakeData 里折算到份量上，故此处比例为 1。
                        NutritionAnalysis.analyze(ageMonths, 1.0, data.items, dishCompositions)
                    }
                    val result = base.copy(
                        totals = stored,
                        gaps = NutritionAnalysis.gapsOf(stored, base.reference),
                        conclusion = NutritionAnalysis.conclusionOf(stored, base.reference),
                    )
                    toJson(patient, patientName, analysisDate, meal, result)
                }
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    private fun loadPatient(patientId: String): Future<Row> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("patient not found: $patientId"),
                )
            Future.succeededFuture(row)
        }

    /** 读摄入记录：逐项明细（按记录 `intake_ratio` 折算份量）+ 记录内已落库的汇总。 */
    private fun loadIntakeData(
        patientId: String,
        date: LocalDate,
        mealTime: String?,
    ): Future<IntakeData> {
        val sql = if (mealTime == null) INTAKE_SQL else INTAKE_BY_MEAL_SQL
        val tuple = if (mealTime == null) {
            Tuple.of(patientId, date)
        } else {
            Tuple.of(patientId, date, mealTime)
        }
        return pool.preparedQuery(sql).execute(tuple).map { rows ->
            val items = mutableListOf<NutritionAnalysis.ItemInput>()
            var energy = BigDecimal.ZERO
            var protein = BigDecimal.ZERO
            var fat = BigDecimal.ZERO
            var carb = BigDecimal.ZERO
            var sodium = BigDecimal.ZERO

            for (row in rows) {
                // 落库汇总：A1 已按「食物项 × 菜品营养 × 摄入比例」服务端汇总，本接口直接累加
                energy += row.getBigDecimal("energy_kcal") ?: BigDecimal.ZERO
                protein += row.getBigDecimal("protein_g") ?: BigDecimal.ZERO
                fat += row.getBigDecimal("fat_g") ?: BigDecimal.ZERO
                carb += row.getBigDecimal("carb_g") ?: BigDecimal.ZERO
                sodium += row.getBigDecimal("sodium_mg") ?: BigDecimal.ZERO

                val ratio = validRatio(row.getBigDecimal("intake_ratio")?.toDouble())
                val raw = row.getJsonArray("items") ?: JsonArray()
                for (index in 0 until raw.size()) {
                    val item = raw.getValue(index) as? JsonObject ?: continue
                    val foodName = item.getString("food_name")?.trim() ?: continue
                    if (foodName.isEmpty()) continue
                    val amount = item.getValue("amount_g")?.let { numberOrNull(it) } ?: continue
                    if (amount <= 0.0) continue
                    items.add(
                        NutritionAnalysis.ItemInput(
                            foodName = foodName,
                            amountG = amount * ratio,
                            note = item.getString("note"),
                        ),
                    )
                }
            }

            IntakeData(
                items = items,
                stored = NutritionAnalysis.Totals(
                    energyKcal = round2(energy),
                    proteinG = round2(protein),
                    fatG = round2(fat),
                    carbG = round2(carb),
                    sodiumMg = round2(sodium),
                ),
            )
        }
    }

    /** 按食物名取菜品库营养快照（唯一明细来源；未维护即视为未匹配，不使用常量回落）。 */
    private fun loadDishCompositions(
        foodNames: List<String>,
    ): Future<Map<String, NutritionReference.FoodComposition>> {
        var result: Future<Map<String, NutritionReference.FoodComposition>> =
            Future.succeededFuture(emptyMap())
        for (name in foodNames) {
            result = result.compose { acc ->
                pool.preparedQuery(DISH_NUTRITION_SQL).execute(Tuple.of(name)).map { rows ->
                    val nutrition = rows.iterator().asSequence().firstOrNull()?.getJsonObject("nutrition")
                        ?: return@map acc
                    val composition = toComposition(name, nutrition) ?: return@map acc
                    acc + (name to composition)
                }
            }
        }
        return result
    }

    // ========================================================================
    //  组装
    // ========================================================================

    private fun toJson(
        patientId: String,
        patientName: String,
        date: LocalDate,
        mealTime: String?,
        result: NutritionAnalysis.Result,
    ): JsonObject {
        val reference = result.reference
        return JsonObject()
            .put("patient_id", patientId)
            .put("patient_name", patientName)
            .put("intake_date", date.toString())
            .put("meal_time", mealTime ?: "全部")
            .put("age_months", result.ageMonths)
            .put("band", JsonObject().put("code", result.bandCode).put("label", result.bandLabel))
            .put("energy_kcal", result.totals.energyKcal)
            .put("protein_g", result.totals.proteinG)
            .put("fat_g", result.totals.fatG)
            .put("carb_g", result.totals.carbG)
            .put("sodium_mg", result.totals.sodiumMg)
            .put(
                "reference",
                JsonObject()
                    .put("energy_kcal", reference.energyKcal)
                    .put("protein_g", reference.proteinG)
                    .put("fat_g", reference.fatG)
                    .put("carb_g", reference.carbG)
                    .put("sodium_mg", reference.sodiumMg),
            )
            .put("conclusion", result.conclusion)
            .put("gaps", JsonArray(result.gaps.toList()))
            .put(
                "items",
                JsonArray(
                    result.items.map {
                        JsonObject()
                            .put("food_name", it.foodName)
                            .put("amount_g", it.amountG)
                            .put("energy_kcal", it.energyKcal)
                            .put("matched", it.matched)
                    },
                ),
            )
            .put("unmatched", JsonArray(result.unmatched.toList()))
            .put("reference_version", result.referenceVersion)
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    /** 读时聚合的中间结构：逐项明细 + 记录内已落库的权威总量。 */
    private data class IntakeData(
        val items: List<NutritionAnalysis.ItemInput>,
        val stored: NutritionAnalysis.Totals,
    )

    private fun parseDate(value: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("date must be a valid ISO date (yyyy-MM-dd)")
    }

    /** 落库 NUMERIC(10,2) 累加后统一两位小数 `HALF_UP`。 */
    private fun round2(value: BigDecimal): Double =
        value.setScale(2, RoundingMode.HALF_UP).toDouble()

    /** 摄入比例兜底到 (0,1]：DB 侧已校验，此处防御历史脏数据导致分析整体失败。 */
    private fun validRatio(value: Double?): Double =
        if (value != null && value.isFinite() && value > 0.0 && value <= 1.0) value else 1.0

    private fun numberOrNull(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    /** `dining_dishes.nutrition` JSONB → 引擎成分（基准缺省 100 g；基准非法视为无成分）。 */
    private fun toComposition(
        foodName: String,
        nutrition: JsonObject,
    ): NutritionReference.FoodComposition? {
        val basis = numberOrNull(nutrition.getValue("basis_g")) ?: 100.0
        if (!basis.isFinite() || basis <= 0.0) return null
        fun value(key: String): Double = numberOrNull(nutrition.getValue(key)) ?: 0.0
        return NutritionReference.FoodComposition(
            foodName = foodName,
            basisG = basis,
            energyKcal = value("energy_kcal"),
            proteinG = value("protein_g"),
            fatG = value("fat_g"),
            carbG = value("carb_g"),
            sodiumMg = value("sodium_mg"),
        )
    }
}
