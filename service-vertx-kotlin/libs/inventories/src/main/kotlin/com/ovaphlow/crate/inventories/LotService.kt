package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Lots
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL.count
import java.time.LocalDate

class LotService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val l = Lots.LOTS
    private val m = Materials.MATERIALS

    fun create(body: JsonObject): Future<JsonObject> {
        val materialId = body.getString("material_id")
            ?: return Future.failedFuture(IllegalArgumentException("material_id is required"))
        val batchNo = body.getString("batch_no")
            ?: return Future.failedFuture(IllegalArgumentException("batch_no is required"))

        // Step 1: Check material exists and has batch control enabled
        val materialQuery = ctx.select(m.ID, m.ENABLE_BATCH_CONTROL)
            .from(m)
            .where(m.ID.eq(materialId))

        return pool.preparedQuery(DatabaseConfig.sql(materialQuery))
            .execute(DatabaseConfig.tuple(materialQuery))
            .flatMap { materialRows ->
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
                val existsQuery = ctx.selectFrom(l)
                    .where(l.MATERIAL_ID.eq(materialId))
                    .and(l.BATCH_NO.eq(batchNo))

                pool.preparedQuery(DatabaseConfig.sql(existsQuery))
                    .execute(DatabaseConfig.tuple(existsQuery))
                    .flatMap { existRows ->
                        if (existRows.size() > 0) {
                            return@flatMap Future.succeededFuture(toJson(existRows.iterator().next()))
                        }

                        // Step 3: Insert new lot
                        val id = Ulid.generate()
                        val insertQuery = ctx.insertInto(l)
                            .set(l.ID, id)
                            .set(l.MATERIAL_ID, materialId)
                            .set(l.BATCH_NO, batchNo)
                            .set(l.PRODUCTION_DATE, body.getString("production_date")?.let { LocalDate.parse(it) })
                            .set(l.EXPIRY_DATE, body.getString("expiry_date")?.let { LocalDate.parse(it) })
                            .set(l.MANUFACTURER, body.getString("manufacturer"))
                            .set(l.SUPPLIER, body.getString("supplier"))
                            .set(l.METADATA, body.containsKey("metadata")
                                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })

                        pool.preparedQuery(DatabaseConfig.sql(insertQuery))
                            .execute(DatabaseConfig.tuple(insertQuery))
                            .map {
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
        val conditions = mutableListOf<org.jooq.Condition>()
        materialId?.let { conditions.add(l.MATERIAL_ID.eq(it)) }
        batchNo?.let { conditions.add(l.BATCH_NO.like("%$it%")) }
        expiryBefore?.let { conditions.add(l.EXPIRY_DATE.le(LocalDate.parse(it))) }
        expiryAfter?.let { conditions.add(l.EXPIRY_DATE.ge(LocalDate.parse(it))) }
        manufacturer?.let { conditions.add(l.MANUFACTURER.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(l).where(conditions)
        val dataQuery = ctx.selectFrom(l)
            .where(conditions)
            .orderBy(l.EXPIRY_DATE.asc().nullsLast())
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
                        JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
                    }
            }
    }

    fun get(id: String): Future<JsonObject> {
        val query = ctx.selectFrom(l).where(l.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                if (rows.size() == 0) {
                    throw NotFoundException("lot not found: $id")
                }
                toJson(rows.iterator().next())
            }
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
