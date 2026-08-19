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

// assessmentPointsItemPath is the item route of an assessment point.
func assessmentPointsItemPath(pointID string) string {
	return "/crate-api/prototype/v1/assessment-points/" + pointID
}

// pointJSON mirrors the assessment point response for assertions.
type pointJSON struct {
	ID          string `json:"id"`
	ScenarioID  string `json:"scenario_id"`
	Title       string `json:"title"`
	Description string `json:"description"`
	CreatedBy   string `json:"created_by"`
	CreatedAt   string `json:"created_at"`
	UpdatedAt   string `json:"updated_at"`
}

type pointListJSON struct {
	Records []pointJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodePoint(t *testing.T, recorder *httptest.ResponseRecorder) pointJSON {
	t.Helper()
	var point pointJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &point); err != nil {
		t.Fatalf("body %q is not a point JSON: %v", recorder.Body.String(), err)
	}
	return point
}

func decodePointList(t *testing.T, recorder *httptest.ResponseRecorder) pointListJSON {
	t.Helper()
	var list pointListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createPoint posts a valid assessment point body and asserts 201;
// returns the created point.
func createPoint(t *testing.T, handler http.Handler, scenarioID, body string) pointJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, scenariosPath+"/"+scenarioID+"/assessment-points", body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodePoint(t, recorder)
}

