package chapters

import (
	"context"
	"sort"
	"sync"
)

// Store persists chapters. The prototype ships the in-memory
// implementation; the interface keeps the routing and service layers
// independent of the storage backend. DeleteByCourse removes every
// chapter of a course and backs the cascade delete of the courses
// service (in the database the foreign key carries ON DELETE CASCADE).
type Store interface {
	Create(ctx context.Context, chapter Chapter) error
	ListByCourse(ctx context.Context, courseID string, filter Filter) ([]Chapter, int, error)
	Get(ctx context.Context, id string) (Chapter, error)
	Update(ctx context.Context, chapter Chapter) error
	Delete(ctx context.Context, id string) error
	DeleteByCourse(ctx context.Context, courseID string) error
}

// InMemoryStore keeps chapters in an insertion-ordered slice guarded by a
// mutex. Listing sorts a copy by sort_order (ties broken by created_at),
// so the store stays independent of insertion order. It implements Store
// for the prototype and never touches a database; a database-backed store
// arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Chapter
}

// NewInMemoryStore returns an empty in-memory chapter store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Create appends the chapter to the store.
func (s *InMemoryStore) Create(_ context.Context, chapter Chapter) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, cloneChapter(chapter))
	return nil
}

// ListByCourse returns the chapters of the course in sort order
// (sort_order ascending, ties broken by created_at ascending), the total
// number of chapters of the course and the paginated page (Limit records
// starting at Offset).
func (s *InMemoryStore) ListByCourse(_ context.Context, courseID string, filter Filter) ([]Chapter, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var matched []Chapter
	for _, item := range s.items {
		if item.CourseID == courseID {
			matched = append(matched, item)
		}
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].SortOrder != matched[j].SortOrder {
			return matched[i].SortOrder < matched[j].SortOrder
		}
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	total := len(matched)
	start := filter.Offset
	if start > total {
		start = total
	}
	end := start + filter.Limit
	if filter.Limit < 0 || end > total {
		end = total
	}
	page := make([]Chapter, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneChapter(item))
	}
	return page, total, nil
}

// Get returns the chapter with the given id, or ErrNotFound.
func (s *InMemoryStore) Get(_ context.Context, id string) (Chapter, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return Chapter{}, ErrNotFound
	}
	return cloneChapter(s.items[index]), nil
}

// Update replaces the chapter with the same id, or returns ErrNotFound.
func (s *InMemoryStore) Update(_ context.Context, chapter Chapter) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(chapter.ID)
	if index < 0 {
		return ErrNotFound
	}
	s.items[index] = cloneChapter(chapter)
	return nil
}

// Delete removes the chapter with the given id, or returns ErrNotFound.
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

// DeleteByCourse removes every chapter of the given course. It is a
// no-op when the course has no chapters and never fails.
func (s *InMemoryStore) DeleteByCourse(_ context.Context, courseID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.items[:0]
	for _, item := range s.items {
		if item.CourseID != courseID {
			kept = append(kept, item)
		}
	}
	s.items = kept
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

// cloneChapter copies a chapter so the caller never aliases the stored
// value; the blocks slice and each block map are copied as well.
func cloneChapter(chapter Chapter) Chapter {
	cloned := chapter
	if chapter.Blocks != nil {
		cloned.Blocks = make([]map[string]any, len(chapter.Blocks))
		for i, block := range chapter.Blocks {
			copied := make(map[string]any, len(block))
			for key, value := range block {
				copied[key] = value
			}
			cloned.Blocks[i] = copied
		}
	}
	return cloned
}
