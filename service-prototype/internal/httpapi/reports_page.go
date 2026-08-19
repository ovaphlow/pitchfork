package httpapi

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// reportsPagePath is the server-rendered comprehensive evaluation and
// report page (htmx SSR, no shared client, no database).
const reportsPagePath = "/demo/evaluation/reports"

// handleReportsPage renders the comprehensive evaluation and report
// page. The display data is injected in memory — the evaluation seed
// dictionary (evaluation.SeedData) with fixed fixture ULIDs, the
// example drill-runs fixture (a completed run with its demo scores,
// score records and drill/dispatch data) and the report snapshot
// produced by the evaluation report service over exactly that fixture
// data — so the page renders without a database or a running API and
// stays consistent with GET /evaluation/runs/{rid}/report by
// construction. The run_id query parameter selects the run: missing or
// empty renders the 请选择演练 hint, an unknown id (or a fixture run
// that is not completed) renders the 演练不存在或未完成 hint, both with
// 200 — never a 500.
func handleReportsPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderReports(w, reportsPageData(r.URL.Query().Get("run_id"))); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// The fixed 26-character Crockford Base32 ULIDs of the reports page
// fixture. The runs and the expert score record are "existing" data
// (created earlier, so their ids are stable); the fixture carries every
// id the page renders (form targets, hidden fields, demo_scores keys) —
// the page itself never constructs ids.
const (
	reportsExampleRunID        = "06G02ZKK9CPE5E0YCE4EMZJH7M"
	reportsExampleScenarioID   = "06G02ZKK9EAK77X7D7RJFBAV3R"
	reportsInProgressRunID     = "06G02ZKK9CJWYDC3J9VJBP5H4M"
	reportsInProgressScenarioID = "06G02ZKK9EP9ERXSNVG7E9KAJC"
	reportsExpertScoreID       = "06G02ZKK9DYH9W79EHVYX1N1XM"
	reportsExampleRunTitle     = "迎新春特展大客流聚集综合评估演练"
	reportsInProgressRunTitle  = "市电中断应急处置综合评估演练"
	// reportsIndicatorAlertSpeedID is the fixture id of 预警响应速度
	// (响应速度 1), the indicator with an existing 专家评分 record (the
	// PUT-form instance of the scoring forms).
	reportsIndicatorAlertSpeedID = "06G02ZKK9CZ1DTC8SJQVRDQ9AR"
	// The three presentation indicators with a demo score in the fixture
	// run metadata (the scored branch of the demo indicator area); the
	// remaining five demo indicators carry no value (未评分(演示)).
	reportsIndicatorAudienceEvacID  = "06G02ZKK9F241206H8TPBJRWQ0" // 观众疏散组织 (观众安全 1)
	reportsIndicatorRelicTransferID = "06G02ZKK9EYDSDRRKWAAKPA3TG" // 文物转移保护 (文物安全 1)
	reportsIndicatorOpinionWatchID  = "06G02ZKK9FW1QD26TTPS73EYJC" // 舆情监测预警 (舆情管控 1)
)

// reportsIndicatorIDs are the fixed 26-character Crockford Base32 ULIDs
// of the fifteen built-in evaluation indicators in SeedData order (one
// per seed row). The ids are stable across renders so the fixture demo
// scores, the score records and the form fields stay in sync with the
// indicator dictionary.
var reportsIndicatorIDs = []string{
	"06G02ZKK9CZ1DTC8SJQVRDQ9AR", // 预警响应速度 (响应速度 1)
	"06G02ZKK9ECJ558BJBQGWDDH9G", // 指挥调度响应速度 (响应速度 2)
	"06G02ZKK9ENS1KXXH5HRFZER5R", // 力量到场速度 (响应速度 3)
	"06G02ZKK9EQJEPQR1Y5WH6FHWW", // 处置流程规范性 (处置规范性 1)
	"06G02ZKK9CQJQMCWJ4TH65PWH0", // 信息报告规范性 (处置规范性 2)
	"06G02ZKK9FAHN6GJCZZQHR0RM4", // 部门协同效率 (协同效率 1)
	"06G02ZKK9C9W162RWTX0FKWDNR", // 信息共享效率 (协同效率 2)
	"06G02ZKK9F241206H8TPBJRWQ0", // 观众疏散组织 (观众安全 1)
	"06G02ZKK9D2G9ZRTCRQ9M1QCEG", // 观众秩序维护 (观众安全 2)
	"06G02ZKK9D2N32Y2JJ7CQK8AF4", // 观众伤亡防控 (观众安全 3)
	"06G02ZKK9EYDSDRRKWAAKPA3TG", // 文物转移保护 (文物安全 1)
	"06G02ZKK9D2M4PAE0GJF2R2BYC", // 文物损失防控 (文物安全 2)
	"06G02ZKK9FW1QD26TTPS73EYJC", // 舆情监测预警 (舆情管控 1)
	"06G02ZKK9CJBWP1Y7PW8AWTVJG", // 信息发布引导 (舆情管控 2)
	"06G02ZKK9E4X22KCA35KW150YR", // 舆情处置效果 (舆情管控 3)
}

