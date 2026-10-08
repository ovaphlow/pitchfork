package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.isUniqueViolation
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

/**
 * 054 F2 基础数据内容服务（迁移 `V539`）：疫苗类型与发育量表的**建 / 查 / 改**。
 *
 * ## 读库优先、常量回落（054 §3 Dh / §8.6）
 * 两个消费方原先硬编码常量：
 *  - [ChildVaccinationService.VACCINATION_SCHEDULE]（疫苗排程）
 *  - [DevelopmentalScaleCatalog.scales]（发育量表）
 *
 * 本服务把内容表加载结果写入二者的**进程内覆盖层**（[refreshVaccinationContent] /
 * [refreshDevelopmentalScaleContent]）。覆盖层为空 = **库为空或读失败 → 完全等价于常量基线**，
 * 因此 052 的接种状态与 053 的筛查判读结果**在库为空时与改造前逐字节一致**。
 *
 * ## 规则
 * - `status` = `启用 / 停用`（应用层白名单）；只有 `启用` 记录参与内容加载。
 * - 疫苗类型：`code` 唯一；`dose_count ≥ 1`；`recommended_months` 长度**必须等于** `dose_count`。
 * - 发育量表：`(scale_code, scale_version)` 唯一；`definition` 形状见 V539 注释，畸形行在加载时**跳过**。
 * - `created_by` / `updated_by` 一律取认证主体（042 口径），客户端不得提交。
 * - 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class BaseDataService(private val pool: Pool) {

    companion object {
        private val log = LoggerFactory.getLogger(BaseDataService::class.java)

        /** 内容状态白名单（054 §5.8）。 */
        val STATUSES: Set<String> = linkedSetOf("启用", "停用")

        const val STATUS_ENABLED = "启用"

        private val VACCINATION_KEYS =
            setOf("code", "name", "dose_count", "recommended_months", "status", "sort_order", "metadata")

        private val SCALE_KEYS =
            setOf("scale_code", "scale_version", "name", "age_min_months", "age_max_months", "definition", "status", "metadata")

        private const val VACCINATION_COLUMNS =
            "id, code, name, dose_count, recommended_months, status, sort_order, metadata, " +
                "created_by, updated_by, created_at, updated_at"

        private const val SCALE_COLUMNS =
            "id, scale_code, scale_version, name, age_min_months, age_max_months, definition, status, " +
                "metadata, created_by, updated_by, created_at, updated_at"

        private const val VACCINATION_BY_ID_SQL =
            "SELECT $VACCINATION_COLUMNS FROM healthcare.base_vaccination_types WHERE id = \$1"

        private const val VACCINATION_CODE_TAKEN_SQL =
            "SELECT id FROM healthcare.base_vaccination_types WHERE code = \$1 AND id <> \$2"

        private const val INSERT_VACCINATION_SQL =
            "INSERT INTO healthcare.base_vaccination_types " +
                "(id, code, name, dose_count, recommended_months, status, sort_order, metadata, " +
                "created_by, updated_by, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12)"

        private const val UPDATE_VACCINATION_SQL =
            "UPDATE healthcare.base_vaccination_types SET code = \$2, name = \$3, dose_count = \$4, " +
                "recommended_months = \$5, status = \$6, sort_order = \$7, metadata = \$8, " +
                "updated_by = \$9, updated_at = \$10 WHERE id = \$1"

        private const val VACCINATION_COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.base_vaccination_types"

        private const val SCALE_BY_ID_SQL =
            "SELECT $SCALE_COLUMNS FROM healthcare.base_developmental_scales WHERE id = \$1"

        private const val SCALE_CODE_TAKEN_SQL =
            "SELECT id FROM healthcare.base_developmental_scales " +
                "WHERE scale_code = \$1 AND scale_version = \$2 AND id <> \$3"

        private const val INSERT_SCALE_SQL =
            "INSERT INTO healthcare.base_developmental_scales " +
                "(id, scale_code, scale_version, name, age_min_months, age_max_months, definition, status, " +
                "metadata, created_by, updated_by, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13)"

        private const val UPDATE_SCALE_SQL =
            "UPDATE healthcare.base_developmental_scales SET scale_code = \$2, scale_version = \$3, name = \$4, " +
                "age_min_months = \$5, age_max_months = \$6, definition = \$7, status = \$8, metadata = \$9, " +
                "updated_by = \$10, updated_at = \$11 WHERE id = \$1"

        private const val SCALE_COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.base_developmental_scales"

        /** 内容加载只取启用记录（读库优先的输入）。 */
        private const val ENABLED_VACCINATION_SQL =
            "SELECT $VACCINATION_COLUMNS FROM healthcare.base_vaccination_types " +
                "WHERE status = '启用' ORDER BY sort_order ASC, code ASC, id ASC"

        private const val ENABLED_SCALE_SQL =
            "SELECT $SCALE_COLUMNS FROM healthcare.base_developmental_scales " +
                "WHERE status = '启用' ORDER BY scale_code ASC, scale_version ASC, id ASC"

        internal fun vaccinationJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("code", row.getString("code"))
            .put("name", row.getString("name"))
            .put("dose_count", row.getInteger("dose_count"))
            .put("recommended_months", row.getJsonArray("recommended_months") ?: JsonArray())
            .put("status", row.getString("status"))
            .put("sort_order", row.getInteger("sort_order"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_by", row.getString("created_by"))
            .put("updated_by", row.getString("updated_by"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        internal fun scaleJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("scale_code", row.getString("scale_code"))
            .put("scale_version", row.getString("scale_version"))
            .put("name", row.getString("name"))
            .put("age_min_months", row.getInteger("age_min_months"))
            .put("age_max_months", row.getInteger("age_max_months"))
            .put("definition", row.getJsonObject("definition") ?: JsonObject())
            .put("status", row.getString("status"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_by", row.getString("created_by"))
            .put("updated_by", row.getString("updated_by"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        /**
         * 内容行 → 排程条目（`code × 剂次 1..dose_count`）。
         *
         * `recommended_months` 缺项/长度不足的剂次**跳过**（写入时已强制等长，此处为读侧兜底）。
         */
        internal fun toScheduleEntries(row: Row): List<ChildVaccinationService.Companion.ScheduleEntry> {
            val code = row.getString("code") ?: return emptyList()
            val name = row.getString("name") ?: return emptyList()
            val doseCount = row.getInteger("dose_count") ?: 0
            val months = row.getJsonArray("recommended_months") ?: JsonArray()
            return (1..doseCount).mapNotNull { doseNo ->
                val month = months.getInteger(doseNo - 1) ?: return@mapNotNull null
                ChildVaccinationService.Companion.ScheduleEntry(code, name, doseNo, month)
            }
        }

        /** 内容行 → 量表；`definition` 畸形（缺 domains/items 或条目字段不全）返回 `null`（跳过）。 */
        internal fun toScale(row: Row): DevelopmentalScaleCatalog.Scale? {
            val definition = row.getJsonObject("definition") ?: return null
            val domains = definition.getJsonArray("domains")?.mapNotNull { element ->
                val domain = element as? JsonObject ?: return@mapNotNull null
                val code = domain.getString("code")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = domain.getString("name")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                DevelopmentalScaleCatalog.Domain(code, name)
            } ?: return null
            val items = definition.getJsonArray("items")?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val code = item.getString("code")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val domain = item.getString("domain")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val prompt = item.getString("prompt")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val minMonths = item.getInteger("min_months") ?: return@mapNotNull null
                val maxMonths = item.getInteger("max_months") ?: return@mapNotNull null
                val options = item.getJsonArray("options")?.mapNotNull { optionElement ->
                    val option = optionElement as? JsonObject ?: return@mapNotNull null
                    val value = option.getInteger("value") ?: return@mapNotNull null
                    val label = option.getString("label")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    DevelopmentalScaleCatalog.ItemOption(value, label)
                } ?: return@mapNotNull null
                if (options.isEmpty()) return@mapNotNull null
                DevelopmentalScaleCatalog.Item(code, domain, prompt, minMonths, maxMonths, options)
            } ?: return null
            if (domains.isEmpty() || items.isEmpty()) return null
            return DevelopmentalScaleCatalog.Scale(
                code = row.getString("scale_code") ?: return null,
                version = row.getString("scale_version") ?: return null,
                name = row.getString("name") ?: return null,
                minMonths = row.getInteger("age_min_months") ?: return null,
                maxMonths = row.getInteger("age_max_months") ?: return null,
                domains = domains,
                items = items,
            )
        }
    }

    // ========================================================================
    //  内容加载（读库优先、常量回落）
    // ========================================================================

    /**
     * 重新加载疫苗排程内容：库为空 / 读失败（含**同步抛错**）→ 覆盖层置空（**回落常量排程**）。
     *
     * 本方法**永不失败、永不抛出**，供路由装配时 fire-and-forget 与每次内容写入后调用；
     * 装配期任何异常都不得影响路由构建。
     */
    fun refreshVaccinationContent(): Future<Unit> {
        val query = try {
            pool.preparedQuery(ENABLED_VACCINATION_SQL).execute()
        } catch (error: Throwable) {
            log.warn("base vaccination content load failed, falling back to constants", error)
            ChildVaccinationService.applyContentSchedule(emptyList())
            return Future.succeededFuture(Unit)
        }
        return query.map { rows ->
            ChildVaccinationService.applyContentSchedule(rows.flatMap(Companion::toScheduleEntries))
            Unit
        }.recover { error ->
            log.warn("base vaccination content load failed, falling back to constants", error)
            ChildVaccinationService.applyContentSchedule(emptyList())
            Future.succeededFuture(Unit)
        }
    }

    /**
     * 重新加载发育量表内容：库为空 / 读失败（含**同步抛错**）/ 行畸形 → 覆盖层置空或跳过该行
     * （**回落常量**）。本方法**永不失败、永不抛出**。
     */
    fun refreshDevelopmentalScaleContent(): Future<Unit> {
        val query = try {
            pool.preparedQuery(ENABLED_SCALE_SQL).execute()
        } catch (error: Throwable) {
            log.warn("base developmental scale content load failed, falling back to constants", error)
            DevelopmentalScaleCatalog.applyContent(emptyList())
            return Future.succeededFuture(Unit)
        }
        return query.map { rows ->
            DevelopmentalScaleCatalog.applyContent(rows.mapNotNull(Companion::toScale))
            Unit
        }.recover { error ->
            log.warn("base developmental scale content load failed, falling back to constants", error)
            DevelopmentalScaleCatalog.applyContent(emptyList())
            Future.succeededFuture(Unit)
        }
    }

    /** 一次刷新两类内容（路由装配时调用；任一失败不影响另一类，均内部回落常量）。 */
    fun refreshAll(): Future<Unit> =
        refreshVaccinationContent().compose { refreshDevelopmentalScaleContent() }

    // ========================================================================
    //  疫苗类型：建 / 查 / 改
    // ========================================================================

    fun createVaccinationType(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseVaccinationCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return ensureVaccinationCodeAvailable(pool, parsed.code, excludeId = null).compose {
            val tuple = Tuple.tuple()
            tuple.addString(id)
            tuple.addString(parsed.code)
            tuple.addString(parsed.name)
            tuple.addInteger(parsed.doseCount)
            tuple.addValue(parsed.recommendedMonths)
            tuple.addString(parsed.status)
            tuple.addInteger(parsed.sortOrder)
            tuple.addValue(parsed.metadata)
            tuple.addString(operator)
            tuple.addString(operator)
            tuple.addOffsetDateTime(now)
            tuple.addOffsetDateTime(now)
            pool.preparedQuery(INSERT_VACCINATION_SQL).execute(tuple)
                .compose { getVaccinationType(id) }
                .compose(refreshAfterWrite())
                .mapUniqueViolation("uq_base_vaccination_types_code", "vaccination type code already exists: ${parsed.code}")
        }
    }

    fun getVaccinationType(id: String): Future<JsonObject> =
        pool.preparedQuery(VACCINATION_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(vaccinationJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("base vaccination type not found: $id"))
        }

    fun listVaccinationTypes(status: String?, limit: Int, offset: Int): Future<JsonObject> =
        page(
            countSql = VACCINATION_COUNT_SQL,
            columns = VACCINATION_COLUMNS,
            table = "healthcare.base_vaccination_types",
            mapper = Companion::vaccinationJson,
            status = status,
            limit = limit,
            offset = offset,
        )

    fun updateVaccinationType(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val request = try {
            rejectUnsupported(body, VACCINATION_KEYS, "base vaccination type")
            body
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.preparedQuery(VACCINATION_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val current = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("base vaccination type not found: $id"),
                )
            val merged = try {
                mergeVaccination(current, request)
            } catch (error: IllegalArgumentException) {
                return@compose Future.failedFuture(error)
            }
            val codeChanged = merged.code != current.getString("code")
            val availability: Future<Unit> = if (codeChanged) {
                ensureVaccinationCodeAvailable(pool, merged.code, excludeId = id)
            } else {
                Future.succeededFuture(Unit)
            }
            availability.compose {
                val tuple = Tuple.tuple()
                tuple.addString(id)
                tuple.addString(merged.code)
                tuple.addString(merged.name)
                tuple.addInteger(merged.doseCount)
                tuple.addValue(merged.recommendedMonths)
                tuple.addString(merged.status)
                tuple.addInteger(merged.sortOrder)
                tuple.addValue(merged.metadata)
                tuple.addString(operator)
                tuple.addOffsetDateTime(OffsetDateTime.now())
                pool.preparedQuery(UPDATE_VACCINATION_SQL).execute(tuple)
                    .compose { getVaccinationType(id) }
                    .compose(refreshAfterWrite())
                    .mapUniqueViolation("uq_base_vaccination_types_code", "vaccination type code already exists: ${merged.code}")
            }
        }
    }

    // ========================================================================
    //  发育量表：建 / 查 / 改
    // ========================================================================

    fun createDevelopmentalScale(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseScaleCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return ensureScaleCodeAvailable(pool, parsed.scaleCode, parsed.scaleVersion, excludeId = null).compose {
            val tuple = Tuple.tuple()
            tuple.addString(id)
            tuple.addString(parsed.scaleCode)
            tuple.addString(parsed.scaleVersion)
            tuple.addString(parsed.name)
            tuple.addInteger(parsed.ageMinMonths)
            tuple.addInteger(parsed.ageMaxMonths)
            tuple.addValue(parsed.definition)
            tuple.addString(parsed.status)
            tuple.addValue(parsed.metadata)
            tuple.addString(operator)
            tuple.addString(operator)
            tuple.addOffsetDateTime(now)
            tuple.addOffsetDateTime(now)
            pool.preparedQuery(INSERT_SCALE_SQL).execute(tuple)
                .compose { getDevelopmentalScale(id) }
                .compose(refreshAfterWrite())
                .mapUniqueViolation(
                    "uq_base_developmental_scales_code_version",
                    "developmental scale already exists: ${parsed.scaleCode}@${parsed.scaleVersion}",
                )
        }
    }

    fun getDevelopmentalScale(id: String): Future<JsonObject> =
        pool.preparedQuery(SCALE_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(scaleJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("base developmental scale not found: $id"))
        }

    fun listDevelopmentalScales(status: String?, limit: Int, offset: Int): Future<JsonObject> =
        page(
            countSql = SCALE_COUNT_SQL,
            columns = SCALE_COLUMNS,
            table = "healthcare.base_developmental_scales",
            mapper = Companion::scaleJson,
            status = status,
            limit = limit,
            offset = offset,
        )

    fun updateDevelopmentalScale(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val request = try {
            rejectUnsupported(body, SCALE_KEYS, "base developmental scale")
            body
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.preparedQuery(SCALE_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            val current = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("base developmental scale not found: $id"),
                )
            val merged = try {
                mergeScale(current, request)
            } catch (error: IllegalArgumentException) {
                return@compose Future.failedFuture(error)
            }
            val identityChanged = merged.scaleCode != current.getString("scale_code") ||
                merged.scaleVersion != current.getString("scale_version")
            val availability: Future<Unit> = if (identityChanged) {
                ensureScaleCodeAvailable(pool, merged.scaleCode, merged.scaleVersion, excludeId = id)
            } else {
                Future.succeededFuture(Unit)
            }
            availability.compose {
                val tuple = Tuple.tuple()
                tuple.addString(id)
                tuple.addString(merged.scaleCode)
                tuple.addString(merged.scaleVersion)
                tuple.addString(merged.name)
                tuple.addInteger(merged.ageMinMonths)
                tuple.addInteger(merged.ageMaxMonths)
                tuple.addValue(merged.definition)
                tuple.addString(merged.status)
                tuple.addValue(merged.metadata)
                tuple.addString(operator)
                tuple.addOffsetDateTime(OffsetDateTime.now())
                pool.preparedQuery(UPDATE_SCALE_SQL).execute(tuple)
                    .compose { getDevelopmentalScale(id) }
                    .compose(refreshAfterWrite())
                    .mapUniqueViolation(
                        "uq_base_developmental_scales_code_version",
                        "developmental scale already exists: ${merged.scaleCode}@${merged.scaleVersion}",
                    )
            }
        }
    }

    // ========================================================================
    //  内部：分页、校验与解析
    // ========================================================================

    /** 内容写入后刷新覆盖层（保证「读库优先」立刻生效）；刷新自身不失败。 */
    private fun <T> refreshAfterWrite(): (T) -> Future<T> = { value ->
        refreshAll().map { value }
    }

    private fun page(
        countSql: String,
        columns: String,
        table: String,
        mapper: (Row) -> JsonObject,
        status: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val filter = status?.trim()?.takeIf(String::isNotBlank)
        if (filter != null && filter !in STATUSES) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $filter"))
        }
        val where = if (filter == null) "" else " WHERE status = \$1"
        val countQuery = countSql + where
        val listQuery = "SELECT $columns FROM $table" + where +
            " ORDER BY created_at DESC, id DESC LIMIT \$${if (filter == null) 1 else 2} OFFSET \$${if (filter == null) 2 else 3}"
        val countTuple = if (filter == null) Tuple.tuple() else Tuple.of(filter)
        val listTuple = if (filter == null) Tuple.of(limit, offset) else Tuple.of(filter, limit, offset)
        return pool.preparedQuery(countQuery).execute(countTuple).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(listQuery).execute(listTuple).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(mapper)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    private data class ParsedVaccination(
        val code: String,
        val name: String,
        val doseCount: Int,
        val recommendedMonths: JsonArray,
        val status: String,
        val sortOrder: Int,
        val metadata: JsonObject?,
    )

    private data class ParsedScale(
        val scaleCode: String,
        val scaleVersion: String,
        val name: String,
        val ageMinMonths: Int,
        val ageMaxMonths: Int,
        val definition: JsonObject,
        val status: String,
        val metadata: JsonObject?,
    )

    private fun parseVaccinationCreate(body: JsonObject): ParsedVaccination {
        rejectUnsupported(body, VACCINATION_KEYS, "base vaccination type")
        val doseCount = requiredInt(body, "dose_count", min = 1)
        val months = requiredMonths(body, "recommended_months")
        if (months.size() != doseCount) {
            throw IllegalArgumentException("recommended_months length must equal dose_count ($doseCount)")
        }
        return ParsedVaccination(
            code = requiredText(body, "code"),
            name = requiredText(body, "name"),
            doseCount = doseCount,
            recommendedMonths = months,
            status = optionalStatus(body, STATUSES, STATUS_ENABLED),
            sortOrder = optionalInt(body, "sort_order", 0, min = 0),
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    private fun mergeVaccination(current: Row, body: JsonObject): ParsedVaccination {
        val doseCount = if (body.containsKey("dose_count")) {
            requiredInt(body, "dose_count", min = 1)
        } else {
            current.getInteger("dose_count") ?: 1
        }
        val months = if (body.containsKey("recommended_months")) {
            requiredMonths(body, "recommended_months")
        } else {
            current.getJsonArray("recommended_months") ?: JsonArray()
        }
        if (months.size() != doseCount) {
            throw IllegalArgumentException("recommended_months length must equal dose_count ($doseCount)")
        }
        return ParsedVaccination(
            code = if (body.containsKey("code")) requiredText(body, "code") else current.getString("code"),
            name = if (body.containsKey("name")) requiredText(body, "name") else current.getString("name"),
            doseCount = doseCount,
            recommendedMonths = months,
            status = if (body.containsKey("status")) {
                optionalStatus(body, STATUSES, STATUS_ENABLED)
            } else {
                current.getString("status")
            },
            sortOrder = if (body.containsKey("sort_order")) {
                optionalInt(body, "sort_order", 0, min = 0)
            } else {
                current.getInteger("sort_order") ?: 0
            },
            metadata = if (body.containsKey("metadata")) optionalJsonObject(body, "metadata") else current.getJsonObject("metadata"),
        )
    }

    private fun parseScaleCreate(body: JsonObject): ParsedScale {
        rejectUnsupported(body, SCALE_KEYS, "base developmental scale")
        val ageMin = requiredInt(body, "age_min_months", min = 0)
        val ageMax = requiredInt(body, "age_max_months", min = 0)
        if (ageMax < ageMin) {
            throw IllegalArgumentException("age_max_months must not be less than age_min_months")
        }
        return ParsedScale(
            scaleCode = requiredText(body, "scale_code"),
            scaleVersion = requiredText(body, "scale_version"),
            name = requiredText(body, "name"),
            ageMinMonths = ageMin,
            ageMaxMonths = ageMax,
            definition = requiredDefinition(body, "definition"),
            status = optionalStatus(body, STATUSES, STATUS_ENABLED),
            metadata = optionalJsonObject(body, "metadata"),
        )
    }

    private fun mergeScale(current: Row, body: JsonObject): ParsedScale {
        val ageMin = if (body.containsKey("age_min_months")) {
            requiredInt(body, "age_min_months", min = 0)
        } else {
            current.getInteger("age_min_months") ?: 0
        }
        val ageMax = if (body.containsKey("age_max_months")) {
            requiredInt(body, "age_max_months", min = 0)
        } else {
            current.getInteger("age_max_months") ?: 0
        }
        if (ageMax < ageMin) {
            throw IllegalArgumentException("age_max_months must not be less than age_min_months")
        }
        return ParsedScale(
            scaleCode = if (body.containsKey("scale_code")) requiredText(body, "scale_code") else current.getString("scale_code"),
            scaleVersion = if (body.containsKey("scale_version")) requiredText(body, "scale_version") else current.getString("scale_version"),
            name = if (body.containsKey("name")) requiredText(body, "name") else current.getString("name"),
            ageMinMonths = ageMin,
            ageMaxMonths = ageMax,
            definition = if (body.containsKey("definition")) {
                requiredDefinition(body, "definition")
            } else {
                current.getJsonObject("definition") ?: JsonObject()
            },
            status = if (body.containsKey("status")) {
                optionalStatus(body, STATUSES, STATUS_ENABLED)
            } else {
                current.getString("status")
            },
            metadata = if (body.containsKey("metadata")) optionalJsonObject(body, "metadata") else current.getJsonObject("metadata"),
        )
    }

    /** 推荐月龄数组：非空、整数、非负、长度 = `dose_count`（在 create/merge 内校验等长）。 */
    private fun requiredMonths(body: JsonObject, key: String): JsonArray {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val array = raw as? JsonArray ?: throw IllegalArgumentException("$key must be a JSON array")
        if (array.isEmpty) throw IllegalArgumentException("$key must not be empty")
        array.forEachIndexed { index, element ->
            val month = (element as? Number)?.toInt()
                ?: throw IllegalArgumentException("$key[$index] must be an integer")
            if (month < 0) throw IllegalArgumentException("$key[$index] must not be negative")
        }
        return array
    }

    /** `definition` 形状校验（domains/items 非空、字段齐全、domain 必须已声明、options 非空）。 */
    private fun requiredDefinition(body: JsonObject, key: String): JsonObject {
        val raw = body.getValue(key) ?: throw IllegalArgumentException("$key is required")
        val definition = raw as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
        val domains = definition.getJsonArray("domains")
            ?: throw IllegalArgumentException("$key.domains is required")
        val items = definition.getJsonArray("items")
            ?: throw IllegalArgumentException("$key.items is required")
        if (domains.isEmpty) throw IllegalArgumentException("$key.domains must not be empty")
        if (items.isEmpty) throw IllegalArgumentException("$key.items must not be empty")
        val domainCodes = domains.mapIndexed { index, element ->
            val domain = element as? JsonObject
                ?: throw IllegalArgumentException("$key.domains[$index] must be a JSON object")
            domain.getString("code")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$key.domains[$index].code is required")
        }.toSet()
        items.forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("$key.items[$index] must be a JSON object")
            item.getString("code")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$key.items[$index].code is required")
            val domain = item.getString("domain")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$key.items[$index].domain is required")
            if (domain !in domainCodes) {
                throw IllegalArgumentException("$key.items[$index].domain must be one of $domainCodes")
            }
            item.getString("prompt")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$key.items[$index].prompt is required")
            val minMonths = (item.getValue("min_months") as? Number)?.toInt()
                ?: throw IllegalArgumentException("$key.items[$index].min_months is required")
            val maxMonths = (item.getValue("max_months") as? Number)?.toInt()
                ?: throw IllegalArgumentException("$key.items[$index].max_months is required")
            if (minMonths < 0 || maxMonths < minMonths) {
                throw IllegalArgumentException("$key.items[$index] month range is invalid")
            }
            val options = item.getJsonArray("options")
                ?: throw IllegalArgumentException("$key.items[$index].options is required")
            if (options.isEmpty) throw IllegalArgumentException("$key.items[$index].options must not be empty")
            options.forEachIndexed { optionIndex, optionElement ->
                val option = optionElement as? JsonObject
                    ?: throw IllegalArgumentException("$key.items[$index].options[$optionIndex] must be a JSON object")
                option.getValue("value") as? Number
                    ?: throw IllegalArgumentException("$key.items[$index].options[$optionIndex].value is required")
                option.getString("label")?.trim()?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("$key.items[$index].options[$optionIndex].label is required")
            }
        }
        return definition
    }

    private fun ensureVaccinationCodeAvailable(client: SqlClient, code: String, excludeId: String?): Future<Unit> =
        client.preparedQuery(VACCINATION_CODE_TAKEN_SQL).execute(Tuple.of(code, excludeId ?: "")).compose { rows ->
            if (rows.iterator().hasNext()) {
                Future.failedFuture(ConflictException("vaccination type code already exists: $code"))
            } else {
                Future.succeededFuture(Unit)
            }
        }

    private fun ensureScaleCodeAvailable(
        client: SqlClient,
        scaleCode: String,
        scaleVersion: String,
        excludeId: String?,
    ): Future<Unit> =
        client.preparedQuery(SCALE_CODE_TAKEN_SQL)
            .execute(Tuple.of(scaleCode, scaleVersion, excludeId ?: ""))
            .compose { rows ->
                if (rows.iterator().hasNext()) {
                    Future.failedFuture(
                        ConflictException("developmental scale already exists: $scaleCode@$scaleVersion"),
                    )
                } else {
                    Future.succeededFuture(Unit)
                }
            }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun requiredInt(body: JsonObject, key: String, min: Int? = null): Int {
        val value = (body.getValue(key) as? Number)?.toInt()
            ?: throw IllegalArgumentException("$key is required and must be an integer")
        if (min != null && value < min) throw IllegalArgumentException("$key must be >= $min")
        return value
    }

    private fun optionalInt(body: JsonObject, key: String, fallback: Int, min: Int? = null): Int {
        val raw = body.getValue(key) ?: return fallback
        val value = (raw as? Number)?.toInt()
            ?: throw IllegalArgumentException("$key must be an integer")
        if (min != null && value < min) throw IllegalArgumentException("$key must be >= $min")
        return value
    }

    private fun optionalStatus(body: JsonObject, allowed: Set<String>, fallback: String): String {
        val raw = body.getString("status")?.trim()?.takeIf(String::isNotBlank) ?: return fallback
        if (raw !in allowed) throw IllegalArgumentException("invalid status: $raw")
        return raw
    }

    private fun optionalJsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectUnsupported(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    /** 唯一索引兜底（并发撞键）：委托共享判定 [isUniqueViolation]（遍历 cause 链 + `sqlState == 23505` + 约束名兜底）。 */
    private fun <T> Future<T>.mapUniqueViolation(constraint: String, message: String): Future<T> =
        recover { error ->
            if (isUniqueViolation(error, setOf(constraint))) {
                Future.failedFuture(ConflictException(message))
            } else {
                Future.failedFuture(error)
            }
        }
}
