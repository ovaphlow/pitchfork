package evaluation

import (
	"context"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// Service applies the evaluation business rules (validation, defaults,
// server-generated ids and timestamps and the delete reference check)
// on top of the store.
type Service struct {
	store     Store
	scoreRefs ScoreRefChecker // nil until wired: reference check against evaluation scores
	now       func() time.Time
	newID     func() string
}

// NewService builds a service over the given store. The server-generated
// id is a 26-character Crockford Base32 ULID.
func NewService(store Store) *Service {
	return &Service{store: store, now: time.Now, newID: ulid.New}
}

// ScoreRefChecker counts the evaluation scores that reference an
// indicator. Deleting an indicator is rejected while the count is
// positive (the database carries FK RESTRICT; the in-memory store
// implements the same rule here). The evaluation_scores card (000024)
// wires its store behind this interface at the composition root; the
// tests inject a fixture counter. Wired via SetScoreRefChecker, never
// by the routing layer.
type ScoreRefChecker interface {
	CountScoresByIndicator(ctx context.Context, indicatorID string) (int, error)
}

// SetScoreRefChecker wires the reference check: from then on
// DeleteIndicator rejects indicators whose score count is positive with
// ErrIndicatorReferenced. Calling it is optional; without a checker
// DeleteIndicator behaves exactly as before (an indicator referenced by
// scores would only be blocked by the database backend).
func (s *Service) SetScoreRefChecker(checker ScoreRefChecker) {
	s.scoreRefs = checker
}

// CreateIndicator validates the input, assigns a server-generated id and
// the timestamps, and stores the new indicator.
func (s *Service) CreateIndicator(ctx context.Context, input IndicatorInput) (Indicator, error) {
	indicator, err := normalizeIndicator(input, s.now(), s.newID())
	if err != nil {
		return Indicator{}, err
	}
	if err := s.store.CreateIndicator(ctx, indicator); err != nil {
		return Indicator{}, err
	}
	return indicator, nil
}

// ListIndicators returns the indicators matching the filter and the
// total number of matches (before pagination), ordered by dimension,
// sort_order and created_at ascending.
func (s *Service) ListIndicators(ctx context.Context, filter IndicatorFilter) ([]Indicator, int, error) {
	return s.store.ListIndicators(ctx, filter)
}

// GetIndicator returns the indicator with the given id, or
// ErrIndicatorNotFound.
func (s *Service) GetIndicator(ctx context.Context, id string) (Indicator, error) {
	return s.store.GetIndicator(ctx, id)
}

// UpdateIndicator validates the input with the same rules as
// CreateIndicator, replaces the indicator with the given id and returns
// the updated record. The original creation timestamp is preserved; the
// update timestamp is refreshed. Fields omitted by the request body are
// reset to their defaults (weight 1, demo false, sort_order 0,
// description ”, created_by ”), the established PUT semantics of the
// prototype.
func (s *Service) UpdateIndicator(ctx context.Context, id string, input IndicatorInput) (Indicator, error) {
	existing, err := s.store.GetIndicator(ctx, id)
	if err != nil {
		return Indicator{}, err
	}
	updated, err := normalizeIndicator(input, s.now(), id)
	if err != nil {
		return Indicator{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdateIndicator(ctx, updated); err != nil {
		return Indicator{}, err
	}
	return updated, nil
}

// DeleteIndicator removes the indicator with the given id, or returns
// ErrIndicatorNotFound. When a score-ref checker is wired, indicators
// still referenced by evaluation scores (count > 0) are rejected with
// ErrIndicatorReferenced and stay in place; the check runs before the
// deletion so a referenced indicator can never be removed by mistake.
func (s *Service) DeleteIndicator(ctx context.Context, id string) error {
	// Verify the indicator exists first: a missing indicator must still
	// answer ErrIndicatorNotFound without consulting the reference
	// source.
	if _, err := s.store.GetIndicator(ctx, id); err != nil {
		return err
	}
	if s.scoreRefs != nil {
		count, err := s.scoreRefs.CountScoresByIndicator(ctx, id)
		if err != nil {
			return err
		}
		if count > 0 {
			return ErrIndicatorReferenced
		}
	}
	return s.store.DeleteIndicator(ctx, id)
}
