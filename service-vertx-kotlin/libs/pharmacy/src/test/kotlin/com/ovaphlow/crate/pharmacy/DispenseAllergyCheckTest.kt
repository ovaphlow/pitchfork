package com.ovaphlow.crate.pharmacy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 054 D3：发药前药物过敏交叉检查接线的**不连数据库**单元测试。
 *
 * 锁定语义：
 *  1. 严重提醒且未二次确认 → 在**开启事务之前**失败（[AllergyAcknowledgementRequiredException]，路由 409），
 *     不产生库存/单据副作用；
 *  2. 二次确认（`acknowledged_warnings: true` 或非空数组）→ 放行，响应带
 *     `allergy_warnings` + `requires_acknowledgement=false`；
 *  3. 一般提醒只提示、不阻断；无提醒零误报；
 *  4. 端口返回 `null`（无法判定：药品不在目录/非药品/已停用等域判定）→ 不阻断（054 L16 向后兼容）；
 *  5. 端口未接线 → **完全不检查**（不查库、不追加响应字段），行为与接线前一致；
 *  6. 端口基础设施故障 → 向上传播（fail-closed，不静默跳过安全提示）。
 */
class DispenseAllergyCheckTest {

    private lateinit var pool: Pool
    private lateinit var reader: MedicalOrderReader
    private lateinit var outboundPort: InventoryOutboundPort

    @BeforeEach
    fun setUp() {
        pool = mockk()
        reader = mockk()
        outboundPort = mockk()
    }

    // ========================================================================
    //  严重提醒：二次确认
    // ========================================================================

    @Test
    fun `严重提醒未二次确认时在事务前失败`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        val service = serviceWith(portOf { _, _ -> resultOf(severe("过敏史", "青霉素")) })

        val error = failureOf(service.confirm("d1", JsonObject()))

        val acknowledgement = assertInstanceOf(AllergyAcknowledgementRequiredException::class.java, error)
        assertEquals(1, acknowledgement.warnings.size(), "错误体必须携带提醒明细")
        assertEquals(
            "严重",
            acknowledgement.warnings.getJsonObject(0).getString("severity"),
        )
        assertEquals("mat-1", acknowledgement.warnings.getJsonObject(0).getString("material_id"))
        verify(exactly = 0) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `严重提醒已二次确认时放行`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        stubTransaction()
        val service = serviceWith(portOf { _, _ -> resultOf(severe("交叉反应", "PENICILLIN_GROUP")) })

        val result = service.confirm("d1", JsonObject().put("acknowledged_warnings", true)).result()

        assertEquals(1, result.getJsonArray("allergy_warnings")?.size() ?: -1, "提醒必须随成功响应返回")
        assertEquals(false, result.getBoolean("requires_acknowledgement"))
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `非空数组形式的二次确认同样放行`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        stubTransaction()
        val service = serviceWith(portOf { _, _ -> resultOf(severe("过敏史", "青霉素")) })

        val result = service
            .confirm("d1", JsonObject().put("acknowledged_warnings", JsonArray().add("过敏史:青霉素")))
            .result()

        assertEquals(false, result.getBoolean("requires_acknowledgement"))
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `空数组视为未确认`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        val service = serviceWith(portOf { _, _ -> resultOf(severe("过敏史", "青霉素")) })

        val error = failureOf(service.confirm("d1", JsonObject().put("acknowledged_warnings", JsonArray())))

