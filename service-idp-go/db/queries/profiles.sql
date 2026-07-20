-- name: CreateProfile :exec
INSERT INTO identity_profiles(subject_id, display_name, created_at, updated_at)
VALUES (?, ?, ?, ?);
