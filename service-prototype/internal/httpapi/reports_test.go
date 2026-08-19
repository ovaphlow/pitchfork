package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

const (
	reportsListPath   = "/crate-api/prototype/v1/evaluation/reports"
	reportRunPath     = "/crate-api/prototype/v1/evaluation/runs"
	generateSuffix    = "/reports/generate"
	reportSuffix      = "/report"
	indicatorsPathAPI = "/crate-api/prototype/v1/evaluation/indicators"
	scoresPathAPI     = "/crate-api/prototype/v1/evaluation/runs"
)

// reportJSON mirrors the report response for assertions.
type reportJSON struct {
	ID              string  `json:"id"`
	RunID           string  `json:"run_id"`
	OverallScore    float64 `json:"overall_score"`
	DimensionScores map[string]struct {
		Score     float64            `json:"score"`
		Breakdown map[string]float64 `json:"breakdown"`
	} `json:"dimension_scores"`
	IndicatorScores map[string]struct {
		Score    float64  `json:"score"`
		Auto     *float64 `json:"auto"`
		Expert   *float64 `json:"expert"`
		SelfPeer *float64 `json:"self_peer"`
		Demo     *float64 `json:"demo"`
	} `json:"indicator_scores"`
	Suggestions []struct {
		Dimension string `json:"dimension"`
		Level     string `json:"level"`
		Text      string `json:"text"`
	} `json:"suggestions"`
	CreatedBy string `json:"created_by"`
	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at"`
}

type reportListJSON struct {
	Records []reportJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

var crockford26HTTP = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

func decodeReport(t *testing.T, recorder *httptest.ResponseRecorder) reportJSON {
	t.Helper()
	var report reportJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &report); err != nil {
		t.Fatalf("body %q is not a report JSON: %v", recorder.Body.String(), err)
	}
	return report
}

func decodeReportList(t *testing.T, recorder *httptest.ResponseRecorder) reportListJSON {
	t.Helper()
	var list reportListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// completedRun creates a scenario, a run, starts and completes it
// (the only run state that admits report generation), and returns the
// run.
func completedRun(t *testing.T, handler http.Handler) runJSON {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	run := createRun(t, handler, scenario.ID, "")
	if recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", ""); recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	if recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/complete", ""); recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return run
}

func generateReport(t *testing.T, handler http.Handler, runID string, wantStatus int) reportJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, reportRunPath+"/"+runID+generateSuffix, "")
	if recorder.Code != wantStatus {
		t.Fatalf("generate status = %d, want %d; body = %s", recorder.Code, wantStatus, recorder.Body.String())
	}
	return decodeReport(t, recorder)
}

// ─── 生命周期：201 / 200 覆盖 / GET / 失败路径 / 列表 / 级联 ─────────

