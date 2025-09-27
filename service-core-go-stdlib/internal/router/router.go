package router

import (
	"net/http"
	"time"

	"go.uber.org/zap"

	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting"
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
func RegisterRoutes(logger *zap.SugaredLogger) http.Handler {
	mux := http.NewServeMux()

	// health
	mux.HandleFunc("GET /pitchfork-api-core/health", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	// setting routes
	settingHandler := setting.NewHandler(logger)
	mux.HandleFunc("GET /pitchfork-api-core/settings", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		settingHandler.List(w, r)
	})

	// wrap with security headers middleware then logging middleware
	handler := LoggingMiddleware(logger)(SecurityHeadersMiddleware()(mux))
	return handler
}
