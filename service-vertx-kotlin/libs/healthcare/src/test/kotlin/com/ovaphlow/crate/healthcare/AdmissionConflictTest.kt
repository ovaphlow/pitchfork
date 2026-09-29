package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.function.Function as JavaFunction

/**
 * 028 W1 入住区间/床位占用冲突校验的非数据库测试（mockk + 嵌入式 HTTP）。
 *
 * 判定口径（与 `HealthcareService.ensureAdmissionSlotAvailable` 同源）：
 *   - 半开区间 `[admit_date, discharge_date)`：`O.admit_date < X` 且 `coalesce(O.discharge_date, ∞) > S`；
 *     新建入住 `X = discharge_date = null` → 第一条条件恒真（SQL 中不出现 `admit_date` 过滤）。
 *   - 床位命中优先于同长者区间重叠；`department`/`ward` 都非空白时按 `trim` 后精确比较。
 *   - `until` 取冲突记录的 `discharge_date`，为 null 时字面量 `ongoing`。
 *
 * mock 返回的行只包含真正参与判定的最小列集合（`encounter_no`、`discharge_date`），
 * 其余列由 null 兜底；判定本身发生在生产代码的 jOOQ 表达式中，mock 只负责提供命中/未命中。
 *
 * 覆盖验收口径 §7-1/2/3：床位被占 409、同长者区间重叠 409、无冲突 201、
 * `PUT /encounters/:id` 改 ward 409、已离院改回 ACTIVE 不再绕过门禁、V501 唯一索引冲突 409 而非 500。
 */
@ExtendWith(VertxExtension::class)
class AdmissionConflictTest {

    private val bedOccupiedMessage = bedMessage("301-1", "A20260801001", "2026-09-30T00:00+08:00")

    /** 028 §4.2 床位冲突文案；`until` 为冲突记录的 `discharge_date`（null → `ongoing`）。 */
    private fun bedMessage(ward: String, encounterNo: String, until: String): String =
        "bed already occupied: department=三楼 ward=$ward encounter_no=$encounterNo until=$until"

