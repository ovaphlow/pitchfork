package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
	"github.com/ovaphlow/pitchfork/service-idp-go/web"
)

const (
	identityPrefix        = "/crate-api/identity/v1"
	identityAssetsPrefix  = identityPrefix + "/assets"
	problemMediaType      = "application/problem+json"
	problemTypePrefix     = identityPrefix + "/problems/"
	problemGenericDetail  = "The request could not be completed."
	problemNotFoundDetail = "The requested resource was not found."
	problemMethodDetail   = "The request method is not allowed for this resource."
)

const (
	defaultSubjectListLimit int64 = 20
	maximumSubjectListLimit int64 = 100
	maximumJSONBodyBytes          = 1 << 20
)

const (
	sessionCookieName = "identityd_session"
	csrfCookieName    = "identityd_csrf"
)

type Options struct {
	SessionSettings      identity.SessionSettings
	LoginThrottle        identity.LoginThrottleSettings
	SecureSessionCookie  bool
	TrustedProxyPrefixes []netip.Prefix
}

type Handler struct {
	database             *sql.DB
	sessionSettings      identity.SessionSettings
	loginThrottle        identity.LoginThrottleSettings
	secureSessionCookie  bool
	trustedProxyPrefixes []netip.Prefix
}

func NewMux(database *sql.DB, options Options) http.Handler {
	handler := Handler{
		database:             database,
		sessionSettings:      options.SessionSettings,
		loginThrottle:        options.LoginThrottle,
		secureSessionCookie:  options.SecureSessionCookie,
		trustedProxyPrefixes: append([]netip.Prefix(nil), options.TrustedProxyPrefixes...),
	}
	staticFiles, err := fs.Sub(web.StaticFiles, "static")
	if err != nil {
		panic("load embedded static assets: " + err.Error())
	}
	mux := http.NewServeMux()
	mux.Handle("GET "+identityAssetsPrefix+"/{path...}", http.StripPrefix(identityAssetsPrefix+"/", http.FileServerFS(staticFiles)))
	mux.HandleFunc("GET "+identityPrefix+"/healthz", handler.health)
	mux.HandleFunc("GET "+identityPrefix+"/login", handler.loginPage)
	mux.HandleFunc("POST "+identityPrefix+"/sessions", handler.createSession)
	mux.HandleFunc("DELETE "+identityPrefix+"/sessions/current", handler.deleteCurrentSession)
	mux.HandleFunc("GET "+identityPrefix+"/password", handler.passwordPage)
	mux.HandleFunc("PATCH "+identityPrefix+"/password", handler.changePassword)
	mux.HandleFunc("POST "+identityPrefix+"/password", handler.changePassword)
	mux.HandleFunc("GET "+identityPrefix+"/dashboard", handler.dashboard)
	mux.HandleFunc("GET "+identityPrefix+"/subjects", handler.listSubjects)
	mux.HandleFunc("POST "+identityPrefix+"/subjects", handler.createSubject)
	mux.HandleFunc("GET "+identityPrefix+"/subjects/{subjectID}", handler.getSubject)
	mux.HandleFunc("PATCH "+identityPrefix+"/subjects/{subjectID}", handler.updateSubject)
	return problemDetailsHandler{next: mux}
}

func (handler Handler) health(responseWriter http.ResponseWriter, request *http.Request) {
	if err := handler.database.PingContext(request.Context()); err != nil {
		writeProblem(responseWriter, request, http.StatusServiceUnavailable, "service-unavailable", "database unavailable")
		return
	}
	responseWriter.WriteHeader(http.StatusNoContent)
}

func (handler Handler) loginPage(responseWriter http.ResponseWriter, request *http.Request) {
	if session, err := handler.currentSession(request); err == nil {
		destination := identityPrefix + "/dashboard"
		if session.Access == "仅改密" {
			destination = identityPrefix + "/password"
		}
		http.Redirect(responseWriter, request, destination, http.StatusSeeOther)
		return
	}
	responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
	loginTemplate.Execute(responseWriter, loginPageData{HasError: request.URL.Query().Get("error") == "1"})
}

