// Package chapters implements the training-course-chapter business object
// of prototyped: the model with its content-block type validation, a
// store interface with an in-memory implementation, and the service
// layer. The package never touches a database; a PostgreSQL-backed store
// can be swapped in later behind the same interface. Chapters belong to a
// course (validated through an injected course store) and are deleted
// together with their course via the cascade hook in the courses service.
package chapters

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrNotFound is returned by the store and service when a chapter id does
// not exist. It maps to HTTP 404 in the routing layer.
var ErrNotFound = errors.New("chapter not found")

// ErrCourseNotFound is returned by the service when the parent course of
// a chapter operation does not exist. It maps to HTTP 404 in the routing
// layer (course existence is checked before any chapter operation).
var ErrCourseNotFound = errors.New("course not found")

// ValidationError describes a request that violates the chapters business
// rules (missing required fields or invalid block types). It maps to
// HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// BlockType is the content block type (内容块类型) of a chapter.
type BlockType string

const (
	BlockVideo    BlockType = "视频"
	BlockRichText BlockType = "图文"
	BlockQuiz     BlockType = "互动问答"
)

var validBlockTypes = []BlockType{BlockVideo, BlockRichText, BlockQuiz}

// Valid reports whether blockType is one of the allowed block types.
func (blockType BlockType) Valid() bool {
	for _, candidate := range validBlockTypes {
		if blockType == candidate {
			return true
		}
	}
	return false
}

// Chapter is a course chapter as exposed by the API. Blocks are an array
// of content blocks passed through verbatim after per-block type
// validation; quiz_config is an optional JSONB extension echoed verbatim
// without structural checks (the card scope does not define field-level
// rules for questions/options/answers/feedback).
type Chapter struct {
	ID         string           `json:"id"`
	CourseID   string           `json:"course_id"`
	SortOrder  int              `json:"sort_order"`
	Title      string           `json:"title"`
	Blocks     []map[string]any `json:"blocks"`
	QuizConfig any              `json:"quiz_config"`
	CreatedAt  time.Time        `json:"created_at"`
	UpdatedAt  time.Time        `json:"updated_at"`
}

// Input carries the client-supplied fields shared by create and update.
// CourseID is never part of the input: it is decided by the route path
// (create) or kept from the stored record (update).
type Input struct {
	SortOrder  int
	Title      string
	Blocks     []map[string]any
	QuizConfig any
}

// Filter selects chapters for listing. Chapters have no filter dimensions
// besides the owning course (given by the route path), so the filter only
// paginates; Limit and Offset follow the repository default of 50.
type Filter struct {
	Limit  int
	Offset int
}

// normalize validates client input and produces a complete chapter. Title
// is required; every content block must carry a type of 视频/图文/互动问答;
// sort_order defaults to 0 and blocks to an empty array. quiz_config is
// optional and passed through as-is (nil renders as JSON null). The
// timestamps and the server-generated id come from the caller.
func normalize(input Input, now time.Time, id, courseID string) (Chapter, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return Chapter{}, &ValidationError{Message: "title required"}
	}
	blocks := input.Blocks
	if blocks == nil {
		blocks = []map[string]any{}
	}
	if err := validateBlocks(blocks); err != nil {
		return Chapter{}, err
	}
	return Chapter{
		ID:         id,
		CourseID:   courseID,
		SortOrder:  input.SortOrder,
		Title:      title,
		Blocks:     blocks,
		QuizConfig: input.QuizConfig,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}

// validateBlocks checks every content block for a valid type. A block
// without a type string, or with a type outside 视频/图文/互动问答, is
// rejected; validated blocks are passed through unchanged.
func validateBlocks(blocks []map[string]any) error {
	for index, block := range blocks {
		raw, ok := block["type"].(string)
		if !ok || !BlockType(raw).Valid() {
			return &ValidationError{
				Message: fmt.Sprintf("invalid block type at index %d: %q", index, raw),
			}
		}
	}
	return nil
}
