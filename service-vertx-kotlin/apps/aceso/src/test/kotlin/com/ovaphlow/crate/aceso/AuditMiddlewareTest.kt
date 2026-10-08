package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.healthcare.AuditEntry
import com.ovaphlow.crate.healthcare.AuditRecorder
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * 054 F3 审计中间件单测（**不访问数据库**）。
 *
 * 锁五件事：
 *  1. 写方法（POST/PUT/PATCH/DELETE）在响应结束时落**恰好一条**元数据（method/path/status/actor/`:id`）；
 *  2. **绝不落请求体**（只记元数据；`AuditEntry` 结构上无 body，且 metadata 不含请求体内容）；
 *  3. GET 不入账；
 *  4. 落库失败**不阻断主请求、不改状态码**（旁路语义）；
 *  5. **门禁 403 尝试同样入账**（中间件注册在集中式门禁之前）。
 */
@ExtendWith(VertxExtension::class)
class AuditMiddlewareTest {

    private class RecordingRecorder : AuditRecorder {
        val entries = mutableListOf<AuditEntry>()
        var failure: Throwable? = null

        override fun record(entry: AuditEntry): Future<Unit> {
            entries.add(entry)
            return failure?.let { Future.failedFuture(it) } ?: Future.succeededFuture(Unit)
        }
    }

    @Test
    fun `写方法在响应结束时落一条元数据且不含请求体`(vertx: Vertx, ctx: VertxTestContext) {
        val recorder = RecordingRecorder()
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        // 生产顺序：认证总闸（提供 userId）→ 审计中间件 → 业务路由
        router.route().handler { ctx2 -> ctx2.put("userId", "cashier-1"); ctx2.next() }
        router.route().handler(auditMiddleware(recorder))
        router.post("/crate-api/healthcare/v1/patients/:id").handler { ctx2 ->
            ctx2.response().setStatusCode(201).putHeader("Content-Type", "application/json")
                .end(JsonObject().put("ok", true).encode())
        }

        withServer(vertx, router) { port ->
            rawRequest(
                vertx,
                port,
                HttpMethod.POST,
                "/crate-api/healthcare/v1/patients/p-1",
                """{"name":"敏感-PHI-姓名","id_card":"110101199001011234"}""",
            )
        }.onComplete { ar ->
            ctx.verify {
                val (status, _) = ar.result()
                assertEquals(201, status, "主请求状态码不得被审计影响")
                assertEquals(1, recorder.entries.size, "一次响应恰好一条审计记录")
                val entry = recorder.entries[0]
                assertEquals("POST", entry.method)
                assertEquals("/crate-api/healthcare/v1/patients/p-1", entry.path)
                assertEquals(201, entry.statusCode)
                assertEquals("cashier-1", entry.actor, "actor 取认证主体 ctx[\"userId\"]")
                assertEquals("p-1", entry.resourceId, ":id 路径参数单独落 resource_id")
                assertFalse(entry.metadata!!.getBoolean("platform_admin"), "非平台管理员")
                assertFalse(
                    entry.metadata.toString().contains("敏感-PHI-姓名") ||
                        entry.metadata.toString().contains("110101199001011234"),
                    "审计元数据绝不能包含请求体内容：${entry.metadata}",
                )
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `GET 不入账`(vertx: Vertx, ctx: VertxTestContext) {
        val recorder = RecordingRecorder()
        val router = Router.router(vertx)
        router.route().handler(auditMiddleware(recorder))
        router.get("/crate-api/healthcare/v1/patients").handler { ctx2 -> ctx2.json(JsonObject()) }

        withServer(vertx, router) { port ->
            rawRequest(vertx, port, HttpMethod.GET, "/crate-api/healthcare/v1/patients", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, _) = ar.result()
                assertEquals(200, status)
                assertTrue(recorder.entries.isEmpty(), "GET 不入账：${recorder.entries}")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `落库失败不阻断主请求也不改状态码`(vertx: Vertx, ctx: VertxTestContext) {
        val recorder = RecordingRecorder().apply { failure = RuntimeException("audit db down") }
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        router.route().handler(auditMiddleware(recorder))
        router.put("/crate-api/healthcare/v1/beds/:id").handler { ctx2 ->
            ctx2.response().setStatusCode(200).end(JsonObject().put("ok", true).encode())
        }

        withServer(vertx, router) { port ->
            rawRequest(vertx, port, HttpMethod.PUT, "/crate-api/healthcare/v1/beds/b-1", """{"status":"停用"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, payload) = ar.result()
                assertEquals(200, status, "审计落库失败不得改变主请求状态码：$payload")
                assertTrue(payload.getBoolean("ok"), "主请求响应体不受影响")
                assertEquals(1, recorder.entries.size, "仍尝试落一条（失败只告警）")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `门禁 403 尝试同样入账`(vertx: Vertx, ctx: VertxTestContext) {
        val recorder = RecordingRecorder()
        val router = Router.router(vertx)
        router.route().handler(auditMiddleware(recorder))
        // 模拟集中式门禁：直接 end 403（审计注册在它之前，故必须入账）
        router.post("/crate-api/healthcare/v1/patients").handler { ctx2 ->
            ctx2.response().setStatusCode(403)
                .end(JsonObject().put("error", "缺少权限：health:manage").encode())
        }

        withServer(vertx, router) { port ->
            rawRequest(vertx, port, HttpMethod.POST, "/crate-api/healthcare/v1/patients", """{"name":"x"}""")
        }.onComplete { ar ->
            ctx.verify {
                val (status, _) = ar.result()
                assertEquals(403, status)
                assertEquals(1, recorder.entries.size, "403 尝试必须入账")
                assertEquals(403, recorder.entries[0].statusCode)
                assertEquals("/crate-api/healthcare/v1/patients", recorder.entries[0].path)
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `子路由挂载下路径不重复前缀`(vertx: Vertx, ctx: VertxTestContext) {
        val recorder = RecordingRecorder()
        val apiRouter = Router.router(vertx)
        apiRouter.route().handler(auditMiddleware(recorder))
        apiRouter.delete("/healthcare/v1/beds/:id").handler { ctx2 -> ctx2.response().setStatusCode(204).end() }
        val root = Router.router(vertx)
        root.route("/crate-api/*").subRouter(apiRouter)

        withServer(vertx, root) { port ->
            rawRequest(vertx, port, HttpMethod.DELETE, "/crate-api/healthcare/v1/beds/b-9", null)
        }.onComplete { ar ->
            ctx.verify {
                val (status, _) = ar.result()
                assertEquals(204, status)
                assertEquals(1, recorder.entries.size)
                assertEquals(
                    "/crate-api/healthcare/v1/beds/b-9",
                    recorder.entries[0].path,
                    "挂载点 + normalizedPath 必须幂等，不得出现 /crate-api/crate-api",
                )
                assertEquals("b-9", recorder.entries[0].resourceId)
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  脚手架
    // ========================================================================

    private fun <T> withServer(vertx: Vertx, router: Router, block: (Int) -> Future<T>): Future<T> =
        vertx.createHttpServer().requestHandler(router).listen(0).compose { server ->
            block(server.actualPort()).compose { value -> server.close().map(value) }
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
