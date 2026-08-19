package evaluation

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrScoreNotFound is returned when an evaluation score id does not
// exist within the run of the route path. It maps to HTTP 404 in the
// routing layer.
var ErrScoreNotFound = errors.New("score not found")

// ErrExpertScoreExists is returned when a 专家评分 record already exists
// for the (run, indicator) pair. It maps to HTTP 400 in the routing
// layer; the message is the pinned repository wording (该演练与指标下已
// 存在专家评分，请用 PUT 更新), so the service and the tests share one
// contract.
var ErrExpertScoreExists = errors.New("该演练与指标下已存在专家评分，请用 PUT 更新")

// ScoreType is the scoring source (评分来源) of an evaluation score
// record. The three values mirror the product specification: 专家评分
// (expert assessment), 自评 (self assessment) and 互评 (peer assessment).
type ScoreType string

const (
	ScoreTypeExpert ScoreType = "专家评分"
	ScoreTypeSelf   ScoreType = "自评"
	ScoreTypePeer   ScoreType = "互评"
)

var validScoreTypes = []ScoreType{ScoreTypeExpert, ScoreTypeSelf, ScoreTypePeer}

// Valid reports whether scoreType is one of the three allowed scoring
// sources.
func (scoreType ScoreType) Valid() bool {
	for _, candidate := range validScoreTypes {
		if scoreType == candidate {
			return true
		}
	}
	return false
}

// Score is one evaluation score record (评估评分记录) of the
// comprehensive-evaluation module: one of the three human scoring
// sources for one evaluation indicator of one drill run. An expert
// score is unique per (run, indicator) — the service rejects a
// duplicate with ErrExpertScoreExists (the partial unique index of the
// migration mirrors the rule at the database level) — while self and
// peer scores allow multiple records. target is required for 自评/互评
// and always empty for 专家评分 (the service forces it, no matter what
// the request carries); rater is required; score must be between 0 and
// 100; comment and created_by default to empty strings. The id is a
// server-generated 26-character Crockford Base32 ULID; the timestamps
// are maintained by the service.
type Score struct {
	ID          string    `json:"id"`
	RunID       string    `json:"run_id"`
	IndicatorID string    `json:"indicator_id"`
	ScoreType   ScoreType `json:"score_type"`
	Rater       string    `json:"rater"`
	Target      string    `json:"target"`
	Score       int       `json:"score"`
	Comment     string    `json:"comment"`
	CreatedBy   string    `json:"created_by"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// ScoreInput carries the client-supplied fields shared by score create
// and update. indicator_id is only read on create (a PUT never changes
// it: the owning run of the route path and the existing record decide
// it); score is a pointer so a missing field can be told apart from an
// explicit zero — 0 is a legal score, so the Go zero value must not
// pass for the required field.
type ScoreInput struct {
	IndicatorID string
	ScoreType   ScoreType
	Rater       string
	Target      string
	Score       *int
	Comment     string
	CreatedBy   string
}

// ScoreFilter selects scores for listing. Empty values match
// everything; Limit and Offset paginate the matching set (ordered by
// created_at ASC, id ASC).
type ScoreFilter struct {
	ScoreType   ScoreType
	IndicatorID string
	Limit       int
	Offset      int
}

// normalizeScore validates client input and produces a complete score.
// score_type must be one of the three allowed values; rater is required
// (trimmed, a blank string counts as missing); score is required and
// must be between 0 and 100; target is required for 自评/互评 (trimmed)
// and forced to an empty string for 专家评分 no matter what the request
// carries; comment and created_by default to empty strings. The run,
// the indicator and the timestamps come from the caller; the
// server-generated id comes from the caller as well. The same
// normalization serves create and update, so a PUT body that omits
// comment or created_by resets them to their defaults (established PUT
// semantics of the prototype).
func normalizeScore(runID, indicatorID string, input ScoreInput, now time.Time, id string) (Score, error) {
	if !input.ScoreType.Valid() {
		return Score{}, &ValidationError{Message: fmt.Sprintf("invalid score_type: %q", input.ScoreType)}
	}
	rater := strings.TrimSpace(input.Rater)
	if rater == "" {
		return Score{}, &ValidationError{Message: "rater required"}
	}
	if input.Score == nil {
		return Score{}, &ValidationError{Message: "score required"}
	}
	if *input.Score < 0 || *input.Score > 100 {
		return Score{}, &ValidationError{Message: "score must be between 0 and 100"}
	}
	target := strings.TrimSpace(input.Target)
	if input.ScoreType == ScoreTypeExpert {
		target = ""
	} else if target == "" {
		return Score{}, &ValidationError{Message: "target required"}
	}
	return Score{
		ID:          id,
		RunID:       runID,
		IndicatorID: indicatorID,
		ScoreType:   input.ScoreType,
		Rater:       rater,
		Target:      target,
		Score:       *input.Score,
		Comment:     input.Comment,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}
