package httpapi_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/httpapi"
	"github.com/ovaphlow/pitchfork/platform/internal/identity"
)

var testLoginThrottle = identity.LoginThrottleSettings{
	Secret:          []byte("test-login-throttle-secret-with-at-least-32-bytes"),
	FailureLimit:    3,
	Window:          time.Hour,
	LockoutDuration: time.Hour,
}

func TestHealthzReturnsNoContentWhenDatabaseIsAvailable(t *testing.T) {
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "platformd.sqlite"))
	if err != nil {
		t.Fatalf("open SQLite database: %v", err)
	}
	t.Cleanup(func() {
		databaseConnection.Close()
	})

	request := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/healthz", nil)
	response := httptest.NewRecorder()

	httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	}).ServeHTTP(response, request)

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusNoContent)
	}
	if contentType := response.Header().Get("Content-Type"); contentType != "" {
		t.Fatalf("content type = %q", contentType)
	}
	if body := response.Body.String(); body != "" {
		t.Fatalf("body = %q", body)
	}
}

func TestProblemDetailsResponses(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})

	unauthenticatedRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	unauthenticatedResponse := httptest.NewRecorder()
	mux.ServeHTTP(unauthenticatedResponse, unauthenticatedRequest)
	assertProblemDetails(t, unauthenticatedResponse, http.StatusUnauthorized, "not-authenticated", "/crate-api/identity/v1/subjects")

	adminSession, adminCSRF := loginCookies(t, mux, "admin", "correct horse battery staple")

	missingCSRFRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/subjects", strings.NewReader(`{"display_name":"张三","identifier":"zhangsan","password":"a sufficiently long password"}`))
	missingCSRFRequest.Header.Set("Content-Type", "application/json")
	missingCSRFRequest.AddCookie(adminSession)
	missingCSRFResponse := httptest.NewRecorder()
	mux.ServeHTTP(missingCSRFResponse, missingCSRFRequest)
	assertProblemDetails(t, missingCSRFResponse, http.StatusForbidden, "invalid-csrf-token", "/crate-api/identity/v1/subjects")

	invalidJSONRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/subjects", strings.NewReader(`{"display_name":`))
	invalidJSONRequest.Header.Set("Content-Type", "application/json")
	invalidJSONRequest.Header.Set("X-CSRF-Token", adminCSRF.Value)
	invalidJSONRequest.AddCookie(adminSession)
	invalidJSONRequest.AddCookie(adminCSRF)
	invalidJSONResponse := httptest.NewRecorder()
	mux.ServeHTTP(invalidJSONResponse, invalidJSONRequest)
	assertProblemDetails(t, invalidJSONResponse, http.StatusBadRequest, "invalid-request", "/crate-api/identity/v1/subjects")

	missingSubjectRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects/unknown-subject", nil)
	missingSubjectRequest.AddCookie(adminSession)
	missingSubjectResponse := httptest.NewRecorder()
	mux.ServeHTTP(missingSubjectResponse, missingSubjectRequest)
	assertProblemDetails(t, missingSubjectResponse, http.StatusNotFound, "subject-not-found", "/crate-api/identity/v1/subjects/unknown-subject")

	invalidPaginationRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects?limit=invalid", nil)
	invalidPaginationRequest.AddCookie(adminSession)
	invalidPaginationResponse := httptest.NewRecorder()
	mux.ServeHTTP(invalidPaginationResponse, invalidPaginationRequest)
	assertProblemDetails(t, invalidPaginationResponse, http.StatusBadRequest, "invalid-request", "/crate-api/identity/v1/subjects?limit=invalid")

	unknownRouteRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/unknown", nil)
	unknownRouteResponse := httptest.NewRecorder()
	mux.ServeHTTP(unknownRouteResponse, unknownRouteRequest)
	assertProblemDetails(t, unknownRouteResponse, http.StatusNotFound, "not-found", "/crate-api/identity/v1/unknown")

	unsupportedMethodRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/healthz", nil)
	unsupportedMethodResponse := httptest.NewRecorder()
	mux.ServeHTTP(unsupportedMethodResponse, unsupportedMethodRequest)
	assertProblemDetails(t, unsupportedMethodResponse, http.StatusMethodNotAllowed, "method-not-allowed", "/crate-api/identity/v1/healthz")
	if allow := unsupportedMethodResponse.Header().Get("Allow"); !strings.Contains(allow, http.MethodGet) {
		t.Errorf("405 Allow header = %q, want it to contain %q", allow, http.MethodGet)
	}

	htmxCSRFRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/subjects", strings.NewReader("display_name=张三&identifier=zhangsan&password=a+sufficiently+long+password"))
	htmxCSRFRequest.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	htmxCSRFRequest.Header.Set("HX-Request", "true")
	htmxCSRFRequest.AddCookie(adminSession)
	htmxCSRFResponse := httptest.NewRecorder()
	mux.ServeHTTP(htmxCSRFResponse, htmxCSRFRequest)
	if htmxCSRFResponse.Code != http.StatusUnprocessableEntity {
		t.Fatalf("HTMX CSRF status = %d, want %d", htmxCSRFResponse.Code, http.StatusUnprocessableEntity)
	}
	if redirect := htmxCSRFResponse.Header().Get("HX-Redirect"); redirect != "/crate-api/identity/v1/subjects?error=1" {
		t.Errorf("HTMX CSRF redirect = %q", redirect)
	}
	if htmxCSRFResponse.Body.Len() != 0 {
		t.Errorf("HTMX CSRF response body = %q, want empty", htmxCSRFResponse.Body.String())
	}
}

