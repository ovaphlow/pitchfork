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

// opinionReleasesPath builds the opinion release collection path of a
// run.
func opinionReleasesPath(runID string) string {
	return fmt.Sprintf("%s/%s/releases", runsPath, runID)
}

// opinionReleaseItemPath builds the opinion release item path of a
// (run, release) pair.
func opinionReleaseItemPath(runID, releaseID string) string {
	return opinionReleasesPath(runID) + "/" + releaseID
}

// opinionReleaseJSON mirrors the opinion release response for
// assertions.
type opinionReleaseJSON struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	Channel     string         `json:"channel"`
	Title       string         `json:"title"`
	Content     string         `json:"content"`
	MediaName   string         `json:"media_name"`
	Status      string         `json:"status"`
	PublishedAt *string        `json:"published_at"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

type opinionReleaseListJSON struct {
	Records []opinionReleaseJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeOpinionRelease(t *testing.T, recorder *httptest.ResponseRecorder) opinionReleaseJSON {
	t.Helper()
	var release opinionReleaseJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &release); err != nil {
		t.Fatalf("body %q is not an opinion release JSON: %v", recorder.Body.String(), err)
	}
	return release
}

func decodeOpinionReleaseList(t *testing.T, recorder *httptest.ResponseRecorder) opinionReleaseListJSON {
	t.Helper()
	var list opinionReleaseListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createOpinionRelease posts the given body to the run's collection and
// asserts 201; returns the created release.
func createOpinionRelease(t *testing.T, handler http.Handler, runID, body string) opinionReleaseJSON {
	t.Helper()
	if body == "" {
		body = `{"title":"情况说明","content":"展厅秩序已恢复"}`
	}
	recorder := do(handler, http.MethodPost, opinionReleasesPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOpinionRelease(t, recorder)
}

// ─── POST /drills/{rid}/releases ─────────────────────────────────────

// 合法创建：201 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 回显为路径 {rid} 值（body 中出现的 run_id/id 被忽略），
// title/content 必填透传，channel 缺省 官网公告、status 缺省 草稿、
// media_name 缺省 ''、published_at null、metadata 缺省 {}、created_by
// 缺省 ''、created_at/updated_at 服务端时间且相等。
func TestCreateOpinionReleaseDefaults(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）；body 只
	// 给 title/content，其余全部缺省。
	recorder := do(handler, http.MethodPost, opinionReleasesPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID","title":"情况说明","content":"展厅秩序已恢复"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	release := decodeOpinionRelease(t, recorder)
	if !ulidPattern.MatchString(release.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", release.ID)
	}
	if release.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", release.RunID, run.ID)
	}
	if release.Title != "情况说明" || release.Content != "展厅秩序已恢复" {
		t.Fatalf("title/content = %q/%q, want the provided values", release.Title, release.Content)
	}
	if release.Channel != "官网公告" {
		t.Fatalf("channel = %q, want the default 官网公告", release.Channel)
	}
	if release.MediaName != "" {
		t.Fatalf("media_name = %q, want the empty default", release.MediaName)
	}
	if release.Status != "草稿" {
		t.Fatalf("status = %q, want the default 草稿", release.Status)
	}
	if release.PublishedAt != nil {
		t.Fatalf("published_at = %v, want null at creation", release.PublishedAt)
	}
	if release.Metadata == nil || len(release.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", release.Metadata)
	}
	if release.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", release.CreatedBy)
	}
	if release.CreatedAt == "" || release.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", release)
	}
	if release.CreatedAt != release.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", release.CreatedAt, release.UpdatedAt)
	}
}

// 显式字段原样回显：channel 四种枚举 / media_name / metadata / created_by
// 透传；title/content 必填。
func TestCreateOpinionReleasePassthrough(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	release := createOpinionRelease(t, handler, run.ID,
		`{"title":"通稿标题","content":"通稿正文","channel":"新闻媒体通稿","media_name":"新华社","metadata":{"platform":"press"},"created_by":"u-admin"}`)
	if release.Title != "通稿标题" || release.Content != "通稿正文" || release.Channel != "新闻媒体通稿" ||
		release.MediaName != "新华社" || release.Metadata["platform"] != "press" || release.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", release)
	}

	for _, channel := range []string{"官网公告", "微信公众号", "微博官方号", "新闻媒体通稿"} {
		got := createOpinionRelease(t, handler, run.ID, `{"title":"T","content":"C","channel":`+jsonString(channel)+`}`)
		if got.Channel != channel {
			t.Fatalf("channel %s: got %q", channel, got.Channel)
		}
	}
}

// 首次创建仅接受 草稿：显式 待审核/已发布/已撤回 → 400，错误体统一
// { "error": ... }。
func TestCreateOpinionReleaseRejectsExplicitNonDraft(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for _, status := range []string{"待审核", "已发布", "已撤回"} {
		recorder := do(handler, http.MethodPost, opinionReleasesPath(run.ID),
			`{"title":"T","content":"C","status":`+jsonString(status)+`}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("status %s: code = %d, want 400; body = %s", status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/releases（列表）──────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}。
