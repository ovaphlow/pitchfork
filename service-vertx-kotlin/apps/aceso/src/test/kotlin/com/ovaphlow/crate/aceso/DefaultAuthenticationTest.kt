package com.ovaphlow.crate.aceso

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit

/**
 * 024 回归测试：`/crate-api` 路由树默认拒绝认证总闸（不访问数据库）。
 *
 * 用**与 `Main.kt` 同构**的最小路由（相同的 `apiRouter` → `<module>/v1` 前缀 subRouter
 * 挂载方式与挂载顺序）+ 一个**假 sessionAuth** 替代真实的 `idpSessionAuthHandler`，
 * 从而在不连 IdP、不连库的前提下固定三件事：
 *
 * 1. 总闸在 `apiRouter` 子路由里看到的路径形态（这直接决定白名单常量怎么写字面量）；
 * 2. 未认证请求对每个已挂载模块的读/写路由都 **401 且不进入业务处理器**；
 * 3. 总闸**注册顺序**是必需的（对照路由器漏流量 → 防止未来有人把它挪到子路由之后）。
 *
 * 说明：真实的 401 响应形状由 `Main.kt` 里的 private `respondUnauthorized` 产出；本测试
 * 的假 sessionAuth 复制同一形状（`401` + `{"error":"authentication required"}`），
 * 不修改也不依赖那两个 private 函数的既有行为。
 */
@ExtendWith(VertxExtension::class)
class DefaultAuthenticationTest {

    private data class Probe(
        val status: Int,
        val body: String,
        val allowOrigin: String?,
    )

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private var port: Int = 0

    /** 记录总闸交给 sessionAuth 的 `normalizedPath()`：既证明"调用了它"，也钉死路径形态。 */
    private val sessionAuthPaths = mutableListOf<String>()

    /** 记录总闸所在上下文的 `mountPoint()`：与完整路径一起构成路径形态的证据。 */
    private val sessionAuthMountPoints = mutableListOf<String?>()

    /** 记录真正被执行到的业务处理器：这是"未认证不进入业务逻辑"的唯一可靠证据。 */
    private val businessCalls = mutableListOf<String>()

    private val sessionAuth: Handler<RoutingContext> = Handler { ctx ->
        sessionAuthPaths += ctx.normalizedPath()
        sessionAuthMountPoints += ctx.mountPoint()
        val cookie = ctx.request().getHeader("Cookie")
        if (cookie != null && cookie.contains("identityd_session=")) {
            ctx.put("userId", "test-user")
            ctx.next()
        } else {
            // 与 Main.kt 的 respondUnauthorized 行为一致（private，无法直接复用）。
            ctx.response()
                .setStatusCode(401)
                .end(JsonObject().put("error", "authentication required").encode())
        }
    }

    private val authenticatedCookie = "identityd_session=opaque-test-token"

    /** 每个已挂载模块一条读 + 一条写（路径取自各 lib 的真实路由注册）。 */
    private val protectedRoutes: List<Pair<HttpMethod, String>> =
        listOf(
            HttpMethod.GET to "/crate-api/healthcare/v1/patients",
            HttpMethod.PATCH to "/crate-api/healthcare/v1/encounters/e1/death",
            HttpMethod.GET to "/crate-api/nursing/v1/timeline",
            HttpMethod.PATCH to "/crate-api/nursing/v1/executions/e1/status",
            HttpMethod.GET to "/crate-api/pharmacy/v1/dispenses",
            HttpMethod.POST to "/crate-api/pharmacy/v1/dispenses/d1/confirm",
            HttpMethod.GET to "/crate-api/dining/v1/dishes",
            HttpMethod.POST to "/crate-api/dining/v1/rosters/generate",
            HttpMethod.GET to "/crate-api/inventories/v1/materials",
            HttpMethod.POST to "/crate-api/inventories/v1/operations/inbound",
        )

    private val publicHealthPaths: List<String> =
        listOf(
            "/crate-api/inventories/v1/health",
            "/crate-api/healthcare/v1/health",
            "/crate-api/nursing/v1/health",
            "/crate-api/dining/v1/health",
            "/crate-api/pharmacy/v1/health",
        )

