package progress

import (
	"context"
	"testing"
	"time"
)

// 唯一键去重：同键第二次 Upsert 更新而非插入，内存断言行数不新增；
// 不同章节/员工是独立行。
func TestUpsertDeduplicatesOnUniqueKey(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	now := time.Now()
	first := Progress{
		ID: "row-1", AssignmentID: "a-1", EmployeeID: "e-1", ChapterID: "c-1",
		ProgressPercent: 40, Status: StatusLearning, Detail: map[string]any{"note": "first"},
		StartedAt: &now, CreatedAt: now, UpdatedAt: now,
	}

	if err := store.Upsert(ctx, first); err != nil {
		t.Fatalf("first upsert: %v", err)
	}
	second := first
	second.ProgressPercent = 80
	second.Detail = map[string]any{"note": "second"}
	second.UpdatedAt = now.Add(time.Minute)
	if err := store.Upsert(ctx, second); err != nil {
		t.Fatalf("second upsert: %v", err)
	}

	rows, err := store.ListByAssignment(ctx, "a-1", "e-1")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("rows = %d, want 1 (the same key must update, not insert)", len(rows))
	}
	if rows[0].ProgressPercent != 80 {
		t.Fatalf("progress_percent = %d, want 80 (updated in place)", rows[0].ProgressPercent)
	}
	if rows[0].Detail["note"] != "second" {
		t.Fatalf("detail = %v, want the second report's detail", rows[0].Detail)
	}

	// 同一指派、同一员工的不同章节是独立行。
	otherChapter := first
	otherChapter.ID = "row-2"
	otherChapter.ChapterID = "c-2"
	if err := store.Upsert(ctx, otherChapter); err != nil {
		t.Fatalf("upsert other chapter: %v", err)
	}
	// 同一指派、不同员工也是独立行。
	otherEmployee := first
	otherEmployee.ID = "row-3"
	otherEmployee.EmployeeID = "e-2"
	if err := store.Upsert(ctx, otherEmployee); err != nil {
		t.Fatalf("upsert other employee: %v", err)
	}
	rows, err = store.ListByAssignment(ctx, "a-1", "e-1")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(rows) != 2 {
		t.Fatalf("rows = %d, want 2 (different chapter is a distinct row)", len(rows))
	}
}

// ListByAssignment 只返回该 (指派, 员工) 对的记录，其他员工与指派不泄漏。
func TestListByAssignmentIsolatesEmployeeAndAssignment(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	now := time.Now()
	rows := []Progress{
		{ID: "1", AssignmentID: "a-1", EmployeeID: "e-1", ChapterID: "c-1", ProgressPercent: 10, Status: StatusLearning, Detail: map[string]any{}, StartedAt: &now, CreatedAt: now, UpdatedAt: now},
		{ID: "2", AssignmentID: "a-1", EmployeeID: "e-1", ChapterID: "c-2", ProgressPercent: 20, Status: StatusLearning, Detail: map[string]any{}, StartedAt: &now, CreatedAt: now, UpdatedAt: now},
		{ID: "3", AssignmentID: "a-1", EmployeeID: "e-2", ChapterID: "c-1", ProgressPercent: 30, Status: StatusLearning, Detail: map[string]any{}, StartedAt: &now, CreatedAt: now, UpdatedAt: now},
		{ID: "4", AssignmentID: "a-2", EmployeeID: "e-1", ChapterID: "c-1", ProgressPercent: 40, Status: StatusLearning, Detail: map[string]any{}, StartedAt: &now, CreatedAt: now, UpdatedAt: now},
	}
	for _, row := range rows {
		if err := store.Upsert(ctx, row); err != nil {
			t.Fatalf("upsert %s: %v", row.ID, err)
		}
	}

	got, err := store.ListByAssignment(ctx, "a-1", "e-1")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("rows = %d, want 2 (only the a-1/e-1 pair)", len(got))
	}

	if _, err := store.GetByKey(ctx, "a-1", "e-1", "c-1"); err != nil {
		t.Fatalf("get existing key: %v", err)
	}
	if _, err := store.GetByKey(ctx, "a-1", "e-1", "c-9"); err == nil {
		t.Fatal("get unknown key: want ErrNotFound")
	}
}

// 克隆语义：更新存储中的行不会通过返回的副本改写存储内容。
func TestUpsertCopiesRows(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	now := time.Now()
	row := Progress{
		ID: "row-1", AssignmentID: "a-1", EmployeeID: "e-1", ChapterID: "c-1",
		ProgressPercent: 10, Status: StatusLearning, Detail: map[string]any{"note": "original"},
		StartedAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	if err := store.Upsert(ctx, row); err != nil {
		t.Fatalf("upsert: %v", err)
	}

	fetched, err := store.GetByKey(ctx, "a-1", "e-1", "c-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	fetched.Detail["note"] = "mutated"
	fetched.ProgressPercent = 99

	again, err := store.GetByKey(ctx, "a-1", "e-1", "c-1")
	if err != nil {
		t.Fatalf("get again: %v", err)
	}
	if again.ProgressPercent != 10 || again.Detail["note"] != "original" {
		t.Fatalf("stored row mutated through a fetched copy: %+v", again)
	}
}