func TestListOpinionReleasesEmpty(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, opinionReleasesPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeOpinionReleaseList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 {
		t.Fatalf("records = %#v, want an empty array", list.Records)
	}
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 排序 created_at DESC（最新发布在前）可断言：依次创建三条（间隔 sleep
// 保证毫秒级时间可区分），列表按创建逆序返回。
func TestListOpinionReleasesSortedNewestFirst(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	first := createOpinionRelease(t, handler, run.ID, `{"title":"第一条","content":"C1"}`)
	time.Sleep(5 * time.Millisecond)
	second := createOpinionRelease(t, handler, run.ID, `{"title":"第二条","content":"C2"}`)
	time.Sleep(5 * time.Millisecond)
	third := createOpinionRelease(t, handler, run.ID, `{"title":"第三条","content":"C3"}`)

	list := decodeOpinionReleaseList(t, do(handler, http.MethodGet, opinionReleasesPath(run.ID), ""))
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", list.Meta.Total, len(list.Records))
	}
	wantOrder := []opinionReleaseJSON{third, second, first}
	for i, want := range wantOrder {
		if list.Records[i].ID != want.ID {
			t.Fatalf("records[%d] = %q, want %q (created_at DESC)", i, list.Records[i].ID, want.ID)
		}
		if i > 0 && list.Records[i-1].CreatedAt < list.Records[i].CreatedAt {
			t.Fatalf("created_at not descending: %s then %s", list.Records[i-1].CreatedAt, list.Records[i].CreatedAt)
		}
	}
}

