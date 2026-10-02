# Departments 模块

## 概述

组织部门目录（039 从 settings KV 升格为独立表）。部门是 Nexus 的一等实体，
可被 `subject_departments` 以 `ON DELETE RESTRICT` 外键引用；
带 `parent_code` 层级与 `sort_order`。

> ⚠️ 与床位/入住/申领里的 `department`（照护单元/病区，自由文本）不是同一概念，
> 二者无外键、无联动。

## 表结构

| 列名 | 类型 | 约束 | 说明 |
| ------ | ------ | ------ | ------ |
| `id` | TEXT | PK | ULID |
| `code` | TEXT | NOT NULL, UNIQUE | 部门编码 |
| `name` | TEXT | NOT NULL | 显示名称 |
| `description` | TEXT | NOT NULL, DEFAULT '' | 描述 |
| `parent_code` | TEXT | NOT NULL, DEFAULT '' | 上级部门编码；空串为根 |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 排序 |
| `created_at` / `updated_at` | TEXT | NOT NULL | 时间戳 |

## API 路由

根路径 `/crate-api/shared/v1/departments`，需会话。

| 方法 | 路径 | 说明 |
| ------ | ------ | ------ |
| GET | `/` | 分页列出，`{ records, meta.total }`；每条带 `member_count` |
| POST | `/` | 创建；`code` 冲突 409，未知 `parent_code` 400 |
| GET | `/:id` | 单条 |
| PUT | `/:id` | 更新；`code` 创建后不可改 |
| DELETE | `/:id` | 删除；仍有成员 409 |

## 迁移

- `0004_departments.sql`：建表，并把 `settings` 中 `category='department'` 的行复制进来。
  原 settings 行保留，供仍在读取 `/settings?category=department` 的冻结应用使用。
