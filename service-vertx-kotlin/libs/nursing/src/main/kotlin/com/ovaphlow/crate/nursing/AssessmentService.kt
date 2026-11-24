package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** 护理评估服务。持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。 */
class AssessmentService(
    private val pool: Pool
) {
    companion object {
        private val VALID_ASSESS_TYPES = setOf(
            "ADMISSION", "FALL_RISK", "PRESSURE_SORE", "PAIN",
            "BARTHEL", "NUTRITION", "HOME_ENVIRONMENT", "OTHER"
        )

        private const val COLUMNS =
            "id, encounter_id, period_id, assess_type, assess_date, assessor, " +
                "total_score, result_level, detail, remark, metadata, created_at"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_assessments ($COLUMNS) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        private const val SELECT_BY_ID_SQL =
            "SELECT $COLUMNS FROM nursing.nursing_assessments WHERE id = \$1"

        private const val DELETE_SQL = "DELETE FROM nursing.nursing_assessments WHERE id = \$1"

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("period_id", row.getValue("period_id")?.toString())
                .put("assess_type", row.getValue("assess_type")?.toString())
                .put("assess_date", row.getValue("assess_date")?.toString())
                .put("assessor", row.getValue("assessor")?.toString())
                // Vert.x pg-client 把 NUMERIC 解码为 io.vertx.sqlclient.data.Numeric（Number），
                // 直接 `as? BigDecimal` 会恒为 null；按 Number 取值才能保住分数。
                .put("total_score", (row.getValue("total_score") as? Number)?.toDouble())
                .put("result_level", row.getValue("result_level")?.toString())
                .put("detail", row.getValue("detail") as? JsonObject)
                .put("remark", row.getValue("remark")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }

    fun create(body: JsonObject, assessor: String?): Future<JsonObject> {
        val assessType = body.getString("assess_type")
        val encounterId = body.getString("encounter_id")
        val periodId = body.getString("period_id")

        if (assessType.isNullOrBlank() || assessType !in VALID_ASSESS_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid assess_type, must be one of: $VALID_ASSESS_TYPES"))
        if (body.getString("assess_date").isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("assess_date is required"))
        if (encounterId.isNullOrBlank() && periodId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("either encounter_id or period_id is required"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val tuple = Tuple.of(
            id,
            encounterId?.takeIf { it.isNotBlank() },
            periodId?.takeIf { it.isNotBlank() },
            assessType,
            LocalDate.parse(body.getString("assess_date")),
            assessor,
            body.getDouble("total_score")?.let { BigDecimal.valueOf(it) },
            body.getString("result_level"),
            if (body.containsKey("detail")) body.getJsonObject("detail") else null,
            body.getString("remark"),
            if (body.containsKey("metadata")) body.getJsonObject("metadata") else null,
            now,
        )

        return pool.preparedQuery(INSERT_SQL)
            .execute(tuple)
            .map {
                JsonObject()
                    .put("id", id)
                    .put("encounter_id", encounterId?.takeIf { it.isNotBlank() })
                    .put("period_id", periodId?.takeIf { it.isNotBlank() })
                    .put("assess_type", assessType)
                    .put("assess_date", body.getString("assess_date"))
                    .put("assessor", assessor)
                    .put("total_score", body.getDouble("total_score"))
                    .put("result_level", body.getString("result_level"))
                    .put("detail", body.getJsonObject("detail"))
                    .put("remark", body.getString("remark"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())
            }
    }

    fun list(
        encounterId: String? = null,
        periodId: String? = null,
        assessType: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        encounterId?.let { where.add("encounter_id = ${bind(it)}") }
        periodId?.let { where.add("period_id = ${bind(it)}") }
        assessType?.let { where.add("assess_type = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM nursing.nursing_assessments$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $COLUMNS FROM nursing.nursing_assessments$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
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
                    Future.failedFuture(NotFoundException("assessment not found: $id"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    fun update(id: String, body: JsonObject, assessor: String?): Future<JsonObject> {
        return get(id).flatMap { _ ->
            val sets = mutableListOf<String>()
            val args = mutableListOf<Any?>()

            /** 追加一个绑定值并返回其占位符。 */
            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            sets.add("assessor = ${bind(assessor)}")

            if (body.containsKey("total_score"))
                sets.add("total_score = ${bind(body.getDouble("total_score")?.let { BigDecimal.valueOf(it) })}")
            if (body.containsKey("result_level"))
                sets.add("result_level = ${bind(body.getString("result_level"))}")
            if (body.containsKey("detail"))
                sets.add("detail = ${bind(body.getJsonObject("detail"))}")
            if (body.containsKey("remark"))
                sets.add("remark = ${bind(body.getString("remark"))}")
            if (body.containsKey("metadata"))
                sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")

            val idPlaceholder = bind(id)
            val updateSql =
                "UPDATE nursing.nursing_assessments SET ${sets.joinToString(", ")} " +
                    "WHERE id = $idPlaceholder"

            pool.preparedQuery(updateSql)
                .execute(Tuple.from(args))
                .flatMap { get(id) }
        }
    }

    fun delete(id: String): Future<Void?> {
        return pool.preparedQuery(DELETE_SQL)
            .execute(Tuple.of(id))
            .map { null as Void? }
    }
}
