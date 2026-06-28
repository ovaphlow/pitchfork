package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.math.BigDecimal
import java.time.OffsetDateTime

class ReturnService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val r = DSL.table("pharmacy_returns")
    private val ri = DSL.table("pharmacy_return_items")

    private val cId = DSL.field("id", String::class.java)
    private val cReturnNo = DSL.field("return_no", String::class.java)
    private val cOriginalDispenseId = DSL.field("original_dispense_id", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cReturnReason = DSL.field("return_reason", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cOperator = DSL.field("operator", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cConfirmedAt = DSL.field("confirmed_at", OffsetDateTime::class.java)

    private val ciId = DSL.field("id", String::class.java)
    private val ciReturnId = DSL.field("return_id", String::class.java)
    private val ciDispenseItemId = DSL.field("dispense_item_id", String::class.java)
    private val ciQuantity = DSL.field("quantity", BigDecimal::class.java)
    private val ciStockOpDetailId = DSL.field("stock_operation_detail_id", String::class.java)
    private val ciUnitCost = DSL.field("unit_cost", BigDecimal::class.java)
    private val ciTotalCost = DSL.field("total_cost", BigDecimal::class.java)
    private val ciMetadata = DSL.field("metadata", JSONB::class.java)

    companion object {
        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("return_no", row.getValue("return_no")?.toString())
                .put("original_dispense_id", row.getValue("original_dispense_id")?.toString())
                .put("patient_id", row.getValue("patient_id")?.toString())
                .put("return_reason", row.getValue("return_reason")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("operator", row.getValue("operator")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("confirmed_at", row.getValue("confirmed_at")?.toString())
        }

        fun itemToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("return_id", row.getValue("return_id")?.toString())
                .put("dispense_item_id", row.getValue("dispense_item_id")?.toString())
                .put("quantity", (row.getValue("quantity") as? BigDecimal)?.toDouble())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("unit_cost", (row.getValue("unit_cost") as? BigDecimal)?.toDouble())
                .put("total_cost", (row.getValue("total_cost") as? BigDecimal)?.toDouble())
                .put("metadata", row.getValue("metadata") as? JsonObject)
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val returnNo = body.getString("return_no")
        val originalDispenseId = body.getString("original_dispense_id")
        val patientId = body.getString("patient_id")

        if (returnNo.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("return_no is required"))
        if (originalDispenseId.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("original_dispense_id is required"))
        if (patientId.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("patient_id is required"))

        val itemsArray = body.getJsonArray("items")
        if (itemsArray == null || itemsArray.isEmpty)
            return Future.failedFuture(IllegalArgumentException("items is required and must not be empty"))

        val now = OffsetDateTime.now()
        val returnId = Ulid.generate()

        val headerInsert = ctx.insertInto(r)
            .set(cId, returnId)
            .set(cReturnNo, returnNo)
            .set(cOriginalDispenseId, originalDispenseId)
            .set(cPatientId, patientId)
            .set(cReturnReason, body.getString("return_reason"))
            .set(cStatus, "PENDING")
            .set(cOperator, body.getString("operator"))
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(headerInsert))
            .execute(DatabaseConfig.tuple(headerInsert))
            .flatMap { _ ->
                val header = JsonObject()
                    .put("id", returnId)
                    .put("return_no", returnNo)
                    .put("original_dispense_id", originalDispenseId)
                    .put("patient_id", patientId)
                    .put("return_reason", body.getString("return_reason"))
                    .put("status", "PENDING")
                    .put("operator", body.getString("operator"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())

                val items = JsonArray()

                fun insertItem(index: Int): Future<JsonObject> {
                    if (index >= itemsArray.size()) {
                        header.put("items", items)
                        return Future.succeededFuture(header)
                    }

                    val itemObj = itemsArray.getJsonObject(index)
                    val itemId = Ulid.generate()
                    val quantity = itemObj.getDouble("quantity")
                    val unitCost = itemObj.getDouble("unit_cost")
                    val totalCost = if (quantity != null && unitCost != null)
                        BigDecimal.valueOf(quantity * unitCost) else null

                    val itemInsert = ctx.insertInto(ri)
                        .set(ciId, itemId)
                        .set(ciReturnId, returnId)
                        .set(ciDispenseItemId, itemObj.getString("dispense_item_id"))
                        .set(ciQuantity, quantity?.let { BigDecimal.valueOf(it) })
                        .set(ciStockOpDetailId, itemObj.getString("stock_operation_detail_id"))
                        .set(ciUnitCost, unitCost?.let { BigDecimal.valueOf(it) })
                        .set(ciTotalCost, totalCost)
                        .set(ciMetadata, itemObj.containsKey("metadata")
                            .let { if (it) JSONB.valueOf(itemObj.getJsonObject("metadata").encode()) else null })

                    return pool.preparedQuery(DatabaseConfig.sql(itemInsert))
                        .execute(DatabaseConfig.tuple(itemInsert))
                        .flatMap { _ ->
                            items.add(JsonObject()
                                .put("id", itemId)
                                .put("return_id", returnId)
                                .put("dispense_item_id", itemObj.getString("dispense_item_id"))
                                .put("quantity", itemObj.getDouble("quantity"))
                                .put("stock_operation_detail_id", itemObj.getString("stock_operation_detail_id"))
                                .put("unit_cost", itemObj.getDouble("unit_cost"))
                                .put("total_cost", totalCost?.toDouble())
                                .put("metadata", itemObj.getJsonObject("metadata")))
                            insertItem(index + 1)
                        }
                }

                insertItem(0)
            }
    }

    fun list(
        patientId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.let { conditions.add(cPatientId.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(r).where(conditions)
        val dataQuery = ctx.selectFrom(r)
            .where(conditions)
            .orderBy(cCreatedAt.desc())
            .limit(limit)
            .offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows ->
                        val records = JsonArray()
                        for (row in dataRows) {
                            records.add(toJson(row))
                        }
                        JsonObject().put("records", records)
                            .put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(r).where(cId.eq(id))
        val itemsQuery = ctx.selectFrom(ri).where(ciReturnId.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("return not found: $id"))
                } else {
                    val header = toJson(rows.iterator().next())
                    pool.preparedQuery(DatabaseConfig.sql(itemsQuery))
                        .execute(DatabaseConfig.tuple(itemsQuery))
                        .map { itemRows ->
                            val items = JsonArray()
                            for (row in itemRows) {
                                items.add(itemToJson(row))
                            }
                            header.put("items", items)
                            header
                        }
                }
            }
    }

    fun confirm(id: String): Future<JsonObject> {
        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            if (currentStatus != "PENDING") {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot confirm return in status: $currentStatus")
                )
            }
            val now = OffsetDateTime.now()
            val query = ctx.update(r)
                .set(cStatus, "CONFIRMED")
                .set(cConfirmedAt, now)
                .where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { get(id) }
        }
    }

    fun cancel(id: String): Future<JsonObject> {
        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            if (currentStatus != "PENDING") {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot cancel return in status: $currentStatus")
                )
            }
            val query = ctx.update(r)
                .set(cStatus, "CANCELLED")
                .where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { get(id) }
        }
    }
}