package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * D2（053）— 药物过敏/禁忌检查引擎测试（**不访问数据库**）。
 *
 * 锁定验收口径（053 §4 D2、§8 验收 7、§11 裁定 4、§12 R3）：
 *  - 精确匹配（名称忽略大小写/空白；编码 trim 后精确）；
 *  - 交叉反应（命中同组另一成员才触发，组内同一成员不触发）；
 *  - 年龄禁忌（月龄 < 阈值且药名命中，含边界；**独立于过敏史**触发）；
 *  - 已停用过敏史忽略、无过敏史时过敏史/交叉反应类零误报、空药品名零误报；
 *  - 去重与稳定排序（先严重后一般，再按 source/matched）；
 *  - 占位规则常量结构自检 + 引擎源文件零 jOOQ / 零 Vert.x 依赖。
 */
class DrugAllergyCheckerTest {

    // ========================================================================
    //  规则常量自检
    // ========================================================================

    @Test
    fun `交叉反应规则非空且结构合法`() {
        assertTrue(DrugAllergyRules.crossReactionGroups.isNotEmpty(), "至少一组交叉反应")
        DrugAllergyRules.crossReactionGroups.forEach { group ->
            assertTrue(group.code.isNotBlank(), "组编码非空")
            assertTrue(group.name.isNotBlank(), "组名非空")
            assertTrue(group.members.isNotEmpty(), "${group.code} 成员非空")
            assertTrue(group.members.all { it.isNotBlank() }, "${group.code} 成员关键字非空")
        }
        val penicillin = DrugAllergyRules.crossReactionGroups.single { it.code == "PENICILLIN_CEPHALOSPORIN" }
        assertTrue("青霉素" in penicillin.members)
        assertTrue("头孢" in penicillin.members)
    }

    @Test
    fun `年龄禁忌规则非空且阈值合法`() {
        assertTrue(DrugAllergyRules.ageContraindications.isNotEmpty(), "至少一条年龄禁忌")
        DrugAllergyRules.ageContraindications.forEach { rule ->
            assertTrue(rule.code.isNotBlank(), "规则编码非空")
            assertTrue(rule.name.isNotBlank(), "规则名非空")
            assertTrue(rule.drugKeywords.isNotEmpty(), "${rule.code} 关键字非空")
            assertTrue(rule.maxAgeMonths > 0, "${rule.code} 月龄阈值必须为正")
            assertTrue(rule.reason.isNotBlank(), "${rule.code} 说明非空")
        }
    }

    @Test
    fun `引擎源文件不依赖 jOOQ 与 Vertx`() {
        val base = "src/main/kotlin/com/ovaphlow/crate/healthcare/"
        listOf("DrugAllergyRules.kt", "DrugAllergyChecker.kt").forEach { name ->
            val file = File(base + name)
            assertTrue(file.exists(), "找不到引擎源文件：${file.absolutePath}")
            val source = file.readText()
            // 拼接前缀，避免本检查自身的字面量命中「新增文件零 jOOQ / 零 Vert.x」的 rg 门禁。
            val jooqPackage = "org." + "jooq"
            val vertxPackage = "io." + "vertx"
            assertFalse(source.contains(jooqPackage), "$name 不得依赖 jOOQ")
            assertFalse(source.contains(vertxPackage), "$name 不得依赖 Vert.x")
        }
    }

    // ========================================================================
    //  语义 1：精确匹配
    // ========================================================================

    @Test
    fun `精确匹配药品名并返回过敏史严重度`() {
        val result = DrugAllergyChecker.check("青霉素", null, listOf(allergy("青霉素", severity = "严重")), null)
        assertEquals(1, result.size)
        val warning = result.single()
        assertEquals(DrugAllergyChecker.SOURCE_ALLERGY, warning.source)
        assertEquals(DrugAllergyChecker.SEVERITY_SEVERE, warning.severity)
        assertEquals("青霉素", warning.matched)
        assertTrue(warning.reason.contains("青霉素"))
    }

    @Test
    fun `精确匹配名称忽略大小写与空白`() {
        val allergies = listOf(allergy("Amoxicillin", severity = "一般"))
        listOf("amoxicillin", "  AMOXICILLIN  ").forEach { drugName ->
            val result = DrugAllergyChecker.check(drugName, null, allergies, null)
            assertEquals(1, result.size, "药品名「$drugName」应命中")
            assertEquals("Amoxicillin", result.single().matched)
            assertEquals(DrugAllergyChecker.SEVERITY_GENERAL, result.single().severity)
        }
    }

