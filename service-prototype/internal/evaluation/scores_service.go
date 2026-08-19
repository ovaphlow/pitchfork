package evaluation

import (
	"context"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// ScoreService applies the evaluation score business rules (validation,
// defaults, server-generated ids and timestamps, the run and indicator
// existence checks and the expert-score uniqueness rule) on top of the
// score store. It never touches a database.
type ScoreService struct {
	scores     ScoreStore
	indicators Store               // the evaluation indicator store (indicator existence)
	runs       RunExistenceChecker // the drills store (run existence), injected at the composition root
	now        func() time.Time
	newID      func() string
}

// NewScoreService builds a score service over the given stores. The
// server-generated id is a 26-character Crockford Base32 ULID.
func NewScoreService(scores ScoreStore, indicators Store, runs RunExistenceChecker) *ScoreService {
	return &ScoreService{scores: scores, indicators: indicators, runs: runs, now: time.Now, newID: ulid.New}
}

// RunExistenceChecker verifies that the drill run a score belongs to
// exists. The drills store is injected at the composition root behind
// this interface so the evaluation package never imports the drills
// package; the routing layer maps the injected not-found error (the
// drills ErrRunNotFound) to HTTP 404.
type RunExistenceChecker interface {
	RunExists(ctx context.Context, runID string) error
}

// CreateScore validates the input, assigns a server-generated id and
// the timestamps, and stores the new record. The run and the indicator
// must exist, otherwise their not-found errors are returned unchanged
// (404 at the routing layer). A 专家评分 is unique per (run, indicator):
// a duplicate creation answers ErrExpertScoreExists (400 with the
// use-PUT hint); 自评 and 互评 allow multiple records.
func (s *ScoreService) CreateScore(ctx context.Context, runID string, input ScoreInput) (Score, error) {
	if err := s.runs.RunExists(ctx, runID); err != nil {
		return Score{}, err
	}
	if _, err := s.indicators.GetIndicator(ctx, input.IndicatorID); err != nil {
		return Score{}, err
	}
	score, err := normalizeScore(runID, input.IndicatorID, input, s.now(), s.newID())
	if err != nil {
		return Score{}, err
	}
	if score.ScoreType == ScoreTypeExpert {
		count, err := s.scores.CountExpertScores(ctx, runID, score.IndicatorID, "")
		if err != nil {
			return Score{}, err
		}
		if count > 0 {
			return Score{}, ErrExpertScoreExists
		}
	}
	if err := s.scores.CreateScore(ctx, score); err != nil {
		return Score{}, err
	}
	return score, nil
}

// ListScores returns the scores of the run matching the filter (score
// type and indicator, ordered by created_at ASC, id ASC) and the total
// number of matches (before pagination). A missing run is a 404.
func (s *ScoreService) ListScores(ctx context.Context, runID string, filter ScoreFilter) ([]Score, int, error) {
	if err := s.runs.RunExists(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.scores.ListScoresByRun(ctx, runID, filter)
}

// GetScore returns the score with the given id within the run, or
// ErrScoreNotFound. A missing run is a 404.
func (s *ScoreService) GetScore(ctx context.Context, runID, id string) (Score, error) {
	if err := s.runs.RunExists(ctx, runID); err != nil {
		return Score{}, err
	}
	return s.scores.GetScore(ctx, runID, id)
}

// UpdateScore validates the input with the same rules as CreateScore
// and replaces the score with the given id, returning the updated
// record. The run and the indicator are decided by the route path and
// the existing record: run_id and indicator_id are never modified by a
// PUT (a request body that carries them has them ignored). Fields
// omitted by the request body are reset to their defaults (comment ”,
// created_by ”), the established PUT semantics of the prototype; the
// original creation timestamp is preserved and the update timestamp is
// refreshed. Updating a record to 专家评分 conflicts with another expert
// score of the same (run, indicator) pair (excluding the record itself)
// with ErrExpertScoreExists; 自评/互评 updates never conflict.
func (s *ScoreService) UpdateScore(ctx context.Context, runID, id string, input ScoreInput) (Score, error) {
	if err := s.runs.RunExists(ctx, runID); err != nil {
		return Score{}, err
	}
	existing, err := s.scores.GetScore(ctx, runID, id)
	if err != nil {
		return Score{}, err
	}
	updated, err := normalizeScore(runID, existing.IndicatorID, input, s.now(), id)
	if err != nil {
		return Score{}, err
	}
	if updated.ScoreType == ScoreTypeExpert {
		count, err := s.scores.CountExpertScores(ctx, runID, existing.IndicatorID, id)
		if err != nil {
			return Score{}, err
		}
		if count > 0 {
			return Score{}, ErrExpertScoreExists
		}
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.scores.UpdateScore(ctx, updated); err != nil {
		return Score{}, err
	}
	return updated, nil
}

// DeleteScore removes the score with the given id within the run, or
// returns ErrScoreNotFound. A missing run is a 404.
func (s *ScoreService) DeleteScore(ctx context.Context, runID, id string) error {
	if err := s.runs.RunExists(ctx, runID); err != nil {
		return err
	}
	return s.scores.DeleteScore(ctx, runID, id)
}