    @BeforeEach
    fun setUp(vertx: Vertx) {
        this.vertx = vertx
        sessionAuthPaths.clear()
        sessionAuthMountPoints.clear()
        businessCalls.clear()
        port = startServer(buildProductionShapedRouter(vertx, gateBeforeSubRouters = true))
        // keepAlive=false 让每个请求走新连接（测试只关心闸门判定，不测连接复用语义）。
        // 注意：**它不是**历史 flake 的修法——实测 keepAlive=true 的旧读法同样出现空 body
        // 与读超时（诊断类里 300 次 51 空 + 50 超时）；flake 真因与修法见 [send]。
        client = vertx.createHttpClient(io.vertx.core.http.HttpClientOptions().setKeepAlive(false))
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }

    // ------------------------------------------------------------------
    // 1. 路径形态：apiRouter 子路由里看到的是完整外部路径，不是被截断的相对路径
    // ------------------------------------------------------------------

    @Test
    fun `gate observes the full crate-api prefixed path inside the sub router`() {
        val probe = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/patients")

        assertEquals(401, probe.status, "未认证的受保护读路由必须 401，实际 body=${probe.body}")
        // 实测结论（Vert.x 4.5.12）：apiRouter 里 ctx.normalizedPath() == 完整外部路径
        // `/crate-api/healthcare/v1/patients`，**不是** `/healthcare/v1/patients`。
        // 依据：RoutingContextWrapper.normalizedPath() 直接委托给最外层上下文，
        //      不做挂载点裁剪；只有路由匹配用去挂载点的相对路径。
        // 因此 ApiAuthentication.kt 的白名单常量按完整路径书写。
        assertEquals(
            listOf("/crate-api/healthcare/v1/patients"),
            sessionAuthPaths,
            "总闸交给 sessionAuth 的必须是完整外部路径",
        )
        // 同一上下文的 mountPoint() 是 /crate-api（尾斜杠形式随 Vert.x 版本可能不同），
        // 与"normalizedPath() 不做挂载点裁剪"互为佐证。
        assertTrue(
            sessionAuthMountPoints.single().orEmpty().trimEnd('/') == "/crate-api",
            "mountPoint 应为 /crate-api，实测=${sessionAuthMountPoints}",
        )

        // 白名单据此判定；同时接受去前缀形态，避免 Vert.x 升级后白名单静默失效（fail closed）。
        assertTrue(isPublicApiPath("/crate-api/healthcare/v1/health"))
        assertTrue(isPublicApiPath("/healthcare/v1/health"))
        assertFalse(isPublicApiPath("/crate-api/healthcare/v1/patients"))
    }

    @Test
    fun `whitelist is minimal and does not use a bare endsWith health rule`() {
        // 显式模块枚举：只有 /<module>/v1/health 放行。
        publicHealthPaths.forEach { path -> assertTrue(isPublicApiPath(path), "$path 应放行") }
        // 未挂载的模块、以及"以 health 结尾"的业务路径都不得被放行。
        assertFalse(isPublicApiPath("/crate-api/shared/v1/health"))
        assertFalse(isPublicApiPath("/crate-api/analytics/v1/health"))
        assertFalse(isPublicApiPath("/crate-api/healthcare/v1/patients/health"))
        assertFalse(isPublicApiPath("/crate-api/healthcare/v1/health-checkups/health"))
        // Identity 放行有且只有代理子树。代价：该子树内的一切路径都放行，
        // 包括 Identity 未来可能新增的 `/identity/v1/health`——这是"整棵登录代理子树
        // 必须放行"的既定取舍（计划产品决策 2），不是 `endsWith("/health")` 那种泛匹配。
        assertTrue(isPublicApiPath("/crate-api/identity/v1/session"))
        assertTrue(isPublicApiPath("/crate-api/identity/v1/login"))
        assertTrue(isPublicApiPath("/crate-api/identity/v1/health"))
        assertFalse(isPublicApiPath("/crate-api/identity/v1"))
        // /shared/v1/*（Nexus 代理）默认不放行。
        assertFalse(isPublicApiPath("/crate-api/shared/v1/settings"))
    }

    // ------------------------------------------------------------------
    // 2. 白名单内：各模块 health 直达到业务处理器，且不惊动 sessionAuth
    // ------------------------------------------------------------------

