package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
)

const maxAuthBodyBytes = 64 << 10

type loginInput struct {
	Identifier string `json:"identifier"`
	Password   string `json:"password"`
}

type authSessionResponse struct {
	SubjectID     string   `json:"subject_id"`
	DisplayName   string   `json:"display_name"`
	Identifier    string   `json:"identifier,omitempty"`
	PlatformAdmin bool     `json:"platform_admin"`
	Access        string   `json:"access"`
	Roles         []string `json:"roles"`
	Permissions   []string `json:"permissions"`
	NexusReady    bool     `json:"nexus_ready"`
}

func (deps Deps) login(responseWriter http.ResponseWriter, request *http.Request) {
	if deps.Identity == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "Authentication is temporarily unavailable.")
		return
	}

	var input loginInput
	if err := decodeAuthJSON(responseWriter, request, &input); err != nil {
		writeError(responseWriter, http.StatusBadRequest, "Invalid login request.")
		return
	}

	identifier, password, message := validateLogin(input)
	if message != "" {
		writeError(responseWriter, http.StatusBadRequest, message)
		return
	}

	access, cookies, err := deps.Identity.Login(request.Context(), identifier, password, requestSourceAddress(request, deps.TrustedProxies))
	if err != nil {
		deps.writeIdentityError(responseWriter, err, "Incorrect account or password.")
		return
	}
	if access != "完整" {
		writeError(responseWriter, http.StatusForbidden, "Password change is required before signing in.")
		return
	}

	identity, err := deps.Identity.CurrentSession(request.Context(), identityclient.CookieHeader(cookies))
	if err != nil {
		deps.writeIdentityError(responseWriter, err, "Authentication is temporarily unavailable.")
		return
	}

	forwardCookies(responseWriter, cookies)
	writeJSON(responseWriter, http.StatusOK, deps.buildSession(request.Context(), identity, identityclient.CookieHeader(cookies)))
}

func (deps Deps) currentSession(responseWriter http.ResponseWriter, request *http.Request) {
	if deps.Identity == nil {
		writeError(responseWriter, http.StatusServiceUnavailable, "Authentication is temporarily unavailable.")
		return
	}

	cookieHeader := strings.TrimSpace(request.Header.Get("Cookie"))
	if cookieHeader == "" {
		writeError(responseWriter, http.StatusUnauthorized, "Not signed in.")
		return
	}

	identity, err := deps.Identity.CurrentSession(request.Context(), cookieHeader)
	if err != nil {
		deps.writeIdentityError(responseWriter, err, "Not signed in.")
		return
	}

	// Any subject opening Agora joins the Agora user registry. Other products'
	// accounts never reach this handler.
	if deps.Intents != nil {
		if err := deps.Intents.Ensure(request.Context(), identity.SubjectID); err != nil {
			deps.Logger.Warn("register Agora user failed", "request_id", requestIDFrom(request.Context()), "error", err)
		}
	}

	writeJSON(responseWriter, http.StatusOK, deps.buildSession(request.Context(), identity, cookieHeader))
}

func (deps Deps) logout(responseWriter http.ResponseWriter, request *http.Request) {
	cookieHeader := strings.TrimSpace(request.Header.Get("Cookie"))
	if deps.Identity != nil && cookieHeader != "" {
		cookies, err := deps.Identity.Logout(request.Context(), cookieHeader)
		if err != nil {
			deps.Logger.Warn("identity logout failed", "error", err)
		} else {
			forwardCookies(responseWriter, cookies)
		}
	}
	clearAuthCookies(responseWriter)
	responseWriter.WriteHeader(http.StatusNoContent)
}

