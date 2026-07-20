-- name: InsertAuditEvent :exec
INSERT INTO identity_audit_events(
    id, event_action, outcome, actor_subject_id, target_subject_id,
    request_id, source_hash, metadata, created_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
