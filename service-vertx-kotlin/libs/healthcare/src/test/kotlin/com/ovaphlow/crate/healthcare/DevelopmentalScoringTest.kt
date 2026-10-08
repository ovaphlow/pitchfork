package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * A1（053）— 发育筛查评分引擎测试（**不访问数据库**）。
 *
 * 锁定验收口径（053 §4 A1 / §8 验收 1）：
 *  - 正常 / 可疑 / 异常三档判读按达标率切点（0.90 / 0.70）。
 *  - 适龄条目随月龄切换；月龄越界、缺项、多余项、非法分值均明确报错。
 *  - 领域分与领域满分、触发项、回显顺序正确。
 *  - 占位量表包结构自检（编码唯一、domain 合法、区间合法）+ 源文件零 jOOQ / 零 Vert.x 依赖。
 */
class DevelopmentalScoringTest {

    private val scale = DevelopmentalScaleCatalog.find(DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE)!!

    // ========================================================================
    //  量表包自检
    // ========================================================================

    @Test
    fun `占位量表包结构自检`() {
        assertTrue(DevelopmentalScaleCatalog.scales.isNotEmpty(), "至少注册一个量表")

        val allItemCodes = mutableListOf<String>()
        DevelopmentalScaleCatalog.scales.values.forEach { scale ->
            assertTrue(scale.minMonths <= scale.maxMonths, "${scale.code} 适用区间非法")
            assertTrue(scale.domains.isNotEmpty(), "${scale.code} 必须有领域")
            assertTrue(scale.items.size in 10..15, "${scale.code} 应有 10–15 个条目，实际 ${scale.items.size}")

            val domainCodes = scale.domains.map { it.code }.toSet()
            assertEquals(domainCodes.size, scale.domains.size, "${scale.code} 领域编码必须唯一")

            scale.items.forEach { item ->
                assertTrue(
                    item.domain in domainCodes,
                    "${item.code} 的 domain=${item.domain} 不在量表领域内：$domainCodes",
                )
                assertTrue(item.minMonths <= item.maxMonths, "${item.code} 适龄区间非法")
                assertTrue(
                    item.minMonths >= scale.minMonths && item.maxMonths <= scale.maxMonths,
                    "${item.code} 适龄区间超出量表区间",
                )
                assertTrue(item.options.size in 2..3, "${item.code} 应有 2–3 个选项")
                assertEquals(
                    item.options.size,
                    item.options.map { it.value }.toSet().size,
                    "${item.code} 选项值必须互不相同",
                )
                assertTrue(item.options.all { it.label.isNotBlank() }, "${item.code} 选项标签非空")
                allItemCodes += item.code
            }
        }

        assertEquals(allItemCodes.size, allItemCodes.toSet().size, "条目编码必须全局唯一")
    }

    @Test
    fun `占位量表覆盖零到六十月且无空档`() {
        assertEquals(0, scale.minMonths)
        assertEquals(60, scale.maxMonths)
        (scale.minMonths..scale.maxMonths).forEach { age ->
            assertTrue(
                DevelopmentalScaleCatalog.ageAppropriateItems(scale, age).isNotEmpty(),
                "月龄 $age 没有任何适龄条目",
            )
        }
    }

    @Test
    fun `量表源文件不依赖 jOOQ 与 Vertx`() {
        val file = File("src/main/kotlin/com/ovaphlow/crate/healthcare/DevelopmentalScale.kt")
        assertTrue(file.exists(), "找不到量表源文件：${file.absolutePath}")
        val source = file.readText()
        // 拼接前缀，避免本检查自身的字面量命中「新增文件零 jOOQ / 零 Vert.x」的 rg 门禁。
        val jooqPackage = "org." + "jooq"
        val vertxPackage = "io." + "vertx"
        assertFalse(source.contains(jooqPackage), "A1 引擎不得依赖 jOOQ")
        assertFalse(source.contains(vertxPackage), "A1 引擎不得依赖 Vert.x")
    }

    // ========================================================================
    //  适龄条目
    // ========================================================================

    @Test
    fun `适龄条目随月龄变化`() {
        val band0 = DevelopmentalScaleCatalog.ageAppropriateItems(scale, 0)
        assertEquals(listOf("GM_HEAD_CONTROL", "FM_GRASP", "LA_VOICE"), band0.map { it.code })
        assertEquals(band0.map { it.code }, DevelopmentalScaleCatalog.ageAppropriateItems(scale, 6).map { it.code })

        assertEquals(
            listOf("GM_SIT", "FM_TRANSFER", "LA_BABBLE"),
            DevelopmentalScaleCatalog.ageAppropriateItems(scale, 7).map { it.code },
        )
        assertEquals(
            listOf("GM_WALK", "FM_STACK", "LA_WORD"),
            DevelopmentalScaleCatalog.ageAppropriateItems(scale, 18).map { it.code },
        )
        assertEquals(
            listOf("GM_JUMP", "GM_BALANCE", "FM_DRAW", "LA_SENTENCE", "LA_UNDERSTAND"),
            DevelopmentalScaleCatalog.ageAppropriateItems(scale, 36).map { it.code },
        )
    }

