-- 用户 ↔ 角色分配（迁自 service-nexus-shared/migrations/0003_subject_roles.sql）。
-- subject_id 是 platformd identity_subjects.id 的跨表引用（不建外键）；role_id 指向本地角色目录。
CREATE TABLE IF NOT EXISTS subject_roles (
    id                    TEXT PRIMARY KEY,
    subject_id            TEXT NOT NULL,
    role_id               TEXT NOT NULL
        REFERENCES roles(id) ON DELETE RESTRICT,
    granted_by_subject_id TEXT NOT NULL DEFAULT '',
    created_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(subject_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_subject_roles_subject ON subject_roles(subject_id);
CREATE INDEX IF NOT EXISTS idx_subject_roles_role ON subject_roles(role_id);
