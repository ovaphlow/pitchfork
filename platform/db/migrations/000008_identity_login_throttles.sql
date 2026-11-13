CREATE TABLE identity_login_throttles (
    id TEXT PRIMARY KEY CHECK(length(id) = 26),
    identifier_hash BLOB NOT NULL CHECK(length(identifier_hash) = 32),
    source_hash BLOB NOT NULL CHECK(length(source_hash) = 32),
    failed_count INTEGER NOT NULL CHECK(failed_count >= 0),
    window_started_at DATETIME NOT NULL,
    locked_until DATETIME,
    updated_at DATETIME NOT NULL,
    UNIQUE(identifier_hash, source_hash)
);
