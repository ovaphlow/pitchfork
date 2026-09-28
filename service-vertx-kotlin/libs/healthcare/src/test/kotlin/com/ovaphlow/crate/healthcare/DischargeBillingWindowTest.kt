package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 023 决策 A：离院/去世后、结算收束前的**可收费窗口** 非数据库测试
 * （mockk 桩 + 嵌入式 HTTP，默认流水线运行，**禁止连库**）。
 *
 * 背景：离院/去世不再收束账单，`encounters.settled_at` 只有「结算收束」会写；
 * 账单层冻结条件始终只有 `settled_at`，与入住状态无关。
 *
 * 覆盖验收口径（逐条对应 023 验收): 
 *  1. 窗口内 `POST /encounters/:id/bills`、`POST /bills/:id/items`、
 *     `POST /bills/:id/payments` 三个动作均可用（201）→ `窗口内生成账单手工加项缴费均可用`；
 *  2. 离院/去世后 `settled_at` 为 null、账单未被写入（独立 fixture，
 *     与 `BillingSettlementTest` 互为交叉验证）→ `离院与去世后settled_at为null且账单未被写入`；
 *  3. 窗口内收束成功：区间最终账单 + 押金核销 + 减免留痕 + 冻结 + 押金余额减少
 *     → `窗口内结算收束生成区间账单核销押金并冻结`；
 *  4. 收束后生成/加项/缴费/再次收束一律 409 且无任何写入
 *     → `收束后生成加项缴费与再次收束一律409且无写入`；
 *  5. 021 门禁在离院后收束时真实生效：核销后仍有未结且无 `write_off_reason` → 409
 *     → `核销后仍有未结且无减免原因时收束409且不进入冻结`；
 *  6. 去世路径与离院路径同构 → `去世路径与离院路径同构`。
 *
 * 限制（不声称覆盖）：mockk 桩不模拟 PostgreSQL 事务回滚，用例 5 只能断言「未进入冻结」；
 * 真实「离院零账单写入」残差、失败整笔回滚与并发收束只能由后置隔离库集成测试覆盖。
 */
@ExtendWith(VertxExtension::class)
class DischargeBillingWindowTest {

    // ========================================================================
    //  1. 窗口内：生成 / 加项 / 缴费三动作均可用
    // ========================================================================

