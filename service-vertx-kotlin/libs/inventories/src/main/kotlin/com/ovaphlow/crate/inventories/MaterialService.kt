package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL.count
import org.jooq.UpdateSetMoreStep
import com.ovaphlow.crate.database.gen.inventories.public_.tables.records.MaterialsRecord
import java.math.BigDecimal
import java.time.OffsetDateTime

class MaterialService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = Materials.MATERIALS

    fun create(body: JsonObject): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val query = ctx.insertInto(t)
            .set(t.ID, id)
            .set(t.CODE, body.getString("code"))
            .set(t.NAME, body.getString("name"))
            .set(t.CATEGORY, body.getString("category"))
            .set(t.SPEC, body.getString("spec"))
            .set(t.PACKAGE_UNIT, body.getString("package_unit"))
            .set(t.SPLIT_UNIT, body.getString("split_unit"))
            .set(t.SPLIT_RATIO, body.getDouble("split_ratio")?.let { BigDecimal.valueOf(it) })
            .set(t.ENABLE_BATCH_CONTROL, body.getBoolean("enable_batch_control"))
            .set(t.COST_METHOD, body.getString("cost_method"))
            .set(t.METADATA, body.containsKey("metadata")
                .let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(t.STATUS, body.getString("status"))
            .set(t.CREATED_AT, now)

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map {
                JsonObject()
                    .put("id", id)
                    .put("code", body.getString("code"))
                    .put("name", body.getString("name"))
                    .put("category", body.getString("category"))
                    .put("spec", body.getString("spec"))
                    .put("package_unit", body.getString("package_unit"))
                    .put("split_unit", body.getString("split_unit"))
                    .put("split_ratio", body.getDouble("split_ratio"))
                    .put("enable_batch_control", body.getBoolean("enable_batch_control"))
                    .put("cost_method", body.getString("cost_method"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("status", body.getString("status"))
                    .put("created_at", now.toString())
            }
    }

    fun list(
        code: String? = null,
        name: String? = null,
        category: String? = null,
        status: String? = null,
        enableBatchControl: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        code?.let { conditions.add(t.CODE.eq(it)) }
        name?.let { conditions.add(t.NAME.like("%$it%")) }
        category?.let { conditions.add(t.CATEGORY.eq(it)) }
        status?.let { conditions.add(t.STATUS.eq(it)) }
        enableBatchControl?.let { conditions.add(t.ENABLE_BATCH_CONTROL.eq(it)) }

        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.selectFrom(t)
            .where(conditions)
            .orderBy(t.CREATED_AT.desc())
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
        val query = ctx.selectFrom(t).where(t.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("material not found"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }
    }

    @Suppress("UNCHECKED_CAST")
    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { _ ->
            var q: UpdateSetMoreStep<MaterialsRecord> = ctx.update(t) as UpdateSetMoreStep<MaterialsRecord>

            if (body.containsKey("name")) {
                q = q.set(t.NAME, body.getString("name"))
            }
            if (body.containsKey("category")) {
                q = q.set(t.CATEGORY, body.getString("category"))
            }
            if (body.containsKey("spec")) {
                q = q.set(t.SPEC, body.getString("spec"))
            }
            if (body.containsKey("package_unit")) {
                q = q.set(t.PACKAGE_UNIT, body.getString("package_unit"))
            }
            if (body.containsKey("split_unit")) {
                q = q.set(t.SPLIT_UNIT, body.getString("split_unit"))
            }
            if (body.containsKey("split_ratio")) {
                q = q.set(t.SPLIT_RATIO, body.getDouble("split_ratio")?.let { BigDecimal.valueOf(it) })
            }
            if (body.containsKey("enable_batch_control")) {
                q = q.set(t.ENABLE_BATCH_CONTROL, body.getBoolean("enable_batch_control"))
            }
            if (body.containsKey("cost_method")) {
                q = q.set(t.COST_METHOD, body.getString("cost_method"))
            }
            if (body.containsKey("metadata")) {
                q = q.set(t.METADATA, JSONB.valueOf(body.getJsonObject("metadata").encode()))
            }
            if (body.containsKey("status")) {
                q = q.set(t.STATUS, body.getString("status"))
            }

            val query = q.where(t.ID.eq(id))

            pool.preparedQuery(DatabaseConfig.sql(query))
                .execute(DatabaseConfig.tuple(query))
                .flatMap { get(id) }
        }
    }

    fun delete(id: String): Future<Void?> {
        val query = ctx.deleteFrom(t).where(t.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    companion object {
        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("code", row.getValue("code")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("category", row.getValue("category")?.toString())
                .put("spec", row.getValue("spec")?.toString())
                .put("package_unit", row.getValue("package_unit")?.toString())
                .put("split_unit", row.getValue("split_unit")?.toString())
                .put("split_ratio", (row.getValue("split_ratio") as? BigDecimal)?.toDouble())
                .put("enable_batch_control", row.getValue("enable_batch_control") as? Boolean)
                .put("cost_method", row.getValue("cost_method")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("status", row.getValue("status")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())
        }
    }
}

class NotFoundException(message: String) : Exception(message)
