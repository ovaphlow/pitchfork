package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"net/netip"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/nexusclient"
)

// Pinger is the database capability required by the readiness endpoint.
type Pinger interface {
	Ping(context.Context) error
}

// Deps contains the dependencies mounted on the HTTP router.
type Deps struct {
	Logger           *slog.Logger
	DB               Pinger
	AllowedOrigins   []string
	ReadinessTimeout time.Duration
	Identity         *identityclient.Client
	Nexus            *nexusclient.Client
	// Files is a second shared-API client with a longer timeout: uploads read
	// the request body and hash it on disk, which outlasts the permissions call.
	Files          *nexusclient.Client
	TrustedProxies []netip.Prefix
	Organizations  OrganizationStore
	Positions      PositionStore
	Intents        IntentStore
	// EmailLinkRedirectURI is the Agora UI landing page for mail links; it must
	// match platformd's PLATFORMD_EMAIL_LINK_REDIRECT_URIS exactly.
	EmailLinkRedirectURI string
}

type statusResponse struct {
	Service string `json:"service"`
	Status  string `json:"status"`
	Time    string `json:"time"`
}

type readyResponse struct {
	Status string `json:"status"`
}

// New builds the native net/http router and its middleware chain.
func New(deps Deps) http.Handler {
	if deps.Logger == nil {
		deps.Logger = slog.Default()
	}
	if deps.ReadinessTimeout <= 0 {
		deps.ReadinessTimeout = 2 * time.Second
	}

	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, readyResponse{Status: "ok"})
	})

	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if deps.DB == nil {
			writeError(w, http.StatusServiceUnavailable, "database is not configured")
			return
		}

		ctx, cancel := context.WithTimeout(r.Context(), deps.ReadinessTimeout)
		defer cancel()

		if err := deps.DB.Ping(ctx); err != nil {
			deps.Logger.Warn("readiness check failed", "request_id", requestIDFrom(r.Context()), "error", err)
			writeError(w, http.StatusServiceUnavailable, "database unavailable")
			return
		}

		writeJSON(w, http.StatusOK, readyResponse{Status: "ready"})
	})

	mux.HandleFunc("GET /crate-api/agora/v1/status", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, statusResponse{
			Service: "agora-api",
			Status:  "ok",
			Time:    time.Now().UTC().Format(time.RFC3339),
		})
	})

	mux.HandleFunc("POST /crate-api/agora/v1/auth/email-link", deps.requestEmailLink)
	mux.HandleFunc("POST /crate-api/agora/v1/auth/email-link/exchange", deps.exchangeEmailLink)
	mux.HandleFunc("POST /crate-api/agora/v1/auth/login", deps.login)
	mux.HandleFunc("GET /crate-api/agora/v1/auth/session", deps.currentSession)
	mux.HandleFunc("DELETE /crate-api/agora/v1/auth/session", deps.logout)

	mux.HandleFunc("GET /crate-api/agora/v1/organizations", deps.listOrganizations)
	mux.HandleFunc("POST /crate-api/agora/v1/organizations", deps.createOrganization)
	mux.HandleFunc("GET /crate-api/agora/v1/organizations/mine", deps.listMyOrganizations)
	mux.HandleFunc("GET /crate-api/agora/v1/organizations/{id}", deps.getOrganization)
	mux.HandleFunc("PATCH /crate-api/agora/v1/organizations/{id}", deps.updateOrganization)
	mux.HandleFunc("POST /crate-api/agora/v1/organizations/{id}/review", deps.reviewOrganization)
	mux.HandleFunc("GET /crate-api/agora/v1/organizations/{id}/members", deps.listOrganizationMembers)
	mux.HandleFunc("POST /crate-api/agora/v1/organizations/{id}/members", deps.addOrganizationMember)
	mux.HandleFunc("DELETE /crate-api/agora/v1/organizations/{id}/members/{subject_id}", deps.removeOrganizationMember)
	mux.HandleFunc("GET /crate-api/agora/v1/organizations/{id}/positions", deps.listOrganizationPositions)

	mux.HandleFunc("POST /crate-api/agora/v1/files", deps.uploadFile)
	mux.HandleFunc("GET /crate-api/agora/v1/files/{id}/content", deps.getFile)

	mux.HandleFunc("PUT /crate-api/agora/v1/me/intent", deps.saveMyIntent)

	// 管理端：求职用户（平台账号）查询与启用/禁用。
	mux.HandleFunc("GET /crate-api/agora/v1/admin/subjects", deps.adminListSubjects)
	mux.HandleFunc("PATCH /crate-api/agora/v1/admin/subjects/{id}", deps.adminUpdateSubject)

	mux.HandleFunc("GET /crate-api/agora/v1/positions", deps.listPositions)
	mux.HandleFunc("POST /crate-api/agora/v1/positions", deps.createPosition)
	mux.HandleFunc("GET /crate-api/agora/v1/positions/{id}", deps.getPosition)
	mux.HandleFunc("PATCH /crate-api/agora/v1/positions/{id}", deps.updatePosition)
	mux.HandleFunc("DELETE /crate-api/agora/v1/positions/{id}", deps.deletePosition)
	mux.HandleFunc("POST /crate-api/agora/v1/positions/{id}/status", deps.setPositionStatus)

	handler := cors(deps.AllowedOrigins, mux)
	handler = securityHeaders(handler)
	handler = recovery(deps.Logger, handler)
	handler = accessLog(deps.Logger, handler)
	handler = requestID(handler)
	return handler
}
