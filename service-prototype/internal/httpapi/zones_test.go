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

// zonesPath builds the zone-density collection path of a run.
func zonesPath(runID string) string {
	return fmt.Sprintf("%s/%s/zone-densities", runsPath, runID)
}

// zoneDensityItemPath builds the zone-density item path of a
// (run, report) pair.
func zoneDensityItemPath(runID, zid string) string {
	return zonesPath(runID) + "/" + zid
}

// zoneDensityJSON mirrors the zone-density response for assertions.
type zoneDensityJSON struct {
	ID          string  `json:"id"`
	RunID       string  `json:"run_id"`
	ZoneName    string  `json:"zone_name"`
	PeopleCount int     `json:"people_count"`
	ReportedAt  *string `json:"reported_at"`
	CreatedBy   string  `json:"created_by"`
	CreatedAt   string  `json:"created_at"`
	UpdatedAt   string  `json:"updated_at"`
}

type zoneDensityListJSON struct {
	Records []zoneDensityJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeZoneDensity(t *testing.T, recorder *httptest.ResponseRecorder) zoneDensityJSON {
	t.Helper()
	var density zoneDensityJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &density); err != nil {
		t.Fatalf("body %q is not a zone-density JSON: %v", recorder.Body.String(), err)
	}
	return density
}

