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
import java.time.ZoneId

/**
 * 儿童发育筛查结果服务（053 A2，V531；053 A3 增结论判读与体检联动）。
 *
 * 职责：把 A1 的纯逻辑评分引擎（[DevelopmentalScoring]）接上持久化与查询接口。
 * **不复制/改写引擎逻辑**，一律调用 `DevelopmentalScoring.score(...)`。
 *
 * 业务规则（服务端强制）：
 *  1. 只服务 `person_type=儿童` 且 `birth_date` 非空的患者（本服务内原生 SQL 自查，不使用
 *     需要 `DSLContext` 的 `childPatientRow`）。
 *  2. `administered_at` 缺省当前时间，不得晚于现在；`age_months` 由服务端按 `birth_date`
 *     与 `administered_at` 的**业务日**（Asia/Shanghai）用 [ChildHealth.ageMonths] 派生。
 *  3. 写白名单：`patient_id` / `scale_code` / `administered_at` / `item_scores` / `metadata`；
 *     客户端不得提交 `scale_version` / `age_months` / `domain_scores` / `total_score` /
 *     `classification` / `conclusion` / `operator`（派生或另接口），多余键一律 400。
 *  4. `scale_version` / `age_months` / `domain_scores` / `total_score` / `classification`
 *     全部取 A1 引擎结果；引擎抛出的 [IllegalArgumentException]（未知量表 / 月龄越界 /
 *     缺项 / 非法分值）原样冒泡为 400。
 *  5. `item_scores` 落引擎回显的 `answeredItems`（按适龄顺序）；`metadata` 额外落
 *     `domain_max_scores` / `triggered_items` 供读接口回显（不新增列）。
 *  6. `operator` 一律取认证主体（042 范式）。
 *  7. 053 新代码一律原生参数化 SQL（`$1..$n` + `Tuple`），**本文件无任何 jOOQ 引用**。
 */
class ChildScreeningService(private val pool: Pool) {

