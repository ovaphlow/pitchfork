package router

import (
	"context"
	"net/http"
	"time"

	"github.com/jmoiron/sqlx"
	"go.uber.org/zap"

	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/oidc"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/user"
)

// loggingResponseWriter wraps http.ResponseWriter to capture status and size.
type loggingResponseWriter struct {
	http.ResponseWriter
	status int
	size   int
}

func (lrw *loggingResponseWriter) WriteHeader(code int) {
	lrw.status = code
	lrw.ResponseWriter.WriteHeader(code)
}

func (lrw *loggingResponseWriter) Write(b []byte) (int, error) {
	if lrw.status == 0 {
		lrw.status = http.StatusOK
	}
	n, err := lrw.ResponseWriter.Write(b)
	lrw.size += n
	return n, err
}

// LoggingMiddleware returns a middleware that logs requests at debug level using the provided sugared logger.
func LoggingMiddleware(logger *zap.SugaredLogger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			lrw := &loggingResponseWriter{ResponseWriter: w}
			next.ServeHTTP(lrw, r)
			dur := time.Since(start)
			// ensure status is set
			status := lrw.status
			if status == 0 {
				status = http.StatusOK
			}
			logger.Debugw("http request",
				"method", r.Method,
				"path", r.URL.Path,
				"remote", r.RemoteAddr,
				"status", status,
				"duration_ms", float64(dur.Microseconds())/1000.0,
				"size", lrw.size,
			)
		})
	}
}

// SecurityHeadersMiddleware returns a middleware that sets common HTTP security headers.
// It is intentionally simple and conservative so it works with most setups.
func SecurityHeadersMiddleware() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Prevent MIME sniffing
			w.Header().Set("X-Content-Type-Options", "nosniff")

			// Clickjacking protection
			w.Header().Set("X-Frame-Options", "DENY")

			// Referrer policy
			w.Header().Set("Referrer-Policy", "no-referrer-when-downgrade")

			// Permissions policy (formerly Feature-Policy) - tighten common features
			// allow none for camera, microphone, geolocation by default
			w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

			// Basic Content-Security-Policy - block mixed content and restrict sources to self by default
			// Keep this conservative; callers may opt to override with more specific policy downstream.
			if w.Header().Get("Content-Security-Policy") == "" {
				w.Header().Set("Content-Security-Policy", "default-src 'self'; object-src 'none'; base-uri 'self';")
			}

			// HSTS - instruct browsers to use HTTPS for future requests. Only set if request is over TLS.
			if r.TLS != nil {
				// 30 days by default
				w.Header().Set("Strict-Transport-Security", "max-age=2592000; includeSubDomains")
			}

			next.ServeHTTP(w, r)
		})
	}
}

// RegisterRoutes mounts HTTP handlers using the standard library's http.ServeMux.
// This keeps the project stdlib-only while keeping wiring simple and testable.
func RegisterRoutes(logger *zap.SugaredLogger, db *sqlx.DB) http.Handler {
	mux := http.NewServeMux()

	// Liveness: root level /health for Consul sidecar HTTP check
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	// Legacy / namespaced health (retain existing path for any clients already using it)
	mux.HandleFunc("GET /pitchfork-api/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	// Readiness: attempts a lightweight DB ping (with short timeout)
	mux.HandleFunc("GET /ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 500*time.Millisecond)
		defer cancel()
		if err := db.DB.PingContext(ctx); err != nil {
			logger.Warnw("readiness ping failed", "error", err)
			http.Error(w, "not ready", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ready"))
	})

	// setting routes
	settingHandler := setting.NewHandler(logger)
	mux.HandleFunc("GET /pitchfork-api/settings", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		settingHandler.List(w, r)
	})

	// GET single setting by id (path suffix)
	mux.HandleFunc("GET /pitchfork-api/settings/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		settingHandler.Get(w, r)
	})

	// Create setting
	mux.HandleFunc("POST /pitchfork-api/settings", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		settingHandler.Create(w, r)
	})

	// Update setting
	mux.HandleFunc("PUT /pitchfork-api/settings/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		settingHandler.Update(w, r)
	})

	// Delete setting
	mux.HandleFunc("DELETE /pitchfork-api/settings/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		settingHandler.Delete(w, r)
	})

	// id route - generate ids
	mux.HandleFunc("GET /pitchfork-api/id", func(w http.ResponseWriter, r *http.Request) {
		settingHandler.ID(w, r)
	})

	// user routes (signup / login)
	userHandler := user.NewHandler(db, logger)
	mux.HandleFunc("POST /pitchfork-api/signup", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		userHandler.Signup(w, r)
	})
	mux.HandleFunc("POST /pitchfork-api/login", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		userHandler.Login(w, r)
	})

	// OIDC endpoints
	oidcHandler, err := oidc.NewHandler(db, "http://localhost:8431")
	if err == nil {
		mux.HandleFunc("GET /.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
			oidcHandler.Discovery(w, r)
		})
		mux.HandleFunc("GET /jwks.json", func(w http.ResponseWriter, r *http.Request) {
			oidcHandler.JWKS(w, r)
		})
		mux.HandleFunc("POST /token", func(w http.ResponseWriter, r *http.Request) {
			oidcHandler.Token(w, r)
		})
		// OAuth2 token revocation - RFC 7009
		mux.HandleFunc("POST /revoke", func(w http.ResponseWriter, r *http.Request) {
			oidcHandler.Revoke(w, r)
		})
		// OAuth2 token introspection - RFC 7662
		mux.HandleFunc("POST /introspect", func(w http.ResponseWriter, r *http.Request) {
			oidcHandler.Introspect(w, r)
		})
		mux.HandleFunc("GET /userinfo", func(w http.ResponseWriter, r *http.Request) {
			oidcHandler.Userinfo(w, r)
		})
	} else {
		logger.Warnw("failed to initialize oidc handler", "err", err)
	}

	// wrap with security headers middleware then logging middleware
	handler := LoggingMiddleware(logger)(SecurityHeadersMiddleware()(mux))
	return handler
}
