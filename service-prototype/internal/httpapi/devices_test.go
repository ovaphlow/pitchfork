package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// devicesPath builds the device collection path of a run.
func devicesPath(runID string) string {
	return fmt.Sprintf("%s/%s/devices", runsPath, runID)
}

// deviceItemPath builds the device item path of a (run, report) pair.
func deviceItemPath(runID, did string) string {
	return devicesPath(runID) + "/" + did
}

// deviceJSON mirrors the device response for assertions.
type deviceJSON struct {
	ID         string `json:"id"`
	RunID      string `json:"run_id"`
	DeviceName string `json:"device_name"`
	DeviceType string `json:"device_type"`
	Status     string `json:"status"`
	Note       string `json:"note"`
	CreatedBy  string `json:"created_by"`
	CreatedAt  string `json:"created_at"`
	UpdatedAt  string `json:"updated_at"`
}

type deviceListJSON struct {
	Records []deviceJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeDevice(t *testing.T, recorder *httptest.ResponseRecorder) deviceJSON {
	t.Helper()
	var device deviceJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &device); err != nil {
		t.Fatalf("body %q is not a device JSON: %v", recorder.Body.String(), err)
	}
	return device
}

func decodeDeviceList(t *testing.T, recorder *httptest.ResponseRecorder) deviceListJSON {
	t.Helper()
	var list deviceListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// validDeviceBody is a minimal valid device status report body.
const validDeviceBody = `{"device_name":"1号配电柜","device_type":"供配电"}`

// reportDevice posts the given body to the run's collection and asserts
// 201; returns the created report.
func reportDevice(t *testing.T, handler http.Handler, runID, body string) deviceJSON {
	t.Helper()
	if body == "" {
		body = validDeviceBody
	}
	recorder := do(handler, http.MethodPost, devicesPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeDevice(t, recorder)
}

// ─── POST /drills/{rid}/devices ──────────────────────────────────────

// 合法上报：201，id 为服务端生成的 26 位 Crockford Base32 ULID，run_id
// 取自路径回显（body 传入 run_id/id 被忽略），device_name/device_type 回
// 显，status 缺省 正常、note 缺省 ”，created_by 透传，created_at/updated_at
// 服务端设置。
func TestReportDeviceSuccess(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, devicesPath(run.ID),
		`{"device_name":"1号配电柜","device_type":"供配电","run_id":"ignored","id":"FAKE-ID","created_by":"u-iot-bridge"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	device := decodeDevice(t, recorder)
	if !ulidPattern.MatchString(device.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", device.ID)
	}
	if device.RunID != run.ID {
		t.Fatalf("run_id = %q, want the run from the path (body run_id ignored)", device.RunID)
	}
	if device.DeviceName != "1号配电柜" || device.DeviceType != "供配电" {
		t.Fatalf("device_name/device_type not echoed: %+v", device)
	}
	// status 省略时缺省 正常。
	if device.Status != "正常" {
		t.Fatalf("status = %q, want the default 正常", device.Status)
	}
	if device.Note != "" {
		t.Fatalf("note = %q, want an empty default", device.Note)
	}
	if device.CreatedBy != "u-iot-bridge" {
		t.Fatalf("created_by = %q, want u-iot-bridge", device.CreatedBy)
	}
	if device.CreatedAt == "" || device.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", device)
	}
}

// 显式字段：status=告警/离线、note、created_by 原样回显；created_by 省略
// 时空串。
func TestReportDeviceExplicitFieldsAndDefaults(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, devicesPath(run.ID),
		`{"device_name":"东区消防栓","device_type":"消防","status":"告警","note":"水压不足","created_by":"u-iot-bridge"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	device := decodeDevice(t, recorder)
	if device.Status != "告警" || device.Note != "水压不足" || device.CreatedBy != "u-iot-bridge" {
		t.Fatalf("explicit fields not echoed: %+v", device)
	}

	// 缺省口径：created_by/note 缺省空串、status 缺省 正常。
	recorder = do(handler, http.MethodPost, devicesPath(run.ID), validDeviceBody)
	device = decodeDevice(t, recorder)
	if device.Status != "正常" || device.Note != "" || device.CreatedBy != "" {
		t.Fatalf("defaults = %+v, want status 正常 / note '' / created_by ''", device)
	}
}

// 失败路径：缺 device_name（含空白）、缺 device_type、非法 device_type/
// status、空 body、畸形 body → 400，错误体统一 {"error": ...}。
func TestReportDeviceFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for name, body := range map[string]string{
		"missing device_name": `{"device_type":"供配电"}`,
		"blank device_name":   `{"device_name":"  ","device_type":"供配电"}`,
		"missing device_type": `{"device_name":"1号配电柜"}`,
		"invalid device_type": `{"device_name":"1号配电柜","device_type":"门禁"}`,
		"invalid status":      `{"device_name":"1号配电柜","device_type":"供配电","status":"离线中"}`,
		"malformed body":      `{"device_name":`,
	} {
		recorder := do(handler, http.MethodPost, devicesPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 空 body → 400。
	recorder := do(handler, http.MethodPost, devicesPath(run.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("empty body: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 状态约束：run 不存在 404（优先于门控）；仅 进行中 可上报（未开始/
// 已完成 400）。
func TestReportDeviceRunState(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	// run 不存在 → 404。
	recorder := do(handler, http.MethodPost, devicesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), validDeviceBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 未开始 → 400。
	notStarted := createRun(t, handler, scenario.ID, "")
	recorder = do(handler, http.MethodPost, devicesPath(notStarted.ID), validDeviceBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 进行中 → 201。
	inProgress := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, inProgress.ID)
	recorder = do(handler, http.MethodPost, devicesPath(inProgress.ID), validDeviceBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("进行中 run: status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}

	// 已完成 → 400。
	completed := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, completed.ID)
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPost, devicesPath(completed.ID), validDeviceBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/devices ───────────────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}；run 不存在 404。
func TestListDevicesEmptyAndMissingRun(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, devicesPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeDeviceList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("empty list = %+v, want records [] and total 0", list)
	}

	recorder = do(handler, http.MethodGet, devicesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 排序 created_at ASC, id ASC（最早上报在前）；device_type/status 精确匹
// 配筛选生效；meta.total 为筛选后的总数。
func TestListDevicesSortedAndFiltered(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// 依次上报：供配电 正常 → 消防 告警 → 供配电 离线。
	first := reportDevice(t, handler, run.ID, `{"device_name":"1号配电柜","device_type":"供配电"}`)
	time.Sleep(5 * time.Millisecond)
	second := reportDevice(t, handler, run.ID, `{"device_name":"东区消防栓","device_type":"消防","status":"告警","note":"水压不足"}`)
	time.Sleep(5 * time.Millisecond)
	third := reportDevice(t, handler, run.ID, `{"device_name":"2号配电柜","device_type":"供配电","status":"离线","note":"断电"}`)

	recorder := do(handler, http.MethodGet, devicesPath(run.ID), "")
	list := decodeDeviceList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	// created_at ASC：一号、二号、三号。
	if list.Records[0].ID != first.ID || list.Records[1].ID != second.ID || list.Records[2].ID != third.ID {
		t.Fatalf("records not in created_at ASC order: %+v", list.Records)
	}

	// device_type 筛选：供配电 → 一号、三号（不含二号）。
	recorder = do(handler, http.MethodGet, devicesPath(run.ID)+"?device_type="+"供配电", "")
	list = decodeDeviceList(t, recorder)
	if list.Meta.Total != 2 || len(list.Records) != 2 || list.Records[0].ID != first.ID || list.Records[1].ID != third.ID {
		t.Fatalf("device_type filter: records = %d, total = %d; want 2 / 2", len(list.Records), list.Meta.Total)
	}

	// status 筛选：告警 → 仅二号，total=1。
	recorder = do(handler, http.MethodGet, devicesPath(run.ID)+"?status="+"告警", "")
	list = decodeDeviceList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != second.ID {
		t.Fatalf("status filter: records = %d, total = %d; want 1 / 1", len(list.Records), list.Meta.Total)
	}

	// 组合筛选：供配电 + 离线 → 仅三号。
	recorder = do(handler, http.MethodGet, devicesPath(run.ID)+"?device_type="+"供配电"+"&status="+"离线", "")
	list = decodeDeviceList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != third.ID {
		t.Fatalf("combined filter: records = %d, total = %d; want 1 / 1", len(list.Records), list.Meta.Total)
	}
}

// limit/offset 分页生效（缺省 limit 50，meta.total 保持总数）；非法
// limit/offset → 400；非法 device_type/status 筛选值 → 400。
func TestListDevicesPaginationAndInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		reportDevice(t, handler, run.ID, fmt.Sprintf(
			`{"device_name":"设备%03d","device_type":"供配电"}`, i))
	}

	recorder := do(handler, http.MethodGet, devicesPath(run.ID)+"?limit=2&offset=0", "")
	list := decodeDeviceList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	// created_at ASC：最早上报的（设备001、002）排在最前。
	if list.Records[0].DeviceName != "设备001" || list.Records[1].DeviceName != "设备002" {
		t.Fatalf("first page not in created_at ASC order: %+v", list.Records)
	}

	recorder = do(handler, http.MethodGet, devicesPath(run.ID)+"?limit=2&offset=52", "")
	list = decodeDeviceList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d; want 1 / 53", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, devicesPath(run.ID), "")
	list = decodeDeviceList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid limit":       "?limit=abc",
		"negative limit":      "?limit=-1",
		"invalid offset":      "?offset=-2",
		"invalid device_type": "?device_type=门禁",
		"invalid status":      "?status=离线中",
	} {
		recorder := do(handler, http.MethodGet, devicesPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/devices/{did} ─────────────────────────────────

// 存在的 (run, did) 返回 200 完整对象（含 run_id/created_by，id 为
// ULID）；did 不存在、报告不属于该 run、run 不存在 → 404 {error}；GET
// 不受写门控（已完成 run 的报告仍 200）。
func TestGetDevice(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	device := reportDevice(t, handler, runA.ID, `{"device_name":"1号配电柜","device_type":"供配电","created_by":"u-iot-bridge"}`)

	recorder := do(handler, http.MethodGet, deviceItemPath(runA.ID, device.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	got := decodeDevice(t, recorder)
	if !ulidPattern.MatchString(got.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", got.ID)
	}
	if got.ID != device.ID || got.RunID != runA.ID || got.DeviceName != "1号配电柜" ||
		got.DeviceType != "供配电" || got.CreatedBy != "u-iot-bridge" {
		t.Fatalf("get does not return the full object: %+v", got)
	}

	recorder = do(handler, http.MethodGet, deviceItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown did: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, deviceItemPath(runB.ID, device.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("report of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, deviceItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", device.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// GET 不受写门控：已完成 run 的报告仍 200。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	deviceCompletedRun := reportDevice(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, deviceItemPath(completed.ID, deviceCompletedRun.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, devicesPath(completed.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── PUT /drills/{rid}/devices/{did} ─────────────────────────────────

// 更新 device_name/device_type/status/note 生效；id/run_id/created_by/
// created_at 保留创建时值不回改、updated_at 刷新；PUT 后 GET 反映更新。
func TestPutDevice(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := reportDevice(t, handler, run.ID,
		`{"device_name":"1号配电柜","device_type":"供配电","status":"告警","note":"电压波动","created_by":"u-iot-bridge"}`)

	time.Sleep(5 * time.Millisecond)
	recorder := do(handler, http.MethodPut, deviceItemPath(run.ID, created.ID),
		`{"device_name":"1号配电柜（北区）","device_type":"其他","status":"离线","note":"断电","created_by":"ignored"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeDevice(t, recorder)
	if updated.DeviceName != "1号配电柜（北区）" || updated.DeviceType != "其他" ||
		updated.Status != "离线" || updated.Note != "断电" {
		t.Fatalf("updated fields not applied: %+v", updated)
	}
	if updated.ID != created.ID || updated.RunID != run.ID {
		t.Fatalf("id/run_id must be preserved: %+v", updated)
	}
	if updated.CreatedAt != created.CreatedAt {
		t.Fatalf("created_at must be preserved: %+v", updated)
	}
	if updated.CreatedBy != "u-iot-bridge" {
		t.Fatalf("created_by must be preserved (body created_by ignored): %q", updated.CreatedBy)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must be refreshed: %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, deviceItemPath(run.ID, created.ID), "")
	got := decodeDevice(t, recorder)
	if got.DeviceName != "1号配电柜（北区）" || got.Status != "离线" || got.Note != "断电" {
		t.Fatalf("GET after PUT must reflect the update: %+v", got)
	}
}

// 失败路径（与 POST 一致覆盖）：缺 device_name（含空白）、缺 device_type、
// 非法 device_type/status → 400；did 不存在 404；run 不存在 404（优先于
// 门控）；非 进行中 400。
func TestPutDeviceFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := reportDevice(t, handler, run.ID, "")

	for name, body := range map[string]string{
		"missing device_name": `{"device_type":"供配电"}`,
		"blank device_name":   `{"device_name":"  ","device_type":"供配电"}`,
		"missing device_type": `{"device_name":"1号配电柜"}`,
		"invalid device_type": `{"device_name":"1号配电柜","device_type":"门禁"}`,
		"invalid status":      `{"device_name":"1号配电柜","device_type":"供配电","status":"离线中"}`,
		"empty body":          ``,
	} {
		recorder := do(handler, http.MethodPut, deviceItemPath(run.ID, created.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// did 不存在 → 404。
	recorder := do(handler, http.MethodPut, deviceItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), validDeviceBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown did PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// run 不存在 → 404。
	recorder = do(handler, http.MethodPut, deviceItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", created.ID), validDeviceBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 非 进行中 → 400。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	deviceCompleted := reportDevice(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPut, deviceItemPath(completed.ID, deviceCompleted.ID), validDeviceBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("PUT on 已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/devices/{did} ──────────────────────────────

// DELETE 204；DELETE 后 GET 404；did 不存在 404；run 不存在 404（优先
// 于门控）；非 进行中 DELETE 400。
func TestDeleteDevice(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	device := reportDevice(t, handler, run.ID, "")

	recorder := do(handler, http.MethodDelete, deviceItemPath(run.ID, device.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, deviceItemPath(run.ID, device.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// did 不存在 → 404。
	recorder = do(handler, http.MethodDelete, deviceItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown did DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// run 不存在 → 404。
	recorder = do(handler, http.MethodDelete, deviceItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", device.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 非 进行中 → 400。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	deviceCompleted := reportDevice(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, deviceItemPath(completed.ID, deviceCompleted.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE on 已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 级联：删除 run 后设备报告随之清空 ─────────────────────────────────

// 创建报告后 DELETE run（runs 路由），再 GET 单条返回 404、列表为空；
// 其他 run 的报告保留（内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesDevices(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	deviceA := reportDevice(t, handler, runA.ID, `{"device_name":"1号配电柜","device_type":"供配电"}`)
	deviceB := reportDevice(t, handler, runB.ID, `{"device_name":"东区消防栓","device_type":"消防"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+runA.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, deviceItemPath(runA.ID, deviceA.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET report after run delete: status = %d, want 404 (cascade)", recorder.Code)
	}
	decodeError(t, recorder)

	// runB 的报告保留。
	recorder = do(handler, http.MethodGet, deviceItemPath(runB.ID, deviceB.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET report of the other run: status = %d, want 200", recorder.Code)
	}
}

// ─── 405 与 Allow ────────────────────────────────────────────────────

// 集合只允许 GET/POST、条目只允许 GET/PUT/DELETE：其他方法 405 且带
// Allow 头，响应体为 JSON 错误。
func TestDevicesMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	device := reportDevice(t, handler, run.ID, "")

	for _, testCase := range []struct {
		name   string
		method string
		target string
		allow  string
	}{
		{"collection PUT", http.MethodPut, devicesPath(run.ID), "GET, POST"},
		{"collection DELETE", http.MethodDelete, devicesPath(run.ID), "GET, POST"},
		{"collection PATCH", http.MethodPatch, devicesPath(run.ID), "GET, POST"},
		{"item POST", http.MethodPost, deviceItemPath(run.ID, device.ID), "GET, PUT, DELETE"},
		{"item PATCH", http.MethodPatch, deviceItemPath(run.ID, device.ID), "GET, PUT, DELETE"},
	} {
		recorder := do(handler, testCase.method, testCase.target, "")
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s: status = %d, want 405; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		if allow := recorder.Header().Get("Allow"); allow != testCase.allow {
			t.Fatalf("%s: Allow = %q, want %q", testCase.name, allow, testCase.allow)
		}
		decodeError(t, recorder)
	}
}

// ─── CORS 预检 ───────────────────────────────────────────────────────

// 对 devices 路径的 OPTIONS 预检：204 且 Access-Control-Allow-Methods 含
// POST/PUT/DELETE（写方法可被浏览器调用）。
func TestDevicesCORSPreflight(t *testing.T) {
	req := httptest.NewRequest(http.MethodOptions, devicesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), nil)
	req.Header.Set("Origin", "https://allowed.example")
	recorder := httptest.NewRecorder()
	testMux([]string{"https://allowed.example"}).ServeHTTP(recorder, req)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("preflight status = %d, want 204", recorder.Code)
	}
	methods := recorder.Header().Get("Access-Control-Allow-Methods")
	for _, method := range []string{"POST", "PUT", "DELETE"} {
		if !strings.Contains(methods, method) {
			t.Fatalf("Access-Control-Allow-Methods = %q, want it to contain %s", methods, method)
		}
	}
}
