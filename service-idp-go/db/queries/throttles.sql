-- name: GetLoginThrottle :one
SELECT id,identifier_hash,source_hash,failed_count,window_started_at,locked_until,updated_at
FROM identity_login_throttles
WHERE identifier_hash=? AND source_hash=?;

-- name: UpsertLoginThrottle :exec
INSERT INTO identity_login_throttles(id,identifier_hash,source_hash,failed_count,window_started_at,locked_until,updated_at)
VALUES(?,?,?,?,?,?,?)
ON CONFLICT(identifier_hash,source_hash) DO UPDATE SET
failed_count=excluded.failed_count,window_started_at=excluded.window_started_at,
locked_until=excluded.locked_until,updated_at=excluded.updated_at;

-- name: DeleteLoginThrottle :exec
DELETE FROM identity_login_throttles
WHERE identifier_hash=? AND source_hash=?;
