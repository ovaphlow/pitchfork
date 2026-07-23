-- name: ListRoleCodesBySubjectID :many
SELECT role.role_code
FROM identity_subject_roles AS subject_role
JOIN identity_roles AS role ON role.id=subject_role.role_id
WHERE subject_role.subject_id=?
ORDER BY role.role_code;

-- name: CountEnabledSubjectsByRoleCodeExcludingSubjectID :one
SELECT COUNT(*)
FROM identity_subjects AS subject
JOIN identity_subject_roles AS subject_role ON subject_role.subject_id=subject.id
JOIN identity_roles AS role ON role.id=subject_role.role_id
WHERE subject.status=? AND role.role_code=? AND subject.id<>?;
