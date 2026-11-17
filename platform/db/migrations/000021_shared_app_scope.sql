-- 000021_shared_app_scope.sql — 共享数据按产品隔离
--
-- 背景：platformd 同时服务 Agora / Aceso / Trainova，但 settings / messages /
-- interactions / files / roles 原先没有产品维度，任何产品都能读到其他产品的数据。
-- 本迁移新增 app_code 列；写入与读取由 shared.Handler 按请求头 X-App-Code 强制过滤。
--
-- 回填口径（迁移时点的真实数据）：
--   settings / messages / interactions / roles → 'aceso'（Aceso 时期的科室、角色与消息）
--   files                                    → 'agora'（现存文件均由 agora-api 上传）
-- 重建表是因为唯一键要带上 app_code；SQLite 无法原地修改 UNIQUE 约束。
-- 新表不设 app_code 默认值，写入一律由 handler 显式提供。

CREATE TABLE settings_new (
    id          TEXT PRIMARY KEY,
    app_code    TEXT NOT NULL,
    category    TEXT NOT NULL,
    code        TEXT NOT NULL,
    root_code   TEXT NOT NULL DEFAULT '',
    parent_code TEXT NOT NULL DEFAULT '',
    payload     TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(app_code, category, code)
);

INSERT INTO settings_new (id, app_code, category, code, root_code, parent_code, payload, created_at, updated_at)
SELECT id, 'aceso', category, code, root_code, parent_code, payload, created_at, updated_at
FROM settings;

DROP TABLE settings;
ALTER TABLE settings_new RENAME TO settings;

CREATE INDEX idx_settings_app_category ON settings(app_code, category);
CREATE INDEX idx_settings_category ON settings(category);

CREATE TABLE messages_new (
    id            TEXT PRIMARY KEY,
    app_code      TEXT NOT NULL,
    message_type  TEXT NOT NULL,
    sender_id     TEXT NOT NULL,
    sender_type   TEXT NOT NULL,
    receiver_id   TEXT NOT NULL,
    receiver_type TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'unread',
    payload       TEXT NOT NULL DEFAULT '{}',
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

INSERT INTO messages_new (
    id, app_code, message_type, sender_id, sender_type, receiver_id, receiver_type,
    status, payload, created_at, updated_at
)
SELECT id, 'aceso', message_type, sender_id, sender_type, receiver_id, receiver_type,
       status, payload, created_at, updated_at
FROM messages;

DROP TABLE messages;
ALTER TABLE messages_new RENAME TO messages;

CREATE INDEX idx_messages_app_receiver ON messages(app_code, receiver_id, receiver_type);
CREATE INDEX idx_messages_app_sender ON messages(app_code, sender_id, sender_type);
CREATE INDEX idx_messages_app_message_type ON messages(app_code, message_type);
CREATE INDEX idx_messages_app_status ON messages(app_code, status);
CREATE INDEX idx_messages_app_created_at ON messages(app_code, created_at);

CREATE TABLE interactions_new (
    id               TEXT PRIMARY KEY,
    app_code         TEXT NOT NULL,
    actor_id         TEXT NOT NULL,
    target_type      TEXT NOT NULL,
    target_id        TEXT NOT NULL,
    interaction_type TEXT NOT NULL,
    value            REAL,
    payload          TEXT NOT NULL DEFAULT '{}',
    created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(app_code, actor_id, target_type, target_id, interaction_type)
);

INSERT INTO interactions_new (
    id, app_code, actor_id, target_type, target_id, interaction_type, value, payload,
    created_at, updated_at
)
SELECT id, 'aceso', actor_id, target_type, target_id, interaction_type, value, payload,
       created_at, updated_at
FROM interactions;

DROP TABLE interactions;
ALTER TABLE interactions_new RENAME TO interactions;

CREATE INDEX idx_interactions_app_target ON interactions(app_code, target_type, target_id);
CREATE INDEX idx_interactions_app_actor ON interactions(app_code, actor_id);

CREATE TABLE files_new (
    id            TEXT PRIMARY KEY,
    app_code      TEXT NOT NULL,
    original_name TEXT NOT NULL,
    stored_name   TEXT NOT NULL UNIQUE,
    mime_type     TEXT NOT NULL,
    size_bytes    INTEGER NOT NULL CHECK (size_bytes >= 0),
    storage_path  TEXT NOT NULL,
    hash_sha256   TEXT NOT NULL,
    uploaded_by   TEXT NOT NULL,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

INSERT INTO files_new (
    id, app_code, original_name, stored_name, mime_type, size_bytes, storage_path,
    hash_sha256, uploaded_by, created_at
)
SELECT id, 'agora', original_name, stored_name, mime_type, size_bytes, storage_path,
       hash_sha256, uploaded_by, created_at
FROM files;

DROP TABLE files;
ALTER TABLE files_new RENAME TO files;

CREATE INDEX idx_files_app_uploaded_by ON files(app_code, uploaded_by);
CREATE INDEX idx_files_app_created_at ON files(app_code, created_at);
CREATE INDEX idx_files_hash ON files(hash_sha256);

-- roles 只加列：role_code 仍全局唯一，产品角色用 `<app>.` 前缀区分，避免重建被
-- subject_roles 外键引用的表。旧角色（nursing.staff 等）归 Aceso。
ALTER TABLE roles ADD COLUMN app_code TEXT NOT NULL DEFAULT 'aceso';
CREATE INDEX idx_roles_app_code ON roles(app_code);
