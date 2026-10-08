package com.ovaphlow.crate.dining

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.util.Collections

/**
 * 054 A4b：儿保儿童饮食档案（无入住）与过敏原可达的**不连数据库**护栏（mockk 桩 `Pool`）。
 *
 * 锁定语义：
 *  1. 儿童（`person_type=儿童`）**无入住可建档**：`encounter_id` 绑 NULL（V603 已放开 NOT NULL）。
 *  2. **非儿童行为完全不变**：无 `encounter_id` 仍 400；非 ACTIVE/非 ELDERLY_CARE 仍 400；
 *     患者不存在时仍走既有分支（不新增 404）。
 *  3. `PUT /diet-profiles/by-patient/:patient_id/allergens`：
 *     儿童 → upsert 最小启用档案（`encounter_id=NULL`，`ON CONFLICT (patient_id) WHERE status='启用'`）+ 覆盖写过敏原；
 *     非儿童 → 只更新既有启用档案；没有启用档案 → 400；患者不存在 → 404；缺 `allergies` → 400。
 *  4. 过敏原一律写既有列 `dining_diet_profiles.allergies`（字符串数组口径），**不新建主数据表**。
 */
@ExtendWith(VertxExtension::class)
class DietProfileChildAllergenRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val profileInsertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val upsertTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())
    private val updateTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    private var personType: String? = "儿童"
    private var patientFound = true
    private var activeProfileId: String? = null
    private var encounterType = "ELDERLY_CARE"
    private var encounterStatus = "ACTIVE"
    private var encounterPatientId = "patient-1"

    @BeforeEach
    fun setUp() {
        executed.clear()
        profileInsertTuples.clear()
        upsertTuples.clear()
        updateTuples.clear()
        personType = "儿童"
        patientFound = true
        activeProfileId = null
        encounterType = "ELDERLY_CARE"
        encounterStatus = "ACTIVE"
        encounterPatientId = "patient-1"

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("select id, person_type from healthcare.patients") ->
                    Future.succeededFuture(if (patientFound) rowSet(personTypeRow()) else rowSet())
                sql.contains("select id from dining.dining_diet_profiles where patient_id") ->
                    Future.succeededFuture(
                        if (activeProfileId != null) rowSet(profileIdRow(activeProfileId!!)) else rowSet(),
                    )
                sql.contains("on conflict (patient_id)") -> {
                    upsertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(profileIdRow("profile-child")))
                }
                sql.contains("update dining.dining_diet_profiles set allergies") -> {
                    updateTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("insert into dining.dining_diet_profiles") -> {
                    profileInsertTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("from healthcare.encounters") -> Future.succeededFuture(rowSet(encounterRow()))
                sql.contains("from dining.dining_diet_profiles") -> Future.succeededFuture(rowSet(profileRow()))
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    // ========================================================================
    //  建档分流
    // ========================================================================

    @Test
    fun `儿童无入住可建档且encounter_id绑NULL`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "儿童"

        post(vertx, JsonObject().put("patient_id", "patient-1").put("meal_type", "普食")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "儿保儿童无入住必须可建档，实际: ${body.encode()}")
                assertEquals(1, profileInsertTuples.size, "必须执行一次 INSERT")
                val tuple = profileInsertTuples.first()
                // 列序：0 id / 1 patient_id / 2 encounter_id / 3 meal_type ...
                assertNull(tuple.getValue(2), "儿童建档 encounter_id 必须为 NULL")
                assertEquals("patient-1", tuple.getValue(1))
                assertTrue(
                    executed.none { it.contains("from healthcare.encounters") },
                    "儿童无入住不得去校验入住：$executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童无入住仍返回400`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "长者"

        post(vertx, JsonObject().put("patient_id", "patient-1").put("meal_type", "普食")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "非儿童无入住必须仍 400（行为不变），实际: ${body.encode()}")
                assertEquals("encounter_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童入住非在院仍返回400`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "长者"
        encounterStatus = "DISCHARGED"

        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("encounter_id", "enc-1").put("meal_type", "普食"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "非在院入住必须仍 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("encounter is not active"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童入住类型不符仍返回400`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "长者"
        encounterType = "OUTPATIENT"

        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("encounter_id", "enc-1").put("meal_type", "普食"),
        ) { status, body ->
            ctx.verify {
                assertEquals(400, status, "实际: ${body.encode()}")
                assertEquals("encounter is not an elderly admission", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `患者不存在时仍走既有分支不新增404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false

        post(vertx, JsonObject().put("patient_id", "patient-x").put("meal_type", "普食")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "既有行为：缺 encounter_id 仍 400（不引入新的 404）")
                assertEquals("encounter_id is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童在院入住建档路径不变`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "长者"

        post(
            vertx,
            JsonObject().put("patient_id", "patient-1").put("encounter_id", "enc-1").put("meal_type", "普食"),
        ) { status, body ->
            ctx.verify {
                assertEquals(201, status, "养老建档链路必须不变，实际: ${body.encode()}")
                val tuple = profileInsertTuples.first()
                assertEquals("enc-1", tuple.getValue(2), "非儿童 encounter_id 必须落库")
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  patient 维度过敏原
    // ========================================================================

    @Test
    fun `儿童无档案时upsert最小启用档案并覆盖写过敏原`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "儿童"
        activeProfileId = null

        putAllergens(vertx, "patient-1", JsonObject().put("allergies", JsonArray().add("花生").add("牛奶"))) { status, body ->
            ctx.verify {
                assertEquals(200, status, "儿保过敏原标记必须可达，实际: ${body.encode()}")
                val upsertSql = executed.first { it.contains("on conflict (patient_id)") }
                assertTrue(
                    upsertSql.contains("null"),
                    "upsert 必须把 encounter_id 写死 NULL（儿童无入住）：$upsertSql",
                )
                assertTrue(
                    upsertSql.contains("where status = '启用'"),
                    "ON CONFLICT 推断谓词必须与部分唯一索引一致：$upsertSql",
                )
                val tuple = upsertTuples.first()
                // 列序：0 id / 1 patient_id / 2 meal_type / 3 allergies ...
                assertEquals("patient-1", tuple.getValue(1))
                assertEquals("普食", tuple.getValue(2), "缺省餐食类型")
                assertEquals(
                    listOf("花生", "牛奶"),
                    (tuple.getValue(3) as JsonArray).map { it.toString() },
                )
                assertEquals(listOf("花生", "牛奶"), body.getJsonArray("allergies")?.map { it.toString() })
                assertTrue(
                    executed.none {
                        it.contains("insert into dining.dining_diet_profiles") && !it.contains("on conflict")
                    },
                    "不得走通用建档（会要求入住）：$executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童已有启用档案时仍走upsert覆盖`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "儿童"
        activeProfileId = "profile-child"

        putAllergens(vertx, "patient-1", JsonObject().put("allergies", "花生，海鲜")) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertTrue(
                    executed.any { it.contains("on conflict (patient_id)") },
                    "儿童路径统一走 upsert（存在即覆盖）：$executed",
                )
                val tuple = upsertTuples.first()
                assertEquals(
                    listOf("花生", "海鲜"),
                    (tuple.getValue(3) as JsonArray).map { it.toString() },
                    "逗号分隔字符串必须按既有口径拆分",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童无启用档案返回400且不新建`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "长者"
        activeProfileId = null

        putAllergens(vertx, "patient-1", JsonObject().put("allergies", JsonArray().add("花生"))) { status, body ->
            ctx.verify {
                assertEquals(400, status, "非儿童无档案不得新建，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("非儿童必须先建立饮食档案"), body.encode())
                assertTrue(
                    executed.none { it.contains("on conflict (patient_id)") },
                    "不得走儿童 upsert 分支：$executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非儿童有启用档案时只更新过敏原`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "长者"
        activeProfileId = "profile-elder"

        putAllergens(vertx, "patient-1", JsonObject().put("allergies", JsonArray().add("青霉素"))) { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                assertTrue(
                    executed.any { it.contains("update dining.dining_diet_profiles set allergies") },
                    "必须覆盖更新既有档案：$executed",
                )
                assertEquals(
                    listOf("青霉素"),
                    (updateTuples.first().getValue(0) as JsonArray).map { it.toString() },
                )
                assertTrue(
                    executed.none { it.contains("on conflict (patient_id)") },
                    "非儿童不得走 upsert：$executed",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童档案单条查询走leftJoin以保留encounter_id为NULL的行`(vertx: Vertx, ctx: VertxTestContext) {
        personType = "儿童"
        activeProfileId = null

        putAllergens(vertx, "patient-1", JsonObject().put("allergies", JsonArray().add("花生"))) { status, _ ->
            ctx.verify {
                assertEquals(200, status)
                val getSql = executed.first {
                    it.contains("from dining.dining_diet_profiles") && it.contains("join healthcare.encounters")
                }
                // 儿童档案 encounter_id 为 NULL：INNER JOIN 会整行丢弃 → 单条查询 404。
                // DB-free 桩无法验证 join 语义，这里锁 SQL 文本（真库护栏由集成测试补）。
                assertTrue(
                    getSql.contains("left outer join"),
                    "encounter_id 可空 → encounters 必须 LEFT JOIN，实际: $getSql",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童档案列表查询同样走leftJoin`(vertx: Vertx, ctx: VertxTestContext) {
        getProfiles(vertx, "/dining/v1/diet-profiles?patient_id=patient-1") { status, body ->
            ctx.verify {
                assertEquals(200, status, "实际: ${body.encode()}")
                val listSql = executed.first {
                    it.contains("from dining.dining_diet_profiles") && it.contains("join healthcare.encounters")
                }
                assertTrue(
                    listSql.contains("left outer join"),
                    "列表同样必须 LEFT JOIN（否则儿童档案在列表里永远查不到）：$listSql",
                )
                assertTrue(
                    body.containsKey("records") && body.containsKey("meta"),
                    "列表形状必须是 {records, meta}：${body.encode()}",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `过敏原缺失返回400`(vertx: Vertx, ctx: VertxTestContext) {
        putAllergens(vertx, "patient-1", JsonObject()) { status, body ->
            ctx.verify {
                assertEquals(400, status, "覆盖式写入必须显式给出 allergies，实际: ${body.encode()}")
                assertEquals("allergies is required", body.getString("error"))
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `儿童不存在返回404`(vertx: Vertx, ctx: VertxTestContext) {
        patientFound = false

        putAllergens(vertx, "patient-x", JsonObject().put("allergies", JsonArray().add("花生"))) { status, body ->
            ctx.verify {
                assertEquals(404, status, "实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("patient not found"), body.encode())
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun personTypeRow(): Row = mockk(relaxed = true) {
        every { getString("id") } returns "patient-1"
        every { getString("person_type") } returns personType
    }

    private fun profileIdRow(id: String): Row = mockk(relaxed = true) {
        every { getString("id") } returns id
    }

    private fun encounterRow(): Row = mockk(relaxed = true) {
        every { getString("encounter_type") } returns encounterType
        every { getString("status") } returns encounterStatus
        every { getString("enc_patient_id") } returns encounterPatientId
    }

    /** `DietProfileService.toJson` 走 `row.getValue(...)`。 */
    private fun profileRow(): Row = mockk(relaxed = true) {
        every { getValue("id") } returns "profile-child"
        every { getValue("patient_id") } returns "patient-1"
        every { getValue("patient_name") } returns "小明"
        every { getValue("encounter_id") } returns null
        every { getValue("meal_type") } returns "普食"
        every { getValue("allergies") } returns JsonArray().add("花生").add("牛奶")
        every { getValue("portion_preference") } returns null
        every { getValue("remark") } returns null
        every { getValue("status") } returns "启用"
        every { getValue("metadata") } returns null
        every { getValue("encounter_status") } returns null
        every { getValue("created_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
        every { getValue("updated_at") } returns OffsetDateTime.parse("2026-10-06T12:00:00+08:00")
    }

    private fun post(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/diet-profiles", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun putAllergens(vertx: Vertx, patientId: String, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PUT, "/dining/v1/diet-profiles/by-patient/$patientId/allergens", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun getProfiles(vertx: Vertx, path: String, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.GET, path, null).map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun request(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject?,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "127.0.0.1", path)
            .compose { req ->
                req.putHeader("Content-Type", "application/json")
                if (body == null) req.send() else req.send(body.encode())
            }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer.toString())
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val authHandler = Handler<RoutingContext> { rctx ->
            rctx.put("userId", "dietitian-1")
            rctx.next()
        }
        val root = Router.router(vertx)
        root.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, authHandler))
        return vertx.createHttpServer().requestHandler(root).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { Unit } }
        }
    }

    private fun record(sql: String) {
        executed.add(normalized(sql))
    }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    private fun rowSet(vararg rows: Row, rowCount: Int = rows.size): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { rowCount() } returns rowCount
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }
}
