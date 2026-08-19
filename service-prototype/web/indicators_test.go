package web

import (
	"strings"
	"testing"
)

// ─── #63 评估指标配置页 ────────────────────────────────────────

// indicatorFixture builds the display payload of the evaluation
// indicator configuration page with the fifteen built-in indicators of
// the six dimensions (titles/dimensions/descriptions identical to
// evaluation.SeedData and the seed migration 000023, demo distribution
// 演示 8 / 非演示 7, per-dimension sort_order 1..N in SeedData order)
// and known 26-character Crockford Base32 ULIDs, so the form actions
// can be pinned to the ids carried by the data (the page never
// constructs ids).
func indicatorFixture() IndicatorsPageData {
	options := []string{"响应速度", "处置规范性", "协同效率", "观众安全", "文物安全", "舆情管控"}
	return IndicatorsPageData{
		DimensionOptions: options,
		Groups: []DimensionGroupView{
			{
				Name: "响应速度",
				Indicators: []IndicatorView{
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4P1", Dimension: "响应速度", Title: "预警响应速度", Weight: 1, Demo: false, SortOrder: 1, Description: "从预警触发到应急响应的用时"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4P2", Dimension: "响应速度", Title: "指挥调度响应速度", Weight: 1, Demo: false, SortOrder: 2, Description: "从指令下达到力量调集的用时"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4P3", Dimension: "响应速度", Title: "力量到场速度", Weight: 1, Demo: false, SortOrder: 3, Description: "应急处置力量抵达现场的用时"},
				},
			},
			{
				Name: "处置规范性",
				Indicators: []IndicatorView{
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4Q1", Dimension: "处置规范性", Title: "处置流程规范性", Weight: 1, Demo: false, SortOrder: 1, Description: "处置步骤与应急预案流程的符合程度"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4Q2", Dimension: "处置规范性", Title: "信息报告规范性", Weight: 1, Demo: false, SortOrder: 2, Description: "信息上报及时准确、要素完整"},
				},
			},
			{
				Name: "协同效率",
				Indicators: []IndicatorView{
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4R1", Dimension: "协同效率", Title: "部门协同效率", Weight: 1, Demo: false, SortOrder: 1, Description: "跨部门联动配合的顺畅程度"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4R2", Dimension: "协同效率", Title: "信息共享效率", Weight: 1, Demo: false, SortOrder: 2, Description: "现场信息传递与共享的时效"},
				},
			},
			{
				Name: "观众安全",
				Indicators: []IndicatorView{
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4S1", Dimension: "观众安全", Title: "观众疏散组织", Weight: 1, Demo: true, SortOrder: 1, Description: "疏散组织有序、路线合理"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4S2", Dimension: "观众安全", Title: "观众秩序维护", Weight: 1, Demo: true, SortOrder: 2, Description: "现场秩序稳定、观众情绪安抚到位"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4S3", Dimension: "观众安全", Title: "观众伤亡防控", Weight: 1, Demo: true, SortOrder: 3, Description: "无观众伤亡或伤情得到及时处置"},
				},
			},
			{
				Name: "文物安全",
				Indicators: []IndicatorView{
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4T1", Dimension: "文物安全", Title: "文物转移保护", Weight: 1, Demo: true, SortOrder: 1, Description: "珍贵文物转移保护及时到位"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4T2", Dimension: "文物安全", Title: "文物损失防控", Weight: 1, Demo: true, SortOrder: 2, Description: "无文物损毁或损失可控"},
				},
			},
			{
				Name: "舆情管控",
				Indicators: []IndicatorView{
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4W1", Dimension: "舆情管控", Title: "舆情监测预警", Weight: 1, Demo: true, SortOrder: 1, Description: "舆情信息监测与预警及时"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4W2", Dimension: "舆情管控", Title: "信息发布引导", Weight: 1, Demo: true, SortOrder: 2, Description: "官方信息发布及时、口径统一"},
					{ID: "01J1R8Z4K2M6Q9T3V5X7Y0B2C4W3", Dimension: "舆情管控", Title: "舆情处置效果", Weight: 1, Demo: true, SortOrder: 3, Description: "舆情发酵得到有效控制"},
				},
			},
		},
	}
}

