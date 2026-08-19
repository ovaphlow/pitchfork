package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// progressPath builds the per-assignment progress summary path.
func progressPath(assignmentID, employeeID string) string {
	return "/crate-api/prototype/v1/assignments/" + assignmentID + "/employees/" + employeeID + "/progress"
}

// chapterProgressPath builds the per-chapter progress report path.
func chapterProgressPath(assignmentID, employeeID, chapterID string) string {
	return progressPath(assignmentID, employeeID) + "/chapters/" + chapterID
}

// completePath builds the per-assignment complete path.
func completePath(assignmentID, employeeID string) string {
	return "/crate-api/prototype/v1/assignments/" + assignmentID + "/employees/" + employeeID + "/complete"
}

// progressJSON mirrors the progress row response for assertions.
type progressJSON struct {
	ID              string         `json:"id"`
	AssignmentID    string         `json:"assignment_id"`
	EmployeeID      string         `json:"employee_id"`
	ChapterID       string         `json:"chapter_id"`
	ProgressPercent int            `json:"progress_percent"`
	Status          string         `json:"status"`
	Detail          map[string]any `json:"detail"`
	StartedAt       *string        `json:"started_at"`
	CompletedAt     *string        `json:"completed_at"`
	CreatedAt       string         `json:"created_at"`
	UpdatedAt       string         `json:"updated_at"`
}

// chapterProgressJSON mirrors one chapter row of the summary response.
type chapterProgressJSON struct {
	ChapterID       string         `json:"chapter_id"`
	ChapterTitle    string         `json:"chapter_title"`
	ProgressPercent int            `json:"progress_percent"`
	Status          string         `json:"status"`
	StartedAt       *string        `json:"started_at"`
	CompletedAt     *string        `json:"completed_at"`
	Detail          map[string]any `json:"detail"`
}

// summaryJSON mirrors the progress summary response for assertions.
type summaryJSON struct {
	AssignmentID      string                `json:"assignment_id"`
	EmployeeID        string                `json:"employee_id"`
	CourseID          string                `json:"course_id"`
	CourseTitle       string                `json:"course_title"`
	TotalChapters     int                   `json:"total_chapters"`
	CompletedChapters int                   `json:"completed_chapters"`
	Status            string                `json:"status"`
	Chapters          []chapterProgressJSON `json:"chapters"`
}

func decodeProgress(t *testing.T, recorder *httptest.ResponseRecorder) progressJSON {
	t.Helper()
	var row progressJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &row); err != nil {
		t.Fatalf("body %q is not a progress row JSON: %v", recorder.Body.String(), err)
	}
	return row
}

func decodeSummary(t *testing.T, recorder *httptest.ResponseRecorder) summaryJSON {
	t.Helper()
	var summary summaryJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &summary); err != nil {
		t.Fatalf("body %q is not a summary JSON: %v", recorder.Body.String(), err)
	}
	return summary
}

// putProgress issues a chapter progress report and returns the recorder.
func putProgress(t *testing.T, handler http.Handler, assignmentID, employeeID, chapterID, body string) *httptest.ResponseRecorder {
	t.Helper()
	return do(handler, http.MethodPut, chapterProgressPath(assignmentID, employeeID, chapterID), body)
}

// getProgress issues a progress summary request and returns the recorder.
func getProgress(t *testing.T, handler http.Handler, assignmentID, employeeID string) *httptest.ResponseRecorder {
	t.Helper()
	return do(handler, http.MethodGet, progressPath(assignmentID, employeeID), "")
}

// postComplete issues a complete request and returns the recorder.
func postComplete(t *testing.T, handler http.Handler, assignmentID, employeeID string) *httptest.ResponseRecorder {
	t.Helper()
	return do(handler, http.MethodPost, completePath(assignmentID, employeeID), "")
}

