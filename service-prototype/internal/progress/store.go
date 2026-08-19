package progress

import (
	"context"
	"sync"
)

// Store persists learning-progress rows. The prototype ships the
// in-memory implementation; the interface keeps the routing and service
// layers independent of the storage backend. Upsert creates the row on
// the first report of a chapter and replaces it on later reports, keyed
// by (assignment_id, employee_id, chapter_id) — the unique key of the
// table.
type Store interface {
	Upsert(ctx context.Context, progress Progress) error
	GetByKey(ctx context.Context, assignmentID, employeeID, chapterID string) (Progress, error)
	ListByAssignment(ctx context.Context, assignmentID, employeeID string) ([]Progress, error)
}

// InMemoryStore keeps progress rows in an insertion-ordered slice
// guarded by a mutex. Upsert deduplicates on the unique key, so a second
// report of the same chapter never grows the store. It implements Store
// for the prototype and never touches a database; a database-backed
// store arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Progress
}

// NewInMemoryStore returns an empty in-memory progress store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Upsert creates the row, or replaces the row with the same
// (assignment_id, employee_id, chapter_id) key. A second report of the
// same chapter updates the stored row in place instead of inserting a
// duplicate.
func (s *InMemoryStore) Upsert(_ context.Context, progress Progress) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.items {
		if sameKey(item, progress) {
			s.items[i] = cloneProgress(progress)
			return nil
		}
	}
	s.items = append(s.items, cloneProgress(progress))
	return nil
}

// GetByKey returns the row of one (assignment, employee, chapter)
// triple, or ErrNotFound.
func (s *InMemoryStore) GetByKey(_ context.Context, assignmentID, employeeID, chapterID string) (Progress, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, item := range s.items {
		if item.AssignmentID == assignmentID && item.EmployeeID == employeeID && item.ChapterID == chapterID {
			return cloneProgress(item), nil
		}
	}
	return Progress{}, ErrNotFound
}

// ListByAssignment returns every row of one (assignment, employee) pair.
// Rows of other employees or assignments never leak into the result.
func (s *InMemoryStore) ListByAssignment(_ context.Context, assignmentID, employeeID string) ([]Progress, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var rows []Progress
	for _, item := range s.items {
		if item.AssignmentID == assignmentID && item.EmployeeID == employeeID {
			rows = append(rows, cloneProgress(item))
		}
	}
	return rows, nil
}

// sameKey reports whether two rows address the same unique key.
func sameKey(a, b Progress) bool {
	return a.AssignmentID == b.AssignmentID && a.EmployeeID == b.EmployeeID && a.ChapterID == b.ChapterID
}

// cloneProgress copies a progress row so the caller never aliases the
// stored value; the detail map and the nullable timestamps are copied as
// well.
func cloneProgress(progress Progress) Progress {
	cloned := progress
	cloned.Detail = make(map[string]any, len(progress.Detail))
	for key, value := range progress.Detail {
		cloned.Detail[key] = value
	}
	if progress.StartedAt != nil {
		started := *progress.StartedAt
		cloned.StartedAt = &started
	}
	if progress.CompletedAt != nil {
		completed := *progress.CompletedAt
		cloned.CompletedAt = &completed
	}
	return cloned
}
