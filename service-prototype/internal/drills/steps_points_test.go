// Service unit tests for the drill scenario steps and assessment points
// (演练流程步骤 / 考核要点模板): input validation and defaults, create /
// list / get / update / delete through the service over the in-memory
// store, and the cascade deletion of both child resources when the
// scenario child cleaner is wired. The tests never touch a database; the
// service clock and id generator are injected so ordering and timestamps
// are deterministic.
package drills

import (
	"context"
	"errors"
	"testing"
	"time"
)

// stepInput is a valid step input for the service-level tests.
var stepInput = StepInput{SortOrder: 1, Title: "疏散广播", Description: "启动应急广播"}

// pointInput is a valid assessment point input for the service-level
// tests.
var pointInput = PointInput{Title: "疏散指令传达", Description: "考察指令传达是否准确"}

// mustCreateStep creates a step within the given scenario, failing the
// test on error.
func mustCreateStep(t *testing.T, service *Service, scenarioID string, input StepInput) ScenarioStep {
	t.Helper()
	step, err := service.CreateStep(context.Background(), scenarioID, input)
	if err != nil {
		t.Fatalf("CreateStep: %v", err)
	}
	return step
}

// mustCreatePoint creates an assessment point within the given scenario,
// failing the test on error.
func mustCreatePoint(t *testing.T, service *Service, scenarioID string, input PointInput) AssessmentPoint {
	t.Helper()
	point, err := service.CreatePoint(context.Background(), scenarioID, input)
	if err != nil {
		t.Fatalf("CreatePoint: %v", err)
	}
	return point
}

// ─── normalizeStep / normalizePoint ──────────────────────────────────

// sort_order 负数 → ValidationError（normalizeStep 同时服务 POST 与 PUT，
// 两入口口径一致）；缺 title（含空白）同样拒绝。
func TestNormalizeStepRejectsInvalidInput(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	cases := map[string]StepInput{
		"missing title": {SortOrder: 1},
		"blank title":   {SortOrder: 1, Title: "  "},
		"negative sort": {SortOrder: -1, Title: "疏散广播"},
		"both invalid":  {SortOrder: -2, Title: " "},
	}
	for name, input := range cases {
		_, err := normalizeStep("scenario-001", input, now, "step-001")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
}

// normalizeStep 合法输入：scenario_id 由调用方给出、sort_order/description/
// created_by 透传（缺省 0 / ” / ”），时间戳与 id 由调用方给出。
func TestNormalizeStepDefaultsAndPassthrough(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)

	withFields, err := normalizeStep("scenario-001", StepInput{
		SortOrder:   2,
		Title:       " 疏散广播 ",
		Description: "启动应急广播",
		CreatedBy:   "u-admin",
	}, now, "step-001")
	if err != nil {
		t.Fatalf("normalizeStep: %v", err)
	}
	if withFields.ID != "step-001" || withFields.ScenarioID != "scenario-001" || withFields.SortOrder != 2 ||
		withFields.Title != "疏散广播" || withFields.Description != "启动应急广播" || withFields.CreatedBy != "u-admin" {
		t.Fatalf("normalizeStep = %+v, want the given fields (title trimmed)", withFields)
	}
	if !withFields.CreatedAt.Equal(now) || !withFields.UpdatedAt.Equal(now) {
		t.Fatalf("timestamps = %v / %v, want the given now", withFields.CreatedAt, withFields.UpdatedAt)
	}

	defaults, err := normalizeStep("scenario-001", StepInput{Title: "疏散广播"}, now, "step-002")
	if err != nil {
		t.Fatalf("normalizeStep: %v", err)
	}
	if defaults.SortOrder != 0 || defaults.Description != "" || defaults.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want sort_order 0, description '', created_by ''", defaults)
	}
}

