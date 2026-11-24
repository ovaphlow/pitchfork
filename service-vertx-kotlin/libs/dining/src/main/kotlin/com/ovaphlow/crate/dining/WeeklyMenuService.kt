package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.isUniqueViolation
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

/**
 * 周菜谱编排（FR-3）：按周为机构编排每天每餐次的菜品组合。
 * - 每周最多一份启用菜谱（部分唯一索引），停用后可换版，历史数据不受影响；
 * - 菜品明细保存 dish_name 快照，菜品改名/停用不影响历史菜谱展示；
 * - 支持整周模板复制到另一周（copy）。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class WeeklyMenuService(
    private val pool: Pool,
) {

    companion object {
        private const val MENU_COLUMNS =
            "id, week_start, name, status, remark, metadata, created_at, updated_at"

        private const val ITEM_COLUMNS =
            "id, menu_id, day_of_week, meal_time, dish_id, dish_name, sort_order, metadata, created_at"

        private const val MENU_INSERT_SQL =
            "INSERT INTO dining.dining_weekly_menus " +
                "(id, week_start, name, status, remark, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"

        private const val MENU_STATUS_SQL =
            "UPDATE dining.dining_weekly_menus SET status = \$1, updated_at = \$2 WHERE id = \$3"

        private const val ACTIVE_WEEK_SQL =
            "SELECT 1 FROM dining.dining_weekly_menus WHERE week_start = \$1 AND status = \$2"

        private const val ITEM_INSERT_SQL =
            "INSERT INTO dining.dining_weekly_menu_items " +
                "(id, menu_id, day_of_week, meal_time, dish_id, dish_name, sort_order, metadata, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val ITEM_DELETE_SQL =
            "DELETE FROM dining.dining_weekly_menu_items WHERE menu_id = \$1"

        private const val ITEM_ORDER =
            " ORDER BY day_of_week ASC, meal_time ASC, sort_order ASC, created_at ASC"

        private const val ACTIVE_STATUS = "启用"

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
        return pool.preparedQuery(MENU_INSERT_SQL).execute(
            Tuple.of(
                id, normalized, body.getString("name"), ACTIVE_STATUS, body.getString("remark"),
                body.getJsonObject("metadata"), now, now,
            )
        ).compose { result ->
            if (result.rowCount() == 0)
                return@compose Future.failedFuture(IllegalStateException("insert returned no row"))
            get(id)
        }.recover { error ->
            // 命中部分唯一索引（该周已有启用菜谱）→ 409
            if (isUniqueViolation(error, setOf("uq_dining_weekly_menus_active_week")))
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        weekStart?.takeIf(String::isNotBlank)?.let { where.add("week_start = ${bind(parseDate(it, "week_start"))}") }
        status?.takeIf(String::isNotBlank)?.let { where.add("status = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM dining.dining_weekly_menus$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $MENU_COLUMNS FROM dining.dining_weekly_menus$whereClause " +
                "ORDER BY week_start DESC, created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).compose { dataRows ->
                val heads = dataRows.map { toJson(it) }
                if (heads.isEmpty()) {
                    return@compose Future.succeededFuture(
                        JsonObject().put("records", JsonArray())
                            .put("meta", JsonObject().put("total", total))
                    )
                }
                countItems(heads.mapNotNull { it.getString("id") }).map { counts ->
                    val records = JsonArray()
                    for (head in heads)
                        records.add(head.copy().put("item_count", counts[head.getString("id")] ?: 0L))
                    JsonObject().put("records", records)
                        .put("meta", JsonObject().put("total", total))
                }
            }
        }
    }

    /** 列表页「菜品项」：按 menu_id 统计明细条数，避免列表接口带出全部明细。 */
    private fun countItems(menuIds: List<String>): Future<Map<String, Long>> {
        if (menuIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val placeholders = menuIds.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "SELECT menu_id, count(*) AS item_count FROM dining.dining_weekly_menu_items " +
                "WHERE menu_id IN ($placeholders) GROUP BY menu_id"
        return pool.preparedQuery(sql).execute(Tuple.from(menuIds)).map { rows ->
            rows.associate { it.getString("menu_id") to (it.getLong("item_count") ?: 0L) }
        }
    }

    fun get(id: String): Future<JsonObject> {
        val menuSql = "SELECT $MENU_COLUMNS FROM dining.dining_weekly_menus WHERE id = \$1"
        return pool.preparedQuery(menuSql).execute(Tuple.of(id)).compose { rows ->
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
        val sql =
            "SELECT $MENU_COLUMNS FROM dining.dining_weekly_menus WHERE week_start = \$1 AND status = \$2"
        return pool.preparedQuery(sql).execute(Tuple.of(weekStart, ACTIVE_STATUS)).compose { rows ->
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
            val sets = mutableListOf("updated_at = \$1")
            val args = mutableListOf<Any?>(OffsetDateTime.now())

            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            if (body.containsKey("name"))
                sets.add("name = ${bind(body.getString("name"))}")
            if (body.containsKey("remark"))
                sets.add("remark = ${bind(body.getString("remark"))}")
            if (body.containsKey("metadata"))
                sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")
            val sql =
                "UPDATE dining.dining_weekly_menus SET ${sets.joinToString(", ")} WHERE id = ${bind(id)}"
            pool.preparedQuery(sql).execute(Tuple.from(args)).flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        if (status.isBlank() || status !in DiningConstants.ENABLE_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.ENABLE_STATUSES}"))
        return get(id).flatMap {
            pool.preparedQuery(MENU_STATUS_SQL).execute(Tuple.of(status, OffsetDateTime.now(), id))
                .compose { result ->
                    if (result.rowCount() == 0)
                        return@compose Future.failedFuture(DiningNotFoundException("weekly menu not found: $id"))
                    get(id)
                }.recover { error ->
                    if (isUniqueViolation(error, setOf("uq_dining_weekly_menus_active_week")))
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
                    conn.preparedQuery(ITEM_DELETE_SQL).execute(Tuple.of(id)).compose {
                        val now = OffsetDateTime.now()
                        var inserts = Future.succeededFuture<RowSet<Row>>()
                        for (item in validated) {
                            inserts = inserts.compose {
                                insertItem(
                                    conn, id, item.dayOfWeek, item.mealTime, item.dishId,
                                    dishNamesById[item.dishId] ?: "", item.sortOrder, JsonObject(), now,
                                )
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
        if (dishIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val placeholders = dishIds.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = "SELECT id, name, status FROM dining.dining_dishes WHERE id IN ($placeholders)"
        return pool.preparedQuery(sql).execute(Tuple.from(dishIds)).map { rows ->
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
            val notEnabled = dishIds.filter { statusById[it] != ACTIVE_STATUS }
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
            pool.preparedQuery(ACTIVE_WEEK_SQL).execute(Tuple.of(targetWeek, ACTIVE_STATUS)).compose { rows ->
                if (rows.size() > 0)
                    return@compose Future.failedFuture(DiningConflictException("目标周已存在启用的菜谱，请先停用旧版"))

                val newId = Ulid.generate()
                pool.withTransaction<Void?> { conn ->
                    val now = OffsetDateTime.now()
                    conn.preparedQuery(MENU_INSERT_SQL).execute(
                        Tuple.of(
                            newId, targetWeek, source.getString("name"), ACTIVE_STATUS,
                            source.getString("remark"), source.getJsonObject("metadata") ?: JsonObject(),
                            now, now,
                        )
                    ).compose {
                        copyItems(conn, id, newId, now).map<Void?> { null }
                    }
                }.compose { get(newId) }
            }
        }
    }

    /** 复制明细并重新快照菜品名称（不沿用源明细的旧快照）。 */
    private fun copyItems(conn: SqlClient, sourceMenuId: String, targetMenuId: String, now: OffsetDateTime): Future<Void?> {
        val sql = "SELECT $ITEM_COLUMNS FROM dining.dining_weekly_menu_items WHERE menu_id = \$1$ITEM_ORDER"
        return conn.preparedQuery(sql).execute(Tuple.of(sourceMenuId)).compose { rows ->
            var chain = Future.succeededFuture<RowSet<Row>>()
            for (row in rows) {
                val item = itemJson(row)
                chain = chain.compose {
                    insertCopiedItem(conn, targetMenuId, item, now)
                }
            }
            chain.map<Void?> { null }
        }
    }

    private fun insertCopiedItem(
        conn: SqlClient,
        menuId: String,
        item: JsonObject,
        now: OffsetDateTime,
    ): Future<RowSet<Row>> {
        val dishId = item.getString("dish_id")
        // 重新读取菜品当前名称快照
        return conn.preparedQuery("SELECT name FROM dining.dining_dishes WHERE id = \$1")
            .execute(Tuple.of(dishId))
            .compose { dishRows ->
                val dishRow = dishRows.iterator().asSequence().firstOrNull()
                val dishName = dishRow?.getString("name") ?: item.getString("dish_name") ?: ""
                insertItem(
                    conn, menuId, item.getInteger("day_of_week"), item.getString("meal_time"),
                    dishId, dishName, item.getInteger("sort_order") ?: 0,
                    item.getJsonObject("metadata") ?: JsonObject(), now,
                )
            }
    }

    /** 在事务连接上写一条菜谱明细（`dish_name` 已由调用方快照好）。 */
    private fun insertItem(
        conn: SqlClient,
        menuId: String,
        dayOfWeek: Int?,
        mealTime: String?,
        dishId: String?,
        dishName: String,
        sortOrder: Int,
        metadata: JsonObject,
        now: OffsetDateTime,
    ): Future<RowSet<Row>> {
        val id = Ulid.generate()
        return conn.preparedQuery(ITEM_INSERT_SQL).execute(
            Tuple.of(id, menuId, dayOfWeek, mealTime, dishId, dishName, sortOrder, metadata, now)
        )
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

    private class ValidatedItem(
        val dayOfWeek: Int,
        val mealTime: String,
        val dishId: String,
        val sortOrder: Int,
    )

    private fun loadItems(menuId: String): Future<RowSet<Row>> =
        pool.preparedQuery("SELECT $ITEM_COLUMNS FROM dining.dining_weekly_menu_items WHERE menu_id = \$1$ITEM_ORDER")
            .execute(Tuple.of(menuId))

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
}
