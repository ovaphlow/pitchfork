package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

const papersPath = "/crate-api/prototype/v1/papers"

// paperJSON mirrors the paper response for assertions. questionJSON
// (from questions_test.go) mirrors the question snapshots.
type paperJSON struct {
	ID                 string         `json:"id"`
	Title              string         `json:"title"`
	DurationMinutes    int            `json:"duration_minutes"`
	PassScore          int            `json:"pass_score"`
	GenerationStrategy map[string]int `json:"generation_strategy"`
	Questions          []questionJSON `json:"questions"`
	CreatedBy          string         `json:"created_by"`
	CreatedAt          string         `json:"created_at"`
	UpdatedAt          string         `json:"updated_at"`
}

type paperListJSON struct {
	Records []paperJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodePaper(t *testing.T, recorder *httptest.ResponseRecorder) paperJSON {
	t.Helper()
	var paper paperJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &paper); err != nil {
		t.Fatalf("body %q is not a paper JSON: %v", recorder.Body.String(), err)
	}
	return paper
}

func decodePaperList(t *testing.T, recorder *httptest.ResponseRecorder) paperListJSON {
	t.Helper()
	var list paperListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a paper list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createPaper posts a valid paper body and asserts 201; returns the
// created paper.
func createPaper(t *testing.T, handler http.Handler, body string) paperJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, papersPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodePaper(t, recorder)
}

const validPaperBody = `{"title":"月度理论考核","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":10,"多选":5,"判断":5,"填空":0},"created_by":"u-admin"}`

// singleChoiceBody / judgmentBody / multipleChoiceBody build question
// bank bodies for seeding generate tests through the questions API.
func singleChoiceBody(n int) string {
	return fmt.Sprintf(`{"type":"单选","difficulty":%d,"content":"单选题目 %d","options":["A","B"],"answer":"A"}`, 1+n%5, n)
}

func judgmentBody(n int) string {
	return fmt.Sprintf(`{"type":"判断","difficulty":1,"content":"判断题目 %d","answer":"正确"}`, n)
}

func multipleChoiceBody(n int) string {
	return fmt.Sprintf(`{"type":"多选","difficulty":1,"content":"多选题目 %d","options":["A","B","C"],"answer":["A","B"]}`, n)
}

// seedQuestions creates the given question bodies through the questions
// API and returns the created questions.
func seedQuestions(t *testing.T, handler http.Handler, bodies ...string) []questionJSON {
	t.Helper()
	created := make([]questionJSON, 0, len(bodies))
	for _, body := range bodies {
		created = append(created, createQuestion(t, handler, body))
	}
	return created
}

// ─── POST /papers ────────────────────────────────────────────────────

// 缺 title/duration_minutes/pass_score、duration_minutes≤0、
// pass_score 越界、strategy 各类非法、非法/空 JSON body → 400，
// 错误响应体为 {error}。
func TestCreatePaperInvalidBodies(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"missing title":               `{"duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":1}}`,
		"blank title":                 `{"title":"  ","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":1}}`,
		"missing duration_minutes":    `{"title":"试卷","pass_score":60,"generation_strategy":{"单选":1}}`,
		"zero duration_minutes":       `{"title":"试卷","duration_minutes":0,"pass_score":60,"generation_strategy":{"单选":1}}`,
		"negative duration_minutes":   `{"title":"试卷","duration_minutes":-10,"pass_score":60,"generation_strategy":{"单选":1}}`,
		"missing pass_score":          `{"title":"试卷","duration_minutes":60,"generation_strategy":{"单选":1}}`,
		"negative pass_score":         `{"title":"试卷","duration_minutes":60,"pass_score":-1,"generation_strategy":{"单选":1}}`,
		"pass_score above 100":        `{"title":"试卷","duration_minutes":60,"pass_score":101,"generation_strategy":{"单选":1}}`,
		"missing generation_strategy": `{"title":"试卷","duration_minutes":60,"pass_score":60}`,
		"unknown strategy key":        `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"选择题":1}}`,
		"negative strategy value":     `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":-1}}`,
		"non-integer strategy value":  `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":1.5}}`,
		"non-number strategy value":   `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":"2"}}`,
		"all-zero strategy":           `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":0,"多选":0}}`,
		"empty strategy object":       `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{}}`,
		"null strategy":               `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":null}`,
		"array strategy":              `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":[1,2]}`,
		"string strategy":             `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":"单选"}`,
		"malformed body":              `{"title": `,
		"empty body":                  ``,
		"array body":                  `[]`,
	} {
		recorder := do(handler, http.MethodPost, papersPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		if message := decodeError(t, recorder); message == "" {
			t.Fatalf("%s: empty error message", name)
		}
	}
}