    // ========================================================================
    //  判读三档
    // ========================================================================

    @Test
    fun `达标率不低于零点九判正常`() {
        // 36 月满分为 10：9 分达标率恰好 0.90 → 正常（验证含等号边界）。
        val result = score(36, answersOf("GM_JUMP" to 1, "GM_BALANCE" to 2, "FM_DRAW" to 2, "LA_SENTENCE" to 2, "LA_UNDERSTAND" to 2))
        assertEquals(9, result.totalScore)
        assertEquals(10, result.maxTotalScore)
        assertEquals(DevelopmentalClassification.NORMAL, result.classification)
        assertTrue(result.classification in DevelopmentalClassification.all)
    }

    @Test
    fun `达标率介于零点七与零点九判可疑`() {
        // 0.80
        val highSuspect = score(36, answersOf("GM_JUMP" to 1, "GM_BALANCE" to 1, "FM_DRAW" to 2, "LA_SENTENCE" to 2, "LA_UNDERSTAND" to 2))
        assertEquals(8, highSuspect.totalScore)
        assertEquals(DevelopmentalClassification.SUSPECT, highSuspect.classification)

        // 0.70 恰好命中下界（验证含等号边界）。
        val lowSuspect = score(36, answersOf("GM_JUMP" to 1, "GM_BALANCE" to 1, "FM_DRAW" to 1, "LA_SENTENCE" to 2, "LA_UNDERSTAND" to 2))
        assertEquals(7, lowSuspect.totalScore)
        assertEquals(DevelopmentalClassification.SUSPECT, lowSuspect.classification)
    }

    @Test
    fun `达标率低于零点七判异常`() {
        val result = score(36, answersOf("GM_JUMP" to 1, "GM_BALANCE" to 1, "FM_DRAW" to 1, "LA_SENTENCE" to 1, "LA_UNDERSTAND" to 2))
        assertEquals(6, result.totalScore)
        assertEquals(DevelopmentalClassification.ABNORMAL, result.classification)
    }

    @Test
    fun `判读切点为可见常量`() {
        assertEquals(0.90, DevelopmentalScoring.NORMAL_THRESHOLD)
        assertEquals(0.70, DevelopmentalScoring.SUSPECT_THRESHOLD)
        assertTrue(DevelopmentalScoring.SUSPECT_THRESHOLD < DevelopmentalScoring.NORMAL_THRESHOLD)
    }

    // ========================================================================
    //  领域分 / 满分 / 触发项 / 回显
    // ========================================================================

    @Test
    fun `领域分与领域满分正确`() {
        // GM: GM_JUMP+GM_BALANCE=1+1；FM: FM_DRAW=2；LA: LA_SENTENCE+LA_UNDERSTAND=2+2。
        val result = score(36, answersOf("GM_JUMP" to 1, "GM_BALANCE" to 1, "FM_DRAW" to 2, "LA_SENTENCE" to 2, "LA_UNDERSTAND" to 2))
        assertEquals(mapOf("GM" to 2, "FM" to 2, "LA" to 4), result.domainScores)
        assertEquals(mapOf("GM" to 4, "FM" to 2, "LA" to 4), result.domainMaxScores)
        assertEquals(8, result.totalScore)
        assertEquals(10, result.maxTotalScore)
        assertEquals(mapOf("GM" to "大运动", "FM" to "精细动作", "LA" to "语言"), result.domainNames)
    }

    @Test
    fun `触发项为得最低分的适龄条目且升序`() {
        // 0–6 月三条：GM_HEAD_CONTROL=0、FM_GRASP=1、LA_VOICE=0 → 触发 GM_HEAD_CONTROL 与 LA_VOICE。
        val result = score(3, answersOf("GM_HEAD_CONTROL" to 0, "FM_GRASP" to 1, "LA_VOICE" to 0))
        assertEquals(listOf("GM_HEAD_CONTROL", "LA_VOICE"), result.triggeredItems)
        assertEquals(1, result.totalScore)
        assertEquals(6, result.maxTotalScore)
        assertEquals(DevelopmentalClassification.ABNORMAL, result.classification)
    }

