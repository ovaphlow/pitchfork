package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.isUniqueViolation
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import java.time.OffsetDateTime

/**
 * 长者饮食档案（FR-1）：餐食类型/忌口过敏/份量偏好/备注。
 * 建档时校验活动 ELDERLY_CARE 入住（入住自动生效）；
 * 离院后因配餐口径只纳入在院入住而自然停用，亦可显式停用/启用。
 *
 * 持久化一律原生参数化 SQL（`$1..$n` + `Tuple`），无 jOOQ。
 */
class DietProfileService(
    private val pool: Pool,
) {

    companion object {
        /** 最小启用档案缺省餐食类型。 */
        const val DEFAULT_MEAL_TYPE = "普食"

        private const val ENCOUNTER_SQL =
            "SELECT encounter_type, status, patient_id AS enc_patient_id " +
                "FROM healthcare.encounters WHERE id = \$1"

        private const val ACTIVE_PROFILE_SQL =
            "SELECT id FROM dining.dining_diet_profiles " +
                "WHERE patient_id = \$1 AND status = '启用' LIMIT 1"

        private const val UPDATE_ALLERGENS_SQL =
            "UPDATE dining.dining_diet_profiles SET allergies = \$1, updated_at = \$2 WHERE id = \$3"

        private const val INSERT_SQL =
            "INSERT INTO dining.dining_diet_profiles " +
                "(id, patient_id, encounter_id, meal_type, allergies, portion_preference, remark, " +
                "status, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

        private const val STATUS_SQL =
            "UPDATE dining.dining_diet_profiles SET status = \$1, updated_at = \$2 WHERE id = \$3"

        private const val DELETE_SQL =
            "DELETE FROM dining.dining_diet_profiles WHERE id = \$1"

        /** 联查列清单（`patient_name` 取自 patients，`encounter_status` 取自 encounters）。 */
        private const val JOINED_COLUMNS =
            "p.id AS id, p.patient_id AS patient_id, pa.name AS patient_name, " +
                "p.encounter_id AS encounter_id, p.meal_type AS meal_type, p.allergies AS allergies, " +
                "p.portion_preference AS portion_preference, p.remark AS remark, p.status AS status, " +
                "p.metadata AS metadata, p.created_at AS created_at, p.updated_at AS updated_at, " +
                "e.status AS encounter_status"

        /**
         * 054 A4b：`encounters` 必须 LEFT JOIN——儿童档案 `encounter_id IS NULL`（无入住），
         * INNER JOIN 会把它们整行丢掉（upsert 后 get()/list() 查不到）。`patients` 保持 INNER
         * （`patient_id` NOT NULL）。
         */
        private const val JOINED_FROM =
            " FROM dining.dining_diet_profiles p " +
                "LEFT JOIN healthcare.encounters e ON e.id = p.encounter_id " +
                "JOIN healthcare.patients pa ON pa.id = p.patient_id"

        fun toJson(row: Row): JsonObject = JsonObject()
            .put("id", row.getValue("id")?.toString())
            .put("patient_id", row.getValue("patient_id")?.toString())
            .put("patient_name", row.getValue("patient_name")?.toString())
            .put("encounter_id", row.getValue("encounter_id")?.toString())
            .put("meal_type", row.getValue("meal_type")?.toString())
            .put("allergies", row.getValue("allergies") as? JsonArray ?: JsonArray())
            .put("portion_preference", row.getValue("portion_preference")?.toString())
            .put("remark", row.getValue("remark")?.toString())
            .put("status", row.getValue("status")?.toString())
            .put("metadata", row.getValue("metadata") as? JsonObject)
            .put("encounter_status", row.getValue("encounter_status")?.toString())
            .put("created_at", row.getValue("created_at")?.toString())
            .put("updated_at", row.getValue("updated_at")?.toString())
    }

    /**
     * 建档。必须给出 ACTIVE 的 ELDERLY_CARE 入住且与 `patient_id` 一致。
     *
     * 该规则依赖跨表状态，无法用 `CHECK` 表达，故只能在此强制。
     */
    fun create(body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")
        if (patientId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        val encounterId = body.getString("encounter_id")

        if (encounterId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("encounter_id is required"))

        val mealType = body.getString("meal_type")
        if (mealType.isNullOrBlank() || mealType !in DiningConstants.MEAL_TYPES)
            return Future.failedFuture(IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}"))

        val allergies = allergiesOf(body)
        val portion = body.getString("portion_preference")
        if (!portion.isNullOrBlank() && portion !in DiningConstants.PORTION_PREFERENCES)
            return Future.failedFuture(IllegalArgumentException("invalid portion_preference, must be one of: ${DiningConstants.PORTION_PREFERENCES}"))

        // 入住自动生效：仅允许为活动 ELDERLY_CARE 入住建档
        return validateElderlyEncounter(encounterId, patientId).compose {
            insertProfile(
                patientId = patientId,
                encounterId = encounterId,
                mealType = mealType,
                allergies = allergies,
                portion = portion,
                body = body,
            )
        }
    }

    /** 入住校验（既有口径，逐字保留错误文案）。 */
    private fun validateElderlyEncounter(encounterId: String, patientId: String): Future<Unit> {
        return pool.preparedQuery(ENCOUNTER_SQL).execute(Tuple.of(encounterId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(IllegalArgumentException("encounter not found: $encounterId"))
            if (row.getString("encounter_type") != "ELDERLY_CARE")
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            if (row.getString("status") != "ACTIVE")
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not active, 仅支持为在院入住建档"))
            if (row.getString("enc_patient_id") != patientId)
                return@compose Future.failedFuture(IllegalArgumentException("patient_id does not match the encounter"))
            Future.succeededFuture(Unit)
        }
    }

    private fun insertProfile(
        patientId: String,
        encounterId: String?,
        mealType: String,
        allergies: JsonArray,
        portion: String?,
        body: JsonObject,
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.preparedQuery(INSERT_SQL).execute(
            Tuple.of(
                id, patientId, encounterId, mealType, allergies,
                portion.takeIf { !it.isNullOrBlank() },
                body.getString("remark"), "启用",
                if (body.containsKey("metadata")) body.getJsonObject("metadata") else null,
                now, now,
            )
        ).compose { result ->
            if (result.rowCount() == 0)
                return@compose Future.failedFuture(IllegalStateException("insert returned no row"))
            get(id)
        }.recover { error ->
            // 命中部分唯一索引（同一长者已有启用档案）→ 409
            if (isUniqueViolation(error, setOf("uq_dining_diet_profiles_active_patient")))
                Future.failedFuture(DiningConflictException("该长者已有启用的饮食档案"))
            else Future.failedFuture(error)
        }
    }

    /**
     * 按 patient 维度**过敏原标记**（`PUT /diet-profiles/by-patient/:patient_id/allergens`）。
     *
     * 只覆盖更新其既有启用档案的 `allergies`；没有启用档案 → 400（无入住无法建档）。
     * 复用 V600 既有列 `dining_diet_profiles.allergies`，不新建过敏主数据表。
     */
    fun upsertAllergensByPatient(patientId: String?, body: JsonObject): Future<JsonObject> {
        val id = patientId?.trim()
        if (id.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")
        if (!body.containsKey("allergies"))
            throw IllegalArgumentException("allergies is required")
        val allergies = allergiesOf(body)
        return updateExistingProfileAllergens(id, allergies)
    }

    private fun updateExistingProfileAllergens(patientId: String, allergies: JsonArray): Future<JsonObject> {
        return pool.preparedQuery(ACTIVE_PROFILE_SQL).execute(Tuple.of(patientId)).compose { rows ->
            val profileId = rows.iterator().asSequence().firstOrNull()?.getString("id")
                ?: throw IllegalArgumentException("非儿童必须先建立饮食档案（需在院入住）")
            val now = OffsetDateTime.now()
            pool.preparedQuery(UPDATE_ALLERGENS_SQL)
                .execute(Tuple.of(allergies, now, profileId))
                .compose { get(profileId) }
        }
    }

    fun list(
        patientId: String? = null,
        encounterId: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        /** 追加一个绑定值并返回其占位符。 */
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

        patientId?.takeIf(String::isNotBlank)?.let { where.add("p.patient_id = ${bind(it)}") }
        encounterId?.takeIf(String::isNotBlank)?.let { where.add("p.encounter_id = ${bind(it)}") }
        status?.takeIf(String::isNotBlank)?.let { where.add("p.status = ${bind(it)}") }

        val whereClause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val countSql = "SELECT count(*) AS total$JOINED_FROM$whereClause"
        val countArgs = args.toList()
        val dataSql =
            "SELECT $JOINED_COLUMNS$JOINED_FROM$whereClause " +
                "ORDER BY p.created_at DESC LIMIT ${bind(limit)} OFFSET ${bind(offset)}"
        val dataArgs = args.toList()

        return pool.preparedQuery(countSql).execute(Tuple.from(countArgs)).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            pool.preparedQuery(dataSql).execute(Tuple.from(dataArgs)).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> =
        pool.preparedQuery("SELECT $JOINED_COLUMNS$JOINED_FROM WHERE p.id = \$1")
            .execute(Tuple.of(id))
            .compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row == null)
                    Future.failedFuture(DiningNotFoundException("diet profile not found: $id"))
                else Future.succeededFuture(toJson(row))
            }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap {
            val sets = mutableListOf("updated_at = \$1")
            val args = mutableListOf<Any?>(OffsetDateTime.now())

            fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }

            if (body.containsKey("meal_type")) {
                val mealType = body.getString("meal_type")
                if (mealType.isNullOrBlank() || mealType !in DiningConstants.MEAL_TYPES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}"))
                sets.add("meal_type = ${bind(mealType)}")
            }
            if (body.containsKey("allergies"))
                sets.add("allergies = ${bind(allergiesOf(body))}")
            if (body.containsKey("portion_preference")) {
                val portion = body.getString("portion_preference")
                if (!portion.isNullOrBlank() && portion !in DiningConstants.PORTION_PREFERENCES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid portion_preference, must be one of: ${DiningConstants.PORTION_PREFERENCES}"))
                sets.add("portion_preference = ${bind(portion)}")
            }
            if (body.containsKey("remark"))
                sets.add("remark = ${bind(body.getString("remark"))}")
            if (body.containsKey("metadata"))
                sets.add("metadata = ${bind(body.getJsonObject("metadata"))}")

            val sql = "UPDATE dining.dining_diet_profiles SET ${sets.joinToString(", ")} WHERE id = ${bind(id)}"
            pool.preparedQuery(sql).execute(Tuple.from(args)).flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        if (status.isBlank() || status !in DiningConstants.ENABLE_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.ENABLE_STATUSES}"))
        return get(id).flatMap { existing ->
            pool.preparedQuery(STATUS_SQL).execute(Tuple.of(status, OffsetDateTime.now(), id))
                .compose { result ->
                    if (result.rowCount() == 0)
                        return@compose Future.failedFuture(DiningNotFoundException("diet profile not found: $id"))
                    get(id)
                }.recover { error ->
                    // 重新启用时命中部分唯一索引（同一长者已有启用档案）→ 409
                    if (isUniqueViolation(error, setOf("uq_dining_diet_profiles_active_patient")))
                        Future.failedFuture(DiningConflictException("该长者已有启用的饮食档案"))
                    else Future.failedFuture(error)
                }
        }
    }

    fun delete(id: String): Future<Void?> {
        return get(id).flatMap {
            pool.preparedQuery(DELETE_SQL).execute(Tuple.of(id)).map<Void?> { null }
        }
    }

    /**
     * 054 A4：过敏原标记**覆盖式**写入（`PUT /diet-profiles/:id/allergens`）。
     *
     * 复用 V600 既有列 `dining_diet_profiles.allergies`（字符串数组口径），
     * **不新建过敏主数据表**（Lead 裁定 L2 / §11.2 Q7）；临床主数据 `patient_allergies`(V535)
     * 落地后由独立对齐任务映射，本方法不改其形状。
     * `allergies` 键缺失 → 400（覆盖式写入必须显式给出，`null` 表示清空）。
     */
    fun updateAllergens(id: String, body: JsonObject): Future<JsonObject> {
        if (!body.containsKey("allergies"))
            throw IllegalArgumentException("allergies is required")
        val allergies = allergiesOf(body)
        return get(id).flatMap {
            pool.preparedQuery(
                "UPDATE dining.dining_diet_profiles SET allergies = \$1, updated_at = \$2 WHERE id = \$3"
            ).execute(Tuple.of(allergies, OffsetDateTime.now(), id))
                .compose { result ->
                    if (result.rowCount() == 0)
                        return@compose Future.failedFuture(DiningNotFoundException("diet profile not found: $id"))
                    get(id)
                }
        }
    }

    private fun stringArray(value: JsonArray?, field: String): JsonArray {
        if (value == null) return JsonArray()
        if (!value.all { it is String })
            throw IllegalArgumentException("$field must be an array of strings")
        return JsonArray(value.map { it.toString().trim() }.filter { it.isNotEmpty() })
    }

    /**
     * `allergies` 取值（051 真实根因：直接调接口的客户端把该字段传成 JSON 字符串，
     * `body.getJsonArray` 会抛 ClassCastException → 被路由兜成 500）。
     *
     * 接收三种形态：缺省/`null` → 空数组；数组 → 逐项校验字符串；
     * 逗号（半/全角）分隔的字符串 → 拆成数组（与膳食/长者表单的输入口径一致）。
     * 其它类型给出中文 400，绝不因类型不符退化成 500。
     */
    private fun allergiesOf(body: JsonObject): JsonArray {
        if (!body.containsKey("allergies")) return JsonArray()
        return when (val raw = body.getValue("allergies")) {
            null -> JsonArray()
            is JsonArray -> stringArray(raw, "allergies")
            is String -> JsonArray(raw.split(',', '，').map(String::trim).filter(String::isNotEmpty))
            else -> throw IllegalArgumentException("allergies 必须是字符串数组，或逗号分隔的字符串")
        }
    }
}
