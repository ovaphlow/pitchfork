package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 保育员环境安全巡查服务（054 C2，迁移 V414）。
 *
 * ## 隐患闭环（§5.8 冻结）
 *
 * `待整改 → 已整改 → 已关闭`，**严格线性**：
 *   - 创建即 `待整改`；
 *   - `POST /:id/items` 追加整改/复查记录：`待整改 → 已整改`（已整改时仅追加，状态不变）；
 *     `已关闭` 后再追加 → 409（终态不可再改）；
 *   - `PATCH /:id/close` 关闭：仅 `已整改 → 已关闭`；`待整改` 直接关闭（跳步）→ **409**；
 *     已关闭再关闭 → 409。
 *
 * ## 检查项
 *
 *  `items` 为 JSONB 数组快照，元素 `{item_code, result, hazard?}`：
 *   - `item_code` 必须命中常量表 [CHECK_ITEMS]（12 项环境安全检查项）；
 *   - `result` ∈ [RESULTS]（`合格 / 不合格 / 不适用`；§5.8 未冻结该字段，本任务定义）；
 *   - `hazard` 为隐患描述（自由文本，可空）。
 *
 * `operator` 取认证主体（042 口径）；持久化走**原生参数化 SQL**，本文件不引入 jOOQ。
 */
class ChildcareSafetyInspectionService(private val pool: Pool) {

