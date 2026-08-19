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

// opinionPostsPath builds the opinion post collection path of a run.
func opinionPostsPath(runID string) string {
	return fmt.Sprintf("%s/%s/posts", runsPath, runID)
}

// opinionPostItemPath builds the opinion post item path of a (run,
// post) pair.
func opinionPostItemPath(runID, postID string) string {
	return opinionPostsPath(runID) + "/" + postID
}

// opinionPostJSON mirrors the opinion post response for assertions.
type opinionPostJSON struct {
	ID         string         `json:"id"`
	RunID      string         `json:"run_id"`
	Source     string         `json:"source"`
	Content    string         `json:"content"`
	Sentiment  string         `json:"sentiment"`
	Heat       int            `json:"heat"`
	WarnStatus string         `json:"warn_status"`
	WarnedAt   *string        `json:"warned_at"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
	CreatedAt  string         `json:"created_at"`
	UpdatedAt  string         `json:"updated_at"`
}

type opinionPostListJSON struct {
	Records []opinionPostJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeOpinionPost(t *testing.T, recorder *httptest.ResponseRecorder) opinionPostJSON {
	t.Helper()
	var post opinionPostJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &post); err != nil {
		t.Fatalf("body %q is not an opinion post JSON: %v", recorder.Body.String(), err)
	}
	return post
}

func decodeOpinionPostList(t *testing.T, recorder *httptest.ResponseRecorder) opinionPostListJSON {
	t.Helper()
	var list opinionPostListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createOpinionPost posts the given body to the run's collection and
// asserts 201; returns the created post.
func createOpinionPost(t *testing.T, handler http.Handler, runID, body string) opinionPostJSON {
	t.Helper()
	if body == "" {
		body = `{"content":"展厅入口聚集大量游客"}`
	}
	recorder := do(handler, http.MethodPost, opinionPostsPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOpinionPost(t, recorder)
}

// ─── POST /drills/{rid}/posts ────────────────────────────────────────

// 合法创建：201 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 回显为路径 {rid} 值（body 中出现的 run_id/id 被忽略），
// content 必填透传，source 缺省 微博、sentiment 缺省 负面、heat 缺省 0、
// warn_status 缺省 未预警、warned_at null、metadata 缺省 {}、created_by
// 缺省 ”、created_at/updated_at 服务端时间且相等。
func TestCreateOpinionPostDefaults(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）；body 只
	// 给 content，其余全部缺省。
	recorder := do(handler, http.MethodPost, opinionPostsPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID","content":"展厅入口聚集大量游客"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	post := decodeOpinionPost(t, recorder)
	if !ulidPattern.MatchString(post.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", post.ID)
	}
	if post.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", post.RunID, run.ID)
	}
	if post.Content != "展厅入口聚集大量游客" {
		t.Fatalf("content = %q, want the provided value", post.Content)
	}
	if post.Source != "微博" {
		t.Fatalf("source = %q, want the default 微博", post.Source)
	}
	if post.Sentiment != "负面" {
		t.Fatalf("sentiment = %q, want the default 负面", post.Sentiment)
	}
	if post.Heat != 0 {
		t.Fatalf("heat = %d, want the default 0", post.Heat)
	}
	if post.WarnStatus != "未预警" {
		t.Fatalf("warn_status = %q, want the default 未预警", post.WarnStatus)
	}
	if post.WarnedAt != nil {
		t.Fatalf("warned_at = %v, want null at creation", post.WarnedAt)
	}
	if post.Metadata == nil || len(post.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", post.Metadata)
	}
	if post.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", post.CreatedBy)
	}
	if post.CreatedAt == "" || post.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", post)
	}
	if post.CreatedAt != post.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", post.CreatedAt, post.UpdatedAt)
	}
}

// 显式字段原样回显：source 五种枚举 / sentiment 三种枚举 / heat 0–100 /
// metadata / created_by 透传；content 必填。
func TestCreateOpinionPostPassthrough(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	post := createOpinionPost(t, handler, run.ID,
		`{"content":"展厅出口出现踩踏风险","source":"新闻媒体","sentiment":"正面","heat":88,"metadata":{"platform":"news"},"created_by":"u-admin"}`)
	if post.Content != "展厅出口出现踩踏风险" || post.Source != "新闻媒体" || post.Sentiment != "正面" ||
		post.Heat != 88 || post.Metadata["platform"] != "news" || post.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", post)
	}

	for _, source := range []string{"微博", "抖音", "新闻媒体", "论坛", "其他"} {
		got := createOpinionPost(t, handler, run.ID, `{"content":"A","source":`+jsonString(source)+`}`)
		if got.Source != source {
			t.Fatalf("source %s: got %q", source, got.Source)
		}
	}
	for _, sentiment := range []string{"负面", "中性", "正面"} {
		got := createOpinionPost(t, handler, run.ID, `{"content":"A","sentiment":`+jsonString(sentiment)+`}`)
		if got.Sentiment != sentiment {
			t.Fatalf("sentiment %s: got %q", sentiment, got.Sentiment)
		}
	}
	got := createOpinionPost(t, handler, run.ID, `{"content":"A","heat":100}`)
	if got.Heat != 100 {
		t.Fatalf("heat = %d, want 100", got.Heat)
	}
}

// 首次创建仅接受 未预警：显式 已预警 → 400，错误体统一 { "error": ... }。
func TestCreateOpinionPostRejectsExplicitWarned(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, opinionPostsPath(run.ID), `{"content":"A","warn_status":"已预警"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/posts（列表）──────────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}。
func TestListOpinionPostsEmpty(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, opinionPostsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeOpinionPostList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 {
		t.Fatalf("records = %#v, want an empty array", list.Records)
	}
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 排序 created_at DESC（监测流最新在前）可断言：依次创建三条（间隔 sleep
// 保证毫秒级时间可区分），列表按创建逆序返回。
func TestListOpinionPostsSortedNewestFirst(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	first := createOpinionPost(t, handler, run.ID, `{"content":"第一条"}`)
	time.Sleep(5 * time.Millisecond)
	second := createOpinionPost(t, handler, run.ID, `{"content":"第二条"}`)
	time.Sleep(5 * time.Millisecond)
	third := createOpinionPost(t, handler, run.ID, `{"content":"第三条"}`)

	list := decodeOpinionPostList(t, do(handler, http.MethodGet, opinionPostsPath(run.ID), ""))
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", list.Meta.Total, len(list.Records))
	}
	wantOrder := []opinionPostJSON{third, second, first}
	for i, want := range wantOrder {
		if list.Records[i].ID != want.ID {
			t.Fatalf("records[%d] = %q, want %q (created_at DESC)", i, list.Records[i].ID, want.ID)
		}
		if i > 0 && list.Records[i-1].CreatedAt < list.Records[i].CreatedAt {
			t.Fatalf("created_at not descending: %s then %s", list.Records[i-1].CreatedAt, list.Records[i].CreatedAt)
		}
	}
}

// 筛选 source/sentiment/warn_status 生效（含与分页组合）；非法筛选值
// 400；limit/offset 分页生效。
func TestListOpinionPostsFilterAndPagination(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	createOpinionPost(t, handler, run.ID, `{"content":"负面微博","source":"微博","sentiment":"负面","heat":90}`)
	createOpinionPost(t, handler, run.ID, `{"content":"正面新闻","source":"新闻媒体","sentiment":"正面","heat":10}`)
	createOpinionPost(t, handler, run.ID, `{"content":"中性抖音","source":"抖音","sentiment":"中性","heat":50}`)

	// 单一筛选。
	filtered := decodeOpinionPostList(t, do(handler, http.MethodGet,
		opinionPostsPath(run.ID)+"?sentiment=负面", ""))
	if filtered.Meta.Total != 1 || len(filtered.Records) != 1 || filtered.Records[0].Content != "负面微博" {
		t.Fatalf("sentiment filter = %+v, want the single 负面 post", filtered)
	}
	filtered = decodeOpinionPostList(t, do(handler, http.MethodGet,
		opinionPostsPath(run.ID)+"?source=新闻媒体", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Content != "正面新闻" {
		t.Fatalf("source filter = %+v, want the single 新闻媒体 post", filtered)
	}
	// 预警状态：先把「负面微博」置为已预警。
	list := decodeOpinionPostList(t, do(handler, http.MethodGet, opinionPostsPath(run.ID)+"?sentiment=负面", ""))
	recorder := do(handler, http.MethodPut, opinionPostItemPath(run.ID, list.Records[0].ID), `{"warn_status":"已预警"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT warn: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	filtered = decodeOpinionPostList(t, do(handler, http.MethodGet,
		opinionPostsPath(run.ID)+"?warn_status=已预警", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Content != "负面微博" {
		t.Fatalf("warn_status filter = %+v, want the single warned post", filtered)
	}

	// 筛选与分页组合：负面三条（负面微博、负面抖音、另一负面微博），按
	// 创建逆序为 [另一负面微博, 负面抖音, 负面微博]；limit=1 offset=1
	// → 取第二条（负面抖音），total 保持 3。
	createOpinionPost(t, handler, run.ID, `{"content":"负面抖音","source":"抖音","sentiment":"负面"}`)
	time.Sleep(5 * time.Millisecond)
	createOpinionPost(t, handler, run.ID, `{"content":"另一负面微博","source":"微博","sentiment":"负面"}`)
	page := decodeOpinionPostList(t, do(handler, http.MethodGet,
		opinionPostsPath(run.ID)+"?sentiment=负面&limit=1&offset=1", ""))
	if page.Meta.Total != 3 || len(page.Records) != 1 {
		t.Fatalf("combined page = %+v, want total 3 and 1 record", page)
	}
	if page.Records[0].Content != "负面抖音" {
		t.Fatalf("combined page record = %q, want 负面抖音 (newest-first page 2)", page.Records[0].Content)
	}

	// 非法筛选值与分页参数 → 400，错误体统一。
	for name, query := range map[string]string{
		"invalid source":      "?source=微信",
		"invalid sentiment":   "?sentiment=消极",
		"invalid warn_status": "?warn_status=预警中",
		"invalid limit":       "?limit=abc",
		"negative limit":      "?limit=-1",
		"invalid offset":      "?offset=1.5",
		"negative offset":     "?offset=-1",
	} {
		recorder := do(handler, http.MethodGet, opinionPostsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET/PUT/DELETE /drills/{rid}/posts/{pid} ────────────────────────

// GET 200 返回完整对象；PUT 200 返回更新后对象（部分更新：缺省字段保持
// 原值）；PUT 后 GET 反映更新；DELETE 204；DELETE 后 GET 404。
func TestGetPutDeleteOpinionPost(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionPost(t, handler, run.ID,
		`{"content":"原内容","source":"论坛","sentiment":"负面","heat":30,"metadata":{"k":"v"},"created_by":"u-admin"}`)
	itemPath := opinionPostItemPath(run.ID, created.ID)

	// GET 200。
	recorder := do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeOpinionPost(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.Content != "原内容" || fetched.Heat != 30 {
		t.Fatalf("GET = %+v, want the created post", fetched)
	}

	// PUT 部分更新：只改 content，其余保持。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"content":"新内容"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeOpinionPost(t, recorder)
	if updated.Content != "新内容" || updated.Source != "论坛" || updated.Sentiment != "负面" ||
		updated.Heat != 30 || updated.Metadata["k"] != "v" || updated.CreatedBy != "u-admin" ||
		updated.ID != created.ID {
		t.Fatalf("partial update = %+v, want content changed and the rest kept", updated)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must be refreshed on PUT")
	}

	// PUT 后 GET 反映更新。
	fetched = decodeOpinionPost(t, do(handler, http.MethodGet, itemPath, ""))
	if fetched.Content != "新内容" || fetched.Heat != 30 {
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

// 不存在 pid：GET/PUT/DELETE 均 404，错误体统一 { "error": ... }。
func TestOpinionPostItemNotFound(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionPostItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionPostItemPath(run.ID, missing), `{"content":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionPostItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 预警状态机 ──────────────────────────────────────────────────────

// 未预警→已预警 单向：置 已预警 时服务端设 warned_at；同值 no-op 合法且
// warned_at 保持不变（不重置）；已预警→未预警 400；PUT 未涉及 warn_status
// 时 warned_at 保持原值；创建/未预警时 warned_at 为 null。
func TestOpinionPostWarnStateMachine(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionPost(t, handler, run.ID, `{"content":"A"}`)
	if created.WarnedAt != nil {
		t.Fatalf("warned_at = %v at creation, want null", created.WarnedAt)
	}
	itemPath := opinionPostItemPath(run.ID, created.ID)

	// 未预警 → 已预警：warned_at 被服务端设置。
	recorder := do(handler, http.MethodPut, itemPath, `{"warn_status":"已预警"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("warn transition: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	warned := decodeOpinionPost(t, recorder)
	if warned.WarnStatus != "已预警" || warned.WarnedAt == nil {
		t.Fatalf("warned = %+v, want 已预警 with a server-set warned_at", warned)
	}
	warnedAt := *warned.WarnedAt

	// 同值 no-op：已预警 → 已预警 200，warned_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"warn_status":"已预警"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	again := decodeOpinionPost(t, recorder)
	if again.WarnStatus != "已预警" || again.WarnedAt == nil || *again.WarnedAt != warnedAt {
		t.Fatalf("no-op warned_at = %v, want the unchanged %v", again.WarnedAt, warnedAt)
	}

	// PUT 未涉及 warn_status：warned_at 保持原值。
	recorder = do(handler, http.MethodPut, itemPath, `{"content":"B"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("update without warn_status: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionPost(t, recorder); got.WarnedAt == nil || *got.WarnedAt != warnedAt {
		t.Fatalf("warned_at after unrelated update = %v, want %v", got.WarnedAt, warnedAt)
	}

	// 已预警 → 未预警：400。
	recorder = do(handler, http.MethodPut, itemPath, `{"warn_status":"未预警"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("backward transition: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 同值 未预警 no-op：200 且 warned_at 保持 null。
	pending := createOpinionPost(t, handler, run.ID, `{"content":"B"}`)
	recorder = do(handler, http.MethodPut, opinionPostItemPath(run.ID, pending.ID), `{"warn_status":"未预警"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("未预警 no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionPost(t, recorder); got.WarnStatus != "未预警" || got.WarnedAt != nil {
		t.Fatalf("pending no-op = %+v, want 未预警 with null warned_at", got)
	}
}

// ─── 失败路径（POST 与 PUT 双入口一致覆盖）───────────────────────────

// 缺 content（POST 必填；PUT 为部分更新语义允许缺省）、非法枚举、heat
// 越界/非整数、metadata 非 JSON 对象、空/畸形 body → 400，错误体统一
// { "error": ... }。
func TestOpinionPostInvalidBodyBothEntries(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := createOpinionPost(t, handler, run.ID, `{"content":"A"}`)
	itemPath := opinionPostItemPath(run.ID, created.ID)

	postBodies := map[string]string{
		"empty body":          "",
		"malformed JSON":      `{"content":`,
		"JSON string":         `"内容"`,
		"JSON array":          `[{"content":"A"}]`,
		"JSON null":           `null`,
		"missing content":     `{"source":"微博"}`,
		"empty content":       `{"content":""}`,
		"invalid source":      `{"content":"A","source":"微信"}`,
		"invalid sentiment":   `{"content":"A","sentiment":"消极"}`,
		"invalid warn_status": `{"content":"A","warn_status":"预警中"}`,
		"heat below range":    `{"content":"A","heat":-1}`,
		"heat above range":    `{"content":"A","heat":101}`,
		"heat fractional":     `{"content":"A","heat":1.5}`,
		"heat string":         `{"content":"A","heat":"10"}`,
		"heat boolean":        `{"content":"A","heat":true}`,
		"heat null":           `{"content":"A","heat":null}`,
		"metadata array":      `{"content":"A","metadata":[1]}`,
		"metadata string":     `{"content":"A","metadata":"x"}`,
		"metadata null":       `{"content":"A","metadata":null}`,
	}
	for name, body := range postBodies {
		recorder := do(handler, http.MethodPost, opinionPostsPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST %s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 双入口一致覆盖：除「缺 content」外，其余失败路径同样 400
	// （缺 content 是合法部分更新）。
	putBodies := map[string]string{
		"empty body":          "",
		"malformed JSON":      `{"content":`,
		"JSON null":           `null`,
		"invalid source":      `{"source":"微信"}`,
		"invalid sentiment":   `{"sentiment":"消极"}`,
		"invalid warn_status": `{"warn_status":"预警中"}`,
		"heat below range":    `{"heat":-1}`,
		"heat above range":    `{"heat":101}`,
		"heat fractional":     `{"heat":1.5}`,
		"heat string":         `{"heat":"10"}`,
		"heat null":           `{"heat":null}`,
		"metadata array":      `{"metadata":[1]}`,
		"metadata null":       `{"metadata":null}`,
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
func TestOpinionPostRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionPostsPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET list: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, opinionPostsPath(missing), `{"content":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("POST: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, opinionPostItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET item: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionPostItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"content":"A"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionPostItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 写门控：仅 run 进行中 可写——未开始/已完成/已终止 时 POST/PUT/DELETE
// 均 400；GET（列表与单条）不受门控。
func TestOpinionPostWriteGate(t *testing.T) {
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
		recorder := do(handler, http.MethodPost, opinionPostsPath(run.ID), `{"content":"A"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 先在进行中 run 上创建帖子，再结束 run：PUT/DELETE 均 400；
	// GET 不受门控（200）。
	locked := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, locked.ID)
	post := createOpinionPost(t, handler, locked.ID, `{"content":"A"}`)
	do(handler, http.MethodPost, runsPath+"/"+locked.ID+"/complete", "")
	itemPath := opinionPostItemPath(locked.ID, post.ID)

	recorder := do(handler, http.MethodPut, itemPath, `{"content":"B"}`)
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
	recorder = do(handler, http.MethodGet, opinionPostsPath(locked.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成: status = %d, want 200", recorder.Code)
	}
	if list := decodeOpinionPostList(t, recorder); list.Meta.Total != 1 {
		t.Fatalf("list on 已完成: total = %d, want 1", list.Meta.Total)
	}
	recorder = do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET item on 已完成: status = %d, want 200", recorder.Code)
	}
}

// ─── 级联：删除 run 后舆情信息清空 ───────────────────────────────────

// 创建帖子后 DELETE run（runs 路由），再 GET 帖子列表返回 404（run 已
// 不存在；内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesOpinionPosts(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	createOpinionPost(t, handler, run.ID, `{"content":"A"}`)
	createOpinionPost(t, handler, run.ID, `{"content":"B"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, opinionPostsPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET posts after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON：collection 路由 Allow 为 GET, POST；item
// 路由 Allow 为 GET, PUT, DELETE。
func TestOpinionPostMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	post := createOpinionPost(t, handler, run.ID, `{"content":"A"}`)

	// collection：PATCH/PUT → 405，Allow 含 GET 与 POST。
	for _, method := range []string{http.MethodPatch, http.MethodPut} {
		recorder := do(handler, method, opinionPostsPath(run.ID), `{}`)
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
		recorder := do(handler, method, opinionPostItemPath(run.ID, post.ID), `{}`)
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
func TestOpinionPostCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	target := opinionPostsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV")

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