// reportsFixture holds the in-memory data of the completed example run:
// the run itself (with the demo scores), the full indicator dictionary,
// the score records and the drill/dispatch data the automatic scoring
// engine consumes, plus the report snapshot produced from exactly this
// data.
type reportsFixture struct {
	run               evaluation.ReportRun
	indicators        []evaluation.Indicator
	scores            []evaluation.Score
	simEvents         []evaluation.ReportSimEvent
	stepRecords       []evaluation.ReportStepRecord
	orders            []evaluation.ReportOrder
	messages          []evaluation.ReportMessage
	departmentReports []evaluation.ReportDepartmentReport
	report            evaluation.Report
}

// reportsFixtureSource adapts the fixed page fixture to the evaluation
// ReportDataSource interface: the fixture drill/dispatch data (sim
// events, step records, orders, messages, department reports) is
// injected in memory, so the report snapshot of the page is produced by
// the real scoring engine over exactly the data the page displays — no
// database, no API call.
type reportsFixtureSource struct {
	fixture reportsFixture
}

func (s reportsFixtureSource) GetRun(_ context.Context, runID string) (evaluation.ReportRun, error) {
	if runID != s.fixture.run.ID {
		return evaluation.ReportRun{}, drills.ErrRunNotFound
	}
	return s.fixture.run, nil
}

func (s reportsFixtureSource) ListSimEvents(_ context.Context, _ string) ([]evaluation.ReportSimEvent, error) {
	return s.fixture.simEvents, nil
}

func (s reportsFixtureSource) ListStepRecords(_ context.Context, _ string) ([]evaluation.ReportStepRecord, error) {
	return s.fixture.stepRecords, nil
}

func (s reportsFixtureSource) ListOrders(_ context.Context, _ string) ([]evaluation.ReportOrder, error) {
	return s.fixture.orders, nil
}

func (s reportsFixtureSource) ListMessages(_ context.Context, _ string) ([]evaluation.ReportMessage, error) {
	return s.fixture.messages, nil
}

func (s reportsFixtureSource) ListDepartmentReports(_ context.Context, _ string) ([]evaluation.ReportDepartmentReport, error) {
	return s.fixture.departmentReports, nil
}

