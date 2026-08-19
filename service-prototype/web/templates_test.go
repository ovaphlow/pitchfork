package web

import (
	"strings"
	"testing"
)

// 模板集合解析成功（包初始化时即解析，这里显式验证可执行）。
func TestTemplatesParseAndRenderDemo(t *testing.T) {
	var output strings.Builder
	if err := RenderDemo(&output, "你好，世界"); err != nil {
		t.Fatalf("RenderDemo: %v", err)
	}
	rendered := output.String()
	if !strings.Contains(rendered, "Prototype Demo") {
		t.Fatalf("rendered page does not contain the demo heading")
	}
	if !strings.Contains(rendered, "你好，世界") {
		t.Fatalf("rendered page does not contain the greeting")
	}
	if !strings.Contains(rendered, `hx-get="/crate-api/prototype/v1/demo-fragment"`) {
		t.Fatalf("rendered page does not reference the htmx fragment resource")
	}
}

// 含 HTML 特殊字符的输入被正确转义（无注入）。
func TestTemplateEscapesHTMLInput(t *testing.T) {
	var output strings.Builder
	payload := `<script>alert("xss")</script>`
	if err := RenderDemo(&output, payload); err != nil {
		t.Fatalf("RenderDemo: %v", err)
	}
	rendered := output.String()
	if strings.Contains(rendered, "<script>alert") {
		t.Fatalf("rendered output contains unescaped HTML: %s", rendered)
	}
	if !strings.Contains(rendered, "&lt;script&gt;alert") {
		t.Fatalf("rendered output does not contain the escaped input: %s", rendered)
	}
}

// 片段渲染同样转义用户可控输入。
func TestTemplateEscapesFragmentMessage(t *testing.T) {
	var output strings.Builder
	if err := RenderDemoFragment(&output, `<img src=x onerror=alert(1)>`); err != nil {
		t.Fatalf("RenderDemoFragment: %v", err)
	}
	rendered := output.String()
	if strings.Contains(rendered, "<img") {
		t.Fatalf("fragment contains unescaped HTML: %s", rendered)
	}
	if !strings.Contains(rendered, "&lt;img") {
		t.Fatalf("fragment does not contain the escaped input: %s", rendered)
	}
}

// ─── #44 场景模板管理页 ───────────────────────────────────────

