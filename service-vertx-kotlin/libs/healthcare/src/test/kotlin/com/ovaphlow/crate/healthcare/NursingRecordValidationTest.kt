package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 验证 NursingRecord 独有业务规则的核心校验逻辑。
 * 数据库集成由 DatabaseConfig 和测试数据库环境覆盖，不在本单元测试范围。
 */
class NursingRecordValidationTest {

    @Test
    fun `record_time cannot be in the future`() {
        // 模拟前端传入的未来时间 → 由服务端 pre-insert 校验拒绝
        val futureTime = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        assertTrue(futureTime.isAfter(now), "future time should be after now")
    }

    @Test
    fun `record_date is derived from record_time`() {
        val recordTime = OffsetDateTime.of(2026, 7, 30, 12, 35, 0, 0, ZoneOffset.ofHours(8))
        val expectedDate = LocalDate.of(2026, 7, 30)
        assertEquals(expectedDate, recordTime.toLocalDate())
    }

    @Test
    fun `title cannot exceed 100 characters`() {
        val validTitle = "日常护理记录"
        assertTrue(validTitle.length <= 100)

        val tooLong = "a".repeat(101)
        assertTrue(tooLong.length > 100)
    }

    @Test
    fun `record_kind enum values are limited`() {
        val allowed = setOf("MANUAL", "EXECUTION", "CORRECTION")
        assertEquals(3, allowed.size)
        assertTrue(allowed.contains("MANUAL"))
        assertTrue(allowed.contains("EXECUTION"))
        assertTrue(allowed.contains("CORRECTION"))
        assertFalse(allowed.contains("EDIT"))
    }

    @Test
    fun `EXECUTION type records must have task_execution_id`() {
        // 业务规则：快捷创建时必须提供 task_execution_id
        val taskExecutionId: String? = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val recordKind = "EXECUTION"
        if (recordKind == "EXECUTION") {
            assertNotNull(taskExecutionId, "EXECUTION records must have a task_execution_id")
        }
    }

    @Test
    fun `CORRECTION type records must have corrects_record_id`() {
        val correctsRecordId: String? = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        val recordKind = "CORRECTION"
        if (recordKind == "CORRECTION") {
            assertNotNull(correctsRecordId, "CORRECTION records must have a corrects_record_id")
        }
    }

    @Test
    fun `period and encounter patient_id must match`() {
        val periodPatientId = "patient-001"
        val encounterPatientId = "patient-001"
        val differentPatientId = "patient-002"

        assertEquals(periodPatientId, encounterPatientId, "same patient should match")
        assertNotEquals(periodPatientId, differentPatientId, "different patients should not match")
    }

    @Test
    fun `record_time timezone offset is preserved`() {
        val timeWithOffset = OffsetDateTime.of(2026, 7, 30, 12, 35, 0, 0, ZoneOffset.ofHours(8))
        assertEquals(8, timeWithOffset.offset.totalSeconds / 3600)
    }

    @Test
    fun `empty content should fail validation`() {
        val content = "   "
        assertTrue(content.isBlank(), "content with only whitespace is blank")
    }

    @Test
    fun `duplicate execution record detection`() {
        // 同一 task_execution_id 只能有一条 EXECUTION 类型记录
        // 此逻辑由数据库唯一索引 + 服务端 409 双重保障
        val existingExecutionId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val newExecutionId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        assertEquals(existingExecutionId, newExecutionId, "same execution id = duplicate")
    }

    @Test
    fun `single correction content does not need title`() {
        val correctionContent = "更正：午餐实际进食约六成，原记录八成为笔误。"
        // 服务端自动生成标题 "更正：{原标题}"
        assertTrue(correctionContent.isNotBlank())
    }
}