// normalizePoint 缺 title（含空白）→ ValidationError；合法输入透传
// description/created_by（缺省 ”）。
func TestNormalizePoint(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	for name, input := range map[string]PointInput{
		"missing title": {},
		"blank title":   {Title: "  "},
	} {
		_, err := normalizePoint("scenario-001", input, now, "point-001")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}

	point, err := normalizePoint("scenario-001", PointInput{Title: " 疏散指令传达 ", Description: "考察准确性", CreatedBy: "u-admin"}, now, "point-001")
	if err != nil {
		t.Fatalf("normalizePoint: %v", err)
	}
	if point.ID != "point-001" || point.ScenarioID != "scenario-001" || point.Title != "疏散指令传达" ||
		point.Description != "考察准确性" || point.CreatedBy != "u-admin" {
		t.Fatalf("normalizePoint = %+v, want the given fields (title trimmed)", point)
	}

	defaults, err := normalizePoint("scenario-001", PointInput{Title: "疏散指令传达"}, now, "point-002")
	if err != nil {
		t.Fatalf("normalizePoint: %v", err)
	}
	if defaults.Description != "" || defaults.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want description '' and created_by ''", defaults)
	}
}

// ─── CreateStep / CreatePoint ────────────────────────────────────────

// 成功创建：id 为 26 位 Crockford Base32 ULID，scenario_id 来自路径参数，
// sort_order 缺省 0、description 缺省 ”、created_by 缺省 ”，
// created_at/updated_at 为服务端时间。
func TestCreateStepAssignsULIDAndDefaults(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, testScenarioInput)

	step, err := service.CreateStep(context.Background(), scenario.ID, StepInput{Title: "疏散广播"})
	if err != nil {
		t.Fatalf("CreateStep: %v", err)
	}
	if !ulidPattern.MatchString(step.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", step.ID)
	}
	if step.ScenarioID != scenario.ID {
		t.Fatalf("scenario_id = %q, want %q", step.ScenarioID, scenario.ID)
	}
	if step.SortOrder != 0 || step.Description != "" || step.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want sort_order 0, description '', created_by ''", step)
	}
	if step.CreatedAt.IsZero() || step.UpdatedAt.IsZero() || !step.CreatedAt.Equal(step.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want equal server-set times", step.CreatedAt, step.UpdatedAt)
	}
}

// scenario 不存在 → ErrScenarioNotFound，不写入存储。
func TestCreateStepScenarioNotFound(t *testing.T) {
	service := NewService(NewInMemoryStore())
	_, err := service.CreateStep(context.Background(), "no-such-scenario", stepInput)
	if !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("err = %v, want ErrScenarioNotFound", err)
	}
}

// 非法输入经服务层同样返回 ValidationError（POST 与 PUT 共用 normalizeStep，
// 口径一致）。
func TestCreateStepRejectsInvalidInput(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, testScenarioInput)
	for name, input := range map[string]StepInput{
		"missing title": {SortOrder: 1},
		"blank title":   {SortOrder: 1, Title: " "},
		"negative sort": {SortOrder: -1, Title: "疏散广播"},
	} {
		_, err := service.CreateStep(context.Background(), scenario.ID, input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
}

// 成功创建考核要点：id 为 ULID、scenario_id 来自路径参数、description/
// created_by 缺省 ”。
func TestCreatePointAssignsULIDAndDefaults(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, testScenarioInput)

	point, err := service.CreatePoint(context.Background(), scenario.ID, PointInput{Title: "疏散指令传达"})
	if err != nil {
		t.Fatalf("CreatePoint: %v", err)
	}
	if !ulidPattern.MatchString(point.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", point.ID)
	}
	if point.ScenarioID != scenario.ID {
		t.Fatalf("scenario_id = %q, want %q", point.ScenarioID, scenario.ID)
	}
	if point.Description != "" || point.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want description '' and created_by ''", point)
	}
	if point.CreatedAt.IsZero() || point.UpdatedAt.IsZero() || !point.CreatedAt.Equal(point.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want equal server-set times", point.CreatedAt, point.UpdatedAt)
	}
}

// scenario 不存在 → ErrScenarioNotFound。
func TestCreatePointScenarioNotFound(t *testing.T) {
	service := NewService(NewInMemoryStore())
	_, err := service.CreatePoint(context.Background(), "no-such-scenario", pointInput)
	if !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("err = %v, want ErrScenarioNotFound", err)
	}
}

// ─── ListSteps / ListPoints ──────────────────────────────────────────

// 空列表返回空切片（非 nil）与 total 0。
func TestListStepsEmpty(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)

	records, total, err := service.ListSteps(context.Background(), scenario.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListSteps: %v", err)
	}
	if len(records) != 0 || total != 0 {
		t.Fatalf("records = %d, total = %d; want 0 / 0", len(records), total)
	}
	if records == nil {
		t.Fatal("records must be an empty slice, not nil")
	}
}

