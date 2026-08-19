package evaluation

import (
	"context"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// ReportService applies the evaluation report business rules on top of
// the report store: it gathers the run data through the injected
// ReportDataSource, runs the deterministic scoring engine, assigns the
// server-generated id and the timestamps and preserves id/created_at
// across regenerations. It never touches a database.
type ReportService struct {
	reports    ReportStore
	indicators Store            // the evaluation indicator store (the dictionary of the run)
	scores     ScoreStore       // the evaluation score store (the human sources of the run)
	source     ReportDataSource // the drill/dispatch data of the run, injected at the composition root
	now        func() time.Time
	newID      func() string
}

// NewReportService builds a report service over the given stores. The
// server-generated id is a 26-character Crockford Base32 ULID.
func NewReportService(reports ReportStore, indicators Store, scores ScoreStore, source ReportDataSource) *ReportService {
	return &ReportService{
		reports:    reports,
		indicators: indicators,
		scores:     scores,
		source:     source,
		now:        time.Now,
		newID:      ulid.New,
	}
}

// GenerateReport computes and stores the report of the run. The run
// must exist (otherwise its not-found error travels unchanged and maps
// to HTTP 404) and must be 已完成 (otherwise a ValidationError maps to
// HTTP 400): only completed drills admit a report. The first generation
// creates the report and reports created=true; a later generation
// overwrites the snapshot in place — the id and the created_at are
// preserved, the updated_at is refreshed — and reports created=false.
// The report is always returned in full, so the routing layer answers
// 201 on create and 200 on overwrite with the same object shape.
func (s *ReportService) GenerateReport(ctx context.Context, runID string) (Report, bool, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Report{}, false, err
	}
	if run.Status != reportRunStatusCompleted {
		return Report{}, false, &ValidationError{
			Message: "run status " + run.Status + " does not allow report generation",
		}
	}
	indicators, _, err := s.indicators.ListIndicators(ctx, IndicatorFilter{Limit: -1})
	if err != nil {
		return Report{}, false, err
	}
	scores, _, err := s.scores.ListScoresByRun(ctx, runID, ScoreFilter{Limit: -1})
	if err != nil {
		return Report{}, false, err
	}
	simEvents, err := s.source.ListSimEvents(ctx, runID)
	if err != nil {
		return Report{}, false, err
	}
	stepRecords, err := s.source.ListStepRecords(ctx, runID)
	if err != nil {
		return Report{}, false, err
	}
	orders, err := s.source.ListOrders(ctx, runID)
	if err != nil {
		return Report{}, false, err
	}
	messages, err := s.source.ListMessages(ctx, runID)
	if err != nil {
		return Report{}, false, err
	}
	departmentReports, err := s.source.ListDepartmentReports(ctx, runID)
	if err != nil {
		return Report{}, false, err
	}
	content := ComposeReport(ScoringInput{
		Run:               run,
		Indicators:        indicators,
		Scores:            scores,
		SimEvents:         simEvents,
		StepRecords:       stepRecords,
		Orders:            orders,
		Messages:          messages,
		DepartmentReports: departmentReports,
	})
	now := s.now()
	existing, err := s.reports.GetReportByRun(ctx, runID)
	switch {
	case errors.Is(err, ErrReportNotFound):
		report := Report{
			ID:              s.newID(),
			RunID:           runID,
			OverallScore:    content.OverallScore,
			DimensionScores: content.DimensionScores,
			IndicatorScores: content.IndicatorScores,
			Suggestions:     content.Suggestions,
			CreatedAt:       now,
			UpdatedAt:       now,
		}
		if err := s.reports.CreateReport(ctx, report); err != nil {
			return Report{}, false, err
		}
		return report, true, nil
	case err != nil:
		return Report{}, false, err
	default:
		report := Report{
			ID:              existing.ID,
			RunID:           runID,
			OverallScore:    content.OverallScore,
			DimensionScores: content.DimensionScores,
			IndicatorScores: content.IndicatorScores,
			Suggestions:     content.Suggestions,
			CreatedBy:       existing.CreatedBy,
			CreatedAt:       existing.CreatedAt,
			UpdatedAt:       now,
		}
		if err := s.reports.UpdateReport(ctx, report); err != nil {
			return Report{}, false, err
		}
		return report, false, nil
	}
}

// GetReportByRun returns the report of the run, or ErrReportNotFound
// (no report generated yet — a missing run or a run that never reached
// 已完成 can never carry a report, so both answer the same 404).
func (s *ReportService) GetReportByRun(ctx context.Context, runID string) (Report, error) {
	return s.reports.GetReportByRun(ctx, runID)
}

// ListReports returns the reports matching the filter (run_id exact
// match; an unknown or malformed run_id simply matches nothing) and the
// total number of matches (before pagination), ordered by created_at
// DESC, id DESC — the newest report first. An invalid run_id filter is
// not an error (established list convention of the drills/dispatch
// routes).
func (s *ReportService) ListReports(ctx context.Context, filter ReportFilter) ([]Report, int, error) {
	return s.reports.ListReports(ctx, filter)
}
