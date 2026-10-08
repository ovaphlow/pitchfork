package com.ovaphlow.crate.healthcare

/**
 * A1（053）— 儿童发育筛查量表目录与评分引擎（**纯逻辑：无 DB / 无 jOOQ / 无 Vert.x**）。
 *
 * ## 版权声明（务必先读）
 * 本文件内的量表编码、领域、条目提示语与选项文案均为**通用发育里程碑的原创占位描述**，
 * 仅用于打通「量表定义 → 按龄选条 → 领域/总合计分 → 判读」的引擎链路，**不是** ASQ-3、DST、
 * Gesell、丹佛（DDST）等任何受版权保护量表的条目，也不得作为临床诊断依据。
 * 正式量表内容（条目、常模、切点）待产品确认版权与标准后填入；届时只替换本文件的**数据常量**
 * [DevelopmentalScaleCatalog]，[DevelopmentalScoring] 的引擎与结构保持不变
 * （053 §1.1 A1、§11 裁定 1）。
 *
 * ## 结构
 *  - [DevelopmentalScaleCatalog.Scale]      一个量表（编码 / 版本 / 适用月龄区间 / 领域 / 条目）。
 *  - [DevelopmentalScaleCatalog.Domain]     领域（如大运动、精细动作、语言）。
 *  - [DevelopmentalScaleCatalog.Item]       条目（编码全局唯一、所属领域、提示语、适龄区间、取值选项）。
 *  - [DevelopmentalScaleCatalog.ItemOption] 取值选项（整数值 + 中文标签）。
 *
 * ## 占位量表包
 *  - 量表：`CHILD_DEV_PLACEHOLDER`（version `0.1`），适用 0–60 月。
 *  - 领域：`GM` 大运动 / `FM` 精细动作 / `LA` 语言。
 *  - 条目：14 条，按 0–6 / 7–12 / 13–24 / 25–60 月分档，覆盖 0–60 月无空档。
 *  - 选项：每条 2–3 个取值（多为 0/1/2，含一条 0/2）。
 */
object DevelopmentalScaleCatalog {

    /** 领域编码：大运动。 */
    const val DOMAIN_GROSS_MOTOR = "GM"

    /** 领域编码：精细动作。 */
    const val DOMAIN_FINE_MOTOR = "FM"

    /** 领域编码：语言。 */
    const val DOMAIN_LANGUAGE = "LA"

    /** 量表编码：占位量表。 */
    const val PLACEHOLDER_SCALE_CODE = "CHILD_DEV_PLACEHOLDER"

    /** 一个条目的取值选项（整数值 + 中文标签）。 */
    data class ItemOption(val value: Int, val label: String)

    /**
     * 一个发育筛查条目。
     *
     * @param code       条目编码，跨全部量表**全局唯一**。
     * @param domain     所属领域编码，须在该条目所属量表的 [Scale.domains] 内。
     * @param prompt     条目提示语（占位文案，勿抄录受版权量表）。
     * @param minMonths  适龄下界（含）。
     * @param maxMonths  适龄上界（含）。
     * @param options    取值选项（2–3 个，值互不相同）。
     */
    data class Item(
        val code: String,
        val domain: String,
        val prompt: String,
        val minMonths: Int,
        val maxMonths: Int,
        val options: List<ItemOption>,
    )

    /** 一个领域（编码 + 中文名）。 */
    data class Domain(val code: String, val name: String)

    /**
     * 一个量表。
     *
     * @param code       量表编码（全局唯一）。
     * @param version    量表版本。
     * @param name       中文名。
     * @param minMonths  适用月龄下界（含）。
     * @param maxMonths  适用月龄上界（含）。
     * @param domains    领域列表（顺序即落库/展示顺序）。
     * @param items      条目列表（顺序即回显/作答顺序）。
     */
    data class Scale(
        val code: String,
        val version: String,
        val name: String,
        val minMonths: Int,
        val maxMonths: Int,
        val domains: List<Domain>,
        val items: List<Item>,
    )

    /** 已注册量表（**常量基线**），按 `scale_code` 索引。 */
    val scales: Map<String, Scale> = listOf(placeholderScale()).associateBy { it.code }

    /**
     * 054 F2 内容表覆盖层（进程内缓存）：由 `BaseDataService` 在路由装配时与每次内容写入后刷新。
     *
     * **空 = 库为空或读失败 → 完全等价于常量基线**（052/053 已交付的筛查判读结果不变）。
     */
    @Volatile
    private var contentScales: Map<String, Scale> = emptyMap()

