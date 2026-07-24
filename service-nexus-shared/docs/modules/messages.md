# Messages 模块

## 概述

Messages 模块提供站内消息/通知的存储与查询，支持消息类型区分、发送方/接收方标识、状态流转等功能。

## 原表结构 (PostgreSQL → 迁移参考)

> ⚠️ **注意**: 原 `service-vertx-kotlin` 中 `messages` 表**没有独立的 Flyway 迁移脚本**。
> 该表通过 `libs/database/jooq-config.xml` 的 jOOQ 代码生成器包含在 `public_` schema 中，
> 对应 jOOQ 生成类 `com.ovaphlow.crate.database.gen.public_.tables.Messages`。
> 以下结构从 jOOQ 生成代码及 `MessagesService.kt` 逆向推导。

### 推导表结构

| 列名 | 类型 | 约束 | 说明 |
| ------ | ------ | ------ | ------ |
| `id` | VARCHAR(32) | PK, NOT NULL | ULID 主键 |
| `message_type` | VARCHAR | NOT NULL | 消息类型 |
| `sender_id` | VARCHAR | NOT NULL | 发送方 ID |
| `sender_type` | VARCHAR | NOT NULL | 发送方类型（如 `user`、`system`） |
| `receiver_id` | VARCHAR | NOT NULL | 接收方 ID |
| `receiver_type` | VARCHAR | NOT NULL | 接收方类型（如 `user`、`department`） |
| `status` | VARCHAR | — | 消息状态（如 `unread`、`read`、`archived`） |
| `payload` | JSONB | NOT NULL DEFAULT '{}' | 消息负载（标题、内容等） |
| `created_at` | TIMESTAMPTZ | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| `updated_at` | TIMESTAMPTZ | DEFAULT CURRENT_TIMESTAMP | 更新时间 |

### 索引

从 jOOQ 生成代码推断的索引需要表结构验证。建议创建：

| 索引名 | 列 | 说明 |
| -------- | ----- | ------ |
| `messages_pkey` | `id` | 主键 |
| `idx_messages_message_type` | `message_type` | 按消息类型过滤 |
| `idx_messages_receiver` | `receiver_id, receiver_type` | 按接收方查询 |
| `idx_messages_sender` | `sender_id, sender_type` | 按发送方查询 |
| `idx_messages_status` | `status` | 按状态过滤 |
| `idx_messages_created_at` | `created_at` | 时间排序 |

## API 路由映射

原 `service-vertx-kotlin` 路由前缀: `/crate-api/messages/v1/*`

| HTTP 方法 | 路径 | 说明 |
| ----------- | ------ | ------ |
| GET | `/health` | 健康检查 |
| POST | `/messages` | 创建消息 |
| GET | `/messages` | 列表查询（支持 `message_type`, `sender_id`, `sender_type`, `receiver_id`, `receiver_type`, `status`, `limit`, `offset`） |
| GET | `/messages/:id` | 获取单条消息 |
| PUT | `/messages/:id` | 更新消息（status, payload） |
| DELETE | `/messages/:id` | 删除消息 |
| PATCH | `/messages/:id/status` | 仅更新状态 |

### 请求示例

**创建消息:**

```json
POST /messages
{
  "message_type": "system_notification",
  "sender_id": "01J8Z4Q5W6V7B8N9M0K1L2P3Q4",
  "sender_type": "system",
  "receiver_id": "01J8Z4Q5W6V7B8N9M0K1L2P3Q5",
  "receiver_type": "user",
  "payload": { "title": "系统通知", "content": "..." }
}
```

**更新状态:**

```json
PATCH /messages/:id/status
{
  "status": "read"
}
```

## Nexus 迁移要点

根据 CONSTITUTION.md，Messages 在 Nexus 中：

- **API 根路径**: `/crate-api/shared/v1/messages`
- **数据库**: SQLite（WAL 模式）
- **技术栈**: Rust + Axum
- **迁移**: 使用 `sqlx migrate`，脚本放 `/migrations`

### SQLite 建表建议

```sql
CREATE TABLE IF NOT EXISTS messages (
    id            TEXT PRIMARY KEY,
    message_type  TEXT NOT NULL,
    sender_id     TEXT NOT NULL,
    sender_type   TEXT NOT NULL,
    receiver_id   TEXT NOT NULL,
    receiver_type TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'unread',
    payload       TEXT NOT NULL DEFAULT '{}',  -- JSON stored as TEXT
    created_at    TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_messages_message_type ON messages(message_type);
CREATE INDEX IF NOT EXISTS idx_messages_receiver ON messages(receiver_id, receiver_type);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id, sender_type);
CREATE INDEX IF NOT EXISTS idx_messages_status ON messages(status);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(created_at);
```