func TestLoginDashboardAndCSRFFlow(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})
	form := url.Values{"identifier": {"admin"}, "password": {"correct horse battery staple"}}
	loginRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/sessions", strings.NewReader(form.Encode()))
	loginRequest.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	loginResponse := httptest.NewRecorder()
	mux.ServeHTTP(loginResponse, loginRequest)
	if loginResponse.Code != http.StatusSeeOther {
		t.Fatalf("login status = %d, want %d", loginResponse.Code, http.StatusSeeOther)
	}

	cookies := loginResponse.Result().Cookies()
	var sessionCookie, csrfCookie *http.Cookie
	for _, cookie := range cookies {
		switch cookie.Name {
		case "platformd_session":
			sessionCookie = cookie
		case "platformd_csrf":
			csrfCookie = cookie
		}
	}
	if sessionCookie == nil || csrfCookie == nil || !sessionCookie.HttpOnly || csrfCookie.HttpOnly {
		t.Fatalf("invalid login cookies: %#v", cookies)
	}
	if sessionCookie.Path != "/" || csrfCookie.Path != "/" {
		t.Fatalf("login cookie paths = session:%q csrf:%q, want /", sessionCookie.Path, csrfCookie.Path)
	}

	currentSessionRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/session", nil)
	currentSessionRequest.AddCookie(sessionCookie)
	currentSessionResponse := httptest.NewRecorder()
	mux.ServeHTTP(currentSessionResponse, currentSessionRequest)
	if currentSessionResponse.Code != http.StatusOK {
		t.Fatalf("current session status = %d, body = %s", currentSessionResponse.Code, currentSessionResponse.Body.String())
	}
	var currentSession struct {
		SubjectID     string `json:"subject_id"`
		Access        string `json:"access"`
		PlatformAdmin bool   `json:"platform_admin"`
		DisplayName   string `json:"display_name"`
		Identifier    string `json:"identifier"`
	}
	if err := json.Unmarshal(currentSessionResponse.Body.Bytes(), &currentSession); err != nil {
		t.Fatalf("decode current session: %v", err)
	}
	if currentSession.SubjectID == "" || currentSession.Access != "完整" {
		t.Fatalf("current session = %#v", currentSession)
	}
	// 042：代操作门禁与"我是谁"展示依赖这两个新增字段，不能悄悄缺失。
	if !currentSession.PlatformAdmin {
		t.Fatalf("current session platform_admin = false, want true for the bootstrap administrator")
	}
	if currentSession.DisplayName != "系统管理员" {
		t.Fatalf("current session display_name = %q, want %q", currentSession.DisplayName, "系统管理员")
	}
	if currentSession.Identifier != "admin" {
		t.Fatalf("current session identifier = %q, want %q", currentSession.Identifier, "admin")
	}

	dashboardRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/dashboard", nil)
	dashboardRequest.AddCookie(sessionCookie)
	dashboardResponse := httptest.NewRecorder()
	mux.ServeHTTP(dashboardResponse, dashboardRequest)
	if dashboardResponse.Code != http.StatusOK {
		t.Fatalf("dashboard status = %d, want %d", dashboardResponse.Code, http.StatusOK)
	}

	logoutRequest := httptest.NewRequest(http.MethodDelete, "/crate-api/identity/v1/sessions/current", nil)
	logoutRequest.AddCookie(sessionCookie)
	logoutRequest.AddCookie(csrfCookie)
	logoutRequest.Header.Set("X-CSRF-Token", csrfCookie.Value)
	logoutResponse := httptest.NewRecorder()
	mux.ServeHTTP(logoutResponse, logoutRequest)
	if logoutResponse.Code != http.StatusNoContent {
		t.Fatalf("logout status = %d, want %d", logoutResponse.Code, http.StatusNoContent)
	}
	clearedCookies := map[string]bool{}
	for _, cookie := range logoutResponse.Result().Cookies() {
		if cookie.Name != "platformd_session" && cookie.Name != "platformd_csrf" {
			continue
		}
		if cookie.MaxAge >= 0 {
			t.Fatalf("cleared cookie = %#v, want MaxAge < 0", cookie)
		}
		if cookie.Path == "/" {
			clearedCookies[cookie.Name] = true
		}
	}
	if !clearedCookies["platformd_session"] || !clearedCookies["platformd_csrf"] {
		t.Fatalf("logout did not clear root session cookies: %#v", logoutResponse.Result().Cookies())
	}

	staleDashboardRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/dashboard", nil)
	staleDashboardRequest.AddCookie(sessionCookie)
	staleDashboardResponse := httptest.NewRecorder()
	mux.ServeHTTP(staleDashboardResponse, staleDashboardRequest)
	if staleDashboardResponse.Code != http.StatusSeeOther {
		t.Fatalf("stale dashboard status = %d, want %d", staleDashboardResponse.Code, http.StatusSeeOther)
	}

	staleSessionRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/session", nil)
	staleSessionRequest.AddCookie(sessionCookie)
	staleSessionResponse := httptest.NewRecorder()
	mux.ServeHTTP(staleSessionResponse, staleSessionRequest)
	assertProblemDetails(t, staleSessionResponse, http.StatusUnauthorized, "not-authenticated", "/crate-api/identity/v1/session")
}

