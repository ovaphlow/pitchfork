package assignments

import (
	"context"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// CourseLookup is the subset of the courses store the assignment service
// needs: it only checks that the assigned course exists before an
// assignment is stored. Injected at the composition root so the
// assignment service never owns a course store.
type CourseLookup interface {
	Get(ctx context.Context, id string) (courses.Course, error)
}

// Service applies the assignments business rules (validation, defaults,
// course existence checks, server-generated ids and timestamps) on top
// of the store.
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

// Create validates the input (course_id required and every enum/format
// rule), checks that the course exists, assigns a server-generated id
// and the timestamps, and stores the new assignment.
func (s *Service) Create(ctx context.Context, input Input) (Assignment, error) {
	assignment, err := normalize(input, s.now(), s.newID())
	if err != nil {
		return Assignment{}, err
	}
	if err := s.requireCourse(ctx, assignment.CourseID); err != nil {
		return Assignment{}, err
	}
	if err := s.store.Create(ctx, assignment); err != nil {
		return Assignment{}, err
	}
	return assignment, nil
}

// List returns the assignments matching the filter and the total number
// of matches (before pagination). A course_id filter pointing to a
// missing course is not an error: it simply matches nothing.
func (s *Service) List(ctx context.Context, filter Filter) ([]Assignment, int, error) {
	return s.store.List(ctx, filter)
}

// Get returns the assignment with the given id, or ErrNotFound. It is
// used by dependent modules (learning progress) to validate that an
// assignment exists and to resolve its course.
func (s *Service) Get(ctx context.Context, id string) (Assignment, error) {
	return s.store.Get(ctx, id)
}

// Delete removes the assignment with the given id, or returns
// ErrNotFound.
func (s *Service) Delete(ctx context.Context, id string) error {
	return s.store.Delete(ctx, id)
}

// requireCourse maps a missing course to ErrCourseNotFound so the
// routing layer can answer 404 before the assignment is stored.
func (s *Service) requireCourse(ctx context.Context, courseID string) error {
	if _, err := s.courses.Get(ctx, courseID); err != nil {
		if errors.Is(err, courses.ErrNotFound) {
			return ErrCourseNotFound
		}
		return err
	}
	return nil
}
