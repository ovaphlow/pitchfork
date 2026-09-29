package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.FeeItems.FEE_ITEMS
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 费用项目字典服务（养老费用管理独立子任务）。
 *
 * 业务规则（服务端强制）：
 *  1. 分类中文枚举：床位费/护理费/伙食费/个性化服务费/押金/其他
 *     （DB CHECK 兜底 + 应用层白名单校验 400）。
 *  2. 单价 NUMERIC(12,2)：正数且至多两位小数，上限 9999999999.99。
 *  3. 状态 启用/停用（中文值，应用层白名单管控，默认 启用）；
 *     启用/停用通过 PATCH /:id/status 独立流转，PUT 更新不得改动状态。
 *  4. 字典为账单自动计费提供单价来源；账单明细为快照，
 *     字典改价/停用不影响已生成账单。
 *  5. 列表按 created_at 倒序分页，返回 {records, meta:{total}}；
 *     空列表 records: [] 且 total: 0。
 *  6. 护理等级（030 W1，契约冻结）：`category = 护理费` 必填顶层键 `nursing_level`，
 *     且 ∈ 低风险/中风险/高风险/无需干预；非护理费不得携带（传非空值 400）。
 *     服务端把等级落 `metadata.nursing_level` 并在响应顶层拉平为 `nursing_level`；
 *     `metadata.nursing_level` 是服务端字段（客户端经 metadata 写入一律被剥离/覆盖）。
 *     同一护理等级至多一条**启用**项：服务端事务内检查 + DB 部分唯一索引
 *     `uq_fee_items_nursing_level_enabled` 兜底，违反一律 409。
 */