// buildSession joins the identity view with platformd's shared roles and permissions.
// Base data is best-effort: a shared API outage must not block a valid login.
func (deps Deps) buildSession(ctx context.Context, identity identityclient.Identity, cookieHeader string) authSessionResponse {
	response := authSessionResponse{
		SubjectID:     identity.SubjectID,
		DisplayName:   identity.DisplayName,
		PlatformAdmin: identity.PlatformAdmin,
		Access:        identity.Access,
		Roles:         []string{},
		Permissions:   []string{},
	}
	if identity.Access != "完整" || cookieHeader == "" || deps.Nexus == nil {
		return response
	}

	permissions, err := deps.Nexus.SubjectPermissions(ctx, cookieHeader, identity.SubjectID)
	if err != nil {
		deps.Logger.Warn("load nexus permissions failed", "error", err)
		return response
	}

	response.Roles = permissions.RoleCodes
	response.Permissions = permissions.PermissionCodes
	response.NexusReady = true
	return response
}

func (deps Deps) writeIdentityError(responseWriter http.ResponseWriter, err error, unauthorizedMessage string) {
	var upstream *identityclient.UpstreamError
	if errors.As(err, &upstream) {
		deps.Logger.Warn("identity request failed", "status", upstream.Status)
		switch upstream.Status {
		case http.StatusUnauthorized, http.StatusNotFound:
			writeError(responseWriter, http.StatusUnauthorized, unauthorizedMessage)
		case http.StatusBadRequest:
			writeError(responseWriter, http.StatusBadRequest, "Check the account details and try again.")
		case http.StatusTooManyRequests:
			writeError(responseWriter, http.StatusTooManyRequests, "Too many attempts. Try again later.")
		default:
			writeError(responseWriter, http.StatusServiceUnavailable, "Authentication is temporarily unavailable.")
		}
		return
	}

	deps.Logger.Warn("identity request failed", "error", err)
	writeError(responseWriter, http.StatusServiceUnavailable, "Authentication is temporarily unavailable.")
}

func validateLogin(input loginInput) (string, string, string) {
	identifier := strings.TrimSpace(input.Identifier)
	if identifier == "" || len(identifier) > 320 {
		return "", "", "Enter your account and password."
	}
	if len(input.Password) == 0 || len(input.Password) > 1024 {
		return "", "", "Enter your account and password."
	}
	return identifier, input.Password, ""
}

func decodeAuthJSON(responseWriter http.ResponseWriter, request *http.Request, destination any) error {
	request.Body = http.MaxBytesReader(responseWriter, request.Body, maxAuthBodyBytes)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request must contain one JSON value")
	}
	return nil
}

func requestSourceAddress(request *http.Request, trustedProxies []netip.Prefix) string {
	peer := remoteAddress(request.RemoteAddr)
	peerAddress, err := netip.ParseAddr(peer)
	if err != nil || !addressTrusted(peerAddress, trustedProxies) {
		return peer
	}

	forwarded := strings.TrimSpace(request.Header.Get("X-Forwarded-For"))
	if forwarded == "" {
		return peer
	}

	parts := strings.Split(forwarded, ",")
	for index := len(parts) - 1; index >= 0; index-- {
		candidate, err := netip.ParseAddr(strings.TrimSpace(parts[index]))
		if err != nil {
			continue
		}
		if !addressTrusted(candidate, trustedProxies) {
			return candidate.String()
		}
	}
	return peer
}

func remoteAddress(remoteAddr string) string {
	host, _, err := net.SplitHostPort(strings.TrimSpace(remoteAddr))
	if err != nil {
		return strings.TrimSpace(remoteAddr)
	}
	return host
}

func addressTrusted(address netip.Addr, prefixes []netip.Prefix) bool {
	address = address.Unmap()
	for _, prefix := range prefixes {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func forwardCookies(responseWriter http.ResponseWriter, cookies []*http.Cookie) {
	for _, cookie := range cookies {
		if cookie.Name != "" {
			http.SetCookie(responseWriter, cookie)
		}
	}
}

func clearAuthCookies(responseWriter http.ResponseWriter) {
	for _, name := range []string{identityclient.SessionCookieName, identityclient.CSRFCookieName} {
		http.SetCookie(responseWriter, &http.Cookie{
			Name:     name,
			Value:    "",
			Path:     "/",
			MaxAge:   -1,
			Expires:  time.Unix(1, 0),
			HttpOnly: name == identityclient.SessionCookieName,
			SameSite: http.SameSiteLaxMode,
		})
	}
}