// TestReportGenerateLifecycle walks the full report lifecycle through
// the API: the empty list contract, the 404 before generation, the
// first 201 with the complete 演练数据不足 report object, the 200
// overwrite preserving id/created_at and refreshing updated_at, the
// GET reflecting the latest generation, the 400 on non-已完成 runs, the
// 404 on missing runs, the list filter/pagination/limit contract and
// the run-deletion cascade.
func TestReportGenerateLifecycle(t *testing.T) {
	handler := testMux(nil)

	// 空列表契约。
	empty := decodeReportList(t, get(handler, reportsListPath, nil))
	if empty.Meta.Total != 0 || len(empty.Records) != 0 {
		t.Fatalf("empty list = %d/%d, want 0/0", len(empty.Records), empty.Meta.Total)
	}

	run := completedRun(t, handler)
	// 额外的 run 复用同一场景（未开始 / 已终止）。
	scenario := createScenario(t, handler, validScenarioBody)

	// GET 未生成 → 404。
	recorder := get(handler, reportRunPath+"/"+run.ID+reportSuffix, nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET before generation status = %d, want 404", recorder.Code)
	}
	if got := decodeError(t, recorder); got != "report not found" {
		t.Errorf("GET before generation error = %q, want %q", got, "report not found")
	}

	// 首次生成 → 201：完整报告对象。
	first := generateReport(t, handler, run.ID, http.StatusCreated)
	if !crockford26HTTP.MatchString(first.ID) {
		t.Errorf("id %q is not a 26-character Crockford Base32 ULID", first.ID)
	}
	if first.RunID != run.ID {
		t.Errorf("run_id = %q, want %q", first.RunID, run.ID)
	}
	if first.OverallScore != 0 {
		t.Errorf("overall_score = %v, want 0 (bare run)", first.OverallScore)
	}
	if len(first.DimensionScores) != 0 || len(first.IndicatorScores) != 0 {
		t.Errorf("dimension/indicator scores = %v/%v, want empty objects", first.DimensionScores, first.IndicatorScores)
	}
	if len(first.Suggestions) != 1 ||
		first.Suggestions[0].Dimension != "" || first.Suggestions[0].Level != "" ||
		first.Suggestions[0].Text != "演练数据不足，请补全评分后重新生成。" {
		t.Errorf("suggestions = %+v, want the single 演练数据不足 notice", first.Suggestions)
	}
	if first.CreatedBy != "" {
		t.Errorf("created_by = %q, want the default empty string", first.CreatedBy)
	}
	if first.CreatedAt == "" || first.UpdatedAt == "" {
		t.Errorf("created_at/updated_at = %q/%q, want server-maintained timestamps", first.CreatedAt, first.UpdatedAt)
	}

	// 重新生成 → 200：原地覆盖，id/created_at 保留，updated_at 刷新，
	// 响应体与 201 同构（完整报告对象）。
	second := generateReport(t, handler, run.ID, http.StatusOK)
	if second.ID != first.ID {
		t.Errorf("regenerated id = %q, want %q (preserved)", second.ID, first.ID)
	}
	if second.CreatedAt != first.CreatedAt {
		t.Errorf("regenerated created_at = %q, want %q (preserved)", second.CreatedAt, first.CreatedAt)
	}
	if second.UpdatedAt < first.UpdatedAt {
		t.Errorf("regenerated updated_at = %q, want refreshed after %q", second.UpdatedAt, first.UpdatedAt)
	}
	if second.RunID != run.ID || second.CreatedBy != "" {
		t.Errorf("regenerated run_id/created_by = %q/%q, want %q/''", second.RunID, second.CreatedBy, run.ID)
	}
	if len(second.Suggestions) != 1 || second.OverallScore != first.OverallScore {
		t.Errorf("regenerated content = %+v, want the same report shape as the 201", second)
	}

	// GET 已生成 → 200：与最近一次 generate 结果一致。
	recorder = get(handler, reportRunPath+"/"+run.ID+reportSuffix, nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after generation status = %d, want 200", recorder.Code)
	}
	fetched := decodeReport(t, recorder)
	if fetched.ID != second.ID || fetched.OverallScore != second.OverallScore ||
		len(fetched.Suggestions) != len(second.Suggestions) {
		t.Errorf("GET report = %+v, want the latest generation %+v", fetched, second)
	}

	// run 状态非已完成 → 400（未开始 / 已终止）。
	runNotStarted := createRun(t, handler, scenario.ID, "")
	recorder = do(handler, http.MethodPost, reportRunPath+"/"+runNotStarted.ID+generateSuffix, "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("generate on 未开始 run status = %d, want 400", recorder.Code)
	}
	if got := decodeError(t, recorder); got != "run status 未开始 does not allow report generation" {
		t.Errorf("未开始 error = %q", got)
	}
	// run 非已完成也从未有报告 → GET report 404。
	recorder = get(handler, reportRunPath+"/"+runNotStarted.ID+reportSuffix, nil)
	if recorder.Code != http.StatusNotFound {
		t.Errorf("GET report of a non-completed run status = %d, want 404", recorder.Code)
	}
	runTerminated := createRun(t, handler, scenario.ID, "")
	if recorder := do(handler, http.MethodPost, runsPath+"/"+runTerminated.ID+"/start", ""); recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d", recorder.Code)
	}
	if recorder := do(handler, http.MethodPost, runsPath+"/"+runTerminated.ID+"/terminate", ""); recorder.Code != http.StatusOK {
		t.Fatalf("terminate status = %d", recorder.Code)
	}
	recorder = do(handler, http.MethodPost, reportRunPath+"/"+runTerminated.ID+generateSuffix, "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("generate on 已终止 run status = %d, want 400", recorder.Code)
	}
	if got := decodeError(t, recorder); got != "run status 已终止 does not allow report generation" {
		t.Errorf("已终止 error = %q", got)
	}

	// run 不存在（含非 ULID 的 rid）→ 404，错误体统一。
	for _, rid := range []string{"no-such-run", "garbage"} {
		recorder = do(handler, http.MethodPost, reportRunPath+"/"+rid+generateSuffix, "")
		if recorder.Code != http.StatusNotFound {
			t.Errorf("generate on %q status = %d, want 404", rid, recorder.Code)
		}
		if got := decodeError(t, recorder); got != "drill not found" {
			t.Errorf("generate on %q error = %q, want %q", rid, got, "drill not found")
		}
	}
	recorder = get(handler, reportRunPath+"/no-such-run"+reportSuffix, nil)
	if recorder.Code != http.StatusNotFound {
		t.Errorf("GET report of a missing run status = %d, want 404", recorder.Code)
	}

	// 列表：run_id 筛选、分页、非法参数。
	list := decodeReportList(t, get(handler, reportsListPath, nil))
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != first.ID {
		t.Fatalf("list = %d/%d, want the single report of run %s", len(list.Records), list.Meta.Total, run.ID)
	}
	filtered := decodeReportList(t, get(handler, reportsListPath+"?run_id="+run.ID, nil))
	if filtered.Meta.Total != 1 || filtered.Records[0].RunID != run.ID {
		t.Errorf("run_id filter = %+v, want the report of %s", filtered, run.ID)
	}
	for _, rid := range []string{"unknown-run", "not-a-26-char-ulid"} {
		none := decodeReportList(t, get(handler, reportsListPath+"?run_id="+rid, nil))
		if none.Meta.Total != 0 || len(none.Records) != 0 {
			t.Errorf("run_id=%s filter = %d/%d, want the empty list", rid, len(none.Records), none.Meta.Total)
		}
	}
	page := decodeReportList(t, get(handler, reportsListPath+"?limit=0", nil))
	if page.Meta.Total != 1 || len(page.Records) != 0 {
		t.Errorf("limit=0 = %d/%d, want 0/1", len(page.Records), page.Meta.Total)
	}
	tail := decodeReportList(t, get(handler, reportsListPath+"?offset=1", nil))
	if tail.Meta.Total != 1 || len(tail.Records) != 0 {
		t.Errorf("offset=1 = %d/%d, want 0/1", len(tail.Records), tail.Meta.Total)
	}
	for _, query := range []string{"limit=-1", "limit=abc", "offset=-1", "offset=abc"} {
		recorder = get(handler, reportsListPath+"?"+query, nil)
		if recorder.Code != http.StatusBadRequest {
			t.Errorf("%s status = %d, want 400", query, recorder.Code)
			continue
		}
		message := "invalid limit"
		if len(query) > 6 && query[:6] == "offset" {
			message = "invalid offset"
		}
		if got := decodeError(t, recorder); got != message {
			t.Errorf("%s error = %q, want %q", query, got, message)
		}
	}

	// 列表集合只读：POST → 405 带 Allow。
	recorder = do(handler, http.MethodPost, reportsListPath, "{}")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Errorf("POST list status = %d, want 405", recorder.Code)
	}
	if recorder.Header().Get("Allow") != "GET" {
		t.Errorf("Allow = %q, want GET", recorder.Header().Get("Allow"))
	}

	// 删除 run → 级联删除其报告（store 层 DeleteReportsByRun）。
	recorder = do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run status = %d, want 204", recorder.Code)
	}
	recorder = get(handler, reportRunPath+"/"+run.ID+reportSuffix, nil)
	if recorder.Code != http.StatusNotFound {
		t.Errorf("GET report after run deletion status = %d, want 404", recorder.Code)
	}
	list = decodeReportList(t, get(handler, reportsListPath, nil))
	if list.Meta.Total != 0 || len(list.Records) != 0 {
		t.Errorf("list after run deletion = %d/%d, want 0/0", len(list.Records), list.Meta.Total)
	}
}

