package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * DispenseService 016 基础数量发药单元测试 + 025 药品目录绑定一致性/补绑单元测试。
 * 覆盖请求白名单、必填校验、操作人要求、绑定不一致 409、补绑成功与各类失败；不依赖数据库。
 */
class DispenseServiceTest {

    private lateinit var pool: Pool
    private lateinit var reader: MedicalOrderReader
    private lateinit var outboundPort: InventoryOutboundPort
    private lateinit var catalogPort: DrugCatalogPort
    private lateinit var service: DispenseService

    @BeforeEach
    fun setUp() {
        pool = mockk()
        reader = mockk()
        outboundPort = mockk()
        catalogPort = object : DrugCatalogPort {
            override fun findDrugMaterial(client: SqlClient, materialId: String): Future<DrugCatalogMaterial?> =
                Future.succeededFuture(
                    when (materialId) {
                        "mat-1" -> DrugCatalogMaterial(
                            id = "mat-1",
                            code = "DEMO-DRUG-001",
                            name = "阿司匹林",
                            spec = "100mg/片",
                            baseUnit = "片",
                            status = "ACTIVE",
                            category = "药品",
                        )
                        // 耗材：category 非药品，走补绑时必须 409
                        "mat-consumable" -> DrugCatalogMaterial(
                            id = "mat-consumable",
                            code = "CONS-001",
                            name = "纱布",
                            spec = null,
                            baseUnit = "包",
                            status = "ACTIVE",
                            category = "耗材",
                        )
                        else -> null
                    },
                )
        }
        service = DispenseService(pool, reader, outboundPort, catalogPort)
    }

    private fun failureOf(future: io.vertx.core.Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    /** 未核对快照：护士核对门禁必须拦截，即使列表过滤被绕过 */
    private fun unCheckedSnapshot(
        nurseCheckedBy: String? = null,
        nurseCheckedAt: OffsetDateTime? = null,
        materialId: String? = null,
        orderDetails: JsonObject = JsonObject(),
    ) = MedicationOrderSnapshot(
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
        orderDetails = orderDetails,
        nurseCheckedBy = nurseCheckedBy,
        nurseCheckedAt = nurseCheckedAt,
        materialId = materialId,
        materialCode = materialId?.let { "DEMO-DRUG-001" },
        materialName = materialId?.let { "阿司匹林" },
    )

    /** 已核对快照（护士核对门禁通过），绑定状态由 materialId 决定 */
    private fun checkedSnapshot(materialId: String? = null, orderDetails: JsonObject = JsonObject()) =
        unCheckedSnapshot(
            nurseCheckedBy = "nurse-1",
            nurseCheckedAt = OffsetDateTime.parse("2026-08-01T10:00:00+08:00"),
            materialId = materialId,
            orderDetails = orderDetails,
        )

    /** 事务连接桩：发药单/明细写入与重复接方查询返回空行集 */
    private fun stubbedConnection(): SqlConnection {
        val connection = mockk<SqlConnection>()
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { connection.preparedQuery(any<String>()) } returns pq
        every { connection.preparedQuery(any<String>(), any()) } returns pq
        every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(mockk<RowSet<Row>>(relaxed = true))
        every { pq.execute() } returns Future.succeededFuture(mockk<RowSet<Row>>(relaxed = true))
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        return connection
    }

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
        // 已绑定且与请求一致：走一致性校验分支，不触发补绑
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(checkedSnapshot(materialId = "mat-1"))
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
        verify(exactly = 0) {
            reader.bindDrugMaterial(any(), any(), any(), any(), any(), any())
        }
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

    // ========================================================================
    //  025 医嘱-药品目录绑定一致性校验与存量医嘱补绑
    // ========================================================================

    @Test
    fun `发药 material_id 与医嘱绑定不一致返回 409 且不触碰库存`() {
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(checkedSnapshot(materialId = "mat-1"))

        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-other")
                    .put("dispensed_quantity", "5"),
                operator = "pharmacist-1",
            ),
        )

