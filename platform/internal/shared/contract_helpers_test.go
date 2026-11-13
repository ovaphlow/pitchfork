package shared_test

// 契约回归测试（task-13）：独立于开发实现，把 service-nexus-shared/tests/*.rs 的
// 可观察断言翻译为 Go 黑盒 HTTP 测试。只依赖 shared 包的导出 API：
// shared.NewHandler(shared.Store{...}) + Handler.Register(mux) + httptest.Server。
// 任何生产代码、migration、其他人的测试都不在这里修改。

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

// 与 tests/authorization.rs 相同的 26 位 ULID 主体。
const (
	contractAdminID   = "01KYE3F6CPNJS16RHT01M64D8B"
	contractManagerID = "01M3ZR3CENV2DGYY2RS1BPJG2D"
	contractStaffID   = "01M3ZR3CDAQ6ZKHCYD065RSYMT"
	contractOtherID   = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
)

// 会话桩的取值：请求头 X-Contract-Session 决定当前主体（对应 Rust 测试里的
// platformd_session cookie 桩），缺省表示没有会话。
const (
	contractSessionHeader  = "X-Contract-Session"
	contractAdminSession   = "admin"   // platform_admin=true
	contractManagerSession = "manager" // 普通主体，测试按需授予 access:manage
	contractStaffSession   = "staff"   // 普通业务主体
)

var contractIDCounter atomic.Int64

// contractNewID 生成一个测试内唯一的 26 位 ID（用于直接造 roles 等数据的行）。
func contractNewID() string {
	return fmt.Sprintf("01J%023d", contractIDCounter.Add(1))
}

type contractHarness struct {
	t              *testing.T
	db             *sql.DB
	filesDir       string
	handler        *shared.Handler
	server         *httptest.Server
	client         *http.Client
	maxUploadBytes int64
}

// newContractHarness 起一个真实 HTTP 服务：临时 SQLite + 迁移 + shared.Handler + mux。
func newContractHarness(t *testing.T) *contractHarness {
	t.Helper()
	return newContractHarnessWithUploadLimit(t, 20<<20)
}

func newContractHarnessWithUploadLimit(t *testing.T, maxUploadBytes int64) *contractHarness {
	t.Helper()

	ctx := context.Background()
	databaseConnection, err := database.OpenSQLite(ctx, filepath.Join(t.TempDir(), "shared.sqlite"))
	if err != nil {
		t.Fatalf("open test SQLite: %v", err)
	}
	if _, err := database.Migrate(ctx, databaseConnection, migrations.Files); err != nil {
		databaseConnection.Close()
		t.Fatalf("migrate test SQLite: %v", err)
	}

	filesDir := t.TempDir()
	handler := shared.NewHandler(shared.Store{
		DB:             databaseConnection,
		FilesDir:       filesDir,
		MaxUploadBytes: maxUploadBytes,
		RequireSession: contractSessionStub,
	})
	mux := http.NewServeMux()
	handler.Register(mux)
	server := httptest.NewServer(mux)

	client := &http.Client{
		// 不跟随重定向：尾斜杠兼容性必须由 handler 直接 200，而不是 301 掩盖。
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	t.Cleanup(func() {
		server.Close()
		if err := databaseConnection.Close(); err != nil {
			t.Logf("close test SQLite: %v", err)
		}
	})

	return &contractHarness{
		t:              t,
		db:             databaseConnection,
		filesDir:       filesDir,
		handler:        handler,
		server:         server,
		client:         client,
		maxUploadBytes: maxUploadBytes,
	}
}

// contractSessionStub 按请求头返回会话；对应 Rust 测试里按 cookie 切换身份的桩 IDP。
func contractSessionStub(request *http.Request) (shared.Session, bool) {
	switch request.Header.Get(contractSessionHeader) {
	case contractAdminSession:
		return shared.Session{SubjectID: contractAdminID, PlatformAdmin: true}, true
	case contractManagerSession:
		return shared.Session{SubjectID: contractManagerID}, true
	case contractStaffSession:
		return shared.Session{SubjectID: contractStaffID}, true
	default:
		return shared.Session{}, false
	}
}

type contractResponse struct {
	t      *testing.T
	method string
	path   string
	status int
	header http.Header
	body   []byte
}

func (harness *contractHarness) request(method, path, session string, body any) *contractResponse {
	harness.t.Helper()

	var payload []byte
	contentType := ""
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			harness.t.Fatalf("encode request body: %v", err)
		}
		payload = encoded
		contentType = "application/json"
	}
	return harness.requestRaw(method, path, session, payload, contentType)
}

func (harness *contractHarness) requestRaw(method, path, session string, body []byte, contentType string) *contractResponse {
	harness.t.Helper()

	url := harness.server.URL + path
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	request, err := http.NewRequest(method, url, reader)
	if err != nil {
		harness.t.Fatalf("build request %s %s: %v", method, path, err)
	}
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
	request.Header.Set("Accept", "application/json")
	if session != "" {
		request.Header.Set(contractSessionHeader, session)
	}

	response, err := harness.client.Do(request)
	if err != nil {
		harness.t.Fatalf("do request %s %s: %v", method, path, err)
	}
	responseBody, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil {
		harness.t.Fatalf("read response %s %s: %v", method, path, err)
	}
	return &contractResponse{
		t:      harness.t,
		method: method,
		path:   path,
		status: response.StatusCode,
		header: response.Header,
		body:   responseBody,
	}
}

