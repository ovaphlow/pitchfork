# Subject Departments 模块

## 概述

用户 ↔ 部门归属，一人一主部门。`subject_id` 是 IDP `identity_subjects.id`
（跨服务引用，只校验 ULID 形态）；`department_id` 指向本地 `departments`，
分配一个不存在的部门在数据库层就不可行。

## 表结构

| 列名 | 类型 | 约束 | 说明 |
| ------ | ------ | ------ | ------ |
| `id` | TEXT | PK | ULID |
| `subject_id` | TEXT | NOT NULL, UNIQUE | IDP 主体 ULID，一人一部门 |
| `department_id` | TEXT | NOT NULL, FK → departments(id) ON DELETE RESTRICT | 部门 |
| `granted_by_subject_id` | TEXT | NOT NULL, DEFAULT '' | 操作者主体 ULID |
| `created_at` / `updated_at` | TEXT | NOT NULL | 时间戳 |

## API 路由

根路径 `/crate-api/shared/v1/subject-departments`，需会话。

| 方法 | 路径 | 说明 |
| ------ | ------ | ------ |
| GET | `/?subject_ids=<ulid,ulid>` | `{ records, meta.total }`；非法 ULID 400 |
| PUT | `/subjects/:subject_id` | 全量替换单值；`{"department_id": null}` 清空；返回记录数组 |

## 迁移

- `0005_subject_departments.sql`：建关系表与 `department_id` 索引。
