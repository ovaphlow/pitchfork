package dispatch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// intPtr returns a pointer to the given int (a helper for the required
// people_count input).
func intPtr(value int) *int {
	return &value
}

// ─── CreateZoneDensity ───────────────────────────────────────────────

// 首次上报：完整对象，id 为 26 位 Crockford Base32 ULID，run_id 回显，
// zone_name/people_count 透传，reported_at 服务端创建时设置非空（恒非
// 空回显），created_by 缺省空串，created_at/updated_at 为服务端时间且
// 相等。
func TestCreateZoneDensityRecordsWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	density, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName:    "东区广场",
		PeopleCount: intPtr(128),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}
	if !crockford26.MatchString(density.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", density.ID)
	}
	if density.RunID != "run-1" {
		t.Fatalf("run_id = %q, want the run from the caller", density.RunID)
	}
	if density.ZoneName != "东区广场" || density.PeopleCount != 128 {
		t.Fatalf("zone_name/people_count = %q / %d, want the input echoed", density.ZoneName, density.PeopleCount)
	}
	if density.ReportedAt == nil {
		t.Fatalf("reported_at = nil, want the server-set creation instant")
	}
	if density.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", density.CreatedBy)
	}
	if density.CreatedAt.IsZero() || !density.CreatedAt.Equal(density.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want server time and equal", density.CreatedAt, density.UpdatedAt)
	}
}

// 显式字段：zone_name、people_count（含 0）、created_by 原样写入；
// reported_at 由服务端设置（请求无法覆盖）。
func TestCreateZoneDensityExplicitFields(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	density, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName:    "3 号出口",
		PeopleCount: intPtr(0),
		CreatedBy:   "u-field-zhang",
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}
	if density.ZoneName != "3 号出口" || density.PeopleCount != 0 || density.CreatedBy != "u-field-zhang" {
		t.Fatalf("density = %+v, want the explicit fields echoed", density)
	}
	if density.ReportedAt == nil {
		t.Fatalf("reported_at = nil, want the server-set creation instant")
	}
}

// 失败路径：缺 zone_name（含空白）、缺 people_count（nil）、负值 →
// ValidationError。
func TestCreateZoneDensityValidation(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	for name, input := range map[string]ZoneDensityInput{
		"missing zone_name":     {PeopleCount: intPtr(10)},
		"blank zone_name":       {ZoneName: "  ", PeopleCount: intPtr(10)},
		"missing people_count":  {ZoneName: "东区广场"},
		"negative people_count": {ZoneName: "东区广场", PeopleCount: intPtr(-1)},
	} {
		if _, err := service.CreateZoneDensity(context.Background(), "run-1", input); err == nil {
			t.Fatalf("%s: want a ValidationError", name)
		} else {
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				t.Fatalf("%s: err = %v, want a ValidationError", name, err)
			}
		}
	}
}

// 状态约束：run 不存在 404 优先于门控；仅 进行中 可上报（未开始/已完成
// 400）。
func TestCreateZoneDensityRunState(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusNotStarted),
		run("run-2", drills.RunStatusCompleted),
	)

	if _, err := service.CreateZoneDensity(context.Background(), "missing", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(10),
	}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for _, runID := range []string{"run-1", "run-2"} {
		if _, err := service.CreateZoneDensity(context.Background(), runID, ZoneDensityInput{
			ZoneName: "东区广场", PeopleCount: intPtr(10),
		}); err == nil {
			t.Fatalf("%s: want a ValidationError (write gate)", runID)
		} else {
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				t.Fatalf("%s: err = %v, want a ValidationError", runID, err)
			}
		}
	}
}

// ─── GetZoneDensity ──────────────────────────────────────────────────

