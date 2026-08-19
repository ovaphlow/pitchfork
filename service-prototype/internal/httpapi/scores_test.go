package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// scoresPath builds the score collection path of the given run.
func scoresPath(runID string) string {
	return "/crate-api/prototype/v1/evaluation/runs/" + runID + "/scores"
}

// scoreItemPath builds the score item path of the given run and score.
func scoreItemPath(runID, scoreID string) string {
	return scoresPath(runID) + "/" + scoreID
}

// scoreJSON mirrors the score response for assertions.
type scoreJSON struct {
	ID          string `json:"id"`
	RunID       string `json:"run_id"`
	IndicatorID string `json:"indicator_id"`
	ScoreType   string `json:"score_type"`
	Rater       string `json:"rater"`
	Target      string `json:"target"`
	Score       int    `json:"score"`
	Comment     string `json:"comment"`
	CreatedBy   string `json:"created_by"`
	CreatedAt   string `json:"created_at"`
	UpdatedAt   string `json:"updated_at"`
}

type scoreListJSON struct {
	Records []scoreJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeScore(t *testing.T, recorder *httptest.ResponseRecorder) scoreJSON {
	t.Helper()
	var score scoreJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &score); err != nil {
		t.Fatalf("body %q is not a score JSON: %v", recorder.Body.String(), err)
	}
	return score
}

func decodeScoreList(t *testing.T, recorder *httptest.ResponseRecorder) scoreListJSON {
	t.Helper()
	var list scoreListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// postScore POSTs a score body and asserts 201; returns the created
// score.
func postScore(t *testing.T, handler http.Handler, runID, body string) scoreJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, scoresPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeScore(t, recorder)
}

// scoreFixture creates the owning run and the indicator of the score
// tests through the API and returns their ids.
func scoreFixture(t *testing.T, handler http.Handler) (runID, indicatorID string) {
	t.Helper()
	scenario := createScenario(t, handler, validScenarioBody)
	run := createRun(t, handler, scenario.ID, "")
	indicator := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度"}`)
	return run.ID, indicator.ID
}

// expertScoreBody builds a 专家评分 body; the target is carried on
// purpose so the forced-empty rule can be asserted.
func expertScoreBody(indicatorID string) string {
	return fmt.Sprintf(`{"indicator_id":%q,"score_type":"专家评分","rater":"评审专家A","target":"不应存在的目标","score":92,"comment":"处置得当","created_by":"admin"}`, indicatorID)
}

// assertScoreCreated checks the field contract of a created expert
// score: ULID id, echoed run/indicator, the forced-empty target, the
// echoed rater/score/comment/created_by and server-set timestamps.
func assertScoreCreated(t *testing.T, score scoreJSON, runID, indicatorID string) {
	t.Helper()
	if !ulidPattern.MatchString(score.ID) {
		t.Errorf("id = %q, want a 26-character Crockford Base32 ULID", score.ID)
	}
	if score.RunID != runID || score.IndicatorID != indicatorID {
		t.Errorf("run_id/indicator_id = %q/%q, want %q/%q", score.RunID, score.IndicatorID, runID, indicatorID)
	}
	if score.ScoreType != "专家评分" {
		t.Errorf("score_type = %q, want 专家评分", score.ScoreType)
	}
	if score.Rater != "评审专家A" {
		t.Errorf("rater = %q, want 评审专家A", score.Rater)
	}
	if score.Target != "" {
		t.Errorf("target = %q, want empty (forced for 专家评分)", score.Target)
	}
	if score.Score != 92 {
		t.Errorf("score = %d, want 92", score.Score)
	}
	if score.Comment != "处置得当" || score.CreatedBy != "admin" {
		t.Errorf("comment/created_by = %q/%q, want 处置得当/admin", score.Comment, score.CreatedBy)
	}
	if score.CreatedAt == "" || score.UpdatedAt == "" {
		t.Errorf("timestamps = %q/%q, want server-set values", score.CreatedAt, score.UpdatedAt)
	}
}

