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

// opinionComplaintsPath builds the opinion complaint collection path of
// a run.
func opinionComplaintsPath(runID string) string {
	return fmt.Sprintf("%s/%s/complaints", runsPath, runID)
}

// opinionComplaintItemPath builds the opinion complaint item path of a
// (run, complaint) pair.
func opinionComplaintItemPath(runID, complaintID string) string {
	return opinionComplaintsPath(runID) + "/" + complaintID
}

// opinionComplaintJSON mirrors the opinion complaint response for
// assertions.
type opinionComplaintJSON struct {
	ID            string         `json:"id"`
	RunID         string         `json:"run_id"`
	Complainant   string         `json:"complainant"`
	Channel       string         `json:"channel"`
	ComplaintType string         `json:"complaint_type"`
	Content       string         `json:"content"`
	Status        string         `json:"status"`
	Handling      string         `json:"handling"`
	Handler       string         `json:"handler"`
	ClosedAt      *string        `json:"closed_at"`
	Metadata      map[string]any `json:"metadata"`
	CreatedBy     string         `json:"created_by"`
	CreatedAt     string         `json:"created_at"`
	UpdatedAt     string         `json:"updated_at"`
}

type opinionComplaintListJSON struct {
	Records []opinionComplaintJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeOpinionComplaint(t *testing.T, recorder *httptest.ResponseRecorder) opinionComplaintJSON {
	t.Helper()
	var complaint opinionComplaintJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &complaint); err != nil {
		t.Fatalf("body %q is not an opinion complaint JSON: %v", recorder.Body.String(), err)
	}
	return complaint
}

func decodeOpinionComplaintList(t *testing.T, recorder *httptest.ResponseRecorder) opinionComplaintListJSON {
	t.Helper()
	var list opinionComplaintListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createOpinionComplaint posts the given body to the run's collection
// and asserts 201; returns the created complaint.
func createOpinionComplaint(t *testing.T, handler http.Handler, runID, body string) opinionComplaintJSON {
	t.Helper()
	if body == "" {
		body = `{"complainant":"观众甲","content":"入馆排队受阻"}`
	}
	recorder := do(handler, http.MethodPost, opinionComplaintsPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOpinionComplaint(t, recorder)
}

// ─── POST /drills/{rid}/complaints ───────────────────────────────────

// 合法创建：201 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 自动设置为路径 rid 并回显（body 中出现的 run_id/id 被忽
// 略），complainant/content 必填透传，channel 缺省 现场、complaint_type
// 缺省 入馆受阻、status 缺省 待受理、handling/handler 缺省 ”、closed_at
// null、metadata 缺省 {}、created_by 缺省 ”、created_at/updated_at 服
// 务端时间且相等。
func TestCreateOpinionComplaintDefaults(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）；body 只
	// 给 complainant/content，其余全部缺省。
	recorder := do(handler, http.MethodPost, opinionComplaintsPath(run.ID),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID","complainant":"观众甲","content":"入馆排队受阻"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	complaint := decodeOpinionComplaint(t, recorder)
	if !ulidPattern.MatchString(complaint.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", complaint.ID)
	}
	if complaint.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", complaint.RunID, run.ID)
	}
	if complaint.Complainant != "观众甲" || complaint.Content != "入馆排队受阻" {
		t.Fatalf("complainant/content = %q/%q, want the provided values", complaint.Complainant, complaint.Content)
	}
	if complaint.Channel != "现场" {
		t.Fatalf("channel = %q, want the default 现场", complaint.Channel)
	}
	if complaint.ComplaintType != "入馆受阻" {
		t.Fatalf("complaint_type = %q, want the default 入馆受阻", complaint.ComplaintType)
	}
	if complaint.Status != "待受理" {
		t.Fatalf("status = %q, want the default 待受理", complaint.Status)
	}
	if complaint.Handling != "" || complaint.Handler != "" {
		t.Fatalf("handling/handler = %q/%q, want the empty defaults", complaint.Handling, complaint.Handler)
	}
	if complaint.ClosedAt != nil {
		t.Fatalf("closed_at = %v, want null at creation", complaint.ClosedAt)
	}
	if complaint.Metadata == nil || len(complaint.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", complaint.Metadata)
	}
	if complaint.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", complaint.CreatedBy)
	}
	if complaint.CreatedAt == "" || complaint.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", complaint)
	}
	if complaint.CreatedAt != complaint.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", complaint.CreatedAt, complaint.UpdatedAt)
	}
}

