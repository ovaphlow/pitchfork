package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// indicatorsPath is the unified collection path of the evaluation
// indicator dictionary.
const indicatorsPath = "/crate-api/prototype/v1/evaluation/indicators"

// indicatorItemPath builds the indicator item path of the given id.
func indicatorItemPath(id string) string {
	return indicatorsPath + "/" + id
}

// indicatorJSON mirrors the indicator response for assertions.
type indicatorJSON struct {
	ID          string `json:"id"`
	Dimension   string `json:"dimension"`
	Title       string `json:"title"`
	Weight      int    `json:"weight"`
	Demo        bool   `json:"demo"`
	SortOrder   int    `json:"sort_order"`
	Description string `json:"description"`
	CreatedBy   string `json:"created_by"`
	CreatedAt   string `json:"created_at"`
	UpdatedAt   string `json:"updated_at"`
}

type indicatorListJSON struct {
	Records []indicatorJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeIndicator(t *testing.T, recorder *httptest.ResponseRecorder) indicatorJSON {
	t.Helper()
	var indicator indicatorJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &indicator); err != nil {
		t.Fatalf("body %q is not an indicator JSON: %v", recorder.Body.String(), err)
	}
	return indicator
}

func decodeIndicatorList(t *testing.T, recorder *httptest.ResponseRecorder) indicatorListJSON {
	t.Helper()
	var list indicatorListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// decodeErrorPayload reads the { "error": ... } body of a failure
// response.
func decodeErrorPayload(t *testing.T, recorder *httptest.ResponseRecorder) string {
	t.Helper()
	var payload map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("body %q is not a JSON error: %v", recorder.Body.String(), err)
	}
	if payload["error"] == "" {
		t.Fatalf("body %q carries no error message", recorder.Body.String())
	}
	return payload["error"]
}

// postIndicator POSTs an indicator body and asserts 201; returns the
// created indicator.
func postIndicator(t *testing.T, handler http.Handler, body string) indicatorJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, indicatorsPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeIndicator(t, recorder)
}

// fixtureScoreRefs is the test fixture behind the evaluation
// ScoreRefChecker interface: it returns a reference count for every
// indicator (the future evaluation_scores store implements the same
// interface). The count is a field so a test can drop it to zero and
// reuse the same fixture.
type fixtureScoreRefs struct{ count int }

func (f *fixtureScoreRefs) CountScoresByIndicator(context.Context, string) (int, error) {
	return f.count, nil
}

// indicatorMuxWithChecker builds the evaluation indicator routes with a
// score-ref checker injected (the fixture that stands in for the
// evaluation_scores store of card 000024, which wires the checker at
// the composition root).
func indicatorMuxWithChecker(checker evaluation.ScoreRefChecker) http.Handler {
	handler := newIndicatorsHandler(evaluation.NewInMemoryStore())
	handler.service.SetScoreRefChecker(checker)
	mux := http.NewServeMux()
	mux.HandleFunc(indicatorsPath, handler.handleCollection)
	mux.HandleFunc(indicatorsPath+"/{id}", handler.handleItem)
	return mux
}

// assertIndicatorDefaults checks the field contract of a created or
// updated indicator: ULID id, the given dimension/title and the default
// weight 1, demo false, sort_order 0, description ” and created_by ”.
func assertIndicatorDefaults(t *testing.T, indicator indicatorJSON, dimension, title string) {
	t.Helper()
	if !ulidPattern.MatchString(indicator.ID) {
		t.Errorf("id = %q, want a 26-character Crockford Base32 ULID", indicator.ID)
	}
	if indicator.Dimension != dimension || indicator.Title != title {
		t.Errorf("dimension/title = %q/%q, want %q/%q", indicator.Dimension, indicator.Title, dimension, title)
	}
	if indicator.Weight != 1 {
		t.Errorf("weight = %d, want default 1", indicator.Weight)
	}
	if indicator.Demo {
		t.Errorf("demo = true, want default false")
	}
	if indicator.SortOrder != 0 {
		t.Errorf("sort_order = %d, want default 0", indicator.SortOrder)
	}
	if indicator.Description != "" || indicator.CreatedBy != "" {
		t.Errorf("description/created_by = %q/%q, want empty defaults", indicator.Description, indicator.CreatedBy)
	}
	if indicator.CreatedAt == "" || indicator.UpdatedAt == "" {
		t.Errorf("timestamps = %q/%q, want server-set values", indicator.CreatedAt, indicator.UpdatedAt)
	}
}

