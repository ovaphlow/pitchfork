// Service unit tests for the drill run business object (演练任务): the
// input validation and defaults of normalizeRun, create / get / update /
// delete / list through the service over the in-memory store, the run
// state machine (未开始 -> 进行中 -> 已完成/已终止) with its legal and
// illegal transitions, and the cascade deletion of the step records,
// sim events and assessments when the run child cleaner is wired. The
// tests never touch a database; the service clock and id generator are
// injected so ordering and timestamps are deterministic.
package drills

import (
	"context"
	"errors"
	"testing"
	"time"
)

// runInput is a valid run input for the service-level tests; it
// references the scenario created by mustCreateScenario with
// testScenarioInput (category 大客流聚集).
var runInput = RunInput{ScenarioID: "placeholder", Title: "大客流疏散演练执行"}

// mustCreateRun creates a run within the given scenario, failing the
// test on error.
func mustCreateRun(t *testing.T, service *Service, scenarioID string, input RunInput) Run {
	t.Helper()
	input.ScenarioID = scenarioID
	run, err := service.CreateRun(context.Background(), input)
	if err != nil {
		t.Fatalf("CreateRun: %v", err)
	}
	return run
}

// mustStartRun moves the run into 进行中, failing the test on error.
func mustStartRun(t *testing.T, service *Service, runID string) Run {
	t.Helper()
	run, err := service.StartRun(context.Background(), runID)
	if err != nil {
		t.Fatalf("StartRun: %v", err)
	}
	return run
}

// ─── normalizeRun ────────────────────────────────────────────────────

// 缺 scenario_id / title（含空白）→ ValidationError。
func TestNormalizeRunRejectsInvalidInput(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	cases := []struct {
		name  string
		input RunInput
	}{
		{"missing scenario_id", RunInput{Title: "演练"}},
		{"blank scenario_id", RunInput{ScenarioID: "  ", Title: "演练"}},
		{"missing title", RunInput{ScenarioID: "scenario-001"}},
		{"blank title", RunInput{ScenarioID: "scenario-001", Title: " \t"}},
	}
	for _, testCase := range cases {
		_, err := normalizeRun(testCase.input, now, "run-001")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}
}

// 缺省值：status 未开始、started_at/completed_at 为 nil、metadata {}；
// created_by 透传。
func TestNormalizeRunDefaultsAndPassthrough(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	run, err := normalizeRun(RunInput{ScenarioID: "scenario-001", Title: "演练", CreatedBy: "u-admin"}, now, "run-001")
	if err != nil {
		t.Fatalf("normalizeRun: %v", err)
	}
	if run.ID != "run-001" || run.ScenarioID != "scenario-001" || run.Title != "演练" {
		t.Fatalf("normalizeRun does not echo the input: %+v", run)
	}
	if run.Status != DefaultRunStatus {
		t.Fatalf("status = %q, want %q (default)", run.Status, DefaultRunStatus)
	}
	if run.StartedAt != nil || run.CompletedAt != nil {
		t.Fatalf("started_at/completed_at = %v / %v, want nil", run.StartedAt, run.CompletedAt)
	}
	if len(run.Metadata) != 0 {
		t.Fatalf("metadata = %v, want an empty object when omitted", run.Metadata)
	}
	if run.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", run.CreatedBy)
	}
	if !run.CreatedAt.Equal(now) || !run.UpdatedAt.Equal(now) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", run.CreatedAt, run.UpdatedAt, now)
	}
}

// ─── CreateRun ───────────────────────────────────────────────────────

