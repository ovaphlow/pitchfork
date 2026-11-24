package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate

/**
 * 批次（lot）服务：批号受控物资的批次建档与查询。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class LotService(
    private val pool: Pool,
) {

    companion object {
        private const val COLUMNS =
            "id, material_id, batch_no, production_date, expiry_date, manufacturer, supplier, metadata"

        private const val MATERIAL_SQL =
            "SELECT enable_batch_control FROM materials WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO lots " +
                "(id, material_id, batch_no, production_date, expiry_date, manufacturer, supplier, metadata) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)"
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val materialId = body.getString("material_id")
            ?: return Future.failedFuture(IllegalArgumentException("material_id is required"))
        val batchNo = body.getString("batch_no")
            ?: return Future.failedFuture(IllegalArgumentException("batch_no is required"))

        // Step 1: Check material exists and has batch control enabled
        return pool.preparedQuery(MATERIAL_SQL).execute(Tuple.of(materialId)).flatMap { materialRows ->
            if (materialRows.size() == 0) {
                return@flatMap Future.failedFuture<JsonObject>(
                    NotFoundException("material not found: $materialId")
                )
            }

            val enableBatchControl = materialRows.iterator().next()
                .getValue("enable_batch_control") as? Boolean ?: false

            if (!enableBatchControl) {
                return@flatMap Future.failedFuture<JsonObject>(
                    IllegalArgumentException("material does not enable batch control")
                )
            }

            // Step 2: Check if lot (material_id, batch_no) already exists
            pool.preparedQuery(
                "SELECT $COLUMNS FROM lots WHERE material_id = \$1 AND batch_no = \$2"
            ).execute(Tuple.of(materialId, batchNo)).flatMap { existRows ->
                if (existRows.size() > 0) {
                    return@flatMap Future.succeededFuture(toJson(existRows.iterator().next()))
                }

                // Step 3: Insert new lot
                val id = Ulid.generate()
                val productionDate = body.getString("production_date")?.let { LocalDate.parse(it) }
                val expiryDate = body.getString("expiry_date")?.let { LocalDate.parse(it) }
                pool.preparedQuery(INSERT_SQL).execute(
                    Tuple.of(
                        id, materialId, batchNo, productionDate, expiryDate,
                        body.getString("manufacturer"), body.getString("supplier"),
                        body.getJsonObject("metadata"),
                    )
                ).map {
                    JsonObject()
                        .put("id", id)
                        .put("material_id", materialId)
                        .put("batch_no", batchNo)
                        .put("production_date", body.getString("production_date"))
                        .put("expiry_date", body.getString("expiry_date"))
                        .put("manufacturer", body.getString("manufacturer"))
                        .put("supplier", body.getString("supplier"))
                        .put("metadata", body.getJsonObject("metadata"))
                }
            }
        }
    }

    fun list(
        materialId: String? = null,
        batchNo: String? = null,
        expiryBefore: String? = null,
        expiryAfter: String? = null,
        manufacturer: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        materialId?.let { where.add("material_id = ${bind(it)}") }
        batchNo?.let { where.add("batch_no LIKE '%' || ${bind(it)} || '%'") }
        expiryBefore?.let { where.add("expiry_date <= ${bind(LocalDate.parse(it))}") }
        expiryAfter?.let { where.add("expiry_date >= ${bind(LocalDate.parse(it))}") }
        manufacturer?.let { where.add("manufacturer = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM lots$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $COLUMNS FROM lots$whereClause " +
                "ORDER BY expiry_date ASC NULLS LAST LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) {
                    records.add(toJson(row))
                }
                JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT $COLUMNS FROM lots WHERE id = \$1").execute(Tuple.of(id)).map { rows ->
            if (rows.size() == 0) {
                throw NotFoundException("lot not found: $id")
            }
            toJson(rows.iterator().next())
        }

    private fun toJson(row: Row): JsonObject {
        return JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("material_id", row.getValue("material_id")?.toString())
            .put("batch_no", row.getValue("batch_no")?.toString())
            .put("production_date", row.getValue("production_date")?.toString())
            .put("expiry_date", row.getValue("expiry_date")?.toString())
            .put("manufacturer", row.getValue("manufacturer")?.toString())
            .put("supplier", row.getValue("supplier")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
    }
}
