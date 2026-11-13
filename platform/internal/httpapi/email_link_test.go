package httpapi_test

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/httpapi"
	"github.com/ovaphlow/pitchfork/platform/internal/identity"
)

const emailLoginLinkPath = "/crate-api/identity/v1/email-login-links"

var testEmailLinkSettings = identity.EmailLoginLinkSettings{
	TTL:          10 * time.Minute,
	RedirectURIs: []string{"http://localhost:4321/"},
	RateWindow:   15 * time.Minute,
	EmailLimit:   3,
	SourceLimit:  10,
}

var testEmailLinkSessionSettings = identity.SessionSettings{TTL: time.Hour, IdleTTL: 30 * time.Minute}

type sentEmail struct {
	To   string
	Link string
	TTL  time.Duration
}

// fakeMailer 记录投递内容；err 非空时模拟中继故障。
type fakeMailer struct {
	mutex sync.Mutex
	sent  []sentEmail
	err   error
}

func (mailer *fakeMailer) SendEmailLoginLink(_ context.Context, to string, link string, ttl time.Duration) error {
	mailer.mutex.Lock()
	defer mailer.mutex.Unlock()
	if mailer.err != nil {
		return mailer.err
	}
	mailer.sent = append(mailer.sent, sentEmail{To: to, Link: link, TTL: ttl})
	return nil
}

func (mailer *fakeMailer) messages() []sentEmail {
	mailer.mutex.Lock()
	defer mailer.mutex.Unlock()
	return append([]sentEmail(nil), mailer.sent...)
}

func newEmailLinkMux(t *testing.T, settings identity.EmailLoginLinkSettings, mailer identity.EmailLoginLinkMailer) (*sql.DB, http.Handler) {
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
	mux := httpapi.NewMux(databaseConnection, httpapi.Options{
		SessionSettings:        testEmailLinkSessionSettings,
		LoginThrottle:          testLoginThrottle,
		EmailLoginLinkSettings: settings,
		EmailLoginLinkMailer:   mailer,
	})
	return databaseConnection, mux
}

func createEmailSubject(t *testing.T, databaseConnection *sql.DB, email string) identity.Subject {
	t.Helper()
	subject, err := identity.CreateSubject(context.Background(), databaseConnection, "", identity.CreateSubjectInput{
		DisplayName: "测试用户",
		Identifier:  email,
		Password:    "a sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create subject %s: %v", email, err)
	}
	return subject
}

func postEmailLoginLink(t *testing.T, mux http.Handler, body string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, emailLoginLinkPath, strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	mux.ServeHTTP(response, request)
	return response
}

func exchangeEmailLoginLink(t *testing.T, mux http.Handler, token string) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, emailLoginLinkPath+"/"+url.PathEscape(token)+"/exchange", nil)
	response := httptest.NewRecorder()
	mux.ServeHTTP(response, request)
	return response
}

func tokenFromLink(t *testing.T, link string) string {
	t.Helper()
	parsed, err := url.Parse(link)
	if err != nil {
		t.Fatalf("parse mail link %q: %v", link, err)
	}
	token := parsed.Query().Get("email_token")
	if token == "" {
		t.Fatalf("mail link %q has no email_token", link)
	}
	return token
}

func assertAccepted(t *testing.T, response *httptest.ResponseRecorder) {
	t.Helper()
	if response.Code != http.StatusAccepted {
		t.Fatalf("status = %d, want %d; body = %s", response.Code, http.StatusAccepted, response.Body.String())
	}
	if body := strings.TrimSpace(response.Body.String()); body != `{"status":"accepted"}` {
		t.Fatalf("body = %s", body)
	}
	if cacheControl := response.Header().Get("Cache-Control"); cacheControl != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", cacheControl)
	}
}