// 合法创建：服务端生成 id、status 未开始、时间戳赋值、metadata/created_by
// 回显。
func TestCreateRunAssignsULIDAndDefaults(t *testing.T) {
	service, clock := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)

	run, err := service.CreateRun(context.Background(), RunInput{
		ScenarioID: scenario.ID,
		Title:      "大客流疏散演练执行",
		Metadata:   map[string]any{"source": "merit"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateRun: %v", err)
	}
	if run.ID == "" || run.ScenarioID != scenario.ID || run.Title != "大客流疏散演练执行" {
		t.Fatalf("create does not echo the input: %+v", run)
	}
	if run.Status != RunStatusNotStarted {
		t.Fatalf("status = %q, want 未开始", run.Status)
	}
	if run.StartedAt != nil || run.CompletedAt != nil {
		t.Fatalf("started_at/completed_at = %v / %v, want nil", run.StartedAt, run.CompletedAt)
	}
	if len(run.Metadata) != 1 || run.Metadata["source"] != "merit" {
		t.Fatalf("metadata = %v, want the request metadata echoed", run.Metadata)
	}
	if run.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", run.CreatedBy)
	}
	if !run.CreatedAt.Equal(clock.current.Add(-time.Second)) || !run.UpdatedAt.Equal(run.CreatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want the clock time, equal", run.CreatedAt, run.UpdatedAt)
	}

	// GET 复验：store 中的记录与创建响应一致。
	fetched, err := service.GetRun(context.Background(), run.ID)
	if err != nil {
		t.Fatalf("GetRun: %v", err)
	}
	if fetched.ID != run.ID || fetched.Status != RunStatusNotStarted || fetched.Title != "大客流疏散演练执行" {
		t.Fatalf("GetRun = %+v, want the created run", fetched)
	}
}

// scenario_id 指向不存在的场景 → ErrScenarioNotFound（404 口径）。
func TestCreateRunScenarioNotFound(t *testing.T) {
	service, _ := testService()
	_, err := service.CreateRun(context.Background(), RunInput{ScenarioID: "scenario-missing", Title: "演练"})
	if !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("err = %v, want ErrScenarioNotFound", err)
	}
}

// ─── GetRun ──────────────────────────────────────────────────────────

// 存在的 id 返回记录；不存在的 id 返回 ErrRunNotFound。
func TestGetRun(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	created := mustCreateRun(t, service, scenario.ID, runInput)

	fetched, err := service.GetRun(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("GetRun: %v", err)
	}
	if fetched.ID != created.ID || fetched.ScenarioID != scenario.ID || fetched.Title != created.Title {
		t.Fatalf("GetRun = %+v, want the created run", fetched)
	}

	_, err = service.GetRun(context.Background(), "run-missing")
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrRunNotFound", err)
	}
}

// ─── UpdateRun ───────────────────────────────────────────────────────

// PUT 整体替换：title/metadata 更新可见，status/started_at/completed_at
// 服务端管理保持不变，created_at 保留、updated_at 刷新；PUT 后 GET 反映
// 更新；校验口径与 POST 一致。
func TestUpdateRunReplacesAndPreservesServerManagedFields(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	created := mustCreateRun(t, service, scenario.ID, RunInput{Title: "第一版", Metadata: map[string]any{"source": "merit"}})
	started := mustStartRun(t, service, created.ID)
	if started.StartedAt == nil {
		t.Fatalf("started_at must be set after start")
	}
	createdAt := started.CreatedAt

	updated, err := service.UpdateRun(context.Background(), created.ID, RunInput{
		ScenarioID: scenario.ID,
		Title:      "第二版",
		Metadata:   map[string]any{"source": "merit", "level": "2"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("UpdateRun: %v", err)
	}
	if updated.ID != created.ID || updated.Title != "第二版" || updated.Metadata["level"] != "2" ||
		updated.CreatedBy != "u-admin" {
		t.Fatalf("UpdateRun does not apply the input: %+v", updated)
	}
	// status 与 started_at/completed_at 服务端管理：PUT 不可改变。
	if updated.Status != RunStatusInProgress {
		t.Fatalf("status = %q, want 进行中 (server-managed)", updated.Status)
	}
	if updated.StartedAt == nil || !updated.StartedAt.Equal(*started.StartedAt) {
		t.Fatalf("started_at changed on update: %v -> %v", *started.StartedAt, updated.StartedAt)
	}
	if updated.CompletedAt != nil {
		t.Fatalf("completed_at = %v, want nil (server-managed)", updated.CompletedAt)
	}
	if !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at %v must be refreshed on update", updated.UpdatedAt)
	}

	// PUT 后 GET 反映更新。
	fetched, err := service.GetRun(context.Background(), created.ID)
	if err != nil {
		t.Fatalf("GetRun after update: %v", err)
	}
	if fetched.Title != "第二版" || fetched.Metadata["level"] != "2" || fetched.Status != RunStatusInProgress {
		t.Fatalf("GetRun after update = %+v, want the updated values", fetched)
	}

	// PUT 缺省字段仍应用缺省值：metadata 回 {}。
	updated, err = service.UpdateRun(context.Background(), created.ID, RunInput{ScenarioID: scenario.ID, Title: "第三版"})
	if err != nil {
		t.Fatalf("UpdateRun defaults: %v", err)
	}
	if len(updated.Metadata) != 0 {
		t.Fatalf("defaults: metadata = %v, want an empty object", updated.Metadata)
	}

	// PUT 校验与 POST 一致：缺 scenario_id/title 400、scenario 不存在 404。
	_, err = service.UpdateRun(context.Background(), created.ID, RunInput{Title: "缺 scenario_id"})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("missing scenario_id: err = %v, want ValidationError", err)
	}
	_, err = service.UpdateRun(context.Background(), created.ID, RunInput{ScenarioID: scenario.ID})
	if !errors.As(err, &validationError) {
		t.Fatalf("missing title: err = %v, want ValidationError", err)
	}
	_, err = service.UpdateRun(context.Background(), created.ID, RunInput{ScenarioID: "scenario-missing", Title: "演练"})
	if !errors.Is(err, ErrScenarioNotFound) {
		t.Fatalf("missing scenario: err = %v, want ErrScenarioNotFound", err)
	}

	// PUT 不存在的 id → ErrRunNotFound。
	_, err = service.UpdateRun(context.Background(), "run-missing", RunInput{ScenarioID: scenario.ID, Title: "演练"})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrRunNotFound", err)
	}
}

