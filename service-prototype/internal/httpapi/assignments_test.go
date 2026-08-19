package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// assignmentsPath is the unified resource path of the training task
// assignments.
const assignmentsPath = "/crate-api/prototype/v1/assignments"

// assignmentJSON mirrors the assignment response for assertions.
type assignmentJSON struct {
	ID          string         `json:"id"`
	CourseID    string         `json:"course_id"`
	AssignType  string         `json:"assign_type"`
	TriggerRule map[string]any `json:"trigger_rule"`
	Deadline    string         `json:"deadline"`
	TargetType  string         `json:"target_type"`
	TargetIDs   []string       `json:"target_ids"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   string         `json:"created_at"`
	UpdatedAt   string         `json:"updated_at"`
}

type assignmentListJSON struct {
	Records []assignmentJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeAssignment(t *testing.T, recorder *httptest.ResponseRecorder) assignmentJSON {
	t.Helper()
	var assignment assignmentJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &assignment); err != nil {
		t.Fatalf("body %q is not an assignment JSON: %v", recorder.Body.String(), err)
	}
	return assignment
}

func decodeAssignmentList(t *testing.T, recorder *httptest.ResponseRecorder) assignmentListJSON {
	t.Helper()
	var list assignmentListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// validAssignmentBody builds a valid assignment body for the course.
func validAssignmentBody(courseID string) string {
	return `{"course_id":"` + courseID + `","assign_type":"手动指派","target_type":"用户","target_ids":["u-001","u-002"]}`
}

// createAssignment posts a valid assignment body and asserts 201; returns
// the created assignment.
func createAssignment(t *testing.T, handler http.Handler, courseID, body string) assignmentJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, assignmentsPath, body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeAssignment(t, recorder)
}

// ─── POST /assignments ───────────────────────────────────────────────

// course_id 指向不存在的课程 → 404，错误响应体为 {error}。
func TestCreateAssignmentCourseNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, assignmentsPath, validAssignmentBody("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 缺 course_id（或空白 course_id）→ 400，与课程不存在 404 是两个独立用例。
func TestCreateAssignmentMissingCourseID(t *testing.T) {
	handler := testMux(nil)
	for name, body := range map[string]string{
		"missing course_id": `{"assign_type":"手动指派","target_type":"用户","target_ids":["u-001"]}`,
		"blank course_id":   `{"course_id":"  ","assign_type":"手动指派","target_type":"用户","target_ids":["u-001"]}`,
	} {
		recorder := do(handler, http.MethodPost, assignmentsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 缺 assign_type / target_type → 400，错误响应体为 {error}。
func TestCreateAssignmentMissingEnums(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, body := range map[string]string{
		"missing assign_type": fmt.Sprintf(`{"course_id":%q,"target_type":"用户","target_ids":["u-001"]}`, course.ID),
		"missing target_type": fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_ids":["u-001"]}`, course.ID),
	} {
		recorder := do(handler, http.MethodPost, assignmentsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 非法 assign_type / target_type 枚举值 → 400，错误响应体为 {error}。
func TestCreateAssignmentInvalidEnums(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, body := range map[string]string{
		"invalid assign_type": fmt.Sprintf(`{"course_id":%q,"assign_type":"随机指派","target_type":"用户","target_ids":["u-001"]}`, course.ID),
		"invalid target_type": fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"角色","target_ids":["u-001"]}`, course.ID),
	} {
		recorder := do(handler, http.MethodPost, assignmentsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// target_ids 缺失 / 空数组 / 含空字符串元素 → 400，错误响应体为 {error}。
func TestCreateAssignmentInvalidTargetIDs(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, body := range map[string]string{
		"missing target_ids": fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户"}`, course.ID),
		"empty target_ids":   fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":[]}`, course.ID),
		"empty element":      fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-001",""]}`, course.ID),
		"blank element":      fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-001","  "]}`, course.ID),
	} {
		recorder := do(handler, http.MethodPost, assignmentsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// deadline 非空但非 RFC3339 格式 → 400，错误响应体为 {error}。
func TestCreateAssignmentInvalidDeadline(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, deadline := range map[string]string{
		"date only":  `"2026-08-10"`,
		"not a date": `"not-a-date"`,
	} {
		body := fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-001"],"deadline":%s}`, course.ID, deadline)
		recorder := do(handler, http.MethodPost, assignmentsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// trigger_rule 提供但非 JSON 对象（数组/字符串/数字/null 各一例）→ 400。
func TestCreateAssignmentInvalidTriggerRule(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, rule := range map[string]string{
		"array":  `[1,2]`,
		"string": `"everyone"`,
		"number": `42`,
		"null":   `null`,
	} {
		body := fmt.Sprintf(`{"course_id":%q,"assign_type":"自动触发","target_type":"部门","target_ids":["d-001"],"trigger_rule":%s}`, course.ID, rule)
		recorder := do(handler, http.MethodPost, assignmentsPath, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 畸形 JSON 请求体 → 400 {error}。
func TestCreateAssignmentMalformedBody(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, assignmentsPath, `{"course_id": `)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 合法创建：201，id 为服务端生成的 26 位 Crockford Base32 ULID；全字段回显；
// 缺省值 trigger_rule={}、deadline=""、created_by=""；created_at/updated_at
// 非空且创建时一致。
func TestCreateAssignmentSuccess(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)

	recorder := do(handler, http.MethodPost, assignmentsPath, validAssignmentBody(course.ID))
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	assignment := decodeAssignment(t, recorder)
	if !ulidPattern.MatchString(assignment.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", assignment.ID)
	}
	if assignment.CourseID != course.ID {
		t.Fatalf("course_id = %q, want %q", assignment.CourseID, course.ID)
	}
	if assignment.AssignType != "手动指派" || assignment.TargetType != "用户" {
		t.Fatalf("assign_type/target_type = %q/%q, want 手动指派/用户", assignment.AssignType, assignment.TargetType)
	}
	if len(assignment.TargetIDs) != 2 || assignment.TargetIDs[0] != "u-001" || assignment.TargetIDs[1] != "u-002" {
		t.Fatalf("target_ids = %v, want [u-001 u-002]", assignment.TargetIDs)
	}
	if len(assignment.TriggerRule) != 0 {
		t.Fatalf("trigger_rule = %v, want an empty object when omitted", assignment.TriggerRule)
	}
	if !strings.Contains(recorder.Body.String(), `"trigger_rule":{}`) {
		t.Fatalf("body %q must echo trigger_rule as an empty JSON object", recorder.Body.String())
	}
	if assignment.Deadline != "" {
		t.Fatalf("deadline = %q, want empty when omitted", assignment.Deadline)
	}
	if assignment.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty when omitted", assignment.CreatedBy)
	}
	if assignment.CreatedAt == "" || assignment.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", assignment)
	}
	if assignment.CreatedAt != assignment.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want them equal at creation", assignment.CreatedAt, assignment.UpdatedAt)
	}
}

// 携带 trigger_rule / deadline / created_by 时创建成功且响应回显。
func TestCreateAssignmentEchoesProvidedFields(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	body := fmt.Sprintf(`{"course_id":%q,"assign_type":"自动触发","trigger_rule":{"event":"course_publish","days":3},"deadline":"2026-08-10T08:00:00+08:00","target_type":"岗位","target_ids":["p-001"],"created_by":"u-admin"}`, course.ID)
	assignment := createAssignment(t, handler, course.ID, body)
	if assignment.AssignType != "自动触发" {
		t.Fatalf("assign_type = %q, want 自动触发", assignment.AssignType)
	}
	if assignment.TriggerRule["event"] != "course_publish" || assignment.TriggerRule["days"] != float64(3) {
		t.Fatalf("trigger_rule = %v, want it echoed verbatim", assignment.TriggerRule)
	}
	if assignment.Deadline != "2026-08-10T08:00:00+08:00" {
		t.Fatalf("deadline = %q, want 2026-08-10T08:00:00+08:00", assignment.Deadline)
	}
	if assignment.TargetType != "岗位" || len(assignment.TargetIDs) != 1 || assignment.TargetIDs[0] != "p-001" {
		t.Fatalf("target = %s %v, want 岗位 [p-001]", assignment.TargetType, assignment.TargetIDs)
	}
	if assignment.CreatedBy != "u-admin" {
		t.Fatalf("created_by = %q, want u-admin", assignment.CreatedBy)
	}
}

// ─── GET /assignments ────────────────────────────────────────────────

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非 null。
func TestListAssignmentsEmpty(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, assignmentsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// course_id 筛选生效：仅返回该课程的指派；指向不存在的课程返回空列表
// total 0（非 404）。
func TestListAssignmentsFilterByCourse(t *testing.T) {
	handler := testMux(nil)
	courseA := createCourse(t, handler, validCourseBody)
	courseB := createCourse(t, handler, `{"title":"线下课堂","topic":"安全应急处置","type":"线下授课"}`)
	assignmentA := createAssignment(t, handler, courseA.ID, validAssignmentBody(courseA.ID))
	createAssignment(t, handler, courseB.ID, validAssignmentBody(courseB.ID))

	recorder := do(handler, http.MethodGet, assignmentsPath+"?course_id="+courseA.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != assignmentA.ID {
		t.Fatalf("course filter: records = %d, total = %d; want the course A assignment only",
			len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, assignmentsPath+"?course_id=01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("unknown course filter: status = %d, want 200 (empty list, not 404)", recorder.Code)
	}
	list = decodeAssignmentList(t, recorder)
	if list.Meta.Total != 0 || len(list.Records) != 0 {
		t.Fatalf("unknown course filter: records = %d, total = %d; want empty", len(list.Records), list.Meta.Total)
	}
}

// target_type 筛选生效：仅返回该类型的指派。
func TestListAssignmentsFilterByTargetType(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	user := createAssignment(t, handler, course.ID, validAssignmentBody(course.ID))
	post := createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"岗位","target_ids":["p-001"]}`, course.ID))
	createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"部门","target_ids":["d-001"]}`, course.ID))

	recorder := do(handler, http.MethodGet, assignmentsPath+"?target_type="+"用户", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != user.ID {
		t.Fatalf("target_type 用户: records = %d, total = %d; want the 用户 assignment only",
			len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, assignmentsPath+"?target_type="+"岗位", "")
	list = decodeAssignmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != post.ID {
		t.Fatalf("target_type 岗位: records = %d, total = %d; want the 岗位 assignment only",
			len(list.Records), list.Meta.Total)
	}
}

// employee_id 展开匹配：仅 target_type=用户 且 target_ids 包含该 id 的
// 指派命中；岗位/部门类型指派不因 employee_id 命中。
func TestListAssignmentsFilterByEmployeeID(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	hit := createAssignment(t, handler, course.ID, validAssignmentBody(course.ID)) // 用户，含 u-001
	createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-999"]}`, course.ID))
	createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"岗位","target_ids":["u-001"]}`, course.ID))
	createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"部门","target_ids":["u-001"]}`, course.ID))

	recorder := do(handler, http.MethodGet, assignmentsPath+"?employee_id=u-001", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != hit.ID {
		t.Fatalf("employee filter: records = %d, total = %d; want only the 用户 assignment containing u-001",
			len(list.Records), list.Meta.Total)
	}
}

// 各筛选参数可组合、同时生效。
func TestListAssignmentsFilterCombination(t *testing.T) {
	handler := testMux(nil)
	courseA := createCourse(t, handler, validCourseBody)
	courseB := createCourse(t, handler, `{"title":"线下课堂","topic":"安全应急处置","type":"线下授课"}`)
	target := createAssignment(t, handler, courseA.ID, validAssignmentBody(courseA.ID)) // A 用户 u-001
	createAssignment(t, handler, courseA.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"岗位","target_ids":["u-001"]}`, courseA.ID))
	createAssignment(t, handler, courseB.ID, validAssignmentBody(courseB.ID)) // B 用户 u-001

	query := "?course_id=" + courseA.ID + "&target_type=" + "用户" + "&employee_id=u-001"
	recorder := do(handler, http.MethodGet, assignmentsPath+query, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 1 || len(list.Records) != 1 || list.Records[0].ID != target.ID {
		t.Fatalf("combined filter: records = %d, total = %d; want the single combined match",
			len(list.Records), list.Meta.Total)
	}
}

// limit/offset 分页生效，meta.total 为筛选后全量计数。
func TestListAssignmentsPagination(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for i := 0; i < 5; i++ {
		createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-%02d"]}`, course.ID, i))
	}

	recorder := do(handler, http.MethodGet, assignmentsPath+"?limit=2&offset=0", "")
	list := decodeAssignmentList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 5", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, assignmentsPath+"?limit=2&offset=4", "")
	list = decodeAssignmentList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=4: records = %d, total = %d; want 1 / 5", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, assignmentsPath+"?limit=2&offset=10", "")
	list = decodeAssignmentList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d; want 0 / 5", len(list.Records), list.Meta.Total)
	}
}

// 未传 limit 时默认 50（仓库列表约定）。
func TestListAssignmentsDefaultPageSize(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for i := 0; i < 51; i++ {
		createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-%02d"]}`, course.ID, i))
	}
	recorder := do(handler, http.MethodGet, assignmentsPath, "")
	list := decodeAssignmentList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 51 {
		t.Fatalf("records = %d, total = %d; want 50 / 51 (default limit 50)", len(list.Records), list.Meta.Total)
	}
}

// 排序口径：按 created_at 倒序（相邻记录时间戳非递增）。
func TestListAssignmentsSortOrder(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for i := 0; i < 5; i++ {
		createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"用户","target_ids":["u-%02d"]}`, course.ID, i))
	}
	recorder := do(handler, http.MethodGet, assignmentsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 5 || len(list.Records) != 5 {
		t.Fatalf("records = %d, total = %d; want 5 / 5", len(list.Records), list.Meta.Total)
	}
	for i := 1; i < len(list.Records); i++ {
		if list.Records[i-1].CreatedAt < list.Records[i].CreatedAt {
			t.Fatalf("records not sorted by created_at desc: %q < %q",
				list.Records[i-1].CreatedAt, list.Records[i].CreatedAt)
		}
	}
}

// 筛选参数非法值 → 400 {error}。
func TestListAssignmentsInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	for name, query := range map[string]string{
		"invalid target_type": "?target_type=" + "角色",
		"invalid limit":       "?limit=abc",
		"negative limit":      "?limit=-1",
		"invalid offset":      "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, assignmentsPath+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── DELETE /assignments/{id} ────────────────────────────────────────

// 成功返回 204，随后列表 total 减一且记录不再出现（删除生效性以列表验证）；
// 不存在的 id（合法 26 位 ULID 格式）返回 404 {error}。
func TestDeleteAssignment(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	created := createAssignment(t, handler, course.ID, validAssignmentBody(course.ID))
	createAssignment(t, handler, course.ID, fmt.Sprintf(`{"course_id":%q,"assign_type":"手动指派","target_type":"岗位","target_ids":["p-001"]}`, course.ID))

	recorder := do(handler, http.MethodDelete, assignmentsPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, assignmentsPath, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after DELETE: status = %d, want 200", recorder.Code)
	}
	list := decodeAssignmentList(t, recorder)
	if list.Meta.Total != 1 {
		t.Fatalf("total after DELETE = %d, want 1 (decrement by one)", list.Meta.Total)
	}
	if len(list.Records) != 1 || list.Records[0].ID == created.ID {
		t.Fatalf("records after DELETE = %v; the deleted assignment must not appear", list.Records)
	}

	recorder = do(handler, http.MethodDelete, assignmentsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 方法与 CORS ─────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头。
func TestAssignmentsMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, assignmentsPath, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH collection: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH collection Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, assignmentsPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH item: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH item Allow = %q, want it to contain DELETE", allow)
	}
	decodeError(t, recorder)
}

// 允许 Origin 的 OPTIONS /assignments 预检返回 204，Allow-Methods 含
// GET、POST、DELETE（接口将被前端浏览器消费）。
func TestAssignmentsCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	for _, target := range []string{assignmentsPath, assignmentsPath + "/01ARZ3NDEKTSV4RRFFQ69G5FAV"} {
		req := httptest.NewRequest(http.MethodOptions, target, nil)
		req.Header.Set("Origin", "https://allowed.example")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusNoContent {
			t.Fatalf("%s: preflight status = %d, want 204", target, recorder.Code)
		}
		methods := recorder.Header().Get("Access-Control-Allow-Methods")
		for _, method := range []string{"GET", "POST", "DELETE", "OPTIONS"} {
			if !strings.Contains(methods, method) {
				t.Fatalf("%s: Allow-Methods = %q, want it to contain %s", target, methods, method)
			}
		}
		if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
			t.Fatalf("%s: ACAO = %q", target, recorder.Header().Get("Access-Control-Allow-Origin"))
		}
	}
}
