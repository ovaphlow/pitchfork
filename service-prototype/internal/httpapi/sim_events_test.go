package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// simEventsPath builds the sim event collection path of a run.
func simEventsPath(runID string) string {
	return fmt.Sprintf("%s/%s/sim-events", runsPath, runID)
}

// simEventItemPath builds the sim event item path of a (run, event)
// pair.
func simEventItemPath(runID, eventID string) string {
	return simEventsPath(runID) + "/" + eventID
}

// simEventJSON mirrors the sim event response for assertions.
type simEventJSON struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	EventType   string         `json:"event_type"`
	Payload     map[string]any `json:"payload"`
	Status      string         `json:"status"`
	TriggeredAt *string        `json:"triggered_at"`
	HandledAt   *string        `json:"handled_at"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

type simEventListJSON struct {
	Records []simEventJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeSimEvent(t *testing.T, recorder *httptest.ResponseRecorder) simEventJSON {
	t.Helper()
	var event simEventJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &event); err != nil {
		t.Fatalf("body %q is not a sim event JSON: %v", recorder.Body.String(), err)
	}
	return event
}

func decodeSimEventList(t *testing.T, recorder *httptest.ResponseRecorder) simEventListJSON {
	t.Helper()
	var list simEventListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// startRun moves the run into 进行中 via the state machine route,
// failing the test unless the transition returns 200.
func startRun(t *testing.T, handler http.Handler, runID string) {
	t.Helper()
	recorder := do(handler, http.MethodPost, runsPath+"/"+runID+"/start", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
}

// mustCreateInProgressRun creates a scenario-backed run and starts it,
// returning the run.
func mustCreateInProgressRun(t *testing.T, handler http.Handler, scenarioBody string) runJSON {
	t.Helper()
	scenario := createScenario(t, handler, scenarioBody)
	run := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, run.ID)
	return run
}

// createSimEvent posts the given body to the run's collection and
// asserts 201; returns the created event.
func createSimEvent(t *testing.T, handler http.Handler, runID, body string) simEventJSON {
	t.Helper()
	if body == "" {
		body = `{"event_type":"客流密度超阈值"}`
	}
	recorder := do(handler, http.MethodPost, simEventsPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeSimEvent(t, recorder)
}

// ─── POST /drills/{rid}/sim-events ───────────────────────────────────

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// run_id/event_type 回显，payload 缺省 {}、status 缺省 已触发、
// triggered_at 服务端设置非空、handled_at 为 null、created_by 透传
// （缺省空串）、created_at/updated_at 服务端设置。
func TestCreateSimEventSuccess(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, simEventsPath(run.ID),
		`{"event_type":"客流密度超阈值","payload":{"count":120},"created_by":"u-admin"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	event := decodeSimEvent(t, recorder)
	if !ulidPattern.MatchString(event.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", event.ID)
	}
	if event.RunID != run.ID || event.EventType != "客流密度超阈值" {
		t.Fatalf("create does not echo the input: %+v", event)
	}
	if len(event.Payload) != 1 || event.Payload["count"] != float64(120) {
		t.Fatalf("payload = %v, want the request payload echoed", event.Payload)
	}
	if event.Status != "已触发" {
		t.Fatalf("status = %q, want 已触发 (default)", event.Status)
	}
	if event.TriggeredAt == nil || *event.TriggeredAt == "" {
		t.Fatalf("triggered_at = %v, want a non-empty server-set instant", event.TriggeredAt)
	}
	if event.HandledAt != nil {
		t.Fatalf("handled_at = %v, want null for 已触发", event.HandledAt)
	}
	if event.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", event.CreatedBy)
	}
	if event.CreatedAt == "" || event.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", event)
	}
}

// 缺省创建：payload 缺省 {}、status 缺省 已触发、created_by 缺省空串。
func TestCreateSimEventDefaults(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	event := createSimEvent(t, handler, run.ID, "")
	if len(event.Payload) != 0 {
		t.Fatalf("payload = %v, want an empty object when omitted", event.Payload)
	}
	if event.Status != "已触发" {
		t.Fatalf("status = %q, want 已触发 (default)", event.Status)
	}
	if event.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", event.CreatedBy)
	}
	if event.TriggeredAt == nil {
		t.Fatalf("triggered_at must be set by the service at creation")
	}
}

