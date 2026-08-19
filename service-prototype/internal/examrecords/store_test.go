package examrecords

import (
	"context"
	"errors"
	"testing"
	"time"
)

// testTime is the fixed clock of the store tests; times are distinct so
// ordering assertions never depend on tie-breaks unless a test sets
// them explicitly.
var testTime = time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)

// testRecord builds a record with the given id/employee/paper and the
// fixed test clock.
func testRecord(id, employeeID, paperID string, createdAt time.Time) Record {
	return Record{
		ID:         id,
		EmployeeID: employeeID,
		PaperID:    paperID,
		StartTime:  createdAt,
		CreatedAt:  createdAt,
		UpdatedAt:  createdAt,
		AnswersSnapshot: Snapshot{
			PaperID:   paperID,
			PassScore: 2,
			Questions: []QuestionSnapshot{
				{ID: "q-1", Type: "单选", Content: "题目", Options: []string{"A", "B"}, Answer: "B"},
			},
		},
		Metadata: map[string]any{},
	}
}

func newTestStore() *InMemoryStore {
	return NewInMemoryStore()
}

// Create 后 Get 返回同一记录，且返回值为深拷贝：修改返回值不影响
// store 内的记录。
func TestStoreCreateGetCloneIsolation(t *testing.T) {
	store := newTestStore()
	record := testRecord("r-1", "e-1", "p-1", testTime)
	if err := store.Create(context.Background(), record); err != nil {
		t.Fatalf("create: %v", err)
	}
	got, err := store.Get(context.Background(), "r-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got.ID != record.ID || got.EmployeeID != "e-1" || got.PaperID != "p-1" {
		t.Fatalf("got %+v, want the created record", got)
	}
	if !got.StartTime.Equal(testTime) {
		t.Fatalf("start_time = %v, want %v", got.StartTime, testTime)
	}
	// Mutating the returned record must not leak into the store.
	got.AnswersSnapshot.Questions[0].Options[0] = "mutated"
	got.Metadata["k"] = "v"
	again, err := store.Get(context.Background(), "r-1")
	if err != nil {
		t.Fatalf("second get: %v", err)
	}
	if again.AnswersSnapshot.Questions[0].Options[0] != "A" {
		t.Fatalf("store leaked option mutation: %v", again.AnswersSnapshot.Questions[0].Options)
	}
	if _, ok := again.Metadata["k"]; ok {
		t.Fatal("store leaked metadata mutation")
	}
}

// Get 未知 id 返回 ErrNotFound。
func TestStoreGetNotFound(t *testing.T) {
	store := newTestStore()
	_, err := store.Get(context.Background(), "missing")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("error = %v, want ErrNotFound", err)
	}
}

// Update 替换同 id 记录（交卷写入 end_time/score/passed 的路径）；未知
// id 返回 ErrNotFound。
func TestStoreUpdate(t *testing.T) {
	store := newTestStore()
	record := testRecord("r-1", "e-1", "p-1", testTime)
	if err := store.Create(context.Background(), record); err != nil {
		t.Fatalf("create: %v", err)
	}
	score := 3
	passed := true
	endTime := testTime.Add(time.Hour)
	record.EndTime = &endTime
	record.Score = &score
	record.Passed = &passed
	record.UpdatedAt = endTime
	if err := store.Update(context.Background(), record); err != nil {
		t.Fatalf("update: %v", err)
	}
	got, err := store.Get(context.Background(), "r-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got.EndTime == nil || !got.EndTime.Equal(endTime) {
		t.Fatalf("end_time = %v, want %v", got.EndTime, endTime)
	}
	if got.Score == nil || *got.Score != 3 {
		t.Fatalf("score = %v, want 3", got.Score)
	}
	if got.Passed == nil || !*got.Passed {
		t.Fatalf("passed = %v, want true", got.Passed)
	}

	if err := store.Update(context.Background(), testRecord("missing", "e-1", "p-1", testTime)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("update unknown error = %v, want ErrNotFound", err)
	}
}

// List 空 store 返回空页与 total 0。
func TestStoreListEmpty(t *testing.T) {
	store := newTestStore()
	records, total, err := store.List(context.Background(), Filter{Limit: 50})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("records = %v, total = %d, want empty and 0", records, total)
	}
}

