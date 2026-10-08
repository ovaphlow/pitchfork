package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.VitalSignRecords.VITAL_SIGN_RECORDS
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 儿保成长测量服务（052 P2，V527）。
 *
 * 业务规则（服务端强制）：
 *  1. 只服务 `person_type=儿童` 且 `birth_date` 非空的患者（`childPatientRow`）。
 *  2. 一次测量会话一行：身高/体重/头围三项可空，但**至少一项**非空；体温不落本表。
 *  3. 体温按 052 裁定 7 合并录入：请求体带 `temperature` 时，同事务再写一条
 *     `vital_sign_records`（type=TEMPERATURE），护士不必分两处录。
 *  4. `age_days` 由服务端按 `birth_date` 与 `measured_at` 派生落库，客户端不得提交。
 *  5. `percentile_snapshot` 为落库时评价快照（前端 `lib/growth.ts` 按 WHO LMS 计算后提交），
 *     服务端只做形状校验并透传——不在 Kotlin 侧复制 WHO 参考数据（052 D3）。
 *  6. 删除为软删除（deleted_at），默认查询与序列均排除；体温记录不随本表删除（各表独立追溯）。
 *  7. `operator` 一律取认证主体（042 范式）。
 */
class ChildGrowthService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 测量指标 → 列名（序列与筛选共用）。 */
        internal val metrics = mapOf(
            "height" to "height_cm",
            "weight" to "weight_kg",
            "head" to "head_circumference_cm",
        )

        /** 创建写白名单：id/age_days/operator/deleted_at/created_at/updated_at 由服务端管控。 */
        private val createKeys = setOf(
            "patient_id", "measured_at", "height_cm", "weight_kg", "head_circumference_cm",
            "temperature", "percentile_snapshot", "note", "metadata",
        )

        /** 修正写白名单（不含体温：体温记录在 vital_sign_records，走体征修正路由）。 */
        private val patchKeys = setOf(
            "measured_at", "height_cm", "weight_kg", "head_circumference_cm",
            "percentile_snapshot", "note", "metadata",
        )

        internal fun measurementJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("measured_at", row.getOffsetDateTime("measured_at")?.toString())
                .put("age_days", row.getInteger("age_days"))
                .put("height_cm", row.getBigDecimal("height_cm"))
                .put("weight_kg", row.getBigDecimal("weight_kg"))
                .put("head_circumference_cm", row.getBigDecimal("head_circumference_cm"))
                .put("percentile_snapshot", row.getValue("percentile_snapshot"))
                .put("operator", row.getString("operator"))
                .put("note", row.getString("note"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    private val table = DSL.table(DSL.name("healthcare", "child_growth_measurements"))
    private val patientsTable = DSL.table(DSL.name("healthcare", "patients"))

    private val fId = DSL.field(DSL.name("child_growth_measurements", "id"), String::class.java)
    private val fPatientId = DSL.field(DSL.name("child_growth_measurements", "patient_id"), String::class.java)
    private val fMeasuredAt = DSL.field(DSL.name("child_growth_measurements", "measured_at"), OffsetDateTime::class.java)
    private val fAgeDays = DSL.field(DSL.name("child_growth_measurements", "age_days"), Int::class.javaObjectType)
    private val fHeight = DSL.field(DSL.name("child_growth_measurements", "height_cm"), BigDecimal::class.java)
    private val fWeight = DSL.field(DSL.name("child_growth_measurements", "weight_kg"), BigDecimal::class.java)
    private val fHead = DSL.field(DSL.name("child_growth_measurements", "head_circumference_cm"), BigDecimal::class.java)
    private val fSnapshot = DSL.field(DSL.name("child_growth_measurements", "percentile_snapshot"), JSONB::class.java)
    private val fOperator = DSL.field(DSL.name("child_growth_measurements", "operator"), String::class.java)
    private val fNote = DSL.field(DSL.name("child_growth_measurements", "note"), String::class.java)
    private val fMetadata = DSL.field(DSL.name("child_growth_measurements", "metadata"), JSONB::class.java)
    private val fDeletedAt = DSL.field(DSL.name("child_growth_measurements", "deleted_at"), OffsetDateTime::class.java)
    private val fCreatedAt = DSL.field(DSL.name("child_growth_measurements", "created_at"), OffsetDateTime::class.java)
    private val fUpdatedAt = DSL.field(DSL.name("child_growth_measurements", "updated_at"), OffsetDateTime::class.java)

    private val selectFields: List<Field<*>> = listOf(
        fId, fPatientId, fMeasuredAt, fAgeDays, fHeight, fWeight, fHead,
        fSnapshot, fOperator, fNote, fMetadata, fCreatedAt, fUpdatedAt,
    )

    // INSERT / UPDATE SET 用的裸列名。
    private val wId = DSL.field(DSL.name("id"), String::class.java)
    private val wPatientId = DSL.field(DSL.name("patient_id"), String::class.java)
    private val wMeasuredAt = DSL.field(DSL.name("measured_at"), OffsetDateTime::class.java)
    private val wAgeDays = DSL.field(DSL.name("age_days"), Int::class.javaObjectType)
    private val wHeight = DSL.field(DSL.name("height_cm"), BigDecimal::class.java)
    private val wWeight = DSL.field(DSL.name("weight_kg"), BigDecimal::class.java)
    private val wHead = DSL.field(DSL.name("head_circumference_cm"), BigDecimal::class.java)
    private val wSnapshot = DSL.field(DSL.name("percentile_snapshot"), JSONB::class.java)
    private val wOperator = DSL.field(DSL.name("operator"), String::class.java)
    private val wNote = DSL.field(DSL.name("note"), String::class.java)
    private val wMetadata = DSL.field(DSL.name("metadata"), JSONB::class.java)
    private val wDeletedAt = DSL.field(DSL.name("deleted_at"), OffsetDateTime::class.java)
    private val wCreatedAt = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
    private val wUpdatedAt = DSL.field(DSL.name("updated_at"), OffsetDateTime::class.java)

    private val businessZone = ZoneId.of("Asia/Shanghai")

    // ========================================================================
    //  创建（一次会话写成长表；带体温时同事务写体征表）
    // ========================================================================

    fun createMeasurement(body: JsonObject, operator: String): Future<JsonObject> {
        val parsed: Parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        val vitalSignId = if (parsed.temperature != null) Ulid.generate() else null
        return pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, parsed.patientId).compose { patient ->
                val birthDate = patient.getLocalDate("birth_date")
                val ageDays = try {
                    ChildHealth.ageDays(birthDate, localDateOf(parsed.measuredAt))
                } catch (error: IllegalArgumentException) {
                    return@compose Future.failedFuture(error)
                }
                val insert = ctx.insertInto(table)
                    .set(wId, id)
                    .set(wPatientId, parsed.patientId)
                    .set(wMeasuredAt, parsed.measuredAt)
                    .set(wAgeDays, ageDays.toInt())
                    .set(wOperator, operator)
                    .set(wCreatedAt, now)
                    .set(wUpdatedAt, now)
                    .apply {
                        parsed.height?.let { set(wHeight, it) }
                        parsed.weight?.let { set(wWeight, it) }
                        parsed.head?.let { set(wHead, it) }
                        parsed.snapshot?.let { set(wSnapshot, JSONB.valueOf(it.encode())) }
                        parsed.note?.let { set(wNote, it) }
                        parsed.metadata?.let { set(wMetadata, JSONB.valueOf(it.encode())) }
                    }
                executeChildQuery(connection, insert).compose {
                    if (parsed.temperature == null || vitalSignId == null) {
                        getMeasurementVia(connection, id).map { measurement ->
                            JsonObject().put("measurement", measurement).putNull("vital_sign")
                        }
                    } else {
                        val abnormal = ChildVitalThresholds.isAbnormal(
                            "TEMPERATURE",
                            parsed.temperature.toDouble(),
                            ageDays,
                        )
                        val vitalInsert = ctx.insertInto(VITAL_SIGN_RECORDS)
                            .set(VITAL_SIGN_RECORDS.ID, vitalSignId)
                            .set(VITAL_SIGN_RECORDS.PATIENT_ID, parsed.patientId)
                            .set(VITAL_SIGN_RECORDS.TYPE, "TEMPERATURE")
                            .set(VITAL_SIGN_RECORDS.VALUE, parsed.temperature)
                            .set(VITAL_SIGN_RECORDS.UNIT, "℃")
                            .set(VITAL_SIGN_RECORDS.MEASURED_AT, parsed.measuredAt)
                            .set(VITAL_SIGN_RECORDS.RECORDED_BY, operator)
                            .set(VITAL_SIGN_RECORDS.ABNORMAL, abnormal)
                            .set(VITAL_SIGN_RECORDS.CREATED_AT, now)
                            .set(VITAL_SIGN_RECORDS.UPDATED_AT, now)
                        executeChildQuery(connection, vitalInsert).compose {
                            getMeasurementVia(connection, id).map { measurement ->
                                JsonObject()
                                    .put("measurement", measurement)
                                    .put(
                                        "vital_sign",
                                        JsonObject()
                                            .put("id", vitalSignId)
                                            .put("type", "TEMPERATURE")
                                            .put("value", parsed.temperature)
                                            .put("unit", "℃")
                                            .put("abnormal", abnormal),
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    //  查询
    // ========================================================================

    fun listMeasurements(
        patientId: String?,
        metric: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = try {
            val patient = patientId?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("patient_id is required")
            mutableListOf<Condition>(fPatientId.eq(patient), fDeletedAt.isNull()).also {
                metric?.trim()?.takeIf(String::isNotBlank)?.let { value ->
                    val column = metrics[value]
                        ?: throw IllegalArgumentException("invalid metric, must be one of: ${metrics.keys}")
                    it += DSL.field(DSL.name("child_growth_measurements", column), BigDecimal::class.java).isNotNull
                }
                dateFrom?.takeIf(String::isNotBlank)?.let { value ->
                    it += fMeasuredAt.ge(offsetDateTime(value, "date_from"))
                }
                dateTo?.takeIf(String::isNotBlank)?.let { value ->
                    it += fMeasuredAt.le(offsetDateTime(value, "date_to"))
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val countQuery = ctx.select(DSL.count().`as`("total")).from(table).where(conditions)
        val dataQuery = baseQuery()
            .where(conditions)
            .orderBy(fMeasuredAt.desc(), fCreatedAt.desc())
            .limit(limit)
            .offset(offset)

        return executeChildQuery(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            executeChildQuery(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::measurementJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 绘图序列：按指标聚合的年龄轴数据点（`age_days` 升序）。
     *
     * 返回 `{patient_id, birth_date, age_days, series: {height: [...], weight: [...], head: [...]}}`。
     * 参考带（P3/P15/P50/P85/P97）由前端 `lib/growth.ts` 按 WHO LMS 生成，后端只给实测点。
     */
    fun getGrowthSeries(patientId: String): Future<JsonObject> =
        pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, patientId).compose { patient ->
                val birthDate = patient.getLocalDate("birth_date")
                val today = LocalDate.now(businessZone)
                executeChildQuery(
                    connection,
                    baseQuery()
                        .where(fPatientId.eq(patientId), fDeletedAt.isNull())
                        .orderBy(fMeasuredAt.asc(), fCreatedAt.asc()),
                ).map { rows ->
                    val series = JsonObject()
                    metrics.keys.forEach { key -> series.put(key, JsonArray()) }
                    rows.forEach { row ->
                        val ageDays = row.getInteger("age_days")
                        val snapshot = row.getValue("percentile_snapshot") as? JsonObject
                        metrics.forEach { (key, column) ->
                            val value = row.getBigDecimal(column) ?: return@forEach
                            val point = JsonObject()
                                .put("id", row.getString("id"))
                                .put("age_days", ageDays)
                                .put("value", value)
                                .put("measured_at", row.getOffsetDateTime("measured_at")?.toString())
                            (snapshot?.getJsonObject(key))?.let { point.put("percentile", it) }
                            series.getJsonArray(key).add(point)
                        }
                    }
                    JsonObject()
                        .put("patient_id", patientId)
                        .put("patient_name", patient.getString("name"))
                        .put("birth_date", birthDate.toString())
                        .put("age_days", ChildHealth.ageDays(birthDate, today).toInt())
                        .put("series", series)
                }
            }
        }

    fun getMeasurement(id: String): Future<JsonObject> = getMeasurementVia(pool, id)

    private fun getMeasurementVia(client: SqlClient, id: String): Future<JsonObject> =
        executeChildQuery(client, baseQuery().where(fId.eq(id), fDeletedAt.isNull())).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(measurementJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("child growth measurement not found: $id"))
        }

    // ========================================================================
    //  修正与软删除
    // ========================================================================

    fun updateMeasurement(id: String, body: JsonObject): Future<JsonObject> {
        val parsed: Parsed
        try {
            rejectForbiddenKeys(body, patchKeys, "child growth measurement update")
            parsed = parsePatch(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            getRow(connection, id).compose { existing ->
                val patientId = requireNotNull(existing.getString("patient_id"))
                val measuredAt = parsed.measuredAt ?: existing.getOffsetDateTime("measured_at") ?: now
                childPatientRow(ctx, connection, patientId).compose { patient ->
                    val ageDays = try {
                        ChildHealth.ageDays(patient.getLocalDate("birth_date"), localDateOf(measuredAt))
                    } catch (error: IllegalArgumentException) {
                        return@compose Future.failedFuture(error)
                    }
                    val effective = effectiveValues(existing, parsed, body)
                    if (effective.height == null && effective.weight == null && effective.head == null) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("at least one of height_cm, weight_kg, head_circumference_cm is required"),
                        )
                    }
                    val update = ctx.update(table)
                        .set(wMeasuredAt, measuredAt)
                        .set(wAgeDays, ageDays.toInt())
                        .set(wUpdatedAt, now)
                        .apply {
                            if (body.containsKey("height_cm")) {
                                if (parsed.height == null) setNull(wHeight) else set(wHeight, parsed.height)
                            }
                            if (body.containsKey("weight_kg")) {
                                if (parsed.weight == null) setNull(wWeight) else set(wWeight, parsed.weight)
                            }
                            if (body.containsKey("head_circumference_cm")) {
                                if (parsed.head == null) setNull(wHead) else set(wHead, parsed.head)
                            }
                            if (body.containsKey("percentile_snapshot")) {
                                if (parsed.snapshot == null) setNull(wSnapshot)
                                else set(wSnapshot, JSONB.valueOf(parsed.snapshot.encode()))
                            }
                            if (body.containsKey("note")) {
                                if (parsed.note == null) setNull(wNote) else set(wNote, parsed.note)
                            }
                            if (body.containsKey("metadata")) {
                                if (parsed.metadata == null) setNull(wMetadata)
                                else set(wMetadata, JSONB.valueOf(parsed.metadata.encode()))
                            }
                        }
                        .where(fId.eq(id), fDeletedAt.isNull())
                    executeChildQuery(connection, update).compose {
                        if (it.rowCount() != 1) {
                            Future.failedFuture(HealthcareNotFoundException("child growth measurement not found: $id"))
                        } else {
                            getMeasurementVia(connection, id)
                        }
                    }
                }
            }
        }
    }

    fun deleteMeasurement(id: String): Future<JsonObject> {
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            executeChildQuery(
                connection,
                ctx.update(table)
                    .set(wDeletedAt, now)
                    .set(wUpdatedAt, now)
                    .where(fId.eq(id), fDeletedAt.isNull()),
            ).compose { rows ->
                if (rows.rowCount() == 1) {
                    Future.succeededFuture(JsonObject().put("id", id).put("deleted_at", now.toString()))
                } else {
                    Future.failedFuture(HealthcareNotFoundException("child growth measurement not found: $id"))
                }
            }
        }
    }

    // ========================================================================
    //  解析与校验
    // ========================================================================

    private data class Parsed(
        val patientId: String = "",
        val measuredAt: OffsetDateTime = OffsetDateTime.now(),
        val height: BigDecimal? = null,
        val weight: BigDecimal? = null,
        val head: BigDecimal? = null,
        val temperature: BigDecimal? = null,
        val snapshot: JsonObject? = null,
        val note: String? = null,
        val metadata: JsonObject? = null,
    )

    private data class Effective(
        val height: BigDecimal?,
        val weight: BigDecimal?,
        val head: BigDecimal?,
    )

    private fun parseCreate(body: JsonObject): Parsed {
        rejectForbiddenKeys(body, createKeys, "child growth measurement")
        val patientId = body.getString("patient_id")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("patient_id is required")
        val measuredAt = body.getString("measured_at")?.let { offsetDateTime(it, "measured_at") } ?: OffsetDateTime.now()
        if (measuredAt.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("measured_at must not be in the future")
        }
        val height = decimal(body, "height_cm", 30.0, 200.0)
        val weight = decimal(body, "weight_kg", 0.5, 150.0)
        val head = decimal(body, "head_circumference_cm", 20.0, 70.0)
        val temperature = decimal(body, "temperature", 30.0, 45.0)
        if (height == null && weight == null && head == null && temperature == null) {
            throw IllegalArgumentException(
                "at least one of height_cm, weight_kg, head_circumference_cm, temperature is required",
            )
        }
        return Parsed(
            patientId = patientId,
            measuredAt = measuredAt,
            height = height,
            weight = weight,
            head = head,
            temperature = temperature,
            snapshot = snapshotValue(body),
            note = optionalText(body, "note", 1000),
            metadata = jsonObject(body, "metadata"),
        )
    }

    private fun parsePatch(body: JsonObject): Parsed {
        val measuredAt = body.getString("measured_at")?.let {
            offsetDateTime(it, "measured_at").also { value ->
                if (value.isAfter(OffsetDateTime.now())) {
                    throw IllegalArgumentException("measured_at must not be in the future")
                }
            }
        }
        return Parsed(
            measuredAt = measuredAt ?: OffsetDateTime.now(),
            height = decimal(body, "height_cm", 30.0, 200.0),
            weight = decimal(body, "weight_kg", 0.5, 150.0),
            head = decimal(body, "head_circumference_cm", 20.0, 70.0),
            snapshot = snapshotValue(body),
            note = optionalText(body, "note", 1000),
            metadata = jsonObject(body, "metadata"),
        )
    }

    /** 修正后仍须至少一项非空：未提交的项沿用原值。 */
    private fun effectiveValues(existing: Row, parsed: Parsed, body: JsonObject): Effective = Effective(
        height = if (body.containsKey("height_cm")) parsed.height else existing.getBigDecimal("height_cm"),
        weight = if (body.containsKey("weight_kg")) parsed.weight else existing.getBigDecimal("weight_kg"),
        head = if (body.containsKey("head_circumference_cm")) parsed.head
        else existing.getBigDecimal("head_circumference_cm"),
    )

    private fun snapshotValue(body: JsonObject): JsonObject? {
        val value = body.getValue("percentile_snapshot") ?: return null
        val object_ = value as? JsonObject
            ?: throw IllegalArgumentException("percentile_snapshot must be a JSON object")
        object_.forEach { (key, entry) ->
            if (entry != null && entry !is JsonObject) {
                throw IllegalArgumentException("percentile_snapshot.$key must be a JSON object")
            }
        }
        return object_
    }

    private fun decimal(body: JsonObject, key: String, min: Double, max: Double): BigDecimal? {
        val value = body.getValue(key) ?: return null
        val number = (value as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$key must be a number")
        if (!number.isFinite() || number <= 0) throw IllegalArgumentException("$key must be a positive number")
        if (number < min || number > max) {
            throw IllegalArgumentException("$key must be between $min and $max")
        }
        val decimal = BigDecimal.valueOf(number)
        if (decimal.scale() > 2) throw IllegalArgumentException("$key must have at most 2 decimal places")
        return decimal
    }

    private fun optionalText(body: JsonObject, key: String, max: Int): String? =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > max) throw IllegalArgumentException("$key must not exceed $max characters")
        }

    private fun jsonObject(body: JsonObject, key: String): JsonObject? {
        val value = body.getValue(key) ?: return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun offsetDateTime(value: String, field: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 offset date-time")
        }

    /** `measured_at` → 业务时区日历日期（天龄按业务时区口径计算，与前端一致）。 */
    private fun localDateOf(measuredAt: OffsetDateTime): LocalDate = measuredAt.atZoneSameInstant(businessZone).toLocalDate()

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    private fun baseQuery() = ctx.select(
        selectFields + listOf(DSL.field(DSL.name("patients", "name"), String::class.java).`as`("patient_name")),
    )
        .from(table)
        .join(patientsTable)
        .on(fPatientId.eq(DSL.field(DSL.name("patients", "id"), String::class.java)))

    private fun getRow(client: SqlClient, id: String): Future<Row> =
        executeChildQuery(client, ctx.select(selectFields).from(table).where(fId.eq(id), fDeletedAt.isNull()))
            .compose { rows ->
                rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                    ?: Future.failedFuture(HealthcareNotFoundException("child growth measurement not found: $id"))
            }
}
