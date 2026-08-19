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

// ordersPath builds the order collection path of a run.
func ordersPath(runID string) string {
	return fmt.Sprintf("%s/%s/orders", runsPath, runID)
}

// orderItemPath builds the order item path of a (run, order) pair.
func orderItemPath(runID, orderID string) string {
	return ordersPath(runID) + "/" + orderID
}

// orderJSON mirrors the order response for assertions.
type orderJSON struct {
	ID          string  `json:"id"`
	RunID       string  `json:"run_id"`
	Title       string  `json:"title"`
	Content     string  `json:"content"`
	Priority    string  `json:"priority"`
	TargetType  string  `json:"target_type"`
	TargetName  string  `json:"target_name"`
	Status      string  `json:"status"`
	Feedback    string  `json:"feedback"`
	Deadline    *string `json:"deadline"`
	IssuedAt    *string `json:"issued_at"`
	CompletedAt *string `json:"completed_at"`
	CreatedBy   string  `json:"created_by"`
	CreatedAt   string  `json:"created_at"`
	UpdatedAt   string  `json:"updated_at"`
}

type orderListJSON struct {
	Records []orderJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeOrder(t *testing.T, recorder *httptest.ResponseRecorder) orderJSON {
	t.Helper()
	var order orderJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &order); err != nil {
		t.Fatalf("body %q is not an order JSON: %v", recorder.Body.String(), err)
	}
	return order
}