func (handler Handler) createSession(responseWriter http.ResponseWriter, request *http.Request) {
	if err := request.ParseForm(); err != nil {
		http.Redirect(responseWriter, request, identityPrefix+"/login?error=1", http.StatusSeeOther)
		return
	}
	login, err := identity.Login(request.Context(), handler.database, identity.LoginInput{
		Identifier:    request.Form.Get("identifier"),
		Password:      request.Form.Get("password"),
		SourceAddress: clientSourceAddress(request, handler.trustedProxyPrefixes),
	}, handler.sessionSettings, handler.loginThrottle)
	if err != nil {
		http.Redirect(responseWriter, request, identityPrefix+"/login?error=1", http.StatusSeeOther)
		return
	}
	handler.setSessionCookies(responseWriter, login)
	destination := identityPrefix + "/dashboard"
	if login.Access == "仅改密" {
		destination = identityPrefix + "/password"
	}
	http.Redirect(responseWriter, request, destination, http.StatusSeeOther)
}

func (handler Handler) passwordPage(responseWriter http.ResponseWriter, request *http.Request) {
	session, err := handler.currentSession(request)
	if err != nil {
		handler.clearSessionCookies(responseWriter)
		http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
		return
	}
	responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
	passwordTemplate.Execute(responseWriter, passwordPageData{
		CSRFToken:        requestCSRFToken(request),
		PasswordRequired: session.Access == "仅改密",
		HasError:         request.URL.Query().Get("error") == "1",
	})
}

type changePasswordRequest struct {
	CurrentPassword string `json:"current_password"`
	NewPassword     string `json:"new_password"`
}

func (handler Handler) changePassword(responseWriter http.ResponseWriter, request *http.Request) {
	htmlRequest := request.Method == http.MethodPost || wantsHTML(request)
	session, err := handler.currentSession(request)
	if err != nil {
		handler.clearSessionCookies(responseWriter)
		if htmlRequest {
			http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
			return
		}
		writeProblem(responseWriter, request, http.StatusUnauthorized, "not-authenticated", "not authenticated")
		return
	}
	if !requestHasValidCSRFToken(request, session) {
		if htmlRequest {
			handler.redirectPasswordWithError(responseWriter, request)
			return
		}
		writeProblem(responseWriter, request, http.StatusForbidden, "invalid-csrf-token", "invalid CSRF token")
		return
	}

	var input changePasswordRequest
	if htmlRequest {
		if err := request.ParseForm(); err != nil {
			handler.redirectPasswordWithError(responseWriter, request)
			return
		}
		input = changePasswordRequest{
			CurrentPassword: request.PostForm.Get("current_password"),
			NewPassword:     request.PostForm.Get("new_password"),
		}
	} else if err := decodeJSON(request, responseWriter, &input); err != nil {
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid JSON request")
		return
	}
	if err := identity.ChangePassword(request.Context(), handler.database, session.SubjectID, identity.ChangePasswordInput{
		CurrentPassword: input.CurrentPassword,
		NewPassword:     input.NewPassword,
	}); err != nil {
		if htmlRequest {
			handler.redirectPasswordWithError(responseWriter, request)
			return
		}
		handler.writePasswordChangeError(responseWriter, request, err)
		return
	}
	handler.clearSessionCookies(responseWriter)
	if htmlRequest {
		if isHTMXRequest(request) {
			responseWriter.Header().Set("HX-Redirect", identityPrefix+"/login")
			responseWriter.WriteHeader(http.StatusOK)
			return
		}
		http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
		return
	}
	responseWriter.WriteHeader(http.StatusNoContent)
}

func (handler Handler) dashboard(responseWriter http.ResponseWriter, request *http.Request) {
	session, err := handler.currentSession(request)
	if err != nil {
		handler.clearSessionCookies(responseWriter)
		http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
		return
	}
	if session.Access != "完整" {
		writeProblem(responseWriter, request, http.StatusForbidden, "password-change-required", "password change required")
		return
	}
	administrator, err := identity.HasRole(request.Context(), handler.database, session.SubjectID, "identity.admin")
	if err != nil {
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not authorize subject")
		return
	}
	if !administrator {
		writeProblem(responseWriter, request, http.StatusForbidden, "not-authorized", "not authorized")
		return
	}

	responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
	dashboardTemplate.Execute(responseWriter, dashboardPageData{SubjectID: session.SubjectID, CSRFToken: requestCSRFToken(request)})
}

