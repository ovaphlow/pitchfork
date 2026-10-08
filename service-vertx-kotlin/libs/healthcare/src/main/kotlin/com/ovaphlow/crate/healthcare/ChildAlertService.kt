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
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 儿保异常识别与家长通报服务（052 P4，V529）。
 *
 * 业务规则（服务端强制）：
 *  1. 异常识别**按儿童口径**：体温/心率/呼吸用 [ChildVitalThresholds] 的年龄段分档
 *     （成人 `vital_sign_records.abnormal` 的写时判定完全不变，052 §7）；
 *     生长按落库快照的百分位（P3/P97）；接种按排程读时结论「逾期」；体检按结论「异常/需转诊」。
 *  2. 聚合读 `GET /child-health-alerts` 按**单个儿童**聚合（patient_id 必填），
 *     四类来源合并后按发生时间倒序；`status` 为 待通报/已通报（依据本域台账是否已有
 *     同 `alert_type + alert_ref` 的通报）。
 *  3. 通报 = 本域台账（`child_health_notifications`）+ Nexus `messages`（052 D6）。
 *     只有渠道「站内」且端口已接线时才调用 Nexus；短信/微信未接入一律记「待接入」，
 *     **不得**宣称已送达；Nexus 调用失败记「发送失败」并留台账（护士可据此重试）。
 *  4. payload 只带必要字段（儿童 id/姓名、异常类型与引用、内容快照、渠道），不落身份证号（052 D10）。
 *  5. `operator` 一律取认证主体（042 范式）。
 */
