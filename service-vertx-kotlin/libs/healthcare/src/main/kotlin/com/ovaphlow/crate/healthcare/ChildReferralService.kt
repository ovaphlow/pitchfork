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
 * 儿童健康问题转诊记录服务（053 B2，V533）。
 *
 * 职责：把体检/筛查/异常/手动来源的健康问题落成一条转诊记录，并以**应用层白名单状态机**
 * 管控流转（`待转诊 → 已转诊 → 已完成`，+`已取消`）。不强制关联 encounter（延续 052 口径）。
 *
 * 业务规则（服务端强制）：
 *  1. 只服务 `person_type=儿童` 的患者（本服务内原生 SQL 自查，不使用需要 `DSLContext`
 *     的 `childPatientRow`）。
 *  2. 创建写白名单：`patient_id` / `source_type` / `source_id` / `problem_summary` /
 *     `referral_type` / `target_org` / `target_dept` / `urgency` / `metadata`；多余键一律 400。
 *     必填 `patient_id` / `source_type` / `problem_summary` / `referral_type`；
 *     `source_type` / `referral_type` / `urgency` 白名单校验；`problem_summary` ≤ 2000 字。
 *  3. 创建时 `status` 固定 [createStatus]（待转诊），`operator` 取认证主体，`referred_at`
 *     不设；不接受客户端提交。
 *  4. 状态更新写白名单：`status` / `outcome` / `referred_at` / `metadata`；多余键一律 400。
 *     非法流转（含同状态重复提交）→ [IllegalArgumentException]，由路由映射 400。
 *  5. 转入「已转诊」时 `referred_at` 缺省 `now`，不得晚于现在；其余流转不触碰该列。
 *  6. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class ChildReferralService(private val pool: Pool) {

    companion object {
        /** 来源中文枚举（应用层白名单，与 V533 注释一致）。 */
        val sourceTypes: Set<String> = linkedSetOf("体检", "筛查", "异常", "手动")

        /** 转诊类型中文枚举（应用层白名单）。 */
        val referralTypes: Set<String> = linkedSetOf("院内转诊", "转上级医院", "专科转诊")

        /** 紧急度中文枚举（应用层白名单）。 */
        val urgencies: Set<String> = linkedSetOf("常规", "急")

        /** 状态中文枚举（应用层白名单）。 */
        val statuses: Set<String> = linkedSetOf("待转诊", "已转诊", "已完成", "已取消")

        /** 创建时的固定初始状态。 */
        const val createStatus: String = "待转诊"

        /** 状态机：当前状态 → 允许的下一状态（终态映射空集）——纯数据，供单测。 */
        val transitions: Map<String, Set<String>> = mapOf(
            "待转诊" to setOf("已转诊", "已取消"),
            "已转诊" to setOf("已完成"),
            "已完成" to emptySet(),
            "已取消" to emptySet(),
        )

        /** 纯状态机判定：同状态重复提交、跨级流转、终态再转一律 `false`。 */
        fun canTransition(from: String, to: String): Boolean =
            transitions[from]?.contains(to) == true

        /** 创建写白名单（派生字段 `status` / `operator` / `referred_at` 不在其中）。 */
        private val createKeys = setOf(
            "patient_id", "source_type", "source_id", "problem_summary",
            "referral_type", "target_org", "target_dept", "urgency", "metadata",
        )

        /** 状态更新写白名单。 */
        private val statusKeys = setOf("status", "outcome", "referred_at", "metadata")

        private const val MAX_PROBLEM_SUMMARY = 2000

        /** 儿童档案原生查询（不使用 jOOQ / `childPatientRow`）。 */
        private const val PATIENT_SQL =
            "SELECT id, name, birth_date, person_type FROM healthcare.patients WHERE id = \$1"

        private const val SELECT_COLUMNS =
            "r.id, r.patient_id, p.name AS patient_name, r.source_type, r.source_id, " +
                "r.problem_summary, r.referral_type, r.target_org, r.target_dept, r.urgency, " +
                "r.status, r.referred_at, r.outcome, r.operator, r.metadata, r.created_at, r.updated_at"

        private const val BASE_FROM =
            " FROM healthcare.child_referrals r " +
                "JOIN healthcare.patients p ON p.id = r.patient_id "

        private const val SELECT_BY_ID_SQL = "SELECT $SELECT_COLUMNS$BASE_FROM WHERE r.id = \$1"

        private const val LIST_SQL =
            "SELECT $SELECT_COLUMNS$BASE_FROM WHERE r.patient_id = \$1 " +
                "ORDER BY r.created_at DESC, r.id DESC LIMIT \$2 OFFSET \$3"

        private const val LIST_BY_STATUS_SQL =
            "SELECT $SELECT_COLUMNS$BASE_FROM WHERE r.patient_id = \$1 AND r.status = \$2 " +
                "ORDER BY r.created_at DESC, r.id DESC LIMIT \$3 OFFSET \$4"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.child_referrals WHERE patient_id = \$1"

        private const val COUNT_BY_STATUS_SQL =
            "SELECT count(*) AS total FROM healthcare.child_referrals " +
                "WHERE patient_id = \$1 AND status = \$2"

        /** 状态机读取当前状态并加行锁（存在性 + 流转判定在同一事务内）。 */
        private const val LOCK_SQL =
            "SELECT status FROM healthcare.child_referrals WHERE id = \$1 FOR UPDATE"

        /** 行 → 单条转诊 JSON（字段缺失给 null，不抛）。 */
        internal fun referralJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("source_type", row.getString("source_type"))
            .put("source_id", row.getString("source_id"))
            .put("problem_summary", row.getString("problem_summary"))
            .put("referral_type", row.getString("referral_type"))
            .put("target_org", row.getString("target_org"))
            .put("target_dept", row.getString("target_dept"))
            .put("urgency", row.getString("urgency"))
            .put("status", row.getString("status"))
            .put("referred_at", row.getOffsetDateTime("referred_at")?.toString())
            .put("outcome", row.getString("outcome"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  创建
    // ========================================================================

    /**
     * 创建一条转诊记录：校验 → 儿童自查 → 落库 → 回读单条。
     *
     * `status` 固定 [createStatus]，`referred_at` / `outcome` 不设。
     *
     * @throws IllegalArgumentException 白名单/形状校验失败（经 `Future` 失败回传，路由 400）。
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
            loadChild(connection, parsed.patientId).compose<JsonObject> { _ ->
                // 动态列：可空字段仅在提供时入列，避免绑定 null（原生参数化 SQL，$1..$n 位置绑定）。
                val columns = mutableListOf(
                    "id", "patient_id", "source_type", "problem_summary",
                    "referral_type", "urgency", "status", "operator",
                )
                val values = mutableListOf<Any>(
                    id, parsed.patientId, parsed.sourceType, parsed.problemSummary,
                    parsed.referralType, parsed.urgency, createStatus, operator,
                )
                parsed.sourceId?.let { columns.add("source_id"); values.add(it) }
                parsed.targetOrg?.let { columns.add("target_org"); values.add(it) }
                parsed.targetDept?.let { columns.add("target_dept"); values.add(it) }
                parsed.metadata?.let { columns.add("metadata"); values.add(it) }
                columns.add("created_at"); values.add(now)
                columns.add("updated_at"); values.add(now)

                val placeholders = values.indices.joinToString(", ") { "\$${it + 1}" }
                val sql =
                    "INSERT INTO healthcare.child_referrals (${columns.joinToString(", ")}) " +
                        "VALUES ($placeholders)"
                val tuple = Tuple.tuple()
                values.forEach { tuple.addValue(it) }
                connection.preparedQuery(sql).execute(tuple)
                    .compose { getReferralVia(connection, id) }
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    /**
     * 按儿童列出转诊记录（`created_at DESC`）。
     *
     * `patient_id` 必填；`status` 可选且必须在 [statuses] 白名单内；`patient_name` 由 JOIN 回显。
     * 空列表也返回 `records: []` 与 `total: 0`。
     */
    fun list(patientId: String?, status: String?, limit: Int, offset: Int): Future<JsonObject> {
        val patient = patientId?.trim()?.takeIf(String::isNotBlank)
            ?: return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        val statusFilter = status?.trim()?.takeIf(String::isNotBlank)
        if (statusFilter != null && statusFilter !in statuses) {
            return Future.failedFuture(IllegalArgumentException("invalid status: $statusFilter"))
        }
        return if (statusFilter == null) {
            pool.preparedQuery(COUNT_SQL).execute(Tuple.of(patient)).compose { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(LIST_SQL).execute(Tuple.of(patient, limit, offset)).map { rows ->
                    pageJson(rows, total)
                }
            }
        } else {
            pool.preparedQuery(COUNT_BY_STATUS_SQL)
                .execute(Tuple.of(patient, statusFilter)).compose { countRows ->
                    val total = countRows.iterator().next().getLong("total") ?: 0L
                    pool.preparedQuery(LIST_BY_STATUS_SQL)
                        .execute(Tuple.of(patient, statusFilter, limit, offset)).map { rows ->
                            pageJson(rows, total)
                        }
                }
        }
    }

    // ========================================================================
    //  状态流转
    // ========================================================================

    /**
     * 按状态机流转一条转诊记录：读当前状态（行锁）→ `canTransition` 判定 → 更新 → 回读。
     *
     * 非法流转（含同状态重复提交、终态再转）→ [IllegalArgumentException]（路由 400）；
     * 记录不存在 → [HealthcareNotFoundException]（路由 404）。
     *
     * `operator` 为冻结签名的一部分：转诊记录的操作人以创建时的 `operator` 为准，
     * 状态流转不覆写该列（审计语义见 042）。
     */
    fun updateStatus(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseStatus(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(LOCK_SQL).execute(Tuple.of(id)).compose<JsonObject> { rows ->
                val current = rows.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(
                        HealthcareNotFoundException("child referral not found: $id"),
                    )
                val from = current.getString("status")
                if (!canTransition(from, parsed.nextStatus)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("invalid status transition: $from -> ${parsed.nextStatus}"),
                    )
                }
                applyStatusVia(connection, id, parsed, now)
            }
        }
    }

    private fun applyStatusVia(
        client: SqlClient,
        id: String,
        parsed: ParsedStatus,
        now: OffsetDateTime,
    ): Future<JsonObject> {
        // $1 固定为 WHERE id；其余按提供情况动态拼 SET，全部绑定非空值。
        val sets = mutableListOf("status = \$2")
        val values = mutableListOf<Any>(parsed.nextStatus)
        var nextIndex = 3
        parsed.referredAt?.let { sets.add("referred_at = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.outcome?.let { sets.add("outcome = \$$nextIndex"); values.add(it); nextIndex++ }
        parsed.metadata?.let { sets.add("metadata = \$$nextIndex"); values.add(it); nextIndex++ }
        sets.add("updated_at = \$$nextIndex")
        values.add(now)

        val sql = "UPDATE healthcare.child_referrals SET ${sets.joinToString(", ")} WHERE id = \$1"
        val tuple = Tuple.of(id)
        values.forEach { tuple.addValue(it) }
        return client.preparedQuery(sql).execute(tuple)
            .compose { getReferralVia(client, id) }
    }

    // ========================================================================
    //  内部：持久化与校验
    // ========================================================================

    private fun getReferralVia(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(SELECT_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(referralJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child referral not found: $id"))
        }

    private fun pageJson(rows: Iterable<Row>, total: Long): JsonObject =
        JsonObject()
            .put("records", JsonArray(rows.map(Companion::referralJson)))
            .put("meta", JsonObject().put("total", total))

    /**
     * 原生 SQL 校验儿童档案（不引入 jOOQ / `childPatientRow`）。
     *
     * 非 `person_type=儿童` 或 `birth_date` 为空 → [IllegalArgumentException]（路由 400）；
     * 档案不存在 → [HealthcareNotFoundException]（路由 404）。
     */
    private fun loadChild(client: SqlClient, patientId: String): Future<Row> =
        client.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(
                    HealthcareNotFoundException("patient not found: $patientId"),
                )
            if (row.getString("person_type") != ChildHealth.PERSON_TYPE_CHILD) {
                return@compose Future.failedFuture(
                    IllegalArgumentException("patient is not a child (person_type must be 儿童): $patientId"),
                )
            }
            if (row.getLocalDate("birth_date") == null) {
                return@compose Future.failedFuture(
                    IllegalArgumentException("child birth_date is required: $patientId"),
                )
            }
            Future.succeededFuture(row)
        }

    private data class Parsed(
        val patientId: String,
        val sourceType: String,
        val sourceId: String?,
        val problemSummary: String,
        val referralType: String,
        val targetOrg: String?,
        val targetDept: String?,
        val urgency: String,
        val metadata: JsonObject?,
    )

    private data class ParsedStatus(
        val nextStatus: String,
        val referredAt: OffsetDateTime?,
        val outcome: String?,
        val metadata: JsonObject?,
    )

    private fun parseCreate(body: JsonObject): Parsed {
        rejectForbiddenKeys(body, createKeys, "child referral")
        val sourceType = requiredText(body, "source_type")
        if (sourceType !in sourceTypes) {
            throw IllegalArgumentException(
                "invalid source_type: $sourceType (expected ${sourceTypes.joinToString("/")})",
            )
        }
        val problemSummary = requiredText(body, "problem_summary")
        if (problemSummary.length > MAX_PROBLEM_SUMMARY) {
            throw IllegalArgumentException("problem_summary must be at most $MAX_PROBLEM_SUMMARY characters")
        }
        val referralType = requiredText(body, "referral_type")
        if (referralType !in referralTypes) {
            throw IllegalArgumentException(
                "invalid referral_type: $referralType (expected ${referralTypes.joinToString("/")})",
            )
        }
        val urgency = optionalText(body, "urgency") ?: "常规"
        if (urgency !in urgencies) {
            throw IllegalArgumentException(
                "invalid urgency: $urgency (expected ${urgencies.joinToString("/")})",
            )
        }
        return Parsed(
            patientId = requiredText(body, "patient_id"),
            sourceType = sourceType,
            sourceId = optionalText(body, "source_id"),
            problemSummary = problemSummary,
            referralType = referralType,
            targetOrg = optionalText(body, "target_org"),
            targetDept = optionalText(body, "target_dept"),
            urgency = urgency,
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun parseStatus(body: JsonObject): ParsedStatus {
        rejectForbiddenKeys(body, statusKeys, "child referral status")
        val nextStatus = requiredText(body, "status")
        if (nextStatus !in statuses) {
            throw IllegalArgumentException("invalid status: $nextStatus")
        }
        return ParsedStatus(
            nextStatus = nextStatus,
            // 仅转入「已转诊」时设 referred_at；其余流转保留既有值（不触碰列）。
            referredAt = if (nextStatus == "已转诊") referredAtValue(body) else null,
            outcome = optionalText(body, "outcome"),
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun referredAtValue(body: JsonObject): OffsetDateTime {
        val raw = body.getString("referred_at")?.trim()?.takeIf(String::isNotBlank)
        val value = if (raw == null) {
            OffsetDateTime.now()
        } else {
            try {
                OffsetDateTime.parse(raw)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("referred_at must be an ISO-8601 offset date-time")
            }
        }
        if (value.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("referred_at must not be in the future")
        }
        return value
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun optionalText(body: JsonObject, key: String): String? =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)

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
