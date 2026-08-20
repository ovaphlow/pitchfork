CREATE TABLE IF NOT EXISTS roles (
    id                TEXT PRIMARY KEY,                -- ULID
    role_code         TEXT NOT NULL UNIQUE,            -- 角色编码，唯一
    display_name      TEXT NOT NULL,                   -- 显示名称，必填
    description       TEXT NOT NULL DEFAULT '',        -- 描述，可选
    permission_codes  TEXT NOT NULL DEFAULT '[]',      -- JSON 字符串数组
    created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_roles_role_code ON roles(role_code);