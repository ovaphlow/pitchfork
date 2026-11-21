package com.ovaphlow.crate.healthcare

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `patients.person_type` 的纯函数校验（不访库）。
 *
 * 056 D3：养老单产品只写「长者」，但白名单保留 `居民 / 长者 / 儿童` 三个历史值可读——
 * 既有库中可能存在这两种档案，删值会让历史档案读不出来。本测试锁定该口径：
 * 三值均通过校验，白名单之外一律拒绝。
 */
class PatientPersonTypeTest {

    @Test
    fun `person_type 缺省为居民`() {
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject()))
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject().put("person_type", "")))
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject().put("person_type", "   ")))
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject().putNull("person_type")))
    }

    @Test
    fun `person_type 去除首尾空白`() {
        assertEquals("长者", HealthcareService.personTypeFrom(JsonObject().put("person_type", " 长者 ")))
    }

    @Test
    fun `person_type 非字符串被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.personTypeFrom(JsonObject().put("person_type", 1))
        }
    }

    @Test
    fun `白名单三值均通过`() {
        HealthcareService.validatePersonType("居民")
        HealthcareService.validatePersonType("长者")
        HealthcareService.validatePersonType("儿童")
    }

    @Test
    fun `白名单之外一律拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("老人")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("CHILD")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("")
        }
    }
}