    companion object {
        /** §5.8 冻结：`ChildcareSafetyInspection.status`。 */
        const val STATUS_PENDING = "待整改"
        const val STATUS_RECTIFIED = "已整改"
        const val STATUS_CLOSED = "已关闭"

        val STATUSES = setOf(STATUS_PENDING, STATUS_RECTIFIED, STATUS_CLOSED)

        /** 隐患闭环状态机（严格线性）。 */
        val TRANSITIONS: Map<String, Set<String>> = mapOf(
            STATUS_PENDING to setOf(STATUS_RECTIFIED),
            STATUS_RECTIFIED to setOf(STATUS_CLOSED),
            STATUS_CLOSED to emptySet(),
        )

        /** 检查项结果（§5.8 未冻结 → 本任务定义）。 */
        val RESULTS = setOf("合格", "不合格", "不适用")

        /** 检查项常量表（12 项环境安全巡查）。 */
        val CHECK_ITEMS: List<Pair<String, String>> = listOf(
            "FLOOR_SLIP" to "地面防滑与积水",
            "FURNITURE_EDGE" to "家具棱角防护",
            "ELECTRIC_SOCKET" to "电源插座防护",
            "TOY_SMALL_PARTS" to "玩具小零件与破损",
            "WINDOW_GUARD" to "窗户护栏与限位",
            "STAIR_GATE" to "楼梯口安全门",
            "MEDICINE_STORAGE" to "药品与消毒剂存放",
            "FOOD_HYGIENE" to "食品留样与卫生",
            "FIRE_EQUIPMENT" to "消防器材与疏散通道",
            "OUTDOOR_EQUIPMENT" to "户外器械稳固与磨损",
            "WATER_AREA" to "水域与饮水设备安全",
            "SANITATION" to "卫生间清洁与防滑",
        )

        val CHECK_ITEM_CODES: Set<String> = CHECK_ITEMS.map { it.first }.toSet()

        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        private const val DAY_EXPR = "(i.inspected_at AT TIME ZONE 'Asia/Shanghai')::date"

        private const val INSERT_SQL =
            "INSERT INTO nursing.nursing_childcare_safety_inspections " +
                "(id, area, inspected_at, items, status, operator, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9)"

        private const val UPDATE_ITEMS_SQL =
            "UPDATE nursing.nursing_childcare_safety_inspections " +
                "SET items = \$1, status = \$2, updated_at = \$3 WHERE id = \$4"

        private const val UPDATE_STATUS_SQL =
            "UPDATE nursing.nursing_childcare_safety_inspections " +
                "SET status = \$1, updated_at = \$2 WHERE id = \$3"

        private const val SELECT_COLUMNS =
            "i.id, i.area, i.inspected_at, i.items, i.status, i.operator, i.metadata, " +
                "i.created_at, i.updated_at"

        private const val SELECT_FROM = " FROM nursing.nursing_childcare_safety_inspections i "

        private const val GET_SQL = "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE i.id = \$1"

        private const val COUNT_SQL = "SELECT count(*) AS total" + SELECT_FROM

        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("area", row.getString("area"))
            .put("inspected_at", row.getOffsetDateTime("inspected_at")?.toString())
            .put("items", row.getJsonArray("items") ?: JsonArray())
            .put("status", row.getString("status"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 创建巡查记录（初始 `待整改`）。参数非法抛 `IllegalArgumentException`（400）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val area = body.getString("area")?.trim()
        if (area.isNullOrEmpty()) throw IllegalArgumentException("area is required")

        val inspectedAt = parseInstant(body.getString("inspected_at"))
        val items = normalizeItems(body.getValue("items"), requireNonEmpty = false)
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(area)
        tuple.addOffsetDateTime(inspectedAt)
        tuple.addValue(items)
        tuple.addString(STATUS_PENDING)
        tuple.addValue(operator)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
    }

    /**
     * 追加检查/整改项：`待整改 → 已整改`；`已整改` 时仅追加；`已关闭` → [ConflictException]（409）。
     */
    fun appendItem(id: String, body: JsonObject, operator: String?): Future<JsonObject> {
        val inspectionId = id.trim()
        if (inspectionId.isEmpty()) throw IllegalArgumentException("id is required")
        val item = normalizeItem(body)

        return get(inspectionId).compose { existing ->
            val current = existing.getString("status") ?: STATUS_PENDING
            if (current == STATUS_CLOSED)
                throw ConflictException("inspection already closed: $inspectionId")

            val items = existing.getJsonArray("items") ?: JsonArray()
            items.add(item)
            val next = if (current == STATUS_PENDING) STATUS_RECTIFIED else current

            val now = OffsetDateTime.now()
            pool.preparedQuery(UPDATE_ITEMS_SQL)
                .execute(Tuple.of(items, next, now, inspectionId))
                .compose { get(inspectionId) }
        }
    }

    /** 关闭隐患：仅 `已整改 → 已关闭`；跳步（待整改）或终态再关闭 → [ConflictException]（409）。 */
    fun close(id: String, operator: String?): Future<JsonObject> {
        val inspectionId = id.trim()
        if (inspectionId.isEmpty()) throw IllegalArgumentException("id is required")

        return get(inspectionId).compose { existing ->
            val current = existing.getString("status") ?: STATUS_PENDING
            if (STATUS_CLOSED !in (TRANSITIONS[current] ?: emptySet()))
                throw ConflictException("invalid status transition: $current -> $STATUS_CLOSED")

            val now = OffsetDateTime.now()
            pool.preparedQuery(UPDATE_STATUS_SQL)
                .execute(Tuple.of(STATUS_CLOSED, now, inspectionId))
                .compose { get(inspectionId) }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("childcare safety inspection not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`area` / `date` / `status`（+ 附加 from/to）。 */
    fun list(
        area: String? = null,
        date: String? = null,
        status: String? = null,
        from: String? = null,
        to: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        area?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("i.area = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(date, "date")?.let {
            conditions.add("$DAY_EXPR = \$${values.size + 1}")
            values.add(it)
        }
        parseStatus(status)?.let {
            conditions.add("i.status = \$${values.size + 1}")
            values.add(it)
        }
        parseDate(from, "from")?.let {
            conditions.add("$DAY_EXPR >= \$${values.size + 1}")
            values.add(it)
        }
        parseDate(to, "to")?.let {
            conditions.add("$DAY_EXPR <= \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY i.inspected_at DESC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun normalizeItems(value: Any?, requireNonEmpty: Boolean): JsonArray {
        if (value == null) {
            if (requireNonEmpty) throw IllegalArgumentException("items is required")
            return JsonArray()
        }
        if (value !is JsonArray) throw IllegalArgumentException("items must be an array")
        val normalized = JsonArray()
        value.forEach { normalized.add(normalizeItem(it)) }
        if (requireNonEmpty && normalized.size() == 0)
            throw IllegalArgumentException("items must not be empty")
        return normalized
    }

    private fun normalizeItem(value: Any?): JsonObject {
        if (value !is JsonObject) throw IllegalArgumentException("each item must be an object")
        val code = value.getString("item_code")?.trim()
        if (code.isNullOrEmpty()) throw IllegalArgumentException("item_code is required")
        if (code !in CHECK_ITEM_CODES)
            throw IllegalArgumentException("unknown item_code: $code, must be one of: $CHECK_ITEM_CODES")

        val result = value.getString("result")?.trim()
        if (result.isNullOrEmpty()) throw IllegalArgumentException("result is required")
        if (result !in RESULTS)
            throw IllegalArgumentException("invalid result, must be one of: ${RESULTS.toList()}")

        val hazard = value.getString("hazard")?.trim()?.takeIf { it.isNotEmpty() }
        return JsonObject()
            .put("item_code", code)
            .put("result", result)
            .put("hazard", hazard)
    }

    private fun parseStatus(value: String?): String? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        if (text !in STATUSES)
            throw IllegalArgumentException("invalid status, must be one of: ${STATUSES.toList()}")
        return text
    }

    private fun parseDate(value: String?, field: String): LocalDate? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
        }
    }

    private fun parseInstant(value: String?): OffsetDateTime {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return OffsetDateTime.now()
        runCatching { return OffsetDateTime.parse(text) }
        runCatching { return LocalDate.parse(text).atStartOfDay(businessZone).toOffsetDateTime() }
        throw IllegalArgumentException("inspected_at must be a valid ISO-8601 date or date-time")
    }

    private fun parseMetadata(value: Any?): JsonObject? = when (value) {
        null -> null
        is JsonObject -> value
        else -> throw IllegalArgumentException("metadata must be an object")
    }

    private fun tupleOf(values: List<Any?>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
