package web

import (
	"strings"
	"testing"
)

// ─── #44 演练执行与考核页 ──────────────────────────────────────

// drillsFixture builds the display payload of the drill execution and
// assessment page: two example drill runs (titles/scenario/status
// identical to the httpapi page fixture, the runs and the simulated
// event carrying the same fixed 26-character server-side ULIDs) plus the
// ordered steps and assessment points of the built-in scenarios
// (content identical to drills.SeedData), so the form actions can be
// pinned to the ids carried by the data (the page never constructs ids).
func drillsFixture() DrillsPageData {
	return DrillsPageData{
		Scenarios: []ScenarioOption{
			{ID: "06FZXT9YJAAM00YSRKTP290N0M", Name: "大客流聚集应急演练"},
			{ID: "06FZXT9YJ8PDGA7PSQNY8GS3G0", Name: "停电与基础设施故障应急演练"},
		},
		Runs: []RunView{
			{
				ID:           "06FZXT3DW8KHD056NHT2QYKY3M",
				Title:        "迎新春特展大客流聚集应急演练",
				ScenarioName: "大客流聚集应急演练",
				Status:       "进行中",
				Steps: []StepView{
					{ID: "06FZXT3DWB94KXV60J7YQ22P50", SortOrder: 1, Title: "预警触发", Description: "客流统计系统识别某区域人流密度超过阈值，系统自动预警"},
					{ID: "06FZXT3DWAFF5XAWWB1SWG32QC", SortOrder: 2, Title: "信息上报", Description: "值班人员通过系统向指挥中心报告"},
				},
				Points: []PointView{
					{ID: "06FZXT3DWA3Y0EB75HNKQ789P8", Title: "预警响应时间"},
					{ID: "06FZXT3DW9VWWRYV3X05V3JTE4", Title: "信息上报规范性"},
				},
				SimEvents: []SimEventView{
					{
						ID:        "06FZXT3DW9H30SS3Y92F8090PG",
						EventType: "客流密度超阈值",
						Status:    "已触发",
						Payload:   `{"area":"A区东侧展厅","density":8.5,"threshold":6.0}`,
					},
				},
			},
			{
				ID:           "06FZXT3DW9JBJ4NESWSSTFCBKG",
				Title:        "市电中断应急处置演练",
				ScenarioName: "停电与基础设施故障应急演练",
				Status:       "进行中",
				Steps: []StepView{
					{ID: "06FZXT3DW88KJCJG60K2AQNFSG", SortOrder: 1, Title: "故障发现", Description: "供配电系统监测到异常，系统自动报警"},
				},
				Points: []PointView{
					{ID: "06FZXT9YJ8PDGA7PSQNY8GS3G0", Title: "应急供电切换速度"},
				},
			},
		},
	}
}

// 演练执行与考核页渲染成功且包含关键内容：示例任务列表（标题/场景/状态
// 「进行中」）、场景流程步骤标题、考核要点标题、模拟事件演示区（触发按钮
// 与五种 event_type 枚举项、已触发事件的模拟数据 payload）、状态机操作
// 入口（开始/完成/终止）与 0–100 评分输入（与 drills.SeedData / API
// 契约一致）。
func TestRenderDrillsPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderDrills(&output, drillsFixture()); err != nil {
		t.Fatalf("RenderDrills: %v", err)
	}
	rendered := output.String()
	// 任务列表与详情：示例任务标题、场景名、状态「进行中」。
	for _, text := range []string{"迎新春特展大客流聚集应急演练", "市电中断应急处置演练", "大客流聚集应急演练", "停电与基础设施故障应急演练", "进行中"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 场景流程步骤与考核要点（SeedData 内容）。
	for _, text := range []string{"预警触发", "信息上报", "故障发现", "预警响应时间", "应急供电切换速度"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 模拟事件演示区：触发按钮与五种 event_type 枚举项、已触发事件展示。
	for _, text := range []string{"触发模拟事件", "客流密度超阈值", "供配电异常报警", "烟感探测器触发", "气象预警接收", "其他", "已触发"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 已触发事件的模拟数据 payload 被展示（引号经 html/template 转义为 &#34;）。
	for _, text := range []string{"A区东侧展厅", "&#34;density&#34;", "8.5", "&#34;threshold&#34;", "6.0"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not display the payload %q", text)
		}
	}
	// 状态机操作入口与 0–100 评分输入。
	for _, text := range []string{">开始<", ">完成<", ">终止<"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the state machine button %q", text)
		}
	}
	if !strings.Contains(rendered, `name="score"`) || !strings.Contains(rendered, `min="0"`) || !strings.Contains(rendered, `max="100"`) {
		t.Fatalf("rendered page does not contain the 0-100 score input")
	}
}

