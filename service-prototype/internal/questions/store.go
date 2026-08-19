package questions

import (
	"context"
	"sync"
)

// Store persists questions. The prototype ships the in-memory
// implementation; the interface keeps the routing and service layers
// independent of the storage backend.
type Store interface {
	Create(ctx context.Context, question Question) error
	List(ctx context.Context, filter Filter) ([]Question, int, error)
	Get(ctx context.Context, id string) (Question, error)
	Update(ctx context.Context, question Question) error
	Delete(ctx context.Context, id string) error
}

// InMemoryStore keeps questions in an insertion-ordered slice guarded by
// a mutex. It implements Store for the prototype and never touches a
// database; a database-backed store arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Question
}

// NewInMemoryStore returns an empty in-memory question store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Create appends the question to the store.
func (s *InMemoryStore) Create(_ context.Context, question Question) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, cloneQuestion(question))
	return nil
}

// List returns the questions matching the filter (type and difficulty
// exact match, tags AND match), the total number of matches and the
// paginated page (Limit records starting at Offset).
func (s *InMemoryStore) List(_ context.Context, filter Filter) ([]Question, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var matched []Question
	for _, item := range s.items {
		if filter.Type != "" && item.Type != filter.Type {
			continue
		}
		if filter.Difficulty != 0 && item.Difficulty != filter.Difficulty {
			continue
		}
		if !containsAllTags(item.Tags, filter.Tags) {
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
	page := make([]Question, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneQuestion(item))
	}
	return page, total, nil
}

// Get returns the question with the given id, or ErrNotFound.
func (s *InMemoryStore) Get(_ context.Context, id string) (Question, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return Question{}, ErrNotFound
	}
	return cloneQuestion(s.items[index]), nil
}

// Update replaces the question with the same id, or returns ErrNotFound.
func (s *InMemoryStore) Update(_ context.Context, question Question) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(question.ID)
	if index < 0 {
		return ErrNotFound
	}
	s.items[index] = cloneQuestion(question)
	return nil
}

// Delete removes the question with the given id, or returns ErrNotFound.
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

// containsAllTags reports whether have carries every tag in want.
func containsAllTags(have, want []string) bool {
	for _, tag := range want {
		if !contains(have, tag) {
			return false
		}
	}
	return true
}

// cloneQuestion copies a question so the caller never aliases the stored
// value; the slices, the answer array and the metadata map are copied as
// well.
func cloneQuestion(question Question) Question {
	cloned := question
	cloned.Tags = append([]string(nil), question.Tags...)
	cloned.Options = append([]string(nil), question.Options...)
	cloned.Metadata = make(map[string]any, len(question.Metadata))
	for key, value := range question.Metadata {
		cloned.Metadata[key] = value
	}
	if values, ok := question.Answer.([]any); ok {
		cloned.Answer = append([]any(nil), values...)
	}
	return cloned
}
