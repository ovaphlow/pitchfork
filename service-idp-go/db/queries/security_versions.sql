-- name: GetEnabledSubjectSecurityVersion :one
SELECT security_version
FROM identity_subjects
WHERE id=? AND status=?;

-- name: IncrementEnabledSubjectSecurityVersion :execrows
UPDATE identity_subjects
SET security_version=security_version+1,updated_at=?
WHERE id=? AND status=?;
