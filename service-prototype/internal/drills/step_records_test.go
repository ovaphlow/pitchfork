// Service unit tests for the drill step execution records (演练步骤执行
// 记录): the input validation and defaults of normalizeStepRecord, the
// idempotent (run, step) upsert through the service over the in-memory
// store (create / update in place / writable-run and step-ownership
// checks), the single-record query with its run/record 404 distinction,
// the sorted + paginated list, and the delete with its writable-run
// check. The tests never touch a database; the service clock and id
// generator are injected so ordering and timestamps are deterministic.
package drills

import (
	"context"
	"errors"
	"testing"
	"time"
)

// mustPutStepRecord upserts a step record with the given input, failing
// the test on error.
func mustPutStepRecord(t *testing.T, service *Service, runID, stepID string, input StepRecordInput) StepRecord {
	t.Helper()
	record, err := service.UpsertStepRecord(context.Background(), runID, stepID, input)
	if err != nil {
		t.Fatalf("UpsertStepRecord: %v", err)
	}
	return record
}

// ─── normalizeStepRecord ─────────────────────────────────────────────

// status 缺省 待执行；action_note / performed_by / performed_at /
// created_by 透传；非法 status → ValidationError。
func TestNormalizeStepRecordDefaultsAndPassthrough(t *testing.T) {
	now := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	performedAt := time.Date(2026, 8, 14, 10, 30, 0, 0, time.UTC)

	record, err := normalizeStepRecord("run-001", "step-001", StepRecordInput{
		ActionNote:  "已完成广播",
		PerformedBy: "u-admin",
		PerformedAt: &performedAt,
		CreatedBy:   "u-admin",
	}, now, "record-001")
	if err != nil {
		t.Fatalf("normalizeStepRecord: %v", err)
	}
	if record.Status != DefaultStepRecordStatus {
		t.Fatalf("status = %q, want %q (default)", record.Status, DefaultStepRecordStatus)
	}
	if record.ActionNote != "已完成广播" || record.PerformedBy != "u-admin" || record.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", record)
	}
	if record.PerformedAt == nil || !record.PerformedAt.Equal(performedAt) {
		t.Fatalf("performed_at = %v, want %v", record.PerformedAt, performedAt)
	}
	if record.RunID != "run-001" || record.StepID != "step-001" || record.ID != "record-001" ||
		!record.CreatedAt.Equal(now) || !record.UpdatedAt.Equal(now) {
		t.Fatalf("key fields / timestamps = %+v", record)
	}

	// performed_at 缺省 null。
	record, err = normalizeStepRecord("run-001", "step-001", StepRecordInput{}, now, "record-002")
	if err != nil {
		t.Fatalf("normalizeStepRecord defaults: %v", err)
	}
	if record.Status != DefaultStepRecordStatus || record.ActionNote != "" ||
		record.PerformedBy != "" || record.CreatedBy != "" || record.PerformedAt != nil {
		t.Fatalf("defaults = %+v, want 待执行 / '' / '' / '' / null", record)
	}

	if _, err := normalizeStepRecord("run-001", "step-001", StepRecordInput{Status: "草稿"}, now, "record-003"); err == nil {
		t.Fatal("invalid status must be rejected")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("err = %v, want a ValidationError", err)
		}
	}
}

// ─── UpsertStepRecord ────────────────────────────────────────────────