// ─── GET /evaluation/indicators ──────────────────────────────────────

// 空表返回 {records:[], meta:{total:0}}。
func TestIndicatorListEmpty(t *testing.T) {
	recorder := get(testMux(nil), indicatorsPath, nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeIndicatorList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("records/total = %d/%d, want 0/0", len(list.Records), list.Meta.Total)
	}
}

// ─── POST /evaluation/indicators ─────────────────────────────────────

// POST 成功 201 返回完整对象：id 为服务端生成的 26 位 Crockford Base32
// ULID、created_at/updated_at 服务端设置、created_by 默认 ”；缺省的
// weight/demo/sort_order/description 按默认值写入，GET /{id} 200 回显
// 与 POST 响应一致。
func TestIndicatorCreateWithDefaults(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPost, indicatorsPath, `{"dimension":"响应速度","title":"预警响应速度"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	created := decodeIndicator(t, recorder)
	assertIndicatorDefaults(t, created, "响应速度", "预警响应速度")

	getRecorder := get(handler, indicatorItemPath(created.ID), nil)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", getRecorder.Code, getRecorder.Body.String())
	}
	echoed := decodeIndicator(t, getRecorder)
	if echoed.ID != created.ID || echoed.CreatedAt != created.CreatedAt || echoed.UpdatedAt != created.UpdatedAt {
		t.Fatalf("GET echo diverges from POST response: %+v vs %+v", echoed, created)
	}
	if echoed.Weight != 1 || echoed.Demo || echoed.SortOrder != 0 || echoed.Description != "" || echoed.CreatedBy != "" {
		t.Errorf("GET echo defaults = %d/%v/%d/%q/%q, want 1/false/0/''/''", echoed.Weight, echoed.Demo, echoed.SortOrder, echoed.Description, echoed.CreatedBy)
	}
}

// POST 携带全部字段时按请求值写入并回显；维度枚举、权重、demo、sort_order
// 均透传。
func TestIndicatorCreateCarriesExplicitFields(t *testing.T) {
	handler := testMux(nil)
	created := postIndicator(t, handler, `{
		"dimension":"观众安全","title":"观众疏散组织","weight":3,"demo":true,
		"sort_order":2,"description":"疏散组织有序、路线合理","created_by":"admin"
	}`)
	if !ulidPattern.MatchString(created.ID) {
		t.Fatalf("id = %q, want a ULID", created.ID)
	}
	if created.Weight != 3 || !created.Demo || created.SortOrder != 2 {
		t.Errorf("weight/demo/sort_order = %d/%v/%d, want 3/true/2", created.Weight, created.Demo, created.SortOrder)
	}
	if created.Description != "疏散组织有序、路线合理" || created.CreatedBy != "admin" {
		t.Errorf("description/created_by = %q/%q, want the explicit values", created.Description, created.CreatedBy)
	}
}

// POST 失败路径（双入口覆盖之一）：缺 title、非法 dimension、weight<1
// 均 400，错误响应体统一 { "error": ... }。
func TestIndicatorCreateValidation(t *testing.T) {
	handler := testMux(nil)
	cases := []struct {
		name string
		body string
	}{
		{"missing title", `{"dimension":"响应速度"}`},
		{"blank title", `{"dimension":"响应速度","title":"  "}`},
		{"invalid dimension", `{"dimension":"其他","title":"测试"}`},
		{"zero weight", `{"dimension":"响应速度","title":"测试","weight":0}`},
		{"negative weight", `{"dimension":"响应速度","title":"测试","weight":-1}`},
		{"malformed body", `{"dimension":`},
	}
	for _, tc := range cases {
		recorder := do(handler, http.MethodPost, indicatorsPath, tc.body)
		if recorder.Code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want 400; body = %s", tc.name, recorder.Code, recorder.Body.String())
			continue
		}
		if message := decodeErrorPayload(t, recorder); message == "" {
			t.Errorf("%s: empty error message", tc.name)
		}
	}
}

// ─── GET /evaluation/indicators/{id} ─────────────────────────────────

// GET 不存在的 ID 404，错误响应体为 { "error": ... }。
func TestIndicatorGetNotFound(t *testing.T) {
	recorder := get(testMux(nil), indicatorItemPath("06G01KFRJTE84EP3234FBD2YD4"), nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", recorder.Code)
	}
	decodeErrorPayload(t, recorder)
}

// ─── PUT /evaluation/indicators/{id} ─────────────────────────────────

// PUT 成功 200 返回更新后对象：请求字段生效，缺省字段按默认值重置
// （weight→1、demo→false、sort_order→0、description→”、created_by→”），
// created_at 保留、updated_at 刷新；PUT 后 GET 反映更新。
func TestIndicatorUpdate(t *testing.T) {
	handler := testMux(nil)
	created := postIndicator(t, handler, `{"dimension":"观众安全","title":"观众疏散组织","weight":3,"demo":true,"sort_order":2,"description":"疏散组织有序、路线合理","created_by":"admin"}`)

	putRecorder := do(handler, http.MethodPut, indicatorItemPath(created.ID), `{"dimension":"响应速度","title":"预警响应速度"}`)
	if putRecorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", putRecorder.Code, putRecorder.Body.String())
	}
	updated := decodeIndicator(t, putRecorder)
	if updated.ID != created.ID {
		t.Errorf("id = %q, want %q (preserved)", updated.ID, created.ID)
	}
	if updated.Dimension != "响应速度" || updated.Title != "预警响应速度" {
		t.Errorf("dimension/title = %q/%q, want the updated values", updated.Dimension, updated.Title)
	}
	if updated.Weight != 1 || updated.Demo || updated.SortOrder != 0 {
		t.Errorf("weight/demo/sort_order = %d/%v/%d, want the defaults 1/false/0 (omitted fields reset)", updated.Weight, updated.Demo, updated.SortOrder)
	}
	if updated.Description != "" || updated.CreatedBy != "" {
		t.Errorf("description/created_by = %q/%q, want empty (omitted fields reset)", updated.Description, updated.CreatedBy)
	}
	if updated.CreatedAt != created.CreatedAt {
		t.Errorf("created_at = %q, want %q (preserved)", updated.CreatedAt, created.CreatedAt)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Errorf("updated_at = %q, want a refreshed value", updated.UpdatedAt)
	}

	getRecorder := get(handler, indicatorItemPath(created.ID), nil)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", getRecorder.Code, getRecorder.Body.String())
	}
	echoed := decodeIndicator(t, getRecorder)
	if echoed.Title != "预警响应速度" || echoed.Dimension != "响应速度" {
		t.Errorf("GET after PUT = %q/%q, want the updated values", echoed.Title, echoed.Dimension)
	}
	if echoed.UpdatedAt != updated.UpdatedAt {
		t.Errorf("GET updated_at = %q, want %q (PUT response echoed)", echoed.UpdatedAt, updated.UpdatedAt)
	}
}

// PUT 失败路径（双入口覆盖之二）：缺 title、非法 dimension、weight<1 均
// 400；不存在的 ID 404；错误响应体统一 { "error": ... }。
func TestIndicatorUpdateValidation(t *testing.T) {
	handler := testMux(nil)
	created := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度"}`)

	cases := []struct {
		name string
		id   string
		body string
		code int
	}{
		{"missing title", created.ID, `{"dimension":"响应速度"}`, http.StatusBadRequest},
		{"invalid dimension", created.ID, `{"dimension":"其他","title":"测试"}`, http.StatusBadRequest},
		{"zero weight", created.ID, `{"dimension":"响应速度","title":"测试","weight":0}`, http.StatusBadRequest},
		{"negative weight", created.ID, `{"dimension":"响应速度","title":"测试","weight":-1}`, http.StatusBadRequest},
		{"unknown id", "06G01KFRJTE84EP3234FBD2YD4", `{"dimension":"响应速度","title":"测试"}`, http.StatusNotFound},
	}
	for _, tc := range cases {
		recorder := do(handler, http.MethodPut, indicatorItemPath(tc.id), tc.body)
		if recorder.Code != tc.code {
			t.Errorf("%s: status = %d, want %d; body = %s", tc.name, recorder.Code, tc.code, recorder.Body.String())
			continue
		}
		decodeErrorPayload(t, recorder)
	}
}

