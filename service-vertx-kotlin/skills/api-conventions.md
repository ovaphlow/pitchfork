# API Conventions — 响应格式与设计约定

## 响应格式

### 分页列表

```json
{
  "records": [...],
  "meta": { "total": 123 }
}
```

- 空结果返回 `{ "records": [], "meta": { "total": 0 } }`

### 单条记录

直接返回 JSON 对象，不需要 `records` 包装。

### 错误

```json
{ "error": "<message>" }
```

状态码：
- `400` — 请求参数错误
- `401` — 未认证 / Token 过期
- `403` — 无权限
- `404` — 资源不存在
- `500` — 服务器内部错误

## ID 生成

所有表 ID 使用 ULID (26 位 Crockford Base32)：

```kotlin
val id = Ulid.generate()  // e.g., "01J8Z4Q5W6V7B8N9M0K1L2P3Q4"
```

## 时间戳

- `created_at` / `updated_at` 使用 `OffsetDateTime` (带时区)
- jOOQ 绑定：`tuple.addOffsetDateTime(p)` — 不能使用 `tuple.addString(p.toString())`

## JSONB 列

- 扩展属性列统一命名为 `metadata`，类型 `JSONB`
- 读取：`DSL.field("metadata", SQLDataType.JSONB)`

## SQL 参数转义 (Critical)

Kotlin 字符串插值与 PostgreSQL `$N` 参数冲突。**始终使用 `${'$'}`**：

```kotlin
// ✅ 正确
val sql = """UPDATE items SET status = ${'$'}1 WHERE id = ${'$'}2"""

// 动态索引
conditions.add("name = ${'$'}${idx}")
params.add(name); idx++
conditions.add("type = ${'$'}${idx}")
params.add(type); idx++
```

## 模块注册

1. 添加到 `settings.gradle.kts` 作为 `"libs:<name>"`
2. 添加 `implementation(project(":libs:<name>"))` 到 `apps/service/build.gradle.kts`
3. 路由挂载到 `Main.kt`：`apiRouter.route("/<module>/v1/*").subRouter(<Module>Routes.create(vertx, pool))`

## 中文枚举值

业务使用全中文枚举值（而非英文 code）：
- `线上/线下实操` — 课程类型
- `手动指派/自动触发` — 作业下发方式
- `单选/多选/判断/填空/看图识错` — 题目类型
- `启用/禁用` — 状态开关
- `有用/没用` — 反馈评价
