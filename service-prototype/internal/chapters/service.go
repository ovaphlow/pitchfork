package chapters

import (
	"context"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// CourseLookup is the subset of the courses store the chapter service
// needs: it only checks that a parent course exists before chapter
// operations run. Injected at the composition root so the chapter service
// never owns a course store.
type CourseLookup interface {
	Get(ctx context.Context, id string) (courses.Course, error)
}

// Service applies the chapters business rules (validation, defaults,
// course existence checks, server-generated ids and timestamps) on top of
// the store.
type Service struct {
	store   Store
	courses CourseLookup
	now     func() time.Time
	newID   func() string
}

// NewService builds a service over the given store and course lookup.
// The server-generated id is a 26-character Crockford Base32 ULID.
func NewService(store Store, courses CourseLookup) *Service {
	return &Service{store: store, courses: courses, now: time.Now, newID: ulid.New}
}

// Create checks that the parent course exists, validates the input,
// assigns a server-generated id and the timestamps, and stores the new
// chapter.
func (s *Service) Create(ctx context.Context, courseID string, input Input) (Chapter, error) {
	if err := s.requireCourse(ctx, courseID); err != nil {
		return Chapter{}, err
	}
	chapter, err := normalize(input, s.now(), s.newID(), courseID)
	if err != nil {
		return Chapter{}, err
	}
	if err := s.store.Create(ctx, chapter); err != nil {
		return Chapter{}, err
	}
	return chapter, nil
}

// List checks that the parent course exists and returns the chapters of
// the course in sort order plus the total number (before pagination).
func (s *Service) List(ctx context.Context, courseID string, filter Filter) ([]Chapter, int, error) {
	if err := s.requireCourse(ctx, courseID); err != nil {
		return nil, 0, err
	}
	return s.store.ListByCourse(ctx, courseID, filter)
}

// Get returns the chapter with the given id, or ErrNotFound.
func (s *Service) Get(ctx context.Context, id string) (Chapter, error) {
	return s.store.Get(ctx, id)
}

// Update validates the input with the same rules as Create, replaces the
// chapter with the given id and returns the updated record. The owning
// course id comes from the stored record (a chapter never changes its
// course); the original creation timestamp is preserved; the update
// timestamp is refreshed.
func (s *Service) Update(ctx context.Context, id string, input Input) (Chapter, error) {
	existing, err := s.store.Get(ctx, id)
	if err != nil {
		return Chapter{}, err
	}
	updated, err := normalize(input, s.now(), id, existing.CourseID)
	if err != nil {
		return Chapter{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.Update(ctx, updated); err != nil {
		return Chapter{}, err
	}
	return updated, nil
}

// Delete removes the chapter with the given id, or returns ErrNotFound.
func (s *Service) Delete(ctx context.Context, id string) error {
	return s.store.Delete(ctx, id)
}

// requireCourse maps a missing parent course to ErrCourseNotFound so the
// routing layer can answer 404 before any chapter operation runs.
func (s *Service) requireCourse(ctx context.Context, courseID string) error {
	if _, err := s.courses.Get(ctx, courseID); err != nil {
		if errors.Is(err, courses.ErrNotFound) {
			return ErrCourseNotFound
		}
		return err
	}
	return nil
}
