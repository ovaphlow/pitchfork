-- 000001_create_service_meta.sql
-- Initial prototype schema: a small metadata table the service uses for
-- schema bookkeeping. Business tables arrive with future slices.

CREATE TABLE IF NOT EXISTS service_prototype_meta (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
