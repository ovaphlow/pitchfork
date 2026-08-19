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

// commandSessionPath is the item route of the dispatch command session
// of one run.
func commandSessionPath(runID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/command-session"
}

// sessionJSON mirrors the session response for assertions.
type sessionJSON struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	Mode        string         `json:"mode"`
	MainVenue   string         `json:"main_venue"`
	JointVenues []string       `json:"joint_venues"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

func decodeSession(t *testing.T, recorder *httptest.ResponseRecorder) sessionJSON {
	t.Helper()
	var session sessionJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &session); err != nil {
		t.Fatalf("body %q is not a session JSON: %v", recorder.Body.String(), err)
	}
	return session
}

// putSession PUTs a session body and asserts 200; returns the session.
func putSession(t *testing.T, handler http.Handler, runID, body string) sessionJSON {
	t.Helper()
	recorder := do(handler, http.MethodPut, commandSessionPath(runID), body)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeSession(t, recorder)
}

// newSessionRun creates a scenario and a run in 未开始 (writable); the
// caller transitions the run when the test needs another status.
func newSessionRun(t *testing.T, handler http.Handler) runJSON {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	return createRun(t, handler, scenario.ID, "")
}

// ─── PUT /drills/{rid}/command-session ───────────────────────────────

// 首次 PUT（空对象 {} 即合法 body）：200 + 完整对象，id 为服务端生成的
// 26 位 Crockford Base32 ULID，run_id 来自路径（body 中出现的 run_id/id
// 被忽略），mode 缺省 实战演练、joint_venues 缺省 []、metadata 缺省 {}、
// main_venue/created_by 缺省 ”，created_at/updated_at 服务端时间且相等。
func TestPutCommandSessionCreatesWithDefaults(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）。
	recorder := do(handler, http.MethodPut, commandSessionPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	session := decodeSession(t, recorder)
	if !ulidPattern.MatchString(session.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", session.ID)
	}
	if session.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", session.RunID, run.ID)
	}
	if session.Mode != "实战演练" {
		t.Fatalf("mode = %q, want the default 实战演练", session.Mode)
	}
	if session.MainVenue != "" || session.CreatedBy != "" {
		t.Fatalf("main_venue/created_by = %q / %q, want empty defaults", session.MainVenue, session.CreatedBy)
	}
	if session.JointVenues == nil || len(session.JointVenues) != 0 {
		t.Fatalf("joint_venues = %#v, want an empty array", session.JointVenues)
	}
	if session.Metadata == nil || len(session.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", session.Metadata)
	}
	if session.CreatedAt == "" || session.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", session)
	}
	if session.CreatedAt != session.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", session.CreatedAt, session.UpdatedAt)
	}
}

// 显式字段原样回显：mode 三种枚举均可、main_venue 字符串透传（空串合法）、
// joint_venues/metadata/created_by 透传。
func TestPutCommandSessionPassthrough(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)

	for _, mode := range []string{"桌面推演", "实战演练", "远程协同"} {
		session := putSession(t, handler, run.ID, `{"mode":`+jsonString(mode)+`,"main_venue":"主场馆A","joint_venues":["联训场馆B","联训场馆C"],"metadata":{"source":"merit"},"created_by":"u-admin"}`)
		if session.Mode != mode || session.MainVenue != "主场馆A" ||
			len(session.JointVenues) != 2 || session.JointVenues[1] != "联训场馆C" ||
			session.Metadata["source"] != "merit" || session.CreatedBy != "u-admin" {
			t.Fatalf("mode %s: passthrough fields = %+v", mode, session)
		}
	}

	// 显式空串 main_venue 合法。
	session := putSession(t, handler, run.ID, `{"mode":"桌面推演","main_venue":""}`)
	if session.MainVenue != "" {
		t.Fatalf("empty main_venue = %q, want empty", session.MainVenue)
	}
}

// 再次 PUT 原地更新：200 + 更新后对象，id/created_at 不变、updated_at 刷新；
// 全量覆盖（body 缺省字段重置为默认值）；随后 GET 反映更新。
func TestPutCommandSessionUpdatesInPlace(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)

	created := putSession(t, handler, run.ID,
		`{"mode":"远程协同","main_venue":"主场馆A","joint_venues":["场馆B"],"metadata":{"source":"merit"},"created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 updated_at 与 created_at 可区分（毫秒级分辨率）。
	time.Sleep(5 * time.Millisecond)

	// 再次 PUT 空对象 {}：id/created_at 不变、updated_at 刷新、全量重置。
	updated := putSession(t, handler, run.ID, `{}`)
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}
	if updated.Mode != "实战演练" || updated.MainVenue != "" || updated.CreatedBy != "" ||
		len(updated.JointVenues) != 0 || len(updated.Metadata) != 0 {
		t.Fatalf("replacement semantics = %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder := do(handler, http.MethodGet, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeSession(t, recorder)
	if fetched.Mode != "实战演练" || fetched.MainVenue != "" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}
}

