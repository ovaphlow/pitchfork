package com.ovaphlow.crate.dining

import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import java.time.LocalDate

/**
 * 就餐统计（FR-6）：按日期范围统计就餐率、各状态人次、餐次/日期汇总。
 *
 * 口径：
 * - 应就餐人次 expected = 名单条目数，排除 外出/请假 手工标记（本餐不就餐）；
 * - 实际就餐 eaten = 登记状态为 正常/部分；
 * - 就餐率 = eaten / expected × 100（两位小数，expected 为 0 时 null）；
 * - 未登记 = expected − 已登记人次。
 */
class DiningStatisticsService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val rosters = DSL.table(DSL.name("dining", "dining_rosters"))
    private val items = DSL.table(DSL.name("dining", "dining_roster_items"))
    private val executions = DSL.table(DSL.name("dining", "dining_meal_executions"))

    private val fMenuDate = DSL.field(DSL.name("r", "menu_date"), LocalDate::class.java)
    private val fMealTime = DSL.field(DSL.name("r", "meal_time"), String::class.java)
    private val fItemId = DSL.field(DSL.name("i", "id"), String::class.java)
    private val fSource = DSL.field(DSL.name("i", "source"), String::class.java)
    private val fAdjustType = DSL.field(DSL.name("i", "adjust_type"), String::class.java)
    private val fExecStatus = DSL.field(DSL.name("x", "status"), String::class.java)
    private val fExecId = DSL.field(DSL.name("x", "id"), String::class.java)

    /** 应就餐口径：排除所有标记外出/请假（本餐不就餐）的条目，无论来源自动或手工。 */
    private val expectedCond: org.jooq.Condition =
        fAdjustType.isNull()
            .or(fAdjustType.notIn(DiningConstants.NOT_EXPECTED_ADJUST_TYPES))

    private val eatenCond: org.jooq.Condition =
        fExecStatus.`in`(DiningConstants.EATEN_STATUSES)

    /** 汇总统计（summary + by_status + by_meal + by_date）。 */
    fun mealStatistics(dateFrom: String, dateTo: String, mealTime: String? = null): Future<JsonObject> {
        val from = parseDate(dateFrom, "date_from")
        val to = parseDate(dateTo, "date_to")
        if (from.isAfter(to))
            return Future.failedFuture(IllegalArgumentException("date_from must not be after date_to"))
        if (!mealTime.isNullOrBlank() && mealTime !in DiningConstants.MEAL_TIMES)
            return Future.failedFuture(IllegalArgumentException("invalid meal_time, must be one of: ${DiningConstants.MEAL_TIMES}"))

        val conditions = mutableListOf<org.jooq.Condition>()
        conditions.add(fMenuDate.greaterOrEqual(from))
        conditions.add(fMenuDate.lessOrEqual(to))
        mealTime?.takeIf(String::isNotBlank)?.let { conditions.add(fMealTime.eq(it)) }

        val base = rosters.`as`("r")
            .join(items.`as`("i")).on(DSL.field(DSL.name("i", "roster_id")).eq(DSL.field(DSL.name("r", "id"))))
            .leftOuterJoin(executions.`as`("x")).on(DSL.field(DSL.name("x", "roster_item_id")).eq(fItemId))

        return Future.all(
            summaryQuery(base, conditions),
            byStatusQuery(base, conditions),
            byMealQuery(base, conditions),
            byDateQuery(base, conditions),
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

    private fun summaryQuery(base: org.jooq.Table<*>, conditions: List<org.jooq.Condition>): Future<JsonObject> {
        val query = ctx.select(
            DSL.count().filterWhere(expectedCond).`as`("expected_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull())).`as`("recorded_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(eatenCond)).`as`("eaten_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(fExecStatus.eq("正常"))).`as`("normal_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(fExecStatus.eq("部分"))).`as`("partial_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(fExecStatus.eq("未就餐"))).`as`("not_eaten_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(fExecStatus.eq("拒食"))).`as`("refused_total"),
            DSL.count().filterWhere(expectedCond.not()).`as`("not_expected_total"),
        ).from(base).where(conditions)

        return execute(pool, query).map { rows ->
            val row = rows.iterator().next()
            val expected = row.getLong("expected_total") ?: 0L
            val recorded = row.getLong("recorded_total") ?: 0L
            JsonObject()
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
    }

    private fun byStatusQuery(base: org.jooq.Table<*>, conditions: List<org.jooq.Condition>): Future<JsonObject> {
        val query = ctx.select(
            DSL.count().filterWhere(expectedCond.and(fExecStatus.eq("正常"))).`as`("正常"),
            DSL.count().filterWhere(expectedCond.and(fExecStatus.eq("部分"))).`as`("部分"),
            DSL.count().filterWhere(expectedCond.and(fExecStatus.eq("未就餐"))).`as`("未就餐"),
            DSL.count().filterWhere(expectedCond.and(fExecStatus.eq("拒食"))).`as`("拒食"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNull())).`as`("未登记"),
        ).from(base).where(conditions)

        return execute(pool, query).map { rows ->
            val row = rows.iterator().next()
            JsonObject()
                .put("正常", row.getLong("正常") ?: 0L)
                .put("部分", row.getLong("部分") ?: 0L)
                .put("未就餐", row.getLong("未就餐") ?: 0L)
                .put("拒食", row.getLong("拒食") ?: 0L)
                .put("未登记", row.getLong("未登记") ?: 0L)
        }
    }

    private fun byMealQuery(base: org.jooq.Table<*>, conditions: List<org.jooq.Condition>): Future<JsonObject> {
        val query = ctx.select(
            fMealTime.`as`("meal_time"),
            DSL.count().filterWhere(expectedCond).`as`("expected_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull())).`as`("recorded_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(eatenCond)).`as`("eaten_total"),
        ).from(base).where(conditions)
            .groupBy(fMealTime)
            .orderBy(fMealTime.asc())

        return execute(pool, query).map { rows ->
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
            JsonObject().put("records", records)
        }
    }

    private fun byDateQuery(base: org.jooq.Table<*>, conditions: List<org.jooq.Condition>): Future<JsonObject> {
        val query = ctx.select(
            fMenuDate.`as`("menu_date"),
            DSL.count().filterWhere(expectedCond).`as`("expected_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull())).`as`("recorded_total"),
            DSL.count().filterWhere(expectedCond.and(fExecId.isNotNull()).and(eatenCond)).`as`("eaten_total"),
        ).from(base).where(conditions)
            .groupBy(fMenuDate)
            .orderBy(fMenuDate.asc())

        return execute(pool, query).map { rows ->
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
            JsonObject().put("records", records)
        }
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

    private fun execute(client: SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
