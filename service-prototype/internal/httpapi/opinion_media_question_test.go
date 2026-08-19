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

// opinionMediaQuestionsPath builds the opinion media question
// collection path of a run.
func opinionMediaQuestionsPath(runID string) string {
	return fmt.Sprintf("%s/%s/media-questions", runsPath, runID)
}

// opinionMediaQuestionItemPath builds the opinion media question item
// path of a (run, question) pair.
func opinionMediaQuestionItemPath(runID, questionID string) string {
	return opinionMediaQuestionsPath(runID) + "/" + questionID
}

// opinionMediaQuestionJSON mirrors the opinion media question response
// for assertions.
type opinionMediaQuestionJSON struct {
	ID           string         `json:"id"`
	RunID        string         `json:"run_id"`
	MediaName    string         `json:"media_name"`
	Reporter     string         `json:"reporter"`
	Question     string         `json:"question"`
	QuestionType string         `json:"question_type"`
	Answer       string         `json:"answer"`
	Status       string         `json:"status"`
	AnsweredAt   *string        `json:"answered_at"`
	Metadata     map[string]any `json:"metadata"`
	CreatedBy    string         `json:"created_by"`
	CreatedAt    string         `json:"created_at"`
	UpdatedAt    string         `json:"updated_at"`
}

type opinionMediaQuestionListJSON struct {
	Records []opinionMediaQuestionJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeOpinionMediaQuestion(t *testing.T, recorder *httptest.ResponseRecorder) opinionMediaQuestionJSON {
	t.Helper()
	var question opinionMediaQuestionJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &question); err != nil {
		t.Fatalf("body %q is not an opinion media question JSON: %v", recorder.Body.String(), err)
	}
	return question
}

func decodeOpinionMediaQuestionList(t *testing.T, recorder *httptest.ResponseRecorder) opinionMediaQuestionListJSON {
	t.Helper()
	var list opinionMediaQuestionListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createOpinionMediaQuestion posts the given body to the run's
// collection and asserts 201; returns the created question.
func createOpinionMediaQuestion(t *testing.T, handler http.Handler, runID, body string) opinionMediaQuestionJSON {
	t.Helper()
	if body == "" {
		body = `{"media_name":"新华网","question":"请问本次事件的起因是什么？"}`
	}
	recorder := do(handler, http.MethodPost, opinionMediaQuestionsPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOpinionMediaQuestion(t, recorder)
}

// ─── POST /drills/{rid}/media-questions ─────────────────────────────

// 合法创建：201 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 回显为路径 {rid} 值（body 中出现的 run_id/id 被忽略），
// media_name/question 必填透传，reporter 缺省 ”、question_type 缺省
// 事实类、answer 缺省 ”、status 缺省 未回答、answered_at null、metadata
// 缺省 {}、created_by 缺省 ”、created_at/updated_at 服务端时间且相等。
func TestCreateOpinionMediaQuestionDefaults(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）；body 只
	// 给 media_name/question，其余全部缺省。
	recorder := do(handler, http.MethodPost, opinionMediaQuestionsPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID","media_name":"新华网","question":"请问本次事件的起因是什么？"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	question := decodeOpinionMediaQuestion(t, recorder)
	if !ulidPattern.MatchString(question.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", question.ID)
	}
	if question.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", question.RunID, run.ID)
	}
	if question.MediaName != "新华网" || question.Question != "请问本次事件的起因是什么？" {
		t.Fatalf("media_name/question = %q / %q, want the provided values", question.MediaName, question.Question)
	}
	if question.Reporter != "" {
		t.Fatalf("reporter = %q, want the empty default", question.Reporter)
	}
	if question.QuestionType != "事实类" {
		t.Fatalf("question_type = %q, want the default 事实类", question.QuestionType)
	}
	if question.Answer != "" {
		t.Fatalf("answer = %q, want the empty default", question.Answer)
	}
	if question.Status != "未回答" {
		t.Fatalf("status = %q, want the default 未回答", question.Status)
	}
	if question.AnsweredAt != nil {
		t.Fatalf("answered_at = %v, want null at creation", question.AnsweredAt)
	}
	if question.Metadata == nil || len(question.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", question.Metadata)
	}
	if question.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", question.CreatedBy)
	}
	if question.CreatedAt == "" || question.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", question)
	}
	if question.CreatedAt != question.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", question.CreatedAt, question.UpdatedAt)
	}
}

