package opinion

import (
	"context"
	"errors"
	"testing"
	"time"
)

// ─── InMemoryStore ───────────────────────────────────────────────────

// UpsertEvent 首次插入、再次按 run_id 原地替换（不新增行）；GetEvent
// 返回副本（改返回值不影响存储）；DeleteEvent 删除；不存在时各自返回
// ErrEventNotFound。
func TestInMemoryStoreUpsertGetDelete(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	if _, err := store.GetEvent(ctx, "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GetEvent on empty store: err = %v, want ErrEventNotFound", err)
	}
	if err := store.DeleteEvent(ctx, "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("DeleteEvent on empty store: err = %v, want ErrEventNotFound", err)
	}

	created := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	first := Event{RunID: "run-1", EventName: "A", Metadata: map[string]any{"k": "v"}, CreatedAt: created, UpdatedAt: created}
	if err := store.UpsertEvent(ctx, first); err != nil {
		t.Fatalf("UpsertEvent first: %v", err)
	}
	replacement := Event{RunID: "run-1", EventName: "B", CreatedAt: created, UpdatedAt: created.Add(time.Hour)}
	if err := store.UpsertEvent(ctx, replacement); err != nil {
		t.Fatalf("UpsertEvent replacement: %v", err)
	}

	got, err := store.GetEvent(ctx, "run-1")
	if err != nil {
		t.Fatalf("GetEvent: %v", err)
	}
	if got.EventName != "B" || got.Metadata == nil || len(got.Metadata) != 0 {
		t.Fatalf("event = %+v, want the replacement (metadata reset to an empty object)", got)
	}

	// 返回副本：修改返回值不影响存储。
	got.Metadata = map[string]any{"mutated": true}
	again, err := store.GetEvent(ctx, "run-1")
	if err != nil {
		t.Fatalf("GetEvent again: %v", err)
	}
	if len(again.Metadata) != 0 {
		t.Fatalf("store was mutated through the returned copy: %+v", again.Metadata)
	}

	if err := store.DeleteEvent(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteEvent: %v", err)
	}
	if _, err := store.GetEvent(ctx, "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GetEvent after delete: err = %v, want ErrEventNotFound", err)
	}
}

// 存储副本深度复制 occurred_at 指针：修改返回值指针指向的值不影响存储。
func TestInMemoryStoreClonesOccurredAt(t *testing.T) {
	store := NewInMemoryStore()
	occurredAt := time.Date(2026, 8, 1, 8, 30, 0, 0, time.UTC)
	if err := store.UpsertEvent(context.Background(), Event{RunID: "run-1", EventName: "A", OccurredAt: &occurredAt}); err != nil {
		t.Fatalf("UpsertEvent: %v", err)
	}
	got, err := store.GetEvent(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetEvent: %v", err)
	}
	*got.OccurredAt = time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)
	again, err := store.GetEvent(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetEvent again: %v", err)
	}
	if !again.OccurredAt.Equal(occurredAt) {
		t.Fatalf("occurred_at = %v, want the stored %v (must be cloned)", *again.OccurredAt, occurredAt)
	}
}

// DeleteByRun 清空该 run 的全部事件（本卡仅 opinion_events 一种对象），
// 其他 run 的事件保留；无事件可清时不是错误。
func TestInMemoryStoreDeleteByRun(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for _, runID := range []string{"run-1", "run-1", "run-2"} {
		if err := store.UpsertEvent(ctx, Event{RunID: runID, EventName: "E"}); err != nil {
			t.Fatalf("UpsertEvent(%s): %v", runID, err)
		}
	}
	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if _, err := store.GetEvent(ctx, "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GET run-1: err = %v, want ErrEventNotFound", err)
	}
	if event, err := store.GetEvent(ctx, "run-2"); err != nil || event.EventName != "E" {
		t.Fatalf("GET run-2: event = %+v, err = %v; want the untouched event", event, err)
	}
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
