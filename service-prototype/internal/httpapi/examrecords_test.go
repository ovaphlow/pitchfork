package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/examrecords"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/progress"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

const examRecordsPath = "/crate-api/prototype/v1/exam-records"

const (
	paperID     = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
	paperID2    = "AAAAAAAAAAAAAAAAAAAAAAAAAA"
	employeeID  = "BBBBBBBBBBBBBBBBBBBBBBBBBB"
	employeeID2 = "CCCCCCCCCCCCCCCCCCCCCCCCCC"
)

// seedExamPaper builds a paper with one question of every type
// (单选 B / 多选 [A,C] / 判断 正确 / 填空 Java) and pass_score 2.
func seedExamPaper(store *papers.InMemoryStore, id string) papers.Paper {
	paper := papers.Paper{
		ID:        id,
		Title:     "月度理论考核",
		PassScore: 2,
		Questions: []papers.QuestionSnapshot{
			{ID: "q-single", Type: questions.QuestionTypeSingle, Difficulty: 3, Content: "单选题目", Options: []string{"A", "B", "C"}, Answer: "B"},
			{ID: "q-multi", Type: questions.QuestionTypeMultiple, Difficulty: 3, Content: "多选题目", Options: []string{"A", "B", "C", "D"}, Answer: []any{"A", "C"}},
			{ID: "q-judge", Type: questions.QuestionTypeJudgment, Difficulty: 2, Content: "判断题目", Options: []string{}, Answer: "正确"},
			{ID: "q-fill", Type: questions.QuestionTypeFill, Difficulty: 2, Content: "填空题目", Options: []string{}, Answer: "Java"},
		},
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
	_ = store.Create(context.Background(), paper)
	return paper
}

// examMux builds a mux with fresh in-memory stores plus a seeded paper
// store holding the two test papers.
func examMux(allowedOrigins []string) http.Handler {
	paperStore := papers.NewInMemoryStore()
	seedExamPaper(paperStore, paperID)
	seedExamPaper(paperStore, paperID2)
	return NewMux(allowedOrigins, courses.NewInMemoryStore(), chapters.NewInMemoryStore(), questions.NewInMemoryStore(), assignments.NewInMemoryStore(), progress.NewInMemoryStore(), paperStore, examrecords.NewInMemoryStore(), drills.NewInMemoryStore(), dispatch.NewInMemoryStore(), opinion.NewInMemoryStore(), evaluation.NewInMemoryStore())
}

// examRecordJSON mirrors the exam-record response for assertions.
type examRecordJSON struct {
	ID              string         `json:"id"`
	EmployeeID      string         `json:"employee_id"`
	PaperID         string         `json:"paper_id"`
	StartTime       string         `json:"start_time"`
	EndTime         *string        `json:"end_time"`
	Score           *int           `json:"score"`
	Passed          *bool          `json:"passed"`
	AnswersSnapshot map[string]any `json:"answers_snapshot"`
	Metadata        map[string]any `json:"metadata"`
	CreatedBy       string         `json:"created_by"`
	CreatedAt       string         `json:"created_at"`
	UpdatedAt       string         `json:"updated_at"`
}

type examRecordListJSON struct {
	Records []examRecordJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeExamRecord(t *testing.T, recorder *httptest.ResponseRecorder) examRecordJSON {
	t.Helper()
	var record examRecordJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &record); err != nil {
		t.Fatalf("body %q is not an exam-record JSON: %v", recorder.Body.String(), err)
	}
	return record
}

func decodeExamRecordList(t *testing.T, recorder *httptest.ResponseRecorder) examRecordListJSON {
	t.Helper()
	var list examRecordListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not an exam-record list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createExamRecord opens an exam and asserts 201; returns the record.
func createExamRecord(t *testing.T, handler http.Handler, body string) examRecordJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, examRecordsPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeExamRecord(t, recorder)
}

// validOpenBody opens the seeded paper for the default employee.
const validOpenBody = `{"employee_id":"BBBBBBBBBBBBBBBBBBBBBBBBBB","paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV"}`

// submitAnswers submits the given answers JSON and returns the recorder.
func submitAnswers(handler http.Handler, id, answers string) *httptest.ResponseRecorder {
	return do(handler, http.MethodPost, examRecordsPath+"/"+id+"/submit", `{"answers":`+answers+`}`)
}

// assertSnapshot asserts the answers_snapshot contract: paper_id,
// pass_score and every question with id/type/difficulty/content/options/
// answer.
func assertSnapshot(t *testing.T, snapshot map[string]any, wantPaperID string) {
	t.Helper()
	if snapshot["paper_id"] != wantPaperID {
		t.Fatalf("snapshot paper_id = %v, want %s", snapshot["paper_id"], wantPaperID)
	}
	if passScore, ok := snapshot["pass_score"].(float64); !ok || passScore != 2 {
		t.Fatalf("snapshot pass_score = %v, want 2", snapshot["pass_score"])
	}
	questions, ok := snapshot["questions"].([]any)
	if !ok || len(questions) != 4 {
		t.Fatalf("snapshot questions = %v, want 4 items", snapshot["questions"])
	}
	first, ok := questions[0].(map[string]any)
	if !ok {
		t.Fatalf("snapshot question is not an object: %v", questions[0])
	}
	for _, field := range []string{"id", "type", "difficulty", "content", "options", "answer"} {
		if _, ok := first[field]; !ok {
			t.Fatalf("snapshot question misses field %q: %v", field, first)
		}
	}
	if first["id"] != "q-single" || first["type"] != "单选" || first["answer"] != "B" {
		t.Fatalf("snapshot first question = %v, want the seeded 单选 question", first)
	}
}

// ─── POST /exam-records 开考 ────────────────────────────────────────

// 开考返回 201 与完整记录：26 位 ULID id、回显 employee_id/paper_id、
// start_time 非空、end_time/score/passed 为 null、快照契约完整、
// metadata/created_by 回显、created_at/updated_at 非空。
func TestCreateExamRecord(t *testing.T) {
	handler := examMux(nil)
	body := `{"employee_id":"BBBBBBBBBBBBBBBBBBBBBBBBBB","paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","metadata":{"source":"web","attempt":2},"created_by":"u-admin"}`
	record := createExamRecord(t, handler, body)
	if !examrecords.ValidULID(record.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", record.ID)
	}
	if record.EmployeeID != "BBBBBBBBBBBBBBBBBBBBBBBBBB" || record.PaperID != paperID {
		t.Fatalf("employee_id/paper_id = %q/%q, want echoed", record.EmployeeID, record.PaperID)
	}
	if _, err := time.Parse(time.RFC3339Nano, record.StartTime); err != nil {
		t.Fatalf("start_time %q is not a timestamp: %v", record.StartTime, err)
	}
	if record.EndTime != nil || record.Score != nil || record.Passed != nil {
		t.Fatalf("end_time/score/passed must be null before submission, got %+v", record)
	}
	assertSnapshot(t, record.AnswersSnapshot, paperID)
	if record.Metadata["source"] != "web" || record.Metadata["attempt"] != float64(2) {
		t.Fatalf("metadata = %v, want echoed", record.Metadata)
	}
	if record.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want echoed", record.CreatedBy)
	}
	if record.CreatedAt == "" || record.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be set, got %q/%q", record.CreatedAt, record.UpdatedAt)
	}
}