// ─── POST /scenarios/{sid}/assessment-points ─────────────────────────

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// scenario_id 由路径决定并回显，description/created_by 透传，
// created_at/updated_at 为服务端时间。
func TestCreatePointSuccess(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, scenariosPath+"/"+scenario.ID+"/assessment-points",
		`{"title":"疏散指令传达","description":"考察指令传达是否准确","created_by":"u-admin"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	point := decodePoint(t, recorder)
	if !ulidPattern.MatchString(point.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", point.ID)
	}
	if point.ScenarioID != scenario.ID {
		t.Fatalf("scenario_id = %q, want %q (from the route path)", point.ScenarioID, scenario.ID)
	}
	if point.Title != "疏散指令传达" || point.Description != "考察指令传达是否准确" || point.CreatedBy != "u-admin" {
		t.Fatalf("create does not echo the input: %+v", point)
	}
	if point.CreatedAt == "" || point.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", point)
	}
}

// 缺省创建：description 缺省 ”、created_by 缺省 ”。
func TestCreatePointDefaults(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	point := createPoint(t, handler, scenario.ID, `{"title":"清点人数"}`)
	if point.Description != "" {
		t.Fatalf("description = %q, want empty when omitted", point.Description)
	}
	if point.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", point.CreatedBy)
	}
}

// scenario 不存在 → 404 {error}。
func TestCreatePointScenarioNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/assessment-points", `{"title":"疏散指令传达"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 缺 title（含空白）400、字段类型错误 400、畸形 JSON 400；错误响应体统一
// {error}。
func TestCreatePointInvalidInput(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	target := scenariosPath + "/" + scenario.ID + "/assessment-points"
	for name, body := range map[string]string{
		"missing title":    `{"description":"说明"}`,
		"blank title":      `{"title":"  "}`,
		"wrong title type": `{"title":123}`,
		"malformed json":   `{"title": `,
	} {
		recorder := do(handler, http.MethodPost, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /scenarios/{sid}/assessment-points ──────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非
// null。
func TestListPointsEmpty(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/assessment-points", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodePointList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 仅返回路径场景的考核要点（其他场景的要点不出现）；按 created_at ASC
// 排序。
func TestListPointsSortedAndScopedToPath(t *testing.T) {
	handler := testMux(nil)
	scenarioA := createScenario(t, handler, validScenarioBody)
	scenarioB := createScenario(t, handler, `{"name":"停电应急演练","category":"停电与基础设施","background":"市电中断"}`)

	createPoint(t, handler, scenarioA.ID, `{"title":"要点三"}`)
	// 保证 created_at 严格递增（毫秒级分辨率），排序断言确定。
	time.Sleep(5 * time.Millisecond)
	createPoint(t, handler, scenarioA.ID, `{"title":"要点一"}`)
	time.Sleep(5 * time.Millisecond)
	createPoint(t, handler, scenarioA.ID, `{"title":"要点二"}`)
	// 另一个场景的要点不得出现在 A 的列表里。
	createPoint(t, handler, scenarioB.ID, `{"title":"B场景要点"}`)

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenarioA.ID+"/assessment-points", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodePointList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	for i, title := range []string{"要点三", "要点一", "要点二"} {
		if list.Records[i].Title != title {
			t.Fatalf("records[%d].title = %q, want %q (created_at ASC)", i, list.Records[i].Title, title)
		}
	}

	// B 的列表只含自己的要点。
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+scenarioB.ID+"/assessment-points", "")
	list = decodePointList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].Title != "B场景要点" {
		t.Fatalf("scenario B list = %+v, want only its own point", list)
	}
}

// limit/offset 分页生效；缺省 limit 50；meta.total 保持分页前总数。
func TestListPointsPagination(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		createPoint(t, handler, scenario.ID, fmt.Sprintf(`{"title":"要点%02d"}`, i))
	}

	recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/assessment-points?limit=2&offset=0", "")
	list := decodePointList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Title != "要点01" || list.Records[1].Title != "要点02" {
		t.Fatalf("first page titles = %q %q, want 要点01 要点02", list.Records[0].Title, list.Records[1].Title)
	}

	// limit=2&offset=52（末页）
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/assessment-points?limit=2&offset=52", "")
	list = decodePointList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 || list.Records[0].Title != "要点53" {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d, title = %q; want 1 / 53 / 要点53",
			len(list.Records), list.Meta.Total, func() string {
				if len(list.Records) == 0 {
					return ""
				}
				return list.Records[0].Title
			}())
	}

	// 缺省 limit 50
	recorder = do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/assessment-points", "")
	list = decodePointList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}
}

// 非法 limit/offset → 400 {error}。
func TestListPointsInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	for name, query := range map[string]string{
		"invalid limit":   "?limit=abc",
		"negative limit":  "?limit=-1",
		"invalid offset":  "?offset=abc",
		"negative offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, scenariosPath+"/"+scenario.ID+"/assessment-points"+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// scenario 不存在 → 404 {error}。
func TestListPointsScenarioNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/assessment-points", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /assessment-points/{id} ─────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetPoint(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createPoint(t, handler, scenario.ID, `{"title":"疏散指令传达","description":"考察指令传达是否准确","created_by":"u-admin"}`)

	recorder := do(handler, http.MethodGet, assessmentPointsItemPath(created.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	point := decodePoint(t, recorder)
	if point.ID != created.ID || point.ScenarioID != scenario.ID ||
		point.Title != "疏散指令传达" || point.Description != "考察指令传达是否准确" || point.CreatedBy != "u-admin" {
		t.Fatalf("GET response %+v does not echo the created point", point)
	}

	recorder = do(handler, http.MethodGet, assessmentPointsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /assessment-points/{id} ─────────────────────────────────────

// PUT 整体替换：成功 200 返回更新后对象，created_at 保留、updated_at 刷新，
// 随后 GET 反映更新；校验口径与 POST 一致（缺 title 400）；不存在的 id 404。
func TestUpdatePoint(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createPoint(t, handler, scenario.ID, `{"title":"疏散指令传达","description":"考察指令传达是否准确","created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 PUT 的 updated_at 与 created_at 可区分。
	time.Sleep(5 * time.Millisecond)

	recorder := do(handler, http.MethodPut, assessmentPointsItemPath(created.ID),
		`{"title":"疏散指令传达-加强","description":"考察指令传达准确性与时效性","created_by":"u-other"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodePoint(t, recorder)
	if updated.ID != created.ID || updated.ScenarioID != scenario.ID ||
		updated.Title != "疏散指令传达-加强" ||
		updated.Description != "考察指令传达准确性与时效性" || updated.CreatedBy != "u-other" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, assessmentPointsItemPath(created.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodePoint(t, recorder)
	if fetched.Title != "疏散指令传达-加强" || fetched.Description != "考察指令传达准确性与时效性" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// PUT 缺省字段仍应用缺省值：description 回 ''。
	recorder = do(handler, http.MethodPut, assessmentPointsItemPath(created.ID), `{"title":"第三版"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT defaults: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	defaults := decodePoint(t, recorder)
	if defaults.Description != "" || defaults.CreatedBy != "" {
		t.Fatalf("defaults: description = %q, created_by = %q; want both empty", defaults.Description, defaults.CreatedBy)
	}
	if defaults.CreatedAt != createdAt {
		t.Fatalf("defaults: created_at %q changed to %q", createdAt, defaults.CreatedAt)
	}

	// PUT 校验与 POST 一致。
	for name, body := range map[string]string{
		"missing title": `{"description":"说明"}`,
		"blank title":   `{"title":" "}`,
	} {
		recorder := do(handler, http.MethodPut, assessmentPointsItemPath(created.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 不存在的 id → 404。
	recorder = do(handler, http.MethodPut, assessmentPointsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"title":"疏散指令传达"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /assessment-points/{id} ──────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeletePoint(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	created := createPoint(t, handler, scenario.ID, `{"title":"疏散指令传达"}`)

	recorder := do(handler, http.MethodDelete, assessmentPointsItemPath(created.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, assessmentPointsItemPath(created.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, assessmentPointsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ─────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：collection 含 GET/POST，item
// 含 GET/PUT/DELETE。
func TestPointsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, scenariosPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/assessment-points", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /scenarios/{sid}/assessment-points: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /scenarios/{sid}/assessment-points Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, assessmentPointsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /assessment-points/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /assessment-points/{id} Allow = %q, want it to contain GET, PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS 预检返回 204，Allow-Methods 含 POST/PUT/DELETE
// （以及 GET/OPTIONS）：collection 与 item 路由均覆盖。
func TestPointsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		scenariosPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV/assessment-points",
		assessmentPointsItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
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
