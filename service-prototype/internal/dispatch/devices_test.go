package dispatch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ─── CreateDevice ────────────────────────────────────────────────────

// 首次上报：完整对象，id 为 26 位 Crockford Base32 ULID，run_id 回显，
// device_name/device_type 透传，status 缺省 正常、note 缺省空串，
// created_by 缺省空串，created_at/updated_at 为服务端时间且相等。
func TestCreateDeviceWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	device, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜",
		DeviceType: DeviceTypePowerSupply,
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	if !crockford26.MatchString(device.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", device.ID)
	}
	if device.RunID != "run-1" {
		t.Fatalf("run_id = %q, want the run from the caller", device.RunID)
	}
	if device.DeviceName != "1号配电柜" || device.DeviceType != DeviceTypePowerSupply {
		t.Fatalf("device_name/device_type = %q / %q, want the input echoed", device.DeviceName, device.DeviceType)
	}
	if device.Status != DefaultDeviceStatus {
		t.Fatalf("status = %q, want the default %q", device.Status, DefaultDeviceStatus)
	}
	if device.Note != "" {
		t.Fatalf("note = %q, want an empty default", device.Note)
	}
	if device.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", device.CreatedBy)
	}
	if device.CreatedAt.IsZero() || !device.CreatedAt.Equal(device.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want server time and equal", device.CreatedAt, device.UpdatedAt)
	}
}

// 显式字段：device_type=告警设备、status=告警、note、created_by 原样写
// 入；所有合法 device_type/status 组合均被接受。
func TestCreateDeviceExplicitFields(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	device, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "东区消防栓",
		DeviceType: DeviceTypeFire,
		Status:     DeviceStatusWarning,
		Note:       "水压不足",
		CreatedBy:  "u-iot-bridge",
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	if device.DeviceName != "东区消防栓" || device.DeviceType != DeviceTypeFire ||
		device.Status != DeviceStatusWarning || device.Note != "水压不足" || device.CreatedBy != "u-iot-bridge" {
		t.Fatalf("device = %+v, want the explicit fields echoed", device)
	}

	// 全部合法 device_type / status 组合。
	for _, deviceType := range validDeviceTypes {
		for _, status := range validDeviceStatuses {
			if _, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
				DeviceName: "组合设备", DeviceType: deviceType, Status: status,
			}); err != nil {
				t.Fatalf("device_type %q / status %q: %v", deviceType, status, err)
			}
		}
	}
}

// 失败路径：缺 device_name（含空白）、缺 device_type、非法 device_type、
// 非法 status → ValidationError。
func TestCreateDeviceValidation(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	cases := []struct {
		name  string
		input DeviceInput
	}{
		{"missing device_name", DeviceInput{DeviceType: DeviceTypePowerSupply}},
		{"blank device_name", DeviceInput{DeviceName: "  ", DeviceType: DeviceTypePowerSupply}},
		{"missing device_type", DeviceInput{DeviceName: "1号配电柜"}},
		{"invalid device_type", DeviceInput{DeviceName: "1号配电柜", DeviceType: DeviceType("门禁")}},
		{"invalid status", DeviceInput{DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply, Status: DeviceStatus("离线中")}},
	}
	for _, testCase := range cases {
		_, err := service.CreateDevice(context.Background(), "run-1", testCase.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}
}

// 状态约束：run 不存在 → ErrRunNotFound；仅 进行中 可 POST（未开始/已完
// 成/已终止 400）。
func TestCreateDeviceRunState(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusNotStarted),
		run("run-3", drills.RunStatusCompleted),
		run("run-4", drills.RunStatusTerminated),
	)

	// run 不存在 → ErrRunNotFound。
	_, err := service.CreateDevice(context.Background(), "run-9", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// 非 进行中 → ValidationError。
	for _, runID := range []string{"run-2", "run-3", "run-4"} {
		_, err := service.CreateDevice(context.Background(), runID, DeviceInput{
			DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
		})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", runID, err)
		}
	}

	// 进行中 → 成功。
	if _, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	}); err != nil {
		t.Fatalf("in-progress run: %v", err)
	}
}

