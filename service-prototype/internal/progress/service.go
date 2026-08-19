package progress

import (
	"context"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// AssignmentLookup is the subset of the assignments service the progress
// service needs: it reads an assignment by id to validate that the
// assignment of a progress operation exists (404) and to resolve its
// course. Injected at the composition root so the progress service never
// owns an assignment store.
type AssignmentLookup interface {
	Get(ctx context.Context, id string) (assignments.Assignment, error)
}

// ChapterLookup is the subset of the chapters service the progress
// service needs: it reads a chapter by id to validate existence and
// course ownership (404) and lists the chapters of a course for the
// summary and the complete action. Injected at the composition root so
// the progress service never owns a chapter store.
type ChapterLookup interface {
	Get(ctx context.Context, id string) (chapters.Chapter, error)
	ListByCourse(ctx context.Context, courseID string, filter chapters.Filter) ([]chapters.Chapter, int, error)
}

// CourseLookup is the subset of the courses store the progress service
// needs: it reads a course by id for the summary title. Injected at the
// composition root so the progress service never owns a course store.
type CourseLookup interface {
	Get(ctx context.Context, id string) (courses.Course, error)
}

// Service applies the learning-progress business rules (validation,
// upsert semantics, status derivation, server-generated ids and
// timestamps) on top of the store.
type Service struct {
	store       Store
	assignments AssignmentLookup
	chapters    ChapterLookup
	courses     CourseLookup
	now         func() time.Time
	newID       func() string
}

// NewService builds a service over the given store and lookups. The
// server-generated id is a 26-character Crockford Base32 ULID.
func NewService(store Store, assignments AssignmentLookup, chapters ChapterLookup, courses CourseLookup) *Service {
	return &Service{
		store:       store,
		assignments: assignments,
		chapters:    chapters,
		courses:     courses,
		now:         time.Now,
		newID:       ulid.New,
	}
}

// Upsert records the progress of one chapter of one employee within one
// assignment and returns the updated row. The first report of a chapter
// creates the row (started_at is set then); later reports update it in
// place (started_at is preserved). The status is derived server-side: a
// row starts 学习中 and becomes 已完成 at progress_percent 100; a
// completed row never reverts — later reports with progress_percent < 100
// keep status and completed_at while progress_percent and detail still
// update. A missing assignment or chapter, or a chapter that does not
// belong to the assignment course, is a 404. The employee id is a plain
// progress dimension and is never validated.
func (s *Service) Upsert(ctx context.Context, assignmentID, employeeID, chapterID string, input Input) (Progress, error) {
	assignment, err := s.requireAssignment(ctx, assignmentID)
	if err != nil {
		return Progress{}, err
	}
	chapter, err := s.requireChapter(ctx, chapterID)
	if err != nil {
		return Progress{}, err
	}
	if chapter.CourseID != assignment.CourseID {
		return Progress{}, ErrChapterNotFound
	}
	if input.ProgressPercent < 0 || input.ProgressPercent > 100 {
		return Progress{}, &ValidationError{Message: "progress_percent must be between 0 and 100"}
	}
	detail := input.Detail
	if detail == nil {
		detail = map[string]any{}
	}
	now := s.now()
	existing, err := s.store.GetByKey(ctx, assignmentID, employeeID, chapterID)
	if err != nil && !errors.Is(err, ErrNotFound) {
		return Progress{}, err
	}
	if errors.Is(err, ErrNotFound) {
		row := Progress{
			ID:              s.newID(),
			AssignmentID:    assignmentID,
			EmployeeID:      employeeID,
			ChapterID:       chapterID,
			ProgressPercent: input.ProgressPercent,
			Status:          StatusLearning,
			Detail:          detail,
			StartedAt:       &now,
			CreatedAt:       now,
			UpdatedAt:       now,
		}
		if input.ProgressPercent == 100 {
			row.Status = StatusCompleted
			row.CompletedAt = &now
		}
		if err := s.store.Upsert(ctx, row); err != nil {
			return Progress{}, err
		}
		return row, nil
	}
	row := existing
	row.ProgressPercent = input.ProgressPercent
	row.Detail = detail
	if row.Status != StatusCompleted && input.ProgressPercent == 100 {
		row.Status = StatusCompleted
		row.CompletedAt = &now
	}
	row.UpdatedAt = now
	if err := s.store.Upsert(ctx, row); err != nil {
		return Progress{}, err
	}
	return row, nil
}

// Summary returns the learning-progress summary of one employee within
// one assignment. Chapters covers every chapter of the assignment course
// in sort_order ascending; chapters without a report carry the zero
// values (0 / 学习中 / nil / {}). The summary status is derived from the
// chapter rows: every chapter completed → 已完成, otherwise (partial
// completion, no reports, or a course without chapters) 学习中. A missing
// assignment is a 404.
func (s *Service) Summary(ctx context.Context, assignmentID, employeeID string) (Summary, error) {
	assignment, err := s.requireAssignment(ctx, assignmentID)
	if err != nil {
		return Summary{}, err
	}
	course, err := s.courses.Get(ctx, assignment.CourseID)
	if err != nil {
		if errors.Is(err, courses.ErrNotFound) {
			return Summary{}, ErrCourseNotFound
		}
		return Summary{}, err
	}
	chapterList, _, err := s.chapters.ListByCourse(ctx, assignment.CourseID, chapters.Filter{Limit: -1})
	if err != nil {
		return Summary{}, err
	}
	rows, err := s.store.ListByAssignment(ctx, assignmentID, employeeID)
	if err != nil {
		return Summary{}, err
	}
	rowByChapter := make(map[string]Progress, len(rows))
	for _, row := range rows {
		rowByChapter[row.ChapterID] = row
	}
	chapterProgress := make([]ChapterProgress, 0, len(chapterList))
	completed := 0
	for _, chapter := range chapterList {
		entry := ChapterProgress{
			ChapterID:       chapter.ID,
			ChapterTitle:    chapter.Title,
			ProgressPercent: 0,
			Status:          StatusLearning,
			Detail:          map[string]any{},
		}
		if row, reported := rowByChapter[chapter.ID]; reported {
			entry.ProgressPercent = row.ProgressPercent
			entry.Status = row.Status
			entry.StartedAt = row.StartedAt
			entry.CompletedAt = row.CompletedAt
			entry.Detail = row.Detail
			if row.Status == StatusCompleted {
				completed++
			}
		}
		chapterProgress = append(chapterProgress, entry)
	}
	status := StatusLearning
	if len(chapterProgress) > 0 && completed == len(chapterProgress) {
		status = StatusCompleted
	}
	return Summary{
		AssignmentID:      assignmentID,
		EmployeeID:        employeeID,
		CourseID:          assignment.CourseID,
		CourseTitle:       course.Title,
		TotalChapters:     len(chapterProgress),
		CompletedChapters: completed,
		Status:            status,
		Chapters:          chapterProgress,
	}, nil
}

// Complete marks every chapter of the assignment course as 已完成 for
// the employee: existing rows are updated (progress_percent 100, status
// 已完成, completed_at set; started_at preserved), missing rows are
// created completed. The response is the summary after the update, so a
// subsequent GET reflects the same state. A course without chapters has
// no rows to write and the summary stays 学习中 (an empty set is never
// 已完成). The action is idempotent: repeated calls still return 200
// with the same summary. A missing assignment is a 404.
func (s *Service) Complete(ctx context.Context, assignmentID, employeeID string) (Summary, error) {
	assignment, err := s.requireAssignment(ctx, assignmentID)
	if err != nil {
		return Summary{}, err
	}
	chapterList, _, err := s.chapters.ListByCourse(ctx, assignment.CourseID, chapters.Filter{Limit: -1})
	if err != nil {
		return Summary{}, err
	}
	now := s.now()
	for _, chapter := range chapterList {
		existing, err := s.store.GetByKey(ctx, assignmentID, employeeID, chapter.ID)
		if err != nil && !errors.Is(err, ErrNotFound) {
			return Summary{}, err
		}
		if errors.Is(err, ErrNotFound) {
			row := Progress{
				ID:              s.newID(),
				AssignmentID:    assignmentID,
				EmployeeID:      employeeID,
				ChapterID:       chapter.ID,
				ProgressPercent: 100,
				Status:          StatusCompleted,
				Detail:          map[string]any{},
				StartedAt:       &now,
				CompletedAt:     &now,
				CreatedAt:       now,
				UpdatedAt:       now,
			}
			if err := s.store.Upsert(ctx, row); err != nil {
				return Summary{}, err
			}
			continue
		}
		row := existing
		row.ProgressPercent = 100
		row.Status = StatusCompleted
		row.CompletedAt = &now
		row.UpdatedAt = now
		if err := s.store.Upsert(ctx, row); err != nil {
			return Summary{}, err
		}
	}
	return s.Summary(ctx, assignmentID, employeeID)
}

// requireAssignment maps a missing assignment to ErrAssignmentNotFound so
// the routing layer can answer 404.
func (s *Service) requireAssignment(ctx context.Context, assignmentID string) (assignments.Assignment, error) {
	assignment, err := s.assignments.Get(ctx, assignmentID)
	if err != nil {
		if errors.Is(err, assignments.ErrNotFound) {
			return assignments.Assignment{}, ErrAssignmentNotFound
		}
		return assignments.Assignment{}, err
	}
	return assignment, nil
}

// requireChapter maps a missing chapter to ErrChapterNotFound so the
// routing layer can answer 404.
func (s *Service) requireChapter(ctx context.Context, chapterID string) (chapters.Chapter, error) {
	chapter, err := s.chapters.Get(ctx, chapterID)
	if err != nil {
		if errors.Is(err, chapters.ErrNotFound) {
			return chapters.Chapter{}, ErrChapterNotFound
		}
		return chapters.Chapter{}, err
	}
	return chapter, nil
}
