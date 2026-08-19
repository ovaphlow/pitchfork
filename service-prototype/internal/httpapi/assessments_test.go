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

// assessmentsPath is the collection route of the drill assessments of
// one run.
func assessmentsPath(runID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/assessments"
}

// assessmentItemPath is the item route of one assessment.
func assessmentItemPath(runID, pointID string) string {
	return "/crate-api/prototype/v1/drills/" + runID + "/assessments/" + pointID
}

// assessmentJSON mirrors the assessment response for assertions.
type assessmentJSON struct {
	ID        string `json:"id"`
	RunID     string `json:"run_id"`
	PointID   string `json:"point_id"`
	Score     int    `json:"score"`
	Comment   string `json:"comment"`
	CreatedBy string `json:"created_by"`
	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at"`
}

type assessmentListJSON struct {
	Records []assessmentJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeAssessment(t *testing.T, recorder *httptest.ResponseRecorder) assessmentJSON {
	t.Helper()
	var assessment assessmentJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &assessment); err != nil {
		t.Fatalf("body %q is not an assessment JSON: %v", recorder.Body.String(), err)
	}
	return assessment
}

func decodeAssessmentList(t *testing.T, recorder *httptest.ResponseRecorder) assessmentListJSON {
	t.Helper()
	var list assessmentListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// newAssessmentRun builds a scenario with two assessment points and a
// run started into 进行中; returns the run and the points.
func newAssessmentRun(t *testing.T, handler http.Handler) (runJSON, []pointJSON) {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	pointA := createPoint(t, handler, scenario.ID, `{"title":"疏散指令传达","description":"考察指令传达是否准确"}`)
	pointB := createPoint(t, handler, scenario.ID, `{"title":"现场秩序维护","description":"考察秩序维护是否到位"}`)
	run := createRun(t, handler, scenario.ID, "")
	recorder := do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("start status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return run, []pointJSON{pointA, pointB}
}

// putAssessment PUTs an assessment body and asserts 200; returns the
// assessment.
func putAssessment(t *testing.T, handler http.Handler, runID, pointID, body string) assessmentJSON {
	t.Helper()
	recorder := do(handler, http.MethodPut, assessmentItemPath(runID, pointID), body)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeAssessment(t, recorder)
}

// ─── PUT /drills/{rid}/assessments/{pointId} ─────────────────────────

// 首次 PUT：200 + 完整记录，id 为服务端生成的 26 位 Crockford Base32 ULID，
// run_id/point_id 来自路径，comment/created_by 缺省 ”，created_at/updated_at
// 服务端时间且相等。
func TestPutAssessmentCreatesWithDefaults(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)

	recorder := do(handler, http.MethodPut, assessmentItemPath(run.ID, points[0].ID), `{"score":80}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	assessment := decodeAssessment(t, recorder)
	if !ulidPattern.MatchString(assessment.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", assessment.ID)
	}
	if assessment.RunID != run.ID || assessment.PointID != points[0].ID {
		t.Fatalf("run_id/point_id = %q / %q, want the route path values", assessment.RunID, assessment.PointID)
	}
	if assessment.Score != 80 {
		t.Fatalf("score = %d, want 80", assessment.Score)
	}
	if assessment.Comment != "" || assessment.CreatedBy != "" {
		t.Fatalf("comment/created_by = %q / %q, want empty defaults", assessment.Comment, assessment.CreatedBy)
	}
	if assessment.CreatedAt == "" || assessment.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", assessment)
	}
	if assessment.CreatedAt != assessment.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want equal", assessment.CreatedAt, assessment.UpdatedAt)
	}
}

// 显式 score 0 是合法分值（与缺省区分）：0 与 100 均通过；score/comment/
// created_by 原样回显。
func TestPutAssessmentPassthrough(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)

	recorder := do(handler, http.MethodPut, assessmentItemPath(run.ID, points[0].ID),
		`{"score":100,"comment":"响应迅速，处置得当","created_by":"u-admin"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	assessment := decodeAssessment(t, recorder)
	if assessment.Score != 100 || assessment.Comment != "响应迅速，处置得当" || assessment.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", assessment)
	}

	// 显式 0 合法（非缺省）。
	recorder = do(handler, http.MethodPut, assessmentItemPath(run.ID, points[1].ID), `{"score":0}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("explicit score 0: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	assessment = decodeAssessment(t, recorder)
	if assessment.Score != 0 {
		t.Fatalf("explicit score 0: score = %d, want 0", assessment.Score)
	}
}

// 再次 PUT 原地更新：200 + 更新后完整记录，id/created_at 不变、updated_at
// 刷新；全量替换（省略字段按默认值重置）；随后 GET 单条与列表均反映更新。
func TestPutAssessmentUpdatesInPlace(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)

	created := putAssessment(t, handler, run.ID, points[0].ID,
		`{"score":70,"comment":"第一版","created_by":"u-admin"}`)
	createdAt := created.CreatedAt
	// 保证 updated_at 与 created_at 可区分（毫秒级分辨率）。
	time.Sleep(5 * time.Millisecond)

	// 再次 PUT：id/created_at 不变、updated_at 刷新、字段全量替换
	// （省略的 comment/created_by 按默认值重置）。
	updated := putAssessment(t, handler, run.ID, points[0].ID, `{"score":90}`)
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if updated.CreatedAt != createdAt {
		t.Fatalf("created_at %q changed to %q on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == createdAt {
		t.Fatalf("updated_at %q must be refreshed on update", updated.UpdatedAt)
	}
	if updated.Score != 90 || updated.Comment != "" || updated.CreatedBy != "" {
		t.Fatalf("replacement semantics = %+v", updated)
	}

	// GET 单条与列表反映更新。
	recorder := do(handler, http.MethodGet, assessmentItemPath(run.ID, points[0].ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeAssessment(t, recorder)
	if fetched.Score != 90 || fetched.Comment != "" {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}
	recorder = do(handler, http.MethodGet, assessmentsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("list after PUT: status = %d, want 200", recorder.Code)
	}
	list := decodeAssessmentList(t, recorder)
	if list.Meta.Total != 1 || list.Records[0].Score != 90 {
		t.Fatalf("list after PUT = %+v, want the updated record", list)
	}
}

// 非法输入一律 400 {error}：空 body、非法 JSON、非对象 body（字符串/数组/
// null）、score 缺省（含显式 null）、score 越界（<0、>100）、score 非数字。
func TestPutAssessmentInvalidBody(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)
	target := assessmentItemPath(run.ID, points[0].ID)

	for name, body := range map[string]string{
		"empty body":         "",
		"malformed JSON":     `{"score":`,
		"JSON string":        `"80"`,
		"JSON array":         `[{"score":80}]`,
		"JSON null":          `null`,
		"score missing":      `{"comment":"无分"}`,
		"score null":         `{"score":null}`,
		"score too small":    `{"score":-1}`,
		"score too big":      `{"score":101}`,
		"score not a number": `{"score":"80"}`,
	} {
		recorder := do(handler, http.MethodPut, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// run 不存在 → 404；point 不存在 → 404；point 不属于 run 场景 → 404；错误体
// 统一 {error}。
func TestPutAssessmentRunAndPointNotFound(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)
	otherScenario := createScenario(t, handler, `{"name":"停电应急演练","category":"停电与基础设施","background":"市电中断"}`)
	foreignPoint := createPoint(t, handler, otherScenario.ID, `{"title":"恢复供电","description":"考察恢复供电流程"}`)

	cases := []struct {
		name   string
		target string
	}{
		{"run missing", assessmentItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", points[0].ID)},
		{"point missing", assessmentItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV")},
		{"point of another scenario", assessmentItemPath(run.ID, foreignPoint.ID)},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodPut, testCase.target, `{"score":80}`)
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("%s: status = %d, want 404; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// run 非 进行中/已完成（未开始/已终止）→ PUT/DELETE 均 400；进行中与已完成
// 均可写。
func TestAssessmentRunWritableStates(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	point := createPoint(t, handler, scenario.ID, `{"title":"疏散指令传达","description":"考察指令传达是否准确"}`)

	notStarted := createRun(t, handler, scenario.ID, "")
	inProgress := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+inProgress.ID+"/start", "")
	completed := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	terminated := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/start", "")
	do(handler, http.MethodPost, runsPath+"/"+terminated.ID+"/terminate", "")

	// 未开始/已终止 → PUT/DELETE 均 400。
	for _, run := range []runJSON{notStarted, terminated} {
		recorder := do(handler, http.MethodPut, assessmentItemPath(run.ID, point.ID), `{"score":80}`)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("PUT on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
		recorder = do(handler, http.MethodDelete, assessmentItemPath(run.ID, point.ID), "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("DELETE on %s: status = %d, want 400; body = %s", run.Status, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 进行中/已完成 → 可写。
	putAssessment(t, handler, inProgress.ID, point.ID, `{"score":80}`)
	putAssessment(t, handler, completed.ID, point.ID, `{"score":90}`)
	recorder := do(handler, http.MethodDelete, assessmentItemPath(inProgress.ID, point.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 进行中: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, assessmentItemPath(completed.ID, point.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE on 已完成: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── GET /drills/{rid}/assessments ───────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}（records 为 JSON 数组而非
// null）；run 不存在 → 404。
func TestListAssessmentsEmpty(t *testing.T) {
	handler := testMux(nil)
	run, _ := newAssessmentRun(t, handler)

	recorder := do(handler, http.MethodGet, assessmentsPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeAssessmentList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, assessmentsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// 排序 created_at ASC（先 PUT 的在前）；limit/offset 分页生效、缺省 limit
// 50、meta.total 为分页前总数；非法 limit/offset → 400。
func TestListAssessmentsSortedAndPaginated(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	points := make([]pointJSON, 3)
	for i := range points {
		points[i] = createPoint(t, handler, scenario.ID, fmt.Sprintf(`{"title":"要点%02d"}`, i+1))
	}
	run := createRun(t, handler, scenario.ID, "")
	do(handler, http.MethodPost, runsPath+"/"+run.ID+"/start", "")

	// 逆序 PUT，期望列表按 PUT 顺序（created_at ASC）返回。
	for i := len(points) - 1; i >= 0; i-- {
		putAssessment(t, handler, run.ID, points[i].ID, fmt.Sprintf(`{"score":%d}`, 60+i*10))
		// 保证 created_at 严格递增（毫秒级分辨率），排序断言确定。
		time.Sleep(time.Millisecond)
	}

	recorder := do(handler, http.MethodGet, assessmentsPath(run.ID)+"?limit=2&offset=0", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeAssessmentList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 3 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 3", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].PointID != points[2].ID || list.Records[1].PointID != points[1].ID {
		t.Fatalf("first page = %s %s, want point 3 then point 2 (created_at ASC)",
			list.Records[0].PointID, list.Records[1].PointID)
	}
	if list.Records[0].Score != 80 || list.Records[1].Score != 70 {
		t.Fatalf("first page scores = %d %d, want 80 70", list.Records[0].Score, list.Records[1].Score)
	}

	recorder = do(handler, http.MethodGet, assessmentsPath(run.ID)+"?limit=2&offset=2", "")
	list = decodeAssessmentList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 3 || list.Records[0].PointID != points[0].ID {
		t.Fatalf("limit=2 offset=2: records = %d, total = %d, point = %q; want 1 / 3 / point 1",
			len(list.Records), list.Meta.Total, func() string {
				if len(list.Records) == 0 {
					return ""
				}
				return list.Records[0].PointID
			}())
	}

	recorder = do(handler, http.MethodGet, assessmentsPath(run.ID), "")
	list = decodeAssessmentList(t, recorder)
	if len(list.Records) != 3 || list.Meta.Total != 3 {
		t.Fatalf("default limit: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid limit":   "?limit=abc",
		"negative limit":  "?limit=-1",
		"invalid offset":  "?offset=abc",
		"negative offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, assessmentsPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/assessments/{pointId} ─────────────────────────

// 存在的记录返回 200 + 完整对象；run 不存在 → 404；记录不存在 → 404；
// DELETE 后 GET 单条返回 404（写操作生效性）。
func TestGetAssessment(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)
	created := putAssessment(t, handler, run.ID, points[0].ID, `{"score":75,"comment":"总体达标"}`)

	recorder := do(handler, http.MethodGet, assessmentItemPath(run.ID, points[0].ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	fetched := decodeAssessment(t, recorder)
	if fetched.ID != created.ID || fetched.RunID != run.ID || fetched.PointID != points[0].ID ||
		fetched.Score != 75 || fetched.Comment != "总体达标" {
		t.Fatalf("GET response %+v does not echo the created assessment", fetched)
	}

	// run 不存在。
	recorder = do(handler, http.MethodGet, assessmentItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", points[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	// 记录不存在（另一 point 从未 PUT）。
	recorder = do(handler, http.MethodGet, assessmentItemPath(run.ID, points[1].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("record missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	// DELETE 后 GET 单条返回 404。
	recorder = do(handler, http.MethodDelete, assessmentItemPath(run.ID, points[0].ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, assessmentItemPath(run.ID, points[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/assessments/{pointId} ──────────────────────

// 成功返回 204，随后列表不含该记录；run 不存在 → 404；记录不存在（含 point
// 存在但未评估）→ 404。
func TestDeleteAssessment(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)
	putAssessment(t, handler, run.ID, points[0].ID, `{"score":70}`)
	putAssessment(t, handler, run.ID, points[1].ID, `{"score":80}`)

	recorder := do(handler, http.MethodDelete, assessmentItemPath(run.ID, points[0].ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, assessmentsPath(run.ID), "")
	list := decodeAssessmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].PointID != points[1].ID {
		t.Fatalf("list after DELETE = %+v, want only the remaining record", list)
	}

	// run 不存在。
	recorder = do(handler, http.MethodDelete, assessmentItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", points[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("run missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	// 记录不存在（point 存在但从未评估）。
	recorder = do(handler, http.MethodDelete, assessmentItemPath(run.ID, points[0].ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("record missing: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头：集合路径 Allow 为 GET（本资源
// 无 POST），条目路径 Allow 为 GET, PUT, DELETE。
func TestAssessmentsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run, points := newAssessmentRun(t, handler)

	recorder := do(handler, http.MethodPost, assessmentsPath(run.ID), `{}`)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /drills/{rid}/assessments: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); allow != "GET" {
		t.Fatalf("POST /drills/{rid}/assessments Allow = %q, want GET", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, assessmentsPath(run.ID), "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH /drills/{rid}/assessments: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") {
		t.Fatalf("PATCH /drills/{rid}/assessments Allow = %q, want it to contain GET", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPost, assessmentItemPath(run.ID, points[0].ID), `{}`)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST /drills/{rid}/assessments/{pointId}: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("POST /drills/{rid}/assessments/{pointId} Allow = %q, want GET, PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS 预检对集合与条目路径返回 204，Allow-Methods 含
// PUT/DELETE（以及 GET/OPTIONS），ACAO 回显。
func TestAssessmentsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{
		assessmentsPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
		assessmentItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAV"),
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
