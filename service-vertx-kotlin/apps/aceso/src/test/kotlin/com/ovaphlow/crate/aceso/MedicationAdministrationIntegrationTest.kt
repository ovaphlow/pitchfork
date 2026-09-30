package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.math.BigDecimal

/**
 * 030/031 给药闭环（MAR）PostgreSQL 集成测试（仅隔离 `aceso_test`）。
 *
 * 与生产 `apps/aceso/Main.kt` 的装配等价：全局身份门注入 `userId`
 * （`AcesoIntegrationTestSupport.fakeAuth`），路由挂载 `/nursing/v1/...` 与
 * `/pharmacy/v1/...`，药房出库端口走同一连接适配器。
 *
 * 031 W1（必须复现，禁止猜测式改查询）：
 *   构造「老人 → 在住养老入住 → 用药医嘱（ACTIVE 且已护士核对）→ 护理任务/执行 →
 *   从医嘱发药（from-medical-order）并 confirm 到 DISPENSED（扣库存）」，然后：
 *   1) `GET /nursing/v1/executions/{id}/administration/sources` 必须返回 1 条
 *      `remaining_quantity > 0` 的来源；
 *   2) `POST .../administration`（result=已服，数量≤剩余）必须返回 201 且执行 → COMPLETED。
 *   若第 1 步在真实库上失败，即为 W1 的真因（SQL/关联/状态/路由），而非前端静默吞异常。
 *
 * 031 W2（已确认根因，回归锁定）：
 *   拒服（带 reason）必须返回 201 且执行 → SKIPPED，不得因
 *   `UPDATE ... AS e SET e.status` 的 PostgreSQL 非法限定而退化为 500。
 */
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
class MedicationAdministrationIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "ma-"
    override val serverPort = 18517

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient")}', '给药闭环测试长者', '男', '1940-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc")}', '${id("patient")}', 'ELDERLY_CARE', 'MA-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE')
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
        // 已绑定目录药品的用药医嘱（ACTIVE + 已护士核对），药房 from-medical-order 直接发药
        executeSql(
            """
            INSERT INTO healthcare.medical_orders (id, encounter_id, order_type, order_class, order_content, order_details, start_time, doctor, status, nurse_checked_by, nurse_checked_at)
            VALUES ('${id("order")}', '${id("enc")}', 'MEDICATION', 'LONG_TERM', '阿司匹林 100mg 每日一次', '{"material_id":"${id("mat")}","material_code":"MA-MAT","material_name":"阿司匹林","drug_name":"阿司匹林","dose":"100mg","unit":"片","route":"口服","frequency_code":"QD","frequency_name":"每日一次","duration_days":7}', '2026-08-01T10:00:00+08:00', '赵医生', 'ACTIVE', '护士甲', '2026-08-01T11:00:00+08:00')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'MA-MAT', '阿司匹林', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot")}', '${id("mat")}', 'MA-LOT-1', CURRENT_DATE + 30)
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
        // W1：已服路径的执行（需先发药）
        executeSql(
            """
            INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, order_item_id, task_type, description, status)
            VALUES ('${id("task")}', '${id("period")}', '${id("enc")}', '${id("order")}', 'MEDICATION', '阿司匹林 100mg 每日一次', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
            VALUES ('${id("exec")}', '${id("task")}', '2026-08-01T09:00:00+08:00', 'IN_PROGRESS')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        // W2：拒服路径的执行（不消耗发药数量）
        executeSql(
            """
            INSERT INTO nursing.nursing_tasks (id, period_id, encounter_id, order_item_id, task_type, description, status)
            VALUES ('${id("task-skip")}', '${id("period")}', '${id("enc")}', '${id("order")}', 'MEDICATION', '阿司匹林 100mg 每日一次（拒服用例）', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
            VALUES ('${id("exec-skip")}', '${id("task-skip")}', '2026-08-01T20:00:00+08:00', 'PENDING')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() {
        // medication_administrations 引用 nursing_task_executions，必须在后者被
        // cleanupAll 删除之前先清掉（表内 id 为服务端 ULID，只能按 task_execution_id 前缀定位）。
        executeSql(
            "DELETE FROM nursing.medication_administrations WHERE task_execution_id LIKE '$fixturePrefix%'",
        )
        cleanupAll(fixturePrefix)
    }

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM healthcare.patients WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.medication_administrations WHERE task_execution_id LIKE '$fixturePrefix%'") == 0L)
    }

    private fun createDispenseBody(): JsonObject =
        JsonObject()
            .put("medical_order_id", id("order"))
            .put("warehouse", "主库")
            .put("material_id", id("mat"))
            .put("lot_id", id("lot"))
            .put("dispensed_quantity", "10")

    // ========================================================================
    //  W1：真实库复现 —— 发药来源可查询 + 已服提交闭环
    // ========================================================================

    @Test
    fun `W1发药来源返回未给完明细且已服提交后执行转COMPLETED`(vertx: Vertx, ctx: VertxTestContext) {
        var dispenseId: String? = null
        var dispenseItemId: String? = null
        request(vertx, HttpMethod.POST, "/pharmacy/v1/dispenses/from-medical-order", createDispenseBody())
            .compose { (status, dispense) ->
                ctx.verify {
                    assertEquals(201, status, "创建发药单应 201: ${dispense.encode()}")
                    dispenseId = dispense.getString("id")
                    assertNotNull(dispenseId)
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
                        "confirm 后库存必须由 100 扣减为 90",
                    )
                }
                request(vertx, HttpMethod.GET, "/nursing/v1/executions/${id("exec")}/administration/sources")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "sources 应 200 而非 500 或空: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(1, records.size(), "应恰有 1 条已发药未给完来源: ${body.encode()}")
                    val source = records.getJsonObject(0)
                    val remaining = BigDecimal(source.getString("remaining_quantity"))
                    assertTrue(remaining > BigDecimal.ZERO, "remaining_quantity 应 > 0: ${source.encode()}")
                    assertEquals("10", source.getString("dispensed_quantity"), "实发数量应为 10: ${source.encode()}")
                    dispenseItemId = source.getString("id")
                    assertNotNull(dispenseItemId, "来源必须返回发药明细 id")
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/nursing/v1/executions/${id("exec")}/administration",
                    JsonObject()
                        .put("result", "已服")
                        .put("dispense_item_id", dispenseItemId)
                        .put("administered_quantity", "2"),
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "记录给药应 201: ${body.encode()}")
                    assertEquals("已服", body.getString("result"))
                    assertEquals("2", body.getString("administered_quantity"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(
                            1L,
                            countRows("SELECT count(*) FROM nursing.nursing_task_executions WHERE id = '${id("exec")}' AND status = 'COMPLETED'"),
                            "给药后执行必须联动为 COMPLETED",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  W2：真实库回归 —— 拒服不再 500，执行转 SKIPPED
    // ========================================================================

    @Test
    fun `W2拒服提交返回201且执行转SKIPPED`(vertx: Vertx, ctx: VertxTestContext) {
        request(
            vertx,
            HttpMethod.POST,
            "/nursing/v1/executions/${id("exec-skip")}/administration",
            JsonObject().put("result", "拒服").put("reason", "长者拒绝服药"),
        )
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "拒服应 201 而非 500: ${body.encode()}")
                    assertEquals("拒服", body.getString("result"))
                    assertEquals("长者拒绝服药", body.getString("reason"))
                }
                io.vertx.core.Future.future<Unit> { promise ->
                    ctx.verify {
                        assertEquals(
                            1L,
                            countRows("SELECT count(*) FROM nursing.nursing_task_executions WHERE id = '${id("exec-skip")}' AND status = 'SKIPPED'"),
                            "拒服后执行必须联动为 SKIPPED",
                        )
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
