package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

const questionsPath = "/crate-api/prototype/v1/questions"

// questionJSON mirrors the question response for assertions.
type questionJSON struct {
	ID          string         `json:"id"`
	Type        string         `json:"type"`
	Difficulty  int            `json:"difficulty"`
	Tags        []string       `json:"tags"`
	Content     string         `json:"content"`
	Options     []string       `json:"options"`
	Answer      any            `json:"answer"`
	Explanation string         `json:"explanation"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

type questionListJSON struct {
	Records []questionJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

type importJSON struct {
	Imported int            `json:"imported"`
	Records  []questionJSON `json:"records"`
}

type importErrorJSON struct {
	Error   string `json:"error"`
	Details []struct {
		Index   int    `json:"index"`
		Message string `json:"message"`
	} `json:"details"`
}

func decodeQuestion(t *testing.T, recorder *httptest.ResponseRecorder) questionJSON {
	t.Helper()
	var question questionJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &question); err != nil {
		t.Fatalf("body %q is not a question JSON: %v", recorder.Body.String(), err)
	}
	return question
}

func decodeQuestionList(t *testing.T, recorder *httptest.ResponseRecorder) questionListJSON {
	t.Helper()
	var list questionListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a question list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

func decodeImport(t *testing.T, recorder *httptest.ResponseRecorder) importJSON {
	t.Helper()
	var payload importJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("body %q is not an import JSON: %v", recorder.Body.String(), err)
	}
	return payload
}

func decodeImportError(t *testing.T, recorder *httptest.ResponseRecorder) importErrorJSON {
	t.Helper()
	var payload importErrorJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("body %q is not an import error JSON: %v", recorder.Body.String(), err)
	}
	return payload
}

// createQuestion posts a valid question body and asserts 201; returns the
// created question.
func createQuestion(t *testing.T, handler http.Handler, body string) questionJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, questionsPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeQuestion(t, recorder)
}

const validQuestionBody = `{"type":"单选","difficulty":3,"tags":["客流","基础"],"content":"车站客流高峰时段为？","options":["A","B","C"],"answer":"A","explanation":"解析","metadata":{"source":"merit"},"created_by":"u-admin"}`

// ─── POST /questions ─────────────────────────────────────────────────

// 缺 type/content/difficulty/answer（四个 type 均必填）→ 400，错误响应体为 {error}。
func TestCreateQuestionMissingRequiredFields(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"missing type":       `{"difficulty":2,"content":"题干","answer":"A"}`,
		"missing content":    `{"type":"单选","difficulty":2,"answer":"A"}`,
		"blank content":      `{"type":"单选","difficulty":2,"content":"   ","answer":"A"}`,
		"missing difficulty": `{"type":"单选","content":"题干","answer":"A"}`,
		"missing answer 单选":  `{"type":"单选","difficulty":2,"content":"题干","options":["A","B"]}`,
		"missing answer 多选":  `{"type":"多选","difficulty":2,"content":"题干","options":["A","B"]}`,
		"missing answer 判断":  `{"type":"判断","difficulty":2,"content":"题干"}`,
		"missing answer 填空":  `{"type":"填空","difficulty":2,"content":"题干"}`,
	} {
		recorder := do(handler, http.MethodPost, questionsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		if message := decodeError(t, recorder); message == "" {
			t.Fatalf("%s: empty error message", name)
		}
	}
}

// 非法 type 枚举、difficulty 越界（0/6）或非整数 → 400 {error}。
func TestCreateQuestionInvalidTypeAndDifficulty(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"invalid type":      `{"type":"简答","difficulty":2,"content":"题干","answer":"A"}`,
		"empty type":        `{"type":"","difficulty":2,"content":"题干","answer":"A"}`,
		"difficulty zero":   `{"type":"单选","difficulty":0,"content":"题干","answer":"A"}`,
		"difficulty six":    `{"type":"单选","difficulty":6,"content":"题干","answer":"A"}`,
		"difficulty string": `{"type":"单选","difficulty":"3","content":"题干","answer":"A"}`,
		"difficulty float":  `{"type":"单选","difficulty":3.5,"content":"题干","answer":"A"}`,
	} {
		recorder := do(handler, http.MethodPost, questionsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 类型联动校验：单选/多选 options 非数组或长度 < 2、answer 不在 options 内、
// 多选 answer 非数组/含 options 之外的值、判断 answer 非正确/错误、填空 answer 空白 → 400。
func TestCreateQuestionTypeLinkedValidation(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"单选 options too few":   `{"type":"单选","difficulty":2,"content":"题干","options":["A"],"answer":"A"}`,
		"单选 options non-array": `{"type":"单选","difficulty":2,"content":"题干","options":"A","answer":"A"}`,
		"单选 answer outside":    `{"type":"单选","difficulty":2,"content":"题干","options":["A","B"],"answer":"C"}`,
		"单选 answer array":      `{"type":"单选","difficulty":2,"content":"题干","options":["A","B"],"answer":["A"]}`,
		"多选 options missing":   `{"type":"多选","difficulty":2,"content":"题干","answer":["A"]}`,
		"多选 answer string":     `{"type":"多选","difficulty":2,"content":"题干","options":["A","B"],"answer":"A"}`,
		"多选 answer outside":    `{"type":"多选","difficulty":2,"content":"题干","options":["A","B"],"answer":["A","X"]}`,
		"多选 answer empty":      `{"type":"多选","difficulty":2,"content":"题干","options":["A","B"],"answer":[]}`,
		"判断 answer wrong":      `{"type":"判断","difficulty":2,"content":"题干","answer":"对"}`,
		"判断 answer non-string": `{"type":"判断","difficulty":2,"content":"题干","answer":1}`,
		"填空 answer blank":      `{"type":"填空","difficulty":2,"content":"题干","answer":"   "}`,
	} {
		recorder := do(handler, http.MethodPost, questionsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 合法创建：返回 201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// 回显全部请求字段；判断/填空省略的 tags/options/explanation/metadata 回显缺省值。
func TestCreateQuestionSuccess(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPost, questionsPath, validQuestionBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	question := decodeQuestion(t, recorder)
	if !ulidPattern.MatchString(question.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", question.ID)
	}
	if question.Type != "单选" || question.Difficulty != 3 {
		t.Fatalf("type/difficulty = %q/%d, want 单选/3", question.Type, question.Difficulty)
	}
	if len(question.Tags) != 2 || question.Tags[0] != "客流" || question.Tags[1] != "基础" {
		t.Fatalf("tags = %v, want [客流 基础]", question.Tags)
	}
	if question.Content != "车站客流高峰时段为？" {
		t.Fatalf("content = %q", question.Content)
	}
	if len(question.Options) != 3 || question.Options[0] != "A" {
		t.Fatalf("options = %v, want [A B C]", question.Options)
	}
	if answer, ok := question.Answer.(string); !ok || answer != "A" {
		t.Fatalf("answer = %v, want A", question.Answer)
	}
	if question.Explanation != "解析" {
		t.Fatalf("explanation = %q, want 解析", question.Explanation)
	}
	if question.Metadata["source"] != "merit" {
		t.Fatalf("metadata = %v, want it to echo {source: merit}", question.Metadata)
	}
	if question.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", question.CreatedBy)
	}
	if question.CreatedAt == "" || question.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", question)
	}

	// 判断题省略可选字段：tags/options 缺省 []、explanation 缺省 ""、metadata 缺省 {}。
	judgment := createQuestion(t, handler, `{"type":"判断","difficulty":1,"content":"判断题干","answer":"正确"}`)
	if judgment.Tags == nil || len(judgment.Tags) != 0 {
		t.Fatalf("judgment tags = %v, want []", judgment.Tags)
	}
	if judgment.Options == nil || len(judgment.Options) != 0 {
		t.Fatalf("judgment options = %v, want []", judgment.Options)
	}
	if judgment.Explanation != "" {
		t.Fatalf("judgment explanation = %q, want empty", judgment.Explanation)
	}
	if len(judgment.Metadata) != 0 {
		t.Fatalf("judgment metadata = %v, want {}", judgment.Metadata)
	}
	if answer, ok := judgment.Answer.(string); !ok || answer != "正确" {
		t.Fatalf("judgment answer = %v, want 正确", judgment.Answer)
	}
}

// 客户端传入 id/created_at/updated_at 被忽略：响应中的 id 为服务端 ULID，时间非伪造值。
func TestCreateQuestionIgnoresClientIDAndTimestamps(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, questionsPath,
		`{"id":"FAKEID","type":"单选","difficulty":2,"content":"题干","options":["A","B"],"answer":"A","created_at":"2000-01-01T00:00:00Z","updated_at":"2000-01-02T00:00:00Z"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	question := decodeQuestion(t, recorder)
	if question.ID == "FAKEID" || !ulidPattern.MatchString(question.ID) {
		t.Fatalf("id = %q, want a server-generated ULID", question.ID)
	}
	if question.CreatedAt == "2000-01-01T00:00:00Z" || question.UpdatedAt == "2000-01-02T00:00:00Z" {
		t.Fatalf("client timestamps must be ignored, got %+v", question)
	}
}

// 畸形 JSON 请求体 → 400 {error}。
func TestCreateQuestionMalformedBody(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, questionsPath, `{"type": `)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /questions ──────────────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非 null。
func TestListQuestionsEmpty(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, questionsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeQuestionList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 筛选 type/difficulty 精确匹配、tags 逗号分隔且为 AND 语义、组合筛选生效，
// meta.total 为筛选后的总数（分页前），顺序为插入序。
func TestListQuestionsFiltering(t *testing.T) {
	handler := testMux(nil)
	createQuestion(t, handler, `{"type":"单选","difficulty":3,"tags":["客流","基础"],"content":"q1","options":["A","B"],"answer":"A"}`)
	createQuestion(t, handler, `{"type":"单选","difficulty":2,"tags":["客流"],"content":"q2","options":["A","B"],"answer":"B"}`)
	createQuestion(t, handler, `{"type":"多选","difficulty":3,"tags":["基础","安全"],"content":"q3","options":["A","B"],"answer":["A"]}`)
	createQuestion(t, handler, `{"type":"判断","difficulty":1,"tags":["安全"],"content":"q4","answer":"正确"}`)
	createQuestion(t, handler, `{"type":"填空","difficulty":4,"tags":["客流","基础","安全"],"content":"q5","answer":"站台"}`)

	cases := []struct {
		name     string
		query    string
		total    int
		contents []string
	}{
		{"by type", "?type=" + "单选", 2, []string{"q1", "q2"}},
		{"by difficulty", "?difficulty=3", 2, []string{"q1", "q3"}},
		{"by single tag", "?tags=" + "客流", 3, []string{"q1", "q2", "q5"}},
		{"by two tags AND", "?tags=" + "客流,基础", 2, []string{"q1", "q5"}},
		{"type+difficulty", "?type=" + "单选" + "&difficulty=2", 1, []string{"q2"}},
		{"tags+difficulty", "?tags=" + "安全" + "&difficulty=1", 1, []string{"q4"}},
		{"type+tags", "?type=" + "填空" + "&tags=" + "客流,基础", 1, []string{"q5"}},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodGet, questionsPath+testCase.query, "")
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200", testCase.name, recorder.Code)
		}
		list := decodeQuestionList(t, recorder)
		if list.Meta.Total != testCase.total {
			t.Fatalf("%s: total = %d, want %d", testCase.name, list.Meta.Total, testCase.total)
		}
		if len(list.Records) != len(testCase.contents) {
			t.Fatalf("%s: records = %d, want %d", testCase.name, len(list.Records), len(testCase.contents))
		}
		for i, content := range testCase.contents {
			if list.Records[i].Content != content {
				t.Fatalf("%s: records[%d].content = %q, want %q", testCase.name, i, list.Records[i].Content, content)
			}
		}
	}
}