    @Test
    fun `回显条目按适龄顺序且分值一致`() {
        val answers = answersOf("GM_JUMP" to 2, "GM_BALANCE" to 1, "FM_DRAW" to 0, "LA_SENTENCE" to 2, "LA_UNDERSTAND" to 0)
        val result = score(36, answers)
        assertEquals(listOf("GM_JUMP", "GM_BALANCE", "FM_DRAW", "LA_SENTENCE", "LA_UNDERSTAND"), result.answeredItems.keys.toList())
        assertEquals(answers, result.answeredItems)
        assertEquals(DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE, result.scaleCode)
        assertEquals("0.1", result.scaleVersion)
        assertEquals(36, result.ageMonths)
    }

    @Test
    fun `零分项全部计入触发项`() {
        val result = score(3, answersOf("GM_HEAD_CONTROL" to 0, "FM_GRASP" to 0, "LA_VOICE" to 0))
        assertEquals(listOf("FM_GRASP", "GM_HEAD_CONTROL", "LA_VOICE"), result.triggeredItems)
        assertEquals(0, result.totalScore)
        assertEquals(DevelopmentalClassification.ABNORMAL, result.classification)
    }

    // ========================================================================
    //  非法输入
    // ========================================================================

    @Test
    fun `未知量表报错`() {
        val error = assertThrows<IllegalArgumentException> {
            DevelopmentalScoring.score("NO_SUCH_SCALE", 3, emptyMap())
        }
        assertTrue(error.message!!.contains("unknown scale_code"))
        assertTrue(error.message!!.contains("NO_SUCH_SCALE"))
        assertNull(DevelopmentalScaleCatalog.find("NO_SUCH_SCALE"))
        assertNotNull(DevelopmentalScaleCatalog.find(DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE))
    }

    @Test
    fun `月龄越界报错`() {
        listOf(-1, 61).forEach { age ->
            val error = assertThrows<IllegalArgumentException> {
                DevelopmentalScoring.score(DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE, age, emptyMap())
            }
            assertTrue(error.message!!.contains("age_months"), "月龄 $age 的报错应含 age_months：${error.message}")
        }
    }

    @Test
    fun `缺项报错且提示条目编码`() {
        val error = assertThrows<IllegalArgumentException> {
            // 0–6 月适龄三条，缺 LA_VOICE。
            score(3, answersOf("GM_HEAD_CONTROL" to 1, "FM_GRASP" to 1))
        }
        assertTrue(error.message!!.contains("missing"))
        assertTrue(error.message!!.contains("LA_VOICE"))
    }

    @Test
    fun `多余或非适龄条目报错且提示条目编码`() {
        val error = assertThrows<IllegalArgumentException> {
            // 3 月龄却提交 7–12 月的 GM_SIT。
            score(3, answersOf("GM_HEAD_CONTROL" to 1, "FM_GRASP" to 1, "LA_VOICE" to 1, "GM_SIT" to 1))
        }
        assertTrue(error.message!!.contains("unexpected"))
        assertTrue(error.message!!.contains("GM_SIT"))
    }

    @Test
    fun `非法分值报错且提示条目编码`() {
        val error = assertThrows<IllegalArgumentException> {
            score(3, answersOf("GM_HEAD_CONTROL" to 9, "FM_GRASP" to 1, "LA_VOICE" to 1))
        }
        assertTrue(error.message!!.contains("invalid"))
        assertTrue(error.message!!.contains("GM_HEAD_CONTROL"))
    }

    @Test
    fun `选项不连续的条目拒绝中间值`() {
        // LA_UNDERSTAND 仅接受 0 / 2，1 非法。
        val error = assertThrows<IllegalArgumentException> {
            score(36, answersOf("GM_JUMP" to 2, "GM_BALANCE" to 2, "FM_DRAW" to 2, "LA_SENTENCE" to 2, "LA_UNDERSTAND" to 1))
        }
        assertTrue(error.message!!.contains("invalid"))
        assertTrue(error.message!!.contains("LA_UNDERSTAND"))
    }

    @Test
    fun `空作答在适龄条目非空时按缺项报错`() {
        val error = assertThrows<IllegalArgumentException> {
            score(3, emptyMap())
        }
        assertTrue(error.message!!.contains("missing"))
    }

    // ========================================================================
    //  工具
    // ========================================================================

    private fun score(ageMonths: Int, answers: Map<String, Int>): DevelopmentalScoreResult =
        DevelopmentalScoring.score(DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE, ageMonths, answers)

    private fun answersOf(vararg pairs: Pair<String, Int>): Map<String, Int> =
        linkedMapOf(*pairs)
}