// sharedPath 拼接共享模块的 API 前缀。
func sharedPath(suffix string) string {
	return shared.Prefix + suffix
}

func (response *contractResponse) requireStatus(want int) *contractResponse {
	response.t.Helper()
	if response.status != want {
		response.t.Fatalf("%s %s: status = %d, want %d; body = %s",
			response.method, response.path, response.status, want, response.body)
	}
	return response
}

func (response *contractResponse) decodeAny() any {
	response.t.Helper()
	var value any
	if err := json.Unmarshal(response.body, &value); err != nil {
		response.t.Fatalf("%s %s: decode JSON (status %d): %v; body = %s",
			response.method, response.path, response.status, err, response.body)
	}
	return value
}

func (response *contractResponse) object() map[string]any {
	response.t.Helper()
	value, ok := response.decodeAny().(map[string]any)
	if !ok {
		response.t.Fatalf("%s %s: response is not a JSON object; body = %s",
			response.method, response.path, response.body)
	}
	return value
}

func (response *contractResponse) array() []any {
	response.t.Helper()
	value, ok := response.decodeAny().([]any)
	if !ok {
		response.t.Fatalf("%s %s: response is not a JSON array; body = %s",
			response.method, response.path, response.body)
	}
	return value
}

// requireProblem 断言 RFC 9457 Problem Details 形状（type/title/status/detail，
// 403 额外 required_permission，且不输出 instance）。
func (response *contractResponse) requireProblem(status int, slug string, requiredPermission string) map[string]any {
	response.t.Helper()
	response.requireStatus(status)

	if contentType := response.header.Get("Content-Type"); !strings.HasPrefix(contentType, "application/problem+json") {
		response.t.Fatalf("%s %s: Content-Type = %q, want application/problem+json; body = %s",
			response.method, response.path, contentType, response.body)
	}

	body := response.object()
	if got := contractString(response.t, body, "type"); got != shared.Prefix+"/problems/"+slug {
		response.t.Fatalf("%s %s: problem type = %q, want %q",
			response.method, response.path, got, shared.Prefix+"/problems/"+slug)
	}
	if got := contractNumber(response.t, body, "status"); got != float64(status) {
		response.t.Fatalf("%s %s: problem status field = %v, want %d",
			response.method, response.path, got, status)
	}
	if got := contractString(response.t, body, "title"); got != http.StatusText(status) {
		response.t.Fatalf("%s %s: problem title = %q, want %q",
			response.method, response.path, got, http.StatusText(status))
	}
	if got := contractString(response.t, body, "detail"); got == "" {
		response.t.Fatalf("%s %s: problem detail is empty", response.method, response.path)
	}
	if _, exists := body["instance"]; exists {
		response.t.Fatalf("%s %s: problem must not contain instance; body = %s",
			response.method, response.path, response.body)
	}
	if requiredPermission != "" {
		if got := contractString(response.t, body, "required_permission"); got != requiredPermission {
			response.t.Fatalf("%s %s: required_permission = %q, want %q; body = %s",
				response.method, response.path, got, requiredPermission, response.body)
		}
	}
	return body
}

// ---------------------------------------------------------------------------
// JSON 字段断言辅助
// ---------------------------------------------------------------------------

func contractString(t *testing.T, object map[string]any, key string) string {
	t.Helper()
	value, exists := object[key]
	if !exists {
		t.Fatalf("JSON object missing key %q; object = %v", key, object)
	}
	text, ok := value.(string)
	if !ok {
		t.Fatalf("JSON key %q = %v (%T), want string", key, value, value)
	}
	return text
}

func contractNumber(t *testing.T, object map[string]any, key string) float64 {
	t.Helper()
	value, exists := object[key]
	if !exists {
		t.Fatalf("JSON object missing key %q; object = %v", key, object)
	}
	number, ok := value.(float64)
	if !ok {
		t.Fatalf("JSON key %q = %v (%T), want number", key, value, value)
	}
	return number
}

func contractBool(t *testing.T, object map[string]any, key string) bool {
	t.Helper()
	value, exists := object[key]
	if !exists {
		t.Fatalf("JSON object missing key %q; object = %v", key, object)
	}
	boolean, ok := value.(bool)
	if !ok {
		t.Fatalf("JSON key %q = %v (%T), want bool", key, value, value)
	}
	return boolean
}

func contractArrayField(t *testing.T, object map[string]any, key string) []any {
	t.Helper()
	value, exists := object[key]
	if !exists {
		t.Fatalf("JSON object missing key %q; object = %v", key, object)
	}
	array, ok := value.([]any)
	if !ok {
		t.Fatalf("JSON key %q = %v (%T), want array", key, value, value)
	}
	return array
}