// ─── GetDevice / DeleteDevice ────────────────────────────────────────

// GetDevice：存在 → 完整对象（含 created_by 回显）；报告不存在 →
// ErrDeviceNotFound；run 不存在 → ErrRunNotFound；GET 不受门控（已完成
// run 的报告仍可读）。
func TestGetDevice(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-3", drills.RunStatusCompleted),
	)
	created, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply, CreatedBy: "u-iot-bridge",
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	got, err := service.GetDevice(context.Background(), "run-1", created.ID)
	if err != nil {
		t.Fatalf("GetDevice: %v", err)
	}
	if got.ID != created.ID || got.DeviceName != "1号配电柜" || got.CreatedBy != "u-iot-bridge" {
		t.Fatalf("GetDevice = %+v, want the created device", got)
	}
	if _, err := service.GetDevice(context.Background(), "run-1", "no-such-id"); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("missing device: err = %v, want ErrDeviceNotFound", err)
	}
	if _, err := service.GetDevice(context.Background(), "run-9", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// 已完成 run 的 GET 不受门控。
	if _, err := service.GetDevice(context.Background(), "run-3", "no-such-id"); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("completed run GET: err = %v, want ErrDeviceNotFound (not a gate error)", err)
	}
}

// DeleteDevice：成功删除；报告不存在 → ErrDeviceNotFound；run 不存在 →
// ErrRunNotFound（优先于门控）；非 进行中 → ValidationError。
func TestDeleteDevice(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusCompleted),
	)
	created, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := service.DeleteDevice(context.Background(), "run-1", created.ID); err != nil {
		t.Fatalf("DeleteDevice: %v", err)
	}
	if _, err := service.GetDevice(context.Background(), "run-1", created.ID); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("device after delete: err = %v, want ErrDeviceNotFound", err)
	}
	if err := service.DeleteDevice(context.Background(), "run-1", created.ID); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("delete again: err = %v, want ErrDeviceNotFound", err)
	}
	if err := service.DeleteDevice(context.Background(), "run-9", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// 非 进行中 → ValidationError（门控先于报告存在性检查，同 orders）。
	if err := service.DeleteDevice(context.Background(), "run-2", created.ID); !errors.Is(err, ErrDeviceNotFound) {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("completed run delete: err = %v, want ValidationError (gate precedes device check)", err)
		}
	}
}

// ─── UpdateDevice ────────────────────────────────────────────────────

// 更新 device_name/device_type/status/note 生效（全量替换：缺省字段重置
// 为默认值）；id/run_id/created_by/created_at 保留、updated_at 刷新；
// 更新后 GetDevice 反映更新。
func TestUpdateDevice(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply, Status: DeviceStatusWarning,
		Note: "电压波动", CreatedBy: "u-iot-bridge",
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	updated, err := service.UpdateDevice(context.Background(), "run-1", created.ID, DeviceInput{
		DeviceName: "1号配电柜（北区）", DeviceType: DeviceTypeOther, Status: DeviceStatusOffline, Note: "断电",
	})
	if err != nil {
		t.Fatalf("UpdateDevice: %v", err)
	}
	if updated.DeviceName != "1号配电柜（北区）" || updated.DeviceType != DeviceTypeOther ||
		updated.Status != DeviceStatusOffline || updated.Note != "断电" {
		t.Fatalf("updated fields not applied: %+v", updated)
	}
	if updated.ID != created.ID || updated.RunID != "run-1" {
		t.Fatalf("id/run_id must be preserved: %+v", updated)
	}
	if updated.CreatedBy != "u-iot-bridge" {
		t.Fatalf("created_by must be preserved: %q", updated.CreatedBy)
	}
	if !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at %v must be refreshed on update", updated.UpdatedAt)
	}

	// 更新后 GET 反映更新。
	got, err := service.GetDevice(context.Background(), "run-1", created.ID)
	if err != nil {
		t.Fatalf("GetDevice after update: %v", err)
	}
	if got.Status != DeviceStatusOffline || got.Note != "断电" {
		t.Fatalf("GET after PUT must reflect the update: %+v", got)
	}

	// 全量替换：缺省 status/note 重置为默认值。
	replaced, err := service.UpdateDevice(context.Background(), "run-1", created.ID, DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	})
	if err != nil {
		t.Fatalf("UpdateDevice replacement: %v", err)
	}
	if replaced.Status != DefaultDeviceStatus || replaced.Note != "" {
		t.Fatalf("full replacement semantics = %+v", replaced)
	}
	if replaced.CreatedBy != "u-iot-bridge" || !replaced.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_by/created_at must survive the replacement: %+v", replaced)
	}
}

