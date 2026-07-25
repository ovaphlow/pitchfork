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

CREATE TABLE IF NOT EXISTS messages (
    id            TEXT PRIMARY KEY,
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

CREATE INDEX IF NOT EXISTS idx_messages_receiver ON messages(receiver_id, receiver_type);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id, sender_type);
CREATE INDEX IF NOT EXISTS idx_messages_message_type ON messages(message_type);
CREATE INDEX IF NOT EXISTS idx_messages_status ON messages(status);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(created_at);

CREATE TABLE IF NOT EXISTS files (
    id            TEXT PRIMARY KEY,
    original_name TEXT NOT NULL,
    stored_name   TEXT NOT NULL UNIQUE,
    mime_type     TEXT NOT NULL,
    size_bytes    INTEGER NOT NULL CHECK (size_bytes >= 0),
    storage_path  TEXT NOT NULL,
    hash_sha256   TEXT NOT NULL,
    uploaded_by   TEXT NOT NULL,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_files_uploaded_by ON files(uploaded_by);
CREATE INDEX IF NOT EXISTS idx_files_hash ON files(hash_sha256);

CREATE TABLE IF NOT EXISTS interactions (
    id               TEXT PRIMARY KEY,
    actor_id         TEXT NOT NULL,
    target_type      TEXT NOT NULL,
    target_id        TEXT NOT NULL,
    interaction_type TEXT NOT NULL,
    value            REAL,
    payload          TEXT NOT NULL DEFAULT '{}',
    created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE(actor_id, target_type, target_id, interaction_type)
);

CREATE INDEX IF NOT EXISTS idx_interactions_target ON interactions(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_interactions_actor ON interactions(actor_id);
