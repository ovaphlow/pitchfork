package opinion

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// RunSource supplies the drill run an opinion event belongs to: the
// service reads the run status through it for the write gate and the
// 404 checks. The production implementation adapts the drills.Store (its
// GetRun); tests inject an in-memory fake, keeping the service logic
// free of any storage.
type RunSource interface {
	GetRun(ctx context.Context, id string) (drills.Run, error)
}

// runSourceAdapter adapts a drills.Store to the RunSource interface.
type runSourceAdapter struct {
	store drills.Store
}

// NewRunSource returns a RunSource backed by the drill store of the
// drills package.
func NewRunSource(store drills.Store) RunSource {
	return &runSourceAdapter{store: store}
}

// GetRun returns the run with the given id, or ErrRunNotFound.
func (a *runSourceAdapter) GetRun(ctx context.Context, id string) (drills.Run, error) {
	run, err := a.store.GetRun(ctx, id)
	if err != nil {
		if errors.Is(err, drills.ErrRunNotFound) {
			return drills.Run{}, ErrRunNotFound
		}
		return drills.Run{}, err
	}
	return run, nil
}

// Service applies the opinion event business rules (validation,
// defaults, the disposition state machine, the run write gate, the 404
// checks and server-generated ids and timestamps) on top of the store
// and the injected run source.
type Service struct {
	store  Store
	source RunSource
	now    func() time.Time
	newID  func() string
}

// NewService builds a service over the given store and run source. The
// server-generated id is a 26-character Crockford Base32 ULID.
func NewService(store Store, source RunSource) *Service {
	return &Service{store: store, source: source, now: time.Now, newID: ulid.New}
}

// writableRun reports whether a run in the given status may be
// configured: 未开始 and 进行中 are writable, 已完成 and 已终止 are not.
func writableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusNotStarted || status == drills.RunStatusInProgress
}

// UpsertEvent configures the opinion event of the run and returns the
// updated row. The first PUT of a run creates the event (only 监测中 is
// accepted as the initial status); later PUTs update it in place (the
// id and created_at are preserved) with full replacement semantics
// (omitted fields reset to their defaults) and the disposition state
// machine governs the status: same-value transitions are legal no-ops,
// adjacent advances (监测中 -> 已预警 -> 已处置) are legal, skips and
// backward steps are 400. A missing run is ErrRunNotFound (404); a run
// in 已完成/已终止 is a ValidationError (400).
func (s *Service) UpsertEvent(ctx context.Context, runID string, input EventInput) (Event, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Event{}, err
	}
	if !writableRun(run.Status) {
		return Event{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	existing, err := s.store.GetEvent(ctx, runID)
	if err != nil && !errors.Is(err, ErrEventNotFound) {
		return Event{}, err
	}
	if errors.Is(err, ErrEventNotFound) {
		event, err := normalizeEvent(runID, input, now, s.newID(), true)
		if err != nil {
			return Event{}, err
		}
		if err := s.store.UpsertEvent(ctx, event); err != nil {
			return Event{}, err
		}
		return event, nil
	}
	event, err := normalizeEvent(runID, input, now, existing.ID, false)
	if err != nil {
		return Event{}, err
	}
	event.CreatedAt = existing.CreatedAt
	if !legalStatusTransition(existing.Status, event.Status) {
		return Event{}, &ValidationError{
			Message: fmt.Sprintf("illegal opinion event status transition: %s -> %s", existing.Status, event.Status),
		}
	}
	if err := s.store.UpsertEvent(ctx, event); err != nil {
		return Event{}, err
	}
	return event, nil
}

// GetEvent returns the opinion event of the run. A missing run is
// ErrRunNotFound; a run without a configured event is ErrEventNotFound.
// GET is not subject to the write gate: a run in 已完成/已终止 with a
// configured event still answers 200.
func (s *Service) GetEvent(ctx context.Context, runID string) (Event, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Event{}, err
	}
	return s.store.GetEvent(ctx, runID)
}

// DeleteEvent removes the opinion event of the run. A missing run is
// ErrRunNotFound; a run without a configured event is ErrEventNotFound;
// a run in 已完成/已终止 is a ValidationError (400). The checks follow
// the pinned order: run existence, then event existence, then the write
// gate.
func (s *Service) DeleteEvent(ctx context.Context, runID string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if _, err := s.store.GetEvent(ctx, runID); err != nil {
		return err
	}
	if !writableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteEvent(ctx, runID)
}
