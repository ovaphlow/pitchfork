package shared_test

// settings / messages / interactions 三模块的开发自测（task-10）。
// 黑盒：只经 shared.NewHandler + Handler.Register + httptest 访问，
// 数据库用临时目录里的 SQLite，会话用 RequireSession 桩注入，不依赖真实端口。
// 公共 harness 放在本文件，messages_test.go / interactions_test.go 复用。

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

const (
	sharedModulesSubjectID      = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
	sharedModulesOtherSubjectID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
	sharedModulesSessionHeader  = "X-Shared-Modules-Session"
	sharedModulesSubjectSession = "subject"
	sharedModulesOtherSession   = "other"
)

var (
	sharedModulesTimestampPattern = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$`)
	sharedModulesULIDPattern      = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)
)

// sharedModulesHarness 暴露路由与数据库，便于直接插入测试 seed 行。
type sharedModulesHarness struct {
	mux *http.ServeMux
	db  *sql.DB
}

func newSharedModulesHarness(t *testing.T) sharedModulesHarness {
	t.Helper()
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "platformd.sqlite"))
	if err != nil {
		t.Fatalf("open SQLite database: %v", err)
	}
	t.Cleanup(func() {
		databaseConnection.Close()
	})
	if _, err := database.Migrate(context.Background(), databaseConnection, migrations.Files); err != nil {
		t.Fatalf("migrate database: %v", err)
	}

	handler := shared.NewHandler(shared.Store{
		DB:     databaseConnection,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		RequireSession: func(request *http.Request) (shared.Session, bool) {
			switch request.Header.Get(sharedModulesSessionHeader) {
			case sharedModulesSubjectSession:
				return shared.Session{SubjectID: sharedModulesSubjectID}, true
			case sharedModulesOtherSession:
				return shared.Session{SubjectID: sharedModulesOtherSubjectID}, true
			default:
				return shared.Session{}, false
			}
		},
	})
	mux := http.NewServeMux()
	handler.Register(mux)
	return sharedModulesHarness{mux: mux, db: databaseConnection}
}

// request 发送 JSON 请求；session 为空表示不带会话头。
func (harness sharedModulesHarness) request(t *testing.T, method, path, session string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("encode request body: %v", err)
		}
		reader = bytes.NewReader(encoded)
	}
	request := httptest.NewRequest(method, path, reader)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if session != "" {
		request.Header.Set(sharedModulesSessionHeader, session)
	}
	recorder := httptest.NewRecorder()
	harness.mux.ServeHTTP(recorder, request)
	return recorder
}

func sharedModulesDecode[T any](t *testing.T, recorder *httptest.ResponseRecorder) T {
	t.Helper()
	var value T
	if err := json.Unmarshal(recorder.Body.Bytes(), &value); err != nil {
		t.Fatalf("decode response %q: %v", recorder.Body.String(), err)
	}
	return value
}

func sharedModulesDecodeArray(t *testing.T, recorder *httptest.ResponseRecorder) []json.RawMessage {
	t.Helper()
	var values []json.RawMessage
	if err := json.Unmarshal(recorder.Body.Bytes(), &values); err != nil {
		t.Fatalf("decode array response %q: %v", recorder.Body.String(), err)
	}
	return values
}

// requireProblem 断言 RFC 9457 Problem Details 的形状（type/title/status/detail）。
func sharedModulesRequireProblem(t *testing.T, recorder *httptest.ResponseRecorder, status int, slug, detail string) {
	t.Helper()
	if recorder.Code != status {
		t.Fatalf("status = %d, want %d (body %s)", recorder.Code, status, recorder.Body.String())
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "application/problem+json" {
		t.Fatalf("content-type = %q, want application/problem+json", contentType)
	}
	problem := sharedModulesDecode[struct {
		Type   string `json:"type"`
		Title  string `json:"title"`
		Status int    `json:"status"`
		Detail string `json:"detail"`
	}](t, recorder)
	if problem.Type != shared.Prefix+"/problems/"+slug {
		t.Fatalf("problem type = %q, want %q", problem.Type, shared.Prefix+"/problems/"+slug)
	}
	if problem.Status != status {
		t.Fatalf("problem status = %d, want %d", problem.Status, status)
	}
	if detail != "" && problem.Detail != detail {
		t.Fatalf("problem detail = %q, want %q", problem.Detail, detail)
	}
}

func sharedModulesRequireTimestamp(t *testing.T, value string) {
	t.Helper()
	if !sharedModulesTimestampPattern.MatchString(value) {
		t.Fatalf("timestamp = %q, want %q", value, sharedModulesTimestampPattern.String())
	}
}

func sharedModulesRequireULID(t *testing.T, value string) {
	t.Helper()
	if !sharedModulesULIDPattern.MatchString(value) {
		t.Fatalf("id = %q, want 26-char Crockford Base32 ULID", value)
	}
}

// seedSharedModulesRow 直接插 seed 行（用于排序 / 他人数据等不可经 API 构造的前置）。
func (harness sharedModulesHarness) seed(t *testing.T, statement string, arguments ...any) {
	t.Helper()
	if _, err := harness.db.ExecContext(context.Background(), statement, arguments...); err != nil {
		t.Fatalf("seed database: %v", err)
	}
}

// seedID 生成 26 位测试 ID（字母数字，满足 ULID 字符集）。
func seedID(prefix string, index int) string {
	id := fmt.Sprintf(prefix+"%023d", index)
	if len(id) > 26 {
		return id[:26]
	}
	return id
}

func TestSettingsCRUDValidationAndPagination(t *testing.T) {
	harness := newSharedModulesHarness(t)

	type settingResponse struct {
		ID         string         `json:"id"`
		Category   string         `json:"category"`
		Code       string         `json:"code"`
		RootCode   string         `json:"root_code"`
		ParentCode string         `json:"parent_code"`
		Payload    map[string]any `json:"payload"`
		CreatedAt  string         `json:"created_at"`
		UpdatedAt  string         `json:"updated_at"`
	}
	create := func(category, code string, payload any) settingResponse {
		t.Helper()
		recorder := harness.request(t, http.MethodPost, shared.Prefix+"/settings", sharedModulesSubjectSession, map[string]any{
			"category": category,
			"code":     code,
			"payload":  payload,
		})
		if recorder.Code != http.StatusCreated {
			t.Fatalf("create setting %s/%s status = %d (body %s)", category, code, recorder.Code, recorder.Body.String())
		}
		return sharedModulesDecode[settingResponse](t, recorder)
	}

	first := create("zzz", "b", map[string]any{"name": "B"})
	second := create("zzz", "a", map[string]any{"name": "A", "nested": map[string]any{"level": 2}})
	third := create("aaa", "c", map[string]any{"name": "C"})
	for _, setting := range []settingResponse{first, second, third} {
		sharedModulesRequireULID(t, setting.ID)
		sharedModulesRequireTimestamp(t, setting.CreatedAt)
		sharedModulesRequireTimestamp(t, setting.UpdatedAt)
	}
	if first.RootCode != "" || first.ParentCode != "" {
		t.Fatalf("defaults = %q/%q, want empty root_code/parent_code", first.RootCode, first.ParentCode)
	}
	if got := second.Payload["name"]; got != "A" {
		t.Fatalf("payload name = %v, want A", got)
	}

	// 列表：裸数组，ORDER BY category, code。
	listed := sharedModulesDecodeArray(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings", sharedModulesSubjectSession, nil))
	if len(listed) != 3 {
		t.Fatalf("settings list length = %d, want 3", len(listed))
	}
	order := make([]settingResponse, 0, len(listed))
	for _, raw := range listed {
		var setting settingResponse
		if err := json.Unmarshal(raw, &setting); err != nil {
			t.Fatalf("decode listed setting: %v", err)
		}
		order = append(order, setting)
	}
	if order[0].Category != "aaa" || order[1].Code != "a" || order[2].Code != "b" {
		t.Fatalf("settings order = %s/%s, %s/%s, %s/%s", order[0].Category, order[0].Code, order[1].Category, order[1].Code, order[2].Category, order[2].Code)
	}

	// category 过滤。
	filtered := sharedModulesDecodeArray(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings?category=zzz", sharedModulesSubjectSession, nil))
	if len(filtered) != 2 {
		t.Fatalf("filtered settings length = %d, want 2", len(filtered))
	}

	// 分页：page 默认 1、page_size 默认 20，越界 400。
	pageTwo := sharedModulesDecodeArray(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings?page=2&page_size=2", sharedModulesSubjectSession, nil))
	if len(pageTwo) != 1 {
		t.Fatalf("page 2 length = %d, want 1", len(pageTwo))
	}
	emptyPage := harness.request(t, http.MethodGet, shared.Prefix+"/settings?page=9&page_size=2", sharedModulesSubjectSession, nil)
	if strings.TrimSpace(emptyPage.Body.String()) != "[]" {
		t.Fatalf("empty page body = %q, want []", emptyPage.Body.String())
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings?page=0", sharedModulesSubjectSession, nil), http.StatusBadRequest, "invalid-request", "page must be positive and page_size must be between 1 and 100")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings?page_size=101", sharedModulesSubjectSession, nil), http.StatusBadRequest, "invalid-request", "page must be positive and page_size must be between 1 and 100")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings?page=abc", sharedModulesSubjectSession, nil), http.StatusBadRequest, "invalid-request", "")

	// 唯一键冲突。
	duplicate := harness.request(t, http.MethodPost, shared.Prefix+"/settings", sharedModulesSubjectSession, map[string]any{
		"category": "zzz",
		"code":     "a",
		"payload":  map[string]any{},
	})
	sharedModulesRequireProblem(t, duplicate, http.StatusConflict, "conflict", "setting category and code already exist")

	// 校验：payload 必须是对象；category/code 必填。
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/settings", sharedModulesSubjectSession, map[string]any{
		"category": "zzz",
		"code":     "array",
		"payload":  []any{1, 2},
	}), http.StatusBadRequest, "invalid-request", "payload must be a JSON object")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPost, shared.Prefix+"/settings", sharedModulesSubjectSession, map[string]any{
		"code":    "no-category",
		"payload": map[string]any{},
	}), http.StatusBadRequest, "invalid-request", "category and code are required")

	// GET / PUT / DELETE。
	fetched := sharedModulesDecode[settingResponse](t, harness.request(t, http.MethodGet, shared.Prefix+"/settings/"+second.ID, sharedModulesSubjectSession, nil))
	if fetched.ID != second.ID || fetched.Payload["name"] != "A" {
		t.Fatalf("fetched setting = %#v", fetched)
	}
	updated := sharedModulesDecode[settingResponse](t, harness.request(t, http.MethodPut, shared.Prefix+"/settings/"+second.ID, sharedModulesSubjectSession, map[string]any{
		"category":    "zzz",
		"code":        "a",
		"root_code":   "root",
		"parent_code": "parent",
		"payload":     map[string]any{"name": "A2"},
	}))
	if updated.RootCode != "root" || updated.ParentCode != "parent" || updated.Payload["name"] != "A2" {
		t.Fatalf("updated setting = %#v", updated)
	}
	if updated.CreatedAt != second.CreatedAt {
		t.Fatalf("created_at changed on update: %q -> %q", second.CreatedAt, updated.CreatedAt)
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/settings/"+second.ID, sharedModulesSubjectSession, map[string]any{
		"category": "aaa",
		"code":     "c",
		"payload":  map[string]any{},
	}), http.StatusConflict, "conflict", "setting category and code already exist")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings/not-a-real-id", sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "setting not found")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodPut, shared.Prefix+"/settings/not-a-real-id", sharedModulesSubjectSession, map[string]any{
		"category": "x",
		"code":     "y",
		"payload":  map[string]any{},
	}), http.StatusNotFound, "not-found", "setting not found")

	if recorder := harness.request(t, http.MethodDelete, shared.Prefix+"/settings/"+first.ID, sharedModulesSubjectSession, nil); recorder.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d, want 204 (body %s)", recorder.Code, recorder.Body.String())
	}
	sharedModulesRequireProblem(t, harness.request(t, http.MethodGet, shared.Prefix+"/settings/"+first.ID, sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "setting not found")
	sharedModulesRequireProblem(t, harness.request(t, http.MethodDelete, shared.Prefix+"/settings/"+first.ID, sharedModulesSubjectSession, nil), http.StatusNotFound, "not-found", "setting not found")

	// 尾斜杠兼容：GET /settings/ 与 POST /settings/ 都等同集合根。
	trailingList := harness.request(t, http.MethodGet, shared.Prefix+"/settings/", sharedModulesSubjectSession, nil)
	if trailingList.Code != http.StatusOK {
		t.Fatalf("trailing-slash list status = %d (body %s)", trailingList.Code, trailingList.Body.String())
	}
	trailingCreate := harness.request(t, http.MethodPost, shared.Prefix+"/settings/", sharedModulesSubjectSession, map[string]any{
		"category": "trailing",
		"code":     "slash",
		"payload":  map[string]any{"ok": true},
	})
	if trailingCreate.Code != http.StatusCreated {
		t.Fatalf("trailing-slash create status = %d (body %s)", trailingCreate.Code, trailingCreate.Body.String())
	}

	// 无会话 -> 401 Problem Details。
	anonymous := harness.request(t, http.MethodGet, shared.Prefix+"/settings", "", nil)
	sharedModulesRequireProblem(t, anonymous, http.StatusUnauthorized, "not-authenticated", "not authenticated")
}