// ─── 报告内容：三类来源 + 演示指标 + 联动建议 + 覆盖生效 ─────────────

// TestReportGenerateContent drives the report content through the API:
// an expert score, two self scores (multi-rater mean), a demo score
// through run.metadata.demo_scores and a non-completed department
// report. The generated report must carry the exact aggregation (1
// decimal), the dimension/indicator breakdowns and the rule-based
// suggestions (dimension template + appended linkage suggestion), and
// a regeneration after a new expert score must overwrite the content in
// place (the GET reflects the new content).
func TestReportGenerateContent(t *testing.T) {
	handler := testMux(nil)

	// 指标字典：预警响应速度（响应速度 s1）、部门协同效率（协同效率 s1）、
	// 观众疏散组织（观众安全 s1，演示指标）。
	idAlert := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度","sort_order":1}`)
	idSynergy := postIndicator(t, handler, `{"dimension":"协同效率","title":"部门协同效率","sort_order":1}`)
	idAudience := postIndicator(t, handler, `{"dimension":"观众安全","title":"观众疏散组织","demo":true,"sort_order":1}`)

	// run 手动走状态机：部门报告只能在 进行中 写入，联动数据须在完成前落库。
	scenario := createScenario(t, handler, validScenarioBody)
	run := createRun(t, handler, scenario.ID, "")
	if recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", ""); recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d; body = %s", recorder.Code, recorder.Body.String())
	}

	// 非已完成部门报告 → 联动建议（且部门协同效率的自动分 = 0/1×100 = 0）。
	recorder := do(handler, http.MethodPut, runsPath+"/"+run.ID+"/departments/消防", `{"status":"未响应"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT department status = %d; body = %s", recorder.Code, recorder.Body.String())
	}

	if recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/complete", ""); recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d; body = %s", recorder.Code, recorder.Body.String())
	}

	// 专家评分 35 分；自评两条 60/80（多 rater 取均值 70）。
	recorder = do(handler, http.MethodPost, scoresPathAPI+"/"+run.ID+"/scores",
		fmt.Sprintf(`{"indicator_id":%q,"score_type":"专家评分","rater":"评审员","score":35}`, idAlert.ID))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST expert score status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	expertScore := decodeScore(t, recorder)
	recorder = do(handler, http.MethodPost, scoresPathAPI+"/"+run.ID+"/scores",
		fmt.Sprintf(`{"indicator_id":%q,"score_type":"自评","rater":"参演甲","target":"参演乙","score":60}`, idSynergy.ID))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST self score status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPost, scoresPathAPI+"/"+run.ID+"/scores",
		fmt.Sprintf(`{"indicator_id":%q,"score_type":"自评","rater":"参演丙","target":"参演乙","score":80}`, idSynergy.ID))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST self score status = %d; body = %s", recorder.Code, recorder.Body.String())
	}

	// 演示得分写 run.metadata.demo_scores（key = 指标 id）。
	recorder = do(handler, http.MethodPut, runsPath+"/"+run.ID,
		fmt.Sprintf(`{"scenario_id":%q,"title":%q,"metadata":{"demo_scores":{%q:88}}}`, run.ScenarioID, run.Title, idAudience.ID))
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT run metadata status = %d; body = %s", recorder.Code, recorder.Body.String())
	}

	// 首次生成 → 201：
	//   预警响应速度: 专家 35 → 35（无自动分）
	//   部门协同效率: 自动 0（1 份未响应报告）+ 自评互评 70 → (0+70)/2 = 35
	//   观众疏散组织: 演示 88 → 88
	//   总分: (35+35+88)/3 = 52.666… → 52.7
	//   建议: 响应速度 35 严重模板 + 协同效率 35 严重模板 + 联动追加（并存）。
	report := generateReport(t, handler, run.ID, http.StatusCreated)
	assertIndicatorEntry(t, report, idAlert.ID, 35, nil, f64(35), nil, nil)
	assertIndicatorEntry(t, report, idSynergy.ID, 35, f64(0), nil, f64(70), nil)
	assertIndicatorEntry(t, report, idAudience.ID, 88, nil, nil, nil, f64(88))
	if len(report.IndicatorScores) != 3 {
		t.Errorf("indicator_scores = %d entries, want 3", len(report.IndicatorScores))
	}
	if report.DimensionScores["响应速度"].Score != 35 ||
		report.DimensionScores["响应速度"].Breakdown[idAlert.ID] != 35 {
		t.Errorf("响应速度 dimension = %+v, want {35, %s: 35}", report.DimensionScores["响应速度"], idAlert.ID)
	}
	if report.DimensionScores["协同效率"].Score != 35 {
		t.Errorf("协同效率 dimension = %v, want 35", report.DimensionScores["协同效率"].Score)
	}
	if report.DimensionScores["观众安全"].Score != 88 {
		t.Errorf("观众安全 dimension = %v, want 88", report.DimensionScores["观众安全"].Score)
	}
	if len(report.DimensionScores) != 3 {
		t.Errorf("dimension_scores = %d entries, want 3", len(report.DimensionScores))
	}
	if report.OverallScore != 52.7 {
		t.Errorf("overall = %v, want 52.7", report.OverallScore)
	}
	wantSuggestions := []struct{ dimension, level, text string }{
		{"响应速度", "严重", "响应速度维度平均分 35.0 分，未达 60 分，建议优化预警发现、信息上报与预案启动流程，缩短应急响应用时。"},
		{"协同效率", "严重", "协同效率维度平均分 35.0 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。"},
		{"协同效率", "关注", "存在未完成联动处置的部门，建议跟进相关部门的处置进度并补全联动记录。"},
	}
	if len(report.Suggestions) != len(wantSuggestions) {
		t.Fatalf("suggestions = %d entries, want %d: %+v", len(report.Suggestions), len(wantSuggestions), report.Suggestions)
	}
	for i, want := range wantSuggestions {
		got := report.Suggestions[i]
		if got.Dimension != want.dimension || got.Level != want.level || got.Text != want.text {
			t.Errorf("suggestion[%d] = %+v, want %+v", i, got, want)
		}
	}

	// 重新生成 → 200：id/created_at 保留，内容相同。
	regenerated := generateReport(t, handler, run.ID, http.StatusOK)
	if regenerated.ID != report.ID || regenerated.CreatedAt != report.CreatedAt {
		t.Errorf("regenerated id/created_at = %q/%q, want %q/%q", regenerated.ID, regenerated.CreatedAt, report.ID, report.CreatedAt)
	}
	if regenerated.OverallScore != report.OverallScore {
		t.Errorf("regenerated overall = %v, want %v", regenerated.OverallScore, report.OverallScore)
	}

	// 专家评分改为 95（PUT 原地更新，专家评分同 run×指标唯一）→ 覆盖生效。
	recorder = do(handler, http.MethodPut, scoresPathAPI+"/"+run.ID+"/scores/"+expertScore.ID,
		`{"score_type":"专家评分","rater":"评审员","score":95}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT expert score status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	overwritten := generateReport(t, handler, run.ID, http.StatusOK)
	if overwritten.ID != report.ID {
		t.Errorf("overwritten id = %q, want %q", overwritten.ID, report.ID)
	}
	assertIndicatorEntry(t, overwritten, idAlert.ID, 95, nil, f64(95), nil, nil)
	// 总分 (95+35+88)/3 = 72.666… → 72.7；响应速度 ≥60 不再有模板建议。
	if overwritten.OverallScore != 72.7 {
		t.Errorf("overwritten overall = %v, want 72.7", overwritten.OverallScore)
	}
	if len(overwritten.Suggestions) != 2 ||
		overwritten.Suggestions[0].Dimension != "协同效率" ||
		overwritten.Suggestions[1].Dimension != "协同效率" ||
		overwritten.Suggestions[1].Level != "关注" {
		t.Errorf("overwritten suggestions = %+v, want [协同效率严重模板, 协同效率联动]", overwritten.Suggestions)
	}

	// GET report 反映覆盖后的新内容。
	fetched := decodeReport(t, get(handler, reportRunPath+"/"+run.ID+reportSuffix, nil))
	if fetched.OverallScore != 72.7 || fetched.ID != report.ID {
		t.Errorf("GET after overwrite = %+v, want the overwritten content", fetched)
	}
}