func decodeOrderList(t *testing.T, recorder *httptest.ResponseRecorder) orderListJSON {
	t.Helper()
	var list orderListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// validOrderBody is a minimal valid order creation body.
const validOrderBody = `{"title":"疏散东区游客","content":"引导东区游客经 3 号出口疏散","target_type":"部门","target_name":"疏散组"}`

// createOrder posts the given body to the run's collection and asserts
// 201; returns the created order.
func createOrder(t *testing.T, handler http.Handler, runID, body string) orderJSON {
	t.Helper()
	if body == "" {
		body = validOrderBody
	}
	recorder := do(handler, http.MethodPost, ordersPath(runID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeOrder(t, recorder)
}

// ─── POST /drills/{rid}/orders ───────────────────────────────────────

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID，
// run_id 取自路径回显（body 传入 run_id 被忽略），status 缺省 待接收、
// priority 缺省 普通、feedback 缺省 ''、deadline 为 null、issued_at 服务
// 端创建时设置非空、completed_at 为 null、created_by 透传（缺省空串）、
// created_at/updated_at 服务端设置。
func TestCreateOrderSuccess(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, ordersPath(run.ID),
		`{"title":"疏散东区游客","content":"引导东区游客经 3 号出口疏散","target_type":"部门","target_name":"疏散组","run_id":"ignored","created_by":"u-commander"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	order := decodeOrder(t, recorder)
	if !ulidPattern.MatchString(order.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", order.ID)
	}
	if order.RunID != run.ID {
		t.Fatalf("run_id = %q, want the run from the path (body run_id ignored)", order.RunID)
	}
	if order.Title != "疏散东区游客" || order.Content != "引导东区游客经 3 号出口疏散" {
		t.Fatalf("title/content = %q / %q, want the input echoed", order.Title, order.Content)
	}
	if order.Priority != "普通" {
		t.Fatalf("priority = %q, want 普通 (default)", order.Priority)
	}
	if order.TargetType != "部门" || order.TargetName != "疏散组" {
		t.Fatalf("target = %q / %q, want the input echoed", order.TargetType, order.TargetName)
	}
	if order.Status != "待接收" {
		t.Fatalf("status = %q, want 待接收 (default)", order.Status)
	}
	if order.Feedback != "" {
		t.Fatalf("feedback = %q, want an empty default", order.Feedback)
	}
	if order.Deadline != nil {
		t.Fatalf("deadline = %v, want null when omitted", order.Deadline)
	}
	if order.IssuedAt == nil || *order.IssuedAt == "" {
		t.Fatalf("issued_at = %v, want a non-empty server-set instant", order.IssuedAt)
	}
	if order.CompletedAt != nil {
		t.Fatalf("completed_at = %v, want null for a fresh order", order.CompletedAt)
	}
	if order.CreatedBy != "u-commander" {
		t.Fatalf("created_by = %q, want u-commander", order.CreatedBy)
	}
	if order.CreatedAt == "" || order.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", order)
	}
}

// 显式字段：priority/status=待接收/feedback/deadline(RFC3339)/created_by
// 原样写入。
func TestCreateOrderExplicitFields(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodPost, ordersPath(run.ID),
		`{"title":"封控西区","content":"对西区实施临时封控","priority":"特急","target_type":"小组","target_name":"安保一组","status":"待接收","feedback":"已转达","deadline":"2026-08-03T18:00:00+08:00","created_by":"u-commander"}`)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	order := decodeOrder(t, recorder)
	if order.Priority != "特急" || order.TargetType != "小组" || order.TargetName != "安保一组" ||
		order.Status != "待接收" || order.Feedback != "已转达" || order.CreatedBy != "u-commander" {
		t.Fatalf("explicit fields not echoed: %+v", order)
	}
	if order.Deadline == nil || *order.Deadline != "2026-08-03T18:00:00+08:00" {
		t.Fatalf("deadline = %v, want the RFC3339 instant echoed", order.Deadline)
	}
}

// 失败路径：缺必填 title/content/target_type/target_name（含空白）、非法
// priority/target_type/status、POST status 非 待接收、deadline 非 RFC3339
// （含数字、null 之外的非法串）、请求体非法 → 400 {error}，POST 与 PUT
// 两入口一致覆盖（PUT 见 TestPutOrderFailures）。
func TestCreateOrderFailures(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	for name, body := range map[string]string{
		"missing title":       `{"content":"内容","target_type":"部门","target_name":"疏散组"}`,
		"blank title":         `{"title":"  ","content":"内容","target_type":"部门","target_name":"疏散组"}`,
		"missing content":     `{"title":"标题","target_type":"部门","target_name":"疏散组"}`,
		"blank content":       `{"title":"标题","content":"\t","target_type":"部门","target_name":"疏散组"}`,
		"missing target_type": `{"title":"标题","content":"内容","target_name":"疏散组"}`,
		"blank target_type":   `{"title":"标题","content":"内容","target_type":"","target_name":"疏散组"}`,
		"missing target_name": `{"title":"标题","content":"内容","target_type":"部门"}`,
		"blank target_name":   `{"title":"标题","content":"内容","target_type":"部门","target_name":" "}`,
		"invalid priority":    `{"title":"标题","content":"内容","target_type":"部门","target_name":"疏散组","priority":"加急"}`,
		"invalid target_type": `{"title":"标题","content":"内容","target_type":"班组","target_name":"疏散组"}`,
		"invalid status":      `{"title":"标题","content":"内容","target_type":"部门","target_name":"疏散组","status":"草稿"}`,
		"status 已接收":          `{"title":"标题","content":"内容","target_type":"部门","target_name":"疏散组","status":"已接收"}`,
		"deadline number":     `{"title":"标题","content":"内容","target_type":"部门","target_name":"疏散组","deadline":123}`,
		"deadline bad format": `{"title":"标题","content":"内容","target_type":"部门","target_name":"疏散组","deadline":"明天"}`,
		"deadline empty":      `{"title":"标题","content":"内容","target_type":"部门","target_name":"疏散组","deadline":""}`,
		"malformed body":      `{"title":`,
	} {
		recorder := do(handler, http.MethodPost, ordersPath(run.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 空 body → 400。
	recorder := do(handler, http.MethodPost, ordersPath(run.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("empty body: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 状态约束：run 不存在 404；仅 进行中 可 POST（未开始/已完成 400）。
func TestCreateOrderRunState(t *testing.T) {
	handler := testMux(nil)
	scenario := createScenario(t, handler, validScenarioBody)

	// run 不存在 → 404。
	recorder := do(handler, http.MethodPost, ordersPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), validOrderBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 未开始 → 400。
	notStarted := createRun(t, handler, scenario.ID, "")
	recorder = do(handler, http.MethodPost, ordersPath(notStarted.ID), validOrderBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 进行中 → 201。
	inProgress := createRun(t, handler, scenario.ID, "")
	startRun(t, handler, inProgress.ID)
	recorder = do(handler, http.MethodPost, ordersPath(inProgress.ID), validOrderBody)
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
	recorder = do(handler, http.MethodPost, ordersPath(completed.ID), validOrderBody)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /drills/{rid}/orders ────────────────────────────────────────

// 空列表返回 {records:[], meta:{total:0}}；run 不存在 404。
func TestListOrdersEmptyAndMissingRun(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	recorder := do(handler, http.MethodGet, ordersPath(run.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	list := decodeOrderList(t, recorder)
	if list.Records == nil || len(list.Records) != 0 || list.Meta.Total != 0 {
		t.Fatalf("empty list = %+v, want records [] and total 0", list)
	}

	recorder = do(handler, http.MethodGet, ordersPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 排序 created_at DESC, id DESC；status/priority/target_type 筛选生效
// （组合也生效）；meta.total 为筛选后的总数。
func TestListOrdersSortedAndFiltered(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)

	// 创建 3 条指令：普通/部门、紧急/小组、特急/个人。
	createOrder(t, handler, run.ID, `{"title":"一号","content":"内容","target_type":"部门","target_name":"疏散组","priority":"普通"}`)
	time.Sleep(5 * time.Millisecond)
	urgent := createOrder(t, handler, run.ID, `{"title":"二号","content":"内容","target_type":"小组","target_name":"安保一组","priority":"紧急"}`)
	time.Sleep(5 * time.Millisecond)
	createOrder(t, handler, run.ID, `{"title":"三号","content":"内容","target_type":"个人","target_name":"讲解员乙","priority":"特急"}`)

	recorder := do(handler, http.MethodGet, ordersPath(run.ID), "")
	list := decodeOrderList(t, recorder)
	if list.Meta.Total != 3 || len(list.Records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(list.Records), list.Meta.Total)
	}
	// created_at DESC：三号、二号、一号。
	if list.Records[0].Title != "三号" || list.Records[1].ID != urgent.ID || list.Records[2].Title != "一号" {
		t.Fatalf("records not in created_at DESC order: %+v", list.Records)
	}

	cases := []struct {
		name   string
		query  string
		total  int
		titles []string
	}{
		{"by priority", "?priority=" + "紧急", 1, []string{"二号"}},
		{"by target_type", "?target_type=" + "个人", 1, []string{"三号"}},
		{"by status", "?status=" + "待接收", 3, []string{"三号", "二号", "一号"}},
		{"combined", "?priority=" + "特急" + "&target_type=" + "个人", 1, []string{"三号"}},
		{"no match", "?status=" + "已完成", 0, nil},
	}
	for _, testCase := range cases {
		recorder := do(handler, http.MethodGet, ordersPath(run.ID)+testCase.query, "")
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200; body = %s", testCase.name, recorder.Code, recorder.Body.String())
		}
		list := decodeOrderList(t, recorder)
		if list.Meta.Total != testCase.total || len(list.Records) != len(testCase.titles) {
			t.Fatalf("%s: records = %d, total = %d; want %d / %d",
				testCase.name, len(list.Records), list.Meta.Total, len(testCase.titles), testCase.total)
		}
		for i, title := range testCase.titles {
			if list.Records[i].Title != title {
				t.Fatalf("%s: records[%d].title = %q, want %q", testCase.name, i, list.Records[i].Title, title)
			}
		}
	}
}

// limit/offset 分页生效（缺省 limit 50，meta.total 保持筛选后的总数）；
// 非法枚举筛选或非法 limit/offset → 400。
func TestListOrdersPaginationAndInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	for i := 1; i <= 53; i++ {
		createOrder(t, handler, run.ID, fmt.Sprintf(
			`{"title":"指令%03d","content":"内容","target_type":"部门","target_name":"疏散组"}`, i))
	}

	recorder := do(handler, http.MethodGet, ordersPath(run.ID)+"?limit=2&offset=0", "")
	list := decodeOrderList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 53", len(list.Records), list.Meta.Total)
	}
	// created_at DESC：最新创建的（指令053、指令052）排在最前。
	if list.Records[0].Title != "指令053" || list.Records[1].Title != "指令052" {
		t.Fatalf("first page not in created_at DESC order: %+v", list.Records)
	}

	recorder = do(handler, http.MethodGet, ordersPath(run.ID)+"?limit=2&offset=52", "")
	list = decodeOrderList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 53 {
		t.Fatalf("limit=2 offset=52: records = %d, total = %d; want 1 / 53", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, ordersPath(run.ID), "")
	list = decodeOrderList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 53 {
		t.Fatalf("default limit: records = %d, total = %d; want 50 / 53", len(list.Records), list.Meta.Total)
	}

	for name, query := range map[string]string{
		"invalid status":     "?status=" + "草稿",
		"invalid priority":   "?priority=" + "加急",
		"invalid target_type": "?target_type=" + "班组",
		"invalid limit":      "?limit=abc",
		"negative limit":     "?limit=-1",
		"invalid offset":     "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, ordersPath(run.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /drills/{rid}/orders/{oid} ──────────────────────────────────

// 存在的 (run, oid) 返回 200 完整对象（含 run_id/issued_at/completed_at）；
// oid 不存在、指令不属于该 run、run 不存在 → 404 {error}。
func TestGetOrder(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	order := createOrder(t, handler, runA.ID, `{"title":"疏散东区游客","content":"引导东区游客经 3 号出口疏散","target_type":"部门","target_name":"疏散组"}`)

	recorder := do(handler, http.MethodGet, orderItemPath(runA.ID, order.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	got := decodeOrder(t, recorder)
	if got.ID != order.ID || got.RunID != runA.ID || got.Title != "疏散东区游客" || got.Status != "待接收" {
		t.Fatalf("get does not return the full object: %+v", got)
	}
	if got.IssuedAt == nil {
		t.Fatalf("get must return issued_at: %+v", got)
	}

	recorder = do(handler, http.MethodGet, orderItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown oid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, orderItemPath(runB.ID, order.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("order of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, orderItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", order.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// GET 不受写门控：已完成 run 的指令仍 200。
	completed := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	startRun(t, handler, completed.ID)
	orderCompletedRun := createOrder(t, handler, completed.ID, "")
	recorder = do(handler, http.MethodPost, runsPath+"/"+completed.ID+"/complete", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, orderItemPath(completed.ID, orderCompletedRun.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET on 已完成 run: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
}

// ─── PUT /drills/{rid}/orders/{oid} ──────────────────────────────────

// 部分更新：省略字段保留原值；title/content/priority/target_type/
// target_name/feedback/deadline 可改；deadline 省略保持、显式 null 清空；
// issued_at/created_at 保持不变、updated_at 刷新；PUT 后 GET 反映更新。
func TestPutOrderPartialUpdate(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	order := createOrder(t, handler, run.ID, `{"title":"疏散东区游客","content":"引导东区游客经 3 号出口疏散","target_type":"部门","target_name":"疏散组","priority":"紧急","feedback":"初始反馈","deadline":"2026-08-03T18:00:00+08:00","created_by":"u-commander"}`)

	time.Sleep(5 * time.Millisecond)
	recorder := do(handler, http.MethodPut, orderItemPath(run.ID, order.ID),
		`{"title":"封控西区","content":"对西区实施临时封控","priority":"特急","target_type":"小组","target_name":"安保一组","feedback":"已转达","deadline":"2026-08-04T18:00:00+08:00"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeOrder(t, recorder)
	if updated.Title != "封控西区" || updated.Content != "对西区实施临时封控" ||
		updated.Priority != "特急" || updated.TargetType != "小组" || updated.TargetName != "安保一组" ||
		updated.Feedback != "已转达" {
		t.Fatalf("updated fields not applied: %+v", updated)
	}
	if updated.Deadline == nil || *updated.Deadline != "2026-08-04T18:00:00+08:00" {
		t.Fatalf("deadline = %v, want the new RFC3339 instant", updated.Deadline)
	}
	if updated.ID != order.ID || updated.RunID != run.ID {
		t.Fatalf("id/run_id must be preserved: %+v", updated)
	}
	if updated.IssuedAt == nil || *updated.IssuedAt != *order.IssuedAt {
		t.Fatalf("issued_at must be preserved: %+v", updated)
	}
	if updated.CreatedAt != order.CreatedAt {
		t.Fatalf("created_at must be preserved: %+v", updated)
	}
	if updated.UpdatedAt == order.UpdatedAt {
		t.Fatalf("updated_at must be refreshed: %+v", updated)
	}
	if updated.Status != "待接收" {
		t.Fatalf("omitted status must keep its value: %+v", updated)
	}
	if updated.CreatedBy != "u-commander" {
		t.Fatalf("created_by must be preserved: %+v", updated)
	}

	// PUT 后 GET 反映更新。
	recorder = do(handler, http.MethodGet, orderItemPath(run.ID, order.ID), "")
	got := decodeOrder(t, recorder)
	if got.Title != "封控西区" || got.Priority != "特急" || got.Deadline == nil {
		t.Fatalf("GET after PUT must reflect the update: %+v", got)
	}

	// deadline 显式 null → 清空；其余省略字段保持现值。
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"deadline":null}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT clear deadline status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeOrder(t, recorder)
	if updated.Deadline != nil {
		t.Fatalf("deadline = %v, want null after explicit clear", updated.Deadline)
	}
	if updated.Title != "封控西区" || updated.Priority != "特急" {
		t.Fatalf("omitted fields must keep their values: %+v", updated)
	}

	// feedback 显式空串 → 清空反馈。
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"feedback":""}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT clear feedback status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeOrder(t, recorder)
	if updated.Feedback != "" {
		t.Fatalf("feedback = %q, want empty after explicit clear", updated.Feedback)
	}
}

// 状态机：仅相邻迁移 待接收→已接收→执行中→已完成；置 已完成 时服务端设置
// completed_at；同状态 no-op 合法；跳级/回退（含 已完成 改回）→ 400。
func TestPutOrderStatusMachine(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	order := createOrder(t, handler, run.ID, "")

	// 待接收→已接收。
	recorder := do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"status":"已接收"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("已接收 status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeOrder(t, recorder)
	if updated.Status != "已接收" || updated.CompletedAt != nil {
		t.Fatalf("after 已接收: status = %q, completed_at = %v", updated.Status, updated.CompletedAt)
	}

	// 已接收→执行中。
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"status":"执行中"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("执行中 status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeOrder(t, recorder)
	if updated.Status != "执行中" || updated.CompletedAt != nil {
		t.Fatalf("after 执行中: status = %q, completed_at = %v", updated.Status, updated.CompletedAt)
	}

	// 执行中→已完成：completed_at 服务端设置。
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"status":"已完成"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("已完成 status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeOrder(t, recorder)
	if updated.Status != "已完成" || updated.CompletedAt == nil || *updated.CompletedAt == "" {
		t.Fatalf("after 已完成: status = %q, completed_at = %v; want set", updated.Status, updated.CompletedAt)
	}

	// 已完成→已完成：同状态 no-op 合法，completed_at 保留。
	completedAt := *updated.CompletedAt
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"status":"已完成"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("已完成 no-op status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated = decodeOrder(t, recorder)
	if updated.Status != "已完成" || updated.CompletedAt == nil || *updated.CompletedAt != completedAt {
		t.Fatalf("no-op must keep status and completed_at: %+v", updated)
	}

	// 已完成 改回 → 400。
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, order.ID), `{"status":"执行中"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("已完成 revert: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 新指令：跳级 → 400（待接收→执行中），回退 → 400（已接收→待接收）。
	other := createOrder(t, handler, run.ID, "")
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, other.ID), `{"status":"执行中"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("skip: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, other.ID), `{"status":"已接收"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("已接收 status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodPut, orderItemPath(run.ID, other.ID), `{"status":"待接收"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("back: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// PUT 失败路径：空必填/非法枚举/非法 deadline → 400；oid 不存在、指令不属
// 于该 run、run 不存在 → 404；run 非 进行中 → 400。错误响应体统一 {error}。
func TestPutOrderFailures(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	order := createOrder(t, handler, runA.ID, "")

	for name, body := range map[string]string{
		"blank title":       `{"title":" "}`,
		"blank content":     `{"content":""}`,
		"blank target_name": `{"target_name":"\t"}`,
		"invalid priority":  `{"priority":"加急"}`,
		"invalid target_type": `{"target_type":"班组"}`,
		"invalid status":    `{"status":"草稿"}`,
		"invalid deadline":  `{"deadline":"明天"}`,
		"deadline number":   `{"deadline":123}`,
		"malformed body":    `{"status":`,
	} {
		recorder := do(handler, http.MethodPut, orderItemPath(runA.ID, order.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	// 404 路径：未知 oid / 其他 run 的指令 / run 不存在。
	recorder := do(handler, http.MethodPut, orderItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), `{"feedback":"新反馈"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown oid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodPut, orderItemPath(runB.ID, order.ID), `{"feedback":"新反馈"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("order of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodPut, orderItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", order.ID), `{"feedback":"新反馈"}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 状态约束：未开始 run → 400。
	notStarted := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	recorder = do(handler, http.MethodPut, orderItemPath(notStarted.ID, order.ID), `{"feedback":"新反馈"}`)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── DELETE /drills/{rid}/orders/{oid} ───────────────────────────────

// 删除 204；DELETE 后 GET 404；指令不属于该 run / oid 不存在 / run 不存
// 在 404；run 非 进行中 400。
func TestDeleteOrder(t *testing.T) {
	handler := testMux(nil)
	runA := mustCreateInProgressRun(t, handler, validScenarioBody)
	runB := mustCreateInProgressRun(t, handler, validScenarioBody)
	order := createOrder(t, handler, runA.ID, "")

	recorder := do(handler, http.MethodDelete, orderItemPath(runA.ID, order.ID), "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}
	recorder = do(handler, http.MethodGet, orderItemPath(runA.ID, order.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// runA 的第二条指令：对 runB 路径删除 → 404（指令不属于该 run）。
	orderA2 := createOrder(t, handler, runA.ID, "")
	recorder = do(handler, http.MethodDelete, orderItemPath(runB.ID, orderA2.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("order of another run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodDelete, orderItemPath(runA.ID, "01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown oid: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
	recorder = do(handler, http.MethodDelete, orderItemPath("01ARZ3NDEKTSV4RRFFQ69G5FAV", orderA2.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("missing run: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 状态约束：未开始 run → 400。
	notStarted := createRun(t, handler, createScenario(t, handler, validScenarioBody).ID, "")
	recorder = do(handler, http.MethodDelete, orderItemPath(notStarted.ID, orderA2.ID), "")
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("未开始 run: status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── 405 与 Allow ────────────────────────────────────────────────────

// 集合只允许 GET/POST、条目只允许 GET/PUT/DELETE：其他方法 405 且带
// Allow 头，响应体为 JSON 错误。
func TestOrdersMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	run := mustCreateInProgressRun(t, handler, validScenarioBody)
	order := createOrder(t, handler, run.ID, "")

	for _, testCase := range []struct {
		name   string
		method string
		target string
		allow  string
	}{
		{"collection PUT", http.MethodPut, ordersPath(run.ID), "GET, POST"},
		{"collection DELETE", http.MethodDelete, ordersPath(run.ID), "GET, POST"},
		{"collection PATCH", http.MethodPatch, ordersPath(run.ID), "GET, POST"},
		{"item POST", http.MethodPost, orderItemPath(run.ID, order.ID), "GET, PUT, DELETE"},
		{"item PATCH", http.MethodPatch, orderItemPath(run.ID, order.ID), "GET, PUT, DELETE"},
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

// 对 orders 路径的 OPTIONS 预检：204 且 Access-Control-Allow-Methods 含
// POST/PUT/DELETE（写方法可被浏览器调用）。
func TestOrdersCORSPreflight(t *testing.T) {
	req := httptest.NewRequest(http.MethodOptions, ordersPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), nil)
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
