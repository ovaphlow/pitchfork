-- 共享 Messages（迁自 service-nexus-shared/migrations/0001_initial.sql）。
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
