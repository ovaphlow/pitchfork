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
import java.time.OffsetDateTime

/**
 * 菜品库（FR-2）：名称、分类、适用餐次、饮食标签、启用状态。
 * 菜品只停用不删除，历史菜谱与配餐快照不受影响。
 *
 * 054 A1（V601）追加 `nutrition`（每 basis_g 克营养成分）与 `allergens`
 * （过敏原字符串数组）两列：营养分析的数据源与膳食域过敏原标记口径。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class DishService(
    private val pool: Pool,
) {

    companion object {
        /** 菜品营养字段白名单（每 `basis_g` 克）；未知字段忽略，已知字段必须是数字。 */
        val NUTRITION_FIELDS = setOf("basis_g", "energy_kcal", "protein_g", "fat_g", "carb_g", "sodium_mg")

        private const val COLUMNS =
            "id, name, category, meal_times, diet_tags, status, remark, nutrition, allergens, metadata, " +
                "created_at, updated_at"

        private const val INSERT_SQL =
            "INSERT INTO dining.dining_dishes " +
                "(id, name, category, meal_times, diet_tags, status, remark, nutrition, allergens, " +
                "metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        private const val STATUS_SQL =
            "UPDATE dining.dining_dishes SET status = \$1, updated_at = \$2 WHERE id = \$3"

        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("name", row.getValue("name")?.toString())
            .put("category", row.getValue("category")?.toString())
            .put("meal_times", row.getValue("meal_times") as? JsonArray ?: JsonArray())
            .put("diet_tags", row.getValue("diet_tags") as? JsonArray ?: JsonArray())
            .put("status", row.getValue("status")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("nutrition", row.getValue("nutrition") as? JsonObject)
            .put("allergens", row.getValue("allergens") as? JsonArray ?: JsonArray())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val name = body.getString("name")
        val category = body.getString("category")
        if (name.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("name is required"))
        if (category.isNullOrBlank() || category !in DiningConstants.DISH_CATEGORIES)
            return Future.failedFuture(IllegalArgumentException("invalid category, must be one of: ${DiningConstants.DISH_CATEGORIES}"))

        val mealTimes = stringSubset(body.getJsonArray("meal_times"), "meal_times", DiningConstants.MEAL_TIMES)
        val dietTags = stringSubset(body.getJsonArray("diet_tags"), "diet_tags", DiningConstants.DIET_TAGS)
        val nutrition = try {
            nutritionObject(body.getValue("nutrition"))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val allergens = try {
            allergenList(body.getValue("allergens"))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.preparedQuery(INSERT_SQL).execute(
            Tuple.of(
                id, name.trim(), category, mealTimes, dietTags, "启用", body.getString("remark"),
                nutrition, allergens, body.getJsonObject("metadata"), now, now,
            )
        ).flatMap { get(id) }
    }

    fun list(
        category: String? = null,
        status: String? = null,
        keyword: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        category?.takeIf(String::isNotBlank)?.let { where.add("category = ${bind(it)}") }
        status?.takeIf(String::isNotBlank)?.let { where.add("status = ${bind(it)}") }
        keyword?.takeIf(String::isNotBlank)?.let {
            where.add("lower(name) LIKE lower('%' || ${bind(it)} || '%')")
        }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM dining.dining_dishes$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $COLUMNS FROM dining.dining_dishes$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT $COLUMNS FROM dining.dining_dishes WHERE id = \$1")
            .execute(Tuple.of(id))
            .compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row == null)
                    Future.failedFuture(DiningNotFoundException("dish not found: $id"))
                else Future.succeededFuture(toJson(row))
            }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap {
            val sets = mutableListOf<String>()
            val args = mutableListOf<Any?>()

            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            args.add(OffsetDateTime.now())
            sets.add("updated_at = \$1")

            if (body.containsKey("name")) {
                val name = body.getString("name")
                if (name.isNullOrBlank())
                    return@flatMap Future.failedFuture(IllegalArgumentException("name is required"))
                sets.add("name = ${bind(name.trim())}")
            }
            if (body.containsKey("category")) {
                val category = body.getString("category")
                if (category.isNullOrBlank() || category !in DiningConstants.DISH_CATEGORIES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid category, must be one of: ${DiningConstants.DISH_CATEGORIES}"))
                sets.add("category = ${bind(category)}")
            }
            if (body.containsKey("meal_times")) {
                val mealTimes = try {
                    stringSubset(body.getJsonArray("meal_times"), "meal_times", DiningConstants.MEAL_TIMES)
                } catch (error: IllegalArgumentException) {
                    return@flatMap Future.failedFuture(error)
                }
                sets.add("meal_times = ${bind(mealTimes)}")
            }
            if (body.containsKey("diet_tags")) {
                val dietTags = try {
                    stringSubset(body.getJsonArray("diet_tags"), "diet_tags", DiningConstants.DIET_TAGS)
                } catch (error: IllegalArgumentException) {
                    return@flatMap Future.failedFuture(error)
                }
                sets.add("diet_tags = ${bind(dietTags)}")
            }
            if (body.containsKey("remark"))
                sets.add("remark = ${bind(body.getString("remark"))}")
            if (body.containsKey("nutrition")) {
                val nutrition = try {
                    nutritionObject(body.getValue("nutrition"))
                } catch (error: IllegalArgumentException) {
                    return@flatMap Future.failedFuture(error)
                }
                sets.add("nutrition = ${bind(nutrition)}")
            }
            if (body.containsKey("allergens")) {
                val allergens = try {
                    allergenList(body.getValue("allergens"))
                } catch (error: IllegalArgumentException) {
                    return@flatMap Future.failedFuture(error)
                }
                sets.add("allergens = ${bind(allergens)}")
            }
            if (body.containsKey("metadata"))
                sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")

            val sql =
                "UPDATE dining.dining_dishes SET ${sets.joinToString(", ")} WHERE id = ${bind(id)}"
            pool.preparedQuery(sql).execute(Tuple.from(args)).flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        if (status.isBlank() || status !in DiningConstants.ENABLE_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.ENABLE_STATUSES}"))
        return get(id).flatMap {
            pool.preparedQuery(STATUS_SQL).execute(Tuple.of(status, OffsetDateTime.now(), id))
                .flatMap { get(id) }
        }
    }

    private fun stringSubset(value: JsonArray?, field: String, allowed: Set<String>): JsonArray {
        if (value == null) return JsonArray()
        val items = value.map { it.toString().trim() }.filter { it.isNotEmpty() }
        val invalid = items.filter { it !in allowed }
        if (invalid.isNotEmpty())
            throw IllegalArgumentException("invalid $field values: $invalid, must be subset of $allowed")
        return JsonArray(items)
    }

    /**
     * 054 A1：菜品营养快照校验（每 `basis_g` 克）。
     * `null` 表示清空；未知字段忽略；已知字段必须是数字且非负。
     */
    private fun nutritionObject(value: Any?): JsonObject? {
        if (value == null) return null
        val raw = value as? JsonObject
            ?: throw IllegalArgumentException("nutrition must be an object with numeric fields")
        val normalized = JsonObject()
        for (key in NUTRITION_FIELDS) {
            val entry = raw.getValue(key) ?: continue
            val number = when (entry) {
                is Number -> BigDecimal(entry.toString())
                is String -> entry.trim().toBigDecimalOrNull()
                else -> null
            } ?: throw IllegalArgumentException("nutrition.$key must be a number")
            if (number < BigDecimal.ZERO)
                throw IllegalArgumentException("nutrition.$key must not be negative")
            normalized.put(key, number.setScale(2, RoundingMode.HALF_UP).toDouble())
        }
        if (normalized.isEmpty())
            throw IllegalArgumentException("nutrition must contain at least one of: $NUTRITION_FIELDS")
        return normalized
    }

    /**
     * 054 A1：过敏原标记（字符串数组口径，与 `dining_diet_profiles.allergies` 一致）。
     * 兼容逗号分隔字符串（051 同款宽松处理）；`null` 视为空数组。
     */
    private fun allergenList(value: Any?): JsonArray {
        val items = when (value) {
            null -> emptyList()
            is JsonArray -> value.map { element ->
                element as? String
                    ?: throw IllegalArgumentException("allergens must contain only strings")
            }.map { it.trim() }.filter { it.isNotEmpty() }
            is String -> value.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw IllegalArgumentException("allergens must be a string array, or a comma-separated string")
        }
        return JsonArray(items.distinct())
    }
}
