package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * 011/025 药房发药闭环 PostgreSQL 集成测试（仅隔离 `aceso_test`）。
 *
 * 与生产 `apps/aceso/Main.kt` 的装配等价：全局身份门注入 `userId`（`AcesoIntegrationTestSupport.fakeAuth`）、
 * 医嘱只读端口与库存出库端口都用同连接适配器、025 药品目录端口用 `MaterialService.findMaterialById`。
 *
 * 025 §7.2 覆盖点：
 *   3. 待接方列表返回 `material_id`/`material_code`/`material_name`，历史自由文本医嘱为 null；
 *   4. 请求 `material_id` 与医嘱绑定不一致 → 409，且无发药单、无库存操作、医嘱未被改动；
 *   5. 存量无绑定医嘱一次性补绑成功：同事务写绑定字段与 `material_bound_by`/`material_bound_at`，
 *      发药确认后库存扣减且 `stock_operation_detail_id` 回写；
 *   6. 补绑路径失败（耗材 / 非 ACTIVE / 不存在）→ 409 或 404，医嘱 `order_details` 保持原样。
 *
 * 另覆盖 §4.5 fail-closed：补绑路径缺药品目录端口 → 503；补绑路径缺可信身份 → 401
 * （且已绑定医嘱在同一无身份挂载点上仍可正常发药，证明 401 只作用于补绑路径）。
 */
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
class DispenseIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "dp-"
    override val serverPort = 18511

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient")}', '发药测试长者', '男', '1940-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // V501 唯一索引 uq_encounters_active_elderly_care 只允许每位长者一条
        // ACTIVE + ELDERLY_CARE 入住，因此第二个入住必须挂在另一位长者名下。
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient2")}', '发药测试长者二', '女', '1941-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // dp-enc：存量自由文本医嘱（无 material_id），补绑路径夹具
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc")}', '${id("patient")}', 'ELDERLY_CARE', 'DP-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status)
            VALUES ('${id("period")}', '${id("patient")}', 'ELDERLY_CARE', '${id("enc")}', '2026-08-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // dp-enc2：025 已绑定医嘱 + 第二条存量文本医嘱，用于一致性与补绑失败用例
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc2")}', '${id("patient2")}', 'ELDERLY_CARE', 'DP-ENC-2', '2026-08-01T00:00:00+08:00', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO nursing.nursing_service_periods (id, patient_id, service_type, encounter_id, start_date, status)
            VALUES ('${id("period2")}', '${id("patient2")}', 'ELDERLY_CARE', '${id("enc2")}', '2026-08-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )

        // 存量自由文本医嘱（025 之前的形态：只有 drug_name，没有 material_id）
        executeSql(
            """
            INSERT INTO healthcare.medical_orders (id, encounter_id, order_type, order_class, order_content, order_details, start_time, doctor, status, nurse_checked_by, nurse_checked_at)
            VALUES ('${id("order")}', '${id("enc")}', 'MEDICATION', 'LONG_TERM', '阿莫西林 0.5g 每日两次', '{"drug_name":"阿莫西林","dose":"0.5g","unit":"片/次","route":"口服","frequency_code":"QD","frequency_name":"每日一次","duration_days":2}', '2026-08-01T10:00:00+08:00', '赵医生', 'ACTIVE', '护士甲', '2026-08-01T11:00:00+08:00')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.medical_orders (id, encounter_id, order_type, order_class, order_content, order_details, start_time, doctor, status, nurse_checked_by, nurse_checked_at)
            VALUES ('${id("order2")}', '${id("enc2")}', 'MEDICATION', 'LONG_TERM', '存量文本医嘱二', '{"drug_name":"阿莫西林","dose":"0.5g","unit":"片/次","route":"口服","frequency_code":"QD","frequency_name":"每日一次","duration_days":2}', '2026-08-01T10:00:00+08:00', '赵医生', 'ACTIVE', '护士甲', '2026-08-01T11:00:00+08:00')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // 025 已绑定目录药品的医嘱（绑定快照与目录一致）
        executeSql(
            """
            INSERT INTO healthcare.medical_orders (id, encounter_id, order_type, order_class, order_content, order_details, start_time, doctor, status, nurse_checked_by, nurse_checked_at)
            VALUES ('${id("order-bound")}', '${id("enc2")}', 'MEDICATION', 'LONG_TERM', '已绑定降压药医嘱', '{"material_id":"${id("mat")}","material_code":"DP-MAT","material_name":"阿莫西林","drug_name":"阿莫西林","dose":"0.5g","unit":"片/次","route":"口服","frequency_code":"QD","frequency_name":"每日一次","duration_days":2}', '2026-08-01T10:00:00+08:00', '赵医生', 'ACTIVE', '护士甲', '2026-08-01T11:00:00+08:00')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )

        // 目录物资：dp-mat（合法药品）、dp-mat2（另一合法药品，用于不一致用例）、
        // dp-mat-inactive（非 ACTIVE 药品）、dp-consumable（耗材）
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'DP-MAT', '阿莫西林', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat2")}', 'DP-MAT2', '布洛芬', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat-inactive")}', 'DP-MAT-INACTIVE', '停用药品', '药品', '片', 0, TRUE, '盒', 24, 'INACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("consumable")}', 'DP-CONSUMABLE', '消毒纱布', '耗材', '包', 0, TRUE, '包', 1, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )

        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot")}', '${id("mat")}', 'DP-LOT-1', CURRENT_DATE + 30)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot2")}', '${id("mat2")}', 'DP-LOT-2', CURRENT_DATE + 30)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot-consumable")}', '${id("consumable")}', 'DP-LOT-3', CURRENT_DATE + 30)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )

        executeSql(
            """
            INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost)
            VALUES ('${id("stock")}', '主库', '${id("mat")}', '${id("lot")}', 100, 0, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost)
            VALUES ('${id("stock2")}', '主库', '${id("mat2")}', '${id("lot2")}', 100, 0, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost)
            VALUES ('${id("stock-consumable")}', '主库', '${id("consumable")}', '${id("lot-consumable")}', 100, 0, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() = cleanupAll(fixturePrefix)

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM healthcare.patients WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE id LIKE '${fixturePrefix}%'") == 0L)
    }

    // ========================================================================
    //  辅助断言：医嘱 order_details 绑定快照 / 副作用计数
    // ========================================================================

    /** 医嘱 `order_details` 的绑定快照是否与期望完全一致（缺键视为 null）。 */
    private fun orderDetailsMatches(orderId: String, expectations: Map<String, String?>): Boolean =
        countRows(
            "SELECT count(*) FROM healthcare.medical_orders WHERE id = '$orderId' AND " +
                expectations.entries.joinToString(" AND ") { (key, value) ->
                    if (value == null) {
                        "(order_details->>'$key' IS NULL)"
                    } else {
                        "(order_details->>'$key' = '$value')"
                    }
                },
        ) == 1L

    /** 指定医嘱关联的发药单数量（用于证明 409/401/503 时无发药单副作用）。 */
    private fun dispenseCountForEncounter(encounterId: String): Long =
        countRows("SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE encounter_id = '$encounterId'")

    /** 指定物资的 OUTBOUND 库存操作明细数量。 */
    private fun outboundDetailCount(materialId: String): Long =
        countRows(
            "SELECT count(*) FROM public.stock_operation_details d " +
                "JOIN public.stock_operations o ON o.id = d.operation_id " +
                "WHERE d.material_id = '$materialId' AND o.operation_type = 'OUTBOUND'",
        )

    private fun createBody(
        orderId: String,
        materialId: String,
        lotId: String? = null,
        quantity: String = "1",
    ): JsonObject =
        JsonObject()
            .put("medical_order_id", orderId)
            .put("warehouse", "主库")
            .put("material_id", materialId)
            .put("dispensed_quantity", quantity)
            .also { if (lotId != null) it.put("lot_id", lotId) }

    /** 读取单个标量（用于比对补绑审计时间戳是否被后续请求覆盖）。 */
    private fun textValue(sql: String): String? =
        java.sql.DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun boundAt(orderId: String): String? =
        textValue("SELECT order_details->>'material_bound_at' FROM healthcare.medical_orders WHERE id = '$orderId'")

    // ========================================================================
    //  011 既有回归：补绑发药闭环扣减库存并生成出库流水
    // ========================================================================

    @Test
    fun `发药闭环扣减库存并生成出库流水`(vertx: Vertx, ctx: VertxTestContext) {
        var dispenseId: String? = null
        val createBody = JsonObject()
            .put("medical_order_id", id("order"))
            .put("warehouse", "主库")
            .put("material_id", id("mat"))
            .put("lot_id", id("lot"))
            .put("dispensed_quantity", "10")
        request(vertx, HttpMethod.POST, "/pharmacy/v1/dispenses/from-medical-order", createBody)
            .compose { (status, dispense) ->
                ctx.verify {
                    assertEquals(201, status, "创建发药单应 201: ${dispense.encode()}")
                    dispenseId = dispense.getString("id")
                    assertNotNull(dispenseId)
                    // 025 补绑与发药单同事务：创建成功时绑定与审计字段已落库
                    check(
                        orderDetailsMatches(
                            id("order"),
                            mapOf(
                                "material_id" to id("mat"),
                                "material_code" to "DP-MAT",
                                "material_name" to "阿莫西林",
                                "material_bound_by" to "integration-tester",
                            ),
                        ),
                    ) { "补绑应写入 material_id/material_code/material_name/material_bound_by" }
                    check(
                        countRows("SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' AND order_details->>'material_bound_at' IS NOT NULL") == 1L,
                    ) { "补绑应写入 material_bound_at" }
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/$dispenseId/review",
                    JsonObject().put("operator", "审方药师"),
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/$dispenseId/start",
                    JsonObject().put("operator", "调配药师"),
                )
            }
            .compose { (status, _) ->
                ctx.verify {
                    assertEquals(200, status)
                    val notNull = countRows("SELECT count(*) FROM pharmacy.pharmacy_dispense_items WHERE dispense_id = '$dispenseId' AND material_id IS NOT NULL AND dispensed_quantity IS NOT NULL")
                    assertEquals(1L, notNull, "发药明细应含 material_id 和 dispensed_quantity")
                }
                request(vertx, HttpMethod.POST, "/pharmacy/v1/dispenses/$dispenseId/confirm", JsonObject())
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(200, status, "confirm 应 200: ${body.encode()}") }
                io.vertx.core.Future.future<Unit> { promise ->
                    val stockQty = countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'")
                    val ops = countRows(
                        "SELECT count(*) FROM public.stock_operation_details d JOIN public.stock_operations o ON d.operation_id = o.id WHERE (o.id LIKE '${fixturePrefix}%' OR o.metadata::text LIKE '%${fixturePrefix}%' OR d.material_id LIKE '${fixturePrefix}%') AND o.operation_type = 'OUTBOUND'",
                    )
                    ctx.verify {
                        assertEquals(90L, stockQty)
                        assertEquals(1L, ops)
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  025 §7.2 断言 3：待接方列表返回绑定字段，历史医嘱为 null
    // ========================================================================

    @Test
    fun `025待接方列表返回医嘱绑定字段且历史文本医嘱为空`(vertx: Vertx, ctx: VertxTestContext) {
        request(vertx, HttpMethod.GET, "/pharmacy/v1/dispenses/medication-orders?encounter_id=${id("enc2")}&limit=50")
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "待接方列表必须 200: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(2, records.size(), "dp-enc2 应有 2 条待接方医嘱: ${body.encode()}")
                    val bound = records.map { it as JsonObject }.first { it.getString("order_id") == id("order-bound") }
                    assertEquals(id("mat"), bound.getString("material_id"), "已绑定医嘱必须返回 material_id")
                    assertEquals("DP-MAT", bound.getString("material_code"), "已绑定医嘱必须返回 material_code")
                    assertEquals("阿莫西林", bound.getString("material_name"), "已绑定医嘱必须返回 material_name")
                    assertEquals("阿莫西林", bound.getString("drug_name"), "drug_name 必须回落到目录名快照")

                    val legacy = records.map { it as JsonObject }.first { it.getString("order_id") == id("order2") }
                    assertNull(legacy.getString("material_id"), "历史自由文本医嘱 material_id 必须为 null")
                    assertNull(legacy.getString("material_code"), "历史自由文本医嘱 material_code 必须为 null")
                    assertNull(legacy.getString("material_name"), "历史自由文本医嘱 material_name 必须为 null")
                    assertEquals("阿莫西林", legacy.getString("drug_name"), "历史医嘱仍暴露自由文本药名")
                }
                io.vertx.core.Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  025 §7.2 断言 4：请求物资与医嘱绑定不一致 → 409 且零副作用
    // ========================================================================

    @Test
    fun `025请求物资与医嘱绑定不一致返回409且无发药单无库存操作医嘱未改动`(vertx: Vertx, ctx: VertxTestContext) {
        val before = orderDetailsMatches(
            id("order-bound"),
            mapOf(
                "material_id" to id("mat"),
                "material_name" to "阿莫西林",
                "material_bound_by" to null,
                "material_bound_at" to null,
            ),
        )
        check(before) { "前置：dp-order-bound 必须绑定 dp-mat 且无补绑审计" }

        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order-bound"), id("mat2"), id("lot2"), "1"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "绑定不一致必须 409: ${body.encode()}")
                    assertEquals(
                        "material_id does not match the prescribed drug",
                        body.getString("error"),
                        "必须是绑定一致性错误，而不是库存不足等其它 409",
                    )
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(0L, dispenseCountForEncounter(id("enc2")), "不得创建发药单")
                        assertEquals(0L, outboundDetailCount(id("mat2")), "不得产生库存出库明细")
                        assertEquals(0L, outboundDetailCount(id("mat")), "不得误用医嘱绑定的物资出库")
                        check(
                            orderDetailsMatches(
                                id("order-bound"),
                                mapOf(
                                    "material_id" to id("mat"),
                                    "material_code" to "DP-MAT",
                                    "material_name" to "阿莫西林",
                                    "material_bound_by" to null,
                                    "material_bound_at" to null,
                                ),
                            ),
                        ) { "医嘱 order_details 必须保持原样（含未写入补绑审计）" }
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  025 §7.2 断言 5：存量无绑定医嘱一次性补绑成功并完成扣减
    // ========================================================================

    @Test
    fun `025存量无绑定医嘱补绑成功写审计并扣减库存回收库存明细ID`(vertx: Vertx, ctx: VertxTestContext) {
        var dispenseId: String? = null
        check(
            orderDetailsMatches(
                id("order"),
                mapOf("material_id" to null, "material_bound_by" to null, "drug_name" to "阿莫西林"),
            ),
        ) { "前置：dp-order 必须是未绑定的存量自由文本医嘱" }

        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "10"),
        )
            .compose { (status, dispense) ->
                ctx.verify {
                    assertEquals(201, status, "存量医嘱补绑发药应 201: ${dispense.encode()}")
                    dispenseId = dispense.getString("id")
                    assertNotNull(dispenseId)
                    // 同事务：创建发药单时绑定与审计已落库
                    check(
                        orderDetailsMatches(
                            id("order"),
                            mapOf(
                                "material_id" to id("mat"),
                                "material_code" to "DP-MAT",
                                "material_name" to "阿莫西林",
                                "material_bound_by" to "integration-tester",
                                "drug_name" to "阿莫西林",
                            ),
                        ),
                    ) { "补绑必须写入 material_id/material_code/material_name/material_bound_by" }
                    check(
                        countRows("SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' AND order_details->>'material_bound_at' IS NOT NULL") == 1L,
                    ) { "补绑必须写入 material_bound_at（与 material_bound_by 成对）" }
                    check(
                        countRows("SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' AND order_details->>'material_bound_at' = 'null'") == 0L,
                    ) { "material_bound_at 不得写成字符串 null" }
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/$dispenseId/review",
                    JsonObject().put("operator", "审方药师"),
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/$dispenseId/start",
                    JsonObject().put("operator", "调配药师"),
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                request(vertx, HttpMethod.POST, "/pharmacy/v1/dispenses/$dispenseId/confirm", JsonObject())
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(200, status, "confirm 应 200: ${body.encode()}") }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(90L, countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'"), "库存必须由 100 扣减为 90")
                        assertEquals(1L, outboundDetailCount(id("mat")), "必须恰有 1 条 OUTBOUND 出库明细")
                        assertEquals(
                            1L,
                            countRows("SELECT count(*) FROM pharmacy.pharmacy_dispense_items WHERE dispense_id = '$dispenseId' AND stock_operation_detail_id IS NOT NULL"),
                            "发药明细必须回写 stock_operation_detail_id",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  025 §7.2 断言 6：补绑路径失败 → 409/404 且医嘱原样
    // ========================================================================

    @Test
    fun `025补绑请求耗材非ACTIVE与不存在物资分别409与404且医嘱原样`(vertx: Vertx, ctx: VertxTestContext) {
        check(
            orderDetailsMatches(
                id("order2"),
                mapOf("material_id" to null, "material_bound_by" to null, "drug_name" to "阿莫西林"),
            ),
        ) { "前置：dp-order2 必须是未绑定的存量自由文本医嘱" }

        // 1. 耗材 → 409（不得因为 category 判定缺失而误绑）
        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order2"), id("consumable"), id("lot-consumable"), "1"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "补绑到耗材必须 409: ${body.encode()}")
                    assertEquals("material is not a drug: ${id("consumable")}", body.getString("error"))
                }
                // 2. 非 ACTIVE 药品 → 409
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/from-medical-order",
                    createBody(id("order2"), id("mat-inactive"), null, "1"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "补绑到非 ACTIVE 药品必须 409: ${body.encode()}")
                    assertEquals("material is not active: ${id("mat-inactive")}", body.getString("error"))
                }
                // 3. 物资不存在 → 404
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/from-medical-order",
                    createBody(id("order2"), id("ghost"), null, "1"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(404, status, "补绑到不存在物资必须 404: ${body.encode()}")
                    assertEquals("material not found: ${id("ghost")}", body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(0L, dispenseCountForEncounter(id("enc2")), "三次失败均不得创建发药单")
                        assertEquals(0L, outboundDetailCount(id("mat")), "不得产生任何出库明细")
                        assertEquals(0L, outboundDetailCount(id("consumable")), "耗材不得出库")
                        check(
                            orderDetailsMatches(
                                id("order2"),
                                mapOf(
                                    "material_id" to null,
                                    "material_code" to null,
                                    "material_name" to null,
                                    "material_bound_by" to null,
                                    "material_bound_at" to null,
                                    "drug_name" to "阿莫西林",
                                ),
                            ),
                        ) { "医嘱 order_details 必须保持原样（不得写 material_id 或补绑审计）" }
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  025 §4.5.2 / §4.5.4：端口缺失与身份缺失必须 fail-closed
    // ========================================================================

    @Test
    fun `025补绑路径缺药品目录端口返回503且无副作用`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy-noport/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "1"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(503, status, "端口未注入必须 503 而不是静默跳过补绑: ${body.encode()}")
                    assertEquals("drug catalog port is not configured", body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(0L, dispenseCountForEncounter(id("enc")), "不得创建发药单")
                        assertEquals(0L, outboundDetailCount(id("mat")), "不得产生出库明细")
                        check(
                            orderDetailsMatches(
                                id("order"),
                                mapOf("material_id" to null, "material_bound_by" to null),
                            ),
                        ) { "端口缺失时不得绑定，也不得降级为只校验不补绑后成功" }
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    @Test
    fun `025补绑路径缺可信身份返回401不写null审计而已绑定医嘱仍可发药`(vertx: Vertx, ctx: VertxTestContext) {
        // 1. 未绑定医嘱 + 无身份 → 401（不得写 material_bound_by: null）
        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy-noauth/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "1"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(401, status, "补绑路径缺身份必须 401: ${body.encode()}")
                    assertEquals("authentication required to bind drug material", body.getString("error"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(0L, dispenseCountForEncounter(id("enc")), "缺身份不得创建发药单")
                        assertEquals(0L, outboundDetailCount(id("mat")), "缺身份不得出库")
                        check(
                            orderDetailsMatches(
                                id("order"),
                                mapOf("material_id" to null, "material_bound_by" to null, "material_bound_at" to null),
                            ),
                        ) { "缺身份不得写入任何绑定或审计字段" }
                        check(
                            countRows("SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' AND order_details ? 'material_bound_by'") == 0L,
                        ) { "不得写入 material_bound_by 键（哪怕是 JSON null），否则产生无法追责的审计记录" }
                    }
                    promise.complete()
                }
            }
            // 2. 已绑定医嘱 + 无身份 → 仍应 201：身份只在补绑路径上要求
            .compose {
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy-noauth/v1/dispenses/from-medical-order",
                    createBody(id("order-bound"), id("mat"), id("lot"), "1"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "已绑定医嘱不需要补绑身份，必须照常发药: ${body.encode()}")
                    assertEquals(id("mat"), body.getJsonArray("items").getJsonObject(0).getString("material_id"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(1L, dispenseCountForEncounter(id("enc2")), "已绑定路径应创建 1 张发药单")
                        check(
                            orderDetailsMatches(
                                id("order-bound"),
                                mapOf(
                                    "material_id" to id("mat"),
                                    "material_bound_by" to null,
                                    "material_bound_at" to null,
                                ),
                            ),
                        ) { "已绑定路径不得写入补绑审计" }
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  025 对抗性用例（评审要求补充）
    // ========================================================================

    /**
     * 已发药（补绑）的存量文本医嘱再次发药：
     * 同一医嘱、同一 `material_id` 必须 409（重复接方），且**不得二次补绑**——
     * 已有发药单时补绑不会重跑，`material_bound_by`/`material_bound_at` 必须保持首次值，
     * 库存与出库明细也不得因为重试而变化。
     */
    @Test
    fun `025已发药存量医嘱再次发药返回409且补绑审计不被覆盖`(vertx: Vertx, ctx: VertxTestContext) {
        var firstDispenseId: String? = null
        var boundAtFirst: String? = null
        check(orderDetailsMatches(id("order"), mapOf("material_id" to null, "material_bound_by" to null))) {
            "前置：dp-order 必须是未绑定的存量自由文本医嘱"
        }

        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "10"),
        )
            .compose { (status, dispense) ->
                ctx.verify {
                    assertEquals(201, status, "首次补绑发药应 201: ${dispense.encode()}")
                    firstDispenseId = dispense.getString("id")
                    assertNotNull(firstDispenseId)
                    boundAtFirst = boundAt(id("order"))
                    assertNotNull(boundAtFirst, "首次补绑必须写入 material_bound_at")
                }
                // 第二次：同一医嘱同一物资 → 必须 409 且不得二次补绑 / 二次扣减
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/from-medical-order",
                    createBody(id("order"), id("mat"), id("lot"), "10"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "已有未取消发药单必须 409: ${body.encode()}")
                    assertNotNull(body.getString("error"), "409 必须带错误文案")
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(1L, dispenseCountForEncounter(id("enc")), "不得创建第二张发药单")
                        assertEquals(
                            100L,
                            countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'"),
                            "未确认发药前库存必须保持 100（重试不得重复出库）",
                        )
                        assertEquals(0L, outboundDetailCount(id("mat")), "创建阶段不得产生出库明细")
                        assertEquals(boundAtFirst, boundAt(id("order")), "重试不得覆盖 material_bound_at")
                        check(
                            orderDetailsMatches(
                                id("order"),
                                mapOf(
                                    "material_id" to id("mat"),
                                    "material_code" to "DP-MAT",
                                    "material_name" to "阿莫西林",
                                    "material_bound_by" to "integration-tester",
                                ),
                            ),
                        ) { "重试不得改写绑定快照与补绑审计" }
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    /**
     * 已补绑医嘱不得改绑到别的药品：补绑后取消发药单（医嘱绑定保留），
     * 再以另一个目录药品创建发药单 → 409，原绑定与补绑审计原样保留。
     */
    @Test
    fun `025已补绑医嘱改绑其他药品返回409且原绑定与审计不变`(vertx: Vertx, ctx: VertxTestContext) {
        var dispenseId: String? = null
        request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "1"),
        )
            .compose { (status, dispense) ->
                ctx.verify {
                    assertEquals(201, status, "补绑发药应 201: ${dispense.encode()}")
                    dispenseId = dispense.getString("id")
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/$dispenseId/cancel",
                    JsonObject().put("operator", "取消药师").put("remark", "改绑用例"),
                )
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(200, status, "取消发药单应 200: ${body.encode()}") }
                // 绑定仍在，但请求换成另一个药品 → 必须 409
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/from-medical-order",
                    createBody(id("order"), id("mat2"), id("lot2"), "1"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "已补绑医嘱改绑其他药品必须 409: ${body.encode()}")
                    assertEquals(
                        "material_id does not match the prescribed drug",
                        body.getString("error"),
                        "必须是绑定一致性错误",
                    )
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        check(
                            orderDetailsMatches(
                                id("order"),
                                mapOf(
                                    "material_id" to id("mat"),
                                    "material_code" to "DP-MAT",
                                    "material_name" to "阿莫西林",
                                    "material_bound_by" to "integration-tester",
                                ),
                            ),
                        ) { "原绑定与补绑审计必须原样保留" }
                        assertEquals(0L, outboundDetailCount(id("mat2")), "不得对替换药品产生任何库存操作")
                        assertEquals(
                            100L,
                            countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock2")}'"),
                            "替换药品库存必须不变",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    /**
     * 并发双发药同一存量文本医嘱：两个请求同时创建发药单，必须**恰好一成一败**，
     * 且补绑只提交一次、不得重复扣减或产生第二条发药单。
     *
     * 串行化依赖 `lockMedicationOrder` 的 `FOR UPDATE OF medical_orders`：后到事务必须先
     * 看到先到事务提交的绑定与发药单，因此只能走 409 重复接方分支。
     */
    @Test
    fun `025存量文本医嘱并发双发药只成功一次且补绑只提交一次`(vertx: Vertx, ctx: VertxTestContext) {
        val first = request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "10"),
        )
        val second = request(
            vertx,
            HttpMethod.POST,
            "/pharmacy/v1/dispenses/from-medical-order",
            createBody(id("order"), id("mat"), id("lot"), "10"),
        )
        first.compose { f -> second.map { s -> Pair(f, s) } }
            .compose { (f, s) ->
                ctx.verify {
                    assertEquals(
                        listOf(201, 409),
                        listOf(f.first, s.first).sorted(),
                        "并发双发药必须恰好一成一败；实际=${listOf(f.first, s.first)}，body=${f.second.encode()} / ${s.second.encode()}",
                    )
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(1L, dispenseCountForEncounter(id("enc")), "只允许落一张发药单")
                        assertEquals(
                            100L,
                            countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'"),
                            "未确认发药前不得扣减库存",
                        )
                        assertEquals(0L, outboundDetailCount(id("mat")), "不得产生重复出库明细")
                        assertEquals(
                            1L,
                            countRows(
                                "SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' " +
                                    "AND order_details->>'material_id' = '${id("mat")}' " +
                                    "AND order_details->>'material_bound_by' = 'integration-tester' " +
                                    "AND order_details ? 'material_bound_at'",
                            ),
                            "补绑必须恰好提交一次且审计成对",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
