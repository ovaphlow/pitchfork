package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/httpapi"
	"github.com/ovaphlow/pitchfork/platform/internal/identity"
)

// 055：共享路由（/crate-api/v1/*）的真实接线测试——用 platformd 真实
// 登录/自注册会话（不是桩），确认 NewMux 的 RequireSession、043 写门禁与文件存储。
func TestSharedRoutesUsePlatformSessionAndAuthorizationGate(t *testing.T) {
	context := context.Background()
	databaseConnection, err := database.OpenSQLite(context, filepath.Join(t.TempDir(), "platformd.sqlite"))
	if err != nil {
		t.Fatalf("open SQLite database: %v", err)
	}
	t.Cleanup(func() { databaseConnection.Close() })
	if _, err := database.Migrate(context, databaseConnection, migrations.Files); err != nil {
		t.Fatalf("migrate database: %v", err)
	}
	if _, err := identity.EnsureBootstrap(context, databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	filesDir := filepath.Join(t.TempDir(), "files")
	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings:      identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:        testLoginThrottle,
		SharedFilesDir:       filesDir,
		SharedMaxUploadBytes: 1 << 20,
	})

	t.Run("without a session every shared route is 401", func(t *testing.T) {
		response := httptest.NewRecorder()
		mux.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/crate-api/v1/roles", nil))
		assertSharedProblem(t, response, http.StatusUnauthorized, "/crate-api/v1/problems/not-authenticated", "")
	})

	adminSessionCookie, _ := loginCookies(t, mux, "admin", "correct horse battery staple")

	t.Run("platform administrator can write the role directory", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodPost, "/crate-api/v1/roles", strings.NewReader(
			`{"role_code":"nursing.staff","display_name":"护理"}`))
		request.AddCookie(adminSessionCookie)
		response := httptest.NewRecorder()
		mux.ServeHTTP(response, request)
		if response.Code != http.StatusCreated {
			t.Fatalf("create role status = %d, want %d; body = %s", response.Code, http.StatusCreated, response.Body.String())
		}
	})

	// 业务账号（自注册，platform_admin=false，无角色）只能读目录，不能写。
	registerBody := `{"display_name":"Nurse","identifier":"nurse01","password":"correct horse battery staple"}`
	registerRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/registrations", strings.NewReader(registerBody))
	registerRequest.Header.Set("Accept", "application/json")
	registerResponse := httptest.NewRecorder()
	mux.ServeHTTP(registerResponse, registerRequest)
	if registerResponse.Code != http.StatusCreated {
		t.Fatalf("register status = %d, want %d; body = %s", registerResponse.Code, http.StatusCreated, registerResponse.Body.String())
	}
	var nurseSessionCookie *http.Cookie
	for _, cookie := range registerResponse.Result().Cookies() {
		if cookie.Name == "platformd_session" {
			nurseSessionCookie = cookie
		}
	}
	if nurseSessionCookie == nil {
		t.Fatalf("registration did not set the platformd_session cookie")
	}

	t.Run("read routes accept any full session", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodGet, "/crate-api/v1/roles", nil)
		request.AddCookie(nurseSessionCookie)
		response := httptest.NewRecorder()
		mux.ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("list roles status = %d, want %d; body = %s", response.Code, http.StatusOK, response.Body.String())
		}
		var roles []json.RawMessage
		if err := json.Unmarshal(response.Body.Bytes(), &roles); err != nil {
			t.Fatalf("decode roles: %v", err)
		}
	})

	t.Run("write routes keep the 043 authorization gate", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodPost, "/crate-api/v1/roles", strings.NewReader(
			`{"role_code":"escalation","display_name":"提权"}`))
		request.AddCookie(nurseSessionCookie)
		response := httptest.NewRecorder()
		mux.ServeHTTP(response, request)
		assertSharedProblem(t, response, http.StatusForbidden, "/crate-api/v1/problems/permission-required", "access:manage")
	})

	t.Run("file upload stores the entity under PLATFORMD_FILES_DIR", func(t *testing.T) {
		var body bytes.Buffer
		writer := multipart.NewWriter(&body)
		part, err := writer.CreateFormFile("file", "护理计划.pdf")
		if err != nil {
			t.Fatalf("create multipart file: %v", err)
		}
		if _, err := part.Write([]byte("hello shared files")); err != nil {
			t.Fatalf("write multipart file: %v", err)
		}
		if err := writer.Close(); err != nil {
			t.Fatalf("close multipart writer: %v", err)
		}

		request := httptest.NewRequest(http.MethodPost, "/crate-api/v1/files/upload", &body)
		request.Header.Set("Content-Type", writer.FormDataContentType())
		request.AddCookie(adminSessionCookie)
		response := httptest.NewRecorder()
		mux.ServeHTTP(response, request)
		if response.Code != http.StatusCreated {
			t.Fatalf("upload status = %d, want %d; body = %s", response.Code, http.StatusCreated, response.Body.String())
		}
		var uploaded struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &uploaded); err != nil {
			t.Fatalf("decode upload response: %v", err)
		}
		stored, err := os.ReadFile(filepath.Join(filesDir, uploaded.ID))
		if err != nil {
			t.Fatalf("read stored file: %v", err)
		}
		if string(stored) != "hello shared files" {
			t.Fatalf("stored file = %q", string(stored))
		}
	})
}

// assertSharedProblem 校验共享模块的 RFC 9457 形状（type 前缀为 /crate-api/v1）。
func assertSharedProblem(t *testing.T, response *httptest.ResponseRecorder, wantStatus int, wantType string, wantRequiredPermission string) {
	t.Helper()
	if response.Code != wantStatus {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, wantStatus, response.Body.String())
	}
	if contentType := response.Header().Get("Content-Type"); contentType != "application/problem+json" {
		t.Fatalf("content type = %q", contentType)
	}
	var body struct {
		Type               string `json:"type"`
		Status             int    `json:"status"`
		RequiredPermission string `json:"required_permission"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode problem details: %v", err)
	}
	if body.Type != wantType {
		t.Fatalf("problem type = %q, want %q", body.Type, wantType)
	}
	if body.Status != wantStatus {
		t.Fatalf("problem status = %d, want %d", body.Status, wantStatus)
	}
	if body.RequiredPermission != wantRequiredPermission {
		t.Fatalf("required_permission = %q, want %q", body.RequiredPermission, wantRequiredPermission)
	}
}