// 合法创建：返回 201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// questions 默认为空数组，strategy/created_by 回显，时间戳存在。
func TestCreatePaperSuccess(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, papersPath, validPaperBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	paper := decodePaper(t, recorder)
	if !ulidPattern.MatchString(paper.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", paper.ID)
	}
	if paper.Title != "月度理论考核" || paper.DurationMinutes != 60 || paper.PassScore != 60 {
		t.Fatalf("paper %+v does not echo title/duration/pass_score", paper)
	}
	if paper.GenerationStrategy["单选"] != 10 || paper.GenerationStrategy["多选"] != 5 ||
		paper.GenerationStrategy["判断"] != 5 || paper.GenerationStrategy["填空"] != 0 {
		t.Fatalf("generation_strategy = %v, want {单选:10 多选:5 判断:5 填空:0}", paper.GenerationStrategy)
	}
	if len(paper.Questions) != 0 {
		t.Fatalf("questions = %v, want an empty list on create", paper.Questions)
	}
	if paper.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", paper.CreatedBy)
	}
	if paper.CreatedAt == "" || paper.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", paper)
	}
}

// 客户端传入的 questions/id/时间戳被忽略：questions 保持为空数组，
// id 仍为服务端生成的 ULID。
func TestCreatePaperIgnoresClientQuestionsAndID(t *testing.T) {
	body := `{"id":"client-id","title":"试卷","duration_minutes":30,"pass_score":0,"generation_strategy":{"单选":2},"questions":[{"id":"q-1","type":"单选"}],"created_at":"2000-01-01T00:00:00Z"}`
	recorder := do(testMux(nil), http.MethodPost, papersPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	paper := decodePaper(t, recorder)
	if !ulidPattern.MatchString(paper.ID) {
		t.Fatalf("id = %q, want a server-generated ULID", paper.ID)
	}
	if len(paper.Questions) != 0 {
		t.Fatalf("questions = %v, want empty (client questions ignored)", paper.Questions)
	}
	if paper.PassScore != 0 {
		t.Fatalf("pass_score = %d, want 0 (legal)", paper.PassScore)
	}
	if strings.HasPrefix(paper.CreatedAt, "2000-") {
		t.Fatalf("created_at = %q, want the server timestamp", paper.CreatedAt)
	}
}

// ─── GET /papers ─────────────────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非 null。
func TestListPapersEmpty(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, papersPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodePaperList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// limit/offset 分页生效，meta.total 保持总数，排序按 created_at DESC
// （最新创建的在前）。
func TestListPapersPaginationAndOrder(t *testing.T) {
	handler := testMux(nil)
	created := make([]paperJSON, 0, 5)
	for i := 0; i < 5; i++ {
		created = append(created, createPaper(t, handler, `{"title":"试卷`+fmt.Sprint(i)+`","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":1}}`))
	}

	recorder := do(handler, http.MethodGet, papersPath+"?limit=2&offset=0", "")
	list := decodePaperList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 5", len(list.Records), list.Meta.Total)
	}
	// 最新创建的两份试卷在前（created_at DESC）。
	if list.Records[0].ID != created[4].ID || list.Records[1].ID != created[3].ID {
		t.Fatalf("first page ids = %q %q, want %q %q (newest first)",
			list.Records[0].ID, list.Records[1].ID, created[4].ID, created[3].ID)
	}

	recorder = do(handler, http.MethodGet, papersPath+"?limit=2&offset=3", "")
	list = decodePaperList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=3: records = %d, total = %d; want 2 / 5", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].ID != created[1].ID || list.Records[1].ID != created[0].ID {
		t.Fatalf("second page ids = %q %q, want %q %q", list.Records[0].ID, list.Records[1].ID, created[1].ID, created[0].ID)
	}

	recorder = do(handler, http.MethodGet, papersPath+"?limit=2&offset=10", "")
	list = decodePaperList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d; want 0 / 5", len(list.Records), list.Meta.Total)
	}
}

