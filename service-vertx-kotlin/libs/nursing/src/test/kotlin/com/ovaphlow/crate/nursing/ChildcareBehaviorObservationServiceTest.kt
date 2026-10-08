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
import java.time.OffsetDateTime

/**
 * 054 C1：行为观测的**等级白名单**、入参校验与 JSON 形状单测（不访库）。
 *
 * §5.8 冻结：`ChildcareBehaviorObservation.level ∈ {一般, 关注, 需干预}`（可空，给了就必须命中）。
 */
class ChildcareBehaviorObservationServiceTest {

    private val service = ChildcareBehaviorObservationService(mockk<Pool>(relaxed = true))

    @Test
    fun `等级白名单为冻结值`() {
        assertEquals(setOf("一般", "关注", "需干预"), ChildcareBehaviorObservationService.LEVELS)
    }

    @Test
    fun `缺少儿童类别或描述同步报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("category", "情绪").put("description", "入园哭闹"), "trainer01")
            }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("patient_id", "p1").put("description", "入园哭闹"), "trainer01")
            }.message!!.contains("category"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("patient_id", "p1").put("category", "情绪"), "trainer01")
            }.message!!.contains("description"),
        )
    }

    @Test
    fun `等级不在白名单同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("patient_id", "p1").put("category", "情绪")
                    .put("description", "入园哭闹").put("level", "严重"),
                "trainer01",
            )
        }
        assertTrue(error.message!!.contains("invalid level"), error.message)
    }

    @Test
    fun `观测时间非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("patient_id", "p1").put("category", "情绪")
                    .put("description", "入园哭闹").put("observed_at", "2026/10/06"),
                "trainer01",
            )
        }
        assertTrue(error.message!!.contains("observed_at"), error.message)
    }

    @Test
    fun `列表等级非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> { service.list(level = "严重") }
        assertTrue(error.message!!.contains("invalid level"), error.message)
    }

    @Test
    fun `JSON字段与契约一致且level可空`() {
        val json = ChildcareBehaviorObservationService.toJson(observationRow(level = null))
        for (key in listOf(
            "id", "patient_id", "observed_at", "category", "description", "level", "operator", "created_at",
        )) {
            assertTrue(json.containsKey(key), "§5.7 ChildcareBehaviorObservation 必须含字段 $key")
        }
        assertNull(json.getString("level"))
        assertTrue(
            json.containsKey("updated_at").not(),
            "行为观测为 append-only，§5.7 无 updated_at",
        )
    }

    private fun observationRow(level: String?): Row = mockk(relaxed = true) {
        every { getString("id") } returns "obs-1"
        every { getString("patient_id") } returns "patient-1"
        every { getOffsetDateTime("observed_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getString("category") } returns "情绪"
        every { getString("description") } returns "入园哭闹约 5 分钟"
        every { getString("level") } returns level
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }
}