class ChildAlertService(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
    private val messagePort: ChildHealthMessagePort? = null,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ChildAlertService::class.java)

        /** 异常类型中文白名单（与 V529 注释同源）。 */
        val alertTypes = setOf("体温异常", "心率异常", "呼吸异常", "生长异常", "接种逾期", "体检异常", "健康指导")

        /** 通报渠道白名单；MVP 只接入「站内」。 */
        val channels = setOf("站内", "短信", "微信")

        /** 已接入外发能力的渠道（其余记「待接入」）。 */
        private val WIRED_CHANNELS = setOf("站内")

        /** 聚合状态（读时计算）。 */
        val alertStatuses = setOf("待通报", "已通报")

        /** 体征类型 → 异常类型中文名。 */
        private val vitalAlertTypes = mapOf(
            "TEMPERATURE" to "体温异常",
            "PULSE" to "心率异常",
            "RESPIRATION" to "呼吸异常",
        )

        /** 生长异常的百分位阈值（< P3 或 > P97）。 */
        const val GROWTH_LOW_PERCENTILE = 3.0
        const val GROWTH_HIGH_PERCENTILE = 97.0

        /** 单次聚合每个来源最多扫描的记录数（读端点必须有界）。 */
        private const val SOURCE_SCAN_LIMIT = 200

        private val createKeys = setOf(
            "patient_id", "alert_type", "alert_ref", "guardian_name", "guardian_phone",
            "channel", "content", "metadata",
        )

        internal fun notificationJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("patient_name", row.getString("patient_name"))
                .put("alert_type", row.getString("alert_type"))
                .put("alert_ref", row.getString("alert_ref"))
                .put("guardian_name", row.getString("guardian_name"))
                .put("guardian_phone", row.getString("guardian_phone"))
                .put("channel", row.getString("channel"))
                .put("content", row.getString("content"))
                .put("message_id", row.getString("message_id"))
                .put("status", row.getString("status"))
                .put("operator", row.getString("operator"))
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
    }

    private val businessZone = ZoneId.of("Asia/Shanghai")

    private val notifications = DSL.table(DSL.name("healthcare", "child_health_notifications"))
    private val checkups = DSL.table(DSL.name("healthcare", "child_health_checkups"))
    private val measurements = DSL.table(DSL.name("healthcare", "child_growth_measurements"))
    private val vaccinations = DSL.table(DSL.name("healthcare", "child_vaccination_records"))

    private val nId = DSL.field(DSL.name("child_health_notifications", "id"), String::class.java)
    private val nPatientId = DSL.field(DSL.name("child_health_notifications", "patient_id"), String::class.java)
    private val nAlertType = DSL.field(DSL.name("child_health_notifications", "alert_type"), String::class.java)
    private val nAlertRef = DSL.field(DSL.name("child_health_notifications", "alert_ref"), String::class.java)
    private val nGuardianName = DSL.field(DSL.name("child_health_notifications", "guardian_name"), String::class.java)
    private val nGuardianPhone = DSL.field(DSL.name("child_health_notifications", "guardian_phone"), String::class.java)
    private val nChannel = DSL.field(DSL.name("child_health_notifications", "channel"), String::class.java)
    private val nContent = DSL.field(DSL.name("child_health_notifications", "content"), String::class.java)
    private val nMessageId = DSL.field(DSL.name("child_health_notifications", "message_id"), String::class.java)
    private val nStatus = DSL.field(DSL.name("child_health_notifications", "status"), String::class.java)
    private val nOperator = DSL.field(DSL.name("child_health_notifications", "operator"), String::class.java)
    private val nMetadata = DSL.field(DSL.name("child_health_notifications", "metadata"), JSONB::class.java)
    private val nCreatedAt = DSL.field(DSL.name("child_health_notifications", "created_at"), OffsetDateTime::class.java)

    private val selectFields: List<Field<*>> = listOf(
        nId, nPatientId, nAlertType, nAlertRef, nGuardianName, nGuardianPhone,
        nChannel, nContent, nMessageId, nStatus, nOperator, nMetadata, nCreatedAt,
    )

    private val wId = DSL.field(DSL.name("id"), String::class.java)
    private val wPatientId = DSL.field(DSL.name("patient_id"), String::class.java)
    private val wAlertType = DSL.field(DSL.name("alert_type"), String::class.java)
    private val wAlertRef = DSL.field(DSL.name("alert_ref"), String::class.java)
    private val wGuardianName = DSL.field(DSL.name("guardian_name"), String::class.java)
    private val wGuardianPhone = DSL.field(DSL.name("guardian_phone"), String::class.java)
    private val wChannel = DSL.field(DSL.name("channel"), String::class.java)
    private val wContent = DSL.field(DSL.name("content"), String::class.java)
    private val wMessageId = DSL.field(DSL.name("message_id"), String::class.java)
    private val wStatus = DSL.field(DSL.name("status"), String::class.java)
    private val wOperator = DSL.field(DSL.name("operator"), String::class.java)
    private val wMetadata = DSL.field(DSL.name("metadata"), JSONB::class.java)
    private val wCreatedAt = DSL.field(DSL.name("created_at"), OffsetDateTime::class.java)
    private val wUpdatedAt = DSL.field(DSL.name("updated_at"), OffsetDateTime::class.java)

    // ========================================================================
    //  通报台账
    // ========================================================================

    fun createNotification(body: JsonObject, operator: String, cookie: String?): Future<JsonObject> {
        val parsed: Parsed = try {
            parseCreate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, parsed.patientId).compose { patient ->
                val childName = patient.getString("name") ?: parsed.patientId
                dispatch(parsed, childName, cookie).compose { outcome ->
                    insertNotification(connection, parsed, outcome, operator)
                }
            }
        }
    }

    /**
     * 「生成提醒」：对逾期项逐条写台账（并调用 Nexus message），临期项只在响应里回给护士。
     *
     * 临期不是异常，不生成台账——否则告警页会被「还没到龄」的正常排程塞满。
     */
    fun remindVaccination(
        patientId: String,
        body: JsonObject,
        operator: String,
        cookie: String?,
    ): Future<JsonObject> {
        val channel = body.getString("channel")?.trim()?.takeIf(String::isNotBlank) ?: "站内"
        if (channel !in channels) {
            return Future.failedFuture(IllegalArgumentException("invalid channel, must be one of: $channels"))
        }
        val vaccinationService = ChildVaccinationService(pool, ctx)
        return guardianVia(pool, patientId).compose { guardian ->
            vaccinationService.getVaccinationStatus(patientId).compose { status ->
                val (overdue, dueSoon) = vaccinationService.reminderItems(status)
                val patientName = status.getString("patient_name") ?: patientId
                val notified = JsonArray()
                val pending = overdue.map { it as JsonObject }
                if (pending.isEmpty()) {
                    return@compose Future.succeededFuture(
                        JsonObject()
                            .put("patient_id", patientId)
                            .put("notified", notified)
                            .put("due_soon", dueSoon)
                            .put("meta", JsonObject().put("notified", 0).put("due_soon", dueSoon.size())),
                    )
                }
                // 逐条串行发送：台账与 message 一一对应，便于告警页按 alert_ref 判定「已通报」。
                var chain: Future<Unit> = Future.succeededFuture(Unit)
                pending.forEach { item ->
                    chain = chain.compose {
                        val alertRef = "${item.getString("vaccine_code")}:${item.getInteger("dose_no")}"
                        val parsed = Parsed(
                            patientId = patientId,
                            alertType = "接种逾期",
                            alertRef = alertRef,
                            guardianName = guardian.first,
                            guardianPhone = guardian.second,
                            channel = channel,
                            content = "预防接种逾期提醒：${item.getString("vaccine_name")} 第 " +
                                "${item.getInteger("dose_no")} 剂，应于 ${item.getString("due_date")} 接种，" +
                                "当前状态：逾期。请尽快安排补种。",
                        )
                        dispatch(parsed, patientName, cookie).compose { outcome ->
                            pool.withTransaction<JsonObject> { connection ->
                                insertNotification(connection, parsed, outcome, operator)
                            }.map { created ->
                                notified.add(created)
                                Unit
                            }
                        }
                    }
                }
                chain.map {
                    JsonObject()
                        .put("patient_id", patientId)
                        .put("notified", notified)
                        .put("due_soon", dueSoon)
                        .put(
                            "meta",
                            JsonObject().put("notified", notified.size()).put("due_soon", dueSoon.size()),
                        )
                }
            }
        }
    }

    fun listNotifications(patientId: String?, limit: Int, offset: Int): Future<JsonObject> {
        val conditions = try {
            val patient = patientId?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("patient_id is required")
            listOf(nPatientId.eq(patient))
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val countQuery = ctx.select(DSL.count().`as`("total")).from(notifications).where(conditions)
        val dataQuery = baseQuery().where(conditions).orderBy(nCreatedAt.desc()).limit(limit).offset(offset)
        return executeChildQuery(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            executeChildQuery(pool, dataQuery).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(Companion::notificationJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  异常聚合读
    // ========================================================================

    fun listAlerts(
        patientId: String?,
        status: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val childId = patientId?.trim()?.takeIf(String::isNotBlank)
            ?: return Future.failedFuture(IllegalArgumentException("patient_id is required"))
        val statusFilter = status?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            value.takeIf { it in alertStatuses }
                ?: return Future.failedFuture(
                    IllegalArgumentException("invalid status, must be one of: $alertStatuses"),
                )
        }

        return pool.withTransaction<JsonObject> { connection ->
            childPatientRow(ctx, connection, childId).compose { patient ->
                val birthDate = patient.getLocalDate("birth_date")
                val childName = patient.getString("name") ?: childId
                Future.all(
                    vitalAlerts(connection, childId, birthDate),
                    growthAlerts(connection, childId, birthDate),
                    vaccinationAlerts(connection, childId, birthDate),
                    checkupAlerts(connection, childId, birthDate),
                    notifiedRefs(connection, childId),
                    guardianVia(connection, childId),
                ).map { results ->
                    val alerts = mutableListOf<JsonObject>()
                    (0..3).forEach { index ->
                        results.resultAt<List<JsonObject>>(index).forEach { alerts.add(it) }
                    }
                    val notified = results.resultAt<Set<String>>(4)
                    val guardian = results.resultAt<Pair<String?, String?>>(5)
                    val merged = alerts
                        .sortedByDescending { it.getString("occurred_at") ?: "" }
                        .map { alert ->
                            val key = "${alert.getString("alert_type")}|${alert.getString("alert_ref")}"
                            alert
                                .put("status", if (key in notified) "已通报" else "待通报")
                                .put("patient_id", childId)
                                .put("patient_name", childName)
                                .put("guardian_name", guardian.first)
                                .put("guardian_phone", guardian.second)
                        }
                        .filter { statusFilter == null || it.getString("status") == statusFilter }
                    val page = merged.drop(offset).take(limit)
                    JsonObject()
                        .put("records", JsonArray(page))
                        .put("meta", JsonObject().put("total", merged.size.toLong()))
                }
            }
        }
    }

    private fun vitalAlerts(client: SqlClient, patientId: String, birthDate: LocalDate): Future<List<JsonObject>> =
        executeChildQuery(
            client,
            ctx.select(
                VITAL_SIGN_RECORDS.ID,
                VITAL_SIGN_RECORDS.TYPE,
                VITAL_SIGN_RECORDS.VALUE,
                VITAL_SIGN_RECORDS.UNIT,
                VITAL_SIGN_RECORDS.MEASURED_AT,
            )
                .from(VITAL_SIGN_RECORDS)
                .where(
                    VITAL_SIGN_RECORDS.PATIENT_ID.eq(patientId),
                    VITAL_SIGN_RECORDS.DELETED_AT.isNull,
                    VITAL_SIGN_RECORDS.TYPE.`in`(vitalAlertTypes.keys.toList()),
                )
                .orderBy(VITAL_SIGN_RECORDS.MEASURED_AT.desc())
                .limit(SOURCE_SCAN_LIMIT),
        ).map { rows ->
            rows.mapNotNull { row ->
                val type = row.getString("type") ?: return@mapNotNull null
                val value = row.getBigDecimal("value") ?: return@mapNotNull null
                val measuredAt = row.getOffsetDateTime("measured_at") ?: return@mapNotNull null
                val ageDays = ChildHealth.ageDays(birthDate, measuredAt.atZoneSameInstant(businessZone).toLocalDate())
                if (!ChildVitalThresholds.isAbnormal(type, value.toDouble(), ageDays)) return@mapNotNull null
                JsonObject()
                    .put("alert_type", vitalAlertTypes.getValue(type))
                    .put("alert_ref", row.getString("id"))
                    .put("severity", if (type == "TEMPERATURE") "高" else "中")
                    .put("title", "${vitalAlertTypes.getValue(type)}：${value.stripTrailingZeros().toPlainString()} ${row.getString("unit")}")
                    .put("detail", ChildVitalThresholds.describe(type, ageDays))
                    .put("occurred_at", measuredAt.toString())
                    .put("metric", type)
                    .put("value", value)
            }
        }

    private fun growthAlerts(client: SqlClient, patientId: String, birthDate: LocalDate): Future<List<JsonObject>> =
        executeChildQuery(
            client,
            ctx.select(
                DSL.field(DSL.name("child_growth_measurements", "id"), String::class.java),
                DSL.field(DSL.name("child_growth_measurements", "measured_at"), OffsetDateTime::class.java),
                DSL.field(DSL.name("child_growth_measurements", "percentile_snapshot"), JSONB::class.java),
            )
                .from(measurements)
                .where(
                    DSL.field(DSL.name("child_growth_measurements", "patient_id"), String::class.java).eq(patientId),
                    DSL.field(DSL.name("child_growth_measurements", "deleted_at"), OffsetDateTime::class.java).isNull,
                )
                .orderBy(DSL.field(DSL.name("child_growth_measurements", "measured_at"), OffsetDateTime::class.java).desc())
                .limit(SOURCE_SCAN_LIMIT),
        ).map { rows ->
            rows.mapNotNull { row ->
                val snapshot = row.getValue("percentile_snapshot") as? JsonObject ?: return@mapNotNull null
                val outliers = snapshot.fieldNames().mapNotNull { metric ->
                    val percentile = snapshot.getJsonObject(metric)?.getValue("p")?.let { (it as? Number)?.toDouble() }
                        ?: return@mapNotNull null
                    if (percentile < GROWTH_LOW_PERCENTILE || percentile > GROWTH_HIGH_PERCENTILE) {
                        "$metric=P$percentile"
                    } else {
                        null
                    }
                }
                if (outliers.isEmpty()) return@mapNotNull null
                JsonObject()
                    .put("alert_type", "生长异常")
                    .put("alert_ref", row.getString("id"))
                    .put("severity", "中")
                    .put("title", "生长指标超出 P3–P97：${outliers.joinToString("，")}")
                    .put("detail", "WHO 0–5 岁参考标准；建议复测并评估喂养/疾病因素")
                    .put("occurred_at", row.getOffsetDateTime("measured_at")?.toString())
                    .put("metric", "GROWTH")
            }
        }

    private fun vaccinationAlerts(client: SqlClient, patientId: String, birthDate: LocalDate): Future<List<JsonObject>> =
        executeChildQuery(
            client,
            ctx.select(
                DSL.field(DSL.name("child_vaccination_records", "vaccine_code"), String::class.java),
                DSL.field(DSL.name("child_vaccination_records", "dose_no"), Int::class.javaObjectType),
                DSL.field(DSL.name("child_vaccination_records", "status"), String::class.java),
            )
                .from(vaccinations)
                .where(DSL.field(DSL.name("child_vaccination_records", "patient_id"), String::class.java).eq(patientId)),
        ).map { rows ->
            val byKey = rows.associateBy {
                "${it.getString("vaccine_code")}:${it.getInteger("dose_no")}"
            }
            val today = ChildHealth.today()
            ChildVaccinationService.VACCINATION_SCHEDULE.mapNotNull { entry ->
                val record = byKey["${entry.code}:${entry.doseNo}"]
                val status = ChildVaccinationService.statusOf(entry, record?.getString("status"), birthDate, today)
                if (status != "逾期") return@mapNotNull null
                JsonObject()
                    .put("alert_type", "接种逾期")
                    .put("alert_ref", "${entry.code}:${entry.doseNo}")
                    .put("severity", "中")
                    .put("title", "接种逾期：${entry.name} 第 ${entry.doseNo} 剂")
                    .put(
                        "detail",
                        "推荐月龄 ${entry.recommendedMonth} 月，应于 " +
                            "${ChildVaccinationService.dueDate(birthDate, entry)} 前接种",
                    )
                    .put("occurred_at", ChildVaccinationService.dueDate(birthDate, entry).atStartOfDay(businessZone).toOffsetDateTime().toString())
                    .put("metric", "VACCINATION")
            }
        }

    private fun checkupAlerts(client: SqlClient, patientId: String, birthDate: LocalDate): Future<List<JsonObject>> =
        executeChildQuery(
            client,
            ctx.select(
                DSL.field(DSL.name("child_health_checkups", "id"), String::class.java),
                DSL.field(DSL.name("child_health_checkups", "checkup_date"), LocalDate::class.java),
                DSL.field(DSL.name("child_health_checkups", "checkup_type"), String::class.java),
                DSL.field(DSL.name("child_health_checkups", "conclusion"), String::class.java),
            )
                .from(checkups)
                .where(
                    DSL.field(DSL.name("child_health_checkups", "patient_id"), String::class.java).eq(patientId),
                    DSL.field(DSL.name("child_health_checkups", "conclusion"), String::class.java)
                        .`in`(listOf("异常", "需转诊")),
                )
                .orderBy(DSL.field(DSL.name("child_health_checkups", "checkup_date"), LocalDate::class.java).desc())
                .limit(SOURCE_SCAN_LIMIT),
        ).map { rows ->
            rows.mapNotNull { row ->
                val date = row.getLocalDate("checkup_date") ?: return@mapNotNull null
                JsonObject()
                    .put("alert_type", "体检异常")
                    .put("alert_ref", row.getString("id"))
                    .put("severity", if (row.getString("conclusion") == "需转诊") "高" else "中")
                    .put("title", "儿保体检结论：${row.getString("conclusion")}（${row.getString("checkup_type")}）")
                    .put("detail", "体检日期 $date；请复核检查项并按需转诊")
                    .put("occurred_at", date.atStartOfDay(businessZone).toOffsetDateTime().toString())
                    .put("metric", "CHECKUP")
            }
        }

    private fun notifiedRefs(client: SqlClient, patientId: String): Future<Set<String>> =
        executeChildQuery(client, ctx.select(nAlertType, nAlertRef).from(notifications).where(nPatientId.eq(patientId)))
            .map { rows ->
                rows.mapNotNull { row ->
                    val type = row.getString("alert_type") ?: return@mapNotNull null
                    "$type|${row.getString("alert_ref")}"
                }.toSet()
            }

    // ========================================================================
    //  台账写入与 Nexus 外发
    // ========================================================================

    private data class Parsed(
        val patientId: String,
        val alertType: String,
        val alertRef: String?,
        val guardianName: String?,
        val guardianPhone: String?,
        val channel: String,
        val content: String,
        val metadata: JsonObject? = null,
    )

    private data class Outcome(val messageId: String?, val status: String, val error: String? = null)

    private fun parseCreate(body: JsonObject): Parsed {
        rejectForbiddenKeys(body, createKeys, "child health notification")
        val patientId = body.getString("patient_id")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("patient_id is required")
        val alertType = body.getString("alert_type")?.trim()?.takeIf(String::isNotBlank)
            ?.let { it.takeIf { value -> value in alertTypes }
                ?: throw IllegalArgumentException("invalid alert_type, must be one of: $alertTypes") }
            ?: throw IllegalArgumentException("alert_type is required")
        val channel = body.getString("channel")?.trim()?.takeIf(String::isNotBlank) ?: "站内"
        if (channel !in channels) {
            throw IllegalArgumentException("invalid channel, must be one of: $channels")
        }
        val content = body.getString("content")?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("content is required")
        if (content.length > 2000) throw IllegalArgumentException("content must not exceed 2000 characters")
        return Parsed(
            patientId = patientId,
            alertType = alertType,
            alertRef = optionalText(body, "alert_ref", 200),
            guardianName = optionalText(body, "guardian_name", 100),
            guardianPhone = optionalText(body, "guardian_phone", 50),
            channel = channel,
            content = content,
            metadata = jsonObject(body, "metadata"),
        )
    }

    /**
     * 外发：只有渠道已接入且端口已接线时才调用 Nexus；其余情况明确记「待接入」。
     * 失败只记错误信息，绝不记 cookie / 会话值。
     */
    private fun dispatch(parsed: Parsed, childName: String, cookie: String?): Future<Outcome> {
        if (parsed.channel !in WIRED_CHANNELS || messagePort == null) {
            return Future.succeededFuture(Outcome(null, "待接入"))
        }
        val payload = JsonObject()
            .put("child_id", parsed.patientId)
            .put("child_name", childName)
            .put("alert_type", parsed.alertType)
            .put("alert_ref", parsed.alertRef)
            .put("channel", parsed.channel)
            .put("guardian_name", parsed.guardianName)
            .put("content", parsed.content)
        return messagePort.send(
            messageType = "child_health_alert",
            receiverId = parsed.guardianPhone ?: parsed.patientId,
            receiverType = "guardian",
            payload = payload,
            cookie = cookie,
        ).map { messageId -> Outcome(messageId, "已发送") }
            .recover { error ->
                log.warn("child health notification dispatch failed: {}", error.message)
                Future.succeededFuture(Outcome(null, "发送失败", error.message))
            }
    }

    private fun insertNotification(
        client: SqlClient,
        parsed: Parsed,
        outcome: Outcome,
        operator: String,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val id = Ulid.generate()
        val metadata = (parsed.metadata ?: JsonObject()).copy()
        outcome.error?.let { metadata.put("dispatch_error", it.take(200)) }
        val insert = ctx.insertInto(notifications)
            .set(wId, id)
            .set(wPatientId, parsed.patientId)
            .set(wAlertType, parsed.alertType)
            .set(wChannel, parsed.channel)
            .set(wContent, parsed.content)
            .set(wStatus, outcome.status)
            .set(wOperator, operator)
            .set(wCreatedAt, now)
            .set(wUpdatedAt, now)
            .apply {
                parsed.alertRef?.let { set(wAlertRef, it) }
                parsed.guardianName?.let { set(wGuardianName, it) }
                parsed.guardianPhone?.let { set(wGuardianPhone, it) }
                outcome.messageId?.let { set(wMessageId, it) }
                if (metadata.size() > 0) set(wMetadata, JSONB.valueOf(metadata.encode()))
            }
        return executeChildQuery(client, insert).compose { getNotificationVia(client, id) }
    }

    private fun getNotificationVia(client: SqlClient, id: String): Future<JsonObject> =
        executeChildQuery(client, baseQuery().where(nId.eq(id))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(notificationJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("child health notification not found: $id"))
        }

    /** 监护人快照：读 `child_health_profiles`（V525），无档案时返回 (null, null)。 */
    private fun guardianVia(client: SqlClient, patientId: String): Future<Pair<String?, String?>> =
        executeChildQuery(
            client,
            ctx.select(
                DSL.field(DSL.name("child_health_profiles", "guardian_name"), String::class.java),
                DSL.field(DSL.name("child_health_profiles", "guardian_phone"), String::class.java),
            )
                .from(DSL.table(DSL.name("healthcare", "child_health_profiles")))
                .where(DSL.field(DSL.name("child_health_profiles", "patient_id"), String::class.java).eq(patientId)),
        ).map { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            Pair(row?.getString("guardian_name"), row?.getString("guardian_phone"))
        }

    private fun baseQuery() = ctx.select(
        selectFields + listOf(DSL.field(DSL.name("patients", "name"), String::class.java).`as`("patient_name")),
    )
        .from(notifications)
        .join(DSL.table(DSL.name("healthcare", "patients")))
        .on(nPatientId.eq(DSL.field(DSL.name("patients", "id"), String::class.java)))

    private fun optionalText(body: JsonObject, key: String, max: Int): String? =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > max) throw IllegalArgumentException("$key must not exceed $max characters")
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