func TestJSONLoginReturnsSessionAccessAndCookies(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})
	form := url.Values{"identifier": {"admin"}, "password": {"correct horse battery staple"}}
	loginRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/sessions", strings.NewReader(form.Encode()))
	loginRequest.Header.Set("Accept", "application/json")
	loginRequest.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	loginResponse := httptest.NewRecorder()
	mux.ServeHTTP(loginResponse, loginRequest)
	if loginResponse.Code != http.StatusOK {
		t.Fatalf("login status = %d, want %d", loginResponse.Code, http.StatusOK)
	}
	var loginResult struct {
		Access string `json:"access"`
	}
	if err := json.Unmarshal(loginResponse.Body.Bytes(), &loginResult); err != nil {
		t.Fatalf("decode login response: %v", err)
	}
	if loginResult.Access != "完整" {
		t.Fatalf("login access = %q, want 完整", loginResult.Access)
	}
	if len(loginResponse.Result().Cookies()) != 2 {
		t.Fatalf("login cookies = %#v, want session and csrf cookies", loginResponse.Result().Cookies())
	}

	wrongPasswordForm := url.Values{"identifier": {"admin"}, "password": {"wrong password"}}
	wrongPasswordRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/sessions", strings.NewReader(wrongPasswordForm.Encode()))
	wrongPasswordRequest.Header.Set("Accept", "application/json")
	wrongPasswordRequest.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	wrongPasswordResponse := httptest.NewRecorder()
	mux.ServeHTTP(wrongPasswordResponse, wrongPasswordRequest)
	assertProblemDetails(t, wrongPasswordResponse, http.StatusUnauthorized, "invalid-credentials", "/crate-api/identity/v1/sessions")
}

func TestSelfRegistrationCreatesSubjectAndSession(t *testing.T) {
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

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})

	body := `{"display_name":"张三","identifier":"New.User@Example.com","password":"correct horse battery staple"}`
	registerRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/registrations", strings.NewReader(body))
	registerRequest.Header.Set("Accept", "application/json")
	registerRequest.Header.Set("Content-Type", "application/json")
	registerResponse := httptest.NewRecorder()
	mux.ServeHTTP(registerResponse, registerRequest)
	if registerResponse.Code != http.StatusCreated {
		t.Fatalf("register status = %d, want %d; body = %s", registerResponse.Code, http.StatusCreated, registerResponse.Body.String())
	}

	var registered struct {
		SubjectID   string `json:"subject_id"`
		DisplayName string `json:"display_name"`
		Identifier  string `json:"identifier"`
		Access      string `json:"access"`
	}
	if err := json.Unmarshal(registerResponse.Body.Bytes(), &registered); err != nil {
		t.Fatalf("decode registration response: %v", err)
	}
	if len(registered.SubjectID) != 26 {
		t.Fatalf("subject_id = %q, want a 26 character ULID", registered.SubjectID)
	}
	if registered.DisplayName != "张三" {
		t.Fatalf("display_name = %q, want 张三", registered.DisplayName)
	}
	if registered.Identifier != "new.user@example.com" {
		t.Fatalf("identifier = %q, want normalized new.user@example.com", registered.Identifier)
	}
	if registered.Access != identity.SessionAccessFull {
		t.Fatalf("access = %q, want %q", registered.Access, identity.SessionAccessFull)
	}

	var sessionCookie *http.Cookie
	for _, cookie := range registerResponse.Result().Cookies() {
		if cookie.Name == "platformd_session" {
			sessionCookie = cookie
		}
	}
	if sessionCookie == nil {
		t.Fatal("registration did not return a session cookie")
	}

	sessionRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/session", nil)
	sessionRequest.AddCookie(sessionCookie)
	sessionResponse := httptest.NewRecorder()
	mux.ServeHTTP(sessionResponse, sessionRequest)
	if sessionResponse.Code != http.StatusOK {
		t.Fatalf("session status = %d, want %d", sessionResponse.Code, http.StatusOK)
	}
	var session struct {
		SubjectID     string `json:"subject_id"`
		DisplayName   string `json:"display_name"`
		Identifier    string `json:"identifier"`
		PlatformAdmin bool   `json:"platform_admin"`
	}
	if err := json.Unmarshal(sessionResponse.Body.Bytes(), &session); err != nil {
		t.Fatalf("decode session response: %v", err)
	}
	if session.SubjectID != registered.SubjectID || session.DisplayName != "张三" || session.PlatformAdmin {
		t.Fatalf("session = %+v, want the self-registered non-admin subject", session)
	}
	if session.Identifier != "new.user@example.com" {
		t.Fatalf("session identifier = %q, want %q", session.Identifier, "new.user@example.com")
	}
}