// 显式 status=已处置：服务端同时设置 handled_at（triggered_at 依然设置）。
func TestCreateSimEventExplicitHandled(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, simEventsPath(run.ID),
		`{"event_type":"客流密度超阈值","status":"已处置"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	event := decodeSimEvent(t, recorder)
	if event.Status != "已处置" {
		t.Fatalf("status = %q, want 已处置", event.Status)
	}
	if event.HandledAt == nil || *event.HandledAt == "" {
		t.Fatalf("handled_at = %v, want set when status is 已处置 at creation", event.HandledAt)
	}
	if event.TriggeredAt == nil {
		t.Fatalf("triggered_at must be set at creation")
	}
}

// 失败路径：缺 event_type / 非法 event_type / 非法 status / payload 非
// JSON 对象（数组、字符串、null、数字）/ 请求体非法 → 400 {error}。
func TestCreateSimEventFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for name, body := range map[string]string{
		"missing event_type": `{"payload":{}}`,
		"blank event_type":   `{"event_type":" "}`,
		"invalid event_type": `{"event_type":"不存在的类型"}`,
		"invalid status":     `{"event_type":"客流密度超阈值","status":"草稿"}`,
		"payload array":      `{"event_type":"客流密度超阈值","payload":[1,2]}`,
		"payload string":     `{"event_type":"客流密度超阈值","payload":"boom"}`,
		"payload number":     `{"event_type":"客流密度超阈值","payload":123}`,
		"payload null":       `{"event_type":"客流密度超阈值","payload":null}`,
		"malformed body":     `{"event_type":`,
	} {
		recorder := do(handler, http.MethodPost, simEventsPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 类型-分类映射（POST 入口）：大客流聚集→客流密度超阈值、停电与基础设
// 施→供配电异常报警、火灾→烟感探测器触发、气象灾害→气象预警接收；其他
// 任意场景可用；不匹配 400。
func TestCreateSimEventCategoryMapping(t *testing.T) {
	handler := testMux(nil)

	// 每个分类 × 全部事件类型：匹配的 201、不匹配的 400、其他 总是 201。
	cases := []struct {
		category string
		match    string
	}{
		{"大客流聚集", "客流密度超阈值"},
		{"停电与基础设施", "供配电异常报警"},
		{"火灾", "烟感探测器触发"},
		{"气象灾害", "气象预警接收"},
	}
	allTypes := []string{"客流密度超阈值", "供配电异常报警", "烟感探测器触发", "气象预警接收", "其他"}
	for _, testCase := range cases {
		run := mustCreateInProgressRun(t, handler, fmt.Sprintf(
			`{"name":"演练","category":%q,"background":"背景"}`, testCase.category))
		for _, eventType := range allTypes {
			recorder := do(handler, http.MethodPost, simEventsPath(run.ID),
				fmt.Sprintf(`{"event_type":%q}`, eventType))
			if eventType == testCase.match || eventType == "其他" {
				if recorder.Code != http.StatusCreated {
					t.Fatalf("category %q event_type %q: status = %d, want 201; body = %s",
						testCase.category, eventType, recorder.Code, recorder.Body.String())
				}
			} else if recorder.Code != http.StatusBadRequest {
				t.Fatalf("category %q event_type %q: status = %d, want 400; body = %s",
					testCase.category, eventType, recorder.Code, recorder.Body.String())
			} else {
				decodeError(t, recorder)
			}
		}
	}
}

// 状态约束：run 不存在 404；仅 进行中 可 POST（未开始/已完成 400）。
func TestCreateSimEventRunState(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	// run 不存在 → 404。
	recorder := do(handler, http.MethodPost, simEventsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"event_type":"客流密度超阈值"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 未开始 → 400。
	notStarted := createRun(t, handler, scenario.ID, "")
	recorder = do(handler, http.MethodPost, simEventsPath(notStarted.ID), `{"event_type":"客流密度超阈值"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 进行中 → 201。
	inProgress := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, inProgress.ID)
	recorder = do(handler, http.MethodPost, simEventsPath(inProgress.ID), `{"event_type":"客流密度超阈值"}`)
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
	recorder = do(handler, http.MethodPost, simEventsPath(completed.ID), `{"event_type":"客流密度超阈值"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/sim-events ────────────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}；run 不存在 404。
func TestListSimEventsEmptyAndMissingRun(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, simEventsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeSimEventList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("empty list = %+v, want records [] and total 0", list)
	}

	recorder = do(handler, http.MethodGet, simEventsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 排序 created_at ASC；event_type/status 筛选生效（组合也生效）；
// meta.total 为筛选后的总数。
func TestListSimEventsSortedAndFiltered(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// 创建 3 个事件：已触发(客流)、已处置(客流)、已触发(其他)。
	createSimEvent(t, handler, run.ID, `{"event_type":"客流密度超阈值"}`)
	time.Sleep(5 * time.Millisecond)
	handled := createSimEvent(t, handler, run.ID, `{"event_type":"客流密度超阈值","status":"已处置"}`)
	time.Sleep(5 * time.Millisecond)
	createSimEvent(t, handler, run.ID, `{"event_type":"其他","payload":{"note":"临时"}}`)

	recorder := do(handler, http.MethodGet, simEventsPath(run.ID), "")
	list := decodeSimEventList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].EventType != "客流密度超阈值" || list.Records[1].ID != handled.ID || list.Records[2].EventType != "其他" {
		t.Fatalf("records not in created_at ASC order: %+v", list.Records)
	}

	cases := []struct {
		name  string
		query string
		total int
		types []string
	}{
		{"by event_type", "?event_type=" + "客流密度超阈值", 2, []string{"客流密度超阈值", "客流密度超阈值"}},
		{"by status", "?status=" + "已处置", 1, []string{"客流密度超阈值"}},
		{"combined", "?event_type=" + "客流密度超阈值" + "&status=" + "已触发", 1, []string{"客流密度超阈值"}},
		{"no match", "?event_type=" + "气象预警接收", 0, nil},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodGet, simEventsPath(run.ID)+testCase.query, "")
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		list := decodeSimEventList(t, recorder)
		if list.Meta.Total != testCase.total || len(list.Records) != len(testCase.types) {
			t.Fatalf("%s: records = %d, total = %d; want %d / %d",
				testCase.name, len(list.Records), list.Meta.Total, len(testCase.types), testCase.total)
		}
		for i, eventType := range testCase.types {
			if list.Records[i].EventType != eventType {
				t.Fatalf("%s: records[%d].event_type = %q, want %q", testCase.name, i, list.Records[i].EventType, eventType)
			}
		}
	}
}

// limit/offset 分页生效（缺省 limit 50，meta.total 保持筛选后的总数）；
// 非法枚举筛选或非法 limit/offset → 400。
func TestListSimEventsPaginationAndInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		createSimEvent(t, handler, run.ID, fmt.Sprintf(`{"event_type":"客流密度超阈值","payload":{"seq":%d}}`, i))
	}

	recorder := do(handler, http.MethodGet, simEventsPath(run.ID)+"?limit=2&offset=0", "")
	list := decodeSimEventList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, simEventsPath(run.ID)+"?limit=2&offset=52", "")
	list = decodeSimEventList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d; want 1 / 53", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, simEventsPath(run.ID), "")
	list = decodeSimEventList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid event_type": "?event_type=" + "不存在的类型",
		"invalid status":     "?status=" + "草稿",
		"invalid limit":      "?limit=abc",
		"negative limit":     "?limit=-1",
		"invalid offset":     "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, simEventsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/sim-events/{eid} ──────────────────────────────

// 存在的 (run, eid) 返回 200 完整对象；eid 不存在、事件不属于该 run、
// run 不存在 → 404 {error}。
func TestGetSimEvent(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	event := createSimEvent(t, handler, runA.ID, `{"event_type":"客流密度超阈值","payload":{"count":88},"created_by":"u-admin"}`)

	recorder := do(handler, http.MethodGet, simEventItemPath(runA.ID, event.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	got := decodeSimEvent(t, recorder)
	if got.ID != event.ID || got.RunID != runA.ID || got.EventType != "客流密度超阈值" || got.Status != "已触发" {
		t.Fatalf("get does not return the full object: %+v", got)
	}
	if got.TriggeredAt == nil || len(got.Payload) != 1 || got.Payload["count"] != float64(88) {
		t.Fatalf("get must return triggered_at and payload: %+v", got)
	}

	recorder = do(handler, http.MethodGet, simEventItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown eid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, simEventItemPath(runB.ID, event.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("event of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, simEventItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", event.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── PUT /drills/{rid}/sim-events/{eid} ──────────────────────────────

// 部分更新：省略字段保留原值；status→已处置 设置 handled_at、改回 已触发
// 置 null；triggered_at/created_at 保持不变、updated_at 刷新；PUT 后
// GET 反映更新。
func TestPutSimEventPartialUpdate(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	event := createSimEvent(t, handler, run.ID, `{"event_type":"客流密度超阈值","payload":{"count":120}}`)

	// status→已处置：handled_at 设置，其余字段保留。
	time.Sleep(5 * time.Millisecond)
	recorder := do(handler, http.MethodPut, simEventItemPath(run.ID, event.ID), `{"status":"已处置"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeSimEvent(t, recorder)
	if updated.Status != "已处置" || updated.HandledAt == nil || *updated.HandledAt == "" {
		t.Fatalf("updated = %+v, want status 已处置 with handled_at", updated)
	}
	if updated.EventType != "客流密度超阈值" || len(updated.Payload) != 1 || updated.Payload["count"] != float64(120) {
		t.Fatalf("omitted fields must keep their values: %+v", updated)
	}
	if updated.TriggeredAt == nil || *updated.TriggeredAt != *event.TriggeredAt {
		t.Fatalf("triggered_at must be preserved: %+v", updated)
	}
	if updated.CreatedAt != event.CreatedAt {
		t.Fatalf("created_at must be preserved: %+v", updated)
	}
	if updated.UpdatedAt == event.UpdatedAt {
		t.Fatalf("updated_at must be refreshed: %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, simEventItemPath(run.ID, event.ID), "")
	got := decodeSimEvent(t, recorder)
	if got.Status != "已处置" || got.HandledAt == nil {
		t.Fatalf("GET after PUT must reflect the update: %+v", got)
	}

	// 改回 已触发：handled_at 置 null。
	recorder = do(handler, http.MethodPut, simEventItemPath(run.ID, event.ID), `{"status":"已触发"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT back status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeSimEvent(t, recorder)
	if updated.Status != "已触发" || updated.HandledAt != nil {
		t.Fatalf("updated = %+v, want status 已触发 with null handled_at", updated)
	}

	// 更新 payload 与 event_type（其他 对本分类合法）。
	recorder = do(handler, http.MethodPut, simEventItemPath(run.ID, event.ID),
		`{"event_type":"其他","payload":{"note":"补充说明"}}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT fields status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeSimEvent(t, recorder)
	if updated.EventType != "其他" || len(updated.Payload) != 1 || updated.Payload["note"] != "补充说明" {
		t.Fatalf("updated = %+v, want the new event_type and payload", updated)
	}
}

// PUT 失败路径：非法 event_type / 非法 status / payload 非对象 / 分类不
// 匹配 → 400；事件不存在、事件不属于该 run、run 不存在 → 404；run 非
// 进行中 → 400。错误响应体统一 {error}。
func TestPutSimEventFailures(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	event := createSimEvent(t, handler, runA.ID, "")

	for name, body := range map[string]string{
		"invalid event_type": `{"event_type":"不存在的类型"}`,
		"invalid status":     `{"status":"草稿"}`,
		"payload array":      `{"payload":[1,2]}`,
		"payload string":     `{"payload":"boom"}`,
		"payload null":       `{"payload":null}`,
		"category mismatch":  `{"event_type":"烟感探测器触发"}`,
		"malformed body":     `{"status":`,
	} {
		recorder := do(handler, http.MethodPut, simEventItemPath(runA.ID, event.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 404 路径：未知 eid / 其他 run 的事件 / run 不存在。
	recorder := do(handler, http.MethodPut, simEventItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"status":"已处置"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown eid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodPut, simEventItemPath(runB.ID, event.ID), `{"status":"已处置"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("event of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodPut, simEventItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", event.ID), `{"status":"已处置"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 状态约束：未开始 run → 400。
	notStarted := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	recorder = do(handler, http.MethodPut, simEventItemPath(notStarted.ID, event.ID), `{"status":"已处置"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/sim-events/{eid} ───────────────────────────

// 删除 204；DELETE 后 GET 404；事件不属于该 run / run 不存在 404；run 非
// 进行中 400。
func TestDeleteSimEvent(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	event := createSimEvent(t, handler, runA.ID, "")

	recorder := do(handler, http.MethodDelete, simEventItemPath(runA.ID, event.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, simEventItemPath(runA.ID, event.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// runA 的第二个事件：对 runB 路径删除 → 404（事件不属于该 run）。
	eventA2 := createSimEvent(t, handler, runA.ID, "")
	recorder = do(handler, http.MethodDelete, simEventItemPath(runB.ID, eventA2.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("event of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodDelete, simEventItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown eid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodDelete, simEventItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", eventA2.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 状态约束：未开始 run → 400。
	notStarted := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	recorder = do(handler, http.MethodDelete, simEventItemPath(notStarted.ID, eventA2.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 405 与 Allow ────────────────────────────────────────────────────

// 集合只允许 GET/POST、条目只允许 GET/PUT/DELETE：其他方法 405 且带
// Allow 头，响应体为 JSON 错误。
func TestSimEventsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	event := createSimEvent(t, handler, run.ID, "")

	for _, testCase := range []struct {
		name   string
		method string
		target string
		allow  string
	}{
		{"collection PUT", http.MethodPut, simEventsPath(run.ID), "GET, POST"},
		{"collection DELETE", http.MethodDelete, simEventsPath(run.ID), "GET, POST"},
		{"collection PATCH", http.MethodPatch, simEventsPath(run.ID), "GET, POST"},
		{"item POST", http.MethodPost, simEventItemPath(run.ID, event.ID), "GET, PUT, DELETE"},
		{"item PATCH", http.MethodPatch, simEventItemPath(run.ID, event.ID), "GET, PUT, DELETE"},
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
