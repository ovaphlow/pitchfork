package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.BillItems.BILL_ITEMS
import com.ovaphlow.crate.database.gen.healthcare.tables.Bills.BILLS
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.FeeItems.FEE_ITEMS
import com.ovaphlow.crate.database.gen.healthcare.tables.Payments.PAYMENTS
import com.ovaphlow.crate.database.gen.nursing.tables.NursingAssessments.NURSING_ASSESSMENTS
import com.ovaphlow.crate.nursing.ConflictException
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.Query
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/** 同 encounter 同账期重复生成账单（409）。 */
class DuplicateBillException(message: String) : Exception(message)

/**
 * 账单服务（按月自动计费 + 手工加项；养老费用管理）。
 *
 * 业务规则（服务端强制）：
 *  1. 生成：账期 = 自然月（体 {month: "YYYY-MM"}），首/尾月按实际在院日裁剪；
 *     床位费 = 单价 × 闭区间在院天数（入住日与离院日均计费）；
 *     护理费 = 按 nursing_assessments.result_level 分段（生效日 = assess_date，
 *     同日多份取最新 created_at），每区间 = 等级字典单价 × 天数；
 *     伙食费 = 账期内就餐执行折合餐次 × 单价（正常=全额、部分=半价、未就餐/拒食=0）。
 *  2. 计费单价取费用项目字典启用项：床位/伙食按分类取唯一启用项；
 *     护理按 分类=护理费 + 名称=result_level 取唯一启用项；
 *     无对应启用字典单价 → 400。停用字典项不可用于新账单（自动）与加项（手工）→ 400。
 *  3. 同 encounter 同账期唯一：事务内按 encounter 行锁串行化 + 预检，重复生成 409。
 *  4. 明细为字典快照（item 编码/名称/单价），来源 自动/手工；
 *     手工加项可覆盖单价（unit_price 缺省取字典单价）；账单初始状态 待缴费。
 *  5. 明细金额 = 单价 × 数量 ROUND_HALF_UP 到分；合计 = 明细之和。
 *  6. 结算收束（023 决策 A 起**唯一入口**是 `HealthcareService.settleEncounterBilling`，
 *     离院/去世不再收束账单）分三步组合：阶段一 资格校验 + 按需生成区间最终账单
 *     （状态 待缴费，创建时不写 settled_at）→ 押金核销 → 阶段二 未结余额合计 →
 *     阶段三 冻结（全部账单置 已结算 + settled_at，逐张写 outstanding_amount 与
 *     write_off_reason）。冻结（encounters.settled_at 非空）后生成/加项一律 409；
 *     不做撤销/重算/红冲。收束时仍有未结余额必须由调用方显式提供减免原因，
 *     否则拒绝（见 `HealthcareService.settleEncounterBilling`）。
 */
