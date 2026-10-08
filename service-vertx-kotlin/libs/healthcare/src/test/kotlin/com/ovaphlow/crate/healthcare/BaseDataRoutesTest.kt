package com.ovaphlow.crate.healthcare

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime

/**
 * 054 F2 基础数据路由测试（**不访问数据库**）：疫苗类型 / 发育量表的建查改。
 *
 * 锁四件事：
 *  1. 未认证写路由 401；
 *  2. 内容建/查/改契约：201 / 列表 `{records,meta:{total}}` / 200；空列表 `records:[]` + `total:0`；
 *  3. 校验：`recommended_months` 长度必须等于 `dose_count`、`definition` 形状（domains/items/domain/options）、
 *     年龄区间、状态白名单、客户端提交服务端字段（`id/created_by/updated_by`）一律 400 且零触库；
 *  4. 唯一键冲突 → 409（疫苗 `code`、量表 `(scale_code,scale_version)`）且不写库。
 *
 * 用 mockk 桩驱动 `Pool.preparedQuery`；**不引用 jOOQ**。
 * 读库优先/常量回落的等价性由 [BaseDataFallbackTest] 覆盖；集中式权限声明由 `apps/aceso` 的门禁测试覆盖。
 */
@ExtendWith(VertxExtension::class)
class BaseDataRoutesTest {

    private val stub = BaseDataDatabaseStub()

    @BeforeEach
    fun setUp() {
        stub.reset()
        ChildVaccinationService.applyContentSchedule(emptyList())
        DevelopmentalScaleCatalog.applyContent(emptyList())
    }

    @AfterEach
    fun tearDown() {
        ChildVaccinationService.applyContentSchedule(emptyList())
        DevelopmentalScaleCatalog.applyContent(emptyList())
    }

    // ========================================================================
    //  认证兜底
    // ========================================================================

