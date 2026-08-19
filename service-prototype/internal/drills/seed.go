package drills

import (
	"context"
	"fmt"
)

// SeedScenario describes one built-in drill scenario of the seed data:
// the name, the category, the simulated background and the ordered steps
// and assessment points of the template.
type SeedScenario struct {
	Name       string
	Category   Category
	Background string
	Steps      []SeedStep
	Points     []SeedPoint
}

// SeedStep is one built-in drill scenario step (title and description,
// ordered by sort_order 1..N).
type SeedStep struct {
	SortOrder   int
	Title       string
	Description string
}

// SeedPoint is one built-in assessment point. The description is empty.
type SeedPoint struct {
	Title string
}

// SeedData holds the four built-in drill scenarios of the museum safety
// platform (大客流聚集 / 停电与基础设施 / 火灾 / 气象灾害): 4 scenarios,
// 21 steps and 15 assessment points in total. The content matches the
// product specification word for word; it is the single source of truth
// shared by the seed function and the anti-drift test of the seed
// migration (000016).
var SeedData = []SeedScenario{
	{
		Name:       "大客流聚集应急演练",
		Category:   CategoryPassengerFlow,
		Background: "新举办展览吸引大量观众，瞬时客流集中、入场通道拥堵。",
		Steps: []SeedStep{
			{SortOrder: 1, Title: "预警触发", Description: "客流统计系统识别某区域人流密度超过阈值，系统自动预警"},
			{SortOrder: 2, Title: "信息上报", Description: "值班人员通过系统向指挥中心报告，同步启动“博物馆—主管部门—属地政府”信息联动机制"},
			{SortOrder: 3, Title: "预案启动", Description: "启动大客流聚集专项应急疏解预案"},
			{SortOrder: 4, Title: "疏散引导", Description: "利用视频监控和客流统计系统精准识别人流聚集区域，通过广播、电子屏、现场人员引导疏散"},
			{SortOrder: 5, Title: "限流分流", Description: "实施预约限流、分时分批入场等措施"},
		},
		Points: []SeedPoint{
			{Title: "预警响应时间"},
			{Title: "信息上报规范性"},
			{Title: "疏散路线合理性"},
			{Title: "观众安抚效果"},
		},
	},
	{
		Name:       "停电与基础设施故障应急演练",
		Category:   CategoryPowerOutage,
		Background: "市电中断，安消防控制室、电梯、展厅、库房等关键区域面临停电风险。",
		Steps: []SeedStep{
			{SortOrder: 1, Title: "故障发现", Description: "供配电系统监测到异常，系统自动报警"},
			{SortOrder: 2, Title: "应急供电", Description: "启动多层级供电保障体系，确保关键区域持续运行"},
			{SortOrder: 3, Title: "观众疏导", Description: "启动应急照明，通过广播引导观众有序撤离或原地等候"},
			{SortOrder: 4, Title: "空调故障应对", Description: "启动备用通风方案，做好观众解释安抚工作"},
			{SortOrder: 5, Title: "设备抢修", Description: "联系专业团队进行故障排查与修复"},
		},
		Points: []SeedPoint{
			{Title: "应急供电切换速度"},
			{Title: "观众疏导秩序"},
			{Title: "信息发布及时性"},
		},
	},
	{
		Name:       "火灾应急处置演练",
		Category:   CategoryFire,
		Background: "展厅电气线路故障引发火情。",
		Steps: []SeedStep{
			{SortOrder: 1, Title: "火情发现与报警", Description: "烟感探测器触发、视频监控确认"},
			{SortOrder: 2, Title: "初期处置", Description: "使用灭火器、消火栓进行初期火灾扑救"},
			{SortOrder: 3, Title: "人员疏散", Description: "启动应急广播，按照疏散路线组织观众和工作人员撤离"},
			{SortOrder: 4, Title: "文物转移", Description: "对展厅内珍贵文物实施紧急转移保护"},
			{SortOrder: 5, Title: "消防联动", Description: "拨打119，引导消防救援力量入场"},
			{SortOrder: 6, Title: "善后处置", Description: "现场保护、损失评估、信息发布"},
		},
		Points: []SeedPoint{
			{Title: "报警及时性"},
			{Title: "初期处置规范性"},
			{Title: "疏散效率"},
			{Title: "文物安全保护"},
		},
	},
	{
		Name:       "气象灾害应急演练",
		Category:   CategoryWeather,
		Background: "暑期高温、雷电、暴雨、台风等极端天气来袭。",
		Steps: []SeedStep{
			{SortOrder: 1, Title: "预警接收", Description: "气象部门发布预警信息，系统自动接收并推送"},
			{SortOrder: 2, Title: "研判决策", Description: "馆领导研判是否调整开放安排"},
			{SortOrder: 3, Title: "信息发布", Description: "通过官网、微信公众号、馆内广播等渠道发布调整通知"},
			{SortOrder: 4, Title: "现场处置", Description: "加固户外设施、疏导滞留观众、做好防汛排涝"},
			{SortOrder: 5, Title: "灾后恢复", Description: "隐患排查、设施检修、恢复正常开放"},
		},
		Points: []SeedPoint{
			{Title: "预警响应速度"},
			{Title: "决策科学性"},
			{Title: "信息发布覆盖面"},
			{Title: "现场处置效果"},
		},
	},
}

// Seed inserts the four built-in drill scenarios with their steps and
// assessment points through the service, so every seed row carries a
// server-generated 26-character Crockford Base32 ULID, the current
// server timestamps and the regular validation/defaults of the domain.
// Deduplication is by scenario name: when a scenario with the same name
// already exists — whether created by an earlier seed run or by a user —
// the whole scenario (steps and assessment points included) is skipped.
// Seed rows are ordinary rows: user modifications (renames, disabling,
// edits) are never overwritten, patched or restored; a rename releases
// the original name, so the next seed run inserts a fresh scenario under
// the seed name. The seed rows are created with created_by='system', the
// scenario status defaults to 启用 and the metadata to an empty object.
// An error aborts the seed run and is returned to the caller (the
// composition root logs it and keeps serving).
func Seed(ctx context.Context, service *Service) error {
	existing, _, err := service.ListScenarios(ctx, ScenarioFilter{Limit: 1000})
	if err != nil {
		return fmt.Errorf("seed drill scenarios: list existing: %w", err)
	}
	existingNames := make(map[string]bool, len(existing))
	for _, scenario := range existing {
		existingNames[scenario.Name] = true
	}
	for _, seed := range SeedData {
		if existingNames[seed.Name] {
			continue
		}
		scenario, err := service.CreateScenario(ctx, ScenarioInput{
			Name:       seed.Name,
			Category:   seed.Category,
			Background: seed.Background,
			CreatedBy:  "system",
		})
		if err != nil {
			return fmt.Errorf("seed drill scenarios: create scenario %q: %w", seed.Name, err)
		}
		for _, step := range seed.Steps {
			if _, err := service.CreateStep(ctx, scenario.ID, StepInput{
				SortOrder:   step.SortOrder,
				Title:       step.Title,
				Description: step.Description,
				CreatedBy:   "system",
			}); err != nil {
				return fmt.Errorf("seed drill scenarios: create step %q of %q: %w", step.Title, seed.Name, err)
			}
		}
		for _, point := range seed.Points {
			if _, err := service.CreatePoint(ctx, scenario.ID, PointInput{
				Title:     point.Title,
				CreatedBy: "system",
			}); err != nil {
				return fmt.Errorf("seed drill scenarios: create point %q of %q: %w", point.Title, seed.Name, err)
			}
		}
	}
	return nil
}
