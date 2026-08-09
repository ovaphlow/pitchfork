package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * DispenseService 016 基础数量发药单元测试。
 * 覆盖请求白名单、必填校验与操作人要求；不依赖数据库。
 */
class DispenseServiceTest {

    private lateinit var pool: Pool
    private lateinit var reader: MedicalOrderReader
    private lateinit var outboundPort: InventoryOutboundPort
    private lateinit var service: DispenseService

    @BeforeEach
    fun setUp() {
        pool = mockk()
        reader = mockk()
        outboundPort = mockk()
        service = DispenseService(pool, reader, outboundPort)
    }

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    /** 未核对快照：护士核对门禁必须拦截，即使列表过滤被绕过 */
    private fun unCheckedSnapshot(nurseCheckedBy: String? = null, nurseCheckedAt: OffsetDateTime? = null) =
        MedicationOrderSnapshot(
            orderId = "order-1",
            encounterId = "enc-1",
            patientId = "pat-1",
            patientName = "测试患者",
            encounterNo = "A20260801001",
            encounterType = "ELDERLY_CARE",
            encounterStatus = "ACTIVE",
            orderType = "MEDICATION",
            orderClass = "LONG_TERM",
            orderStatus = "ACTIVE",
            orderContent = "阿司匹林 100mg 每日一次",
            doctor = "赵医生",
            startTime = OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            endTime = null,
            orderDetails = JsonObject(),
            nurseCheckedBy = nurseCheckedBy,
            nurseCheckedAt = nurseCheckedAt,
        )

    private fun dispenseBody(): JsonObject =
        JsonObject()
            .put("medical_order_id", "order-1")
            .put("warehouse", "西药库")
            .put("material_id", "mat-1")
            .put("dispensed_quantity", "5")

    @Test
    fun `createFromMedicalOrder rejects missing medical order id`() {
        val error = failureOf(service.createFromMedicalOrder(JsonObject()))
        assertTrue(error.message!!.contains("medical_order_id"))
    }

    @Test
    fun `createFromMedicalOrder rejects missing warehouse and material`() {
        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1"),
            ),
        )
        assertTrue(error.message!!.contains("warehouse"))
    }

    @Test
    fun `createFromMedicalOrder rejects non positive dispensed quantity`() {
        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-1")
                    .put("dispensed_quantity", "0"),
            ),
        )
        assertTrue(error.message!!.contains("dispensed_quantity"))
    }

    @Test
    fun `createFromMedicalOrder rejects legacy unit field`() {
        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-1")
                    .put("dispensed_quantity", "5")
                    .put("unit", "PACKAGE"),
            ),
        )
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `review requires operator before touching database`() {
        val error = failureOf(service.review("dispense-1", JsonObject()))
        assertTrue(error.message!!.contains("operator"))
    }

    @Test
    fun `start requires operator before touching database`() {
        val error = failureOf(service.start("dispense-1", JsonObject()))
        assertTrue(error.message!!.contains("operator"))
    }

    @Test
    fun `updateStatus rejects blank status`() {
        val error = failureOf(service.updateStatus("dispense-1", JsonObject()))
        assertTrue(error.message!!.contains("status"))
    }

    @Test
    fun `legacyCreate rejects unknown body fields`() {
        val error = failureOf(
            service.create(
                JsonObject()
                    .put("dispense_type", "OUTPATIENT")
                    .put("unit", "PACKAGE"),
            ),
        )
        assertTrue(error.message!!.contains("unknown fields"))
    }

    @Test
    fun `createFromMedicalOrder rejects order that has not been nurse checked`() {
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(unCheckedSnapshot())

        val error = failureOf(service.createFromMedicalOrder(dispenseBody()))
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("nurse-checked"), "got: ${error.message}")
    }

    @Test
    fun `createFromMedicalOrder rejects order with checker but no checked time`() {
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        // 核对审计字段必须成对出现，只有核对人没有核对时间同样视为未核对
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(unCheckedSnapshot(nurseCheckedBy = "nurse-1"))

        val error = failureOf(service.createFromMedicalOrder(dispenseBody()))
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("nurse-checked"), "got: ${error.message}")
    }

    @Test
    fun `createFromMedicalOrder 传出的发药数量为精确十进制文本构造`() {
        val connection = mockk<SqlConnection>()
        val pq = mockk<io.vertx.sqlclient.PreparedQuery<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>>()
        every { connection.preparedQuery(any<String>()) } returns pq
        every { connection.preparedQuery(any<String>(), any()) } returns pq
        every { pq.execute(any<io.vertx.sqlclient.Tuple>()) } returns
            Future.succeededFuture(mockk<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>(relaxed = true))
        every { pq.execute() } returns
            Future.succeededFuture(mockk<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>>(relaxed = true))
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(unCheckedSnapshot(nurseCheckedBy = "nurse-1", nurseCheckedAt = OffsetDateTime.parse("2026-08-01T10:00:00+08:00")))
        val outboundSlot = io.mockk.slot<OutboundCommand>()
        every { outboundPort.validateOutbound(connection, capture(outboundSlot)) } returns Future.succeededFuture(null)

        service.createFromMedicalOrder(
            JsonObject()
                .put("medical_order_id", "order-1")
                .put("warehouse", "西药库")
                .put("material_id", "mat-1")
                .put("dispensed_quantity", "0.1"),
        ).toCompletionStage().toCompletableFuture().get()

        // 0.1 必须以十进制文本精确进入库存出库端口，绝无 double 二进制尾差
        assertEquals(BigDecimal("0.1"), outboundSlot.captured.quantity, "实际: ${outboundSlot.captured.quantity}")
    }

    @Test
    fun `createFromMedicalOrder 拒绝超过6位小数的发药数量`() {
        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-1")
                    .put("dispensed_quantity", "0.1234567"),
            ),
        )
        assertTrue(error.message!!.contains("6 decimals"), "got: ${error.message}")
    }

    @Test
    fun `createFromMedicalOrder rejects numeric JSON quantity`() {
        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-1")
                    .put("dispensed_quantity", 5),
            ),
        )
        assertTrue(error.message!!.contains("decimal text"))
    }
}