    @Test
    fun `精确匹配编码且编码区分大小写`() {
        val allergies = listOf(allergy("阿莫西林", code = "J01CA04"))
        // 相同编码（含空白 trim）命中。
        val hit = DrugAllergyChecker.check(null, " J01CA04 ", allergies, null)
        assertEquals(1, hit.size)
        assertEquals("J01CA04", hit.single().matched)
        assertEquals(DrugAllergyChecker.SOURCE_ALLERGY, hit.single().source)
        // 编码精确：大小写不同不命中。
        assertTrue(DrugAllergyChecker.check(null, "j01ca04", allergies, null).isEmpty())
    }

    @Test
    fun `药品名与编码分别命中输出两条精确提醒并按命中排序`() {
        val allergies = listOf(
            allergy("青霉素", code = null),
            allergy("其他", code = "J01CA04"),
        )
        val result = DrugAllergyChecker.check("青霉素", "J01CA04", allergies, null)
        assertEquals(2, result.size)
        assertEquals(listOf("J01CA04", "青霉素"), result.map { it.matched })
        assertTrue(result.all { it.source == DrugAllergyChecker.SOURCE_ALLERGY })
        assertTrue(result.all { it.severity == DrugAllergyChecker.SEVERITY_GENERAL })
    }

    // ========================================================================
    //  语义 2：交叉反应
    // ========================================================================

    @Test
    fun `交叉反应命中判严重`() {
        // 药品「头孢克肟」命中头孢类成员；过敏史「青霉素」命中同组另一成员 → 交叉。
        val result = DrugAllergyChecker.check("头孢克肟", null, listOf(allergy("青霉素")), null)
        assertEquals(1, result.size)
        val warning = result.single()
        assertEquals(DrugAllergyChecker.SOURCE_CROSS_REACTION, warning.source)
        assertEquals(DrugAllergyChecker.SEVERITY_SEVERE, warning.severity)
        assertEquals("PENICILLIN_CEPHALOSPORIN", warning.matched)
        assertTrue(warning.reason.contains("交叉"))
    }

    @Test
    fun `交叉反应同组同一成员不触发`() {
        // 药品名与过敏史命中同一成员（阿莫西林），精确不等且非「另一成员」→ 零提醒。
        assertTrue(DrugAllergyChecker.check("阿莫西林胶囊", null, listOf(allergy("阿莫西林")), null).isEmpty())
    }

    @Test
    fun `交叉反应同组但过敏史不在组内不误报`() {
        assertTrue(DrugAllergyChecker.check("头孢克肟", null, listOf(allergy("花生", type = "食物")), null).isEmpty())
    }

    // ========================================================================
    //  语义 3：年龄禁忌
    // ========================================================================

    @Test
    fun `年龄禁忌命中且边界为小于阈值`() {
        val allergies = listOf(allergy("花生", type = "食物"))
        // 60 月远低于 216 月 → 命中。
        val hit = DrugAllergyChecker.check("左氧氟沙星", null, allergies, 60)
        assertEquals(1, hit.size)
        val warning = hit.single()
        assertEquals(DrugAllergyChecker.SOURCE_AGE_CONTRAINDICATION, warning.source)
        assertEquals(DrugAllergyChecker.SEVERITY_SEVERE, warning.severity)
        assertEquals("QUINOLONE_CHILD", warning.matched)
        assertTrue(warning.reason.contains("60"))
        // 215 月（阈值前一月）命中；216 月（阈值当月）不命中 → 严格小于。
        assertEquals(1, DrugAllergyChecker.check("左氧氟沙星", null, allergies, 215).size)
        assertTrue(DrugAllergyChecker.check("左氧氟沙星", null, allergies, 216).isEmpty())
    }

    @Test
    fun `年龄禁忌月龄缺失或达标不触发`() {
        val allergies = listOf(allergy("花生", type = "食物"))
        assertTrue(DrugAllergyChecker.check("左氧氟沙星", null, allergies, null).isEmpty())
        assertTrue(DrugAllergyChecker.check("左氧氟沙星", null, allergies, 216).isEmpty())
    }

    @Test
    fun `年龄禁忌药名未命中不误报`() {
        val allergies = listOf(allergy("花生", type = "食物"))
        assertTrue(DrugAllergyChecker.check("阿莫西林", null, allergies, 10).isEmpty())
    }

    @Test
    fun `年龄禁忌不因患者有其它过敏史而误报`() {
        // 有过敏史，但药品既非过敏原也无年龄禁忌 → 零提醒。
        assertTrue(DrugAllergyChecker.check("维生素C", null, listOf(allergy("青霉素")), 10).isEmpty())
    }

    // ========================================================================
    //  语义 5/6：零误报与已停用
    // ========================================================================

