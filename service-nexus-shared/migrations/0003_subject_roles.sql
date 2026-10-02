-- 用户 ↔ 角色分配。角色目录见 0002_roles.sql。
-- subject_id 是 IDP identity_subjects.id（跨服务引用，不建外键）；
-- role_id 指向本地目录，分配一个不存在的角色在数据库层就不可行。
CREATE TABLE IF NOT EXISTS subject_roles (
    id                    TEXT PRIMARY KEY,                -- ULID
    subject_id            TEXT NOT NULL,                   -- IDP 主体 ULID
    role_id               TEXT NOT NULL
        REFERENCES roles(id) ON DELETE RESTRICT,
    granted_by_subject_id TEXT NOT NULL DEFAULT '',        -- 操作者主体 ULID
    created_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at            TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(subject_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_subject_roles_subject ON subject_roles(subject_id);
CREATE INDEX IF NOT EXISTS idx_subject_roles_role ON subject_roles(role_id);
