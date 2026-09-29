package com.ovaphlow.crate.healthcare

import com.ovaphlow.crate.nursing.ConflictException
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 床位主数据（BedService）非数据库测试（mockk Pool 桩，不访问数据库）。
 *
 * 覆盖 030 §4.5 验收口径：
 *  - 创建：26 位 ULID、department/ward trim、状态默认 启用、可选 label/remark 省略即不写列
 *  - 参数校验文案：必填/空白/类型/50 字上限/remark 500 字/写白名单（status 只能走 PATCH）
 *  - 重复启用 (department, ward) → 409 `bed already exists: <department>/<ward>`；
 *    唯一索引 23505 兜底同样 409（约束名路径 + sqlState 路径）
 *  - 删除：占用中（ACTIVE 的 ELDERLY_CARE 入住）→ 409 `bed is occupied by an active admission`
 *  - 不存在 → 404 `bed not found: <id>`
 *  - 列表：`{records, meta:{total}}`、空列表 `records: []`/`total: 0`，
 *    SQL 形状 `LIMIT`/`OFFSET`/`ORDER BY created_at DESC, id DESC` 与过滤条件
 *
 * 路由（`HealthcareRoutes` 的 beds 段）由调度者接线，不在本文件覆盖范围内。
 */
class BedServiceTest {

