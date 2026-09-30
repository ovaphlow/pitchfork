package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.TaskService
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 032 P4 非数据库测试：医嘱明细 `dose_quantity` 校验，以及药房待接方行的
 * `dose_quantity` / `daily_dose_count` / `duration_days` / `prescribed_total_quantity` 推导。
 *
 * 口径（用药安全优先，推不出来就不预填）：
 * - `daily_dose_count` 只有每日给药频次有值（QD/BID/TID/QID）；QOD/QW/BIW/TIW、PRN/STAT、
 *   空白与未知频次一律 null，不用 `scheduleTimes` 时段条数冒充每日次数；
 * - `prescribed_total_quantity` 仅三者齐备时求积，`STAT` 为一次性（等于每次数量），
 *   其余一律 null；
 * - 存量医嘱（无 `dose_quantity`）四个字段全为 null，行为与改动前一致。
 */
class MedicalOrderPrescribedQuantityTest {

    // ========================================================================
    //  dose_quantity 输入校验（校验失败不得触发任何 SQL）
    // ========================================================================

    @Test
    fun `dose_quantity 非法值一律被拒且不触发SQL`() {
        val pool = mockk<Pool>()
        val service = MedicalOrderService(pool, mockk<TaskService>())

        fun expectInvalid(doseQuantity: Any?, vararg fragments: String) {
            val details = JsonObject()
                .put("material_id", "mat-1")
                .put("drug_name", "阿司匹林")
                .put("dose", "100mg")
                .put("dose_quantity", doseQuantity)
            val body = JsonObject()
                .put("order_type", "MEDICATION")
                .put("order_class", "LONG_TERM")
                .put("order_content", "阿司匹林 100mg 每日一次")
                .put("doctor", "赵医生")
                .put("start_time", "2026-08-01T09:00:00+08:00")
                .put("order_details", details)
            val cause = causeOf(service.createOrder("enc-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectInvalid(1, "dose_quantity must be decimal text")
        expectInvalid(1.5, "dose_quantity must be decimal text")
        expectInvalid("abc", "dose_quantity must be decimal text")
        expectInvalid("", "dose_quantity must be decimal text")
        expectInvalid("0", "dose_quantity must be positive")
        expectInvalid("0.000", "dose_quantity must be positive")
        expectInvalid("-1", "dose_quantity must be positive")
        expectInvalid("0.1234567", "dose_quantity exceeds precision of 6 decimals")

        io.mockk.verify(exactly = 0) { pool.preparedQuery(any<String>()) }
        io.mockk.verify(exactly = 0) { pool.withTransaction<Any>(any()) }
    }

    @Test
    fun `dose_quantity 只对 MEDICATION 开放`() {
        val service = MedicalOrderService(mockk<Pool>(), mockk<TaskService>())
        val body = JsonObject()
            .put("order_type", "THERAPY")
            .put("order_class", "LONG_TERM")
            .put("order_content", "康复理疗 30 分钟")
            .put("doctor", "赵医生")
            .put("start_time", "2026-08-01T09:00:00+08:00")
            .put(
                "order_details",
                JsonObject()
                    .put("treatment_item", "康复理疗")
                    .put("dose_quantity", "1"),
            )
        val cause = causeOf(service.createOrder("enc-1", body))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("unsupported keys") == true, "got: ${cause.message}")
    }

    // ========================================================================
    //  药房待接方行推导
    // ========================================================================

    @Test
    fun `三因子齐备时求积并按十进制文本输出`() {
        val row = pharmacyRow(
            JsonObject()
                .put("dose_quantity", "1.500")
                .put("frequency_code", "BID")
                .put("duration_days", 3),
        )
        assertEquals("1.5", row.getString("dose_quantity"), "每次数量按 D5 去尾零规范化")
        assertEquals(2, row.getInteger("daily_dose_count"))
        assertEquals(3, row.getInteger("duration_days"))
        assertEquals("9", row.getString("prescribed_total_quantity"), "1.5 × 2 × 3")
    }

    @Test
    fun `三因子齐备时小数乘积不引入二进制尾差`() {
        val row = pharmacyRow(
            JsonObject()
                .put("dose_quantity", "0.1")
                .put("frequency_code", "TID")
                .put("duration_days", 7),
        )
        assertEquals("2.1", row.getString("prescribed_total_quantity"), "0.1 × 3 × 7 必须精确为 2.1")
    }

    @Test
    fun `STAT 为一次性等于每次数量且不乘天数`() {
        val withoutDuration = pharmacyRow(
            JsonObject().put("dose_quantity", "2").put("frequency_code", "STAT"),
        )
        assertEquals("2", withoutDuration.getString("prescribed_total_quantity"))
        assertNull(withoutDuration.getInteger("daily_dose_count"), "STAT 不参与每日次数推导")
        assertNull(withoutDuration.getInteger("duration_days"))

        val withDuration = pharmacyRow(
            JsonObject()
                .put("dose_quantity", "2")
                .put("frequency_code", "STAT")
                .put("duration_days", 3),
        )
        assertEquals("2", withDuration.getString("prescribed_total_quantity"), "STAT 仍是一次性")
        assertEquals(3, withDuration.getInteger("duration_days"))
    }

    @Test
    fun `缺 dose_quantity 时总量为 null 但每日次数照常推导`() {
        val row = pharmacyRow(
            JsonObject().put("frequency_code", "QD").put("duration_days", 3),
        )
        assertNull(row.getString("dose_quantity"))
        assertEquals(1, row.getInteger("daily_dose_count"))
        assertEquals(3, row.getInteger("duration_days"))
        assertNull(row.getString("prescribed_total_quantity"), "缺每次数量不得预填发药量")
    }

    @Test
    fun `非 STAT 缺 duration_days 时总量为 null`() {
        val row = pharmacyRow(JsonObject().put("dose_quantity", "2").put("frequency_code", "QD"))
        assertEquals("2", row.getString("dose_quantity"))
        assertEquals(1, row.getInteger("daily_dose_count"))
        assertNull(row.getInteger("duration_days"))
        assertNull(row.getString("prescribed_total_quantity"))
    }

    @Test
    fun `每日次数为 null 的频次不预填总量`() {
        for (code in listOf("QW", "QOD", "BIW", "TIW", "PRN")) {
            val row = pharmacyRow(
                JsonObject()
                    .put("dose_quantity", "2")
                    .put("frequency_code", code)
                    .put("duration_days", 4),
            )
            assertNull(row.getInteger("daily_dose_count"), "$code 不是每日频次，每日次数必须为 null")
            assertNull(
                row.getString("prescribed_total_quantity"),
                "$code 推不出可信总量，必须为 null（不得用时段条数高估）",
            )
        }
    }

    @Test
    fun `存量医嘱不产生预填量且既有字段不变`() {
        // 存量医嘱（无 dose_quantity）：预填量必须为 null，药房保持手填——行为与改动前一致。
        // daily_dose_count 只由 frequency_code 推导（冻结契约），QD 存量为 1，不参与预填。
        val row = pharmacyRow(
            JsonObject()
                .put("drug_name", "阿莫西林")
                .put("dose", "0.5g")
                .put("unit", "片/次")
                .put("frequency_code", "QD")
                .put("frequency_name", "每日一次"),
        )
        assertNull(row.getString("dose_quantity"))
        assertEquals(1, row.getInteger("daily_dose_count"))
        assertNull(row.getInteger("duration_days"))
        assertNull(row.getString("prescribed_total_quantity"), "无每次数量的存量医嘱不得预填发药量")
        // 既有字段名/类型不变
        assertEquals("阿莫西林", row.getString("drug_name"))
        assertEquals("0.5g", row.getString("dose"))
        assertEquals("片/次", row.getString("unit"))
        assertEquals("QD", row.getString("frequency_code"))
        assertEquals("每日一次", row.getString("frequency_name"))
    }

    @Test
    fun `无频次无每次数量的存量医嘱四个字段全为 null`() {
        val row = pharmacyRow(
            JsonObject()
                .put("drug_name", "阿莫西林")
                .put("dose", "0.5g")
                .put("unit", "片/次"),
        )
        assertNull(row.getString("dose_quantity"))
        assertNull(row.getInteger("daily_dose_count"))
        assertNull(row.getInteger("duration_days"))
        assertNull(row.getString("prescribed_total_quantity"))
    }

    // ——— 辅助 ———

    /** 走真实 `listMedicationOrdersForPharmacy`，只桩掉两次 SQL 执行（count + data）。 */
    private fun pharmacyRow(details: JsonObject): JsonObject {
        val pool = mockk<Pool>()
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } returns prepared
        every { pool.preparedQuery(any<String>(), any()) } returns prepared
        var executed = 0
        every { prepared.execute(any<Tuple>()) } answers {
            executed++
            if (executed == 1) {
                Future.succeededFuture(rowSet(row(mapOf("total" to 1L))))
            } else {
                Future.succeededFuture(rowSet(row(pharmacyFields(details))))
            }
        }
        val service = MedicalOrderService(pool, mockk<TaskService>())
        return service.listMedicationOrdersForPharmacy(pool, null, null, 50, 0)
            .toCompletionStage().toCompletableFuture().get()
            .getJsonArray("records")
            .getJsonObject(0)
    }

    private fun pharmacyFields(details: JsonObject): Map<String, Any?> = mapOf(
        "order_id" to "ord-1",
        "encounter_id" to "enc-1",
        "patient_id" to "pat-1",
        "patient_name" to "测试长者",
        "encounter_no" to "ENC-1",
        "order_type" to "MEDICATION",
        "order_class" to "LONG_TERM",
        "order_content" to "阿司匹林 100mg 每日一次",
        "order_details" to details,
        "doctor" to "赵医生",
        "start_time" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        "end_time" to null,
        "nurse_checked_by" to "护士甲",
        "nurse_checked_at" to OffsetDateTime.parse("2026-08-01T11:00:00+08:00"),
    )

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
}

private fun row(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
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