// 仅返回路径场景的步骤（其他场景的步骤不出现）；按 sort_order ASC,
// created_at ASC 排序。
func TestListStepsSortedAndScopedToScenario(t *testing.T) {
	service, _ := testService()
	scenarioA := mustCreateScenario(t, service, testScenarioInput)
	scenarioB := mustCreateScenario(t, service, ScenarioInput{Name: "停电应急演练", Category: CategoryPowerOutage, Background: "市电中断"})

	// 乱序创建：sort_order 2, 0, 1 → 列表按 sort_order ASC 返回 0, 1, 2；
	// 相同 sort_order 时按 created_at ASC（时钟逐秒递增）。
	mustCreateStep(t, service, scenarioA.ID, StepInput{SortOrder: 2, Title: "第三"})
	mustCreateStep(t, service, scenarioA.ID, StepInput{SortOrder: 0, Title: "第一"})
	mustCreateStep(t, service, scenarioA.ID, StepInput{SortOrder: 1, Title: "第二"})
	mustCreateStep(t, service, scenarioA.ID, StepInput{SortOrder: 1, Title: "第二-晚"})
	// 另一个场景的步骤不得出现在 A 的列表里。
	mustCreateStep(t, service, scenarioB.ID, StepInput{SortOrder: 0, Title: "B场景步骤"})

	records, total, err := service.ListSteps(context.Background(), scenarioA.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListSteps: %v", err)
	}
	if total != 4 || len(records) != 4 {
		t.Fatalf("records = %d, total = %d; want 4 / 4", len(records), total)
	}
	for i, title := range []string{"第一", "第二", "第二-晚", "第三"} {
		if records[i].Title != title {
			t.Fatalf("records[%d].title = %q, want %q (sort_order ASC, created_at ASC)", i, records[i].Title, title)
		}
	}

	records, total, err = service.ListSteps(context.Background(), scenarioB.ID, ListFilter{Limit: 50})
	if err != nil || total != 1 || len(records) != 1 || records[0].Title != "B场景步骤" {
		t.Fatalf("scenario B list = %+v, total = %d, err = %v; want only its own step", records, total, err)
	}
}

// scenario 不存在 → ErrScenarioNotFound；分页生效且 total 为分页前总数。
func TestListStepsScenarioNotFoundAndPagination(t *testing.T) {
	service, _ := testService()
	if _, _, err := service.ListSteps(context.Background(), "no-such-scenario", ListFilter{Limit: 50}); !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("missing scenario: err = %v, want ErrScenarioNotFound", err)
	}

	scenario := mustCreateScenario(t, service, testScenarioInput)
	for i := 0; i < 5; i++ {
		mustCreateStep(t, service, scenario.ID, StepInput{Title: "步骤"})
	}
	records, total, err := service.ListSteps(context.Background(), scenario.ID, ListFilter{Limit: 2, Offset: 1})
	if err != nil || len(records) != 2 || total != 5 {
		t.Fatalf("limit=2 offset=1: records = %d, total = %d, err = %v; want 2 / 5", len(records), total, err)
	}
}

// 空列表、按 created_at ASC 排序、路径筛选与分页（考核要点孪生口径）。
func TestListPointsSortedAndScopedToScenario(t *testing.T) {
	service, _ := testService()
	scenarioA := mustCreateScenario(t, service, testScenarioInput)
	scenarioB := mustCreateScenario(t, service, ScenarioInput{Name: "停电应急演练", Category: CategoryPowerOutage, Background: "市电中断"})

	mustCreatePoint(t, service, scenarioA.ID, PointInput{Title: "要点三"})
	mustCreatePoint(t, service, scenarioA.ID, PointInput{Title: "要点一"})
	mustCreatePoint(t, service, scenarioA.ID, PointInput{Title: "要点二"})
	mustCreatePoint(t, service, scenarioB.ID, PointInput{Title: "B场景要点"})

	records, total, err := service.ListPoints(context.Background(), scenarioA.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListPoints: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(records), total)
	}
	for i, title := range []string{"要点三", "要点一", "要点二"} {
		if records[i].Title != title {
			t.Fatalf("records[%d].title = %q, want %q (created_at ASC)", i, records[i].Title, title)
		}
	}

	records, total, err = service.ListPoints(context.Background(), scenarioB.ID, ListFilter{Limit: 50})
	if err != nil || total != 1 || len(records) != 1 || records[0].Title != "B场景要点" {
		t.Fatalf("scenario B list = %+v, total = %d, err = %v; want only its own point", records, total, err)
	}
	if len(records) == 0 {
		t.Fatal("records must not be nil")
	}

	if _, _, err := service.ListPoints(context.Background(), "no-such-scenario", ListFilter{Limit: 50}); !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("missing scenario: err = %v, want ErrScenarioNotFound", err)
	}
}

