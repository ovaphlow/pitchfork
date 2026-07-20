-- name: GetActiveSessionByTokenHash :one
SELECT id,subject_id,subject_security_version,session_access,csrf_token_hash,expires_at
FROM identity_sessions
WHERE token_hash=? AND revoked_at IS NULL AND expires_at>? AND idle_expires_at>?;
