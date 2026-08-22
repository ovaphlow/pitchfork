package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

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
        executeSql(
            """
            INSERT INTO healthcare.medical_orders (id, encounter_id, order_type, order_class, order_content, order_details, start_time, doctor, status, nurse_checked_by, nurse_checked_at)
            VALUES ('${id("order")}', '${id("enc")}', 'MEDICATION', 'LONG_TERM', '阿莫西林 0.5g 每日两次', '{"drug_name":"阿莫西林","dose":"0.5g","unit":"片/次","route":"口服","frequency_code":"QD","frequency_name":"每日一次","duration_days":2}', '2026-08-01T10:00:00+08:00', '赵医生', 'ACTIVE', '护士甲', '2026-08-01T11:00:00+08:00')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.materials (id, code, name, category, base_unit, quantity_scale, enable_batch_control, package_unit, package_size, status)
            VALUES ('${id("mat")}', 'DP-MAT', '阿莫西林', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
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
            INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost)
            VALUES ('${id("stock")}', '主库', '${id("mat")}', '${id("lot")}', 100, 0, 0)
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
}
