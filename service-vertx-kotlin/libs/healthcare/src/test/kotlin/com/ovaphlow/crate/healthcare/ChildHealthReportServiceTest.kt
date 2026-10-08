package com.ovaphlow.crate.healthcare

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 053 C1：儿童健康报告**纯装配函数**测试（**不访问数据库**）。
 *
 * 锁定冻结输出形状：9 个数据段 + `meta.counts`（各数组 size）；空输入仍返回完整骨架且
 * 数组为 `[]`（非 null）；`patient` 段**不含任何身份证号键**（053 §3 Dh / §8.6）。
 */
class ChildHealthReportServiceTest {

    private fun emptyVaccinations(): JsonObject = JsonObject()
        .put("records", JsonArray())
        .put("schedule", JsonArray())
        .put(
            "summary",
            JsonObject()
                .put("total", 0)
                .put("已接种", 0)
                .put("待接种", 0)
                .put("临期", 0)
                .put("逾期", 0)
                .put("暂缓", 0)
                .put("拒种", 0),
        )

    private fun samplePatient(): JsonObject = JsonObject()
        .put("id", "01J000000000000000000000A")
        .put("name", "小明")
        .put("gender", "男")
        .put("birth_date", "2024-01-15")
        .put("age_months", 32)
        .put("person_type", "儿童")

    @Test
    fun `完整输入装配出全部 9 段与正确计数`() {
        val patient = samplePatient()
        val profile = JsonObject()
            .put("id", "01J000000000000000000000P")
            .put("patient_id", patient.getString("id"))
            .put("guardian_name", "王女士")
        val checkups = JsonArray().add(
            JsonObject()
                .put("id", "01J000000000000000000000C")
                .put("checkup_type", "常规")
                .put("diagnosis", "上呼吸道感染")
                .put("diagnosis_code", "J06")
                .put("status", "已完成"),
        )
        val growth = JsonArray().add(
            JsonObject()
                .put("id", "01J000000000000000000000G")
                .put("percentile_snapshot", JsonObject().put("height", JsonObject().put("p", 50))),
        )
        val vaccinations = JsonObject()
            .put("records", JsonArray().add(JsonObject().put("id", "01J000000000000000000000V")))
            .put("schedule", JsonArray().add(JsonObject().put("vaccine_code", "HEPB")))
            .put("summary", JsonObject().put("total", 20))
        val screenings = JsonArray().add(
            JsonObject()
                .put("id", "01J000000000000000000000S")
                .put("classification", "可疑")
                .put("conclusion", "建议复查"),
        )
        val referrals = JsonArray().add(
            JsonObject().put("id", "01J000000000000000000000R").put("status", "待转诊"),
        )
        val guidance = JsonArray().add(
            JsonObject().put("id", "01J000000000000000000000D").put("kind", "疾病防控"),
        )
        val allergies = JsonArray()
            .add(JsonObject().put("allergen", "青霉素").put("reaction", "皮疹"))
            .add(JsonObject().put("allergen", "鸡蛋").put("reaction", "腹泻"))

        val report = ChildHealthReportService.assembleReport(
            patient, profile, checkups, growth, vaccinations, screenings, referrals, guidance, allergies,
        )

        // 9 个数据段齐备
        assertEquals(patient, report.getJsonObject("patient"))
        assertEquals(profile, report.getJsonObject("profile"))
        assertEquals(checkups, report.getJsonArray("checkups"))
        assertEquals(growth, report.getJsonArray("growth"))
        assertEquals(vaccinations, report.getJsonObject("vaccinations"))
        assertEquals(screenings, report.getJsonArray("screenings"))
        assertEquals(referrals, report.getJsonArray("referrals"))
        assertEquals(guidance, report.getJsonArray("guidance"))
        assertEquals(allergies, report.getJsonArray("allergies"))

        // meta.counts 为各数组 size
        val meta = report.getJsonObject("meta")
        assertNotNull(meta.getString("generated_at"))
        val counts = meta.getJsonObject("counts")
        assertEquals(1, counts.getInteger("checkups"))
        assertEquals(1, counts.getInteger("growth"))
        assertEquals(1, counts.getInteger("screenings"))
        assertEquals(1, counts.getInteger("referrals"))
        assertEquals(1, counts.getInteger("guidance"))
        assertEquals(2, counts.getInteger("allergies"))
    }

    @Test
    fun `patient 段不含任何身份证号键`() {
        val report = ChildHealthReportService.assembleReport(
            samplePatient(), null, JsonArray(), JsonArray(), emptyVaccinations(),
            JsonArray(), JsonArray(), JsonArray(), JsonArray(),
        )
        val patient = report.getJsonObject("patient")
        val keys = patient.fieldNames()
        assertFalse(keys.contains("id_card"), "patient 段不得含 id_card")
        assertFalse(keys.contains("id_number"), "patient 段不得含 id_number")
        assertTrue(
            keys.none { it.contains("id_card") || it.contains("id_number") },
            "patient 段不得含任何身份证号键：$keys",
        )
    }

    @Test
    fun `全空输入仍返回完整骨架且数组为空数组`() {
        val report = ChildHealthReportService.assembleReport(
            JsonObject(), null, JsonArray(), JsonArray(), emptyVaccinations(),
            JsonArray(), JsonArray(), JsonArray(), JsonArray(),
        )

        assertNotNull(report.getJsonObject("patient"))
        assertTrue(report.containsKey("profile"))
        assertNull(report.getJsonObject("profile"), "无档案时 profile 为 null")
        assertNotNull(report.getJsonObject("vaccinations"))

        listOf("checkups", "growth", "screenings", "referrals", "guidance", "allergies").forEach { key ->
            val array = report.getJsonArray(key)
            assertNotNull(array, "$key 必须存在")
            assertEquals(0, array.size(), "$key 必须为空数组（非 null）")
        }

        val counts = report.getJsonObject("meta").getJsonObject("counts")
        listOf("checkups", "growth", "screenings", "referrals", "guidance", "allergies").forEach { key ->
            assertEquals(0, counts.getInteger(key), "$key 计数应为 0")
        }
    }
}
