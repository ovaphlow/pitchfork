-- name: GetSubjectByID :one
SELECT
    id,
    status,
    security_version,
    disabled_at,
    metadata,
    created_at,
    updated_at
FROM identity_subjects
WHERE id = ?;

-- name: CountSubjects :one
SELECT COUNT(*)
FROM identity_subjects;

-- name: CreateSubject :exec
INSERT INTO identity_subjects(
    id, status, security_version, disabled_at, metadata, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?);
