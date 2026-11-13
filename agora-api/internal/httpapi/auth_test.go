package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/nexusclient"
)

func newAuthTestHandler(identityURL, nexusURL string) http.Handler {
	return New(Deps{
		Logger:               testLogger(),
		AllowedOrigins:       []string{"http://localhost:4321"},
		ReadinessTimeout:     time.Second,
		Identity:             identityclient.New(identityURL, time.Second),
		Nexus:                nexusclient.New(nexusURL, time.Second),
		EmailLinkRedirectURI: "http://localhost:4321/",
	})
}

func doRequest(handler http.Handler, method, path, body string, headers map[string]string) *httptest.ResponseRecorder {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}

func newFakeIdentity(t *testing.T, calls *atomic.Int64) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/crate-api/identity/v1/sessions":
			if err := r.ParseForm(); err != nil {
				http.Error(w, "bad form", http.StatusBadRequest)
				return
			}
			if r.Form.Get("identifier") != "user@example.com" || r.Form.Get("password") != "correct horse battery staple" {
				w.Header().Set("Content-Type", "application/problem+json")
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"detail":"invalid credentials"}`))
				return
			}
			if source := r.Header.Get("X-Forwarded-For"); source != "203.0.113.9" {
				t.Errorf("X-Forwarded-For = %q, want 203.0.113.9", source)
			}
			http.SetCookie(w, &http.Cookie{Name: identityclient.SessionCookieName, Value: "session-token", Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode})
			http.SetCookie(w, &http.Cookie{Name: identityclient.CSRFCookieName, Value: "csrf-token", Path: "/", SameSite: http.SameSiteLaxMode})
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access":"完整"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/crate-api/identity/v1/session":
			if !strings.Contains(r.Header.Get("Cookie"), identityclient.SessionCookieName+"=session-token") {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"detail":"not authenticated"}`))
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"subject_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","display_name":"张三","access":"完整","platform_admin":false}`))
		case r.Method == http.MethodDelete && r.URL.Path == "/crate-api/identity/v1/sessions/current":
			if r.Header.Get("X-CSRF-Token") != "csrf-token" {
				w.Header().Set("Content-Type", "application/problem+json")
				w.WriteHeader(http.StatusForbidden)
				_, _ = w.Write([]byte(`{"detail":"invalid CSRF token"}`))
				return
			}
			http.SetCookie(w, &http.Cookie{Name: identityclient.SessionCookieName, Value: "", Path: "/", MaxAge: -1})
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
}

func newFakeNexus(wantCookie string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/crate-api/v1/subject-permissions" {
			http.NotFound(w, r)
			return
		}
		if !strings.Contains(r.Header.Get("Cookie"), wantCookie) {
			http.Error(w, "unauthenticated", http.StatusUnauthorized)
			return
		}
		if got := r.URL.Query().Get("subject_id"); got != "01ARZ3NDEKTSV4RRFFQ69G5FAV" {
			http.Error(w, "wrong subject", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"subject_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","role_codes":["agora.user"],"permission_codes":["agora:read"],"source":"roles"}`))
	}))
}

func TestRequestSourceAddressResolvesTrustedProxies(t *testing.T) {
	trusted := []netip.Prefix{
		netip.MustParsePrefix("127.0.0.1/32"),
		netip.MustParsePrefix("::1/128"),
		netip.MustParsePrefix("10.0.0.0/8"),
	}

	tests := []struct {
		name      string
		remote    string
		forwarded string
		want      string
	}{
		{name: "direct client ignores spoofed header", remote: "203.0.113.9:1234", forwarded: "198.51.100.7", want: "203.0.113.9"},
		{name: "trusted proxy uses client", remote: "127.0.0.1:1234", forwarded: "198.51.100.7", want: "198.51.100.7"},
		{name: "trusted chain skips trusted hops", remote: "127.0.0.1:1234", forwarded: "198.51.100.7, 10.2.3.4", want: "198.51.100.7"},
		{name: "invalid hop is skipped", remote: "127.0.0.1:1234", forwarded: "bogus, 198.51.100.7", want: "198.51.100.7"},
		{name: "all hops trusted falls back to peer", remote: "127.0.0.1:1234", forwarded: "10.2.3.4", want: "127.0.0.1"},
		{name: "no forwarded header", remote: "127.0.0.1:1234", forwarded: "", want: "127.0.0.1"},
		{name: "IPv6 proxy", remote: "[::1]:1234", forwarded: "2001:db8::1", want: "2001:db8::1"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/crate-api/agora/v1/auth/login", nil)
			request.RemoteAddr = test.remote
			if test.forwarded != "" {
				request.Header.Set("X-Forwarded-For", test.forwarded)
			}
			if got := requestSourceAddress(request, trusted); got != test.want {
				t.Fatalf("source address = %q, want %q", got, test.want)
			}
		})
	}
}