// ─── ListRuns ────────────────────────────────────────────────────────

// 空列表返回空切片与 total 0。
func TestListRunsEmpty(t *testing.T) {
	service, _ := testService()
	runs, total, err := service.ListRuns(context.Background(), RunFilter{})
	if err != nil {
		t.Fatalf("ListRuns: %v", err)
	}
	if len(runs) != 0 || total != 0 {
		t.Fatalf("runs = %d, total = %d; want 0 / 0", len(runs), total)
	}
}

// status / scenario_id 筛选生效；排序 created_at DESC（后创建的在前）。
func TestListRunsFilterAndSort(t *testing.T) {
	service, _ := testService()
	scenarioA := mustCreateScenario(t, service, testScenarioInput)
	scenarioB := mustCreateScenario(t, service, ScenarioInput{Name: "停电应急演练", Category: CategoryPowerOutage, Background: "市电中断"})

	mustCreateRun(t, service, scenarioA.ID, RunInput{Title: "A-1"})
	mustCreateRun(t, service, scenarioB.ID, RunInput{Title: "B-1"})
	runA2 := mustCreateRun(t, service, scenarioA.ID, RunInput{Title: "A-2"})
	mustCreateRun(t, service, scenarioA.ID, RunInput{Title: "A-3"})
	mustStartRun(t, service, runA2.ID)
	mustCompleteRun(t, service, runA2.ID)

	cases := []struct {
		name   string
		filter RunFilter
		total  int
		titles []string
	}{
		{"no filter", RunFilter{Limit: 50}, 4, []string{"A-3", "A-2", "B-1", "A-1"}},
		{"by status", RunFilter{Status: RunStatusCompleted, Limit: 50}, 1, []string{"A-2"}},
		{"by status not started", RunFilter{Status: RunStatusNotStarted, Limit: 50}, 3, []string{"A-3", "B-1", "A-1"}},
		{"by scenario", RunFilter{ScenarioID: scenarioB.ID, Limit: 50}, 1, []string{"B-1"}},
		{"scenario+status", RunFilter{Status: RunStatusNotStarted, ScenarioID: scenarioA.ID, Limit: 50}, 2, []string{"A-3", "A-1"}},
		{"no match", RunFilter{Status: RunStatusTerminated, Limit: 50}, 0, nil},
	}
	for _, testCase := range cases {
		runs, total, err := service.ListRuns(context.Background(), testCase.filter)
		if err != nil {
			t.Fatalf("%s: ListRuns: %v", testCase.name, err)
		}
		if total != testCase.total {
			t.Fatalf("%s: total = %d, want %d", testCase.name, total, testCase.total)
		}
		if len(runs) != len(testCase.titles) {
			t.Fatalf("%s: runs = %d, want %d", testCase.name, len(runs), len(testCase.titles))
		}
		for i, title := range testCase.titles {
			if runs[i].Title != title {
				t.Fatalf("%s: runs[%d].title = %q, want %q (created_at DESC)", testCase.name, i, runs[i].Title, title)
			}
		}
	}
}

