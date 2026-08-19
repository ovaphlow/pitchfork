// Package dispatch implements the command-and-dispatch training objects
// (module 3 of the museum safety platform) of prototyped: the dispatch
// command session configuration (指挥调度会话配置), the dispatch orders
// (调度指令), the dispatch department reports (部门联动处置记录), the
// dispatch messages (即时通讯消息) and the dispatch device status reports
// (设备运行状态上报) of a drill run. The package defines
// the models with their enum validation, a store interface with an
// in-memory implementation, and the service layer that reads the owning
// drill run through an injected run source (the 404 check and the write
// gate) and offers the cascade cleanup entries the drills service calls
// on run deletion. It never touches a database; a PostgreSQL-backed
// store can be swapped in later behind the same interface.
package dispatch

import (
	"errors"
	"fmt"
	"time"
)

// ErrRunNotFound is returned when the drill run of a session does not
// exist. It maps to HTTP 404 in the routing layer.
var ErrRunNotFound = errors.New("drill not found")

// ErrSessionNotFound is returned when the run exists but its dispatch
// command session has not been configured. It maps to HTTP 404 in the
// routing layer.
var ErrSessionNotFound = errors.New("command session not found")

// ValidationError describes a request that violates the dispatch
// business rules (an invalid mode). It maps to HTTP 400 in the routing
// layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// Mode is the training mode (实训方式) of a drill run: how the drill is
// exercised. 远程协同 joins multiple venues remotely; the joint venues
// only make sense in that mode.
type Mode string

const (
	ModeTabletop Mode = "桌面推演"
	ModeLive     Mode = "实战演练"
	ModeRemote   Mode = "远程协同"
)

// DefaultMode is applied when a request omits the mode field.
const DefaultMode = ModeLive

var validModes = []Mode{ModeTabletop, ModeLive, ModeRemote}

// Valid reports whether mode is one of the allowed mode values.
func (mode Mode) Valid() bool {
	for _, candidate := range validModes {
		if mode == candidate {
			return true
		}
	}
	return false
}

// Session is the dispatch command session configuration (指挥调度会话配置)
// of one drill run: the training mode, the main venue and the joint
// venues (remote multi-venue joint drills), plus the repository
// extension fields. At most one session exists per run (run_id UNIQUE);
// it is created by the first PUT and updated in place by later PUTs
// (full replacement semantics: omitted fields reset to their defaults).
type Session struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	Mode        Mode           `json:"mode"`
	MainVenue   string         `json:"main_venue"`
	JointVenues []string       `json:"joint_venues"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

// SessionInput carries the client-supplied fields of a session upsert.
// run_id and id are never part of the input: they are decided by the
// route path and the service. Mode defaults to 实战演练 when empty and
// must be one of the allowed values; main_venue passes through (empty is
// legal); joint_venues and metadata default to an empty array / object;
// created_by passes through (the prototype has no auth context).
type SessionInput struct {
	Mode        Mode
	MainVenue   string
	JointVenues []string
	Metadata    map[string]any
	CreatedBy   string
}

// normalizeSession validates client input and produces a complete
// session. Mode defaults to 实战演练 and must be one of the allowed
// values; joint_venues and metadata default to an empty array / object.
// The run and the timestamps come from the caller.
func normalizeSession(runID string, input SessionInput, now time.Time, id string) (Session, error) {
	mode := input.Mode
	if mode == "" {
		mode = DefaultMode
	}
	if !mode.Valid() {
		return Session{}, &ValidationError{Message: fmt.Sprintf("invalid mode: %q", input.Mode)}
	}
	jointVenues := input.JointVenues
	if jointVenues == nil {
		jointVenues = []string{}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Session{
		ID:          id,
		RunID:       runID,
		Mode:        mode,
		MainVenue:   input.MainVenue,
		JointVenues: jointVenues,
		Metadata:    metadata,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}
