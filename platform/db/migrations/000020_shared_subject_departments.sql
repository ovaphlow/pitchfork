-- 用户 ↔ 部门（一人一主部门，迁自 service-nexus-shared/migrations/0005_subject_departments.sql）。
-- subject_id 是 platformd identity_subjects.id 的跨表引用（不建外键）；department_id 指向本地部门目录。
CREATE TABLE IF NOT EXISTS subject_departments (
    id                    TEXT PRIMARY KEY,
    subject_id            TEXT NOT NULL UNIQUE,
    department_id         TEXT NOT NULL
        REFERENCES departments(id) ON DELETE RESTRICT,
    granted_by_subject_id TEXT NOT NULL DEFAULT '',
    created_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_subject_departments_department ON subject_departments(department_id);
