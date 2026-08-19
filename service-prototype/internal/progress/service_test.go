package progress

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
)

// ─── 测试替身 ────────────────────────────────────────────────────────

// fakeAssignments serves assignments by id for the AssignmentLookup.
type fakeAssignments struct {
	byID map[string]assignments.Assignment
}

func (f *fakeAssignments) Get(_ context.Context, id string) (assignments.Assignment, error) {
	assignment, ok := f.byID[id]
	if !ok {
		return assignments.Assignment{}, assignments.ErrNotFound
	}
	return assignment, nil
}

// fakeChapters serves chapters by id and by course for the
// ChapterLookup; byCourse keeps the insertion order of the slice.
type fakeChapters struct {
	byID     map[string]chapters.Chapter
	byCourse map[string][]chapters.Chapter
}

func (f *fakeChapters) Get(_ context.Context, id string) (chapters.Chapter, error) {
	chapter, ok := f.byID[id]
	if !ok {
		return chapters.Chapter{}, chapters.ErrNotFound
	}
	return chapter, nil
}

func (f *fakeChapters) ListByCourse(_ context.Context, courseID string, _ chapters.Filter) ([]chapters.Chapter, int, error) {
	// Emulate the chapters store contract: sort_order ascending.
	list := append([]chapters.Chapter(nil), f.byCourse[courseID]...)
	sort.SliceStable(list, func(i, j int) bool {
		return list[i].SortOrder < list[j].SortOrder
	})
	return list, len(list), nil
}

// fakeCourses serves courses by id for the CourseLookup.
type fakeCourses struct {
	byID map[string]courses.Course
}

func (f *fakeCourses) Get(_ context.Context, id string) (courses.Course, error) {
	course, ok := f.byID[id]
	if !ok {
		return courses.Course{}, courses.ErrNotFound
	}
	return course, nil
}

// newTestService builds a service over the fakes with a fixed clock and
// sequential server-generated ids. The clock pointer lets tests advance
// the time between calls.
func newTestService(store Store, assignmentsByID map[string]assignments.Assignment, chaptersByID map[string]chapters.Chapter, chaptersByCourse map[string][]chapters.Chapter, coursesByID map[string]courses.Course, now *time.Time) *Service {
	service := NewService(
		store,
		&fakeAssignments{byID: assignmentsByID},
		&fakeChapters{byID: chaptersByID, byCourse: chaptersByCourse},
		&fakeCourses{byID: coursesByID},
	)
	service.now = func() time.Time { return *now }
	next := 0
	service.newID = func() string {
		next++
		return fmt.Sprintf("row-%d", next)
	}
	return service
}

// fixtureCourse/Chapter/Assignment build the shared dataset: one course
// with two chapters and one assignment targeting the employee.
type fixture struct {
	service  *Service
	store    Store
	course   courses.Course
	chapters []chapters.Chapter
	clock    *time.Time
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	now := time.Date(2026, 8, 10, 9, 0, 0, 0, time.UTC)
	store := NewInMemoryStore()
	course := courses.Course{ID: "course-1", Title: "客流组织基础"}
	chapter1 := chapters.Chapter{ID: "chapter-1", CourseID: "course-1", SortOrder: 2, Title: "第二章"}
	chapter2 := chapters.Chapter{ID: "chapter-2", CourseID: "course-1", SortOrder: 1, Title: "第一章"}
	assignment := assignments.Assignment{ID: "assignment-1", CourseID: "course-1"}
	service := newTestService(
		store,
		map[string]assignments.Assignment{assignment.ID: assignment},
		map[string]chapters.Chapter{chapter1.ID: chapter1, chapter2.ID: chapter2},
		map[string][]chapters.Chapter{"course-1": {chapter1, chapter2}},
		map[string]courses.Course{course.ID: course},
		&now,
	)
	return &fixture{service: service, store: store, course: course, chapters: []chapters.Chapter{chapter1, chapter2}, clock: &now}
}