// progressFixture sets up a course with two chapters (created out of
// sort order) and an assignment targeting u-001, and returns the ids.
type progressFixture struct {
	handler    http.Handler
	course     courseJSON
	chapterOne chapterJSON // sort_order 1, 第一章
	chapterTwo chapterJSON // sort_order 2, 第二章
	assignment assignmentJSON
	employeeID string
}

func newProgressFixture(t *testing.T) progressFixture {
	t.Helper()
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	// 故意逆序创建：sort_order 2 在前、1 在后，验证汇总按 sort_order 升序。
	chapterTwo := createChapter(t, handler, course.ID, `{"sort_order":2,"title":"第二章"}`)
	chapterOne := createChapter(t, handler, course.ID, `{"sort_order":1,"title":"第一章"}`)
	assignment := createAssignment(t, handler, course.ID, validAssignmentBody(course.ID))
	return progressFixture{
		handler:    handler,
		course:     course,
		chapterOne: chapterOne,
		chapterTwo: chapterTwo,
		assignment: assignment,
		employeeID: "u-001",
	}
}

// ─── GET 汇总（成功路径）─────────────────────────────────────────────

// GET progress 返回 200 单对象汇总：chapters 覆盖指派课程全部章节、按
// sort_order 升序，无上报章节为零值；汇总 status 由章节行推导。
func TestGetProgressSummaryShape(t *testing.T) {
	fixture := newProgressFixture(t)

	// 无上报：全部章节零值，汇总学习中。
	recorder := getProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	summary := decodeSummary(t, recorder)
	if summary.AssignmentID != fixture.assignment.ID || summary.EmployeeID != fixture.employeeID {
		t.Fatalf("assignment_id/employee_id = %q/%q, want %q/%q",
			summary.AssignmentID, summary.EmployeeID, fixture.assignment.ID, fixture.employeeID)
	}
	if summary.CourseID != fixture.course.ID || summary.CourseTitle != "客流组织基础" {
		t.Fatalf("course_id/course_title = %q/%q, want %q/客流组织基础", summary.CourseID, summary.CourseTitle, fixture.course.ID)
	}
	if summary.TotalChapters != 2 || summary.CompletedChapters != 0 || summary.Status != "学习中" {
		t.Fatalf("total/completed/status = %d/%d/%q, want 2/0/学习中", summary.TotalChapters, summary.CompletedChapters, summary.Status)
	}
	if len(summary.Chapters) != 2 {
		t.Fatalf("chapters = %d, want 2 (every course chapter)", len(summary.Chapters))
	}
	// sort_order 升序：第一章（order 1）在前。
	if summary.Chapters[0].ChapterID != fixture.chapterOne.ID || summary.Chapters[1].ChapterID != fixture.chapterTwo.ID {
		t.Fatalf("chapter order = %s, %s; want sort_order ascending", summary.Chapters[0].ChapterID, summary.Chapters[1].ChapterID)
	}
	if summary.Chapters[0].ChapterTitle != "第一章" || summary.Chapters[1].ChapterTitle != "第二章" {
		t.Fatalf("chapter titles = %q, %q; want 第一章, 第二章", summary.Chapters[0].ChapterTitle, summary.Chapters[1].ChapterTitle)
	}
	for _, entry := range summary.Chapters {
		if entry.ProgressPercent != 0 || entry.Status != "学习中" ||
			entry.StartedAt != nil || entry.CompletedAt != nil || len(entry.Detail) != 0 {
			t.Fatalf("unreported chapter %+v, want zero values (0/学习中/null/null/{})", entry)
		}
	}

	// 一章 50：汇总反映更新，仍学习中。
	recorder = putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":50,"detail":{"watch":3}}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	summary = decodeSummary(t, getProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID))
	if summary.CompletedChapters != 0 || summary.Status != "学习中" {
		t.Fatalf("after 50: completed/status = %d/%q, want 0/学习中", summary.CompletedChapters, summary.Status)
	}
	first := summary.Chapters[0]
	if first.ProgressPercent != 50 || first.Status != "学习中" || first.StartedAt == nil || first.CompletedAt != nil || first.Detail["watch"] != float64(3) {
		t.Fatalf("reported chapter %+v, want 50/学习中/started_at set/detail echoed", first)
	}

	// 一章 100：completed 1，仍学习中（另一章未完成）。
	putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":100}`)
	summary = decodeSummary(t, getProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID))
	if summary.CompletedChapters != 1 || summary.Status != "学习中" {
		t.Fatalf("after one 100: completed/status = %d/%q, want 1/学习中", summary.CompletedChapters, summary.Status)
	}
	if summary.Chapters[0].Status != "已完成" || summary.Chapters[0].CompletedAt == nil {
		t.Fatalf("completed chapter %+v, want 已完成 with completed_at", summary.Chapters[0])
	}

	// 全部完成：汇总已完成。
	putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterTwo.ID, `{"progress_percent":100}`)
	summary = decodeSummary(t, getProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID))
	if summary.CompletedChapters != 2 || summary.Status != "已完成" {
		t.Fatalf("after all: completed/status = %d/%q, want 2/已完成", summary.CompletedChapters, summary.Status)
	}
}