// 显式字段原样回显：channel 五种枚举 / complaint_type 五种枚举 /
// handling / handler / metadata / created_by 透传；complainant/content
// 必填。
func TestCreateOpinionComplaintPassthrough(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	complaint := createOpinionComplaint(t, handler, run.ID,
		`{"complainant":"观众乙","channel":"12345转办","complaint_type":"参观受限","content":"展区拥挤无法参观","handling":"安排专人疏导","handler":"值班员小李","metadata":{"platform":"hotline"},"created_by":"u-admin"}`)
	if complaint.Complainant != "观众乙" || complaint.Channel != "12345转办" ||
		complaint.ComplaintType != "参观受限" || complaint.Content != "展区拥挤无法参观" ||
		complaint.Handling != "安排专人疏导" || complaint.Handler != "值班员小李" ||
		complaint.Metadata["platform"] != "hotline" || complaint.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", complaint)
	}

	for _, channel := range []string{"现场", "电话", "网络留言", "12345转办", "其他"} {
		got := createOpinionComplaint(t, handler, run.ID,
			`{"complainant":"A","content":"C","channel":`+jsonString(channel)+`}`)
		if got.Channel != channel {
			t.Fatalf("channel %s: got %q", channel, got.Channel)
		}
	}
	for _, complaintType := range []string{"入馆受阻", "参观受限", "服务态度", "设施问题", "其他"} {
		got := createOpinionComplaint(t, handler, run.ID,
			`{"complainant":"A","content":"C","complaint_type":`+jsonString(complaintType)+`}`)
		if got.ComplaintType != complaintType {
			t.Fatalf("complaint_type %s: got %q", complaintType, got.ComplaintType)
		}
	}
}

// 首次创建仅接受 待受理：显式 处理中/已办结 → 400，错误体统一
// { "error": ... }。
func TestCreateOpinionComplaintRejectsExplicitNonPending(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for _, status := range []string{"处理中", "已办结"} {
		recorder := do(handler, http.MethodPost, opinionComplaintsPath(run.ID),
			`{"complainant":"观众甲","content":"C","status":`+jsonString(status)+`}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("status %s: code = %d, want 400; body = %s", status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/complaints（列表）─────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}。
func TestListOpinionComplaintsEmpty(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, opinionComplaintsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeOpinionComplaintList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 {
		t.Fatalf("records = %#v, want an empty array", list.Records)
	}
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 排序 created_at ASC, id ASC（受理顺序）可断言：依次创建三条（间隔 sleep
// 保证毫秒级时间可区分），列表按创建正序返回。
func TestListOpinionComplaintsSortedIntakeOrder(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	first := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众甲","content":"第一条"}`)
	time.Sleep(5 * time.Millisecond)
	second := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众乙","content":"第二条"}`)
	time.Sleep(5 * time.Millisecond)
	third := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众丙","content":"第三条"}`)

	list := decodeOpinionComplaintList(t, do(handler, http.MethodGet, opinionComplaintsPath(run.ID), ""))
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", list.Meta.Total, len(list.Records))
	}
	wantOrder := []opinionComplaintJSON{first, second, third}
	for i, want := range wantOrder {
		if list.Records[i].ID != want.ID {
			t.Fatalf("records[%d] = %q, want %q (created_at ASC)", i, list.Records[i].ID, want.ID)
		}
		if i > 0 && list.Records[i-1].CreatedAt > list.Records[i].CreatedAt {
			t.Fatalf("created_at not ascending: %s then %s", list.Records[i-1].CreatedAt, list.Records[i].CreatedAt)
		}
	}
}

