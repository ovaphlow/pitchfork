package com.ovaphlow.crate.nursing

import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 根据任务频次编码和目标日期计算应生成的计划执行时间列表。
 *
 * 约定：
 * - 默认日内时段从 metadata.schedule_times 读取（ISO HH:mm 数组），
 *   若缺失则使用频次对应的默认值。
 * - PRN（按需）和 STAT（立即/临时）不自动生成，返回空列表。
 * - 任务起止日期 (start_date / end_date) 和周期状态由调用方控制，
 *   本工具仅做频率 × 日期的纯计算。
 */
object FrequencyCalculator {

    private val log = LoggerFactory.getLogger(FrequencyCalculator::class.java)

    /** 各频次默认日内时段（HH:mm） */
    private val defaultTimes: Map<String, List<String>> = mapOf(
        "QD"  to listOf("09:00"),
        "BID" to listOf("09:00", "18:00"),
        "TID" to listOf("08:00", "12:00", "18:00"),
        "QID" to listOf("08:00", "12:00", "18:00", "22:00"),
        "QOD" to listOf("09:00"),
        "QW"  to listOf("09:00"),
        "BIW" to listOf("09:00"),
        "TIW" to listOf("09:00"),
    )

    /** 不允许自动生成的频次编码 */
    private val nonGeneratable: Set<String> = setOf("PRN", "STAT")

    /**
     * 计算某个任务在目标日期应生成的计划时间列表。
     *
     * @param frequencyCode 任务频次编码（QD / BID / ...）
     * @param startDate     任务开始日期（可为 null，表示无起始限制）
     * @param targetDate    目标日期
     * @param metadata      任务 metadata（可选，读取 schedule_times）
     * @return 计划时间列表（OffsetDateTime，UTC），可能为空
     */
    fun plannedTimesForDate(
        frequencyCode: String?,
        startDate: LocalDate?,
        targetDate: LocalDate,
        metadata: JsonObject?
    ): List<OffsetDateTime> {
        val code = frequencyCode?.trim()?.uppercase() ?: return emptyList()

        if (code in nonGeneratable) return emptyList()

        // 检查该频次在目标日期是否应生成
        if (!shouldGenerateOnDate(code, startDate, targetDate)) return emptyList()

        val times = scheduleTimes(code, metadata)
        val zone = ZoneOffset.UTC // 使用 UTC 存储，前端负责展示转换

        return times.map { timeStr ->
            val lt = LocalTime.parse(timeStr)
            OffsetDateTime.of(targetDate, lt, zone)
        }
    }

    /**
     * 判断频次编码是否可自动生成（即非 PRN / STAT 且为已知编码）。
     */
    fun isGeneratable(frequencyCode: String?): Boolean {
        val code = frequencyCode?.trim()?.uppercase() ?: return false
        return code !in nonGeneratable && code in defaultTimes
    }

    // ---- 内部方法 ----

    /** 读取 metadata.schedule_times，缺失时使用默认值 */
    private fun scheduleTimes(code: String, metadata: JsonObject?): List<String> {
        val custom = metadata?.getJsonArray("schedule_times")
        if (custom != null && !custom.isEmpty) {
            return custom.map { it.toString() }
        }
        return defaultTimes[code] ?: defaultTimes["QD"]!!
    }

    /** 判断指定频次在目标日期是否应生成执行记录 */
    private fun shouldGenerateOnDate(
        code: String,
        startDate: LocalDate?,
        targetDate: LocalDate
    ): Boolean {
        return when (code) {
            "QD" -> true              // 每日都生成
            "BID" -> true
            "TID" -> true
            "QID" -> true
            "QOD" -> {
                // 以 start_date 为第 0 天，偶数天生成
                if (startDate == null) true // 无起始日期则每天都生成
                else {
                    val daysBetween = ChronoUnit.DAYS.between(startDate, targetDate)
                    daysBetween >= 0 && daysBetween % 2 == 0L
                }
            }
            "QW" -> {
                // 每周与 start_date 同星期生成
                if (startDate == null) true
                else {
                    !targetDate.isBefore(startDate) &&
                        targetDate.dayOfWeek == startDate.dayOfWeek
                }
            }
            "BIW" -> {
                // 每周两次：start_date 同星期 + 3 天后
                if (startDate == null) true
                else if (targetDate.isBefore(startDate)) false
                else {
                    val dow = targetDate.dayOfWeek
                    val baseDow = startDate.dayOfWeek
                    val secondDow = baseDow.plus(3)
                    dow == baseDow || dow == secondDow
                }
            }
            "TIW" -> {
                // 每周三次：start_date 同星期 + 2 天后 + 4 天后
                if (startDate == null) true
                else if (targetDate.isBefore(startDate)) false
                else {
                    val dow = targetDate.dayOfWeek
                    val baseDow = startDate.dayOfWeek
                    val secondDow = baseDow.plus(2)
                    val thirdDow = baseDow.plus(4)
                    dow == baseDow || dow == secondDow || dow == thirdDow
                }
            }
            else -> {
                log.warn("unknown frequency code '{}', skipping auto-generation", code)
                false
            }
        }
    }
}
