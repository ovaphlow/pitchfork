package com.ovaphlow.crate.aceso

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.URLEncoder

/**
 * 035 儿保模式真库端到端（L-TEST）——只走真实 HTTP 接口与隔离的 `aceso_test`。
 *
 * 覆盖计划第 9 节的服务端验收条目：
 *  1. 创建儿童 + 儿保字段落位（含 vaccination_records 结构化回读）；
 *  2. 列表按 `person_type=儿童` 过滤，居民不出现、`meta.total` 与过滤结果一致；
 *  3. 单条回显内嵌 `child_profile`，非儿童恒为 null；
 *  4. 更新儿保字段（监护人电话 / 出生体重 / 追加接种记录）后重新 GET 生效；
 *  5. 校验拒绝：非法 person_type、儿童缺出生日期、非儿童带 child_profile 全部 400 且 `{error}`；
 *  6. 缺省创建的居民 `person_type=居民`、`child_profile=null`，不过滤列表仍可见。
 *
 * fixture 说明：所有患者经真实 `POST /healthcare/v1/patients` 创建，服务端生成 ULID 主键，
 * 因此清理不能只按 id 前缀匹配 —— 夹具患者统一把 [fixturePrefix] 写进 `name`，
 * `cleanupFixtures()` 先按 name/id 前缀删儿保子表（外键），再删父表，最后断言残差为 0。
 */
@EnabledIfSystemProperty(named = "integration.db.host", matches = ".+")
class ChildHealthProfileIntegrationTest : AcesoDbIntegrationTestBase() {

    override val fixturePrefix = "01CHILDFIX"
    override val serverPort = 18525

    private val patientsPath = "/healthcare/v1/patients"

    /** URL 编码后的 `儿童`，用于查询串（避免非 ASCII 路径被客户端/服务端处理不一致）。 */
    private val encodedChild = URLEncoder.encode("儿童", Charsets.UTF_8)

    private fun name(suffix: String) = "$fixturePrefix-$suffix"

    /** 计划 §5 冻结契约的儿童创建请求体（全部儿保字段 + 一条接种记录）。 */
    private fun fullChildBody(suffix: String): JsonObject = JsonObject()
        .put("name", name(suffix))
        .put("gender", "女")
        .put("person_type", "儿童")
        .put("birth_date", "2024-05-20")
        .put(
            "child_profile",
            JsonObject()
                .put("guardian_name", "张监护")
                .put("guardian_relationship", "母亲")
                .put("guardian_phone", "13800000001")
                .put("birth_weight_g", 3200)
                .put("birth_height_mm", 500)
                .put("delivery_mode", "顺产")
                .put("feeding_method", "母乳")
                .put("vaccination_summary", "已完成卡介苗、乙肝疫苗")
                .put(
                    "vaccination_records",
                    JsonArray().add(
                        JsonObject()
                            .put("vaccine", "卡介苗")
                            .put("dose", "1")
                            .put("date", "2024-05-21")
                            .put("facility", "市妇幼保健院"),
                    ),
                )
                .put("remark", "足月儿"),
        )

    /** 缺省创建（不带 person_type / child_profile）的居民请求体。 */
    private fun residentBody(suffix: String): JsonObject = JsonObject()
        .put("name", name(suffix))
        .put("gender", "男")

    private fun create(vertx: Vertx, body: JsonObject): Future<Pair<Int, JsonObject>> =
        request(vertx, HttpMethod.POST, patientsPath, body)

    // ========================================================================
    //  fixture 生命周期：无 SQL 夹具；按 name 前缀清理 API 创建的患者
    // ========================================================================

    override fun setupFixtures() {
        // 本类全部夹具经真实接口创建，无需预置 SQL。
    }