// 失败路径（与 POST 一致覆盖）：缺 device_name（含空白）、缺
// device_type、非法 device_type/status → ValidationError；报告不存在 →
// ErrDeviceNotFound；run 不存在 → ErrRunNotFound（优先于门控）；非
// 进行中 → ValidationError。
func TestUpdateDeviceValidationAndNotFound(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusCompleted),
	)
	created, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	cases := []struct {
		name  string
		input DeviceInput
	}{
		{"missing device_name", DeviceInput{DeviceType: DeviceTypePowerSupply}},
		{"blank device_name", DeviceInput{DeviceName: " ", DeviceType: DeviceTypePowerSupply}},
		{"missing device_type", DeviceInput{DeviceName: "1号配电柜"}},
		{"invalid device_type", DeviceInput{DeviceName: "1号配电柜", DeviceType: DeviceType("门禁")}},
		{"invalid status", DeviceInput{DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply, Status: DeviceStatus("离线中")}},
	}
	for _, testCase := range cases {
		_, err := service.UpdateDevice(context.Background(), "run-1", created.ID, testCase.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}

	// 报告不存在 → ErrDeviceNotFound。
	if _, err := service.UpdateDevice(context.Background(), "run-1", "no-such-id", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	}); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("unknown id: err = %v, want ErrDeviceNotFound", err)
	}

	// run 不存在 → ErrRunNotFound。
	if _, err := service.UpdateDevice(context.Background(), "run-9", created.ID, DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// 非 进行中 → ValidationError（门控先于报告存在性检查，同 orders）。
	_, err = service.UpdateDevice(context.Background(), "run-2", created.ID, DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("completed run update: err = %v, want ValidationError", err)
	}
}

// ─── ListDevices ────────────────────────────────────────────────────

// 空列表：records 为空、total 为 0；run 不存在 → ErrRunNotFound；GET
// 不受门控（已完成 run 仍可列出）。
func TestListDevicesEmptyAndRunState(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-3", drills.RunStatusCompleted),
	)

	records, total, err := service.ListDevices(context.Background(), "run-1", DeviceFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices: %v", err)
	}
	if len(records) != 0 || total != 0 {
		t.Fatalf("empty run: %d records / %d total, want 0 / 0", len(records), total)
	}
	if _, _, err := service.ListDevices(context.Background(), "run-9", DeviceFilter{Limit: 50}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// 已完成 run 的 GET 不受门控。
	if _, _, err := service.ListDevices(context.Background(), "run-3", DeviceFilter{Limit: 50}); err != nil {
		t.Fatalf("completed run list: %v, want no gate error", err)
	}
}

