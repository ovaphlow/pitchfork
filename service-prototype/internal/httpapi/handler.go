// Package httpapi provides the HTTP routing layer of prototyped: the
// unified API route prefix, a configurable CORS middleware and JSON error
// responses following the repository convention { "error": "<message>" }.
package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
)

// prototypePrefix is the unified API prefix. It follows the repository
// route convention /crate-api/<module>/v1/<resource> with the module fixed
// to "prototype" for this service.
const prototypePrefix = "/crate-api/prototype/v1"

type healthResponse struct {
	Status string `json:"status"`
}

// NewMux builds the route mux, applies the CORS middleware with the given
// allow list, and serves healthz through the unified resource route.
// Routes:
//
//	GET  /crate-api/prototype/v1/healthz  -> JSON health
//	GET  /crate-api/prototype/v1/{resource} -> 404 JSON for unknown resources
//	any  other path                       -> 404 JSON
//	any  non-GET on a known resource path -> 405 JSON with Allow: GET
func NewMux(allowedOrigins []string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc(prototypePrefix+"/{resource}", handleResource)
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, "not found")
	})
	return corsMiddleware(mux, allowedOrigins)
}

// handleResource serves resources under the unified prefix. GET healthz
// returns the JSON health payload; other resources are not implemented yet
// and yield a JSON 404 (later cards register further resources).
func handleResource(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if r.PathValue("resource") == "healthz" {
		writeJSON(w, http.StatusOK, healthResponse{Status: "ok"})
		return
	}
	writeError(w, http.StatusNotFound, "resource not found")
}

// corsMiddleware wraps the mux with CORS handling. Requests carrying an
// Origin that is in the allow list get Access-Control-Allow-Origin; allowed
// OPTIONS preflights short-circuit with 204 and the CORS headers. Requests
// with a disallowed origin, or without an Origin, are passed through
// untouched (a no-Origin OPTIONS falls back to the mux, which yields the
// normal 405 with Allow).
func corsMiddleware(next http.Handler, allowedOrigins []string) http.Handler {
	allowed := make(map[string]bool, len(allowedOrigins))
	for _, origin := range allowedOrigins {
		allowed[origin] = true
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := strings.TrimSpace(r.Header.Get("Origin"))
		if origin == "" || !allowed[origin] {
			next.ServeHTTP(w, r)
			return
		}
		header := w.Header()
		header.Set("Access-Control-Allow-Origin", origin)
		header.Add("Vary", "Origin")
		if r.Method == http.MethodOptions {
			header.Set("Access-Control-Allow-Methods", "GET, OPTIONS")
			header.Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
