package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Stocks
import com.ovaphlow.crate.database.gen.inventories.public_.tables.records.MaterialsRecord
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.UpdateSetMoreStep
import org.jooq.impl.DSL.count
import java.time.OffsetDateTime

/**
 * 016 单一基础单位物资服务。
 *
 * 每个物资在创建时一次性提交 `base_unit` 与 `quantity_scale`（0..6），不再存在
 * 包装单位、拆零单位、换算率、规格表或计量模型状态。base_unit / quantity_scale
 * 在物资存在库存事实后不可修改（409），与 ADR-001 的“存在流水后不可修改”一致。
 */
class MaterialService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = Materials.MATERIALS

    private val createAllowed = setOf(
        "code", "name", "category", "spec", "base_unit", "quantity_scale",
        "enable_batch_control", "cost_method", "metadata", "status",
    )
    private val updateAllowed = setOf(
        "name", "category", "spec", "base_unit", "quantity_scale",
        "enable_batch_control", "cost_method", "metadata", "status",
    )

    fun create(body: JsonObject): Future<JsonObject> {
        rejectUnknown(body, createAllowed)?.let { return Future.failedFuture(it) }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()

        val code = body.getString("code")
        val name = body.getString("name")
        val category = body.getString("category")
        val baseUnit = body.getString("base_unit")
        if (code.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("code is required"))
        if (name.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("name is required"))
        if (category.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("category is required"))
        if (baseUnit.isNullOrBlank()) return Future.failedFuture(IllegalArgumentException("base_unit is required"))
        val scale = parseScale(body.getValue("quantity_scale"))

        val query = ctx.insertInto(t)
            .set(t.ID, id)
            .set(t.CODE, code)
            .set(t.NAME, name)
            .set(t.CATEGORY, category)
            .set(t.SPEC, body.getString("spec"))
            .set(t.BASE_UNIT, baseUnit)
            .set(t.QUANTITY_SCALE, scale)
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
                    .put("code", code)
                    .put("name", name)
                    .put("category", category)
                    .put("spec", body.getString("spec"))
                    .put("base_unit", baseUnit)
                    .put("quantity_scale", scale)
                    .put("enable_batch_control", body.getBoolean("enable_batch_control"))
                    .put("cost_method", body.getString("cost_method"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("status", body.getString("status"))
                    .put("created_at", now.toString())
                    .put("updated_at", null)
            }
    }

    fun list(
        code: String? = null,
        name: String? = null,
        category: String? = null,
        status: String? = null,
        enableBatchControl: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0,
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
                        for (row in dataRows) records.add(toJson(row))
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

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        rejectUnknown(body, updateAllowed)?.let { return Future.failedFuture(it) }
        return get(id).flatMap { existing ->
            val touchesUnit = body.containsKey("base_unit") || body.containsKey("quantity_scale")
            if (touchesUnit) {
                val stockQuery = ctx.select(Stocks.STOCKS.ID)
                    .from(Stocks.STOCKS)
                    .where(Stocks.STOCKS.MATERIAL_ID.eq(id))
                    .limit(1)
                pool.preparedQuery(DatabaseConfig.sql(stockQuery))
                    .execute(DatabaseConfig.tuple(stockQuery))
                    .compose { stockRows ->
                        if (stockRows.size() > 0) {
                            Future.failedFuture(
                                ConflictException(
                                    "base_unit/quantity_scale are immutable once stock facts exist for material $id",
                                ),
                            )
                        } else {
                            applyUpdate(id, existing, body)
                        }
                    }
            } else {
                applyUpdate(id, existing, body)
            }
        }
    }

    private fun applyUpdate(id: String, existing: JsonObject, body: JsonObject): Future<JsonObject> {
        val now = OffsetDateTime.now()
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
        if (body.containsKey("base_unit")) {
            q = q.set(t.BASE_UNIT, body.getString("base_unit"))
        }
        if (body.containsKey("quantity_scale")) {
            q = q.set(t.QUANTITY_SCALE, parseScale(body.getValue("quantity_scale")))
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
        q = q.set(t.UPDATED_AT, now)

        val query = q.where(t.ID.eq(id))

        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .flatMap { get(id) }
    }

    fun delete(id: String): Future<Void?> {
        val query = ctx.deleteFrom(t).where(t.ID.eq(id))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { null as Void? }
    }

    private fun parseScale(value: Any?): Short {
        val scale = jsonDecimal(value)?.let { decimal ->
            runCatching { decimal.intValueExact() }.getOrNull()
        } ?: 0
        if (scale !in 0..6) throw IllegalArgumentException("quantity_scale must be between 0 and 6")
        return scale.toShort()
    }

    private fun rejectUnknown(body: JsonObject, allowed: Set<String>): IllegalArgumentException? {
        val unknown = body.fieldNames().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            return IllegalArgumentException("unknown fields: ${unknown.joinToString(", ")}")
        }
        return null
    }

    companion object {
        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("code", row.getValue("code")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("category", row.getValue("category")?.toString())
                .put("spec", row.getValue("spec")?.toString())
                .put("base_unit", row.getValue("base_unit")?.toString())
                .put("quantity_scale", (row.getValue("quantity_scale") as? Number)?.toInt())
                .put("enable_batch_control", row.getValue("enable_batch_control") as? Boolean)
                .put("cost_method", row.getValue("cost_method")?.toString())
                .put("metadata", row.getValue("metadata") as? JsonObject)
                .put("status", row.getValue("status")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }
    }
}
