package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 054 C2：班级日程常量、状态机与入参校验单测（不访库）。
 *
 * §5.8 冻结：`ChildcareClassSchedule.status ∈ {草稿, 已发布, 已取消}`；
 * 流转 草稿→{已发布,已取消}、已发布→已取消、已取消 终态。
 */
class ChildcareClassScheduleServiceTest {

    private val service = ChildcareClassScheduleService(mockk<Pool>(relaxed = true))

    @Test
    fun `状态白名单与流转为冻结口径`() {
        assertEquals(setOf("草稿", "已发布", "已取消"), ChildcareClassScheduleService.STATUSES)
        assertEquals(
            setOf("已发布", "已取消"),
            ChildcareClassScheduleService.TRANSITIONS["草稿"],
        )
        assertEquals(setOf("已取消"), ChildcareClassScheduleService.TRANSITIONS["已发布"])
        assertTrue(ChildcareClassScheduleService.TRANSITIONS["已取消"]!!.isEmpty(), "已取消为终态")
    }

    @Test
    fun `时段常量为固定集合`() {
        val slots = ChildcareClassScheduleService.TIME_SLOTS
        assertEquals(8, slots.size, "时段规模变化需同步本断言与文档")
        assertEquals(slots.size, slots.distinct().size, "时段必须唯一（否则冲突判定失效）")
        assertTrue(slots.all { it.matches(Regex("\\d{2}:\\d{2}-\\d{2}:\\d{2}")) })
    }

    @Test
    fun `排课必填项缺失同步报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> { service.create(bodyWithout("class_name"), "trainer01") }
                .message!!.contains("class_name"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { service.create(bodyWithout("schedule_date"), "trainer01") }
                .message!!.contains("schedule_date"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { service.create(bodyWithout("time_slot"), "trainer01") }
                .message!!.contains("time_slot"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { service.create(bodyWithout("activity_name"), "trainer01") }
                .message!!.contains("activity_name"),
        )
    }

    /** 完整排课体（`JsonObject.remove` 返回 `Object`，故用 `apply` 保持类型）。 */
    private fun bodyWithout(key: String): JsonObject = JsonObject()
        .put("class_name", "小班A")
        .put("schedule_date", "2026-10-06")
        .put("time_slot", "09:00-10:00")
        .put("activity_name", "户外游戏")
        .apply { remove(key) }

    @Test
    fun `非法时段与日期同步报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("class_name", "小班A").put("schedule_date", "2026-10-06")
                        .put("time_slot", "12:30-13:30").put("activity_name", "午休"),
                    "trainer01",
                )
            }.message!!.contains("invalid time_slot"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("class_name", "小班A").put("schedule_date", "2026/10/06")
                        .put("time_slot", "09:00-10:00").put("activity_name", "户外游戏"),
                    "trainer01",
                )
            }.message!!.contains("schedule_date"),
        )
    }

    @Test
    fun `状态流转入参校验`() {
        assertTrue(
            assertThrows<IllegalArgumentException> { service.updateStatus("s1", JsonObject(), "trainer01") }
                .message!!.contains("status"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.updateStatus("s1", JsonObject().put("status", "已归档"), "trainer01")
            }.message!!.contains("invalid status"),
        )
    }

    @Test
    fun `列表筛选参数校验`() {
        assertTrue(
            assertThrows<IllegalArgumentException> { service.list(status = "已归档") }
                .message!!.contains("invalid status"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { service.list(date = "2026/10/06") }
                .message!!.contains("date"),
        )
    }

    @Test
    fun `JSON字段与契约一致`() {
        val json = ChildcareClassScheduleService.toJson(scheduleRow())
        for (key in listOf(
            "id", "class_name", "schedule_date", "time_slot", "activity_name",
            "status", "operator", "created_at", "updated_at",
        )) {
            assertTrue(json.containsKey(key), "§5.7 ChildcareClassSchedule 必须含字段 $key")
        }
    }

    private fun scheduleRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "schedule-1"
        every { getString("class_name") } returns "小班A"
        every { getLocalDate("schedule_date") } returns LocalDate.parse("2026-10-06")
        every { getString("time_slot") } returns "09:00-10:00"
        every { getString("activity_name") } returns "户外游戏"
        every { getString("status") } returns "草稿"
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }
}
