// Service unit tests for the drill simulated events (演练模拟事件演示):
// the input validation and defaults of normalizeSimEvent, the
// category-mapping check (validEventTypeForCategory) on create and
// update, the writable-run constraint (仅 进行中 可写), the handled_at
// linkage with the status, the partial-update semantics of
// UpdateSimEvent, and the sorted + filtered + paginated list. The tests
// never touch a database; the service clock and id generator are
// injected so ordering and timestamps are deterministic.
package drills

import (
	"context"
	"errors"
	"testing"
	"time"
)

// simEventScenarioInput is a valid scenario input of the given category.
// The category drives the allowed event types (validEventTypeForCategory).
func simEventScenarioInput(name string, category Category) ScenarioInput {
	return ScenarioInput{Name: name, Category: category, Background: "模拟背景"}
}

// mustCreateSimEvent raises an event within the given run with the
// default input (客流密度超阈值 for a 大客流聚集 run), failing the test
// on error.
func mustCreateSimEvent(t *testing.T, service *Service, runID string, input SimEventInput) SimEvent {
	t.Helper()
	event, err := service.CreateSimEvent(context.Background(), runID, input)
	if err != nil {
		t.Fatalf("CreateSimEvent: %v", err)
	}
	return event
}

// ─── normalizeSimEvent ───────────────────────────────────────────────

// 非法 event_type / status → ValidationError。
func TestNormalizeSimEventRejectsInvalidInput(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	cases := map[string]SimEventInput{
		"missing event_type": {Status: SimEventTriggered},
		"invalid event_type": {EventType: "不存在的类型"},
		"invalid status":     {EventType: SimEventFlowOverflow, Status: "草稿"},
	}
	for name, input := range cases {
		_, err := normalizeSimEvent("run-001", input, now, "event-001")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}
}

// 缺省值：payload {}、status 已触发、triggered_at 由服务端设置（非空）、
// created_by 透传、created_at/updated_at 为 now。
func TestNormalizeSimEventDefaultsAndPassthrough(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	event, err := normalizeSimEvent("run-001", SimEventInput{
		EventType: SimEventFlowOverflow,
		Payload:   map[string]any{"count": 120},
		CreatedBy: "u-admin",
	}, now, "event-001")
	if err != nil {
		t.Fatalf("normalizeSimEvent: %v", err)
	}
	if event.ID != "event-001" || event.RunID != "run-001" || event.EventType != SimEventFlowOverflow {
		t.Fatalf("normalizeSimEvent does not echo the input: %+v", event)
	}
	if event.Status != DefaultSimEventStatus {
		t.Fatalf("status = %q, want %q (default)", event.Status, DefaultSimEventStatus)
	}
	if len(event.Payload) != 1 || event.Payload["count"] != 120 {
		t.Fatalf("payload = %v, want the request payload echoed", event.Payload)
	}
	if event.TriggeredAt == nil || !event.TriggeredAt.Equal(now) {
		t.Fatalf("triggered_at = %v, want %v (server-set)", event.TriggeredAt, now)
	}
	if event.HandledAt != nil {
		t.Fatalf("handled_at = %v, want nil", event.HandledAt)
	}
	if event.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", event.CreatedBy)
	}
	if !event.CreatedAt.Equal(now) || !event.UpdatedAt.Equal(now) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", event.CreatedAt, event.UpdatedAt, now)
	}
}

// payload 缺省 {}：omit 与显式空对象都得到非 nil 空 map。
func TestNormalizeSimEventPayloadDefaultsToEmptyObject(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	for name, input := range map[string]SimEventInput{
		"omitted": {EventType: SimEventFlowOverflow},
		"nil map": {EventType: SimEventFlowOverflow, Payload: nil},
	} {
		event, err := normalizeSimEvent("run-001", input, now, "event-001")
		if err != nil {
			t.Fatalf("%s: normalizeSimEvent: %v", name, err)
		}
		if event.Payload == nil || len(event.Payload) != 0 {
			t.Fatalf("%s: payload = %v, want an empty object", name, event.Payload)
		}
	}
}