// ─── Upsert：首次上报与 started_at 保留 ──────────────────────────────

// 首次上报记 started_at；二次上报（同键）保留 started_at、刷新
// updated_at，行数不新增。
func TestUpsertSetsStartedAtOnlyOnFirstReport(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	first, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: 40, Detail: map[string]any{"watch": 3}})
	if err != nil {
		t.Fatalf("first upsert: %v", err)
	}
	if first.ID != "row-1" {
		t.Fatalf("id = %q, want the server-generated row-1", first.ID)
	}
	if first.Status != StatusLearning {
		t.Fatalf("status = %q, want 学习中 on the first report", first.Status)
	}
	if first.StartedAt == nil || !first.StartedAt.Equal(*fixture.clock) {
		t.Fatalf("started_at = %v, want the first-report timestamp", first.StartedAt)
	}
	if first.CompletedAt != nil {
		t.Fatalf("completed_at = %v, want nil at 40 percent", first.CompletedAt)
	}
	if !first.CreatedAt.Equal(first.UpdatedAt) {
		t.Fatalf("created_at = %v, updated_at = %v; want them equal at creation", first.CreatedAt, first.UpdatedAt)
	}

	// 推进时钟后二次上报：started_at 保留、updated_at 刷新。
	firstStarted := *first.StartedAt
	*fixture.clock = fixture.clock.Add(2 * time.Second)
	second, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: 60, Detail: map[string]any{"watch": 6}})
	if err != nil {
		t.Fatalf("second upsert: %v", err)
	}
	if second.ID != first.ID {
		t.Fatalf("id = %q, want the row id to stay stable across reports", second.ID)
	}
	if second.StartedAt == nil || !second.StartedAt.Equal(firstStarted) {
		t.Fatalf("started_at = %v, want it preserved from the first report", second.StartedAt)
	}
	if !second.UpdatedAt.After(first.UpdatedAt) {
		t.Fatalf("updated_at = %v, want it refreshed by the second report", second.UpdatedAt)
	}
	if !second.CreatedAt.Equal(first.CreatedAt) {
		t.Fatalf("created_at = %v, want it preserved from the first report", second.CreatedAt)
	}
	if second.ProgressPercent != 60 || second.Detail["watch"] != 6 {
		t.Fatalf("row = %+v, want the second report's progress_percent/detail", second)
	}

	rows, err := fixture.store.ListByAssignment(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("rows = %d, want 1 (the same key must not insert again)", len(rows))
	}
}

// ─── Upsert：100 分完成与不回退 ─────────────────────────────────────

// 100 分置已完成并记 completed_at；已完成后再次上报 <100 不回退
// status/completed_at，progress_percent/detail 仍更新。
func TestUpsertCompletedNeverReverts(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	completed, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: 100})
	if err != nil {
		t.Fatalf("upsert 100: %v", err)
	}
	if completed.Status != StatusCompleted {
		t.Fatalf("status = %q, want 已完成 at 100 percent", completed.Status)
	}
	if completed.CompletedAt == nil || !completed.CompletedAt.Equal(*fixture.clock) {
		t.Fatalf("completed_at = %v, want it set at 100 percent", completed.CompletedAt)
	}

	regressed, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: 80, Detail: map[string]any{"note": "review"}})
	if err != nil {
		t.Fatalf("upsert 80 after completion: %v", err)
	}
	if regressed.Status != StatusCompleted {
		t.Fatalf("status = %q, want 已完成 to never revert", regressed.Status)
	}
	if regressed.CompletedAt == nil || !regressed.CompletedAt.Equal(*fixture.clock) {
		t.Fatalf("completed_at = %v, want it preserved after regression", regressed.CompletedAt)
	}
	if regressed.ProgressPercent != 80 || regressed.Detail["note"] != "review" {
		t.Fatalf("row = %+v, want progress_percent/detail to keep updating", regressed)
	}
}

