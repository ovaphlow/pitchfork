package dispatch

import (
	"context"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// RunSource supplies the drill run a dispatch command session belongs
// to: the service reads the run status through it for the write gate
// and the 404 checks. The production implementation adapts the
// drills.Store (its GetRun); tests inject an in-memory fake, keeping the
// service logic free of any storage.
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

// Service applies the dispatch business rules (validation, defaults,
// the run write gate, the 404 checks and server-generated ids and
// timestamps) on top of the store and the injected run source.
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

// UpsertSession configures the dispatch command session of the run and
// returns the updated row. The first PUT of a run creates the row;
// later PUTs update it in place (the id and created_at are preserved)
// with full replacement semantics (omitted fields reset to their
// defaults). A missing run is ErrRunNotFound (404); a run in
// 已完成/已终止 is a ValidationError (400).
func (s *Service) UpsertSession(ctx context.Context, runID string, input SessionInput) (Session, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Session{}, err
	}
	if !writableRun(run.Status) {
		return Session{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	existing, err := s.store.GetSession(ctx, runID)
	if err != nil && !errors.Is(err, ErrSessionNotFound) {
		return Session{}, err
	}
	if errors.Is(err, ErrSessionNotFound) {
		session, err := normalizeSession(runID, input, now, s.newID())
		if err != nil {
			return Session{}, err
		}
		if err := s.store.UpsertSession(ctx, session); err != nil {
			return Session{}, err
		}
		return session, nil
	}
	session, err := normalizeSession(runID, input, now, existing.ID)
	if err != nil {
		return Session{}, err
	}
	session.CreatedAt = existing.CreatedAt
	if err := s.store.UpsertSession(ctx, session); err != nil {
		return Session{}, err
	}
	return session, nil
}

// GetSession returns the dispatch command session of the run. A missing
// run is ErrRunNotFound; a run without a configured session is
// ErrSessionNotFound. GET is not subject to the write gate: a run in
// 已完成/已终止 with a configured session still answers 200.
func (s *Service) GetSession(ctx context.Context, runID string) (Session, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Session{}, err
	}
	return s.store.GetSession(ctx, runID)
}

// DeleteSession removes the dispatch command session of the run. A
// missing run is ErrRunNotFound; a run without a configured session is
// ErrSessionNotFound; a run in 已完成/已终止 is a ValidationError (400).
// The checks follow the pinned order: run existence, then session
// existence, then the write gate.
func (s *Service) DeleteSession(ctx context.Context, runID string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if _, err := s.store.GetSession(ctx, runID); err != nil {
		return err
	}
	if !writableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteSession(ctx, runID)
}
