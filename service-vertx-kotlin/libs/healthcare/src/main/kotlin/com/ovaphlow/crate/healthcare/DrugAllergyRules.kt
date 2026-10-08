package com.ovaphlow.crate.healthcare

/**
 * D2（053）— 药物过敏/禁忌检查的**静态占位规则常量**（纯逻辑：无 DB / 无 jOOQ / 无 Vert.x）。
 *
 * ## 性质与边界（务必先读）
 * 本文件是**应用内置的静态占位规则表**，不是药典、不是药物相互作用库、也不来自任何采购的
 * 临床知识库（053 §6 明确「不做用药知识库/相互作用库采购」）。规则条目仅用于打通
 * 「目录药品 → 过敏史精确匹配 / 交叉反应组 / 年龄禁忌」的检查链路，**不得作为临床决策依据**。
 * 正式规则（交叉反应分组、年龄下限、禁用人群）待产品/药事确认后填入；届时只替换
 * [crossReactionGroups] 与 [ageContraindications] 的**数据常量**，
 * [DrugAllergyChecker] 的引擎与判定语义保持不变（053 §4 D2、§11 裁定 4、§12 R3）。
 *
 * ## 与持久化的关系
 * 本文件不读取 `healthcare.patient_allergies`（D1 表）；调用方（D3 开嘱接线）负责把该表投影为
 * [AllergyRecord] 后传入 [DrugAllergyChecker.check]。因此本文件零持久化依赖。
 *
 * ## 匹配口径
 *  - [CrossReactionGroup.members] 与 [AgeContraindication.drugKeywords] 均为**关键字**，
 *    对药品名/编码做 trim + 忽略大小写的**包含**匹配（如药品名「阿莫西林胶囊」命中关键字「阿莫西林」）。
 *  - 过敏史侧同样对 `allergen_name` / `allergen_code` 做关键字包含匹配。
 */
object DrugAllergyRules {

    /**
     * 一个交叉反应组。
     *
     * @param code    组编码（稳定标识，用作提醒的 `matched`，如 `PENICILLIN_CEPHALOSPORIN`）。
     * @param name    组中文名（提醒文案用）。
     * @param members 组内成员关键字（药品通用名/编码关键字）。
     */
    data class CrossReactionGroup(
        val code: String,
        val name: String,
        val members: Set<String>,
    )

    /**
     * 一条年龄禁忌。
     *
     * @param code          规则编码（稳定标识，用作提醒的 `matched`）。
     * @param name          规则中文名。
     * @param drugKeywords  药品关键字（药品名/编码包含命中即视为适用该规则）。
     * @param maxAgeMonths  患者月龄 **<** 该值时触发（18 岁 = 216 月；8 岁 = 96 月）。
     * @param reason        人类可读的中文禁忌说明。
     */
    data class AgeContraindication(
        val code: String,
        val name: String,
        val drugKeywords: Set<String>,
        val maxAgeMonths: Int,
        val reason: String,
    )

    /**
     * 交叉反应组（占位规则，正式分组由产品/药事确认）。
     *
     * `PENICILLIN_CEPHALOSPORIN`：青霉素类 ↔ 头孢类。组内任一成员命中患者过敏史时，
     * 组内其它成员也提醒（D2 判定语义 2）。members 混合中文通用名与英文/编码关键字，
     * 以覆盖目录名与编码两种命中路径。
     */
    val crossReactionGroups: List<CrossReactionGroup> = listOf(
        CrossReactionGroup(
            code = "PENICILLIN_CEPHALOSPORIN",
            name = "青霉素类 ↔ 头孢类交叉过敏",
            members = setOf(
                // 青霉素类
                "青霉素", "阿莫西林", "氨苄西林", "哌拉西林", "苯唑西林",
                "penicillin", "amoxicillin", "ampicillin", "piperacillin",
                // 头孢类
                "头孢", "头孢菌素", "cephalosporin", "ceph",
            ),
        ),
    )

    /**
     * 年龄禁忌（**占位规则，正式由产品/药事确认**）。
     *
     *  - `QUINOLONE_CHILD`：喹诺酮类，18 岁以下一般不推荐（软骨发育顾虑）。
     *  - `TETRACYCLINE_CHILD`：四环素类，8 岁以下不推荐（牙齿黄染/釉质发育不良）。
     *
     * 上述阈值与人群为通用儿科用药常识的占位描述，**非**正式说明书口径，不得直接用于临床。
     */
    val ageContraindications: List<AgeContraindication> = listOf(
        AgeContraindication(
            code = "QUINOLONE_CHILD",
            name = "喹诺酮类儿童慎用",
            drugKeywords = setOf(
                "左氧氟沙星", "环丙沙星", "诺氟沙星", "莫西沙星", "氧氟沙星",
                "quinolone", "ciprofloxacin", "levofloxacin", "moxifloxacin",
            ),
            maxAgeMonths = 216,
            reason = "喹诺酮类可能影响儿童软骨发育，一般不推荐 18 岁以下使用",
        ),
        AgeContraindication(
            code = "TETRACYCLINE_CHILD",
            name = "四环素类儿童慎用",
            drugKeywords = setOf(
                "四环素", "多西环素", "米诺环素", "土霉素",
                "tetracycline", "doxycycline", "minocycline",
            ),
            maxAgeMonths = 96,
            reason = "四环素类可致牙齿黄染与釉质发育不良，8 岁以下不推荐",
        ),
    )
}
