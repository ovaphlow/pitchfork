package courses

import (
	"context"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// Service applies the courses business rules (validation, defaults,
// server-generated ids and timestamps) on top of the store.
type Service struct {
	store    Store
	chapters ChapterCleaner // nil until wired: cascade deletion of chapters
	now      func() time.Time
	newID    func() string
}

// NewService builds a service over the given store. The server-generated
// id is a 26-character Crockford Base32 ULID.
func NewService(store Store) *Service {
	return &Service{store: store, now: time.Now, newID: ulid.New}
}

// Create validates the input, assigns a server-generated id and the
// timestamps, and stores the new course.
func (s *Service) Create(ctx context.Context, input Input) (Course, error) {
	course, err := normalize(input, s.now(), s.newID())
	if err != nil {
		return Course{}, err
	}
	if err := s.store.Create(ctx, course); err != nil {
		return Course{}, err
	}
	return course, nil
}

// List returns the courses matching the filter and the total number of
// matches (before pagination).
func (s *Service) List(ctx context.Context, filter Filter) ([]Course, int, error) {
	return s.store.List(ctx, filter)
}

// Get returns the course with the given id, or ErrNotFound.
func (s *Service) Get(ctx context.Context, id string) (Course, error) {
	return s.store.Get(ctx, id)
}

// Update validates the input with the same rules as Create, replaces the
// course with the given id and returns the updated record. The original
// creation timestamp is preserved; the update timestamp is refreshed.
func (s *Service) Update(ctx context.Context, id string, input Input) (Course, error) {
	existing, err := s.store.Get(ctx, id)
	if err != nil {
		return Course{}, err
	}
	updated, err := normalize(input, s.now(), id)
	if err != nil {
		return Course{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.Update(ctx, updated); err != nil {
		return Course{}, err
	}
	return updated, nil
}

// ChapterCleaner removes every chapter of a course. Deleting a course
// cascades to its chapters through this injected dependency (the database
// carries ON DELETE CASCADE; the in-memory store implements the same
// rule here). Wired at the composition root, never by the routing layer.
type ChapterCleaner interface {
	DeleteByCourse(ctx context.Context, courseID string) error
}

// SetChapterCleaner wires cascade deletion: from then on Delete removes
// the chapters of the course together with the course itself. Calling it
// is optional; without a cleaner Delete behaves exactly as before.
func (s *Service) SetChapterCleaner(cleaner ChapterCleaner) {
	s.chapters = cleaner
}

// Delete removes the course with the given id, or returns ErrNotFound.
// When a chapter cleaner is wired, the chapters of the course are
// removed together with the course (cascade delete). The chapters are
// removed first so a failing cleanup cannot leave the course deleted
// while its chapters survive.
func (s *Service) Delete(ctx context.Context, id string) error {
	// Verify the course exists first: a missing course must still answer
	// ErrNotFound without touching any chapters.
	if _, err := s.store.Get(ctx, id); err != nil {
		return err
	}
	if s.chapters != nil {
		if err := s.chapters.DeleteByCourse(ctx, id); err != nil {
			return err
		}
	}
	return s.store.Delete(ctx, id)
}
