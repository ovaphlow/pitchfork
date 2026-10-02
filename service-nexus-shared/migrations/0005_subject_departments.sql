-- 039：用户 ↔ 部门，一人一主部门。subject_id 是 IDP identity_subjects.id（跨服务引用，不建外键）。
-- department_id 指向本地部门目录，分配一个不存在的部门在数据库层就不可行。
CREATE TABLE IF NOT EXISTS subject_departments (
    id                    TEXT PRIMARY KEY,                -- ULID
    subject_id            TEXT NOT NULL UNIQUE,            -- 一人一主部门
    department_id         TEXT NOT NULL
        REFERENCES departments(id) ON DELETE RESTRICT,
    granted_by_subject_id TEXT NOT NULL DEFAULT '',        -- 操作者主体 ULID
    created_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_subject_departments_department ON subject_departments(department_id);
