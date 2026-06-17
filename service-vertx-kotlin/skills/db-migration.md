# DB Migration — Flyway + jOOQ Codegen 流程

## Overview

```
Schema changes (DDL)
    ↓
Flyway migration (版本化 SQL)
    ↓
jOOQ codegen (Java 类型安全查询类)
    ↓
Service 中使用生成的 Table/Field 类
```

## Flyway Migration

### 命名规则

```
V<next-version>__<description>.sql
```

查看当前版本：
```sql
SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
```

### 幂等 DDL 写法

```sql
CREATE TABLE IF NOT EXISTS my_table (
    id    TEXT PRIMARY KEY,
    name  TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_my_table_name ON my_table(name);

-- ALTER TABLE 需用 DO 块包裹
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name='my_table' AND column_name='new_col') THEN
        ALTER TABLE my_table ADD COLUMN new_col TEXT;
    END IF;
END $$;
```

## jOOQ Codegen

更改 schema 后执行：

```bash
./gradlew :libs:database:generateJooq
```

生成的类位于 `libs/database/build/generated-sources/jooq/`。

## jOOQ Query 模式

### 类型安全查询 (推荐)

```kotlin
ctx.insertInto(MY_TABLE)
    .set(MY_TABLE.ID, id)
    .set(MY_TABLE.NAME, name)
    .set(MY_TABLE.STATUS, status)   // nullable 列省略 .set()
    .returning(MY_TABLE.ID)
    .fetchOne()
```

### 转换为 Vert.x Prepared Query

```kotlin
val query = ctx.select(MY_TABLE.ID, MY_TABLE.NAME).from(MY_TABLE).where(conditions)
pool.preparedQuery(DatabaseConfig.sql(query))
    .execute(DatabaseConfig.tuple(query))
```

### 处理未生成的列

当列在 jOOQ 生成类中不存在时：

```kotlin
DSL.field("column_name", SQLDataType.JSONB)
```

### 常见 jOOQ 陷阱

1. **Null inlining**: 当 `.values()` 中包含 null 时，jOOQ 会在 SQL 中内联 NULL 并从 `getBindValues()` 中跳过该参数，导致后续所有参数移位。解决方案：完全省略 nullable 列。
2. **10+ 列 select**: `ctx.select(c1..c10)` 类型推断会失败。使用 `ctx.select(listOf(c1..c10))` 或原始 SQL。