    override fun cleanupFixtures() {
        // 外键顺序硬约束：先删 child_health_profiles 再删 patients。
        // API 创建的患者 id 是服务端 ULID，不以 fixturePrefix 开头，故按 name 前缀定位；
        // SQL 夹具（若有）按 id 前缀定位，统一交给基类 cleanupAll。
        executeSql(
            "DELETE FROM healthcare.child_health_profiles WHERE patient_id IN (" +
                "SELECT id FROM healthcare.patients " +
                "WHERE id LIKE '$fixturePrefix%' OR name LIKE '$fixturePrefix%')",
        )
        cleanupAll(fixturePrefix)
        executeSql("DELETE FROM healthcare.patients WHERE name LIKE '$fixturePrefix%'")
    }

    override fun assertNoResidual() {
        val patientResidual = countRows(
            "SELECT count(*) FROM healthcare.patients " +
                "WHERE id LIKE '$fixturePrefix%' OR name LIKE '$fixturePrefix%'",
        )
        check(patientResidual == 0L) {
            "healthcare.patients 仍有 $fixturePrefix 前缀残差：$patientResidual"
        }
        val profileResidual = countRows(
            "SELECT count(*) FROM healthcare.child_health_profiles p " +
                "JOIN healthcare.patients pt ON p.patient_id = pt.id " +
                "WHERE pt.id LIKE '$fixturePrefix%' OR pt.name LIKE '$fixturePrefix%'",
        )
        check(profileResidual == 0L) {
            "healthcare.child_health_profiles 仍有 $fixturePrefix 前缀残差：$profileResidual"
        }
    }

    // ========================================================================
    //  1. 创建儿童 + 儿保字段落位
    // ========================================================================