// 存在返回完整对象（含 run_id/reported_at）；id 不存在、报告属于其他
// run、run 不存在 → ErrZoneDensityNotFound / ErrRunNotFound。
func TestGetZoneDensity(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusInProgress),
	)
	density, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(128),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}
	other, err := service.CreateZoneDensity(context.Background(), "run-2", ZoneDensityInput{
		ZoneName: "西区", PeopleCount: intPtr(30),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity run-2: %v", err)
	}

	got, err := service.GetZoneDensity(context.Background(), "run-1", density.ID)
	if err != nil {
		t.Fatalf("GetZoneDensity: %v", err)
	}
	if got.ID != density.ID || got.RunID != "run-1" || got.ZoneName != "东区广场" ||
		got.PeopleCount != 128 || got.ReportedAt == nil {
		t.Fatalf("get does not return the full object: %+v", got)
	}

	if _, err := service.GetZoneDensity(context.Background(), "run-1", "01ARZ3NDEKTSV4RRFFQ69G5FAV"); !errors.Is(err, ErrZoneDensityNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrZoneDensityNotFound", err)
	}
	if _, err := service.GetZoneDensity(context.Background(), "run-1", other.ID); !errors.Is(err, ErrZoneDensityNotFound) {
		t.Fatalf("report of another run: err = %v, want ErrZoneDensityNotFound", err)
	}
	if _, err := service.GetZoneDensity(context.Background(), "missing", density.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}

// GET 不受写门控：已完成 run 的报告仍可取（同一 store 换已完成状态的
// run 源）。
func TestGetZoneDensityNotGatedOnCompletedRun(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "主场馆", PeopleCount: intPtr(200),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}
	completedService := NewService(store, &fakeRunSource{runs: map[string]drills.Run{
		"run-1": {ID: "run-1", Status: drills.RunStatusCompleted},
	}})
	got, err := completedService.GetZoneDensity(context.Background(), "run-1", created.ID)
	if err != nil {
		t.Fatalf("GET on 已完成 run: %v", err)
	}
	if got.ID != created.ID || got.ZoneName != "主场馆" {
		t.Fatalf("get on completed run does not return the report: %+v", got)
	}
}

// ─── ListZoneDensities ───────────────────────────────────────────────

