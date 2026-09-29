package com.ovaphlow.crate.aceso

import io.vertx.core.Handler
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext

/**
 * Aceso `crate-api` 路由树的**默认拒绝（fail closed）**认证总闸与最小白名单。
 *
 * 背景：各领域 lib 的路由只在 `create(...)` 里注册了 `BodyHandler`，认证靠**逐路由
 * 显式挂载**，实测漏了 155 条（含 `PATCH /encounters/:id/death`、
 * `POST /dispenses/:id/confirm` 等危险写接口；`DiningRoutes.create(...)` 甚至接收了
 * 认证参数却从未使用）。根治办法是把"必须登录"下沉到 app 编排层的兜底中间件：
 * **白名单之外的一切方法/路径都必须先通过 IdP 会话校验，新路由默认受保护。**
 *
 * ## 路径形态（实测结论，证据见 `DefaultAuthenticationTest`）
 *
 * `apiRouter` 由 `mainRouter` 以 `/crate-api` 为挂载前缀、用 `subRouter` 挂进来
 * （块注释里不写星号通配，因为 Kotlin 块注释可嵌套）。
 * Vert.x 4.5 里**只有路由匹配**用的是去掉挂载点后的相对路径；而
 * `RoutingContext.normalizedPath()`（以及 `request().path()`）在子路由里返回的仍是
 * **完整外部路径**，即 `/crate-api/healthcare/v1/patients`，而不是
 * `/healthcare/v1/patients`。`RoutingContextWrapper.normalizedPath()` 直接委托给最外层
 * 上下文的 `HttpUtils.normalizePath(request.path())`，不做挂载点裁剪。
 *
 * 因此白名单常量按**完整外部路径**书写。为免将来 Vert.x 升级改变该委托行为时白名单
 * 静默失效（那会让 `/health` 与登录链路一起 401，属"可用性事故"而非"安全放宽"），
 * 判定函数同时接受"去掉 `/crate-api` 前缀"的形态；两种形态指向同一个路由，不放宽暴露面。
 */

/** `mainRouter` 挂载 `apiRouter` 用的前缀；白名单常量以此为基准书写。 */
private const val API_MOUNT_PREFIX = "/crate-api"

/**
 * 放行的健康检查路径——**显式枚举模块**，刻意不用 `endsWith("/health")`。
 *
 * 理由：`endsWith` 会把将来任何以 `health` 结尾的业务路径一并放行（例如
 * `/patients/health`、`/health-checkups/health`），而白名单必须能逐条审计。
 * 这里只列出 `Main.kt` 实际挂载、且 lib 内确实注册了 `router.get("/health")` 的 5 个模块。
 */
private val PUBLIC_HEALTH_PATHS: Set<String> =
    setOf(
        "/inventories/v1/health",
        "/healthcare/v1/health",
        "/nursing/v1/health",
        "/dining/v1/health",
        "/pharmacy/v1/health",
    )

/**
 * 放行的 Identity 代理子树前缀。
 *
 * 理由：登录、登出与"当前会话"查询都必须经 `apiRouter` 的 identity 代理子树（挂载点
 * `/identity/v1`，星号通配）代理到 IdP，拦掉它整站都无法登录。放行的是**代理路径本身**，
 * IdP 自己的认证与限流语义不变。
 * 不带 `/identity/v1`（无尾斜杠）形态：该形态根本匹配不到子路由，放行也无意义。
 */
private const val PUBLIC_IDENTITY_PREFIX = "/identity/v1/"

/**
 * `/crate-api` 下的公共路径白名单：只有这些路径允许匿名访问。
 *
 * 注意 `shared/v1`（Nexus 代理）**不在**白名单内——计划决策 3 明确 fail closed。
 */
internal fun isPublicApiPath(path: String): Boolean {
    val relative =
        if (path.startsWith("$API_MOUNT_PREFIX/")) {
            path.substring(API_MOUNT_PREFIX.length)
        } else {
            path
        }
    return relative in PUBLIC_HEALTH_PATHS || relative.startsWith(PUBLIC_IDENTITY_PREFIX)
}

/**
 * 默认拒绝的总闸：`OPTIONS` 放行；白名单放行；其余一律先过 [sessionAuth]。
 *
 * 必须注册在任何模块子路由**之前**——Vert.x 按注册顺序匹配，注册晚了就等于没注册
 * （对照见 `DefaultAuthenticationTest` 的注册顺序护栏用例）。
 *
 * 本中间件只做"是否放行"的判定：不吞异常、不自行 `end()` 业务数据；未通过时由
 * [sessionAuth] 负责 401 或 `ctx.next()`（复用既有 `idpSessionAuthHandler` 行为）。
 */
internal fun apiAuthenticationGate(sessionAuth: Handler<RoutingContext>): Handler<RoutingContext> =
    Handler { ctx ->
        // 1. CORS 预检：mainRouter 上的 CorsHandler 已经在前，但总闸自身也必须不拦，
        //    否则跨源请求会先撞上 401。
        if (ctx.request().method() == HttpMethod.OPTIONS) {
            ctx.next()
            return@Handler
        }
        // 2. 最小白名单：各模块 health + Identity 代理子树。
        if (isPublicApiPath(ctx.normalizedPath())) {
            ctx.next()
            return@Handler
        }
        // 3. 默认拒绝：其余一切 /crate-api/* 交给 IdP 会话校验。
        sessionAuth.handle(ctx)
    }