    /**
     * 全库 mock 桩：`conn`/`pool` 的 preparedQuery 按 normalized SQL 特征分发；
     * insert/update/delete 落到内存 `beds`，select/count 据此派生（含 id <> 排除自身），
     * `admissions` 模拟 `healthcare.encounters` 的 ACTIVE/ELDERLY_CARE 占用事实来源。
     */
    private class BedStub(
        beds: MutableList<MutableMap<String, Any?>> = mutableListOf(),
        admissions: MutableList<MutableMap<String, Any?>> = mutableListOf(),
    ) {
        val beds: MutableList<MutableMap<String, Any?>> = beds
        val admissions: MutableList<MutableMap<String, Any?>> = admissions
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

        /** 非 null 时，床位 INSERT 返回失败（模拟并发撞部分唯一索引 23505）。 */
        var failBedInsert: Throwable? = null

        /** 非 null 时，床位 UPDATE（含状态流转）返回失败（模拟并发撞部分唯一索引 23505）。 */
        var failBedUpdate: Throwable? = null

        private var lastSql = ""
        private val conn = mockk<SqlConnection>()
        private val pq = mockk<PreparedQuery<RowSet<Row>>>()
        val pool = mockk<Pool>()

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg<String>()); pq }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg<String>()); pq }
            every { pq.execute(any<Tuple>()) } answers {
                val sql = lastSql
                val values = bedTupleValues(firstArg())
                tuples.add(sql to values)
                val failure = when {
                    sql.contains("insert into healthcare.beds") -> failBedInsert
                    sql.contains("update healthcare.beds") -> failBedUpdate
                    else -> null
                }
                if (failure != null) Future.failedFuture(failure)
                else Future.succeededFuture(dispatch(sql, values))
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun dispatch(sql: String, values: List<Any?>): RowSet<Row> = when {
            sql.contains("insert into healthcare.beds") -> {
                beds.add(insertedBed(sql, values))
                bedRowSet()
            }
            sql.contains("delete from healthcare.beds") -> {
                val id = boundValue(sql, values, "id")
                val removed = beds.removeIf { it["id"] == id }
                bedRowSet(rowCount = if (removed) 1 else 0)
            }
            sql.contains("update healthcare.beds") -> {
                val id = boundValue(sql, values, "id")
                val target = beds.firstOrNull { it["id"] == id }
                if (target != null) {
                    applyUpdate(target, sql, values)
                    bedRowSet(rowCount = 1)
                } else {
                    bedRowSet(rowCount = 0)
                }
            }
            sql.contains("count(*)") && sql.contains("from healthcare.encounters") ->
                bedRowSet(bedMockRow(mapOf("total" to matchingAdmissions(sql, values).size.toLong())))
            sql.contains("count(*)") && sql.contains("from healthcare.beds") ->
                bedRowSet(bedMockRow(mapOf("total" to filteredBeds(sql, values).size.toLong())))
            sql.contains("from healthcare.beds") -> bedRows(*filteredBeds(sql, values).toTypedArray())
            else -> bedRowSet()
        }

        private fun record(sql: String) {
            val normalizedSql = bedNormalized(sql)
            lastSql = normalizedSql
            queries.add(normalizedSql)
        }

        /** insert 列清单跟随 set 顺序渲染，按列名逐位回填（可选列未 set 时不出现在列清单里）。 */
        private fun insertedBed(sql: String, values: List<Any?>): MutableMap<String, Any?> {
            val match = Regex("insert into healthcare\\.beds \\(([^)]+)\\) values").find(sql)
                ?: error("cannot parse insert columns: $sql")
            val cols = match.groupValues[1].split(",").map { it.trim() }
            val record = mutableMapOf<String, Any?>()
            cols.forEachIndexed { index, col -> record[col] = values.getOrNull(index) }
            return record
        }

        /** `field = $n` 的绑定值（列名均为未限定小写名）。 */
        private fun boundValue(sql: String, values: List<Any?>, field: String): String? {
            val match = Regex("$field = \\$(\\d+)").find(sql) ?: return null
            return values.getOrNull(match.groupValues[1].toInt() - 1) as? String
        }

        /** `field <> $n` 的绑定值（启用态唯一检查排除自身）。 */
        private fun boundNotEqual(sql: String, values: List<Any?>, field: String): String? {
            val match = Regex("$field <> \\$(\\d+)").find(sql) ?: return null
            return values.getOrNull(match.groupValues[1].toInt() - 1) as? String
        }

        /** SQL 中被内联的字面量条件（如 encounter_type = 'ELDERLY_CARE'）。 */
        private fun literalValue(sql: String, field: String): String? =
            Regex("$field = '([^']*)'").find(sql)?.groupValues?.get(1)

        /** 条件值：jOOQ 默认把常量渲染成绑定参数，内联字面量作为兜底。 */
        private fun conditionValue(sql: String, values: List<Any?>, field: String): String? =
            boundValue(sql, values, field) ?: literalValue(sql, field)

        private fun filteredBeds(sql: String, values: List<Any?>): List<MutableMap<String, Any?>> {
            val id = boundValue(sql, values, "id")
            val excludeId = boundNotEqual(sql, values, "id")
            val department = boundValue(sql, values, "department")
            val ward = boundValue(sql, values, "ward")
            val status = boundValue(sql, values, "status")
            return beds.filter { bed ->
                (id == null || bed["id"] == id) &&
                    (excludeId == null || bed["id"] != excludeId) &&
                    (department == null || bed["department"] == department) &&
                    (ward == null || bed["ward"] == ward) &&
                    (status == null || bed["status"] == status)
            }.sortedWith(
                compareByDescending<MutableMap<String, Any?>> { it["created_at"] as? OffsetDateTime }
                    .thenByDescending { it["id"] as? String },
            )
        }

        private fun matchingAdmissions(sql: String, values: List<Any?>): List<MutableMap<String, Any?>> {
            val department = boundValue(sql, values, "department")
            val ward = boundValue(sql, values, "ward")
            val encounterType = conditionValue(sql, values, "encounter_type")
            val status = conditionValue(sql, values, "status")
            return admissions.filter { admission ->
                (department == null || admission["department"] == department) &&
                    (ward == null || admission["ward"] == ward) &&
                    (encounterType == null || admission["encounter_type"] == encounterType) &&
                    (status == null || admission["status"] == status)
            }
        }

        /** 按 set 子句逐字段回填（setNull 渲染为 field = null；cast($n as ...) 取绑定值）。 */
        private fun applyUpdate(target: MutableMap<String, Any?>, sql: String, values: List<Any?>) {
            val setPart = sql.substringAfter(" set ", "").substringBefore(" where")
            for (part in setPart.split(",")) {
                val eq = part.indexOf(" = ")
                if (eq <= 0) continue
                val field = part.substring(0, eq).trim()
                val rawValue = part.substring(eq + 3).trim()
                val value: Any? = when {
                    rawValue == "null" -> null
                    rawValue.startsWith("$") -> values.getOrNull(rawValue.removePrefix("$").toInt() - 1)
                    rawValue.startsWith("cast(") -> {
                        val bind = Regex("cast\\((\\$\\d+) as").find(rawValue)
                            ?.groupValues?.get(1)?.removePrefix("$")?.toInt()
                        bind?.let { values.getOrNull(it - 1) }
                    }
                    else -> null
                }
                target[field] = value
            }
        }
    }

    private fun bedRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "bed-1",
            "department" to "东区",
            "ward" to "1",
            "label" to "东区1床",
            "status" to BedService.STATUS_ENABLED,
            "remark" to null,
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun admissionRow(overrides: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "department" to "东区",
            "ward" to "1",
            "encounter_type" to "ELDERLY_CARE",
            "status" to "ACTIVE",
        )
        base.putAll(overrides)
        return base
    }

    private fun bedBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("department", "东区")
            .put("ward", "1")
            .put("label", "东区1床")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    // ——— 1. 创建 ———

    @Test
    fun `创建床位返回26位ULID且状态默认启用并trim业务键`() {
        val stub = BedStub()
        val created = BedService(stub.pool).createBed(
            JsonObject()
                .put("department", "  东区 ")
                .put("ward", " 1 ")
                .put("label", " 东区1床 ")
                .put("remark", " 靠窗 "),
        ).toCompletionStage().toCompletableFuture().get()

        assertEquals(26, created.getString("id").length, "必须生成 26 位 ULID")
        assertEquals("东区", created.getString("department"), "department 必须 trim")
        assertEquals("1", created.getString("ward"), "ward 必须 trim")
        assertEquals("东区1床", created.getString("label"), "label 必须 trim")
        assertEquals("靠窗", created.getString("remark"), "remark 必须 trim")
        assertEquals(BedService.STATUS_ENABLED, created.getString("status"), "新建床位状态必须默认 启用")
        assertNotNull(created.getString("created_at"))
        assertNotNull(created.getString("updated_at"))

        assertEquals(1, stub.beds.size)
        assertEquals("东区", stub.beds.single()["department"])
        assertEquals("启用", stub.beds.single()["status"])
        assertEquals(1, stub.transactionCalls, "创建必须在事务内做启用态唯一检查")
        assertTrue(stub.queries.any { it.contains("insert into healthcare.beds") })
    }

    @Test
    fun `创建省略label与remark时不写可选列`() {
        val stub = BedStub()
        val created = BedService(stub.pool)
            .createBed(JsonObject().put("department", "东区").put("ward", "2"))
            .toCompletionStage().toCompletableFuture().get()

        assertNull(created.getValue("label"), "未提交 label 必须为 null")
        assertNull(created.getValue("remark"), "未提交 remark 必须为 null")

        val insertSql = stub.queries.first { it.contains("insert into healthcare.beds") }
        assertTrue(!insertSql.contains("label"), "null 可选列必须 omit .set() 而不是绑定 null: $insertSql")
        assertTrue(!insertSql.contains("remark"), "null 可选列必须 omit .set() 而不是绑定 null: $insertSql")
    }

    // ——— 2. 参数校验（400 文案） ———

    @Test
    fun `创建与更新与状态流转参数校验全部返回400且不触发SQL`() {
        val stub = BedStub()
        val service = BedService(stub.pool)

        fun expectCreateInvalid(body: JsonObject, vararg fragments: String) {
            val cause = bedCauseOf(service.createBed(body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectCreateInvalid(JsonObject(), "department is required")
        expectCreateInvalid(JsonObject().put("department", 123), "department must be a string")
        expectCreateInvalid(JsonObject().put("department", "   "), "department must not be blank")
        expectCreateInvalid(
            JsonObject().put("department", "x".repeat(51)).put("ward", "1"),
            "department must not exceed 50 characters",
        )
        expectCreateInvalid(JsonObject().put("department", "东区"), "ward is required")
        expectCreateInvalid(JsonObject().put("department", "东区").put("ward", "  "), "ward must not be blank")
        expectCreateInvalid(
            JsonObject().put("department", "东区").put("ward", "1".repeat(51)),
            "ward must not exceed 50 characters",
        )
        expectCreateInvalid(JsonObject().put("department", "东区").put("ward", "1").put("label", 1), "label must be a string")
        expectCreateInvalid(
            JsonObject().put("department", "东区").put("ward", "1").put("remark", "x".repeat(501)),
            "remark must not exceed 500 characters",
        )
        expectCreateInvalid(bedBody(mapOf("status" to "停用")), "unsupported bed keys: status")
        expectCreateInvalid(bedBody(mapOf("id" to "hacked")), "unsupported bed keys: id")

        fun expectUpdateInvalid(body: JsonObject, vararg fragments: String) {
            val cause = bedCauseOf(service.updateBed("bed-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectUpdateInvalid(JsonObject(), "department is required")
        expectUpdateInvalid(bedBody(mapOf("ward" to " ")), "ward must not be blank")
        expectUpdateInvalid(bedBody(mapOf("remark" to "x".repeat(501))), "500")
        expectUpdateInvalid(bedBody(mapOf("status" to "停用")), "unsupported bed keys: status")

        fun expectStatusInvalid(body: JsonObject, vararg fragments: String) {
            val cause = bedCauseOf(service.updateBedStatus("bed-1", body))
            assertInstanceOf(IllegalArgumentException::class.java, cause)
            for (fragment in fragments) {
                assertTrue(cause.message?.contains(fragment) == true, "got: ${cause.message}")
            }
        }

        expectStatusInvalid(JsonObject(), "status is required")
        expectStatusInvalid(JsonObject().put("status", 1), "status must be a string")
        expectStatusInvalid(JsonObject().put("status", "下架"), "status must be one of: 启用, 停用")
        expectStatusInvalid(
            JsonObject().put("status", "停用").put("ward", "2"),
            "unsupported status keys: ward",
        )

        assertTrue(stub.queries.isEmpty(), "校验失败不得触发任何 SQL: ${stub.queries}")
        assertEquals(0, stub.transactionCalls)
    }

    // ——— 3. 启用态业务键唯一 ———

    @Test
    fun `重复启用department与ward返回409且不插入`() {
        val stub = BedStub(beds = mutableListOf(bedRow()))
        val cause = bedCauseOf(
            BedService(stub.pool).createBed(JsonObject().put("department", "东区").put("ward", "1")),
        )

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("bed already exists: 东区/1", cause.message)
        assertEquals(1, stub.beds.size, "冲突不得写入新床位")
        assertTrue(
            stub.queries.none { it.contains("insert into healthcare.beds") },
            "冲突必须在 INSERT 前拦截: ${stub.queries}",
        )
    }

    @Test
    fun `同键已有停用项时创建启用项成功`() {
        val stub = BedStub(beds = mutableListOf(bedRow(mapOf("status" to BedService.STATUS_DISABLED))))
        val created = BedService(stub.pool)
            .createBed(JsonObject().put("department", "东区").put("ward", "1"))
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(BedService.STATUS_ENABLED, created.getString("status"))
        assertEquals(2, stub.beds.size, "停用项不占用业务键")
    }

    @Test
    fun `更新改用已启用的department与ward返回409`() {
        val stub = BedStub(
            beds = mutableListOf(
                bedRow(mapOf("id" to "bed-1", "department" to "东区", "ward" to "1")),
                bedRow(mapOf("id" to "bed-2", "department" to "东区", "ward" to "2")),
            ),
        )
        val cause = bedCauseOf(
            BedService(stub.pool)
                .updateBed("bed-1", JsonObject().put("department", "东区").put("ward", "2")),
        )

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("bed already exists: 东区/2", cause.message)
        assertEquals("1", stub.beds.first { it["id"] == "bed-1" }["ward"], "冲突不得落库")
        assertTrue(stub.queries.none { it.contains("update healthcare.beds") }, "冲突必须在 UPDATE 前拦截")
    }

    @Test
    fun `更新自身键不触发自我冲突`() {
        val stub = BedStub(beds = mutableListOf(bedRow()))
        val updated = BedService(stub.pool)
            .updateBed("bed-1", JsonObject().put("department", "东区").put("ward", "1").put("label", "东区1床A"))
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("东区1床A", updated.getString("label"))
        assertEquals("1", updated.getString("ward"))
    }

    @Test
    fun `启用已停用床位撞其它启用项返回409`() {
        val stub = BedStub(
            beds = mutableListOf(
                bedRow(mapOf("id" to "bed-3", "department" to "西区", "ward" to "9", "status" to BedService.STATUS_DISABLED)),
                bedRow(mapOf("id" to "bed-4", "department" to "西区", "ward" to "9")),
            ),
        )
        val cause = bedCauseOf(
            BedService(stub.pool).updateBedStatus("bed-3", JsonObject().put("status", BedService.STATUS_ENABLED)),
        )

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("bed already exists: 西区/9", cause.message)
        assertEquals(
            BedService.STATUS_DISABLED,
            stub.beds.first { it["id"] == "bed-3" }["status"],
            "冲突不得落库",
        )
    }

    @Test
    fun `唯一索引23505兜底映射为409而非500`() {
        // 约束名路径：INSERT 撞 uq_beds_enabled_identity
        val insertStub = BedStub()
        insertStub.failBedInsert = PgException(
            "duplicate key value violates unique constraint \"uq_beds_enabled_identity\"",
            "ERROR",
            "23505",
            "Key (department, ward)=(东区, 1) already exists.",
        )
        val insertCause = bedCauseOf(
            BedService(insertStub.pool).createBed(JsonObject().put("department", "东区").put("ward", "1")),
        )
        assertInstanceOf(ConflictException::class.java, insertCause)
        assertEquals("bed already exists: 东区/1", insertCause.message)
        assertTrue(insertStub.beds.isEmpty(), "失败插入不得落库")

        // sqlState 路径：启用流转撞唯一索引
        val updateStub = BedStub(
            beds = mutableListOf(
                bedRow(mapOf("id" to "bed-5", "department" to "西区", "ward" to "9", "status" to BedService.STATUS_DISABLED)),
            ),
        )
        updateStub.failBedUpdate = PgException(
            "duplicate key value violates unique constraint \"beds_identity\"",
            "ERROR",
            "23505",
            "Key (department, ward)=(西区, 9) already exists.",
        )
        val updateCause = bedCauseOf(
            BedService(updateStub.pool).updateBedStatus("bed-5", JsonObject().put("status", BedService.STATUS_ENABLED)),
        )
        assertInstanceOf(ConflictException::class.java, updateCause)
        assertEquals("bed already exists: 西区/9", updateCause.message)
        assertEquals("停用", updateStub.beds.single()["status"], "失败流转不得落库")
    }

    // ——— 4. 删除与占用 ———

    @Test
    fun `删除被活动入住占用的床位返回409且保留记录`() {
        val stub = BedStub(beds = mutableListOf(bedRow()), admissions = mutableListOf(admissionRow()))
        val cause = bedCauseOf(BedService(stub.pool).deleteBed("bed-1"))

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("bed is occupied by an active admission", cause.message)
        assertEquals(1, stub.beds.size, "占用中不得删除")
        assertTrue(
            stub.queries.none { it.contains("delete from healthcare.beds") },
            "占用检查必须在 DELETE 前拦截: ${stub.queries}",
        )
    }

    @Test
    fun `停用床位若仍有活动入住同样不可删除`() {
        val stub = BedStub(
            beds = mutableListOf(bedRow(mapOf("status" to BedService.STATUS_DISABLED))),
            admissions = mutableListOf(admissionRow()),
        )
        val cause = bedCauseOf(BedService(stub.pool).deleteBed("bed-1"))

        assertInstanceOf(ConflictException::class.java, cause)
        assertEquals("bed is occupied by an active admission", cause.message)
    }

    @Test
    fun `非活动入住或非养老入住不阻断删除`() {
        val stub = BedStub(
            beds = mutableListOf(bedRow()),
            admissions = mutableListOf(
                admissionRow(mapOf("id" to "enc-1", "status" to "DISCHARGED")),
                admissionRow(mapOf("id" to "enc-2", "encounter_type" to "INPATIENT")),
            ),
        )
        val deleted = BedService(stub.pool).deleteBed("bed-1")
            .toCompletionStage().toCompletableFuture().get()

        assertEquals("bed-1", deleted.getString("id"))
        assertTrue(stub.beds.isEmpty(), "未被占用必须删除")
    }

    // ——— 5. 404 ———

    @Test
    fun `查询更新状态流转删除不存在床位返回404且不写入`() {
        val stub = BedStub()
        val service = BedService(stub.pool)

        for (invoker in listOf(
            { service.getBed("missing") },
            { service.updateBed("missing", JsonObject().put("department", "东区").put("ward", "1")) },
            { service.updateBedStatus("missing", JsonObject().put("status", BedService.STATUS_DISABLED)) },
            { service.deleteBed("missing") },
        )) {
            val cause = bedCauseOf(invoker())
            assertInstanceOf(HealthcareNotFoundException::class.java, cause)
            assertTrue(cause.message?.contains("bed not found: missing") == true, "got: ${cause.message}")
        }
        assertTrue(stub.queries.none { it.contains("insert into healthcare.beds") })
        assertTrue(stub.queries.none { it.contains("delete from healthcare.beds") })
    }

    // ——— 6. 列表 ———

    @Test
    fun `列表返回records与meta包含total且倒序分页`() {
        val stub = BedStub(
            beds = mutableListOf(
                bedRow(
                    mapOf(
                        "id" to "bed-1",
                        "department" to "东区",
                        "ward" to "1",
                        "label" to "东区1床",
                        "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                        "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
                    ),
                ),
                bedRow(
                    mapOf(
                        "id" to "bed-2",
                        "department" to "西区",
                        "ward" to "9",
                        "label" to "西区9床",
                        "status" to BedService.STATUS_DISABLED,
                        "remark" to "维修中",
                        "created_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                        "updated_at" to OffsetDateTime.parse("2026-08-02T09:00:00+08:00"),
                    ),
                ),
            ),
        )
        val service = BedService(stub.pool)

        val page = service.listBeds(null, null, null, 10, 0)
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(2, page.getJsonArray("records").size())
        assertEquals(2L, page.getJsonObject("meta").getLong("total"))

        val first = page.getJsonArray("records").getJsonObject(0)
        assertEquals("bed-2", first.getString("id"), "列表必须按 created_at 倒序")
        assertEquals("西区", first.getString("department"))
        assertEquals("9", first.getString("ward"))
        assertEquals("西区9床", first.getString("label"))
        assertEquals("停用", first.getString("status"))
        assertEquals("维修中", first.getString("remark"))
        assertNotNull(first.getString("created_at"))
        assertNotNull(first.getString("updated_at"))

        val countSql = stub.queries.first { it.contains("count(*)") && it.contains("from healthcare.beds") }
        val dataSql = stub.queries.first { it.contains("from healthcare.beds") && !it.contains("count(*)") }
        assertTrue(dataSql.contains("order by created_at desc, id desc"), "列表必须倒序: $dataSql")
        assertTrue(dataSql.contains("fetch next $"), "列表必须分页 limit: $dataSql")
        assertTrue(dataSql.contains("offset $"), "列表必须分页 offset: $dataSql")
        assertTrue(countSql.contains("from healthcare.beds"), "计数必须走同一张表: $countSql")

        // 过滤：department/ward/status 条件必须落到 SQL
        val filtered = service.listBeds(" 东区 ", "1", "启用", 5, 10)
            .toCompletionStage().toCompletableFuture().get()
        val filteredSql = stub.queries.last { it.contains("count(*)") && it.contains("from healthcare.beds") }
        assertTrue(filteredSql.contains("department = $"), "计数必须按 department 过滤: $filteredSql")
        assertTrue(filteredSql.contains("ward = $"), "计数必须按 ward 过滤: $filteredSql")
        assertTrue(filteredSql.contains("status = $"), "计数必须按 status 过滤: $filteredSql")
        assertEquals(1L, filtered.getJsonObject("meta").getLong("total"), "过滤必须生效（trim 后精确匹配）")
        assertEquals("bed-1", filtered.getJsonArray("records").getJsonObject(0).getString("id"))
    }

    @Test
    fun `空列表返回空records与total0`() {
        val stub = BedStub()
        val page = BedService(stub.pool).listBeds(null, null, null, 50, 0)
            .toCompletionStage().toCompletableFuture().get()

        assertEquals(0, page.getJsonArray("records").size(), "空列表 records 必须为 []")
        assertEquals(0L, page.getJsonObject("meta").getLong("total"), "空列表 total 必须为 0")
    }

    // ——— 7. 更新与状态流转闭环 ———

    @Test
    fun `更新可清空label与remark且不改状态`() {
        val stub = BedStub(beds = mutableListOf(bedRow(mapOf("remark" to "靠窗"))))
        val service = BedService(stub.pool)

        val cleared = service.updateBed(
            "bed-1",
            JsonObject().put("department", "东区").put("ward", "1").put("label", null as String?)
                .put("remark", "   "),
        ).toCompletionStage().toCompletableFuture().get()

        assertNull(cleared.getValue("label"), "提交 null 必须清空 label")
        assertNull(cleared.getValue("remark"), "提交空白 remark 必须清空")
        assertEquals(BedService.STATUS_ENABLED, cleared.getString("status"), "PUT 更新不得改动状态")

        val updateSql = stub.queries.first { it.contains("update healthcare.beds") }
        val updateTuple = stub.tuples.first { it.first.contains("update healthcare.beds") }
        assertTrue(updateSql.contains("label = "), "清空必须写入 label 列: $updateSql")
        assertTrue(updateSql.contains("remark = "), "清空必须写入 remark 列: $updateSql")
        assertTrue(updateSql.contains("id = $"), "更新必须按 id 定位: $updateSql")
        // jOOQ 的 setNull 可能内联为 `= null`，也可能渲染成 `cast($n as varchar)` 并绑定 null；两者等价
        assertTrue(
            updateSql.contains("label = null") || updateTuple.second.count { it == null } == 2,
            "清空必须以 null 落库: $updateSql / ${updateTuple.second}",
        )
    }

    @Test
    fun `状态流转停用与启用成功`() {
        val stub = BedStub(beds = mutableListOf(bedRow()))
        val service = BedService(stub.pool)

        val disabled = service.updateBedStatus("bed-1", JsonObject().put("status", "停用"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(BedService.STATUS_DISABLED, disabled.getString("status"))
        assertEquals("停用", stub.beds.single()["status"])

        val enabled = service.updateBedStatus("bed-1", JsonObject().put("status", "启用"))
            .toCompletionStage().toCompletableFuture().get()
        assertEquals(BedService.STATUS_ENABLED, enabled.getString("status"), "停用后可再次启用")
        assertEquals("东区", enabled.getString("department"), "状态流转不得改动业务键")

        val statusSql = stub.tuples.filter { it.first.contains("update healthcare.beds") }
        assertTrue(statusSql.all { it.first.contains("id = $") }, "状态流转必须按 id 定位")
    }
}

// ——— mock 基础设施（文件级私有，避免与其他测试文件的顶层私有声明重名冲突） ———

private fun bedMockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun bedRowSet(vararg rows: Row, rowCount: Int = 0): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    every { rs.rowCount() } returns rowCount
    return rs
}

private fun bedRows(vararg values: Map<String, Any?>): RowSet<Row> =
    bedRowSet(*values.map { bedMockRow(it) }.toTypedArray())

private fun bedNormalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun bedTupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}

private fun bedCauseOf(future: Future<*>): Throwable {
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