// limit/offset 分页生效：limit 默认 50，offset 越界返回空 records 且 total 保持。
func TestListQuestionsPagination(t *testing.T) {
	handler := testMux(nil)
	contents := []string{"题目一", "题目二", "题目三", "题目四", "题目五"}
	for _, content := range contents {
		createQuestion(t, handler, `{"type":"单选","difficulty":2,"content":"`+content+`","options":["A","B"],"answer":"A"}`)
	}

	recorder := do(handler, http.MethodGet, questionsPath+"?limit=2&offset=0", "")
	list := decodeQuestionList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 5", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Content != "题目一" || list.Records[1].Content != "题目二" {
		t.Fatalf("first page contents = %q %q, want 题目一 题目二", list.Records[0].Content, list.Records[1].Content)
	}

	recorder = do(handler, http.MethodGet, questionsPath+"?limit=2&offset=4", "")
	list = decodeQuestionList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=4: records = %d, total = %d; want 1 / 5", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Content != "题目五" {
		t.Fatalf("last page content = %q, want 题目五", list.Records[0].Content)
	}

	recorder = do(handler, http.MethodGet, questionsPath+"?limit=2&offset=10", "")
	list = decodeQuestionList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d; want 0 / 5", len(list.Records), list.Meta.Total)
	}

	// limit 缺省 = 50：全部返回。
	recorder = do(handler, http.MethodGet, questionsPath, "")
	list = decodeQuestionList(t, recorder)
	if len(list.Records) != 5 {
		t.Fatalf("default limit: records = %d, want 5", len(list.Records))
	}
}