    @Test
    fun `基础数据写路由未认证一律 401`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = null) { port ->
            rawRequest(vertx, port, HttpMethod.POST, VACCINATION_PATH, VACCINATION_BODY)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "POST 疫苗类型未认证必须 401: $payload")
                        assertEquals("authentication required", payload.getString("error"))
                        assertTrue(stub.executedSql.none { it.startsWith("insert") }, "未认证不得写库")
                    }
                    rawRequest(vertx, port, HttpMethod.PUT, "$SCALE_PATH/ds-1", SCALE_BODY)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(401, status, "PUT 发育量表未认证必须 401: $payload")
                        assertTrue(stub.executedSql.none { it.startsWith("update") }, "未认证不得写库")
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  疫苗类型
    // ========================================================================

    @Test
    fun `创建疫苗类型 201 且字段落库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.vaccinationRow = vaccinationRow()
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, VACCINATION_PATH, VACCINATION_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("HEPB", payload.getString("code"))
                assertEquals("启用", payload.getString("status"), "status 缺省必须启用")

                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.base_vaccination_types") }
                val values = insert.second
                assertEquals(26, (values[0] as String).length, "服务端生成 26 位 ULID")
                assertEquals("HEPB", values[1])
                assertEquals("乙肝疫苗", values[2])
                assertEquals(3, values[3])
                assertEquals(3, (values[4] as JsonArray).size(), "recommended_months 原样落库")
                assertEquals("启用", values[5])
                assertEquals("admin-1", values[8], "created_by 取认证主体")
                assertEquals("admin-1", values[9], "updated_by 取认证主体")
                assertTrue(insert.first.contains("\$12"), "必须使用 \$1..\$n 参数化占位：${insert.first}")
                assertFalse(insert.first.contains("HEPB"), "SQL 文本不得拼接用户输入")
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `创建疫苗类型非法入参 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"name":"乙肝疫苗","dose_count":3,"recommended_months":[0,1,6]}""" to "code is required",
            """{"code":"HEPB","dose_count":3,"recommended_months":[0,1,6]}""" to "name is required",
            """{"code":"HEPB","name":"乙肝疫苗","recommended_months":[0,1,6]}""" to "dose_count is required",
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":3}""" to "recommended_months is required",
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":3,"recommended_months":[0,1]}"""
                to "recommended_months length must equal dose_count",
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":2,"recommended_months":[0,-1]}"""
                to "must not be negative",
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":3,"recommended_months":[0,1,6],"status":"ACTIVE"}"""
                to "invalid status",
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":3,"recommended_months":[0,1,6],"created_by":"hacker"}"""
                to "unsupported base vaccination type keys",
        )
        withServer(vertx, userId = "admin-1") { port ->
            // 路由装配会 fire-and-forget 加载内容（读库优先）；清掉装配期查询，只断言请求期零触库
            stub.executedSql.clear()
            cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
                chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, VACCINATION_PATH, body).map { (status, payload) ->
                        assertEquals(400, status, "body=$body response=$payload")
                        assertTrue(
                            payload.getString("error")!!.contains(expected),
                            "期望错误含 '$expected'，实际: $payload",
                        )
                    }
                }
            }
        }.onComplete { ar ->
            ctx.verify {
                ar.result()
                assertTrue(stub.executedSql.isEmpty(), "非法入参必须在进库前被拒绝：${stub.executedSql}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `疫苗编码重复 409 且不写库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.vaccinationCodeTaken = true
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, VACCINATION_PATH, VACCINATION_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(409, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("already exists"), "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `疫苗类型列表空结果与非法状态`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, VACCINATION_PATH, null)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(200, status, "body=$payload")
                        assertEquals(0, payload.getJsonArray("records").size())
                        assertEquals(0L, payload.getJsonObject("meta").getLong("total"))
                    }
                    rawRequest(vertx, port, HttpMethod.GET, "$VACCINATION_PATH?status=ACTIVE", null)
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(400, status, "非法 status 必须 400: $payload")
                        assertTrue(payload.getString("error")!!.contains("invalid status"))
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `修改疫苗类型 200 与不存在 404`(vertx: Vertx, ctx: VertxTestContext) {
        stub.vaccinationRow = vaccinationRow()
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.PUT,
                "$VACCINATION_PATH/vt-1",
                """{"name":"乙肝疫苗(改名)","status":"停用"}""",
            ).compose { (status, payload) ->
                ctx.verify {
                    assertEquals(200, status, "body=$payload")
                    val update = stub.tuples.first { it.first.startsWith("update healthcare.base_vaccination_types") }
                    assertEquals("vt-1", update.second[0])
                    assertEquals("HEPB", update.second[1], "未提交 code 时保持原值")
                    assertEquals("乙肝疫苗(改名)", update.second[2])
                    assertEquals(3, update.second[3], "未提交 dose_count 时保持原值")
                    assertEquals("停用", update.second[5])
                    assertEquals("admin-1", update.second[8], "updated_by 取认证主体")
                }
                stub.vaccinationRow = null
                rawRequest(vertx, port, HttpMethod.PUT, "$VACCINATION_PATH/vt-x", """{"status":"停用"}""")
            }.map { (status, payload) ->
                ctx.verify {
                    assertEquals(404, status, "不存在必须 404: $payload")
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  发育量表
    // ========================================================================

    @Test
    fun `创建发育量表 201 且定义落库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.scaleRow = scaleRow()
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, SCALE_PATH, SCALE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(201, status, "body=$payload")
                assertEquals("DB_SCALE", payload.getString("scale_code"))
                assertEquals("v1", payload.getString("scale_version"))

                val insert = stub.tuples.first { it.first.startsWith("insert into healthcare.base_developmental_scales") }
                val values = insert.second
                assertEquals(26, (values[0] as String).length)
                assertEquals("DB_SCALE", values[1])
                assertEquals("v1", values[2])
                assertEquals("库量表", values[3])
                assertEquals(0, values[4])
                assertEquals(36, values[5])
                assertNotNull(values[6] as JsonObject, "definition 原样落库")
                assertEquals("启用", values[7])
                assertEquals("admin-1", values[9])
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `创建发育量表非法定义 400 且不触库`(vertx: Vertx, ctx: VertxTestContext) {
        val cases = listOf(
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":0,"age_max_months":36}"""
                to "definition is required",
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":36,"age_max_months":0,"definition":${DEFINITION}}"""
                to "age_max_months must not be less than age_min_months",
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":0,"age_max_months":36,"definition":{"items":[]}}"""
                to "definition.domains is required",
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":0,"age_max_months":36,"definition":{"domains":[],"items":[]}}"""
                to "definition.domains must not be empty",
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":0,"age_max_months":36,"definition":{"domains":[{"code":"GM","name":"大运动"}],"items":[{"code":"I1","domain":"XX","prompt":"p","min_months":0,"max_months":1,"options":[{"value":0,"label":"否"}]}]}}"""
                to "domain must be one of",
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":0,"age_max_months":36,"definition":{"domains":[{"code":"GM","name":"大运动"}],"items":[{"code":"I1","domain":"GM","prompt":"p","min_months":0,"max_months":1,"options":[]}]}}"""
                to "options must not be empty",
            """{"scale_code":"S","scale_version":"v1","name":"量表","age_min_months":0,"age_max_months":36,"definition":${DEFINITION},"updated_by":"hacker"}"""
                to "unsupported base developmental scale keys",
        )
        withServer(vertx, userId = "admin-1") { port ->
            // 路由装配会 fire-and-forget 加载内容（读库优先）；清掉装配期查询，只断言请求期零触库
            stub.executedSql.clear()
            cases.fold(Future.succeededFuture<Unit>()) { chain, (body, expected) ->
                chain.compose {
                    rawRequest(vertx, port, HttpMethod.POST, SCALE_PATH, body).map { (status, payload) ->
                        assertEquals(400, status, "body=$body response=$payload")
                        assertTrue(
                            payload.getString("error")!!.contains(expected),
                            "期望错误含 '$expected'，实际: $payload",
                        )
                    }
                }
            }
        }.onComplete { ar ->
            ctx.verify {
                ar.result()
                assertTrue(stub.executedSql.isEmpty(), "非法入参必须在进库前被拒绝：${stub.executedSql}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `量表编码加版本重复 409 且不写库`(vertx: Vertx, ctx: VertxTestContext) {
        stub.scaleCodeTaken = true
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(vertx, port, HttpMethod.POST, SCALE_PATH, SCALE_BODY)
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(409, status, "body=$payload")
                assertTrue(payload.getString("error")!!.contains("already exists"), "body=$payload")
                assertTrue(stub.executedSql.none { it.startsWith("insert") })
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `发育量表列表空结果与修改 404`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx, userId = "admin-1") { port ->
            rawRequest(vertx, port, HttpMethod.GET, SCALE_PATH, null)
                .compose { (status, payload) ->
                    ctx.verify {
                        assertEquals(200, status, "body=$payload")
                        assertEquals(0, payload.getJsonArray("records").size())
                        assertEquals(0L, payload.getJsonObject("meta").getLong("total"))
                    }
                    stub.scaleRow = null
                    rawRequest(vertx, port, HttpMethod.PUT, "$SCALE_PATH/ds-x", """{"status":"停用"}""")
                }
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(404, status, "不存在必须 404: $payload")
                        assertTrue(stub.executedSql.none { it.startsWith("update") })
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private companion object {
        const val VACCINATION_PATH = "/healthcare/v1/base-vaccination-types"
        const val SCALE_PATH = "/healthcare/v1/base-developmental-scales"
        const val DEFINITION =
            """{"domains":[{"code":"GM","name":"大运动"}],"items":[{"code":"I1","domain":"GM","prompt":"p","min_months":0,"max_months":1,"options":[{"value":0,"label":"否"},{"value":1,"label":"是"}]}]}"""
        const val VACCINATION_BODY =
            """{"code":"HEPB","name":"乙肝疫苗","dose_count":3,"recommended_months":[0,1,6]}"""
        const val SCALE_BODY =
            """{"scale_code":"DB_SCALE","scale_version":"v1","name":"库量表","age_min_months":0,"age_max_months":36,"definition":$DEFINITION}"""
    }

    private class BaseDataDatabaseStub {
        val pool: Pool = mockk(relaxed = true)
        private val conn: SqlConnection = mockk(relaxed = true)
        private val prepared: PreparedQuery<RowSet<Row>> = mockk(relaxed = true)

        val executedSql = mutableListOf<String>()
        val tuples = mutableListOf<Pair<String, List<Any?>>>()

        var vaccinationRow: Row? = null
        var vaccinationCodeTaken: Boolean = false
        var vaccinationCount: Long = 0
        var scaleRow: Row? = null
        var scaleCodeTaken: Boolean = false
        var scaleCount: Long = 0

        private var lastSql = ""

        init {
            every { conn.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { conn.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
            every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
            every { prepared.execute(any<Tuple>()) } answers {
                val sql = lastSql
                tuples.add(sql to tupleValues(firstArg()))
                Future.succeededFuture(route(sql))
            }
            every { prepared.execute() } answers {
                tuples.add(lastSql to emptyList())
                Future.succeededFuture(route(lastSql))
            }
        }

        fun reset() {
            executedSql.clear()
            tuples.clear()
            vaccinationRow = null
            vaccinationCodeTaken = false
            vaccinationCount = 0
            scaleRow = null
            scaleCodeTaken = false
            scaleCount = 0
            lastSql = ""
        }

        private fun route(sql: String): RowSet<Row> = when {
            // 内容加载（读库优先）：测试默认空 → 覆盖层置空 → 回落常量
            sql.contains("status = '启用'") -> rowSet()
            sql.contains("base_vaccination_types where id =") ->
                vaccinationRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("base_vaccination_types where code =") ->
                if (vaccinationCodeTaken) rowSet(mockRow(mapOf("id" to "other"))) else rowSet()
            sql.startsWith("insert into healthcare.base_vaccination_types") -> rowSet()
            sql.startsWith("update healthcare.base_vaccination_types") -> rowSet()
            sql.contains("select count(*) as total from healthcare.base_vaccination_types") ->
                rowSet(mockRow(mapOf("total" to vaccinationCount)))
            sql.contains("from healthcare.base_vaccination_types") && sql.contains("order by") -> rowSet()
            sql.contains("from healthcare.base_vaccination_types") ->
                vaccinationRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("base_developmental_scales where id =") ->
                scaleRow?.let { rowSet(it) } ?: rowSet()
            sql.contains("scale_code = ") ->
                if (scaleCodeTaken) rowSet(mockRow(mapOf("id" to "other"))) else rowSet()
            sql.startsWith("insert into healthcare.base_developmental_scales") -> rowSet()
            sql.startsWith("update healthcare.base_developmental_scales") -> rowSet()
            sql.contains("select count(*) as total from healthcare.base_developmental_scales") ->
                rowSet(mockRow(mapOf("total" to scaleCount)))
            sql.contains("from healthcare.base_developmental_scales") && sql.contains("order by") -> rowSet()
            sql.contains("from healthcare.base_developmental_scales") ->
                scaleRow?.let { rowSet(it) } ?: rowSet()
            else -> rowSet()
        }

        private fun record(sql: String) {
            lastSql = sql.lowercase().replace("\"", "")
            executedSql.add(lastSql)
        }
    }

    private fun <T> withServer(
        vertx: Vertx,
        userId: String?,
        block: (Int) -> Future<T>,
    ): Future<T> {
        val router = Router.router(vertx)
        if (userId != null) {
            router.route("/healthcare/v1/*").handler { ctx -> ctx.put("userId", userId); ctx.next() }
        }
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, stub.pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose { value -> server.close().map(value) }
        }
    }

    private fun rawRequest(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        rawBody: String?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "localhost", path)
            .compose { req ->
                if (rawBody != null) req.putHeader("Content-Type", "application/json").send(rawBody) else req.send()
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer)
                    } catch (_: RuntimeException) {
                        JsonObject()
                    }
                    resp.statusCode() to json
                }
            }
            .onComplete { client.close() }
    }
}

// ========================================================================
//  mockk 桩辅助（Kotlin 顶层 private 为文件可见，故本文件自带一份）
// ========================================================================

private fun vaccinationRow(
    id: String = "vt-1",
    code: String = "HEPB",
    name: String = "乙肝疫苗",
    doseCount: Int = 3,
    status: String = "启用",
): Row = mockRow(
    mapOf(
        "id" to id,
        "code" to code,
        "name" to name,
        "dose_count" to doseCount,
        "recommended_months" to JsonArray().add(0).add(1).add(6),
        "status" to status,
        "sort_order" to 0,
        "metadata" to null,
        "created_by" to "admin-1",
        "updated_by" to "admin-1",
        "created_at" to OffsetDateTime.now(),
        "updated_at" to OffsetDateTime.now(),
    ),
)

private fun scaleRow(
    id: String = "ds-1",
    scaleCode: String = "DB_SCALE",
    scaleVersion: String = "v1",
    status: String = "启用",
): Row = mockRow(
    mapOf(
        "id" to id,
        "scale_code" to scaleCode,
        "scale_version" to scaleVersion,
        "name" to "库量表",
        "age_min_months" to 0,
        "age_max_months" to 36,
        "definition" to JsonObject()
            .put("domains", JsonArray().add(JsonObject().put("code", "GM").put("name", "大运动")))
            .put("items", JsonArray()),
        "status" to status,
        "metadata" to null,
        "created_by" to "admin-1",
        "updated_by" to "admin-1",
        "created_at" to OffsetDateTime.now(),
        "updated_at" to OffsetDateTime.now(),
    ),
)

private fun mockRow(values: Map<String, Any?>): Row {
    val row = mockk<Row>()
    every { row.getString(any<String>()) } answers { values[firstArg<String>()] as? String }
    every { row.getValue(any<String>()) } answers { values[firstArg<String>()] }
    every { row.getInteger(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toInt() }
    every { row.getLong(any<String>()) } answers { (values[firstArg<String>()] as? Number)?.toLong() }
    every { row.getJsonArray(any<String>()) } answers { values[firstArg<String>()] as? JsonArray }
    every { row.getJsonObject(any<String>()) } answers { values[firstArg<String>()] as? JsonObject }
    every { row.getOffsetDateTime(any<String>()) } answers { values[firstArg<String>()] as? OffsetDateTime }
    return row
}

private fun rowSet(vararg rows: Row): RowSet<Row> {
    val rs = mockk<RowSet<Row>>()
    every { rs.iterator() } answers {
        val delegate = rows.iterator()
        val iterator = mockk<io.vertx.sqlclient.RowIterator<Row>>()
        every { iterator.hasNext() } answers { delegate.hasNext() }
        every { iterator.next() } answers { delegate.next() }
        iterator
    }
    every { rs.size() } returns rows.size
    return rs
}

private fun tupleValues(tuple: Tuple): List<Any?> {
    val result = mutableListOf<Any?>()
    for (i in 0 until tuple.size()) {
        result.add(tuple.getValue(i))
    }
    return result
}