// limit/offset 分页生效；total 保持筛选后的总数。
func TestListRunsPagination(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	for i := 1; i <= 53; i++ {
		mustCreateRun(t, service, scenario.ID, RunInput{Title: "演练"})
	}

	runs, total, err := service.ListRuns(context.Background(), RunFilter{Limit: 2, Offset: 0})
	if err != nil {
		t.Fatalf("ListRuns: %v", err)
	}
	if len(runs) != 2 || total != 53 {
		t.Fatalf("limit=2 offset=0: runs = %d, total = %d; want 2 / 53", len(runs), total)
	}

	runs, total, err = service.ListRuns(context.Background(), RunFilter{Limit: 2, Offset: 52})
	if err != nil {
		t.Fatalf("ListRuns: %v", err)
	}
	if len(runs) != 1 || total != 53 {
		t.Fatalf("limit=2 offset=52: runs = %d, total = %d; want 1 / 53", len(runs), total)
	}
}

// ─── 状态机 ──────────────────────────────────────────────────────────

// 合法迁移：未开始 -> start -> 进行中（started_at 非空、completed_at 保持
// null）；进行中 -> complete -> 已完成（completed_at 非空）；进行中 ->
// terminate -> 已终止（completed_at 保持 null）。每次迁移后 GET 复验新状态
// 与时间戳。
func TestRunStateMachineLegalTransitions(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)

	// start
	started := mustStartRun(t, service, run.ID)
	if started.Status != RunStatusInProgress {
		t.Fatalf("start: status = %q, want 进行中", started.Status)
	}
	if started.StartedAt == nil {
		t.Fatalf("start: started_at must be set")
	}
	if started.CompletedAt != nil {
		t.Fatalf("start: completed_at = %v, want nil", started.CompletedAt)
	}
	fetched, err := service.GetRun(context.Background(), run.ID)
	if err != nil {
		t.Fatalf("GetRun after start: %v", err)
	}
	if fetched.Status != RunStatusInProgress || fetched.StartedAt == nil || fetched.CompletedAt != nil {
		t.Fatalf("GetRun after start = %+v, want 进行中 with started_at set", fetched)
	}

	// complete
	completed, err := service.CompleteRun(context.Background(), run.ID)
	if err != nil {
		t.Fatalf("CompleteRun: %v", err)
	}
	if completed.Status != RunStatusCompleted {
		t.Fatalf("complete: status = %q, want 已完成", completed.Status)
	}
	if completed.CompletedAt == nil {
		t.Fatalf("complete: completed_at must be set")
	}
	if completed.StartedAt == nil || !completed.StartedAt.Equal(*started.StartedAt) {
		t.Fatalf("complete: started_at changed: %v -> %v", *started.StartedAt, completed.StartedAt)
	}
	fetched, err = service.GetRun(context.Background(), run.ID)
	if err != nil {
		t.Fatalf("GetRun after complete: %v", err)
	}
	if fetched.Status != RunStatusCompleted || fetched.CompletedAt == nil {
		t.Fatalf("GetRun after complete = %+v, want 已完成 with completed_at set", fetched)
	}

	// terminate 分支：新的 run 走 start -> terminate。
	run2 := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run2.ID)
	terminated, err := service.TerminateRun(context.Background(), run2.ID)
	if err != nil {
		t.Fatalf("TerminateRun: %v", err)
	}
	if terminated.Status != RunStatusTerminated {
		t.Fatalf("terminate: status = %q, want 已终止", terminated.Status)
	}
	if terminated.CompletedAt != nil {
		t.Fatalf("terminate: completed_at = %v, want nil (已终止不设 completed_at)", terminated.CompletedAt)
	}
	fetched, err = service.GetRun(context.Background(), run2.ID)
	if err != nil {
		t.Fatalf("GetRun after terminate: %v", err)
	}
	if fetched.Status != RunStatusTerminated || fetched.CompletedAt != nil {
		t.Fatalf("GetRun after terminate = %+v, want 已终止 with completed_at nil", fetched)
	}
}

