package assignments

import (
	"context"
	"errors"
	"testing"
	"time"
)

// TestListSortsByCreatedAtDesc pins the listing order: created_at
// descending, ties keeping insertion order. The store sorts a copy, so
// insertion order must not matter.
func TestListSortsByCreatedAtDesc(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Date(2026, 8, 2, 10, 0, 0, 0, time.UTC)
	items := []Assignment{
		{ID: "a-old", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-1"}, CreatedAt: base},
		{ID: "b-newest", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-2"}, CreatedAt: base.Add(3 * time.Millisecond)},
		{ID: "c-mid", CourseID: "course-1", AssignType: AssignTypeAuto, TargetType: TargetTypeDept, TargetIDs: []string{"d-1"}, CreatedAt: base.Add(2 * time.Millisecond)},
		{ID: "d-tie", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-3"}, CreatedAt: base.Add(2 * time.Millisecond)},
		{ID: "e-other", CourseID: "course-2", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-9"}, CreatedAt: base.Add(5 * time.Millisecond)},
	}
	for _, item := range items {
		if err := store.Create(ctx, item); err != nil {
			t.Fatalf("create %s: %v", item.ID, err)
		}
	}

	records, total, err := store.List(ctx, Filter{Limit: 50})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 5 {
		t.Fatalf("total = %d, want 5", total)
	}
	want := []string{"e-other", "b-newest", "c-mid", "d-tie", "a-old"}
	if len(records) != len(want) {
		t.Fatalf("records = %d, want %d", len(records), len(want))
	}
	for i, id := range want {
		if records[i].ID != id {
			t.Fatalf("records[%d].id = %q, want %q (created_at desc, ties keep insertion order)", i, records[i].ID, id)
		}
	}
}

// TestListFilters verify course_id/target_type exact matching and the
// employee_id expansion rule: only 用户 assignments whose target_ids
// contain the id match; 岗位/部门 assignments never match an employee id.
func TestListFilters(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Now()
	for _, item := range []Assignment{
		{ID: "a1", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-001", "u-002"}, CreatedAt: base},
		{ID: "a2", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-999"}, CreatedAt: base.Add(1 * time.Millisecond)},
		{ID: "a3", CourseID: "course-1", AssignType: AssignTypeAuto, TargetType: TargetTypePost, TargetIDs: []string{"u-001"}, CreatedAt: base.Add(2 * time.Millisecond)},
		{ID: "a4", CourseID: "course-1", AssignType: AssignTypeAuto, TargetType: TargetTypeDept, TargetIDs: []string{"u-001"}, CreatedAt: base.Add(3 * time.Millisecond)},
		{ID: "b1", CourseID: "course-2", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-001"}, CreatedAt: base.Add(4 * time.Millisecond)},
	} {
		if err := store.Create(ctx, item); err != nil {
			t.Fatalf("create %s: %v", item.ID, err)
		}
	}

	cases := []struct {
		name   string
		filter Filter
		total  int
		want   []string
	}{
		{"course_id", Filter{CourseID: "course-1", Limit: 50}, 4, []string{"a4", "a3", "a2", "a1"}},
		{"unknown course_id", Filter{CourseID: "missing", Limit: 50}, 0, nil},
		{"target_type 用户", Filter{TargetType: TargetTypeUser, Limit: 50}, 3, []string{"b1", "a2", "a1"}},
		{"target_type 岗位", Filter{TargetType: TargetTypePost, Limit: 50}, 1, []string{"a3"}},
		{"employee hit", Filter{EmployeeID: "u-001", Limit: 50}, 2, []string{"b1", "a1"}},
		{"employee miss", Filter{EmployeeID: "u-404", Limit: 50}, 0, nil},
		{"combined course+user+employee", Filter{CourseID: "course-1", TargetType: TargetTypeUser, EmployeeID: "u-001", Limit: 50}, 1, []string{"a1"}},
	}
	for _, testCase := range cases {
		records, total, err := store.List(ctx, testCase.filter)
		if err != nil {
			t.Fatalf("%s: %v", testCase.name, err)
		}
		if total != testCase.total || len(records) != len(testCase.want) {
			t.Fatalf("%s: records = %d, total = %d; want %d / %d",
				testCase.name, len(records), total, len(testCase.want), testCase.total)
		}
		for i, id := range testCase.want {
			if records[i].ID != id {
				t.Fatalf("%s: records[%d].id = %q, want %q", testCase.name, i, records[i].ID, id)
			}
		}
	}
}

// TestListPagination verifies limit/offset slicing happens after sorting
// and keeps the total before pagination.
func TestListPagination(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Now()
	for i := 0; i < 3; i++ {
		item := Assignment{
			ID: string(rune('a' + i)), CourseID: "course-1",
			AssignType: AssignTypeManual, TargetType: TargetTypeUser,
			TargetIDs: []string{"u-1"}, CreatedAt: base.Add(time.Duration(i) * time.Millisecond),
		}
		if err := store.Create(ctx, item); err != nil {
			t.Fatalf("create: %v", err)
		}
	}

	records, total, err := store.List(ctx, Filter{Limit: 2, Offset: 0})
	if err != nil || total != 3 || len(records) != 2 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d, err = %v; want 2 / 3", len(records), total, err)
	}

	records, total, err = store.List(ctx, Filter{Limit: 2, Offset: 5})
	if err != nil || total != 3 || len(records) != 0 {
		t.Fatalf("offset beyond end: records = %d, total = %d, err = %v; want 0 / 3", len(records), total, err)
	}
}

// TestDelete removes the assignment with the given id and leaves the
// others untouched; an unknown id returns ErrNotFound.
func TestDelete(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Now()
	for _, item := range []Assignment{
		{ID: "a1", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-1"}, CreatedAt: base},
		{ID: "a2", CourseID: "course-1", AssignType: AssignTypeManual, TargetType: TargetTypeUser, TargetIDs: []string{"u-2"}, CreatedAt: base},
	} {
		if err := store.Create(ctx, item); err != nil {
			t.Fatalf("create %s: %v", item.ID, err)
		}
	}

	if err := store.Delete(ctx, "a1"); err != nil {
		t.Fatalf("delete a1: %v", err)
	}
	records, total, err := store.List(ctx, Filter{Limit: 50})
	if err != nil || total != 1 || len(records) != 1 || records[0].ID != "a2" {
		t.Fatalf("after delete: records = %v, total = %d, err = %v; want a2 only", records, total, err)
	}

	if err := store.Delete(ctx, "01ARZ3NDEKTSV4RRFFQ69G5FAV"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("delete unknown id: err = %v, want ErrNotFound", err)
	}
}