// ─── GetStep / GetPoint ──────────────────────────────────────────────

// 存在的 id 返回对象，不存在的 id 返回对应 Err*NotFound。
func TestGetStepAndPoint(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)

	fetchedStep, err := service.GetStep(context.Background(), step.ID)
	if err != nil {
		t.Fatalf("GetStep: %v", err)
	}
	if fetchedStep.ID != step.ID || fetchedStep.ScenarioID != scenario.ID ||
		fetchedStep.SortOrder != 1 || fetchedStep.Title != "疏散广播" ||
		fetchedStep.Description != "启动应急广播" {
		t.Fatalf("fetched step %+v does not match created %+v", fetchedStep, step)
	}

	fetchedPoint, err := service.GetPoint(context.Background(), point.ID)
	if err != nil {
		t.Fatalf("GetPoint: %v", err)
	}
	if fetchedPoint.ID != point.ID || fetchedPoint.ScenarioID != scenario.ID ||
		fetchedPoint.Title != "疏散指令传达" || fetchedPoint.Description != "考察指令传达是否准确" {
		t.Fatalf("fetched point %+v does not match created %+v", fetchedPoint, point)
	}

	if _, err := service.GetStep(context.Background(), "no-such-id"); !errors.Is(err, ErrStepNotFound) {
		t.Fatalf("unknown step: err = %v, want ErrStepNotFound", err)
	}
	if _, err := service.GetPoint(context.Background(), "no-such-id"); !errors.Is(err, ErrPointNotFound) {
		t.Fatalf("unknown point: err = %v, want ErrPointNotFound", err)
	}
}

// ─── UpdateStep / UpdatePoint ────────────────────────────────────────

// PUT 语义：整体替换（缺省字段仍应用缺省值），scenario_id 来自既有记录
// （请求体不可改写），created_at 保留、updated_at 刷新；校验口径与创建
// 一致；不存在的 id 返回对应 Err*NotFound。
func TestUpdateStepReplacesAndPreservesCreatedAt(t *testing.T) {
	service, clock := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	created := mustCreateStep(t, service, scenario.ID, stepInput)
	createdAt := created.CreatedAt

	replaced, err := service.UpdateStep(context.Background(), created.ID, StepInput{
		SortOrder:   4,
		Title:       "疏散广播-加强",
		Description: "启动应急广播并引导",
		CreatedBy:   "u-other",
	})
	if err != nil {
		t.Fatalf("UpdateStep: %v", err)
	}
	if replaced.ID != created.ID || replaced.ScenarioID != scenario.ID || replaced.SortOrder != 4 ||
		replaced.Title != "疏散广播-加强" || replaced.Description != "启动应急广播并引导" || replaced.CreatedBy != "u-other" {
		t.Fatalf("updated %+v is not the replaced record", replaced)
	}
	if !replaced.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v on update", createdAt, replaced.CreatedAt)
	}
	if !replaced.UpdatedAt.After(createdAt) {
		t.Fatalf("updated_at %v must be refreshed after created_at %v", replaced.UpdatedAt, createdAt)
	}
	if clock.current.Sub(createdAt) <= 0 {
		t.Fatalf("test clock must have advanced past created_at")
	}

	// 缺省字段在更新时仍应用缺省值：sort_order 回 0、description 回 ''。
	defaults, err := service.UpdateStep(context.Background(), created.ID, StepInput{Title: "第三版"})
	if err != nil {
		t.Fatalf("UpdateStep (defaults): %v", err)
	}
	if defaults.SortOrder != 0 || defaults.Description != "" || defaults.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want sort_order 0, description '', created_by ''", defaults)
	}
	if !defaults.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v across updates", createdAt, defaults.CreatedAt)
	}

	// 更新对后续读取可见。
	fetched, err := service.GetStep(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("GetStep after update: %v", err)
	}
	if fetched.Title != "第三版" || fetched.SortOrder != 0 {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// 校验口径与创建一致。
	for name, input := range map[string]StepInput{
		"missing title": {SortOrder: 1},
		"blank title":   {SortOrder: 1, Title: " "},
		"negative sort": {SortOrder: -1, Title: "疏散广播"},
	} {
		var validationError *ValidationError
		if _, err := service.UpdateStep(context.Background(), created.ID, input); !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}

	if _, err := service.UpdateStep(context.Background(), "no-such-id", stepInput); !errors.Is(err, ErrStepNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrStepNotFound", err)
	}
}

