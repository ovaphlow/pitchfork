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
 * 就餐执行登记（FR-5）：护理员按名单登记实际就餐状态（正常/部分/未就餐/拒食）。
 * 同一长者同一餐次（同一名单条目）幂等更新：重复登记覆盖状态、备注、
 * 登记人与登记时间，不产生重复记录。
 */
class MealExecutionService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = DSL.table(DSL.name("dining", "dining_meal_executions"))
    private val cId = DSL.field("id", String::class.java)
    private val cRosterItemId = DSL.field("roster_item_id", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cRecordedBy = DSL.field("recorded_by", String::class.java)
    private val cRecordedAt = DSL.field("recorded_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)

    private val rosterItems = DSL.table(DSL.name("dining", "dining_roster_items"))
    private val rosters = DSL.table(DSL.name("dining", "dining_rosters"))

    companion object {
        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("roster_item_id", row.getValue("roster_item_id")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("recorded_by", row.getValue("recorded_by")?.toString())
            .put("recorded_at", row.getValue("recorded_at")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            // 联查字段（list 接口带出；getById 单表查询时缺列返回 null）
            .put("menu_date", valueOrNull(row, "menu_date")?.toString())
            .put("meal_time", valueOrNull(row, "meal_time")?.toString())
            .put("patient_id", valueOrNull(row, "patient_id")?.toString())
            .put("patient_name", valueOrNull(row, "patient_name")?.toString())
            .put("meal_type", valueOrNull(row, "meal_type")?.toString())
            .put("allergies", valueOrNull(row, "allergies") as? JsonArray ?: JsonArray())
            .put("adjust_type", valueOrNull(row, "adjust_type")?.toString())

        /** 按列名取值；查询未包含该列时返回 null（而非抛异常）。 */
        private fun valueOrNull(row: Row, name: String): Any? =
            if (row.getColumnIndex(name) >= 0) row.getValue(name) else null
    }

    /**
     * 登记（幂等）：roster_item_id 唯一，重复登记同一长者同一餐次为更新。
     * [userId] 为认证主体（登记人）。
     */
    fun register(rosterItemId: String, status: String, remark: String?, userId: String): Future<JsonObject> {
        if (rosterItemId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("roster_item_id is required"))
        if (status.isNullOrBlank() || status !in DiningConstants.MEAL_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.MEAL_STATUSES}"))

        val itemQuery = ctx.selectOne().from(rosterItems).where(DSL.field("id").eq(rosterItemId))
        return execute(pool, itemQuery).compose { rows ->
            if (rows.size() == 0)
                return@compose Future.failedFuture(DiningNotFoundException("roster item not found: $rosterItemId"))

            val id = Ulid.generate()
            val now = OffsetDateTime.now()
            val query = ctx.insertInto(t)
                .set(cId, id)
                .set(cRosterItemId, rosterItemId)
                .set(cStatus, status)
                .set(cRemark, remark)
                .set(cRecordedBy, userId)
                .set(cRecordedAt, now)
                .set(cUpdatedAt, now)
                .set(cMetadata, JSONB.valueOf("{}"))
                .onConflict(cRosterItemId)
                .doUpdate()
                .set(cStatus, status)
                .set(cRemark, remark)
                .set(cRecordedBy, userId)
                .set(cRecordedAt, now)
                .set(cUpdatedAt, now)
                .returning(cId)

            execute(pool, query).compose { result ->
                val row = result.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(IllegalStateException("execution upsert returned no row"))
                getById(row.getString("id") ?: id)
            }
        }
    }

    fun list(
        date: String? = null,
        mealTime: String? = null,
        status: String? = null,
        patientId: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        date?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("r", "menu_date")).eq(parseDate(it))) }
        mealTime?.takeIf(String::isNotBlank)?.let {
            if (it !in DiningConstants.MEAL_TIMES)
                return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))
            conditions.add(DSL.field(DSL.name("r", "meal_time")).eq(it))
        }
        status?.takeIf(String::isNotBlank)?.let {
            if (it !in DiningConstants.MEAL_STATUSES)
                return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.MEAL_STATUSES}"))
            conditions.add(DSL.field(DSL.name("x", "status")).eq(it))
        }
        patientId?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("i", "patient_id")).eq(it)) }

        val x = t.`as`("x")
        val i = rosterItems.`as`("i")
        val r = rosters.`as`("r")

        val base = x
            .join(i).on(DSL.field(DSL.name("x", "roster_item_id")).eq(DSL.field(DSL.name("i", "id"))))
            .join(r).on(DSL.field(DSL.name("i", "roster_id")).eq(DSL.field(DSL.name("r", "id"))))

        val countQuery = ctx.select(count().`as`("total")).from(base).where(conditions)
        val dataQuery = ctx.select(
            DSL.field(DSL.name("x", "id")).`as`("id"),
            DSL.field(DSL.name("x", "roster_item_id")).`as`("roster_item_id"),
            DSL.field(DSL.name("x", "status")).`as`("status"),
            DSL.field(DSL.name("x", "remark")).`as`("remark"),
            DSL.field(DSL.name("x", "recorded_by")).`as`("recorded_by"),
            DSL.field(DSL.name("x", "recorded_at")).`as`("recorded_at"),
            DSL.field(DSL.name("x", "metadata")).`as`("metadata"),
            DSL.field(DSL.name("r", "menu_date")).`as`("menu_date"),
            DSL.field(DSL.name("r", "meal_time")).`as`("meal_time"),
            DSL.field(DSL.name("i", "patient_id")).`as`("patient_id"),
            DSL.field(DSL.name("i", "patient_name")).`as`("patient_name"),
            DSL.field(DSL.name("i", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("i", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("i", "adjust_type")).`as`("adjust_type"),
        ).from(base).where(conditions)
            .orderBy(DSL.field(DSL.name("r", "menu_date")).desc(), DSL.field(DSL.name("r", "meal_time")).desc(), DSL.field(DSL.name("x", "recorded_at")).desc())
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

    private fun getById(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(t).where(cId.eq(id))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(DiningNotFoundException("meal execution not found: $id"))
            else Future.succeededFuture(toJson(row))
        }
    }

    private fun parseDate(value: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("date must be a valid ISO date (yyyy-MM-dd)")
        }

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
