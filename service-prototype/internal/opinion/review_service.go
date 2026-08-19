package opinion

import (
	"context"
	"errors"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// reviewWritableRun reports whether a run in the given status may
// receive opinion review writes: 进行中 and 已完成. The review is
// written after the disposition ends (the 「舆情复盘」 phase), so the
// drill can still be running or already finished; 未开始 and 已终止
// reject PUT/DELETE with 400 while GET is never gated. This is the
// pinned gate of this card: it does not reuse the opinion-event
// writableRun semantics (which also allows 未开始 but not 已完成) nor
// the post gate (进行中 only).
func reviewWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress || status == drills.RunStatusCompleted
}

// UpsertReview configures the opinion review (舆情复盘记录) of the run
// and returns the updated row. The first PUT of a run creates the
// review (an empty object {} is a legal all-default create); later PUTs
// update it in place with full replacement semantics (omitted fields
// reset to their defaults; the id and created_at are preserved and
// updated_at is refreshed). A missing run is ErrRunNotFound (404); a
// run in 未开始/已终止 is a ValidationError (400).
func (s *Service) UpsertReview(ctx context.Context, runID string, input ReviewInput) (Review, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Review{}, err
	}
	if !reviewWritableRun(run.Status) {
		return Review{}, postWriteGateError(run.Status)
	}
	now := s.now()
	existing, err := s.store.GetReview(ctx, runID)
	if err != nil && !errors.Is(err, ErrReviewNotFound) {
		return Review{}, err
	}
	review := normalizeReview(runID, input, now, s.newID())
	if !errors.Is(err, ErrReviewNotFound) {
		review.ID = existing.ID
		review.CreatedAt = existing.CreatedAt
	}
	if err := s.store.UpsertReview(ctx, review); err != nil {
		return Review{}, err
	}
	return review, nil
}

// GetReview returns the opinion review of the run. A missing run is
// ErrRunNotFound; a run without a written review is ErrReviewNotFound.
// GET is not subject to the write gate: a run in 未开始/已终止 with a
// written review still answers 200.
func (s *Service) GetReview(ctx context.Context, runID string) (Review, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Review{}, err
	}
	return s.store.GetReview(ctx, runID)
}

// DeleteReview removes the opinion review of the run. A missing run is
// ErrRunNotFound; a run without a written review is ErrReviewNotFound;
// a run in 未开始/已终止 is a ValidationError (400). The checks follow
// the pinned order: run existence, then review existence, then the
// write gate.
func (s *Service) DeleteReview(ctx context.Context, runID string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if _, err := s.store.GetReview(ctx, runID); err != nil {
		return err
	}
	if !reviewWritableRun(run.Status) {
		return postWriteGateError(run.Status)
	}
	return s.store.DeleteReview(ctx, runID)
}