// 首次 PUT 创建（服务端 id、缺省 status 待执行、action_note/performed_by/
// performed_at/created_by 缺省值）；再次 PUT 原地更新：id/created_at 不变、
// updated_at 刷新、全量替换（省略字段按默认值重置）；随后 GetStepRecord 与
// ListStepRecords 反映更新。
func TestUpsertStepRecordCreatesAndUpdatesInPlace(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)

	created := mustPutStepRecord(t, service, run.ID, step.ID, StepRecordInput{
		Status: StepRecordExecuted, ActionNote: "第一版",
	})
	if created.ID == "" || created.RunID != run.ID || created.StepID != step.ID {
		t.Fatalf("created record = %+v", created)
	}
	if created.Status != StepRecordExecuted || created.ActionNote != "第一版" {
		t.Fatalf("created values = %+v", created)
	}
	if created.CreatedAt != created.UpdatedAt {
		t.Fatalf("created_at = %v, updated_at = %v; want equal", created.CreatedAt, created.UpdatedAt)
	}

	// 第二次 PUT：id/created_at 保留，updated_at 刷新，字段全量替换
	// （省略的 performed_by/performed_at/created_by 按默认值重置）。
	updated := mustPutStepRecord(t, service, run.ID, step.ID, StepRecordInput{
		Status: StepRecordSkipped, ActionNote: "第二版",
	})
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Fatalf("created_at %v changed to %v on update", created.CreatedAt, updated.CreatedAt)
	}
	if updated.UpdatedAt.Equal(created.UpdatedAt) {
		t.Fatalf("updated_at %v must be refreshed on update", updated.UpdatedAt)
	}
	if updated.Status != StepRecordSkipped || updated.ActionNote != "第二版" ||
		updated.PerformedBy != "" || updated.PerformedAt != nil || updated.CreatedBy != "" {
		t.Fatalf("updated record = %+v, want replaced values with defaults", updated)
	}

	fetched, err := service.GetStepRecord(context.Background(), run.ID, step.ID)
	if err != nil {
		t.Fatalf("GetStepRecord: %v", err)
	}
	if fetched.ID != created.ID || fetched.Status != StepRecordSkipped || fetched.ActionNote != "第二版" {
		t.Fatalf("GetStepRecord after update = %+v", fetched)
	}

	records, total, err := service.ListStepRecords(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListStepRecords: %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].ActionNote != "第二版" {
		t.Fatalf("list after update = %+v (total %d), want the updated record", records, total)
	}
}