func TestLoginReturnsSessionAndNexusPermissions(t *testing.T) {
	var identityCalls atomic.Int64
	identityServer := newFakeIdentity(t, &identityCalls)
	defer identityServer.Close()
	nexusServer := newFakeNexus(identityclient.SessionCookieName + "=session-token")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	request := httptest.NewRequest(http.MethodPost, "/crate-api/agora/v1/auth/login", strings.NewReader(`{"identifier":"user@example.com","password":"correct horse battery staple"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Origin", "http://localhost:4321")
	request.RemoteAddr = "203.0.113.9:54321"
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusOK, response.Body.String())
	}
	if got := response.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:4321" {
		t.Fatalf("Access-Control-Allow-Origin = %q", got)
	}
	if got := response.Header().Get("Access-Control-Allow-Credentials"); got != "true" {
		t.Fatalf("Access-Control-Allow-Credentials = %q, want true", got)
	}

	var session authSessionResponse
	if err := json.Unmarshal(response.Body.Bytes(), &session); err != nil {
		t.Fatalf("decode session: %v", err)
	}
	if session.SubjectID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || session.DisplayName != "张三" {
		t.Fatalf("session = %+v", session)
	}
	if !session.NexusReady || len(session.Roles) != 1 || session.Roles[0] != "agora.user" || len(session.Permissions) != 1 {
		t.Fatalf("nexus data = %+v", session)
	}

	var sessionCookie, csrfCookie *http.Cookie
	for _, cookie := range response.Result().Cookies() {
		switch cookie.Name {
		case identityclient.SessionCookieName:
			sessionCookie = cookie
		case identityclient.CSRFCookieName:
			csrfCookie = cookie
		}
	}
	if sessionCookie == nil || csrfCookie == nil {
		t.Fatalf("cookies = %#v, want session and csrf cookies", response.Result().Cookies())
	}
	if !sessionCookie.HttpOnly || sessionCookie.Value != "session-token" {
		t.Fatalf("session cookie = %#v", sessionCookie)
	}

	sessionRequest := httptest.NewRequest(http.MethodGet, "/crate-api/agora/v1/auth/session", nil)
	sessionRequest.Header.Set("Cookie", identityclient.SessionCookieName+"=session-token; "+identityclient.CSRFCookieName+"=csrf-token")
	sessionResponse := httptest.NewRecorder()
	handler.ServeHTTP(sessionResponse, sessionRequest)
	if sessionResponse.Code != http.StatusOK {
		t.Fatalf("session status = %d, want %d; body = %s", sessionResponse.Code, http.StatusOK, sessionResponse.Body.String())
	}
}

func TestLoginRejectsInvalidCredentials(t *testing.T) {
	var identityCalls atomic.Int64
	identityServer := newFakeIdentity(t, &identityCalls)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/auth/login",
		`{"identifier":"user@example.com","password":"wrong password"}`,
		map[string]string{"Content-Type": "application/json"})

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusUnauthorized, response.Body.String())
	}
	if len(response.Result().Cookies()) != 0 {
		t.Fatalf("cookies = %#v, want none on failed login", response.Result().Cookies())
	}
	if !strings.Contains(response.Body.String(), "Incorrect account or password") {
		t.Fatalf("body = %s", response.Body.String())
	}
}

func TestRequestEmailLinkForwardsRequestToIdentity(t *testing.T) {
	fake, identityServer := newFakeEmailIdentity(t)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	request := httptest.NewRequest(http.MethodPost, "/crate-api/agora/v1/auth/email-link", strings.NewReader(`{"email":"user@example.com","display_name":"张三"}`))
	request.Header.Set("Content-Type", "application/json")
	request.RemoteAddr = "203.0.113.9:54321"
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusAccepted, response.Body.String())
	}
	if body := strings.TrimSpace(response.Body.String()); body != `{"status":"accepted"}` {
		t.Fatalf("body = %s", body)
	}
	if len(response.Result().Cookies()) != 0 {
		t.Fatalf("cookies = %#v, want none when only a link was requested", response.Result().Cookies())
	}

	links := fake.linksSnapshot()
	if len(links) != 1 {
		t.Fatalf("identity link requests = %d, want 1", len(links))
	}
	if links[0].Email != "user@example.com" || links[0].DisplayName != "张三" {
		t.Fatalf("link request = %+v", links[0])
	}
	if links[0].RedirectURI != "http://localhost:4321/" {
		t.Errorf("redirect URI = %q", links[0].RedirectURI)
	}
	if links[0].Source != "203.0.113.9" {
		t.Errorf("X-Forwarded-For = %q, want 203.0.113.9", links[0].Source)
	}
}

func TestRequestEmailLinkValidatesBeforeCallingIdentity(t *testing.T) {
	fake, identityServer := newFakeEmailIdentity(t)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/auth/email-link",
		`{"email":"   "}`,
		map[string]string{"Content-Type": "application/json"})

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusBadRequest, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), "Invalid sign-in request") {
		t.Fatalf("body = %s", response.Body.String())
	}
	if calls := fake.calls(); calls != 0 {
		t.Fatalf("identity calls = %d, want 0", calls)
	}
}

func TestRequestEmailLinkMapsUpstreamBadRequest(t *testing.T) {
	fake, identityServer := newFakeEmailIdentity(t)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/auth/email-link",
		`{"email":"invalid@example.com"}`,
		map[string]string{"Content-Type": "application/json"})

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusBadRequest, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), "Enter a valid email address") {
		t.Fatalf("body = %s", response.Body.String())
	}
	if calls := fake.calls(); calls != 1 {
		t.Fatalf("identity calls = %d, want 1", calls)
	}
}

func TestExchangeEmailLinkForwardsSessionCookies(t *testing.T) {
	fake, identityServer := newFakeEmailIdentity(t)
	defer identityServer.Close()
	nexusServer := newFakeNexus(identityclient.SessionCookieName + "=session-token")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/auth/email-link/exchange",
		`{"token":"token-abc"}`,
		map[string]string{"Content-Type": "application/json"})

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusOK, response.Body.String())
	}
	if cacheControl := response.Header().Get("Cache-Control"); cacheControl != "no-store" {
		t.Errorf("Cache-Control = %q, want no-store", cacheControl)
	}

	var session authSessionResponse
	if err := json.Unmarshal(response.Body.Bytes(), &session); err != nil {
		t.Fatalf("decode session: %v", err)
	}
	if session.SubjectID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || session.Access != "完整" || !session.NexusReady {
		t.Fatalf("session = %+v", session)
	}

	forwarded := map[string]bool{}
	for _, cookie := range response.Result().Cookies() {
		forwarded[cookie.Name] = true
	}
	if !forwarded[identityclient.SessionCookieName] || !forwarded[identityclient.CSRFCookieName] {
		t.Fatalf("cookies = %#v, want platformd session and csrf cookies", response.Result().Cookies())
	}
	if exchanges := fake.exchangePaths(); len(exchanges) != 1 || exchanges[0] != "/crate-api/identity/v1/email-login-links/token-abc/exchange" {
		t.Fatalf("exchange paths = %v", exchanges)
	}
}

func TestExchangeEmailLinkRejectsUnknownToken(t *testing.T) {
	_, identityServer := newFakeEmailIdentity(t)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/auth/email-link/exchange",
		`{"token":"expired-token"}`,
		map[string]string{"Content-Type": "application/json"})

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusUnauthorized, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), "invalid or has expired") {
		t.Fatalf("body = %s", response.Body.String())
	}
	if len(response.Result().Cookies()) != 0 {
		t.Fatalf("cookies = %#v, want none", response.Result().Cookies())
	}
}

func TestSessionRequiresCookie(t *testing.T) {
	var identityCalls atomic.Int64
	identityServer := newFakeIdentity(t, &identityCalls)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/auth/session", "", nil)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusUnauthorized)
	}
	if identityCalls.Load() != 0 {
		t.Fatalf("identity calls = %d, want 0", identityCalls.Load())
	}
}

func TestLogoutForwardsCSRFAndClearsCookies(t *testing.T) {
	var identityCalls atomic.Int64
	identityServer := newFakeIdentity(t, &identityCalls)
	defer identityServer.Close()
	nexusServer := newFakeNexus("")
	defer nexusServer.Close()

	handler := newAuthTestHandler(identityServer.URL, nexusServer.URL)
	cookie := identityclient.SessionCookieName + "=session-token; " + identityclient.CSRFCookieName + "=csrf-token"
	response := doRequest(handler, http.MethodDelete, "/crate-api/agora/v1/auth/session", "", map[string]string{"Cookie": cookie})

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusNoContent, response.Body.String())
	}

	cleared := map[string]bool{}
	for _, clearedCookie := range response.Result().Cookies() {
		if clearedCookie.MaxAge < 0 {
			cleared[clearedCookie.Name] = true
		}
	}
	if !cleared[identityclient.SessionCookieName] || !cleared[identityclient.CSRFCookieName] {
		t.Fatalf("cookies = %#v, want both cookies cleared", response.Result().Cookies())
	}
}

func TestAuthPreflightAllowsConfiguredOrigin(t *testing.T) {
	handler := New(Deps{
		Logger:         testLogger(),
		AllowedOrigins: []string{"http://localhost:4321"},
	})
	response := doRequest(handler, http.MethodOptions, "/crate-api/agora/v1/auth/login", "", map[string]string{
		"Origin":                         "http://localhost:4321",
		"Access-Control-Request-Method":  "POST",
		"Access-Control-Request-Headers": "content-type",
	})

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusNoContent)
	}
	if got := response.Header().Get("Access-Control-Allow-Credentials"); got != "true" {
		t.Fatalf("Access-Control-Allow-Credentials = %q", got)
	}
}
