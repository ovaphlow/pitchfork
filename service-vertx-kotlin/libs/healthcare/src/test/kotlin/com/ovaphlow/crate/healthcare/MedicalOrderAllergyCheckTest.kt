package com.ovaphlow.crate.healthcare

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * 053 D3 开嘱前检查与提醒接线测试（**不访问数据库**）。
 *
 * 锁定验收口径（053 §4 D3、§8 验收 7、§11 裁定 4、§12 R3）：
 *  - 分级非阻塞：一般提醒永不阻断；**严重**项未在 `acknowledged_warnings` 中确认才进入拒绝集；
 *  - 零误报：无过敏史 + 非年龄禁忌药品 → 拒绝集为空；
 *  - `acknowledged_warnings` 解析：字符串数组 → 集合；非数组 / 含非字符串 → 400；
 *  - 只读 `GET /drug-allergy-check` 路由：缺参 400；端口未接线 fail-closed 503。
 *
 * 纯逻辑直接调用 `MedicalOrderService` companion 辅助函数；路由测试用 relaxed mockk `Pool`，
 * 因为缺参与端口未配置两条路径都在触库之前返回。**不引用 jOOQ**。
 */
@ExtendWith(VertxExtension::class)
class MedicalOrderAllergyCheckTest {

    private val pool: Pool = mockk(relaxed = true)

    // ========================================================================
    //  分级与二次确认（纯逻辑）
    // ========================================================================

    @Test
    fun `severeWarnings 只保留严重项`() {
        val warnings = listOf(
            warning(severity = "一般", matched = "青霉素"),
            warning(severity = "严重", matched = "PENICILLIN_CEPHALOSPORIN"),
        )
        assertEquals(
            listOf("PENICILLIN_CEPHALOSPORIN"),
            MedicalOrderService.severeWarnings(warnings).map { it.matched },
        )
    }

    @Test
    fun `unacknowledgedSevere 一般项永不阻断`() {
        val warnings = listOf(warning(severity = "一般", matched = "青霉素"))
        assertTrue(
            MedicalOrderService.unacknowledgedSevere(warnings, emptySet()).isEmpty(),
            "一般提醒默认非阻塞，绝不进入拒绝集",
        )
    }

    @Test
    fun `unacknowledgedSevere 严重项未确认被列出且确认后为空`() {
        val warnings = listOf(
            warning(severity = "严重", matched = "PENICILLIN_CEPHALOSPORIN", source = "交叉反应"),
            warning(severity = "严重", matched = "QUINOLONE_CHILD", source = "年龄禁忌"),
        )
        assertEquals(
            listOf("QUINOLONE_CHILD"),
            MedicalOrderService.unacknowledgedSevere(
                warnings,
                setOf("PENICILLIN_CEPHALOSPORIN"),
            ).map { it.matched },
        )
        assertTrue(
            MedicalOrderService.unacknowledgedSevere(
                warnings,
                setOf("PENICILLIN_CEPHALOSPORIN", "QUINOLONE_CHILD"),
            ).isEmpty(),
            "全部严重项已确认后必须放行",
        )
    }

    @Test
    fun `unacknowledgedSevere 空提醒为空`() {
        assertTrue(MedicalOrderService.unacknowledgedSevere(emptyList(), emptySet()).isEmpty())
        assertTrue(MedicalOrderService.unacknowledgedSevere(emptyList(), setOf("X")).isEmpty())
    }

    @Test
    fun `无过敏史且非年龄禁忌药品零误报`() {
        // 无过敏史：过敏史/交叉反应类零产出；下列药品也不命中年龄禁忌 → 拒绝集为空。
        for (drug in listOf("青霉素", "阿莫西林", "头孢克肟", "维生素C", "对乙酰氨基酚")) {
            val warnings = DrugAllergyChecker.check(drug, "CODE-$drug", emptyList(), 24)
            assertTrue(
                MedicalOrderService.unacknowledgedSevere(warnings, emptySet()).isEmpty(),
                "无过敏史 + 非年龄禁忌药品「$drug」不得误报",
            )
        }
    }

