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
 * 054 B4：手册模板常量、签收状态派生与入参校验的单测（不访库）。
 *
 * 签收状态**由 `acknowledged_at` 派生**（NULL → 未签收；非空 → 已签收），不落独立状态列。
 */
class ChildRehabHandoutTest {

    /** 校验路径不触库：入参非法必须在访问 `pool` 之前同步抛出。 */
    private val service = ChildRehabHandoutService(mockk<Pool>(relaxed = true))

    // ========================================================================
    //  模板常量
    // ========================================================================

    @Test
    fun `模板常量为8册且code唯一内容非空`() {
        val templates = ChildRehabHandoutService.TEMPLATES
        assertEquals(8, templates.size, "模板规模变化需同步本断言与文档")
        assertEquals(templates.size, templates.map { it.code }.distinct().size, "code 必须唯一")
        assertTrue(templates.all { it.name.isNotBlank() && it.content.isNotBlank() })
        assertEquals("现场", ChildRehabHandoutService.DEFAULT_CHANNEL)
        assertEquals("未签收", ChildRehabHandoutService.STATUS_PENDING)
        assertEquals("已签收", ChildRehabHandoutService.STATUS_ACKNOWLEDGED)
    }

    @Test
    fun `按code取模板支持trim且未知返回null`() {
        assertEquals("家庭抬头训练指导", ChildRehabHandoutService.templateOf("HOME_HEAD_CONTROL")?.name)
        assertEquals("家庭日常照护指导", ChildRehabHandoutService.templateOf(" HOME_DAILY_CARE ")?.name)
        assertNull(ChildRehabHandoutService.templateOf("NOPE"))
    }

    @Test
    fun `模板清单返回records与total`() {
        val result = service.templates().result()
        assertEquals(8L, result.getJsonObject("meta")?.getLong("total"))
        assertEquals(8, result.getJsonArray("records")?.size() ?: -1)
        val first = result.getJsonArray("records")!!.getJsonObject(0)
        assertTrue(first.containsKey("template_code") && first.containsKey("template_name"))
        assertTrue((first.getString("content") ?: "").isNotBlank())
    }

    // ========================================================================
    //  签收状态派生
    // ========================================================================

    @Test
    fun `未签收与已签收由acknowledged_at派生`() {
        assertEquals("未签收", ChildRehabHandoutService.toJson(handoutRow(null)).getString("status"))
        assertNull(ChildRehabHandoutService.toJson(handoutRow(null)).getString("acknowledged_at"))

        val acknowledged = ChildRehabHandoutService.toJson(
            handoutRow(OffsetDateTime.parse("2026-10-06T10:00:00+08:00")),
        )
        assertEquals("已签收", acknowledged.getString("status"))
        assertEquals(
            OffsetDateTime.parse("2026-10-06T10:00:00+08:00").toString(),
            acknowledged.getString("acknowledged_at"),
            "ISO 偏移时间字符串（秒为 0 时省略）",
        )
        assertEquals("rehab01", acknowledged.getString("acknowledged_by"))
    }

    @Test
    fun `台账JSON字段与契约一致`() {
        val json = ChildRehabHandoutService.toJson(handoutRow(null))
        for (key in listOf(
            "id", "patient_id", "template_code", "content", "channel",
            "delivered_at", "acknowledged_at", "operator", "created_at",
        )) {
            assertTrue(json.containsKey(key), "§5.7 ChildRehabHandout 必须含字段 $key")
        }
    }

    // ========================================================================
    //  入参校验
    // ========================================================================

    @Test
    fun `分发入参非法同步报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("template_code", "HOME_SIT"), "rehab01")
            }.message!!.contains("patient_id"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("patient_id", "p1"), "rehab01")
            }.message!!.contains("template_code"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("patient_id", "p1").put("template_code", "NOPE"), "rehab01")
            }.message!!.contains("unknown template_code"),
        )
    }

    @Test
    fun `分发时间非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject()
                    .put("patient_id", "p1")
                    .put("template_code", "HOME_SIT")
                    .put("delivered_at", "2026/10/06"),
                "rehab01",
            )
        }
        assertTrue(error.message!!.contains("delivered_at"), error.message)
    }

    @Test
    fun `metadata类型非法同步报错`() {
        val error = assertThrows<IllegalArgumentException> {
            service.create(
                JsonObject().put("patient_id", "p1").put("template_code", "HOME_SIT").put("metadata", "x"),
                "rehab01",
            )
        }
        assertTrue(error.message!!.contains("metadata"), error.message)
    }

    @Test
    fun `签收入参非法同步报错`() {
        assertTrue(
            assertThrows<IllegalArgumentException> { service.acknowledge("   ", "rehab01") }
                .message!!.contains("id"),
        )
    }

    private fun handoutRow(acknowledgedAt: OffsetDateTime?): Row = mockk(relaxed = true) {
        every { getString("id") } returns "handout-1"
        every { getString("patient_id") } returns "patient-1"
        every { getString("template_code") } returns "HOME_SIT"
        every { getString("template_name") } returns "家庭独坐训练指导"
        every { getString("content") } returns "靠坐 → 扶坐 → 独坐"
        every { getString("channel") } returns "现场"
        every { getOffsetDateTime("delivered_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("acknowledged_at") } returns acknowledgedAt
        every { getString("acknowledged_by") } returns if (acknowledgedAt == null) null else "rehab01"
        every { getString("operator") } returns "rehab01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }
}
