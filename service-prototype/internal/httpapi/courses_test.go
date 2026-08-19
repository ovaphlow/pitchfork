package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

const coursesPath = "/crate-api/prototype/v1/courses"

// ulidPattern matches a 26-character Crockford Base32 ULID (the alphabet
// omits I, L, O and U).
var ulidPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// courseJSON mirrors the course response for assertions.
type courseJSON struct {
	ID        string         `json:"id"`
	Title     string         `json:"title"`
	Topic     string         `json:"topic"`
	Type      string         `json:"type"`
	Status    string         `json:"status"`
	Metadata  map[string]any `json:"metadata"`
	CreatedBy string         `json:"created_by"`
	CreatedAt string         `json:"created_at"`
	UpdatedAt string         `json:"updated_at"`
}

type listJSON struct {
	Records []courseJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

type errorJSON struct {
	Error string `json:"error"`
}

// do issues a request against the handler and returns the recorder.
func do(handler http.Handler, method, target, body string) *httptest.ResponseRecorder {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, target, reader)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	return recorder
}

// decodeError asserts the body is a JSON error and returns its message.
func decodeError(t *testing.T, recorder *httptest.ResponseRecorder) string {
	t.Helper()
	if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", contentType)
	}
	var payload errorJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("body %q is not a JSON error: %v", recorder.Body.String(), err)
	}
	if payload.Error == "" {
		t.Fatalf("body %q has an empty error message", recorder.Body.String())
	}
	return payload.Error
}

func decodeCourse(t *testing.T, recorder *httptest.ResponseRecorder) courseJSON {
	t.Helper()
	var course courseJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &course); err != nil {
		t.Fatalf("body %q is not a course JSON: %v", recorder.Body.String(), err)
	}
	return course
}

func decodeList(t *testing.T, recorder *httptest.ResponseRecorder) listJSON {
	t.Helper()
	var list listJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createCourse posts a valid course body and asserts 201; returns the
// created course.
func createCourse(t *testing.T, handler http.Handler, body string) courseJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, coursesPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeCourse(t, recorder)
}

const validCourseBody = `{"title":"客流组织基础","topic":"客流评估与引导","type":"线上授课","metadata":{"source":"merit"},"created_by":"u-admin"}`

// ─── POST /courses ───────────────────────────────────────────────────

// 缺 title/topic/type → 400，错误响应体为 {error}。
func TestCreateCourseMissingRequiredFields(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"missing title": `{"topic":"客流评估与引导","type":"线上授课"}`,
		"blank title":   `{"title":"  ","topic":"客流评估与引导","type":"线上授课"}`,
		"missing topic": `{"title":"客流组织基础","type":"线上授课"}`,
		"missing type":  `{"title":"客流组织基础","topic":"客流评估与引导"}`,
	} {
		recorder := do(handler, http.MethodPost, coursesPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		if message := decodeError(t, recorder); message == "" {
			t.Fatalf("%s: empty error message", name)
		}
	}
}

// 非法 topic/type/status（含显式传非法 status）→ 400，错误响应体为 {error}。
// 空 status 视为缺省（默认启用），不属非法值。
func TestCreateCourseInvalidEnums(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"invalid topic":  `{"title":"课程","topic":"不存在的专题","type":"线上授课"}`,
		"invalid type":   `{"title":"课程","topic":"客流评估与引导","type":"直播"}`,
		"invalid status": `{"title":"课程","topic":"客流评估与引导","type":"线上授课","status":"草稿"}`,
	} {
		recorder := do(handler, http.MethodPost, coursesPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
	// 空 status 与缺省等价：默认启用。
	blankStatus := createCourse(t, handler, `{"title":"课程","topic":"客流评估与引导","type":"线上授课","status":""}`)
	if blankStatus.Status != "启用" {
		t.Fatalf("blank status: status = %q, want 启用", blankStatus.Status)
	}
}

// 合法创建：默认 status=启用，返回 201，id 为服务端生成的 26 位 Crockford Base32 ULID。
func TestCreateCourseSuccess(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, coursesPath, validCourseBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	course := decodeCourse(t, recorder)
	if !ulidPattern.MatchString(course.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", course.ID)
	}
	if course.Title != "客流组织基础" {
		t.Fatalf("title = %q, want 客流组织基础", course.Title)
	}
	if course.Topic != "客流评估与引导" {
		t.Fatalf("topic = %q, want 客流评估与引导", course.Topic)
	}
	if course.Type != "线上授课" {
		t.Fatalf("type = %q, want 线上授课", course.Type)
	}
	if course.Status != "启用" {
		t.Fatalf("status = %q, want 启用 (default)", course.Status)
	}
	if course.CreatedAt == "" || course.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", course)
	}
}