// 列表非法筛选参数（type 非四值、difficulty 越界/非整数、limit/offset 非法）→ 400 {error}。
func TestListQuestionsInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	for name, query := range map[string]string{
		"invalid type":       "?type=" + "简答",
		"difficulty zero":    "?difficulty=0",
		"difficulty six":     "?difficulty=6",
		"difficulty letters": "?difficulty=abc",
		"difficulty float":   "?difficulty=1.5",
		"invalid limit":      "?limit=abc",
		"negative limit":     "?limit=-1",
		"invalid offset":     "?offset=abc",
		"negative offset":    "?offset=-1",
	} {
		recorder := do(handler, http.MethodGet, questionsPath+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /questions/{id} ─────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetQuestion(t *testing.T) {
	handler := testMux(nil)
	created := createQuestion(t, handler, validQuestionBody)

	recorder := do(handler, http.MethodGet, questionsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	question := decodeQuestion(t, recorder)
	if question.ID != created.ID || question.Type != "单选" || question.Difficulty != 3 ||
		question.Content != "车站客流高峰时段为？" || question.CreatedBy != "u-admin" ||
		question.Metadata["source"] != "merit" {
		t.Fatalf("GET response %+v does not echo the created question", question)
	}

	recorder = do(handler, http.MethodGet, questionsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /questions/{id} ─────────────────────────────────────────────

// PUT 校验口径与 POST 一致：缺必填/非法枚举/类型联动 → 400；成功返回 200 与更新后
// 记录，created_at 保留原值、updated_at 刷新，随后 GET 反映更新；不存在的 id 返回 404。
func TestUpdateQuestion(t *testing.T) {
	handler := testMux(nil)
	created := createQuestion(t, handler, validQuestionBody)

	updatedBody := `{"type":"多选","difficulty":5,"tags":["安全"],"content":"更新后的题干","options":["A","B","C","D"],"answer":["B","D"],"explanation":"新解析","metadata":{"source":"merit","level":"2"},"created_by":"u-admin","created_at":"2000-01-01T00:00:00Z","updated_at":"2000-01-02T00:00:00Z"}`
	recorder := do(handler, http.MethodPut, questionsPath+"/"+created.ID, updatedBody)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeQuestion(t, recorder)
	if updated.ID != created.ID || updated.Type != "多选" || updated.Difficulty != 5 ||
		updated.Content != "更新后的题干" || updated.CreatedBy != "u-admin" ||
		updated.Metadata["level"] != "2" || len(updated.Tags) != 1 || updated.Tags[0] != "安全" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}
	if answer, ok := updated.Answer.([]any); !ok || len(answer) != 2 || answer[0] != "B" || answer[1] != "D" {
		t.Fatalf("PUT answer = %v, want [B D]", updated.Answer)
	}
	if updated.CreatedAt != created.CreatedAt {
		t.Fatalf("created_at = %q, want the original %q preserved", updated.CreatedAt, created.CreatedAt)
	}
	if updated.CreatedAt == "2000-01-01T00:00:00Z" || updated.UpdatedAt == "2000-01-02T00:00:00Z" {
		t.Fatalf("client timestamps must be ignored, got %+v", updated)
	}
	if updated.UpdatedAt < updated.CreatedAt {
		t.Fatalf("updated_at = %q must be refreshed to >= created_at %q", updated.UpdatedAt, updated.CreatedAt)
	}

	recorder = do(handler, http.MethodGet, questionsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeQuestion(t, recorder)
	if fetched.Type != "多选" || fetched.Difficulty != 5 || fetched.Content != "更新后的题干" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	for name, body := range map[string]string{
		"missing type":          `{"difficulty":2,"content":"题干","answer":"A"}`,
		"missing content":       `{"type":"单选","difficulty":2,"answer":"A"}`,
		"missing difficulty":    `{"type":"单选","content":"题干","answer":"A"}`,
		"missing answer":        `{"type":"单选","difficulty":2,"content":"题干","options":["A","B"]}`,
		"invalid type":          `{"type":"简答","difficulty":2,"content":"题干","answer":"A"}`,
		"invalid difficulty":    `{"type":"单选","difficulty":6,"content":"题干","answer":"A"}`,
		"answer outside":        `{"type":"单选","difficulty":2,"content":"题干","options":["A","B"],"answer":"C"}`,
		"judgment answer wrong": `{"type":"判断","difficulty":2,"content":"题干","answer":"对"}`,
	} {
		recorder := do(handler, http.MethodPut, questionsPath+"/"+created.ID, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	recorder = do(handler, http.MethodPut, questionsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", validQuestionBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /questions/{id} ──────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeleteQuestion(t *testing.T) {
	handler := testMux(nil)
	created := createQuestion(t, handler, validQuestionBody)

	recorder := do(handler, http.MethodDelete, questionsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, questionsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, questionsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── POST /questions/import ──────────────────────────────────────────

// 合法数组（多类型混合）→ 201 + {imported, records}，每条含服务端 ULID；
// 导入后列表与单条查询均可见全部条目（内存断言）。
func TestImportQuestionsSuccess(t *testing.T) {
	handler := testMux(nil)
	body := `[
		{"type":"单选","difficulty":3,"content":"导入单选","options":["A","B"],"answer":"A"},
		{"type":"多选","difficulty":2,"content":"导入多选","options":["A","B","C"],"answer":["A","C"]},
		{"type":"判断","difficulty":1,"content":"导入判断","answer":"错误"},
		{"type":"填空","difficulty":4,"content":"导入填空","answer":"车厢"}
	]`
	recorder := do(handler, http.MethodPost, questionsPath+"/import", body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("import status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	payload := decodeImport(t, recorder)
	if payload.Imported != 4 || len(payload.Records) != 4 {
		t.Fatalf("imported = %d, records = %d; want 4 / 4", payload.Imported, len(payload.Records))
	}
	for i, question := range payload.Records {
		if !ulidPattern.MatchString(question.ID) {
			t.Fatalf("records[%d].id = %q, want a 26-character Crockford Base32 ULID", i, question.ID)
		}
	}
	if payload.Records[0].Type != "单选" || payload.Records[0].Answer != "A" {
		t.Fatalf("records[0] = %+v, want the single-choice item", payload.Records[0])
	}
	if answer, ok := payload.Records[1].Answer.([]any); !ok || len(answer) != 2 {
		t.Fatalf("records[1].answer = %v, want [A C]", payload.Records[1].Answer)
	}
	if len(payload.Records[2].Options) != 0 || payload.Records[2].Answer != "错误" {
		t.Fatalf("records[2] = %+v, want default options [] and answer 错误", payload.Records[2])
	}

	// 落库可见：列表 total 为 4，逐条 GET 返回 200 且内容一致。
	list := decodeQuestionList(t, do(handler, http.MethodGet, questionsPath, ""))
	if list.Meta.Total != 4 {
		t.Fatalf("list after import: total = %d, want 4", list.Meta.Total)
	}
	got := do(handler, http.MethodGet, questionsPath+"/"+payload.Records[3].ID, "")
	if got.Code != http.StatusOK {
		t.Fatalf("GET imported id: status = %d, want 200", got.Code)
	}
	if decodeQuestion(t, got).Content != "导入填空" {
		t.Fatalf("GET imported question does not echo the imported content")
	}
}

// 空数组 → 400 {error: import failed, details: []}。
func TestImportQuestionsEmptyArray(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPost, questionsPath+"/import", `[]`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	payload := decodeImportError(t, recorder)
	if payload.Error != "import failed" {
		t.Fatalf("error = %q, want %q", payload.Error, "import failed")
	}
	if len(payload.Details) != 0 {
		t.Fatalf("details = %v, want empty", payload.Details)
	}
}

// 含 1 条非法 → 整体 400，错误体为 {error: "import failed", details: [{index, message}]}，
// 且整体不落库（内存断言 total 仍为 0）。
func TestImportQuestionsOneInvalidFailsAll(t *testing.T) {
	handler := testMux(nil)
	body := `[
		{"type":"单选","difficulty":3,"content":"合法一","options":["A","B"],"answer":"A"},
		{"type":"多选","difficulty":2,"content":"非法多选","options":["A","B"],"answer":["A","X"]},
		{"type":"判断","difficulty":1,"content":"合法判断","answer":"正确"}
	]`
	recorder := do(handler, http.MethodPost, questionsPath+"/import", body)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	payload := decodeImportError(t, recorder)
	if payload.Error != "import failed" {
		t.Fatalf("error = %q, want %q", payload.Error, "import failed")
	}
	if len(payload.Details) != 1 {
		t.Fatalf("details = %v, want exactly 1 detail", payload.Details)
	}
	if payload.Details[0].Index != 1 {
		t.Fatalf("details[0].index = %d, want 1", payload.Details[0].Index)
	}
	if payload.Details[0].Message == "" {
		t.Fatalf("details[0].message is empty")
	}

	// 整体不落库。
	list := decodeQuestionList(t, do(handler, http.MethodGet, questionsPath, ""))
	if list.Meta.Total != 0 {
		t.Fatalf("list after failed import: total = %d, want 0", list.Meta.Total)
	}
}

// 多条非法 → 每条一个明细，index 为数组下标且顺序保留；整体不落库。
func TestImportQuestionsMultipleInvalid(t *testing.T) {
	handler := testMux(nil)
	body := `[
		{"type":"简答","difficulty":1,"content":"非法类型"},
		{"type":"判断","difficulty":1,"content":"合法判断","answer":"正确"},
		{"type":"单选","difficulty":9,"content":"非法难度","options":["A","B"],"answer":"A"},
		{"type":"填空","difficulty":1,"content":"合法填空","answer":"站"}
	]`
	recorder := do(handler, http.MethodPost, questionsPath+"/import", body)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	payload := decodeImportError(t, recorder)
	if len(payload.Details) != 2 {
		t.Fatalf("details = %v, want exactly 2 details", payload.Details)
	}
	if payload.Details[0].Index != 0 || payload.Details[1].Index != 2 {
		t.Fatalf("details indexes = [%d %d], want [0 2]", payload.Details[0].Index, payload.Details[1].Index)
	}
	if payload.Details[0].Message == "" || payload.Details[1].Message == "" {
		t.Fatalf("details messages must not be empty: %v", payload.Details)
	}

	list := decodeQuestionList(t, do(handler, http.MethodGet, questionsPath, ""))
	if list.Meta.Total != 0 {
		t.Fatalf("list after failed import: total = %d, want 0", list.Meta.Total)
	}
}

// 畸形 JSON 请求体（非数组或残缺数组）→ 400 {error}。
func TestImportQuestionsMalformedBody(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"object not array": `{"type":"单选"}`,
		"truncated array":  `[{"type":`,
	} {
		recorder := do(handler, http.MethodPost, questionsPath+"/import", body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── 方法与路由语义 ──────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头。
func TestQuestionsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, questionsPath, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /questions: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /questions Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, questionsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /questions/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /questions/{id} Allow = %q, want it to contain PUT and DELETE", allow)
	}
}

// import 端点路由语义：仅 POST /questions/import 有效；
// GET/PUT/DELETE /questions/import 落入 {id} 模式按 id="import" 处理 → 404 {error}。
func TestQuestionsImportRouteSemantics(t *testing.T) {
	handler := testMux(nil)
	// GET/DELETE need no body; PUT carries a valid question body so the
	// request passes body decoding and reaches the item handler, which
	// answers 404 for id="import".
	for method, body := range map[string]string{
		http.MethodGet:    "",
		http.MethodPut:    `{"type":"单选","difficulty":2,"content":"题干","options":["A","B"],"answer":"A"}`,
		http.MethodDelete: "",
	} {
		recorder := do(handler, method, questionsPath+"/import", body)
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("%s /questions/import: status = %d, want 404", method, recorder.Code)
		}
		decodeError(t, recorder)
	}
	recorder := do(handler, http.MethodPost, questionsPath+"/import",
		`[{"type":"判断","difficulty":1,"content":"导入判断","answer":"正确"}]`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST /questions/import: status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
}

// 允许 Origin 的 OPTIONS /questions 与 /questions/{id} 预检返回 204，
// Allow-Methods 含 GET/POST/PUT/DELETE/OPTIONS。
func TestQuestionsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{questionsPath, questionsPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV"} {
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

// 字面路径 /questions 优先于 {resource} 通配：GET /questions 返回 200 而非 404。
func TestQuestionsLiteralPathTakesPrecedence(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, questionsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET /questions: status = %d, want 200", recorder.Code)
	}
}
