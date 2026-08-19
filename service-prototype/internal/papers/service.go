package papers

import (
	"context"
	"fmt"
	"math/rand"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// QuestionSource supplies the question bank for automatic paper
// generation: it returns every question of the given type. The
// production implementation adapts the questions.Store (its List with a
// type filter); tests inject an in-memory fake, keeping the generation
// logic free of any storage.
type QuestionSource interface {
	ListByType(ctx context.Context, questionType questions.QuestionType) ([]questions.Question, error)
}

// maxBankPage asks the question store for every question of a type
// without pagination; the store caps the page at the total.
const maxBankPage = int(^uint(0) >> 1)

// questionSourceAdapter adapts a questions.Store to the QuestionSource
// interface.
type questionSourceAdapter struct {
	store questions.Store
}

// NewQuestionSource returns a QuestionSource backed by the question-bank
// store of the questions package.
func NewQuestionSource(store questions.Store) QuestionSource {
	return &questionSourceAdapter{store: store}
}

// ListByType returns every question of the given type.
func (a *questionSourceAdapter) ListByType(ctx context.Context, questionType questions.QuestionType) ([]questions.Question, error) {
	records, _, err := a.store.List(ctx, questions.Filter{Type: questionType, Limit: maxBankPage})
	if err != nil {
		return nil, err
	}
	return records, nil
}

// generationTypeOrder fixes the canonical order of the four question
// types so the picked questions and the shortage message of a failed
// generation are stable.
var generationTypeOrder = []questions.QuestionType{
	questions.QuestionTypeSingle,
	questions.QuestionTypeMultiple,
	questions.QuestionTypeJudgment,
	questions.QuestionTypeFill,
}

// Service applies the papers business rules (validation, defaults,
// server-generated ids and timestamps) on top of the store and runs
// automatic paper generation against the injected question source.
type Service struct {
	store  Store
	source QuestionSource
	now    func() time.Time
	newID  func() string
}

// NewService builds a service over the given store and question source.
// The server-generated id is a 26-character Crockford Base32 ULID.
func NewService(store Store, source QuestionSource) *Service {
	return &Service{store: store, source: source, now: time.Now, newID: ulid.New}
}

// Create validates the input, assigns a server-generated id and the
// timestamps, and stores the new paper with an empty questions list.
func (s *Service) Create(ctx context.Context, input Input) (Paper, error) {
	paper, err := normalize(input, s.now(), s.newID())
	if err != nil {
		return Paper{}, err
	}
	if err := s.store.Create(ctx, paper); err != nil {
		return Paper{}, err
	}
	return paper, nil
}

// List returns the papers ordered by created_at DESC and the total
// number of papers (before pagination).
func (s *Service) List(ctx context.Context, filter Filter) ([]Paper, int, error) {
	return s.store.List(ctx, filter)
}

// Get returns the paper with the given id, or ErrNotFound.
func (s *Service) Get(ctx context.Context, id string) (Paper, error) {
	return s.store.Get(ctx, id)
}

// Update validates the input with the same rules as Create, replaces the
// paper with the given id and returns the updated record. questions is
// never touched by an update (only generation writes it); the original
// creation timestamp is preserved and the update timestamp is
// refreshed.
func (s *Service) Update(ctx context.Context, id string, input Input) (Paper, error) {
	existing, err := s.store.Get(ctx, id)
	if err != nil {
		return Paper{}, err
	}
	updated, err := normalize(input, s.now(), id)
	if err != nil {
		return Paper{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	updated.Questions = existing.Questions
	if err := s.store.Update(ctx, updated); err != nil {
		return Paper{}, err
	}
	return updated, nil
}

// Delete removes the paper with the given id, or returns ErrNotFound.
func (s *Service) Delete(ctx context.Context, id string) error {
	return s.store.Delete(ctx, id)
}

// Generate runs automatic paper generation for the paper with the given
// id: it picks questions from the question source according to the
// latest generation_strategy (random selection per type) and overwrites
// the paper's questions with the result. A strategy the question bank
// cannot satisfy returns a GenerationError whose message describes the
// shortage per type (e.g. 「题库不足：单选缺 2 题、多选缺 1 题」) and
// leaves the paper untouched; repeated generation overwrites the
// previous result.
func (s *Service) Generate(ctx context.Context, id string) (Paper, error) {
	paper, err := s.store.Get(ctx, id)
	if err != nil {
		return Paper{}, err
	}
	picked, err := s.generateQuestions(ctx, paper.GenerationStrategy)
	if err != nil {
		return Paper{}, err
	}
	paper.Questions = picked
	paper.UpdatedAt = s.now()
	if err := s.store.Update(ctx, paper); err != nil {
		return Paper{}, err
	}
	return paper, nil
}

// generateQuestions picks need questions per type from the question
// source. A type whose bank holds fewer questions than the strategy asks
// for is recorded as a shortage instead of picking; when any type is
// short the whole generation fails with the per-type gap description.
func (s *Service) generateQuestions(ctx context.Context, strategy map[string]int) ([]QuestionSnapshot, error) {
	picked := make([]QuestionSnapshot, 0, len(strategy))
	var missing []string
	for _, questionType := range generationTypeOrder {
		need := strategy[string(questionType)]
		if need == 0 {
			continue
		}
		bank, err := s.source.ListByType(ctx, questionType)
		if err != nil {
			return nil, err
		}
		if len(bank) < need {
			missing = append(missing, fmt.Sprintf("%s缺 %d 题", questionType, need-len(bank)))
			continue
		}
		picked = append(picked, pickRandom(bank, need)...)
	}
	if len(missing) > 0 {
		return nil, &GenerationError{Message: "题库不足：" + strings.Join(missing, "、")}
	}
	return picked, nil
}

// pickRandom selects need distinct questions out of bank uniformly at
// random (partial Fisher-Yates shuffle) and projects them onto
// snapshots. The caller guarantees len(bank) >= need.
func pickRandom(bank []questions.Question, need int) []QuestionSnapshot {
	shuffled := append([]questions.Question(nil), bank...)
	rand.Shuffle(len(shuffled), func(i, j int) {
		shuffled[i], shuffled[j] = shuffled[j], shuffled[i]
	})
	snapshots := make([]QuestionSnapshot, 0, need)
	for _, question := range shuffled[:need] {
		snapshots = append(snapshots, snapshotOf(question))
	}
	return snapshots
}

// snapshotOf projects a question-bank item onto the read-only snapshot
// stored in the paper: id/type/difficulty/content/options/answer.
func snapshotOf(question questions.Question) QuestionSnapshot {
	snapshot := QuestionSnapshot{
		ID:         question.ID,
		Type:       question.Type,
		Difficulty: question.Difficulty,
		Content:    question.Content,
		Options:    append([]string(nil), question.Options...),
	}
	if values, ok := question.Answer.([]any); ok {
		snapshot.Answer = append([]any(nil), values...)
	} else {
		snapshot.Answer = question.Answer
	}
	return snapshot
}
