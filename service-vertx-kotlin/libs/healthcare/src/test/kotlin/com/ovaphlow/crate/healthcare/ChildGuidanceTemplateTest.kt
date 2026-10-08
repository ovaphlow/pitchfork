package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 052 P5：健康指导模板常量测试（**不访问数据库**）。
 *
 * 锁定：月龄段边界与前端 `lib/guidance.ts` 同口径；每个月龄段至少覆盖喂养/安全/发育促进
 * 等核心主题；主题取值都在白名单内（052 D7）。
 */
class ChildGuidanceTemplateTest {

    @Test
    fun `月龄段边界`() {
        assertEquals("0-6月", ChildGuidanceService.ageBandOf(0))
        assertEquals("0-6月", ChildGuidanceService.ageBandOf(6))
        assertEquals("7-12月", ChildGuidanceService.ageBandOf(7))
        assertEquals("7-12月", ChildGuidanceService.ageBandOf(12))
        assertEquals("13-24月", ChildGuidanceService.ageBandOf(13))
        assertEquals("13-24月", ChildGuidanceService.ageBandOf(24))
        assertEquals("25-60月", ChildGuidanceService.ageBandOf(25))
        assertEquals("25-60月", ChildGuidanceService.ageBandOf(60))
    }

    @Test
    fun `每个模板的月龄段与主题都在白名单内`() {
        assertTrue(ChildGuidanceService.GUIDANCE_TEMPLATES.isNotEmpty())
        ChildGuidanceService.GUIDANCE_TEMPLATES.forEach { template ->
            assertTrue(
                template.ageBand in ChildGuidanceService.ageBands,
                "${template.code} 的月龄段非法：${template.ageBand}",
            )
            assertTrue(
                template.topic in ChildGuidanceService.topics,
                "${template.code} 的主题非法：${template.topic}",
            )
            assertTrue(template.content.isNotBlank(), "${template.code} 必须有指导内容")
        }
    }

    @Test
    fun `模板编码唯一且每个年龄带都有喂养与安全或发育主题`() {
        val codes = ChildGuidanceService.GUIDANCE_TEMPLATES.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "模板编码必须唯一")

        val byBand = ChildGuidanceService.GUIDANCE_TEMPLATES.groupBy { it.ageBand }
        assertEquals(
            ChildGuidanceService.ageBands.toSet(),
            byBand.keys,
            "每个月龄段都必须有模板，否则护士在部分月龄取不到推荐",
        )
        byBand.forEach { (band, templates) ->
            val topics = templates.map { it.topic }.toSet()
            assertTrue(
                topics.contains("喂养") || topics.contains("疾病预防") || topics.contains("发育促进"),
                "$band 至少要有喂养/疾病预防/发育促进之一：$topics",
            )
        }
    }

    @Test
    fun `模板可序列化为契约形状`() {
        val json = ChildGuidanceService.templateJson(ChildGuidanceService.GUIDANCE_TEMPLATES.first())
        listOf("template_code", "age_band", "topic", "title", "content", "kind").forEach { key ->
            assertTrue(json.containsKey(key), "模板 JSON 必须含 $key")
        }
    }

    @Test
    fun `疾病防控模板至少四条且主题月龄段合法`() {
        val prevention = ChildGuidanceService.GUIDANCE_TEMPLATES.filter { it.kind == "疾病防控" }
        assertTrue(prevention.size >= 4, "医生疾病防控模板至少 4 条，实际 ${prevention.size}")
        prevention.forEach { template ->
            assertEquals(
                "疾病预防",
                template.topic,
                "${template.code} 疾病防控模板主题应为「疾病预防」",
            )
            assertTrue(
                template.topic in ChildGuidanceService.topics,
                "${template.code} 的主题非法：${template.topic}",
            )
            assertTrue(
                template.ageBand in ChildGuidanceService.ageBands,
                "${template.code} 的月龄段非法：${template.ageBand}",
            )
            assertTrue(template.content.isNotBlank(), "${template.code} 必须有指导内容")
        }
        val codes = prevention.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "疾病防控模板编码必须唯一")
    }

    @Test
    fun `每个模板的 kind 在白名单内且 JSON 含 kind`() {
        ChildGuidanceService.GUIDANCE_TEMPLATES.forEach { template ->
            assertTrue(
                template.kind in ChildGuidanceService.kinds,
                "${template.code} 的 kind 非法：${template.kind}",
            )
            val json = ChildGuidanceService.templateJson(template)
            assertTrue(json.containsKey("kind"), "${template.code} 模板 JSON 必须含 kind")
            assertEquals(template.kind, json.getString("kind"), "${template.code} 的 kind 序列化不一致")
        }
        // 052 既有模板未显式声明 kind，缺省应为「日常」，保证护士日常指导回归不变。
        assertTrue(
            ChildGuidanceService.GUIDANCE_TEMPLATES
                .filter { it.code == "FEED_0_6" || it.code == "PREVENT_25_60" }
                .all { it.kind == "日常" },
            "052 既有模板的 kind 缺省必须为「日常」",
        )
    }
}
