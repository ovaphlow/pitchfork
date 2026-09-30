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

/**
 * 033 E：历史文本医嘱补绑路径的给药来源真库回归（仅隔离 `aceso_test`）。
 *
 * 与生产 `apps/aceso/Main.kt` 装配等价：全局身份门注入 `userId`
 * （`AcesoIntegrationTestSupport.fakeAuth`），`/healthcare`、`/nursing`、`/pharmacy`
 * 路由与药房库存出库端口均走同一连接适配器。
 *
 * 回答的问题（用户怀疑「补绑链路上任务挂在旧文本医嘱项、发药明细挂在补绑后的目录项，
 * 因此 sources 查不到来源」）：
 *   `nursing_tasks.order_item_id` 指向历史文本医嘱（未绑定目录物资），
 *   药房 `from-medical-order` 提交 `material_id` 完成一次性补绑并确认到 `DISPENSED` 后，
 *   `GET /nursing/v1/executions/{id}/administration/sources` 必须返回该医嘱的
 *   `≥1` 条 `remaining_quantity > 0` 的来源（`dose_quantity` 在该历史路径上为 null），
 *   且记「已服」返回 201、执行联动为 `COMPLETED`。
 *
 * 断言不得为了让用例变绿而放宽：来源为空或非 200 即为真实缺陷证据。
 */
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
class TextOrderRebindAdministrationIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "tr-"
    override val serverPort = 18519

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient")}', '补绑来源测试长者', '男', '1940-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc")}', '${id("patient")}', 'ELDERLY_CARE', 'TR-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE')
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
        // 025 之前的历史文本医嘱：只有 drug_name（自由文本），没有 material_id，
        // 也没有 032 才引入的 dose_quantity；尚未护士核对（本用例走真实核对接口）。
        executeSql(
            """
            INSERT INTO healthcare.medical_orders (id, encounter_id, order_type, order_class, order_content, order_details, start_time, doctor, status)
            VALUES ('${id("order")}', '${id("enc")}', 'MEDICATION', 'LONG_TERM', '阿莫西林 0.5g 每日一次', '{"drug_name":"阿莫西林","dose":"0.5g","unit":"片","route":"口服","frequency_code":"QD","frequency_name":"每日一次","duration_days":3}', '2026-08-01T00:00:00+08:00', '赵医生', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // 历史文本医嘱当年开嘱时派生的护理任务：order_item_id 挂在「旧文本医嘱」上，
        // 而不是任何目录物资项 —— 这正是用户怀疑的来源断链位置。
        executeSql(
            """
            INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, order_item_id, task_type, description, frequency_code, frequency_name, start_date, end_date, status)
            VALUES ('${id("task")}', '${id("period")}', '${id("enc")}', '${id("order")}', 'MEDICATION', '阿莫西林 0.5g 每日一次', 'QD', '每日一次', '2026-08-01', '2026-08-03', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // 补绑目标：目录药品 + 批次 + 库存
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'TR-MAT', '阿莫西林', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot")}', '${id("mat")}', 'TR-LOT-1', CURRENT_DATE + 30)
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
    }

    /**
     * 本用例的执行记录由真实生成接口创建（主键是服务端 ULID，不带 fixture 前缀），
     * 所以给药记录只能经「任务/执行 → 本用例 fixture」回溯定位；medication_administrations
     * 的外键指向 nursing_task_executions，必须先于 cleanupAll 删除。
     */
    private val fixtureAdministrationFilter: String =
        "medical_order_id LIKE '$fixturePrefix%' OR task_execution_id IN (" +
            "SELECT e.id FROM nursing.nursing_task_executions e " +
            "JOIN nursing.nursing_tasks t ON t.id = e.task_id " +
            "WHERE t.id LIKE '$fixturePrefix%' OR t.period_id LIKE '$fixturePrefix%' " +
            "OR t.encounter_id LIKE '$fixturePrefix%' OR t.order_item_id LIKE '$fixturePrefix%')"

    override fun cleanupFixtures() {
        executeSql("DELETE FROM nursing.medication_administrations WHERE $fixtureAdministrationFilter")
        cleanupAll(fixturePrefix)
    }

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM healthcare.patients WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM healthcare.medical_orders WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.nursing_tasks WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.medication_administrations WHERE $fixtureAdministrationFilter") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE encounter_id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '$fixturePrefix%'") == 0L)
    }

    /** 读取单个标量（用于取生成接口未回传的执行 id、比对绑定审计）。 */
    private fun textValue(sql: String): String? =
        java.sql.DriverManager.getConnection(jdbcUrl(), user, password).use { conn ->
            conn.createStatement().executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    @Test
    fun `历史文本医嘱补绑发药后给药来源非空且已服提交转COMPLETED`(vertx: Vertx, ctx: VertxTestContext) {
        var executionId: String? = null
        var dispenseId: String? = null
        var dispenseItemId: String? = null

        // 前置：未绑定目录物资、无每次数量的历史文本医嘱，且护理任务挂在它上面
        check(
            countRows(
                "SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' " +
                    "AND order_details->>'drug_name' = '阿莫西林' " +
                    "AND NOT (order_details ? 'material_id') " +
                    "AND NOT (order_details ? 'dose_quantity') " +
                    "AND nurse_checked_at IS NULL",
            ) == 1L,
        ) { "前置：tr-order 必须是未绑定目录物资、无每次数量、未核对的历史文本医嘱" }
        check(
            countRows(
                "SELECT count(*) FROM nursing.nursing_tasks WHERE id = '${id("task")}' " +
                    "AND order_item_id = '${id("order")}' AND task_type = 'MEDICATION' AND status = 'ACTIVE'",
            ) == 1L,
        ) { "前置：护理任务必须挂在历史文本医嘱项（order_item_id = tr-order）" }

        // 1. 护士核对（真实接口；核对人由认证门注入）
        request(vertx, HttpMethod.PATCH, "/healthcare/v1/orders/${id("order")}/nurse-check", JsonObject())
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "历史文本医嘱核对应 200: ${body.encode()}")
                    check(
                        countRows(
                            "SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' " +
                                "AND nurse_checked_by IS NOT NULL AND nurse_checked_at IS NOT NULL " +
                                "AND NOT (order_details ? 'material_id')",
                        ) == 1L,
                    ) { "护士核对只写核对审计，不得顺手补绑目录物资" }
                }
                // 2. 生成护理执行（真实批量生成接口，按任务频次 QD 计划 09:00）
                request(
                    vertx,
                    HttpMethod.POST,
                    "/nursing/v1/executions/generate",
                    JsonObject()
                        .put("date_from", "2026-08-01")
                        .put("date_to", "2026-08-01")
                        .put("period_id", id("period")),
                )
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(200, status, "生成护理执行应 200: ${body.encode()}") }
                Future.future<Unit> { promise ->
                    val generatedId = textValue(
                        "SELECT id FROM nursing.nursing_task_executions WHERE task_id = '${id("task")}' ORDER BY id LIMIT 1",
                    )
                    ctx.verify {
                        assertNotNull(generatedId, "MEDICATION 任务必须生成执行记录: ${body.encode()}")
                        executionId = generatedId
                    }
                    promise.complete()
                }
            }
            // 3. 药房待接方列表：历史文本医嘱可见，material_id 仍为 null（补绑入口）
            .compose {
                request(
                    vertx,
                    HttpMethod.GET,
                    "/pharmacy/v1/dispenses/medication-orders?encounter_id=${id("enc")}&limit=50",
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "待接方列表必须 200: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(1, records.size(), "tr-enc 应恰有 1 条待接方医嘱: ${body.encode()}")
                    val row = records.getJsonObject(0)
                    assertEquals(id("order"), row.getString("order_id"))
                    assertNull(row.getString("material_id"), "历史文本医嘱补绑前 material_id 必须为 null")
                    assertEquals("阿莫西林", row.getString("drug_name"), "历史文本医嘱暴露自由文本药名")
                }
                // 4. 发药前：没有 DISPENSED 明细 → 来源必须为空（证明第 7 步的非空来自本次发药）
                request(vertx, HttpMethod.GET, "/nursing/v1/executions/$executionId/administration/sources")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "发药前 sources 应 200: ${body.encode()}")
                    assertEquals(0, body.getJsonArray("records").size(), "发药前不得有给药来源: ${body.encode()}")
                }
                // 5. 补绑 + 发药单：提交目录 material_id 完成一次性补绑
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/from-medical-order",
                    JsonObject()
                        .put("medical_order_id", id("order"))
                        .put("warehouse", "主库")
                        .put("material_id", id("mat"))
                        .put("lot_id", id("lot"))
                        .put("dispensed_quantity", "10"),
                )
            }
            .compose { (status, dispense) ->
                ctx.verify {
                    assertEquals(201, status, "历史文本医嘱补绑发药应 201: ${dispense.encode()}")
                    dispenseId = dispense.getString("id")
                    assertNotNull(dispenseId)
                    check(
                        countRows(
                            "SELECT count(*) FROM healthcare.medical_orders WHERE id = '${id("order")}' " +
                                "AND order_details->>'material_id' = '${id("mat")}' " +
                                "AND order_details->>'material_code' = 'TR-MAT' " +
                                "AND order_details->>'material_name' = '阿莫西林' " +
                                "AND order_details->>'material_bound_by' = 'integration-tester' " +
                                "AND order_details->>'material_bound_at' IS NOT NULL " +
                                "AND order_details->>'drug_name' = '阿莫西林'",
                        ) == 1L,
                    ) { "补绑必须写入 material_id/material_code/material_name 与成对审计，且保留 drug_name" }
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
                ctx.verify {
                    assertEquals(200, status, "confirm 应 200: ${body.encode()}")
                    assertEquals(
                        90L,
                        countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'"),
                        "确认发药后库存必须由 100 扣减为 90",
                    )
                }
                // 6. 关联键核对：任务与发药明细必须落在同一医嘱项上（否则 sources 必然为空）
                Future.future<Unit> { promise ->
                    val sameOrderItem = countRows(
                        "SELECT count(*) FROM nursing.nursing_tasks t " +
                            "JOIN pharmacy.pharmacy_dispense_items di ON di.order_item_id = t.order_item_id " +
                            "WHERE t.id = '${id("task")}' AND di.dispense_id = '$dispenseId'",
                    )
                    ctx.verify {
                        assertEquals(
                            1L,
                            sameOrderItem,
                            "发药明细 order_item_id 必须与护理任务 order_item_id 同一（均为历史文本医嘱 id）",
                        )
                    }
                    promise.complete()
                }
            }
            // 7. 给药来源：必须返回本次已发药未给完的明细
            .compose {
                request(vertx, HttpMethod.GET, "/nursing/v1/executions/$executionId/administration/sources")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "补绑发药后 sources 必须 200 而不是空或报错: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(1, records.size(), "应恰有 1 条已发药未给完来源: ${body.encode()}")
                    val source = records.getJsonObject(0)
                    dispenseItemId = source.getString("id")
                    assertNotNull(dispenseItemId, "来源必须返回发药明细 id")
                    assertEquals(dispenseId, source.getString("dispense_id"), "来源必须属于本次发药单")
                    assertEquals(id("mat"), source.getString("material_id"), "来源必须指向补绑后的目录药品")
                    assertEquals("阿莫西林", source.getString("material_name"))
                    assertEquals("片", source.getString("unit"), "单位来自医嘱 order_details.unit")
                    assertEquals("10", source.getString("dispensed_quantity"), "实发数量应为 10")
                    assertEquals(
                        "10",
                        source.getString("remaining_quantity"),
                        "未给药时剩余数量应等于实发数量",
                    )
                    val remaining = BigDecimal(source.getString("remaining_quantity"))
                    assertTrue(remaining > BigDecimal.ZERO, "remaining_quantity 必须 > 0: ${source.encode()}")
                    assertNull(
                        source.getString("dose_quantity"),
                        "历史文本医嘱没有 dose_quantity，该来源行不得凭空造出每次数量: ${source.encode()}",
                    )
                }
                // 8. 记「已服」：引用该来源明细
                request(
                    vertx,
                    HttpMethod.POST,
                    "/nursing/v1/executions/$executionId/administration",
                    JsonObject()
                        .put("result", "已服")
                        .put("dispense_item_id", dispenseItemId)
                        .put("administered_quantity", "2"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "记「已服」应 201: ${body.encode()}")
                    assertEquals("已服", body.getString("result"))
                    assertEquals("2", body.getString("administered_quantity"))
                    assertEquals(id("order"), body.getString("medical_order_id"), "给药记录必须挂历史文本医嘱")
                }
                Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(
                            1L,
                            countRows(
                                "SELECT count(*) FROM nursing.nursing_task_executions WHERE id = '$executionId' AND status = 'COMPLETED'",
                            ),
                            "给药后执行必须联动为 COMPLETED",
                        )
                        assertEquals(
                            1L,
                            countRows(
                                "SELECT count(*) FROM nursing.medication_administrations WHERE task_execution_id = '$executionId' " +
                                    "AND result = '已服' AND administered_by = 'integration-tester'",
                            ),
                            "必须恰好落一条给药记录并记录给药人",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
