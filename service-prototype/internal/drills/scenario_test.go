// Package drills unit tests for the drill scenario template business
// object (演练场景模板): the input validation and defaults of
// normalizeScenario, and create / get / update / delete / list through
// the service over the in-memory store. The tests never touch a
// database; the service clock and id generator are injected so ordering
// and timestamps are deterministic.
package drills

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"testing"
	"time"
)

// ulidPattern matches a 26-character Crockford Base32 ULID (the alphabet
// omits I, L, O and U).
var ulidPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// testClock is a deterministic clock: every call returns the next
// timestamp of a fixed sequence starting at start with the given step.
type testClock struct {
	current time.Time
	step    time.Duration
}

func (clock *testClock) now() time.Time {
	current := clock.current
	clock.current = clock.current.Add(clock.step)
	return current
}

// testService builds a service over a fresh in-memory store with a
// deterministic clock (1s steps from a fixed instant) and deterministic
// sequential ids, so ordering and timestamp assertions never race.
func testService() (*Service, *testClock) {
	clock := &testClock{
		current: time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC),
		step:    time.Second,
	}
	sequence := 0
	service := &Service{
		store: NewInMemoryStore(),
		now:   clock.now,
		newID: func() string {
			sequence++
			return fmt.Sprintf("scenario-%03d", sequence)
		},
	}
	return service, clock
}

var testScenarioInput = ScenarioInput{
	Name:       "大客流疏散演练",
	Category:   CategoryPassengerFlow,
	Background: "节假日高峰客流超阈值，出口拥堵",
}

func mustCreateScenario(t *testing.T, service *Service, input ScenarioInput) Scenario {
	t.Helper()
	scenario, err := service.CreateScenario(context.Background(), input)
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	return scenario
}

// ─── normalizeScenario ───────────────────────────────────────────────

// 缺 name / category / background（含空白）与非法 category / status →
// ValidationError。
func TestNormalizeScenarioRejectsInvalidInput(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	cases := map[string]ScenarioInput{
		"missing name":       {Category: CategoryPassengerFlow, Background: "背景"},
		"blank name":         {Name: "   ", Category: CategoryPassengerFlow, Background: "背景"},
		"missing category":   {Name: "演练", Background: "背景"},
		"invalid category":   {Name: "演练", Category: "不存在的分类", Background: "背景"},
		"missing background": {Name: "演练", Category: CategoryPassengerFlow},
		"blank background":   {Name: "演练", Category: CategoryPassengerFlow, Background: " \t"},
		"invalid status":     {Name: "演练", Category: CategoryPassengerFlow, Background: "背景", Status: "草稿"},
	}
	for name, input := range cases {
		_, err := normalizeScenario(input, now, "scenario-001")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
}

// 合法输入：name/background 去空白，status 缺省 启用，metadata 缺省 {}，
// created_by 透传，时间戳与 id 由调用方给出。
func TestNormalizeScenarioDefaultsAndPassthrough(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)

	withFields, err := normalizeScenario(ScenarioInput{
		Name:       " 大客流疏散演练 ",
		Category:   CategoryFire,
		Background: " 展厅烟感探测器触发 ",
		Status:     ScenarioStatusDisabled,
		Metadata:   map[string]any{"source": "merit", "level": 2},
		CreatedBy:  "u-admin",
	}, now, "scenario-001")
	if err != nil {
		t.Fatalf("normalizeScenario: %v", err)
	}
	if withFields.ID != "scenario-001" {
		t.Fatalf("id = %q, want scenario-001", withFields.ID)
	}
	if withFields.Name != "大客流疏散演练" || withFields.Background != "展厅烟感探测器触发" {
		t.Fatalf("name/background must be trimmed, got %q / %q", withFields.Name, withFields.Background)
	}
	if withFields.Category != CategoryFire {
		t.Fatalf("category = %q, want 火灾", withFields.Category)
	}
	if withFields.Status != ScenarioStatusDisabled {
		t.Fatalf("status = %q, want 停用", withFields.Status)
	}
	if withFields.Metadata["source"] != "merit" || withFields.Metadata["level"] != 2 {
		t.Fatalf("metadata = %v, want it echoed verbatim", withFields.Metadata)
	}
	if withFields.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", withFields.CreatedBy)
	}
	if !withFields.CreatedAt.Equal(now) || !withFields.UpdatedAt.Equal(now) {
		t.Fatalf("timestamps = %v / %v, want the given now", withFields.CreatedAt, withFields.UpdatedAt)
	}

	defaults, err := normalizeScenario(testScenarioInput, now, "scenario-002")
	if err != nil {
		t.Fatalf("normalizeScenario: %v", err)
	}
	if defaults.Status != DefaultScenarioStatus {
		t.Fatalf("status = %q, want default 启用", defaults.Status)
	}
	if defaults.Metadata == nil || len(defaults.Metadata) != 0 {
		t.Fatalf("metadata = %v, want a non-nil empty object", defaults.Metadata)
	}
	if defaults.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", defaults.CreatedBy)
	}
}

