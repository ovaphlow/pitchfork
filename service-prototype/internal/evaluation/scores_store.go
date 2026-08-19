package evaluation

import (
	"context"
	"sort"
	"sync"
)

// ScoreStore persists the evaluation score records. The prototype ships
// the in-memory implementation; the interface keeps the service layer
// independent of the storage backend. The cascade rule of the database
// (scores are removed with their run) is implemented through the drills
// service's evaluation-score cleaner hook, which calls
// DeleteScoresByRun; the expert-score uniqueness rule is enforced by
// the score service through CountExpertScores; the indicator reference
// rule is enforced by the indicator service through
// CountScoresByIndicator (the ScoreRefChecker of the evaluation
// package — this store is its real reference source).
type ScoreStore interface {
	CreateScore(ctx context.Context, score Score) error
	ListScoresByRun(ctx context.Context, runID string, filter ScoreFilter) ([]Score, int, error)
	GetScore(ctx context.Context, runID, id string) (Score, error)
	UpdateScore(ctx context.Context, score Score) error
	DeleteScore(ctx context.Context, runID, id string) error
	DeleteScoresByRun(ctx context.Context, runID string) error
	CountExpertScores(ctx context.Context, runID, indicatorID, excludeID string) (int, error)
}

// InMemoryScoreStore keeps the evaluation score rows in an
// insertion-ordered slice guarded by a mutex. It implements ScoreStore
// for the prototype and never touches a database; a database-backed
// store arrives with a later slice. The listing method returns the rows
// in the repository sort order (created_at ASC, id ASC as the
// deterministic tie-break) and the paginated page. It also implements
// the evaluation ScoreRefChecker interface (CountScoresByIndicator), so
// the indicator service's delete reference check works against the real
// score data.
type InMemoryScoreStore struct {
	mu     sync.Mutex
	scores []Score
}

// NewInMemoryScoreStore returns an empty in-memory evaluation score
// store.
func NewInMemoryScoreStore() *InMemoryScoreStore {
	return &InMemoryScoreStore{}
}

// CreateScore appends the score to the store.
func (s *InMemoryScoreStore) CreateScore(_ context.Context, score Score) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.scores = append(s.scores, score)
	return nil
}

// ListScoresByRun returns the scores of the run matching the filter
// (score_type and indicator_id exact matches) ordered by created_at ASC,
// id ASC, the total number of matches and the paginated page.
func (s *InMemoryScoreStore) ListScoresByRun(_ context.Context, runID string, filter ScoreFilter) ([]Score, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Score, 0, len(s.scores))
	for _, item := range s.scores {
		if item.RunID != runID {
			continue
		}
		if filter.ScoreType != "" && item.ScoreType != filter.ScoreType {
			continue
		}
		if filter.IndicatorID != "" && item.IndicatorID != filter.IndicatorID {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].CreatedAt.Equal(matched[j].CreatedAt) {
			return matched[i].ID < matched[j].ID
		}
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]Score, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, item)
	}
	return page, total, nil
}

// GetScore returns the score with the given id within the run, or
// ErrScoreNotFound (a score of another run is not found as well).
func (s *InMemoryScoreStore) GetScore(_ context.Context, runID, id string) (Score, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfScore(runID, id)
	if index < 0 {
		return Score{}, ErrScoreNotFound
	}
	return s.scores[index], nil
}

// UpdateScore replaces the score with the same id (within the same
// run), or returns ErrScoreNotFound.
func (s *InMemoryScoreStore) UpdateScore(_ context.Context, score Score) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfScore(score.RunID, score.ID)
	if index < 0 {
		return ErrScoreNotFound
	}
	s.scores[index] = score
	return nil
}

// DeleteScore removes the score with the given id within the run, or
// returns ErrScoreNotFound.
func (s *InMemoryScoreStore) DeleteScore(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfScore(runID, id)
	if index < 0 {
		return ErrScoreNotFound
	}
	s.scores = append(s.scores[:index], s.scores[index+1:]...)
	return nil
}

// DeleteScoresByRun removes every score of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE). Removing no scores is not
// an error.
func (s *InMemoryScoreStore) DeleteScoresByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.scores[:0]
	for _, item := range s.scores {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.scores = kept
	return nil
}

// CountExpertScores returns the number of 专家评分 records of the
// (run, indicator) pair, optionally excluding one record — the id of
// the record being updated; an empty excludeID excludes nothing. The
// score service uses the count to enforce the one-expert-score-per-pair
// rule on create and on update (excluding the record itself).
func (s *InMemoryScoreStore) CountExpertScores(_ context.Context, runID, indicatorID, excludeID string) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	count := 0
	for _, item := range s.scores {
		if item.RunID != runID || item.IndicatorID != indicatorID {
			continue
		}
		if item.ScoreType != ScoreTypeExpert {
			continue
		}
		if excludeID != "" && item.ID == excludeID {
			continue
		}
		count++
	}
	return count, nil
}

// CountScoresByIndicator returns the number of scores referencing the
// indicator. It implements the evaluation ScoreRefChecker interface, so
// the indicator service rejects the deletion of indicators still
// referenced by score records (the real reference source behind the
// check injected at the composition root).
func (s *InMemoryScoreStore) CountScoresByIndicator(_ context.Context, indicatorID string) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	count := 0
	for _, item := range s.scores {
		if item.IndicatorID == indicatorID {
			count++
		}
	}
	return count, nil
}

func (s *InMemoryScoreStore) indexOfScore(runID, id string) int {
	for i, item := range s.scores {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}
