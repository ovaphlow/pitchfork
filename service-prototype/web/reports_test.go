package web

import (
	"regexp"
	"strings"
	"testing"
)

// ─── #63 综合评估与报告页 ──────────────────────────────────────

// The fixed 26-character Crockford Base32 ULIDs of the reports page
// fixture, identical to the httpapi page fixture (the page never
// constructs ids).
const (
	reportsTestRunID      = "06G02ZKK9CPE5E0YCE4EMZJH7M"
	reportsTestScenarioID = "06G02ZKK9EAK77X7D7RJFBAV3R"
	reportsTestScoreID    = "06G02ZKK9DYH9W79EHVYX1N1XM"
)

var reportsTestIndicatorIDs = []string{
	"06G02ZKK9CZ1DTC8SJQVRDQ9AR", // 预警响应速度
	"06G02ZKK9ECJ558BJBQGWDDH9G", // 指挥调度响应速度
	"06G02ZKK9ENS1KXXH5HRFZER5R", // 力量到场速度
	"06G02ZKK9EQJEPQR1Y5WH6FHWW", // 处置流程规范性
	"06G02ZKK9CQJQMCWJ4TH65PWH0", // 信息报告规范性
	"06G02ZKK9FAHN6GJCZZQHR0RM4", // 部门协同效率
	"06G02ZKK9C9W162RWTX0FKWDNR", // 信息共享效率
	"06G02ZKK9F241206H8TPBJRWQ0", // 观众疏散组织
	"06G02ZKK9D2G9ZRTCRQ9M1QCEG", // 观众秩序维护
	"06G02ZKK9D2N32Y2JJ7CQK8AF4", // 观众伤亡防控
	"06G02ZKK9EYDSDRRKWAAKPA3TG", // 文物转移保护
	"06G02ZKK9D2M4PAE0GJF2R2BYC", // 文物损失防控
	"06G02ZKK9FW1QD26TTPS73EYJC", // 舆情监测预警
	"06G02ZKK9CJBWP1Y7PW8AWTVJG", // 信息发布引导
	"06G02ZKK9E4X22KCA35KW150YR", // 舆情处置效果
}

// reportsFixtureIndicator is one evaluation indicator of the web test
// fixture: the fixed id, the business fields and the demo flag,
// mirroring evaluation.SeedData (dimension + sort_order order).
type reportsFixtureIndicator struct {
	id        string
	dimension string
	title     string
	sortOrder int
	demo      bool
}

func reportsFixtureIndicators() []reportsFixtureIndicator {
	ids := reportsTestIndicatorIDs
	return []reportsFixtureIndicator{
		{ids[0], "响应速度", "预警响应速度", 1, false},
		{ids[1], "响应速度", "指挥调度响应速度", 2, false},
		{ids[2], "响应速度", "力量到场速度", 3, false},
		{ids[3], "处置规范性", "处置流程规范性", 1, false},
		{ids[4], "处置规范性", "信息报告规范性", 2, false},
		{ids[5], "协同效率", "部门协同效率", 1, false},
		{ids[6], "协同效率", "信息共享效率", 2, false},
		{ids[7], "观众安全", "观众疏散组织", 1, true},
		{ids[8], "观众安全", "观众秩序维护", 2, true},
		{ids[9], "观众安全", "观众伤亡防控", 3, true},
		{ids[10], "文物安全", "文物转移保护", 1, true},
		{ids[11], "文物安全", "文物损失防控", 2, true},
		{ids[12], "舆情管控", "舆情监测预警", 1, true},
		{ids[13], "舆情管控", "信息发布引导", 2, true},
		{ids[14], "舆情管控", "舆情处置效果", 3, true},
	}
}