// 指标配置页渲染成功且包含关键内容：15 项种子指标标题、6 维度分组标题
// 与每项的权重/描述都在（与 evaluation.SeedData / 迁移种子一致）；演示
// 标志分布精确（演示 8 项带「演示」badge、非演示 7 项不带，fixture 8/7
// 分布与 SeedData 一致）。
func TestRenderIndicatorsPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderIndicators(&output, indicatorFixture()); err != nil {
		t.Fatalf("RenderIndicators: %v", err)
	}
	rendered := output.String()
	for _, title := range []string{
		"预警响应速度", "指挥调度响应速度", "力量到场速度",
		"处置流程规范性", "信息报告规范性",
		"部门协同效率", "信息共享效率",
		"观众疏散组织", "观众秩序维护", "观众伤亡防控",
		"文物转移保护", "文物损失防控",
		"舆情监测预警", "信息发布引导", "舆情处置效果",
	} {
		if !strings.Contains(rendered, title) {
			t.Fatalf("rendered page does not contain built-in indicator %q", title)
		}
	}
	for _, dimension := range []string{"响应速度", "处置规范性", "协同效率", "观众安全", "文物安全", "舆情管控"} {
		if !strings.Contains(rendered, "<h3>"+dimension+"</h3>") {
			t.Fatalf("rendered page does not contain the dimension group %q", dimension)
		}
	}
	// 权重与描述随每一项展示（抽样两项断言列内容真实渲染）。
	if !strings.Contains(rendered, "从预警触发到应急响应的用时") {
		t.Fatalf("rendered page does not show a built-in description")
	}
	if !strings.Contains(rendered, "舆情发酵得到有效控制") {
		t.Fatalf("rendered page does not show the last built-in description")
	}
	// 演示标志分布：恰好 8 个「演示」badge（fixture 演示 8 / 非演示 7）。
	if got := strings.Count(rendered, `<span class="demo-badge">演示</span>`); got != 8 {
		t.Fatalf("demo badge count = %d, want 8 (演示 8 / 非演示 7)", got)
	}
}