// 显式字段原样回显：question_type 三种枚举 / reporter / answer / metadata
// / created_by 透传；media_name/question 必填。
func TestCreateOpinionMediaQuestionPassthrough(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	question := createOpinionMediaQuestion(t, handler, run.ID,
		`{"media_name":"澎湃新闻","reporter":"记者小王","question":"网上流传的视频是否属实？","question_type":"尖锐类","answer":"该视频经核实存在断章取义……","metadata":{"platform":"press"},"created_by":"u-admin"}`)
	if question.MediaName != "澎湃新闻" || question.Reporter != "记者小王" ||
		question.Question != "网上流传的视频是否属实？" || question.QuestionType != "尖锐类" ||
		question.Answer != "该视频经核实存在断章取义……" || question.Metadata["platform"] != "press" ||
		question.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", question)
	}

	for _, questionType := range []string{"事实类", "质疑类", "尖锐类"} {
		got := createOpinionMediaQuestion(t, handler, run.ID,
			`{"media_name":"新华网","question":"A","question_type":`+jsonString(questionType)+`}`)
		if got.QuestionType != questionType {
			t.Fatalf("question_type %s: got %q", questionType, got.QuestionType)
		}
	}
}

// 首次创建仅接受 未回答：显式 已回答 → 400，错误体统一 { "error": ... }。
func TestCreateOpinionMediaQuestionRejectsExplicitAnswered(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, opinionMediaQuestionsPath(run.ID),
		`{"media_name":"新华网","question":"A","status":"已回答"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/media-questions（列表）────────────────────────

// 空列表返回 {records:[], meta:{total:0}}。
func TestListOpinionMediaQuestionsEmpty(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, opinionMediaQuestionsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeOpinionMediaQuestionList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 {
		t.Fatalf("records = %#v, want an empty array", list.Records)
	}
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 排序 created_at ASC, id ASC（发布会提问顺序）可断言：依次创建三条（间隔
// sleep 保证毫秒级时间可区分），列表按创建正序返回。
func TestListOpinionMediaQuestionsSortedQuestionOrder(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	first := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"第一条"}`)
	time.Sleep(5 * time.Millisecond)
	second := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"第二条"}`)
	time.Sleep(5 * time.Millisecond)
	third := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"第三条"}`)

	list := decodeOpinionMediaQuestionList(t, do(handler, http.MethodGet, opinionMediaQuestionsPath(run.ID), ""))
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", list.Meta.Total, len(list.Records))
	}
	wantOrder := []opinionMediaQuestionJSON{first, second, third}
	for i, want := range wantOrder {
		if list.Records[i].ID != want.ID {
			t.Fatalf("records[%d] = %q, want %q (created_at ASC)", i, list.Records[i].ID, want.ID)
		}
		if i > 0 && list.Records[i-1].CreatedAt > list.Records[i].CreatedAt {
			t.Fatalf("created_at not ascending: %s then %s", list.Records[i-1].CreatedAt, list.Records[i].CreatedAt)
		}
	}
}