// reportsFixture builds the display payload of the comprehensive
// evaluation and report page, identical to the httpapi page fixture:
// the completed example run with its fixed ULIDs, the 15 built-in
// indicators (7 auto / 8 demo), the demo scores (观众疏散组织 88 /
// 文物转移保护 90 / 舆情监测预警 75, the rest unscored), the existing
// 专家评分 record of 预警响应速度 (the PUT-form instance) and the report
// snapshot values produced by the evaluation report service over the
// same data (总分 74.7, 6 维度得分, 15 指标明细, 2 建议).
func reportsFixture() ReportsPageData {
	indicators := reportsFixtureIndicators()
	scoreCollection := "/crate-api/prototype/v1/evaluation/runs/" + reportsTestRunID + "/scores"

	autoScores := make([]IndicatorScoreView, 0, 7)
	demoIndicators := make([]DemoIndicatorView, 0, 8)
	demoFormInputs := make([]DemoScoreInputView, 0, 8)
	demoScores := map[string]string{
		reportsTestIndicatorIDs[7]:  "88.0",
		reportsTestIndicatorIDs[10]: "90.0",
		reportsTestIndicatorIDs[12]: "75.0",
	}
	reportRows := make([]IndicatorRowView, 0, 15)
	reportScores := map[string]string{
		reportsTestIndicatorIDs[0]:  "94.0",
		reportsTestIndicatorIDs[1]:  "88.0",
		reportsTestIndicatorIDs[2]:  "76.0",
		reportsTestIndicatorIDs[3]:  "80.0",
		reportsTestIndicatorIDs[4]:  "80.0",
		reportsTestIndicatorIDs[5]:  "0.0",
		reportsTestIndicatorIDs[6]:  "76.0",
		reportsTestIndicatorIDs[7]:  "88.0",
		reportsTestIndicatorIDs[10]: "90.0",
		reportsTestIndicatorIDs[12]: "75.0",
	}
	for _, indicator := range indicators {
		if !indicator.demo {
			autoScores = append(autoScores, IndicatorScoreView{
				ID: indicator.id, Dimension: indicator.dimension, Title: indicator.title,
				SortOrder: indicator.sortOrder, Score: reportScores[indicator.id], HasScore: true,
			})
		} else {
			view := DemoIndicatorView{
				ID: indicator.id, Dimension: indicator.dimension, Title: indicator.title,
				SortOrder: indicator.sortOrder,
			}
			if value, ok := demoScores[indicator.id]; ok {
				view.HasScore = true
				view.Score = value
			}
			demoIndicators = append(demoIndicators, view)
			demoFormInputs = append(demoFormInputs, DemoScoreInputView{
				IndicatorID: indicator.id, Title: indicator.title, Value: demoScores[indicator.id],
			})
		}
		row := IndicatorRowView{
			ID: indicator.id, Dimension: indicator.dimension, Title: indicator.title,
			SortOrder: indicator.sortOrder, Demo: indicator.demo,
		}
		if value, ok := reportScores[indicator.id]; ok {
			row.HasScore = true
			row.Score = value
		}
		reportRows = append(reportRows, row)
	}

	var scoreForms []IndicatorScoreFormsView
	for _, indicator := range indicators {
		group := IndicatorScoreFormsView{IndicatorID: indicator.id, Title: indicator.title}
		for _, scoreType := range []string{"专家评分", "自评", "互评"} {
			form := ScoreFormView{
				Method: "POST", Action: scoreCollection, ScoreType: scoreType,
				IndicatorID: indicator.id, ShowTarget: scoreType != "专家评分",
			}
			if indicator.id == reportsTestIndicatorIDs[0] && scoreType == "专家评分" {
				// 已存在专家评分记录 → PUT 该记录（预填 rater/score/comment，
				// 不含 indicator_id 与 target）。
				form.Method = "PUT"
				form.Action = scoreCollection + "/" + reportsTestScoreID
				form.HasRecord = true
				form.Rater = "评审员"
				form.Score = "100"
				form.Comment = "响应及时"
			}
			group.Forms = append(group.Forms, form)
		}
		scoreForms = append(scoreForms, group)
	}

	return ReportsPageData{
		Runs: []RunOptionView{
			{ID: reportsTestRunID, Title: "迎新春特展大客流聚集综合评估演练"},
			{ID: "06G02ZKK9CJWYDC3J9VJBP5H4M", Title: "市电中断应急处置综合评估演练"},
		},
		SelectedRunID: reportsTestRunID,
		Run: &SelectedRunView{
			ID: reportsTestRunID, Title: "迎新春特展大客流聚集综合评估演练", Status: "已完成",
		},
		AutoScores:     autoScores,
		DemoIndicators: demoIndicators,
		DemoScoreForm: &DemoScoreFormView{
			RunID:      reportsTestRunID,
			ScenarioID: reportsTestScenarioID,
			Title:      "迎新春特展大客流聚集综合评估演练",
			Inputs:     demoFormInputs,
		},
		ScoreForms: scoreForms,
		GenerateAction: "/crate-api/prototype/v1/evaluation/runs/" +
			reportsTestRunID + "/reports/generate",
		Report: &ReportAreaView{
			OverallScore: "74.7",
			DimensionScores: []DimensionScoreView{
				{Dimension: "响应速度", Score: "86.0"},
				{Dimension: "处置规范性", Score: "80.0"},
				{Dimension: "协同效率", Score: "38.0"},
				{Dimension: "观众安全", Score: "88.0"},
				{Dimension: "文物安全", Score: "90.0"},
				{Dimension: "舆情管控", Score: "75.0"},
			},
			IndicatorRows: reportRows,
			Suggestions: []SuggestionView{
				{Dimension: "协同效率", Level: "严重", Text: "协同效率维度平均分 38.0 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。"},
				{Dimension: "协同效率", Level: "关注", Text: "存在未完成联动处置的部门，建议跟进相关部门的处置进度并补全联动记录。"},
			},
		},
	}
}

