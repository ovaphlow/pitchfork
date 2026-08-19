package evaluation

import (
	"context"
	"sort"
	"sync"
)

// Store persists the evaluation objects. The prototype ships the
// in-memory implementation; the interface keeps the service layer
// independent of the storage backend. The reference rule of the database
// (an indicator referenced by evaluation_scores cannot be deleted) is
// implemented at the service layer through the injected score-ref
// checker; the store itself only manages the indicator rows.
type Store interface {
	CreateIndicator(ctx context.Context, indicator Indicator) error
	ListIndicators(ctx context.Context, filter IndicatorFilter) ([]Indicator, int, error)
	GetIndicator(ctx context.Context, id string) (Indicator, error)
	UpdateIndicator(ctx context.Context, indicator Indicator) error
	DeleteIndicator(ctx context.Context, id string) error
}

// InMemoryStore keeps the evaluation indicator rows in an insertion-
// ordered slice guarded by a mutex. It implements Store for the
// prototype and never touches a database; a database-backed store
// arrives with a later slice. The listing method returns the rows in
// the repository sort order (dimension, sort_order, created_at
// ascending, id as the deterministic tie-break) and the paginated page.
type InMemoryStore struct {
	mu         sync.Mutex
	indicators []Indicator
}

// NewInMemoryStore returns an empty in-memory evaluation store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// CreateIndicator appends the indicator to the store.
func (s *InMemoryStore) CreateIndicator(_ context.Context, indicator Indicator) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.indicators = append(s.indicators, indicator)
	return nil
}

// ListIndicators returns the indicators matching the filter (dimension
// exact match) ordered by dimension ASC, sort_order ASC, created_at ASC
// (id ASC as the tie-break), the total number of matches and the
// paginated page.
func (s *InMemoryStore) ListIndicators(_ context.Context, filter IndicatorFilter) ([]Indicator, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Indicator, 0, len(s.indicators))
	for _, item := range s.indicators {
		if filter.Dimension != "" && item.Dimension != filter.Dimension {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].Dimension != matched[j].Dimension {
			return matched[i].Dimension < matched[j].Dimension
		}
		if matched[i].SortOrder != matched[j].SortOrder {
			return matched[i].SortOrder < matched[j].SortOrder
		}
		if matched[i].CreatedAt.Equal(matched[j].CreatedAt) {
			return matched[i].ID < matched[j].ID
		}
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]Indicator, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, item)
	}
	return page, total, nil
}

// GetIndicator returns the indicator with the given id, or
// ErrIndicatorNotFound.
func (s *InMemoryStore) GetIndicator(_ context.Context, id string) (Indicator, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfIndicator(id)
	if index < 0 {
		return Indicator{}, ErrIndicatorNotFound
	}
	return s.indicators[index], nil
}

// UpdateIndicator replaces the indicator with the same id, or returns
// ErrIndicatorNotFound.
func (s *InMemoryStore) UpdateIndicator(_ context.Context, indicator Indicator) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfIndicator(indicator.ID)
	if index < 0 {
		return ErrIndicatorNotFound
	}
	s.indicators[index] = indicator
	return nil
}

// DeleteIndicator removes the indicator with the given id, or returns
// ErrIndicatorNotFound.
func (s *InMemoryStore) DeleteIndicator(_ context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfIndicator(id)
	if index < 0 {
		return ErrIndicatorNotFound
	}
	s.indicators = append(s.indicators[:index], s.indicators[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOfIndicator(id string) int {
	for i, item := range s.indicators {
		if item.ID == id {
			return i
		}
	}
	return -1
}

// paginate computes the page bounds for a list of total items: the page
// starts at offset and holds up to limit items (a negative limit means
// no limit).
func paginate(total, limit, offset int) (start, end int) {
	start = offset
	if start > total {
		start = total
	}
	end = start + limit
	if limit < 0 || end > total {
		end = total
	}
	return start, end
}
