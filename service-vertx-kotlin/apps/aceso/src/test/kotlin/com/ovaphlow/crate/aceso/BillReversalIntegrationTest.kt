package com.ovaphlow.crate.aceso

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.math.BigDecimal
import java.sql.DriverManager

/**
 * 034 账单红冲（A2 反向单）真库端到端回归 —— 只走真实 HTTP 接口与隔离的 `aceso_test`。
 *
 * 装配与生产 `apps/aceso/Main.kt` 等价：`AcesoIntegrationTestSupport.createRouter` 挂
 * Healthcare/Inventories/Nursing/Pharmacy 子路由，全局身份门注入 `userId`
 * （`integration-tester`），因此 `reversed_by` 必须等于该认证主体。
 *
 * 覆盖 034 计划第 6 节的**真库验收条目**（逐条对应下方测试方法）：
 *  1./2. 生成账单 → 红冲 201 → 红字单金额取反/账期复制/状态 待缴费/`reversal_of` 指向原单；
 *         原单 `total_amount`/`status`/明细逐字不动，`reversed_at/reversed_by/reversal_reason` 三列同生同灭；
 *  3.    欠费列表排除已红冲原单，汇总 `应缴 − 已缴 = 欠费` 在红冲后仍成立且净额回到红冲前水平；
 *  4.    红冲后同账期可重新生成（部分唯一索引 + `effectiveBillExists` 同口径），
 *         precheck 与 generate 判定一致（红冲后放行、有效原单存在时都阻断）；
 *  5.    重复红冲 409 / 红字单再红冲 400 / 0 元封口账单红冲 400，失败的调用不改写任何一行；
 *  6.    红字单与已红冲原单不可缴费（不产生 payments 流水）、不可加项；
 *  6b.   红冲后结算收束预览未结余额为 0、不要求 `write_off_reason`（无幻影欠费），
 *         且不带原因的真实收束也能成功；
 *  7.    有缴费记录的账单红冲 409（先退款/冲正）。
 *
 * 断言纪律：每条验收都有独立断言与失败消息，金额一律 `BigDecimal.compareTo` 精确到分；
 * 不使用「null 也接受」「≥0 条」这类恒真断言。任何一条变红都是真实缺陷证据，不得放宽。
 */
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
class BillReversalIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "brv-"
    override val serverPort = 18520

    private fun id(s: String) = "${fixturePrefix}$s"

    private companion object {
        /** 账期（自然月）：与 V521 演示费用字典一起决定自动计费金额。 */
        const val BILL_MONTH = "2026-08"
        const val PERIOD_START = "2026-08-01"
        const val PERIOD_END = "2026-08-31"

        /**
         * V521 演示字典下的自动计费合计（元）：
         * 床位费 60.00 × 31 天（闭区间 08-01~08-31）+ 护理费·低风险 30.00 × 31 天 = 2790.00。
         * 该值是断言而不是估算：演示价被改动时本条立刻变红（提示口径变化，而不是静默放宽）。
         */
        const val EXPECTED_TOTAL = "2790.00"

        /** 红冲原因（请求体唯一允许的键 `reason`；服务端 trim 后原样写在原单上）。 */
        const val REVERSAL_REASON = "重复计费，按实际在院区间重算"

        /** 认证主体（`AcesoIntegrationTestSupport.fakeAuth` 写入的 `userId`）。 */
        const val OPERATOR = "integration-tester"

        /** 红字单状态：必须为 待缴费（全仓金额聚合口径，见 034 §2.2）。 */
        const val STATUS_PENDING = "待缴费"
    }

    // ========================================================================
    //  fixture：三个养老入住 + 护理评估（护理费必须有匹配等级的启用字典项才参与计费）
    // ========================================================================

    /** 主用例 encounter（ACTIVE，在院 2026-08-01 起）：红冲、重算、守卫、缴费、欠费用例共用。 */
    private val encId = id("enc")

    /** 已离院 encounter（DISCHARGED + 离院日 = 账期末日）：结算收束预览用例。 */
    private val dischargedEncounterId = id("enc-discharged")

    /** 0 元封口账单所在 encounter（独立账期，避免撞部分唯一索引）。 */
    private val zeroEncounterId = id("enc-zero")

    /** 0 元封口账单夹具行 id（固定值，便于按 id 断言残留）。 */
    private val zeroBillId = id("bill-zero")

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient")}', '红冲测试长者', '男', '1940-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient-discharged")}', '红冲测试长者（已离院）', '女', '1941-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient-zero")}', '红冲测试长者（零元单）', '男', '1942-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc")}', '${id("patient")}', 'ELDERLY_CARE', 'BRV-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // 收束预览要求终态（已离院/已去世）+ 离院日：离院日取账期末日，使收束区间与账期一致
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, discharge_date, status)
            VALUES ('${id("enc-discharged")}', '${id("patient-discharged")}', 'ELDERLY_CARE', 'BRV-ENC-2', '2026-08-01T00:00:00+08:00', '2026-08-31T00:00:00+08:00', 'DISCHARGED')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc-zero")}', '${id("patient-zero")}', 'ELDERLY_CARE', 'BRV-ENC-3', '2026-07-01T00:00:00+08:00', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // 护理评估：result_level 必须等于 V521 演示字典的 metadata.nursing_level（低风险）才匹配护理费
        executeSql(
            """
            INSERT INTO nursing.nursing_assessments (id, encounter_id, assess_type, assess_date, assessor, total_score, result_level)
            VALUES ('${id("assess")}', '${id("enc")}', 'ADMISSION', '2026-08-01', '王护士', 12.5, '低风险')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO nursing.nursing_assessments (id, encounter_id, assess_type, assess_date, assessor, total_score, result_level)
            VALUES ('${id("assess-discharged")}', '${id("enc-discharged")}', 'ADMISSION', '2026-08-01', '王护士', 12.5, '低风险')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // 0 元封口账单：结算路径在「无可用计费项」时落下的合法原单形态
        // （BillService.computeBillItems 的 400 → 空明细 + 合计 0）。
        // 该形态用演示字典（V521 每分类恰好一个启用项）无法经真实接口复现，故作为夹具行写入；
        // 被测的红冲接口本身仍走真实 HTTP 与真实事务。
        executeSql(
            """
            INSERT INTO healthcare.bills (id, encounter_id, period_start, period_end, status, total_amount)
            VALUES ('${id("bill-zero")}', '${id("enc-zero")}', '2026-07-01', '2026-07-31', '$STATUS_PENDING', 0.00)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() {
        // 红字单通过 reversal_of 引用原单：先按 encounter 清子表与账单，再交给 cleanupAll 收尾。
        executeSql("DELETE FROM healthcare.payments WHERE bill_id IN (SELECT id FROM healthcare.bills WHERE encounter_id LIKE '$fixturePrefix%')")
        executeSql("DELETE FROM healthcare.bill_items WHERE bill_id IN (SELECT id FROM healthcare.bills WHERE encounter_id LIKE '$fixturePrefix%')")
        executeSql("DELETE FROM healthcare.bills WHERE id LIKE '$fixturePrefix%' OR encounter_id LIKE '$fixturePrefix%'")
        cleanupAll(fixturePrefix)
    }

    override fun assertNoResidual() {
        check(
            countRows("SELECT count(*) FROM healthcare.bills WHERE encounter_id LIKE '$fixturePrefix%' OR id LIKE '$fixturePrefix%'") == 0L,
        ) { "账单残留（含红字单）" }
        check(countRows("SELECT count(*) FROM healthcare.bills WHERE reversal_of LIKE '$fixturePrefix%'") == 0L) {
            "仍存在指向本用例原单的红字单"
        }
        check(
            countRows(
                "SELECT count(*) FROM healthcare.bill_items WHERE bill_id IN " +
                    "(SELECT id FROM healthcare.bills WHERE encounter_id LIKE '$fixturePrefix%' OR id LIKE '$fixturePrefix%')",
            ) == 0L,
        ) { "账单明细残留" }
        check(
            countRows(
                "SELECT count(*) FROM healthcare.payments WHERE bill_id IN " +
                    "(SELECT id FROM healthcare.bills WHERE encounter_id LIKE '$fixturePrefix%' OR id LIKE '$fixturePrefix%')",
            ) == 0L,
        ) { "缴费流水残留" }
        check(countRows("SELECT count(*) FROM nursing.nursing_assessments WHERE encounter_id LIKE '$fixturePrefix%'") == 0L) {
            "护理评估残留"
        }
        check(countRows("SELECT count(*) FROM healthcare.encounters WHERE id LIKE '$fixturePrefix%'") == 0L) { "入住残留" }
        check(countRows("SELECT count(*) FROM healthcare.patients WHERE id LIKE '$fixturePrefix%'") == 0L) { "长者档案残留" }
    }

    // ========================================================================
    //  共用小工具（数值一律 BigDecimal，禁止经 Double；落库断言用标量查询）
    // ========================================================================

    /** 从响应体取数值字段（Vert.x 可能给出 BigDecimal / Double / String，三者都精确还原）。 */
    private fun decimal(body: JsonObject, key: String): BigDecimal {
        val raw = body.getValue(key) ?: throw AssertionError("响应缺少字段 $key：${body.encode()}")
        return when (raw) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> BigDecimal(raw)
            else -> throw AssertionError("字段 $key 不是数值：${body.encode()}")
        }
    }

    /** 金额断言：`BigDecimal.compareTo` 比较（忽略 scale，但精确到分，不容许任何误差）。 */
    private fun assertAmount(expected: String, body: JsonObject, key: String, message: String) {
        assertEquals(0, BigDecimal(expected).compareTo(decimal(body, key)), "$message（实际 $key=${body.getValue(key)}）")
    }

    /** 读单个标量（夹具/落库断言用；与 TextOrderRebindAdministrationIntegrationTest 同写法）。 */
    private fun stringValue(sql: String): String? =
        DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** 落库合计（`NUMERIC(12,2)::text` 原样读出，供精确比较）。 */
    private fun dbAmount(billId: String): BigDecimal =
        BigDecimal(
            requireNotNull(stringValue("SELECT total_amount::text FROM healthcare.bills WHERE id = '$billId'")) {
                "缺少账单 $billId 的落库合计"
            },
        )

    private fun billCount(encounterId: String): Long =
        countRows("SELECT count(*) FROM healthcare.bills WHERE encounter_id = '$encounterId'")

    /** 该账期的「有效原单」数（与 V523 部分唯一索引 / effectiveBillExists 同谓词）。 */
    private fun effectiveOriginalCount(encounterId: String): Long =
        countRows(
            "SELECT count(*) FROM healthcare.bills WHERE encounter_id = '$encounterId' " +
                "AND period_start = '$PERIOD_START' AND period_end = '$PERIOD_END' " +
                "AND reversal_of IS NULL AND reversed_at IS NULL",
        )

    /** 原单留痕 + 金额 + 状态的落库快照（用于「失败的调用不得改写任何一行」的前后对比）。 */
    private fun auditSnapshot(billId: String): String? =
        stringValue(
            "SELECT coalesce(reversed_at::text, '-') || '|' || coalesce(reversed_by, '-') || '|' || " +
                "coalesce(reversal_reason, '-') || '|' || total_amount::text || '|' || status " +
                "FROM healthcare.bills WHERE id = '$billId'",
        )

    private fun paymentsOf(billId: String): Long =
        countRows("SELECT count(*) FROM healthcare.payments WHERE bill_id = '$billId'")

    private fun itemsOf(billId: String): Long =
        countRows("SELECT count(*) FROM healthcare.bill_items WHERE bill_id = '$billId'")

    private fun generateBill(vertx: Vertx, encounterId: String): Future<Pair<Int, JsonObject>> =
        request(
            vertx,
            HttpMethod.POST,
            "/healthcare/v1/encounters/$encounterId/bills",
            JsonObject().put("month", BILL_MONTH),
        )

    private fun reverseBill(vertx: Vertx, billId: String, reason: String = REVERSAL_REASON): Future<Pair<Int, JsonObject>> =
        request(
            vertx,
            HttpMethod.POST,
            "/healthcare/v1/bills/$billId/reversal",
            JsonObject().put("reason", reason),
        )

    private fun detail(vertx: Vertx, billId: String): Future<Pair<Int, JsonObject>> =
        request(vertx, HttpMethod.GET, "/healthcare/v1/bills/$billId")

    private fun precheck(vertx: Vertx, encounterId: String): Future<Pair<Int, JsonObject>> =
        request(vertx, HttpMethod.GET, "/healthcare/v1/encounters/$encounterId/bills/precheck?month=$BILL_MONTH")

    private fun summary(vertx: Vertx): Future<Pair<Int, JsonObject>> =
        request(vertx, HttpMethod.GET, "/healthcare/v1/payments/summary")

    private fun arrears(vertx: Vertx, encounterId: String): Future<Pair<Int, JsonObject>> =
        request(vertx, HttpMethod.GET, "/healthcare/v1/payments/arrears?encounter_id=$encounterId")

    private fun pay(vertx: Vertx, billId: String, amount: String): Future<Pair<Int, JsonObject>> =
        request(
            vertx,
            HttpMethod.POST,
            "/healthcare/v1/bills/$billId/payments",
            JsonObject().put("amount", BigDecimal(amount)).put("method", "现金"),
        )

    private fun addItem(vertx: Vertx, billId: String, itemId: String): Future<Pair<Int, JsonObject>> =
        request(
            vertx,
            HttpMethod.POST,
            "/healthcare/v1/bills/$billId/items",
            JsonObject().put("item_id", itemId).put("quantity", BigDecimal.ONE),
        )

    private fun errorOf(body: JsonObject): String = body.getString("error") ?: ""

    /**
     * 前置链路：生成 2026-08 账单 → 红冲，返回 `(原单 id, 红字单 id)`。
     * 途中把两跳的状态码与金额作为**测试前置**校验（用 `check`，不是验收断言）：
     * 前置不成立时后续验收断言没有意义。
     */
    private fun generateThenReverse(vertx: Vertx, encounterId: String): Future<Pair<String, String>> =
        generateBill(vertx, encounterId).compose { (status, bill) ->
            val originalId = requireNotNull(bill.getString("id")) { "生成响应缺少 id：${bill.encode()}" }
            check(status == 201) { "前置：生成账单必须 201，实际 $status：${bill.encode()}" }
            check(BigDecimal(EXPECTED_TOTAL).compareTo(decimal(bill, "total_amount")) == 0) {
                "前置：自动计费合计应为 $EXPECTED_TOTAL，实际 ${bill.getValue("total_amount")}"
            }
            reverseBill(vertx, originalId).map { (redStatus, red) ->
                val redId = requireNotNull(red.getString("id")) { "红冲响应缺少 id：${red.encode()}" }
                check(redStatus == 201) { "前置：红冲必须 201，实际 $redStatus：${red.encode()}" }
                check(BigDecimal("-$EXPECTED_TOTAL").compareTo(decimal(red, "total_amount")) == 0) {
                    "前置：红字单合计应为 -$EXPECTED_TOTAL，实际 ${red.getValue("total_amount")}"
                }
                originalId to redId
            }
        }

    // ========================================================================
    //  验收 1 + 2：红字单形态 + 原单不动 + 三列留痕 + 双向关联（详情与列表同源）
    // ========================================================================

    @Test
    fun `红冲生成负金额红字单且原单金额状态明细不变并三列留痕`(vertx: Vertx, ctx: VertxTestContext) {
        var originalId = ""
        var redId = ""
        var itemsBefore = ""

        precheck(vertx, encId)
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "生成前置校验应 200：${body.encode()}")
                    assertEquals(true, body.getBoolean("can_generate"), "无账单时应可生成：${body.encode()}")
                    assertTrue(
                        body.containsKey("blocked_by") && body.getValue("blocked_by") == null,
                        "无账单时 blocked_by 必须显式为 null：${body.encode()}",
                    )
                    assertEquals(PERIOD_START, body.getString("period_start"), "账期起应为 $PERIOD_START")
                    assertEquals(PERIOD_END, body.getString("period_end"), "账期止应为 $PERIOD_END")
                    assertEquals(0L, billCount(encId), "前置：该 encounter 尚不应有账单")
                }
                generateBill(vertx, encId)
            }
            .compose { (status, bill) ->
                originalId = requireNotNull(bill.getString("id")) { "生成响应缺少 id：${bill.encode()}" }
                itemsBefore = bill.getJsonArray("items")?.encode() ?: ""
                ctx.verify {
                    assertEquals(201, status, "生成账单应 201：${bill.encode()}")
                    assertAmount(EXPECTED_TOTAL, bill, "total_amount", "生成账单合计应为 V521 演示价下的 $EXPECTED_TOTAL")
                    assertTrue(decimal(bill, "total_amount").signum() > 0, "原单合计必须严格大于 0（红冲前提）")
                    assertEquals(PERIOD_START, bill.getString("period_start"), "账期起")
                    assertEquals(PERIOD_END, bill.getString("period_end"), "账期止")
                    assertEquals(STATUS_PENDING, bill.getString("status"), "新单状态应为 $STATUS_PENDING")
                    assertEquals(2, bill.getJsonArray("items").size(), "自动明细应为 床位费 + 护理费 两条：${bill.encode()}")
                    assertNull(bill.getValue("reversal_of"), "非红字单 reversal_of 必须为 null：${bill.encode()}")
                    assertNull(bill.getValue("reversed_at"), "未被红冲的原单 reversed_at 必须为 null")
                    assertNull(bill.getValue("reversal_bill_id"), "未被红冲的原单没有红字单关联")
                }
                reverseBill(vertx, originalId)
            }
            .compose { (status, red) ->
                redId = requireNotNull(red.getString("id")) { "红冲响应缺少 id：${red.encode()}" }
                ctx.verify {
                    assertEquals(201, status, "红冲应 201：${red.encode()}")
                    assertTrue(redId != originalId, "红字单必须是一张新账单，不能复用原单 id")
                    assertAmount("-$EXPECTED_TOTAL", red, "total_amount", "红字单合计必须是原单取反（精确到分）")
                    assertTrue(decimal(red, "total_amount").signum() < 0, "红字单合计必须严格小于 0：${red.encode()}")
                    assertEquals(originalId, red.getString("reversal_of"), "红字单必须指向被冲的原单")
                    assertEquals(PERIOD_START, red.getString("period_start"), "红字单账期必须复制原单（起）")
                    assertEquals(PERIOD_END, red.getString("period_end"), "红字单账期必须复制原单（止）")
                    assertEquals(STATUS_PENDING, red.getString("status"), "红字单状态应为 $STATUS_PENDING（全仓金额聚合口径）")
                    assertNull(red.getValue("reversal_reason"), "红字单自身不得被标记为已红冲（reason 为空）")
                    assertNull(red.getValue("reversed_by"), "红字单自身不得被标记为已红冲（reversed_by 为空）")
                    assertNull(red.getValue("reversed_at"), "红字单自身不得被标记为已红冲（reversed_at 为空）")
                    assertNull(red.getValue("reversal_bill_id"), "红字单不是原单，reversal_bill_id 必须为 null")
                    assertNotNull(red.getString("created_at"), "红字单必须有 created_at")
                    assertEquals(0, red.getJsonArray("items").size(), "红字单不复制明细行（金额整单取反）")
                    // 同一事务落库：原单三列 + 红字单一行
                    assertEquals(2L, billCount(encId), "红冲后该 encounter 应恰有 2 张账单（原单 + 红字单）")
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE id = '$originalId' " +
                                "AND reversed_at IS NOT NULL AND reversed_by = '$OPERATOR' " +
                                "AND reversal_reason = '$REVERSAL_REASON'",
                        ),
                        "原单三列必须同生（时刻/操作人/原因），且原因等于请求体 trim 后的 reason",
                    )
                    assertEquals(
                        0L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE id = '$originalId' " +
                                "AND (reversed_at IS NULL OR reversed_by IS NULL OR reversal_reason IS NULL)",
                        ),
                        "原单三列必须同灭（任一为空即违反 V523 不变量）",
                    )
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE reversal_of = '$originalId' AND id = '$redId' " +
                                "AND total_amount = -$EXPECTED_TOTAL::numeric AND period_start = '$PERIOD_START' " +
                                "AND period_end = '$PERIOD_END'",
                        ),
                        "红字单落库形态必须与响应一致（关联/金额/账期）",
                    )
                }
                detail(vertx, originalId)
            }
            .compose { (status, original) ->
                ctx.verify {
                    assertEquals(200, status, "原单详情应 200：${original.encode()}")
                    assertAmount(EXPECTED_TOTAL, original, "total_amount", "红冲不得改写原单合计")
                    assertEquals(STATUS_PENDING, original.getString("status"), "红冲不得改写原单状态")
                    assertEquals(itemsBefore, original.getJsonArray("items")?.encode(), "红冲不得改写原单明细（逐字段原样）")
                    assertEquals(OPERATOR, original.getString("reversed_by"), "reversed_by 必须是认证主体")
                    assertNotNull(original.getString("reversed_at"), "reversed_at 必须已落库")
                    assertEquals(REVERSAL_REASON, original.getString("reversal_reason"), "reversal_reason 必须是请求体 reason")
                    assertEquals(redId, original.getString("reversal_bill_id"), "原单必须能反查冲销它的红字单")
                    assertNull(original.getValue("reversal_of"), "原单不是红字单，reversal_of 必须为 null")
                    assertEquals(2L, itemsOf(originalId), "原单明细行数不得变化（床位费 + 护理费）")
                    assertEquals(2, original.getJsonArray("items").size(), "原单明细仍应为两条：${original.encode()}")
                }
                // 列表接口必须与详情同源返回红冲字段（034 §4）
                request(vertx, HttpMethod.GET, "/healthcare/v1/encounters/$encId/bills")
            }
            .compose { (status, list) ->
                ctx.verify {
                    assertEquals(200, status, "账单列表应 200：${list.encode()}")
                    assertEquals(2L, list.getJsonObject("meta").getLong("total"), "列表总数应为 2：${list.encode()}")
                    val records = list.getJsonArray("records")
                    assertEquals(2, records.size(), "列表记录数应为 2：${list.encode()}")
                    val rows = (0 until records.size()).map { records.getJsonObject(it) }
                    val red = rows.single { it.getString("id") == redId }
                    val original = rows.single { it.getString("id") == originalId }
                    assertEquals(originalId, red.getString("reversal_of"), "列表里的红字单必须带 reversal_of")
                    assertAmount("-$EXPECTED_TOTAL", red, "total_amount", "列表里的红字单合计")
                    assertEquals(redId, original.getString("reversal_bill_id"), "列表里的原单必须带 reversal_bill_id")
                    assertEquals(OPERATOR, original.getString("reversed_by"), "列表里的原单必须带 reversed_by")
                    assertEquals(REVERSAL_REASON, original.getString("reversal_reason"), "列表里的原单必须带 reversal_reason")
                    assertEquals(STATUS_PENDING, original.getString("status"), "列表里的原单状态不得变化")
                    assertAmount(EXPECTED_TOTAL, original, "total_amount", "列表里的原单合计不得变化")
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  验收 3：欠费列表排除已红冲原单 + 汇总恒等式
    // ========================================================================

    @Test
    fun `红冲后欠费列表排除已红冲原单且汇总恒等式与净额回到红冲前水平`(vertx: Vertx, ctx: VertxTestContext) {
        var dueBefore = BigDecimal.ZERO
        var paidBefore = BigDecimal.ZERO
        var arrearsBefore = BigDecimal.ZERO
        var originalId = ""

        summary(vertx)
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "汇总应 200：${body.encode()}")
                    dueBefore = decimal(body, "due_amount")
                    paidBefore = decimal(body, "paid_amount")
                    arrearsBefore = decimal(body, "arrears_amount")
                    // 基线必须满足全仓恒等式 应缴 − 已缴 = 欠费（隔离库 aceso_test 中只有本类账单）
                    assertEquals(
                        0,
                        dueBefore.subtract(paidBefore).compareTo(arrearsBefore),
                        "基线破坏恒等式：应缴 $dueBefore − 已缴 $paidBefore ≠ 欠费 $arrearsBefore",
                    )
                }
                generateBill(vertx, encId)
            }
            .compose { (status, bill) ->
                originalId = requireNotNull(bill.getString("id")) { "生成响应缺少 id：${bill.encode()}" }
                ctx.verify {
                    assertEquals(201, status, "生成账单应 201：${bill.encode()}")
                    assertAmount(EXPECTED_TOTAL, bill, "total_amount", "生成账单合计")
                }
                summary(vertx)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "汇总应 200：${body.encode()}")
                    val due = decimal(body, "due_amount")
                    val paid = decimal(body, "paid_amount")
                    val arrears = decimal(body, "arrears_amount")
                    assertAmount(
                        dueBefore.add(BigDecimal(EXPECTED_TOTAL)).toPlainString(),
                        body,
                        "due_amount",
                        "生成原单后应缴应恰好增加 A",
                    )
                    assertEquals(0, paidBefore.compareTo(paid), "尚未缴费，已缴不得变化（实际 $paid）")
                    assertAmount(
                        arrearsBefore.add(BigDecimal(EXPECTED_TOTAL)).toPlainString(),
                        body,
                        "arrears_amount",
                        "生成原单后欠费应恰好增加 A",
                    )
                    assertEquals(0, due.subtract(paid).compareTo(arrears), "生成后恒等式 应缴 − 已缴 = 欠费 必须成立")
                }
                arrears(vertx, encId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "欠费列表应 200：${body.encode()}")
                    assertEquals(1L, body.getJsonObject("meta").getLong("total"), "红冲前该 encounter 应恰有 1 条欠费：${body.encode()}")
                    val record = body.getJsonArray("records").getJsonObject(0)
                    assertEquals(originalId, record.getString("id"), "欠费记录必须是原单")
                    assertEquals(STATUS_PENDING, record.getString("status"), "欠费记录状态应为 $STATUS_PENDING")
                    assertAmount(EXPECTED_TOTAL, record, "balance", "未缴费时余额应等于合计 A")
                    assertAmount("0.00", record, "paid_amount", "未缴费时已缴应为 0")
                }
                reverseBill(vertx, originalId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "红冲应 201：${body.encode()}")
                    assertAmount("-$EXPECTED_TOTAL", body, "total_amount", "红字单合计")
                }
                summary(vertx)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "汇总应 200：${body.encode()}")
                    val due = decimal(body, "due_amount")
                    val paid = decimal(body, "paid_amount")
                    val arrears = decimal(body, "arrears_amount")
                    assertEquals(0, dueBefore.compareTo(due), "红冲后应缴必须回到红冲前水平（基线 $dueBefore，实际 $due）")
                    assertEquals(0, paidBefore.compareTo(paid), "红冲不得产生缴费流水，已缴必须不变（实际 $paid）")
                    assertEquals(0, arrearsBefore.compareTo(arrears), "红冲后欠费必须回到红冲前水平（基线 $arrearsBefore，实际 $arrears）")
                    assertEquals(0, due.subtract(paid).compareTo(arrears), "红冲后恒等式 应缴 − 已缴 = 欠费 必须成立")
                    assertEquals(
                        0,
                        due.subtract(dueBefore).compareTo(BigDecimal.ZERO),
                        "本用例的红冲净额必须为 0（原单 A + 红字单 −A），实际 ${due.subtract(dueBefore)}",
                    )
                }
                arrears(vertx, encId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "欠费列表应 200：${body.encode()}")
                    assertEquals(0L, body.getJsonObject("meta").getLong("total"), "已红冲原单与红字单都不得出现在欠费列表：${body.encode()}")
                    assertEquals(0, body.getJsonArray("records").size(), "欠费记录应为空数组：${body.encode()}")
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE id = '$originalId' AND reversed_at IS NOT NULL",
                        ),
                        "原单必须已被标记红冲（欠费排除谓词的前提）",
                    )
                    assertEquals(0L, paymentsOf(originalId), "红冲不产生缴费流水")
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  验收 4：同账期重新生成（部分唯一索引 + effectiveBillExists 同口径）
    // ========================================================================

    @Test
    fun `红冲后同账期可重新生成且precheck与生成判定一致`(vertx: Vertx, ctx: VertxTestContext) {
        var redId = ""
        var newBillId = ""

        generateThenReverse(vertx, encId)
            .compose { (originalId, red) ->
                redId = red
                ctx.verify {
                    assertEquals(2L, billCount(encId), "红冲后应是 原单 + 红字单 两张")
                    assertEquals(0L, effectiveOriginalCount(encId), "红冲后该账期不得再有有效原单：${originalId}")
                }
                precheck(vertx, encId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "红冲后 precheck 应 200：${body.encode()}")
                    assertEquals(true, body.getBoolean("can_generate"), "红冲后 precheck 必须放行同账期重新生成：${body.encode()}")
                    assertTrue(
                        body.containsKey("blocked_by") && body.getValue("blocked_by") == null,
                        "红冲后 blocked_by 必须为 null（已红冲原单与红字单都不算已存在）：${body.encode()}",
                    )
                }
                generateBill(vertx, encId)
            }
            .compose { (status, bill) ->
                newBillId = requireNotNull(bill.getString("id")) { "重算响应缺少 id：${bill.encode()}" }
                ctx.verify {
                    assertEquals(201, status, "同账期重新生成应 201（部分唯一索引生效）：${bill.encode()}")
                    assertTrue(newBillId != redId, "重算单必须是新账单，不是红字单")
                    assertAmount(EXPECTED_TOTAL, bill, "total_amount", "重算单合计应与原单一致（演示价不变）")
                    assertEquals(PERIOD_START, bill.getString("period_start"), "重算单账期起")
                    assertEquals(PERIOD_END, bill.getString("period_end"), "重算单账期止")
                    assertEquals(STATUS_PENDING, bill.getString("status"), "重算单状态应为 $STATUS_PENDING")
                    assertNull(bill.getValue("reversal_of"), "重算单不是红字单")
                    assertNull(bill.getValue("reversed_at"), "重算单未被红冲")
                    // 该账期唯一「有效原单」= 重算单
                    assertEquals(1L, effectiveOriginalCount(encId), "该账期必须恰有 1 张有效原单")
                    assertEquals(
                        newBillId,
                        stringValue(
                            "SELECT id FROM healthcare.bills WHERE encounter_id = '$encId' " +
                                "AND period_start = '$PERIOD_START' AND period_end = '$PERIOD_END' " +
                                "AND reversal_of IS NULL AND reversed_at IS NULL",
                        ),
                        "有效原单必须就是重算出来的新单",
                    )
                    assertEquals(3L, billCount(encId), "该 encounter 应累计 3 张账单（原单 + 红字单 + 重算单）")
                }
                precheck(vertx, encId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "precheck 应 200：${body.encode()}")
                    assertEquals(false, body.getBoolean("can_generate"), "已有有效原单时 precheck 必须阻断：${body.encode()}")
                    assertEquals("already_exists", body.getString("blocked_by"), "阻断原因应为 already_exists：${body.encode()}")
                }
                // precheck 与 generate 同口径：此时 generate 必须同样阻断 409
                generateBill(vertx, encId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "precheck 阻断时生成必须 409（同口径）：${body.encode()}")
                    assertTrue(errorOf(body).contains("already exists"), "409 消息应说明账期已存在：${body.encode()}")
                    assertEquals(3L, billCount(encId), "失败的生成不得留下任何账单")
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  验收 5：红冲前提守卫（重复 409 / 红字单 400 / 0 元封口单 400）
    // ========================================================================

    @Test
    fun `重复红冲409红字单再红冲400零元封口账单红冲400`(vertx: Vertx, ctx: VertxTestContext) {
        var originalId = ""
        var redId = ""
        var auditAfterFirstReverse = ""

        generateThenReverse(vertx, encId)
            .compose { (original, red) ->
                originalId = original
                redId = red
                auditAfterFirstReverse = auditSnapshot(originalId) ?: ""
                ctx.verify {
                    assertTrue(auditAfterFirstReverse.isNotBlank(), "必须能读到原单留痕快照")
                    assertTrue(
                        auditAfterFirstReverse.contains("|$OPERATOR|$REVERSAL_REASON|"),
                        "原单三列应已落库（操作人/原因）：$auditAfterFirstReverse",
                    )
                    assertTrue(!auditAfterFirstReverse.startsWith("-|-"), "原单 reversed_at 不得为空：$auditAfterFirstReverse")
                    assertTrue(
                        auditAfterFirstReverse.endsWith("|$EXPECTED_TOTAL|$STATUS_PENDING"),
                        "第一次红冲后原单合计/状态必须不变：$auditAfterFirstReverse",
                    )
                }
                reverseBill(vertx, originalId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "对已红冲原单再次红冲必须 409：${body.encode()}")
                    assertTrue(errorOf(body).contains("already reversed"), "409 消息应说明原单已被红冲：${body.encode()}")
                    assertEquals(2L, billCount(encId), "失败的红冲不得留下第二张红字单")
                    assertEquals(
                        auditAfterFirstReverse,
                        auditSnapshot(originalId),
                        "失败的红冲不得改写原单留痕（三列/金额/状态必须逐字不变）",
                    )
                }
                reverseBill(vertx, redId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "对红字单红冲必须 400：${body.encode()}")
                    assertEquals("cannot reverse a reversal bill", errorOf(body), "红字单红冲的 400 文案：${body.encode()}")
                    assertEquals(2L, billCount(encId), "失败的红冲不得留下新账单")
                    assertEquals(
                        1L,
                        countRows("SELECT count(*) FROM healthcare.bills WHERE id = '$redId' AND reversal_of = '$originalId'"),
                        "失败的红冲不得动红字单自身的关联",
                    )
                }
                reverseBill(vertx, zeroBillId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "0 元封口账单红冲必须 400（没有可冲销金额）：${body.encode()}")
                    assertEquals(
                        "bill total must be positive, cannot reverse",
                        errorOf(body),
                        "0 元封口账单红冲的 400 文案：${body.encode()}",
                    )
                    assertEquals(1L, billCount(zeroEncounterId), "0 元封口账单不得产生红字单")
                    assertEquals(
                        0L,
                        countRows("SELECT count(*) FROM healthcare.bills WHERE reversal_of = '$zeroBillId'"),
                        "0 元封口账单不得有红字单",
                    )
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE id = '$zeroBillId' " +
                                "AND reversed_at IS NULL AND reversal_reason IS NULL AND reversed_by IS NULL",
                        ),
                        "0 元封口账单不得被写任何留痕",
                    )
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  验收 6：红字单 / 已红冲原单不可缴费、不可加项
    // ========================================================================

    @Test
    fun `红字单与已红冲原单不可缴费也不可加项`(vertx: Vertx, ctx: VertxTestContext) {
        var originalId = ""
        var redId = ""
        var feeItemId = ""

        generateThenReverse(vertx, encId)
            .compose { (original, red) ->
                originalId = original
                redId = red
                ctx.verify {
                    assertEquals(2L, billCount(encId), "前置：原单 + 红字单")
                }
                // 明细项从原单快照取一个真实启用字典项，保证「不可加项」的 400 不是被字典校验挡下的
                detail(vertx, originalId)
            }
            .compose { (status, original) ->
                val items = original.getJsonArray("items")
                ctx.verify {
                    assertEquals(200, status, "原单详情应 200：${original.encode()}")
                    assertEquals(2, items.size(), "原单应有 2 条自动明细（床位费 + 护理费），用于取真实字典项 id：${original.encode()}")
                }
                feeItemId = requireNotNull(items.getJsonObject(0).getString("item_code")) { "明细缺少 item_code" }
                pay(vertx, redId, "1.00")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "红字单缴费必须 400：${body.encode()}")
                    assertTrue(errorOf(body).contains("cannot pay"), "400 消息应说明红字单不可缴费：${body.encode()}")
                    assertEquals(0L, paymentsOf(redId), "红字单缴费失败不得产生 payments 流水")
                    assertEquals(0L, paymentsOf(originalId), "整个 encounter 不得产生任何缴费流水")
                    assertEquals(
                        STATUS_PENDING,
                        stringValue("SELECT status FROM healthcare.bills WHERE id = '$redId'"),
                        "红字单状态不得变化",
                    )
                }
                pay(vertx, originalId, "1.00")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "已红冲原单缴费必须 400：${body.encode()}")
                    assertTrue(errorOf(body).contains("cannot pay"), "400 消息应说明已红冲原单不可缴费：${body.encode()}")
                    assertEquals(0L, paymentsOf(originalId), "已红冲原单缴费失败不得产生 payments 流水")
                    assertEquals(
                        0L,
                        countRows("SELECT count(*) FROM healthcare.payments WHERE bill_id IN ('$originalId', '$redId')"),
                        "整个 encounter 的缴费流水必须仍为 0 条",
                    )
                }
                addItem(vertx, redId, feeItemId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "红字单加项必须 400：${body.encode()}")
                    assertTrue(errorOf(body).contains("cannot add items"), "400 消息应说明红字单不可加项：${body.encode()}")
                    assertEquals(0L, itemsOf(redId), "红字单不得被加项（失败也不得写明细）")
                }
                addItem(vertx, originalId, feeItemId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "已红冲原单加项必须 400：${body.encode()}")
                    assertTrue(errorOf(body).contains("cannot add items"), "400 消息应说明已红冲原单不可加项：${body.encode()}")
                    assertEquals(2L, itemsOf(originalId), "已红冲原单的明细行数不得变化")
                    assertEquals(
                        0,
                        dbAmount(originalId).compareTo(BigDecimal(EXPECTED_TOTAL)),
                        "已红冲原单的合计不得被改写（实际 ${dbAmount(originalId)}）",
                    )
                    assertEquals(
                        STATUS_PENDING,
                        stringValue("SELECT status FROM healthcare.bills WHERE id = '$originalId'"),
                        "已红冲原单的状态不得被改写",
                    )
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  验收 6b：红冲后结算收束预览不得出现幻影欠费、不得要求 write_off_reason
    // ========================================================================

    @Test
    fun `红冲后结算收束预览未结余额为零且不要求减免原因`(vertx: Vertx, ctx: VertxTestContext) {
        var originalId = ""
        var redId = ""

        generateBill(vertx, dischargedEncounterId)
            .compose { (status, bill) ->
                originalId = requireNotNull(bill.getString("id")) { "生成响应缺少 id：${bill.encode()}" }
                ctx.verify {
                    assertEquals(201, status, "已离院 encounter 仍可生成账期账单：${bill.encode()}")
                    assertAmount(EXPECTED_TOTAL, bill, "total_amount", "生成账单合计")
                }
                request(vertx, HttpMethod.GET, "/healthcare/v1/encounters/$dischargedEncounterId/billing-settlement/preview")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "收束预览应 200：${body.encode()}")
                    assertEquals(PERIOD_START, body.getJsonObject("settlement_period")?.getString("start"), "收束区间起")
                    assertEquals(PERIOD_END, body.getJsonObject("settlement_period")?.getString("end"), "收束区间止")
                    assertAmount(EXPECTED_TOTAL, body, "pending_balance", "红冲前未结余额应等于原单合计（证明该断言非恒真）")
                    assertAmount(EXPECTED_TOTAL, body, "outstanding_total", "红冲前未结合计应等于原单合计")
                    assertEquals(true, body.getBoolean("requires_write_off"), "红冲前无押金可核销，必须要求减免原因：${body.encode()}")
                    assertAmount("0.00", body, "deposit_balance", "无押金登记")
                }
                reverseBill(vertx, originalId)
            }
            .compose { (status, body) ->
                redId = requireNotNull(body.getString("id")) { "红冲响应缺少 id：${body.encode()}" }
                ctx.verify {
                    assertEquals(201, status, "红冲应 201：${body.encode()}")
                    assertAmount("-$EXPECTED_TOTAL", body, "total_amount", "红字单合计")
                }
                request(vertx, HttpMethod.GET, "/healthcare/v1/encounters/$dischargedEncounterId/billing-settlement/preview")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "红冲后收束预览应 200：${body.encode()}")
                    assertAmount("0.00", body, "pending_balance", "已红冲原单不得计入未结余额（Q3/Q5 核心断言）")
                    assertAmount("0.00", body, "final_bill_total", "同账期已有账单（红字单账期一致），不得再算出一张最终账单")
                    assertAmount("0.00", body, "outstanding_total", "红冲后未结合计必须为 0，不得出现原单金额造成的幻影欠费")
                    assertAmount("0.00", body, "max_offset", "无可核销金额")
                    assertEquals(false, body.getBoolean("requires_write_off"), "未结为 0 时不得要求 write_off_reason：${body.encode()}")
                    assertEquals(PERIOD_START, body.getJsonObject("settlement_period")?.getString("start"), "收束区间起不变")
                    assertEquals(PERIOD_END, body.getJsonObject("settlement_period")?.getString("end"), "收束区间止不变")
                }
                // 最强证明：不带 write_off_reason 的真实收束必须成功（201），而不是 409
                request(
                    vertx,
                    HttpMethod.POST,
                    "/healthcare/v1/encounters/$dischargedEncounterId/billing-settlement",
                    JsonObject(),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "未结为 0 时收束必须 201（不得要求 write_off_reason）：${body.encode()}")
                    assertNotNull(body.getString("settled_at"), "收束后 encounter 必须有 settled_at：${body.encode()}")
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.encounters WHERE id = '$dischargedEncounterId' AND settled_at IS NOT NULL",
                        ),
                        "encounters.settled_at 必须已置位",
                    )
                    assertEquals(2L, billCount(dischargedEncounterId), "收束不得新建账单（红字单账期已覆盖）")
                    assertEquals(
                        0L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE encounter_id = '$dischargedEncounterId' AND outstanding_amount <> 0",
                        ),
                        "收束时不得为任何账单写未结快照（幻影欠费的落库形态）",
                    )
                    assertEquals(
                        0L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE encounter_id = '$dischargedEncounterId' AND write_off_reason IS NOT NULL",
                        ),
                        "未结为 0 时不得写任何减免原因",
                    )
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE id = '$redId' AND reversal_of IS NOT NULL",
                        ),
                        "收束后红字单自身形态不得被改写",
                    )
                }
                request(vertx, HttpMethod.GET, "/healthcare/v1/encounters/$dischargedEncounterId/billing-settlement/preview")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "收束后预览必须 409（证明上一步确实收束了）：${body.encode()}")
                    assertTrue(errorOf(body).contains("already settled"), "409 消息：${body.encode()}")
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  验收 7：有缴费记录的账单红冲 409
    // ========================================================================

    @Test
    fun `已有缴费记录的账单红冲409且不产生红字单`(vertx: Vertx, ctx: VertxTestContext) {
        var originalId = ""

        generateBill(vertx, encId)
            .compose { (status, bill) ->
                originalId = requireNotNull(bill.getString("id")) { "生成响应缺少 id：${bill.encode()}" }
                ctx.verify {
                    assertEquals(201, status, "生成账单应 201：${bill.encode()}")
                    assertAmount(EXPECTED_TOTAL, bill, "total_amount", "生成账单合计")
                }
                pay(vertx, originalId, "100.00")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "部分缴费应 201：${body.encode()}")
                    assertEquals(
                        "100.00",
                        stringValue("SELECT amount::text FROM healthcare.payments WHERE bill_id = '$originalId'"),
                        "缴费流水金额",
                    )
                    assertEquals(1L, paymentsOf(originalId), "应恰有 1 条缴费流水")
                    assertEquals(
                        STATUS_PENDING,
                        stringValue("SELECT status FROM healthcare.bills WHERE id = '$originalId'"),
                        "部分缴费后仍为 $STATUS_PENDING",
                    )
                }
                reverseBill(vertx, originalId)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "有缴费记录的账单红冲必须 409（先退款/冲正）：${body.encode()}")
                    assertTrue(errorOf(body).contains("payments"), "409 消息应说明存在缴费记录：${body.encode()}")
                    assertEquals(1L, billCount(encId), "失败的红冲不得留下红字单")
                    assertEquals(
                        0L,
                        countRows("SELECT count(*) FROM healthcare.bills WHERE reversal_of = '$originalId'"),
                        "不得有指向原单的红字单",
                    )
                    assertEquals(1L, paymentsOf(originalId), "失败的红冲不得动缴费流水")
                    assertEquals(
                        1L,
                        countRows(
                            "SELECT count(*) FROM healthcare.bills WHERE id = '$originalId' " +
                                "AND reversed_at IS NULL AND reversed_by IS NULL AND reversal_reason IS NULL",
                        ),
                        "失败的红冲不得写任何留痕",
                    )
                    assertEquals(
                        0,
                        dbAmount(originalId).compareTo(BigDecimal(EXPECTED_TOTAL)),
                        "原单合计不得变化（实际 ${dbAmount(originalId)}）",
                    )
                    assertEquals(
                        STATUS_PENDING,
                        stringValue("SELECT status FROM healthcare.bills WHERE id = '$originalId'"),
                        "原单状态不得变化",
                    )
                }
                Future.succeededFuture(Pair(200, JsonObject()))
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
