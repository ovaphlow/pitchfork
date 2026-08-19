package questions

import (
	"context"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// Service applies the questions business rules (validation, defaults,
// server-generated ids and timestamps) on top of the store.
type Service struct {
	store Store
	now   func() time.Time
	newID func() string
}

// NewService builds a service over the given store. The server-generated
// id is a 26-character Crockford Base32 ULID.
func NewService(store Store) *Service {
	return &Service{store: store, now: time.Now, newID: ulid.New}
}

// Create validates the input, assigns a server-generated id and the
// timestamps, and stores the new question.
func (s *Service) Create(ctx context.Context, input Input) (Question, error) {
	question, err := normalize(input, s.now(), s.newID())
	if err != nil {
		return Question{}, err
	}
	if err := s.store.Create(ctx, question); err != nil {
		return Question{}, err
	}
	return question, nil
}

// List returns the questions matching the filter and the total number of
// matches (before pagination).
func (s *Service) List(ctx context.Context, filter Filter) ([]Question, int, error) {
	return s.store.List(ctx, filter)
}

// Get returns the question with the given id, or ErrNotFound.
func (s *Service) Get(ctx context.Context, id string) (Question, error) {
	return s.store.Get(ctx, id)
}

// Update validates the input with the same rules as Create, replaces the
// question with the given id and returns the updated record. The original
// creation timestamp is preserved; the update timestamp is refreshed.
func (s *Service) Update(ctx context.Context, id string, input Input) (Question, error) {
	existing, err := s.store.Get(ctx, id)
	if err != nil {
		return Question{}, err
	}
	updated, err := normalize(input, s.now(), id)
	if err != nil {
		return Question{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.Update(ctx, updated); err != nil {
		return Question{}, err
	}
	return updated, nil
}

// Delete removes the question with the given id, or returns ErrNotFound.
func (s *Service) Delete(ctx context.Context, id string) error {
	return s.store.Delete(ctx, id)
}

// ImportDetail describes one failing item of a batch import; Index is the
// position of the item in the request array.
type ImportDetail struct {
	Index   int    `json:"index"`
	Message string `json:"message"`
}

// ImportError reports that a batch import failed; Details lists every
// failing item. Nothing is stored when an import fails.
type ImportError struct{ Details []ImportDetail }

func (e *ImportError) Error() string { return "import failed" }

// Import validates every item of a batch before storing anything. When
// any item is invalid it returns an ImportError with one detail per
// failing item and stores nothing; a valid batch is stored entirely,
// each item getting a server-generated id and timestamps. An empty batch
// is rejected.
func (s *Service) Import(ctx context.Context, inputs []Input) ([]Question, error) {
	if len(inputs) == 0 {
		return nil, &ImportError{Details: []ImportDetail{}}
	}
	questions := make([]Question, 0, len(inputs))
	details := make([]ImportDetail, 0)
	for i, input := range inputs {
		question, err := normalize(input, s.now(), s.newID())
		if err != nil {
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				return nil, err
			}
			details = append(details, ImportDetail{Index: i, Message: validationError.Message})
			continue
		}
		questions = append(questions, question)
	}
	if len(details) > 0 {
		return nil, &ImportError{Details: details}
	}
	for _, question := range questions {
		if err := s.store.Create(ctx, question); err != nil {
			return nil, err
		}
	}
	return questions, nil
}
