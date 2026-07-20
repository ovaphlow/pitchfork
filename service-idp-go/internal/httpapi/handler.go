package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"html/template"
	"net/http"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
)

const identityPrefix = "/crate-api/identity/v1"

const (
	sessionCookieName = "identityd_session"
	csrfCookieName    = "identityd_csrf"
)

var loginTemplate = template.Must(template.New("login").Parse(`<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>identityd 登录</title></head>
<body><main><h1>identityd</h1>{{if .}}<p>账号标识或密码错误。</p>{{end}}
<form method="post" action="/crate-api/identity/v1/sessions">
<label>账号标识 <input name="identifier" autocomplete="username" required></label>
<label>密码 <input type="password" name="password" autocomplete="current-password" required></label>
<button type="submit">登录</button></form></main></body></html>`))

var dashboardTemplate = template.Must(template.New("dashboard").Parse(`<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>identityd 控制台</title></head>
<body><main><h1>identityd 控制台</h1><p>已认证主体：{{.}}</p></main></body></html>`))

type Options struct {
	SessionSettings     identity.SessionSettings
	SecureSessionCookie bool
}

type Handler struct {
	database            *sql.DB
	sessionSettings     identity.SessionSettings
	secureSessionCookie bool
}

func NewMux(database *sql.DB, options Options) *http.ServeMux {
	handler := Handler{
		database:            database,
		sessionSettings:     options.SessionSettings,
		secureSessionCookie: options.SecureSessionCookie,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET "+identityPrefix+"/healthz", handler.health)
	mux.HandleFunc("GET "+identityPrefix+"/login", handler.loginPage)
	mux.HandleFunc("POST "+identityPrefix+"/sessions", handler.createSession)
	mux.HandleFunc("DELETE "+identityPrefix+"/sessions/current", handler.deleteCurrentSession)
	mux.HandleFunc("GET "+identityPrefix+"/dashboard", handler.dashboard)
	return mux
}

func (handler Handler) health(responseWriter http.ResponseWriter, request *http.Request) {
	if err := handler.database.PingContext(request.Context()); err != nil {
		writeJSON(responseWriter, http.StatusServiceUnavailable, map[string]string{"error": "database unavailable"})
		return
	}
	writeJSON(responseWriter, http.StatusOK, map[string]string{"status": "ok"})
}

func (handler Handler) loginPage(responseWriter http.ResponseWriter, request *http.Request) {
	if _, err := handler.currentSession(request); err == nil {
		http.Redirect(responseWriter, request, identityPrefix+"/dashboard", http.StatusSeeOther)
		return
	}
	responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
	loginTemplate.Execute(responseWriter, request.URL.Query().Get("error") == "1")
}

func (handler Handler) createSession(responseWriter http.ResponseWriter, request *http.Request) {
	if err := request.ParseForm(); err != nil {
		http.Redirect(responseWriter, request, identityPrefix+"/login?error=1", http.StatusSeeOther)
		return
	}
	login, err := identity.Login(request.Context(), handler.database, request.Form.Get("identifier"), request.Form.Get("password"), handler.sessionSettings)
	if err != nil {
		http.Redirect(responseWriter, request, identityPrefix+"/login?error=1", http.StatusSeeOther)
		return
	}
	handler.setSessionCookies(responseWriter, login)
	http.Redirect(responseWriter, request, identityPrefix+"/dashboard", http.StatusSeeOther)
}

func (handler Handler) dashboard(responseWriter http.ResponseWriter, request *http.Request) {
	session, err := handler.currentSession(request)
	if err != nil {
		handler.clearSessionCookies(responseWriter)
		http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
		return
	}
	if session.Access != "完整" {
		http.Error(responseWriter, "password change required", http.StatusForbidden)
		return
	}
	administrator, err := identity.HasRole(request.Context(), handler.database, session.SubjectID, "identity.admin")
	if err != nil {
		writeJSON(responseWriter, http.StatusInternalServerError, map[string]string{"error": "could not authorize subject"})
		return
	}
	if !administrator {
		writeJSON(responseWriter, http.StatusForbidden, map[string]string{"error": "not authorized"})
		return
	}

	responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
	dashboardTemplate.Execute(responseWriter, session.SubjectID)
}

func (handler Handler) deleteCurrentSession(responseWriter http.ResponseWriter, request *http.Request) {
	session, err := handler.currentSession(request)
	if err != nil {
		writeJSON(responseWriter, http.StatusUnauthorized, map[string]string{"error": "not authenticated"})
		return
	}
	csrfCookie, err := request.Cookie(csrfCookieName)
	if err != nil || csrfCookie.Value == "" || csrfCookie.Value != request.Header.Get("X-CSRF-Token") || !identity.VerifyCSRF(session, csrfCookie.Value) {
		writeJSON(responseWriter, http.StatusForbidden, map[string]string{"error": "invalid CSRF token"})
		return
	}
	sessionCookie, _ := request.Cookie(sessionCookieName)
	if err := identity.Logout(request.Context(), handler.database, sessionCookie.Value); err != nil && !errors.Is(err, identity.ErrInvalidSession) {
		writeJSON(responseWriter, http.StatusInternalServerError, map[string]string{"error": "could not end session"})
		return
	}
	handler.clearSessionCookies(responseWriter)
	responseWriter.WriteHeader(http.StatusNoContent)
}

func (handler Handler) currentSession(request *http.Request) (identity.Session, error) {
	sessionCookie, err := request.Cookie(sessionCookieName)
	if err != nil {
		return identity.Session{}, identity.ErrInvalidSession
	}
	return identity.CurrentSession(request.Context(), handler.database, sessionCookie.Value, handler.sessionSettings)
}

func (handler Handler) setSessionCookies(responseWriter http.ResponseWriter, login identity.LoginResult) {
	maxAge := int(time.Until(login.ExpiresAt).Seconds())
	for _, cookie := range []*http.Cookie{
		{Name: sessionCookieName, Value: login.SessionToken, Path: identityPrefix, HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: handler.secureSessionCookie, Expires: login.ExpiresAt, MaxAge: maxAge},
		{Name: csrfCookieName, Value: login.CSRFToken, Path: identityPrefix, HttpOnly: false, SameSite: http.SameSiteLaxMode, Secure: handler.secureSessionCookie, Expires: login.ExpiresAt, MaxAge: maxAge},
	} {
		http.SetCookie(responseWriter, cookie)
	}
}

func (handler Handler) clearSessionCookies(responseWriter http.ResponseWriter) {
	for _, name := range []string{sessionCookieName, csrfCookieName} {
		http.SetCookie(responseWriter, &http.Cookie{Name: name, Value: "", Path: identityPrefix, HttpOnly: name == sessionCookieName, SameSite: http.SameSiteLaxMode, Secure: handler.secureSessionCookie, MaxAge: -1, Expires: time.Unix(1, 0)})
	}
}

func writeJSON(responseWriter http.ResponseWriter, statusCode int, value any) {
	responseWriter.Header().Set("Content-Type", "application/json; charset=utf-8")
	responseWriter.WriteHeader(statusCode)
	json.NewEncoder(responseWriter).Encode(value)
}
