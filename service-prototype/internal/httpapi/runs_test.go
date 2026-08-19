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

const runsPath = "/crate-api/prototype/v1/drills"

// runJSON mirrors the run response for assertions.
type runJSON struct {
	ID          string         `json:"id"`
	ScenarioID  string         `json:"scenario_id"`
	Title       string         `json:"title"`
	Status      string         `json:"status"`
	StartedAt   *string        `json:"started_at"`
	CompletedAt *string        `json:"completed_at"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

type runListJSON struct {
	Records []runJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeRun(t *testing.T, recorder *httptest.ResponseRecorder) runJSON {
	t.Helper()
	var run runJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &run); err != nil {
		t.Fatalf("body %q is not a run JSON: %v", recorder.Body.String(), err)
	}
	return run
}

func decodeRunList(t *testing.T, recorder *httptest.ResponseRecorder) runListJSON {
	t.Helper()
	var list runListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createRun posts a valid run body and asserts 201; returns the created
// run.
func createRun(t *testing.T, handler http.Handler, scenarioID, body string) runJSON {
	t.Helper()
	if body == "" {
		body = fmt.Sprintf(`{"scenario_id":%q,"title":"大客流疏散演练执行"}`, scenarioID)
	}
	recorder := do(handler, http.MethodPost, runsPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeRun(t, recorder)
}

// ─── POST /drills ────────────────────────────────────────────────────

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// scenario_id/title 回显，status 缺省 未开始，started_at/completed_at 为
// null，metadata 缺省 {}（携带时原样回显），created_by 透传（缺省空串），
// created_at/updated_at 为服务端时间且相等。
func TestCreateRunSuccess(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, runsPath, fmt.Sprintf(
		`{"scenario_id":%q,"title":"大客流疏散演练执行","metadata":{"source":"merit"},"created_by":"u-admin"}`, scenario.ID))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	run := decodeRun(t, recorder)
	if !ulidPattern.MatchString(run.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", run.ID)
	}
	if run.ScenarioID != scenario.ID || run.Title != "大客流疏散演练执行" {
		t.Fatalf("create does not echo the input: %+v", run)
	}
	if run.Status != "未开始" {
		t.Fatalf("status = %q, want 未开始 (default)", run.Status)
	}
	if run.StartedAt != nil || run.CompletedAt != nil {
		t.Fatalf("started_at/completed_at = %v / %v, want null", run.StartedAt, run.CompletedAt)
	}
	if len(run.Metadata) != 1 || run.Metadata["source"] != "merit" {
		t.Fatalf("metadata = %v, want the request metadata echoed", run.Metadata)
	}
	if run.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", run.CreatedBy)
	}
	if run.CreatedAt == "" || run.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", run)
	}
	if run.CreatedAt != run.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", run.CreatedAt, run.UpdatedAt)
	}
}

// 缺省创建：metadata 缺省 {}、created_by 缺省 ”。
func TestCreateRunDefaults(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	run := createRun(t, handler, scenario.ID, "")
	if len(run.Metadata) != 0 {
		t.Fatalf("metadata = %v, want an empty object when omitted", run.Metadata)
	}
	if run.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", run.CreatedBy)
	}
}

// 缺 scenario_id / title（含空白）→ 400；scenario_id 指向不存在场景 →
// 404；错误响应体统一 {error}。
func TestCreateRunMissingRequiredFieldsAndUnknownScenario(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	for name, body := range map[string]string{
		"missing scenario_id": fmt.Sprintf(`{"title":"演练"}`),
		"blank scenario_id":   fmt.Sprintf(`{"scenario_id":"  ","title":"演练"}`),
		"missing title":       fmt.Sprintf(`{"scenario_id":%q}`, scenario.ID),
		"blank title":         fmt.Sprintf(`{"scenario_id":%q,"title":" "}`, scenario.ID),
	} {
		recorder := do(handler, http.MethodPost, runsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	recorder := do(handler, http.MethodPost, runsPath, `{"scenario_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","title":"演练"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown scenario: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, runsPath, `{"scenario_id":`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("malformed body: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 请求体携带 status/started_at/completed_at 被忽略（服务端管理）：创建结果
// 仍为 未开始 且两个时间戳为 null。
func TestCreateRunIgnoresServerManagedFields(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	recorder := do(handler, http.MethodPost, runsPath, fmt.Sprintf(
		`{"scenario_id":%q,"title":"演练","status":"已完成","started_at":"2026-08-14T10:00:00Z","completed_at":"2026-08-14T11:00:00Z"}`,
		scenario.ID))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	run := decodeRun(t, recorder)
	if run.Status != "未开始" {
		t.Fatalf("status = %q, want 未开始 (server-managed)", run.Status)
	}
	if run.StartedAt != nil || run.CompletedAt != nil {
		t.Fatalf("started_at/completed_at = %v / %v, want null (server-managed)", run.StartedAt, run.CompletedAt)
	}
}

// ─── GET /drills ────────────────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非
// null。
func TestListRunsEmpty(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, runsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeRunList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// status/scenario_id 筛选生效，meta.total 为筛选后的总数；排序 created_at
// DESC（后创建的在前）。
func TestListRunsFiltering(t *testing.T) {
	handler := testMux(nil)
	scenarioA := createScenario(t, handler, validScenarioBody)
	scenarioB := createScenario(t, handler, `{"name":"停电应急演练","category":"停电与基础设施","background":"市电中断"}`)
	runA1 := createRun(t, handler, scenarioA.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"A-1"}`, scenarioA.ID))
	createRun(t, handler, scenarioB.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"B-1"}`, scenarioB.ID))
	runA2 := createRun(t, handler, scenarioA.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"A-2"}`, scenarioA.ID))
	createRun(t, handler, scenarioA.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"A-3"}`, scenarioA.ID))

	// runA2 走完 未开始 -> 进行中 -> 已完成。
	do(handler, http.MethodPost, runsPath+"/"+runA2.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+runA2.ID+"/complete", "")
	// runA1 走 未开始 -> 进行中。
	do(handler, http.MethodPost, runsPath+"/"+runA1.ID+"/start", "")
	// runB1 保持 未开始。

	cases := []struct {
		name   string
		query  string
		total  int
		titles []string
	}{
		{"by status", "?status=" + "已完成", 1, []string{"A-2"}},
		{"by status 未开始", "?status=" + "未开始", 2, []string{"A-3", "B-1"}},
		{"by scenario", "?scenario_id=" + scenarioB.ID, 1, []string{"B-1"}},
		{"scenario+status", "?scenario_id=" + scenarioA.ID + "&status=" + "进行中", 1, []string{"A-1"}},
		{"no match", "?status=" + "已终止", 0, nil},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodGet, runsPath+testCase.query, "")
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200", testCase.name, recorder.Code)
		}
		list := decodeRunList(t, recorder)
		if list.Meta.Total != testCase.total {
			t.Fatalf("%s: total = %d, want %d", testCase.name, list.Meta.Total, testCase.total)
		}
		if len(list.Records) != len(testCase.titles) {
			t.Fatalf("%s: records = %d, want %d", testCase.name, len(list.Records), len(testCase.titles))
		}
		for i, title := range testCase.titles {
			if list.Records[i].Title != title {
				t.Fatalf("%s: records[%d].title = %q, want %q (created_at DESC)", testCase.name, i, list.Records[i].Title, title)
			}
		}
	}
}

