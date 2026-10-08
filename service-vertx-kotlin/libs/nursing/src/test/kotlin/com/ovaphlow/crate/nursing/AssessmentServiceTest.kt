package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.data.Numeric
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 护理评估回读映射回归测试。
 *
 * Vert.x pg-client 把 PostgreSQL NUMERIC 解码为 io.vertx.sqlclient.data.Numeric（Number 子类），
 * 而不是 java.math.BigDecimal。历史上 toJson 用 `getValue("total_score") as? BigDecimal` 取值，
 * 该软转换恒为 null，导致列表（GET /nursing/v1/assessments/）与单条（GET /nursing/v1/assessments/{id}）
 * 回读时「分数」列永远显示 -（写入正常，仅读取丢字段）。本测试锁定读取侧不再丢分。
 */
class AssessmentServiceTest {

    private fun assessmentRow(totalScore: Any?): Row {
        val values = mapOf<String, Any?>(
            "id" to "asm-1",
            "encounter_id" to "enc-1",
            "period_id" to "per-1",
            "assess_type" to "BARTHEL",
            "assess_date" to LocalDate.of(2026, 8, 5),
            "assessor" to "护理员",
            "total_score" to totalScore,
            "result_level" to "中度依赖",
            "detail" to JsonObject().put("note", "步行能力下降"),
            "remark" to "复评说明",
            "metadata" to null,
            "created_at" to OffsetDateTime.parse("2026-08-05T09:00:00+08:00"),
        )
        val row = mockk<Row>()
        every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
        return row
    }

    @Test
    fun `NUMERIC 分数按 Number 解码不再丢失`() {
        val json = AssessmentService.toJson(assessmentRow(Numeric.parse("77.0")))

        assertEquals("BARTHEL", json.getString("assess_type"))
        assertEquals(77.0, json.getDouble("total_score"))
    }

    @Test
    fun `边界分数 0 与 100 正确回读`() {
        assertEquals(0.0, AssessmentService.toJson(assessmentRow(Numeric.parse("0.0"))).getDouble("total_score"))
        assertEquals(100.0, AssessmentService.toJson(assessmentRow(Numeric.parse("100.0"))).getDouble("total_score"))
    }

    @Test
    fun `分数为空时保持 null`() {
        assertNull(AssessmentService.toJson(assessmentRow(null)).getValue("total_score"))
    }
}