func TestRequestEmailLoginLinkMailsExistingSubject(t *testing.T) {
	mailer := &fakeMailer{}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)
	subject := createEmailSubject(t, databaseConnection, "User@Example.com")

	response := postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`)
	assertAccepted(t, response)

	messages := mailer.messages()
	if len(messages) != 1 {
		t.Fatalf("mails sent = %d, want 1", len(messages))
	}
	if messages[0].To != "user@example.com" {
		t.Errorf("recipient = %q", messages[0].To)
	}
	if messages[0].TTL != testEmailLinkSettings.TTL {
		t.Errorf("ttl = %s", messages[0].TTL)
	}
	if !strings.HasPrefix(messages[0].Link, "http://localhost:4321/?email_token=") {
		t.Errorf("link = %q", messages[0].Link)
	}

	var linkSubjectID string
	if err := databaseConnection.QueryRow("SELECT subject_id FROM identity_email_login_links").Scan(&linkSubjectID); err != nil {
		t.Fatalf("read email login link: %v", err)
	}
	if linkSubjectID != subject.ID {
		t.Errorf("link subject = %q, want %q", linkSubjectID, subject.ID)
	}
}

func TestRequestEmailLoginLinkHidesUnknownEmail(t *testing.T) {
	mailer := &fakeMailer{}
	_, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)

	response := postEmailLoginLink(t, mux, `{"email":"nobody@example.com","redirect_uri":"http://localhost:4321/"}`)

	assertAccepted(t, response)
	if sent := mailer.messages(); len(sent) != 0 {
		t.Fatalf("mails sent = %d, want 0", len(sent))
	}
}

func TestRequestEmailLoginLinkCreatesPasswordlessSubject(t *testing.T) {
	mailer := &fakeMailer{}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)

	response := postEmailLoginLink(t, mux, `{"email":"new.user@example.com","display_name":"新用户","redirect_uri":"http://localhost:4321/"}`)
	assertAccepted(t, response)

	if sent := mailer.messages(); len(sent) != 1 {
		t.Fatalf("mails sent = %d, want 1", len(sent))
	}

	var identifierType, identifierValue string
	var subjectStatus string
	if err := databaseConnection.QueryRow(`
		SELECT i.identifier_type, i.identifier_value, s.status
		FROM identity_identifiers i
		JOIN identity_subjects s ON s.id = i.subject_id
		WHERE i.normalized_value = 'new.user@example.com'
	`).Scan(&identifierType, &identifierValue, &subjectStatus); err != nil {
		t.Fatalf("read created subject: %v", err)
	}
	if identifierType != "邮箱" || identifierValue != "new.user@example.com" || subjectStatus != "启用" {
		t.Fatalf("identifier = %q/%q status = %q", identifierType, identifierValue, subjectStatus)
	}

	var credentialCount int
	if err := databaseConnection.QueryRow("SELECT COUNT(*) FROM identity_password_credentials").Scan(&credentialCount); err != nil {
		t.Fatalf("count password credentials: %v", err)
	}
	if credentialCount != 0 {
		t.Fatalf("password credentials = %d, want 0 for a passwordless subject", credentialCount)
	}
}

func TestRequestEmailLoginLinkRejectsInvalidRequests(t *testing.T) {
	tests := []struct {
		name       string
		body       string
		mailer     identity.EmailLoginLinkMailer
		settings   identity.EmailLoginLinkSettings
		wantStatus int
		wantType   string
	}{
		{
			name:       "malformed email",
			body:       `{"email":"not-an-email","redirect_uri":"http://localhost:4321/"}`,
			mailer:     &fakeMailer{},
			settings:   testEmailLinkSettings,
			wantStatus: http.StatusBadRequest,
			wantType:   "invalid-request",
		},
		{
			name:       "redirect URI not allowed",
			body:       `{"email":"user@example.com","redirect_uri":"http://evil.example/"}`,
			mailer:     &fakeMailer{},
			settings:   testEmailLinkSettings,
			wantStatus: http.StatusInternalServerError,
			wantType:   "email-login-misconfigured",
		},
		{
			name:       "unknown field",
			body:       `{"email":"user@example.com","redirect_uri":"http://localhost:4321/","admin":true}`,
			mailer:     &fakeMailer{},
			settings:   testEmailLinkSettings,
			wantStatus: http.StatusBadRequest,
			wantType:   "invalid-request",
		},
		{
			name:       "mailer not configured",
			body:       `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`,
			mailer:     nil,
			settings:   testEmailLinkSettings,
			wantStatus: http.StatusServiceUnavailable,
			wantType:   "email-login-unavailable",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, mux := newEmailLinkMux(t, test.settings, test.mailer)
			response := postEmailLoginLink(t, mux, test.body)
			assertProblemDetails(t, response, test.wantStatus, test.wantType, emailLoginLinkPath)
		})
	}
}

func TestExchangeEmailLoginLinkCreatesSessionOnce(t *testing.T) {
	mailer := &fakeMailer{}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)
	subject := createEmailSubject(t, databaseConnection, "user@example.com")

	assertAccepted(t, postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`))
	messages := mailer.messages()
	if len(messages) != 1 {
		t.Fatalf("mails sent = %d, want 1", len(messages))
	}
	token := tokenFromLink(t, messages[0].Link)

	response := exchangeEmailLoginLink(t, mux, token)
	if response.Code != http.StatusOK {
		t.Fatalf("exchange status = %d, want %d; body = %s", response.Code, http.StatusOK, response.Body.String())
	}
	if body := strings.TrimSpace(response.Body.String()); body != `{"access":"完整"}` {
		t.Fatalf("exchange body = %s", body)
	}
	if cacheControl := response.Header().Get("Cache-Control"); cacheControl != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", cacheControl)
	}

	var sessionCookie *http.Cookie
	cookieCount := 0
	for _, cookie := range response.Result().Cookies() {
		cookieCount++
		if cookie.Name == "platformd_session" {
			sessionCookie = cookie
		}
	}
	if cookieCount != 2 || sessionCookie == nil {
		t.Fatalf("cookies = %#v, want platformd_session and platformd_csrf", response.Result().Cookies())
	}

	sessionRequest := httptest.NewRequest(http.MethodGet, "/crate-api/identity/v1/session", nil)
	sessionRequest.AddCookie(sessionCookie)
	sessionResponse := httptest.NewRecorder()
	mux.ServeHTTP(sessionResponse, sessionRequest)
	if sessionResponse.Code != http.StatusOK {
		t.Fatalf("session status = %d; body = %s", sessionResponse.Code, sessionResponse.Body.String())
	}
	var session struct {
		SubjectID string `json:"subject_id"`
		Access    string `json:"access"`
	}
	if err := json.Unmarshal(sessionResponse.Body.Bytes(), &session); err != nil {
		t.Fatalf("decode session: %v", err)
	}
	if session.SubjectID != subject.ID || session.Access != "完整" {
		t.Fatalf("session = %+v", session)
	}

	var verifiedAt sql.NullTime
	if err := databaseConnection.QueryRow("SELECT verified_at FROM identity_identifiers WHERE id = (SELECT identifier_id FROM identity_email_login_links)").Scan(&verifiedAt); err != nil {
		t.Fatalf("read verified_at: %v", err)
	}
	if !verifiedAt.Valid {
		t.Error("email identifier was not marked verified")
	}

	var auditMetadata string
	if err := databaseConnection.QueryRow("SELECT metadata FROM identity_audit_events WHERE event_action = '登录' AND target_subject_id = ?", subject.ID).Scan(&auditMetadata); err != nil {
		t.Fatalf("read login audit event: %v", err)
	}
	if auditMetadata != `{"method":"email_link"}` {
		t.Errorf("audit metadata = %q", auditMetadata)
	}

	replay := exchangeEmailLoginLink(t, mux, token)
	assertProblemDetails(t, replay, http.StatusUnauthorized, "invalid-email-login-link", emailLoginLinkPath+"/"+token+"/exchange")
}

