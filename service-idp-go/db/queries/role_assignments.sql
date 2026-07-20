-- name: CountSubjectRoleAssignments :one
SELECT COUNT(*)
FROM identity_subject_roles AS subject_role
JOIN identity_roles AS role ON role.id = subject_role.role_id
WHERE subject_role.subject_id = ?
  AND role.role_code = ?;
