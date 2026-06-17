# Common Pitfalls — 常见陷阱与解决方案

## 1. JAR Staleness (JAR 过时问题)

**症状**: 代码编译通过，运行时行为与代码不一致。

**原因**: `installDist` 将 lib JAR 复制到 distribution 目录，这些 JAR 可能过时。

**解决**: lib 代码修改后始终使用 `clean` 或 `--rerun-tasks`：
```bash
./gradlew clean :apps:service:installDist --no-build-cache --rerun-tasks
```

## 2. Port in Use (端口占用)

**症状**: 服务"卡住"无法启动，无错误日志。

**解决**: 启动前确认无残留进程：
```bash
ps aux | grep '[s]ervice/bin/service' | awk '{print $2}' | xargs kill -9
```

## 3. jOOQ Null Inlining

**症状**: SQL 参数错位，报参数类型不匹配错误。

**原因**: 当 `.values()` 中包含 `null` 时，jOOQ 内联 `NULL` 并跳过 `getBindValues()`，导致后续参数移位。

**解决**: nullable 列在值为 null 时完全省略 `.set()` 调用。

## 4. `as? Int` on VARCHAR — 静默返回 null

**症状**: 从 VARCHAR 列读取数值时返回 `null`。

**解决**: 使用 `.toString().toIntOrNull()` 替代 `as? Int`。

## 5. `OffsetDateTime` 类型绑定

**症状**: `tuple.addString(p.toString())` 导致 SQL 参数类型不匹配。

**解决**: 始终使用 `tuple.addOffsetDateTime(p)`。

## 6. 10+ 列 Select 类型推断失败

**症状**: `ctx.select(c1, c2, ..., c11)` 编译错误。

**解决**: 使用 `ctx.select(listOf(c1, c2, ..., c11))` 或原始 SQL。

## 7. 路由路径混淆

**症状**: 404 / 路由不匹配。

**解决**: 子路由内使用相对路径 (`/items`)，版本前缀在 `Main.kt` 设置：`apiRouter.route("/<module>/v1/*")`。

## 8. 服务"卡住"启动

**症状**: 日志停在没有错误的位置。

**原因**: Flyway scanner 产生大量 DEBUG 日志，看起来像卡住。

**解决**: 用 `curl localhost:8421/crate-api/<module>/v1/health` 测试，不要仅靠日志判断。

## 9. ArrayList cannot be cast to JsonObject

**症状**: `b.getJsonObject(key)` 抛出 ClassCastException。

**解决**: 使用 `b.getValue(key)` + 安全类型转换：
```kotlin
val obj = b.getValue(key) as? JsonObject ?: JsonObject()
```

## 10. `column "updated_at" does not exist`

**症状**: 查询报列不存在错误。

**解决**: 不是所有表都有 `updated_at` 列。先检查 jOOQ 生成的类中是否存在该字段。

## 11. `items` 变量重命名

**症状**: 分页返回格式从 `{ items, meta }` 改为 `{ records, meta }` 后引用未更新。

**解决**: 同时搜索 `.put("items",`、`.put("records",` 以及 map 块中的 final expression 引用，确保全部更新。
