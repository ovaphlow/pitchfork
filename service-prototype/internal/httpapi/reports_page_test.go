package httpapi

import (
	"net/http"
	"regexp"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
)

// ─── #63 综合评估与报告页 ──────────────────────────────────────

// reportsPageULIDPattern is the 26-character Crockford Base32 ULID
// shape every run / sid / indicator id of the page must carry. The page
// itself never constructs ids — every id comes from the fixture.
var reportsPageULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// GET /demo/evaluation/reports?run_id=<已完成演练> 返回 200 且
// Content-Type 为 text/html; charset=utf-8，页面由 handler 注入的
// fixture 渲染：演练选择器（GET 表单，全部示例演练，option value 为
// 固定 ULID）、7 项自动得分、8 项演示指标（3 项有值 / 5 项「未评分(演
// 示)」）、演示得分表单、评分表单（PUT/POST 两种实例）、生成/刷新报告
// 表单与报告区（总分、6 维度得分、指标明细、建议列表）。
func TestReportsPageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/evaluation/reports?run_id="+reportsExampleRunID, nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "text/html; charset=utf-8" {
		t.Fatalf("Content-Type = %q, want text/html; charset=utf-8", contentType)
	}
	body := recorder.Body.String()

	// 演练选择器：GET 表单提交 run_id，列出 fixture 全部示例演练，
	// 当前选中项带 selected。
	if !strings.Contains(body, `<form method="get" action="/demo/evaluation/reports">`) {
		t.Fatalf("selector is not a GET form reloading the page")
	}
	for _, title := range []string{reportsExampleRunTitle, reportsInProgressRunTitle} {
		if !strings.Contains(body, title) {
			t.Fatalf("selector does not list the example run %q", title)
		}
	}
	if !strings.Contains(body, `<option value="`+reportsExampleRunID+`" selected>`) {
		t.Fatalf("selector does not mark the selected run with its fixture ULID")
	}
	if !strings.Contains(body, `<option value="`+reportsInProgressRunID+`">`) {
		t.Fatalf("selector does not carry the in-progress run option")
	}

	// 运行信息（已完成）与 7 项自动得分（报告最终得分，维度+sort_order
	// 顺序）。
	if !strings.Contains(body, `<span class="run-status">已完成</span>`) {
		t.Fatalf("page does not render the 已完成 run status")
	}
	for _, item := range []string{
		`<li class="auto-score"><span class="indicator-title">预警响应速度</span> <span class="score">94.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">指挥调度响应速度</span> <span class="score">88.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">力量到场速度</span> <span class="score">76.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">处置流程规范性</span> <span class="score">80.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">信息报告规范性</span> <span class="score">80.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">部门协同效率</span> <span class="score">0.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">信息共享效率</span> <span class="score">76.0</span></li>`,
	} {
		if !strings.Contains(body, item) {
			t.Fatalf("auto-score row %q not rendered", item)
		}
	}

	// 8 项演示指标：3 项有 demo_scores 值显示得分，5 项无值显示
	// 「未评分(演示)」（演示区 5 处 + 指标明细 5 处 = 10 处）。
	for _, item := range []string{
		`<li class="demo-indicator"><span class="indicator-title">观众疏散组织</span> <span class="score">88.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">文物转移保护</span> <span class="score">90.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">舆情监测预警</span> <span class="score">75.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">观众秩序维护</span> <span class="unscored">未评分(演示)</span></li>`,
	} {
		if !strings.Contains(body, item) {
			t.Fatalf("demo-indicator row %q not rendered", item)
		}
	}
	if got := strings.Count(body, "未评分(演示)"); got != 10 {
		t.Fatalf("未评分(演示) count = %d, want 10 (5 demo area + 5 detail rows)", got)
	}

	// 演示得分表单：PUT /drills/{id}，hidden scenario_id + title 必填
	// 接线，8 个演示得分输入（名称嵌入指标 id，与报告引擎读取口径一致）。
	if !strings.Contains(body, `hx-put="/crate-api/prototype/v1/drills/`+reportsExampleRunID+`"`) {
		t.Fatalf("demo score form does not target PUT /drills/%s", reportsExampleRunID)
	}
	if !strings.Contains(body, `name="scenario_id" value="`+reportsExampleScenarioID+`"`) ||
		!strings.Contains(body, `name="title" value="`+reportsExampleRunTitle+`"`) {
		t.Fatalf("demo score form does not carry the required hidden scenario_id/title")
	}
	if got := strings.Count(body, `name="metadata.demo_scores.`); got != 8 {
		t.Fatalf("demo score inputs = %d, want 8", got)
	}

	// 评分表单：已存在专家评分（预警响应速度）→ PUT /scores/{sid}；
	// 其余 → POST /scores 集合；契约字段齐全（indicator_id 仅 POST、
	// target 仅自评/互评）。
	if !strings.Contains(body, `hx-put="/crate-api/prototype/v1/evaluation/runs/`+reportsExampleRunID+`/scores/`+reportsExpertScoreID+`"`) {
		t.Fatalf("expert score form does not target PUT .../scores/%s", reportsExpertScoreID)
	}
	if got := strings.Count(body, `hx-post="/crate-api/prototype/v1/evaluation/runs/`+reportsExampleRunID+`/scores"`); got != 44 {
		t.Fatalf("POST score form count = %d, want 44", got)
	}
	if got := strings.Count(body, `name="score_type"`); got != 45 {
		t.Fatalf("score_type field count = %d, want 45", got)
	}
	if got := strings.Count(body, `name="indicator_id"`); got != 44 {
		t.Fatalf("indicator_id field count = %d, want 44 (POST forms only)", got)
	}
	if got := strings.Count(body, `name="target"`); got != 30 {
		t.Fatalf("target field count = %d, want 30 (自评/互评 only)", got)
	}

	// 生成/刷新报告表单 → 依赖卡注册的 reports/generate 路由。
	if !strings.Contains(body, `hx-post="/crate-api/prototype/v1/evaluation/runs/`+reportsExampleRunID+`/reports/generate"`) {
		t.Fatalf("generate form does not target POST .../reports/generate")
	}

	// 反馈元素与表单接线：固定 id 反馈元素（role="status"），每个 htmx
	// 表单都 hx-target/hx-swap 指向它（45 POST + 2 PUT 表单）。
	if !strings.Contains(body, `<p id="report-feedback" role="status"></p>`) {
		t.Fatalf("page does not carry the #report-feedback status element")
	}
	if got := strings.Count(body, `hx-target="#report-feedback"`); got != 47 {
		t.Fatalf("hx-target count = %d, want 47 (every htmx form)", got)
	}
	if got := strings.Count(body, `hx-swap="innerHTML"`); got != 47 {
		t.Fatalf("hx-swap count = %d, want 47", got)
	}

	// 报告区：总分、6 维度得分、指标明细（15 行，自动/演示标志）与
	// 建议列表。
	if !strings.Contains(body, `<span class="overall-score">74.7</span>`) {
		t.Fatalf("page does not render the overall score 74.7")
	}
	for _, item := range []string{
		`<li><span class="dimension">响应速度</span> <span class="score">86.0</span></li>`,
		`<li><span class="dimension">处置规范性</span> <span class="score">80.0</span></li>`,
		`<li><span class="dimension">协同效率</span> <span class="score">38.0</span></li>`,
		`<li><span class="dimension">观众安全</span> <span class="score">88.0</span></li>`,
		`<li><span class="dimension">文物安全</span> <span class="score">90.0</span></li>`,
		`<li><span class="dimension">舆情管控</span> <span class="score">75.0</span></li>`,
	} {
		if !strings.Contains(body, item) {
			t.Fatalf("dimension score row %q not rendered", item)
		}
	}
	if got := strings.Count(body, `<td>自动</td>`); got != 7 {
		t.Fatalf("auto flag count = %d, want 7", got)
	}
	if got := strings.Count(body, `<td>演示</td>`); got != 8 {
		t.Fatalf("demo flag count = %d, want 8", got)
	}
	for _, text := range []string{
		"协同效率维度平均分 38.0 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。",
		"存在未完成联动处置的部门，建议跟进相关部门的处置进度并补全联动记录。",
	} {
		if !strings.Contains(body, text) {
			t.Fatalf("page does not render the suggestion %q", text)
		}
	}

	// 页面不构造 ID：没有任何 id 输入项。
	if strings.Contains(body, `name="id"`) {
		t.Fatalf("page must not construct ids (no name=\"id\" input)")
	}
}