// 越界 progress_percent → ValidationError（400）。
func TestUpsertRejectsOutOfRange(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	for _, percent := range []int{-1, 101} {
		_, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: percent})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("percent %d: err = %v, want ValidationError", percent, err)
		}
	}
}

// 指派不存在 / 章节不存在 / 章节不属于指派课程 → 404 错误。
func TestUpsertNotFoundCases(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	if _, err := fixture.service.Upsert(ctx, "missing-assignment", "e-1", "chapter-1", Input{ProgressPercent: 50}); !errors.Is(err, ErrAssignmentNotFound) {
		t.Fatalf("missing assignment: err = %v, want ErrAssignmentNotFound", err)
	}
	if _, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "missing-chapter", Input{ProgressPercent: 50}); !errors.Is(err, ErrChapterNotFound) {
		t.Fatalf("missing chapter: err = %v, want ErrChapterNotFound", err)
	}
	// 章节存在但属于另一课程。
	foreign := chapters.Chapter{ID: "chapter-9", CourseID: "course-9"}
	fixture.service.chapters.(*fakeChapters).byID["chapter-9"] = foreign
	if _, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-9", Input{ProgressPercent: 50}); !errors.Is(err, ErrChapterNotFound) {
		t.Fatalf("foreign chapter: err = %v, want ErrChapterNotFound", err)
	}
}

// ─── 汇总推导 ───────────────────────────────────────────────────────

// 汇总推导：无上报→学习中、部分完成→学习中、全部完成→已完成；
// 章节按 sort_order 升序；无上报章节以零值呈现。
func TestSummaryDerivation(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	// 无上报：全部零值、学习中。
	summary, err := fixture.service.Summary(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if summary.CourseID != "course-1" || summary.CourseTitle != "客流组织基础" {
		t.Fatalf("course = %s/%s, want course-1/客流组织基础", summary.CourseID, summary.CourseTitle)
	}
	if summary.TotalChapters != 2 || summary.CompletedChapters != 0 || summary.Status != StatusLearning {
		t.Fatalf("total/completed/status = %d/%d/%q, want 2/0/学习中", summary.TotalChapters, summary.CompletedChapters, summary.Status)
	}
	if len(summary.Chapters) != 2 {
		t.Fatalf("chapters = %d, want 2", len(summary.Chapters))
	}
	// sort_order 升序：chapter-2（第一章，order 1）在前。
	if summary.Chapters[0].ChapterID != "chapter-2" || summary.Chapters[1].ChapterID != "chapter-1" {
		t.Fatalf("chapter order = %s, %s; want sort_order ascending (chapter-2, chapter-1)", summary.Chapters[0].ChapterID, summary.Chapters[1].ChapterID)
	}
	for _, entry := range summary.Chapters {
		if entry.ProgressPercent != 0 || entry.Status != StatusLearning ||
			entry.StartedAt != nil || entry.CompletedAt != nil || len(entry.Detail) != 0 {
			t.Fatalf("unreported chapter %+v, want zero values", entry)
		}
	}

	// 部分完成：一章 100 → 学习中，completed 1。
	if _, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: 100}); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	summary, err = fixture.service.Summary(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if summary.CompletedChapters != 1 || summary.Status != StatusLearning {
		t.Fatalf("completed/status = %d/%q, want 1/学习中 (partial completion)", summary.CompletedChapters, summary.Status)
	}
	if summary.Chapters[1].ProgressPercent != 100 || summary.Chapters[1].Status != StatusCompleted {
		t.Fatalf("completed chapter row = %+v, want 100/已完成", summary.Chapters[1])
	}

	// 全部完成 → 已完成。
	if _, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-2", Input{ProgressPercent: 100}); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	summary, err = fixture.service.Summary(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if summary.CompletedChapters != 2 || summary.Status != StatusCompleted {
		t.Fatalf("completed/status = %d/%q, want 2/已完成", summary.CompletedChapters, summary.Status)
	}
}

