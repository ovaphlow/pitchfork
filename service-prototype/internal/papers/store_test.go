package papers

import (
	"context"
	"testing"
	"time"
)

// TestListSortsByCreatedAtDescThenIDDesc pins the listing order:
// created_at DESC, ties broken by id DESC. The store sorts a copy, so
// the insertion order must not matter.
func TestListSortsByCreatedAtDescThenIDDesc(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Date(2026, 8, 2, 10, 0, 0, 0, time.UTC)
	papers := []Paper{
		{ID: "p-old", Title: "最早", CreatedAt: base},
		{ID: "p-mid-b", Title: "中间乙", CreatedAt: base.Add(2 * time.Millisecond)},
		{ID: "p-new", Title: "最新", CreatedAt: base.Add(3 * time.Millisecond)},
		{ID: "p-mid-a", Title: "中间甲", CreatedAt: base.Add(2 * time.Millisecond)},
		{ID: "p-other", Title: "同刻早", CreatedAt: base},
	}
	for _, paper := range papers {
		if err := store.Create(ctx, paper); err != nil {
			t.Fatalf("create %s: %v", paper.ID, err)
		}
	}

	records, total, err := store.List(ctx, Filter{Limit: 50})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 5 {
		t.Fatalf("total = %d, want 5", total)
	}
	// created_at DESC; the two mid papers share a timestamp and must be
	// ordered by id DESC (p-mid-b before p-mid-a); the two base papers
	// likewise (p-other before p-old).
	want := []string{"p-new", "p-mid-b", "p-mid-a", "p-other", "p-old"}
	if len(records) != len(want) {
		t.Fatalf("records = %d, want %d", len(records), len(want))
	}
	for i, id := range want {
		if records[i].ID != id {
			t.Fatalf("records[%d].id = %q, want %q (order %v)", i, records[i].ID, id, want)
		}
	}
}

// TestListPagination verifies limit/offset slicing happens after sorting
// and keeps the total before pagination.
func TestListPagination(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Date(2026, 8, 2, 10, 0, 0, 0, time.UTC)
	for i := 0; i < 5; i++ {
		paper := Paper{ID: string(rune('a' + i)), Title: "试卷", CreatedAt: base.Add(time.Duration(i) * time.Millisecond)}
		if err := store.Create(ctx, paper); err != nil {
			t.Fatalf("create: %v", err)
		}
	}

	records, total, err := store.List(ctx, Filter{Limit: 2, Offset: 0})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(records) != 2 || total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 5", len(records), total)
	}
	// Newest first: ids e and d.
	if records[0].ID != "e" || records[1].ID != "d" {
		t.Fatalf("first page ids = %q %q, want e d", records[0].ID, records[1].ID)
	}

	records, total, err = store.List(ctx, Filter{Limit: 2, Offset: 4})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(records) != 1 || total != 5 || records[0].ID != "a" {
		t.Fatalf("limit=2 offset=4: records = %v, total = %d; want [a] / 5", ids(records), total)
	}

	records, total, err = store.List(ctx, Filter{Limit: 2, Offset: 10})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(records) != 0 || total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d; want 0 / 5", len(records), total)
	}
}

// TestStoreGetUpdateDelete pins the item operations: Get/Update/Delete
// on an unknown id answer ErrNotFound, Update replaces the record.
func TestStoreGetUpdateDelete(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	paper := Paper{ID: "p-1", Title: "试卷", CreatedAt: time.Now(), UpdatedAt: time.Now()}
	if err := store.Create(ctx, paper); err != nil {
		t.Fatalf("create: %v", err)
	}

	got, err := store.Get(ctx, "p-1")
	if err != nil || got.ID != "p-1" {
		t.Fatalf("get: %+v, err = %v", got, err)
	}
	if _, err := store.Get(ctx, "missing"); err != ErrNotFound {
		t.Fatalf("get missing: err = %v, want ErrNotFound", err)
	}

	paper.Title = "更新后"
	if err := store.Update(ctx, paper); err != nil {
		t.Fatalf("update: %v", err)
	}
	got, _ = store.Get(ctx, "p-1")
	if got.Title != "更新后" {
		t.Fatalf("get after update: title = %q, want 更新后", got.Title)
	}
	if err := store.Update(ctx, Paper{ID: "missing"}); err != ErrNotFound {
		t.Fatalf("update missing: err = %v, want ErrNotFound", err)
	}

	if err := store.Delete(ctx, "p-1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := store.Get(ctx, "p-1"); err != ErrNotFound {
		t.Fatalf("get after delete: err = %v, want ErrNotFound", err)
	}
	if err := store.Delete(ctx, "missing"); err != ErrNotFound {
		t.Fatalf("delete missing: err = %v", err)
	}
}

// ids extracts the ids of the records for assertions.
func ids(records []Paper) []string {
	out := make([]string, len(records))
	for i, record := range records {
		out[i] = record.ID
	}
	return out
}
