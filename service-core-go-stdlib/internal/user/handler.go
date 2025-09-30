package user

import (
	"encoding/json"
	"net/http"

	"github.com/jmoiron/sqlx"
	"go.uber.org/zap"

	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user/entity"
)

// Handler exposes HTTP endpoints for user operations (signup / login).
type Handler struct {
	svc    *UserService
	logger *zap.SugaredLogger
}

func NewHandler(db *sqlx.DB, logger *zap.SugaredLogger) *Handler {
	svc := NewUserService(db, nil, nil)
	return &Handler{svc: svc, logger: logger}
}

// SignupRequest request body for signup endpoint.
type SignupRequest struct {
	Username  string `json:"username"`
	Email     string `json:"email"`
	Password  string `json:"password"`
	UserType  string `json:"user_type"`
	MustReset bool   `json:"must_reset"`
}

// SignupResponse response body containing new user id.
type SignupResponse struct {
	ID int64 `json:"id"`
}

func (h *Handler) Signup(w http.ResponseWriter, r *http.Request) {
	var req SignupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.logger.Debugw("invalid signup payload", "err", err)
		h.writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid payload"})
		return
	}
	id, err := h.svc.SignupUser(r.Context(), req.Username, req.Email, req.Password, req.UserType, req.MustReset)
	if err != nil {
		h.logger.Warnw("signup failed", "err", err)
		h.writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "signup failed"})
		return
	}
	h.writeJSON(w, http.StatusCreated, SignupResponse{ID: id})
}

// LoginRequest login payload.
type LoginRequest struct {
	Identifier string `json:"identifier"`
	Password   string `json:"password"`
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var req LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		h.logger.Debugw("invalid login payload", "err", err)
		h.writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid payload"})
		return
	}
	view, err := h.svc.AuthenticatePassword(r.Context(), req.Identifier, req.Password)
	if err != nil {
		h.logger.Debugw("login failed", "err", err)
		// map common errors to status codes
		switch err {
		case ErrBadCredentials, ErrMustResetPassword:
			h.writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid credentials"})
		case ErrLocked:
			h.writeJSON(w, http.StatusForbidden, map[string]string{"error": "account locked"})
		case ErrDisabled:
			h.writeJSON(w, http.StatusForbidden, map[string]string{"error": "account disabled"})
		default:
			h.writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "login failed"})
		}
		return
	}
	// Return minimal auth view as JSON.
	h.writeJSON(w, http.StatusOK, view)
}

func (h *Handler) writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// expose entity type for handler responses
var _ = entity.MinimalAuthView{}
