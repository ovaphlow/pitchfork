package com.ovaphlow.crate.inventories

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * MaterialService 016 单一基础单位物资服务单元测试。
 *
 * 覆盖：创建/更新的请求白名单（旧拆零/换算/规格字段一律 400）、quantity_scale 0..6、
 * 包装规格成对校验、package_size 十进制文本校验、base_unit/quantity_scale 存在库存事实后
 * 不可变（409），以及 package_size 以十进制文本无损往返。不依赖数据库。
 */
class MaterialServiceTest {

    private lateinit var pool: Pool
    private lateinit var service: MaterialService

    @BeforeEach
    fun setUp() {
        pool = mockk<Pool>()
        service = MaterialService(pool)
    }

    // ========================================================================
    //  create：必填字段
    // ========================================================================

    @Test
    fun `create rejects missing required fields`() {
        val missing = listOf("code", "name", "category", "base_unit")
        for (field in missing) {
            val body = materialBody()
            body.remove(field)
            val error = failureOf(service.create(body))
            assertTrue(error.message!!.contains(field), "缺少 $field 应报错，实际: ${error.message}")
        }
    }

    // ========================================================================
    //  create：未知字段白名单（旧拆零/换算/规格字段一律拒绝）
    // ========================================================================

    @Test
    fun `create rejects legacy split and conversion fields`() {
        for (field in listOf("split_unit", "split_ratio", "unit_spec_id", "input_quantity", "conversion_ratio", "split_quantity", "unit")) {
            val body = materialBody().put(field, "whatever")
            val error = failureOf(service.create(body))
            assertTrue(error.message!!.contains("unknown fields"), "字段 $field 应被白名单拒绝，实际: ${error.message}")
            assertTrue(error.message!!.contains(field))
        }
    }

    // ========================================================================
    //  create：quantity_scale 0..6
    // ========================================================================

    @Test
    fun `create rejects quantity_scale outside 0 to 6`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create(materialBody().put("quantity_scale", 7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.create(materialBody().put("quantity_scale", -1))
        }
        // 合法边界 0 与 6 不抛错（校验发生在数据库访问之前）
        stubPool(anyRowSet(), anyRowSet())
        service.create(materialBody().put("quantity_scale", 0))
        service.create(materialBody().put("quantity_scale", 6))
    }

    // ========================================================================
    //  create：包装规格成对校验与十进制文本
    // ========================================================================

    @Test
    fun `create rejects package spec with only package unit`() {
        val error = runCatching { service.create(materialBody().put("package_unit", "盒")) }.exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(error!!.message!!.contains("both set or both empty"), "实际: ${error.message}")
    }

    @Test
    fun `create rejects package spec with only package size`() {
        val error = runCatching { service.create(materialBody().put("package_size", "24")) }.exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(error!!.message!!.contains("both set or both empty"), "实际: ${error.message}")
    }

    @Test
    fun `create rejects non positive package size`() {
        val body = materialBody().put("package_unit", "盒").put("package_size", "0")
        val error = runCatching { service.create(body) }.exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(error!!.message!!.contains("greater than 0"), "实际: ${error.message}")
    }

    @Test
    fun `create rejects package size as JSON number`() {
        val body = materialBody().put("package_unit", "盒").put("package_size", 24)
        val error = runCatching { service.create(body) }.exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(error!!.message!!.contains("decimal text"), "实际: ${error.message}")
    }

    // ========================================================================
    //  create：成功路径 — package_size 十进制文本无损往返
    // ========================================================================

    @Test
    fun `create preserves package size as decimal text`() {
        stubPool(anyRowSet())
        val json = service.create(
            materialBody()
                .put("package_unit", "盒")
                .put("package_size", "24.000000"),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals("24.000000", json.getString("package_size"), "package_size 必须以十进制文本原样往返")
        assertEquals("盒", json.getString("package_unit"))
        assertEquals("片", json.getString("base_unit"))
        assertEquals(0, json.getInteger("quantity_scale"))
    }

    // ========================================================================
    //  update：白名单与包装规格校验
    // ========================================================================

    @Test
    fun `update rejects unknown fields`() {
        val body = JsonObject().put("split_ratio", "2")
        val error = failureOf(service.update("mat-1", body))
        assertTrue(error.message!!.contains("unknown fields"), "实际: ${error.message}")
    }

    @Test
    fun `update rejects package spec pair violation`() {
        stubPool(materialRowSet())
        val body = JsonObject().put("package_unit", "瓶")
        val error = failureOf(service.update("mat-1", body))
        assertTrue(error.message!!.contains("both set or both empty"), "实际: ${error.message}")
    }

    // ========================================================================
    //  update：base_unit/quantity_scale 存在库存事实后不可变（409）
    // ========================================================================

    @Test
    fun `update blocks base_unit change when stock facts exist`() {
        stubPool(materialRowSet(), stockRowSet(exists = true))
        val body = JsonObject().put("base_unit", "粒")
        val error = failureOf(service.update("mat-1", body))
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("immutable"), "实际: ${error.message}")
    }

    @Test
    fun `update blocks quantity_scale change when stock facts exist`() {
        stubPool(materialRowSet(), stockRowSet(exists = true))
        val body = JsonObject().put("quantity_scale", 3)
        val error = failureOf(service.update("mat-1", body))
        assertInstanceOf(ConflictException::class.java, error)
        assertTrue(error.message!!.contains("immutable"), "实际: ${error.message}")
    }

