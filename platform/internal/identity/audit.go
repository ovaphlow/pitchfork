package identity

import (
	"context"
	"time"
)

// auditEvent 描述一条待写入的审计事件。
// 空字符串的可空字段会写成 NULL，空 Metadata 会写成 "{}"。
type auditEvent struct {
	Action          string
	Outcome         string
	ActorSubjectID  string
	TargetSubjectID string
	RequestID       string
	SourceHash      []byte
	Metadata        string
}

const insertAuditEventSQL = `
INSERT INTO identity_audit_events(
    id, event_action, outcome, actor_subject_id, target_subject_id,
    request_id, source_hash, metadata, created_at
) VALUES (
    :id, :event_action, :outcome, :actor_subject_id, :target_subject_id,
    :request_id, :source_hash, :metadata, :created_at
)`

// insertAuditEvent 是写审计事件的唯一入口，集中处理主键生成、
// 可空字段转换和 Metadata 默认值，避免每个调用点重复九个字段的赋值。
func insertAuditEvent(ctx context.Context, queries querier, event auditEvent, now time.Time) error {
	eventID, err := NewULID(now)
	if err != nil {
		return err
	}
	metadata := event.Metadata
	if metadata == "" {
		metadata = "{}"
	}
	return namedExec(ctx, queries, insertAuditEventSQL, map[string]any{
		"id":                eventID,
		"event_action":      event.Action,
		"outcome":           event.Outcome,
		"actor_subject_id":  optionalString(event.ActorSubjectID),
		"target_subject_id": optionalString(event.TargetSubjectID),
		"request_id":        optionalString(event.RequestID),
		"source_hash":       event.SourceHash,
		"metadata":          metadata,
		"created_at":        now,
	})
}

// optionalString 把空字符串映射为 NULL，非空则取地址。
func optionalString(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}
