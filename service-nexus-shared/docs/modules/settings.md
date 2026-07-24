# Settings 模块

## 概述

Settings 模块提供通用的键值配置存储，通过 `category` + `code` 组合实现分类管理。广泛用于部门、知识分类、标签等枚举型/字典型数据的存储。

## 原表结构 (PostgreSQL → 迁移参考)

> 来源: `service-vertx-kotlin/libs/settings/src/main/resources/db/migration/`

```sql
-- V100__create_settings_table.sql
CREATE TABLE IF NOT EXISTS settings (
    id          VARCHAR(32) PRIMARY KEY,
    category    VARCHAR NOT NULL,
    code        VARCHAR NOT NULL,
    root_code   VARCHAR DEFAULT ''::character varying NOT NULL,
    parent_code VARCHAR DEFAULT ''::character varying NOT NULL,
    payload     JSONB NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_settings_category ON settings(category);
CREATE INDEX IF NOT EXISTS idx_settings_category_code ON settings(category, code);

-- V101__rename_settings_time_fields.sql
ALTER TABLE settings RENAME COLUMN create_time TO created_at;
ALTER TABLE settings RENAME COLUMN update_time TO updated_at;
```

### 最终表结构

| 列名 | 类型 | 约束 | 说明 |
| ------ | ------ | ------ | ------ |
| `id` | VARCHAR(32) | PK, NOT NULL | ULID 主键 |
| `category` | VARCHAR | NOT NULL | 分类（如 `department`、`knowledge-category`、`knowledge-tag`） |
| `code` | VARCHAR | NOT NULL | 分类内唯一编码 |
| `root_code` | VARCHAR | NOT NULL, DEFAULT '' | 根节点编码（层级结构） |
| `parent_code` | VARCHAR | NOT NULL, DEFAULT '' | 父节点编码（层级结构） |
| `payload` | JSONB | NOT NULL | 实际数据负载，至少包含 `name` 字段 |
| `created_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |

### 索引

| 索引名 | 列 | 说明 |
| -------- | ----- | ------ |
| `settings_pkey` | `id` | 主键 |
| `idx_settings_category` | `category` | 按分类查询 |
| `idx_settings_category_code` | `category, code` | 分类+编码唯一查找 |

## 已使用的 Category 值

| Category | 路由路径 | 说明 | 使用方 |
| ---------- | ---------- | ------ | -------- |
| `department` | `/settings/v1/departments` | 部门管理 | aceso, trainova |
| `knowledge-category` | `/settings/v1/knowledge-categories` | 知识分类 | trainova |
| `knowledge-tag` | `/settings/v1/knowledge-tags` | 知识标签 | trainova |

## API 路由映射

原 `service-vertx-kotlin` 路由前缀: `/crate-api/settings/v1/*`

| HTTP 方法 | 路径 | 说明 |
| ----------- | ------ | ------ |
| GET | `/health` | 健康检查 |
| GET | `/departments` | 列出所有部门 |
| POST | `/departments` | 创建部门 |
| PUT | `/departments/:id` | 更新部门 |
| DELETE | `/departments/:id` | 删除部门 |
| GET | `/knowledge-categories` | 列出知识分类 |
| POST | `/knowledge-categories` | 创建知识分类 |
| PUT | `/knowledge-categories/:code` | 更新知识分类 |
| DELETE | `/knowledge-categories/:code` | 删除知识分类 |
| GET | `/knowledge-tags` | 列出知识标签 |
| POST | `/knowledge-tags` | 创建知识标签 |
| DELETE | `/knowledge-tags/:code` | 删除知识标签 |

## Nexus 迁移要点

根据 CONSTITUTION.md，Settings 在 Nexus 中：

- **API 根路径**: `/crate-api/shared/v1/settings`
- **数据库**: SQLite（WAL 模式）
- **技术栈**: Rust + Axum
- **迁移**: 使用 `sqlx migrate`，脚本放 `/migrations`

### SQLite 建表建议

```sql
CREATE TABLE IF NOT EXISTS settings (
    id          TEXT PRIMARY KEY,
    category    TEXT NOT NULL,
    code        TEXT NOT NULL,
    root_code   TEXT NOT NULL DEFAULT '',
    parent_code TEXT NOT NULL DEFAULT '',
    payload     TEXT NOT NULL,  -- JSON stored as TEXT
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_settings_category ON settings(category);
CREATE UNIQUE INDEX IF NOT EXISTS idx_settings_category_code ON settings(category, code);
```
