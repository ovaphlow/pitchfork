package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.MedicalOrders.MEDICAL_ORDERS
import com.ovaphlow.crate.database.gen.healthcare.tables.Patients.PATIENTS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingServicePeriods.NURSING_SERVICE_PERIODS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTaskExecutions.NURSING_TASK_EXECUTIONS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingTasks.NURSING_TASKS
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.TaskService
import com.ovaphlow.crate.nursing.TaskService.OrderTaskInput
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Query
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class MedicalOrderService(
    private val pool: Pool,
    private val taskService: TaskService,
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        val VALID_ORDER_TYPES = setOf("MEDICATION", "THERAPY", "EXAMINATION", "LAB_TEST")
        val VALID_ORDER_CLASSES = setOf("LONG_TERM", "TEMPORARY")
        val VALID_ORDER_STATUSES = setOf("ACTIVE", "DISCONTINUED", "CANCELLED", "COMPLETED")
        val STATUS_TRANSITIONS = mapOf(
            "ACTIVE" to listOf("DISCONTINUED", "CANCELLED", "COMPLETED"),
            "DISCONTINUED" to emptyList(),
            "CANCELLED" to emptyList(),
            "COMPLETED" to emptyList(),
        )
        val ORDER_TASK_TYPE = mapOf(
            "MEDICATION" to "MEDICATION",
            "THERAPY" to "TREATMENT",
            "EXAMINATION" to "TREATMENT",
            "LAB_TEST" to "TREATMENT",
        )
        // 只读展示标签：不参与查询、状态机或模块联动
        val ORDER_TYPE_LABELS = mapOf(
            "MEDICATION" to "用药医嘱",
            "THERAPY" to "治疗医嘱",
            "EXAMINATION" to "检查医嘱",
            "LAB_TEST" to "检验医嘱",
        )
        val ORDER_CLASS_LABELS = mapOf(
            "LONG_TERM" to "长期医嘱",
            "TEMPORARY" to "临时医嘱",
        )
        val DETAIL_WHITELIST = mapOf(
            "MEDICATION" to setOf("drug_name", "dose", "unit", "route", "frequency_code", "frequency_name", "duration_days", "remark"),
            "THERAPY" to setOf("treatment_item", "frequency_code", "frequency_name", "duration_days", "remark"),
            "EXAMINATION" to setOf("item_name", "body_part", "priority", "clinical_note", "frequency_code", "frequency_name", "duration_days", "remark"),
            "LAB_TEST" to setOf("item_name", "specimen_type", "priority", "fasting", "clinical_note", "frequency_code", "frequency_name", "duration_days", "remark"),
        )
        val REQUIRED_DETAIL_KEY = mapOf(
            "MEDICATION" to "drug_name",
            "THERAPY" to "treatment_item",
            "EXAMINATION" to "item_name",
            "LAB_TEST" to "item_name",
        )
        private val businessZone = ZoneId.of("Asia/Shanghai")
        private val OPEN_PERIOD_STATUSES = setOf("ACTIVE", "SUSPENDED")
    }

    fun createOrder(encounterId: String, body: JsonObject): Future<JsonObject> {
        val input = try {
            validateCreateInput(body)
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
                lockPeriod(connection, encounterId).compose { period ->
                    if (period.getString("status") !in OPEN_PERIOD_STATUSES) {
                        return@compose Future.failedFuture(
                            ConflictException("nursing care period is not open: ${period.getString("status")}")
                        )
                    }
                    if (period.getString("patient_id") != encounter.getString("patient_id")) {
                        return@compose Future.failedFuture(
                            ConflictException("patient_id mismatch between period and encounter")
                        )
                    }

                    val orderId = Ulid.generate()
                    val now = OffsetDateTime.now()
                    var insertQuery = ctx.insertInto(MEDICAL_ORDERS)
                        .set(MEDICAL_ORDERS.ID, orderId)
                        .set(MEDICAL_ORDERS.ENCOUNTER_ID, encounterId)
                        .set(MEDICAL_ORDERS.ORDER_TYPE, input.orderType)
                        .set(MEDICAL_ORDERS.ORDER_CLASS, input.orderClass)
                        .set(MEDICAL_ORDERS.ORDER_CONTENT, input.orderContent)
                        .set(MEDICAL_ORDERS.ORDER_DETAILS, JSONB.valueOf(input.orderDetails.encode()))
                        .set(MEDICAL_ORDERS.START_TIME, input.startTime)
                        .set(MEDICAL_ORDERS.DOCTOR, input.doctor)
                        .set(MEDICAL_ORDERS.STATUS, "ACTIVE")
                        .set(MEDICAL_ORDERS.CREATED_AT, now)
                        .set(MEDICAL_ORDERS.UPDATED_AT, now)
                    input.endTime?.let { insertQuery = insertQuery.set(MEDICAL_ORDERS.END_TIME, it) }

                    execute(connection, insertQuery).compose {
                        val startDate = businessDate(input.startTime)
                        val endDate = input.endTime?.let(::businessDate) ?: if (input.orderDetails.containsKey("duration_days")) {
                            startDate.plusDays(
                                (input.orderDetails.getValue("duration_days") as Number).toLong()
                            )
                        } else if (input.orderDetails.getString("frequency_code") == "STAT") {
                            startDate
                        } else {
                            null
                        }
                        taskService.createOrderTask(
                            connection,
                            OrderTaskInput(
                                periodId = requireNotNull(period.getString("id")),
                                encounterId = encounterId,
                                orderItemId = orderId,
                                taskType = ORDER_TASK_TYPE.getValue(input.orderType),
                                description = input.orderContent,
                                frequencyCode = input.orderDetails.getString("frequency_code")
                                    ?.trim()?.takeIf { it.isNotBlank() },
                                frequencyName = input.orderDetails.getString("frequency_name")
                                    ?.trim()?.takeIf { it.isNotBlank() },
                                startDate = startDate,
                                endDate = endDate,
                            ),
                        )
                    }.compose {
                        readOrderWithTask(connection, orderId).map { row ->
                            orderJson(row, row.getString("task_id"))
                        }
                    }
                }
            }
        }
    }

    fun listOrders(
        encounterId: String,
        orderType: String? = null,
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        conditions.add(MEDICAL_ORDERS.ENCOUNTER_ID.eq(encounterId))
        orderType?.takeIf(String::isNotBlank)?.let { conditions.add(MEDICAL_ORDERS.ORDER_TYPE.eq(it)) }
        status?.takeIf(String::isNotBlank)?.let { conditions.add(MEDICAL_ORDERS.STATUS.eq(it)) }

        val joinOn = MEDICAL_ORDERS.ID.eq(NURSING_TASKS.ORDER_ITEM_ID)
        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS).on(joinOn)
            .where(conditions)
        val dataQuery = ctx.select(orderSelectFields())
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS).on(joinOn)
            .where(conditions)
            .orderBy(MEDICAL_ORDERS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map { orderJson(it, it.getString("task_id")) }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    fun getOrder(id: String): Future<JsonObject> {
        val query = ctx.select(orderSelectFields())
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS)
            .on(MEDICAL_ORDERS.ID.eq(NURSING_TASKS.ORDER_ITEM_ID))
            .where(MEDICAL_ORDERS.ID.eq(id))
        return execute(pool, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
                ?: return@compose Future.failedFuture(HealthcareNotFoundException("order not found: $id"))

            val summaryQuery = ctx.select(NURSING_TASK_EXECUTIONS.STATUS, DSL.count().`as`("cnt"))
                .from(NURSING_TASK_EXECUTIONS)
                .join(NURSING_TASKS)
                .on(NURSING_TASK_EXECUTIONS.TASK_ID.eq(NURSING_TASKS.ID))
                .where(NURSING_TASKS.ORDER_ITEM_ID.eq(id))
                .groupBy(NURSING_TASK_EXECUTIONS.STATUS)
            execute(pool, summaryQuery).map { summaryRows ->
                val summary = JsonObject()
                    .put("PENDING", 0)
                    .put("IN_PROGRESS", 0)
                    .put("COMPLETED", 0)
                    .put("SKIPPED", 0)
                    .put("CANCELLED", 0)
                for (summaryRow in summaryRows) {
                    val execStatus = summaryRow.getString("status") ?: continue
                    summary.put(execStatus, summaryRow.getLong("cnt") ?: 0L)
                }
                orderJson(row, row.getString("task_id")).put("execution_summary", summary)
            }
        }
    }

    fun updateOrderStatus(id: String, body: JsonObject): Future<JsonObject> {
        val target = try {
            requiredText(body, "status")
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        if (target !in VALID_ORDER_STATUSES) {
            return Future.failedFuture(
                IllegalArgumentException("invalid order status, must be one of: $VALID_ORDER_STATUSES")
            )
        }

        return pool.withTransaction<JsonObject> { connection ->
            lockOrder(connection, id).compose { row ->
                val current = row.getString("status")
                if (current != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("order is already $current"))
                }
                if (target !in STATUS_TRANSITIONS.getValue(current)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("cannot transition from $current to $target")
                    )
                }

                val now = OffsetDateTime.now()
                val taskTarget = if (target == "COMPLETED") "COMPLETED" else "CANCELLED"
                taskService.terminateOrderTask(connection, id, taskTarget)
                    .compose {
                        val updateQuery = ctx.update(MEDICAL_ORDERS)
                            .set(MEDICAL_ORDERS.STATUS, target)
                            .set(MEDICAL_ORDERS.END_TIME, now)
                            .set(MEDICAL_ORDERS.UPDATED_AT, now)
                            .where(MEDICAL_ORDERS.ID.eq(id))
                        execute(connection, updateQuery)
                    }
                    .compose {
                        readOrderWithTask(connection, id).map { orderRow ->
                            orderJson(orderRow, orderRow.getString("task_id"))
                        }
                    }
            }
        }
    }

    // ========================================================================
    //  011 药房接方/发药内部端口：只供 App 编排调用，不暴露为 Healthcare 路由
    // ========================================================================

    /**
     * 护士核对用药医嘱（护士核对计划）：只更新核对审计字段与 updated_at，
     * 不创建领药记录、护理任务、执行、发药单或库存操作，也不改写临床 status。
     *
     * 请求体必须为空：核对人由认证中间件提供的 userId 决定，拒绝客户端伪造。
     * 校验顺序：请求体严格性 → 医嘱存在 → MEDICATION 类型 → ACTIVE 状态 →
     * 未核对（幂等冲突）→ 入住 ELDERLY_CARE → 入住 ACTIVE。
     */
    fun nurseCheckOrder(id: String, userId: String, body: JsonObject): Future<JsonObject> {
        if (body.fieldNames().any { it.isNotBlank() }) {
            return Future.failedFuture(
                IllegalArgumentException("nurse-check request must not contain any fields")
            )
        }
        return pool.withTransaction<JsonObject> { connection ->
            lockOrder(connection, id).compose { order ->
                val encounterId = requireNotNull(order.getString("encounter_id"))
                if (order.getString("order_type") != "MEDICATION") {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("only MEDICATION orders can be nurse-checked")
                    )
                }
                if (order.getString("status") != "ACTIVE") {
                    return@compose Future.failedFuture(ConflictException("order is not active"))
                }
                if (order.getString("nurse_checked_by") != null || order.getOffsetDateTime("nurse_checked_at") != null) {
                    return@compose Future.failedFuture(ConflictException("order is already nurse-checked"))
                }
                lockEncounter(connection, encounterId).compose { encounter ->
                    if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                        return@compose Future.failedFuture(
                            IllegalArgumentException("encounter is not an elderly admission")
                        )
                    }
                    if (encounter.getString("status") != "ACTIVE") {
                        return@compose Future.failedFuture(ConflictException("encounter is not active"))
                    }
                    val now = OffsetDateTime.now()
                    val updateQuery = ctx.update(MEDICAL_ORDERS)
                        .set(MEDICAL_ORDERS.NURSE_CHECKED_BY, userId)
                        .set(MEDICAL_ORDERS.NURSE_CHECKED_AT, now)
                        .set(MEDICAL_ORDERS.UPDATED_AT, now)
                        .where(MEDICAL_ORDERS.ID.eq(id))
                    execute(connection, updateQuery).compose {
                        readOrderWithTask(connection, id).map { row ->
                            orderJson(row, row.getString("task_id"))
                        }
                    }
                }
            }
        }
    }

    /**
     * 药房待接方只读列表（011）：只返回活动养老入住（ELDERLY_CARE + ACTIVE）
     * 下的 `MEDICATION` + `ACTIVE` 医嘱。读取不写医嘱、不创建任务、不锁库存。
     * 只读场景调用方传 Pool 即可，不开启事务。
     */
    fun listMedicationOrdersForPharmacy(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        conditions.add(MEDICAL_ORDERS.ORDER_TYPE.eq("MEDICATION"))
        conditions.add(MEDICAL_ORDERS.STATUS.eq("ACTIVE"))
        // 药房门禁：只返回已核对的活动用药医嘱，未核对医嘱药房不可见
        conditions.add(MEDICAL_ORDERS.NURSE_CHECKED_AT.isNotNull())
        conditions.add(ENCOUNTERS.ENCOUNTER_TYPE.eq("ELDERLY_CARE"))
        conditions.add(ENCOUNTERS.STATUS.eq("ACTIVE"))
        encounterId?.let { conditions.add(MEDICAL_ORDERS.ENCOUNTER_ID.eq(it)) }
        if (!search.isNullOrBlank()) {
            conditions.add(
                DSL.or(
                    MEDICAL_ORDERS.ORDER_CONTENT.like("%$search%"),
                    DSL.field("order_details->>'drug_name'").like("%$search%"),
                    PATIENTS.NAME.like("%$search%"),
                    ENCOUNTERS.ENCOUNTER_NO.like("%$search%"),
                ),
            )
        }

        val fields = listOf(
            MEDICAL_ORDERS.ID.`as`("order_id"),
            MEDICAL_ORDERS.ENCOUNTER_ID,
            ENCOUNTERS.PATIENT_ID,
            PATIENTS.NAME.`as`("patient_name"),
            ENCOUNTERS.ENCOUNTER_NO,
            MEDICAL_ORDERS.ORDER_TYPE,
            MEDICAL_ORDERS.ORDER_CLASS,
            MEDICAL_ORDERS.ORDER_CONTENT,
            MEDICAL_ORDERS.ORDER_DETAILS,
            MEDICAL_ORDERS.DOCTOR,
            MEDICAL_ORDERS.START_TIME,
            MEDICAL_ORDERS.END_TIME,
            MEDICAL_ORDERS.NURSE_CHECKED_BY,
            MEDICAL_ORDERS.NURSE_CHECKED_AT,
        )
        val from = ctx.select(fields)
            .from(MEDICAL_ORDERS)
            .join(ENCOUNTERS).on(MEDICAL_ORDERS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(conditions)
        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(MEDICAL_ORDERS)
            .join(ENCOUNTERS).on(MEDICAL_ORDERS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(conditions)
        val dataQuery = from
            .orderBy(MEDICAL_ORDERS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(client, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(client, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::medicationOrderRowJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 护士核对汇总列表：跨入住展示所有待核对的用药医嘱（010 计划）。
     * 只返回活动养老入住（ELDERLY_CARE + ACTIVE）下的 `MEDICATION` + `ACTIVE`
     * 且尚未护士核对（nurse_checked_at IS NULL）的医嘱，供护理汇总页核对；
     * 已核对医嘱由药房待接方列表呈现。读取不写医嘱、不创建任务、不锁库存，
     * 只读场景调用方传 Pool 即可，不开启事务。
     */
    fun listPendingNurseCheckOrders(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject> {
        val conditions = mutableListOf<Condition>()
        conditions.add(MEDICAL_ORDERS.ORDER_TYPE.eq("MEDICATION"))
        conditions.add(MEDICAL_ORDERS.STATUS.eq("ACTIVE"))
        // 汇总页只列尚未核对的用药医嘱；已核对医嘱在药房待接方列表可见
        conditions.add(MEDICAL_ORDERS.NURSE_CHECKED_AT.isNull())
        conditions.add(ENCOUNTERS.ENCOUNTER_TYPE.eq("ELDERLY_CARE"))
        conditions.add(ENCOUNTERS.STATUS.eq("ACTIVE"))
        encounterId?.let { conditions.add(MEDICAL_ORDERS.ENCOUNTER_ID.eq(it)) }
        if (!search.isNullOrBlank()) {
            conditions.add(
                DSL.or(
                    MEDICAL_ORDERS.ORDER_CONTENT.like("%$search%"),
                    DSL.field("order_details->>'drug_name'").like("%$search%"),
                    PATIENTS.NAME.like("%$search%"),
                    ENCOUNTERS.ENCOUNTER_NO.like("%$search%"),
                ),
            )
        }

        val fields = listOf(
            MEDICAL_ORDERS.ID,
            MEDICAL_ORDERS.ENCOUNTER_ID,
            ENCOUNTERS.PATIENT_ID,
            PATIENTS.NAME.`as`("patient_name"),
            ENCOUNTERS.ENCOUNTER_NO,
            MEDICAL_ORDERS.ORDER_TYPE,
            MEDICAL_ORDERS.ORDER_CLASS,
            MEDICAL_ORDERS.ORDER_CONTENT,
            MEDICAL_ORDERS.ORDER_DETAILS,
            MEDICAL_ORDERS.DOCTOR,
            MEDICAL_ORDERS.START_TIME,
            MEDICAL_ORDERS.END_TIME,
            MEDICAL_ORDERS.STATUS,
            MEDICAL_ORDERS.NURSE_CHECKED_BY,
            MEDICAL_ORDERS.NURSE_CHECKED_AT,
            MEDICAL_ORDERS.CREATED_AT,
            MEDICAL_ORDERS.UPDATED_AT,
        )
        val from = ctx.select(fields)
            .from(MEDICAL_ORDERS)
            .join(ENCOUNTERS).on(MEDICAL_ORDERS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(conditions)
        val countQuery = ctx.select(DSL.count().`as`("total"))
            .from(MEDICAL_ORDERS)
            .join(ENCOUNTERS).on(MEDICAL_ORDERS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(conditions)
        val dataQuery = from
            .orderBy(MEDICAL_ORDERS.CREATED_AT.desc())
            .limit(limit)
            .offset(offset)

        return execute(client, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(client, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map(::nurseCheckPendingRowJson)))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    /**
     * 药房发药事务内精确锁读一条医嘱（011）：FOR UPDATE OF medical_orders，
     * 返回受控快照。必须在调用方外层事务连接内执行，禁止重新取 Pool。
     */
    fun lockMedicationOrderForPharmacy(
        client: SqlClient,
        medicalOrderId: String,
    ): Future<MedicationOrderLockSnapshot> {
        val fields = listOf(
            MEDICAL_ORDERS.ID.`as`("order_id"),
            MEDICAL_ORDERS.ENCOUNTER_ID,
            MEDICAL_ORDERS.ORDER_TYPE,
            MEDICAL_ORDERS.ORDER_CLASS,
            MEDICAL_ORDERS.ORDER_CONTENT,
            MEDICAL_ORDERS.ORDER_DETAILS,
            MEDICAL_ORDERS.DOCTOR,
            MEDICAL_ORDERS.START_TIME,
            MEDICAL_ORDERS.END_TIME,
            MEDICAL_ORDERS.STATUS.`as`("order_status"),
            MEDICAL_ORDERS.NURSE_CHECKED_BY,
            MEDICAL_ORDERS.NURSE_CHECKED_AT,
            ENCOUNTERS.PATIENT_ID,
            ENCOUNTERS.ENCOUNTER_NO,
            ENCOUNTERS.ENCOUNTER_TYPE,
            ENCOUNTERS.STATUS.`as`("encounter_status"),
            PATIENTS.NAME.`as`("patient_name"),
        )
        val query = ctx.select(fields)
            .from(MEDICAL_ORDERS)
            .join(ENCOUNTERS).on(MEDICAL_ORDERS.ENCOUNTER_ID.eq(ENCOUNTERS.ID))
            .join(PATIENTS).on(ENCOUNTERS.PATIENT_ID.eq(PATIENTS.ID))
            .where(MEDICAL_ORDERS.ID.eq(medicalOrderId))
            .forUpdate()
            .of(MEDICAL_ORDERS)
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(HealthcareNotFoundException("order not found: $medicalOrderId"))
            } else {
                Future.succeededFuture(
                    MedicationOrderLockSnapshot(
                        orderId = row.getString("order_id"),
                        encounterId = row.getString("encounter_id"),
                        patientId = row.getString("patient_id"),
                        patientName = row.getString("patient_name") ?: "",
                        encounterNo = row.getString("encounter_no"),
                        encounterType = row.getString("encounter_type") ?: "",
                        encounterStatus = row.getString("encounter_status") ?: "",
                        orderType = row.getString("order_type") ?: "",
                        orderClass = row.getString("order_class"),
                        orderStatus = row.getString("order_status") ?: "",
                        orderContent = row.getString("order_content") ?: "",
                        doctor = row.getString("doctor") ?: "",
                        startTime = row.getOffsetDateTime("start_time"),
                        endTime = row.getOffsetDateTime("end_time"),
                        orderDetails = (row.getValue("order_details") as? JsonObject) ?: JsonObject(),
                        nurseCheckedBy = row.getString("nurse_checked_by"),
                        nurseCheckedAt = row.getOffsetDateTime("nurse_checked_at"),
                    ),
                )
            }
        }
    }

    private fun medicationOrderRowJson(row: Row): JsonObject {
        val orderType = row.getString("order_type")
        val orderClass = row.getString("order_class")
        return JsonObject()
            .put("order_id", row.getString("order_id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("encounter_no", row.getString("encounter_no"))
            .put("order_type", orderType)
            .put("order_type_label", ORDER_TYPE_LABELS[orderType])
            .put("order_class", orderClass)
            .put("order_class_label", orderClass?.let { ORDER_CLASS_LABELS[it] })
            .put("drug_name", (row.getValue("order_details") as? JsonObject)?.getString("drug_name"))
            .put("order_content", row.getString("order_content"))
            .put("dose", (row.getValue("order_details") as? JsonObject)?.getString("dose"))
            .put("unit", (row.getValue("order_details") as? JsonObject)?.getString("unit"))
            .put("route", (row.getValue("order_details") as? JsonObject)?.getString("route"))
            .put("frequency_code", (row.getValue("order_details") as? JsonObject)?.getString("frequency_code"))
            .put("frequency_name", (row.getValue("order_details") as? JsonObject)?.getString("frequency_name"))
            .put("start_time", row.getOffsetDateTime("start_time")?.toString())
            .put("end_time", row.getOffsetDateTime("end_time")?.toString())
            .put("doctor", row.getString("doctor"))
            .put("nurse_checked_by", row.getString("nurse_checked_by"))
            .put("nurse_checked_at", row.getOffsetDateTime("nurse_checked_at")?.toString())
    }

    /**
     * 护士核对汇总行：字段与 orderJson 一致并补患者/入住信息，但不读 task_id
     * （汇总查询未 join 护理任务，jOOQ Row 对未选列取值会抛异常）。
     */
    private fun nurseCheckPendingRowJson(row: Row): JsonObject {
        val orderType = row.getString("order_type")
        val orderClass = row.getString("order_class")
        return JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("patient_id", row.getString("patient_id"))
            .put("patient_name", row.getString("patient_name"))
            .put("encounter_no", row.getString("encounter_no"))
            .put("order_type", orderType)
            .put("order_type_label", ORDER_TYPE_LABELS[orderType])
            .put("order_class", orderClass)
            .put("order_class_label", orderClass?.let { ORDER_CLASS_LABELS[it] })
            .put("order_content", row.getString("order_content"))
            .put("order_details", row.getValue("order_details") as? JsonObject)
            .put("start_time", row.getOffsetDateTime("start_time")?.toString())
            .put("end_time", row.getOffsetDateTime("end_time")?.toString())
            .put("doctor", row.getString("doctor"))
            .put("status", row.getString("status"))
            .put("nurse_checked_by", row.getString("nurse_checked_by"))
            .put("nurse_checked_at", row.getOffsetDateTime("nurse_checked_at")?.toString())
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    /**
     * 离院/去世终局编排复用：锁读该 encounter 全部 ACTIVE 医嘱，逐条改为
     * DISCONTINUED 并同连接取消关联任务。必须在调用方外层事务内执行。
     */
    fun terminateEncounterOrders(
        client: SqlClient,
        encounterId: String,
        endTime: OffsetDateTime,
    ): Future<Void> {
        val query = ctx.selectFrom(MEDICAL_ORDERS)
            .where(MEDICAL_ORDERS.ENCOUNTER_ID.eq(encounterId))
            .and(MEDICAL_ORDERS.STATUS.eq("ACTIVE"))
            .forUpdate()
        return execute(client, query).compose { rows ->
            val now = OffsetDateTime.now()
            terminateOrders(client, rows.map { it.getString("id") }, endTime, now)
        }
    }

    // ========================================================================
    //  私有辅助
    // ========================================================================

    private fun lockEncounter(client: SqlClient, id: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(id)).forUpdate()).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("patient_id", row.getString("patient_id"))
                        .put("encounter_type", row.getString("encounter_type"))
                        .put("status", row.getString("status"))
                )
            } ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $id"))
        }

    private fun lockPeriod(client: SqlClient, encounterId: String): Future<JsonObject> =
        execute(
            client,
            ctx.selectFrom(NURSING_SERVICE_PERIODS)
                .where(NURSING_SERVICE_PERIODS.ENCOUNTER_ID.eq(encounterId))
                .forUpdate(),
        ).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(
                    ConflictException("elderly admission has no bound nursing care period: $encounterId")
                )
            } else {
                Future.succeededFuture(
                    JsonObject()
                        .put("id", row.getString("id"))
                        .put("patient_id", row.getString("patient_id"))
                        .put("status", row.getString("status"))
                )
            }
        }

    private fun lockOrder(client: SqlClient, id: String): Future<Row> =
        execute(client, ctx.selectFrom(MEDICAL_ORDERS).where(MEDICAL_ORDERS.ID.eq(id)).forUpdate()).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(HealthcareNotFoundException("order not found: $id"))
            } else {
                Future.succeededFuture(row)
            }
        }

    private fun readOrderWithTask(client: SqlClient, id: String): Future<Row> {
        val query = ctx.select(orderSelectFields())
            .from(MEDICAL_ORDERS)
            .join(NURSING_TASKS)
            .on(MEDICAL_ORDERS.ID.eq(NURSING_TASKS.ORDER_ITEM_ID))
            .where(MEDICAL_ORDERS.ID.eq(id))
        return execute(client, query).compose { rows ->
            val row = rows.iterator().asSequence().firstOrNull()
            if (row == null) {
                Future.failedFuture(HealthcareNotFoundException("order not found: $id"))
            } else {
                Future.succeededFuture(row)
            }
        }
    }

    private fun terminateOrders(
        client: SqlClient,
        orderIds: List<String>,
        endTime: OffsetDateTime,
        now: OffsetDateTime,
    ): Future<Void> {
        fun loop(index: Int): Future<Void> {
            if (index >= orderIds.size) return Future.succeededFuture()
            val orderId = orderIds[index]
            val updateQuery = ctx.update(MEDICAL_ORDERS)
                .set(MEDICAL_ORDERS.STATUS, "DISCONTINUED")
                .set(MEDICAL_ORDERS.END_TIME, endTime)
                .set(MEDICAL_ORDERS.UPDATED_AT, now)
                .where(MEDICAL_ORDERS.ID.eq(orderId))
            return execute(client, updateQuery)
                .compose { taskService.terminateOrderTask(client, orderId, "CANCELLED") }
                .compose { loop(index + 1) }
        }
        return loop(0)
    }

    private fun orderSelectFields(): List<org.jooq.Field<*>> =
        MEDICAL_ORDERS.fields().toList() + NURSING_TASKS.ID.`as`("task_id")

    private fun validateCreateInput(body: JsonObject): CreateOrderInput {
        val orderType = validStatus(body.getString("order_type"), VALID_ORDER_TYPES, "order_type")
        // 新建医嘱必须明确周期，服务端不根据 duration_days / frequency_code 猜测
        val orderClass = validStatus(requiredText(body, "order_class"), VALID_ORDER_CLASSES, "order_class")
        val orderContent = requiredText(body, "order_content")
        if (orderContent.length > 2000) {
            throw IllegalArgumentException("order_content must not exceed 2000 characters")
        }
        val doctor = requiredText(body, "doctor")
        if (doctor.length > 100) {
            throw IllegalArgumentException("doctor must not exceed 100 characters")
        }
        val startTime = offsetDateTime(requiredText(body, "start_time"), "start_time")
        val endTime = body.getString("end_time")?.trim()?.takeIf(String::isNotBlank)?.let {
            offsetDateTime(it, "end_time")
        }
        if (endTime != null && !endTime.isAfter(startTime)) {
            throw IllegalArgumentException("end_time must be later than start_time")
        }
        val orderDetails = normalizeDetails(body.getValue("order_details"), orderType)
        val hasDuration = orderDetails.containsKey("duration_days")
        val frequencyCode = orderDetails.getString("frequency_code")
        if (endTime != null && hasDuration) {
            throw IllegalArgumentException("provide only one temporary order end condition")
        }
        if (orderClass == "TEMPORARY" && endTime == null && !hasDuration && frequencyCode != "STAT") {
            throw IllegalArgumentException("TEMPORARY order requires end_time, duration_days, or STAT frequency")
        }
        return CreateOrderInput(orderType, orderClass, orderContent, doctor, startTime, endTime, orderDetails)
    }

    private data class CreateOrderInput(
        val orderType: String,
        val orderClass: String,
        val orderContent: String,
        val doctor: String,
        val startTime: OffsetDateTime,
        val endTime: OffsetDateTime?,
        val orderDetails: JsonObject,
    )

    private fun normalizeDetails(value: Any?, orderType: String): JsonObject {
        val raw = when (value) {
            null -> JsonObject()
            is JsonObject -> value
            else -> throw IllegalArgumentException("order_details must be a JSON object")
        }
        val allowedKeys = DETAIL_WHITELIST.getValue(orderType)
        val unsupportedKeys = raw.fieldNames().filter { it !in allowedKeys }
        if (unsupportedKeys.isNotEmpty()) {
            throw IllegalArgumentException("order_details contains unsupported keys: ${unsupportedKeys.joinToString(", ")}")
        }

        val normalized = JsonObject()
        for (key in raw.fieldNames()) {
            val fieldValue = raw.getValue(key)
            if (key == "duration_days") {
                if (fieldValue !is Int && fieldValue !is Long) {
                    throw IllegalArgumentException("duration_days must be a positive integer")
                }
                val duration = (fieldValue as Number).toLong()
                if (duration <= 0) throw IllegalArgumentException("duration_days must be a positive integer")
                normalized.put(key, fieldValue)
                continue
            }
            if (fieldValue !is String && fieldValue !is Boolean) {
                throw IllegalArgumentException("$key must be a string")
            }
            if (fieldValue is String) {
                normalized.put(key, fieldValue.trim())
            } else {
                normalized.put(key, fieldValue)
            }
        }

        val requiredKey = REQUIRED_DETAIL_KEY.getValue(orderType)
        if (normalized.getString(requiredKey).isNullOrBlank()) {
            throw IllegalArgumentException("$requiredKey is required for $orderType order")
        }

        val hasFrequencyCode = normalized.containsKey("frequency_code")
        val hasFrequencyName = normalized.containsKey("frequency_name")
        if (hasFrequencyCode != hasFrequencyName) {
            throw IllegalArgumentException("frequency_code and frequency_name must be provided together")
        }
        return normalized
    }

    private fun orderJson(row: Row, taskId: String? = null): JsonObject {
        val orderType = row.getString("order_type")
        val orderClass = row.getString("order_class")
        return JsonObject()
            .put("id", row.getString("id"))
            .put("encounter_id", row.getString("encounter_id"))
            .put("order_type", orderType)
            .put("order_type_label", ORDER_TYPE_LABELS[orderType])
            .put("order_class", orderClass)
            .put("order_class_label", orderClass?.let { ORDER_CLASS_LABELS[it] })
            .put("order_content", row.getString("order_content"))
            .put("order_details", row.getValue("order_details") as? JsonObject)
            .put("start_time", row.getOffsetDateTime("start_time")?.toString())
            .put("end_time", row.getOffsetDateTime("end_time")?.toString())
            .put("doctor", row.getString("doctor"))
            .put("status", row.getString("status"))
            .put("nurse_checked_by", row.getString("nurse_checked_by"))
            .put("nurse_checked_at", row.getOffsetDateTime("nurse_checked_at")?.toString())
            .put("task_id", taskId ?: row.getString("task_id"))
            .put("created_at", row.getOffsetDateTime("created_at")?.toString())
            .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    private fun businessDate(value: OffsetDateTime): LocalDate =
        value.atZoneSameInstant(businessZone).toLocalDate()

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))


    private fun requiredText(body: JsonObject, key: String): String =
        body.getString(key)?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$key is required")

    private fun validStatus(value: String?, allowed: Set<String>, label: String): String =
        value?.takeIf { it in allowed }
            ?: throw IllegalArgumentException("invalid $label, must be one of: $allowed")

    private fun offsetDateTime(value: String, field: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("$field must be an ISO-8601 offset date-time")
        }
}