    @Test
    fun `无过敏史但年龄禁忌仍产出严重项`() {
        // 与 D2 语义一致：年龄禁忌独立于过敏史；此处验证它进入拒绝集需二次确认。
        val warnings = DrugAllergyChecker.check("左氧氟沙星", null, emptyList(), 10)
        assertEquals(
            listOf("QUINOLONE_CHILD"),
            MedicalOrderService.unacknowledgedSevere(warnings, emptySet()).map { it.matched },
        )
    }

    // ========================================================================
    //  acknowledged_warnings 解析（纯逻辑）
    // ========================================================================

    @Test
    fun `parseAcknowledgedWarnings 合法数组与缺省返回集合`() {
        assertEquals(
            setOf("A", "B"),
            MedicalOrderService.parseAcknowledgedWarnings(JsonArray().add("A").add("B").add("A")),
        )
        assertTrue(MedicalOrderService.parseAcknowledgedWarnings(null).isEmpty(), "未提交 → 空集")
        assertTrue(MedicalOrderService.parseAcknowledgedWarnings(JsonArray()).isEmpty(), "空数组 → 空集")
    }

    @Test
    fun `parseAcknowledgedWarnings 非数组或含非字符串返回400`() {
        val nonArray = assertThrows(IllegalArgumentException::class.java) {
            MedicalOrderService.parseAcknowledgedWarnings("青霉素")
        }
        assertTrue(
            nonArray.message?.contains("must be an array of strings") == true,
            "got: ${nonArray.message}",
        )

        val nonString = assertThrows(IllegalArgumentException::class.java) {
            MedicalOrderService.parseAcknowledgedWarnings(JsonArray().add("A").add(2))
        }
        assertTrue(
            nonString.message?.contains("acknowledged_warnings[1] must be a string") == true,
            "got: ${nonString.message}",
        )
    }

    @Test
    fun `DrugAllergyWarningException 携带未确认严重提醒`() {
        val severe = listOf(warning(severity = "严重", matched = "PENICILLIN_CEPHALOSPORIN"))
        val exception = DrugAllergyWarningException(
            "drug allergy warning requires acknowledgment: PENICILLIN_CEPHALOSPORIN",
            severe,
        )
        assertEquals(severe, exception.warnings)
        assertTrue(exception.message?.contains("PENICILLIN_CEPHALOSPORIN") == true)
    }

    // ========================================================================
    //  只读路由：GET /drug-allergy-check（缺参 400 / 端口未接线 503）
    // ========================================================================

    @Test
    fun `过敏检查缺参返回400`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx) { port ->
            rawRequest(vertx, port, "/healthcare/v1/drug-allergy-check").compose { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "缺 patient_id/material_id 必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("patient_id is required") == true)
                }
                rawRequest(vertx, port, "/healthcare/v1/drug-allergy-check?patient_id=p1")
            }.map { (status, payload) ->
                ctx.verify {
                    assertEquals(400, status, "缺 material_id 必须 400: $payload")
                    assertTrue(payload.getString("error")?.contains("material_id is required") == true)
                }
            }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    @Test
    fun `过敏检查端口未接线返回503`(vertx: Vertx, ctx: VertxTestContext) {
        withServer(vertx) { port ->
            rawRequest(vertx, port, "/healthcare/v1/drug-allergy-check?patient_id=p1&material_id=m1")
                .map { (status, payload) ->
                    ctx.verify {
                        assertEquals(503, status, "目录端口未接线必须 fail-closed 503: $payload")
                        assertTrue(payload.getString("error")?.contains("not configured") == true)
                    }
                }
        }.onComplete { ar -> if (ar.succeeded()) ctx.completeNow() else ctx.failNow(ar.cause()) }
    }

    // ========================================================================
    //  测试脚手架
    // ========================================================================

    private fun warning(
        severity: String,
        matched: String,
        source: String = "过敏史",
    ): DrugAllergyWarning = DrugAllergyWarning(
        severity = severity,
        reason = "test reason",
        source = source,
        matched = matched,
    )

    /** 端口未注入的半挂载路由：`GET /drug-allergy-check` 走 [DrugCatalogUnavailableException]。 */
    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val router = Router.router(vertx)
        router.route("/healthcare/v1/*").subRouter(HealthcareRoutes.create(vertx, pool))
        return vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { } }
        }
    }

    private fun rawRequest(
        vertx: Vertx,
        port: Int,
        path: String,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(HttpMethod.GET, port, "localhost", path)
            .compose { it.send() }
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
