package courses

import (
	"context"
	"sync"
)

// Store persists courses. The prototype ships the in-memory
// implementation; the interface keeps the routing and service layers
// independent of the storage backend.
type Store interface {
	Create(ctx context.Context, course Course) error
	List(ctx context.Context, filter Filter) ([]Course, int, error)
	Get(ctx context.Context, id string) (Course, error)
	Update(ctx context.Context, course Course) error
	Delete(ctx context.Context, id string) error
}

// InMemoryStore keeps courses in an insertion-ordered slice guarded by a
// mutex. It implements Store for the prototype and never touches a
// database; a database-backed store arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Course
}

// NewInMemoryStore returns an empty in-memory course store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Create appends the course to the store.
func (s *InMemoryStore) Create(_ context.Context, course Course) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, cloneCourse(course))
	return nil
}

// List returns the courses matching the filter, the total number of
// matches and the paginated page (Limit records starting at Offset).
func (s *InMemoryStore) List(_ context.Context, filter Filter) ([]Course, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var matched []Course
	for _, item := range s.items {
		if filter.Topic != "" && item.Topic != filter.Topic {
			continue
		}
		if filter.Type != "" && item.Type != filter.Type {
			continue
		}
		if filter.Status != "" && item.Status != filter.Status {
			continue
		}
		matched = append(matched, item)
	}
	total := len(matched)
	start := filter.Offset
	if start > total {
		start = total
	}
	end := start + filter.Limit
	if filter.Limit < 0 || end > total {
		end = total
	}
	page := make([]Course, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneCourse(item))
	}
	return page, total, nil
}

// Get returns the course with the given id, or ErrNotFound.
func (s *InMemoryStore) Get(_ context.Context, id string) (Course, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return Course{}, ErrNotFound
	}
	return cloneCourse(s.items[index]), nil
}

// Update replaces the course with the same id, or returns ErrNotFound.
func (s *InMemoryStore) Update(_ context.Context, course Course) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(course.ID)
	if index < 0 {
		return ErrNotFound
	}
	s.items[index] = cloneCourse(course)
	return nil
}

// Delete removes the course with the given id, or returns ErrNotFound.
func (s *InMemoryStore) Delete(_ context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return ErrNotFound
	}
	s.items = append(s.items[:index], s.items[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOf(id string) int {
	for i, item := range s.items {
		if item.ID == id {
			return i
		}
	}
	return -1
}

// cloneCourse copies a course so the caller never aliases the stored
// value; the metadata map is copied as well.
func cloneCourse(course Course) Course {
	cloned := course
	cloned.Metadata = make(map[string]any, len(course.Metadata))
	for key, value := range course.Metadata {
		cloned.Metadata[key] = value
	}
	return cloned
}