// ─── POST /evaluation/runs/{rid}/scores ──────────────────────────────

// POST 成功 201 返回完整对象：id 为服务端生成的 26 位 Crockford Base32
// ULID、created_at/updated_at 服务端设置、created_by/comment 携带值写入，
// 专家评分提交非空 target 时服务层强制置空；GET /{sid} 200 回显与 POST
// 响应一致。自评/互评的 target 透传回显。
func TestScoreCreateAndEcho(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)

	created := postScore(t, handler, runID, expertScoreBody(indicatorID))
	assertScoreCreated(t, created, runID, indicatorID)

	getRecorder := get(handler, scoreItemPath(runID, created.ID), nil)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", getRecorder.Code, getRecorder.Body.String())
	}
	echoed := decodeScore(t, getRecorder)
	if echoed.ID != created.ID || echoed.RunID != created.RunID || echoed.IndicatorID != created.IndicatorID ||
		echoed.ScoreType != created.ScoreType || echoed.Rater != created.Rater || echoed.Target != created.Target ||
		echoed.Score != created.Score || echoed.Comment != created.Comment || echoed.CreatedBy != created.CreatedBy ||
		echoed.CreatedAt != created.CreatedAt || echoed.UpdatedAt != created.UpdatedAt {
		t.Fatalf("GET echo diverges from POST response: %+v vs %+v", echoed, created)
	}

	// 自评：target 必填且透传。
	self := postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"自评","rater":"值班员","target":"班组A","score":80}`, indicatorID))
	if self.Target != "班组A" {
		t.Errorf("self target = %q, want 班组A (passthrough)", self.Target)
	}
	if self.Comment != "" || self.CreatedBy != "" {
		t.Errorf("self comment/created_by = %q/%q, want empty defaults", self.Comment, self.CreatedBy)
	}
	// 互评：target 透传。
	peer := postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"互评","rater":"互评员","target":"互评组B","score":85}`, indicatorID))
	if peer.Target != "互评组B" {
		t.Errorf("peer target = %q, want 互评组B (passthrough)", peer.Target)
	}
}

// ─── PUT /evaluation/runs/{rid}/scores/{sid} ─────────────────────────

// PUT 成功 200 返回更新后对象：请求字段生效，缺省字段按默认值重置
// （comment→”、created_by→”），target 对专家评分强制置空，created_at
// 保留、updated_at 刷新；run_id/indicator_id 不可修改（请求体携带亦忽
// 略）；PUT 后 GET 反映更新。
func TestScoreUpdate(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	created := postScore(t, handler, runID, expertScoreBody(indicatorID))

	// 请求体携带 indicator_id 与目标值：均被忽略/置空，仅请求字段生效。
	putRecorder := do(handler, http.MethodPut, scoreItemPath(runID, created.ID),
		fmt.Sprintf(`{"indicator_id":%q,"score_type":"专家评分","rater":"评审专家B","target":"仍不应存在","score":95}`, "06G01KFRJTE84EP3234FBD2YD4"))
	if putRecorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", putRecorder.Code, putRecorder.Body.String())
	}
	updated := decodeScore(t, putRecorder)
	if updated.ID != created.ID || updated.RunID != runID || updated.IndicatorID != indicatorID {
		t.Errorf("id/run_id/indicator_id = %q/%q/%q, want preserved %q/%q/%q", updated.ID, updated.RunID, updated.IndicatorID, created.ID, runID, indicatorID)
	}
	if updated.Rater != "评审专家B" || updated.Score != 95 {
		t.Errorf("rater/score = %q/%d, want 评审专家B/95", updated.Rater, updated.Score)
	}
	if updated.Target != "" {
		t.Errorf("target = %q, want empty (forced for 专家评分)", updated.Target)
	}
	if updated.Comment != "" || updated.CreatedBy != "" {
		t.Errorf("comment/created_by = %q/%q, want empty (omitted fields reset)", updated.Comment, updated.CreatedBy)
	}
	if updated.CreatedAt != created.CreatedAt {
		t.Errorf("created_at = %q, want %q (preserved)", updated.CreatedAt, created.CreatedAt)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Errorf("updated_at = %q, want a refreshed value", updated.UpdatedAt)
	}

	getRecorder := get(handler, scoreItemPath(runID, created.ID), nil)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", getRecorder.Code, getRecorder.Body.String())
	}
	echoed := decodeScore(t, getRecorder)
	if echoed.Rater != "评审专家B" || echoed.Score != 95 {
		t.Errorf("GET after PUT = %q/%d, want the updated values", echoed.Rater, echoed.Score)
	}
	if echoed.UpdatedAt != updated.UpdatedAt {
		t.Errorf("GET updated_at = %q, want %q (PUT response echoed)", echoed.UpdatedAt, updated.UpdatedAt)
	}

	// 自评 PUT：target 必填、透传；comment 缺省重置为 ”。
	putRecorder = do(handler, http.MethodPut, scoreItemPath(runID, created.ID),
		`{"score_type":"自评","rater":"值班员","target":"班组C","score":81}`)
	if putRecorder.Code != http.StatusOK {
		t.Fatalf("PUT self status = %d, want 200; body = %s", putRecorder.Code, putRecorder.Body.String())
	}
	asSelf := decodeScore(t, putRecorder)
	if asSelf.ScoreType != "自评" || asSelf.Target != "班组C" || asSelf.Score != 81 || asSelf.Comment != "" {
		t.Errorf("PUT self = %+v, want 自评/班组C/81 with comment reset to ''", asSelf)
	}
}

