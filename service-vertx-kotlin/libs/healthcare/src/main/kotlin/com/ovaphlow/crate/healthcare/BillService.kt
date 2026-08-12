package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.common.Ulid
import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.healthcare.tables.BillItems.BILL_ITEMS
import com.ovaphlow.crate.database.gen.healthcare.tables.Bills.BILLS
import com.ovaphlow.crate.database.gen.healthcare.tables.Encounters.ENCOUNTERS
import com.ovaphlow.crate.database.gen.healthcare.tables.FeeItems.FEE_ITEMS
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
 *  6. 结算收束：离院/去世同事务生成区间最终账单并冻结；冻结（encounters.settled_at
 *     非空）后生成/加项一律 409；不做撤销/重算/红冲。
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

        /** NUMERIC(12,2) 上限：10 位整数 + 2 位小数 */
        val maxAmount = BigDecimal("9999999999.99")

        private val monthPattern = Regex("""^(\d{4})-(0[1-9]|1[0-2])$""")

        private fun billJson(row: Row, items: List<JsonObject>): JsonObject =
            JsonObject()
                .put("id", row.getString("id"))
                .put("encounter_id", row.getString("encounter_id"))
                .put("period_start", row.getLocalDate("period_start")?.toString())
                .put("period_end", row.getLocalDate("period_end")?.toString())
                .put("status", row.getString("status"))
                .put("settled_at", row.getOffsetDateTime("settled_at")?.toString())
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
    //  结算收束（离院/去世）
    // ========================================================================

    /** 已收束终态（补结算端点的资格判定）。 */
    private val terminalStatuses = setOf("DISCHARGED", "DECEASED")

    /**
     * 离院/去世结算收束（必须在调用方外层事务内执行，同连接）：
     *  1. 行锁读 encounter（防并发）后判定：非养老入住 400；
     *     [requireTerminalStatus] 时非 已离院/已去世 409；已冻结（settled_at 非空）409；
     *  2. 区间最终账单：账期 = [MAX(已结算账期末日)+1 或入住日, 收束日]（闭区间）；
     *     区间起 > 区间止不生成；与既有账单账期完全一致时不重复生成（唯一约束防冲突）；
     *     明细按自动计费（床位/护理/伙食），无可用计费项时生成 0 元封口账单；
     *     创建即状态 已结算 并写 settled_at；
     *  3. 冻结：该 encounter 全部 bills 置 已结算 并写 settled_at；
     *     encounters.settled_at 置结算时间（冻结标记，供生成/加项/缴费做 O(1) 判定）。
     * 不做撤销/重算/红冲：既有未结账单不重算、不裁剪，直接置 已结算；
     * 其账期与最终区间重叠属既定口径。
     */
    fun settleEncounter(
        client: SqlClient,
        encounterId: String,
        now: OffsetDateTime,
        requireTerminalStatus: Boolean,
        endDate: LocalDate? = null,
    ): Future<JsonObject> =
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
            settleAndFreeze(client, encounterId, admitDate, end, now)
        }

    private fun settleAndFreeze(
        client: SqlClient,
        encounterId: String,
        admitDate: LocalDate,
        endDate: LocalDate,
        now: OffsetDateTime,
    ): Future<JsonObject> =
        maxSettledPeriodEnd(client, encounterId).compose { maxEnd ->
            val interval = BillingEngine.settlementInterval(admitDate, endDate, listOfNotNull(maxEnd))
            if (interval == null) {
                freezeAndReturn(client, encounterId, now)
            } else {
                val (start, end) = interval
                exactBillExists(client, encounterId, start, end).compose { exists ->
                    if (exists) {
                        // 最终区间与既有账单完全一致：唯一约束防冲突，不重复生成，直接冻结
                        freezeAndReturn(client, encounterId, now)
                    } else {
                        generateFinalBill(client, encounterId, start, end, now)
                            .compose { freezeAndReturn(client, encounterId, now) }
                    }
                }
            }
        }

    /** 区间最终账单：自动计费明细；无可用计费项时 0 元封口；创建即 已结算 + settled_at。 */
    private fun generateFinalBill(
        client: SqlClient,
        encounterId: String,
        start: LocalDate,
        end: LocalDate,
        now: OffsetDateTime,
    ): Future<Unit> {
        val billId = Ulid.generate()
        return computeAutoItems(client, encounterId, start, end)
            .recover { error ->
                if (error is IllegalArgumentException) {
                    // 无可计费项（如字典无启用单价）：0 元封口账单，账期正确即封口
                    Future.succeededFuture(emptyList())
                } else {
                    Future.failedFuture(error)
                }
            }
            .compose { items ->
                val total = BillingEngine.totalOf(items.map { it.amount })
                execute(
                    client,
                    ctx.insertInto(BILLS)
                        .set(BILLS.ID, billId)
                        .set(BILLS.ENCOUNTER_ID, encounterId)
                        .set(BILLS.PERIOD_START, start)
                        .set(BILLS.PERIOD_END, end)
                        .set(BILLS.STATUS, BillingEngine.STATUS_SETTLED)
                        .set(BILLS.TOTAL_AMOUNT, total)
                        .set(BILLS.SETTLED_AT, now)
                        .set(BILLS.CREATED_AT, now)
                        .set(BILLS.UPDATED_AT, now),
                ).compose { insertItems(client, billId, items, now) }
            }
    }

    /** 冻结：全部 bills 置 已结算 并写 settled_at；encounters.settled_at 置结算时间。 */
    private fun freezeAndReturn(
        client: SqlClient,
        encounterId: String,
        now: OffsetDateTime,
    ): Future<JsonObject> =
        execute(
            client,
            ctx.update(BILLS)
                .set(BILLS.STATUS, BillingEngine.STATUS_SETTLED)
                .set(BILLS.SETTLED_AT, now)
                .set(BILLS.UPDATED_AT, now)
                .where(BILLS.ENCOUNTER_ID.eq(encounterId)),
        ).compose {
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
        val month = raw as? String ?: throw IllegalArgumentException("month must be a string")
        if (!monthPattern.matches(month)) {
            throw IllegalArgumentException("month must be in YYYY-MM format")
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

    /** 计算自动明细：床位（在院天数）/护理（等级分段天数）/伙食（折合餐次）。 */
    private fun computeAutoItems(
        connection: SqlClient,
        encounterId: String,
        stayStart: LocalDate,
        stayEnd: LocalDate,
    ): Future<List<AutoItem>> {
        val enabledItemsQuery = ctx.select(
            FEE_ITEMS.ID,
            FEE_ITEMS.CATEGORY,
            FEE_ITEMS.NAME,
            FEE_ITEMS.UNIT_PRICE,
        ).from(FEE_ITEMS)
            .where(FEE_ITEMS.STATUS.eq(FeeItemService.STATUS_ENABLED))
        return execute(connection, enabledItemsQuery).compose { rows ->
            val items = rows.map { row ->
                FeeItemRow(
                    id = row.getString("id"),
                    category = row.getString("category"),
                    name = row.getString("name"),
                    unitPrice = row.getBigDecimal("unit_price"),
                )
            }
            try {
                val autoItems = mutableListOf<AutoItem>()

                // 床位费：分类取唯一启用项 × 闭区间在院天数
                val bedItem = requireSingleEnabled(items.filter { it.category == "床位费" }, "床位费")
                val bedDays = BillingEngine.inclusiveDays(stayStart, stayEnd)
                autoItems += AutoItem(
                    itemCode = bedItem.id,
                    itemName = bedItem.name,
                    unitPrice = bedItem.unitPrice,
                    quantity = BigDecimal.valueOf(bedDays),
                    amount = BillingEngine.money(bedItem.unitPrice, BigDecimal.valueOf(bedDays)),
                )

                // 护理费：等级分段，每区间 = 等级字典单价 × 天数
                val segmentsFuture = loadAssessments(connection, encounterId, stayEnd).map { assessments ->
                    BillingEngine.nursingSegments(
                        stayStart,
                        stayEnd,
                        assessments.map { (date, createdAt, level) ->
                            BillingEngine.Assessment(date, createdAt, level)
                        },
                    )
                }
                // 伙食费：账期内就餐执行折合餐次
                val mealFuture = loadMealStatuses(connection, encounterId, stayStart, stayEnd)
                    .map { BillingEngine.mealQuantity(it) }

                segmentsFuture.compose { segments ->
                    mealFuture.compose { mealQuantity ->
                        val nursingRows = segments.map { segment ->
                            val levelItem = requireSingleEnabled(
                                items.filter { it.category == "护理费" && it.name == segment.level },
                                "护理费",
                                level = segment.level,
                            )
                            AutoItem(
                                itemCode = levelItem.id,
                                itemName = levelItem.name,
                                unitPrice = levelItem.unitPrice,
                                quantity = BigDecimal.valueOf(segment.days),
                                amount = BillingEngine.money(levelItem.unitPrice, BigDecimal.valueOf(segment.days)),
                            )
                        }
                        autoItems += nursingRows
                        if (mealQuantity.signum() > 0) {
                            val mealItem = requireSingleEnabled(items.filter { it.category == "伙食费" }, "伙食费")
                            autoItems += AutoItem(
                                itemCode = mealItem.id,
                                itemName = mealItem.name,
                                unitPrice = mealItem.unitPrice,
                                quantity = mealQuantity,
                                amount = BillingEngine.money(mealItem.unitPrice, mealQuantity),
                            )
                        }
                        Future.succeededFuture(autoItems)
                    }
                }
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
