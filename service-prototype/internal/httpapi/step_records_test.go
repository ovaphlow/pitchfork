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

// stepRecordsPath is the collection route of the step execution records
// of one run.
func stepRecordsPath(runID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/steps"
}

// stepRecordItemPath is the item route of one step record.
func stepRecordItemPath(runID, stepID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/steps/" + stepID
}

// stepRecordJSON mirrors the step record response for assertions.
type stepRecordJSON struct {
	ID          string  `json:"id"`
	RunID       string  `json:"run_id"`
	StepID      string  `json:"step_id"`
	Status      string  `json:"status"`
	ActionNote  string  `json:"action_note"`
	PerformedBy string  `json:"performed_by"`
	PerformedAt *string `json:"performed_at"`
	CreatedBy   string  `json:"created_by"`
	CreatedAt   string  `json:"created_at"`
	UpdatedAt   string  `json:"updated_at"`
}

type stepRecordListJSON struct {
	Records []stepRecordJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeStepRecord(t *testing.T, recorder *httptest.ResponseRecorder) stepRecordJSON {
	t.Helper()
	var record stepRecordJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &record); err != nil {
		t.Fatalf("body %q is not a step record JSON: %v", recorder.Body.String(), err)
	}
	return record
}

func decodeStepRecordList(t *testing.T, recorder *httptest.ResponseRecorder) stepRecordListJSON {
	t.Helper()
	var list stepRecordListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// newStepRecordRun builds a scenario with two steps and a run started
// into 进行中; returns the run and the steps.
func newStepRecordRun(t *testing.T, handler http.Handler) (runJSON, []stepJSON) {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	stepA := createStep(t, handler, scenario.ID, `{"sort_order":1,"title":"疏散广播"}`)
	stepB := createStep(t, handler, scenario.ID, `{"sort_order":2,"title":"清点人数"}`)
	run := createRun(t, handler, scenario.ID, "")
	recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return run, []stepJSON{stepA, stepB}
}

// putStepRecord PUTs a step record body and asserts 200; returns the
// record.
func putStepRecord(t *testing.T, handler http.Handler, runID, stepID, body string) stepRecordJSON {
	t.Helper()
	recorder := do(handler, http.MethodPut, stepRecordItemPath(runID, stepID), body)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeStepRecord(t, recorder)
}

// ─── PUT /drills/{rid}/steps/{stepId} ────────────────────────────────

// 首次 PUT：200 + 完整记录，id 为服务端生成的 26 位 Crockford Base32 ULID，
// run_id/step_id 来自路径，status 缺省 待执行，action_note/performed_by/
// created_by 缺省 ”，performed_at 缺省 null，created_at/updated_at 服务端
// 时间且相等。
func TestPutStepRecordCreatesWithDefaults(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)

	recorder := do(handler, http.MethodPut, stepRecordItemPath(run.ID, steps[0].ID), `{}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	record := decodeStepRecord(t, recorder)
	if !ulidPattern.MatchString(record.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", record.ID)
	}
	if record.RunID != run.ID || record.StepID != steps[0].ID {
		t.Fatalf("run_id/step_id = %q / %q, want the route path values", record.RunID, record.StepID)
	}
	if record.Status != "待执行" {
		t.Fatalf("status = %q, want 待执行 (default)", record.Status)
	}
	if record.ActionNote != "" || record.PerformedBy != "" || record.CreatedBy != "" {
		t.Fatalf("action_note/performed_by/created_by = %q / %q / %q, want empty defaults",
			record.ActionNote, record.PerformedBy, record.CreatedBy)
	}
	if record.PerformedAt != nil {
		t.Fatalf("performed_at = %v, want null", record.PerformedAt)
	}
	if record.CreatedAt == "" || record.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", record)
	}
	if record.CreatedAt != record.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", record.CreatedAt, record.UpdatedAt)
	}
}

// 全字段透传：status 已执行、action_note/performed_by/created_by 原样回显，
// performed_at 为合法 RFC 3339 时间戳时原样透传；显式 null 与省略等价。
func TestPutStepRecordPassthrough(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)

	recorder := do(handler, http.MethodPut, stepRecordItemPath(run.ID, steps[0].ID),
		`{"status":"已执行","action_note":"启动应急广播并疏散","performed_by":"u-admin","performed_at":"2026-08-14T10:30:00+08:00","created_by":"u-admin"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	record := decodeStepRecord(t, recorder)
	if record.Status != "已执行" || record.ActionNote != "启动应急广播并疏散" ||
		record.PerformedBy != "u-admin" || record.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", record)
	}
	if record.PerformedAt == nil || *record.PerformedAt != "2026-08-14T10:30:00+08:00" {
		t.Fatalf("performed_at = %v, want the request timestamp echoed", record.PerformedAt)
	}

	// 显式 null 与省略等价：performed_at 为 null。
	recorder = do(handler, http.MethodPut, stepRecordItemPath(run.ID, steps[1].ID),
		`{"status":"跳过","performed_at":null}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	record = decodeStepRecord(t, recorder)
	if record.Status != "跳过" || record.PerformedAt != nil {
		t.Fatalf("explicit null: status = %q, performed_at = %v; want 跳过 / null", record.Status, record.PerformedAt)
	}
}

// 再次 PUT 原地更新：200 + 更新后完整记录，id/created_at 不变、updated_at
// 刷新；全量替换（省略字段按默认值重置，body 为 {} 时全部重置）；随后 GET
// 单条与列表均反映更新。
func TestPutStepRecordUpdatesInPlace(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)

	created := putStepRecord(t, handler, run.ID, steps[0].ID,
		`{"status":"已执行","action_note":"第一版","performed_by":"u-admin","performed_at":"2026-08-14T10:30:00Z","created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 updated_at 与 created_at 可区分（毫秒级分辨率）。
	time.Sleep(5 * time.Millisecond)

	// body {}：全部字段按默认值重置。
	updated := putStepRecord(t, handler, run.ID, steps[0].ID, `{}`)
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}
	if updated.Status != "待执行" || updated.ActionNote != "" || updated.PerformedBy != "" ||
		updated.PerformedAt != nil || updated.CreatedBy != "" {
		t.Fatalf("defaults after {}: %+v", updated)
	}

	// 省略字段按默认值重置、携带字段替换。
	time.Sleep(5 * time.Millisecond)
	replaced := putStepRecord(t, handler, run.ID, steps[0].ID, `{"status":"跳过","action_note":"第二版"}`)
	if replaced.ID != created.ID || replaced.CreatedAt != createdAt {
		t.Fatalf("id/created_at must survive the update: %+v", replaced)
	}
	if replaced.Status != "跳过" || replaced.ActionNote != "第二版" || replaced.PerformedBy != "" ||
		replaced.PerformedAt != nil || replaced.CreatedBy != "" {
		t.Fatalf("replacement semantics = %+v", replaced)
	}

	// GET 单条与列表反映更新。
	recorder := do(handler, http.MethodGet, stepRecordItemPath(run.ID, steps[0].ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeStepRecord(t, recorder)
	if fetched.Status != "跳过" || fetched.ActionNote != "第二版" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}
	recorder = do(handler, http.MethodGet, stepRecordsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("list after PUT: status = %d, want 200", recorder.Code)
	}
	list := decodeStepRecordList(t, recorder)
	if list.Meta.Total != 1 || list.Records[0].ActionNote != "第二版" {
		t.Fatalf("list after PUT = %+v, want the updated record", list)
	}
}

// 非法输入一律 400 {error}：空 body、非法 JSON、非对象 body（字符串/数组/
// null）、非法 status、非法 performed_at 时间戳。
func TestPutStepRecordInvalidBody(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)
	target := stepRecordItemPath(run.ID, steps[0].ID)

	for name, body := range map[string]string{
		"empty body":        "",
		"malformed JSON":    `{"status":`,
		"JSON string":       `"已执行"`,
		"JSON array":        `[{"status":"已执行"}]`,
		"JSON null":         `null`,
		"invalid status":    `{"status":"草稿"}`,
		"invalid performed": `{"performed_at":"2026-08-14"}`,
		"empty performed":   `{"performed_at":""}`,
	} {
		recorder := do(handler, http.MethodPut, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// run 不存在 → 404；step 不存在 → 404；step 不属于 run 场景 → 404；错误体
// 统一 {error}。
func TestPutStepRecordRunAndStepNotFound(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)
	otherScenario := createScenario(t, handler, `{"name":"停电应急演练","category":"停电与基础设施","background":"市电中断"}`)
	foreignStep := createStep(t, handler, otherScenario.ID, `{"sort_order":1,"title":"恢复供电"}`)

	cases := []struct {
		name   string
		target string
	}{
		{"run missing", stepRecordItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", steps[0].ID)},
		{"step missing", stepRecordItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV")},
		{"step of another scenario", stepRecordItemPath(run.ID, foreignStep.ID)},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodPut, testCase.target, `{"status":"已执行"}`)
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("%s: status = %d, want 404; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// run 非 进行中（未开始/已完成/已终止）→ PUT/DELETE 均 400。
func TestStepRecordRunNotInProgress(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	step := createStep(t, handler, scenario.ID, `{"sort_order":1,"title":"疏散广播"}`)

	notStarted := createRun(t, handler, scenario.ID, "")
	completed := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	terminated := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/terminate", "")

	for _, run := range []runJSON{notStarted, completed, terminated} {
		recorder := do(handler, http.MethodPut, stepRecordItemPath(run.ID, step.ID), `{"status":"已执行"}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, stepRecordItemPath(run.ID, step.ID), "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("DELETE on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/steps ─────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}（records 为 JSON 数组而非
// null）；run 不存在 → 404。
func TestListStepRecordsEmpty(t *testing.T) {
	handler := testMux(nil)
	run, _ := newStepRecordRun(t, handler)

	recorder := do(handler, http.MethodGet, stepRecordsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeStepRecordList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, stepRecordsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// 排序 created_at ASC（先 PUT 的在前）；limit/offset 分页生效、缺省 limit
// 50、meta.total 为分页前总数；非法 limit/offset → 400。
func TestListStepRecordsSortedAndPaginated(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	steps := make([]stepJSON, 53)
	for i := range steps {
		steps[i] = createStep(t, handler, scenario.ID, fmt.Sprintf(`{"sort_order":%d,"title":"步骤%02d"}`, i+1, i+1))
	}
	run := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")

	// 按 sort_order 逆序 PUT，期望列表按 PUT 顺序（created_at ASC）返回。
	for i := len(steps) - 1; i >= 0; i-- {
		putStepRecord(t, handler, run.ID, steps[i].ID, fmt.Sprintf(`{"action_note":"第%02d条"}`, i+1))
		// 保证 created_at 严格递增（毫秒级分辨率），排序断言确定。
		time.Sleep(time.Millisecond)
	}

	recorder := do(handler, http.MethodGet, stepRecordsPath(run.ID)+"?limit=2&offset=0", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeStepRecordList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].ActionNote != "第53条" || list.Records[1].ActionNote != "第52条" {
		t.Fatalf("first page = %q %q, want 第53条 第52条 (created_at ASC)", list.Records[0].ActionNote, list.Records[1].ActionNote)
	}

	recorder = do(handler, http.MethodGet, stepRecordsPath(run.ID)+"?limit=2&offset=52", "")
	list = decodeStepRecordList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 || list.Records[0].ActionNote != "第01条" {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d, note = %q; want 1 / 53 / 第01条",
			len(list.Records), list.Meta.Total, func() string {
				if len(list.Records) == 0 {
					return ""
				}
				return list.Records[0].ActionNote
			}())
	}

	recorder = do(handler, http.MethodGet, stepRecordsPath(run.ID), "")
	list = decodeStepRecordList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid limit":   "?limit=abc",
		"negative limit":  "?limit=-1",
		"invalid offset":  "?offset=abc",
		"negative offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, stepRecordsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/steps/{stepId} ────────────────────────────────

// 存在的记录返回 200 + 完整对象；run 不存在 → 404；记录不存在 → 404；
// DELETE 后 GET 单条返回 404（写操作生效性）。
func TestGetStepRecord(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)
	created := putStepRecord(t, handler, run.ID, steps[0].ID,
		`{"status":"已执行","action_note":"已完成广播","performed_by":"u-admin"}`)

	recorder := do(handler, http.MethodGet, stepRecordItemPath(run.ID, steps[0].ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeStepRecord(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.StepID != steps[0].ID ||
		fetched.Status != "已执行" || fetched.ActionNote != "已完成广播" || fetched.PerformedBy != "u-admin" {
		t.Fatalf("GET response %+v does not echo the created record", fetched)
	}

	// run 不存在。
	recorder = do(handler, http.MethodGet, stepRecordItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", steps[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	// 记录不存在（另一 step 从未 PUT）。
	recorder = do(handler, http.MethodGet, stepRecordItemPath(run.ID, steps[1].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("record missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	// DELETE 后 GET 单条返回 404。
	recorder = do(handler, http.MethodDelete, stepRecordItemPath(run.ID, steps[0].ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, stepRecordItemPath(run.ID, steps[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/steps/{stepId} ─────────────────────────────

// 成功返回 204，随后列表不含该记录；run 不存在 → 404；记录不存在 → 404。
func TestDeleteStepRecord(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)
	putStepRecord(t, handler, run.ID, steps[0].ID, `{"status":"已执行"}`)
	putStepRecord(t, handler, run.ID, steps[1].ID, `{"status":"跳过"}`)

	recorder := do(handler, http.MethodDelete, stepRecordItemPath(run.ID, steps[0].ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, stepRecordsPath(run.ID), "")
	list := decodeStepRecordList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].StepID != steps[1].ID {
		t.Fatalf("list after DELETE = %+v, want only the remaining record", list)
	}

	// run 不存在。
	recorder = do(handler, http.MethodDelete, stepRecordItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", steps[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	// 记录不存在（已删除）。
	recorder = do(handler, http.MethodDelete, stepRecordItemPath(run.ID, steps[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("record missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：集合路径 Allow 为 GET（本资源
// 无 POST），条目路径 Allow 为 GET, PUT, DELETE。
func TestStepRecordsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run, steps := newStepRecordRun(t, handler)

	recorder := do(handler, http.MethodPost, stepRecordsPath(run.ID), `{}`)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /drills/{rid}/steps: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); allow != "GET" {
		t.Fatalf("POST /drills/{rid}/steps Allow = %q, want GET", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, stepRecordsPath(run.ID), "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /drills/{rid}/steps: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") {
		t.Fatalf("PATCH /drills/{rid}/steps Allow = %q, want it to contain GET", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, stepRecordItemPath(run.ID, steps[0].ID), `{}`)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /drills/{rid}/steps/{stepId}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("POST /drills/{rid}/steps/{stepId} Allow = %q, want GET, PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS 预检对集合与条目路径返回 204，Allow-Methods 含
// PUT/DELETE（以及 GET/OPTIONS），ACAO 回显。
func TestStepRecordsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		stepRecordsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
		stepRecordItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAV"),
	} {
		req := httptest.NewRequest(http.MethodOptions, target, nil)
		req.Header.Set("Origin", "https://allowed.example")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusNoContent {
			t.Fatalf("%s: preflight status = %d, want 204", target, recorder.Code)
		}
		methods := recorder.Header().Get("Access-Control-Allow-Methods")
		for _, method := range []string{"GET", "PUT", "DELETE", "OPTIONS"} {
			if !strings.Contains(methods, method) {
				t.Fatalf("%s: Allow-Methods = %q, want it to contain %s", target, methods, method)
			}
		}
		if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
			t.Fatalf("%s: ACAO = %q", target, recorder.Header().Get("Access-Control-Allow-Origin"))
		}
	}
}