    /** 生效量表：覆盖层非空时按 `scale_code` 覆盖常量基线，否则就是常量基线。 */
    fun effectiveScales(): Map<String, Scale> =
        if (contentScales.isEmpty()) scales else scales + contentScales

    /** 按编码取量表（**读库优先、常量回落**）；不存在返回 `null`。 */
    fun find(code: String): Scale? = effectiveScales()[code]

    /** 054 F2：应用内容表加载结果；传空列表即回落常量基线（库空 / 读失败 / 测试重置）。 */
    internal fun applyContent(content: List<Scale>) {
        contentScales = if (content.isEmpty()) emptyMap() else content.associateBy { it.code }
    }

    /**
     * 该月龄的适龄条目（`minMonths <= ageMonths <= maxMonths`），保持量表定义顺序。
     *
     * @param scale     量表。
     * @param ageMonths 儿童月龄（可为任意整数，本函数不校验区间；区间校验见 [DevelopmentalScoring.score]）。
     */
    fun ageAppropriateItems(scale: Scale, ageMonths: Int): List<Item> =
        scale.items.filter { ageMonths >= it.minMonths && ageMonths <= it.maxMonths }

    // ========================================================================
    //  占位量表包（正式内容待产品填入；替换数据不影响引擎）
    // ========================================================================

    private fun placeholderScale(): Scale {
        val grossMotor = Domain(DOMAIN_GROSS_MOTOR, "大运动")
        val fineMotor = Domain(DOMAIN_FINE_MOTOR, "精细动作")
        val language = Domain(DOMAIN_LANGUAGE, "语言")
        return Scale(
            code = PLACEHOLDER_SCALE_CODE,
            version = "0.1",
            name = "儿童发育筛查占位量表（通用里程碑描述，非受版权保护量表）",
            minMonths = 0,
            maxMonths = 60,
            domains = listOf(grossMotor, fineMotor, language),
            items = listOf(
                // ---- 0–6 月 ----
                item(
                    "GM_HEAD_CONTROL", DOMAIN_GROSS_MOTOR,
                    "俯卧时能短暂抬头并左右转头", 0, 6,
                    triple("不能抬头", "能短暂抬头", "能稳定抬头并转头"),
                ),
                item(
                    "FM_GRASP", DOMAIN_FINE_MOTOR,
                    "能主动抓握递到手中的物品", 0, 6,
                    triple("不能抓握", "偶有抓握", "能稳定抓握"),
                ),
                item(
                    "LA_VOICE", DOMAIN_LANGUAGE,
                    "对声音有反应并能发出单一元音", 0, 6,
                    triple("无反应", "偶有反应", "能稳定回应并发音"),
                ),
                // ---- 7–12 月 ----
                item(
                    "GM_SIT", DOMAIN_GROSS_MOTOR,
                    "能独坐并保持身体平衡", 7, 12,
                    triple("不能独坐", "需扶持坐", "能独坐"),
                ),
                item(
                    "FM_TRANSFER", DOMAIN_FINE_MOTOR,
                    "能把物品从一只手传递到另一只手", 7, 12,
                    triple("不能传递", "偶可传递", "能稳定传递"),
                ),
                item(
                    "LA_BABBLE", DOMAIN_LANGUAGE,
                    "能发出连续音节（咿呀学语）", 7, 12,
                    triple("无连续音节", "偶尔发音", "能连续咿呀"),
                ),
                // ---- 13–24 月 ----
                item(
                    "GM_WALK", DOMAIN_GROSS_MOTOR,
                    "能独站并迈步行走", 13, 24,
                    triple("不能独站", "需扶持走", "能独立行走"),
                ),
                item(
                    "FM_STACK", DOMAIN_FINE_MOTOR,
                    "能把积木叠放或完成简单涂鸦", 13, 24,
                    triple("不能叠放", "偶可叠放", "能叠放多块"),
                ),
                item(
                    "LA_WORD", DOMAIN_LANGUAGE,
                    "能有意识地叫人或说出简单单词", 13, 24,
                    triple("无单词", "偶有单词", "能主动说单词"),
                ),
                // ---- 25–60 月 ----
                item(
                    "GM_JUMP", DOMAIN_GROSS_MOTOR,
                    "能双脚同时跳离地面", 25, 60,
                    triple("不能跳起", "能单脚跳", "能双脚跳"),
                ),
                item(
                    "GM_BALANCE", DOMAIN_GROSS_MOTOR,
                    "能单脚站立数秒并沿直线行走", 25, 60,
                    triple("不能单脚站立", "可短暂站立", "能稳定单脚站"),
                ),
                item(
                    "FM_DRAW", DOMAIN_FINE_MOTOR,
                    "能握笔画出圆形或简单图形", 25, 60,
                    triple("不能握笔画", "能随意涂画", "能画圆形图形"),
                ),
                item(
                    "LA_SENTENCE", DOMAIN_LANGUAGE,
                    "能用两三个词组成短句表达需求", 25, 60,
                    triple("不能组词", "偶有短句", "能说完整短句"),
                ),
                item(
                    "LA_UNDERSTAND", DOMAIN_LANGUAGE,
                    "能理解并执行两步简单指令", 25, 60,
                    binary("不能完成", "能完成"),
                ),
            ),
        )
    }

