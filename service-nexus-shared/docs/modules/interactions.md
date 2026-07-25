# Interactions 模块

## 概述

Interactions 记录当前认证主体对业务目标的通用行为，可用于点赞、收藏、关注和评分。模块只存储关系与可选 JSON 负载，不拥有目标资源本身。

## SQLite 表结构

```sql
CREATE TABLE interactions (
    id               TEXT PRIMARY KEY, -- ULID
    actor_id         TEXT NOT NULL,    -- service-idp-go subject_id
    target_type      TEXT NOT NULL,
    target_id        TEXT NOT NULL,
    interaction_type TEXT NOT NULL,    -- like, favorite, follow, rate, ...
    value            REAL,
    payload          TEXT NOT NULL DEFAULT '{}',
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL,
    UNIQUE(actor_id, target_type, target_id, interaction_type)
);
```

同一主体只能对同一目标创建一种同类型交互一次；重复创建返回 `409 Conflict`。`actor_id` 始终从 IDP 已验证会话取得，客户端不能伪造。

## HTTP API

所有端点以 `/crate-api/shared/v1/interactions` 为根路径，并要求 IDP 的 `完整` 会话。

| HTTP 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 列表，支持 `page`、`page_size`、`actor_id`、`target_type`、`target_id`、`interaction_type` |
| POST | `/` | 创建当前主体的交互 |
| GET | `/:id` | 查询单条交互 |
| PUT | `/:id` | 更新当前主体自己的 `value` 或 `payload` |
| DELETE | `/:id` | 物理删除当前主体自己的交互 |

列表直接返回 JSON 数组。所有错误均为 RFC 9457 Problem Details。