func assertIndicatorEntry(t *testing.T, report reportJSON, id string, score float64, auto, expert, selfPeer, demo *float64) {
	t.Helper()
	entry, ok := report.IndicatorScores[id]
	if !ok {
		t.Fatalf("indicator %s missing from indicator_scores", id)
	}
	if entry.Score != score {
		t.Errorf("indicator %s score = %v, want %v", id, entry.Score, score)
	}
	if !sameOptionalScore(entry.Auto, auto) || !sameOptionalScore(entry.Expert, expert) ||
		!sameOptionalScore(entry.SelfPeer, selfPeer) || !sameOptionalScore(entry.Demo, demo) {
		t.Errorf("indicator %s sources = auto %v / expert %v / self_peer %v / demo %v, want %v/%v/%v/%v",
			id, ptrValue(entry.Auto), ptrValue(entry.Expert), ptrValue(entry.SelfPeer), ptrValue(entry.Demo),
			ptrValue(auto), ptrValue(expert), ptrValue(selfPeer), ptrValue(demo))
	}
}

func sameOptionalScore(got, want *float64) bool {
	if got == nil || want == nil {
		return got == nil && want == nil
	}
	return *got == *want
}

func ptrValue(value *float64) any {
	if value == nil {
		return nil
	}
	return *value
}

func f64(value float64) *float64 { return &value }
