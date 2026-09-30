package com.ovaphlow.crate.aceso

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * 032 P4 每次数量（基础单位）PostgreSQL 集成测试（仅隔离 `aceso_test`）。
 *
 * 覆盖「服务端推导本疗程应发总量」的真实链路（与生产 `apps/aceso/Main.kt` 装配等价）：
 *   开嘱（MEDICATION，`dose_quantity=1`、`frequency_code=QD`、`duration_days=3`，走真实校验与落库）
 *   → 护士核对
 *   → 药房待接方行 `dose_quantity="1"` / `daily_dose_count=1` / `duration_days=3` /
 *     `prescribed_total_quantity="3"`
 *   → 按该总量从医嘱发药 `dispensed_quantity=3` 并 confirm（库存 100 → 97）
 *   → `GET /nursing/v1/executions/{id}/administration/sources` 的来源行 `dose_quantity="1"`。
 *
 * 用药安全口径：推不出可信总量时服务端返回 null、不预填，本测试同时锁定「推得出时必须等于
 * 每次数量 × 每日次数 × 疗程天数」这一侧。
 */
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
class DoseQuantityIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "dq-"
    override val serverPort = 18518

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient")}', '每次数量测试长者', '男', '1940-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc")}', '${id("patient")}', 'ELDERLY_CARE', 'DQ-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE')
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
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'DQ-MAT', '阿司匹林', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot")}', '${id("mat")}', 'DQ-LOT-1', CURRENT_DATE + 30)
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

    override fun cleanupFixtures() = cleanupAll(fixturePrefix)

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM healthcare.patients WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_dispenses WHERE encounter_id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM healthcare.medical_orders WHERE encounter_id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.nursing_task_executions WHERE id LIKE '$fixturePrefix%'") == 0L)
        check(countRows("SELECT count(*) FROM nursing.nursing_tasks WHERE encounter_id LIKE '$fixturePrefix%'") == 0L)
    }

    private fun orderBody(): JsonObject =
        JsonObject()
            .put("order_type", "MEDICATION")
            .put("order_class", "LONG_TERM")
            .put("order_content", "阿司匹林 100mg 每日一次 共 3 天")
            .put("doctor", "赵医生")
            .put("start_time", "2026-08-01T09:00:00+08:00")
            .put(
                "order_details",
                JsonObject()
                    .put("material_id", id("mat"))
                    .put("drug_name", "阿司匹林")
                    .put("dose", "100mg")
                    .put("unit", "片")
                    .put("route", "口服")
                    .put("frequency_code", "QD")
                    .put("frequency_name", "每日一次")
                    .put("duration_days", 3)
                    .put("dose_quantity", "1"),
            )

    @Test
    fun `032P4开嘱带每次数量时药房预填本疗程总量且给药来源带出每次数量`(vertx: Vertx, ctx: VertxTestContext) {
        var orderId: String? = null
        var dispenseId: String? = null

        request(vertx, HttpMethod.POST, "/healthcare/v1/encounters/${id("enc")}/orders", orderBody())
            .compose { (status, order) ->
                ctx.verify {
                    assertEquals(201, status, "开嘱应 201: ${order.encode()}")
                    orderId = order.getString("id")
                    assertNotNull(orderId, "开嘱必须返回医嘱 id")
                    assertEquals(
                        "1",
                        order.getJsonObject("order_details").getString("dose_quantity"),
                        "每次数量必须原样落在 order_details: ${order.encode()}",
                    )
                }
                request(vertx, HttpMethod.PATCH, "/healthcare/v1/orders/$orderId/nurse-check", JsonObject())
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(200, status, "护士核对应 200: ${body.encode()}") }
                request(vertx, HttpMethod.GET, "/pharmacy/v1/dispenses/medication-orders?encounter_id=${id("enc")}&limit=50")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "待接方列表必须 200: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(1, records.size(), "dq-enc 应恰有 1 条待接方医嘱: ${body.encode()}")
                    val row = records.getJsonObject(0)
                    assertEquals(orderId, row.getString("order_id"))
                    assertEquals("1", row.getString("dose_quantity"), "每次数量应回传十进制文本")
                    assertEquals(1, row.getInteger("daily_dose_count"), "QD 每日 1 次")
                    assertEquals(3, row.getInteger("duration_days"))
                    assertEquals(
                        "3",
                        row.getString("prescribed_total_quantity"),
                        "本疗程应发总量 = 每次数量 1 × 每日 1 次 × 3 天",
                    )
                }
                request(
                    vertx,
                    HttpMethod.POST,
                    "/pharmacy/v1/dispenses/from-medical-order",
                    JsonObject()
                        .put("medical_order_id", orderId)
                        .put("warehouse", "主库")
                        .put("material_id", id("mat"))
                        .put("lot_id", id("lot"))
                        .put("dispensed_quantity", "3"),
                )
            }
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
                        97L,
                        countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'"),
                        "按预填总量 3 发药后库存必须 100 → 97",
                    )
                    // 给药来源取自开嘱时生成的护理任务（真实链路），执行行按任务补一条
                    executeSql(
                        """
                        INSERT INTO nursing.nursing_task_executions (id, task_id, planned_time, status)
                        SELECT '${id("exec")}', id, '2026-08-01T09:00:00+08:00', 'PENDING'
                        FROM nursing.nursing_tasks WHERE order_item_id = '$orderId'
                        """.trimIndent(),
                    )
                }
                request(vertx, HttpMethod.GET, "/nursing/v1/executions/${id("exec")}/administration/sources")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "给药来源应 200: ${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertEquals(1, records.size(), "应恰有 1 条已发药未给完来源: ${body.encode()}")
                    val source = records.getJsonObject(0)
                    assertEquals("3", source.getString("dispensed_quantity"))
                    assertEquals("1", source.getString("dose_quantity"), "来源行必须带出每次数量")
                }
                Future.succeededFuture<Unit>(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
