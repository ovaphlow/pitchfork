package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.nursing.CarePlanRevisionService
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.ElderlyDischargeHandoverSnapshotService
import com.ovaphlow.crate.nursing.NursingIncidentService
import com.ovaphlow.crate.nursing.PlanService
import com.ovaphlow.crate.nursing.ServicePeriodService
import com.ovaphlow.crate.nursing.ShiftHandoverService
import com.ovaphlow.crate.nursing.TaskService
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class HealthcareService(
    private val pool: Pool,
    /**
     * 025 药品目录端口：由 Aceso `Main.kt` 注入 `MaterialService` 适配器。
     * 为 null 时 `MEDICATION` 医嘱一律 fail-closed（503），不跳过目录校验。
     */
    private val drugCatalogPort: DrugCatalogPort? = null,
) {
    private val servicePeriodService = ServicePeriodService(pool)
    private val dischargeHandoverSnapshotService = ElderlyDischargeHandoverSnapshotService()
    private val taskService = TaskService(pool)
    private val planService = PlanService(pool)
    private val medicalOrderService = MedicalOrderService(pool, taskService, drugCatalogPort = drugCatalogPort)
    private val carePlanRevisionService = CarePlanRevisionService(pool)
    private val nursingIncidentService = NursingIncidentService(pool)
    private val shiftHandoverService = ShiftHandoverService(
        pool,
        ensureCareUnitActive = { client, careUnit -> ensureCareUnitActiveForHandover(client, careUnit) },
    )
    private val billService = BillService(pool)
    private val depositOffsetService = DepositOffsetService()

    // ========================================================================
    //  原生 SQL 显式列清单（含 V524 留痕列与 V525 患者域标识）
    //  两种拼法不能混：
    //   - SELECT 必须带表名限定（listEncounters 与 patients 联表）；
    //   - UPDATE SET 子句必须用裸列名（PostgreSQL 不接受表名前缀）。
    // ========================================================================

    /** patients 显式列清单（表别名 `p`）。 */
    private val patientColumns =
        "healthcare.patients.id, healthcare.patients.name, healthcare.patients.gender, healthcare.patients.birth_date, healthcare.patients.id_card_no, healthcare.patients.phone, healthcare.patients.address, " +
            "healthcare.patients.emergency_contact, healthcare.patients.medical_insurance, healthcare.patients.allergies, healthcare.patients.past_history, healthcare.patients.metadata, " +
            "healthcare.patients.status, healthcare.patients.person_type, healthcare.patients.created_at, healthcare.patients.updated_at"

    /** encounters 显式列清单（表别名 `e`，含 V517/V524/V525 新增列）。 */
    private val encounterColumns =
        "healthcare.encounters.id, healthcare.encounters.patient_id, healthcare.encounters.encounter_type, healthcare.encounters.encounter_no, healthcare.encounters.department, healthcare.encounters.ward, healthcare.encounters.admit_date, " +
            "healthcare.encounters.discharge_date, healthcare.encounters.death_date, healthcare.encounters.death_cause, healthcare.encounters.admitting_diagnosis, " +
            "healthcare.encounters.discharge_diagnosis, healthcare.encounters.attending_physician, healthcare.encounters.status, healthcare.encounters.settled_at, " +
            "healthcare.encounters.discharged_by, healthcare.encounters.discharged_at, healthcare.encounters.deceased_by, healthcare.encounters.deceased_at, healthcare.encounters.metadata, " +
            "healthcare.encounters.created_at, healthcare.encounters.updated_at"

    /** progress_notes 显式列清单（单表，无别名）。 */
    private val progressNoteColumns =
        "id, encounter_id, note_type, content, physician, record_time, metadata, created_at"

    /** diagnoses 显式列清单（单表，无别名）。 */
    private val diagnosisColumns =
        "id, encounter_id, diagnosis_type, icd_code, diagnosis_text, diagnosis_date, physician, " +
            "is_major, metadata, created_at"

    /** medical_records 显式列清单（单表，无别名）。 */
    private val medicalRecordColumns =
        "id, encounter_id, record_type, title, content, content_blocks, physician, record_date, " +
            "metadata, created_at, updated_at"

    /** 护理记录/离院交接摘要共用的文书插入语句（content 与 content_blocks 位置固定）。 */
    private val INSERT_MEDICAL_RECORD_SQL =
        "INSERT INTO healthcare.medical_records " +
            "(id, encounter_id, record_type, title, content, content_blocks, physician, record_date, " +
            "metadata, created_at, updated_at) " +
            "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10, \$11)"

    /** nursing.nursing_service_periods 显式列清单（单表，无别名）。 */
    private val servicePeriodColumns =
        "id, patient_id, service_type, start_date, end_date, coordinator, status, metadata, " +
            "created_at, updated_at"


    companion object {
        private val patientStatuses = setOf("ACTIVE", "INACTIVE", "DECEASED")
        private val encounterStatuses = setOf("ACTIVE", "DISCHARGED", "TRANSFERRED")
        private val diagnosisTypes = setOf("PRIMARY", "SECONDARY")
        private val businessZone = ZoneId.of("Asia/Shanghai")
        /** 交班快照只收集开放照护周期下的活动养老入住。 */
        private val HANDOVER_PERIOD_OPEN_STATUSES = setOf("ACTIVE", "SUSPENDED")

        /**
         * 患者域白名单。养老单产品只写「长者」；「居民」「儿童」保留为历史值可读
         * （056 D3：既有库中可能存在这两种档案，删值会让历史档案不可读）。
         */
        internal val personTypeValues = setOf("居民", "长者", "儿童")
        internal const val PERSON_TYPE_DEFAULT = "居民"

        /**
         * 解析请求体中的 person_type（纯函数，不访库）：缺省/空白 → 默认「居民」；非字符串 → 400。
         */
        internal fun personTypeFrom(body: JsonObject): String {
            val raw = body.getValue("person_type") ?: return PERSON_TYPE_DEFAULT
            if (raw !is String) throw IllegalArgumentException("person_type must be a string")
            return raw.trim().ifBlank { PERSON_TYPE_DEFAULT }
        }

        /** 校验 person_type 白名单（纯函数，不访库）。 */
        internal fun validatePersonType(personType: String) {
            if (personType !in personTypeValues) {
                throw IllegalArgumentException("invalid person_type, must be one of: $personTypeValues")
            }
        }

        private fun patientJson(row: Row): JsonObject {
            val personType = row.getString("person_type")
            return JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("gender", row.getString("gender"))
                .put("birth_date", row.getLocalDate("birth_date")?.toString())
                .put("id_card_no", row.getString("id_card_no"))
                .put("phone", row.getString("phone"))
                .put("address", row.getString("address"))
                .put("emergency_contact", row.getValue("emergency_contact"))
                .put("medical_insurance", row.getString("medical_insurance"))
                .put("allergies", row.getValue("allergies"))
                .put("past_history", row.getString("past_history"))
                .put("metadata", row.getValue("metadata"))
                .put("status", row.getString("status"))
                .put("person_type", personType)
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
        }

        private fun encounterJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("patient_id", row.getString("patient_id"))
                .put("encounter_type", row.getString("encounter_type"))
                .put("encounter_no", row.getString("encounter_no"))
                .put("department", row.getString("department"))
                .put("ward", row.getString("ward"))
                .put("admit_date", row.getOffsetDateTime("admit_date")?.toString())
                .put("discharge_date", row.getOffsetDateTime("discharge_date")?.toString())
                .put("death_date", row.getOffsetDateTime("death_date")?.toString())
                .put("death_cause", row.getString("death_cause"))
                .put("admitting_diagnosis", row.getString("admitting_diagnosis"))
                .put("discharge_diagnosis", row.getString("discharge_diagnosis"))
                .put("attending_physician", row.getString("attending_physician"))
                .put("status", row.getString("status"))
                .put("settled_at", row.getOffsetDateTime("settled_at")?.toString())
                .put("discharged_by", row.getString("discharged_by"))
                .put("discharged_at", row.getOffsetDateTime("discharged_at")?.toString())
                .put("deceased_by", row.getString("deceased_by"))
                .put("deceased_at", row.getOffsetDateTime("deceased_at")?.toString())
                .put("metadata", row.getValue("metadata"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    fun createPatient(body: JsonObject): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val personType = try {
            personTypeFrom(body).also { validatePersonType(it) }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        // 响应统一走 getPatient，保证含 person_type。
        return pool.withTransaction<JsonObject> { connection ->
            val (sql, args) = patientInsert(body, id, now, personType)
            execute(connection, sql, args)
                .compose { getPatient(connection, id) }
        }
    }

    fun listPatients(
        name: String?,
        status: String?,
        personType: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val conditions = mutableListOf<String>()
        name?.takeIf(String::isNotBlank)?.let { conditions += "healthcare.patients.name ILIKE '%' || ${bind(it)} || '%'" }
        status?.takeIf(String::isNotBlank)?.let { conditions += "healthcare.patients.status = ${bind(it)}" }
        personType?.takeIf(String::isNotBlank)?.let { conditions += "healthcare.patients.person_type = ${bind(it)}" }
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"

        val countSql = "SELECT count(*) AS total FROM healthcare.patients$where"
        val dataSql = "SELECT $patientColumns FROM healthcare.patients$where " +
            "ORDER BY healthcare.patients.created_at DESC OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                val records = rows.iterator().asSequence().toList()
                JsonObject()
                    .put("records", JsonArray(records.map { patientJson(it) }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getPatient(id: String): Future<JsonObject> = getPatient(pool, id)

    fun updatePatient(id: String, body: JsonObject): Future<JsonObject> {
        try {
            validatePatientUpdate(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return getPatient(id).compose { existing ->
            val finalPersonType = if (body.containsKey("person_type")) {
                personTypeFrom(body)
            } else {
                existing.getString("person_type") ?: PERSON_TYPE_DEFAULT
            }
            try {
                validatePersonType(finalPersonType)
            } catch (error: IllegalArgumentException) {
                return@compose Future.failedFuture<JsonObject>(error)
            }
            val now = OffsetDateTime.now()
            pool.withTransaction<JsonObject> { connection ->
                val (sql, args) = patientUpdate(body, id, now)
                execute(connection, sql, args)
                    .compose { getPatient(connection, id) }
            }
        }
    }

    fun createEncounter(body: JsonObject, attendingPhysician: String): Future<JsonObject> {
        val patientId = try {
            requiredText(body, "patient_id")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        // 029：入住区间/床位校验与插入必须原子（`admitElderly` 已是事务内，不重复嵌套）
        return pool.withTransaction<JsonObject> { connection ->
            getPatient(connection, patientId).compose {
                createEncounter(connection, body, patientId, attendingPhysician)
            }
        }
    }

    fun listEncounters(
        patientId: String?,
        encounterType: String?,
        status: String?,
        limit: Int,
        offset: Int,
        search: String? = null,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val conditions = mutableListOf<String>()
        patientId?.takeIf(String::isNotBlank)?.let { conditions += "healthcare.encounters.patient_id = ${bind(it)}" }
        encounterType?.takeIf(String::isNotBlank)?.let { conditions += "healthcare.encounters.encounter_type = ${bind(it)}" }
        status?.takeIf(String::isNotBlank)?.let { conditions += "healthcare.encounters.status = ${bind(it)}" }
        search?.takeIf(String::isNotBlank)?.let {
            val index = args.size + 1
            args.add(it)
            conditions += "(healthcare.encounters.encounter_no ILIKE '%' || \$$index || '%' " +
                "OR healthcare.patients.name ILIKE '%' || \$$index || '%' " +
                "OR healthcare.patients.id_card_no ILIKE '%' || \$$index || '%')"
        }
        val wherePart = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val from = " FROM healthcare.encounters JOIN healthcare.patients ON healthcare.patients.id = healthcare.encounters.patient_id"

        val countSql = "SELECT count(*) AS total$from$wherePart"
        val dataSql = "SELECT $encounterColumns$from$wherePart " +
            "ORDER BY healthcare.encounters.admit_date DESC, healthcare.encounters.created_at DESC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::encounterJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getEncounter(id: String): Future<JsonObject> = getEncounter(pool, id)

    /**
     * 床位占用只读查询（029 §4.1）：当前仍占用中的养老入住
     * （`encounter_type = 'ELDERLY_CARE'` 且 `discharge_date IS NULL OR discharge_date > now`），
     * 按 `department, ward, admit_date` 升序。`department`/`ward` 传入时按 `trim` 后精确过滤
     * （大小写敏感、不做全角归一，见 029 残余 R1）。空结果仍返回 `records: []` 与 `total: 0`。
     */
    fun listBedOccupancy(
        department: String?,
        ward: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val now = OffsetDateTime.now()
        val args = mutableListOf<Any?>("ELDERLY_CARE", now)
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val conditions = mutableListOf(
            "healthcare.encounters.encounter_type = \$1",
            "(healthcare.encounters.discharge_date IS NULL OR healthcare.encounters.discharge_date > " +
                "CAST(\$2 AS TIMESTAMPTZ))",
        )
        department?.trim()?.takeIf(String::isNotBlank)?.let {
            conditions += "healthcare.encounters.department = ${bind(it)}"
        }
        ward?.trim()?.takeIf(String::isNotBlank)?.let {
            conditions += "healthcare.encounters.ward = ${bind(it)}"
        }
        val wherePart = " WHERE ${conditions.joinToString(" AND ")}"

        val countSql = "SELECT count(*) AS total FROM healthcare.encounters$wherePart"
        val dataSql = "SELECT healthcare.encounters.id AS encounter_id, healthcare.encounters.encounter_no, healthcare.encounters.patient_id, " +
            "healthcare.patients.name AS patient_name, healthcare.encounters.department, healthcare.encounters.ward, healthcare.encounters.admit_date, healthcare.encounters.discharge_date, healthcare.encounters.status " +
            "FROM healthcare.encounters JOIN healthcare.patients ON healthcare.patients.id = healthcare.encounters.patient_id$wherePart " +
            "ORDER BY healthcare.encounters.department ASC, healthcare.encounters.ward ASC, healthcare.encounters.admit_date ASC " +
            "OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put(
                        "records",
                        JsonArray(
                            rows.map { row ->
                                JsonObject()
                                    .put("encounter_id", row.getString("encounter_id"))
                                    .put("encounter_no", row.getString("encounter_no"))
                                    .put("patient_id", row.getString("patient_id"))
                                    .put("patient_name", row.getString("patient_name"))
                                    .put("department", row.getString("department"))
                                    .put("ward", row.getString("ward"))
                                    .put("admit_date", row.getOffsetDateTime("admit_date")?.toString())
                                    .put("discharge_date", row.getOffsetDateTime("discharge_date")?.toString())
                                    .put("status", row.getString("status"))
                            },
                        ),
                    )
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun updateEncounter(id: String, body: JsonObject): Future<JsonObject> {
        validateEncounterUpdate(body)
        // 029 §2.1-2：改 department/ward/status 时必须在同一事务内先 `FOR UPDATE` 锁定，
        // 再按同一套区间/床位口径复检，否则「已离院记录改回 ACTIVE」会绕过全部入住门禁。
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, id).compose { encounter ->
                val now = OffsetDateTime.now()
                val patientId = requireNotNull(encounter.getString("patient_id"))
                val isElderly = encounter.getString("encounter_type") == "ELDERLY_CARE"
                val touchSlot =
                    body.containsKey("department") || body.containsKey("ward") || body.containsKey("status")

                val slotCheck: Future<Void> = if (isElderly && touchSlot) {
                    val admitDate = encounter.getString("admit_date")
                        ?: return@compose Future.failedFuture(
                            IllegalArgumentException("admit_date is required to validate admission interval"),
                        )
                    ensureAdmissionSlotAvailable(
                        connection,
                        patientId,
                        if (body.containsKey("department")) body.getString("department") else encounter.getString("department"),
                        if (body.containsKey("ward")) body.getString("ward") else encounter.getString("ward"),
                        offsetDateTime(admitDate, "admit_date"),
                        encounter.getString("discharge_date")?.let { offsetDateTime(it, "discharge_date") },
                        id,
                    )
                } else {
                    Future.succeededFuture()
                }

                // 惰性构造：Vert.x 的 execute() 立即发查询，若在此处直接调用会把 activeCheck 的
                // SELECT 发到咨询锁外面（评审 P2-2），故包成 () -> Future 在取锁后调用。
                val activeCheck: () -> Future<Void> =
                    if (isElderly && body.getString("status") == "ACTIVE") {
                        { ensureNoOtherActiveElderlyAdmission(connection, patientId, id) }
                    } else {
                        { Future.succeededFuture() }
                    }

                slotCheck.compose { activeCheck() }
                    .compose {
                        val (sql, args) = encounterUpdate(body, id, now)
                        execute(connection, sql, args)
                    }
                    .compose { getEncounter(connection, id) }
            }
        }
    }

    fun dischargeEncounter(id: String, body: JsonObject, operator: String? = null): Future<JsonObject> {
        val dischargeDate = body.getString("discharge_date")?.let { offsetDateTime(it, "discharge_date") }
            ?: OffsetDateTime.now()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, id).compose { encounter ->
                if (encounter.getString("status") == "DISCHARGED") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is already discharged"))
                }

                // 养老入住必须在同一事务中收束精确关联的照护周期
                val closePeriodFuture: Future<Void> =
                    if (encounter.getString("encounter_type") == "ELDERLY_CARE") {
                        medicalOrderService
                            .terminateEncounterOrders(connection, id, dischargeDate)
                            .map<Void> { null }
                            .compose {
                                servicePeriodService
                                    .closeElderlyCarePeriod(connection, id, businessDate(dischargeDate), now)
                                    .map<Void> { null }
                            }
                    } else {
                        Future.succeededFuture()
                    }

                closePeriodFuture.compose {
                    // 023 决策 A：离院只标记业务事实与照护流程收尾（医嘱终止、护理周期关闭、
                    // 状态/日期/诊断写入），**不再收束账单**。原先在此同事务生成区间最终账单
                    // 并冻结全部账单，使 settled_at 在离院时即被置位，养老收费页「结算收束」
                    // 因此永不可达，押金核销与减免门禁形同虚设。
                    // 账单收尾统一由「结算收束」（settleEncounterBilling，显式三步）承担。
                    // V524：离院操作人留痕。operator 取认证中间件写入的 userId，客户端不得提交；
                    // *_at 用服务端 now，与可回填的业务日期 discharge_date 区分。
                    val args = mutableListOf<Any?>(
                        dischargeDate,
                        body.getString("discharge_diagnosis"),
                        "DISCHARGED",
                        now,
                        now,
                    )
                    var sets = "discharge_date = \$1, discharge_diagnosis = \$2, status = \$3, " +
                        "discharged_at = \$4, updated_at = \$5"
                    if (!operator.isNullOrBlank()) {
                        args.add(operator)
                        sets += ", discharged_by = \$${args.size}"
                    }
                    args.add(id)
                    execute(
                        connection,
                        "UPDATE healthcare.encounters SET $sets " +
                            "WHERE healthcare.encounters.id = \$${args.size}",
                        Tuple.from(args),
                    ).compose { getEncounter(connection, id) }
                }
            }
        }
    }

    fun deathEncounter(id: String, body: JsonObject, operator: String? = null): Future<JsonObject> {
        val deathDate = try {
            offsetDateTime(requiredText(body, "death_date"), "death_date")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val deathCause = try {
            body.getValue("death_cause")?.let { value ->
                if (value !is String) throw IllegalArgumentException("death_cause must be a string")
                value.trim().takeIf { it.isNotBlank() }?.let { trimmed ->
                    if (trimmed.length > 500) throw IllegalArgumentException("death_cause must not exceed 500 characters")
                    trimmed
                }
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, id).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }

                val patientId = requireNotNull(encounter.getString("patient_id"))
                val now = OffsetDateTime.now()
                ensureNoOtherActiveElderlyAdmission(connection, patientId, id)
                    .compose {
                        medicalOrderService.terminateEncounterOrders(connection, id, deathDate)
                    }
                    .compose {
                        servicePeriodService.closeElderlyCarePeriod(connection, id, businessDate(deathDate), now)
                    }
                    .compose {
                        // 023 决策 A：去世与离院同构，只标记事实与照护流程收尾，
                        // **不再收束账单**（账单收尾统一由 settleEncounterBilling 承担）。
                        // V524：去世操作人留痕，口径同离院（*_at 为服务端确认时刻，
                        // death_date 为可回填的业务日期）。
                        val args = mutableListOf<Any?>(deathDate, "DECEASED", now, now)
                        var sets = "death_date = \$1, status = \$2, deceased_at = \$3, updated_at = \$4"
                        if (deathCause != null) {
                            args.add(deathCause)
                            sets += ", death_cause = \$${args.size}"
                        }
                        if (!operator.isNullOrBlank()) {
                            args.add(operator)
                            sets += ", deceased_by = \$${args.size}"
                        }
                        args.add(id)
                        execute(
                            connection,
                            "UPDATE healthcare.encounters SET $sets " +
                                "WHERE healthcare.encounters.id = \$${args.size}",
                            Tuple.from(args),
                        )
                    }
                    .compose {
                        execute(
                            connection,
                            "UPDATE healthcare.patients SET status = \$1, updated_at = \$2 " +
                                "WHERE healthcare.patients.id = \$3",
                            Tuple.of("DECEASED", now, patientId),
                        )
                    }
                    .compose {
                        getEncounter(connection, id)
                    }
            }
        }
    }

    private fun businessDate(value: OffsetDateTime): LocalDate =
        value.atZoneSameInstant(businessZone).toLocalDate()

    /**
     * 结算收束（显式三步，021）：已离院/去世但未结算的养老入住 → 生成区间最终账单（`待缴费`）
     * → 押金核销 → 判定未结 → 冻结全部账单。
     * 已全部结算 409；未离院/去世 409；非养老入住 400。
     *
     * 023 决策 A 起这是**账单收尾的唯一入口**：离院/去世本身只标记事实，不再收束账单，
     * 因此「已离院未收束」是一段正常的可收费窗口，收束由本方法显式触发并强制留痕。
     *
     * 请求体只允许 `{"deposit_offset": <金额>, "write_off_reason": "<原因>"}`（其他键 400）。
     * **次序**：区间最终账单先建（`待缴费`），核销在最终账单之后、减免判定在核销之后
     * （021 刻意变更 020 的「先核销后收束」，使最终账期同样可被核销）。
     * 核销、未结判定与冻结在**同一事务、同一连接**内完成；任一环节失败整笔回滚
     * （区间最终账单、押金台账、缴费流水、账单状态、settled_at 全部撤销）。
     * 核销后仍有未结余额且未提供 `write_off_reason` → 409（不允许静默把未结账单置 已结算）；
     * 提供原因时，全部未结账单写入同一条 trim 后的原因。
     */
    fun settleEncounterBilling(id: String, body: JsonObject, operator: String): Future<JsonObject> {
        val request = try {
            BillService.parseSettlementRequest(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction { connection ->
            val now = OffsetDateTime.now()
            billService.prepareSettlement(connection, id, now, requireTerminalStatus = true).compose {
                depositOffsetService.offsetArrears(connection, id, request.depositOffset, operator, now)
            }.compose {
                billService.outstandingBills(connection, id)
            }.compose { outstanding ->
                val total = outstanding.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.balance) }
                if (total.signum() > 0 && request.writeOffReason == null) {
                    Future.failedFuture(
                        ConflictException("unsettled bills require explicit write-off: outstanding $total"),
                    )
                } else {
                    billService.freezeSettlement(connection, id, now, outstanding, request.writeOffReason)
                }
            }
        }
    }

    /**
     * 收束预览（只读）：与执行路径共用同一套资格校验、区间解析与金额计算，
     * **不产生任何写入**，可反复调用。字段见 [BillService.previewSettlement]。
     */
    fun previewEncounterBilling(id: String): Future<JsonObject> =
        pool.withTransaction { connection ->
            billService.previewSettlement(connection, id, requireTerminalStatus = true)
        }

    // ——— 医嘱读取/创建/状态机委托（实现位于 MedicalOrderService） ———
    fun createOrder(encounterId: String, body: JsonObject): Future<JsonObject> =
        medicalOrderService.createOrder(encounterId, body)

    fun listOrders(
        encounterId: String,
        orderType: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> = medicalOrderService.listOrders(encounterId, orderType, status, limit, offset)

    fun getOrder(id: String): Future<JsonObject> = medicalOrderService.getOrder(id)

    /** 医嘱给药明细列表（只读，含批次/药品来源摘要） */
    fun getOrderAdministrations(id: String): Future<JsonObject> =
        medicalOrderService.listOrderAdministrations(id)

    fun updateOrderStatus(id: String, body: JsonObject): Future<JsonObject> =
        medicalOrderService.updateOrderStatus(id, body)

    fun nurseCheckOrder(id: String, userId: String, body: JsonObject): Future<JsonObject> =
        medicalOrderService.nurseCheckOrder(id, userId, body)

    /** 027 到期医嘱显式收束（幂等）：委托 MedicalOrderService，保持医嘱写入单一入口 */
    fun closeExpiredOrders(body: JsonObject): Future<JsonObject> =
        medicalOrderService.closeExpiredOrders(body)

    /** 护士核对汇总列表：跨入住待核对用药医嘱，供护理汇总页核对 */
    fun listPendingNurseCheckOrders(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> =
        medicalOrderService.listPendingNurseCheckOrders(client, encounterId, search, limit, offset)

    // ——— 011 药房接方/发药内部端口（只供 App 编排调用，不注册为 Healthcare 路由） ———

    fun listMedicationOrdersForPharmacy(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> =
        medicalOrderService.listMedicationOrdersForPharmacy(client, encounterId, search, limit, offset)

    fun lockMedicationOrderForPharmacy(client: SqlClient, medicalOrderId: String): Future<MedicationOrderLockSnapshot> =
        medicalOrderService.lockMedicationOrderForPharmacy(client, medicalOrderId)

    /**
     * 025 存量自由文本医嘱一次性补绑目录药品（同连接写，供药房外层事务编排）。
     * 只写 `order_details` 与 `updated_at`；发药单、库存出库与补绑同事务提交或回滚。
     */
    fun bindDrugMaterial(
        client: SqlClient,
        medicalOrderId: String,
        materialId: String,
        materialCode: String?,
        materialName: String?,
        operator: String,
    ): Future<Void?> =
        medicalOrderService.bindDrugMaterial(
            client,
            medicalOrderId,
            materialId,
            materialCode,
            materialName,
            operator,
        )

    // ========================================================================
    //  医生病程记录 (progress_notes)
    // ========================================================================

    /** 创建医生病程记录：只允许活动养老入住，追加写入、只读历史语义 */
    fun createProgressNote(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateProgressNoteInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                val id = Ulid.generate()
                val now = OffsetDateTime.now()
                // 慢病病程：校验档案存在且属于该老人（不同老人/不同档案严格隔离）
                val chronicCheck: Future<Void> = if (input.noteType == "CHRONIC") {
                    ensureChronicRegistrationBelongs(connection, input.chronicDiseaseId!!, encounter.getString("patient_id"))
                } else {
                    Future.succeededFuture()
                }
                chronicCheck.compose {
                    execute(
                        connection,
                        "INSERT INTO healthcare.progress_notes " +
                            "(id, encounter_id, note_type, content, physician, record_time, created_at, metadata) " +
                            "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8)",
                        Tuple.of(
                            id,
                            encounterId,
                            input.noteType,
                            input.content,
                            input.physician,
                            input.recordTime,
                            now,
                            input.metadata,
                        ),
                    ).map {
                        progressNoteResponse(id, encounterId, input, now)
                    }
                }
            }
        }
    }

    /** 按精确 encounter_id 读取病程记录，record_time DESC 分页 */
    fun listProgressNotes(
        encounterId: String,
        noteType: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>(encounterId)
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val conditions = mutableListOf("encounter_id = \$1")
        noteType?.takeIf(String::isNotBlank)?.let { conditions.add("note_type = ${bind(it)}") }
        val from = try {
            dateFrom?.takeIf(String::isNotBlank)?.let {
                LocalDate.parse(it).atStartOfDay(businessZone).toOffsetDateTime()
            }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_from"))
        }
        val to = try {
            dateTo?.takeIf(String::isNotBlank)?.let {
                LocalDate.parse(it).plusDays(1).atStartOfDay(businessZone).toOffsetDateTime()
            }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_to"))
        }
        from?.let {
            conditions.add("healthcare.progress_notes.record_time >= CAST(${bind(it)} AS TIMESTAMPTZ)")
        }
        to?.let {
            conditions.add("healthcare.progress_notes.record_time < CAST(${bind(it)} AS TIMESTAMPTZ)")
        }
        val wherePart = " WHERE ${conditions.joinToString(" AND ")}"

        val countSql = "SELECT count(*) AS total FROM healthcare.progress_notes$wherePart"
        val dataSql = "SELECT $progressNoteColumns FROM healthcare.progress_notes$wherePart " +
            "ORDER BY record_time DESC, created_at DESC OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::progressNoteJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 病程记录详情：按 ID 读取，不校验入住状态（历史只读） */
    fun getProgressNote(id: String): Future<JsonObject> =
        execute(
            pool,
            "SELECT $progressNoteColumns FROM healthcare.progress_notes WHERE id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(progressNoteJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("progress note not found: $id"))
        }

    // ========================================================================
    //  诊断 (diagnoses)
    // ========================================================================

    /** 创建诊断：只允许活动养老入住，追加写入、不提供覆盖式编辑和删除 */
    fun createDiagnosis(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateDiagnosisInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                val id = Ulid.generate()
                val now = OffsetDateTime.now()
                // is_major 由 diagnosis_type 派生，不接受客户端取值；remark 落 metadata
                val metadata = input.remark?.let { JsonObject().put("remark", it) }
                execute(
                    connection,
                    "INSERT INTO healthcare.diagnoses " +
                        "(id, encounter_id, diagnosis_type, diagnosis_text, diagnosis_date, physician, " +
                        "created_at, icd_code, is_major, metadata) " +
                        "VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9, \$10)",
                    Tuple.of(
                        id,
                        encounterId,
                        input.diagnosisType,
                        input.diagnosisText,
                        input.diagnosisDate,
                        input.physician,
                        now,
                        input.icdCode,
                        isMajorDiagnosis(input.diagnosisType),
                        metadata,
                    ),
                ).map {
                    diagnosisResponse(id, encounterId, input, now)
                }
            }
        }
    }

    /** 按精确 encounter_id 读取诊断，诊断日期倒序分页 */
    fun listDiagnoses(
        encounterId: String,
        diagnosisType: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>(encounterId)
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val conditions = mutableListOf("encounter_id = \$1")
        diagnosisType?.takeIf(String::isNotBlank)?.let { conditions.add("diagnosis_type = ${bind(it)}") }
        val wherePart = " WHERE ${conditions.joinToString(" AND ")}"

        val countSql = "SELECT count(*) AS total FROM healthcare.diagnoses$wherePart"
        val dataSql = "SELECT $diagnosisColumns FROM healthcare.diagnoses$wherePart " +
            "ORDER BY diagnosis_date DESC, created_at DESC OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map(::diagnosisJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 诊断详情：按 ID 读取，不校验入住状态（历史只读） */
    fun getDiagnosis(id: String): Future<JsonObject> =
        execute(
            pool,
            "SELECT $diagnosisColumns FROM healthcare.diagnoses WHERE id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(diagnosisJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("diagnosis not found: $id"))
        }

    // ========================================================================
    //  复评与照护计划修订
    // ========================================================================

    /**
     * 在单个数据库事务内完成：锁定 encounter/period → 锁定唯一活动计划 →
     * 检查 IN_PROGRESS 执行 → 写入复评 → 收束旧计划/措施/任务 →
     * 创建新计划/措施/任务 → 写入修订关系。任一步失败整笔回滚。
     */
    fun createCarePlanRevision(
        encounterId: String,
        body: JsonObject,
        assessor: String? = null,
        createdBy: String? = null,
    ): Future<JsonObject> {
        val input = try {
            validateCarePlanRevisionInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                val patientId = requireNotNull(encounter.getString("patient_id"))
                carePlanRevisionService
                    .lockPeriodForEncounter(connection, encounterId, patientId)
                    .compose { period ->
                        val periodId = requireNotNull(period.getString("id"))
                        val periodStartDate = period.getString("start_date")?.let { LocalDate.parse(it) }
                        if (periodStartDate != null && input.assessment.assessDate.isBefore(periodStartDate)) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("assess_date cannot be earlier than the care period start date")
                            )
                        }
                        val today = businessDate(OffsetDateTime.now())
                        if (input.assessment.assessDate.isAfter(today)) {
                            return@compose Future.failedFuture(IllegalArgumentException("assess_date cannot be in the future"))
                        }
                        if (input.plan.startDate.isBefore(input.assessment.assessDate)) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("plan.start_date cannot be earlier than assess_date")
                            )
                        }
                        if (input.plan.endDate != null && input.plan.endDate.isBefore(input.plan.startDate)) {
                            return@compose Future.failedFuture(
                                IllegalArgumentException("plan.end_date cannot be earlier than start_date")
                            )
                        }

                        planService.lockActivePlan(connection, periodId).compose { oldPlan ->
                            val oldPlanId = requireNotNull(oldPlan.getString("id"))
                            planService.lockActivePlanItems(connection, oldPlanId).compose { _ ->
                                taskService.lockActivePlanTasks(connection, oldPlanId).compose { oldTasks ->
                                    val oldTaskIds = oldTasks.map { it.getString("id")!! }
                                    carePlanRevisionService.checkNoInProgressExecution(connection, oldTaskIds).compose {
                                        carePlanRevisionService.nextRevisionNo(connection, periodId).compose { revisionNo ->
                                            carePlanRevisionService.createAssessment(
                                                connection,
                                                CarePlanRevisionService.AssessmentCreateInput(
                                                    encounterId = encounterId,
                                                    periodId = periodId,
                                                    assessType = input.assessment.assessType,
                                                    assessDate = input.assessment.assessDate,
                                                    // 042：评估人由路由层从认证会话派生后传入（请求体同名键不再作为权威值）
                                                    assessor = assessor,
                                                    totalScore = input.assessment.totalScore,
                                                    resultLevel = input.assessment.resultLevel,
                                                    detail = input.assessment.detail,
                                                    remark = input.assessment.remark,
                                                ),
                                            ).compose { assessment ->
                                                planService.terminatePlan(connection, oldPlanId).compose {
                                                    taskService.cancelPlanTasks(connection, oldTaskIds).compose {
                                                        planService.createPlanWithItems(
                                                            connection,
                                                            PlanService.PlanCreateInput(
                                                                periodId = periodId,
                                                                encounterId = encounterId,
                                                                planName = input.plan.planName,
                                                                goals = input.plan.goals,
                                                                // 042：制定人同评估人口径，由路由层注入
                                                                createdBy = createdBy,
                                                                startDate = input.plan.startDate,
                                                                endDate = input.plan.endDate,
                                                                items = input.plan.items.map { item ->
                                                                    PlanService.PlanItemInput(
                                                                        action = item.action,
                                                                        frequencyCode = item.frequencyCode,
                                                                        frequencyName = item.frequencyName,
                                                                        durationDays = item.durationDays,
                                                                        remark = item.remark,
                                                                    )
                                                                },
                                                            ),
                                                        ).compose { newPlan ->
                                                            val newPlanId = requireNotNull(newPlan.getString("id"))
                                                            createPlanTasks(
                                                                connection,
                                                                periodId,
                                                                encounterId,
                                                                newPlan.getJsonArray("items") ?: JsonArray(),
                                                                input,
                                                            ).compose { tasks ->
                                                                    carePlanRevisionService.insertRevision(
                                                                        connection,
                                                                        periodId,
                                                                        encounterId,
                                                                        requireNotNull(assessment.getString("id")),
                                                                        oldPlanId,
                                                                        newPlanId,
                                                                        revisionNo,
                                                                    ).map { revision ->
                                                                        JsonObject()
                                                                            .put("revision_id", revision.getString("id"))
                                                                            .put("revision_no", revisionNo)
                                                                            .put("assessment", assessment)
                                                                            .put("previous_plan", JsonObject()
                                                                                .put("id", oldPlanId)
                                                                                .put("status", "DISCONTINUED"))
                                                                            .put("plan", newPlan)
                                                                            .put("items", newPlan.getJsonArray("items") ?: JsonArray())
                                                                            .put("tasks", tasks)
                                                                    }
                                                                }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    /** 修订历史列表：按修订号倒序，空列表返回空 records 和 total 0 */
    fun listCarePlanRevisions(encounterId: String): Future<JsonObject> =
        carePlanRevisionService.listRevisions(encounterId)

    /** 修订历史详情：按修订 ID 读取，服务端再次校验 period/encounter 归属 */
    fun getCarePlanRevision(id: String): Future<JsonObject> =
        carePlanRevisionService.getRevision(id)

    /**
     * 为新计划每条措施派生一条 `NURSING` 任务，精确写入 plan_item_id / period_id / encounter_id；
     * 任务结束日期按措施 duration_days 计算（计划开始日 + 天数），未提供时为空。
     */
    private fun createPlanTasks(
        connection: SqlClient,
        periodId: String,
        encounterId: String,
        planItems: JsonArray,
        input: RevisionCreateInput,
    ): Future<JsonArray> {
        val newPlan = input.plan
        fun loop(index: Int, acc: JsonArray): Future<JsonArray> {
            if (index >= planItems.size()) return Future.succeededFuture(acc)
            val item = planItems.getJsonObject(index)
            val itemId = requireNotNull(item.getString("id"))
            val durationDays = item.getInteger("duration_days")
            val taskEndDate = durationDays?.let { newPlan.startDate.plusDays(it.toLong()) }
            return taskService.createPlanTask(
                connection,
                TaskService.PlanTaskInput(
                    periodId = periodId,
                    encounterId = encounterId,
                    planItemId = itemId,
                    description = requireNotNull(item.getString("action")),
                    frequencyCode = item.getString("frequency_code"),
                    frequencyName = item.getString("frequency_name"),
                    startDate = newPlan.startDate,
                    endDate = taskEndDate,
                ),
            ).compose { task ->
                loop(index + 1, acc.add(task))
            }
        }
        return loop(0, JsonArray())
    }

    private fun validateCarePlanRevisionInput(body: JsonObject): RevisionCreateInput {
        rejectUnknownKeys(body, setOf("assessment", "plan"), "request")

        val assessmentValue = body.getValue("assessment")
        if (assessmentValue !is JsonObject) throw IllegalArgumentException("assessment is required")
        rejectUnknownKeys(
            assessmentValue,
            setOf("assess_type", "assess_date", "assessor", "total_score", "result_level", "detail", "remark"),
            "assessment",
        )

        val assessType = requiredText(assessmentValue, "assess_type")
        if (!CarePlanRevisionService.isValidAssessType(assessType)) {
            throw IllegalArgumentException(
                "invalid assess_type, must be one of: ${CarePlanRevisionService.VALID_ASSESS_TYPES}"
            )
        }
        val assessDate = localDate(requiredText(assessmentValue, "assess_date"), "assess_date")
        val assessor = optionalText(assessmentValue, "assessor")
        val totalScore = optionalNumber(assessmentValue, "total_score")
        val resultLevel = optionalText(assessmentValue, "result_level")
        val detail = optionalJsonObject(assessmentValue, "detail")
        val remark = optionalText(assessmentValue, "remark")

        val planValue = body.getValue("plan")
        if (planValue !is JsonObject) throw IllegalArgumentException("plan is required")
        rejectUnknownKeys(
            planValue,
            setOf("plan_name", "goals", "created_by", "start_date", "end_date", "items"),
            "plan",
        )

        val planName = requiredText(planValue, "plan_name")
        val goals = optionalText(planValue, "goals")
        val createdBy = optionalText(planValue, "created_by")
        val startDate = localDate(requiredText(planValue, "start_date"), "start_date")
        val endDate = planValue.getValue("end_date")?.let {
            if (it !is String) throw IllegalArgumentException("end_date must be a string")
            localDate(it, "end_date")
        }
        val items = parsePlanItems(planValue.getValue("items"))

        return RevisionCreateInput(
            RevisionAssessmentInput(assessType, assessDate, assessor, totalScore, resultLevel, detail, remark),
            RevisionPlanInput(planName, goals, createdBy, startDate, endDate, items),
        )
    }

    private fun parsePlanItems(value: Any?): List<RevisionPlanItemInput> {
        if (value == null) return emptyList()
        if (value !is JsonArray) throw IllegalArgumentException("plan.items must be an array")
        val result = mutableListOf<RevisionPlanItemInput>()
        for (raw in value) {
            if (raw !is JsonObject) throw IllegalArgumentException("plan items must be objects")
            rejectUnknownKeys(raw, setOf("action", "frequency_code", "frequency_name", "duration_days", "remark"), "plan items")
            val action = requiredText(raw, "action")
            val frequencyCode = optionalText(raw, "frequency_code")
            val frequencyName = optionalText(raw, "frequency_name")
            val durationDays = raw.getValue("duration_days")?.let { v ->
                if (v !is Int && v !is Long) throw IllegalArgumentException("duration_days must be a non-negative integer")
                val days = (v as Number).toInt()
                if (days < 0) throw IllegalArgumentException("duration_days must be a non-negative integer")
                days
            }
            val remark = optionalText(raw, "remark")
            result.add(RevisionPlanItemInput(action, frequencyCode, frequencyName, durationDays, remark))
        }
        return result
    }

    private fun rejectUnknownKeys(obj: JsonObject, allowed: Set<String>, label: String) {
        val unknown = obj.fieldNames().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("unsupported keys in $label: ${unknown.joinToString(", ")}")
        }
    }

    private fun optionalText(obj: JsonObject, key: String): String? {
        if (!obj.containsKey(key) || obj.getValue(key) == null) return null
        val value = obj.getValue(key)
        if (value !is String) throw IllegalArgumentException("$key must be a string")
        return value.trim().takeIf(String::isNotBlank)
    }

    private fun optionalNumber(obj: JsonObject, key: String): Double? {
        if (!obj.containsKey(key) || obj.getValue(key) == null) return null
        val value = obj.getValue(key)
        if (value !is Number) throw IllegalArgumentException("$key must be a number")
        return value.toDouble()
    }

    private fun optionalJsonObject(obj: JsonObject, key: String): JsonObject? {
        if (!obj.containsKey(key) || obj.getValue(key) == null) return null
        val value = obj.getValue(key)
        if (value !is JsonObject) throw IllegalArgumentException("$key must be a JSON object")
        return value
    }

    private data class RevisionAssessmentInput(
        val assessType: String,
        val assessDate: LocalDate,
        val assessor: String?,
        val totalScore: Double?,
        val resultLevel: String?,
        val detail: JsonObject?,
        val remark: String?,
    )

    private data class RevisionPlanItemInput(
        val action: String,
        val frequencyCode: String?,
        val frequencyName: String?,
        val durationDays: Int?,
        val remark: String?,
    )

    private data class RevisionPlanInput(
        val planName: String,
        val goals: String?,
        val createdBy: String?,
        val startDate: LocalDate,
        val endDate: LocalDate?,
        val items: List<RevisionPlanItemInput>,
    )

    private data class RevisionCreateInput(
        val assessment: RevisionAssessmentInput,
        val plan: RevisionPlanInput,
    )

    fun admitElderly(body: JsonObject, attendingPhysician: String): Future<JsonObject> {
        val patientId = body.getString("patient_id")?.takeIf(String::isNotBlank)
        val patient = body.getJsonObject("patient")
        if ((patientId == null) == (patient == null)) {
            return Future.failedFuture(IllegalArgumentException("provide exactly one of patient_id or patient"))
        }
        try {
            requiredText(body, "encounter_no")
            requiredText(body, "admit_date")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<JsonObject> { connection ->
            val patientFuture: Future<JsonObject> = if (patientId != null) {
                getPatient(connection, patientId)
            } else {
                val patientBody = requireNotNull(patient)
                val id = Ulid.generate()
                val now = OffsetDateTime.now()
                val (patientSql, patientArgs) = patientInsert(patientBody, id, now)
                execute(connection, patientSql, patientArgs)
                    .map { patientResponse(patientBody, id, now) }
            }

            patientFuture.compose { resident ->
                val residentId = requireNotNull(resident.getString("id"))
                ensureNoActiveElderlyAdmission(connection, residentId)
                    .compose { createEncounter(connection, body, residentId, attendingPhysician, "ELDERLY_CARE") }
                    .compose { encounter ->
                        val encounterId = requireNotNull(encounter.getString("id"))
                        // admit_date 是周期唯一的开始日期来源
                        val admitDate = try {
                            offsetDateTime(requiredText(body, "admit_date"), "admit_date")
                        } catch (error: IllegalArgumentException) {
                            return@compose Future.failedFuture(error)
                        }
                        servicePeriodService
                            .createElderlyCarePeriod(connection, residentId, encounterId, businessDate(admitDate), OffsetDateTime.now())
                            .map<JsonObject> { (_, nursingPeriod) ->
                                JsonObject()
                                    .put("patient", resident)
                                    .put("encounter", encounter)
                                    .put("nursing_period", nursingPeriod)
                            }
                    }
            }
        }
    }

    private fun lockEncounter(client: SqlClient, id: String): Future<JsonObject> =
        execute(
            client,
            "SELECT $encounterColumns FROM healthcare.encounters WHERE healthcare.encounters.id = \$1 FOR UPDATE",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(encounterJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    private fun getPatient(client: SqlClient, id: String): Future<JsonObject> =
        execute(
            client,
            "SELECT $patientColumns FROM healthcare.patients WHERE healthcare.patients.id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(patientJson(row))
            } ?: Future.failedFuture(HealthcareNotFoundException("patient not found: $id"))
        }

    private fun getEncounter(client: SqlClient, id: String): Future<JsonObject> =
        execute(
            client,
            "SELECT $encounterColumns FROM healthcare.encounters WHERE healthcare.encounters.id = \$1",
            Tuple.of(id),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(encounterJson(it)) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    private fun ensureNoActiveElderlyAdmission(client: SqlClient, patientId: String): Future<Void> {
        val sql = "SELECT 1 AS hit, healthcare.encounters.encounter_no FROM healthcare.encounters " +
            "WHERE healthcare.encounters.patient_id = \$1 " +
            "AND healthcare.encounters.encounter_type = \$2 AND healthcare.encounters.status = \$3"
        return execute(client, sql, Tuple.of(patientId, "ELDERLY_CARE", "ACTIVE")).compose { rows ->
            // 存在性以「是否命中行」判定；住院号只用于消息（无号时省略该段）
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.succeededFuture()
            } else {
                // 029 §4.2：已有活动入住是冲突（409），消息带上既有住院号便于前端提示
                Future.failedFuture(
                    ConflictException(
                        "patient already has an active elderly admission" +
                            encounterNoSuffix(row.getString("encounter_no")),
                    ),
                )
            }
        }
    }

    private fun ensureNoOtherActiveElderlyAdmission(
        client: SqlClient,
        patientId: String,
        excludeEncounterId: String,
    ): Future<Void> {
        val sql = "SELECT 1 AS hit, healthcare.encounters.encounter_no FROM healthcare.encounters " +
            "WHERE healthcare.encounters.patient_id = \$1 " +
            "AND healthcare.encounters.encounter_type = \$2 AND healthcare.encounters.status = \$3 " +
            "AND healthcare.encounters.id <> \$4"
        return execute(client, sql, Tuple.of(patientId, "ELDERLY_CARE", "ACTIVE", excludeEncounterId))
            .compose { rows ->
            // 存在性以「是否命中行」判定；住院号只用于消息（无号时省略该段）
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.succeededFuture()
            } else {
                // 029 §4.2 冻结文案：与 ensureNoActiveElderlyAdmission 同串，前端 admissionMessages 才能命中
                Future.failedFuture(
                    ConflictException(
                        "patient already has an active elderly admission" +
                            encounterNoSuffix(row.getString("encounter_no")),
                    ),
                )
            }
        }
    }

    private fun encounterNoSuffix(encounterNo: String?): String =
        encounterNo?.takeIf(String::isNotBlank)?.let { ": encounter_no=$it" } ?: ""

    /**
     * 029 D1–D3 入住区间/床位占用校验（仅 `ELDERLY_CARE`）：
     * 存在另一条养老入住 `O`（`O.id <> excludeEncounterId`）满足
     * `(O.admit_date IS NULL OR O.admit_date < X)` 且 `(O.discharge_date IS NULL OR O.discharge_date > S)` 即冲突，
     * 其中 `S = admitDate`、`X = dischargeDate`（`X = null` 表示未定离院 → 第一条条件恒真，不加 `admit_date` 过滤）。
     * 半开区间 `[admit_date, discharge_date)`：首尾相接（`O.discharge_date = S`）不算冲突。
     * 床位检查优先于「同长者区间重叠」，两者命中都返回 409；`department`/`ward` 都非空白时按 `trim` 后精确比较。
     *
     * 并发：SELECT-then-INSERT 存在 TOCTOU，故在同一事务内先按归一化床位键与 `patient_id`
     * 取事务级咨询锁（顺序固定：先床位、后长者），与 inventories `InventoryConsumptionService.lockExecutionKey`
     * 同一写法；仅覆盖 mock/嵌入式路由测试无法证明的真库并发窗口。
     */
    private fun ensureAdmissionSlotAvailable(
        client: SqlClient,
        patientId: String,
        department: String?,
        ward: String?,
        admitDate: OffsetDateTime,
        dischargeDate: OffsetDateTime?,
        excludeEncounterId: String?,
    ): Future<Void> {
        val bedDepartment = department?.trim()?.takeIf(String::isNotBlank)
        val bedWard = ward?.trim()?.takeIf(String::isNotBlank)
        val bedKey = if (bedDepartment != null && bedWard != null) "bed:$bedDepartment/$bedWard" else null

        /**
         * 构造「另一条养老入住与本条重叠」的 WHERE 片段（`$1..` 按调用顺序编号）：
         * 先追加半开区间重叠谓词与可选排除 id，调用方再补床位键或 patient_id。
         */
        fun conflictSql(args: MutableList<Any?>): String {
            val parts = mutableListOf(
                "healthcare.encounters.encounter_type = 'ELDERLY_CARE'",
                admissionOverlapSql(admitDate, dischargeDate, args),
            )
            if (excludeEncounterId != null) {
                parts += "healthcare.encounters.id <> \$${args.size + 1}"
                args.add(excludeEncounterId)
            }
            return parts.joinToString(" AND ")
        }

        // 冲突检查必须是惰性函数：Vert.x 的 execute() 会立即发起查询，若在取锁之前构建
        // Future，SELECT 就跑在锁外面，咨询锁将形同虚设（READ COMMITTED 下仍可写入重叠记录）。
        fun bedConflictCheck(): Future<Void> {
            if (bedKey == null) return Future.succeededFuture()
            val args = mutableListOf<Any?>()
            var where = conflictSql(args)
            where += " AND healthcare.encounters.department = \$${args.size + 1}"
            args.add(bedDepartment)
            where += " AND healthcare.encounters.ward = \$${args.size + 1}"
            args.add(bedWard)
            val sql = "SELECT healthcare.encounters.encounter_no, " +
                "healthcare.encounters.discharge_date FROM healthcare.encounters WHERE $where"
            return execute(client, sql, Tuple.from(args)).compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row == null) {
                    Future.succeededFuture()
                } else {
                    Future.failedFuture(
                        ConflictException(
                            "bed already occupied: department=$bedDepartment ward=$bedWard" +
                                " encounter_no=${row.getString("encounter_no")}" +
                                " until=${admissionUntil(row.getOffsetDateTime("discharge_date"))}",
                        ),
                    )
                }
            }
        }

        fun patientConflictCheck(): Future<Void> {
            val args = mutableListOf<Any?>()
            var where = conflictSql(args)
            where += " AND healthcare.encounters.patient_id = \$${args.size + 1}"
            args.add(patientId)
            val sql = "SELECT healthcare.encounters.encounter_no, " +
                "healthcare.encounters.discharge_date FROM healthcare.encounters WHERE $where"
            return execute(client, sql, Tuple.from(args)).compose { rows ->
                val row = rows.iterator().asSequence().firstOrNull()
                if (row == null) {
                    Future.succeededFuture()
                } else {
                    Future.failedFuture(
                        ConflictException(
                            "admission interval overlaps existing encounter:" +
                                " encounter_no=${row.getString("encounter_no")}" +
                                " until=${admissionUntil(row.getOffsetDateTime("discharge_date"))}",
                        ),
                    )
                }
            }
        }

        var locked: Future<Void> = Future.succeededFuture()
        listOfNotNull(bedKey, "patient:$patientId").forEach { key ->
            locked = locked.compose { lockAdmissionKey(client, key) }
        }

        // 顺序固定：取锁 → 床位占用检查 → 同长者区间重叠检查（检查全部在锁内发起）
        return locked.compose { bedConflictCheck() }.compose { patientConflictCheck() }
    }

    /** 半开区间重叠谓词（原生参数化 SQL 片段，占位符按 `args` 顺序编号）。 */
    private fun admissionOverlapSql(
        admitDate: OffsetDateTime,
        dischargeDate: OffsetDateTime?,
        args: MutableList<Any?>,
    ): String {
        // B：`O.discharge_date IS NULL OR O.discharge_date > S`，S = 本次入住开始，恒需判定
        var sql = "(healthcare.encounters.discharge_date IS NULL OR " +
            "healthcare.encounters.discharge_date > CAST(\$${args.size + 1} AS TIMESTAMPTZ))"
        args.add(admitDate)
        // A：`O.admit_date IS NULL OR O.admit_date < X`；X = 本次入住结束，为 null（未定离院）时恒真 → 不加过滤
        if (dischargeDate != null) {
            sql += " AND (healthcare.encounters.admit_date IS NULL OR " +
                "healthcare.encounters.admit_date < CAST(\$${args.size + 1} AS TIMESTAMPTZ))"
            args.add(dischargeDate)
        }
        return sql
    }

    private fun admissionUntil(dischargeDate: OffsetDateTime?): String =
        dischargeDate?.toString() ?: "ongoing"

    /**
     * 事务级 PostgreSQL 咨询锁（`pg_advisory_xact_lock`）：同一床位键/`patient_id` 的入住
     * 校验与插入在同一事务内互斥，关闭 SELECT-then-INSERT 的并发窗口。无迁移依赖。
     */
    private fun lockAdmissionKey(client: SqlClient, key: String): Future<Void> =
        client.preparedQuery("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .execute(Tuple.of(key))
            .map { null }

    private fun createEncounter(
        client: SqlClient,
        body: JsonObject,
        patientId: String,
        attendingPhysician: String,
        forcedType: String? = null,
    ): Future<JsonObject> {
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        val encounterNo = requiredText(body, "encounter_no")
        return ensureEncounterNoAvailable(client, encounterNo).compose {
            val encounterType = forcedType ?: body.getString("encounter_type")
            val slotCheck: Future<Void> = if (encounterType == "ELDERLY_CARE") {
                ensureAdmissionSlotAvailable(
                    client,
                    patientId,
                    body.getString("department"),
                    body.getString("ward"),
                    offsetDateTime(requiredText(body, "admit_date"), "admit_date"),
                    null,
                    null,
                )
            } else {
                Future.succeededFuture()
            }
            slotCheck.compose {
                val (encounterSql, encounterArgs) =
                    encounterInsert(body, id, patientId, attendingPhysician, forcedType, now)
                execute(client, encounterSql, encounterArgs)
                    .map { encounterResponse(body, id, patientId, attendingPhysician, forcedType, now) }
            }
        }
    }

    private fun ensureEncounterNoAvailable(client: SqlClient, encounterNo: String): Future<Void> =
        execute(
            client,
            "SELECT 1 FROM healthcare.encounters " +
                "WHERE healthcare.encounters.encounter_no = \$1",
            Tuple.of(encounterNo),
        ).compose { rows ->
            if (rows.size() == 0) Future.succeededFuture()
            else Future.failedFuture(IllegalArgumentException("encounter_no already exists"))
        }

    private fun patientInsert(
        body: JsonObject,
        id: String,
        now: OffsetDateTime,
        personType: String = PERSON_TYPE_DEFAULT,
    ): Pair<String, Tuple> {
        val name = requiredText(body, "name")
        val columns = mutableListOf("id", "name", "gender", "status", "person_type", "created_at", "updated_at")
        val args = mutableListOf<Any?>(id, name, body.getString("gender", ""), "ACTIVE", personType, now, now)
        fun add(column: String, value: Any?) {
            columns += column
            args += value
        }
        body.getString("birth_date")?.let { add("birth_date", localDate(it, "birth_date")) }
        body.getString("id_card_no")?.let { add("id_card_no", it) }
        body.getString("phone")?.let { add("phone", it) }
        body.getString("address")?.let { add("address", it) }
        jsonObject(body, "emergency_contact")?.let { add("emergency_contact", it) }
        body.getString("medical_insurance")?.let { add("medical_insurance", it) }
        jsonArray(body, "allergies")?.let { add("allergies", it) }
        body.getString("past_history")?.let { add("past_history", it) }
        jsonObject(body, "metadata")?.let { add("metadata", it) }
        val placeholders = args.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = "INSERT INTO healthcare.patients (${columns.joinToString(", ")}) VALUES ($placeholders)"
        return sql to Tuple.from(args)
    }

    private fun patientUpdate(body: JsonObject, id: String, now: OffsetDateTime): Pair<String, Tuple> {
        val args = mutableListOf<Any?>(now)
        val sets = mutableListOf("updated_at = \$1")
        fun set(column: String, value: Any?) {
            sets += "$column = \$${args.size + 1}"
            args += value
        }
        if (body.containsKey("name")) set("name", requiredText(body, "name"))
        if (body.containsKey("gender")) set("gender", body.getString("gender", ""))
        if (body.containsKey("birth_date")) set("birth_date", localDate(requiredText(body, "birth_date"), "birth_date"))
        if (body.containsKey("id_card_no")) set("id_card_no", body.getString("id_card_no"))
        if (body.containsKey("phone")) set("phone", body.getString("phone"))
        if (body.containsKey("address")) set("address", body.getString("address"))
        if (body.containsKey("emergency_contact")) {
            set("emergency_contact", requireNotNull(jsonObject(body, "emergency_contact", true)))
        }
        if (body.containsKey("medical_insurance")) set("medical_insurance", body.getString("medical_insurance"))
        if (body.containsKey("allergies")) set("allergies", requireNotNull(jsonArray(body, "allergies", true)))
        if (body.containsKey("past_history")) set("past_history", body.getString("past_history"))
        if (body.containsKey("metadata")) set("metadata", requireNotNull(jsonObject(body, "metadata", true)))
        if (body.containsKey("status")) {
            set("status", validStatus(body.getString("status"), patientStatuses, "patient status"))
        }
        if (body.containsKey("person_type")) set("person_type", personTypeFrom(body))
        args += id
        val sql = "UPDATE healthcare.patients SET ${sets.joinToString(", ")} " +
            "WHERE healthcare.patients.id = \$${args.size}"
        return sql to Tuple.from(args)
    }

    private fun encounterInsert(
        body: JsonObject,
        id: String,
        patientId: String,
        attendingPhysician: String,
        forcedType: String?,
        now: OffsetDateTime,
    ): Pair<String, Tuple> {
        val encounterType = forcedType ?: requiredText(body, "encounter_type")
        val encounterNo = requiredText(body, "encounter_no")
        val admitDate = offsetDateTime(requiredText(body, "admit_date"), "admit_date")
        val columns = mutableListOf(
            "id", "patient_id", "encounter_type", "encounter_no", "admit_date", "status",
            "created_at", "updated_at",
        )
        val args = mutableListOf<Any?>(
            id, patientId, encounterType, encounterNo, admitDate, "ACTIVE", now, now,
        )
        fun add(column: String, value: Any?) {
            columns += column
            args += value
        }
        body.getString("department")?.let { add("department", it) }
        body.getString("ward")?.let { add("ward", it) }
        body.getString("admitting_diagnosis")?.let { add("admitting_diagnosis", it) }
        // 责任医生/照护师由服务端按当前操作人写入，忽略请求体中的同名取值
        if (attendingPhysician.isNotBlank()) add("attending_physician", attendingPhysician)
        jsonObject(body, "metadata")?.let { add("metadata", it) }
        val placeholders = args.indices.joinToString(", ") { "\$${it + 1}" }
        val sql = "INSERT INTO healthcare.encounters (${columns.joinToString(", ")}) VALUES ($placeholders)"
        return sql to Tuple.from(args)
    }

    private fun encounterUpdate(body: JsonObject, id: String, now: OffsetDateTime): Pair<String, Tuple> {
        val args = mutableListOf<Any?>(now)
        val sets = mutableListOf("updated_at = \$1")
        fun set(column: String, value: Any?) {
            sets += "$column = \$${args.size + 1}"
            args += value
        }
        if (body.containsKey("department")) set("department", body.getString("department"))
        if (body.containsKey("ward")) set("ward", body.getString("ward"))
        if (body.containsKey("admitting_diagnosis")) set("admitting_diagnosis", body.getString("admitting_diagnosis"))
        // 责任医生/照护师不可修改：取值只来自创建时的操作人，这里不接收请求体
        if (body.containsKey("metadata")) set("metadata", requireNotNull(jsonObject(body, "metadata", true)))
        if (body.containsKey("status")) {
            set("status", validStatus(body.getString("status"), encounterStatuses - "DISCHARGED", "encounter status"))
        }
        args += id
        val sql = "UPDATE healthcare.encounters SET ${sets.joinToString(", ")} " +
            "WHERE healthcare.encounters.id = \$${args.size}"
        return sql to Tuple.from(args)
    }

    private fun patientResponse(body: JsonObject, id: String, now: OffsetDateTime): JsonObject =
        JsonObject()
            .put("id", id)
            .put("name", body.getString("name"))
            .put("gender", body.getString("gender", ""))
            .put("birth_date", body.getString("birth_date"))
            .put("id_card_no", body.getString("id_card_no"))
            .put("phone", body.getString("phone"))
            .put("address", body.getString("address"))
            .put("emergency_contact", body.getJsonObject("emergency_contact"))
            .put("medical_insurance", body.getString("medical_insurance", ""))
            .put("allergies", body.getJsonArray("allergies"))
            .put("past_history", body.getString("past_history"))
            .put("metadata", body.getJsonObject("metadata"))
            .put("status", "ACTIVE")
            .put("person_type", body.getString("person_type")?.takeIf(String::isNotBlank) ?: PERSON_TYPE_DEFAULT)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun encounterResponse(
        body: JsonObject,
        id: String,
        patientId: String,
        attendingPhysician: String,
        forcedType: String?,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("patient_id", patientId)
            .put("encounter_type", forcedType ?: body.getString("encounter_type"))
            .put("encounter_no", requiredText(body, "encounter_no"))
            .put("department", body.getString("department"))
            .put("ward", body.getString("ward"))
            .put("admit_date", body.getString("admit_date"))
            .put("discharge_date", null)
            .put("admitting_diagnosis", body.getString("admitting_diagnosis"))
            .put("discharge_diagnosis", null)
            .put("attending_physician", attendingPhysician.takeIf(String::isNotBlank))
            .put("status", "ACTIVE")
            .put("metadata", body.getJsonObject("metadata"))
            .put("created_at", now.toString())
            .put("updated_at", now.toString())

    private fun validatePatientUpdate(body: JsonObject) {
        if (body.isEmpty) throw IllegalArgumentException("at least one patient field is required")
        body.getString("status")?.let { validStatus(it, patientStatuses, "patient status") }
        if (body.containsKey("emergency_contact")) jsonObject(body, "emergency_contact", true)
        if (body.containsKey("allergies")) jsonArray(body, "allergies", true)
        if (body.containsKey("metadata")) jsonObject(body, "metadata", true)
        // person_type 形状（白名单在取到现有行后由 validatePersonType 统一校验）
        if (body.containsKey("person_type")) personTypeFrom(body)
    }

    private fun validateEncounterUpdate(body: JsonObject) {
        if (body.isEmpty) throw IllegalArgumentException("at least one encounter field is required")
        if (body.containsKey("encounter_no")) throw IllegalArgumentException("encounter_no cannot be modified")
        if (body.containsKey("status")) validStatus(body.getString("status"), encounterStatuses - "DISCHARGED", "encounter status")
        if (body.containsKey("metadata")) jsonObject(body, "metadata", true)
    }

    // ========================================================================
    //  护理记录 (NURSING_RECORD)
    // ========================================================================

    /** 创建日常护理记录 */
    fun createNursingRecord(body: JsonObject): Future<JsonObject> {
        val periodId = try { requiredText(body, "period_id") } catch (e: IllegalArgumentException) { return Future.failedFuture(e) }
        val encounterId = try { requiredText(body, "encounter_id") } catch (e: IllegalArgumentException) { return Future.failedFuture(e) }
        val title = try { requiredText(body, "title") } catch (e: IllegalArgumentException) { return Future.failedFuture(e) }
        val content = body.getString("content")?.trim() ?: return Future.failedFuture(IllegalArgumentException("content is required"))
        if (content.isBlank()) return Future.failedFuture(IllegalArgumentException("content is required"))

        val taskExecId = body.getString("task_execution_id")?.trim()?.takeIf { it.isNotBlank() }
        val recordTimeStr = body.getString("record_time")
        val now = OffsetDateTime.now()

        // 验证周期与入住归属
        return validatePeriodEncounter(periodId, encounterId).compose { (periodPatientId, encounterPatientId) ->
            if (periodPatientId != encounterPatientId)
                return@compose Future.failedFuture(IllegalArgumentException("period and encounter belong to different patients"))

            // 验证 task_execution_id 归属（如果提供）
            if (taskExecId != null) validateTaskExecutionBelonging(taskExecId, periodId, encounterId)
            else Future.succeededFuture<String?>(null)
        }.compose { taskId ->
            // 解析 record_time
            val recordTime = if (recordTimeStr != null) {
                try {
                    val t = OffsetDateTime.parse(recordTimeStr)
                    if (t.isAfter(OffsetDateTime.now())) return@compose Future.failedFuture(IllegalArgumentException("record_time cannot be in the future"))
                    t
                } catch (_: RuntimeException) {
                    return@compose Future.failedFuture(IllegalArgumentException("record_time must be an ISO-8601 offset date-time"))
                }
            } else {
                OffsetDateTime.now()
            }
            val recordDate = businessDate(recordTime)
            val recordKind = if (taskExecId != null) "EXECUTION" else "MANUAL"
            val author = body.getString("author")?.trim()?.takeIf { it.isNotBlank() } ?: ""

            // 构建受控 metadata
            val meta = JsonObject()
                .put("period_id", periodId)
                .put("record_kind", recordKind)
                .put("record_time", recordTime.toString())
            if (taskExecId != null) meta.put("task_execution_id", taskExecId)
            if (taskId != null) meta.put("task_id", taskId)

            val id = Ulid.generate()
            execute(
                pool,
                INSERT_MEDICAL_RECORD_SQL,
                Tuple.of(
                    id,
                    encounterId,
                    "NURSING_RECORD",
                    title.take(100),
                    content,
                    JsonArray(),
                    author,
                    recordDate,
                    meta,
                    now,
                    now,
                ),
            ).map {
                nursingRecordResponse(id, encounterId, periodId, recordKind, title, content, recordTime, recordDate, author, taskExecId, taskId, null, now)
            }.recover { err ->
                // 唯一索引冲突 → 409
                val msg = err.message ?: ""
                if (msg.contains("idx_nursing_record_execution") || msg.contains("duplicate key")) {
                    Future.failedFuture(DuplicateNursingRecordException("nursing record already exists for task execution"))
                } else {
                    Future.failedFuture(err)
                }
            }
        }
    }

    /** 查询护理记录 */
    fun listNursingRecords(
        periodId: String?,
        encounterId: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val args = mutableListOf<Any?>()
        fun bind(value: Any?): String = "\$${args.size + 1}".also { args.add(value) }
        val conditions = mutableListOf("record_type = ${bind("NURSING_RECORD")}")
        encounterId?.takeIf(String::isNotBlank)?.let { conditions.add("encounter_id = ${bind(it)}") }
        periodId?.takeIf(String::isNotBlank)?.let { conditions.add("metadata ->> 'period_id' = ${bind(it)}") }
        dateFrom?.takeIf(String::isNotBlank)?.let { d ->
            try {
                conditions.add("record_date >= ${bind(LocalDate.parse(d))}")
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid date_from")
            }
        }
        dateTo?.takeIf(String::isNotBlank)?.let { d ->
            try {
                conditions.add("record_date <= ${bind(LocalDate.parse(d))}")
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid date_to")
            }
        }
        val wherePart = " WHERE ${conditions.joinToString(" AND ")}"

        val countSql = "SELECT count(*) AS total FROM healthcare.medical_records$wherePart"
        val dataSql = "SELECT $medicalRecordColumns FROM healthcare.medical_records$wherePart " +
            "ORDER BY record_date DESC, created_at DESC OFFSET \$${args.size + 1} ROWS FETCH NEXT \$${args.size + 2} ROWS ONLY"

        return execute(pool, countSql, Tuple.from(args)).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataSql, Tuple.from(args + listOf<Any?>(offset, limit))).map { rows ->
                JsonObject()
                    .put("records", JsonArray(rows.map { nursingRecordFromRow(it) }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /** 获取单条护理记录 */
    fun getNursingRecord(id: String): Future<JsonObject> =
        execute(
            pool,
            "SELECT $medicalRecordColumns FROM healthcare.medical_records " +
                "WHERE healthcare.medical_records.id = \$1 " +
                "AND healthcare.medical_records.record_type = 'NURSING_RECORD'",
            Tuple.of(id),
        ).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("nursing record not found: $id"))
            // 检查更正记录
            val correctionSql = "SELECT count(*) AS total FROM healthcare.medical_records " +
                "WHERE metadata ->> 'corrects_record_id' = \$1 AND record_type = 'NURSING_RECORD'"
            execute(pool, correctionSql, Tuple.of(id)).map { correctionRows ->
                val correctionCount = correctionRows.iterator().next().getLong("total") ?: 0L
                val record = nursingRecordFromRow(row)
                if (correctionCount > 0) {
                    record.put("is_corrected", true)
                    record.put("correction_count", correctionCount)
                }
                record
            }
        }

    /** 创建更正记录 */
    fun createNursingRecordCorrection(recordId: String, body: JsonObject): Future<JsonObject> {
        val content = body.getString("content")?.trim() ?: return Future.failedFuture(IllegalArgumentException("content is required"))
        if (content.isBlank()) return Future.failedFuture(IllegalArgumentException("content is required"))

        val recordTimeStr = body.getString("record_time")
        val now = OffsetDateTime.now()

        return getNursingRecord(recordId).compose { original ->
            // 只允许更正 NURSING_RECORD
            if (original.getString("record_type") != "NURSING_RECORD")
                return@compose Future.failedFuture(IllegalArgumentException("only NURSING_RECORD can be corrected"))

            val encounterId = original.getString("encounter_id")
            val periodId = original.getJsonObject("metadata")?.getString("period_id")
            val recordKind = original.getJsonObject("metadata")?.getString("record_kind")
            val taskExecId = original.getJsonObject("metadata")?.getString("task_execution_id")
            val taskId = original.getJsonObject("metadata")?.getString("task_id")
            val originalTitle = original.getString("title") ?: ""
            val recordTime = if (recordTimeStr != null) {
                try {
                    val t = OffsetDateTime.parse(recordTimeStr)
                    if (t.isAfter(OffsetDateTime.now())) return@compose Future.failedFuture(IllegalArgumentException("record_time cannot be in the future"))
                    t
                } catch (_: RuntimeException) {
                    return@compose Future.failedFuture(IllegalArgumentException("record_time must be an ISO-8601 offset date-time"))
                }
            } else {
                OffsetDateTime.now()
            }
            val recordDate = businessDate(recordTime)

            // 构建受控 metadata
            val meta = JsonObject()
                .put("period_id", periodId ?: "")
                .put("record_kind", "CORRECTION")
                .put("record_time", recordTime.toString())
                .put("corrects_record_id", recordId)
            if (taskExecId != null) meta.put("task_execution_id", taskExecId)
            if (taskId != null) meta.put("task_id", taskId)

            val id = Ulid.generate()
            val author = body.getString("author")?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val correctionTitle = "更正：${originalTitle}"

            execute(
                pool,
                INSERT_MEDICAL_RECORD_SQL,
                Tuple.of(
                    id,
                    encounterId ?: "",
                    "NURSING_RECORD",
                    correctionTitle.take(100),
                    content,
                    JsonArray(),
                    author,
                    recordDate,
                    meta,
                    now,
                    now,
                ),
            ).map {
                nursingRecordResponse(id, encounterId ?: "", periodId ?: "", "CORRECTION", correctionTitle.take(100), content, recordTime, recordDate, author, taskExecId, taskId, recordId, now)
            }
        }
    }

    // ========================================================================
    //  017 院内护理异常事件
    //  创建/追加处置/关闭走外层事务；列表与详情只读，绝不产生写副作用。
    // ========================================================================

    /** 上报异常事件：锁定 encounter 与精确 period，写入事件与首条审计事实。 */
    fun createIncident(encounterId: String, body: JsonObject, userId: String): Future<JsonObject> {
        val request = try {
            validateIncidentCreateInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockEncounter(connection, encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                val patientId = requireNotNull(encounter.getString("patient_id"))
                carePlanRevisionService.lockPeriodForEncounter(connection, encounterId, patientId).compose { period ->
                    val periodStart = period.getString("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val occurredBusinessDate = NursingIncidentService.businessDateOf(request.occurredAt)
                    if (periodStart != null && occurredBusinessDate.isBefore(periodStart)) {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("occurred_at cannot be earlier than the care period start date")
                        )
                    }
                    nursingIncidentService.createIncident(
                        connection,
                        NursingIncidentService.IncidentCreateInput(
                            encounterId = encounterId,
                            periodId = requireNotNull(period.getString("id")),
                            periodStartDate = periodStart,
                            incidentType = request.incidentType,
                            severity = request.severity,
                            occurredAt = request.occurredAt,
                            description = request.description,
                            reporter = userId,
                            initialAction = request.initialAction,
                        ),
                    )
                }
            }
        }
    }

    /** 按精确 encounter 分页列出异常事件；status/date 过滤与分页参数均受控。 */
    fun listIncidents(
        encounterId: String,
        status: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        status?.takeIf(String::isNotBlank)?.let {
            if (it !in NursingIncidentService.VALID_STATUSES) {
                return Future.failedFuture(IllegalArgumentException("invalid status, must be one of: ${NursingIncidentService.VALID_STATUSES}"))
            }
        }
        val from = try {
            dateFrom?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it) }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_from"))
        }
        val to = try {
            dateTo?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it) }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid date_to"))
        }
        return getEncounter(encounterId).compose { encounter ->
            if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            }
            nursingIncidentService.listIncidents(encounterId, status, from, to, limit, offset)
        }
    }

    /** 事件详情：主事实 + 全部追加审计事实（只读，按 encounter 精确隔离）。 */
    fun getIncident(encounterId: String, id: String): Future<JsonObject> =
        nursingIncidentService.getIncident(encounterId, id)

    /** 追加处置/通知/观察；事件必须归属 [encounterId]，跨入住返回 404；
     *  事件已关闭或周期终态返回 409。 */
    fun addIncidentAction(encounterId: String, incidentId: String, body: JsonObject, userId: String): Future<JsonObject> {
        val input = try {
            validateIncidentActionInput(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            nursingIncidentService.appendAction(connection, encounterId, incidentId, input, userId)
        }
    }

    /** 关闭事件：只接受关闭说明；事件必须归属 [encounterId]，跨入住返回 404；
     *  重复关闭返回 409。 */
    fun closeIncident(encounterId: String, incidentId: String, body: JsonObject, userId: String): Future<JsonObject> {
        val closeNote = try {
            rejectUnknownKeys(body, setOf("close_note"), "request")
            val note = requiredText(body, "close_note")
            if (note.length > 2000) throw IllegalArgumentException("close_note must not exceed 2000 characters")
            note
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            nursingIncidentService.closeIncident(connection, encounterId, incidentId, closeNote, userId)
        }
    }

    // ========================================================================
    //  017 班次交接
    //  创建要求 Idempotency-Key；照护单元由服务端从锚定入住 department 推导。
    // ========================================================================

    /**
     * 创建班次交接单：首次 201，同键同内容重试 200，其余冲突 409。
     * 活动养老入住与护理记录快照由本服务在连接上收集（生成表类），
     * Nursing 侧只补齐未完成执行与未关闭事件后冻结为交班事项。
     */
    fun createShiftHandover(body: JsonObject, userId: String, idempotencyKey: String?): Future<Pair<Boolean, JsonObject>> {
        val request = try {
            validateHandoverCreateInput(body, idempotencyKey)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<Pair<Boolean, JsonObject>> { connection ->
            lockEncounter(connection, request.encounterId).compose { encounter ->
                if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                    return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
                }
                if (encounter.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("encounter is not active"))
                }
                val careUnit = encounter.getString("department")?.trim()?.takeIf(String::isNotBlank)
                    ?: return@compose Future.failedFuture(
                        ConflictException("care unit is not verifiable for this admission")
                    )
                collectHandoverSnapshot(connection, careUnit, request.businessDate).compose { snapshot ->
                    if (snapshot.getJsonArray("admissions").isEmpty()) {
                        return@compose Future.failedFuture(
                            ConflictException("care unit has no active elderly admission; cannot create handover")
                        )
                    }
                    shiftHandoverService.createHandover(
                        connection,
                        ShiftHandoverService.HandoverCreateInput(
                            encounterId = request.encounterId,
                            careUnit = careUnit,
                            businessDate = request.businessDate,
                            shift = request.shift,
                            manualItems = request.manualItems,
                            handoverBy = userId,
                            idempotencyKey = request.idempotencyKey,
                        ),
                        snapshot,
                    )
                }
            }
        }
    }

    /** 按照护单元分页列出交接单；business_date/shift 过滤均为可选项。 */
    fun listShiftHandovers(
        careUnit: String?,
        businessDate: String?,
        shift: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        if (careUnit.isNullOrBlank()) {
            return Future.failedFuture(IllegalArgumentException("care_unit is required"))
        }
        shift?.takeIf(String::isNotBlank)?.let {
            if (it !in ShiftHandoverService.VALID_SHIFTS) {
                return Future.failedFuture(IllegalArgumentException("invalid shift, must be one of: ${ShiftHandoverService.VALID_SHIFTS}"))
            }
        }
        val date = try {
            businessDate?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it) }
        } catch (_: RuntimeException) {
            return Future.failedFuture(IllegalArgumentException("invalid business_date"))
        }
        return shiftHandoverService.listHandovers(careUnit, date, shift, limit, offset)
    }

    /** 交接单详情：头 + 全部冻结/补充事项（只读）。 */
    fun getShiftHandover(id: String): Future<JsonObject> = shiftHandoverService.getHandover(id)

    /** 接班：接班人来自认证主体，只允许一次；请求体必须为空对象。 */
    fun receiveShiftHandover(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        if (!body.isEmpty) {
            return Future.failedFuture(IllegalArgumentException("receive request must be an empty object"))
        }
        return pool.withTransaction<JsonObject> { connection ->
            shiftHandoverService.receiveHandover(connection, id, userId)
        }
    }

    /** 补充事项：只接受正文；来源关联与创建人由服务端写入。 */
    fun appendShiftHandoverItem(id: String, body: JsonObject, userId: String): Future<JsonObject> {
        val content = try {
            rejectUnknownKeys(body, setOf("content"), "request")
            val text = requiredText(body, "content")
            if (text.length > 2000) throw IllegalArgumentException("content must not exceed 2000 characters")
            text
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        return pool.withTransaction<JsonObject> { connection ->
            shiftHandoverService.appendItem(connection, id, content, userId)
        }
    }

    // ——— 017 交班快照收集（Healthcare 侧，生成表类；Nursing 不直接读 Healthcare 表） ———

    /**
     * 在调用方连接上收集交班只读快照：该照护单元下活动养老入住（含精确
     * 开放照护周期）与本业务日新增护理记录。返回
     * `{ "admissions": [...], "records": [...] }`，供 [ShiftHandoverService]
     * 补齐 Nursing 侧执行/事件后冻结为交班事项。绝不产生任何写副作用。
     */
    private fun collectHandoverSnapshot(client: SqlClient, careUnit: String, businessDate: LocalDate): Future<JsonObject> {
        val encounterSql = "SELECT healthcare.encounters.id AS encounter_id, healthcare.encounters.patient_id, healthcare.encounters.encounter_no, healthcare.encounters.ward, " +
            "healthcare.patients.name AS patient_name FROM healthcare.encounters " +
            "JOIN healthcare.patients ON healthcare.patients.id = healthcare.encounters.patient_id " +
            "WHERE healthcare.encounters.encounter_type = 'ELDERLY_CARE' AND healthcare.encounters.status = 'ACTIVE' AND healthcare.encounters.department = \$1 " +
            "ORDER BY healthcare.encounters.admit_date ASC, healthcare.encounters.id ASC"

        return execute(client, encounterSql, Tuple.of(careUnit)).compose { rows ->
            val admissions = rows.map { row ->
                JsonObject()
                    .put("encounter_id", row.getString("encounter_id"))
                    .put("patient_id", row.getString("patient_id"))
                    .put("patient_name", row.getString("patient_name"))
                    .put("encounter_no", row.getString("encounter_no"))
                    .put("ward", row.getString("ward"))
            }
            if (admissions.isEmpty()) {
                return@compose Future.succeededFuture(
                    JsonObject().put("admissions", JsonArray()).put("records", JsonArray())
                )
            }
            val encounterIds = admissions.mapNotNull { it.getString("encounter_id") }
            val periodArgs = mutableListOf<Any?>()
            periodArgs.addAll(encounterIds)
            periodArgs.addAll(HANDOVER_PERIOD_OPEN_STATUSES)
            val encounterPlaceholders = encounterIds.indices.joinToString(", ") { "\$${it + 1}" }
            val statusPlaceholders = HANDOVER_PERIOD_OPEN_STATUSES.indices.joinToString(", ") {
                "\$${encounterIds.size + it + 1}"
            }
            val periodSql = "SELECT $servicePeriodColumns FROM nursing.nursing_service_periods " +
                "WHERE encounter_id IN ($encounterPlaceholders) AND status IN ($statusPlaceholders)"
            execute(client, periodSql, Tuple.from(periodArgs)).compose { periodRows ->
                val periodByEncounter = periodRows.associateBy { it.getString("encounter_id") }
                val withPeriods = admissions.mapNotNull { enc ->
                    val period = periodByEncounter[enc.getString("encounter_id")] ?: return@mapNotNull null
                    enc.copy()
                        .put("period_id", period.getString("id"))
                        .put("period_start_date", period.getLocalDate("start_date")?.toString())
                }
                val periodIds = withPeriods.mapNotNull { it.getString("period_id") }
                if (periodIds.isEmpty()) {
                    return@compose Future.succeededFuture(
                        JsonObject().put("admissions", JsonArray(withPeriods)).put("records", JsonArray())
                    )
                }
                val recordArgs = mutableListOf<Any?>(businessDate)
                recordArgs.addAll(periodIds)
                val periodPlaceholders = periodIds.indices.joinToString(", ") { "\$${it + 2}" }
                val recordsSql = "SELECT id, title, content, record_date, metadata " +
                    "FROM healthcare.medical_records " +
                    "WHERE record_type = 'NURSING_RECORD' AND record_date = \$1 " +
                    "AND (metadata ->> 'period_id') IN ($periodPlaceholders) " +
                    "ORDER BY created_at ASC, id ASC"
                execute(client, recordsSql, Tuple.from(recordArgs)).map { recordRows ->
                    val records = recordRows.map { row ->
                        val meta = row.getValue("metadata") as? JsonObject ?: JsonObject()
                        JsonObject()
                            .put("id", row.getString("id"))
                            .put("period_id", meta.getString("period_id"))
                            .put("title", row.getString("title"))
                            .put("content", row.getString("content"))
                            .put("record_date", row.getLocalDate("record_date")?.toString())
                    }
                    JsonObject().put("admissions", JsonArray(withPeriods)).put("records", JsonArray(records))
                }
            }
        }
    }

    /** 校验照护单元仍存在活动养老入住（读 healthcare.encounters，生成表类）。
     *  由 [ShiftHandoverService] 以连接绑定端口调用；无活动入住时交接单只读。 */
    private fun ensureCareUnitActiveForHandover(client: SqlClient, careUnit: String): Future<Void> =
        execute(
            client,
            "SELECT 1 FROM healthcare.encounters " +
                "WHERE encounter_type = 'ELDERLY_CARE' AND status = 'ACTIVE' AND department = \$1",
            Tuple.of(careUnit),
        ).compose { rows ->
            if (rows.size() == 0) {
                return@compose Future.failedFuture(
                    ConflictException("care unit has no active elderly admission; handover is read-only")
                )
            }
            Future.succeededFuture()
        }

    // ——— 017 私有校验 ———

    private data class IncidentRequest(
        val incidentType: String,
        val severity: String,
        val occurredAt: OffsetDateTime,
        val description: String,
        val initialAction: NursingIncidentService.ActionInput?,
    )

    private data class HandoverRequest(
        val encounterId: String,
        val businessDate: LocalDate,
        val shift: String,
        val manualItems: List<String>,
        val idempotencyKey: String,
    )

    private fun validateIncidentCreateInput(body: JsonObject): IncidentRequest {
        rejectUnknownKeys(body, setOf("incident_type", "severity", "occurred_at", "description", "initial_action"), "request")
        val incidentType = requiredText(body, "incident_type")
        if (incidentType !in NursingIncidentService.VALID_INCIDENT_TYPES) {
            throw IllegalArgumentException("invalid incident_type, must be one of: ${NursingIncidentService.VALID_INCIDENT_TYPES}")
        }
        val severity = requiredText(body, "severity")
        if (severity !in NursingIncidentService.VALID_SEVERITIES) {
            throw IllegalArgumentException("invalid severity, must be one of: ${NursingIncidentService.VALID_SEVERITIES}")
        }
        val occurredAt = offsetDateTime(requiredText(body, "occurred_at"), "occurred_at")
        if (occurredAt.isAfter(OffsetDateTime.now())) {
            throw IllegalArgumentException("occurred_at cannot be in the future")
        }
        val description = requiredText(body, "description")
        if (description.length > 2000) {
            throw IllegalArgumentException("description must not exceed 2000 characters")
        }
        val initialAction = body.getValue("initial_action")?.let { value ->
            if (value !is JsonObject) throw IllegalArgumentException("initial_action must be a JSON object")
            validateIncidentActionInput(value)
        }
        return IncidentRequest(incidentType, severity, occurredAt, description, initialAction)
    }

    private fun validateIncidentActionInput(body: JsonObject): NursingIncidentService.ActionInput {
        rejectUnknownKeys(body, setOf("action_type", "body", "notified_party", "notification_result"), "request")
        val actionType = requiredText(body, "action_type")
        if (actionType !in NursingIncidentService.VALID_ACTION_TYPES) {
            throw IllegalArgumentException("invalid action_type, must be one of: ${NursingIncidentService.VALID_ACTION_TYPES}")
        }
        val actionBody = requiredText(body, "body")
        if (actionBody.length > 2000) {
            throw IllegalArgumentException("body must not exceed 2000 characters")
        }
        val notifiedParty = optionalText(body, "notified_party")?.let {
            if (it.length > 200) throw IllegalArgumentException("notified_party must not exceed 200 characters")
            it
        }
        val notificationResult = optionalText(body, "notification_result")?.let {
            if (it.length > 500) throw IllegalArgumentException("notification_result must not exceed 500 characters")
            it
        }
        return NursingIncidentService.ActionInput(actionType, actionBody, notifiedParty, notificationResult)
    }

    private fun validateHandoverCreateInput(body: JsonObject, idempotencyKey: String?): HandoverRequest {
        if (idempotencyKey.isNullOrBlank()) {
            throw IllegalArgumentException("Idempotency-Key header is required")
        }
        rejectUnknownKeys(body, setOf("encounter_id", "business_date", "shift", "manual_items"), "request")
        val encounterId = requiredText(body, "encounter_id")
        val businessDate = localDate(requiredText(body, "business_date"), "business_date")
        if (businessDate.isAfter(businessDate(OffsetDateTime.now()))) {
            throw IllegalArgumentException("business_date cannot be in the future")
        }
        val shift = requiredText(body, "shift")
        if (shift !in ShiftHandoverService.VALID_SHIFTS) {
            throw IllegalArgumentException("invalid shift, must be one of: ${ShiftHandoverService.VALID_SHIFTS}")
        }
        val manualItems = body.getValue("manual_items")?.let { value ->
            if (value !is JsonArray) throw IllegalArgumentException("manual_items must be an array of strings")
            value.map { item ->
                if (item !is String) throw IllegalArgumentException("manual_items must be an array of strings")
                val trimmed = item.trim()
                if (trimmed.isBlank()) throw IllegalArgumentException("manual_items must not contain blank strings")
                if (trimmed.length > 500) throw IllegalArgumentException("manual_items must not exceed 500 characters per item")
                trimmed
            }
        } ?: emptyList()
        if (manualItems.size > 50) throw IllegalArgumentException("manual_items must not exceed 50 items")
        return HandoverRequest(encounterId, businessDate, shift, manualItems, idempotencyKey.trim())
    }

    // ——— 病程记录与诊断私有辅助方法 ———

    private data class ProgressNoteCreateInput(
        val noteType: String,
        val content: String,
        val physician: String,
        val recordTime: OffsetDateTime,
        val metadata: JsonObject?,
        val chronicDiseaseId: String?,
    )

    private fun validateProgressNoteInput(body: JsonObject): ProgressNoteCreateInput {
        rejectUnknownKeys(body, setOf("note_type", "content", "physician", "record_time", "metadata"), "request")
        val noteType = requiredText(body, "note_type")
        if (noteType !in setOf("DAILY", "CHRONIC")) {
            throw IllegalArgumentException("invalid note_type, must be one of: [DAILY, CHRONIC]")
        }
        val content = requiredText(body, "content")
        if (content.length > 2000) {
            throw IllegalArgumentException("content must not exceed 2000 characters")
        }
        val physician = requiredText(body, "physician")
        if (physician.length > 100) {
            throw IllegalArgumentException("physician must not exceed 100 characters")
        }
        val recordTime = body.getString("record_time")?.let { offsetDateTime(it, "record_time") }
            ?: OffsetDateTime.now()
        val metadata = jsonObject(body, "metadata")
        val chronicDiseaseId = if (noteType == "CHRONIC") {
            val value = metadata?.getString("chronic_disease_id")?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("metadata.chronic_disease_id is required for CHRONIC notes")
            if (value.length > 32) throw IllegalArgumentException("chronic_disease_id must not exceed 32 characters")
            value
        } else {
            null
        }
        return ProgressNoteCreateInput(noteType, content, physician, recordTime, metadata, chronicDiseaseId)
    }

    /** 慢病病程校验：档案存在且属于该 encounter 的老人（跨档案/跨老人一律拒绝） */
    private fun ensureChronicRegistrationBelongs(client: SqlClient, chronicDiseaseId: String, patientId: String?): Future<Void> {
        val sql = "SELECT 1 FROM healthcare.chronic_disease_registrations " +
            "WHERE id = \$1 AND patient_id = \$2"
        return execute(client, sql, Tuple.of(chronicDiseaseId, patientId)).compose { rows ->
            if (rows.size() == 0) {
                Future.failedFuture(
                    HealthcareNotFoundException("chronic disease registration not found for this patient"),
                )
            } else {
                Future.succeededFuture()
            }
        }
    }

    private fun progressNoteJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("note_type", row.getString("note_type"))
            .put("content", row.getString("content"))
            .put("physician", row.getString("physician"))
            .put("record_time", row.getOffsetDateTime("record_time")?.toString())
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

    private fun progressNoteResponse(
        id: String,
        encounterId: String,
        input: ProgressNoteCreateInput,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("note_type", input.noteType)
            .put("content", input.content)
            .put("physician", input.physician)
            .put("record_time", input.recordTime.toString())
            .put("metadata", input.metadata)
            .put("created_at", now.toString())

    private data class DiagnosisCreateInput(
        val diagnosisType: String,
        val diagnosisText: String,
        val diagnosisDate: LocalDate,
        val physician: String,
        val icdCode: String?,
        val remark: String?,
    )

    /**
     * 主/次诊断只由 `diagnosis_type` 决定，`is_major` 是它的派生只读字段。
     *
     * 二者不是正交维度：`diagnosis_type` 已被闭集校验为二值，`is_major` 因此是它的纯函数。
     * 允许独立设置会直接产生「次要诊断 + 主诊断」这类自相矛盾的记录，所以写入与响应一律按
     * `diagnosis_type` 派生；请求体中的 `is_major` 只做形状校验，取值被忽略。
     */
    private fun isMajorDiagnosis(diagnosisType: String): Boolean = diagnosisType == "PRIMARY"

    private fun validateDiagnosisInput(body: JsonObject): DiagnosisCreateInput {
        rejectUnknownKeys(
            body,
            setOf("diagnosis_type", "diagnosis_text", "icd_code", "diagnosis_date", "physician", "is_major", "remark"),
            "request",
        )
        val diagnosisType = requiredText(body, "diagnosis_type")
        if (diagnosisType !in diagnosisTypes) {
            throw IllegalArgumentException("invalid diagnosis_type, must be one of: $diagnosisTypes")
        }
        val diagnosisText = requiredText(body, "diagnosis_text")
        if (diagnosisText.length > 2000) {
            throw IllegalArgumentException("diagnosis_text must not exceed 2000 characters")
        }
        val diagnosisDate = localDate(requiredText(body, "diagnosis_date"), "diagnosis_date")
        val physician = requiredText(body, "physician")
        if (physician.length > 100) {
            throw IllegalArgumentException("physician must not exceed 100 characters")
        }
        val icdCode = optionalText(body, "icd_code")?.let {
            if (it.length > 32) throw IllegalArgumentException("icd_code must not exceed 32 characters")
            it
        }
        // is_major 已改为派生字段：保留形状校验以拒绝畸形输入，但不采用其取值。
        body.getValue("is_major")?.let { value ->
            if (value !is Boolean) throw IllegalArgumentException("is_major must be a boolean")
        }
        val remark = optionalText(body, "remark")?.let {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
            it
        }
        return DiagnosisCreateInput(diagnosisType, diagnosisText, diagnosisDate, physician, icdCode, remark)
    }

    private fun diagnosisJson(row: Row): JsonObject =
        JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("diagnosis_type", row.getString("diagnosis_type"))
            .put("icd_code", row.getString("icd_code"))
            .put("diagnosis_text", row.getString("diagnosis_text"))
            .put("diagnosis_date", row.getLocalDate("diagnosis_date")?.toString())
            .put("physician", row.getString("physician"))
            // 按 diagnosis_type 派生，使历史数据中不一致的存量 is_major 不再对外暴露
            .put("is_major", isMajorDiagnosis(row.getString("diagnosis_type")))
            .put("metadata", row.getValue("metadata"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())

    private fun diagnosisResponse(
        id: String,
        encounterId: String,
        input: DiagnosisCreateInput,
        now: OffsetDateTime,
    ): JsonObject =
        JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("diagnosis_type", input.diagnosisType)
            .put("icd_code", input.icdCode)
            .put("diagnosis_text", input.diagnosisText)
            .put("diagnosis_date", input.diagnosisDate.toString())
            .put("physician", input.physician)
            .put("is_major", isMajorDiagnosis(input.diagnosisType))
            .put("metadata", input.remark?.let { JsonObject().put("remark", it) })
            .put("created_at", now.toString())

    // ——— 护理记录私有辅助方法 ———

    private fun nursingRecordResponse(
        id: String, encounterId: String, periodId: String?, recordKind: String?,
        title: String, content: String, recordTime: OffsetDateTime, recordDate: LocalDate,
        author: String, taskExecutionId: String?, taskId: String?,
        correctsRecordId: String?, now: OffsetDateTime,
    ): JsonObject {
        val meta = JsonObject()
            .put("period_id", periodId)
            .put("record_kind", recordKind)
            .put("record_time", recordTime.toString())
        if (taskExecutionId != null) meta.put("task_execution_id", taskExecutionId)
        if (taskId != null) meta.put("task_id", taskId)
        if (correctsRecordId != null) meta.put("corrects_record_id", correctsRecordId)

        return JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("period_id", periodId)
            .put("record_type", "NURSING_RECORD")
            .put("record_kind", recordKind)
            .put("title", title)
            .put("content", content)
            .put("record_time", recordTime.toString())
            .put("record_date", recordDate.toString())
            .put("author", author)
            .put("task_execution_id", taskExecutionId)
            .put("task_id", taskId)
            .put("corrects_record_id", correctsRecordId)
            .put("metadata", meta)
            .put("created_at", now.toString())
            .put("updated_at", now.toString())
    }

    private fun nursingRecordFromRow(row: io.vertx.sqlclient.Row): JsonObject {
        val id = row.getString("id") ?: ""
        val encounterId = row.getString("encounter_id") ?: ""
        val title = row.getString("title") ?: ""
        val content = row.getString("content")
        val physician = row.getString("physician") ?: ""
        val recordDate = row.getLocalDate("record_date")
        val createdAt = row.getOffsetDateTime("created_at")
        val rowMeta = row.getValue("metadata") as? JsonObject ?: JsonObject()

        val periodId = rowMeta.getString("period_id")
        val recordKind = rowMeta.getString("record_kind")
        val recordTimeStr = rowMeta.getString("record_time")
        val taskExecutionId = rowMeta.getString("task_execution_id")
        val taskId = rowMeta.getString("task_id")
        val correctsRecordId = rowMeta.getString("corrects_record_id")

        return JsonObject()
            .put("id", id)
            .put("encounter_id", encounterId)
            .put("period_id", periodId)
            .put("record_type", "NURSING_RECORD")
            .put("record_kind", recordKind)
            .put("title", title)
            .put("content", content)
            .put("record_time", recordTimeStr)
            .put("record_date", recordDate?.toString())
            .put("author", physician)
            .put("task_execution_id", taskExecutionId)
            .put("task_id", taskId)
            .put("corrects_record_id", correctsRecordId)
            .put("metadata", rowMeta)
            .put("created_at", createdAt?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /** 验证 period_id 和 encounter_id 的 patient_id 一致性 */
    private fun validatePeriodEncounter(periodId: String, encounterId: String): Future<Pair<String?, String?>> {
        val periodSql = "SELECT patient_id FROM nursing.nursing_service_periods WHERE id = \$1"
        val encounterSql = "SELECT healthcare.encounters.patient_id FROM healthcare.encounters " +
            "WHERE healthcare.encounters.id = \$1"

        return execute(pool, periodSql, Tuple.of(periodId)).compose { periodRows ->
            val periodRow = periodRows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("nursing service period not found: $periodId"))
            val periodPatientId = periodRow.getString("patient_id")
            execute(pool, encounterSql, Tuple.of(encounterId)).map { encounterRows ->
                val encounterRow = encounterRows.iterator().asSequence().firstOrNull()
                    ?: throw HealthcareNotFoundException("encounter not found: $encounterId")
                val encounterPatientId = encounterRow.getString("patient_id")
                Pair(periodPatientId, encounterPatientId)
            }
        }
    }

    /** 验证 task_execution_id 归属 */
    private fun validateTaskExecutionBelonging(taskExecId: String, periodId: String, encounterId: String): Future<String?> {
        val sql = "SELECT nursing.nursing_tasks.id, nursing.nursing_tasks.period_id, " +
            "nursing.nursing_tasks.encounter_id " +
            "FROM nursing.nursing_task_executions " +
            "JOIN nursing.nursing_tasks " +
            "ON nursing.nursing_tasks.id = nursing.nursing_task_executions.task_id " +
            "WHERE nursing.nursing_task_executions.id = \$1"

        return execute(pool, sql, Tuple.of(taskExecId)).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("task execution not found: $taskExecId"))
            val taskId = row.getString("id")
            val taskPeriodId = row.getString("period_id")
            val taskEncounterId = row.getString("encounter_id")

            if (taskPeriodId != null && taskPeriodId != periodId)
                return@compose Future.failedFuture(IllegalArgumentException("task execution does not belong to the specified period"))
            if (taskEncounterId != null && taskEncounterId != encounterId)
                return@compose Future.failedFuture(IllegalArgumentException("task execution does not belong to the specified encounter"))
            return@compose Future.succeededFuture(taskId)
        }
    }

    private fun execute(client: SqlClient, sql: String, args: Tuple): Future<RowSet<Row>> =
        client.preparedQuery(sql).execute(args)

    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun validStatus(value: String?, allowed: Set<String>, label: String): String =
        value?.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun localDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 date")
        }

    private fun offsetDateTime(value: String, field: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 offset date-time")
        }

    private fun jsonObject(body: JsonObject, key: String, required: Boolean = false): JsonObject? {
        val value = body.getValue(key)
        if (value == null && !required) return null
        return value as? JsonObject ?: throw IllegalArgumentException("$key must be a JSON object")
    }

    private fun jsonArray(body: JsonObject, key: String, required: Boolean = false): JsonArray? {
        val value = body.getValue(key)
        if (value == null && !required) return null
        return value as? JsonArray ?: throw IllegalArgumentException("$key must be a JSON array")
    }

    // ========================================================================
    //  养老离院交接摘要归档 (DISCHARGE_SUMMARY)
    // ========================================================================

    private val handoverTitle = "养老照护离院交接摘要"
    private val deceasedHandoverTitle = "养老照护去世交接摘要"

    /** 归档文书标题按入住终态区分：已离院为离院交接摘要，已去世为去世照护快照摘要。 */
    private fun handoverTitleFor(encounter: JsonObject): String =
        if (encounter.getString("status") == "DECEASED") deceasedHandoverTitle else handoverTitle

    /**
     * 可归档终态的归档时点：已离院取 `discharge_date`，已去世取 `death_date`。
     * 其它状态或时点缺失/非法返回 null，由资格校验统一映射为 409。
     * 去世同样关闭了精确关联的照护周期，因此复用同一归档时点做周期锁定。
     */
    private fun handoverArchiveInstant(encounter: JsonObject): OffsetDateTime? {
        val raw: String? = when (encounter.getString("status")) {
            "DISCHARGED" -> encounter.getString("discharge_date")
            "DECEASED" -> encounter.getString("death_date")
            else -> null
        }
        if (raw == null) return null
        return try {
            OffsetDateTime.parse(raw)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 获取既有交接摘要；资格错误按 4.1 返回 400/409，无文书返回 404 */
    fun getElderlyDischargeHandover(id: String): Future<JsonObject> {
        return pool.withTransaction<JsonObject> { connection ->
            validateHandoverEligibility(connection, id).compose { (encounter, period) ->
                val periodId = requireNotNull(period.getString("id"))
                lockExistingHandover(connection, encounter.getString("id"), periodId).compose { row ->
                    if (row == null) {
                        Future.failedFuture(HealthcareNotFoundException("elderly discharge handover not found"))
                    } else {
                        Future.succeededFuture(handoverFromRow(row))
                    }
                }
            }
        }
    }

    /** 创建或幂等读取交接摘要；首次 201，相同规范化输入重试 200，不同输入 409 */
    fun createElderlyDischargeHandover(id: String, body: JsonObject): Future<Pair<Boolean, JsonObject>> {
        val author = try {
            val value = requiredText(body, "author")
            if (value.length > 100) throw IllegalArgumentException("author must not exceed 100 characters")
            value
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val handoverNote = try {
            val noteValue = body.getValue("handover_note")
            if (noteValue != null && noteValue !is String) {
                throw IllegalArgumentException("handover_note must be a string")
            }
            noteValue?.trim()?.takeIf(String::isNotBlank)?.let {
                if (it.length > 2000) throw IllegalArgumentException("handover_note must not exceed 2000 characters")
                it
            }
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }

        return pool.withTransaction<Pair<Boolean, JsonObject>> { connection ->
            validateHandoverEligibility(connection, id).compose { (encounter, period) ->
                val encounterId = requireNotNull(encounter.getString("id"))
                val periodId = requireNotNull(period.getString("id"))
                val archiveInstant = requireNotNull(handoverArchiveInstant(encounter))

                lockExistingHandover(connection, encounterId, periodId).compose { existing ->
                    if (existing != null) {
                        return@compose compareHandoverExisting(existing, author, handoverNote)
                    }
                    buildAndInsertHandover(connection, encounter, period, author, handoverNote, archiveInstant)
                        .map { handover -> Pair(true, handover) }
                        .recover { err ->
                            // 唯一索引竞争：重新锁读既有行后按输入比较，不泄漏数据库约束文本
                            if (!isUniqueViolation(err)) return@recover Future.failedFuture(err)
                            lockExistingHandover(connection, encounterId, periodId).compose { row ->
                                if (row == null) Future.failedFuture(err)
                                else compareHandoverExisting(row, author, handoverNote)
                            }
                        }
                }
            }
        }
    }

    /** 已存在摘要时的幂等比较：相同返回 200 原文书，不同返回 409 */
    private fun compareHandoverExisting(
        row: Row,
        author: String,
        handoverNote: String?,
    ): Future<Pair<Boolean, JsonObject>> {
        val sameAuthor = row.getString("physician") == author
        val sameNote = (row.getString("content") ?: null) == handoverNote
        if (sameAuthor && sameNote) {
            return Future.succeededFuture(Pair(false, handoverFromRow(row)))
        }
        return Future.failedFuture(
            ConflictException("elderly discharge handover already exists with different author or handover note")
        )
    }

    /**
     * 资格校验（第 4.1 节扩展）：锁定 encounter → 校验养老主体 → 校验已离院/已去世及归档时点
     * → 锁定精确关联已完成周期 → 患者一致。已离院与已去世都归入同一份照护快照。
     */
    private fun validateHandoverEligibility(
        client: SqlClient,
        encounterId: String,
    ): Future<Pair<JsonObject, JsonObject>> {
        return lockEncounter(client, encounterId).compose { encounter ->
            if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(IllegalArgumentException("encounter is not an elderly admission"))
            }
            val status = encounter.getString("status")
            if (status != "DISCHARGED" && status != "DECEASED") {
                return@compose Future.failedFuture(
                    ConflictException("encounter is not discharged or deceased; complete the discharge or death flow first")
                )
            }
            val archiveInstant = handoverArchiveInstant(encounter)
                ?: return@compose Future.failedFuture(
                    ConflictException(
                        if (status == "DECEASED") "encounter has no valid death date"
                        else "encounter has no valid discharge date"
                    )
                )

            servicePeriodService.lockCompletedElderlyCarePeriodForHandover(
                client, encounterId, businessDate(archiveInstant),
            ).compose { period ->
                if (period.getString("patient_id") != encounter.getString("patient_id")) {
                    return@compose Future.failedFuture(
                        ConflictException("patient_id mismatch between period and encounter")
                    )
                }
                Future.succeededFuture(Pair(encounter, period))
            }
        }
    }

    /** 锁读既有本计划归档文书；无则返回 null */
    private fun lockExistingHandover(
        client: SqlClient,
        encounterId: String,
        periodId: String,
    ): Future<Row?> {
        val sql = "SELECT $medicalRecordColumns FROM healthcare.medical_records " +
            "WHERE healthcare.medical_records.record_type = \$1 " +
            "AND healthcare.medical_records.encounter_id = \$2 " +
            "AND (healthcare.medical_records.metadata ->> 'period_id') = \$3 " +
            "AND (healthcare.medical_records.metadata ->> 'is_elderly_discharge_handover') = 'true' " +
            "FOR UPDATE"
        return execute(client, sql, Tuple.of("DISCHARGE_SUMMARY", encounterId, periodId)).map { rows ->
            rows.iterator().asSequence().firstOrNull()
        }
    }

    /** 组装快照、受控 metadata 并写入单条 DISCHARGE_SUMMARY，随后读取返回 */
    private fun buildAndInsertHandover(
        client: SqlClient,
        encounter: JsonObject,
        period: JsonObject,
        author: String,
        handoverNote: String?,
        archiveInstant: OffsetDateTime,
    ): Future<JsonObject> {
        val encounterId = requireNotNull(encounter.getString("id"))
        val periodId = requireNotNull(period.getString("id"))
        val patientId = requireNotNull(encounter.getString("patient_id"))
        val recordDate = businessDate(archiveInstant)
        val now = OffsetDateTime.now()

        return dischargeHandoverSnapshotService.buildNursingSnapshot(client, periodId)
            .compose { nursing ->
                loadPatientHandoverData(client, patientId).compose { patient ->
                    loadHandoverNursingRecords(client, encounterId, periodId).compose { records ->
                        val snapshot = JsonObject()
                            .put("patient", patient)
                            .put("encounter", handoverEncounterJson(encounter))
                            .put("care_period", handoverCarePeriodJson(period))
                            .put("assessments", nursing.getJsonArray("assessments"))
                            .put("plans", nursing.getJsonArray("plans"))
                            .put("tasks", nursing.getJsonArray("tasks"))
                            .put("execution_summary", nursing.getJsonObject("execution_summary"))
                            .put("nursing_records", JsonArray(records))

                        val plans = nursing.getJsonArray("plans")
                        val tasks = nursing.getJsonArray("tasks")
                        val planItems = plans.sumOf { (it as JsonObject).getJsonArray("items").size() }
                        val taskExecutions = tasks.sumOf { (it as JsonObject).getJsonArray("executions").size() }
                        val sourceCounts = JsonObject()
                            .put("assessments", nursing.getJsonArray("assessments").size())
                            .put("plans", plans.size())
                            .put("plan_items", planItems)
                            .put("tasks", tasks.size())
                            .put("executions", taskExecutions)
                            .put("nursing_records", records.size)

                        val contentBlocks = JsonArray().add(
                            JsonObject().put("snapshot_version", 1).put("snapshot", snapshot)
                        )
                        val meta = JsonObject()
                            .put("is_elderly_discharge_handover", true)
                            .put("period_id", periodId)
                            .put("snapshot_version", 1)
                            .put("generated_at", now.toString())
                            .put("source_counts", sourceCounts)

                        val id = Ulid.generate()
                        execute(
                            client,
                            INSERT_MEDICAL_RECORD_SQL,
                            Tuple.of(
                                id,
                                encounterId,
                                "DISCHARGE_SUMMARY",
                                handoverTitleFor(encounter),
                                handoverNote,
                                contentBlocks,
                                author,
                                recordDate,
                                meta,
                                now,
                                now,
                            ),
                        ).compose {
                            execute(
                                client,
                                "SELECT $medicalRecordColumns FROM healthcare.medical_records WHERE id = \$1",
                                Tuple.of(id),
                            ).map { rows ->
                                handoverFromRow(rows.iterator().next())
                            }
                        }
                    }
                }
            }
    }

    private fun loadPatientHandoverData(client: SqlClient, patientId: String): Future<JsonObject> {
        val sql = "SELECT id, name, gender, birth_date, emergency_contact, allergies, past_history " +
            "FROM healthcare.patients WHERE id = \$1"
        return execute(client, sql, Tuple.of(patientId)).map { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: throw HealthcareNotFoundException("patient not found: $patientId")
            JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("gender", row.getString("gender"))
                .put("birth_date", row.getLocalDate("birth_date")?.toString())
                .put("emergency_contact", row.getValue("emergency_contact"))
                .put("allergies", row.getValue("allergies"))
                .put("past_history", row.getString("past_history"))
        }
    }

    /** 同时限制 encounter_id 与 metadata.period_id 的原始 NURSING_RECORD 及其更正记录 */
    private fun loadHandoverNursingRecords(
        client: SqlClient,
        encounterId: String,
        periodId: String,
    ): Future<List<JsonObject>> {
        val sql = "SELECT id, title, content, physician, record_date, metadata, created_at " +
            "FROM healthcare.medical_records " +
            "WHERE healthcare.medical_records.record_type = \$1 " +
            "AND healthcare.medical_records.encounter_id = \$2 " +
            "AND (healthcare.medical_records.metadata ->> 'period_id') = \$3 " +
            "ORDER BY (healthcare.medical_records.metadata ->> 'record_time') ASC NULLS LAST, " +
            "healthcare.medical_records.created_at ASC, healthcare.medical_records.id ASC"
        return execute(client, sql, Tuple.of("NURSING_RECORD", encounterId, periodId)).map { rows ->
            rows.map { row ->
                val meta = row.getValue("metadata") as? JsonObject ?: JsonObject()
                JsonObject()
                    .put("id", row.getString("id"))
                    .put("record_kind", meta.getString("record_kind"))
                    .put("title", row.getString("title"))
                    .put("content", row.getString("content"))
                    .put("record_time", meta.getString("record_time"))
                    .put("record_date", row.getLocalDate("record_date")?.toString())
                    .put("author", row.getString("physician"))
                    .put("corrects_record_id", meta.getString("corrects_record_id"))
                    .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            }
        }
    }

    private fun handoverEncounterJson(encounter: JsonObject): JsonObject =
        JsonObject()
            .put("id", encounter.getString("id"))
            .put("encounter_no", encounter.getString("encounter_no"))
            .put("department", encounter.getString("department"))
            .put("ward", encounter.getString("ward"))
            .put("admit_date", encounter.getString("admit_date"))
            .put("discharge_date", encounter.getString("discharge_date"))
            .put("death_date", encounter.getString("death_date"))
            .put("death_cause", encounter.getString("death_cause"))
            .put("admitting_diagnosis", encounter.getString("admitting_diagnosis"))
            .put("discharge_diagnosis", encounter.getString("discharge_diagnosis"))
            .put("attending_physician", encounter.getString("attending_physician"))
            .put("status", encounter.getString("status"))

    private fun handoverCarePeriodJson(period: JsonObject): JsonObject =
        JsonObject()
            .put("id", period.getString("id"))
            .put("service_type", period.getString("service_type"))
            .put("start_date", period.getString("start_date"))
            .put("end_date", period.getString("end_date"))
            .put("coordinator", period.getString("coordinator"))
            .put("status", period.getString("status"))

    /** 从 medical_records 行映射为 API 对象（解包 content_blocks[0].snapshot 与受控 metadata） */
    private fun handoverFromRow(row: Row): JsonObject {
        val rowMeta = row.getValue("metadata") as? JsonObject ?: JsonObject()
        val contentBlocks = row.getValue("content_blocks") as? JsonArray ?: JsonArray()
        val snapshot = if (contentBlocks.size() > 0) contentBlocks.getJsonObject(0).getJsonObject("snapshot") else JsonObject()
        return JsonObject()
            .put("id", row.getString("id"))
            .put("record_type", row.getString("record_type"))
            .put("title", row.getString("title"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("period_id", rowMeta.getString("period_id"))
            .put("record_date", row.getLocalDate("record_date")?.toString())
            .put("author", row.getString("physician"))
            .put("handover_note", row.getString("content"))
            .put("generated_at", rowMeta.getString("generated_at"))
            .put("snapshot_version", rowMeta.getInteger("snapshot_version") ?: 1)
            .put("snapshot", snapshot)
    }

    private fun isUniqueViolation(err: Throwable): Boolean {
        var current: Throwable? = err
        while (current != null) {
            if (current is PgException && current.sqlState == "23505") return true
            current = current.cause
        }
        return false
    }
}
