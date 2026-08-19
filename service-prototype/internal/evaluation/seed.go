package evaluation

import (
	"context"
	"fmt"
)

// SeedIndicator describes one built-in evaluation indicator of the seed
// data: the dimension, the title, the demo flag and the description.
// The weight is 1 (the domain default) for every built-in row; the
// per-dimension sort_order (1..N) is assigned by the seed function in
// SeedData order.
type SeedIndicator struct {
	Dimension   Dimension
	Title       string
	Demo        bool
	Description string
}

// SeedData holds the fifteen built-in evaluation indicators of the
// museum safety comprehensive evaluation: 6 dimensions × 15 indicators
// (响应速度 3 / 处置规范性 2 / 协同效率 2 / 观众安全 3 / 文物安全 2 / 舆情
// 管控 3). The demo flag separates the seven computable indicators
// (demo=false: the three 响应速度, the two 处置规范性 and the two 协同效率
// rows) from the eight presentation indicators (demo=true: the three
// 观众安全, the two 文物安全 and the three 舆情管控 rows). The content
// matches the product specification word for word; it is the single
// source of truth shared by the seed function and the anti-drift test
// of the seed migration (000023).
var SeedData = []SeedIndicator{
	// 响应速度 (3, computable)
	{Dimension: DimensionResponseSpeed, Title: "预警响应速度", Demo: false, Description: "从预警触发到应急响应的用时"},
	{Dimension: DimensionResponseSpeed, Title: "指挥调度响应速度", Demo: false, Description: "从指令下达到力量调集的用时"},
	{Dimension: DimensionResponseSpeed, Title: "力量到场速度", Demo: false, Description: "应急处置力量抵达现场的用时"},
	// 处置规范性 (2, computable)
	{Dimension: DimensionDisposalStandard, Title: "处置流程规范性", Demo: false, Description: "处置步骤与应急预案流程的符合程度"},
	{Dimension: DimensionDisposalStandard, Title: "信息报告规范性", Demo: false, Description: "信息上报及时准确、要素完整"},
	// 协同效率 (2, computable)
	{Dimension: DimensionCoordination, Title: "部门协同效率", Demo: false, Description: "跨部门联动配合的顺畅程度"},
	{Dimension: DimensionCoordination, Title: "信息共享效率", Demo: false, Description: "现场信息传递与共享的时效"},
	// 观众安全 (3, demo)
	{Dimension: DimensionAudienceSafety, Title: "观众疏散组织", Demo: true, Description: "疏散组织有序、路线合理"},
	{Dimension: DimensionAudienceSafety, Title: "观众秩序维护", Demo: true, Description: "现场秩序稳定、观众情绪安抚到位"},
	{Dimension: DimensionAudienceSafety, Title: "观众伤亡防控", Demo: true, Description: "无观众伤亡或伤情得到及时处置"},
	// 文物安全 (2, demo)
	{Dimension: DimensionRelicSafety, Title: "文物转移保护", Demo: true, Description: "珍贵文物转移保护及时到位"},
	{Dimension: DimensionRelicSafety, Title: "文物损失防控", Demo: true, Description: "无文物损毁或损失可控"},
	// 舆情管控 (3, demo)
	{Dimension: DimensionPublicOpinion, Title: "舆情监测预警", Demo: true, Description: "舆情信息监测与预警及时"},
	{Dimension: DimensionPublicOpinion, Title: "信息发布引导", Demo: true, Description: "官方信息发布及时、口径统一"},
	{Dimension: DimensionPublicOpinion, Title: "舆情处置效果", Demo: true, Description: "舆情发酵得到有效控制"},
}

// Seed inserts the fifteen built-in evaluation indicators through the
// service, so every seed row carries a server-generated 26-character
// Crockford Base32 ULID, the current server timestamps and the regular
// validation/defaults of the domain. Deduplication is by title: when an
// indicator with the same title already exists — whether created by an
// earlier seed run or by a user — it is skipped. Seed rows are ordinary
// rows: user modifications (renames, edits) are never overwritten,
// patched or restored; a rename releases the original title, so the
// next seed run inserts a fresh indicator under the seed title. The
// seed rows are created with created_by='system', the default weight 1
// and per-dimension sort_order 1..N in SeedData order. An error aborts
// the seed run and is returned to the caller (the composition root logs
// it and keeps serving).
func Seed(ctx context.Context, service *Service) error {
	existing, _, err := service.ListIndicators(ctx, IndicatorFilter{Limit: 1000})
	if err != nil {
		return fmt.Errorf("seed evaluation indicators: list existing: %w", err)
	}
	existingTitles := make(map[string]bool, len(existing))
	for _, indicator := range existing {
		existingTitles[indicator.Title] = true
	}
	orderByDimension := make(map[Dimension]int, len(SeedData))
	for _, seed := range SeedData {
		if existingTitles[seed.Title] {
			continue
		}
		orderByDimension[seed.Dimension]++
		demo := seed.Demo
		order := orderByDimension[seed.Dimension]
		if _, err := service.CreateIndicator(ctx, IndicatorInput{
			Dimension:   seed.Dimension,
			Title:       seed.Title,
			Weight:      intPtr(1),
			Demo:        &demo,
			SortOrder:   &order,
			Description: seed.Description,
			CreatedBy:   "system",
		}); err != nil {
			return fmt.Errorf("seed evaluation indicators: create indicator %q: %w", seed.Title, err)
		}
	}
	return nil
}

func intPtr(value int) *int { return &value }
