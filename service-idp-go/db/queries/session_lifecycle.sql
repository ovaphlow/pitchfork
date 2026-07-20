-- name: TouchActiveSession :execrows
UPDATE identity_sessions
SET last_seen_at = ?,
    idle_expires_at = ?
WHERE id = ?
  AND revoked_at IS NULL;

-- name: GetActiveSessionSubjectByTokenHash :one
SELECT subject_id
FROM identity_sessions
WHERE token_hash = ?
  AND revoked_at IS NULL;

-- name: RevokeActiveSessionByTokenHash :execrows
UPDATE identity_sessions
SET revoked_at = ?,
    revoked_reason = ?
WHERE token_hash = ?
  AND revoked_at IS NULL;
