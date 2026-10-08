package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * 054 D2 儿科用药规则库（迁移 `V304`）。
 *
 * ## 职责
 * - 规则建/查/改（[create] / [list] / [get] / [update]），状态白名单 `启用 / 停用`（054 §5.8）。
 * - 按药品匹配 + 剂量折算（[calculate]）——**委托纯引擎** [PediatricDoseCalculator]（054 D1），
 *   本服务只负责取规则与装配响应，不在服务层复制折算/封顶逻辑。
 * - `material_id` 存在性校验：对药品目录 `public.materials` 为**跨 schema 弱关联**（不加 FK），
 *   写入时校验存在并落 `material_name` 快照（054 §3 De）。
 *
 * ## 边界与安全（054 §12 R6）
 * 规则与计算结果只是**辅助计算 + 上限提示**：**不写医嘱、不替代药师判断**。
 * `created_by` 取认证主体（`ctx["userId"]`），客户端提交的操作人字段一律丢弃（042 口径）。
 *
 * ## 持久化
 * 全部走**原生参数化 SQL**（`$1..$n` + [Tuple]），跨 schema 显式限定；本文件不引入 jOOQ。
 */
class PediatricDoseRuleService(private val pool: Pool) {

    companion object {
        /** 规则状态白名单（054 §5.8 冻结）。 */
        val STATUSES: Set<String> = setOf(PediatricDoseRule.STATUS_ENABLED, PediatricDoseRule.STATUS_DISABLED)

        /** 客户端可提交的规则字段；`id/material_name/created_by/created_at/updated_at` 由服务端管控。 */
        val WRITABLE_FIELDS: Set<String> = setOf(
            "material_id",
            "age_min_months",
            "age_max_months",
            "weight_min_kg",
            "weight_max_kg",
            "dose_per_kg",
            "fixed_dose",
            "single_dose_max",
            "daily_dose_max",
            "doses_per_day",
            "unit",
            "status",
            "note",
        )

        private const val SELECT_COLUMNS =
            "id, material_id, material_name, age_min_months, age_max_months, weight_min_kg, weight_max_kg, " +
                "dose_per_kg, fixed_dose, single_dose_max, daily_dose_max, doses_per_day, unit, status, note, " +
                "created_by, created_at, updated_at"

        private const val GET_SQL =
            "SELECT $SELECT_COLUMNS FROM pharmacy.pharmacy_pediatric_dose_rules WHERE id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM pharmacy.pharmacy_pediatric_dose_rules"

        /** 药品目录存在性 + 名称快照（跨 schema 只读弱关联，无 FK）。 */
        private const val MATERIAL_SQL =
            "SELECT id, name FROM public.materials WHERE id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO pharmacy.pharmacy_pediatric_dose_rules " +
                "(id, material_id, material_name, age_min_months, age_max_months, weight_min_kg, weight_max_kg, " +
                "dose_per_kg, fixed_dose, single_dose_max, daily_dose_max, doses_per_day, unit, status, note, " +
                "created_by, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14, \$15, \$16, \$17, \$18)"

        private const val UPDATE_SQL =
            "UPDATE pharmacy.pharmacy_pediatric_dose_rules SET " +
                "material_id = \$2, material_name = \$3, age_min_months = \$4, age_max_months = \$5, " +
                "weight_min_kg = \$6, weight_max_kg = \$7, dose_per_kg = \$8, fixed_dose = \$9, " +
                "single_dose_max = \$10, daily_dose_max = \$11, doses_per_day = \$12, unit = \$13, " +
                "status = \$14, note = \$15, updated_at = \$16 " +
                "WHERE id = \$1"

        /** 计算入口取规则：仅启用规则；顺序决定 D1 同具体度并列时的取舍（确定性）。 */
        private const val ENABLED_RULES_SQL =
            "SELECT $SELECT_COLUMNS FROM pharmacy.pharmacy_pediatric_dose_rules " +
                "WHERE material_id = \$1 AND status = '启用' " +
                "ORDER BY updated_at DESC NULLS LAST, created_at DESC, id ASC"

        /** 行 → 规则响应 JSON（054 §5.7 形状 + `fixed_dose`/`doses_per_day`/`note` 增补）。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("material_id", row.getString("material_id"))
            .put("material_name", row.getString("material_name"))
            .put("age_min_months", row.getInteger("age_min_months"))
            .put("age_max_months", row.getInteger("age_max_months"))
            .put("weight_min_kg", row.getBigDecimal("weight_min_kg"))
            .put("weight_max_kg", row.getBigDecimal("weight_max_kg"))
            .put("dose_per_kg", row.getBigDecimal("dose_per_kg"))
            .put("fixed_dose", row.getBigDecimal("fixed_dose"))
            .put("single_dose_max", row.getBigDecimal("single_dose_max"))
            .put("daily_dose_max", row.getBigDecimal("daily_dose_max"))
            .put("doses_per_day", row.getInteger("doses_per_day"))
            .put("unit", row.getString("unit"))
            .put("status", row.getString("status"))
            .put("note", row.getString("note"))
            .put("created_by", row.getString("created_by"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /** 行 → D1 引擎规则（`material_name` 随规则携带，供响应标注来源）。 */
        internal fun toRule(row: Row): PediatricDoseRule = PediatricDoseRule(
            id = row.getString("id"),
            materialId = row.getString("material_id"),
            materialName = row.getString("material_name"),
            ageMinMonths = row.getInteger("age_min_months"),
            ageMaxMonths = row.getInteger("age_max_months"),
            weightMinKg = row.getBigDecimal("weight_min_kg"),
            weightMaxKg = row.getBigDecimal("weight_max_kg"),
            dosePerKg = row.getBigDecimal("dose_per_kg"),
            fixedDose = row.getBigDecimal("fixed_dose"),
            singleDoseMax = row.getBigDecimal("single_dose_max"),
            dailyDoseMax = row.getBigDecimal("daily_dose_max"),
            dosesPerDay = row.getInteger("doses_per_day"),
            unit = row.getString("unit"),
            status = row.getString("status"),
            note = row.getString("note"),
        )
    }

