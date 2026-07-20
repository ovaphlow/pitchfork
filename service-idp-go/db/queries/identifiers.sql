-- name: CreateIdentifier :exec
INSERT INTO identity_identifiers(
    id, subject_id, identifier_type, identifier_value, normalized_value,
    identifier_usage, status, verified_at, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
