package examrecords

import (
	"context"
	"sort"
	"sync"
)

// Store persists exam records. The prototype ships the in-memory
// implementation; the interface keeps the routing and service layers
// independent of the storage backend. Submission mutates a record in
// place through Update (end_time/score/passed), so a record is created
// once by Create and replaced by Update; there is no delete in the card
// scope.
type Store interface {
	Create(ctx context.Context, record Record) error
	List(ctx context.Context, filter Filter) ([]Record, int, error)
	Get(ctx context.Context, id string) (Record, error)
	Update(ctx context.Context, record Record) error
}

// InMemoryStore keeps exam records in a slice guarded by a mutex. It
// implements Store for the prototype and never touches a database; a
// database-backed store arrives with a later slice.
type InMemoryStore struct {
	mu    sync.Mutex
	items []Record
}

// NewInMemoryStore returns an empty in-memory exam-record store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// Create appends the record to the store.
func (s *InMemoryStore) Create(_ context.Context, record Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, cloneRecord(record))
	return nil
}

// List returns the records matching the filter (employee_id and
// paper_id exact matches; empty values match everything) ordered by
// created_at DESC (ties broken by id DESC), the total number of
// matching records and the paginated page (Limit records starting at
// Offset). The store sorts a copy, so the insertion order must not
// matter.
func (s *InMemoryStore) List(_ context.Context, filter Filter) ([]Record, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matching := make([]Record, 0, len(s.items))
	for _, item := range s.items {
		if filter.EmployeeID != "" && item.EmployeeID != filter.EmployeeID {
			continue
		}
		if filter.PaperID != "" && item.PaperID != filter.PaperID {
			continue
		}
		matching = append(matching, item)
	}
	sort.SliceStable(matching, func(i, j int) bool {
		if !matching[i].CreatedAt.Equal(matching[j].CreatedAt) {
			return matching[i].CreatedAt.After(matching[j].CreatedAt)
		}
		return matching[i].ID > matching[j].ID
	})
	total := len(matching)
	start := filter.Offset
	if start > total {
		start = total
	}
	end := start + filter.Limit
	if filter.Limit < 0 || end > total {
		end = total
	}
	page := make([]Record, 0, end-start)
	for _, item := range matching[start:end] {
		page = append(page, cloneRecord(item))
	}
	return page, total, nil
}

// Get returns the record with the given id, or ErrNotFound.
func (s *InMemoryStore) Get(_ context.Context, id string) (Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(id)
	if index < 0 {
		return Record{}, ErrNotFound
	}
	return cloneRecord(s.items[index]), nil
}

// Update replaces the record with the same id (used by submission to
// write end_time/score/passed), or returns ErrNotFound.
func (s *InMemoryStore) Update(_ context.Context, record Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOf(record.ID)
	if index < 0 {
		return ErrNotFound
	}
	s.items[index] = cloneRecord(record)
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

// cloneRecord copies a record so the caller never aliases the stored
// value; the snapshot questions with their nested slices and answer
// arrays, the metadata map and the pointer fields are copied as well.
func cloneRecord(record Record) Record {
	cloned := record
	if record.EndTime != nil {
		endTime := *record.EndTime
		cloned.EndTime = &endTime
	}
	if record.Score != nil {
		score := *record.Score
		cloned.Score = &score
	}
	if record.Passed != nil {
		passed := *record.Passed
		cloned.Passed = &passed
	}
	cloned.Metadata = make(map[string]any, len(record.Metadata))
	for key, value := range record.Metadata {
		cloned.Metadata[key] = value
	}
	cloned.AnswersSnapshot = cloneSnapshot(record.AnswersSnapshot)
	return cloned
}

// cloneSnapshot copies an exam snapshot including the question list,
// the options slices and the answer arrays.
func cloneSnapshot(snapshot Snapshot) Snapshot {
	cloned := snapshot
	cloned.Questions = make([]QuestionSnapshot, len(snapshot.Questions))
	for i, question := range snapshot.Questions {
		cloned.Questions[i] = question
		cloned.Questions[i].Options = append([]string(nil), question.Options...)
		cloned.Questions[i].Answer = cloneAnswer(question.Answer)
	}
	return cloned
}

// cloneAnswer copies the answer of a question: a string is immutable
// and passed through, an array is copied so the caller never aliases
// the stored value.
func cloneAnswer(answer any) any {
	if values, ok := answer.([]any); ok {
		return append([]any(nil), values...)
	}
	if values, ok := answer.([]string); ok {
		return append([]string(nil), values...)
	}
	return answer
}
