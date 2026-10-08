package com.ovaphlow.crate.healthcare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 052 P4：儿童体征阈值分档测试（**不访问数据库**）。
 *
 * 锁定验收口径：儿童体温/心率/呼吸按年龄档判定，成人阈值不变（052 §7/§9.4）。
 */
class ChildVitalThresholdsTest {

    @Test
    fun `年龄分档边界`() {
        assertEquals(ChildVitalThresholds.Band.NEONATE, ChildVitalThresholds.bandFor(0))
        assertEquals(ChildVitalThresholds.Band.NEONATE, ChildVitalThresholds.bandFor(27))
        assertEquals(ChildVitalThresholds.Band.INFANT, ChildVitalThresholds.bandFor(28))
        assertEquals(ChildVitalThresholds.Band.INFANT, ChildVitalThresholds.bandFor(364))
        assertEquals(ChildVitalThresholds.Band.TODDLER, ChildVitalThresholds.bandFor(365))
        assertEquals(ChildVitalThresholds.Band.TODDLER, ChildVitalThresholds.bandFor(365 * 3 - 1))
        assertEquals(ChildVitalThresholds.Band.PRESCHOOL, ChildVitalThresholds.bandFor(365 * 3))
        assertEquals(ChildVitalThresholds.Band.PRESCHOOL, ChildVitalThresholds.bandFor(365 * 6 - 1))
        assertEquals(ChildVitalThresholds.Band.OLDER, ChildVitalThresholds.bandFor(365 * 6))
    }

    @Test
    fun `体温全档一致且边界含在内`() {
        listOf(0L, 100L, 400L, 1000L, 3000L).forEach { ageDays ->
            assertFalse(ChildVitalThresholds.isAbnormal("TEMPERATURE", 36.0, ageDays))
            assertFalse(ChildVitalThresholds.isAbnormal("TEMPERATURE", 37.5, ageDays))
            assertTrue(ChildVitalThresholds.isAbnormal("TEMPERATURE", 35.9, ageDays))
            assertTrue(ChildVitalThresholds.isAbnormal("TEMPERATURE", 37.6, ageDays))
        }
    }

    @Test
    fun `婴幼儿脉搏宽于成人`() {
        // 新生儿 100–160：130 正常，而成人范围（60–100）会误判异常
        assertFalse(ChildVitalThresholds.isAbnormal("PULSE", 130.0, 5))
        assertTrue(ChildVitalThresholds.isAbnormal("PULSE", 130.0, 365 * 6))
        // 婴儿 90–150
        assertTrue(ChildVitalThresholds.isAbnormal("PULSE", 89.0, 100))
        assertFalse(ChildVitalThresholds.isAbnormal("PULSE", 150.0, 100))
        // 幼儿 80–130
        assertTrue(ChildVitalThresholds.isAbnormal("PULSE", 131.0, 500))
        assertFalse(ChildVitalThresholds.isAbnormal("PULSE", 80.0, 500))
    }

    @Test
    fun `婴幼儿呼吸快于成人`() {
        // 新生儿 30–60
        assertFalse(ChildVitalThresholds.isAbnormal("RESPIRATION", 45.0, 10))
        assertTrue(ChildVitalThresholds.isAbnormal("RESPIRATION", 45.0, 365 * 6))
        // 学龄前 20–30
        assertFalse(ChildVitalThresholds.isAbnormal("RESPIRATION", 28.0, 365 * 4))
        assertTrue(ChildVitalThresholds.isAbnormal("RESPIRATION", 31.0, 365 * 4))
    }

    @Test
    fun `学龄及以上回落成人范围`() {
        assertEquals(60.0 to 100.0, ChildVitalThresholds.rangeOf("PULSE", 365 * 7))
        assertEquals(12.0 to 20.0, ChildVitalThresholds.rangeOf("RESPIRATION", 365 * 7))
    }

    @Test
    fun `无儿童范围的体征不判异常`() {
        listOf("WEIGHT", "SPO2", "SYSTOLIC_BP", "DIASTOLIC_BP", "BLOOD_GLUCOSE").forEach { type ->
            assertNull(ChildVitalThresholds.rangeOf(type, 100))
            assertFalse(ChildVitalThresholds.isAbnormal(type, 1.0, 100))
            assertFalse(ChildVitalThresholds.isAbnormal(type, 999.0, 100))
        }
    }

    @Test
    fun `脏数据负天龄不抛错`() {
        assertEquals(ChildVitalThresholds.Band.NEONATE, ChildVitalThresholds.bandFor(-1))
    }

    @Test
    fun `参考范围可读文本含档位与单位`() {
        assertEquals("新生儿参考范围 100–160 次/分", ChildVitalThresholds.describe("PULSE", 3))
        assertEquals("新生儿参考范围 36–37.5 ℃", ChildVitalThresholds.describe("TEMPERATURE", 3))
        assertNull(ChildVitalThresholds.describe("WEIGHT", 3))
    }
}
