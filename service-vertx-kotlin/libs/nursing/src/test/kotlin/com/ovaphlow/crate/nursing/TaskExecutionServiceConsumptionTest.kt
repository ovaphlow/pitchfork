package com.ovaphlow.crate.nursing

import io.vertx.core.Future
import io.vertx.sqlclient.Pool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * TaskExecutionService 016 基础数量耗材联动单元测试。
 * 覆盖输入约束与重复 stock_id 拒绝；不依赖数据库。
 */
class TaskExecutionServiceConsumptionTest {

    private lateinit var service: TaskExecutionService

    @BeforeEach
    fun setUp() {
        service = TaskExecutionService(mockk<Pool>())
    }

    @Test
    fun `consumptionInput stores stock id and base quantity`() {
        val input = TaskExecutionService.ConsumptionInput(
            stockId = "stock-1",
            quantity = BigDecimal.valueOf(2),
        )
        assertEquals("stock-1", input.stockId)
        assertEquals(BigDecimal.valueOf(2), input.quantity)
    }

    @Test
    fun `completeExecutionWithConsumptions rejects duplicate stock ids before touching db`() {
        val error = failureOf(
            service.completeExecutionWithConsumptions(
                id = "exec-1",
                note = null,
                consumptions = listOf(
                    TaskExecutionService.ConsumptionInput("stock-1", BigDecimal.ONE),
                    TaskExecutionService.ConsumptionInput("stock-1", BigDecimal.ONE),
                ),
                authenticatedSubject = "user-1",
            ),
        )
        assertTrue(error.message!!.contains("duplicate stock_id"))
    }

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }
}
