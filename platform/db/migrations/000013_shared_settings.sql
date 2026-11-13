-- 共享 Settings（迁自 service-nexus-shared/migrations/0001_initial.sql）。
-- category + code 唯一；payload 为 JSON 文本。
CREATE TABLE IF NOT EXISTS settings (
    id          TEXT PRIMARY KEY,
    category    TEXT NOT NULL,
    code        TEXT NOT NULL,
    root_code   TEXT NOT NULL DEFAULT '',
    parent_code TEXT NOT NULL DEFAULT '',
    payload     TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(category, code)
);

CREATE INDEX IF NOT EXISTS idx_settings_category ON settings(category);