    companion object {
        /** 业务时区（与 052 儿保链一致）。 */
        private val businessZone = ZoneId.of("Asia/Shanghai")

        /** 创建写白名单；派生字段与 `operator` 不在其中（客户端提交即 400）。 */
        private val createKeys = setOf(
            "patient_id", "scale_code", "administered_at", "item_scores", "metadata",
        )

        /** 判读写白名单（053 A3）：`conclusion` 必填，`checkup_id` 可选；多余键 400。 */
        private val concludeKeys = setOf("conclusion", "checkup_id")

        private const val MAX_CONCLUSION = 2000
        private const val MAX_CHECKUP_ID = 32

        /** 可转诊判读白名单（053 A3）：只有「可疑/异常」提示可一键转诊。 */
        val referableClassifications: Set<String> = linkedSetOf("可疑", "异常")

        /** 纯规则：判读是否可建转诊（null /「正常」→ false）。 */
        fun canRefer(classification: String?): Boolean = classification in referableClassifications

        /** 儿童档案原生查询（不使用 jOOQ / `childPatientRow`）。 */
        private const val PATIENT_SQL =
            "SELECT id, name, birth_date, person_type FROM healthcare.patients WHERE id = \$1"

        private const val SELECT_COLUMNS =
            "s.id, s.patient_id, p.name AS patient_name, s.scale_code, s.scale_version, " +
                "s.age_months, s.administered_at, s.item_scores, s.domain_scores, s.total_score, " +
                "s.classification, s.conclusion, s.checkup_id, s.operator, s.metadata, " +
                "s.created_at, s.updated_at"

        private const val BASE_FROM =
            " FROM healthcare.developmental_screenings s " +
                "JOIN healthcare.patients p ON p.id = s.patient_id "

        private const val SELECT_BY_ID_SQL = "SELECT $SELECT_COLUMNS$BASE_FROM WHERE s.id = \$1"

        private const val LIST_SQL =
            "SELECT $SELECT_COLUMNS$BASE_FROM WHERE s.patient_id = \$1 " +
                "ORDER BY s.administered_at DESC, s.created_at DESC LIMIT \$2 OFFSET \$3"

        private const val COUNT_SQL =
            "SELECT count(*) AS total FROM healthcare.developmental_screenings WHERE patient_id = \$1"

        private const val INSERT_SQL =
            "INSERT INTO healthcare.developmental_screenings " +
                "(id, patient_id, scale_code, scale_version, age_months, administered_at, " +
                "item_scores, domain_scores, total_score, classification, operator, metadata, " +
                "created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11, \$12, \$13, \$14)"

        /** 判读事务内按 id 读取筛查行并加行锁（存在性 + `patient_id` 归属校验同一事务）。 */
        private const val LOCK_SCREENING_SQL =
            "SELECT patient_id FROM healthcare.developmental_screenings WHERE id = \$1 FOR UPDATE"

        /** 原生 SQL 校验关联体检记录存在及其归属儿童（不引入 jOOQ）。 */
        private const val CHECKUP_PATIENT_SQL =
            "SELECT patient_id FROM healthcare.child_health_checkups WHERE id = \$1"

        /** 行 → 单条筛查 JSON（字段缺失给 `{}` / `[]`，不抛）。 */
        internal fun screeningJson(row: Row): JsonObject {
            val itemScores = row.getJsonObject("item_scores") ?: JsonObject()
            val domainScores = row.getJsonObject("domain_scores") ?: JsonObject()
            val metadata = row.getJsonObject("metadata")
            val domainMaxScores = metadata?.getJsonObject("domain_max_scores") ?: JsonObject()
            val triggeredItems = metadata?.getJsonArray("triggered_items") ?: JsonArray()
            val maxTotalScore = domainMaxScores.fieldNames().sumOf { key ->
                (domainMaxScores.getValue(key) as? Number)?.toInt() ?: 0
            }
            return JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("scale_code", row.getString("scale_code"))
                .put("scale_version", row.getString("scale_version"))
                .put("age_months", row.getInteger("age_months"))
                .put("administered_at", row.getOffsetDateTime("administered_at")?.toString())
                .put("item_scores", itemScores)
                .put("domain_scores", domainScores)
                .put("domain_max_scores", domainMaxScores)
                .put("total_score", row.getInteger("total_score"))
                .put("max_total_score", maxTotalScore)
                .put("classification", row.getString("classification"))
                .put("triggered_items", triggeredItems)
                .put("conclusion", row.getString("conclusion"))
                .put("checkup_id", row.getString("checkup_id"))
                .put("operator", row.getString("operator"))
                .put("metadata", metadata)
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
        }

        private fun jsonObjectOfInts(values: Map<String, Int>): JsonObject {
            val obj = JsonObject()
            values.forEach { (key, value) -> obj.put(key, value) }
            return obj
        }
    }

    // ========================================================================
    //  创建
    // ========================================================================