// 筛选 device_type/status 生效；排序 created_at ASC、同刻 id ASC（最早
// 上报在前）；limit/offset 分页生效；其他 run 的报告不混入。
func TestStoreListDevicesFilterSortAndPagination(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	// 同一 created_at 的并列：id ASC → ...0A 在前。
	devices := []Device{
		{
			ID: "0000000000000000000000000C", RunID: "run-1", DeviceName: "3号电梯",
			DeviceType: DeviceTypeElevator, Status: DeviceStatusNormal, Note: "",
			CreatedAt: now.Add(2 * time.Minute), UpdatedAt: now.Add(2 * time.Minute),
		},
		{
			ID: "0000000000000000000000000B", RunID: "run-1", DeviceName: "2号消防栓",
			DeviceType: DeviceTypeFire, Status: DeviceStatusWarning, Note: "水压不足",
			CreatedAt: now.Add(time.Minute), UpdatedAt: now.Add(time.Minute),
		},
		{
			ID: "0000000000000000000000000A", RunID: "run-1", DeviceName: "1号配电柜",
			DeviceType: DeviceTypePowerSupply, Status: DeviceStatusOffline, Note: "断电",
			CreatedAt: now, UpdatedAt: now,
		},
		{
			ID: "0000000000000000000000000D", RunID: "run-2", DeviceName: "别的run设备",
			DeviceType: DeviceTypePowerSupply, Status: DeviceStatusNormal,
			CreatedAt: now, UpdatedAt: now,
		},
	}
	for _, device := range devices {
		if err := store.CreateDevice(context.Background(), device); err != nil {
			t.Fatalf("CreateDevice %s: %v", device.ID, err)
		}
	}

	// 全量：created_at ASC（一号→二号→三号），total=3（run-2 排除）。
	records, total, err := store.ListDevices(context.Background(), "run-1", DeviceFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(records), total)
	}
	if records[0].ID != "0000000000000000000000000A" || records[1].ID != "0000000000000000000000000B" ||
		records[2].ID != "0000000000000000000000000C" {
		t.Fatalf("created_at ASC order broken: %+v", records)
	}

	// 筛选 device_type=消防：仅二号，total=1。
	records, total, err = store.ListDevices(context.Background(), "run-1", DeviceFilter{DeviceType: DeviceTypeFire, Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices device_type filter: %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].DeviceName != "2号消防栓" {
		t.Fatalf("device_type filter: %d records / %d total, want 1 / 1 with 2号消防栓", len(records), total)
	}

	// 筛选 status=离线：仅一号，total=1。
	records, total, err = store.ListDevices(context.Background(), "run-1", DeviceFilter{Status: DeviceStatusOffline, Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices status filter: %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].DeviceName != "1号配电柜" {
		t.Fatalf("status filter: %d records / %d total, want 1 / 1 with 1号配电柜", len(records), total)
	}

	// 分页 limit=2&offset=1：二号+三号，total 仍为 3。
	records, total, err = store.ListDevices(context.Background(), "run-1", DeviceFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListDevices paginated: %v", err)
	}
	if total != 3 || len(records) != 2 || records[0].DeviceName != "2号消防栓" || records[1].DeviceName != "3号电梯" {
		t.Fatalf("pagination: %d records / %d total, want 2 / 3 (二号, 三号)", len(records), total)
	}
}

// 同一 created_at 的设备按 id ASC 排序（并列次序，稳定尾键）。
func TestStoreListDevicesTieSortsByIDAscending(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	first := Device{
		ID: "0000000000000000000000000Z", RunID: "run-1", DeviceName: "后",
		DeviceType: DeviceTypePowerSupply, Status: DeviceStatusNormal,
		CreatedAt: now, UpdatedAt: now,
	}
	second := Device{
		ID: "0000000000000000000000000A", RunID: "run-1", DeviceName: "前",
		DeviceType: DeviceTypePowerSupply, Status: DeviceStatusNormal,
		CreatedAt: now, UpdatedAt: now,
	}
	if err := store.CreateDevice(context.Background(), first); err != nil {
		t.Fatalf("CreateDevice first: %v", err)
	}
	if err := store.CreateDevice(context.Background(), second); err != nil {
		t.Fatalf("CreateDevice second: %v", err)
	}

	records, total, err := store.ListDevices(context.Background(), "run-1", DeviceFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices: %v", err)
	}
	if total != 2 || len(records) != 2 {
		t.Fatalf("records = %d, total = %d; want 2 / 2", len(records), total)
	}
	// 相同的 created_at：id ASC → second（...0A）在前。
	if records[0].ID != second.ID || records[1].ID != first.ID {
		t.Fatalf("ties must sort by id ASC: %+v", records)
	}
}

// ─── 级联清理入口 ────────────────────────────────────────────────────

// DeleteDevicesByRun 只删除该 run 的报告；删除不存在的 run 不是错误；
// 与其他 run 的报告互不影响。
func TestStoreDeleteDevicesByRun(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	if _, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	}); err != nil {
		t.Fatalf("CreateDevice run-1: %v", err)
	}
	if _, err := service.CreateDevice(context.Background(), "run-1", DeviceInput{
		DeviceName: "2号消防栓", DeviceType: DeviceTypeFire,
	}); err != nil {
		t.Fatalf("CreateDevice run-1 second: %v", err)
	}
	other, err := service.CreateDevice(context.Background(), "run-2", DeviceInput{
		DeviceName: "别的run设备", DeviceType: DeviceTypeSecurity,
	})
	if err != nil {
		t.Fatalf("CreateDevice run-2: %v", err)
	}

	if err := store.DeleteDevicesByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteDevicesByRun: %v", err)
	}
	records, total, err := service.ListDevices(context.Background(), "run-1", DeviceFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices run-1 after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("run-1 after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if device, err := store.GetDevice(context.Background(), "run-2", other.ID); err != nil || device.ID != other.ID {
		t.Fatalf("run-2 after cascade: device = %+v, err = %v; want untouched", device, err)
	}

	// 无报告可删不是错误。
	if err := store.DeleteDevicesByRun(context.Background(), "run-9"); err != nil {
		t.Fatalf("DeleteDevicesByRun on empty: %v", err)
	}
}