func (handler Handler) deleteCurrentSession(responseWriter http.ResponseWriter, request *http.Request) {
	session, err := handler.currentSession(request)
	if err != nil {
		writeProblem(responseWriter, request, http.StatusUnauthorized, "not-authenticated", "not authenticated")
		return
	}
	if !requestHasValidCSRFToken(request, session) {
		writeProblem(responseWriter, request, http.StatusForbidden, "invalid-csrf-token", "invalid CSRF token")
		return
	}
	sessionCookie, _ := request.Cookie(sessionCookieName)
	if err := identity.Logout(request.Context(), handler.database, sessionCookie.Value); err != nil && !errors.Is(err, identity.ErrInvalidSession) {
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not end session")
		return
	}
	handler.clearSessionCookies(responseWriter)
	responseWriter.WriteHeader(http.StatusNoContent)
}

func (handler Handler) listSubjects(responseWriter http.ResponseWriter, request *http.Request) {
	setSubjectRepresentationVary(responseWriter)
	if wantsHTML(request) {
		_, ok := handler.requireAdministratorPage(responseWriter, request)
		if !ok {
			return
		}
		handler.renderSubjectsPage(responseWriter, request, http.StatusOK)
		return
	}
	if _, ok := handler.requireAdministrator(responseWriter, request); !ok {
		return
	}
	limit, offset, err := subjectListPagination(request)
	if err != nil {
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid pagination")
		return
	}
	result, err := identity.ListSubjects(request.Context(), handler.database, identity.ListSubjectsInput{Limit: limit, Offset: offset})
	if err != nil {
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not list subjects")
		return
	}
	writeJSON(responseWriter, http.StatusOK, map[string]any{
		"records": result.Subjects,
		"meta":    map[string]int64{"total": result.Total},
	})
}

func (handler Handler) getSubject(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireAdministrator(responseWriter, request); !ok {
		return
	}
	subject, err := identity.GetSubject(request.Context(), handler.database, request.PathValue("subjectID"))
	if err != nil {
		handler.writeSubjectManagementError(responseWriter, request, err)
		return
	}
	writeJSON(responseWriter, http.StatusOK, subject)
}

type createSubjectRequest struct {
	DisplayName string `json:"display_name"`
	Identifier  string `json:"identifier"`
	Password    string `json:"password"`
}