    /**
     * 执行一次筛查：校验儿童 → 派生月龄 → 调 A1 引擎计分 → 落库 → 回读单条。
     *
     * @throws IllegalArgumentException 白名单/形状校验失败，或引擎判定量表未知 / 月龄越界 /
     *   缺项 / 非法分值（经 `Future` 失败回传，由路由映射 400）。
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
            loadChild(connection, parsed.patientId).compose<JsonObject> { patient ->
                val birthDate = patient.getLocalDate("birth_date")
                val businessDate = parsed.administeredAt
                    .atZoneSameInstant(businessZone)
                    .toLocalDate()
                val ageMonths = try {
                    ChildHealth.ageMonths(birthDate, businessDate)
                } catch (error: IllegalArgumentException) {
                    return@compose Future.failedFuture(error)
                }

                val scored = try {
                    DevelopmentalScoring.score(parsed.scaleCode, ageMonths, parsed.answers)
                } catch (error: IllegalArgumentException) {
                    // 未知量表 / 月龄越界 / 缺项 / 非法分值：原样冒泡（路由 400）。
                    return@compose Future.failedFuture(error)
                }

                val metadata = parsed.metadata?.copy() ?: JsonObject()
                metadata.put("domain_max_scores", jsonObjectOfInts(scored.domainMaxScores))
                val triggered = JsonArray()
                scored.triggeredItems.forEach { triggered.add(it) }
                metadata.put("triggered_items", triggered)

                val tuple = Tuple.tuple()
                tuple.addString(id)
                tuple.addString(parsed.patientId)
                tuple.addString(scored.scaleCode)
                tuple.addString(scored.scaleVersion)
                tuple.addInteger(ageMonths)
                tuple.addOffsetDateTime(parsed.administeredAt)
                tuple.addValue(jsonObjectOfInts(scored.answeredItems))
                tuple.addValue(jsonObjectOfInts(scored.domainScores))
                tuple.addInteger(scored.totalScore)
                tuple.addString(scored.classification)
                tuple.addString(operator)
                tuple.addValue(metadata)
                tuple.addOffsetDateTime(now)
                tuple.addOffsetDateTime(now)

                connection.preparedQuery(INSERT_SQL).execute(tuple)
                    .compose { getScreeningVia(connection, id) }
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    /**
     * 按儿童列出筛查记录（`administered_at DESC, created_at DESC`）。
     *
     * `patient_id` 必填；空列表也返回 `records: []` 与 `total: 0`。
     */
    fun list(patientId: String?, limit: Int, offset: Int): Future<JsonObject> {
        val patient = patientId?.trim()?.takeIf(String::isNotBlank)
            ?: return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        return pool.preparedQuery(COUNT_SQL).execute(Tuple.of(patient)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(LIST_SQL).execute(Tuple.of(patient, limit, offset)).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::screeningJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 按 id 取单条；不存在 → [HealthcareNotFoundException]（路由 404）。 */
    fun get(id: String): Future<JsonObject> = getScreeningVia(pool, id)

    // ========================================================================
    //  结论判读（053 A3）
    // ========================================================================

    /**
     * 医生对筛查结果写结论判读（053 A3，**覆盖式**）。
     *
     * 入参白名单：`conclusion`（必填，≤2000 字）、`checkup_id`（可选 ≤32 字）；多余键 400。
     * 选填 `checkup_id` 时，原生 SQL 校验该体检记录存在且与本筛查同属一名儿童，否则 400。
     * 更新 `conclusion` / `checkup_id`（未提供则保留原值）/ `updated_at`，回读单条返回。
     * 判读「状态」即 `conclusion` 是否已写，允许覆盖式重复判读；不引入未定义列。
     *
     * 「可疑/异常」（见 [canRefer]）可由前端携带本筛查 `id` 调 B2 `POST /child-referrals`
     * （`source_type="筛查"`、`source_id=screening.id`）完成「一键转诊」；本接口不直接建转诊，
     * 避免与转诊服务耦合。`operator` 为冻结签名的一部分（认证主体 / `clinical:write`），
     * 筛查行的 `operator` 列沿用 A2 录入时的值，本接口不覆写。
     *
     * @throws IllegalArgumentException 白名单 / 长度 / 体检归属校验失败（经 `Future` 失败回传，路由 400）。
     * @throws HealthcareNotFoundException 筛查记录不存在（路由 404）。
     */
    fun conclude(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val parsed = try {
            parseConclude(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            connection.preparedQuery(LOCK_SCREENING_SQL).execute(Tuple.of(id)).compose<JsonObject> { rows ->
                val screening = rows.iterator().asSequence().firstOrNull()
                    ?: return@compose Future.failedFuture(
                        HealthcareNotFoundException("developmental screening not found: $id"),
                    )
                val patientId = screening.getString("patient_id")
                val checkupId = parsed.checkupId
                if (checkupId == null) {
                    applyConclusionVia(connection, id, parsed, now)
                } else {
                    connection.preparedQuery(CHECKUP_PATIENT_SQL).execute(Tuple.of(checkupId))
                        .compose<JsonObject> { checkupRows ->
                            val owner = checkupRows.iterator().asSequence().firstOrNull()
                                ?.getString("patient_id")
                            if (owner != patientId) {
                                return@compose Future.failedFuture(
                                    IllegalArgumentException("checkup_id does not belong to the same child"),
                                )
                            }
                            applyConclusionVia(connection, id, parsed, now)
                        }
                }
            }
        }
    }

    private fun applyConclusionVia(
        client: SqlClient,
        id: String,
        parsed: ParsedConclusion,
        now: OffsetDateTime,
    ): Future<JsonObject> {
        // $1 固定为 conclusion；checkup_id 仅在提供时入 SET；末尾 updated_at 与 WHERE id。
        val sets = mutableListOf("conclusion = \$1")
        val values = mutableListOf<Any>(parsed.conclusion)
        var nextIndex = 2
        parsed.checkupId?.let { sets.add("checkup_id = \$$nextIndex"); values.add(it); nextIndex++ }
        sets.add("updated_at = \$$nextIndex")
        values.add(now)
        nextIndex++

        val sql = "UPDATE healthcare.developmental_screenings SET ${sets.joinToString(", ")} " +
            "WHERE id = \$$nextIndex"
        val tuple = Tuple.tuple()
        values.forEach { tuple.addValue(it) }
        tuple.addString(id)
        return client.preparedQuery(sql).execute(tuple)
            .compose { getScreeningVia(client, id) }
    }

    // ========================================================================
    //  内部：持久化与校验
    // ========================================================================

    private fun getScreeningVia(client: SqlClient, id: String): Future<JsonObject> =
        client.preparedQuery(SELECT_BY_ID_SQL).execute(Tuple.of(id)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(screeningJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("developmental screening not found: $id"))
        }

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
        val scaleCode: String,
        val administeredAt: OffsetDateTime,
        val answers: Map<String, Int>,
        val metadata: JsonObject?,
    )

    private data class ParsedConclusion(
        val conclusion: String,
        val checkupId: String?,
    )

    private fun parseCreate(body: JsonObject): Parsed {
        rejectForbiddenKeys(body, createKeys, "developmental screening")
        return Parsed(
            patientId = requiredText(body, "patient_id"),
            scaleCode = requiredText(body, "scale_code"),
            administeredAt = administeredAtValue(body),
            answers = answersValue(body),
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun parseConclude(body: JsonObject): ParsedConclusion {
        rejectForbiddenKeys(body, concludeKeys, "developmental screening conclusion")
        val conclusion = requiredText(body, "conclusion")
        if (conclusion.length > MAX_CONCLUSION) {
            throw IllegalArgumentException("conclusion must not exceed $MAX_CONCLUSION characters")
        }
        return ParsedConclusion(
            conclusion = conclusion,
            checkupId = optionalText(body, "checkup_id", MAX_CHECKUP_ID),
        )
    }

    private fun administeredAtValue(body: JsonObject): OffsetDateTime {
        val raw = body.getString("administered_at")?.trim()?.takeIf(String::isNotBlank)
        val value = if (raw == null) {
            OffsetDateTime.now()
        } else {
            try {
                OffsetDateTime.parse(raw)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("administered_at must be an ISO-8601 offset date-time")
            }
        }
        if (value.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("administered_at must not be in the future")
        }
        return value
    }

    private fun answersValue(body: JsonObject): Map<String, Int> {
        val value = body.getValue("item_scores")
            ?: throw IllegalArgumentException("item_scores is required")
        val obj = value as? JsonObject
            ?: throw IllegalArgumentException("item_scores must be a JSON object")
        val answers = LinkedHashMap<String, Int>()
        obj.fieldNames().forEach { key ->
            val number = obj.getValue(key) as? Number
                ?: throw IllegalArgumentException("item_scores.$key must be an integer")
            val int = number.toInt()
            if (number.toDouble() != int.toDouble()) {
                throw IllegalArgumentException("item_scores.$key must be an integer")
            }
            answers[key] = int
        }
        return answers
    }

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun optionalText(body: JsonObject, key: String, maxLength: Int): String? {
        val value = body.getString(key)?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (value.length > maxLength) {
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