    private fun item(
        code: String,
        domain: String,
        prompt: String,
        minMonths: Int,
        maxMonths: Int,
        options: List<ItemOption>,
    ): Item = Item(code, domain, prompt, minMonths, maxMonths, options)

    private fun triple(low: String, mid: String, high: String): List<ItemOption> =
        listOf(ItemOption(0, low), ItemOption(1, mid), ItemOption(2, high))

    private fun binary(low: String, high: String): List<ItemOption> =
        listOf(ItemOption(0, low), ItemOption(2, high))
}

/**
 * 发育筛查判读分类（与 A2 `developmental_screenings.classification` 同值）。
 *
 *  - [NORMAL]   正常
 *  - [SUSPECT]  可疑
 *  - [ABNORMAL] 异常
 */
object DevelopmentalClassification {
    /** 正常。 */
    const val NORMAL: String = "正常"

    /** 可疑。 */
    const val SUSPECT: String = "可疑"

    /** 异常。 */
    const val ABNORMAL: String = "异常"

    /** 全部分类（校验用）。 */
    val all: Set<String> = setOf(NORMAL, SUSPECT, ABNORMAL)
}

/**
 * 一次发育筛查的计分结果（A1 纯数据；A2 据此落 `developmental_screenings`）。
 *
 * @param scaleCode      量表编码。
 * @param scaleVersion   量表版本。
 * @param ageMonths      儿童月龄。
 * @param domainScores   领域编码 → 得分（适龄条目得分之和）。
 * @param domainMaxScores 领域编码 → 该月龄适龄满分（各适龄条目 options 最大 value 之和）。
 * @param totalScore     总分。
 * @param maxTotalScore  该月龄最大可能总分。
 * @param classification 判读：正常 / 可疑 / 异常。
 * @param triggeredItems 未通过（触发）条目编码，升序。
 * @param answeredItems  回显：按适龄条目顺序的 `条目编码 → 分值`。
 * @param domainNames    领域编码 → 中文名（便于 A2 直接落库）。
 */
data class DevelopmentalScoreResult(
    val scaleCode: String,
    val scaleVersion: String,
    val ageMonths: Int,
    val domainScores: Map<String, Int>,
    val domainMaxScores: Map<String, Int>,
    val totalScore: Int,
    val maxTotalScore: Int,
    val classification: String,
    val triggeredItems: List<String>,
    val answeredItems: Map<String, Int>,
    val domainNames: Map<String, String>,
)

/**
 * A1（053）— 发育筛查计分与判读引擎（纯函数，无 DB）。
 *
 * 语义（053 §4 A1 / §8 验收 1）：
 *  1. `scaleCode` 不存在 → [IllegalArgumentException]（`unknown scale_code: <code>`）。
 *  2. `ageMonths < 0` 或超出量表适用区间 → [IllegalArgumentException]（消息含 `age_months`）。
 *  3. `answers` 键集必须**恰好等于**该月龄适龄条目集：缺项 / 多余 / 非适龄 → [IllegalArgumentException]
 *     （消息含 `missing` / `unexpected` 与条目编码）。
 *  4. 每个分值须在该条目 `options.value` 内，否则 → [IllegalArgumentException]（消息含 `invalid` 与条目编码）。
 *  5. 领域分 = 该领域适龄条目得分之和；领域满分 = 各适龄条目 `options` 最大 value 之和。
 *  6. `triggeredItems` = 得分为该条目 `options` **最小值**的适龄条目编码，升序。
 *  7. 判读按达标率 `totalScore / maxTotalScore`：`>= 0.90` 正常；`>= 0.70 且 < 0.90` 可疑；`< 0.70` 异常。
 *  8. `maxTotalScore == 0` 视为量表/月龄配置错误 → [IllegalArgumentException]。
 *
 * 该引擎**不校验儿童身份、不落库、不生成标识**；A2 负责服务端派生与持久化。
 */
object DevelopmentalScoring {

    /** 达标率切点：`>= 0.90` 判正常 —— 占位切点，正式量表由产品裁定。 */
    const val NORMAL_THRESHOLD: Double = 0.90

