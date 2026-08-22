package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ReturnIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "rt-"
    override val serverPort = 18512

    private fun id(s: String) = "${fixturePrefix}$s"

    override fun setupFixtures() {
        executeSql(
            """
            INSERT INTO healthcare.patients (id, name, gender, birth_date, status)
            VALUES ('${id("patient")}', '退药测试长者', '男', '1940-01-01', 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO healthcare.encounters (id, patient_id, encounter_type, encounter_no, admit_date, status)
            VALUES ('${id("enc")}', '${id("patient")}', 'ELDERLY_CARE', 'RT-ENC-1', '2026-08-01T00:00:00+08:00', 'ACTIVE')
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
            VALUES ('${id("mat")}', 'RT-MAT', '阿莫西林', '药品', '片', 0, TRUE, '盒', 24, 'ACTIVE')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
            VALUES ('${id("lot")}', '${id("mat")}', 'RT-LOT-1', CURRENT_DATE + 30)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.stock_operations (id, order_no, operation_type, warehouse, status, metadata)
            VALUES ('${id("out-op")}', 'RT-OUT-1', 'OUTBOUND', '主库', 'CONFIRMED', '{"source":"PHARMACY_DISPENSE"}')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.stock_operation_details (id, operation_id, material_id, lot_id, quantity, unit, unit_cost, total_cost)
            VALUES ('${id("out-detail")}', '${id("out-op")}', '${id("mat")}', '${id("lot")}', 10, '片', 1, 10)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO public.stocks (id, warehouse, material_id, lot_id, quantity, locked_quantity, total_cost)
            VALUES ('${id("stock")}', '主库', '${id("mat")}', '${id("lot")}', 90, 0, 90)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO pharmacy.pharmacy_dispenses (id, dispense_no, patient_id, encounter_id, dispense_type, status, pharmacist, reviewer, warehouse, metadata, created_at, dispensed_at)
            VALUES ('${id("dispense")}', 'RT-DISP-1', '${id("patient")}', '${id("enc")}', 'ELDERLY_ROUTINE', 'DISPENSED', '调配药师', '审方药师', '主库', '{}', now(), now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        executeSql(
            """
            INSERT INTO pharmacy.pharmacy_dispense_items (id, dispense_id, order_item_id, material_id, lot_id, prescribed_quantity, dispensed_quantity, stock_operation_detail_id, unit_cost, total_cost)
            VALUES ('${id("dispense-item")}', '${id("dispense")}', '${id("order")}', '${id("mat")}', '${id("lot")}', 10, 10, '${id("out-detail")}', 1, 10)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    override fun cleanupFixtures() = cleanupAll(fixturePrefix)

    override fun assertNoResidual() {
        check(countRows("SELECT count(*) FROM healthcare.patients WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM public.materials WHERE id LIKE '${fixturePrefix}%'") == 0L)
        check(countRows("SELECT count(*) FROM pharmacy.pharmacy_returns WHERE id LIKE '${fixturePrefix}%'") == 0L)
    }

    @Test
    fun `退药确认回补库存并生成入库流水`(vertx: Vertx, ctx: VertxTestContext) {
        var returnId: String? = null
        val createBody = JsonObject()
            .put("dispense_id", id("dispense"))
            .put("dispense_item_id", id("dispense-item"))
            .put("quantity", "5")
            .put("return_reason", "老人未使用")
            .put("operator", "护士甲")
            .put("restockable", true)
            .put("remark", "包装完整")
        request(vertx, HttpMethod.POST, "/pharmacy/v1/returns/from-dispense", createBody)
            .compose { (status, result) ->
                ctx.verify {
                    assertEquals(201, status, "创建退药单应 201: ${result.encode()}")
                    returnId = result.getString("id")
                    assertNotNull(returnId)
                }
                request(
                    vertx,
                    HttpMethod.PUT,
                    "/pharmacy/v1/returns/$returnId/confirm",
                    JsonObject().put("operator", "药房药师"),
                )
            }
            .compose { (status, _) ->
                ctx.verify { assertEquals(200, status) }
                io.vertx.core.Future.future<Unit> { promise ->
                    val stockQty = countRows("SELECT quantity FROM public.stocks WHERE id = '${id("stock")}'")
                    val inboundOps = countRows(
                        "SELECT count(*) FROM public.stock_operation_details d JOIN public.stock_operations o ON d.operation_id = o.id WHERE (o.id LIKE '${fixturePrefix}%' OR o.metadata::text LIKE '%${fixturePrefix}%' OR d.material_id LIKE '${fixturePrefix}%') AND o.operation_type = 'INBOUND'",
                    )
                    ctx.verify {
                        assertEquals(95L, stockQty)
                        assertEquals(1L, inboundOps)
                    }
                    promise.complete()
                }
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