func contractStringSlice(t *testing.T, object map[string]any, key string) []string {
	t.Helper()
	array := contractArrayField(t, object, key)
	result := make([]string, 0, len(array))
	for _, item := range array {
		text, ok := item.(string)
		if !ok {
			t.Fatalf("JSON key %q contains %v (%T), want string", key, item, item)
		}
		result = append(result, text)
	}
	return result
}

var (
	contractULIDPattern      = regexp.MustCompile("^[0-9A-HJKMNP-TV-Z]{26}$")
	contractTimestampPattern = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$`)
)

// contractULIDField 断言字段是 26 位 Crockford Base32 ULID 并返回它。
func contractULIDField(t *testing.T, object map[string]any, key string) string {
	t.Helper()
	value := contractString(t, object, key)
	if !contractULIDPattern.MatchString(value) {
		t.Fatalf("JSON key %q = %q, want 26-char ULID", key, value)
	}
	return value
}

// contractTimestampField 断言字段是 Nexus/SQLite 同形的 UTC 毫秒时间戳。
func contractTimestampField(t *testing.T, object map[string]any, key string) string {
	t.Helper()
	value := contractString(t, object, key)
	if !contractTimestampPattern.MatchString(value) {
		t.Fatalf("JSON key %q = %q, want format YYYY-MM-DDTHH:MM:SS.mmmZ", key, value)
	}
	return value
}

func contractObjectField(t *testing.T, object map[string]any, key string) map[string]any {
	t.Helper()
	value, exists := object[key]
	if !exists {
		t.Fatalf("JSON object missing key %q; object = %v", key, object)
	}
	nested, ok := value.(map[string]any)
	if !ok {
		t.Fatalf("JSON key %q = %v (%T), want object", key, value, value)
	}
	return nested
}

// contractStringsEqual 断言两个字符串序列完全一致。
func contractStringsEqual(t *testing.T, label string, got, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s = %v, want %v", label, got, want)
	}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("%s = %v, want %v", label, got, want)
		}
	}
}

// ---------------------------------------------------------------------------
// 直接造数：043 门禁的权限来自 roles.permission_codes + subject_roles 行。
// ---------------------------------------------------------------------------

func (harness *contractHarness) seedRole(t *testing.T, roleCode, displayName string, permissionCodes []string) string {
	t.Helper()
	encoded, err := json.Marshal(permissionCodes)
	if err != nil {
		t.Fatalf("encode permission codes: %v", err)
	}
	roleID := contractNewID()
	if _, err := harness.db.Exec(
		"INSERT INTO roles (id, role_code, display_name, description, permission_codes) VALUES (?, ?, ?, '', ?)",
		roleID, roleCode, displayName, string(encoded),
	); err != nil {
		t.Fatalf("seed role %q: %v", roleCode, err)
	}
	return roleID
}

func (harness *contractHarness) seedSubjectRole(t *testing.T, subjectID, roleID, grantedBy string) {
	t.Helper()
	if _, err := harness.db.Exec(
		"INSERT INTO subject_roles (id, subject_id, role_id, granted_by_subject_id) VALUES (?, ?, ?, ?)",
		contractNewID(), subjectID, roleID, grantedBy,
	); err != nil {
		t.Fatalf("seed subject role: %v", err)
	}
}

func (harness *contractHarness) seedDepartment(t *testing.T, code, name string) string {
	t.Helper()
	departmentID := contractNewID()
	if _, err := harness.db.Exec(
		"INSERT INTO departments (id, code, name, description, parent_code, sort_order) VALUES (?, ?, ?, '', '', 0)",
		departmentID, code, name,
	); err != nil {
		t.Fatalf("seed department %q: %v", code, err)
	}
	return departmentID
}

func (harness *contractHarness) rowCount(t *testing.T, query string, args ...any) int {
	t.Helper()
	var count int
	if err := harness.db.QueryRow(query, args...).Scan(&count); err != nil {
		t.Fatalf("query row count: %v", err)
	}
	return count
}

// uploadFile 构造 multipart/form-data 上传（等价的 Rust helper request_file）。
func (harness *contractHarness) uploadFile(session, fieldName, filename, contentType string, content []byte) *contractResponse {
	harness.t.Helper()

	var buffer bytes.Buffer
	writer := multipart.NewWriter(&buffer)
	header := make(textproto.MIMEHeader)
	header.Set("Content-Disposition", fmt.Sprintf("form-data; name=%q; filename=%q", fieldName, filename))
	header.Set("Content-Type", contentType)
	part, err := writer.CreatePart(header)
	if err != nil {
		harness.t.Fatalf("create multipart part: %v", err)
	}
	if _, err := part.Write(content); err != nil {
		harness.t.Fatalf("write multipart part: %v", err)
	}
	if err := writer.Close(); err != nil {
		harness.t.Fatalf("close multipart writer: %v", err)
	}
	return harness.requestRaw(http.MethodPost, sharedPath("/files/upload"), session, buffer.Bytes(), writer.FormDataContentType())
}

// readStoredFile 读取 FilesDir 下的物理文件。
func (harness *contractHarness) readStoredFile(t *testing.T, storedName string) ([]byte, error) {
	t.Helper()
	return os.ReadFile(filepath.Join(harness.filesDir, storedName))
}