// ─── PUT 上报（成功路径）─────────────────────────────────────────────

// PUT 返回 200 + 完整进度行对象：服务端生成 26 位 ULID、路径 id 回显、
// status 推导、detail 透传、时间戳服务端设置；100 分记完成。
func TestPutProgressReturnsFullRow(t *testing.T) {
	fixture := newProgressFixture(t)

	recorder := putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":40,"detail":{"watch":3,"note":"看了一半"}}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	row := decodeProgress(t, recorder)
	if !ulidPattern.MatchString(row.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", row.ID)
	}
	if row.AssignmentID != fixture.assignment.ID || row.EmployeeID != fixture.employeeID || row.ChapterID != fixture.chapterOne.ID {
		t.Fatalf("path ids = %q/%q/%q, want the route values", row.AssignmentID, row.EmployeeID, row.ChapterID)
	}
	if row.ProgressPercent != 40 || row.Status != "学习中" {
		t.Fatalf("progress_percent/status = %d/%q, want 40/学习中", row.ProgressPercent, row.Status)
	}
	if row.Detail["watch"] != float64(3) || row.Detail["note"] != "看了一半" {
		t.Fatalf("detail = %v, want it echoed verbatim", row.Detail)
	}
	if row.StartedAt == nil || row.CompletedAt != nil {
		t.Fatalf("started_at/completed_at = %v/%v, want set/nil", row.StartedAt, row.CompletedAt)
	}
	if row.CreatedAt == "" || row.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", row)
	}
	if row.CreatedAt != row.UpdatedAt {
		t.Fatalf("created_at = %q, updated_at = %q; want them equal at creation", row.CreatedAt, row.UpdatedAt)
	}

	// 100 分：status=已完成、completed_at 置位。
	recorder = putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":100}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT 100 status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	completed := decodeProgress(t, recorder)
	if completed.Status != "已完成" || completed.CompletedAt == nil {
		t.Fatalf("row after 100 = %+v, want 已完成 with completed_at", completed)
	}
	if completed.ID != row.ID {
		t.Fatalf("id = %q, want the upserted row id %q", completed.ID, row.ID)
	}
}

