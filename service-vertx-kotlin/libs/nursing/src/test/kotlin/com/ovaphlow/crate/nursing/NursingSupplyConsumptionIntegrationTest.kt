package com.ovaphlow.crate.nursing

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.inventories.ConflictException as InventoryConflictException
import com.ovaphlow.crate.inventories.InventoryConsumptionService
import com.ovaphlow.crate.inventories.StockService
import io.vertx.core.Vertx
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.core.http.HttpMethod
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class NursingSupplyConsumptionIntegrationTest {

    companion object {
        private const val FIXTURE_PREFIX = "sc-"
        private const val WAREHOUSE = "sc-warehouse"
        private const val PACKAGE_MATERIAL = "sc-material-package"
        private const val SPLIT_MATERIAL = "sc-material-split"
        private const val BATCH_MATERIAL = "sc-material-batch"
        private const val INACTIVE_MATERIAL = "sc-material-inactive"
        private const val VALID_LOT = "sc-lot-valid"
        private const val EXPIRED_LOT = "sc-lot-expired"
        private const val PERIOD_ID = "sc-period"
        private const val TASK_ID = "sc-task"
        private const val EXECUTION_ID = "sc-execution"
    }

    private lateinit var vertx: Vertx
    private lateinit var pool: Pool
    private lateinit var jdbcUrl: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var stockService: StockService
    private lateinit var consumptionService: InventoryConsumptionService
    private lateinit var taskExecutionService: TaskExecutionService

    @BeforeAll
    fun setUpDatabase() {
        val host = System.getProperty("integration.db.host", "localhost")
        val port = System.getProperty("integration.db.port", "5432").toInt()
        user = System.getProperty("integration.db.user", "ovaphlow")
        password = System.getenv("PITCHFORK_DB_PASSWORD")
            ?: error("PITCHFORK_DB_PASSWORD must be set")
        jdbcUrl = "jdbc:postgresql://$host:$port/aceso_test"

        val config = JsonObject()
            .put("host", host)
            .put("port", port)
            .put("database", "aceso_test")
            .put("user", user)
        DatabaseConfig.migrate(config)

        vertx = Vertx.vertx()
        pool = DatabaseConfig.createPool(vertx, config)
        stockService = StockService(pool)
        consumptionService = InventoryConsumptionService()
        taskExecutionService = TaskExecutionService(pool)
    }

    @BeforeEach
    fun setUpFixture() {
        cleanupFixture()
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO public.materials
                        (id, code, name, category, base_unit, quantity_scale, enable_batch_control, status)
                    VALUES
                        ('$PACKAGE_MATERIAL', 'SC-PACKAGE', '测试无菌包耗材', '护理耗材', '包', 0, FALSE, 'ACTIVE'),
                        ('$SPLIT_MATERIAL', 'SC-SPLIT', '测试消毒液耗材', '护理耗材', 'mL', 3, FALSE, 'ACTIVE'),
                        ('$BATCH_MATERIAL', 'SC-BATCH', '测试批次耗材', '护理耗材', '片', 0, TRUE, 'ACTIVE'),
                        ('$INACTIVE_MATERIAL', 'SC-INACTIVE', '测试停用耗材', '护理耗材', '包', 0, FALSE, 'INACTIVE')
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO public.lots (id, material_id, batch_no, expiry_date)
                    VALUES
                        ('$VALID_LOT', '$BATCH_MATERIAL', 'SC-VALID', CURRENT_DATE + 30),
                        ('$EXPIRED_LOT', '$BATCH_MATERIAL', 'SC-EXPIRED', CURRENT_DATE - 1)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO nursing.nursing_service_periods
                        (id, patient_id, service_type, start_date, status)
                    VALUES ('$PERIOD_ID', 'sc-patient', 'HOME_CARE', CURRENT_DATE, 'ACTIVE')
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO nursing.nursing_tasks
                        (id, period_id, task_type, description, frequency_code, start_date, status)
                    VALUES ('$TASK_ID', '$PERIOD_ID', 'NURSING', '测试护理任务', 'QD', CURRENT_DATE, 'ACTIVE')
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO nursing.nursing_task_executions
                        (id, task_id, planned_time, status)
                    VALUES ('$EXECUTION_ID', '$TASK_ID', '${OffsetDateTime.now()}', 'IN_PROGRESS')
                    """.trimIndent(),
                )
            }
        }
    }

    @AfterEach
    fun tearDownFixture() {
        cleanupFixture()
        assertEquals(0, count("nursing.nursing_task_execution_consumptions", "id LIKE '$FIXTURE_PREFIX%'").toInt())
        assertEquals(0, count("nursing.nursing_task_executions", "id LIKE '$FIXTURE_PREFIX%'").toInt())
        assertEquals(0, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(0, count("public.stocks", "warehouse = '$WAREHOUSE'").toInt())
    }

    @AfterAll
    fun closeDatabase() {
        if (::pool.isInitialized) await(pool.close())
        if (::vertx.isInitialized) await(vertx.close())
    }

    @Test
    fun `inbound merges stock and available query filters inactive and expired rows`() {
        await(stockService.confirmInbound(
            StockService.InboundCommand(
                warehouse = WAREHOUSE,
                items = listOf(
                    StockService.InboundItem(PACKAGE_MATERIAL, null, quantity = decimal("10"), unitCost = decimal("5")),
                    StockService.InboundItem(BATCH_MATERIAL, VALID_LOT, quantity = decimal("2"), unitCost = decimal("7")),
                    StockService.InboundItem(BATCH_MATERIAL, EXPIRED_LOT, quantity = decimal("4"), unitCost = decimal("7")),
                    StockService.InboundItem(INACTIVE_MATERIAL, null, quantity = decimal("3"), unitCost = decimal("1")),
                ),
                note = "fixture inbound",
            ),
        ))
        assertDecimalEquals("10", stockState(stockId(PACKAGE_MATERIAL)).quantity)
        await(stockService.confirmInbound(
            StockService.InboundCommand(
                warehouse = WAREHOUSE,
                items = listOf(StockService.InboundItem(PACKAGE_MATERIAL, null, quantity = decimal("2"), unitCost = decimal("7"))),
                note = "fixture merge",
            ),
        ))
        assertEquals(1, count("public.stocks", "warehouse = '$WAREHOUSE' AND material_id = '$PACKAGE_MATERIAL'" ).toInt())

        val packageStock = stockState(stockId(PACKAGE_MATERIAL))
        assertDecimalEquals("12", packageStock.quantity)
        assertDecimalEquals("64", packageStock.totalCost)

        val page = await(stockService.listAvailableStocks(warehouse = WAREHOUSE))
        val records = page.getJsonArray("records")
        assertEquals(2, records.size())
        assertTrue((0 until records.size()).any { records.getJsonObject(it).getString("material_id") == PACKAGE_MATERIAL })
        assertTrue((0 until records.size()).any { records.getJsonObject(it).getString("lot_id") == VALID_LOT })
        assertFalse((0 until records.size()).any { records.getJsonObject(it).getString("material_id") == INACTIVE_MATERIAL })
        assertFalse((0 until records.size()).any { records.getJsonObject(it).getString("lot_id") == EXPIRED_LOT })
    }

    @Test
    fun `invalid batch inbound rolls back the whole operation`() {
        val error = expectFailure(stockService.confirmInbound(
            StockService.InboundCommand(
                warehouse = WAREHOUSE,
                items = listOf(
                    StockService.InboundItem(PACKAGE_MATERIAL, null, quantity = decimal("2"), unitCost = decimal("4")),
                    StockService.InboundItem(BATCH_MATERIAL, null, quantity = decimal("1"), unitCost = decimal("9")),
                ),
                note = "invalid fixture inbound",
            ),
        ))

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(0, count("public.stocks", "warehouse = '$WAREHOUSE'").toInt())
    }

    @Test
    fun `package consumption is idempotent and conflicting retry is rejected`() {
        await(inbound(PACKAGE_MATERIAL, null, "5", "8"))
        val stockId = stockId(PACKAGE_MATERIAL)
        val initialState = stockState(stockId)
        assertDecimalEquals("5", initialState.quantity)
        assertDecimalEquals("0", initialState.lockedQuantity)
        val command = consumptionCommand("sc-consume-package", stockId, "1")

        val first = consume(command)
        val afterFirst = stockState(stockId)
        val retry = consume(command)
        val conflict = expectFailure(consumeFuture(command.copy(items = listOf(
            InventoryConsumptionService.ConsumptionItem(stockId, decimal("2")),
        ))))

        assertEquals(first.operationId, retry.operationId)
        assertEquals(1, first.detailResults.size)
        assertDecimalEquals("1", first.detailResults.single().quantity)
        assertDecimalEquals("4", afterFirst.quantity)
        assertEquals(1, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(1, count("public.stock_operation_details", "operation_id = '${first.operationId}'").toInt())
        assertTrue(conflict is InventoryConflictException)
    }

    @Test
    fun `liquid consumption uses base quantity with configured precision`() {
        await(inbound(SPLIT_MATERIAL, null, "5", "3"))
        val stockId = stockId(SPLIT_MATERIAL)
        val result = consume(consumptionCommand("sc-consume-split", stockId, "2"))
        val detail = result.detailResults.single()
        val state = stockState(stockId)

        assertDecimalEquals("2", detail.quantity)
        assertDecimalEquals("3", state.quantity)
        assertDecimalEquals("9", state.totalCost)
        assertEquals("mL", detail.unit)
    }

    @Test
    fun `multi-stock consumption rolls back when a later row is insufficient`() {
        await(inbound(PACKAGE_MATERIAL, null, "1", "4"))
        await(inbound(SPLIT_MATERIAL, null, "1", "6"))
        val packageStockId = stockId(PACKAGE_MATERIAL)
        val splitStockId = stockId(SPLIT_MATERIAL)
        val command = InventoryConsumptionService.NursingConsumptionCommand(
            items = listOf(
                InventoryConsumptionService.ConsumptionItem(packageStockId, decimal("1")),
                InventoryConsumptionService.ConsumptionItem(splitStockId, decimal("2")),
            ),
            taskExecutionId = "sc-consume-rollback",
            taskId = TASK_ID,
            periodId = PERIOD_ID,
            patientId = "sc-patient",
            executor = "sc-tester",
            businessTime = OffsetDateTime.now(),
        )

        val error = expectFailure(consumeFuture(command))

        assertTrue(error is InventoryConflictException)
        assertDecimalEquals("1", stockState(packageStockId).quantity)
        assertDecimalEquals("1", stockState(splitStockId).quantity)
        assertEquals(0, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(0, count("public.stock_operation_details", "id LIKE '$FIXTURE_PREFIX%'").toInt())
    }

    @Test
    fun `concurrent different executions can consume the last package only once`() {
        await(inbound(PACKAGE_MATERIAL, null, "1", "4"))
        val stockId = stockId(PACKAGE_MATERIAL)
        val commands = listOf("sc-concurrent-a", "sc-concurrent-b").map { executionId ->
            consumptionCommand(executionId, stockId, "1")
        }
        val futures = commands.map { command ->
            consumeFuture(command).toCompletionStage().toCompletableFuture()
        }

        val outcomes = futures.map { future ->
            runCatching { future.get(20, TimeUnit.SECONDS) }
        }
        val successes = outcomes.count { it.isSuccess }
        val failures = outcomes.mapNotNull { it.exceptionOrNull()?.rootCause() }

        assertEquals(1, successes)
        assertEquals(1, failures.count { it is InventoryConflictException })
        assertDecimalEquals("0", stockState(stockId).quantity)
        assertEquals(1, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
    }

    @Test
    fun `concurrent retry for one execution returns one existing operation`() {
        await(inbound(PACKAGE_MATERIAL, null, "1", "4"))
        val stockId = stockId(PACKAGE_MATERIAL)
        val command = consumptionCommand("sc-concurrent-idempotent", stockId, "1")
        val futures = listOf(command, command).map { current ->
            consumeFuture(current).toCompletionStage().toCompletableFuture()
        }

        val results = futures.map { requireNotNull(await(it)) }

        assertEquals(results[0].operationId, results[1].operationId)
        assertDecimalEquals("0", stockState(stockId).quantity)
        assertEquals(1, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(1, count("public.stock_operation_details", "operation_id = '${results[0].operationId}'").toInt())
    }

    @Test
    fun `task completion creates multiple consumptions and retry does not deduct again`() {
        await(inbound(PACKAGE_MATERIAL, null, "2", "4"))
        await(inbound(SPLIT_MATERIAL, null, "3", "6"))
        val packageStockId = stockId(PACKAGE_MATERIAL)
        val splitStockId = stockId(SPLIT_MATERIAL)
        val inputs = listOf(
            TaskExecutionService.ConsumptionInput(packageStockId, decimal("1")),
            TaskExecutionService.ConsumptionInput(splitStockId, decimal("1.5")),
        )

        val first = await(taskExecutionService.completeExecutionWithConsumptions(EXECUTION_ID, "完成备注", inputs, "sc-tester"))
        val retry = await(taskExecutionService.completeExecutionWithConsumptions(EXECUTION_ID, "完成备注", inputs, "sc-tester"))

        assertEquals("COMPLETED", first.getString("status"))
        assertEquals(2, first.getJsonArray("consumptions").size())
        assertEquals(2, retry.getJsonArray("consumptions").size())
        assertNull(first.getString("stock_operation_detail_id"))
        assertNull(first.getValue("quantity"))
        assertDecimalEquals("1", stockState(packageStockId).quantity)
        assertDecimalEquals("1.5", stockState(splitStockId).quantity)
        assertEquals(1, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(2, count("nursing.nursing_task_execution_consumptions", "task_execution_id = '$EXECUTION_ID'").toInt())

        val details = await(taskExecutionService.listExecutionConsumptions(EXECUTION_ID))
        assertEquals(2, details.getJsonArray("records").size())
        val conflict = expectFailure(taskExecutionService.completeExecutionWithConsumptions(
            EXECUTION_ID,
            "不同备注",
            listOf(TaskExecutionService.ConsumptionInput(packageStockId, decimal("2"))),
            "sc-tester",
        ))
        assertTrue(conflict is ConflictException)
    }

    @Test
    fun `task completion rolls back status and inventory on insufficient stock`() {
        await(inbound(PACKAGE_MATERIAL, null, "1", "4"))
        val stockId = stockId(PACKAGE_MATERIAL)
        val error = expectFailure(taskExecutionService.completeExecutionWithConsumptions(
            EXECUTION_ID,
            "不应保存",
            listOf(TaskExecutionService.ConsumptionInput(stockId, decimal("2"))),
            "sc-tester",
        ))

        assertTrue(error is InventoryConflictException)
        val execution = queryOne("SELECT status, actual_time, note FROM nursing.nursing_task_executions WHERE id = '$EXECUTION_ID'")
        assertEquals("IN_PROGRESS", execution["status"])
        assertNull(execution["actual_time"])
        assertNull(execution["note"])
        assertDecimalEquals("1", stockState(stockId).quantity)
        assertEquals(0, count("public.stock_operations", "warehouse = '$WAREHOUSE' AND operation_type = 'OUTBOUND'").toInt())
        assertEquals(0, count("nursing.nursing_task_execution_consumptions", "task_execution_id = '$EXECUTION_ID'").toInt())
    }

    @Test
    fun `status route maps missing inventory stock to 404`() {
        val server = await(
            vertx.createHttpServer()
                .requestHandler(TaskExecutionRoutes.create(vertx, pool))
                .listen(0),
        )
        val client = vertx.createHttpClient()
        try {
            val response = await(
                client.request(HttpMethod.PATCH, server.actualPort(), "localhost", "/$EXECUTION_ID/status")
                    .compose { request ->
                        request.putHeader("content-type", "application/json")
                            .send(
                                JsonObject()
                                    .put("status", "COMPLETED")
                                    .put(
                                        "consumptions",
                                        listOf(
                                            JsonObject()
                                                .put("stock_id", "sc-stock-missing")
                                                .put("quantity", 1),
                                        ),
                                    )
                                    .encode(),
                            )
                    },
            )
            val body = await(response.body())

            assertEquals(404, response.statusCode())
            assertTrue(JsonObject(body).getString("error").isNotBlank())
        } finally {
            await(client.close())
            await(server.close())
        }
    }

    private fun inbound(materialId: String, lotId: String?, quantity: String, unitCost: String): Future<JsonObject> =
        stockService.confirmInbound(
            StockService.InboundCommand(
                warehouse = WAREHOUSE,
                items = listOf(StockService.InboundItem(materialId, lotId, quantity = decimal(quantity), unitCost = decimal(unitCost))),
                note = "fixture inbound",
            ),
        )

    private fun consumptionCommand(
        executionId: String,
        stockId: String,
        quantity: String,
    ): InventoryConsumptionService.NursingConsumptionCommand =
        InventoryConsumptionService.NursingConsumptionCommand(
            items = listOf(
                InventoryConsumptionService.ConsumptionItem(
                    stockId = stockId,
                    quantity = decimal(quantity),
                ),
            ),
            taskExecutionId = executionId,
            taskId = TASK_ID,
            periodId = PERIOD_ID,
            patientId = "sc-patient",
            executor = "sc-tester",
            businessTime = OffsetDateTime.now(),
        )

    private fun consume(command: InventoryConsumptionService.NursingConsumptionCommand): InventoryConsumptionService.ConsumptionResult =
        requireNotNull(await(consumeFuture(command)))

    private fun consumeFuture(
        command: InventoryConsumptionService.NursingConsumptionCommand,
    ): Future<InventoryConsumptionService.ConsumptionResult?> =
        pool.withTransaction { connection ->
            consumptionService.consumeForNursingExecution(connection, command)
                .map<InventoryConsumptionService.ConsumptionResult?> { it }
        }

    private fun stockId(materialId: String, lotId: String? = null): String =
        queryOne(
            "SELECT id FROM public.stocks WHERE warehouse = '$WAREHOUSE' AND material_id = '$materialId' " +
                if (lotId == null) "AND lot_id IS NULL" else "AND lot_id = '$lotId'",
        )["id"] as String

    private fun stockState(stockId: String): StockState {
        val row = queryOne("SELECT quantity, locked_quantity, total_cost FROM public.stocks WHERE id = '$stockId'")
        return StockState(
            row["quantity"] as java.math.BigDecimal,
            row["locked_quantity"] as java.math.BigDecimal,
            row["total_cost"] as java.math.BigDecimal,
        )
    }

    private fun queryOne(sql: String): Map<String, Any?> {
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    assertTrue(result.next(), "expected one row for query: $sql")
                    val metadata = result.metaData
                    return (1..metadata.columnCount).associate { index ->
                        metadata.getColumnLabel(index).lowercase() to result.getObject(index)
                    }
                }
            }
        }
    }

    private fun count(table: String, condition: String): Long =
        (queryOne("SELECT count(*) AS total FROM $table WHERE $condition")["total"] as Number).toLong()

    private fun cleanupFixture() {
        if (!::jdbcUrl.isInitialized) return
        DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM nursing.nursing_task_execution_consumptions WHERE task_execution_id LIKE '$FIXTURE_PREFIX%'")
                statement.execute("DELETE FROM nursing.nursing_task_executions WHERE id LIKE '$FIXTURE_PREFIX%'")
                statement.execute("DELETE FROM nursing.nursing_tasks WHERE id LIKE '$FIXTURE_PREFIX%'")
                statement.execute("DELETE FROM nursing.nursing_service_periods WHERE id LIKE '$FIXTURE_PREFIX%'")
                statement.execute("DELETE FROM public.stock_operation_details WHERE operation_id IN (SELECT id FROM public.stock_operations WHERE warehouse = '$WAREHOUSE')")
                statement.execute("DELETE FROM public.stock_operations WHERE warehouse = '$WAREHOUSE'")
                statement.execute("DELETE FROM public.stocks WHERE warehouse = '$WAREHOUSE'")
                statement.execute("DELETE FROM public.lots WHERE id LIKE '$FIXTURE_PREFIX%'")
                statement.execute("DELETE FROM public.materials WHERE id LIKE '$FIXTURE_PREFIX%'")
            }
        }
    }

    private fun expectFailure(future: Future<*>): Throwable {
        val error = assertThrows<ExecutionException> {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
        }
        return error.rootCause()
    }

    private fun <T> await(future: Future<T>): T =
        future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)

    private fun <T> await(future: CompletableFuture<T>): T =
        future.get(20, TimeUnit.SECONDS)

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current !is InventoryConflictException && current !is ConflictException) {
            current = current.cause!!
        }
        return current
    }

    private fun decimal(value: String) = java.math.BigDecimal(value)

    private fun assertDecimalEquals(expected: String, actual: java.math.BigDecimal) {
        assertEquals(0, decimal(expected).compareTo(actual), "expected $expected but got $actual")
    }

    private data class StockState(
        val quantity: java.math.BigDecimal,
        val lockedQuantity: java.math.BigDecimal,
        val totalCost: java.math.BigDecimal,
    )
}