    /**
     * 按 normalized SQL 特征分发的 mock 桩。分派顺序即 SQL 形状的区分顺序：
     * `insert/update` → 咨询锁 → `for update`（lockEncounter）→ 患者 → 床位 → 活动入住 →
     * 住院号 → 按 id 读回 → 其余 `from healthcare.encounters`（同长者区间重叠）。
     */
    private class DatabaseStub(
        var patients: RowSet<Row> = rowSet(),
        var activeAdmission: RowSet<Row> = rowSet(),
        var encounterNoLookup: RowSet<Row> = rowSet(),
        var bedOccupancy: RowSet<Row> = rowSet(),
        var patientOverlap: RowSet<Row> = rowSet(),
        var lockedEncounter: RowSet<Row> = rowSet(),
        var readBackEncounter: RowSet<Row> = rowSet(),
        var failEncounterInsert: Throwable? = null,
        var failEncounterUpdate: Throwable? = null,
    ) {
        val queries = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()
        var transactionCalls = 0
            private set

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
                tuples.add(sql to tupleValues(firstArg()))
                val failure = failEncounterInsert
                when {
                    sql.contains("insert into healthcare.encounters") ->
                        if (failure != null) Future.failedFuture(failure) else Future.succeededFuture(rowSet())
                    sql.contains("update healthcare.encounters") ->
                        failEncounterUpdate?.let { Future.failedFuture(it) } ?: Future.succeededFuture(updated(1))
                    sql.contains("insert into nursing.nursing_service_periods") -> Future.succeededFuture(updated(1))
                    sql.contains("pg_advisory_xact_lock") -> Future.succeededFuture(rowSet())
                    sql.contains("for update") -> Future.succeededFuture(lockedEncounter)
                    sql.contains("from healthcare.patients") -> Future.succeededFuture(patients)
                    // 读回/锁定按 id 定位必须先于列清单判据（`selectFrom(ENCOUNTERS)` 的列清单也含 department/ward）
                    sql.contains("encounters.id = ") -> Future.succeededFuture(readBackEncounter)
                    sql.contains("encounters.ward") || sql.contains("encounters.department") ->
                        Future.succeededFuture(bedOccupancy)
                    sql.contains("encounters.status") -> Future.succeededFuture(activeAdmission)
                    sql.contains("encounters.encounter_no = ") -> Future.succeededFuture(encounterNoLookup)
                    sql.contains("from healthcare.encounters") -> Future.succeededFuture(patientOverlap)
                    else -> Future.succeededFuture(rowSet())
                }
            }
            every { pool.withTransaction<Any>(any()) } answers {
                transactionCalls++
                val handler = firstArg<JavaFunction<SqlConnection, Future<Any>>>()
                handler.apply(conn)
            }
        }

        private fun record(sql: String) {
            lastSql = normalized(sql)
            queries.add(lastSql)
        }
    }

    // ——— fixture 行 ———

    private fun patientRow(): Map<String, Any?> = mapOf(
        "id" to "pat-1",
        "name" to "张奶奶",
        "status" to "ACTIVE",
    )

    /** 冲突命中行：判定只需 `encounter_no` 与 `discharge_date`（后者为 null → `until=ongoing`）。 */
    private fun conflictingRow(encounterNo: String, dischargeDate: OffsetDateTime?): Map<String, Any?> =
        mapOf("encounter_no" to encounterNo, "discharge_date" to dischargeDate)

    private fun activeElderlyEncounterRow(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "id" to "enc-1",
            "patient_id" to "pat-1",
            "encounter_type" to "ELDERLY_CARE",
            "encounter_no" to "A20260801001",
            "department" to "三楼",
            "ward" to "301-1",
            "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
            "discharge_date" to null,
            "status" to "ACTIVE",
            "created_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
            "updated_at" to OffsetDateTime.parse("2026-08-01T09:00:00+08:00"),
        )
        base.putAll(overrides)
        return base
    }

    private fun admissionBody(overrides: Map<String, Any?> = emptyMap()): JsonObject {
        val body = JsonObject()
            .put("patient_id", "pat-1")
            .put("encounter_no", "A20260901001")
            .put("admit_date", "2026-09-01T00:00:00+08:00")
            .put("department", "三楼")
            .put("ward", "301-1")
        overrides.forEach { (key, value) -> body.put(key, value) }
        return body
    }

    // ——— 嵌入式 HTTP ———

    private fun httpRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (body != null) req.putHeader("Content-Type", "application/json").send(body.encode())
                else req.send()
            }
            .compose { resp ->
                resp.body().map { b ->
                    val json = try { JsonObject(b) } catch (_: Exception) { JsonObject() }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun withServer(
        vertx: Vertx,
        stub: DatabaseStub,
        block: (Int) -> Future<Unit>,
    ): Future<Unit> {
        val router = Router.router(vertx)
        router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", "user-1"); ctx.next() }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { Unit } }
        }
    }

    private val ulidPattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    // ========================================================================
    //  1. 床位被占 → 409
    // ========================================================================

    @Test
    fun `养老入住床位被占返回409且消息带住院号与until`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            bedOccupancy = rows(conflictingRow("A20260801001", OffsetDateTime.parse("2026-09-30T00:00:00+08:00"))),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "床位被占必须 409，实际: ${body.encode()}")
                        assertEquals(bedOccupiedMessage, body.getString("error"))
                        assertTrue(
                            stub.queries.none { it.startsWith("insert into healthcare.encounters") },
                            "冲突时不得插入 encounter: ${stub.queries}",
                        )
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }

    /**
     * 028 补充（评审 D1）：冲突 SELECT 必须在取得咨询锁**之后**才发起。
     *
     * Vert.x 的 `execute()` 立即发出查询、Future 不是惰性的，若先构建 Future 再取锁，
     * SELECT 会跑在锁外面，`pg_advisory_xact_lock` 形同虚设（READ COMMITTED 下两个并发
     * 事务仍可各自通过检查并写入重叠记录）。本用例按 mock 记录的 SQL 顺序做可判定断言。
     */
    @Test
    fun `床位冲突检查在取得咨询锁之后才发起`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            bedOccupancy = rows(conflictingRow("A20260801001", null)),
        )
        val cause = causeOf(HealthcareService(stub.pool).admitElderly(admissionBody(), "user-1"))
        assertTrue(
            cause.message?.startsWith("bed already occupied") == true,
            "床位被占必须失败: ${cause.message}",
        )
        val lockIndexes = stub.queries.withIndex()
            .filter { it.value.contains("pg_advisory_xact_lock") }
            .map { it.index }
        assertEquals(2, lockIndexes.size, "床位键与患者键两把锁都要取: ${stub.queries}")
        val bedIndex = stub.queries.indexOfFirst { it.contains("encounters.ward") }
        assertTrue(bedIndex >= 0, "必须做床位占用检查: ${stub.queries}")
        assertTrue(
            lockIndexes.all { it < bedIndex },
            "咨询锁必须先于床位占用 SELECT 发起（否则并发窗口未关闭）: ${stub.queries}",
        )
    }

    /** 同上：同长者区间重叠 SELECT 也必须在取锁之后发起。 */
    @Test
    fun `同长者区间重叠检查在取得咨询锁之后才发起`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            patientOverlap = rows(conflictingRow("A20260701001", null)),
        )
        val cause = causeOf(HealthcareService(stub.pool).admitElderly(admissionBody(), "user-1"))
        assertTrue(
            cause.message?.startsWith("admission interval overlaps") == true,
            "同长者区间重叠必须失败: ${cause.message}",
        )
        val lockIndexes = stub.queries.withIndex()
            .filter { it.value.contains("pg_advisory_xact_lock") }
            .map { it.index }
        assertTrue(lockIndexes.size == 2, "两把锁都要取: ${stub.queries}")
        val overlapIndex = stub.queries.indexOfLast { it.contains("from healthcare.encounters") }
        assertTrue(
            lockIndexes.all { it < overlapIndex },
            "咨询锁必须先于同长者区间 SELECT 发起: ${stub.queries}",
        )
    }

    @Test
    fun `床位占用判定为半开区间且department与ward做trim后精确比较`() {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            bedOccupancy = rows(conflictingRow("A20260801001", null)),
        )
        val cause = causeOf(
            HealthcareService(stub.pool).admitElderly(
                admissionBody(mapOf("department" to " 三楼 ", "ward" to " 301-1 ")),
                "user-1",
            ),
        )
        assertEquals(
            "bed already occupied: department=三楼 ward=301-1 encounter_no=A20260801001 until=ongoing",
            cause.message,
            "discharge_date 为 null 时 until 必须是字面量 ongoing",
        )
        val bedSql = stub.queries.single { it.contains("encounters.ward") }
        assertTrue(bedSql.contains("encounters.department = \$"), "床位过滤必须参数化: $bedSql")
        assertTrue(bedSql.contains("encounters.ward = \$"), "床位过滤必须参数化: $bedSql")
        assertTrue(
            stub.tuples.any { it.second.contains("三楼") && it.second.contains("301-1") },
            "比较值必须是 trim 后的精确文本: ${stub.tuples}",
        )
        assertTrue(
            bedSql.contains("encounters.encounter_type = \$") ||
                stub.tuples.any { it.second.contains("ELDERLY_CARE") },
            "只校验 ELDERLY_CARE: $bedSql",
        )
    }

    // ========================================================================
    //  2. 同长者区间重叠 → 409
    // ========================================================================

    @Test
    fun `同长者区间重叠即便对方已离院也返回409`(vertx: Vertx, ctx: VertxTestContext) {
        // 对方记录已 DISCHARGED（discharge_date 非空）但区间与本次入住重叠：
        // 本次 [2026-09-01, ∞) 与对方 [2026-06-01, 2026-09-20) 相交 → 仍须 409（D3）。
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            patientOverlap = rows(conflictingRow("A20260601001", OffsetDateTime.parse("2026-09-20T10:00:00+08:00"))),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/healthcare/v1/elderly-admissions",
                admissionBody(mapOf("department" to null, "ward" to null)),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "区间重叠必须 409，实际: ${body.encode()}")
                    assertEquals(
                        "admission interval overlaps existing encounter:" +
                            " encounter_no=A20260601001 until=2026-09-20T10:00+08:00",
                        body.getString("error"),
                    )
                    assertTrue(
                        stub.queries.none { it.contains("encounters.ward") },
                        "无 department/ward 时不得做床位检查: ${stub.queries}",
                    )
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `新建入住X为null时不加admit_date过滤且未定离院视为无穷`() {
        // 本次入住 X = discharge_date = null（未定离院）→ 条件 A（admit_date < X）恒真，不加过滤；
        // 条件 B（discharge_date IS NULL OR discharge_date > S）仍必须按 S = 2026-09-01 判定。
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            patientOverlap = rows(conflictingRow("A20260601001", OffsetDateTime.parse("2026-09-20T10:00:00+08:00"))),
        )
        causeOf(
            HealthcareService(stub.pool).admitElderly(
                admissionBody(mapOf("department" to null, "ward" to null)),
                "user-1",
            ),
        )
        val overlapSql = stub.queries.single { it.contains("from healthcare.encounters") && it.contains("discharge_date") }
        assertTrue(overlapSql.contains("encounters.discharge_date is null"), "null 离院日视为 ∞: $overlapSql")
        assertTrue(overlapSql.contains("encounters.discharge_date > "), "必须严格大于 S（占位符前的 `> ` 可区分 `>=`）: $overlapSql")
        // 评审 P2-10：`>=` 字符串包含 `>` 前缀，故必须显式排除非严格比较，断言才有防退步能力
        assertFalse(overlapSql.contains("encounters.discharge_date >="), "条件 B 必须严格（不得为 >=）: $overlapSql")
        assertFalse(overlapSql.contains("encounters.admit_date"), "X 为 null 时不得加 admit_date 过滤: $overlapSql")
    }

    @Test
    fun `改ward时按既有admit_date与discharge_date做严格小于大于比较`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            lockedEncounter = rows(activeElderlyEncounterRow()),
            bedOccupancy = rows(conflictingRow("A20260801001", OffsetDateTime.parse("2026-09-30T00:00:00+08:00"))),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "/healthcare/v1/encounters/enc-1",
                JsonObject().put("ward", "302-1"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "改 ward 撞车必须 409，实际: ${body.encode()}")
                    assertEquals(
                        bedMessage("302-1", "A20260801001", "2026-09-30T00:00+08:00"),
                        body.getString("error"),
                        "冲突文案必须用生效后的 ward",
                    )
                    assertTrue(
                        stub.queries.none { it.startsWith("update healthcare.encounters") },
                        "冲突时不得更新 encounter: ${stub.queries}",
                    )
                    assertTrue(stub.queries.any { it.contains("for update") }, "必须先 FOR UPDATE 锁定")
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  3. 无冲突 → 201
    // ========================================================================

    @Test
    fun `无冲突时养老入住返回201并写入责任医生`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(patients = rows(patientRow()))
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(201, status, "无冲突必须 201，实际: ${body.encode()}")
                        val encounter = body.getJsonObject("encounter")
                        assertNotNull(encounter)
                        assertTrue(ulidPattern.matches(encounter.getString("id")), "encounter id 必须是 ULID")
                        assertEquals("ACTIVE", encounter.getString("status"))
                        assertEquals("ELDERLY_CARE", encounter.getString("encounter_type"))
                        assertEquals("pat-1", body.getJsonObject("patient").getString("id"))
                        assertNotNull(body.getJsonObject("nursing_period"), "养老入住必须同事务建照护周期")
                        assertEquals(1, stub.transactionCalls, "校验与插入必须同一事务")
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `非养老入住不触发床位与区间校验`() {
        val stub = DatabaseStub(patients = rows(patientRow()))
        val result = successOf(
            HealthcareService(stub.pool).createEncounter(
                JsonObject()
                    .put("patient_id", "pat-1")
                    .put("encounter_type", "OUTPATIENT")
                    .put("encounter_no", "OP-1")
                    .put("admit_date", "2026-09-01T00:00:00+08:00")
                    .put("department", "三楼")
                    .put("ward", "301-1"),
                "user-1",
            ),
        ) as JsonObject
        assertEquals("OUTPATIENT", result.getString("encounter_type"))
        assertTrue(stub.queries.none { it.contains("pg_advisory_xact_lock") }, "非养老不做入住校验")
        assertTrue(stub.queries.none { it.contains("discharge_date is null") }, "非养老不做区间校验")
    }

    @Test
    fun `POST encounters在养老类型时同样受床位门禁保护`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            bedOccupancy = rows(conflictingRow("A20260801001", OffsetDateTime.parse("2026-09-30T00:00:00+08:00"))),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/healthcare/v1/encounters",
                admissionBody(mapOf("encounter_type" to "ELDERLY_CARE")),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "POST /encounters 养老类型必须同受保护: ${body.encode()}")
                    assertEquals(bedOccupiedMessage, body.getString("error"))
                    assertTrue(
                        stub.queries.none { it.startsWith("insert into healthcare.encounters") },
                        "冲突时不得插入 encounter",
                    )
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  4. 已离院改回 ACTIVE 不再绕过门禁
    // ========================================================================

    @Test
    fun `已离院改回ACTIVE时区间重叠仍409`(vertx: Vertx, ctx: VertxTestContext) {
        // 目标记录已离院（discharge_date 非空）；本次请求只提交 status=ACTIVE。
        // 校验以「既有 admit_date / discharge_date」为 S/X，重叠即拒绝 → 关闭绕过门禁的漏洞。
        val stub = DatabaseStub(
            lockedEncounter = rows(
                activeElderlyEncounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "discharge_date" to OffsetDateTime.parse("2026-08-31T00:00:00+08:00"),
                    ),
                ),
            ),
            patientOverlap = rows(conflictingRow("A20260601001", null)),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "/healthcare/v1/encounters/enc-1",
                JsonObject().put("status", "ACTIVE"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "改回 ACTIVE 且区间重叠必须 409，实际: ${body.encode()}")
                    assertEquals(
                        "admission interval overlaps existing encounter: encounter_no=A20260601001 until=ongoing",
                        body.getString("error"),
                    )
                    val overlapSql = stub.queries.single {
                        it.contains("encounters.patient_id = \$") && it.contains("encounters.admit_date <")
                    }
                    assertTrue(overlapSql.contains("encounters.discharge_date > "), "条件 B 必须严格大于 S（`> ` 可区分 `>=`）: $overlapSql")
                    assertFalse(overlapSql.contains("encounters.discharge_date >="), "条件 B 不得为 >=: $overlapSql")
                    assertFalse(overlapSql.contains("encounters.admit_date <="), "条件 A 不得为 <=: $overlapSql")
                    assertTrue(overlapSql.contains("encounters.id <>"), "必须排除自身: $overlapSql")
                    assertTrue(
                        stub.queries.none { it.startsWith("update healthcare.encounters") },
                        "冲突时不得更新 encounter",
                    )
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `改回ACTIVE时同长者已有活动入住返回409并带住院号`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            lockedEncounter = rows(
                activeElderlyEncounterRow(
                    mapOf(
                        "status" to "DISCHARGED",
                        "department" to null,
                        "ward" to null,
                        "discharge_date" to OffsetDateTime.parse("2026-08-31T00:00:00+08:00"),
                    ),
                ),
            ),
            activeAdmission = rows(conflictingRow("A20260701001", null)),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "/healthcare/v1/encounters/enc-1",
                JsonObject().put("status", "ACTIVE"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "已有活动入住必须 409（原实现为 400），实际: ${body.encode()}")
                    assertEquals(
                        "patient already has an active elderly admission: encounter_no=A20260701001",
                        body.getString("error"),
                        "028 §4.2 冻结文案，前端 admissionMessages 按此映射",
                    )
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `改ward无冲突时200并落库`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            lockedEncounter = rows(activeElderlyEncounterRow()),
            readBackEncounter = rows(activeElderlyEncounterRow(mapOf("ward" to "302-1"))),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "/healthcare/v1/encounters/enc-1",
                JsonObject().put("ward", "302-1"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "无冲突改 ward 必须 200，实际: ${body.encode()}")
                    assertEquals("302-1", body.getString("ward"))
                    assertTrue(
                        stub.queries.any { it.startsWith("update healthcare.encounters") },
                        "无冲突必须执行更新: ${stub.queries}",
                    )
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  5. 唯一索引冲突映射 409（不退化 500）
    // ========================================================================

    @Test
    fun `V501唯一索引冲突映射为409而非500`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            failEncounterInsert = PgException(
                "duplicate key value violates unique constraint \"uq_encounters_active_elderly_care\"",
                "ERROR",
                "23505",
                "Key (patient_id)=(pat-1) already exists.",
            ),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "并发唯一索引冲突必须 409，实际: ${body.encode()}")
                        assertEquals("patient already has an active elderly admission", body.getString("error"))
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }

    /**
     * 评审 P2-1：`PUT /encounters/:id` 走 `respondFailure` 而不是 `respondCreateFailure`，
     * 并发撞 V501（唯一索引）时必须同样给 409，而不是 500 "internal error"。
     */
    @Test
    fun `PUT路径撞V501唯一索引同样映射为409而非500`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            lockedEncounter = rows(
                activeElderlyEncounterRow(
                    mapOf(
                        "admit_date" to OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
                        "discharge_date" to OffsetDateTime.parse("2026-08-05T00:00:00+08:00"),
                        "status" to "DISCHARGED",
                    ),
                ),
            ),
            failEncounterUpdate = PgException(
                "duplicate key value violates unique constraint \"uq_encounters_active_elderly_care\"",
                "ERROR",
                "23505",
                "Key (patient_id)=(pat-1) already exists.",
            ),
        )
        withServer(vertx, stub) { port ->
            httpRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "/healthcare/v1/encounters/enc-1",
                JsonObject().put("status", "ACTIVE"),
            ).map { (status, body) ->
                ctx.verify {
                    assertEquals(409, status, "并发唯一索引冲突必须 409，实际: ${body.encode()}")
                    assertEquals("patient already has an active elderly admission", body.getString("error"))
                    ctx.completeNow()
                }
            }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `已有活动入住返回409且消息带既有住院号`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            activeAdmission = rows(mapOf("encounter_no" to "A20260701001")),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "已有活动入住必须 409（原实现为 400），实际: ${body.encode()}")
                        assertEquals(
                            "patient already has an active elderly admission: encounter_no=A20260701001",
                            body.getString("error"),
                        )
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `已有活动入住但无住院号时消息省略该段`(vertx: Vertx, ctx: VertxTestContext) {
        // 存在性判据必须是「是否命中行」，而不是住院号是否非空：命中行但 encounter_no 为空仍须 409。
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            activeAdmission = rows(mapOf("encounter_no" to null)),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status, "命中行即冲突，实际: ${body.encode()}")
                        assertEquals("patient already has an active elderly admission", body.getString("error"))
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `住院号重复仍映射为409encounter_no已存在`(vertx: Vertx, ctx: VertxTestContext) {
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            encounterNoLookup = rows(mapOf("one" to 1)),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status)
                        assertEquals("encounter_no already exists", body.getString("error"))
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }

    @Test
    fun `CONFLICT异常消息不会被住院号子串判据改写`(vertx: Vertx, ctx: VertxTestContext) {
        // 回归：床位/区间冲突消息里含 `encounter_no=`，respondCreateFailure 必须先按 ConflictException
        // 原样 409 返回，否则会被改写成「encounter_no already exists」。
        val stub = DatabaseStub(
            patients = rows(patientRow()),
            patientOverlap = rows(conflictingRow("A20260601001", null)),
        )
        withServer(vertx, stub) { port ->
            httpRequest(vertx, port, HttpMethod.POST, "/healthcare/v1/elderly-admissions", admissionBody())
                .map { (status, body) ->
                    ctx.verify {
                        assertEquals(409, status)
                        val message = body.getString("error")
                        assertTrue(message.contains("encounter_no=A20260601001"), "实际: $message")
                        assertFalse(
                            message.contains("already exists"),
                            "ConflictException 消息不得被住院号子串判据改写: $message",
                        )
                        ctx.completeNow()
                    }
                }
        }.onFailure { ctx.failNow(it) }
    }
}

// ——— mock 基础设施（文件级 private：与其它测试文件的同名工具互不可见） ———

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    every { row.getLocalDate(any<String>()) } answers { values[firstArg<String>()] as? LocalDate }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns rows.size
    every { rs.rowCount() } returns rows.size
    return rs
}

private fun updated(affected: Int): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = emptyList<Row>().iterator()
        val rowIterator = mockk<RowIterator<Row>>()
        every { rowIterator.hasNext() } answers { delegate.hasNext() }
        every { rowIterator.next() } answers { delegate.next() }
        rowIterator
    }
    every { rs.size() } returns 0
    every { rs.rowCount() } returns affected
    return rs
}

private fun rows(vararg values: Map<String, Any?>): RowSet<Row> =
    rowSet(*values.map { mockRow(it) }.toTypedArray())

private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

private fun tupleValues(tuple: Tuple): List<Any?> {
    val values = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) values.add(tuple.getValue(i))
    return values
}

private fun causeOf(future: Future<*>): Throwable {
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

private fun successOf(future: Future<*>): Any? =
    try {
        future.toCompletionStage().toCompletableFuture().get()
    } catch (error: Throwable) {
        throw AssertionError("expected future to succeed, got: $error")
    }
