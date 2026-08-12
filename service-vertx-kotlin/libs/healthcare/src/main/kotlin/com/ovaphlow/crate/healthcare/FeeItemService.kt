package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.FeeItems.FEE_ITEMS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
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

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxUnitPrice = BigDecimal("9999999999.99")

        /** 写白名单：status/created_at/updated_at/id 由服务端管控 */
        private val createKeys = setOf("category", "name", "unit_price", "remark", "metadata")
        private val updateKeys = setOf("category", "name", "unit_price", "remark", "metadata")
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
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  创建
    // ========================================================================

    /** 创建费用项目：分类/名称/单价必填且校验；状态默认 启用。 */
    fun createItem(body: JsonObject): Future<JsonObject> {
        val fields = try {
            validateCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
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
        return execute(pool, query).map {
            JsonObject()
                .put("id", id)
                .put("category", fields.category)
                .put("name", fields.name)
                .put("unit_price", fields.unitPrice)
                .put("status", STATUS_ENABLED)
                .put("remark", fields.remark)
                .put("metadata", fields.metadata)
                .put("created_at", now.toString())
                .put("updated_at", now.toString())
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

    /** 全量更新字典字段（分类/名称/单价/备注/扩展）；状态只能走 PATCH 流转。 */
    fun updateItem(id: String, body: JsonObject): Future<JsonObject> {
        val fields = try {
            validateUpdate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireItem(connection, id).compose {
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

    /** 启用/停用流转：非法状态值 400；不存在 404。 */
    fun updateItemStatus(id: String, body: JsonObject): Future<JsonObject> {
        val status = try {
            validateStatus(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireItem(connection, id).compose {
                execute(
                    connection,
                    ctx.update(FEE_ITEMS)
                        .set(FEE_ITEMS.STATUS, status)
                        .set(FEE_ITEMS.UPDATED_AT, now)
                        .where(FEE_ITEMS.ID.eq(id)),
                ).compose { updatedJson(connection, id) }
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
        val metadata: JsonObject?,
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
        val metadata = jsonObject(body, "metadata")
        return Fields(category, name, unitPrice, remark, metadata)
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

    /** 更新/状态流转前确认存在：不存在 404。 */
    private fun requireItem(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(FEE_ITEMS).where(FEE_ITEMS.ID.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("fee item not found: $id"))
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
            FEE_ITEMS.CREATED_AT,
            FEE_ITEMS.UPDATED_AT,
        ).from(FEE_ITEMS)
            .where(FEE_ITEMS.ID.eq(id))

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
