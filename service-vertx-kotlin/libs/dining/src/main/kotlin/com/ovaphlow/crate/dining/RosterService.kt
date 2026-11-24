package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 配餐名单（FR-4）：按「日期 + 餐次」生成当日就餐长者名单。
 * - 依据启用饮食档案自动生成条目，快照长者姓名/餐食类型/忌口；
 * - 只纳入在院（ACTIVE）ELDERLY_CARE 入住，离院长者自动排除；
 * - 重复生成幂等：自动条目未登记就餐时刷新快照，手工调整条目不受影响；
 *   **同时**刷新名单容器的生成留痕（`generated_by`/`generated_at` = 最近一次生成的人与时刻）；
 * - 手工调整：外出/请假（标记本餐不就餐）、临时加餐（新增）、删除手工条目；
 *   已登记就餐的条目不可删除（改用外出/请假标记），保证留痕。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class RosterService(
    private val pool: Pool,
) {

    companion object {
        private const val ROSTER_COLUMNS =
            "id, menu_date, meal_time, generated_by, generated_at, remark, metadata, created_at, updated_at"

        /** 名单容器插入（`(menu_date, meal_time)` 唯一；并发冲突静默）。 */
        private const val ROSTER_INSERT_SQL =
            "INSERT INTO dining.dining_rosters " +
                "(id, menu_date, meal_time, generated_by, generated_at, remark, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9) " +
                "ON CONFLICT (menu_date, meal_time) DO NOTHING"

        /** 条目联查列清单（`exec_*` 为就餐执行记录，左联无记录时为空）。 */
        private const val ITEM_COLUMNS =
            "i.id AS id, i.roster_id AS roster_id, i.patient_id AS patient_id, " +
                "i.encounter_id AS encounter_id, i.patient_name AS patient_name, " +
                "i.meal_type AS meal_type, i.allergies AS allergies, i.source AS source, " +
                "i.adjust_type AS adjust_type, i.remark AS remark, i.sort_order AS sort_order, " +
                "i.created_at AS created_at, i.updated_at AS updated_at, " +
                "e.status AS exec_status, e.remark AS exec_remark, " +
                "e.recorded_by AS recorded_by, e.recorded_at AS recorded_at"

        private const val ITEM_FROM =
            " FROM dining.dining_roster_items i " +
                "LEFT JOIN dining.dining_meal_executions e ON e.roster_item_id = i.id"

        /** 自动条目插入（不写 adjust_type/remark；`(roster_id, patient_id)` 唯一）。 */
        private const val AUTO_ITEM_INSERT_SQL =
            "INSERT INTO dining.dining_roster_items " +
                "(id, roster_id, patient_id, encounter_id, patient_name, meal_type, allergies, source, " +
                "sort_order, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12) " +
                "ON CONFLICT (roster_id, patient_id) DO NOTHING"

        /** 手工条目插入（带 adjust_type/remark）。 */
        private const val MANUAL_ITEM_INSERT_SQL =
            "INSERT INTO dining.dining_roster_items " +
                "(id, roster_id, patient_id, encounter_id, patient_name, meal_type, allergies, source, " +
                "adjust_type, remark, sort_order, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14) " +
                "ON CONFLICT (roster_id, patient_id) DO NOTHING"

        private const val AUTO_ITEM_REFRESH_SQL =
            "UPDATE dining.dining_roster_items " +
                "SET patient_name = \$1, meal_type = \$2, allergies = \$3, updated_at = \$4 WHERE id = \$5"

        private const val ADJUST_ITEM_SQL =
            "UPDATE dining.dining_roster_items " +
                "SET adjust_type = \$1, remark = \$2, updated_at = \$3 WHERE id = \$4"

        private const val ITEM_DELETE_SQL =
            "DELETE FROM dining.dining_roster_items WHERE id = \$1"

        /**
         * 配餐口径（启用档案 ∩ 在院入住）的共同 FROM/WHERE 片段。
         *
         * **在院口径是显式的**：`p JOIN e` 为 INNER JOIN + `e.status = 'ACTIVE'`；
         * 另显式写出 `p.encounter_id IS NOT NULL`（被该 INNER JOIN 逻辑蕴含，**行为逐字不变**），
         * 把「儿童档案（A4b 后 `encounter_id IS NULL`）**有意**不进养老配餐名单」固定为代码意图，
         * 防止后人误改成 LEFT JOIN 时静默回归。
         * **不得**再加 `e.encounter_type = 'ELDERLY_CARE'` / `pa.person_type <> '儿童'`：
         * 现状没有 encounter_type 过滤，加它们会排除「绑定 ACTIVE 非 ELDERLY_CARE 入住」的档案 = 行为变更。
         */
        private const val EXPECTED_PROFILES_SQL =
            "SELECT p.patient_id AS patient_id, p.encounter_id AS encounter_id, " +
                "p.meal_type AS meal_type, p.allergies AS allergies, pa.name AS patient_name " +
                "FROM dining.dining_diet_profiles p " +
                "JOIN healthcare.encounters e ON e.id = p.encounter_id " +
                "JOIN healthcare.patients pa ON pa.id = p.patient_id " +
                "WHERE p.status = \$1 AND p.encounter_id IS NOT NULL AND e.status = \$2"

        private const val MANUAL_PROFILE_SQL =
            "SELECT p.encounter_id AS encounter_id, p.meal_type AS meal_type, " +
                "p.allergies AS allergies, pa.name AS patient_name " +
                "FROM dining.dining_diet_profiles p " +
                "JOIN healthcare.encounters e ON e.id = p.encounter_id " +
                "JOIN healthcare.patients pa ON pa.id = p.patient_id " +
                "WHERE p.patient_id = \$1 AND p.status = \$2 " +
                "AND p.encounter_id IS NOT NULL AND e.status = \$3"

        private const val PATIENT_NAME_SQL =
            "SELECT name FROM healthcare.patients WHERE id = \$1"

        /** 手工条目无档案时的缺省餐食类型。 */
        private const val DEFAULT_MEAL_TYPE = "普食"

        private const val PROFILE_STATUS = "启用"
        private const val ENCOUNTER_STATUS = "ACTIVE"
        private const val SOURCE_AUTO = "自动"
        private const val SOURCE_MANUAL = "手工"
        private const val ADJUST_EXTRA_MEAL = "临时加餐"
        private const val MANUAL_SORT_ORDER = 999

        fun rosterJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("menu_date", row.getValue("menu_date")?.toString())
            .put("meal_time", row.getValue("meal_time")?.toString())
            .put("generated_by", row.getValue("generated_by")?.toString())
            .put("generated_at", row.getValue("generated_at")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())

        fun itemJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("roster_id", row.getValue("roster_id")?.toString())
            .put("patient_id", row.getValue("patient_id")?.toString())
            .put("encounter_id", row.getValue("encounter_id")?.toString())
            .put("patient_name", row.getValue("patient_name")?.toString())
            .put("meal_type", row.getValue("meal_type")?.toString())
            .put("allergies", row.getValue("allergies") as? JsonArray ?: JsonArray())
            .put("source", row.getValue("source")?.toString())
            .put("adjust_type", row.getValue("adjust_type")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("sort_order", row.getValue("sort_order") as? Int ?: 0)
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
            .put("execution", executionJson(row))

        /** 与 roster items 联查时带出的执行记录（无则 null） */
        private fun executionJson(row: Row): JsonObject? {
            val execStatus = row.getValue("exec_status")?.toString() ?: return null
            return JsonObject()
                .put("status", execStatus)
                .put("remark", row.getValue("exec_remark")?.toString())
                .put("recorded_by", row.getValue("recorded_by")?.toString())
                .put("recorded_at", row.getValue("recorded_at")?.toString())
        }

        private fun itemsQuery(whereClause: String): String =
            "SELECT $ITEM_COLUMNS$ITEM_FROM $whereClause"
    }

    // ========================================================================
    //  名单生成
    // ========================================================================

    /**
     * 生成（或刷新）某日某餐次的配餐名单。
     * 返回 { roster, created, updated, skipped, total }。
     */
    fun generate(date: String, mealTime: String, remark: String?, userId: String?): Future<JsonObject> {
        val d = parseDate(date, "date")
        if (mealTime.isNullOrBlank() || mealTime !in DiningConstants.MEAL_TIMES)
            return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))

        return getOrCreateRoster(d, mealTime, remark, userId).compose { roster ->
            val rosterId = roster.getString("id")
            loadExpectedProfiles().compose { expected ->
                loadItemsByPatient(rosterId).compose { existingItems ->
                    var created = 0
                    var updated = 0
                    var skipped = 0
                    var chain = Future.succeededFuture<RowSet<Row>>()

                    for (profile in expected) {
                        val patientId = profile.getString("patient_id")
                        val existing = existingItems[patientId]
                        when {
                            existing == null -> {
                                chain = chain.compose {
                                    insertAutoItem(rosterId, profile, existingItems.size + created)
                                }
                                created++
                            }
                            existing.getString("source") == SOURCE_MANUAL -> skipped++
                            existing.getJsonObject("execution") != null -> skipped++
                            else -> {
                                chain = chain.compose {
                                    refreshAutoItem(existing.getString("id"), profile)
                                }
                                updated++
                            }
                        }
                    }

                    chain.map {
                        JsonObject()
                            .put("created", created)
                            .put("updated", updated)
                            .put("skipped", skipped)
                            .put("total", existingItems.size + created)
                    }.compose { counters ->
                        get(rosterId).map { roster -> counters.put("roster", roster) }
                    }
                }
            }
        }
    }

    /**
     * 名单容器：按 (日期, 餐次) 幂等查找或创建。
     *
     * 已存在时不是原样返回，而是先 [refreshGeneration] 把「生成人/生成时间」推进到本次：
     * 重新生成（同一日期+餐次再调一次）既要刷新条目，也要刷新名单头上的生成留痕，
     * 否则列表一直停在首次生成的人与时刻（UI 两列就是 `generated_by` / `generated_at`）。
     */
    private fun getOrCreateRoster(
        date: LocalDate,
        mealTime: String,
        remark: String?,
        userId: String?,
    ): Future<JsonObject> {
        val lookupSql =
            "SELECT * FROM dining.dining_rosters WHERE menu_date = \$1 AND meal_time = \$2"
        return pool.preparedQuery(lookupSql).execute(Tuple.of(date, mealTime)).compose { rows ->
            val existingId = rows.iterator().asSequence().firstOrNull()?.getValue("id")?.toString()
            if (existingId != null) return@compose refreshGeneration(existingId, userId)
            val id = Ulid.generate()
            val now = OffsetDateTime.now()
            pool.preparedQuery(ROSTER_INSERT_SQL).execute(
                Tuple.of(id, date, mealTime, userId, now, remark, JsonObject(), now, now)
            ).compose { result ->
                if (result.rowCount() > 0) {
                    get(id)
                } else {
                    // 并发创建冲突：既有名单同样按本次生成刷新生成留痕
                    pool.preparedQuery(lookupSql).execute(Tuple.of(date, mealTime)).compose { rows2 ->
                        val concurrentId = rows2.iterator().asSequence().firstOrNull()?.getValue("id")?.toString()
                        if (concurrentId == null) get(id) else refreshGeneration(concurrentId, userId)
                    }
                }
            }
        }
    }

    /**
     * 刷新名单容器的生成留痕（重新生成路径）。
     *
     * 语义：`generated_at` = **最近一次**生成/刷新时刻，`generated_by` = 最近一次触发的主体，
     * 与 UI 「生成人/生成时间」两列逐字对应。
     *
     * 主体未知时（无认证挂载的嵌入式/直接服务调用）**不覆盖** `generated_by`，
     * 沿用 042「操作人未知则不写」口径，保留上一个已知的生成人；
     * 但 `generated_at`/`updated_at` 仍推进——本次生成确实发生了。
     */
    private fun refreshGeneration(rosterId: String, userId: String?): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val generator = userId?.trim()?.takeIf(String::isNotBlank)
        val sets = mutableListOf("generated_at = \$1", "updated_at = \$2")
        val args = mutableListOf<Any?>(now, now)
        if (generator != null) {
            sets.add("generated_by = \$3")
            args.add(generator)
        }
        args.add(rosterId)
        val sql =
            "UPDATE dining.dining_rosters SET ${sets.joinToString(", ")} WHERE id = \$${args.size}"
        return pool.preparedQuery(sql).execute(Tuple.from(args)).compose { get(rosterId) }
    }

    /** 启用档案 ∩ 在院入住 的长者（配餐口径，见 [EXPECTED_PROFILES_SQL]）。 */
    private fun loadExpectedProfiles(): Future<List<JsonObject>> =
        pool.preparedQuery(EXPECTED_PROFILES_SQL)
            .execute(Tuple.of(PROFILE_STATUS, ENCOUNTER_STATUS))
            .map { rows ->
                rows.map { row ->
                    JsonObject()
                        .put("patient_id", row.getValue("patient_id")?.toString())
                        .put("encounter_id", row.getValue("encounter_id")?.toString())
                        .put("meal_type", row.getValue("meal_type")?.toString())
                        .put("allergies", row.getValue("allergies") as? JsonArray ?: JsonArray())
                        .put("patient_name", row.getValue("patient_name")?.toString())
                }
            }

    /** 名单现有条目（含执行状态），按 patient_id 索引 */
    private fun loadItemsByPatient(rosterId: String): Future<Map<String, JsonObject>> =
        pool.preparedQuery(itemsQuery("WHERE i.roster_id = \$1")).execute(Tuple.of(rosterId))
            .map { rows ->
                rows.associate { (it.getString("patient_id") ?: "") to itemJson(it) }
            }

    private fun insertAutoItem(rosterId: String, profile: JsonObject, position: Int): Future<RowSet<Row>> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.preparedQuery(AUTO_ITEM_INSERT_SQL).execute(
            Tuple.of(
                id, rosterId, profile.getString("patient_id"), profile.getString("encounter_id"),
                profile.getString("patient_name"), profile.getString("meal_type"),
                profile.getJsonArray("allergies") ?: JsonArray(), SOURCE_AUTO, position, JsonObject(), now, now,
            )
        )
    }

    private fun refreshAutoItem(itemId: String, profile: JsonObject): Future<RowSet<Row>> =
        pool.preparedQuery(AUTO_ITEM_REFRESH_SQL).execute(
            Tuple.of(
                profile.getString("patient_name"), profile.getString("meal_type"),
                profile.getJsonArray("allergies") ?: JsonArray(), OffsetDateTime.now(), itemId,
            )
        )

    // ========================================================================
    //  名单查询
    // ========================================================================

    fun list(
        date: String? = null,
        mealTime: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        date?.takeIf(String::isNotBlank)?.let { where.add("menu_date = ${bind(parseDate(it, "date"))}") }
        mealTime?.takeIf(String::isNotBlank)?.let {
            if (it !in DiningConstants.MEAL_TIMES)
                return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))
            where.add("meal_time = ${bind(it)}")
        }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM dining.dining_rosters$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $ROSTER_COLUMNS FROM dining.dining_rosters$whereClause " +
                "ORDER BY menu_date DESC, meal_time DESC, created_at DESC " +
                "LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).compose { dataRows ->
                val heads = dataRows.map { rosterJson(it) }
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

    /** 列表页「名单人数」：按 roster_id 统计条目数，避免列表接口带出全部明细。 */
    private fun countItems(rosterIds: List<String>): Future<Map<String, Long>> {
        if (rosterIds.isEmpty()) return Future.succeededFuture(emptyMap())
        val placeholders = rosterIds.indices.joinToString(", ") { "\$${it + 1}" }
        val sql =
            "SELECT roster_id, count(*) AS item_count FROM dining.dining_roster_items " +
                "WHERE roster_id IN ($placeholders) GROUP BY roster_id"
        return pool.preparedQuery(sql).execute(Tuple.from(rosterIds)).map { rows ->
            rows.associate { it.getString("roster_id") to (it.getLong("item_count") ?: 0L) }
        }
    }

    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT * FROM dining.dining_rosters WHERE id = \$1")
            .execute(Tuple.of(id))
            .compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row == null)
                    return@compose Future.failedFuture(DiningNotFoundException("roster not found: $id"))
                loadItems(id).map { itemRows ->
                    rosterJson(row).put("items", JsonArray(itemRows.map(::itemJson)))
                }
            }

    private fun loadItems(rosterId: String): Future<RowSet<Row>> =
        pool.preparedQuery(
            itemsQuery("WHERE i.roster_id = \$1 ORDER BY i.sort_order ASC, i.created_at ASC")
        ).execute(Tuple.of(rosterId))

    // ========================================================================
    //  手工调整（增删）
    // ========================================================================

    /**
     * 手工新增条目：
     * - 外出/请假：已有条目则改标（含自动条目），否则新建手工条目标记；
     * - 临时加餐：长者须不在名单中，新建手工条目。
     */
    fun addItem(rosterId: String, body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")
        if (patientId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        val adjustType = body.getString("adjust_type")
        if (adjustType.isNullOrBlank() || adjustType !in DiningConstants.ADJUST_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid adjust_type, must be one of: ${DiningConstants.ADJUST_TYPES}"))
        val remark = body.getString("remark")

        return get(rosterId).flatMap {
            loadItemByPatient(rosterId, patientId).compose { existing ->
                when {
                    adjustType == ADJUST_EXTRA_MEAL && existing != null ->
                        Future.failedFuture(DiningConflictException("该长者已在名单中"))
                    existing != null -> {
                        // 外出/请假：改标既有条目
                        val itemId = existing.getString("id")
                        pool.preparedQuery(ADJUST_ITEM_SQL)
                            .execute(Tuple.of(adjustType, remark, OffsetDateTime.now(), itemId))
                            .flatMap { loadItemById(itemId) }
                    }
                    else -> buildManualItem(patientId).compose { profile ->
                        val id = Ulid.generate()
                        val now = OffsetDateTime.now()
                        pool.preparedQuery(MANUAL_ITEM_INSERT_SQL).execute(
                            Tuple.of(
                                id, rosterId, patientId, profile.getString("encounter_id"),
                                profile.getString("patient_name"), profile.getString("meal_type"),
                                profile.getJsonArray("allergies") ?: JsonArray(), SOURCE_MANUAL,
                                adjustType, remark, MANUAL_SORT_ORDER, JsonObject(), now, now,
                            )
                        ).compose { result ->
                            if (result.rowCount() == 0)
                                Future.failedFuture(DiningConflictException("该长者已在名单中"))
                            else loadItemById(id)
                        }
                    }
                }
            }
        }
    }

    /** 手工条目删除；自动条目与已登记就餐的条目不可删除（留痕）。 */
    fun removeItem(rosterId: String, itemId: String): Future<Void?> {
        return loadItemById(itemId).compose { item ->
            if (item.getString("roster_id") != rosterId)
                return@compose Future.failedFuture(DiningNotFoundException("roster item not found: $itemId"))
            if (item.getString("source") == SOURCE_AUTO)
                return@compose Future.failedFuture(DiningConflictException("自动生成条目请使用外出/请假标记，不可删除"))
            if (item.getJsonObject("execution") != null)
                return@compose Future.failedFuture(DiningConflictException("该条目已登记就餐，无法删除，请改用外出/请假标记"))
            pool.preparedQuery(ITEM_DELETE_SQL).execute(Tuple.of(itemId)).map<Void?> { null }
        }
    }

    private fun loadItemByPatient(rosterId: String, patientId: String): Future<JsonObject?> =
        pool.preparedQuery(itemsQuery("WHERE i.roster_id = \$1 AND i.patient_id = \$2"))
            .execute(Tuple.of(rosterId, patientId))
            .map { rows -> rows.iterator().asSequence().firstOrNull()?.let { itemJson(it) } }

    private fun loadItemById(itemId: String): Future<JsonObject> =
        pool.preparedQuery(itemsQuery("WHERE i.id = \$1")).execute(Tuple.of(itemId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(DiningNotFoundException("roster item not found: $itemId"))
            else Future.succeededFuture(itemJson(row))
        }

    /** 手工条目资料：优先取启用档案（在院口径），无档案则取患者姓名，餐食类型默认普食。 */
    private fun buildManualItem(patientId: String): Future<JsonObject> =
        pool.preparedQuery(MANUAL_PROFILE_SQL)
            .execute(Tuple.of(patientId, PROFILE_STATUS, ENCOUNTER_STATUS))
            .compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row != null)
                    return@compose Future.succeededFuture(
                        JsonObject()
                            .put("encounter_id", row.getValue("encounter_id")?.toString())
                            .put("patient_name", row.getValue("patient_name")?.toString())
                            .put("meal_type", row.getValue("meal_type")?.toString())
                            .put("allergies", row.getValue("allergies") as? JsonArray ?: JsonArray())
                    )
                // 无启用档案：只取患者姓名（如为离院长者临时加餐场景）
                pool.preparedQuery(PATIENT_NAME_SQL).execute(Tuple.of(patientId)).compose { patientRows ->
                    val patientRow = patientRows.iterator().asSequence().firstOrNull()
                    if (patientRow == null)
                        return@compose Future.failedFuture(IllegalArgumentException("patient not found: $patientId"))
                    Future.succeededFuture(
                        JsonObject()
                            .put("encounter_id", null)
                            .put("patient_name", patientRow.getString("name"))
                            .put("meal_type", DEFAULT_MEAL_TYPE)
                            .put("allergies", JsonArray())
                    )
                }
            }

    private fun parseDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }
}