    @Test
    fun `创建儿童落位全部儿保字段并可回读接种记录`(vertx: Vertx, ctx: VertxTestContext) {
        create(vertx, fullChildBody("create"))
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "创建儿童应 201：${body.encode()}")
                    assertEquals("儿童", body.getString("person_type"), "person_type 必须回显为儿童")
                    val profile = body.getJsonObject("child_profile")
                    assertNotNull(profile, "儿童创建响应必须内嵌 child_profile：${body.encode()}")
                    assertEquals("张监护", profile.getString("guardian_name"))
                    assertEquals("母亲", profile.getString("guardian_relationship"))
                    assertEquals("13800000001", profile.getString("guardian_phone"))
                    assertEquals(3200, profile.getInteger("birth_weight_g"))
                    assertEquals(500, profile.getInteger("birth_height_mm"))
                    assertEquals("顺产", profile.getString("delivery_mode"))
                    assertEquals("母乳", profile.getString("feeding_method"))
                    assertEquals("已完成卡介苗、乙肝疫苗", profile.getString("vaccination_summary"))
                    assertEquals("足月儿", profile.getString("remark"))
                    val records = profile.getJsonArray("vaccination_records")
                    assertNotNull(records, "vaccination_records 必须是 JSON 数组：${profile.encode()}")
                    assertEquals(1, records.size(), "接种记录条数应与提交一致：${records.encode()}")
                    val record = records.getJsonObject(0)
                    assertEquals("卡介苗", record.getString("vaccine"))
                    assertEquals("1", record.getString("dose"))
                    assertEquals("2024-05-21", record.getString("date"))
                    assertEquals("市妇幼保健院", record.getString("facility"))
                }
                Future.succeededFuture(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  2. 列表过滤
    // ========================================================================

    @Test
    fun `列表按person_type过滤只返回儿童且居民不出现`(vertx: Vertx, ctx: VertxTestContext) {
        var childId: String? = null
        var residentId: String? = null
        create(vertx, fullChildBody("list-child"))
            .compose { (status, body) ->
                ctx.verify { assertEquals(201, status, "创建儿童应 201：${body.encode()}") }
                childId = body.getString("id")
                create(vertx, residentBody("list-resident"))
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(201, status, "创建居民应 201：${body.encode()}") }
                residentId = body.getString("id")
                request(
                    vertx,
                    HttpMethod.GET,
                    "$patientsPath?person_type=$encodedChild&limit=100",
                )
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "过滤列表应 200：${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertNotNull(records, "过滤列表必须返回 records：${body.encode()}")
                    val recordObjects = records.map { it as JsonObject }
                    val ids = recordObjects.mapNotNull { it.getString("id") }
                    assertTrue(ids.contains(childId), "过滤列表必须包含儿童 id=$childId：$ids")
                    assertFalse(ids.contains(residentId), "过滤列表不得包含居民 id=$residentId：$ids")
                    recordObjects.forEach { record ->
                        assertEquals("儿童", record.getString("person_type"), "过滤结果含非儿童：${record.encode()}")
                    }
                    assertEquals(
                        recordObjects.size,
                        body.getJsonObject("meta").getInteger("total")?.toInt(),
                        "meta.total 必须与过滤后条数一致：${body.encode()}",
                    )
                }
                Future.succeededFuture(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  3. 单条回显
    // ========================================================================

    @Test
    fun `单条回显儿童内嵌档案而非儿童为null`(vertx: Vertx, ctx: VertxTestContext) {
        var childId: String? = null
        var residentId: String? = null
        create(vertx, fullChildBody("get-child"))
            .compose { (status, body) ->
                ctx.verify { assertEquals(201, status, "创建儿童应 201：${body.encode()}") }
                childId = body.getString("id")
                request(vertx, HttpMethod.GET, "$patientsPath/$childId")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "单条查询应 200：${body.encode()}")
                    assertEquals("儿童", body.getString("person_type"))
                    val profile = body.getJsonObject("child_profile")
                    assertNotNull(profile, "儿童单条查询必须内嵌 child_profile：${body.encode()}")
                    assertEquals("张监护", profile.getString("guardian_name"))
                    assertEquals(1, profile.getJsonArray("vaccination_records").size())
                }
                create(vertx, residentBody("get-resident"))
            }
            .compose { (status, body) ->
                ctx.verify { assertEquals(201, status, "创建居民应 201：${body.encode()}") }
                residentId = body.getString("id")
                request(vertx, HttpMethod.GET, "$patientsPath/$residentId")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "居民单条查询应 200：${body.encode()}")
                    assertEquals("居民", body.getString("person_type"), "缺省创建必须是居民")
                    assertNull(body.getValue("child_profile"), "非儿童 child_profile 必须为 null：${body.encode()}")
                }
                Future.succeededFuture(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  4. 更新
    // ========================================================================

    @Test
    fun `更新儿保字段后重新查询生效`(vertx: Vertx, ctx: VertxTestContext) {
        var patientId: String? = null
        create(vertx, fullChildBody("update"))
            .compose { (status, body) ->
                ctx.verify { assertEquals(201, status, "创建儿童应 201：${body.encode()}") }
                patientId = body.getString("id")
                val updateBody = JsonObject().put(
                    "child_profile",
                    JsonObject()
                        .put("guardian_phone", "13900000002")
                        .put("birth_weight_g", 3500)
                        .put(
                            "vaccination_records",
                            JsonArray()
                                .add(
                                    JsonObject().put("vaccine", "卡介苗").put("dose", "1")
                                        .put("date", "2024-05-21").put("facility", "市妇幼保健院"),
                                )
                                .add(
                                    JsonObject().put("vaccine", "乙肝疫苗").put("dose", "2")
                                        .put("date", "2024-06-20").put("facility", "社区卫生服务中心"),
                                ),
                        ),
                )
                request(vertx, HttpMethod.PUT, "$patientsPath/$patientId", updateBody)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "更新应 200：${body.encode()}")
                    val profile = body.getJsonObject("child_profile")
                    assertNotNull(profile, "更新响应必须内嵌 child_profile：${body.encode()}")
                    assertEquals("13900000002", profile.getString("guardian_phone"))
                    assertEquals(3500, profile.getInteger("birth_weight_g"))
                    assertEquals(2, profile.getJsonArray("vaccination_records").size())
                }
                request(vertx, HttpMethod.GET, "$patientsPath/$patientId")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "更新后重新查询应 200：${body.encode()}")
                    val profile = body.getJsonObject("child_profile")
                    assertNotNull(profile, "重新查询必须内嵌 child_profile：${body.encode()}")
                    assertEquals("13900000002", profile.getString("guardian_phone"), "监护人电话更新未生效")
                    assertEquals(3500, profile.getInteger("birth_weight_g"), "出生体重更新未生效")
                    // 未提交的字段保持原值（部分更新语义）
                    assertEquals("张监护", profile.getString("guardian_name"), "未提交字段不得被清空")
                    assertEquals("顺产", profile.getString("delivery_mode"), "未提交字段不得被清空")
                    val records = profile.getJsonArray("vaccination_records")
                    assertEquals(2, records.size(), "追加的接种记录未生效：${records.encode()}")
                    assertEquals("乙肝疫苗", records.getJsonObject(1).getString("vaccine"))
                }
                Future.succeededFuture(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  5. 校验拒绝
    // ========================================================================

    @Test
    fun `校验拒绝非法person_type儿童缺出生日期非儿童带档案`(vertx: Vertx, ctx: VertxTestContext) {
        val invalidPersonType = JsonObject()
            .put("name", name("invalid-person-type"))
            .put("person_type", "成人")
        val childWithoutBirthDate = JsonObject()
            .put("name", name("child-no-birth"))
            .put("person_type", "儿童")
        val residentWithProfile = JsonObject()
            .put("name", name("resident-with-profile"))
            .put(
                "child_profile",
                JsonObject().put("guardian_name", "李监护"),
            )
        val explicitResidentWithProfile = JsonObject()
            .put("name", name("resident-explicit-with-profile"))
            .put("person_type", "居民")
            .put(
                "child_profile",
                JsonObject().put("guardian_name", "李监护"),
            )

        create(vertx, invalidPersonType)
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "非法 person_type 必须 400：${body.encode()}")
                    assertNotNull(body.getString("error"), "错误响应体必须是 {error: ...}：${body.encode()}")
                }
                create(vertx, childWithoutBirthDate)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "儿童缺出生日期必须 400：${body.encode()}")
                    assertNotNull(body.getString("error"), "错误响应体必须是 {error: ...}：${body.encode()}")
                }
                create(vertx, residentWithProfile)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "缺省居民带 child_profile 必须 400：${body.encode()}")
                    assertNotNull(body.getString("error"), "错误响应体必须是 {error: ...}：${body.encode()}")
                }
                create(vertx, explicitResidentWithProfile)
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(400, status, "显式居民带 child_profile 必须 400：${body.encode()}")
                    assertNotNull(body.getString("error"), "错误响应体必须是 {error: ...}：${body.encode()}")
                }
                Future.succeededFuture(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }

    // ========================================================================
    //  6. 居民不被打扰
    // ========================================================================

    @Test
    fun `缺省创建居民字段正确且不过滤列表仍可见`(vertx: Vertx, ctx: VertxTestContext) {
        var residentId: String? = null
        create(vertx, residentBody("plain-resident"))
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(201, status, "缺省创建应 201：${body.encode()}")
                    assertEquals("居民", body.getString("person_type"), "缺省 person_type 必须是居民")
                    assertNull(body.getValue("child_profile"), "居民 child_profile 必须为 null：${body.encode()}")
                }
                residentId = body.getString("id")
                request(vertx, HttpMethod.GET, "$patientsPath?limit=100")
            }
            .compose { (status, body) ->
                ctx.verify {
                    assertEquals(200, status, "不过滤列表应 200：${body.encode()}")
                    val records = body.getJsonArray("records")
                    assertNotNull(records, "不过滤列表必须返回 records：${body.encode()}")
                    val ids = records.map { (it as JsonObject).getString("id") }
                    assertTrue(ids.contains(residentId), "不过滤列表必须仍能看到居民 id=$residentId：$ids")
                }
                Future.succeededFuture(Unit)
            }
            .onSuccess { ctx.completeNow() }
            .onFailure { ctx.failNow(it) }
    }
}