class FeeItemService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 分类中文枚举（DB CHECK 与应用层白名单保持一致） */
        val categories = setOf("床位费", "护理费", "伙食费", "个性化服务费", "押金", "其他")

        const val STATUS_ENABLED = "启用"
        const val STATUS_DISABLED = "停用"

        /** 状态白名单：启用/停用（中文值） */
        val statuses = setOf(STATUS_ENABLED, STATUS_DISABLED)

        /** 护理费分类（等级绑定与唯一性检查只适用于本分类）。 */
        const val CATEGORY_NURSING = "护理费"

        /** 护理等级四值枚举（与评估表单 `result_level` 的既有中文值一致，不引入英文 code）。 */
        val nursingLevels = setOf("低风险", "中风险", "高风险", "无需干预")

        /** 护理等级在 metadata 中的键（服务端字段，API 顶层拉平为 `nursing_level`）。 */
        const val METADATA_NURSING_LEVEL = "nursing_level"

        /** 校验文案（契约冻结，逐字不可改）。 */
        const val NURSING_LEVEL_REQUIRED = "nursing_level is required for 护理费 fee item"
        const val NURSING_LEVEL_ONLY_NURSING = "nursing_level is only allowed for 护理费 fee item"

        /** 非法值文案（枚举顺序即 [nursingLevels] 的声明顺序）。 */
        private fun nursingLevelEnumMessage(): String =
            "nursing_level must be one of: ${nursingLevels.joinToString(", ")}"

        /** 护理等级列表达式：`metadata ->> 'nursing_level'`（别名供 Row 按名读取）。 */
        val nursingLevelColumn: Field<String> =
            DSL.field("{0} ->> 'nursing_level'", String::class.java, FEE_ITEMS.METADATA).`as`(METADATA_NURSING_LEVEL)

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxUnitPrice = BigDecimal("9999999999.99")

        /** 写白名单：status/created_at/updated_at/id 由服务端管控；nursing_level 为业务字段 */
        private val createKeys = setOf("category", "name", "unit_price", "remark", "metadata", METADATA_NURSING_LEVEL)
        private val updateKeys = setOf("category", "name", "unit_price", "remark", "metadata", METADATA_NURSING_LEVEL)
        private val statusKeys = setOf("status")

        private fun recordJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("category", row.getString("category"))
                .put("name", row.getString("name"))
                .put("unit_price", row.getBigDecimal("unit_price"))
                .put("status", row.getString("status"))
                .put("remark", row.getString("remark"))
                .put("metadata", row.getValue("metadata"))
                .put(METADATA_NURSING_LEVEL, row.getString(METADATA_NURSING_LEVEL))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  创建
    // ========================================================================

    /**
     * 创建费用项目：分类/名称/单价必填且校验；状态默认 启用。
     * 护理费必填四值枚举等级，落 `metadata.nursing_level`；
     * 同一等级至多一条启用项（事务内检查 + DB 部分唯一索引兜底，冲突 409）。
     */
    fun createItem(body: JsonObject): Future<JsonObject> {
        val fields = try {
            validateCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireLevelAvailable(connection, fields.category, fields.nursingLevel, excludeId = null)
                .compose { execute(connection, insertItemQuery(id, fields, now)) }
                .map {
                    JsonObject()
                        .put("id", id)
                        .put("category", fields.category)
                        .put("name", fields.name)
                        .put("unit_price", fields.unitPrice)
                        .put("status", STATUS_ENABLED)
                        .put("remark", fields.remark)
                        .put("metadata", fields.metadata)
                        .put(METADATA_NURSING_LEVEL, fields.nursingLevel)
                        .put("created_at", now.toString())
                        .put("updated_at", now.toString())
                }
                .recover { error -> recoverUniqueViolation(error, fields.category, fields.nursingLevel) }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    /** 字典列表：支持 category/status 过滤，倒序分页，返回 {records, meta:{total}}。 */
    fun listItems(
        category: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<org.jooq.Condition>()
        category?.takeIf(String::isNotBlank)?.let { conditions += FEE_ITEMS.CATEGORY.eq(it) }
        status?.takeIf(String::isNotBlank)?.let { conditions += FEE_ITEMS.STATUS.eq(it) }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(FEE_ITEMS).where(conditions)
        val dataQuery = ctx.select(
            FEE_ITEMS.ID,
            FEE_ITEMS.CATEGORY,
            FEE_ITEMS.NAME,
            FEE_ITEMS.UNIT_PRICE,
            FEE_ITEMS.STATUS,
            FEE_ITEMS.REMARK,
            FEE_ITEMS.METADATA,
            nursingLevelColumn,
            FEE_ITEMS.CREATED_AT,
            FEE_ITEMS.UPDATED_AT,
        ).from(FEE_ITEMS)
            .where(conditions)
            .orderBy(FEE_ITEMS.CREATED_AT.desc(), FEE_ITEMS.ID.desc())
            .limit(limit)
            .offset(offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::recordJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 单条查询：不存在 404。 */
    fun getItem(id: String): Future<JsonObject> =
        execute(pool, selectById(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(recordJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("fee item not found: $id"))
        }

    // ========================================================================
    //  更新
    // ========================================================================

    /**
     * 全量更新字典字段（分类/名称/单价/备注/扩展）；状态只能走 PATCH 流转。
     * 更新后仍是「启用 + 护理费」时按目标等级做同等级唯一性检查（冲突 409）。
     */
    fun updateItem(id: String, body: JsonObject): Future<JsonObject> {
        val fields = try {
            validateUpdate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireItem(connection, id).compose { existing ->
                val guard = if (existing.getString("status") == STATUS_ENABLED) {
                    requireLevelAvailable(connection, fields.category, fields.nursingLevel, excludeId = id)
                } else {
                    Future.succeededFuture()
                }
                guard.compose {
                    var query = ctx.update(FEE_ITEMS)
                        .set(FEE_ITEMS.CATEGORY, fields.category)
                        .set(FEE_ITEMS.NAME, fields.name)
                        .set(FEE_ITEMS.UNIT_PRICE, fields.unitPrice)
                        .set(FEE_ITEMS.UPDATED_AT, now)
                    if (fields.remark != null) query = query.set(FEE_ITEMS.REMARK, fields.remark)
                    else query = query.setNull(FEE_ITEMS.REMARK)
                    if (fields.metadata != null) query = query.set(FEE_ITEMS.METADATA, JSONB.valueOf(fields.metadata.encode()))
                    else query = query.setNull(FEE_ITEMS.METADATA)
                    execute(connection, query.where(FEE_ITEMS.ID.eq(id))).compose { updatedJson(connection, id) }
                }.recover { error -> recoverUniqueViolation(error, fields.category, fields.nursingLevel) }
            }
        }
    }

    // ========================================================================
    //  删除
    // ========================================================================

    /** 删除字典条目：不存在 404。 */
    fun deleteItem(id: String): Future<JsonObject> =
        execute(pool, ctx.deleteFrom(FEE_ITEMS).where(FEE_ITEMS.ID.eq(id))).compose { rows ->
            if (rows.rowCount() == 1) {
                Future.succeededFuture(JsonObject().put("id", id))
            } else {
                Future.failedFuture(HealthcareNotFoundException("fee item not found: $id"))
            }
        }

    // ========================================================================
    //  状态流转（启用/停用）
    // ========================================================================

    /** 启用/停用流转：非法状态值 400；不存在 404；启用护理费时同等级唯一性检查（409）。 */
    fun updateItemStatus(id: String, body: JsonObject): Future<JsonObject> {
        val status = try {
            validateStatus(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireItem(connection, id).compose { existing ->
                val level = existing.getString(METADATA_NURSING_LEVEL)
                val guard = if (status == STATUS_ENABLED) {
                    requireLevelAvailable(connection, existing.getString("category") ?: "", level, excludeId = id)
                } else {
                    Future.succeededFuture()
                }
                guard.compose {
                    execute(
                        connection,
                        ctx.update(FEE_ITEMS)
                            .set(FEE_ITEMS.STATUS, status)
                            .set(FEE_ITEMS.UPDATED_AT, now)
                            .where(FEE_ITEMS.ID.eq(id)),
                    ).compose { updatedJson(connection, id) }
                }.recover { error ->
                    recoverUniqueViolation(error, existing.getString("category") ?: "", level)
                }
            }
        }
    }

    // ========================================================================
    //  内部实现
    // ========================================================================

    private data class Fields(
        val category: String,
        val name: String,
        val unitPrice: BigDecimal,
        val remark: String?,
        /** 落库用的**有效** metadata：已按分类合并/剥离 `nursing_level`。 */
        val metadata: JsonObject?,
        /** 护理等级（非护理费为 null）；只作为服务端字段写 `metadata.nursing_level`。 */
        val nursingLevel: String?,
    )

    private fun validateCreate(body: JsonObject): Fields {
        rejectForbiddenKeys(body, createKeys, "fee item")
        return parseFields(body, "category is required")
    }

    private fun validateUpdate(body: JsonObject): Fields {
        rejectForbiddenKeys(body, updateKeys, "fee item")
        return parseFields(body, "category is required")
    }

    private fun parseFields(body: JsonObject, missingCategoryMessage: String): Fields {
        val category = requiredCategory(body, missingCategoryMessage)
        val name = requiredText(body, "name")
        val unitPrice = numericUnitPrice(body)
        val remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
        }
        val nursingLevel = nursingLevelOf(category, body)
        val metadata = metadataWithNursingLevel(jsonObject(body, "metadata"), category, nursingLevel)
        return Fields(category, name, unitPrice, remark, metadata, nursingLevel)
    }

    /**
     * 护理等级校验（契约冻结文案）：
     *  - `category = 护理费`：必填，trim 后非空且 ∈ [nursingLevels]；
     *    缺失/null/空白 → `is required`；非字符串或不在枚举 → `must be one of`；
     *  - 其他分类：省略/null/空白合法，传非空值 → 400（非护理费不得携带）。
     */
    private fun nursingLevelOf(category: String, body: JsonObject): String? {
        val raw = body.getValue(METADATA_NURSING_LEVEL)
        if (category != CATEGORY_NURSING) {
            if (raw != null && (raw !is String || raw.isNotBlank())) {
                throw IllegalArgumentException(NURSING_LEVEL_ONLY_NURSING)
            }
            return null
        }
        if (raw == null) throw IllegalArgumentException(NURSING_LEVEL_REQUIRED)
        val value = (raw as? String)?.trim() ?: throw IllegalArgumentException(nursingLevelEnumMessage())
        if (value.isEmpty()) throw IllegalArgumentException(NURSING_LEVEL_REQUIRED)
        if (value !in nursingLevels) throw IllegalArgumentException(nursingLevelEnumMessage())
        return value
    }

    /**
     * `metadata` 合并写：`nursing_level` 是服务端字段，
     * 客户端经 metadata 直接写入一律剥离，护理费按顶层键回填；其余键原样保留。
     *
     * 030 评审 P2-6：只有「未提供 metadata」才归一为 null；客户端显式传 `{}` 时保留空对象
     * （与 §4.1「其余键原样保留」及 base 语义一致，避免响应从 `{}` 漂移成 `null`）。
     */
    private fun metadataWithNursingLevel(input: JsonObject?, category: String, level: String?): JsonObject? {
        val metadata = input?.copy() ?: JsonObject()
        metadata.remove(METADATA_NURSING_LEVEL)
        if (category == CATEGORY_NURSING && level != null) metadata.put(METADATA_NURSING_LEVEL, level)
        return if (metadata.isEmpty && input == null) null else metadata
    }

    private fun validateStatus(body: JsonObject): String {
        rejectForbiddenKeys(body, statusKeys, "status")
        val raw = body.getValue("status") ?: throw IllegalArgumentException("status is required")
        val status = raw as? String ?: throw IllegalArgumentException("status must be a string")
        if (status !in statuses) {
            throw IllegalArgumentException("status must be one of: ${statuses.joinToString(", ")}")
        }
        return status
    }

    private fun requiredCategory(body: JsonObject, missingMessage: String): String {
        val raw = body.getValue("category") ?: throw IllegalArgumentException(missingMessage)
        val category = raw as? String ?: throw IllegalArgumentException("category must be a string")
        if (category !in categories) {
            throw IllegalArgumentException("category must be one of: ${categories.joinToString(", ")}")
        }
        return category
    }

    private fun requiredText(body: JsonObject, key: String): String {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val value = raw as? String ?: throw IllegalArgumentException("$key must be a string")
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("$key must not be blank")
        if (trimmed.length > 100) throw IllegalArgumentException("$key must not exceed 100 characters")
        return trimmed
    }

    private fun numericUnitPrice(body: JsonObject): BigDecimal {
        val raw = body.getValue("unit_price") ?: throw IllegalArgumentException("unit_price is required")
        val value = (raw as? Number)?.toDouble()
            ?: throw IllegalArgumentException("unit_price must be a number")
        if (!value.isFinite() || value <= 0) {
            throw IllegalArgumentException("unit_price must be a positive number")
        }
        val decimal = BigDecimal.valueOf(value)
        if (decimal.scale() > 2) {
            throw IllegalArgumentException("unit_price must have at most 2 decimal places")
        }
        if (decimal > maxUnitPrice) {
            throw IllegalArgumentException("unit_price must not exceed $maxUnitPrice")
        }
        return decimal
    }

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key)
        if (value == null) return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    /** 创建写库语句（ID/状态/时间戳由服务端管控）。 */
    private fun insertItemQuery(id: String, fields: Fields, now: OffsetDateTime): Query {
        var query = ctx.insertInto(FEE_ITEMS)
            .set(FEE_ITEMS.ID, id)
            .set(FEE_ITEMS.CATEGORY, fields.category)
            .set(FEE_ITEMS.NAME, fields.name)
            .set(FEE_ITEMS.UNIT_PRICE, fields.unitPrice)
            .set(FEE_ITEMS.STATUS, STATUS_ENABLED)
            .set(FEE_ITEMS.CREATED_AT, now)
            .set(FEE_ITEMS.UPDATED_AT, now)
        fields.remark?.let { query = query.set(FEE_ITEMS.REMARK, it) }
        fields.metadata?.let { query = query.set(FEE_ITEMS.METADATA, JSONB.valueOf(it.encode())) }
        return query
    }

    /** 更新/状态流转前确认存在：不存在 404（含分类/状态/等级，供同等级唯一性检查复用）。 */
    private fun requireItem(client: SqlClient, id: String): Future<Row> =
        execute(
            client,
            ctx.select(FEE_ITEMS.ID, FEE_ITEMS.CATEGORY, FEE_ITEMS.STATUS, nursingLevelColumn)
                .from(FEE_ITEMS)
                .where(FEE_ITEMS.ID.eq(id)),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("fee item not found: $id"))
        }

    /**
     * 启用态同等级唯一性检查（服务端事务内，[excludeId] 为被更新的自身）：
     * 只对 `护理费 + 非空等级` 生效；命中 → 409
     * `another enabled 护理费 item already bound to level <level>`。
     */
    private fun requireLevelAvailable(
        client: SqlClient,
        category: String,
        level: String?,
        excludeId: String?,
    ): Future<Unit> {
        if (category != CATEGORY_NURSING || level == null) return Future.succeededFuture()
        return execute(client, enabledNursingItemsQuery()).map { rows ->
            val conflict = rows.iterator().asSequence().any { row ->
                row.getString(METADATA_NURSING_LEVEL) == level && row.getString("id") != excludeId
            }
            if (conflict) {
                throw ConflictException("another enabled $CATEGORY_NURSING item already bound to level $level")
            }
            Unit
        }
    }

    /** 全部启用护理费项（id + 等级），供同等级唯一性检查（与 V520 部分唯一索引同口径）。 */
    private fun enabledNursingItemsQuery(): Query =
        ctx.select(FEE_ITEMS.ID, nursingLevelColumn)
            .from(FEE_ITEMS)
            .where(FEE_ITEMS.CATEGORY.eq(CATEGORY_NURSING))
            .and(FEE_ITEMS.STATUS.eq(STATUS_ENABLED))

    /**
     * 唯一索引兜底（并发竞态）：23505 / `uq_fee_items_*` → 409；
     * 其他错误原样抛出（不把无关失败伪装成冲突）。
     */
    private fun recoverUniqueViolation(error: Throwable, category: String, level: String?): Future<JsonObject> {
        val message = error.message ?: ""
        val isUniqueViolation = (error as? io.vertx.pgclient.PgException)?.sqlState == "23505" ||
            message.contains("uq_fee_items_nursing_level_enabled") ||
            message.contains("uq_fee_items_single_enabled_per_category")
        if (!isUniqueViolation) return Future.failedFuture(error)
        val conflict = if (category == CATEGORY_NURSING && level != null) {
            ConflictException("another enabled $CATEGORY_NURSING item already bound to level $level")
        } else {
            ConflictException("another enabled $category item already exists")
        }
        return Future.failedFuture(conflict)
    }

    private fun updatedJson(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, selectById(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(recordJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("fee item not found: $id"))
        }

    private fun selectById(id: String): Query =
        ctx.select(
            FEE_ITEMS.ID,
            FEE_ITEMS.CATEGORY,
            FEE_ITEMS.NAME,
            FEE_ITEMS.UNIT_PRICE,
            FEE_ITEMS.STATUS,
            FEE_ITEMS.REMARK,
            FEE_ITEMS.METADATA,
            nursingLevelColumn,
            FEE_ITEMS.CREATED_AT,
            FEE_ITEMS.UPDATED_AT,
        ).from(FEE_ITEMS)
            .where(FEE_ITEMS.ID.eq(id))

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