// 缺省 metadata/created_by：metadata 为 {}、created_by 为 ""。
func TestCreateExamRecordDefaults(t *testing.T) {
	handler := examMux(nil)
	record := createExamRecord(t, handler, validOpenBody)
	if len(record.Metadata) != 0 {
		t.Fatalf("metadata = %v, want {}", record.Metadata)
	}
	if record.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty", record.CreatedBy)
	}
}

// 失败路径：缺 employee_id/paper_id、employee_id 非 ULID、非法 JSON
// body、metadata 非对象 → 400；paper 不存在 → 404；均为 {error} 单字段。
func TestCreateExamRecordErrors(t *testing.T) {
	handler := examMux(nil)
	cases := []struct {
		name   string
		body   string
		status int
	}{
		{"missing employee_id", `{"paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV"}`, http.StatusBadRequest},
		{"blank employee_id", `{"employee_id":"  ","paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV"}`, http.StatusBadRequest},
		{"non-ULID employee_id", `{"employee_id":"not-a-ulid","paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV"}`, http.StatusBadRequest},
		{"missing paper_id", `{"employee_id":"BBBBBBBBBBBBBBBBBBBBBBBBBB"}`, http.StatusBadRequest},
		{"unknown paper", `{"employee_id":"BBBBBBBBBBBBBBBBBBBBBBBBBB","paper_id":"DDDDDDDDDDDDDDDDDDDDDDDDDD"}`, http.StatusNotFound},
		{"invalid JSON body", `{not json`, http.StatusBadRequest},
		{"metadata as array", `{"employee_id":"BBBBBBBBBBBBBBBBBBBBBBBBBB","paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","metadata":[1]}`, http.StatusBadRequest},
		{"metadata as null", `{"employee_id":"BBBBBBBBBBBBBBBBBBBBBBBBBB","paper_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","metadata":null}`, http.StatusBadRequest},
	}
	for _, tc := range cases {
		recorder := do(handler, http.MethodPost, examRecordsPath, tc.body)
		if recorder.Code != tc.status {
			t.Fatalf("%s: status = %d, want %d; body = %s", tc.name, recorder.Code, tc.status, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── POST /exam-records/{id}/submit 交卷 ────────────────────────────

// 全对交卷：200，score = 4、passed = true、end_time 非空且 >=
// start_time、快照不变。
func TestSubmitExamRecordAllCorrect(t *testing.T) {
	handler := examMux(nil)
	record := createExamRecord(t, handler, validOpenBody)
	recorder := submitAnswers(handler, record.ID, `{"q-single":"B","q-multi":["A","C"],"q-judge":"正确","q-fill":"Java"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("submit status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	finished := decodeExamRecord(t, recorder)
	if finished.Score == nil || *finished.Score != 4 {
		t.Fatalf("score = %v, want 4", finished.Score)
	}
	if finished.Passed == nil || !*finished.Passed {
		t.Fatalf("passed = %v, want true", finished.Passed)
	}
	if finished.EndTime == nil {
		t.Fatal("end_time must be set after submission")
	}
	start, _ := time.Parse(time.RFC3339Nano, record.StartTime)
	end, err := time.Parse(time.RFC3339Nano, *finished.EndTime)
	if err != nil {
		t.Fatalf("end_time %q is not a timestamp: %v", *finished.EndTime, err)
	}
	if end.Before(start) {
		t.Fatalf("end_time %v < start_time %v", end, start)
	}
	// answers_snapshot 不变：与开考响应的快照逐字段一致。
	before := record.AnswersSnapshot
	after := finished.AnswersSnapshot
	if before["paper_id"] != after["paper_id"] || before["pass_score"] != after["pass_score"] {
		t.Fatal("answers_snapshot must stay unchanged by submission")
	}
	beforeJSON, _ := json.Marshal(before["questions"])
	afterJSON, _ := json.Marshal(after["questions"])
	if string(beforeJSON) != string(afterJSON) {
		t.Fatal("answers_snapshot questions must stay unchanged by submission")
	}
}

// 交卷后 GET /exam-records/{id} 反映 score/passed/end_time（交卷生
// 效）；交卷前 GET 三字段为 null。
func TestSubmitPersistsAndGetReflects(t *testing.T) {
	handler := examMux(nil)
	record := createExamRecord(t, handler, validOpenBody)

	before := get(handler, examRecordsPath+"/"+record.ID, nil)
	if before.Code != http.StatusOK {
		t.Fatalf("GET before submit status = %d, want 200", before.Code)
	}
	unsubmitted := decodeExamRecord(t, before)
	if unsubmitted.EndTime != nil || unsubmitted.Score != nil || unsubmitted.Passed != nil {
		t.Fatalf("before submission end_time/score/passed must be null, got %+v", unsubmitted)
	}

	if recorder := submitAnswers(handler, record.ID, `{"q-single":"B"}`); recorder.Code != http.StatusOK {
		t.Fatalf("submit status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	after := get(handler, examRecordsPath+"/"+record.ID, nil)
	if after.Code != http.StatusOK {
		t.Fatalf("GET after submit status = %d, want 200", after.Code)
	}
	submitted := decodeExamRecord(t, after)
	if submitted.Score == nil || *submitted.Score != 1 {
		t.Fatalf("score = %v, want 1 (only 单选 correct)", submitted.Score)
	}
	if submitted.Passed == nil || *submitted.Passed {
		t.Fatalf("passed = %v, want false (1 < pass_score 2)", submitted.Passed)
	}
	if submitted.EndTime == nil {
		t.Fatal("end_time must be set after submission")
	}
}

// 交卷失败路径：记录不存在 404、重复交卷 400、缺 answers 400、answers
// 非对象 400、未知题目 id 400、答案形状不符 400；重复交卷 400 后再次
// GET 内容不变。全部为 {error} 单字段。
func TestSubmitExamRecordErrors(t *testing.T) {
	handler := examMux(nil)
	record := createExamRecord(t, handler, validOpenBody)
	cases := []struct {
		name   string
		id     string
		body   string
		status int
	}{
		{"record not found", "DDDDDDDDDDDDDDDDDDDDDDDDDD", `{"answers":{}}`, http.StatusNotFound},
		{"missing answers", record.ID, `{}`, http.StatusBadRequest},
		{"answers as array", record.ID, `{"answers":[1]}`, http.StatusBadRequest},
		{"answers as string", record.ID, `{"answers":"B"}`, http.StatusBadRequest},
		{"answers as null", record.ID, `{"answers":null}`, http.StatusBadRequest},
		{"unknown question id", record.ID, `{"answers":{"q-unknown":"B"}}`, http.StatusBadRequest},
		{"multiple as string", record.ID, `{"answers":{"q-multi":"A"}}`, http.StatusBadRequest},
		{"single as array", record.ID, `{"answers":{"q-single":["B"]}}`, http.StatusBadRequest},
		{"single empty string", record.ID, `{"answers":{"q-single":""}}`, http.StatusBadRequest},
		{"judgment empty string", record.ID, `{"answers":{"q-judge":""}}`, http.StatusBadRequest},
		{"fill empty string", record.ID, `{"answers":{"q-fill":""}}`, http.StatusBadRequest},
		{"multiple empty array", record.ID, `{"answers":{"q-multi":[]}}`, http.StatusBadRequest},
		{"multiple non-string element", record.ID, `{"answers":{"q-multi":["A",1]}}`, http.StatusBadRequest},
		{"invalid JSON body", record.ID, `{not json`, http.StatusBadRequest},
	}
	for _, tc := range cases {
		recorder := do(handler, http.MethodPost, examRecordsPath+"/"+tc.id+"/submit", tc.body)
		if recorder.Code != tc.status {
			t.Fatalf("%s: status = %d, want %d; body = %s", tc.name, recorder.Code, tc.status, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
	// 上述失败交卷后记录仍未交卷。
	recorder := get(handler, examRecordsPath+"/"+record.ID, nil)
	unchanged := decodeExamRecord(t, recorder)
	if unchanged.EndTime != nil || unchanged.Score != nil || unchanged.Passed != nil {
		t.Fatalf("failed submissions must not write end_time/score/passed, got %+v", unchanged)
	}

	// 正常交卷后重复交卷 → 400，再次 GET 内容不变。
	if recorder := submitAnswers(handler, record.ID, `{"q-single":"B"}`); recorder.Code != http.StatusOK {
		t.Fatalf("first submit status = %d, want 200", recorder.Code)
	}
	recorder = submitAnswers(handler, record.ID, `{"q-single":"A"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("duplicate submit status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = get(handler, examRecordsPath+"/"+record.ID, nil)
	after := decodeExamRecord(t, recorder)
	if after.Score == nil || *after.Score != 1 || after.EndTime == nil {
		t.Fatalf("duplicate submit must leave the record unchanged, got %+v", after)
	}
}

// ─── GET /exam-records/{id} ─────────────────────────────────────────

// 记录不存在 → 404 {error}。
func TestGetExamRecordNotFound(t *testing.T) {
	handler := examMux(nil)
	recorder := get(handler, examRecordsPath+"/DDDDDDDDDDDDDDDDDDDDDDDDDD", nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── GET /exam-records 列表 ─────────────────────────────────────────

// 空列表 → {records:[], meta:{total:0}}。
func TestListExamRecordsEmpty(t *testing.T) {
	handler := examMux(nil)
	recorder := get(handler, examRecordsPath, nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeExamRecordList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("records = %v, total = %d, want empty and 0", list.Records, list.Meta.Total)
	}
}

// 筛选（employee_id/paper_id 精确、可联合）、limit/offset 分页（total
// 为全量）、created_at DESC 排序、同一员工同一试卷可多次开考。
func TestListExamRecordsFilterPagination(t *testing.T) {
	handler := examMux(nil)
	open := func(employee, paper string) examRecordJSON {
		body := `{"employee_id":"` + employee + `","paper_id":"` + paper + `"}`
		return createExamRecord(t, handler, body)
	}
	// 同一员工对同一试卷开考两次（各自独立记录）+ 另外两条。
	open(employeeID, paperID)
	open(employeeID, paperID)
	open(employeeID2, paperID)
	open(employeeID, paperID2)

	// 全量：4 条，created_at DESC（非递增）。
	list := decodeExamRecordList(t, get(handler, examRecordsPath, nil))
	if list.Meta.Total != 4 || len(list.Records) != 4 {
		t.Fatalf("total = %d, len = %d, want 4/4", list.Meta.Total, len(list.Records))
	}
	for i := 1; i < len(list.Records); i++ {
		previous, _ := time.Parse(time.RFC3339Nano, list.Records[i-1].CreatedAt)
		current, _ := time.Parse(time.RFC3339Nano, list.Records[i].CreatedAt)
		if current.After(previous) {
			t.Fatalf("records not ordered by created_at DESC: %s before %s", list.Records[i-1].CreatedAt, list.Records[i].CreatedAt)
		}
	}

	// employee_id 筛选：employeeID 的 3 条。
	list = decodeExamRecordList(t, get(handler, examRecordsPath+"?employee_id="+employeeID, nil))
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("employee filter: total = %d, len = %d, want 3/3", list.Meta.Total, len(list.Records))
	}
	for _, record := range list.Records {
		if record.EmployeeID != employeeID {
			t.Fatalf("employee filter leaked record %+v", record)
		}
	}

	// paper_id 筛选：paperID 的 3 条。
	list = decodeExamRecordList(t, get(handler, examRecordsPath+"?paper_id="+paperID, nil))
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("paper filter: total = %d, len = %d, want 3/3", list.Meta.Total, len(list.Records))
	}

	// 联合筛选：employeeID + paperID2 的 1 条。
	list = decodeExamRecordList(t, get(handler, examRecordsPath+"?employee_id="+employeeID+"&paper_id="+paperID2, nil))
	if list.Meta.Total != 1 || len(list.Records) != 1 {
		t.Fatalf("combined filter: total = %d, len = %d, want 1/1", list.Meta.Total, len(list.Records))
	}

	// 空串筛选参数视为未设置：不过滤。
	list = decodeExamRecordList(t, get(handler, examRecordsPath+"?employee_id=&paper_id=", nil))
	if list.Meta.Total != 4 {
		t.Fatalf("empty filters: total = %d, want 4 (unfiltered)", list.Meta.Total)
	}

	// limit=1：1 条且 meta.total 为全量 4；offset=1 跳过第一条。
	list = decodeExamRecordList(t, get(handler, examRecordsPath+"?limit=1", nil))
	if len(list.Records) != 1 || list.Meta.Total != 4 {
		t.Fatalf("limit=1: len = %d, total = %d, want 1/4", len(list.Records), list.Meta.Total)
	}
	list = decodeExamRecordList(t, get(handler, examRecordsPath+"?offset=1", nil))
	if len(list.Records) != 3 || list.Meta.Total != 4 {
		t.Fatalf("offset=1: len = %d, total = %d, want 3/4", len(list.Records), list.Meta.Total)
	}
}

// 列表参数校验：employee_id/paper_id 非空且非 ULID → 400；limit/offset
// 负数或非整数 → 400；均为 {error}。
func TestListExamRecordsParamErrors(t *testing.T) {
	handler := examMux(nil)
	cases := []struct {
		name   string
		query  string
		status int
	}{
		{"non-ULID employee_id", "?employee_id=not-a-ulid", http.StatusBadRequest},
		{"non-ULID paper_id", "?paper_id=not-a-ulid", http.StatusBadRequest},
		{"negative limit", "?limit=-1", http.StatusBadRequest},
		{"non-integer limit", "?limit=abc", http.StatusBadRequest},
		{"float limit", "?limit=1.5", http.StatusBadRequest},
		{"negative offset", "?offset=-1", http.StatusBadRequest},
		{"non-integer offset", "?offset=abc", http.StatusBadRequest},
		{"float offset", "?offset=0.5", http.StatusBadRequest},
	}
	for _, tc := range cases {
		recorder := get(handler, examRecordsPath+tc.query, nil)
		if recorder.Code != tc.status {
			t.Fatalf("%s: status = %d, want %d; body = %s", tc.name, recorder.Code, tc.status, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── CORS 预检 ──────────────────────────────────────────────────────

// 新增 POST 路由的 OPTIONS 预检：允许 Origin 返回 204 且
// Access-Control-Allow-Methods 含 POST。
func TestExamRecordCORSPreflight(t *testing.T) {
	for _, target := range []string{
		examRecordsPath,
		examRecordsPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV/submit",
	} {
		handler := examMux([]string{"https://allowed.example"})
		req := httptest.NewRequest(http.MethodOptions, target, nil)
		req.Header.Set("Origin", "https://allowed.example")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusNoContent {
			t.Fatalf("%s: OPTIONS status = %d, want 204", target, recorder.Code)
		}
		methods := recorder.Header().Get("Access-Control-Allow-Methods")
		if !strings.Contains(methods, "POST") {
			t.Fatalf("%s: Allow-Methods = %q, want POST", target, methods)
		}
		if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
			t.Fatalf("%s: ACAO = %q", target, recorder.Header().Get("Access-Control-Allow-Origin"))
		}
	}
}
