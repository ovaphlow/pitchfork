// Package evaluation implements the comprehensive-evaluation business
// objects of prototyped (module 4 of the museum safety platform): the
// evaluation indicator dictionary (评估指标字典) with its six dimensions
// and the built-in seed data. The package defines the models with their
// enum validation, a store interface with an in-memory implementation,
// and the service layer. It never touches a database; a PostgreSQL-backed
// store can be swapped in later behind the same interface.
package evaluation

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrIndicatorNotFound is returned when an evaluation indicator id does
// not exist. It maps to HTTP 404 in the routing layer.
var ErrIndicatorNotFound = errors.New("indicator not found")

// ErrIndicatorReferenced is returned when deleting an indicator that is
// still referenced by evaluation scores. It maps to HTTP 400 in the
// routing layer; the message is the pinned repository wording (指标已被
// 评分引用，请先清理评分), so the evaluation_scores card (000024) and the
// tests share one contract.
var ErrIndicatorReferenced = errors.New("指标已被评分引用，请先清理评分")

// ValidationError describes a request that violates the evaluation
// business rules (missing required fields, invalid enum values or
// out-of-range numeric fields). It maps to HTTP 400 in the routing
// layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// Dimension is the evaluation dimension (评估维度). The six built-in
// dimensions cover the museum safety comprehensive evaluation; every
// indicator belongs to exactly one dimension.
type Dimension string

const (
	DimensionResponseSpeed    Dimension = "响应速度"
	DimensionDisposalStandard Dimension = "处置规范性"
	DimensionCoordination     Dimension = "协同效率"
	DimensionAudienceSafety   Dimension = "观众安全"
	DimensionRelicSafety      Dimension = "文物安全"
	DimensionPublicOpinion    Dimension = "舆情管控"
)

var validDimensions = []Dimension{
	DimensionResponseSpeed,
	DimensionDisposalStandard,
	DimensionCoordination,
	DimensionAudienceSafety,
	DimensionRelicSafety,
	DimensionPublicOpinion,
}

// Valid reports whether dimension is one of the six allowed dimension
// values.
func (dimension Dimension) Valid() bool {
	for _, candidate := range validDimensions {
		if dimension == candidate {
			return true
		}
	}
	return false
}

// Indicator is one evaluation indicator (评估指标) of the dictionary: the
// owning dimension, the required title, the weight (default 1, must be
// at least 1), the demo flag separating the seven computable indicators
// (demo=false) from the eight presentation indicators (demo=true), the
// per-dimension sort_order (default 0), the free-form description
// (default ”) and the optional creator (default ”). The id is a
// server-generated 26-character Crockford Base32 ULID; the timestamps
// are maintained by the service.
type Indicator struct {
	ID          string    `json:"id"`
	Dimension   Dimension `json:"dimension"`
	Title       string    `json:"title"`
	Weight      int       `json:"weight"`
	Demo        bool      `json:"demo"`
	SortOrder   int       `json:"sort_order"`
	Description string    `json:"description"`
	CreatedBy   string    `json:"created_by"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// IndicatorInput carries the client-supplied fields shared by indicator
// create and update. Weight, Demo and SortOrder are pointers so a
// missing field can be told apart from an explicit zero (weight 0 and
// negative weights are invalid; an explicit 0 for weight must not be
// silently replaced by the default).
type IndicatorInput struct {
	Dimension   Dimension
	Title       string
	Weight      *int
	Demo        *bool
	SortOrder   *int
	Description string
	CreatedBy   string
}

// IndicatorFilter selects indicators for listing. An empty Dimension
// matches every dimension; Limit and Offset paginate the matching set
// (ordered by dimension, sort_order, created_at ascending).
type IndicatorFilter struct {
	Dimension Dimension
	Limit     int
	Offset    int
}

// normalizeIndicator validates client input and produces a complete
// indicator. Title is required (trimmed); dimension must be one of the
// six enum values; weight defaults to 1 and must be at least 1; demo
// defaults to false; sort_order defaults to 0 and must not be negative;
// description and created_by default to empty strings. The timestamps
// and the server-generated id come from the caller. The same
// normalization serves create and update, so a PUT body that omits a
// field resets it to its default (established convention of the
// prototype).
func normalizeIndicator(input IndicatorInput, now time.Time, id string) (Indicator, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return Indicator{}, &ValidationError{Message: "title required"}
	}
	if !input.Dimension.Valid() {
		return Indicator{}, &ValidationError{Message: fmt.Sprintf("invalid dimension: %q", input.Dimension)}
	}
	weight := 1
	if input.Weight != nil {
		weight = *input.Weight
	}
	if weight < 1 {
		return Indicator{}, &ValidationError{Message: "weight must be at least 1"}
	}
	demo := false
	if input.Demo != nil {
		demo = *input.Demo
	}
	sortOrder := 0
	if input.SortOrder != nil {
		sortOrder = *input.SortOrder
	}
	if sortOrder < 0 {
		return Indicator{}, &ValidationError{Message: "sort_order must not be negative"}
	}
	return Indicator{
		ID:          id,
		Dimension:   input.Dimension,
		Title:       title,
		Weight:      weight,
		Demo:        demo,
		SortOrder:   sortOrder,
		Description: input.Description,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}
