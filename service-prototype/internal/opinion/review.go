package opinion

import (
	"errors"
	"time"
)

// ErrReviewNotFound is returned when the run exists but its opinion
// review has not been written (or no longer exists). It maps to HTTP
// 404 in the routing layer.
var ErrReviewNotFound = errors.New("opinion review not found")

// Review is the after-action review report (舆情复盘记录) of one drill
// run: the five review sections 事件经过 (case_summary) / 处置亮点
// (highlights) / 存在问题 (problems) / 经验教训 (lessons) / 改进建议
// (suggestions), plus the repository extension fields. At most one
// review exists per run (run_id UNIQUE); it is created by the first PUT
// and updated in place by later PUTs (full replacement semantics:
// omitted fields reset to their defaults). All five sections are
// optional: an empty object {} is a legal all-default create or update.
type Review struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	CaseSummary string         `json:"case_summary"`
	Highlights  string         `json:"highlights"`
	Problems    string         `json:"problems"`
	Lessons     string         `json:"lessons"`
	Suggestions string         `json:"suggestions"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

// ReviewInput carries the client-supplied fields of a review upsert.
// run_id and id are never part of the input: they are decided by the
// route path and the service. The five text sections pass through
// (empty is legal; there is no required field); metadata defaults to an
// empty object; created_by passes through (the prototype has no auth
// context).
type ReviewInput struct {
	CaseSummary string
	Highlights  string
	Problems    string
	Lessons     string
	Suggestions string
	Metadata    map[string]any
	CreatedBy   string
}

// normalizeReview produces a complete review from client input. Every
// field is optional: the five text sections pass through (empty stays
// empty), metadata nil becomes an empty object, created_by passes
// through. The run and the timestamps come from the caller; there is no
// validation failure on this resource (the type checks happen at the
// JSON decode layer).
func normalizeReview(runID string, input ReviewInput, now time.Time, id string) Review {
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Review{
		ID:          id,
		RunID:       runID,
		CaseSummary: input.CaseSummary,
		Highlights:  input.Highlights,
		Problems:    input.Problems,
		Lessons:     input.Lessons,
		Suggestions: input.Suggestions,
		Metadata:    metadata,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}
}
