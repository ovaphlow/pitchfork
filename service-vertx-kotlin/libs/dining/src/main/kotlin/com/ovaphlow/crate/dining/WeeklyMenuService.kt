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
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 周菜谱编排（FR-3）：按周为机构编排每天每餐次的菜品组合。
 * - 每周最多一份启用菜谱（部分唯一索引），停用后可换版，历史数据不受影响；
 * - 菜品明细保存 dish_name 快照，菜品改名/停用不影响历史菜谱展示；
 * - 支持整周模板复制到另一周（copy）。
 */
class WeeklyMenuService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = DSL.table(DSL.name("dining", "dining_weekly_menus"))
    private val cId = DSL.field("id", String::class.java)
    private val cWeekStart = DSL.field("week_start", LocalDate::class.java)
    private val cName = DSL.field("name", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    private val items = DSL.table(DSL.name("dining", "dining_weekly_menu_items"))
    private val cMenuId = DSL.field("menu_id", String::class.java)
    private val cDayOfWeek = DSL.field("day_of_week", Int::class.java)
    private val cMealTime = DSL.field("meal_time", String::class.java)
    private val cDishId = DSL.field("dish_id", String::class.java)
    private val cDishName = DSL.field("dish_name", String::class.java)
    private val cSortOrder = DSL.field("sort_order", Int::class.java)

    private val dishes = DSL.table(DSL.name("dining", "dining_dishes"))
    private val dId = DSL.field(DSL.name("dining", "dining_dishes", "id"), String::class.java)
    private val dName = DSL.field(DSL.name("dining", "dining_dishes", "name"), String::class.java)
    private val dStatus = DSL.field(DSL.name("dining", "dining_dishes", "status"), String::class.java)

    companion object {
        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("week_start", row.getValue("week_start")?.toString())
            .put("name", row.getValue("name")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val weekStart = body.getString("week_start")
        if (weekStart.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("week_start is required"))
        val weekStartDate = parseDate(weekStart, "week_start")
        val normalized = DiningConstants.weekStartOf(weekStartDate)

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cWeekStart, normalized)
            .set(cName, body.getString("name"))
            .set(cStatus, "启用")
            .set(cRemark, body.getString("remark"))
            .set(cMetadata, body.containsKey("metadata").let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return execute(pool, query).compose { result ->
            if (result.rowCount() == 0)
                return@compose Future.failedFuture(IllegalStateException("insert returned no row"))
            get(id)
        }.recover { error ->
            // 命中部分唯一索引（该周已有启用菜谱）→ 409
            if (error is io.vertx.pgclient.PgException && error.sqlState == "23505")
                Future.failedFuture(DiningConflictException("该周已存在启用的菜谱，请先停用旧版"))
            else Future.failedFuture(error)
        }
    }

    fun list(
        weekStart: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        weekStart?.takeIf(String::isNotBlank)?.let { conditions.add(cWeekStart.eq(parseDate(it, "week_start"))) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(cStatus.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t).where(conditions)
            .orderBy(cWeekStart.desc(), cCreatedAt.desc())
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
        val menuQuery = ctx.selectFrom(t).where(cId.eq(id))
        return execute(pool, menuQuery).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                return@compose Future.failedFuture(DiningNotFoundException("weekly menu not found: $id"))
            loadItems(id).map { itemRows ->
                toJson(row).put("items", JsonArray(itemRows.map(::itemJson)))
            }
        }
    }

    /** 返回 [date] 所在周当前启用的菜谱（含明细），无则 404。 */
    fun getByDate(date: String): Future<JsonObject> {
        val d = parseDate(date, "date")
        val weekStart = DiningConstants.weekStartOf(d)
        val query = ctx.selectFrom(t)
            .where(cWeekStart.eq(weekStart).and(cStatus.eq("启用")))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                return@compose Future.failedFuture(DiningNotFoundException("no active weekly menu for week of $d"))
            loadItems(row.getString("id")).map { itemRows ->
                toJson(row).put("items", JsonArray(itemRows.map(::itemJson)))
            }
        }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap {
            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cUpdatedAt, now)
            if (body.containsKey("name"))
                q = q.set(cName, body.getString("name"))
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
            execute(pool, query).compose { result ->
                if (result.rowCount() == 0)
                    return@compose Future.failedFuture(DiningNotFoundException("weekly menu not found: $id"))
                get(id)
            }.recover { error ->
                if (error is io.vertx.pgclient.PgException && error.sqlState == "23505")
                    Future.failedFuture(DiningConflictException("该周已存在启用的菜谱，请先停用旧版"))
                else Future.failedFuture(error)
            }
        }
    }

    /**
     * 整周替换明细：{ "items": [{ "day_of_week": 1..7, "meal_time": "午餐", "dish_id": "...", "sort_order": 0 }] }。
     * 全量替换（先删后插），同一事务内完成；菜品须存在且处于启用状态，
     * dish_name 在写入时从菜品库快照。
     */
    fun replaceItems(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            val rawItems = body.getJsonArray("items") ?: JsonArray()
            val validated = try {
                rawItems.mapIndexed { index, raw ->
                    if (raw !is JsonObject)
                        throw IllegalArgumentException("items must be an array of objects")
                    validateItem(raw, index)
                }
            } catch (error: IllegalArgumentException) {
                return@flatMap Future.failedFuture(error)
            }
            if (validated.isEmpty())
                return@flatMap Future.failedFuture(IllegalArgumentException("items must not be empty"))

            validateDishes(validated).compose { dishNamesById ->
                pool.withTransaction<Void?> { conn ->
                    val deleteQuery = ctx.deleteFrom(items).where(cMenuId.eq(id))
                    execute(conn, deleteQuery).compose {
                        val now = OffsetDateTime.now()
                        var inserts = Future.succeededFuture<io.vertx.sqlclient.RowSet<Row>>()
                        for (item in validated) {
                            inserts = inserts.compose {
                                execute(conn, item.toInsert(id, now, dishNamesById))
                            }
                        }
                        inserts.map<Void?> { null }
                    }
                }.compose { get(id) }
            }
        }
    }

    /**
     * 批量校验菜品：全部存在且启用，返回 dishId -> dishName 快照。
     */
    private fun validateDishes(validated: List<ValidatedItem>): Future<Map<String, String>> {
        val dishIds = validated.map { it.dishId }.distinct()
        val query = ctx.select(dId, dName, dStatus)
            .from(dishes)
            .where(dId.`in`(dishIds))
        return execute(pool, query).map { rows ->
            val nameById = mutableMapOf<String, String>()
            val statusById = mutableMapOf<String, String>()
            for (row in rows) {
                val dishId = row.getString("id") ?: continue
                nameById[dishId] = row.getString("name") ?: ""
                statusById[dishId] = row.getString("status") ?: ""
            }
            val missing = dishIds.filter { it !in nameById }
            if (missing.isNotEmpty())
                throw IllegalArgumentException("dish not found: ${missing.first()}")
            val notEnabled = dishIds.filter { statusById[it] != "启用" }
            if (notEnabled.isNotEmpty())
                throw IllegalArgumentException("dish is not enabled: ${notEnabled.first()}")
            nameById
        }
    }

    /**
     * 整周模板复制：把 [id] 菜谱连同明细复制到 [weekStart] 所在周。
     * 目标周已有启用菜谱时 409；复制后目标菜谱为启用状态。
     */
    fun copy(id: String, weekStart: String): Future<JsonObject> {
        if (weekStart.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("week_start is required"))
        val targetWeek = DiningConstants.weekStartOf(parseDate(weekStart, "week_start"))

        return get(id).flatMap { source ->
            val existingQuery = ctx.selectOne().from(t)
                .where(cWeekStart.eq(targetWeek).and(cStatus.eq("启用")))
            execute(pool, existingQuery).compose { rows ->
                if (rows.size() > 0)
                    return@compose Future.failedFuture(DiningConflictException("目标周已存在启用的菜谱，请先停用旧版"))

                val newId = Ulid.generate()
                pool.withTransaction<Void?> { conn ->
                    val now = OffsetDateTime.now()
                    val insertMenu = ctx.insertInto(t)
                        .set(cId, newId)
                        .set(cWeekStart, targetWeek)
                        .set(cName, source.getString("name"))
                        .set(cStatus, "启用")
                        .set(cRemark, source.getString("remark"))
                        .set(cMetadata, JSONB.valueOf(source.getJsonObject("metadata")?.encode() ?: "{}"))
                        .set(cCreatedAt, now)
                        .set(cUpdatedAt, now)
                    execute(conn, insertMenu).compose {
                        copyItems(conn, id, newId, now).map<Void?> { null }
                    }
                }.compose { get(newId) }
            }
        }
    }

    /** 复制明细并重新快照菜品名称（不沿用源明细的旧快照）。 */
    private fun copyItems(conn: SqlClient, sourceMenuId: String, targetMenuId: String, now: OffsetDateTime): Future<Void?> {
        val selectQuery = ctx.select(cId, cMenuId, cDayOfWeek, cMealTime, cDishId, cDishName, cSortOrder, cMetadata, cCreatedAt)
            .from(items)
            .where(cMenuId.eq(sourceMenuId))
        return execute(conn, selectQuery).compose { rows ->
            var chain = Future.succeededFuture<io.vertx.sqlclient.RowSet<Row>>()
            for (row in rows) {
                val itemJson = itemJson(row)
                chain = chain.compose {
                    insertCopiedItem(conn, targetMenuId, itemJson, now)
                }
            }
            chain.map<Void?> { null }
        }
    }

    private fun insertCopiedItem(conn: SqlClient, menuId: String, item: JsonObject, now: OffsetDateTime): Future<io.vertx.sqlclient.RowSet<Row>> {
        val dishId = item.getString("dish_id")
        // 重新读取菜品当前名称快照
        val dishQuery = ctx.select(dName).from(dishes).where(dId.eq(dishId))
        return execute(conn, dishQuery).compose { dishRows ->
            val dishRow = dishRows.iterator().asSequence().firstOrNull()
            val dishName = dishRow?.getString("name") ?: item.getString("dish_name")
            val id = Ulid.generate()
            val insertQuery = ctx.insertInto(items)
                .set(cId, id)
                .set(cMenuId, menuId)
                .set(cDayOfWeek, item.getInteger("day_of_week"))
                .set(cMealTime, item.getString("meal_time"))
                .set(cDishId, dishId)
                .set(cDishName, dishName)
                .set(cSortOrder, item.getInteger("sort_order") ?: 0)
                .set(cMetadata, JSONB.valueOf(item.getJsonObject("metadata")?.encode() ?: "{}"))
                .set(DSL.field("created_at", OffsetDateTime::class.java), now)
            execute(conn, insertQuery)
        }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    private fun validateItem(raw: JsonObject, index: Int): ValidatedItem {
        val day = raw.getInteger("day_of_week")
        if (day == null || day !in 1..7)
            throw IllegalArgumentException("items[$index].day_of_week must be between 1 and 7")
        val mealTime = raw.getString("meal_time")
        if (mealTime.isNullOrBlank() || mealTime !in DiningConstants.MEAL_TIMES)
            throw IllegalArgumentException("items[$index].meal_time must be one of: ${DiningConstants.MEAL_TIMES}")
        val dishId = raw.getString("dish_id")
        if (dishId.isNullOrBlank())
            throw IllegalArgumentException("items[$index].dish_id is required")
        return ValidatedItem(day, mealTime, dishId, raw.getInteger("sort_order") ?: 0)
    }

    private inner class ValidatedItem(
        val dayOfWeek: Int,
        val mealTime: String,
        val dishId: String,
        val sortOrder: Int,
    ) {
        fun toInsert(menuId: String, now: OffsetDateTime, dishNamesById: Map<String, String>): org.jooq.Query {
            val id = Ulid.generate()
            return ctx.insertInto(items)
                .set(cId, id)
                .set(cMenuId, menuId)
                .set(cDayOfWeek, dayOfWeek)
                .set(cMealTime, mealTime)
                .set(cDishId, dishId)
                .set(cDishName, dishNamesById[dishId] ?: "")
                .set(cSortOrder, sortOrder)
                .set(cMetadata, JSONB.valueOf("{}"))
                .set(DSL.field("created_at", OffsetDateTime::class.java), now)
        }
    }

    private fun loadItems(menuId: String): Future<io.vertx.sqlclient.RowSet<Row>> {
        val query = ctx.selectFrom(items)
            .where(cMenuId.eq(menuId))
            .orderBy(cDayOfWeek.asc(), cMealTime.asc(), cSortOrder.asc(), cCreatedAt.asc())
        return execute(pool, query)
    }

    private fun itemJson(row: Row): JsonObject = JsonObject()
        .put("id", row.getValue("id")?.toString())
        .put("menu_id", row.getValue("menu_id")?.toString())
        .put("day_of_week", row.getValue("day_of_week") as? Int)
        .put("meal_time", row.getValue("meal_time")?.toString())
        .put("dish_id", row.getValue("dish_id")?.toString())
        .put("dish_name", row.getValue("dish_name")?.toString())
        .put("sort_order", row.getValue("sort_order") as? Int ?: 0)
        .put("metadata", row.getValue("metadata") as? JsonObject)
        .put("created_at", row.getValue("created_at")?.toString())

    private fun parseDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