// ─── CreateSimEvent ──────────────────────────────────────────────────

// 仅 进行中 可创建：run 不存在 → ErrRunNotFound；未开始/已完成/已终止 →
// ValidationError。
func TestCreateSimEventRequiresInProgressRun(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, simEventScenarioInput("大客流疏散演练", CategoryPassengerFlow))

	_, err := service.CreateSimEvent(context.Background(), "run-missing", SimEventInput{EventType: SimEventFlowOverflow})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	run := mustCreateRun(t, service, scenario.ID, runInput)
	_, err = service.CreateSimEvent(context.Background(), run.ID, SimEventInput{EventType: SimEventFlowOverflow})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("未开始 run: err = %v, want ValidationError", err)
	}

	mustStartRun(t, service, run.ID)
	if _, err := service.CreateSimEvent(context.Background(), run.ID, SimEventInput{EventType: SimEventFlowOverflow}); err != nil {
		t.Fatalf("进行中 run: CreateSimEvent: %v", err)
	}

	if _, err := service.CompleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("CompleteRun: %v", err)
	}
	_, err = service.CreateSimEvent(context.Background(), run.ID, SimEventInput{EventType: SimEventFlowOverflow})
	if !errors.As(err, &validationError) {
		t.Fatalf("已完成 run: err = %v, want ValidationError", err)
	}
}

// 类型-分类映射：每个分类恰好对应一个事件类型，不匹配 → ValidationError；
// 其他 任意场景可用；非法 event_type → ValidationError。
func TestCreateSimEventCategoryMapping(t *testing.T) {
	service := NewService(NewInMemoryStore())
	// scenarioA 大客流聚集 / scenarioB 停电与基础设施 / scenarioC 火灾 /
	// scenarioD 气象灾害。
	scenarioA := mustCreateScenario(t, service, simEventScenarioInput("大客流疏散演练", CategoryPassengerFlow))
	scenarioB := mustCreateScenario(t, service, simEventScenarioInput("停电应急演练", CategoryPowerOutage))
	scenarioC := mustCreateScenario(t, service, simEventScenarioInput("火灾疏散演练", CategoryFire))
	scenarioD := mustCreateScenario(t, service, simEventScenarioInput("台风防范演练", CategoryWeather))

	matching := []struct {
		scenario  Scenario
		eventType SimEventType
	}{
		{scenarioA, SimEventFlowOverflow},
		{scenarioB, SimEventPowerAlarm},
		{scenarioC, SimEventSmokeAlarm},
		{scenarioD, SimEventWeatherWarning},
	}
	// 匹配的类型全部可用。
	for _, match := range matching {
		scenario, eventType := match.scenario, match.eventType
		run := mustCreateRun(t, service, scenario.ID, runInput)
		mustStartRun(t, service, run.ID)
		if _, err := service.CreateSimEvent(context.Background(), run.ID, SimEventInput{EventType: eventType}); err != nil {
			t.Fatalf("category %q event_type %q: %v", scenario.Category, eventType, err)
		}
	}

	// 其他 任意场景可用。
	for _, scenario := range []Scenario{scenarioA, scenarioB, scenarioC, scenarioD} {
		run := mustCreateRun(t, service, scenario.ID, runInput)
		mustStartRun(t, service, run.ID)
		if _, err := service.CreateSimEvent(context.Background(), run.ID, SimEventInput{EventType: SimEventOther}); err != nil {
			t.Fatalf("category %q event_type 其他: %v", scenario.Category, err)
		}
	}

	// 不匹配的类型 → ValidationError；非法 event_type → ValidationError。
	run := mustCreateRun(t, service, scenarioA.ID, runInput)
	mustStartRun(t, service, run.ID)
	for name, eventType := range map[string]SimEventType{
		"fire on flow":  SimEventSmokeAlarm,
		"power on flow": SimEventPowerAlarm,
		"invalid type":  "不存在的类型",
	} {
		_, err := service.CreateSimEvent(context.Background(), run.ID, SimEventInput{EventType: eventType})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}
}