// 筛选 question_type/status 生效（含与分页组合）；非法筛选值 400；
// limit/offset 分页生效。
func TestListOpinionMediaQuestionsFilterAndPagination(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"事实问题","question_type":"事实类"}`)
	createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"澎湃新闻","question":"质疑问题","question_type":"质疑类"}`)
	createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"南方都市报","question":"尖锐问题","question_type":"尖锐类"}`)

	// 单一筛选。
	filtered := decodeOpinionMediaQuestionList(t, do(handler, http.MethodGet,
		opinionMediaQuestionsPath(run.ID)+"?question_type=质疑类", ""))
	if filtered.Meta.Total != 1 || len(filtered.Records) != 1 || filtered.Records[0].Question != "质疑问题" {
		t.Fatalf("question_type filter = %+v, want the single 质疑类 question", filtered)
	}
	filtered = decodeOpinionMediaQuestionList(t, do(handler, http.MethodGet,
		opinionMediaQuestionsPath(run.ID)+"?question_type=事实类", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Question != "事实问题" {
		t.Fatalf("question_type filter = %+v, want the single 事实类 question", filtered)
	}
	// 回答状态筛选：先把「质疑问题」置为已回答。
	list := decodeOpinionMediaQuestionList(t, do(handler, http.MethodGet,
		opinionMediaQuestionsPath(run.ID)+"?question_type=质疑类", ""))
	recorder := do(handler, http.MethodPut, opinionMediaQuestionItemPath(run.ID, list.Records[0].ID),
		`{"media_name":"澎湃新闻","question":"质疑问题","status":"已回答"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT answer: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	filtered = decodeOpinionMediaQuestionList(t, do(handler, http.MethodGet,
		opinionMediaQuestionsPath(run.ID)+"?status=已回答", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Question != "质疑问题" {
		t.Fatalf("status filter = %+v, want the single answered question", filtered)
	}

	// 筛选与分页组合：事实类两条（事实问题、另一事实问题），按创建正序为
	// [事实问题, 另一事实问题]；limit=1 offset=1 → 取第二条，total 保持 2。
	createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华社","question":"另一事实问题","question_type":"事实类"}`)
	page := decodeOpinionMediaQuestionList(t, do(handler, http.MethodGet,
		opinionMediaQuestionsPath(run.ID)+"?question_type=事实类&limit=1&offset=1", ""))
	if page.Meta.Total != 2 || len(page.Records) != 1 {
		t.Fatalf("combined page = %+v, want total 2 and 1 record", page)
	}
	if page.Records[0].Question != "另一事实问题" {
		t.Fatalf("combined page record = %q, want 另一事实问题 (question-order page 2)", page.Records[0].Question)
	}

	// 非法筛选值与分页参数 → 400，错误体统一。
	for name, query := range map[string]string{
		"invalid question_type": "?question_type=诱导类",
		"invalid status":        "?status=回答中",
		"invalid limit":         "?limit=abc",
		"negative limit":        "?limit=-1",
		"invalid offset":        "?offset=1.5",
		"negative offset":       "?offset=-1",
	} {
		recorder := do(handler, http.MethodGet, opinionMediaQuestionsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET/PUT/DELETE /drills/{rid}/media-questions/{mqid} ────────────

// GET 200 返回完整对象；PUT 200 返回更新后对象（部分更新：缺省字段保持
// 原值，answer 可随时修订）；PUT 后 GET 反映更新；DELETE 204；DELETE 后
// GET 404。
func TestGetPutDeleteOpinionMediaQuestion(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionMediaQuestion(t, handler, run.ID,
		`{"media_name":"新华网","reporter":"记者小王","question":"原问题","question_type":"质疑类","answer":"原回答","metadata":{"k":"v"},"created_by":"u-admin"}`)
	itemPath := opinionMediaQuestionItemPath(run.ID, created.ID)

	// GET 200。
	recorder := do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeOpinionMediaQuestion(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.Question != "原问题" || fetched.MediaName != "新华网" {
		t.Fatalf("GET = %+v, want the created question", fetched)
	}

	// PUT 部分更新：只改 answer（可随时修订），其余保持。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath,
		`{"media_name":"新华网","question":"原问题","answer":"修订后的回答"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeOpinionMediaQuestion(t, recorder)
	if updated.Answer != "修订后的回答" || updated.MediaName != "新华网" || updated.Reporter != "记者小王" ||
		updated.QuestionType != "质疑类" || updated.Status != "未回答" ||
		updated.Metadata["k"] != "v" || updated.CreatedBy != "u-admin" ||
		updated.ID != created.ID {
		t.Fatalf("partial update = %+v, want answer changed and the rest kept", updated)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must be refreshed on PUT")
	}

	// PUT 后 GET 反映更新。
	fetched = decodeOpinionMediaQuestion(t, do(handler, http.MethodGet, itemPath, ""))
	if fetched.Answer != "修订后的回答" || fetched.Reporter != "记者小王" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// DELETE 204；DELETE 后 GET 404。
	recorder = do(handler, http.MethodDelete, itemPath, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// 不存在 mqid：GET/PUT/DELETE 均 404，错误体统一 { "error": ... }。
func TestOpinionMediaQuestionItemNotFound(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionMediaQuestionItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionMediaQuestionItemPath(run.ID, missing),
		`{"media_name":"新华网","question":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionMediaQuestionItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 回答状态机 ──────────────────────────────────────────────────────

// 未回答→已回答 单向：置 已回答 时服务端设 answered_at；同值 no-op 合法且
// answered_at 保持不变（不重置）；已回答→未回答 400；PUT 未涉及 status 时
// answered_at 保持原值；创建/未回答时 answered_at 为 null。
func TestOpinionMediaQuestionAnswerStateMachine(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"A"}`)
	if created.AnsweredAt != nil {
		t.Fatalf("answered_at = %v at creation, want null", created.AnsweredAt)
	}
	itemPath := opinionMediaQuestionItemPath(run.ID, created.ID)

	// 未回答 → 已回答：answered_at 被服务端设置。
	recorder := do(handler, http.MethodPut, itemPath,
		`{"media_name":"新华网","question":"A","status":"已回答"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("answer transition: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	answered := decodeOpinionMediaQuestion(t, recorder)
	if answered.Status != "已回答" || answered.AnsweredAt == nil {
		t.Fatalf("answered = %+v, want 已回答 with a server-set answered_at", answered)
	}
	answeredAt := *answered.AnsweredAt

	// 同值 no-op：已回答 → 已回答 200，answered_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath,
		`{"media_name":"新华网","question":"A","status":"已回答"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	again := decodeOpinionMediaQuestion(t, recorder)
	if again.Status != "已回答" || again.AnsweredAt == nil || *again.AnsweredAt != answeredAt {
		t.Fatalf("no-op answered_at = %v, want the unchanged %v", again.AnsweredAt, answeredAt)
	}

	// PUT 未涉及 status：answered_at 保持原值。
	recorder = do(handler, http.MethodPut, itemPath, `{"media_name":"新华网","question":"B"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("update without status: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionMediaQuestion(t, recorder); got.AnsweredAt == nil || *got.AnsweredAt != answeredAt {
		t.Fatalf("answered_at after unrelated update = %v, want %v", got.AnsweredAt, answeredAt)
	}

	// 已回答 → 未回答：400。
	recorder = do(handler, http.MethodPut, itemPath,
		`{"media_name":"新华网","question":"B","status":"未回答"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("backward transition: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 同值 未回答 no-op：200 且 answered_at 保持 null。
	pending := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"B"}`)
	recorder = do(handler, http.MethodPut, opinionMediaQuestionItemPath(run.ID, pending.ID),
		`{"media_name":"新华网","question":"B","status":"未回答"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("未回答 no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionMediaQuestion(t, recorder); got.Status != "未回答" || got.AnsweredAt != nil {
		t.Fatalf("pending no-op = %+v, want 未回答 with null answered_at", got)
	}
}

// ─── 失败路径（POST 与 PUT 双入口一致覆盖）───────────────────────────

// 缺 media_name/question（POST 与 PUT 双入口必填）、非法 question_type/
// status、metadata 非 JSON 对象、空/畸形 body → 400，错误体统一
// { "error": ... }。
func TestOpinionMediaQuestionInvalidBodyBothEntries(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"A"}`)
	itemPath := opinionMediaQuestionItemPath(run.ID, created.ID)

	postBodies := map[string]string{
		"empty body":            "",
		"malformed JSON":        `{"media_name":`,
		"JSON string":           `"内容"`,
		"JSON array":            `[{"media_name":"新华网"}]`,
		"JSON null":             `null`,
		"missing media_name":    `{"question":"A"}`,
		"empty media_name":      `{"media_name":"","question":"A"}`,
		"missing question":      `{"media_name":"新华网"}`,
		"empty question":        `{"media_name":"新华网","question":""}`,
		"invalid question_type": `{"media_name":"新华网","question":"A","question_type":"诱导类"}`,
		"invalid status":        `{"media_name":"新华网","question":"A","status":"回答中"}`,
		"metadata array":        `{"media_name":"新华网","question":"A","metadata":[1]}`,
		"metadata string":       `{"media_name":"新华网","question":"A","metadata":"x"}`,
		"metadata null":         `{"media_name":"新华网","question":"A","metadata":null}`,
	}
	for name, body := range postBodies {
		recorder := do(handler, http.MethodPost, opinionMediaQuestionsPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST %s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 双入口一致覆盖：media_name/question 缺省同样 400（双入口必填），
	// 非法 question_type/status 同样 400。
	putBodies := map[string]string{
		"empty body":            "",
		"malformed JSON":        `{"media_name":`,
		"JSON null":             `null`,
		"missing media_name":    `{"question":"A"}`,
		"empty media_name":      `{"media_name":""}`,
		"missing question":      `{"media_name":"新华网"}`,
		"empty question":        `{"media_name":"新华网","question":""}`,
		"invalid question_type": `{"media_name":"新华网","question":"A","question_type":"诱导类"}`,
		"invalid status":        `{"media_name":"新华网","question":"A","status":"回答中"}`,
		"metadata array":        `{"media_name":"新华网","question":"A","metadata":[1]}`,
		"metadata null":         `{"media_name":"新华网","question":"A","metadata":null}`,
	}
	for name, body := range putBodies {
		recorder := do(handler, http.MethodPut, itemPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT %s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── run 不存在 / 写门控 ─────────────────────────────────────────────

// run 不存在：POST/GET（列表与单条）/PUT/DELETE 均 404，错误体统一
// { "error": ... }。
func TestOpinionMediaQuestionRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionMediaQuestionsPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET list: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, opinionMediaQuestionsPath(missing),
		`{"media_name":"新华网","question":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("POST: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, opinionMediaQuestionItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET item: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionMediaQuestionItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"),
		`{"media_name":"新华网","question":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionMediaQuestionItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 写门控：仅 run 进行中 可写——未开始/已完成/已终止 时 POST/PUT/DELETE
// 均 400；GET（列表与单条）不受门控。
func TestOpinionMediaQuestionWriteGate(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	notStarted := createRun(t, handler, scenario.ID, "")
	completed := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, completed.ID)
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	terminated := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, terminated.ID)
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/terminate", "")

	// 未开始/已完成/已终止：POST 均 400。
	for _, run := range []runJSON{notStarted, completed, terminated} {
		recorder := do(handler, http.MethodPost, opinionMediaQuestionsPath(run.ID),
			`{"media_name":"新华网","question":"A"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 先在进行中 run 上创建问题，再结束 run：PUT/DELETE 均 400；
	// GET 不受门控（200）。
	locked := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, locked.ID)
	question := createOpinionMediaQuestion(t, handler, locked.ID, `{"media_name":"新华网","question":"A"}`)
	do(handler, http.MethodPost, runsPath+"/"+locked.ID+"/complete", "")
	itemPath := opinionMediaQuestionItemPath(locked.ID, question.ID)

	recorder := do(handler, http.MethodPut, itemPath, `{"media_name":"新华网","question":"B"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("PUT on 已完成: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodDelete, itemPath, "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE on 已完成: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// GET 不受门控：列表与单条均 200。
	recorder = do(handler, http.MethodGet, opinionMediaQuestionsPath(locked.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成: status = %d, want 200", recorder.Code)
	}
	if list := decodeOpinionMediaQuestionList(t, recorder); list.Meta.Total != 1 {
		t.Fatalf("list on 已完成: total = %d, want 1", list.Meta.Total)
	}
	recorder = do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET item on 已完成: status = %d, want 200", recorder.Code)
	}
}

// ─── 级联：删除 run 后问答记录清空 ───────────────────────────────────

// 创建问答后 DELETE run（runs 路由），再 GET 问答列表返回 404（run 已
// 不存在；内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesOpinionMediaQuestions(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"A"}`)
	createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"澎湃新闻","question":"B"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, opinionMediaQuestionsPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET media questions after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON：collection 路由 Allow 为 GET, POST；item
// 路由 Allow 为 GET, PUT, DELETE。
func TestOpinionMediaQuestionMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	question := createOpinionMediaQuestion(t, handler, run.ID, `{"media_name":"新华网","question":"A"}`)

	// collection：PATCH/PUT → 405，Allow 含 GET 与 POST。
	for _, method := range []string{http.MethodPatch, http.MethodPut} {
		recorder := do(handler, method, opinionMediaQuestionsPath(run.ID), `{}`)
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s collection: status = %d, want 405", method, recorder.Code)
		}
		allow := recorder.Header().Get("Allow")
		if !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
			t.Fatalf("%s collection: Allow = %q, want GET and POST", method, allow)
		}
		decodeError(t, recorder)
	}

	// item：POST/PATCH → 405，Allow 含 GET、PUT 与 DELETE。
	for _, method := range []string{http.MethodPost, http.MethodPatch} {
		recorder := do(handler, method, opinionMediaQuestionItemPath(run.ID, question.ID), `{}`)
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s item: status = %d, want 405", method, recorder.Code)
		}
		allow := recorder.Header().Get("Allow")
		if !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
			t.Fatalf("%s item: Allow = %q, want GET, PUT and DELETE", method, allow)
		}
		decodeError(t, recorder)
	}
}

// 允许 Origin 的 OPTIONS 预检返回 204，Allow-Methods 含 GET/POST/PUT/
// DELETE（写方法全覆盖），ACAO 回显。
func TestOpinionMediaQuestionCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	target := opinionMediaQuestionsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV")

	req := httptest.NewRequest(http.MethodOptions, target, nil)
	req.Header.Set("Origin", "https://allowed.example")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("preflight status = %d, want 204", recorder.Code)
	}
	methods := recorder.Header().Get("Access-Control-Allow-Methods")
	for _, method := range []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"} {
		if !strings.Contains(methods, method) {
			t.Fatalf("Allow-Methods = %q, want it to contain %s", methods, method)
		}
	}
	if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
		t.Fatalf("ACAO = %q, want the allowed origin", recorder.Header().Get("Access-Control-Allow-Origin"))
	}
}