// 空列表返回空切片与 total 0；run 不存在 ErrRunNotFound。
func TestListZoneDensitiesEmptyAndMissingRun(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	records, total, err := service.ListZoneDensities(context.Background(), "run-1", ZoneDensityFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListZoneDensities: %v", err)
	}
	if records == nil || len(records) != 0 || total != 0 {
		t.Fatalf("empty list = %+v / %d, want empty and total 0", records, total)
	}

	if _, _, err := service.ListZoneDensities(context.Background(), "missing", ZoneDensityFilter{Limit: 50}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
}

// 排序 reported_at DESC, id DESC（最新上报在前）；zone_name 精确匹配筛
// 选；meta.total 为筛选后的总数。
func TestListZoneDensitiesSortedAndFiltered(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	earliest := ZoneDensity{
		ID: "0000000000000000000000000Z", RunID: "run-1", ZoneName: "东区广场",
		PeopleCount: 100, ReportedAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	now2 := now.Add(time.Minute)
	middle := ZoneDensity{
		ID: "0000000000000000000000000A", RunID: "run-1", ZoneName: "西区",
		PeopleCount: 200, ReportedAt: &now2, CreatedAt: now2, UpdatedAt: now2,
	}
	now3 := now.Add(2 * time.Minute)
	latest := ZoneDensity{
		ID: "0000000000000000000000000B", RunID: "run-1", ZoneName: "东区广场",
		PeopleCount: 300, ReportedAt: &now3, CreatedAt: now3, UpdatedAt: now3,
	}
	other := ZoneDensity{
		ID: "0000000000000000000000000C", RunID: "run-2", ZoneName: "东区广场",
		PeopleCount: 50, ReportedAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	for _, density := range []ZoneDensity{earliest, middle, latest, other} {
		if err := store.CreateZoneDensity(context.Background(), density); err != nil {
			t.Fatalf("CreateZoneDensity: %v", err)
		}
	}

	records, total, err := store.ListZoneDensities(context.Background(), "run-1", ZoneDensityFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListZoneDensities: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(records), total)
	}
	// reported_at DESC：latest、middle、earliest。
	if records[0].ID != latest.ID || records[1].ID != middle.ID || records[2].ID != earliest.ID {
		t.Fatalf("records not in reported_at DESC order: %+v", records)
	}

	// zone_name 筛选：东区广场 → latest、earliest。
	records, total, err = store.ListZoneDensities(context.Background(), "run-1", ZoneDensityFilter{ZoneName: "东区广场", Limit: 50})
	if err != nil {
		t.Fatalf("ListZoneDensities filtered: %v", err)
	}
	if total != 2 || len(records) != 2 || records[0].ID != latest.ID || records[1].ID != earliest.ID {
		t.Fatalf("zone_name filter: records = %d, total = %d; want 2 / 2 with latest first", len(records), total)
	}
}

// 并列 reported_at 时按 id DESC（同一上报时刻的并列次序）。
func TestStoreListZoneDensitiesTieSortsByIDDescending(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	first := ZoneDensity{
		ID: "0000000000000000000000000A", RunID: "run-1", ZoneName: "东区广场",
		PeopleCount: 100, ReportedAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	second := ZoneDensity{
		ID: "0000000000000000000000000Z", RunID: "run-1", ZoneName: "西区",
		PeopleCount: 200, ReportedAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	for _, density := range []ZoneDensity{first, second} {
		if err := store.CreateZoneDensity(context.Background(), density); err != nil {
			t.Fatalf("CreateZoneDensity: %v", err)
		}
	}

	records, total, err := store.ListZoneDensities(context.Background(), "run-1", ZoneDensityFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListZoneDensities: %v", err)
	}
	if total != 2 || len(records) != 2 {
		t.Fatalf("records = %d, total = %d; want 2 / 2", len(records), total)
	}
	// 相同的 reported_at：id DESC → second（...0Z）在前。
	if records[0].ID != second.ID || records[1].ID != first.ID {
		t.Fatalf("ties must sort by id DESC: %+v", records)
	}
}

// limit/offset 分页生效（缺省 limit 50，total 保持总数）。
func TestListZoneDensitiesPagination(t *testing.T) {
	store := NewInMemoryStore()
	base := fixedTime
	for i := 0; i < 3; i++ {
		now := base.Add(time.Duration(i) * time.Minute)
		density := ZoneDensity{
			ID: "0000000000000000000000000" + string(rune('A'+i)), RunID: "run-1",
			ZoneName: "东区广场", PeopleCount: 10 + i, ReportedAt: &now, CreatedAt: now, UpdatedAt: now,
		}
		if err := store.CreateZoneDensity(context.Background(), density); err != nil {
			t.Fatalf("CreateZoneDensity: %v", err)
		}
	}

	records, total, err := store.ListZoneDensities(context.Background(), "run-1", ZoneDensityFilter{Limit: 1, Offset: 1})
	if err != nil {
		t.Fatalf("ListZoneDensities: %v", err)
	}
	if len(records) != 1 || total != 3 {
		t.Fatalf("limit=1 offset=1: records = %d, total = %d; want 1 / 3", len(records), total)
	}
	// reported_at DESC：第 2 新的记录（B）。
	if records[0].ID != "0000000000000000000000000B" {
		t.Fatalf("page not in reported_at DESC order: %+v", records)
	}
}

// ─── UpdateZoneDensity ───────────────────────────────────────────────

// 更新 zone_name/people_count 生效，reported_at 刷新（晚于创建时刻），
// id/run_id/created_at/created_by 保持不变，updated_at 变化。
func TestUpdateZoneDensity(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	created, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(128), CreatedBy: "u-field-zhang",
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}
	time.Sleep(5 * time.Millisecond)

	updated, err := service.UpdateZoneDensity(context.Background(), "run-1", created.ID, ZoneDensityInput{
		ZoneName: "东区广场北侧", PeopleCount: intPtr(256),
	})
	if err != nil {
		t.Fatalf("UpdateZoneDensity: %v", err)
	}
	if updated.ID != created.ID || updated.RunID != created.RunID {
		t.Fatalf("id/run_id must be preserved: %+v", updated)
	}
	if updated.ZoneName != "东区广场北侧" || updated.PeopleCount != 256 {
		t.Fatalf("update does not apply: %+v", updated)
	}
	if !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Fatalf("created_at must be preserved: %v -> %v", created.CreatedAt, updated.CreatedAt)
	}
	if updated.CreatedBy != "u-field-zhang" {
		t.Fatalf("created_by must be preserved: %q", updated.CreatedBy)
	}
	if updated.ReportedAt == nil || !updated.ReportedAt.After(*created.ReportedAt) {
		t.Fatalf("reported_at must be refreshed: %v -> %v", *created.ReportedAt, updated.ReportedAt)
	}
	if !updated.UpdatedAt.After(created.UpdatedAt) {
		t.Fatalf("updated_at must be refreshed: %v -> %v", created.UpdatedAt, updated.UpdatedAt)
	}

	// PUT 后 GET 反映更新。
	got, err := service.GetZoneDensity(context.Background(), "run-1", created.ID)
	if err != nil {
		t.Fatalf("GetZoneDensity after update: %v", err)
	}
	if got.ZoneName != "东区广场北侧" || got.PeopleCount != 256 {
		t.Fatalf("get after update does not reflect it: %+v", got)
	}
}

// 失败路径：缺 zone_name/people_count/负值 → ValidationError；id 不存
// 在 → ErrZoneDensityNotFound；run 不存在 → ErrRunNotFound（优先于门
// 控）；非 进行中 → ValidationError。
func TestUpdateZoneDensityFailures(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusCompleted),
	)
	density, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(128),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}

	for name, input := range map[string]ZoneDensityInput{
		"missing zone_name":     {PeopleCount: intPtr(10)},
		"blank zone_name":       {ZoneName: "  ", PeopleCount: intPtr(10)},
		"missing people_count":  {ZoneName: "东区广场"},
		"negative people_count": {ZoneName: "东区广场", PeopleCount: intPtr(-1)},
	} {
		if _, err := service.UpdateZoneDensity(context.Background(), "run-1", density.ID, input); err == nil {
			t.Fatalf("%s: want a ValidationError", name)
		} else {
			var validationError *ValidationError
			if !errors.As(err, &validationError) {
				t.Fatalf("%s: err = %v, want a ValidationError", name, err)
			}
		}
	}

	if _, err := service.UpdateZoneDensity(context.Background(), "run-1", "01ARZ3NDEKTSV4RRFFQ69G5FAV", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(10),
	}); !errors.Is(err, ErrZoneDensityNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrZoneDensityNotFound", err)
	}
	if _, err := service.UpdateZoneDensity(context.Background(), "missing", density.ID, ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(10),
	}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpdateZoneDensity(context.Background(), "run-2", density.ID, ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(10),
	}); err == nil {
		t.Fatalf("已完成 run: want a ValidationError (write gate)")
	}
}