// 携带 created_by / metadata 时创建成功且响应回显；缺省亦可创建。
func TestCreateCourseEchoesCreatedByAndMetadata(t *testing.T) {
	handler := testMux(nil)

	withFields := createCourse(t, handler, validCourseBody)
	if withFields.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", withFields.CreatedBy)
	}
	if withFields.Metadata["source"] != "merit" {
		t.Fatalf("metadata = %v, want it to echo {source: merit}", withFields.Metadata)
	}

	withoutFields := createCourse(t, handler, `{"title":"线下课堂","topic":"安全应急处置","type":"线下授课"}`)
	if withoutFields.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", withoutFields.CreatedBy)
	}
	if len(withoutFields.Metadata) != 0 {
		t.Fatalf("metadata = %v, want an empty object when omitted", withoutFields.Metadata)
	}
}

// 畸形 JSON 请求体 → 400 {error}。
func TestCreateCourseMalformedBody(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, coursesPath, `{"title": `)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /courses ────────────────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非 null。
func TestListCoursesEmpty(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, coursesPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 筛选 topic/type/status 生效，meta.total 为筛选后的总数。
func TestListCoursesFiltering(t *testing.T) {
	handler := testMux(nil)
	createCourse(t, handler, `{"title":"客流一","topic":"客流评估与引导","type":"线上授课"}`)
	createCourse(t, handler, `{"title":"客流二","topic":"客流评估与引导","type":"线下授课"}`)
	createCourse(t, handler, `{"title":"防灾一","topic":"自然灾害防范","type":"线上授课"}`)
	createCourse(t, handler, `{"title":"舆情一","topic":"舆情应对","type":"线上授课","status":"停用"}`)

	cases := []struct {
		name   string
		query  string
		total  int
		titles []string
	}{
		{"by topic", "?topic=" + "客流评估与引导", 2, []string{"客流一", "客流二"}},
		{"by type", "?type=" + "线上授课", 3, []string{"客流一", "防灾一", "舆情一"}},
		{"by status", "?status=" + "停用", 1, []string{"舆情一"}},
		{"topic+type", "?topic=" + "客流评估与引导" + "&type=" + "线上授课", 1, []string{"客流一"}},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodGet, coursesPath+testCase.query, "")
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200", testCase.name, recorder.Code)
		}
		list := decodeList(t, recorder)
		if list.Meta.Total != testCase.total {
			t.Fatalf("%s: total = %d, want %d", testCase.name, list.Meta.Total, testCase.total)
		}
		if len(list.Records) != len(testCase.titles) {
			t.Fatalf("%s: records = %d, want %d", testCase.name, len(list.Records), len(testCase.titles))
		}
		for i, title := range testCase.titles {
			if list.Records[i].Title != title {
				t.Fatalf("%s: records[%d].title = %q, want %q", testCase.name, i, list.Records[i].Title, title)
			}
		}
	}
}

// limit/offset 分页生效，meta.total 保持筛选后的总数。
func TestListCoursesPagination(t *testing.T) {
	handler := testMux(nil)
	titles := []string{"课程一", "课程二", "课程三", "课程四", "课程五"}
	for _, title := range titles {
		createCourse(t, handler, `{"title":"`+title+`","topic":"客流评估与引导","type":"线上授课"}`)
	}

	recorder := do(handler, http.MethodGet, coursesPath+"?limit=2&offset=0", "")
	list := decodeList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 5", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Title != "课程一" || list.Records[1].Title != "课程二" {
		t.Fatalf("first page titles = %q %q, want 课程一 课程二", list.Records[0].Title, list.Records[1].Title)
	}

	recorder = do(handler, http.MethodGet, coursesPath+"?limit=2&offset=4", "")
	list = decodeList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=4: records = %d, total = %d; want 1 / 5", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Title != "课程五" {
		t.Fatalf("last page title = %q, want 课程五", list.Records[0].Title)
	}

	recorder = do(handler, http.MethodGet, coursesPath+"?limit=2&offset=10", "")
	list = decodeList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d; want 0 / 5", len(list.Records), list.Meta.Total)
	}
}

