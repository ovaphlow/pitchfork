// Package questions implements the question-bank business object of
// prototyped: the model with its enum and type-linked validation, a store
// interface with an in-memory implementation, and the service layer. The
// package never touches a database; a PostgreSQL-backed store can be
// swapped in later behind the same interface.
package questions

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrNotFound is returned by the store and service when a question id
// does not exist. It maps to HTTP 404 in the routing layer.
var ErrNotFound = errors.New("question not found")

// ValidationError describes a request that violates the questions
// business rules (missing required fields, invalid enum values or
// type-linked options/answer mismatches). It maps to HTTP 400 in the
// routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// QuestionType is the question type (题型) of a question-bank item.
type QuestionType string

const (
	QuestionTypeSingle   QuestionType = "单选"
	QuestionTypeMultiple QuestionType = "多选"
	QuestionTypeJudgment QuestionType = "判断"
	QuestionTypeFill     QuestionType = "填空"
)

var validQuestionTypes = []QuestionType{
	QuestionTypeSingle,
	QuestionTypeMultiple,
	QuestionTypeJudgment,
	QuestionTypeFill,
}

// Valid reports whether questionType is one of the allowed type values.
func (questionType QuestionType) Valid() bool {
	for _, candidate := range validQuestionTypes {
		if questionType == candidate {
			return true
		}
	}
	return false
}

// Judgment answers allowed for 判断 questions.
const (
	JudgmentAnswerTrue  = "正确"
	JudgmentAnswerFalse = "错误"
)

// DifficultyMin and DifficultyMax bound the difficulty scale.
const (
	DifficultyMin = 1
	DifficultyMax = 5
)

// Question is a question-bank item as exposed by the API. The database
// stores content/options/answer/explanation as JSONB; the API models them
// as a string, a string array, a string or string array (by type) and a
// string respectively. Metadata follows the repository JSONB
// extension-field convention and is always present (an omitted request
// field is stored and echoed as an empty object).
type Question struct {
	ID          string         `json:"id"`
	Type        QuestionType   `json:"type"`
	Difficulty  int            `json:"difficulty"`
	Tags        []string       `json:"tags"`
	Content     string         `json:"content"`
	Options     []string       `json:"options"`
	Answer      any            `json:"answer"`
	Explanation string         `json:"explanation"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

// Input carries the client-supplied fields shared by create, update and
// import. The prototype has no auth context, so CreatedBy is optional and
// taken from the request body (empty when omitted).
type Input struct {
	Type        QuestionType
	Difficulty  int
	Tags        []string
	Content     string
	Options     []string
	Answer      any
	Explanation string
	Metadata    map[string]any
	CreatedBy   string
}

// Filter selects questions for listing. Empty enum values match
// everything; Tags are matched with AND semantics (a question must carry
// every given tag); Limit and Offset paginate the matching set.
type Filter struct {
	Type       QuestionType
	Difficulty int // 0 = unset; the valid range 1-5 never collides
	Tags       []string
	Limit      int
	Offset     int
}

// normalize validates client input and produces a complete question.
// type, difficulty, content and answer are required; the options and
// answer rules depend on the type; tags, explanation and metadata default
// to []/""/{}. The timestamps and the server-generated id come from the
// caller.
func normalize(input Input, now time.Time, id string) (Question, error) {
	if !input.Type.Valid() {
		return Question{}, &ValidationError{Message: fmt.Sprintf("invalid type: %q", input.Type)}
	}
	if input.Difficulty < DifficultyMin || input.Difficulty > DifficultyMax {
		return Question{}, &ValidationError{Message: fmt.Sprintf("invalid difficulty: %d", input.Difficulty)}
	}
	content := strings.TrimSpace(input.Content)
	if content == "" {
		return Question{}, &ValidationError{Message: "content required"}
	}
	options, answer, err := normalizeAnswer(input.Type, input.Options, input.Answer)
	if err != nil {
		return Question{}, err
	}
	tags := input.Tags
	if tags == nil {
		tags = []string{}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Question{
		ID:          id,
		Type:        input.Type,
		Difficulty:  input.Difficulty,
		Tags:        tags,
		Content:     content,
		Options:     options,
		Answer:      answer,
		Explanation: input.Explanation,
		Metadata:    metadata,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// normalizeAnswer applies the type-linked rules for options and answer:
// 单选/多选 need at least two string options and an answer drawn from
// them (多选 as a non-empty subset array); 判断 answers exactly
// 正确/错误; 填空 answers a non-blank string. 判断/填空 omit options,
// which default to [].
func normalizeAnswer(questionType QuestionType, options []string, answer any) ([]string, any, error) {
	switch questionType {
	case QuestionTypeSingle, QuestionTypeMultiple:
		if len(options) < 2 {
			return nil, nil, &ValidationError{Message: "options must contain at least 2 items for 单选/多选"}
		}
		if answer == nil {
			return nil, nil, &ValidationError{Message: "answer required"}
		}
		if questionType == QuestionTypeSingle {
			value, ok := answer.(string)
			if !ok || !contains(options, value) {
				return nil, nil, &ValidationError{Message: "answer must be one of the options"}
			}
			return options, value, nil
		}
		values, ok := answer.([]any)
		if !ok || len(values) == 0 {
			return nil, nil, &ValidationError{Message: "answer required"}
		}
		for _, value := range values {
			option, ok := value.(string)
			if !ok || !contains(options, option) {
				return nil, nil, &ValidationError{Message: "answer must be a subset of the options"}
			}
		}
		return options, values, nil
	case QuestionTypeJudgment:
		if answer == nil {
			return nil, nil, &ValidationError{Message: "answer required"}
		}
		value, ok := answer.(string)
		if !ok || (value != JudgmentAnswerTrue && value != JudgmentAnswerFalse) {
			return nil, nil, &ValidationError{Message: "answer must be 正确 or 错误"}
		}
		return []string{}, value, nil
	case QuestionTypeFill:
		value, ok := answer.(string)
		if !ok || strings.TrimSpace(value) == "" {
			return nil, nil, &ValidationError{Message: "answer required"}
		}
		return []string{}, value, nil
	}
	return []string{}, answer, nil
}

// contains reports whether items carries target.
func contains(items []string, target string) bool {
	for _, item := range items {
		if item == target {
			return true
		}
	}
	return false
}
