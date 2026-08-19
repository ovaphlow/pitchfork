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

// departmentsPath builds the department-report collection path of a run.
func departmentsPath(runID string) string {
	return fmt.Sprintf("%s/%s/departments", runsPath, runID)
}

// departmentItemPath builds the department-report item path of a
// (run, department) pair. The department is the path enum value (one of
// 消防/公安/卫健/场馆应急组/其他); URL-escaped by the caller when needed.
func departmentItemPath(runID, department string) string {
	return departmentsPath(runID) + "/" + department
}

// departmentJSON mirrors the department-report response for assertions.
type departmentJSON struct {
	ID         string  `json:"id"`
	RunID      string  `json:"run_id"`
	Department string  `json:"department"`
	Status     string  `json:"status"`
	Note       string  `json:"note"`
	ArrivedAt  *string `json:"arrived_at"`
	CreatedBy  string  `json:"created_by"`
	CreatedAt  string  `json:"created_at"`
	UpdatedAt  string  `json:"updated_at"`
}

type departmentListJSON struct {
	Records []departmentJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeDepartment(t *testing.T, recorder *httptest.ResponseRecorder) departmentJSON {
	t.Helper()
	var report departmentJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &report); err != nil {
		t.Fatalf("body %q is not a department report JSON: %v", recorder.Body.String(), err)
	}
	return report
}

