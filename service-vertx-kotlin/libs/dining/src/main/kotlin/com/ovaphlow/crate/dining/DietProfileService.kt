package com.ovaphlow.crate.dining

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.isUniqueViolation
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import java.time.OffsetDateTime

/**
 * 长者饮食档案（FR-1）：餐食类型/忌口过敏/份量偏好/备注。
 * 建档时校验活动 ELDERLY_CARE 入住（入住自动生效）；
 * 离院后因配餐口径只纳入在院入住而自然停用，亦可显式停用/启用。
 */
class DietProfileService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val t = DSL.table(DSL.name("dining", "dining_diet_profiles"))
    private val cId = DSL.field("id", String::class.java)
    private val cPatientId = DSL.field("patient_id", String::class.java)
    private val cEncounterId = DSL.field("encounter_id", String::class.java)
    private val cMealType = DSL.field("meal_type", String::class.java)
    private val cAllergies = DSL.field("allergies", JSONB::class.java)
    private val cPortionPreference = DSL.field("portion_preference", String::class.java)
    private val cRemark = DSL.field("remark", String::class.java)
    private val cStatus = DSL.field("status", String::class.java)
    private val cMetadata = DSL.field("metadata", JSONB::class.java)
    private val cCreatedAt = DSL.field("created_at", OffsetDateTime::class.java)
    private val cUpdatedAt = DSL.field("updated_at", OffsetDateTime::class.java)

    private val encounters = DSL.table(DSL.name("healthcare", "encounters"))
    private val cEncounterType = DSL.field("encounter_type", String::class.java)
    private val cEncounterStatus = DSL.field("status", String::class.java)
    private val patients = DSL.table(DSL.name("healthcare", "patients"))

    companion object {
        /** 儿童 `person_type`（052/053 口径）。 */
        const val CHILD_PERSON_TYPE = "儿童"

        /** 最小启用档案缺省餐食类型。 */
        const val DEFAULT_MEAL_TYPE = "普食"

        private const val PERSON_TYPE_SQL =
            "SELECT id, person_type FROM healthcare.patients WHERE id = \$1"

        private const val ACTIVE_PROFILE_SQL =
            "SELECT id FROM dining.dining_diet_profiles " +
                "WHERE patient_id = \$1 AND status = '启用' LIMIT 1"

        private const val UPDATE_ALLERGENS_SQL =
            "UPDATE dining.dining_diet_profiles SET allergies = \$1, updated_at = \$2 WHERE id = \$3"

        /**
         * 054 A4b：儿童按 patient 维度 upsert 最小启用档案（`encounter_id = NULL`）。
         * `ON CONFLICT` 的推断谓词必须与部分唯一索引
         * `uq_dining_diet_profiles_active_patient (patient_id) WHERE status='启用'` 一致。
         */
        private const val UPSERT_CHILD_PROFILE_SQL =
            "INSERT INTO dining.dining_diet_profiles " +
                "(id, patient_id, encounter_id, meal_type, allergies, portion_preference, remark, " +
                "status, metadata, created_at, updated_at) " +
                "VALUES (\$1, \$2, NULL, \$3, \$4, \$5, \$6, '启用', \$7, \$8, \$9) " +
                "ON CONFLICT (patient_id) WHERE status = '启用' DO UPDATE SET " +
                "allergies = EXCLUDED.allergies, updated_at = EXCLUDED.updated_at " +
                "RETURNING id"

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
     * 建档。054 A4b：按 `healthcare.patients.person_type` 分流——
     * **儿童（person_type=儿童）允许 `encounter_id` 为空**（儿保儿童无入住周期，
     * 落 NULL；V603 已放开该列 NOT NULL）；**非儿童保持既有校验，行为完全不变**
     * （必须给出 ACTIVE 的 ELDERLY_CARE 入住且与 patient_id 一致）。
     *
     * 「非儿童必须有 ACTIVE 的 ELDERLY_CARE 入住」是**服务层规则**：它依赖跨表状态，
     * 无法用 `CHECK` 表达，故只能在此强制（V603 注释同口径）。
     */
    fun create(body: JsonObject): Future<JsonObject> {
        val patientId = body.getString("patient_id")
        if (patientId.isNullOrBlank())
            return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        val encounterId = body.getString("encounter_id")

        return loadPersonType(patientId).compose { personType ->
            val childWithoutEncounter = personType == CHILD_PERSON_TYPE && encounterId.isNullOrBlank()
            if (!childWithoutEncounter && encounterId.isNullOrBlank())
                return@compose Future.failedFuture(IllegalArgumentException("encounter_id is required"))

            val mealType = body.getString("meal_type")
            if (mealType.isNullOrBlank() || mealType !in DiningConstants.MEAL_TYPES)
                return@compose Future.failedFuture(IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}"))

            val allergies = allergiesOf(body)
            val portion = body.getString("portion_preference")
            if (!portion.isNullOrBlank() && portion !in DiningConstants.PORTION_PREFERENCES)
                return@compose Future.failedFuture(IllegalArgumentException("invalid portion_preference, must be one of: ${DiningConstants.PORTION_PREFERENCES}"))

            val validated = if (childWithoutEncounter) {
                Future.succeededFuture(Unit)
            } else {
                // 入住自动生效：仅允许为活动 ELDERLY_CARE 入住建档
                validateElderlyEncounter(requireNotNull(encounterId), patientId)
            }

            validated.compose {
                insertProfile(
                    patientId = patientId,
                    encounterId = if (childWithoutEncounter) null else encounterId,
                    mealType = mealType,
                    allergies = allergies,
                    portion = portion,
                    body = body,
                )
            }
        }
    }

    /** 入住校验（既有口径，逐字保留错误文案）。 */
    private fun validateElderlyEncounter(encounterId: String, patientId: String): Future<Unit> {
        val encounterQuery = ctx.select(
            cEncounterType,
            cEncounterStatus,
            DSL.field("patient_id", String::class.java).`as`("enc_patient_id"),
        ).from(encounters).where(DSL.field("id").eq(encounterId))

        return execute(pool, encounterQuery).compose { rows ->
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
        val query = ctx.insertInto(t)
            .set(cId, id)
            .set(cPatientId, patientId)
            .set(cEncounterId, encounterId)
            .set(cMealType, mealType)
            .set(cAllergies, JSONB.valueOf(allergies.encode()))
            .set(cPortionPreference, portion.takeIf { !it.isNullOrBlank() })
            .set(cRemark, body.getString("remark"))
            .set(cStatus, "启用")
            .set(cMetadata, body.containsKey("metadata").let { if (it) JSONB.valueOf(body.getJsonObject("metadata").encode()) else null })
            .set(cCreatedAt, now)
            .set(cUpdatedAt, now)

        return execute(pool, query).compose { result ->
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

    /** 读取 `person_type`（054 A4b，原生参数化 SQL）；患者不存在返回 `null`。 */
    private fun loadPersonType(patientId: String): Future<String?> =
        pool.preparedQuery(PERSON_TYPE_SQL).execute(Tuple.of(patientId)).map { rows ->
            rows.iterator().asSequence().firstOrNull()?.getString("person_type")
        }

    /**
     * 054 A4b：按 patient 维度**过敏原标记**（`PUT /diet-profiles/by-patient/:patient_id/allergens`）。
     *
     * - 儿童：**upsert 最小启用档案**（`encounter_id = NULL`，`meal_type` 缺省「普食」）
     *   并**覆盖式**写 `allergies`；同儿童已有启用档案时由
     *   `ON CONFLICT (patient_id) WHERE status='启用'` 只更新过敏原与 `updated_at`。
     * - 非儿童：**不新建档案**（无入住无法建档），仅覆盖更新其既有启用档案的过敏原；
     *   没有启用档案 → 400。
     * - 复用 V600 既有列 `dining_diet_profiles.allergies`，**不新建过敏主数据表**（L2 裁定）。
     */
    fun upsertAllergensByPatient(patientId: String?, body: JsonObject): Future<JsonObject> {
        val id = patientId?.trim()
        if (id.isNullOrEmpty()) throw IllegalArgumentException("patient_id is required")
        if (!body.containsKey("allergies"))
            throw IllegalArgumentException("allergies is required")
        val allergies = allergiesOf(body)

        return loadPersonType(id).compose { personType ->
            when (personType) {
                null -> Future.failedFuture(DiningNotFoundException("patient not found: $id"))
                CHILD_PERSON_TYPE -> upsertChildProfileAllergens(id, allergies, body)
                else -> updateExistingProfileAllergens(id, allergies)
            }
        }
    }

    private fun upsertChildProfileAllergens(
        patientId: String,
        allergies: JsonArray,
        body: JsonObject,
    ): Future<JsonObject> {
        val mealType = body.getString("meal_type")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MEAL_TYPE
        if (mealType !in DiningConstants.MEAL_TYPES)
            throw IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}")

        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val tuple = Tuple.tuple()
        tuple.addString(id)
        tuple.addString(patientId)
        tuple.addString(mealType)
        // JSONB 一律绑 Vert.x JsonArray（pgclient 原生编解码）；不要绑 org.jooq.JSONB
        tuple.addValue(allergies)
        tuple.addValue(body.getString("portion_preference"))
        tuple.addValue(body.getString("remark"))
        tuple.addValue(body.containsKey("metadata").let { if (it) body.getJsonObject("metadata") else null })
        tuple.addOffsetDateTime(now)
        tuple.addOffsetDateTime(now)

        return pool.preparedQuery(UPSERT_CHILD_PROFILE_SQL).execute(tuple).compose { rows ->
            val savedId = rows.iterator().asSequence().firstOrNull()?.getString("id") ?: id
            get(savedId)
        }
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
        val conditions = mutableListOf<org.jooq.Condition>()
        patientId?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("p", "patient_id")).eq(it)) }
        encounterId?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("p", "encounter_id")).eq(it)) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(DSL.field(DSL.name("p", "status")).eq(it)) }

        val e = encounters.`as`("e")
        val p = t.`as`("p")
        val pa = patients.`as`("pa")
        // 054 A4b：`encounters` 必须 LEFT JOIN——儿童档案 `encounter_id IS NULL`（无入住），
        // INNER JOIN 会把它们整行丢掉（upsert 后 get()/list() 查不到）。`patients` 保持 INNER
        // （`patient_id` NOT NULL）。既有先例：libs/skills/.../SkillsService.kt:285。
        val joined = p
            .leftJoin(e).on(DSL.field(DSL.name("p", "encounter_id")).eq(DSL.field(DSL.name("e", "id"), String::class.java)))
            .join(pa).on(DSL.field(DSL.name("p", "patient_id")).eq(DSL.field(DSL.name("pa", "id"), String::class.java)))
        val countQuery = ctx.select(count().`as`("total")).from(joined).where(conditions)
        val dataQuery = ctx.select(
            DSL.field(DSL.name("p", "id")).`as`("id"),
            DSL.field(DSL.name("p", "patient_id")).`as`("patient_id"),
            DSL.field(DSL.name("pa", "name")).`as`("patient_name"),
            DSL.field(DSL.name("p", "encounter_id")).`as`("encounter_id"),
            DSL.field(DSL.name("p", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("p", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("p", "portion_preference")).`as`("portion_preference"),
            DSL.field(DSL.name("p", "remark")).`as`("remark"),
            DSL.field(DSL.name("p", "status")).`as`("status"),
            DSL.field(DSL.name("p", "metadata")).`as`("metadata"),
            DSL.field(DSL.name("p", "created_at")).`as`("created_at"),
            DSL.field(DSL.name("p", "updated_at")).`as`("updated_at"),
            DSL.field(DSL.name("e", "status"), String::class.java).`as`("encounter_status"),
        ).from(joined).where(conditions).orderBy(cCreatedAt.desc()).limit(limit).offset(offset)

        return execute(pool, countQuery).flatMap { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                val records = JsonArray()
                for (row in dataRows) records.add(toJson(row))
                JsonObject().put("records", records)
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun get(id: String): Future<JsonObject> {
        val e = encounters.`as`("e")
        val p = t.`as`("p")
        val pa = patients.`as`("pa")
        val pId = DSL.field(DSL.name("p", "id"))
        val query = ctx.select(
            pId.`as`("id"),
            DSL.field(DSL.name("p", "patient_id")).`as`("patient_id"),
            DSL.field(DSL.name("pa", "name")).`as`("patient_name"),
            DSL.field(DSL.name("p", "encounter_id")).`as`("encounter_id"),
            DSL.field(DSL.name("p", "meal_type")).`as`("meal_type"),
            DSL.field(DSL.name("p", "allergies")).`as`("allergies"),
            DSL.field(DSL.name("p", "portion_preference")).`as`("portion_preference"),
            DSL.field(DSL.name("p", "remark")).`as`("remark"),
            DSL.field(DSL.name("p", "status")).`as`("status"),
            DSL.field(DSL.name("p", "metadata")).`as`("metadata"),
            DSL.field(DSL.name("p", "created_at")).`as`("created_at"),
            DSL.field(DSL.name("p", "updated_at")).`as`("updated_at"),
            DSL.field(DSL.name("e", "status"), String::class.java).`as`("encounter_status"),
        ).from(p
            // 054 A4b：儿童档案 `encounter_id IS NULL` → 必须 LEFT JOIN，否则单条查询 404
            .leftJoin(e).on(DSL.field(DSL.name("p", "encounter_id")).eq(DSL.field(DSL.name("e", "id"))))
            .join(pa).on(DSL.field(DSL.name("p", "patient_id")).eq(DSL.field(DSL.name("pa", "id")))))
            .where(pId.eq(id))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null)
                Future.failedFuture(DiningNotFoundException("diet profile not found: $id"))
            else Future.succeededFuture(toJson(row))
        }
    }

    fun update(id: String, body: JsonObject): Future<JsonObject> {
        return get(id).flatMap {
            val now = OffsetDateTime.now()
            var q = ctx.update(t).set(cUpdatedAt, now)

            if (body.containsKey("meal_type")) {
                val mealType = body.getString("meal_type")
                if (mealType.isNullOrBlank() || mealType !in DiningConstants.MEAL_TYPES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid meal_type, must be one of: ${DiningConstants.MEAL_TYPES}"))
                q = q.set(cMealType, mealType)
            }
            if (body.containsKey("allergies"))
                q = q.set(cAllergies, JSONB.valueOf(allergiesOf(body).encode()))
            if (body.containsKey("portion_preference")) {
                val portion = body.getString("portion_preference")
                if (!portion.isNullOrBlank() && portion !in DiningConstants.PORTION_PREFERENCES)
                    return@flatMap Future.failedFuture(IllegalArgumentException("invalid portion_preference, must be one of: ${DiningConstants.PORTION_PREFERENCES}"))
                q = q.set(cPortionPreference, portion)
            }
            if (body.containsKey("remark"))
                q = q.set(cRemark, body.getString("remark"))
            if (body.containsKey("metadata"))
                q = q.set(cMetadata, JSONB.valueOf(body.getJsonObject("metadata").encode()))

            val updateQuery = q.where(cId.eq(id))
            execute(pool, updateQuery).flatMap { get(id) }
        }
    }

    fun updateStatus(id: String, status: String): Future<JsonObject> {
        if (status.isBlank() || status !in DiningConstants.ENABLE_STATUSES)
            return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${DiningConstants.ENABLE_STATUSES}"))
        return get(id).flatMap { existing ->
            val now = OffsetDateTime.now()
            val query = ctx.update(t)
                .set(cStatus, status)
                .set(cUpdatedAt, now)
                .where(cId.eq(id))
            execute(pool, query).compose { result ->
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
            val query = ctx.deleteFrom(t).where(cId.eq(id))
            execute(pool, query).map<Void?> { null }
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
            val now = OffsetDateTime.now()
            val query = ctx.update(t)
                .set(cAllergies, JSONB.valueOf(allergies.encode()))
                .set(cUpdatedAt, now)
                .where(cId.eq(id))
            execute(pool, query).compose { result ->
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

    private fun execute(client: io.vertx.sqlclient.SqlClient, query: org.jooq.Query): Future<io.vertx.sqlclient.RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
