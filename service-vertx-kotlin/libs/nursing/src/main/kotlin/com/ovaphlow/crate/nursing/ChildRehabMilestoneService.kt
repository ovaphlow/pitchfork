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
 * 儿童康复里程碑服务（054 B3，迁移 V411）。
 *
 * 规则：
 *  1. `milestone_code` 必须是 [ChildRehabMilestone.CATALOG] 收录的 code（否则 400）；
 *     `milestone_name` / `expected_age_months` 由常量表**服务端派生**，客户端提交即忽略。
 *  2. `POST` 为**幂等 upsert**：同一儿童同一里程碑至多一行
 *     （唯一索引 `uq_child_rehab_milestones_patient_code` + `ON CONFLICT`）；
 *     不传 `achieved_at` 即覆盖为「未达成」。
 *  3. 落库 `status` 是**登记态**（未达成/已达成）；四态（未达/临近/已达成/逾期）
 *     由 [ChildRehabMilestone.stateOf] **读时派生**，不落库。
 *  4. `GET /child-rehab-milestone-status/:patient_id` 返回**常量表全量**里程碑的读时状态
 *     （未登记的按未达/临近/逾期判定），并附已登记但不在常量表的自定义 code（不丢数据）。
 *  5. `operator` 取认证主体（042 口径）。
 *  6. 持久化走**原生参数化 SQL**（`$1..$n` + `Tuple`），本文件不引入 jOOQ。
 */
class ChildRehabMilestoneService(private val pool: Pool) {

