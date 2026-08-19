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

// stepsItemPath is the item route of a scenario step.
func stepsItemPath(stepID string) string {
	return "/crate-api/prototype/v1/steps/" + stepID
}

// stepJSON mirrors the step response for assertions.
type stepJSON struct {
	ID          string `json:"id"`
	ScenarioID  string `json:"scenario_id"`
	SortOrder   int    `json:"sort_order"`
	Title       string `json:"title"`
	Description string `json:"description"`
	CreatedBy   string `json:"created_by"`
	CreatedAt   string `json:"created_at"`
	UpdatedAt   string `json:"updated_at"`
}

type stepListJSON struct {
	Records []stepJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeStep(t *testing.T, recorder *httptest.ResponseRecorder) stepJSON {
	t.Helper()
	var step stepJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &step); err != nil {
		t.Fatalf("body %q is not a step JSON: %v", recorder.Body.String(), err)
	}
	return step
}

func decodeStepList(t *testing.T, recorder *httptest.ResponseRecorder) stepListJSON {
	t.Helper()
	var list stepListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createStep posts a valid step body and asserts 201; returns the
// created step.
func createStep(t *testing.T, handler http.Handler, scenarioID, body string) stepJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, scenariosPath+"/"+scenarioID+"/steps", body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeStep(t, recorder)
}