// ─── DELETE /evaluation/runs/{rid}/scores/{sid} ──────────────────────

// DELETE 成功 204，DELETE 后 GET 404；DELETE 不存在的 sid 404。
func TestScoreDelete(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	created := postScore(t, handler, runID, expertScoreBody(indicatorID))

	deleteRecorder := do(handler, http.MethodDelete, scoreItemPath(runID, created.ID), "")
	if deleteRecorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", deleteRecorder.Code, deleteRecorder.Body.String())
	}
	getRecorder := get(handler, scoreItemPath(runID, created.ID), nil)
	if getRecorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE status = %d, want 404", getRecorder.Code)
	}
	decodeErrorPayload(t, getRecorder)

	again := do(handler, http.MethodDelete, scoreItemPath(runID, created.ID), "")
	if again.Code != http.StatusNotFound {
		t.Fatalf("DELETE again status = %d, want 404", again.Code)
	}
	decodeErrorPayload(t, again)
}

// ─── 失败路径（POST 与 PUT 双入口） ──────────────────────────────────

// POST 失败路径：缺 rater（含空串）、缺 score、非法/缺失 score_type、
// score 越界、自评/互评缺 target（含空串）均 400；run 不存在、indicator
// 不存在 404；错误响应体统一 { "error": ... }。
func TestScoreCreateValidation(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	valid := func() string {
		return fmt.Sprintf(`{"indicator_id":%q,"score_type":"自评","rater":"值班员","target":"班组A","score":80}`, indicatorID)
	}

	cases := []struct {
		name  string
		runID string
		body  string
		code  int
	}{
		{"missing rater", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","target":"班组A","score":80}`, http.StatusBadRequest},
		{"blank rater", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","rater":"   ","target":"班组A","score":80}`, http.StatusBadRequest},
		{"missing score", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","rater":"值班员","target":"班组A"}`, http.StatusBadRequest},
		{"score below zero", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","rater":"值班员","target":"班组A","score":-1}`, http.StatusBadRequest},
		{"score above 100", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","rater":"值班员","target":"班组A","score":101}`, http.StatusBadRequest},
		{"invalid score_type", runID, `{"indicator_id":"` + indicatorID + `","score_type":"其他","rater":"值班员","target":"班组A","score":80}`, http.StatusBadRequest},
		{"missing score_type", runID, `{"indicator_id":"` + indicatorID + `","rater":"值班员","target":"班组A","score":80}`, http.StatusBadRequest},
		{"self missing target", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","rater":"值班员","score":80}`, http.StatusBadRequest},
		{"self blank target", runID, `{"indicator_id":"` + indicatorID + `","score_type":"自评","rater":"值班员","target":"  ","score":80}`, http.StatusBadRequest},
		{"peer missing target", runID, `{"indicator_id":"` + indicatorID + `","score_type":"互评","rater":"互评员","score":80}`, http.StatusBadRequest},
		{"malformed body", runID, `{"score_type":`, http.StatusBadRequest},
		{"unknown run", "06G01KFRJTE84EP3234FBD2YD4", valid(), http.StatusNotFound},
		{"unknown indicator", runID, `{"indicator_id":"06G01KFRJTE84EP3234FBD2YD4","score_type":"自评","rater":"值班员","target":"班组A","score":80}`, http.StatusNotFound},
	}
	for _, tc := range cases {
		recorder := do(handler, http.MethodPost, scoresPath(tc.runID), tc.body)
		if recorder.Code != tc.code {
			t.Errorf("%s: status = %d, want %d; body = %s", tc.name, recorder.Code, tc.code, recorder.Body.String())
			continue
		}
		if message := decodeErrorPayload(t, recorder); message == "" {
			t.Errorf("%s: empty error message", tc.name)
		}
	}
}

// PUT 失败路径与 POST 一致；sid 不存在 404；run 不存在 404。
func TestScoreUpdateValidation(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	created := postScore(t, handler, runID, expertScoreBody(indicatorID))

	cases := []struct {
		name string
		id   string
		body string
		code int
	}{
		{"missing rater", created.ID, `{"score_type":"专家评分","score":95}`, http.StatusBadRequest},
		{"blank rater", created.ID, `{"score_type":"专家评分","rater":"  ","score":95}`, http.StatusBadRequest},
		{"missing score", created.ID, `{"score_type":"专家评分","rater":"评审专家B"}`, http.StatusBadRequest},
		{"score below zero", created.ID, `{"score_type":"专家评分","rater":"评审专家B","score":-1}`, http.StatusBadRequest},
		{"score above 100", created.ID, `{"score_type":"专家评分","rater":"评审专家B","score":150}`, http.StatusBadRequest},
		{"invalid score_type", created.ID, `{"score_type":"其他","rater":"评审专家B","score":95}`, http.StatusBadRequest},
		{"missing score_type", created.ID, `{"rater":"评审专家B","score":95}`, http.StatusBadRequest},
		{"self missing target", created.ID, `{"score_type":"自评","rater":"值班员","score":80}`, http.StatusBadRequest},
		{"peer blank target", created.ID, `{"score_type":"互评","rater":"互评员","target":"  ","score":80}`, http.StatusBadRequest},
		{"unknown sid", "06G01KFRJTE84EP3234FBD2YD4", `{"score_type":"专家评分","rater":"评审专家B","score":95}`, http.StatusNotFound},
		{"unknown run", created.ID, `{"score_type":"专家评分","rater":"评审专家B","score":95}`, http.StatusNotFound},
		{"malformed body", created.ID, `{"score_type":`, http.StatusBadRequest},
	}
	for _, tc := range cases {
		target := scoreItemPath(runID, tc.id)
		if tc.name == "unknown run" {
			target = scoreItemPath("06G01KFRJTE84EP3234FBD2YD4", created.ID)
		}
		recorder := do(handler, http.MethodPut, target, tc.body)
		if recorder.Code != tc.code {
			t.Errorf("%s: status = %d, want %d; body = %s", tc.name, recorder.Code, tc.code, recorder.Body.String())
			continue
		}
		if message := decodeErrorPayload(t, recorder); message == "" {
			t.Errorf("%s: empty error message", tc.name)
		}
	}
}

// 专家评分重复：POST 同 (run, indicator) 已有专家评分 400 且错误消息为
// 「该演练与指标下已存在专家评分，请用 PUT 更新」；自评/互评多条不查重；
// PUT 将记录改为专家评分后与同 (run, indicator) 其他记录冲突（排除自
// 身）400。
func TestScoreExpertDuplicate(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	expert := postScore(t, handler, runID, expertScoreBody(indicatorID))

	// POST 重复专家评分 → 400 + 钉死文案。
	recorder := do(handler, http.MethodPost, scoresPath(runID), expertScoreBody(indicatorID))
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("duplicate POST status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	if message := decodeErrorPayload(t, recorder); message != "该演练与指标下已存在专家评分，请用 PUT 更新" {
		t.Errorf("error message = %q, want 该演练与指标下已存在专家评分，请用 PUT 更新", message)
	}

	// 自评/互评多条不查重。
	self := postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"自评","rater":"值班员","target":"班组A","score":80}`, indicatorID))
	postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"互评","rater":"互评员","target":"班组B","score":85}`, indicatorID))

	// 把自评记录改为专家评分：与既有专家评分冲突（排除自身后仍存在）→ 400。
	recorder = do(handler, http.MethodPut, scoreItemPath(runID, self.ID),
		`{"score_type":"专家评分","rater":"值班员","score":90}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("PUT self->expert status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	if message := decodeErrorPayload(t, recorder); message != "该演练与指标下已存在专家评分，请用 PUT 更新" {
		t.Errorf("error message = %q, want 该演练与指标下已存在专家评分，请用 PUT 更新", message)
	}

	// 专家评分原地 PUT（排除自身）不冲突。
	recorder = do(handler, http.MethodPut, scoreItemPath(runID, expert.ID),
		`{"score_type":"专家评分","rater":"评审专家A","score":95}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT expert in place status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}

	// 另一指标不受影响：该 run 下可再建专家评分。
	indicator2 := postIndicator(t, handler, `{"dimension":"处置规范性","title":"处置流程规范性"}`)
	if recorder := do(handler, http.MethodPost, scoresPath(runID),
		fmt.Sprintf(`{"indicator_id":%q,"score_type":"专家评分","rater":"评审专家A","score":88}`, indicator2.ID)); recorder.Code != http.StatusCreated {
		t.Fatalf("expert score of another indicator: status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── GET /evaluation/runs/{rid}/scores ───────────────────────────────

// 空列表 {records:[], meta:{total:0}}；rid 不存在 404。
func TestScoreListEmpty(t *testing.T) {
	handler := testMux(nil)
	runID, _ := scoreFixture(t, handler)

	recorder := get(handler, scoresPath(runID), nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeScoreList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("records/total = %d/%d, want 0/0", len(list.Records), list.Meta.Total)
	}

	missing := get(handler, scoresPath("06G01KFRJTE84EP3234FBD2YD4"), nil)
	if missing.Code != http.StatusNotFound {
		t.Fatalf("unknown run list status = %d, want 404", missing.Code)
	}
	decodeErrorPayload(t, missing)
}

// 列表：score_type 与 indicator_id 筛选生效（含组合筛选），limit/offset
// 分页生效（meta.total 为分页前总数），排序口径 created_at ASC、id ASC
// 可断言。
func TestScoreListFilterPaginationSort(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	indicator2 := postIndicator(t, handler, `{"dimension":"处置规范性","title":"处置流程规范性"}`)

	// 三条同一指标 + 一条另一指标：列表顺序为创建顺序（created_at ASC）。
	expert := postScore(t, handler, runID, expertScoreBody(indicatorID))
	self := postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"自评","rater":"值班员","target":"班组A","score":80}`, indicatorID))
	peer := postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"互评","rater":"互评员","target":"班组B","score":85}`, indicatorID))
	other := postScore(t, handler, runID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"专家评分","rater":"专家B","score":88}`, indicator2.ID))

	recorder := get(handler, scoresPath(runID), nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeScoreList(t, recorder)
	if list.Meta.Total != 4 || len(list.Records) != 4 {
		t.Fatalf("total/records = %d/%d, want 4/4", list.Meta.Total, len(list.Records))
	}
	wantOrder := []string{expert.ID, self.ID, peer.ID, other.ID}
	for index, want := range wantOrder {
		if list.Records[index].ID != want {
			t.Fatalf("sort order diverges at %d: got %q, want %q (created_at ASC)", index, list.Records[index].ID, want)
		}
	}

	// score_type 筛选。
	filtered := get(handler, scoresPath(runID)+"?score_type="+url.QueryEscape("自评"), nil)
	if filtered.Code != http.StatusOK {
		t.Fatalf("score_type filter status = %d, want 200", filtered.Code)
	}
	filteredList := decodeScoreList(t, filtered)
	if filteredList.Meta.Total != 1 || len(filteredList.Records) != 1 || filteredList.Records[0].ID != self.ID {
		t.Fatalf("score_type filter = %d/%d, want the single 自评 record", filteredList.Meta.Total, len(filteredList.Records))
	}

	// indicator_id 筛选。
	filtered = get(handler, scoresPath(runID)+"?indicator_id="+indicator2.ID, nil)
	if filtered.Code != http.StatusOK {
		t.Fatalf("indicator_id filter status = %d, want 200", filtered.Code)
	}
	filteredList = decodeScoreList(t, filtered)
	if filteredList.Meta.Total != 1 || len(filteredList.Records) != 1 || filteredList.Records[0].ID != other.ID {
		t.Fatalf("indicator_id filter = %d/%d, want the single 专家B record", filteredList.Meta.Total, len(filteredList.Records))
	}

	// 组合筛选：指标一 + 专家评分 → 1 条。
	combined := get(handler, scoresPath(runID)+"?score_type="+url.QueryEscape("专家评分")+"&indicator_id="+indicatorID, nil)
	if combined.Code != http.StatusOK {
		t.Fatalf("combined filter status = %d, want 200", combined.Code)
	}
	combinedList := decodeScoreList(t, combined)
	if combinedList.Meta.Total != 1 || len(combinedList.Records) != 1 || combinedList.Records[0].ID != expert.ID {
		t.Fatalf("combined filter = %d/%d, want the single expert record of 指标一", combinedList.Meta.Total, len(combinedList.Records))
	}

	// limit/offset 分页：limit=2&offset=1 回第 2..3 条，total 仍为 4。
	page := get(handler, scoresPath(runID)+"?limit=2&offset=1", nil)
	if page.Code != http.StatusOK {
		t.Fatalf("page status = %d, want 200", page.Code)
	}
	pageList := decodeScoreList(t, page)
	if pageList.Meta.Total != 4 || len(pageList.Records) != 2 {
		t.Fatalf("page total/records = %d/%d, want 4/2", pageList.Meta.Total, len(pageList.Records))
	}
	if pageList.Records[0].ID != self.ID || pageList.Records[1].ID != peer.ID {
		t.Errorf("page ids = %q..%q, want the second and third rows", pageList.Records[0].ID, pageList.Records[1].ID)
	}
}

// 列表筛选参数一致覆盖：score_type 传非 3 值 400、limit/offset 非非负整
// 数 400，错误响应体统一 { "error": ... }。
func TestScoreListFilterValidation(t *testing.T) {
	handler := testMux(nil)
	runID, _ := scoreFixture(t, handler)
	for _, target := range []string{
		scoresPath(runID) + "?score_type=其他",
		scoresPath(runID) + "?score_type=" + url.QueryEscape("评分"),
		scoresPath(runID) + "?limit=-1",
		scoresPath(runID) + "?limit=abc",
		scoresPath(runID) + "?offset=-1",
		scoresPath(runID) + "?offset=1.5",
	} {
		recorder := get(handler, target, nil)
		if recorder.Code != http.StatusBadRequest {
			t.Errorf("%s: status = %d, want 400; body = %s", target, recorder.Code, recorder.Body.String())
			continue
		}
		if message := decodeErrorPayload(t, recorder); message == "" {
			t.Errorf("%s: empty error message", target)
		}
	}
}

// ─── 跨切面：run 删除级联 / 指标引用检查 ──────────────────────────────

// run 删除级联：经 drills 服务删除 run 后该 run 的 scores 随 run 一起消
// 失（DeleteScoresByRun 接入生效）——scores 列表与 GET /{sid} 均 404（run
// 已不存在）；其他 run 的评分不受影响。
func TestScoreRunDeleteCascades(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)
	runA := createRun(t, handler, scenario.ID, "")
	runB := createRun(t, handler, scenario.ID, "")
	indicator := postIndicator(t, handler, `{"dimension":"响应速度","title":"预警响应速度"}`)

	scoreA := postScore(t, handler, runA.ID, expertScoreBody(indicator.ID))
	scoreB := postScore(t, handler, runB.ID, fmt.Sprintf(`{"indicator_id":%q,"score_type":"专家评分","rater":"专家B","score":88}`, indicator.ID))

	// 删除 runA → 204。
	recorder := do(handler, http.MethodDelete, "/crate-api/prototype/v1/drills/"+runA.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE run status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	// runA 的 scores 随 run 消失：列表 404（run 不存在）、GET /{sid} 404。
	recorder = get(handler, scoresPath(runA.ID), nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("runA scores list status = %d, want 404", recorder.Code)
	}
	recorder = get(handler, scoreItemPath(runA.ID, scoreA.ID), nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("runA score item status = %d, want 404", recorder.Code)
	}
	recorder = do(handler, http.MethodDelete, scoreItemPath(runA.ID, scoreA.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("runA score DELETE status = %d, want 404", recorder.Code)
	}

	// runB 的评分不受影响。
	recorder = get(handler, scoresPath(runB.ID), nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("runB scores list status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeScoreList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != scoreB.ID {
		t.Fatalf("runB scores = %d/%d, want the single runB record", list.Meta.Total, len(list.Records))
	}
}

// 指标引用检查接入：删除已被评分记录引用的指标（真实引用源）返回 400 且
// 错误消息为「指标已被评分引用，请先清理评分」，指标仍存在；评分清理后
// 删除 204。
func TestIndicatorDeleteReferencedByScores(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	score := postScore(t, handler, runID, expertScoreBody(indicatorID))

	recorder := do(handler, http.MethodDelete, "/crate-api/prototype/v1/evaluation/indicators/"+indicatorID, "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("DELETE referenced indicator status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	if message := decodeErrorPayload(t, recorder); message != "指标已被评分引用，请先清理评分" {
		t.Errorf("error message = %q, want 指标已被评分引用，请先清理评分", message)
	}
	// 指标仍存在。
	getRecorder := get(handler, "/crate-api/prototype/v1/evaluation/indicators/"+indicatorID, nil)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET indicator after rejected DELETE status = %d, want 200 (indicator must still exist)", getRecorder.Code)
	}

	// 评分删除后引用数归零，指标可删除。
	recorder = do(handler, http.MethodDelete, scoreItemPath(runID, score.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE score status = %d, want 204", recorder.Code)
	}
	recorder = do(handler, http.MethodDelete, "/crate-api/prototype/v1/evaluation/indicators/"+indicatorID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE unreferenced indicator status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── 其他方法 ────────────────────────────────────────────────────────

// 集合与单条路由的其他方法返回 405 JSON 并带 Allow。
func TestScoreMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	runID, indicatorID := scoreFixture(t, handler)
	created := postScore(t, handler, runID, expertScoreBody(indicatorID))

	collectionRecorder := do(handler, http.MethodDelete, scoresPath(runID), "")
	if collectionRecorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("collection DELETE status = %d, want 405", collectionRecorder.Code)
	}
	if allow := collectionRecorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Errorf("collection Allow = %q, want GET and POST", allow)
	}

	itemRecorder := do(handler, http.MethodPost, scoreItemPath(runID, created.ID), `{}`)
	if itemRecorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("item POST status = %d, want 405", itemRecorder.Code)
	}
	if allow := itemRecorder.Header().Get("Allow"); !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Errorf("item Allow = %q, want GET, PUT and DELETE", allow)
	}
}
