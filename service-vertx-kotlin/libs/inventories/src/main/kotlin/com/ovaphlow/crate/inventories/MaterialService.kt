package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 025 药品目录快照（只读）：供医嘱/发药在同连接内按 id 判定目录药品。
 *
 * 只含目录判定与快照回填所需字段，不含库存、批次或包装换算；`category = '药品'`
 * 与 `status = 'ACTIVE'` 由调用方判定。`code`/`name` 在目录中为 NOT NULL，
 * 这里保留可空以表达“读取失败/字段缺失”而非抛异常。
 */
data class MaterialSnapshot(
    val id: String,
    val code: String?,
    val name: String?,
    val spec: String?,
    val baseUnit: String?,
    val status: String?,
    val category: String?,
)

/**
 * 016 单一基础单位物资服务。
 *
 * 每个物资在创建时一次性提交 `base_unit` 与 `quantity_scale`（0..6），不再存在
 * 包装单位、拆零单位、换算率、规格表或计量模型状态。base_unit / quantity_scale
 * 在物资存在库存事实后不可修改（409），与 ADR-001 的“存在流水后不可修改”一致。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class MaterialService(
    private val pool: Pool,
) {
    private val createAllowed = setOf(
        "code", "name", "category", "spec", "base_unit", "quantity_scale",
        "enable_batch_control", "cost_method", "metadata", "status",
        "package_unit", "package_size",
    )
    private val updateAllowed = setOf(
        "name", "category", "spec", "base_unit", "quantity_scale",
        "enable_batch_control", "cost_method", "metadata", "status",
        "package_unit", "package_size",
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
        validatePackageSpec(body)

        val packageSize = body.getValue("package_size")?.let { requestDecimalText(it) }
        return pool.preparedQuery(INSERT_SQL).execute(
            Tuple.of(
                id, code, name, category, body.getString("spec"), baseUnit,
                body.getString("package_unit"), packageSize, scale,
                body.getBoolean("enable_batch_control"), body.getString("cost_method"),
                body.getJsonObject("metadata"), body.getString("status"), now,
            )
        ).map {
            JsonObject()
                .put("id", id)
                .put("code", code)
                .put("name", name)
                .put("category", category)
                .put("spec", body.getString("spec"))
                .put("base_unit", baseUnit)
                .put("package_unit", body.getString("package_unit"))
                .put("package_size", decimalText(packageSize))
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
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        code?.let { where.add("code = ${bind(it)}") }
        name?.let { where.add("name LIKE '%' || ${bind(it)} || '%'") }
        category?.let { where.add("category = ${bind(it)}") }
        status?.let { where.add("status = ${bind(it)}") }
        enableBatchControl?.let { where.add("enable_batch_control = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total FROM materials$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $COLUMNS FROM materials$whereClause " +
                "ORDER BY created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records).put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT $COLUMNS FROM materials WHERE id = \$1").execute(Tuple.of(id))
            .flatMap { rows ->
                if (rows.size() == 0) Future.failedFuture(NotFoundException("material not found"))
                else Future.succeededFuture(toJson(rows.iterator().next()))
            }

    /**
     * 025 药品目录同连接只读快照：按 id 读取目录物资。
     *
     * 必须复用调用方外层事务连接（禁止内部重新取 Pool 或开启新事务），
     * 只读不锁行、不写任何表；物资不存在返回 null，由调用方映射 404。
     */
    fun findMaterialById(client: SqlClient, id: String): Future<MaterialSnapshot?> =
        client.preparedQuery(
            "SELECT id, code, name, spec, base_unit, status, category FROM materials WHERE id = \$1"
        ).execute(Tuple.of(id)).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                MaterialSnapshot(
                    id = row.getString("id"),
                    code = row.getString("code"),
                    name = row.getString("name"),
                    spec = row.getString("spec"),
                    baseUnit = row.getString("base_unit"),
                    status = row.getString("status"),
                    category = row.getString("category"),
                )
            }
        }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        rejectUnknown(body, updateAllowed)?.let { return Future.failedFuture(it) }
        return get(id).flatMap { existing ->
            validatePackageSpec(body)
            // 记账口径字段（base_unit/quantity_scale）存在库存事实后不可改；
            // 包装规格仅是展示投影、不影响台账，可随时调整。
            val immutableChanged = listOf("base_unit", "quantity_scale")
                .any { immutableFieldChanged(existing, body, it) }
            if (immutableChanged) {
                pool.preparedQuery("SELECT id FROM stocks WHERE material_id = \$1 LIMIT 1")
                    .execute(Tuple.of(id))
                    .compose { stockRows ->
                        if (stockRows.size() > 0) {
                            Future.failedFuture(
                                ConflictException(
                                    "base_unit/quantity_scale are immutable once stock facts exist for material $id",
                                ),
                            )
                        } else {
                            applyUpdate(id, body)
                        }
                    }
            } else {
                applyUpdate(id, body)
            }
        }
    }

    /** 不可变字段是否发生了值变化（null 视为空值，数字按数值比较，其余按字符串比较） */
    private fun immutableFieldChanged(existing: JsonObject, body: JsonObject, key: String): Boolean {
        if (!body.containsKey(key)) return false
        val a = existing.getValue(key)
        val b = body.getValue(key)
        return when {
            a == null && b == null -> false
            a == null || b == null -> true
            a is Number && b is Number -> {
                val x = jsonDecimal(a)
                val y = jsonDecimal(b)
                if (x == null || y == null) true else x.compareTo(y) != 0
            }
            else -> a.toString() != b.toString()
        }
    }

    private fun applyUpdate(id: String, body: JsonObject): Future<JsonObject> {
        val sets = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        if (body.containsKey("name")) sets.add("name = ${bind(body.getString("name"))}")
        if (body.containsKey("category")) sets.add("category = ${bind(body.getString("category"))}")
        if (body.containsKey("spec")) sets.add("spec = ${bind(body.getString("spec"))}")
        if (body.containsKey("base_unit")) sets.add("base_unit = ${bind(body.getString("base_unit"))}")
        if (body.containsKey("package_unit")) sets.add("package_unit = ${bind(body.getString("package_unit"))}")
        if (body.containsKey("package_size"))
            sets.add("package_size = ${bind(body.getValue("package_size")?.let { requestDecimalText(it) })}")
        if (body.containsKey("quantity_scale")) sets.add("quantity_scale = ${bind(parseScale(body.getValue("quantity_scale")))}")
        if (body.containsKey("enable_batch_control"))
            sets.add("enable_batch_control = ${bind(body.getBoolean("enable_batch_control"))}")
        if (body.containsKey("cost_method")) sets.add("cost_method = ${bind(body.getString("cost_method"))}")
        if (body.containsKey("metadata")) sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")
        if (body.containsKey("status")) sets.add("status = ${bind(body.getString("status"))}")
        sets.add("updated_at = ${bind(OffsetDateTime.now())}")

        val sql = "UPDATE materials SET ${sets.joinToString(", ")} WHERE id = ${bind(id)}"
        return pool.preparedQuery(sql).execute(Tuple.from(args)).flatMap { get(id) }
    }

    fun delete(id: String): Future<Void?> =
        pool.preparedQuery("DELETE FROM materials WHERE id = \$1").execute(Tuple.of(id))
            .map { null as Void? }

    /** 包装规格成对校验：package_unit 与 package_size 必须同空或同非空，且每包含量 > 0 */
    private fun validatePackageSpec(body: JsonObject) {
        val unit = body.getString("package_unit")
        val sizeRaw = body.getValue("package_size")
        if (unit.isNullOrBlank() && sizeRaw == null) return
        if (unit.isNullOrBlank() || sizeRaw == null) {
            throw IllegalArgumentException("package_unit and package_size must be both set or both empty")
        }
        val size = requestDecimalText(sizeRaw)
            ?: throw IllegalArgumentException("package_size must be a decimal text")
        if (size <= BigDecimal.ZERO) throw IllegalArgumentException("package_size must be greater than 0")
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
        private const val COLUMNS =
            "id, code, name, category, spec, base_unit, package_unit, package_size, quantity_scale, " +
                "enable_batch_control, cost_method, metadata, status, created_at, updated_at"

        private const val INSERT_SQL =
            "INSERT INTO materials " +
                "(id, code, name, category, spec, base_unit, package_unit, package_size, quantity_scale, " +
                "enable_batch_control, cost_method, metadata, status, created_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14)"

        fun toJson(row: Row): JsonObject {
            return JsonObject()
                .put("id", row.getValue("id")?.toString())
                .put("code", row.getValue("code")?.toString())
                .put("name", row.getValue("name")?.toString())
                .put("category", row.getValue("category")?.toString())
                .put("spec", row.getValue("spec")?.toString())
                .put("base_unit", row.getValue("base_unit")?.toString())
                .put("package_unit", row.getValue("package_unit")?.toString())
                .put("package_size", decimalText(jsonDecimal(row.getValue("package_size"))))
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