// ─── POST /scenarios/{sid}/steps ─────────────────────────────────────

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// scenario_id 由路径决定并回显，sort_order/description/created_by 透传，
// created_at/updated_at 为服务端时间。
func TestCreateStepSuccess(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, scenariosPath+"/"+scenario.ID+"/steps",
		`{"sort_order":3,"title":"疏散广播","description":"启动应急广播","created_by":"u-admin"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	step := decodeStep(t, recorder)
	if !ulidPattern.MatchString(step.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", step.ID)
	}
	if step.ScenarioID != scenario.ID {
		t.Fatalf("scenario_id = %q, want %q (from the route path)", step.ScenarioID, scenario.ID)
	}
	if step.SortOrder != 3 || step.Title != "疏散广播" || step.Description != "启动应急广播" || step.CreatedBy != "u-admin" {
		t.Fatalf("create does not echo the input: %+v", step)
	}
	if step.CreatedAt == "" || step.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", step)
	}
}

// 缺省创建：sort_order 缺省 0、description 缺省 ”、created_by 缺省 ”。
func TestCreateStepDefaults(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	step := createStep(t, handler, scenario.ID, `{"title":"清点人数"}`)
	if step.SortOrder != 0 {
		t.Fatalf("sort_order = %d, want 0 (default)", step.SortOrder)
	}
	if step.Description != "" {
		t.Fatalf("description = %q, want empty when omitted", step.Description)
	}
	if step.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", step.CreatedBy)
	}
}

// scenario 不存在 → 404 {error}。
func TestCreateStepScenarioNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/steps", `{"title":"疏散广播"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 缺 title（含空白）400、sort_order 负数 400、字段类型错误 400、畸形 JSON
// 400；错误响应体统一 {error}。
func TestCreateStepInvalidInput(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	target := scenariosPath + "/" + scenario.ID + "/steps"
	for name, body := range map[string]string{
		"missing title":    `{"sort_order":1}`,
		"blank title":      `{"sort_order":1,"title":"  "}`,
		"negative sort":    `{"sort_order":-1,"title":"疏散广播"}`,
		"wrong title type": `{"title":123}`,
		"wrong sort type":  `{"sort_order":"abc","title":"疏散广播"}`,
		"malformed json":   `{"title": `,
	} {
		recorder := do(handler, http.MethodPost, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /scenarios/{sid}/steps ──────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非
// null。
func TestListStepsEmpty(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/steps", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeStepList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 仅返回路径场景的步骤（其他场景的步骤不出现）；按 sort_order ASC,
// created_at ASC 排序。
func TestListStepsSortedAndScopedToPath(t *testing.T) {
	handler := testMux(nil)
	scenarioA := createScenario(t, handler, validScenarioBody)
	scenarioB := createScenario(t, handler, `{"name":"停电应急演练","category":"停电与基础设施","background":"市电中断"}`)

	// 乱序创建：sort_order 2, 0, 1 → 列表按 sort_order ASC 返回 0, 1, 2。
	createStep(t, handler, scenarioA.ID, `{"sort_order":2,"title":"第三","description":"C"}`)
	createStep(t, handler, scenarioA.ID, `{"sort_order":0,"title":"第一","description":"A"}`)
	createStep(t, handler, scenarioA.ID, `{"sort_order":1,"title":"第二","description":"B"}`)
	// 另一个场景的步骤不得出现在 A 的列表里。
	createStep(t, handler, scenarioB.ID, `{"sort_order":0,"title":"B场景步骤","description":"X"}`)

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenarioA.ID+"/steps", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeStepList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	for i, title := range []string{"第一", "第二", "第三"} {
		if list.Records[i].Title != title {
			t.Fatalf("records[%d].title = %q, want %q (sort_order ASC)", i, list.Records[i].Title, title)
		}
	}

	// B 的列表只含自己的步骤。
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+scenarioB.ID+"/steps", "")
	list = decodeStepList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].Title != "B场景步骤" {
		t.Fatalf("scenario B list = %+v, want only its own step", list)
	}
}

// 相同 sort_order 时按 created_at ASC 排序。
func TestListStepsTiebreakByCreatedAtAsc(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	createStep(t, handler, scenario.ID, `{"sort_order":0,"title":"先创建"}`)
	// 保证 created_at 严格递增（毫秒级分辨率），排序断言确定。
	time.Sleep(5 * time.Millisecond)
	createStep(t, handler, scenario.ID, `{"sort_order":0,"title":"后创建"}`)

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/steps", "")
	list := decodeStepList(t, recorder)
	if len(list.Records) != 2 {
		t.Fatalf("records = %d, want 2", len(list.Records))
	}
	if list.Records[0].Title != "先创建" || list.Records[1].Title != "后创建" {
		t.Fatalf("order = %q %q, want 先创建 后创建 (created_at ASC tiebreak)",
			list.Records[0].Title, list.Records[1].Title)
	}
}

// limit/offset 分页生效；缺省 limit 50；meta.total 保持分页前总数。
func TestListStepsPagination(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		createStep(t, handler, scenario.ID, fmt.Sprintf(`{"title":"步骤%02d"}`, i))
	}

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/steps?limit=2&offset=0", "")
	list := decodeStepList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Title != "步骤01" || list.Records[1].Title != "步骤02" {
		t.Fatalf("first page titles = %q %q, want 步骤01 步骤02", list.Records[0].Title, list.Records[1].Title)
	}

	// limit=2&offset=52（末页）
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/steps?limit=2&offset=52", "")
	list = decodeStepList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 || list.Records[0].Title != "步骤53" {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d, title = %q; want 1 / 53 / 步骤53",
			len(list.Records), list.Meta.Total, func() string {
				if len(list.Records) == 0 {
					return ""
				}
				return list.Records[0].Title
			}())
	}

	// 缺省 limit 50
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/steps", "")
	list = decodeStepList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}
}

// 非法 limit/offset → 400 {error}。
func TestListStepsInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	for name, query := range map[string]string{
		"invalid limit":   "?limit=abc",
		"negative limit":  "?limit=-1",
		"invalid offset":  "?offset=abc",
		"negative offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/steps"+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// scenario 不存在 → 404 {error}。
func TestListStepsScenarioNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/steps", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /steps/{id} ─────────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetStep(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createStep(t, handler, scenario.ID, `{"sort_order":1,"title":"疏散广播","description":"启动应急广播","created_by":"u-admin"}`)

	recorder := do(handler, http.MethodGet, stepsItemPath(created.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	step := decodeStep(t, recorder)
	if step.ID != created.ID || step.ScenarioID != scenario.ID || step.SortOrder != 1 ||
		step.Title != "疏散广播" || step.Description != "启动应急广播" || step.CreatedBy != "u-admin" {
		t.Fatalf("GET response %+v does not echo the created step", step)
	}

	recorder = do(handler, http.MethodGet, stepsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /steps/{id} ─────────────────────────────────────────────────

// PUT 整体替换：成功 200 返回更新后对象，created_at 保留、updated_at 刷新，
// 随后 GET 反映更新；校验口径与 POST 一致（缺 title / sort_order 负数
// 400）；不存在的 id 404。
func TestUpdateStep(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createStep(t, handler, scenario.ID, `{"sort_order":1,"title":"疏散广播","description":"启动应急广播","created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 PUT 的 updated_at 与 created_at 可区分。
	time.Sleep(5 * time.Millisecond)

	recorder := do(handler, http.MethodPut, stepsItemPath(created.ID),
		`{"sort_order":4,"title":"疏散广播-加强","description":"启动应急广播并引导","created_by":"u-other"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeStep(t, recorder)
	if updated.ID != created.ID || updated.ScenarioID != scenario.ID ||
		updated.SortOrder != 4 || updated.Title != "疏散广播-加强" ||
		updated.Description != "启动应急广播并引导" || updated.CreatedBy != "u-other" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, stepsItemPath(created.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeStep(t, recorder)
	if fetched.SortOrder != 4 || fetched.Title != "疏散广播-加强" || fetched.Description != "启动应急广播并引导" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// PUT 缺省字段仍应用缺省值：sort_order 回缺省 0、description 回 ''。
	recorder = do(handler, http.MethodPut, stepsItemPath(created.ID), `{"title":"第三版"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT defaults: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	defaults := decodeStep(t, recorder)
	if defaults.SortOrder != 0 {
		t.Fatalf("defaults: sort_order = %d, want 0", defaults.SortOrder)
	}
	if defaults.Description != "" || defaults.CreatedBy != "" {
		t.Fatalf("defaults: description = %q, created_by = %q; want both empty", defaults.Description, defaults.CreatedBy)
	}
	if defaults.CreatedAt != createdAt {
		t.Fatalf("defaults: created_at %q changed to %q", createdAt, defaults.CreatedAt)
	}

	// PUT 校验与 POST 一致。
	for name, body := range map[string]string{
		"missing title": `{"sort_order":1}`,
		"blank title":   `{"sort_order":1,"title":" "}`,
		"negative sort": `{"sort_order":-1,"title":"疏散广播"}`,
	} {
		recorder := do(handler, http.MethodPut, stepsItemPath(created.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 不存在的 id → 404。
	recorder = do(handler, http.MethodPut, stepsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"sort_order":1,"title":"疏散广播"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /steps/{id} ──────────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeleteStep(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createStep(t, handler, scenario.ID, `{"title":"疏散广播"}`)

	recorder := do(handler, http.MethodDelete, stepsItemPath(created.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, stepsItemPath(created.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, stepsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ─────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：collection 含 GET/POST，item
// 含 GET/PUT/DELETE。
func TestStepsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/steps", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /scenarios/{sid}/steps: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /scenarios/{sid}/steps Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, stepsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /steps/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /steps/{id} Allow = %q, want it to contain GET, PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS 预检返回 204，Allow-Methods 含 POST/PUT/DELETE
// （以及 GET/OPTIONS）：collection 与 item 路由均覆盖。
func TestStepsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		scenariosPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV/steps",
		stepsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
	} {
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