// ─── CreateScenario ──────────────────────────────────────────────────

// 成功创建：id 为 26 位 Crockford Base32 ULID，status 缺省 启用，metadata
// 缺省 {}，created_at/updated_at 为服务端时间。
func TestCreateScenarioAssignsULIDAndDefaults(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario, err := service.CreateScenario(context.Background(), testScenarioInput)
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	if !ulidPattern.MatchString(scenario.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", scenario.ID)
	}
	if scenario.Name != "大客流疏散演练" || scenario.Category != CategoryPassengerFlow ||
		scenario.Background != "节假日高峰客流超阈值，出口拥堵" {
		t.Fatalf("create does not echo the input: %+v", scenario)
	}
	if scenario.Status != ScenarioStatusEnabled {
		t.Fatalf("status = %q, want default 启用", scenario.Status)
	}
	if scenario.Metadata == nil || len(scenario.Metadata) != 0 {
		t.Fatalf("metadata = %v, want a non-nil empty object", scenario.Metadata)
	}
	if scenario.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", scenario.CreatedBy)
	}
	if scenario.CreatedAt.IsZero() || scenario.UpdatedAt.IsZero() {
		t.Fatalf("created_at/updated_at must be server-set, got %+v", scenario)
	}
	if !scenario.CreatedAt.Equal(scenario.UpdatedAt) {
		t.Fatalf("created_at %v != updated_at %v on create", scenario.CreatedAt, scenario.UpdatedAt)
	}
}