// 筛选 channel/status 生效（可单参、可组合）；非法筛选值 400（两个筛选参
// 数均覆盖）；limit/offset 分页生效。
func TestListOpinionReleasesFilterAndPagination(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	createOpinionRelease(t, handler, run.ID, `{"title":"官网稿","content":"C","channel":"官网公告"}`)
	createOpinionRelease(t, handler, run.ID, `{"title":"媒体稿","content":"C","channel":"新闻媒体通稿","media_name":"新华社"}`)
	createOpinionRelease(t, handler, run.ID, `{"title":"公众号稿","content":"C","channel":"微信公众号"}`)

	// 单一筛选 channel。
	filtered := decodeOpinionReleaseList(t, do(handler, http.MethodGet,
		opinionReleasesPath(run.ID)+"?channel=新闻媒体通稿", ""))
	if filtered.Meta.Total != 1 || len(filtered.Records) != 1 || filtered.Records[0].Title != "媒体稿" {
		t.Fatalf("channel filter = %+v, want the single 新闻媒体通稿 release", filtered)
	}

	// 单一筛选 status：先把「官网稿」推进到 待审核。
	list := decodeOpinionReleaseList(t, do(handler, http.MethodGet,
		opinionReleasesPath(run.ID)+"?channel=官网公告", ""))
	recorder := do(handler, http.MethodPut, opinionReleaseItemPath(run.ID, list.Records[0].ID),
		`{"title":"官网稿","content":"C","status":"待审核"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	filtered = decodeOpinionReleaseList(t, do(handler, http.MethodGet,
		opinionReleasesPath(run.ID)+"?status=待审核", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Title != "官网稿" {
		t.Fatalf("status filter = %+v, want the single 待审核 release", filtered)
	}

	// 组合筛选：channel=新闻媒体通稿 且 status=待审核 → 空。
	filtered = decodeOpinionReleaseList(t, do(handler, http.MethodGet,
		opinionReleasesPath(run.ID)+"?channel=新闻媒体通稿&status=待审核", ""))
	if filtered.Meta.Total != 0 || len(filtered.Records) != 0 {
		t.Fatalf("combined filter = %+v, want empty", filtered)
	}

	// 筛选与分页组合：channel=官网公告 两条（官网稿、另一官网稿），按创建
	// 逆序为 [另一官网稿, 官网稿]；limit=1 offset=1 → 取第二条（官网稿），
	// total 保持 2。
	time.Sleep(5 * time.Millisecond)
	createOpinionRelease(t, handler, run.ID, `{"title":"另一官网稿","content":"C","channel":"官网公告"}`)
	page := decodeOpinionReleaseList(t, do(handler, http.MethodGet,
		opinionReleasesPath(run.ID)+"?channel=官网公告&limit=1&offset=1", ""))
	if page.Meta.Total != 2 || len(page.Records) != 1 {
		t.Fatalf("combined page = %+v, want total 2 and 1 record", page)
	}
	if page.Records[0].Title != "官网稿" {
		t.Fatalf("combined page record = %q, want 官网稿 (newest-first page 2)", page.Records[0].Title)
	}

	// 非法筛选值与分页参数 → 400，错误体统一。
	for name, query := range map[string]string{
		"invalid channel": "?channel=电视台",
		"invalid status":  "?status=审核中",
		"invalid limit":   "?limit=abc",
		"negative limit":  "?limit=-1",
		"invalid offset":  "?offset=1.5",
		"negative offset": "?offset=-1",
	} {
		recorder := do(handler, http.MethodGet, opinionReleasesPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET/PUT/DELETE /drills/{rid}/releases/{lid} ─────────────────────

// GET 200 返回完整对象；PUT 200 返回更新后对象（部分更新：缺省字段保持
// 原值）；PUT 后 GET 反映更新；DELETE 204；DELETE 后 GET 404。
func TestGetPutDeleteOpinionRelease(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionRelease(t, handler, run.ID,
		`{"title":"原标题","content":"原正文","channel":"新闻媒体通稿","media_name":"新华社","metadata":{"k":"v"},"created_by":"u-admin"}`)
	itemPath := opinionReleaseItemPath(run.ID, created.ID)

	// GET 200。
	recorder := do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeOpinionRelease(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.Title != "原标题" || fetched.Channel != "新闻媒体通稿" {
		t.Fatalf("GET = %+v, want the created release", fetched)
	}

	// PUT 部分更新：title/content 必填，只改 title/content，其余保持。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"新标题","content":"新正文"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeOpinionRelease(t, recorder)
	if updated.Title != "新标题" || updated.Content != "新正文" || updated.Channel != "新闻媒体通稿" ||
		updated.MediaName != "新华社" || updated.Status != "草稿" || updated.Metadata["k"] != "v" ||
		updated.CreatedBy != "u-admin" || updated.ID != created.ID {
		t.Fatalf("partial update = %+v, want title/content changed and the rest kept", updated)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must be refreshed on PUT")
	}

	// PUT 后 GET 反映更新。
	fetched = decodeOpinionRelease(t, do(handler, http.MethodGet, itemPath, ""))
	if fetched.Title != "新标题" || fetched.Channel != "新闻媒体通稿" {
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

// 不存在 lid：GET/PUT/DELETE 均 404，错误体统一 { "error": ... }。
func TestOpinionReleaseItemNotFound(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionReleaseItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionReleaseItemPath(run.ID, missing), `{"title":"T","content":"C"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionReleaseItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 发布状态机 ──────────────────────────────────────────────────────

// 草稿→待审核→已发布→已撤回 仅相邻迁移：置 已发布 时服务端设
// published_at；同值 no-op 合法且 published_at 保持不变（不重置）；置
// 已撤回 时 published_at 重置 null；跳级/回退（含已发布→待审核、已撤回
// 改回）400；PUT 未涉及 status 时 published_at 保持原值；创建时
// published_at 为 null。
func TestOpinionReleaseStatusStateMachine(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionRelease(t, handler, run.ID, `{"title":"T","content":"C"}`)
	if created.PublishedAt != nil {
		t.Fatalf("published_at = %v at creation, want null", created.PublishedAt)
	}
	itemPath := opinionReleaseItemPath(run.ID, created.ID)

	// 跳级 草稿 → 已发布：400。
	recorder := do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":"已发布"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("skip transition: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 草稿 → 待审核：published_at 保持 null。
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":"待审核"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("draft -> pending: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	pending := decodeOpinionRelease(t, recorder)
	if pending.Status != "待审核" || pending.PublishedAt != nil {
		t.Fatalf("pending = %+v, want 待审核 with null published_at", pending)
	}

	// 待审核 → 已发布：published_at 被服务端设置。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":"已发布"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("pending -> published: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	published := decodeOpinionRelease(t, recorder)
	if published.Status != "已发布" || published.PublishedAt == nil {
		t.Fatalf("published = %+v, want 已发布 with a server-set published_at", published)
	}
	publishedAt := *published.PublishedAt

	// 同值 no-op：已发布 → 已发布 200，published_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":"已发布"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	again := decodeOpinionRelease(t, recorder)
	if again.Status != "已发布" || again.PublishedAt == nil || *again.PublishedAt != publishedAt {
		t.Fatalf("no-op published_at = %v, want the unchanged %v", again.PublishedAt, publishedAt)
	}

	// PUT 未涉及 status：published_at 保持原值。
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"新标题","content":"新正文"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("update without status: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionRelease(t, recorder); got.PublishedAt == nil || *got.PublishedAt != publishedAt {
		t.Fatalf("published_at after unrelated update = %v, want %v", got.PublishedAt, publishedAt)
	}

	// 回退 已发布 → 待审核：400。
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":"待审核"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("backward transition: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 已发布 → 已撤回：合法，published_at 重置 null。
	recorder = do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":"已撤回"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("published -> withdrawn: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	withdrawn := decodeOpinionRelease(t, recorder)
	if withdrawn.Status != "已撤回" || withdrawn.PublishedAt != nil {
		t.Fatalf("withdrawn = %+v, want 已撤回 with null published_at", withdrawn)
	}

	// 已撤回改回：400。
	for _, status := range []string{"草稿", "待审核", "已发布"} {
		recorder = do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C","status":`+jsonString(status)+`}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("away from 已撤回 (%s): status = %d, want 400", status, recorder.Code)
		}
		decodeError(t, recorder)
	}

	// 同值 草稿 no-op：200 且 published_at 保持 null。
	draft := createOpinionRelease(t, handler, run.ID, `{"title":"T2","content":"C2"}`)
	recorder = do(handler, http.MethodPut, opinionReleaseItemPath(run.ID, draft.ID), `{"title":"T2","content":"C2","status":"草稿"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("草稿 no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionRelease(t, recorder); got.Status != "草稿" || got.PublishedAt != nil {
		t.Fatalf("draft no-op = %+v, want 草稿 with null published_at", got)
	}
}

// ─── 失败路径（POST 与 PUT 双入口一致覆盖）───────────────────────────

// 缺 title/content（POST 与 PUT 双入口均必填）、非法 channel/status（双
// 入口）、metadata 非 JSON 对象、空/畸形 body → 400，错误体统一
// { "error": ... }。
func TestOpinionReleaseInvalidBodyBothEntries(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := createOpinionRelease(t, handler, run.ID, `{"title":"T","content":"C"}`)
	itemPath := opinionReleaseItemPath(run.ID, created.ID)

	postBodies := map[string]string{
		"empty body":          "",
		"malformed JSON":      `{"title":`,
		"JSON string":         `"标题"`,
		"JSON array":          `[{"title":"T"}]`,
		"JSON null":           `null`,
		"missing title":       `{"content":"C"}`,
		"empty title":         `{"title":"","content":"C"}`,
		"missing content":     `{"title":"T"}`,
		"empty content":       `{"title":"T","content":""}`,
		"invalid channel":     `{"title":"T","content":"C","channel":"电视台"}`,
		"invalid status":      `{"title":"T","content":"C","status":"审核中"}`,
		"metadata array":      `{"title":"T","content":"C","metadata":[1]}`,
		"metadata string":     `{"title":"T","content":"C","metadata":"x"}`,
		"metadata null":       `{"title":"T","content":"C","metadata":null}`,
	}
	for name, body := range postBodies {
		recorder := do(handler, http.MethodPost, opinionReleasesPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST %s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 双入口一致覆盖：缺 title/content 同样 400（releases 的
	// title/content 在 PUT 上也是必填）。
	putBodies := map[string]string{
		"empty body":      "",
		"malformed JSON":  `{"title":`,
		"JSON null":       `null`,
		"missing title":   `{"content":"C"}`,
		"empty title":     `{"title":"","content":"C"}`,
		"missing content": `{"title":"T"}`,
		"empty content":   `{"title":"T","content":""}`,
		"invalid channel": `{"title":"T","content":"C","channel":"电视台"}`,
		"invalid status":  `{"title":"T","content":"C","status":"审核中"}`,
		"metadata array":  `{"title":"T","content":"C","metadata":[1]}`,
		"metadata null":   `{"title":"T","content":"C","metadata":null}`,
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
func TestOpinionReleaseRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionReleasesPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET list: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, opinionReleasesPath(missing), `{"title":"T","content":"C"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("POST: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, opinionReleaseItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET item: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionReleaseItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"title":"T","content":"C"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionReleaseItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 写门控：仅 run 进行中 可写——未开始/已完成/已终止 时 POST/PUT/DELETE
// 均 400；GET（列表与单条）不受门控。
func TestOpinionReleaseWriteGate(t *testing.T) {
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
		recorder := do(handler, http.MethodPost, opinionReleasesPath(run.ID), `{"title":"T","content":"C"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 先在进行中 run 上创建发布记录，再结束 run：PUT/DELETE 均 400；
	// GET 不受门控（200）。
	locked := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, locked.ID)
	release := createOpinionRelease(t, handler, locked.ID, `{"title":"T","content":"C"}`)
	do(handler, http.MethodPost, runsPath+"/"+locked.ID+"/complete", "")
	itemPath := opinionReleaseItemPath(locked.ID, release.ID)

	recorder := do(handler, http.MethodPut, itemPath, `{"title":"T","content":"C"}`)
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
	recorder = do(handler, http.MethodGet, opinionReleasesPath(locked.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成: status = %d, want 200", recorder.Code)
	}
	if list := decodeOpinionReleaseList(t, recorder); list.Meta.Total != 1 {
		t.Fatalf("list on 已完成: total = %d, want 1", list.Meta.Total)
	}
	recorder = do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET item on 已完成: status = %d, want 200", recorder.Code)
	}
}

// ─── 级联：删除 run 后发布记录清空 ───────────────────────────────────

// 创建发布记录后 DELETE run（runs 路由），再 GET 发布记录列表返回 404
// （run 已不存在；内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesOpinionReleases(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	createOpinionRelease(t, handler, run.ID, `{"title":"A","content":"C1"}`)
	createOpinionRelease(t, handler, run.ID, `{"title":"B","content":"C2"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, opinionReleasesPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET releases after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON：collection 路由 Allow 为 GET, POST；item
// 路由 Allow 为 GET, PUT, DELETE。
func TestOpinionReleaseMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	release := createOpinionRelease(t, handler, run.ID, `{"title":"T","content":"C"}`)

	// collection：PATCH/PUT → 405，Allow 含 GET 与 POST。
	for _, method := range []string{http.MethodPatch, http.MethodPut} {
		recorder := do(handler, method, opinionReleasesPath(run.ID), `{}`)
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
		recorder := do(handler, method, opinionReleaseItemPath(run.ID, release.ID), `{}`)
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
// DELETE（写方法全覆盖），ACAO 回显；collection 与 item 两路由均覆盖。
func TestOpinionReleaseCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		opinionReleasesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
		opinionReleaseItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAV"),
	} {
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
}