// 创建赋值：id 服务端生成、triggered_at 非空；显式 status=已处置 时同时
// 设置 handled_at；缺省 status 已触发 时 handled_at 为 nil；payload 原样
// 透传。
func TestCreateSimEventAssignsServerFields(t *testing.T) {
	service, clock := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)

	event, err := service.CreateSimEvent(context.Background(), run.ID, SimEventInput{
		EventType: SimEventFlowOverflow,
		Payload:   map[string]any{"count": 120},
		CreatedBy: "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateSimEvent: %v", err)
	}
	if event.ID == "" || event.RunID != run.ID {
		t.Fatalf("id/run_id = %q / %q, want server-generated id and the run", event.ID, event.RunID)
	}
	if event.Status != SimEventTriggered {
		t.Fatalf("status = %q, want 已触发 (default)", event.Status)
	}
	if event.TriggeredAt == nil || event.TriggeredAt.After(clock.current) {
		t.Fatalf("triggered_at = %v, want a service-set instant not after the current clock %v", event.TriggeredAt, clock.current)
	}
	if event.HandledAt != nil {
		t.Fatalf("handled_at = %v, want nil for 已触发", event.HandledAt)
	}
	if len(event.Payload) != 1 || event.Payload["count"] != 120 {
		t.Fatalf("payload = %v, want the request payload echoed", event.Payload)
	}

	// 显式 status=已处置：handled_at 同时被服务端设置。
	handled, err := service.CreateSimEvent(context.Background(), run.ID, SimEventInput{
		EventType: SimEventFlowOverflow,
		Status:    SimEventHandled,
	})
	if err != nil {
		t.Fatalf("CreateSimEvent (已处置): %v", err)
	}
	if handled.Status != SimEventHandled || handled.HandledAt == nil {
		t.Fatalf("handled event = %+v, want status 已处置 with handled_at set", handled)
	}
}

// ─── ListSimEvents ───────────────────────────────────────────────────