// 传入的 metadata / created_by 原样保存并可取回。
func TestCreateScenarioEchoesMetadataAndCreatedBy(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario, err := service.CreateScenario(context.Background(), ScenarioInput{
		Name:       "停电应急演练",
		Category:   CategoryPowerOutage,
		Background: "市电中断，备用电源切换",
		Metadata:   map[string]any{"source": "merit"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	fetched, err := service.GetScenario(context.Background(), scenario.ID)
	if err != nil {
		t.Fatalf("GetScenario: %v", err)
	}
	if fetched.CreatedBy != "u-admin" || fetched.Metadata["source"] != "merit" {
		t.Fatalf("fetched = %+v, want created_by/metadata echoed", fetched)
	}
}

// 非法输入经服务层同样返回 ValidationError，不写入存储。
func TestCreateScenarioRejectsInvalidInput(t *testing.T) {
	service := NewService(NewInMemoryStore())
	for name, input := range map[string]ScenarioInput{
		"missing name":       {Category: CategoryPassengerFlow, Background: "背景"},
		"missing category":   {Name: "演练", Background: "背景"},
		"missing background": {Name: "演练", Category: CategoryPassengerFlow},
		"invalid category":   {Name: "演练", Category: "不存在的分类", Background: "背景"},
		"invalid status":     {Name: "演练", Category: CategoryPassengerFlow, Background: "背景", Status: "草稿"},
	} {
		_, err := service.CreateScenario(context.Background(), input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
	records, total, err := service.ListScenarios(context.Background(), ScenarioFilter{Limit: 50})
	if err != nil || total != 0 || len(records) != 0 {
		t.Fatalf("store must stay empty after rejected creates: records = %d, total = %d, err = %v", len(records), total, err)
	}
}

// ─── GetScenario ─────────────────────────────────────────────────────

// 存在的 id 返回对象，不存在的 id 返回 ErrScenarioNotFound。
func TestGetScenario(t *testing.T) {
	service, _ := testService()
	created := mustCreateScenario(t, service, testScenarioInput)

	fetched, err := service.GetScenario(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("GetScenario: %v", err)
	}
	if fetched.ID != created.ID || fetched.Name != created.Name ||
		fetched.Category != created.Category || fetched.Background != created.Background ||
		fetched.Status != created.Status {
		t.Fatalf("fetched %+v does not match created %+v", fetched, created)
	}

	_, err = service.GetScenario(context.Background(), "no-such-id")
	if !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrScenarioNotFound", err)
	}
}

// ─── UpdateScenario ──────────────────────────────────────────────────

// PUT 语义：整体替换（缺省字段仍应用缺省值），created_at 保留、updated_at
// 刷新；不存在的 id 返回 ErrScenarioNotFound。
func TestUpdateScenarioReplacesAndPreservesCreatedAt(t *testing.T) {
	service, clock := testService()
	created := mustCreateScenario(t, service, testScenarioInput)
	createdAt := created.CreatedAt

	replaced, err := service.UpdateScenario(context.Background(), created.ID, ScenarioInput{
		Name:       "更新后的演练",
		Category:   CategoryFire,
		Background: "更新后的背景",
		Status:     ScenarioStatusDisabled,
		Metadata:   map[string]any{"level": "2"},
		CreatedBy:  "u-other",
	})
	if err != nil {
		t.Fatalf("UpdateScenario: %v", err)
	}
	if replaced.ID != created.ID || replaced.Name != "更新后的演练" ||
		replaced.Category != CategoryFire || replaced.Background != "更新后的背景" ||
		replaced.Status != ScenarioStatusDisabled || replaced.CreatedBy != "u-other" ||
		replaced.Metadata["level"] != "2" {
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

	// 缺省字段在更新时仍应用缺省值：status 回缺省 启用、metadata 回 {}。
	defaults, err := service.UpdateScenario(context.Background(), created.ID, ScenarioInput{
		Name:       "第三版",
		Category:   CategoryWeather,
		Background: "台风红色预警",
	})
	if err != nil {
		t.Fatalf("UpdateScenario (defaults): %v", err)
	}
	if defaults.Status != ScenarioStatusEnabled {
		t.Fatalf("status = %q, want default 启用 on update", defaults.Status)
	}
	if defaults.Metadata == nil || len(defaults.Metadata) != 0 {
		t.Fatalf("metadata = %v, want a non-nil empty object on update", defaults.Metadata)
	}
	if defaults.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted on update", defaults.CreatedBy)
	}
	if !defaults.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v across updates", createdAt, defaults.CreatedAt)
	}

	// 更新对后续读取可见。
	fetched, err := service.GetScenario(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("GetScenario after update: %v", err)
	}
	if fetched.Name != "第三版" || fetched.Category != CategoryWeather || fetched.Background != "台风红色预警" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	_, err = service.UpdateScenario(context.Background(), "no-such-id", testScenarioInput)
	if !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrScenarioNotFound", err)
	}
}

// ─── ListScenarios ───────────────────────────────────────────────────

// 空列表返回空切片与 total 0。
func TestListScenariosEmpty(t *testing.T) {
	service, _ := testService()
	records, total, err := service.ListScenarios(context.Background(), ScenarioFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListScenarios: %v", err)
	}
	if len(records) != 0 || total != 0 {
		t.Fatalf("records = %d, total = %d; want 0 / 0", len(records), total)
	}
	if records == nil {
		t.Fatal("records must be an empty slice, not nil")
	}
}

// 按 category/status 筛选生效；排序 created_at ASC, id ASC；total 先于
// 分页统计。
func TestListScenariosFilterAndSort(t *testing.T) {
	service, _ := testService()
	names := []string{"演练一", "演练二", "演练三", "演练四", "演练五"}
	inputs := []ScenarioInput{
		{Name: names[0], Category: CategoryPassengerFlow, Background: "背景一"},
		{Name: names[1], Category: CategoryPowerOutage, Background: "背景二"},
		{Name: names[2], Category: CategoryFire, Background: "背景三"},
		{Name: names[3], Category: CategoryPassengerFlow, Background: "背景四", Status: ScenarioStatusDisabled},
		{Name: names[4], Category: CategoryWeather, Background: "背景五"},
	}
	for _, input := range inputs {
		mustCreateScenario(t, service, input)
	}

	// 无筛选：按 created_at ASC（时钟逐秒递增，创建顺序即时间顺序）。
	records, total, err := service.ListScenarios(context.Background(), ScenarioFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListScenarios: %v", err)
	}
	if total != 5 || len(records) != 5 {
		t.Fatalf("records = %d, total = %d; want 5 / 5", len(records), total)
	}
	for i, name := range names {
		if records[i].Name != name {
			t.Fatalf("records[%d].name = %q, want %q (created_at ASC)", i, records[i].Name, name)
		}
	}

	// category 筛选。
	records, total, err = service.ListScenarios(context.Background(), ScenarioFilter{Category: CategoryPassengerFlow, Limit: 50})
	if err != nil || total != 2 || len(records) != 2 {
		t.Fatalf("category filter: records = %d, total = %d, err = %v; want 2 / 2", len(records), total, err)
	}
	if records[0].Name != "演练一" || records[1].Name != "演练四" {
		t.Fatalf("category filter order = %q %q, want 演练一 演练四", records[0].Name, records[1].Name)
	}

	// status 筛选。
	records, total, err = service.ListScenarios(context.Background(), ScenarioFilter{Status: ScenarioStatusDisabled, Limit: 50})
	if err != nil || total != 1 || len(records) != 1 || records[0].Name != "演练四" {
		t.Fatalf("status filter: records = %+v, total = %d, err = %v; want 演练四 / 1", records, total, err)
	}
}

// 分页生效且 meta.total 保持筛选后的总数。
func TestListScenariosPagination(t *testing.T) {
	service, _ := testService()
	for i := 0; i < 5; i++ {
		mustCreateScenario(t, service, ScenarioInput{
			Name:       fmt.Sprintf("演练%02d", i+1),
			Category:   CategoryFire,
			Background: "背景",
		})
	}

	records, total, err := service.ListScenarios(context.Background(), ScenarioFilter{Limit: 2, Offset: 0})
	if err != nil || len(records) != 2 || total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d, err = %v; want 2 / 5", len(records), total, err)
	}
	if records[0].Name != "演练01" || records[1].Name != "演练02" {
		t.Fatalf("first page = %q %q, want 演练01 演练02", records[0].Name, records[1].Name)
	}

	records, total, err = service.ListScenarios(context.Background(), ScenarioFilter{Limit: 2, Offset: 4})
	if err != nil || len(records) != 1 || total != 5 || records[0].Name != "演练05" {
		t.Fatalf("limit=2 offset=4: records = %+v, total = %d, err = %v; want 演练05 / 5", records, total, err)
	}

	records, total, err = service.ListScenarios(context.Background(), ScenarioFilter{Limit: 2, Offset: 10})
	if err != nil || len(records) != 0 || total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d, err = %v; want 0 / 5", len(records), total, err)
	}
}

