package httpapi_test

import (
	"context"
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