func decodeZoneDensityList(t *testing.T, recorder *httptest.ResponseRecorder) zoneDensityListJSON {
	t.Helper()
	var list zoneDensityListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// validZoneDensityBody is a minimal valid zone-density report body.
const validZoneDensityBody = `{"zone_name":"东区广场","people_count":128}`

// reportZoneDensity posts the given body to the run's collection and
// asserts 201; returns the created report.
func reportZoneDensity(t *testing.T, handler http.Handler, runID, body string) zoneDensityJSON {
	t.Helper()
	if body == "" {
		body = validZoneDensityBody
	}
	recorder := do(handler, http.MethodPost, zonesPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeZoneDensity(t, recorder)
}

// ─── POST /drills/{rid}/zone-densities ───────────────────────────────

// 合法上报：201，id 为服务端生成的 26 位 Crockford Base32 ULID，run_id
// 取自路径回显（body 传入 run_id/id 被忽略），zone_name/people_count 回
// 显，reported_at 服务端创建时设置非空（body 传入 reported_at 被忽略），
// created_by 透传，created_at/updated_at 服务端设置。
func TestReportZoneDensitySuccess(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, zonesPath(run.ID),
		`{"zone_name":"东区广场","people_count":128,"run_id":"ignored","id":"FAKE-ID","reported_at":"2020-01-01T00:00:00Z","created_by":"u-field-zhang"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	density := decodeZoneDensity(t, recorder)
	if !ulidPattern.MatchString(density.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", density.ID)
	}
	if density.RunID != run.ID {
		t.Fatalf("run_id = %q, want the run from the path (body run_id ignored)", density.RunID)
	}
	if density.ZoneName != "东区广场" || density.PeopleCount != 128 {
		t.Fatalf("zone_name/people_count not echoed: %+v", density)
	}
	if density.ReportedAt == nil || *density.ReportedAt == "" || strings.HasPrefix(*density.ReportedAt, "2020") {
		t.Fatalf("reported_at = %v, want the server-set creation instant (body reported_at ignored)", density.ReportedAt)
	}
	if density.CreatedBy != "u-field-zhang" {
		t.Fatalf("created_by = %q, want u-field-zhang", density.CreatedBy)
	}
	if density.CreatedAt == "" || density.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", density)
	}
}

// 缺省口径：people_count 0 合法（非负整数下限）；created_by 省略时空串。
func TestReportZoneDensityDefaultsAndZero(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, zonesPath(run.ID), `{"zone_name":"3 号出口","people_count":0}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	density := decodeZoneDensity(t, recorder)
	if density.ZoneName != "3 号出口" || density.PeopleCount != 0 {
		t.Fatalf("zone_name/people_count = %q / %d, want the input echoed", density.ZoneName, density.PeopleCount)
	}
	if density.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", density.CreatedBy)
	}
	if density.ReportedAt == nil {
		t.Fatalf("reported_at = nil, want the server-set creation instant")
	}
}

// 失败路径：缺 zone_name（含空白）、缺 people_count、负值、非数字、非
// 整数、空 body、畸形 body → 400，错误体统一 {"error": ...}。
func TestReportZoneDensityFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for name, body := range map[string]string{
		"missing zone_name":     `{"people_count":10}`,
		"blank zone_name":       `{"zone_name":"  ","people_count":10}`,
		"missing people_count":  `{"zone_name":"东区广场"}`,
		"negative people_count": `{"zone_name":"东区广场","people_count":-1}`,
		"non-number people":     `{"zone_name":"东区广场","people_count":"abc"}`,
		"fractional people":     `{"zone_name":"东区广场","people_count":1.5}`,
		"malformed body":        `{"zone_name":`,
	} {
		recorder := do(handler, http.MethodPost, zonesPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 空 body → 400。
	recorder := do(handler, http.MethodPost, zonesPath(run.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("empty body: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 状态约束：run 不存在 404（优先于门控）；仅 进行中 可上报（未开始/
// 已完成 400）。
func TestReportZoneDensityRunState(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	// run 不存在 → 404。
	recorder := do(handler, http.MethodPost, zonesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), validZoneDensityBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 未开始 → 400。
	notStarted := createRun(t, handler, scenario.ID, "")
	recorder = do(handler, http.MethodPost, zonesPath(notStarted.ID), validZoneDensityBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 进行中 → 201。
	inProgress := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, inProgress.ID)
	recorder = do(handler, http.MethodPost, zonesPath(inProgress.ID), validZoneDensityBody)
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
	recorder = do(handler, http.MethodPost, zonesPath(completed.ID), validZoneDensityBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/zone-densities ────────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}；run 不存在 404。
func TestListZoneDensitiesEmptyAndMissingRun(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, zonesPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeZoneDensityList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("empty list = %+v, want records [] and total 0", list)
	}

	recorder = do(handler, http.MethodGet, zonesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 排序 reported_at DESC, id DESC（最新上报在前）；PUT 刷新 reported_at
// 后该报告排到最前；zone_name 精确匹配筛选；meta.total 为筛选后的总数。
func TestListZoneDensitiesSortedAndFiltered(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// 依次上报：东区广场 → 西区 → 东区广场。
	first := reportZoneDensity(t, handler, run.ID, `{"zone_name":"东区广场","people_count":100}`)
	time.Sleep(5 * time.Millisecond)
	second := reportZoneDensity(t, handler, run.ID, `{"zone_name":"西区","people_count":200}`)
	time.Sleep(5 * time.Millisecond)
	third := reportZoneDensity(t, handler, run.ID, `{"zone_name":"东区广场","people_count":300}`)

	recorder := do(handler, http.MethodGet, zonesPath(run.ID), "")
	list := decodeZoneDensityList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	// reported_at DESC：三号、二号、一号。
	if list.Records[0].ID != third.ID || list.Records[1].ID != second.ID || list.Records[2].ID != first.ID {
		t.Fatalf("records not in reported_at DESC order: %+v", list.Records)
	}

	// PUT 刷新 second 的 reported_at → 排到最前。
	recorder = do(handler, http.MethodPut, zoneDensityItemPath(run.ID, second.ID), `{"zone_name":"西区","people_count":250}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, zonesPath(run.ID), "")
	list = decodeZoneDensityList(t, recorder)
	if list.Records[0].ID != second.ID || list.Records[1].ID != third.ID || list.Records[2].ID != first.ID {
		t.Fatalf("records not re-sorted after PUT refresh: %+v", list.Records)
	}

	// zone_name 筛选：东区广场 → 三号、一号（不含二号）。
	recorder = do(handler, http.MethodGet, zonesPath(run.ID)+"?zone_name="+"东区广场", "")
	list = decodeZoneDensityList(t, recorder)
	if list.Meta.Total != 2 || len(list.Records) != 2 || list.Records[0].ID != third.ID || list.Records[1].ID != first.ID {
		t.Fatalf("zone_name filter: records = %d, total = %d; want 2 / 2 with newest first", len(list.Records), list.Meta.Total)
	}
}

// limit/offset 分页生效（缺省 limit 50，meta.total 保持总数）；非法
// limit/offset → 400。
func TestListZoneDensitiesPaginationAndInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		reportZoneDensity(t, handler, run.ID, fmt.Sprintf(
			`{"zone_name":"东区广场","people_count":%d}`, i))
	}

	recorder := do(handler, http.MethodGet, zonesPath(run.ID)+"?limit=2&offset=0", "")
	list := decodeZoneDensityList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	// reported_at DESC：最新上报的（53、52）排在最前。
	if list.Records[0].PeopleCount != 53 || list.Records[1].PeopleCount != 52 {
		t.Fatalf("first page not in reported_at DESC order: %+v", list.Records)
	}

	recorder = do(handler, http.MethodGet, zonesPath(run.ID)+"?limit=2&offset=52", "")
	list = decodeZoneDensityList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d; want 1 / 53", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, zonesPath(run.ID), "")
	list = decodeZoneDensityList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid limit":  "?limit=abc",
		"negative limit": "?limit=-1",
		"invalid offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, zonesPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/zone-densities/{zid} ──────────────────────────

// 存在的 (run, zid) 返回 200 完整对象（含 run_id/reported_at，id 为
// ULID）；zid 不存在、报告不属于该 run、run 不存在 → 404 {error}；GET
// 不受写门控（已完成 run 的报告仍 200）。
func TestGetZoneDensity(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	density := reportZoneDensity(t, handler, runA.ID, `{"zone_name":"东区广场","people_count":128}`)

	recorder := do(handler, http.MethodGet, zoneDensityItemPath(runA.ID, density.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	got := decodeZoneDensity(t, recorder)
	if !ulidPattern.MatchString(got.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", got.ID)
	}
	if got.ID != density.ID || got.RunID != runA.ID || got.ZoneName != "东区广场" || got.PeopleCount != 128 {
		t.Fatalf("get does not return the full object: %+v", got)
	}
	if got.ReportedAt == nil {
		t.Fatalf("get must return reported_at: %+v", got)
	}

	recorder = do(handler, http.MethodGet, zoneDensityItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown zid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, zoneDensityItemPath(runB.ID, density.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("report of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, zoneDensityItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", density.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// GET 不受写门控：已完成 run 的报告仍 200。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	densityCompletedRun := reportZoneDensity(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, zoneDensityItemPath(completed.ID, densityCompletedRun.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, zonesPath(completed.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET list on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── PUT /drills/{rid}/zone-densities/{zid} ──────────────────────────

// 更新 zone_name/people_count 生效；reported_at 刷新（晚于创建时刻）；
// id/run_id/created_at/created_by 保持不变、updated_at 刷新；PUT 后
// GET 反映更新。
func TestPutZoneDensity(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := reportZoneDensity(t, handler, run.ID,
		`{"zone_name":"东区广场","people_count":128,"created_by":"u-field-zhang"}`)

	time.Sleep(5 * time.Millisecond)
	recorder := do(handler, http.MethodPut, zoneDensityItemPath(run.ID, created.ID),
		`{"zone_name":"东区广场北侧","people_count":256,"created_by":"ignored"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeZoneDensity(t, recorder)
	if updated.ZoneName != "东区广场北侧" || updated.PeopleCount != 256 {
		t.Fatalf("updated fields not applied: %+v", updated)
	}
	if updated.ID != created.ID || updated.RunID != run.ID {
		t.Fatalf("id/run_id must be preserved: %+v", updated)
	}
	if updated.CreatedAt != created.CreatedAt {
		t.Fatalf("created_at must be preserved: %+v", updated)
	}
	if updated.CreatedBy != "u-field-zhang" {
		t.Fatalf("created_by must be preserved (body created_by ignored): %q", updated.CreatedBy)
	}
	if updated.ReportedAt == nil || *updated.ReportedAt == *created.ReportedAt {
		t.Fatalf("reported_at must be refreshed: %v -> %v", *created.ReportedAt, updated.ReportedAt)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must be refreshed: %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, zoneDensityItemPath(run.ID, created.ID), "")
	got := decodeZoneDensity(t, recorder)
	if got.ZoneName != "东区广场北侧" || got.PeopleCount != 256 {
		t.Fatalf("GET after PUT must reflect the update: %+v", got)
	}
}

// 失败路径（与 POST 一致覆盖）：缺 zone_name（含空白）、缺 people_count、
// 负值、非数字、非整数 → 400；zid 不存在 404；run 不存在 404（优先于门
// 控）；非 进行中 400。
func TestPutZoneDensityFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	created := reportZoneDensity(t, handler, run.ID, "")

	for name, body := range map[string]string{
		"missing zone_name":     `{"people_count":10}`,
		"blank zone_name":       `{"zone_name":"  ","people_count":10}`,
		"missing people_count":  `{"zone_name":"东区广场"}`,
		"negative people_count": `{"zone_name":"东区广场","people_count":-1}`,
		"non-number people":     `{"zone_name":"东区广场","people_count":"abc"}`,
		"fractional people":     `{"zone_name":"东区广场","people_count":2.5}`,
		"empty body":            ``,
	} {
		recorder := do(handler, http.MethodPut, zoneDensityItemPath(run.ID, created.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// zid 不存在 → 404。
	recorder := do(handler, http.MethodPut, zoneDensityItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), validZoneDensityBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown zid PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// run 不存在 → 404。
	recorder = do(handler, http.MethodPut, zoneDensityItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", created.ID), validZoneDensityBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run PUT: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 非 进行中 → 400。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	densityCompleted := reportZoneDensity(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPut, zoneDensityItemPath(completed.ID, densityCompleted.ID), validZoneDensityBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("PUT on 已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/zone-densities/{zid} ───────────────────────

// DELETE 204；DELETE 后 GET 404；zid 不存在 404；run 不存在 404（优先
// 于门控）；非 进行中 DELETE 400。
func TestDeleteZoneDensity(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	density := reportZoneDensity(t, handler, run.ID, "")

	recorder := do(handler, http.MethodDelete, zoneDensityItemPath(run.ID, density.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, zoneDensityItemPath(run.ID, density.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// zid 不存在 → 404。
	recorder = do(handler, http.MethodDelete, zoneDensityItemPath(run.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown zid DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// run 不存在 → 404。
	recorder = do(handler, http.MethodDelete, zoneDensityItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", density.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 非 进行中 → 400。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	densityCompleted := reportZoneDensity(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodDelete, zoneDensityItemPath(completed.ID, densityCompleted.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE on 已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 级联：删除 run 后 zone-density 报告随之清空 ───────────────────────

// 创建报告后 DELETE run（runs 路由），再 GET 单条返回 404、列表为空；
// 其他 run 的报告保留（内存行为与迁移 ON DELETE CASCADE 一致）。
func TestDeleteRunCascadesZoneDensities(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	densityA := reportZoneDensity(t, handler, runA.ID, `{"zone_name":"东区广场","people_count":128}`)
	densityB := reportZoneDensity(t, handler, runB.ID, `{"zone_name":"西区","people_count":30}`)

	recorder := do(handler, http.MethodDelete, runsPath+"/"+runA.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run: status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, zoneDensityItemPath(runA.ID, densityA.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET report after run delete: status = %d, want 404 (cascade)", recorder.Code)
	}
	decodeError(t, recorder)

	// runB 的报告保留。
	recorder = do(handler, http.MethodGet, zoneDensityItemPath(runB.ID, densityB.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET report of the other run: status = %d, want 200", recorder.Code)
	}
}

// ─── 405 与 Allow ────────────────────────────────────────────────────

// 集合只允许 GET/POST、条目只允许 GET/PUT/DELETE：其他方法 405 且带
// Allow 头，响应体为 JSON 错误。
func TestZoneDensitiesMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	density := reportZoneDensity(t, handler, run.ID, "")

	for _, testCase := range []struct {
		name   string
		method string
		target string
		allow  string
	}{
		{"collection PUT", http.MethodPut, zonesPath(run.ID), "GET, POST"},
		{"collection DELETE", http.MethodDelete, zonesPath(run.ID), "GET, POST"},
		{"collection PATCH", http.MethodPatch, zonesPath(run.ID), "GET, POST"},
		{"item POST", http.MethodPost, zoneDensityItemPath(run.ID, density.ID), "GET, PUT, DELETE"},
		{"item PATCH", http.MethodPatch, zoneDensityItemPath(run.ID, density.ID), "GET, PUT, DELETE"},
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

// 对 zone-densities 路径的 OPTIONS 预检：204 且 Access-Control-Allow-
// Methods 含 POST/PUT/DELETE（写方法可被浏览器调用）。
func TestZoneDensitiesCORSPreflight(t *testing.T) {
	req := httptest.NewRequest(http.MethodOptions, zonesPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), nil)
	req.Header.Set("Origin", "https://allowed.example")
	recorder := httptest.NewRecorder()
	testMux([]string{"https://allowed.example"}).ServeHTTP(recorder, req)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("preflight status = %d, want 204", recorder.Code)
	}
	methods := recorder.Header().Get("Access-Control-Allow-Methods")
	for _, method := range []string{"POST", "PUT", "DELETE"} {
		if !strings.Contains(methods, method) {
			t.Fatalf("Access-Control-Allow-Methods = %q, want it to contain %s", methods, method)
		}
	}
}