// mustCompleteRun completes the run, failing the test on error.
func mustCompleteRun(t *testing.T, service *Service, runID string) Run {
	t.Helper()
	run, err := service.CompleteRun(context.Background(), runID)
	if err != nil {
		t.Fatalf("CompleteRun: %v", err)
	}
	return run
}

// 非法迁移一律 ValidationError：未开始直接 complete/terminate、已完成/已终止
// 再 start、已完成再 terminate、已终止再 complete 等；不存在 id 404。
func TestRunStateMachineIllegalTransitions(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)

	notStarted := mustCreateRun(t, service, scenario.ID, RunInput{Title: "未开始"})
	inProgress := mustCreateRun(t, service, scenario.ID, RunInput{Title: "进行中"})
	mustStartRun(t, service, inProgress.ID)
	completed := mustCreateRun(t, service, scenario.ID, RunInput{Title: "已完成"})
	mustStartRun(t, service, completed.ID)
	mustCompleteRun(t, service, completed.ID)
	terminated := mustCreateRun(t, service, scenario.ID, RunInput{Title: "已终止"})
	mustStartRun(t, service, terminated.ID)
	if _, err := service.TerminateRun(context.Background(), terminated.ID); err != nil {
		t.Fatalf("setup terminate: %v", err)
	}

	cases := []struct {
		name  string
		runID string
		apply func(context.Context, string) (Run, error)
	}{
		{"未开始 -> complete", notStarted.ID, service.CompleteRun},
		{"未开始 -> terminate", notStarted.ID, service.TerminateRun},
		{"进行中 -> start", inProgress.ID, service.StartRun},
		{"已完成 -> start", completed.ID, service.StartRun},
		{"已完成 -> complete", completed.ID, service.CompleteRun},
		{"已完成 -> terminate", completed.ID, service.TerminateRun},
		{"已终止 -> start", terminated.ID, service.StartRun},
		{"已终止 -> complete", terminated.ID, service.CompleteRun},
		{"已终止 -> terminate", terminated.ID, service.TerminateRun},
	}
	for _, testCase := range cases {
		_, err := testCase.apply(context.Background(), testCase.runID)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}

	// 不存在 id → ErrRunNotFound（三个入口一致）。
	for name, apply := range map[string]func(context.Context, string) (Run, error){
		"start":     service.StartRun,
		"complete":  service.CompleteRun,
		"terminate": service.TerminateRun,
	} {
		_, err := apply(context.Background(), "run-missing")
		if !errors.Is(err, ErrRunNotFound) {
			t.Fatalf("%s unknown id: err = %v, want ErrRunNotFound", name, err)
		}
	}
}

// ─── DeleteRun 级联 ──────────────────────────────────────────────────

