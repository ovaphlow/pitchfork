package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 就餐执行登记（FR-5）：护理员按名单登记实际就餐状态（正常/部分/未就餐/拒食）。
 * 同一长者同一餐次（同一名单条目）幂等更新：重复登记覆盖状态、备注、
 * 登记人与登记时间，不产生重复记录。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class MealExecutionService(
    private val pool: Pool,
) {

    companion object {
        /** 单表查询列（`SELECT *` 的显式列清单，顺序与 [toJson] 一致）。 */
        private const val EXECUTION_COLUMNS =
            "id, roster_item_id, status, remark, recorded_by, recorded_at, metadata"

        private const val ROSTER_ITEM_SQL =
            "SELECT 1 FROM dining.dining_roster_items WHERE id = \$1"

        private const val UPSERT_SQL =
            "INSERT INTO dining.dining_meal_executions " +
                "(id, roster_item_id, status, remark, recorded_by, recorded_at, updated_at, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8) " +
                "ON CONFLICT (roster_item_id) DO UPDATE SET " +
                "status = \$9, remark = \$10, recorded_by = \$11, recorded_at = \$12, updated_at = \$13 " +
                "RETURNING id"

        private const val LIST_COLUMNS =
            "x.id AS id, x.roster_item_id AS roster_item_id, x.status AS status, x.remark AS remark, " +
                "x.recorded_by AS recorded_by, x.recorded_at AS recorded_at, x.metadata AS metadata, " +
                "r.menu_date AS menu_date, r.meal_time AS meal_time, i.patient_id AS patient_id, " +
                "i.patient_name AS patient_name, i.meal_type AS meal_type, " +
                "i.allergies AS allergies, i.adjust_type AS adjust_type"

        private const val LIST_FROM =
            " FROM dining.dining_meal_executions x " +
                "JOIN dining.dining_roster_items i ON i.id = x.roster_item_id " +
                "JOIN dining.dining_rosters r ON r.id = i.roster_id "

        private const val LIST_ORDER =
            " ORDER BY r.menu_date DESC, r.meal_time DESC, x.recorded_at DESC"

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

        return pool.preparedQuery(ROSTER_ITEM_SQL).execute(Tuple.of(rosterItemId)).compose { rows ->
            if (rows.size() == 0)
                return@compose Future.failedFuture(DiningNotFoundException("roster item not found: $rosterItemId"))

            val id = Ulid.generate()
            val now = OffsetDateTime.now()
            pool.preparedQuery(UPSERT_SQL).execute(
                Tuple.of(
                    id, rosterItemId, status, remark, userId, now, now, JsonObject(),
                    status, remark, userId, now, now,
                )
            ).compose { result ->
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        date?.takeIf(String::isNotBlank)?.let { where.add("r.menu_date = ${bind(parseDate(it))}") }
        mealTime?.takeIf(String::isNotBlank)?.let {
            if (it !in DiningConstants.MEAL_TIMES)
                return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))
            where.add("r.meal_time = ${bind(it)}")
        }
        status?.takeIf(String::isNotBlank)?.let {
            if (it !in DiningConstants.MEAL_STATUSES)
                return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.MEAL_STATUSES}"))
            where.add("x.status = ${bind(it)}")
        }
        patientId?.takeIf(String::isNotBlank)?.let { where.add("i.patient_id = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total$LIST_FROM$whereClause"
        val countArgs = args.toList()
        val dataSql = "SELECT $LIST_COLUMNS$LIST_FROM$whereClause$LIST_ORDER LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
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

    private fun getById(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT $EXECUTION_COLUMNS FROM dining.dining_meal_executions WHERE id = \$1")
            .execute(Tuple.of(id))
            .compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row == null)
                    Future.failedFuture(DiningNotFoundException("meal execution not found: $id"))
                else Future.succeededFuture(toJson(row))
            }

    private fun parseDate(value: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("date must be a valid ISO date (yyyy-MM-dd)")
        }
}