func TestSelfRegistrationRejectsDuplicateAndInvalidInput(t *testing.T) {
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

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})

	validBody := `{"display_name":"李四","identifier":"lisi","password":"correct horse battery staple"}`
	firstRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/registrations", strings.NewReader(validBody))
	firstRequest.Header.Set("Content-Type", "application/json")
	firstResponse := httptest.NewRecorder()
	mux.ServeHTTP(firstResponse, firstRequest)
	if firstResponse.Code != http.StatusCreated {
		t.Fatalf("first registration status = %d, want %d", firstResponse.Code, http.StatusCreated)
	}

	duplicateBody := `{"display_name":"李四","identifier":"LISI","password":"correct horse battery staple"}`
	duplicateRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/registrations", strings.NewReader(duplicateBody))
	duplicateRequest.Header.Set("Content-Type", "application/json")
	duplicateResponse := httptest.NewRecorder()
	mux.ServeHTTP(duplicateResponse, duplicateRequest)
	assertProblemDetails(t, duplicateResponse, http.StatusConflict, "identifier-already-exists", "/crate-api/identity/v1/registrations")

	shortPasswordBody := `{"display_name":"王五","identifier":"wangwu","password":"too-short"}`
	shortPasswordRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/registrations", strings.NewReader(shortPasswordBody))
	shortPasswordRequest.Header.Set("Content-Type", "application/json")
	shortPasswordResponse := httptest.NewRecorder()
	mux.ServeHTTP(shortPasswordResponse, shortPasswordRequest)
	assertProblemDetails(t, shortPasswordResponse, http.StatusBadRequest, "invalid-registration", "/crate-api/identity/v1/registrations")

	unknownFieldBody := `{"display_name":"王五","identifier":"wangwu","password":"correct horse battery staple","platform_admin":true}`
	unknownFieldRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/registrations", strings.NewReader(unknownFieldBody))
	unknownFieldRequest.Header.Set("Content-Type", "application/json")
	unknownFieldResponse := httptest.NewRecorder()
	mux.ServeHTTP(unknownFieldResponse, unknownFieldRequest)
	assertProblemDetails(t, unknownFieldResponse, http.StatusBadRequest, "invalid-request", "/crate-api/identity/v1/registrations")
}

// TestPlatformAdministratorAPI 覆盖平台管理员开关：未认证 401、
// 且最后一个启用的平台管理员不可撤销（403 last-administrator）。
func TestPlatformAdministratorAPI(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})

	adminSession, adminCSRF := loginCookies(t, mux, "admin", "correct horse battery staple")

	subjectsRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	subjectsRequest.AddCookie(adminSession)
	subjectsResponse := httptest.NewRecorder()
	mux.ServeHTTP(subjectsResponse, subjectsRequest)
	if subjectsResponse.Code != http.StatusOK {
		t.Fatalf("list subjects status = %d, body = %s", subjectsResponse.Code, subjectsResponse.Body.String())
	}
	var subjectsPayload struct {
		Records []identity.Subject `json:"records"`
	}
	if err := json.Unmarshal(subjectsResponse.Body.Bytes(), &subjectsPayload); err != nil {
		t.Fatalf("decode subjects payload: %v", err)
	}
	if len(subjectsPayload.Records) != 1 || !subjectsPayload.Records[0].PlatformAdmin {
		t.Fatalf("bootstrap subject = %#v", subjectsPayload.Records)
	}
	adminID := subjectsPayload.Records[0].ID

	unauthenticated := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/subjects/"+adminID, strings.NewReader(`{"platform_admin":false}`))
	unauthenticated.Header.Set("Content-Type", "application/json")
	unauthenticatedResponse := httptest.NewRecorder()
	mux.ServeHTTP(unauthenticatedResponse, unauthenticated)
	if unauthenticatedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated platform admin status = %d", unauthenticatedResponse.Code)
	}

	demote := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/subjects/"+adminID, strings.NewReader(`{"platform_admin":false}`))
	demote.Header.Set("Content-Type", "application/json")
	demote.Header.Set("X-CSRF-Token", adminCSRF.Value)
	demote.AddCookie(adminSession)
	demote.AddCookie(adminCSRF)
	demoteResponse := httptest.NewRecorder()
	mux.ServeHTTP(demoteResponse, demote)
	assertProblemDetails(t, demoteResponse, http.StatusForbidden, "last-administrator", "/crate-api/identity/v1/subjects/"+adminID)
}

