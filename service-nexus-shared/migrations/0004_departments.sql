-- 039：部门从通用 settings KV 升格为独立实体表，成为可被外键引用的组织科室目录。
-- 注意：业务侧的 beds/encounters/pharmacy_requisitions.department 是「照护单元/病区」，
-- 与这里的组织部门不是同一概念，不做外键关联。
CREATE TABLE IF NOT EXISTS departments (
    id          TEXT PRIMARY KEY,                 -- ULID
    code        TEXT NOT NULL UNIQUE,             -- 部门编码，唯一
    name        TEXT NOT NULL,                    -- 显示名称
    description TEXT NOT NULL DEFAULT '',
    parent_code TEXT NOT NULL DEFAULT '',         -- 上级部门编码，空串表示根
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_departments_parent ON departments(parent_code);

-- 迁移既有的 category='department' 行。原 settings 行保留，
-- 供仍在读取 /settings?category=department 的冻结应用继续工作；清理单独立项。
INSERT OR IGNORE INTO departments (id, code, name, description, parent_code, sort_order, created_at, updated_at)
SELECT id,
       code,
       COALESCE(json_extract(payload, '$.name'), code),
       COALESCE(json_extract(payload, '$.description'), ''),
       parent_code,
       0,
       created_at,
       updated_at
FROM settings
WHERE category = 'department';