// buildReportsFixture assembles the page fixture and generates its
// report snapshot through the evaluation report service over in-memory
// stores. The fixture data is static and deterministic (fixed ULIDs,
// fixed timestamps), so the engine output is stable: the seven
// computable indicators are all scored (auto values derived from the
// sim-event / step-record / order / message / department-report data),
// the three demo indicators with a demo_scores value are scored, the
// five remaining demo indicators carry no value, and the suggestions
// follow from the dimension averages and the unfinished department
// report. A generation error can only be a programming error in the
// fixture, so it panics loudly (same spirit as template.Must).
func buildReportsFixture() reportsFixture {
	now := time.Date(2026, 8, 1, 10, 0, 0, 0, time.UTC)
	triggeredAt := now.Add(60 * time.Second)
	issuedAt := now.Add(120 * time.Second)
	sentAt := now.Add(180 * time.Second)

	fixture := reportsFixture{
		// The run metadata carries only the demo_scores key (the
		// wholesale replacement of the demo-score form PUT never drops
		// other keys).
		run: evaluation.ReportRun{
			ID:        reportsExampleRunID,
			Status:    string(drills.RunStatusCompleted),
			StartedAt: &now,
			Metadata: map[string]any{
				"demo_scores": map[string]any{
					reportsIndicatorAudienceEvacID:  float64(88),
					reportsIndicatorRelicTransferID: float64(90),
					reportsIndicatorOpinionWatchID:  float64(75),
				},
			},
		},
		indicators: reportsFixtureIndicators(),
		// The single 专家评分 record of 预警响应速度 is the PUT-form
		// instance of the scoring forms (every other indicator ×
		// score_type pair renders a POST form).
		scores: []evaluation.Score{
			{
				ID:          reportsExpertScoreID,
				RunID:       reportsExampleRunID,
				IndicatorID: reportsIndicatorAlertSpeedID,
				ScoreType:   evaluation.ScoreTypeExpert,
				Rater:       "评审员",
				Score:       100,
				Comment:     "响应及时",
				CreatedAt:   now,
				UpdatedAt:   now,
			},
		},
		// The drill/dispatch data behind the seven auto scores: one
		// triggered sim event (预警响应速度), one issued order (指挥调
		// 度响应速度), one 现场人员 message (力量到场速度 and 信息共享效
		// 率), five step records 4 已执行 / 1 跳过 (处置流程规范性 and
		// 信息报告规范性) and one unfinished department report (部门协
		// 同效率 0 and the linkage suggestion).
		simEvents: []evaluation.ReportSimEvent{
			{TriggeredAt: &triggeredAt, CreatedAt: triggeredAt},
		},
		stepRecords: []evaluation.ReportStepRecord{
			{Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"}, {Status: "跳过"},
		},
		orders: []evaluation.ReportOrder{
			{IssuedAt: &issuedAt},
		},
		messages: []evaluation.ReportMessage{
			{SenderType: "现场人员", SentAt: &sentAt},
		},
		departmentReports: []evaluation.ReportDepartmentReport{
			{Status: "未响应"},
		},
	}
	fixture.report = reportsFixtureReport(context.Background(), fixture)
	return fixture
}

// reportsFixtureIndicators converts the built-in seed dictionary into
// the fixture indicator rows with the fixed ULIDs, mirroring the seed
// function (weight 1, per-dimension sort_order 1..N in SeedData order,
// created_by system).
func reportsFixtureIndicators() []evaluation.Indicator {
	now := time.Date(2026, 8, 1, 9, 0, 0, 0, time.UTC)
	indicators := make([]evaluation.Indicator, 0, len(evaluation.SeedData))
	orderByDimension := make(map[evaluation.Dimension]int, len(evaluation.SeedData))
	for i, seed := range evaluation.SeedData {
		orderByDimension[seed.Dimension]++
		indicators = append(indicators, evaluation.Indicator{
			ID:          reportsIndicatorIDs[i],
			Dimension:   seed.Dimension,
			Title:       seed.Title,
			Weight:      1,
			Demo:        seed.Demo,
			SortOrder:   orderByDimension[seed.Dimension],
			Description: seed.Description,
			CreatedBy:   "system",
			CreatedAt:   now,
			UpdatedAt:   now,
		})
	}
	return indicators
}

// reportsFixtureReport runs the evaluation report service over the
// in-memory stores seeded with the fixture data and returns the report
// snapshot. The handler and the rendering tests share this builder, so
// the page report area matches GET /evaluation/runs/{rid}/report by
// construction.
func reportsFixtureReport(ctx context.Context, fixture reportsFixture) evaluation.Report {
	indicatorStore := evaluation.NewInMemoryStore()
	for _, item := range fixture.indicators {
		if err := indicatorStore.CreateIndicator(ctx, item); err != nil {
			panic("reports page fixture: create indicator: " + err.Error())
		}
	}
	scoreStore := evaluation.NewInMemoryScoreStore()
	for _, item := range fixture.scores {
		if err := scoreStore.CreateScore(ctx, item); err != nil {
			panic("reports page fixture: create score: " + err.Error())
		}
	}
	reportStore := evaluation.NewInMemoryReportStore()
	service := evaluation.NewReportService(reportStore, indicatorStore, scoreStore, reportsFixtureSource{fixture: fixture})
	report, _, err := service.GenerateReport(ctx, fixture.run.ID)
	if err != nil {
		panic("reports page fixture: generate report: " + err.Error())
	}
	return report
}

// reportsPageData converts the page fixture into the view model for the
// given run_id selection.
func reportsPageData(runID string) web.ReportsPageData {
	fixture := buildReportsFixture()
	data := web.ReportsPageData{
		Runs: []web.RunOptionView{
			{ID: reportsExampleRunID, Title: reportsExampleRunTitle},
			{ID: reportsInProgressRunID, Title: reportsInProgressRunTitle},
		},
		SelectedRunID: runID,
	}
	switch {
	case runID == "":
		data.Notice = "请选择演练"
	case runID != reportsExampleRunID:
		// An unknown run_id (or the in-progress example run, which
		// never admits a report) renders the same hint with 200 —
		// never a 500.
		data.Notice = "演练不存在或未完成"
	default:
		data.Run = &web.SelectedRunView{
			ID:     fixture.run.ID,
			Title:  reportsExampleRunTitle,
			Status: string(drills.RunStatusCompleted),
		}
		data.AutoScores = reportsAutoScoreViews(fixture)
		data.DemoIndicators = reportsDemoIndicatorViews(fixture)
		data.DemoScoreForm = reportsDemoScoreFormView(fixture)
		data.ScoreForms = reportsScoreFormViews(runID, fixture)
		// The generate/refresh form target derives from the registered
		// reports/generate route (reportsGenerateBase), so the page
		// action can never drift from the contract.
		data.GenerateAction = strings.Replace(reportsGenerateBase, "{rid}", runID, 1)
		data.Report = reportsReportAreaView(fixture)
	}
	return data
}

// reportsDemoScores reads the demo score values of the run metadata
// (metadata.demo_scores, key = indicator id), the same reading 口径 of
// the report engine.
func reportsDemoScores(metadata map[string]any) map[string]float64 {
	raw, ok := metadata["demo_scores"].(map[string]any)
	if !ok {
		return nil
	}
	values := make(map[string]float64, len(raw))
	for id, value := range raw {
		switch number := value.(type) {
		case float64:
			values[id] = number
		case int:
			values[id] = float64(number)
		}
	}
	return values
}

// reportsAutoScoreViews builds the seven computable indicator rows of
// the auto-score area in dimension + sort_order order (the seed order).
// The displayed value is the report's per-indicator final score.
func reportsAutoScoreViews(fixture reportsFixture) []web.IndicatorScoreView {
	views := make([]web.IndicatorScoreView, 0, 7)
	for _, indicator := range fixture.indicators {
		if indicator.Demo {
			continue
		}
		view := web.IndicatorScoreView{
			ID:        indicator.ID,
			Dimension: string(indicator.Dimension),
			Title:     indicator.Title,
			SortOrder: indicator.SortOrder,
		}
		if entry, ok := fixture.report.IndicatorScores[indicator.ID]; ok {
			view.HasScore = true
			view.Score = format1(entry.Score)
		}
		views = append(views, view)
	}
	return views
}

// reportsDemoIndicatorViews builds the eight presentation indicator
// rows of the demo-indicator area in seed order: a row carries its
// metadata.demo_scores value (formatted) or renders 未评分(演示) when
// the metadata provides no key.
func reportsDemoIndicatorViews(fixture reportsFixture) []web.DemoIndicatorView {
	demoValues := reportsDemoScores(fixture.run.Metadata)
	views := make([]web.DemoIndicatorView, 0, 8)
	for _, indicator := range fixture.indicators {
		if !indicator.Demo {
			continue
		}
		view := web.DemoIndicatorView{
			ID:        indicator.ID,
			Dimension: string(indicator.Dimension),
			Title:     indicator.Title,
			SortOrder: indicator.SortOrder,
		}
		if value, ok := demoValues[indicator.ID]; ok {
			view.HasScore = true
			view.Score = format1(value)
		}
		views = append(views, view)
	}
	return views
}

// reportsDemoScoreFormView builds the presentation-score form of the
// selected run: the PUT /drills/{id} target, the hidden scenario_id and
// title the drills PUT contract requires (PUT replaces the run
// wholesale) and one prefilled input per demo indicator (name
// metadata.demo_scores.<id>, the key format of the report engine).
func reportsDemoScoreFormView(fixture reportsFixture) *web.DemoScoreFormView {
	demoValues := reportsDemoScores(fixture.run.Metadata)
	form := &web.DemoScoreFormView{
		RunID:      fixture.run.ID,
		ScenarioID: reportsExampleScenarioID,
		Title:      reportsExampleRunTitle,
	}
	for _, indicator := range fixture.indicators {
		if !indicator.Demo {
			continue
		}
		input := web.DemoScoreInputView{IndicatorID: indicator.ID, Title: indicator.Title}
		if value, ok := demoValues[indicator.ID]; ok {
			input.Value = format1(value)
		}
		form.Inputs = append(form.Inputs, input)
	}
	return form
}

// reportsScoreFormViews builds the expert/self/peer scoring forms of
// the selected run, grouped by indicator. An (indicator, score_type)
// pair with an existing fixture record renders a PUT form to the record
// (the sid comes from the fixture), every other pair renders a POST
// form to the scores collection.
func reportsScoreFormViews(runID string, fixture reportsFixture) []web.IndicatorScoreFormsView {
	existing := make(map[[2]string]evaluation.Score, len(fixture.scores))
	for _, score := range fixture.scores {
		existing[[2]string{score.IndicatorID, string(score.ScoreType)}] = score
	}
	// The form targets derive from the registered scores route
	// (scoresBase), so they can never drift from the contract.
	collection := strings.Replace(scoresBase, "{rid}", runID, 1)
	views := make([]web.IndicatorScoreFormsView, 0, len(fixture.indicators))
	for _, indicator := range fixture.indicators {
		group := web.IndicatorScoreFormsView{IndicatorID: indicator.ID, Title: indicator.Title}
		for _, scoreType := range []evaluation.ScoreType{
			evaluation.ScoreTypeExpert, evaluation.ScoreTypeSelf, evaluation.ScoreTypePeer,
		} {
			form := web.ScoreFormView{
				Method:      "POST",
				Action:      collection,
				ScoreType:   string(scoreType),
				IndicatorID: indicator.ID,
				ShowTarget:  scoreType != evaluation.ScoreTypeExpert,
			}
			if record, ok := existing[[2]string{indicator.ID, string(scoreType)}]; ok {
				// PUT the existing record, prefilled with its values;
				// the form carries no indicator_id (the server ignores
				// it on PUT).
				form.Method = "PUT"
				form.Action = collection + "/" + record.ID
				form.HasRecord = true
				form.Rater = record.Rater
				form.Target = record.Target
				form.Score = strconv.Itoa(record.Score)
				form.Comment = record.Comment
			}
			group.Forms = append(group.Forms, form)
		}
		views = append(views, group)
	}
	return views
}

// reportsReportAreaView builds the report area (总分、6 维度得分、指标
// 明细与建议列表) from the fixture report snapshot. The dimension rows
// follow the fixed six-dimension display order; the indicator rows
// follow dimension + sort_order (the seed order) with the auto/demo
// flag; the suggestions keep the report order.
func reportsReportAreaView(fixture reportsFixture) *web.ReportAreaView {
	report := fixture.report
	view := &web.ReportAreaView{OverallScore: format1(report.OverallScore)}
	for _, dimension := range indicatorDimensionOrder {
		entry, ok := report.DimensionScores[dimension]
		if !ok {
			continue
		}
		view.DimensionScores = append(view.DimensionScores, web.DimensionScoreView{
			Dimension: string(dimension),
			Score:     format1(entry.Score),
		})
	}
	for _, indicator := range fixture.indicators {
		row := web.IndicatorRowView{
			ID:        indicator.ID,
			Dimension: string(indicator.Dimension),
			Title:     indicator.Title,
			SortOrder: indicator.SortOrder,
			Demo:      indicator.Demo,
		}
		if entry, ok := report.IndicatorScores[indicator.ID]; ok {
			row.HasScore = true
			row.Score = format1(entry.Score)
		}
		view.IndicatorRows = append(view.IndicatorRows, row)
	}
	for _, suggestion := range report.Suggestions {
		view.Suggestions = append(view.Suggestions, web.SuggestionView{
			Dimension: string(suggestion.Dimension),
			Level:     suggestion.Level,
			Text:      suggestion.Text,
		})
	}
	return view
}

// format1 renders a score with exactly 1 decimal (the display format of
// the page, matching the report JSON values).
func format1(value float64) string {
	return strconv.FormatFloat(value, 'f', 1, 64)
}
