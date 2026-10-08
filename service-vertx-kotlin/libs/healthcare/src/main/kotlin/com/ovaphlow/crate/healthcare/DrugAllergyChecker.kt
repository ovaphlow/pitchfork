package com.ovaphlow.crate.healthcare

/**
 * 一条患者过敏史（D1 `healthcare.patient_allergies` 的投影）。
 *
 * `status=已停用` 的过敏史**不参与判定**（D2 判定语义 6）。本类型只承载判定所需字段，
 * 不含 `patient_id` / `operator` 等身份或审计字段，避免把 PHI 带入纯逻辑层。
 *
 * @param allergenType 过敏原类型：药物 / 食物 / 其他。
 * @param allergenName 过敏原名称（如「青霉素」）。
 * @param allergenCode 过敏原编码（可空，如 ATC 编码）。
 * @param severity     严重度：一般 / 严重。
 * @param status       状态：有效 / 已停用。
 */
data class AllergyRecord(
    val allergenType: String,
    val allergenName: String,
    val allergenCode: String?,
    val severity: String,
    val status: String,
)

/**
 * 一条药物过敏/禁忌提醒（D3 开嘱链路消费）。
 *
 * @param severity 提醒严重度：一般 / 严重（分级提醒，默认非阻塞；053 §11 裁定 4）。
 * @param reason   人类可读的中文说明。
 * @param source   来源：过敏史 / 交叉反应 / 年龄禁忌。
 * @param matched  命中的过敏原或规则关键字（脱敏，不含身份证号等 PHI）。
 */
data class DrugAllergyWarning(
    val severity: String,
    val reason: String,
    val source: String,
    val matched: String,
)

/**
 * D2（053）— 药物过敏/禁忌检查引擎（**纯函数：无 DB / 无 jOOQ / 无 Vert.x**）。
 *
 * 输入目录药品（名称/编码）、患者有效过敏史、患者月龄，输出分级提醒列表。
 * 规则数据来自 [DrugAllergyRules] 的**静态占位常量**（非药典/相互作用库，053 §6、§12 R3）。
 *
 * ## 判定语义（053 §4 D2）
 *  1. **精确匹配**（`source=过敏史`）：药品名或编码与某条 `allergen_name` / `allergen_code`
 *     规范化后**相等**（名称 trim + 忽略大小写；编码 trim 后**精确**，区分大小写）。
 *     命中 severity = 该条过敏史的 severity。
 *  2. **交叉反应**（`source=交叉反应`）：药品名/编码命中某交叉反应组成员，且患者过敏史命中
 *     **同组另一个成员** → severity = `严重`；`matched` 为该组 `code`。
 *  3. **年龄禁忌**（`source=年龄禁忌`）：`ageMonths != null` 且 `ageMonths < maxAgeMonths`
 *     且药品名/编码命中 `drugKeywords` → severity = `严重`；`matched` 为该规则 `code`。
 *  4. **去重与排序**：同一 `(source, matched)` 只输出一次；排序稳定，先 `严重` 后 `一般`，
 *     再按 `source`、`matched` 升序。同 `(source, matched)` 出现不同 severity 时保留更严重者。
 *  5. **零误报与独立触发**（2026-10-06 调度者裁定修正）：
 *     - **过敏史类**规则（`过敏史` / `交叉反应`）仅在有效过敏史命中时产出；`allergies` 为空 → 这两类零产出。
 *     - **年龄禁忌**规则只要 `ageMonths != null` 且药品名/编码命中 `drugKeywords` 即产出，
 *       **与 `allergies` 是否为空无关**（不得因「无过敏史」而漏掉年龄禁忌）。
 *     - 全局零误报：药品名与编码均为空、或药品无任何命中 → 返回空列表；
 *       **禁止**因为「患者有任意过敏史」就对无关药报警。
 *  6. `status=已停用` 的过敏史一律忽略。
 *
 * 本引擎**不校验药品是否在目录**（目录 fail-closed 校验仍由 `DrugCatalogPort` 负责，053 §6），
 * **不落库、不生成标识**；D3 负责接线与二次确认。
 */
object DrugAllergyChecker {

    /** 来源：过敏史精确匹配。 */
    const val SOURCE_ALLERGY: String = "过敏史"

    /** 来源：交叉反应。 */
    const val SOURCE_CROSS_REACTION: String = "交叉反应"

    /** 来源：年龄禁忌。 */
    const val SOURCE_AGE_CONTRAINDICATION: String = "年龄禁忌"

    /** 严重度：一般。 */
    const val SEVERITY_GENERAL: String = "一般"

    /** 严重度：严重。 */
    const val SEVERITY_SEVERE: String = "严重"

    /** 已停用状态（不参与判定）。 */
    private const val STATUS_INACTIVE: String = "已停用"