// ─── DeleteZoneDensity ───────────────────────────────────────────────

// 删除成功；id 不存在 → ErrZoneDensityNotFound；run 不存在 →
// ErrRunNotFound（优先于门控）；非 进行中 → ValidationError。
func TestDeleteZoneDensity(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusCompleted),
	)
	density, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(128),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity: %v", err)
	}

	if err := service.DeleteZoneDensity(context.Background(), "run-1", density.ID); err != nil {
		t.Fatalf("DeleteZoneDensity: %v", err)
	}
	if _, err := service.GetZoneDensity(context.Background(), "run-1", density.ID); !errors.Is(err, ErrZoneDensityNotFound) {
		t.Fatalf("GET after DELETE: err = %v, want ErrZoneDensityNotFound", err)
	}

	if err := service.DeleteZoneDensity(context.Background(), "run-1", "01ARZ3NDEKTSV4RRFFQ69G5FAV"); !errors.Is(err, ErrZoneDensityNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrZoneDensityNotFound", err)
	}
	if err := service.DeleteZoneDensity(context.Background(), "missing", density.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	if err := service.DeleteZoneDensity(context.Background(), "run-2", density.ID); err == nil {
		t.Fatalf("已完成 run: want a ValidationError (write gate)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("已完成 run: err = %v, want a ValidationError", err)
		}
	}
}