// 表单动作与 API 契约一一对应：任务创建 → POST /drills；状态机 →
// POST /drills/{id}/start|complete|terminate；步骤执行记录 →
// PUT /drills/{rid}/steps/{stepId}（status 已执行/跳过 + action_note）；
// 模拟事件触发/处置 → POST /drills/{rid}/sim-events、
// PUT /drills/{rid}/sim-events/{eid}（status=已处置）；考核评分 →
// PUT /drills/{rid}/assessments/{pointId}（score 0–100 + comment）。
// 所有 URL 中的 id 均为数据带出的 ULID（fixture 固定值），页面不构造 id。
func TestRenderDrillsFormActions(t *testing.T) {
	var output strings.Builder
	if err := RenderDrills(&output, drillsFixture()); err != nil {
		t.Fatalf("RenderDrills: %v", err)
	}
	rendered := output.String()
	runID := "06FZXT3DW8KHD056NHT2QYKY3M"
	stepID := "06FZXT3DWB94KXV60J7YQ22P50"
	pointID := "06FZXT3DWA3Y0EB75HNKQ789P8"
	eventID := "06FZXT3DW9H30SS3Y92F8090PG"

	// 任务创建 → POST /drills，字段 title + scenario_id（下拉选项值随数据带出）。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills"`) {
		t.Fatalf("task create form does not target POST /crate-api/prototype/v1/drills")
	}
	if !strings.Contains(rendered, `name="title"`) || !strings.Contains(rendered, `name="scenario_id"`) {
		t.Fatalf("task create form does not carry the title/scenario_id fields")
	}
	if !strings.Contains(rendered, `name="scenario_id" required`) {
		t.Fatalf("task create form scenario_id is not required")
	}
	// 状态机三入口 → POST /drills/{id}/start|complete|terminate。
	for _, transition := range []string{"start", "complete", "terminate"} {
		target := `/crate-api/prototype/v1/drills/` + runID + `/` + transition
		if !strings.Contains(rendered, `hx-post="`+target+`"`) {
			t.Fatalf("state machine form does not target POST %s", target)
		}
	}
	// 步骤执行记录 → PUT /drills/{rid}/steps/{stepId}，字段 status + action_note。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/steps/`+stepID+`"`) {
		t.Fatalf("step record form does not target PUT /drills/%s/steps/%s", runID, stepID)
	}
	if !strings.Contains(rendered, `name="status"`) || !strings.Contains(rendered, `name="action_note"`) {
		t.Fatalf("step record form does not carry the status/action_note fields")
	}
	if !strings.Contains(rendered, `value="已执行"`) || !strings.Contains(rendered, `value="跳过"`) {
		t.Fatalf("step record form does not offer the 已执行/跳过 status options")
	}
	// 模拟事件触发 → POST /drills/{rid}/sim-events，字段 event_type。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/sim-events"`) {
		t.Fatalf("sim event trigger form does not target POST /drills/%s/sim-events", runID)
	}
	if !strings.Contains(rendered, `name="event_type"`) {
		t.Fatalf("sim event trigger form does not carry the event_type field")
	}
	// 标记已处置 → PUT /drills/{rid}/sim-events/{eid}，body 携带 status=已处置。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/sim-events/`+eventID+`"`) {
		t.Fatalf("sim event handle form does not target PUT /drills/%s/sim-events/%s", runID, eventID)
	}
	if !strings.Contains(rendered, `name="status" value="已处置"`) {
		t.Fatalf("sim event handle form does not carry status=已处置")
	}
	// 考核评分 → PUT /drills/{rid}/assessments/{pointId}，字段 score（0–100）+ comment。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/assessments/`+pointID+`"`) {
		t.Fatalf("assessment form does not target PUT /drills/%s/assessments/%s", runID, pointID)
	}
	if !strings.Contains(rendered, `name="comment"`) {
		t.Fatalf("assessment form does not carry the comment field")
	}
	// 页面不构造 ID：表单只携带契约字段，没有任何 id 输入项。
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("forms must not carry an id input (the page never constructs ids)")
	}
}
