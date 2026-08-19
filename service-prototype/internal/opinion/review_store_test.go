package opinion

import (
	"context"
	"errors"
	"testing"
	"time"
)

// ─── InMemoryStore 复盘读写 ──────────────────────────────────────────

// UpsertReview 首次插入、再次按 run_id 原地替换（不新增行）；GetReview
// 返回副本（改返回值不影响存储）；DeleteReview 删除；不存在时各自返回
// ErrReviewNotFound。
func TestInMemoryStoreUpsertGetDeleteReview(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	if _, err := store.GetReview(ctx, "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("GetReview on empty store: err = %v, want ErrReviewNotFound", err)
	}
	if err := store.DeleteReview(ctx, "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("DeleteReview on empty store: err = %v, want ErrReviewNotFound", err)
	}

	created := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	first := Review{RunID: "run-1", CaseSummary: "事件经过", Metadata: map[string]any{"k": "v"}, CreatedAt: created, UpdatedAt: created}
	if err := store.UpsertReview(ctx, first); err != nil {
		t.Fatalf("UpsertReview first: %v", err)
	}
	replacement := Review{RunID: "run-1", Highlights: "处置亮点", CreatedAt: created, UpdatedAt: created.Add(time.Hour)}
	if err := store.UpsertReview(ctx, replacement); err != nil {
		t.Fatalf("UpsertReview replacement: %v", err)
	}

	got, err := store.GetReview(ctx, "run-1")
	if err != nil {
		t.Fatalf("GetReview: %v", err)
	}
	if got.CaseSummary != "" || got.Highlights != "处置亮点" || got.Metadata == nil || len(got.Metadata) != 0 {
		t.Fatalf("review = %+v, want the replacement (case_summary reset, metadata reset to an empty object)", got)
	}

	// 返回副本：修改返回值不影响存储。
	got.Metadata = map[string]any{"mutated": true}
	again, err := store.GetReview(ctx, "run-1")
	if err != nil {
		t.Fatalf("GetReview again: %v", err)
	}
	if len(again.Metadata) != 0 {
		t.Fatalf("store was mutated through the returned copy: %+v", again.Metadata)
	}

	if err := store.DeleteReview(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteReview: %v", err)
	}
	if _, err := store.GetReview(ctx, "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("GetReview after delete: err = %v, want ErrReviewNotFound", err)
	}
}

// 按 run_id 归并：同一 run 只保留一行（最后写入的复盘）。
func TestInMemoryStoreUpsertReviewKeepsOneRowPerRun(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if err := store.UpsertReview(ctx, Review{RunID: "run-1", CaseSummary: "经过"}); err != nil {
			t.Fatalf("UpsertReview: %v", err)
		}
	}
	if len(store.reviews) != 1 {
		t.Fatalf("reviews rows = %d, want 1 (run_id UNIQUE)", len(store.reviews))
	}
}

// ─── DeleteByRun 级联清空复盘 ────────────────────────────────────────

// DeleteByRun 清空该 run 的复盘记录（与迁移 ON DELETE CASCADE 一致），
// 其他 run 的复盘保留；无复盘可清时不是错误；既有 opinion 对象（事件等）
// 的清理不受影响。
func TestInMemoryStoreDeleteByRunCleansReviews(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	if err := store.UpsertReview(ctx, Review{RunID: "run-1", CaseSummary: "A"}); err != nil {
		t.Fatalf("UpsertReview run-1: %v", err)
	}
	if err := store.UpsertReview(ctx, Review{RunID: "run-2", CaseSummary: "B"}); err != nil {
		t.Fatalf("UpsertReview run-2: %v", err)
	}
	if err := store.UpsertEvent(ctx, Event{RunID: "run-1", EventName: "E"}); err != nil {
		t.Fatalf("UpsertEvent run-1: %v", err)
	}

	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if _, err := store.GetReview(ctx, "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("GET review run-1: err = %v, want ErrReviewNotFound", err)
	}
	if review, err := store.GetReview(ctx, "run-2"); err != nil || review.CaseSummary != "B" {
		t.Fatalf("GET review run-2: review = %+v, err = %v; want the untouched review", review, err)
	}
	// 既有 opinion 对象照常清理。
	if _, err := store.GetEvent(ctx, "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GET event run-1: err = %v, want ErrEventNotFound", err)
	}
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