// 渲染成功且包含关键内容：演练选择器、运行信息、7 项自动得分、8 项演示
// 指标（有值显示得分 / 无值「未评分(演示)」）、报告区总分、6 维度得分、
// 15 项指标明细（自动/演示标志）与建议列表。
func TestRenderReportsPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderReports(&output, reportsFixture()); err != nil {
		t.Fatalf("RenderReports: %v", err)
	}
	rendered := output.String()

	if !strings.Contains(rendered, `method="get" action="/demo/evaluation/reports"`) {
		t.Fatalf("selector is not a GET form")
	}
	for _, text := range []string{"迎新春特展大客流聚集综合评估演练", "市电中断应急处置综合评估演练", `<span class="run-status">已完成</span>`} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	for _, item := range []string{
		`<li class="auto-score"><span class="indicator-title">预警响应速度</span> <span class="score">94.0</span></li>`,
		`<li class="auto-score"><span class="indicator-title">信息共享效率</span> <span class="score">76.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">观众疏散组织</span> <span class="score">88.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">文物转移保护</span> <span class="score">90.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">舆情监测预警</span> <span class="score">75.0</span></li>`,
		`<li class="demo-indicator"><span class="indicator-title">观众秩序维护</span> <span class="unscored">未评分(演示)</span></li>`,
		`<span class="overall-score">74.7</span>`,
		`<li><span class="dimension">响应速度</span> <span class="score">86.0</span></li>`,
		`<li><span class="dimension">协同效率</span> <span class="score">38.0</span></li>`,
	} {
		if !strings.Contains(rendered, item) {
			t.Fatalf("rendered page does not contain %q", item)
		}
	}
	if got := strings.Count(rendered, "未评分(演示)"); got != 10 {
		t.Fatalf("未评分(演示) count = %d, want 10", got)
	}
	if got := strings.Count(rendered, `<td>自动</td>`); got != 7 {
		t.Fatalf("auto flag count = %d, want 7", got)
	}
	if got := strings.Count(rendered, `<td>演示</td>`); got != 8 {
		t.Fatalf("demo flag count = %d, want 8", got)
	}
	for _, text := range []string{
		"协同效率维度平均分 38.0 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。",
		"存在未完成联动处置的部门，建议跟进相关部门的处置进度并补全联动记录。",
	} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the suggestion %q", text)
		}
	}
}