func TestExchangeEmailLoginLinkRejectsExpiredAndUnknownTokens(t *testing.T) {
	mailer := &fakeMailer{}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)
	createEmailSubject(t, databaseConnection, "user@example.com")
	assertAccepted(t, postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`))
	token := tokenFromLink(t, mailer.messages()[0].Link)

	if _, err := databaseConnection.Exec("UPDATE identity_email_login_links SET expires_at = ?", time.Now().UTC().Add(-time.Minute)); err != nil {
		t.Fatalf("expire link: %v", err)
	}

	expired := exchangeEmailLoginLink(t, mux, token)
	assertProblemDetails(t, expired, http.StatusUnauthorized, "invalid-email-login-link", emailLoginLinkPath+"/"+token+"/exchange")

	unknown := exchangeEmailLoginLink(t, mux, "not-a-real-token")
	assertProblemDetails(t, unknown, http.StatusUnauthorized, "invalid-email-login-link", emailLoginLinkPath+"/not-a-real-token/exchange")
}

func TestRequestEmailLoginLinkSkipsDisabledSubject(t *testing.T) {
	mailer := &fakeMailer{}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)
	subject := createEmailSubject(t, databaseConnection, "user@example.com")
	if _, err := identity.DisableSubject(context.Background(), databaseConnection, "", subject.ID); err != nil {
		t.Fatalf("disable subject: %v", err)
	}

	response := postEmailLoginLink(t, mux, `{"email":"user@example.com","display_name":"测试用户","redirect_uri":"http://localhost:4321/"}`)
	assertAccepted(t, response)
	if sent := mailer.messages(); len(sent) != 0 {
		t.Fatalf("mails sent = %d, want 0 for a disabled subject", len(sent))
	}
}

func TestRequestEmailLoginLinkRateLimitsPerEmail(t *testing.T) {
	mailer := &fakeMailer{}
	settings := testEmailLinkSettings
	settings.EmailLimit = 1
	databaseConnection, mux := newEmailLinkMux(t, settings, mailer)
	createEmailSubject(t, databaseConnection, "user@example.com")

	assertAccepted(t, postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`))
	assertAccepted(t, postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`))

	if sent := mailer.messages(); len(sent) != 1 {
		t.Fatalf("mails sent = %d, want 1", len(sent))
	}
}

func TestRequestEmailLoginLinkRemovesLinkWhenDeliveryFails(t *testing.T) {
	mailer := &fakeMailer{err: errors.New("relay refused")}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)
	createEmailSubject(t, databaseConnection, "user@example.com")

	response := postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`)
	assertAccepted(t, response)

	var linkCount int
	if err := databaseConnection.QueryRow("SELECT COUNT(*) FROM identity_email_login_links").Scan(&linkCount); err != nil {
		t.Fatalf("count email login links: %v", err)
	}
	if linkCount != 0 {
		t.Fatalf("email login links = %d, want 0 after a delivery failure", linkCount)
	}
}

func TestRequestEmailLoginLinkInvalidatesPreviousLink(t *testing.T) {
	mailer := &fakeMailer{}
	databaseConnection, mux := newEmailLinkMux(t, testEmailLinkSettings, mailer)
	createEmailSubject(t, databaseConnection, "user@example.com")

	assertAccepted(t, postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`))
	first := tokenFromLink(t, mailer.messages()[0].Link)
	assertAccepted(t, postEmailLoginLink(t, mux, `{"email":"user@example.com","redirect_uri":"http://localhost:4321/"}`))
	second := tokenFromLink(t, mailer.messages()[1].Link)

	assertProblemDetails(t, exchangeEmailLoginLink(t, mux, first), http.StatusUnauthorized, "invalid-email-login-link", emailLoginLinkPath+"/"+first+"/exchange")
	if response := exchangeEmailLoginLink(t, mux, second); response.Code != http.StatusOK {
		t.Fatalf("latest link exchange status = %d; body = %s", response.Code, response.Body.String())
	}
}