// 考核要点 PUT 语义：整体替换、created_at 保留、updated_at 刷新、缺 title
// 400、不存在 id 404。
func TestUpdatePointReplacesAndPreservesCreatedAt(t *testing.T) {
	service, clock := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	created := mustCreatePoint(t, service, scenario.ID, pointInput)
	createdAt := created.CreatedAt

	replaced, err := service.UpdatePoint(context.Background(), created.ID, PointInput{
		Title:       "疏散指令传达-加强",
		Description: "考察指令传达准确性与时效性",
		CreatedBy:   "u-other",
	})
	if err != nil {
		t.Fatalf("UpdatePoint: %v", err)
	}
	if replaced.ID != created.ID || replaced.ScenarioID != scenario.ID ||
		replaced.Title != "疏散指令传达-加强" ||
		replaced.Description != "考察指令传达准确性与时效性" || replaced.CreatedBy != "u-other" {
		t.Fatalf("updated %+v is not the replaced record", replaced)
	}
	if !replaced.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v on update", createdAt, replaced.CreatedAt)
	}
	if !replaced.UpdatedAt.After(createdAt) {
		t.Fatalf("updated_at %v must be refreshed after created_at %v", replaced.UpdatedAt, createdAt)
	}
	if clock.current.Sub(createdAt) <= 0 {
		t.Fatalf("test clock must have advanced past created_at")
	}

	// 缺省字段在更新时仍应用缺省值：description 回 ''。
	defaults, err := service.UpdatePoint(context.Background(), created.ID, PointInput{Title: "第三版"})
	if err != nil {
		t.Fatalf("UpdatePoint (defaults): %v", err)
	}
	if defaults.Description != "" || defaults.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want description '' and created_by ''", defaults)
	}

	fetched, err := service.GetPoint(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("GetPoint after update: %v", err)
	}
	if fetched.Title != "第三版" || fetched.Description != "" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	var validationError *ValidationError
	if _, err := service.UpdatePoint(context.Background(), created.ID, PointInput{}); !errors.As(err, &validationError) {
		t.Fatalf("missing title: err = %v, want a ValidationError", err)
	}
	if _, err := service.UpdatePoint(context.Background(), "no-such-id", pointInput); !errors.Is(err, ErrPointNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrPointNotFound", err)
	}
}

// ─── DeleteStep / DeletePoint ────────────────────────────────────────

// 删除成功，随后 Get 返回对应 Err*NotFound；重复删除同样 404。
func TestDeleteStepAndPoint(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)

	if err := service.DeleteStep(context.Background(), step.ID); err != nil {
		t.Fatalf("DeleteStep: %v", err)
	}
	if _, err := service.GetStep(context.Background(), step.ID); !errors.Is(err, ErrStepNotFound) {
		t.Fatalf("GET step after DELETE: err = %v, want ErrStepNotFound", err)
	}
	if err := service.DeleteStep(context.Background(), step.ID); !errors.Is(err, ErrStepNotFound) {
		t.Fatalf("DELETE step again: err = %v, want ErrStepNotFound", err)
	}

	if err := service.DeletePoint(context.Background(), point.ID); err != nil {
		t.Fatalf("DeletePoint: %v", err)
	}
	if _, err := service.GetPoint(context.Background(), point.ID); !errors.Is(err, ErrPointNotFound) {
		t.Fatalf("GET point after DELETE: err = %v, want ErrPointNotFound", err)
	}
	if err := service.DeletePoint(context.Background(), point.ID); !errors.Is(err, ErrPointNotFound) {
		t.Fatalf("DELETE point again: err = %v, want ErrPointNotFound", err)
	}
}

