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

## jOOQ 使用规范

### 原则

**jOOQ 可用时，优先使用 jOOQ DSL，避免手写 SQL 字符串。**

jOOQ 提供了类型安全、可编译检查的数据库操作。所有已生成 jOOQ 表的模块，应当使用 jOOQ DSL 而非原始 SQL 字符串。

### 正确做法 ✅

```kotlin
// DELETE
val query = ctx.deleteFrom(t).where(t.ID.eq(id))
return pool.preparedQuery(DatabaseConfig.sql(query))
    .execute(DatabaseConfig.tuple(query))
    .map { null as Void? }

// INSERT with RETURNING
val query = ctx.insertInto(t)
    .set(t.ID, id)
    .set(t.NAME, name)
    .set(t.JSONB_COL, JSONB.valueOf(obj.encode()))
query.returning(t.ID, t.NAME, t.JSONB_COL)
return pool.preparedQuery(DatabaseConfig.sql(query))
    .execute(DatabaseConfig.tuple(query))
    .map { rows -> toJson(rows.iterator().next()) }

// UPDATE with RETURNING
val query = ctx.update(t)
    .set(t.NAME, newName)
    .set(t.UPDATED_AT, OffsetDateTime.now())
    .where(t.ID.eq(id))
query.returning(t.ID, t.NAME)
return pool.preparedQuery(DatabaseConfig.sql(query))
    .execute(DatabaseConfig.tuple(query))

// SELECT COUNT
val countQuery = ctx.select(count().`as`("total")).from(t).where(t.STATUS.eq("active"))
val countTuple = DatabaseConfig.tuple(countQuery)

// SELECT with condition
val dataQuery = ctx.select(t.ID, t.NAME)
    .from(t)
    .where(t.CATEGORY.eq(category).and(t.STATUS.ne("archived")))
    .orderBy(t.CREATED_AT.desc())
    .limit(limit).offset(offset)

// UPSERT (INSERT ... ON CONFLICT DO UPDATE)
val query = ctx.insertInto(lp)
    .set(lp.EMPLOYEE_ID, employeeId)
    .set(lp.STATUS, "completed")
    .onConflict(lp.ASSIGNMENT_ID, lp.EMPLOYEE_ID, lp.CHAPTER_ID)
    .doUpdate()
    .set(lp.STATUS, "completed")
    .set(lp.COMPLETED_AT, OffsetDateTime.now())
query.returning(lp.ID, lp.STATUS, lp.COMPLETED_AT)
```

### 需要保留原始 SQL 的情况 ⚠️

以下场景可以保留原始 SQL，但需要添加注释说明原因：

1. **动态 SET 子句** — WHERE/SET 子句的列在运行时变化（如 UPDATE 只更新非 null 字段）
2. **列名与 jOOQ 生成不匹配** — jOOQ codegen 生成的列名与数据库实际列名不一致
3. **复杂 PostgreSQL 专有语法** — `FILTER (WHERE ...)`、窗口函数等 jOOQ 原生不直接支持的语法

```kotlin
// 动态 SET 子句 — 保留原始 SQL
val setClauses = mutableListOf<String>()
if (name != null) setClauses.add("name = \${'$'}\${++idx}")
if (description != null) setClauses.add("description = \${'$'}\${++idx}")
// ...
```

### 类型映射

jOOQ 生成的列有严格的 Kotlin 类型。常见映射：

| PostgreSQL | jOOQ Kotlin | 转换方式 |
|------------|------------|---------|
| `VARCHAR`  | `String`    | 直接使用 |
| `INTEGER`  | `Int`       | `value.toShort()` 用于 `SMALLINT` |
| `SMALLINT` | `Short`     | `intValue.toShort()` |
| `DATE`     | `LocalDate` | `LocalDate.parse(string)` |
| `TIMESTAMP`| `OffsetDateTime` | `OffsetDateTime.parse(string)` |
| `JSONB`    | `JSONB`     | `JSONB.valueOf(jsonObj.encode())` |
| `BIGINT`   | `Long`      | `value.toLong()` |
| `BOOLEAN`  | `Boolean`   | 直接使用 |

### 执行方式

所有 jOOQ 查询通过 Vert.x `pool.preparedQuery()` 执行，非阻塞：

```kotlin
pool.preparedQuery(DatabaseConfig.sql(query))
    .execute(DatabaseConfig.tuple(query))
```

其中 `DatabaseConfig.sql(query)` 将 jOOQ 命名参数 (`:1`) 转为 PostgreSQL 格式 (`$1`)，
`DatabaseConfig.tuple(query)` 提取绑定值。
