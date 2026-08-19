// Package papers implements the exam-paper (试卷) business object of
// prototyped: the model with its validation, the generation_strategy
// rules, a store interface with an in-memory implementation and the
// service layer that also runs automatic paper generation against an
// injected question source. The package never touches a database; a
// PostgreSQL-backed store can be swapped in later behind the same
// interface.
package papers

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// ErrNotFound is returned by the store and service when a paper id does
// not exist. It maps to HTTP 404 in the routing layer.
var ErrNotFound = errors.New("paper not found")

// ValidationError describes a request that violates the papers business
// rules (missing required fields or an invalid generation_strategy). It
// maps to HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// GenerationError reports that automatic paper generation failed because
// the question bank does not hold enough questions for the
// generation_strategy. It maps to HTTP 400 in the routing layer.
type GenerationError struct{ Message string }

func (e *GenerationError) Error() string { return e.Message }

// PassScoreMin and PassScoreMax bound the pass score scale.
const (
	PassScoreMin = 0
	PassScoreMax = 100
)

// Paper is an exam paper as exposed by the API. generation_strategy is
// the automatic-generation configuration: a JSON object mapping question
// types (单选/多选/判断/填空) to non-negative counts with at least one
// positive count. questions is the read-only snapshot produced by
// generation (POST /papers/{id}/generate) and never written by the
// client.
type Paper struct {
	ID                 string             `json:"id"`
	Title              string             `json:"title"`
	DurationMinutes    int                `json:"duration_minutes"`
	PassScore          int                `json:"pass_score"`
	GenerationStrategy map[string]int     `json:"generation_strategy"`
	Questions          []QuestionSnapshot `json:"questions"`
	CreatedBy          string             `json:"created_by"`
	CreatedAt          time.Time          `json:"created_at"`
	UpdatedAt          time.Time          `json:"updated_at"`
}

// QuestionSnapshot is the read-only question snapshot embedded in a
// paper's questions list. Generation projects the picked question-bank
// items onto this shape (id/type/difficulty/content/options/answer) so
// the paper is self-contained; clients never write it.
type QuestionSnapshot struct {
	ID         string                 `json:"id"`
	Type       questions.QuestionType `json:"type"`
	Difficulty int                    `json:"difficulty"`
	Content    string                 `json:"content"`
	Options    []string               `json:"options"`
	Answer     any                    `json:"answer"`
}

// Input carries the client-supplied fields shared by create and update.
// DurationMinutes and PassScore are pointers so a missing field can be
// told apart from an explicit zero (pass_score 0 is legal). The prototype
// has no auth context, so CreatedBy is optional and taken from the
// request body (empty when omitted).
type Input struct {
	Title              string
	DurationMinutes    *int
	PassScore          *int
	GenerationStrategy map[string]any
	CreatedBy          string
}

// Filter selects papers for listing; Limit and Offset paginate the
// matching set (ordered by created_at DESC, id DESC).
type Filter struct {
	Limit  int
	Offset int
}

// normalize validates client input and produces a complete paper. title,
// duration_minutes, pass_score and generation_strategy are required:
// duration_minutes must be positive, pass_score within 0-100 and
// generation_strategy a JSON object whose keys are the four question
// types with non-negative integer values and at least one positive
// count. questions always starts empty (only generation writes it). The
// timestamps and the server-generated id come from the caller.
func normalize(input Input, now time.Time, id string) (Paper, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return Paper{}, &ValidationError{Message: "title required"}
	}
	if input.DurationMinutes == nil {
		return Paper{}, &ValidationError{Message: "duration_minutes required"}
	}
	durationMinutes := *input.DurationMinutes
	if durationMinutes <= 0 {
		return Paper{}, &ValidationError{Message: fmt.Sprintf("invalid duration_minutes: %d", durationMinutes)}
	}
	if input.PassScore == nil {
		return Paper{}, &ValidationError{Message: "pass_score required"}
	}
	passScore := *input.PassScore
	if passScore < PassScoreMin || passScore > PassScoreMax {
		return Paper{}, &ValidationError{Message: fmt.Sprintf("invalid pass_score: %d", passScore)}
	}
	strategy, err := normalizeStrategy(input.GenerationStrategy)
	if err != nil {
		return Paper{}, err
	}
	return Paper{
		ID:                 id,
		Title:              title,
		DurationMinutes:    durationMinutes,
		PassScore:          passScore,
		GenerationStrategy: strategy,
		Questions:          []QuestionSnapshot{},
		CreatedBy:          input.CreatedBy,
		CreatedAt:          now,
		UpdatedAt:          now,
	}, nil
}

// normalizeStrategy validates the raw generation_strategy JSON object and
// converts it to the canonical count map. The keys must be the four
// question-bank types, every value a non-negative integer and at least
// one count positive; anything else is a ValidationError.
func normalizeStrategy(raw map[string]any) (map[string]int, error) {
	if raw == nil {
		return nil, &ValidationError{Message: "generation_strategy required"}
	}
	strategy := make(map[string]int, len(raw))
	total := 0
	for key, value := range raw {
		questionType := questions.QuestionType(key)
		if !questionType.Valid() {
			return nil, &ValidationError{Message: fmt.Sprintf("invalid generation_strategy: unknown type %q", key)}
		}
		count, ok := strategyCount(value)
		if !ok {
			return nil, &ValidationError{Message: fmt.Sprintf("invalid generation_strategy: %s must be a non-negative integer", key)}
		}
		strategy[key] = count
		total += count
	}
	if total == 0 {
		return nil, &ValidationError{Message: "invalid generation_strategy: at least one type must be positive"}
	}
	return strategy, nil
}

// strategyCount normalizes a generation_strategy value to a count. JSON
// numbers decode as float64 (so 1.5 and 1e300 are caught here), while
// in-process callers hand over int/int64 values directly; anything else
// (strings, booleans, null, nested values) is invalid.
func strategyCount(value any) (int, bool) {
	switch number := value.(type) {
	case float64:
		if number != math.Trunc(number) || number < 0 || number > math.MaxInt32 {
			return 0, false
		}
		return int(number), true
	case int:
		if number < 0 {
			return 0, false
		}
		return number, true
	case int64:
		if number < 0 || number > math.MaxInt32 {
			return 0, false
		}
		return int(number), true
	default:
		return 0, false
	}
}