// 筛选 channel/complaint_type/status 生效（可单参、可组合）；非法筛选值
// 400（三个筛选参数均覆盖）；limit/offset 分页生效且非空列表 records 长度
// 与 meta.total 一致。
func TestListOpinionComplaintsFilterAndPagination(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众甲","content":"C1","channel":"现场","complaint_type":"入馆受阻"}`)
	createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众乙","content":"C2","channel":"电话","complaint_type":"服务态度"}`)
	createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众丙","content":"C3","channel":"网络留言","complaint_type":"参观受限"}`)

	// 单一筛选 channel。
	filtered := decodeOpinionComplaintList(t, do(handler, http.MethodGet,
		opinionComplaintsPath(run.ID)+"?channel=电话", ""))
	if filtered.Meta.Total != 1 || len(filtered.Records) != 1 || filtered.Records[0].Complainant != "观众乙" {
		t.Fatalf("channel filter = %+v, want the single 电话 complaint", filtered)
	}

	// 单一筛选 complaint_type。
	filtered = decodeOpinionComplaintList(t, do(handler, http.MethodGet,
		opinionComplaintsPath(run.ID)+"?complaint_type=参观受限", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Complainant != "观众丙" {
		t.Fatalf("complaint_type filter = %+v, want the single 参观受限 complaint", filtered)
	}

	// 单一筛选 status：先把「观众甲」推进到 处理中。
	recorder := do(handler, http.MethodPut, opinionComplaintItemPath(run.ID, filtered.Records[0].ID),
		`{"complainant":"观众丙","content":"C3","status":"待受理"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT no-op status: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeOpinionComplaintList(t, do(handler, http.MethodGet, opinionComplaintsPath(run.ID)+"?channel=现场", ""))
	recorder = do(handler, http.MethodPut, opinionComplaintItemPath(run.ID, list.Records[0].ID),
		`{"complainant":"观众甲","content":"C1","status":"处理中","handling":"安抚并引导","handler":"值班员小李"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT to 处理中: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	filtered = decodeOpinionComplaintList(t, do(handler, http.MethodGet,
		opinionComplaintsPath(run.ID)+"?status=处理中", ""))
	if filtered.Meta.Total != 1 || filtered.Records[0].Complainant != "观众甲" {
		t.Fatalf("status filter = %+v, want the single 处理中 complaint", filtered)
	}

	// 组合筛选：channel=电话 且 status=处理中 → 空。
	filtered = decodeOpinionComplaintList(t, do(handler, http.MethodGet,
		opinionComplaintsPath(run.ID)+"?channel=电话&status=处理中", ""))
	if filtered.Meta.Total != 0 || len(filtered.Records) != 0 {
		t.Fatalf("combined filter = %+v, want empty", filtered)
	}

	// 筛选与分页组合：channel=现场 两条（现场甲、另一现场稿），按创建正序
	// 为 [现场甲, 另一现场稿]；limit=1 offset=1 → 取第二条（另一现场稿），
	// total 保持 2 且 records 长度与 total 一致口径可断言。
	time.Sleep(5 * time.Millisecond)
	createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众丁","content":"另一现场稿","channel":"现场"}`)
	page := decodeOpinionComplaintList(t, do(handler, http.MethodGet,
		opinionComplaintsPath(run.ID)+"?channel=现场&limit=1&offset=1", ""))
	if page.Meta.Total != 2 || len(page.Records) != 1 {
		t.Fatalf("combined page = %+v, want total 2 and 1 record", page)
	}
	if page.Records[0].Complainant != "观众丁" {
		t.Fatalf("combined page record = %q, want 观众丁 (intake-order page 2)", page.Records[0].Complainant)
	}

	// 非法筛选值与分页参数 → 400，错误体统一。
	for name, query := range map[string]string{
		"invalid channel":        "?channel=邮件",
		"invalid complaint_type": "?complaint_type=门票",
		"invalid status":         "?status=已受理",
		"invalid limit":          "?limit=abc",
		"negative limit":         "?limit=-1",
		"invalid offset":         "?offset=1.5",
		"negative offset":        "?offset=-1",
	} {
		recorder := do(handler, http.MethodGet, opinionComplaintsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET/PUT/DELETE /drills/{rid}/complaints/{cid} ───────────────────

// GET 200 返回完整对象；PUT 200 返回更新后对象（部分更新：缺省字段保持
// 原值）；PUT 后 GET 反映更新；DELETE 204；DELETE 后 GET 404。
func TestGetPutDeleteOpinionComplaint(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionComplaint(t, handler, run.ID,
		`{"complainant":"观众甲","content":"原内容","channel":"电话","complaint_type":"服务态度","handling":"安抚","handler":"值班员小李","metadata":{"k":"v"},"created_by":"u-admin"}`)
	itemPath := opinionComplaintItemPath(run.ID, created.ID)

	// GET 200。
	recorder := do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeOpinionComplaint(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.Complainant != "观众甲" || fetched.Channel != "电话" {
		t.Fatalf("GET = %+v, want the created complaint", fetched)
	}

	// PUT 部分更新：complainant/content 必填，只改 complainant/content，
	// 其余保持。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"complainant":"观众甲（更正）","content":"新内容"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeOpinionComplaint(t, recorder)
	if updated.Complainant != "观众甲（更正）" || updated.Content != "新内容" || updated.Channel != "电话" ||
		updated.ComplaintType != "服务态度" || updated.Status != "待受理" || updated.Handling != "安抚" ||
		updated.Handler != "值班员小李" || updated.Metadata["k"] != "v" ||
		updated.CreatedBy != "u-admin" || updated.ID != created.ID {
		t.Fatalf("partial update = %+v, want complainant/content changed and the rest kept", updated)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must be refreshed on PUT")
	}

	// PUT 后 GET 反映更新。
	fetched = decodeOpinionComplaint(t, do(handler, http.MethodGet, itemPath, ""))
	if fetched.Complainant != "观众甲（更正）" || fetched.Channel != "电话" {
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

// 不存在 cid：GET/PUT/DELETE 均 404，错误体统一 { "error": ... }。
func TestOpinionComplaintItemNotFound(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionComplaintItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionComplaintItemPath(run.ID, missing), `{"complainant":"A","content":"C"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionComplaintItemPath(run.ID, missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 处理状态机 ──────────────────────────────────────────────────────

// 待受理→处理中→已办结 仅相邻迁移：置 已办结 时服务端设 closed_at；
// 同值 no-op 合法且 closed_at 保持不变（不重置）；其余状态 closed_at 为
// null；跳级/回退（含已办结→处理中）400；PUT 未涉及 status（仅改
// handling/handler/content 等业务字段）时 closed_at 保持原值；创建时
// closed_at 为 null。
func TestOpinionComplaintStatusStateMachine(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	created := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众甲","content":"C"}`)
	if created.ClosedAt != nil {
		t.Fatalf("closed_at = %v at creation, want null", created.ClosedAt)
	}
	itemPath := opinionComplaintItemPath(run.ID, created.ID)

	// 跳级 待受理 → 已办结：400。
	recorder := do(handler, http.MethodPut, itemPath, `{"complainant":"观众甲","content":"C","status":"已办结"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("skip transition: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 待受理 → 处理中：closed_at 保持 null，handling/handler 一并留痕。
	recorder = do(handler, http.MethodPut, itemPath,
		`{"complainant":"观众甲","content":"C","status":"处理中","handling":"安抚并引导至人工通道","handler":"值班员小李"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("pending -> processing: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	processing := decodeOpinionComplaint(t, recorder)
	if processing.Status != "处理中" || processing.ClosedAt != nil {
		t.Fatalf("processing = %+v, want 处理中 with null closed_at", processing)
	}
	if processing.Handling != "安抚并引导至人工通道" || processing.Handler != "值班员小李" {
		t.Fatalf("handling/handler not applied: %+v", processing)
	}

	// 处理中 → 已办结：closed_at 被服务端设置。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"complainant":"观众甲","content":"C","status":"已办结"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("processing -> closed: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	closed := decodeOpinionComplaint(t, recorder)
	if closed.Status != "已办结" || closed.ClosedAt == nil {
		t.Fatalf("closed = %+v, want 已办结 with a server-set closed_at", closed)
	}
	closedAt := *closed.ClosedAt

	// 同值 no-op：已办结 → 已办结 200，closed_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	recorder = do(handler, http.MethodPut, itemPath, `{"complainant":"观众甲","content":"C","status":"已办结"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	again := decodeOpinionComplaint(t, recorder)
	if again.Status != "已办结" || again.ClosedAt == nil || *again.ClosedAt != closedAt {
		t.Fatalf("no-op closed_at = %v, want the unchanged %v", again.ClosedAt, closedAt)
	}

	// PUT 未涉及 status（仅改 handling/handler/content 等业务字段）：
	// closed_at 保持原值。
	recorder = do(handler, http.MethodPut, itemPath,
		`{"complainant":"观众甲","content":"已办结后的补充说明","handling":"回访确认满意","handler":"值班员小王"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("update without status: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	businessOnly := decodeOpinionComplaint(t, recorder)
	if businessOnly.ClosedAt == nil || *businessOnly.ClosedAt != closedAt {
		t.Fatalf("closed_at after business-only update = %v, want %v", businessOnly.ClosedAt, closedAt)
	}
	if businessOnly.Content != "已办结后的补充说明" || businessOnly.Handling != "回访确认满意" || businessOnly.Handler != "值班员小王" {
		t.Fatalf("business fields not applied: %+v", businessOnly)
	}

	// 回退 已办结 → 处理中 / 待受理：400。
	for _, status := range []string{"处理中", "待受理"} {
		recorder = do(handler, http.MethodPut, itemPath, `{"complainant":"观众甲","content":"C","status":`+jsonString(status)+`}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("backward transition (%s): status = %d, want 400", status, recorder.Code)
		}
		decodeError(t, recorder)
	}

	// 同值 待受理 no-op：200 且 closed_at 保持 null。
	pending := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众乙","content":"C2"}`)
	recorder = do(handler, http.MethodPut, opinionComplaintItemPath(run.ID, pending.ID), `{"complainant":"观众乙","content":"C2","status":"待受理"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("待受理 no-op: status = %d; body = %s", recorder.Code, recorder.Body.String())
	}
	if got := decodeOpinionComplaint(t, recorder); got.Status != "待受理" || got.ClosedAt != nil {
		t.Fatalf("pending no-op = %+v, want 待受理 with null closed_at", got)
	}
}

// ─── 失败路径（POST 与 PUT 双入口一致覆盖）───────────────────────────

// 缺 complainant/content（POST 与 PUT 双入口均必填）、非法
// channel/complaint_type/status（双入口）、metadata 非 JSON 对象、空/畸
// 形 body → 400，错误体统一 { "error": ... }。
func TestOpinionComplaintInvalidBodyBothEntries(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众甲","content":"C"}`)
	itemPath := opinionComplaintItemPath(run.ID, created.ID)

	postBodies := map[string]string{
		"empty body":             "",
		"malformed JSON":         `{"complainant":`,
		"JSON string":            `"观众甲"`,
		"JSON array":             `[{"complainant":"A"}]`,
		"JSON null":              `null`,
		"missing complainant":    `{"content":"C"}`,
		"empty complainant":      `{"complainant":"","content":"C"}`,
		"missing content":        `{"complainant":"A"}`,
		"empty content":          `{"complainant":"A","content":""}`,
		"invalid channel":        `{"complainant":"A","content":"C","channel":"邮件"}`,
		"invalid complaint_type": `{"complainant":"A","content":"C","complaint_type":"门票"}`,
		"invalid status":         `{"complainant":"A","content":"C","status":"已受理"}`,
		"metadata array":         `{"complainant":"A","content":"C","metadata":[1]}`,
		"metadata string":        `{"complainant":"A","content":"C","metadata":"x"}`,
		"metadata null":          `{"complainant":"A","content":"C","metadata":null}`,
	}
	for name, body := range postBodies {
		recorder := do(handler, http.MethodPost, opinionComplaintsPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST %s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// PUT 双入口一致覆盖：缺 complainant/content 同样 400。
	putBodies := map[string]string{
		"empty body":             "",
		"malformed JSON":         `{"complainant":`,
		"JSON null":              `null`,
		"missing complainant":    `{"content":"C"}`,
		"empty complainant":      `{"complainant":"","content":"C"}`,
		"missing content":        `{"complainant":"A"}`,
		"empty content":          `{"complainant":"A","content":""}`,
		"invalid channel":        `{"complainant":"A","content":"C","channel":"邮件"}`,
		"invalid complaint_type": `{"complainant":"A","content":"C","complaint_type":"门票"}`,
		"invalid status":         `{"complainant":"A","content":"C","status":"已受理"}`,
		"metadata array":         `{"complainant":"A","content":"C","metadata":[1]}`,
		"metadata null":          `{"complainant":"A","content":"C","metadata":null}`,
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
func TestOpinionComplaintRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodGet, opinionComplaintsPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET list: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, opinionComplaintsPath(missing), `{"complainant":"A","content":"C"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("POST: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, opinionComplaintItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET item: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPut, opinionComplaintItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"complainant":"A","content":"C"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, opinionComplaintItemPath(missing, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 写门控：仅 run 进行中 可写——未开始/已完成/已终止 时 POST/PUT/DELETE
// 均 400；GET（列表与单条）不受门控。
func TestOpinionComplaintWriteGate(t *testing.T) {
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
		recorder := do(handler, http.MethodPost, opinionComplaintsPath(run.ID), `{"complainant":"A","content":"C"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("POST on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 先在进行中 run 上创建投诉单，再结束 run：PUT/DELETE 均 400；
	// GET 不受门控（200）。
	locked := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, locked.ID)
	complaint := createOpinionComplaint(t, handler, locked.ID, `{"complainant":"观众甲","content":"C"}`)
	do(handler, http.MethodPost, runsPath+"/"+locked.ID+"/complete", "")
	itemPath := opinionComplaintItemPath(locked.ID, complaint.ID)

	recorder := do(handler, http.MethodPut, itemPath, `{"complainant":"观众甲","content":"C"}`)
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
	recorder = do(handler, http.MethodGet, opinionComplaintsPath(locked.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成: status = %d, want 200", recorder.Code)
	}
	if list := decodeOpinionComplaintList(t, recorder); list.Meta.Total != 1 {
		t.Fatalf("list on 已完成: total = %d, want 1", list.Meta.Total)
	}
	recorder = do(handler, http.MethodGet, itemPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET item on 已完成: status = %d, want 200", recorder.Code)
	}
}

// ─── 级联：删除 run 后投诉记录清空 ───────────────────────────────────

// 创建投诉记录后 DELETE run（runs 路由），再 GET 投诉记录列表返回 404
// （run 已不存在；内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesOpinionComplaints(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众甲","content":"C1"}`)
	createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众乙","content":"C2"}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+run.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, opinionComplaintsPath(run.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET complaints after run delete: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON：collection 路由 Allow 为 GET, POST；item
// 路由 Allow 为 GET, PUT, DELETE。
func TestOpinionComplaintMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	complaint := createOpinionComplaint(t, handler, run.ID, `{"complainant":"观众甲","content":"C"}`)

	// collection：PATCH/PUT → 405，Allow 含 GET 与 POST。
	for _, method := range []string{http.MethodPatch, http.MethodPut} {
		recorder := do(handler, method, opinionComplaintsPath(run.ID), `{}`)
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
		recorder := do(handler, method, opinionComplaintItemPath(run.ID, complaint.ID), `{}`)
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
func TestOpinionComplaintCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		opinionComplaintsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
		opinionComplaintItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAV"),
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