func decodeDepartmentList(t *testing.T, recorder *httptest.ResponseRecorder) departmentListJSON {
	t.Helper()
	var list departmentListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// putDepartment PUTs a department-report body and asserts 200; returns
// the report.
func putDepartment(t *testing.T, handler http.Handler, runID, department, body string) departmentJSON {
	t.Helper()
	recorder := do(handler, http.MethodPut, departmentItemPath(runID, department), body)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeDepartment(t, recorder)
}

// newDepartmentRun creates a scenario and a run in 进行中 (the only
// writable status for department reports); returns the run.
func newDepartmentRun(t *testing.T, handler http.Handler) runJSON {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	run := createRun(t, handler, scenario.ID, "")
	recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return run
}

// ─── PUT /drills/{rid}/departments/{department} ──────────────────────

// 首次 PUT：200 + 完整对象，id 为服务端生成的 26 位 Crockford Base32
// ULID，run_id 与 department 取自路径回显，status 缺省 未响应、note 缺省
// ”、arrived_at 缺省 null、created_by 缺省 ”，created_at/updated_at
// 服务端时间且相等。
func TestPutDepartmentCreatesWithDefaults(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	// body 携带 run_id/id 也被忽略（路径决定归属、服务端决定 id）。
	recorder := do(handler, http.MethodPut, departmentItemPath(run.ID, "消防"),
		`{"run_id":"FAKE-RUN","id":"FAKE-ID"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	report := decodeDepartment(t, recorder)
	if !ulidPattern.MatchString(report.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", report.ID)
	}
	if report.RunID != run.ID {
		t.Fatalf("run_id = %q, want the route path value %q", report.RunID, run.ID)
	}
	if report.Department != "消防" {
		t.Fatalf("department = %q, want the route path value 消防", report.Department)
	}
	if report.Status != "未响应" {
		t.Fatalf("status = %q, want the default 未响应", report.Status)
	}
	if report.Note != "" || report.CreatedBy != "" {
		t.Fatalf("note/created_by = %q / %q, want empty defaults", report.Note, report.CreatedBy)
	}
	if report.ArrivedAt != nil {
		t.Fatalf("arrived_at = %v, want null when omitted", report.ArrivedAt)
	}
	if report.CreatedAt == "" || report.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", report)
	}
	if report.CreatedAt != report.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", report.CreatedAt, report.UpdatedAt)
	}
}

// 显式字段透传：status 未响应（创建起点）、note、arrived_at（RFC3339）、
// created_by 原样回显；五个部门枚举路径均可创建。
func TestPutDepartmentPassthroughAndEcho(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	for _, department := range []string{"消防", "公安", "卫健", "场馆应急组", "其他"} {
		report := putDepartment(t, handler, run.ID, department,
			`{"status":"未响应","note":"已通知，待出动","arrived_at":"2026-08-03T10:30:00+08:00","created_by":"u-commander"}`)
		if report.Department != department || report.Status != "未响应" ||
			report.Note != "已通知，待出动" || report.CreatedBy != "u-commander" {
			t.Fatalf("%s: report = %+v, want the explicit fields echoed", department, report)
		}
		if report.ArrivedAt == nil || *report.ArrivedAt != "2026-08-03T10:30:00+08:00" {
			t.Fatalf("%s: arrived_at = %v, want the RFC3339 instant echoed", department, report.ArrivedAt)
		}
	}
}

// 再次 PUT 原地更新：200 + 更新后对象，id/created_at 不变、updated_at
// 刷新；status 省略保持当前值不重置；note/arrived_at 全量替换；随后
// GET 列表反映更新。
func TestPutDepartmentUpdatesInPlace(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	created := putDepartment(t, handler, run.ID, "消防", `{"note":"初始说明","created_by":"u-commander"}`)
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	// 推进到 已响应 并携带 note/arrived_at。
	advanced := putDepartment(t, handler, run.ID, "消防",
		`{"status":"已响应","note":"已出动","arrived_at":"2026-08-03T10:30:00+08:00","created_by":"u-commander"}`)
	if advanced.ID != created.ID || advanced.CreatedAt != createdAt {
		t.Fatalf("id/created_at must be preserved: %+v", advanced)
	}
	if advanced.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", advanced.UpdatedAt)
	}
	if advanced.Status != "已响应" || advanced.Note != "已出动" {
		t.Fatalf("advance fields = %+v, want 已响应/已出动", advanced)
	}
	if advanced.ArrivedAt == nil || *advanced.ArrivedAt != "2026-08-03T10:30:00+08:00" {
		t.Fatalf("arrived_at = %v, want the new instant", advanced.ArrivedAt)
	}

	time.Sleep(5 * time.Millisecond)

	// status 省略 → 保持 已响应；note/arrived_at 省略 → 重置缺省。
	updated := putDepartment(t, handler, run.ID, "消防", `{}`)
	if updated.ID != created.ID || updated.CreatedAt != createdAt {
		t.Fatalf("id/created_at must survive the update: %+v", updated)
	}
	if updated.Status != "已响应" {
		t.Fatalf("omitted status must keep its value, got %q", updated.Status)
	}
	if updated.Note != "" || updated.ArrivedAt != nil || updated.CreatedBy != "" {
		t.Fatalf("omitted note/arrived_at/created_by must reset: %+v", updated)
	}
	if updated.UpdatedAt == advanced.UpdatedAt {
		t.Fatalf("updated_at must be refreshed on every PUT")
	}

	// PUT 后 GET 列表反映更新。
	recorder := do(handler, http.MethodGet, departmentsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	list := decodeDepartmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 {
		t.Fatalf("GET after PUT: records = %d, total = %d; want 1 / 1", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Status != "已响应" || list.Records[0].Note != "" {
		t.Fatalf("GET after PUT = %+v, want the updated values", list.Records[0])
	}
}

// 失败路径（400）：空/畸形 body（零字节、非 JSON、null、字符串、数组）、
// 路径 department 非五态枚举、非法 status、note 非字符串、arrived_at 非
// RFC3339（数字/布尔/畸形字符串）。
func TestPutDepartmentFailurePaths(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)
	target := departmentItemPath(run.ID, "消防")

	for name, body := range map[string]string{
		"empty body":         "",
		"malformed JSON":     `{"status":`,
		"JSON string":        `"未响应"`,
		"JSON array":         `[{"status":"未响应"}]`,
		"JSON null":          `null`,
		"invalid status":     `{"status":"待命"}`,
		"numeric status":     `{"status":1}`,
		"numeric note":       `{"note":123}`,
		"boolean note":       `{"note":true}`,
		"arrived_at number":  `{"arrived_at":123}`,
		"arrived_at boolean": `{"arrived_at":true}`,
		"arrived_at non-RFC": `{"arrived_at":"2026/08/03 10:30"}`,
		"arrived_at not str": `{"arrived_at":["2026-08-03T10:30:00+08:00"]}`,
	} {
		recorder := do(handler, http.MethodPut, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 路径 department 非法枚举 → 400。
	for _, department := range []string{"武警", "消防组", "应急"} {
		recorder := do(handler, http.MethodPut, departmentItemPath(run.ID, department), `{}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("department %q: status = %d, want 400; body = %s", department, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 首次创建显式 status 非 未响应（跳级）→ 400。
	for _, status := range []string{"已响应", "已到位", "处置中", "已完成"} {
		recorder := do(handler, http.MethodPut, target, `{"status":`+jsonString(status)+`}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("status %q at creation: status = %d, want 400; body = %s", status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 状态机（经 API）：相邻正向迁移合法；跳级与回退（含 已完成 改回）400；
// 迁移失败不改变已存记录。
func TestPutDepartmentStateMachine(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	steps := []string{"已响应", "已到位", "处置中", "已完成"}
	report := putDepartment(t, handler, run.ID, "卫健", `{}`)
	for _, step := range steps {
		report = putDepartment(t, handler, run.ID, "卫健", `{"status":`+jsonString(step)+`}`)
		if report.Status != step {
			t.Fatalf("status = %q, want %q", report.Status, step)
		}
	}
	// 同级 no-op 合法。
	report = putDepartment(t, handler, run.ID, "卫健", `{"status":"已完成"}`)
	if report.Status != "已完成" {
		t.Fatalf("same-status no-op: status = %q, want 已完成", report.Status)
	}

	// 已完成 改回 → 400。
	recorder := do(handler, http.MethodPut, departmentItemPath(run.ID, "卫健"), `{"status":"处置中"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 -> 处置中: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 跳级 → 400（未响应 -> 已到位）。
	putDepartment(t, handler, run.ID, "公安", `{}`)
	recorder = do(handler, http.MethodPut, departmentItemPath(run.ID, "公安"), `{"status":"已到位"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未响应 -> 已到位: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 迁移失败不改变已存记录：GET 列表仍为 卫健=已完成、公安=未响应。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID), "")
	list := decodeDepartmentList(t, recorder)
	if list.Meta.Total != 2 {
		t.Fatalf("after failed transitions: total = %d, want 2", list.Meta.Total)
	}
	for _, item := range list.Records {
		if item.Department == "卫健" && item.Status != "已完成" {
			t.Fatalf("卫健 after failed transition: status = %q, want 已完成", item.Status)
		}
		if item.Department == "公安" && item.Status != "未响应" {
			t.Fatalf("公安 after failed transition: status = %q, want 未响应", item.Status)
		}
	}
}

// ─── GET /drills/{rid}/departments ───────────────────────────────────

// 空列表：200 且 records 为 []、total 为 0。
func TestGetDepartmentsEmptyList(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	recorder := do(handler, http.MethodGet, departmentsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeDepartmentList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("empty list = %#v, want records [] and total 0", list)
	}
}

// 列表：records/meta 约定、created_at ASC 排序、department/status 筛选、
// limit/offset 分页（缺省 limit 50）、非法枚举筛选 400、非法分页 400。
func TestGetDepartmentsListFilterAndPagination(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	putDepartment(t, handler, run.ID, "消防", `{}`)
	time.Sleep(5 * time.Millisecond)
	putDepartment(t, handler, run.ID, "公安", `{}`)
	putDepartment(t, handler, run.ID, "公安", `{"status":"已响应","note":"已出动"}`)
	time.Sleep(5 * time.Millisecond)
	putDepartment(t, handler, run.ID, "卫健", `{}`)
	putDepartment(t, handler, run.ID, "卫健", `{"status":"已响应"}`)

	// 排序 created_at ASC：消防、公安、卫健。
	recorder := do(handler, http.MethodGet, departmentsPath(run.ID), "")
	list := decodeDepartmentList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	for i, want := range []string{"消防", "公安", "卫健"} {
		if list.Records[i].Department != want {
			t.Fatalf("records[%d] = %s, want %s (created_at ASC)", i, list.Records[i].Department, want)
		}
	}

	// 筛选 department。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID)+"?department="+"公安", "")
	list = decodeDepartmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].Department != "公安" {
		t.Fatalf("department filter: records = %+v, total = %d; want 公安 only", list.Records, list.Meta.Total)
	}
	// 筛选 status。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID)+"?status="+"已响应", "")
	list = decodeDepartmentList(t, recorder)
	if list.Meta.Total != 2 || len(list.Records) != 2 {
		t.Fatalf("status filter: records = %d, total = %d; want 2 / 2", len(list.Records), list.Meta.Total)
	}
	// 无匹配筛选。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID)+"?status="+"已完成", "")
	list = decodeDepartmentList(t, recorder)
	if list.Meta.Total != 0 || len(list.Records) != 0 {
		t.Fatalf("no match: records = %d, total = %d; want 0 / 0", len(list.Records), list.Meta.Total)
	}

	// 分页：limit/offset 生效，meta.total 保持筛选后总数。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID)+"?limit=1&offset=1", "")
	list = decodeDepartmentList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 1 || list.Records[0].Department != "公安" {
		t.Fatalf("limit=1 offset=1: records = %+v, total = %d; want 公安", list.Records, list.Meta.Total)
	}
	// limit=0：空页但 total 不变。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID)+"?limit=0", "")
	list = decodeDepartmentList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 0 {
		t.Fatalf("limit=0: records = %d, total = %d; want 0 / 3", len(list.Records), list.Meta.Total)
	}
	// offset 越界：空页。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID)+"?offset=10", "")
	list = decodeDepartmentList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 0 {
		t.Fatalf("offset=10: records = %d, total = %d; want 0 / 3", len(list.Records), list.Meta.Total)
	}

	// 非法筛选与分页参数 → 400。
	for name, query := range map[string]string{
		"invalid department filter": "?department=武警",
		"invalid status filter":     "?status=待命",
		"invalid limit":             "?limit=-1",
		"invalid limit string":      "?limit=abc",
		"invalid offset":            "?offset=-2",
		"invalid offset string":     "?offset=xyz",
	} {
		recorder := do(handler, http.MethodGet, departmentsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── DELETE /drills/{rid}/departments/{department} ───────────────────

// DELETE 204；DELETE 后列表不再包含该部门记录；PUT 可重新创建（新 id）；
// 记录不存在 DELETE 404（判定顺序：run 存在 → 记录存在 → 写门控）。
func TestDeleteDepartment(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)

	created := putDepartment(t, handler, run.ID, "消防", `{"note":"说明"}`)
	putDepartment(t, handler, run.ID, "公安", `{}`)

	recorder := do(handler, http.MethodDelete, departmentItemPath(run.ID, "消防"), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	// DELETE 后列表不再包含该部门记录。
	recorder = do(handler, http.MethodGet, departmentsPath(run.ID), "")
	list := decodeDepartmentList(t, recorder)
	if list.Meta.Total != 1 || list.Records[0].Department != "公安" {
		t.Fatalf("after DELETE: records = %+v, total = %d; want 公安 only", list.Records, list.Meta.Total)
	}

	// 记录不存在 → 404。
	recorder = do(handler, http.MethodDelete, departmentItemPath(run.ID, "消防"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE missing report: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// PUT 重新创建（upsert 语义，新 id）。
	recreated := putDepartment(t, handler, run.ID, "消防", `{"note":"重建"}`)
	if recreated.ID == created.ID || recreated.CreatedAt == created.CreatedAt {
		t.Fatalf("recreate must mint a fresh id/created_at: %+v vs %+v", recreated, created)
	}
}

// ─── run 不存在 / 写门控 ─────────────────────────────────────────────

// run 不存在：PUT/GET/DELETE 均 404，错误体统一 { "error": ... }。
func TestDepartmentReportRunNotFound(t *testing.T) {
	handler := testMux(nil)
	missing := "01ARZ3NDEKTSV4RRFFQ69G5FAV"

	recorder := do(handler, http.MethodPut, departmentItemPath(missing, "消防"), `{}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, departmentsPath(missing), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, departmentItemPath(missing, "消防"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 写门控：PUT/DELETE 仅 run 进行中 可写（未开始/已完成/已终止 → 400）；
// GET 列表不受门控（run 存在即 200）。
func TestDepartmentReportWriteGate(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	notStarted := createRun(t, handler, scenario.ID, "")
	completed := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	terminated := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/terminate", "")
	// 已完成 run 先建好记录（进行中时写入，再走到结束状态）。
	inProgress := newDepartmentRun(t, handler)
	putDepartment(t, handler, inProgress.ID, "消防", `{}`)
	do(handler, http.MethodPost, runsPath+"/"+inProgress.ID+"/complete", "")
	completedWithReport := inProgress

	// 未开始/已完成/已终止（无记录）→ PUT 400（写门控）。
	for _, run := range []runJSON{notStarted, completed, terminated} {
		recorder := do(handler, http.MethodPut, departmentItemPath(run.ID, "消防"), `{}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
	// 已完成（有记录）→ DELETE 400（写门控）。
	recorder := do(handler, http.MethodDelete, departmentItemPath(completedWithReport.ID, "消防"), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE on 已完成 with report: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	// 未开始（无记录）→ DELETE 404（记录不存在先于写门控判定）。
	recorder = do(handler, http.MethodDelete, departmentItemPath(notStarted.ID, "消防"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE on 未开始 without report: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 进行中 → 可写（PUT 200、DELETE 204）。
	run := newDepartmentRun(t, handler)
	putDepartment(t, handler, run.ID, "消防", `{}`)
	recorder = do(handler, http.MethodDelete, departmentItemPath(run.ID, "消防"), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 进行中: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	// GET 不受写门控：已完成 run 的列表仍 200。
	recorder = do(handler, http.MethodGet, departmentsPath(completedWithReport.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeDepartmentList(t, recorder)
	if list.Meta.Total != 1 {
		t.Fatalf("GET on 已完成: total = %d, want 1", list.Meta.Total)
	}
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：集合路径 Allow 为 GET（本资源
// 无 POST），条目路径 Allow 为 PUT, DELETE（无 GET）。
func TestDepartmentReportsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := newDepartmentRun(t, handler)
	putDepartment(t, handler, run.ID, "消防", `{}`)

	recorder := do(handler, http.MethodPost, departmentsPath(run.ID), `{}`)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /drills/{rid}/departments: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); allow != "GET" {
		t.Fatalf("POST /drills/{rid}/departments Allow = %q, want GET", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, departmentsPath(run.ID), "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /drills/{rid}/departments: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") {
		t.Fatalf("PATCH /drills/{rid}/departments Allow = %q, want it to contain GET", allow)
	}
	decodeError(t, recorder)

	for _, method := range []string{http.MethodGet, http.MethodPost, http.MethodPatch} {
		recorder := do(handler, method, departmentItemPath(run.ID, "消防"), "")
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s /drills/{rid}/departments/{{department}}: status = %d, want 405", method, recorder.Code)
		}
		if allow := recorder.Header().Get("Allow"); allow != "PUT, DELETE" {
			t.Fatalf("%s item Allow = %q, want PUT, DELETE", method, allow)
		}
		decodeError(t, recorder)
	}
}

// 允许 Origin 的 OPTIONS 预检对集合与条目路径返回 204，Allow-Methods 含
// PUT/DELETE（以及 GET/OPTIONS），ACAO 回显。
func TestDepartmentReportsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		departmentsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
		departmentItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", "消防"),
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
