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
import java.time.OffsetDateTime

/**
 * 菜品库（FR-2）：名称、分类、适用餐次、饮食标签、启用状态。
 * 菜品只停用不删除，历史菜谱与配餐快照不受影响。
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
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    companion object {
        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("name", row.getValue("name")?.toString())
            .put("category", row.getValue("category")?.toString())
            .put("meal_times", row.getValue("meal_times") as? JsonArray ?: JsonArray())
            .put("diet_tags", row.getValue("diet_tags") as? JsonArray ?: JsonArray())
            .put("status", row.getValue("status")?.toString())
            .put("remark", row.getValue("remark")?.toString())
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

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