// run_id 缺失/为空 → 200 + 「请选择演练」；未知或未完成的 run_id →
// 200 + 「演练不存在或未完成」；两者都渲染提示而非 500，且选择器仍在。
func TestReportsPageSelectionBranches(t *testing.T) {
	handler := testMux(nil)
	for name, target := range map[string]string{
		"missing":    "/demo/evaluation/reports",
		"empty":      "/demo/evaluation/reports?run_id=",
		"unknown":    "/demo/evaluation/reports?run_id=06G02ZKK9FSSP2YTTV0GXDB6FG",
		"in-progress": "/demo/evaluation/reports?run_id=" + reportsInProgressRunID,
	} {
		recorder := get(handler, target, nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200 (never 500)", name, recorder.Code)
		}
		body := recorder.Body.String()
		if name == "missing" || name == "empty" {
			if !strings.Contains(body, "请选择演练") {
				t.Fatalf("%s: page does not render the 请选择演练 hint", name)
			}
			if strings.Contains(body, "演练不存在或未完成") {
				t.Fatalf("%s: hint must be 请选择演练, got 演练不存在或未完成", name)
			}
		} else {
			if !strings.Contains(body, "演练不存在或未完成") {
				t.Fatalf("%s: page does not render the 演练不存在或未完成 hint", name)
			}
		}
		if strings.Contains(body, `id="report-area"`) || strings.Contains(body, "总分") {
			t.Fatalf("%s: report area must not render without a completed run", name)
		}
		if !strings.Contains(body, `<option value="`+reportsExampleRunID+`"`) {
			t.Fatalf("%s: selector must still list the fixture runs", name)
		}
	}
}

