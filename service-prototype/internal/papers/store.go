package papers

import (
	"context"
	"sort"
	"sync"
)

// Store persists papers. The prototype ships the in-memory
// implementation; the interface keeps the routing and service layers
// independent of the storage backend.
type Store interface {
	Create(ctx context.Context, paper Paper) error
	List(ctx context.Context, filter Filter) ([]Paper, int, error)
	Get(ctx context.Context, id string) (Paper, error)
	Update(ctx context.Context, paper Paper) error
	Delete(ctx context.Context, id string) error
}

// InMemoryStore keeps papers in a slice guarded by a mutex. It
// implements Store for the prototype and never touches a database; a
// database-backed store arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Paper
}

// NewInMemoryStore returns an empty in-memory paper store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Create appends the paper to the store.
func (s *InMemoryStore) Create(_ context.Context, paper Paper) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, clonePaper(paper))
	return nil
}

// List returns the papers ordered by created_at DESC (ties broken by id
// DESC), the total number of papers and the paginated page (Limit
// records starting at Offset). The store sorts a copy, so the insertion
// order must not matter.
func (s *InMemoryStore) List(_ context.Context, filter Filter) ([]Paper, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sorted := make([]Paper, len(s.items))
	copy(sorted, s.items)
	sort.SliceStable(sorted, func(i, j int) bool {
		if !sorted[i].CreatedAt.Equal(sorted[j].CreatedAt) {
			return sorted[i].CreatedAt.After(sorted[j].CreatedAt)
		}
		return sorted[i].ID > sorted[j].ID
	})
	total := len(sorted)
	start := filter.Offset
	if start > total {
		start = total
	}
	end := start + filter.Limit
	if filter.Limit < 0 || end > total {
		end = total
	}
	page := make([]Paper, 0, end-start)
	for _, item := range sorted[start:end] {
		page = append(page, clonePaper(item))
	}
	return page, total, nil
}

// Get returns the paper with the given id, or ErrNotFound.
func (s *InMemoryStore) Get(_ context.Context, id string) (Paper, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return Paper{}, ErrNotFound
	}
	return clonePaper(s.items[index]), nil
}

// Update replaces the paper with the same id, or returns ErrNotFound.
func (s *InMemoryStore) Update(_ context.Context, paper Paper) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(paper.ID)
	if index < 0 {
		return ErrNotFound
	}
	s.items[index] = clonePaper(paper)
	return nil
}

// Delete removes the paper with the given id, or returns ErrNotFound.
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

// clonePaper copies a paper so the caller never aliases the stored
// value; the strategy map, the question snapshots and their nested
// slices and answer arrays are copied as well.
func clonePaper(paper Paper) Paper {
	cloned := paper
	cloned.GenerationStrategy = make(map[string]int, len(paper.GenerationStrategy))
	for key, value := range paper.GenerationStrategy {
		cloned.GenerationStrategy[key] = value
	}
	cloned.Questions = make([]QuestionSnapshot, len(paper.Questions))
	for i, snapshot := range paper.Questions {
		cloned.Questions[i] = cloneSnapshot(snapshot)
	}
	return cloned
}

// cloneSnapshot copies a question snapshot including its options slice
// and answer array.
func cloneSnapshot(snapshot QuestionSnapshot) QuestionSnapshot {
	cloned := snapshot
	cloned.Options = append([]string(nil), snapshot.Options...)
	if values, ok := snapshot.Answer.([]any); ok {
		cloned.Answer = append([]any(nil), values...)
	}
	return cloned
}
