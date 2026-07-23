-- name: RevokeActiveSessionsBySubjectID :execrows
UPDATE identity_sessions
SET revoked_at=?,revoked_reason=?
WHERE subject_id=? AND revoked_at IS NULL;
