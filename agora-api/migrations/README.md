# agora-api 迁移

PostgreSQL 17。文件按名称字典序执行，一个文件一个事务；执行记录写入 `schema_migrations`。
迁移只由 `agora-api` 进程在启动时执行（`internal/migrate`，`AUTO_MIGRATE=false` 可关闭），
不连接 platformd 的数据库。

runner 的行为：

- 启动时用 `pg_advisory_lock` 串行化并发的多个实例，避免重复执行。
- 每个文件在一个事务内执行，失败只回滚该文件，之前的文件保持已应用。
- `schema_migrations.checksum` 记录文件内容的 SHA-256；已应用的文件被重新编辑会在启动时报错，
  必须新增一个迁移文件而不是修改历史。

## 编号规则

`NNNN_<domain>_<subject>.sql`，四位递增，不复用编号、不修改已发布文件。

| 文件 | 内容 |
|---|---|
| `0001_organizations.sql` | `organizations`、`organization_members` |
| `0002_job_positions.sql` | `job_positions` |
| `0003_organization_license.sql` | 仅更新 `organizations.metadata` 契约注释（新增 `license_file_id`） |

## 字段切分规则

留在列上只有三类：

1. 筛选、排序维度：`city`、`employment_type`、`salary_min`、`education`、`status`、`published_at`。
2. 唯一约束、外键、状态机字段：`name + city`、`organization_id`、`status`、`reviewed_by`。
3. 列表卡片直接展示、且不需要解析 JSON 的字段：`short_name`、`logo_file_id`、`title`、`headcount`。

其余展示型字段与正文内容（岗位职责、任职要求、福利、详细地址、联系方式、附件）进 `metadata` JSONB。

## metadata 契约

写入前必须由 Go 侧 struct 序列化，读时反序列化到同一 struct；禁止用 `map[string]any` 直写、禁止写入未定义键。

```jsonc
// organizations.metadata
{
  "intro": "机构简介",
  "industry": "医疗健康",
  "scale": "100-499人",
  "website": "https://example.com",
  "address": "××路 1 号",
  "credit_code": "91310000XXXXXXXXXX",
  "license_file_id": "<platformd 共享 files.id>",
  "founded_year": 2015,
  "tags": ["双休", "五险一金"],
  "contact": { "name": "", "phone": "", "email": "" }
}

// job_positions.metadata
{
  "description": "岗位职责",
  "requirements": "任职要求",
  "benefits": "五险一金、双休",
  "address": "详细地址",
  "salary_note": "13 薪 + 绩效",
  "tags": ["急招", "可远程"],
  "attachments": ["<file_id>"],
  "contact": { "name": "", "phone": "", "email": "" },
  "apply": { "method": "email", "value": "hr@example.com" }
}
```

`apply.method` 取值：`email` / `link` / `phone`。

## 后续要把 metadata 键提成列

单条 DDL，无需重写表：

```sql
ALTER TABLE job_positions
    ADD COLUMN industry TEXT GENERATED ALWAYS AS (metadata->>'industry') STORED;
CREATE INDEX idx_job_positions_industry ON job_positions (industry);
```

## 业务不变式（表结构无法表达，由应用层保证）

- 机构成员才能增删改本机构岗位；`member_role` 决定是否能改机构档案与成员。
- 机构 `status <> '已认证'` 时只能存草稿，不能进入「招聘中」。
- `status = '招聘中' AND expires_at < NOW()` 对外表现为「已过期」，不落状态。
