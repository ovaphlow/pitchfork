-- name: CreatePasswordCredential :exec
INSERT INTO identity_password_credentials(
    id, subject_id, password_hash, password_revision, credential_status,
    changed_at, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