// ─── DELETE /evaluation/indicators/{id} ──────────────────────────────

// DELETE 成功 204，DELETE 后 GET 404；DELETE 不存在的 ID 404。
func TestIndicatorDelete(t *testing.T) {
	handler := testMux(nil)
	created := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度"}`)

	deleteRecorder := do(handler, http.MethodDelete, indicatorItemPath(created.ID), "")
	if deleteRecorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204", deleteRecorder.Code)
	}
	getRecorder := get(handler, indicatorItemPath(created.ID), nil)
	if getRecorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE status = %d, want 404", getRecorder.Code)
	}

	again := do(handler, http.MethodDelete, indicatorItemPath(created.ID), "")
	if again.Code != http.StatusNotFound {
		t.Fatalf("DELETE again status = %d, want 404", again.Code)
	}
	decodeErrorPayload(t, again)
}

// DELETE 已被评分引用（经引用检查接口注入同库 fixture：引用数 >0）的指标
// 返回 400 且错误消息为「指标已被评分引用，请先清理评分」，指标仍存在；
// 未引用时删除成功 204。
func TestIndicatorDeleteReferenced(t *testing.T) {
	refs := &fixtureScoreRefs{count: 2}
	handler := indicatorMuxWithChecker(refs)
	created := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度"}`)

	recorder := do(handler, http.MethodDelete, indicatorItemPath(created.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE referenced status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	if message := decodeErrorPayload(t, recorder); message != "指标已被评分引用，请先清理评分" {
		t.Errorf("error message = %q, want 指标已被评分引用，请先清理评分", message)
	}
	getRecorder := get(handler, indicatorItemPath(created.ID), nil)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET after rejected DELETE status = %d, want 200 (indicator must still exist)", getRecorder.Code)
	}

	// 引用数归零后删除成功（同一 store，同一引用检查器）。
	refs.count = 0
	recorder = do(handler, http.MethodDelete, indicatorItemPath(created.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE unreferenced status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── 列表：筛选、分页、排序 ──────────────────────────────────────────

// 列表 dimension 筛选生效、limit/offset 分页生效；排序口径
// (dimension, sort_order, created_at) 升序可断言（维度字节序为
// 协同效率 < 响应速度 < 处置规范性 < 文物安全 < 舆情管控 < 观众安全）。
func TestIndicatorListFilterPaginationSort(t *testing.T) {
	handler := testMux(nil)
	postIndicator(t, handler, `{"dimension":"观众安全","title":"观众疏散组织","demo":true,"sort_order":1}`)
	postIndicator(t, handler, `{"dimension":"响应速度","title":"力量到场速度","sort_order":3}`)
	postIndicator(t, handler, `{"dimension":"协同效率","title":"部门协同效率","sort_order":1}`)
	postIndicator(t, handler, `{"dimension":"文物安全","title":"文物转移保护","demo":true,"sort_order":1}`)
	postIndicator(t, handler, `{"dimension":"舆情管控","title":"舆情监测预警","demo":true,"sort_order":1}`)
	postIndicator(t, handler, `{"dimension":"处置规范性","title":"处置流程规范性","sort_order":1}`)
	postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度","sort_order":1}`)

	// 全量列表：7 条，(dimension, sort_order, created_at) 升序。
	recorder := get(handler, indicatorsPath, nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeIndicatorList(t, recorder)
	if list.Meta.Total != 7 || len(list.Records) != 7 {
		t.Fatalf("total/records = %d/%d, want 7/7", list.Meta.Total, len(list.Records))
	}
	wantOrder := []string{"部门协同效率", "预警响应速度", "力量到场速度", "处置流程规范性", "文物转移保护", "舆情监测预警", "观众疏散组织"}
	for index, want := range wantOrder {
		if list.Records[index].Title != want {
			t.Fatalf("sort order diverges at %d: got %q, want %q (full order %v)", index, list.Records[index].Title, want, titlesOf(list.Records))
		}
	}

	// dimension 筛选：只回 响应速度 且仍按 sort_order 升序。
	filtered := get(handler, indicatorsPath+"?dimension="+url.QueryEscape("响应速度"), nil)
	if filtered.Code != http.StatusOK {
		t.Fatalf("filtered status = %d, want 200", filtered.Code)
	}
	filteredList := decodeIndicatorList(t, filtered)
	if filteredList.Meta.Total != 2 || len(filteredList.Records) != 2 {
		t.Fatalf("filtered total/records = %d/%d, want 2/2", filteredList.Meta.Total, len(filteredList.Records))
	}
	if filteredList.Records[0].Title != "预警响应速度" || filteredList.Records[1].Title != "力量到场速度" {
		t.Errorf("filtered titles = %q/%q, want 预警响应速度/力量到场速度", filteredList.Records[0].Title, filteredList.Records[1].Title)
	}

	// limit/offset 分页：limit=3&offset=2 回第 3..5 条，total 仍为 7。
	page := get(handler, indicatorsPath+"?limit=3&offset=2", nil)
	if page.Code != http.StatusOK {
		t.Fatalf("page status = %d, want 200", page.Code)
	}
	pageList := decodeIndicatorList(t, page)
	if pageList.Meta.Total != 7 || len(pageList.Records) != 3 {
		t.Fatalf("page total/records = %d/%d, want 7/3", pageList.Meta.Total, len(pageList.Records))
	}
	if pageList.Records[0].Title != "力量到场速度" || pageList.Records[2].Title != "文物转移保护" {
		t.Errorf("page titles = %q..%q, want 力量到场速度..文物转移保护", pageList.Records[0].Title, pageList.Records[2].Title)
	}
}

func titlesOf(records []indicatorJSON) []string {
	titles := make([]string, 0, len(records))
	for _, record := range records {
		titles = append(titles, record.Title)
	}
	return titles
}

// 列表筛选参数一致覆盖：dimension 传非 6 维度值 400、limit/offset 非非负
// 整数 400，错误响应体统一 { "error": ... }。
func TestIndicatorListFilterValidation(t *testing.T) {
	handler := testMux(nil)
	for _, target := range []string{
		indicatorsPath + "?dimension=其他",
		indicatorsPath + "?limit=-1",
		indicatorsPath + "?limit=abc",
		indicatorsPath + "?offset=-1",
		indicatorsPath + "?offset=1.5",
	} {
		recorder := get(handler, target, nil)
		if recorder.Code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want 400; body = %s", target, recorder.Code, recorder.Body.String())
			continue
		}
		if message := decodeErrorPayload(t, recorder); message == "" {
			t.Errorf("%s: empty error message", target)
		}
	}
}

// ─── 其他方法 ────────────────────────────────────────────────────────

// 集合与单条路由的其他方法返回 405 JSON 并带 Allow。
func TestIndicatorMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	created := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度"}`)

	collectionRecorder := do(handler, http.MethodDelete, indicatorsPath, "")
	if collectionRecorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("collection DELETE status = %d, want 405", collectionRecorder.Code)
	}
	if allow := collectionRecorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Errorf("collection Allow = %q, want GET and POST", allow)
	}

	itemRecorder := do(handler, http.MethodPost, indicatorItemPath(created.ID), `{}`)
	if itemRecorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("item POST status = %d, want 405", itemRecorder.Code)
	}
	if allow := itemRecorder.Header().Get("Allow"); !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Errorf("item Allow = %q, want GET, PUT and DELETE", allow)
	}
}