    companion object {
        /** 业务时区：与 healthcare 侧同口径（读时状态按业务当天算月龄）。 */
        private val businessZone: ZoneId = ZoneId.of("Asia/Shanghai")

        fun today(): LocalDate = LocalDate.now(businessZone)

        private const val PATIENT_SQL =
            "SELECT id, birth_date FROM healthcare.patients WHERE id = \$1"

        private const val UPSERT_SQL =
            "INSERT INTO nursing.nursing_child_rehab_milestones " +
                "(id, patient_id, milestone_code, milestone_name, expected_age_months, achieved_at, " +
                "status, operator, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11) " +
                "ON CONFLICT (patient_id, milestone_code) DO UPDATE SET " +
                "milestone_name = EXCLUDED.milestone_name, " +
                "expected_age_months = EXCLUDED.expected_age_months, " +
                "achieved_at = EXCLUDED.achieved_at, status = EXCLUDED.status, " +
                "operator = EXCLUDED.operator, metadata = EXCLUDED.metadata, " +
                "updated_at = EXCLUDED.updated_at " +
                "RETURNING id"

        private const val SELECT_COLUMNS =
            "m.id, m.patient_id, m.milestone_code, m.milestone_name, m.expected_age_months, " +
                "m.achieved_at, m.status, m.operator, m.metadata, m.created_at, m.updated_at"

        private const val SELECT_FROM = " FROM nursing.nursing_child_rehab_milestones m "

        private const val GET_SQL =
            "SELECT " + SELECT_COLUMNS + SELECT_FROM + "WHERE m.id = \$1"

        private const val COUNT_SQL =
            "SELECT count(*) AS total" + SELECT_FROM

        private const val BY_PATIENT_SQL =
            "SELECT milestone_code, achieved_at FROM nursing.nursing_child_rehab_milestones " +
                "WHERE patient_id = \$1"

        /** 行 → 里程碑登记 JSON。 */
        internal fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getString("id"))
            .put("patient_id", row.getString("patient_id"))
            .put("milestone_code", row.getString("milestone_code"))
            .put("milestone_name", row.getString("milestone_name"))
            .put("expected_age_months", row.getInteger("expected_age_months"))
            .put("achieved_at", row.getLocalDate("achieved_at")?.toString())
            .put("status", row.getString("status"))
            .put("operator", row.getString("operator"))
            .put("metadata", row.getJsonObject("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 登记/更新里程碑达成（幂等 upsert）。
     * 参数非法抛 `IllegalArgumentException`（400）；儿童不存在抛 [NotFoundException]（404）。
     */
    fun create(body: JsonObject, operator: String?): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.trim()
        if (patientId.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        val code = body.getString("milestone_code")?.trim()
        if (code.isNullOrEmpty()) throw IllegalArgumentException("milestone_code is required")
        val definition = ChildRehabMilestone.definitionOf(code)
            ?: throw IllegalArgumentException(
                "unknown milestone_code: $code, must be one of: ${ChildRehabMilestone.CATALOG.map { it.code }}",
            )

        val achievedAt = body.getString("achieved_at")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { parseDate(it, "achieved_at") }
        val metadata = parseMetadata(body.getValue("metadata"))

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(patientId)
        tuple.addString(definition.code)
        tuple.addString(definition.name)
        tuple.addInteger(definition.expectedAgeMonths)
        tuple.addValue(achievedAt)
        tuple.addString(if (achievedAt == null) "未达成" else "已达成")
        tuple.addValue(operator)
        tuple.addValue(metadata)
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return verifyPatient(patientId).compose {
            pool.preparedQuery(UPSERT_SQL).execute(tuple).compose { rows ->
                val savedId = rows.iterator().asSequence().firstOrNull()?.getString("id") ?: id
                get(savedId)
            }
        }
    }

    /** 按 id 取单条；不存在 → [NotFoundException]（404）。 */
    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery(GET_SQL).execute(Tuple.of(id)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("child rehab milestone not found: $id"))
            Future.succeededFuture(toJson(row))
        }

    /** 列表（空结果也返回 `records: []` 与 `total: 0`）。过滤：`patient_id`。 */
    fun list(
        patientId: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any>()
        patientId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions.add("m.patient_id = \$${values.size + 1}")
            values.add(it)
        }

        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val countSql = COUNT_SQL + where
        val listSql = "SELECT " + SELECT_COLUMNS + SELECT_FROM + where +
            " ORDER BY m.expected_age_months NULLS LAST, m.milestone_code " +
            "LIMIT \$${values.size + 1} OFFSET \$${values.size + 2}"

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
     * 读时派生某儿童全部里程碑状态（§5.7 `ChildRehabMilestoneStatus`）。
     * 常量表全量 + 已登记的自定义 code；`state` 由月龄与期望月龄派生。
     */
    fun status(patientId: String?): Future<JsonObject> {
        val id = patientId?.trim()
        if (id.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")

        return loadPatient(id).compose { patientRow ->
            val birthDate = patientRow.getLocalDate("birth_date")
                ?: throw IllegalArgumentException("patient birth_date is required for milestone status")
            val asOf = today()
            val ageMonths = ChildRehabMilestone.ageMonths(birthDate, asOf)

            pool.preparedQuery(BY_PATIENT_SQL).execute(Tuple.of(id)).map { rows ->
                val achievedByCode = linkedMapOf<String, LocalDate?>()
                for (row in rows) {
                    val code = row.getString("milestone_code") ?: continue
                    achievedByCode[code] = row.getLocalDate("achieved_at")
                }

                val items = JsonArray()
                for (definition in ChildRehabMilestone.CATALOG) {
                    val achievedAt = achievedByCode[definition.code]
                    items.add(
                        JsonObject()
                            .put("milestone_code", definition.code)
                            .put("milestone_name", definition.name)
                            .put("expected_age_months", definition.expectedAgeMonths)
                            .put("achieved_at", achievedAt?.toString())
                            .put("state", ChildRehabMilestone.stateOf(definition.expectedAgeMonths, ageMonths, achievedAt != null)),
                    )
                }
                // 常量表之外的已登记 code 也要出现在结果里（不丢数据）
                for ((code, achievedAt) in achievedByCode) {
                    if (ChildRehabMilestone.definitionOf(code) != null) continue
                    items.add(
                        JsonObject()
                            .put("milestone_code", code)
                            .put("milestone_name", code)
                            .put("expected_age_months", null)
                            .put("achieved_at", achievedAt?.toString())
                            .put("state", ChildRehabMilestone.stateOf(null, ageMonths, achievedAt != null)),
                    )
                }

                JsonObject()
                    .put("patient_id", id)
                    .put("age_months", ageMonths)
                    .put("as_of", asOf.toString())
                    .put("items", items)
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun verifyPatient(patientId: String): Future<Unit> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            if (rows.iterator().asSequence().firstOrNull() == null)
                Future.failedFuture(NotFoundException("patient not found: $patientId"))
            else Future.succeededFuture(Unit)
        }

    private fun loadPatient(patientId: String): Future<Row> =
        pool.preparedQuery(PATIENT_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(NotFoundException("patient not found: $patientId"))
            Future.succeededFuture(row)
        }

    private fun parseDate(value: String, field: String): LocalDate = try {
        LocalDate.parse(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("$field must be a valid ISO date (yyyy-MM-dd)")
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