    @Test
    fun `module health paths are public and reach their business handlers`() {
        publicHealthPaths.forEach { path ->
            sessionAuthPaths.clear()
            businessCalls.clear()

            val probe = send(port, HttpMethod.GET, path)

            assertEquals(200, probe.status, "$path 必须仍然 200，实际 body=${probe.body}")
            // "/crate-api/<module>/v1/health" -> "<module>:GET /health"
            assertEquals(
                listOf("${path.split("/")[2]}:GET /health"),
                businessCalls,
                "$path 必须直达 lib 的 health 处理器",
            )
            assertTrue(sessionAuthPaths.isEmpty(), "$path 不应触发 IdP 会话校验")
        }
    }

    // ------------------------------------------------------------------
    // 3. 白名单外：每个已挂载模块的读/写路由未认证一律 401 且不进业务处理器
    // ------------------------------------------------------------------

    @Test
    fun `unauthenticated requests are rejected before any business handler`() {
        assertTrue(protectedRoutes.size >= 10, "至少覆盖 5 个模块 × 读/写各一条")

        protectedRoutes.forEach { (method, path) ->
            sessionAuthPaths.clear()
            businessCalls.clear()

            val probe = send(port, method, path)

            assertEquals(401, probe.status, "$method $path 未认证必须 401，实际 ${probe.status}")
            assertEquals("{\"error\":\"authentication required\"}", probe.body, "$method $path 的 401 响应体必须与既有认证一致")
            assertEquals(listOf(path), sessionAuthPaths, "$method $path 必须由总闸交给 sessionAuth")
            // 关键断言：不能只看状态码——用"业务处理器是否被调用"证明没进业务逻辑
            // （写路由尤其重要：未认证绝不能产生任何数据库写入）。
            assertTrue(
                businessCalls.isEmpty(),
                "$method $path 不得进入业务处理器，却被调用：$businessCalls",
            )
        }
    }

    @Test
    fun `authenticated requests pass the gate and reach business handlers`() {
        // 反向对照：证明上面的 401 来自总闸而不是"探针路由本身没接对"。
        protectedRoutes.forEach { (method, path) ->
            sessionAuthPaths.clear()
            businessCalls.clear()

            val probe = send(port, method, path, authenticatedCookie)

            assertEquals(200, probe.status, "已认证的 $method $path 应到达处理器，body=${probe.body}")
            assertFalse(businessCalls.isEmpty(), "已认证的 $method $path 必须到达业务处理器")
            // 受保护路由即便已认证也要经过会话校验（由它放行）。
            assertEquals(listOf(path), sessionAuthPaths)
        }

        // 白名单路由（各模块 health）连会话校验都不惊动，带不带 cookie 都一样。
        publicHealthPaths.forEach { path ->
            sessionAuthPaths.clear()
            businessCalls.clear()

            val probe = send(port, HttpMethod.GET, path, authenticatedCookie)

            assertEquals(200, probe.status, "已认证的 GET $path 应到达处理器，body=${probe.body}")
            assertFalse(businessCalls.isEmpty(), "已认证的 GET $path 必须到达业务处理器")
            assertTrue(sessionAuthPaths.isEmpty(), "$path 在白名单内，不应触发会话校验")
        }
    }

    // ------------------------------------------------------------------
    // 4. 未知路径：先认证后 404，避免用 401/404 差异探测路由是否存在
    // ------------------------------------------------------------------

