-- name: CreatePasswordCredential :exec
INSERT INTO identity_password_credentials(
    id, subject_id, password_hash, password_revision, credential_status,
    changed_at, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?);

-- name: GetPasswordCredentialBySubjectID :one
SELECT subject_id,password_hash,password_revision,credential_status
FROM identity_password_credentials
WHERE subject_id=?;

-- name: UpdatePasswordCredential :execrows
UPDATE identity_password_credentials
SET password_hash=?,credential_status=?,password_revision=password_revision+1,changed_at=?,updated_at=?
WHERE subject_id=? AND password_revision=?;
