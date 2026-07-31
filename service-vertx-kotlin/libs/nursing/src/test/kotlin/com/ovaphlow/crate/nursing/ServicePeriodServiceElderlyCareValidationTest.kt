package com.ovaphlow.crate.nursing

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


/**
 * ServicePeriodService 养老周期输入隔离的单元测试。
 *
 * 校验逻辑发生在任何数据库访问之前，因此可以用未 stub 的 Pool mock 构造服务，
 * 不访问数据库即可验证：
 *   - 通用创建路径拒绝 ELDERLY_CARE（养老周期只能走专用流程）
 *   - 通用创建路径忽略 encounter_id 字段（不允许通过通用接口绑定入住）
 *   - 补建接口拒绝空 encounter_id
 */
class ServicePeriodServiceElderlyCareValidationTest {

    private val pool = io.mockk.mockk<Pool>(relaxed = true)
    private val service = ServicePeriodService(pool)

    private fun causeOf(future: io.vertx.core.Future<*>): Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get()
            throw AssertionError("expected future to fail")
        } catch (error: Throwable) {
            var cause = error
            while (cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException) {
                cause = cause.cause ?: break
            }
            return cause
        }
    }

    @Test
    fun `通用创建路径拒绝 ELDERLY_CARE 服务类型`() {
        val cause = causeOf(
            service.create(
                JsonObject()
                    .put("patient_id", "01patient")
                    .put("service_type", "ELDERLY_CARE")
                    .put("start_date", "2026-07-31")
            )
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertTrue(cause.message?.contains("invalid service_type") == true, "got: ${cause.message}")
    }

    @Test
    fun `通用创建路径即使携带 encounter_id 也不会创建养老周期`() {
        // encounter_id 对通用路径是未知字段：service_type=ELDERLY_CARE 必须先被拒绝
        val cause = causeOf(
            service.create(
                JsonObject()
                    .put("patient_id", "01patient")
                    .put("service_type", "ELDERLY_CARE")
                    .put("encounter_id", "01encounter")
                    .put("start_date", "2026-07-31")
            )
        )
        assertInstanceOf(IllegalArgumentException::class.java, cause)
    }

    @Test
    fun `补建接口拒绝空白 encounter_id`() {
        val cause = causeOf(service.enrollElderlyAdmission("  "))
        assertInstanceOf(IllegalArgumentException::class.java, cause)
        assertEquals("encounter_id is required", cause.message)
    }

    @Test
    fun `通用创建路径不提前拒绝既有非养老类型`() {
        // 校验通过后才会触达数据库层：stub 数据库返回失败，
        // 若输入校验提前拒绝，此处会得到 IllegalArgumentException
        val prepared = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any()) } returns prepared
        every { prepared.execute(any<Tuple>()) } returns Future.failedFuture(RuntimeException("db not reached"))
        val cause = causeOf(
            service.create(
                JsonObject()
                    .put("patient_id", "01patient")
                    .put("service_type", "HOME_CARE")
                    .put("start_date", "2026-07-31")
            )
        )
        assertTrue(cause !is IllegalArgumentException, "HOME_CARE 输入不应在校验阶段被拒绝，got: ${cause.message}")
    }
}
