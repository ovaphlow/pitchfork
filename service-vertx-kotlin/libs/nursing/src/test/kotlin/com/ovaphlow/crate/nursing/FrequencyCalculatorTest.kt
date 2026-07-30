package com.ovaphlow.crate.nursing

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FrequencyCalculatorTest {

    @Test
    fun `QD generates one time per day`() {
        val date = LocalDate.of(2026, 7, 30)
        val times = FrequencyCalculator.plannedTimesForDate("QD", null, date, null)
        assertEquals(1, times.size)
        assertEquals("09:00", times[0].toLocalTime().toString())
        assertEquals(date, times[0].toLocalDate())
    }

    @Test
    fun `BID generates two times per day`() {
        val date = LocalDate.of(2026, 7, 30)
        val times = FrequencyCalculator.plannedTimesForDate("BID", null, date, null)
        assertEquals(2, times.size)
        assertEquals("09:00", times[0].toLocalTime().toString())
        assertEquals("18:00", times[1].toLocalTime().toString())
    }

    @Test
    fun `TID generates three times per day`() {
        val date = LocalDate.of(2026, 7, 30)
        val times = FrequencyCalculator.plannedTimesForDate("TID", null, date, null)
        assertEquals(3, times.size)
    }

    @Test
    fun `QID generates four times per day`() {
        val date = LocalDate.of(2026, 7, 30)
        val times = FrequencyCalculator.plannedTimesForDate("QID", null, date, null)
        assertEquals(4, times.size)
    }

    @Test
    fun `PRN returns empty list`() {
        val times = FrequencyCalculator.plannedTimesForDate("PRN", null, LocalDate.now(), null)
        assertTrue(times.isEmpty())
    }

    @Test
    fun `STAT returns empty list`() {
        val times = FrequencyCalculator.plannedTimesForDate("STAT", null, LocalDate.now(), null)
        assertTrue(times.isEmpty())
    }

    @Test
    fun `null frequency returns empty list`() {
        val times = FrequencyCalculator.plannedTimesForDate(null, null, LocalDate.now(), null)
        assertTrue(times.isEmpty())
    }

    @Test
    fun `unknown frequency returns empty list`() {
        val times = FrequencyCalculator.plannedTimesForDate("UNKNOWN", null, LocalDate.now(), null)
        assertTrue(times.isEmpty())
    }

    @Test
    fun `QOD generates on start date and every other day`() {
        val start = LocalDate.of(2026, 7, 1) // Monday
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, start, null).isNotEmpty())
        // Day+1 = Tuesday — should NOT generate
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, start.plusDays(1), null).isEmpty())
        // Day+2 = Wednesday — should generate
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, start.plusDays(2), null).isNotEmpty())
        // Day+3 = Thursday — should NOT generate
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", start, start.plusDays(3), null).isEmpty())
    }

    @Test
    fun `QW generates on same day of week as start date`() {
        val start = LocalDate.of(2026, 7, 1) // Wednesday
        assertTrue(FrequencyCalculator.plannedTimesForDate("QW", start, start, null).isNotEmpty())
        // Same day of week (next Wednesday)
        assertTrue(FrequencyCalculator.plannedTimesForDate("QW", start, start.plusDays(7), null).isNotEmpty())
        // Different day of week (Thursday)
        assertTrue(FrequencyCalculator.plannedTimesForDate("QW", start, start.plusDays(1), null).isEmpty())
    }

    @Test
    fun `BIW generates on base day and 3 days after`() {
        val start = LocalDate.of(2026, 7, 1) // Wednesday
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, start, null).isNotEmpty())
        // Wednesday + 3 = Saturday
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, start.plusDays(3), null).isNotEmpty())
        // Other days
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, start.plusDays(1), null).isEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("BIW", start, start.plusDays(2), null).isEmpty())
    }

    @Test
    fun `TIW generates on base day and 2 and 4 days after`() {
        val start = LocalDate.of(2026, 7, 1) // Wednesday
        assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, start, null).isNotEmpty())
        // Wednesday + 2 = Friday
        assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, start.plusDays(2), null).isNotEmpty())
        // Wednesday + 4 = Sunday
        assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, start.plusDays(4), null).isNotEmpty())
        // Other days
        assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, start.plusDays(1), null).isEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("TIW", start, start.plusDays(3), null).isEmpty())
    }

    @Test
    fun `custom schedule_times from metadata override defaults`() {
        val date = LocalDate.of(2026, 7, 30)
        val meta = JsonObject().put("schedule_times", listOf("07:00", "19:00"))
        val times = FrequencyCalculator.plannedTimesForDate("BID", null, date, meta)
        assertEquals(2, times.size)
        assertEquals("07:00", times[0].toLocalTime().toString())
        assertEquals("19:00", times[1].toLocalTime().toString())
    }

    @Test
    fun `isGeneratable returns true for known frequencies`() {
        assertTrue(FrequencyCalculator.isGeneratable("QD"))
        assertTrue(FrequencyCalculator.isGeneratable("BID"))
        assertTrue(FrequencyCalculator.isGeneratable("TID"))
        assertTrue(FrequencyCalculator.isGeneratable("PRN").not())
        assertTrue(FrequencyCalculator.isGeneratable(null).not())
        assertTrue(FrequencyCalculator.isGeneratable("UNKNOWN").not())
    }

    @Test
    fun `QOD without start date generates every day`() {
        val date1 = LocalDate.of(2026, 7, 1)
        val date2 = LocalDate.of(2026, 7, 2)
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", null, date1, null).isNotEmpty())
        assertTrue(FrequencyCalculator.plannedTimesForDate("QOD", null, date2, null).isNotEmpty())
    }
}
