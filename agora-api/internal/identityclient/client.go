// Package identityclient talks to platformd. The browser never receives
// the IDP origin directly: agora-api forwards the opaque session cookie and
// relays the IDP's Set-Cookie headers.
package identityclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	// SessionCookieName and CSRFCookieName are opaque platformd cookies.
	SessionCookieName = "platformd_session"
	CSRFCookieName    = "platformd_csrf"

	identityPrefix = "/crate-api/identity/v1"
)

// Identity is the downstream view returned by GET /session.
type Identity struct {
	SubjectID     string `json:"subject_id"`
	DisplayName   string `json:"display_name"`
	Access        string `json:"access"`
	PlatformAdmin bool   `json:"platform_admin"`
}

// UpstreamError carries the status and problem detail returned by platformd.
type UpstreamError struct {
	Status int
	Detail string
}

func (e *UpstreamError) Error() string {
	if e.Detail == "" {
		return fmt.Sprintf("platformd returned HTTP %d", e.Status)
	}
	return fmt.Sprintf("platformd returned HTTP %d: %s", e.Status, e.Detail)
}

// Client is a small HTTP client for the identity service.
type Client struct {
	baseURL string
	http    *http.Client
}

func New(baseURL string, timeout time.Duration) *Client {
	return &Client{
		baseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"),
		http:    &http.Client{Timeout: timeout},
	}
}

// RequestEmailLoginLink asks platformd to mail a one-time sign-in link.
// platformd answers 202 for every address: the caller must not learn whether
// the email is registered.
func (c *Client) RequestEmailLoginLink(ctx context.Context, email, displayName, redirectURI, sourceAddress string) error {
	payload := map[string]string{
		"email":        email,
		"redirect_uri": redirectURI,
	}
	if name := strings.TrimSpace(displayName); name != "" {
		payload["display_name"] = name
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("encode email login link request: %w", err)
	}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+identityPrefix+"/email-login-links", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build email login link request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	if source := strings.TrimSpace(sourceAddress); source != "" {
		request.Header.Set("X-Forwarded-For", source)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return fmt.Errorf("call email login link endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusAccepted {
		return upstreamError(response)
	}
	return nil
}

// ExchangeEmailLoginLink consumes the one-time token and returns the IDP
// session cookies. The caller relays them to the browser.
func (c *Client) ExchangeEmailLoginLink(ctx context.Context, token, sourceAddress string) (string, []*http.Cookie, error) {
	endpoint := c.baseURL + identityPrefix + "/email-login-links/" + url.PathEscape(token) + "/exchange"
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader("{}"))
	if err != nil {
		return "", nil, fmt.Errorf("build email login link exchange request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	if source := strings.TrimSpace(sourceAddress); source != "" {
		request.Header.Set("X-Forwarded-For", source)
	}

	response, err := c.http.Do(request)
	if err != nil {
		// url.Error 会带上完整 URL（其中含一次性 token）：只保留底层原因。
		var urlErr *url.Error
		if errors.As(err, &urlErr) {
			return "", nil, fmt.Errorf("call email login link exchange endpoint: %w", urlErr.Err)
		}
		return "", nil, fmt.Errorf("call email login link exchange endpoint failed")
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return "", nil, upstreamError(response)
	}

	var payload struct {
		Access string `json:"access"`
	}
	if err := decodeJSON(response.Body, &payload); err != nil {
		return "", nil, fmt.Errorf("decode email login link exchange response: %w", err)
	}
	if payload.Access == "" {
		return "", nil, fmt.Errorf("email login link exchange response is missing access")
	}
	return payload.Access, response.Cookies(), nil
}

// Login authenticates with the account identifier and returns the IDP session
// cookies. platformd accepts form-encoded login requests for JSON clients.
func (c *Client) Login(ctx context.Context, identifier, password, sourceAddress string) (string, []*http.Cookie, error) {
	form := url.Values{
		"identifier": {identifier},
		"password":   {password},
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+identityPrefix+"/sessions", strings.NewReader(form.Encode()))
	if err != nil {
		return "", nil, fmt.Errorf("build login request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if source := strings.TrimSpace(sourceAddress); source != "" {
		request.Header.Set("X-Forwarded-For", source)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return "", nil, fmt.Errorf("call login endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return "", nil, upstreamError(response)
	}

	var payload struct {
		Access string `json:"access"`
	}
	if err := decodeJSON(response.Body, &payload); err != nil {
		return "", nil, fmt.Errorf("decode login response: %w", err)
	}
	if payload.Access == "" {
		return "", nil, fmt.Errorf("login response is missing access")
	}
	return payload.Access, response.Cookies(), nil
}

// CurrentSession resolves the opaque cookie without inspecting its contents.
func (c *Client) CurrentSession(ctx context.Context, cookieHeader string) (Identity, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+identityPrefix+"/session", nil)
	if err != nil {
		return Identity{}, fmt.Errorf("build session request: %w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cookie", cookieHeader)

	response, err := c.http.Do(request)
	if err != nil {
		return Identity{}, fmt.Errorf("call session endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return Identity{}, upstreamError(response)
	}

	var identity Identity
	if err := decodeJSON(response.Body, &identity); err != nil {
		return Identity{}, fmt.Errorf("decode session response: %w", err)
	}
	if identity.SubjectID == "" || identity.Access == "" {
		return Identity{}, fmt.Errorf("session response is missing subject_id or access")
	}
	return identity, nil
}

// Logout revokes the IDP session and returns the cookie expirations to relay.
func (c *Client) Logout(ctx context.Context, cookieHeader string) ([]*http.Cookie, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodDelete, c.baseURL+identityPrefix+"/sessions/current", nil)
	if err != nil {
		return nil, fmt.Errorf("build logout request: %w", err)
	}
	request.Header.Set("Cookie", cookieHeader)
	if csrfToken := cookieValue(cookieHeader, CSRFCookieName); csrfToken != "" {
		request.Header.Set("X-CSRF-Token", csrfToken)
	}

	response, err := c.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("call logout endpoint: %w", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusNoContent {
		return nil, upstreamError(response)
	}
	return response.Cookies(), nil
}

// CookieHeader serializes cookies for a forwarded Cookie header.
func CookieHeader(cookies []*http.Cookie) string {
	parts := make([]string, 0, len(cookies))
	for _, cookie := range cookies {
		parts = append(parts, cookie.Name+"="+cookie.Value)
	}
	return strings.Join(parts, "; ")
}

func cookieValue(cookieHeader, name string) string {
	for _, part := range strings.Split(cookieHeader, ";") {
		key, value, ok := strings.Cut(strings.TrimSpace(part), "=")
		if ok && key == name {
			return value
		}
	}
	return ""
}

func decodeJSON(reader io.Reader, destination any) error {
	return json.NewDecoder(io.LimitReader(reader, 1<<20)).Decode(destination)
}

func upstreamError(response *http.Response) error {
	var problem struct {
		Detail string `json:"detail"`
		Error  string `json:"error"`
	}
	_ = decodeJSON(response.Body, &problem)
	detail := strings.TrimSpace(problem.Detail)
	if detail == "" {
		detail = strings.TrimSpace(problem.Error)
	}
	return &UpstreamError{Status: response.StatusCode, Detail: detail}
}
