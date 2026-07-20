-- name: CreateSession :exec
INSERT INTO identity_sessions(
    id, subject_id, subject_security_version, token_hash, csrf_token_hash,
    session_access, authenticated_at, last_seen_at, expires_at, idle_expires_at,
    revoked_at, revoked_reason, metadata, created_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