// 表单动作与 4.1 契约端点一一对应：新建 → POST /evaluation/indicators、
// 编辑 → PUT /evaluation/indicators/{id}、删除 → DELETE /evaluation/indicators/{id}，
// 编辑/删除 URL 中的 id 由数据带出（fixture 的 26 位 ULID），页面不构造 id；
// 编辑表单回带全部契约字段（PUT 全量替换语义：weight/sort_order/description
// 缺省值由表单显式回带、demo 按当前值勾选、维度选中当前项）；created_by 与
// created_at/updated_at 不在页面暴露。
func TestRenderIndicatorsFormActions(t *testing.T) {
	var output strings.Builder
	if err := RenderIndicators(&output, indicatorFixture()); err != nil {
		t.Fatalf("RenderIndicators: %v", err)
	}
	rendered := output.String()

	// 新建表单 → POST 集合端点。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/evaluation/indicators"`) {
		t.Fatalf("create form does not target POST /crate-api/prototype/v1/evaluation/indicators")
	}
	// 编辑与删除表单 → PUT/DELETE /evaluation/indicators/{id}，id 为 fixture
	// 数据带出的 26 位 ULID（每项各一个编辑、一个删除表单）。
	editIDs := []string{
		"01J1R8Z4K2M6Q9T3V5X7Y0B2C4P1", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4P2", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4P3",
		"01J1R8Z4K2M6Q9T3V5X7Y0B2C4Q1", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4Q2",
		"01J1R8Z4K2M6Q9T3V5X7Y0B2C4R1", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4R2",
		"01J1R8Z4K2M6Q9T3V5X7Y0B2C4S1", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4S2", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4S3",
		"01J1R8Z4K2M6Q9T3V5X7Y0B2C4T1", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4T2",
		"01J1R8Z4K2M6Q9T3V5X7Y0B2C4W1", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4W2", "01J1R8Z4K2M6Q9T3V5X7Y0B2C4W3",
	}
	for _, id := range editIDs {
		if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/evaluation/indicators/`+id+`"`) {
			t.Fatalf("edit form does not target PUT /evaluation/indicators/%s", id)
		}
		if !strings.Contains(rendered, `hx-delete="/crate-api/prototype/v1/evaluation/indicators/`+id+`"`) {
			t.Fatalf("delete form does not target DELETE /evaluation/indicators/%s", id)
		}
	}
	// 编辑表单回带全部字段：title/weight/sort_order/description 原值回带，
	// demo 仅演示项勾选，维度 select 选中当前项。
	if !strings.Contains(rendered, `name="title" value="预警响应速度"`) {
		t.Fatalf("edit form does not carry back the original title")
	}
	if !strings.Contains(rendered, `name="weight" value="1"`) {
		t.Fatalf("edit form does not carry back the weight (PUT full replacement)")
	}
	if !strings.Contains(rendered, `name="sort_order" value="3"`) {
		t.Fatalf("edit form does not carry back the sort_order (PUT full replacement)")
	}
	if !strings.Contains(rendered, `name="description" value="舆情发酵得到有效控制"`) {
		t.Fatalf("edit form does not carry back the original description")
	}
	if !strings.Contains(rendered, `name="demo" value="true" checked`) {
		t.Fatalf("edit form of a demo indicator does not check the demo flag")
	}
	if !strings.Contains(rendered, `name="demo" value="true">`) {
		t.Fatalf("edit form of a non-demo indicator must leave the demo checkbox unchecked")
	}
	if !strings.Contains(rendered, `value="观众安全" selected`) {
		t.Fatalf("edit form does not preselect the current dimension")
	}
	// 错误展示路径：反馈容器存在（id/role 钉死），全部 31 个表单
	// （1 新建 + 15 编辑 + 15 删除）hx-target 指向该容器且 hx-swap=innerHTML。
	if !strings.Contains(rendered, `<p id="indicator-feedback" role="status">`) {
		t.Fatalf("page does not contain the pinned feedback container")
	}
	if got := strings.Count(rendered, `hx-target="#indicator-feedback"`); got != 31 {
		t.Fatalf("hx-target count = %d, want 31 (1 create + 15 edit + 15 delete)", got)
	}
	if got := strings.Count(rendered, `hx-swap="innerHTML"`); got != 31 {
		t.Fatalf("hx-swap count = %d, want 31", got)
	}
	// 页面不构造 ID、不暴露服务端维护字段。
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("forms must not carry an id input (the page never constructs ids)")
	}
	for _, forbidden := range []string{`name="created_by"`, `name="created_at"`, `name="updated_at"`, "created_at", "updated_at"} {
		if strings.Contains(rendered, forbidden) {
			t.Fatalf("page must not expose server-maintained field %q", forbidden)
		}
	}
}

// 维度选项与分组顺序钉死：六个维度按固定顺序（响应速度/处置规范性/协同
// 效率/观众安全/文物安全/舆情管控）出现在筛选与表单的 option 中，与 4.1
// 契约枚举及列表排序口径一致。
func TestRenderIndicatorsDimensionOptionsFixedOrder(t *testing.T) {
	var output strings.Builder
	if err := RenderIndicators(&output, indicatorFixture()); err != nil {
		t.Fatalf("RenderIndicators: %v", err)
	}
	rendered := output.String()
	order := []string{"响应速度", "处置规范性", "协同效率", "观众安全", "文物安全", "舆情管控"}
	previous := -1
	for _, dimension := range order {
		index := strings.Index(rendered, `<option value="`+dimension+`"`)
		if index < 0 {
			t.Fatalf("rendered page does not contain the dimension option %q", dimension)
		}
		if index < previous {
			t.Fatalf("dimension options are not in the fixed order (%q before a previous one)", dimension)
		}
		previous = index
	}
}
