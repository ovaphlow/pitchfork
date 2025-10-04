package oidc

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"github.com/jmoiron/sqlx"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user"
)

type Handler struct {
	svc     *OIDCService
	userSvc *user.UserService
	issuer  string
}

func NewHandler(db *sqlx.DB, issuer string) (*Handler, error) {
	o, err := NewOIDCService(db, issuer)
	if err != nil {
		return nil, err
	}
	u := user.NewUserService(db, nil, nil)
	return &Handler{svc: o, userSvc: u, issuer: issuer}, nil
}

func (h *Handler) Discovery(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{
		"issuer":            h.issuer,
		"jwks_uri":          h.issuer + "/jwks.json",
		"token_endpoint":    h.issuer + "/token",
		"userinfo_endpoint": h.issuer + "/userinfo",
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
}

func (h *Handler) JWKS(w http.ResponseWriter, r *http.Request) {
	jwks, _ := h.svc.JWKS()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(jwks)
}

func (h *Handler) Token(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	grant := r.Form.Get("grant_type")
	if grant != "password" && grant != "refresh_token" {
		http.Error(w, "unsupported_grant_type", http.StatusBadRequest)
		return
	}
	clientID := r.Form.Get("client_id")
	if grant == "password" {
		username := r.Form.Get("username")
		password := r.Form.Get("password")
		// authenticate
		u, err := h.userSvc.AuthenticatePassword(r.Context(), username, password)
		if err != nil {
			http.Error(w, "invalid_grant", http.StatusUnauthorized)
			return
		}
		// issue tokens (also returns refresh token)
		idTok, accessTok, refreshTok, err := h.svc.IssueTokens(r.Context(), u, clientID, 15*time.Minute)
		if err != nil {
			http.Error(w, "server_error", http.StatusInternalServerError)
			return
		}
		resp := map[string]any{
			"access_token":  accessTok,
			"id_token":      idTok,
			"refresh_token": refreshTok,
			"token_type":    "Bearer",
			"expires_in":    900,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
		return
	}

	// refresh_token grant
	rt := r.Form.Get("refresh_token")
	if rt == "" {
		http.Error(w, "invalid_request", http.StatusBadRequest)
		return
	}
	session, ok := h.svc.ValidateRefreshToken(r.Context(), rt)
	if !ok {
		http.Error(w, "invalid_grant", http.StatusUnauthorized)
		return
	}
	// load minimal view for the user
	v, err := h.userSvc.GetMinimalAuthView(r.Context(), session.UserID)
	if err != nil {
		http.Error(w, "invalid_grant", http.StatusUnauthorized)
		return
	}
	// rotate refresh token: revoke old and issue new
	if err := h.svc.RevokeRefreshToken(r.Context(), rt); err != nil {
		// best-effort; if revoke fails, reject to avoid issuing new token
		http.Error(w, "invalid_grant", http.StatusUnauthorized)
		return
	}
	idTok, accessTok, newRT, err := h.svc.IssueTokens(r.Context(), v, session.ClientID, 15*time.Minute)
	if err != nil {
		http.Error(w, "server_error", http.StatusInternalServerError)
		return
	}
	resp := map[string]any{
		"access_token":  accessTok,
		"id_token":      idTok,
		"refresh_token": newRT,
		"token_type":    "Bearer",
		"expires_in":    900,
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func (h *Handler) Userinfo(w http.ResponseWriter, r *http.Request) {
	auth := r.Header.Get("Authorization")
	if auth == "" || !strings.HasPrefix(strings.ToLower(auth), "bearer ") {
		http.Error(w, "missing_token", http.StatusUnauthorized)
		return
	}
	token := strings.TrimSpace(auth[len("bearer "):])
	// parse and verify
	claims := map[string]any{}
	_, err := jwt.ParseWithClaims(token, jwt.MapClaims(claims), func(t *jwt.Token) (any, error) {
		return h.svc.PublicKey(), nil
	})
	if err != nil {
		http.Error(w, "invalid_token", http.StatusUnauthorized)
		return
	}
	// return subset
	out := map[string]any{
		"sub":            claims["sub"],
		"email":          claims["email"],
		"email_verified": claims["email_verified"],
		"user_type":      claims["user_type"],
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
}

// Revoke implements RFC 7009 token revocation. It currently supports revoking
// refresh tokens (opaque) persisted in DB. Per spec the endpoint returns 200
// even if the token is invalid.
func (h *Handler) Revoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid_request", http.StatusBadRequest)
		return
	}
	token := r.Form.Get("token")
	if token == "" {
		http.Error(w, "invalid_request", http.StatusBadRequest)
		return
	}
	// Best-effort revoke as refresh token. Ignore errors and always return 200.
	_ = h.svc.RevokeRefreshToken(r.Context(), token)
	w.WriteHeader(http.StatusOK)
}

// Introspect implements RFC 7662 introspection endpoint. It supports
// introspecting opaque refresh tokens (DB) and JWT access tokens issued by
// this service. Response follows RFC structure with `active` boolean.
func (h *Handler) Introspect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid_request", http.StatusBadRequest)
		return
	}
	token := r.Form.Get("token")
	if token == "" {
		http.Error(w, "invalid_request", http.StatusBadRequest)
		return
	}
	// Try opaque refresh token first
	if sess, ok := h.svc.ValidateRefreshToken(r.Context(), token); ok {
		out := map[string]any{
			"active":     true,
			"client_id":  sess.ClientID,
			"sub":        fmt.Sprintf("%d", sess.UserID),
			"exp":        sess.ExpiresAt.Unix(),
			"token_type": "refresh_token",
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
		return
	}

	// Try JWT access token
	claims := jwt.MapClaims{}
	tkn, err := jwt.ParseWithClaims(token, jwt.MapClaims(claims), func(t *jwt.Token) (any, error) {
		return h.svc.PublicKey(), nil
	})
	if err == nil && tkn.Valid {
		out := map[string]any{"active": true}
		// copy common registered claims if present
		if v, ok := claims["sub"]; ok {
			out["sub"] = v
		}
		if v, ok := claims["aud"]; ok {
			out["aud"] = v
		}
		if v, ok := claims["iss"]; ok {
			out["iss"] = v
		}
		if v, ok := claims["exp"]; ok {
			out["exp"] = v
		}
		if v, ok := claims["iat"]; ok {
			out["iat"] = v
		}
		out["token_type"] = "access_token"
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
		return
	}

	// token not active
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"active": false})
}
