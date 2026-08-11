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
 * 配餐名单（FR-4）：按「日期 + 餐次」生成当日就餐长者名单。
 * - 依据启用饮食档案自动生成条目，快照长者姓名/餐食类型/忌口；
 * - 只纳入在院（ACTIVE）ELDERLY_CARE 入住，离院长者自动排除；
 * - 重复生成幂等：自动条目未登记就餐时刷新快照，手工调整条目不受影响；
 * - 手工调整：外出/请假（标记本餐不就餐）、临时加餐（新增）、删除手工条目；
 *   已登记就餐的条目不可删除（改用外出/请假标记），保证留痕。
 */
class RosterService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = DSL.table(DSL.name("dining", "dining_rosters"))
    private val cId = DSL.field("id", String::class.java)
    private val cMenuDate = DSL.field("menu_date", LocalDate::class.java)
    private val cMealTime = DSL.field("meal_time", String::class.java)
    private val cGeneratedBy = DSL.field("generated_by", String::class.java)
    private val cGeneratedAt = DSL.field("generated_at", OffsetDateTime::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    private val items = DSL.table(DSL.name("dining", "dining_roster_items"))
    private val cRosterId = DSL.field("roster_id", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cPatientName = DSL.field("patient_name", String::class.java)
    private val cMealType = DSL.field("meal_type", String::class.java)
    private val cAllergies = DSL.field("allergies", JSONB::class.java)
    private val cSource = DSL.field("source", String::class.java)
    private val cAdjustType = DSL.field("adjust_type", String::class.java)
    private val cSortOrder = DSL.field("sort_order", Int::class.java)

    private val executions = DSL.table(DSL.name("dining", "dining_meal_executions"))
    private val cRosterItemId = DSL.field("roster_item_id", String::class.java)
    private val cExecStatus = DSL.field("status", String::class.java)
    private val cExecRemark = DSL.field("remark", String::class.java)
    private val cRecordedBy = DSL.field("recorded_by", String::class.java)
    private val cRecordedAt = DSL.field("recorded_at", OffsetDateTime::class.java)

    private val profiles = DSL.table(DSL.name("dining", "dining_diet_profiles"))
    private val cProfileStatus = DSL.field(DSL.name("dining", "dining_diet_profiles", "status"), String::class.java)

    private val encounters = DSL.table(DSL.name("healthcare", "encounters"))
    private val cEncounterStatus = DSL.field(DSL.name("healthcare", "encounters", "status"), String::class.java)

    private val patients = DSL.table(DSL.name("healthcare", "patients"))
    private val cName = DSL.field(DSL.name("healthcare", "patients", "name"), String::class.java)

    companion object {
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
                    var chain = Future.succeededFuture<io.vertx.sqlclient.RowSet<Row>>()

                    for (profile in expected) {
                        val patientId = profile.getString("patient_id")
                        val existing = existingItems[patientId]
                        when {
                            existing == null -> {
                                chain = chain.compose {
                                    execute(pool, insertAutoItem(rosterId, profile, existingItems.size + created))
                                        .map { it }
                                }
                                created++
                            }
                            existing.getString("source") == "手工" -> skipped++
                            existing.getJsonObject("execution") != null -> skipped++
                            else -> {
                                chain = chain.compose {
                                    execute(pool, refreshAutoItem(existing.getString("id"), profile)).map { it }
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

    /** 名单容器：按 (日期, 餐次) 幂等查找或创建 */
    private fun getOrCreateRoster(date: LocalDate, mealTime: String, remark: String?, userId: String?): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cMenuDate.eq(date).and(cMealTime.eq(mealTime)))
        return execute(pool, query).compose { rows ->
            val existing = rows.iterator().asSequence().firstOrNull()
            if (existing != null)
                return@compose Future.succeededFuture(rosterJson(existing))
            val id = Ulid.generate()
            val now = OffsetDateTime.now()
            val insert = ctx.insertInto(t)
                .set(cId, id)
                .set(cMenuDate, date)
                .set(cMealTime, mealTime)
                .set(cGeneratedBy, userId)
                .set(cGeneratedAt, now)
                .set(cRemark, remark)
                .set(cMetadata, JSONB.valueOf("{}"))
                .set(cCreatedAt, now)
                .set(cUpdatedAt, now)
                .onConflict(cMenuDate, cMealTime)
                .doNothing()
            execute(pool, insert).compose { result ->
                if (result.rowCount() > 0) {
                    get(id)
                } else {
                    // 并发创建冲突：读取既有名单
                    execute(pool, query).map { rows2 ->
                        rosterJson(rows2.iterator().next())
                    }
                }
            }
        }
    }

    /** 启用档案 ∩ 在院入住 的长者（配餐口径） */
    private fun loadExpectedProfiles(): Future<List<JsonObject>> {
        val p = profiles.`as`("p")
        val e = encounters.`as`("e")
        val pa = patients.`as`("pa")
        val query = ctx.select(
            DSL.field(DSL.name("p", "patient_id")).`as`("patient_id"),
            DSL.field(DSL.name("p", "encounter_id")).`as`("encounter_id"),
            DSL.field(DSL.name("p", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("p", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("pa", "name")).`as`("patient_name"),
        )
            .from(p)
            .join(e).on(DSL.field(DSL.name("p", "encounter_id")).eq(DSL.field(DSL.name("e", "id"))))
            .join(pa).on(DSL.field(DSL.name("p", "patient_id")).eq(DSL.field(DSL.name("pa", "id"))))
            .where(DSL.field(DSL.name("p", "status")).eq("启用").and(DSL.field(DSL.name("e", "status")).eq("ACTIVE")))
        return execute(pool, query).map { rows ->
            rows.map { row ->
                JsonObject()
                    .put("patient_id", row.getValue("patient_id")?.toString())
                    .put("encounter_id", row.getValue("encounter_id")?.toString())
                    .put("meal_type", row.getValue("meal_type")?.toString())
                    .put("allergies", row.getValue("allergies") as? JsonArray ?: JsonArray())
                    .put("patient_name", row.getValue("patient_name")?.toString())
            }
        }
    }

    /** 名单现有条目（含执行状态），按 patient_id 索引 */
    private fun loadItemsByPatient(rosterId: String): Future<Map<String, JsonObject>> {
        val query = itemsQuery().where(cRosterId.eq(rosterId))
        return execute(pool, query).map { rows ->
            rows.associate { (it.getString("patient_id") ?: "") to itemJson(it) }
        }
    }

    private fun itemsQuery() = ctx.select(
        DSL.field(DSL.name("i", "id")).`as`("id"),
        DSL.field(DSL.name("i", "roster_id")).`as`("roster_id"),
        DSL.field(DSL.name("i", "patient_id")).`as`("patient_id"),
        DSL.field(DSL.name("i", "encounter_id")).`as`("encounter_id"),
        DSL.field(DSL.name("i", "patient_name")).`as`("patient_name"),
        DSL.field(DSL.name("i", "meal_type")).`as`("meal_type"),
        DSL.field(DSL.name("i", "allergies")).`as`("allergies"),
        DSL.field(DSL.name("i", "source")).`as`("source"),
        DSL.field(DSL.name("i", "adjust_type")).`as`("adjust_type"),
        DSL.field(DSL.name("i", "remark")).`as`("remark"),
        DSL.field(DSL.name("i", "sort_order")).`as`("sort_order"),
        DSL.field(DSL.name("i", "created_at")).`as`("created_at"),
        DSL.field(DSL.name("i", "updated_at")).`as`("updated_at"),
        DSL.field(DSL.name("e", "status")).`as`("exec_status"),
        DSL.field(DSL.name("e", "remark")).`as`("exec_remark"),
        DSL.field(DSL.name("e", "recorded_by")).`as`("recorded_by"),
        DSL.field(DSL.name("e", "recorded_at")).`as`("recorded_at"),
    )
        .from(items.`as`("i"))
        .leftOuterJoin(executions.`as`("e")).on(DSL.field(DSL.name("e", "roster_item_id")).eq(DSL.field(DSL.name("i", "id"))))

    private fun insertAutoItem(rosterId: String, profile: JsonObject, position: Int): org.jooq.Query {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return ctx.insertInto(items)
            .set(cId, id)
            .set(cRosterId, rosterId)
            .set(cPatientId, profile.getString("patient_id"))
            .set(cEncounterId, profile.getString("encounter_id"))
            .set(cPatientName, profile.getString("patient_name"))
            .set(cMealType, profile.getString("meal_type"))
            .set(cAllergies, JSONB.valueOf(profile.getJsonArray("allergies").encode()))
            .set(cSource, "自动")
            .set(cSortOrder, position)
            .set(cMetadata, JSONB.valueOf("{}"))
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)
            .onConflict(cRosterId, cPatientId)
            .doNothing()
    }

    private fun refreshAutoItem(itemId: String, profile: JsonObject): org.jooq.Query =
        ctx.update(items)
            .set(cPatientName, profile.getString("patient_name"))
            .set(cMealType, profile.getString("meal_type"))
            .set(cAllergies, JSONB.valueOf(profile.getJsonArray("allergies").encode()))
            .set(cUpdatedAt, OffsetDateTime.now())
            .where(cId.eq(itemId))

    // ========================================================================
    //  名单查询
    // ========================================================================

    fun list(
        date: String? = null,
        mealTime: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        date?.takeIf(String::isNotBlank)?.let { conditions.add(cMenuDate.eq(parseDate(it, "date"))) }
        mealTime?.takeIf(String::isNotBlank)?.let {
            if (it !in DiningConstants.MEAL_TIMES)
                return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))
            conditions.add(cMealTime.eq(it))
        }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t).where(conditions)
            .orderBy(cMenuDate.desc(), cMealTime.desc(), cCreatedAt.desc())
            .limit(limit).offset(offset)

        return execute(pool, countQuery).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(rosterJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        val rosterQuery = ctx.selectFrom(t).where(cId.eq(id))
        return execute(pool, rosterQuery).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                return@compose Future.failedFuture(DiningNotFoundException("roster not found: $id"))
            loadItems(id).map { itemRows ->
                rosterJson(row).put("items", JsonArray(itemRows.map(::itemJson)))
            }
        }
    }

    private fun loadItems(rosterId: String): Future<io.vertx.sqlclient.RowSet<Row>> {
        val query = itemsQuery().where(cRosterId.eq(rosterId))
            .orderBy(DSL.field(DSL.name("i", "sort_order")).asc(), DSL.field(DSL.name("i", "created_at")).asc())
        return execute(pool, query)
    }

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

        return get(rosterId).flatMap { roster ->
            loadItemByPatient(rosterId, patientId).compose { existing ->
                when {
                    adjustType == "临时加餐" && existing != null ->
                        Future.failedFuture(DiningConflictException("该长者已在名单中"))
                    existing != null -> {
                        // 外出/请假：改标既有条目
                        val query = ctx.update(items)
                            .set(cAdjustType, adjustType)
                            .set(cRemark, remark)
                            .set(cUpdatedAt, OffsetDateTime.now())
                            .where(cId.eq(existing.getString("id")))
                        execute(pool, query).flatMap { loadItemById(existing.getString("id")) }
                    }
                    else -> buildManualItem(patientId, adjustType, remark).compose { profile ->
                        val id = Ulid.generate()
                        val now = OffsetDateTime.now()
                        val insert = ctx.insertInto(items)
                            .set(cId, id)
                            .set(cRosterId, rosterId)
                            .set(cPatientId, patientId)
                            .set(cEncounterId, profile.getString("encounter_id"))
                            .set(cPatientName, profile.getString("patient_name"))
                            .set(cMealType, profile.getString("meal_type"))
                            .set(cAllergies, JSONB.valueOf(profile.getJsonArray("allergies").encode()))
                            .set(cSource, "手工")
                            .set(cAdjustType, adjustType)
                            .set(cRemark, remark)
                            .set(cSortOrder, 999)
                            .set(cMetadata, JSONB.valueOf("{}"))
                            .set(cCreatedAt, now)
                            .set(cUpdatedAt, now)
                            .onConflict(cRosterId, cPatientId)
                            .doNothing()
                        execute(pool, insert).compose { result ->
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
            if (item.getString("source") == "自动")
                return@compose Future.failedFuture(DiningConflictException("自动生成条目请使用外出/请假标记，不可删除"))
            if (item.getJsonObject("execution") != null)
                return@compose Future.failedFuture(DiningConflictException("该条目已登记就餐，无法删除，请改用外出/请假标记"))
            val query = ctx.deleteFrom(items).where(cId.eq(itemId))
            execute(pool, query).map<Void?> { null }
        }
    }

    private fun loadItemByPatient(rosterId: String, patientId: String): Future<JsonObject?> {
        val query = itemsQuery().where(cRosterId.eq(rosterId).and(DSL.field(DSL.name("i", "patient_id")).eq(patientId)))
        return execute(pool, query).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { itemJson(it) }
        }
    }

    private fun loadItemById(itemId: String): Future<JsonObject> {
        val query = itemsQuery().where(DSL.field(DSL.name("i", "id")).eq(itemId))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(DiningNotFoundException("roster item not found: $itemId"))
            else Future.succeededFuture(itemJson(row))
        }
    }

    /** 手工条目资料：优先取启用档案（在院口径），无档案则取患者姓名，餐食类型默认普食。 */
    private fun buildManualItem(patientId: String, adjustType: String, remark: String?): Future<JsonObject> {
        val p = profiles.`as`("p")
        val e = encounters.`as`("e")
        val pa = patients.`as`("pa")
        val query = ctx.select(
            DSL.field(DSL.name("p", "encounter_id")).`as`("encounter_id"),
            DSL.field(DSL.name("p", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("p", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("pa", "name")).`as`("patient_name"),
        )
            .from(p)
            .join(e).on(DSL.field(DSL.name("p", "encounter_id")).eq(DSL.field(DSL.name("e", "id"))))
            .join(pa).on(DSL.field(DSL.name("p", "patient_id")).eq(DSL.field(DSL.name("pa", "id"))))
            .where(DSL.field(DSL.name("p", "patient_id")).eq(patientId)
                .and(DSL.field(DSL.name("p", "status")).eq("启用"))
                .and(DSL.field(DSL.name("e", "status")).eq("ACTIVE")))

        return execute(pool, query).compose { rows ->
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
            val patientQuery = ctx.select(cName).from(patients).where(DSL.field("id").eq(patientId))
            execute(pool, patientQuery).compose { patientRows ->
                val patientRow = patientRows.iterator().asSequence().firstOrNull()
                if (patientRow == null)
                    return@compose Future.failedFuture(IllegalArgumentException("patient not found: $patientId"))
                Future.succeededFuture(
                    JsonObject()
                        .put("encounter_id", null)
                        .put("patient_name", patientRow.getString("name"))
                        .put("meal_type", "普食")
                        .put("allergies", JsonArray())
                )
            }
        }
    }

    private fun parseDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
