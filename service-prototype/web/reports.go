package web

import (
	"html/template"
	"io"
)

// ReportsPageData carries the display data of the comprehensive
// evaluation and report page (综合评估与报告). The page renders purely
// from this in-memory payload — no database access, no API call — so
// the caller builds it from the evaluation seed dictionary, the example
// drill-runs fixture (with its demo scores, score records and the
// report snapshot produced by the evaluation report service) and never
// constructs ids itself: every id rendered by the page is carried by
// the data (the fixture ULIDs).
type ReportsPageData struct {
	// Runs is the drill-run dropdown of the selector (演练选择器); the
	// option values are the fixed 26-character ULIDs of the fixture
	// runs. The selector submits run_id through a plain GET form that
	// reloads the page.
	Runs []RunOptionView
	// SelectedRunID is the run_id query value ("" when none selected).
	SelectedRunID string
	// Run is the selected completed run (nil when no run is selected,
	// which also hides the whole report area).
	Run *SelectedRunView
	// Notice is the page hint: "" while the report renders, 请选择演练
	// when no run is selected and 演练不存在或未完成 when the selected
	// run is unknown or not completed (both answer 200, never 500).
	Notice string
	// AutoScores are the seven computable indicators (demo=false) with
	// their report scores, in dimension + sort_order order (the
	// evaluation seed order). The value is the report's per-indicator
	// final score; HasScore is false only when the engine produced no
	// entry (a fixture with all seven scored renders every value).
	AutoScores []IndicatorScoreView
	// DemoIndicators are the eight presentation indicators (demo=true);
	// their values come from run.metadata.demo_scores (key = indicator
	// id): HasScore is true with the Score only when the metadata
	// provides the key, otherwise the page renders 未评分(演示).
	DemoIndicators []DemoIndicatorView
	// DemoScoreForm is the presentation-score form of the selected run
	// (PUT /drills/{id}, metadata replaced wholesale); nil when no run
	// is selected. It carries the hidden scenario_id and title of the
	// run (both required by the drills PUT contract — a missing field
	// is a 400) and one input per demo indicator whose name embeds the
	// indicator id, the same key format the report engine reads from
	// metadata.demo_scores.
	DemoScoreForm *DemoScoreFormView
	// ScoreForms are the expert/self/peer scoring forms of the selected
	// run, grouped by indicator. A form for an (indicator, score_type)
	// pair with an existing score record targets PUT
	// /evaluation/runs/{rid}/scores/{sid} (the sid comes from the
	// fixture record), otherwise POST /evaluation/runs/{rid}/scores.
	// The field set follows the scores contract: indicator_id (hidden,
	// POST forms only — a PUT body has it ignored by the server),
	// score_type, rater, target (expert forms omit it — the server
	// forces an empty string), score and comment. created_by is
	// deliberately absent (optional, the server defaults it to "" in
	// this auth-less prototype).
	ScoreForms []IndicatorScoreFormsView
	// GenerateAction is the report generate/refresh form target (POST
	// /evaluation/runs/{rid}/reports/generate).
	GenerateAction string
	// Report is the report area (总分、6 维度得分、指标明细与建议列表);
	// nil when no run is selected.
	Report *ReportAreaView
}

// RunOptionView is one option of the drill-run selector: the fixed
// 26-character ULID of a fixture run and its title.
type RunOptionView struct {
	ID    string
	Title string
}

// SelectedRunView is the selected completed run: its fixed id, title
// and the 已完成 status.
type SelectedRunView struct {
	ID     string
	Title  string
	Status string
}

// IndicatorScoreView is one computable indicator of the auto-score
// area: the indicator fields and the report score formatted with 1
// decimal. HasScore is false when the report carries no entry for the
// indicator.
type IndicatorScoreView struct {
	ID        string
	Dimension string
	Title     string
	SortOrder int
	Score     string
	HasScore  bool
}

