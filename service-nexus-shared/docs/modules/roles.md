# Roles 模块

## 概述

Roles 模块提供角色目录管理：角色编码、显示名称、描述与权限码集合。角色目录是共享字典能力，
供各产品消费；"用户 ↔ 角色"分配仍保留在 IDP 的 `identity_subject_roles` 表，本期不做同步。
Nexus 角色目录与 IDP 角色在语义上可并存。

## SQLite 表结构

```sql
CREATE TABLE roles (
    id                TEXT PRIMARY KEY,             -- ULID
    role_code         TEXT NOT NULL UNIQUE,         -- 角色编码，唯一
    display_name      TEXT NOT NULL,                -- 显示名称，必填
    description       TEXT NOT NULL DEFAULT '',     -- 描述，可选
    permission_codes  TEXT NOT NULL DEFAULT '[]',   -- JSON 字符串数组
    created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
```

`role_code` 仅允许小写字母、数字与点（如 `nursing.staff`、`pharmacy.manager`），长度 1..64，
创建后不可变。`permission_codes` 存储为 JSON 字符串数组，服务端去重并按首次出现顺序写入。

## HTTP API

所有端点以 `/crate-api/shared/v1/roles` 为根路径，并要求 IDP 的 `完整` 会话。

| HTTP 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 列表，支持 `page`、`page_size`，按 `role_code` 升序 |
| POST | `/` | 创建角色（201） |
| GET | `/:id` | 查询单条角色 |
| PUT | `/:id` | 完整替换 `display_name`/`description`/`permission_codes` |
| DELETE | `/:id` | 物理删除角色（204） |

列表直接返回 JSON 数组；单条返回对象。所有错误均为 RFC 9457 Problem Details：请求体非法 `400`、
无会话 `401`、不存在 `404`、`role_code` 唯一冲突 `409`。删除为字典管理，不做级联或引用检查。