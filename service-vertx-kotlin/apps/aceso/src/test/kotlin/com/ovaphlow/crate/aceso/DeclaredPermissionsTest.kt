package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 权限自省端点护栏（**不访问数据库、不连真实服务**）。
 *
 * 锁两件事：
 * 1. 清单与真实接线共用同一个权限码常量——护理执行码必须在列（防止漏登记变成"死配置"）；
 * 2. `GET /access/v1/permissions` 的形状：`permission_codes` 去重、`routes` 逐条可读。
 */
@ExtendWith(VertxExtension::class)
class DeclaredPermissionsTest {

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private var serverPort = 0

    @BeforeEach
    fun setUp(vertx: Vertx) {
        this.vertx = vertx
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        val server = vertx.createHttpServer()
            .requestHandler(AccessRoutes.create(vertx))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)
        serverPort = server.actualPort()
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }

    @Test
    fun `the declared list covers every wired permission code`() {
        val codes = DECLARED_PERMISSIONS.map { it.permissionCode }.toSet()

        assertTrue(codes.contains(NURSING_EXECUTE_PERMISSION), "护理执行码必须登记在清单里，否则角色页会把它标成未接线")
        assertEquals(listOf(NURSING_EXECUTE_PERMISSION), codes.toList(), "新增接线时必须同步更新清单与本断言")
    }

    @Test
    fun `the access endpoint exposes the declared permissions`() {
        val payload = get("/permissions")

        assertEquals(
            listOf(NURSING_EXECUTE_PERMISSION),
            payload.getJsonArray("permission_codes").map { it as String },
        )
        val routes = payload.getJsonArray("routes")
        assertEquals(2, routes.size(), "两条护理执行写路由都在清单里：$routes")
        val first = routes.getJsonObject(0)
        assertEquals("POST", first.getString("method"))
        assertEquals("/nursing/v1/executions/:id/administration", first.getString("path"))
        assertEquals(NURSING_EXECUTE_PERMISSION, first.getString("permission_code"))
    }

    @Test
    fun `the catalog covers every wired code and keeps unwired codes selectable`() {
        val catalogCodes = PERMISSION_CATALOG.map { it.code }.toSet()
        val wiredCodes = DECLARED_PERMISSIONS.map { it.permissionCode }.toSet()
        assertTrue(
            catalogCodes.containsAll(wiredCodes),
            "每个已接线的码都必须在产品权限目录里，否则角色页选不到它：wired=$wiredCodes catalog=$catalogCodes",
        )
        assertTrue(
            catalogCodes.any { !wiredCodes.contains(it) },
            "目录必须保留未接线的码：放量协议要求先建角色再接线",
        )

        val catalog = get("/permissions").getJsonArray("catalog")
        val entries = (0 until catalog.size()).map { catalog.getJsonObject(it) }
        assertEquals(PERMISSION_CATALOG.size, entries.size, "目录条目数与 PERMISSION_CATALOG 必须一致")
        val byCode = entries.associateBy { it.getString("code") }
        wiredCodes.forEach { code ->
            assertTrue(
                byCode[code]?.getBoolean("wired") == true,
                "$code 已接线，catalog 必须标 wired=true",
            )
        }
        assertTrue(
            byCode.values.any { it.getBoolean("wired") != true },
            "未接线的码必须标 wired=false，角色页才能显示「未接线」",
        )
        assertTrue(
            entries.all { !it.getString("description").isNullOrBlank() },
            "每条目录都要有说明，角色页照着它选：$entries",
        )
    }

    private fun get(path: String): JsonObject {
        val result = CompletableFuture<JsonObject>()
        client.request(HttpMethod.GET, serverPort, "127.0.0.1", path).onSuccess { request ->
            request.send().onSuccess { response ->
                response.body().onSuccess { body ->
                    assertEquals(200, response.statusCode(), "body=${body}")
                    result.complete(body.toJsonObject())
                }.onFailure(result::completeExceptionally)
            }.onFailure(result::completeExceptionally)
        }.onFailure(result::completeExceptionally)
        return result.get(10, TimeUnit.SECONDS)
    }
}
