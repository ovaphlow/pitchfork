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
 * 每餐膳食摄入记录（054 A1，迁移 V601）。
 *
 * 业务规则（服务端强制）：
 *  1. 同一儿童（`patient_id`）+ 同一日期（`intake_date`）+ 同一餐次（`meal_time`）唯一：
 *     重复提交为**覆盖更新**（幂等），不新增行。
 *  2. `items` 为 `[{food_name, amount_g, note?}]`，`amount_g` 必须为正数；
 *     `intake_ratio` 为 0–1 的摄入比例（缺省 1）。
 *  3. `energy_kcal/protein_g/fat_g/carb_g/sodium_mg` 一律**服务端汇总**：
 *     按 `food_name` 匹配 `dining.dining_dishes.name` 的 `nutrition` 快照
 *     （每 `basis_g` 克，缺省 100），乘份量与摄入比例后两位小数 `HALF_UP` 落库；
 *     未匹配到菜品营养的食物项按 0 计入，不报错。客户端提交的同名字段一律忽略。
 *  4. `operator` 取认证主体（042 口径），客户端提交的 `operator` 丢弃。
 *  5. 持久化按 `service-vertx-kotlin/AGENTS.md` 走**原生参数化 SQL**（`$1..$n` + `Tuple`），
 *     本文件不引入 jOOQ。
 */
class MealIntakeService(private val pool: Pool) {

    companion object {
        /** 营养素金额与比例落库的小数位（两位，`HALF_UP`）。 */
        private const val AMOUNT_SCALE = 2

        /** 摄入比例落库小数位（NUMERIC(5,4)）。 */
        private const val RATIO_SCALE = 4

        /** 中间除法的小数位，避免非终止小数抛 ArithmeticException。 */
        private const val DIVISION_SCALE = 6

        /** 菜品营养缺省基准克数。 */
        private val DEFAULT_BASIS_G = BigDecimal("100")

        private const val PATIENT_SQL =
            "SELECT id, name FROM healthcare.patients WHERE id = \$1"

        private const val ROSTER_ITEM_SQL =
            "SELECT id FROM dining.dining_roster_items WHERE id = \$1"

        /** 同名菜品取最早一条带营养的（菜品库无 name 唯一约束，取确定顺序）。 */
        private const val DISH_NUTRITION_SQL =
            "SELECT nutrition FROM dining.dining_dishes " +
                "WHERE name = \$1 AND nutrition IS NOT NULL ORDER BY created_at LIMIT 1"

        private const val UPSERT_SQL =
            "INSERT INTO dining.dining_meal_intake_records " +
                "(id, patient_id, roster_item_id, intake_date, meal_time, items, intake_ratio, " +
                "energy_kcal, protein_g, fat_g, carb_g, sodium_mg, operator, remark, metadata, " +
                "created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14, \$15, \$16, \$17) " +
                "ON CONFLICT (patient_id, intake_date, meal_time) DO UPDATE SET " +
                "roster_item_id = EXCLUDED.roster_item_id, items = EXCLUDED.items, " +
                "intake_ratio = EXCLUDED.intake_ratio, energy_kcal = EXCLUDED.energy_kcal, " +
                "protein_g = EXCLUDED.protein_g, fat_g = EXCLUDED.fat_g, carb_g = EXCLUDED.carb_g, " +
                "sodium_mg = EXCLUDED.sodium_mg, operator = EXCLUDED.operator, remark = EXCLUDED.remark, " +
                "metadata = EXCLUDED.metadata, updated_at = EXCLUDED.updated_at " +
                "RETURNING id"

        private const val SELECT_COLUMNS =
            "x.id, x.patient_id, p.name AS patient_name, x.roster_item_id, x.intake_date, " +
                "x.meal_time, x.items, x.intake_ratio, x.energy_kcal, x.protein_g, x.fat_g, " +
                "x.carb_g, x.sodium_mg, x.operator, x.remark, x.metadata, x.created_at, x.updated_at"

        private const val SELECT_FROM =
            " FROM dining.dining_meal_intake_records x " +
                "JOIN healthcare.patients p ON p.id = x.patient_id "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE x.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        /** 行 → 单条摄入记录 JSON（字段缺失不抛）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("roster_item_id", row.getString("roster_item_id"))
            .put("intake_date", row.getLocalDate("intake_date")?.toString())
            .put("meal_time", row.getString("meal_time"))
            .put("items", row.getJsonArray("items") ?: JsonArray())
            .put("intake_ratio", row.getBigDecimal("intake_ratio")?.toDouble() ?: 1.0)
            .put("energy_kcal", row.getBigDecimal("energy_kcal")?.toDouble() ?: 0.0)
            .put("protein_g", row.getBigDecimal("protein_g")?.toDouble() ?: 0.0)
            .put("fat_g", row.getBigDecimal("fat_g")?.toDouble() ?: 0.0)
            .put("carb_g", row.getBigDecimal("carb_g")?.toDouble() ?: 0.0)
            .put("sodium_mg", row.getBigDecimal("sodium_mg")?.toDouble() ?: 0.0)
            .put("operator", row.getString("operator"))
            .put("remark", row.getString("remark"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 汇总口径：按 `food_name` 匹配菜品营养，乘份量与摄入比例。 */
        private data class NutritionTotals(
            val energyKcal: BigDecimal,
            val proteinG: BigDecimal,
            val fatG: BigDecimal,
            val carbG: BigDecimal,
            val sodiumMg: BigDecimal,
        )
    }

