package dispatch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ─── UpsertDepartment ────────────────────────────────────────────────

// 首次 PUT：完整对象，id 为 26 位 Crockford Base32 ULID，run_id 与
// department 回显，status 缺省 未响应、note 缺省空串、arrived_at 缺省
// null、created_by 缺省空串，created_at/updated_at 为服务端时间且相等。
func TestUpsertDepartmentCreatesWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	report, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment: %v", err)
	}
	if !crockford26.MatchString(report.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", report.ID)
	}
	if report.RunID != "run-1" {
		t.Fatalf("run_id = %q, want the run from the caller", report.RunID)
	}
	if report.Department != DepartmentFire {
		t.Fatalf("department = %q, want the value from the caller", report.Department)
	}
	if report.Status != DefaultDepartmentStatus {
		t.Fatalf("status = %q, want %q (default)", report.Status, DefaultDepartmentStatus)
	}
	if report.Note != "" {
		t.Fatalf("note = %q, want an empty default", report.Note)
	}
	if report.ArrivedAt != nil {
		t.Fatalf("arrived_at = %v, want null when omitted", report.ArrivedAt)
	}
	if report.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", report.CreatedBy)
	}
	if report.CreatedAt.IsZero() || !report.CreatedAt.Equal(report.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want server time and equal", report.CreatedAt, report.UpdatedAt)
	}
}

// 显式字段：status 未响应（创建起点）显式传入合法；note/arrived_at/
// created_by 透传。
func TestUpsertDepartmentExplicitFields(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	arrivedAt := fixedTime.Add(30 * time.Minute)
	report, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentPolice, DepartmentReportInput{
		Status:    DepartmentStatusNotResponded,
		Note:      "已通知，待出动",
		ArrivedAt: &arrivedAt,
		CreatedBy: "u-commander",
	})
	if err != nil {
		t.Fatalf("UpsertDepartment: %v", err)
	}
	if report.Status != DepartmentStatusNotResponded || report.Note != "已通知，待出动" ||
		report.CreatedBy != "u-commander" {
		t.Fatalf("report = %+v, want the explicit fields echoed", report)
	}
	if report.ArrivedAt == nil || !report.ArrivedAt.Equal(arrivedAt) {
		t.Fatalf("arrived_at = %v, want %v", report.ArrivedAt, arrivedAt)
	}
}

// 首次创建显式 status 非 未响应 → ValidationError（跳级）；非法
// department/status 枚举 → ValidationError。
func TestUpsertDepartmentInvalidEnumAndCreationStatus(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	cases := []struct {
		name       string
		department Department
		status     DepartmentStatus
	}{
		{"invalid department", Department("武警"), ""},
		{"invalid status", DepartmentFire, DepartmentStatus("待命")},
		{"status 已响应 at creation", DepartmentFire, DepartmentStatusResponded},
		{"status 已到位 at creation", DepartmentFire, DepartmentStatusArrived},
		{"status 处置中 at creation", DepartmentFire, DepartmentStatusHandling},
		{"status 已完成 at creation", DepartmentFire, DepartmentStatusCompleted},
	}
	for _, testCase := range cases {
		_, err := service.UpsertDepartment(context.Background(), "run-1", testCase.department, DepartmentReportInput{
			Status: testCase.status,
		})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}
}

