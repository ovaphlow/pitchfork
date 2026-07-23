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

	"github.com/ovaphlow/pitchfork/service-idp-go/db/migrations"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/httpapi"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
)

var testLoginThrottle = identity.LoginThrottleSettings{
	Secret:          []byte("test-login-throttle-secret-with-at-least-32-bytes"),
	FailureLimit:    3,
	Window:          time.Hour,
	LockoutDuration: time.Hour,
}

func TestHealthzReturnsOKWhenDatabaseIsAvailable(t *testing.T) {
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
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

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}
	if contentType := response.Header().Get("Content-Type"); contentType != "application/json; charset=utf-8" {
		t.Fatalf("content type = %q", contentType)
	}
	if body := response.Body.String(); body != "{\"status\":\"ok\"}\n" {
		t.Fatalf("body = %q", body)
	}
}

func TestLoginDashboardAndCSRFFlow(t *testing.T) {
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
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
		case "identityd_session":
			sessionCookie = cookie
		case "identityd_csrf":
			csrfCookie = cookie
		}
	}
	if sessionCookie == nil || csrfCookie == nil || !sessionCookie.HttpOnly || csrfCookie.HttpOnly {
		t.Fatalf("invalid login cookies: %#v", cookies)
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

	staleDashboardRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/dashboard", nil)
	staleDashboardRequest.AddCookie(sessionCookie)
	staleDashboardResponse := httptest.NewRecorder()
	mux.ServeHTTP(staleDashboardResponse, staleDashboardRequest)
	if staleDashboardResponse.Code != http.StatusSeeOther {
		t.Fatalf("stale dashboard status = %d, want %d", staleDashboardResponse.Code, http.StatusSeeOther)
	}
}

func TestAdministratorSubjectManagementAPI(t *testing.T) {
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
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
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
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
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
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
		case "identityd_session":
			sessionCookie = cookie
		case "identityd_csrf":
			csrfCookie = cookie
		}
	}
	if sessionCookie == nil || csrfCookie == nil {
		t.Fatalf("login %s did not return session cookies", identifier)
	}
	return sessionCookie, csrfCookie
}
