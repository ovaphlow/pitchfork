package assignments

import (
	"context"
	"sort"
	"sync"
)

// Store persists assignments. The prototype ships the in-memory
// implementation; the interface keeps the routing and service layers
// independent of the storage backend.
type Store interface {
	Create(ctx context.Context, assignment Assignment) error
	List(ctx context.Context, filter Filter) ([]Assignment, int, error)
	Get(ctx context.Context, id string) (Assignment, error)
	Delete(ctx context.Context, id string) error
}

// InMemoryStore keeps assignments in an insertion-ordered slice guarded
// by a mutex. Listing filters a copy and sorts it by created_at
// descending (ties keep insertion order), so the newest assignment comes
// first regardless of insertion order. It implements Store for the
// prototype and never touches a database; a database-backed store
// arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Assignment
}

// NewInMemoryStore returns an empty in-memory assignment store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Create appends the assignment to the store.
func (s *InMemoryStore) Create(_ context.Context, assignment Assignment) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, cloneAssignment(assignment))
	return nil
}

// List returns the assignments matching the filter (course_id and
// target_type exact match; employee_id matches only 用户 assignments
// whose target_ids contain the id), sorted by created_at descending, the
// total number of matches and the paginated page (Limit records starting
// at Offset).
func (s *InMemoryStore) List(_ context.Context, filter Filter) ([]Assignment, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var matched []Assignment
	for _, item := range s.items {
		if filter.CourseID != "" && item.CourseID != filter.CourseID {
			continue
		}
		if filter.TargetType != "" && item.TargetType != filter.TargetType {
			continue
		}
		if filter.EmployeeID != "" && !matchesEmployee(item, filter.EmployeeID) {
			continue
		}
		matched = append(matched, item)
	}
	// Newest first; ties keep insertion order (stable sort).
	sort.SliceStable(matched, func(i, j int) bool {
		return matched[i].CreatedAt.After(matched[j].CreatedAt)
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
	page := make([]Assignment, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneAssignment(item))
	}
	return page, total, nil
}

// Get returns the assignment with the given id, or ErrNotFound.
func (s *InMemoryStore) Get(_ context.Context, id string) (Assignment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return Assignment{}, ErrNotFound
	}
	return cloneAssignment(s.items[index]), nil
}

// Delete removes the assignment with the given id, or returns
// ErrNotFound.
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

// matchesEmployee reports whether an assignment is hit by an employee
// expansion: only 用户 assignments whose target_ids contain the employee
// id. With no master data for 岗位/部门, those target types never match
// an employee id.
func matchesEmployee(assignment Assignment, employeeID string) bool {
	if assignment.TargetType != TargetTypeUser {
		return false
	}
	for _, targetID := range assignment.TargetIDs {
		if targetID == employeeID {
			return true
		}
	}
	return false
}

func (s *InMemoryStore) indexOf(id string) int {
	for i, item := range s.items {
		if item.ID == id {
			return i
		}
	}
	return -1
}

// cloneAssignment copies an assignment so the caller never aliases the
// stored value; the trigger_rule map and the target_ids slice are copied
// as well.
func cloneAssignment(assignment Assignment) Assignment {
	cloned := assignment
	cloned.TriggerRule = make(map[string]any, len(assignment.TriggerRule))
	for key, value := range assignment.TriggerRule {
		cloned.TriggerRule[key] = value
	}
	cloned.TargetIDs = append([]string(nil), assignment.TargetIDs...)
	return cloned
}
