package evaluation

import (
	"context"
	"errors"
	"time"
)

// ErrReportNotFound is returned when no evaluation report exists for
// the drill run. It maps to HTTP 404 in the routing layer.
var ErrReportNotFound = errors.New("report not found")

// ErrReportExists is returned when a report for the drill run already
// exists. It backs the store-level invariant of the unique run_id (the
// service never triggers it: generation looks up first and either
// creates or overwrites in place); a database backend enforces the same
// rule with the UNIQUE constraint of the migration.
var ErrReportExists = errors.New("report already exists")

// reportRunStatusCompleted is the drill run status that admits report
// generation ("仅已完成演练可生成报告"). The evaluation package never
// imports the drills package, so the status value is mirrored here and
// the drills status travels through ReportRun as a plain string.
const reportRunStatusCompleted = "已完成"

// Report is one evaluation report (评估报告) of the comprehensive-
// evaluation module: the aggregated result of one completed drill run.
// The engine computes overall_score, dimension_scores, indicator_scores
// and suggestions as a snapshot; the id is a server-generated
// 26-character Crockford Base32 ULID, created_by defaults to ” and the
// timestamps are maintained by the service. At most one report exists
// per run (run_id UNIQUE): generating again overwrites the snapshot in
// place, preserving id and created_at and refreshing updated_at.
type Report struct {
	ID              string                       `json:"id"`
	RunID           string                       `json:"run_id"`
	OverallScore    float64                      `json:"overall_score"`
	DimensionScores map[Dimension]DimensionScore `json:"dimension_scores"`
	IndicatorScores map[string]IndicatorScore    `json:"indicator_scores"`
	Suggestions     []Suggestion                 `json:"suggestions"`
	CreatedBy       string                       `json:"created_by"`
	CreatedAt       time.Time                    `json:"created_at"`
	UpdatedAt       time.Time                    `json:"updated_at"`
}

// DimensionScore is the score of one evaluation dimension inside a
// report: the weighted average (weights normalized, rounded to 1
// decimal) of the scored indicators of the dimension and the breakdown
// of the per-indicator final scores (key = indicator id). A dimension
// without any scored indicator does not appear in dimension_scores.
type DimensionScore struct {
	Score     float64            `json:"score"`
	Breakdown map[string]float64 `json:"breakdown"`
}

// IndicatorScore is the score of one evaluation indicator inside a
// report: the final score (arithmetic mean of the present sources,
// rounded to 1 decimal) and the per-source values. auto is the
// deterministic engine score of a computable indicator, expert the mean
// of the 专家评分 records, self_peer the mean of the 自评/互评 records and
// demo the run.metadata.demo_scores value of a presentation indicator.
// A source without a value is absent from the JSON (omitempty); an
// indicator without any present source does not appear in
// indicator_scores at all. A presentation indicator only appears when
// demo_scores provides its value.
type IndicatorScore struct {
	Score    float64  `json:"score"`
	Auto     *float64 `json:"auto,omitempty"`
	Expert   *float64 `json:"expert,omitempty"`
	SelfPeer *float64 `json:"self_peer,omitempty"`
	Demo     *float64 `json:"demo,omitempty"`
}

// Suggestion is one rule-based improvement suggestion (改进建议) of a
// report: the owning dimension, the level (严重/关注) and the fixed
// template text. The single 演练数据不足 notice carries an empty
// dimension and an empty level.
type Suggestion struct {
	Dimension Dimension `json:"dimension"`
	Level     string    `json:"level"`
	Text      string    `json:"text"`
}

// ReportFilter selects reports for listing. An empty RunID matches
// every report; Limit and Offset paginate the matching set (ordered by
// created_at DESC, id DESC — the newest report first).
type ReportFilter struct {
	RunID  string
	Limit  int
	Offset int
}

// ReportRun is the drill-run data the report engine reads: the status
// (as a plain string; the service compares it against 已完成), the
// started_at of the run and the run metadata that carries the demo
// scores (metadata.demo_scores, key = indicator id, value 0–100).
type ReportRun struct {
	ID        string
	Status    string
	StartedAt *time.Time
	Metadata  map[string]any
}

// ReportSimEvent, ReportStepRecord, ReportOrder, ReportMessage and
// ReportDepartmentReport carry the drill/dispatch data the automatic
// scoring engine reads. The httpapi layer adapts the drills and
// dispatch stores behind the ReportDataSource interface, so the
// evaluation package never imports the drills or dispatch packages
// (established module boundary of the score service).
type ReportSimEvent struct {
	TriggeredAt *time.Time
	CreatedAt   time.Time
}

type ReportStepRecord struct {
	Status string
}

type ReportOrder struct {
	IssuedAt *time.Time
}

type ReportMessage struct {
	SenderType string
	SentAt     *time.Time
}

type ReportDepartmentReport struct {
	Status string
}

// ReportDataSource supplies the drill-run and dispatch data of the
// report engine. The composition root adapts the drills and dispatch
// stores behind this interface; the drills ErrRunNotFound travels
// unchanged and maps to HTTP 404 in the routing layer.
type ReportDataSource interface {
	GetRun(ctx context.Context, runID string) (ReportRun, error)
	ListSimEvents(ctx context.Context, runID string) ([]ReportSimEvent, error)
	ListStepRecords(ctx context.Context, runID string) ([]ReportStepRecord, error)
	ListOrders(ctx context.Context, runID string) ([]ReportOrder, error)
	ListMessages(ctx context.Context, runID string) ([]ReportMessage, error)
	ListDepartmentReports(ctx context.Context, runID string) ([]ReportDepartmentReport, error)
}