func TestAdministratorSubjectManagementAPI(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})
	adminSession, adminCSRF := loginCookies(t, mux, "admin", "correct horse battery staple")

	unauthenticatedList := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	unauthenticatedListResponse := httptest.NewRecorder()
	mux.ServeHTTP(unauthenticatedListResponse, unauthenticatedList)
	if unauthenticatedListResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated subject list status = %d", unauthenticatedListResponse.Code)
	}

	missingCSRF := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/subjects", strings.NewReader(`{"display_name":"张三","identifier":"zhangsan","password":"a sufficiently long password"}`))
	missingCSRF.Header.Set("Content-Type", "application/json")
	missingCSRF.AddCookie(adminSession)
	missingCSRFResponse := httptest.NewRecorder()
	mux.ServeHTTP(missingCSRFResponse, missingCSRF)
	if missingCSRFResponse.Code != http.StatusForbidden {
		t.Fatalf("create subject without CSRF status = %d", missingCSRFResponse.Code)
	}

	createRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/subjects", strings.NewReader(`{"display_name":"张三","identifier":"ZhangSan","password":"a sufficiently long password"}`))
	createRequest.Header.Set("Content-Type", "application/json")
	createRequest.Header.Set("X-CSRF-Token", adminCSRF.Value)
	createRequest.AddCookie(adminSession)
	createRequest.AddCookie(adminCSRF)
	createResponse := httptest.NewRecorder()
	mux.ServeHTTP(createResponse, createRequest)
	if createResponse.Code != http.StatusCreated {
		t.Fatalf("create subject status = %d, body = %s", createResponse.Code, createResponse.Body.String())
	}
	var created identity.Subject
	if err := json.Unmarshal(createResponse.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode created subject: %v", err)
	}
	if created.Identifier != "zhangsan" || created.Status != "启用" {
		t.Fatalf("created subject = %#v", created)
	}

	nonAdministratorSession, _ := loginCookies(t, mux, "zhangsan", "a sufficiently long password")
	nonAdministratorList := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	nonAdministratorList.AddCookie(nonAdministratorSession)
	nonAdministratorListResponse := httptest.NewRecorder()
	mux.ServeHTTP(nonAdministratorListResponse, nonAdministratorList)
	if nonAdministratorListResponse.Code != http.StatusForbidden {
		t.Fatalf("non-administrator subject list status = %d", nonAdministratorListResponse.Code)
	}

	listRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects?limit=20&offset=0", nil)
	listRequest.AddCookie(adminSession)
	listResponse := httptest.NewRecorder()
	mux.ServeHTTP(listResponse, listRequest)
	if listResponse.Code != http.StatusOK {
		t.Fatalf("list subject status = %d, body = %s", listResponse.Code, listResponse.Body.String())
	}
	var listed struct {
		Records []identity.Subject `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(listResponse.Body.Bytes(), &listed); err != nil {
		t.Fatalf("decode subject list: %v", err)
	}
	if listed.Meta.Total != 2 || len(listed.Records) != 2 {
		t.Fatalf("listed subjects = %#v", listed)
	}

	detailRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects/"+created.ID, nil)
	detailRequest.AddCookie(adminSession)
	detailResponse := httptest.NewRecorder()
	mux.ServeHTTP(detailResponse, detailRequest)
	if detailResponse.Code != http.StatusOK {
		t.Fatalf("subject detail status = %d", detailResponse.Code)
	}

	disableRequest := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/subjects/"+created.ID, strings.NewReader(`{"status":"禁用"}`))
	disableRequest.Header.Set("Content-Type", "application/json")
	disableRequest.Header.Set("X-CSRF-Token", adminCSRF.Value)
	disableRequest.AddCookie(adminSession)
	disableRequest.AddCookie(adminCSRF)
	disableResponse := httptest.NewRecorder()
	mux.ServeHTTP(disableResponse, disableRequest)
	if disableResponse.Code != http.StatusOK {
		t.Fatalf("disable subject status = %d, body = %s", disableResponse.Code, disableResponse.Body.String())
	}

	var administratorID string
	if err := databaseConnection.QueryRow(`
		SELECT subject_id
		FROM identity_identifiers
		WHERE identifier_type = '账号' AND normalized_value = 'admin'
	`).Scan(&administratorID); err != nil {
		t.Fatalf("read administrator ID: %v", err)
	}
	lastAdministratorRequest := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/subjects/"+administratorID, strings.NewReader(`{"status":"禁用"}`))
	lastAdministratorRequest.Header.Set("Content-Type", "application/json")
	lastAdministratorRequest.Header.Set("X-CSRF-Token", adminCSRF.Value)
	lastAdministratorRequest.AddCookie(adminSession)
	lastAdministratorRequest.AddCookie(adminCSRF)
	lastAdministratorResponse := httptest.NewRecorder()
	mux.ServeHTTP(lastAdministratorResponse, lastAdministratorRequest)
	if lastAdministratorResponse.Code != http.StatusForbidden {
		t.Fatalf("last administrator disable status = %d, body = %s", lastAdministratorResponse.Code, lastAdministratorResponse.Body.String())
	}
}

func TestSubjectsPageServesEmbeddedAssetsAndHTMXFragments(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})
	adminSession, adminCSRF := loginCookies(t, mux, "admin", "correct horse battery staple")

	assetRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/assets/app.css", nil)
	assetResponse := httptest.NewRecorder()
	mux.ServeHTTP(assetResponse, assetRequest)
	if assetResponse.Code != http.StatusOK || !strings.Contains(assetResponse.Header().Get("Content-Type"), "text/css") || assetResponse.Body.Len() == 0 {
		t.Fatalf("CSS asset response = status:%d content-type:%q length:%d", assetResponse.Code, assetResponse.Header().Get("Content-Type"), assetResponse.Body.Len())
	}

	pageRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	pageRequest.Header.Set("Accept", "text/html")
	pageRequest.AddCookie(adminSession)
	pageRequest.AddCookie(adminCSRF)
	pageResponse := httptest.NewRecorder()
	mux.ServeHTTP(pageResponse, pageRequest)
	if pageResponse.Code != http.StatusOK || !strings.Contains(pageResponse.Header().Get("Content-Type"), "text/html") {
		t.Fatalf("subjects page response = status:%d content-type:%q", pageResponse.Code, pageResponse.Header().Get("Content-Type"))
	}
	if vary := strings.Join(pageResponse.Header().Values("Vary"), ","); !strings.Contains(vary, "Accept") || !strings.Contains(vary, "HX-Request") {
		t.Fatalf("subjects page Vary header = %q", vary)
	}
	if body := pageResponse.Body.String(); !strings.Contains(body, "hx-post=\"/crate-api/identity/v1/subjects\"") || !strings.Contains(body, "temporary_password") || !strings.Contains(body, "htmx.min.js") {
		t.Fatalf("subjects page is missing HTMX management markup: %s", body)
	}

	createForm := url.Values{
		"csrf_token":   {adminCSRF.Value},
		"display_name": {"李四"},
		"identifier":   {"lisi"},
		"password":     {"a sufficiently long password"},
	}
	createRequest := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/subjects", strings.NewReader(createForm.Encode()))
	createRequest.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	createRequest.Header.Set("HX-Request", "true")
	createRequest.AddCookie(adminSession)
	createRequest.AddCookie(adminCSRF)
	createResponse := httptest.NewRecorder()
	mux.ServeHTTP(createResponse, createRequest)
	if createResponse.Code != http.StatusCreated || !strings.Contains(createResponse.Header().Get("Content-Type"), "text/html") || !strings.Contains(createResponse.Body.String(), "李四") {
		t.Fatalf("HTMX subject create response = status:%d content-type:%q body:%s", createResponse.Code, createResponse.Header().Get("Content-Type"), createResponse.Body.String())
	}

	var subjectID string
	if err := databaseConnection.QueryRow(`
		SELECT subject_id
		FROM identity_identifiers
		WHERE identifier_type = '账号' AND normalized_value = 'lisi'
	`).Scan(&subjectID); err != nil {
		t.Fatalf("read HTMX-created subject: %v", err)
	}
	disableForm := url.Values{"csrf_token": {adminCSRF.Value}, "status": {"禁用"}}
	disableRequest := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/subjects/"+subjectID, strings.NewReader(disableForm.Encode()))
	disableRequest.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	disableRequest.Header.Set("HX-Request", "true")
	disableRequest.AddCookie(adminSession)
	disableRequest.AddCookie(adminCSRF)
	disableResponse := httptest.NewRecorder()
	mux.ServeHTTP(disableResponse, disableRequest)
	if disableResponse.Code != http.StatusOK || !strings.Contains(disableResponse.Body.String(), "禁用") {
		t.Fatalf("HTMX subject disable response = status:%d body:%s", disableResponse.Code, disableResponse.Body.String())
	}
}

func TestTemporaryPasswordSessionCanOnlyChangePassword(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})
	adminSession, adminCSRF := loginCookies(t, mux, "admin", "correct horse battery staple")
	var administratorID string
	if err := databaseConnection.QueryRow(`
		SELECT subject_id
		FROM identity_identifiers
		WHERE identifier_type = '账号' AND normalized_value = 'admin'
	`).Scan(&administratorID); err != nil {
		t.Fatalf("read administrator ID: %v", err)
	}
	created, err := identity.CreateSubject(context.Background(), databaseConnection, administratorID, identity.CreateSubjectInput{
		DisplayName: "张三",
		Identifier:  "zhangsan",
		Password:    "original sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create subject: %v", err)
	}

	temporaryPasswordRequest := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/subjects/"+created.ID, strings.NewReader(`{"temporary_password":"temporary sufficiently long password"}`))
	temporaryPasswordRequest.Header.Set("Content-Type", "application/json")
	temporaryPasswordRequest.Header.Set("X-CSRF-Token", adminCSRF.Value)
	temporaryPasswordRequest.AddCookie(adminSession)
	temporaryPasswordRequest.AddCookie(adminCSRF)
	temporaryPasswordResponse := httptest.NewRecorder()
	mux.ServeHTTP(temporaryPasswordResponse, temporaryPasswordRequest)
	if temporaryPasswordResponse.Code != http.StatusOK {
		t.Fatalf("set temporary password status = %d, body = %s", temporaryPasswordResponse.Code, temporaryPasswordResponse.Body.String())
	}

	temporarySession, temporaryCSRF := loginCookiesWithRedirect(t, mux, "zhangsan", "temporary sufficiently long password", "/crate-api/identity/v1/password")
	dashboardRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/dashboard", nil)
	dashboardRequest.AddCookie(temporarySession)
	dashboardResponse := httptest.NewRecorder()
	mux.ServeHTTP(dashboardResponse, dashboardRequest)
	if dashboardResponse.Code != http.StatusForbidden {
		t.Fatalf("temporary-password dashboard status = %d", dashboardResponse.Code)
	}

	subjectsRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	subjectsRequest.AddCookie(temporarySession)
	subjectsResponse := httptest.NewRecorder()
	mux.ServeHTTP(subjectsResponse, subjectsRequest)
	if subjectsResponse.Code != http.StatusForbidden {
		t.Fatalf("temporary-password subjects status = %d", subjectsResponse.Code)
	}

	passwordPageRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/password", nil)
	passwordPageRequest.AddCookie(temporarySession)
	passwordPageRequest.AddCookie(temporaryCSRF)
	passwordPageResponse := httptest.NewRecorder()
	mux.ServeHTTP(passwordPageResponse, passwordPageRequest)
	if passwordPageResponse.Code != http.StatusOK || !strings.Contains(passwordPageResponse.Body.String(), "当前密码") {
		t.Fatalf("password page response = status:%d body:%s", passwordPageResponse.Code, passwordPageResponse.Body.String())
	}

	missingCSRFRequest := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/password", strings.NewReader(`{"current_password":"temporary sufficiently long password","new_password":"replacement sufficiently long password"}`))
	missingCSRFRequest.Header.Set("Content-Type", "application/json")
	missingCSRFRequest.AddCookie(temporarySession)
	missingCSRFResponse := httptest.NewRecorder()
	mux.ServeHTTP(missingCSRFResponse, missingCSRFRequest)
	if missingCSRFResponse.Code != http.StatusForbidden {
		t.Fatalf("password change without CSRF status = %d", missingCSRFResponse.Code)
	}

	passwordChangeRequest := httptest.NewRequest(http.MethodPatch, "/crate-api/identity/v1/password", strings.NewReader(`{"current_password":"temporary sufficiently long password","new_password":"replacement sufficiently long password"}`))
	passwordChangeRequest.Header.Set("Content-Type", "application/json")
	passwordChangeRequest.Header.Set("X-CSRF-Token", temporaryCSRF.Value)
	passwordChangeRequest.AddCookie(temporarySession)
	passwordChangeRequest.AddCookie(temporaryCSRF)
	passwordChangeResponse := httptest.NewRecorder()
	mux.ServeHTTP(passwordChangeResponse, passwordChangeRequest)
	if passwordChangeResponse.Code != http.StatusNoContent {
		t.Fatalf("password change status = %d, body = %s", passwordChangeResponse.Code, passwordChangeResponse.Body.String())
	}
	if _, err := identity.CurrentSession(context.Background(), databaseConnection, temporarySession.Value, identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute}); !errors.Is(err, identity.ErrInvalidSession) {
		t.Fatalf("temporary session after password change error = %v", err)
	}

	changedSession, _ := loginCookies(t, mux, "zhangsan", "replacement sufficiently long password")
	changedDashboardRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/dashboard", nil)
	changedDashboardRequest.AddCookie(changedSession)
	changedDashboardResponse := httptest.NewRecorder()
	mux.ServeHTTP(changedDashboardResponse, changedDashboardRequest)
	if changedDashboardResponse.Code != http.StatusForbidden {
		t.Fatalf("non-administrator dashboard status = %d", changedDashboardResponse.Code)
	}
}

func loginCookies(t *testing.T, handler http.Handler, identifier string, password string) (*http.Cookie, *http.Cookie) {
	t.Helper()
	return loginCookiesWithRedirect(t, handler, identifier, password, "/crate-api/identity/v1/dashboard")
}

func loginCookiesWithRedirect(t *testing.T, handler http.Handler, identifier string, password string, expectedLocation string) (*http.Cookie, *http.Cookie) {
	t.Helper()
	form := url.Values{"identifier": {identifier}, "password": {password}}
	request := httptest.NewRequest(http.MethodPost, "/crate-api/identity/v1/sessions", strings.NewReader(form.Encode()))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusSeeOther {
		t.Fatalf("login %s status = %d", identifier, response.Code)
	}
	if location := response.Header().Get("Location"); location != expectedLocation {
		t.Fatalf("login %s redirect = %q, want %q", identifier, location, expectedLocation)
	}

	var sessionCookie, csrfCookie *http.Cookie
	for _, cookie := range response.Result().Cookies() {
		switch cookie.Name {
		case "platformd_session":
			sessionCookie = cookie
		case "platformd_csrf":
			csrfCookie = cookie
		}
	}
	if sessionCookie == nil || csrfCookie == nil {
		t.Fatalf("login %s did not return session cookies", identifier)
	}
	return sessionCookie, csrfCookie
}

func assertProblemDetails(t *testing.T, response *httptest.ResponseRecorder, wantStatus int, wantType string, wantInstance string) {
	t.Helper()
	if response.Code != wantStatus {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, wantStatus, response.Body.String())
	}
	if contentType := response.Header().Get("Content-Type"); contentType != "application/problem+json" {
		t.Fatalf("content type = %q, want application/problem+json", contentType)
	}

	var problem struct {
		Type     string `json:"type"`
		Title    string `json:"title"`
		Status   int    `json:"status"`
		Detail   string `json:"detail"`
		Instance string `json:"instance"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &problem); err != nil {
		t.Fatalf("decode problem details: %v; body = %s", err, response.Body.String())
	}
	if problem.Type != "/crate-api/identity/v1/problems/"+wantType {
		t.Errorf("type = %q, want %q", problem.Type, "/crate-api/identity/v1/problems/"+wantType)
	}
	if problem.Title != http.StatusText(wantStatus) {
		t.Errorf("title = %q, want %q", problem.Title, http.StatusText(wantStatus))
	}
	if problem.Status != wantStatus {
		t.Errorf("problem status = %d, want %d", problem.Status, wantStatus)
	}
	if problem.Detail == "" {
		t.Error("detail is empty")
	}
	if problem.Instance != wantInstance {
		t.Errorf("instance = %q, want %q", problem.Instance, wantInstance)
	}

	var fields map[string]json.RawMessage
	if err := json.Unmarshal(response.Body.Bytes(), &fields); err != nil {
		t.Fatalf("decode problem detail fields: %v", err)
	}
	if _, ok := fields["error"]; ok {
		t.Error("problem response contains legacy error field")
	}
}