    @Test
    fun `update allows base_unit change when no stock facts exist`() {
        stubPool(materialRowSet(), stockRowSet(exists = false), anyRowSet(), materialRowSet())
        val json = service.update("mat-1", JsonObject().put("base_unit", "粒"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("片", json.getString("base_unit"), "返回物资本身字段")
    }

    @Test
    fun `update allows package spec change without touching immutability check`() {
        stubPool(materialRowSet(), anyRowSet(), materialRowSet())
        val json = service.update("mat-1", JsonObject().put("package_unit", "瓶").put("package_size", "500"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals("片", json.getString("base_unit"), "更新包装规格不改变基础单位")
    }

    // ========================================================================
    //  list：{ records, meta.total } 列表格式
    // ========================================================================

    @Test
    fun `list returns records with meta total`() {
        val countRow = mockk<Row>(relaxed = true) {
            every { getLong("total") } returns 2L
        }
        val countRowSet = mockk<RowSet<Row>>(relaxed = true) {
            every { size() } returns 1
            every { iterator() } returns iteratorOf(countRow)
        }
        val materialA = materialRow("mat-a", "AMX-001", "阿莫西林")
        val materialB = materialRow("mat-b", "NC-002", "护理垫")
        val dataRowSet = mockk<RowSet<Row>>(relaxed = true) {
            every { size() } returns 2
            every { iterator() } returns iteratorOf(materialA, materialB)
        }
        stubPool(countRowSet, dataRowSet)

        val json = service.list().toCompletionStage().toCompletableFuture().get()

        assertEquals(2, json.getJsonArray("records").size(), "records 为空列表以外的条目数")
        assertEquals(2L, json.getJsonObject("meta").getLong("total"))
        assertEquals("mat-a", json.getJsonArray("records").getJsonObject(0).getString("id"))
        assertEquals("片", json.getJsonArray("records").getJsonObject(0).getString("base_unit"))
    }

    // ========================================================================
    //  toJson：package_size 十进制文本
    // ========================================================================

    @Test
    fun `toJson preserves package size as plain decimal text`() {
        val row = mockk<Row>(relaxed = true) {
            every { getValue("id") } returns "mat-1"
            every { getValue("base_unit") } returns "片"
            every { getValue("quantity_scale") } returns 0
            every { getValue("package_unit") } returns "盒"
            every { getValue("package_size") } returns BigDecimal("24.000000")
            every { getValue("created_at") } returns "2026-08-07T10:00:00Z"
        }
        val json = MaterialService.toJson(row)
        assertEquals("24.000000", json.getString("package_size"), "NUMERIC(20,6) 不得经 Double 截断")
        assertNull(json.getValue("split_ratio"))
        assertNull(json.getValue("split_unit"))
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun materialBody(): JsonObject =
        JsonObject()
            .put("code", "AMX-001")
            .put("name", "阿莫西林")
            .put("category", "药品")
            .put("base_unit", "片")
            .put("quantity_scale", 0)
            .put("enable_batch_control", false)
            .put("cost_method", "AVERAGE")
            .put("status", "ACTIVE")

    private fun stubPool(vararg results: RowSet<Row>) {
        val pq = mockk<PreparedQuery<RowSet<Row>>>()
        every { pool.preparedQuery(any<String>()) } returns pq
        val queue = ArrayDeque(results.toList())
        every { pq.execute(any<Tuple>()) } answers {
            if (queue.isEmpty()) throw AssertionError("unexpected extra database query")
            Future.succeededFuture(queue.removeFirst())
        }
        every { pq.execute() } returns Future.succeededFuture(anyRowSet())
    }

    private fun anyRowSet(): RowSet<Row> =
        mockk<RowSet<Row>>(relaxed = true) {
            every { size() } returns 0
        }

    /** 返回一行物资（base_unit=片、quantity_scale=0、包装 盒/24），与 016 fixture 一致。 */
    private fun materialRowSet(): RowSet<Row> {
        val row = materialRow("mat-1", "AMX-001", "阿莫西林")
        return mockk<RowSet<Row>>(relaxed = true) {
            every { size() } returns 1
            every { iterator() } returns iteratorOf(row)
        }
    }

    private fun materialRow(id: String, code: String, name: String): Row =
        mockk<Row>(relaxed = true) {
            every { getValue("id") } returns id
            every { getValue("code") } returns code
            every { getValue("name") } returns name
            every { getValue("category") } returns "药品"
            every { getValue("spec") } returns null
            every { getValue("base_unit") } returns "片"
            every { getValue("package_unit") } returns "盒"
            every { getValue("package_size") } returns BigDecimal("24")
            every { getValue("quantity_scale") } returns 0
            every { getValue("enable_batch_control") } returns false
            every { getValue("cost_method") } returns "AVERAGE"
            every { getValue("metadata") } returns null
            every { getValue("status") } returns "ACTIVE"
            every { getValue("created_at") } returns "2026-08-07T10:00:00Z"
            every { getValue("updated_at") } returns null
        }

    private fun iteratorOf(vararg rows: Row): RowIterator<Row> {
        val delegate = rows.iterator()
        return mockk<RowIterator<Row>> {
            every { hasNext() } answers { delegate.hasNext() }
            every { next() } answers { delegate.next() }
        }
    }

    private fun stockRowSet(exists: Boolean): RowSet<Row> =
        mockk<RowSet<Row>>(relaxed = true) {
            every { size() } returns if (exists) 1 else 0
        }

    private fun failureOf(future: Future<*>): Throwable {
        val failures = mutableListOf<Throwable>()
        future.onFailure { failures.add(it) }
        return failures.single()
    }
}
