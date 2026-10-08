package com.ovaphlow.crate.nursing

/**
 * 054 B3：儿童康复里程碑**常量表 + 读时状态派生纯函数**（无 DB、无 IO）。
 *
 * ## 四态（054 §5.8 冻结，读时派生、**不落库**）
 *
 * | 状态 | 判定 |
 * |---|---|
 * | `已达成` | 已登记达成日期（`achieved_at` 非空）——优先于其它判定 |
 * | `逾期` | 未达成且 `当前月龄 >= 期望月龄` |
 * | `临近` | 未达成且 `期望月龄 - 当前月龄 <= ` [NEAR_WINDOW_MONTHS] |
 * | `未达` | 其余（含期望月龄未知） |
 *
 * 落库的 `status` 只是**登记态**（`未达成` / `已达成`，与 `achieved_at` 同步）；
 * 月龄推进导致的「临近 / 逾期」一律读时算，避免状态陈旧（§11.2 Q10：读时派生 + 显式动作）。
 */
object ChildRehabMilestone {

    const val STATE_NOT_REACHED = "未达"
    const val STATE_NEAR = "临近"
    const val STATE_ACHIEVED = "已达成"
    const val STATE_OVERDUE = "逾期"

    /** 四态白名单（§5.8 冻结）。 */
    val STATES = setOf(STATE_NOT_REACHED, STATE_NEAR, STATE_ACHIEVED, STATE_OVERDUE)

    /** 登记态白名单（落库 `status`，与 `achieved_at` 同步）。 */
    val PERSISTED_STATUSES = setOf("未达成", "已达成")

    /** 「临近」窗口：距期望月龄不超过该月数即视为临近。 */
    const val NEAR_WINDOW_MONTHS = 1

    /** 里程碑定义（常量，版本化维护）。 */
    data class Definition(
        val code: String,
        val name: String,
        val expectedAgeMonths: Int,
    )

    /**
     * 里程碑常量表（儿童发育/康复目标，按期望月龄升序）。
     * 与 V411 同批交付；新增/调整需同步 [ChildRehabMilestoneServiceTest] 的规模断言。
     */
    val CATALOG: List<Definition> = listOf(
        Definition("HEAD_CONTROL", "抬头控制", 4),
        Definition("ROLL_OVER", "自主翻身", 6),
        Definition("SIT_INDEPENDENT", "独坐", 9),
        Definition("CRAWL", "匍匐爬行", 10),
        Definition("STAND_SUPPORTED", "扶站", 12),
        Definition("FINE_PINCH", "拇食指捏取", 12),
        Definition("WALK_INDEPENDENT", "独立行走", 18),
        Definition("SPEECH_WORDS", "单词表达", 18),
        Definition("STAIRS_UP_DOWN", "上下楼梯", 24),
        Definition("SPEECH_SENTENCE", "短句表达", 30),
        Definition("RUN_JUMP", "跑跳", 36),
    ).sortedWith(compareBy({ it.expectedAgeMonths }, { it.code }))

    private val BY_CODE: Map<String, Definition> = CATALOG.associateBy { it.code }

    /** 按 code 取定义；未收录返回 `null`。 */
    fun definitionOf(code: String): Definition? = BY_CODE[code.trim()]

    /**
     * 读时四态派生。
     *
     * @param expectedAgeMonths 期望达成月龄；`null` 表示无期望（只能「未达」或「已达成」）
     * @param currentAgeMonths 当前月龄（负数按 0 处理）
     * @param achieved 是否已登记达成
     */
    fun stateOf(expectedAgeMonths: Int?, currentAgeMonths: Int, achieved: Boolean): String {
        if (achieved) return STATE_ACHIEVED
        val age = currentAgeMonths.coerceAtLeast(0)
        val expected = expectedAgeMonths ?: return STATE_NOT_REACHED
        if (age >= expected) return STATE_OVERDUE
        if (expected - age <= NEAR_WINDOW_MONTHS) return STATE_NEAR
        return STATE_NOT_REACHED
    }

    /**
     * 月龄（日历差，出生当天为 0）：与 052 `ChildHealthSupport.ageMonths` 同口径。
     * nursing 不依赖 healthcare lib，故在此镜像实现（口径必须保持一致）。
     */
    fun ageMonths(birthDate: java.time.LocalDate, on: java.time.LocalDate): Int {
        if (on.isBefore(birthDate))
            throw IllegalArgumentException("date must not be before child birth_date")
        var months = (on.year - birthDate.year) * 12 + (on.monthValue - birthDate.monthValue)
        if (on.dayOfMonth < birthDate.dayOfMonth) months -= 1
        return months.coerceAtLeast(0)
    }
}
