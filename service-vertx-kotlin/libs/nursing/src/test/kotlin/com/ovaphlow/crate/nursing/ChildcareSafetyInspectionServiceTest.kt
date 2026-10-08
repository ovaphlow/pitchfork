package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime

/**
 * 054 C2：安全巡查**检查项常量**、隐患闭环状态机与入参校验单测（不访库）。
 *
 * §5.8 冻结：`ChildcareSafetyInspection.status ∈ {待整改, 已整改, 已关闭}`，
 * 闭环**严格线性** `待整改 → 已整改 → 已关闭`（跳步/终态再流转非法）。
 */
class ChildcareSafetyInspectionServiceTest {

    private val service = ChildcareSafetyInspectionService(mockk<Pool>(relaxed = true))

    @Test
    fun `状态白名单与闭环为严格线性`() {
        assertEquals(setOf("待整改", "已整改", "已关闭"), ChildcareSafetyInspectionService.STATUSES)
        assertEquals(
            setOf("已整改"),
            ChildcareSafetyInspectionService.TRANSITIONS["待整改"],
            "待整改不得直接关闭（跳步）",
        )
        assertEquals(setOf("已关闭"), ChildcareSafetyInspectionService.TRANSITIONS["已整改"])
        assertTrue(ChildcareSafetyInspectionService.TRANSITIONS["已关闭"]!!.isEmpty(), "已关闭为终态")
    }

    @Test
    fun `检查项常量为12项且code唯一`() {
        val items = ChildcareSafetyInspectionService.CHECK_ITEMS
        assertEquals(12, items.size, "检查项规模变化需同步本断言与文档")
        assertEquals(items.size, items.map { it.first }.distinct().size, "item_code 必须唯一")
        assertTrue(items.all { it.second.isNotBlank() }, "检查项名称不得为空")
        assertEquals(items.map { it.first }.toSet(), ChildcareSafetyInspectionService.CHECK_ITEM_CODES)
    }

    @Test
    fun `检查结果白名单为三值`() {
        assertEquals(setOf("合格", "不合格", "不适用"), ChildcareSafetyInspectionService.RESULTS)
    }

    @Test
    fun `创建巡查必填项与检查项校验`() {
        assertTrue(
            assertThrows<IllegalArgumentException> { service.create(JsonObject(), "trainer01") }
                .message!!.contains("area"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("area", "活动室").put("items", "x"), "trainer01")
            }.message!!.contains("items must be an array"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("area", "活动室").put("items", JsonArray().add("x")), "trainer01")
            }.message!!.contains("each item must be an object"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("area", "活动室")
                        .put("items", JsonArray().add(JsonObject().put("result", "合格"))),
                    "trainer01",
                )
            }.message!!.contains("item_code"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("area", "活动室")
                        .put("items", JsonArray().add(JsonObject().put("item_code", "NOPE").put("result", "合格"))),
                    "trainer01",
                )
            }.message!!.contains("unknown item_code"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("area", "活动室")
                        .put(
                            "items",
                            JsonArray().add(JsonObject().put("item_code", "FLOOR_SLIP").put("result", "差不多")),
                        ),
                    "trainer01",
                )
            }.message!!.contains("invalid result"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(
                    JsonObject().put("area", "活动室")
                        .put(
                            "items",
                            JsonArray().add(JsonObject().put("item_code", "FLOOR_SLIP")),
                        ),
                    "trainer01",
                )
            }.message!!.contains("result is required"),
        )
    }

    @Test
    fun `创建巡查时间与metadata校验`() {
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("area", "活动室").put("inspected_at", "2026/10/06"), "trainer01")
            }.message!!.contains("inspected_at"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> {
                service.create(JsonObject().put("area", "活动室").put("metadata", "x"), "trainer01")
            }.message!!.contains("metadata"),
        )
    }

    @Test
    fun `追加整改项与关闭入参校验`() {
        assertTrue(
            assertThrows<IllegalArgumentException> { service.appendItem("i1", JsonObject(), "trainer01") }
                .message!!.contains("item_code"),
        )
        assertTrue(
            assertThrows<IllegalArgumentException> { service.close("   ", "trainer01") }
                .message!!.contains("id"),
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
    fun `JSON字段与契约一致且items为数组`() {
        val json = ChildcareSafetyInspectionService.toJson(inspectionRow())
        for (key in listOf(
            "id", "area", "inspected_at", "items", "status", "operator", "created_at", "updated_at",
        )) {
            assertTrue(json.containsKey(key), "§5.7 ChildcareSafetyInspection 必须含字段 $key")
        }
        assertEquals(1, json.getJsonArray("items")?.size() ?: -1)
        assertEquals("FLOOR_SLIP", json.getJsonArray("items")!!.getJsonObject(0).getString("item_code"))
    }

    private fun inspectionRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "inspection-1"
        every { getString("area") } returns "活动室"
        every { getOffsetDateTime("inspected_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getJsonArray("items") } returns JsonArray().add(
            JsonObject().put("item_code", "FLOOR_SLIP").put("result", "不合格").put("hazard", "地面有积水"),
        )
        every { getString("status") } returns "待整改"
        every { getString("operator") } returns "trainer01"
        every { getJsonObject("metadata") } returns null
        every { getOffsetDateTime("created_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
        every { getOffsetDateTime("updated_at") } returns OffsetDateTime.parse("2026-10-06T09:00:00+08:00")
    }
}
