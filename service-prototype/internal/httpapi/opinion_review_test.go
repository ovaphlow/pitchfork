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

// opinionReviewPath is the item route of the opinion review of one run.
func opinionReviewPath(runID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/review"
}

// opinionReviewJSON mirrors the opinion review response for assertions.
type opinionReviewJSON struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	CaseSummary string         `json:"case_summary"`
	Highlights  string         `json:"highlights"`
	Problems    string         `json:"problems"`
	Lessons     string         `json:"lessons"`
	Suggestions string         `json:"suggestions"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

func decodeOpinionReview(t *testing.T, recorder *httptest.ResponseRecorder) opinionReviewJSON {
	t.Helper()
	var review opinionReviewJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &review); err != nil {
		t.Fatalf("body %q is not an opinion review JSON: %v", recorder.Body.String(), err)
	}
	return review
}

// putOpinionReview PUTs an opinion review body and asserts 200; returns
// the review.
func putOpinionReview(t *testing.T, handler http.Handler, runID, body string) opinionReviewJSON {
	t.Helper()
	recorder := do(handler, http.MethodPut, opinionReviewPath(runID), body)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOpinionReview(t, recorder)
}

// newOpinionReviewRun creates a scenario and a run in 未开始; the caller
// transitions the run when the test needs another status (the review
// gate requires 进行中/已完成).
func newOpinionReviewRun(t *testing.T, handler http.Handler) runJSON {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	return createRun(t, handler, scenario.ID, "")
}

// ─── PUT /drills/{rid}/review ────────────────────────────────────────

// 首次 PUT：200 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 来自路径（body 中出现的 run_id/id 被忽略），五段文本缺省
// ”、metadata 缺省 {}、created_by 缺省 ”、created_at/updated_at 服务端
// 时间且相等；空对象 {} 是全缺省创建。
func TestPutOpinionReviewCreatesWithDefaults(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）；空对象
	// {} 是全缺省创建。
	recorder := do(handler, http.MethodPut, opinionReviewPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	review := decodeOpinionReview(t, recorder)
	if !ulidPattern.MatchString(review.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", review.ID)
	}
	if review.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", review.RunID, run.ID)
	}
	if review.CaseSummary != "" || review.Highlights != "" || review.Problems != "" ||
		review.Lessons != "" || review.Suggestions != "" || review.CreatedBy != "" {
		t.Fatalf("five sections/created_by = %+v, want empty defaults", review)
	}
	if review.Metadata == nil || len(review.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", review.Metadata)
	}
	if review.CreatedAt == "" || review.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", review)
	}
	if review.CreatedAt != review.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", review.CreatedAt, review.UpdatedAt)
	}
}

// 显式字段原样回显：五段文本 / metadata / created_by 透传；文本字段显式
// null 合法（视为 ”）；显式空串合法。
func TestPutOpinionReviewPassthrough(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")

	review := putOpinionReview(t, handler, run.ID, `{"case_summary":"事件经过","highlights":"处置亮点","problems":"存在问题","lessons":"经验教训","suggestions":"改进建议","metadata":{"source":"merit"},"created_by":"u-admin"}`)
	if review.CaseSummary != "事件经过" || review.Highlights != "处置亮点" || review.Problems != "存在问题" ||
		review.Lessons != "经验教训" || review.Suggestions != "改进建议" ||
		review.Metadata["source"] != "merit" || review.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", review)
	}

	// 文本字段显式 null / 空串均合法（null 视为 ”）。
	got := putOpinionReview(t, handler, run.ID, `{"case_summary":null,"highlights":"","problems":null,"lessons":"","suggestions":""}`)
	if got.CaseSummary != "" || got.Highlights != "" || got.Problems != "" || got.Lessons != "" || got.Suggestions != "" {
		t.Fatalf("null/empty text fields = %+v, want empty strings", got)
	}
}

// 再次 PUT 原地更新：200 + 更新后对象，id/created_at 不变、updated_at 刷新；
// 全量覆盖（body 缺省字段重置为默认值）；随后 GET 反映更新。
func TestPutOpinionReviewUpdatesInPlace(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")

	created := putOpinionReview(t, handler, run.ID,
		`{"case_summary":"事件经过","highlights":"处置亮点","problems":"存在问题","lessons":"经验教训","suggestions":"改进建议","metadata":{"source":"merit"},"created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 updated_at 与 created_at 可区分（毫秒级分辨率）。
	time.Sleep(5 * time.Millisecond)

	// 再次 PUT 只改 case_summary（其余缺省）：id/created_at 不变、updated_at
	// 刷新、缺省字段重置为默认值。
	updated := putOpinionReview(t, handler, run.ID, `{"case_summary":"修订后的事件经过"}`)
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}
	if updated.CaseSummary != "修订后的事件经过" || updated.Highlights != "" || updated.Problems != "" ||
		updated.Lessons != "" || updated.Suggestions != "" || updated.CreatedBy != "" ||
		len(updated.Metadata) != 0 {
		t.Fatalf("replacement semantics = %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder := do(handler, http.MethodGet, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeOpinionReview(t, recorder)
	if fetched.CaseSummary != "修订后的事件经过" || fetched.Highlights != "" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}
}

