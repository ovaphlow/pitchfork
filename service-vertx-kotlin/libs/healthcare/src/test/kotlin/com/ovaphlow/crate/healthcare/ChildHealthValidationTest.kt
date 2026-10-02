package com.ovaphlow.crate.healthcare

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * V525 儿保契约的纯函数校验（不访库）。
 *
 * 数据库侧的真实落库、1:1 upsert/删除与列表过滤由 L-TEST 的真库集成测试覆盖；
 * 本测试只锁定服务端在进入 DB 之前必须拒绝的请求形状与域约束。
 */
class ChildHealthValidationTest {

    @Test
    fun `person_type 缺省为居民`() {
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject()))
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject().put("person_type", "")))
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject().put("person_type", "   ")))
        assertEquals("居民", HealthcareService.personTypeFrom(JsonObject().putNull("person_type")))
    }

    @Test
    fun `person_type 去除首尾空白`() {
        assertEquals("儿童", HealthcareService.personTypeFrom(JsonObject().put("person_type", " 儿童 ")))
    }

    @Test
    fun `person_type 非字符串被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.personTypeFrom(JsonObject().put("person_type", 1))
        }
    }

    @Test
    fun `非法 person_type 被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("老人", "2020-01-01", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("CHILD", "2020-01-01", false)
        }
    }

    @Test
    fun `儿童必须填写出生日期`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("儿童", null, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("儿童", "   ", false)
        }
        // 合法：儿童 + 出生日期
        HealthcareService.validatePersonType("儿童", "2024-05-01", false)
    }

    @Test
    fun `非儿童携带 child_profile 被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("居民", "1990-01-01", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validatePersonType("长者", "1950-01-01", true)
        }
        // 合法：儿童携带 child_profile
        HealthcareService.validatePersonType("儿童", "2024-05-01", true)
    }

    @Test
    fun `居民与长者不要求出生日期`() {
        HealthcareService.validatePersonType("居民", null, false)
        HealthcareService.validatePersonType("长者", null, false)
    }

    @Test
    fun `child_profile 形状只接受对象或 null`() {
        HealthcareService.validateChildProfileShape(null)
        HealthcareService.validateChildProfileShape(JsonObject())
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validateChildProfileShape(JsonArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            HealthcareService.validateChildProfileShape("guardian")
        }
    }
}