// 失败路径：run 不存在 → ErrRunNotFound；step 不存在或不属于 run 场景 →
// ErrStepNotFound；run 非 进行中 → ValidationError；非法 status →
// ValidationError。
func TestUpsertStepRecordRejectsInvalidState(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	otherScenario := mustCreateScenario(t, service, ScenarioInput{
		Name: "停电应急演练", Category: CategoryPowerOutage, Background: "市电中断",
	})
	foreignStep := mustCreateStep(t, service, otherScenario.ID, stepInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)

	// run 不存在。
	if _, err := service.UpsertStepRecord(context.Background(), "run-missing", step.ID, StepRecordInput{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// step 不存在。
	mustStartRun(t, service, run.ID)
	if _, err := service.UpsertStepRecord(context.Background(), run.ID, "step-missing", StepRecordInput{}); !errors.Is(err, ErrStepNotFound) {
		t.Fatalf("missing step: err = %v, want ErrStepNotFound", err)
	}
	// step 属于其他场景。
	if _, err := service.UpsertStepRecord(context.Background(), run.ID, foreignStep.ID, StepRecordInput{}); !errors.Is(err, ErrStepNotFound) {
		t.Fatalf("foreign step: err = %v, want ErrStepNotFound", err)
	}
	// 非法 status。
	if _, err := service.UpsertStepRecord(context.Background(), run.ID, step.ID, StepRecordInput{Status: "草稿"}); err == nil {
		t.Fatal("invalid status must be rejected")
	}
	// run 非 进行中：新 run 未开始、已完成、已终止。
	notStarted := mustCreateRun(t, service, scenario.ID, RunInput{Title: "未开始"})
	if _, err := service.UpsertStepRecord(context.Background(), notStarted.ID, step.ID, StepRecordInput{}); err == nil {
		t.Fatal("upsert on a 未开始 run must be rejected")
	}
	completed := mustCreateRun(t, service, scenario.ID, RunInput{Title: "已完成"})
	mustStartRun(t, service, completed.ID)
	mustCompleteRun(t, service, completed.ID)
	if _, err := service.UpsertStepRecord(context.Background(), completed.ID, step.ID, StepRecordInput{}); err == nil {
		t.Fatal("upsert on a 已完成 run must be rejected")
	}
	terminated := mustCreateRun(t, service, scenario.ID, RunInput{Title: "已终止"})
	mustStartRun(t, service, terminated.ID)
	if _, err := service.TerminateRun(context.Background(), terminated.ID); err != nil {
		t.Fatalf("TerminateRun: %v", err)
	}
	if _, err := service.UpsertStepRecord(context.Background(), terminated.ID, step.ID, StepRecordInput{}); err == nil {
		t.Fatal("upsert on a 已终止 run must be rejected")
	}
}

// ─── GetStepRecord ───────────────────────────────────────────────────

// run 不存在 → ErrRunNotFound；run 存在但记录不存在 → ErrStepRecordNotFound
// （两类 404 可区分）；记录存在 → 返回完整记录。
func TestGetStepRecordDistinguishesRunAndRecord(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)

	if _, err := service.GetStepRecord(context.Background(), "run-missing", step.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.GetStepRecord(context.Background(), run.ID, step.ID); !errors.Is(err, ErrStepRecordNotFound) {
		t.Fatalf("missing record: err = %v, want ErrStepRecordNotFound", err)
	}

	mustStartRun(t, service, run.ID)
	created := mustPutStepRecord(t, service, run.ID, step.ID, StepRecordInput{Status: StepRecordExecuted})
	fetched, err := service.GetStepRecord(context.Background(), run.ID, step.ID)
	if err != nil {
		t.Fatalf("GetStepRecord: %v", err)
	}
	if fetched.ID != created.ID || fetched.Status != StepRecordExecuted {
		t.Fatalf("fetched = %+v, want the created record", fetched)
	}
}

// ─── ListStepRecords ─────────────────────────────────────────────────

// 空列表返回空切片与 total 0；created_at ASC 排序；created_at 相同时按 id
// ASC 决胜（固定时钟 + 逆序 id 注入，确定性验证）；limit/offset 分页生效且
// total 为分页前总数；run 不存在 → ErrRunNotFound。
func TestListStepRecordsSortedAndPaginated(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	stepA := mustCreateStep(t, service, scenario.ID, StepInput{SortOrder: 1, Title: "疏散广播"})
	stepB := mustCreateStep(t, service, scenario.ID, StepInput{SortOrder: 2, Title: "清点人数"})
	stepC := mustCreateStep(t, service, scenario.ID, StepInput{SortOrder: 3, Title: "现场评估"})
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)

	// 空列表。
	records, total, err := service.ListStepRecords(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListStepRecords empty: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("empty list: records = %v, total = %d; want [] / 0", records, total)
	}

	// 按 stepB → stepA → stepC 的顺序创建：期望 created_at ASC 为
	// stepB, stepA, stepC。
	mustPutStepRecord(t, service, run.ID, stepB.ID, StepRecordInput{ActionNote: "B"})
	mustPutStepRecord(t, service, run.ID, stepA.ID, StepRecordInput{ActionNote: "A"})
	mustPutStepRecord(t, service, run.ID, stepC.ID, StepRecordInput{ActionNote: "C"})

	records, total, err = service.ListStepRecords(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListStepRecords: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(records), total)
	}
	for i, want := range []string{"B", "A", "C"} {
		if records[i].ActionNote != want {
			t.Fatalf("records[%d].action_note = %q, want %q (created_at ASC)", i, records[i].ActionNote, want)
		}
	}

	// created_at 相同（固定时钟）时按 id ASC 决胜：两条记录注入与插入顺序
	// 相反的 id（先 PUT 得 record-b，后 PUT 得 record-a）。
	store := NewInMemoryStore()
	base := NewService(store)
	tieScenario := mustCreateScenario(t, base, testScenarioInput)
	tieStepA := mustCreateStep(t, base, tieScenario.ID, StepInput{Title: "甲"})
	tieStepB := mustCreateStep(t, base, tieScenario.ID, StepInput{Title: "乙"})
	tieRun := mustCreateRun(t, base, tieScenario.ID, runInput)
	mustStartRun(t, base, tieRun.ID)
	fixedClock := &testClock{
		current: time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC),
		step:    0,
	}
	sequence := 0
	tieService := &Service{
		store: store,
		now:   fixedClock.now,
		newID: func() string {
			sequence++
			if sequence == 1 {
				return "record-b"
			}
			return "record-a"
		},
	}
	mustPutStepRecord(t, tieService, tieRun.ID, tieStepA.ID, StepRecordInput{ActionNote: "后插入但 id 更小"})
	mustPutStepRecord(t, tieService, tieRun.ID, tieStepB.ID, StepRecordInput{ActionNote: "先插入但 id 更大"})
	tieRecords, _, err := tieService.ListStepRecords(context.Background(), tieRun.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListStepRecords tiebreak: %v", err)
	}
	if len(tieRecords) != 2 || tieRecords[0].ID != "record-a" || tieRecords[1].ID != "record-b" {
		t.Fatalf("tiebreak order = %q %q, want record-a record-b (id ASC)", tieRecords[0].ID, tieRecords[1].ID)
	}

	// 分页：limit=1&offset=1 取中间一条，total 保持 3。
	records, total, err = service.ListStepRecords(context.Background(), run.ID, ListFilter{Limit: 1, Offset: 1})
	if err != nil {
		t.Fatalf("ListStepRecords paginated: %v", err)
	}
	if total != 3 || len(records) != 1 || records[0].ActionNote != "A" {
		t.Fatalf("limit=1 offset=1: records = %v, total = %d; want [A] / 3", records, total)
	}

	// 其他 run 的记录不混入。
	otherRun := mustCreateRun(t, service, scenario.ID, RunInput{Title: "第二场"})
	mustStartRun(t, service, otherRun.ID)
	mustPutStepRecord(t, service, otherRun.ID, stepA.ID, StepRecordInput{ActionNote: "其他 run"})
	records, total, err = service.ListStepRecords(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListStepRecords scoped: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("run-scoped list: records = %d, total = %d; want 3 / 3", len(records), total)
	}

	// run 不存在。
	if _, _, err := service.ListStepRecords(context.Background(), "run-missing", ListFilter{Limit: 50}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}

