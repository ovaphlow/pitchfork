-- 共享 Interactions（迁自 service-nexus-shared/migrations/0001_initial.sql）。
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
