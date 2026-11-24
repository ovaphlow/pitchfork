package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/** 上门排班服务。持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。 */
class VisitScheduleService(
    private val pool: Pool
) {
    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "SCHEDULED" to listOf("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS" to listOf("COMPLETED", "CANCELLED"),
            "COMPLETED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        private const val COLUMNS =
            "id, period_id, task_execution_id, planned_date, planned_start, planned_end, caregiver, " +
                "actual_start, actual_end, travel_time, status, metadata, created_at, updated_at"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_visit_schedules ($COLUMNS) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14)"

        private const val SELECT_BY_ID_SQL =
            "SELECT $COLUMNS FROM nursing.nursing_visit_schedules WHERE id = \$1"

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("task_execution_id", row.getValue("task_execution_id")?.toString())
                .put("planned_date", row.getValue("planned_date")?.toString())
                .put("planned_start", row.getValue("planned_start")?.toString())
                .put("planned_end", row.getValue("planned_end")?.toString())
                .put("caregiver", row.getValue("caregiver")?.toString())
                .put("actual_start", row.getValue("actual_start")?.toString())
                .put("actual_end", row.getValue("actual_end")?.toString())
                .put("travel_time", row.getValue("travel_time") as? Int)
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val periodId = body.getString("period_id")
        val plannedDate = body.getString("planned_date")

        if (periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("period_id is required"))
        if (plannedDate.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("planned_date is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val tuple = Tuple.of(
            id,
            periodId,
            body.getString("task_execution_id"),
            LocalDate.parse(plannedDate),
            body.getString("planned_start")?.let { LocalTime.parse(it) },
            body.getString("planned_end")?.let { LocalTime.parse(it) },
            body.getString("caregiver"),
            // actual_start / actual_end 建单时恒为空
            null,
            null,
            body.getInteger("travel_time"),
            "SCHEDULED",
            if (body.containsKey("metadata")) body.getJsonObject("metadata") else null,
            now,
            now,
        )

        return pool.preparedQuery(INSERT_SQL)
            .execute(tuple)
            .map {
                JsonObject()
                    .put("id", id)
                    .put("period_id", periodId)
                    .put("task_execution_id", body.getString("task_execution_id"))
                    .put("planned_date", plannedDate)
                    .put("planned_start", body.getString("planned_start"))
                    .put("planned_end", body.getString("planned_end"))
                    .put("caregiver", body.getString("caregiver"))
                    .put("travel_time", body.getInteger("travel_time"))
                    .put("status", "SCHEDULED")
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
                    .put("updated_at", now.toString())
            }
    }

    fun list(
        periodId: String? = null,
        caregiver: String? = null,
        status: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        periodId?.let { where.add("period_id = ${bind(it)}") }
        caregiver?.let { where.add("caregiver = ${bind(it)}") }
        status?.let { where.add("status = ${bind(it)}") }
        dateFrom?.let { where.add("planned_date >= ${bind(LocalDate.parse(it))}") }
        dateTo?.let { where.add("planned_date <= ${bind(LocalDate.parse(it))}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_visit_schedules$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $COLUMNS FROM nursing.nursing_visit_schedules$whereClause " +
                "ORDER BY planned_date ASC, planned_start ASC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs))
                .map { dataRows ->
                    val records = JsonArray()
                    for (row in dataRows) records.add(toJson(row))
                    JsonObject().put("records", records)
                        .put("meta", JsonObject().put("total", total))
                }
        }
    }

    fun get(id: String): Future<JsonObject> {
        return pool.preparedQuery(SELECT_BY_ID_SQL)
            .execute(Tuple.of(id))
            .flatMap { rows ->
                if (rows.size() == 0)
                    Future.failedFuture(NotFoundException("visit schedule not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun updateStatus(id: String, body: JsonObject): Future<JsonObject> {
        val newStatus = body.getString("status")
        if (newStatus.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("status is required"))

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()
            if (newStatus !in allowedNext)
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus")
                )

            val now = OffsetDateTime.now()
            val args = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            // 列 → 占位符；同一列重复赋值时后者覆盖（与原 jOOQ set 覆盖语义一致）。
            val sets = linkedMapOf<String, String>()
            sets["status"] = bind(newStatus)
            sets["updated_at"] = bind(now)

            if (newStatus == "IN_PROGRESS" && !body.containsKey("actual_start"))
                sets["actual_start"] = bind(now)
            if (newStatus == "COMPLETED" && !body.containsKey("actual_end"))
                sets["actual_end"] = bind(now)

            if (body.containsKey("actual_start"))
                sets["actual_start"] = bind(body.getString("actual_start")?.let { OffsetDateTime.parse(it) })
            if (body.containsKey("actual_end"))
                sets["actual_end"] = bind(body.getString("actual_end")?.let { OffsetDateTime.parse(it) })
            if (body.containsKey("travel_time"))
                sets["travel_time"] = bind(body.getInteger("travel_time"))

            val idPlaceholder = bind(id)
            val updateSql =
                "UPDATE nursing.nursing_visit_schedules " +
                    "SET ${sets.entries.joinToString(", ") { "${it.key} = ${it.value}" }} " +
                    "WHERE id = $idPlaceholder"

            pool.preparedQuery(updateSql)
                .execute(Tuple.from(args))
                .flatMap { get(id) }
        }
    }
}