// 同 created_at 时按 id ASC 排序（仓库排序口径的决胜键）。
func TestListScenariosTiebreakByIDAsc(t *testing.T) {
	store := NewInMemoryStore()
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	for _, id := range []string{"b-scenario", "a-scenario"} {
		scenario := Scenario{
			ID:         id,
			Name:       "演练-" + id,
			Category:   CategoryFire,
			Background: "背景",
			Status:     ScenarioStatusEnabled,
			Metadata:   map[string]any{},
			CreatedAt:  now,
			UpdatedAt:  now,
		}
		if err := store.CreateScenario(context.Background(), scenario); err != nil {
			t.Fatalf("CreateScenario: %v", err)
		}
	}
	records, total, err := store.ListScenarios(context.Background(), ScenarioFilter{Limit: 50})
	if err != nil || total != 2 || len(records) != 2 {
		t.Fatalf("records = %d, total = %d, err = %v; want 2 / 2", len(records), total, err)
	}
	if records[0].ID != "a-scenario" || records[1].ID != "b-scenario" {
		t.Fatalf("order = %q %q, want a-scenario b-scenario (id ASC tiebreak)", records[0].ID, records[1].ID)
	}
}

// ─── DeleteScenario ──────────────────────────────────────────────────

// 删除成功，随后 Get 返回 ErrScenarioNotFound；重复删除同样 404。
func TestDeleteScenario(t *testing.T) {
	service, _ := testService()
	created := mustCreateScenario(t, service, testScenarioInput)

	if err := service.DeleteScenario(context.Background(), created.ID); err != nil {
		t.Fatalf("DeleteScenario: %v", err)
	}
	if _, err := service.GetScenario(context.Background(), created.ID); !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("GET after DELETE: err = %v, want ErrScenarioNotFound", err)
	}
	if err := service.DeleteScenario(context.Background(), created.ID); !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("DELETE again: err = %v, want ErrScenarioNotFound", err)
	}
}