        assertInstanceOf(ConflictException::class.java, error)
        assertEquals("material_id does not match the prescribed drug", error.message)
        // 不一致必须先于库存校验与任何写入失败：无出库校验、无补绑
        verify(exactly = 0) { outboundPort.validateOutbound(any(), any()) }
        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `已绑定医嘱与请求一致时放行且不补绑`() {
        val connection = stubbedConnection()
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(checkedSnapshot(materialId = "mat-1"))
        every { outboundPort.validateOutbound(connection, any()) } returns Future.succeededFuture(null)

        service.createFromMedicalOrder(
            JsonObject()
                .put("medical_order_id", "order-1")
                .put("warehouse", "西药库")
                .put("material_id", "mat-1")
                .put("dispensed_quantity", "5"),
            operator = "pharmacist-1",
        ).toCompletionStage().toCompletableFuture().get()

        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `存量无绑定医嘱补绑成功并带目录快照与操作人`() {
        val connection = stubbedConnection()
        // 存量自由文本医嘱：order_details 只有 drug_name，没有 material_id
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(
                checkedSnapshot(orderDetails = JsonObject().put("drug_name", "阿司匹林")),
            )
        val bound = io.mockk.slot<String>()
        every {
            reader.bindDrugMaterial(connection, "order-1", "mat-1", "DEMO-DRUG-001", "阿司匹林", capture(bound))
        } returns Future.succeededFuture(null)
        every { outboundPort.validateOutbound(connection, any()) } returns Future.succeededFuture(null)

        service.createFromMedicalOrder(
            JsonObject()
                .put("medical_order_id", "order-1")
                .put("warehouse", "西药库")
                .put("material_id", "mat-1")
                .put("dispensed_quantity", "5"),
            operator = "pharmacist-1",
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("pharmacist-1", bound.captured, "补绑操作人必须为服务端解析身份")
        verify(exactly = 1) {
            reader.bindDrugMaterial(connection, "order-1", "mat-1", "DEMO-DRUG-001", "阿司匹林", "pharmacist-1")
        }
    }

    @Test
    fun `补绑路径请求目录外物资返回 404 且不补绑`() {
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(
                checkedSnapshot(orderDetails = JsonObject().put("drug_name", "阿司匹林")),
            )

        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-unknown")
                    .put("dispensed_quantity", "5"),
                operator = "pharmacist-1",
            ),
        )

        assertInstanceOf(NotFoundException::class.java, error)
        assertTrue(error.message!!.contains("material not found"), "got: ${error.message}")
        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `补绑路径请求非药品物资返回 409 且不补绑`() {
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(
                checkedSnapshot(orderDetails = JsonObject().put("drug_name", "阿司匹林")),
            )

        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-consumable")
                    .put("dispensed_quantity", "5"),
                operator = "pharmacist-1",
            ),
        )

        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("material is not a drug"), "got: ${error.message}")
        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `补绑路径缺少认证身份返回 401 且不补绑`() {
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(
                checkedSnapshot(orderDetails = JsonObject().put("drug_name", "阿司匹林")),
            )

        // operator 缺省（无认证上下文）：不得写 null 审计，必须 401
        val error = failureOf(
            service.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-1")
                    .put("dispensed_quantity", "5"),
            ),
        )

        assertInstanceOf(UnauthorizedException::class.java, error)
        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { outboundPort.validateOutbound(any(), any()) }
    }

    @Test
    fun `补绑路径药品目录端口未配置时 fail_closed 返回 503`() {
        val noPortService = DispenseService(pool, reader, outboundPort)
        val connection = mockk<SqlConnection>()
        every { pool.withTransaction<JsonObject>(any()) } answers {
            firstArg<JavaFunction<SqlConnection, Future<JsonObject>>>().apply(connection)
        }
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(
                checkedSnapshot(orderDetails = JsonObject().put("drug_name", "阿司匹林")),
            )

        val error = failureOf(
            noPortService.createFromMedicalOrder(
                JsonObject()
                    .put("medical_order_id", "order-1")
                    .put("warehouse", "西药库")
                    .put("material_id", "mat-1")
                    .put("dispensed_quantity", "5"),
                operator = "pharmacist-1",
            ),
        )

        assertInstanceOf(DrugCatalogUnavailableException::class.java, error)
        assertTrue(error.message!!.contains("not configured"), "got: ${error.message}")
        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `医嘱绑定已存在但请求一致时不触发补绑与库存拒绝`() {
        // 契约：已绑定医嘱走一致性校验分支，即使 order_details 同时携带历史自由文本 drug_name
        val connection = stubbedConnection()
        every { reader.lockMedicationOrder(connection, "order-1") } returns
            Future.succeededFuture(
                checkedSnapshot(
                    materialId = "mat-1",
                    orderDetails = JsonObject().put("drug_name", "阿司匹林"),
                ),
            )
        every { outboundPort.validateOutbound(connection, any()) } returns Future.succeededFuture(null)

        service.createFromMedicalOrder(
            JsonObject()
                .put("medical_order_id", "order-1")
                .put("warehouse", "西药库")
                .put("material_id", "mat-1")
                .put("dispensed_quantity", "5"),
            operator = "pharmacist-1",
        ).toCompletionStage().toCompletableFuture().get()

        verify(exactly = 0) { reader.bindDrugMaterial(any(), any(), any(), any(), any(), any()) }
    }
}