// 再次 PUT 原地更新：id/created_at 保留、updated_at 刷新；status 省略时
// 保持当前值不重置；note/arrived_at/created_by 全量替换（省略重置缺省）。
func TestUpsertDepartmentUpdatesInPlaceKeepsStatus(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	created, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{
		Note:      "初始说明",
		CreatedBy: "u-commander",
	})
	if err != nil {
		t.Fatalf("first UpsertDepartment: %v", err)
	}
	createdAt := created.CreatedAt
	arrivedAt := fixedTime.Add(time.Hour)
	time.Sleep(5 * time.Millisecond)

	// 推进到 已响应 并携带 note/arrived_at。
	advanced, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{
		Status:    DepartmentStatusResponded,
		Note:      "已出动",
		ArrivedAt: &arrivedAt,
		CreatedBy: "u-commander",
	})
	if err != nil {
		t.Fatalf("advance UpsertDepartment: %v", err)
	}
	if advanced.ID != created.ID || !advanced.CreatedAt.Equal(createdAt) {
		t.Fatalf("id/created_at must be preserved: %+v", advanced)
	}
	if advanced.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at %v must be refreshed on update", advanced.UpdatedAt)
	}
	if advanced.Status != DepartmentStatusResponded || advanced.Note != "已出动" {
		t.Fatalf("advance fields = %+v, want 已响应/已出动", advanced)
	}

	time.Sleep(5 * time.Millisecond)

	// status 省略 → 保持 已响应；note/arrived_at 省略 → 重置缺省。
	updated, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("second UpsertDepartment: %v", err)
	}
	if updated.ID != created.ID || !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("id/created_at must survive the update: %+v", updated)
	}
	if updated.Status != DepartmentStatusResponded {
		t.Fatalf("omitted status must keep its value, got %q", updated.Status)
	}
	if updated.Note != "" || updated.ArrivedAt != nil || updated.CreatedBy != "" {
		t.Fatalf("omitted note/arrived_at/created_by must reset: %+v", updated)
	}
	if updated.UpdatedAt.Equal(advanced.UpdatedAt) {
		t.Fatalf("updated_at must be refreshed on every PUT")
	}
}

// 状态机：仅相邻正向迁移合法（未响应→已响应→已到位→处置中→已完成），
// 同级 no-op 合法；跳级、回退（含 已完成 改回）→ ValidationError。
func TestUpsertDepartmentStateMachine(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	steps := []DepartmentStatus{
		DepartmentStatusResponded,
		DepartmentStatusArrived,
		DepartmentStatusHandling,
		DepartmentStatusCompleted,
	}
	report, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	for _, step := range steps {
		report, err = service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{
			Status: step,
		})
		if err != nil {
			t.Fatalf("transition to %q: %v", step, err)
		}
		if report.Status != step {
			t.Fatalf("status = %q, want %q", report.Status, step)
		}
	}
	// 同级 no-op 合法。
	report, err = service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{
		Status: DepartmentStatusCompleted,
	})
	if err != nil || report.Status != DepartmentStatusCompleted {
		t.Fatalf("same-status no-op: report = %+v, err = %v; want 已完成 kept", report, err)
	}

	// 非法迁移：跳级与回退。消防停在 已响应，场馆应急组停在 未响应，
	// 卫健停在 已完成，分别验证三类非法迁移。
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{}); err != nil {
		t.Fatalf("create fire: %v", err)
	}
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{
		Status: DepartmentStatusResponded,
	}); err != nil {
		t.Fatalf("advance fire to 已响应: %v", err)
	}
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentVenue, DepartmentReportInput{}); err != nil {
		t.Fatalf("create venue: %v", err)
	}
	for name, target := range map[string]DepartmentStatus{
		"未响应 -> 已到位 (skip)": DepartmentStatusArrived,
		"未响应 -> 处置中 (skip)": DepartmentStatusHandling,
		"未响应 -> 已完成 (skip)": DepartmentStatusCompleted,
	} {
		_, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentVenue, DepartmentReportInput{
			Status: target,
		})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}
	for name, target := range map[string]DepartmentStatus{
		"已响应 -> 处置中 (skip)": DepartmentStatusHandling,
		"已响应 -> 已完成 (skip)": DepartmentStatusCompleted,
		"已响应 -> 未响应 (back)": DepartmentStatusNotResponded,
	} {
		_, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{
			Status: target,
		})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}
	for name, target := range map[string]DepartmentStatus{
		"已完成 -> 处置中 (back)": DepartmentStatusHandling,
		"已完成 -> 未响应 (back)": DepartmentStatusNotResponded,
	} {
		_, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{
			Status: target,
		})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}
	// 迁移失败不改变已存记录（卫健仍停在 已完成，其他记录不受影响）。
	records, total, err := service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("after failed transitions: records = %d, total = %d; want 3 / 3", len(records), total)
	}
	for _, record := range records {
		if record.Department == DepartmentHealth && record.Status != DepartmentStatusCompleted {
			t.Fatalf("卫健 after failed transitions: status = %q, want 已完成 kept", record.Status)
		}
		if record.Department == DepartmentFire && record.Status != DepartmentStatusResponded {
			t.Fatalf("消防 after failed transitions: status = %q, want 已响应 kept", record.Status)
		}
		if record.Department == DepartmentVenue && record.Status != DepartmentStatusNotResponded {
			t.Fatalf("场馆应急组 after failed transitions: status = %q, want 未响应 kept", record.Status)
		}
	}
}