// scenarioFixture builds the display payload of the drill scenario
// management page with the four built-in scenarios (names/categories/
// status identical to drills.SeedData and the seed migration 000016)
// and known 26-character server-side ULIDs, so the form actions can be
// pinned to the ids carried by the data (the page never constructs ids).
func scenarioFixture() ScenariosPageData {
	return ScenariosPageData{Scenarios: []ScenarioView{
		{
			ID:         "01J1R8Z4K2M6Q9T3V5X7Y0B2C4D6",
			Name:       "大客流聚集应急演练",
			Category:   "大客流聚集",
			Background: "新举办展览吸引大量观众，瞬时客流集中、入场通道拥堵。",
			Status:     "启用",
			Steps: []StepView{
				{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4E7", SortOrder: 1, Title: "预警触发", Description: "客流统计系统识别某区域人流密度超过阈值，系统自动预警"},
				{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4F8", SortOrder: 2, Title: "信息上报", Description: "值班人员通过系统向指挥中心报告"},
			},
			Points: []PointView{
				{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4G9", Title: "预警响应时间"},
				{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4H1", Title: "信息上报规范性"},
			},
		},
		{
			ID:         "01J1R8Z4K2M6Q9T3V5X7Y0B2C4J2",
			Name:       "停电与基础设施故障应急演练",
			Category:   "停电与基础设施",
			Background: "市电中断，安消防控制室、电梯、展厅、库房等关键区域面临停电风险。",
			Status:     "启用",
		},
		{
			ID:         "01J1R8Z4K2M6Q9T3V5X7Y0B2C4K3",
			Name:       "火灾应急处置演练",
			Category:   "火灾",
			Background: "展厅电气线路故障引发火情。",
			Status:     "启用",
		},
		{
			ID:         "01J1R8Z4K2M6Q9T3V5X7Y0B2C4M5",
			Name:       "气象灾害应急演练",
			Category:   "气象灾害",
			Background: "暑期高温、雷电、暴雨、台风等极端天气来袭。",
			Status:     "启用",
		},
	}}
}

// 场景模板页渲染成功且包含关键内容：四大内置场景名称/分类、状态列
// 「启用」、步骤与考核要点标题（与 drills.SeedData / 迁移种子一致）。
func TestRenderScenariosPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderScenarios(&output, scenarioFixture()); err != nil {
		t.Fatalf("RenderScenarios: %v", err)
	}
	rendered := output.String()
	for _, name := range []string{"大客流聚集应急演练", "停电与基础设施故障应急演练", "火灾应急处置演练", "气象灾害应急演练"} {
		if !strings.Contains(rendered, name) {
			t.Fatalf("rendered page does not contain built-in scenario %q", name)
		}
	}
	for _, category := range []string{"大客流聚集", "停电与基础设施", "火灾", "气象灾害"} {
		if !strings.Contains(rendered, category) {
			t.Fatalf("rendered page does not contain category %q", category)
		}
	}
	if !strings.Contains(rendered, "启用") {
		t.Fatalf("rendered page does not contain the enabled status column")
	}
	if !strings.Contains(rendered, "预警触发") {
		t.Fatalf("rendered page does not show a built-in step title")
	}
	if !strings.Contains(rendered, "预警响应时间") {
		t.Fatalf("rendered page does not show a built-in assessment point title")
	}
}

// 表单动作与 API 契约一一对应：场景创建/编辑/停用、步骤与考核要点
// 创建/编辑分别指向对应端点，编辑/停用 URL 中的 id 由数据带出（fixture
// 的 ULID），页面不构造 id。
func TestRenderScenariosFormActions(t *testing.T) {
	var output strings.Builder
	if err := RenderScenarios(&output, scenarioFixture()); err != nil {
		t.Fatalf("RenderScenarios: %v", err)
	}
	rendered := output.String()
	scenarioID := "01J1R8Z4K2M6Q9T3V5X7Y0B2C4D6"
	stepID := "01J1R8Z4K2M6Q9T3V5X7Y0B2C4E7"
	pointID := "01J1R8Z4K2M6Q9T3V5X7Y0B2C4G9"

	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/scenarios"`) {
		t.Fatalf("scenario create form does not target POST /crate-api/prototype/v1/scenarios")
	}
	// 编辑与停用都走 PUT /scenarios/{id}，id 为数据带出的服务端 ID。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/scenarios/`+scenarioID+`"`) {
		t.Fatalf("scenario edit/deactivate form does not target PUT /scenarios/%s", scenarioID)
	}
	// 停用表单回带全部字段（后端 PUT 为全量替换语义）且 status=停用。
	if !strings.Contains(rendered, `name="background" value="新举办展览吸引大量观众，瞬时客流集中、入场通道拥堵。"`) {
		t.Fatalf("deactivate form does not carry back the original background")
	}
	if !strings.Contains(rendered, `name="status" value="停用"`) {
		t.Fatalf("deactivate form does not set status to 停用")
	}
	// 步骤创建 → POST /scenarios/{sid}/steps；步骤编辑 → PUT /steps/{id}。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/scenarios/`+scenarioID+`/steps"`) {
		t.Fatalf("step create form does not target POST /scenarios/%s/steps", scenarioID)
	}
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/steps/`+stepID+`"`) {
		t.Fatalf("step edit form does not target PUT /steps/%s", stepID)
	}
	// 考核要点创建 → POST /scenarios/{sid}/assessment-points；编辑 → PUT /assessment-points/{id}。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/scenarios/`+scenarioID+`/assessment-points"`) {
		t.Fatalf("assessment point create form does not target POST /scenarios/%s/assessment-points", scenarioID)
	}
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/assessment-points/`+pointID+`"`) {
		t.Fatalf("assessment point edit form does not target PUT /assessment-points/%s", pointID)
	}
	// 页面不构造 ID：表单只携带契约字段，没有任何 id 输入项。
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("forms must not carry an id input (the page never constructs ids)")
	}
}
