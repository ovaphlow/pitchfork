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

// messagesPath builds the message collection path of a run.
func messagesPath(runID string) string {
	return fmt.Sprintf("%s/%s/messages", runsPath, runID)
}

// messageItemPath builds the message item path of a (run, message)
// pair.
func messageItemPath(runID, messageID string) string {
	return messagesPath(runID) + "/" + messageID
}

// messageJSON mirrors the message response for assertions.
type messageJSON struct {
	ID         string  `json:"id"`
	RunID      string  `json:"run_id"`
	SenderType string  `json:"sender_type"`
	SenderName string  `json:"sender_name"`
	Content    string  `json:"content"`
	SentAt     *string `json:"sent_at"`
	CreatedBy  string  `json:"created_by"`
	CreatedAt  string  `json:"created_at"`
	UpdatedAt  string  `json:"updated_at"`
}

type messageListJSON struct {
	Records []messageJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeMessage(t *testing.T, recorder *httptest.ResponseRecorder) messageJSON {
	t.Helper()
	var message messageJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &message); err != nil {
		t.Fatalf("body %q is not a message JSON: %v", recorder.Body.String(), err)
	}
	return message
}

func decodeMessageList(t *testing.T, recorder *httptest.ResponseRecorder) messageListJSON {
	t.Helper()
	var list messageListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// validMessageBody is a minimal valid message creation body.
const validMessageBody = `{"sender_type":"指挥中心","sender_name":"总指挥","content":"东区出现大客流聚集，请现场人员立即疏导"}`

// sendMessage posts the given body to the run's collection and asserts
// 201; returns the created message.
func sendMessage(t *testing.T, handler http.Handler, runID, body string) messageJSON {
	t.Helper()
	if body == "" {
		body = validMessageBody
	}
	recorder := do(handler, http.MethodPost, messagesPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeMessage(t, recorder)
}

// ─── POST /drills/{rid}/messages ─────────────────────────────────────

// 合法发送：201，id 为服务端生成的 26 位 Crockford Base32 ULID，run_id
// 取自路径回显（body 传入 run_id/id 被忽略），sender_type/sender_name/
// content 回显，sent_at 服务端创建时设置非空（body 传入 sent_at 被忽略），
// created_by 透传（缺省空串）、sender_name 缺省空串，created_at/updated_at
// 服务端设置。
func TestSendMessageSuccess(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, messagesPath(run.ID),
		`{"sender_type":"指挥中心","sender_name":"总指挥","content":"东区出现大客流聚集，请现场人员立即疏导","run_id":"ignored","id":"FAKE-ID","sent_at":"2020-01-01T00:00:00Z","created_by":"u-commander"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	message := decodeMessage(t, recorder)
	if !ulidPattern.MatchString(message.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", message.ID)
	}
	if message.RunID != run.ID {
		t.Fatalf("run_id = %q, want the run from the path (body run_id ignored)", message.RunID)
	}
	if message.SenderType != "指挥中心" || message.SenderName != "总指挥" ||
		message.Content != "东区出现大客流聚集，请现场人员立即疏导" {
		t.Fatalf("sender fields/content not echoed: %+v", message)
	}
	if message.SentAt == nil || *message.SentAt == "" || strings.HasPrefix(*message.SentAt, "2020") {
		t.Fatalf("sent_at = %v, want the server-set creation instant (body sent_at ignored)", message.SentAt)
	}
	if message.CreatedBy != "u-commander" {
		t.Fatalf("created_by = %q, want u-commander", message.CreatedBy)
	}
	if message.CreatedAt == "" || message.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", message)
	}
}

// 缺省口径：sender_name 与 created_by 省略时均为空串（sender_name 可
// 选，不 400）；发送方 现场人员 显式写入。
func TestSendMessageDefaultsAndFieldSender(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, messagesPath(run.ID),
		`{"sender_type":"现场人员","content":"收到，3 号出口已就位"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	message := decodeMessage(t, recorder)
	if message.SenderType != "现场人员" {
		t.Fatalf("sender_type = %q, want 现场人员", message.SenderType)
	}
	if message.SenderName != "" {
		t.Fatalf("sender_name = %q, want an empty default", message.SenderName)
	}
	if message.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", message.CreatedBy)
	}
}

// 失败路径：缺 content（含空白）、缺 sender_type（含空白）、非法
// sender_type（非 指挥中心/现场人员）、空 body、畸形 body → 400，错误体
// 统一 {"error": ...}。
func TestSendMessageFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for name, body := range map[string]string{
		"missing content":     `{"sender_type":"指挥中心"}`,
		"blank content":       `{"sender_type":"指挥中心","content":"  "}`,
		"missing sender_type": `{"content":"内容"}`,
		"blank sender_type":   `{"sender_type":"","content":"内容"}`,
		"invalid sender_type": `{"sender_type":"游客","content":"内容"}`,
		"malformed body":      `{"sender_type":`,
	} {
		recorder := do(handler, http.MethodPost, messagesPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 空 body → 400。
	recorder := do(handler, http.MethodPost, messagesPath(run.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("empty body: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 状态约束：run 不存在 404（优先于门控）；仅 进行中 可 POST（未开始/
// 已完成 400）。
func TestSendMessageRunState(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	// run 不存在 → 404。
	recorder := do(handler, http.MethodPost, messagesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), validMessageBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 未开始 → 400。
	notStarted := createRun(t, handler, scenario.ID, "")
	recorder = do(handler, http.MethodPost, messagesPath(notStarted.ID), validMessageBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 进行中 → 201。
	inProgress := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, inProgress.ID)
	recorder = do(handler, http.MethodPost, messagesPath(inProgress.ID), validMessageBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("进行中 run: status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}

	// 已完成 → 400。
	completed := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, completed.ID)
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPost, messagesPath(completed.ID), validMessageBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/messages ──────────────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}；run 不存在 404。
func TestListMessagesEmptyAndMissingRun(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, messagesPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeMessageList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("empty list = %+v, want records [] and total 0", list)
	}

	recorder = do(handler, http.MethodGet, messagesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 排序 created_at ASC, id ASC（聊天顺序）；sender_type 筛选生效；meta.
// total 为筛选后的总数。
func TestListMessagesSortedAndFiltered(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// 依次发送 3 条消息：指挥中心 → 现场人员 → 指挥中心。
	sendMessage(t, handler, run.ID, `{"sender_type":"指挥中心","sender_name":"总指挥","content":"一号：东区客流超阈值"}`)
	time.Sleep(5 * time.Millisecond)
	field := sendMessage(t, handler, run.ID, `{"sender_type":"现场人员","sender_name":"张伟","content":"二号：3 号出口已就位"}`)
	time.Sleep(5 * time.Millisecond)
	sendMessage(t, handler, run.ID, `{"sender_type":"指挥中心","sender_name":"总指挥","content":"三号：持续监控"}`)

	recorder := do(handler, http.MethodGet, messagesPath(run.ID), "")
	list := decodeMessageList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	// created_at ASC：一号、二号、三号（聊天顺序）。
	if list.Records[0].Content != "一号：东区客流超阈值" ||
		list.Records[1].ID != field.ID ||
		list.Records[2].Content != "三号：持续监控" {
		t.Fatalf("records not in created_at ASC order: %+v", list.Records)
	}

	// sender_type 筛选：现场人员 → 仅二号。
	recorder = do(handler, http.MethodGet, messagesPath(run.ID)+"?sender_type="+"现场人员", "")
	list = decodeMessageList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].Content != "二号：3 号出口已就位" {
		t.Fatalf("sender_type filter: records = %d, total = %d; want 1 / 1 with 二号", len(list.Records), list.Meta.Total)
	}
}

// limit/offset 分页生效（缺省 limit 50，meta.total 保持总数）；非法枚举
// 筛选或非法 limit/offset → 400。
func TestListMessagesPaginationAndInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		sendMessage(t, handler, run.ID, fmt.Sprintf(
			`{"sender_type":"指挥中心","sender_name":"总指挥","content":"消息%03d"}`, i))
	}

	recorder := do(handler, http.MethodGet, messagesPath(run.ID)+"?limit=2&offset=0", "")
	list := decodeMessageList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	// created_at ASC：最早创建的（消息001、消息002）排在最前。
	if list.Records[0].Content != "消息001" || list.Records[1].Content != "消息002" {
		t.Fatalf("first page not in created_at ASC order: %+v", list.Records)
	}

	recorder = do(handler, http.MethodGet, messagesPath(run.ID)+"?limit=2&offset=52", "")
	list = decodeMessageList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d; want 1 / 53", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, messagesPath(run.ID), "")
	list = decodeMessageList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid sender_type": "?sender_type=" + "游客",
		"invalid limit":       "?limit=abc",
		"negative limit":      "?limit=-1",
		"invalid offset":      "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, messagesPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/messages/{mid} ────────────────────────────────

// 存在的 (run, mid) 返回 200 完整对象（含 run_id/sent_at）；mid 不存在、
// 消息不属于该 run、run 不存在 → 404 {error}；GET 不受写门控（已完成
// run 的消息仍 200）。
func TestGetMessage(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	message := sendMessage(t, handler, runA.ID, `{"sender_type":"指挥中心","sender_name":"总指挥","content":"东区客流超阈值"}`)

	recorder := do(handler, http.MethodGet, messageItemPath(runA.ID, message.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	got := decodeMessage(t, recorder)
	if got.ID != message.ID || got.RunID != runA.ID || got.Content != "东区客流超阈值" || got.SenderType != "指挥中心" {
		t.Fatalf("get does not return the full object: %+v", got)
	}
	if got.SentAt == nil {
		t.Fatalf("get must return sent_at: %+v", got)
	}

	recorder = do(handler, http.MethodGet, messageItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown mid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, messageItemPath(runB.ID, message.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("message of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, messageItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", message.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// GET 不受写门控：已完成 run 的消息仍 200。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	messageCompletedRun := sendMessage(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, messageItemPath(completed.ID, messageCompletedRun.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, messagesPath(completed.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── DELETE /drills/{rid}/messages/{mid} ─────────────────────────────

// DELETE 204；DELETE 后 GET 404；mid 不存在 404；run 不存在 404（优先
// 于门控）；非 进行中 DELETE 400。
func TestDeleteMessage(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	message := sendMessage(t, handler, run.ID, "")

	recorder := do(handler, http.MethodDelete, messageItemPath(run.ID, message.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, messageItemPath(run.ID, message.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// mid 不存在 → 404。
	recorder = do(handler, http.MethodDelete, messageItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown mid DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// run 不存在 → 404。
	recorder = do(handler, http.MethodDelete, messageItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", message.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 非 进行中 → 400。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	messageCompleted := sendMessage(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, messageItemPath(completed.ID, messageCompleted.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE on 已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 405 与 Allow ────────────────────────────────────────────────────

// 集合只允许 GET/POST、条目只允许 GET/POST/DELETE（消息不可编辑，PUT
// 405）：其他方法 405 且带 Allow 头，响应体为 JSON 错误。
func TestMessagesMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	message := sendMessage(t, handler, run.ID, "")

	for _, testCase := range []struct {
		name   string
		method string
		target string
		allow  string
	}{
		{"collection PUT", http.MethodPut, messagesPath(run.ID), "GET, POST"},
		{"collection PATCH", http.MethodPatch, messagesPath(run.ID), "GET, POST"},
		{"collection DELETE", http.MethodDelete, messagesPath(run.ID), "GET, POST"},
		{"item PUT", http.MethodPut, messageItemPath(run.ID, message.ID), "GET, POST, DELETE"},
		{"item PATCH", http.MethodPatch, messageItemPath(run.ID, message.ID), "GET, POST, DELETE"},
		{"item POST", http.MethodPost, messageItemPath(run.ID, message.ID), "GET, POST, DELETE"},
	} {
		recorder := do(handler, testCase.method, testCase.target, "")
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s: status = %d, want 405; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		if allow := recorder.Header().Get("Allow"); allow != testCase.allow {
			t.Fatalf("%s: Allow = %q, want %q", testCase.name, allow, testCase.allow)
		}
		decodeError(t, recorder)
	}
}

// ─── CORS 预检 ───────────────────────────────────────────────────────

// 对 messages 路径的 OPTIONS 预检：204 且 Access-Control-Allow-Methods
// 含 POST/DELETE（写方法可被浏览器调用）。
func TestMessagesCORSPreflight(t *testing.T) {
	req := httptest.NewRequest(http.MethodOptions, messagesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), nil)
	req.Header.Set("Origin", "https://allowed.example")
	recorder := httptest.NewRecorder()
	testMux([]string{"https://allowed.example"}).ServeHTTP(recorder, req)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("preflight status = %d, want 204", recorder.Code)
	}
	methods := recorder.Header().Get("Access-Control-Allow-Methods")
	for _, method := range []string{"POST", "DELETE"} {
		if !strings.Contains(methods, method) {
			t.Fatalf("Access-Control-Allow-Methods = %q, want it to contain %s", methods, method)
		}
	}
}
