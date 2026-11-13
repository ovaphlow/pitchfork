-- 共享 Files 元数据（迁自 service-nexus-shared/migrations/0001_initial.sql）。
-- 文件实体存放在 PLATFORMD_FILES_DIR，stored_name 为服务端 ULID。
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