// DemoIndicatorView is one presentation indicator of the demo-indicator
// area: the indicator fields and the metadata.demo_scores value
// formatted with 1 decimal (HasScore=false renders 未评分(演示)).
type DemoIndicatorView struct {
	ID        string
	Dimension string
	Title     string
	SortOrder int
	Score     string
	HasScore  bool
}

// DemoScoreFormView is the presentation-score form of the selected run:
// the run id (form target PUT /drills/{id}), the hidden scenario_id and
// title the drills PUT contract requires (PUT replaces the run
// wholesale — omitting them would 400 and reset the run fields) and the
// per-demo-indicator score inputs. The input name embeds the indicator
// id (metadata.demo_scores.<id>), the same key format the report engine
// reads; the fixture metadata carries only the demo_scores key, so the
// wholesale replacement never drops other keys.
type DemoScoreFormView struct {
	RunID      string
	ScenarioID string
	Title      string
	Inputs     []DemoScoreInputView
}

// DemoScoreInputView is one demo-indicator score input of the
// presentation-score form: the indicator id (embedded in the input
// name), the indicator title and the current value ("" when the run
// metadata carries no demo score yet).
type DemoScoreInputView struct {
	IndicatorID string
	Title       string
	Value       string
}

// IndicatorScoreFormsView groups the three scoring forms (专家评分 /
// 自评 / 互评) of one indicator.
type IndicatorScoreFormsView struct {
	IndicatorID string
	Title       string
	Forms       []ScoreFormView
}

// ScoreFormView is one scoring form: POST (no existing record) or PUT
// (an existing record of the same run+indicator+score_type, prefilled
// with its values). ShowTarget is false for 专家评分 forms (the target
// input is omitted — the server forces an empty string); the POST
// forms carry the hidden indicator_id, the PUT forms never do (the
// server ignores it).
type ScoreFormView struct {
	Method      string // "POST" (hx-post) or "PUT" (hx-put)
	Action      string
	ScoreType   string
	IndicatorID string
	Rater       string
	Target      string
	Score       string
	Comment     string
	ShowTarget  bool
	HasRecord   bool
}

// ReportAreaView is the report area of the page: the overall score, the
// six dimension scores, the 15 indicator detail rows (dimension +
// sort_order order, auto/demo flag) and the suggestion list in report
// order.
type ReportAreaView struct {
	OverallScore    string
	DimensionScores []DimensionScoreView
	IndicatorRows   []IndicatorRowView
	Suggestions     []SuggestionView
}

// DimensionScoreView is one dimension score of the report area: the
// dimension name and the score formatted with 1 decimal.
type DimensionScoreView struct {
	Dimension string
	Score     string
}

// IndicatorRowView is one indicator row of the 指标明细 table: the
// indicator fields, the auto/demo flag and the report score formatted
// with 1 decimal (HasScore=false renders 未评分(演示) for demo
// indicators and 未评分 otherwise).
type IndicatorRowView struct {
	ID        string
	Dimension string
	Title     string
	SortOrder int
	Demo      bool
	Score     string
	HasScore  bool
}

// SuggestionView is one rule-based improvement suggestion of the
// report: the owning dimension, the level and the text, in the report
// order.
type SuggestionView struct {
	Dimension string
	Level     string
	Text      string
}

// reportsTemplate is the parsed template collection of the
// comprehensive evaluation and report page (layout + reports page). It
// lives in its own template set because the layout's content/title
// hooks are page-specific: every page defines its own content block,
// and one shared parse set would let the alphabetically last page win
// for every page (same pattern as the scenarios, drills and indicators
// pages).
var reportsTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/reports.html"))

// RenderReports renders the comprehensive evaluation and report page
// (layout + reports content) with the given in-memory display data. All
// user-controlled input is HTML-escaped by html/template.
func RenderReports(w io.Writer, data ReportsPageData) error {
	return reportsTemplate.ExecuteTemplate(w, "layout.html", data)
}