// 写门控与 run 缺失：仅 进行中 可写；未开始/已完成/已终止 → 400；
// run 不存在 → ErrRunNotFound。
func TestUpsertDepartmentWriteGateAndRunMissing(t *testing.T) {
	for _, status := range []drills.RunStatus{
		drills.RunStatusNotStarted,
		drills.RunStatusCompleted,
		drills.RunStatusTerminated,
	} {
		service, _ := newTestService(run("run-1", status))
		_, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentOther, DepartmentReportInput{})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", status, err)
		}
	}

	service, _ := newTestService()
	_, err := service.UpsertDepartment(context.Background(), "run-missing", DepartmentFire, DepartmentReportInput{})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}

// ─── ListDepartments ─────────────────────────────────────────────────

// 空列表 → 空切片与 0；run 不存在 → ErrRunNotFound；其他 run 的报告不
// 混入；排序 created_at ASC, id ASC；department/status 筛选生效；
// limit/offset 分页生效（meta.total 保持筛选后总数）。
func TestListDepartmentsSortedFilteredAndPaginated(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))

	records, total, err := service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments (empty): %v", err)
	}
	if len(records) != 0 || total != 0 {
		t.Fatalf("empty list = %d records / %d total, want 0 / 0", len(records), total)
	}

	// run-2 先建 1 条，run-1 再建 3 条（不同 department/status），确保
	// 列表按 run 隔离且排序正确。
	if _, err := service.UpsertDepartment(context.Background(), "run-2", DepartmentOther, DepartmentReportInput{}); err != nil {
		t.Fatalf("UpsertDepartment run-2: %v", err)
	}
	first, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment first: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentPolice, DepartmentReportInput{}); err != nil {
		t.Fatalf("UpsertDepartment second (create): %v", err)
	}
	second, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentPolice, DepartmentReportInput{
		Status: DepartmentStatusResponded,
		Note:   "已出动",
	})
	if err != nil {
		t.Fatalf("UpsertDepartment second (advance): %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{}); err != nil {
		t.Fatalf("UpsertDepartment third (create): %v", err)
	}
	for _, step := range []DepartmentStatus{DepartmentStatusResponded, DepartmentStatusArrived, DepartmentStatusHandling} {
		if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{Status: step}); err != nil {
			t.Fatalf("UpsertDepartment third (advance to %s): %v", step, err)
		}
	}
	third, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentHealth, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment third (fetch): %v", err)
	}

	records, total, err = service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(records), total)
	}
	// created_at ASC：消防、公安、卫健。
	if records[0].ID != first.ID || records[1].ID != second.ID || records[2].ID != third.ID {
		t.Fatalf("records not in created_at ASC order: %+v", records)
	}

	// 筛选。
	records, total, err = service.ListDepartments(context.Background(), "run-1", DepartmentFilter{
		Department: DepartmentPolice, Limit: 50,
	})
	if err != nil {
		t.Fatalf("ListDepartments (department): %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].ID != second.ID {
		t.Fatalf("department filter: records = %+v, total = %d; want 公安 only", records, total)
	}
	records, total, err = service.ListDepartments(context.Background(), "run-1", DepartmentFilter{
		Status: DepartmentStatusResponded, Limit: 50,
	})
	if err != nil {
		t.Fatalf("ListDepartments (status): %v", err)
	}
	if total != 1 || records[0].ID != second.ID {
		t.Fatalf("status filter: records = %+v, total = %d; want 已响应 only", records, total)
	}
	records, total, err = service.ListDepartments(context.Background(), "run-1", DepartmentFilter{
		Status: DepartmentStatusCompleted, Limit: 50,
	})
	if err != nil {
		t.Fatalf("ListDepartments (no match): %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("no match: records = %d, total = %d; want 0 / 0", len(records), total)
	}

	// 分页。
	records, total, err = service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 1, Offset: 1})
	if err != nil {
		t.Fatalf("ListDepartments (paginated): %v", err)
	}
	if total != 3 || len(records) != 1 || records[0].ID != second.ID {
		t.Fatalf("limit=1 offset=1: records = %+v, total = %d; want 公安", records, total)
	}
	records, total, err = service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50, Offset: 10})
	if err != nil {
		t.Fatalf("ListDepartments (offset past end): %v", err)
	}
	if total != 3 || len(records) != 0 {
		t.Fatalf("offset past end: records = %d, total = %d; want 0 / 3", len(records), total)
	}

	// run 隔离与缺失。
	records, total, err = service.ListDepartments(context.Background(), "run-2", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments run-2: %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].Department != DepartmentOther {
		t.Fatalf("run-2 isolation: records = %+v, total = %d; want 其他 only", records, total)
	}
	_, _, err = service.ListDepartments(context.Background(), "run-missing", DepartmentFilter{Limit: 50})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}