// ─── 级联清理入口 ────────────────────────────────────────────────────

// DeleteZoneDensitiesByRun 只删除该 run 的报告；删除不存在的 run 不是
// 错误；与其他 run 的报告互不影响。
func TestStoreDeleteZoneDensitiesByRun(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	if _, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(128),
	}); err != nil {
		t.Fatalf("CreateZoneDensity run-1: %v", err)
	}
	if _, err := service.CreateZoneDensity(context.Background(), "run-1", ZoneDensityInput{
		ZoneName: "西区", PeopleCount: intPtr(30),
	}); err != nil {
		t.Fatalf("CreateZoneDensity run-1 west: %v", err)
	}
	other, err := service.CreateZoneDensity(context.Background(), "run-2", ZoneDensityInput{
		ZoneName: "主场馆", PeopleCount: intPtr(200),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity run-2: %v", err)
	}

	if err := store.DeleteZoneDensitiesByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteZoneDensitiesByRun: %v", err)
	}
	records, total, err := service.ListZoneDensities(context.Background(), "run-1", ZoneDensityFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListZoneDensities run-1 after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("run-1 after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if density, err := store.GetZoneDensity(context.Background(), "run-2", other.ID); err != nil || density.ID != other.ID {
		t.Fatalf("run-2 after cascade: density = %+v, err = %v; want untouched", density, err)
	}

	// 无报告可删不是错误。
	if err := store.DeleteZoneDensitiesByRun(context.Background(), "run-9"); err != nil {
		t.Fatalf("DeleteZoneDensitiesByRun on empty: %v", err)
	}
}

// ─── run 删除级联（与 drills.DeleteRun 接线）──────────────────────────

// 把 dispatch store 作为 drills 服务的 RunSessionCleaner 接线后，删除
// run 会级联清空其区域人流热力上报（与 DB 的 ON DELETE CASCADE 一致）；
// 其他 run 的报告保留；既有清理入口（会话/指令/部门报告/消息）不回归。
func TestDeleteRunCascadesToZoneDensities(t *testing.T) {
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

	dispatchService := NewService(dispatchStore, NewRunSource(drillStore))
	if _, err := dispatchService.CreateZoneDensity(context.Background(), runA.ID, ZoneDensityInput{
		ZoneName: "东区广场", PeopleCount: intPtr(128),
	}); err != nil {
		t.Fatalf("CreateZoneDensity runA: %v", err)
	}
	reportB, err := dispatchService.CreateZoneDensity(context.Background(), runB.ID, ZoneDensityInput{
		ZoneName: "西区", PeopleCount: intPtr(30),
	})
	if err != nil {
		t.Fatalf("CreateZoneDensity runB: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), runA.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}

	// runA 的 zone-density 报告清空（run 已删除，直接用 store 断言）。
	records, total, err := dispatchStore.ListZoneDensities(context.Background(), runA.ID, ZoneDensityFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListZoneDensities runA after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("runA after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}

	// runB 的报告保留。
	if density, err := dispatchStore.GetZoneDensity(context.Background(), runB.ID, reportB.ID); err != nil || density.ID != reportB.ID {
		t.Fatalf("runB after cascade: density = %+v, err = %v; want untouched", density, err)
	}
}
