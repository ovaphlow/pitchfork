package httpapi

import (
	"errors"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
)

type emailLinkRequest struct {
	Email       string `json:"email"`
	DisplayName string `json:"display_name"`
}

type emailLinkExchangeRequest struct {
	Token string `json:"token"`
}

// requestEmailLink asks platformd to mail a one-time sign-in link.
//
// The response never reveals whether the address is registered: platformd
// answers 202 for unknown addresses, rate-limited requests and delivery
// failures, and this handler relays that as 202 + {"status":"accepted"}.
func (deps Deps) requestEmailLink(responseWriter http.ResponseWriter, request *http.Request) {
	var input emailLinkRequest
	if err := decodeAuthJSON(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "Invalid sign-in request.")
		return
	}

	email := strings.TrimSpace(input.Email)
	displayName := strings.TrimSpace(input.DisplayName)
	if email == "" || len(email) > 320 || utf8.RuneCountInString(displayName) > 120 {
		writeError(responseWriter, http.StatusBadRequest, "Invalid sign-in request.")
		return
	}

	if deps.Identity == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "Authentication is temporarily unavailable.")
		return
	}

	err := deps.Identity.RequestEmailLoginLink(
		request.Context(),
		email,
		displayName,
		deps.EmailLinkRedirectURI,
		requestSourceAddress(request, deps.TrustedProxies),
	)
	if err == nil {
		writeJSON(responseWriter, http.StatusAccepted, map[string]string{"status": "accepted"})
		return
	}

	var upstream *identityclient.UpstreamError
	if errors.As(err, &upstream) && upstream.Status == http.StatusBadRequest {
		writeError(responseWriter, http.StatusBadRequest, "Enter a valid email address.")
		return
	}
	// 不记录邮箱：错误日志只带上游状态。
	deps.Logger.Warn("email sign-in request failed", "error", err)
	writeError(responseWriter, http.StatusServiceUnavailable, "Email sign-in is temporarily unavailable.")
}

// exchangeEmailLink consumes the one-time token, relays the IDP session cookies
// and returns the same session payload as a password login.
func (deps Deps) exchangeEmailLink(responseWriter http.ResponseWriter, request *http.Request) {
	responseWriter.Header().Set("Cache-Control", "no-store")

	if deps.Identity == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "Authentication is temporarily unavailable.")
		return
	}

	var input emailLinkExchangeRequest
	if err := decodeAuthJSON(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "Invalid sign-in request.")
		return
	}
	token := strings.TrimSpace(input.Token)
	if token == "" || len(token) > 128 {
		writeError(responseWriter, http.StatusBadRequest, "Invalid sign-in request.")
		return
	}

	access, cookies, err := deps.Identity.ExchangeEmailLoginLink(
		request.Context(),
		token,
		requestSourceAddress(request, deps.TrustedProxies),
	)
	if err != nil {
		var upstream *identityclient.UpstreamError
		if errors.As(err, &upstream) && (upstream.Status == http.StatusUnauthorized || upstream.Status == http.StatusNotFound) {
			writeError(responseWriter, http.StatusUnauthorized, "This sign-in link is invalid or has expired.")
			return
		}
		deps.Logger.Warn("email sign-in exchange failed", "status", upstreamStatus(err))
		writeError(responseWriter, http.StatusServiceUnavailable, "Email sign-in is temporarily unavailable.")
		return
	}
	if access != "完整" {
		writeError(responseWriter, http.StatusForbidden, "This sign-in link cannot be used to sign in.")
		return
	}

	forwardCookies(responseWriter, cookies)
	cookieHeader := identityclient.CookieHeader(cookies)
	identity, err := deps.Identity.CurrentSession(request.Context(), cookieHeader)
	if err != nil {
		deps.writeIdentityError(responseWriter, err, "Authentication is temporarily unavailable.")
		return
	}
	writeJSON(responseWriter, http.StatusOK, deps.buildSession(request.Context(), identity, cookieHeader))
}

// upstreamStatus 只把上游状态码写进日志，避免把 token 或地址带进日志。
func upstreamStatus(err error) int {
	var upstream *identityclient.UpstreamError
	if errors.As(err, &upstream) {
		return upstream.Status
	}
	return 0
}