// 页面报告区数据与依赖卡报告接口一致：渲染测试用同一个 fixture 构造器
// （buildReportsFixture，内部经评估报告服务的内存注入生成报告）作为期
// 望值，断言页面渲染的总分、6 维度得分、指标明细与建议列表与之一致。
func TestReportsPageReportMatchesService(t *testing.T) {
	fixture := buildReportsFixture()
	report := fixture.report
	recorder := get(testMux(nil), "/demo/evaluation/reports?run_id="+reportsExampleRunID, nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	body := recorder.Body.String()

	if !strings.Contains(body, `<span class="overall-score">`+format1(report.OverallScore)+`</span>`) {
		t.Fatalf("overall score %s not rendered", format1(report.OverallScore))
	}
	for _, dimension := range indicatorDimensionOrder {
		entry, ok := report.DimensionScores[dimension]
		if !ok {
			t.Fatalf("fixture report misses dimension %s", dimension)
		}
		item := `<li><span class="dimension">` + string(dimension) + `</span> <span class="score">` + format1(entry.Score) + `</span></li>`
		if !strings.Contains(body, item) {
			t.Fatalf("dimension row %q not rendered", item)
		}
	}
	for _, indicator := range fixture.indicators {
		if entry, ok := report.IndicatorScores[indicator.ID]; ok {
			if !strings.Contains(body, `<td>`+format1(entry.Score)+`</td>`) {
				t.Fatalf("indicator %s score %s not rendered in the detail table",
					indicator.Title, format1(entry.Score))
			}
		} else if indicator.Demo && !strings.Contains(body, "未评分(演示)") {
			t.Fatalf("demo indicator %s without demo_scores must render 未评分(演示)", indicator.Title)
		}
	}
	last := -1
	for _, suggestion := range report.Suggestions {
		index := strings.Index(body, suggestion.Text)
		if index < 0 {
			t.Fatalf("suggestion %q not rendered", suggestion.Text)
		}
		if index < last {
			t.Fatalf("suggestions not rendered in report order")
		}
		last = index
	}
}

// fixture 报告快照的值被钉死：总分、6 维度得分、指标得分与建议由固定
// fixture 数据经报告引擎确定性产出，防止 fixture 漂移。
func TestReportsFixturePinsReportValues(t *testing.T) {
	fixture := buildReportsFixture()
	report := fixture.report

	if report.OverallScore != 74.7 {
		t.Fatalf("overall = %v, want 74.7", report.OverallScore)
	}
	wantDimensions := map[evaluation.Dimension]float64{
		evaluation.DimensionResponseSpeed:    86.0,
		evaluation.DimensionDisposalStandard: 80.0,
		evaluation.DimensionCoordination:     38.0,
		evaluation.DimensionAudienceSafety:   88.0,
		evaluation.DimensionRelicSafety:      90.0,
		evaluation.DimensionPublicOpinion:    75.0,
	}
	for dimension, want := range wantDimensions {
		entry, ok := report.DimensionScores[dimension]
		if !ok || entry.Score != want {
			t.Fatalf("dimension %s = %v (present=%v), want %v", dimension, entry.Score, ok, want)
		}
	}
	if len(report.DimensionScores) != 6 {
		t.Fatalf("dimension_scores = %d entries, want 6", len(report.DimensionScores))
	}
	wantIndicators := map[string]float64{
		reportsIndicatorIDs[0]:  94.0, // 预警响应速度: (自动 88 + 专家 100)/2
		reportsIndicatorIDs[1]:  88.0, // 指挥调度响应速度: 自动
		reportsIndicatorIDs[2]:  76.0, // 力量到场速度: 自动
		reportsIndicatorIDs[3]:  80.0, // 处置流程规范性: 自动
		reportsIndicatorIDs[4]:  80.0, // 信息报告规范性: 自动
		reportsIndicatorIDs[5]:  0.0,  // 部门协同效率: 自动（0/1 部门报告完成）
		reportsIndicatorIDs[6]:  76.0, // 信息共享效率: 自动
		reportsIndicatorIDs[7]:  88.0, // 观众疏散组织: 演示
		reportsIndicatorIDs[10]: 90.0, // 文物转移保护: 演示
		reportsIndicatorIDs[12]: 75.0, // 舆情监测预警: 演示
	}
	if len(report.IndicatorScores) != len(wantIndicators) {
		t.Fatalf("indicator_scores = %d entries, want %d", len(report.IndicatorScores), len(wantIndicators))
	}
	for id, want := range wantIndicators {
		entry, ok := report.IndicatorScores[id]
		if !ok || entry.Score != want {
			t.Fatalf("indicator %s = %v (present=%v), want %v", id, entry.Score, ok, want)
		}
	}
	wantSuggestions := []struct{ dimension, level, text string }{
		{"协同效率", "严重", "协同效率维度平均分 38.0 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。"},
		{"协同效率", "关注", "存在未完成联动处置的部门，建议跟进相关部门的处置进度并补全联动记录。"},
	}
	if len(report.Suggestions) != len(wantSuggestions) {
		t.Fatalf("suggestions = %d entries, want %d", len(report.Suggestions), len(wantSuggestions))
	}
	for i, want := range wantSuggestions {
		got := report.Suggestions[i]
		if string(got.Dimension) != want.dimension || got.Level != want.level || got.Text != want.text {
			t.Fatalf("suggestion[%d] = %+v, want %+v", i, got, want)
		}
	}
}

// fixture 的 run_id / sid / 指标 id 全部为 26 位 Crockford Base32
// ULID（页面所有 URL 与表单字段都来自这些 id）。
func TestReportsFixtureULIDs(t *testing.T) {
	ids := append([]string{
		reportsExampleRunID, reportsInProgressRunID,
		reportsExampleScenarioID, reportsInProgressScenarioID,
		reportsExpertScoreID,
	}, reportsIndicatorIDs...)
	for _, id := range ids {
		if !reportsPageULIDPattern.MatchString(id) {
			t.Errorf("fixture id %q is not a 26-character Crockford Base32 ULID", id)
		}
	}
}
