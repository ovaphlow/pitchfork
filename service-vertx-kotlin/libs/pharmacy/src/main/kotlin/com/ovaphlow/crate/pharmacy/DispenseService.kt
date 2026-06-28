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

class DispenseService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = DSL.table("pharmacy_dispenses")
    private val ti = DSL.table("pharmacy_dispense_items")

    // column references for dispenses header
    private val cId = DSL.field("id", String::class.java)
    private val cDispenseNo = DSL.field("dispense_no", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cDispenseType = DSL.field("dispense_type", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cPharmacist = DSL.field("pharmacist", String::class.java)
    private val cReviewer = DSL.field("reviewer", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cDispensedAt = DSL.field("dispensed_at", OffsetDateTime::class.java)

    // column references for dispense items
    private val ciId = DSL.field("id", String::class.java)
    private val ciDispenseId = DSL.field("dispense_id", String::class.java)
    private val ciOrderItemId = DSL.field("order_item_id", String::class.java)
    private val ciOrderExecutionId = DSL.field("order_execution_id", String::class.java)
    private val ciMaterialId = DSL.field("material_id", String::class.java)
    private val ciLotId = DSL.field("lot_id", String::class.java)
    private val ciPrescribedQty = DSL.field("prescribed_quantity", BigDecimal::class.java)
    private val ciDispensedQty = DSL.field("dispensed_quantity", BigDecimal::class.java)
    private val ciUnit = DSL.field("unit", String::class.java)
    private val ciSplitQty = DSL.field("split_quantity", BigDecimal::class.java)
    private val ciStockOpDetailId = DSL.field("stock_operation_detail_id", String::class.java)
    private val ciUnitCost = DSL.field("unit_cost", BigDecimal::class.java)
    private val ciTotalCost = DSL.field("total_cost", BigDecimal::class.java)
    private val ciMetadata = DSL.field("metadata", JSONB::class.java)

    companion object {
        private val VALID_STATUS_TRANSITIONS = mapOf(
            "PENDING" to listOf("REVIEWED", "CANCELLED"),
            "REVIEWED" to listOf("DISPENSING", "CANCELLED"),
            "DISPENSING" to listOf("DISPENSED", "CANCELLED"),
            "DISPENSED" to emptyList(),
            "CANCELLED" to emptyList()
        )

        private val VALID_DISPENSE_TYPES = setOf(
            "OUTPATIENT", "INPATIENT", "WARD_BATCH", "ELDERLY_ROUTINE"
        )

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("dispense_no", row.getValue("dispense_no")?.toString())
                .put("patient_id", row.getValue("patient_id")?.toString())
                .put("encounter_id", row.getValue("encounter_id")?.toString())
                .put("dispense_type", row.getValue("dispense_type")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("pharmacist", row.getValue("pharmacist")?.toString())
                .put("reviewer", row.getValue("reviewer")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("dispensed_at", row.getValue("dispensed_at")?.toString())
        }

        fun itemToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("dispense_id", row.getValue("dispense_id")?.toString())
                .put("order_item_id", row.getValue("order_item_id")?.toString())
                .put("order_execution_id", row.getValue("order_execution_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("lot_id", row.getValue("lot_id")?.toString())
                .put("prescribed_quantity", (row.getValue("prescribed_quantity") as? BigDecimal)?.toDouble())
                .put("dispensed_quantity", (row.getValue("dispensed_quantity") as? BigDecimal)?.toDouble())
                .put("unit", row.getValue("unit")?.toString())
                .put("split_quantity", (row.getValue("split_quantity") as? BigDecimal)?.toDouble())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("unit_cost", (row.getValue("unit_cost") as? BigDecimal)?.toDouble())
                .put("total_cost", (row.getValue("total_cost") as? BigDecimal)?.toDouble())
                .put("metadata", row.getValue("metadata") as? JsonObject)
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val dispenseNo = body.getString("dispense_no")
        val patientId = body.getString("patient_id")
        val dispenseType = body.getString("dispense_type")

        if (dispenseNo.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("dispense_no is required"))
        if (patientId.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        if (dispenseType.isNullOrBlank() || dispenseType !in VALID_DISPENSE_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid dispense_type, must be one of: $VALID_DISPENSE_TYPES"))

        val itemsArray = body.getJsonArray("items")
        if (itemsArray == null || itemsArray.isEmpty)
            return Future.failedFuture(IllegalArgumentException("items is required and must not be empty"))

        val now = OffsetDateTime.now()
        val headerId = Ulid.generate()

        val headerInsert = ctx.insertInto(t)
            .set(cId, headerId)
            .set(cDispenseNo, dispenseNo)
            .set(cPatientId, patientId)
            .set(cEncounterId, body.getString("encounter_id"))
            .set(cDispenseType, dispenseType)
            .set(cStatus, "PENDING")
            .set(cPharmacist, body.getString("pharmacist"))
            .set(cReviewer, body.getString("reviewer"))
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(headerInsert))
            .execute(DatabaseConfig.tuple(headerInsert))
            .flatMap { _ ->
                val header = JsonObject()
                    .put("id", headerId)
                    .put("dispense_no", dispenseNo)
                    .put("patient_id", patientId)
                    .put("encounter_id", body.getString("encounter_id"))
                    .put("dispense_type", dispenseType)
                    .put("status", "PENDING")
                    .put("pharmacist", body.getString("pharmacist"))
                    .put("reviewer", body.getString("reviewer"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("created_at", now.toString())

                val items = JsonArray()

                // insert all items sequentially
                fun insertItem(index: Int): Future<JsonObject> {
                    if (index >= itemsArray.size()) {
                        header.put("items", items)
                        return Future.succeededFuture(header)
                    }

                    val itemObj = itemsArray.getJsonObject(index)
                    val itemId = Ulid.generate()
                    val prescribedQty = itemObj.getDouble("prescribed_quantity")
                    val dispensedQty = itemObj.getDouble("dispensed_quantity")
                    val unitCost = itemObj.getDouble("unit_cost")
                    val totalCost = if (dispensedQty != null && unitCost != null)
                        BigDecimal.valueOf(dispensedQty * unitCost) else null

                    val itemInsert = ctx.insertInto(ti)
                        .set(ciId, itemId)
                        .set(ciDispenseId, headerId)
                        .set(ciOrderItemId, itemObj.getString("order_item_id"))
                        .set(ciOrderExecutionId, itemObj.getString("order_execution_id"))
                        .set(ciMaterialId, itemObj.getString("material_id"))
                        .set(ciLotId, itemObj.getString("lot_id"))
                        .set(ciPrescribedQty, prescribedQty?.let { BigDecimal.valueOf(it) })
                        .set(ciDispensedQty, dispensedQty?.let { BigDecimal.valueOf(it) })
                        .set(ciUnit, itemObj.getString("unit"))
                        .set(ciSplitQty, itemObj.getDouble("split_quantity")?.let { BigDecimal.valueOf(it) })
                        .set(ciUnitCost, unitCost?.let { BigDecimal.valueOf(it) })
                        .set(ciTotalCost, totalCost)
                        .set(ciMetadata, itemObj.containsKey("metadata")
                            .let { if (it) JSONB.valueOf(itemObj.getJsonObject("metadata").encode()) else null })

                    return pool.preparedQuery(DatabaseConfig.sql(itemInsert))
                        .execute(DatabaseConfig.tuple(itemInsert))
                        .flatMap { _ ->
                            items.add(JsonObject()
                                .put("id", itemId)
                                .put("dispense_id", headerId)
                                .put("order_item_id", itemObj.getString("order_item_id"))
                                .put("order_execution_id", itemObj.getString("order_execution_id"))
                                .put("material_id", itemObj.getString("material_id"))
                                .put("lot_id", itemObj.getString("lot_id"))
                                .put("prescribed_quantity", itemObj.getDouble("prescribed_quantity"))
                                .put("dispensed_quantity", itemObj.getDouble("dispensed_quantity"))
                                .put("unit", itemObj.getString("unit"))
                                .put("split_quantity", itemObj.getDouble("split_quantity"))
                                .put("stock_operation_detail_id", itemObj.getString("stock_operation_detail_id"))
                                .put("unit_cost", itemObj.getDouble("unit_cost"))
                                .put("total_cost", totalCost?.toDouble()))
                            insertItem(index + 1)
                        }
                }

                insertItem(0)
            }
    }

    fun list(
        patientId: String? = null,
        dispenseType: String? = null,
        status: String? = null,
        pharmacist: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.let { conditions.add(cPatientId.eq(it)) }
        dispenseType?.let { conditions.add(cDispenseType.eq(it)) }
        status?.let { conditions.add(cStatus.eq(it)) }
        pharmacist?.let { conditions.add(cPharmacist.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t)
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
        val query = ctx.selectFrom(t).where(cId.eq(id))
        val itemsQuery = ctx.selectFrom(ti).where(ciDispenseId.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("dispense not found: $id"))
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

    fun updateStatus(id: String, newStatus: String): Future<JsonObject> {
        if (newStatus.isBlank())
            return Future.failedFuture(IllegalArgumentException("status is required"))

        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            val allowedNext = VALID_STATUS_TRANSITIONS[currentStatus] ?: emptyList()

            if (newStatus !in allowedNext) {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot transition from $currentStatus to $newStatus")
                )
            }

            val now = OffsetDateTime.now()
            var q = ctx.update(t)
                .set(cStatus, newStatus)

            if (newStatus == "DISPENSED") {
                q = q.set(cDispensedAt, now)
            }

            val updateQuery = q
                .where(cId.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                .execute(DatabaseConfig.tuple(updateQuery))
                .flatMap { get(id) }
        }
    }
}