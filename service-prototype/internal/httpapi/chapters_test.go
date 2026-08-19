package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
)

// ─── 测试辅助 ────────────────────────────────────────────────────────

// chaptersPath is the unified resource path of the chapter item routes.
const chaptersPath = "/crate-api/prototype/v1/chapters"

// courseChaptersPath builds the per-course chapter collection path.
func courseChaptersPath(courseID string) string {
	return "/crate-api/prototype/v1/courses/" + courseID + "/chapters"
}

// chapterJSON mirrors the chapter response for assertions.
type chapterJSON struct {
	ID         string           `json:"id"`
	CourseID   string           `json:"course_id"`
	SortOrder  int              `json:"sort_order"`
	Title      string           `json:"title"`
	Blocks     []map[string]any `json:"blocks"`
	QuizConfig any              `json:"quiz_config"`
	CreatedAt  string           `json:"created_at"`
	UpdatedAt  string           `json:"updated_at"`
}

type chapterListJSON struct {
	Records []chapterJSON `json:"records"`
	Meta    struct {
		Total int `json:"total"`
	} `json:"meta"`
}

func decodeChapter(t *testing.T, recorder *httptest.ResponseRecorder) chapterJSON {
	t.Helper()
	var chapter chapterJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &chapter); err != nil {
		t.Fatalf("body %q is not a chapter JSON: %v", recorder.Body.String(), err)
	}
	return chapter
}

func decodeChapterList(t *testing.T, recorder *httptest.ResponseRecorder) chapterListJSON {
	t.Helper()
	var list chapterListJSON
	if err := json.Unmarshal(recorder.Body.Bytes(), &list); err != nil {
		t.Fatalf("body %q is not a list JSON: %v", recorder.Body.String(), err)
	}
	return list
}