// ─── run 删除级联（与 drills.DeleteRun 接线）──────────────────────────

// 把 dispatch store 作为 drills 服务的 RunSessionCleaner 接线后，删除
// run 会级联清空其设备报告（与 DB 的 ON DELETE CASCADE 一致）；其他 run
// 的报告保留。
func TestDeleteRunCascadesToDevices(t *testing.T) {
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
	if _, err := dispatchService.CreateDevice(context.Background(), runA.ID, DeviceInput{
		DeviceName: "1号配电柜", DeviceType: DeviceTypePowerSupply,
	}); err != nil {
		t.Fatalf("CreateDevice runA: %v", err)
	}
	if _, err := dispatchService.CreateDevice(context.Background(), runA.ID, DeviceInput{
		DeviceName: "2号消防栓", DeviceType: DeviceTypeFire, Status: DeviceStatusWarning,
	}); err != nil {
		t.Fatalf("CreateDevice runA second: %v", err)
	}
	deviceB, err := dispatchService.CreateDevice(context.Background(), runB.ID, DeviceInput{
		DeviceName: "B 的设备", DeviceType: DeviceTypeSecurity,
	})
	if err != nil {
		t.Fatalf("CreateDevice runB: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), runA.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}

	// runA 的报告清空（run 已删除，直接用 store 断言）。
	records, total, err := dispatchStore.ListDevices(context.Background(), runA.ID, DeviceFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListDevices runA after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("runA after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if _, err := dispatchStore.GetDevice(context.Background(), runA.ID, "any"); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("runA device after cascade: err = %v, want ErrDeviceNotFound", err)
	}

	// runB 的报告保留。
	if device, err := dispatchStore.GetDevice(context.Background(), runB.ID, deviceB.ID); err != nil || device.ID != deviceB.ID {
		t.Fatalf("runB after cascade: device = %+v, err = %v; want untouched", device, err)
	}
}

// 未接线 cleaner 时删除 run 不触碰设备报告（最小增量扩展，未接线行为不
// 变）。
func TestDeleteRunWithoutCleanerKeepsDevices(t *testing.T) {
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
	device, err := dispatchService.CreateDevice(context.Background(), run.ID, DeviceInput{
		DeviceName: "保留的设备", DeviceType: DeviceTypeElevator,
	})
	if err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}
	if got, err := dispatchStore.GetDevice(context.Background(), run.ID, device.ID); err != nil || got.ID != device.ID {
		t.Fatalf("device after un-wired delete: got = %+v, err = %v; want untouched", got, err)
	}
}