// 无章节课程：GET 与 complete 后一致，均为学习中（空集不视为全部完成）。
func TestSummaryEmptyCourseStaysLearning(t *testing.T) {
	ctx := context.Background()
	now := time.Date(2026, 8, 10, 9, 0, 0, 0, time.UTC)
	store := NewInMemoryStore()
	service := newTestService(
		store,
		map[string]assignments.Assignment{"assignment-1": {ID: "assignment-1", CourseID: "course-empty"}},
		map[string]chapters.Chapter{},
		map[string][]chapters.Chapter{"course-empty": {}},
		map[string]courses.Course{"course-empty": {ID: "course-empty", Title: "空课程"}},
		&now,
	)

	summary, err := service.Summary(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if summary.TotalChapters != 0 || summary.CompletedChapters != 0 || summary.Status != StatusLearning {
		t.Fatalf("total/completed/status = %d/%d/%q, want 0/0/学习中", summary.TotalChapters, summary.CompletedChapters, summary.Status)
	}
	if len(summary.Chapters) != 0 {
		t.Fatalf("chapters = %d, want an empty slice", len(summary.Chapters))
	}

	completed, err := service.Complete(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	if completed.TotalChapters != 0 || completed.CompletedChapters != 0 || completed.Status != StatusLearning {
		t.Fatalf("complete: total/completed/status = %d/%d/%q, want 0/0/学习中 (empty set)", completed.TotalChapters, completed.CompletedChapters, completed.Status)
	}
	if rows, err := store.ListByAssignment(ctx, "assignment-1", "e-1"); err != nil || len(rows) != 0 {
		t.Fatalf("rows after complete = %d, err = %v; want no rows written", len(rows), err)
	}
}

// ─── Complete ───────────────────────────────────────────────────────

// complete 置全部章节已完成：已上报行保留 started_at，未上报行创建即
// 完成；幂等；complete 后 GET 与 complete 响应一致。
func TestCompleteMarksAllChapters(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	if _, err := fixture.service.Upsert(ctx, "assignment-1", "e-1", "chapter-1", Input{ProgressPercent: 30}); err != nil {
		t.Fatalf("upsert: %v", err)
	}

	summary, err := fixture.service.Complete(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	if summary.TotalChapters != 2 || summary.CompletedChapters != 2 || summary.Status != StatusCompleted {
		t.Fatalf("total/completed/status = %d/%d/%q, want 2/2/已完成", summary.TotalChapters, summary.CompletedChapters, summary.Status)
	}
	for _, entry := range summary.Chapters {
		if entry.ProgressPercent != 100 || entry.Status != StatusCompleted {
			t.Fatalf("chapter row = %+v, want 100/已完成", entry)
		}
		if entry.CompletedAt == nil {
			t.Fatalf("chapter %s: completed_at must be set after complete", entry.ChapterID)
		}
	}

	// complete 后 GET 反映同样状态。
	after, err := fixture.service.Summary(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("summary after complete: %v", err)
	}
	if after.Status != StatusCompleted || after.CompletedChapters != 2 {
		t.Fatalf("GET after complete = %+v, want the completed state", after)
	}

	// 幂等：重复调用仍成功且状态一致。
	again, err := fixture.service.Complete(ctx, "assignment-1", "e-1")
	if err != nil {
		t.Fatalf("second complete: %v", err)
	}
	if again.Status != StatusCompleted || again.CompletedChapters != 2 {
		t.Fatalf("second complete = %+v, want the same completed state", again)
	}
}

// complete 对不存在指派的处理与 GET 一致：404。
func TestCompleteAssignmentNotFound(t *testing.T) {
	ctx := context.Background()
	fixture := newFixture(t)
	if _, err := fixture.service.Complete(ctx, "missing-assignment", "e-1"); !errors.Is(err, ErrAssignmentNotFound) {
		t.Fatalf("err = %v, want ErrAssignmentNotFound", err)
	}
}