// 列表按 created_at ASC 排序；event_type/status 筛选生效；meta.total 为
// 筛选后的总数；limit/offset 分页生效；空结果返回空切片。
func TestListSimEventsSortedFilteredAndPaginated(t *testing.T) {
	service, clock := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)

	first := mustCreateSimEvent(t, service, run.ID, SimEventInput{
		EventType: SimEventFlowOverflow, Payload: map[string]any{"count": 100},
	})
	mustCreateSimEvent(t, service, run.ID, SimEventInput{
		EventType: SimEventFlowOverflow, Payload: map[string]any{"count": 150}, Status: SimEventHandled,
	})
	mustCreateSimEvent(t, service, run.ID, SimEventInput{EventType: SimEventOther, Payload: map[string]any{"note": "临时演练"}})
	_ = clock // the deterministic clock guarantees strictly increasing created_at

	// 全部：created_at ASC，total 3。
	records, total, err := service.ListSimEvents(context.Background(), run.ID, SimEventFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListSimEvents: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(records), total)
	}
	if records[0].ID != first.ID {
		t.Fatalf("records[0].id = %q, want %q (created_at ASC)", records[0].ID, first.ID)
	}
	for i := 1; i < len(records); i++ {
		if !records[i].CreatedAt.After(records[i-1].CreatedAt) {
			t.Fatalf("records not in created_at ASC order: %v", records)
		}
	}

	// event_type 筛选。
	records, total, err = service.ListSimEvents(context.Background(), run.ID, SimEventFilter{EventType: SimEventFlowOverflow, Limit: 50})
	if err != nil {
		t.Fatalf("ListSimEvents (event_type): %v", err)
	}
	if total != 2 || len(records) != 2 {
		t.Fatalf("by event_type: records = %d, total = %d; want 2 / 2", len(records), total)
	}
	for _, record := range records {
		if record.EventType != SimEventFlowOverflow {
			t.Fatalf("by event_type: record %q has event_type %q", record.ID, record.EventType)
		}
	}

	// status 筛选。
	records, total, err = service.ListSimEvents(context.Background(), run.ID, SimEventFilter{Status: SimEventHandled, Limit: 50})
	if err != nil {
		t.Fatalf("ListSimEvents (status): %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].Status != SimEventHandled {
		t.Fatalf("by status: records = %d, total = %d; want 1 / 1 已处置", len(records), total)
	}

	// event_type + status 组合筛选。
	records, total, err = service.ListSimEvents(context.Background(), run.ID, SimEventFilter{
		EventType: SimEventFlowOverflow, Status: SimEventHandled, Limit: 50,
	})
	if err != nil {
		t.Fatalf("ListSimEvents (combined): %v", err)
	}
	if total != 1 || len(records) != 1 {
		t.Fatalf("combined: records = %d, total = %d; want 1 / 1", len(records), total)
	}

	// 分页：limit=1&offset=1 取第二条，total 保持 3。
	records, total, err = service.ListSimEvents(context.Background(), run.ID, SimEventFilter{Limit: 1, Offset: 1})
	if err != nil {
		t.Fatalf("ListSimEvents (paginated): %v", err)
	}
	if total != 3 || len(records) != 1 || records[0].ID == first.ID {
		t.Fatalf("limit=1 offset=1: records = %d, total = %d, id = %q; want 1 / 3 and not the first",
			len(records), total, func() string {
				if len(records) == 0 {
					return ""
				}
				return records[0].ID
			}())
	}

	// 无匹配：空切片 + total 0（非 nil）。
	records, total, err = service.ListSimEvents(context.Background(), run.ID, SimEventFilter{EventType: SimEventWeatherWarning, Limit: 50})
	if err != nil {
		t.Fatalf("ListSimEvents (no match): %v", err)
	}
	if records == nil || len(records) != 0 || total != 0 {
		t.Fatalf("no match: records = %v, total = %d; want [] / 0", records, total)
	}

	// run 不存在 → ErrRunNotFound。
	_, _, err = service.ListSimEvents(context.Background(), "run-missing", SimEventFilter{Limit: 50})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// 其他 run 的事件互不可见。
	otherRun := mustCreateRun(t, service, scenario.ID, RunInput{Title: "另一场演练"})
	mustStartRun(t, service, otherRun.ID)
	records, total, err = service.ListSimEvents(context.Background(), otherRun.ID, SimEventFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListSimEvents (other run): %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("other run: records = %d, total = %d; want 0 / 0", len(records), total)
	}
}

// ─── GetSimEvent / UpdateSimEvent / DeleteSimEvent ───────────────────

// GetSimEvent 返回所属 run 内的事件；事件不存在或不属于该 run →
// ErrSimEventNotFound。
func TestGetSimEventScopedToRun(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, testScenarioInput)
	runA := mustCreateRun(t, service, scenario.ID, RunInput{Title: "A"})
	mustStartRun(t, service, runA.ID)
	runB := mustCreateRun(t, service, scenario.ID, RunInput{Title: "B"})
	mustStartRun(t, service, runB.ID)
	event := mustCreateSimEvent(t, service, runA.ID, SimEventInput{EventType: SimEventFlowOverflow})

	if got, err := service.GetSimEvent(context.Background(), runA.ID, event.ID); err != nil || got.ID != event.ID {
		t.Fatalf("GetSimEvent = %+v, %v; want the event", got, err)
	}
	if _, err := service.GetSimEvent(context.Background(), runA.ID, "event-missing"); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("missing id: err = %v, want ErrSimEventNotFound", err)
	}
	if _, err := service.GetSimEvent(context.Background(), runB.ID, event.ID); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("event of another run: err = %v, want ErrSimEventNotFound", err)
	}
}