        assertInstanceOf(AllergyAcknowledgementRequiredException::class.java, error)
        verify(exactly = 0) { pool.withTransaction<JsonObject>(any()) }
    }

    // ========================================================================
    //  一般提醒 / 零误报 / 无法判定
    // ========================================================================

    @Test
    fun `一般提醒只提示不阻断`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        stubTransaction()
        val service = serviceWith(portOf { _, _ -> resultOf(general("年龄禁忌", "AGE_NEONATE")) })

        val result = service.confirm("d1", JsonObject()).result()

        assertEquals(1, result.getJsonArray("allergy_warnings")?.size() ?: -1)
        assertEquals(false, result.getBoolean("requires_acknowledgement"), "一般项不需要二次确认")
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `无提醒时零误报`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        stubTransaction()
        val service = serviceWith(portOf { _, _ -> DrugAllergyCheckResult(drugName = "阿司匹林") })

        val result = service.confirm("d1", JsonObject()).result()

        assertEquals(0, result.getJsonArray("allergy_warnings")?.size() ?: -1)
        assertEquals(false, result.getBoolean("requires_acknowledgement"))
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `端口返回无法判定时不阻断`() {
        stubAllergyInputs(listOf("pat-1" to "mat-consumable"))
        stubTransaction()
        val service = serviceWith(portOf { _, _ -> null })

        val result = service.confirm("d1", JsonObject()).result()

        assertEquals(0, result.getJsonArray("allergy_warnings")?.size() ?: -1)
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    // ========================================================================
    //  未接线 / 基础设施故障 / 多药品
    // ========================================================================

    @Test
    fun `端口未接线时完全不检查且响应不追加字段`() {
        stubTransaction()
        val service = serviceWith(null)

        val result = service.confirm("d1", JsonObject()).result()

        assertTrue(result.containsKey("allergy_warnings").not(), "未接线时响应必须与接线前逐字一致")
        assertTrue(result.containsKey("requires_acknowledgement").not())
        verify(exactly = 0) { pool.preparedQuery(any<String>()) }
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `端口基础设施故障时向上传播`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1"))
        val service = serviceWith(
            object : DrugAllergyCheckPort {
                override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> =
                    Future.failedFuture(RuntimeException("healthcare unavailable"))
            },
        )

        val error = failureOf(service.confirm("d1", JsonObject()))

        assertEquals("healthcare unavailable", error.message)
        verify(exactly = 0) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `无发药明细时跳过检查`() {
        stubAllergyInputs(emptyList())
        stubTransaction()
        val calls = mutableListOf<String>()
        val service = serviceWith(
            portOf { _, materialId -> calls.add(materialId); null },
        )

        service.confirm("d1", JsonObject()).result()

        assertTrue(calls.isEmpty(), "无明细不得调用检查端口")
        verify(exactly = 1) { pool.withTransaction<JsonObject>(any()) }
    }

    @Test
    fun `多药品提醒合并且按药品标注`() {
        stubAllergyInputs(listOf("pat-1" to "mat-1", "pat-1" to "mat-2", "pat-1" to "mat-1"))
        stubTransaction()
        val calls = mutableListOf<String>()
        val service = serviceWith(
            portOf { _, materialId ->
                calls.add(materialId)
                if (materialId == "mat-1") resultOf(general("过敏史", "青霉素")) else null
            },
        )

        val result = service.confirm("d1", JsonObject()).result()

        assertEquals(listOf("mat-1", "mat-2"), calls, "同一药品只检查一次，且按首次出现顺序")
        val warnings = result.getJsonArray("allergy_warnings")!!
        assertEquals(1, warnings.size())
        assertEquals("mat-1", warnings.getJsonObject(0).getString("material_id"))
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun serviceWith(port: DrugAllergyCheckPort?): DispenseService =
        DispenseService(pool, reader, outboundPort, null, port)

    private fun portOf(handler: (String, String) -> DrugAllergyCheckResult?): DrugAllergyCheckPort =
        object : DrugAllergyCheckPort {
            override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> =
                Future.succeededFuture(handler(patientId, materialId))
        }

    private fun resultOf(vararg warnings: AllergyWarning) = DrugAllergyCheckResult(
        drugName = "阿司匹林",
        drugCode = "DEMO-DRUG-001",
        warnings = warnings.toList(),
    )

    private fun severe(source: String, matched: String) =
        AllergyWarning("严重", "命中$source：$matched", source, matched)

    private fun general(source: String, matched: String) =
        AllergyWarning("一般", "提示$source：$matched", source, matched)

    private fun stubAllergyInputs(inputs: List<Pair<String, String>>) {
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } returns pq
        every { pq.execute(any<Tuple>()) } returns Future.succeededFuture(
            rowSet(inputs.map { (patientId, materialId) -> inputRow(patientId, materialId) }),
        )
    }

    private fun stubTransaction(result: JsonObject = JsonObject().put("id", "d1").put("status", "DISPENSED")) {
        every { pool.withTransaction<JsonObject>(any()) } returns Future.succeededFuture(result)
    }

    private fun inputRow(patientId: String, materialId: String): Row = mockk(relaxed = true) {
        every { getString("patient_id") } returns patientId
        every { getString("material_id") } returns materialId
    }

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }

    private fun rowSet(rows: List<Row>): RowSet<Row> = mockk(relaxed = true) {
        every { size() } returns rows.size
        every { rowCount() } returns rows.size
        every { iterator() } answers {
            val delegate = rows.iterator()
            mockk<RowIterator<Row>>(relaxed = true) {
                every { hasNext() } answers { delegate.hasNext() }
                every { next() } answers { delegate.next() }
            }
        }
    }
}
