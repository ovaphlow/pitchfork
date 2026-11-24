package com.ovaphlow.crate.dining

import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate

/**
 * 就餐统计（FR-6）：按日期范围统计就餐率、各状态人次、餐次/日期汇总。
 *
 * 口径：
 * - 应就餐人次 expected = 名单条目数，排除 外出/请假 手工标记（本餐不就餐）；
 * - 实际就餐 eaten = 登记状态为 正常/部分；
 * - 就餐率 = eaten / expected × 100（两位小数，expected 为 0 时 null）；
 * - 未登记 = expected − 已登记人次。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ；聚合口径用
 * PostgreSQL `count(*) FILTER (WHERE ...)` 表达。
 */
class DiningStatisticsService(
    private val pool: Pool,
) {

    /** 汇总统计（summary + by_status + by_meal + by_date）。 */
    fun mealStatistics(dateFrom: String, dateTo: String, mealTime: String? = null): Future<JsonObject> {
        val from = parseDate(dateFrom, "date_from")
        val to = parseDate(dateTo, "date_to")
        if (from.isAfter(to))
            return Future.failedFuture(IllegalArgumentException("date_from must not be after date_to"))
        if (!mealTime.isNullOrBlank() && mealTime !in DiningConstants.MEAL_TIMES)
            return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))

        val queries = StatisticsQueries(from, to, mealTime?.takeIf(String::isNotBlank))
        val tuple = Tuple.from(queries.boundArgs)

        return Future.all(
            fetch(queries.summarySql, tuple).map(::summaryJson),
            fetch(queries.byStatusSql, tuple).map(::byStatusJson),
            fetch(queries.byMealSql, tuple).map(::byMealJson),
            fetch(queries.byDateSql, tuple).map(::byDateJson),
        ).map { results ->
            val summary = results.resultAt(0) as JsonObject
            val byStatus = results.resultAt(1) as JsonObject
            val byMeal = results.resultAt(2) as JsonObject
            val byDate = results.resultAt(3) as JsonObject

            val expected = summary.getLong("expected_total") ?: 0L
            val eaten = summary.getLong("eaten_total") ?: 0L
            JsonObject()
                .put("date_from", from.toString())
                .put("date_to", to.toString())
                .put("summary", summary
                    .put("dining_rate", DiningConstants.diningRate(eaten, expected)))
                .put("by_status", byStatus)
                .put("by_meal", byMeal.getJsonArray("records"))
                .put("by_date", byDate.getJsonArray("records"))
        }
    }

    private fun fetch(sql: String, tuple: Tuple): Future<RowSet<Row>> =
        pool.preparedQuery(sql).execute(tuple)

    private fun summaryJson(rows: RowSet<Row>): JsonObject {
        val row = rows.iterator().next()
        val expected = row.getLong("expected_total") ?: 0L
        val recorded = row.getLong("recorded_total") ?: 0L
        return JsonObject()
            .put("expected_total", expected)
            .put("recorded_total", recorded)
            .put("eaten_total", row.getLong("eaten_total") ?: 0L)
            .put("normal_total", row.getLong("normal_total") ?: 0L)
            .put("partial_total", row.getLong("partial_total") ?: 0L)
            .put("not_eaten_total", row.getLong("not_eaten_total") ?: 0L)
            .put("refused_total", row.getLong("refused_total") ?: 0L)
            .put("not_expected_total", row.getLong("not_expected_total") ?: 0L)
            .put("unrecorded_total", (expected - recorded).coerceAtLeast(0))
    }

    private fun byStatusJson(rows: RowSet<Row>): JsonObject {
        val row = rows.iterator().next()
        return JsonObject()
            .put("正常", row.getLong("正常") ?: 0L)
            .put("部分", row.getLong("部分") ?: 0L)
            .put("未就餐", row.getLong("未就餐") ?: 0L)
            .put("拒食", row.getLong("拒食") ?: 0L)
            .put("未登记", row.getLong("未登记") ?: 0L)
    }

    private fun byMealJson(rows: RowSet<Row>): JsonObject {
        val records = JsonArray()
        for (row in rows) {
            val expected = row.getLong("expected_total") ?: 0L
            val eaten = row.getLong("eaten_total") ?: 0L
            records.add(
                JsonObject()
                    .put("meal_time", row.getValue("meal_time")?.toString())
                    .put("expected_total", expected)
                    .put("recorded_total", row.getLong("recorded_total") ?: 0L)
                    .put("eaten_total", eaten)
                    .put("dining_rate", DiningConstants.diningRate(eaten, expected))
            )
        }
        return JsonObject().put("records", records)
    }

    private fun byDateJson(rows: RowSet<Row>): JsonObject {
        val records = JsonArray()
        for (row in rows) {
            val expected = row.getLong("expected_total") ?: 0L
            val eaten = row.getLong("eaten_total") ?: 0L
            records.add(
                JsonObject()
                    .put("menu_date", row.getValue("menu_date")?.toString())
                    .put("expected_total", expected)
                    .put("recorded_total", row.getLong("recorded_total") ?: 0L)
                    .put("eaten_total", eaten)
                    .put("dining_rate", DiningConstants.diningRate(eaten, expected))
            )
        }
        return JsonObject().put("records", records)
    }

    private fun parseDate(value: String, field: String): LocalDate {
        if (value.isNullOrBlank())
            throw IllegalArgumentException("$field is required")
        return try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }
    }

    /**
     * 四条统计查询的 SQL 文本与绑定值。
     *
     * 常量谓词（外出/请假、正常/部分、四种登记状态）各绑定一次占位符并在多处 FILTER
     * 子句中复用：PostgreSQL 允许同一 `$n` 在一条语句内出现多次，绑定值只传一份。
     */
    private class StatisticsQueries(from: LocalDate, to: LocalDate, mealTime: String?) {
        private val args = mutableListOf<Any?>()

        private fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        val summarySql: String
        val byStatusSql: String
        val byMealSql: String
        val byDateSql: String
        val boundArgs: List<Any?>

        init {
            val where = mutableListOf<String>()
            where.add("r.menu_date >= ${bind(from)}")
            where.add("r.menu_date <= ${bind(to)}")
            if (mealTime != null) where.add("r.meal_time = ${bind(mealTime)}")
            val whereSql = " WHERE " + where.joinToString(" AND ")

            val notExpected = DiningConstants.NOT_EXPECTED_ADJUST_TYPES.map { bind(it) }
            val eaten = DiningConstants.EATEN_STATUSES.map { bind(it) }
            val statuses = DiningConstants.MEAL_STATUSES.associateWith { bind(it) }

            val expectedCond =
                "(i.adjust_type IS NULL OR i.adjust_type NOT IN (${notExpected.joinToString(", ")}))"
            val eatenCond = "x.status IN (${eaten.joinToString(", ")})"
            val recorded = "$expectedCond AND x.id IS NOT NULL"
            val eatenRecorded = "$recorded AND $eatenCond"

            summarySql =
                "SELECT count(*) FILTER (WHERE $expectedCond) AS expected_total, " +
                    "count(*) FILTER (WHERE $recorded) AS recorded_total, " +
                    "count(*) FILTER (WHERE $eatenRecorded) AS eaten_total, " +
                    "count(*) FILTER (WHERE $recorded AND x.status = ${statuses.getValue("正常")}) AS normal_total, " +
                    "count(*) FILTER (WHERE $recorded AND x.status = ${statuses.getValue("部分")}) AS partial_total, " +
                    "count(*) FILTER (WHERE $recorded AND x.status = ${statuses.getValue("未就餐")}) AS not_eaten_total, " +
                    "count(*) FILTER (WHERE $recorded AND x.status = ${statuses.getValue("拒食")}) AS refused_total, " +
                    "count(*) FILTER (WHERE NOT $expectedCond) AS not_expected_total " +
                    "FROM $BASE$whereSql"

            byStatusSql =
                "SELECT count(*) FILTER (WHERE $expectedCond AND x.status = ${statuses.getValue("正常")}) AS \"正常\", " +
                    "count(*) FILTER (WHERE $expectedCond AND x.status = ${statuses.getValue("部分")}) AS \"部分\", " +
                    "count(*) FILTER (WHERE $expectedCond AND x.status = ${statuses.getValue("未就餐")}) AS \"未就餐\", " +
                    "count(*) FILTER (WHERE $expectedCond AND x.status = ${statuses.getValue("拒食")}) AS \"拒食\", " +
                    "count(*) FILTER (WHERE $expectedCond AND x.id IS NULL) AS \"未登记\" " +
                    "FROM $BASE$whereSql"

            byMealSql =
                "SELECT r.meal_time AS meal_time, " +
                    "count(*) FILTER (WHERE $expectedCond) AS expected_total, " +
                    "count(*) FILTER (WHERE $recorded) AS recorded_total, " +
                    "count(*) FILTER (WHERE $eatenRecorded) AS eaten_total " +
                    "FROM $BASE$whereSql GROUP BY r.meal_time ORDER BY r.meal_time ASC"

            byDateSql =
                "SELECT r.menu_date AS menu_date, " +
                    "count(*) FILTER (WHERE $expectedCond) AS expected_total, " +
                    "count(*) FILTER (WHERE $recorded) AS recorded_total, " +
                    "count(*) FILTER (WHERE $eatenRecorded) AS eaten_total " +
                    "FROM $BASE$whereSql GROUP BY r.menu_date ORDER BY r.menu_date ASC"

            boundArgs = args.toList()
        }

        companion object {
            private const val BASE =
                "dining.dining_rosters r " +
                    "JOIN dining.dining_roster_items i ON i.roster_id = r.id " +
                    "LEFT JOIN dining.dining_meal_executions x ON x.roster_item_id = i.id"
        }
    }
}