// 非法 limit/offset → 400 {error}。
func TestListPapersInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	for name, query := range map[string]string{
		"invalid limit":   "?limit=abc",
		"negative limit":  "?limit=-1",
		"invalid offset":  "?offset=abc",
		"negative offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, papersPath+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /papers/{id} ────────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetPaper(t *testing.T) {
	handler := testMux(nil)
	created := createPaper(t, handler, validPaperBody)

	recorder := do(handler, http.MethodGet, papersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	paper := decodePaper(t, recorder)
	if paper.ID != created.ID || paper.Title != "月度理论考核" ||
		paper.DurationMinutes != 60 || paper.PassScore != 60 ||
		paper.CreatedBy != "u-admin" || paper.GenerationStrategy["单选"] != 10 {
		t.Fatalf("GET response %+v does not echo the created paper", paper)
	}

	recorder = do(handler, http.MethodGet, papersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /papers/{id} ────────────────────────────────────────────────

// PUT 校验口径与 POST 一致：缺必填或非法字段 → 400；成功返回 200 与更新后
// 的记录，随后 GET 反映更新；不存在的 id 返回 404。
func TestUpdatePaper(t *testing.T) {
	handler := testMux(nil)
	created := createPaper(t, handler, validPaperBody)

	updatedBody := `{"title":"月度理论考核（进阶）","duration_minutes":90,"pass_score":80,"generation_strategy":{"判断":8},"created_by":"u-admin"}`
	recorder := do(handler, http.MethodPut, papersPath+"/"+created.ID, updatedBody)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodePaper(t, recorder)
	if updated.ID != created.ID || updated.Title != "月度理论考核（进阶）" ||
		updated.DurationMinutes != 90 || updated.PassScore != 80 ||
		updated.GenerationStrategy["判断"] != 8 || updated.CreatedBy != "u-admin" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}

	recorder = do(handler, http.MethodGet, papersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodePaper(t, recorder)
	if fetched.Title != "月度理论考核（进阶）" || fetched.DurationMinutes != 90 ||
		fetched.PassScore != 80 || fetched.GenerationStrategy["判断"] != 8 {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	for name, body := range map[string]string{
		"missing title":           `{"duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":1}}`,
		"zero duration_minutes":   `{"title":"试卷","duration_minutes":0,"pass_score":60,"generation_strategy":{"单选":1}}`,
		"missing pass_score":      `{"title":"试卷","duration_minutes":60,"generation_strategy":{"单选":1}}`,
		"pass_score above 100":    `{"title":"试卷","duration_minutes":60,"pass_score":101,"generation_strategy":{"单选":1}}`,
		"unknown strategy key":    `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"选择题":1}}`,
		"negative strategy value": `{"title":"试卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":-1}}`,
	} {
		recorder := do(handler, http.MethodPut, papersPath+"/"+created.ID, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	recorder = do(handler, http.MethodPut, papersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", updatedBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /papers/{id} ─────────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeletePaper(t *testing.T) {
	handler := testMux(nil)
	created := createPaper(t, handler, validPaperBody)

	recorder := do(handler, http.MethodDelete, papersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, papersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, papersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── POST /papers/{id}/generate ──────────────────────────────────────

// 组卷成功：返回 200 与试卷对象，questions 按 strategy 的数量与题型
// 匹配题库中随机选出的题目；随后 GET 反映新的 questions。
func TestGeneratePaperSuccess(t *testing.T) {
	handler := testMux(nil)
	seedQuestions(t, handler,
		singleChoiceBody(1), singleChoiceBody(2), singleChoiceBody(3),
		judgmentBody(1), judgmentBody(2),
	)
	paper := createPaper(t, handler, `{"title":"自动组卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":2,"判断":1}}`)

	recorder := do(handler, http.MethodPost, papersPath+"/"+paper.ID+"/generate", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("generate status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	generated := decodePaper(t, recorder)
	if generated.ID != paper.ID {
		t.Fatalf("generate response id = %q, want %q", generated.ID, paper.ID)
	}
	single, judgment := 0, 0
	for _, question := range generated.Questions {
		if question.ID == "" || question.Content == "" {
			t.Fatalf("snapshot %+v has empty id/content", question)
		}
		switch question.Type {
		case "单选":
			single++
		case "判断":
			judgment++
		default:
			t.Fatalf("snapshot type = %q, want 单选 or 判断", question.Type)
		}
	}
	if single != 2 || judgment != 1 {
		t.Fatalf("questions = %d (单选 %d, 判断 %d), want 2 单选 + 1 判断", len(generated.Questions), single, judgment)
	}

	recorder = do(handler, http.MethodGet, papersPath+"/"+paper.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after generate: status = %d, want 200", recorder.Code)
	}
	fetched := decodePaper(t, recorder)
	if len(fetched.Questions) != 3 {
		t.Fatalf("GET after generate: questions = %d, want 3", len(fetched.Questions))
	}
}

// 重复组卷覆盖上次结果：更新 strategy 后再次 generate，GET 断言旧题目
// 被替换。
func TestGeneratePaperOverwritesPreviousResult(t *testing.T) {
	handler := testMux(nil)
	seedQuestions(t, handler,
		singleChoiceBody(1), singleChoiceBody(2), singleChoiceBody(3),
		multipleChoiceBody(1), multipleChoiceBody(2),
	)
	paper := createPaper(t, handler, `{"title":"自动组卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":2}}`)

	recorder := do(handler, http.MethodPost, papersPath+"/"+paper.ID+"/generate", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("first generate status = %d, want 200", recorder.Code)
	}
	first := decodePaper(t, recorder)
	if len(first.Questions) != 2 {
		t.Fatalf("first generate questions = %d, want 2", len(first.Questions))
	}

	// 更新 strategy 后再次组卷，结果覆盖第一次的 2 道单选。
	recorder = do(handler, http.MethodPut, papersPath+"/"+paper.ID,
		`{"title":"自动组卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"多选":1}}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPost, papersPath+"/"+paper.ID+"/generate", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("second generate status = %d, want 200", recorder.Code)
	}
	second := decodePaper(t, recorder)
	if len(second.Questions) != 1 || second.Questions[0].Type != "多选" {
		t.Fatalf("second generate questions = %v, want exactly 1 多选", second.Questions)
	}

	recorder = do(handler, http.MethodGet, papersPath+"/"+paper.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after second generate: status = %d, want 200", recorder.Code)
	}
	fetched := decodePaper(t, recorder)
	if len(fetched.Questions) != 1 || fetched.Questions[0].Type != "多选" {
		t.Fatalf("GET after second generate: questions = %v, want the replaced 多选 question", fetched.Questions)
	}
}

// 题库不足：返回 400，error 含按题型缺口说明；组卷失败不影响试卷
// （questions 保持原样）。不存在的 id 返回 404。
func TestGeneratePaperInsufficientBank(t *testing.T) {
	handler := testMux(nil)
	seedQuestions(t, handler, singleChoiceBody(1)) // 只有 1 道单选
	paper := createPaper(t, handler, `{"title":"自动组卷","duration_minutes":60,"pass_score":60,"generation_strategy":{"单选":3,"多选":2}}`)

	recorder := do(handler, http.MethodPost, papersPath+"/"+paper.ID+"/generate", "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("generate status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	message := decodeError(t, recorder)
	for _, gap := range []string{"题库不足", "单选缺 2 题", "多选缺 2 题"} {
		if !strings.Contains(message, gap) {
			t.Fatalf("error %q does not contain %q", message, gap)
		}
	}

	recorder = do(handler, http.MethodGet, papersPath+"/"+paper.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after failed generate: status = %d, want 200", recorder.Code)
	}
	fetched := decodePaper(t, recorder)
	if len(fetched.Questions) != 0 {
		t.Fatalf("failed generate must not touch questions, got %d", len(fetched.Questions))
	}

	recorder = do(handler, http.MethodPost, papersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV/generate", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("generate unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ─────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头；POST 不适用于 {id} 项路由
// （组卷是独立的 /generate 路径）。
func TestPapersMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, papersPath, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /papers: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH /papers Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, papersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /papers/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH /papers/{id} Allow = %q, want it to contain PUT and DELETE", allow)
	}

	recorder = do(handler, http.MethodPost, papersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /papers/{id}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("POST /papers/{id} Allow = %q, want GET, PUT, DELETE", allow)
	}
}

// 允许 Origin 的 OPTIONS 预检返回 204，Allow-Methods 含全部写方法
// （含 generate 路径）。
func TestPapersCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		papersPath,
		papersPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV",
		papersPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV/generate",
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