// 失败路径（400，响应体统一 { "error": ... }）：空/畸形 body（零字节、非
// JSON、null、字符串、数组）、五段文本任一为非字符串（数字/布尔/对象/
// 数组）、metadata 非 JSON 对象（数组/字符串/数字）。
func TestPutOpinionReviewInvalidBody(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	target := opinionReviewPath(run.ID)

	// metadata 显式 null 合法（视为 {}），单独断言。
	got := putOpinionReview(t, handler, run.ID, `{"metadata":null}`)
	if got.Metadata == nil || len(got.Metadata) != 0 {
		t.Fatalf("metadata explicit null: got %#v, want an empty object", got.Metadata)
	}

	for name, body := range map[string]string{
		"empty body":           "",
		"malformed JSON":       `{"case_summary":`,
		"JSON string":          `"事件经过"`,
		"JSON array":           `[{"case_summary":"A"}]`,
		"JSON null":            `null`,
		"case_summary number":  `{"case_summary":123}`,
		"case_summary boolean": `{"case_summary":true}`,
		"case_summary object":  `{"case_summary":{"a":1}}`,
		"case_summary array":   `{"case_summary":["A"]}`,
		"highlights number":    `{"highlights":1}`,
		"problems boolean":     `{"problems":false}`,
		"lessons object":       `{"lessons":{}}`,
		"suggestions array":    `{"suggestions":[]}`,
		"metadata array":       `{"metadata":[1]}`,
		"metadata string":      `{"metadata":"x"}`,
		"metadata number":      `{"metadata":1}`,
		"metadata boolean":     `{"metadata":true}`,
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
func TestOpinionReviewRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionReviewPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionReviewPath(missing), `{}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionReviewPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// run 存在但复盘未写：GET/DELETE 均 404；PUT 则创建（200）。
func TestOpinionReviewNotConfigured(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")

	recorder := do(handler, http.MethodGet, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionReviewPath(run.ID), `{}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT: status = %d, want 200 (create); body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── 写门控 ──────────────────────────────────────────────────────────

// run 状态 未开始/已终止 时 PUT/DELETE 均 400；进行中/已完成 可写；
// GET 不受写门控限制（run 存在且已配置即 200）。判定顺序：复盘未配置
// 404 先于写门控 400（未开始 run 未配置 → DELETE 404）。
func TestOpinionReviewWriteGate(t *testing.T) {
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
	// 已写复盘的已终止 run（先写复盘，再走到终止状态）。
	terminatedAfterReviewA := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+terminatedAfterReviewA.ID+"/start", "")
	putOpinionReview(t, handler, terminatedAfterReviewA.ID, `{"case_summary":"A"}`)
	do(handler, http.MethodPost, runsPath+"/"+terminatedAfterReviewA.ID+"/terminate", "")
	terminatedWithReview := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+terminatedWithReview.ID+"/start", "")
	putOpinionReview(t, handler, terminatedWithReview.ID, `{"case_summary":"A"}`)
	do(handler, http.MethodPost, runsPath+"/"+terminatedWithReview.ID+"/terminate", "")

	// 未开始/已终止（未配置）→ PUT 400（写门控）、DELETE 404（未配置先于
	// 写门控判定）。
	for _, run := range []runJSON{notStarted, terminated} {
		recorder := do(handler, http.MethodPut, opinionReviewPath(run.ID), `{}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, opinionReviewPath(run.ID), "")
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("DELETE on %s without review: status = %d, want 404; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 已终止（已配置）→ PUT/DELETE 均 400（写门控）。
	for _, run := range []runJSON{terminatedAfterReviewA, terminatedWithReview} {
		recorder := do(handler, http.MethodPut, opinionReviewPath(run.ID), `{"case_summary":"B"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s with review: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, opinionReviewPath(run.ID), "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("DELETE on %s with review: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 进行中/已完成 → 可写（PUT 200，DELETE 204）。
	putOpinionReview(t, handler, inProgress.ID, `{"case_summary":"A"}`)
	putOpinionReview(t, handler, completed.ID, `{"case_summary":"A"}`)
	recorder := do(handler, http.MethodDelete, opinionReviewPath(inProgress.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 进行中: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, opinionReviewPath(completed.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 已完成: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	// GET 不受写门控：已终止 run 已写复盘仍可读（终止前写的复盘）。
	recorder = do(handler, http.MethodGet, opinionReviewPath(terminatedAfterReviewA.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已终止 with review: status = %d, want 200", recorder.Code)
	}
	fetched := decodeOpinionReview(t, recorder)
	if fetched.CaseSummary != "A" {
		t.Fatalf("review = %+v, want case_summary A", fetched)
	}
}

// ─── GET /drills/{rid}/review ────────────────────────────────────────

// 已配置 → 200 + 完整对象；DELETE 204 后 GET 404（DELETE 生效性）。
func TestGetOpinionReview(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	created := putOpinionReview(t, handler, run.ID,
		`{"case_summary":"事件经过","highlights":"处置亮点","metadata":{"source":"merit"}}`)

	recorder := do(handler, http.MethodGet, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeOpinionReview(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.CaseSummary != "事件经过" ||
		fetched.Highlights != "处置亮点" || fetched.Metadata["source"] != "merit" {
		t.Fatalf("GET response %+v does not echo the created review", fetched)
	}

	recorder = do(handler, http.MethodDelete, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/review ─────────────────────────────────────

// 成功 204；run 不存在 404；未配置 404（判定顺序：404 先于写门控 400）。
func TestDeleteOpinionReview(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	putOpinionReview(t, handler, run.ID, `{"case_summary":"A"}`)

	recorder := do(handler, http.MethodDelete, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	// 再次 DELETE：未配置 → 404。
	recorder = do(handler, http.MethodDelete, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE again: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionReviewPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 级联：删除 run 后复盘随之清空 ───────────────────────────────────

// 创建复盘后 DELETE run（runs 路由），再 GET 复盘返回 404（内存行为与
// 迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesOpinionReview(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	putOpinionReview(t, handler, run.ID, `{"case_summary":"事件经过"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, opinionReviewPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET review after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且 Allow 头为 GET, PUT, DELETE（本资源无
// POST、无列表端点）。
func TestOpinionReviewMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := newOpinionReviewRun(t, handler)
	target := opinionReviewPath(run.ID)

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
func TestOpinionReviewCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	target := opinionReviewPath("01ARZ3NDEKTSV4RRFFQ69G5FAV")

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