    /** 达标率切点：`>= 0.70` 且 `< 0.90` 判可疑 —— 占位切点，正式量表由产品裁定。 */
    const val SUSPECT_THRESHOLD: Double = 0.70

    /**
     * 计分与判读。
     *
     * @param scaleCode 量表编码（须在 [DevelopmentalScaleCatalog.scales] 内）。
     * @param ageMonths 儿童月龄（须在量表适用区间内）。
     * @param answers   `条目编码 → 分值`，键集须恰好等于该月龄适龄条目集。
     * @return 计分结果。
     * @throws IllegalArgumentException 量表未知 / 月龄越界 / 缺项 / 多余项 / 非法分值 / 量表配置错误。
     */
    fun score(scaleCode: String, ageMonths: Int, answers: Map<String, Int>): DevelopmentalScoreResult {
        val scale = DevelopmentalScaleCatalog.find(scaleCode)
            ?: throw IllegalArgumentException("unknown scale_code: $scaleCode")

        // 语义 2：月龄越界。
        if (ageMonths < 0 || ageMonths < scale.minMonths || ageMonths > scale.maxMonths) {
            throw IllegalArgumentException(
                "age_months must be within ${scale.minMonths}..${scale.maxMonths} for scale $scaleCode",
            )
        }

        val items = DevelopmentalScaleCatalog.ageAppropriateItems(scale, ageMonths)
        if (items.isEmpty()) {
            throw IllegalArgumentException("no age-appropriate items for age_months=$ageMonths")
        }

        // 语义 3：键集必须恰好等于适龄条目集。
        val expectedCodes = items.map { it.code }
        val expected = expectedCodes.toSet()
        val provided = answers.keys
        val missing = (expected - provided).sorted()
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("missing answers for items: ${missing.joinToString(", ")}")
        }
        val unexpected = (provided - expected).sorted()
        if (unexpected.isNotEmpty()) {
            throw IllegalArgumentException("unexpected answers for items: ${unexpected.joinToString(", ")}")
        }

        // 语义 4：分值须在选项内。
        items.forEach { item ->
            val value = answers.getValue(item.code)
            if (item.options.none { it.value == value }) {
                throw IllegalArgumentException("invalid score $value for item ${item.code}")
            }
        }

        // 语义 5：领域分与领域满分。
        val domainScores = LinkedHashMap<String, Int>()
        val domainMaxScores = LinkedHashMap<String, Int>()
        scale.domains.forEach { domain ->
            val domainItems = items.filter { it.domain == domain.code }
            domainScores[domain.code] = domainItems.sumOf { answers.getValue(it.code) }
            domainMaxScores[domain.code] = domainItems.sumOf { domainItem -> domainItem.options.maxOf { it.value } }
        }

        val totalScore = domainScores.values.sum()
        val maxTotalScore = domainMaxScores.values.sum()

        // 语义 8：量表/月龄配置错误。
        if (maxTotalScore == 0) {
            throw IllegalArgumentException(
                "invalid scale configuration: maxTotalScore is 0 for scale $scaleCode at age_months=$ageMonths",
            )
        }

        // 语义 7：按达标率判读（占位切点，正式量表由产品裁定）。
        val ratio = totalScore.toDouble() / maxTotalScore
        val classification = when {
            ratio >= NORMAL_THRESHOLD -> DevelopmentalClassification.NORMAL
            ratio >= SUSPECT_THRESHOLD -> DevelopmentalClassification.SUSPECT
            else -> DevelopmentalClassification.ABNORMAL
        }

        // 语义 6：触发项 = 取到该条目最低选项值的适龄条目，升序。
        val triggeredItems = items
            .filter { item -> answers.getValue(item.code) == item.options.minOf { it.value } }
            .map { it.code }
            .sorted()

        // 回显：按适龄条目顺序。
        val answeredItems = LinkedHashMap<String, Int>()
        expectedCodes.forEach { answeredItems[it] = answers.getValue(it) }

        // 领域中文名：便于 A2 直接落库。
        val domainNames = LinkedHashMap<String, String>()
        scale.domains.forEach { domainNames[it.code] = it.name }

        return DevelopmentalScoreResult(
            scaleCode = scale.code,
            scaleVersion = scale.version,
            ageMonths = ageMonths,
            domainScores = domainScores,
            domainMaxScores = domainMaxScores,
            totalScore = totalScore,
            maxTotalScore = maxTotalScore,
            classification = classification,
            triggeredItems = triggeredItems,
            answeredItems = answeredItems,
            domainNames = domainNames,
        )
    }
}
