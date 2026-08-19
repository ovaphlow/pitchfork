// Package opinion implements the public-opinion-response training
// objects (module 5 of the museum safety platform) of prototyped: the
// opinion event configuration (舆情事件配置) of a drill run. The package
// defines the models with their enum validation and the event-level
// disposition state machine (监测中 -> 已预警 -> 已处置), a store
// interface with an in-memory implementation, and the service layer
// that reads the owning drill run through an injected run source (the
// 404 check and the write gate) and offers the uniform cascade cleanup
// entry (DeleteByRun) the drills service calls on run deletion. It
// never touches a database; a PostgreSQL-backed store can be swapped in
// later behind the same interface.
package opinion

import (
	"errors"
	"fmt"
	"time"
)

// ErrRunNotFound is returned when the drill run of an opinion event
// does not exist. It maps to HTTP 404 in the routing layer.
var ErrRunNotFound = errors.New("drill not found")

// ErrEventNotFound is returned when the run exists but its opinion
// event has not been configured. It maps to HTTP 404 in the routing
// layer.
var ErrEventNotFound = errors.New("opinion event not found")

// ValidationError describes a request that violates the opinion event
// business rules (a missing required field, an invalid enum value or an
// illegal status transition). It maps to HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// Level is the opinion level (舆情等级) of the event: how hot the public
// opinion is. 高热 is the hottest, 低热 the mildest.
type Level string

const (
	LevelHigh   Level = "高热"
	LevelMedium Level = "中热"
	LevelLow    Level = "低热"
)

// DefaultLevel is applied when a request omits the level field.
const DefaultLevel = LevelMedium

var validLevels = []Level{LevelHigh, LevelMedium, LevelLow}

// Valid reports whether level is one of the allowed level values.
func (level Level) Valid() bool {
	for _, candidate := range validLevels {
		if level == candidate {
			return true
		}
	}
	return false
}

// Status is the event-level disposition state (事件级处置状态) of the
// opinion event. The state machine is enforced by the service: a new
// event starts in 监测中 and only moves through the adjacent steps
// 监测中 -> 已预警 -> 已处置 (same-value transitions are legal no-ops,
// skips and backward steps are rejected with 400), mirroring the
// 「舆情监测与预警」 training phase.
type Status string

const (
	StatusMonitoring Status = "监测中"
	StatusWarning    Status = "已预警"
	StatusHandled    Status = "已处置"
)

// DefaultStatus is applied when a request omits the status field.
const DefaultStatus = StatusMonitoring

var validStatuses = []Status{StatusMonitoring, StatusWarning, StatusHandled}

// Valid reports whether status is one of the allowed status values.
func (status Status) Valid() bool {
	for _, candidate := range validStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// legalStatusTransition reports whether moving from one status to
// another follows the state machine: staying on the same status is a
// legal no-op, advancing one adjacent step (监测中 -> 已预警 -> 已处置) is
// legal, every skip or backward step is illegal.
func legalStatusTransition(from, to Status) bool {
	if from == to {
		return true
	}
	return from == StatusMonitoring && to == StatusWarning ||
		from == StatusWarning && to == StatusHandled
}

// Event is the opinion event configuration (舆情事件配置) of one drill
// run: the event background (event_name / subject / summary /
// occurred_at), the opinion level and the event-level disposition
// status, plus the repository extension fields. At most one event
// exists per run (run_id UNIQUE); it is created by the first PUT and
// updated in place by later PUTs (full replacement semantics: omitted
// fields reset to their defaults).
type Event struct {
	ID         string         `json:"id"`
	RunID      string         `json:"run_id"`
	EventName  string         `json:"event_name"`
	Subject    string         `json:"subject"`
	Summary    string         `json:"summary"`
	OccurredAt *time.Time     `json:"occurred_at"`
	Level      Level          `json:"level"`
	Status     Status         `json:"status"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
	CreatedAt  time.Time      `json:"created_at"`
	UpdatedAt  time.Time      `json:"updated_at"`
}

// EventInput carries the client-supplied fields of an event upsert.
// run_id and id are never part of the input: they are decided by the
// route path and the service. event_name is required; subject and
// summary pass through (empty is legal); occurred_at is an optional
// instant (nil means unset); level defaults to 中热 when empty and must
// be one of the allowed values; status defaults to 监测中 when empty; a
// new event only accepts 监测中 (the state machine governs later PUTs);
// metadata defaults to an empty object; created_by passes through (the
// prototype has no auth context).
type EventInput struct {
	EventName  string
	Subject    string
	Summary    string
	OccurredAt *time.Time
	Level      Level
	Status     Status
	Metadata   map[string]any
	CreatedBy  string
}

// normalizeEvent validates client input and produces a complete event.
// event_name is required; level defaults to 中热 and must be one of the
// allowed values; status defaults to 监测中 and must be one of the
// allowed values; a create additionally only accepts 监测中 (an explicit
// 已预警/已处置 on the first PUT is a ValidationError — the state
// machine governs the later PUTs in the service). subject / summary /
// occurred_at / metadata / created_by pass through (occurred_at nil
// stays nil, metadata nil becomes an empty object). The run and the
// timestamps come from the caller.
func normalizeEvent(runID string, input EventInput, now time.Time, id string, create bool) (Event, error) {
	if input.EventName == "" {
		return Event{}, &ValidationError{Message: "event_name required"}
	}
	level := input.Level
	if level == "" {
		level = DefaultLevel
	}
	if !level.Valid() {
		return Event{}, &ValidationError{Message: fmt.Sprintf("invalid level: %q", input.Level)}
	}
	status := input.Status
	if status == "" {
		status = DefaultStatus
	}
	if !status.Valid() {
		return Event{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	if create && status != DefaultStatus {
		return Event{}, &ValidationError{
			Message: fmt.Sprintf("invalid status for a new opinion event: %q (only %s is accepted)", input.Status, DefaultStatus),
		}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Event{
		ID:         id,
		RunID:      runID,
		EventName:  input.EventName,
		Subject:    input.Subject,
		Summary:    input.Summary,
		OccurredAt: input.OccurredAt,
		Level:      level,
		Status:     status,
		Metadata:   metadata,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}