// 列表排序 created_at DESC：后创建的 run 排前面。
func TestListRunsSortedByCreatedAtDesc(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	titles := []string{"演练一", "演练二", "演练三"}
	for _, title := range titles {
		createRun(t, handler, scenario.ID, fmt.Sprintf(`{"scenario_id":%q,"title":%q}`, scenario.ID, title))
		// 保证 created_at 严格递增（毫秒级分辨率），排序断言确定。
		time.Sleep(5 * time.Millisecond)
	}
	recorder := do(handler, http.MethodGet, runsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeRunList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	for i, title := range []string{"演练三", "演练二", "演练一"} {
		if list.Records[i].Title != title {
			t.Fatalf("records[%d].title = %q, want %q (created_at DESC)", i, list.Records[i].Title, title)
		}
	}
}

// limit/offset 分页生效；缺省 limit 50；meta.total 保持筛选后的总数。
func TestListRunsPagination(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		createRun(t, handler, scenario.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"演练%02d"}`, scenario.ID, i))
	}

	recorder := do(handler, http.MethodGet, runsPath+"?limit=2&offset=0", "")
	list := decodeRunList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Title != "演练53" || list.Records[1].Title != "演练52" {
		t.Fatalf("first page titles = %q %q, want 演练53 演练52 (created_at DESC)", list.Records[0].Title, list.Records[1].Title)
	}

	recorder = do(handler, http.MethodGet, runsPath+"?limit=2&offset=52", "")
	list = decodeRunList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 || list.Records[0].Title != "演练01" {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d, title = %q; want 1 / 53 / 演练01",
			len(list.Records), list.Meta.Total, func() string {
				if len(list.Records) == 0 {
					return ""
				}
				return list.Records[0].Title
			}())
	}

	recorder = do(handler, http.MethodGet, runsPath, "")
	list = decodeRunList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}
}

// 非法枚举筛选值或非法 limit/offset → 400 {error}。
func TestListRunsInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	for name, query := range map[string]string{
		"invalid status": "?status=" + "草稿",
		"invalid limit":  "?limit=abc",
		"negative limit": "?limit=-1",
		"invalid offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, runsPath+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{id} ───────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetRun(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createRun(t, handler, scenario.ID, fmt.Sprintf(
		`{"scenario_id":%q,"title":"大客流疏散演练执行","metadata":{"source":"merit"},"created_by":"u-admin"}`, scenario.ID))

	recorder := do(handler, http.MethodGet, runsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	run := decodeRun(t, recorder)
	if run.ID != created.ID || run.ScenarioID != scenario.ID || run.Title != "大客流疏散演练执行" ||
		run.Status != "未开始" || run.CreatedBy != "u-admin" || run.Metadata["source"] != "merit" ||
		run.StartedAt != nil || run.CompletedAt != nil {
		t.Fatalf("GET response %+v does not echo the created run", run)
	}

	recorder = do(handler, http.MethodGet, runsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /drills/{id} ───────────────────────────────────────────────

// PUT 整体替换：成功 200 返回更新后对象，title/metadata 变化可见，status/
// started_at/completed_at 服务端管理不变、metadata 整体替换（缺省 {}）、
// created_at 保留、updated_at 刷新；随后 GET 反映更新；PUT 失败路径与
// POST 一致（缺 scenario_id/title 400、scenario 不存在 404、携带 status/
// started_at/completed_at 被忽略）；不存在的 id 404。
func TestUpdateRun(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createRun(t, handler, scenario.ID, fmt.Sprintf(
		`{"scenario_id":%q,"title":"第一版","metadata":{"source":"merit"}}`, scenario.ID))
	createdAt := created.CreatedAt

	// start 后再 PUT：status/started_at 必须保持不变。
	do(handler, http.MethodPost, runsPath+"/"+created.ID+"/start", "")
	// 保证 PUT 的 updated_at 与 created_at 可区分。
	time.Sleep(5 * time.Millisecond)

	updatedBody := fmt.Sprintf(`{"scenario_id":%q,"title":"第二版","metadata":{"source":"merit","level":"2"},"created_by":"u-admin","status":"已完成","started_at":"2026-08-14T10:00:00Z","completed_at":"2026-08-14T11:00:00Z"}`,
		scenario.ID)
	recorder := do(handler, http.MethodPut, runsPath+"/"+created.ID, updatedBody)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeRun(t, recorder)
	if updated.ID != created.ID || updated.Title != "第二版" || updated.CreatedBy != "u-admin" ||
		updated.Metadata["level"] != "2" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}
	if updated.Status != "进行中" {
		t.Fatalf("status = %q, want 进行中 (server-managed, PUT 携带的 status 被忽略)", updated.Status)
	}
	if updated.StartedAt == nil {
		t.Fatalf("started_at must survive PUT (server-managed)")
	}
	if updated.CompletedAt != nil {
		t.Fatalf("completed_at = %v, want null (server-managed, PUT 携带的 completed_at 被忽略)", updated.CompletedAt)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, runsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeRun(t, recorder)
	if fetched.Title != "第二版" || fetched.Metadata["level"] != "2" || fetched.Status != "进行中" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// PUT 缺省字段仍应用缺省值：metadata 回 {}。
	recorder = do(handler, http.MethodPut, runsPath+"/"+created.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"第三版"}`, scenario.ID))
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT defaults: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	defaults := decodeRun(t, recorder)
	if len(defaults.Metadata) != 0 {
		t.Fatalf("defaults: metadata = %v, want an empty object (整体替换)", defaults.Metadata)
	}
	if defaults.CreatedBy != "" {
		t.Fatalf("defaults: created_by = %q, want empty", defaults.CreatedBy)
	}
	if defaults.CreatedAt != createdAt {
		t.Fatalf("defaults: created_at %q changed to %q", createdAt, defaults.CreatedAt)
	}

	// PUT 校验与 POST 一致。
	for name, body := range map[string]string{
		"missing scenario_id": `{"title":"演练"}`,
		"missing title":       fmt.Sprintf(`{"scenario_id":%q}`, scenario.ID),
		"blank title":         fmt.Sprintf(`{"scenario_id":%q,"title":" "}`, scenario.ID),
	} {
		recorder := do(handler, http.MethodPut, runsPath+"/"+created.ID, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
	recorder = do(handler, http.MethodPut, runsPath+"/"+created.ID, `{"scenario_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","title":"演练"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown scenario: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// PUT 不存在的 id → 404。
	recorder = do(handler, http.MethodPut, runsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", updatedBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{id} ────────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeleteRun(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createRun(t, handler, scenario.ID, "")

	recorder := do(handler, http.MethodDelete, runsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, runsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, runsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 状态机 start / complete / terminate ─────────────────────────────

// start 后 status=进行中、started_at 非空且 completed_at 保持 null；
// complete 后 status=已完成、completed_at 非空；terminate 后 status=已终止、
// completed_at 保持 null；各 transition 后 GET 复验新状态与时间戳。
func TestRunTransitions(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	run := createRun(t, handler, scenario.ID, "")

	// start
	recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	started := decodeRun(t, recorder)
	if started.Status != "进行中" || started.StartedAt == nil || started.CompletedAt != nil {
		t.Fatalf("start response = %+v, want 进行中 with started_at set and completed_at null", started)
	}
	recorder = do(handler, http.MethodGet, runsPath+"/"+run.ID, "")
	fetched := decodeRun(t, recorder)
	if fetched.Status != "进行中" || fetched.StartedAt == nil || fetched.CompletedAt != nil {
		t.Fatalf("GET after start = %+v, want 进行中", fetched)
	}

	// complete
	recorder = do(handler, http.MethodPost, runsPath+"/"+run.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	completed := decodeRun(t, recorder)
	if completed.Status != "已完成" || completed.CompletedAt == nil {
		t.Fatalf("complete response = %+v, want 已完成 with completed_at set", completed)
	}
	if completed.StartedAt == nil || *completed.StartedAt != *started.StartedAt {
		t.Fatalf("complete: started_at changed: %v -> %v", *started.StartedAt, completed.StartedAt)
	}
	recorder = do(handler, http.MethodGet, runsPath+"/"+run.ID, "")
	fetched = decodeRun(t, recorder)
	if fetched.Status != "已完成" || fetched.CompletedAt == nil {
		t.Fatalf("GET after complete = %+v, want 已完成", fetched)
	}

	// terminate 分支：新的 run 走 start -> terminate。
	run2 := createRun(t, handler, scenario.ID, fmt.Sprintf(`{"scenario_id":%q,"title":"第二场"}`, scenario.ID))
	recorder = do(handler, http.MethodPost, runsPath+"/"+run2.ID+"/start", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("start2 status = %d, want 200", recorder.Code)
	}
	recorder = do(handler, http.MethodPost, runsPath+"/"+run2.ID+"/terminate", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("terminate status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	terminated := decodeRun(t, recorder)
	if terminated.Status != "已终止" || terminated.CompletedAt != nil {
		t.Fatalf("terminate response = %+v, want 已终止 with completed_at null", terminated)
	}
	recorder = do(handler, http.MethodGet, runsPath+"/"+run2.ID, "")
	fetched = decodeRun(t, recorder)
	if fetched.Status != "已终止" || fetched.CompletedAt != nil {
		t.Fatalf("GET after terminate = %+v, want 已终止", fetched)
	}
}

// 非法状态迁移一律 400：未开始直接 complete/terminate、已完成/已终止再
// start、已完成再 terminate、已终止再 complete 等；不存在 id 404；全部失败
// 路径错误体统一 {error}。
func TestRunTransitionsIllegal(t *testing.T) {
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

	cases := []struct {
		name   string
		runID  string
		action string
	}{
		{"未开始 -> complete", notStarted.ID, "complete"},
		{"未开始 -> terminate", notStarted.ID, "terminate"},
		{"进行中 -> start", inProgress.ID, "start"},
		{"已完成 -> start", completed.ID, "start"},
		{"已完成 -> complete", completed.ID, "complete"},
		{"已完成 -> terminate", completed.ID, "terminate"},
		{"已终止 -> start", terminated.ID, "start"},
		{"已终止 -> complete", terminated.ID, "complete"},
		{"已终止 -> terminate", terminated.ID, "terminate"},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodPost, runsPath+"/"+testCase.runID+"/"+testCase.action, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 不存在 id → 404（三个入口一致）。
	for _, action := range []string{"start", "complete", "terminate"} {
		recorder := do(handler, http.MethodPost, runsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/"+action, "")
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("%s unknown id: status = %d, want 404", action, recorder.Code)
		}
		decodeError(t, recorder)
	}
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：collection 含 GET/POST，item
// 含 GET/PUT/DELETE。
func TestRunsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, runsPath, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /drills: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /drills Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, runsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /drills/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /drills/{id} Allow = %q, want it to contain GET, PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS 预检对 /drills 与 /drills/{id} 返回 204，
// Allow-Methods 含 POST/PUT/DELETE（以及 GET/OPTIONS），ACAO 回显。
func TestRunsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{runsPath, runsPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV"} {
		req := httptest.NewRequest(http.MethodOptions, target, nil)
		req.Header.Set("Origin", "https://allowed.example")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusNoContent {
			t.Fatalf("%s: preflight status = %d, want 204", target, recorder.Code)
		}
		methods := recorder.Header().Get("Access-Control-Allow-Methods")
		for _, method := range []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"} {
			if !strings.Contains(methods, method) {
				t.Fatalf("%s: Allow-Methods = %q, want it to contain %s", target, methods, method)
			}
		}
		if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
			t.Fatalf("%s: ACAO = %q", target, recorder.Header().Get("Access-Control-Allow-Origin"))
		}
	}
}
