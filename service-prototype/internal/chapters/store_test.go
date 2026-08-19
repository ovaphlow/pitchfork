package chapters

import (
	"context"
	"errors"
	"testing"
	"time"
)

// TestListByCourseSortOrderAndTieBreak pins the listing order: sort_order
// ascending, ties broken by created_at ascending. The store sorts a copy,
// so insertion order must not matter.
func TestListByCourseSortOrderAndTieBreak(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Date(2026, 8, 2, 10, 0, 0, 0, time.UTC)
	chapters := []Chapter{
		{ID: "c-late2", CourseID: "course-1", SortOrder: 2, Title: "乙", CreatedAt: base},
		{ID: "c-a-later", CourseID: "course-1", SortOrder: 0, Title: "甲后", CreatedAt: base.Add(2 * time.Millisecond)},
		{ID: "c-a-earlier", CourseID: "course-1", SortOrder: 0, Title: "甲前", CreatedAt: base.Add(1 * time.Millisecond)},
		{ID: "c-mid", CourseID: "course-1", SortOrder: 1, Title: "丙", CreatedAt: base},
		{ID: "c-other", CourseID: "course-2", SortOrder: 0, Title: "他课章节", CreatedAt: base},
	}
	for _, chapter := range chapters {
		if err := store.Create(ctx, chapter); err != nil {
			t.Fatalf("create %s: %v", chapter.ID, err)
		}
	}

	records, total, err := store.ListByCourse(ctx, "course-1", Filter{Limit: 50})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 4 {
		t.Fatalf("total = %d, want 4 (only chapters of course-1)", total)
	}
	want := []string{"c-a-earlier", "c-a-later", "c-mid", "c-late2"}
	if len(records) != len(want) {
		t.Fatalf("records = %d, want %d", len(records), len(want))
	}
	for i, id := range want {
		if records[i].ID != id {
			t.Fatalf("records[%d].id = %q, want %q (order %v)", i, records[i].ID, id, want)
		}
	}
}

// TestListByCoursePagination verifies limit/offset slicing happens after
// sorting and keeps the total before pagination.
func TestListByCoursePagination(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		chapter := Chapter{ID: string(rune('a' + i)), CourseID: "course-1", Title: "章节", CreatedAt: time.Now()}
		if err := store.Create(ctx, chapter); err != nil {
			t.Fatalf("create: %v", err)
		}
	}

	records, total, err := store.ListByCourse(ctx, "course-1", Filter{Limit: 2, Offset: 0})
	if err != nil || total != 3 || len(records) != 2 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d, err = %v; want 2 / 3", len(records), total, err)
	}

	records, total, err = store.ListByCourse(ctx, "course-1", Filter{Limit: 2, Offset: 5})
	if err != nil || total != 3 || len(records) != 0 {
		t.Fatalf("offset beyond end: records = %d, total = %d, err = %v; want 0 / 3", len(records), total, err)
	}
}

// TestDeleteByCourse removes every chapter of a course and only that
// course; it is a no-op for courses without chapters.
func TestDeleteByCourse(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Now()
	for _, chapter := range []Chapter{
		{ID: "a1", CourseID: "course-1", Title: "甲一", CreatedAt: base},
		{ID: "a2", CourseID: "course-1", Title: "甲二", CreatedAt: base},
		{ID: "b1", CourseID: "course-2", Title: "乙一", CreatedAt: base},
	} {
		if err := store.Create(ctx, chapter); err != nil {
			t.Fatalf("create %s: %v", chapter.ID, err)
		}
	}

	if err := store.DeleteByCourse(ctx, "course-1"); err != nil {
		t.Fatalf("delete by course: %v", err)
	}
	for _, id := range []string{"a1", "a2"} {
		if _, err := store.Get(ctx, id); !errors.Is(err, ErrNotFound) {
			t.Fatalf("chapter %s after DeleteByCourse: err = %v, want ErrNotFound", id, err)
		}
	}
	if _, err := store.Get(ctx, "b1"); err != nil {
		t.Fatalf("chapter of the other course must survive: %v", err)
	}

	// 无章节的课程删除为空操作，不报错。
	if err := store.DeleteByCourse(ctx, "course-without-chapters"); err != nil {
		t.Fatalf("delete by course without chapters: %v", err)
	}
	records, total, err := store.ListByCourse(ctx, "course-2", Filter{Limit: 50})
	if err != nil || total != 1 || len(records) != 1 {
		t.Fatalf("course-2 chapters: records = %d, total = %d, err = %v; want 1 / 1", len(records), total, err)
	}
}