// 筛选传非法枚举值或非法 limit/offset → 400 {error}。
func TestListCoursesInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	for name, query := range map[string]string{
		"invalid topic":  "?topic=" + "不存在的专题",
		"invalid type":   "?type=" + "直播",
		"invalid status": "?status=" + "草稿",
		"invalid limit":  "?limit=abc",
		"negative limit": "?limit=-1",
		"invalid offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, coursesPath+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /courses/{id} ───────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetCourse(t *testing.T) {
	handler := testMux(nil)
	created := createCourse(t, handler, validCourseBody)

	recorder := do(handler, http.MethodGet, coursesPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	course := decodeCourse(t, recorder)
	if course.ID != created.ID || course.Title != "客流组织基础" ||
		course.Topic != "客流评估与引导" || course.Type != "线上授课" ||
		course.Status != "启用" || course.CreatedBy != "u-admin" ||
		course.Metadata["source"] != "merit" {
		t.Fatalf("GET response %+v does not echo the created course", course)
	}

	recorder = do(handler, http.MethodGet, coursesPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /courses/{id} ───────────────────────────────────────────────

// PUT 校验口径与 POST 一致：缺必填或非法枚举 → 400；成功返回 200 与更新后的记录，
// 随后 GET 反映更新；不存在的 id 返回 404。
func TestUpdateCourse(t *testing.T) {
	handler := testMux(nil)
	created := createCourse(t, handler, validCourseBody)

	updatedBody := `{"title":"客流组织进阶","topic":"安全应急处置","type":"线下授课","status":"停用","metadata":{"source":"merit","level":"2"},"created_by":"u-admin"}`
	recorder := do(handler, http.MethodPut, coursesPath+"/"+created.ID, updatedBody)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeCourse(t, recorder)
	if updated.ID != created.ID || updated.Title != "客流组织进阶" ||
		updated.Topic != "安全应急处置" || updated.Type != "线下授课" ||
		updated.Status != "停用" || updated.CreatedBy != "u-admin" ||
		updated.Metadata["level"] != "2" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}

	recorder = do(handler, http.MethodGet, coursesPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeCourse(t, recorder)
	if fetched.Title != "客流组织进阶" || fetched.Topic != "安全应急处置" ||
		fetched.Type != "线下授课" || fetched.Status != "停用" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	for name, body := range map[string]string{
		"missing title":  `{"topic":"安全应急处置","type":"线下授课"}`,
		"invalid topic":  `{"title":"课程","topic":"不存在的专题","type":"线上授课"}`,
		"invalid type":   `{"title":"课程","topic":"安全应急处置","type":"直播"}`,
		"invalid status": `{"title":"课程","topic":"安全应急处置","type":"线下授课","status":"草稿"}`,
	} {
		recorder := do(handler, http.MethodPut, coursesPath+"/"+created.ID, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	recorder = do(handler, http.MethodPut, coursesPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", updatedBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /courses/{id} ────────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeleteCourse(t *testing.T) {
	handler := testMux(nil)
	created := createCourse(t, handler, validCourseBody)

	recorder := do(handler, http.MethodDelete, coursesPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, coursesPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, coursesPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ─────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头。
func TestCoursesMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, coursesPath, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /courses: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /courses Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, coursesPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /courses/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /courses/{id} Allow = %q, want it to contain PUT and DELETE", allow)
	}
}

// 允许 Origin 的 OPTIONS /courses 预检返回 204，Allow-Methods 含 POST/PUT/DELETE。
func TestCoursesCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{coursesPath, coursesPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV"} {
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

// 字面路径 /courses 优先于 {resource} 通配：POST /courses 不会落入 healthz 的 405。
func TestCoursesLiteralPathTakesPrecedence(t *testing.T) {
	handler := testMux(nil)
	created := createCourse(t, handler, validCourseBody)
	if created.ID == "" {
		t.Fatal("create must succeed on the literal /courses path")
	}
	// {resource}=courses 的 GET 走字面路由而非 handleResource 的 404。
	recorder := do(handler, http.MethodGet, coursesPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET /courses: status = %d, want 200", recorder.Code)
	}
}
