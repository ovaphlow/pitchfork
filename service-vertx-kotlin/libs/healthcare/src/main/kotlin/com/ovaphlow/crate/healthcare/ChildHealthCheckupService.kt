package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 儿保体检记录服务（052 P1，V526）。
 *
 * 业务规则（服务端强制）：
 *  1. 只服务 `person_type=儿童` 且 `birth_date` 非空的患者（`childPatientRow`）。
 *  2. `checkup_type` / `conclusion` 为中文白名单；`checkup_date` 不得晚于业务时区今天。
 *  3. `age_months` 由服务端按 `birth_date` 与 `checkup_date` 派生落库，客户端不得提交；
 *     口径与前端 `lib/age.ts` 的 `formatAge` 日历差一致。
 *  4. `items` 为 `[{item,result,conclusion}]` 数组（缺省 `[]`），只做形状校验。
 *  5. `operator` 一律取认证主体（042 范式），写接口按白名单拒绝 id/age_months/operator/
 *     created_at/updated_at。
 *  6. 年度批次体检（`health_checkups`）保持现状，本表不参与批次（052 D1）。
 */
class ChildHealthCheckupService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 体检类型中文白名单（与前端表单选项同源）。 */
        val checkupTypes = setOf("满月", "3月", "6月", "9月", "12月", "18月", "24月", "3岁", "常规")

        /** 结论中文白名单。 */
        val conclusions = setOf("正常", "异常", "需转诊")

        /** 诊断状态中文白名单（053 B1，单向流转：待诊断 → 已完成）。 */
        val checkupStatuses = setOf("待诊断", "已完成")

        /** 诊断写白名单：`reviewed_by`/`reviewed_at`/`status` 由服务端派生，客户端提交即 400。 */
        private val diagnoseKeys = setOf("diagnosis", "diagnosis_code")

        /** 诊断回写（053 B1，原生参数化 SQL，覆盖式；`status` 单向到「已完成」）。 */
        private const val DIAGNOSE_SQL =
            "UPDATE healthcare.child_health_checkups SET " +
                "diagnosis = \$1, diagnosis_code = \$2, reviewed_by = \$3, reviewed_at = \$4, " +
                "status = \$5, updated_at = \$6 WHERE id = \$7"

        /** 创建写白名单：id/age_months/operator/created_at/updated_at 由服务端管控。 */
        private val createKeys = setOf(
            "patient_id", "checkup_date", "checkup_type", "items", "conclusion",
            "guidance", "next_checkup_date", "remark", "metadata",
        )

        /** 修正写白名单（patient_id 不可改：体检记录不换儿童）。 */
        private val patchKeys = setOf(
            "checkup_date", "checkup_type", "items", "conclusion",
            "guidance", "next_checkup_date", "remark", "metadata",
        )

        internal fun checkupJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("checkup_date", row.getLocalDate("checkup_date")?.toString())
                .put("checkup_type", row.getString("checkup_type"))
                .put("age_months", row.getInteger("age_months"))
                .put("items", row.getValue("items"))
                .put("conclusion", row.getString("conclusion"))
                .put("diagnosis", row.getString("diagnosis"))
                .put("diagnosis_code", row.getString("diagnosis_code"))
                .put("status", row.getString("status"))
                .put("reviewed_by", row.getString("reviewed_by"))
                .put("reviewed_at", row.getOffsetDateTime("reviewed_at")?.toString())
                .put("guidance", row.getString("guidance"))
                .put("next_checkup_date", row.getLocalDate("next_checkup_date")?.toString())
                .put("operator", row.getString("operator"))
                .put("remark", row.getString("remark"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    private val table = DSL.table(DSL.name("healthcare", "child_health_checkups"))

    /** SELECT 列（带表名限定，与 V523–V525 约定一致）。 */
    private val selectFields: List<Field<*>> = listOf(
        DSL.field(DSL.name("child_health_checkups", "id"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "patient_id"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "checkup_date"), LocalDate::class.java),
        DSL.field(DSL.name("child_health_checkups", "checkup_type"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "age_months"), Int::class.javaObjectType),
        DSL.field(DSL.name("child_health_checkups", "items"), JSONB::class.java),
        DSL.field(DSL.name("child_health_checkups", "conclusion"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "diagnosis"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "diagnosis_code"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "status"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "reviewed_by"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "reviewed_at"), OffsetDateTime::class.java),
        DSL.field(DSL.name("child_health_checkups", "guidance"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "next_checkup_date"), LocalDate::class.java),
        DSL.field(DSL.name("child_health_checkups", "operator"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "remark"), String::class.java),
        DSL.field(DSL.name("child_health_checkups", "metadata"), JSONB::class.java),
        DSL.field(DSL.name("child_health_checkups", "created_at"), OffsetDateTime::class.java),
        DSL.field(DSL.name("child_health_checkups", "updated_at"), OffsetDateTime::class.java),
    )

    // INSERT / UPDATE SET 用的裸列名。
    private val wId = DSL.field(DSL.name("id"), String::class.java)
    private val wPatientId = DSL.field(DSL.name("patient_id"), String::class.java)
    private val wCheckupDate = DSL.field(DSL.name("checkup_date"), LocalDate::class.java)
    private val wCheckupType = DSL.field(DSL.name("checkup_type"), String::class.java)
    private val wAgeMonths = DSL.field(DSL.name("age_months"), Int::class.javaObjectType)
    private val wItems = DSL.field(DSL.name("items"), JSONB::class.java)
    private val wConclusion = DSL.field(DSL.name("conclusion"), String::class.java)
    private val wGuidance = DSL.field(DSL.name("guidance"), String::class.java)
    private val wNextCheckupDate = DSL.field(DSL.name("next_checkup_date"), LocalDate::class.java)
    private val wOperator = DSL.field(DSL.name("operator"), String::class.java)
    private val wRemark = DSL.field(DSL.name("remark"), String::class.java)
    private val wMetadata = DSL.field(DSL.name("metadata"), JSONB::class.java)
    private val wCreatedAt = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
    private val wUpdatedAt = DSL.field(DSL.name("updated_at"), OffsetDateTime::class.java)

    // ========================================================================
    //  创建
    // ========================================================================

    fun createCheckup(body: JsonObject, operator: String): Future<JsonObject> {
        val fields: Fields = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        val patientId = requireNotNull(fields.patientId)
        val checkupDate = requireNotNull(fields.checkupDate)
        return pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, patientId).compose { patient ->
                val birthDate = patient.getLocalDate("birth_date")
                val ageMonths = try {
                    ChildHealth.ageMonths(birthDate, checkupDate)
                } catch (error: IllegalArgumentException) {
                    return@compose Future.failedFuture(error)
                }
                val insert = ctx.insertInto(table)
                    .set(wId, id)
                    .set(wPatientId, patientId)
                    .set(wCheckupDate, checkupDate)
                    .set(wCheckupType, requireNotNull(fields.checkupType))
                    .set(wAgeMonths, ageMonths)
                    .set(wItems, JSONB.valueOf(fields.items.encode()))
                    .set(wOperator, operator)
                    .set(wCreatedAt, now)
                    .set(wUpdatedAt, now)
                    .apply {
                        fields.conclusion?.let { set(wConclusion, it) }
                        fields.guidance?.let { set(wGuidance, it) }
                        fields.nextCheckupDate?.let { set(wNextCheckupDate, it) }
                        fields.remark?.let { set(wRemark, it) }
                        fields.metadata?.let { set(wMetadata, JSONB.valueOf(it.encode())) }
                    }
                executeChildQuery(connection, insert).compose { getCheckupVia(connection, id) }
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    fun listCheckups(
        patientId: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = try {
            val patient = patientId?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("patient_id is required")
            mutableListOf<Condition>(
                DSL.field(DSL.name("child_health_checkups", "patient_id"), String::class.java).eq(patient),
            ).also {
                dateFrom?.takeIf(String::isNotBlank)?.let { value ->
                    it += DSL.field(DSL.name("child_health_checkups", "checkup_date"), LocalDate::class.java)
                        .ge(localDate(value, "date_from"))
                }
                dateTo?.takeIf(String::isNotBlank)?.let { value ->
                    it += DSL.field(DSL.name("child_health_checkups", "checkup_date"), LocalDate::class.java)
                        .le(localDate(value, "date_to"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(table).where(conditions)
        val dataQuery = baseQuery()
            .where(conditions)
            .orderBy(
                DSL.field(DSL.name("child_health_checkups", "checkup_date"), LocalDate::class.java).desc(),
                DSL.field(DSL.name("child_health_checkups", "created_at"), OffsetDateTime::class.java).desc(),
            )
            .limit(limit)
            .offset(offset)

        return executeChildQuery(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            executeChildQuery(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::checkupJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getCheckup(id: String): Future<JsonObject> = getCheckupVia(pool, id)

    private fun getCheckupVia(client: SqlClient, id: String): Future<JsonObject> =
        executeChildQuery(client, baseQuery().where(DSL.field(DSL.name("child_health_checkups", "id"), String::class.java).eq(id)))
            .compose { rows ->
                rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(checkupJson(it)) }
                    ?: Future.failedFuture(HealthcareNotFoundException("child health checkup not found: $id"))
            }

    // ========================================================================
    //  修正
    // ========================================================================

    fun updateCheckup(id: String, body: JsonObject): Future<JsonObject> {
        val fields: Fields
        val patch: JsonObject
        try {
            rejectForbiddenKeys(body, patchKeys, "child health checkup update")
            patch = body
            fields = parsePatch(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getRow(connection, id).compose { existing ->
                val checkupDate = fields.checkupDate ?: existing.getLocalDate("checkup_date")
                childPatientRow(ctx, connection, existing.getString("patient_id")!!).compose { patient ->
                    val birthDate = patient.getLocalDate("birth_date")
                    val ageMonths = try {
                        ChildHealth.ageMonths(birthDate, checkupDate)
                    } catch (error: IllegalArgumentException) {
                        return@compose Future.failedFuture(error)
                    }
                    val nextCheckupDate = if (patch.containsKey("next_checkup_date")) {
                        fields.nextCheckupDate
                    } else {
                        existing.getLocalDate("next_checkup_date")
                    }
                    if (nextCheckupDate != null && nextCheckupDate.isBefore(checkupDate)) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("next_checkup_date must not be before checkup_date"),
                        )
                    }
                    val update = ctx.update(table)
                        .set(wCheckupDate, checkupDate)
                        .set(wAgeMonths, ageMonths)
                        .set(wUpdatedAt, now)
                        .apply {
                            fields.checkupType?.let { set(wCheckupType, it) }
                            if (patch.containsKey("items")) set(wItems, JSONB.valueOf(fields.items.encode()))
                            if (patch.containsKey("conclusion")) {
                                if (fields.conclusion == null) setNull(wConclusion) else set(wConclusion, fields.conclusion)
                            }
                            if (patch.containsKey("guidance")) {
                                if (fields.guidance == null) setNull(wGuidance) else set(wGuidance, fields.guidance)
                            }
                            if (patch.containsKey("next_checkup_date")) {
                                if (nextCheckupDate == null) setNull(wNextCheckupDate) else set(wNextCheckupDate, nextCheckupDate)
                            }
                            if (patch.containsKey("remark")) {
                                if (fields.remark == null) setNull(wRemark) else set(wRemark, fields.remark)
                            }
                            if (patch.containsKey("metadata")) {
                                if (fields.metadata == null) setNull(wMetadata) else set(wMetadata, JSONB.valueOf(fields.metadata.encode()))
                            }
                        }
                        .where(DSL.field(DSL.name("child_health_checkups", "id"), String::class.java).eq(id))
                    executeChildQuery(connection, update).compose { getCheckupVia(connection, id) }
                }
            }
        }
    }

    // ========================================================================
    //  医生诊断（053 B1）
    // ========================================================================

    /**
     * 医生对体检记录回写诊断（053 B1，**覆盖式**）。
     *
     * 入参白名单：`diagnosis`（必填，≤2000 字）、`diagnosis_code`（可选，≤100 字）；多余键 400。
     * `reviewed_by` = 认证主体 [operator]、`reviewed_at` = now、`status` = `已完成` 均由服务端派生，
     * 客户端提交即 400。允许对同一记录重复诊断（覆写上述字段与 `updated_at`）；因本接口无 `status`
     * 入参，已「已完成」天然不会回到「待诊断」（单向流转）。
     *
     * 记录不存在 → [HealthcareNotFoundException]（由 [getCheckupVia] 回读判定）。
     *
     * 新持久化走原生参数化 SQL；回读沿用本文件既有 jOOQ `getCheckupVia`（052 文件保留 jOOQ）。
     */
    fun diagnose(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val diagnosis: String
        val diagnosisCode: String?
        try {
            rejectForbiddenKeys(body, diagnoseKeys, "child health checkup diagnosis")
            diagnosis = requiredText(body, "diagnosis").also {
                if (it.length > 2000) throw IllegalArgumentException("diagnosis must not exceed 2000 characters")
            }
            diagnosisCode = optionalText(body, "diagnosis_code", 100)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(diagnosis)
        tuple.addValue(diagnosisCode)
        tuple.addString(operator)
        tuple.addOffsetDateTime(now)
        tuple.addString("已完成")
        tuple.addOffsetDateTime(now)
        tuple.addString(id)

        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(DIAGNOSE_SQL).execute(tuple)
                .compose { getCheckupVia(connection, id) }
        }
    }

    // ========================================================================
    //  解析与校验
    // ========================================================================

    private data class Fields(
        val patientId: String? = null,
        val checkupDate: LocalDate? = null,
        val checkupType: String? = null,
        val items: JsonArray = JsonArray(),
        val conclusion: String? = null,
        val guidance: String? = null,
        val nextCheckupDate: LocalDate? = null,
        val remark: String? = null,
        val metadata: JsonObject? = null,
    )

    private fun parseCreate(body: JsonObject): Fields {
        rejectForbiddenKeys(body, createKeys, "child health checkup")
        val patientId = requiredText(body, "patient_id")
        val checkupDate = requiredDate(body, "checkup_date")
        if (checkupDate.isAfter(ChildHealth.today())) {
            throw IllegalArgumentException("checkup_date must not be in the future")
        }
        val checkupType = validValue(requiredText(body, "checkup_type"), checkupTypes, "checkup_type")
        val items = itemsValue(body)
        val conclusion = body.getString("conclusion")?.trim()?.takeIf(String::isNotBlank)?.let {
            validValue(it, conclusions, "conclusion")
        }
        val guidance = optionalText(body, "guidance", 2000)
        val nextCheckupDate = body.getString("next_checkup_date")?.let { localDate(it, "next_checkup_date") }
        if (nextCheckupDate != null && nextCheckupDate.isBefore(checkupDate)) {
            throw IllegalArgumentException("next_checkup_date must not be before checkup_date")
        }
        return Fields(
            patientId = patientId,
            checkupDate = checkupDate,
            checkupType = checkupType,
            items = items,
            conclusion = conclusion,
            guidance = guidance,
            nextCheckupDate = nextCheckupDate,
            remark = optionalText(body, "remark", 1000),
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun parsePatch(body: JsonObject): Fields {
        val checkupDate = body.getString("checkup_date")?.let {
            localDate(it, "checkup_date").also { date ->
                if (date.isAfter(ChildHealth.today())) {
                    throw IllegalArgumentException("checkup_date must not be in the future")
                }
            }
        }
        val checkupType = body.getString("checkup_type")?.trim()?.takeIf(String::isNotBlank)?.let {
            validValue(it, checkupTypes, "checkup_type")
        }
        val conclusion = if (body.containsKey("conclusion")) {
            body.getString("conclusion")?.trim()?.takeIf(String::isNotBlank)?.let {
                validValue(it, conclusions, "conclusion")
            }
        } else {
            null
        }
        val nextCheckupDate = body.getString("next_checkup_date")?.let { localDate(it, "next_checkup_date") }
        if (nextCheckupDate != null && checkupDate != null && nextCheckupDate.isBefore(checkupDate)) {
            throw IllegalArgumentException("next_checkup_date must not be before checkup_date")
        }
        return Fields(
            checkupDate = checkupDate,
            checkupType = checkupType,
            items = itemsValue(body),
            conclusion = conclusion,
            guidance = optionalText(body, "guidance", 2000),
            nextCheckupDate = nextCheckupDate,
            remark = optionalText(body, "remark", 1000),
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun itemsValue(body: JsonObject): JsonArray {
        val value = body.getValue("items") ?: return JsonArray()
        val array = value as? JsonArray ?: throw IllegalArgumentException("items must be a JSON array")
        array.forEachIndexed { index, entry ->
            if (entry !is JsonObject) throw IllegalArgumentException("items[$index] must be a JSON object")
        }
        return array
    }

    private fun baseQuery() = ctx.select(
        selectFields + listOf(DSL.field(DSL.name("patients", "name"), String::class.java).`as`("patient_name")),
    )
        .from(table)
        .join(DSL.table(DSL.name("healthcare", "patients")))
        .on(
            DSL.field(DSL.name("child_health_checkups", "patient_id"), String::class.java)
                .eq(DSL.field(DSL.name("patients", "id"), String::class.java)),
        )

    private fun getRow(client: SqlClient, id: String): Future<Row> =
        executeChildQuery(
            client,
            ctx.select(selectFields).from(table)
                .where(DSL.field(DSL.name("child_health_checkups", "id"), String::class.java).eq(id)),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("child health checkup not found: $id"))
        }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun requiredDate(body: JsonObject, key: String): LocalDate =
        localDate(requiredText(body, key), key)

    private fun optionalText(body: JsonObject, key: String, max: Int): String? =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > max) throw IllegalArgumentException("$key must not exceed $max characters")
        }

    private fun validValue(value: String, allowed: Set<String>, label: String): String =
        value.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun localDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 date")
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
