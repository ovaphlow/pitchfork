package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// opinionEventPath is the item route of the opinion event of one run.
func opinionEventPath(runID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/opinion-event"
}

// opinionEventJSON mirrors the opinion event response for assertions.
type opinionEventJSON struct {
	ID         string         `json:"id"`
	RunID      string         `json:"run_id"`
	EventName  string         `json:"event_name"`
	Subject    string         `json:"subject"`
	Summary    string         `json:"summary"`
	OccurredAt *string        `json:"occurred_at"`
	Level      string         `json:"level"`
	Status     string         `json:"status"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
	CreatedAt  string         `json:"created_at"`
	UpdatedAt  string         `json:"updated_at"`
}

func decodeOpinionEvent(t *testing.T, recorder *httptest.ResponseRecorder) opinionEventJSON {
	t.Helper()
	var event opinionEventJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &event); err != nil {
		t.Fatalf("body %q is not an opinion event JSON: %v", recorder.Body.String(), err)
	}
	return event
}

// putOpinionEvent PUTs an opinion event body and asserts 200; returns the
// event.
func putOpinionEvent(t *testing.T, handler http.Handler, runID, body string) opinionEventJSON {
	t.Helper()
	recorder := do(handler, http.MethodPut, opinionEventPath(runID), body)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOpinionEvent(t, recorder)
}

// newOpinionEventRun creates a scenario and a run in 未开始 (writable);
// the caller transitions the run when the test needs another status.
func newOpinionEventRun(t *testing.T, handler http.Handler) runJSON {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	return createRun(t, handler, scenario.ID, "")
}

// ─── PUT /drills/{rid}/opinion-event ─────────────────────────────────

// 首次 PUT：200 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 来自路径（body 中出现的 run_id/id 被忽略），event_name
// 必填透传，subject/summary 透传可省略缺省 ”，level 缺省 中热、status
// 缺省 监测中，occurred_at 缺省 null，metadata 缺省 {}、created_by 缺省
// ”，created_at/updated_at 服务端时间且相等。
func TestPutOpinionEventCreatesWithDefaults(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）；body 只
	// 给 event_name，其余全部缺省。
	recorder := do(handler, http.MethodPut, opinionEventPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID","event_name":"展厅舆情事件"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	event := decodeOpinionEvent(t, recorder)
	if !ulidPattern.MatchString(event.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", event.ID)
	}
	if event.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", event.RunID, run.ID)
	}
	if event.EventName != "展厅舆情事件" {
		t.Fatalf("event_name = %q, want the provided value", event.EventName)
	}
	if event.Subject != "" || event.Summary != "" || event.CreatedBy != "" {
		t.Fatalf("subject/summary/created_by = %q / %q / %q, want empty defaults", event.Subject, event.Summary, event.CreatedBy)
	}
	if event.OccurredAt != nil {
		t.Fatalf("occurred_at = %v, want the null default", event.OccurredAt)
	}
	if event.Level != "中热" {
		t.Fatalf("level = %q, want the default 中热", event.Level)
	}
	if event.Status != "监测中" {
		t.Fatalf("status = %q, want the default 监测中", event.Status)
	}
	if event.Metadata == nil || len(event.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", event.Metadata)
	}
	if event.CreatedAt == "" || event.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", event)
	}
	if event.CreatedAt != event.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", event.CreatedAt, event.UpdatedAt)
	}
}

// 显式字段原样回显：event_name/subject/summary/occurred_at（RFC3339 时间
// 串）/level 三种枚举/metadata/created_by 透传；occurred_at 显式 null
// 合法；subject/summary 显式空串合法。
func TestPutOpinionEventPassthrough(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)

	event := putOpinionEvent(t, handler, run.ID, `{"event_name":"展厅舆情事件","subject":"涉事主体","summary":"事件概述","occurred_at":"2026-08-01T08:30:00Z","level":"高热","metadata":{"source":"merit"},"created_by":"u-admin"}`)
	if event.EventName != "展厅舆情事件" || event.Subject != "涉事主体" || event.Summary != "事件概述" ||
		event.OccurredAt == nil || *event.OccurredAt != "2026-08-01T08:30:00Z" ||
		event.Level != "高热" || event.Metadata["source"] != "merit" || event.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", event)
	}

	// level 三种枚举均可；occurred_at 显式 null 合法；subject/summary 空串合法。
	for _, level := range []string{"高热", "中热", "低热"} {
		got := putOpinionEvent(t, handler, run.ID, `{"event_name":"A","level":`+jsonString(level)+`}`)
		if got.Level != level {
			t.Fatalf("level %s: got %q", level, got.Level)
		}
	}
	got := putOpinionEvent(t, handler, run.ID, `{"event_name":"A","subject":"","summary":"","occurred_at":null}`)
	if got.Subject != "" || got.Summary != "" || got.OccurredAt != nil {
		t.Fatalf("empty subject/summary/null occurred_at = %+v", got)
	}
}

// 再次 PUT 原地更新：200 + 更新后对象，id/created_at 不变、updated_at 刷新；
// 全量覆盖（body 缺省字段重置为默认值）；随后 GET 反映更新。
func TestPutOpinionEventUpdatesInPlace(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)

	created := putOpinionEvent(t, handler, run.ID,
		`{"event_name":"展厅舆情事件","subject":"主体","summary":"概述","occurred_at":"2026-08-01T08:30:00Z","level":"高热","metadata":{"source":"merit"},"created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 updated_at 与 created_at 可区分（毫秒级分辨率）。
	time.Sleep(5 * time.Millisecond)

	// 再次 PUT 只改 event_name（其余缺省）：id/created_at 不变、updated_at
	// 刷新、缺省字段重置为默认值。
	updated := putOpinionEvent(t, handler, run.ID, `{"event_name":"更名后的事件"}`)
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}
	if updated.EventName != "更名后的事件" || updated.Subject != "" || updated.Summary != "" ||
		updated.OccurredAt != nil || updated.Level != "中热" || updated.CreatedBy != "" ||
		len(updated.Metadata) != 0 {
		t.Fatalf("replacement semantics = %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder := do(handler, http.MethodGet, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeOpinionEvent(t, recorder)
	if fetched.EventName != "更名后的事件" || fetched.Level != "中热" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}
}

// 状态机（PUT 入口）：首次创建仅接受 监测中（显式 已预警/已处置 400）；
// 相邻前进 监测中→已预警→已处置 合法；同值 no-op 合法；跳级（监测中→
// 已处置）与回退（已处置→已预警）400。
func TestPutOpinionEventStatusMachine(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)
	target := opinionEventPath(run.ID)

	// 首次创建显式 已预警/已处置 → 400。
	for _, status := range []string{"已预警", "已处置"} {
		recorder := do(handler, http.MethodPut, target, `{"event_name":"A","status":`+jsonString(status)+`}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("create with %s: status = %d, want 400; body = %s", status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
	// 创建（缺省 监测中）→ 已预警 → 已处置 → 已处置 no-op 均 200。
	putOpinionEvent(t, handler, run.ID, `{"event_name":"A"}`)
	event := putOpinionEvent(t, handler, run.ID, `{"event_name":"A","status":"已预警"}`)
	if event.Status != "已预警" {
		t.Fatalf("status = %q, want 已预警", event.Status)
	}
	event = putOpinionEvent(t, handler, run.ID, `{"event_name":"A","status":"已处置"}`)
	if event.Status != "已处置" {
		t.Fatalf("status = %q, want 已处置", event.Status)
	}
	event = putOpinionEvent(t, handler, run.ID, `{"event_name":"A","status":"已处置"}`)
	if event.Status != "已处置" {
		t.Fatalf("status = %q, want 已处置 (no-op)", event.Status)
	}

	// 跳级：监测中 → 已处置 400；回退：已处置 → 已预警 400、已处置 → 监测中
	// （body 缺省 status）400。
	skipped := newOpinionEventRun(t, handler)
	putOpinionEvent(t, handler, skipped.ID, `{"event_name":"A"}`)
	recorder := do(handler, http.MethodPut, opinionEventPath(skipped.ID), `{"event_name":"A","status":"已处置"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("skip 监测中 -> 已处置: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	for _, body := range []string{`{"event_name":"A","status":"已预警"}`, `{"event_name":"A"}`} {
		recorder := do(handler, http.MethodPut, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("backward %s: status = %d, want 400; body = %s", body, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 失败路径（400，响应体统一 { "error": ... }）：空/畸形 body（零字节、非
// JSON、null、字符串、数组）、缺 event_name、非法 level/status、occurred_at
// 非时间（非 RFC3339 或非字符串）、metadata 非 JSON 对象。
func TestPutOpinionEventInvalidBody(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)
	target := opinionEventPath(run.ID)

	for name, body := range map[string]string{
		"empty body":              "",
		"malformed JSON":          `{"event_name":`,
		"JSON string":             `"展厅舆情事件"`,
		"JSON array":              `[{"event_name":"A"}]`,
		"JSON null":               `null`,
		"missing event_name":      `{"level":"高热"}`,
		"empty event_name":        `{"event_name":""}`,
		"invalid level":           `{"event_name":"A","level":"爆热"}`,
		"numeric level":           `{"event_name":"A","level":1}`,
		"invalid status":          `{"event_name":"A","status":"已结束"}`,
		"numeric status":          `{"event_name":"A","status":1}`,
		"occurred_at not RFC3339": `{"event_name":"A","occurred_at":"2026-08-01"}`,
		"occurred_at not string":  `{"event_name":"A","occurred_at":123}`,
		"metadata not object":     `{"event_name":"A","metadata":[1]}`,
		"metadata string":         `{"event_name":"A","metadata":"x"}`,
	} {
		recorder := do(handler, http.MethodPut, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── run 不存在 / 未配置 ─────────────────────────────────────────────

// run 不存在：GET/PUT/DELETE 均 404，错误体统一 { "error": ... }。
func TestOpinionEventRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionEventPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionEventPath(missing), `{"event_name":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionEventPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// run 存在但事件未配置：GET/DELETE 均 404；PUT 则创建（200）。
func TestOpinionEventNotConfigured(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)

	recorder := do(handler, http.MethodGet, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionEventPath(run.ID), `{"event_name":"A"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT: status = %d, want 200 (create); body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── 写门控 ──────────────────────────────────────────────────────────

// run 状态 已完成/已终止 时 PUT/DELETE 均 400；未开始/进行中 可写；
// GET 不受写门控限制（run 存在且已配置即 200）。判定顺序：事件未配置
// 404 先于写门控 400（已完成 run 未配置 → DELETE 404）。
func TestOpinionEventWriteGate(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	notStarted := createRun(t, handler, scenario.ID, "")
	inProgress := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+inProgress.ID+"/start", "")
	completed := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	terminated := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/terminate", "")
	// 已配置事件的已完成/已终止 run（配置先行，再走到结束状态）。
	completedWithEvent := createRun(t, handler, scenario.ID, "")
	putOpinionEvent(t, handler, completedWithEvent.ID, `{"event_name":"A"}`)
	do(handler, http.MethodPost, runsPath+"/"+completedWithEvent.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+completedWithEvent.ID+"/complete", "")
	terminatedWithEvent := createRun(t, handler, scenario.ID, "")
	putOpinionEvent(t, handler, terminatedWithEvent.ID, `{"event_name":"A"}`)
	do(handler, http.MethodPost, runsPath+"/"+terminatedWithEvent.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+terminatedWithEvent.ID+"/terminate", "")

	// 已完成/已终止（未配置）→ PUT 400（写门控）、DELETE 404（未配置先于
	// 写门控判定）。
	for _, run := range []runJSON{completed, terminated} {
		recorder := do(handler, http.MethodPut, opinionEventPath(run.ID), `{"event_name":"A"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, opinionEventPath(run.ID), "")
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("DELETE on %s without event: status = %d, want 404; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 已完成/已终止（已配置）→ PUT/DELETE 均 400（写门控）。
	for _, run := range []runJSON{completedWithEvent, terminatedWithEvent} {
		recorder := do(handler, http.MethodPut, opinionEventPath(run.ID), `{"event_name":"B"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s with event: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, opinionEventPath(run.ID), "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("DELETE on %s with event: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 未开始/进行中 → 可写（PUT 200，DELETE 204）。
	putOpinionEvent(t, handler, notStarted.ID, `{"event_name":"A"}`)
	putOpinionEvent(t, handler, inProgress.ID, `{"event_name":"A"}`)
	recorder := do(handler, http.MethodDelete, opinionEventPath(notStarted.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 未开始: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, opinionEventPath(inProgress.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 进行中: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	// GET 不受写门控：已完成 run 已配置 → 200。
	recorder = do(handler, http.MethodGet, opinionEventPath(completedWithEvent.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成 with event: status = %d, want 200", recorder.Code)
	}
	fetched := decodeOpinionEvent(t, recorder)
	if fetched.EventName != "A" {
		t.Fatalf("event = %+v, want event_name A", fetched)
	}
}

// ─── GET /drills/{rid}/opinion-event ─────────────────────────────────

// 已配置 → 200 + 完整对象；DELETE 204 后 GET 404（DELETE 生效性）。
func TestGetOpinionEvent(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)
	created := putOpinionEvent(t, handler, run.ID,
		`{"event_name":"展厅舆情事件","subject":"涉事主体","occurred_at":"2026-08-01T08:30:00Z","level":"高热"}`)

	recorder := do(handler, http.MethodGet, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeOpinionEvent(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.EventName != "展厅舆情事件" ||
		fetched.Subject != "涉事主体" || fetched.Level != "高热" ||
		fetched.OccurredAt == nil || *fetched.OccurredAt != "2026-08-01T08:30:00Z" {
		t.Fatalf("GET response %+v does not echo the created event", fetched)
	}

	recorder = do(handler, http.MethodDelete, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/opinion-event ──────────────────────────────

// 成功 204；run 不存在 404；未配置 404（判定顺序：404 先于写门控 400）。
func TestDeleteOpinionEvent(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)
	putOpinionEvent(t, handler, run.ID, `{"event_name":"A"}`)

	recorder := do(handler, http.MethodDelete, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	// 再次 DELETE：未配置 → 404。
	recorder = do(handler, http.MethodDelete, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE again: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionEventPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 级联：删除 run 后事件随之清空 ───────────────────────────────────

// 创建事件后 DELETE run（runs 路由），再 GET 事件返回 404（内存行为与
// 迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesOpinionEvent(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)
	putOpinionEvent(t, handler, run.ID, `{"event_name":"展厅舆情事件"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, opinionEventPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET event after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且 Allow 头为 GET, PUT, DELETE（本资源无
// POST、无列表端点）。
func TestOpinionEventMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionEventRun(t, handler)
	target := opinionEventPath(run.ID)

	for _, method := range []string{http.MethodPost, http.MethodPatch} {
		recorder := do(handler, method, target, `{}`)
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s: status = %d, want 405", method, recorder.Code)
		}
		if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
			t.Fatalf("%s: Allow = %q, want GET, PUT and DELETE", method, allow)
		}
		decodeError(t, recorder)
	}
}

// 允许 Origin 的 OPTIONS 预检返回 204，Allow-Methods 含 PUT/DELETE
// （以及 GET/OPTIONS），ACAO 回显。
func TestOpinionEventCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	target := opinionEventPath("01ARZ3NDEKTSV4RRFFQ69G5FAV")

	req := httptest.NewRequest(http.MethodOptions, target, nil)
	req.Header.Set("Origin", "https://allowed.example")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("preflight status = %d, want 204", recorder.Code)
	}
	methods := recorder.Header().Get("Access-Control-Allow-Methods")
	for _, method := range []string{"GET", "PUT", "DELETE", "OPTIONS"} {
		if !strings.Contains(methods, method) {
			t.Fatalf("Allow-Methods = %q, want it to contain %s", methods, method)
		}
	}
	if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
		t.Fatalf("ACAO = %q, want the allowed origin", recorder.Header().Get("Access-Control-Allow-Origin"))
	}
}
