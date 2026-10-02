# Subject Roles 模块

## 概述

Subject Roles 模块负责"用户 ↔ 角色"分配：把 IDP 主体（`identity_subjects.id`）与本地角色目录
（`roles.role_code`）关联起来。角色目录回答"有哪些角色"，本模块回答"谁拥有哪些角色"。

分配是**集合语义**：写入使用全量替换，不做单条增删接口。`subject_id` 是跨服务引用（IDP 的主体
ULID），Nexus 只校验其 ULID 形态，不做存在性探活；角色侧则是同库外键，无法分配目录中不存在的角色。

## SQLite 表结构

```sql
CREATE TABLE subject_roles (
    id                    TEXT PRIMARY KEY,            -- ULID
    subject_id            TEXT NOT NULL,               -- IDP 主体 ULID
    role_id               TEXT NOT NULL
        REFERENCES roles(id) ON DELETE RESTRICT,
    granted_by_subject_id TEXT NOT NULL DEFAULT '',    -- 操作者主体 ULID
    created_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(subject_id, role_id)
);
```

`UNIQUE(subject_id, role_id)` 保证同一用户同一角色只有一行；`ON DELETE RESTRICT` 让"删除仍被分配
的角色"在数据库层失败（HTTP 层映射为 `409`）。全量替换按 `subject_id` 删除后重插，`granted_by_subject_id`
记录当次操作者。

## HTTP API

所有端点以 `/crate-api/shared/v1/subject-roles` 为根路径，并要求 IDP 的 `完整` 会话。

| HTTP 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/?subject_ids=<ulid,ulid>` | 返回分配列表，按 `subject_id`、`role_code` 升序；省略 `subject_ids` 时返回全部（仍受 `page`/`page_size` 分页，默认 20） |
| PUT | `/subjects/:subjectID` | 全量替换该主体的角色集合，请求体 `{"role_codes":["nursing.staff"]}`；空数组表示清空 |
| GET | `/subject-permissions?subject_id=<ulid>` | 该主体的有效权限：`{subject_id, role_codes, permission_codes, source}`；`permission_codes` 为各角色权限码的去重并集，无角色时两个数组为空 |

列表直接返回 JSON 数组，元素含 `subject_id`、`role_id`、`role_code`、`role_display_name`、
`granted_by_subject_id` 与时间戳。所有错误均为 RFC 9457 Problem Details：`subject_ids` 含非法 ULID、
`subjectID` 非 ULID、`role_codes` 含目录中不存在的编码均为 `400`；无会话 `401`；删除被分配的角色 `409`。

`subject-permissions` 挂在 `/crate-api/shared/v1/subject-permissions`（与 `subject-roles` 同级，
同一个模块内实现）。它是**产品后端做接口准入的取值入口**：一次调用即可拿到该主体当前可用的权限码，
不必自己 join 角色与权限码。调用方需带用户会话 cookie（与其他模块一致）。

## 已知边界

- 不校验 `subject_id` 在 IDP 中是否存在（避免 Nexus 依赖 IDP 管理面路由）。
- 分配变更不写审计事件，仅保留 `granted_by_subject_id` 与时间戳。
- 角色码尚无产品归属字段，各产品的角色（`nursing.*`、`pharmacy.*`）共用同一目录。

## 测试

`tests/subject_roles.rs` 使用内存 SQLite + 认证桩覆盖：分配/去重/排序、按主体过滤、全量替换与清空、
未知角色码 `400`、非法 ULID `400`、缺少 `role_codes` 不清空既有分配、已分配角色删除 `409` 与解除后 `204`。
