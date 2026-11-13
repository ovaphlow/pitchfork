-- 共享角色目录（迁自 service-nexus-shared/migrations/0002_roles.sql）。
CREATE TABLE IF NOT EXISTS roles (
    id                TEXT PRIMARY KEY,
    role_code         TEXT NOT NULL UNIQUE,
    display_name      TEXT NOT NULL,
    description       TEXT NOT NULL DEFAULT '',
    permission_codes  TEXT NOT NULL DEFAULT '[]',
    created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_roles_role_code ON roles(role_code);
