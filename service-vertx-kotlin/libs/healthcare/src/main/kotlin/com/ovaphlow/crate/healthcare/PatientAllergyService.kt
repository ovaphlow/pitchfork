package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.OffsetDateTime

/**
 * 患者药物过敏史规范化服务（053 D1，V535）。
 *
 * 职责：把 `patients.allergies` 只读遗留 JSONB（V500，`[{"allergen","reaction"}]`）规范化后的
 * 过敏史行提供增改查删（软删），供 053 D2 过敏/禁忌检查引擎与开嘱提醒消费。
 *
 * 业务规则（服务端强制）：
 *  1. 过敏史适用于**所有患者**（不限制 `person_type=儿童`）；`patient_id` 用原生 SQL 校验存在，
 *     不存在 → [HealthcareNotFoundException]（路由 404）。
 *  2. 创建写白名单：`patient_id` / `allergen_type` / `allergen_name` / `allergen_code` /
 *     `reaction` / `severity` / `metadata`；多余键一律 400。
 *     必填 `patient_id` / `allergen_type` / `allergen_name`；`allergen_type` / `severity` 白名单校验；
 *     `allergen_name` ≤ 200、`reaction` ≤ 1000、`allergen_code` ≤ 100；`severity` 缺省「一般」。
 *  3. 创建时 `status` 固定 [createStatus]（有效），`operator` 取认证主体，客户端不得提交。
 *  4. 更新写白名单：`allergen_type` / `allergen_name` / `allergen_code` / `reaction` /
 *     `severity` / `status` / `metadata`（**不含 `patient_id`**）；枚举白名单校验；多余键一律 400。
 *     记录不存在 → [HealthcareNotFoundException]（路由 404）。
 *  5. 删除为软删：`status → 已停用`、`updated_at = now`；幂等（已停用再调仍 200）。
 *  6. 列表 `patient_id` 必填；`status` 可选白名单，**缺省只返回「有效」**（临床查询默认有效过敏史），
 *     `status=已停用` 可显式查；`patient_name` 由 JOIN 回显；按 `created_at DESC`。
 *  7. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class PatientAllergyService(private val pool: Pool) {

    companion object {
        /** 过敏原类型中文枚举（应用层白名单，与 V535 注释一致）。 */
        val allergenTypes: Set<String> = linkedSetOf("药物", "食物", "其他")

        /** 严重度中文枚举（应用层白名单）。 */
        val severities: Set<String> = linkedSetOf("一般", "严重")

        /** 状态中文枚举（应用层白名单）。 */
        val statuses: Set<String> = linkedSetOf("有效", "已停用")

        /** 创建时的固定初始状态。 */
        const val createStatus: String = "有效"

        /** 列表缺省过滤状态：临床查询默认只看有效过敏史。 */
        const val defaultListStatus: String = "有效"

        /** 创建写白名单（派生字段 `status` / `operator` 不在其中）。 */
        private val createKeys = setOf(
            "patient_id", "allergen_type", "allergen_name", "allergen_code",
            "reaction", "severity", "metadata",
        )

        /** 更新写白名单（`patient_id` 不在其中，不允许改归属）。 */
        private val updateKeys = setOf(
            "allergen_type", "allergen_name", "allergen_code",
            "reaction", "severity", "status", "metadata",
        )

        private const val MAX_ALLERGEN_NAME = 200
        private const val MAX_REACTION = 1000
        private const val MAX_ALLERGEN_CODE = 100

        /** 患者档案原生存在性查询（过敏史适用于所有患者，不限制 person_type）。 */
        private const val PATIENT_SQL =
            "SELECT id FROM healthcare.patients WHERE id = \$1"

        private const val SELECT_COLUMNS =
            "a.id, a.patient_id, p.name AS patient_name, a.allergen_type, a.allergen_name, " +
                "a.allergen_code, a.reaction, a.severity, a.status, a.operator, a.metadata, " +
                "a.created_at, a.updated_at"

        private const val BASE_FROM =
            " FROM healthcare.patient_allergies a " +
                "JOIN healthcare.patients p ON p.id = a.patient_id "

        private const val SELECT_BY_ID_SQL = "SELECT $SELECT_COLUMNS$BASE_FROM WHERE a.id = \$1"

        private const val LIST_SQL =
            "SELECT $SELECT_COLUMNS$BASE_FROM " +
                "WHERE a.patient_id = \$1 AND a.status = \$2 " +
                "ORDER BY a.created_at DESC, a.id DESC LIMIT \$3 OFFSET \$4"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.patient_allergies " +
                "WHERE patient_id = \$1 AND status = \$2"

        /** 软删：置「已停用」，幂等（重复调用仍命中同一行）。 */
        private const val DEACTIVATE_SQL =
            "UPDATE healthcare.patient_allergies SET status = '已停用', updated_at = \$2 WHERE id = \$1"

        /** 行 → 单条过敏史 JSON（字段缺失给 null，不抛）。 */
        internal fun allergyJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("allergen_type", row.getString("allergen_type"))
            .put("allergen_name", row.getString("allergen_name"))
            .put("allergen_code", row.getString("allergen_code"))
            .put("reaction", row.getString("reaction"))
            .put("severity", row.getString("severity"))
            .put("status", row.getString("status"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  创建
    // ========================================================================

    /**
     * 创建一条过敏史：校验 → 患者存在性自查 → 落库 → 回读单条。
     *
     * `status` 固定 [createStatus]（有效），`operator` 取认证主体。
     *
     * @throws IllegalArgumentException 白名单/形状/枚举/长度校验失败（经 `Future` 失败回传，路由 400）。
     * @throws HealthcareNotFoundException 患者不存在（路由 404）。
     */
    fun create(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(PATIENT_SQL).execute(Tuple.of(parsed.patientId))
                .compose<JsonObject> { rows ->
                    if (rows.iterator().asSequence().firstOrNull() == null) {
                        return@compose Future.failedFuture(
                            HealthcareNotFoundException("patient not found: ${parsed.patientId}"),
                        )
                    }
                    // 动态列：可空字段仅在提供时入列，避免绑定 null（原生参数化 SQL，$1..$n 位置绑定）。
                    val columns = mutableListOf(
                        "id", "patient_id", "allergen_type", "allergen_name",
                        "severity", "status", "operator",
                    )
                    val values = mutableListOf<Any>(
                        id, parsed.patientId, parsed.allergenType, parsed.allergenName,
                        parsed.severity, createStatus, operator,
                    )
                    parsed.allergenCode?.let { columns.add("allergen_code"); values.add(it) }
                    parsed.reaction?.let { columns.add("reaction"); values.add(it) }
                    parsed.metadata?.let { columns.add("metadata"); values.add(it) }
                    columns.add("created_at"); values.add(now)
                    columns.add("updated_at"); values.add(now)

                    val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
                    val sql =
                        "INSERT INTO healthcare.patient_allergies (${columns.joinToString(", ")}) " +
                            "VALUES ($placeholders)"
                    val tuple = Tuple.tuple()
                    values.forEach { tuple.addValue(it) }
                    connection.preparedQuery(sql).execute(tuple)
                        .compose { getAllergyVia(connection, id) }
                }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    /**
     * 按患者列出过敏史（`created_at DESC`）。
     *
     * `patient_id` 必填；`status` 可选且必须在 [statuses] 白名单内，**缺省只返回「有效」**；
     * `patient_name` 由 JOIN 回显。空列表也返回 `records: []` 与 `total: 0`。
     */
    fun list(patientId: String?, status: String?, limit: Int, offset: Int): Future<JsonObject> {
        val patient = patientId?.trim()?.takeIf(String::isNotBlank)
            ?: return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        val explicitStatus = status?.trim()?.takeIf(String::isNotBlank)
        if (explicitStatus != null && explicitStatus !in statuses) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $explicitStatus"))
        }
        val statusFilter = explicitStatus ?: defaultListStatus
        return pool.preparedQuery(COUNT_SQL).execute(Tuple.of(patient, statusFilter)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(LIST_SQL).execute(Tuple.of(patient, statusFilter, limit, offset)).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::allergyJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  更新（覆盖式）与软删
    // ========================================================================

    /**
     * 更新一条过敏史（只改提供的字段，`patient_id` 不可改）。
     *
     * 入参白名单：[updateKeys]；枚举白名单校验；多余键 400。
     * 记录不存在 → [HealthcareNotFoundException]（路由 404）。
     *
     * `operator` 为冻结签名的一部分：过敏史行的 `operator` 以创建时的认证主体为准，
     * 本接口不覆写该列（042 审计语义）。
     */
    fun update(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseUpdate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val sets = mutableListOf<String>()
        val values = mutableListOf<Any>()
        var nextIndex = 1
        parsed.allergenType?.let { sets.add("allergen_type = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.allergenName?.let { sets.add("allergen_name = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.allergenCode?.let { sets.add("allergen_code = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.reaction?.let { sets.add("reaction = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.severity?.let { sets.add("severity = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.status?.let { sets.add("status = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.metadata?.let { sets.add("metadata = \$$nextIndex"); values.add(it); nextIndex++ }
        sets.add("updated_at = \$$nextIndex")
        values.add(now)
        nextIndex++

        val sql = "UPDATE healthcare.patient_allergies SET ${sets.joinToString(", ")} WHERE id = \$$nextIndex"
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        tuple.addString(id)
        return pool.preparedQuery(sql).execute(tuple)
            .compose { getAllergyVia(pool, id) }
    }

    /**
     * 软删一条过敏史：`status → 已停用`、`updated_at = now`。
     *
     * 幂等：已停用再调仍命中同一行并回读 200；记录不存在 → [HealthcareNotFoundException]（路由 404）。
     */
    fun deactivate(id: String): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return pool.preparedQuery(DEACTIVATE_SQL).execute(Tuple.of(id, now))
            .compose { getAllergyVia(pool, id) }
    }

    // ========================================================================
    //  内部：持久化与校验
    // ========================================================================

    private fun getAllergyVia(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(SELECT_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(allergyJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("patient allergy not found: $id"))
        }

    private data class ParsedCreate(
        val patientId: String,
        val allergenType: String,
        val allergenName: String,
        val allergenCode: String?,
        val reaction: String?,
        val severity: String,
        val metadata: JsonObject?,
    )

    private data class ParsedUpdate(
        val allergenType: String?,
        val allergenName: String?,
        val allergenCode: String?,
        val reaction: String?,
        val severity: String?,
        val status: String?,
        val metadata: JsonObject?,
    )

    private fun parseCreate(body: JsonObject): ParsedCreate {
        rejectForbiddenKeys(body, createKeys, "patient allergy")
        val allergenType = requiredText(body, "allergen_type")
        if (allergenType !in allergenTypes) {
            throw IllegalArgumentException(
                "invalid allergen_type: $allergenType (expected ${allergenTypes.joinToString("/")})",
            )
        }
        val allergenName = requiredText(body, "allergen_name")
        if (allergenName.length > MAX_ALLERGEN_NAME) {
            throw IllegalArgumentException("allergen_name must not exceed $MAX_ALLERGEN_NAME characters")
        }
        val severity = optionalText(body, "severity") ?: "一般"
        if (severity !in severities) {
            throw IllegalArgumentException(
                "invalid severity: $severity (expected ${severities.joinToString("/")})",
            )
        }
        return ParsedCreate(
            patientId = requiredText(body, "patient_id"),
            allergenType = allergenType,
            allergenName = allergenName,
            allergenCode = optionalText(body, "allergen_code", MAX_ALLERGEN_CODE),
            reaction = optionalText(body, "reaction", MAX_REACTION),
            severity = severity,
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun parseUpdate(body: JsonObject): ParsedUpdate {
        rejectForbiddenKeys(body, updateKeys, "patient allergy")
        val allergenType = optionalText(body, "allergen_type")
        if (allergenType != null && allergenType !in allergenTypes) {
            throw IllegalArgumentException(
                "invalid allergen_type: $allergenType (expected ${allergenTypes.joinToString("/")})",
            )
        }
        val severity = optionalText(body, "severity")
        if (severity != null && severity !in severities) {
            throw IllegalArgumentException(
                "invalid severity: $severity (expected ${severities.joinToString("/")})",
            )
        }
        val status = optionalText(body, "status")
        if (status != null && status !in statuses) {
            throw IllegalArgumentException("invalid status: $status")
        }
        return ParsedUpdate(
            allergenType = allergenType,
            allergenName = optionalText(body, "allergen_name", MAX_ALLERGEN_NAME),
            allergenCode = optionalText(body, "allergen_code", MAX_ALLERGEN_CODE),
            reaction = optionalText(body, "reaction", MAX_REACTION),
            severity = severity,
            status = status,
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun optionalText(body: JsonObject, key: String, maxLength: Int? = null): String? {
        val value = body.getString(key)?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (maxLength != null && value.length > maxLength) {
            throw IllegalArgumentException("$key must not exceed $maxLength characters")
        }
        return value
    }

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }
}