    /**
     * 创建规则。`status` 缺省 `启用`；`dose_per_kg` 与 `fixed_dose` 至少一项。
     *
     * 参数非法抛 `IllegalArgumentException`（路由 400）；`material_id` 不存在于
     * `public.materials` 抛 [NotFoundException]（路由 404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        rejectUnknown(body)
        val materialId = body.getString("material_id")?.trim()
        if (materialId.isNullOrEmpty()) throw IllegalArgumentException("material_id is required")
        val unit = body.getString("unit")?.trim()
        if (unit.isNullOrEmpty()) throw IllegalArgumentException("unit is required")
        val status = if (body.containsKey("status")) {
            val value = body.getString("status")?.trim()
            if (value == null || value !in STATUSES) {
                throw IllegalArgumentException("invalid status, must be one of: $STATUSES")
            }
            value
        } else {
            PediatricDoseRule.STATUS_ENABLED
        }

        val state = RuleState(
            materialId = materialId,
            materialName = "",
            ageMinMonths = mergeInt(body, "age_min_months", null, min = 0),
            ageMaxMonths = mergeInt(body, "age_max_months", null, min = 0),
            weightMinKg = mergeDecimal(body, "weight_min_kg", null),
            weightMaxKg = mergeDecimal(body, "weight_max_kg", null),
            dosePerKg = mergeDecimal(body, "dose_per_kg", null),
            fixedDose = mergeDecimal(body, "fixed_dose", null),
            singleDoseMax = mergeDecimal(body, "single_dose_max", null),
            dailyDoseMax = mergeDecimal(body, "daily_dose_max", null),
            dosesPerDay = mergeInt(body, "doses_per_day", null, min = 1),
            unit = unit,
            status = status,
            note = mergeText(body, "note", null),
        )
        validate(state)

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return findMaterial(materialId).compose { materialName ->
            if (materialName == null) {
                return@compose Future.failedFuture(NotFoundException("material not found: $materialId"))
            }
            val tuple = Tuple.tuple()
            tuple.addString(id)
            tuple.addString(state.materialId)
            tuple.addString(materialName)
            tuple.addValue(state.ageMinMonths)
            tuple.addValue(state.ageMaxMonths)
            tuple.addValue(state.weightMinKg)
            tuple.addValue(state.weightMaxKg)
            tuple.addValue(state.dosePerKg)
            tuple.addValue(state.fixedDose)
            tuple.addValue(state.singleDoseMax)
            tuple.addValue(state.dailyDoseMax)
            tuple.addValue(state.dosesPerDay)
            tuple.addString(state.unit)
            tuple.addString(state.status)
            tuple.addValue(state.note)
            tuple.addValue(operator)
            tuple.addOffsetDateTime(now)
            tuple.addOffsetDateTime(now)
            pool.preparedQuery(INSERT_SQL).execute(tuple).compose { get(id) }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("pediatric dose rule not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`material_id` / `status`。 */
    fun list(
        materialId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()

        materialId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("material_id = \$${values.size + 1}")
            values.add(it)
        }
        status?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in STATUSES) {
                return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: $STATUSES"))
            }
            conditions.add("status = \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT $SELECT_COLUMNS FROM pharmacy.pharmacy_pediatric_dose_rules" + where +
            " ORDER BY created_at DESC, id ASC LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

        return pool.preparedQuery(countSql).execute(tupleOf(values)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listSql).execute(tupleOf(values + listOf(limit, offset))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::toJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 修改规则（PUT 语义：只覆盖请求体中出现的字段，未出现的保持原值；显式 `null` 清空可选字段）。
     *
     * `material_id` 变更时重新校验存在性并刷新 `material_name` 快照；规则不存在 → [NotFoundException]（404）；
     * 参数非法 → `IllegalArgumentException`（400）。
     */
    fun update(id: String, body: JsonObject, operator: String?): Future<JsonObject> {
        rejectUnknown(body)
        return pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("pediatric dose rule not found: $id"))

            val materialId = if (body.containsKey("material_id")) {
                body.getString("material_id")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("material_id must not be empty")
            } else {
                row.getString("material_id")
            }
            val unit = if (body.containsKey("unit")) {
                body.getString("unit")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("unit must not be empty")
            } else {
                row.getString("unit")
            }
            val status = if (body.containsKey("status")) {
                val value = body.getString("status")?.trim()
                if (value == null || value !in STATUSES) {
                    throw IllegalArgumentException("invalid status, must be one of: $STATUSES")
                }
                value
            } else {
                row.getString("status")
            }

            val state = RuleState(
                materialId = materialId,
                materialName = row.getString("material_name"),
                ageMinMonths = mergeInt(body, "age_min_months", row.getInteger("age_min_months"), min = 0),
                ageMaxMonths = mergeInt(body, "age_max_months", row.getInteger("age_max_months"), min = 0),
                weightMinKg = mergeDecimal(body, "weight_min_kg", row.getBigDecimal("weight_min_kg")),
                weightMaxKg = mergeDecimal(body, "weight_max_kg", row.getBigDecimal("weight_max_kg")),
                dosePerKg = mergeDecimal(body, "dose_per_kg", row.getBigDecimal("dose_per_kg")),
                fixedDose = mergeDecimal(body, "fixed_dose", row.getBigDecimal("fixed_dose")),
                singleDoseMax = mergeDecimal(body, "single_dose_max", row.getBigDecimal("single_dose_max")),
                dailyDoseMax = mergeDecimal(body, "daily_dose_max", row.getBigDecimal("daily_dose_max")),
                dosesPerDay = mergeInt(body, "doses_per_day", row.getInteger("doses_per_day"), min = 1),
                unit = unit,
                status = status,
                note = mergeText(body, "note", row.getString("note")),
            )
            validate(state)

            val materialChanged = state.materialId != row.getString("material_id")
            val materialLookup: Future<String?> = if (materialChanged) {
                findMaterial(state.materialId).map { name ->
                    name ?: throw NotFoundException("material not found: ${state.materialId}")
                }
            } else {
                Future.succeededFuture(state.materialName)
            }

            materialLookup.compose { materialName ->
                val now = OffsetDateTime.now()
                val tuple = Tuple.tuple()
                tuple.addString(id)
                tuple.addString(state.materialId)
                tuple.addString(materialName)
                tuple.addValue(state.ageMinMonths)
                tuple.addValue(state.ageMaxMonths)
                tuple.addValue(state.weightMinKg)
                tuple.addValue(state.weightMaxKg)
                tuple.addValue(state.dosePerKg)
                tuple.addValue(state.fixedDose)
                tuple.addValue(state.singleDoseMax)
                tuple.addValue(state.dailyDoseMax)
                tuple.addValue(state.dosesPerDay)
                tuple.addString(state.unit)
                tuple.addString(state.status)
                tuple.addValue(state.note)
                tuple.addOffsetDateTime(now)
                pool.preparedQuery(UPDATE_SQL).execute(tuple).compose { get(id) }
            }
        }
    }

    /**
     * 用量计算：取该药品全部启用规则 → [PediatricDoseCalculator] 选规则 + 折算 + 封顶。
     *
     * 无任何可用规则 → [NotFoundException]（路由 404，**不返回 0 剂量**）；
     * 体重/年龄非法（由路由层用 [PediatricDoseCalculator.parseWeightKg] / `parseAgeMonths` 先行拒绝）或
     * 规则数据非法 → `IllegalArgumentException`（400）。
     */
    fun calculate(materialId: String, weightKg: BigDecimal?, ageMonths: Int?): Future<JsonObject> {
        val id = materialId.trim()
        if (id.isEmpty()) throw IllegalArgumentException("material_id is required")
        return pool.preparedQuery(ENABLED_RULES_SQL).execute(Tuple.of(id)).map { rows ->
            val rules = rows.map(Companion::toRule)
            val result = PediatricDoseCalculator.calculate(id, weightKg, ageMonths, rules)
                ?: throw NotFoundException("no enabled pediatric dose rule for material: $id")
            val materialName = rules.firstOrNull { it.id == result.ruleId }?.materialName
            JsonObject()
                .put("material_id", result.materialId)
                .put("material_name", materialName)
                .put("weight_kg", result.weightKg)
                .put("age_months", result.ageMonths)
                .put("single_dose", result.singleDose)
                .put("daily_dose", result.dailyDose)
                .put("unit", result.unit)
                .put("capped", result.capped)
                .put("rule_id", result.ruleId)
                .put("source", result.source.name)
        }
    }

    // —— 内部：校验与解析 ——

    private data class RuleState(
        val materialId: String,
        val materialName: String,
        val ageMinMonths: Int?,
        val ageMaxMonths: Int?,
        val weightMinKg: BigDecimal?,
        val weightMaxKg: BigDecimal?,
        val dosePerKg: BigDecimal?,
        val fixedDose: BigDecimal?,
        val singleDoseMax: BigDecimal?,
        val dailyDoseMax: BigDecimal?,
        val dosesPerDay: Int?,
        val unit: String,
        val status: String,
        val note: String?,
    )

    /** 只接受白名单字段：未声明字段（含客户端试图提交的 `id/material_name/created_by`）一律 400。 */
    private fun rejectUnknown(body: JsonObject) {
        val unknown = body.fieldNames().filter { it !in WRITABLE_FIELDS }
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("unknown fields: ${unknown.sorted()}")
        }
    }

    private fun validate(state: RuleState) {
        if (state.materialId.isBlank()) throw IllegalArgumentException("material_id is required")
        if (state.unit.isBlank()) throw IllegalArgumentException("unit is required")
        if (state.status !in STATUSES) throw IllegalArgumentException("invalid status, must be one of: $STATUSES")
        if (state.dosePerKg == null && state.fixedDose == null) {
            throw IllegalArgumentException("dose_per_kg or fixed_dose is required")
        }
        if (state.ageMinMonths != null && state.ageMaxMonths != null && state.ageMinMonths > state.ageMaxMonths) {
            throw IllegalArgumentException("age_min_months must not be greater than age_max_months")
        }
        if (state.weightMinKg != null && state.weightMaxKg != null && state.weightMinKg > state.weightMaxKg) {
            throw IllegalArgumentException("weight_min_kg must not be greater than weight_max_kg")
        }
    }

    /** 药品目录名称快照；不存在返回 `null`（调用方决定 404）。 */
    private fun findMaterial(materialId: String): Future<String?> =
        pool.preparedQuery(MATERIAL_SQL).execute(Tuple.of(materialId)).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.getString("name")
        }

    /** 可选整数字段：键缺失 → 原值；显式 `null` → 清空；否则解析并做下界校验。 */
    private fun mergeInt(body: JsonObject, key: String, current: Int?, min: Int? = null): Int? {
        if (!body.containsKey(key)) return current
        val raw = body.getValue(key) ?: return null
        val parsed = wholeNumber(raw, key) ?: return null
        if (min != null && parsed < min) throw IllegalArgumentException("$key must be >= $min")
        return parsed
    }

    /** 可选十进制字段：键缺失 → 原值；显式 `null` → 清空；否则解析并要求正数。 */
    private fun mergeDecimal(body: JsonObject, key: String, current: BigDecimal?): BigDecimal? {
        if (!body.containsKey(key)) return current
        val raw = body.getValue(key) ?: return null
        val parsed = decimalText(raw) ?: throw IllegalArgumentException("$key must be a number")
        if (parsed.signum() <= 0) throw IllegalArgumentException("$key must be positive")
        return parsed
    }

    /** 可选文本字段：键缺失 → 原值；显式 `null` 或空串 → 清空。 */
    private fun mergeText(body: JsonObject, key: String, current: String?): String? {
        if (!body.containsKey(key)) return current
        return body.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun wholeNumber(raw: Any?, label: String): Int? {
        val value: Long = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Number -> {
                val d = raw.toDouble()
                if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) {
                    throw IllegalArgumentException("$label must be a whole number")
                }
                d.toLong()
            }
            is String -> raw.trim().toLongOrNull()
                ?: throw IllegalArgumentException("$label must be a whole number")
            else -> throw IllegalArgumentException("$label must be a number")
        }
        if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) {
            throw IllegalArgumentException("$label is out of range")
        }
        return value.toInt()
    }

    /** 动态 `WHERE` 片段的绑定值（`$n` 与值一一对应，绝不拼接用户输入）。 */
    private fun tupleOf(values: List<Any>): Tuple {
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        return tuple
    }
}
