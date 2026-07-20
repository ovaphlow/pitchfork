-- name: CreateRoleIfAbsent :exec
INSERT INTO identity_roles(id, role_code, display_name, description, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(role_code) DO NOTHING;

-- name: GetRoleIDByCode :one
SELECT id
FROM identity_roles
WHERE role_code = ?;

-- name: AssignSubjectRole :exec
INSERT INTO identity_subject_roles(id, subject_id, role_id, granted_by_subject_id, created_at)
VALUES (?, ?, ?, ?, ?);