// 接线 cleaner 后 DeleteRun 级联清理该 run 的步骤记录/模拟事件/评估；其他
// run 的子记录保留；删除不存在 run 不触碰子记录。
func TestDeleteRunCascadesToChildren(t *testing.T) {
	store := NewInMemoryStore()
	service := NewService(store)
	service.SetRunChildCleaner(store)
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	point := mustCreatePoint(t, service, scenario.ID, pointInput)

	runA := mustCreateRun(t, service, scenario.ID, RunInput{Title: "A"})
	mustStartRun(t, service, runA.ID)
	// 步骤记录 / 模拟事件 / 评估各一条（run 进行中、事件类型匹配场景分类）。
	if _, err := service.UpsertStepRecord(context.Background(), runA.ID, step.ID, StepRecordInput{
		Status: StepRecordExecuted, ActionNote: "已完成广播",
	}); err != nil {
		t.Fatalf("UpsertStepRecord: %v", err)
	}
	event, err := service.CreateSimEvent(context.Background(), runA.ID, SimEventInput{
		EventType: SimEventFlowOverflow, Payload: map[string]any{"count": 120},
	})
	if err != nil {
		t.Fatalf("CreateSimEvent: %v", err)
	}
	if _, err := service.UpsertAssessment(context.Background(), runA.ID, point.ID, AssessmentInput{
		Score: 90, Comment: "指令传达准确",
	}); err != nil {
		t.Fatalf("UpsertAssessment: %v", err)
	}

	// runB 也有子记录，用于断言级联只清理 runA。
	runB := mustCreateRun(t, service, scenario.ID, RunInput{Title: "B"})
	mustStartRun(t, service, runB.ID)
	if _, err := service.UpsertStepRecord(context.Background(), runB.ID, step.ID, StepRecordInput{
		Status: StepRecordExecuted, ActionNote: "B 的记录",
	}); err != nil {
		t.Fatalf("UpsertStepRecord B: %v", err)
	}
	eventB, err := service.CreateSimEvent(context.Background(), runB.ID, SimEventInput{
		EventType: SimEventFlowOverflow, Payload: map[string]any{"count": 80},
	})
	if err != nil {
		t.Fatalf("CreateSimEvent B: %v", err)
	}
	if _, err := service.UpsertAssessment(context.Background(), runB.ID, point.ID, AssessmentInput{
		Score: 85, Comment: "B 的评估",
	}); err != nil {
		t.Fatalf("UpsertAssessment B: %v", err)
	}

	if err := service.DeleteRun(context.Background(), runA.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}

	// runA 自身与三个子集全部消失。
	if _, err := service.GetRun(context.Background(), runA.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("runA after delete: err = %v, want ErrRunNotFound", err)
	}
	if _, err := store.GetStepRecord(context.Background(), runA.ID, step.ID); !errors.Is(err, ErrStepRecordNotFound) {
		t.Fatalf("runA step record after cascade: err = %v, want ErrStepRecordNotFound", err)
	}
	if _, err := store.GetSimEvent(context.Background(), runA.ID, event.ID); !errors.Is(err, ErrSimEventNotFound) {
		t.Fatalf("runA sim event after cascade: err = %v, want ErrSimEventNotFound", err)
	}
	if _, err := store.GetAssessment(context.Background(), runA.ID, point.ID); !errors.Is(err, ErrAssessmentNotFound) {
		t.Fatalf("runA assessment after cascade: err = %v, want ErrAssessmentNotFound", err)
	}

	// runB 及其子记录保留。
	if _, err := service.GetRun(context.Background(), runB.ID); err != nil {
		t.Fatalf("runB must survive the cascade: %v", err)
	}
	if _, err := store.GetStepRecord(context.Background(), runB.ID, step.ID); err != nil {
		t.Fatalf("runB step record must survive the cascade: %v", err)
	}
	if _, err := store.GetSimEvent(context.Background(), runB.ID, eventB.ID); err != nil {
		t.Fatalf("runB sim event must survive the cascade: %v", err)
	}
	if _, err := store.GetAssessment(context.Background(), runB.ID, point.ID); err != nil {
		t.Fatalf("runB assessment must survive the cascade: %v", err)
	}

	// 删除不存在的 run：ErrRunNotFound，不触碰任何子记录。
	if err := service.DeleteRun(context.Background(), "run-missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("delete unknown run: err = %v, want ErrRunNotFound", err)
	}
	if _, err := store.GetStepRecord(context.Background(), runB.ID, step.ID); err != nil {
		t.Fatalf("runB step record must survive a missing-run delete: %v", err)
	}
}

// 未接线 cleaner 时 DeleteRun 只删除 run 本身，子记录保留。
func TestDeleteRunWithoutCleanerKeepsChildren(t *testing.T) {
	store := NewInMemoryStore()
	service := NewService(store)
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)
	if _, err := service.UpsertStepRecord(context.Background(), run.ID, step.ID, StepRecordInput{
		Status: StepRecordExecuted, ActionNote: "记录",
	}); err != nil {
		t.Fatalf("UpsertStepRecord: %v", err)
	}

	if err := service.DeleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}
	if _, err := store.GetStepRecord(context.Background(), run.ID, step.ID); err != nil {
		t.Fatalf("step record must survive without a cleaner, err = %v", err)
	}
}