class BillService(
    private val pool: Pool,
    private val ctx: org.jooq.DSLContext = DatabaseConfig.createDSL(),
) {
    companion object {
        /** 手工加项写白名单：source/item_code/item_name/bill_id/amount/created_at/updated_at/id 由服务端管控 */
        private val addKeys = setOf("item_id", "unit_price", "quantity", "remark")

        /** 生成写白名单：账期只接受 month */
        private val generateKeys = setOf("month")

        /** 结算请求体唯一允许的键：核销额 + 减免原因（operator/bill_id/amount 等一律 400） */
        private val settlementKeys = setOf(KEY_DEPOSIT_OFFSET, KEY_WRITE_OFF_REASON)

        private const val KEY_DEPOSIT_OFFSET = "deposit_offset"
        private const val KEY_WRITE_OFF_REASON = "write_off_reason"

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        private val monthPattern = Regex("""^(\d{4})-(0[1-9]|1[0-2])$""")

        /** 账期格式错误文案（生成与 precheck 共用的单一来源）。 */
        private const val MONTH_FORMAT_MESSAGE = "month must be in YYYY-MM format"

        /** 自动计费分类（槽位推导、字典匹配与 precheck 计数共用）。 */
        private const val CATEGORY_BED = "床位费"
        private const val CATEGORY_NURSING = "护理费"
        private const val CATEGORY_MEAL = "伙食费"

        /** 养老入住类型（precheck 资格判定；与结算资格校验同一中文枚举口径）。 */
        private const val ENCOUNTER_TYPE_ELDERLY = "ELDERLY_CARE"

        /** precheck 机器可读阻断原因（中文提示由前端映射，后端不引入中文业务文案）。 */
        private const val BLOCK_MISSING_FEE_ITEMS = "missing_fee_items"
        private const val BLOCK_ALREADY_EXISTS = "already_exists"
        private const val BLOCK_NOT_OVERLAPPING = "not_overlapping"
        private const val BLOCK_NO_ADMIT_DATE = "no_admit_date"
        private const val BLOCK_SETTLED = "settled"
        private const val BLOCK_NOT_ELDERLY_ADMISSION = "not_elderly_admission"

        /**
         * 解析并校验结算请求体：只允许 `deposit_offset` 与 `write_off_reason` 两个键。
         *
         *  - 其他键（含 amount/operator/bill_id）→ `unsupported settlement keys: <排序后 joinToString>`；
         *  - `deposit_offset` 的数值校验**委托** [DepositOffsetService.parseOffset]（单一来源，
         *    省略或 0 = 不核销）；
         *  - `write_off_reason` 必须是字符串（否则 400），trim 后为空视为未提供（null），
         *    trim 后超过 500 字符 → 400。
         */
        fun parseSettlementRequest(body: JsonObject): SettlementRequest {
            val extra = body.fieldNames().filter { it !in settlementKeys }.sorted()
            if (extra.isNotEmpty()) {
                throw IllegalArgumentException("unsupported settlement keys: ${extra.joinToString(", ")}")
            }
            // 数值校验单一来源：只把 deposit_offset 交给 DepositOffsetService.parseOffset
            val offsetBody = JsonObject()
            body.getValue(KEY_DEPOSIT_OFFSET)?.let { offsetBody.put(KEY_DEPOSIT_OFFSET, it) }
            val depositOffset = DepositOffsetService.parseOffset(offsetBody)
            val rawReason = body.getValue(KEY_WRITE_OFF_REASON)
            val writeOffReason = when {
                rawReason == null -> null
                rawReason is String -> rawReason.trim().takeIf(String::isNotEmpty)?.also {
                    if (it.length > 500) throw IllegalArgumentException("write_off_reason must not exceed 500 characters")
                }
                else -> throw IllegalArgumentException("write_off_reason must be a string")
            }
            return SettlementRequest(depositOffset, writeOffReason)
        }

        private fun billJson(row: Row, items: List<JsonObject>): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("encounter_id", row.getString("encounter_id"))
                .put("period_start", row.getLocalDate("period_start")?.toString())
                .put("period_end", row.getLocalDate("period_end")?.toString())
                .put("status", row.getString("status"))
                .put("settled_at", row.getOffsetDateTime("settled_at")?.toString())
                .put("outstanding_amount", row.getBigDecimal("outstanding_amount"))
                .put("write_off_reason", row.getString("write_off_reason"))
                .put("total_amount", row.getBigDecimal("total_amount"))
                .put("items", JsonArray(items))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())

        private fun itemJson(row: Row): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("bill_id", row.getString("bill_id"))
                .put("source", row.getString("source"))
                .put("item_code", row.getString("item_code"))
                .put("item_name", row.getString("item_name"))
                .put("unit_price", row.getBigDecimal("unit_price"))
                .put("quantity", row.getBigDecimal("quantity"))
                .put("amount", row.getBigDecimal("amount"))
                .put("remark", row.getString("remark"))
                .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
    }

    // ========================================================================
    //  账单生成（自动计费）
    // ========================================================================

    /**
     * 生成账单：体 {month: "YYYY-MM"}。
     * 账期按自然月裁剪到在院区间；自动计费床位/护理/伙食并落明细快照。
     */
    fun generate(encounterId: String, body: JsonObject, operator: String): Future<JsonObject> {
        val month = try {
            validateMonth(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val periodStart = LocalDate.parse("$month-01")
        val periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth())
        val billId = Ulid.generate()
        val now = OffsetDateTime.now()

        return pool.withTransaction<JsonObject> { connection ->
            requireEncounter(connection, encounterId).compose { encounter ->
                if (encounter.getOffsetDateTime("settled_at") != null) {
                    return@compose Future.failedFuture(
                        ConflictException("encounter billing is settled, cannot generate bills"),
                    )
                }
                val admitDate = encounter.getOffsetDateTime("admit_date")
                    ?: return@compose Future.failedFuture(
                        IllegalArgumentException("encounter has no admit date, cannot bill"),
                    )
                val dischargeDate = encounter.getOffsetDateTime("discharge_date")
                val stayStart = maxOf(admitDate.toLocalDate(), periodStart)
                val stayEnd = minOf(dischargeDate?.toLocalDate() ?: periodEnd, periodEnd)
                if (stayStart.isAfter(stayEnd)) {
                    return@compose Future.failedFuture(
                        IllegalArgumentException("encounter does not overlap month $month"),
                    )
                }
                requireNoDuplicate(connection, encounterId, stayStart, stayEnd).compose {
                    computeAutoItems(connection, encounterId, stayStart, stayEnd).compose { autoItems ->
                        val total = BillingEngine.totalOf(autoItems.map { it.amount })
                        execute(connection, insertBill(billId, encounterId, stayStart, stayEnd, total, now))
                            .compose {
                                insertItems(connection, billId, autoItems, now)
                            }
                            .compose {
                                billDetail(connection, billId)
                            }
                    }
                }.recover { error -> recoverUniqueViolation(error, encounterId, stayStart, stayEnd) }
            }
        }
    }

    // ========================================================================
    //  生成账单前置校验（只读）
    // ========================================================================

    /**
     * 生成账单前置校验（**纯读**，可反复调用，不产生任何写入）：返回结构化数据，
     * 中文提示由前端按 `blocked_by` / `requirements` 映射（后端不引入业务文案）。
     *
     * 资格判定顺序与真实生成路径 [generate] 的校验顺序一致（避免「precheck 说能生成、
     * 生成却报另一个错」），且逐条对应既有错误码：
     *  1. `month` 缺失/非法 → 400（沿用 `month must be in YYYY-MM format`，不触发 SQL）；
     *  2. encounter 不存在 → 404（`encounter not found: <id>`）；
     *  3. 非养老入住 → `not_elderly_admission`（与结算资格校验同序：非养老 400 在最前）；
     *  4. 已收束（`encounters.settled_at` 非空）→ `settled`（生成 409）；
     *  5. 缺 `admit_date` → `no_admit_date`（生成 400）；
     *  6. 账期与在院区间无重合 → `not_overlapping`（生成 400）；
     *  7. 该账期账单已存在（[exactBillExists] 同一语义）→ `already_exists`（生成 409）；
     *  8. 存在 `required && !satisfied` 的槽位 → `missing_fee_items`（生成 400 缺字典）；
     *     否则 `blocked_by = null`、`can_generate = true`。
     *
     * 与生成路径的**唯一差异**：缺字典不抛异常，而是表达为 `requirements` 数据
     * （缺字典是被查询的业务状态，不是请求错误），因此恒为 200。非养老入住没有对应的
     * 生成守卫，precheck 按计划口径收紧（保守方向：宁可先挡）。
     *
     * 槽位推导与 [computeAutoItems] 共用 [deriveBillingSlots]（唯一实现，禁止各算一套）；
     * 账期裁剪与 [generate] 逐字一致：`start = max(月首, admit_date)`、
     * `end = min(月末, discharge_date ?: 月末)`；区间取不到时 `period_start/period_end` 为 null。
     */
    fun precheckBillGeneration(encounterId: String, month: String?): Future<JsonObject> {
        val parsedMonth = try {
            val raw = month ?: throw IllegalArgumentException(MONTH_FORMAT_MESSAGE)
            parseMonthString(raw)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val monthStart = LocalDate.parse("$parsedMonth-01")
        val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())
        return pool.withTransaction { connection ->
            loadEncounter(connection, encounterId).compose { encounter ->
                val admitDate = encounter.getOffsetDateTime("admit_date")?.toLocalDate()
                val dischargeDate = encounter.getOffsetDateTime("discharge_date")?.toLocalDate()
                val stayStart = admitDate?.let { maxOf(it, monthStart) }
                val stayEnd = minOf(dischargeDate ?: monthEnd, monthEnd)
                // 与 [generate] 同一裁剪口径：账期起 > 账期止 = 无重合（区间取不到 → null）
                val interval = if (admitDate != null && stayStart != null && !stayStart.isAfter(stayEnd)) {
                    stayStart to stayEnd
                } else {
                    null
                }
                val blocked = when {
                    encounter.getString("encounter_type") != ENCOUNTER_TYPE_ELDERLY -> BLOCK_NOT_ELDERLY_ADMISSION
                    encounter.getOffsetDateTime("settled_at") != null -> BLOCK_SETTLED
                    admitDate == null -> BLOCK_NO_ADMIT_DATE
                    interval == null -> BLOCK_NOT_OVERLAPPING
                    else -> null
                }
                if (blocked != null) {
                    Future.succeededFuture(precheckJson(parsedMonth, interval, blocked, emptyList()))
                } else {
                    // blocked == null 保证区间可裁剪（否则上面已判 not_overlapping）
                    val period = interval!!
                    exactBillExists(connection, encounterId, period.first, period.second).compose { exists ->
                        precheckRequirements(connection, encounterId, period.first, period.second).map { requirements ->
                            val reason = when {
                                exists -> BLOCK_ALREADY_EXISTS
                                requirements.any { it.required && !it.satisfied } -> BLOCK_MISSING_FEE_ITEMS
                                else -> null
                            }
                            precheckJson(parsedMonth, period, reason, requirements)
                        }
                    }
                }
            }
        }
    }

    /** precheck 响应（字段名与前端 TS 类型逐字一致；`level`/`blocked_by` 取不到时显式 null）。 */
    private fun precheckJson(
        month: String,
        period: Pair<LocalDate, LocalDate>?,
        blockedBy: String?,
        requirements: List<PrecheckRequirement>,
    ): JsonObject {
        val json = JsonObject()
            .put("month", month)
            .put("period_start", period?.first?.toString())
            .put("period_end", period?.second?.toString())
            .put("can_generate", blockedBy == null)
            .put("requirements", JsonArray(requirements.map(::requirementJson)))
        if (blockedBy == null) json.putNull("blocked_by") else json.put("blocked_by", blockedBy)
        return json
    }

    private fun requirementJson(requirement: PrecheckRequirement): JsonObject {
        val json = JsonObject()
            .put("category", requirement.category)
            .put("required", requirement.required)
            .put("enabled_count", requirement.enabledCount)
            .put("satisfied", requirement.satisfied)
        if (requirement.level == null) json.putNull("level") else json.put("level", requirement.level)
        return json
    }

    // ========================================================================
    //  手工加项
    // ========================================================================

    /**
     * 手工加项（自费药/检查费等）：体 {item_id, unit_price?, quantity?, remark?}。
     * 字典项必须存在（404）且启用（400）；unit_price 缺省取字典单价，可覆盖；
     * 加项后重算账单合计。
     */
    fun addItem(billId: String, body: JsonObject, operator: String): Future<JsonObject> {
        val fields = try {
            validateAdd(body)
        } catch (error: IllegalArgumentException) {
            return Future.failedFuture(error)
        }
        val id = Ulid.generate()
        val now = OffsetDateTime.now()
        return pool.withTransaction<JsonObject> { connection ->
            requireBill(connection, billId).compose { bill ->
                requireEncounter(connection, bill.getString("encounter_id")).compose { encounter ->
                    if (encounter.getOffsetDateTime("settled_at") != null) {
                        return@compose Future.failedFuture(
                            ConflictException("encounter billing is settled, cannot add items"),
                        )
                    }
                    when (bill.getString("status")) {
                        BillingEngine.STATUS_SETTLED -> Future.failedFuture(
                            ConflictException("bill is settled, cannot add items"),
                        )
                        BillingEngine.STATUS_PENDING -> requireEnabledItem(connection, fields.itemId)
                            .compose { item ->
                                val unitPrice = fields.unitPrice ?: item.getBigDecimal("unit_price")
                                val itemName = item.getString("name")
                                val itemCode = item.getString("id")
                                val amount = BillingEngine.money(unitPrice, fields.quantity)
                                var insert = ctx.insertInto(BILL_ITEMS)
                                    .set(BILL_ITEMS.ID, id)
                                    .set(BILL_ITEMS.BILL_ID, billId)
                                    .set(BILL_ITEMS.SOURCE, BillingEngine.SOURCE_MANUAL)
                                    .set(BILL_ITEMS.ITEM_CODE, itemCode)
                                    .set(BILL_ITEMS.ITEM_NAME, itemName)
                                    .set(BILL_ITEMS.UNIT_PRICE, unitPrice)
                                    .set(BILL_ITEMS.QUANTITY, fields.quantity)
                                    .set(BILL_ITEMS.AMOUNT, amount)
                                    .set(BILL_ITEMS.CREATED_AT, now)
                                    .set(BILL_ITEMS.UPDATED_AT, now)
                                fields.remark?.let { insert = insert.set(BILL_ITEMS.REMARK, it) }
                                execute(connection, insert).compose {
                                    refreshTotal(connection, billId, now)
                                }.compose {
                                    billDetail(connection, billId)
                                }
                            }
                        else -> Future.failedFuture(
                            IllegalArgumentException(
                                "bill status is not ${BillingEngine.STATUS_PENDING}, cannot add items",
                            ),
                        )
                    }
                }
            }
        }
    }

    // ========================================================================
    //  结算收束（账单收尾的唯一入口：HealthcareService.settleEncounterBilling）
    // ========================================================================

    /** 已收束终态（补结算端点的资格判定）。 */
    private val terminalStatuses = setOf("DISCHARGED", "DECEASED")

    /**
     * 结算请求体（已校验）：`depositOffset` 省略或 0 = 不核销；
     * `writeOffReason` trim 后为空 = 未提供。
     */
    data class SettlementRequest(val depositOffset: BigDecimal, val writeOffReason: String?)

    /** 未结账单（阶段二结果）：余额 = 合计 − Σ缴费，下限 0；只含 > 0 的行。 */
    data class BillOutstanding(val billId: String, val balance: BigDecimal)

    /**
     * 阶段一：资格校验 + 解析区间 + 按需生成区间最终账单（状态 待缴费，不写 settled_at）。
     *
     * 必须在调用方外层事务内执行（同连接）。资格校验顺序与错误码与既有实现逐字一致：
     * 非养老入住 400 → [requireTerminalStatus] 时非 已离院/已去世 409 → 已收束 409 →
     * 缺入住日 400 → 缺离院/去世日 400。
     *
     * 返回形状（自定，供 [HealthcareService] 编排与 [previewSettlement] 复用）：
     * ```
     * {"encounter_id": "enc-1",
     *  "settlement_period": {"start": "2026-09-01", "end": "2026-09-28"} | null,
     *  "final_bill_id": "<新生成账单 id>" | null,
     *  "final_bill_total": <本轮区间最终账单合计，未生成时为 0>}
     * ```
     * `settlement_period` 为 null 表示区间起 > 区间止（不生成）；同账期账单已存在时不重复生成
     * （`final_bill_id` = null、`final_bill_total` = 0）。
     */
    fun prepareSettlement(
        client: SqlClient,
        encounterId: String,
        now: OffsetDateTime,
        requireTerminalStatus: Boolean,
        endDate: LocalDate? = null,
    ): Future<JsonObject> =
        settlementContext(client, encounterId, requireTerminalStatus, endDate).compose { context ->
            planFinalBill(client, encounterId, context.admitDate, context.endDate).compose { plan ->
                if (plan == null || plan.exists) {
                    Future.succeededFuture(preparationJson(encounterId, plan, null))
                } else {
                    insertFinalBill(client, encounterId, plan, now)
                        .map { billId -> preparationJson(encounterId, plan, billId) }
                }
            }
        }

    /**
     * 阶段二：该 encounter 全部 `待缴费` 账单的未结余额（合计 − Σ缴费，下限 0），
     * 只返回 > 0 的行；与 [DepositOffsetService] 的目标账单口径一致，一次聚合查询
     * （bills LEFT JOIN payments ... GROUP BY ...），按账期升序、同账期按 id 升序。
     */
    fun outstandingBills(client: SqlClient, encounterId: String): Future<List<BillOutstanding>> {
        val paid = DSL.coalesce(DSL.sum(PAYMENTS.AMOUNT), BigDecimal.ZERO)
        val query = ctx.select(
            BILLS.ID,
            BILLS.PERIOD_START,
            BILLS.PERIOD_END,
            BILLS.TOTAL_AMOUNT,
            paid.`as`("paid_amount"),
            BILLS.TOTAL_AMOUNT.subtract(paid).`as`("balance"),
        ).from(BILLS)
            .leftJoin(PAYMENTS).on(PAYMENTS.BILL_ID.eq(BILLS.ID))
            .where(BILLS.ENCOUNTER_ID.eq(encounterId))
            .and(BILLS.STATUS.eq(BillingEngine.STATUS_PENDING))
            .groupBy(BILLS.ID, BILLS.PERIOD_START, BILLS.PERIOD_END, BILLS.TOTAL_AMOUNT)
            .orderBy(BILLS.PERIOD_START.asc(), BILLS.ID.asc())
        return execute(client, query).map { rows ->
            rows.mapNotNull { row ->
                val balance = row.getBigDecimal("balance")
                if (balance == null || balance.signum() <= 0) {
                    null
                } else {
                    BillOutstanding(row.getString("id"), balance)
                }
            }
        }
    }

    /**
     * 阶段三：冻结 —— 该 encounter 全部 bills 置 `已结算` + `settled_at = now`，
     * 逐张写收束时刻的未结快照与减免原因；最后把 encounters.settled_at 置位并返回 encounter
     * （形状与既有响应一致）。
     *
     *  - [outstanding]（阶段二结果，> 0 的行）：写实际余额 + 传入原因；
     *  - 其余账单：`outstanding_amount = 0`、`write_off_reason = NULL`（未结事实不可考）；
     *  - 账单数量很小，逐张 UPDATE（不用 JSON/字符串拼接 SQL）。
     */
    fun freezeSettlement(
        client: SqlClient,
        encounterId: String,
        now: OffsetDateTime,
        outstanding: List<BillOutstanding>,
        writeOffReason: String?,
    ): Future<JsonObject> {
        val outstandingById = outstanding.associateBy { it.billId }
        return billIdsOf(client, encounterId).compose { billIds ->
            var chain: Future<Unit> = Future.succeededFuture()
            for (billId in billIds) {
                val row = outstandingById[billId]
                val balance = row?.balance ?: BigDecimal.ZERO
                val reason = if (row == null) null else writeOffReason
                chain = chain.compose {
                    execute(client, freezeBillQuery(billId, balance, reason, now)).map { Unit }
                }
            }
            chain
        }.compose {
            execute(
                client,
                ctx.update(ENCOUNTERS)
                    .set(ENCOUNTERS.SETTLED_AT, now)
                    .set(ENCOUNTERS.UPDATED_AT, now)
                    .where(ENCOUNTERS.ID.eq(encounterId)),
            )
        }.compose {
            getEncounter(client, encounterId)
        }
    }

    /**
     * 收束预览（只读）：与执行路径共用 [settlementContext] / [planFinalBill] /
     * [outstandingBills] 与押金余额口径 [DepositService.balance]，**不写任何行**，可反复调用。
     *
     * 返回字段（前端按此对接）：
     *  - `settlement_period`：解析出的区间 `{start,end}`，无区间时 null；
     *  - `final_bill_total`：区间为 null 或同账期账单已存在时为 0，否则 = 自动计费合计；
     *  - `pending_balance`：既有 `待缴费` 账单未结合计（核销前）；
     *  - `outstanding_total`：`pending_balance + final_bill_total`；
     *  - `deposit_balance`：押金余额（Σ登记 − Σ退押 − Σ核销）；
     *  - `max_offset`：`min(deposit_balance, outstanding_total)`；
     *  - `requires_write_off`：`outstanding_total > max_offset`。
     *
     * 资格校验与执行路径同一套（非养老 400 / 不存在 404 / 未离院去世或已收束 409）。
     */
    fun previewSettlement(
        client: SqlClient,
        encounterId: String,
        requireTerminalStatus: Boolean,
    ): Future<JsonObject> =
        settlementContext(client, encounterId, requireTerminalStatus, null).compose { context ->
            planFinalBill(client, encounterId, context.admitDate, context.endDate).compose { plan ->
                outstandingBills(client, encounterId).compose { outstanding ->
                    val pending = outstanding.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.balance) }
                    val finalTotal = plan?.total ?: BigDecimal.ZERO
                    DepositService.balance(client, encounterId).map { deposit ->
                        val outstandingTotal = pending.add(finalTotal)
                        val maxOffset = deposit.min(outstandingTotal)
                        JsonObject()
                            .put("settlement_period", periodJson(plan))
                            .put("final_bill_total", finalTotal)
                            .put("pending_balance", pending)
                            .put("outstanding_total", outstandingTotal)
                            .put("deposit_balance", deposit)
                            .put("max_offset", maxOffset)
                            .put("requires_write_off", outstandingTotal > maxOffset)
                    }
                }
            }
        }

    /** 阶段一/预览共用的中间结果 JSON（区间、区间最终账单 id 与合计）。 */
    private fun preparationJson(encounterId: String, plan: FinalBillPlan?, billId: String?): JsonObject =
        JsonObject()
            .put("encounter_id", encounterId)
            .put("settlement_period", periodJson(plan))
            .put("final_bill_id", billId)
            .put("final_bill_total", plan?.total ?: BigDecimal.ZERO)

    private fun periodJson(plan: FinalBillPlan?): JsonObject? =
        plan?.let { JsonObject().put("start", it.start.toString()).put("end", it.end.toString()) }

    /** 结算资格上下文：通过资格校验后的 encounter 与收束区间止。 */
    private data class SettlementContext(val encounterId: String, val admitDate: LocalDate, val endDate: LocalDate)

    /**
     * 收束资格校验（阶段一与预览共用；顺序与错误码与既有实现逐字一致）：
     * 非养老 400 → [requireTerminalStatus] 时非终态 409 → 已收束 409 → 缺入住日 400 →
     * 缺离院/去世日 400。
     */
    private fun settlementContext(
        client: SqlClient,
        encounterId: String,
        requireTerminalStatus: Boolean,
        endDate: LocalDate?,
    ): Future<SettlementContext> =
        requireEncounter(client, encounterId).compose { encounter ->
            if (encounter.getString("encounter_type") != "ELDERLY_CARE") {
                return@compose Future.failedFuture(
                    IllegalArgumentException("encounter is not an elderly admission"),
                )
            }
            val status = encounter.getString("status")
            if (requireTerminalStatus && status !in terminalStatuses) {
                return@compose Future.failedFuture(
                    ConflictException("encounter is not discharged or deceased, cannot settle billing"),
                )
            }
            if (encounter.getOffsetDateTime("settled_at") != null) {
                return@compose Future.failedFuture(
                    ConflictException("encounter billing is already settled"),
                )
            }
            val admitDate = encounter.getOffsetDateTime("admit_date")?.toLocalDate()
                ?: return@compose Future.failedFuture(
                    IllegalArgumentException("encounter has no admit date, cannot settle"),
                )
            val end = if (requireTerminalStatus) {
                when (status) {
                    "DISCHARGED" -> encounter.getOffsetDateTime("discharge_date")?.toLocalDate()
                        ?: return@compose Future.failedFuture(
                            IllegalArgumentException("encounter has no discharge date, cannot settle"),
                        )
                    "DECEASED" -> encounter.getOffsetDateTime("death_date")?.toLocalDate()
                        ?: return@compose Future.failedFuture(
                            IllegalArgumentException("encounter has no death date, cannot settle"),
                        )
                    else -> return@compose Future.failedFuture(
                        ConflictException("encounter is not discharged or deceased, cannot settle billing"),
                    )
                }
            } else {
                endDate ?: return@compose Future.failedFuture(
                    IllegalArgumentException("end date is required for settlement"),
                )
            }
            Future.succeededFuture(SettlementContext(encounterId, admitDate, end))
        }

    /** 区间最终账单计划（只读计算）：区间、自动明细、合计与「同账期已存在」标记。 */
    private data class FinalBillPlan(
        val start: LocalDate,
        val end: LocalDate,
        val items: List<AutoItem>,
        val total: BigDecimal,
        val exists: Boolean,
    )

    /**
     * 计算区间最终账单计划（只读，执行路径与预览**共用同一实现**）：
     *  - 区间 = [BillingEngine.settlementInterval]（区间起 = MAX(已结算账期末日)+1 或入住日，
     *    区间止 = 离院/去世日）；区间起 > 区间止 → null；
     *  - 与既有账单账期完全一致（[exactBillExists]）时不重复生成（`exists = true`、合计 0）；
     *  - 否则自动计费（床位/护理/伙食），无可用计费项时按 0 元封口（空明细、合计 0）。
     */
    private fun planFinalBill(
        client: SqlClient,
        encounterId: String,
        admitDate: LocalDate,
        endDate: LocalDate,
    ): Future<FinalBillPlan?> =
        maxSettledPeriodEnd(client, encounterId).compose { maxEnd ->
            val interval = BillingEngine.settlementInterval(admitDate, endDate, listOfNotNull(maxEnd))
            if (interval == null) {
                Future.succeededFuture<FinalBillPlan?>(null)
            } else {
                val (start, end) = interval
                exactBillExists(client, encounterId, start, end).compose { exists ->
                    if (exists) {
                        Future.succeededFuture(FinalBillPlan(start, end, emptyList(), BigDecimal.ZERO, true))
                    } else {
                        computeBillItems(client, encounterId, start, end).map { items ->
                            FinalBillPlan(start, end, items, BillingEngine.totalOf(items.map { it.amount }), false)
                        }
                    }
                }
            }
        }

    /** 区间最终账单：以 `待缴费` 建立（创建时不写 settled_at），明细按自动计费；返回账单 id。 */
    private fun insertFinalBill(
        client: SqlClient,
        encounterId: String,
        plan: FinalBillPlan,
        now: OffsetDateTime,
    ): Future<String> {
        val billId = Ulid.generate()
        return execute(
            client,
            ctx.insertInto(BILLS)
                .set(BILLS.ID, billId)
                .set(BILLS.ENCOUNTER_ID, encounterId)
                .set(BILLS.PERIOD_START, plan.start)
                .set(BILLS.PERIOD_END, plan.end)
                .set(BILLS.STATUS, BillingEngine.STATUS_PENDING)
                .set(BILLS.TOTAL_AMOUNT, plan.total)
                .set(BILLS.CREATED_AT, now)
                .set(BILLS.UPDATED_AT, now),
        ).compose {
            insertItems(client, billId, plan.items, now)
        }.map { billId }
    }

    /** 自动计费明细：无可用计费项（400）时按 0 元封口处理为空明细（沿用既有 recover 口径）。 */
    private fun computeBillItems(
        client: SqlClient,
        encounterId: String,
        start: LocalDate,
        end: LocalDate,
    ): Future<List<AutoItem>> =
        computeAutoItems(client, encounterId, start, end).recover { error ->
            if (error is IllegalArgumentException) {
                // 无可计费项（如字典无启用单价）：0 元封口账单，账期正确即封口
                Future.succeededFuture(emptyList())
            } else {
                Future.failedFuture(error)
            }
        }

    /** 该 encounter 全部账单 id（冻结逐张写未结快照；账单数量很小）。 */
    private fun billIdsOf(client: SqlClient, encounterId: String): Future<List<String>> =
        execute(
            client,
            ctx.select(BILLS.ID).from(BILLS)
                .where(BILLS.ENCOUNTER_ID.eq(encounterId))
                .orderBy(BILLS.ID.asc()),
        ).map { rows -> rows.map { row -> row.getString("id") } }

    /**
     * 单张账单冻结：状态 已结算 + settled_at = now + 未结快照；
     * 未结行写减免原因，其余以 `cast(null as varchar)` 显式置 NULL。
     */
    private fun freezeBillQuery(
        billId: String,
        outstandingAmount: BigDecimal,
        writeOffReason: String?,
        now: OffsetDateTime,
    ): Query {
        var query = ctx.update(BILLS)
            .set(BILLS.STATUS, BillingEngine.STATUS_SETTLED)
            .set(BILLS.SETTLED_AT, now)
            .set(BILLS.OUTSTANDING_AMOUNT, outstandingAmount)
            .set(BILLS.UPDATED_AT, now)
        query = if (writeOffReason == null) {
            query.set(BILLS.WRITE_OFF_REASON, DSL.castNull(String::class.java))
        } else {
            query.set(BILLS.WRITE_OFF_REASON, writeOffReason)
        }
        return query.where(BILLS.ID.eq(billId))
    }

    /** 已结算账期末日最大值（状态 已结清/已结算）：无则 null。 */
    private fun maxSettledPeriodEnd(client: SqlClient, encounterId: String): Future<LocalDate?> =
        execute(
            client,
            ctx.select(DSL.max(BILLS.PERIOD_END).`as`("max_end")).from(BILLS)
                .where(BILLS.ENCOUNTER_ID.eq(encounterId))
                .and(BILLS.STATUS.`in`(BillingEngine.STATUS_PAID, BillingEngine.STATUS_SETTLED)),
        ).map { rows -> rows.iterator().asSequence().firstOrNull()?.getLocalDate("max_end") }

    /** 与既有账单账期完全一致（唯一约束冲突判定）。 */
    private fun exactBillExists(
        client: SqlClient,
        encounterId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Future<Boolean> =
        execute(
            client,
            ctx.select(DSL.count().`as`("total")).from(BILLS)
                .where(BILLS.ENCOUNTER_ID.eq(encounterId))
                .and(BILLS.PERIOD_START.eq(periodStart))
                .and(BILLS.PERIOD_END.eq(periodEnd)),
        ).map { rows ->
            val total = rows.iterator().asSequence().firstOrNull()?.getLong("total") ?: 0L
            total > 0
        }

    /** 结算后返回 encounter（含 settled_at 冻结标记）。 */
    private fun getEncounter(client: SqlClient, encounterId: String): Future<JsonObject> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                Future.succeededFuture(
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
                        .put("status", row.getString("status"))
                        .put("settled_at", row.getOffsetDateTime("settled_at")?.toString())
                        .put("created_at", row.getOffsetDateTime("created_at")?.toString())
                        .put("updated_at", row.getOffsetDateTime("updated_at")?.toString())
                )
            } ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    // ========================================================================
    //  查询
    // ========================================================================

    /** 账单详情（含明细）：不存在 404。 */
    fun getBill(billId: String): Future<JsonObject> =
        execute(pool, selectBill(billId)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { billRow ->
                execute(pool, selectItems(billId)).map { itemRows ->
                    billJson(billRow, itemRows.map(::itemJson))
                }
            } ?: Future.failedFuture(HealthcareNotFoundException("bill not found: $billId"))
        }

    /** 按 encounter 查询账单列表（账期倒序分页），返回 {records, meta:{total}}。 */
    fun listBills(encounterId: String, limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(DSL.count().`as`("total")).from(BILLS)
            .where(BILLS.ENCOUNTER_ID.eq(encounterId))
        val dataQuery = ctx.select(
            BILLS.ID,
            BILLS.ENCOUNTER_ID,
            BILLS.PERIOD_START,
            BILLS.PERIOD_END,
            BILLS.STATUS,
            BILLS.SETTLED_AT,
            BILLS.OUTSTANDING_AMOUNT,
            BILLS.WRITE_OFF_REASON,
            BILLS.TOTAL_AMOUNT,
            BILLS.CREATED_AT,
            BILLS.UPDATED_AT,
        ).from(BILLS)
            .where(BILLS.ENCOUNTER_ID.eq(encounterId))
            .orderBy(BILLS.PERIOD_START.desc(), BILLS.ID.desc())
            .limit(limit)
            .offset(offset)
        return execute(pool, countQuery).compose { countRows ->
            val total = countRows.iterator().next().getLong("total") ?: 0L
            execute(pool, dataQuery).map { dataRows ->
                JsonObject()
                    .put("records", JsonArray(dataRows.map { row ->
                        billJson(row, emptyList())
                    }))
                    .put("meta", JsonObject().put("total", total))
            }
        }
    }

    // ========================================================================
    //  内部实现：校验
    // ========================================================================

    private data class AddFields(
        val itemId: String,
        val unitPrice: BigDecimal?,
        val quantity: BigDecimal,
        val remark: String?,
    )

    private fun validateMonth(body: JsonObject): String {
        rejectForbiddenKeys(body, generateKeys, "bill")
        val raw = body.getValue("month") ?: throw IllegalArgumentException("month is required")
        return parseMonthString(raw)
    }

    /** `month` 值格式校验（生成与 precheck 共用的单一实现）：非字符串 400，非 `YYYY-MM` 400。 */
    private fun parseMonthString(raw: Any?): String {
        val month = raw as? String ?: throw IllegalArgumentException("month must be a string")
        if (!monthPattern.matches(month)) {
            throw IllegalArgumentException(MONTH_FORMAT_MESSAGE)
        }
        return month
    }

    private fun validateAdd(body: JsonObject): AddFields {
        rejectForbiddenKeys(body, addKeys, "bill item")
        val rawItemId = body.getValue("item_id") ?: throw IllegalArgumentException("item_id is required")
        val itemId = rawItemId as? String ?: throw IllegalArgumentException("item_id must be a string")
        val trimmedId = itemId.trim()
        if (trimmedId.isEmpty()) throw IllegalArgumentException("item_id must not be blank")
        if (trimmedId.length > 32) throw IllegalArgumentException("item_id must not exceed 32 characters")
        val unitPrice = body.containsKey("unit_price").let { present ->
            if (!present) null else positiveDecimal(body, "unit_price", required = true)
        }
        val quantity = positiveDecimal(body, "quantity", required = false) ?: BigDecimal.ONE
        val remark = body.getString("remark")?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it.length > 500) throw IllegalArgumentException("remark must not exceed 500 characters")
        }
        return AddFields(trimmedId, unitPrice, quantity, remark)
    }

    private fun positiveDecimal(body: JsonObject, key: String, required: Boolean): BigDecimal? {
        val raw = body.getValue(key) ?: if (required) throw IllegalArgumentException("$key is required") else return null
        val value = (raw as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$key must be a number")
        if (!value.isFinite() || value <= 0) {
            throw IllegalArgumentException("$key must be a positive number")
        }
        val decimal = BigDecimal.valueOf(value)
        if (decimal.scale() > 2) {
            throw IllegalArgumentException("$key must have at most 2 decimal places")
        }
        if (decimal > maxAmount) {
            throw IllegalArgumentException("$key must not exceed $maxAmount")
        }
        return decimal
    }

    private fun rejectForbiddenKeys(body: JsonObject, allowed: Set<String>, label: String) {
        val extra = body.fieldNames().filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            throw IllegalArgumentException("unsupported $label keys: ${extra.joinToString(", ")}")
        }
    }

    // ========================================================================
    //  内部实现：自动计费
    // ========================================================================

    private data class AutoItem(
        val itemCode: String,
        val itemName: String,
        val unitPrice: BigDecimal,
        val quantity: BigDecimal,
        val amount: BigDecimal,
    )

    private data class FeeItemRow(val id: String, val category: String, val name: String, val unitPrice: BigDecimal)

    /**
     * 本账期计费槽位（槽位推导结果）：`required = false` 表示本账期不会用到该分类
     * （账期内无就餐记录时的伙食费）。`quantity` 为计价数量（床位/护理 = 闭区间天数，伙食 = 折合餐次）。
     */
    private data class BillingSlot(
        val category: String,
        val level: String?,
        val required: Boolean,
        val quantity: BigDecimal,
    )

    /** precheck 槽位结果：`satisfied = enabledCount == 1`（0 条与多条都不满足，由服务端判定）。 */
    private data class PrecheckRequirement(
        val category: String,
        val level: String?,
        val required: Boolean,
        val enabledCount: Int,
        val satisfied: Boolean,
    )

    /**
     * 启用费用项目字典（一次查询）：[computeAutoItems] 取项与 precheck 槽位计数**共用**。
     */
    private fun enabledFeeItemsQuery(): Query =
        ctx.select(
            FEE_ITEMS.ID,
            FEE_ITEMS.CATEGORY,
            FEE_ITEMS.NAME,
            FEE_ITEMS.UNIT_PRICE,
        ).from(FEE_ITEMS)
            .where(FEE_ITEMS.STATUS.eq(FeeItemService.STATUS_ENABLED))

    private fun feeItemRowOf(row: Row): FeeItemRow =
        FeeItemRow(
            id = row.getString("id"),
            category = row.getString("category"),
            name = row.getString("name"),
            unitPrice = row.getBigDecimal("unit_price"),
        )

    /** 槽位与启用字典项的匹配口径（取项与计数共用）：护理费按 `level` 匹配 `name`。 */
    private fun FeeItemRow.matchesSlot(slot: BillingSlot): Boolean =
        category == slot.category && (slot.level == null || name == slot.level)

    /**
     * 本账期需要哪些费用项目槽位（**唯一推导实现**，[computeAutoItems] 与 [precheckBillGeneration]
     * 共用，禁止各算一套）：
     * ```
     * 槽位 = [床位费] + [护理费(level) for 账期内每个生效等级] + ([伙食费] if 折合餐次 > 0)
     * ```
     * 生效等级取法完全沿用 [BillingEngine.nursingSegments]（同日多份取 `created_at` 最新、
     * 账期前最后一次评估决定首段、账期内每个变更点各成一段）；账期内无就餐时
     * 伙食费槽位 `required = false`（不参与计价、也不构成缺项）。
     */
    private fun deriveBillingSlots(
        client: SqlClient,
        encounterId: String,
        stayStart: LocalDate,
        stayEnd: LocalDate,
    ): Future<List<BillingSlot>> {
        val bedDays = BigDecimal.valueOf(BillingEngine.inclusiveDays(stayStart, stayEnd))
        val segmentsFuture = loadAssessments(client, encounterId, stayEnd).map { assessments ->
            BillingEngine.nursingSegments(
                stayStart,
                stayEnd,
                assessments.map { (date, createdAt, level) ->
                    BillingEngine.Assessment(date, createdAt, level)
                },
            )
        }
        val mealFuture = loadMealStatuses(client, encounterId, stayStart, stayEnd)
            .map { BillingEngine.mealQuantity(it) }
        return segmentsFuture.compose { segments ->
            mealFuture.map { mealQuantity ->
                val slots = mutableListOf(BillingSlot(CATEGORY_BED, null, required = true, quantity = bedDays))
                slots += segments.map {
                    BillingSlot(CATEGORY_NURSING, it.level, required = true, quantity = BigDecimal.valueOf(it.days))
                }
                slots += BillingSlot(
                    CATEGORY_MEAL,
                    null,
                    required = mealQuantity.signum() > 0,
                    quantity = mealQuantity,
                )
                slots
            }
        }
    }

    /** 槽位取唯一启用字典项并计价（计价口径与错误文案逐字不变）。 */
    private fun autoItemOf(items: List<FeeItemRow>, slot: BillingSlot): AutoItem {
        val item = requireSingleEnabled(items.filter { it.matchesSlot(slot) }, slot.category, slot.level)
        return AutoItem(
            itemCode = item.id,
            itemName = item.name,
            unitPrice = item.unitPrice,
            quantity = slot.quantity,
            amount = BillingEngine.money(item.unitPrice, slot.quantity),
        )
    }

    /** 槽位 → 启用字典项计数：一次查询取全部启用项，按 `(category, name/level)` 匹配计数。 */
    private fun precheckRequirements(
        client: SqlClient,
        encounterId: String,
        stayStart: LocalDate,
        stayEnd: LocalDate,
    ): Future<List<PrecheckRequirement>> =
        execute(client, enabledFeeItemsQuery()).compose { rows ->
            val items = rows.map(::feeItemRowOf)
            deriveBillingSlots(client, encounterId, stayStart, stayEnd).map { slots ->
                slots.map { slot ->
                    val count = items.count { it.matchesSlot(slot) }
                    PrecheckRequirement(slot.category, slot.level, slot.required, count, count == 1)
                }
            }
        }

    /** 计算自动明细：床位（在院天数）/护理（等级分段天数）/伙食（折合餐次）。 */
    private fun computeAutoItems(
        connection: SqlClient,
        encounterId: String,
        stayStart: LocalDate,
        stayEnd: LocalDate,
    ): Future<List<AutoItem>> =
        execute(connection, enabledFeeItemsQuery()).compose { rows ->
            val items = rows.map(::feeItemRowOf)
            // 取项基于同一份槽位推导结果；required = false 的槽位（账期内无就餐的伙食费）不参与计价
            deriveBillingSlots(connection, encounterId, stayStart, stayEnd).compose { slots ->
                try {
                    Future.succeededFuture(slots.filter { it.required }.map { slot -> autoItemOf(items, slot) })
                } catch (error: IllegalArgumentException) {
                    Future.failedFuture(error)
                }
            }
        }

    /** 分类/等级取唯一启用字典项：无 → 400，多个 → 400。 */
    private fun requireSingleEnabled(items: List<FeeItemRow>, category: String, level: String? = null): FeeItemRow {
        val label = if (level != null) "nursing level $level" else "category $category"
        return when {
            items.isEmpty() -> throw IllegalArgumentException("no enabled fee item for $label")
            items.size > 1 -> throw IllegalArgumentException("multiple enabled fee items for $label, expected exactly one")
            else -> items.single()
        }
    }

    // ========================================================================
    //  内部实现：数据库访问
    // ========================================================================

    /** 事务内按 encounter 行锁读：不存在 404。 */
    private fun requireEncounter(client: SqlClient, encounterId: String): Future<Row> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId)).forUpdate()).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    /** 只读 encounter 读取（precheck 不加行锁，纯读）：不存在 404。 */
    private fun loadEncounter(client: SqlClient, encounterId: String): Future<Row> =
        execute(client, ctx.selectFrom(ENCOUNTERS).where(ENCOUNTERS.ID.eq(encounterId))).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("encounter not found: $encounterId"))
        }

    /** 同账期重复预检：行锁已串行化，命中即 409。 */
    private fun requireNoDuplicate(
        client: SqlClient,
        encounterId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Future<Unit> =
        execute(
            client,
            ctx.select(DSL.count().`as`("total")).from(BILLS)
                .where(BILLS.ENCOUNTER_ID.eq(encounterId))
                .and(BILLS.PERIOD_START.eq(periodStart))
                .and(BILLS.PERIOD_END.eq(periodEnd)),
        ).map { rows ->
            val total = rows.iterator().next().getLong("total") ?: 0L
            if (total > 0) {
                throw DuplicateBillException(
                    "bill for encounter $encounterId and period $periodStart ~ $periodEnd already exists",
                )
            }
            Unit
        }

    /** 唯一约束兜底（并发竞态）：23505 / 约束名 → 409。 */
    private fun recoverUniqueViolation(
        error: Throwable,
        encounterId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Future<JsonObject> {
        val message = error.message ?: ""
        val isUniqueViolation = message.contains("uq_bills_encounter_period") ||
            (error as? io.vertx.pgclient.PgException)?.code == "23505"
        return if (isUniqueViolation) {
            Future.failedFuture(
                DuplicateBillException("bill for encounter $encounterId and period $periodStart ~ $periodEnd already exists"),
            )
        } else {
            Future.failedFuture(error)
        }
    }

    /** 护理评估（账期内及账期前生效的等级来源）：assess_date ≤ 账期止。 */
    private fun loadAssessments(
        client: SqlClient,
        encounterId: String,
        periodEnd: LocalDate,
    ): Future<List<Triple<LocalDate, OffsetDateTime, String>>> {
        val query = ctx.select(
            NURSING_ASSESSMENTS.ASSESS_DATE,
            NURSING_ASSESSMENTS.CREATED_AT,
            NURSING_ASSESSMENTS.RESULT_LEVEL,
        ).from(NURSING_ASSESSMENTS)
            .where(NURSING_ASSESSMENTS.ENCOUNTER_ID.eq(encounterId))
            .and(NURSING_ASSESSMENTS.ASSESS_DATE.le(periodEnd))
        return execute(client, query).map { rows ->
            rows.mapNotNull { row ->
                val level = row.getString("result_level")?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val date = row.getLocalDate("assess_date") ?: return@mapNotNull null
                val createdAt = row.getOffsetDateTime("created_at") ?: OffsetDateTime.MIN
                Triple(date, createdAt, level)
            }
        }
    }

    /** 账期内就餐执行状态（跨 dining schema：executions → roster_items → rosters）。 */
    private fun loadMealStatuses(
        client: SqlClient,
        encounterId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Future<List<String>> {
        val mealExecutions = DSL.table(DSL.name("dining", "dining_meal_executions")).`as`("dme")
        val rosterItems = DSL.table(DSL.name("dining", "dining_roster_items")).`as`("dri")
        val rosters = DSL.table(DSL.name("dining", "dining_rosters")).`as`("dr")
        val cDmeStatus = DSL.field(DSL.name("dme", "status"), String::class.java)
        val cDmeRosterItemId = DSL.field(DSL.name("dme", "roster_item_id"), String::class.java)
        val cDriId = DSL.field(DSL.name("dri", "id"), String::class.java)
        val cDriEncounterId = DSL.field(DSL.name("dri", "encounter_id"), String::class.java)
        val cDriRosterId = DSL.field(DSL.name("dri", "roster_id"), String::class.java)
        val cDrId = DSL.field(DSL.name("dr", "id"), String::class.java)
        val cDrMenuDate = DSL.field(DSL.name("dr", "menu_date"), LocalDate::class.java)

        val query = ctx.select(cDmeStatus).from(mealExecutions)
            .join(rosterItems).on(cDmeRosterItemId.eq(cDriId))
            .join(rosters).on(cDriRosterId.eq(cDrId))
            .where(cDriEncounterId.eq(encounterId))
            .and(cDrMenuDate.between(periodStart, periodEnd))
        return execute(client, query).map { rows -> rows.map { row -> row.getString("status") ?: "" } }
    }

    private fun insertBill(
        id: String,
        encounterId: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        total: BigDecimal,
        now: OffsetDateTime,
    ): Query =
        ctx.insertInto(BILLS)
            .set(BILLS.ID, id)
            .set(BILLS.ENCOUNTER_ID, encounterId)
            .set(BILLS.PERIOD_START, periodStart)
            .set(BILLS.PERIOD_END, periodEnd)
            .set(BILLS.STATUS, BillingEngine.STATUS_PENDING)
            .set(BILLS.TOTAL_AMOUNT, total)
            .set(BILLS.CREATED_AT, now)
            .set(BILLS.UPDATED_AT, now)

    private fun insertItems(client: SqlClient, billId: String, items: List<AutoItem>, now: OffsetDateTime): Future<Unit> {
        var chain: Future<Unit> = Future.succeededFuture()
        for (item in items) {
            val id = Ulid.generate()
            val query = ctx.insertInto(BILL_ITEMS)
                .set(BILL_ITEMS.ID, id)
                .set(BILL_ITEMS.BILL_ID, billId)
                .set(BILL_ITEMS.SOURCE, BillingEngine.SOURCE_AUTO)
                .set(BILL_ITEMS.ITEM_CODE, item.itemCode)
                .set(BILL_ITEMS.ITEM_NAME, item.itemName)
                .set(BILL_ITEMS.UNIT_PRICE, item.unitPrice)
                .set(BILL_ITEMS.QUANTITY, item.quantity)
                .set(BILL_ITEMS.AMOUNT, item.amount)
                .set(BILL_ITEMS.CREATED_AT, now)
                .set(BILL_ITEMS.UPDATED_AT, now)
            chain = chain.compose { execute(client, query).map { Unit } }
        }
        return chain
    }

    /** 加项后按明细之和重算账单合计。 */
    private fun refreshTotal(client: SqlClient, billId: String, now: OffsetDateTime): Future<Unit> {
        val sumQuery = ctx.select(DSL.sum(BILL_ITEMS.AMOUNT).`as`("total")).from(BILL_ITEMS)
            .where(BILL_ITEMS.BILL_ID.eq(billId))
        return execute(client, sumQuery).compose { rows ->
            val total = rows.iterator().next().getBigDecimal("total") ?: BigDecimal.ZERO
            execute(
                client,
                ctx.update(BILLS)
                    .set(BILLS.TOTAL_AMOUNT, total)
                    .set(BILLS.UPDATED_AT, now)
                    .where(BILLS.ID.eq(billId)),
            ).map { Unit }
        }
    }

    /** 账单头（不存在 404）——加项前确认。 */
    private fun requireBill(client: SqlClient, billId: String): Future<Row> =
        execute(client, selectBill(billId)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { Future.succeededFuture(it) }
                ?: Future.failedFuture(HealthcareNotFoundException("bill not found: $billId"))
        }

    /** 字典项必须存在（404）且启用（400）。 */
    private fun requireEnabledItem(client: SqlClient, itemId: String): Future<Row> =
        execute(
            client,
            ctx.select(FEE_ITEMS.ID, FEE_ITEMS.NAME, FEE_ITEMS.UNIT_PRICE, FEE_ITEMS.STATUS)
                .from(FEE_ITEMS)
                .where(FEE_ITEMS.ID.eq(itemId)),
        ).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { row ->
                if (row.getString("status") != FeeItemService.STATUS_ENABLED) {
                    Future.failedFuture(IllegalArgumentException("fee item is disabled: $itemId"))
                } else {
                    Future.succeededFuture(row)
                }
            } ?: Future.failedFuture(HealthcareNotFoundException("fee item not found: $itemId"))
        }

    private fun billDetail(client: SqlClient, billId: String): Future<JsonObject> =
        execute(client, selectBill(billId)).compose { rows ->
            rows.iterator().asSequence().firstOrNull()?.let { billRow ->
                execute(client, selectItems(billId)).map { itemRows ->
                    billJson(billRow, itemRows.map(::itemJson))
                }
            } ?: Future.failedFuture(HealthcareNotFoundException("bill not found: $billId"))
        }

    private fun selectBill(id: String): Query =
        ctx.select(
            BILLS.ID,
            BILLS.ENCOUNTER_ID,
            BILLS.PERIOD_START,
            BILLS.PERIOD_END,
            BILLS.STATUS,
            BILLS.SETTLED_AT,
            BILLS.OUTSTANDING_AMOUNT,
            BILLS.WRITE_OFF_REASON,
            BILLS.TOTAL_AMOUNT,
            BILLS.CREATED_AT,
            BILLS.UPDATED_AT,
        ).from(BILLS)
            .where(BILLS.ID.eq(id))

    private fun selectItems(billId: String): Query =
        ctx.select(
            BILL_ITEMS.ID,
            BILL_ITEMS.BILL_ID,
            BILL_ITEMS.SOURCE,
            BILL_ITEMS.ITEM_CODE,
            BILL_ITEMS.ITEM_NAME,
            BILL_ITEMS.UNIT_PRICE,
            BILL_ITEMS.QUANTITY,
            BILL_ITEMS.AMOUNT,
            BILL_ITEMS.REMARK,
            BILL_ITEMS.CREATED_AT,
            BILL_ITEMS.UPDATED_AT,
        ).from(BILL_ITEMS)
            .where(BILL_ITEMS.BILL_ID.eq(billId))
            .orderBy(BILL_ITEMS.CREATED_AT.asc(), BILL_ITEMS.ID.asc())

    private fun execute(client: SqlClient, query: Query): Future<RowSet<Row>> =
        client.preparedQuery(DatabaseConfig.sql(query)).execute(DatabaseConfig.tuple(query))
}