// 表单动作与 API 契约一一对应（4.1–4.5）：评分表单 POST/PUT 实例规则、
// 字段清单（indicator_id 仅 POST、target 仅自评/互评、score_type 三值）、
// 演示得分表单（PUT /drills/{id}，hidden scenario_id + title，输入名嵌
// 入指标 id）、生成/刷新报告表单与固定反馈元素的 hx-target 接线。
func TestRenderReportsFormActions(t *testing.T) {
	var output strings.Builder
	if err := RenderReports(&output, reportsFixture()); err != nil {
		t.Fatalf("RenderReports: %v", err)
	}
	rendered := output.String()
	ulidPattern := regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)
	alertID := reportsTestIndicatorIDs[0]

	// 反馈元素：固定 id + role="status"；每个 htmx 表单都指向它
	// （45 POST + 2 PUT 表单 = 47）。
	if !strings.Contains(rendered, `<p id="report-feedback" role="status"></p>`) {
		t.Fatalf("rendered page does not carry the #report-feedback status element")
	}
	if got := strings.Count(rendered, `hx-target="#report-feedback"`); got != 47 {
		t.Fatalf("hx-target count = %d, want 47", got)
	}
	if got := strings.Count(rendered, `hx-swap="innerHTML"`); got != 47 {
		t.Fatalf("hx-swap count = %d, want 47", got)
	}
	if got := strings.Count(rendered, `hx-post=`); got != 45 {
		t.Fatalf("hx-post count = %d, want 45", got)
	}
	if got := strings.Count(rendered, `hx-put=`); got != 2 {
		t.Fatalf("hx-put count = %d, want 2 (demo form + expert PUT form)", got)
	}

	// 评分表单字段清单：score_type 45（三值全覆盖）、indicator_id 44
	// （仅 POST）、target 30（仅自评/互评）、无 created_by / id。
	if got := strings.Count(rendered, `name="score_type"`); got != 45 {
		t.Fatalf("score_type field count = %d, want 45", got)
	}
	if got := strings.Count(rendered, `name="indicator_id"`); got != 44 {
		t.Fatalf("indicator_id field count = %d, want 44 (POST forms only)", got)
	}
	if got := strings.Count(rendered, `name="target"`); got != 30 {
		t.Fatalf("target field count = %d, want 30 (自评/互评 only)", got)
	}
	if strings.Contains(rendered, `name="created_by"`) || strings.Contains(rendered, `name="id"`) {
		t.Fatalf("forms must not carry created_by/id inputs (the page never constructs ids)")
	}

	// 预警响应速度的评分表单组：专家评分已有记录 → PUT /scores/{sid}
	// （预填、无 indicator_id/target）；自评/互评无记录 → POST 集合
	// （hidden indicator_id + required target）。
	group := section(rendered, `<legend>预警响应速度</legend>`, "</fieldset>")
	putAction := `hx-put="/crate-api/prototype/v1/evaluation/runs/` + reportsTestRunID + `/scores/` + reportsTestScoreID + `"`
	if !strings.Contains(group, putAction) {
		t.Fatalf("expert form does not target PUT .../scores/%s", reportsTestScoreID)
	}
	putForm := section(rendered, `hx-put="/crate-api/prototype/v1/evaluation/runs/`+reportsTestRunID+`/scores/`+reportsTestScoreID+`"`, "</form>")
	if strings.Contains(putForm, `name="indicator_id"`) || strings.Contains(putForm, `name="target"`) {
		t.Fatalf("PUT expert form must not carry indicator_id/target")
	}
	if !strings.Contains(putForm, `name="score_type" value="专家评分"`) ||
		!strings.Contains(putForm, `name="rater" value="评审员"`) ||
		!strings.Contains(putForm, `name="score" value="100"`) ||
		!strings.Contains(putForm, `name="comment" value="响应及时"`) {
		t.Fatalf("PUT expert form is not prefilled with the existing record values")
	}
	if got := strings.Count(group, `hx-post="/crate-api/prototype/v1/evaluation/runs/`+reportsTestRunID+`/scores"`); got != 2 {
		t.Fatalf("POST form count in the group = %d, want 2 (自评/互评)", got)
	}
	if got := strings.Count(group, `name="indicator_id" value="`+alertID+`"`); got != 2 {
		t.Fatalf("hidden indicator_id count = %d, want 2 (POST forms only)", got)
	}
	if got := strings.Count(group, `name="target"`); got != 2 {
		t.Fatalf("target input count = %d, want 2 (自评/互评)", got)
	}
	for _, value := range []string{`value="自评"`, `value="互评"`} {
		if !strings.Contains(group, `name="score_type" `+value) {
			t.Fatalf("group does not carry the %s score_type", value)
		}
	}
	if !ulidPattern.MatchString(reportsTestScoreID) {
		t.Fatalf("PUT sid %q is not a 26-character Crockford Base32 ULID", reportsTestScoreID)
	}

	// 指挥调度响应速度（无任何评分记录）：三表单全部 POST，专家表单无
	// target 输入。
	group = section(rendered, `<legend>指挥调度响应速度</legend>`, "</fieldset>")
	if got := strings.Count(group, `hx-post="/crate-api/prototype/v1/evaluation/runs/`+reportsTestRunID+`/scores"`); got != 3 {
		t.Fatalf("POST form count = %d, want 3 (no existing records)", got)
	}
	if got := strings.Count(group, `name="indicator_id"`); got != 3 {
		t.Fatalf("indicator_id count = %d, want 3", got)
	}
	if got := strings.Count(group, `name="target"`); got != 2 {
		t.Fatalf("target count = %d, want 2 (专家 form omits target)", got)
	}

	// 演示得分表单：PUT /drills/{id}，hidden scenario_id + title（整体
	// 替换下二者必填），8 个输入名嵌入指标 id（与报告引擎读取口径一致），
	// 有值的预填、无值的留空。
	demoForm := section(rendered, `<form hx-put="/crate-api/prototype/v1/drills/`+reportsTestRunID+`"`, "</form>")
	if !strings.Contains(demoForm, `name="scenario_id" value="`+reportsTestScenarioID+`"`) ||
		!strings.Contains(demoForm, `name="title" value="迎新春特展大客流聚集综合评估演练"`) {
		t.Fatalf("demo score form does not carry the required hidden scenario_id/title")
	}
	if got := strings.Count(demoForm, `name="metadata.demo_scores.`); got != 8 {
		t.Fatalf("demo score input count = %d, want 8", got)
	}
	if !strings.Contains(demoForm, `name="metadata.demo_scores.`+reportsTestIndicatorIDs[7]+`" value="88.0"`) ||
		!strings.Contains(demoForm, `name="metadata.demo_scores.`+reportsTestIndicatorIDs[10]+`" value="90.0"`) ||
		!strings.Contains(demoForm, `name="metadata.demo_scores.`+reportsTestIndicatorIDs[12]+`" value="75.0"`) {
		t.Fatalf("demo score form is not prefilled with the fixture demo_scores values")
	}
	if got := strings.Count(demoForm, `value=""`); got != 5 {
		t.Fatalf("empty demo score inputs = %d, want 5 (indicators without demo_scores)", got)
	}
	if !ulidPattern.MatchString(reportsTestIndicatorIDs[7]) {
		t.Fatalf("demo_scores key %q is not a 26-character Crockford Base32 ULID", reportsTestIndicatorIDs[7])
	}

	// 生成/刷新报告表单 → POST reports/generate 路由。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/evaluation/runs/`+reportsTestRunID+`/reports/generate"`) {
		t.Fatalf("generate form does not target POST .../reports/generate")
	}
}

// section returns the substring of rendered between the first occurrence
// of start and the following occurrence of end (exclusive), failing the
// test when either marker is missing.
func section(rendered, start, end string) string {
	from := strings.Index(rendered, start)
	if from < 0 {
		panic("marker " + start + " not found")
	}
	to := strings.Index(rendered[from:], end)
	if to < 0 {
		panic("end marker " + end + " not found")
	}
	return rendered[from : from+to]
}
