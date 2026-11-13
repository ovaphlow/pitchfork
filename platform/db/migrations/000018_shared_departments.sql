-- 共享部门目录（迁自 service-nexus-shared/migrations/0004_departments.sql）。
-- 注意：业务侧的床位/入住/申领里的 department 是照护单元/病区，与本表无外键关联。
CREATE TABLE IF NOT EXISTS departments (
    id          TEXT PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    parent_code TEXT NOT NULL DEFAULT '',
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_departments_parent ON departments(parent_code);

-- 历史镜像：把 settings 中 category='department' 的行复制为部门（原 settings 行保留）。
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