// ─── DeleteDepartment ────────────────────────────────────────────────

// DELETE 后记录消失；再次 PUT 重新创建（新 id/created_at，upsert 语义）；
// 记录不存在 → ErrDepartmentNotFound；run 不存在 → ErrRunNotFound；
// 非 进行中 → ValidationError（判定顺序：run 存在 → 记录存在 → 写门控）。
func TestDeleteDepartmentAndRecreate(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	created, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment: %v", err)
	}
	if err := service.DeleteDepartment(context.Background(), "run-1", DepartmentFire); err != nil {
		t.Fatalf("DeleteDepartment: %v", err)
	}
	records, total, err := service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments after delete: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("after delete: records = %d, total = %d; want 0 / 0", len(records), total)
	}

	// 记录不存在 → ErrDepartmentNotFound。
	if err := service.DeleteDepartment(context.Background(), "run-1", DepartmentFire); !errors.Is(err, ErrDepartmentNotFound) {
		t.Fatalf("delete missing report: err = %v, want ErrDepartmentNotFound", err)
	}

	// 再次 PUT 重新创建（upsert 语义，新 id 与 created_at）。
	recreated, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("recreate: %v", err)
	}
	if recreated.ID == created.ID || recreated.CreatedAt.Equal(created.CreatedAt) {
		t.Fatalf("recreate must mint a fresh id/created_at: %+v vs %+v", recreated, created)
	}

	// run 缺失与写门控。
	serviceMissing, _ := newTestService()
	if err := serviceMissing.DeleteDepartment(context.Background(), "run-missing", DepartmentFire); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	serviceNotStarted, storeNotStarted := newTestService(run("run-1", drills.RunStatusNotStarted))
	if err := storeNotStarted.UpsertDepartment(context.Background(), DepartmentReport{
		ID: "0000000000000000000000000A", RunID: "run-1", Department: DepartmentFire,
		Status: DefaultDepartmentStatus, CreatedAt: fixedTime, UpdatedAt: fixedTime,
	}); err != nil {
		t.Fatalf("seed report on 未开始 run: %v", err)
	}
	var validationError *ValidationError
	if err := serviceNotStarted.DeleteDepartment(context.Background(), "run-1", DepartmentFire); !errors.As(err, &validationError) {
		t.Fatalf("delete on 未开始: err = %v, want ValidationError", err)
	}
	// 判定顺序：记录不存在（404）先于写门控（400）。
	serviceCompleted, _ := newTestService(run("run-1", drills.RunStatusCompleted))
	if err := serviceCompleted.DeleteDepartment(context.Background(), "run-1", DepartmentFire); !errors.Is(err, ErrDepartmentNotFound) {
		t.Fatalf("completed run without report: err = %v, want ErrDepartmentNotFound", err)
	}
}

