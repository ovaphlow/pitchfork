# Module Pattern — Routes & Service 代码规范

## Overview

每个领域模块严格由两个文件组成，遵循"路由薄、服务厚"的原则。

## File Structure

```
libs/<module>/src/main/kotlin/<module>/<Name>Routes.kt   # Vert.x Router 定义
libs/<module>/src/main/kotlin/<module>/<Name>Service.kt  # 数据库操作实现
```

## Routes.kt Pattern

```kotlin
object <Name>Routes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = <Name>Service(pool)
        router.route().handler(BodyHandler.create())

        // ALWAYS include health check
        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "<module>"))
        }

        router.get("/items").handler { ctx ->
            service.list(/* params from ctx.request().getParam() */)
                .onSuccess { ctx.json(it) }
                .onFailure { respondError(ctx, it) }
        }
        return router
    }

    private fun body(ctx: RoutingContext) = ctx.body().asJsonObject() ?: JsonObject()
    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }
    private fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("module route error", err)
        ctx.response().setStatusCode(500).end(JsonObject().put("error", "internal error").encode())
    }
}
```

## Service.kt Pattern

```kotlin
class <Name>Service(
    private val pool: Pool,
    private val ctx: DSLContext = DatabaseConfig.createDSL()
) {
    private val t = <Table>.<TABLE>  // jOOQ generated table

    fun list(limit: Int = 50, offset: Int = 0): Future<JsonObject> {
        val countQuery = ctx.select(count().`as`("total")).from(t).where(conditions)
        val dataQuery = ctx.select(t.ID, t.NAME).from(t).where(conditions)
            .orderBy(t.CREATED_AT.desc()).limit(limit).offset(offset)

        return pool.preparedQuery(DatabaseConfig.sql(countQuery))
            .execute(DatabaseConfig.tuple(countQuery))
            .flatMap { countRows ->
                val total = countRows.iterator().next().getLong("total") ?: 0L
                pool.preparedQuery(DatabaseConfig.sql(dataQuery))
                    .execute(DatabaseConfig.tuple(dataQuery))
                    .map { dataRows -> /* build { records, meta } */ }
            }
    }
}
```

## Key Rules

1. **相对路径**: 子路由内使用相对路径 (`/items`)，版本前缀 `/module/v1/*` 在 `Main.kt` 中统一设置
2. **Content-Type**: `respond()` 必须设置 `Content-Type: application/json`
3. **封装 JSON 工具函数**: 每个 Routes 对象提供 `body()` 和 `respond()` / `respondError()` 助手
4. **Health check**: 每个模块 `/health` 端点，返回 `{ status, service }`