// upsert 语义：同键二次 PUT 行 id/started_at/created_at 稳定，GET 反映
// progress_percent/detail 更新。
func TestPutProgressUpsertKeepsIdentityAndGetReflects(t *testing.T) {
	fixture := newProgressFixture(t)

	first := decodeProgress(t, putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":20,"detail":{"watch":1}}`))
	second := decodeProgress(t, putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":70,"detail":{"watch":7,"note":"继续"}}`))
	if second.ID != first.ID {
		t.Fatalf("id = %q, want %q (upsert keeps the row id)", second.ID, first.ID)
	}
	if second.StartedAt == nil || first.StartedAt == nil || *second.StartedAt != *first.StartedAt {
		t.Fatalf("started_at = %v -> %v, want it preserved", first.StartedAt, second.StartedAt)
	}
	if second.CreatedAt != first.CreatedAt {
		t.Fatalf("created_at = %q -> %q, want it preserved", first.CreatedAt, second.CreatedAt)
	}

	summary := decodeSummary(t, getProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID))
	entry := summary.Chapters[0]
	if entry.ProgressPercent != 70 || entry.Detail["watch"] != float64(7) || entry.Detail["note"] != "继续" {
		t.Fatalf("GET after PUT = %+v, want the updated progress_percent/detail", entry)
	}
	if entry.StartedAt == nil || *entry.StartedAt != *first.StartedAt {
		t.Fatalf("GET started_at = %v, want the first-report timestamp", entry.StartedAt)
	}
}

// ─── PUT 校验（失败路径）─────────────────────────────────────────────

// progress_percent 仅接受 0-100 的 JSON 整数：缺失、字符串、布尔、null、
// 非整数数值、越界、请求体缺失/非 JSON、detail 非对象均 400 {error}。
func TestPutProgressValidation(t *testing.T) {
	fixture := newProgressFixture(t)
	target := chapterProgressPath(fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID)
	for name, body := range map[string]string{
		"missing progress_percent": `{"detail":{"watch":1}}`,
		"string number":            `{"progress_percent":"50"}`,
		"string abc":               `{"progress_percent":"abc"}`,
		"boolean":                  `{"progress_percent":true}`,
		"null":                     `{"progress_percent":null}`,
		"float":                    `{"progress_percent":50.5}`,
		"negative":                 `{"progress_percent":-1}`,
		"over 100":                 `{"progress_percent":101}`,
		"malformed JSON":           `{"progress_percent": `,
		"non-JSON body":            `not-json`,
		"empty body":               ``,
		"detail array":             `{"progress_percent":50,"detail":[1,2]}`,
		"detail string":            `{"progress_percent":50,"detail":"notes"}`,
		"detail number":            `{"progress_percent":50,"detail":42}`,
		"detail null":              `{"progress_percent":50,"detail":null}`,
	} {
		recorder := do(fixture.handler, http.MethodPut, target, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// PUT 指派不存在（合法 26 位 ULID 格式）/ 章节不存在 / 章节不属于该指派
// 课程 → 404 {error}。
func TestPutProgressNotFound(t *testing.T) {
	fixture := newProgressFixture(t)

	recorder := putProgress(t, fixture.handler, "01ARZ3NDEKTSV4RRFFQ69G5FAV", fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":50}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown assignment: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	recorder = putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, "01ARZ3NDEKTSV4RRFFQ69G5FAV", `{"progress_percent":50}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown chapter: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)

	// 章节存在但属于另一课程。
	otherCourse := createCourse(t, fixture.handler, `{"title":"线下课堂","topic":"安全应急处置","type":"线下授课"}`)
	foreignChapter := createChapter(t, fixture.handler, otherCourse.ID, `{"sort_order":1,"title":"外部章节"}`)
	recorder = putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, foreignChapter.ID, `{"progress_percent":50}`)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("foreign chapter: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET / complete 指派不存在（失败路径）───────────────────────────

func TestGetProgressAssignmentNotFound(t *testing.T) {
	recorder := getProgress(t, testMux(nil), "01ARZ3NDEKTSV4RRFFQ69G5FAV", "u-001")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

func TestCompleteAssignmentNotFound(t *testing.T) {
	recorder := postComplete(t, testMux(nil), "01ARZ3NDEKTSV4RRFFQ69G5FAV", "u-001")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── POST complete（成功路径 + 写操作生效性）────────────────────────

// complete 置全部章节已完成：已上报行保留 started_at、未上报行创建即完成；
// 响应与 GET 同形，complete 后 GET 反映一致；重复调用幂等；complete 创建
// 的行 id 为 26 位 ULID（经后续 PUT 回读验证）。
func TestCompleteMarksAllChaptersAndGetReflects(t *testing.T) {
	fixture := newProgressFixture(t)
	reported := decodeProgress(t, putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":30,"detail":{"watch":2}}`))

	recorder := postComplete(t, fixture.handler, fixture.assignment.ID, fixture.employeeID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	summary := decodeSummary(t, recorder)
	if summary.TotalChapters != 2 || summary.CompletedChapters != 2 || summary.Status != "已完成" {
		t.Fatalf("total/completed/status = %d/%d/%q, want 2/2/已完成", summary.TotalChapters, summary.CompletedChapters, summary.Status)
	}
	for _, entry := range summary.Chapters {
		if entry.ProgressPercent != 100 || entry.Status != "已完成" {
			t.Fatalf("chapter %s = %+v, want 100/已完成", entry.ChapterID, entry)
		}
		if entry.CompletedAt == nil {
			t.Fatalf("chapter %s: completed_at must be set after complete", entry.ChapterID)
		}
		if entry.StartedAt == nil {
			t.Fatalf("chapter %s: started_at must be set after complete", entry.ChapterID)
		}
	}
	// 已上报行保留 started_at。
	if *summary.Chapters[0].StartedAt != *reported.StartedAt {
		t.Fatalf("reported chapter started_at = %v, want %v preserved", *summary.Chapters[0].StartedAt, *reported.StartedAt)
	}

	// complete 后 GET 反映同样状态。
	after := decodeSummary(t, getProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID))
	if after.Status != "已完成" || after.CompletedChapters != 2 {
		t.Fatalf("GET after complete = %+v, want the completed state", after)
	}

	// 幂等：重复调用仍 200 且状态一致。
	again := postComplete(t, fixture.handler, fixture.assignment.ID, fixture.employeeID)
	if again.Code != http.StatusOK {
		t.Fatalf("second complete status = %d, want 200", again.Code)
	}
	if decodeSummary(t, again).Status != "已完成" {
		t.Fatalf("second complete must stay 已完成")
	}

	// complete 创建的章节行（未上报过）id 为 26 位 ULID：后续 PUT 回读该行。
	row := decodeProgress(t, putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterTwo.ID, `{"progress_percent":80}`))
	if !ulidPattern.MatchString(row.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID (row created by complete)", row.ID)
	}
	// 已完成行不回退：80 分后 status/completed_at 保持。
	if row.Status != "已完成" || row.CompletedAt == nil {
		t.Fatalf("row after 80 = %+v, want 已完成 with completed_at kept", row)
	}
	if row.ProgressPercent != 80 {
		t.Fatalf("progress_percent = %d, want 80 (still updatable after completion)", row.ProgressPercent)
	}
}

// ─── 无章节课程 ──────────────────────────────────────────────────────

// 课程无章节：GET 返回 chapters=[]、total/completed=0、status=学习中
// （指派存在非 404）；POST complete 同样 200 且状态一致（空集不视为全部
// 完成），complete 后 GET 无矛盾。
func TestProgressEmptyCourse(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, `{"title":"空课程","topic":"安全应急处置","type":"线上授课"}`)
	assignment := createAssignment(t, handler, course.ID, validAssignmentBody(course.ID))
	employeeID := "u-001"

	recorder := getProgress(t, handler, assignment.ID, employeeID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	summary := decodeSummary(t, recorder)
	if !strings.Contains(recorder.Body.String(), `"chapters":[]`) {
		t.Fatalf("body %q must render chapters as an empty JSON array", recorder.Body.String())
	}
	if summary.TotalChapters != 0 || summary.CompletedChapters != 0 || summary.Status != "学习中" {
		t.Fatalf("GET: total/completed/status = %d/%d/%q, want 0/0/学习中", summary.TotalChapters, summary.CompletedChapters, summary.Status)
	}

	recorder = postComplete(t, handler, assignment.ID, employeeID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("complete status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	completed := decodeSummary(t, recorder)
	if completed.TotalChapters != 0 || completed.CompletedChapters != 0 || completed.Status != "学习中" {
		t.Fatalf("complete: total/completed/status = %d/%d/%q, want 0/0/学习中 (empty set)", completed.TotalChapters, completed.CompletedChapters, completed.Status)
	}

	after := decodeSummary(t, getProgress(t, handler, assignment.ID, employeeID))
	if after.Status != "学习中" || after.CompletedChapters != 0 {
		t.Fatalf("GET after complete = %+v, want the same 学习中 state", after)
	}
}

// ─── 员工维度 ───────────────────────────────────────────────────────

// employee_id 仅作进度维度标识（不校验归属）：一个员工的进度不影响
// 另一员工的汇总。
func TestProgressEmployeeIsolation(t *testing.T) {
	fixture := newProgressFixture(t)
	putProgress(t, fixture.handler, fixture.assignment.ID, fixture.employeeID, fixture.chapterOne.ID, `{"progress_percent":100}`)

	other := decodeSummary(t, getProgress(t, fixture.handler, fixture.assignment.ID, "u-999"))
	if other.CompletedChapters != 0 || other.Status != "学习中" {
		t.Fatalf("other employee summary = %+v, want untouched zero state", other)
	}
	if other.Chapters[0].ProgressPercent != 0 || other.Chapters[0].Status != "学习中" {
		t.Fatalf("other employee chapter = %+v, want zero values", other.Chapters[0])
	}
}

// ─── CORS ───────────────────────────────────────────────────────────

// 允许 Origin 的 OPTIONS 预检覆盖 progress 与 complete 路径：204 且
// Allow-Methods 含 GET、POST、PUT、DELETE、OPTIONS（接口将被前端浏览器
// 消费）。
func TestProgressCORSPreflightCoversWriteMethods(t *testing.T) {
	handler := testMux([]string{"https://allowed.example"})
	recorder := httptest.NewRecorder()
	for _, target := range []string{
		"/crate-api/prototype/v1/assignments/01ARZ3NDEKTSV4RRFFQ69G5FAV/employees/u-001/progress",
		"/crate-api/prototype/v1/assignments/01ARZ3NDEKTSV4RRFFQ69G5FAV/employees/u-001/progress/chapters/01ARZ3NDEKTSV4RRFFQ69G5FAV",
		"/crate-api/prototype/v1/assignments/01ARZ3NDEKTSV4RRFFQ69G5FAV/employees/u-001/complete",
	} {
		recorder = httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodOptions, target, nil)
		req.Header.Set("Origin", "https://allowed.example")
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusNoContent {
			t.Fatalf("%s: preflight status = %d, want 204", target, recorder.Code)
		}
		methods := recorder.Header().Get("Access-Control-Allow-Methods")
		for _, method := range []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"} {
			if !strings.Contains(methods, method) {
				t.Fatalf("%s: Allow-Methods = %q, want it to contain %s", target, methods, method)
			}
		}
		if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
			t.Fatalf("%s: ACAO = %q", target, recorder.Header().Get("Access-Control-Allow-Origin"))
		}
	}
}

// 未注册方法（如 PATCH progress 路径）不落入 assignments/{id} 路由，
// 返回 404 JSON（多段字面路径优先且互不冲突）。
func TestProgressRoutesDoNotCollideWithAssignmentItem(t *testing.T) {
	fixture := newProgressFixture(t)
	// assignments/{id} 仍只服务 DELETE（既有行为不回归）。
	recorder := do(fixture.handler, http.MethodGet, "/crate-api/prototype/v1/assignments/"+fixture.assignment.ID, "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET assignment item: status = %d, want 405; body = %s", recorder.Code, recorder.Body.String())
	}
	// 汇总路径已注册，PUT progress 路径的未注册方法落到 404 而非 405。
	recorder = do(fixture.handler, http.MethodPatch, progressPath(fixture.assignment.ID, fixture.employeeID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PATCH progress path: status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "error") {
		t.Fatalf("body %q is not a JSON error", recorder.Body.String())
	}
}