// ─── 级联清理入口 ────────────────────────────────────────────────────

// DeleteDepartmentsByRun 只删除该 run 的报告；删除不存在的 run 不是
// 错误；与其他 run 的报告互不影响。
func TestStoreDeleteDepartmentsByRun(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentFire, DepartmentReportInput{}); err != nil {
		t.Fatalf("UpsertDepartment run-1: %v", err)
	}
	if _, err := service.UpsertDepartment(context.Background(), "run-1", DepartmentPolice, DepartmentReportInput{}); err != nil {
		t.Fatalf("UpsertDepartment run-1 police: %v", err)
	}
	other, err := service.UpsertDepartment(context.Background(), "run-2", DepartmentOther, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment run-2: %v", err)
	}

	if err := store.DeleteDepartmentsByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteDepartmentsByRun: %v", err)
	}
	records, total, err := service.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments run-1 after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("run-1 after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if report, err := store.GetDepartment(context.Background(), "run-2", other.Department); err != nil || report.ID != other.ID {
		t.Fatalf("run-2 after cascade: report = %+v, err = %v; want untouched", report, err)
	}

	// 无报告可删不是错误。
	if err := store.DeleteDepartmentsByRun(context.Background(), "run-9"); err != nil {
		t.Fatalf("DeleteDepartmentsByRun on empty: %v", err)
	}
}

// 同一 created_at 的报告按 id ASC 排序（created_at 相同的并列次序）。
func TestStoreListDepartmentsTieSortsByIDAscending(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	first := DepartmentReport{
		ID: "0000000000000000000000000Z", RunID: "run-1", Department: DepartmentFire,
		Status: DefaultDepartmentStatus, CreatedAt: now, UpdatedAt: now,
	}
	second := DepartmentReport{
		ID: "0000000000000000000000000A", RunID: "run-1", Department: DepartmentPolice,
		Status: DefaultDepartmentStatus, CreatedAt: now, UpdatedAt: now,
	}
	if err := store.UpsertDepartment(context.Background(), first); err != nil {
		t.Fatalf("UpsertDepartment first: %v", err)
	}
	if err := store.UpsertDepartment(context.Background(), second); err != nil {
		t.Fatalf("UpsertDepartment second: %v", err)
	}

	records, total, err := store.ListDepartments(context.Background(), "run-1", DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments: %v", err)
	}
	if total != 2 || len(records) != 2 {
		t.Fatalf("records = %d, total = %d; want 2 / 2", len(records), total)
	}
	// 相同的 created_at：id ASC → second（...0A）在前。
	if records[0].ID != second.ID || records[1].ID != first.ID {
		t.Fatalf("ties must sort by id ASC: %+v", records)
	}
}

// ─── run 删除级联（与 drills.DeleteRun 接线）──────────────────────────