    /**
     * 登记摄入（幂等）：同一儿童同一餐次重复提交为覆盖更新。
     * 校验异常同步抛出 `IllegalArgumentException`（路由映射 400），
     * 儿童 / 名单条目不存在抛 [DiningNotFoundException]（路由映射 404）。
     */
    fun register(body: JsonObject, operator: String): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty())
            throw IllegalArgumentException("patient_id is required")

        val intakeDate = parseDate(body.getString("intake_date"))
        val mealTime = body.getString("meal_time")?.trim()
        if (mealTime.isNullOrEmpty() || mealTime !in DiningConstants.MEAL_TIMES)
            throw IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}")

        val items = normalizeItems(body.getValue("items"))
        val ratio = parseRatio(body.getValue("intake_ratio"))
        val rosterItemId = body.getString("roster_item_id")?.trim()?.takeIf { it.isNotEmpty() }
        val remark = body.getString("remark")?.trim()?.takeIf { it.isNotEmpty() }
        val metadata = parseMetadata(body.getValue("metadata"))

        return loadPatientName(patientId).compose { _ ->
            verifyRosterItem(rosterItemId).compose {
                loadNutrition(foodNames(items)).compose { nutritionByName ->
                    val totals = summarize(items, nutritionByName, ratio)
                    val id = Ulid.generate()
                    val now = OffsetDateTime.now()

                    val tuple = Tuple.tuple()
                    tuple.addString(id)
                    tuple.addString(patientId)
                    tuple.addValue(rosterItemId)
                    tuple.addLocalDate(intakeDate)
                    tuple.addString(mealTime)
                    tuple.addValue(items)
                    tuple.addBigDecimal(ratio)
                    tuple.addBigDecimal(totals.energyKcal)
                    tuple.addBigDecimal(totals.proteinG)
                    tuple.addBigDecimal(totals.fatG)
                    tuple.addBigDecimal(totals.carbG)
                    tuple.addBigDecimal(totals.sodiumMg)
                    tuple.addString(operator)
                    tuple.addValue(remark)
                    tuple.addValue(metadata)
                    tuple.addOffsetDateTime(now)
                    tuple.addOffsetDateTime(now)

                    pool.preparedQuery(UPSERT_SQL).execute(tuple).compose { rows ->
                        val savedId = rows.iterator().asSequence().firstOrNull()?.getString("id") ?: id
                        get(savedId)
                    }
                }
            }
        }
    }

    /** 按 id 取单条；不存在 → [DiningNotFoundException]（路由 404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("meal intake record not found: $id"),
                )
            Future.succeededFuture(toJson(row))
        }

    /**
     * 列表查询（空结果也返回 `records: []` 与 `total: 0`）。
     * 过滤：`patient_id` / `date`（单日）/ `meal_time` / `from`+`to`（区间，含端点）。
     */
    fun list(
        patientId: String? = null,
        date: String? = null,
        mealTime: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("x.patient_id = \$${values.size + 1}")
            values.add(it)
        }
        date?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("x.intake_date = \$${values.size + 1}")
            values.add(parseDate(it))
        }
        mealTime?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in DiningConstants.MEAL_TIMES)
                return Future.failedFuture(
                    IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"),
                )
            conditions.add("x.meal_time = \$${values.size + 1}")
            values.add(it)
        }
        from?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("x.intake_date >= \$${values.size + 1}")
            values.add(parseDate(it))
        }
        to?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("x.intake_date <= \$${values.size + 1}")
            values.add(parseDate(it))
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY x.intake_date DESC, x.meal_time DESC, x.created_at DESC" +
            " LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            val listValues = values + listOf(limit, offset)
            pool.preparedQuery(listSql).execute(tupleOf(listValues)).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  校验
    // ========================================================================

    private fun parseDate(value: String?): LocalDate {
        val text = value?.trim()
        if (text.isNullOrEmpty()) throw IllegalArgumentException("intake_date is required")
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("intake_date must be a valid ISO date (yyyy-MM-dd)")
        }
    }

    private fun normalizeItems(value: Any?): JsonArray {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("items must be a non-empty array of {food_name, amount_g}")
        if (array.isEmpty()) throw IllegalArgumentException("items must not be empty")

        val normalized = JsonArray()
        for (index in 0 until array.size()) {
            val item = array.getValue(index) as? JsonObject
                ?: throw IllegalArgumentException("items[$index] must be an object")
            val foodName = item.getString("food_name")?.trim()
            if (foodName.isNullOrEmpty())
                throw IllegalArgumentException("items[$index].food_name is required")
            val amount = numberOrNull(item.getValue("amount_g"))
                ?: throw IllegalArgumentException("items[$index].amount_g must be a positive number")
            if (amount <= BigDecimal.ZERO)
                throw IllegalArgumentException("items[$index].amount_g must be a positive number")

            val entry = JsonObject()
                .put("food_name", foodName)
                .put("amount_g", amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toDouble())
            item.getString("note")?.trim()?.takeIf { it.isNotEmpty() }?.let { entry.put("note", it) }
            normalized.add(entry)
        }
        return normalized
    }

    private fun parseRatio(value: Any?): BigDecimal {
        if (value == null) return BigDecimal.ONE
        val number = numberOrNull(value)
            ?: throw IllegalArgumentException("intake_ratio must be a number between 0 and 1")
        if (number <= BigDecimal.ZERO || number > BigDecimal.ONE)
            throw IllegalArgumentException("intake_ratio must be greater than 0 and at most 1")
        return number.setScale(RATIO_SCALE, RoundingMode.HALF_UP)
    }

    private fun parseMetadata(value: Any?): JsonObject? = when (value) {
        null -> null
        is JsonObject -> value
        else -> throw IllegalArgumentException("metadata must be an object")
    }

    private fun numberOrNull(value: Any?): BigDecimal? = when (value) {
        is Number -> BigDecimal(value.toString())
        is String -> value.trim().toBigDecimalOrNull()
        else -> null
    }

    /**
     * 按 `$1..$n` 位置顺序组装绑定参数。
     * 不用 `Tuple.of(*array)`：Vert.x 的 `Tuple.of` 是固定元数重载，不是 vararg。
     */
    private fun tupleOf(values: List<Any?>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }

    // ========================================================================
    //  查询与汇总
    // ========================================================================

    private fun loadPatientName(patientId: String): Future<String> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    DiningNotFoundException("patient not found: $patientId"),
                )
            Future.succeededFuture(row.getString("name") ?: "")
        }

    private fun verifyRosterItem(rosterItemId: String?): Future<Unit> {
        if (rosterItemId == null) return Future.succeededFuture(Unit)
        return pool.preparedQuery(ROSTER_ITEM_SQL).execute(Tuple.of(rosterItemId)).compose { rows ->
            if (rows.iterator().asSequence().firstOrNull() == null)
                Future.failedFuture(DiningNotFoundException("roster item not found: $rosterItemId"))
            else Future.succeededFuture(Unit)
        }
    }

    private fun foodNames(items: JsonArray): List<String> =
        (0 until items.size())
            .mapNotNull { items.getJsonObject(it)?.getString("food_name") }
            .distinct()

    private fun loadNutrition(names: List<String>): Future<Map<String, JsonObject>> {
        var result: Future<Map<String, JsonObject>> = Future.succeededFuture(emptyMap())
        for (name in names) {
            result = result.compose { acc ->
                pool.preparedQuery(DISH_NUTRITION_SQL).execute(Tuple.of(name)).map { rows ->
                    val nutrition = rows.iterator().asSequence().firstOrNull()?.getJsonObject("nutrition")
                    if (nutrition == null) acc else acc + (name to nutrition)
                }
            }
        }
        return result
    }

    private fun summarize(
        items: JsonArray,
        nutritionByName: Map<String, JsonObject>,
        ratio: BigDecimal,
    ): NutritionTotals {
        var energy = BigDecimal.ZERO
        var protein = BigDecimal.ZERO
        var fat = BigDecimal.ZERO
        var carb = BigDecimal.ZERO
        var sodium = BigDecimal.ZERO

        for (index in 0 until items.size()) {
            val item = items.getJsonObject(index) ?: continue
            val nutrition = nutritionByName[item.getString("food_name")] ?: continue
            val amount = numberOrNull(item.getValue("amount_g")) ?: continue
            val basis = numberOrNull(nutrition.getValue("basis_g")) ?: DEFAULT_BASIS_G
            if (basis <= BigDecimal.ZERO) continue
            val factor = amount.divide(basis, DIVISION_SCALE, RoundingMode.HALF_UP)
            energy += nutrient(nutrition, "energy_kcal") * factor
            protein += nutrient(nutrition, "protein_g") * factor
            fat += nutrient(nutrition, "fat_g") * factor
            carb += nutrient(nutrition, "carb_g") * factor
            sodium += nutrient(nutrition, "sodium_mg") * factor
        }

        fun scaled(value: BigDecimal): BigDecimal =
            (value * ratio).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)

        return NutritionTotals(
            energyKcal = scaled(energy),
            proteinG = scaled(protein),
            fatG = scaled(fat),
            carbG = scaled(carb),
            sodiumMg = scaled(sodium),
        )
    }

    private fun nutrient(nutrition: JsonObject, key: String): BigDecimal =
        numberOrNull(nutrition.getValue(key)) ?: BigDecimal.ZERO
}