// 失败路径（400）：空/畸形 body（零字节、非 JSON、null、字符串、数组）、
// 非法 mode、joint_venues 非 JSON 字符串数组、metadata 非 JSON 对象。
func TestPutCommandSessionInvalidBody(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)
	target := commandSessionPath(run.ID)

	for name, body := range map[string]string{
		"empty body":               "",
		"malformed JSON":           `{"mode":`,
		"JSON string":              `"实战演练"`,
		"JSON array":               `[{"mode":"实战演练"}]`,
		"JSON null":                `null`,
		"invalid mode":             `{"mode":"演练"}`,
		"numeric mode":             `{"mode":1}`,
		"joint_venues not array":   `{"joint_venues":"场馆B"}`,
		"joint_venues not strings": `{"joint_venues":[1,2]}`,
		"metadata not object":      `{"metadata":[1]}`,
		"metadata string":          `{"metadata":"x"}`,
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
func TestCommandSessionRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, commandSessionPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, commandSessionPath(missing), `{}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, commandSessionPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// run 存在但 session 未配置：GET/DELETE 均 404；PUT 则创建（200）。
func TestCommandSessionNotConfigured(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)

	recorder := do(handler, http.MethodGet, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, commandSessionPath(run.ID), `{}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT: status = %d, want 200 (create); body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── 写门控 ──────────────────────────────────────────────────────────

// run 状态 已完成/已终止 时 PUT/DELETE 均 400；未开始/进行中 可写；
// GET 不受写门控限制（run 存在且已配置即 200）。判定顺序：session 未配置
// 404 先于写门控 400（已完成 run 未配置 → DELETE 404）。
func TestCommandSessionWriteGate(t *testing.T) {
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
	// 已配置 session 的已完成/已终止 run（配置先行，再走到结束状态）。
	completedWithSession := createRun(t, handler, scenario.ID, "")
	putSession(t, handler, completedWithSession.ID, `{"mode":"桌面推演"}`)
	do(handler, http.MethodPost, runsPath+"/"+completedWithSession.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+completedWithSession.ID+"/complete", "")
	terminatedWithSession := createRun(t, handler, scenario.ID, "")
	putSession(t, handler, terminatedWithSession.ID, `{"mode":"桌面推演"}`)
	do(handler, http.MethodPost, runsPath+"/"+terminatedWithSession.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+terminatedWithSession.ID+"/terminate", "")

	// 已完成/已终止（未配置）→ PUT 400（写门控）、DELETE 404（未配置
	// 先于写门控判定）。
	for _, run := range []runJSON{completed, terminated} {
		recorder := do(handler, http.MethodPut, commandSessionPath(run.ID), `{"mode":"桌面推演"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, commandSessionPath(run.ID), "")
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("DELETE on %s without session: status = %d, want 404; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 已完成/已终止（已配置）→ PUT/DELETE 均 400（写门控）。
	for _, run := range []runJSON{completedWithSession, terminatedWithSession} {
		recorder := do(handler, http.MethodPut, commandSessionPath(run.ID), `{"mode":"远程协同"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s with session: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, commandSessionPath(run.ID), "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("DELETE on %s with session: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 未开始/进行中 → 可写（PUT 200，DELETE 204）。
	putSession(t, handler, notStarted.ID, `{"mode":"桌面推演"}`)
	putSession(t, handler, inProgress.ID, `{"mode":"实战演练"}`)
	recorder := do(handler, http.MethodDelete, commandSessionPath(notStarted.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 未开始: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, commandSessionPath(inProgress.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 进行中: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	// GET 不受写门控：已完成 run 已配置 → 200。
	recorder = do(handler, http.MethodGet, commandSessionPath(completedWithSession.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成 with session: status = %d, want 200", recorder.Code)
	}
	fetched := decodeSession(t, recorder)
	if fetched.Mode != "桌面推演" {
		t.Fatalf("session = %+v, want 桌面推演", fetched)
	}
}

// ─── GET /drills/{rid}/command-session ───────────────────────────────

// 已配置 → 200 + 完整对象；DELETE 204 后 GET 404（DELETE 生效性）。
func TestGetCommandSession(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)
	created := putSession(t, handler, run.ID, `{"mode":"远程协同","main_venue":"主场馆A","joint_venues":["场馆B"]}`)

	recorder := do(handler, http.MethodGet, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeSession(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.Mode != "远程协同" ||
		fetched.MainVenue != "主场馆A" || len(fetched.JointVenues) != 1 {
		t.Fatalf("GET response %+v does not echo the created session", fetched)
	}

	recorder = do(handler, http.MethodDelete, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/command-session ────────────────────────────

// 成功 204；run 不存在 404；未配置 404（判定顺序：404 先于写门控 400）。
func TestDeleteCommandSession(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)
	putSession(t, handler, run.ID, `{}`)

	recorder := do(handler, http.MethodDelete, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	// 再次 DELETE：未配置 → 404。
	recorder = do(handler, http.MethodDelete, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE again: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, commandSessionPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 级联：删除 run 后 session 随之清空 ───────────────────────────────

// 创建 session 后 DELETE run（runs 路由），再 GET session 返回 404
// （内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesCommandSession(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)
	putSession(t, handler, run.ID, `{"mode":"远程协同","main_venue":"主场馆A"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, commandSessionPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET session after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且 Allow 头为 GET, PUT, DELETE（本资源无
// POST、无列表端点）。
func TestCommandSessionMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := newSessionRun(t, handler)
	target := commandSessionPath(run.ID)

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
func TestCommandSessionCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	target := commandSessionPath("01ARZ3NDEKTSV4RRFFQ69G5FAV")

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

// jsonString quotes a string for embedding in a JSON body.
func jsonString(value string) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}
