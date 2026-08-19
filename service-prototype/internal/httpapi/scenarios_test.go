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

const scenariosPath = "/crate-api/prototype/v1/scenarios"

// scenarioJSON mirrors the scenario response for assertions.
type scenarioJSON struct {
	ID         string         `json:"id"`
	Name       string         `json:"name"`
	Category   string         `json:"category"`
	Background string         `json:"background"`
	Status     string         `json:"status"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
	CreatedAt  string         `json:"created_at"`
	UpdatedAt  string         `json:"updated_at"`
}

type scenarioListJSON struct {
	Records []scenarioJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeScenario(t *testing.T, recorder *httptest.ResponseRecorder) scenarioJSON {
	t.Helper()
	var scenario scenarioJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &scenario); err != nil {
		t.Fatalf("body %q is not a scenario JSON: %v", recorder.Body.String(), err)
	}
	return scenario
}

func decodeScenarioList(t *testing.T, recorder *httptest.ResponseRecorder) scenarioListJSON {
	t.Helper()
	var list scenarioListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createScenario posts a valid scenario body and asserts 201; returns
// the created scenario.
func createScenario(t *testing.T, handler http.Handler, body string) scenarioJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, scenariosPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeScenario(t, recorder)
}

const validScenarioBody = `{"name":"大客流疏散演练","category":"大客流聚集","background":"节假日高峰客流超阈值，出口拥堵","metadata":{"source":"merit"},"created_by":"u-admin"}`

// ─── POST /scenarios ─────────────────────────────────────────────────

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID，name/
// category/background 回显，status 缺省 启用，created_at/updated_at 为
// 服务端时间。
func TestCreateScenarioSuccess(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, scenariosPath, validScenarioBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	scenario := decodeScenario(t, recorder)
	if !ulidPattern.MatchString(scenario.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", scenario.ID)
	}
	if scenario.Name != "大客流疏散演练" || scenario.Category != "大客流聚集" ||
		scenario.Background != "节假日高峰客流超阈值，出口拥堵" {
		t.Fatalf("create does not echo the input: %+v", scenario)
	}
	if scenario.Status != "启用" {
		t.Fatalf("status = %q, want 启用 (default)", scenario.Status)
	}
	if len(scenario.Metadata) != 1 || scenario.Metadata["source"] != "merit" {
		t.Fatalf("metadata = %v, want the request metadata echoed", scenario.Metadata)
	}
	if scenario.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", scenario.CreatedBy)
	}
	if scenario.CreatedAt == "" || scenario.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", scenario)
	}
}

// 缺省创建：status 缺省 启用、metadata 缺省 {}、created_by 缺省 ”。
func TestCreateScenarioDefaults(t *testing.T) {
	scenario := createScenario(t, testMux(nil), `{"name":"停电应急演练","category":"停电与基础设施","background":"市电中断，备用电源切换"}`)
	if scenario.Status != "启用" {
		t.Fatalf("status = %q, want 启用 (default)", scenario.Status)
	}
	if len(scenario.Metadata) != 0 {
		t.Fatalf("metadata = %v, want an empty object when omitted", scenario.Metadata)
	}
	if scenario.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", scenario.CreatedBy)
	}
}

// 缺 name / category / background（含空白）→ 400，错误响应体统一 {error}。
func TestCreateScenarioMissingRequiredFields(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"missing name":       `{"category":"火灾","background":"背景"}`,
		"blank name":         `{"name":"  ","category":"火灾","background":"背景"}`,
		"missing category":   `{"name":"演练","background":"背景"}`,
		"missing background": `{"name":"演练","category":"火灾"}`,
		"blank background":   `{"name":"演练","category":"火灾","background":" "}`,
	} {
		recorder := do(handler, http.MethodPost, scenariosPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 非法 category / status → 400，错误响应体统一 {error}。
func TestCreateScenarioInvalidEnums(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"invalid category": `{"name":"演练","category":"不存在的分类","background":"背景"}`,
		"invalid status":   `{"name":"演练","category":"火灾","background":"背景","status":"草稿"}`,
	} {
		recorder := do(handler, http.MethodPost, scenariosPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 畸形 JSON 请求体 → 400 {error}。
func TestCreateScenarioMalformedBody(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, scenariosPath, `{"name": `)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /scenarios ──────────────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非
// null。
func TestListScenariosEmpty(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, scenariosPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeScenarioList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// category/status 筛选生效，meta.total 为筛选后的总数。
func TestListScenariosFiltering(t *testing.T) {
	handler := testMux(nil)
	createScenario(t, handler, `{"name":"演练一","category":"大客流聚集","background":"背景一"}`)
	createScenario(t, handler, `{"name":"演练二","category":"停电与基础设施","background":"背景二"}`)
	createScenario(t, handler, `{"name":"演练三","category":"火灾","background":"背景三"}`)
	createScenario(t, handler, `{"name":"演练四","category":"大客流聚集","background":"背景四","status":"停用"}`)
	createScenario(t, handler, `{"name":"演练五","category":"气象灾害","background":"背景五"}`)

	cases := []struct {
		name  string
		query string
		total int
		names []string
	}{
		{"by category", "?category=" + "大客流聚集", 2, []string{"演练一", "演练四"}},
		{"by status", "?status=" + "停用", 1, []string{"演练四"}},
		{"category+status", "?category=" + "大客流聚集" + "&status=" + "启用", 1, []string{"演练一"}},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodGet, scenariosPath+testCase.query, "")
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200", testCase.name, recorder.Code)
		}
		list := decodeScenarioList(t, recorder)
		if list.Meta.Total != testCase.total {
			t.Fatalf("%s: total = %d, want %d", testCase.name, list.Meta.Total, testCase.total)
		}
		if len(list.Records) != len(testCase.names) {
			t.Fatalf("%s: records = %d, want %d", testCase.name, len(list.Records), len(testCase.names))
		}
		for i, name := range testCase.names {
			if list.Records[i].Name != name {
				t.Fatalf("%s: records[%d].name = %q, want %q", testCase.name, i, list.Records[i].Name, name)
			}
		}
	}
}

// 列表排序 created_at ASC, id ASC：创建的先后即返回顺序。
func TestListScenariosSortedByCreatedAtAsc(t *testing.T) {
	handler := testMux(nil)
	names := []string{"演练一", "演练二", "演练三"}
	for _, name := range names {
		createScenario(t, handler, `{"name":"`+name+`","category":"火灾","background":"背景"}`)
		// 保证 created_at 严格递增（毫秒级分辨率），排序断言确定。
		time.Sleep(5 * time.Millisecond)
	}
	recorder := do(handler, http.MethodGet, scenariosPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeScenarioList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	for i, name := range names {
		if list.Records[i].Name != name {
			t.Fatalf("records[%d].name = %q, want %q (created_at ASC)", i, list.Records[i].Name, name)
		}
	}
}

// limit/offset 分页生效；缺省 limit 50；meta.total 保持筛选后的总数。
func TestListScenariosPagination(t *testing.T) {
	handler := testMux(nil)
	for i := 1; i <= 53; i++ {
		createScenario(t, handler, fmt.Sprintf(`{"name":"演练%02d","category":"火灾","background":"背景"}`, i))
	}

	// limit=2&offset=0
	recorder := do(handler, http.MethodGet, scenariosPath+"?limit=2&offset=0", "")
	list := decodeScenarioList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Name != "演练01" || list.Records[1].Name != "演练02" {
		t.Fatalf("first page names = %q %q, want 演练01 演练02", list.Records[0].Name, list.Records[1].Name)
	}

	// limit=2&offset=52（末页）
	recorder = do(handler, http.MethodGet, scenariosPath+"?limit=2&offset=52", "")
	list = decodeScenarioList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 || list.Records[0].Name != "演练53" {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d, name = %q; want 1 / 53 / 演练53",
			len(list.Records), list.Meta.Total, func() string {
				if len(list.Records) == 0 {
					return ""
				}
				return list.Records[0].Name
			}())
	}

	// 缺省 limit 50
	recorder = do(handler, http.MethodGet, scenariosPath, "")
	list = decodeScenarioList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}
}

// 非法枚举筛选值或非法 limit/offset → 400 {error}。
func TestListScenariosInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	for name, query := range map[string]string{
		"invalid category": "?category=" + "不存在的分类",
		"invalid status":   "?status=" + "草稿",
		"invalid limit":    "?limit=abc",
		"negative limit":   "?limit=-1",
		"invalid offset":   "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, scenariosPath+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /scenarios/{id} ─────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetScenario(t *testing.T) {
	handler := testMux(nil)
	created := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	scenario := decodeScenario(t, recorder)
	if scenario.ID != created.ID || scenario.Name != "大客流疏散演练" ||
		scenario.Category != "大客流聚集" || scenario.Background != "节假日高峰客流超阈值，出口拥堵" ||
		scenario.Status != "启用" || scenario.CreatedBy != "u-admin" ||
		scenario.Metadata["source"] != "merit" {
		t.Fatalf("GET response %+v does not echo the created scenario", scenario)
	}

	recorder = do(handler, http.MethodGet, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /scenarios/{id} ─────────────────────────────────────────────

// PUT 整体替换：成功 200 返回更新后对象，created_at 保留、updated_at 刷新，
// 随后 GET 反映更新；校验口径与 POST 一致（缺必填/非法枚举 400）；不存在
// 的 id 404。
func TestUpdateScenario(t *testing.T) {
	handler := testMux(nil)
	created := createScenario(t, handler, validScenarioBody)
	createdAt := created.CreatedAt
	// 保证 PUT 的 updated_at 与 created_at 可区分。
	time.Sleep(5 * time.Millisecond)

	updatedBody := `{"name":"大客流疏散演练-加强版","category":"火灾","background":"展厅烟感探测器触发","status":"停用","metadata":{"source":"merit","level":"2"},"created_by":"u-admin"}`
	recorder := do(handler, http.MethodPut, scenariosPath+"/"+created.ID, updatedBody)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeScenario(t, recorder)
	if updated.ID != created.ID || updated.Name != "大客流疏散演练-加强版" ||
		updated.Category != "火灾" || updated.Background != "展厅烟感探测器触发" ||
		updated.Status != "停用" || updated.CreatedBy != "u-admin" ||
		updated.Metadata["level"] != "2" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeScenario(t, recorder)
	if fetched.Name != "大客流疏散演练-加强版" || fetched.Category != "火灾" ||
		fetched.Background != "展厅烟感探测器触发" || fetched.Status != "停用" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// PUT 缺省字段仍应用缺省值：status 回缺省 启用、metadata 回 {}。
	recorder = do(handler, http.MethodPut, scenariosPath+"/"+created.ID, `{"name":"第三版","category":"气象灾害","background":"台风红色预警"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT defaults: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	defaults := decodeScenario(t, recorder)
	if defaults.Status != "启用" {
		t.Fatalf("defaults: status = %q, want 启用", defaults.Status)
	}
	if len(defaults.Metadata) != 0 {
		t.Fatalf("defaults: metadata = %v, want an empty object", defaults.Metadata)
	}
	if defaults.CreatedBy != "" {
		t.Fatalf("defaults: created_by = %q, want empty", defaults.CreatedBy)
	}
	if defaults.CreatedAt != createdAt {
		t.Fatalf("defaults: created_at %q changed to %q", createdAt, defaults.CreatedAt)
	}

	// PUT 校验与 POST 一致。
	for name, body := range map[string]string{
		"missing name":       `{"category":"火灾","background":"背景"}`,
		"missing category":   `{"name":"演练","background":"背景"}`,
		"missing background": `{"name":"演练","category":"火灾"}`,
		"invalid category":   `{"name":"演练","category":"不存在的分类","background":"背景"}`,
		"invalid status":     `{"name":"演练","category":"火灾","background":"背景","status":"草稿"}`,
	} {
		recorder := do(handler, http.MethodPut, scenariosPath+"/"+created.ID, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 不存在的 id → 404。
	recorder = do(handler, http.MethodPut, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", updatedBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /scenarios/{id} ──────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeleteScenario(t *testing.T) {
	handler := testMux(nil)
	created := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodDelete, scenariosPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, scenariosPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ─────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：collection 含 GET/POST，item
// 含 GET/PUT/DELETE。
func TestScenariosMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, scenariosPath, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /scenarios: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /scenarios Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /scenarios/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /scenarios/{id} Allow = %q, want it to contain GET, PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS /scenarios 预检返回 204，Allow-Methods 含
// POST/PUT/DELETE（以及 GET/OPTIONS）。
func TestScenariosCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{scenariosPath, scenariosPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV"} {
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