func (handler Handler) createSubject(responseWriter http.ResponseWriter, request *http.Request) {
	setSubjectRepresentationVary(responseWriter)
	htmlRequest := wantsHTML(request)
	var session identity.Session
	var ok bool
	if htmlRequest {
		session, ok = handler.requireAdministratorPage(responseWriter, request)
	} else {
		session, ok = handler.requireAdministrator(responseWriter, request)
	}
	if !ok {
		return
	}
	if !requestHasValidCSRFToken(request, session) {
		if htmlRequest {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		writeProblem(responseWriter, request, http.StatusForbidden, "invalid-csrf-token", "invalid CSRF token")
		return
	}
	var input createSubjectRequest
	if htmlRequest {
		if err := request.ParseForm(); err != nil {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		input = createSubjectRequest{
			DisplayName: request.PostForm.Get("display_name"),
			Identifier:  request.PostForm.Get("identifier"),
			Password:    request.PostForm.Get("password"),
		}
	} else if err := decodeJSON(request, responseWriter, &input); err != nil {
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid JSON request")
		return
	}
	subject, err := identity.CreateSubject(request.Context(), handler.database, session.SubjectID, identity.CreateSubjectInput{
		DisplayName: input.DisplayName,
		Identifier:  input.Identifier,
		Password:    input.Password,
	})
	if err != nil {
		if htmlRequest {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		handler.writeSubjectManagementError(responseWriter, request, err)
		return
	}
	if htmlRequest {
		if isHTMXRequest(request) {
			responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
			responseWriter.WriteHeader(http.StatusCreated)
			subjectRowTemplate.ExecuteTemplate(responseWriter, "subject-row", subjectRowData{CSRFToken: requestCSRFToken(request), Subject: subject})
			return
		}
		http.Redirect(responseWriter, request, identityPrefix+"/subjects", http.StatusSeeOther)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, subject)
}

type updateSubjectRequest struct {
	Status            string `json:"status"`
	TemporaryPassword string `json:"temporary_password"`
}

func (handler Handler) updateSubject(responseWriter http.ResponseWriter, request *http.Request) {
	setSubjectRepresentationVary(responseWriter)
	htmlRequest := wantsHTML(request)
	var session identity.Session
	var ok bool
	if htmlRequest {
		session, ok = handler.requireAdministratorPage(responseWriter, request)
	} else {
		session, ok = handler.requireAdministrator(responseWriter, request)
	}
	if !ok {
		return
	}
	if !requestHasValidCSRFToken(request, session) {
		if htmlRequest {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		writeProblem(responseWriter, request, http.StatusForbidden, "invalid-csrf-token", "invalid CSRF token")
		return
	}
	var input updateSubjectRequest
	if htmlRequest {
		if err := request.ParseForm(); err != nil {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		input = updateSubjectRequest{
			Status:            request.PostForm.Get("status"),
			TemporaryPassword: request.PostForm.Get("temporary_password"),
		}
	} else if err := decodeJSON(request, responseWriter, &input); err != nil {
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid JSON request")
		return
	}
	subjectID := request.PathValue("subjectID")
	var subject identity.Subject
	var err error
	switch {
	case input.Status == "禁用" && input.TemporaryPassword == "":
		subject, err = identity.DisableSubject(request.Context(), handler.database, session.SubjectID, subjectID)
	case input.Status == "" && input.TemporaryPassword != "":
		err = identity.SetTemporaryPassword(request.Context(), handler.database, session.SubjectID, subjectID, input.TemporaryPassword)
		if err == nil {
			subject, err = identity.GetSubject(request.Context(), handler.database, subjectID)
		}
	default:
		if htmlRequest {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "unsupported subject update")
		return
	}
	if err != nil {
		if htmlRequest {
			handler.redirectSubjectsWithError(responseWriter, request)
			return
		}
		handler.writeSubjectManagementError(responseWriter, request, err)
		return
	}
	if subject.ID == session.SubjectID {
		handler.clearSessionCookies(responseWriter)
		if htmlRequest {
			if isHTMXRequest(request) {
				responseWriter.Header().Set("HX-Redirect", identityPrefix+"/login")
				responseWriter.WriteHeader(http.StatusOK)
				return
			}
			http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
			return
		}
	}
	if htmlRequest {
		if isHTMXRequest(request) {
			responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
			subjectRowTemplate.ExecuteTemplate(responseWriter, "subject-row", subjectRowData{CSRFToken: requestCSRFToken(request), Subject: subject})
			return
		}
		http.Redirect(responseWriter, request, identityPrefix+"/subjects", http.StatusSeeOther)
		return
	}
	writeJSON(responseWriter, http.StatusOK, subject)
}

func (handler Handler) redirectPasswordWithError(responseWriter http.ResponseWriter, request *http.Request) {
	location := identityPrefix + "/password?error=1"
	if isHTMXRequest(request) {
		responseWriter.Header().Set("HX-Redirect", location)
		responseWriter.WriteHeader(http.StatusUnprocessableEntity)
		return
	}
	http.Redirect(responseWriter, request, location, http.StatusSeeOther)
}

func (handler Handler) writePasswordChangeError(responseWriter http.ResponseWriter, request *http.Request, err error) {
	switch {
	case errors.Is(err, identity.ErrInvalidPasswordInput), errors.Is(err, identity.ErrIncorrectPassword):
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-password-change", "invalid password change")
	case errors.Is(err, identity.ErrPasswordUpdateConflict):
		writeProblem(responseWriter, request, http.StatusConflict, "password-change-conflict", "password changed concurrently")
	case errors.Is(err, identity.ErrPasswordCredentialNotFound):
		writeProblem(responseWriter, request, http.StatusNotFound, "subject-not-found", "subject not found")
	default:
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not change password")
	}
}

func (handler Handler) requireAdministrator(responseWriter http.ResponseWriter, request *http.Request) (identity.Session, bool) {
	session, err := handler.currentSession(request)
	if err != nil {
		handler.clearSessionCookies(responseWriter)
		writeProblem(responseWriter, request, http.StatusUnauthorized, "not-authenticated", "not authenticated")
		return identity.Session{}, false
	}
	if session.Access != "完整" {
		writeProblem(responseWriter, request, http.StatusForbidden, "password-change-required", "password change required")
		return identity.Session{}, false
	}
	administrator, err := identity.HasRole(request.Context(), handler.database, session.SubjectID, "identity.admin")
	if err != nil {
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not authorize subject")
		return identity.Session{}, false
	}
	if !administrator {
		writeProblem(responseWriter, request, http.StatusForbidden, "not-authorized", "not authorized")
		return identity.Session{}, false
	}
	return session, true
}

func (handler Handler) requireAdministratorPage(responseWriter http.ResponseWriter, request *http.Request) (identity.Session, bool) {
	session, err := handler.currentSession(request)
	if err != nil {
		handler.clearSessionCookies(responseWriter)
		http.Redirect(responseWriter, request, identityPrefix+"/login", http.StatusSeeOther)
		return identity.Session{}, false
	}
	if session.Access != "完整" {
		writeProblem(responseWriter, request, http.StatusForbidden, "password-change-required", "password change required")
		return identity.Session{}, false
	}
	administrator, err := identity.HasRole(request.Context(), handler.database, session.SubjectID, "identity.admin")
	if err != nil {
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not authorize subject")
		return identity.Session{}, false
	}
	if !administrator {
		writeProblem(responseWriter, request, http.StatusForbidden, "not-authorized", "not authorized")
		return identity.Session{}, false
	}
	return session, true
}

func (handler Handler) renderSubjectsPage(responseWriter http.ResponseWriter, request *http.Request, statusCode int) {
	result, err := identity.ListSubjects(request.Context(), handler.database, identity.ListSubjectsInput{Limit: defaultSubjectListLimit, Offset: 0})
	if err != nil {
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not list subjects")
		return
	}
	csrfToken := requestCSRFToken(request)
	rows := make([]subjectRowData, 0, len(result.Subjects))
	for _, subject := range result.Subjects {
		rows = append(rows, subjectRowData{CSRFToken: csrfToken, Subject: subject})
	}
	responseWriter.Header().Set("Content-Type", "text/html; charset=utf-8")
	responseWriter.WriteHeader(statusCode)
	subjectsTemplate.Execute(responseWriter, subjectsPageData{
		CSRFToken: csrfToken,
		Rows:      rows,
		Total:     result.Total,
		HasError:  request.URL.Query().Get("error") == "1",
	})
}

func (handler Handler) redirectSubjectsWithError(responseWriter http.ResponseWriter, request *http.Request) {
	location := identityPrefix + "/subjects?error=1"
	if isHTMXRequest(request) {
		responseWriter.Header().Set("HX-Redirect", location)
		responseWriter.WriteHeader(http.StatusUnprocessableEntity)
		return
	}
	http.Redirect(responseWriter, request, location, http.StatusSeeOther)
}

func (handler Handler) writeSubjectManagementError(responseWriter http.ResponseWriter, request *http.Request, err error) {
	switch {
	case errors.Is(err, identity.ErrSubjectNotFound), errors.Is(err, identity.ErrPasswordCredentialNotFound):
		writeProblem(responseWriter, request, http.StatusNotFound, "subject-not-found", "subject not found")
	case errors.Is(err, identity.ErrIdentifierAlreadyExists), errors.Is(err, identity.ErrInvalidSubjectInput), errors.Is(err, identity.ErrInvalidPasswordInput):
		writeProblem(responseWriter, request, http.StatusBadRequest, "invalid-request", "invalid subject input")
	case errors.Is(err, identity.ErrPasswordUpdateConflict):
		writeProblem(responseWriter, request, http.StatusConflict, "password-change-conflict", "password changed concurrently")
	case errors.Is(err, identity.ErrLastAdministrator):
		writeProblem(responseWriter, request, http.StatusForbidden, "last-administrator", "cannot disable the last enabled administrator")
	default:
		writeProblem(responseWriter, request, http.StatusInternalServerError, "internal-error", "could not manage subject")
	}
}

func subjectListPagination(request *http.Request) (int64, int64, error) {
	limit, err := parseNonnegativeQueryInteger(request, "limit", defaultSubjectListLimit)
	if err != nil || limit < 1 || limit > maximumSubjectListLimit {
		return 0, 0, errors.New("invalid limit")
	}
	offset, err := parseNonnegativeQueryInteger(request, "offset", 0)
	if err != nil {
		return 0, 0, errors.New("invalid offset")
	}
	return limit, offset, nil
}

func parseNonnegativeQueryInteger(request *http.Request, key string, defaultValue int64) (int64, error) {
	value := request.URL.Query().Get(key)
	if value == "" {
		return defaultValue, nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed < 0 {
		return 0, errors.New("invalid query integer")
	}
	return parsed, nil
}

func requestHasValidCSRFToken(request *http.Request, session identity.Session) bool {
	csrfCookie, err := request.Cookie(csrfCookieName)
	if err != nil {
		return false
	}
	providedToken := request.Header.Get("X-CSRF-Token")
	if providedToken == "" && strings.HasPrefix(request.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
		if err := request.ParseForm(); err != nil {
			return false
		}
		providedToken = request.PostForm.Get("csrf_token")
	}
	return providedToken != "" && csrfCookie.Value == providedToken && identity.VerifyCSRF(session, csrfCookie.Value)
}

func requestCSRFToken(request *http.Request) string {
	csrfCookie, err := request.Cookie(csrfCookieName)
	if err != nil {
		return ""
	}
	return csrfCookie.Value
}

func wantsHTML(request *http.Request) bool {
	return isHTMXRequest(request) || strings.Contains(request.Header.Get("Accept"), "text/html")
}

func isHTMXRequest(request *http.Request) bool {
	return request.Header.Get("HX-Request") == "true"
}

func setSubjectRepresentationVary(responseWriter http.ResponseWriter) {
	responseWriter.Header().Add("Vary", "Accept")
	responseWriter.Header().Add("Vary", "HX-Request")
}

func decodeJSON(request *http.Request, responseWriter http.ResponseWriter, destination any) error {
	decoder := json.NewDecoder(http.MaxBytesReader(responseWriter, request.Body, maximumJSONBodyBytes))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request must contain one JSON value")
	}
	return nil
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
	_ = json.NewEncoder(responseWriter).Encode(value)
}

type problemDetails struct {
	Type     string `json:"type"`
	Title    string `json:"title"`
	Status   int    `json:"status"`
	Detail   string `json:"detail"`
	Instance string `json:"instance"`
}

func writeProblem(responseWriter http.ResponseWriter, request *http.Request, statusCode int, problemType string, detail string) {
	responseWriter.Header().Del("Content-Length")
	responseWriter.Header().Set("Content-Type", problemMediaType)
	responseWriter.WriteHeader(statusCode)
	_ = json.NewEncoder(responseWriter).Encode(problemDetails{
		Type:     problemTypePrefix + problemType,
		Title:    http.StatusText(statusCode),
		Status:   statusCode,
		Detail:   detail,
		Instance: request.URL.RequestURI(),
	})
}

type problemDetailsHandler struct {
	next http.Handler
}

func (handler problemDetailsHandler) ServeHTTP(responseWriter http.ResponseWriter, request *http.Request) {
	handler.next.ServeHTTP(&problemDetailsResponseWriter{ResponseWriter: responseWriter, request: request}, request)
}

type problemDetailsResponseWriter struct {
	http.ResponseWriter
	request     *http.Request
	wroteHeader bool
	discardBody bool
}

func (responseWriter *problemDetailsResponseWriter) WriteHeader(statusCode int) {
	if responseWriter.wroteHeader {
		return
	}
	responseWriter.wroteHeader = true
	if statusCode >= http.StatusBadRequest && responseWriter.Header().Get("HX-Redirect") == "" && !isProblemMediaType(responseWriter.Header().Get("Content-Type")) {
		responseWriter.discardBody = true
		writeProblem(responseWriter.ResponseWriter, responseWriter.request, statusCode, problemTypeForStatus(statusCode), problemDetailForStatus(statusCode))
		return
	}
	responseWriter.ResponseWriter.WriteHeader(statusCode)
}

func (responseWriter *problemDetailsResponseWriter) Write(value []byte) (int, error) {
	if !responseWriter.wroteHeader {
		responseWriter.WriteHeader(http.StatusOK)
	}
	if responseWriter.discardBody {
		return len(value), nil
	}
	return responseWriter.ResponseWriter.Write(value)
}

func (responseWriter *problemDetailsResponseWriter) Unwrap() http.ResponseWriter {
	return responseWriter.ResponseWriter
}

func isProblemMediaType(contentType string) bool {
	mediaType, _, _ := strings.Cut(contentType, ";")
	return strings.EqualFold(strings.TrimSpace(mediaType), problemMediaType)
}

func problemTypeForStatus(statusCode int) string {
	switch statusCode {
	case http.StatusBadRequest:
		return "invalid-request"
	case http.StatusUnauthorized:
		return "not-authenticated"
	case http.StatusForbidden:
		return "not-authorized"
	case http.StatusNotFound:
		return "not-found"
	case http.StatusMethodNotAllowed:
		return "method-not-allowed"
	default:
		return "internal-error"
	}
}

func problemDetailForStatus(statusCode int) string {
	switch statusCode {
	case http.StatusNotFound:
		return problemNotFoundDetail
	case http.StatusMethodNotAllowed:
		return problemMethodDetail
	default:
		return problemGenericDetail
	}
}
