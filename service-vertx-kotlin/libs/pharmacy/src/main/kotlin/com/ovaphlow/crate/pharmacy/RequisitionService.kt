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

class RequisitionService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val r = DSL.table("pharmacy_requisitions")
    private val ri = DSL.table("pharmacy_requisition_items")

    private val cId = DSL.field("id", String::class.java)
    private val cRequisitionNo = DSL.field("requisition_no", String::class.java)
    private val cWarehouse = DSL.field("warehouse", String::class.java)
    private val cRequester = DSL.field("requester", String::class.java)
    private val cDepartment = DSL.field("department", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cDispensedAt = DSL.field("dispensed_at", OffsetDateTime::class.java)

    private val ciId = DSL.field("id", String::class.java)
    private val ciRequisitionId = DSL.field("requisition_id", String::class.java)
    private val ciMaterialId = DSL.field("material_id", String::class.java)
    private val ciRequestedQty = DSL.field("requested_quantity", BigDecimal::class.java)
    private val ciApprovedQty = DSL.field("approved_quantity", BigDecimal::class.java)
    private val ciDispensedQty = DSL.field("dispensed_quantity", BigDecimal::class.java)
    private val ciStockOpDetailId = DSL.field("stock_operation_detail_id", String::class.java)
    private val ciMetadata = DSL.field("metadata", JSONB::class.java)

    companion object {
        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("requisition_no", row.getValue("requisition_no")?.toString())
                .put("warehouse", row.getValue("warehouse")?.toString())
                .put("requester", row.getValue("requester")?.toString())
                .put("department", row.getValue("department")?.toString())
                .put("status", row.getValue("status")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("created_at", row.getValue("created_at")?.toString())
                .put("dispensed_at", row.getValue("dispensed_at")?.toString())
        }

        fun itemToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("requisition_id", row.getValue("requisition_id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("requested_quantity", (row.getValue("requested_quantity") as? BigDecimal)?.toDouble())
                .put("approved_quantity", (row.getValue("approved_quantity") as? BigDecimal)?.toDouble())
                .put("dispensed_quantity", (row.getValue("dispensed_quantity") as? BigDecimal)?.toDouble())
                .put("stock_operation_detail_id", row.getValue("stock_operation_detail_id")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
        }
    }

    fun create(body: JsonObject): Future<JsonObject> {
        val requisitionNo = body.getString("requisition_no")
        val warehouse = body.getString("warehouse")

        if (requisitionNo.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("requisition_no is required"))
        if (warehouse.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("warehouse is required"))

        val itemsArray = body.getJsonArray("items")
        if (itemsArray == null || itemsArray.isEmpty)
            return Future.failedFuture(IllegalArgumentException("items is required and must not be empty"))

        val now = OffsetDateTime.now()
        val reqId = Ulid.generate()

        val headerInsert = ctx.insertInto(r)
            .set(cId, reqId)
            .set(cRequisitionNo, requisitionNo)
            .set(cWarehouse, warehouse)
            .set(cRequester, body.getString("requester"))
            .set(cDepartment, body.getString("department"))
            .set(cStatus, "DRAFT")
            .set(cMetadata, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)

        return pool.preparedQuery(DatabaseConfig.sql(headerInsert))
            .execute(DatabaseConfig.tuple(headerInsert))
            .flatMap { _ ->
                val header = JsonObject()
                    .put("id", reqId)
                    .put("requisition_no", requisitionNo)
                    .put("warehouse", warehouse)
                    .put("requester", body.getString("requester"))
                    .put("department", body.getString("department"))
                    .put("status", "DRAFT")
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
                    val requestedQty = itemObj.getDouble("requested_quantity")
                    if (requestedQty == null) {
                        return Future.failedFuture(
                            IllegalArgumentException("items[$index].requested_quantity is required")
                        )
                    }

                    val itemInsert = ctx.insertInto(ri)
                        .set(ciId, itemId)
                        .set(ciRequisitionId, reqId)
                        .set(ciMaterialId, itemObj.getString("material_id"))
                        .set(ciRequestedQty, BigDecimal.valueOf(requestedQty))
                        .set(ciMetadata, itemObj.containsKey("metadata")
                            .let { if (it) JSONB.valueOf(itemObj.getJsonObject("metadata").encode()) else null })

                    return pool.preparedQuery(DatabaseConfig.sql(itemInsert))
                        .execute(DatabaseConfig.tuple(itemInsert))
                        .flatMap { _ ->
                            items.add(JsonObject()
                                .put("id", itemId)
                                .put("requisition_id", reqId)
                                .put("material_id", itemObj.getString("material_id"))
                                .put("requested_quantity", requestedQty)
                                .put("metadata", itemObj.getJsonObject("metadata")))
                            insertItem(index + 1)
                        }
                }

                insertItem(0)
            }
    }

    fun list(
        warehouse: String? = null,
        department: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        warehouse?.let { conditions.add(cWarehouse.eq(it)) }
        department?.let { conditions.add(cDepartment.eq(it)) }
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
        val itemsQuery = ctx.selectFrom(ri).where(ciRequisitionId.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) {
                    Future.failedFuture(NotFoundException("requisition not found: $id"))
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

    fun approve(id: String, itemsBody: JsonArray): Future<JsonObject> {
        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            if (currentStatus != "DRAFT") {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("can only approve a DRAFT requisition, current: $currentStatus")
                )
            }

            // build item id -> approved_quantity map
            val approvedMap = mutableMapOf<String, Double>()
            for (i in 0 until itemsBody.size()) {
                val item = itemsBody.getJsonObject(i)
                val itemId = item.getString("id") ?: continue
                val qty = item.getDouble("approved_quantity")
                if (qty != null) {
                    approvedMap[itemId] = qty
                }
            }

            // update each item with approved quantity
            var updates: Future<Unit> = Future.succeededFuture()
            for ((itemId, qty) in approvedMap) {
                val updateQuery = ctx.update(ri)
                    .set(ciApprovedQty, BigDecimal.valueOf(qty))
                    .where(ciId.eq(itemId))
                    .and(ciRequisitionId.eq(id))

                updates = updates.flatMap {
                    pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                        .execute(DatabaseConfig.tuple(updateQuery))
                        .map { }
                }
            }

            val statusQuery = ctx.update(r)
                .set(cStatus, "APPROVED")
                .where(cId.eq(id))

            updates.flatMap {
                pool.preparedQuery(DatabaseConfig.sql(statusQuery))
                    .execute(DatabaseConfig.tuple(statusQuery))
            }.flatMap {
                get(id)
            }
        }
    }

    fun dispense(id: String, itemsBody: JsonArray): Future<JsonObject> {
        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            if (currentStatus != "APPROVED") {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("can only dispense an APPROVED requisition, current: $currentStatus")
                )
            }

            val dispensedMap = mutableMapOf<String, Double>()
            for (i in 0 until itemsBody.size()) {
                val item = itemsBody.getJsonObject(i)
                val itemId = item.getString("id") ?: continue
                val qty = item.getDouble("dispensed_quantity")
                if (qty != null) {
                    dispensedMap[itemId] = qty
                }
            }

            var updates: Future<Unit> = Future.succeededFuture()
            for ((itemId, qty) in dispensedMap) {
                val updateQuery = ctx.update(ri)
                    .set(ciDispensedQty, BigDecimal.valueOf(qty))
                    .where(ciId.eq(itemId))
                    .and(ciRequisitionId.eq(id))

                updates = updates.flatMap {
                    pool.preparedQuery(DatabaseConfig.sql(updateQuery))
                        .execute(DatabaseConfig.tuple(updateQuery))
                        .map { }
                }
            }

            val now = OffsetDateTime.now()
            val statusQuery = ctx.update(r)
                .set(cStatus, "DISPENSED")
                .set(cDispensedAt, now)
                .where(cId.eq(id))

            updates.flatMap {
                pool.preparedQuery(DatabaseConfig.sql(statusQuery))
                    .execute(DatabaseConfig.tuple(statusQuery))
            }.flatMap {
                get(id)
            }
        }
    }

    fun cancel(id: String): Future<JsonObject> {
        return get(id).flatMap { existing ->
            val currentStatus = existing.getString("status")
            if (currentStatus == "DISPENSED") {
                return@flatMap Future.failedFuture(
                    IllegalArgumentException("cannot cancel a DISPENSED requisition")
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