    @Test
    fun `窗口内生成账单手工加项缴费均可用`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fixture(
            encounters = mutableListOf(dischargedEncounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        var generatedBillId: String? = null

        withServer(vertx, stub, userId = "cashier-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/bills",
                JsonObject().put("month", "2026-09"),
            ).compose { (billStatus, billBody) ->
                ctx.verify {
                    assertEquals(201, billStatus, "离院后未收束窗口内必须可生成账单")
                    assertEquals("2026-09-01", billBody.getString("period_start"))
                    assertEquals("2026-09-20", billBody.getString("period_end"), "账期止裁剪到离院日")
                    assertEquals("待缴费", billBody.getString("status"))
                    assertEquals(
                        0,
                        BigDecimal("3675.00").compareTo(amount(billBody.getValue("total_amount"))),
                        "床位 20 天×100 + 护理 20 天×80 + 伙食 2.5×30",
                    )
                    generatedBillId = billBody.getString("id")
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/bills/$generatedBillId/items",
                    JsonObject().put("item_id", "fee-other").put("quantity", 1),
                ).compose { (itemStatus, itemBody) ->
                    ctx.verify {
                        assertEquals(201, itemStatus, "离院后未收束窗口内必须可手工加项")
                        assertEquals(
                            0,
                            BigDecimal("3875.00").compareTo(amount(itemBody.getValue("total_amount"))),
                            "加项后合计 = 3675 + 200",
                        )
                    }
                    httpRequest(
                        vertx, port, HttpMethod.POST,
                        "/healthcare/v1/bills/$generatedBillId/payments",
                        JsonObject().put("amount", 100).put("method", "现金"),
                    ).map { (payStatus, payBody) ->
                        ctx.verify {
                            assertEquals(201, payStatus, "离院后未收束窗口内必须可缴费")
                            assertEquals(0, BigDecimal("100").compareTo(amount(payBody.getValue("amount"))))
                            assertEquals(
                                "待缴费",
                                stub.bills.first { it["id"] == generatedBillId }["status"],
                                "部分缴费后账单仍为 待缴费",
                            )
                            assertNull(stub.encounters.single()["settled_at"], "窗口内三动作均不得写 settled_at")
                        }
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ========================================================================
    //  2. 离院 / 去世不写账单（独立 fixture）
    // ========================================================================

    @Test
    fun `离院与去世后settled_at为null且账单未被写入`() {
        // 离院：ACTIVE → DISCHARGED
        val dischargeStub = fixture(
            encounters = mutableListOf(encounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00")),
        )
        HealthcareService(dischargeStub.pool)
            .dischargeEncounter("enc-1", JsonObject().put("discharge_date", "2026-09-20T10:00:00+08:00"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("DISCHARGED", dischargeStub.encounters.single()["status"])
        assertNull(dischargeStub.encounters.single()["settled_at"], "023：离院不写 settled_at")
        assertBillsUntouched(dischargeStub, "离院")

        // 去世：ACTIVE → DECEASED（同构）
        val deathStub = fixture(
            encounters = mutableListOf(encounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结清")),
        )
        HealthcareService(deathStub.pool)
            .deathEncounter(
                "enc-1",
                JsonObject()
                    .put("death_date", "2026-09-20T14:00:00+08:00")
                    .put("death_cause", "心脏骤停"),
            )
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("DECEASED", deathStub.encounters.single()["status"])
        assertNull(deathStub.encounters.single()["settled_at"], "023：去世不写 settled_at")
        assertBillsUntouched(deathStub, "去世")
    }

    /** 023 断言：离院/去世全过程不得有任何账单/台账写入，账单逐行未变。 */
    private fun assertBillsUntouched(stub: DatabaseStub, path: String) {
        assertTrue(
            stub.tuples.none { it.first.contains("insert into healthcare.bills") },
            "023：$path 不得生成区间最终账单: ${stub.tuples.map { it.first }}",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.bills") },
            "023：$path 不得更新任何账单（冻结未发生）: ${stub.tuples.map { it.first }}",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.encounters") && it.first.contains("settled_at") },
            "023：$path 不得写 encounters.settled_at",
        )
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
        assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })
        val bill = stub.bills.single()
        assertNull(bill["settled_at"], "023：$path 后账单不得写 settled_at")
        assertNull(bill["outstanding_amount"], "023：$path 后未结快照不得写入")
        assertNull(bill["write_off_reason"], "023：$path 后减免原因不得写入")
    }

    // ========================================================================
    //  3. 窗口内收束：区间账单 + 核销 + 减免 + 冻结 + 押金减少
    // ========================================================================

    @Test
    fun `窗口内结算收束生成区间账单核销押金并冻结`() {
        val stub = fixture(
            encounters = mutableListOf(dischargedEncounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-07-01", "2026-07-31", "5655.00")),
        )
        stub.deposits.add(depositRow("登记", "5000.00"))
        val reason = "离院结算，家属书面确认不再追收"

        val encounter = HealthcareService(stub.pool)
            .settleEncounterBilling(
                "enc-1",
                JsonObject().put("deposit_offset", 1000).put("write_off_reason", reason),
                "cashier-1",
            )
            .toCompletionStage().toCompletableFuture().get()
        assertNotNull(encounter.getString("settled_at"), "收束必须写 encounters.settled_at")

        // 区间最终账单：无已结算账期 → 起 = 入住日 08-01，止 = 离院日 09-20（闭区间 51 天）
        val (_, values) = stub.tuples.first { it.first.contains("insert into healthcare.bills") }
        assertEquals(LocalDate.parse("2026-08-01"), values[2], "区间起 = 无已结算账期时取入住日")
        assertEquals(LocalDate.parse("2026-09-20"), values[3], "区间止 = 离院日")
        assertEquals("待缴费", values[4], "021：区间最终账单以 待缴费 建立")
        assertEquals(
            0,
            BigDecimal("9255.00").compareTo(values[5] as BigDecimal),
            "区间账单自动计费 = 床位 51×100 + 护理 51×80 + 伙食 2.5×30",
        )

        // 全部账单 已结算 + settled_at + 未结快照 + 减免原因
        assertEquals(2, stub.bills.size)
        for (bill in stub.bills) {
            assertEquals("已结算", bill["status"], "冻结后该 encounter 全部账单必须为 已结算")
            assertNotNull(bill["settled_at"], "冻结后每张账单必须写 settled_at")
        }
        val existing = stub.bills.first { it["id"] == "bill-1" }
        assertEquals(
            0,
            BigDecimal("4655.00").compareTo(amount(existing["outstanding_amount"])),
            "核销 1000 后既有账单未结 = 5655 − 1000",
        )
        assertEquals(reason, existing["write_off_reason"], "021：未结行必须带减免原因")
        val finalBill = stub.bills.first { it["id"] == values[0] }
        assertEquals(0, BigDecimal("9255.00").compareTo(amount(finalBill["outstanding_amount"])))
        assertEquals(reason, finalBill["write_off_reason"])

        // 核销双台账：payments(方式 押金) + deposit_records(类型 核销)
        val paymentInserts = stub.tuples.filter { it.first.contains("insert into healthcare.payments") }
        assertEquals(1, paymentInserts.size, "核销 1000 < 既有账单余额 5655 → 命中一笔")
        assertEquals(PaymentService.METHOD_DEPOSIT, paymentInserts.single().second[3])
        assertEquals("cashier-1", paymentInserts.single().second[4])
        val depositInserts = stub.tuples.filter { it.first.contains("insert into healthcare.deposit_records") }
        assertEquals(1, depositInserts.size)
        assertEquals(DepositOffsetService.TYPE_OFFSET, depositInserts.single().second[2])

        // 押金余额按核销减少：登记 5000 − 核销 1000 = 4000
        val balance = DepositService.balance(stub.pool, "enc-1")
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(0, BigDecimal("4000.00").compareTo(balance), "押金余额 = Σ登记 − Σ核销")
    }

    // ========================================================================
    //  4. 收束后：一律 409 且无任何写入
    // ========================================================================

    @Test
    fun `收束后生成加项缴费与再次收束一律409且无写入`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = fixture(
            encounters = mutableListOf(
                dischargedEncounterRow(mapOf("settled_at" to OffsetDateTime.parse("2026-09-20T18:00:00+08:00"))),
            ),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-08-01", "2026-08-31", "5655.00", status = "已结算")),
        )

        withServer(vertx, stub, userId = "cashier-1") { port ->
            httpRequest(
                vertx, port, HttpMethod.POST,
                "/healthcare/v1/encounters/enc-1/bills",
                JsonObject().put("month", "2026-09"),
            ).compose { (billStatus, billBody) ->
                ctx.verify {
                    assertEquals(409, billStatus, "收束后生成账单必须 409")
                    assertTrue(billBody.getString("error")?.contains("settled") == true, "got: ${billBody.getString("error")}")
                }
                httpRequest(
                    vertx, port, HttpMethod.POST,
                    "/healthcare/v1/bills/bill-1/items",
                    JsonObject().put("item_id", "fee-other").put("quantity", 1),
                ).compose { (itemStatus, itemBody) ->
                    ctx.verify {
                        assertEquals(409, itemStatus, "收束后手工加项必须 409")
                        assertTrue(itemBody.getString("error")?.contains("settled") == true, "got: ${itemBody.getString("error")}")
                    }
                    httpRequest(
                        vertx, port, HttpMethod.POST,
                        "/healthcare/v1/bills/bill-1/payments",
                        JsonObject().put("amount", 100).put("method", "现金"),
                    ).compose { (payStatus, payBody) ->
                        ctx.verify {
                            assertEquals(409, payStatus, "收束后缴费必须 409")
                            assertTrue(payBody.getString("error")?.contains("settled") == true, "got: ${payBody.getString("error")}")
                        }
                        httpRequest(
                            vertx, port, HttpMethod.POST,
                            "/healthcare/v1/encounters/enc-1/billing-settlement",
                            JsonObject().put("write_off_reason", "重复收束"),
                        ).map { (settleStatus, settleBody) ->
                            ctx.verify {
                                assertEquals(409, settleStatus, "收束后再次收束必须 409")
                                assertTrue(
                                    settleBody.getString("error")?.contains("already settled") == true,
                                    "got: ${settleBody.getString("error")}",
                                )
                                // 四个动作一律无写入
                                assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bills") })
                                assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.bill_items") })
                                assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.payments") })
                                assertTrue(stub.tuples.none { it.first.contains("insert into healthcare.deposit_records") })
                                assertTrue(stub.tuples.none { it.first.contains("update healthcare.bills") })
                                assertTrue(
                                    stub.tuples.none {
                                        it.first.contains("update healthcare.encounters") && it.first.contains("settled_at")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }.onComplete { ar ->
            if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause())
        }
    }

    // ========================================================================
    //  5. 021 门禁：未结无原因 → 409 且不进入冻结
    // ========================================================================

    @Test
    fun `核销后仍有未结且无减免原因时收束409且不进入冻结`() {
        val stub = fixture(
            encounters = mutableListOf(dischargedEncounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-07-01", "2026-07-31", "5655.00")),
        )
        stub.deposits.add(depositRow("登记", "5000.00"))

        val cause = causeOf(
            HealthcareService(stub.pool)
                .settleEncounterBilling("enc-1", JsonObject().put("deposit_offset", 1000), "cashier-1"),
        )
        assertInstanceOf(ConflictException::class.java, cause)
        assertTrue(cause.message?.contains("require explicit write-off") == true, "got: ${cause.message}")

        // 未进入冻结：不写账单未结快照、不写 encounters.settled_at，账单保持原状态
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.bills") && it.first.contains("settled_at") },
            "门禁拦截后不得冻结账单",
        )
        assertTrue(
            stub.tuples.none { it.first.contains("update healthcare.encounters") && it.first.contains("settled_at") },
            "门禁拦截后不得写 encounters.settled_at",
        )
        assertNull(stub.encounters.single()["settled_at"])
        assertTrue(stub.bills.none { it["status"] == "已结算" }, "拦截后账单不得流转 已结算")
        // 限制说明：mockk 桩不模拟 PostgreSQL 事务回滚，本用例只能断言「未进入冻结」；
        // 真实「整笔回滚、零残差」由后置隔离库集成测试覆盖（见 docs/plans/023）。
    }

    // ========================================================================
    //  6. 去世路径与离院路径同构
    // ========================================================================

    @Test
    fun `去世路径与离院路径同构`() {
        val stub = fixture(
            encounters = mutableListOf(encounterRow()),
            bills = mutableListOf(billRow("bill-1", "enc-1", "2026-07-01", "2026-07-31", "5655.00")),
        )
        stub.deposits.add(depositRow("登记", "5000.00"))
        val service = HealthcareService(stub.pool)

        // 去世：只标记事实，不收束账单
        val deceased = service
            .deathEncounter(
                "enc-1",
                JsonObject()
                    .put("death_date", "2026-09-20T14:00:00+08:00")
                    .put("death_cause", "心脏骤停"),
            )
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("DECEASED", deceased.getString("status"))
        // 响应中的时间经服务端 encounterJson 归一化（OffsetDateTime.toString()，零秒省略）
        assertEquals("2026-09-20T14:00+08:00", deceased.getString("death_date"))
        assertNull(deceased.getString("settled_at"), "023：去世不写 settled_at")
        assertBillsUntouched(stub, "去世")

        // 窗口内仍可收束（与离院同一条路径、同一口径）
        val settlementReason = "去世结算，剩余未结确认为减免"
        val settled = service
            .settleEncounterBilling(
                "enc-1",
                JsonObject().put("deposit_offset", 1000).put("write_off_reason", settlementReason),
                "cashier-1",
            )
            .toCompletionStage().toCompletableFuture().get()
        assertNotNull(settled.getString("settled_at"), "去世后收束必须写 settled_at")

        val (_, values) = stub.tuples.first { it.first.contains("insert into healthcare.bills") }
        assertEquals(LocalDate.parse("2026-08-01"), values[2])
        assertEquals(LocalDate.parse("2026-09-20"), values[3], "区间止 = 去世日")
        assertEquals(0, BigDecimal("9255.00").compareTo(values[5] as BigDecimal))
        for (bill in stub.bills) {
            assertEquals("已结算", bill["status"])
            assertNotNull(bill["settled_at"])
        }
        val existing = stub.bills.first { it["id"] == "bill-1" }
        assertEquals(0, BigDecimal("4655.00").compareTo(amount(existing["outstanding_amount"])))
        assertEquals(settlementReason, existing["write_off_reason"])
    }

    // ========================================================================
    //  fixture 构造
    // ========================================================================

    private fun dischargedEncounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> =
        encounterRow(
            mapOf(
                "status" to "DISCHARGED",
                "discharge_date" to OffsetDateTime.parse("2026-09-20T10:00:00+08:00"),
            ) + overrides,
        )

    private fun encounterRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260801001",
            "department" to "三楼",
            "ward" to "301-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "death_date" to null,
            "death_cause" to null,
            "admitting_diagnosis" to "高血压",
            "discharge_diagnosis" to null,
            "attending_physician" to "赵医生",
            "status" to "ACTIVE",
            "settled_at" to null,
            "metadata" to JsonObject(),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun periodRow(): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to "period-1",
            "patient_id" to "pat-1",
            "service_type" to "ELDERLY_CARE",
            "start_date" to LocalDate.parse("2026-08-01"),
            "end_date" to null,
            "coordinator" to null,
            "encounter_id" to "enc-1",
            "status" to "ACTIVE",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )

    private fun feeItemRow(id: String, category: String, name: String, price: String): Map<String, Any?> =
        mapOf(
            "id" to id,
            "category" to category,
            "name" to name,
            "unit_price" to BigDecimal(price),
            "status" to "启用",
        )

    private fun assessmentRow(date: String, level: String): Map<String, Any?> =
        mapOf(
            "encounter_id" to "enc-1",
            "assess_date" to LocalDate.parse(date),
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "result_level" to level,
        )

    private fun billRow(
        id: String,
        encounterId: String,
        periodStart: String,
        periodEnd: String,
        total: String,
        status: String = "待缴费",
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to id,
            "encounter_id" to encounterId,
            "period_start" to LocalDate.parse(periodStart),
            "period_end" to LocalDate.parse(periodEnd),
            "status" to status,
            "total_amount" to BigDecimal(total),
            "settled_at" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
        )

    private fun depositRow(type: String, amount: String): Map<String, Any?> =
        mapOf(
            "id" to "dep-$type-$amount",
            "encounter_id" to "enc-1",
            "type" to type,
            "amount" to BigDecimal(amount),
            "operator" to "cashier-1",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )

    /** 标准计费环境：床位 100/天、护理 中度依赖 80/天、伙食 30/餐、评估 08-01 中度依赖、餐次 正常×2+部分×1。 */
    private fun fixture(
        encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(encounterRow()),
        bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
    ): DatabaseStub =
        DatabaseStub(
            encounters = encounters,
            periods = mutableListOf(periodRow()),
            feeItems = mutableListOf(
                feeItemRow("fee-bed", "床位费", "标准床位", "100"),
                feeItemRow("fee-nurse", "护理费", "中度依赖", "80"),
                feeItemRow("fee-meal", "伙食费", "三餐", "30"),
                feeItemRow("fee-other", "其他", "自费药", "200"),
            ),
            assessments = mutableListOf(assessmentRow("2026-08-01", "中度依赖")),
            mealsByEncounter = mutableMapOf("enc-1" to listOf("正常", "正常", "部分")),
            bills = bills,
        )

    // ========================================================================
    //  全库 mock 桩（按 normalized SQL 特征分发；bills/encounters 状态随写入演进）
    //  说明：与 BillingSettlementTest 的同名桩各自独立（本文件是独立 fixture），
    //  仅实现本文件用到的 SQL 形状。
    // ========================================================================

    private class DatabaseStub(
        val encounters: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var periods: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var orders: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var patients: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var feeItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var assessments: MutableList<Map<String, Any?>> = mutableListOf(),
        var mealsByEncounter: MutableMap<String, List<String>> = mutableMapOf(),
        var bills: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        var billItems: MutableList<Map<String, Any?>> = mutableListOf(),
        var payments: MutableList<Map<String, Any?>> = mutableListOf(),
        var deposits: MutableList<Map<String, Any?>> = mutableListOf(),
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                val values = tupleValues(firstArg())
                tuples.add(sql to values)
                when {
                    // ——— encounters ———
                    sql.contains("update healthcare.encounters") && sql.contains("settled_at") -> {
                        val target = encounters.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["settled_at"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.encounters") && sql.contains("death_date") -> {
                        val target = encounters.firstOrNull { it["id"] == values.last() }
                        if (target != null) {
                            target["death_date"] = values[0]
                            target["status"] = values[1]
                            target["updated_at"] = values[2]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.encounters") -> {
                        val target = encounters.firstOrNull { it["id"] == values.getOrNull(4) }
                        if (target != null) {
                            target["discharge_date"] = values[0]
                            target["discharge_diagnosis"] = values[1]
                            target["status"] = values[2]
                            target["updated_at"] = values[3]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("select 1") && sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter {
                            it["patient_id"] == values.getOrNull(0) &&
                                it["encounter_type"] == values.getOrNull(1) &&
                                it["status"] == values.getOrNull(2) &&
                                it["id"] != values.getOrNull(3)
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    sql.contains("from healthcare.encounters") -> {
                        val scoped = encounters.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— patients ———
                    sql.contains("update healthcare.patients") -> Future.succeededFuture(rowSet())
                    sql.contains("from healthcare.patients") -> {
                        val scoped = patients.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— medical orders（默认无活动医嘱） ———
                    sql.contains("from healthcare.medical_orders") -> {
                        val scoped = orders.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— nursing 照护周期 ———
                    sql.contains("update nursing.nursing_service_periods") -> Future.succeededFuture(rowSet())
                    sql.contains("from nursing.nursing_task_executions") -> Future.succeededFuture(rowSet())
                    sql.contains("from nursing.nursing_service_periods") -> {
                        val scoped = if (sql.contains("where id")) {
                            periods.filter { it["id"] == values.getOrNull(0) }
                        } else {
                            periods.filter { it["encounter_id"] == values.getOrNull(0) }
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 费用字典 ———
                    sql.contains("from healthcare.fee_items") && sql.contains("status = $") ->
                        Future.succeededFuture(
                            rowSet(*feeItems.filter { it["status"] == values.getOrNull(0) }.map { mockRow(it) }.toTypedArray()),
                        )
                    sql.contains("from healthcare.fee_items") ->
                        Future.succeededFuture(
                            feeItems.firstOrNull { it["id"] == values.getOrNull(0) }?.let { rowSet(mockRow(it)) } ?: rowSet(),
                        )
                    // ——— 护理评估 ———
                    sql.contains("from nursing.nursing_assessments") -> {
                        val scoped = assessments.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (values.getOrNull(1) == null || !(it["assess_date"] as LocalDate).isAfter(values[1] as LocalDate))
                        }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 就餐执行 ———
                    sql.contains("from dining.dining_meal_executions") -> {
                        val statuses = mealsByEncounter[values.getOrNull(0)] ?: emptyList()
                        Future.succeededFuture(rowSet(*statuses.map { mockRow(mapOf("status" to it)) }.toTypedArray()))
                    }
                    // ——— bills ———
                    sql.contains("insert into healthcare.bills") -> {
                        bills.add(
                            mutableMapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "period_start" to values[2],
                                "period_end" to values[3],
                                "status" to values[4],
                                "total_amount" to values[5],
                                "settled_at" to null,
                                "outstanding_amount" to BigDecimal.ZERO,
                                "write_off_reason" to null,
                                "created_at" to values[6],
                                "updated_at" to values[7],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") && sql.contains("settled_at") -> {
                        val target = bills.firstOrNull { it["id"] == values.last() }
                        if (target != null) {
                            target["status"] = values[0]
                            target["settled_at"] = values[1]
                            target["outstanding_amount"] = values[2]
                            target["updated_at"] = values[3]
                            target["write_off_reason"] = if (values.size >= 6) values[4] else null
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") && sql.contains("total_amount") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["total_amount"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("update healthcare.bills") -> {
                        val target = bills.firstOrNull { it["id"] == values.getOrNull(2) }
                        if (target != null) {
                            target["status"] = values[0]
                            target["updated_at"] = values[1]
                        }
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("max(") && sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter {
                            it["encounter_id"] == values.getOrNull(0) &&
                                (it["status"] == values.getOrNull(1) || it["status"] == values.getOrNull(2))
                        }
                        val maxEnd = scoped.mapNotNull { it["period_end"] as? LocalDate }.maxOrNull()
                        Future.succeededFuture(rowSet(mockRow(mapOf("max_end" to maxEnd))))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") && sql.contains("period_start") -> {
                        val count = bills.count {
                            it["encounter_id"] == values.getOrNull(0) &&
                                it["period_start"] == values.getOrNull(1) &&
                                it["period_end"] == values.getOrNull(2)
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    sql.contains("count(*)") && sql.contains("from healthcare.bills") -> {
                        val count = bills.count { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to count.toLong()))))
                    }
                    // ——— 核销/未结目标账单：待缴费 且 余额 > 0（必须先于通用 bills 分支） ———
                    sql.contains("from healthcare.bills") && sql.contains("left outer join") -> {
                        val scoped = bills
                            .filter { it["encounter_id"] == values.getOrNull(2) && it["status"] == values.getOrNull(3) }
                            .mapNotNull { bill ->
                                val paid = payments
                                    .filter { it["bill_id"] == bill["id"] }
                                    .fold(BigDecimal.ZERO) { acc, payment -> acc.add(payment["amount"] as BigDecimal) }
                                val balance = (bill["total_amount"] as BigDecimal).subtract(paid)
                                if (balance.signum() <= 0) {
                                    null
                                } else {
                                    mapOf(
                                        "id" to bill["id"],
                                        "period_start" to bill["period_start"],
                                        "period_end" to bill["period_end"],
                                        "balance" to balance,
                                    )
                                }
                            }
                            .sortedWith(
                                compareBy<Map<String, Any?>> { it["period_start"] as LocalDate }
                                    .thenBy { it["id"] as String },
                            )
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— 冻结前的全部账单 id ———
                    sql.contains("select healthcare.bills.id from healthcare.bills") -> {
                        val scoped = bills.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(
                            rowSet(*scoped.map { mockRow(mapOf("id" to it["id"])) }.toTypedArray()),
                        )
                    }
                    sql.contains("from healthcare.bills") -> {
                        val scoped = bills.filter { it["id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— bill items ———
                    sql.contains("insert into healthcare.bill_items") -> {
                        billItems.add(
                            mapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "source" to values[2],
                                "item_code" to values[3],
                                "item_name" to values[4],
                                "unit_price" to values[5],
                                "quantity" to values[6],
                                "amount" to values[7],
                                "created_at" to values[8],
                                "updated_at" to values[9],
                                "remark" to values.getOrNull(10),
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    sql.contains("sum(") && sql.contains("from healthcare.bill_items") -> {
                        val scoped = billItems.filter { it["bill_id"] == values.getOrNull(0) }
                        val total = scoped.fold(BigDecimal.ZERO) { acc, item ->
                            acc.add(item["amount"] as BigDecimal)
                        }
                        Future.succeededFuture(rowSet(mockRow(mapOf("total" to total))))
                    }
                    sql.contains("from healthcare.bill_items") -> {
                        val scoped = billItems.filter { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(rowSet(*scoped.map { mockRow(it) }.toTypedArray()))
                    }
                    // ——— payments（含核销写入：8 个绑定值时 metadata 在 $6） ———
                    sql.contains("insert into healthcare.payments") -> {
                        val hasMetadata = values.size >= 8
                        payments.add(
                            mapOf(
                                "id" to values[0],
                                "bill_id" to values[1],
                                "amount" to values[2],
                                "method" to values[3],
                                "operator" to values[4],
                                "metadata" to if (hasMetadata) values[5] else null,
                                "created_at" to if (hasMetadata) values[6] else values[5],
                                "updated_at" to if (hasMetadata) values[7] else values[6],
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    // ——— deposit_records（核销写入 type = 核销） ———
                    sql.contains("insert into healthcare.deposit_records") -> {
                        deposits.add(
                            mapOf(
                                "id" to values[0],
                                "encounter_id" to values[1],
                                "type" to values[2],
                                "amount" to values[3],
                                "operator" to values[4],
                                "metadata" to values.getOrNull(5),
                                "created_at" to values.getOrNull(6),
                                "updated_at" to values.getOrNull(7),
                            ),
                        )
                        Future.succeededFuture(rowSet())
                    }
                    // ——— 押金余额（口径唯一来自 DepositService.balanceOf） ———
                    sql.contains("from healthcare.deposit_records") -> {
                        val scoped = deposits.filter { it["encounter_id"] == values.getOrNull(0) }
                        Future.succeededFuture(
                            rowSet(
                                *scoped.map {
                                    mockRow(mapOf("type" to it["type"], "amount" to it["amount"]))
                                }.toTypedArray(),
                            ),
                        )
                    }
                    sql.contains("from healthcare.payments") -> {
                        val scoped = payments.filter { it["bill_id"] == values.getOrNull(0) }
                        Future.succeededFuture(
                            rowSet(*scoped.map { mockRow(mapOf("amount" to it["amount"])) }.toTypedArray()),
                        )
                    }
                    else -> Future.succeededFuture(rowSet())
                }
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            queries.add(lastSql)
        }
    }

    // ========================================================================
    //  嵌入式 HTTP 与断言辅助
    // ========================================================================

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body.encode())
                else req.send()
            }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun <T> withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        userId: String? = null,
        block: (Int) -> Future<T>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose {
                server.close().map { Unit }
            }
        }
    }

    private fun causeOf(future: Future<*>): Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get()
            throw AssertionError("expected future to fail")
        } catch (error: Throwable) {
            var cause = error
            while (cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException) {
                cause = cause.cause ?: break
            }
            return cause
        }
    }

    private fun amount(value: Any?): BigDecimal =
        BigDecimal.valueOf((value as Number).toDouble())
}

// ========================================================================
//  mock 基础设施（顶层私有：供本文件的桩与测试类共用）
// ========================================================================

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getBigDecimal(any<String>()) } answers { values[firstArg<String>()] as? BigDecimal }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}