// List 按 created_at DESC 排序，同刻按 id DESC 稳定排序；employee_id/
// paper_id 精确筛选可联合；limit/offset 分页，total 为筛选后全量。
func TestStoreListFilterSortPagination(t *testing.T) {
	store := newTestStore()
	records := []Record{
		testRecord("r-1", "e-1", "p-1", testTime.Add(3*time.Hour)),
		testRecord("r-2", "e-2", "p-1", testTime.Add(2*time.Hour)),
		testRecord("r-3", "e-1", "p-2", testTime.Add(time.Hour)),
		testRecord("r-4", "e-2", "p-2", testTime),                  // oldest
		testRecord("r-5", "e-1", "p-2", testTime.Add(3*time.Hour)), // ties with r-1
	}
	// Tie-break control: r-5 (created_at equal to r-1) has a larger id,
	// so created_at DESC + id DESC puts r-5 before r-1.
	for _, record := range records {
		if err := store.Create(context.Background(), record); err != nil {
			t.Fatalf("create: %v", err)
		}
	}

	// 无筛选：5 条，created_at DESC（同刻 r-5 > r-1 在前）。
	page, total, err := store.List(context.Background(), Filter{Limit: 50})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 5 {
		t.Fatalf("total = %d, want 5", total)
	}
	wantOrder := []string{"r-5", "r-1", "r-2", "r-3", "r-4"}
	for i, want := range wantOrder {
		if page[i].ID != want {
			t.Fatalf("page[%d].ID = %q, want %q (order %v)", i, page[i].ID, want, wantOrder)
		}
	}

	// employee_id 筛选：e-1 的 3 条。
	page, total, err = store.List(context.Background(), Filter{EmployeeID: "e-1", Limit: 50})
	if err != nil {
		t.Fatalf("list employee: %v", err)
	}
	if total != 3 || len(page) != 3 {
		t.Fatalf("employee filter: total = %d, len = %d, want 3/3", total, len(page))
	}
	for _, record := range page {
		if record.EmployeeID != "e-1" {
			t.Fatalf("employee filter leaked %+v", record)
		}
	}

	// paper_id 筛选：p-2 的 3 条。
	page, total, err = store.List(context.Background(), Filter{PaperID: "p-2", Limit: 50})
	if err != nil {
		t.Fatalf("list paper: %v", err)
	}
	if total != 3 || len(page) != 3 {
		t.Fatalf("paper filter: total = %d, len = %d, want 3/3", total, len(page))
	}

	// 联合筛选：e-1 + p-2 的 2 条。
	page, total, err = store.List(context.Background(), Filter{EmployeeID: "e-1", PaperID: "p-2", Limit: 50})
	if err != nil {
		t.Fatalf("list combined: %v", err)
	}
	if total != 2 {
		t.Fatalf("combined filter: total = %d, want 2", total)
	}

	// limit/offset：limit=1 返回 1 条且 total 为全量；offset 跳过前 N 条。
	page, total, err = store.List(context.Background(), Filter{Limit: 1})
	if err != nil {
		t.Fatalf("list limit: %v", err)
	}
	if len(page) != 1 || total != 5 {
		t.Fatalf("limit=1: len = %d, total = %d, want 1/5", len(page), total)
	}
	if page[0].ID != "r-5" {
		t.Fatalf("limit=1 page = %q, want the newest record", page[0].ID)
	}
	page, total, err = store.List(context.Background(), Filter{Limit: 50, Offset: 2})
	if err != nil {
		t.Fatalf("list offset: %v", err)
	}
	if len(page) != 3 || total != 5 {
		t.Fatalf("offset=2: len = %d, total = %d, want 3/5", len(page), total)
	}
	if page[0].ID != "r-2" {
		t.Fatalf("offset=2 first = %q, want the third-newest record", page[0].ID)
	}
	// limit=0 与越界 offset 返回空页但 total 仍为全量。
	page, total, err = store.List(context.Background(), Filter{Limit: 0})
	if err != nil {
		t.Fatalf("list limit 0: %v", err)
	}
	if len(page) != 0 || total != 5 {
		t.Fatalf("limit=0: len = %d, total = %d, want 0/5", len(page), total)
	}
	page, total, err = store.List(context.Background(), Filter{Limit: 50, Offset: 99})
	if err != nil {
		t.Fatalf("list offset beyond: %v", err)
	}
	if len(page) != 0 || total != 5 {
		t.Fatalf("offset beyond: len = %d, total = %d, want 0/5", len(page), total)
	}
}

// List 返回的记录也是深拷贝：修改分页结果不影响 store。
func TestStoreListCloneIsolation(t *testing.T) {
	store := newTestStore()
	if err := store.Create(context.Background(), testRecord("r-1", "e-1", "p-1", testTime)); err != nil {
		t.Fatalf("create: %v", err)
	}
	page, _, err := store.List(context.Background(), Filter{Limit: 50})
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	page[0].AnswersSnapshot.Questions[0].Options[0] = "mutated"
	got, err := store.Get(context.Background(), "r-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got.AnswersSnapshot.Questions[0].Options[0] != "A" {
		t.Fatal("list leaked mutation into the store")
	}
}