// 部分更新语义：省略字段保留原值（payload 省略保留、event_type 变更须通过
// 分类映射）、status→已处置 设置 handled_at（已有时不覆盖）、改回已触发
// 置 null、triggered_at/created_at 保持不变、updated_at 刷新、run 须
// 进行中。
func TestUpdateSimEventPartialUpdate(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, testScenarioInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)
	event := mustCreateSimEvent(t, service, run.ID, SimEventInput{
		EventType: SimEventFlowOverflow, Payload: map[string]any{"count": 120},
	})
	createdAt := event.CreatedAt
	triggeredAt := *event.TriggeredAt

	// 只更新 status→已处置：handled_at 设置；payload/event_type 保留；
	// triggered_at/created_at 不变；updated_at 刷新。
	updated, err := service.UpdateSimEvent(context.Background(), run.ID, event.ID, SimEventUpdate{Status: SimEventHandled})
	if err != nil {
		t.Fatalf("UpdateSimEvent (已处置): %v", err)
	}
	if updated.Status != SimEventHandled || updated.HandledAt == nil {
		t.Fatalf("updated = %+v, want status 已处置 with handled_at", updated)
	}
	if updated.HandledAt.After(updated.UpdatedAt) || updated.HandledAt.Before(updated.CreatedAt) {
		t.Fatalf("handled_at = %v must lie between created_at %v and updated_at %v", updated.HandledAt, updated.CreatedAt, updated.UpdatedAt)
	}
	if updated.EventType != SimEventFlowOverflow || len(updated.Payload) != 1 || updated.Payload["count"] != 120 {
		t.Fatalf("omitted fields must keep their values: %+v", updated)
	}
	if !updated.TriggeredAt.Equal(triggeredAt) || !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("triggered_at/created_at must be preserved: %+v", updated)
	}
	if !updated.UpdatedAt.After(updated.CreatedAt) {
		t.Fatalf("updated_at = %v must be refreshed after created_at %v", updated.UpdatedAt, updated.CreatedAt)
	}

	// 再次置 已处置：handled_at 已有时不覆盖。
	firstHandledAt := *updated.HandledAt
	updated, err = service.UpdateSimEvent(context.Background(), run.ID, event.ID, SimEventUpdate{Status: SimEventHandled})
	if err != nil {
		t.Fatalf("UpdateSimEvent (已处置 twice): %v", err)
	}
	if updated.HandledAt == nil || !updated.HandledAt.Equal(firstHandledAt) {
		t.Fatalf("handled_at must not be overwritten when already set: %+v", updated)
	}

	// 改回 已触发：handled_at 置 null。
	updated, err = service.UpdateSimEvent(context.Background(), run.ID, event.ID, SimEventUpdate{Status: SimEventTriggered})
	if err != nil {
		t.Fatalf("UpdateSimEvent (已触发): %v", err)
	}
	if updated.Status != SimEventTriggered || updated.HandledAt != nil {
		t.Fatalf("updated = %+v, want status 已触发 with nil handled_at", updated)
	}

	// 更新 payload（省略保留已在上文断言；显式更新替换原值）。
	updated, err = service.UpdateSimEvent(context.Background(), run.ID, event.ID, SimEventUpdate{
		Payload: map[string]any{"count": 300}, HasPayload: true,
	})
	if err != nil {
		t.Fatalf("UpdateSimEvent (payload): %v", err)
	}
	if len(updated.Payload) != 1 || updated.Payload["count"] != 300 {
		t.Fatalf("payload = %v, want the new payload", updated.Payload)
	}

	// 变更 event_type（同类另一事件类型不存在，改到其他 合法）。
	updated, err = service.UpdateSimEvent(context.Background(), run.ID, event.ID, SimEventUpdate{EventType: SimEventOther})
	if err != nil {
		t.Fatalf("UpdateSimEvent (event_type): %v", err)
	}
	if updated.EventType != SimEventOther {
		t.Fatalf("event_type = %q, want 其他", updated.EventType)
	}

	// 失败路径：非法 event_type / 非法 status / 分类不匹配 → ValidationError。
	for name, update := range map[string]SimEventUpdate{
		"invalid event_type": {EventType: "不存在的类型"},
		"invalid status":     {Status: "草稿"},
		"category mismatch":  {EventType: SimEventSmokeAlarm},
	} {
		_, err := service.UpdateSimEvent(context.Background(), run.ID, event.ID, update)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}

	// 事件不存在 / 事件不属于该 run → ErrSimEventNotFound；run 不存在 →
	// ErrRunNotFound（run 此时仍 进行中，状态检查先行通过）。
	otherRun := mustCreateRun(t, service, scenario.ID, RunInput{Title: "B"})
	mustStartRun(t, service, otherRun.ID)
	if _, err := service.UpdateSimEvent(context.Background(), otherRun.ID, event.ID, SimEventUpdate{Status: SimEventHandled}); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("event of another run: err = %v, want ErrSimEventNotFound", err)
	}
	if _, err := service.UpdateSimEvent(context.Background(), run.ID, "event-missing", SimEventUpdate{Status: SimEventHandled}); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("missing event: err = %v, want ErrSimEventNotFound", err)
	}
	if _, err := service.UpdateSimEvent(context.Background(), "run-missing", event.ID, SimEventUpdate{Status: SimEventHandled}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// run 须 进行中：已完成 run 上的更新 → ValidationError。
	if _, err := service.CompleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("CompleteRun: %v", err)
	}
	var validationError *ValidationError
	if _, err := service.UpdateSimEvent(context.Background(), run.ID, event.ID, SimEventUpdate{Status: SimEventHandled}); !errors.As(err, &validationError) {
		t.Fatalf("已完成 run: err = %v, want ValidationError", err)
	}
}

