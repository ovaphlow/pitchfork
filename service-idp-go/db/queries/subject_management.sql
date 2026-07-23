-- name: CountSubjectsForManagement :one
SELECT COUNT(*)
FROM identity_subjects;

-- name: ListSubjectsForManagement :many
SELECT s.id,s.status,s.security_version,p.display_name,i.identifier_value,s.created_at,s.updated_at
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id=s.id
JOIN identity_identifiers i ON i.subject_id=s.id
WHERE i.identifier_usage=?
ORDER BY s.created_at DESC
LIMIT ? OFFSET ?;

-- name: GetSubjectForManagement :one
SELECT s.id,s.status,s.security_version,p.display_name,i.identifier_value,s.created_at,s.updated_at
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id=s.id
JOIN identity_identifiers i ON i.subject_id=s.id
WHERE s.id=? AND i.identifier_usage=?;

-- name: GetIdentifierSubjectID :one
SELECT subject_id
FROM identity_identifiers
WHERE identifier_type=? AND normalized_value=?;

-- name: DisableSubject :execrows
UPDATE identity_subjects
SET status=?,security_version=security_version+1,disabled_at=?,updated_at=?
WHERE id=? AND status=?;