    /**
     * 执行检查。
     *
     * @param drugName  目录药品名（可空；空则不判名称路径）。
     * @param drugCode  目录药品编码（可空；空则不判编码路径）。
     * @param allergies 患者过敏史（`status=已停用` 的忽略；为空时仅跳过过敏史/交叉反应，不影响年龄禁忌）。
     * @param ageMonths 患者月龄（可空；空则跳过年龄禁忌）。
     * @return 提醒列表；无命中返回空列表，绝不因「存在过敏史」而误报，也不因「无过敏史」而漏掉年龄禁忌。
     */
    fun check(
        drugName: String?,
        drugCode: String?,
        allergies: List<AllergyRecord>,
        ageMonths: Int?,
    ): List<DrugAllergyWarning> {
        val normalizedName = normalizeName(drugName)
        val normalizedCode = normalizeCode(drugCode)

        // 全局零误报：药品名与编码均空 → 无任何可判定输入。
        if (normalizedName == null && normalizedCode == null) {
            return emptyList()
        }

        // 语义 6：忽略已停用过敏史（为空时不影响下方年龄禁忌的独立判定）。
        val activeAllergies = allergies.filter { normalizeStatus(it.status) != STATUS_INACTIVE }

        val drugLabel = drugName?.trim()?.takeIf(String::isNotEmpty)
            ?: drugCode?.trim()?.takeIf(String::isNotEmpty)
            ?: "未知药品"

        val warnings = mutableListOf<DrugAllergyWarning>()

        // ---- 语义 1：精确匹配（名称忽略大小写；编码精确） ----
        activeAllergies.forEach { allergy ->
            val allergenName = normalizeName(allergy.allergenName)
            val allergenCode = normalizeCode(allergy.allergenCode)
            val nameHit = normalizedName != null && allergenName != null && normalizedName == allergenName
            val codeHit = normalizedCode != null && allergenCode != null && normalizedCode == allergenCode
            if (nameHit || codeHit) {
                val severity = normalizeSeverity(allergy.severity)
                val matched = if (nameHit) allergy.allergenName.trim() else allergy.allergenCode!!.trim()
                warnings += DrugAllergyWarning(
                    severity = severity,
                    reason = "患者过敏史记录对「${allergy.allergenName.trim()}」过敏" +
                        "（严重度：$severity），本次药品「$drugLabel」与之匹配",
                    source = SOURCE_ALLERGY,
                    matched = matched,
                )
            }
        }

        // ---- 语义 2：交叉反应（药品命中成员 A，过敏史命中同组另一成员 B，A != B） ----
        DrugAllergyRules.crossReactionGroups.forEach { group ->
            val drugMembers = matchedKeywords(listOf(drugName, drugCode), group.members)
            if (drugMembers.isEmpty()) return@forEach
            val allergyMembers = activeAllergies
                .flatMap { matchedKeywords(listOf(it.allergenName, it.allergenCode), group.members) }
                .toSet()
            val crossHit = drugMembers.any { drugMember ->
                allergyMembers.any { allergyMember -> allergyMember != drugMember }
            }
            if (crossHit) {
                warnings += DrugAllergyWarning(
                    severity = SEVERITY_SEVERE,
                    reason = "药品「$drugLabel」与患者过敏史存在交叉过敏风险" +
                        "（交叉反应组：${group.name}）",
                    source = SOURCE_CROSS_REACTION,
                    matched = group.code,
                )
            }
        }

        // ---- 语义 3：年龄禁忌 ----
        if (ageMonths != null) {
            DrugAllergyRules.ageContraindications.forEach { rule ->
                val hit = matchedKeywords(listOf(drugName, drugCode), rule.drugKeywords).isNotEmpty()
                if (ageMonths < rule.maxAgeMonths && hit) {
                    warnings += DrugAllergyWarning(
                        severity = SEVERITY_SEVERE,
                        reason = "患者月龄 $ageMonths 月低于「${rule.name}」适用下限" +
                            "（${rule.maxAgeMonths} 月）；${rule.reason}",
                        source = SOURCE_AGE_CONTRAINDICATION,
                        matched = rule.code,
                    )
                }
            }
        }

        // ---- 语义 4：排序（严重在前，再按 source/matched）+ 去重（同一 source+matched 一次） ----
        val severityRank = mapOf(SEVERITY_SEVERE to 0, SEVERITY_GENERAL to 1)
        return warnings
            .sortedWith(
                compareBy(
                    { severityRank[it.severity] ?: 2 },
                    { it.source },
                    { it.matched },
                ),
            )
            .distinctBy { it.source to it.matched }
    }

    // ========================================================================
    //  规范化与匹配工具
    // ========================================================================

    /** 名称规范化：trim + 转小写；空白 → null。 */
    private fun normalizeName(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    /** 编码规范化：仅 trim（编码**精确**，区分大小写）；空白 → null。 */
    private fun normalizeCode(value: String?): String? =
        value?.trim()?.takeIf(String::isNotEmpty)

    /** 状态规范化：trim；空白 → null。 */
    private fun normalizeStatus(value: String?): String? =
        value?.trim()?.takeIf(String::isNotEmpty)

    /** 严重度规范化：仅「严重」为严重，其余（含脏值）按「一般」处理。 */
    private fun normalizeSeverity(value: String?): String =
        if (value?.trim() == SEVERITY_SEVERE) SEVERITY_SEVERE else SEVERITY_GENERAL

    /**
     * 关键字包含匹配（trim + 忽略大小写）。
     *
     * @param values   待匹配值（药品名/编码，或过敏史名称/编码）。
     * @param keywords 关键字集合。
     * @return 命中的关键字集合（保留原关键字大小写）。
     */
    private fun matchedKeywords(values: List<String?>, keywords: Set<String>): Set<String> {
        val loweredValues = values
            .mapNotNull { it?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
        if (loweredValues.isEmpty()) return emptySet()
        return keywords.filterTo(mutableSetOf()) { keyword ->
            val normalizedKeyword = keyword.trim().lowercase()
            normalizedKeyword.isNotEmpty() && loweredValues.any { it.contains(normalizedKeyword) }
        }
    }
}