// ─── DeleteScenario 级联 ─────────────────────────────────────────────

// 接线 SetScenarioChildCleaner 后删除场景，其步骤与考核要点随之清空，其他
// 场景的子资源保留；未接线时子资源原样保留（级联是可选的注入行为）。
func TestDeleteScenarioCascadesToStepsAndPoints(t *testing.T) {
	store := NewInMemoryStore()
	service := NewService(store)
	service.SetScenarioChildCleaner(store)
	scenarioA := mustCreateScenario(t, service, testScenarioInput)
	scenarioB := mustCreateScenario(t, service, ScenarioInput{Name: "停电应急演练", Category: CategoryPowerOutage, Background: "市电中断"})

	// 每个场景各一个步骤与一个考核要点；A 多一个步骤以覆盖同场景多行。
	mustCreateStep(t, service, scenarioA.ID, StepInput{SortOrder: 0, Title: "A-步骤1"})
	mustCreateStep(t, service, scenarioA.ID, StepInput{SortOrder: 1, Title: "A-步骤2"})
	mustCreateStep(t, service, scenarioB.ID, StepInput{SortOrder: 0, Title: "B-步骤"})
	mustCreatePoint(t, service, scenarioA.ID, PointInput{Title: "A-要点"})
	mustCreatePoint(t, service, scenarioB.ID, PointInput{Title: "B-要点"})

	if err := service.DeleteScenario(context.Background(), scenarioA.ID); err != nil {
		t.Fatalf("DeleteScenario: %v", err)
	}

	// A 的步骤与要点全部消失（场景已删除，服务层列表不可再查；直接断言
	// 存储层行已清空）。
	stepRecords, err := store.ListStepsByScenario(context.Background(), scenarioA.ID)
	if err != nil {
		t.Fatalf("ListStepsByScenario after cascade: %v", err)
	}
	if len(stepRecords) != 0 {
		t.Fatalf("A steps after cascade = %d, want 0", len(stepRecords))
	}
	pointRecords, err := store.ListPointsByScenario(context.Background(), scenarioA.ID)
	if err != nil {
		t.Fatalf("ListPointsByScenario after cascade: %v", err)
	}
	if len(pointRecords) != 0 {
		t.Fatalf("A points after cascade = %d, want 0", len(pointRecords))
	}

	// B 的子资源保留。
	stepRecords, err = store.ListStepsByScenario(context.Background(), scenarioB.ID)
	if err != nil || len(stepRecords) != 1 || stepRecords[0].Title != "B-步骤" {
		t.Fatalf("B steps after cascade = %+v, err = %v; want 1", stepRecords, err)
	}
	pointRecords, err = store.ListPointsByScenario(context.Background(), scenarioB.ID)
	if err != nil || len(pointRecords) != 1 || pointRecords[0].Title != "B-要点" {
		t.Fatalf("B points after cascade = %+v, err = %v; want 1", pointRecords, err)
	}

	// 级联删除不影响后续创建。
	mustCreateStep(t, service, scenarioB.ID, StepInput{SortOrder: 1, Title: "B-步骤2"})
	stepRecords, err = store.ListStepsByScenario(context.Background(), scenarioB.ID)
	if err != nil || len(stepRecords) != 2 {
		t.Fatalf("B steps after new create = %d, err = %v; want 2", len(stepRecords), err)
	}
}

// 未接线 cleaner 时 DeleteScenario 只删除场景本身，子资源保留。
func TestDeleteScenarioWithoutCleanerKeepsChildren(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)

	if err := service.DeleteScenario(context.Background(), scenario.ID); err != nil {
		t.Fatalf("DeleteScenario: %v", err)
	}
	if _, err := service.GetStep(context.Background(), step.ID); err != nil {
		t.Fatalf("step must survive without a cleaner, err = %v", err)
	}
	if _, err := service.GetPoint(context.Background(), point.ID); err != nil {
		t.Fatalf("point must survive without a cleaner, err = %v", err)
	}
}
