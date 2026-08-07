package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.MaterialUnitSpecs
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Stocks
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
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
    private val specs = MaterialUnitSpecs.MATERIAL_UNIT_SPECS

    /** 单位模型可写状态：LEGACY（无计量事实）下的物资单位字段仍按旧契约维护 */
    private fun unitFieldsImmutable(modelStatus: String?): Boolean =
        modelStatus != null && modelStatus != "LEGACY"

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
            .set(t.SPLIT_RATIO, jsonDecimal(body.getValue("split_ratio")))
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
                    .put("split_ratio", jsonDecimal(body.getValue("split_ratio"))?.toDouble())
                    .put("enable_batch_control", body.getBoolean("enable_batch_control"))
                    .put("cost_method", body.getString("cost_method"))
                    .put("metadata", body.getJsonObject("metadata"))
                    .put("status", body.getString("status"))
                    .put("base_unit", null)
                    .put("base_quantity_scale", 0)
                    .put("unit_model_status", "LEGACY")
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

    /**
     * 计划 015 6.1.7：单位字段不可变性。物资一旦建立计量模型（unit_model_status != LEGACY，
     * 隐含规格引用），package_unit / split_unit / split_ratio 一律 409；只允许不影响
     * 计量事实的常规字段更新与状态变更。
     */
    @Suppress("UNCHECKED_CAST")
    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap { existing ->
            val modelStatus = existing.getString("unit_model_status")
            if (unitFieldsImmutable(modelStatus)) {
                listOf("package_unit", "split_unit", "split_ratio").firstOrNull { body.containsKey(it) }?.let { field ->
                    return@flatMap Future.failedFuture(
                        ConflictException("unit field $field is immutable: material $id has an active unit model"),
                    )
                }
            }

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
            if (body.containsKey("package_unit") && !unitFieldsImmutable(modelStatus)) {
                q = q.set(t.PACKAGE_UNIT, body.getString("package_unit"))
            }
            if (body.containsKey("split_unit") && !unitFieldsImmutable(modelStatus)) {
                q = q.set(t.SPLIT_UNIT, body.getString("split_unit"))
            }
            if (body.containsKey("split_ratio") && !unitFieldsImmutable(modelStatus)) {
                q = q.set(t.SPLIT_RATIO, jsonDecimal(body.getValue("split_ratio")))
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

    // ========================================================================
    //  计划 015：单位模型与包装规格生命周期（4.1 / 6.1 / 6.2）
    // ========================================================================

    /** GET /materials/:id/unit-specs：基础单位、精度、迁移状态与全部规格（只读） */
    fun listUnitSpecs(id: String): Future<JsonObject> {
        val materialQuery = ctx.selectFrom(t).where(t.ID.eq(id))
        val specQuery = ctx.selectFrom(specs)
            .where(specs.MATERIAL_ID.eq(id))
            .orderBy(specs.CREATED_AT.asc())
        return pool.preparedQuery(DatabaseConfig.sql(materialQuery))
            .execute(DatabaseConfig.tuple(materialQuery))
            .flatMap { matRows ->
                if (matRows.size() == 0) {
                    Future.failedFuture(NotFoundException("material not found"))
                } else {
                    val matRow = matRows.iterator().next()
                    pool.preparedQuery(DatabaseConfig.sql(specQuery))
                        .execute(DatabaseConfig.tuple(specQuery))
                        .map { specRows ->
                            val list = JsonArray()
                            for (row in specRows) list.add(unitSpecToJson(row))
                            JsonObject()
                                .put("material_id", id)
                                .put("base_unit", matRow.getValue("base_unit")?.toString())
                                .put("base_quantity_scale", (matRow.getValue("base_quantity_scale") as? Number)?.toInt())
                                .put("unit_model_status", matRow.getValue("unit_model_status")?.toString() ?: "LEGACY")
                                .put("unit_specs", list)
                        }
                }
            }
    }

    /**
     * PUT /materials/:id/unit-model：仅在无计量事实时创建首次基础单位模型。
     * 请求只接受 base_unit、base_quantity_scale（0..6）与可选 default_spec
     * （input_unit / base_ratio，缺省 = 基础单位 / 1）；不接受库存、成本、
     * 历史操作或状态伪造字段。同一事务内创建基础规格与默认规格。
     */
    fun activateUnitModel(id: String, body: JsonObject): Future<JsonObject> {
        val allowed = setOf("base_unit", "base_quantity_scale", "default_spec")
        body.fieldNames().firstOrNull { it !in allowed }?.let { field ->
            return Future.failedFuture(
                IllegalArgumentException("unsupported field for unit model: $field"),
            )
        }
        val baseUnit = body.getString("base_unit")
        if (baseUnit.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("base_unit is required"))

        val scaleValue = jsonDecimal(body.getValue("base_quantity_scale"))
        val scale = if (scaleValue == null) 0 else scaleValue.toIntExactOrReject()
            ?: return Future.failedFuture(IllegalArgumentException("base_quantity_scale must be an integer"))
        if (scale !in 0..6)
            return Future.failedFuture(IllegalArgumentException("base_quantity_scale must be between 0 and 6"))

        val defaultInputUnit: String
        val defaultRatio: BigDecimal
        if (body.containsKey("default_spec")) {
            val ds = body.getJsonObject("default_spec")
            val dsAllowed = setOf("input_unit", "base_ratio")
            ds.fieldNames().firstOrNull { it !in dsAllowed }?.let { field ->
                return Future.failedFuture(IllegalArgumentException("unsupported field in default_spec: $field"))
            }
            defaultInputUnit = ds.getString("input_unit") ?: baseUnit
            defaultRatio = jsonDecimal(ds.getValue("base_ratio")) ?: BigDecimal.ONE
            if (defaultInputUnit.isBlank())
                return Future.failedFuture(IllegalArgumentException("default_spec.input_unit must not be blank"))
            if (defaultRatio <= BigDecimal.ZERO)
                return Future.failedFuture(IllegalArgumentException("default_spec.base_ratio must be positive"))
        } else {
            defaultInputUnit = baseUnit
            defaultRatio = BigDecimal.ONE
        }

        val now = OffsetDateTime.now()
        val baseSpecId = Ulid.generate()
        val defaultSpecId = Ulid.generate()

        return pool.withTransaction { connection ->
            val lockMaterial = ctx.select(t.ID, t.UNIT_MODEL_STATUS)
                .from(t)
                .where(t.ID.eq(id))
                .forUpdate()
            val checkSpecs = ctx.select(specs.ID)
                .from(specs)
                .where(specs.MATERIAL_ID.eq(id))
                .limit(1)

            connection.preparedQuery(DatabaseConfig.sql(lockMaterial))
                .execute(DatabaseConfig.tuple(lockMaterial))
                .compose { matRows ->
                    if (matRows.size() == 0) {
                        Future.failedFuture(NotFoundException("material not found"))
                    } else {
                        val current = matRows.iterator().next().getValue(1)?.toString() ?: "LEGACY"
                        if (current != "LEGACY") {
                            Future.failedFuture(
                                ConflictException("material $id already has a unit model (status: $current)"),
                            )
                        } else {
                            connection.preparedQuery(DatabaseConfig.sql(checkSpecs))
                                .execute(DatabaseConfig.tuple(checkSpecs))
                                .map { specRows ->
                                    if (specRows.size() > 0) {
                                        throw ConflictException("material $id already has unit specs")
                                    }
                                }
                        }
                    }
                }
                .compose {
                    val updateMaterial = ctx.update(t)
                        .set(t.BASE_UNIT, baseUnit)
                        .set(t.BASE_QUANTITY_SCALE, scale.toShort())
                        .set(t.UNIT_MODEL_STATUS, "ACTIVE")
                        .set(t.UPDATED_AT, now)
                        .where(t.ID.eq(id))
                    val insertBaseSpec = ctx.insertInto(specs)
                        .set(specs.ID, baseSpecId)
                        .set(specs.MATERIAL_ID, id)
                        .set(specs.INPUT_UNIT, baseUnit)
                        .set(specs.BASE_RATIO, BigDecimal.ONE)
                        .set(specs.IS_BASE_UNIT, true)
                        .set(specs.IS_DEFAULT, false)
                        .set(specs.STATUS, "ACTIVE")
                        .set(specs.CREATED_AT, now)
                    val insertDefaultSpec = ctx.insertInto(specs)
                        .set(specs.ID, defaultSpecId)
                        .set(specs.MATERIAL_ID, id)
                        .set(specs.INPUT_UNIT, defaultInputUnit)
                        .set(specs.BASE_RATIO, defaultRatio)
                        .set(specs.IS_BASE_UNIT, false)
                        .set(specs.IS_DEFAULT, true)
                        .set(specs.STATUS, "ACTIVE")
                        .set(specs.CREATED_AT, now)
                    connection.preparedQuery(DatabaseConfig.sql(updateMaterial))
                        .execute(DatabaseConfig.tuple(updateMaterial))
                        .compose {
                            connection.preparedQuery(DatabaseConfig.sql(insertBaseSpec))
                                .execute(DatabaseConfig.tuple(insertBaseSpec))
                        }
                        .compose {
                            connection.preparedQuery(DatabaseConfig.sql(insertDefaultSpec))
                                .execute(DatabaseConfig.tuple(insertDefaultSpec))
                        }
                }
        }.flatMap { get(id) }
    }

    /**
     * POST /materials/:id/unit-specs：新增一条包装规格。只接受 input_unit、
     * 正 base_ratio 与可选 is_default；服务端决定 ULID 与审计字段。
     * is_default=true 时切换默认规格：存在未结算锁定（locked_base_quantity > 0）
     * 返回 409；新默认规格与旧默认规格的切换在同一事务完成。
     */
    fun createUnitSpec(id: String, body: JsonObject): Future<JsonObject> {
        val allowed = setOf("input_unit", "base_ratio", "is_default")
        body.fieldNames().firstOrNull { it !in allowed }?.let { field ->
            return Future.failedFuture(IllegalArgumentException("unsupported field for unit spec: $field"))
        }
        val inputUnit = body.getString("input_unit")
        if (inputUnit.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("input_unit is required"))
        val ratio = jsonDecimal(body.getValue("base_ratio"))
        if (ratio == null || ratio <= BigDecimal.ZERO)
            return Future.failedFuture(IllegalArgumentException("base_ratio must be positive"))
        val makeDefault = body.getBoolean("is_default") ?: false

        val now = OffsetDateTime.now()
        val specId = Ulid.generate()

        return pool.withTransaction { connection ->
            val lockMaterial = ctx.select(t.ID, t.STATUS, t.UNIT_MODEL_STATUS)
                .from(t)
                .where(t.ID.eq(id))
                .forUpdate()
            val lockStocks = ctx.select(Stocks.STOCKS.ID)
                .from(Stocks.STOCKS)
                .where(Stocks.STOCKS.MATERIAL_ID.eq(id).and(Stocks.STOCKS.LOCKED_BASE_QUANTITY.gt(BigDecimal.ZERO)))
                .limit(1)

            connection.preparedQuery(DatabaseConfig.sql(lockMaterial))
                .execute(DatabaseConfig.tuple(lockMaterial))
                .compose { matRows ->
                    if (matRows.size() == 0) {
                        Future.failedFuture(NotFoundException("material not found"))
                    } else {
                        val row = matRows.iterator().next()
                        if (row.getValue(1)?.toString() != "ACTIVE") {
                            Future.failedFuture(ConflictException("material $id is not ACTIVE"))
                        } else if (row.getValue(2)?.toString() != "ACTIVE") {
                            Future.failedFuture(
                                ConflictException("material $id has no active unit model"),
                            )
                        } else if (makeDefault) {
                            connection.preparedQuery(DatabaseConfig.sql(lockStocks))
                                .execute(DatabaseConfig.tuple(lockStocks))
                                .map { stockRows ->
                                    if (stockRows.size() > 0) {
                                        throw ConflictException(
                                            "cannot switch default unit spec while reservations are locked for material $id",
                                        )
                                    }
                                }
                        } else {
                            Future.succeededFuture(Unit)
                        }
                    }
                }
                .compose {
                    val retireOldDefault = ctx.update(specs)
                        .set(specs.STATUS, "RETIRED")
                        .set(specs.RETIRED_AT, now)
                        .where(
                            specs.MATERIAL_ID.eq(id)
                                .and(specs.IS_DEFAULT.eq(true))
                                .and(specs.STATUS.eq("ACTIVE")),
                        )
                    val insertSpec = ctx.insertInto(specs)
                        .set(specs.ID, specId)
                        .set(specs.MATERIAL_ID, id)
                        .set(specs.INPUT_UNIT, inputUnit)
                        .set(specs.BASE_RATIO, ratio)
                        .set(specs.IS_BASE_UNIT, false)
                        .set(specs.IS_DEFAULT, makeDefault)
                        .set(specs.STATUS, "ACTIVE")
                        .set(specs.CREATED_AT, now)

                    val retire: Future<RowSet<Row>?> = if (makeDefault) {
                        connection.preparedQuery(DatabaseConfig.sql(retireOldDefault))
                            .execute(DatabaseConfig.tuple(retireOldDefault))
                            .map { null }
                    } else {
                        Future.succeededFuture(null)
                    }
                    retire.compose {
                        connection.preparedQuery(DatabaseConfig.sql(insertSpec))
                            .execute(DatabaseConfig.tuple(insertSpec))
                    }
                    // 先停用旧默认再插入新默认，避免触碰部分唯一索引
                    // （material_id WHERE is_default AND status='ACTIVE'）
                }
                .recover { error: Throwable ->
                    if (error is io.vertx.pgclient.PgException && error.sqlState == "23505") {
                        Future.failedFuture(
                            ConflictException("material $id already has an active unit spec with input unit $inputUnit"),
                        )
                    } else {
                        Future.failedFuture(error)
                    }
                }
        }.flatMap { getUnitSpec(id, specId) }
    }

    /**
     * POST /materials/:id/unit-specs/:specId/retire：停用包装规格。默认活动规格与
     * 基础规格不可停用（409）；已引用规格保留历史可读，仅标记 RETIRED，不物理删除。
     */
    fun retireUnitSpec(id: String, specId: String): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return pool.withTransaction { connection ->
            val lockQuery = ctx.selectFrom(specs)
                .where(specs.ID.eq(specId).and(specs.MATERIAL_ID.eq(id)))
                .forUpdate()
            connection.preparedQuery(DatabaseConfig.sql(lockQuery))
                .execute(DatabaseConfig.tuple(lockQuery))
                .compose { rows ->
                    if (rows.size() == 0) {
                        Future.failedFuture(NotFoundException("unit spec not found: $specId for material $id"))
                    } else {
                        val row = rows.iterator().next()
                        val status = row.getValue("status")?.toString()
                        val isBase = row.getValue("is_base_unit") as? Boolean ?: false
                        val isDefault = row.getValue("is_default") as? Boolean ?: false
                        if (status != "ACTIVE") {
                            Future.failedFuture(ConflictException("unit spec $specId is already retired"))
                        } else if (isBase) {
                            Future.failedFuture(ConflictException("base unit spec $specId cannot be retired"))
                        } else if (isDefault) {
                            Future.failedFuture(
                                ConflictException("unit spec $specId is the active default spec and cannot be retired"),
                            )
                        } else {
                            val oldInputUnit = row.getValue("input_unit")?.toString()
                            val oldRatio = (row.getValue("base_ratio") as? BigDecimal)?.toDouble()
                            val oldCreatedAt = row.getValue("created_at")?.toString()
                            val retire = ctx.update(specs)
                                .set(specs.STATUS, "RETIRED")
                                .set(specs.RETIRED_AT, now)
                                .where(specs.ID.eq(specId))
                            connection.preparedQuery(DatabaseConfig.sql(retire))
                                .execute(DatabaseConfig.tuple(retire))
                                .map {
                                    JsonObject()
                                        .put("id", specId)
                                        .put("material_id", id)
                                        .put("input_unit", oldInputUnit)
                                        .put("base_ratio", oldRatio)
                                        .put("is_base_unit", isBase)
                                        .put("is_default", isDefault)
                                        .put("status", "RETIRED")
                                        .put("created_at", oldCreatedAt)
                                        .put("retired_at", now.toString())
                                }
                        }
                    }
                }
        }
    }

    private fun getUnitSpec(materialId: String, specId: String): Future<JsonObject> {
        val query = ctx.selectFrom(specs)
            .where(specs.ID.eq(specId).and(specs.MATERIAL_ID.eq(materialId)))
        return pool.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .map { rows ->
                if (rows.size() == 0) throw NotFoundException("unit spec not found: $specId for material $materialId")
                unitSpecToJson(rows.iterator().next())
            }
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
                .put("base_unit", row.getValue("base_unit")?.toString())
                .put("base_quantity_scale", (row.getValue("base_quantity_scale") as? Number)?.toInt())
                .put("unit_model_status", row.getValue("unit_model_status")?.toString() ?: "LEGACY")
                .put("created_at", row.getValue("created_at")?.toString())
                .put("updated_at", row.getValue("updated_at")?.toString())
        }

        fun unitSpecToJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("material_id", row.getValue("material_id")?.toString())
                .put("input_unit", row.getValue("input_unit")?.toString())
                .put("base_ratio", (row.getValue("base_ratio") as? BigDecimal)?.toDouble())
                .put("is_base_unit", row.getValue("is_base_unit") as? Boolean)
                .put("is_default", row.getValue("is_default") as? Boolean)
                .put("status", row.getValue("status")?.toString())
                .put("created_at", row.getValue("created_at")?.toString())
                .put("retired_at", row.getValue("retired_at")?.toString())
        }
    }
}

private fun BigDecimal.toIntExactOrReject(): Int? =
    runCatching { intValueExact() }.getOrNull()