// 把 dispatch store 作为 drills 服务的 RunSessionCleaner 接线后，删除
// run 会级联清空其会话、指令与部门联动记录（与 DB 的 ON DELETE CASCADE
// 一致）；其他 run 的记录保留。
func TestDeleteRunCascadesToDepartmentReports(t *testing.T) {
	drillStore := drills.NewInMemoryStore()
	drillService := drills.NewService(drillStore)
	dispatchStore := NewInMemoryStore()
	drillService.SetRunSessionCleaner(dispatchStore)

	scenario, err := drillService.CreateScenario(context.Background(), drills.ScenarioInput{
		Name: "火警疏散", Category: drills.CategoryFire, Background: "背景",
	})
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	makeRun := func(title string) drills.Run {
		run, err := drillService.CreateRun(context.Background(), drills.RunInput{ScenarioID: scenario.ID, Title: title})
		if err != nil {
			t.Fatalf("CreateRun %s: %v", title, err)
		}
		if _, err := drillService.StartRun(context.Background(), run.ID); err != nil {
			t.Fatalf("StartRun %s: %v", title, err)
		}
		return run
	}
	runA := makeRun("演练A")
	runB := makeRun("演练B")

	// 两 run 各有部门报告；runA 另建会话与指令，验证三类子对象一起级联。
	dispatchService := NewService(dispatchStore, NewRunSource(drillStore))
	if _, err := dispatchService.UpsertDepartment(context.Background(), runA.ID, DepartmentFire, DepartmentReportInput{}); err != nil {
		t.Fatalf("UpsertDepartment runA: %v", err)
	}
	if _, err := dispatchService.UpsertSession(context.Background(), runA.ID, SessionInput{Mode: ModeTabletop}); err != nil {
		t.Fatalf("UpsertSession runA: %v", err)
	}
	if _, err := dispatchService.CreateOrder(context.Background(), runA.ID, OrderInput{
		Title: "疏散", Content: "引导疏散", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	}); err != nil {
		t.Fatalf("CreateOrder runA: %v", err)
	}
	reportB, err := dispatchService.UpsertDepartment(context.Background(), runB.ID, DepartmentPolice, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment runB: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), runA.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}

	// runA 的部门报告清空（run 已删除，直接用 store 断言）。
	records, total, err := dispatchStore.ListDepartments(context.Background(), runA.ID, DepartmentFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDepartments runA after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("runA after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	// runA 的会话与指令也级联清空（既有清理入口不回归）。
	if _, err := dispatchStore.GetSession(context.Background(), runA.ID); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("runA session after cascade: err = %v, want ErrSessionNotFound", err)
	}
	orders, total, err := dispatchStore.ListOrders(context.Background(), runA.ID, OrderFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders runA after cascade: %v", err)
	}
	if total != 0 || len(orders) != 0 {
		t.Fatalf("runA orders after cascade: %d / %d, want 0 / 0", len(orders), total)
	}

	// runB 的部门报告保留。
	if report, err := dispatchStore.GetDepartment(context.Background(), runB.ID, reportB.Department); err != nil || report.ID != reportB.ID {
		t.Fatalf("runB after cascade: report = %+v, err = %v; want untouched", report, err)
	}
}

// 未接线 cleaner 时删除 run 不触碰部门报告（与 #55/#56 的未接线行为
// 一致）。
func TestDeleteRunWithoutCleanerKeepsDepartmentReports(t *testing.T) {
	drillStore := drills.NewInMemoryStore()
	drillService := drills.NewService(drillStore)
	dispatchStore := NewInMemoryStore()

	scenario, err := drillService.CreateScenario(context.Background(), drills.ScenarioInput{
		Name: "火警疏散", Category: drills.CategoryFire, Background: "背景",
	})
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	run, err := drillService.CreateRun(context.Background(), drills.RunInput{ScenarioID: scenario.ID, Title: "演练"})
	if err != nil {
		t.Fatalf("CreateRun: %v", err)
	}
	if _, err := drillService.StartRun(context.Background(), run.ID); err != nil {
		t.Fatalf("StartRun: %v", err)
	}
	dispatchService := NewService(dispatchStore, NewRunSource(drillStore))
	report, err := dispatchService.UpsertDepartment(context.Background(), run.ID, DepartmentFire, DepartmentReportInput{})
	if err != nil {
		t.Fatalf("UpsertDepartment: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}
	if got, err := dispatchStore.GetDepartment(context.Background(), run.ID, report.Department); err != nil || got.ID != report.ID {
		t.Fatalf("report must survive without a cleaner: got = %+v, err = %v", got, err)
	}
}