    @Test
    fun `无过敏史且非禁忌药品零误报`() {
        assertTrue(DrugAllergyChecker.check("青霉素", null, emptyList(), 10).isEmpty())
        assertTrue(DrugAllergyChecker.check("维生素C", null, emptyList(), 10).isEmpty())
    }

    @Test
    fun `无过敏史时年龄禁忌独立触发`() {
        // 修正语义：年龄禁忌与 allergies 是否为空无关。
        val result = DrugAllergyChecker.check("左氧氟沙星", null, emptyList(), 10)
        assertEquals(1, result.size)
        val warning = result.single()
        assertEquals(DrugAllergyChecker.SOURCE_AGE_CONTRAINDICATION, warning.source)
        assertEquals(DrugAllergyChecker.SEVERITY_SEVERE, warning.severity)
        assertEquals("QUINOLONE_CHILD", warning.matched)
    }

    @Test
    fun `无过敏史且超过年龄阈值零产出`() {
        assertTrue(DrugAllergyChecker.check("左氧氟沙星", null, emptyList(), 216).isEmpty())
        assertTrue(DrugAllergyChecker.check("四环素", null, emptyList(), 96).isEmpty())
    }

    @Test
    fun `无过敏史时命中交叉反应组不误报`() {
        // 药品命中交叉反应组成员，但无过敏史 → 过敏史/交叉反应两类均零产出。
        assertTrue(DrugAllergyChecker.check("头孢克肟", null, emptyList(), 300).isEmpty())
        assertTrue(DrugAllergyChecker.check("阿莫西林", null, emptyList(), 300).isEmpty())
    }

    @Test
    fun `空药品名与编码零误报`() {
        val allergies = listOf(allergy("青霉素", severity = "严重"))
        assertTrue(DrugAllergyChecker.check(null, null, allergies, 10).isEmpty())
        assertTrue(DrugAllergyChecker.check("", "   ", allergies, 10).isEmpty())
    }

    @Test
    fun `已停用过敏史被忽略`() {
        val stopped = listOf(allergy("青霉素", severity = "严重", status = "已停用"))
        assertTrue(DrugAllergyChecker.check("青霉素", null, stopped, null).isEmpty())
        assertTrue(DrugAllergyChecker.check("头孢克肟", null, stopped, null).isEmpty())
    }

    @Test
    fun `有效与已停用混合时只判有效`() {
        val allergies = listOf(
            allergy("青霉素", severity = "严重", status = "已停用"),
            allergy("花生", type = "食物", status = "有效"),
        )
        assertTrue(DrugAllergyChecker.check("青霉素", null, allergies, null).isEmpty())
    }

    // ========================================================================
    //  语义 4：去重与排序
    // ========================================================================

    @Test
    fun `同一来源同一命中去重且保留更严重`() {
        val allergies = listOf(
            allergy("青霉素", severity = "一般"),
            allergy("青霉素", severity = "严重"),
        )
        val result = DrugAllergyChecker.check("青霉素", null, allergies, null)
        assertEquals(1, result.size, "同一 (source, matched) 只输出一次")
        assertEquals(DrugAllergyChecker.SEVERITY_SEVERE, result.single().severity, "去重保留更严重者")
    }

    @Test
    fun `排序先严重再按来源与命中`() {
        val allergies = listOf(
            allergy("青霉素", severity = "一般"),
            allergy("头孢", severity = "一般"),
        )
        val result = DrugAllergyChecker.check("青霉素", null, allergies, null)
        assertEquals(2, result.size)
        // 严重（交叉反应）在前，一般（过敏史）在后。
        assertEquals(
            listOf(DrugAllergyChecker.SOURCE_CROSS_REACTION, DrugAllergyChecker.SOURCE_ALLERGY),
            result.map { it.source },
        )
        assertEquals(DrugAllergyChecker.SEVERITY_SEVERE, result.first().severity)
        assertEquals(DrugAllergyChecker.SEVERITY_GENERAL, result.last().severity)
    }

    @Test
    fun `无任何命中返回空列表`() {
        assertTrue(DrugAllergyChecker.check("维生素C", null, listOf(allergy("青霉素")), 300).isEmpty())
        assertTrue(DrugAllergyChecker.check(null, "CODE-9999", listOf(allergy("青霉素", code = "J01CA04")), null).isEmpty())
    }

    // ========================================================================
    //  工具
    // ========================================================================

    private fun allergy(
        name: String,
        code: String? = null,
        severity: String = "一般",
        status: String = "有效",
        type: String = "药物",
    ): AllergyRecord = AllergyRecord(
        allergenType = type,
        allergenName = name,
        allergenCode = code,
        severity = severity,
        status = status,
    )
}