// DeleteSimEvent 删除后 GetSimEvent 404；仅 进行中 可删除；事件不属于该
// run / 不存在 → ErrSimEventNotFound；run 不存在 → ErrRunNotFound。
func TestDeleteSimEvent(t *testing.T) {
	service := NewService(NewInMemoryStore())
	scenario := mustCreateScenario(t, service, testScenarioInput)
	runA := mustCreateRun(t, service, scenario.ID, RunInput{Title: "A"})
	mustStartRun(t, service, runA.ID)
	runB := mustCreateRun(t, service, scenario.ID, RunInput{Title: "B"})
	mustStartRun(t, service, runB.ID)
	event := mustCreateSimEvent(t, service, runA.ID, SimEventInput{EventType: SimEventFlowOverflow})

	if err := service.DeleteSimEvent(context.Background(), runA.ID, event.ID); err != nil {
		t.Fatalf("DeleteSimEvent: %v", err)
	}
	if _, err := service.GetSimEvent(context.Background(), runA.ID, event.ID); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("event after delete: err = %v, want ErrSimEventNotFound", err)
	}
	if _, err := service.GetSimEvent(context.Background(), runB.ID, event.ID); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("other run event after delete: err = %v, want ErrSimEventNotFound", err)
	}

	// runB 的事件：属于 runB，删除时用 runA 的 id 不行 → ErrSimEventNotFound。
	eventB := mustCreateSimEvent(t, service, runB.ID, SimEventInput{EventType: SimEventFlowOverflow})
	if err := service.DeleteSimEvent(context.Background(), runA.ID, eventB.ID); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("delete other run's event: err = %v, want ErrSimEventNotFound", err)
	}
	if err := service.DeleteSimEvent(context.Background(), runB.ID, "event-missing"); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("delete missing event: err = %v, want ErrSimEventNotFound", err)
	}

	// run 须 进行中。
	notStarted := mustCreateRun(t, service, scenario.ID, RunInput{Title: "C"})
	var validationError *ValidationError
	if err := service.DeleteSimEvent(context.Background(), notStarted.ID, eventB.ID); !errors.As(err, &validationError) {
		t.Fatalf("未开始 run: err = %v, want ValidationError", err)
	}
	if err := service.DeleteSimEvent(context.Background(), "run-missing", eventB.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}
