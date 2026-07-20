-- name: GetEnabledSubjectSecurityVersion :one
SELECT security_version
FROM identity_subjects
WHERE id=? AND status=?;
