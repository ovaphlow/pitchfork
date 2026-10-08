package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 054 C1：活动日志的入参校验与 JSON 形状单测（不访库）。
 *
 * 班级级台账：`patient_id` 可空、`activity_date` 缺省业务当天、`participant_count` 缺省 0 且非负。
 */
class ChildcareActivityLogServiceTest {

    private val service = ChildcareActivityLogService(mockk<Pool>(relaxed = true))

    @Test
    fun `业务当天可判定`() {
        assertTrue(ChildcareActivityLogService.today() <= LocalDate.now())
    }

    @Test
    fun `缺少班级或活动名同步报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("activity_name", "户外游戏"), "trainer01")
            }.message!!.contains("class_name"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("class_name", "小班A"), "trainer01")
            }.message!!.contains("activity_name"),
        )
    }

    @Test
    fun `日期非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("class_name", "小班A").put("activity_name", "户外游戏").put("activity_date", "2026/10/06"),
                "trainer01",
            )
        }
        assertTrue(error.message!!.contains("date"), error.message)
    }

    @Test
    fun `参与人数必须为非负整数`() {
        for (bad in listOf(-1, "abc")) {
            val error = assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("class_name", "小班A").put("activity_name", "户外游戏")
                        .put("participant_count", bad),
                    "trainer01",
                )
            }
            assertTrue(error.message!!.contains("participant_count"), "非法参与人数 $bad 必须报错")
        }
    }

    @Test
    fun `metadata类型非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("class_name", "小班A").put("activity_name", "户外游戏").put("metadata", "x"),
                "trainer01",
            )
        }
        assertTrue(error.message!!.contains("metadata"), error.message)
    }

    @Test
    fun `列表筛选参数非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> { service.list(date = "2026/10/06") }
        assertTrue(error.message!!.contains("date"), error.message)
    }

    @Test
    fun `JSON字段与契约一致且patient_id可空`() {
        val json = ChildcareActivityLogService.toJson(activityRow(patientId = null))
        for (key in listOf(
            "id", "patient_id", "class_name", "activity_date", "activity_name",
            "participant_count", "note", "operator", "created_at", "updated_at",
        )) {
            assertTrue(json.containsKey(key), "§5.7 ChildcareActivityLog 必须含字段 $key")
        }
        assertNull(json.getString("patient_id"), "班级级活动 patient_id 必须可空")
        assertEquals(0, json.getInteger("participant_count") ?: -1)
    }

    private fun activityRow(patientId: String?): Row = mockk(relaxed = true) {
        every { getString("id") } returns "log-1"
        every { getString("patient_id") } returns patientId
        every { getString("class_name") } returns "小班A"
        every { getLocalDate("activity_date") } returns LocalDate.parse("2026-10-06")
        every { getString("activity_name") } returns "户外游戏"
        every { getInteger("participant_count") } returns 0
        every { getString("note") } returns null
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }
}