// TestDirectoryIsReadableByBusinessAccountsWithMinimalFields 锁定计划 044 的核心契约：
// 业务账号（非平台管理员）**读不到**管理接口 `/subjects`，但**读得到**业务人员目录
// `/directory`，且目录只暴露 `id` + `display_name`、只含启用主体。
//
// 这条护栏的存在理由：本端点存在的唯一原因就是"业务账号需要把主体 ID 显示成姓名"
// （药房护士核对列、病程记录、配餐名单、执行人筛选）。一旦有人把它改成管理员接口，
// 或者顺手把管理字段带进响应，业务侧会立刻退回显示 ULID 或泄露账号信息。
func TestDirectoryIsReadableByBusinessAccountsWithMinimalFields(t *testing.T) {
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
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings: identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute},
		LoginThrottle:   testLoginThrottle,
	})
	var administratorID string
	if err := databaseConnection.QueryRow(`
		SELECT subject_id
		FROM identity_identifiers
		WHERE identifier_type = '账号' AND normalized_value = 'admin'
	`).Scan(&administratorID); err != nil {
		t.Fatalf("read administrator ID: %v", err)
	}
	active, err := identity.CreateSubject(context.Background(), databaseConnection, administratorID, identity.CreateSubjectInput{
		DisplayName: "护理员一号",
		Identifier:  "nurse01",
		Password:    "sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create active subject: %v", err)
	}
	disabled, err := identity.CreateSubject(context.Background(), databaseConnection, administratorID, identity.CreateSubjectInput{
		DisplayName: "离职员工",
		Identifier:  "gone01",
		Password:    "sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create disabled subject: %v", err)
	}
	if _, err := identity.DisableSubject(context.Background(), databaseConnection, administratorID, disabled.ID); err != nil {
		t.Fatalf("disable subject: %v", err)
	}

	businessSession, _ := loginCookiesWithRedirect(t, mux, "nurse01", "sufficiently long password", "/crate-api/identity/v1/dashboard")

	// 管理接口对业务账号必须是 403（目录端点存在的前提）
	subjectsRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/subjects", nil)
	subjectsRequest.AddCookie(businessSession)
	subjectsResponse := httptest.NewRecorder()
	mux.ServeHTTP(subjectsResponse, subjectsRequest)
	if subjectsResponse.Code != http.StatusForbidden {
		t.Fatalf("business account /subjects status = %d, want 403", subjectsResponse.Code)
	}

	directoryRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/directory?limit=200", nil)
	directoryRequest.AddCookie(businessSession)
	directoryResponse := httptest.NewRecorder()
	mux.ServeHTTP(directoryResponse, directoryRequest)
	if directoryResponse.Code != http.StatusOK {
		t.Fatalf("business account /directory status = %d, body = %s", directoryResponse.Code, directoryResponse.Body.String())
	}

	var payload struct {
		Records []map[string]any `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(directoryResponse.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode directory: %v", err)
	}

	names := map[string]bool{}
	for _, record := range payload.Records {
		if len(record) != 2 {
			t.Fatalf("directory record must expose exactly id + display_name, got %#v", record)
		}
		if _, ok := record["id"]; !ok {
			t.Fatalf("directory record missing id: %#v", record)
		}
		name, ok := record["display_name"].(string)
		if !ok {
			t.Fatalf("directory record missing display_name: %#v", record)
		}
		for _, forbidden := range []string{"identifier", "status", "security_version", "platform_admin", "created_at"} {
			if _, leaked := record[forbidden]; leaked {
				t.Fatalf("directory must not expose management field %q: %#v", forbidden, record)
			}
		}
		names[name] = true
	}
	if !names["护理员一号"] || !names["系统管理员"] {
		t.Fatalf("directory must contain every enabled subject, got %#v", names)
	}
	if names["离职员工"] {
		t.Fatal("directory must exclude disabled subjects（已禁用账号不得作为可选操作人）")
	}
	if payload.Meta.Total != int64(len(payload.Records)) {
		t.Fatalf("directory meta.total = %d, records = %d", payload.Meta.Total, len(payload.Records))
	}

	searchRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/directory?search=%E6%8A%A4%E7%90%86", nil)
	searchRequest.AddCookie(businessSession)
	searchResponse := httptest.NewRecorder()
	mux.ServeHTTP(searchResponse, searchRequest)
	var searched struct {
		Records []map[string]any `json:"records"`
	}
	if err := json.Unmarshal(searchResponse.Body.Bytes(), &searched); err != nil {
		t.Fatalf("decode searched directory: %v", err)
	}
	if len(searched.Records) != 1 || searched.Records[0]["id"] != active.ID {
		t.Fatalf("directory search must filter by display_name, got %#v", searched.Records)
	}

	anonymousRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/directory", nil)
	anonymousResponse := httptest.NewRecorder()
	mux.ServeHTTP(anonymousResponse, anonymousRequest)
	if anonymousResponse.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous /directory status = %d, want 401", anonymousResponse.Code)
	}
}
