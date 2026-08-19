package evaluation

import (
	"context"
	"sort"
	"sync"
)

// ReportStore persists the evaluation reports. The prototype ships the
// in-memory implementation; the interface keeps the service layer
// independent of the storage backend. At most one report exists per run
// (the database carries the UNIQUE run_id; the in-memory store enforces
// the same rule in CreateReport); the cascade rule of the database
// (reports vanish with their run) is implemented by DeleteReportsByRun,
// the uniform cleanup entry the drills service calls through its
// run-report cleaner hook.
type ReportStore interface {
	CreateReport(ctx context.Context, report Report) error
	UpdateReport(ctx context.Context, report Report) error
	GetReportByRun(ctx context.Context, runID string) (Report, error)
	ListReports(ctx context.Context, filter ReportFilter) ([]Report, int, error)
	DeleteReportsByRun(ctx context.Context, runID string) error
}

// InMemoryReportStore keeps the evaluation report rows in an
// insertion-ordered slice guarded by a mutex. It implements ReportStore
// for the prototype and never touches a database; a database-backed
// store arrives with a later slice. The listing method returns the rows
// in the repository sort order (created_at DESC, id DESC as the
// deterministic tie-break — the newest report first) and the paginated
// page. Every accessor returns a deep copy, so callers can never mutate
// the stored JSONB snapshots through the returned value.
type InMemoryReportStore struct {
	mu      sync.Mutex
	reports []Report
}

// NewInMemoryReportStore returns an empty in-memory evaluation report
// store.
func NewInMemoryReportStore() *InMemoryReportStore {
	return &InMemoryReportStore{}
}

// CreateReport appends the report. A report for the same run already
// existing answers ErrReportExists (the in-memory counterpart of the
// UNIQUE run_id constraint); the service never triggers it, because
// generation looks up first and either creates or overwrites in place.
func (s *InMemoryReportStore) CreateReport(_ context.Context, report Report) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.indexOfReport(report.RunID) >= 0 {
		return ErrReportExists
	}
	s.reports = append(s.reports, cloneReport(report))
	return nil
}

// UpdateReport replaces the report of the same run, or returns
// ErrReportNotFound. The service preserves id and created_at across
// regenerations; the store itself replaces the whole row (snapshot
// semantics).
func (s *InMemoryReportStore) UpdateReport(_ context.Context, report Report) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfReport(report.RunID)
	if index < 0 {
		return ErrReportNotFound
	}
	s.reports[index] = cloneReport(report)
	return nil
}

// GetReportByRun returns the report of the run, or ErrReportNotFound.
func (s *InMemoryReportStore) GetReportByRun(_ context.Context, runID string) (Report, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfReport(runID)
	if index < 0 {
		return Report{}, ErrReportNotFound
	}
	return cloneReport(s.reports[index]), nil
}

// ListReports returns the reports matching the filter (run_id exact
// match) ordered by created_at DESC, id DESC (the newest report first),
// the total number of matches and the paginated page.
func (s *InMemoryReportStore) ListReports(_ context.Context, filter ReportFilter) ([]Report, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Report, 0, len(s.reports))
	for _, report := range s.reports {
		if filter.RunID != "" && report.RunID != filter.RunID {
			continue
		}
		matched = append(matched, report)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].CreatedAt.Equal(matched[j].CreatedAt) {
			return matched[i].ID > matched[j].ID
		}
		return matched[i].CreatedAt.After(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]Report, 0, end-start)
	for _, report := range matched[start:end] {
		page = append(page, cloneReport(report))
	}
	return page, total, nil
}

// DeleteReportsByRun removes every report of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE). Removing no reports is
// not an error.
func (s *InMemoryReportStore) DeleteReportsByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.reports[:0]
	for _, report := range s.reports {
		if report.RunID != runID {
			kept = append(kept, report)
		}
	}
	s.reports = kept
	return nil
}

func (s *InMemoryReportStore) indexOfReport(runID string) int {
	for i, report := range s.reports {
		if report.RunID == runID {
			return i
		}
	}
	return -1
}

// cloneReport deep-copies the JSONB snapshot fields (maps and slices),
// so the stored rows and the returned values never share mutable state.
func cloneReport(report Report) Report {
	clone := report
	clone.DimensionScores = make(map[Dimension]DimensionScore, len(report.DimensionScores))
	for dimension, score := range report.DimensionScores {
		breakdown := make(map[string]float64, len(score.Breakdown))
		for id, value := range score.Breakdown {
			breakdown[id] = value
		}
		clone.DimensionScores[dimension] = DimensionScore{Score: score.Score, Breakdown: breakdown}
	}
	clone.IndicatorScores = make(map[string]IndicatorScore, len(report.IndicatorScores))
	for id, score := range report.IndicatorScores {
		clone.IndicatorScores[id] = score
	}
	clone.Suggestions = append([]Suggestion(nil), report.Suggestions...)
	return clone
}