// createChapter posts a valid chapter body under the course and asserts
// 201; returns the created chapter.
func createChapter(t *testing.T, handler http.Handler, courseID, body string) chapterJSON {
	t.Helper()
	recorder := do(handler, http.MethodPost, courseChaptersPath(courseID), body)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("POST status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	return decodeChapter(t, recorder)
}

const validChapterBody = `{"sort_order":1,"title":"第一章 客流基础","blocks":[{"type":"视频","url":"https://example.test/v1.mp4"},{"type":"图文","content":"图文内容"},{"type":"互动问答","question":{"stem":"问"}}],"quiz_config":{"pass_score":60,"instant_feedback":true}}`

// ─── POST /courses/{courseId}/chapters ───────────────────────────────

// 课程不存在 → 404，错误响应体为 {error}。
func TestCreateChapterCourseNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodPost, courseChaptersPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), validChapterBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 缺 title（或空白 title）→ 400，错误响应体为 {error}。
func TestCreateChapterMissingTitle(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, body := range map[string]string{
		"missing title": `{"sort_order":1,"blocks":[]}`,
		"blank title":   `{"title":"  ","blocks":[]}`,
	} {
		recorder := do(handler, http.MethodPost, courseChaptersPath(course.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// blocks 含非法块类型（非 视频/图文/互动问答、缺 type、type 非字符串）→ 400。
func TestCreateChapterInvalidBlockType(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, body := range map[string]string{
		"unknown type":    `{"title":"章节","blocks":[{"type":"直播"}]}`,
		"missing type":    `{"title":"章节","blocks":[{"content":"没有 type"}]}`,
		"non-string type": `{"title":"章节","blocks":[{"type":123}]}`,
		"mixed valid+bad": `{"title":"章节","blocks":[{"type":"视频"},{"type":"图文"},{"type":"直播"}]}`,
	} {
		recorder := do(handler, http.MethodPost, courseChaptersPath(course.ID), body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// 合法创建：201，响应完整章节对象——服务端生成的 26 位 ULID id、course_id
// 取自路径参数、sort_order/title/blocks/quiz_config 回显、时间字段存在。
func TestCreateChapterSuccess(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)

	recorder := do(handler, http.MethodPost, courseChaptersPath(course.ID), validChapterBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", recorder.Code, recorder.Body.String())
	}
	chapter := decodeChapter(t, recorder)
	if !ulidPattern.MatchString(chapter.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", chapter.ID)
	}
	if chapter.CourseID != course.ID {
		t.Fatalf("course_id = %q, want %q (from the path)", chapter.CourseID, course.ID)
	}
	if chapter.SortOrder != 1 {
		t.Fatalf("sort_order = %d, want 1", chapter.SortOrder)
	}
	if chapter.Title != "第一章 客流基础" {
		t.Fatalf("title = %q, want 第一章 客流基础", chapter.Title)
	}
	if len(chapter.Blocks) != 3 ||
		chapter.Blocks[0]["type"] != "视频" ||
		chapter.Blocks[1]["type"] != "图文" ||
		chapter.Blocks[2]["type"] != "互动问答" ||
		chapter.Blocks[0]["url"] != "https://example.test/v1.mp4" {
		t.Fatalf("blocks = %v, want the three validated blocks echoed", chapter.Blocks)
	}
	quiz, ok := chapter.QuizConfig.(map[string]any)
	if !ok || quiz["pass_score"] != float64(60) || quiz["instant_feedback"] != true {
		t.Fatalf("quiz_config = %v, want it echoed verbatim", chapter.QuizConfig)
	}
	if chapter.CreatedAt == "" || chapter.UpdatedAt == "" {
		t.Fatalf("created_at/updated_at must be present, got %+v", chapter)
	}
}

// 可选字段缺省：sort_order=0、blocks=[]、quiz_config=null；body 不接收
// course_id（即使传入也以路径为准的字段集之外）。
func TestCreateChapterDefaults(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)

	chapter := createChapter(t, handler, course.ID, `{"title":"仅标题"}`)
	if chapter.SortOrder != 0 {
		t.Fatalf("sort_order = %d, want default 0", chapter.SortOrder)
	}
	if len(chapter.Blocks) != 0 {
		t.Fatalf("blocks = %v, want an empty array when omitted", chapter.Blocks)
	}
	if chapter.QuizConfig != nil {
		t.Fatalf("quiz_config = %v, want null when omitted", chapter.QuizConfig)
	}
	if !strings.Contains(chapterResponseBody(handler, course.ID, chapter.ID), `"quiz_config":null`) {
		t.Fatalf("response must echo quiz_config as JSON null")
	}
}

// chapterResponseBody fetches a chapter and returns the raw JSON body.
func chapterResponseBody(handler http.Handler, courseID, chapterID string) string {
	recorder := do(handler, http.MethodGet, chaptersPath+"/"+chapterID, "")
	if recorder.Code != http.StatusOK {
		return ""
	}
	return recorder.Body.String()
}

// 畸形 JSON 请求体 → 400 {error}。
func TestCreateChapterMalformedBody(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	recorder := do(handler, http.MethodPost, courseChaptersPath(course.ID), `{"title": `)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// ─── GET /courses/{courseId}/chapters ────────────────────────────────

// 课程不存在 → 404（存在性检查优先于空列表）。
func TestListChaptersCourseNotFound(t *testing.T) {
	recorder := do(testMux(nil), http.MethodGet, courseChaptersPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body = %s", recorder.Code, recorder.Body.String())
	}
	decodeError(t, recorder)
}

// 空列表返回 {records: [], meta: {total: 0}}，records 为 JSON 数组而非 null。
func TestListChaptersEmpty(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	recorder := do(handler, http.MethodGet, courseChaptersPath(course.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"records":[]`) {
		t.Fatalf("body %q must contain an empty records array", recorder.Body.String())
	}
	list := decodeChapterList(t, recorder)
	if list.Meta.Total != 0 {
		t.Fatalf("total = %d, want 0", list.Meta.Total)
	}
}

// 章节列表按 sort_order 升序返回。
func TestListChaptersSortOrder(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	createChapter(t, handler, course.ID, `{"sort_order":2,"title":"乙"}`)
	createChapter(t, handler, course.ID, `{"sort_order":0,"title":"甲"}`)
	createChapter(t, handler, course.ID, `{"sort_order":1,"title":"丙"}`)

	recorder := do(handler, http.MethodGet, courseChaptersPath(course.ID), "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	list := decodeChapterList(t, recorder)
	if list.Meta.Total != 3 {
		t.Fatalf("total = %d, want 3", list.Meta.Total)
	}
	titles := []string{list.Records[0].Title, list.Records[1].Title, list.Records[2].Title}
	if titles[0] != "甲" || titles[1] != "丙" || titles[2] != "乙" {
		t.Fatalf("titles = %v, want [甲 丙 乙] (sort_order 0,1,2)", titles)
	}
}

// limit/offset 分页生效，meta.total 保持课程章节总数。
func TestListChaptersPagination(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for i := 0; i < 5; i++ {
		createChapter(t, handler, course.ID, fmt.Sprintf(`{"title":"章节%d"}`, i))
	}

	recorder := do(handler, http.MethodGet, courseChaptersPath(course.ID)+"?limit=2&offset=0", "")
	list := decodeChapterList(t, recorder)
	if len(list.Records) != 2 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=0: records = %d, total = %d; want 2 / 5", len(list.Records), list.Meta.Total)
	}
	if list.Records[0].Title != "章节0" || list.Records[1].Title != "章节1" {
		t.Fatalf("first page titles = %q %q, want 章节0 章节1", list.Records[0].Title, list.Records[1].Title)
	}

	recorder = do(handler, http.MethodGet, courseChaptersPath(course.ID)+"?limit=2&offset=4", "")
	list = decodeChapterList(t, recorder)
	if len(list.Records) != 1 || list.Meta.Total != 5 {
		t.Fatalf("limit=2 offset=4: records = %d, total = %d; want 1 / 5", len(list.Records), list.Meta.Total)
	}

	recorder = do(handler, http.MethodGet, courseChaptersPath(course.ID)+"?limit=2&offset=10", "")
	list = decodeChapterList(t, recorder)
	if len(list.Records) != 0 || list.Meta.Total != 5 {
		t.Fatalf("offset beyond end: records = %d, total = %d; want 0 / 5", len(list.Records), list.Meta.Total)
	}
}

// 未传 limit 时默认 50（与 courses 同款）。
func TestListChaptersDefaultPageSize(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for i := 0; i < 51; i++ {
		createChapter(t, handler, course.ID, fmt.Sprintf(`{"title":"章节%02d"}`, i))
	}
	recorder := do(handler, http.MethodGet, courseChaptersPath(course.ID), "")
	list := decodeChapterList(t, recorder)
	if len(list.Records) != 50 || list.Meta.Total != 51 {
		t.Fatalf("records = %d, total = %d; want 50 / 51 (default limit 50)", len(list.Records), list.Meta.Total)
	}
}

// limit/offset 非非负整数 → 400 {error}。
func TestListChaptersInvalidFilter(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	for name, query := range map[string]string{
		"invalid limit":  "?limit=abc",
		"negative limit": "?limit=-1",
		"invalid offset": "?offset=-2",
	} {
		recorder := do(handler, http.MethodGet, courseChaptersPath(course.ID)+query, "")
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}
}

// ─── GET /chapters/{id} ──────────────────────────────────────────────

// 存在的 id 返回 200 且响应体含全部字段；不存在的 id 返回 404 {error}。
func TestGetChapter(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	created := createChapter(t, handler, course.ID, validChapterBody)

	recorder := do(handler, http.MethodGet, chaptersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	chapter := decodeChapter(t, recorder)
	if chapter.ID != created.ID || chapter.CourseID != course.ID ||
		chapter.SortOrder != 1 || chapter.Title != "第一章 客流基础" ||
		len(chapter.Blocks) != 3 || chapter.QuizConfig == nil {
		t.Fatalf("GET response %+v does not echo the created chapter", chapter)
	}

	recorder = do(handler, http.MethodGet, chaptersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── PUT /chapters/{id} ──────────────────────────────────────────────

// PUT 校验口径与 POST 一致（缺 title / 非法块类型 → 400）；成功返回 200 与
// 更新后的完整对象（全量替换：可选字段缺省取 POST 同款默认值），随后 GET
// 反映更新且 created_at 保留、updated_at 刷新；不存在的 id 返回 404。
func TestUpdateChapter(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	created := createChapter(t, handler, course.ID, validChapterBody)

	updatedBody := `{"sort_order":3,"title":"第一章 进阶","blocks":[{"type":"图文","content":"替换内容"}],"quiz_config":{"pass_score":80}}`
	recorder := do(handler, http.MethodPut, chaptersPath+"/"+created.ID, updatedBody)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	updated := decodeChapter(t, recorder)
	if updated.ID != created.ID || updated.CourseID != course.ID ||
		updated.SortOrder != 3 || updated.Title != "第一章 进阶" ||
		len(updated.Blocks) != 1 || updated.Blocks[0]["type"] != "图文" {
		t.Fatalf("PUT response %+v is not the updated record", updated)
	}
	if updated.CreatedAt != created.CreatedAt {
		t.Fatalf("created_at changed on PUT: %q -> %q", created.CreatedAt, updated.CreatedAt)
	}
	if updated.UpdatedAt == created.UpdatedAt {
		t.Fatalf("updated_at must refresh on PUT, still %q", updated.UpdatedAt)
	}

	recorder = do(handler, http.MethodGet, chaptersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET after PUT: status = %d, want 200", recorder.Code)
	}
	fetched := decodeChapter(t, recorder)
	if fetched.Title != "第一章 进阶" || fetched.SortOrder != 3 ||
		len(fetched.Blocks) != 1 || fetched.Blocks[0]["type"] != "图文" ||
		fetched.QuizConfig.(map[string]any)["pass_score"] != float64(80) {
		t.Fatalf("GET after PUT = %+v, want the updated values", fetched)
	}

	// PUT 为全量替换：省略的可选字段取默认值（sort_order=0、blocks=[]、quiz_config=null）。
	recorder = do(handler, http.MethodPut, chaptersPath+"/"+created.ID, `{"title":"仅标题"}`)
	if recorder.Code != http.StatusOK {
		t.Fatalf("PUT minimal body: status = %d, want 200; body = %s", recorder.Code, recorder.Body.String())
	}
	replaced := decodeChapter(t, recorder)
	if replaced.SortOrder != 0 || len(replaced.Blocks) != 0 || replaced.QuizConfig != nil {
		t.Fatalf("PUT full-replace defaults = %+v, want sort_order 0 / blocks [] / quiz_config null", replaced)
	}

	for name, body := range map[string]string{
		"missing title": `{"sort_order":1}`,
		"blank title":   `{"title":" "}`,
		"invalid block": `{"title":"章节","blocks":[{"type":"直播"}]}`,
		"missing btype": `{"title":"章节","blocks":[{"content":"无类型"}]}`,
	} {
		recorder := do(handler, http.MethodPut, chaptersPath+"/"+created.ID, body)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400; body = %s", name, recorder.Code, recorder.Body.String())
		}
		decodeError(t, recorder)
	}

	recorder = do(handler, http.MethodPut, chaptersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", updatedBody)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("PUT unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── DELETE /chapters/{id} ───────────────────────────────────────────

// 成功返回 204，随后 GET 该 id 返回 404（删除生效）；不存在的 id 返回 404。
func TestDeleteChapter(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	created := createChapter(t, handler, course.ID, validChapterBody)

	recorder := do(handler, http.MethodDelete, chaptersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, chaptersPath+"/"+created.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET after DELETE: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodDelete, chaptersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("DELETE unknown id: status = %d, want 404", recorder.Code)
	}
	decodeError(t, recorder)
}

// ─── 级联删除 ────────────────────────────────────────────────────────

// 路由层：删除课程后，其章节随之删除——课程章节集合因课程不存在返回 404
// （存在性检查优先），章节详情返回 404。
func TestDeleteCourseCascadesToChaptersViaHTTP(t *testing.T) {
	handler := testMux(nil)
	course := createCourse(t, handler, validCourseBody)
	chapter := createChapter(t, handler, course.ID, validChapterBody)
	createChapter(t, handler, course.ID, `{"title":"第二章"}`)

	recorder := do(handler, http.MethodDelete, coursesPath+"/"+course.ID, "")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("DELETE course status = %d, want 204; body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = do(handler, http.MethodGet, courseChaptersPath(course.ID), "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET chapters after course delete: status = %d, want 404 (course gone)", recorder.Code)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodGet, chaptersPath+"/"+chapter.ID, "")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("GET chapter after course delete: status = %d, want 404 (cascade)", recorder.Code)
	}
	decodeError(t, recorder)
}

// Service/Store 层：直接构造内存 Store 与 Service 并接线，断言删除课程后
// 其章节全部删除（按 course_id 查询为空、原章节 ID 返回 ErrNotFound），
// 其他课程的章节不受影响。
func TestDeleteCourseCascadesToChaptersAtServiceLayer(t *testing.T) {
	ctx := context.Background()
	courseStore := courses.NewInMemoryStore()
	chapterStore := chapters.NewInMemoryStore()
	courseService := courses.NewService(courseStore)
	courseService.SetChapterCleaner(chapterStore)
	chapterService := chapters.NewService(chapterStore, courseStore)

	kept, err := courseService.Create(ctx, courses.Input{Title: "保留课程", Topic: "客流评估与引导", Type: "线上授课"})
	if err != nil {
		t.Fatalf("create kept course: %v", err)
	}
	deleted, err := courseService.Create(ctx, courses.Input{Title: "删除课程", Topic: "客流评估与引导", Type: "线上授课"})
	if err != nil {
		t.Fatalf("create deleted course: %v", err)
	}
	keptChapter, err := chapterService.Create(ctx, kept.ID, chapters.Input{Title: "保留章节"})
	if err != nil {
		t.Fatalf("create kept chapter: %v", err)
	}
	deletedChapterA, err := chapterService.Create(ctx, deleted.ID, chapters.Input{Title: "章节甲"})
	if err != nil {
		t.Fatalf("create chapter A: %v", err)
	}
	deletedChapterB, err := chapterService.Create(ctx, deleted.ID, chapters.Input{Title: "章节乙"})
	if err != nil {
		t.Fatalf("create chapter B: %v", err)
	}

	if err := courseService.Delete(ctx, deleted.ID); err != nil {
		t.Fatalf("delete course: %v", err)
	}

	records, total, err := chapterStore.ListByCourse(ctx, deleted.ID, chapters.Filter{Limit: 50})
	if err != nil || total != 0 || len(records) != 0 {
		t.Fatalf("chapters of deleted course: records = %d, total = %d, err = %v; want empty", len(records), total, err)
	}
	for _, id := range []string{deletedChapterA.ID, deletedChapterB.ID} {
		if _, err := chapterStore.Get(ctx, id); !errors.Is(err, chapters.ErrNotFound) {
			t.Fatalf("chapter %s after cascade: err = %v, want ErrNotFound", id, err)
		}
	}

	records, total, err = chapterStore.ListByCourse(ctx, kept.ID, chapters.Filter{Limit: 50})
	if err != nil || total != 1 || len(records) != 1 || records[0].ID != keptChapter.ID {
		t.Fatalf("chapters of kept course: records = %v, total = %d, err = %v; want the kept chapter only", records, total, err)
	}

	if err := courseService.Delete(ctx, "01ARZ3NDEKTSV4RRFFQ69G5FAV"); !errors.Is(err, courses.ErrNotFound) {
		t.Fatalf("delete missing course: err = %v, want ErrNotFound", err)
	}
	if _, total, _ := chapterStore.ListByCourse(ctx, kept.ID, chapters.Filter{Limit: 50}); total != 1 {
		t.Fatalf("deleting a missing course must not touch chapters, total = %d, want 1", total)
	}
}

// ─── 方法 ────────────────────────────────────────────────────────────

// 未注册的方法返回 405 JSON 且带 Allow 头。
func TestChaptersMethodNotAllowed(t *testing.T) {
	handler := testMux(nil)
	recorder := do(handler, http.MethodPatch, courseChaptersPath("01ARZ3NDEKTSV4RRFFQ69G5FAV"), "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH collection: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "POST") {
		t.Fatalf("PATCH collection Allow = %q, want it to contain GET and POST", allow)
	}
	decodeError(t, recorder)

	recorder = do(handler, http.MethodPatch, chaptersPath+"/01ARZ3NDEKTSV4RRFFQ69G5FAV", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("PATCH item: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "PUT") || !strings.Contains(allow, "DELETE") {
		t.Fatalf("PATCH item Allow = %q, want it to contain PUT and DELETE", allow)
	}
	decodeError(t, recorder)
}