// ─── DeleteStepRecord ────────────────────────────────────────────────

// 删除成功且随后 GetStepRecord 返回 ErrStepRecordNotFound、列表不含该记录；
// 记录不存在 → ErrStepRecordNotFound；run 非 进行中 → ValidationError。
func TestDeleteStepRecord(t *testing.T) {
	service, _ := testService()
	scenario := mustCreateScenario(t, service, testScenarioInput)
	step := mustCreateStep(t, service, scenario.ID, stepInput)
	run := mustCreateRun(t, service, scenario.ID, runInput)
	mustStartRun(t, service, run.ID)
	mustPutStepRecord(t, service, run.ID, step.ID, StepRecordInput{Status: StepRecordExecuted})

	if err := service.DeleteStepRecord(context.Background(), run.ID, step.ID); err != nil {
		t.Fatalf("DeleteStepRecord: %v", err)
	}
	if _, err := service.GetStepRecord(context.Background(), run.ID, step.ID); !errors.Is(err, ErrStepRecordNotFound) {
		t.Fatalf("record after delete: err = %v, want ErrStepRecordNotFound", err)
	}
	records, total, err := service.ListStepRecords(context.Background(), run.ID, ListFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListStepRecords after delete: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("list after delete: records = %v, total = %d; want [] / 0", records, total)
	}

	// 再次删除同一记录 → ErrStepRecordNotFound。
	if err := service.DeleteStepRecord(context.Background(), run.ID, step.ID); !errors.Is(err, ErrStepRecordNotFound) {
		t.Fatalf("delete again: err = %v, want ErrStepRecordNotFound", err)
	}

	// 非 进行中 run 的删除 → ValidationError。
	notStarted := mustCreateRun(t, service, scenario.ID, RunInput{Title: "未开始"})
	if err := service.DeleteStepRecord(context.Background(), notStarted.ID, step.ID); err == nil {
		t.Fatal("delete on a 未开始 run must be rejected")
	}
}
