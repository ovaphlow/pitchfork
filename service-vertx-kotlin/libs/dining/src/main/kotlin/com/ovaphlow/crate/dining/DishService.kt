package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/**
 * 菜品库（FR-2）：名称、分类、适用餐次、饮食标签、启用状态。
 * 菜品只停用不删除，历史菜谱与配餐快照不受影响。
 *
 * 054 A1（V601）追加 `nutrition`（每 basis_g 克营养成分）与 `allergens`
 * （过敏原字符串数组）两列：营养分析的数据源与膳食域过敏原标记口径。
 */
class DishService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = DSL.table(DSL.name("dining", "dining_dishes"))
    private val cId = DSL.field("id", String::class.java)
    private val cName = DSL.field("name", String::class.java)
    private val cCategory = DSL.field("category", String::class.java)
    private val cMealTimes = DSL.field("meal_times", JSONB::class.java)
    private val cDietTags = DSL.field("diet_tags", JSONB::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cNutrition = DSL.field("nutrition", JSONB::class.java)
    private val cAllergens = DSL.field("allergens", JSONB::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    companion object {
        /** 菜品营养字段白名单（每 `basis_g` 克）；未知字段忽略，已知字段必须是数字。 */
        val NUTRITION_FIELDS = setOf("basis_g", "energy_kcal", "protein_g", "fat_g", "carb_g", "sodium_mg")

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
        val nutrition = nutritionObject(body.getValue("nutrition"))
        val allergens = allergenList(body.getValue("allergens"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cName, name.trim())
            .set(cCategory, category)
            .set(cMealTimes, JSONB.valueOf(mealTimes.encode()))
            .set(cDietTags, JSONB.valueOf(dietTags.encode()))
            .set(cStatus, "启用")
            .set(cRemark, body.getString("remark"))
            .set(cNutrition, nutrition?.let { JSONB.valueOf(it.encode()) })
            .set(cAllergens, JSONB.valueOf(allergens.encode()))
            .set(cMetadata, body.containsKey("metadata").let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return execute(pool, query).flatMap { get(id) }
    }

    fun list(
        category: String? = null,
        status: String? = null,
        keyword: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        category?.takeIf(String::isNotBlank)?.let { conditions.add(cCategory.eq(it)) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(cStatus.eq(it)) }
        keyword?.takeIf(String::isNotBlank)?.let { conditions.add(cName.containsIgnoreCase(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t).where(conditions)
            .orderBy(cCreatedAt.desc())
            .limit(limit).offset(offset)

        return execute(pool, countQuery).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(DiningNotFoundException("dish not found: $id"))
            else Future.succeededFuture(toJson(row))
        }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap {
            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cUpdatedAt, now)

            if (body.containsKey("name")) {
                val name = body.getString("name")
                if (name.isNullOrBlank())
                    return@flatMap Future.failedFuture(IllegalArgumentException("name is required"))
                q = q.set(cName, name.trim())
            }
            if (body.containsKey("category")) {
                val category = body.getString("category")
                if (category.isNullOrBlank() || category !in DiningConstants.DISH_CATEGORIES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid category, must be one of: ${DiningConstants.DISH_CATEGORIES}"))
                q = q.set(cCategory, category)
            }
            if (body.containsKey("meal_times"))
                q = q.set(cMealTimes, JSONB.valueOf(stringSubset(body.getJsonArray("meal_times"), "meal_times", DiningConstants.MEAL_TIMES).encode()))
            if (body.containsKey("diet_tags"))
                q = q.set(cDietTags, JSONB.valueOf(stringSubset(body.getJsonArray("diet_tags"), "diet_tags", DiningConstants.DIET_TAGS).encode()))
            if (body.containsKey("remark"))
                q = q.set(cRemark, body.getString("remark"))
            if (body.containsKey("nutrition")) {
                val nutrition = nutritionObject(body.getValue("nutrition"))
                q = q.set(cNutrition, nutrition?.let { JSONB.valueOf(it.encode()) })
            }
            if (body.containsKey("allergens"))
                q = q.set(cAllergens, JSONB.valueOf(allergenList(body.getValue("allergens")).encode()))
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))
            execute(pool, updateQuery).flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        if (status.isBlank() || status !in DiningConstants.ENABLE_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.ENABLE_STATUSES}"))
        return get(id).flatMap {
            val now = OffsetDateTime.now()
            val query = ctx.update(t)
                .set(cStatus, status)
                .set(cUpdatedAt, now)
                .where(cId.eq(id))
            execute(pool, query).flatMap { get(id) }
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

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