    @Test
    fun `unknown paths are rejected identically so route existence cannot be probed`() {
        sessionAuthPaths.clear()
        businessCalls.clear()
        val anonymous = send(port, HttpMethod.GET, "/crate-api/healthcare/v1/nonexistent-xyz")
        assertEquals(401, anonymous.status, "未认证时未知路径也是 401，不泄露路由是否存在")
        assertTrue(businessCalls.isEmpty())

        sessionAuthPaths.clear()
        businessCalls.clear()
        val authenticated = send(
            port,
            HttpMethod.GET,
            "/crate-api/healthcare/v1/nonexistent-xyz",
            authenticatedCookie,
        )
        assertEquals(404, authenticated.status, "已认证时未知路径仍保持原有 404 语义")
        assertTrue(businessCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. OPTIONS 预检：任何路径都不被总闸拦
    // ------------------------------------------------------------------

    @Test
    fun `OPTIONS preflight is never handed to the session authenticator`() {
        sessionAuthPaths.clear()
        businessCalls.clear()
        val probe = send(port, HttpMethod.OPTIONS, "/crate-api/healthcare/v1/patients")

        assertNotEquals(401, probe.status, "CORS 预检不得被认证总闸拦下")
        assertEquals(204, probe.status)
        assertEquals("http://localhost:4324", probe.allowOrigin)
        assertTrue(sessionAuthPaths.isEmpty(), "OPTIONS 不应触发 IdP 会话校验")
        assertTrue(businessCalls.isEmpty())

        // 连不存在的路径也一样：OPTIONS 一律放行（由后续路由决定结果，绝不 401）。
        sessionAuthPaths.clear()
        val unmatched = send(port, HttpMethod.OPTIONS, "/crate-api/healthcare/v1/nonexistent-xyz")
        assertNotEquals(401, unmatched.status)
        assertTrue(sessionAuthPaths.isEmpty())
    }

    // ------------------------------------------------------------------
    // 6. Identity 代理子树放行（否则无法登录），/shared/v1/* 默认纳入认证
    // ------------------------------------------------------------------

    @Test
    fun `identity proxy subtree is public so the login chain keeps working`() {
        listOf(
            HttpMethod.GET to "/crate-api/identity/v1/session",
            HttpMethod.POST to "/crate-api/identity/v1/login",
        ).forEach { (method, path) ->
            sessionAuthPaths.clear()
            businessCalls.clear()

            val probe = send(port, method, path)

            assertEquals(200, probe.status, "$method $path 必须放行，实际 body=${probe.body}")
            assertTrue(sessionAuthPaths.isEmpty(), "$path 是登录链路，不得要求会话")
            assertEquals(1, businessCalls.size, "$path 应到达代理处理器")
        }
    }

    @Test
    fun `shared proxy subtree is protected by default`() {
        sessionAuthPaths.clear()
        businessCalls.clear()

        val probe = send(port, HttpMethod.GET, "/crate-api/shared/v1/settings")

        // 计划决策 3：/shared/v1/* 默认 fail closed。若实测发现匿名依赖，
        // 必须由调度者决定以显式例外的形式加入白名单，而不是在这里默默放行。
        assertEquals(401, probe.status)
        assertTrue(businessCalls.isEmpty(), "未认证不得访问 Nexus 代理")
        assertEquals(listOf("/crate-api/shared/v1/settings"), sessionAuthPaths)
    }

    // ------------------------------------------------------------------
    // 7. 注册顺序护栏：总闸必须排在模块子路由之前
    // ------------------------------------------------------------------

    @Test
    fun `a gate registered after the module sub routers would leak unauthenticated traffic`() {
        // 对照路由器：与生产同构，唯一区别是把总闸挪到所有子路由之后。
        val controlPort = startServer(buildProductionShapedRouter(vertx, gateBeforeSubRouters = false))
        sessionAuthPaths.clear()
        businessCalls.clear()

        val probe = send(controlPort, HttpMethod.GET, "/crate-api/healthcare/v1/patients")

        assertNotEquals(401, probe.status, "对照路由器本应漏流量；若这里 401 说明顺序不再重要，护栏理由需重审")
        assertEquals(200, probe.status)
        assertTrue(
            businessCalls.contains("healthcare:GET /patients"),
            "总闸排在子路由之后时未认证请求会直接进入业务处理器：$businessCalls",
        )
    }

    // ------------------------------------------------------------------
    // 路由装配与请求工具
    // ------------------------------------------------------------------

    /**
     * 与 `Main.kt` 的 `apiRouter` 同构：相同的挂载点、相同的挂载顺序、
     * 相同的 `mainRouter` + `/crate-api` 前缀 `subRouter` 挂法。
     */
    private fun buildProductionShapedRouter(vertx: Vertx, gateBeforeSubRouters: Boolean): Router {
        val apiRouter = Router.router(vertx)
        val gate = apiAuthenticationGate(sessionAuth)
        if (gateBeforeSubRouters) {
            apiRouter.route().handler(gate)
        }

        apiRouter.route("/identity/v1/*").subRouter(identityRouter(vertx))
        apiRouter.route("/shared/v1/*").subRouter(sharedRouter(vertx))
        apiRouter.route("/inventories/v1/*").subRouter(inventoriesRouter(vertx))
        apiRouter.route("/healthcare/v1/*").subRouter(healthcareRouter(vertx))
        apiRouter.route("/nursing/v1/*").subRouter(nursingRouter(vertx))
        apiRouter.route("/dining/v1/*").subRouter(diningRouter(vertx))
        apiRouter.route("/pharmacy/v1/*").subRouter(pharmacyRouter(vertx))

        if (!gateBeforeSubRouters) {
            apiRouter.route().handler(gate)
        }

        val mainRouter = Router.router(vertx)
        mainRouter.route("/crate-api/*").subRouter(apiRouter)
        return mainRouter
    }

    private fun businessRouter(vertx: Vertx, vararg routes: Triple<HttpMethod, String, String>): Router {
        val router = Router.router(vertx)
        routes.forEach { (method, path, tag) ->
            router.route(method, path).handler { ctx ->
                businessCalls += tag
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("handler", tag).encode())
            }
        }
        return router
    }

    private fun identityRouter(vertx: Vertx): Router =
        businessRouter(
            vertx,
            Triple(HttpMethod.GET, "/session", "identity:GET /session"),
            Triple(HttpMethod.POST, "/login", "identity:POST /login"),
        )

    private fun sharedRouter(vertx: Vertx): Router =
        businessRouter(vertx, Triple(HttpMethod.GET, "/settings", "shared:GET /settings"))

    private fun healthcareRouter(vertx: Vertx): Router {
        val router = businessRouter(
            vertx,
            Triple(HttpMethod.GET, "/health", "healthcare:GET /health"),
            Triple(HttpMethod.GET, "/patients", "healthcare:GET /patients"),
            Triple(
                HttpMethod.PATCH,
                "/encounters/:id/death",
                "healthcare:PATCH /encounters/:id/death",
            ),
        )
        // 模拟 mainRouter 上的 CorsHandler：预检由业务侧应答 204 + CORS 头。
        router.options("/patients").handler { ctx ->
            ctx.response()
                .setStatusCode(204)
                .putHeader("Access-Control-Allow-Origin", "http://localhost:4324")
                .end()
        }
        return router
    }

    private fun nursingRouter(vertx: Vertx): Router {
        val router = businessRouter(
            vertx,
            Triple(HttpMethod.GET, "/health", "nursing:GET /health"),
            Triple(HttpMethod.GET, "/timeline", "nursing:GET /timeline"),
        )
        // 与 NursingRoutes 一致：/executions/* 再挂一层子路由。
        router.route("/executions/*").subRouter(
            businessRouter(
                vertx,
                Triple(
                    HttpMethod.PATCH,
                    "/:id/status",
                    "nursing:PATCH /executions/:id/status",
                ),
            ),
        )
        return router
    }

    private fun diningRouter(vertx: Vertx): Router =
        businessRouter(
            vertx,
            Triple(HttpMethod.GET, "/health", "dining:GET /health"),
            Triple(HttpMethod.GET, "/dishes", "dining:GET /dishes"),
            Triple(HttpMethod.POST, "/rosters/generate", "dining:POST /rosters/generate"),
        )

    private fun pharmacyRouter(vertx: Vertx): Router {
        val router = businessRouter(vertx, Triple(HttpMethod.GET, "/health", "pharmacy:GET /health"))
        // 与 PharmacyRoutes 一致：/dispenses*（无尾斜杠也匹配）再挂一层子路由。
        val dispenses = businessRouter(
            vertx,
            Triple(HttpMethod.GET, "/", "pharmacy:GET /dispenses"),
            Triple(HttpMethod.POST, "/:id/confirm", "pharmacy:POST /dispenses/:id/confirm"),
        )
        router.route("/dispenses*").subRouter(dispenses)
        return router
    }

    private fun inventoriesRouter(vertx: Vertx): Router {
        val router = businessRouter(
            vertx,
            Triple(HttpMethod.GET, "/health", "inventories:GET /health"),
            Triple(HttpMethod.POST, "/operations/inbound", "inventories:POST /operations/inbound"),
        )
        // 与 InventoriesRoutes 一致：/materials*（无尾斜杠也匹配）再挂一层子路由。
        val materials = businessRouter(vertx, Triple(HttpMethod.GET, "/", "inventories:GET /materials"))
        router.route("/materials*").subRouter(materials)
        return router
    }

    private fun startServer(router: Router): Int =
        vertx
            .createHttpServer()
            .requestHandler(router)
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort()

    /**
     * 单次请求：**在事件循环上用 `compose` 串起**「建请求 → 收响应头 → 读响应体」，
     * 只在最后一次性 `await`。关键点是 body handler 的注册时机：
     *
     * 旧形态把 `request.send()`（在响应头到达时完成）与 `response.body()` 分成两次
     * `get()`，中间夹着测试线程的唤醒与调度。事件循环在测试线程醒来之前就可能把响应体
     * 投递完（此时没有 body handler，数据被丢弃）或把响应结束掉，于是 `body()` 要么
     * 立刻返回**空 Buffer**，要么**永不完成**——这正是历史 flake 的两种表现
     * （`expected: <{"error":"authentication required"}> but was: <>` 与「读响应体超时」）。
     *
     * `compose` 的续体在响应头所在的**事件循环线程内联执行**（Vert.x `FutureImpl`
     * 在当前 context 上直接回调，不做线程切换），所以 body handler 一定在后续
     * `HttpContent` 消息被处理之前注册，与机器负载、测试线程调度无关。
     *
     * 实测（临时诊断类，300/50 次循环，最小 401 路由器）：
     *   - 旧形态、keepAlive=false、不 sleep：300 次里 84 次空 body；
     *   - 旧形态、keepAlive=false、sleep 30ms：50/50 读超时；
     *   - 旧形态、keepAlive=true：300 次里 51 次空 body + 50 次超时（排除 keep-alive 因素）；
     *   - `compose` 串联、keepAlive=false：300 次 0 失败；测试线程额外 sleep 5ms：300 次 0 失败。
     */
    private fun send(port: Int, method: HttpMethod, path: String, cookie: String? = null): Probe {
        val probe = client
            .request(method, port, "127.0.0.1", path)
            .compose { request ->
                if (cookie != null) request.putHeader("Cookie", cookie)
                request.send()
            }
            .compose { response ->
                if (response.statusCode() == 204) {
                    // 204 无响应体：Vert.x 客户端对 204 的 body() 不会完成，直接跳过读取。
                    io.vertx.core.Future.succeededFuture(
                        Probe(response.statusCode(), "", response.getHeader("Access-Control-Allow-Origin")),
                    )
                } else {
                    response.body().map { buffer ->
                        Probe(
                            response.statusCode(),
                            buffer.toString(),
                            response.getHeader("Access-Control-Allow-Origin"),
                        )
                    }
                }
            }
            .toCompletionStage()
            .toCompletableFuture()

        return await("$method $path 请求（含响应体）", port, method, path) { probe }
    }

    /**
     * 读响应体必须**在事件循环上一次性串联**（见 [send]），不能在测试线程上把
     * 「等响应头」与「读响应体」拆成两段 await：`send()` 的 future 在**响应头**到达时
     * 就完成，测试线程被唤醒前，事件循环可能已经把响应体投递完（无 handler → 丢弃），
     * 或已经把响应结束掉；此后才注册 body handler 就会拿到**空 body**（401 响应体断言
     * 变成 `<>`）或**永不完成**（10s 读超时）。这是测试自身的客户端用法问题，不是产品
     * 缺陷：同一个最小 401 路由器在 `ZZScratchResponseBodyRaceTest`（诊断用，已删）里
     * 旧形态 300 次出现 84 次空 body，串联形态 600 次 0 失败。
     *
     * 超时 30s 只是兜底：真因已按上述方式消除，放宽超时不再掩盖竞态。
     */
    private fun <T> await(stage: String, port: Int, method: HttpMethod, path: String, block: () -> java.util.concurrent.CompletableFuture<T>): T =
        try {
            block().get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("$stage 超时（port=$port）", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            // 保留阶段信息：连接/协议错误不能只抛裸 ExecutionException。
            throw AssertionError("$stage 失败（port=$port）：${e.cause}", e.cause ?: e)
        }
}
